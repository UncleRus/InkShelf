package ru.unclerus.library.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки приложения (S4) поверх {@link SharedPreferences}. Единственный
 * владелец значений — синглтон в {@code App}, чтобы UI и индексатор читали одно
 * и то же состояние.
 */
public final class Prefs {

    private static final String FILE = "library_prefs";

    // --- ключи ---
    public static final String K_FONT_SCALE = "font_scale";        // float
    public static final String K_DENSITY = "list_density";         // normal|compact
    public static final String K_AUTHOR_FORMAT = "author_format";  // first|last
    public static final String K_SHOW_COVERS = "show_covers";      // boolean
    public static final String K_COVER_CACHE_MB = "cover_cache_mb";// int
    public static final String K_READER = "default_reader";        // package|""
    public static final String K_READER_ASKED = "reader_asked";    // boolean
    public static final String K_SCHEMA_SEEN = "schema_seen";      // int
    /** Последняя открытая вкладка главного экрана (S1); -1 — ещё не запоминалась. */
    public static final String K_TAB = "main_tab";                 // int
    /** Запоминать открытую страницу вкладки между перезапусками (S4.8). */
    public static final String K_REMEMBER_PAGE = "remember_page";  // boolean
    /** Страницы вкладок S1 между запусками: CSV int по индексу вкладки (S4.8). */
    public static final String K_TAB_PAGES = "main_tab_pages";     // String "3,1,5,..."
    // Контексты режима сортировки (значение — BookDao.SORT_*):
    public static final String K_SORT_BOOKS = "sort_all_books";    // S1.6 «Все книги»
    public static final String K_SORT_GENRE = "sort_genre";        // S2, книги жанра
    public static final String K_SORT_DATE = "sort_date";          // S2, группа даты

    // --- значения ---
    public static final String DENSITY_NORMAL = "normal";
    public static final String DENSITY_COMPACT = "compact";
    public static final String AUTHOR_FIRST_LAST = "first";
    public static final String AUTHOR_LAST_FIRST = "last";

    public static final int DEFAULT_COVER_CACHE_MB = 64;

    private final SharedPreferences mSp;

    public Prefs(Context ctx) {
        mSp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return mSp;
    }

    // --- шрифт (S4.3): 1.0 / 1.25 / 1.5 / 1.75 / 2.0 ---
    public float fontScale() {
        return mSp.getFloat(K_FONT_SCALE, 1.0f);
    }

    public void setFontScale(float v) {
        mSp.edit().putFloat(K_FONT_SCALE, v).apply();
    }

    // --- плотность списка (S4.4) ---
    public String density() {
        return mSp.getString(K_DENSITY, DENSITY_NORMAL);
    }

    public boolean isCompact() {
        return DENSITY_COMPACT.equals(density());
    }

    public void setDensity(String d) {
        mSp.edit().putString(K_DENSITY, DENSITY_COMPACT.equals(d) ? DENSITY_COMPACT : DENSITY_NORMAL).apply();
    }

    // --- формат имени автора (S4.5) ---
    public String authorFormat() {
        return mSp.getString(K_AUTHOR_FORMAT, AUTHOR_FIRST_LAST);
    }

    public boolean isLastFirst() {
        return AUTHOR_LAST_FIRST.equals(authorFormat());
    }

    public void setAuthorFormat(String f) {
        mSp.edit().putString(K_AUTHOR_FORMAT,
                AUTHOR_LAST_FIRST.equals(f) ? AUTHOR_LAST_FIRST : AUTHOR_FIRST_LAST).apply();
    }

    // --- обложки (S4.6) ---
    public boolean showCovers() {
        return mSp.getBoolean(K_SHOW_COVERS, true);
    }

    public void setShowCovers(boolean v) {
        mSp.edit().putBoolean(K_SHOW_COVERS, v).apply();
    }

    // --- размер кэша обложек, МБ (S4.6/S4.7): только 64/128/256/512 ---
    /** Допустимые размеры дискового кэша обложек, МБ (S4.7). */
    public static final int[] COVER_CACHE_MB = {64, 128, 256, 512};

    public int coverCacheMb() {
        return normalizeCacheMb(mSp.getInt(K_COVER_CACHE_MB, DEFAULT_COVER_CACHE_MB));
    }

    public void setCoverCacheMb(int mb) {
        mSp.edit().putInt(K_COVER_CACHE_MB, normalizeCacheMb(mb)).apply();
    }

    /** Привести значение к одному из допустимых (S4.7); неизвестное — к значению по умолчанию. */
    private static int normalizeCacheMb(int mb) {
        for (int i = 0; i < COVER_CACHE_MB.length; i++) {
            if (COVER_CACHE_MB[i] == mb) {
                return mb;
            }
        }
        return DEFAULT_COVER_CACHE_MB;
    }

    // --- режимы сортировки (S1.6 и S2): помнятся между запусками ---
    /** Сохранённый режим сортировки контекста {@code key}; {@code ""} — не выбирали. */
    public String sort(String key) {
        return mSp.getString(key, "");
    }

    public void setSort(String key, String value) {
        mSp.edit().putString(key, value == null ? "" : value).apply();
    }

    // --- читалка по умолчанию (S4.2) ---
    public String defaultReader() {
        return mSp.getString(K_READER, "");
    }

    public void setDefaultReader(String pkg) {
        mSp.edit().putString(K_READER, pkg == null ? "" : pkg).apply();
    }

    /** Спрашивали ли читалку по умолчанию при первом запуске (D1·b). */
    public boolean readerAsked() {
        return mSp.getBoolean(K_READER_ASKED, false);
    }

    public void setReaderAsked(boolean v) {
        mSp.edit().putBoolean(K_READER_ASKED, v).apply();
    }

    // --- версия схемы, которую уже приводили в порядок на старте ---
    public int schemaSeen() {
        return mSp.getInt(K_SCHEMA_SEEN, 0);
    }

    public void setSchemaSeen(int v) {
        mSp.edit().putInt(K_SCHEMA_SEEN, v).apply();
    }

    // --- последняя открытая вкладка главного экрана (S1) ---
    /** Индекс последней вкладки или -1, если пользователь ещё не переключался. */
    public int tab() {
        return mSp.getInt(K_TAB, -1);
    }

    public void setTab(int idx) {
        mSp.edit().putInt(K_TAB, idx).apply();
    }

    // --- страницы вкладок между перезапусками (S4.8) ---
    /**
     * Сохранять ли открытую страницу каждой вкладки между запусками (настройка
     * S4.8). По умолчанию включено: позиция списка помнится и в рамках сессии
     * (BACKLOG №14), и между перезапусками (№15); выключение оставляет память
     * сессии и очищает сохранённые страницы.
     */
    public boolean rememberPage() {
        return mSp.getBoolean(K_REMEMBER_PAGE, true);
    }

    public void setRememberPage(boolean v) {
        mSp.edit().putBoolean(K_REMEMBER_PAGE, v).apply();
    }

    /** Сохранённая страница вкладки {@code idx}; 0 — вкладка ещё не запоминалась. */
    public int tabPage(int idx) {
        if (idx < 0) {
            return 0;
        }
        String[] parts = pageParts();
        if (idx >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[idx]);
        } catch (NumberFormatException e) {
            return 0; // испорченное значение читаем как «не запоминалось»
        }
    }

    /** Записать страницу {@code page} (0 — «не листали») для вкладки {@code idx}. */
    public void setTabPage(int idx, int page) {
        if (idx < 0) {
            return;
        }
        String[] parts = pageParts();
        if (idx >= parts.length) {
            String[] grown = new String[idx + 1];
            System.arraycopy(parts, 0, grown, 0, parts.length);
            for (int i = parts.length; i <= idx; i++) {
                grown[i] = "0";
            }
            parts = grown;
        }
        parts[idx] = String.valueOf(Math.max(0, page));
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        mSp.edit().putString(K_TAB_PAGES, sb.toString()).apply();
    }

    /** Забыть сохранённые страницы — при выключении настройки S4.8. */
    public void clearTabPages() {
        mSp.edit().remove(K_TAB_PAGES).apply();
    }

    /** Разобранный CSV страниц; пустое значение — пустой список. */
    private String[] pageParts() {
        String raw = mSp.getString(K_TAB_PAGES, "");
        if (raw == null || raw.isEmpty()) {
            return new String[0];
        }
        return raw.split(",");
    }
}
