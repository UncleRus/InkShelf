package ru.unclerus.library.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import ru.unclerus.library.util.Text;

/**
 * Поиск: FTS4 + {@code unicode61} с префиксом по последнему слову; для коротких
 * запросов (FTS не умеет искать внутри слова) — {@code LIKE}-fallback по
 * нормализованным полям (PLAN §6.1).
 *
 * <p>Запрос санитизируется: кавычки экранируются, спецсимволы FTS отбрасываются,
 * так что пользовательский ввод не может ни сломать, ни «подделать» {@code MATCH}.
 */
public final class SearchDao {

    /** Порог, ниже которого FTS-префикс слишком груб → LIKE. */
    private static final int LIKE_MIN_LEN = 3;

    private SearchDao() {}

    /**
     * Поиск с окном.
     *
     * @param query сырой ввод пользователя
     * @return курсор со колонками {@link BookDao#COLS_SQL}
     */
    public static Cursor search(SQLiteDatabase db, String query, int offset, int limit) {
        String q = Text.collapse(query);
        if (q.isEmpty()) {
            return empty(db);
        }
        if (q.length() < LIKE_MIN_LEN || !hasToken(q)) {
            return like(db, q, offset, limit);
        }
        Cursor c = fts(db, q, offset, limit);
        if (c != null) {
            return c;
        }
        return like(db, q, offset, limit);
    }

    /**
     * Число совпадений (для строки «Найдено: N»). Путь выбирается ровно так же,
     * как в {@link #search}, но считается {@code COUNT(*)} — без вытягивания
     * строк (важно при 100 000 книг).
     */
    public static int count(SQLiteDatabase db, String query) {
        String q = Text.collapse(query);
        if (q.isEmpty()) {
            return 0;
        }
        if (q.length() < LIKE_MIN_LEN || !hasToken(q)) {
            return likeCount(db, q);
        }
        int n = ftsCount(db, q);
        return n >= 0 ? n : likeCount(db, q);
    }

    // ------------------------------------------------------------------

    private static Cursor fts(SQLiteDatabase db, String q, int offset, int limit) {
        String match = toMatch(q);
        if (match == null) {
            return null;
        }
        String sql = "SELECT " + BookDao.COLS_SQL
                + " FROM " + Schema.T_FTS + " f JOIN " + Schema.T_BOOK + " b ON b.id=f.rowid"
                + " WHERE " + Schema.T_FTS + " MATCH ?"
                + " ORDER BY (b.title_letter='" + Text.OTHER + "'), b.title_letter, b.title_norm, b.id"
                + " LIMIT ? OFFSET ?";
        try {
            return db.rawQuery(sql, new String[]{match, String.valueOf(limit), String.valueOf(offset)});
        } catch (Throwable t) {
            return null;
        }
    }

    private static Cursor like(SQLiteDatabase db, String q, int offset, int limit) {
        String norm = Text.norm(q);
        String hay = "'%" + norm.replace("'", "''") + "%'";
        String sql = "SELECT " + BookDao.COLS_SQL
                + " FROM " + Schema.T_BOOK + " b"
                + " WHERE b.title_norm LIKE " + hay
                + " OR b.authors_norm LIKE " + hay
                + " OR b.series_norm LIKE " + hay
                + " ORDER BY (b.title_letter='" + Text.OTHER + "'), b.title_letter, b.title_norm, b.id"
                + " LIMIT ? OFFSET ?";
        return db.rawQuery(sql, new String[]{String.valueOf(limit), String.valueOf(offset)});
    }

    private static Cursor empty(SQLiteDatabase db) {
        return db.rawQuery("SELECT " + BookDao.COLS_SQL + " FROM " + Schema.T_BOOK + " b WHERE 1=0", null);
    }

    /** {@code COUNT(*)} по FTS-пути; {@code -1} — путь неприменим (тогда LIKE). */
    private static int ftsCount(SQLiteDatabase db, String q) {
        String match = toMatch(q);
        if (match == null) {
            return -1;
        }
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + Schema.T_FTS + " f JOIN " + Schema.T_BOOK
                            + " b ON b.id=f.rowid WHERE " + Schema.T_FTS + " MATCH ?",
                    new String[]{match});
            return c.moveToFirst() ? c.getInt(0) : -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /** {@code COUNT(*)} по LIKE-пути (короткие запросы, подстрока). */
    private static int likeCount(SQLiteDatabase db, String q) {
        String hay = "'%" + Text.norm(q).replace("'", "''") + "%'";
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + Schema.T_BOOK + " b"
                            + " WHERE b.title_norm LIKE " + hay
                            + " OR b.authors_norm LIKE " + hay
                            + " OR b.series_norm LIKE " + hay,
                    null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * FTS-выражение: все слова, кроме последнего, — точные фразы; последнее —
     * префикс {@code слово*} (FTS4 понимает звёздочку только у «голого» токена:
     * {@code "слово"*} префиксом <b>не</b> является и даёт 0 совпадений).
     * Пустое выражение → {@code null}.
     */
    private static String toMatch(String q) {
        String[] words = q.split("\\s+");
        List<String> toks = new ArrayList<String>();
        for (int i = 0; i < words.length; i++) {
            String tok = words[i].replaceAll("[^\\p{L}\\p{N}_]", "");
            if (!tok.isEmpty()) {
                toks.add(tok);
            }
        }
        if (toks.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < toks.size(); i++) {
            if (i > 0) {
                b.append(' ');
            }
            if (i == toks.size() - 1) {
                b.append(toks.get(i)).append('*');
            } else {
                b.append('"').append(toks.get(i)).append('"');
            }
        }
        return b.toString();
    }

    /** Есть ли в запросе хотя бы один токен из букв/цифр (иначе FTS бессмысленен). */
    private static boolean hasToken(String q) {
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        return false;
    }
}
