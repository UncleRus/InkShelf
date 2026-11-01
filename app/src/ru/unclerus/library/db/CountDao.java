package ru.unclerus.library.db;

import android.database.sqlite.SQLiteDatabase;

/**
 * Материализованные счётчики и сводные числа для вкладок и пустых состояний.
 * Никаких {@code GROUP BY} «в лоб» на каждый показ — считаем по таблицам-счётчикам.
 */
public final class CountDao {

    private CountDao() {}

    // --- корни ---
    public static int roots(SQLiteDatabase db) {
        return (int) BookDao.scalar(db, "SELECT COUNT(*) FROM " + Schema.T_ROOT);
    }

    // --- книги ---
    public static int books(SQLiteDatabase db) {
        return BookDao.countAll(db);
    }

    public static int booksAvailable(SQLiteDatabase db) {
        return BookDao.countAvailable(db);
    }

    public static int booksMissing(SQLiteDatabase db) {
        return BookDao.countMissing(db);
    }

    // --- классификаторы (только группы с книгами) ---
    public static int authors(SQLiteDatabase db) {
        return (int) BookDao.scalar(db,
                "SELECT COUNT(*) FROM " + Schema.T_AUTHOR_COUNT + " WHERE cnt>0");
    }

    public static int genres(SQLiteDatabase db) {
        return (int) BookDao.scalar(db,
                "SELECT COUNT(*) FROM " + Schema.T_GENRE_COUNT + " WHERE cnt>0");
    }

    public static int series(SQLiteDatabase db) {
        return (int) BookDao.scalar(db,
                "SELECT COUNT(*) FROM " + Schema.T_SERIES_COUNT + " WHERE cnt>0");
    }

    public static int history(SQLiteDatabase db) {
        return BookDao.countHistory(db);
    }

    /**
     * Количество записей во вкладке по её индексу (порядок — как в MainActivity):
     * 0 авторы, 1 жанры, 2 серии, 3 дата (книги), 4 история, 5 все книги.
     */
    public static int tab(SQLiteDatabase db, int tab) {
        switch (tab) {
            case 0:
                return authors(db);
            case 1:
                return genres(db);
            case 2:
                return series(db);
            case 3:
                return books(db);
            case 4:
                return history(db);
            default:
                return books(db);
        }
    }

    /** Нужна ли миграция/пересчёт счётчиков (на случай прерванной индексации). */
    public static boolean needsRepair(SQLiteDatabase db) {
        return scalarBool(db, "SELECT EXISTS(SELECT 1 FROM " + Schema.T_BOOK_AUTHOR
                + " x LEFT JOIN " + Schema.T_BOOK + " b ON b.id=x.book_id WHERE b.id IS NULL)");
    }

    private static boolean scalarBool(SQLiteDatabase db, String sql) {
        return BookDao.scalar(db, sql) != 0;
    }
}
