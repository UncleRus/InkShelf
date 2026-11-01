package ru.unclerus.library.scan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import ru.unclerus.library.Logger;
import ru.unclerus.library.R;
import ru.unclerus.library.ui.MainActivity;

/**
 * Foreground-Service индексации. Держит уведомление с прогрессом (на API 26+ —
 * отдельный канал), работу выполняет {@link Indexer} в фоновом потоке.
 * Останавливается сам по завершении; может быть остановлен из уведомления.
 */
public final class IndexerService extends Service implements ProgressBus.Listener {

    public static final String ACTION_START = "ru.unclerus.library.action.INDEX";
    public static final String ACTION_STOP = "ru.unclerus.library.action.STOP";

    private static final String CHANNEL_ID = "index";
    private static final int NOTIF_ID = 0x1001;
    private static final int REQ_STOP = 1;
    private static final int REQ_OPEN = 2;

    private Thread mWorker;
    private NotificationManager mNm;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        ProgressBus.subscribe(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Logger.i("IndexerService", "запрошена остановка индексации");
            Indexer.cancel();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, build(ProgressBus.last()));

        if (mWorker == null) {
            mWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    Indexer.run();
                    stopSelf();
                }
            }, "library-indexer");
            mWorker.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onProgress(ProgressBus.Progress p) {
        if (mNm != null) {
            mNm.notify(NOTIF_ID, build(p));
        }
    }

    @Override
    public void onDestroy() {
        ProgressBus.unsubscribe(this);
        Indexer.cancel(); // если сервис убили — рабочий поток тоже должен встать
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.dlg_indexing), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        mNm.createNotificationChannel(ch);
    }

    private Notification build(ProgressBus.Progress p) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_refresh)
                .setContentTitle(getString(R.string.dlg_indexing))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        boolean running = p != null && p.isRunning();
        if (p != null && p.root != null) {
            b.setSubText(p.root);
        }
        if (running) {
            b.setContentText(getString(R.string.scan_progress_footer,
                    String.valueOf(p.scanned), String.valueOf(p.found), String.valueOf(p.added)));
        } else if (p != null && p.phase == ProgressBus.PHASE_DONE) {
            b.setContentText(getString(R.string.toast_index_updated));
        } else {
            b.setContentText(getString(R.string.state_loading));
        }

        PendingIntent open = PendingIntent.getActivity(this, REQ_OPEN,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setContentIntent(open);

        if (running || p == null || p.phase == ProgressBus.PHASE_IDLE) {
            PendingIntent stop = PendingIntent.getService(this, REQ_STOP,
                    new Intent(this, IndexerService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0, getString(R.string.action_stop), stop);
        }
        return b.build();
    }
}
