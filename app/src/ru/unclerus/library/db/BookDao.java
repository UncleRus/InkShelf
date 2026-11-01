package ru.unclerus.library.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import ru.unclerus.library.util.Text;

/**
 * Выборки книг. Все списки — оконные ({@code LIMIT/OFFSET}), в памяти только
 * видимая страница (важно при 1.6 ГБ ОЗУ). Одна и та же колонка-набор
 * {@link #COLS_SQL} идёт в адаптер, порядок колонок фиксирован.
 *
 * <p>Размер страницы приходит снаружи ({@code ListMetrics.perPage(context, ...)}) —
 * здесь он не зашит.
 */
public final class BookDao {

    public static final String SORT_TITLE = "title";
    public static final String SORT_FILE_DATE = "file_date";
    public static final String SORT_INDEX_DATE = "index_date";
    public static final String SORT_AUTHOR = "author";
    public static final String SORT_SERIES = "series";

    /**
     * Режимы, доступные пользователю в `D8` (порядок — как в диалоге). Значение,
     * сохранённое в настройках, проверяется через {@link #isValidSort}.
     */
    public static final String[] SORTABLE = {
            SORT_TITLE, SORT_AUTHOR, SORT_FILE_DATE, SORT_INDEX_DATE,
    };

    /** Колонки строки книги в порядке для адаптера. */
    public static final String COLS_SQL =
            "b.id, b.title, b.authors_text, b.series_text, b.format, "
                    + "b.annotation, b.cover_state, b.cover_cache_path, "
                    + "b.availability, b.last_opened_at, b.open_count, b.path, "
                    + "b.file_size, b.file_mtime, b.cover_entry";

    public static final int C_ID = 0;
    public static final int C_TITLE = 1;
    public static final int C_AUTHORS = 2;
    public static final int C_SERIES = 3;
    public static final int C_FORMAT = 4;
    public static final int C_ANNOTATION = 5;
    public static final int C_COVER_STATE = 6;
    public static final int C_COVER_PATH = 7;
    public static final int C_AVAILABILITY = 8;
    public static final int C_OPENED_AT = 9;
    public static final int C_OPEN_COUNT = 10;
    public static final int C_PATH = 11;
    public static final int C_FILE_SIZE = 12;
    public static final int C_MTIME = 13;
    public static final int C_COVER_ENTRY = 14;

    // --- результат {@link #touchSeen} ---
    /** Файла не было в БД — добавлен. */
    public static final int TOUCH_ABSENT = 0;
    /** Файл есть, размер и mtime совпали — ничего не изменилось. */
    public static final int TOUCH_UNCHANGED = 1;
    /** Файл есть, но изменился — сброшен {@code needs_parse}. */
    public static final int TOUCH_CHANGED = 2;

    private BookDao() {}

    // ------------------------------------------------------------------
    // Порядок сортировки
    // ------------------------------------------------------------------

    /**
     * Выражение ORDER BY по имени сортировки. Значения вне белого списка
     * приводятся к «по названию» — SQL-инъекция невозможна.
     */
    public static String orderBy(String sort) {
        if (SORT_FILE_DATE.equals(sort)) {
            return "b.file_mtime DESC, b.title_norm, b.id";
        }
        if (SORT_INDEX_DATE.equals(sort)) {
            return "b.indexed_at DESC, b.title_norm, b.id";
        }
        if (SORT_AUTHOR.equals(sort)) {
            return "b.authors_text, b.title_norm, b.id";
        }
        if (SORT_SERIES.equals(sort)) {
            return "b.series_text, b.title_norm, b.id";
        }
        // по названию: разделы-буквы идут подряд; «#» — в конец.
        return "(b.title_letter='" + Text.OTHER + "'), b.title_letter, b.title_norm, b.id";
    }

    /** Режим из {@link #SORTABLE}? Значения вне белого списка игнорируются. */
    public static boolean isValidSort(String sort) {
        if (sort == null) {
            return false;
        }
        for (int i = 0; i < SORTABLE.length; i++) {
            if (SORTABLE[i].equals(sort)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Оконные выборки
    // ------------------------------------------------------------------

    /** «Все книги» выбранной сортировки. */
    public static Cursor pageAll(SQLiteDatabase db, String sort, int offset, int limit) {
        return query(db, Schema.T_BOOK + " b", null, null, orderBy(sort), offset, limit);
    }

    /**
     * Разделы по первой букве названия (для группировки «Все книги» по
     * названию). Порядок тот же, что в {@link #orderBy} для сортировки по
     * названию, поэтому разделы идут подряд: числа → латиница → кириллица → «#».
     */
    public static Cursor titleSections(SQLiteDatabase db) {
        return db.rawQuery("SELECT b.title_letter, COUNT(*) FROM " + Schema.T_BOOK
                + " b GROUP BY b.title_letter ORDER BY (b.title_letter='"
                + Text.OTHER + "'), b.title_letter", null);
    }

    /**
     * Количества книг по группам даты (Сегодня/Вчера/неделя/месяц/раньше).
     * {@code bounds} — из {@code ui.DateGroups.bounds()}: 4 нисходящие границы.
     */
    public static int[] dateBucketCounts(SQLiteDatabase db, long[] bounds) {
        String sql = "SELECT "
                + "SUM(CASE WHEN file_mtime>=? THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN file_mtime>=? AND file_mtime<? THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN file_mtime>=? AND file_mtime<? THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN file_mtime>=? AND file_mtime<? THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN file_mtime<? THEN 1 ELSE 0 END) FROM " + Schema.T_BOOK;
        Cursor c = null;
        try {
            c = db.rawQuery(sql, new String[]{
                    String.valueOf(bounds[0]),
                    String.valueOf(bounds[1]), String.valueOf(bounds[0]),
                    String.valueOf(bounds[2]), String.valueOf(bounds[1]),
                    String.valueOf(bounds[3]), String.valueOf(bounds[2]),
                    String.valueOf(bounds[3]),
            });
            int[] r = new int[5];
            if (c.moveToFirst()) {
                for (int i = 0; i < 5; i++) {
                    r[i] = c.isNull(i) ? 0 : c.getInt(i);
                }
            }
            return r;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /** Книги в диапазоне отметок времени {@code [from, to)} (группа даты в S2). */
    public static Cursor pageByDateRange(SQLiteDatabase db, long from, long to,
                                         int offset, int limit) {
        return query(db, Schema.T_BOOK + " b", "b.file_mtime>=? AND b.file_mtime<?",
                new String[]{String.valueOf(from), String.valueOf(to)},
                "b.file_mtime DESC, b.id", offset, limit);
    }

    /** Количество книг в диапазоне отметок времени {@code [from, to)}. */
    public static int countByDateRange(SQLiteDatabase db, long from, long to) {
        return (int) scalar(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK
                        + " WHERE file_mtime>=? AND file_mtime<?",
                new String[]{String.valueOf(from), String.valueOf(to)});
    }

    /** Книги автора. */
    public static Cursor pageByAuthor(SQLiteDatabase db, long authorId, String sort,
                                      int offset, int limit) {
        return query(db,
                Schema.T_BOOK + " b JOIN " + Schema.T_BOOK_AUTHOR + " x ON x.book_id=b.id",
                "x.author_id=?", new String[]{String.valueOf(authorId)},
                orderBy(sort), offset, limit);
    }

    /**
     * Книги автора, сгруппированные по сериям (S2, решение оператора): сперва
     * группы серий по названию, «Без серии» — в конце; внутри группы — по номеру
     * в серии, затем по названию. Порядок обязан совпадать с
     * {@link #authorSeriesSections} — иначе разделы и строки разъедутся.
     */
    public static Cursor pageByAuthorGrouped(SQLiteDatabase db, long authorId,
                                             int offset, int limit) {
        return query(db,
                Schema.T_BOOK + " b JOIN " + Schema.T_BOOK_AUTHOR + " a ON a.book_id=b.id"
                        + " LEFT JOIN " + Schema.T_BOOK_SERIES + " x ON x.book_id=b.id",
                "a.author_id=?", new String[]{String.valueOf(authorId)},
                "(COALESCE(b.series_norm,'')=''), COALESCE(b.series_norm,''), "
                        + "(x.number_sort IS NULL), x.number_sort, b.title_norm, b.id",
                offset, limit);
    }

    /**
     * Разделы книг автора по сериям: (заголовок, количество) в том же порядке,
     * что {@link #pageByAuthorGrouped}. Книги без серии получают заголовок
     * {@code noneLabel} («Без серии») и идут последней группой.
     */
    public static Cursor authorSeriesSections(SQLiteDatabase db, long authorId, String noneLabel) {
        return db.rawQuery(
                "SELECT CASE WHEN COALESCE(b.series_norm,'')='' THEN ? ELSE MAX(b.series_text) END,"
                        + " COUNT(*) FROM " + Schema.T_BOOK + " b"
                        + " JOIN " + Schema.T_BOOK_AUTHOR + " a ON a.book_id=b.id"
                        + " WHERE a.author_id=?"
                        + " GROUP BY COALESCE(b.series_norm,'')"
                        + " ORDER BY (COALESCE(b.series_norm,'')=''), COALESCE(b.series_norm,'')",
                new String[]{noneLabel, String.valueOf(authorId)});
    }

    /** Книги жанра. */
    public static Cursor pageByGenre(SQLiteDatabase db, long genreId, String sort,
                                     int offset, int limit) {
        return query(db,
                Schema.T_BOOK + " b JOIN " + Schema.T_BOOK_GENRE + " x ON x.book_id=b.id",
                "x.genre_id=?", new String[]{String.valueOf(genreId)},
                orderBy(sort), offset, limit);
    }

    /** Книги серии (по номеру в серии, затем по названию). */
    public static Cursor pageBySeries(SQLiteDatabase db, long seriesId, int offset, int limit) {
        return query(db,
                Schema.T_BOOK + " b JOIN " + Schema.T_BOOK_SERIES + " x ON x.book_id=b.id",
                "x.series_id=?", new String[]{String.valueOf(seriesId)},
                "x.number_sort, b.title_norm, b.id", offset, limit);
    }

    /** Группа даты: книги по дате файла (заголовки дат рисует адаптер). */
    public static Cursor pageByDate(SQLiteDatabase db, int offset, int limit) {
        return query(db, Schema.T_BOOK + " b", null, null,
                "b.file_mtime DESC, b.id", offset, limit);
    }

    /** «Недавно открытые» (одна группа истории). */
    public static Cursor pageHistory(SQLiteDatabase db, int offset, int limit) {
        return query(db, Schema.T_BOOK + " b", "b.last_opened_at>0", null,
                "b.last_opened_at DESC, b.id", offset, limit);
    }

    /**
     * Отметить открытие книги (FR-5): {@code open_count + 1}, время и пакет
     * читалки, в которой книга была открыта.
     */
    public static void recordOpen(SQLiteDatabase db, long bookId, String pkg, long ts) {
        db.execSQL("UPDATE " + Schema.T_BOOK
                        + " SET open_count=open_count+1, last_opened_at=?,"
                        + " last_opened_by_package=? WHERE id=?",
                new Object[]{ts, pkg == null ? "" : pkg, bookId});
    }

    /** Одна книга по id. */
    public static Cursor one(SQLiteDatabase db, long bookId) {
        return db.rawQuery("SELECT " + COLS_SQL + " FROM " + Schema.T_BOOK + " b WHERE b.id=?",
                new String[]{String.valueOf(bookId)});
    }

    /**
     * Авторы книги в порядке из файла. Колонки: {@code id, name} — карточка
     * делает имена ссылками, поэтому нужен идентификатор для перехода в список.
     */
    public static Cursor authorsOf(SQLiteDatabase db, long bookId) {
        return db.rawQuery("SELECT a.id, a.name FROM " + Schema.T_BOOK_AUTHOR + " x"
                        + " JOIN " + Schema.T_AUTHOR + " a ON a.id=x.author_id"
                        + " WHERE x.book_id=? ORDER BY x.ord, a.name",
                new String[]{String.valueOf(bookId)});
    }

    /** Жанры книги: {@code id, name} текущей локали, по алфавиту. */
    public static Cursor genresOf(SQLiteDatabase db, long bookId, boolean en) {
        String col = GenreDao.nameCol(en);
        return db.rawQuery("SELECT g.id, " + col + " FROM " + Schema.T_BOOK_GENRE + " x"
                        + " JOIN " + Schema.T_GENRE + " g ON g.id=x.genre_id"
                        + " WHERE x.book_id=? ORDER BY " + col,
                new String[]{String.valueOf(bookId)});
    }

    /** Серии книги: {@code id, name, number} (номер может быть {@code NULL}). */
    public static Cursor seriesOf(SQLiteDatabase db, long bookId) {
        return db.rawQuery("SELECT s.id, s.name, x.number FROM " + Schema.T_BOOK_SERIES + " x"
                        + " JOIN " + Schema.T_SERIES + " s ON s.id=x.series_id"
                        + " WHERE x.book_id=? ORDER BY s.name",
                new String[]{String.valueOf(bookId)});
    }

    // ------------------------------------------------------------------
    // Счётчики (для полосы страниц и пустых состояний)
    // ------------------------------------------------------------------

    public static int countAll(SQLiteDatabase db) {
        return (int) scalar(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK);
    }

    public static int countAvailable(SQLiteDatabase db) {
        return (int) scalar(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK
                + " WHERE availability='" + Schema.AVAIL_OK + "'");
    }

    public static int countMissing(SQLiteDatabase db) {
        return (int) scalar(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK
                + " WHERE availability='" + Schema.AVAIL_MISSING + "'");
    }

    public static int countHistory(SQLiteDatabase db) {
        return (int) scalar(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK + " WHERE last_opened_at>0");
    }

    public static int countByRoot(SQLiteDatabase db, long rootId) {
        return (int) scalar(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK + " WHERE root_id=?",
                new String[]{String.valueOf(rootId)});
    }

    public static int countByAuthor(SQLiteDatabase db, long authorId) {
        return (int) scalar(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK_AUTHOR + " WHERE author_id=?",
                new String[]{String.valueOf(authorId)});
    }

    public static int countByGenre(SQLiteDatabase db, long genreId) {
        return (int) scalar(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK_GENRE + " WHERE genre_id=?",
                new String[]{String.valueOf(genreId)});
    }

    public static int countBySeries(SQLiteDatabase db, long seriesId) {
        return (int) scalar(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK_SERIES + " WHERE series_id=?",
                new String[]{String.valueOf(seriesId)});
    }

    // ------------------------------------------------------------------
    // Запись (Фаза A индексатора)
    // ------------------------------------------------------------------

    /**
     * Вставка книги «только по файлу» (Фаза A): title = имя файла,
     * {@code needs_parse=1}. Существующая пара (root, path) игнорируется.
     *
     * @param generation текущая генерация скана → {@code seen_gen}
     * @return id строки, либо -1 при конфликте/ошибке.
     */
    public static long insertFromFile(SQLiteDatabase db, long rootId, String path,
                                      String title, long size, long mtime, String format,
                                      long generation) {
        ContentValues v = new ContentValues();
        v.put("root_id", rootId);
        v.put("path", path);
        v.put("title", title);
        v.put("title_norm", Text.norm(title));
        v.put("title_letter", Text.firstLetter(title));
        v.put("authors_text", "");
        v.put("series_text", "");
        v.put("authors_norm", "");
        v.put("series_norm", "");
        v.put("file_size", size);
        v.put("file_mtime", mtime);
        v.put("format", format);
        v.put("needs_parse", 1);
        v.put("meta_ok", 0);
        v.put("cover_state", "none");
        v.put("seen_gen", generation);
        v.put("indexed_at", 0);
        v.put("availability", Schema.AVAIL_OK);
        return db.insertWithOnConflict(Schema.T_BOOK, null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * Учёт файла в текущем скане: добавление, отметка изменений или «ничего не
     * делать». Служебные колонки не входят в {@code UPDATE OF} FTS-триггера,
     * поэтому индекс не перестраивается зря; файл не читается, только {@code stat}.
     *
     * @return {@link #TOUCH_ABSENT}, {@link #TOUCH_UNCHANGED} или {@link #TOUCH_CHANGED}
     */
    public static int touchSeen(SQLiteDatabase db, long rootId, String path, String title,
                                String format, long size, long mtime, long generation) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id, file_size, file_mtime, availability FROM "
                            + Schema.T_BOOK + " WHERE root_id=? AND path=?",
                    new String[]{String.valueOf(rootId), path});
            if (!c.moveToFirst()) {
                long id = insertFromFile(db, rootId, path, title, size, mtime, format, generation);
                return id > 0 ? TOUCH_ABSENT : TOUCH_UNCHANGED;
            }
            long id = c.getLong(0);
            boolean changed = c.getLong(1) != size || c.getLong(2) != mtime;
            boolean wasMissing = !Schema.AVAIL_OK.equals(c.getString(3));

            if (changed) {
                ContentValues v = new ContentValues();
                v.put("file_size", size);
                v.put("file_mtime", mtime);
                v.put("format", format);
                v.put("needs_parse", 1);
                v.put("availability", Schema.AVAIL_OK);
                v.put("seen_gen", generation);
                v.put("indexed_at", 0);
                db.update(Schema.T_BOOK, v, "id=?", new String[]{String.valueOf(id)});
                return TOUCH_CHANGED;
            }
            if (wasMissing) {
                // Файл вернулся на место — ничего не перечитываем, просто снимаем метку.
                ContentValues v = new ContentValues();
                v.put("availability", Schema.AVAIL_OK);
                v.put("seen_gen", generation);
                db.update(Schema.T_BOOK, v, "id=?", new String[]{String.valueOf(id)});
            }
            return TOUCH_UNCHANGED;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /** Строки корня для проверки доступности файлов (проход B Фазы A). */
    public static Cursor rootRows(SQLiteDatabase db, long rootId) {
        return db.rawQuery("SELECT id, path, availability FROM " + Schema.T_BOOK
                        + " WHERE root_id=? ORDER BY id",
                new String[]{String.valueOf(rootId)});
    }

    /** Проставить доступность одной строки (ok/missing/no_access). */
    public static int setAvailability(SQLiteDatabase db, long bookId, String state) {
        ContentValues v = new ContentValues();
        v.put("availability", state);
        return db.update(Schema.T_BOOK, v, "id=?", new String[]{String.valueOf(bookId)});
    }

    /**
     * Обновить денормализованные тексты (после изменения связей авторов/серий).
     * {@code authorsText}/{@code seriesText} — как показывать; {@code *norm} —
     * регистронезависимые копии для {@code LIKE}-fallback (LIKE в SQLite не
     * понимает регистр кириллицы, поэтому храним нормализованный вариант).
     * Обновление {@code book} заодно переиндексирует FTS (триггер {@code book_au}).
     */
    public static int setTexts(SQLiteDatabase db, long bookId, String authorsText, String seriesText) {
        ContentValues v = new ContentValues();
        v.put("authors_text", authorsText);
        v.put("series_text", seriesText);
        v.put("authors_norm", Text.norm(authorsText));
        v.put("series_norm", Text.norm(seriesText));
        return db.update(Schema.T_BOOK, v, "id=?", new String[]{String.valueOf(bookId)});
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    private static Cursor query(SQLiteDatabase db, String from, String where, String[] whereArgs,
                                String order, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLS_SQL).append(" FROM ").append(from);
        if (where != null && !where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" ORDER BY ").append(order).append(" LIMIT ? OFFSET ?");
        return db.rawQuery(sql.toString(), append(whereArgs, String.valueOf(limit), String.valueOf(offset)));
    }

    private static String[] append(String[] a, String... more) {
        if (a == null || a.length == 0) {
            return more;
        }
        String[] r = new String[a.length + more.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(more, 0, r, a.length, more.length);
        return r;
    }

    static long scalar(SQLiteDatabase db, String sql) {
        return scalar(db, sql, null);
    }

    static long scalar(SQLiteDatabase db, String sql, String[] args) {
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
