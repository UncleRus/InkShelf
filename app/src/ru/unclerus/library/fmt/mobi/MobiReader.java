package ru.unclerus.library.fmt.mobi;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import ru.unclerus.library.fmt.BookFormat;
import ru.unclerus.library.fmt.BookMeta;
import ru.unclerus.library.fmt.GenreTable;
import ru.unclerus.library.fmt.MetadataReader;
import ru.unclerus.library.util.Text;

/**
 * Читатель метаданных MOBI/AZW (PLAN §7, M3c). Контейнер — PDB (Palm Database),
 * запись 0: PalmDOC-заголовок (16 байт) + MOBI-заголовок + EXTH. Читается
 * только запись 0 (заголовок и метаданные), тело книги и картинки не
 * материализуются — важно при ОЗУ 1.6 ГБ.
 *
 * <p>Из метаданных: полное имя (MOBI +0x44/+0x48, перекрывается EXTH 503),
 * авторы (EXTH 100), описание (EXTH 103), subjects (EXTH 105). Обложка — EXTH
 * 201 (offset от первой image-записи) либо 202; если EXTH нет — первая
 * image-запись из MOBI +0x5C. В {@code cover_entry} кладётся <b>номер записи
 * PDB</b> (строкой) — его разрешит {@code cover.CoverStore} в M6; формат книги
 * при этом {@code mobi}, поэтому номер однозначен.
 */
public final class MobiReader implements MetadataReader {

    private static final int PDB_HEADER = 78;
    private static final int PALMDOC = 16;
    private static final int EXTH_FLAG = 0x40;
    /** Верхняя граница читаемой части записи 0 (метаданные всегда в начале). */
    private static final int REC0_CAP = 1 << 20;

    @Override
    public BookMeta read(File file) throws Exception {
        BookMeta meta = new BookMeta();
        meta.format = BookFormat.MOBI;
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] head = new byte[PDB_HEADER];
            raf.readFully(head);
            String type = ascii(head, 60, 4);
            String creator = ascii(head, 64, 4);
            if (!"BOOK".equals(type) && !"MOBI".equals(creator)) {
                throw new IOException("не MOBI (type=" + type + ", creator=" + creator + ")");
            }
            meta.entryId = pdbName(head);

            int numRecords = u16(head, 76);
            if (numRecords < 1) {
                throw new IOException("в PDB нет записей");
            }
            long[] offsets = readRecordOffsets(raf, numRecords);
            if (offsets.length < 2 || offsets[0] < 0 || offsets[1] <= offsets[0]) {
                throw new IOException("повреждён список записей PDB");
            }

            int len = (int) Math.min(offsets[1] - offsets[0], REC0_CAP);
            byte[] rec0 = new byte[len];
            raf.seek(offsets[0]);
            readFully(raf, rec0);

            parseRecord0(rec0, numRecords, meta);
        } finally {
            raf.close();
        }

        if (Text.isBlank(meta.title)) {
            meta.title = BookFormat.displayTitle(file.getName());
        }
        return meta;
    }

    // ------------------------------------------------------------------
    // Запись 0
    // ------------------------------------------------------------------

    private static void parseRecord0(byte[] r, int numRecords, BookMeta meta) {
        if (r.length < PALMDOC + 0x74 + 4 || !"MOBI".equals(ascii(r, PALMDOC, 4))) {
            return; // старый PalmDOC без MOBI-заголовка — только имя PDB
        }
        long headerLen = u32(r, PALMDOC + 0x04);
        long encoding = u32(r, PALMDOC + 0x0C);
        long nameOff = u32(r, PALMDOC + 0x44);
        long nameLen = u32(r, PALMDOC + 0x48);
        long firstImage = u32(r, PALMDOC + 0x5C);
        long exthFlags = u32(r, PALMDOC + 0x70);

        String cs = encoding == 65001 ? "UTF-8" : "windows-1252";

        String name = decode(r, nameOff, nameLen, cs);
        if (!Text.isBlank(name)) {
            meta.title = Text.collapse(name);
        }

        long coverRecord = -1;
        if ((exthFlags & EXTH_FLAG) != 0) {
            long exthOff = PALMDOC + headerLen;
            coverRecord = parseExth(r, exthOff, firstImage, cs, meta);
        }
        if (coverRecord <= 0 && firstImage > 0 && firstImage < numRecords) {
            coverRecord = firstImage;
        }
        if (coverRecord > 0) {
            meta.coverEntry = String.valueOf(coverRecord);
        }
    }

    /**
     * Разбор EXTH. Возвращает номер записи обложки (или {@code -1}). Значение
     * EXTH 503 (updated title) перекрывает полное имя.
     */
    private static long parseExth(byte[] r, long off, long firstImage, String cs, BookMeta meta) {
        if (off < 0 || off + 12 > r.length) {
            return -1;
        }
        int o = (int) off;
        if (!"EXTH".equals(ascii(r, o, 4))) {
            return -1;
        }
        long total = u32(r, o + 4);
        long count = u32(r, o + 8);
        long end = Math.min(off + total, r.length);
        long coverRecord = -1;
        long thumbRecord = -1;

        int pos = o + 12;
        for (long i = 0; i < count && pos + 8 <= end; i++) {
            long type = u32(r, pos);
            long rlen = u32(r, pos + 4);
            if (rlen < 8 || pos + rlen > end) {
                break;
            }
            int dOff = pos + 8;
            int dLen = (int) (rlen - 8);
            switch ((int) type) {
                case 100:
                    addAuthor(meta, decode(r, dOff, dLen, cs));
                    break;
                case 103:
                    if (Text.isBlank(meta.annotation)) {
                        meta.annotation = cap(flatten(decode(r, dOff, dLen, cs)), 4000);
                    }
                    break;
                case 105:
                    addSubject(meta, decode(r, dOff, dLen, cs));
                    break;
                case 201:
                    coverRecord = firstImage + u32(r, dOff);
                    break;
                case 202:
                    thumbRecord = firstImage + u32(r, dOff);
                    break;
                case 503:
                    String t = Text.collapse(decode(r, dOff, dLen, cs));
                    if (!Text.isBlank(t)) {
                        meta.title = t;
                    }
                    break;
                default:
                    break;
            }
            pos += rlen;
        }
        return coverRecord > 0 ? coverRecord : thumbRecord;
    }

    // ------------------------------------------------------------------
    // PDB
    // ------------------------------------------------------------------

    /** Смещения записей (numRecords + 1: конец последней = длина файла). */
    private static long[] readRecordOffsets(RandomAccessFile raf, int numRecords) throws IOException {
        byte[] ri = new byte[numRecords * 8];
        readFully(raf, ri);
        long[] off = new long[numRecords + 1];
        for (int i = 0; i < numRecords; i++) {
            off[i] = u32(ri, i * 8);
        }
        off[numRecords] = raf.length();
        return off;
    }

    /** Имя базы PDB (32 байта, до NUL) — вторичный идентификатор. */
    private static String pdbName(byte[] head) {
        int n = 0;
        while (n < 32 && head[n] != 0) {
            n++;
        }
        if (n == 0) {
            return null;
        }
        String s = new String(head, 0, n, charset("windows-1252"));
        return Text.collapse(s);
    }

    // ------------------------------------------------------------------
    // Данные
    // ------------------------------------------------------------------

    private static void addAuthor(BookMeta meta, String creator) {
        String name = Text.collapse(creator);
        if (name.isEmpty()) {
            return;
        }
        for (int i = 0; i < meta.authors.size(); i++) {
            if (name.equalsIgnoreCase(meta.authors.get(i).name)) {
                return;
            }
        }
        String surname = Text.surname(name);
        String sort = Text.formatAuthor(name, true);
        if (Text.isBlank(sort)) {
            sort = name;
        }
        meta.authors.add(new BookMeta.Author(name, surname, sort));
    }

    /** EXTH 105 subject — свободный текст; оставляем только сводимые к коду {@link GenreTable}. */
    private static void addSubject(BookMeta meta, String subject) {
        String code = GenreTable.normalizeCode(subject);
        if (code.isEmpty() || GenreTable.displayName(code) == null) {
            return;
        }
        if (!meta.genres.contains(code)) {
            meta.genres.add(code);
        }
    }

    private static String flatten(String html) {
        if (html == null) {
            return null;
        }
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("<[^>]+>", " ");
        String[] lines = s.replace('\r', '\n').split("\n");
        StringBuilder out = new StringBuilder(s.length());
        for (String line : lines) {
            String t = Text.collapse(line);
            if (t.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(t);
        }
        return out.toString();
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------
    // Байты
    // ------------------------------------------------------------------

    private static String decode(byte[] b, long off, long len, String cs) {
        if (off < 0 || len <= 0 || off + len > b.length) {
            return null;
        }
        String s = new String(b, (int) off, (int) len, charset(cs));
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    private static java.nio.charset.Charset charset(String name) {
        try {
            return java.nio.charset.Charset.forName(name);
        } catch (Throwable t) {
            return java.nio.charset.Charset.forName("UTF-8");
        }
    }

    private static String ascii(byte[] b, int off, int len) {
        if (off < 0 || off + len > b.length) {
            return "";
        }
        StringBuilder s = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            s.append((char) (b[off + i] & 0xFF));
        }
        return s.toString();
    }

    private static int u16(byte[] b, int off) {
        if (off + 2 > b.length) {
            return 0;
        }
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) {
            return 0;
        }
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void readFully(RandomAccessFile raf, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = raf.read(buf, got, buf.length - got);
            if (n < 0) {
                break;
            }
            got += n;
        }
    }
}
