package ru.unclerus.library.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Жанры: только группы с {@code cnt > 0}, сортировка по названию **текущей локали**
 * — русское ({@code display_name}) либо английское ({@code display_name_en}).
 * Какую колонку читать, решает вызывающий (`util.Lang`); и выборка, и сортировка
 * идут по одной и той же колонке, поэтому порядок всегда согласован с показом.
 */
public final class GenreDao {

    /** Колонки строки жанра: id, name (локаль), code, cnt. */
    public static final int C_ID = 0;
    public static final int C_NAME = 1;
    public static final int C_CODE = 2;
    public static final int C_COUNT = 3;

    private static final String FROM =
            Schema.T_GENRE + " g JOIN " + Schema.T_GENRE_COUNT + " c ON c.genre_id=g.id";
    private static final String WHERE = "c.cnt>0";

    private GenreDao() {}

    /** Колонка-имя для выбранной локали. */
    public static String nameCol(boolean en) {
        return en ? "g.display_name_en" : "g.display_name";
    }

    public static Cursor page(SQLiteDatabase db, boolean en, int offset, int limit) {
        String col = nameCol(en);
        return db.rawQuery("SELECT g.id, " + col + " AS name, g.code, c.cnt FROM " + FROM
                        + " WHERE " + WHERE + " ORDER BY name, g.id LIMIT ? OFFSET ?",
                new String[]{String.valueOf(limit), String.valueOf(offset)});
    }

    public static Cursor one(SQLiteDatabase db, boolean en, long id) {
        String col = nameCol(en);
        return db.rawQuery("SELECT g.id, " + col + " AS name, g.code, c.cnt FROM " + FROM
                        + " WHERE g.id=?",
                new String[]{String.valueOf(id)});
    }

    public static int count(SQLiteDatabase db) {
        return (int) BookDao.scalar(db, "SELECT COUNT(*) FROM " + FROM + " WHERE " + WHERE);
    }
}
