# InkShelf (`ru.unclerus.library`)

Каталог локальной коллекции книг для e-ink (ONYX BOOX RAPHAEL2): индексирует папки
с FB2 (`.fb2`/`.fb2.zip`), EPUB и MOBI, показывает классификаторы (авторы, жанры,
серии, дата, история, все книги) и открывает книгу во внешней читалке. Сам книги
не читает.

## Документация
- `SPEC.md` — исходное задание.
- `PLAN.md` — план по этапам M0–M9, технические решения, карта файлов.
- `UI.md` — инвентарь экранов, диалогов, типов строк и состояний.

## Сборка
Java 8, без Gradle и AndroidX; только Android SDK (`aapt2`, `d8`, `zipalign`,
`apksigner`) и `android.jar`. Путь к SDK берётся из `ANDROID_TOOLS` (по умолчанию
`../../tools`).

```bash
bash build.sh          # → Library.apk
adb install -r Library.apk
```

Ключ подписи `signing/library.keystore` и пароль к нему (`signing/keystore.pass`)
локальные и в репозиторий не входят. При первой сборке без ключа он генерируется
автоматически; пароль можно задать переменной `LIB_KS_PASS`.

## Макеты и строки
`design/gen.py` — единый источник строк RU/EN (`res/values/strings.xml`,
`res/values-en/strings.xml`) и иконок `drawable/ic_*.xml`.
`design/render.sh` — растеризация макетов (Chromium headless) в `design/screens/`.

```bash
python3 design/gen.py
bash design/render.sh
```

## Структура
- `app/src/ru/unclerus/library/` — Java-исходники (без внешних зависимостей).
- `app/res/` — ресурсы; `values/` (ru, default), `values-en/` (en).
- `research/` — профиль коллекции и матрица запуска читалок.
