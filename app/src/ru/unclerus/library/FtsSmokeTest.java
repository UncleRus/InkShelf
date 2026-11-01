package ru.unclerus.library;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Проверка M0: доступен ли FTS4 и работает ли токенизатор {@code unicode61} с префиксным
 * поиском. На целевом устройстве SQLite 3.28, FTS5 отсутствует — поэтому опираемся на FTS4.
 * Тест создаёт временную таблицу в памяти и выполняет несколько MATCH-запросов.
 */
public final class FtsSmokeTest {

    private FtsSmokeTest() {}

    /** @return true, если FTS4 + unicode61 + префикс + регистронезависимость работают. */
    public static boolean run() {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.create(null); // in-memory
            db.execSQL("CREATE VIRTUAL TABLE t USING fts4("
                    + "title, authors, series, tokenize=unicode61)");
            db.execSQL("INSERT INTO t(title, authors) VALUES('Война и мир', 'Лев Толстой')");
            db.execSQL("INSERT INTO t(title, authors) VALUES('1984', 'George Orwell')");

            boolean ok = match(db, "вой*")        // префикс, кириллица, нижний регистр
                    && match(db, "Толстой")       // точное слово, другой регистр
                    && match(db, "1984")          // цифры
                    && !match(db, "неттакогослова"); // ложных срабатываний нет
            Logger.i("FtsSmokeTest", "FTS4/unicode61: " + (ok ? "OK" : "FAIL"));
            return ok;
        } catch (Throwable t) {
            Logger.e("FtsSmokeTest", "FTS4 недоступен", t);
            return false;
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    private static boolean match(SQLiteDatabase db, String query) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT count(*) FROM t WHERE t MATCH ?", new String[]{query});
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }
}
