#!/usr/bin/env bash
# Клиент мода без окна: виртуальный дисплей KWin + Xwayland, звук пишется в WAV (OpenAL Soft «wave»).
# Сценарий (mod/src/devtest/.../ClientScenario) пускает все виды оружия и снимает кадры.
#   tools/client_scenario.sh [all|nuke]   → mod/run/scenario/screenshots/*.png, mod/run/scenario/audio.wav, logs/latest.log
#   all — шахед, ракета, бомба, залп, бинокль и пульт; nuke — МБР и ядерный удар 15 кт с 2 км, чёрный дождь
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/mod/run/scenario"
mkdir -p "$RUN"
rm -rf "$RUN/screenshots"
cat > "$RUN/alsoft.conf" <<CONF
[general]
drivers = wave
frequency = 22050
channels = stereo
sample-type = int16
[wave]
file = $RUN/audio.wav
CONF
# первый запуск: без экрана приветствия и без паузы, когда у окна нет фокуса
[ -f "$RUN/options.txt" ] || cat > "$RUN/options.txt" <<OPT
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:12
simulationDistance:10
guiScale:2
soundCategory_master:1.0
soundCategory_ambient:1.0
tutorialStep:none
joinedFirstServer:true
OPT
export AIRSTRIKE_SCENARIO="${1:-all}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}"
cd "$ROOT/mod"
exec env -u DISPLAY -u WAYLAND_DISPLAY kwin_wayland --virtual --xwayland --socket wayland-airstrike-scenario \
    --width 1280 --height 720 --exit-with-session "$ROOT/mod/gradlew runClientScenario --console=plain"
