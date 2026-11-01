package ru.unclerus.library.fmt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * Определение кодировки текстового XML без внешних библиотек: BOM → объявление
 * {@code <?xml ... encoding="..."?>} → UTF-8 (PLAN §4).
 *
 * <p>На коллекции оператора FB2 — UTF-8 (~91%), windows-1251 (~3%), единичные
 * UTF-16; cp866 встречается в именах entry внутри zip, но поддерживается и тут.
 * Объявление кодировки в первых байтах ASCII-совместимо у всех этих кодировок,
 * поэтому распознаётся побайтово до создания {@link Reader}.
 */
public final class EncodingSniffer {

    private static final int HEAD = 4096;

    private EncodingSniffer() {}

    /**
     * Обернуть поток в {@link Reader} с определённой кодировкой. Прочитанные при
     * распознавании байты возвращаются в поток ({@link SequenceInputStream}),
     * поэтому вызывающий ничего не теряет.
     */
    public static Reader open(InputStream in) throws IOException {
        byte[] head = new byte[HEAD];
        int n = readHead(in, head);
        int off = bomLength(head, n);
        String enc = sniff(head, n);
        ByteArrayInputStream first = new ByteArrayInputStream(head, off, n - off);
        SequenceInputStream seq = new SequenceInputStream(first, in);
        return new InputStreamReader(seq, charset(enc));
    }

    /** Длина BOM в начале потока (0, 2 или 3 байта) — его нужно пропустить: XML-парсер не принимает U+FEFF перед прологом. */
    private static int bomLength(byte[] b, int len) {
        if (len >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return 3;
        }
        if (len >= 2 && (((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
                || ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF))) {
            return 2;
        }
        return 0;
    }

    /** Кодировка по BOM/объявлению; по умолчанию {@code UTF-8}. */
    public static String sniff(byte[] head, int len) {
        if (len >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB
                && (head[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }
        if (len >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return "UTF-16LE";
        }
        if (len >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return "UTF-16BE";
        }
        String declared = declarationEncoding(head, len);
        return declared != null ? declared : "UTF-8";
    }

    /** Прочитать до {@code buf.length} байт (короче — только если поток кончился). */
    private static int readHead(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        return off;
    }

    /** Разбор {@code encoding="..."} из XML-объявления в начале файла. */
    private static String declarationEncoding(byte[] b, int len) {
        int end = Math.min(len, 256);
        int i = indexOf(b, end, "encoding");
        if (i < 0) {
            return null;
        }
        i += 8;
        while (i < end && isSpace(b[i])) {
            i++;
        }
        if (i >= end || b[i] != '=') {
            return null;
        }
        i++;
        while (i < end && isSpace(b[i])) {
            i++;
        }
        if (i >= end) {
            return null;
        }
        char quote = (char) (b[i] & 0xFF);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        i++;
        StringBuilder s = new StringBuilder();
        while (i < end) {
            char c = (char) (b[i] & 0xFF);
            if (c == quote) {
                break;
            }
            s.append(c);
            i++;
        }
        return normalize(s.toString());
    }

    /** Привести имя кодировки к имени Java-чарсета; неизвестное → {@code null}. */
    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.trim().toLowerCase(java.util.Locale.US).replace("_", "-");
        if (c.startsWith("utf-8") || c.equals("utf8")) {
            return "UTF-8";
        }
        if (c.startsWith("utf-16")) {
            if (c.endsWith("le")) {
                return "UTF-16LE";
            }
            if (c.endsWith("be")) {
                return "UTF-16BE";
            }
            return "UTF-16";
        }
        if (c.contains("1251")) {
            return "windows-1251";
        }
        if (c.contains("866")) {
            return "Cp866";
        }
        if (c.startsWith("koi8")) {
            return "KOI8-R";
        }
        return null;
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (Throwable t) {
            return Charset.forName("UTF-8");
        }
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t';
    }

    private static int indexOf(byte[] b, int end, String needle) {
        int n = needle.length();
        for (int i = 0; i + n <= end; i++) {
            int j = 0;
            while (j < n && b[i + j] == needle.charAt(j)) {
                j++;
            }
            if (j == n) {
                return i;
            }
        }
        return -1;
    }

    /** Для тестов/диагностики: доступен ли чарсет в этой сборке. */
    public static boolean available(String name) {
        try {
            new InputStreamReader(new ByteArrayInputStream(new byte[0]), name);
            return true;
        } catch (UnsupportedEncodingException e) {
            return false;
        }
    }
}
