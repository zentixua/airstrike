#!/usr/bin/env bash
# Клиент мода без окна: виртуальный дисплей KWin + Xwayland (tools/nested_kwin.sh — своя шина D-Bus и настройки), звук пишется в WAV (OpenAL Soft «wave»).
# Сценарий (mod/src/devtest/.../ClientScenario) пускает все виды оружия и снимает кадры.
#   tools/client_scenario.sh [all|guide|launch|rocket|loiter|hud|map|target-map|salvo-map|route-map|launch-map|nuke|nuke-water|nuke-lake|pall|pall-front|pall-low|mushroom|firestorm|wall|fx|fx-night|fx-late|models|far-models|occlusion|onboard|flyby|flyby-all|flyby-<случай>] [shaders] [dh]   → mod/run/scenario/screenshots/*.png, audio.wav, logs/latest.log
#   all — шахед, ракета, бомба, залп, бинокль и пульт (короткие полёты издалека);
#   guide — кадры руководства игрока (GuideShots): рецепты, бинокль, пульт, GIF карты, снаряды на экране, камера,
#     «Ланцет», ЗРК, стационарная пусковая; INCOMING_GUIDE=recipes,map — только эти разделы; карта — по прорисовке 20 чанков, без dh;
#     картинки в docs/guide/img — tools/guide_images.sh;
#   launch — пуск с пусковой у игрока, отделение ускорителя, камера снаряда (N) до удара;
#   rocket — залп РСЗО: камера у пакета на очереди, дуги над головой, разрывы по площади;
#   map — камера снаряда дальше прорисовки: видео рядом, дальше карта по телеметрии, попадание на карте;
#   target-map — карта наведения пульта: масштаб, выбор места кликом, огонь по Enter, снаряд на карте;
#   salvo-map — залп шахедов с разбросом на карте наведения: кадры путей и района, время открытия карты в логе;
#   route-map — маршрут на карте наведения: точки, правка мышью, длиннее дальности — без огня, полёт по точкам и попадание;
#   launch-map — то же с местом пуска с карты: кнопка и клик, значок перетащен, старт с него (пусковая или из места вне мира);
#   loiter — рой барражирующих: катапульта, камера на круге (оператор смотрит на цель), со стороны — круги и пике;
#   nuke — МБР и ядерный удар 15 кт с 2 км, чёрный дождь;
#   nuke-water — 15 кт на море в 1,5 км от берега (зритель над водой у берега): столб воды с короной, тёмное кольцо и брызги, базисная волна;
#     INCOMING_WATER=far — тот же подрыв из 4 км (за берегом, на высоте 400), узким углом: столб с короной, сужение и падение (water_far_*);
#   nuke-lake — 15 кт на суше в 1,5 км за прудом перед зрителем: фронт идёт через пруд тёмным кольцом со стеной брызг;
#   pall — пелена после ядерной волны (15 кт у земли): с холма в 4 км, изнутри, с высоты, в сумерки и ночью; качество —
#     INCOMING_PALL=LOW|MEDIUM|HIGH (по умолчанию LOW), в лог — строки SCENARIO pall (плотность у камеры, время на видеокарте);
#   pall-front — пелена за фронтом ночью: камера в 400 м от наземных 15 кт на 120 м над землёй (FOV 50), за эпицентром
#     квартал башен; кадры pall_front_* от прихода фронта к камере (0–60 с), качество — INCOMING_PALL (по умолчанию MEDIUM);
#     INCOMING_EXPORT=2560x1440 — кадр игры больше окна, как при выводе видео Flashback;
#   pall-low — то же, но эпицентр на столбе в 45 м над землёй, камера в 8 м над землёй (ниже земли под эпицентром, как
#     из долины): кадры pall_low_* через 5, 20 и 60 с после фронта; пелена — до рельефа и за прорисовкой (с dh и без);
#   mushroom — гриб объёмом: наземные 15 кт в 10 км, зритель в воздухе; кадры pall_mushroom_<секунды>s от 2 с до 4 минут,
#     утро (солнце сбоку) или INCOMING_MUSHROOM=night; качество — INCOMING_PALL (по умолчанию MEDIUM);
#     INCOMING_MUSHROOM=shadow — тень гриба: подрыв в 5 км к востоку, через 3 минуты земля под зрителем при солнце
#     с востока на разной высоте, край тени — по зрителю, и с запада (кадры pall_shadow_<время>; 8000 — без тени);
#   firestorm — огненный шторм после воздушных 15 кт: через ~1, 5 и 15 минут (минуты пропускаются перемоткой) с 2 км,
#     с крыши в 800 м и изнутри у огненного смерча (кадры fs_<ночь|день>_<точка>_<секунды>s); ночью (по умолчанию) или
#     INCOMING_FIRESTORM=day, качество пелены — INCOMING_PALL (по умолчанию MEDIUM), в лог — строки SCENARIO firestorm;
#   wall — стена пыли проходит мимо зрителя в 1,5 км от наземных 15 кт: пыль у камеры и стена в одном кадре (wall_*, мир
#     у прохода идёт медленно); ночью — INCOMING_WALL=night;
#   fx, fx-night — эффекты крупным планом (взрывы шахеда, ракеты, бомбы и старт МБР; днём и ночью);
#   fx-late — шахед и ракета крупным планом и их столб дыма через 45 с: с половины прорисовки и из-за её края (кадры *_late50, *_late110);
#   models — модели снарядов крупным планом с трёх сторон (на пусковой, в полёте, B-2 с открытым бомболюком);
#   far-models — снаряды вдали по пакетам, как от сервера: пары «сущность / путь по пакетам» на одной позе, ряд всех
#     снарядов на 300, 800 и 1500 блоках днём и ночью и в бинокль на 800–3000 (кадры far_*, match_*);
#   occlusion — большие залпы за каменной стеной, потом камера водит взглядом; в лог — частицы по группам слоя эффектов;
#   onboard — видео с борта ракеты (N) при наводчике на суше и под водой: кадры onboard-dry_*, onboard-wet_*;
#   flyby — зритель на земле на пути снарядов издалека (залп РСЗО, ракета, шахед): звук подлёта и пролёта в лог и audio.wav;
#   flyby-all — случаи звука по очереди (FlybySound): approach (ракета из 1,8 км на зрителя), dogleg (ракета на обходе
#     маршрута — без свиста подлёта), pass (пролёт мимо), grad (залп РСЗО из 32 над головой), far (ракета и шахед из-за
#     дальности сущностей); каждый слой, петли, отметки времени и итоги «SCENARIO check … PASS|FAIL» — в лог;
#     один случай — flyby-grad, несколько — flyby-approach,pass;
#   shaders — ещё Sodium, Iris и шейдерпак хоста из инстанса (как у Артёма); dh — ещё Distant Horizons из инстанса
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
# первый запуск: без экрана приветствия и без паузы, когда у окна нет фокуса; без музыки — она в audio.wav поверх звука мода
[ -f "$RUN/options.txt" ] || cat > "$RUN/options.txt" <<OPT
onboardAccessibility:false
pauseOnLostFocus:false
renderDistance:12
simulationDistance:10
guiScale:2
soundCategory_master:1.0
soundCategory_ambient:1.0
soundCategory_music:0.0
tutorialStep:none
joinedFirstServer:true
OPT
# руководство игрока — по-английски, как и само руководство (docs/guide и docs/guide/ru берут одни кадры)
if [ "${1:-all}" = guide ]; then
  if grep -q '^lang:' "$RUN/options.txt"; then sed -i 's/^lang:.*/lang:en_us/' "$RUN/options.txt"; else echo 'lang:en_us' >> "$RUN/options.txt"; fi
fi
export INCOMING_SCENARIO="${1:-all}"
MC="$(python3 "$ROOT/tools/paths.py" MC)"
unset INCOMING_SHADERS INCOMING_DH
for opt in "${@:2}"; do
  case "$opt" in
    shaders)
      export INCOMING_SHADERS=1
      PACK="$(sed -n 's/^shaderPack=//p' "$MC/config/iris.properties")"
      mkdir -p "$RUN/shaderpacks" "$RUN/config"
      rm -rf "$RUN/shaderpacks/$PACK"
      cp -r "$MC/shaderpacks/$PACK" "$RUN/shaderpacks/"
      printf 'enableShaders=true\nshaderPack=%s\ndisableUpdateMessage=true\n' "$PACK" > "$RUN/config/iris.properties"
      ;;
    dh) export INCOMING_DH=1 ;;
    *) echo "неизвестный параметр: $opt (есть shaders, dh)" >&2; exit 2 ;;
  esac
done
export JAVA_HOME="${JAVA_HOME:-$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta}"
cd "$ROOT/mod"
exec "$ROOT/tools/nested_kwin.sh" wayland-incoming-scenario 1280 720 "$ROOT/mod/gradlew runClientScenario --console=plain"
