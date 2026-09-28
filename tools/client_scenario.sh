#!/usr/bin/env bash
# Клиент мода без окна: виртуальный дисплей KWin + Xwayland (tools/nested_kwin.sh — своя шина D-Bus и настройки), звук пишется в WAV (OpenAL Soft «wave»).
# Сценарий (mod/src/devtest/.../ClientScenario) пускает все виды оружия и снимает кадры.
#   tools/client_scenario.sh [all|launch|rocket|loiter|hud|map|nuke|fx|fx-night|models|occlusion] [shaders]   → mod/run/scenario/screenshots/*.png, audio.wav, logs/latest.log
#   all — шахед, ракета, бомба, залп, бинокль и пульт (короткие полёты издалека);
#   launch — пуск с пусковой у игрока, отделение ускорителя, камера снаряда (V) до удара;
#   rocket — залп РСЗО: камера у пакета на очереди, дуги над головой, разрывы по площади;
#   map — камера снаряда дальше прорисовки: видео рядом, дальше карта по телеметрии, попадание на карте;
#   loiter — рой барражирующих: катапульта, камера на круге (оператор смотрит на цель), со стороны — круги и пике;
#   nuke — МБР и ядерный удар 15 кт с 2 км, чёрный дождь;
#   fx, fx-night — эффекты крупным планом (взрывы шахеда, ракеты, бомбы и старт МБР; днём и ночью);
#   models — модели снарядов крупным планом с трёх сторон (на пусковой, в полёте, B-2 с открытым бомболюком);
#   occlusion — большие залпы за каменной стеной, потом камера водит взглядом; в лог — частицы по слоям движка;
#   shaders — ещё Sodium, Iris и шейдерпак хоста из инстанса (как у Артёма)
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
MC="$(python3 "$ROOT/tools/paths.py" MC)"
if [ "${2:-}" = shaders ]; then
  export AIRSTRIKE_SHADERS=1
  PACK="$(sed -n 's/^shaderPack=//p' "$MC/config/iris.properties")"
  mkdir -p "$RUN/shaderpacks" "$RUN/config"
  rm -rf "$RUN/shaderpacks/$PACK"
  cp -r "$MC/shaderpacks/$PACK" "$RUN/shaderpacks/"
  printf 'enableShaders=true\nshaderPack=%s\ndisableUpdateMessage=true\n' "$PACK" > "$RUN/config/iris.properties"
else
  unset AIRSTRIKE_SHADERS
fi
export JAVA_HOME="${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}"
cd "$ROOT/mod"
exec "$ROOT/tools/nested_kwin.sh" wayland-airstrike-scenario 1280 720 "$ROOT/mod/gradlew runClientScenario --console=plain"
