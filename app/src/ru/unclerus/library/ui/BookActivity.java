package ru.unclerus.library.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.R;
import ru.unclerus.library.cover.ThumbCache;
import ru.unclerus.library.db.BookDao;
import ru.unclerus.library.db.Cleanup;
import ru.unclerus.library.db.Schema;
import ru.unclerus.library.fmt.BookFormat;
import ru.unclerus.library.open.BookActions;
import ru.unclerus.library.util.BookFiles;
import ru.unclerus.library.util.Dialogs;
import ru.unclerus.library.util.Lang;
import ru.unclerus.library.util.Text;

/**
 * S3 «Карточка книги» (UI.md §2, макет 11) и оверлей S8 «Обложка» (макет 24).
 *
 * <p>Крупная обложка (160×240 dp) подгружается лениво через {@link ThumbCache};
 * пока её нет — плейсхолдер-буква. Тап по обложке открывает полноэкранный
 * просмотр (S8), закрытие — тап или системная «Назад».
 *
 * <p>D11 «Удалить файл»: три действия — «Отмена», «Только из каталога»
 * ({@link Cleanup#deleteBook}) и «Удалить файл» (файл + запись). Миниатюра при
 * удалении убирается из кэша.
 *
 * <p>Запуск книги — через {@link BookActions} (M7): «Открыть» (в читалке по
 * умолчанию) и «Открыть в…» (D1). Сама карточка книг не читает.
 */
public final class BookActivity extends ThemedActivity {

    /** Идентификатор книги в БД (передаётся из списков). */
    public static final String EXTRA_BOOK_ID = "book_id";

    private long mBookId;
    private String mTitle = "";
    private String mPath;
    private String mFormat;
    private String mCoverEntry;
    private String mAnnotation = "";
    private long mFileSize;
    private long mOpenedAt;
    private boolean mMissing;
    private boolean mShowCovers = true;

    private View mCoverBox;
    private ImageView mCover;
    private TextView mCoverLetter;
    private View mOverlay;
    private ImageView mCoverBig;
    private TextView mCoverBigLetter;
    private Button mOpen;
    private Button mOpenWith;

    private final Runnable mCoverLoaded = new Runnable() {
        @Override
        public void run() {
            applyCover(ThumbCache.peek(mBookId));
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_book);

        mBookId = getIntent().getLongExtra(EXTRA_BOOK_ID, 0);
        mShowCovers = App.prefs() == null || App.prefs().showCovers();

        mCoverBox = findViewById(R.id.book_cover_box);
        mCover = findViewById(R.id.book_cover);
        mCoverLetter = findViewById(R.id.book_cover_letter);
        mOverlay = findViewById(R.id.cover_overlay);
        mCoverBig = findViewById(R.id.cover_big);
        mCoverBigLetter = findViewById(R.id.cover_big_letter);
        mOpen = findViewById(R.id.book_open);
        mOpenWith = findViewById(R.id.book_open_with);

        findViewById(R.id.book_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mCoverBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCoverOverlay();
            }
        });
        mOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideCoverOverlay();
            }
        });
        findViewById(R.id.cover_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideCoverOverlay();
            }
        });
        mOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BookActions.openDefault(BookActivity.this, mBookId, mTitle, mPath, mFormat);
            }
        });
        mOpenWith.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BookActions.openWith(BookActivity.this, mBookId, mPath, mFormat);
            }
        });
        findViewById(R.id.book_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDeleteDialog();
            }
        });

        load();
    }

    @Override
    public void onBackPressed() {
        if (mOverlay.getVisibility() == View.VISIBLE) {
            hideCoverOverlay();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // Данные
    // ------------------------------------------------------------------

    private void load() {
        SQLiteDatabase db = App.db().getReadableDatabase();
        Cursor c = null;
        try {
            c = BookDao.one(db, mBookId);
            if (!c.moveToFirst()) {
                Logger.w("Book", "книга id=" + mBookId + " не найдена");
                finish();
                return;
            }
            mTitle = value(c, BookDao.C_TITLE);
            mPath = value(c, BookDao.C_PATH);
            mFormat = value(c, BookDao.C_FORMAT);
            mCoverEntry = value(c, BookDao.C_COVER_ENTRY);
            mAnnotation = value(c, BookDao.C_ANNOTATION);
            mFileSize = c.getLong(BookDao.C_FILE_SIZE);
            mOpenedAt = c.getLong(BookDao.C_OPENED_AT);
            mMissing = !Schema.AVAIL_OK.equals(value(c, BookDao.C_AVAILABILITY));
        } catch (Throwable t) {
            Logger.e("Book", "не удалось прочитать карточку id=" + mBookId, t);
            finish();
            return;
        } finally {
            if (c != null) {
                c.close();
            }
        }
        fill(db);
    }

    private void fill(SQLiteDatabase db) {
        ((TextView) findViewById(R.id.book_name)).setText(Text.isBlank(mTitle) ? mPath : mTitle);

        // Классификаторы — подчёркнутые ссылки: тап открывает список группы
        // (авторы/серии/жанры), тот же экран, что и из вкладок.
        SpannableStringBuilder authors = links(db, BookDao.authorsOf(db, mBookId),
                GroupBooksActivity.KIND_AUTHOR, true, false);
        bindLinks(R.id.book_author, authors);
        bindLinks(R.id.row_authors, R.id.card_authors_val, authors);
        bindLinks(R.id.row_series, R.id.card_series_val,
                links(db, BookDao.seriesOf(db, mBookId),
                        GroupBooksActivity.KIND_SERIES, false, true));
        bindLinks(R.id.row_genres, R.id.card_genres_val,
                links(db, BookDao.genresOf(db, mBookId, Lang.english()),
                        GroupBooksActivity.KIND_GENRE, false, false));
        kv(R.id.row_format, R.id.card_format_val, BookFormat.label(mFormat));
        kv(R.id.row_size, R.id.card_size_val, BookRowAdapter.human(mFileSize));
        kv(R.id.row_opened, R.id.card_opened_val, openedText());
        kv(R.id.row_path, R.id.card_path_val, mPath);

        View annotBlock = findViewById(R.id.book_annot_block);
        TextView annot = findViewById(R.id.book_annot);
        if (Text.isBlank(mAnnotation)) {
            annotBlock.setVisibility(View.GONE);
        } else {
            annot.setText(mAnnotation);
            annotBlock.setVisibility(View.VISIBLE);
        }

        // Недоступная книга: открыть нельзя — кнопки гаснут (e-ink: без анимаций).
        mOpen.setEnabled(!mMissing);
        mOpenWith.setEnabled(!mMissing);
        mOpen.setAlpha(mMissing ? 0.4f : 1f);
        mOpenWith.setAlpha(mMissing ? 0.4f : 1f);

        loadCover();
    }

    private String openedText() {
        if (mOpenedAt <= 0) {
            return "";
        }
        return DateFormat.getDateFormat(this).format(new Date(mOpenedAt));
    }

    /**
     * Классификаторы книги одной категории в виде подчёркнутых ссылок.
     * Курсор: {@code id, name[, number]}. Авторы — с форматом имени S4.5,
     * серии — с «· №N»; заголовок списка (тап) — имя без номера.
     */
    private SpannableStringBuilder links(SQLiteDatabase db, Cursor c, String kind,
                                         boolean authors, boolean series) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        try {
            boolean lastFirst = authors && App.prefs() != null && App.prefs().isLastFirst();
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                if (Text.isBlank(name)) {
                    continue;
                }
                String title = authors ? Text.formatAuthor(name, lastFirst) : name;
                String label = title;
                if (series) {
                    String num = c.isNull(2) ? "" : c.getString(2);
                    if (!Text.isBlank(num)) {
                        label = label + " · №" + num.trim();
                    }
                }
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                int start = sb.length();
                sb.append(label);
                sb.setSpan(new ClassifierSpan(kind, id, title), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } finally {
            c.close();
        }
        return sb;
    }

    /** Привязать ссылки к одному TextView (шапка карточки). */
    private void bindLinks(int textId, SpannableStringBuilder sb) {
        TextView tv = findViewById(textId);
        if (sb.length() == 0) {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setText(sb);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setVisibility(View.VISIBLE);
    }

    /** Привязать ссылки к строке-характеристике (метка + значение). */
    private void bindLinks(int rowId, int textId, SpannableStringBuilder sb) {
        View row = findViewById(rowId);
        if (sb.length() == 0) {
            row.setVisibility(View.GONE);
            return;
        }
        bindLinks(textId, sb);
        row.setVisibility(View.VISIBLE);
    }

    /** Ссылка-классификатор в карточке: тап открывает список автора/серии/жанра. */
    private final class ClassifierSpan extends ClickableSpan {

        private final String mKind;
        private final long mId;
        private final String mTitle;

        ClassifierSpan(String kind, long id, String title) {
            mKind = kind;
            mId = id;
            mTitle = title;
        }

        @Override
        public void onClick(View widget) {
            startActivity(new Intent(BookActivity.this, GroupBooksActivity.class)
                    .putExtra(GroupBooksActivity.EXTRA_KIND, mKind)
                    .putExtra(GroupBooksActivity.EXTRA_ID, mId)
                    .putExtra(GroupBooksActivity.EXTRA_TITLE, mTitle)
                    .putExtra(GroupBooksActivity.EXTRA_FROM, 0L)
                    .putExtra(GroupBooksActivity.EXTRA_TO, 0L));
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            // Цвет — как у текста строки (чёрный/серый), подчёркивание — признак ссылки.
            ds.setUnderlineText(true);
        }
    }

    private void kv(int rowId, int valId, String value) {
        View row = findViewById(rowId);
        TextView val = findViewById(valId);
        if (Text.isBlank(value)) {
            row.setVisibility(View.GONE);
        } else {
            val.setText(value);
            row.setVisibility(View.VISIBLE);
        }
    }

    private static String value(Cursor c, int col) {
        String s = c.isNull(col) ? "" : c.getString(col);
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // Обложка (S3 + S8)
    // ------------------------------------------------------------------

    private void loadCover() {
        if (!mShowCovers || mMissing || Text.isBlank(mCoverEntry) || Text.isBlank(mPath)) {
            applyCover(null);
            return;
        }
        Bitmap bmp = ThumbCache.peek(mBookId);
        if (bmp != null) {
            applyCover(bmp);
            return;
        }
        applyCover(null);
        if (!ThumbCache.hasNoCover(mBookId)) {
            ThumbCache.request(this, mBookId, mPath, mFormat, mCoverEntry, mCoverLoaded);
        }
    }

    /** Показать обложку (или плейсхолдер-букву, если {@code bmp == null}). */
    private void applyCover(Bitmap bmp) {
        String letter = Text.firstLetter(mTitle);
        if (bmp != null) {
            mCover.setImageBitmap(bmp);
            mCover.setVisibility(View.VISIBLE);
            mCoverLetter.setVisibility(View.GONE);
            mCoverBox.setBackground(null);

            mCoverBig.setImageBitmap(bmp);
            mCoverBig.setVisibility(View.VISIBLE);
            mCoverBigLetter.setVisibility(View.GONE);
        } else {
            mCover.setImageDrawable(null);
            mCover.setVisibility(View.GONE);
            mCoverLetter.setText(letter);
            mCoverLetter.setVisibility(View.VISIBLE);
            mCoverBox.setBackgroundResource(R.drawable.bg_letter);

            mCoverBig.setImageDrawable(null);
            mCoverBig.setVisibility(View.GONE);
            mCoverBigLetter.setText(letter);
            mCoverBigLetter.setVisibility(View.VISIBLE);
        }
    }

    private void showCoverOverlay() {
        // Если обложка ещё не загрузилась — попробуем догрузить к моменту показа.
        if (mShowCovers && !mMissing && !Text.isBlank(mCoverEntry)
                && ThumbCache.peek(mBookId) == null) {
            ThumbCache.request(this, mBookId, mPath, mFormat, mCoverEntry, mCoverLoaded);
        }
        mOverlay.setVisibility(View.VISIBLE);
    }

    private void hideCoverOverlay() {
        mOverlay.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------
    // История открытий (FR-5)
    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        refreshOpened();
    }

    /**
     * Перечитать дату последнего открытия: её пишет {@link BookActions} при
     * запуске читалки, а карточка может остаться в бэкстеке.
     */
    private void refreshOpened() {
        long at = 0;
        Cursor c = null;
        try {
            c = BookDao.one(App.db().getReadableDatabase(), mBookId);
            if (c.moveToFirst()) {
                at = c.getLong(BookDao.C_OPENED_AT);
            }
        } catch (Throwable t) {
            Logger.e("Book", "не удалось перечитать историю id=" + mBookId, t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (at != mOpenedAt) {
            mOpenedAt = at;
            kv(R.id.row_opened, R.id.card_opened_val, openedText());
        }
    }

    // ------------------------------------------------------------------
    // D11 «Удалить файл»
    // ------------------------------------------------------------------

    private void showDeleteDialog() {
        final View body = getLayoutInflater().inflate(R.layout.dlg_delete_file, null);
        ((TextView) body.findViewById(R.id.del_title)).setText(mTitle);
        String authors = "";
        Cursor c = BookDao.authorsOf(App.db().getReadableDatabase(), mBookId);
        try {
            StringBuilder sb = new StringBuilder();
            boolean lastFirst = App.prefs() != null && App.prefs().isLastFirst();
            while (c.moveToNext()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(Text.formatAuthor(c.getString(1), lastFirst));
            }
            authors = sb.toString();
        } finally {
            c.close();
        }
        TextView authorsView = body.findViewById(R.id.del_authors);
        if (Text.isBlank(authors)) {
            authorsView.setVisibility(View.GONE);
        } else {
            authorsView.setText(authors);
        }
        line(body, R.id.del_file, R.string.dlg_delete_file_label, BookFiles.name(mPath));
        line(body, R.id.del_path, R.string.dlg_delete_path_label, mPath);
        line(body, R.id.del_size, R.string.dlg_delete_size_label,
                BookRowAdapter.human(mFileSize));
        line(body, R.id.del_opened, R.string.dlg_delete_opened_label, openedText());
        ((TextView) body.findViewById(R.id.del_warn)).setText(R.string.dlg_delete_warning);

        Dialogs.show(new AlertDialog.Builder(this, R.style.AppTheme_Dialog)
                .setTitle(R.string.dlg_delete_file)
                .setView(body)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.action_delete_from_catalog,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                removeFromCatalog();
                            }
                        })
                .setPositiveButton(R.string.action_delete_confirm,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                deleteFileAndRecord();
                            }
                        }));
    }

    private void line(View body, int id, int labelRes, String value) {
        ((TextView) body.findViewById(id)).setText(
                getString(R.string.label_value_format, getString(labelRes),
                        Text.isBlank(value) ? "—" : value));
    }

    /** «Только из каталога»: запись и связи, файл на диске остаётся. */
    private void removeFromCatalog() {
        if (Cleanup.deleteBook(App.db().getWritableDatabase(), mBookId)) {
            ThumbCache.remove(this, mBookId);
            toast(R.string.toast_file_deleted);
            finish();
        } else {
            toast(R.string.toast_file_delete_failed);
        }
    }

    /** «Удалить файл»: сначала файл, затем запись. */
    private void deleteFileAndRecord() {
        boolean ok = BookFiles.delete(this, mPath);
        if (!ok) {
            Logger.w("Book", "не удалось удалить файл: " + mPath);
            toast(R.string.toast_file_delete_failed);
            return;
        }
        if (Cleanup.deleteBook(App.db().getWritableDatabase(), mBookId)) {
            ThumbCache.remove(this, mBookId);
            toast(R.string.toast_file_deleted);
            finish();
        } else {
            toast(R.string.toast_file_delete_failed);
        }
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }
}
