package ru.unclerus.library.ui;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import ru.unclerus.library.Logger;
import ru.unclerus.library.R;

/**
 * S7 «О программе» (макет 15): знак, название, девиз, версия, назначение,
 * строка о том, чем написан, и идентификатор пакета.
 *
 * <p>Раньше экран был заглушкой на {@link BaseActivity} — одна колонка текста
 * без иконки, названия и версии. Теперь разметка своя
 * ({@code activity_about.xml}), а версия и имя пакета берутся у системы,
 * чтобы не расходиться с установленным APK.
 */
public final class AboutActivity extends ThemedActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_about);

        ((TextView) findViewById(R.id.about_title)).setText(R.string.title_about);
        ((TextView) findViewById(R.id.about_version))
                .setText(getString(R.string.about_version, versionName()));
        ((TextView) findViewById(R.id.about_package)).setText(getPackageName());

        ImageButton back = findViewById(R.id.about_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    /** {@code versionName} установленного пакета (для строки «версия %s»). */
    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            Logger.e("About", "не удалось получить версию пакета", e);
            return "";
        }
    }
}
