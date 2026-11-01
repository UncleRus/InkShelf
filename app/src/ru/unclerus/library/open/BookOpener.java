package ru.unclerus.library.open;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.io.File;

import ru.unclerus.library.Logger;
import ru.unclerus.library.file.LibraryFileProvider;
import ru.unclerus.library.util.BookFiles;

/**
 * Запуск книги в выбранной читалке (FR-5).
 *
 * <p>Базовый интент одинаков для всех: {@code ACTION_VIEW} + {@code setDataAndType}
 * (data и type вместе — этого требуют фильтры читалок) + адресный {@code setPackage}.
 * Отличие одно: NeoReader ({@code com.onyx.kreader}) получает {@code file://}
 * (поток {@code content://} он не копирует), остальные — {@code content://} нашего
 * {@link LibraryFileProvider}.
 */
public final class BookOpener {

    private BookOpener() {}

    /**
     * Открыть книгу в читалке {@code pkg} и записать историю.
     *
     * @return {@code true}, если активность читалки удалось запустить
     */
    public static boolean open(Activity a, long bookId, String path, String mime, String pkg) {
        try {
            a.startActivity(intent(path, mime, pkg));
        } catch (Throwable t) {
            Logger.e("Open", "не удалось запустить " + pkg + " для " + path, t);
            return false;
        }
        OpenHistory.record(a, bookId, pkg);
        return true;
    }

    /** Интент открытия книги в конкретной читалке. */
    public static Intent intent(String path, String mime, String pkg) {
        Uri uri;
        if (BookFiles.isSaf(path)) {
            // SAF-документ: файлового пути нет — отдаём URI как есть.
            uri = Uri.parse(path);
        } else if (ReaderRegistry.KREADER.equals(pkg)) {
            uri = Uri.fromFile(new File(path));
        } else {
            uri = LibraryFileProvider.uriFor(path);
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (pkg != null && !pkg.isEmpty()) {
            i.setPackage(pkg);
        }
        return i;
    }
}
