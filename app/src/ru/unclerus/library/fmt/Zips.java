package ru.unclerus.library.fmt;

import android.os.Build;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Открытие zip-архивов книг ({@code .fb2.zip}, {@code .epub}) и поиск entry —
 * общая часть читателей форматов (PLAN §4).
 *
 * <p>Имена entry в старых Windows-архивах пишутся в cp866 без флага UTF-8 (на
 * коллекции оператора — 56 из 699). Android на таких именах бросает из
 * конструктора {@code ZipFile} {@code IllegalArgumentException: MALFORMED[1]}
 * (декодирование имени как UTF-8), поэтому {@link #open} сначала пробует UTF-8,
 * затем {@code Cp866} (перегрузка с {@link Charset} есть с API 24).
 */
public final class Zips {

    private Zips() {}

    /** Открыть архив, подобрав кодировку имён entry. */
    public static ZipFile open(File file) throws IOException {
        ZipFile zip = newZip(file, null);
        if (zip != null && !hasReplacementNames(zip)) {
            return zip;
        }
        close(zip);
        if (Build.VERSION.SDK_INT >= 24) {
            zip = newZip(file, Charset.forName("Cp866"));
            if (zip != null && !hasReplacementNames(zip)) {
                return zip;
            }
            close(zip);
        }
        zip = newZip(file, null);
        if (zip != null) {
            return zip;
        }
        throw new IOException("не удалось открыть архив");
    }

    /** Entry с точным (без учёта регистра) именем. */
    public static ZipEntry find(ZipFile zip, String exactName) {
        if (zip == null || exactName == null) {
            return null;
        }
        try {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (!e.isDirectory() && exactName.equalsIgnoreCase(e.getName())) {
                    return e;
                }
            }
        } catch (Throwable ignored) {
            // повреждённый архив — считаем entry ненайденным
        }
        return null;
    }

    /** Первый entry с суффиксом имени (без учёта регистра), например {@code .fb2}. */
    public static ZipEntry findSuffix(ZipFile zip, String suffix) {
        if (zip == null || suffix == null) {
            return null;
        }
        String low = suffix.toLowerCase(Locale.US);
        try {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String n = e.getName();
                if (n != null && n.toLowerCase(Locale.US).endsWith(low)) {
                    return e;
                }
            }
        } catch (Throwable ignored) {
            // см. find()
        }
        return null;
    }

    /** Есть ли в именах entry символ-замена (признак неверной кодировки имён). */
    public static boolean hasReplacementNames(ZipFile zip) {
        try {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                String n = it.nextElement().getName();
                if (n != null && n.indexOf('\uFFFD') >= 0) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return true;
        }
        return false;
    }

    public static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // закрытие не должно ронять индексацию
            }
        }
    }

    private static ZipFile newZip(File file, Charset cs) {
        try {
            return cs == null ? new ZipFile(file) : new ZipFile(file, cs);
        } catch (Throwable t) {
            return null;
        }
    }
}
