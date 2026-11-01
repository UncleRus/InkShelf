package ru.unclerus.library.scan;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Шина прогресса индексации. Индексатор работает в фоновом потоке сервиса и
 * публикует снимки прогресса; подписчики (главный экран, уведомление сервиса)
 * получают их <b>на главном потоке</b>, поэтому UI можно трогать напрямую.
 *
 * <p>Держится последний снимок: новый подписчик сразу получает текущее
 * состояние, не дожидаясь следующего события.
 */
public final class ProgressBus {

    public static final int PHASE_IDLE = 0;
    public static final int PHASE_WALK = 1;
    public static final int PHASE_PARSE = 2;
    public static final int PHASE_DONE = 3;
    public static final int PHASE_CANCELLED = 4;
    public static final int PHASE_ERROR = 5;

    /** Неизменяемый снимок прогресса. */
    public static final class Progress {
        public final int phase;
        public final String root;
        public final long scanned;
        public final long found;
        public final long added;
        public final long changed;
        public final String error;

        public Progress(int phase, String root, long scanned, long found,
                        long added, long changed, String error) {
            this.phase = phase;
            this.root = root;
            this.scanned = scanned;
            this.found = found;
            this.added = added;
            this.changed = changed;
            this.error = error;
        }

        public boolean isRunning() {
            return phase == PHASE_WALK || phase == PHASE_PARSE;
        }
    }

    /** Подписчик: вызывается на главном потоке. */
    public interface Listener {
        void onProgress(Progress p);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile Progress sLast =
            new Progress(PHASE_IDLE, null, 0, 0, 0, 0, null);

    private ProgressBus() {}

    /** Текущий снимок. */
    public static Progress last() {
        return sLast;
    }

    /** Идёт ли индексация. */
    public static boolean isRunning() {
        return sLast.isRunning();
    }

    /** Подписаться; сразу же придёт текущий снимок (на главном потоке). */
    public static void subscribe(Listener l) {
        if (l == null || LISTENERS.contains(l)) {
            return;
        }
        LISTENERS.add(l);
        final Listener fl = l;
        final Progress p = sLast;
        UI.post(new Runnable() {
            @Override
            public void run() {
                if (LISTENERS.contains(fl)) {
                    fl.onProgress(p);
                }
            }
        });
    }

    public static void unsubscribe(Listener l) {
        LISTENERS.remove(l);
    }

    /** Опубликовать снимок (из любого потока). */
    public static void publish(final Progress p) {
        sLast = p;
        UI.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) {
                    l.onProgress(p);
                }
            }
        });
    }

    public static void publish(int phase, String root, long scanned, long found,
                               long added, long changed, String error) {
        publish(new Progress(phase, root, scanned, found, added, changed, error));
    }

    /** Сбросить в «покой» (например, после завершения без подписчиков). */
    public static void reset() {
        publish(PHASE_IDLE, null, 0, 0, 0, 0, null);
    }
}
