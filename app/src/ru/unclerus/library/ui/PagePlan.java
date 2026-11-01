package ru.unclerus.library.ui;

/**
 * Границы страниц. Размер страницы — <b>не константа</b> (PLAN §6.6, UI.md §5.1):
 * страница — это набор строк <i>разных типов</i> (заголовок раздела + элементы),
 * а не «высота области / одна высота строки». Поэтому при группировке границы
 * считаются по составу: набираем строки, пока суммарная высота не переполнит
 * область; разрезанная группа не теряется — её заголовок повторяется вверху
 * следующей страницы.
 *
 * <p>Хранится компактный массив стартовых индексов страниц: при 100k книг и
 * ~10 строках на экран это ~10k int (40 КБ), что для 1.6 ГБ ОЗУ несущественно.
 */
public final class PagePlan {

    private final int[] mPages;
    private final int mTotal;

    private PagePlan(int[] pages, int total) {
        mPages = pages;
        mTotal = total;
    }

    /** Плоский список: страницы равной длины (без заголовков разделов). */
    public static PagePlan flat(int total, int perPage) {
        if (perPage < 1) {
            perPage = 1;
        }
        if (total <= 0) {
            return new PagePlan(new int[]{0}, 0);
        }
        int n = (total + perPage - 1) / perPage;
        int[] pages = new int[n];
        for (int i = 0; i < n; i++) {
            pages[i] = i * perPage;
        }
        return new PagePlan(pages, total);
    }

    /**
     * Группированный список: страница набирается из заголовков и элементов.
     *
     * @param sections  разделы списка (не {@code null} и непустой)
     * @param viewport  высота области списка, px
     * @param headerH   высота строки-заголовка, px
     * @param itemH     высота строки-элемента, px (номинальная, по разметке)
     */
    public static PagePlan grouped(int total, Sections sections, int viewport,
                                   int headerH, int itemH) {
        return grouped(total, sections, viewport, headerH, itemH, 0);
    }

    /**
     * Группированный список: страница набирается из заголовков и элементов.
     *
     * @param sections  разделы списка (не {@code null} и непустой)
     * @param viewport  высота области списка, px
     * @param headerH   высота строки-заголовка, px
     * @param itemH     высота строки-элемента, px (номинальная, по разметке)
     * @param dividerH  высота разделителя между строками, px (номер 4 в
     *                  {@code BACKLOG.md}); строки занимают {@code sum + (n-1)*divider},
     *                  поэтому бюджет берётся как {@code viewport + dividerH}
     */
    public static PagePlan grouped(int total, Sections sections, int viewport,
                                   int headerH, int itemH, int dividerH) {
        if (total <= 0) {
            return new PagePlan(new int[]{0}, 0);
        }
        if (dividerH < 0) {
            dividerH = 0;
        }
        if (sections == null || sections.count() == 0) {
            int per = itemH > 0 && viewport > 0
                    ? Math.max(1, (viewport + dividerH) / (itemH + dividerH))
                    : ListMetrics.DEFAULT;
            return flat(total, per);
        }
        if (viewport <= 0 || itemH <= 0) {
            return flat(total, ListMetrics.DEFAULT);
        }
        if (headerH < 0) {
            headerH = 0;
        }
        // Строка «стоит» свою высоту плюс разделитель; лишний разделитель в конце
        // компенсируется добавкой dividerH к бюджету.
        int costH = headerH + dividerH;
        int costI = itemH + dividerH;
        int budget = viewport + dividerH;
        IntBag pages = new IntBag(total / itemH + 2);
        int i = 0;
        while (i < total) {
            pages.add(i);
            int rem = budget;
            int si = sections.indexOf(i);
            if (i != sections.start(si)) {
                rem -= costH; // повтор заголовка разрезанной группы
            }
            boolean placed = false;
            while (i < total) {
                si = sections.indexOf(i);
                boolean needHeader = (i == sections.start(si));
                if (!placed) {
                    // На странице обязана быть хотя бы одна строка — иначе нет прогресса.
                    if (needHeader) {
                        rem -= costH;
                    }
                    rem -= costI;
                    i++;
                    placed = true;
                    continue;
                }
                if (needHeader) {
                    if (rem - costH < costI) {
                        break; // нет места под заголовок + строку
                    }
                    rem -= costH;
                }
                if (rem < costI) {
                    break;
                }
                rem -= costI;
                i++;
            }
        }
        return new PagePlan(pages.toArray(), total);
    }

    public int pageCount() {
        return mPages.length;
    }

    public int start(int page) {
        return mPages[page];
    }

    public int end(int page) {
        return page + 1 < mPages.length ? mPages[page + 1] : mTotal;
    }

    /** Страница (0-based), которой принадлежит элемент {@code item}. */
    public int pageOf(int item) {
        int lo = 0;
        int hi = mPages.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (mPages[mid] <= item) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Растущий int-массив без бокса (без {@code java.util} overhead). */
    static final class IntBag {
        private int[] mData;
        private int mSize;

        IntBag(int cap) {
            mData = new int[cap < 4 ? 4 : cap];
        }

        void add(int v) {
            if (mSize == mData.length) {
                int[] bigger = new int[mData.length * 2];
                System.arraycopy(mData, 0, bigger, 0, mSize);
                mData = bigger;
            }
            mData[mSize++] = v;
        }

        int[] toArray() {
            int[] r = new int[mSize];
            System.arraycopy(mData, 0, r, 0, mSize);
            return r;
        }
    }
}
