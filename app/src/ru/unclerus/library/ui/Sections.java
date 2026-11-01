package ru.unclerus.library.ui;

import android.database.Cursor;

/**
 * Разделы упорядоченного списка (буквы фамилий/названий, группы даты). Хранит
 * только компактные массивы «заголовок + первый индекс элемента» — при 100k
 * книг сами книги в память не тянутся (PLAN §6.6).
 *
 * <p>Порядок разделов обязан совпадать с {@code ORDER BY} оконной выборки,
 * иначе границы страниц разъедутся со строками. Поэтому {@code starts}
 * накапливаются из выборки, отсортированной тем же ключом, что и список.
 */
public final class Sections {

    private final String[] mLetters;
    private final int[] mStarts;
    private final int mTotal;

    private Sections(String[] letters, int[] starts, int total) {
        mLetters = letters;
        mStarts = starts;
        mTotal = total;
    }

    /**
     * Построить из курсора разбиения: колонка заголовка + колонка количества,
     * строки идут в порядке следования разделов. Пустые/нулевые разделы
     * пропускаются (в списке только группы с книгами).
     */
    public static Sections of(Cursor c, int letterCol, int countCol) {
        if (c == null) {
            return null;
        }
        String[] letters = new String[c.getCount()];
        int[] counts = new int[c.getCount()];
        int n = 0;
        while (c.moveToNext()) {
            int cnt = c.getInt(countCol);
            if (cnt <= 0) {
                continue;
            }
            letters[n] = c.getString(letterCol);
            counts[n] = cnt;
            n++;
        }
        return build(letters, counts, n);
    }

    /** Построить из уже известных заголовков и количеств (например, групп даты). */
    public static Sections of(String[] letters, int[] counts) {
        int n = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                n++;
            }
        }
        String[] ls = new String[n];
        int[] cs = new int[n];
        int k = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                ls[k] = letters[i];
                cs[k] = counts[i];
                k++;
            }
        }
        return build(ls, cs, n);
    }

    /** Один раздел на весь список (история: «Недавно открытые»). */
    public static Sections single(String letter, int total) {
        if (total <= 0) {
            return null;
        }
        return new Sections(new String[]{letter}, new int[]{0}, total);
    }

    private static Sections build(String[] letters, int[] counts, int n) {
        if (n == 0) {
            return null;
        }
        int[] starts = new int[n];
        int acc = 0;
        for (int i = 0; i < n; i++) {
            starts[i] = acc;
            acc += counts[i];
        }
        String[] ls = new String[n];
        System.arraycopy(letters, 0, ls, 0, n);
        return new Sections(ls, starts, acc);
    }

    public int count() {
        return mLetters.length;
    }

    public int total() {
        return mTotal;
    }

    public String letter(int section) {
        return mLetters[section];
    }

    public int start(int section) {
        return mStarts[section];
    }

    public String[] letters() {
        return mLetters;
    }

    /** Индекс раздела, которому принадлежит элемент {@code item} (двоичный поиск). */
    public int indexOf(int item) {
        int lo = 0;
        int hi = mStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (mStarts[mid] <= item) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Элемент — первая строка своего раздела? */
    public boolean isStart(int item) {
        return mStarts[indexOf(item)] == item;
    }

    public String letterOf(int item) {
        return mLetters[indexOf(item)];
    }
}
