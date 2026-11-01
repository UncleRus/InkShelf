package ru.unclerus.library.cover;

import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ru.unclerus.library.fmt.BookFormat;
import ru.unclerus.library.fmt.EncodingSniffer;
import ru.unclerus.library.fmt.Zips;

/**
 * Ленивое извлечение обложки книги (PLAN §7, Фаза C). Возвращает <b>байты</b>
 * картинки (JPEG/PNG/GIF) — декодирует и уменьшает уже {@link ThumbCache}.
 *
 * <p>Что лежит в {@code cover_entry} (его заполняет Фаза B, см. читатели):
 * <ul>
 *   <li>{@code fb2}/{@code fb2zip} — id {@code <binary>} без {@code #}; байты — base64
 *       в самом FB2 (в zip — внутри его {@code .fb2}-entry);</li>
 *   <li>{@code epub} — путь картинки внутри zip (уже относительно корня архива);</li>
 *   <li>{@code mobi} — номер записи PDB (числом);</li>
 * </ul>
 *
 * <p>FB2 читается <b>потоково</b> (pull-парсер поверх потока) — весь файл и все
 * {@code <binary>} в память не тянутся, накапливается только нужная картинка.
 * Любая ошибка (битый архив, неожиданный формат) — {@code null}, без исключений.
 */
public final class CoverStore {

    /** Разумный предел картинки обложки (защита от «мусорных» записей). */
    private static final int MAX_BYTES = 8 << 20;

    private CoverStore() {}

    /**
     * Байты обложки или {@code null}, если её нет/не удалось прочитать.
     *
     * @param file       файл книги
     * @param format     значение {@code BookFormat} (может быть {@code null} — тогда по расширению)
     * @param coverEntry «спека» обложки из БД
     */
    public static byte[] raw(File file, String format, String coverEntry) {
        if (file == null || !file.isFile() || coverEntry == null || coverEntry.trim().isEmpty()) {
            return null;
        }
        String id = coverEntry.trim();
        try {
            String fmt = format;
            if (fmt == null) {
                String n = file.getName().toLowerCase(Locale.US);
                if (n.endsWith(".epub")) {
                    fmt = BookFormat.EPUB;
                } else if (n.endsWith(".mobi") || n.endsWith(".azw") || n.endsWith(".azw3")) {
                    fmt = BookFormat.MOBI;
                } else {
                    fmt = n.endsWith(".zip") ? BookFormat.FB2ZIP : BookFormat.FB2;
                }
            }
            if (BookFormat.EPUB.equals(fmt)) {
                return fromEpub(file, id);
            }
            if (BookFormat.MOBI.equals(fmt)) {
                return fromMobi(file, id);
            }
            if (BookFormat.FB2ZIP.equals(fmt)) {
                return fromFb2Zip(file, id);
            }
            return fromFb2File(file, id);
        } catch (Throwable t) {
            // Битый файл/архив — обложки просто нет.
            return null;
        }
    }

    // ------------------------------------------------------------------
    // FB2 (файл и zip)
    // ------------------------------------------------------------------

    private static byte[] fromFb2File(File file, String id) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            return streamBinary(in, id);
        } finally {
            close(in);
        }
    }

    private static byte[] fromFb2Zip(File file, String id) throws Exception {
        ZipFile zip = null;
        try {
            zip = Zips.open(file);
            ZipEntry entry = Zips.findSuffix(zip, ".fb2");
            if (entry == null) {
                return null;
            }
            InputStream in = zip.getInputStream(entry);
            try {
                return streamBinary(in, id);
            } finally {
                close(in);
            }
        } finally {
            Zips.close(zip);
        }
    }

    /**
     * Потоково найти {@code <binary id="...">} и собрать его base64. Останавливаемся
     * сразу после нужного элемента — остальной документ не разбираем.
     */
    private static byte[] streamBinary(InputStream in, String id) throws Exception {
        Reader r = EncodingSniffer.open(in);
        XmlPullParser p = Xml.newPullParser();
        p.setInput(r);
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "binary".equals(local(p.getName()))) {
                String bid = attr(p, "id");
                if (bid != null && (bid.equals(id) || bid.equalsIgnoreCase(id))) {
                    return readBase64(p);
                }
            }
            ev = p.next();
        }
        return null;
    }

    /** Накопить TEXT/CDSECT до {@code </binary>} и раскодировать base64. */
    private static byte[] readBase64(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int ev = p.next();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                String t = p.getText();
                if (t != null) {
                    for (int i = 0; i < t.length(); i++) {
                        char c = t.charAt(i);
                        if (!Character.isWhitespace(c)) {
                            sb.append(c);
                        }
                    }
                }
            } else if (ev == XmlPullParser.START_TAG) {
                break; // неожиданная вложенность — элемент испорчен
            } else if (ev == XmlPullParser.END_TAG && "binary".equals(local(p.getName()))) {
                break;
            }
            if (sb.length() > MAX_BYTES * 2L) {
                break; // слишком большой base64 — не обложка
            }
            ev = p.next();
        }
        if (sb.length() == 0) {
            return null;
        }
        byte[] out = Base64.decode(sb.toString(), Base64.DEFAULT);
        return out != null && out.length > 0 ? out : null;
    }

    // ------------------------------------------------------------------
    // EPUB
    // ------------------------------------------------------------------

    private static byte[] fromEpub(File file, String entry) throws IOException {
        ZipFile zip = null;
        try {
            zip = Zips.open(file);
            ZipEntry e = Zips.find(zip, entry);
            if (e == null) {
                String norm = normalize(entry);
                if (!norm.equals(entry)) {
                    e = Zips.find(zip, norm);
                }
            }
            if (e == null) {
                return null;
            }
            InputStream in = zip.getInputStream(e);
            try {
                return readAll(in);
            } finally {
                Zips.close(in);
            }
        } finally {
            Zips.close(zip);
        }
    }

    // ------------------------------------------------------------------
    // MOBI
    // ------------------------------------------------------------------

    /**
     * Обложка MOBI — отдельная запись PDB с номером {@code entry} (её заполняет
     * {@code MobiReader} из EXTH 201/202). Картинки не сжаты PalmDOC, поэтому
     * берём байты записи как есть.
     */
    private static byte[] fromMobi(File file, String entry) throws IOException {
        int rec;
        try {
            rec = Integer.parseInt(entry.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (rec < 0) {
            return null;
        }
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            long len = raf.length();
            if (len < 78) {
                return null;
            }
            byte[] head = new byte[78];
            raf.seek(0);
            raf.readFully(head);
            int num = u16(head, 76);
            if (rec >= num || num <= 0) {
                return null;
            }
            byte[] tbl = new byte[num * 8];
            raf.seek(78);
            raf.readFully(tbl);
            long off = u32(tbl, rec * 8);
            long end = (rec + 1 < num) ? u32(tbl, (rec + 1) * 8) : len;
            long size = end - off;
            if (off < 78 || size <= 0 || size > MAX_BYTES) {
                return null;
            }
            byte[] data = new byte[(int) size];
            raf.seek(off);
            raf.readFully(data);
            return data;
        } finally {
            raf.close();
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    /** Свернуть {@code ./} и {@code a/..} в пути entry (EPUB иногда даёт {@code ../}). */
    private static String normalize(String path) {
        String[] parts = path.replace('\\', '/').split("/");
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (String seg : parts) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[64 * 1024];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) {
                return null;
            }
            out.write(buf, 0, n);
        }
        byte[] b = out.toByteArray();
        return b.length > 0 ? b : null;
    }

    private static String local(String name) {
        if (name == null) {
            return null;
        }
        int i = name.indexOf(':');
        return i < 0 ? name : name.substring(i + 1);
    }

    private static String attr(XmlPullParser p, String name) {
        int n = p.getAttributeCount();
        for (int i = 0; i < n; i++) {
            String an = p.getAttributeName(i);
            if (an != null && an.equalsIgnoreCase(name)) {
                return p.getAttributeValue(i);
            }
        }
        return null;
    }

    private static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // закрытие обложки не критично
            }
        }
    }
}
