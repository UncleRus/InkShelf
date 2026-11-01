package ru.unclerus.library.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.R;
import ru.unclerus.library.db.BookDao;
import ru.unclerus.library.open.BookActions;
import ru.unclerus.library.util.Dialogs;
import ru.unclerus.library.util.Prefs;

/**
 * S2 «Книги группы» (UI.md §2, макет 10): постраничный список книг
 * автора/жанра/серии/группы даты. Книги автора сгруппированы по сериям
 * («Без серии» — в конце), внутри — по номеру в серии, затем по названию;
 * серия — по номеру книги в серии (FR-2.3). Для автора и серии порядок
 * фиксирован (строки сортировки нет), для жанра/даты — выбор сортировки (D8).
 *
 * <p>Размер страницы — по высоте экрана и шрифту; свайп-листание; переход к
 * странице (D12).
 */
public final class GroupBooksActivity extends ThemedActivity {

    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_FROM = "from";
    public static final String EXTRA_TO = "to";

    public static final String KIND_AUTHOR = "author";
    public static final String KIND_GENRE = "genre";
    public static final String KIND_SERIES = "series";
    public static final String KIND_DATE = "date";

    private ListView mList;
    private TextView mPageInfo;
    private View mPager;
    private View mEmpty;
    private TextView mEmptyText;
    private TextView mSortText;

    private String mKind;
    private long mId;
    private long mFrom;
    private long mTo;
    private String mSort = BookDao.SORT_TITLE;
    private int mTotal;
    private int mPage = 1;
    private int mViewport;
    private PagePlan mPlan;
    private Cursor mCursor;
    private RowList mRows;

    /** Список автора группируется по сериям («Без серии» — в конце). */
    private boolean mGrouped;
    private Sections mSections;
    /** Первый onResume идёт сразу за onCreate — список уже построен. */
    private boolean mFirst = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_group_books);

        mKind = getIntent().getStringExtra(EXTRA_KIND);
        mId = getIntent().getLongExtra(EXTRA_ID, 0);
        mFrom = getIntent().getLongExtra(EXTRA_FROM, 0);
        mTo = getIntent().getLongExtra(EXTRA_TO, 0);
        mGrouped = KIND_AUTHOR.equals(mKind);
        if (KIND_SERIES.equals(mKind) || mGrouped) {
            mSort = BookDao.SORT_SERIES;
        } else {
            // Жанр и дата помнят свой режим сортировки между запусками (у автора и
            // серии порядок фиксирован, настройка не читается).
            String savedSort = App.prefs().sort(sortKey());
            if (BookDao.isValidSort(savedSort)) {
                mSort = savedSort;
            }
        }

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        ((TextView) findViewById(R.id.gb_title)).setText(title == null ? "" : title);
        mList = findViewById(R.id.gb_list);
        mPageInfo = findViewById(R.id.gb_page_info);
        mPager = findViewById(R.id.gb_pager);
        mEmpty = findViewById(R.id.gb_empty);
        mEmptyText = findViewById(R.id.gb_empty_text);
        mSortText = findViewById(R.id.gb_sort);
        // У автора (группы по сериям) и у серии (номер в серии) порядок фиксирован —
        // строку сортировки скрываем целиком.
        if (mGrouped || KIND_SERIES.equals(mKind)) {
            findViewById(R.id.gb_sort_box).setVisibility(View.GONE);
        }

        findViewById(R.id.gb_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mSortText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortDialog();
            }
        });
        findViewById(R.id.gb_pg_first).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goPage(1);
            }
        });
        findViewById(R.id.gb_pg_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goPage(mPage - 1);
            }
        });
        findViewById(R.id.gb_pg_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goPage(mPage + 1);
            }
        });
        findViewById(R.id.gb_pg_last).setOnClickListener(new View.OnClickListener() {
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
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                startActivity(new Intent(GroupBooksActivity.this, BookActivity.class)
                        .putExtra("book_id", id));
            }
        });
        mList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (id <= 0) {
                    return false; // заголовок раздела «Без серии» — не книга
                }
                int pos = mRows != null ? mRows.position(position) : position;
                BookActions.showMenu(GroupBooksActivity.this, id,
                        cursorString(pos, BookDao.C_TITLE), cursorString(pos, BookDao.C_PATH),
                        cursorString(pos, BookDao.C_FORMAT));
                return true;
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
        mList.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int h = mList.getHeight();
                if (h != mViewport) {
                    mViewport = h;
                    mPlan = null;
                    // setAdapter изнутри layout-прохода AbsListView гасит свой
                    // requestLayout (mInLayout/mBlockLayoutRequests), и список
                    // остаётся без строк. Переносим пересборку на после разметки.
                    mList.post(new Runnable() {
                        @Override
                        public void run() {
                            renderPage();
                        }
                    });
                }
            }
        });

        loadTotal();
        updateSortText();
        renderPage();
    }

    @Override
    protected void onDestroy() {
        closeCursor();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Вернулись из карточки: книгу могли удалить — пересчитываем группу и
        // заново разбиваем страницы (состав строк мог измениться).
        if (!mFirst) {
            loadTotal();
            mSections = null;
            mPlan = null;
            renderPage();
        }
        mFirst = false;
    }

    // ------------------------------------------------------------------
    // Данные
    // ------------------------------------------------------------------

    private void loadTotal() {
        try {
            SQLiteDatabase db = App.db().getReadableDatabase();
            if (KIND_AUTHOR.equals(mKind)) {
                mTotal = BookDao.countByAuthor(db, mId);
            } else if (KIND_GENRE.equals(mKind)) {
                mTotal = BookDao.countByGenre(db, mId);
            } else if (KIND_SERIES.equals(mKind)) {
                mTotal = BookDao.countBySeries(db, mId);
            } else {
                mTotal = BookDao.countByDateRange(db, mFrom, mTo);
            }
        } catch (Throwable t) {
            Logger.e("GroupBooks", "не удалось посчитать группу", t);
            mTotal = 0;
        }
    }

    private Cursor window(SQLiteDatabase db, int offset, int limit) {
        if (KIND_AUTHOR.equals(mKind)) {
            if (mGrouped) {
                return BookDao.pageByAuthorGrouped(db, mId, offset, limit);
            }
            return BookDao.pageByAuthor(db, mId, mSort, offset, limit);
        }
        if (KIND_GENRE.equals(mKind)) {
            return BookDao.pageByGenre(db, mId, mSort, offset, limit);
        }
        if (KIND_SERIES.equals(mKind)) {
            return BookDao.pageBySeries(db, mId, offset, limit);
        }
        return BookDao.pageByDateRange(db, mFrom, mTo, offset, limit);
    }

    /** Разделы книги автора по сериям (один раз на экран). */
    private Sections loadAuthorSections(SQLiteDatabase db) {
        Cursor c = null;
        try {
            c = BookDao.authorSeriesSections(db, mId, getString(R.string.series_none));
            return Sections.of(c, 0, 1);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Страница
    // ------------------------------------------------------------------

    private void renderPage() {
        if (mTotal <= 0) {
            closeCursor();
            mEmpty.setVisibility(View.VISIBLE);
            mEmptyText.setText(R.string.state_nothing_found);
            mList.setVisibility(View.GONE);
            mPager.setVisibility(View.GONE);
            return;
        }
        mEmpty.setVisibility(View.GONE);
        mList.setVisibility(View.VISIBLE);
        mPager.setVisibility(View.VISIBLE);

        if (mPlan == null) {
            int layout = BookRowAdapter.rowLayout(this);
            int itemH = ListMetrics.rowHeight(this, layout);
            int divider = mList.getDividerHeight();
            if (mGrouped) {
                if (mSections == null) {
                    try {
                        mSections = loadAuthorSections(App.db().getReadableDatabase());
                    } catch (Throwable t) {
                        Logger.e("GroupBooks", "не удалось построить разделы по сериям", t);
                        mSections = null;
                    }
                }
                int headerH = ListMetrics.rowHeight(this, R.layout.row_section);
                mPlan = PagePlan.grouped(mTotal, mSections, mViewport, headerH, itemH, divider);
            } else {
                mPlan = PagePlan.flat(mTotal,
                        ListMetrics.perPage(this, mViewport, layout, divider));
            }
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

        closeCursor();
        try {
            mCursor = window(App.db().getReadableDatabase(), start, end - start);
        } catch (Throwable t) {
            Logger.e("GroupBooks", "не удалось прочитать страницу", t);
            mCursor = null;
        }
        RowList rows = mGrouped ? RowList.of(start, end, mSections) : null;
        mRows = rows;
        mList.setAdapter(new BookRowAdapter(this, mCursor, rows));
        mPageInfo.setText(getString(R.string.pagination_range,
                String.valueOf(start + 1), String.valueOf(end), String.valueOf(mTotal)));
        updatePager(pages);
    }

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
            Toast.makeText(this, R.string.toast_first_page, Toast.LENGTH_SHORT).show();
            return;
        }
        if (target > pages) {
            Toast.makeText(this, R.string.toast_last_page, Toast.LENGTH_SHORT).show();
            return;
        }
        mPage = target;
        renderPage();
    }

    private void updatePager(int pages) {
        // Одна страница — листать нечего: полосу не показываем вовсе.
        mPager.setVisibility(pages <= 1 ? View.GONE : View.VISIBLE);
        boolean hasPrev = mPage > 1;
        boolean hasNext = mPage < pages;
        setEnabled(R.id.gb_pg_first, hasPrev);
        setEnabled(R.id.gb_pg_prev, hasPrev);
        setEnabled(R.id.gb_pg_next, hasNext);
        setEnabled(R.id.gb_pg_last, hasNext);
    }

    private void setEnabled(int id, boolean on) {
        View v = findViewById(id);
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.35f);
    }

    private void closeCursor() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
    }

    /** Значение колонки курсора страницы (для контекстного меню D6). */
    private String cursorString(int pos, int col) {
        return mCursor != null && mCursor.moveToPosition(pos) ? mCursor.getString(col) : "";
    }

    // ------------------------------------------------------------------
    // Сортировка (D8) и переход к странице (D12)
    // ------------------------------------------------------------------

    /**
     * Ключ настройки режима сортировки этого контекста. В S2 настраиваются только
     * жанр и группа даты — у них отдельные ключи, чтобы выбор не влиял друг на друга.
     */
    private String sortKey() {
        return KIND_DATE.equals(mKind) ? Prefs.K_SORT_DATE : Prefs.K_SORT_GENRE;
    }

    private void updateSortText() {
        int label;
        if (KIND_SERIES.equals(mKind)) {
            label = R.string.sort_by_series_number;
        } else if (mGrouped) {
            label = R.string.sort_by_series;
        } else if (BookDao.SORT_AUTHOR.equals(mSort)) {
            label = R.string.sort_by_author;
        } else if (BookDao.SORT_FILE_DATE.equals(mSort)) {
            label = R.string.sort_by_file_date;
        } else if (BookDao.SORT_INDEX_DATE.equals(mSort)) {
            label = R.string.sort_by_index_date;
        } else {
            label = R.string.sort_by_title;
        }
        mSortText.setText(getString(R.string.group_sort_prefix, getString(label)));
    }

    private void showSortDialog() {
        if (KIND_SERIES.equals(mKind)) {
            return; // серии — всегда по номеру в серии (FR-2.3)
        }
        if (mGrouped) {
            return; // книги автора всегда сгруппированы по сериям (решение оператора)
        }
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
                        App.prefs().setSort(sortKey(), mSort);
                        mPage = 1;
                        mPlan = null;
                        d.dismiss();
                        updateSortText();
                        renderPage();
                    }
                }));
    }

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
                        goPage(parsePage(input.getText().toString(), pages));
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
}
