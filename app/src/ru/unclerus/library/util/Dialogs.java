package ru.unclerus.library.util;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import ru.unclerus.library.R;

/**
 * Единая отрисовка диалогов.
 *
 * <p>Панель {@link AlertDialog} по умолчанию рисует платформенный фон
 * {@code dialog_border_device_default_light} — толстую рамку со скруглением и
 * тенью. На e-ink это грязно и расходится с макетом ({@code .dlg}: белая
 * панель, рамка 1dp, радиус 6dp). После показа подменяем фон панели на
 * {@link R.drawable#bg_dialog}.
 *
 * <p>Показывать диалоги нужно через {@link #show(AlertDialog.Builder)} —
 * тогда перекраска гарантирована.
 */
public final class Dialogs {

    private Dialogs() {}

    /** Показать диалог из билдера и придать панели вид макета. */
    public static AlertDialog show(AlertDialog.Builder builder) {
        return show(builder.create());
    }

    /** Показать готовый диалог и придать панели вид макета. */
    public static AlertDialog show(AlertDialog dialog) {
        dialog.show();
        panel(dialog);
        return dialog;
    }

    /** Перекрасить фон панели диалога; безопасно вызывать после {@code show()}. */
    public static void panel(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        View decor = w.getDecorView();
        if (decor == null) {
            return;
        }
        View content = decor.findViewById(android.R.id.content);
        if (content != null) {
            content.setBackgroundResource(R.drawable.bg_dialog);
            applyToChildren(content);
        } else {
            applyToChildren(decor);
        }
    }

    private static void applyToChildren(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            g.getChildAt(i).setBackgroundResource(R.drawable.bg_dialog);
        }
    }
}
