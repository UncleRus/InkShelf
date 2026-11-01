package ru.unclerus.library.scan;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.unclerus.library.App;
import ru.unclerus.library.db.BookDao;
import ru.unclerus.library.db.Schema;
import ru.unclerus.library.fmt.BookFormat;
import ru.unclerus.library.util.BookFiles;

/**
 * Фаза A конвейера: обход корней и {@code stat} файлов (PLAN §7). Файлы
 * <b>не читаются</b> — только имя, размер и mtime; при совпадении с БД для
 * файла не делается ни одной записи, поэтому повторный скан стоит почти
 * столько же, сколько обход.
 *
 * <p>Доступность книг проверяется отдельным проходом по строкам корня
 * ({@link #recheckAvailability}): пропавшие файлы помечаются {@code missing},
 * вернувшиеся — {@code ok}. Это надёжнее счётчика генераций и не требует
 * записи для неизменённых файлов.
 */
public final class WalkPhase {

    /** Обратный вызов прогресса; {@code false} — прервать обход. */
    public interface Callback {
        boolean onProgress(long scanned, long found, long added, long changed);
    }

    /** Итог обхода одного корня. */
    public static final class Stats {
        public long scanned;
        public long found;
        public long added;
        public long changed;
        /**
         * Сколько строк реально записано в БД за этот обход ({@code total_changes}).
         * На повторном скане неизменённой коллекции должно быть близко к нулю —
         * это и есть проверка «повторный скан — только {@code stat}».
         */
        public long dbChanges;
        /** Корень прочитан (иначе — нет доступа). */
        public boolean ok = true;
        public boolean cancelled;
        public String volumeState = RootRepo.VOL_READY;
        /**
         * Состояние разрешения на доступ ({@code granted}/{@code denied}).
         * {@code null} — не менялось (не удалось определить).
         */
        public String permissionState;
        public String error;
    }

    /** Служебные каталоги, в которые не заходим. */
    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            "Android", "LOST.DIR", "$RECYCLE.BIN", "System Volume Information"));

    private static final int MAX_DEPTH = 64;
    private static final int PUBLISH_EVERY = 128;
    private static final long PUBLISH_MS = 200;

    private final SQLiteDatabase mDb;
    private final RootRepo.Root mRoot;
    private final long mGen;
    private final Callback mCb;
    private final Stats mStats = new Stats();

    /** Контекст для доступа к SAF-документам (резервный путь). */
    private final Context mCtx = App.ctx();

    private long mLastPublish;
    private int mSincePublish;
    private boolean mStop;
    /** В SAF-обходе встретился недоступный документ — считаем корень нечитаемым. */
    private boolean mSafError;

    private WalkPhase(SQLiteDatabase db, RootRepo.Root root, long generation, Callback cb) {
        mDb = db;
        mRoot = root;
        mGen = generation;
        mCb = cb;
    }

    /** Обойти корень. Вызывать внутри транзакции (её открывает {@link Indexer}). */
    public static Stats run(SQLiteDatabase db, RootRepo.Root root, long generation, Callback cb) {
        return new WalkPhase(db, root, generation, cb).execute();
    }

    // ------------------------------------------------------------------

    private Stats execute() {
        if (RootRepo.KIND_SAF.equals(mRoot.kind)) {
            return executeSaf();
        }
        return executeFile();
    }

    /** Обычный путь: корень — каталог файловой системы (PLAN §6.2). */
    private Stats executeFile() {
        File dir = new File(mRoot.path);
        if (!dir.exists()) {
            mStats.ok = false;
            mStats.volumeState = RootRepo.VOL_MISSING;
            mStats.error = "носитель недоступен: " + mRoot.path;
            publish();
            return mStats;
        }
        if (!dir.canRead()) {
            mStats.ok = false;
            mStats.volumeState = RootRepo.VOL_NO_ACCESS;
            mStats.permissionState = RootRepo.PERM_DENIED;
            mStats.error = "нет доступа на чтение: " + mRoot.path;
            publish();
            return mStats;
        }
        mStats.permissionState = RootRepo.PERM_GRANTED;

        mLastPublish = SystemClock.uptimeMillis();
        long changesBefore = totalChanges();
        walk(dir, 0);
        if (!mStats.cancelled) {
            recheckAvailability();
        }
        mStats.dbChanges = totalChanges() - changesBefore;
        publish();
        return mStats;
    }

    /**
     * Резервный путь SAF: корень — дерево {@code content://} (PLAN §6.2).
     * Обход через {@link SafDocs}; книги адресуются document-URI, а не путём.
     */
    private Stats executeSaf() {
        final String treeUri = mRoot.uri != null ? mRoot.uri : mRoot.path;
        mLastPublish = SystemClock.uptimeMillis();
        long changesBefore = totalChanges();
        try {
            String rootDocId = SafDocs.treeDocumentId(treeUri);
            walkSaf(treeUri, rootDocId, 0);
        } catch (Throwable t) {
            mSafError = true;
        }
        if (mSafError && !mStop) {
            mStats.ok = false;
            mStats.volumeState = RootRepo.VOL_NO_ACCESS;
            mStats.permissionState = RootRepo.PERM_DENIED;
            mStats.error = "нет доступа к папке: " + treeUri;
        } else if (mStats.ok) {
            mStats.permissionState = RootRepo.PERM_GRANTED;
        }
        if (!mStats.cancelled && mStats.ok) {
            recheckAvailability();
        }
        mStats.dbChanges = totalChanges() - changesBefore;
        publish();
        return mStats;
    }

    /** Число строк, изменённых соединением с БД (счётчик SQLite). */
    private long totalChanges() {
        try {
            return DatabaseUtils.longForQuery(mDb, "SELECT total_changes()", null);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** Рекурсивный обход: сначала подкаталоги, затем файлы. */
    private void walk(File dir, int depth) {
        if (mStop || depth > MAX_DEPTH) {
            return;
        }
        File[] children;
        try {
            children = dir.listFiles();
        } catch (SecurityException e) {
            return; // недоступный каталог просто пропускаем
        }
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (mStop) {
                return;
            }
            if (f == null) {
                continue;
            }
            String name = f.getName();
            if (name.isEmpty() || name.charAt(0) == '.') {
                continue; // скрытые и служебные (.thumbnails, .Trash, …)
            }
            if (isDir(f)) {
                if (SKIP_DIRS.contains(name)) {
                    continue;
                }
                walk(f, depth + 1);
                continue;
            }
            if (!f.isFile()) {
                continue;
            }

            mStats.scanned++;
            String format = BookFormat.detect(name);
            if (format == null) {
                maybePublish();
                continue; // не книга — считаем просканированным, но не индексируем
            }

            mStats.found++;
            int r = BookDao.touchSeen(mDb, mRoot.id, f.getAbsolutePath(),
                    BookFormat.displayTitle(name), format,
                    f.length(), f.lastModified(), mGen);
            if (r == BookDao.TOUCH_ABSENT) {
                mStats.added++;
            } else if (r == BookDao.TOUCH_CHANGED) {
                mStats.changed++;
            }
            maybePublish();
        }
    }

    private static boolean isDir(File f) {
        try {
            return f.isDirectory();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Рекурсивный обход SAF-дерева: узлы — документы {@code DocumentsContract}. */
    private void walkSaf(String treeUri, String parentDocId, int depth) {
        if (mStop || depth > MAX_DEPTH) {
            return;
        }
        Cursor c;
        try {
            c = SafDocs.children(mCtx, treeUri, parentDocId);
        } catch (Throwable t) {
            c = null;
        }
        if (c == null) {
            mSafError = true;
            return;
        }
        try {
            while (c.moveToNext()) {
                if (mStop) {
                    return;
                }
                String docId = c.getString(SafDocs.C_DOC_ID);
                String name = c.getString(SafDocs.C_NAME);
                String mime = c.getString(SafDocs.C_MIME);
                if (docId == null || name == null || name.isEmpty() || name.charAt(0) == '.') {
                    continue; // скрытые и служебные (.thumbnails, .Trash, …)
                }
                if (SafDocs.isDir(mime)) {
                    if (SKIP_DIRS.contains(name)) {
                        continue;
                    }
                    walkSaf(treeUri, docId, depth + 1);
                    continue;
                }

                mStats.scanned++;
                String format = BookFormat.detect(name);
                if (format == null) {
                    maybePublish();
                    continue; // не книга — считаем просканированным, но не индексируем
                }

                mStats.found++;
                long size = c.isNull(SafDocs.C_SIZE) ? 0L : c.getLong(SafDocs.C_SIZE);
                long mtime = c.isNull(SafDocs.C_MTIME) ? 0L : c.getLong(SafDocs.C_MTIME);
                String path = SafDocs.documentUri(treeUri, docId);
                int r = BookDao.touchSeen(mDb, mRoot.id, path,
                        BookFormat.displayTitle(name), format, size, mtime, mGen);
                if (r == BookDao.TOUCH_ABSENT) {
                    mStats.added++;
                } else if (r == BookDao.TOUCH_CHANGED) {
                    mStats.changed++;
                }
                maybePublish();
            }
        } finally {
            c.close();
        }
    }

    /**
     * Проход B: сверка доступности. Собираем изменения id-шниками и применяем
     * после закрытия курсора (нельзя писать в таблицу, пока по ней открыт
     * курсор).
     */
    private void recheckAvailability() {
        List<Long> toOk = new ArrayList<>();
        List<Long> toMissing = new ArrayList<>();
        Cursor c = BookDao.rootRows(mDb, mRoot.id);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String path = c.getString(1);
                String avail = c.getString(2);
                boolean exists = BookFiles.exists(mCtx, path);
                if (exists && !Schema.AVAIL_OK.equals(avail)) {
                    toOk.add(Long.valueOf(id));
                } else if (!exists && Schema.AVAIL_OK.equals(avail)) {
                    toMissing.add(Long.valueOf(id));
                }
            }
        } finally {
            c.close();
        }
        for (Long id : toMissing) {
            BookDao.setAvailability(mDb, id.longValue(), Schema.AVAIL_MISSING);
        }
        for (Long id : toOk) {
            BookDao.setAvailability(mDb, id.longValue(), Schema.AVAIL_OK);
        }
    }

    private void maybePublish() {
        mSincePublish++;
        long now = SystemClock.uptimeMillis();
        if (mSincePublish >= PUBLISH_EVERY || now - mLastPublish >= PUBLISH_MS) {
            publish();
        }
    }

    private void publish() {
        if (mCb == null) {
            return;
        }
        mSincePublish = 0;
        mLastPublish = SystemClock.uptimeMillis();
        if (!mCb.onProgress(mStats.scanned, mStats.found, mStats.added, mStats.changed)) {
            mStop = true;
            mStats.cancelled = true;
        }
    }
}
