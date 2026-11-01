package ru.unclerus.library.open;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.unclerus.library.Logger;

/**
 * Список читалок на устройстве (FR-5).
 *
 * <p>Сначала идут известные читалки из allowlist (порядок устойчив), затем —
 * любые другие приложения, зарегистрированные на {@code ACTION_VIEW} хотя бы для
 * одного книжного MIME. Так новые установленные читалки появляются в D1/D1·b без
 * правки кода.
 *
 * <p>При {@code targetSdk 30} на Android 11 без {@code <queries>} в манифесте
 * {@code PackageManager} не видит чужие пакеты — фильтры объявлены там.
 */
public final class ReaderRegistry {

    /**
     * Штатная читалка Onyx. Принимает {@code content://} манифестом, но путь
     * восстанавливает, а не копирует поток, — запускаем её через {@code file://}
     * (см. {@code research/reader-launch-matrix.md}).
     */
    public static final String KREADER = "com.onyx.kreader";

    /** Известные читалки: проверены в матрице запуска. */
    private static final String[] KNOWN = {
            "com.onyx.oreaderx",            // OReaderX (AlReader)
            "com.fbreader",                 // FBReader
            "com.foobnix.pro.pdf.reader",   // Librera FD
            KREADER,                        // NeoReader (Onyx)
    };

    /** Книжные MIME-типы (совпадают у всех четырёх читалок). */
    private static final String[] MIMES = {
            "application/fb2+zip",
            "application/epub+zip",
            "application/x-mobipocket-ebook",
    };

    /**
     * Пробные URI для тех же MIME. Читалки объявляют фильтры со схемой
     * ({@code file}/{@code content}) и MIME, поэтому интент без data-URI
     * (только тип) у них не резолвится — для проверки нужен фиктивный URI с
     * правильным расширением.
     */
    private static final String[] PROBE = {
            "content://probe/books/probe.fb2.zip",
            "content://probe/books/probe.epub",
            "content://probe/books/probe.mobi",
    };

    /** Подпись последнего записанного набора читалок — чтобы не дублировать строку в логе. */
    private static String sLogged = null;

    private ReaderRegistry() {}

    /** Установленные читалки: известные, затем прочие; без дублей. */
    public static List<ReaderInfo> installed(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, ReaderInfo> map = new LinkedHashMap<String, ReaderInfo>();
        for (String pkg : KNOWN) {
            if (canOpen(pm, pkg) && !map.containsKey(pkg)) {
                map.put(pkg, info(pm, pkg));
            }
        }
        for (int i = 0; i < MIMES.length; i++) {
            List<ResolveInfo> res;
            try {
                res = pm.queryIntentActivities(probeIntent(i, null), 0);
            } catch (Throwable t) {
                continue;
            }
            for (ResolveInfo ri : res) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (ctx.getPackageName().equals(pkg) || map.containsKey(pkg)) {
                    continue;
                }
                map.put(pkg, info(pm, pkg));
            }
        }
        List<ReaderInfo> out = new ArrayList<ReaderInfo>(map.values());
        log(out);
        return out;
    }

    /**
     * Записать набор читалок в лог один раз на процесс (или когда он изменился):
     * {@code installed()} зовётся и при старте, и при каждом открытии D1, а
     * строка в {@code files/library.log} нужна только для диагностики.
     */
    private static void log(List<ReaderInfo> out) {
        StringBuilder sb = new StringBuilder();
        for (ReaderInfo r : out) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(r.pkg);
        }
        String sig = out.size() + ":" + sb;
        if (sig.equals(sLogged)) {
            return;
        }
        sLogged = sig;
        if (out.isEmpty()) {
            Logger.w("Reader", "читалки не найдены (проверьте <queries> в манифесте)");
        } else {
            Logger.i("Reader", "читалок: " + out.size() + " — " + sb);
        }
    }

    /** Известная читалка по пакету; {@code null}, если её нет или она не умеет открывать книги. */
    public static ReaderInfo byPackage(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty() || !canOpen(ctx.getPackageManager(), pkg)) {
            return null;
        }
        return info(ctx.getPackageManager(), pkg);
    }

    /** Умеет ли приложение открывать книги (резолвится хоть один книжный MIME). */
    private static boolean canOpen(PackageManager pm, String pkg) {
        for (int i = 0; i < MIMES.length; i++) {
            try {
                if (pm.resolveActivity(probeIntent(i, pkg), 0) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
                // пакет мог исчезнуть между вызовами — считаем неумеющим
            }
        }
        return false;
    }

    /** Пробный VIEW-интент для {@code i}-го книжного MIME (с фиктивным data-URI). */
    private static Intent probeIntent(int i, String pkg) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(PROBE[i]), MIMES[i]);
        if (pkg != null) {
            intent.setPackage(pkg);
        }
        return intent;
    }

    private static ReaderInfo info(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return new ReaderInfo(pkg, label == null ? pkg : label.toString(),
                    pm.getApplicationIcon(ai));
        } catch (Throwable t) {
            return new ReaderInfo(pkg, pkg, null);
        }
    }
}
