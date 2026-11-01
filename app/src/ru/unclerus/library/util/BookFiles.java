package ru.unclerus.library.util;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import ru.unclerus.library.Logger;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Единая точка доступа к файлу книги. Книга адресуется либо обычным путём
 * (корень {@code file}, основной путь через {@code MANAGE_EXTERNAL_STORAGE}),
 * либо SAF-документом ({@code content://…}, корень {@code saf}).
 *
 * <p>Все читатели форматов работают с {@link File}. Для SAF-документа файл
 * материализуется копией в кэш ({@link #open}) и удаляется вызывающим
 * ({@link #release}) — при этом весь существующий код разбора/обложек
 * переиспользуется без изменений. SAF — резервный путь, копирование
 * незначительных объёмов допустимо.
 */
public final class BookFiles {

    private static final String SCHEME = "content://";
    private static final String TMP_DIR = "saf";
    /** Подкаталог копий SAF во внутреннем кэше (номер 6 в BACKLOG.md). */
    private static final String OPEN_DIR = "saf/open";
    /**
     * Подкаталог копий SAF во внешнем кэше. Копию, переданную читалке по
     * {@code file://}, должны уметь прочитать и сторонние читалки: NeoReader и
     * остальные имеют {@code MANAGE_EXTERNAL_STORAGE}, поэтому внешний кэш им
     * доступен, а внутренний {@code /data/data/…} — нет.
     */
    private static final String OPEN_DIR_EXT = "saf-open";
    /** Срок жизни неиспользуемой копии SAF до уборки (сутки). */
    private static final long OPEN_TTL_MS = 24L * 60 * 60 * 1000;

    /** Время последней уборки копий SAF (см. {@link #sweepOpens}). */
    private static long sLastSweep;

    private BookFiles() {}

    /** Адрес — SAF-документ ({@code content://}), а не обычный путь? */
    public static boolean isSaf(String path) {
        return path != null && path.startsWith(SCHEME);
    }

    /** Существует ли книга и доступна ли на чтение. */
    public static boolean exists(Context ctx, String path) {
        if (path == null) {
            return false;
        }
        if (!isSaf(path)) {
            try {
                return new File(path).isFile();
            } catch (Throwable t) {
                return false;
            }
        }
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(Uri.parse(path));
            return in != null;
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
        }
    }

    /**
     * Локальный файл для чтения. Для обычного пути — он сам; для SAF-документа —
     * временная копия в кэше. Возвращает {@code null}, если книга недоступна.
     * Временную копию нужно вернуть через {@link #release}.
     */
    public static File open(Context ctx, String path) {
        if (path == null) {
            return null;
        }
        if (!isSaf(path)) {
            File f = new File(path);
            return f.isFile() ? f : null;
        }
        InputStream in = null;
        OutputStream out = null;
        File tmp = null;
        try {
            in = ctx.getContentResolver().openInputStream(Uri.parse(path));
            if (in == null) {
                return null;
            }
            File dir = new File(ctx.getCacheDir(), TMP_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            tmp = File.createTempFile("book", suffix(path), dir);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            close(out);
            out = null;
            close(in);
            in = null;
            return tmp;
        } catch (Throwable t) {
            Logger.w("BookFiles", "не удалось открыть " + path + ": " + t);
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return null;
        } finally {
            close(in);
            close(out);
        }
    }

    /** Удалить временную копию SAF (для обычного пути — ничего не делает). */
    public static void release(String path, File f) {
        if (f == null || !isSaf(path)) {
            return;
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {
            // удаление временного файла некритично
        }
    }

    // ------------------------------------------------------------------
    // Материализация под внешнюю читалку (номер 6 в BACKLOG.md)
    // ------------------------------------------------------------------

    /**
     * Копия SAF-книги во временный файл для передачи внешней читалке. Для
     * обычного пути возвращается сам файл. Копия живёт в
     * {@code cacheDir/saf/open/<hash>/<имя>} и переиспользуется при повторном
     * открытии той же книги; старые копии убирает {@link #cleanupOpens}.
     *
     * @return читаемый файл или {@code null}, если книга недоступна
     */
    public static File materialize(Context ctx, String path) {
        if (path == null) {
            return null;
        }
        if (!isSaf(path)) {
            File f = new File(path);
            return f.isFile() ? f : null;
        }
        File dir = new File(openBase(ctx), Integer.toHexString(path.hashCode()));
        File out = new File(dir, safeName(name(path)));
        if (out.isFile() && out.length() > 0) {
            out.setLastModified(System.currentTimeMillis());
            return out; // копия уже есть — не копируем заново
        }
        sweepOpens(ctx);
        InputStream in = null;
        OutputStream os = null;
        File tmp = null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            tmp = new File(dir, out.getName() + ".part");
            in = ctx.getContentResolver().openInputStream(Uri.parse(path));
            if (in == null) {
                return null;
            }
            os = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
            close(os);
            os = null;
            close(in);
            in = null;
            if (out.exists()) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            if (!tmp.renameTo(out)) {
                return null;
            }
            return out;
        } catch (Throwable t) {
            Logger.w("BookFiles", "не удалось материализовать " + path + ": " + t);
            return null;
        } finally {
            close(in);
            close(os);
            if (tmp != null && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** Каталог копий SAF во внешнем кэше или {@code null}, если он недоступен. */
    private static File openBaseExt(Context ctx) {
        try {
            File ext = ctx.getExternalCacheDir();
            return ext == null ? null : new File(ext, OPEN_DIR_EXT);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Каталог копий SAF: внешний кэш, а при его недоступности — внутренний. */
    private static File openBase(Context ctx) {
        File ext = openBaseExt(ctx);
        return ext != null ? ext : new File(ctx.getCacheDir(), OPEN_DIR);
    }

    private static String canonical(File f) {
        try {
            return f == null ? null : f.getCanonicalPath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Канонические корни копий SAF (внутренний и внешний). */
    private static String[] materializedRoots(Context ctx) {
        return new String[]{
                canonical(new File(ctx.getCacheDir(), OPEN_DIR)),
                canonical(openBaseExt(ctx)),
        };
    }

    /** Лежит ли путь в каталоге материализованных SAF-копий (для FileProvider). */
    public static boolean isMaterialized(Context ctx, String absolutePath) {
        if (ctx == null || absolutePath == null) {
            return false;
        }
        try {
            String p = new File(absolutePath).getCanonicalPath();
            String[] roots = materializedRoots(ctx);
            for (int i = 0; i < roots.length; i++) {
                if (roots[i] != null && p.startsWith(roots[i] + File.separator)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Убрать давно неиспользуемые копии SAF (номер 6 в {@code BACKLOG.md}).
     * Копии свежее {@link #OPEN_TTL_MS} не трогаем: книгу могли открыть и
     * читать долго; свежая копия помечается при выдаче и повторном открытии.
     */
    public static void cleanupOpens(Context ctx) {
        long now = System.currentTimeMillis();
        // Чистим обе базы: копии могли остаться и во внутреннем кэше (пока
        // внешний был недоступен), и во внешнем.
        trimOpenBase(openBaseExt(ctx), now);
        trimOpenBase(new File(ctx.getCacheDir(), OPEN_DIR), now);
    }

    private static void trimOpenBase(File base, long now) {
        if (base == null) {
            return;
        }
        File[] dirs = base.listFiles();
        if (dirs == null) {
            return;
        }
        for (File d : dirs) {
            long newest = d.lastModified();
            File[] files = d.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.lastModified() > newest) {
                        newest = f.lastModified();
                    }
                }
            }
            if (now - newest > OPEN_TTL_MS) {
                deleteTree(d);
            }
        }
    }

    /** Не чаще раза в час: полный обход каталога копий при открытии книги. */
    private static synchronized void sweepOpens(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - sLastSweep < 60L * 60 * 1000) {
            return;
        }
        sLastSweep = now;
        cleanupOpens(ctx);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Безопасное имя файла (без разделителей пути) с сохранением расширения. */
    private static String safeName(String nm) {
        if (nm == null || nm.isEmpty()) {
            return "book";
        }
        StringBuilder b = new StringBuilder(nm.length());
        for (int i = 0; i < nm.length(); i++) {
            char c = nm.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '-'
                    || c == '_' || c == ' ' || c == '(' || c == ')' || c == '+';
            b.append(ok ? c : '_');
        }
        String s = b.toString();
        if (s.length() > 120) {
            s = s.substring(s.length() - 120); // хвост с расширением важнее начала
        }
        return s;
    }

    /** Имя файла книги (для SAF — последний сегмент document-id). */
    public static String name(String path) {
        if (path == null) {
            return "";
        }
        if (!isSaf(path)) {
            return new File(path).getName();
        }
        try {
            String seg = Uri.parse(path).getLastPathSegment();
            if (seg != null) {
                int slash = seg.lastIndexOf('/');
                return slash >= 0 ? seg.substring(slash + 1) : seg;
            }
        } catch (Throwable ignored) {
            // вернём адрес как есть
        }
        return path;
    }

    /**
     * Удалить файл книги. Для обычного пути — {@link File#delete()};
     * для SAF-документа — {@code DocumentsContract.deleteDocument}.
     *
     * @return {@code true}, если файла больше нет
     */
    public static boolean delete(Context ctx, String path) {
        if (path == null) {
            return false;
        }
        if (!isSaf(path)) {
            File f = new File(path);
            return !f.exists() || f.delete();
        }
        try {
            return DocumentsContract.deleteDocument(ctx.getContentResolver(), Uri.parse(path));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Расширение из адреса (для распознавания zip и т.п.): последний сегмент
     * пути/документа, декодированный от percent-кодирования.
     */
    private static String suffix(String path) {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int slash = p.lastIndexOf('/');
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        try {
            name = Uri.decode(name);
        } catch (Throwable ignored) {
            // оставляем как есть
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 8) {
            return name.substring(dot);
        }
        return ".bin";
    }

    private static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // закрытие некритично
            }
        }
    }
}
