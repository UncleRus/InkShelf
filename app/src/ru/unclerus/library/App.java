package ru.unclerus.library;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.StrictMode;
import android.os.SystemClock;

import ru.unclerus.library.db.Db;
import ru.unclerus.library.db.DbCheck;
import ru.unclerus.library.db.Schema;
import ru.unclerus.library.util.Prefs;

/**
 * Точка входа. Ручной DI: синглтоны (логгер, настройки, БД) создаются здесь и
 * раздаются через статические геттеры — AndroidX/Hilt не используются.
 *
 * <p>Здесь же меряется холодный старт: от {@code onCreate} до готовности БД.
 */
public final class App extends Application {

    private static App sInstance;
    private static Prefs sPrefs;

    /** Дополнительный множитель шрифта поверх системного (настройка S4.3). */
    private static volatile float sFontScale = 1.0f;

    /** Момент старта процесса (для замера холодного старта). */
    private static long sStartMs;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        sStartMs = SystemClock.uptimeMillis();

        // Читалка Onyx (NeoReader) открывает только file:// — поток content:// она
        // не копирует (research/reader-launch-matrix.md). При targetSdk 30 передача
        // file:// через Intent иначе падает с FileUriExposedException, поэтому
        // снимаем VM-проверки StrictMode (внешние приложения получают путь, не данные).
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());

        Logger.init(this);
        sPrefs = new Prefs(this);
        sFontScale = sPrefs.fontScale();

        Logger.i("App", "onCreate: package=" + getPackageName()
                + " · схема " + Schema.VERSION + " · t=" + sStartMs + " ms");
    }

    public static App get() {
        return sInstance;
    }

    public static Context ctx() {
        return sInstance;
    }

    public static Prefs prefs() {
        return sPrefs;
    }

    /** БД (открывается лениво; открытие — в фоновом потоке). */
    public static Db db() {
        return Db.get(sInstance);
    }

    public static long startMs() {
        return sStartMs;
    }

    /** Мс с момента старта процесса. */
    public static long sinceStart() {
        return SystemClock.uptimeMillis() - sStartMs;
    }

    public static float fontScale() {
        return sFontScale;
    }

    public static void setFontScale(float scale) {
        sFontScale = scale <= 0f ? 1.0f : scale;
        if (sPrefs != null) {
            sPrefs.setFontScale(sFontScale);
        }
    }

    /**
     * Открыть БД (при необходимости создать схему) и вернуть отчёт для
     * диагностики. Вызывать в фоновом потоке: первое открытие создаёт схему.
     */
    public static String dbReport() {
        long t0 = SystemClock.uptimeMillis();
        SQLiteDatabase db = db().getWritableDatabase();
        long openMs = SystemClock.uptimeMillis() - t0;
        String report = DbCheck.report(db);
        Logger.i("App", "холодный старт: БД готова за " + openMs + " мс"
                + " (с момента старта процесса " + sinceStart() + " мс)");
        return "открытие БД: " + openMs + " мс\n" + report;
    }
}
