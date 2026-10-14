#!/usr/bin/env bash
# Монтаж трейлера из всего снятого: tools/trailer/cut.sh [аргументы edit.py: --lang, --music, --draft, --take …]
# Запуск — из корня рабочего дерева съёмки. Дубль плана — прошедший проверку кадров (PASS в best.txt) из вывода с
# наибольшим приоритетом; у плана без PASS — не прошедший, тоже по приоритету (лучше, чем заглушка). Живые планы
# (кадры в timeline.jsonl папки записи) — из папок записи по порядку, последняя сильнее; --take в аргументах — сильнее
# всего. Строки best.txt с «-» вместо папки (план не снят) пропускаются.
#
#   CUT_RECS     папки записи под mod/run/scenario, слабая → сильная (нет timeline.jsonl — пропуск)
#   CUT_RENDERS  выводы «папка[:план,план]» под mod/run/scenario, слабый → сильный; «:планы» — только эти планы из
#                него (p03 — из своих выводов тумана). Нет best.txt — пропуск.
# Строка запуска — dist/incoming-trailer-edit.sh, лог — dist/incoming-trailer-edit.log (прежние — рядом с меткой времени).
set -euo pipefail
S=mod/run/scenario
CUT_RECS=${CUT_RECS:-"film-war2 film-war4 film-war5 film-war6"}
# камеры по сцене (render-planned, render-planned2 — p32 заново) — слабее всех: монтаж берёт их только там, где без них
# PASS нет; render-ends (p48, p50 из film-war2 с отметкой фронта) — только запасные: edit.py берёт их, если в сильном
# дубле волна слишком близко к началу (prefer_fitting); render-human2 (p01a, p42 с INCOMING_FILM_SHOWN) — не в монтаже:
# обе вышли хуже render-human (14.10); film-war6 — батарея заново (пуски от поджига); film-war7 — вспышки p40b, p40c на
# свежих пусковых; film-war8 — p40c, три пусковые в кадре; film-war9 — p48 своим подрывом (стена накрывает городок);
# film-war4/render-p50people — p50 камерой ниже (людей там нет: погибли от вспышки); film-war10 — p50 своим подрывом, люди
# на крыше живы
CUT_RENDERS=${CUT_RENDERS:-"film-war4/render-planned film-war2/render-planned film-war2/render-planned2 \
film-war2/render-ends film-war2/render \
film-war2/render-fog-c2-alt110-d3.05-dist200:p03 film-war2/render-fog-c2-human:p03 film-war2/render-human \
film-war4/render film-war2/render-human3 film-war4/render-metro film-war4/render-metro2 film-war6/render film-war7/render \
film-war8/render film-war9/render film-war4/render-p50people film-war10/render"}

# layer <итог или пусто>: --rec папок дублей из всех выводов по приоритету
layer() {
  for r in $CUT_RENDERS; do
    local dir=${r%%:*} only=""
    [ "$dir" != "$r" ] && only=${r#*:}
    [ -f "$S/$dir/best.txt" ] || continue
    awk -v only=",$only," -v v="$1" '$2 != "-" && (only == ",," || index(only, "," $1 ",")) && (v == "" || $3 == v) \
      {printf "--rec %s ", $2}' "$S/$dir/best.txt"
  done
}

ARGS=""
for d in $CUT_RECS; do
  [ -f "$S/$d/timeline.jsonl" ] && ARGS="$ARGS --rec $S/$d"
done
ARGS="$ARGS $(layer '') $(layer PASS)"
mkdir -p dist
stamp=$(date -u +%Y%m%dT%H%M%S)
for f in dist/incoming-trailer-edit.sh dist/incoming-trailer-edit.log; do
  [ -f "$f" ] && cp "$f" "${f%.*}-$stamp.${f##*.}"
done
printf 'uv run tools/trailer/edit.py%s' "$ARGS" > dist/incoming-trailer-edit.sh
printf ' %q' "$@" >> dist/incoming-trailer-edit.sh
echo >> dist/incoming-trailer-edit.sh
# shellcheck disable=SC2086 # ARGS — список слов без пробелов в путях
uv run tools/trailer/edit.py $ARGS "$@" 2>&1 | tee dist/incoming-trailer-edit.log
