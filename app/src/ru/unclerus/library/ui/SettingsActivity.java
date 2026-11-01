package ru.unclerus.library.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.R;
import ru.unclerus.library.cover.ThumbCache;
import ru.unclerus.library.db.Cleanup;
import ru.unclerus.library.db.CountDao;
import ru.unclerus.library.db.MetaDao;
import ru.unclerus.library.db.Schema;
import ru.unclerus.library.db.Tx;
import ru.unclerus.library.open.OpenWithDialog;
import ru.unclerus.library.open.ReaderInfo;
import ru.unclerus.library.open.ReaderRegistry;
import ru.unclerus.library.scan.IndexerService;
import ru.unclerus.library.util.Dialogs;
import ru.unclerus.library.util.Prefs;

/**
 * S4 «Настройки» (макет 12, UI.md §7): четыре раздела — Библиотека, Чтение,
 * Оформление, Доступ и сервис.
 *
 * <ul>
 *   <li>S4.1 «Папки с книгами» — открывает отдельный экран S9 ({@link RootsActivity});</li>
 *   <li>S4.2 читалка по умолчанию — список читалок ({@link OpenWithDialog});</li>
 *   <li>S4.3 размер шрифта, S4.4 плотность, S4.5 формат имени автора, S4.6 обложки,
 *       S4.7 размер кэша — мгновенные настройки;</li>
 *   <li>S4.8 рескан, S4.9 очистка отсутствующих, S4.10 доступ к файлам,
 *       S4.11 диагностика, S4.12 «О программе».</li>
 * </ul>
 *
 * <p>Масштаб шрифта применяется базовым {@link ThemedActivity}: при смене экран
 * пересоздаётся, чтобы sp-размеры пересчитались (NFR-1).
 */
public final class SettingsActivity extends ThemedActivity {

    private static final int A_ACCESS = 1;
    private static final int A_ROOTS = 2;
    private static final int A_RESCAN = 3;
    private static final int A_CLEAR = 4;
    private static final int A_READER = 5;
    private static final int A_FONT = 6;
    private static final int A_DENSITY = 7;
    private static final int A_AUTHOR = 8;
    private static final int A_COVERS = 9;
    private static final int A_CACHE = 10;
    private static final int A_DIAGNOSTICS = 11;
    private static final int A_ABOUT = 12;

    private static final int REQ_STORAGE = 1;

    /** Варианты размера шрифта (S4.3), множитель поверх системного. */
    private static final float[] FONT_SCALES = {1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    /** Варианты размера кэша обложек, МБ (S4.7). */
    private static final int[] CACHE_MB = {16, 32, 64, 128};

    private LinearLayout mList;
    private LayoutInflater mInf;

    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private final Handler mUi = new Handler(Looper.getMainLooper());

    /** Была ли предыдущая строка обычной (для разделителя между строками). */
    private boolean mRowAfter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        mList = findViewById(R.id.set_list);
        mInf = LayoutInflater.from(this);

        findViewById(R.id.set_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        buildRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildRows(); // статус доступа/корней мог измениться, пока экран был в фоне
    }

    @Override
    protected void onDestroy() {
        mBg.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Построение строк
    // ------------------------------------------------------------------

    private void buildRows() {
        mList.removeAllViews();
        mRowAfter = false;

        addSection(R.string.section_library);
        addRow(R.drawable.ic_folder, getString(R.string.setting_roots), rootsSub(), "",
                A_ROOTS);
        addRow(R.drawable.ic_refresh, getString(R.string.setting_rescan), null, "",
                A_RESCAN);
        addRow(R.drawable.ic_trash, getString(R.string.setting_clear_missing), null, "",
                A_CLEAR);

        addSection(R.string.section_reading);
        addRow(R.drawable.ic_book_open, getString(R.string.setting_default_reader), null,
                readerLabel(), A_READER);

        addSection(R.string.section_appearance);
        addRow(R.drawable.ic_type, getString(R.string.setting_font_size), null,
                percentLabel(), A_FONT);
        addRow(R.drawable.ic_list, getString(R.string.setting_list_density), null,
                getString(densityLabel()), A_DENSITY);
        addRow(R.drawable.ic_authors, getString(R.string.setting_author_format), null,
                authorLabel(), A_AUTHOR);
        addRow(R.drawable.ic_image, getString(R.string.setting_show_covers), null,
                getString(App.prefs().showCovers() ? R.string.value_yes : R.string.value_no),
                A_COVERS);
        addRow(R.drawable.ic_database, getString(R.string.setting_cover_cache), null,
                getString(R.string.value_mb, String.valueOf(App.prefs().coverCacheMb())),
                A_CACHE);

        addSection(R.string.section_access_service);
        addRow(R.drawable.ic_key, getString(R.string.setting_file_access), null,
                getString(hasAllFiles() ? R.string.access_granted : R.string.access_denied),
                A_ACCESS);
        addRow(R.drawable.ic_activity, getString(R.string.setting_diagnostics), null, "",
                A_DIAGNOSTICS);
        addRow(R.drawable.ic_info, getString(R.string.setting_about), null, "", A_ABOUT);
    }

    /** Заголовок раздела (серая плашка). */
    private void addSection(int titleRes) {
        TextView head = (TextView) mInf.inflate(R.layout.row_section, mList, false);
        head.setText(titleRes);
        mList.addView(head);
        mRowAfter = false;
    }

    private void addRow(int icon, String title, String sub, String value, final int action) {
        if (mRowAfter) {
            mList.addView(makeDivider());
        }
        View row = mInf.inflate(R.layout.row_setting, mList, false);
        ((ImageView) row.findViewById(R.id.set_row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.set_row_title)).setText(title);

        TextView subView = row.findViewById(R.id.set_row_sub);
        if (sub == null || sub.isEmpty()) {
            subView.setVisibility(View.GONE);
        } else {
            subView.setText(sub);
            subView.setVisibility(View.VISIBLE);
        }
        ((TextView) row.findViewById(R.id.set_row_value)).setText(value == null ? "" : value);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRow(action);
            }
        });
        mList.addView(row);
        mRowAfter = true;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.divider_h)));
        v.setBackgroundResource(R.color.divider);
        return v;
    }

    // ------------------------------------------------------------------
    // Действия
    // ------------------------------------------------------------------

    private void onRow(int action) {
        switch (action) {
            case A_ROOTS:
                startActivity(new Intent(this, RootsActivity.class));
                break;
            case A_RESCAN:
                startScan();
                break;
            case A_CLEAR:
                confirmClear();
                break;
            case A_READER:
                OpenWithDialog.showDefaultSetting(this, new Runnable() {
                    @Override
                    public void run() {
                        buildRows();
                    }
                });
                break;
            case A_FONT:
                showFontDialog();
                break;
            case A_DENSITY:
                showDensityDialog();
                break;
            case A_AUTHOR:
                showAuthorDialog();
                break;
            case A_COVERS:
                showCoversDialog();
                break;
            case A_CACHE:
                showCacheDialog();
                break;
            case A_ACCESS:
                requestAccess();
                break;
            case A_DIAGNOSTICS:
                startActivity(new Intent(this, DiagnosticsActivity.class));
                break;
            case A_ABOUT:
                startActivity(new Intent(this, AboutActivity.class));
                break;
            default:
                break;
        }
    }

    /** S4.8: запустить общий рескан и вернуться в список — прогресс покажет S1. */
    private void startScan() {
        startService(new Intent(this, IndexerService.class).setAction(IndexerService.ACTION_START));
        Toast.makeText(this, R.string.toast_scan_started, Toast.LENGTH_SHORT).show();
        finish();
    }

    /**
     * S4.9 «Очистить отсутствующие» (D4): показать, что именно будет удалено,
     * и по подтверждению удалить книги с пропавшими файлами и «сиротские»
     * классификаторы. Файлы на диске не трогаются.
     */
    private void confirmClear() {
        final int missing;
        final int groups;
        try {
            SQLiteDatabase db = App.db().getReadableDatabase();
            missing = CountDao.booksMissing(db);
            groups = emptyGroups(db);
        } catch (Throwable t) {
            Logger.e("Settings", "не удалось посчитать отсутствующие", t);
            return;
        }

        String body = getString(R.string.dlg_clear_intro) + "\n\n• "
                + getString(R.string.dlg_clear_books, String.valueOf(missing)) + "\n• "
                + getString(R.string.dlg_clear_groups, String.valueOf(groups)) + "\n\n"
                + getString(R.string.dlg_clear_warning);
        Dialogs.show(new AlertDialog.Builder(this, R.style.AppTheme_Dialog)
                .setTitle(R.string.setting_clear_missing)
                .setMessage(body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        clearMissing();
                    }
                }));
    }

    /** Удалить книги со статусом {@code missing} и осиротевшие классификаторы. */
    private void clearMissing() {
        mBg.execute(new Runnable() {
            @Override
            public void run() {
                final int[] removed = {0};
                try {
                    SQLiteDatabase db = App.db().getWritableDatabase();
                    boolean ok = Tx.silent(db, new Tx.Body() {
                        @Override
                        public void run(SQLiteDatabase d) {
                            Cursor c = d.rawQuery("SELECT id FROM " + Schema.T_BOOK
                                            + " WHERE availability=?",
                                    new String[]{Schema.AVAIL_MISSING});
                            java.util.ArrayList<Long> ids = new java.util.ArrayList<Long>();
                            try {
                                while (c.moveToNext()) {
                                    ids.add(Long.valueOf(c.getLong(0)));
                                }
                            } finally {
                                c.close();
                            }
                            for (int i = 0; i < ids.size(); i++) {
                                long id = ids.get(i).longValue();
                                MetaDao.clearLinks(d, id);
                                removed[0] += d.delete(Schema.T_BOOK, "id=?",
                                        new String[]{String.valueOf(id)});
                            }
                            Cleanup.deleteOrphans(d);
                        }
                    });
                    Logger.i("Settings", "очистка отсутствующих: удалено книг " + removed[0]
                            + ", транзакция " + (ok ? "успешна" : "отменена"));
                } catch (Throwable t) {
                    Logger.e("Settings", "не удалось очистить отсутствующие", t);
                }
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SettingsActivity.this, R.string.toast_catalog_cleaned,
                                Toast.LENGTH_SHORT).show();
                        buildRows();
                    }
                });
            }
        });
    }

    /** Число классификаторов без книг — ровно то, что уберёт {@link Cleanup#deleteOrphans}. */
    private static int emptyGroups(SQLiteDatabase db) {
        String sql = "SELECT "
                + "(SELECT COUNT(*) FROM " + Schema.T_AUTHOR
                + " WHERE id NOT IN (SELECT author_id FROM " + Schema.T_BOOK_AUTHOR + "))"
                + "+(SELECT COUNT(*) FROM " + Schema.T_GENRE
                + " WHERE id NOT IN (SELECT genre_id FROM " + Schema.T_BOOK_GENRE + "))"
                + "+(SELECT COUNT(*) FROM " + Schema.T_SERIES
                + " WHERE id NOT IN (SELECT series_id FROM " + Schema.T_BOOK_SERIES + "))";
        Cursor c = db.rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** S4.3: размер шрифта — сегменты 100…200 % поверх системного. */
    private void showFontDialog() {
        String[] items = new String[FONT_SCALES.length];
        float cur = App.fontScale();
        int checked = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < FONT_SCALES.length; i++) {
            items[i] = Math.round(FONT_SCALES[i] * 100f) + "%";
            float diff = Math.abs(FONT_SCALES[i] - cur);
            if (diff < best) {
                best = diff;
                checked = i;
            }
        }
        Dialogs.show(new AlertDialog.Builder(this, R.style.AppTheme_Dialog)
                .setTitle(R.string.setting_font_size)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        App.setFontScale(FONT_SCALES[which]);
                        ListMetrics.reset();
                        d.dismiss();
                        mUi.post(new Runnable() {
                            @Override
                            public void run() {
                                recreate(); // пересчитать sp-размеры (S4.3)
                            }
                        });
                    }
                }));
    }

    /**
     * S4.4: плотность списка. Сброс метрик строки — размер страницы пересчитается
     * при возврате в список ({@code MainActivity.onResume}).
     */
    private void showDensityDialog() {
        final String[] items = {
                getString(R.string.density_compact),
                getString(R.string.density_normal),
        };
        int checked = App.prefs().isCompact() ? 0 : 1;
        Dialogs.show(new AlertDialog.Builder(this, R.style.AppTheme_Dialog)
                .setTitle(R.string.setting_list_density)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        App.prefs().setDensity(which == 0
                                ? Prefs.DENSITY_COMPACT : Prefs.DENSITY_NORMAL);
                        ListMetrics.reset();
                        d.dismiss();
                        buildRows();
                    }
                }));
    }

    /** S4.5: формат имени автора — «Имя Фамилия» / «Фамилия Имя». */
    private void showAuthorDialog() {
        final String[] items = {
                getString(R.string.author_format_first_last),
                getString(R.string.author_format_last_first),
        };
        int checked = App.prefs().isLastFirst() ? 1 : 0;
        Dialogs.show(new AlertDialog.Builder(this, R.style.AppTheme_Dialog)
                .setTitle(R.string.setting_author_format)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        App.prefs().setAuthorFormat(which == 1
                                ? Prefs.AUTHOR_LAST_FIRST : Prefs.AUTHOR_FIRST_LAST);
                        d.dismiss();
                        buildRows();
                    }
                }));
    }

    /** S4.6: показывать ли обложки в списках. */
    private void showCoversDialog() {
        final String[] items = {
                getString(R.string.value_yes),
                getString(R.string.value_no),
        };
        int checked = App.prefs().showCovers() ? 0 : 1;
        Dialogs.show(new AlertDialog.Builder(this, R.style.AppTheme_Dialog)
                .setTitle(R.string.setting_show_covers)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        App.prefs().setShowCovers(which == 0);
                        d.dismiss();
                        buildRows();
                    }
                }));
    }

    /** S4.7: размер дискового кэша обложек; после смены LRU подрезается. */
    private void showCacheDialog() {
        final String[] items = new String[CACHE_MB.length];
        int cur = App.prefs().coverCacheMb();
        int checked = 0;
        for (int i = 0; i < CACHE_MB.length; i++) {
            items[i] = getString(R.string.value_mb, String.valueOf(CACHE_MB[i]));
            if (CACHE_MB[i] == cur) {
                checked = i;
            }
        }
        Dialogs.show(new AlertDialog.Builder(this, R.style.AppTheme_Dialog)
                .setTitle(R.string.setting_cover_cache)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        App.prefs().setCoverCacheMb(CACHE_MB[which]);
                        d.dismiss();
                        buildRows();
                        mBg.execute(new Runnable() {
                            @Override
                            public void run() {
                                ThumbCache.enforceLimit(SettingsActivity.this);
                            }
                        });
                    }
                }));
    }

    // ------------------------------------------------------------------
    // Подписи строк
    // ------------------------------------------------------------------

    /** «N папок · M книг» для строки S4.1. */
    private String rootsSub() {
        try {
            SQLiteDatabase db = App.db().getReadableDatabase();
            int folders = CountDao.roots(db);
            int books = CountDao.books(db);
            return folders + " "
                    + getResources().getQuantityString(R.plurals.folders_count, folders)
                    + " · " + books + " "
                    + getResources().getQuantityString(R.plurals.books_count, books);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Метка выбранной читалки (S4.2) или «не выбрана». */
    private String readerLabel() {
        String pkg = App.prefs().defaultReader();
        ReaderInfo ri = ReaderRegistry.byPackage(this, pkg);
        return ri != null ? ri.label : getString(R.string.reader_none);
    }

    /** Подпись текущего размера шрифта (S4.3), например «150%». */
    private String percentLabel() {
        return Math.round(App.fontScale() * 100f) + "%";
    }

    /** Подпись текущей плотности списка (S4.4). */
    private int densityLabel() {
        return App.prefs().isCompact() ? R.string.density_compact : R.string.density_normal;
    }

    /** Подпись текущего формата имени автора (S4.5). */
    private String authorLabel() {
        return getString(App.prefs().isLastFirst()
                ? R.string.author_format_last_first : R.string.author_format_first_last);
    }

    // ------------------------------------------------------------------
    // Доступ к файлам (S4.10)
    // ------------------------------------------------------------------

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }
}
