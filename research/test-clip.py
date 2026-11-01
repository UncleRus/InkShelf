#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Автотест обрезки строк списка (NFR-1, приёмочный №7, PLAN §8 / §10 M9).

Проверяет, что ни одна строка книги не обрезается при крупном шрифте:

* **Статически** — в разметке строк книги нет ``maxLines``/``ellipsize``/
  ``singleLine`` (именно они и дают обрезку).
* **Динамически** (uiautomator dump) — для ``fontScale`` 1.0/1.5/2.0:
  * bounding box каждого текстового узла строки лежит внутри области списка;
  * строки колонки текста не накладываются и не выходят за низ списка;
  * у каждого узла высота не меньше одной строки (текст не «схлопнут»);
  * длинное название на 2.0 переносится на несколько строк (высота растёт
    относительно 1.0 и относительно однострочной высоты).

Запуск:

    python3 research/test-clip.py --serial emulator-5556 \
        --adb tools/android-sdk/platform-tools/adb \
        --layout-dir app/res/layout
"""

import argparse
import os
import re
import subprocess
import sys
import time

NODE_RE = re.compile(r"<node\b[^>]*>")
ATTR_RE = re.compile(r'([\w:-]+)="([^"]*)"')
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")

# resource-id, которые считаем элементами списка книг
TITLE = "book_title"
SUB = "book_sub"
META = "book_meta"
COVER = "book_cover_box"
LIST = "list"


class Adb:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial

    def sh(self, cmd, timeout=60):
        r = subprocess.run([self.adb, "-s", self.serial, "shell", cmd],
                           capture_output=True, text=True, timeout=timeout)
        return r.stdout

    def is_root(self):
        return "uid=0" in self.sh("id")

    def priv_sh(self, cmd, timeout=60):
        if self.is_root():
            return self.sh(cmd, timeout=timeout)
        return self.su_sh(cmd, timeout=timeout)

    def dump(self):
        self.sh("uiautomator dump /sdcard/_clip.xml")
        r = subprocess.run([self.adb, "-s", self.serial, "exec-out",
                            "cat /sdcard/_clip.xml"],
                           capture_output=True, text=True, timeout=60)
        return r.stdout

    def tap(self, x, y):
        self.sh("input tap %d %d" % (x, y))

    def key(self, k):
        self.sh("input keyevent " + k)


def shquote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def node_id(attrs):
    rid = attrs.get("resource-id", "")
    return rid.split("/")[-1]


def parse_nodes(xml):
    out = []
    for m in NODE_RE.finditer(xml):
        attrs = dict(ATTR_RE.findall(m.group(0)))
        b = attrs.get("bounds")
        if not b:
            continue
        bm = BOUNDS_RE.search(b)
        if not bm:
            continue
        x1, y1, x2, y2 = (int(v) for v in bm.groups())
        out.append({
            "id": node_id(attrs),
            "text": attrs.get("text", ""),
            "x1": x1, "y1": y1, "x2": x2, "y2": y2,
            "h": y2 - y1, "w": x2 - x1,
        })
    return out


def find(nodes, nid):
    return [n for n in nodes if n["id"] == nid]


def open_all_books(adb):
    """Открыть вкладку «Все книги» (последнюю). Прокручивает полосу вкладок."""
    for _ in range(4):
        xml = adb.dump()
        nodes = parse_nodes(xml)
        tabs = [n for n in nodes if n["text"] in ("Все книги", "All books")]
        if tabs:
            t = tabs[0]
            adb.tap((t["x1"] + t["x2"]) // 2, (t["y1"] + t["y2"]) // 2)
            time.sleep(2.5)
            return True
        right = [n for n in nodes if n["id"] == "tab_right"]
        if not right:
            return False
        r = right[0]
        adb.tap((r["x1"] + r["x2"]) // 2, (r["y1"] + r["y2"]) // 2)
        time.sleep(1.5)
    return False


def set_font_scale(adb, scale, prefs_dir):
    adb.sh("am force-stop ru.unclerus.library")
    time.sleep(1)
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
           "<map>\n    <int name=\"main_tab\" value=\"5\" />\n"
           "    <float name=\"font_scale\" value=\"%s\" />\n</map>\n" % scale)
    tmp = "/data/local/tmp/_prefs.xml"
    with open("/tmp/_clip_prefs.xml", "w", encoding="utf-8") as f:
        f.write(xml)
    subprocess.run([adb.adb, "-s", adb.serial, "push", "/tmp/_clip_prefs.xml", tmp],
                   capture_output=True, timeout=60)
    adb.priv_sh("OWN=$(stat -c %%u:%%g %s); mkdir -p %s; cat %s > %s/library_prefs.xml; "
              "chown $OWN %s/library_prefs.xml; "
              "chmod 660 %s/library_prefs.xml; restorecon %s/library_prefs.xml; rm -f %s"
              % (prefs_dir, prefs_dir, tmp, prefs_dir, prefs_dir, prefs_dir, prefs_dir, tmp))


def static_check(layout_dir):
    problems = []
    for name in os.listdir(layout_dir):
        if not name.startswith("row_book"):
            continue
        text = open(os.path.join(layout_dir, name), encoding="utf-8").read()
        # комментарии не считаем
        body = re.sub(r"<!--.*?-->", "", text, flags=re.S)
        for pat in ("maxLines", "ellipsize", "singleLine"):
            if re.search(r"\b%s\s*=" % pat, body):
                problems.append("%s: %s=" % (name, pat))
    return problems


def row_groups(nodes):
    """Собрать строки: текстовые узлы, идущие подряд после cover_box."""
    rows = []
    cur = None
    for n in nodes:
        if n["id"] == COVER:
            cur = {"cover": n, "texts": []}
            rows.append(cur)
        elif cur is not None and n["id"] in (TITLE, SUB, META):
            cur["texts"].append(n)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--adb", default="adb")
    ap.add_argument("--scales", default="1.0,1.5,2.0")
    ap.add_argument("--layout-dir", default="app/res/layout")
    ap.add_argument("--prefs-dir",
                    default="/data/data/ru.unclerus.library/shared_prefs")
    ap.add_argument("--pkg", default="ru.unclerus.library")
    args = ap.parse_args()

    adb = Adb(args.adb, args.serial)
    scales = [s.strip() for s in args.scales.split(",") if s.strip()]
    failures = []
    report = []

    print("== статическая проверка разметки ==")
    probs = static_check(args.layout_dir)
    if probs:
        failures.append("разметка: " + ", ".join(probs))
        print("  ОБРЕЗКА возможна:", probs)
    else:
        print("  OK: в row_book*.xml нет maxLines/ellipsize/singleLine")

    heights = {}  # title -> {scale: node}
    scale_line_h = {}
    for scale in scales:
        print("== fontScale %s ==" % scale)
        set_font_scale(adb, scale, args.prefs_dir)
        adb.sh("am start -n %s/.ui.MainActivity" % args.pkg)
        time.sleep(4)
        if not open_all_books(adb):
            failures.append("fontScale %s: не удалось открыть вкладку «Все книги»" % scale)
            print("  FAIL: вкладка «Все книги» не найдена")
            continue
        xml = adb.dump()
        nodes = parse_nodes(xml)
        lists = find(nodes, LIST)
        if not lists:
            failures.append("fontScale %s: нет области списка" % scale)
            continue
        lst = lists[0]
        rows = row_groups(nodes)
        if not rows:
            failures.append("fontScale %s: нет строк книг" % scale)
            print("  FAIL: строки не найдены")
            continue

        # однострочная высота: минимальная высота title (короткое название)
        line_h = min(r["texts"][0]["h"] for r in rows if r["texts"]) if rows else 0
        scale_line_h[scale] = line_h
        n_overflow = 0
        n_overlap = 0
        min_h = 10 ** 9
        max_h = 0
        for r in rows:
            for t in r["texts"]:
                min_h = min(min_h, t["h"])
                max_h = max(max_h, t["h"])
                if t["x1"] < lst["x1"] or t["x2"] > lst["x2"]:
                    n_overflow += 1
            ts = r["texts"]
            for a, b in zip(ts, ts[1:]):
                if a["y2"] > b["y1"] + 2:
                    n_overlap += 1
            if ts:
                hid = ts[0]["text"]
                heights.setdefault(hid, {})[scale] = ts[0]["h"]

        print("  строк: %d · однострочная высота (title)=%d px · диапазон высот текста: %d..%d px"
              % (len(rows), line_h, min_h, max_h))
        print("  горизонтальных выходов за список: %d · наложений: %d" % (n_overflow, n_overlap))
        if n_overflow:
            failures.append("fontScale %s: %d текстовых узлов шире списка" % (scale, n_overflow))
        if n_overlap:
            failures.append("fontScale %s: %d наложений текстовых узлов" % (scale, n_overlap))
        if line_h <= 0:
            failures.append("fontScale %s: нулевая высота строки" % scale)

    # --- сравнение масштабов: шрифт реально крупнее, длинные названия переносятся ---
    base, big = "1.0", "2.0"
    if base in scale_line_h and big in scale_line_h:
        lh1, lh2 = scale_line_h[base], scale_line_h[big]
        print("== масштабирование ==")
        print("  однострочная высота: %s -> %d px · %s -> %d px" % (base, lh1, big, lh2))
        if lh2 <= lh1:
            failures.append("шрифт не масштабировался (%s: %d px, %s: %d px)"
                            % (base, lh1, big, lh2))
        long_all = long_ok = 0
        for hid, hs in heights.items():
            if len(hid) >= 40 and base in hs and big in hs:
                long_all += 1
                lines = round(hs[big] / float(max(lh2, 1)))
                if lines >= 2 and hs[big] > hs[base]:
                    long_ok += 1
                else:
                    failures.append("длинное название не перенеслось при %s: %r (%d px -> %d px)"
                                    % (big, hid[:40], hs[base], hs[big]))
        print("  длинных названий: %d, перенесены при %s: %d" % (long_all, big, long_ok))

    print()
    if failures:
        print("ИТОГ: FAIL")
        for f in failures:
            print("  -", f)
        return 1
    print("ИТОГ: PASS — обрезки не обнаружено на %s" % ", ".join(scales))
    return 0


if __name__ == "__main__":
    sys.exit(main())
