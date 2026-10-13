#!/usr/bin/env bash
# Съёмка трейлера по данным игры, в два прохода одной папки (INCOMING_TRAILER_DIR):
#   tools/trailer/film.sh record [shaders]  — сценарий трейлера пишет каждый план повтором Flashback с журналом снарядов,
#                                             людей и отметок (replays/<план>.zip, .events.jsonl); кадров нет
#   tools/trailer/film.sh render [shaders]  — планы из повторов (ReplayFilm, таблица Coverage): камеры по журналу,
#                                             вывод Flashback кадр на тик, проверка каждого кадра и пересъёмка;
#                                             render/report.jsonl, render/best.txt — папки планов для edit.py --rec
# Нужны Flashback 0.39 (Fabric), Flashback NeoForge Fixed, Sinytra Connector и Forgified Fabric API в каталоге модов
# (INCOMING_MODS_DIR или инстанс) и «чистый» jar клиента для Connector (INCOMING_CONNECTOR_CLEAN, иначе — из
# библиотек Prism). Остальное — как у record.sh (INCOMING_SIZE, INCOMING_WAR_PART, INCOMING_TRAILER_SHOTS,
# INCOMING_FILM_SHOTS, INCOMING_FILM_OUT (папка вывода вместо render: вариант плана, например с INCOMING_SHADER_OPTS),
# INCOMING_RENDER_DISTANCE: у вывода — не меньше, чем у записи, иначе дальние чанки повтора
# клиент выбросит), INCOMING_FILM_LIVE=0 — запись без кадров планов вживую (интерфейс, дальше прорисовки: их снимает
# машина с видеокартой). Моды войны (Almighty, Point Blank, Immersive Aircraft) и Distant Horizons берутся из каталога модов
# всегда; без DH — INCOMING_DH= (пусто: в облаке DH вместо рельефа рисует столбы). Окно вывода — вдвое меньше кадра
# (INCOMING_WINDOW): вывод Flashback пересоздаёт кадр игры в размер вывода, и при том же размере Iris оставляет LOD DH
# глубину мира вместо своей — LOD ложится поверх ближнего мира (.claude/rules/trailer.md). Клиент вывода, у которого
# прежние повторы остались в памяти, уходит на границе повторов, и film.sh запускает новый (строка SCENARIO film restart).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mode="${1:-}"
shift || true
export INCOMING_FLASHBACK=1 INCOMING_FILM=1 INCOMING_DH="${INCOMING_DH-1}"
if [ -z "${INCOMING_CONNECTOR_CLEAN:-}" ]; then
  PRISM="$(python3 "$ROOT/tools/paths.py" PRISM 2>/dev/null || true)"
  CLEAN="$(ls "$PRISM"/libraries/net/minecraft/client/1.21.1-*/client-1.21.1-*-srg.jar 2>/dev/null | head -n1 || true)"
  if [ -n "$CLEAN" ]; then export INCOMING_CONNECTOR_CLEAN="$CLEAN"; fi
fi
case "$mode" in
  record) export INCOMING_FILM_REPLAY=1 ;;
  render)
    export INCOMING_SCENARIO=film
    size="${INCOMING_SIZE:-1920x1080}"
    export INCOMING_WINDOW="${INCOMING_WINDOW:-$(( ${size%x*} / 2 ))x$(( ${size#*x} / 2 ))}"
    ;;
  *) echo "film.sh record|render [shaders]" >&2; exit 2 ;;
esac
if [ "$mode" = render ]; then
  # клиент, у которого память не вернулась после закрытия повтора, уходит на границе повторов и оставляет файл restart
  # в папке вывода (ReplayFilm): остальные планы снимает новый клиент, по best.txt — с того же места
  dir="${INCOMING_TRAILER_DIR:-trailer}"
  case "$dir" in /*) ;; *) dir="$ROOT/mod/run/scenario/$dir" ;; esac
  marker="$dir/${INCOMING_FILM_OUT:-render}/restart"
  while :; do
    rm -f "$marker"
    "$ROOT/tools/trailer/record.sh" "$@"
    [ -f "$marker" ] || exit 0
    echo "film.sh: новый клиент вывода ($(cat "$marker"))" >&2
    # снятое прежним клиентом (best.txt) новый не переснимает, даже названное в INCOMING_FILM_SHOTS
    export INCOMING_FILM_RESUME=1
  done
fi
exec "$ROOT/tools/trailer/record.sh" "$@"
