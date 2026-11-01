package ru.unclerus.library.file;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.scan.RootRepo;

/**
 * Свой минимальный FileProvider (AndroidX недоступен): отдаёт книги читалкам по
 * {@code content://ru.unclerus.library.files/<путь>}. Путь передаётся как есть в
 * {@code uri.getPath()}, но отдаются только файлы внутри включённых корней
 * библиотеки — иначе любой, кому дали URI, мог бы прочитать произвольный файл.
 */
public final class LibraryFileProvider extends ContentProvider {

    public static final String AUTHORITY = "ru.unclerus.library.files";

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * URI файла книги для передачи читалке:
     * {@code content://ru.unclerus.library.files/<абсолютный путь>}.
     */
    public static Uri uriFor(String path) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .path(path)
                .build();
    }

    private static File toFile(Uri uri) {
        String path = uri.getPath();
        return path == null ? null : new File(path);
    }

    /**
     * Лежит ли файл внутри хотя бы одного включённого корня библиотеки.
     * Пути книг строятся как {@code File(root.path, ...)}, поэтому проверка —
     * префиксная, по каноническому виду.
     */
    private boolean allowed(File f) {
        if (f == null) {
            return false;
        }
        String path = f.getAbsolutePath();
        Context ctx = getContext();
        if (ctx == null) {
            return false;
        }
        try {
            for (RootRepo.Root r : RootRepo.listAll(App.db().getReadableDatabase())) {
                if (r.path == null || r.path.isEmpty()) {
                    continue;
                }
                if (path.equals(r.path) || path.startsWith(r.path + File.separator)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Logger.e("FileProvider", "не удалось проверить корни библиотеки", t);
            return false;
        }
        return false;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = toFile(uri);
        if (f == null || !f.isFile() || !allowed(f)) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = toFile(uri);
        if (f == null || !f.isFile() || !allowed(f)) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = c.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(f.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(f.length());
            } else {
                row.add(null);
            }
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return guessMime(uri.getLastPathSegment());
    }

    private static String guessMime(String name) {
        if (name == null) {
            return "application/octet-stream";
        }
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".epub")) return "application/epub+zip";
        if (n.endsWith(".fb2.zip") || n.endsWith(".fb2")) return "application/fb2+zip";
        if (n.endsWith(".mobi") || n.endsWith(".azw3")) return "application/x-mobipocket-ebook";
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
