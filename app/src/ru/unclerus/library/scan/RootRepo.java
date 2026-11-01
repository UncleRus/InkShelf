package ru.unclerus.library.scan;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Корневые папки библиотеки: CRUD, состояние носителя и разрешений.
 *
 * <p>Корень — это либо обычный путь ({@link #KIND_FILE}, основной путь через
 * {@code MANAGE_EXTERNAL_STORAGE}), либо SAF-дерево ({@link #KIND_SAF},
 * резерв для разделов без прямого доступа).
 */
public final class RootRepo {

    /** Обычный файловый путь. */
    public static final String KIND_FILE = "file";
    /** SAF-дерево (DocumentFile). */
    public static final String KIND_SAF = "saf";

    public static final String VOL_READY = "ready";
    public static final String VOL_MISSING = "missing";
    public static final String VOL_NO_ACCESS = "no_access";
    public static final String VOL_UNKNOWN = "unknown";

    public static final String PERM_GRANTED = "granted";
    public static final String PERM_DENIED = "denied";
    public static final String PERM_UNKNOWN = "unknown";

    /** Запись о корне (для индексатора и UI). */
    public static final class Root {
        public long id;
        public String path;
        public String uri;
        public String kind;
        /** Историческое поле (колонка `enabled` в схеме); выключения папок нет. */
        public boolean enabled;
        public long lastScanAt;
        public String volumeState;
        public String permissionState;
    }

    // `enabled` остаётся в схеме как историческая колонка (миграцию БД ради её
    // удаления не делаем): выключения папок больше нет, значение всегда 1.
    private static final String COLS = "id, path, uri, kind, enabled, last_scan_at, volume_state, permission_state";

    private RootRepo() {}

    // ------------------------------------------------------------------
    // Чтение
    // ------------------------------------------------------------------

    /** Все корни по алфавиту. */
    public static Cursor all(SQLiteDatabase db) {
        return db.rawQuery("SELECT " + COLS + " FROM " + "root ORDER BY path", null);
    }

    /** Все корни (для индексатора и FileProvider); порядок — по пути. */
    public static List<Root> listAll(SQLiteDatabase db) {
        List<Root> out = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT " + COLS + " FROM root ORDER BY path", null);
        try {
            while (c.moveToNext()) {
                out.add(read(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Корень по id. */
    public static Root byId(SQLiteDatabase db, long id) {
        Cursor c = db.rawQuery("SELECT " + COLS + " FROM root WHERE id=?",
                new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? read(c) : null;
        } finally {
            c.close();
        }
    }

    /** Есть ли уже такой путь. */
    public static boolean exists(SQLiteDatabase db, String path) {
        Cursor c = db.rawQuery("SELECT 1 FROM root WHERE path=? LIMIT 1",
                new String[]{path});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public static int count(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM root", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------
    // Запись
    // ------------------------------------------------------------------

    /**
     * Добавить корень.
     *
     * @return id новой строки или {@code -1}, если такой путь уже есть
     *         (сработал {@code UNIQUE(path)})
     */
    public static long add(SQLiteDatabase db, String path, String kind) {
        return add(db, path, null, kind);
    }

    /**
     * Добавить корень с указанием SAF-URI (для {@link #KIND_SAF}: {@code path} —
     * строка tree-URI, {@code uri} — она же; {@code UNIQUE(path)} защищает от
     * дублей).
     *
     * @return id новой строки или {@code -1}, если такой путь уже есть
     */
    public static long add(SQLiteDatabase db, String path, String uri, String kind) {
        ContentValues v = new ContentValues();
        v.put("path", path);
        v.put("uri", uri);
        v.put("kind", kind == null ? KIND_FILE : kind);
        v.put("enabled", 1);
        v.put("last_scan_at", 0);
        v.put("volume_state", VOL_UNKNOWN);
        v.put("permission_state", PERM_UNKNOWN);
        return db.insertWithOnConflict("root", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Удалить корень. Книги корня удаляются отдельно ({@code Cleanup.deleteRootWithBooks}). */
    public static int remove(SQLiteDatabase db, long id) {
        return db.delete("root", "id=?", new String[]{String.valueOf(id)});
    }

    /** Отметить время последнего полного скана корня. */
    public static int markScanned(SQLiteDatabase db, long id, long at) {
        ContentValues v = new ContentValues();
        v.put("last_scan_at", at);
        return db.update("root", v, "id=?", new String[]{String.valueOf(id)});
    }

    public static int setVolumeState(SQLiteDatabase db, long id, String state) {
        ContentValues v = new ContentValues();
        v.put("volume_state", state);
        return db.update("root", v, "id=?", new String[]{String.valueOf(id)});
    }

    public static int setPermissionState(SQLiteDatabase db, long id, String state) {
        ContentValues v = new ContentValues();
        v.put("permission_state", state);
        return db.update("root", v, "id=?", new String[]{String.valueOf(id)});
    }

    public static int setError(SQLiteDatabase db, long id, String error) {
        ContentValues v = new ContentValues();
        v.put("last_error", error);
        return db.update("root", v, "id=?", new String[]{String.valueOf(id)});
    }

    // ------------------------------------------------------------------

    private static Root read(Cursor c) {
        Root r = new Root();
        r.id = c.getLong(0);
        r.path = c.getString(1);
        r.uri = c.getString(2);
        r.kind = c.getString(3);
        r.enabled = c.getInt(4) != 0;
        r.lastScanAt = c.getLong(5);
        r.volumeState = c.getString(6);
        r.permissionState = c.getString(7);
        return r;
    }
}
