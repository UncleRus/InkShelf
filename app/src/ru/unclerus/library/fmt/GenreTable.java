package ru.unclerus.library.fmt;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Таблица соответствия нормализованных кодов жанров FB2 и их названий: русское
 * ({@code values/}) и английское ({@code values-en/}). Имя для показа выбирается по
 * языку приложения (см. {@code util.Lang}); обе локализации хранятся в БД
 * ({@code genre.display_name} / {@code genre.display_name_en}).
 */
public final class GenreTable {

    /** Тройки «нормализованный код» -> «русское название» -> «английское название». */
    private static final String[][] GENRES = {
        { "action", "Боевик", "Action" },
        { "adventure", "Приключения", "Adventure" },
        { "adv_animal", "Приключения о животных", "Animal Adventure" },
        { "adv_history", "Исторические приключения", "Historical Adventure" },
        { "adv_indian", "Приключения об индейцах", "Frontier Adventure" },
        { "antique_european", "Античная европейская литература", "Classical European Literature" },
        { "antique_myths", "Античные мифы", "Classical Myths" },
        { "back_to_ussr", "Назад в СССР", "Back to the USSR" },
        { "boyar_anime", "Бояръ-аниме", "Boyar Anime" },
        { "child_prose", "Детская проза", "Children's Prose" },
        { "child_sf", "Детская фантастика", "Children's Science Fiction" },
        { "children", "Детская литература", "Children's Literature" },
        { "computers", "Компьютеры и Интернет", "Computers & Internet" },
        { "det_action", "Детективный боевик", "Detective Action" },
        { "dorama", "Дорама", "Drama" },
        { "fanfiction", "Фанфик", "Fan Fiction" },
        { "fantasy", "Фэнтези", "Fantasy" },
        { "fantasy_action", "Боевое фэнтези", "Action Fantasy" },
        { "fantasy_fight", "Фэнтези с сражениями", "Combat Fantasy" },
        { "heroic_fantasy", "Героическое фэнтези", "Heroic Fantasy" },
        { "home_sex", "Эротика", "Erotica" },
        { "humor", "Юмор", "Humor" },
        { "humor_prose", "Юмористическая проза", "Humorous Prose" },
        { "love_sf", "Любовная фантастика", "Romantic Science Fiction" },
        { "modern_tale", "Современная сказка", "Modern Tale" },
        { "network_literature", "Сетевая литература", "Web Literature" },
        { "nonf_biography", "Биографии и мемуары", "Biography & Memoir" },
        { "nonf_criticism", "Критика", "Criticism" },
        { "nonf_publicism", "Публицистика", "Publicism" },
        { "novel", "Роман", "Novel" },
        { "other", "Прочее", "Other" },
        { "poetry", "Поэзия", "Poetry" },
        { "popadanec", "Попаданец", "Isekai" },
        { "popadancy", "Попаданцы", "Isekai" },
        { "popadantsy", "Попаданцы", "Isekai" },
        { "popadantsy_v_magicheskie_miry", "Попаданцы в магические миры", "Isekai in Magical Worlds" },
        { "popadantsy_vo_vremeni", "Попаданцы во времени", "Time Travel" },
        { "proce", "Прочая литература", "Miscellaneous Literature" },
        { "prose_contemporary", "Современная проза", "Contemporary Prose" },
        { "prose_history", "Историческая проза", "Historical Prose" },
        { "prose_military", "Военная проза", "Military Prose" },
        { "ref_guide", "Справочник", "Reference Guide" },
        { "ref_ref", "Справочная литература", "Reference Literature" },
        { "reference", "Справочная литература", "Reference Literature" },
        { "sci_fi", "Научная фантастика", "Science Fiction" },
        { "sci_history", "История", "History" },
        { "sci_medicine", "Медицина", "Medicine" },
        { "sci_phys", "Физика", "Physics" },
        { "science", "Наука", "Science" },
        { "sf", "Научная фантастика", "Science Fiction" },
        { "sf_action", "Боевая фантастика", "Military Science Fiction" },
        { "sf_cyberpunk", "Киберпанк", "Cyberpunk" },
        { "sf_detective", "Детективная фантастика", "Detective Science Fiction" },
        { "sf_etc", "Прочая фантастика", "Other Science Fiction" },
        { "sf_fantasy", "Фэнтези", "Fantasy" },
        { "sf_fantasy_city", "Городское фэнтези", "Urban Fantasy" },
        { "sf_heroic", "Героическая фантастика", "Heroic Science Fiction" },
        { "sf_history", "Историческая фантастика", "Alternate History" },
        { "sf_humor", "Юмористическая фантастика", "Humorous Science Fiction" },
        { "sf_litrpg", "ЛитРПГ", "LitRPG" },
        { "sf_mystic", "Мистическая фантастика", "Mystic Science Fiction" },
        { "sf_postapocalyptic", "Постапокалипсис", "Post-Apocalyptic" },
        { "sf_realrpg", "РеалРПГ", "RealRPG" },
        { "sf_social", "Социальная фантастика", "Social Science Fiction" },
        { "sf_space", "Космическая фантастика", "Space Science Fiction" },
        { "sf_stimpank", "Стимпанк", "Steampunk" },
        { "sf_technofantasy", "Технофэнтези", "Technofantasy" },
        { "unfinished", "Незаконченное", "Unfinished" },
        { "urban_fantasy", "Городское фэнтези", "Urban Fantasy" }
    };

    /** Те же тройки, отсортированные по коду (для бинарного поиска и allCodes). */
    private static final String[][] SORTED = sortByCode(GENRES);

    /** Коды в алфавитном порядке. */
    private static final String[] CODES = extractCodes(SORTED);

    private GenreTable() {
    }

    /** Нормализация кода FB2: trim, нижний регистр (Locale.US), дефисы -> подчёркивания. */
    public static String normalizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        s = s.replace('-', '_');
        s = s.toLowerCase(Locale.US);
        return s;
    }

    /** Русское название по НОРМАЛИЗОВАННОМУ коду, либо null, если код неизвестен. */
    public static String displayName(String code) {
        return localized(code, 1);
    }

    /** Английское название по НОРМАЛИЗОВАННОМУ коду, либо null, если код неизвестен. */
    public static String displayNameEn(String code) {
        return localized(code, 2);
    }

    private static String localized(String code, int col) {
        if (code == null) {
            return null;
        }
        int lo = 0;
        int hi = SORTED.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = SORTED[mid][0].compareTo(code);
            if (cmp == 0) {
                return SORTED[mid][col];
            }
            if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return null;
    }

    /** Все известные нормализованные коды, по алфавиту. */
    public static String[] allCodes() {
        return CODES.clone();
    }

    // Сортировка троек по коду без лямбд.
    private static String[][] sortByCode(String[][] src) {
        String[][] copy = new String[src.length][];
        for (int i = 0; i < src.length; i++) {
            copy[i] = new String[] { src[i][0], src[i][1], src[i][2] };
        }
        Arrays.sort(copy, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareTo(b[0]);
            }
        });
        return copy;
    }

    // Выборка кодов из отсортированных троек.
    private static String[] extractCodes(String[][] src) {
        String[] codes = new String[src.length];
        for (int i = 0; i < src.length; i++) {
            codes[i] = src[i][0];
        }
        return codes;
    }
}
