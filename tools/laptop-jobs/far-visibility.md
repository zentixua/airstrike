# Ноутбук: дальняя видимость 2.4.1 — снаряды и взрывы вдали со всей сборкой (одна задача)

Тред «Оружие видно издалека», PR #175, ветка `claude/project-thread-o3dgvv`. Кандидат — один коммит: координатор
вписывает его полный SHA вместо `<SHA>` во **все** блоки ниже перед отправкой (и `SHA7` — первые 7 знаков).
Артём в это время не играет.

**Что проверяем.** Со сборкой Артёма (DH, Iris с шейдерами, ~220 модов) на копии Greenfield: зритель в небе над городом
пускает по точкам в 1,5 и 4 км к северу ракету, залп РСЗО, шахед, B-2 и залп ракет днём, ночью и в дождь. Кадры:
снаряд в полёте дальше прорисовки (точка, факел, шлейф), вспышка, огненный шар и столб дыма вдали; всё дальнее — своим
шейдером (`shaders/core/far_sprite`), дымка — прозрачностью, а не цветом тумана. В лог — строки
`SCENARIO far …` (что рисуется вдали и когда пришёл звук взрыва), `SCENARIO fps …`. Плюс лёгкий шаг Б: статусы чанков
на диске вокруг ядерных ударов в мире Артёма (копии файлов региона, только чтение).

## Правила (для каждого блока)
- Каждый блок — **одним вызовом, как написан**; состояние оболочки между вызовами не сохраняется. Блоки с шага 2
  начинаются с `W=… && cd "${W:?}"`.
- Папка — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7` (новая). Уже есть — стоп, сообщить
  координатору, ничего не удалять и не перезаписывать.
- Инстанс Артёма, его миры и настройки не трогать: `prod_client.py` копирует инстанс и мир в `$W/mod/run/prod`;
  шаг Б копирует нужные файлы региона в `$W/mod/run/far/regions` и читает только копии.
- Клиент — только через `tools/nested_kwin.sh` (это делает `prod_client.py`); тяжёлое — только `tools/laptop_job.sh`;
  одна задача за раз.
- Остановить задачу — только `systemctl --user stop 'airstrike-job-far-vis-*'`. Никаких `pkill`/`killall`/`kill`
  по имени, `./gradlew --stop`, `rm -rf`, `-f`/`--force`.
- Смерть прогона — координатору в течение 5 мин: последние 40 строк лога и `crash-reports/`. Прогон не повторять
  без слова координатора.
- Результат — только текст (до ~40 КБ на сообщение) и названные кадры (SendUserFile). Логи, звук и копии миров
  остаются в `$W`; уборку решает Артём словами в треде «Ноутбук».

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
SHA=<SHA>; [ ${#SHA} = 40 ] || echo "стоп: SHA не вписан"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && echo "стоп: папка far-vis-SHA7 уже есть"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
ls "$MC/saves"        # есть «Greenfield v0.5.4»; для шага Б — «Newisle 2.3.0» и/или «Newisle 2.3.0 exper»
du -sh "$MC/saves/Greenfield v0.5.4" "$MC/mods"; df -h /mnt/data/projects/airstrike/mod/run
grep -E '^(renderDistance|graphicsMode):' "$MC/options.txt"; ls "$MC/shaderpacks" 2>/dev/null | head; ls "$MC/mods" | grep -iE 'distanthorizons|iris' 
```
Свободно — не меньше Greenfield + mods + 5 ГБ. Прислать вывод последних двух строк (прорисовка, шейдеры, DH и Iris).

## 2. Подготовка: worktree, сборка, сценарий на месте
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && R=/mnt/data/projects/airstrike && cd "${R:?}" && \
git fetch origin claude/project-thread-o3dgvv && git worktree add "$W" <SHA> && cd "${W:?}" && mkdir -p "$W/mod/run/far" && \
git log --oneline -1 && [ "$(git rev-parse HEAD)" = <SHA> ] && echo "коммит верный" && \
grep -q 'wait:blast' mod/src/devtest/java/ua/zentix/airstrike/scenario/CommandPlan.java && \
grep -q 'SCENARIO far' mod/src/devtest/java/ua/zentix/airstrike/scenario/ClientScenario.java && \
test -f mod/src/main/java/ua/zentix/airstrike/client/far/FarRenderer.java && echo "сценарий на месте"
```
Должно быть «коммит верный» и «сценарий на месте». Нет — стоп, прислать вывод.

Сборка тестового jar (в фоне, тайм-аут вызова 25 мин; код не 0 — стоп, сообщить):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ../tools/laptop_job.sh far-vis-build -- ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

## Б. Статусы чанков на диске вокруг ядерных ударов 01.10 (лёгкий, до прогона)
Игра Артёма на 2.4.0: наземный подрыв №5 по `294 72 -686` (тяжёлая зона 15 278 чанков, «не с диска 6715») и воздушный
№2 по `-217 658 13`. Какой из двух миров — неизвестно, поэтому оба, если есть. Копируются только файлы региона
`r.-3..2.-4..1` (36 штук на мир), читаются копии.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && cd "${W:?}" && \
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft" && \
for world in "Newisle 2.3.0" "Newisle 2.3.0 exper"; do \
  [ -d "$MC/saves/$world/region" ] || { echo "$world: нет"; continue; }; \
  D="mod/run/far/regions/$world" && mkdir -p "$D/region" && \
  for x in -3 -2 -1 0 1 2; do for z in -4 -3 -2 -1 0 1; do f="$MC/saves/$world/region/r.$x.$z.mca"; [ -f "$f" ] && cp -pn "$f" "$D/region/"; done; done; \
  echo "== $world: файлов $(ls "$D/region" | wc -l), $(du -sh "$D" | cut -f1)"; \
  echo "-- №5 (294 -686, радиус 72 чанка)"; timeout 600 python3 tools/chunk_status.py "$D" 294 -686 72; \
  echo "-- №2 (-217 13, радиус 58 чанков)"; timeout 600 python3 tools/chunk_status.py "$D" -217 13 58; \
done > mod/run/far/chunk-status.txt 2>&1; head -c 40000 mod/run/far/chunk-status.txt
```

## A. Прогон (один; в фоне, тайм-аут вызова 45 мин)
Зритель в спектаторе на `-272 140 -942` (город), взгляд на север. Цели — `-272 64 -2442` (1,5 км) и `-272 64 -4942`
(4 км); высота 64 — у земли или воды: снаряд взрывается о первую поверхность. Пусковую у зрителя в воздухе не поставить,
и удар заходит издалека по курсу взгляда стреляющего: старт — за спиной на длину маршрута от цели. С курсом на север
ночная ракета на 4 км стартовала в 150 блоках за зрителем, на высоте маршрута над городом, и оба раза врезалась в высотку
у `-272 107 -918`. Поэтому перед ракетами, шахедом и РСЗО зритель на миг поворачивается на курс 45° (`tp … 45 3`):
старт — к северо-востоку от цели, за 2–8 км от зрителя и вне города, путь идёт к цели наискосок через поле зрения;
после команды — снова на север. B-2 летит высоко, ему — прежний курс. День и погода стоят (`doDaylightCycle`,
`doWeatherCycle` выключены). `wait:blast` — шаги ждут следующего взрыва на сервере (не дольше 3600 тиков; строки
`SCENARIO commands: взрыв, шаги ждали N тиков` или `… взрыва нет …`). Команда ждёт 40 тиков, кадр — 20, поэтому
`*_flash` снимается на 2-м тике после взрыва (вспышка и молодой шар), `*_fireball` — на 22-м (шар ракеты остывает;
у шахеда и РСЗО шар живёт 13 и 8 тиков — их только `*_flash`). Видео всего прогона — `mod/run/far/take.mkv`
(1920×1080, для клипов координатора).
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && cd "${W:?}" && touch mod/run/far/.step-start && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 40m tools/laptop_job.sh far-vis -- python3 tools/prod_client.py commands --world "Greenfield v0.5.4" --seconds 2100 \
  --size 1920x1080 --video "$W/mod/run/far/take.mkv" \
  --prop 'airstrike.commands=hud:off;gamemode spectator;gamerule doDaylightCycle false;gamerule doWeatherCycle false;time set 6000;weather clear;tp @s -272 140 -942 180 3;wait:600;shot:base;tp @s -272 140 -942 45 3;airstrike missile at -272 64 -2442;tp @s -272 140 -942 180 3;wait:160;shot:flight_missile;wait:blast;wait:2;shot:missile_flash;shot:missile_fireball;wait:180;shot:missile_column;wait:800;shot:missile_column_60s;tp @s -272 140 -942 45 3;airstrike salvo rocket 30 20 at -272 64 -2442;tp @s -272 140 -942 180 3;wait:blast;wait:10;shot:rocket_impacts;wait:300;shot:rocket_dust;tp @s -272 140 -942 45 3;airstrike drone at -272 64 -2442;tp @s -272 140 -942 180 3;wait:300;shot:flight_drone;wait:blast;wait:2;shot:drone_flash;wait:300;shot:drone_column;airstrike bunker at -272 64 -2442;wait:blast;wait:10;shot:bunker;wait:400;shot:bunker_dust;tp @s -272 140 -942 45 3;airstrike salvo missile 3 30 at -272 64 -4942;tp @s -272 140 -942 180 3;wait:400;shot:flight_far;wait:blast;wait:2;shot:far4k_flash;shot:far4k_fireball;wait:280;shot:far4k_column;time set 18000;wait:100;tp @s -272 140 -942 45 3;airstrike salvo rocket 30 20 at -272 64 -2442;tp @s -272 140 -942 180 3;wait:blast;wait:5;shot:night_impacts;wait:100;shot:night_after;tp @s -272 140 -942 45 3;airstrike missile at -272 64 -4942;tp @s -272 140 -942 180 3;wait:300;shot:night_flight;wait:blast;wait:2;shot:night_flash_4k;shot:night_fireball_4k;time set 6000;weather rain;wait:100;tp @s -272 140 -942 45 3;airstrike missile at -272 64 -2442;tp @s -272 140 -942 180 3;wait:blast;wait:2;shot:rain_flash;shot:rain_fireball;wait:280;shot:rain_column;weather clear;wait:200'; \
code=$?; echo "код $code, конец: $(date -u +%T) UTC"; case "$code" in 124|137) systemctl --user stop 'airstrike-job-far-vis-*';; esac; true
```
Наблюдатель: в `$W/mod/run/prod/logs/latest.log` идут строки `SCENARIO …`, в конце `SCENARIO done`.

## В. Лог, кадры, выжимка (сразу после прогона, одним вызовом)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/far-vis-SHA7" && cd "${W:?}" && D=mod/run/far && P=mod/run/prod && mkdir -p "$D/logs" "$D/frames" && \
G=$(find "$P/logs" -maxdepth 1 -name '*.log.gz' -newer "$D/.step-start" | sort -V) && \
{ for g in $G; do zcat "$g"; done; cat "$P/logs/latest.log"; } > "$D/logs/full.log" && cp -pn "$P/logs/gc.log" "$D/logs/" 2>/dev/null; \
[ -f "$P/audio.wav" ] && cp -pn "$P/audio.wav" "$D/"; \
for f in $(find "$P/screenshots" -maxdepth 1 -name '*.png' -newer "$D/.step-start" | sort); do n=$(basename "$f" .png); ffmpeg -loglevel error -y -i "$f" -q:v 3 "$D/frames/$n.jpg"; done; \
ls "$D/frames"; grep -a -c 'SCENARIO done' "$D/logs/full.log"; \
python3 tools/logscan.py "$D/logs/full.log" --all > "$D/logscan.txt"; wc -lc "$D/logscan.txt"; \
grep -aE 'SCENARIO (commands|far|fps|/airstrike)|Удар: |has crashed|emergencySaveAndCrash|Unreported exception|Distant Horizons' "$D/logs/full.log" | cut -c1-400 > "$D/lines.txt"; wc -lc "$D/lines.txt"; \
grep -aE 'Pause (Young|Old|Full)' "$D/logs/gc.log" 2>/dev/null | awk '{ms=$NF; sub(/ms$/,"",ms); if (ms+0>300) print}' > "$D/gc-pauses.txt"; echo "пауз GC > 300 мс: $(wc -l < "$D/gc-pauses.txt")"
```

## Что прислать координатору
0. Вывод шагов 1 (прорисовка, шейдеры, DH, Iris), 2 и код и время прогона.
1. `mod/run/far/chunk-status.txt` целиком (шаг Б; до 40 КБ, иначе — гистограммы `Status` и строки итогов).
2. `mod/run/far/lines.txt` целиком (до 40 КБ; больше — без строк `SCENARIO far`, а из них — каждую десятую и все со словом
   «звук»).
3. logscan: ошибки, исключения, `Mixin`, `AccessTransformer` — или «ноль»; `gc-pauses.txt` целиком.
4. По строке на каждый кадр по критериям ниже; кадры — все JPEG из `mod/run/far/frames/` в
   `/mnt/project-files/far-visibility/laptop/` (SendUserFile).

## Проходит, если
- прогон кончается сам с **кодом 0**, `SCENARIO done` есть, нет `has crashed`/`emergencySaveAndCrash`/`Unreported exception`;
- каждое `wait:blast` дождалось взрыва (нет строк `взрыва нет`);
- звук: после одиночных ударов (ракета, шахед, B-2, ракеты на 4 км, ночная ракета, ракета в дождь) в строках
  `SCENARIO far` есть раскат с дальностью d и задержкой ≈ d / 17,15 тика (±3); громкость у 1,5 км днём > 0
  (строка пишется раз в секунду, так что у залпа видно только последний раскат);
- fps: в окнах залпов (строки `SCENARIO fps` после `rocket_impacts`, `night_impacts`) не ниже 90 % от fps кадра `base`;
  в строках `SCENARIO far` время дальней отрисовки (`… мс (самый долгий за секунду …)`) — в среднем не больше 1 мс,
  самый долгий — не больше 3 мс. Если fps ниже, а дальняя отрисовка в пределах, прислать строки `SCENARIO fps` с тиком
  сервера (`сервер N мс/тик`) этого окна — причина не в ней;
- logscan — без новых ошибок; исключений со `ua.zentix` в стеке нет; пауз GC > 300 мс нет;
- кадры (критерии — для глаза, по строке на кадр):
  - `flight_missile`, `flight_drone`, `flight_far`, `night_flight`: снаряд дальше прорисовки — настоящего углового
    размера, без наименьшего: днём шахед и ракета за 1,5 км — тёмная точка ~1,3 px, за 4 км — бледная точка, шлейф
    ускорителя — белая лента; ночью факел ускорителя — яркая искра с ореолом, маршевый двигатель ракеты почти не светит;
  - `*_flash`: белая вспышка у горизонта в стороне цели (держится 2–4 тика) и под ней молодой шар; `*_fireball`
    (ракета, 22-й тик): остывающий тёмно-оранжевый шар над столбом — днём оранжевым телом без белого ореола, ночью
    с оранжевым ореолом; в строках `SCENARIO far … ярче всех: ядро, пересвет, блик, вуаль` — размеры в пикселях;
  - `*_column`, `*_dust`: столб дыма или пыли растёт (через 2 минуты у шахеда ~220 м, у ракеты ~390), свежий — чёрный,
    потом серо-бурый, сносится ветром, вдали бледнее (дымка); `missile_column_60s`: столб ещё стоит;
  - `rocket_impacts`/`night_impacts`: россыпь вспышек по площади; ночью вспышки ярче и заметнее, чем днём;
  - `night_flash_4k`, `night_impacts`: над вспышкой — зарево на облаках (пятно на их высоте, ~130 блоков над местом) и
    широкий ореол вокруг вспышки; днём (`missile_flash`, `far4k_flash`) ни того, ни другого;
  - `bunker`, `bunker_dust`: без огненной вспышки (подземный), пыль над местом удара; если B-2 был виден — отметить;
  - `rain_*`: дым серый, не синий; дымка сильнее, чем днём в ясную погоду (дождь — видимость 3 км);
  - нет квадратных краёв у клубов, нет мигания, ничего не висит перед камерой.
