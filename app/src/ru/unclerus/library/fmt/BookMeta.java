package ru.unclerus.library.fmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Метаданные книги — результат {@link MetadataReader} (PLAN §4, §7). POJO без
 * логики: читатель наполняет поля, {@code db.MetaDao} переносит их в БД.
 *
 * <p>Авторы хранятся в порядке следования в файле ({@code ord} в
 * {@code book_author}); жанры — нормализованные коды ({@code GenreTable}).
 */
public final class BookMeta {

    /** Автор: отображаемое имя, фамилия (для раздела), ключ сортировки. */
    public static final class Author {
        /** Как показывать: «Имя Фамилия» (или ник). */
        public final String name;
        /** Фамилия для раздела списка авторов. */
        public final String surname;
        /** Ключ сортировки: «Фамилия Имя». */
        public final String sortName;

        public Author(String name, String surname, String sortName) {
            this.name = name;
            this.surname = surname;
            this.sortName = sortName;
        }
    }

    /** Название (без названия — вызывающий подставит имя файла). */
    public String title;
    /** Авторы по порядку. */
    public final List<Author> authors = new ArrayList<Author>();
    /** Нормализованные коды жанров. */
    public final List<String> genres = new ArrayList<String>();
    /** Название серии или {@code null}. */
    public String seriesName;
    /** Номер в серии (текстом, как в файле) или {@code null}. */
    public String seriesNumber;
    /** Аннотация (плоский текст, абзацы через {@code \n}). */
    public String annotation;
    /** Внутреннее имя entry / fictionbook-id — вторичный ключ. */
    public String entryId;
    /** id {@code <binary>} обложки (без {@code #}) для Фазы C. */
    public String coverEntry;
    /** Фактический формат (значения {@code BookFormat}). */
    public String format;

    public boolean hasAuthors() {
        return !authors.isEmpty();
    }

    public boolean hasSeries() {
        return seriesName != null && !seriesName.trim().isEmpty();
    }
}
