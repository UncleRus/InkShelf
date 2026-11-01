package ru.unclerus.library.open;

import android.graphics.drawable.Drawable;

/**
 * Установленная читалка: пакет, отображаемая метка и иконка приложения.
 * Метка/иконка берутся из {@code PackageManager} (FR-5).
 */
public final class ReaderInfo {

    public final String pkg;
    public final String label;
    public final Drawable icon;

    public ReaderInfo(String pkg, String label, Drawable icon) {
        this.pkg = pkg;
        this.label = label;
        this.icon = icon;
    }
}
