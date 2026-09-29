#!/usr/bin/env bash
# Съёмка трейлера: клиент без окна проигрывает сценарий (mod/src/devtest/.../scenario/trailer/Trailer) и пишет
# кадры 60 fps по времени игры и журнал звуков в mod/run/scenario/trailer/; монтаж — tools/trailer/edit.py.
#   tools/trailer/record.sh [shaders]
#   shaders — Sodium, Iris и шейдерпак хоста из инстанса (только на ПК с инстансом и KWin)
# Размер кадра — AIRSTRIKE_SIZE (по умолчанию 1920x1080). Без инстанса (облако) Create/Sable/Aeronautics
# скачиваются tools/fetch_runtime_mods.py, клиент идёт под xvfb-run с программной отрисовкой.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN="$ROOT/mod/run/scenario"
mkdir -p "$RUN/logs"
# папка записи: AIRSTRIKE_TRAILER_DIR (по умолчанию trailer); переснять отдельные планы, не трогая основной дубль:
#   AIRSTRIKE_TRAILER_SHOTS=bomb_bay,targets AIRSTRIKE_TRAILER_DIR=trailer-retake tools/trailer/record.sh shaders
#   uv run tools/trailer/edit.py --rec mod/run/scenario/trailer --rec mod/run/scenario/trailer-retake
# язык игры — AIRSTRIKE_LANG (по умолчанию en_us: ролик только на английском)
# размытие движения — AIRSTRIKE_SUBFRAMES подкадров на кадр (начисто — 8, проба — 1)
export AIRSTRIKE_TRAILER_DIR="${AIRSTRIKE_TRAILER_DIR:-trailer}"
rm -rf "${RUN:?}/$AIRSTRIKE_TRAILER_DIR"
# карта съёмки (Greenfield, уже обновлённая до 1.21.1): AIRSTRIKE_WORLD_SRC — чистая копия, её не трогаем; каждый дубль
# идёт на свежей копии в saves/ (удары и ядерка меняют мир)
if [ -n "${AIRSTRIKE_WORLD_SRC:-}" ]; then
  export AIRSTRIKE_WORLD="${AIRSTRIKE_WORLD:-trailer-world}"
  mkdir -p "$RUN/saves"
  rm -rf "${RUN:?}/saves/$AIRSTRIKE_WORLD"
  cp -r "$AIRSTRIKE_WORLD_SRC" "$RUN/saves/$AIRSTRIKE_WORLD"
fi
# звук с устройства не нужен (игра идёт замедленно, дорожку собирает монтаж), но звуковой движок должен работать
cat > "$RUN/alsoft.conf" <<CONF
[general]
drivers = wave
[wave]
file = /dev/null
CONF
cat > "$RUN/options.txt" <<OPT
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:${AIRSTRIKE_RENDER_DISTANCE:-12}
simulationDistance:16
entityDistanceScaling:1.0
guiScale:3
fov:0.0
bobView:false
soundCategory_music:0.0
tutorialStep:none
joinedFirstServer:true
lang:${AIRSTRIKE_LANG:-en_us}
OPT
export AIRSTRIKE_SCENARIO=trailer
export AIRSTRIKE_SIZE="${AIRSTRIKE_SIZE:-1920x1080}"
W="${AIRSTRIKE_SIZE%x*}"; H="${AIRSTRIKE_SIZE#*x}"
# кадры PNG: ~1.2 байта на пиксель, ~12 000 кадров (720p — 11 ГБ, 1080p — ~30 ГБ)
NEED_GB=$(( W * H * 12 / 10 * 12000 / 1000000000 + 1 ))
FREE_GB=$(( $(df -Pk "$RUN" | awk 'NR==2 {print $4}') / 1000000 ))
if [ "$FREE_GB" -lt "$NEED_GB" ]; then
  echo "record.sh: для кадров ${W}x${H} нужно ~${NEED_GB} ГБ, свободно ${FREE_GB} ГБ в $RUN" >&2
  exit 1
fi
MC="$(python3 "$ROOT/tools/paths.py" MC)"
GRADLE_ARGS=(runClientScenario --console=plain)
if [ ! -d "$MC/mods" ]; then
  python3 "$ROOT/tools/fetch_runtime_mods.py"
  GRADLE_ARGS+=(-PmcModsDir=run/ci-mods)
fi
if [ "${1:-}" = shaders ]; then
  export AIRSTRIKE_SHADERS=1
  PACK="$(sed -n 's/^shaderPack=//p' "$MC/config/iris.properties")"
  mkdir -p "$RUN/shaderpacks" "$RUN/config"
  rm -rf "$RUN/shaderpacks/$PACK"
  cp -r "$MC/shaderpacks/$PACK" "$RUN/shaderpacks/"
  printf 'enableShaders=true\nshaderPack=%s\ndisableUpdateMessage=true\n' "$PACK" > "$RUN/config/iris.properties"
else
  unset AIRSTRIKE_SHADERS
fi
JDK="$(python3 "$ROOT/tools/paths.py" JAVA)"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$JDK" ]; then export JAVA_HOME="$JDK"; fi
cd "$ROOT/mod"
if command -v kwin_wayland >/dev/null; then
  # экран KWin с запасом: окно клиента с заголовком в размер экрана не влезает и ужимается (было 1920×1052);
  # сценарий снимает с окна рамку и ставит его ровно ${W}x${H} (Trailer.exactFrame)
  exec "$ROOT/tools/nested_kwin.sh" wayland-airstrike-trailer "$((W + 64))" "$((H + 160))" "$ROOT/mod/gradlew ${GRADLE_ARGS[*]}"
fi
# облако: звуковой сервер клиенту закрыт, как в nested_kwin.sh (звук трейлера собирается из журнала)
LIBGL_ALWAYS_SOFTWARE=1 PIPEWIRE_REMOTE="$RUN/no-audio-server" PULSE_SERVER="unix:$RUN/no-audio-server" \
  exec xvfb-run -a -s "-screen 0 ${W}x${H}x24" ./gradlew "${GRADLE_ARGS[@]}"
