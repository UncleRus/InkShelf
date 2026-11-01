package ru.unclerus.library.ui;

import java.util.Calendar;
import java.util.Locale;

import ru.unclerus.library.R;

/**
 * Группы вкладки «Дата» (FR-2.4, UI.md §3): Сегодня / Вчера / На этой неделе /
 * В этом месяце / Раньше. Границы считаются от начала текущих локальных суток:
 * «на этой неделе» — последние 7 дней (кроме сегодня/вчера), «в этом месяце» —
 * последние 30 дней. Так границы детерминированы и совпадают с сортировкой по
 * {@code file_mtime} убыв. (порядок групп = порядок убывания даты).
 */
public final class DateGroups {

    public static final int TODAY = 0;
    public static final int YESTERDAY = 1;
    public static final int WEEK = 2;
    public static final int MONTH = 3;
    public static final int EARLIER = 4;
    public static final int COUNT = 5;

    private static final long DAY = 24L * 60 * 60 * 1000;

    private DateGroups() {}

    /** Начало текущих локальных суток (мс). */
    public static long startOfToday(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Границы групп (нисходящие): today, yesterday, week, month. */
    public static long[] bounds(long now) {
        long today = startOfToday(now);
        return new long[]{today, today - DAY, today - 7 * DAY, today - 30 * DAY};
    }

    /** Группа для отметки времени (мс). */
    public static int bucket(long mtime, long[] bounds) {
        if (mtime >= bounds[0]) {
            return TODAY;
        }
        if (mtime >= bounds[1]) {
            return YESTERDAY;
        }
        if (mtime >= bounds[2]) {
            return WEEK;
        }
        if (mtime >= bounds[3]) {
            return MONTH;
        }
        return EARLIER;
    }

    /** Ресурс подписи группы. */
    public static int labelRes(int bucket) {
        switch (bucket) {
            case TODAY:
                return R.string.date_today;
            case YESTERDAY:
                return R.string.date_yesterday;
            case WEEK:
                return R.string.date_this_week;
            case MONTH:
                return R.string.date_this_month;
            default:
                return R.string.date_earlier;
        }
    }

    /** Диапазон [from, to) отметок времени группы (to — исключающая граница). */
    public static long[] range(int bucket, long[] bounds) {
        switch (bucket) {
            case TODAY:
                return new long[]{bounds[0], Long.MAX_VALUE};
            case YESTERDAY:
                return new long[]{bounds[1], bounds[0]};
            case WEEK:
                return new long[]{bounds[2], bounds[1]};
            case MONTH:
                return new long[]{bounds[3], bounds[2]};
            default:
                return new long[]{Long.MIN_VALUE, bounds[3]};
        }
    }

    /** Единый формат даты для карточки/диалогов (локаль). */
    public static String format(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.getDefault(), "%02d.%02d.%04d",
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }
}
