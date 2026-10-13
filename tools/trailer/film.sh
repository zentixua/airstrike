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
# INCOMING_FILM_SHOTS, INCOMING_RENDER_DISTANCE: у вывода — не меньше, чем у записи, иначе дальние чанки повтора
# клиент выбросит), INCOMING_FILM_LIVE=0 — запись без кадров планов вживую (интерфейс, дальше прорисовки: их снимает
# машина с видеокартой). Моды войны (Almighty, Point Blank, Immersive Aircraft) берутся из каталога модов всегда, Distant
# Horizons — только у записи (без него — INCOMING_DH=, пусто: в облаке DH вместо рельефа рисует столбы): в выводе из повтора
# Flashback DH рисует LOD поверх ближнего мира (кроны и стенки колонн полосами вниз, и в комнатах), вживую — нет.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mode="${1:-}"
shift || true
export INCOMING_FLASHBACK=1 INCOMING_FILM=1
if [ -z "${INCOMING_CONNECTOR_CLEAN:-}" ]; then
  PRISM="$(python3 "$ROOT/tools/paths.py" PRISM 2>/dev/null || true)"
  CLEAN="$(ls "$PRISM"/libraries/net/minecraft/client/1.21.1-*/client-1.21.1-*-srg.jar 2>/dev/null | head -n1 || true)"
  if [ -n "$CLEAN" ]; then export INCOMING_CONNECTOR_CLEAN="$CLEAN"; fi
fi
case "$mode" in
  record) export INCOMING_FILM_REPLAY=1 INCOMING_DH="${INCOMING_DH-1}" ;;
  render) export INCOMING_SCENARIO=film INCOMING_DH= ;;
  *) echo "film.sh record|render [shaders]" >&2; exit 2 ;;
esac
exec "$ROOT/tools/trailer/record.sh" "$@"
