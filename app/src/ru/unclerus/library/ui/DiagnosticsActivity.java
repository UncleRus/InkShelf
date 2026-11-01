package ru.unclerus.library.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.R;
import ru.unclerus.library.cover.ThumbCache;
import ru.unclerus.library.db.BookDao;
import ru.unclerus.library.db.CountDao;
import ru.unclerus.library.db.Schema;
import ru.unclerus.library.open.ReaderInfo;
import ru.unclerus.library.open.ReaderRegistry;
import ru.unclerus.library.scan.RootRepo;
import ru.unclerus.library.scan.SafDocs;

/**
 * S6 «Диагностика» (макет 14): четыре секции «ключ → значение» — устройство,
 * индекс, папки с книгами, читалки — и две кнопки внизу: «Скопировать отчёт»
 * (полный текстовый отчёт БД в буфер обмена) и «Выгрузить логи» (копия
 * library.log во внешнее хранилище приложения).
 *
 * <p>Раньше экран был общей заглушкой на {@code activity_placeholder}: один
 * текстовый блок с {@code App.dbReport()}. Теперь разметка своя
 * ({@code activity_diagnostics.xml}),
 * экранные строки — только то, что в макете, а полный отчёт уходит в буфер по
 * кнопке. Все обращения к БД и файловой системе — в фоновом потоке.
 */
public final class DiagnosticsActivity extends ThemedActivity {

    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private final Handler mUi = new Handler(Looper.getMainLooper());
    /** Одна фоновая работа за раз (отчёт / выгрузка логов / загрузка экрана). */
    private final AtomicBoolean mBusy = new AtomicBoolean();

    private TextView mModel;
    private TextView mAndroid;
    private TextView mVersion;
    private TextView mBooks;
    private TextView mAgr;
    private TextView mNoMeta;
    private TextView mMissing;
    private TextView mCovers;
    private LinearLayout mRootRows;
    private LinearLayout mReaderRows;

    /** Снимок экранных данных, собранный в фоновом потоке. */
    private static final class Snapshot {
        String books;
        String agr;
        String noMeta;
        String missing;
        String covers;
        /** Названия читалок — список, по одному на строку. */
        List<String> readerNames = new ArrayList<>();
        /** Пары «путь корня → статус», по порядку RootRepo.listAll. */
        List<String[]> roots = new ArrayList<>();
        String error;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_diagnostics);

        mModel = findViewById(R.id.diag_model_value);
        mAndroid = findViewById(R.id.diag_android_value);
        mVersion = findViewById(R.id.diag_version_value);
        mBooks = findViewById(R.id.diag_books_value);
        mAgr = findViewById(R.id.diag_agr_value);
        mNoMeta = findViewById(R.id.diag_nometa_value);
        mMissing = findViewById(R.id.diag_missing_value);
        mCovers = findViewById(R.id.diag_covers_value);
        mRootRows = findViewById(R.id.diag_roots);
        mReaderRows = findViewById(R.id.diag_readers_list);

        // Секция «Устройство» не требует БД — заполняем сразу.
        mModel.setText(Build.MODEL);
        mAndroid.setText(Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        mVersion.setText(appVersion());

        ImageButton back = findViewById(R.id.diag_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.diag_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyReport();
            }
        });
        findViewById(R.id.diag_logs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLogs();
            }
        });

        load();
    }

    @Override
    protected void onDestroy() {
        mBg.shutdownNow();
        super.onDestroy();
    }

    /** Индекс/корни/читалки — в фоне, потом одним постом в UI. */
    private void load() {
        if (!mBusy.compareAndSet(false, true)) return;
        mBg.execute(new Runnable() {
            @Override
            public void run() {
                final Snapshot s = new Snapshot();
                try {
                    collect(s);
                } catch (Exception e) {
                    Logger.e("Diag", "не удалось собрать данные диагностики", e);
                    s.error = String.valueOf(e);
                }
                mBusy.set(false);
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        fill(s);
                    }
                });
            }
        });
    }

    private void collect(Snapshot s) {
        SQLiteDatabase db = App.db().getReadableDatabase();
        s.books = num(CountDao.books(db));
        s.agr = num(CountDao.authors(db)) + " / " + num(CountDao.genres(db))
                + " / " + num(CountDao.series(db));
        s.noMeta = num(countNoMeta(db));
        s.missing = num(CountDao.booksMissing(db));
        long bytes = ThumbCache.diskBytes(this);
        s.covers = num(ThumbCache.diskCount(this)) + " · "
                + getString(R.string.value_mb, dec(bytes / 1048576.0));

        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.getDefault());
        for (RootRepo.Root r : RootRepo.listAll(db)) {
            String path = RootRepo.KIND_SAF.equals(r.kind)
                    ? SafDocs.displayPath(r.uri) : r.path;
            int books = BookDao.countByRoot(db, r.id);
            String state = rootState(r);
            String value = state == null
                    ? getString(R.string.diag_root_available, nf.format(books))
                    : nf.format(books) + " · " + state;
            s.roots.add(new String[]{path, value});
        }

        for (ReaderInfo ri : ReaderRegistry.installed(this)) s.readerNames.add(ri.label);
    }

    private void fill(Snapshot s) {
        mBooks.setText(s.books);
        mAgr.setText(s.agr);
        mNoMeta.setText(s.noMeta);
        mMissing.setText(s.missing);
        mCovers.setText(s.covers);
        mRootRows.removeAllViews();
        for (String[] row : s.roots) addKvRow(mRootRows, row[0], row[1]);
        mReaderRows.removeAllViews();
        for (String name : s.readerNames) addKvRow(mReaderRows, "", name);
    }

    /** Строка «ключ → значение» (row_diag_kv.xml) в динамический контейнер. */
    private void addKvRow(LinearLayout container, String key, String value) {
        View v = android.view.LayoutInflater.from(this)
                .inflate(R.layout.row_diag_kv, container, false);
        ((TextView) v.findViewById(R.id.diag_kv_key)).setText(key);
        ((TextView) v.findViewById(R.id.diag_kv_value)).setText(value);
        container.addView(v);
    }

    /** Кнопка «Скопировать отчёт»: полный отчёт БД — в буфер обмена. */
    private void copyReport() {
        if (!mBusy.compareAndSet(false, true)) return;
        mBg.execute(new Runnable() {
            @Override
            public void run() {
                String report = null;
                try {
                    report = App.dbReport();
                } catch (Exception e) {
                    Logger.e("Diag", "не удалось получить отчёт", e);
                }
                final boolean ok = report != null;
                if (ok) {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(
                            getString(R.string.title_diagnostics), report));
                }
                mBusy.set(false);
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        toast(ok ? R.string.toast_report_copied : R.string.toast_report_failed);
                    }
                });
            }
        });
    }

    /**
     * Кнопка «Выгрузить логи»: копия library.log во внешнее хранилище
     * приложения ({@code Android/data/…/files}) с отметкой времени в имени.
     * {@link ru.unclerus.library.file.LibraryFileProvider} лог не отдаёт
     * (разрешает только SAF-копии и книги из активных корней), поэтому
     * выгрузка — файлом, путь показываем тостом.
     */
    private void exportLogs() {
        if (!mBusy.compareAndSet(false, true)) return;
        mBg.execute(new Runnable() {
            @Override
            public void run() {
                File dst = null;
                try {
                    File src = Logger.file();
                    if (src != null && src.isFile()) {
                        String stamp = new java.text.SimpleDateFormat(
                                "yyyy-MM-dd_HHmmss", Locale.US).format(new Date());
                        dst = new File(getExternalFilesDir(null), "library-" + stamp + ".log");
                        copy(src, dst);
                    } else {
                        Logger.w("Diag", "файл лога отсутствует");
                    }
                } catch (IOException e) {
                    Logger.e("Diag", "не удалось выгрузить логи", e);
                    dst = null;
                }
                final String path = dst != null && dst.isFile() ? dst.getAbsolutePath() : null;
                mBusy.set(false);
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (path != null) {
                            Toast.makeText(DiagnosticsActivity.this,
                                    getString(R.string.toast_logs_exported, path),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            toast(R.string.toast_logs_failed);
                        }
                    }
                });
            }
        });
    }

    private static void copy(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        try {
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    /** Книги без разобраных метаданных (колонка book.meta_ok). */
    private static int countNoMeta(SQLiteDatabase db) {
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + Schema.T_BOOK + " WHERE meta_ok=0", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Состояние корня → текст; null — всё в порядке (как в S9). */
    private String rootState(RootRepo.Root r) {
        if (RootRepo.VOL_NO_ACCESS.equals(r.volumeState)
                || RootRepo.PERM_DENIED.equals(r.permissionState)) {
            return getString(R.string.root_state_no_access);
        }
        if (RootRepo.VOL_MISSING.equals(r.volumeState)) {
            return getString(R.string.root_state_volume_off);
        }
        return null;
    }

    /** «0.1.0 (1)»: versionName и versionCode установленного APK. */
    private String appVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            String name = pi.versionName == null ? "" : pi.versionName;
            return name + " (" + pi.versionCode + ")";
        } catch (Exception e) {
            Logger.e("Diag", "не удалось получить версию пакета", e);
            return "";
        }
    }

    /** Разряды, как num() в макете: ru — уз. пробел, en — запятая. */
    private static String num(long v) {
        return NumberFormat.getIntegerInstance(Locale.getDefault()).format(v);
    }

    /** Десятичное, как dec() в макете: ru — запятая, en — точка. */
    private static String dec(double v) {
        return String.format(Locale.getDefault(), "%.1f", v);
    }
}
