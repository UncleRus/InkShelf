package ru.unclerus.library.scan;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.db.MetaDao;
import ru.unclerus.library.db.Schema;
import ru.unclerus.library.db.Tx;
import ru.unclerus.library.fmt.BookFormat;
import ru.unclerus.library.fmt.BookMeta;
import ru.unclerus.library.fmt.MetadataReader;
import ru.unclerus.library.fmt.epub.EpubReader;
import ru.unclerus.library.fmt.fb2.Fb2Reader;
import ru.unclerus.library.fmt.mobi.MobiReader;
import ru.unclerus.library.util.BookFiles;

/**
 * Фаза B (PLAN §7): метаданные для книг с {@code needs_parse=1}.
 *
 * <p>Батчами по {@link #BATCH}: выборка id → разбор файлов <b>вне</b>
 * транзакции (чтобы не держать блокировку БД на медленном чтении) → запись
 * одной транзакцией на батч. Битый файл не прерывает индексацию: он получает
 * {@code meta_ok=0}, {@code needs_parse=0} и в следующий раз перечитается только
 * при изменении файла (это делает Фаза A через {@code touchSeen}).
 *
 * <p>Разбираются все форматы, для которых есть читатель ({@code FORMATS}) —
 * fb2/fb2zip/epub/mobi; иных поддерживаемых форматов нет.
 */
public final class ParsePhase {

    /** Размер батча. */
    public static final int BATCH = 50;

    private static final String FORMAT_IN = "'" + BookFormat.FB2 + "','" + BookFormat.FB2ZIP
            + "','" + BookFormat.EPUB + "','" + BookFormat.MOBI + "'";
    /**
     * Кого разбирать: новые/изменённые ({@code needs_parse=1}) и ранее не
     * разобранные ({@code meta_ok=0}) — так после исправления читателя битые
     * книги перечитываются при следующем ручном скане. Файл не читается, если
     * он недоступен.
     */
    private static final String WHERE = "(needs_parse=1 OR meta_ok=0)"
            + " AND availability='" + Schema.AVAIL_OK + "' AND format IN (" + FORMAT_IN + ")";

    private static final MetadataReader FB2 = new Fb2Reader();
    private static final MetadataReader EPUB = new EpubReader();
    private static final MetadataReader MOBI = new MobiReader();

    /** Обратный вызов прогресса; {@code false} — прервать разбор. */
    public interface Callback {
        boolean onProgress(long done, long total, long ok, long failed);
    }

    /** Итог Фазы B. */
    public static final class Stats {
        public long parsed;
        public long ok;
        public long failed;
        /** Книг не удалось записать (сбой транзакции батча) — останутся {@code needs_parse=1}. */
        public long skipped;
        /** Сколько книг подлежало разбору на старте фазы. */
        public long candidates;
        public boolean cancelled;
    }

    private ParsePhase() {}

    /** Пройти Фазу B. Вызывать из потока индексатора, вне открытой транзакции. */
    public static Stats run(SQLiteDatabase db, Callback cb) {
        Stats st = new Stats();
        st.candidates = countCandidates(db);
        long t0 = SystemClock.uptimeMillis();
        long lastId = 0;

        while (!st.cancelled) {
            // Keyset-пагинация по id: даже если запись батча провалится и
            // needs_parse останется 1, мы продвигаемся вперёд — цикла нет.
            List<Row> batch = nextBatch(db, lastId);
            if (batch.isEmpty()) {
                break;
            }
            lastId = batch.get(batch.size() - 1).id;
            List<Result> results = new ArrayList<Result>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                Result r = parse(batch.get(i));
                results.add(r);
                st.parsed++;
                if (r.meta != null) {
                    st.ok++;
                } else {
                    st.failed++;
                }
                if (cb != null && !cb.onProgress(st.parsed, st.candidates, st.ok, st.failed)) {
                    st.cancelled = true;
                    break;
                }
            }
            if (!writeBatch(db, results)) {
                st.skipped += results.size();
            }
        }

        Logger.i("ParsePhase", "Фаза B: разобрано " + st.parsed + " из " + st.candidates
                + " · метаданные " + st.ok + " · ошибок " + st.failed
                + (st.skipped > 0 ? " · не записано " + st.skipped : "")
                + " за " + (SystemClock.uptimeMillis() - t0) + " мс");
        return st;
    }

    // ------------------------------------------------------------------

    /** Следующий батч после {@code lastId} (keyset, без OFFSET). */
    private static List<Row> nextBatch(SQLiteDatabase db, long lastId) {
        List<Row> out = new ArrayList<Row>(BATCH);
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id, path, format FROM " + Schema.T_BOOK
                    + " WHERE id>? AND " + WHERE + " ORDER BY id LIMIT " + BATCH,
                    new String[]{String.valueOf(lastId)});
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getLong(0);
                r.path = c.getString(1);
                r.format = c.getString(2);
                out.add(r);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    private static long countCandidates(SQLiteDatabase db) {
        try {
            return DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM " + Schema.T_BOOK
                    + " WHERE " + WHERE, null);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Result parse(Row row) {
        Result r = new Result();
        r.row = row;
        try {
            MetadataReader reader = readerFor(row.format);
            if (reader == null) {
                r.error = "нет читателя для формата " + row.format;
                return r;
            }
            File f = BookFiles.open(App.ctx(), row.path);
            if (f == null) {
                r.error = "файл недоступен";
                Logger.w("ParsePhase", row.path + ": файл недоступен");
                return r;
            }
            try {
                r.meta = reader.read(f);
            } finally {
                BookFiles.release(row.path, f);
            }
        } catch (Throwable t) {
            r.error = t.toString();
            if (r.error == null || r.error.isEmpty()) {
                r.error = t.getClass().getSimpleName();
            }
            Logger.w("ParsePhase", row.path + ": " + r.error);
        }
        return r;
    }

    private static MetadataReader readerFor(String format) {
        if (BookFormat.FB2.equals(format) || BookFormat.FB2ZIP.equals(format)) {
            return FB2;
        }
        if (BookFormat.EPUB.equals(format)) {
            return EPUB;
        }
        if (BookFormat.MOBI.equals(format)) {
            return MOBI;
        }
        return null;
    }

    private static boolean writeBatch(final SQLiteDatabase db, final List<Result> results) {
        if (results.isEmpty()) {
            return true;
        }
        return Tx.silent(db, new Tx.Body() {
            @Override
            public void run(SQLiteDatabase d) {
                for (int i = 0; i < results.size(); i++) {
                    Result r = results.get(i);
                    MetaDao.writeMeta(d, r.row.id, r.meta, r.error);
                }
            }
        });
    }

    private static final class Row {
        long id;
        String path;
        String format;
    }

    private static final class Result {
        Row row;
        BookMeta meta;
        String error;
    }
}
