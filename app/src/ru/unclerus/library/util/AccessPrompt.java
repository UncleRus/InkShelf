package ru.unclerus.library.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.R;
import ru.unclerus.library.scan.RootRepo;

/**
 * Диалог D2 «Доступ к файлам» (макет 31) и добавление SAF-папки. Общий код для
 * главного экрана ({@code MainActivity}), настроек ({@code SettingsActivity}) и
 * выбора папки ({@code FolderBrowserActivity}).
 */
public final class AccessPrompt {

    /** Что делать по кнопкам диалога D2. */
    public interface Actions {
        /** «Разрешить доступ» — показать системный экран «все файлы». */
        void onGrant();

        /** «Выбрать папку» — открыть системный выбор дерева (SAF). */
        void onPickFolder();
    }

    private AccessPrompt() {}

    /** Показать D2: объяснение, «Позже» · «Выбрать папку» · «Разрешить доступ». */
    public static void show(final Activity a, final Actions actions) {
        String body = a.getString(R.string.dlg_file_access_text) + "\n\n"
                + a.getString(R.string.dlg_file_access_saf_hint);
        Dialogs.show(new AlertDialog.Builder(a, R.style.AppTheme_Dialog)
                .setTitle(R.string.dlg_file_access)
                .setMessage(body)
                .setNegativeButton(R.string.action_later, null)
                .setNeutralButton(R.string.action_pick_folder, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (actions != null) {
                            actions.onPickFolder();
                        }
                    }
                })
                .setPositiveButton(R.string.action_grant_access,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (actions != null) {
                                    actions.onGrant();
                                }
                            }
                        }));
    }

    /**
     * Добавить SAF-папку по выбранному дереву (в фоне), затем вызвать
     * {@code onDone} в главном потоке.
     */
    public static void addSafRoot(final String treeUri, final Runnable onDone) {
        if (treeUri == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteDatabase db = App.db().getWritableDatabase();
                    long id = RootRepo.add(db, treeUri, treeUri, RootRepo.KIND_SAF);
                    if (id < 0) {
                        Logger.i("Access", "SAF-папка уже добавлена: " + treeUri);
                    } else {
                        Logger.i("Access", "добавлена SAF-папка: " + treeUri);
                    }
                } catch (Throwable t) {
                    Logger.e("Access", "не удалось добавить SAF-папку", t);
                }
                if (onDone != null) {
                    new Handler(Looper.getMainLooper()).post(onDone);
                }
            }
        }).start();
    }
}
