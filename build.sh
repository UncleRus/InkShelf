#!/usr/bin/env bash
# Сборка «Библиотеки» (ru.unclerus.library) без Gradle:
#   aapt2 compile -> aapt2 link -> javac -> d8 -> zipalign -> apksigner.
# AndroidX и внешние зависимости не используются: только android.jar.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

APP="$ROOT/app"
TOOLS="${ANDROID_TOOLS:-$ROOT/../../tools}"
BT="$TOOLS/android-sdk/build-tools/android-14"
AJ="$TOOLS/android-sdk/platforms/android-34/android.jar"
JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk/jdk-21}"
KS="${LIB_KS:-$ROOT/signing/library.keystore}"
OUT="$ROOT/build"
DIST="$ROOT/Library.apk"

MIN_SDK=23
TARGET_SDK=30
ALIAS="${LIB_KS_ALIAS:-library}"
PASS_FILE="$ROOT/signing/keystore.pass"

# Пароль ключа подписи берётся из окружения (LIB_KS_PASS) или из локального
# файла signing/keystore.pass (в репозиторий не входит — см. .gitignore).
# Если ключа ещё нет и пароль не задан — генерируем случайный и сохраняем локально.
PASS="${LIB_KS_PASS:-}"
if [ -z "$PASS" ] && [ -f "$PASS_FILE" ]; then
    PASS="$(cat "$PASS_FILE")"
fi
if [ ! -f "$KS" ] && [ -z "$PASS" ]; then
    PASS="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
    mkdir -p "$(dirname "$PASS_FILE")"
    ( umask 077; printf '%s' "$PASS" > "$PASS_FILE" )
fi
[ -n "$PASS" ] || {
    echo "нужен пароль ключа: задайте LIB_KS_PASS или создайте $PASS_FILE" >&2
    exit 1
}

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJ"; do
    [ -e "$f" ] || { echo "не найден инструмент: $f" >&2; exit 1; }
done

# Ключ подписи — локальный (signing/library.keystore, в репозиторий не входит).
mkdir -p "$(dirname "$KS")"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias "$ALIAS" \
        -storepass "$PASS" -keypass "$PASS" \
        -dname "CN=Library,O=UncleRus,C=RU" \
        -keyalg RSA -keysize 2048 -validity 10000
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

"$BT/aapt2" link \
    -o "$OUT/app-unsigned.apk" \
    -I "$AJ" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    "$OUT/res.zip"

find "$APP/src" "$OUT/gen" -name '*.java' -print | sort > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -parameters -Xlint:-options \
    -bootclasspath "$AJ" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --lib "$AJ" --min-api "$MIN_SDK" --output "$OUT" "$OUT/classes.jar"

cp "$OUT/app-unsigned.apk" "$OUT/app-with-dex.apk"
( cd "$OUT" && zip -q -j app-with-dex.apk classes.dex )

"$BT/zipalign" -f 4 "$OUT/app-with-dex.apk" "$OUT/app-aligned.apk"

"$BT/apksigner" sign \
    --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
    --out "$DIST" \
    "$OUT/app-aligned.apk"

"$BT/apksigner" verify --print-certs "$DIST" | head -n 4
ls -lh "$DIST"
