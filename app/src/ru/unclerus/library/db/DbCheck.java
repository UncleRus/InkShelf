package ru.unclerus.library.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Проверка БД для экрана «Диагностика» (S6) и для логов холодного старта:
 * схема на месте, счётчики читаются, оконные выборки и поиск не падают.
 */
public final class DbCheck {

    private DbCheck() {}

    /** Короткий отчёт одной-двумя строками. */
    public static String report(SQLiteDatabase db) {
        StringBuilder b = new StringBuilder();
        try {
            int tables = (int) BookDao.scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table'");
            boolean fts = BookDao.scalar(db,
                    "SELECT COUNT(*) FROM sqlite_master WHERE name='" + Schema.T_FTS + "'") > 0;
            int triggers = (int) BookDao.scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'");

            b.append("схема v").append(db.getVersion())
                    .append(" · таблиц: ").append(tables)
                    .append(" · FTS4: ").append(fts ? "есть" : "нет")
                    .append(" · триггеров: ").append(triggers).append('\n')
                    .append("книг: ").append(CountDao.books(db))
                    .append(" · авторов: ").append(CountDao.authors(db))
                    .append(" · жанров: ").append(CountDao.genres(db))
                    .append(" · серий: ").append(CountDao.series(db)).append('\n')
                    .append("корней: ").append(CountDao.roots(db))
                    .append(" · доступно: ").append(CountDao.booksAvailable(db))
                    .append(" · отсутствует: ").append(CountDao.booksMissing(db));

            // Проверяем, что оконные выборки и поиск выполняются на текущей схеме.
            close(BookDao.pageAll(db, BookDao.SORT_TITLE, 0, 1));
            close(BookDao.pageByDate(db, 0, 1));
            close(BookDao.pageHistory(db, 0, 1));
            close(AuthorDao.page(db, 0, 1));
            close(AuthorDao.sections(db));
            close(GenreDao.page(db, false, 0, 1));
            close(SeriesDao.page(db, 0, 1));
            close(SearchDao.search(db, "тест", 0, 1));
            b.append("\nвыборки: ок");

            b.append("\nFTS-триггеры: ").append(ftsRoundTrip(db) ? "ок" : "сбой");
        } catch (Throwable t) {
            b.append("\nошибка проверки: ").append(t);
        }
        return b.toString();
    }

    /**
     * Проверка связки «book ↔ book_fts»: во временной транзакции вставляем книгу,
     * убеждаемся, что триггер занёс её в индекс (MATCH находит), и откатываем.
     */
    private static boolean ftsRoundTrip(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("path", "\u0000selftest");
            v.put("title", "Проверочная книга");
            v.put("title_norm", ru.unclerus.library.util.Text.norm("Проверочная книга"));
            v.put("title_letter", ru.unclerus.library.util.Text.firstLetter("Проверочная книга"));
            v.put("authors_text", "Иван Тестов");
            v.put("series_text", "");
            v.put("authors_norm", ru.unclerus.library.util.Text.norm("Иван Тестов"));
            v.put("series_norm", "");
            long id = db.insert(Schema.T_BOOK, null, v);
            if (id < 0) {
                return false;
            }
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + Schema.T_FTS
                    + " WHERE " + Schema.T_FTS + " MATCH ?", new String[]{"провер*"});
            try {
                return c.moveToFirst() && c.getLong(0) > 0;
            } finally {
                c.close();
            }
        } finally {
            db.endTransaction(); // без setTransactionSuccessful → откат
        }
    }

    private static void close(Cursor c) {
        if (c != null) {
            c.close();
        }
    }
}
