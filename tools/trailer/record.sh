#!/usr/bin/env bash
# Съёмка трейлера: клиент без окна проигрывает сценарий (mod/src/devtest/.../scenario/trailer/Trailer) и пишет
# кадры 60 fps по времени игры и журнал звуков в mod/run/scenario/trailer/; монтаж — tools/trailer/edit.py.
#   tools/trailer/record.sh [shaders]
#   shaders — Sodium, Iris и шейдерпак хоста из инстанса (только на ПК с инстансом и KWin)
# Размер кадра — INCOMING_SIZE (по умолчанию 1920x1080). Без инстанса (облако) Create/Sable/Aeronautics
# скачиваются tools/fetch_runtime_mods.py, клиент идёт под xvfb-run с программной отрисовкой.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN="$ROOT/mod/run/scenario"
mkdir -p "$RUN/logs"
# папка записи: INCOMING_TRAILER_DIR (по умолчанию trailer); переснять отдельные планы, не трогая основной дубль:
#   INCOMING_TRAILER_SHOTS=bomb_bay,targets INCOMING_TRAILER_DIR=trailer-retake tools/trailer/record.sh shaders
#   uv run tools/trailer/edit.py --rec mod/run/scenario/trailer --rec mod/run/scenario/trailer-retake
# облака ванили — INCOMING_CLOUDS (true, fast, false: слой на высоте 192 закрывает верх башен центра и подлёт ракет;
# у шейдерпака облака — его настройка, их высота для проверки кадров — INCOMING_FILM_CLOUDS; другие настройки пака —
# INCOMING_SHADER_OPTS, «ИМЯ=значение» через пробел)
# язык игры — INCOMING_LANG (по умолчанию ru_ru); планы с текстом игры в кадре для английского ролика — en_us:
#   INCOMING_LANG=en_us INCOMING_TRAILER_SHOTS=remote,scope,targets INCOMING_TRAILER_DIR=trailer-en tools/trailer/record.sh shaders
export INCOMING_TRAILER_DIR="${INCOMING_TRAILER_DIR:-trailer}"
# сценарий: trailer (запись) или film (вывод планов из повторов той же папки, tools/trailer/film.sh) — его папку не чистим
export INCOMING_SCENARIO="${INCOMING_SCENARIO:-trailer}"
# запись повторов (film.sh record) копит планы всех частей войны в одной папке — её не чистим
if [ "$INCOMING_SCENARIO" = trailer ] && [ -z "${INCOMING_FILM_REPLAY:-}" ]; then rm -rf "${RUN:?}/$INCOMING_TRAILER_DIR"; fi
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
renderDistance:${INCOMING_RENDER_DISTANCE:-12}
simulationDistance:16
entityDistanceScaling:1.0
guiScale:3
fov:0.0
bobView:false
soundCategory_music:0.0
tutorialStep:none
joinedFirstServer:true
lang:${INCOMING_LANG:-ru_ru}
renderClouds:"${INCOMING_CLOUDS:-true}"
OPT
# боты Almighty в войне (жители, расчёты, солдаты) — больше предела по умолчанию (bots.max = 8, «Already 8 bots»), и
# без пометки «[bot]» перед именем в чате: строки второго ключа (план 40) называют командира расчёта по имени
if [ -n "${INCOMING_FILM:-}" ]; then
  ALM="$RUN/config/almighty-common.toml"
  mkdir -p "$RUN/config"
  touch "$ALM"
  awk '
    function keys() { print "\tmax = 100"; print "\tmarker = \"\""; done = 1 }
    /^[[:space:]]*\[/ { h = $0; gsub(/[[:space:]]/, "", h); bots = (h == "[bots]"); print; if (bots) keys(); next }
    bots && /^[[:space:]]*(max|marker)[[:space:]]*=/ { next }
    { print }
    END { if (!done) { print ""; print "[bots]"; keys() } }
  ' "$ALM" > "$ALM.tmp"
  mv "$ALM.tmp" "$ALM"
fi
# LOD Distant Horizons у съёмки — только из базы мира и загруженных чанков: генератор DH строил бы их, где их нет в базе,
# рельефом генератора мира (лес и море поверх города карты) и писал в базу мира (.claude/rules/trailer.md)
if [ -n "${INCOMING_FILM:-}" ] && [ -n "${INCOMING_DH:-}" ]; then
  DHC="$RUN/config/DistantHorizons.toml"
  mkdir -p "$RUN/config"
  touch "$DHC"
  awk '
    function keys() { print "\tgeneratorPlan = \"DISABLED\""; print "\tchunkGeneratorMode = \"PRE_EXISTING_ONLY\""; done = 1 }
    /^[[:space:]]*\[/ { h = $0; gsub(/[[:space:]]/, "", h); gen = (h == "[common.worldGenerator]"); print; if (gen) keys(); next }
    gen && /^[[:space:]]*(generatorPlan|chunkGeneratorMode)[[:space:]]*=/ { next }
    { print }
    END { if (!done) { print ""; print "[common.worldGenerator]"; keys() } }
  ' "$DHC" > "$DHC.tmp"
  mv "$DHC.tmp" "$DHC"
fi
export INCOMING_SIZE="${INCOMING_SIZE:-1920x1080}"
W="${INCOMING_SIZE%x*}"; H="${INCOMING_SIZE#*x}"
# кадры PNG: ~1.2 байта на пиксель, ~12 000 кадров (720p — 11 ГБ, 1080p — ~30 ГБ)
NEED_GB=$(( W * H * 12 / 10 * 12000 / 1000000000 + 1 ))
FREE_GB=$(( $(df -Pk "$RUN" | awk 'NR==2 {print $4}') / 1000000 ))
if [ "$FREE_GB" -lt "$NEED_GB" ]; then
  echo "record.sh: для кадров ${W}x${H} нужно ~${NEED_GB} ГБ, свободно ${FREE_GB} ГБ в $RUN" >&2
  exit 1
fi
MC="$(python3 "$ROOT/tools/paths.py" MC)"
GRADLE_ARGS=(runClientScenario --console=plain)
# имя игрока съёмки — в строках чата (второй ключ в плане 40), а не «Dev» запуска из Gradle
[ -n "${INCOMING_FILM:-}" ] && GRADLE_ARGS+=(-PscenarioUser=Sokol)
# моды для запуска: INCOMING_MODS_DIR (каталог от mod/, например моды сборки для съёмки) или инстанс, без него — Modrinth
if [ -n "${INCOMING_MODS_DIR:-}" ]; then
  GRADLE_ARGS+=(-PmcModsDir="$INCOMING_MODS_DIR")
elif [ ! -d "$MC/mods" ]; then
  python3 "$ROOT/tools/fetch_runtime_mods.py"
  GRADLE_ARGS+=(-PmcModsDir=run/ci-mods)
fi
if [ "${1:-}" = shaders ]; then
  export INCOMING_SHADERS=1
  PACK="$(sed -n 's/^shaderPack=//p' "$MC/config/iris.properties")"
  mkdir -p "$RUN/shaderpacks" "$RUN/config"
  rm -rf "$RUN/shaderpacks/$PACK"
  cp -r "$MC/shaderpacks/$PACK" "$RUN/shaderpacks/"
  printf 'enableShaders=true\nshaderPack=%s\ndisableUpdateMessage=true\n' "$PACK" > "$RUN/config/iris.properties"
  # настройки шейдерпака у Iris — <пак>.txt рядом с ним: слой облаков Complementary (CLOUD_ALT1, толщина ±4,2, шаг
  # настройки 4) — над всем действием, его низ не ниже INCOMING_FILM_CLOUDS (по нему проверка кадра считает облака);
  # INCOMING_SHADER_OPTS — ещё настройки пака «ИМЯ=значение» через пробел (план со своими настройками выводится
  # отдельным запуском с INCOMING_FILM_SHOTS)
  OPTS="$RUN/shaderpacks/$PACK.txt"
  : > "$OPTS"
  if [ -n "${INCOMING_FILM_CLOUDS:-}" ]; then
    printf 'CLOUD_ALT1=%d\n' "$(( (INCOMING_FILM_CLOUDS + 8 + 3) / 4 * 4 ))" >> "$OPTS"
  fi
  for o in ${INCOMING_SHADER_OPTS:-}; do printf '%s\n' "$o" >> "$OPTS"; done
  [ -s "$OPTS" ] || rm -f "$OPTS"
else
  unset INCOMING_SHADERS
fi
JDK="$(python3 "$ROOT/tools/paths.py" JAVA)"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$JDK" ]; then export JAVA_HOME="$JDK"; fi
cd "$ROOT/mod"
if command -v kwin_wayland >/dev/null; then
  # экран KWin с запасом: окно клиента с заголовком в размер экрана не влезает и ужимается (до 1920×1052);
  # сценарий снимает с окна рамку и ставит его ровно ${W}x${H} (Trailer.exactFrame)
  exec "$ROOT/tools/nested_kwin.sh" wayland-incoming-trailer "$((W + 64))" "$((H + 160))" "$ROOT/mod/gradlew ${GRADLE_ARGS[*]}"
fi
# облако: звуковой сервер клиенту закрыт, как в nested_kwin.sh (звук трейлера собирается из журнала)
LIBGL_ALWAYS_SOFTWARE=1 PIPEWIRE_REMOTE="$RUN/no-audio-server" PULSE_SERVER="unix:$RUN/no-audio-server" \
  exec xvfb-run -a -s "-screen 0 ${W}x${H}x24" ./gradlew "${GRADLE_ARGS[@]}"
