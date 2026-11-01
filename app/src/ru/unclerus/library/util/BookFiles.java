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
