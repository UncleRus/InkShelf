package ru.unclerus.library;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Простой логгер: logcat + файл {@code files/library.log} (для «Выгрузить логи» в S6).
 * Пишется и в UI-, и в фоновом потоке, поэтому запись синхронизирована.
 */
public final class Logger {

    private static final String TAG = "Library";
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File sFile;

    private Logger() {}

    public static synchronized void init(Context ctx) {
        try {
            sFile = new File(ctx.getFilesDir(), "library.log");
        } catch (Throwable t) {
            sFile = null;
        }
    }

    public static File file() {
        return sFile;
    }

    public static void i(String tag, String msg) {
        write('I', tag, msg, null);
    }

    public static void w(String tag, String msg) {
        write('W', tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        write('E', tag, msg, tr);
    }

    private static synchronized void write(char level, String tag, String msg, Throwable tr) {
        String line = FMT.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        if (level == 'E') {
            Log.e(TAG, line, tr);
        } else if (level == 'W') {
            Log.w(TAG, line);
        } else {
            Log.i(TAG, line);
        }
        if (sFile == null) {
            return;
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(sFile, true))) {
            w.write(line);
            if (tr != null) {
                w.write('\n');
                w.write(Log.getStackTraceString(tr));
            }
            w.newLine();
        } catch (IOException ignored) {
            // логирование не должно ронять приложение
        }
    }
}
