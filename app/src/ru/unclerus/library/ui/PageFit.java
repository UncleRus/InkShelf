package ru.unclerus.library.ui;

import android.view.View;
import android.widget.ListView;

import ru.unclerus.library.R;

/**
 * Страховка от обрезки последней строки страницы (NFR-1, приёмочный №7).
 *
 * <p>{@link PagePlan} считает границы страниц по <b>номинальной</b> высоте
 * строки — той, что даёт разметка с коротким образцом текста. Реальное
 * длинное название переносится на несколько строк, строка становится выше, и
 * последняя строка страницы может вылезти за область списка. Здесь высота
 * строки поднимается до фактической максимальной высоты на отрисованной
 * странице; вызывающий код перестраивает план и перерисовывает страницу.
 *
 * <p>Подъём монотонный (кэш {@link ListMetrics}), поэтому цикл сходится:
 * каждое повторение строго увеличивает высоту, а она ограничена реальной
 * высотой самой длинной строки.
 */
public final class PageFit {

    private PageFit() {}

    /**
     * Проверить страницу и при переполнении поднять высоту строки.
     *
     * @return {@code true}, если высота строки изменена и страницу нужно
     *         перестроить; {@code false}, если всё помещается или поднимать
     *         нечего.
     */
    public static boolean raiseIfOverflow(ListView list, int viewport, int itemLayoutId) {
        if (list == null || viewport <= 0) {
            return false;
        }
        int n = list.getChildCount();
        if (n == 0) {
            return false;
        }
        int divider = Math.max(0, list.getDividerHeight());
        int total = 0;
        int maxItem = 0;
        for (int i = 0; i < n; i++) {
            View child = list.getChildAt(i);
            if (child == null) {
                continue;
            }
            total += child.getHeight();
            if (i > 0) {
                total += divider;
            }
            // Строка-заголовок раздела (буква/серия) — не элемент списка.
            if (child.findViewById(R.id.section_letter) == null) {
                maxItem = Math.max(maxItem, child.getHeight());
            }
        }
        if (total <= viewport + 1) {
            return false;
        }
        int current = ListMetrics.rowHeight(list.getContext(), itemLayoutId);
        if (maxItem <= current) {
            return false;
        }
        ListMetrics.raiseRowHeight(itemLayoutId, maxItem);
        return true;
    }
}
