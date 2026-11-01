package ru.unclerus.library.open;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import ru.unclerus.library.App;
import ru.unclerus.library.R;
import ru.unclerus.library.util.Dialogs;
import ru.unclerus.library.util.Prefs;

/**
 * Диалоги выбора читалки:
 * <ul>
 *   <li><b>D1</b> «Открыть в…» (макет 16) — список читалок и «Открыть в проводнике»;</li>
 *   <li><b>D1·b</b> «Читалка по умолчанию» (макет 25) — разовый выбор при первом запуске;</li>
 *   <li><b>D5</b> «Не удалось открыть книгу» (макет 26);</li>
 *   <li><b>D10</b> «Нет читалок» (макет 27).</li>
 * </ul>
 * Только анонимные классы — лямбды в проекте запрещены.
 */
public final class OpenWithDialog {

    /** Что сделать после выбора читалки в списке. */
    public interface OnPick {
        void picked(Activity a, String pkg);
    }

    private OpenWithDialog() {}

    /** D1: открыть конкретную книгу в выбранной читалке (или в проводнике). */
    public static void showWith(final Activity a, final long bookId, final String path,
                                final String format) {
        List<ReaderInfo> readers = ReaderRegistry.installed(a);
        if (readers.isEmpty()) {
            showNoReaders(a);
            return;
        }
        show(a, readers, R.string.dlg_open_with, path, new OnPick() {
            @Override
            public void picked(Activity act, String pkg) {
                BookActions.launch(act, bookId, path, format, pkg);
            }
        });
    }

    /**
     * D1·b: разовый выбор читалки по умолчанию (при первом запуске).
     *
     * @param openAfter открыть книгу ({@code bookId}/{@code path}) сразу после выбора;
     *                  {@code false} — только запомнить читалку
     */
    public static void showDefault(final Activity a, final long bookId, final String path,
                                   final String format, final boolean openAfter) {
        List<ReaderInfo> readers = ReaderRegistry.installed(a);
        if (readers.isEmpty()) {
            showNoReaders(a);
            return;
        }
        show(a, readers, R.string.dlg_default_reader, null, new OnPick() {
            @Override
            public void picked(Activity act, String pkg) {
                Prefs p = App.prefs();
                if (p != null) {
                    p.setDefaultReader(pkg);
                    p.setReaderAsked(true);
                }
                if (openAfter) {
                    BookActions.launch(act, bookId, path, format, pkg);
                }
            }
        });
    }

    /**
     * S4.2: смена читалки по умолчанию из настроек. После выбора вызывается
     * {@code after} (например, чтобы перестроить список настроек).
     */
    public static void showDefaultSetting(final Activity a, final Runnable after) {
        List<ReaderInfo> readers = ReaderRegistry.installed(a);
        if (readers.isEmpty()) {
            showNoReaders(a);
            return;
        }
        show(a, readers, R.string.dlg_default_reader, null, new OnPick() {
            @Override
            public void picked(Activity act, String pkg) {
                Prefs p = App.prefs();
                if (p != null) {
                    p.setDefaultReader(pkg);
                    p.setReaderAsked(true);
                }
                if (after != null) {
                    after.run();
                }
            }
        });
    }

    /** D10: на устройстве нет ни одной читалки. */
    public static void showNoReaders(Activity a) {
        Dialogs.show(new AlertDialog.Builder(a, R.style.AppTheme_Dialog)
                .setTitle(R.string.dlg_no_readers)
                .setMessage(R.string.dlg_no_readers_text)
                .setPositiveButton(R.string.action_ok, null));
    }

    /** D5: читалка не запустилась — причина и переход к выбору другой. */
    public static void showOpenError(final Activity a, final long bookId, final String path,
                                     final String format, final String pkg) {
        ReaderInfo ri = ReaderRegistry.byPackage(a, pkg);
        String label = ri != null ? ri.label : pkg;
        Dialogs.show(new AlertDialog.Builder(a, R.style.AppTheme_Dialog)
                .setTitle(R.string.dlg_open_error)
                .setMessage(a.getString(R.string.dlg_open_error_reader, label))
                .setPositiveButton(R.string.action_choose_reader,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                showWith(a, bookId, path, format);
                            }
                        })
                .setNegativeButton(R.string.action_cancel, null));
    }

    // ------------------------------------------------------------------

    /**
     * Общий построитель списка читалок.
     *
     * @param folderPath путь книги для строки «Открыть в проводнике»
     *                   ({@code null} — строку не показывать)
     */
    private static void show(final Activity a, List<ReaderInfo> readers, int titleRes,
                             final String folderPath, final OnPick onPick) {
        String def = App.prefs() == null ? "" : App.prefs().defaultReader();
        View body = LayoutInflater.from(a).inflate(R.layout.dlg_open_with, null);
        if (titleRes == R.string.dlg_default_reader) {
            TextView hint = body.findViewById(R.id.dlg_hint);
            hint.setText(R.string.dlg_default_reader_hint);
            hint.setVisibility(View.VISIBLE);
        }
        final AlertDialog dialog = new AlertDialog.Builder(a, R.style.AppTheme_Dialog)
                .setTitle(titleRes)
                .setView(body)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        LinearLayout items = body.findViewById(R.id.dlg_items);

        for (ReaderInfo r : readers) {
            View row = LayoutInflater.from(a).inflate(R.layout.row_reader, items, false);
            ((ImageView) row.findViewById(R.id.reader_icon)).setImageDrawable(r.icon);
            ((TextView) row.findViewById(R.id.reader_name)).setText(r.label);
            boolean isDefault = r.pkg.equals(def);
            row.findViewById(R.id.reader_default)
                    .setVisibility(isDefault ? View.VISIBLE : View.GONE);
            ((ImageView) row.findViewById(R.id.reader_radio)).setImageResource(
                    isDefault ? R.drawable.ic_radio_on : R.drawable.ic_radio_off);
            final String pkg = r.pkg;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (onPick != null) {
                        onPick.picked(a, pkg);
                    }
                }
            });
            items.addView(row);
        }

        if (folderPath != null) {
            View row = LayoutInflater.from(a).inflate(R.layout.row_menu, items, false);
            ((ImageView) row.findViewById(R.id.menu_icon)).setImageResource(R.drawable.ic_folder);
            ((TextView) row.findViewById(R.id.menu_label))
                    .setText(R.string.action_open_in_file_manager);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    BookActions.showInFolder(a, folderPath);
                }
            });
            items.addView(row);
        }

        Dialogs.show(dialog);
    }
}
