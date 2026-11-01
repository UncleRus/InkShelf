package ru.unclerus.library.fmt.fb2;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ru.unclerus.library.fmt.BookFormat;
import ru.unclerus.library.fmt.BookMeta;
import ru.unclerus.library.fmt.EncodingSniffer;
import ru.unclerus.library.fmt.GenreTable;
import ru.unclerus.library.fmt.MetadataReader;
import ru.unclerus.library.fmt.Zips;
import ru.unclerus.library.util.Text;

/**
 * Потоковый разбор FB2 (PLAN §4, §7). Читается только {@code <description>};
 * блоки {@code <binary>} (обложка, картинки) не загружаются — важно при ОЗУ
 * 1.6 ГБ. Кодировка — по BOM/объявлению ({@link EncodingSniffer}).
 *
 * <p>Источники: {@code .fb2} и {@code .fb2.zip}/«голый» {@code .zip} — берётся
 * единственный entry с расширением {@code .fb2}. Имя entry становится
 * {@code entry_id}; для zip с не-UTF-8 именами есть повтор с {@code Cp866}.
 */
public final class Fb2Reader implements MetadataReader {

    private static final int ANNOTATION_MAX = 4000;

    @Override
    public BookMeta read(File file) throws Exception {
        String name = file.getName().toLowerCase(Locale.US);
        if (name.endsWith(".zip")) {
            return readZip(file);
        }
        BookMeta meta = new BookMeta();
        meta.format = BookFormat.FB2;
        InputStream in = new FileInputStream(file);
        try {
            parse(in, meta);
        } finally {
            close(in);
        }
        if (Text.isBlank(meta.title)) {
            meta.title = BookFormat.displayTitle(file.getName());
        }
        if (Text.isBlank(meta.entryId)) {
            meta.entryId = file.getName();
        }
        return meta;
    }

    // ------------------------------------------------------------------
    // Архив
    // ------------------------------------------------------------------

    private BookMeta readZip(File file) throws Exception {
        BookMeta meta = new BookMeta();
        meta.format = BookFormat.FB2ZIP;
        ZipFile zip = null;
        try {
            zip = Zips.open(file);
            ZipEntry entry = Zips.findSuffix(zip, ".fb2");
            if (entry == null) {
                boolean epub = Zips.find(zip, "mimetype") != null;
                throw new IOException(epub ? "не FB2 (EPUB)" : "в архиве нет .fb2");
            }
            meta.entryId = entry.getName();
            InputStream in = zip.getInputStream(entry);
            try {
                parse(in, meta);
            } finally {
                close(in);
            }
        } finally {
            close(zip);
        }
        if (Text.isBlank(meta.title)) {
            meta.title = BookFormat.displayTitle(file.getName());
        }
        return meta;
    }

    // ------------------------------------------------------------------
    // XML
    // ------------------------------------------------------------------

    private void parse(InputStream in, BookMeta meta) throws Exception {
        Reader r = EncodingSniffer.open(in);
        XmlPullParser p = Xml.newPullParser();
        p.setInput(r);
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "title-info".equals(local(p.getName()))) {
                parseTitleInfo(p, meta);
                return; // после title-info метаданных нет
            }
            ev = p.next();
        }
    }

    /**
     * Разбор {@code <title-info>}. Каждый обработчик дочернего элемента
     * «съедает» поддерево целиком и оставляет парсер на его {@code END_TAG},
     * поэтому внешний цикл видит только {@code </title-info>}.
     */
    private static void parseTitleInfo(XmlPullParser p, BookMeta meta) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if ("author".equals(n)) {
                    BookMeta.Author a = parseAuthor(p);
                    if (a != null) {
                        meta.authors.add(a);
                    }
                } else if ("sequence".equals(n)) {
                    parseSequence(p, meta);
                } else if ("book-title".equals(n)) {
                    meta.title = Text.collapse(textOf(p));
                } else if ("genre".equals(n)) {
                    String g = GenreTable.normalizeCode(textOf(p));
                    if (!g.isEmpty() && !meta.genres.contains(g)) {
                        meta.genres.add(g);
                    }
                } else if ("annotation".equals(n)) {
                    meta.annotation = cap(paragraphs(p), ANNOTATION_MAX);
                } else if ("coverpage".equals(n)) {
                    meta.coverEntry = parseCoverpage(p);
                } else {
                    skip(p);
                }
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static BookMeta.Author parseAuthor(XmlPullParser p) throws Exception {
        String first = null;
        String middle = null;
        String last = null;
        String nick = null;
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if ("first-name".equals(n)) {
                    first = Text.collapse(textOf(p));
                } else if ("middle-name".equals(n)) {
                    middle = Text.collapse(textOf(p));
                } else if ("last-name".equals(n)) {
                    last = Text.collapse(textOf(p));
                } else if ("nickname".equals(n)) {
                    nick = Text.collapse(textOf(p));
                } else {
                    skip(p);
                }
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }
        String name = join(first, middle, last);
        if (Text.isBlank(name)) {
            name = nick;
        }
        if (Text.isBlank(name)) {
            return null;
        }
        String surname = !Text.isBlank(last) ? last : (!Text.isBlank(nick) ? nick : Text.surname(name));
        String sort = join(last, first, middle);
        if (Text.isBlank(sort)) {
            sort = name;
        }
        return new BookMeta.Author(name, surname, sort);
    }

    /**
     * Серия: берём внешнюю {@code <sequence>}; если у неё нет имени — первое
     * непустое из вложенных (подсерий).
     */
    private static void parseSequence(XmlPullParser p, BookMeta meta) throws Exception {
        String name = attr(p, "name");
        String number = attr(p, "number");
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                if ("sequence".equals(local(p.getName()))) {
                    if (Text.isBlank(name)) {
                        name = attr(p, "name");
                    }
                    if (Text.isBlank(number)) {
                        number = attr(p, "number");
                    }
                }
                depth++;
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }
        if (!Text.isBlank(name)) {
            meta.seriesName = Text.collapse(name);
            meta.seriesNumber = Text.collapse(number);
        }
    }

    /** id обложки из {@code <coverpage><image l:href="#..."/>}. */
    private static String parseCoverpage(XmlPullParser p) throws Exception {
        String href = null;
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                if ("image".equals(local(p.getName())) && href == null) {
                    href = attr(p, "href");
                }
                depth++;
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }
        if (href == null) {
            return null;
        }
        String h = href.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        return h.isEmpty() ? null : h;
    }

    // ------------------------------------------------------------------
    // Примитивы разбора
    // ------------------------------------------------------------------

    /** Текст простого элемента (до соответствующего {@code END_TAG}). */
    private static String textOf(XmlPullParser p) throws Exception {
        int depth = 1;
        StringBuilder b = new StringBuilder();
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                depth++;
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            } else if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                String t = p.getText();
                if (t != null) {
                    b.append(t);
                }
            }
        }
        return b.toString();
    }

    /** Текст с абзацами: {@code <p>}/{@code <empty-line>} дают перевод строки. */
    private static String paragraphs(XmlPullParser p) throws Exception {
        int depth = 1;
        StringBuilder b = new StringBuilder();
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if ("p".equals(n) || "empty-line".equals(n)) {
                    b.append('\n');
                }
                depth++;
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            } else if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                String t = p.getText();
                if (t != null) {
                    b.append(t);
                }
            }
        }
        String[] lines = b.toString().replace('\r', '\n').split("\n");
        StringBuilder out = new StringBuilder(b.length());
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

    /** Пропустить поддерево элемента целиком. */
    private static void skip(XmlPullParser p) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                depth++;
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    /** Локальное имя тега без префикса пространства имён. */
    private static String local(String name) {
        if (name == null) {
            return "";
        }
        int c = name.indexOf(':');
        return c >= 0 ? name.substring(c + 1) : name;
    }

    /** Значение атрибута по локальному имени (учитывает {@code l:href}, {@code xlink:href}). */
    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String an = p.getAttributeName(i);
            if (an == null) {
                continue;
            }
            int c = an.indexOf(':');
            if (c >= 0) {
                an = an.substring(c + 1);
            }
            if (name.equalsIgnoreCase(an)) {
                return p.getAttributeValue(i);
            }
        }
        return null;
    }

    private static String join(String a, String b, String c) {
        StringBuilder s = new StringBuilder();
        append(s, a);
        append(s, b);
        append(s, c);
        return s.toString();
    }

    private static void append(StringBuilder s, String part) {
        if (part == null || part.trim().isEmpty()) {
            return;
        }
        if (s.length() > 0) {
            s.append(' ');
        }
        s.append(part.trim());
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

    private static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // закрытие не должно ронять индексацию
            }
        }
    }
}
