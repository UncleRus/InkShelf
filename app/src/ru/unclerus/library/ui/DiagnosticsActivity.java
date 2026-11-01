package ru.unclerus.library.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.unclerus.library.App;
import ru.unclerus.library.R;

/**
 * S6 «Диагностика» — отчёт по БД: версия схемы, таблицы, FTS4, счётчики и
 * проверка, что оконные выборки/поиск выполняются.
 */
public final class DiagnosticsActivity extends BaseActivity {

    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private final Handler mUi = new Handler(Looper.getMainLooper());

    @Override
    protected int titleRes() {
        return R.string.title_diagnostics;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setMessage(getString(R.string.state_loading));
        mBg.execute(new Runnable() {
            @Override
            public void run() {
                final String report = App.dbReport();
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        setMessage(report);
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        mBg.shutdownNow();
        super.onDestroy();
    }
}
