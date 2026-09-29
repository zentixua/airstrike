#!/usr/bin/env bash
# Стенд нагрузки: выделенный сервер с режиссёром (StressDirector) и три игрока-клиента без окна (StressClient):
# Host пускает с пульта, Friend1 выходит посреди удара и возвращается, Friend2 — обычный друг.
# Режиссёр по расписанию гоняет залпы по 30, удары по игрокам, точкам и аппаратам в 300–1000 блоках,
# перенацеливание, гибель целей, уход за край загрузки, Незер, ядерку, save-all и «Отбой», в конце — сводка.
#
#   tools/stress.sh                 # весь сценарий
#   AIRSTRIKE_STRESS_RESTART=1 tools/stress.sh   # + остановка сервера посреди полёта и продолжение после запуска
#   AIRSTRIKE_JFR=1 tools/stress.sh   # + профиль JFR сервера (settings=profile) → mod/run/stress/server/stress.jfr
#   AIRSTRIKE_STRESS_PROBES=false tools/stress.sh   # без залпов-проб РСЗО по свежим районам (замер A/B остановок сервера)
#
# Моды: MC_DIR (инстанс) или -PmcModsDir; в облаке — python3 tools/fetch_runtime_mods.py и MODS=run/ci-mods.
# На рабочем столе KDE каждый клиент идёт в своём вложенном KWin (tools/nested_kwin.sh: без окна и без звука) на видеокарте;
# в облаке (без KWin) — под xvfb-run программно (llvmpipe): медленно, но честно — те же пакеты, та же камера, тот же HUD.
# На 4 ядрах облака клиенты съедают процессор, генерация чанков стоит в очереди, и любая синхронная загрузка чанка
# (Sable) держит тик десятки секунд — клиенты отваливаются по тайм-ауту; тик дольше 0,5 с пишется со стеком и строкой
# «остановка:» (какой чанк грузится синхронно, уровни тикетов вокруг, тикеты мода рядом). Телепорты стенда ждут района
# в фоне: квадрат дистанции симуляции + 1 чанк (тикет загрузки без тика), иначе ваниль сама стоит у свежего места игрока.
# Итог: mod/run/stress/server/logs/latest.log (строки STRESS) и mod/run/stress/<игрок>/logs/latest.log (STRESSC).
# Gradle только собирает и готовит запуски (rigLaunch → mod/build/rig/stress-*.sh) и выходит до старта: у живого Gradle
# UDP-сокет блокировок на 0.0.0.0, а в стенде в сеть не смотрит ничего — сервер и клиенты идут прямо из файлов MDG.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/mod/run/stress"
MODS_ARG=()
[ -n "${MODS:-}" ] && MODS_ARG=("-PmcModsDir=$MODS")
cd "$ROOT/mod"

# сервер слушает только петлю и свободный порт: на VPS 0.0.0.0:25565 с online-mode=false нашёл сканер из интернета
# (сторож LoopbackGuard роняет сервер, если сокет не на петле); клиенты берут порт из AIRSTRIKE_STRESS_PORT
export AIRSTRIKE_STRESS_PORT="${AIRSTRIKE_STRESS_PORT:-$(python3 "$ROOT/tools/free_port.py")}"
CLIENTS=("Host host" "Friend1 leaver" "Friend2 friend")

# настройки запусков (имя и роль клиента, порт, режим режиссёра) Gradle читает из окружения при подготовке;
# --no-daemon: без живого Gradle после подготовки, даже если ~/.gradle/gradle.properties или GRADLE_OPTS включают демона
AIRSTRIKE_STRESS="${AIRSTRIKE_STRESS:-run}" ./gradlew --no-daemon --console=plain -q rigLaunch -PrigRun=runStressServer -PrigOut=stress-server "${MODS_ARG[@]}"
for c in "${CLIENTS[@]}"; do
  read -r name role <<< "$c"
  AIRSTRIKE_STRESS_NAME=$name AIRSTRIKE_STRESS_ROLE=$role \
    ./gradlew --no-daemon --console=plain -q rigLaunch -PrigRun=runStressClient -PrigOut="stress-$name" "${MODS_ARG[@]}"
done
RIG="$ROOT/mod/build/rig"
rm -rf "$RUN/server/world" "$RUN/server/logs"
mkdir -p "$RUN/server"
echo eula=true > "$RUN/server/eula.txt"
cat > "$RUN/server/server.properties" <<PROPS
server-ip=127.0.0.1
server-port=$AIRSTRIKE_STRESS_PORT
online-mode=false
enable-rcon=false
enable-query=false
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
# NeoForge объявляет выделенный сервер в LAN (UDP-сокет на 0.0.0.0, рассылка MOTD и порта) — у проверок выключено
python3 "$ROOT/tools/rig_config.py" "$RUN/server"

# сервер и клиенты — каждый в своей сессии; выход скрипта (и Ctrl+C) гасит их целиком, с JVM под обёртками
source "$ROOT/tools/rig_procs.sh"
rig_trap

rig_spawn "$RIG/stress-server.sh" > "$RUN/server.out" 2>&1
server=$RIG_LAST
until grep -q 'Done (' "$RUN/server.out" 2>/dev/null; do
  kill -0 "$server" 2>/dev/null || { echo "сервер не запустился: $RUN/server.out"; exit 1; }
  sleep 2
done

client() {
  local name=$1
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
    rig_spawn "$ROOT/tools/nested_kwin.sh" "wayland-airstrike-stress-$name" 854 480 "$RIG/stress-$name.sh" > "$RUN/$name.out" 2>&1
  else
    # облако: xvfb-run и программная отрисовка; звуковой сервер клиенту закрыт, как в nested_kwin.sh
    LIBGL_ALWAYS_SOFTWARE=1 PIPEWIRE_REMOTE="$RUN/no-audio-server" PULSE_SERVER="unix:$RUN/no-audio-server" \
      rig_spawn xvfb-run -a -s "-screen 0 854x480x24" "$RIG/stress-$name.sh" > "$RUN/$name.out" 2>&1
  fi
}
for i in "${!CLIENTS[@]}"; do
  [ "$i" -gt 0 ] && sleep 20
  client "${CLIENTS[$i]%% *}"
done

wait "$server" || true
sleep 30
grep -h 'STRESS summary\|STRESS problem\|STRESS lost' "$RUN/server/logs/latest.log" || true
