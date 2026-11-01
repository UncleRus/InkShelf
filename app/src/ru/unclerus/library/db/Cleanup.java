package ru.unclerus.library.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Удаление книг и чистка «сирот» (M6, диалог D11 «Удалить файл»).
 *
 * <p>Счётчики классификаторов материализованы (таблицы {@code *_book_count}),
 * поэтому каскад внешних ключей их не обновляет: связи книги снимаются и
 * счётчики уменьшаются вручную ({@link MetaDao#clearLinks}) до удаления строки
 * {@code book}. Сами авторы/жанры/серии, у которых не осталось книг, удаляются
 * последним шагом — иначе после удаления книги остались бы пустые группы.
 */
public final class Cleanup {

    private Cleanup() {}

    /**
     * Удалить книгу из каталога: связи и счётчики, затем строку {@code book}
     * (каскадом уходят связи, FTS-запись снимает триггер {@code book_ad}), затем
     * классификаторы, у которых не осталось ни одной книги.
     *
     * <p>Файл на диске здесь <b>не</b> трогается — это отдельное действие
     * экрана карточки.
     *
     * @return {@code true}, если строка книги была удалена
     */
    public static boolean deleteBook(SQLiteDatabase db, final long bookId) {
        if (bookId <= 0) {
            return false;
        }
        final int[] deleted = {0};
        boolean ok = Tx.silent(db, new Tx.Body() {
            @Override
            public void run(SQLiteDatabase d) {
                MetaDao.clearLinks(d, bookId);
                deleted[0] = d.delete(Schema.T_BOOK, "id=?",
                        new String[]{String.valueOf(bookId)});
                deleteOrphans(d);
            }
        });
        return ok && deleted[0] > 0;
    }

    /**
     * Удалить корень (`root`) и все его книги из каталога. Файлы на диске не
     * трогаются. Связи книг и счётчики снимаются как в {@link #deleteBook}, затем
     * одним шагом удаляются классификаторы без книг и сама строка корня.
     *
     * @return число удалённых книг или -1, если транзакция не удалась
     */
    public static int deleteRootWithBooks(SQLiteDatabase db, final long rootId) {
        if (rootId <= 0) {
            return -1;
        }
        final int[] deleted = {0};
        boolean ok = Tx.silent(db, new Tx.Body() {
            @Override
            public void run(SQLiteDatabase d) {
                Cursor c = d.rawQuery("SELECT id FROM " + Schema.T_BOOK + " WHERE root_id=?",
                        new String[]{String.valueOf(rootId)});
                java.util.ArrayList<Long> ids = new java.util.ArrayList<Long>();
                try {
                    while (c.moveToNext()) {
                        ids.add(Long.valueOf(c.getLong(0)));
                    }
                } finally {
                    c.close();
                }
                for (int i = 0; i < ids.size(); i++) {
                    long id = ids.get(i).longValue();
                    MetaDao.clearLinks(d, id);
                    deleted[0] += d.delete(Schema.T_BOOK, "id=?", new String[]{String.valueOf(id)});
                }
                deleteOrphans(d);
                d.delete(Schema.T_ROOT, "id=?", new String[]{String.valueOf(rootId)});
            }
        });
        return ok ? deleted[0] : -1;
    }

    /**
     * Удалить классификаторы без книг. Счётчики уходят каскадом
     * ({@code *_book_count.author_id REFERENCES author(id) ON DELETE CASCADE}).
     */
    public static void deleteOrphans(SQLiteDatabase db) {
        orphan(db, Schema.T_AUTHOR, Schema.T_BOOK_AUTHOR, "author_id");
        orphan(db, Schema.T_GENRE, Schema.T_BOOK_GENRE, "genre_id");
        orphan(db, Schema.T_SERIES, Schema.T_BOOK_SERIES, "series_id");
    }

    private static void orphan(SQLiteDatabase db, String table, String link, String col) {
        db.execSQL("DELETE FROM " + table + " WHERE id NOT IN (SELECT " + col
                + " FROM " + link + ")");
    }
}
