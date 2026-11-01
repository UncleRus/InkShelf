package ru.unclerus.library.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Серии: только группы с {@code cnt > 0}, сортировка по названию. */
public final class SeriesDao {

    /** Колонки строки серии: id, name, cnt. */
    public static final String COLS_SQL = "s.id, s.name, c.cnt";
    public static final int C_ID = 0;
    public static final int C_NAME = 1;
    public static final int C_COUNT = 2;

    private static final String FROM =
            Schema.T_SERIES + " s JOIN " + Schema.T_SERIES_COUNT + " c ON c.series_id=s.id";
    private static final String WHERE = "c.cnt>0";
    /** «ё»→«е» и нижний регистр уже в name_norm. */
    private static final String ORDER = "s.name_norm, s.id";

    private SeriesDao() {}

    public static Cursor page(SQLiteDatabase db, int offset, int limit) {
        return db.rawQuery("SELECT " + COLS_SQL + " FROM " + FROM + " WHERE " + WHERE
                        + " ORDER BY " + ORDER + " LIMIT ? OFFSET ?",
                new String[]{String.valueOf(limit), String.valueOf(offset)});
    }

    public static Cursor one(SQLiteDatabase db, long id) {
        return db.rawQuery("SELECT " + COLS_SQL + " FROM " + FROM + " WHERE s.id=?",
                new String[]{String.valueOf(id)});
    }

    public static int count(SQLiteDatabase db) {
        return (int) BookDao.scalar(db, "SELECT COUNT(*) FROM " + FROM + " WHERE " + WHERE);
    }
}
