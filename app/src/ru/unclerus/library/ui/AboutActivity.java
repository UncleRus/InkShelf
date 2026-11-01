package ru.unclerus.library.ui;

import android.os.Bundle;

import ru.unclerus.library.R;

/**
 * S7 «О программе» (макет 15). Пока — заглушка на {@link BaseActivity}: название,
 * девиз, версия и описание выводятся одним сообщением, плюс строка о том, чем
 * приложение написано. Полный вид по макету (иконка, отдельные блоки) — M8.
 */
public final class AboutActivity extends BaseActivity {

    @Override
    protected int titleRes() {
        return R.string.title_about;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setMessage(getString(R.string.about_text) + "\n\n" + getString(R.string.about_made));
    }
}
