# Матрица запуска книг (`*.fb2.zip`) во всех читалках ONYX BOOX RAPHAEL2

Устройство: ONYX BOOX RAPHAEL2, Android 11 / API 30, серийник `6C766334`, root = Magisk
(`su -c id` → `uid=0(root) ... context=u:r:magisk:s0`), `ro.debuggable=0`.

Задача: определить надёжный способ, которым новое sideload-приложение-каталог FB2
может открыть файл `*.fb2.zip` (`ACTION_VIEW`) в каждой установленной читалке.

Методика: только чтение устройства — `adb shell`, `dumpsys`, `cmd package`, `su -c cat`
для чтения файлов. Ничего не устанавливалось, не удалялось, настройки не менялись.
Манифесты разобраны `apktool` (`-s`), код — `jadx` 1.5.6 (`--no-res`). Файлы разбора:

- `/tmp/opencode/onyx-research/dumpsys/*.txt` — снятые `dumpsys package`
- `/tmp/opencode/onyx-research/decomp/<pkg>-apktool/AndroidManifest.xml` — точные фильтры
- `/tmp/opencode/onyx-research/decomp/<pkg>-jadx/sources/...` — декомпилированный код
- `/tmp/opencode/onyx-research/db/` — копии БД прогресса и лончера

Инструменты: `adb` = `/home/rus/work/android/tools/android-sdk/platform-tools/adb -s 6C766334`,
`apktool` = `java -jar /home/rus/work/android/tools/apktool/apktool_3.0.3.jar`,
`jadx` = `/home/rus/bin/jadx/bin/jadx`, `JAVA_HOME=/home/rus/work/android/tools/jdk/jdk-21`.

> **Важно (границы доказательств).** Мы разбирали фильтры манифеста и код обработки интента,
> но **не запускали реальное открытие книги** (это меняло бы состояние читалок). Поэтому
> «принимает ли фильтр» и «есть ли в коде разбор `content://`» доказано точно, а «как поведёт
> себя конкретный FileProvider-URI в рантайме» местами помечено как *не подтверждено*.

Затронутые пакеты и их targetSdk (снято `dumpsys package <pkg>`):

| Пакет | Роль | minSdk | targetSdk | MANAGE_EXTERNAL_STORAGE (appops) |
|---|---|---|---|---|
| `com.onyx.kreader` | штатная читалка Onyx | 23 | 29 | allow |
| `com.onyx.oreaderx` | OReaderX (AlReader) | 21 | 35 | allow |
| `com.fbreader` | FBReader | 21 | 35 | allow |
| `com.foobnix.pro.pdf.reader` | Librera FD (foobnix) | 24 | 36 | allow |
| `com.cxinventor.file.explorer` | Cx Проводник (открывает `application/zip`) | — | — | allow |
| `com.raphael.toolbox` | sideload-приложение (наш кандидат-инициатор) | 21 | 34 | **No operations** |
| `ru.plus.bookmate` | Bookmate | — | — | **не установлен** (см. ниже) |

Проверка наличия пакетов:

```
$ adb -s 6C766334 shell pm list packages | grep -iE 'kreader|oreader|fbreader|foobnix|cxinventor|bookmate|toolbox'
package:com.fbreader
package:com.foobnix.pro.pdf.reader
package:com.onyx.kreader
package:com.onyx.oreaderx
package:com.cxinventor.file.explorer
package:com.raphael.toolbox
```

`ru.plus.bookmate` в списке отсутствует — на этом устройстве Bookmate сейчас не установлен,
поэтому он не может быть целью запуска (в `device/packages.txt` он есть, но этот файл устарел).

---

## 1. Intent-фильтры по читалкам (mime / scheme / pathPattern)

### 1.1 Сводная матрица разрешения интентов

Снято на живом устройстве:

```
$ adb -s 6C766334 shell cmd package query-activities -a android.intent.action.VIEW <опции> \
    | grep 'packageName=' | grep -iE 'kreader|oreaderx|fbreader|foobnix|cxinventor' \
    | sed 's/.*packageName=//' | sort -u
```

Где `CP=content://com.raphael.toolbox.fileprovider/books/x.fb2.zip`, файл `file:///sdcard/Books/x.fb2.zip`:

| Data / Type | kreader | oreaderx | fbreader | foobnix | cxinventor |
|---|---|---|---|---|---|
| `file://` без type | ✅ | ✅ | ✅ | ✅ | — |
| `content://` без type | ✅ | ✅ | ❌ | ✅ | — |
| `content://` + `application/fb2+zip` | ✅ | ✅ | ✅ | ✅ | — |
| `content://` + `application/fb2.zip` | ✅ | ✅ | ✅ | ✅ | — |
| `content://` + `application/zip` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `content://` + `application/octet-stream` | ✅ | ✅ | ✅ | ✅ | — |
| `file://` + `application/fb2+zip` | ✅ | ✅ | ✅ | ✅ | — |
| `file://` + `application/zip` | ✅ | ✅ | ✅ | ✅ | ✅ |

Сырой вывод (фрагмент):

```
file://       no-type    : com.fbreader com.foobnix.pro.pdf.reader com.onyx.kreader com.onyx.oreaderx
content://    no-type    : com.foobnix.pro.pdf.reader com.onyx.kreader com.onyx.oreaderx
content://    fb2+zip    : com.fbreader com.foobnix.pro.pdf.reader com.onyx.kreader com.onyx.oreaderx
content://    fb2.zip    : com.fbreader com.foobnix.pro.pdf.reader com.onyx.kreader com.onyx.oreaderx
content://    zip        : com.cxinventor.file.explorer com.fbreader com.foobnix.pro.pdf.reader com.onyx.kreader com.onyx.oreaderx
content://    octet-strm : com.fbreader com.foobnix.pro.pdf.reader com.onyx.kreader com.onyx.oreaderx
```

**Ключевой вывод:** FBReader **не** матчит `content://` без MIME — ему обязательно нужно
`intent.setType(...)`. Все остальные читалки принимают `content://` без type. Cx Проводник
(`com.cxinventor.file.explorer`) появляется только при `-t application/zip` (регистрирует
`ArchiveActivity` и на `file://`, и на `content://`).

### 1.2 Фильтры манифеста (apktool)

**`com.onyx.kreader` — `com.onyx.kreader.ui.ReaderHomeActivity`** (`dumpsys`/`query-activities`):
схемы `file` + `content`, host `*` (WILD, дважды), огромный список StaticType, включая
`application/fb2.zip`, `application/fb2+zip`, `application/zip`, `application/octet-stream`,
`application/epub+zip`, `application/pdf`, `text/fb2+xml` и др. Плюс отдельный фильтр по
`pathPattern`:

```
$ grep -o 'pathPattern="[^"]*fb2[^"]*"' kreader-apktool/AndroidManifest.xml | sort -u
pathPattern=".*\\.fb2"
pathPattern=".*\\.fb2\\.zip"
```

**`com.onyx.oreaderx` — `com.neverland.viscomp.TMainActivity`:**
actions `VIEW`/`SEND`/`PICK`, categories `DEFAULT`/`BROWSABLE`/`OPENABLE`, схемы `file`+`content`,
host `*`, mime list (fb2+zip/fb2.zip/zip/octet-stream и др.) плюс pathPattern:

```
$ grep -o 'pathPattern="[^"]*fb2[^"]*"' oreaderx-apktool/AndroidManifest.xml | sort -u
pathPattern=".*..*..*..*..*..*..*..*.fb2"
pathPattern=".*.fb2"
...
pathPattern=".*.fb2.zip"
```

(у oreaderx `pathPattern` содержит и fb2, и `.*.fb2.zip`; отдельно есть `fb2.rar`, `epub.zip`.)

**`com.fbreader` — `com.fbreader.android.fbreader.FBReader`:** два разных фильтра.
Фильтр для scheme `file` с `host=""` и фильтр для `content`, а также отдельный SAF-фильтр
с фиксированным путём:

```
<intent-filter>
    <action android:name="android.intent.action.MAIN"/>
    <action android:name="android.intent.action.GET_CONTENT"/>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:host="*"/>
    <data android:scheme="file"/>
    <data android:scheme="content"/>
    <data android:mimeType="*/*"/>
    <data android:pathPattern="/external/file/.*"/>
</intent-filter>
```

и `pathPattern`-наборы для `.*\.fb2`, `.*\.fb2\.zip` и др. (см. `fbreader-apktool/AndroidManifest.xml`).
Как и в матрице: `content://` без MIME не матчится (нужен явный type).

**`com.foobnix.pro.pdf.reader` — `com.foobnix.OpenerActivity`:**

```
<intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="file"/>
    <data android:scheme="content"/>
    <data android:host="*"/>
    <data android:mimeType="application/x-fictionbook+xml"/>
    <data android:mimeType="application/fb2+zip"/>
    <data android:mimeType="application/fb2.zip"/>
    <data android:mimeType="application/fb2"/>
    <data android:mimeType="application/epub+zip"/>
    <data android:mimeType="application/octet-stream"/>
    ... (большой список) ...
</intent-filter>
```

---

## 2. `content://` vs `file://`: разбор кода обработки интента

Смотреть в декомпилированный код: `getData()`, `getScheme()`, `openInputStream`.

### 2.1 OReaderX (`com.onyx.oreaderx`) — **поддерживает `content://`, копирует поток**

`com/neverland/viscomp/TMainActivity.java`, `prepareIntent`:

```java
Uri data = intent.getData();
...
} else if ("content".equals(data.getScheme())) {
    // → mainApp.appInstance.saveContent2File2(...)
```

`com/neverland/mainApp.java`, `saveContent2FileReal.run()`:

```java
ContentResolver contentResolver = tMainActivity.getContentResolver();
if (contentResolver != null) {
    InputStream inputStreamOpenInputStream = contentResolver.openInputStream(this.contentUri);
    if (inputStreamOpenInputStream != null) {
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        ...
        while (true) {
            int i3 = inputStreamOpenInputStream.read(bArr);
            ...
            fileOutputStream.write(bArr, 0, i3);
```

→ OReaderX читает `content://` через `ContentResolver.openInputStream` и копирует во временный
файл. Произвольный FileProvider-URI подходит.

### 2.2 FBReader (`com.fbreader`) — **поддерживает `content://`, читает поток**

`org/fbreader/filesystem/UriFile.createFileByUri` для scheme `content` вызывает
`d.h(context, uri)`; класс `d` (extends `h` extends `a` extends `UriFile`):

```
org/fbreader/filesystem/d.java:526:
    public InputStream openInputStream() {
        return this.applicationContext.getContentResolver().openInputStream(this.uri);
    }
```

→ FBReader открывает `content://` напрямую через `ContentResolver.openInputStream`. Нужен
явно заданный MIME (см. §1.1).

### 2.3 Librera FD / foobnix (`com.foobnix.pro.pdf.reader`) — **поддерживает `content://`, копирует поток**

`com/foobnix/OpenerActivity.java`, `onCreate`: сначала пробует восстановить путь
(`getPath()`, `_display_name`, `_size`, MediaStore `_data`), а если локального файла нет —
копирует поток:

```java
if (!file4.isFile()) {
    try {
        g22.f(getContentResolver().openInputStream(data), new FileOutputStream(file4));
```

→ foobnix принимает `content://` и копирует поток во временный файл `[Librera/Downloads]`.

### 2.4 kreader (`com.onyx.kreader`) — **`content://` принимает манифестом, но путь восстанавливает, а не копирует поток**

`com/onyx/android/sdk/utils/FileUtils.java`:

```java
public static String getRealFilePathFromUri(Context context, Uri uri) {
    if ("content".equals(uri.getScheme())) {
        path = Build.VERSION.SDK_INT >= 23 ? h(context, uri)
             : getRealFilePathFromUriByContentResolver(context, uri, BookMetadataBean.IMAGE_DATA_PATH_ALIAS);
    } else {
        path = uri.getPath();
    }
```

```java
private static String h(Context context, Uri uri) {
    if (StringUtils.containsIgnoreCase(uri.getAuthority(), "fileprovider")) {
        return f(context, uri);          // разбор path FileProvider-URI
    }
    String strD = d(context, uri);        // _data через ContentResolver + DocumentsContract
    return strD;
}
```

`f(context, uri)` разбирает `uri.getEncodedPath()` по сегментам и мапит первый сегмент:
`external` / `bluetooth` → внешнее хранилище+остаток; `root`; `download`; иначе `e(path)`
(поиск корня хранилища внутри пути). Стрим **не** копируется: `getFilePathFromUrisWithCopy`
лишь пытается получить читаемый путь (`getRealFilePathFromUri` / `SharePathDecoder`) и
`openInputStream` для `content://` в этом пути **не используется** (в декомпилированном SDK
вызовы `openInputStream` для `content://` встречаются только в `OnyxCmsCenter` и Glide/Fresco,
не в разборе интента читалки).

**Следствие:** kreader откроет наш `content://` только если из URI восстанавливается реальный
путь. Варианты:
- FileProvider с алиасом, чей первый сегмент пути = `external` (т.е. `<external-path name="external" path="."/>`),
  тогда URI `content://<auth>/external/Books/x.fb2.zip` → `/storage/emulated/0/Books/x.fb2.zip`;
- либо `file://` путь (kreader имеет `MANAGE_EXTERNAL_STORAGE: allow`, читает `/sdcard` и
  `/storage/6D4A-1E15`).

*Не подтверждено рантаймом:* восстанавливает ли kreader путь именно для **чужого**
FileProvider-URI (authority содержит `fileprovider` — да, проверка подстроки это допускает)
и открывает ли затем файл. Рекомендуемый безопасный путь — `file://` либо FileProvider с
алиасом `external`.

### 2.5 Cx Проводник (`com.cxinventor.file.explorer`)

Отдельно регистрирует `ArchiveActivity` на `content://` и `file://` только с
`-t application/zip`. Это архиватор, а не читалка FB2; для каталога FB2 — не цель, но
полезен как потенциальный «открыватель» `content://`-zip.

---

## 3. Публичный API/ACTION читалок Onyx и extras

Из `com/onyx/android/sdk/utils/` и `BroadcastHelper`/`DeviceReceiver` (kreader SDK):

- `com.onyx.android.sdk.utils.ViewDocumentUtils` — фабрика VIEW-интентов:
  - `viewActionIntent(File)` → `ACTION_VIEW` + `OnyxFileProviderUtil.setIntentData(...)`;
  - `intentFromMimeType(File)` → то же + `setDataAndType(uri, mime)`;
  - целевой компонент `new ComponentName("com.onyx.kreader", "com.onyx.kreader.ui.ReaderTabHostActivity")`.
- `com.onyx.android.sdk.common.provider.OnyxFileProviderUtil` — соглашение о своём FileProvider:
  authority = `context.getPackageName() + ".onyx.fileprovider"`;
  методы `getUriForFile`, `getFileProviderUri`, `setIntentData`, `setIntentDataAndType`,
  `grantPermissions`. `EXTERNAL_PACKAGE_SPECIFIC_PATH_PREFIXES = ["/storage/emulated/0/Android/data/"]`.
- ACTION-константы (фрагмент, `com.onyx.*` / `onyx.*`):
  - `com.onyx.open` (`DeviceReceiver.OPEN_DOCUMENT_ACTION`);
  - `onyx.action.cloud.push.OPEN_FILE_BY_DEFAULT_ACTIVITY`
    (`BroadcastHelper.OPEN_FILE_BY_DEFAULT_ACTIVITY_ACTION`);
  - `com.onyx.kreader.action.*` (внутренние действия читалки).
- Extras: `file_path` (`CommonConstant.MTP_EXTRA_TAG_FILE_PATH`), `old_file_path`
  (`..._OLD_FILE_PATH`), `json` / `json_file_path` (`BroadcastHelper.KEY_JSON*`),
  `android.intent.extra.STREAM`.

**Статус:** явного документированного публичного «открой книгу по пути/URI» ACTION, которым
можно надёжно инициировать открытие конкретного файла из стороннего приложения, в разобранном
коде **не найдено** (`MAIN_ACTIVITY`, `OPEN_FILE_BY_DEFAULT_ACTIVITY` относятся к внутренним
сценариям лончера/облачной синхронизации). Практический публичный путь — стандартный
`ACTION_VIEW` с data+type. Константы `com.onyx.kreader.action.*` — *не подтверждено* как
пригодные для внешнего запуска.

---

## 4. БД прогресса чтения: можно ли извлечь «позицию/дату последнего чтения»

Копии сняты только на чтение: `adb exec-out su -c "cat /data/data/<pkg>/.../X.db"` →
`/tmp/opencode/onyx-research/db/`.

| Читалка | БД / таблица | Поля позиции/даты | Извлекается? |
|---|---|---|---|
| kreader | `ContentDatabase.db` → `Metadata` | `progress`, `lastAccess`, `readingStatus`, `location`, `nativeAbsolutePath` | да |
| kreader | `OnyxStatisticsModel.db` → `OnyxStatisticsModel` | `action,readingProgress,position,chapter,path,lastPage,currPage,eventTime,durationTime` | да |
| OReaderX | `alrext_read01.db` → `recent` (+ `bookmarks`,`filebmk`) | `bookpos,filesize,datelast,title,author,filename,cardpath` | да |
| FBReader | `books.db` → `BookReadingProgress`, `BookHistory`, `BookState`, `Uri` | `numerator/denominator`, `timestamp,event` | да |
| foobnix / Librera | `/data/data/com.foobnix.pro.pdf.reader/databases` — **нет**; только `cache`/`code_cache` | — | нет (свежая установка, прогресса ещё нет) |

Примеры (kreader `Metadata`):

```
$ sqlite3 -header -column kreader-ContentDatabase.db \
    "SELECT title,progress,lastAccess,readingStatus,location,nativeAbsolutePath
     FROM Metadata WHERE progress IS NOT NULL ORDER BY lastAccess DESC LIMIT 8;"
        title          progress   lastAccess    readingStatus   ...   nativeAbsolutePath
Аттестат зрелости 1    101/605   1771060149116      1               /storage/emulated/0/Download/Telegram/..._fb2.zip
Вход не с той стороны  1/621    1758721182050      1               /storage/emulated/0/Download/Telegram/...zip
Темный Лекарь 1        466/468  1757371549404      1               /storage/emulated/0/Download/Telegram/...fb2.zip
Спектр                 22/1048  1756479701992      1               /storage/emulated/0/Books/Lukyanenko_Spektr...fb2.zip
```

OReaderX `recent`:

```
$ sqlite3 -header -column oreaderx-alrext_read01.db \
    "SELECT filename,cardpath,bookpos,filesize,datelast,title,author FROM recent ORDER BY datelast DESC LIMIT 6;"
... Telegram/..._fb2.zip^A/Agishev_...fb2   /storage/emulated/0   3102   271286   1793477607870   Полумесяц и крест   Агишев Руслан Ряфатевич
```

FBReader:

```
$ sqlite3 -header -column fbreader-books.db "SELECT * FROM BookReadingProgress LIMIT 5;"
book_id  numerator  denominator
      1          1            1
     58         40         1039
$ sqlite3 -header -column fbreader-books.db "SELECT * FROM BookHistory LIMIT 5;"
book_id    timestamp      event
      1  1755718917445      0
```

**Вывод:** позиция («n/m») и дата последнего чтения извлекаются у kreader, OReaderX и FBReader.
У foobnix прогресса пока нет (БД отсутствует). Для сопоставления записи с книгой используется
путь (`nativeAbsolutePath`/`filename`/`location`) — можно матчить по basename `*.fb2.zip`.

---

## 5. Механика `MANAGE_EXTERNAL_STORAGE` для `/sdcard` и `/storage/6D4A-1E15`

Смонтированное хранилище (Android 11, sdcardfs):

```
$ adb -s 6C766334 shell "mount | grep -E 'sdcard|6D4A|emulated'"
/data/media on /storage/emulated type sdcardfs (rw,...)
/dev/block/vold/public:179,33 on /mnt/media_rw/6D4A-1E15 type exfat (rw,...)
/mnt/media_rw/6D4A-1E15 on /storage/6D4A-1E15 type sdcardfs (rw,...)
```

```
$ adb -s 6C766334 shell sm list-volumes all
private mounted null
emulated;0 mounted null
public:179,33 mounted 6D4A-1E15
```

```
$ adb -s 6C766334 shell getprop persist.sys.isolated_storage   → true
$ adb -s 6C766334 shell getprop persist.fuse_sdcard            → true
$ adb -s 6C766334 shell getprop persist.sys.fuse               → false
```

`/sdcard` → `/storage/emulated/0` (sdcardfs), `6D4A-1E15` — публичный том (exfat): реальный
файл лежит на `/mnt/media_rw/6D4A-1E15`, наружу виден как `/storage/6D4A-1E15`.

Разрешение `MANAGE_EXTERNAL_STORAGE` (`appops get`):

```
$ for p in com.onyx.kreader com.onyx.oreaderx com.fbreader com.foobnix.pro.pdf.reader \
           com.cxinventor.file.explorer com.raphael.toolbox; do adb ... shell appops get $p MANAGE_EXTERNAL_STORAGE; done
MANAGE_EXTERNAL_STORAGE: allow        (kreader)
MANAGE_EXTERNAL_STORAGE: allow        (oreaderx)
MANAGE_EXTERNAL_STORAGE: allow        (fbreader)
MANAGE_EXTERNAL_STORAGE: allow        (foobnix)
MANAGE_EXTERNAL_STORAGE: allow; time=+49m46s510ms ago   (cxinventor)
No operations. / Default mode: default                   (toolbox)
```

Экраны выдачи разрешения:

```
$ adb ... shell cmd package resolve-activity -a android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION \
    -d "package:com.raphael.toolbox"
  name=com.android.settings.Settings$AppManageExternalStorageActivity   packageName=com.android.settings

$ adb ... shell cmd package resolve-activity -a android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION
  name=com.android.settings.Settings$ManageExternalStorageActivity      packageName=com.android.settings
```

Легаси-флаг `requestLegacyExternalStorage` в манифесте:

- `com.onyx.kreader` (targetSdk 29) — эффективно действует legacy-модель;
- `com.onyx.oreaderx`, `com.foobnix.pro.pdf.reader`, `com.cxinventor.file.explorer` — флаг
  присутствует в манифесте;
- `com.fbreader` (targetSdk 35) — флага нет, работает через SAF/`MANAGE_EXTERNAL_STORAGE`.

**Вывод:** все четыре читалки уже имеют `MANAGE_EXTERNAL_STORAGE = allow`, поэтому могут
читать `file://` из `/sdcard` и `/storage/6D4A-1E15`. Нашему каталогу `com.raphael.toolbox`
разрешение ещё не выдано (`No operations`); выдать его можно через
`Settings$AppManageExternalStorageActivity` (intent выше). Это не требуется, если каталог
отдаёт книги через `FileProvider` (`content://`) — тогда хватает `FLAG_GRANT_READ_URI_PERMISSION`.

---

## 6. Показывает ли штатный лончер sideload-приложения (или скрывает через `appsFilter`)

Список иконок лончера лежит в `com.onyx` `AppDatabase.db`, таблица `AppItemModel`; строки —
JSON в колонке `data`:

```
$ sqlite3 onyx-AppDatabase.db "SELECT COUNT(*) FROM AppItemModel;"
26
$ sqlite3 onyx-AppDatabase.db "SELECT COUNT(*) FROM AppItemModel WHERE data LIKE '%\"isSystemApp\":false%';"
14
```

Примеры sideload-приложений с `isSystemApp:false`, `isEnable:true`:

```
{"labelName":"FBReader","packageName":"com.fbreader",...,"isSystemApp":false,"isEnable":true,...}
{"labelName":"Raphael Toolbox","packageName":"com.raphael.toolbox",...,"isSystemApp":false,"isEnable":true,...}
{"labelName":"Magisk","packageName":"com.topjohnwu.magisk",...,"isSystemApp":false,"isEnable":true,...}
{"labelName":"Librera FD","packageName":"com.foobnix.pro.pdf.reader",...,"isSystemApp":false,"isEnable":true,...}
```

Полный набор меток (26): AdAway, App&Game 4PDA, BooxDrop, Cx Проводник, DevCheck, FBReader,
F-Droid, Firefox, Librera FD, Magisk, OReaderX, Play Маркет, Raphael Toolbox, RuStore, Telegram,
WEB-чтение, Быстрое меню, Галерея, Запись звука, Заставки, Календарь, Калькулятор++, Плеер,
Словари, Часы.

Механизм скрытия — `DeviceConfig.getAppsFilter()` (список **исключаемых** пакетов);
используется в `LoadAppsOfUserRequest` (`com/onyx/common/applications/request/`):

```java
return CollectionUtils.safelyContains(DeviceConfig.sharedInstance().getAppsFilter(), packageInfo.packageName)
    || CollectionUtils.safelyContains(DeviceConfig.sharedInstance().getTestApps(), packageInfo.packageName)
    || ActivityUtil.getLaunchIntentForPackage(getContext().getPackageManager(), packageInfo, list) == null;
```

В читаемых локальных настройках (`/data/data/com.onyx/shared_prefs/com.onyx_preferences.xml`)
ключа `appsFilter` нет — он приходит из EAC/серверного конфига `DeviceConfig`.

**Вывод:** sideload-приложения штатный лончер **показывает** (14 из 26 иконок — сторонние,
включая наш `com.raphael.toolbox` и Magisk). Скрываются только перечисленные в `appsFilter`
(серверный/preference-список), где наших приложений нет.

---

## Рекомендуемая стратегия запуска книги

Базовый интент-шаблон для всех читалок:

```java
Intent i = new Intent(Intent.ACTION_VIEW);
i.setDataAndType(uri, mime);                       // uri = FileProvider content://, mime = application/fb2+zip
i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // + FLAG_ACTIVITY_NEW_TASK из не-Activity контекста
i.setPackage(targetPkg);                            // адресно выбрать читалку
// либо адресный компонент:
i.setComponent(new ComponentName(targetPkg, targetActivity));
startActivity(i);
```

Использовать `mime = "application/fb2+zip"` (совпадает у всех четырёх) или `"application/zip"`.
`content://` отдавать через `FileProvider` каталога с `android:grantUriPermissions="true"`.

| Читалка | Метод запуска | Уверенность | Примечание |
|---|---|---|---|
| `com.onyx.oreaderx` | `ACTION_VIEW` + FileProvider `content://` + MIME; компонент `com.neverland.viscomp.TMainActivity` | **высокая** | код копирует поток `openInputStream` (mainApp) |
| `com.fbreader` | `ACTION_VIEW` + `content://` + **обязательный** MIME; компонент `com.fbreader.android.fbreader.FBReader` | **высокая** | без MIME `content://` не матчится; читает `ContentResolver.openInputStream` (UriFile/d:526) |
| `com.foobnix.pro.pdf.reader` | `ACTION_VIEW` + `content://` + MIME; компонент `com.foobnix.OpenerActivity` | **высокая** | OpenerActivity копирует поток в `[Librera/Downloads]` |
| `com.onyx.kreader` | `ACTION_VIEW` + **`file://`** (kreader имеет `MANAGE_EXTERNAL_STORAGE`); компонент `com.onyx.kreader.ui.ReaderHomeActivity` | **высокая** (для `file://`) | `content://` kreader принимает манифестом, но путь восстанавливает, а не копирует поток → средняя уверенность для чужого FileProvider |
| `com.onyx.kreader` (fallback-вариант) | `content://` с FileProvider-алиасом `name="external"` (`<external-path>`), чтобы URI был `.../external/<rel>` → kreader реконструирует `/storage/emulated/0/<rel>` | **средняя / не подтверждено** | проверять на устройстве; иначе использовать `file://` |
| `com.cxinventor.file.explorer` | `ACTION_VIEW` + `content://` + `-t application/zip` | средняя | это архиватор, не читалка FB2 |
| `ru.plus.bookmate` | — | — | **не установлен** на устройстве, целью быть не может |

**Читалки, которые НЕ открывают произвольный (чужой) `content://` и требуют fallback:**

1. **`com.onyx.kreader`** — при `content://` восстанавливает путь (`FileUtils.getRealFilePathFromUri` → `h` → `_data`/DocumentsContract/разбор FileProvider-пути), поток не копирует. Fallback: **`file://`** (разрешение уже выдано) либо FileProvider с алиасом `external`.
2. **`com.fbreader`** — формально `content://` открывает, но **не** матчит URI без MIME. Fallback: всегда передавать `setDataAndType(..., "application/fb2+zip")`; либо `file://` (targetSdk 35, есть `MANAGE_EXTERNAL_STORAGE`).

Универсально надёжный рецепт для всех четырёх сразу: **`file://` + `application/fb2+zip`**
(все имеют `MANAGE_EXTERNAL_STORAGE: allow`), а `content://` использовать как основной путь
для oreaderx/fbreader/foobnix и как опциональный (через алиас `external`) для kreader.

---

## Открытые вопросы / риски

1. **kreader + чужой `content://` не подтверждён рантаймом.** Проверка
   `containsIgnoreCase(authority, "fileprovider")` и разбор пути `f()` теоретически работают,
   но реального открытия мы не делали (read-only). Риск: неверный первый сегмент пути →
   `default`-ветка `e(path)` → пустая строка → «файл не открывается». Митигизация: `file://`
   или FileProvider-алиас `external`.
2. **FBReader без MIME по `content://` не матчит.** Если каталог вызовет
   `setData(uri)` без `setType`, FBReader не предложится и (при явном `setPackage`) упадёт.
   Всегда задавать type.
3. **Версии читалок отличаются от `device/packages.txt`.** В списке устаревшие данные:
   `ru.plus.bookmate` в `packages.txt` есть, но не установлен; `com.foobnix.pro.pdf.reader`
   установлен, но в файле отсутствует. Поведение фильтров привязано к текущим версиям,
   снятым с устройства.
4. **`content://` и временные файлы.** oreaderx и foobnix копируют книгу во внутренний temp;
   при больших fb2 это лишний расход и задержка старта; имя/размер temp зависит от
   `_display_name`/`_size` (у foobnix) — при кастомном FileProvider без MediaStore-курсоров
   могут споткнуться о `query`. Рекомендуется обычный `FileProvider` (не MediaStore).
5. **Прогресс foobnix недоступен.** У Librera FD сейчас нет БД прогресса (свежая установка);
   извлекать «последнюю позицию» из неё нельзя, пока она не прочитает хотя бы одну книгу.
6. **Сопоставление прогресса с книгой — по пути.** У всех читалок запись привязана к
   абсолютному пути/`filename`; если каталог отдаёт `content://`, файл у читалки оказывается
   во временном каталоге — сопоставление по basename `*.fb2.zip` может потребовать нормализации.
7. **`appsFilter` — серверный.** Локально ключа нет; при появлении серверного конфига с
   нашим пакетом иконка каталога может скрыться. Эмпирически сейчас наш пакет виден.
8. **`MANAGE_EXTERNAL_STORAGE` для каталога** не выдан (`No operations`). Для `file://`-пути
   потребуется запросить разрешение через `Settings$AppManageExternalStorageActivity`; для
   `content://` — не требуется (достаточно `FLAG_GRANT_READ_URI_PERMISSION`).
9. **Bookmate** — цель недостижима на текущем устройстве: `dumpsys package ru.plus.bookmate`
   возвращает `Unable to find package: ru.plus.bookmate` (пакет не установлен), поэтому его
   intent-фильтры на этом устройстве проанализировать нельзя. Утверждение «у Bookmate нет
   `file://`-фильтра VIEW» из более ранних заметок **не подтверждено** текущим снимком —
   требует проверки, если пакет появится.
