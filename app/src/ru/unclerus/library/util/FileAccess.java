package ru.unclerus.library.util;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import ru.unclerus.library.Logger;

/**
 * Доступ к файлам (PLAN §6.2). Основной путь — «все файлы»
 * ({@code MANAGE_EXTERNAL_STORAGE}, API 30+; на API 23–29 — обычные
 * storage-разрешения). Резерв — SAF: папка добавляется системным выбором
 * дерева ({@code ACTION_OPEN_DOCUMENT_TREE}) с закреплением права.
 */
public final class FileAccess {

    private FileAccess() {}

    /** Есть ли полный доступ к внешнему хранилищу. */
    public static boolean hasAllFiles(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return false;
            }
        }
        return ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Запрос полного доступа: системный экран «Все файлы» (API 30+) либо
     * обычные storage-разрешения (API 23–29, результат придёт в
     * {@code onRequestPermissionsResult} с кодом {@code requestCode}).
     */
    public static void requestManage(Activity a, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.getPackageName()));
            try {
                a.startActivity(i);
            } catch (ActivityNotFoundException e) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            a.requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
        }
    }

    /** Интент системного выбора папки (SAF-дерево): чтение + запись, закрепляемо. */
    public static Intent openTree() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }

    /**
     * Закрепить право на выбранное дерево и вернуть его URI строкой или
     * {@code null}, если выбор пуст. Закрепляем ровно те права, что выдал
     * системный выбор (обычно чтение+запись; запись нужна «Удалить файл»).
     */
    public static String persistTree(Activity a, Intent data) {
        if (data == null || data.getData() == null) {
            return null;
        }
        Uri uri = data.getData();
        int take = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (take == 0) {
            take = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }
        try {
            a.getContentResolver().takePersistableUriPermission(uri, take);
        } catch (Throwable t) {
            Logger.w("FileAccess", "не удалось закрепить право на " + uri + ": " + t);
        }
        return uri.toString();
    }
}
