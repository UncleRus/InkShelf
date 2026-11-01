package ru.unclerus.library.scan;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

/**
 * Обход SAF-дерева через {@link DocumentsContract} (резервный путь доступа,
 * PLAN §6.2). Отдельный от {@link android.provider.DocumentsContract}-обёрток
 * helper — чтобы обход ({@link WalkPhase}) и UI пользовались одним кодом.
 */
public final class SafDocs {

    /** Колонки запроса детей документа. */
    public static final String[] CHILD_COLS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    public static final int C_DOC_ID = 0;
    public static final int C_NAME = 1;
    public static final int C_MIME = 2;
    public static final int C_SIZE = 3;
    public static final int C_MTIME = 4;

    private SafDocs() {}

    public static boolean isDir(String mime) {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
    }

    /** Дети документа (для корня передаётся id tree-документа). */
    public static Cursor children(Context ctx, String treeUri, String parentDocId) {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(
                Uri.parse(treeUri), parentDocId);
        return ctx.getContentResolver().query(uri, CHILD_COLS, null, null, null);
    }

    /** URI документа внутри дерева — он же адрес книги SAF. */
    public static String documentUri(String treeUri, String docId) {
        return DocumentsContract
                .buildDocumentUriUsingTree(Uri.parse(treeUri), docId)
                .toString();
    }

    /** id корневого документа дерева. */
    public static String treeDocumentId(String treeUri) {
        return DocumentsContract.getTreeDocumentId(Uri.parse(treeUri));
    }

    /**
     * Читаемое имя дерева для интерфейса: {@code primary:Documents/Books} →
     * {@code Documents/Books}; том ({@code primary:}) отбрасывается.
     */
    public static String displayPath(String treeUri) {
        try {
            String id = treeDocumentId(treeUri);
            int colon = id.indexOf(':');
            String name = colon >= 0 ? id.substring(colon + 1) : id;
            return name.isEmpty() ? id : name;
        } catch (Throwable t) {
            return treeUri;
        }
    }
}
