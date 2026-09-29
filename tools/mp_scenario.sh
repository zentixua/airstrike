#!/usr/bin/env bash
# Мультиплеер без окон: выделенный сервер с модом (Create, Sable, Aeronautics) и два клиента, каждый во вложенном
# KWin (tools/nested_kwin.sh). Alpha (оператор) бьёт по игроку Bravo и по точке и посреди подлёта выходит и заходит
# снова; Bravo выходит, пока снаряды к нему летят, и возвращается (сценарий — src/devtest/.../MultiplayerScenario).
#   tools/mp_scenario.sh   → mod/run/mp-server/logs/latest.log, mod/run/mp-a|mp-b/logs/latest.log, сводка в конце
# Gradle только собирает и готовит запуски (rigLaunch → mod/build/rig/mp-*.sh) и выходит до старта: у живого Gradle
# UDP-сокет блокировок на 0.0.0.0, а в проверке в сеть не смотрит ничего — сервер и клиенты идут прямо из файлов MDG.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
RUN="$MOD/run"
# сервер слушает только петлю и свободный порт (сторож LoopbackGuard роняет сервер, если сокет не на петле)
PORT="${AIRSTRIKE_MP_PORT:-$(python3 "$ROOT/tools/free_port.py")}"
export AIRSTRIKE_MP_PORT=$PORT
export JAVA_HOME="${JAVA_HOME:-$(python3 "$ROOT/tools/paths.py" JAVA)}"

SERVER="$RUN/mp-server"
rm -rf "$SERVER/world" "$SERVER/logs"
mkdir -p "$SERVER"
echo 'eula=true' > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<PROPS
server-ip=127.0.0.1
server-port=$PORT
online-mode=false
enable-rcon=false
enable-query=false
spawn-protection=0
level-seed=20260927
difficulty=peaceful
allow-flight=true
view-distance=8
simulation-distance=8
motd=airstrike-mp
PROPS
# NeoForge объявляет выделенный сервер в LAN (UDP-сокет на 0.0.0.0, рассылка MOTD и порта) — у проверок выключено
python3 "$ROOT/tools/rig_config.py" "$SERVER"
# ops.json: Alpha — оператор (офлайн-UUID, как его считает сервер без авторизации)
python3 - "$SERVER/ops.json" <<'PY'
import hashlib, json, sys, uuid
def offline(name):
    h = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    h[6] = h[6] & 0x0f | 0x30
    h[8] = h[8] & 0x3f | 0x80
    return str(uuid.UUID(bytes=bytes(h)))
json.dump([{"uuid": offline("Alpha"), "name": "Alpha", "level": 4, "bypassesPlayerLimit": False}], open(sys.argv[1], "w"))
PY

cd "$MOD"
# каталог, ник и сценарий клиента Gradle читает при подготовке (-P и окружение), у каждого клиента свой скрипт;
# --no-daemon: без живого Gradle после подготовки, даже если ~/.gradle/gradle.properties или GRADLE_OPTS включают демона
./gradlew --no-daemon --console=plain -q rigLaunch -PrigRun=runServer -PserverDir=run/mp-server -PrigOut=mp-server
for c in "Alpha mp-a" "Bravo mp-b"; do
  read -r name dir <<< "$c"
  AIRSTRIKE_SCENARIO=$dir ./gradlew --no-daemon --console=plain -q rigLaunch -PrigRun=runClientScenario \
    -PscenarioDir="run/$dir" -PscenarioUser="$name" -PrigOut="$dir"
done
RIG="$MOD/build/rig"
# сервер и клиенты — каждый в своей сессии; выход скрипта (и Ctrl+C) гасит их целиком, с JVM под обёртками
source "$ROOT/tools/rig_procs.sh"
rig_trap
rig_spawn "$RIG/mp-server.sh" > "$RUN/mp-server.out" 2>&1
SERVER_PID=$RIG_LAST
for _ in $(seq 600); do
  grep -q 'Done (' "$SERVER/logs/latest.log" 2>/dev/null && break
  kill -0 $SERVER_PID 2>/dev/null || { echo "сервер не запустился: $RUN/mp-server.out" >&2; exit 1; }
  sleep 1
done
grep -q 'Done (' "$SERVER/logs/latest.log" || { echo "сервер не поднялся за 10 минут" >&2; exit 1; }

client() { # каталог (он же сценарий)
  local dir="$RUN/$1"
  rm -rf "$dir/logs" "$dir/screenshots"
  mkdir -p "$dir/logs"
  [ -f "$dir/options.txt" ] || printf 'onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:8\nsimulationDistance:8\nsoundCategory_master:0.0\ntutorialStep:none\njoinedFirstServer:true\n' > "$dir/options.txt"
  rig_spawn "$ROOT/tools/nested_kwin.sh" "wayland-airstrike-$1" 960 540 "$RIG/$1.sh" > "$RUN/$1.out" 2>&1
}
client mp-b
B_PID=$RIG_LAST
# Alpha заходит, когда Bravo уже в мире: сценарий Alpha бьёт по нему
for _ in $(seq 600); do
  grep -q 'SCENARIO mp joined' "$RUN/mp-b/logs/latest.log" 2>/dev/null && break
  kill -0 $B_PID 2>/dev/null || break
  sleep 1
done
client mp-a
A_PID=$RIG_LAST
wait $A_PID $B_PID || true
rig_stop $SERVER_PID

echo "== сервер"
grep -E "joined the game|left the game|Удар|Снаряд|ERROR|Exception|at ua\.zentix" "$SERVER/logs/latest.log" | cut -c1-200 || true
for c in mp-a mp-b; do
  echo "== $c"
  grep -E "SCENARIO mp|ERROR\] \[ua\.zentix|at ua\.zentix|Exception" "$RUN/$c/logs/latest.log" | cut -c1-200 || true
done
