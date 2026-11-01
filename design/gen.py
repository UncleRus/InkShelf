#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генератор макетов UI «Библиотека», иконок и строковых ресурсов.

Один прогон даёт:
  design/out/<lang>/<screen>.html   — макеты (lang: ru, en)
  app/res/values/strings.xml        — русский (язык по умолчанию)
  app/res/values-en/strings.xml     — английский
  app/res/drawable/ic_*.xml         — тот же набор иконок, что и в макетах

Строки живут в ОДНОЙ таблице S_, иконки — в ОДНОЙ таблице ICONS: правка здесь
меняет и картинку, и resources. Так макет и приложение не расходятся.

Иконки: минималистичные штриховые, сетка 24x24, обводка 1.9, без заливок
(только точки). На e-ink такой стиль даёт ровный штрих без «серой каши».

Токены (цвета/размеры/типографика) совпадают с res/values/{colors,dimens,styles}.xml.
Разрешение — реальное: 1264x1680, density 300 (1 dp = 1.875 px).
Зависимостей нет — только stdlib.
"""
import os
import re
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, os.pardir, 'app')

LANGS = ('ru', 'en')
LANG = 'ru'        # текущий язык при сборке экранов (меняется в main)

S = 1.875          # px в 1 dp
W, H = 1264, 1680  # экран устройства


def dp(v):
    return round(v * S)


# --- Типографика (sp), как в dimens.xml ---
TYPE = {
    'xl': 22, 'title': 18, 'header': 16, 'tab': 15,
    'body': 15, 'meta': 13, 'count': 13, 'placeholder': 20,
}


def t(kind, fs=1.0):
    return "font-size:%dpx" % round(TYPE[kind] * S * fs)


# --- Размеры (dp) ---
PAD_H = dp(12)      # list_pad_h
PAD_V = dp(10)      # list_pad_v
TOUCH = dp(48)      # touch_min
COVER_W, COVER_H = dp(56), dp(84)
COVER_WC, COVER_HC = dp(36), dp(54)
TAB_H = dp(44)
TAB_PH = dp(14)
SEARCH_H = dp(48)
STATUS_H = 45
GAP = dp(10)

# Алфавитный указатель — только КНОПКА в строке поиска (открывает сетку D13).
# Вертикальной полосы разделов у края списка нет: реш. оператора — полоса
# слишком мелкая и неудобная для тапа на e-ink.

# --- Палитра, как в colors.xml ---
C = dict(
    paper='#fff', ink='#000', ink2='#444', faint='#777', icon='#444',
    divider='#b8b8b8', divider2='#000',
    rowsel='#c8c8c8', badge='#fff', badgeink='#000',
    ph='#fff', phb='#666', phink='#000',
    tabact='#000', tabink='#333', sect='#808080',
    banner='#f2f2f2', bannerink='#000',
    btn='#fff', btnb='#000', disabled='#8a8a8a',
    dither='#404040',   # точки дизеринга пустого места под списком
)

# ==========================================================================
# ИКОНКИ. Сетка 24x24, обводка 1.9, скруглённые стыки.
# Элемент: ('p', d) путь штрихом;  ('pf', d) путь заливкой;
#          ('c', cx, cy, r) круг штрихом;  ('cf', cx, cy, r) круг заливкой.
# Здесь же источник для res/drawable/ic_<name>.xml (см. emit_vectors).
# ==========================================================================
ICONS = {
    # --- вкладки ---
    'authors': [('c', 12, 7.6, 3.5), ('p', 'M4.9 20c.8-3.5 3.6-5.5 7.1-5.5s6.3 2 7.1 5.5')],
    'genres': [('p', 'M20.2 12.7 12.7 20.2a1.8 1.8 0 0 1-2.6 0L4 14.1V4h10.1l6.1 6.1a1.8 1.8 0 0 1 0 2.6Z'),
               ('cf', 8.3, 8.3, 1.35)],
    'series': [('p', 'M12 3.8 3.7 8 12 12.2 20.3 8 12 3.8Z'), ('p', 'M3.7 12.4 12 16.6l8.3-4.2'),
               ('p', 'M3.7 16.6 12 20.8l8.3-4.2')],
    'date': [('p', 'M4.6 7.4h14.8a1.8 1.8 0 0 1 1.8 1.8v8.4a1.8 1.8 0 0 1-1.8 1.8H4.6a1.8 1.8 0 0 1-1.8-1.8V9.2a1.8 1.8 0 0 1 1.8-1.8Z'),
             ('p', 'M2.8 11.6h18.4'), ('p', 'M8.2 4.2v3.2'), ('p', 'M15.8 4.2v3.2')],
    'history': [('c', 12, 12, 8.3), ('p', 'M12 7.4V12l3.1 2')],
    'books': [('p', 'M4.4 4.6h5.4a1 1 0 0 1 1 1v13.8H5.4a1 1 0 0 1-1-1V4.6Z'),
              ('p', 'M19.6 4.6h-5.4a1 1 0 0 0-1 1v13.8h5.4a1 1 0 0 0 1-1V4.6Z')],
    # Знак приложения (запись бэклога №9): закрытая книга с закладкой.
    # Отдельный знак — не строчная иконка списка: читается на e-ink в 1-bit,
    # силуэт держится и в 32 px. Идёт в лаунчер, см. emit_vectors().
    'bookmark': [('p', 'M7 4.4h10a1.2 1.2 0 0 1 1.2 1.2v13.4a1.2 1.2 0 0 1-1.2 1.2H7'
                       'a1.2 1.2 0 0 1-1.2-1.2V5.6A1.2 1.2 0 0 1 7 4.4z'),
                 ('pf', 'M9.8 4.4h4.4v7.2l-2.2-1.6-2.2 1.6z')],
    # --- навигация и служебное ---
    'search': [('c', 10.8, 10.8, 6.2), ('p', 'M15.4 15.4 20.3 20.3')],
    'close': [('p', 'M6.6 6.6 17.4 17.4'), ('p', 'M17.4 6.6 6.6 17.4')],
    'back': [('p', 'M14.6 5.4 8 12l6.6 6.6')],
    'chevron': [('p', 'M9.4 5.4 16 12l-6.6 6.6')],
    'chevron_left': [('p', 'M14.6 5.4 8 12l6.6 6.6')],
    'chevron2_left': [('p', 'M11.4 6.6 5.8 12l5.6 5.4'), ('p', 'M18.2 6.6 12.6 12l5.6 5.4')],
    'chevron2_right': [('p', 'M5.8 6.6 11.4 12l-5.6 5.4'), ('p', 'M12.6 6.6 18.2 12l-5.6 5.4')],
    'chevron_down': [('p', 'M5.4 9.4 12 16l6.6-6.6')],
    'more': [('cf', 12, 5.6, 1.5), ('cf', 12, 12, 1.5), ('cf', 12, 18.4, 1.5)],
    'settings': [('p', 'M4.4 7.6h8.2'), ('p', 'M18.2 7.6h1.4'), ('p', 'M4.4 16.4h2.2'),
                 ('p', 'M12.2 16.4h7.4'), ('c', 15.4, 7.6, 2.4), ('c', 9.4, 16.4, 2.4)],
    # --- настройки/разделы ---
    'folder': [('p', 'M3.6 7.4a1.8 1.8 0 0 1 1.8-1.8h3.3l2.1 2.5h6.3a1.8 1.8 0 0 1 1.8 1.8v6.7a1.8 1.8 0 0 1-1.8 1.8H5.4a1.8 1.8 0 0 1-1.8-1.8V7.4Z')],
    'folder_plus': [('p', 'M3.6 7.4a1.8 1.8 0 0 1 1.8-1.8h3.3l2.1 2.5h6.3a1.8 1.8 0 0 1 1.8 1.8v6.7a1.8 1.8 0 0 1-1.8 1.8H5.4a1.8 1.8 0 0 1-1.8-1.8V7.4Z'),
                    ('p', 'M12 11.4v5'), ('p', 'M9.5 13.9h5')],
    'refresh': [('p', 'M19.8 12a7.8 7.8 0 1 1-2.4-5.6'), ('p', 'M17.5 3.4h3.1v3.1')],
    'trash': [('p', 'M4.6 6.9h14.8'),
              ('p', 'M9.4 6.9V5a1.3 1.3 0 0 1 1.3-1.3h2.6A1.3 1.3 0 0 1 14.6 5v1.9'),
              ('p', 'M6.6 6.9l1 12.4c.07.8.72 1.4 1.5 1.4h5.8c.78 0 1.43-.6 1.5-1.4l1-12.4'),
              ('p', 'M10.4 10.6v6.1'), ('p', 'M13.6 10.6v6.1')],
    'check': [('p', 'M5.2 12.6 10 17.4 18.8 6.6')],
    'book_open': [('p', 'M12 6.6C10.4 5.2 8.2 4.4 5.4 4.4H4v14.2h1.4c2.8 0 5 .8 6.6 2.2 1.6-1.4 3.8-2.2 6.6-2.2H20V4.4h-1.4c-2.8 0-5 .8-6.6 2.2Z'),
                  ('p', 'M12 6.6v14.2')],
    'type': [('p', 'M5.6 6.6V5h12.8v1.6'), ('p', 'M12 5v14.2'), ('p', 'M9.2 19.2h5.6')],
    'list': [('p', 'M4.3 7.2h.5'), ('p', 'M4.3 12h.5'), ('p', 'M4.3 16.8h.5'),
             ('p', 'M8.2 7.2h11.5'), ('p', 'M8.2 12h11.5'), ('p', 'M8.2 16.8h11.5')],
    'image': [('p', 'M4.6 5.6h14.8a1.8 1.8 0 0 1 1.8 1.8v9.2a1.8 1.8 0 0 1-1.8 1.8H4.6a1.8 1.8 0 0 1-1.8-1.8V7.4a1.8 1.8 0 0 1 1.8-1.8Z'),
              ('c', 9, 10.2, 1.5), ('p', 'M4.2 17.6 9.4 12.6l3.3 3.1 2.9-2.5 4.2 4')],
    'database': [('p', 'M4.9 6.4c0-1.5 3.2-2.7 7.1-2.7s7.1 1.2 7.1 2.7-3.2 2.7-7.1 2.7-7.1-1.2-7.1-2.7Z'),
                 ('p', 'M4.9 6.4v11.2c0 1.5 3.2 2.7 7.1 2.7s7.1-1.2 7.1-2.7V6.4'),
                 ('p', 'M4.9 12c0 1.5 3.2 2.7 7.1 2.7s7.1-1.2 7.1-2.7')],
    'key': [('c', 8.2, 15.4, 3.4), ('p', 'M10.6 13 19 4.6'), ('p', 'M16.3 7.3l2.2 2.2'),
            ('p', 'M14.1 9.5l2.2 2.2')],
    'activity': [('p', 'M3.6 12.6h3.9l2.4-7 3.6 13.2 2.4-6.2h4.5')],
    'info': [('c', 12, 12, 8.3), ('p', 'M12 11.2v5.4'), ('p', 'M12 7.8h.01')],
    'share': [('p', 'M9.2 5.6H5.6A1.6 1.6 0 0 0 4 7.2v11.2a1.6 1.6 0 0 0 1.6 1.6H16.8a1.6 1.6 0 0 0 1.6-1.6V14.8'),
              ('p', 'M14 4h6v6'), ('p', 'M20 4l-8.2 8.2')],
    'alert': [('p', 'M12 4.6 3.6 19h16.8L12 4.6Z'), ('p', 'M12 10.2v4'),
              ('p', 'M12 16.6h.01')],
    'copy': [('p', 'M8.6 8.6h10a1.8 1.8 0 0 1 1.8 1.8v8a1.8 1.8 0 0 1-1.8 1.8h-10a1.8 1.8 0 0 1-1.8-1.8v-8a1.8 1.8 0 0 1 1.8-1.8Z'),
             ('p', 'M15.4 5.8H5.4a1.8 1.8 0 0 0-1.8 1.8v9.6')],
    'download': [('p', 'M12 4.6v10.2'), ('p', 'M7.8 10.6 12 14.8l4.2-4.2'),
                 ('p', 'M4.6 18.6h14.8')],
    'stop': [('p', 'M6.6 6.6h10.8v10.8H6.6z')],
    # Радио-выбор читалки в диалоге D1 (пустой / выбранный)
    'radio_off': [('c', 12, 12, 8.6)],
    'radio_on': [('c', 12, 12, 8.6), ('cf', 12, 12, 4.6)],
}

STROKE = 1.9


def svg_icon(name, size_px, color=None):
    """Инлайн-SVG для макета: та же геометрия, что у VectorDrawable."""
    color = color or C['icon']
    parts = []
    for el in ICONS[name]:
        if el[0] == 'p':
            parts.append('<path d="%s" fill="none" stroke="%s"/>' % (el[1], color))
        elif el[0] == 'pf':
            parts.append('<path d="%s" fill="%s"/>' % (el[1], color))
        elif el[0] == 'c':
            parts.append('<circle cx="%s" cy="%s" r="%s" fill="none" stroke="%s"/>'
                         % (el[1], el[2], el[3], color))
        elif el[0] == 'cf':
            parts.append('<circle cx="%s" cy="%s" r="%s" fill="%s"/>'
                         % (el[1], el[2], el[3], color))
    return ('<svg class="ic" width="%d" height="%d" viewBox="0 0 24 24" '
            'stroke-width="%s" stroke-linecap="round" stroke-linejoin="round">%s</svg>'
            % (size_px, size_px, STROKE, ''.join(parts)))


# ==========================================================================
# Строки интерфейса. key -> (имя ресурса, ru, en)
# ==========================================================================
S_ = {
    'app_name':        ('app_name', 'InkShelf', 'InkShelf'),
    'app_tagline':     ('app_tagline', 'Каталог книг для e-ink', 'E-ink book catalog'),
    'label_value':     ('label_value_format', '%1$s: %2$s', '%1$s: %2$s'),
    'cancel':          ('action_cancel', 'Отмена', 'Cancel'),
    'yes':             ('value_yes', 'Да', 'Yes'),
    'no':              ('value_no', 'Нет', 'No'),
    'search_hint':     ('search_hint', 'Поиск: название, автор, серия', 'Search: title, author, series'),
    'search_label':    ('search_label', 'Поиск', 'Search'),
    'cd_open_settings': ('cd_open_settings', 'Настройки', 'Settings'),
    'cd_clear_search': ('cd_clear_search', 'Очистить поиск', 'Clear search'),
    'cd_tab_left':     ('cd_tab_scroll_left', 'Прокрутить вкладки влево', 'Scroll tabs left'),
    'cd_tab_right':    ('cd_tab_scroll_right', 'Прокрутить вкладки вправо', 'Scroll tabs right'),
    'cd_back':         ('cd_back', 'Назад', 'Back'),
    'cd_more':         ('cd_more', 'Ещё', 'More'),
    'cd_group':        ('cd_open_group', 'Открыть', 'Open'),
    # --- вкладки ---
    'tab_authors':     ('tab_authors', 'Авторы', 'Authors'),
    'tab_genres':      ('tab_genres', 'Жанры', 'Genres'),
    'tab_series':      ('tab_series', 'Серии', 'Series'),
    'tab_date':        ('tab_date', 'Дата', 'Date'),
    'tab_history':     ('tab_history', 'История', 'History'),
    'tab_all':         ('tab_all_books', 'Все книги', 'All books'),
    # --- сортировка/фильтр ---
    'sort_title':      ('sort_by_title', 'по названию', 'by title'),
    'sort_author':     ('sort_by_author', 'по автору', 'by author'),
    'sort_series_no':  ('sort_by_series_number', 'по номеру в серии', 'by series number'),
    'sort_series':     ('sort_by_series', 'по серии', 'by series'),
    'series_none':     ('series_none', 'Без серии', 'No series'),
    'sort_file_date':  ('sort_by_file_date', 'по дате файла', 'by file date'),
    'sort_index_date': ('sort_by_index_date', 'по дате индексации', 'by indexing date'),
    'sort_prefix':     ('group_sort_prefix', 'Сортировка: %1$s', 'Sort: %1$s'),
    'filter_available': ('filter_available_only', 'только доступные', 'available only'),
    # --- группы даты / история ---
    'date_today':      ('date_today', 'Сегодня', 'Today'),
    'date_yesterday':  ('date_yesterday', 'Вчера', 'Yesterday'),
    'date_week':       ('date_this_week', 'На этой неделе', 'This week'),
    'date_month':      ('date_this_month', 'В этом месяце', 'This month'),
    'date_earlier':    ('date_earlier', 'Раньше', 'Earlier'),
    'history_recent':  ('history_recently_opened', 'Недавно открытые', 'Recently opened'),
    # --- состояния S1 ---
    'st_no_roots':     ('state_no_roots', 'Папки с книгами не заданы.\nДобавьте папку с книгами.',
                        'No book folders yet.\nAdd a folder with books.'),
    'btn_add_folder':  ('action_add_folder', 'Добавить папку', 'Add folder'),
    'st_index_empty':  ('state_index_empty', 'Библиотека ещё не проиндексирована.',
                        'The library is not indexed yet.'),
    'st_indexing':     ('state_indexing', 'Идёт индексация…', 'Indexing…'),
    'btn_start_scan':  ('action_start_scan', 'Начать сканирование', 'Start scanning'),
    'st_nothing_found': ('state_nothing_found', 'Ничего не найдено', 'Nothing found'),
    'st_history_empty': ('state_history_empty', 'Откройте любую книгу', 'Open any book'),
    'warn_partial':    ('warn_partial_unavailable', 'Часть книг недоступна — проверьте папки с книгами.',
                        'Some books are unavailable — check the book folders.'),
    'warn_no_access':  ('warn_no_file_access', 'Нет доступа к файлам — нажмите, чтобы разрешить.',
                        'No file access — tap to allow.'),
    'scan_footer':     ('scan_progress_footer', 'Индексация: просканировано %1$s · найдено %2$s · добавлено %3$s',
                        'Indexing: scanned %1$s · found %2$s · added %3$s'),
    'found_count':     ('search_found_count', 'Найдено: %1$s', 'Found: %1$s'),
    'found_by':        ('search_found_in', 'по названию, авторам, серии', 'in titles, authors, series'),
    # --- S3 карточка ---
    'title_book':      ('title_book', 'Книга', 'Book'),
    'title_group':     ('title_group', 'Книги группы', 'Group books'),
    'card_authors':    ('card_authors', 'Авторы', 'Authors'),
    'card_series':     ('card_series', 'Серии', 'Series'),
    'card_genres':     ('card_genres', 'Жанры', 'Genres'),
    'card_format':     ('card_format', 'Формат', 'Format'),
    'card_path':       ('card_path', 'Путь', 'Path'),
    'card_annotation': ('card_annotation', 'Аннотация', 'Annotation'),
    'card_size':       ('card_size', 'Размер', 'Size'),
    'card_opened':     ('card_opened_at', 'Открывался', 'Last opened'),
    'btn_open':        ('action_open', 'Открыть', 'Open'),
    'btn_open_with':   ('action_open_with', 'Открыть в…', 'Open with…'),
    'btn_delete_file': ('action_delete_file', 'Удалить файл', 'Delete file'),
    'title_cover':     ('title_cover', 'Обложка', 'Cover'),
    'hint_cover_close': ('hint_cover_close', 'Нажмите на обложку, чтобы закрыть', 'Tap the cover to close'),
    'cd_cover_view':   ('cd_cover_view', 'Открыть обложку', 'Open cover'),
    # --- S4 настройки ---
    'title_settings':  ('title_settings', 'Настройки', 'Settings'),
    'sec_library':     ('section_library', 'Библиотека', 'Library'),
    'sec_reading':     ('section_reading', 'Чтение', 'Reading'),
    'sec_appearance':  ('section_appearance', 'Оформление', 'Appearance'),
    'sec_system':      ('section_access_service', 'Доступ и сервис', 'Access & service'),
    'set_roots':       ('setting_roots', 'Папки с книгами', 'Book folders'),
    'set_rescan':      ('setting_rescan', 'Пересканировать библиотеку', 'Rescan library'),
    'set_clear':       ('setting_clear_missing', 'Очистить отсутствующие', 'Remove missing'),
    'set_reader':      ('setting_default_reader', 'Читалка по умолчанию', 'Default reader'),
    'set_font':        ('setting_font_size', 'Размер шрифта', 'Font size'),
    'set_density':     ('setting_list_density', 'Плотность списка', 'List density'),
    'density_normal':  ('density_normal', 'Обычный', 'Normal'),
    'density_compact': ('density_compact', 'Компактный', 'Compact'),
    'set_author_fmt':  ('setting_author_format', 'Формат имени автора', 'Author name format'),
    'author_first':    ('author_format_first_last', 'Имя Фамилия', 'First Last'),
    'author_last':     ('author_format_last_first', 'Фамилия Имя', 'Last First'),
    'set_covers':      ('setting_show_covers', 'Показывать обложки', 'Show covers'),
    'set_cache':       ('setting_cover_cache', 'Размер кэша обложек', 'Cover cache size'),
    'set_access':      ('setting_file_access', 'Доступ к файлам', 'File access'),
    'access_granted':  ('access_granted', 'Разрешён', 'Granted'),
    'access_denied':   ('access_denied', 'Не разрешён', 'Not granted'),
    # --- D2 запрос доступа к файлам (нет MANAGE_EXTERNAL_STORAGE) ---
    'dlg_access':      ('dlg_file_access', 'Доступ к файлам', 'File access'),
    'access_text':     ('dlg_file_access_text',
                        'Чтобы найти и проиндексировать книги, приложению нужен доступ '
                        'к файлам на устройстве.',
                        'To find and index your books, the app needs access to files on the device.'),
    'access_saf_hint': ('dlg_file_access_saf_hint',
                        'Если полный доступ недоступен, добавьте папку через системное окно выбора.',
                        'If full access is unavailable, add a folder via the system picker.'),
    'btn_grant':       ('action_grant_access', 'Разрешить доступ', 'Grant access'),
    'btn_pick_saf':    ('action_pick_folder', 'Выбрать папку', 'Choose folder'),
    'btn_later':       ('action_later', 'Позже', 'Later'),
    'btn_sys_pick':    ('action_system_picker', 'Системный выбор', 'System picker'),
    'set_diagnostics': ('setting_diagnostics', 'Диагностика', 'Diagnostics'),
    'set_about':       ('setting_about', 'О программе', 'About'),
    'reader_none':     ('reader_none', 'не выбрана', 'not set'),
    'value_mb':        ('value_mb', '%1$s МБ', '%1$s MB'),
    'toast_scan_started': ('toast_scan_started', 'Индексация запущена', 'Scan started'),
    'toast_catalog_cleaned': ('toast_catalog_cleaned', 'Каталог очищен', 'Catalog cleaned'),
    # --- S5 выбор папки ---
    'title_pick':      ('title_pick_folder', 'Выбор папки', 'Choose folder'),
    'btn_up':          ('action_up', 'Вверх', 'Up'),
    'btn_pick_here':   ('action_pick_this_folder', 'Выбрать эту папку', 'Select this folder'),
    # --- S9 папки с книгами ---
    'title_roots':     ('title_root_folders', 'Папки с книгами', 'Book folders'),
    'root_kind_file':  ('root_kind_file', 'файл', 'file'),
    'root_kind_saf':   ('root_kind_saf', 'системная папка', 'system folder'),
    'root_no_access':  ('root_state_no_access', 'нет доступа', 'no access'),
    'root_volume_off': ('root_state_volume_off', 'том отключён', 'volume off'),
    'root_scanned':    ('root_scanned', 'скан %1$s', 'scanned %1$s'),
    'root_never':      ('root_never_scanned', 'ещё не сканировался', 'not scanned yet'),
    'cd_delete_root':  ('cd_delete_root', 'Удалить папку', 'Delete folder'),
    'note_roots':      ('note_roots',
                        'Удаление убирает папку и её книги из каталога; '
                        'файлы на диске не трогаются.',
                        'Deleting a folder removes it and its books from the catalog; '
                        'files on disk are left untouched.'),
    # --- D9 удаление папки ---
    'dlg_root_del':    ('dlg_delete_root', 'Удалить папку?', 'Delete folder?'),
    'btn_delete_root': ('action_delete_root', 'Удалить папку', 'Delete folder'),
    'root_del_warn':   ('dlg_delete_root_warn',
                        'Папка «%1$s» и её книги (%2$s) исчезнут из каталога.',
                        'The folder “%1$s” and its books (%2$s) will disappear from the catalog.'),
    'root_del_hint':   ('dlg_delete_root_hint',
                        'Файлы на диске не удаляются — папку можно добавить снова.',
                        'Files on disk are not deleted — the folder can be added again.'),
    # --- S6 диагностика ---
    'title_diag':      ('title_diagnostics', 'Диагностика', 'Diagnostics'),
    'blk_device':      ('diag_device', 'Устройство', 'Device'),
    'k_model':         ('diag_model', 'Модель', 'Model'),
    'k_android':       ('diag_android', 'Android', 'Android'),
    'k_app_version':   ('diag_app_version', 'Версия приложения', 'App version'),
    'blk_index':       ('diag_index', 'Индекс', 'Index'),
    'k_total_books':   ('diag_total_books', 'Всего книг', 'Total books'),
    'k_agr':           ('diag_authors_genres_series', 'Авторов / жанров / серий', 'Authors / genres / series'),
    'k_no_meta':       ('diag_without_metadata', 'Без метаданных', 'Without metadata'),
    'k_missing':       ('diag_unavailable', 'Недоступных', 'Unavailable'),
    'k_covers':        ('diag_covers_cached', 'Обложек в кэше', 'Covers cached'),
    'blk_roots':       ('diag_roots', 'Папки с книгами', 'Book folders'),
    'roots_available': ('diag_root_available', '%1$s · доступен', '%1$s · available'),
    'blk_readers':     ('diag_readers', 'Читалки', 'Readers'),

    'btn_copy_report': ('action_copy_report', 'Скопировать отчёт', 'Copy report'),
    'btn_export_logs': ('action_export_logs', 'Выгрузить логи', 'Export logs'),
    'toast_report_copied': ('toast_report_copied', 'Отчёт скопирован', 'Report copied'),
    'toast_report_failed': ('toast_report_failed', 'Не удалось получить отчёт',
                            "Couldn't get the report"),
    'toast_logs_exported': ('toast_logs_exported', 'Логи выгружены: %1$s', 'Logs exported: %1$s'),
    'toast_logs_failed': ('toast_logs_failed', 'Не удалось выгрузить логи',
                          "Couldn't export logs"),
    # --- S7 о программе ---
    'title_about':     ('title_about', 'О программе', 'About'),
    'about_version':   ('about_version', 'версия %1$s', 'version %1$s'),
    'about_text':      ('about_text',
                        'Каталог локальной библиотеки книг для e-ink. Индексирует папки, показывает '
                        'авторов, жанры и серии и открывает книгу в выбранной читалке. Сам книги не читает.',
                        'An e-ink catalog for a local book library. Indexes folders, shows authors, genres '
                        'and series, and opens a book in the chosen reader. It does not read books itself.'),
    'about_made':      ('about_made',
                        'Написано в OpenCode моделью DeepSeek V4.1 Flash.',
                        'Written in OpenCode with the DeepSeek V4.1 Flash model.'),
    # --- D1 «Открыть в…» ---
    'dlg_open_with':   ('dlg_open_with', 'Открыть в…', 'Open with…'),
    'no_reader':       ('state_no_reader', 'Нет приложения, чтобы открыть файл',
                        'No app to open this file'),
    'open_in_fm':      ('action_open_in_file_manager', 'Открыть в проводнике', 'Open in file manager'),
    # --- D3 прогресс ---
    'dlg_scan':        ('dlg_indexing', 'Индексация библиотеки', 'Indexing the library'),
    'scan_root':       ('dlg_indexing_root', 'Папка: %1$s', 'Folder: %1$s'),
    'scan_phase':      ('dlg_indexing_phase', 'Читаем метаданные… %1$s из %2$s', 'Reading metadata… %1$s of %2$s'),
    'btn_collapse':    ('action_collapse', 'Свернуть', 'Collapse'),
    'btn_stop':        ('action_stop', 'Стоп', 'Stop'),
    # --- D4 очистка ---
    'dlg_clear':       ('dlg_clear_missing', 'Очистить отсутствующие', 'Remove missing'),
    'clear_intro':     ('dlg_clear_intro', 'Будет удалено:', 'Will be removed:'),
    'clear_books':     ('dlg_clear_books', 'книг, чьих файлов нет на месте — %1$s',
                        'books whose files are gone — %1$s'),
    'clear_groups':    ('dlg_clear_groups', 'пустых групп (авторы/жанры/серии) — %1$s',
                        'empty groups (authors/genres/series) — %1$s'),
    'clear_warn':      ('dlg_clear_warning', 'Метаданные удалённых книг будут потеряны.',
                        'Metadata of the removed books will be lost.'),
    'btn_clear':       ('action_clear', 'Очистить', 'Remove'),
    # --- D11 удаление файла ---
    'dlg_delete':      ('dlg_delete_file', 'Удалить файл', 'Delete file'),
    'del_file':        ('dlg_delete_file_label', 'Файл', 'File'),
    'del_path':        ('dlg_delete_path_label', 'Путь', 'Path'),
    'del_size':        ('dlg_delete_size_label', 'Размер', 'Size'),
    'del_opened':      ('dlg_delete_opened_label', 'Открывался', 'Last opened'),
    'del_warn':        ('dlg_delete_warning',
                        'Файл будет удалён с устройства безвозвратно, а запись — из библиотеки. '
                        'Авторы, серии и жанры, у которых не останется книг, исчезнут из списков.',
                        'The file will be permanently deleted from the device and the record — from '
                        'the library. Authors, series and genres left without books will disappear '
                        'from the lists.'),
    'btn_delete_catalog': ('action_delete_from_catalog', 'Только из каталога', 'Catalog only'),
    'btn_delete_confirm': ('action_delete_confirm', 'Удалить файл', 'Delete file'),
    # --- пагинация ---
    'pag_range':       ('pagination_range', '%1$s–%2$s из %3$s', '%1$s–%2$s of %3$s'),
    'pag_page_of':     ('pagination_page_of', 'Страница %1$s из %2$s', 'Page %1$s of %2$s'),
    'pag_per_page':    ('pagination_per_page_dynamic', 'строк на странице — по высоте экрана',
                        'rows per page — by screen height'),
    'pag_left':        ('pagination_left', 'осталось %1$s', '%1$s left'),
    'cd_first_page':   ('cd_first_page', 'В начало', 'First page'),
    'cd_last_page':    ('cd_last_page', 'В конец', 'Last page'),
    'cd_prev_page':    ('cd_prev_page', 'Предыдущая страница', 'Previous page'),
    'cd_next_page':    ('cd_next_page', 'Следующая страница', 'Next page'),
    'st_loading':      ('state_loading', 'Загрузка…', 'Loading…'),
    'fts_ok':          ('fts_ok', 'FTS4 + unicode61: работает', 'FTS4 + unicode61: OK'),
    'fts_fail':        ('fts_fail', 'FTS4 недоступен', 'FTS4 unavailable'),
    'demo_query':      ('demo_query', 'война', 'война'),
    'pag_note_pages':  ('pagination_note_pages',
                        'Страница %1$s из %2$s. Число строк зависит от шрифта и высоты экрана. '
                        'Свайп: справа налево — вперёд, слева направо — назад. '
                        'Кнопки на корпусе: верхняя — назад, нижняя — вперёд.',
                        'Page %1$s of %2$s. Rows per page depend on font and screen height. '
                        'Swipe right-to-left — next, left-to-right — previous. '
                        'Side buttons: top — previous, bottom — next.'),
    'pag_note_group':  ('pagination_note_group',
                        'Страница %1$s из %2$s. Заголовок группы повторяется на новой странице.',
                        'Page %1$s of %2$s. A group header repeats on the new page.'),
    'dlg_jump':        ('dlg_page_jump', 'Перейти к странице', 'Go to page'),    'dlg_jump_label':  ('dlg_page_jump_label', 'Номер страницы', 'Page number'),
    'dlg_jump_range':  ('dlg_page_jump_range', 'от 1 до %1$s', 'from 1 to %1$s'),
    'btn_go':          ('action_go', 'Перейти', 'Go'),
    'menu_jump':       ('menu_jump_to_page', 'Перейти к странице…', 'Go to page…'),
    'menu_first':      ('menu_first_page', 'В начало списка', 'First page'),
    'menu_last':       ('menu_last_page', 'В конец списка', 'Last page'),
    'dlg_alpha':       ('dlg_alphabet_index', 'Алфавитный указатель', 'Alphabetical index'),
    'cd_alpha':        ('cd_alphabet_index', 'Открыть указатель букв', 'Open letter index'),
    'toast_first':     ('toast_first_page', 'Это первая страница', 'This is the first page'),
    'toast_last':      ('toast_last_page', 'Это последняя страница', 'This is the last page'),
    'dlg_sort':        ('dlg_sort', 'Сортировка', 'Sort'),
    # --- N2 тосты ---
    'toast_index_updated': ('toast_index_updated', 'Индекс обновлён', 'Index updated'),
    'toast_root_added': ('toast_root_added', 'Папка добавлена', 'Folder added'),
    'toast_file_deleted': ('toast_file_deleted', 'Файл удалён', 'File deleted'),
    'toast_file_delete_failed': ('toast_file_delete_failed', 'Не удалось удалить файл',
                                 'Could not delete file'),
    # --- D1 «Открыть в…» / D5 ошибка запуска / D6 меню книги / D10 нет читалок ---
    'reader_default':  ('reader_default_mark', 'по умолчанию', 'default'),
    'dlg_reader_default': ('dlg_default_reader', 'Читалка по умолчанию', 'Default reader'),
    'reader_default_hint': ('dlg_default_reader_hint',
                            'Выбранная читалка станет основной. Изменить можно в настройках.',
                            'The chosen reader becomes the default. You can change it in settings.'),
    'dlg_open_error':  ('dlg_open_error', 'Не удалось открыть книгу', 'Could not open the book'),
    'open_error_reader': ('dlg_open_error_reader', 'Не удалось запустить «%1$s».',
                          'Could not start “%1$s”.'),
    'action_choose_reader': ('action_choose_reader', 'Выбрать другую читалку', 'Choose another reader'),
    'dlg_no_readers':  ('dlg_no_readers', 'Нет читалок', 'No readers'),
    'no_readers_text': ('dlg_no_readers_text',
                        'На устройстве не найдено ни одной читалки. Установите приложение для '
                        'чтения FB2/EPUB/MOBI — и оно появится в списке.',
                        'No reader app found on the device. Install an app that reads '
                        'FB2/EPUB/MOBI and it will appear in the list.'),
    'action_ok':       ('action_ok', 'ОК', 'OK'),
    'action_info':     ('action_info', 'Информация', 'Info'),
    'action_show_file': ('action_show_file', 'Показать файл в папке', 'Show file in folder'),
    'no_fm':           ('state_no_file_manager', 'Нет приложения-проводника',
                        'No file manager app'),
}

# Множественные формы: key -> (имя ресурса, {lang: (one, few, many)})
P_ = {
    'books':   ('books_count', {'ru': ('книга', 'книги', 'книг'), 'en': ('book', 'books', 'books')}),
    'folders': ('folders_count', {'ru': ('папка', 'папки', 'папок'), 'en': ('folder', 'folders', 'folders')}),
    'files':   ('files_count', {'ru': ('файл', 'файла', 'файлов'), 'en': ('file', 'files', 'files')}),
    'groups':  ('groups_count', {'ru': ('группа', 'группы', 'групп'), 'en': ('group', 'groups', 'groups')}),
    'readers': ('readers_count', {'ru': ('читалка', 'читалки', 'читалок'), 'en': ('reader', 'readers', 'readers')}),
}


def tr(key):
    """Строка интерфейса на текущем языке."""
    return S_[key][1] if LANG == 'ru' else S_[key][2]


_POS = re.compile(r'%(\d+)\$([sd])')


def fmt(s, *args):
    """Позиционное форматирование как в Android: '%1$s–%2$s из %3$s'.
    В Python %1$s не поддерживается, поэтому подменяем на %(1)s и т.д."""
    out = _POS.sub(lambda m: '%%(%s)%s' % (m.group(1), m.group(2)), s)
    return out % {str(i + 1): a for i, a in enumerate(args)}


def plural(kind, n):
    forms = P_[kind][1][LANG]
    if LANG == 'ru':
        if n % 10 == 1 and n % 100 != 11:
            return forms[0]
        if n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14):
            return forms[1]
        return forms[2]
    return forms[0] if n == 1 else forms[1]


def num(n):
    """Разряды: 41208 -> '41 208' (ru) / '41,208' (en)."""
    sep = '\u00a0' if LANG == 'ru' else ','
    return '{:,}'.format(int(n)).replace(',', sep)


def dec(x):
    """Десятичный разделитель: 41.2 -> '41,2' (ru) / '41.2' (en)."""
    return ('%s' % x).replace('.', ',' if LANG == 'ru' else '.')


def n_plural(kind, n):
    """'677 книг' / '677 books'."""
    return '%s %s' % (num(n), plural(kind, n))


CSS = """
*{margin:0;padding:0;box-sizing:border-box}
html,body{background:#7d7d7d;font-family:"Noto Sans","DejaVu Sans",sans-serif}
.screen{position:relative;width:%(W)dpx;height:%(H)dpx;background:%(paper)s;color:%(ink)s;
        overflow:hidden;display:flex;flex-direction:column}
.ic{flex:0 0 auto;vertical-align:middle}
.ic.text{vertical-align:-0.18em}
.statusbar{height:%(SH)dpx;flex:0 0 auto;display:flex;align-items:center;justify-content:space-between;
           padding:0 %(phx)dpx;font-size:22px;color:#333}
.statusbar .right{display:flex;align-items:center;gap:10px}
.batt{width:52px;height:24px;border:2px solid #333;border-radius:3px;position:relative}
.batt:after{content:"";position:absolute;right:-7px;top:6px;width:4px;height:12px;background:#333}
.batt i{position:absolute;left:2px;top:2px;bottom:2px;width:60%%;background:#333}
.topbar{flex:0 0 auto;display:flex;align-items:center;gap:%(ss)dpx;min-height:%(touch)dpx;
        padding:%(sm)dpx %(phx)dpx;border-bottom:1px solid %(divider)s}
.topbar .back{width:%(touch)dpx;min-height:%(touch)dpx;display:flex;align-items:center;
              justify-content:flex-start;margin-left:-%(sm)dpx}
.topbar .ttl{%(ttitle)s;font-weight:700}
.topbar .sp{flex:1}
.topbar .act{width:%(touch)dpx;min-height:%(touch)dpx;display:flex;align-items:center;
             justify-content:flex-end}
.searchbar{flex:0 0 auto;display:flex;align-items:center;gap:%(sm)dpx;min-height:%(search)dpx;
           padding:0 %(phx)dpx;border-bottom:1px solid %(divider)s}
.searchbar .hint{color:#777;%(tbody)s}
.searchbar .q{color:%(ink)s;%(tbody)s}
.searchbar .sp{flex:1}
.searchbar .x,.searchbar .idx,.searchbar .gear{width:%(touch)dpx;min-height:%(touch)dpx;
                               display:flex;align-items:center;justify-content:flex-end}
.tabwrap{position:relative;border-bottom:1px solid %(divider)s}
.tabs{flex:0 0 auto;display:flex;flex-wrap:nowrap;overflow:hidden}
.tab{display:flex;align-items:center;justify-content:center;gap:%(ss)dpx;min-height:%(tabh)dpx;
     padding:0 %(tabph)dpx;%(ttab)s;color:%(tabink)s;border-bottom:4px solid transparent}
.tab.on{color:#fff;background:%(tabact)s;border-bottom-color:%(ink)s;font-weight:700}
/* указатель прокрутки вкладок (вариант B): у края со скрытыми вкладками */
.tmore{position:absolute;top:0;bottom:0;width:%(tabmore)dpx;display:flex;align-items:center;
       justify-content:center;background:%(paper)s}
.tmore svg{display:block}
.tmore.l{left:0;border-right:1px solid %(divider)s}
.tmore.l svg{transform:scaleX(-1)}
.tmore.r{right:0;border-left:1px solid %(divider)s}
.banner{flex:0 0 auto;background:%(banner)s;color:%(bannerink)s;padding:%(pv)dpx %(phx)dpx;
        %(tmeta)s;display:flex;align-items:center;gap:%(sm)dpx;border-bottom:2px solid %(ink)s}
.banner b{font-weight:700}
.list{flex:1 1 auto;overflow:hidden;background-color:%(paper)s;
      background-image:radial-gradient(%(dither)s 1px,transparent 1.2px);
      background-size:10px 10px}
/* Область свободной прокрутки (карточка книги и др.): содержимое выше экрана
   обрезается, а у правого края остаётся дорожка с бегунком — видно, что ниже
   есть ещё. В приложении это ScrollView с постоянным (не затухающим) скроллбаром. */
.scrollwrap{flex:1 1 auto;min-height:0;position:relative;overflow:hidden}
.vsb{position:absolute;right:%(sbr)dpx;top:%(ss)dpx;bottom:%(ss)dpx;width:%(sbw)dpx;
     background:%(sbtrack)s;border-radius:%(sbrr)dpx}
.vsb i{position:absolute;left:0;width:100%%;background:%(sbthumb)s;border-radius:%(sbrr)dpx}
.row{display:flex;align-items:center;gap:%(gap)dpx;padding:%(pv)dpx %(phx)dpx;
     min-height:%(touch)dpx;border-bottom:1px solid %(divider)s}
/* сплошной белый фон под содержимым: дизеринг виден ТОЛЬКО на пустом месте внизу */
.row,.center,.block,.card{background:%(paper)s}
.row.sel{background:%(rowsel)s}
.row .grow{flex:1;min-width:0}
.row .lead{width:%(lead)dpx;display:flex;align-items:center;justify-content:center}
.row .name{%(tbody)s}
.row .sub{%(tmeta)s;color:%(ink2)s;margin-top:5px}
.row .ttl{%(ttitle)s;font-weight:700}
.row .chev{display:flex;align-items:center;justify-content:flex-end;width:24px}
/* S9: строка папки — иконка слева, корзина справа, путь переносится */
.row .name.wrap{overflow-wrap:anywhere}
.row .ract{width:%(touch)dpx;min-height:%(touch)dpx;display:flex;align-items:center;justify-content:flex-end;flex:0 0 auto}
.row.warn .name,.row.warn .sub{color:%(disabled)s}
.badge{background:%(badge)s;color:%(badgeink)s;%(tcount)s;font-weight:700;
       padding:4px %(ss)dpx;border:1px solid %(ink)s;border-radius:20px;white-space:nowrap}
.row.missing .ttl,.row.missing .name{color:%(disabled)s}
.row.missing .sub{color:%(disabled)s}
.agrid{display:grid;grid-template-columns:repeat(8,minmax(0,1fr));gap:%(sm)dpx}
.agrid .lbtn{min-height:%(touch)dpx;display:flex;align-items:center;justify-content:center;
             border:1px solid %(btnb)s;border-radius:4px;background:%(btn)s;%(tbody)s;font-weight:700}
.agrid .lbtn.on{background:%(ink)s;color:#fff}
.agrid .lbtn.off{opacity:.3}
.coverbox{flex:0 0 auto;width:%(cw)dpx;height:%(ch)dpx;background:%(ph)s;border:1.5px solid %(phb)s;
          border-radius:3px;display:flex;align-items:center;justify-content:center;
          %(tph)s;color:%(phink)s;font-weight:700}
.coverbox.c{width:%(cwc)dpx;height:%(chc)dpx}
.appsq{flex:0 0 auto;width:38px;height:38px;border-radius:8px;border:1.6px solid %(icon)s;
       display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:700;color:%(icon)s}
.sect{background:%(sect)s;color:#fff;font-weight:700;%(tmeta)s;
      padding:%(ss)dpx %(phx)dpx;border-bottom:1px solid %(divider)s}
.letter{background:%(sect)s;color:#fff;font-weight:700;%(theader)s;
        padding:%(ss)dpx %(phx)dpx;border-bottom:1px solid %(divider)s}
.center{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;
        gap:%(m)dpx;padding:%(xl)dpx;text-align:center}
.center .msg{%(tbody)s;color:%(ink2)s;white-space:pre-line}
.center .msg.big{%(ttitle)s;color:%(ink)s;font-weight:700}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:%(ss)dpx;
     min-height:%(touch)dpx;padding:0 %(bp)dpx;background:%(btn)s;border:1px solid %(btnb)s;
     border-radius:4px;color:%(ink)s;%(tbody)s}
.footer{flex:0 0 auto;display:flex;align-items:center;gap:%(ss)dpx;border-top:1px solid %(ink)s;
        background:%(paper)s;padding:%(pv)dpx %(phx)dpx;%(tmeta)s;color:%(ink2)s}
.footer .ftx{flex:1;min-width:0;line-height:1.25}
.footer .ic{flex:0 0 auto}
.hl{background:#000;color:#fff}
.overlay{position:absolute;inset:0;background:rgba(0,0,0,.35);display:flex;
         align-items:center;justify-content:center;padding:%(xl)dpx}
.dlg{background:%(paper)s;border:1px solid %(divider2)s;border-radius:6px;width:88%%;overflow:hidden}
.dlg h3{%(theader)s;font-weight:700;padding:%(m)dpx %(dpad)dpx %(ss)dpx;
        display:flex;align-items:center;gap:%(sm)dpx}
.dlg .items{max-height:760px;overflow:hidden}
.dlg .it{display:flex;align-items:center;gap:%(m)dpx;padding:%(pv)dpx %(dpad)dpx;
         border-top:1px solid %(divider)s;%(tbody)s}
.dlg .it.on{font-weight:700}
.dlg .it .sp{flex:1}
.dlg .it .radio{width:34px;height:34px;border-radius:50%%;border:3px solid %(ink)s;position:relative;flex:0 0 auto}
.dlg .it.on .radio:after{content:"";position:absolute;inset:6px;background:%(ink)s;border-radius:50%%}
.dlg .it .radiox{width:34px;height:34px;flex:0 0 auto}
.dlg .it .def{%(tmeta)s;color:%(faint)s;font-weight:400}
.dlg .body{padding:%(ss)dpx %(dpad)dpx;%(tbody)s;color:%(ink2)s;line-height:1.45}
.dlg .body b{color:%(ink)s}
.dlg .body .line{display:flex;gap:%(ss)dpx;align-items:flex-start}
.dlg .acts{display:flex;justify-content:flex-end;gap:%(ss)dpx;padding:%(pv)dpx %(sm)dpx;
           border-top:1px solid %(divider)s}
.dlg .acts .btn{background:transparent;border:none;font-weight:700}
.progbar{height:14px;background:#e0e0e0;border:1px solid %(ink)s;border-radius:3px;overflow:hidden;margin:%(sm)dpx 0}
.progbar i{display:block;height:100%%;width:38%%;background:%(ink)s}
.card{padding:%(m)dpx %(phx)dpx;display:flex;gap:%(m)dpx}
.card .big{width:%(cwbig)dpx;height:%(chbig)dpx;background:%(ph)s;border:1px solid %(phb)s;
           border-radius:3px;display:flex;align-items:center;justify-content:center;
           font-size:96px;color:%(phink)s;font-weight:700;flex:0 0 auto}
/* S8: полноэкранный просмотр обложки (тап по обложке в карточке) */
.coverview{flex:1;display:flex;flex-direction:column;background:%(paper)s}
.cvtop{flex:0 0 auto;display:flex;align-items:center;min-height:%(touch)dpx;
       padding:0 %(phx)dpx;border-bottom:1px solid %(divider)s}
.cvtop .x{width:%(touch)dpx;min-height:%(touch)dpx;display:flex;align-items:center;justify-content:center}
.cvtop .ttl{flex:1;text-align:center;%(ttitle)s;font-weight:700}
.cvtop .spc{width:%(touch)dpx;flex:0 0 auto}
.cvbody{flex:1;display:flex;align-items:center;justify-content:center;padding:%(m)dpx}
.cvbox{width:%(cwview)dpx;height:%(chview)dpx;background:%(ph)s;border:1px solid %(phb)s;
       border-radius:3px;display:flex;align-items:center;justify-content:center;
       font-size:120px;color:%(phink)s;font-weight:700}
.cvhint{flex:0 0 auto;text-align:center;%(tmeta)s;color:%(faint)s;padding:%(pv)dpx}
.kv{display:flex;gap:%(sm)dpx;padding:%(xs)dpx 0;%(tbody)s}
.kv .k{color:%(faint)s;flex:0 0 150px}
/* S6 «Диагностика»: колонка ключей шире — имена полей не дробятся на строки
   (правка оператора: 150px оказалось мало). */
.kv.dk .k{flex-basis:%(dkw)dpx}
.kv .v{color:%(ink)s;flex:1}
/* Ссылка на классификатор в карточке: тап открывает список автора/жанра/серии. */
.lnk{text-decoration:underline;text-underline-offset:4px}
.block{padding:%(m)dpx %(phx)dpx;border-top:1px solid %(divider)s}
.block .lbl{%(tmeta)s;color:%(faint)s;font-weight:700;text-transform:uppercase;letter-spacing:1px;
            display:flex;align-items:center;gap:%(ss)dpx}
.block .txt{%(tbody)s;color:%(ink)s;margin-top:%(ss)dpx;line-height:1.4}
.actions{display:flex;gap:%(sm)dpx;padding:%(m)dpx %(phx)dpx;border-top:1px solid %(divider)s}
.actions .btn{flex:1}
.pager{flex:0 0 auto;display:flex;align-items:center;gap:%(ss)dpx;padding:0 %(ss)dpx;
       min-height:%(touch)dpx;border-top:2px solid %(ink)s;background:%(paper)s}
.pager .pbtn{width:%(touch)dpx;min-height:%(touch)dpx;display:flex;align-items:center;
             justify-content:center}
.pager .pbtn.off{opacity:.35}
.pager .cnt{flex:1;text-align:center;%(tmeta)s;color:%(ink)s;font-weight:700}
.pager .sp{flex:1}
.pager .btn{min-height:%(touch)dpx}
""" % dict(
    W=W, H=H, paper=C['paper'], ink=C['ink'], ink2=C['ink2'], faint=C['faint'], icon=C['icon'],
    divider=C['divider'], divider2=C['divider2'], rowsel=C['rowsel'],
    sbtrack='#D8D8D8', sbthumb='#3A3A3A', sbw=dp(8), sbr=dp(3), sbrr=dp(4),
    badge=C['badge'], badgeink=C['badgeink'], ph=C['ph'], phb=C['phb'], phink=C['phink'],
    tabact=C['tabact'], tabink=C['tabink'], sect=C['sect'],
    dither=C['dither'],
    banner=C['banner'], bannerink=C['bannerink'],
    btn=C['btn'], btnb=C['btnb'], disabled=C['disabled'],
    SH=STATUS_H, phx=PAD_H, pv=PAD_V, touch=TOUCH, sm=dp(8), ss=dp(4), m=dp(12), xs=dp(4), xl=dp(24),
    dkw=dp(240),
    search=SEARCH_H, tabh=TAB_H, tabph=TAB_PH, tabmore=dp(34), gap=GAP, lead=dp(28),
    cw=COVER_W, ch=COVER_H, cwc=COVER_WC, chc=COVER_HC, cwbig=dp(160), chbig=dp(240),
    cwview=dp(500), chview=dp(750),
    bpad=dp(20), dpad=dp(20), bp=dp(18),
    txl=t('xl'), ttitle=t('title'), theader=t('header'), ttab=t('tab'),
    tbody=t('body'), tmeta=t('meta'), tcount=t('count'), tph=t('placeholder'),
)

STATUS = """<div class="statusbar"><span>18:42</span>
<span class="right"><span>Wi-Fi</span><span>78%%</span><span class="batt"><i></i></span></span></div>"""


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def ic(name, size_dp, color=None, text=False):
    """Иконка для макета: размеры в dp."""
    return svg_icon(name, dp(size_dp), color)


def topbar(title, action=''):
    return ('<div class="topbar"><div class="back">%s</div>'
            '<div class="ttl">%s</div><div class="sp"></div>%s</div>'
            % (svg_icon('back', dp(24), C['ink']), esc(title), action))


TAB_KEYS = (('tab_authors', 'authors'), ('tab_genres', 'genres'), ('tab_series', 'series'),
            ('tab_date', 'date'), ('tab_history', 'history'), ('tab_all', 'books'))


def tabs(active, more=None):
    """Панель вкладок. Вариант B: одна строка с прокруткой, а у края, где есть
    скрытые вкладки, показывается стрелка (`more`: None / 'l' / 'r' / 'lr')."""
    items = []
    for i, (key, ico) in enumerate(TAB_KEYS):
        cls = 'tab on' if i == active else 'tab'
        col = '#fff' if i == active else C['tabink']
        items.append('<div class="%s">%s%s</div>'
                     % (cls, svg_icon(ico, dp(16), col), esc(tr(key))))
    left = ('<div class="tmore l">%s</div>' % svg_icon('chevron', dp(22), C['ink'])) \
        if more in ('l', 'lr') else ''
    right = ('<div class="tmore r">%s</div>' % svg_icon('chevron', dp(22), C['ink'])) \
        if more in ('r', 'lr') else ''
    return ('<div class="tabwrap"><div class="tabs">%s</div>%s%s</div>'
            % (''.join(items), left, right))


def searchbar(query=None, settings=True, index=False):
    """Строка поиска. Справа — вход в указатель букв (когда есть разделы) и настройки;
    при непустом запросе ещё и «очистить»."""
    right = ''
    if query:
        right += '<span class="x">%s</span>' % svg_icon('close', dp(22), C['ink'])
    if index:
        right += '<span class="idx">%s</span>' % svg_icon('list', dp(22), C['ink'])
    if settings:
        right += '<span class="gear">%s</span>' % svg_icon('settings', dp(22), C['ink'])
    hint = ('<span class="q">%s</span>' % esc(query)) if query else \
        ('<span class="hint">%s</span>' % esc(tr('search_hint')))
    return ('<div class="searchbar">%s%s<div class="sp"></div>%s</div>'
            % (svg_icon('search', dp(20), C['ink'] if query else C['icon']), hint, right))


def badge(n):
    return '<span class="badge">%s</span>' % n


CHEV = '<div class="chev">%s</div>' % svg_icon('chevron', dp(20), C['phink'])


def row_group(name, count):
    return ('<div class="row"><div class="grow"><div class="name">%s</div></div>%s%s</div>'
            % (esc(name), badge(count), CHEV))


def row_book(title, authors, series='', letter=None, compact=False, missing=False):
    box = '<div class="coverbox%s">%s</div>' % (' c' if compact else '', esc(letter) if letter else '')
    cls = 'row missing' if missing else 'row'
    extra = '<div class="sub">%s</div>' % esc(series) if series else ''
    return ('<div class="%s">%s<div class="grow"><div class="ttl">%s</div>'
            '<div class="sub">%s</div>%s</div></div>'
            % (cls, box, esc(title), esc(authors), extra))


def row_simple(title, count=None, sub=None, ico=None):
    lead = '<div class="lead">%s</div>' % svg_icon(ico, dp(20), C['icon']) if ico else ''
    right = badge(count) if count is not None else CHEV
    subh = '<div class="sub">%s</div>' % esc(sub) if sub else ''
    return ('<div class="row">%s<div class="grow"><div class="name">%s</div>%s</div>%s</div>'
            % (lead, esc(title), subh, right))


def row_root(path, sub, warn=False):
    """V9: строка папки (S9) — иконка, путь, тип/состояние, корзина.
    `warn` — папка без доступа или с отключённым томом (приглушена)."""
    cls = 'row' + (' warn' if warn else '')
    ico = 'alert' if warn else 'folder'
    lead = '<div class="lead">%s</div>' % svg_icon(
        ico, dp(20), C['disabled'] if warn else C['icon'])
    return ('<div class="%s">%s<div class="grow"><div class="name wrap">%s</div>'
            '<div class="sub">%s</div></div><div class="ract" title="%s">%s</div></div>'
            % (cls, lead, esc(path), esc(sub), esc(tr('cd_delete_root')),
               svg_icon('trash', dp(22), C['ink'])))


def roots_rows():
    """Демо-список папок для S9/D9 (2 доступных file-папки + SAF без доступа)."""
    scan = fmt(tr('root_scanned'), '01.11.2026 16:02')
    return (
        row_root('/storage/emulated/0/Books',
                 '%s · %s · %s' % (tr('root_kind_file'), n_plural('books', 677), scan)) +
        row_root('/storage/emulated/0/Incoming',
                 '%s · %s · %s' % (tr('root_kind_file'), n_plural('books', 23), scan)) +
        row_root('Nexus/Books', '%s · %s' % (tr('root_kind_saf'), tr('root_no_access')), warn=True))


def sect(text, bg=None):
    st = ' style="background:%s"' % bg if bg else ''
    return '<div class="sect"%s>%s</div>' % (st, esc(text))


def letter_head(ch):
    """Заголовок буквы-раздела в списке авторов. Обычная строка (не sticky)."""
    return '<div class="letter">%s</div>' % esc(ch)


def setting(ico, title, value='', chev=True):
    """Строка настройки: иконка + название + текущее значение + шеврон."""
    lead = '<div class="lead">%s</div>' % svg_icon(ico, dp(20), C['icon'])
    v = ('<div class="name" style="color:#777;font-size:24px">%s</div>' % esc(value)) if value else ''
    c = CHEV if chev else ''
    return ('<div class="row">%s<div class="grow"><div class="name">%s</div></div>%s%s</div>'
            % (lead, esc(title), v, c))


def banner(text, warn=False, chev=False):
    """Строка-баннер над списком. `warn` — с иконкой «внимание»,
    `chev` — с шевроном у правого края (баннер кликабелен, ведёт к настройкам)."""
    pre = svg_icon('alert', dp(18), C['bannerink']) if warn else ''
    tail = ('<span style="flex:1"></span>%s' % svg_icon('chevron', dp(20), C['bannerink'])) if chev else ''
    return '<div class="banner">%s%s%s</div>' % (pre, esc(text), tail)


def center(icon_name, msg, btn=None, big=False, btn_icon=None):
    b = ('<div class="btn">%s%s</div>'
         % (svg_icon(btn_icon, dp(20), C['ink']) if btn_icon else '', esc(btn))) if btn else ''
    return ('<div class="center">%s<div class="msg%s">%s</div>%s</div>'
            % (svg_icon(icon_name, dp(56), C['icon']), ' big' if big else '', esc(msg), b))


def footer(text, ico=None):
    pre = svg_icon(ico, dp(16), C['ink2']) if ico else ''
    return '<div class="footer">%s<span class="ftx">%s</span></div>' % (pre, esc(text))


def overlay(inner, dim=0.35):
    return '<div class="overlay" style="background:rgba(0,0,0,%.2f)">%s</div>' % (dim, inner)


def vsb(top=0.0, h=0.3):
    """Скроллбар-подсказка для области .scrollwrap: доли (0..1) от её высоты.

    Положение и длина бегунка отражают текущее место в прокрутке: короткий
    бегунок вверху = «контент длинный, сейчас в начале».
    """
    return ('<div class="vsb"><i style="top:%d%%;height:%d%%"></i></div>'
            % (round(top * 100), round(h * 100)))


def link(text):
    """Классификатор в карточке — подчёркнутая ссылка (тап → список группы)."""
    return '<span class="lnk">%s</span>' % esc(text)


def links(names, sep=' · '):
    """Несколько классификаторов одной категории в строку: подчёркнутые ссылки
    через разделитель. Тап по любой из них открывает её список."""
    return sep.join(link(n) for n in names)


# --------------------------------------------------------------------------
# Пагинация — ТОЛЬКО страницы, для всех списков. Размер страницы НЕ фиксирован:
#   строк на странице = сколько помещается на экран при текущем шрифте и плотности
#   (считается из метрик строки и доступной высоты, с разумным клампом).
#   PER_PAGE ниже — НОМИНАЛ для макетов; в приложении это per_page(context).
#   Плоские (жанры/серии/книги группы/поиск) и группированные (буквы/дата/история)
#   режутся одинаково; в адаптере всегда ≤ per_page элементов (важно при 1.6 ГБ ОЗУ).
#   Разрезанная группа не теряется: если страница начинается посреди группы,
#   заголовок раздела повторяется вверху (см. макет 21).
#   Режим «показать ещё» отклонён оператором.
# --------------------------------------------------------------------------
PER_PAGE = 50  # номинал для макетов; на устройстве строк на странице — по высоте экрана


def page_count(total, per=PER_PAGE):
    return max(1, (total + per - 1) // per)


def pager(page, total, per=PER_PAGE):
    pages = page_count(total, per)
    if pages <= 1:
        # Листать нечего — полосы страниц нет вовсе (решение оператора).
        return ''
    lo = (page - 1) * per + 1
    hi = min(page * per, total)

    def pbtn(ico, on, cd):
        return ('<div class="pbtn%s" title="%s">%s</div>'
                % ('' if on else ' off', esc(cd),
                   svg_icon(ico, dp(22), C['ink'] if on else C['disabled'])))

    return ('<div class="pager">%s%s<div class="cnt">%s</div>%s%s</div>'
            % (pbtn('chevron2_left', page > 1, tr('cd_first_page')),
               pbtn('back', page > 1, tr('cd_prev_page')),
               esc(fmt(tr('pag_range'), num(lo), num(hi), num(total))),
               pbtn('chevron', page < pages, tr('cd_next_page')),
               pbtn('chevron2_right', page < pages, tr('cd_last_page'))))


def dlg_title(ico, text):
    return '<h3>%s%s</h3>' % (svg_icon(ico, dp(20), C['ink']), esc(text))


def dlg_line(ico, text):
    return '<div class="line">%s<span>%s</span></div>' % (svg_icon(ico, dp(18), C['icon']), esc(text))


# ------------- сортировка по алфавиту (RU: «ё» рядом с «е», регистр не важен) -------------
def sort_key(name):
    return name.lower().replace('ё', 'е')


def sorted_alpha(items):
    return sorted(items, key=lambda it: sort_key(it[0]))


# ------------- авторы: фамилия → сортировка и группировка по буквам -------------
# Фамилию берём по надёжности источника:
#   1) «Фамилия, Имя» (EPUB dc:creator) — всё до запятой;
#   2) FB2/EPUB «Имя Отчество Фамилия» — последнее слово;
#   3) «Фамилия И. О.» — слово перед хвостовыми инициалами;
#   4) одно слово (ник/коллектив) — оно само.
# Для FB2 точнее всего: <last-name> в XML; эвристика — резерв для остальных форматов.
_INITIAL = re.compile(r'^[А-ЯЁA-Z]\.?$')


def author_surname(full):
    s = full.strip()
    if ',' in s:
        return s.split(',', 1)[0].strip()
    parts = s.split()
    if len(parts) == 1:
        return parts[0]
    while len(parts) > 1 and _INITIAL.match(parts[-1]):
        parts.pop()
    return parts[-1]


def letter_of(surname):
    """Буква-заголовок: первая буква фамилии в верхнем регистре; прочее → '#'."""
    return first_letter(surname)


def first_letter(text):
    """Раздел для текста, пропуская кавычки/знаки («Краткая…» → 'К').
    Цифра в начале → раздел '0–9'; буква → её верхний регистр (кириллица и латиница);
    если букв/цифр нет вовсе → '#'."""
    for ch in text:
        if ch.isdigit():
            return NUMKEY
        if ch.isalpha():
            return ch.upper()
    return '#'


def sorted_authors(items):
    """(имя, счётчик) → по (фамилия, имя); «ё» рядом с «е»."""
    return sorted(items, key=lambda it: sort_key(author_surname(it[0])))


def authors_list(items):
    """Список авторов с заголовками букв фамилий (обычные строки, не sticky)."""
    out, cur = [], None
    for name, cnt in items:
        lt = letter_of(author_surname(name))
        if lt != cur:
            cur = lt
            out.append(letter_head(lt))
        out.append(row_group(name, cnt))
    return ''.join(out)


def title_letter(title):
    """Буква названия книги: «Краткая…» → 'К'; число/символ → '#'."""
    return first_letter(title)


def books_list(items, compact=False):
    """Список книг с заголовками букв названий (при сортировке по названию)."""
    out, cur = [], None
    for b in items:
        lt = title_letter(b[0])
        if lt != cur:
            cur = lt
            out.append(letter_head(lt))
        out.append(row_book(*b, compact=compact))
    return ''.join(out)


# ------------- алфавитный указатель (правый край; сетка букв — для крупного шрифта) -------------
# Указатель строится ДИНАМИЧЕСКИ: числа ('0–9'), латиница (A–Z) и кириллица (А–Я) — только те
# разделы, что реально есть в списке. Порядок: числа → латиница → кириллица → прочее ('#').
NUMKEY = '0–9'
LATIN = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
CYRILLIC = 'АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ'
ALPHA_ORDER = [NUMKEY] + list(LATIN) + list(CYRILLIC) + ['#']


def letters_present(letters):
    """Разделы, реально встречающиеся в списке (числа/латиница/кириллица), в порядке A–Z/А–Я."""
    have = set(letters)
    return [k for k in ALPHA_ORDER if k in have]


def alpha_grid(present):
    """Отдельный диалог `D13`: крупные кнопки (числа + латиница + кириллица), тач-цель ≥ 48dp.
    Недоступные (нет данных) разделы — приглушены."""
    have = set(present)
    return ''.join('<div class="lbtn%s">%s</div>' % ('' if ch in have else ' off', esc(ch))
                   for ch in [NUMKEY] + list(LATIN) + list(CYRILLIC))


# ---------------- пример данных (из реальной коллекции) ----------------
AUTHORS = sorted_authors([
    ('Виктор Пелевин', 24), ('Николай Лукин', 118), ('Алекс Орлов', 41),
    ('Сергей Кузнецов', 12), ('Александр Громов', 9), ('Алексей Кулаков', 7),
    ('Евгений Белогорский', 6), ('Александр Конторович', 33), ('Борис Бабкин', 4),
    ('Андрей Круз', 15), ('Роман Злотников', 28), ('Александр Мазин', 19),
    ('George Orwell', 6), ('Terry Pratchett', 5), ('1С-Паблишинг', 3),
])
GENRES = sorted_alpha([
    ('Попаданцы', 203), ('Альтернативная история', 176), ('Боевая фантастика', 141),
    ('Сетевые миры', 304), ('Социальная фантастика', 62), ('Космическая фантастика', 55),
    ('Постапокалипсис', 48), ('Киберпанк', 21), ('Юмористическая фантастика', 17),
])
SERIES = sorted_alpha([
    ('Терской фронт', 12), ('Остров', 7), ('Сфера', 5), ('Камбрийская сноровка', 3),
    ('Крылья империи', 6), ('Чёрная смерть', 4),
])
BOOKS = [
    ('1984', 'George Orwell', '', '1'),
    ('451° по Фаренгейту', 'Рэй Брэдбери', '', '4'),
    ('Краткая история пэйнтбола в Москве', 'Виктор Пелевин', 'Киберпанк, 2004', 'П'),
    ('Терской фронт. Выстоять. Буря над Тереком', 'Александр Громов', 'Терской фронт · №2', 'Т'),
    ('Чёрная смерть. Спецназовец из будущего', 'Александр Конторович', 'Чёрная смерть · №1', 'Ч'),
    ('Камбрийская сноровка', 'Сергей Кузнецов', 'Камбрийская сноровка · №1', 'К'),
    ('Острова', 'Алекс Орлов', 'Остров · №1', 'О'),
    ('Крылья империи. Полёт над бездной империи', 'Сергей Кузнецов', 'Крылья империи · №2', 'К'),
    ('Приключения человека в шляпе', 'Борис Бабкин', '', 'П'),
    ('Кормление крокодила Хуфу', 'Виктор Пелевин', 'Пелевин · №12', 'К'),
    ('Восточная война', 'Евгений Белогорский', '', None),
]

# «Все книги» при сортировке по названию — с заголовками букв (см. books_list).
ALL_BOOKS = sorted(BOOKS, key=lambda b: (title_letter(b[0]), sort_key(b[0])))

READERS = [('FBReader', 'F'), ('OReaderX', 'O'), ('Librera FD', 'L'), ('NeoReader (Onyx)', 'N')]

# --------------------------------------------------------------------------
# Экраны
# --------------------------------------------------------------------------

def s01_main_authors():
    return (STATUS + searchbar(index=True) + tabs(0) +
            '<div class="list">' + authors_list(AUTHORS) + '</div>' +
            pager(1, 312))


def s02_main_allbooks():
    return (STATUS + searchbar(index=True) + tabs(5) +
            '<div class="list">' + books_list(ALL_BOOKS) + '</div>' + pager(1, 677))


def s03_main_allbooks_compact():
    return (STATUS + searchbar(index=True) + tabs(5) +
            '<div class="list">' + books_list(ALL_BOOKS, compact=True) + '</div>' + pager(1, 677))


def s04_main_date():
    return (STATUS + searchbar() + tabs(3) + '<div class="list">' +
            sect(tr('date_today')) + ''.join(row_book(*b) for b in BOOKS[:2]) +
            sect(tr('date_yesterday')) + ''.join(row_book(*b) for b in BOOKS[2:5]) +
            sect(tr('date_week')) + ''.join(row_book(*b) for b in BOOKS[5:8]) + '</div>' +
            pager(1, 677))


def s05_main_history():
    return (STATUS + searchbar() + tabs(4) + '<div class="list">' +
            sect(tr('history_recent')) +
            ''.join(row_book(*b) for b in BOOKS[6:9] + BOOKS[:3]) + '</div>' +
            pager(1, 96))


def s06_main_maxfont():
    """Проверка NFR-1: шрифт 200%, вкладки в два ряда, ничего не обрезано."""
    fs = 2.0
    rows = []
    for title, authors, series, letter in BOOKS[:5]:
        box = ('<div class="coverbox" style="width:%dpx;height:%dpx;font-size:38px">%s</div>'
               % (COVER_W, COVER_H, esc(letter) if letter else ''))
        rows.append('<div class="row" style="padding:%dpx %dpx">%s<div class="grow">'
                    '<div style="%s;font-weight:700;line-height:1.2">%s</div>'
                    '<div style="%s;color:#444;margin-top:%dpx">%s</div>'
                    '<div style="%s;color:#444">%s</div></div></div>'
                    % (PAD_V, PAD_H, box, t('title', fs), esc(title),
                       t('meta', fs), dp(8), esc(authors), t('meta', fs), esc(series)))
    tabsbig = ('<div class="tabwrap"><div class="tabs">' + ''.join(
        '<div class="tab%s" style="%s;min-height:%dpx">%s%s</div>'
        % (' on' if i == 3 else '', t('tab', fs), dp(56),
           svg_icon(ico, round(dp(16) * fs), '#fff' if i == 3 else C['tabink']), esc(tr(key)))
        for i, (key, ico) in enumerate(TAB_KEYS)) + '</div>'
        + '<div class="tmore l">%s</div>' % svg_icon('chevron', dp(26), C['ink'])
        + '<div class="tmore r">%s</div></div>' % svg_icon('chevron', dp(26), C['ink']))
    foot = '%d%% — %s' % (200, 'вкладки прокручиваются (стрелка у края), названия не обрезаны'
                          if LANG == 'ru' else 'tabs scroll (arrow at the edge), titles are not clipped')
    idxw = ('<span style="width:%dpx;display:flex;align-items:center;justify-content:flex-end">%s</span>'
            % (TOUCH, svg_icon('list', round(dp(20) * fs), C['ink'])))
    return (STATUS +
            '<div class="searchbar" style="min-height:auto;padding:%dpx %dpx">%s'
            '<span style="%s;color:#777">%s</span><div class="sp"></div>%s%s</div>'
            % (dp(12), PAD_H, svg_icon('search', round(dp(20) * fs), C['icon']), t('body', fs),
               esc(tr('search_hint')), idxw, svg_icon('settings', round(dp(20) * fs), C['ink'])) +
            tabsbig + '<div class="list">' + ''.join(rows) + '</div>' +
            footer(foot, 'type'))


def s07_main_empty():
    return (STATUS + searchbar() + tabs(5) +
            center('folder_plus', tr('st_no_roots'), btn=tr('btn_add_folder'),
                   big=True, btn_icon='plus' if 'plus' in ICONS else 'folder_plus'))


def s08_main_banner_scan():
    return (STATUS + searchbar() + tabs(5) +
            banner(tr('warn_partial'), warn=True, chev=True) +
            '<div class="list">' +
            ''.join(row_book(*b, missing=(i == 3))
                    for i, b in enumerate(BOOKS[:6])) + '</div>' +
            footer(fmt(tr('scan_footer'), num(41208), num(677), num(512)), 'refresh'))


def s09_search_results():
    rows = [('Краткая история пэйнтбола в Москве', 'Киберпанк, 2004'),
            ('Кормление крокодила Хуфу', 'Ампир · №12'),
            ('Жизнь насекомых', '')]
    html = []
    for title, series in rows:
        sub = ('<div class="sub">Виктор <span class="hl">Пелевин</span></div>')
        if series:
            sub += '<div class="sub">%s</div>' % esc(series)
        html.append('<div class="row"><div class="coverbox">%s</div><div class="grow">'
                    '<div class="ttl">%s</div>%s</div></div>'
                    % (esc(title[0]), esc(title), sub))
    return (STATUS + searchbar(query='пелевин') + tabs(5) + '<div class="list">' +
            ''.join(html) + '</div>' +
            footer('%s · %s' % (fmt(tr('found_count'), num(3)), tr('found_by')), 'search') +
            pager(1, 3))


def s10_group_books():
    """S2: книги внутри автора сгруппированы по сериям.
    Порядок групп — по названию серии; «Без серии» — в конце.
    Внутри группы — по номеру в серии, затем по названию (FR-2.3).
    Строки сортировки здесь нет: смена порядка для автора/серии не поддерживается."""
    action = '<div class="act">%s</div>' % svg_icon('more', dp(22), C['ink'])
    groups = [
        ('Вечно голодный студент', [
            ('Вечно голодный студент. Книга 1', 'RedDetonator'),
            ('Вечно голодный студент. Книга 2', 'RedDetonator'),
            ('Вечно голодный студент. Книга 3', 'RedDetonator'),
        ]),
        ('Некромант', [
            ('Некромант. Книга 1', 'RedDetonator'),
            ('Некромант. Книга 2', 'RedDetonator'),
        ]),
        ('НУнП', [
            ('НУнП. Начало', 'RedDetonator'),
        ]),
        (tr('series_none'), [
            ('Сборник рассказов', 'RedDetonator'),
            ('Повесть без серии', 'RedDetonator'),
        ]),
    ]
    body = []
    for name, books in groups:
        body.append(sect(name))
        for title, authors in books:
            body.append(row_book(title, authors))
    return (STATUS + topbar('RedDetonator', action) +
            '<div class="list">' + ''.join(body) + '</div>' +
            pager(1, 9))


def card_body():
    """Содержимое S3 «Карточка книги» (используется и в макете удаления)."""
    def kv(k, v):
        return '<div class="kv"><div class="k">%s</div><div class="v">%s</div></div>' % (k, v)
    return (
        '<div class="card"><div class="big">Т</div>'
        '<div class="grow" style="flex:1">'
        '<div style="%s;font-weight:700;line-height:1.2">Терской фронт. Выстоять. Буря над Тереком</div>'
        '<div style="%s;color:#444;margin-top:%dpx">%s</div></div></div>' % (
            t('title'), t('body'), dp(8), link('Борис Николаевич Громов')) +
        '<div class="block">' +
        kv(tr('card_authors'), links(['Борис Николаевич Громов'])) +
        kv(tr('card_series'), links(['Терской фронт · №2'])) +
        kv(tr('card_genres'), links(['Боевая фантастика', 'Историческая фантастика'])) +
        kv(tr('card_format'), 'FB2 (в ZIP)') +
        kv(tr('card_size'), '1,4 МБ' if LANG == 'ru' else '1.4 MB') +
        kv(tr('card_opened'), '12.05.2026') +
        kv(tr('card_path'), '/storage/6D4A-1E15/Books/Gromov_Terskoy_Front_2_Vyistoyat.231170.fb2.zip') +
        '</div>' +
        '<div class="block"><div class="lbl">%s</div>'
        '<div class="txt">Продолжение истории попаданца в тело казачьего офицера. '
        'Терской фронт выстоял, но война не окончена: над Тереком собираются тучи, '
        'и теперь решается судьба всего фронта. Герою предстоит пройти через новые '
        'испытания, найти союзников среди тех, кому ещё вчера не доверял, и заплатить '
        'за каждую победу свою цену. А где-то далеко, в ставке верховного командования, '
        'уже готовится приказ, который перечеркнёт все его планы. Он привык воевать '
        'в одиночку, но теперь за его спиной стоят люди — молодые, необстрелянные, '
        'доверчивые. Их жизни зависят от каждого его решения, и права на ошибку больше '
        'нет. Прошлое не отпускает: старые долги, старые враги, старая любовь, которую '
        'он считал потерянной навсегда. Ему придётся выбирать, чем пожертвовать, и этот '
        'выбор окажется тяжелее любого боя. Тем временем в столице плетутся свои интриги: '
        'одни делят власть, другие делят вину, и никому нет дела до тех, кто держит фронт '
        'на своих плечах. Его имя уже звучит в высоких кабинетах — одних оно радует, '
        'других пугает. Он же хочет одного: чтобы его людей не отправили на верную смерть '
        'ради чужого тщеславия. Но приказы не обсуждают, и однажды утром придёт тот самый, '
        'от которого зависит всё.</div></div>'
        % tr('card_annotation') +
        '<div class="actions"><div class="btn">%s%s</div><div class="btn">%s%s</div></div>'
        % (svg_icon('book_open', dp(20), C['ink']), tr('btn_open'),
           svg_icon('share', dp(20), C['ink']), tr('btn_open_with')) +
        '<div class="actions"><div class="btn">%s%s</div></div>'
        % (svg_icon('trash', dp(20), C['ink']), tr('btn_delete_file')))


def s11_book_card():
    return (STATUS + topbar(tr('title_book')) +
            '<div class="scrollwrap">' + card_body() + vsb(0, 0.80) + '</div>')


def s12_settings():
    roots = '%s · %s' % (n_plural('folders', 3), n_plural('books', 700))
    return (STATUS + topbar(tr('title_settings')) + '<div class="list">' +
            sect(tr('sec_library')) +
            row_simple(tr('set_roots'), sub=roots, ico='folder') +
            setting('refresh', tr('set_rescan')) +
            setting('trash', tr('set_clear')) +
            sect(tr('sec_reading')) +
            setting('book_open', tr('set_reader'), 'FBReader') +
            sect(tr('sec_appearance')) +
            setting('type', tr('set_font'), '175%') +
            setting('list', tr('set_density'), tr('density_normal')) +
            setting('authors', tr('set_author_fmt'), tr('author_first')) +
            setting('image', tr('set_covers'), tr('yes')) +
            setting('database', tr('set_cache'), '64 МБ' if LANG == 'ru' else '64 MB') +
            sect(tr('sec_system')) +
            setting('key', tr('set_access'), tr('access_granted')) +
            setting('activity', tr('set_diagnostics')) +
            setting('info', tr('set_about')) + '</div>')


def s13_folder_picker():
    books = n_plural('books', 597)
    size = '492 МБ' if LANG == 'ru' else '492 MB'
    return (STATUS + topbar(tr('title_pick')) +
            banner('/storage/6D4A-1E15') +
            '<div class="list">' +
            row_simple('Books', sub='%s, %s' % (books, size), ico='folder') +
            row_simple('Download', ico='folder') + row_simple('Documents', ico='folder') +
            row_simple('Fonts', ico='folder') + row_simple('Music', ico='folder') +
            row_simple('Pictures', ico='folder') + '</div>' +
            '<div class="actions"><div class="btn">%s%s</div><div class="btn">%s%s</div>'
            '<div class="btn">%s%s</div></div>'
            % (svg_icon('chevron_down', dp(20), C['ink']), tr('btn_up'),
               svg_icon('key', dp(20), C['ink']), tr('btn_sys_pick'),
               svg_icon('check' if 'check' in ICONS else 'folder_plus', dp(20), C['ink']),
               tr('btn_pick_here')))


def s14_diagnostics():
    def kv(k, v):
        return ('<div class="kv dk"><div class="k">%s</div>'
                '<div class="v">%s</div></div>' % (k, v))

    def blk(ico, title):
        return ('<div class="block"><div class="lbl">%s%s</div>'
                % (svg_icon(ico, dp(16), C['faint']), esc(title)))
    avail = tr('roots_available')
    return (STATUS + topbar(tr('title_diag')) + '<div class="list">' +
            blk('info', tr('blk_device')) +
            kv(tr('k_model'), 'ONYX BOOX RAPHAEL2') +
            kv(tr('k_android'), '11 (API 30)') +
            kv(tr('k_app_version'), '0.1.1 (2)') + '</div>' +
            blk('database', tr('blk_index')) +
            kv(tr('k_total_books'), num(677)) +
            kv(tr('k_agr'), '%s / %s / %s' % (num(312), num(69), num(96))) +
            kv(tr('k_no_meta'), num(0)) +
            kv(tr('k_missing'), num(0)) +
            kv(tr('k_covers'), '%s · %s %s' % (num(639), dec(41.2),
                                               'МБ' if LANG == 'ru' else 'MB')) + '</div>' +
            blk('folder', tr('blk_roots')) +
            kv('/storage/6D4A-1E15/Books', fmt(avail, num(597))) +
            kv('/storage/emulated/0/Books', fmt(avail, num(80))) + '</div>' +
            blk('book_open', tr('blk_readers')) +
            # Список читалок — по одной на строку, счётчик не нужен
            # (правка оператора).
            ''.join(kv('', r) for r, _ in READERS) +
            '</div></div>' +
            '<div class="actions"><div class="btn">%s%s</div><div class="btn">%s%s</div></div>'
            % (svg_icon('copy', dp(20), C['ink']), tr('btn_copy_report'),
               svg_icon('download', dp(20), C['ink']), tr('btn_export_logs')))


def s15_about():
    return (STATUS + topbar(tr('title_about')) +
            '<div class="center" style="justify-content:flex-start;padding-top:%dpx">' % dp(40) +
            svg_icon('bookmark', dp(72), C['ink']) +
            '<div style="%s;font-weight:700">%s</div>' % (t('xl'), esc(tr('app_name'))) +
            '<div style="%s;color:#444;margin-top:%dpx">%s</div>'
            % (t('body'), dp(6), esc(tr('app_tagline'))) +
            '<div style="%s;color:#777">%s</div>' % (t('body'), esc(fmt(tr('about_version'), '0.1.1'))) +
            '<div class="msg" style="%s;color:#444;margin-top:%dpx">%s</div>'
            % (t('body'), dp(24), esc(tr('about_text'))) +
            '<div class="msg" style="%s;color:#777;margin-top:%dpx">%s</div>'
            % (t('body'), dp(12), esc(tr('about_made'))) +
            '<div class="msg" style="%s;color:#777;margin-top:%dpx">ru.unclerus.library</div>'
            % (t('meta'), dp(24)) + '</div>')


def reader_rows(default=None, radios=True):
    """Строки списка читалок для D1/D1·b: значок, название, метка «по умолчанию».
    Радио показывает текущую читалку по умолчанию."""
    items = []
    for i, (label, letter) in enumerate(READERS):
        on = (default == i)
        right = '<div class="radio"></div>' if radios else ''
        mark = '<span class="def">%s</span>' % esc(tr('reader_default')) if on else ''
        items.append('<div class="it%s"><div class="appsq">%s</div>%s%s'
                     '<div class="sp"></div>%s</div>'
                     % (' on' if on else '', esc(letter), esc(label), mark, right))
    return items


def s16_dlg_open_with():
    """D1: «Открыть в…» — список читалок (тап открывает книгу в выбранной),
    отмечена читалка по умолчанию; ниже — «Открыть в проводнике»."""
    items = reader_rows(default=0)
    items.append('<div class="it"><div class="lead">%s</div>%s</div>'
                 % (svg_icon('folder', dp(20), C['icon']), esc(tr('open_in_fm'))))
    d1 = ('<div class="dlg">%s<div class="items">%s</div>'
          '<div class="acts"><div class="btn">%s</div></div></div>'
          % (dlg_title('share', tr('dlg_open_with')), ''.join(items), esc(tr('cancel'))))
    return (STATUS + searchbar() + tabs(5) +
            '<div class="list">' + ''.join(row_book(*b) for b in BOOKS[:8]) + '</div>' +
            overlay(d1))


def s17_dlg_scan_progress():
    d3 = ('<div class="dlg" style="width:80%%">%s'
          '<div class="body">%s\n%s'
          '<div class="progbar"><i></i></div>\n%s</div>'
          '<div class="acts"><div class="btn">%s%s</div><div class="btn">%s%s</div></div></div>'
          % (dlg_title('database', tr('dlg_scan')),
             esc(fmt(tr('scan_root'), '/storage/6D4A-1E15/Books')),
             esc(fmt(tr('scan_footer'), num(41208), num(677), num(512))),
             esc(fmt(tr('scan_phase'), num(512), num(677))),
             svg_icon('chevron_down', dp(18), C['ink']), tr('btn_collapse'),
             svg_icon('stop', dp(18), C['ink']), tr('btn_stop')))
    return (STATUS + searchbar() + tabs(1) +
            '<div class="list">' + ''.join(row_group(n, c) for n, c in GENRES) + '</div>' +
            overlay(d3, 0.25))


def s18_dlg_clear_missing():
    body = ('%s\n%s\n%s\n\n%s'
            % (esc(tr('clear_intro')),
               esc('• ' + fmt(tr('clear_books'), num(3))),
               esc('• ' + fmt(tr('clear_groups'), num(12))),
               esc(tr('clear_warn'))))
    d4 = ('<div class="dlg" style="width:84%%">%s<div class="body">%s</div>'
          '<div class="acts"><div class="btn">%s</div><div class="btn">%s</div></div></div>'
          % (dlg_title('alert', tr('dlg_clear')), body,
             esc(tr('cancel')), esc(tr('btn_clear'))))
    return (STATUS + topbar(tr('title_settings')) +
            '<div class="list">' + sect(tr('sec_library')) +
            row_simple(tr('set_roots'), sub='%s · %s' % (n_plural('folders', 3), n_plural('books', 700)),
                       ico='folder') +
            setting('refresh', tr('set_rescan')) + setting('trash', tr('set_clear')) + '</div>' +
            overlay(d4))


def s19_dlg_delete_file():
    """D11: удаление файла книги — необратимо, поэтому подтверждение с деталями."""
    body = ('<b>Терской фронт. Выстоять. Буря над Тереком</b>\nАлександр Громов\n\n'
            + '\n'.join([
                dlg_line('books', '%s: %s' % (tr('del_file'), 'Gromov_...231170.fb2.zip')),
                dlg_line('folder', '%s: %s' % (tr('del_path'), '/storage/6D4A-1E15/Books/…')),
                dlg_line('database', '%s: %s' % (tr('del_size'), '1,4 МБ' if LANG == 'ru' else '1.4 MB')),
                dlg_line('history', '%s: %s' % (tr('del_opened'), '12.05.2026')),
            ])
            + '\n\n' + dlg_line('alert', tr('del_warn')))
    d11 = ('<div class="dlg" style="width:86%%">%s<div class="body">%s</div>'
           '<div class="acts"><div class="btn">%s</div><div class="btn">%s</div>'
           '<div class="btn">%s</div></div></div>'
           % (dlg_title('alert', tr('dlg_delete')), body, esc(tr('cancel')),
              esc(tr('btn_delete_catalog')), esc(tr('btn_delete_confirm'))))
    return (STATUS + topbar(tr('title_book')) +
            '<div class="scrollwrap">' + card_body() + vsb(0, 0.80) + '</div>'
            + overlay(d11, 0.45))


def s20_pagination_pages():
    """Вариант А: постранично — только ПЛОСКИЕ списки (жанры/серии/книги группы/поиск)."""
    return (STATUS + searchbar() + tabs(1) +
            '<div class="list">' + ''.join(row_group(n, c) for n, c in GENRES) + '</div>' +
            pager(1, 69) +
            footer(fmt(tr('pag_note_pages'), num(1), num(page_count(69))), 'books'))


def s21_pagination_group():
    """Группа, разрезанная страницей: заголовок раздела ПОВТОРЯЕТСЯ вверху страницы 2.
    Так постраничный разрез не «теряет» группу (буквы фамилий/названий, даты, история)."""
    rows = [letter_head('К')]
    rows += [row_group(n, c) for n, c in [('Сергей Кузнецов', 12), ('Алексей Кулаков', 7)]]
    rows += [letter_head('Л'), row_group('Николай Лукин', 118)]
    rows += [letter_head('М'), row_group('Александр Мазин', 19)]
    rows += [letter_head('О'), row_group('Алекс Орлов', 41)]
    rows += [letter_head('П'), row_group('Виктор Пелевин', 24)]
    return (STATUS + searchbar(index=True) + tabs(0) +
            '<div class="list">' + ''.join(rows) + '</div>' +
            pager(2, 312) + footer(fmt(tr('pag_note_group'), num(2), num(page_count(312))), 'books'))


def s22_dlg_page_jump():
    """При 100 000 книг страниц ~2 000 — без прыжка по номеру не обойтись.
    Демонстрируется на плоском списке (результаты поиска)."""
    body = ('<div style="color:#777;margin-bottom:%dpx">%s</div>'
            '<div class="btn" style="justify-content:flex-start;min-width:%dpx">%s</div>'
            '<div style="color:#777;margin-top:%dpx">%s</div>'
            % (dp(6), esc(tr('dlg_jump_label')), dp(320), '401',
               dp(6), esc(fmt(tr('dlg_jump_range'), num(page_count(100000)))) +
               ' · ' + esc(tr('pag_per_page'))))
    d12 = ('<div class="dlg" style="width:72%%">%s<div class="body">%s</div>'
           '<div class="acts"><div class="btn">%s</div><div class="btn">%s</div></div></div>'
           % (dlg_title('books', tr('dlg_jump')), body, esc(tr('cancel')), esc(tr('btn_go'))))
    return (STATUS + searchbar(query=tr('demo_query')) + tabs(5) +
            '<div class="list">' + ''.join(row_book(*b) for b in BOOKS) + '</div>' +
            pager(401, 100000) + overlay(d12, 0.4))


def s23_dlg_alpha():
    """D13: алфавитный указатель для крупного шрифта — сетка букв (тач-цели ≥ 48dp)."""
    letters = letters_present(letter_of(author_surname(n)) for n, _ in AUTHORS)
    d13 = ('<div class="dlg" style="width:82%%">%s<div class="body">'
           '<div class="agrid">%s</div></div>'
           '<div class="acts"><div class="btn">%s</div></div></div>'
           % (dlg_title('list', tr('dlg_alpha')), alpha_grid(letters), esc(tr('cancel'))))
    return (STATUS + searchbar(index=True) + tabs(0) +
            '<div class="list">' + authors_list(AUTHORS) + '</div>' +
            pager(1, 312) + overlay(d13, 0.4))


def s24_cover_view():
    """S8: обложка в большом размере — открывается тапом по обложке в карточке.
    Закрытие — тап по обложке или системная «Назад»."""
    return (STATUS +
            '<div class="coverview">'
            '<div class="cvtop"><div class="x">%s</div>'
            '<div class="ttl">%s</div><div class="spc"></div></div>'
            '<div class="cvbody"><div class="cvbox">Т</div></div>'
            '<div class="cvhint">%s</div>'
            '</div>'
            % (svg_icon('close', dp(26), C['ink']), esc(tr('title_cover')),
               esc(tr('hint_cover_close'))))


def s25_dlg_default_reader():
    """D1·b: выбор читалки по умолчанию при первом запуске (решение оператора —
    читалка задаётся один раз здесь; смена — в настройках)."""
    d = ('<div class="dlg">%s<div class="body">%s</div><div class="items">%s</div>'
         '<div class="acts"><div class="btn">%s</div></div></div>'
         % (dlg_title('book_open', tr('dlg_reader_default')),
            esc(tr('reader_default_hint')), ''.join(reader_rows(default=None)),
            esc(tr('cancel'))))
    return (STATUS + searchbar() + tabs(5) +
            '<div class="list">' + ''.join(row_book(*b) for b in BOOKS[:8]) + '</div>' +
            overlay(d))


def s26_dlg_open_error():
    """D5: запуск книги не удался — причина + переход к выбору другой читалки."""
    body = dlg_line('alert', fmt(tr('open_error_reader'), 'FBReader'))
    d = ('<div class="dlg">%s<div class="body">%s</div>'
         '<div class="acts"><div class="btn">%s</div><div class="btn">%s</div></div></div>'
         % (dlg_title('alert', tr('dlg_open_error')), body,
            esc(tr('cancel')), esc(tr('action_choose_reader'))))
    return (STATUS + searchbar() + tabs(5) +
            '<div class="list">' + ''.join(row_book(*b) for b in BOOKS[:8]) + '</div>' +
            overlay(d))


def s27_dlg_no_readers():
    """D10: на устройстве нет ни одной читалки — объяснение."""
    d = ('<div class="dlg">%s<div class="body">%s</div>'
         '<div class="acts"><div class="btn">%s</div></div></div>'
         % (dlg_title('alert', tr('dlg_no_readers')), esc(tr('no_readers_text')),
            esc(tr('action_ok'))))
    return (STATUS + searchbar() + tabs(5) +
            '<div class="list">' + ''.join(row_book(*b) for b in BOOKS[:8]) + '</div>' +
            overlay(d))


def s28_menu_book():
    """D6: контекстное меню книги (долгий тап) — открыть / открыть в… /
    информация / показать файл в папке."""
    rows = [('book_open', tr('btn_open')), ('share', tr('btn_open_with')),
            ('info', tr('action_info')), ('folder', tr('action_show_file'))]
    items = ''.join('<div class="it"><div class="lead">%s</div>%s</div>'
                    % (svg_icon(ico, dp(20), C['icon']), esc(label)) for ico, label in rows)
    d6 = ('<div class="dlg">%s<div class="items">%s</div>'
          '<div class="acts"><div class="btn">%s</div></div></div>'
          % (dlg_title('books', BOOKS[3][0]), items, esc(tr('cancel'))))
    body = []
    for i, b in enumerate(BOOKS[:8]):
        row = row_book(*b)
        body.append(row.replace('class="row"', 'class="row sel"', 1) if i == 3 else row)
    return (STATUS + searchbar() + tabs(5) +
            '<div class="list">' + ''.join(body) + '</div>' + overlay(d6))


def s29_root_folders():
    """S9: «Папки с книгами» — список папок (V9) + «Добавить папку».
    Иконка слева, корзина справа — удалить (D9)."""
    return (STATUS + topbar(tr('title_roots')) +
            '<div class="list">' + roots_rows() + '</div>' +
            '<div class="actions"><div class="btn">%s%s</div></div>'
            % (svg_icon('folder_plus', dp(20), C['ink']), esc(tr('btn_add_folder'))) +
            footer(tr('note_roots'), 'key'))


def s30_dlg_delete_root():
    """D9: подтверждение удаления корня — записи книг уходят из каталога, файлы остаются."""
    body = ('%s\n\n%s'
            % (esc(fmt(tr('root_del_warn'),
                       '/storage/emulated/0/Books', n_plural('books', 677))),
               esc(tr('root_del_hint'))))
    d9 = ('<div class="dlg" style="width:84%%">%s<div class="body">%s</div>'
          '<div class="acts"><div class="btn">%s</div><div class="btn">%s</div></div></div>'
          % (dlg_title('alert', tr('dlg_root_del')), body,
             esc(tr('cancel')), esc(tr('btn_delete_root'))))
    return (STATUS + topbar(tr('title_roots')) +
            '<div class="list">' + roots_rows() + '</div>' +
            footer(tr('note_roots'), 'key') + overlay(d9))


def s31_dlg_file_access():
    """D2: нет MANAGE_EXTERNAL_STORAGE — объяснение + «Разрешить доступ»
    (системный экран) и fallback «Выбрать папку» (системный выбор, SAF).
    Позади — S1 с баннером ST9."""
    body = ('%s\n\n%s'
            % (esc(tr('access_text')), esc(tr('access_saf_hint'))))
    d2 = ('<div class="dlg" style="width:84%%">%s<div class="body">%s</div>'
          '<div class="acts"><div class="btn">%s</div><div class="btn">%s</div>'
          '<div class="btn">%s</div></div></div>'
          % (dlg_title('key', tr('dlg_access')), body,
             esc(tr('btn_later')), esc(tr('btn_pick_saf')), esc(tr('btn_grant'))))
    return (STATUS + searchbar() + tabs(5) +
            banner(tr('warn_no_access'), warn=True, chev=True) +
            '<div class="list">' + ''.join(row_book(*b) for b in BOOKS[:8]) + '</div>' +
            overlay(d2, 0.4))


def s32_main_banner_unavailable():
    """ST5: часть книг недоступна (отозван доступ / том отключён) — баннер
    с переходом к настройкам, недоступные строки приглушены."""
    return (STATUS + searchbar() + tabs(5) +
            banner(tr('warn_partial'), warn=True, chev=True) +
            '<div class="list">' +
            ''.join(row_book(*b, missing=(i in (3, 5)))
                    for i, b in enumerate(BOOKS[:8])) + '</div>' +
            pager(1, 677))


BUILDERS = [
    ('01_main_authors', s01_main_authors),
    ('02_main_allbooks', s02_main_allbooks),
    ('03_main_allbooks_compact', s03_main_allbooks_compact),
    ('04_main_date', s04_main_date),
    ('05_main_history', s05_main_history),
    ('06_main_maxfont', s06_main_maxfont),
    ('07_main_empty', s07_main_empty),
    ('08_main_banner_scan', s08_main_banner_scan),
    ('09_search_results', s09_search_results),
    ('10_group_books', s10_group_books),
    ('11_book_card', s11_book_card),
    ('12_settings', s12_settings),
    ('13_folder_picker', s13_folder_picker),
    ('14_diagnostics', s14_diagnostics),
    ('15_about', s15_about),
    ('16_dlg_open_with', s16_dlg_open_with),
    ('17_dlg_scan_progress', s17_dlg_scan_progress),
    ('18_dlg_clear_missing', s18_dlg_clear_missing),
    ('19_dlg_delete_file', s19_dlg_delete_file),
    ('20_pagination_pages', s20_pagination_pages),
    ('21_pagination_group', s21_pagination_group),
    ('22_dlg_page_jump', s22_dlg_page_jump),
    ('23_dlg_alpha', s23_dlg_alpha),
    ('24_cover_view', s24_cover_view),
    ('25_dlg_default_reader', s25_dlg_default_reader),
    ('26_dlg_open_error', s26_dlg_open_error),
    ('27_dlg_no_readers', s27_dlg_no_readers),
    ('28_menu_book', s28_menu_book),
    ('29_root_folders', s29_root_folders),
    ('30_dlg_delete_root', s30_dlg_delete_root),
    ('31_dlg_file_access', s31_dlg_file_access),
    ('32_main_banner_unavailable', s32_main_banner_unavailable),
]


# --------------------------------------------------------------------------
# strings.xml и drawable/ic_*.xml
# --------------------------------------------------------------------------
def xml_escape(s):
    return (s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
             .replace('"', '&quot;').replace("'", "\\'"))


def emit_strings(lang):
    """Пишет res/values/strings.xml (ru) или res/values-en/strings.xml (en)."""
    idx = 1 if lang == 'ru' else 2
    res = os.path.join(APP, 'res', 'values' if lang == 'ru' else 'values-en')
    os.makedirs(res, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- СГЕНЕРИРОВАНО design/gen.py — правьте таблицу S_ там, а не этот файл. -->',
             '<resources>']
    for key in sorted(S_, key=lambda k: S_[k][0]):
        name, ru, en = S_[key]
        lines.append('    <string name="%s">%s</string>' % (name, xml_escape((ru, en)[idx - 1])))
    for key in sorted(P_, key=lambda k: P_[k][0]):
        name, forms = P_[key]
        lines.append('    <plurals name="%s">' % name)
        for q, v in zip(('one', 'few', 'many'), forms[lang]):
            lines.append('        <item quantity="%s">%s</item>' % (q, xml_escape(v)))
        lines.append('    </plurals>')
    lines.append('</resources>')
    path = os.path.join(res, 'strings.xml')
    with open(path, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(lines) + '\n')
    return path


def emit_vectors():
    """Пишет тот же набор иконок в res/drawable/ic_<name>.xml (VectorDrawable).
    Обводка = @color/icon (#777); там, где нужен чёрный, ставим tint в layout."""
    dr = os.path.join(APP, 'res', 'drawable')
    os.makedirs(dr, exist_ok=True)
    written = []
    for name, elems in sorted(ICONS.items()):
        paths = []
        for el in elems:
            if el[0] == 'p':
                paths.append('    <path android:pathData="%s" android:strokeColor="@color/icon" '
                             'android:strokeWidth="%s" android:strokeLineCap="round" '
                             'android:strokeLineJoin="round" android:fillColor="#00000000" />'
                             % (el[1], STROKE))
            elif el[0] == 'pf':
                paths.append('    <path android:pathData="%s" android:fillColor="@color/icon" />' % el[1])
            elif el[0] == 'c':
                paths.append(_circle_path(el[1], el[2], el[3], stroke=True))
            elif el[0] == 'cf':
                paths.append(_circle_path(el[1], el[2], el[3], stroke=False))
        xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
               '<!-- СГЕНЕРИРОВАНО design/gen.py -->\n'
               '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
               '    android:width="24dp" android:height="24dp"\n'
               '    android:viewportWidth="24" android:viewportHeight="24">\n'
               + '\n'.join(paths) + '\n</vector>\n')
        path = os.path.join(dr, 'ic_%s.xml' % name)
        with open(path, 'w', encoding='utf-8') as fh:
            fh.write(xml)
        written.append(path)

    # --- иконка приложения (запись бэклога №9) ---------------------------
    # Знак «закрытая книга с закладкой» лежит в ICONS['bookmark'] (24dp,
    # штрих 1.9). Готовим набор ресурсов с единым весом штриха: 24dp-глиф
    # показывается примерно на 40dp, как остальные иконки набора.
    #   mipmap-anydpi/ic_launcher.xml        — legacy (API < 26): белый квадрат 48dp;
    #   mipmap-anydpi-v26/ic_launcher*.xml   — adaptive: белый фон + передний план;
    #   drawable/ic_launcher_foreground.xml  — знак в безопасной зоне 108dp.
    def _sign(indent, color):
        """Разметка знака приложения: обводка и заливка (закладка) в один цвет."""
        out = []
        for el in ICONS['bookmark']:
            if el[0] == 'p':
                out.append('%s<path android:pathData="%s" android:strokeColor="%s" '
                           'android:strokeWidth="%s" android:strokeLineCap="round" '
                           'android:strokeLineJoin="round" android:fillColor="#00000000" />'
                           % (indent, el[1], color, STROKE))
            elif el[0] == 'pf':
                out.append('%s<path android:pathData="%s" android:fillColor="%s" />'
                           % (indent, el[1], color))
        return '\n'.join(out)

    head = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- СГЕНЕРИРОВАНО design/gen.py — правьте ICONS[\'bookmark\'] там, а не этот файл. -->\n')

    # legacy: 48dp, белый квадрат, знак scale 1.7 (offset (48-40.8)/2 = 3.6)
    xml = (head +
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="48dp" android:height="48dp"\n'
           '    android:viewportWidth="48" android:viewportHeight="48">\n'
           '    <path android:pathData="M0 0h48v48H0z" android:fillColor="#FFFFFFFF" />\n'
           '    <group android:scaleX="1.7" android:scaleY="1.7" '
           'android:translateX="3.6" android:translateY="3.6">\n'
           + _sign('    ', '@color/ink') + '\n    </group>\n</vector>\n')
    legacy = os.path.join(APP, 'res', 'mipmap-anydpi', 'ic_launcher.xml')
    os.makedirs(os.path.dirname(legacy), exist_ok=True)
    with open(legacy, 'w', encoding='utf-8') as fh:
        fh.write(xml)
    written.append(legacy)

    # передний план adaptive: 108dp, знак scale 2.55 (24*2.55 = 61.2; offset 23.4).
    # Знак целиком лежит в безопасной зоне 66dp — ни одна маска его не срежет.
    xml = (head +
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="108dp" android:height="108dp"\n'
           '    android:viewportWidth="108" android:viewportHeight="108">\n'
           '    <group android:scaleX="2.55" android:scaleY="2.55" '
           'android:translateX="23.4" android:translateY="23.4">\n'
           + _sign('    ', '@color/ink') + '\n    </group>\n</vector>\n')
    fg = os.path.join(dr, 'ic_launcher_foreground.xml')
    with open(fg, 'w', encoding='utf-8') as fh:
        fh.write(xml)
    written.append(fg)

    # adaptive-иконка: белая бумага + знак чернилами. Один файл на оба имени —
    # ic_launcher (квадратная маска) и ic_launcher_round (круглая маска).
    adaptive = (head +
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/paper" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n')
    for name in ('ic_launcher', 'ic_launcher_round'):
        p = os.path.join(APP, 'res', 'mipmap-anydpi-v26', name + '.xml')
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, 'w', encoding='utf-8') as fh:
            fh.write(adaptive)
        written.append(p)

    # старый одиночный drawable/ic_launcher.xml больше не нужен — убираем,
    # чтобы не оставалось двух источников знака.
    stale = os.path.join(dr, 'ic_launcher.xml')
    if os.path.exists(stale):
        os.remove(stale)
    return written


# --------------------------------------------------------------------------
# Дизеринг незанятого места в списках (решение оператора, макет 25).
# Тайл 10x10 px кладётся в drawable-nodpi — масштаб 1:1 к пикселю экрана,
# поэтому плотность совпадает с макетом. В разметке — bitmap tileMode=repeat
# (drawable/dither.xml), фон области списка; строки-ряды непрозрачно белые.
# --------------------------------------------------------------------------
DITHER_TILE = 10   # период точек, px экрана (совпадает с CSS background-size)
DITHER_DOT = 2     # точка 2x2 px в центре тайла


def _hex_rgb(s):
    return (int(s[1:3], 16), int(s[3:5], 16), int(s[5:7], 16))


def _png_chunk(tag, data):
    return (struct.pack('>I', len(data)) + tag + data
            + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))


def emit_dither():
    """Пишет app/res/drawable-nodpi/dither_dot.png — тайл дизеринга."""
    n = DITHER_TILE
    rgb = _hex_rgb(C['dither'])
    x0 = (n - DITHER_DOT) // 2
    y0 = (n - DITHER_DOT) // 2
    rows = []
    for y in range(n):
        row = bytearray()
        for x in range(n):
            on = x0 <= x < x0 + DITHER_DOT and y0 <= y < y0 + DITHER_DOT
            row += bytes(rgb + (255,)) if on else bytes((255, 255, 255, 255))
        rows.append(bytes(row))
    raw = b''.join(b'\x00' + r for r in rows)
    png = (b'\x89PNG\r\n\x1a\n'
           + _png_chunk(b'IHDR', struct.pack('>IIBBBBB', n, n, 8, 6, 0, 0, 0))
           + _png_chunk(b'IDAT', zlib.compress(raw, 9))
           + _png_chunk(b'IEND', b''))
    dr = os.path.join(APP, 'res', 'drawable-nodpi')
    os.makedirs(dr, exist_ok=True)
    path = os.path.join(dr, 'dither_dot.png')
    with open(path, 'wb') as fh:
        fh.write(png)
    return path


def _circle_path(cx, cy, r, stroke):
    d = ('M%sin%g-%g%s%gz' % (cx - r, cy, r, r, r)  # приближение: две дуги
         if False else 'M%g %g a%g %g 0 1 0 %g 0 a%g %g 0 1 0 -%g 0' % (cx - r, cy, r, r, 2 * r, r, r, 2 * r))
    if stroke:
        return ('    <path android:pathData="%s" android:strokeColor="@color/icon" '
                'android:strokeWidth="%s" android:fillColor="#00000000" />' % (d, STROKE))
    return '    <path android:pathData="%s" android:fillColor="@color/icon" />' % d


def main():
    for lang in LANGS:
        globals()['LANG'] = lang
        out = os.path.join(HERE, 'out', lang)
        os.makedirs(out, exist_ok=True)
        # убрать HTML удалённых экранов, иначе render.sh воскресит старый PNG
        for old in os.listdir(out):
            if old.endswith('.html'):
                os.remove(os.path.join(out, old))
        for name, fn in BUILDERS:
            page = ('<!doctype html><html lang="%s"><head><meta charset="utf-8">'
                    '<title>%s [%s]</title><style>%s</style></head><body>'
                    '<div class="screen">%s</div></body></html>'
                    % (lang, name, lang, CSS, fn()))
            with open(os.path.join(out, name + '.html'), 'w', encoding='utf-8') as fh:
                fh.write(page)
        print('макеты [%s]: %d -> %s' % (lang, len(BUILDERS), out))
    for lang in LANGS:
        print('строки [%s]: %s' % (lang, emit_strings(lang)))
    vec = emit_vectors()
    print('иконок: %d -> %s' % (len(vec), os.path.dirname(vec[0])))
    print('дизеринг: %s' % emit_dither())


if __name__ == '__main__':
    main()
