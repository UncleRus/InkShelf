package ru.unclerus.library.util;

/**
 * Нормализация текста и эвристики по авторам — зеркало того, что делает
 * {@code design/gen.py} (`sort_key`, `author_surname`, `first_letter`), чтобы
 * Java-код и макеты давали одинаковые разделы и порядок.
 *
 * <p>Инварианты:
 * <ul>
 *   <li>нормализация — нижний регистр, «ё»→«е», схлопывание пробелов;</li>
 *   <li>раздел: число → {@link #NUMKEY} («0–9»), буква → её верхний регистр,
 *       иначе → {@link #OTHER} («#»); кавычки и знаки пропускаются;</li>
 *   <li>фамилия — до запятой, иначе последнее слово без хвостовых инициалов.</li>
 * </ul>
 */
public final class Text {

    /** Раздел для чисел (совпадает с `NUMKEY` в gen.py). */
    public static final String NUMKEY = "0–9";
    /** Раздел для не-алфавитного начала. */
    public static final String OTHER = "#";
    /** Порядок разделов: числа → латиница → кириллица → «#». */
    public static final String LATIN = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static final String CYRILLIC = "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ";

    private Text() {}

    /** Ключ сортировки: нижний регистр, «ё»→«е», схлопнутые пробелы, обрезка. */
    public static String norm(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (c == 'ё') {
                c = 'е';
            }
            if (Character.isWhitespace(c)) {
                pendingSpace = b.length() > 0;
                continue;
            }
            if (pendingSpace) {
                b.append(' ');
                pendingSpace = false;
            }
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Раздел для строки: первая буква или цифра, пропуская кавычки/знаки
     * («Краткая…» → «К», «1984» → «0–9», «#!» → «#»).
     */
    public static String firstLetter(String text) {
        if (text == null) {
            return OTHER;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                return NUMKEY;
            }
            if (Character.isLetter(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return OTHER;
    }

    /** Фамилия по эвристике (для форматов без явного &lt;last-name&gt;). */
    public static String surname(String full) {
        if (full == null) {
            return "";
        }
        String s = full.trim();
        if (s.isEmpty()) {
            return "";
        }
        int comma = s.indexOf(',');
        if (comma > 0) {
            return s.substring(0, comma).trim();
        }
        String[] parts = s.split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        int n = parts.length;
        while (n > 1 && isInitial(parts[n - 1])) {
            n--;
        }
        return parts[n - 1];
    }

    /**
     * Формат отображения имени автора (настройка S4.5).
     *
     * @param lastFirst {@code true} — «Фамилия Имя», иначе «Имя Фамилия».
     */
    public static String formatAuthor(String full, boolean lastFirst) {
        if (full == null) {
            return "";
        }
        String s = collapse(full);
        if (s.isEmpty() || !lastFirst) {
            return s;
        }
        if (s.indexOf(',') > 0) {
            return collapse(s.replace(',', ' '));
        }
        String[] parts = s.split("\\s+");
        if (parts.length < 2) {
            return s;
        }
        int k = parts.length - 1;
        while (k > 0 && isInitial(parts[k])) {
            k--;
        }
        StringBuilder b = new StringBuilder(parts[k]);
        for (int i = 0; i < parts.length; i++) {
            if (i != k) {
                b.append(' ').append(parts[i]);
            }
        }
        return b.toString();
    }

    /** Схлопывает пробелы и обрезает края, регистр сохраняется. */
    public static String collapse(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    /** Пустая строка или только пробелы. */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isInitial(String p) {
        if (p == null) {
            return false;
        }
        if (p.length() == 1) {
            return Character.isLetter(p.charAt(0));
        }
        if (p.length() == 2) {
            return Character.isLetter(p.charAt(0)) && p.charAt(1) == '.';
        }
        return false;
    }

    /** Индекс раздела в {@link #LATIN}/{@link #CYRILLIC}-порядке; для сравнения. */
    public static int sectionOrder(String letter) {
        if (NUMKEY.equals(letter)) {
            return 0;
        }
        if (letter == null || letter.isEmpty()) {
            return 3;
        }
        char c = letter.charAt(0);
        int i = LATIN.indexOf(c);
        if (i >= 0) {
            return 1;
        }
        i = CYRILLIC.indexOf(c);
        if (i >= 0) {
            return 2;
        }
        return 3;
    }
}
