package ru.unclerus.library.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import ru.unclerus.library.R;

/**
 * Базовый экран с шапкой (кнопка «назад» + заголовок) и областью сообщения.
 * Экраны S2–S7 на M0 — заглушки на этом базовом классе; наполнение — по этапам M4–M8.
 */
public abstract class BaseActivity extends ThemedActivity {

    /** Заголовок экрана. */
    protected abstract int titleRes();

    /** Текст-заглушка (0 — пусто). */
    protected int messageRes() {
        return 0;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_placeholder);
        ((TextView) findViewById(R.id.ph_title)).setText(titleRes());
        ImageButton back = findViewById(R.id.ph_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        int m = messageRes();
        setMessage(m != 0 ? getString(m) : "");
    }

    /** Позволяет подклассам (диагностика и т.п.) выставить текст после проверок. */
    protected final void setMessage(CharSequence text) {
        ((TextView) findViewById(R.id.ph_msg)).setText(text);
    }
}
