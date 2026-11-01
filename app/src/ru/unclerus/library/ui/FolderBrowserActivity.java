package ru.unclerus.library.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.unclerus.library.App;
import ru.unclerus.library.Logger;
import ru.unclerus.library.R;
import ru.unclerus.library.scan.RootRepo;
import ru.unclerus.library.util.AccessPrompt;
import ru.unclerus.library.util.FileAccess;

/**
 * S5 «Выбор папки-корня». Файловый навигатор: показывает только читаемые
 * каталоги (скрытые и файлы пропускаются), позволяет подняться к родителю и
 * выбрать текущую папку корнем библиотеки. Запись корня — в фоновом потоке.
 */
public final class FolderBrowserActivity extends ThemedActivity {

    /** Системный выбор папки (SAF-дерево). */
    private static final int REQ_SAF_TREE = 2;

    private TextView mPath;
    private ArrayAdapter<File> mAdapter;
    private final List<File> mDirs = new ArrayList<>();
    private File mCurrent;

    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private final Handler mUi = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_folder_browser);

        mPath = findViewById(R.id.fb_path);
        ListView list = findViewById(R.id.fb_list);

        findViewById(R.id.fb_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.fb_up).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        findViewById(R.id.fb_pick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickCurrent();
            }
        });
        // «Системный выбор» — SAF-папка (fallback, когда прямого доступа нет).
        findViewById(R.id.fb_sys).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTree();
            }
        });

        // Адаптер хранит ссылку на mDirs; строку заполняем только именем каталога.
        mAdapter = new ArrayAdapter<File>(this, R.layout.row_folder, R.id.folder_name, mDirs) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                ((TextView) row.findViewById(R.id.folder_name)).setText(getItem(position).getName());
                return row;
            }
        };
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File dir = mAdapter.getItem(position);
                if (dir != null) {
                    open(dir);
                }
            }
        });

        open(startDir());
    }

    @Override
    protected void onDestroy() {
        mBg.shutdownNow();
        super.onDestroy();
    }

    /** Стартовая папка: внешнее хранилище, если читаемо; иначе корень /. */
    private File startDir() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.isDirectory() && ext.canRead()) {
                return ext;
            }
        } catch (Throwable t) {
            Logger.w("FolderBrowser", "внешнее хранилище недоступно: " + t);
        }
        return new File("/");
    }

    /** Перейти в каталог: обновить путь и перечитать содержимое. */
    private void open(File dir) {
        mCurrent = dir;
        mPath.setText(dir.getAbsolutePath());
        refresh(dir);
    }

    /** Перечитать список подкаталогов: только читаемые, без скрытых, по имени. */
    private void refresh(File dir) {
        mDirs.clear();
        File[] children = null;
        try {
            children = dir.listFiles();
        } catch (SecurityException e) {
            children = null; // недоступный каталог — показываем пустой список
        }
        if (children != null) {
            for (File f : children) {
                if (f == null) {
                    continue;
                }
                String name = f.getName();
                if (name.startsWith(".")) {
                    continue; // скрытые не показываем
                }
                if (!f.isDirectory() || !f.canRead()) {
                    continue; // только читаемые каталоги, без файлов
                }
                mDirs.add(f);
            }
        }
        Collections.sort(mDirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        mAdapter.notifyDataSetChanged();
    }

    /** Переход к родительскому каталогу; из корня не выходим. */
    private void goUp() {
        if (mCurrent == null) {
            return;
        }
        File parent = mCurrent.getParentFile();
        if (parent != null) {
            open(parent);
        }
    }

    /** Сохранить текущий путь корнем в БД и закрыть экран. */
    private void pickCurrent() {
        if (mCurrent == null) {
            return;
        }
        final String path = mCurrent.getAbsolutePath();
        mBg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteDatabase db = App.db().getWritableDatabase();
                    long id = RootRepo.add(db, path, RootRepo.KIND_FILE);
                    if (id < 0) {
                        Logger.i("FolderBrowser", "корень уже есть: " + path);
                    }
                } catch (Throwable t) {
                    Logger.e("FolderBrowser", "не удалось сохранить корень: " + path, t);
                }
                mUi.post(new Runnable() {
                    @Override
                    public void run() {
                        setResult(RESULT_OK);
                        finish();
                    }
                });
            }
        });
    }

    /** Системный выбор папки (SAF): добавить дерево корнем и закрыть экран. */
    private void pickTree() {
        try {
            startActivityForResult(FileAccess.openTree(), REQ_SAF_TREE);
        } catch (Throwable t) {
            Logger.w("FolderBrowser", "системный выбор папки недоступен: " + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAF_TREE) {
            return;
        }
        final String uri = FileAccess.persistTree(this, data);
        if (uri == null) {
            return;
        }
        AccessPrompt.addSafRoot(uri, new Runnable() {
            @Override
            public void run() {
                setResult(RESULT_OK);
                finish();
            }
        });
    }
}
