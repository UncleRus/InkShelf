package ru.unclerus.library.scan;

import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;

import java.util.List;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.db.Tx;

/**
 * Оркестратор индексации: обходит по очереди все включённые корни, на каждый
 * корень — своя транзакция (обрыв не оставляет «полу-скана», повтор безопасен).
 * Публикует прогресс в {@link ProgressBus}.
 *
 * <p>Фаза A — обход + {@code stat} по корням (транзакция на корень). Затем
 * Фаза B — метаданные ({@link ParsePhase}, транзакция на батч, прогресс фазой
 * {@link ProgressBus#PHASE_PARSE}); она сама открывает свои транзакции, поэтому
 * вызывается вне транзакции Фазы A.
 */
public final class Indexer {

    private static volatile boolean sRunning;
    private static volatile boolean sCancel;
    private static volatile long sStartedAt;

    private Indexer() {}

    /** Идёт ли индексация прямо сейчас. */
    public static boolean isRunning() {
        return sRunning;
    }

    /** Момент запуска текущего/последнего скана (мс, wall clock). */
    public static long startedAt() {
        return sStartedAt;
    }

    /** Попросить индексатор остановиться (проверяется в обходе). */
    public static void cancel() {
        sCancel = true;
    }

    /**
     * Полный скан всех включённых корней. Блокирует вызывающий поток — его
     * запускает {@link IndexerService}.
     */
    public static void run() {
        if (sRunning) {
            Logger.w("Indexer", "скан уже идёт — повторный запуск пропущен");
            return;
        }
        sRunning = true;
        sCancel = false;
        sStartedAt = System.currentTimeMillis();
        final long gen = sStartedAt;
        final long t0 = SystemClock.uptimeMillis();

        long scanned = 0;
        long found = 0;
        long added = 0;
        long changed = 0;
        long dbChanges = 0;
        long parsed = 0;
        long parseOk = 0;
        long parseFailed = 0;
        int rootsDone = 0;
        boolean cancelled = false;

        try {
            SQLiteDatabase db = App.db().getWritableDatabase();
            final List<RootRepo.Root> roots = RootRepo.listAll(db);
            Logger.i("Indexer", "скан: корней " + roots.size() + " · генерация " + gen);

            for (int i = 0; i < roots.size() && !sCancel; i++) {
                final RootRepo.Root root = roots.get(i);
                final long baseScanned = scanned;
                final long baseFound = found;
                final long baseAdded = added;
                final long baseChanged = changed;
                final WalkPhase.Stats[] holder = new WalkPhase.Stats[1];

                Tx.run(db, new Tx.Body() {
                    @Override
                    public void run(SQLiteDatabase d) {
                        WalkPhase.Stats st = WalkPhase.run(d, root, gen, new WalkPhase.Callback() {
                            @Override
                            public boolean onProgress(long sc, long fo, long ad, long ch) {
                                ProgressBus.publish(ProgressBus.PHASE_WALK, root.path,
                                        baseScanned + sc, baseFound + fo,
                                        baseAdded + ad, baseChanged + ch, null);
                                return !sCancel;
                            }
                        });
                        holder[0] = st;
                        RootRepo.setVolumeState(d, root.id, st.volumeState);
                        if (st.ok) {
                            RootRepo.markScanned(d, root.id, System.currentTimeMillis());
                            RootRepo.setError(d, root.id, null);
                        } else {
                            RootRepo.setError(d, root.id, st.error);
                        }
                    }
                });

                WalkPhase.Stats st = holder[0];
                if (st == null) {
                    continue;
                }
                scanned += st.scanned;
                found += st.found;
                added += st.added;
                changed += st.changed;
                if (st.dbChanges > 0) {
                    dbChanges += st.dbChanges;
                }
                rootsDone++;
                if (st.cancelled) {
                    cancelled = true;
                }
                Logger.i("Indexer", "корень " + root.path
                        + ": просканировано " + st.scanned + " · найдено " + st.found
                        + " · добавлено " + st.added + " · изменено " + st.changed
                        + " · записей в БД " + st.dbChanges
                        + (st.ok ? "" : " · " + st.error));
            }

            // Фаза B: метаданные для новых/изменённых книг. Свои транзакции —
            // вызывается вне транзакции Фазы A.
            if (!cancelled && !sCancel) {
                Logger.i("Indexer", "Фаза B: старт");
                ParsePhase.Stats ps = ParsePhase.run(db, new ParsePhase.Callback() {
                    @Override
                    public boolean onProgress(long done, long total, long ok, long failed) {
                        ProgressBus.publish(ProgressBus.PHASE_PARSE, null,
                                done, total, ok, failed, null);
                        return !sCancel;
                    }
                });
                parsed = ps.parsed;
                parseOk = ps.ok;
                parseFailed = ps.failed;
                if (ps.cancelled) {
                    cancelled = true;
                }
            }

            ProgressBus.publish(cancelled ? ProgressBus.PHASE_CANCELLED : ProgressBus.PHASE_DONE,
                    null, scanned, found, added, changed, null);
        } catch (Throwable t) {
            Logger.e("Indexer", "скан прерван ошибкой", t);
            ProgressBus.publish(ProgressBus.PHASE_ERROR, null,
                    scanned, found, added, changed, String.valueOf(t));
        } finally {
            sRunning = false;
            sCancel = false;
            Logger.i("Indexer", "скан завершён за " + (SystemClock.uptimeMillis() - t0)
                    + " мс · корней " + rootsDone + " · найдено " + found + " · добавлено " + added
                    + " · записей в БД " + dbChanges
                    + " · фаза B: разобрано " + parsed + " (метаданные " + parseOk
                    + ", ошибок " + parseFailed + ")");
        }
    }
}
