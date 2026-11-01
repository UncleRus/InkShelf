package ru.unclerus.library.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.unclerus.library.App;
import ru.unclerus.library.FtsSmokeTest;
import ru.unclerus.library.Logger;
import ru.unclerus.library.R;
import ru.unclerus.library.db.AuthorDao;
import ru.unclerus.library.db.BookDao;
import ru.unclerus.library.db.CountDao;
import ru.unclerus.library.db.GenreDao;
import ru.unclerus.library.db.SearchDao;
import ru.unclerus.library.db.SeriesDao;
import ru.unclerus.library.open.BookActions;
import ru.unclerus.library.scan.IndexerService;
import ru.unclerus.library.scan.RootRepo;
import ru.unclerus.library.util.AccessPrompt;
import ru.unclerus.library.util.Dialogs;
import ru.unclerus.library.scan.ProgressBus;
import ru.unclerus.library.util.FileAccess;
import ru.unclerus.library.util.Lang;
import ru.unclerus.library.util.Prefs;
import ru.unclerus.library.util.Text;

/**
 * S1 «Главный»: строка поиска, 6 вкладок, постраничный список и алфавитный
 * указатель.
 *
 * <p>M4: вкладки читают оконные курсоры из БД; группированные вкладки (авторы,
 * «Все книги» по названию, дата, история) показывают заголовки разделов;
 * размер страницы считается <b>по составу строк</b> (заголовок + элементы) и
 * повторяет заголовок разрезанной группы; работают алфавитный указатель,
 * свайп-листание и переход к странице (D12).
 *
 * <p>M5: строка поиска. При непустом запросе содержимое активной вкладки
 * заменяется плоским постраничным списком результатов (V8) с подсветкой
 * совпадений; внизу — «Найдено: N»; крестик/back очищают запрос и возвращают
 * список вкладки. Своей записи в бэкстеке поиск не создаёт.
 */
public final class MainActivity extends ThemedActivity implements ProgressBus.Listener {

    private static final int[] TAB_LABELS = {
            R.string.tab_authors, R.string.tab_genres, R.string.tab_series,
            R.string.tab_date, R.string.tab_history, R.string.tab_all_books,
    };

    /** Иконки вкладок — в том же порядке, что TAB_LABELS. */
    private static final int[] TAB_ICONS = {
            R.drawable.ic_authors, R.drawable.ic_genres, R.drawable.ic_series,
            R.drawable.ic_date, R.drawable.ic_history, R.drawable.ic_books,
    };

    private static final int TAB_AUTHORS = 0;
    private static final int TAB_GENRES = 1;
    private static final int TAB_SERIES = 2;
    private static final int TAB_DATE = 3;
    private static final int TAB_HISTORY = 4;
    private static final int TAB_ALL_BOOKS = 5;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_ADD_ROOT = 1;
    private static final int ACTION_SCAN = 2;

    /** Баннер состояния доступа/папок над списком (ST5/ST9). */
    private static final int BANNER_NONE = 0;
    private static final int BANNER_ROOTS = 1;  // ST5 → S9 «Папки с книгами»
    private static final int BANNER_ACCESS = 2; // ST9 → D2 «Доступ к файлам»

    private static final int REQ_FOLDER = 1;
    private static final int REQ_SAF_TREE = 2;
    private static final int REQ_STORAGE = 3;

    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private final Handler mUi = new Handler(Looper.getMainLooper());

    private LinearLayout mTabs;
    private HorizontalScrollView mTabBar;
    private ImageButton mTabLeft;
    private ImageButton mTabRight;
    private ListView mList;
    private TextView mPageInfo;
    private View mPager;
    private View mEmpty;
    private ImageView mEmptyIcon;
    private TextView mEmptyText;
    private Button mEmptyBtn;
    private TextView mScanFooter;
    private ImageButton mAlphaBtn;
    private ImageButton mClearBtn;
    private TextView mSearchFooter;
    private EditText mSearch;
    private TextView mBanner;
    private int mBannerAction = BANNER_NONE;

    private final int[] mTabCount = new int[TAB_LABELS.length];
    /** Активная вкладка; по умолчанию «Авторы» (первый запуск или нет запомненной). */
    private int mTab = TAB_AUTHORS;
    private int mPage = 1;
    private int mAction = ACTION_NONE;

    /** Запрос поиска; пустая строка — режим вкладки (не поиска). */
    private String mQuery = "";
    /** Число найденных книг по текущему {@link #mQuery}. */
    private int mSearchTotal;

    private String mSort = BookDao.SORT_TITLE;
    private Sections mSections;
    private PagePlan mPlan;
    private RowList mRows;
    private long[] mDateBounds;
    private int mViewport;

    private boolean mLoaded;
    private boolean mDiagnosticsLogged;
    private boolean mScanActive;
    private Cursor mCursor;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        // Режим сортировки «Все книги» помнится между запусками (отдельно от жанра/даты).
        String savedSort = App.prefs().sort(Prefs.K_SORT_BOOKS);
        if (BookDao.isValidSort(savedSort)) {
            mSort = savedSort;
        }

        // Последняя открытая вкладка помнится; при первом запуске (или сбое
        // диапазона) открываем «Авторы».
        int savedTab = App.prefs().tab();
        if (savedTab >= 0 && savedTab < TAB_LABELS.length) {
            mTab = savedTab;
        }

        mTabs = findViewById(R.id.tabs);
        mTabBar = findViewById(R.id.tab_bar);
        mTabLeft = findViewById(R.id.tab_left);
        mTabRight = findViewById(R.id.tab_right);
        mList = findViewById(R.id.list);
        mPageInfo = findViewById(R.id.page_info);
        mPager = findViewById(R.id.pager);
        mEmpty = findViewById(R.id.empty);
        mEmptyIcon = findViewById(R.id.empty_icon);
        mEmptyText = findViewById(R.id.empty_text);
        mEmptyBtn = findViewById(R.id.empty_btn);
        mScanFooter = findViewById(R.id.scan_footer);
        mAlphaBtn = findViewById(R.id.btn_alpha);
        mClearBtn = findViewById(R.id.btn_clear);
        mSearchFooter = findViewById(R.id.search_footer);
        mSearchFooter.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        mSearchFooter.setCompoundDrawableTintList(getColorStateList(R.color.ink));

        // Баннер состояния (ST5 «часть книг недоступна» / ST9 «нет доступа»).
        mBanner = findViewById(R.id.banner);
        mBanner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBannerClick();
            }
        });

        mSearch = findViewById(R.id.search);
        mSearch.setHint(R.string.search_hint);
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onSearchChanged(s == null ? "" : s.toString());
            }
        });
        mSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                hideKeyboard(v);
                return false;
            }
        });
        mClearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearSearchField();
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        findViewById(R.id.pg_first).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goPage(1);
            }
        });
        findViewById(R.id.pg_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goPage(mPage - 1);
            }
        });
        findViewById(R.id.pg_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goPage(mPage + 1);
            }
        });
        findViewById(R.id.pg_last).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goPage(Integer.MAX_VALUE);
            }
        });
        mPageInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showJumpDialog();
            }
        });
        mEmptyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onEmptyAction();
            }
        });
        mAlphaBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAlphaDialog();
            }
        });

        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onRowClick(position, id);
            }
        });
        mList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                return onRowLongClick(position, id); // D6 — только для книг
            }
        });
        SwipePager.attach(mList, new SwipePager.Target() {
            @Override
            public void onSwipeForward() {
                swipePage(1);
            }

            @Override
            public void onSwipeBack() {
                swipePage(-1);
            }
        });

        // Размер страницы зависит от высоты области списка — узнаём её, когда
        // она посчитана, и пересчитываем разбиение.
        mList.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int h = mList.getHeight();
                if (h != mViewport) {
                    mViewport = h;
                    mPlan = null;
                    // См. GroupBooksActivity: setAdapter внутри layout-прохода
                    // AbsListView блокирует собственный requestLayout → пустой
                    // список. Пересборку делаем после разметки.
                    mList.post(new Runnable() {
                        @Override
                        public void run() {
                            renderPage();
                        }
                    });
                }
            }
        });

        mTabBar.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                updateTabArrows();
            }
        });
        mTabLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollTabsByOne(false);
            }
        });
        mTabRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollTabsByOne(true);
            }
        });

        buildTabs();
        selectTab(mTab);
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        ProgressBus.subscribe(this);
    }

    @Override
    protected void onStop() {
        ProgressBus.unsubscribe(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Настройки (шрифт/плотность) могли измениться — метрики строк и разбиение
        // страницы пересчитываются заново.
        mPlan = null;
        refresh();
        updateBanner();
        // Первый запуск: разовый выбор читалки по умолчанию (D1·b).
        BookActions.maybeAskDefault(this);
    }

    @Override
    protected void onDestroy() {
        closeCursor();
        mBg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER) {
            refresh();
            updateBanner();
        } else if (requestCode == REQ_SAF_TREE) {
            final String uri = FileAccess.persistTree(this, data);
            if (uri != null) {
                AccessPrompt.addSafRoot(uri, new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, R.string.toast_root_added,
                                Toast.LENGTH_SHORT).show();
                        updateBanner();
                        refresh();
                    }
                });
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            updateBanner();
            refresh();
        }
    }

    // ------------------------------------------------------------------
    // Данные
    // ------------------------------------------------------------------

    /** Перечитать счётчики/разделы активной вкладки либо число совпадений (фоном), затем перерисовать. */
    private void refresh() {
        final int tab = mTab;
        final String sort = mSort;
        final String query = mQuery;
        mBg.execute(new Runnable() {
            @Override
            public void run() {
                int roots = 0;
                int books = 0;
                final int[] counts = new int[TAB_LABELS.length];
                Sections sections = null;
                long[] dateBounds = null;
                int found = 0;
                try {
                    SQLiteDatabase db = App.db().getReadableDatabase();
                    if (query.isEmpty()) {
                        roots = CountDao.roots(db);
                        books = CountDao.books(db);
                        for (int i = 0; i < counts.length; i++) {
                            counts[i] = CountDao.tab(db, i);
                        }
                        sections = buildSections(db, tab, sort, counts);
                        dateBounds = DateGroups.bounds(System.currentTimeMillis());
                    } else {
                        long t0 = System.nanoTime();
                        found = SearchDao.count(db, query);
                        Logger.i("Search", "«" + query + "»: найдено " + found + ", подсчёт "
                                + ((System.nanoTime() - t0) / 1000000) + " мс");
                    }
                    if (!mDiagnosticsLogged) {
                        mDiagnosticsLogged = true;
                        FtsSmokeTest.run();
                        Logger.i("DbCheck", App.dbReport().replace("\n", " | "));
                    }
                } catch (Throwable t) {
                    Logger.e("MainActivity", "не удалось прочитать БД", t);
                }
                final int fr = roots;
                final int fb = books;
                final Sections fs = sections;
                final long[] fbounds = dateBounds;
                final int ffound = found;
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (tab != mTab || !sort.equals(mSort) || !query.equals(mQuery)) {
                            return; // вкладку/запрос уже сменили — результат устарел
                        }
                        mDateBounds = fbounds;
                        mSections = fs;
                        mSearchTotal = ffound;
                        mPlan = null;
                        applyCounts(fr, fb, counts);
                    }
                });
            }
        });
    }

    /** Разделы для вкладки (для плоских — {@code null}). */
    private Sections buildSections(SQLiteDatabase db, int tab, String sort, int[] counts) {
        switch (tab) {
            case TAB_AUTHORS: {
                Cursor c = AuthorDao.sections(db);
                try {
                    return Sections.of(c, 0, 1);
                } finally {
                    c.close();
                }
            }
            case TAB_DATE: {
                long[] b = DateGroups.bounds(System.currentTimeMillis());
                int[] cnt = BookDao.dateBucketCounts(db, b);
                String[] labels = new String[DateGroups.COUNT];
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = getString(DateGroups.labelRes(i));
                }
                return Sections.of(labels, cnt);
            }
            case TAB_HISTORY:
                return Sections.single(getString(R.string.history_recently_opened), counts[TAB_HISTORY]);
            case TAB_ALL_BOOKS:
                if (BookDao.SORT_TITLE.equals(sort)) {
                    Cursor c = BookDao.titleSections(db);
                    try {
                        return Sections.of(c, 0, 1);
                    } finally {
                        c.close();
                    }
                }
                return null;
            default:
                return null;
        }
    }

    private void applyCounts(int roots, int books, int[] counts) {
        System.arraycopy(counts, 0, mTabCount, 0, mTabCount.length);
        mLoaded = true;

        // В режиме поиска списки источников (корни/индекс) не учитываем: пустой
        // результат — это ST3, а не «нет корней»/«индекс не построен».
        if (!searching()) {
            if (roots == 0) {
                showEmpty(R.string.state_no_roots, R.drawable.ic_folder_plus,
                        R.string.action_add_folder, ACTION_ADD_ROOT);
                return;
            }
            if (books == 0) {
                showEmpty(R.string.state_index_empty, R.drawable.ic_refresh,
                        R.string.action_start_scan, ACTION_SCAN);
                return;
            }
        }
        renderPage();
    }

    // ------------------------------------------------------------------
    // Вкладки
    // ------------------------------------------------------------------

    private void buildTabs() {
        mTabs.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < TAB_LABELS.length; i++) {
            TextView t = (TextView) inf.inflate(R.layout.tab, mTabs, false);
            t.setText(TAB_LABELS[i]);
            t.setCompoundDrawablesWithIntrinsicBounds(TAB_ICONS[i], 0, 0, 0);
            t.setCompoundDrawableTintList(getColorStateList(R.color.tab_text));
            final int idx = i;
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(idx);
                }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    onTabLongClick(idx);
                    return true;
                }
            });
            mTabs.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** Только подсветка вкладки, без перерисовки списка. */
    private void selectTab(int idx) {
        for (int i = 0; i < mTabs.getChildCount(); i++) {
            mTabs.getChildAt(i).setSelected(i == idx);
        }
        // Шесть вкладок при крупном шрифте не помещаются — активная может быть
        // за краем. Прокручиваем панель так, чтобы активная вкладка была видна,
        // и обновляем стрелки прокрутки.
        final View tab = mTabs.getChildAt(idx);
        if (tab == null) {
            return;
        }
        mTabBar.post(new Runnable() {
            @Override
            public void run() {
                int w = mTabBar.getWidth();
                int cur = mTabBar.getScrollX();
                if (tab.getLeft() < cur) {
                    mTabBar.scrollTo(tab.getLeft(), 0);
                } else if (tab.getRight() > cur + w) {
                    mTabBar.scrollTo(tab.getRight() - w, 0);
                }
                updateTabArrows();
            }
        });
    }

    /** Показать стрелку у того края, где есть скрытые вкладки. */
    private void updateTabArrows() {
        if (mTabBar == null || mTabLeft == null || mTabRight == null) {
            return;
        }
        int w = mTabBar.getWidth();
        int content = mTabs.getWidth();
        if (w <= 0 || content <= 0) {
            return;
        }
        int cur = mTabBar.getScrollX();
        int max = Math.max(0, content - w);
        mTabLeft.setVisibility(cur > 0 ? View.VISIBLE : View.GONE);
        mTabRight.setVisibility(cur < max ? View.VISIBLE : View.GONE);
    }

    /**
     * Сдвиг панели вкладок на одну скрытую вкладку: вправо — к ближайшей
     * вкладке, спрятанной справа; влево — к ближайшей скрытой слева.
     */
    private void scrollTabsByOne(boolean right) {
        int w = mTabBar.getWidth();
        int content = mTabs.getWidth();
        int max = Math.max(0, content - w);
        if (max <= 0) {
            return;
        }
        int cur = mTabBar.getScrollX();
        int target = cur;
        if (right) {
            for (int i = 0; i < mTabs.getChildCount(); i++) {
                View c = mTabs.getChildAt(i);
                if (c.getRight() > cur + w) {
                    target = c.getRight() - w;
                    break;
                }
            }
        } else {
            for (int i = mTabs.getChildCount() - 1; i >= 0; i--) {
                View c = mTabs.getChildAt(i);
                if (c.getLeft() < cur) {
                    target = c.getLeft();
                    break;
                }
            }
        }
        if (target < 0) {
            target = 0;
        }
        if (target > max) {
            target = max;
        }
        mTabBar.scrollTo(target, 0);
        updateTabArrows();
    }

    private void showTab(int idx) {
        boolean wasSearch = searching();
        if (idx == mTab && !wasSearch) {
            return;
        }
        if (wasSearch) {
            // Тап по вкладке выходит из поиска: query сбрасываем ДО setText, чтобы
            // watcher не запустил лишний refresh (он видит q == mQuery).
            mQuery = "";
            if (mSearch.getText().length() > 0) {
                mSearch.setText("");
            }
            mClearBtn.setVisibility(View.GONE);
            mSearchFooter.setVisibility(View.GONE);
        }
        mTab = idx;
        mPage = 1;
        mSections = null;
        mPlan = null;
        App.prefs().setTab(idx); // помним активную вкладку между запусками
        selectTab(idx);
        refresh();
    }

    // ------------------------------------------------------------------
    // Поиск (M5)
    // ------------------------------------------------------------------

    private boolean searching() {
        return !mQuery.isEmpty();
    }

    /** Реакция на изменение строки поиска: переключение вкладка↔поиск. */
    private void onSearchChanged(String raw) {
        String q = Text.collapse(raw);
        if (q.equals(mQuery)) {
            return;
        }
        boolean transition = mQuery.isEmpty() != q.isEmpty();
        mQuery = q;
        mPage = 1;
        mPlan = null;
        if (transition) {
            mSections = null; // состав строк меняется полностью
        }
        mClearBtn.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        refresh();
    }

    /** Очистить строку поиска (крестик/back). Watcher сам перерисует вкладку. */
    private void clearSearchField() {
        if (mSearch.getText().length() > 0) {
            mSearch.setText("");
        }
        hideKeyboard(mSearch);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    @Override
    public void onBackPressed() {
        if (searching()) {
            clearSearchField();
            return;
        }
        super.onBackPressed();
    }

    /** Долгий тап по вкладке — меню сортировки (D7/D8), где сортировка есть. */
    private void onTabLongClick(int idx) {
        if (searching()) {
            showTab(idx); // из поиска долгий тап просто переключает вкладку
            return;
        }
        if (idx == TAB_ALL_BOOKS) {
            showSortDialog();
        } else if (idx != mTab) {
            showTab(idx);
        }
    }

    private void showSortDialog() {
        final String[] values = BookDao.SORTABLE;
        final int[] labels = {
                R.string.sort_by_title, R.string.sort_by_author,
                R.string.sort_by_file_date, R.string.sort_by_index_date,
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(mSort)) {
                checked = i;
            }
        }
        String[] items = new String[labels.length];
        for (int i = 0; i < labels.length; i++) {
            items[i] = getString(labels[i]);
        }
        Dialogs.show(new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_sort)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mSort = values[which];
                        App.prefs().setSort(Prefs.K_SORT_BOOKS, mSort);
                        mPage = 1;
                        mSections = null;
                        mPlan = null;
                        d.dismiss();
                        refresh();
                    }
                }));
    }

    // ------------------------------------------------------------------
    // Страница
    // ------------------------------------------------------------------

    private void goPage(int page) {
        int pages = mPlan != null ? mPlan.pageCount() : 1;
        if (page < 1) {
            page = 1;
        }
        if (page > pages) {
            page = pages;
        }
        mPage = page;
        renderPage();
    }

    /**
     * Аппаратные кнопки листают список так же, как свайп (см. {@link HwKeys}):
     * верхняя — назад, нижняя — вперёд. Событие потребляем, чтобы оно не ушло в
     * изменение громкости, а удержание (повторы) игнорируем — иначе список убежит.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int delta = HwKeys.pageDelta(e.getKeyCode());
        if (delta != 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                swipePage(delta);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void swipePage(int delta) {
        int pages = mPlan != null ? mPlan.pageCount() : 1;
        int target = mPage + delta;
        if (target < 1) {
            toast(R.string.toast_first_page);
            return;
        }
        if (target > pages) {
            toast(R.string.toast_last_page);
            return;
        }
        mPage = target;
        renderPage();
    }

    /** Разметка строки-элемента вкладки (для метрик и адаптера). */
    private int itemLayout() {
        if (searching()) {
            return BookRowAdapter.rowLayout(this);
        }
        switch (mTab) {
            case TAB_AUTHORS:
            case TAB_GENRES:
            case TAB_SERIES:
                return R.layout.row_group;
            default:
                return BookRowAdapter.rowLayout(this);
        }
    }

    /** Построить разбиение на страницы под текущий состав строк. */
    private PagePlan buildPlan() {
        int divider = mList.getDividerHeight();
        if (searching()) {
            return PagePlan.flat(mSearchTotal,
                    ListMetrics.perPage(this, mViewport, itemLayout(), divider));
        }
        int total = mTabCount[mTab];
        int itemH = ListMetrics.rowHeight(this, itemLayout());
        if (mSections != null) {
            int headerH = ListMetrics.rowHeight(this, R.layout.row_section);
            return PagePlan.grouped(total, mSections, mViewport, headerH, itemH, divider);
        }
        int per = ListMetrics.perPage(this, mViewport, itemLayout(), divider);
        return PagePlan.flat(total, per);
    }

    private void renderPage() {
        if (!mLoaded) {
            return;
        }
        int total = searching() ? mSearchTotal : mTabCount[mTab];
        if (total <= 0) {
            closeCursor();
            if (searching()) {
                showEmpty(R.string.state_nothing_found, R.drawable.ic_search, 0, ACTION_NONE);
            } else if (mTab == TAB_HISTORY) {
                showEmpty(R.string.state_history_empty, R.drawable.ic_history, 0, ACTION_NONE);
            } else {
                showEmpty(R.string.state_nothing_found, R.drawable.ic_books, 0, ACTION_NONE);
            }
            return;
        }
        showList();

        if (mPlan == null) {
            mPlan = buildPlan();
        }
        int pages = mPlan.pageCount();
        if (mPage < 1) {
            mPage = 1;
        }
        if (mPage > pages) {
            mPage = pages;
        }
        int start = mPlan.start(mPage - 1);
        int end = mPlan.end(mPage - 1);
        int limit = end - start;

        closeCursor();
        try {
            SQLiteDatabase db = App.db().getReadableDatabase();
            if (searching()) {
                long t0 = System.nanoTime();
                mCursor = SearchDao.search(db, mQuery, start, limit);
                Logger.i("Search", "страница " + mPage + ": " + limit + " строк за "
                        + ((System.nanoTime() - t0) / 1000000) + " мс");
            } else {
                mCursor = queryTab(db, start, limit);
            }
        } catch (Throwable t) {
            Logger.e("MainActivity", "не удалось прочитать страницу", t);
            mCursor = null;
        }
        mRows = RowList.of(start, end, searching() ? null : mSections);
        mList.setAdapter(newAdapter(mCursor, mRows));
        // NFR-1: если длинное название сделало строку выше номинальной и
        // последняя строка не влезла — поднять высоту и перестроить страницу.
        mList.post(new Runnable() {
            @Override
            public void run() {
                if (PageFit.raiseIfOverflow(mList, mViewport, itemLayout())) {
                    mPlan = null;
                    renderPage();
                }
            }
        });
        mPageInfo.setText(getString(R.string.pagination_range,
                String.valueOf(start + 1), String.valueOf(end),
                String.valueOf(total)));
        if (searching()) {
            mSearchFooter.setText(getString(R.string.search_found_count, String.valueOf(total))
                    + " · " + getString(R.string.search_found_in));
        }
        updatePager(pages);
        updateAlpha();
    }

    private Cursor queryTab(SQLiteDatabase db, int offset, int limit) {
        switch (mTab) {
            case TAB_AUTHORS:
                return AuthorDao.page(db, offset, limit);
            case TAB_GENRES:
                return GenreDao.page(db, Lang.english(), offset, limit);
            case TAB_SERIES:
                return SeriesDao.page(db, offset, limit);
            case TAB_DATE:
                return BookDao.pageByDate(db, offset, limit);
            case TAB_HISTORY:
                return BookDao.pageHistory(db, offset, limit);
            default:
                return BookDao.pageAll(db, mSort, offset, limit);
        }
    }

    private BaseAdapter newAdapter(Cursor c, RowList rows) {
        if (searching()) {
            return new BookRowAdapter(this, c, rows, mQuery);
        }
        switch (mTab) {
            case TAB_AUTHORS:
                return new GroupRowAdapter(this, c, rows, R.drawable.ic_authors,
                        AuthorDao.C_NAME, AuthorDao.C_COUNT);
            case TAB_GENRES:
                return new GroupRowAdapter(this, c, rows, R.drawable.ic_genres,
                        GenreDao.C_NAME, GenreDao.C_COUNT);
            case TAB_SERIES:
                return new GroupRowAdapter(this, c, rows, R.drawable.ic_series,
                        SeriesDao.C_NAME, SeriesDao.C_COUNT);
            default:
                return new BookRowAdapter(this, c, rows);
        }
    }

    /** Доступность кнопок полосы страниц (e-ink: приглушение вместо спиннера). */
    private void updatePager(int pages) {
        // Одна страница — листать нечего: полосу не показываем вовсе.
        mPager.setVisibility(pages <= 1 ? View.GONE : View.VISIBLE);
        boolean hasPrev = mPage > 1;
        boolean hasNext = mPage < pages;
        setEnabled(R.id.pg_first, hasPrev);
        setEnabled(R.id.pg_prev, hasPrev);
        setEnabled(R.id.pg_next, hasNext);
        setEnabled(R.id.pg_last, hasNext);
    }

    private void setEnabled(int id, boolean on) {
        View v = findViewById(id);
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.35f);
    }

    // ------------------------------------------------------------------
    // Алфавитный указатель (V14 / D13)
    // ------------------------------------------------------------------

    /**
     * Кнопка указателя — только там, где в списке есть разделы (авторы; «Все книги»
     * при сортировке по названию). Полосы у края списка нет: кнопка в строке поиска
     * открывает сетку букв D13.
     */
    private void updateAlpha() {
        if (searching()) {
            mAlphaBtn.setVisibility(View.GONE);
            return;
        }
        boolean hasSections = mSections != null && (mTab == TAB_AUTHORS
                || (mTab == TAB_ALL_BOOKS && BookDao.SORT_TITLE.equals(mSort)));
        mAlphaBtn.setVisibility(hasSections ? View.VISIBLE : View.GONE);
    }

    private void jumpToSection(int section) {
        if (mSections == null || mPlan == null) {
            return;
        }
        mPage = mPlan.pageOf(mSections.start(section)) + 1;
        renderPage();
    }

    /** D13: сетка букв для крупного шрифта (полоса не помещается). */
    private void showAlphaDialog() {
        if (mSections == null) {
            return;
        }
        String order = Text.NUMKEY + " " + Text.LATIN + Text.CYRILLIC;
        final java.util.ArrayList<String> orderList = new java.util.ArrayList<String>();
        orderList.add(Text.NUMKEY);
        for (int i = 0; i < Text.LATIN.length(); i++) {
            orderList.add(String.valueOf(Text.LATIN.charAt(i)));
        }
        for (int i = 0; i < Text.CYRILLIC.length(); i++) {
            orderList.add(String.valueOf(Text.CYRILLIC.charAt(i)));
        }
        final java.util.HashSet<String> present = new java.util.HashSet<String>();
        for (String s : mSections.letters()) {
            present.add(s);
        }

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.dialog_pad);
        grid.setPadding(pad, pad, pad, pad);
        LinearLayout row = null;
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_alphabet_index)
                .setView(grid)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        int cellH = getResources().getDimensionPixelSize(R.dimen.touch_min);
        int cellGap = getResources().getDimensionPixelSize(R.dimen.space_xs);
        for (int i = 0; i < orderList.size(); i++) {
            if (i % 8 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final String letter = orderList.get(i);
            boolean on = present.contains(letter);
            TextView cell = new TextView(this);
            cell.setText(letter);
            cell.setGravity(android.view.Gravity.CENTER);
            cell.setTextSize(18f);
            cell.setTypeface(cell.getTypeface(), android.graphics.Typeface.BOLD);
            cell.setEnabled(on);
            cell.setAlpha(on ? 1f : 0.35f);
            if (on) {
                cell.setBackgroundResource(R.drawable.bg_badge);
            }
            cell.setMinHeight(cellH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(cellGap, cellGap, cellGap, cellGap);
            cell.setLayoutParams(lp);
            if (on) {
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        for (int s = 0; s < mSections.count(); s++) {
                            if (letter.equals(mSections.letter(s))) {
                                jumpToSection(s);
                                break;
                            }
                        }
                    }
                });
            }
            row.addView(cell);
        }
        // Неполный последний ряд добиваем пустыми ячейками: иначе оставшиеся
        // буквы растянутся на всю ширину и сетка «поедет» (как в макете D13).
        int rem = orderList.size() % 8;
        if (rem != 0 && row != null) {
            for (int k = rem; k < 8; k++) {
                View filler = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(cellGap, cellGap, cellGap, cellGap);
                filler.setLayoutParams(lp);
                row.addView(filler);
            }
        }
        Dialogs.show(dialog);
    }

    // ------------------------------------------------------------------
    // Клики по строкам
    // ------------------------------------------------------------------

    private void onRowClick(int position, long id) {
        if (mRows == null || mRows.isHeader(position)) {
            return;
        }
        if (searching()) {
            startActivity(new Intent(this, BookActivity.class).putExtra("book_id", id));
            return;
        }
        int pos = mRows.position(position);
        switch (mTab) {
            case TAB_AUTHORS:
                openGroup("author", id, cursorString(pos, AuthorDao.C_NAME), 0, 0);
                break;
            case TAB_GENRES:
                openGroup("genre", id, cursorString(pos, GenreDao.C_NAME), 0, 0);
                break;
            case TAB_SERIES:
                openGroup("series", id, cursorString(pos, SeriesDao.C_NAME), 0, 0);
                break;
            case TAB_DATE: {
                long mtime = cursorLong(pos, BookDao.C_MTIME);
                int bucket = DateGroups.bucket(mtime, mDateBounds);
                long[] range = DateGroups.range(bucket, mDateBounds);
                openGroup("date", 0, getString(DateGroups.labelRes(bucket)), range[0], range[1]);
                break;
            }
            default:
                startActivity(new Intent(this, BookActivity.class)
                        .putExtra("book_id", id));
                break;
        }
    }

    /**
     * Долгий тап по строке книги — контекстное меню D6 (макет 28). Для групповых
     * вкладок (авторы/жанры/серии) и заголовков разделов меню не показываем.
     */
    private boolean onRowLongClick(int position, long id) {
        if (mRows == null || mRows.isHeader(position) || id <= 0 || !bookRow()) {
            return false;
        }
        int pos = mRows.position(position);
        BookActions.showMenu(this, id, cursorString(pos, BookDao.C_TITLE),
                cursorString(pos, BookDao.C_PATH), cursorString(pos, BookDao.C_FORMAT));
        return true;
    }

    /** Строка текущей вкладки — книга (в групповых вкладках строки-группы). */
    private boolean bookRow() {
        if (searching()) {
            return true;
        }
        return mTab == TAB_DATE || mTab == TAB_HISTORY || mTab == TAB_ALL_BOOKS;
    }

    private void openGroup(String kind, long id, String title, long from, long to) {
        Intent i = new Intent(this, GroupBooksActivity.class)
                .putExtra(GroupBooksActivity.EXTRA_KIND, kind)
                .putExtra(GroupBooksActivity.EXTRA_ID, id)
                .putExtra(GroupBooksActivity.EXTRA_TITLE, title)
                .putExtra(GroupBooksActivity.EXTRA_FROM, from)
                .putExtra(GroupBooksActivity.EXTRA_TO, to);
        startActivity(i);
    }

    private String cursorString(int pos, int col) {
        return mCursor != null && mCursor.moveToPosition(pos) ? mCursor.getString(col) : "";
    }

    private long cursorLong(int pos, int col) {
        return mCursor != null && mCursor.moveToPosition(pos) ? mCursor.getLong(col) : 0L;
    }

    // ------------------------------------------------------------------
    // D12 «Перейти к странице»
    // ------------------------------------------------------------------

    private void showJumpDialog() {
        final int pages = mPlan != null ? mPlan.pageCount() : 1;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.dlg_page_jump_label));
        int pad = getResources().getDimensionPixelSize(R.dimen.dialog_pad);
        input.setPadding(pad, pad, pad, pad);
        Dialogs.show(new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_page_jump)
                .setMessage(getString(R.string.dlg_page_jump_range, String.valueOf(pages))
                        + " · " + getString(R.string.pagination_per_page_dynamic))
                .setView(input)
                .setPositiveButton(R.string.action_go, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int n = parsePage(input.getText().toString(), pages);
                        goPage(n);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private static int parsePage(String s, int pages) {
        try {
            int n = Integer.parseInt(s.trim());
            if (n < 1) {
                n = 1;
            }
            if (n > pages) {
                n = pages;
            }
            return n;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ------------------------------------------------------------------
    // Состояния
    // ------------------------------------------------------------------

    private void showEmpty(int textRes, int iconRes, int btnRes, int action) {
        mAction = action;
        mEmptyIcon.setImageResource(iconRes);
        mEmptyText.setText(textRes);
        if (btnRes != 0) {
            mEmptyBtn.setVisibility(View.VISIBLE);
            mEmptyBtn.setText(btnRes);
        } else {
            mEmptyBtn.setVisibility(View.GONE);
        }
        mEmpty.setVisibility(View.VISIBLE);
        mList.setVisibility(View.GONE);
        mPager.setVisibility(View.GONE);
        mAlphaBtn.setVisibility(View.GONE);
        mSearchFooter.setVisibility(View.GONE);
    }

    private void showList() {
        mAction = ACTION_NONE;
        mEmpty.setVisibility(View.GONE);
        mList.setVisibility(View.VISIBLE);
        mPager.setVisibility(View.VISIBLE);
        mSearchFooter.setVisibility(searching() ? View.VISIBLE : View.GONE);
    }

    private void onEmptyAction() {
        if (mAction == ACTION_ADD_ROOT) {
            startActivityForResult(new Intent(this, FolderBrowserActivity.class), REQ_FOLDER);
        } else if (mAction == ACTION_SCAN) {
            startScan();
        }
    }

    private void startScan() {
        Intent i = new Intent(this, IndexerService.class).setAction(IndexerService.ACTION_START);
        startService(i);
    }

    // ------------------------------------------------------------------
    // Баннеры ST5/ST9 и доступ к файлам (D2)
    // ------------------------------------------------------------------

    /**
     * Обновить баннер над списком: нет доступа к файлам (ST9) или часть папок
     * недоступна (ST5). Состояние читается из БД и из системного разрешения.
     */
    private void updateBanner() {
        if (mBanner == null) {
            return;
        }
        if (!FileAccess.hasAllFiles(this)) {
            showBanner(R.string.warn_no_file_access, BANNER_ACCESS);
            return;
        }
        boolean bad = false;
        try {
            SQLiteDatabase db = App.db().getReadableDatabase();
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM root WHERE volume_state IN (?,?)"
                            + " OR permission_state=?",
                    new String[]{RootRepo.VOL_MISSING, RootRepo.VOL_NO_ACCESS,
                            RootRepo.PERM_DENIED});
            try {
                bad = c.moveToFirst() && c.getInt(0) > 0;
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Logger.w("MainActivity", "не удалось прочитать состояние корней: " + t);
        }
        if (bad) {
            showBanner(R.string.warn_partial_unavailable, BANNER_ROOTS);
        } else {
            hideBanner();
        }
    }

    private void showBanner(int textRes, int action) {
        mBannerAction = action;
        mBanner.setText(textRes);
        mBanner.setVisibility(View.VISIBLE);
    }

    private void hideBanner() {
        mBannerAction = BANNER_NONE;
        mBanner.setVisibility(View.GONE);
    }

    private void onBannerClick() {
        if (mBannerAction == BANNER_ROOTS) {
            startActivity(new Intent(this, RootsActivity.class)); // ST5 → S9
        } else if (mBannerAction == BANNER_ACCESS) {
            showAccessDialog(); // ST9 → D2
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        updateBanner();
    }

    /** D2 «Доступ к файлам»: объяснение + «Позже» · «Выбрать папку» · «Разрешить доступ». */
    private void showAccessDialog() {
        AccessPrompt.show(this, new AccessPrompt.Actions() {
            @Override
            public void onGrant() {
                FileAccess.requestManage(MainActivity.this, REQ_STORAGE);
            }

            @Override
            public void onPickFolder() {
                pickTree();
            }
        });
    }

    private void pickTree() {
        try {
            startActivityForResult(FileAccess.openTree(), REQ_SAF_TREE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.state_no_file_manager, Toast.LENGTH_SHORT).show();
        }
    }

    private void closeCursor() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Прогресс индексации
    // ------------------------------------------------------------------

    @Override
    public void onProgress(ProgressBus.Progress p) {
        if (p.isRunning()) {
            mScanActive = true;
            mScanFooter.setVisibility(View.VISIBLE);
            mScanFooter.setText(getString(R.string.scan_progress_footer,
                    String.valueOf(p.scanned), String.valueOf(p.found), String.valueOf(p.added)));
            return;
        }
        mScanFooter.setVisibility(View.GONE);
        if (p.phase == ProgressBus.PHASE_DONE && mScanActive) {
            mScanActive = false;
            Toast.makeText(this, R.string.toast_index_updated, Toast.LENGTH_SHORT).show();
            updateBanner();
            refresh();
        } else if (p.phase == ProgressBus.PHASE_ERROR) {
            mScanActive = false;
            mScanFooter.setText(p.error == null ? "" : p.error);
            mScanFooter.setVisibility(View.VISIBLE);
        } else {
            mScanActive = false;
        }
    }
}
