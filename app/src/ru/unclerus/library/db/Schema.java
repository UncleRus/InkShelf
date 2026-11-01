package ru.unclerus.library.db;

/**
 * DDL и имена объектов схемы (PLAN.md §5). Схема создаётся целиком, миграции —
 * пошагово в {@link Db#onUpgrade}. Всё без внешних зависимостей.
 *
 * <p>Замечание по FTS4: таблица {@code book_fts} — <b>не</b> external content
 * (текст дублируется в индексе, см. {@link #FTS_DDL}). Денормализованные колонки
 * {@code authors_text}/{@code series_text}/{@code title_norm} нужны для списков и
 * LIKE-fallback; их пишет DAO, а триггеры поддерживают FTS-индекс в актуальном
 * состоянии.
 */
public final class Schema {

    /** Имя файла БД. */
    public static final String DB_NAME = "library.db";
    /** Текущая версия схемы. */
    public static final int VERSION = 4;

    // --- имена таблиц ---
    public static final String T_ROOT = "root";
    public static final String T_SCAN_STATE = "scan_state";
    public static final String T_DIR_CACHE = "dir_cache";
    public static final String T_BOOK = "book";
    public static final String T_AUTHOR = "author";
    public static final String T_BOOK_AUTHOR = "book_author";
    public static final String T_GENRE = "genre";
    public static final String T_BOOK_GENRE = "book_genre";
    public static final String T_SERIES = "series";
    public static final String T_BOOK_SERIES = "book_series";
    public static final String T_AUTHOR_COUNT = "author_book_count";
    public static final String T_GENRE_COUNT = "genre_book_count";
    public static final String T_SERIES_COUNT = "series_book_count";
    public static final String T_FTS = "book_fts";

    // --- статусы ---
    public static final String AVAIL_OK = "ok";
    public static final String AVAIL_MISSING = "missing";
    public static final String AVAIL_NO_ACCESS = "no_access";

    /**
     * Форматы, снятые с поддержки в v3 (решение оператора): PDF/DJVU. Их строки
     * удаляются миграцией; новые файлы этих типов {@link ru.unclerus.library.fmt.BookFormat}
     * больше не распознаёт и в каталог не попадают.
     */
    public static final String REMOVED_FORMATS_SQL = "'pdf','djvu'";

    // --- FTS и триггеры (нужны и при создании, и при пересборке в миграции v2) ---

    /**
     * DDL FTS-индекса. Таблица — <b>не</b> external content: спецкоманда
     * {@code 'delete'} относится к FTS5 и в FTS4 даёт «SQL logic error», а
     * удаление по {@code rowid} у external-content таблицы читает контент-таблицу
     * и в триггерах {@code UPDATE}/{@code DELETE} срабатывает неверно (старые
     * токены не снимаются). Поэтому текст дублируется в индексе, а синхронизация
     * идёт обычными {@code INSERT}/{@code DELETE} — надёжно и предсказуемо.
     */
    public static final String FTS_DDL = "CREATE VIRTUAL TABLE " + T_FTS + " USING fts4("
            + "title, authors_text, series_text, tokenize=unicode61)";

    /** Индекс: книга добавлена. */
    public static final String TRIGGER_AI = "CREATE TRIGGER book_ai AFTER INSERT ON " + T_BOOK + " BEGIN "
            + "INSERT INTO " + T_FTS + "(rowid, title, authors_text, series_text) "
            + "VALUES(new.id, new.title, new.authors_text, new.series_text); END";

    /** Индекс: книга удалена. */
    public static final String TRIGGER_AD = "CREATE TRIGGER book_ad AFTER DELETE ON " + T_BOOK + " BEGIN "
            + "DELETE FROM " + T_FTS + " WHERE rowid=old.id; END";

    /**
     * Индекс: изменены индексируемые тексты. {@code UPDATE OF} — триггер
     * срабатывает только когда реально меняются {@code title}/{@code authors_text}/
     * {@code series_text}; массовые обновления служебных колонок Фазы A
     * ({@code seen_gen}/{@code availability}/{@code file_size}) индекс не трогают.
     */
    public static final String TRIGGER_AU = "CREATE TRIGGER book_au "
            + "AFTER UPDATE OF title, authors_text, series_text ON " + T_BOOK + " BEGIN "
            + "DELETE FROM " + T_FTS + " WHERE rowid=old.id; "
            + "INSERT INTO " + T_FTS + "(rowid, title, authors_text, series_text) "
            + "VALUES(new.id, new.title, new.authors_text, new.series_text); END";

    /** Триггеры FTS в порядке создания (для {@code Db.onUpgrade}). */
    public static final String[] CREATE_TRIGGERS = { TRIGGER_AI, TRIGGER_AD, TRIGGER_AU };

    /** Имена триггеров для удаления (обратный порядок). */
    public static final String[] DROP_TRIGGERS = { "DROP TRIGGER IF EXISTS book_au",
            "DROP TRIGGER IF EXISTS book_ad", "DROP TRIGGER IF EXISTS book_ai" };


    /** Все DDL в порядке создания (родители раньше детей). */
    public static final String[] CREATE = {

            "CREATE TABLE " + T_ROOT + "("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "uri TEXT,"
                    + "path TEXT NOT NULL,"
                    + "kind TEXT NOT NULL DEFAULT 'file',"
                    + "enabled INTEGER NOT NULL DEFAULT 1,"
                    + "last_scan_at INTEGER NOT NULL DEFAULT 0,"
                    + "volume_state TEXT NOT NULL DEFAULT 'unknown',"
                    + "permission_state TEXT NOT NULL DEFAULT 'unknown',"
                    + "last_error TEXT,"
                    + "UNIQUE(path))",

            "CREATE TABLE " + T_SCAN_STATE + "(key TEXT PRIMARY KEY, value TEXT)",

            "CREATE TABLE " + T_DIR_CACHE + "("
                    + "root_id INTEGER NOT NULL REFERENCES " + T_ROOT + "(id) ON DELETE CASCADE,"
                    + "path TEXT NOT NULL,"
                    + "mtime INTEGER NOT NULL DEFAULT 0,"
                    + "scanned_at INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY(root_id, path))",

            "CREATE TABLE " + T_BOOK + "("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "root_id INTEGER REFERENCES " + T_ROOT + "(id) ON DELETE SET NULL,"
                    + "path TEXT NOT NULL,"
                    + "file_size INTEGER NOT NULL DEFAULT 0,"
                    + "file_mtime INTEGER NOT NULL DEFAULT 0,"
                    + "format TEXT,"
                    + "title TEXT,"
                    + "title_norm TEXT,"
                    + "title_letter TEXT,"
                    + "authors_text TEXT,"
                    + "series_text TEXT,"
                    + "authors_norm TEXT,"
                    + "series_norm TEXT,"
                    + "annotation TEXT,"
                    + "meta_ok INTEGER NOT NULL DEFAULT 0,"
                    + "needs_parse INTEGER NOT NULL DEFAULT 1,"
                    + "seen_gen INTEGER NOT NULL DEFAULT 0,"
                    + "cover_entry TEXT,"
                    + "cover_state TEXT NOT NULL DEFAULT 'none',"
                    + "cover_cache_path TEXT,"
                    + "entry_id TEXT,"
                    + "indexed_at INTEGER NOT NULL DEFAULT 0,"
                    + "availability TEXT NOT NULL DEFAULT 'ok',"
                    + "last_opened_at INTEGER NOT NULL DEFAULT 0,"
                    + "open_count INTEGER NOT NULL DEFAULT 0,"
                    + "last_opened_by_package TEXT,"
                    + "UNIQUE(root_id, path))",

            "CREATE TABLE " + T_AUTHOR + "("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "name_norm TEXT NOT NULL,"
                    + "sort_name TEXT,"
                    + "surname TEXT,"
                    + "letter TEXT,"
                    + "UNIQUE(name_norm))",

            "CREATE TABLE " + T_BOOK_AUTHOR + "("
                    + "book_id INTEGER NOT NULL REFERENCES " + T_BOOK + "(id) ON DELETE CASCADE,"
                    + "author_id INTEGER NOT NULL REFERENCES " + T_AUTHOR + "(id) ON DELETE CASCADE,"
                    + "ord INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY(book_id, author_id))",

            "CREATE TABLE " + T_GENRE + "("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "code TEXT NOT NULL UNIQUE,"
                    + "display_name TEXT,"
                    + "display_name_en TEXT)",

            "CREATE TABLE " + T_BOOK_GENRE + "("
                    + "book_id INTEGER NOT NULL REFERENCES " + T_BOOK + "(id) ON DELETE CASCADE,"
                    + "genre_id INTEGER NOT NULL REFERENCES " + T_GENRE + "(id) ON DELETE CASCADE,"
                    + "PRIMARY KEY(book_id, genre_id))",

            "CREATE TABLE " + T_SERIES + "("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "name_norm TEXT NOT NULL,"
                    + "UNIQUE(name_norm))",

            "CREATE TABLE " + T_BOOK_SERIES + "("
                    + "book_id INTEGER NOT NULL REFERENCES " + T_BOOK + "(id) ON DELETE CASCADE,"
                    + "series_id INTEGER NOT NULL REFERENCES " + T_SERIES + "(id) ON DELETE CASCADE,"
                    + "number TEXT,"
                    + "number_sort INTEGER,"
                    + "PRIMARY KEY(book_id, series_id))",

            // Материализованные счётчики: списки строятся только там, где cnt > 0,
            // поэтому «групп без книг» не существует (PLAN §6.5).
            "CREATE TABLE " + T_AUTHOR_COUNT + "("
                    + "author_id INTEGER PRIMARY KEY REFERENCES " + T_AUTHOR + "(id) ON DELETE CASCADE,"
                    + "cnt INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE " + T_GENRE_COUNT + "("
                    + "genre_id INTEGER PRIMARY KEY REFERENCES " + T_GENRE + "(id) ON DELETE CASCADE,"
                    + "cnt INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE " + T_SERIES_COUNT + "("
                    + "series_id INTEGER PRIMARY KEY REFERENCES " + T_SERIES + "(id) ON DELETE CASCADE,"
                    + "cnt INTEGER NOT NULL DEFAULT 0)",

            // FTS4 + unicode61 (FTS5 на устройстве нет, §6.1). Не external
            // content — см. комментарий к FTS_DDL.
            FTS_DDL,
            TRIGGER_AI,
            TRIGGER_AD,
            TRIGGER_AU,
    };

    /** Индексы под оконные выборки, сортировки и разделы (PLAN §5). */
    public static final String[] CREATE_INDEXES = {
            "CREATE INDEX idx_book_root_avail ON " + T_BOOK + "(root_id, availability)",
            "CREATE INDEX idx_book_mtime ON " + T_BOOK + "(file_mtime)",
            "CREATE INDEX idx_book_opened ON " + T_BOOK + "(last_opened_at DESC)",
            "CREATE INDEX idx_book_title ON " + T_BOOK + "(title_letter, title_norm)",
            "CREATE INDEX idx_book_needs_parse ON " + T_BOOK + "(needs_parse)",
            "CREATE INDEX idx_book_format ON " + T_BOOK + "(format)",
            "CREATE INDEX idx_book_genre_key ON " + T_BOOK_GENRE + "(genre_id)",
            "CREATE INDEX idx_book_series_key ON " + T_BOOK_SERIES + "(series_id)",
            "CREATE INDEX idx_dir_cache_root ON " + T_DIR_CACHE + "(root_id)",
            "CREATE INDEX idx_author_letter ON " + T_AUTHOR + "(letter, sort_name)",
    };

    /** Порядок удаления — обратный созданию (триггеры → FTS → дети → родители). */
    public static final String[] DROP = {
            "DROP TRIGGER IF EXISTS book_ai",
            "DROP TRIGGER IF EXISTS book_ad",
            "DROP TRIGGER IF EXISTS book_au",
            "DROP TABLE IF EXISTS " + T_FTS,
            "DROP TABLE IF EXISTS " + T_BOOK_AUTHOR,
            "DROP TABLE IF EXISTS " + T_BOOK_GENRE,
            "DROP TABLE IF EXISTS " + T_BOOK_SERIES,
            "DROP TABLE IF EXISTS " + T_AUTHOR_COUNT,
            "DROP TABLE IF EXISTS " + T_GENRE_COUNT,
            "DROP TABLE IF EXISTS " + T_SERIES_COUNT,
            "DROP TABLE IF EXISTS " + T_BOOK,
            "DROP TABLE IF EXISTS " + T_AUTHOR,
            "DROP TABLE IF EXISTS " + T_GENRE,
            "DROP TABLE IF EXISTS " + T_SERIES,
            "DROP TABLE IF EXISTS " + T_DIR_CACHE,
            "DROP TABLE IF EXISTS " + T_SCAN_STATE,
            "DROP TABLE IF EXISTS " + T_ROOT,
    };

    private Schema() {}
}
