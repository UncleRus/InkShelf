#!/usr/bin/env bash
# Растеризация макетов design/out/<lang>/*.html -> design/screens/<lang>/*.png (1264x1680).
# Chromium headless, параллельно (по числу ядер).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/out"
SHOTS="$ROOT/screens"

render_one() {
    local html="$1" base lang dir
    lang="$(basename "$(dirname "$html")")"
    base="$(basename "$html" .html)"
    dir="$SHOTS/$lang"
    mkdir -p "$dir"
    [ -f "$dir/$base.png" ] && [ "$dir/$base.png" -nt "$html" ] && return 0
    timeout 120 chromium --headless=new --no-sandbox --disable-gpu \
        --hide-scrollbars --force-device-scale-factor=1 \
        --window-size=1264,1680 --default-background-color=00000000 \
        --screenshot="$dir/$base.png" "file://$html" >/dev/null 2>&1
    echo "  $lang/$base.png"
}
export -f render_one
export SHOTS

# удалить PNG, у которых больше нет HTML-источника (экран переименован/убран)
for png in "$SHOTS"/*/*.png; do
    [ -e "$png" ] || continue
    lang="$(basename "$(dirname "$png")")"
    base="$(basename "$png" .png)"
    [ -f "$OUT/$lang/$base.html" ] || { rm -f "$png"; echo "  удалён устаревший: $lang/$base.png"; }
done

ls "$OUT"/*/*.html | xargs -P "$(nproc)" -I{} bash -c 'render_one "$@"' _ {}

echo "готово: $SHOTS"
magick identify "$SHOTS"/*/*.png 2>/dev/null | head -1
