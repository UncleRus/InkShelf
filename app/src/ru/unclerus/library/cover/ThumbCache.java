package ru.unclerus.library.cover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.util.Prefs;

/**
 * Кэш миниатюр обложек (PLAN §7, Фаза C). Обложка извлекается <b>лениво</b>,
 * только для видимых строк: сперва смотрим память ({@link MemoryLru}), затем
 * диск ({@code cacheDir/covers/<id>.png}), и лишь потом читаем сам файл книги
 * ({@link CoverStore}) и сохраняем уменьшенную копию на диск.
 *
 * <p>Ограничения под 1.6 ГБ ОЗУ: миниатюра не крупнее {@link #MAX_SIDE} px,
 * декод в {@code RGB_565}, кэш памяти — по суммарным байтам, дисковый кэш —
 * LRU по объёму (лимит из {@link Prefs#coverCacheMb()}).
 *
 * <p>Извлечение идёт в одном фоновом потоке и никогда не блокирует UI; по
 * готовности дёргается {@code onLoaded} в главном потоке (адаптер перерисовывает
 * строку). Повторные запросы одной книги, пока грузится первая, игнорируются.
 */
public final class ThumbCache {

    /** Предел длинной стороны миниатюры (карточка — 160×240 dp). */
    public static final int MAX_SIDE = 640;

    private static final String DIR = "covers";
    /** Кэш памяти не больше этого (МБ) — независимо от дискового лимита. */
    private static final int MEM_CAP_MB = 24;

    private static MemoryLru sMem;
    private static ExecutorService sBg;
    private static Handler sMain;
    private static final Set<Long> sInflight = new HashSet<Long>();
    /** Книги, у которых обложки нет (в этом сеансе) — повторно не извлекаем. */
    private static final Set<Long> sNoCover = new HashSet<Long>();

    private ThumbCache() {}

    // ------------------------------------------------------------------
    // Публичный API
    // ------------------------------------------------------------------

    /** Уже загруженная миниатюра или {@code null} (без обращения к диску/диску книги). */
    public static Bitmap peek(long bookId) {
        return mem().get(Long.valueOf(bookId));
    }

    /** Известно ли, что у книги нет обложки (запрос повторять не нужно). */
    public static boolean hasNoCover(long bookId) {
        synchronized (sNoCover) {
            return sNoCover.contains(Long.valueOf(bookId));
        }
    }

    /**
     * Попросить миниатюру. Если она уже в памяти — {@code onLoaded} вызывается
     * сразу; иначе загрузка уходит в фоновый поток и {@code onLoaded} дёрнется по
     * готовности. Пока миниатюры нет, вызывающий рисует плейсхолдер.
     */
    public static void request(Context ctx, long bookId, File file, String format,
                               String coverEntry, Runnable onLoaded) {
        if (bookId <= 0) {
            return;
        }
        if (peek(bookId) != null) {
            if (onLoaded != null) {
                onLoaded.run();
            }
            return;
        }
        if (hasNoCover(bookId)) {
            return;
        }
        synchronized (sInflight) {
            if (sInflight.contains(Long.valueOf(bookId))) {
                return;
            }
            sInflight.add(Long.valueOf(bookId));
        }
        final Context app = ctx.getApplicationContext();
        final File f = file;
        final String fmt = format;
        final String entry = coverEntry;
        final Runnable done = onLoaded;
        bg().execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    bmp = load(app, bookId, f, fmt, entry);
                } catch (Throwable t) {
                    Logger.e("Cover", "не удалось загрузить обложку id=" + bookId, t);
                }
                if (bmp != null) {
                    mem().put(Long.valueOf(bookId), bmp);
                } else {
                    synchronized (sNoCover) {
                        sNoCover.add(Long.valueOf(bookId));
                    }
                }
                synchronized (sInflight) {
                    sInflight.remove(Long.valueOf(bookId));
                }
                if (bmp != null && done != null) {
                    main().post(done);
                }
            }
        });
    }

    /** Готовый файл миниатюры на диске или {@code null}. */
    public static File thumbFile(Context ctx, long bookId) {
        return new File(dir(ctx), bookId + ".png");
    }

    /** Удалить миниатюру (например, книга удалена). */
    public static void remove(Context ctx, long bookId) {
        if (bookId <= 0) {
            return;
        }
        mem().remove(Long.valueOf(bookId));
        synchronized (sNoCover) {
            sNoCover.remove(Long.valueOf(bookId));
        }
        File f = thumbFile(ctx, bookId);
        if (f.exists()) {
            f.delete();
        }
    }

    /** Полностью очистить дисковый и памяти-кэш (S4/диагностика). */
    public static void clear(Context ctx) {
        mem().evictAll();
        synchronized (sNoCover) {
            sNoCover.clear();
        }
        File[] files = dir(ctx).listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    /** Суммарный размер дискового кэша в байтах (диагностика). */
    public static long diskBytes(Context ctx) {
        long total = 0;
        File[] files = dir(ctx).listFiles();
        if (files != null) {
            for (File f : files) {
                total += f.length();
            }
        }
        return total;
    }

    /** Число миниатюр на диске (диагностика). */
    public static int diskCount(Context ctx) {
        File[] files = dir(ctx).listFiles();
        return files == null ? 0 : files.length;
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    /** Синхронная загрузка: память → диск → извлечение из книги. */
    private static Bitmap load(Context ctx, long bookId, File file, String format, String entry) {
        File cached = thumbFile(ctx, bookId);
        Bitmap bmp = null;
        if (cached.isFile()) {
            bmp = decodeFile(cached);
            if (bmp != null) {
                touch(cached);
                return bmp;
            }
            cached.delete(); // битый кэш — перечитаем из книги
        }
        if (file == null || !file.isFile()) {
            return null;
        }
        byte[] raw = CoverStore.raw(file, format, entry);
        if (raw == null || raw.length == 0) {
            return null;
        }
        bmp = decodeBytes(raw);
        if (bmp == null) {
            return null;
        }
        savePng(bmp, cached);
        trimDisk(ctx);
        return bmp;
    }

    private static Bitmap decodeFile(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            decodeBounds(o);
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decodeBytes(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            decodeBounds(o);
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Общий расчёт: уменьшить до {@link #MAX_SIDE} и декодировать как RGB_565. */
    private static void decodeBounds(BitmapFactory.Options o) {
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            return;
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) {
            sample *= 2;
        }
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inJustDecodeBounds = false;
    }

    private static void savePng(Bitmap bmp, File out) {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        FileOutputStream fos = null;
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try {
            fos = new FileOutputStream(tmp);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
            fos = null;
            if (out.exists()) {
                out.delete();
            }
            tmp.renameTo(out);
        } catch (Throwable t) {
            tmp.delete();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                    // закрытие кэша не критично
                }
            }
        }
    }

    /** Отметить файл как «свежий» — для LRU по времени изменения. */
    private static void touch(File f) {
        try {
            f.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) {
            // не критично
        }
    }

    /**
     * Подрезать дисковый кэш обложек под текущий лимит (S4.7). Вызывать в фоне:
     * обход каталога и удаление файлов — дисковые операции.
     */
    public static void enforceLimit(Context ctx) {
        trimDisk(ctx);
    }

    /** Дисковый LRU: пока суммарный объём больше лимита — удаляем самые старые. */
    private static void trimDisk(Context ctx) {
        int limitMb = 64;
        Prefs p = App.prefs();
        if (p != null) {
            limitMb = p.coverCacheMb();
        }
        if (limitMb <= 0) {
            limitMb = 64;
        }
        long limit = (long) limitMb * 1024L * 1024L;
        File[] files = dir(ctx).listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        while (total > limit) {
            File oldest = null;
            for (File f : files) {
                if (f.exists() && (oldest == null || f.lastModified() < oldest.lastModified())) {
                    oldest = f;
                }
            }
            if (oldest == null) {
                break;
            }
            long len = oldest.length();
            if (oldest.delete()) {
                total -= len;
            } else {
                break;
            }
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), DIR);
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    private static MemoryLru mem() {
        MemoryLru m = sMem;
        if (m == null) {
            synchronized (ThumbCache.class) {
                m = sMem;
                if (m == null) {
                    int mb = MEM_CAP_MB;
                    Prefs p = App.prefs();
                    if (p != null && p.coverCacheMb() > 0 && p.coverCacheMb() < mb) {
                        mb = p.coverCacheMb();
                    }
                    m = new MemoryLru(mb * 1024 * 1024);
                    sMem = m;
                }
            }
        }
        return m;
    }

    private static ExecutorService bg() {
        ExecutorService e = sBg;
        if (e == null) {
            synchronized (ThumbCache.class) {
                e = sBg;
                if (e == null) {
                    e = Executors.newSingleThreadExecutor();
                    sBg = e;
                }
            }
        }
        return e;
    }

    private static Handler main() {
        Handler h = sMain;
        if (h == null) {
            synchronized (ThumbCache.class) {
                h = sMain;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    sMain = h;
                }
            }
        }
        return h;
    }
}
