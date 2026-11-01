package ru.unclerus.library.open;

import android.content.Context;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.db.BookDao;

/**
 * Фиксация факта открытия книги (FR-5): {@code open_count + 1},
 * {@code last_opened_at}, {@code last_opened_by_package}. По этим полям строится
 * вкладка «Недавно открытые» (приёмочный №6).
 */
public final class OpenHistory {

    private OpenHistory() {}

    public static void record(Context ctx, long bookId, String pkg) {
        if (bookId <= 0) {
            return;
        }
        try {
            BookDao.recordOpen(App.db().getWritableDatabase(), bookId, pkg,
                    System.currentTimeMillis());
        } catch (Throwable t) {
            Logger.e("Open", "не удалось записать историю id=" + bookId, t);
        }
    }
}
