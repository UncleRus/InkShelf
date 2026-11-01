package ru.unclerus.library.util;

import java.util.Locale;

/**
 * Язык приложения. Русский и английский разведены ресурсами: {@code values/} —
 * русский (по умолчанию), {@code values-en/} — английский. Отдельной настройки
 * языка нет, поэтому локаль берётся системная. Тем же признаком пользуется выбор
 * локализованных названий жанров ({@code genre.display_name} / {@code display_name_en}).
 */
public final class Lang {

    private Lang() {}

    /** true — активна английская локаль (совпадает с выбором {@code values-en}). */
    public static boolean english() {
        return "en".equals(Locale.getDefault().getLanguage());
    }
}
