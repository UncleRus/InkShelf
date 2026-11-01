package ru.unclerus.library.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import ru.unclerus.library.fmt.BookMeta;
import ru.unclerus.library.fmt.GenreTable;
import ru.unclerus.library.util.Text;

/**
 * Запись метаданных Фазы B (PLAN §7): авторы/жанры/серии, связи и
 * материализованные счётчики. Все методы вызываются <b>внутри транзакции
 * батча</b> (её открывает {@code scan.ParsePhase}).
 *
 * <p>Повторный разбор книги (файл изменился) безопасен: {@link #clearLinks}
 * снимает старые связи и уменьшает счётчики, дальше пишутся новые.
 */
public final class MetaDao {

    private MetaDao() {}

    /**
     * Перенести метаданные книги в БД. {@code meta == null} — разбор не удался:
     * {@code meta_ok=0}, связи и тексты классификаторов очищаются, название
     * остаётся файловым.
     */
    public static void writeMeta(SQLiteDatabase db, long bookId, BookMeta meta, String error) {
        clearLinks(db, bookId);
        if (meta == null) {
            markFailed(db, bookId, error);
            return;
        }

        List<Long> authorIds = new ArrayList<Long>();
        StringBuilder authorsText = new StringBuilder();
        for (int i = 0; i < meta.authors.size(); i++) {
            BookMeta.Author a = meta.authors.get(i);
            long aid = upsertAuthor(db, a);
            if (aid <= 0 || authorIds.contains(Long.valueOf(aid))) {
                continue;
            }
            authorIds.add(Long.valueOf(aid));
            link(db, Schema.T_BOOK_AUTHOR, "author_id", bookId, aid);
            inc(db, Schema.T_AUTHOR_COUNT, "author_id", aid);
            if (authorsText.length() > 0) {
                authorsText.append(", ");
            }
            authorsText.append(a.name);
        }

        List<Long> genreIds = new ArrayList<Long>();
        for (int i = 0; i < meta.genres.size(); i++) {
            long gid = upsertGenre(db, meta.genres.get(i));
            if (gid <= 0 || genreIds.contains(Long.valueOf(gid))) {
                continue;
            }
            genreIds.add(Long.valueOf(gid));
            link(db, Schema.T_BOOK_GENRE, "genre_id", bookId, gid);
            inc(db, Schema.T_GENRE_COUNT, "genre_id", gid);
        }

        String seriesText = "";
        if (meta.hasSeries()) {
            long sid = upsertSeries(db, meta.seriesName);
            if (sid > 0) {
                linkSeries(db, bookId, sid, meta.seriesNumber);
                inc(db, Schema.T_SERIES_COUNT, "series_id", sid);
                seriesText = meta.seriesName;
            }
        }

        ContentValues v = new ContentValues();
        if (!Text.isBlank(meta.title)) {
            String title = meta.title;
            v.put("title", title);
            v.put("title_norm", Text.norm(title));
            v.put("title_letter", Text.firstLetter(title));
        }
        v.put("authors_text", authorsText.toString());
        v.put("authors_norm", Text.norm(authorsText.toString()));
        v.put("series_text", seriesText);
        v.put("series_norm", Text.norm(seriesText));
        if (meta.annotation != null) {
            v.put("annotation", meta.annotation);
        }
        if (!Text.isBlank(meta.entryId)) {
            v.put("entry_id", meta.entryId);
        }
        if (!Text.isBlank(meta.coverEntry)) {
            v.put("cover_entry", meta.coverEntry);
        }
        v.put("meta_ok", 1);
        v.put("needs_parse", 0);
        v.put("indexed_at", System.currentTimeMillis());
        db.update(Schema.T_BOOK, v, "id=?", new String[]{String.valueOf(bookId)});
    }

    /** Снять связи книги и уменьшить счётчики (перед записью новых связей). */
    public static void clearLinks(SQLiteDatabase db, long bookId) {
        decAll(db, Schema.T_BOOK_AUTHOR, "author_id", Schema.T_AUTHOR_COUNT, bookId);
        decAll(db, Schema.T_BOOK_GENRE, "genre_id", Schema.T_GENRE_COUNT, bookId);
        decAll(db, Schema.T_BOOK_SERIES, "series_id", Schema.T_SERIES_COUNT, bookId);
        String[] args = {String.valueOf(bookId)};
        db.delete(Schema.T_BOOK_AUTHOR, "book_id=?", args);
        db.delete(Schema.T_BOOK_GENRE, "book_id=?", args);
        db.delete(Schema.T_BOOK_SERIES, "book_id=?", args);
    }

    // ------------------------------------------------------------------
    // Классификаторы
    // ------------------------------------------------------------------

    public static long upsertAuthor(SQLiteDatabase db, BookMeta.Author a) {
        if (a == null) {
            return -1;
        }
        String name = Text.collapse(a.name);
        if (name.isEmpty()) {
            return -1;
        }
        String norm = Text.norm(name);
        long id = scalar(db, "SELECT id FROM " + Schema.T_AUTHOR + " WHERE name_norm=?",
                new String[]{norm});
        if (id > 0) {
            return id;
        }
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("name_norm", norm);
        v.put("sort_name", Text.isBlank(a.sortName) ? name : a.sortName);
        v.put("surname", a.surname == null ? "" : a.surname);
        v.put("letter", Text.firstLetter(Text.isBlank(a.surname) ? name : a.surname));
        id = db.insertWithOnConflict(Schema.T_AUTHOR, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id <= 0) {
            id = scalar(db, "SELECT id FROM " + Schema.T_AUTHOR + " WHERE name_norm=?",
                    new String[]{norm});
        }
        return id;
    }

    public static long upsertGenre(SQLiteDatabase db, String rawCode) {
        String code = GenreTable.normalizeCode(rawCode);
        if (code.isEmpty()) {
            return -1;
        }
        long id = scalar(db, "SELECT id FROM " + Schema.T_GENRE + " WHERE code=?",
                new String[]{code});
        if (id > 0) {
            return id;
        }
        String display = GenreTable.displayName(code);
        String displayEn = GenreTable.displayNameEn(code);
        ContentValues v = new ContentValues();
        v.put("code", code);
        v.put("display_name", display == null ? code : display);
        v.put("display_name_en", displayEn == null ? code : displayEn);
        id = db.insertWithOnConflict(Schema.T_GENRE, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id <= 0) {
            id = scalar(db, "SELECT id FROM " + Schema.T_GENRE + " WHERE code=?",
                    new String[]{code});
        }
        return id;
    }

    public static long upsertSeries(SQLiteDatabase db, String name) {
        String n = Text.collapse(name);
        if (n.isEmpty()) {
            return -1;
        }
        String norm = Text.norm(n);
        long id = scalar(db, "SELECT id FROM " + Schema.T_SERIES + " WHERE name_norm=?",
                new String[]{norm});
        if (id > 0) {
            return id;
        }
        ContentValues v = new ContentValues();
        v.put("name", n);
        v.put("name_norm", norm);
        id = db.insertWithOnConflict(Schema.T_SERIES, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id <= 0) {
            id = scalar(db, "SELECT id FROM " + Schema.T_SERIES + " WHERE name_norm=?",
                    new String[]{norm});
        }
        return id;
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    private static void markFailed(SQLiteDatabase db, long bookId, String error) {
        ContentValues v = new ContentValues();
        // Классификаторы очищены — синхронизируем денормализованные тексты и FTS.
        v.put("authors_text", "");
        v.put("authors_norm", "");
        v.put("series_text", "");
        v.put("series_norm", "");
        v.put("meta_ok", 0);
        v.put("needs_parse", 0);
        v.put("indexed_at", System.currentTimeMillis());
        db.update(Schema.T_BOOK, v, "id=?", new String[]{String.valueOf(bookId)});
    }

    private static void link(SQLiteDatabase db, String table, String col, long bookId, long id) {
        ContentValues v = new ContentValues();
        v.put("book_id", bookId);
        v.put(col, id);
        if (Schema.T_BOOK_AUTHOR.equals(table)) {
            v.put("ord", 0);
        }
        db.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void linkSeries(SQLiteDatabase db, long bookId, long seriesId, String number) {
        ContentValues v = new ContentValues();
        v.put("book_id", bookId);
        v.put("series_id", seriesId);
        v.put("number", number);
        Integer sort = numberSort(number);
        if (sort == null) {
            v.putNull("number_sort");
        } else {
            v.put("number_sort", sort);
        }
        db.insertWithOnConflict(Schema.T_BOOK_SERIES, null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Ведущее целое из номера серии («7», «7.5» → 7; «I» → null). */
    private static Integer numberSort(String number) {
        if (number == null) {
            return null;
        }
        String s = number.trim();
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return null;
        }
        try {
            return Integer.valueOf(s.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void inc(SQLiteDatabase db, String table, String col, long id) {
        db.execSQL("INSERT OR IGNORE INTO " + table + "(" + col + ",cnt) VALUES(?,0)",
                new Object[]{Long.valueOf(id)});
        db.execSQL("UPDATE " + table + " SET cnt=cnt+1 WHERE " + col + "=?",
                new Object[]{Long.valueOf(id)});
    }

    private static void decAll(SQLiteDatabase db, String linkTable, String col,
                               String countTable, long bookId) {
        db.execSQL("UPDATE " + countTable + " SET cnt=cnt-1 WHERE cnt>0 AND " + col
                        + " IN (SELECT " + col + " FROM " + linkTable + " WHERE book_id=?)",
                new Object[]{Long.valueOf(bookId)});
    }

    private static long scalar(SQLiteDatabase db, String sql, String[] args) {
        Cursor c = null;
        try {
            c = db.rawQuery(sql, args);
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }
}
