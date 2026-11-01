package ru.unclerus.library.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;

import ru.unclerus.library.Logger;
import ru.unclerus.library.fmt.GenreTable;

/**
 * Владелец БД: {@link SQLiteOpenHelper} с PRAGMA, схемой и пошаговыми
 * миграциями. Один экземпляр на процесс (синглтон), чтобы не плодить
 * параллельных писателей (PLAN §Потоки: один писатель — индексатор).
 *
 * <p>Режим: WAL + {@code synchronous=NORMAL}, внешние ключи включены.
 */
public final class Db extends SQLiteOpenHelper {

    private static volatile Db sInstance;

    public static Db get(Context ctx) {
        Db d = sInstance;
        if (d == null) {
            synchronized (Db.class) {
                d = sInstance;
                if (d == null) {
                    d = new Db(ctx.getApplicationContext());
                    sInstance = d;
                }
            }
        }
        return d;
    }

    /** Создать и открыть БД, вернув затраченное время в мс (для холодного старта). */
    public static long openTimed(Context ctx) {
        long t0 = SystemClock.uptimeMillis();
        get(ctx).getWritableDatabase();
        return SystemClock.uptimeMillis() - t0;
    }

    private Db(Context ctx) {
        super(ctx, Schema.DB_NAME, null, Schema.VERSION);
        // WAL: читатели не блокируются писателем (UI читает во время индексации).
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Logger.i("Db", "создание схемы v" + Schema.VERSION);
        for (String ddl : Schema.CREATE) {
            db.execSQL(ddl);
        }
        for (String ddl : Schema.CREATE_INDEXES) {
            db.execSQL(ddl);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Logger.i("Db", "миграция схемы " + oldVersion + " → " + newVersion);
        // Пошаговые миграции: каждая версия — отдельный case, идем снизу вверх.
        for (int v = oldVersion + 1; v <= newVersion; v++) {
            switch (v) {
                case 2:
                    // v1: FTS external-content + FTS5-команда 'delete' (в FTS4 не
                    // работает). v2: обычная FTS4-таблица + DELETE/INSERT-триггеры.
                    for (String ddl : Schema.DROP_TRIGGERS) {
                        db.execSQL(ddl);
                    }
                    db.execSQL("DROP TABLE IF EXISTS " + Schema.T_FTS);
                    db.execSQL(Schema.FTS_DDL);
                    for (String ddl : Schema.CREATE_TRIGGERS) {
                        db.execSQL(ddl);
                    }
                    db.execSQL("INSERT INTO " + Schema.T_FTS
                            + "(rowid, title, authors_text, series_text) "
                            + "SELECT id, title, authors_text, series_text FROM " + Schema.T_BOOK);
                    break;
                case 3:
                    // v3: снята поддержка PDF/DJVU (решение оператора). Строки
                    // этих форматов удаляем: связи book_* уходят по ON DELETE
                    // CASCADE, FTS — триггером book_ad. Метаданных у них не было
                    // (Фаза B их не разбирала), поэтому счётчики не затрагиваются.
                    db.execSQL("DELETE FROM " + Schema.T_BOOK
                            + " WHERE format IN (" + Schema.REMOVED_FORMATS_SQL + ")");
                    break;
                case 4:
                    // v4: локализация жанров — английское название отдельной
                    // колонкой. Заполняем её из кода через GenreTable, файлы книг
                    // не перечитываются.
                    db.execSQL("ALTER TABLE " + Schema.T_GENRE
                            + " ADD COLUMN display_name_en TEXT");
                    backfillGenreNamesEn(db);
                    break;
                default:
                    break;
            }
        }
    }

    /** v4: заполнить {@code genre.display_name_en} по коду (fallback — сам код). */
    private static void backfillGenreNamesEn(SQLiteDatabase db) {
        // Сначала вычитываем id/коды и закрываем курсор, и только потом пишем:
        // менять таблицу, пока по ней открыт курсор, — лишний риск.
        Cursor c = db.rawQuery("SELECT id, code FROM " + Schema.T_GENRE, null);
        long[] ids = new long[c.getCount()];
        String[] codes = new String[c.getCount()];
        int n = 0;
        try {
            while (c.moveToNext()) {
                ids[n] = c.getLong(0);
                codes[n] = c.getString(1);
                n++;
            }
        } finally {
            c.close();
        }
        for (int i = 0; i < n; i++) {
            String en = GenreTable.displayNameEn(codes[i]);
            db.execSQL("UPDATE " + Schema.T_GENRE + " SET display_name_en=? WHERE id=?",
                    new Object[] { en == null ? codes[i] : en, Long.valueOf(ids[i]) });
        }
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Logger.w("Db", "откат схемы " + oldVersion + " → " + newVersion + ": пересоздание");
        dropAll(db);
        onCreate(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) {
            db.execSQL("PRAGMA synchronous=NORMAL");
        }
    }

    /** Полное удаление объектов схемы (пересоздание, откат, диагностика). */
    public static void dropAll(SQLiteDatabase db) {
        for (String ddl : Schema.DROP) {
            db.execSQL(ddl);
        }
    }
}
