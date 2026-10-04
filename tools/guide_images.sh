#!/usr/bin/env bash
# Картинки руководства игрока (docs/guide/img) из кадров сценария guide: tools/client_scenario.sh guide,
# потом этот скрипт (нужен только ffmpeg). Рецепты и экран пульта режутся по месту окна GUI (1280×720, масштаб GUI 2,
# как в options.txt сценария), кадры игры — в JPEG, карта — GIF из кадров guide-gif-map-*.
# Какой кадр серии (hud-N, camera-N, loiter-N, sam-N) лучше, видно только глазами: номера — ниже, их правят после съёмки.
#   tools/guide_images.sh [папка кадров]   (по умолчанию mod/run/scenario/screenshots)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$ROOT/mod/run/scenario/screenshots}"
OUT="$ROOT/docs/guide/img"
mkdir -p "$OUT"

# лучшие кадры серий
SCOPE=scope
HUD=hud-14
CAMERA_ONBOARD=camera-38
CAMERA_MAP=camera-30
CAMERA_HIT=camera-42
LOITER=loiter-6
SAM=sam-38

ff() { ffmpeg -loglevel error -y "$@"; }

# верстак: окно 176×166 GUI в середине экрана, в кадр — заголовок, сетка и результат
for f in "$SRC"/guide-recipe-*.png; do
  name="$(basename "$f" .png)"
  ff -i "$f" -vf "crop=352:144:464:194" "$OUT/${name#guide-}.png"
done

# экран пульта: 320×236 GUI в середине, с полями 8 пикселей
ff -i "$SRC/guide-remote.png" -vf "crop=656:488:312:116" "$OUT/remote.png"

# кадры игры и карты
for pair in "$SCOPE:scope" "$HUD:hud" "$CAMERA_ONBOARD:camera-onboard" "$CAMERA_MAP:camera-map" "$CAMERA_HIT:camera-hit" \
            "$LOITER:camera-loiter" "$SAM:sam" "map:map" "map-route:map-route" "map-flight:map-flight"; do
  ff -i "$SRC/guide-${pair%%:*}.png" -q:v 4 "$OUT/${pair#*:}.jpg"
done

# карта: GIF (960 по ширине, своя палитра)
ff -framerate 12 -i "$SRC/guide-gif-map-%04d.png" \
  -vf "scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" \
  "$OUT/map.gif"
ls -la "$OUT"
