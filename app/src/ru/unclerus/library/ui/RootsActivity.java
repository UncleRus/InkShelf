package ru.unclerus.library.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.R;
import ru.unclerus.library.db.Cleanup;
import ru.unclerus.library.db.Schema;
import ru.unclerus.library.scan.RootRepo;
import ru.unclerus.library.util.Dialogs;

/**
 * S9 «Папки с книгами» (макет 29). Список корней библиотеки (V9): путь,
 * тип/состояние, корзина удаляет папку вместе с книгами каталога (D9).
 * Добавление папки открывает S5 ({@link FolderBrowserActivity}).
 *
 * <p>Выключения папки нет: она либо в каталоге (и сканируется), либо удаляется
 * корзиной. Чтение и запись БД — в фоновом потоке ({@link #mBg}), отрисовка
 * списка — на главном ({@link #mUi}). Файлы на диске экран не трогает: удаление
 * корня убирает только записи каталога.
 */
public final class RootsActivity extends ThemedActivity {

    /** Код запроса S5 «Выбор папки-корня». */
    private static final int REQ_ADD = 1;

    private LinearLayout mList;
    private TextView mEmpty;
    private ScrollView mScroll;

    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private final Handler mUi = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_roots);

        mList = findViewById(R.id.root_list);
        mEmpty = findViewById(R.id.root_empty);
        mScroll = findViewById(R.id.root_scroll);

        findViewById(R.id.root_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.root_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(RootsActivity.this,
                        FolderBrowserActivity.class), REQ_ADD);
            }
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload(); // список мог измениться после добавления/удаления папки
    }

    @Override
    protected void onDestroy() {
        mBg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD && resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.toast_root_added, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Чтение и отрисовка
    // ------------------------------------------------------------------

    /** Перечитать корни из БД в фоне и перестроить список на главном потоке. */
    private void reload() {
        mBg.execute(new Runnable() {
            @Override
            public void run() {
                final List<Item> items = new ArrayList<>();
                try {
                    SQLiteDatabase db = App.db().getReadableDatabase();
                    Cursor c = RootRepo.all(db);
                    try {
                        while (c.moveToNext()) {
                            Item it = new Item();
                            it.id = c.getLong(0);
                            it.path = c.getString(1);
                            it.kind = c.getString(3);
                            it.lastScanAt = c.getLong(5);
                            it.volume = c.getString(6);
                            it.perm = c.getString(7);
                            it.books = bookCount(db, it.id);
                            boolean noAccess = RootRepo.VOL_NO_ACCESS.equals(it.volume)
                                    || RootRepo.PERM_DENIED.equals(it.perm);
                            boolean missing = RootRepo.VOL_MISSING.equals(it.volume);
                            it.warn = noAccess || missing;
                            items.add(it);
                        }
                    } finally {
                        c.close();
                    }
                } catch (Throwable t) {
                    Logger.e("Roots", "не удалось прочитать список корней", t);
                }
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        render(items);
                    }
                });
            }
        });
    }

    /** Число книг корня (для подписи строки и текста D9). */
    private static int bookCount(SQLiteDatabase db, long rootId) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + Schema.T_BOOK + " WHERE root_id=?",
                new String[]{String.valueOf(rootId)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Построить строки V9. Пустой список — показать подсказку вместо списка. */
    private void render(List<Item> items) {
        mList.removeAllViews();
        if (items.isEmpty()) {
            mEmpty.setVisibility(View.VISIBLE);
            mList.setVisibility(View.GONE);
            mScroll.setVisibility(View.GONE);
            return;
        }
        mEmpty.setVisibility(View.GONE);
        mList.setVisibility(View.VISIBLE);
        mScroll.setVisibility(View.VISIBLE);

        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        for (int i = 0; i < items.size(); i++) {
            final Item item = items.get(i);
            View row = getLayoutInflater().inflate(R.layout.row_root, mList, false);

            ((TextView) row.findViewById(R.id.root_path)).setText(item.path);
            ((TextView) row.findViewById(R.id.root_sub)).setText(subtitle(item, fmt));

            ImageView icon = row.findViewById(R.id.root_icon);
            icon.setImageResource(item.warn ? R.drawable.ic_alert : R.drawable.ic_folder);
            icon.setImageTintList(getColorStateList(item.warn
                    ? R.color.disabled_ink : R.color.icon));

            row.findViewById(R.id.root_trash).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(item);
                }
            });

            mList.addView(row);
        }
    }

    /** Подпись строки: тип · книги · [состояние] · время скана. */
    private String subtitle(Item it, SimpleDateFormat fmt) {
        StringBuilder b = new StringBuilder();
        b.append(RootRepo.KIND_FILE.equals(it.kind) ? getString(R.string.root_kind_file) : "SAF");
        b.append(" · ").append(it.books).append(" ").append(getResources().getQuantityString(
                R.plurals.books_count, it.books));
        String st = state(it);
        if (st != null) {
            b.append(" · ").append(st);
        }
        b.append(" · ").append(it.lastScanAt > 0
                ? getString(R.string.root_scanned, fmt.format(new Date(it.lastScanAt)))
                : getString(R.string.root_never_scanned));
        return b.toString();
    }

    /** Предупреждение о состоянии корня или {@code null}, если всё в порядке. */
    private String state(Item it) {
        if (RootRepo.VOL_NO_ACCESS.equals(it.volume) || RootRepo.PERM_DENIED.equals(it.perm)) {
            return getString(R.string.root_state_no_access);
        }
        if (RootRepo.VOL_MISSING.equals(it.volume)) {
            return getString(R.string.root_state_volume_off);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Действия
    // ------------------------------------------------------------------

    /** D9: подтверждение удаления корня вместе с книгами каталога. */
    private void confirmDelete(final Item item) {
        String warn = getString(R.string.dlg_delete_root_warn, item.path,
                item.books + " " + getResources().getQuantityString(R.plurals.books_count, item.books));
        Dialogs.show(new AlertDialog.Builder(this, R.style.AppTheme_Dialog)
                .setTitle(R.string.dlg_delete_root)
                .setMessage(warn + "\n\n" + getString(R.string.dlg_delete_root_hint))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete_root, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        deleteRoot(item.id);
                    }
                }));
    }

    /** Удалить корень и его книги из каталога (файлы на диске не трогаются). */
    private void deleteRoot(final long id) {
        mBg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteDatabase db = App.db().getWritableDatabase();
                    int books = Cleanup.deleteRootWithBooks(db, id);
                    Logger.i("Roots", "удалён корень id=" + id + ", книг: " + books);
                } catch (Throwable t) {
                    Logger.e("Roots", "не удалось удалить корень id=" + id, t);
                }
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        reload();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------

    /** Строка корня, подготовленная в фоне и отрисованная на главном потоке. */
    private static final class Item {
        long id;
        String path;
        String kind;
        long lastScanAt;
        String volume;
        String perm;
        int books;
        boolean warn;
    }
}
