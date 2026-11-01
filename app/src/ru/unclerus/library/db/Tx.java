package ru.unclerus.library.db;

import android.database.sqlite.SQLiteDatabase;

import ru.unclerus.library.Logger;

/**
 * Батч-транзакции. На 100k книг один INSERT на книгу вне транзакции недопустим —
 * поэтому весь пакет идёт через {@link #run}.
 */
public final class Tx {

    /** Тело транзакции. */
    public interface Body {
        void run(SQLiteDatabase db) throws Exception;
    }

    private Tx() {}

    /** Выполнить тело в транзакции; исключение пробрасывается как RuntimeException. */
    public static void run(SQLiteDatabase db, Body body) {
        db.beginTransaction();
        try {
            body.run(db);
            db.setTransactionSuccessful();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            db.endTransaction();
        }
    }

    /** Транзакция «без сюрпризов»: исключение логируется, результат — boolean. */
    public static boolean silent(SQLiteDatabase db, Body body) {
        try {
            run(db, body);
            return true;
        } catch (Throwable t) {
            Logger.e("Tx", "транзакция отменена", t);
            return false;
        }
    }
}
