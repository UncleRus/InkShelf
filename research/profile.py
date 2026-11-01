#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Профилирование InkShelf на большой библиотеке (приёмочные №9, №13, PLAN §10 M9).

Измеряет прямо на устройстве/эмуляторе:

* **Холодный старт** — ``am start -W`` (TotalTime) и внутренние метки приложения
  («БД готова», «открытие БД»); норма §9.13 — ≤ 3 с.
* **Поиск** — по логу ``I/Search`` («найдено N, подсчёт M мс»); норма — ≤ 300 мс.
* **Инкрементальный рескан** — повторный скан неизменённой библиотеки: из лога
  ``I/Indexer`` берём время скана и «записей в БД» (норма №9 — только ``stat``,
  без перечитывания fb2) и проверяем, что Фаза B разобрала 0 книг.

Запуск:

    python3 research/profile.py --serial 6C766334 \
        --adb tools/android-sdk/platform-tools/adb --cold-runs 3
"""

import argparse
import re
import subprocess
import sys
import time

PKG = "ru.unclerus.library"


def shquote(s):
    return "'" + s.replace("'", "'\\''") + "'"


class Adb:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial

    def sh(self, cmd, timeout=120):
        r = subprocess.run([self.adb, "-s", self.serial, "shell", cmd],
                           capture_output=True, text=True, timeout=timeout)
        return r.stdout

    def start_w(self):
        r = subprocess.run([self.adb, "-s", self.serial, "shell", "am", "start", "-W",
                            "-n", "%s/.ui.MainActivity" % PKG],
                           capture_output=True, text=True, timeout=120)
        return r.stdout

    def logcat(self):
        r = subprocess.run([self.adb, "-s", self.serial, "logcat", "-d"],
                           capture_output=True, text=True, timeout=120)
        return r.stdout

    def logcat_clear(self):
        self.sh("logcat -c")


ATTR = re.compile(r'([\w:-]+)="([^"]*)"')
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def find_id(xml, rid):
    for m in re.finditer(r"<node\b[^>]*>", xml):
        a = dict(ATTR.findall(m.group(0)))
        if a.get("resource-id", "").split("/")[-1] == rid:
            b = BOUNDS.search(a.get("bounds", ""))
            if b:
                return [int(v) for v in b.groups()]
    return None


def dump(adb):
    adb.sh("uiautomator dump /sdcard/_prof.xml")
    r = subprocess.run([adb.adb, "-s", adb.serial, "exec-out", "cat", "/sdcard/_prof.xml"],
                       capture_output=True, text=True, timeout=60)
    return r.stdout


def cold_start(adb, runs):
    print("== холодный старт (№13: ≤ 3000 мс) ==")
    totals = []
    dbs = []
    for i in range(runs):
        adb.sh("am force-stop " + PKG)
        time.sleep(1.5)
        adb.logcat_clear()
        out = adb.start_w()
        m = re.search(r"TotalTime:\s*(\d+)", out)
        if not m:
            # Первый am start после force-stop иногда не отдаёт TotalTime
            # (активность уже поднимается/задача возвращается на передний план).
            time.sleep(1.0)
            out = adb.start_w()
            m = re.search(r"TotalTime:\s*(\d+)", out)
        total = int(m.group(1)) if m else -1
        time.sleep(1.5)
        logs = adb.logcat()
        md = re.search(r"холодный старт: БД готова за (\d+) мс", logs)
        dbms = int(md.group(1)) if md else -1
        mo = re.search(r"открытие БД: (\d+) мс", logs)
        print("  #%d: am TotalTime=%s мс · БД готова=%s мс · открытие БД=%s мс"
              % (i + 1, total, dbms, mo.group(1) if mo else "?"))
        totals.append(total)
        dbs.append(dbms)
    ok = all(t >= 0 and t <= 3000 for t in totals)
    print("  -> максимум TotalTime=%d мс: %s" % (max(totals), "OK" if ok else "FAIL"))
    return ok


def search(adb, queries):
    print("== поиск (№13: ≤ 300 мс) ==")
    adb.sh("am start -n %s/.ui.MainActivity" % PKG)
    time.sleep(3)
    ok = True
    for q in queries:
        xml = dump(adb)
        box = find_id(xml, "search")
        if not box:
            print("  ! поле поиска не найдено")
            ok = False
            continue
        cx = (box[0] + box[2]) // 2
        cy = (box[1] + box[3]) // 2
        adb.logcat_clear()
        adb.sh("input tap %d %d" % (cx, cy))
        time.sleep(0.5)
        adb.sh("input text %s" % shquote(q))
        time.sleep(2.5)
        logs = adb.logcat()
        ms = re.findall(r"подсчёт (\d+) мс", logs)
        found = re.findall(r"найдено (\d+), подсчёт", logs)
        if ms:
            v = int(ms[-1])
            print("  «%s»: найдено %s, подсчёт %d мс : %s"
                  % (q, found[-1] if found else "?", v, "OK" if v <= 300 else "FAIL"))
            ok = ok and v <= 300
        else:
            print("  «%s»: метка времени не найдена" % q)
            ok = False
        # очистить
        xml = dump(adb)
        cb = find_id(xml, "btn_clear")
        if cb:
            adb.sh("input tap %d %d" % ((cb[0] + cb[2]) // 2, (cb[1] + cb[3]) // 2))
            time.sleep(1)
    return ok


def rescan(adb, labels):
    print("== инкрементальный рескан (№9: только stat) ==")
    adb.sh("am start -n %s/.ui.MainActivity" % PKG)
    time.sleep(3)
    xml = dump(adb)
    btn = find_id(xml, "btn_settings")
    if not btn:
        print("  ! кнопка настроек не найдена")
        return False
    adb.sh("input tap %d %d" % ((btn[0] + btn[2]) // 2, (btn[1] + btn[3]) // 2))
    time.sleep(3)
    xml = dump(adb)
    coord = None
    for m in re.finditer(r"<node\b[^>]*>", xml):
        a = dict(ATTR.findall(m.group(0)))
        if a.get("text", "").strip() in labels:
            b = BOUNDS.search(a.get("bounds", ""))
            if b:
                coord = [int(v) for v in b.groups()]
                break
    if not coord:
        print("  ! пункт «Рескан» не найден")
        return False
    adb.logcat_clear()
    adb.sh("input tap %d %d" % ((coord[0] + coord[2]) // 2, (coord[1] + coord[3]) // 2))
    # ждём завершения
    for _ in range(120):
        time.sleep(10)
        logs = adb.logcat()
        m = re.search(r"скан завершён за (\d+) мс · корней (\d+) · найдено (\d+) · добавлено (\d+) "
                      r"· записей в БД (\d+) · фаза B: разобрано (\d+)", logs)
        if m:
            ms, roots, found, added, changes, parsed = m.groups()
            print("  скан: %s мс · корней %s · найдено %s · добавлено %s · записей в БД %s · фаза B разобрано %s"
                  % (ms, roots, found, added, changes, parsed))
            good = int(added) == 0 and int(changes) == 0 and int(parsed) == 0
            print("  -> «только stat» (0 добавлено, 0 записей, 0 разобрано): %s"
                  % ("OK" if good else "FAIL"))
            return good
    print("  ! скан не завершился за отведённое время")
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--adb", default="adb")
    ap.add_argument("--cold-runs", type=int, default=3)
    ap.add_argument("--search-queries", default="Zelazny,Ze,Onyx")
    ap.add_argument("--skip", default="",
                    help="что пропустить: cold,search,rescan через запятую")
    args = ap.parse_args()
    skip = set(x.strip() for x in args.skip.split(",") if x.strip())

    adb = Adb(args.adb, args.serial)
    results = []
    if "cold" not in skip:
        results.append(("холодный старт", cold_start(adb, args.cold_runs)))
    if "search" not in skip:
        results.append(("поиск", search(adb, [q for q in args.search_queries.split(",") if q])))
    if "rescan" not in skip:
        results.append(("рескан", rescan(adb, ("Пересканировать библиотеку", "Rescan library"))))

    print()
    print("ИТОГ:")
    overall = True
    for name, ok in results:
        print("  %-16s %s" % (name, "PASS" if ok else "FAIL"))
        overall = overall and ok
    return 0 if overall else 1


if __name__ == "__main__":
    sys.exit(main())
