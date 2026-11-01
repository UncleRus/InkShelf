#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Генератор синтетической библиотеки для M9 (PLAN.md §6.4, §10).

Создаёт детерминированную (по seed) коллекцию книг в трёх форматах:
  * ``*.fb2.zip``  — FB2 в zip (основной формат, по умолчанию ~80 %);
  * ``*.epub``     — минимальный EPUB2/calibre;
  * ``*.mobi``     — минимальный PDB/MOBI с EXTH.

Все три формата читаются штатными ``fmt``-парсерами приложения
(``Fb2Reader`` / ``EpubReader`` / ``MobiReader``), поэтому библиотека годится
и для профилирования скана/разбора, и для проверки мультиформатности.

Запуск (только stdlib, без зависимостей):

    python3 research/gen-synthetic.py -n 100000 -o /tmp/opencode/synth --seed 42

Каталог вывода: по умолчанию плоский; ``--dirs K`` раскладывает файлы по ``K``
подкаталогам (ускоряет файловые операции на больших N).
"""

import argparse
import os
import random
import struct
import sys
import zipfile
from datetime import datetime, timedelta

# --- данные для наполнения ------------------------------------------------

SURNAMES = [
    "Лукин", "Пушкин", "Толстой", "Достоевский", "Чехов", "Гоголь", "Булгаков",
    "Набоков", "Стругацкий", "Ефремов", "Лукьяненко", "Пелевин", "Сорокин",
    "Акунин", "Маринина", "Устинова", "Донцова", "Шилова", "Токарева",
    "Петрушевская", "Улицкая", "Рубина", "Толстая", "Водолазкин", "Яхина",
    "Сальников", "Служитель", "Прилепин", "Захарова", "Глуховский", "Дивов",
    "Панов", "Олди", "Дяченко", "Семёнова", "Фрай", "Логинов", "Старухин",
    "Камша", "Раткевич", "Иванов", "Смирнов", "Кузнецов", "Попов", "Соколов",
    "Лебедев", "Козлов", "Новиков", "Морозов", "Волков", "Зайцев", "Павлов",
    "Семенов", "Голубев", "Виноградов", "Богданов", "Воробьев", "Федоров",
    "Михайлов", "Беляев", "Тарасов", "Белов", "Комаров", "Орлов", "Киселев",
    "Макаров", "Андреев", "Ковалев", "Ильин", "Гусев", "Титов", "Кудрявцев",
    "Баранов", "Куликов", "Алексеев", "Степанов", "Яковлев", "Сорокин",
    "Сергеев", "Романов", "Захаров", "Борисов", "Королев", "Герасимов",
    # нарочно разными буквами (#, латиница, цифра) — проверка разделов
    "Ötomo", "Ōe", "Zelazny", "Asimov", "Bradbury", "Clarke", "Dick",
    "1Петров", "8Марта",
]

NAMES_M = ["Александр", "Иван", "Пётр", "Николай", "Сергей", "Андрей", "Михаил",
           "Виктор", "Олег", "Дмитрий", "Максим", "Роман", "Артём", "Юрий"]
NAMES_F = ["Анна", "Мария", "Ольга", "Елена", "Татьяна", "Наталья", "Ирина",
           "Светлана", "Юлия", "Дарья", "Ксения", "Вера", "София", "Алиса"]

GENRE_CODES = [
    "sf", "sf_history", "sf_action", "sf_fantasy", "sf_social", "sf_space",
    "sf_detective", "sf_humor", "sf_cyberpunk", "sf_postapocalyptic",
    "det_action", "detective", "love", "love_sf", "humor_prose", "humor",
    "prose_contemporary", "prose_military", "prose_history", "adventure",
    "adv_history", "fantasy", "urban_fantasy", "science", "sci_phys",
    "nonf_biography", "nonf_publicism", "poetry", "children", "child_sf",
    "popadancy", "sf_heroic", "sf_litrpg", "ref_guide",
]

TITLE_WORDS = [
    "Тень", "Ветер", "Пепел", "Звезда", "Путь", "Кровь", "Сталь", "Ночь",
    "Свет", "Дорога", "Сердце", "Пламя", "Лёд", "Камень", "Песня", "Тайна",
    "Хроники", "Клинок", "Башня", "Порт", "Море", "Город", "Сад", "Дом",
    "Легенда", "Империя", "Корона", "Дракон", "Магия", "Враг", "Друг",
    "Пыль", "Гроза", "Рассвет", "Закат", "Молчание", "Крик", "Шёпот",
    "Зеркало", "Память", "Судьба", "Выбор", "Потеря", "Надежда", "Клятва",
]

SERIES_WORDS = [
    "Хроники", "Сага о", "Мир", "Клан", "Академия", "Легенды", "Эпоха",
    "Тёмные", "Последний", "Дети", "Хранители", "Орден", "Королевство",
    "Империя", "Звёздный", "Железный", "Алый", "Северный", "Забытый",
]

ANNOTATION = (
    "Это синтетическая книга, созданная для нагрузочного теста каталога "
    "InkShelf. Текст аннотации намеренно повторяется, чтобы проверить, что "
    "длинные описания корректно обрезаются при разборе и не раздувают память. "
)


def make_title(rnd, long_title_chance):
    n = rnd.choice([2, 2, 3, 3, 4, 5])
    parts = [rnd.choice(TITLE_WORDS) for _ in range(n)]
    title = " ".join(parts)
    if rnd.random() < long_title_chance:
        # очень длинное название — материал для автотеста обрезки (§8, №7)
        extra = " ".join(rnd.choice(TITLE_WORDS) for _ in range(rnd.randint(8, 16)))
        title = title + " и " + extra
    return title


def make_authors(rnd):
    k = rnd.choices([1, 2, 3], weights=[80, 15, 5])[0]
    seen = set()
    authors = []
    while len(authors) < k:
        surname = rnd.choice(SURNAMES)
        if surname in seen:
            continue
        seen.add(surname)
        names = NAMES_F if surname.endswith(("а", "я")) else NAMES_M
        authors.append((rnd.choice(names), surname))
    return authors


def make_genres(rnd):
    k = rnd.choices([1, 2, 3], weights=[70, 25, 5])[0]
    return rnd.sample(GENRE_CODES, k)


# --- форматы --------------------------------------------------------------

FB2_TMPL = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description>
<title-info>
{authors}{genres}<book-title>{title}</book-title>
<lang>ru</lang>
{series}</title-info>
<document-info><author><nickname>gen</nickname></author>
<program-used>gen-synthetic.py</program-used><date value="2026-11-01">2026-11-01</date>
<id>{uid}</id><version>1.0</version></document-info>
</description>
<body><section><title><p>Глава 1</p></title>
<annotation><p>{annotation}</p></annotation>
<p>Синтетический текст для нагрузочного теста. {filler}</p>
</section></body>
</FictionBook>
"""


def write_fb2zip(path, rnd, rec):
    genres = "".join("<genre>%s</genre>" % g for g in rec["genres"])
    authors = "".join(
        "<author><first-name>%s</first-name><last-name>%s</last-name></author>"
        % (f, l) for f, l in rec["authors"])
    series = ""
    if rec["series"]:
        series = "<sequence name=\"%s\" number=\"%d\"/>" % (rec["series"], rec["series_no"])
    xml = FB2_TMPL.format(
        authors=authors, genres=genres,
        title=rec["title"], uid=rec["uid"], annotation=ANNOTATION,
        filler=" ".join(rnd.choice(TITLE_WORDS) for _ in range(40)),
        series=series)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as z:
        z.writestr("book.fb2", xml.encode("utf-8"))


EPUB_CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""

EPUB_OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
<dc:title>{title}</dc:title>
{creators}{subjects}<dc:language>ru</dc:language>
<dc:identifier id="bookid">{uid}</dc:identifier>
<dc:description>{annotation}</dc:description>
<meta name="calibre:series" content="{series}"/>
<meta name="calibre:series_index" content="{series_no}"/>
</metadata>
<manifest><item id="c1" media-type="application/xhtml+xml" href="ch1.xhtml"/></manifest>
<spine><itemref idref="c1"/></spine>
</package>
"""

EPUB_CH = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title></head>
<body><h1>{title}</h1><p>{annotation}</p></body></html>
"""


def write_epub(path, rnd, rec):
    creators = "".join("<dc:creator>%s %s</dc:creator>" % (f, l) for f, l in rec["authors"])
    subjects = "".join("<dc:subject>%s</dc:subject>" % g for g in rec["genres"])
    opf = EPUB_OPF.format(title=rec["title"], creators=creators, subjects=subjects,
                          uid=rec["uid"], annotation=ANNOTATION,
                          series=rec["series"] or "", series_no=rec["series_no"])
    ch = EPUB_CH.format(title=rec["title"], annotation=ANNOTATION)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip",
                   compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", EPUB_CONTAINER)
        z.writestr("OEBPS/content.opf", opf)
        z.writestr("OEBPS/ch1.xhtml", ch)


# --- MOBI (PDB) -----------------------------------------------------------

def _exth_record(entries):
    """entries: список (type, data-bytes)."""
    body = b""
    for t, data in entries:
        body += struct.pack(">II", t, 8 + len(data)) + data
    return b"EXTH" + struct.pack(">II", 12 + len(body), len(entries)) + body


def write_mobi(path, rnd, rec):
    name = rec["title"].encode("utf-8")
    authors = ["%s %s" % (f, l) for f, l in rec["authors"]]
    entries = []
    for a in authors:
        entries.append((100, a.encode("utf-8")))
    entries.append((103, ANNOTATION.encode("utf-8")))
    for g in rec["genres"]:
        entries.append((105, g.encode("utf-8")))
    entries.append((503, name))
    exth = _exth_record(entries)

    HEADER_LEN = 232
    palm = struct.pack(">HHIHHHH", 1, 0, 4096, 1, 4096, 0, 0)  # 16 байт
    hdr = bytearray(HEADER_LEN)
    hdr[0:4] = b"MOBI"
    struct.pack_into(">I", hdr, 4, HEADER_LEN)      # headerLength
    struct.pack_into(">I", hdr, 8, 2)               # mobiType = book
    struct.pack_into(">I", hdr, 12, 65001)          # textEncoding = UTF-8
    name_off = len(palm) + HEADER_LEN + len(exth)
    struct.pack_into(">I", hdr, 0x44, name_off)     # full name offset
    struct.pack_into(">I", hdr, 0x48, len(name))    # full name length
    struct.pack_into(">I", hdr, 0x5C, 0)            # firstImage
    struct.pack_into(">I", hdr, 0x70, 0x40)         # exthFlags set

    rec0 = bytes(palm) + bytes(hdr) + exth + name
    rec1 = b"<html><body>synthetic</body></html>"

    recs = [rec0, rec1]
    num = len(recs)
    base = 78 + num * 8
    offsets = []
    off = base
    for r in recs:
        offsets.append(off)
        off += len(r)
    total_len = off

    pdb = bytearray(78)
    nm = rec["uid"].encode("ascii", "replace")[:31]
    pdb[0:len(nm)] = nm  # имя базы PDB, остаток — нули
    struct.pack_into(">H", pdb, 32, 0)     # attributes
    struct.pack_into(">H", pdb, 34, 0)     # version
    struct.pack_into(">I", pdb, 36, 0)     # creationDate
    struct.pack_into(">I", pdb, 40, 0)     # modificationDate
    struct.pack_into(">I", pdb, 44, 0)     # backupDate
    struct.pack_into(">I", pdb, 48, 0)     # modificationNumber
    struct.pack_into(">I", pdb, 52, 0)     # appInfoID
    struct.pack_into(">I", pdb, 56, 0)     # sortInfoID
    pdb[60:64] = b"BOOK"
    pdb[64:68] = b"MOBI"
    struct.pack_into(">I", pdb, 68, 1)     # uniqueIDseed
    struct.pack_into(">I", pdb, 72, 0)     # nextRecordListID
    struct.pack_into(">H", pdb, 76, num)   # numRecords

    recinfo = b""
    for i, r in enumerate(recs):
        recinfo += struct.pack(">IB3s", offsets[i], 0, bytes([i, 0, 0]))
    with open(path, "wb") as f:
        f.write(bytes(pdb))
        f.write(recinfo)
        for r in recs:
            f.write(r)
    assert total_len == os.path.getsize(path)


# --- генерация ------------------------------------------------------------

def build(target, rnd, long_title_chance):
    rec = {}
    rec["authors"] = make_authors(rnd)
    rec["title"] = make_title(rnd, long_title_chance)
    rec["genres"] = make_genres(rnd)
    if rnd.random() < 0.45:
        rec["series"] = "%s %s" % (rnd.choice(SERIES_WORDS), rnd.choice(TITLE_WORDS))
        rec["series_no"] = rnd.randint(1, 30)
    else:
        rec["series"] = ""
        rec["series_no"] = 0
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-n", "--count", type=int, default=100000)
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--dirs", type=int, default=0, help="число подкаталогов (0 — плоско)")
    ap.add_argument("--long-title-chance", type=float, default=0.05)
    ap.add_argument("--fb2-pct", type=int, default=80)
    ap.add_argument("--epub-pct", type=int, default=10)
    ap.add_argument("--mobi-pct", type=int, default=10)
    args = ap.parse_args()

    rnd = random.Random(args.seed)
    os.makedirs(args.out, exist_ok=True)
    subs = []
    for i in range(args.dirs):
        d = os.path.join(args.out, "d%03d" % i)
        os.makedirs(d, exist_ok=True)
        subs.append(d)

    total = args.count
    stats = {"fb2zip": 0, "epub": 0, "mobi": 0}
    t0 = datetime.now()
    for i in range(total):
        rec = build(None, rnd, args.long_title_chance)
        rec["uid"] = "gen-%07d" % i
        r = rnd.randint(1, 100)
        if r <= args.fb2_pct:
            ext = "fb2.zip"
            stats["fb2zip"] += 1
        elif r <= args.fb2_pct + args.epub_pct:
            ext = "epub"
            stats["epub"] += 1
        else:
            ext = "mobi"
            stats["mobi"] += 1
        d = subs[i % len(subs)] if subs else args.out
        fname = "%s_%s.%s" % (rec["authors"][0][1], rec["uid"], ext)
        path = os.path.join(d, fname)
        if ext == "fb2.zip":
            write_fb2zip(path, rnd, rec)
        elif ext == "epub":
            write_epub(path, rnd, rec)
        else:
            write_mobi(path, rnd, rec)
        if (i + 1) % 10000 == 0:
            dt = (datetime.now() - t0).total_seconds()
            print("  %d/%d  (%.0f c, %.0f кн/с)" % (i + 1, total, dt, (i + 1) / max(dt, 1e-9)),
                  flush=True)
    print("готово: %d книг -> %s" % (total, args.out), flush=True)
    print("форматы: %s" % stats, flush=True)


if __name__ == "__main__":
    main()
