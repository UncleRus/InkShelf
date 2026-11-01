package ru.unclerus.library.ui;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

import ru.unclerus.library.App;
import ru.unclerus.library.R;
import ru.unclerus.library.cover.ThumbCache;
import ru.unclerus.library.db.BookDao;
import ru.unclerus.library.db.Schema;
import ru.unclerus.library.fmt.BookFormat;
import ru.unclerus.library.util.Text;

/**
 * Адаптер строк книг поверх оконного курсора {@link BookDao#COLS_SQL}.
 * В памяти — только текущая страница (курсор {@code LIMIT/OFFSET}), поэтому
 * адаптер дешёв даже на 100k книг. Возвращает id элемента как stable id.
 *
 * <p>Строки страницы приходят как {@link RowList} — она чередует заголовки
 * разделов (буквы названий, даты, история) и элементы.
 */
public final class BookRowAdapter extends BaseAdapter {

    private final LayoutInflater mInf;
    private final Context mCtx;
    private final Cursor mCursor;
    private final RowList mRows;
    private final int mLayout;
    /** Запрос поиска для подсветки совпадений; {@code null} вне режима поиска. */
    private final String mQuery;
    /** Показывать ли обложки (настройка S4.6). */
    private final boolean mShowCovers;
    /** Дёргается по готовности миниатюры — перерисовывает окно строк. */
    private final Runnable mCoverListener;

    public BookRowAdapter(Context ctx, Cursor cursor) {
        this(ctx, cursor, null, null);
    }

    public BookRowAdapter(Context ctx, Cursor cursor, RowList rows) {
        this(ctx, cursor, rows, null);
    }

    public BookRowAdapter(Context ctx, Cursor cursor, RowList rows, String query) {
        mCtx = ctx.getApplicationContext();
        mInf = LayoutInflater.from(ctx);
        mCursor = cursor;
        mRows = rows;
        mQuery = query;
        mShowCovers = App.prefs() == null || App.prefs().showCovers();
        mLayout = rowLayout(ctx);
        mCoverListener = new Runnable() {
            @Override
            public void run() {
                try {
                    notifyDataSetChanged();
                } catch (Throwable ignored) {
                    // окно могло быть уже снято — перерисовывать нечего
                }
            }
        };
    }

    /**
     * Разметка строки книги по текущей плотности списка (S4.4): «компактный» —
     * уменьшенная обложка и отбивки, «обычный» — крупная. Один вход для всех
     * списков книг (вкладка «Все книги», дата, история, книги группы, поиск), а
     * также для {@link ListMetrics}, чтобы размер страницы считался по той же
     * разметке, что и рисуется.
     */
    public static int rowLayout(Context ctx) {
        return App.prefs() != null && App.prefs().isCompact()
                ? R.layout.row_book_compact : R.layout.row_book;
    }

    @Override
    public int getCount() {
        if (mRows != null) {
            return mRows.size();
        }
        return mCursor == null ? 0 : mCursor.getCount();
    }

    @Override
    public Object getItem(int position) {
        return null;
    }

    @Override
    public long getItemId(int position) {
        if (isHeader(position)) {
            return -1 - position;
        }
        if (!move(position)) {
            return 0;
        }
        return mCursor.getLong(BookDao.C_ID);
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public int getViewTypeCount() {
        return 2; // 0 — заголовок раздела, 1 — строка книги
    }

    @Override
    public int getItemViewType(int position) {
        return isHeader(position) ? 0 : 1;
    }

    private boolean isHeader(int position) {
        return mRows != null && mRows.isHeader(position);
    }

    private boolean move(int position) {
        if (mCursor == null) {
            return false;
        }
        int pos = mRows != null ? mRows.position(position) : position;
        return mCursor.moveToPosition(pos);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (isHeader(position)) {
            TextView head = (TextView) convertView;
            if (head == null) {
                head = (TextView) mInf.inflate(R.layout.row_section, parent, false);
            }
            head.setText(mRows.text(position));
            return head;
        }
        View row = convertView;
        if (row == null) {
            row = mInf.inflate(mLayout, parent, false);
        }
        if (!move(position)) {
            return row;
        }

        String title = mCursor.getString(BookDao.C_TITLE);
        String authors = mCursor.getString(BookDao.C_AUTHORS);
        String series = mCursor.getString(BookDao.C_SERIES);
        String format = mCursor.getString(BookDao.C_FORMAT);
        long size = mCursor.getLong(BookDao.C_FILE_SIZE);
        boolean missing = !Schema.AVAIL_OK.equals(mCursor.getString(BookDao.C_AVAILABILITY));

        long id = mCursor.getLong(BookDao.C_ID);
        String coverEntry = mCursor.getString(BookDao.C_COVER_ENTRY);
        String path = mCursor.getString(BookDao.C_PATH);

        setText(row, R.id.book_letter, firstLetter(title));
        bindCover(row, id, path, format, coverEntry, missing);
        setText(row, R.id.book_title, highlight(title, mQuery));

        TextView sub = row.findViewById(R.id.book_sub);
        if (!Text.isBlank(authors)) {
            sub.setText(highlight(authors, mQuery));
            sub.setVisibility(View.VISIBLE);
        } else {
            sub.setVisibility(View.GONE);
        }

        String meta = human(size);
        if (!Text.isBlank(series)) {
            meta = series + " · " + meta;
        }
        String label = BookFormat.label(format);
        if (!Text.isBlank(label)) {
            meta = label + " · " + meta;
        }
        setText(row, R.id.book_meta, highlight(meta, mQuery));

        // Недоступная книга — приглушена (файла нет на месте).
        row.setAlpha(missing ? 0.45f : 1f);
        return row;
    }

    private static void setText(View row, int id, CharSequence s) {
        TextView t = row.findViewById(id);
        if (t != null) {
            t.setText(s);
        }
    }

    /**
     * Обложка-миниатюра строки (V1/V12). Живую картинку берём из памяти; при
     * промахе показываем плейсхолдер-букву и просим {@link ThumbCache}
     * подгрузить её — по готовности окно строк перерисуется. Для книг, у
     * которых обложки нет вовсе ({@code cover_entry} пуст либо кэш уже знает
     * об отсутствии), никакого чтения файла не происходит.
     */
    private void bindCover(View row, long bookId, String path, String format,
                           String coverEntry, boolean missing) {
        ImageView img = row.findViewById(R.id.book_cover);
        TextView letter = row.findViewById(R.id.book_letter);
        View box = row.findViewById(R.id.book_cover_box);
        if (img == null || letter == null) {
            return;
        }
        boolean want = mShowCovers && !missing && bookId > 0
                && !Text.isBlank(coverEntry) && !Text.isBlank(path);
        if (!want) {
            showLetter(img, letter, box);
            return;
        }
        Bitmap bmp = ThumbCache.peek(bookId);
        if (bmp != null) {
            showCover(img, letter, box, bmp);
            return;
        }
        showLetter(img, letter, box);
        if (ThumbCache.hasNoCover(bookId)) {
            return;
        }
        ThumbCache.request(mCtx, bookId, path, format, coverEntry, mCoverListener);
    }

    private static void showCover(ImageView img, TextView letter, View box, Bitmap bmp) {
        img.setImageBitmap(bmp);
        img.setVisibility(View.VISIBLE);
        letter.setVisibility(View.GONE);
        if (box != null) {
            box.setBackground(null); // картинка закрывает рамку целиком
        }
    }

    private static void showLetter(ImageView img, TextView letter, View box) {
        img.setImageDrawable(null);
        img.setVisibility(View.GONE);
        letter.setVisibility(View.VISIBLE);
        if (box != null) {
            box.setBackgroundResource(R.drawable.bg_letter);
        }
    }

    /**
     * Подсветка совпадений поиска (макет 09): каждое слово запроса, найденное в
     * тексте без учёта регистра, помечается чёрным фоном с белым текстом.
     * Возвращает исходную строку, если совпадений нет (чтобы не плодить
     * {@link SpannableString} зря).
     */
    static CharSequence highlight(String text, String query) {
        if (text == null) {
            return "";
        }
        if (query == null || query.isEmpty()) {
            return text;
        }
        SpannableString span = new SpannableString(text);
        boolean any = false;
        int n = query.length();
        int i = 0;
        while (i < n) {
            if (!Character.isLetterOrDigit(query.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && Character.isLetterOrDigit(query.charAt(j))) {
                j++;
            }
            String tok = query.substring(i, j);
            int from = 0;
            while (true) {
                int k = indexOfIgnoreCase(text, tok, from);
                if (k < 0) {
                    break;
                }
                span.setSpan(new BackgroundColorSpan(Color.BLACK), k, k + tok.length(),
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                span.setSpan(new ForegroundColorSpan(Color.WHITE), k, k + tok.length(),
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                any = true;
                from = k + tok.length();
            }
            i = j;
        }
        return any ? span : text;
    }

    /** {@code indexOf} без учёта регистра (кириллицу {@code regionMatches} умеет). */
    private static int indexOfIgnoreCase(String hay, String needle, int from) {
        int limit = hay.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (hay.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /** Буква для плейсхолдера: первая буква/цифра, иначе «#». */
    private static String firstLetter(String s) {
        if (s == null) {
            return "#";
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "#";
    }

    /** Человекочитаемый размер, языково-нейтральные единицы. */
    static String human(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.1f GB", mb / 1024.0);
    }
}
