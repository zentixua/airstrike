# Ноутбук: ядерка со всей сборкой — числа и кадры (одна задача)

Четвёртый прогон (после `nuke-gate3` 30.09, 20:27 UTC): папки и задача — `nuke-gate4`, следы прошлых (`nuke-gate*`)
не трогаются. Сторожа температуры нет (Артём, 16:47 UTC).

Тред «Ядерный взрыв: ударная волна». Ветка `claude/project-thread-39w82y`, коммит **@SHA@** — полный SHA из сообщения
координатора подставить во все блоки ниже вместо `@SHA@` (одна замена, до шага 2; строки с `@SHA@` после неё быть не
должно). Что поменялось после gate3: выход из мира после ядерки (задачи чанков в каждом круге выгрузки), доля руин по
готовому плану считается по одному множеству (в gate3 вышло 122 %), подстройка фоновых потоков не считает свои полосы
мода, чанки у края видимости не ждут соседей (окно плана — с диска), зона за волной грузит квадраты 5×5 без полей,
строка «Работа мода за 30 с» (раз в 30 с: тик сервера, полосы мода, остальное).
Здесь и сборка Артёма целиком (prod_client: лаг, память, тики, ошибки), и картинка (башни, рельеф, висящие блоки,
свечение).

Условия: Артём не играет; одна тяжёлая задача на машине; только `tools/laptop_job.sh`; клиент только через
`tools/nested_kwin.sh` (это делает prod_client.py); большие файлы — в `mod/run/`. Инстанс Артёма, его миры и настройки
не трогать. Мир фильма (`greenfield-film`) не трогать: удар идёт по **копии** `greenfield-gate`. Инстанс фильма
(`mod/run/film/instance/minecraft`) после прогона возвращается как был: `options.txt` и jar мода в `mods/` — шаги 2 и 6.

**Каждый блок ниже — одним вызовом, как написан**: состояние оболочки между вызовами не сохраняется. Блоки с шага 2
начинаются с `W=… && cd "${W:?}"`; блок 1 работает до появления папки и пишет полные пути. Папка worktree — ровно
`/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4` (новая; если она уже есть — остановиться и сообщить
координатору, ничего не удалять и не перезаписывать).

**Остановить задачу — только** `systemctl --user stop 'airstrike-job-nuke-gate4-*'`. Никаких `pkill`/`killall`/`kill`
по имени, никакого `./gradlew --stop`, никаких `-f`/`--force`.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп» или «нет» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4" && echo "папка /mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4 уже есть — стоп"
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft"
test -d "$FILM/saves/greenfield-film" && echo "мир фильма есть" || echo "мира фильма нет"
test -e "$FILM/saves/greenfield-gate" && echo "копия greenfield-gate уже есть — стоп"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4-world" && echo "nuke-gate4-world уже есть — стоп"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4-film-state" && echo "nuke-gate4-film-state уже есть — стоп"
ls "$FILM/mods"/airstrike-*.jar; grep -E '^renderDistance:' "$FILM/options.txt"
du -sh "$FILM/saves/greenfield-film"; df -h /mnt/data/projects/airstrike/mod/run    # свободно ≥ размер мира + 5 ГБ
```

## 2. Подготовка: worktree, копия мира, сохранить состояние инстанса фильма
`mod/run/` в .gitignore, в свежем worktree его нет — создаётся сразу после `worktree add`.
Состояние инстанса фильма (jar мода и строка `renderDistance`) сохраняется в `claude-work/nuke-gate4-film-state`
и возвращается в шаге 6.
```sh
cd /mnt/data/projects/airstrike && git fetch origin claude/project-thread-39w82y && \
git worktree add "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4" @SHA@ && \
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4" && cd "${W:?}" && mkdir -p "$W/mod/run" && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4-film-state" && \
mkdir -p "$S/mods" && cp -a "$FILM/mods"/airstrike-*.jar "$S/mods/" && grep -E '^renderDistance:' "$FILM/options.txt" > "$S/renderDistance.txt" && \
cp -a "$FILM/saves/greenfield-film" "$FILM/saves/greenfield-gate" && \
T="$FILM/saves/greenfield-gate/serverconfig/airstrike-server.toml" && C="$FILM/config/airstrike-server.toml" && \
if [ -f "$T" ]; then \
  sed -i -E 's/^(\s*flight_time\s*=).*/\1 1800/; s/^(\s*destruction_ms_per_tick\s*=).*/\1 30/' "$T" && echo "конфиг копии мира: $T" && \
  grep -E '^\s*(flight_time|destruction_ms_per_tick)\s*=' "$T"; \
elif grep -Eq '^\s*flight_time\s*=\s*1800\s*$' "$C" 2>/dev/null && grep -Eq '^\s*destruction_ms_per_tick\s*=\s*30\s*$' "$C" 2>/dev/null; then \
  echo "в копии мира конфига нет — сервер берёт $C:" && grep -E '^\s*(flight_time|destruction_ms_per_tick)\s*=' "$C"; \
else \
  echo "в копии мира конфига нет, а в $C не flight_time 1800 и destruction_ms_per_tick 30 — стоп"; \
  grep -E '^\s*(flight_time|destruction_ms_per_tick)\s*=' "$C"; false; \
fi && \
sed -i -E 's/^renderDistance:.*/renderDistance:24/' "$FILM/options.txt" && \
git log --oneline -1 && ls "$S/mods" && cat "$S/renderDistance.txt" && grep -E '^renderDistance:' "$FILM/options.txt"
```
Строка «стоп» — прогон не запускать, сразу шаг 6 (он уберёт копию мира в сторону и вернёт инстанс фильма), сообщить
координатору. Серверный конфиг инстанса фильма — `config/airstrike-server.toml` (в gate3 проверено: 1800 и 30), не
`defaultconfigs/`.
Сборка jar сценария — отдельно, до задачи (в фоне, тайм-аут вызова не меньше 25 мин); код не 0 — сообщить:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```
(prod_client.py внутри задачи ещё раз зовёт `scenarioJar` — после этого шага это проверка «всё собрано», секунды.)

## 3. Прогон (один; в фоне, тайм-аут вызова 45 мин)
Высоты камер — готовые числа: земля Greenfield у эпицентра и на круге — около 64–70 (эпицентр на 69, море 63).
Ближняя камера — y 130 (≈ 60 над землёй), камеры руин — y 220 (≈ 150 над землёй; круг r = 320 вокруг эпицентра,
взгляд на 30° вниз, как `ruins_orbit` трейлера: берега, вода, рельеф). Камера в спектаторе, стены её не держат;
если кадр всё же упрётся в постройку — это видно на кадре, прогон не повторять, написать об этом в строке кадра.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
echo "начало: $(date -u +%T) UTC" && timeout -k 60 45m tools/laptop_job.sh nuke-gate4 -- python3 tools/prod_client.py commands --no-copy --dir "/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" --world greenfield-gate --seconds 2400 \
  --prop "airstrike.commands=hud:off;gamemode spectator;tp @s -863.5 130 -496.4 -90 -10;wait:600;airstrike nuke at 136.5 69 -495.5 15 air;wait:nuke;wait:10;shot:flash;wait:40;shot:fireball;wait:30;shot:wave;tp @s -3803 120 -499 -90 -5;wait:260;shot:far_dh;tp @s -3803 120 -499 -90 -30;wait:1020;shot:glow70;tp @s -863.5 130 -496.4 -90 -10;wait:4380;time set 6000;tp @s -863.5 130 -496.4 -90 10;shot:close_ruins;tp @s 456.5 220 -495.5 90 30;wait:160;shot:ruins_e;tp @s 136.5 220 -175.5 180 30;wait:160;shot:ruins_s;tp @s -183.5 220 -495.5 -90 30;wait:160;shot:ruins_w;tp @s 136.5 220 -815.5 0 30;wait:160;shot:ruins_n;wait:200"; code=$?; echo "код $code, конец: $(date -u +%T) UTC"; \
case "$code" in 124|137) systemctl --user stop 'airstrike-job-nuke-gate4-*';; esac; true
```
Перед `close_ruins` — `time set 6000` (полдень: кадры руин — днём, при любом времени мира) и поворот ближней камеры на
10° вниз; каждая команда ждёт свои 40 тиков. В Minecraft положительный наклон — вниз: камеры руин +30 (в прогоне gate4
на 7990578 −30 смотрели в небо, близкая −10 — чуть вверх). Время кадров — от прихода к клиенту пакета подрыва (`wait:nuke`: прошлый прогон считал от пуска, и вспышка вышла
на 0,5 с раньше подрыва; строка `SCENARIO commands: подрыв пришёл` в логе). После команды шаг ждёт 40 тиков, после
снимка — 20, `wait:N` прибавляется. flash +0,5 с (ближняя камера, 1 км), fireball +3,5 с, wave +6 с (стена пыли идёт
к камере), far_dh +22 с (4 км, город в LOD DH), glow70 +76 с (4 км, взгляд вверх), close_ruins **+5 мин** (снова 1 км:
пыль осела: по замыслу пелена у камеры гаснет за 5–9 с, шлейф за фронтом и бурая мгла — e-кратно за 45 с, стена
пыли — за 150 с после остановки, юбка и раструб ножки гриба у земли — e-кратно за 90 с после 37 с (до этого PR они
стояли 15 минут: на 6070de4 кадры руин были сплошной бурой пылью); сам гриб висит до ~25 мин), ruins_e/s/w/n — +5 мин 10 с … +5 мин 43 с. Всего ~8 мин
игры после подрыва (полёт МБР — 1800 тиков), плюс загрузка сборки и мира; `--seconds 2400` и `timeout 45m` — пределы.
Конец — `SCENARIO done` в `$FILM/logs/latest.log`, и клиент выходит сам: **код 0**. Код 124 или 143 — предел: в прошлый
раз (6070de4) так выглядело зависание выхода после ядерки. К этому коду процесса уже нет (его гасят prod_client и
`systemctl stop`), поэтому `jstack` снимает наблюдатель (шаг 4) — пока процесс жив.

## 4. Наблюдатель
Прогон жив: в `/mnt/data/projects/airstrike/mod/run/film/instance/minecraft/logs/latest.log` растёт лог, появляются
строки `SCENARIO commands`, в конце `SCENARIO done`. Смерть прогона — координатору
в течение 5 мин, с причиной (последние 40 строк лога, `crash-reports/`).
Java прогона ищется по рабочему каталогу (`/proc/<pid>/cwd` — каталог инстанса фильма: prod_client запускает её
`cd "<--dir>" && exec java @launch.args`): имени мира в её командной строке нет (в gate3 `grep greenfield-gate` не нашёл процесс). Зависание выхода: если `SCENARIO done` в логе уже есть, а через 3 мин после него java прогона ещё жива — один раз
(только чтение, процесс не трогается):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4" && cd "${W:?}" && mkdir -p mod/run && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && \
R=$(realpath "$FILM") && PID=$(for p in $(pgrep -x java); do [ "$(readlink "/proc/$p/cwd")" = "$R" ] && echo "$p"; done | head -1) && [ -n "$PID" ] && echo "java прогона: $PID" && \
timeout 60 "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jstack" "$PID" > mod/run/gate-jstack.txt; \
echo "код $?"; wc -lc mod/run/gate-jstack.txt
```
Координатору — первые 200 строк потока «Server thread» из `gate-jstack.txt` (`grep -A200 '"Server thread"'`).
**Шаг 6 выполняется всегда** — и после смерти прогона или упавшей сборки: он не зависит от прогона и безопасен
при повторе.

## 5. Кадры и выжимка
Кадры — `$FILM/screenshots/<имя>_<тик>.png` (у каждого имени — самый новый файл). JPEG q85 — в `$W/mod/run/gate-frames/`:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4" && cd "${W:?}" && mkdir -p mod/run/gate-frames && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && \
for n in flash fireball wave close_ruins far_dh glow70 ruins_e ruins_s ruins_w ruins_n; do \
  f=$(ls -t "$FILM/screenshots/${n}"_[0-9]*.png 2>/dev/null | head -1); \
  if [ -n "$f" ]; then ffmpeg -loglevel error -y -i "$f" -q:v 3 "mod/run/gate-frames/$n.jpg" && echo "$n ← $(basename "$f")"; else echo "$n: кадра нет"; fi; \
done; ls -l mod/run/gate-frames
```
Лог и куча:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4" && cd "${W:?}" && FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && \
python3 tools/logscan.py "$FILM/logs/latest.log" --all > mod/run/gate-logscan.txt; wc -lc mod/run/gate-logscan.txt; \
grep -E 'Руины удара №|Подрыв №|Руины подрыва №|зона за волной|дальние кольца|Руины: |фоновый план чанка|POI data mismatch|Ядерный тик|Can.t keep up|дальше руины заранее не строятся|Distant Horizons|LevelChunkEditsMixin|Блэкаут|SCENARIO|Работа мода за 30 с|Stopping server|All dimensions are saved' "$FILM/logs/latest.log" | grep -v 'POI data mismatch' | cut -c1-400 > mod/run/gate-lines.txt; \
echo "POI data mismatch: $(grep -c 'POI data mismatch' "$FILM/logs/latest.log")" >> mod/run/gate-lines.txt; wc -lc mod/run/gate-lines.txt; \
L="$FILM/logs/latest.log" && hms() { grep -oE '[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}' | head -1; } && \
{ echo "выход: SCENARIO done $(grep -m1 'SCENARIO done' "$L" | hms), Stopping server $(grep -m1 'Stopping server' "$L" | hms), All dimensions are saved $(grep -m1 'All dimensions are saved' "$L" | hms), последняя строка $(tail -1 "$L" | hms)"; } >> mod/run/gate-lines.txt; tail -1 mod/run/gate-lines.txt; \
grep -E 'Pause (Young|Old|Full)|Garbage Collection|Major Collection|Minor Collection|->' "$FILM/logs/gc.log" | tail -400 > mod/run/gate-gc.txt; wc -lc mod/run/gate-gc.txt; \
grep Pause "$FILM/logs/gc.log" | awk '{ms=$NF; sub(/ms$/,"",ms); if (ms+0>500) print}' > mod/run/gate-gc-pauses.txt; echo "пауз GC > 500 мс: $(wc -l < mod/run/gate-gc-pauses.txt)"
```

## 6. Вернуть инстанс фильма (два блока, всегда, в любом исходе)
Jar мода и `renderDistance` — как до прогона; копия мира — в сторону (не удалять); worktree остаётся (уборка — только
по слову Артёма в треде «Ноутбук»).
Выжимки — каждый файл, только если он есть (без `&&` между ними):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4" && cd "${W:?}" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4-film-state" && mkdir -p "$S" && \
for f in gate-frames gate-logscan.txt gate-lines.txt gate-gc.txt gate-gc-pauses.txt gate-jstack.txt; do [ -e "$W/mod/run/$f" ] && cp -a "$W/mod/run/$f" "$S/" && echo "скопировано $f"; done; ls "$S"
```
Откат инстанса фильма — не зависит от прогона, повтор безвреден (jar, которые лежат в `mods/` сейчас, — в
`$S/removed/`, jar фильма — обратно из `$S/mods/`; если шаг 2 не успел сохранить jar — `mods/` не трогается):
```sh
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4-film-state" && G="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4-world" && \
if ls "$S/mods"/airstrike-*.jar >/dev/null 2>&1; then mkdir -p "$S/removed" && for j in "$FILM/mods"/airstrike-*.jar; do [ -e "$j" ] && mv "$j" "$S/removed/"; done; \
cp -a "$S/mods"/airstrike-*.jar "$FILM/mods/"; else echo "jar фильма не сохранён — mods/ не трогаю"; fi; RD=$(cat "$S/renderDistance.txt") && [ -n "$RD" ] && sed -i -E "s/^renderDistance:.*/${RD}/" "$FILM/options.txt"; \
[ -d "$FILM/saves/greenfield-gate" ] && [ ! -e "$G" ] && mv "$FILM/saves/greenfield-gate" "$G"; \
ls "$FILM/mods"/airstrike-*.jar; grep -E '^renderDistance:' "$FILM/options.txt"; ls "$FILM/saves"; ls -d "$G"
```
Должно быть: в `mods/` — тот же jar, что в шаге 1; `renderDistance` — как в шаге 1; в `saves/` нет `greenfield-gate`.
Выжимки и кадры — в `/mnt/data/projects/airstrike/mod/run/claude-work/nuke-gate4-film-state/` (и в worktree).

## 7. Сравнение с видео Silo — только на ноутбуке
Десять кадров (`nuke-gate4-film-state/gate-frames/*.jpg`) сравнить с видео Silo **здесь, на ноутбуке**: вспышка, шар,
стена пыли, руины, свечение гриба — похоже / чем отличается. Видео Silo и любые кадры из него **никуда не уходят**
(ни в /mnt/project-files, ни в сообщения) — наружу только текст сравнения, по строке на кадр.

## Что прислать координатору
Текстом (≈ до 40 КБ; больше 60 КБ — двумя сообщениями, не урезая):
0. Код выхода прогона и время начала и конца (шаг 3), строку `выход: SCENARIO done …, Stopping server …, All dimensions
   are saved …, последняя строка …` (из `gate-lines.txt`, последняя) и строки `Руины: фоновые потоки остановлены …` /
   `… не остановились …`.
1. Из `gate-lines.txt` — дословно: все строки `Руины подрыва №M (…): отставание …` и `Руины подрыва №M: в памяти при
   подрыве …` (они пишутся дважды: с меткой «(встали руины всех чанков, бывших в памяти при подрыве)» и с меткой «(всё)»),
   `… ушло игроку до руин: …`, `… окна с диска — …`, `… загружены после подрыва …`, `Подрыв №M: зона за волной — …`;
   `Руины удара №N готовы: планов по чанкам в памяти …` (до подрыва) или `Подрыв №M: руины заранее — …`; строки `Блэкаут №…`;
   строки ScarQueue `Руины подрыва №M: по готовому плану … отставание от волны до T тиков (у игроков до …) …`,
   `… план и подмена на месте — в среднем …`, `… не дождались N чанков …`, `Руины: подмены по частям …`,
   `Руины: дольше всего через мир — чанк …`; все `Руины: фоновых потоков …` (сколько выбрано и каждое снижение/возврат
   по тику без полос мода); все `Подрыв №M: дальние кольца: …`; строки `… фоновый план чанка … брошен` или «нет»;
   число `POI data mismatch`.
2. Все строки `Работа мода за 30 с: …` дословно (тик сервера, полосы мода по отдельности, остальное). Самый долгий тик
   сервера за 60 с до подрыва и за 60 с после (раздел «Отставание сервера» из `gate-logscan.txt`); все `Ядерный тик … мс`,
   `Can't keep up`.
3. Куча: пик из `gate-gc.txt` (из 8 ГБ), были ли Full GC; строки `… дальше руины заранее не строятся`,
   `Distant Horizons …`, `… счётчик изменений чанка (LevelChunkEditsMixin) не встал …` — дословно или «нет».
4. logscan: ошибки/исключения/`Mixin`/`AccessTransformer`/`DUMMY` — или «ноль».
5. `gate-gc-pauses.txt` (паузы GC дольше 500 мс) целиком; вывод двух блоков шага 6.
6. По строке о каждом из 10 кадров по критериям ниже и строку сравнения с Silo (шаг 7).
Кадры — только наши, 10 JPEG из `gate-frames` (не из видео Silo): загрузить в `/mnt/project-files/nuke/gate4/` (SendUserFile).

## Проходит, если
Критерии — координатор, 30.09 20:48 UTC.
- **А:** из строки `Руины подрыва №M: в памяти при подрыве N чанков, из них с загруженными соседями M; по готовому плану
  K (X % от всех, Y % из чанков с соседями); на экране игрока без соседей Z` с меткой «(всё)» у парной строки
  отставания: **Y ≥ 90 %** (Y — доля по готовому плану среди тех же M чанков, что в знаменателе; не больше 100 %).
  X и Z прислать тоже. Отставание «у игроков» (строка с меткой «(всё)») **≤ 40 тиков**; план устарел — единицы;
- **Б:** `ушло игроку до руин: 0`;
- **В:** последняя строка `дальние кольца: готово к волне X из Y` — **X/Y ≥ 90 %**; прислать и «позже волны … в среднем /
  самое большее», «плана ещё нет», «не с диска»;
- **выход:** прогон кончается сам с **кодом 0** (не 124/143), строка `Руины: фоновые потоки остановлены`; время от
  `SCENARIO done` до `All dimensions are saved` — отдельной строкой (строка «выход:» шага 5);
- самый долгий тик после подрыва **≤ 250 мс** (кроме тика подрыва и 5 с после `tp` на дальнюю камеру — отдельно);
- куча: пик заметно ниже 8 ГБ, **без Full GC**, пауз GC дольше 500 мс нет; нет строк страховки памяти, DH и счётчика
  изменений;
- `POI data mismatch` — **0**; logscan — без новых ошибок;
- строки `Работа мода за 30 с` есть (раз в 30 с, пока полосы мода работают) — для сведения, не критерий;
- только для сведения: руины после волны у загруженных после подрыва — по плану на месте и по готовому плану (среднее
  и наибольшее, тики), «зона за волной: готово X из Y», «окна с диска» (отдельно — сколько соседей нет на диске целыми), число потоков и снижений;
- flash: кадр залит светом; fireball: шар; wave: стена пыли и огня идёт к камере, город за ней уже в руинах,
  перед ней ещё целый;
- far_dh (для сведения): LOD Distant Horizons дальних колец меняется, только когда их чанки загружены за волной, — написать,
  что видно; glow70: гриб светится (накал видно), не тёмный дым;
- close_ruins (+5 мин, пыль осела, **день**) и ruins_e/s/w/n (день): у камеры крыши и стёкла побиты (1 км от эпицентра
  по земле — 4,4 psi падающего, на стенах к взрыву 8,6–10 psi отражённого: стекло, листва, лёгкое и дерево в один блок
  ломаются, кирпич и бетон — порог 12 psi — стоят; стоящий у ближней камеры город с выбитыми окнами — по модели, не
  отставание), башни у эпицентра рухнули (и на берегу), ничего не висит в воздухе (ни кусков этажей, ни цепочек
  одиночных блоков), скалы, берега и вода целы (нет стен воды, сухих ям, рвов), нет зазубрин по границам чанков.
