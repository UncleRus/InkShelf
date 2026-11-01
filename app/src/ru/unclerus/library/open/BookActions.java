package ru.unclerus.library.open;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

import ru.unclerus.library.App;
import ru.unclerus.library.R;
import ru.unclerus.library.fmt.BookFormat;
import ru.unclerus.library.ui.BookActivity;
import ru.unclerus.library.util.BookFiles;
import ru.unclerus.library.util.Dialogs;
import ru.unclerus.library.util.Prefs;
import ru.unclerus.library.util.Text;

/**
 * Действия над книгой из списков и карточки: «Открыть», «Открыть в…», контекстное
 * меню D6 (макет 28) и «Показать файл в папке». Единая точка входа для
 * {@code MainActivity}, {@code GroupBooksActivity} и {@code BookActivity}.
 */
public final class BookActions {

    private BookActions() {}

    /**
     * «Открыть»: книга в читалке по умолчанию (FR-5, приёмочный №11). Если
     * читалка ещё не выбрана, при первом запуске показываем D1·b, иначе берём
     * первую установленную.
     */
    public static void openDefault(Activity a, long bookId, String title, String path,
                                   String format) {
        List<ReaderInfo> readers = ReaderRegistry.installed(a);
        if (readers.isEmpty()) {
            OpenWithDialog.showNoReaders(a);
            return;
        }
        Prefs p = App.prefs();
        String pkg = p == null ? "" : p.defaultReader();
        ReaderInfo ri = ReaderRegistry.byPackage(a, pkg);
        if (ri == null) {
            if (p != null && !p.readerAsked()) {
                OpenWithDialog.showDefault(a, bookId, path, format, true);
                return;
            }
            ri = readers.get(0); // читалка не выбрана или удалена — берём первую
        }
        launch(a, bookId, path, format, ri.pkg);
    }

    /** «Открыть в…»: диалог D1 со списком читалок. */
    public static void openWith(Activity a, long bookId, String path, String format) {
        OpenWithDialog.showWith(a, bookId, path, format);
    }

    /**
     * Запустить книгу в читалке {@code pkg}; при неудаче — D5 «Не удалось
     * открыть книгу» с переходом к выбору другой читалки.
     */
    public static void launch(Activity a, long bookId, String path, String format, String pkg) {
        if (!BookOpener.open(a, bookId, path, mimeOf(path, format), pkg)) {
            OpenWithDialog.showOpenError(a, bookId, path, format, pkg);
        }
    }

    /** D6: контекстное меню книги (долгий тап в списке). */
    public static void showMenu(final Activity a, final long bookId, final String title,
                                final String path, final String format) {
        final View body = LayoutInflater.from(a).inflate(R.layout.dlg_open_with, null);
        LinearLayout items = body.findViewById(R.id.dlg_items);
        final AlertDialog dialog = new AlertDialog.Builder(a, R.style.AppTheme_Dialog)
                .setTitle(Text.isBlank(title) ? nameOf(path) : title)
                .setView(body)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        addMenuRow(a, items, dialog, R.drawable.ic_book_open, R.string.action_open,
                new Runnable() {
                    @Override
                    public void run() {
                        openDefault(a, bookId, title, path, format);
                    }
                });
        addMenuRow(a, items, dialog, R.drawable.ic_share, R.string.action_open_with,
                new Runnable() {
                    @Override
                    public void run() {
                        openWith(a, bookId, path, format);
                    }
                });
        addMenuRow(a, items, dialog, R.drawable.ic_info, R.string.action_info,
                new Runnable() {
                    @Override
                    public void run() {
                        a.startActivity(new Intent(a, BookActivity.class)
                                .putExtra(BookActivity.EXTRA_BOOK_ID, bookId));
                    }
                });
        addMenuRow(a, items, dialog, R.drawable.ic_folder, R.string.action_show_file,
                new Runnable() {
                    @Override
                    public void run() {
                        showInFolder(a, path);
                    }
                });
        Dialogs.show(dialog);
    }

    /** «Показать файл в папке»: сторонний файловый менеджер (решение №15). */
    public static void showInFolder(Activity a, String path) {
        Uri uri;
        if (BookFiles.isSaf(path)) {
            uri = safParent(path);
        } else {
            File f = new File(path == null ? "" : path);
            File dir = f.getParentFile() != null ? f.getParentFile() : f;
            uri = Uri.fromFile(dir);
        }
        if (launchFolder(a, uri, "resource/folder")) {
            return;
        }
        if (launchFolder(a, uri, "*/*")) {
            return;
        }
        Toast.makeText(a, R.string.state_no_file_manager, Toast.LENGTH_SHORT).show();
    }

    /** Родительский документ SAF-книги (папка, её содержащая) или сама книга. */
    private static Uri safParent(String path) {
        Uri doc = Uri.parse(path);
        try {
            String docId = DocumentsContract.getDocumentId(doc);
            int slash = docId.lastIndexOf('/');
            String parentId = slash > 0 ? docId.substring(0, slash) : docId;
            String treeId = DocumentsContract.getTreeDocumentId(doc);
            return Uri.parse("content://" + doc.getAuthority() + "/tree/" + Uri.encode(treeId)
                    + "/document/" + Uri.encode(parentId));
        } catch (Throwable t) {
            return doc;
        }
    }

    /**
     * Первый запуск: разовый выбор читалки по умолчанию (D1·b). Дальше читалка
     * меняется в настройках (S4.2, M8).
     */
    public static void maybeAskDefault(Activity a) {
        Prefs p = App.prefs();
        if (p == null || p.readerAsked()) {
            return;
        }
        if (ReaderRegistry.installed(a).isEmpty()) {
            return; // нечего выбирать; о флаге не беспокоимся — появится читалка
        }
        p.setReaderAsked(true);
        if (p.defaultReader().isEmpty()) {
            OpenWithDialog.showDefault(a, 0, null, null, false);
        }
    }
    // ------------------------------------------------------------------

    /** MIME по формату книги; для пустого формата — по расширению имени файла. */
    private static String mimeOf(String path, String format) {
        String mime = BookFormat.mime(format);
        if ("application/octet-stream".equals(mime)) {
            mime = BookFormat.mime(BookFormat.detect(path));
        }
        return mime;
    }

    private static void addMenuRow(Activity a, LinearLayout parent, final AlertDialog dialog,
                                   int icon, int label, final Runnable action) {
        View row = LayoutInflater.from(a).inflate(R.layout.row_menu, parent, false);
        ((ImageView) row.findViewById(R.id.menu_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.menu_label)).setText(label);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                action.run();
            }
        });
        parent.addView(row);
    }

    private static boolean launchFolder(Activity a, Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String nameOf(String path) {
        if (path == null) {
            return "";
        }
        if (BookFiles.isSaf(path)) {
            try {
                String id = DocumentsContract.getDocumentId(Uri.parse(path));
                int slash = id.lastIndexOf('/');
                return slash >= 0 ? id.substring(slash + 1) : id;
            } catch (Throwable t) {
                return path;
            }
        }
        return new File(path).getName();
    }
}
