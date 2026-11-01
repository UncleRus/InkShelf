package ru.unclerus.library.ui;

import android.content.Context;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import ru.unclerus.library.R;

/**
 * Метрики списка. Размер страницы <b>не константа</b>: он считается по реальной
 * высоте области списка и высоте строки при текущем шрифте/плотности
 * (PLAN §6.6, UI.md §5.1). Высота измеряется на настоящей разметке, поэтому
 * масштаб шрифта учитывается автоматически.
 *
 * <p>Разные вкладки рисуют <b>разные</b> строки: классификаторы
 * (авторы/жанры/серии) — {@code row_group}, книги — {@code row_book}. Поэтому
 * id разметки строки — параметр: считать страницу по чужой разметке нельзя
 * (иначе на «Авторах» влезало бы 5 строк вместо ~11).
 *
 * <p><b>Группы (M4).</b> Когда в списках появятся строки-разделы (буквы) и
 * повтор заголовка разрезанной группы, размер страницы нельзя будет считать как
 * {@code высота / одна высота строки}: страница — это <i>состав</i> строк разных
 * типов (заголовок + элементы). Поэтому API строится на измерении произвольной
 * разметки ({@link #rowHeight}), а подсчёт по составу добавится отдельно.
 */
public final class ListMetrics {

    /** Запасное значение, если измерить не удалось. */
    public static final int DEFAULT = 12;
    private static final int MIN = 1;
    private static final int MAX = 100;

    /** Кэш высоты строки по id разметки (сбрасывается при смене шрифта). */
    private static final SparseIntArray sRowHeight = new SparseIntArray();

    private ListMetrics() {}

    /** Сколько строк разметки {@code rowLayoutId} помещается в область {@code listHeightPx}. */
    public static int perPage(Context ctx, int listHeightPx, int rowLayoutId) {
        return perPage(ctx, listHeightPx, rowLayoutId, 0);
    }

    /**
     * То же, но с учётом высоты разделителя {@code dividerPx} между строками
     * (номер 4 в {@code BACKLOG.md}): {@code n} строк занимают
     * {@code n*row + (n-1)*divider}, поэтому
     * {@code n = (h + divider) / (row + divider)}. Без этого список вылезал за
     * область на {@code (n-1)*divider} пикселей.
     */
    public static int perPage(Context ctx, int listHeightPx, int rowLayoutId, int dividerPx) {
        if (listHeightPx <= 0) {
            return DEFAULT;
        }
        int row = rowHeight(ctx, rowLayoutId);
        if (row <= 0) {
            return DEFAULT;
        }
        if (dividerPx < 0) {
            dividerPx = 0;
        }
        int n = (listHeightPx + dividerPx) / (row + dividerPx);
        if (n < MIN) {
            n = MIN;
        }
        if (n > MAX) {
            n = MAX;
        }
        return n;
    }

    /** Высота одной строки разметки {@code rowLayoutId} (px) при текущем шрифте. */
    public static int rowHeight(Context ctx, int rowLayoutId) {
        int cached = sRowHeight.get(rowLayoutId, -1);
        if (cached > 0) {
            return cached;
        }
        try {
            LayoutInflater inf = LayoutInflater.from(ctx);
            View row = inf.inflate(rowLayoutId, null, false);
            sample(row);
            int w = ctx.getResources().getDisplayMetrics().widthPixels;
            row.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = row.getMeasuredHeight();
            if (h > 0) {
                sRowHeight.put(rowLayoutId, h);
            }
            return h;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Заполнить образец текстом. Лишние id отсутствуют в разметке —
     * {@code findViewById} вернёт {@code null} и строка просто пропустится.
     */
    private static void sample(View row) {
        setText(row, R.id.book_title, "Пример названия книги");
        setText(row, R.id.book_sub, "Имя Фамилия");
        setText(row, R.id.book_meta, "FB2.ZIP · 1.4 MB");
        setText(row, R.id.group_name, "Фамилия Автора");
        setText(row, R.id.group_count, "999");
        setText(row, R.id.section_letter, "А");
    }

    /** Сбросить кэш высоты (например, после смены масштаба шрифта). */
    public static void reset() {
        sRowHeight.clear();
    }

    private static void setText(View row, int id, CharSequence s) {
        TextView t = row.findViewById(id);
        if (t != null) {
            t.setText(s);
        }
    }
}
