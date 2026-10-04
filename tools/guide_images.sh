#!/usr/bin/env bash
# Картинки руководства игрока (docs/guide/img) из кадров сценария guide: tools/client_scenario.sh guide,
# потом этот скрипт (нужен только ffmpeg). Рецепты режутся по месту окна верстака (1280×720, масштаб GUI 2, как
# в options.txt сценария), остальное — GIF из серий guide-gif-<имя>-NNNN. Нет серии — её GIF не трогается
# (так переснимают один раздел: AIRSTRIKE_GUIDE=<раздел>).
# С какого кадра начать и сколько взять, видно только глазами: числа — ниже, их правят после съёмки.
#   tools/guide_images.sh [папка кадров]   (по умолчанию mod/run/scenario/screenshots)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$ROOT/mod/run/scenario/screenshots}"
OUT="$ROOT/docs/guide/img"
mkdir -p "$OUT"

ff() { ffmpeg -loglevel error -y "$@"; }

# верстак: окно 176×166 GUI в середине экрана, в кадр — заголовок, сетка и результат
for f in "$SRC"/guide-recipe-*.png; do
  [ -e "$f" ] || continue
  name="$(basename "$f" .png)"
  ff -i "$f" -vf "crop=352:144:464:194" "$OUT/${name#guide-}.png"
done

# GIF: имя, первый кадр, число кадров (0 — до конца), фильтр размера, цветов в палитре.
# Экраны GUI — вырезом без масштаба (экран пульта: 320×236 GUI с полями 8 пикселей), мир — 640 по ширине.
gif() {
  local name=$1 start=$2 count=$3 size=$4 colors=$5
  local first; first=$(printf "%s/guide-gif-%s-%04d.png" "$SRC" "$name" "$start")
  [ -e "$first" ] || { echo "нет кадров $name — пропуск"; return; }
  local frames=(); [ "$count" -gt 0 ] && frames=(-frames:v "$count")
  ff -framerate 12 -start_number "$start" -i "$SRC/guide-gif-$name-%04d.png" "${frames[@]}" \
    -vf "$size,split[a][b];[a]palettegen=max_colors=$colors:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" \
    "$OUT/$name.gif"
}
W640="scale=640:-1:flags=lanczos"
gif scope    0 0 "$W640" 128
gif remote   0 0 "crop=656:488:312:116" 96
gif map      0 0 "scale=960:-1:flags=lanczos" 256
gif hud      0 0 "$W640" 128
gif camera   0 0 "$W640" 128
gif loiter   0 0 "$W640" 128
gif sam      0 0 "$W640" 128
gif launcher 0 0 "$W640" 128
ls -la "$OUT"
