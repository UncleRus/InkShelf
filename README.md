# InkShelf (`ru.unclerus.library`)

Каталог локальной коллекции книг для e-ink (ONYX BOOX RAPHAEL2): индексирует папки
с FB2 (`.fb2`/`.fb2.zip`), EPUB и MOBI, показывает классификаторы (авторы, жанры,
серии, дата, история, все книги) и открывает книгу во внешней читалке. Сам книги
не читает.

## Документация
- `SPEC.md` — исходное задание.
- `PLAN.md` — план по этапам M0–M9, технические решения, карта файлов.
- `UI.md` — инвентарь экранов, диалогов, типов строк и состояний.

## Сборка из исходников
Gradle и AndroidX не используются: сборка идёт напрямую инструментами Android SDK.
Исходный уровень языка — **Java 8** (компилятор JDK 21 вызывается с `-source 8
-target 8`).

### Что нужно
- **JDK 21** — для `javac`, `keytool`, `apksigner`.
- **Android SDK** — build-tools (`aapt2`, `d8`, `zipalign`, `apksigner`) и платформа
  `android-34` (`android.jar`).
- `zip` в `PATH`.

### Раскладка инструментов
`build.sh` ожидает такую структуру (задаётся переменной `ANDROID_TOOLS`):

```
$ANDROID_TOOLS/
├── android-sdk/
│   ├── build-tools/android-14/     # aapt2, d8, zipalign, apksigner
│   └── platforms/android-34/android.jar
└── jdk/jdk-21/                     # JAVA_HOME
```

По умолчанию `ANDROID_TOOLS` = `<корень проекта>/../../tools`.

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `ANDROID_TOOLS` | `<проект>/../../tools` | корень с Android SDK и JDK |
| `JAVA_HOME` | `$ANDROID_TOOLS/jdk/jdk-21` | JDK для javac/apksigner/keytool |
| `LIB_KS` | `signing/library.keystore` | путь к ключу подписи |
| `LIB_KS_ALIAS` | `library` | alias ключа |
| `LIB_KS_PASS` | — | пароль ключа (иначе берётся/создаётся `signing/keystore.pass`) |

### Сборка
```bash
git clone <URL> inkshelf && cd inkshelf
ANDROID_TOOLS=/path/to/tools bash build.sh   # → Library.apk
```

Скрипт выполняет цепочку `aapt2 compile → aapt2 link → javac → d8 → zipalign →
apksigner` и в конце печатает сертификат подписи. Промежуточные файлы — в `build/`.

### Подпись и обновления
Ключ `signing/library.keystore` и пароль к нему (`signing/keystore.pass`) в
репозиторий не входят. Если ключа нет, `build.sh` сгенерирует новый ключ и
случайный пароль. Свой пароль можно задать заранее:

```bash
LIB_KS_PASS='...' bash build.sh
# либо положить пароль в signing/keystore.pass
```

Сохраняйте каталог `signing/` между сборками. Смена ключа меняет подпись APK, и
такое обновление не установится поверх (`adb install -r`) — придётся удалить
приложение и поставить заново.

### Установка
```bash
adb install -r Library.apk
```

## Макеты и строки
`design/gen.py` — единый источник строк RU/EN (`res/values/strings.xml`,
`res/values-en/strings.xml`) и иконок `drawable/ic_*.xml`.
`design/render.sh` — растеризация макетов (Chromium headless) в `design/screens/<lang>/`
(`ru/`, `en/`). Валидны только эти подкаталоги; PNG, лежащие прямо в `design/screens/`,
устарели — скрипт их не обновляет.

```bash
python3 design/gen.py
bash design/render.sh
```

## Структура
- `app/src/ru/unclerus/library/` — Java-исходники (без внешних зависимостей).
- `app/res/` — ресурсы; `values/` (ru, default), `values-en/` (en).
- `research/` — профиль коллекции, матрица запуска читалок, скрипты M9 и отчёт `m9-report.md`.
