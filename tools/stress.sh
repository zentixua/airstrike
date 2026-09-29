#!/usr/bin/env bash
# Стенд нагрузки: выделенный сервер с режиссёром (StressDirector) и три игрока-клиента без окна (StressClient):
# Host пускает с пульта, Friend1 выходит посреди удара и возвращается, Friend2 — обычный друг.
# Режиссёр по расписанию гоняет залпы по 30, удары по игрокам, точкам и аппаратам в 300–1000 блоках,
# перенацеливание, гибель целей, уход за край загрузки, Незер, ядерку, save-all и «Отбой», в конце — сводка.
#
#   tools/stress.sh                 # весь сценарий
#   AIRSTRIKE_STRESS_RESTART=1 tools/stress.sh   # + остановка сервера посреди полёта и продолжение после запуска
#   AIRSTRIKE_JFR=1 tools/stress.sh   # + профиль JFR сервера (settings=profile) → mod/run/stress/server/stress.jfr
#
# Моды: MC_DIR (инстанс) или -PmcModsDir; в облаке — python3 tools/fetch_runtime_mods.py и MODS=run/ci-mods.
# На рабочем столе KDE каждый клиент идёт в своём вложенном KWin (tools/nested_kwin.sh: без окна и без звука) на видеокарте;
# в облаке (без KWin) — под xvfb-run программно (llvmpipe): медленно, но честно — те же пакеты, та же камера, тот же HUD.
# На 4 ядрах облака клиенты съедают процессор, генерация чанков стоит в очереди, и любая синхронная загрузка чанка
# (телепорт, Sable) держит тик десятки секунд — клиенты отваливаются по тайм-ауту; тик дольше 0,5 с пишется со стеком.
# Итог: mod/run/stress/server/logs/latest.log (строки STRESS) и mod/run/stress/<игрок>/logs/latest.log (STRESSC).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/mod/run/stress"
MODS_ARG=()
[ -n "${MODS:-}" ] && MODS_ARG=("-PmcModsDir=$MODS")
cd "$ROOT/mod"

./gradlew --console=plain -q classes devtestClasses copyRuntimeMods_stress prepareStressServerRun prepareStressClientRun "${MODS_ARG[@]}"

rm -rf "$RUN/server/world" "$RUN/server/logs"
mkdir -p "$RUN/server"
echo eula=true > "$RUN/server/eula.txt"
cat > "$RUN/server/server.properties" <<PROPS
online-mode=false
enforce-secure-profile=false
view-distance=8
simulation-distance=6
spawn-protection=0
gamemode=creative
difficulty=peaceful
spawn-monsters=false
level-seed=20260928
max-tick-time=-1
sync-chunk-writes=false
PROPS
ln -sfn ../mods "$RUN/server/mods"

pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

AIRSTRIKE_STRESS="${AIRSTRIKE_STRESS:-run}" ./gradlew --console=plain runStressServer "${MODS_ARG[@]}" > "$RUN/server.out" 2>&1 &
server=$!
pids+=("$server")
until grep -q 'Done (' "$RUN/server.out" 2>/dev/null; do
  kill -0 "$server" 2>/dev/null || { echo "сервер не запустился: $RUN/server.out"; exit 1; }
  sleep 2
done

client() {
  local name=$1 role=$2
  mkdir -p "$RUN/$name/logs"
  ln -sfn ../mods "$RUN/$name/mods"
  cat > "$RUN/$name/options.txt" <<OPT
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:4
simulationDistance:6
maxFps:15
graphicsMode:0
particles:1
soundCategory_master:0.0
tutorialStep:none
joinedFirstServer:true
skipMultiplayerWarning:true
OPT
  if command -v kwin_wayland >/dev/null; then
    # рабочий стол KDE: свой вложенный KWin на клиента (без окна, без звука, своя шина и сокет), на видеокарте
    AIRSTRIKE_STRESS_NAME=$name AIRSTRIKE_STRESS_ROLE=$role "$ROOT/tools/nested_kwin.sh" "wayland-airstrike-stress-$name" 854 480 \
      "$ROOT/mod/gradlew -p $ROOT/mod --console=plain runStressClient ${MODS_ARG[*]}" > "$RUN/$name.out" 2>&1 &
  else
    # облако: xvfb-run и программная отрисовка; звуковой сервер клиенту закрыт, как в nested_kwin.sh
    AIRSTRIKE_STRESS_NAME=$name AIRSTRIKE_STRESS_ROLE=$role LIBGL_ALWAYS_SOFTWARE=1 \
      PIPEWIRE_REMOTE="$RUN/no-audio-server" PULSE_SERVER="unix:$RUN/no-audio-server" \
      xvfb-run -a -s "-screen 0 854x480x24" ./gradlew --console=plain runStressClient "${MODS_ARG[@]}" > "$RUN/$name.out" 2>&1 &
  fi
  pids+=("$!")
}
client Host host
sleep 20
client Friend1 leaver
sleep 20
client Friend2 friend

wait "$server" || true
sleep 30
grep -h 'STRESS summary\|STRESS problem\|STRESS lost' "$RUN/server/logs/latest.log" || true
