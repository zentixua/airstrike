#!/usr/bin/env bash
# Картинки руководства игрока (docs/guide/img) из кадров сценария guide: tools/client_scenario.sh guide,
# потом этот скрипт (нужен только ffmpeg с libwebp). Рецепты режутся по месту окна верстака (1280×720, масштаб GUI 2, как
# в options.txt сценария), остальное — анимации из серий guide-gif-<имя>-NNNN. Нет серии — её анимация не трогается
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

# Анимации: имя, первый кадр, число кадров (0 — до конца), фильтр размера. Нет серии — пропуск.
# Экраны GUI почти не меняются от кадра к кадру — GIF (экран пульта — вырезом без масштаба: 320×236 GUI с полями
# 8 пикселей). Мир меняется весь в каждом кадре: в GIF это 50–100 КБ на кадр, анимированный WebP втрое-вчетверо легче
# при той же картинке (браузеры и GitHub его показывают).
frames() {
  local name=$1 start=$2 count=$3
  local first; first=$(printf "%s/guide-gif-%s-%04d.png" "$SRC" "$name" "$start")
  [ -e "$first" ] || { echo "нет кадров $name — пропуск" >&2; return 1; }
  ARGS=(-framerate 12 -start_number "$start" -i "$SRC/guide-gif-$name-%04d.png")
  [ "$count" -gt 0 ] && ARGS+=(-frames:v "$count")
  return 0
}
gif() {
  frames "$1" "$2" "$3" || return 0
  ff "${ARGS[@]}" -vf "$4,split[a][b];[a]palettegen=max_colors=$5:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle" "$OUT/$1.gif"
}
webp() {
  frames "$1" "$2" "$3" || return 0
  ff "${ARGS[@]}" -vf "scale=640:-1:flags=lanczos" -c:v libwebp_anim -quality 60 -compression_level 6 -loop 0 "$OUT/$1.webp"
}
gif  remote   0 112 "crop=656:488:312:116" 96   # последний кадр — уже мир
gif  map      0 0 "scale=960:-1:flags=lanczos" 256
webp scope    0 0
webp hud      0 0
webp camera   0 0
webp loiter   0 104
webp sam      0 0
webp launcher 0 64
ls -la "$OUT"
