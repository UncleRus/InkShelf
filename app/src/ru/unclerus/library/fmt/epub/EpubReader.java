package ru.unclerus.library.fmt.epub;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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
 * Читатель метаданных EPUB (PLAN §7, M3b). EPUB — это zip: сначала
 * {@code META-INF/container.xml} указывает OPF-файл, затем из OPF берутся
 * {@code dc:title / dc:creator / dc:description / dc:subject}, серия
 * ({@code calibre:series} либо EPUB3 {@code belongs-to-collection}) и обложка
 * ({@code meta name="cover"} ссылается на id, либо {@code item
 * properties="cover-image"}). Тело книги (XHTML/картинки) не читается — важно
 * при ОЗУ 1.6 ГБ.
 */
public final class EpubReader implements MetadataReader {

    private static final int ANNOTATION_MAX = 4000;
    private static final String CONTAINER = "META-INF/container.xml";

    @Override
    public BookMeta read(File file) throws Exception {
        BookMeta meta = new BookMeta();
        meta.format = BookFormat.EPUB;
        ZipFile zip = null;
        try {
            zip = Zips.open(file);
            ZipEntry opf = findOpf(zip);
            if (opf == null) {
                throw new IOException("в архиве нет OPF");
            }
            meta.entryId = opf.getName();
            OpfContext ctx = new OpfContext(dirOf(opf.getName()));
            InputStream in = zip.getInputStream(opf);
            try {
                parseOpf(EncodingSniffer.open(in), meta, ctx);
            } finally {
                Zips.close(in);
            }
            meta.coverEntry = resolveCover(ctx);
        } finally {
            Zips.close(zip);
        }
        if (Text.isBlank(meta.title)) {
            meta.title = BookFormat.displayTitle(file.getName());
        }
        return meta;
    }

    // ------------------------------------------------------------------
    // Поиск OPF
    // ------------------------------------------------------------------

    private static ZipEntry findOpf(ZipFile zip) throws Exception {
        ZipEntry container = Zips.find(zip, CONTAINER);
        if (container != null) {
            InputStream in = zip.getInputStream(container);
            try {
                String path = rootfilePath(EncodingSniffer.open(in));
                if (!Text.isBlank(path)) {
                    String p = stripFragment(path.trim());
                    ZipEntry e = Zips.find(zip, p);
                    if (e != null) {
                        return e;
                    }
                }
            } finally {
                Zips.close(in);
            }
        }
        // Запасной путь: единственный/первый .opf в архиве.
        return Zips.findSuffix(zip, ".opf");
    }

    /** {@code <rootfile full-path="OEBPS/content.opf"/>} из container.xml. */
    private static String rootfilePath(Reader r) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(r);
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && "rootfile".equals(local(p.getName()))) {
                String path = attr(p, "full-path");
                if (!Text.isBlank(path)) {
                    return path;
                }
            }
            ev = p.next();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // OPF
    // ------------------------------------------------------------------

    private static void parseOpf(Reader r, BookMeta meta, OpfContext ctx) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(r);
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if ("metadata".equals(n)) {
                    parseMetadata(p, meta, ctx);
                } else if ("manifest".equals(n)) {
                    parseManifest(p, ctx);
                }
                // прочие узлы (package, spine, guide) не пропускаем целиком —
                // парсер сам обойдёт их и дойдёт до нужных контейнеров
            }
            ev = p.next();
        }
    }

    private static void parseMetadata(XmlPullParser p, BookMeta meta, OpfContext ctx) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if ("title".equals(n)) {
                    String t = Text.collapse(textOf(p));
                    if (Text.isBlank(meta.title) && !t.isEmpty()) {
                        meta.title = t;
                    }
                } else if ("creator".equals(n)) {
                    addAuthor(meta, textOf(p));
                } else if ("description".equals(n)) {
                    if (Text.isBlank(meta.annotation)) {
                        meta.annotation = cap(flatten(textOf(p)), ANNOTATION_MAX);
                    }
                } else if ("subject".equals(n)) {
                    addSubject(meta, textOf(p));
                } else if ("meta".equals(n)) {
                    parseMeta(p, meta, ctx);
                } else {
                    skip(p);
                }
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    /**
     * Элемент {@code <meta>}. EPUB2/calibre: атрибуты {@code name}/{@code content}.
     * EPUB3: {@code property}/{@code refines}, значение — текст элемента.
     */
    private static void parseMeta(XmlPullParser p, BookMeta meta, OpfContext ctx) throws Exception {
        String name = attr(p, "name");
        String content = attr(p, "content");
        String property = attr(p, "property");
        String refines = attr(p, "refines");
        String id = attr(p, "id");

        if (!Text.isBlank(name)) {
            if ("cover".equalsIgnoreCase(name.trim())) {
                if (!Text.isBlank(content)) {
                    ctx.coverId = content.trim();
                }
            } else if ("calibre:series".equalsIgnoreCase(name.trim())) {
                if (!Text.isBlank(content)) {
                    meta.seriesName = Text.collapse(content);
                }
            } else if ("calibre:series_index".equalsIgnoreCase(name.trim())) {
                if (!Text.isBlank(content)) {
                    meta.seriesNumber = Text.collapse(content);
                }
            }
            skip(p);
            return;
        }

        if (Text.isBlank(property)) {
            skip(p);
            return;
        }
        String prop = property.trim();
        if ("belongs-to-collection".equalsIgnoreCase(prop)) {
            String val = Text.collapse(textOf(p));
            if (!Text.isBlank(val) && Text.isBlank(meta.seriesName)) {
                meta.seriesName = val;
                ctx.collectionId = id == null ? null : id.trim();
            }
        } else if ("group-position".equalsIgnoreCase(prop)) {
            String val = Text.collapse(textOf(p));
            if (!Text.isBlank(val) && Text.isBlank(meta.seriesNumber) && matchesCollection(refines, ctx.collectionId)) {
                meta.seriesNumber = val;
            }
        } else {
            skip(p);
        }
    }

    private static void parseManifest(XmlPullParser p, OpfContext ctx) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                if ("item".equals(local(p.getName()))) {
                    String href = attr(p, "href");
                    String id = attr(p, "id");
                    String props = attr(p, "properties");
                    if (!Text.isBlank(href)) {
                        if (!Text.isBlank(id)) {
                            ctx.hrefById.put(id.trim(), href.trim());
                        }
                        if (props != null && props.toLowerCase(Locale.US).contains("cover-image")
                                && ctx.coverPropertyHref == null) {
                            ctx.coverPropertyHref = href.trim();
                        }
                    }
                    skip(p);
                } else {
                    skip(p);
                }
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    /** Путь обложки внутри архива: EPUB3 cover-image либо EPUB2 meta name="cover". */
    private static String resolveCover(OpfContext ctx) {
        String rel = ctx.coverPropertyHref;
        if (rel == null && !Text.isBlank(ctx.coverId)) {
            rel = ctx.hrefById.get(ctx.coverId.trim());
        }
        if (Text.isBlank(rel)) {
            return null;
        }
        return resolve(ctx.opfDir, stripFragment(rel.trim()));
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

    /**
     * {@code dc:subject} — свободный текст. В каталоге оставляем только те, что
     * сводятся к известному коду {@link GenreTable}; прочие не засоряют список.
     */
    private static void addSubject(BookMeta meta, String subject) {
        String code = GenreTable.normalizeCode(subject);
        if (code.isEmpty() || GenreTable.displayName(code) == null) {
            return;
        }
        if (!meta.genres.contains(code)) {
            meta.genres.add(code);
        }
    }

    // ------------------------------------------------------------------
    // Примитивы
    // ------------------------------------------------------------------

    /** Плоский текст: HTML-теги описания → пробел, блочные границы → перевод строки. */
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

    private static String local(String name) {
        if (name == null) {
            return "";
        }
        int c = name.indexOf(':');
        return c >= 0 ? name.substring(c + 1) : name;
    }

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

    private static boolean matchesCollection(String refines, String collectionId) {
        if (refines == null || collectionId == null) {
            return false;
        }
        String r = refines.trim();
        if (r.startsWith("#")) {
            r = r.substring(1);
        }
        return collectionId.equals(r);
    }

    private static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String stripFragment(String path) {
        int i = path.indexOf('#');
        return i < 0 ? path : path.substring(0, i);
    }

    /** Путь относительно каталога OPF → путь entry внутри архива. */
    private static String resolve(String dir, String href) {
        if (href.startsWith("/")) {
            return href.substring(1);
        }
        if (dir.isEmpty()) {
            return href;
        }
        return dir + "/" + href;
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

    /** Состояние разбора: каталог OPF, данные обложки, EPUB3-коллекция. */
    private static final class OpfContext {
        final String opfDir;
        final Map<String, String> hrefById = new HashMap<String, String>();
        String coverId;
        String coverPropertyHref;
        String collectionId;

        OpfContext(String opfDir) {
            this.opfDir = opfDir;
        }
    }
}
