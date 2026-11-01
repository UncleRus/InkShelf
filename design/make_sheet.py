#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Собирает design/sheet.html — лист макетов с подписями (оба языка) + design/index.html.
Подписи берутся из TITLES; порядок — по имени файла."""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SCREENS = os.path.join(HERE, 'screens')
LANGS = ('ru', 'en')

TITLES = {
    '01_main_authors': ('S1 · Авторы (буквы фамилий)', 'Фамилия → сортировка, буквы + указатель А–Я'),
    '02_main_allbooks': ('S1 · Все книги (буквы названий)', 'Буквы названий + указатель А–Я'),
    '03_main_allbooks_compact': ('S1 · Все книги (компактная)', 'Обложка 36×54 dp, буквы названий'),
    '04_main_date': ('S1 · Дата', 'Группы по дате, страницы (заголовок повторяется)'),
    '05_main_history': ('S1 · История', 'Одна группа «Недавно открытые», страницы'),
    '06_main_maxfont': ('S1 · Шрифт 200% (NFR-1)', 'Вкладки прокручиваются (стрелки у краёв), ничего не обрезано'),
    '07_main_empty': ('S1 · Нет папок с книгами (ST1)', 'Иконка + «Добавить папку»'),
    '08_main_banner_scan': ('S1 · Баннер + индексация', 'Иконка предупреждения, строка прогресса'),
    '09_search_results': ('S1 · Результаты поиска', 'Подсветка совпадений'),
    '10_group_books': ('S2 · Книги группы', '«Ещё» (⋮) — сортировка/фильтр; книги сгруппированы по сериям'),
    '11_book_card': ('S3 · Карточка книги', 'Классификаторы — подчёркнутые ссылки (тап → список); постоянный скроллбар справа'),
    '12_settings': ('S4 · Настройки', 'Иконка на каждой строке'),
    '13_folder_picker': ('S5 · Выбор папки', 'Папки с иконками'),
    '14_diagnostics': ('S6 · Диагностика', 'Блоки с иконками'),
    '15_about': ('S7 · О программе', 'Знак приложения, девиз «Каталог книг для e-ink»'),
    '16_dlg_open_with': ('D1 · «Открыть в…»', 'Значки читалок; отмечена читалка по умолчанию; ниже — «Открыть в проводнике»'),
    '17_dlg_scan_progress': ('D3 · Прогресс индексации', 'Свернуть / Стоп'),
    '18_dlg_clear_missing': ('D4 · Очистить отсутствующие', 'Предупреждение, удаляет и группы'),
    '19_dlg_delete_file': ('D11 · Удалить файл', 'Подтверждение: файл + каталог / только каталог'),
    '20_pagination_pages': ('Пагинация · страницы', 'Плоские списки (жанры/серии/поиск): по 50 строк'),
    '21_pagination_group': ('Пагинация · группа на стыке страниц', 'Заголовок раздела повторяется вверху страницы 2'),
    '22_dlg_page_jump': ('D12 · Перейти к странице', 'Плоский список, 100 000 книг (~2 000 страниц)'),
    '23_dlg_alpha': ('D13 · Алфавитный указатель', 'Сетка букв для крупного шрифта (тач-цели ≥ 48dp)'),
    '24_cover_view': ('S8 · Обложка (большой размер)', 'Тап по обложке в карточке; закрытие — тап или «Назад»'),
    '25_dlg_default_reader': ('D1·b · Выбор читалки (первый запуск)', 'Разовый выбор читалки по умолчанию; смена — в настройках'),
    '26_dlg_open_error': ('D5 · Ошибка запуска книги', 'Причина + «Выбрать другую читалку» (→ D1)'),
    '27_dlg_no_readers': ('D10 · Нет читалок', 'Объяснение: установите приложение-читалку'),
    '28_menu_book': ('D6 · Меню книги (долгий тап)', 'Открыть · Открыть в… · Информация · Показать файл в папке'),
    '29_root_folders': ('S9 · Папки с книгами', 'Путь, тип (файл/SAF), состояние, корзина (удалить → D9)'),
    '30_dlg_delete_root': ('D9 · Удалить папку', 'Книги уходят из каталога, файлы на диске не трогаются'),
    '31_dlg_file_access': ('D2 · Доступ к файлам', '«Разрешить доступ» → системный экран; fallback — системный выбор папки (SAF)'),
    '32_main_banner_unavailable': ('S1 · Часть книг недоступна (ST5)', 'Баннер с переходом к настройкам; недоступные строки приглушены'),
}


def main():
    tpl = open(os.path.join(HERE, 'sheet.tpl.html'), encoding='utf-8').read()
    rows = []
    for lang in LANGS:
        d = os.path.join(SCREENS, lang)
        if not os.path.isdir(d):
            continue
        names = sorted(n[:-4] for n in os.listdir(d) if n.endswith('.png') and not n.startswith('_'))
        figs = []
        for n in names:
            title, desc = TITLES.get(n, (n, ''))
            figs.append('<figure><img src="screens/%s/%s.png" alt="%s">'
                        '<figcaption><b>%s</b>%s</figcaption></figure>'
                        % (lang, n, title, title, desc))
            rows.append('<li><a href="screens/%s/%s.png">%s</a> — %s</li> %s'
                        % (lang, n, n, lang.upper(), title))
        html = tpl.replace('__FIGURES__', '\n'.join(figs)).replace(
            '__TITLE__', '«InkShelf» — макеты экранов (%s)' % lang.upper()).replace(
            '__SUB__', 'ONYX BOOX RAPHAEL2 · 1264×1680 px · density 300 (1&nbsp;dp&nbsp;=&nbsp;1.875&nbsp;px) · '
                       'e-ink, только ч/б · язык: %s' % lang.upper())
        open(os.path.join(HERE, 'sheet_%s.html' % lang), 'w', encoding='utf-8').write(html)

    open(os.path.join(HERE, 'index.html'), 'w', encoding='utf-8').write(
        '<!doctype html><html lang="ru"><head><meta charset="utf-8">'
        '<title>Макеты «InkShelf»</title></head><body>'
        '<h1>Макеты UI «InkShelf» (1264×1680)</h1>'
        '<p><a href="sheet_ru.html">Лист макетов — русский</a> · '
        '<a href="sheet_en.html">English sheet</a></p><ul>%s</ul></body></html>' % '\n'.join(rows))
    print('листы: sheet_ru.html, sheet_en.html; экранов: %d' % len(rows))


if __name__ == '__main__':
    main()
