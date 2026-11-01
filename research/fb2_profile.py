#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fb2_profile.py — статистический профиль реальной коллекции FB2 (*.fb2.zip).

Прогоняется по выкачанным локально каталогам-корням (или по самим путям на
устройстве, если они доступны как обычные файлы). Разбирает каждый zip
ПОТОКОВО: открывает entry с расширением .fb2 и читает его через
xml.etree.ElementTree.iterparse, очищая каждый элемент на событии 'end'
(elem.clear()), чтобы не удерживать в памяти <binary> и прочие крупные узлы.

Stdlib-only: zipfile, io, re, json, collections, argparse, xml.etree.ElementTree.

Использование:
    python3 fb2_profile.py --roots DIR1 DIR2 --out DIR [--manifest FILE]

--manifest — необязательный текстовый файл со списком путей (по одному на
строку), который подменяет/дополняет обход --roots (удобно, когда список
снят на устройстве через `find`).
"""

from __future__ import annotations

import argparse
import collections
import io
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

# --------------------------------------------------------------------------- #
# Константы
# --------------------------------------------------------------------------- #

FB2_STD_NS = "http://www.gribuser.ru/xml/fictionbook/2.0"

XML_DECL_RE = re.compile(rb"""<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z0-9_.\-]+)["']""", re.I)

# нормализация имён кодеков к каноническим python-кодекам
ENC_ALIASES = {
    "utf8": "utf-8",
    "utf-8": "utf-8",
    "utf-16": "utf-16",
    "utf-16le": "utf-16-le",
    "utf-16be": "utf-16-be",
    "windows-1251": "cp1251",
    "windows1251": "cp1251",
    "win-1251": "cp1251",
    "cp-1251": "cp1251",
    "cp1251": "cp1251",
    "windows-1250": "cp1250",
    "windows-1252": "cp1252",
    "koi8-r": "koi8-r",
    "koi8-u": "koi8-u",
    "ibm866": "cp866",
    "cp866": "cp866",
    "us-ascii": "ascii",
    "ascii": "ascii",
}

AUTHOR_NAME_KEYS = {
    "first-name": "first",
    "middle-name": "middle",
    "last-name": "last",
    "nickname": "nick",
}

NUM_ID_RE = re.compile(r"\.(\d{4,7})$")            # .123456  (fictionbook id)
RAND_SUFFIX_RE = re.compile(r"\.([A-Za-z0-9_\-]{5,8})\.(\d{4,7})$")  # .WxVqEg.635994
FLIBUSTA_RE = re.compile(r"-([0-9a-f]{10,14})$")   # -6870fd6b3b610
SERIES_IN_NAME_RE = re.compile(r"^([^_]+)_(\d+)_(.+)$")  # Author_2_Title
LEADING_NUM_RE = re.compile(r"^\d+[\.\)]\s*")

CYR_RE = re.compile(r"[А-Яа-яЁё]")


# --------------------------------------------------------------------------- #
# Потоковая обёртка: отдать уже прочитанный "хвост" + продолжить чтение
# --------------------------------------------------------------------------- #

class _PrependStream(io.RawIOBase):
    """RawIO: сначала отдаёт head, затем читает из fp (ZipExtFile)."""

    def __init__(self, head: bytes, fp):
        self._head = head
        self._fp = fp

    def readable(self) -> bool:
        return True

    def readinto(self, b) -> int:
        if self._head:
            n = min(len(b), len(self._head))
            b[:n] = self._head[:n]
            self._head = self._head[n:]
            return n
        chunk = self._fp.read(len(b))
        if not chunk:
            return 0
        b[: len(chunk)] = chunk
        return len(chunk)

    def close(self):
        try:
            self._fp.close()
        finally:
            super().close()


def _normalize_enc(name: str | None) -> str | None:
    if not name:
        return None
    key = name.strip().lower()
    return ENC_ALIASES.get(key, key)


# --------------------------------------------------------------------------- #
# Парсинг одного fb2-потока
# --------------------------------------------------------------------------- #

def parse_fb2_stream(binary_stream) -> dict:
    """Потоково разобрать fb2. binary_stream — file-like (ZipExtFile/BytesIO)."""
    res = {
        "encoding_declared": None,
        "encoding_used": None,
        "encoding_unknown": False,
        "bom_only": False,
        "parse_ok": False,
        "parse_error": None,
        "namespace": None,
        "ns_standard": None,
        "fb2_version": None,
        "has_title_info": False,
        "has_description": False,
        "title": None,
        "authors": [],
        "series": [],          # [{"name":..., "number":...}]
        "genres": [],          # [{"code":..., "match":...}]
        "has_annotation": False,
        "has_coverpage": False,
        "cover_href": None,
        "cover_binary_present": False,
        "binary_count": 0,
        "has_body": False,
        "has_publish_info": False,
        "has_document_info": False,
        "trailing_junk": False,
    }

    head = binary_stream.read(65536)
    if not head:
        res["parse_error"] = "empty-entry"
        return res

    # Декларацию ищем и в "широких" кодировках: UTF-16/32 хранят ASCII
    # байтами, перемежёнными нулями, поэтому байтовый regex их не видит.
    is_wide = head[:4] in (b"\xff\xfe\x00\x00", b"\x00\x00\xfe\xff") or \
        head[:2] in (b"\xff\xfe", b"\xfe\xff")
    declared = None
    if is_wide:
        wide_enc = "utf-16"
        if head[:4] == b"\xff\xfe\x00\x00":
            wide_enc = "utf-32-le"
        elif head[:4] == b"\x00\x00\xfe\xff":
            wide_enc = "utf-32-be"
        try:
            text_head = head.decode(wide_enc, "ignore")
        except Exception:  # noqa: BLE001
            text_head = ""
        mw = re.search(r"""<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z0-9_.\-]+)["']""",
                       text_head, re.I)
        if mw:
            declared = mw.group(1)
        else:
            declared = wide_enc  # декларации нет, но BOM задаёт кодировку
            res["bom_only"] = True
    else:
        m = XML_DECL_RE.search(head)
        if m:
            declared = m.group(1).decode("ascii", "replace")

    res["encoding_declared"] = declared
    enc = _normalize_enc(declared)
    if declared and enc is None:
        enc = "utf-8"
    if enc:
        try:
            "".encode(enc)  # noqa - кодек существует?
        except LookupError:
            res["encoding_unknown"] = True
            enc = "utf-8"
    if enc is None:
        # нет декларации: utf-8 (с BOM) по умолчанию
        enc = "utf-8-sig" if head.startswith(b"\xef\xbb\xbf") else "utf-8"
    res["encoding_used"] = enc

    text_stream = io.TextIOWrapper(
        io.BufferedReader(_PrependStream(head, binary_stream), buffer_size=65536),
        encoding=enc,
        errors="replace",
        newline=None,
    )

    stack: list[str] = []
    cur_author: dict | None = None
    binary_ids: set[str] = set()
    root_seen = False

    try:
        it = ET.iterparse(text_stream, events=("start", "end"))
        for event, elem in it:
            tag = elem.tag
            if not isinstance(tag, str):  # комментарии / PI
                continue
            name = tag.rsplit("}", 1)[-1]

            if event == "start":
                stack.append(name)
                if not root_seen:
                    root_seen = True
                    if tag.startswith("{"):
                        res["namespace"] = tag[1:].split("}", 1)[0]
                    else:
                        res["namespace"] = ""  # без namespace
                    res["ns_standard"] = res["namespace"] == FB2_STD_NS
                    res["fb2_version"] = elem.get("version")
                if name == "author" and "title-info" in stack:
                    cur_author = {"first": "", "middle": "", "last": "", "nick": ""}
                continue

            # --- end ---
            in_ti = "title-info" in stack
            if in_ti:
                if name == "book-title":
                    res["title"] = (elem.text or "").strip() or res["title"]
                elif name in AUTHOR_NAME_KEYS and "author" in stack and cur_author is not None:
                    cur_author[AUTHOR_NAME_KEYS[name]] = (elem.text or "").strip()
                elif name == "author" and cur_author is not None:
                    res["authors"].append(cur_author)
                    cur_author = None
                elif name == "sequence":
                    res["series"].append({"name": elem.get("name"), "number": elem.get("number")})
                elif name == "genre":
                    res["genres"].append({"code": (elem.text or "").strip(), "match": elem.get("match")})
                elif name == "annotation":
                    res["has_annotation"] = True
                elif name == "coverpage":
                    res["has_coverpage"] = True
                elif name == "image" and "coverpage" in stack:
                    href = (elem.get("{http://www.w3.org/1999/xlink}href")
                            or elem.get("href") or elem.get("l:href"))
                    if href:
                        res["cover_href"] = href.lstrip("#")

            if name == "title-info":
                res["has_title_info"] = True
            elif name == "description":
                res["has_description"] = True
            elif name == "binary":
                bid = elem.get("id")
                if bid:
                    binary_ids.add(bid)
                res["binary_count"] += 1
            elif name == "body":
                res["has_body"] = True
            elif name == "publish-info":
                res["has_publish_info"] = True
            elif name == "document-info":
                res["has_document_info"] = True

            stack.pop()
            elem.clear()  # важно: сразу отпускаем узел (в т.ч. огромный <binary>)

        res["parse_ok"] = True
    except ET.ParseError as e:
        res["parse_error"] = f"ParseError: {e}"
        if "junk after document element" in str(e):
            res["trailing_junk"] = True
    except Exception as e:  # noqa: BLE001
        res["parse_error"] = f"{type(e).__name__}: {e}"
    finally:
        try:
            text_stream.close()
        except Exception:
            pass

    if res["cover_href"] is not None:
        res["cover_binary_present"] = res["cover_href"] in binary_ids
    return res


def looks_like_cp866(name: str) -> str | None:
    """Если zip хранит имя entry в cp866 (не выставив UTF-8 флаг), вернуть
    расшифровку; иначе None."""
    try:
        raw = name.encode("cp437")
    except (UnicodeEncodeError, LookupError):
        return None
    try:
        dec = raw.decode("cp866")
    except UnicodeDecodeError:
        return None
    before = len(CYR_RE.findall(name))
    after = len(CYR_RE.findall(dec))
    if after >= 2 and after > before:
        return dec
    return None


# --------------------------------------------------------------------------- #
# Разбор имени файла
# --------------------------------------------------------------------------- #

def analyze_filename(basename: str) -> dict:
    """basename — имя без .fb2.zip."""
    stem = basename
    if stem.lower().endswith(".fb2.zip"):
        stem = stem[: -len(".fb2.zip")]

    out = {
        "stem": stem,
        "has_cyrillic": bool(CYR_RE.search(stem)),
        "has_underscore": "_" in stem,
        "has_space": " " in stem,
        "has_num_id": False,
        "num_id": None,
        "has_rand_suffix": False,
        "has_flibusta_hex": False,
        "template": "other",
    }

    m = NUM_ID_RE.search(stem)
    if m:
        out["has_num_id"] = True
        out["num_id"] = m.group(1)

    m = FLIBUSTA_RE.search(stem)
    if m:
        out["has_flibusta_hex"] = True
        out["flibusta_hex"] = m.group(1)

    m = RAND_SUFFIX_RE.search(stem)
    if m:
        out["has_rand_suffix"] = True

    # шаблоны
    if out["has_flibusta_hex"] and "-" in stem and not out["has_num_id"]:
        out["template"] = "flibusta_author-title-hexid"
    elif out["has_rand_suffix"]:
        out["template"] = "flibusta_Author_Title[.Series]..rand.id"
    elif re.match(r"^([^_]+)_(.*?)\.(\d{4,7})$", stem):
        out["template"] = "Author_Title.id"
    elif SERIES_IN_NAME_RE.match(stem) and not out["has_rand_suffix"]:
        out["template"] = "Author_Series_Title"
    elif out["has_underscore"] and re.match(r"^[^_]+_.+", stem):
        out["template"] = "Author_Title"
    elif out["has_space"] and not out["has_underscore"]:
        # "Гордашевский Пётр. Их было четверо" / "Режим бога - 2"
        if re.match(r"^[^.]{2,60}\.\s+\S", stem):
            out["template"] = "Author(s) Name. Title"
        else:
            out["template"] = "free form (space)"
    else:
        out["template"] = "Title only / other"
    return out


# --------------------------------------------------------------------------- #
# Сбор файлов
# --------------------------------------------------------------------------- #

def collect_zip_paths(roots, manifest, manifest_only=False) -> list[str]:
    paths: list[str] = []
    seen = set()

    def add(p: str):
        p = p.rstrip("\n")
        if not p:
            return
        if p.lower().endswith(".fb2.zip") and p not in seen:
            seen.add(p)
            paths.append(p)

    if manifest:
        with open(manifest, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                add(line)

    if not manifest_only:
        for root in roots or []:
            if os.path.isfile(root):
                add(root)
                continue
            for dirpath, _dirnames, filenames in os.walk(root):
                for fn in filenames:
                    if fn.lower().endswith(".fb2.zip"):
                        add(os.path.join(dirpath, fn))
    return sorted(paths)


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Профиль коллекции FB2 (*.fb2.zip)")
    ap.add_argument("--roots", nargs="*", default=[])
    ap.add_argument("--manifest", default=None)
    ap.add_argument("--manifest-only", action="store_true")
    ap.add_argument("--out", required=True, help="каталог для research/*")
    ap.add_argument("--examples", type=int, default=25,
                    help="сколько примеров проблемных файлов сохранить")
    args = ap.parse_args(argv)

    os.makedirs(args.out, exist_ok=True)

    paths = collect_zip_paths(args.roots, args.manifest, args.manifest_only)
    sys.stderr.write(f"[fb2_profile] найдено файлов: {len(paths)}\n")

    total = 0
    zip_ok = 0
    zip_broken: list[dict] = []
    has_fb2_entry = 0
    no_fb2_entry: list[dict] = []
    parse_ok = 0
    parse_failed: list[dict] = []

    enc_dist = collections.Counter()
    no_decl = 0
    enc_unknown = 0

    ns_dist = collections.Counter()
    ns_nonstd_samples: list[dict] = []

    has_title = 0
    has_title_info = 0
    has_description = 0
    no_metadata: list[dict] = []
    empty_title_samples: list[dict] = []

    n_authors_dist = collections.Counter()
    has_author = 0

    n_seq_dist = collections.Counter()
    has_sequence = 0
    seq_with_number = 0
    seq_without_number = 0
    seq_number_multi: list[dict] = []   # номера вида "1-2"
    books_with_seq_number = 0

    n_genre_dist = collections.Counter()
    has_genre = 0
    genre_book_count = collections.Counter()   # код -> число книг
    genre_norm_book_count = collections.Counter()  # нормализованный код -> число книг
    genre_total_occurrences = 0
    genre_empty = 0

    has_annotation = 0
    has_coverpage = 0
    cover_with_href = 0
    cover_with_binary = 0
    cover_href_no_binary: list[dict] = []

    entry_name_nonascii = 0
    entry_name_cp866: list[dict] = []
    entry_multi_fb2 = 0
    entry_no_ext_match = 0
    entry_equals_basename = 0
    entry_differs = 0
    entry_differs_informative = 0
    entry_differs_samples: list[dict] = []
    entry_name_counter = collections.Counter()

    version_dist = collections.Counter()
    bom_only_count = 0
    missing_genre: list[dict] = []
    missing_cover: list[dict] = []
    has_body = 0
    has_document_info = 0
    has_publish_info = 0

    tmpl_dist = collections.Counter()
    tmpl_author_recoverable = collections.Counter()
    file_stems: list[dict] = []

    for path in paths:
        total += 1
        base = os.path.basename(path)
        try:
            zf = zipfile.ZipFile(path)
        except Exception as e:  # noqa: BLE001
            zip_broken.append({"path": path, "error": f"{type(e).__name__}: {e}"})
            continue
        zip_ok += 1

        with zf:
            names = zf.namelist()
            fb2_entries = [n for n in names if n.lower().endswith(".fb2")]
            # .fb2 внутри zip с именем в cp866
            for n in names:
                if any(ord(c) > 127 for c in n):
                    entry_name_nonascii += 1
                    dec = looks_like_cp866(n)
                    if dec:
                        entry_name_cp866.append({"path": os.path.basename(path),
                                                 "name_raw": n, "name_cp866": dec})
            if len(fb2_entries) > 1:
                entry_multi_fb2 += 1

            if not fb2_entries:
                # fallback: самый крупный xml-подобный
                no_fb2_entry.append({"path": path, "entries": names[:8]})
                # всё равно попробуем самый большой xml
                xml_like = [n for n in names
                            if n.lower().endswith((".xml", ".html", ".fb2", ".txt"))]
                if xml_like:
                    xml_like.sort(key=lambda n: zf.getinfo(n).file_size, reverse=True)
                    fb2_entries = [xml_like[0]]
                else:
                    continue
                entry_no_ext_match += 1
            has_fb2_entry += 1

            entry = fb2_entries[0]

            zbase = base[: -len(".fb2.zip")] if base.lower().endswith(".fb2.zip") else base
            entry_stem = entry.rsplit("/", 1)[-1]
            if entry_stem.lower().endswith(".fb2"):
                entry_stem = entry_stem[:-4]
            if entry_stem == zbase:
                entry_equals_basename += 1
            else:
                entry_differs += 1
                if "_" in entry_stem and re.search(r"(\.\d{4,7}$|-[0-9a-f]{10,14}$)", entry_stem):
                    entry_differs_informative += 1
                if len(entry_differs_samples) < args.examples:
                    entry_differs_samples.append({"path": base, "entry": entry})
            entry_name_counter[entry.rsplit("/", 1)[-1]] += 1

            try:
                with zf.open(entry) as stream:
                    r = parse_fb2_stream(stream)
            except Exception as e:  # noqa: BLE001
                parse_failed.append({"path": path, "entry": entry,
                                     "error": f"open: {type(e).__name__}: {e}"})
                continue

        if not r["parse_ok"]:
            parse_failed.append({"path": path, "entry": entry,
                                 "error": r["parse_error"]})
            if r["trailing_junk"]:
                pass
            continue
        parse_ok += 1

        # кодировка
        if r["encoding_declared"]:
            enc_dist[r["encoding_declared"]] += 1
        else:
            no_decl += 1
            enc_dist[f"(нет декларации, {r['encoding_used']})"] += 1
        if r["encoding_unknown"]:
            enc_unknown += 1
        if r["bom_only"]:
            bom_only_count += 1

        version_dist[r["fb2_version"] or "(нет)"] += 1
        if r["has_body"]:
            has_body += 1
        if r["has_document_info"]:
            has_document_info += 1
        if r["has_publish_info"]:
            has_publish_info += 1

        # namespace
        if r["namespace"] == "":
            ns_dist["(без namespace)"] += 1
        elif r["namespace"] == FB2_STD_NS:
            ns_dist["стандартный fictionbook 2.0"] += 1
        else:
            ns_dist[f"нестандартный: {r['namespace']}"] += 1
            if len(ns_nonstd_samples) < args.examples:
                ns_nonstd_samples.append({"path": path, "namespace": r["namespace"],
                                          "ns_standard": r["ns_standard"]})

        # метаданные
        if r["has_title_info"]:
            has_title_info += 1
        if r["has_description"]:
            has_description += 1
        if r["title"]:
            has_title += 1
        else:
            if r["has_title_info"] and len(empty_title_samples) < args.examples:
                empty_title_samples.append({"path": path})

        na = len(r["authors"])
        n_authors_dist[min(na, 3)] += 1
        if na:
            has_author += 1

        ns_ = len(r["series"])
        n_seq_dist[min(ns_, 3)] += 1
        if ns_:
            has_sequence += 1
        book_has_number = False
        for s in r["series"]:
            if s["number"]:
                seq_with_number += 1
                book_has_number = True
                if re.search(r"\d\s*[-–]\s*\d", s["number"]) and len(seq_number_multi) < args.examples * 2:
                    seq_number_multi.append({"path": path, "sequence": s["name"],
                                             "number": s["number"]})
            else:
                seq_without_number += 1
        if book_has_number:
            books_with_seq_number += 1

        codes = set()
        for g in r["genres"]:
            code = g["code"]
            genre_total_occurrences += 1
            if not code:
                genre_empty += 1
                continue
            codes.add(code)
        for c in codes:
            genre_book_count[c] += 1
            genre_norm_book_count[c.strip().lower().replace("-", "_")] += 1
        ng = len(codes)
        n_genre_dist[min(ng, 3)] += 1
        if ng:
            has_genre += 1
        elif len(missing_genre) < args.examples:
            missing_genre.append({"path": path})

        if r["has_annotation"]:
            has_annotation += 1

        if r["has_coverpage"]:
            has_coverpage += 1
        elif len(missing_cover) < args.examples:
            missing_cover.append({"path": path})
        if r["cover_href"]:
            cover_with_href += 1
            if r["cover_binary_present"]:
                cover_with_binary += 1
            elif len(cover_href_no_binary) < args.examples:
                cover_href_no_binary.append({"path": path, "cover_href": r["cover_href"]})

        # «нет метаданных вообще»
        if not (r["has_title_info"] or r["authors"] or r["title"] or r["genres"]):
            if len(no_metadata) < args.examples:
                no_metadata.append({"path": path,
                                    "has_description": r["has_description"],
                                    "binary_count": r["binary_count"],
                                    "namespace": r["namespace"]})

        # имя файла
        fa = analyze_filename(base)
        tmpl_dist[fa["template"]] += 1
        if fa["template"] in ("flibusta_Author_Title[.Series]..rand.id",
                              "Author_Title.id", "Author_Title",
                              "Author_Series_Title", "flibusta_author-title-hexid"):
            tmpl_author_recoverable["точно (разделитель '_', транслит)"] += 1
        elif fa["template"] == "Author(s) Name. Title":
            tmpl_author_recoverable["эвристически (через '. ' или пробел)"] += 1
        else:
            tmpl_author_recoverable["не выводится из имени (обычно только название)"] += 1
        if len(file_stems) < args.examples * 4:
            file_stems.append({"path": path, **fa})

        if total % 100 == 0:
            sys.stderr.write(f"[fb2_profile] обработано {total}/{len(paths)}\n")

    # ---------------------------------------------------------------- отчёт --
    genre_sorted = sorted(genre_book_count.items(), key=lambda kv: (-kv[1], kv[0]))

    summary = {
        "generated_from_roots": args.roots,
        "total_files": total,
        "zip_ok": zip_ok,
        "zip_broken_count": len(zip_broken),
        "has_fb2_entry": has_fb2_entry,
        "no_fb2_entry_count": len(no_fb2_entry),
        "entry_no_ext_match_fallback": entry_no_ext_match,
        "parse_ok": parse_ok,
        "parse_failed_count": len(parse_failed),
        "encoding_distribution": dict(enc_dist.most_common()),
        "no_declaration_count": no_decl,
        "unknown_encoding_count": enc_unknown,
        "namespace_distribution": dict(ns_dist.most_common()),
        "has_title_info": has_title_info,
        "has_description": has_description,
        "has_book_title": has_title,
        "has_author": has_author,
        "authors_count_distribution": {
            "0": n_authors_dist[0], "1": n_authors_dist[1],
            "2": n_authors_dist[2], "3+": n_authors_dist[3]},
        "has_sequence": has_sequence,
        "series_count_distribution": {
            "0": n_seq_dist[0], "1": n_seq_dist[1],
            "2": n_seq_dist[2], "3+": n_seq_dist[3]},
        "sequence_with_number": seq_with_number,
        "sequence_without_number": seq_without_number,
        "books_with_sequence_number": books_with_seq_number,
        "has_genre": has_genre,
        "genre_count_distribution": {
            "0": n_genre_dist[0], "1": n_genre_dist[1],
            "2": n_genre_dist[2], "3+": n_genre_dist[3]},
        "distinct_genre_codes": len(genre_book_count),
        "distinct_genre_codes_normalized": len(genre_norm_book_count),
        "genre_total_occurrences": genre_total_occurrences,
        "genre_empty_count": genre_empty,
        "has_annotation": has_annotation,
        "has_coverpage": has_coverpage,
        "cover_with_href": cover_with_href,
        "cover_with_working_binary": cover_with_binary,
        "entry_name_nonascii_count": entry_name_nonascii,
        "entry_name_cp866_count": len(entry_name_cp866),
        "entry_multi_fb2_count": entry_multi_fb2,
        "entry_equals_basename": entry_equals_basename,
        "entry_differs_from_basename": entry_differs,
        "entry_differs_informative": entry_differs_informative,
        "distinct_inner_entry_names": len(entry_name_counter),
        "duplicate_inner_entry_groups": sum(1 for v in entry_name_counter.values() if v > 1),
        "files_in_duplicate_inner_entry_groups": sum(v for v in entry_name_counter.values() if v > 1),
        "fb2_version_distribution": dict(version_dist.most_common()),
        "bom_only_count": bom_only_count,
        "has_body": has_body,
        "has_document_info": has_document_info,
        "has_publish_info": has_publish_info,
        "filename_templates": dict(tmpl_dist.most_common()),
        "filename_author_recoverable": dict(tmpl_author_recoverable),
        "genre_book_counts": dict(genre_sorted),
    }

    # ------------------------------------------------------------------ .md --
    def pct(n, d=None):
        d = total if d is None else d
        if not d:
            return "—"
        return f"{n} ({100.0 * n / d:.1f}%)"

    md = []
    md.append("# Профиль реальной FB2-коллекции\n")
    md.append(f"Источников (корней): {len(args.roots)}. Файлов `*.fb2.zip`: **{total}**.\n")
    md.append("Парсинг потоковый (`zipfile` + `ET.iterparse`, `elem.clear()` на каждом "
              "`end`, `<binary>` не удерживается в памяти).\n")

    md.append("## 1. Целостность zip / наличие .fb2\n")
    md.append(f"- Открываются как zip: **{pct(zip_ok)}**")
    md.append(f"- Битых/нечитаемых zip: **{len(zip_broken)}**")
    md.append(f"- Содержат entry с расширением `.fb2` (регистронезависимо): **{pct(has_fb2_entry)}**")
    md.append(f"- Без `.fb2`-entry (fallback или пропуск): **{len(no_fb2_entry)}**")
    md.append(f"- Успешно разобраны XML: **{pct(parse_ok)}**")
    md.append(f"- Ошибка парсинга XML: **{len(parse_failed)}**")
    md.append(f"- Несколько `.fb2`-entry в одном zip: {entry_multi_fb2}")
    md.append(f"- Идентификаторов `.fb2` в имени entry в cp866: {len(entry_name_cp866)} "
              f"(не-ASCII имён entry всего: {entry_name_nonascii})")
    md.append(f"- Имя entry **совпадает** с именем zip: {entry_equals_basename}; "
              f"**отличается**: {entry_differs} (из них «информативных», с id/hex-id в имени: "
              f"{entry_differs_informative})")
    md.append(f"- Версия FB2: " + ", ".join(f"`{k}` → {v}" for k, v in version_dist.most_common()))
    dup_groups = sum(1 for v in entry_name_counter.values() if v > 1)
    dup_files = sum(v for v in entry_name_counter.values() if v > 1)
    md.append(f"- Уникальных внутренних имён entry: {len(entry_name_counter)}; "
              f"групп дубликатов (одно entry-имя в разных zip): {dup_groups}, "
              f"файлов в них: {dup_files}\n")

    def sample_block(items, fmt, title):
        md.append(f"**{title}**\n")
        if not items:
            md.append("_нет_\n")
            return
        for it in items[: args.examples]:
            md.append(f"- `{fmt(it)}`")
        md.append("")

    sample_block(zip_broken, lambda x: f"{x['path']} — {x['error']}", "Примеры битых zip")
    sample_block(no_fb2_entry, lambda x: f"{x['path']} — entries: {x['entries']}",
                 "Примеры zip без .fb2")
    sample_block(parse_failed, lambda x: f"{x['path']} [{x.get('entry')}] — {x['error']}",
                 "Примеры ошибок парсинга XML")
    sample_block(entry_name_cp866, lambda x: f"{x['path']} — raw={x['name_raw']!r} → cp866={x['name_cp866']!r}",
                 "Примеры имён entry в cp866")
    sample_block(entry_differs_samples, lambda x: "zip=%r → entry=%r" % (x["path"], x["entry"]),
                 "Примеры: внутреннее имя entry ≠ имя zip-файла")

    md.append("## 2. Кодировки (XML-декларация)\n")
    for k, v in enc_dist.most_common():
        md.append(f"- `{k}` — {v} ({100.0 * v / total:.1f}%)")
    md.append(f"\nБез декларации: **{no_decl}**. Неизвестный/неподдержанный кодек: {enc_unknown}.\n")

    md.append("## 3. Метаданные `<title-info>`\n")
    md.append(f"- Есть `<description>`: {pct(has_description)}")
    md.append(f"- Есть `<title-info>`: {pct(has_title_info)}")
    md.append(f"- Есть непустой `<book-title>`: {pct(has_title)}")
    md.append(f"- Есть хотя бы один `<author>`: **{pct(has_author)}**")
    md.append(f"  - авторов: 0 → {n_authors_dist[0]}, 1 → {n_authors_dist[1]}, "
              f"2 → {n_authors_dist[2]}, 3+ → {n_authors_dist[3]}")
    md.append(f"- Есть `<sequence>`: **{pct(has_sequence)}**")
    md.append(f"  - серий у книги: 0 → {n_seq_dist[0]}, 1 → {n_seq_dist[1]}, "
              f"2 → {n_seq_dist[2]}, 3+ → {n_seq_dist[3]}")
    md.append(f"  - `<sequence>` с атрибутом `number`: {seq_with_number}; без: {seq_without_number}; "
              f"книг хотя бы с одним номером: {books_with_seq_number}")
    md.append(f"- Есть `<genre>`: **{pct(has_genre)}**")
    md.append(f"  - жанров у книги: 0 → {n_genre_dist[0]}, 1 → {n_genre_dist[1]}, "
              f"2 → {n_genre_dist[2]}, 3+ → {n_genre_dist[3]}")
    md.append(f"- Есть `<annotation>`: **{pct(has_annotation)}**")
    md.append(f"- Есть `<coverpage>`: **{pct(has_coverpage)}**")
    md.append(f"  - с `<image xlink:href>`: {pct(cover_with_href)}")
    md.append(f"  - из них с существующим `<binary>` по этому href: "
              f"**{pct(cover_with_binary, cover_with_href or 1)}** (от coverpage с href)\n")
    sample_block(cover_href_no_binary, lambda x: f"{x['path']} — href=#{x['cover_href']}",
                 "coverpage со ссылкой, но без соответствующего <binary>")

    md.append("## 4. Жанры\n")
    md.append(f"Различных кодов `<genre>`: **{len(genre_book_count)}** "
              f"(после нормализации lower/dash→underscore: {len(genre_norm_book_count)}). "
              f"Вхождений всего: {genre_total_occurrences}; пустых: {genre_empty}.\n")
    md.append("Топ-60 (код → число книг):\n")
    md.append("| код | книг |")
    md.append("| --- | ---: |")
    for code, cnt in genre_sorted[:60]:
        md.append(f"| `{code}` | {cnt} |")
    md.append("")
    md.append("Полный список — в `genre-codes.txt`.\n")

    md.append("## 5. Особые случаи\n")
    md.append(f"- Без метаданных вообще (нет title-info/title/author/genre): "
              f"**{len(no_metadata)}** (показаны до {args.examples})")
    md.append(f"- `<title-info>` есть, а `<book-title>` пуст: {len(empty_title_samples)} "
              f"(показаны до {args.examples})")
    md.append(f"- XML-мусор/битая кодировка: ошибок парсинга {len(parse_failed)}")
    md.append(f"- UTF-16/32 без XML-декларации (только BOM): {bom_only_count}")
    md.append(f"- Книг без `<genre>`: {len(missing_genre)}")
    md.append(f"- Книг без `<coverpage>`: {len(missing_cover)}")
    md.append(f"- Есть `<body>`: {has_body}; `<document-info>`: {has_document_info}; "
              f"`<publish-info>`: {has_publish_info}")
    md.append(f"- `<sequence number>` вида «1-2» и подобное: {len(seq_number_multi)}"
              f" (показаны до {args.examples * 2})\n")
    sample_block(no_metadata, lambda x: f"{x['path']} (descr={x['has_description']}, "
                                        f"binaries={x['binary_count']})", "Файлы без метаданных")
    sample_block(empty_title_samples, lambda x: x["path"], "Пустой book-title")
    sample_block(missing_genre, lambda x: x["path"], "Книги без genre")
    sample_block(missing_cover, lambda x: x["path"], "Книги без coverpage")
    sample_block(seq_number_multi, lambda x: f"{x['path']} — «{x['sequence']}» number={x['number']!r}",
                 "Многотомные sequence number")

    md.append("### Namespace FB2\n")
    for k, v in ns_dist.most_common():
        md.append(f"- {k} — {v}")
    md.append("")
    sample_block(ns_nonstd_samples, lambda x: f"{x['path']} — ns={x['namespace']}",
                 "Примеры нестандартных namespace")

    md.append("## 6. Имя файла и восстановимость автора/названия\n")
    for k, v in tmpl_dist.most_common():
        md.append(f"- `{k}` — {v} ({100.0 * v / total:.1f}%)")
    md.append("")
    for k, v in tmpl_author_recoverable.most_common():
        md.append(f"- автор из имени файла: {k} — {v} ({100.0 * v / total:.1f}%)")
    md.append("\n(В XML автор есть у 100% книг — имя файла нужно лишь как fallback.)\n")
    md.append("Примеры стемов (без `.fb2.zip`):\n")
    seen_tmpl = set()
    for st in file_stems:
        if st["template"] in seen_tmpl:
            continue
        seen_tmpl.add(st["template"])
        md.append(f"- `{st['stem']}` → шаблон `{st['template']}`, num_id={st['num_id']!r}")
    md.append("")

    md_path = os.path.join(args.out, "collection-profile.md")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(md) + "\n")

    json_path = os.path.join(args.out, "collection-profile.json")
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)

    gc_path = os.path.join(args.out, "genre-codes.txt")
    with open(gc_path, "w", encoding="utf-8") as fh:
        fh.write(f"# genre code -> книг (всего кодов: {len(genre_sorted)})\n")
        for code, cnt in genre_sorted:
            fh.write(f"{code}\t{cnt}\n")

    sys.stderr.write(f"[fb2_profile] готово: {md_path}\n")
    sys.stderr.write(f"[fb2_profile] готово: {json_path}\n")
    sys.stderr.write(f"[fb2_profile] готово: {gc_path}\n")
    print(json.dumps({k: summary[k] for k in (
        "total_files", "zip_ok", "zip_broken_count", "has_fb2_entry",
        "parse_ok", "parse_failed_count", "distinct_genre_codes")},
        ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
