package ru.unclerus.library.fmt;

import java.util.Locale;

/**
 * Форматы книг: детект по имени файла (Фаза A) и представление.
 *
 * <p>На коллекции оператора книги — это {@code .fb2.zip} (иногда просто
 * {@code .zip} с FB2 внутри). Поэтому «голый» {@code .zip} тоже считается
 * книжным архивом; настоящий формат внутри подтвердит Фаза B (проверка
 * magic-байтов).
 *
 * <p>Значения констант попадают в колонку {@code book.format}.
 */
public final class BookFormat {

    public static final String FB2 = "fb2";
    public static final String FB2ZIP = "fb2zip";
    public static final String EPUB = "epub";
    public static final String MOBI = "mobi";

    /** Составные/одиночные расширения в порядке от длинных к коротким. */
    private static final String[] EXT = {
            ".fb2.zip", ".fb2", ".epub", ".mobi", ".azw3", ".azw", ".zip",
    };

    private BookFormat() {}

    /**
     * Формат по имени файла (регистр расширения не важен).
     *
     * @return одна из констант или {@code null}, если файл не книга
     */
    public static String detect(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".fb2.zip")) {
            return FB2ZIP;
        }
        if (lower.endsWith(".fb2")) {
            return FB2;
        }
        if (lower.endsWith(".epub")) {
            return EPUB;
        }
        if (lower.endsWith(".mobi") || lower.endsWith(".azw3") || lower.endsWith(".azw")) {
            return MOBI;
        }
        if (lower.endsWith(".zip")) {
            return FB2ZIP; // архив: формат внутри уточнит Фаза B
        }
        return null;
    }

    /** Поддерживается ли файл (есть ли у него книжный формат). */
    public static boolean isSupported(String name) {
        return detect(name) != null;
    }

    /**
     * Название книги по имени файла (Фаза A): снимается расширение
     * ({@code .fb2.zip} — целиком). Дальше уточняется метаданными в Фазе B.
     */
    public static String displayTitle(String name) {
        if (name == null) {
            return "";
        }
        String t = name;
        String lower = t.toLowerCase(Locale.US);
        for (String ext : EXT) {
            if (lower.endsWith(ext)) {
                t = t.substring(0, t.length() - ext.length());
                break;
            }
        }
        return t.trim();
    }

    /** Короткое языково-нейтральное обозначение формата (для строки списка/карточки). */
    public static String label(String format) {
        if (FB2.equals(format)) {
            return "FB2";
        }
        if (FB2ZIP.equals(format)) {
            return "FB2.ZIP";
        }
        if (EPUB.equals(format)) {
            return "EPUB";
        }
        if (MOBI.equals(format)) {
            return "MOBI";
        }
        return "";
    }

    /** MIME для запуска во внешней читалке (PLAN §9, матрица читалок). */
    public static String mime(String format) {
        if (FB2.equals(format) || FB2ZIP.equals(format)) {
            return "application/fb2+zip";
        }
        if (EPUB.equals(format)) {
            return "application/epub+zip";
        }
        if (MOBI.equals(format)) {
            return "application/x-mobipocket-ebook";
        }
        return "application/octet-stream";
    }
}
