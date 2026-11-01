package ru.unclerus.library.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import ru.unclerus.library.util.Text;

/**
 * Авторы: списки строятся только по группам с {@code cnt > 0} (PLAN §6.5).
 * Раздел (буква фамилии) хранится в {@code author.letter}, поэтому выборка
 * разделов и переход по алфавитному указателю — по индексу.
 */
public final class AuthorDao {

    /** Колонки строки автора: id, name, surname, letter, cnt. */
    public static final String COLS_SQL = "a.id, a.name, a.surname, a.letter, c.cnt";
    public static final int C_ID = 0;
    public static final int C_NAME = 1;
    public static final int C_SURNAME = 2;
    public static final int C_LETTER = 3;
    public static final int C_COUNT = 4;

    private static final String FROM =
            Schema.T_AUTHOR + " a JOIN " + Schema.T_AUTHOR_COUNT + " c ON c.author_id=a.id";
    private static final String WHERE = "c.cnt>0";
    /** Разделы идут по букве, «#» — в конец. */
    private static final String ORDER = "(a.letter='" + Text.OTHER + "'), a.letter, a.sort_name, a.id";

    private AuthorDao() {}

    public static Cursor page(SQLiteDatabase db, int offset, int limit) {
        return db.rawQuery("SELECT " + COLS_SQL + " FROM " + FROM + " WHERE " + WHERE
                        + " ORDER BY " + ORDER + " LIMIT ? OFFSET ?",
                new String[]{String.valueOf(limit), String.valueOf(offset)});
    }

    /** Строка автора по id (для карточки книги). */
    public static Cursor one(SQLiteDatabase db, long id) {
        return db.rawQuery("SELECT " + COLS_SQL + " FROM " + FROM + " WHERE a.id=?",
                new String[]{String.valueOf(id)});
    }

    /**
     * Присутствующие разделы: letter + число авторов + смещение первой записи
     * раздела (для перехода по алфавитному указателю). Порядок — как в {@link #ORDER}.
     */
    public static Cursor sections(SQLiteDatabase db) {
        return db.rawQuery(
                "SELECT a.letter, COUNT(*) AS cnt FROM " + FROM + " WHERE " + WHERE
                        + " GROUP BY a.letter ORDER BY (a.letter='" + Text.OTHER + "'), a.letter",
                null);
    }

    public static int count(SQLiteDatabase db) {
        return (int) BookDao.scalar(db, "SELECT COUNT(*) FROM " + FROM + " WHERE " + WHERE);
    }
}
