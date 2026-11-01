package ru.unclerus.library.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

import ru.unclerus.library.App;

/**
 * Базовый экран приложения: применяет пользовательский масштаб шрифта (S4.3)
 * поверх системного. Настройка хранится как множитель в {@link App#fontScale()},
 * поэтому итоговый {@code fontScale} ресурсов = системный × пользовательский
 * (на устройстве системный около 1.3, плюс выбранные 100–200 %).
 *
 * <p>Настройка меняется в S4, а экраны в бэкстеке не пересоздаются сами. Поэтому
 * при возврате ({@code onResume}) экран сравнивает применённый множитель с
 * текущим и при расхождении пересоздаётся — sp-размеры пересчитываются, ничего
 * не обрезается (NFR-1).
 */
public abstract class ThemedActivity extends Activity {

    /** Множитель, с которым создан текущий контекст ресурсов. */
    private float mApplied = -1f;

    @Override
    protected void attachBaseContext(Context base) {
        float user = App.fontScale();
        if (user < 0.5f || user > 4f) {
            user = 1f; // защита от мусора в настройках
        }
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.fontScale = c.fontScale * user;
        mApplied = user;
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mApplied > 0f && Math.abs(mApplied - App.fontScale()) > 0.001f) {
            mApplied = App.fontScale();
            recreate();
        }
    }
}
