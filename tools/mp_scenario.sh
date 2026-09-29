#!/usr/bin/env bash
# Мультиплеер без окон: выделенный сервер с модом (Create, Sable, Aeronautics) и два клиента, каждый во вложенном
# KWin (tools/nested_kwin.sh). Alpha (оператор) бьёт по игроку Bravo и по точке и посреди подлёта выходит и заходит
# снова; Bravo выходит, пока снаряды к нему летят, и возвращается (сценарий — src/devtest/.../MultiplayerScenario).
#   tools/mp_scenario.sh   → mod/run/mp-server/logs/latest.log, mod/run/mp-a|mp-b/logs/latest.log, сводка в конце
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
./gradlew runServer -PserverDir=run/mp-server --console=plain > "$RUN/mp-server.out" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT
for _ in $(seq 600); do
  grep -q 'Done (' "$SERVER/logs/latest.log" 2>/dev/null && break
  kill -0 $SERVER_PID 2>/dev/null || { echo "сервер не запустился: $RUN/mp-server.out" >&2; exit 1; }
  sleep 1
done
grep -q 'Done (' "$SERVER/logs/latest.log" || { echo "сервер не поднялся за 10 минут" >&2; exit 1; }

client() { # ник, сценарий, каталог
  local dir="$RUN/$3"
  rm -rf "$dir/logs" "$dir/screenshots"
  mkdir -p "$dir/logs"
  [ -f "$dir/options.txt" ] || printf 'onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:8\nsimulationDistance:8\nsoundCategory_master:0.0\ntutorialStep:none\njoinedFirstServer:true\n' > "$dir/options.txt"
  AIRSTRIKE_SCENARIO="$2" "$ROOT/tools/nested_kwin.sh" "wayland-airstrike-$3" 960 540 \
      "$MOD/gradlew -p $MOD runClientScenario -PscenarioDir=run/$3 -PscenarioUser=$1 --console=plain" > "$RUN/$3.out" 2>&1
}
client Bravo mp-b mp-b &
B_PID=$!
# клиенты стартуют по очереди: у них общие файлы запуска clientScenario (MDG пишет их перед стартом)
for _ in $(seq 600); do
  grep -q 'SCENARIO mp joined' "$RUN/mp-b/logs/latest.log" 2>/dev/null && break
  kill -0 $B_PID 2>/dev/null || break
  sleep 1
done
client Alpha mp-a mp-a &
A_PID=$!
wait $A_PID $B_PID || true
kill $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true

echo "== сервер"
grep -E "joined the game|left the game|Удар|Снаряд|ERROR|Exception|at ua\.zentix" "$SERVER/logs/latest.log" | cut -c1-200 || true
for c in mp-a mp-b; do
  echo "== $c"
  grep -E "SCENARIO mp|ERROR\] \[ua\.zentix|at ua\.zentix|Exception" "$RUN/$c/logs/latest.log" | cut -c1-200 || true
done
