package ru.unclerus.library.ui;

import android.view.KeyEvent;

/**
 * Аппаратные кнопки листания списков.
 *
 * <p>На ONYX BOOX RAPHAEL2 кнопок на корпусе две — это качель громкости, но
 * прошивка Onyx трактует их как «боковые клавиши» (глобальные настройки
 * {@code key_custom_side_key_short_press_up/down} = {@code pageVolUp/pageVolDown})
 * и присылает приложению готовые {@code KEYCODE_PAGE_UP/KEYCODE_PAGE_DOWN}, а не
 * {@code VOLUME_*}. Поэтому обрабатываем оба семейства: {@code PAGE_*} — для этого
 * устройства, {@code VOLUME_*} — на случай, если раскладку боковых клавиш в
 * системе сменят на громкость (или на другом устройстве).
 *
 * <p>Направление как у свайпа и как в обычных читалках: верхняя кнопка — назад,
 * нижняя — вперёд.
 */
public final class HwKeys {

    private HwKeys() {}

    /** Сдвиг страницы по коду клавиши: {@code -1} назад, {@code +1} вперёд, {@code 0} — не листает. */
    public static int pageDelta(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                return -1;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return 1;
            default:
                return 0;
        }
    }
}
