# Ноутбук: ядерка со всей сборкой — диагностика зоны за волной (одна задача)

Диагностический прогон после провала `nuke-gate4` (30.09, 23:40 UTC: руины зоны за волной не успевали, отставание
у игроков 6962 тиков, «ушло игроку до руин» 1951, тик 771 мс с полосами мода 0,1 мс). Тот же сценарий, что в gate4,
плюс свойство `airstrike.nukeDiag=true` (строки «ДИАГ …» раз в 600 тиков: квадраты зоны по состояниям, на чём стоял набор
квадратов, сколько квадрат грузится и сколько стоит готовым, что держит готовый квадрат; причины ожидания очереди руин
и задержка чтений с диска; раз в 200 тиков — очередь потока ввода-вывода чанков (записи, ждущие диска, и задачи) и куча; стеки потока сервера у тиков дольше 250 мс) и запись JFR (файл остаётся на ноутбуке, наружу —
только выжимка). Поведение мода с диагностикой то же. Папки и задача — `nuke-diag`, следы прошлых (`nuke-gate*`)
не трогаются. Сторожа температуры нет (Артём, 16:47 UTC). Исправлено против gate4: камеры руин смотрят вниз (+30,
близкая +10), строка «выход:» берёт время из строки лога с датой.

Тред «Ядерный взрыв: ударная волна». Ветка `claude/project-thread-39w82y`, коммит **@SHA@** — полный SHA из сообщения
координатора подставить во все блоки ниже вместо `@SHA@` (одна замена, до шага 2; строки с `@SHA@` после неё быть не
должно).

Условия: Артём не играет; одна тяжёлая задача на машине; только `tools/laptop_job.sh`; клиент только через
`tools/nested_kwin.sh` (это делает prod_client.py); большие файлы — в `mod/run/`. Инстанс Артёма, его миры и настройки
не трогать. Мир фильма (`greenfield-film`) не трогать: удар идёт по **копии** `greenfield-gate`. Инстанс фильма
(`mod/run/film/instance/minecraft`) после прогона возвращается как был: `options.txt` и jar мода в `mods/` — шаги 2 и 6.

**Каждый блок ниже — одним вызовом, как написан**: состояние оболочки между вызовами не сохраняется. Блоки с шага 2
начинаются с `W=… && cd "${W:?}"`; блок 1 работает до появления папки и пишет полные пути. Папка worktree — ровно
`/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag` (новая; если она уже есть — остановиться и сообщить
координатору, ничего не удалять и не перезаписывать).

**Остановить задачу — только** `systemctl --user stop 'airstrike-job-nuke-diag-*'`. Никаких `pkill`/`killall`/`kill`
по имени, никакого `./gradlew --stop`, никаких `-f`/`--force`.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп» или «нет» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && echo "папка /mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag уже есть — стоп"
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft"
test -d "$FILM/saves/greenfield-film" && echo "мир фильма есть" || echo "мира фильма нет"
test -e "$FILM/saves/greenfield-gate" && echo "копия greenfield-gate уже есть — стоп"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag-world" && echo "nuke-diag-world уже есть — стоп"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag-film-state" && echo "nuke-diag-film-state уже есть — стоп"
ls "$FILM/mods"/airstrike-*.jar; grep -E '^renderDistance:' "$FILM/options.txt"
du -sh "$FILM/saves/greenfield-film"; df -h /mnt/data/projects/airstrike/mod/run    # свободно ≥ размер мира + 10 ГБ (мир + JFR до 4 ГБ)
test -x "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jfr" && echo "jfr есть" || echo "jfr нет — стоп"
```

## 2. Подготовка: worktree, копия мира, сохранить состояние инстанса фильма
`mod/run/` в .gitignore, в свежем worktree его нет — создаётся сразу после `worktree add`.
Состояние инстанса фильма (jar мода и строка `renderDistance`) сохраняется в `claude-work/nuke-diag-film-state`
и возвращается в шаге 6.
```sh
cd /mnt/data/projects/airstrike && git fetch origin claude/project-thread-39w82y && \
git worktree add "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" @SHA@ && \
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && cd "${W:?}" && mkdir -p "$W/mod/run" && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag-film-state" && \
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
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```
(prod_client.py внутри задачи ещё раз зовёт `scenarioJar` — после этого шага это проверка «всё собрано», секунды.)

## 3. Прогон (один; в фоне, тайм-аут вызова 45 мин)
JFR пишется в `$W/mod/run/diag.jfr` (не больше 4 ГБ; хранилище JFR — `$W/mod/run/jfr-repo`, не `/tmp`: там tmpfs в ОЗУ).
Высоты камер — готовые числа: земля Greenfield у эпицентра и на круге — около 64–70 (эпицентр на 69, море 63).
Ближняя камера — y 130 (≈ 60 над землёй), камеры руин — y 220 (≈ 150 над землёй; круг r = 320 вокруг эпицентра,
взгляд на 30° вниз, как `ruins_orbit` трейлера: берега, вода, рельеф). Камера в спектаторе, стены её не держат;
если кадр всё же упрётся в постройку — это видно на кадре, прогон не повторять, написать об этом в строке кадра.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
mkdir -p "$W/mod/run/jfr-repo" && echo "начало: $(date -u +%T) UTC" && timeout -k 60 45m tools/laptop_job.sh nuke-diag -- python3 tools/prod_client.py commands --no-copy --dir "/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" --world greenfield-gate --seconds 2400 \
  --prop airstrike.nukeDiag=true --jvm="-XX:FlightRecorderOptions=repository=$W/mod/run/jfr-repo" --jvm="-XX:StartFlightRecording=filename=$W/mod/run/diag.jfr,settings=profile,maxsize=4g,dumponexit=true" \
  --prop "airstrike.commands=hud:off;gamemode spectator;tp @s -863.5 130 -496.4 -90 -10;wait:600;airstrike nuke at 136.5 69 -495.5 15 air;wait:nuke;wait:10;shot:flash;wait:40;shot:fireball;wait:30;shot:wave;tp @s -3803 120 -499 -90 -5;wait:260;shot:far_dh;tp @s -3803 120 -499 -90 -30;wait:1020;shot:glow70;tp @s -863.5 130 -496.4 -90 -10;wait:4380;time set 6000;tp @s -863.5 130 -496.4 -90 10;shot:close_ruins;tp @s 456.5 220 -495.5 90 30;wait:160;shot:ruins_e;tp @s 136.5 220 -175.5 180 30;wait:160;shot:ruins_s;tp @s -183.5 220 -495.5 -90 30;wait:160;shot:ruins_w;tp @s 136.5 220 -815.5 0 30;wait:160;shot:ruins_n;wait:200"; code=$?; echo "код $code, конец: $(date -u +%T) UTC"; \
case "$code" in 124|137) systemctl --user stop 'airstrike-job-nuke-diag-*';; esac; true
```
Перед `close_ruins` — `time set 6000` (полдень: кадры руин — днём, при любом времени мира) и поворот ближней камеры на
10° вниз (в gate4 −10 смотрело вверх); каждая команда ждёт свои 40 тиков. В Minecraft положительный наклон — вниз:
камеры руин +30 (в gate4 −30 смотрели в небо). Время кадров — от прихода к клиенту пакета подрыва (`wait:nuke`: прошлый прогон считал от пуска, и вспышка вышла
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
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && cd "${W:?}" && mkdir -p mod/run && \
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
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && cd "${W:?}" && mkdir -p mod/run/gate-frames && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && \
for n in flash fireball wave close_ruins far_dh glow70 ruins_e ruins_s ruins_w ruins_n; do \
  f=$(ls -t "$FILM/screenshots/${n}"_[0-9]*.png 2>/dev/null | head -1); \
  if [ -n "$f" ]; then ffmpeg -loglevel error -y -i "$f" -q:v 3 "mod/run/gate-frames/$n.jpg" && echo "$n ← $(basename "$f")"; else echo "$n: кадра нет"; fi; \
done; ls -l mod/run/gate-frames
```
Лог и куча:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && cd "${W:?}" && FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && \
python3 tools/logscan.py "$FILM/logs/latest.log" --all > mod/run/gate-logscan.txt; wc -lc mod/run/gate-logscan.txt; \
grep -E 'Руины удара №|Подрыв №|Руины подрыва №|зона за волной|дальние кольца|Руины: |фоновый план чанка|POI data mismatch|Ядерный тик|Can.t keep up|дальше руины заранее не строятся|Distant Horizons|LevelChunkEditsMixin|Блэкаут|SCENARIO|Работа мода за 30 с|Stopping server|All dimensions are saved' "$FILM/logs/latest.log" | grep -v 'POI data mismatch' | grep -v 'ДИАГ' | cut -c1-400 > mod/run/gate-lines.txt; \
echo "POI data mismatch: $(grep -c 'POI data mismatch' "$FILM/logs/latest.log")" >> mod/run/gate-lines.txt; wc -lc mod/run/gate-lines.txt; \
L="$FILM/logs/latest.log" && hms() { grep -oE '[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}' | head -1; } && \
{ echo "выход: SCENARIO done $(grep -m1 'SCENARIO done' "$L" | hms), Stopping server $(grep -m1 'Stopping server' "$L" | hms), All dimensions are saved $(grep -m1 'All dimensions are saved' "$L" | hms), последняя строка $(tail -1 "$L" | hms)"; } >> mod/run/gate-lines.txt; tail -1 mod/run/gate-lines.txt; \
grep -E 'Pause (Young|Old|Full)|Garbage Collection|Major Collection|Minor Collection|->' "$FILM/logs/gc.log" | tail -400 > mod/run/gate-gc.txt; wc -lc mod/run/gate-gc.txt; \
grep Pause "$FILM/logs/gc.log" | awk '{ms=$NF; sub(/ms$/,"",ms); if (ms+0>500) print}' > mod/run/gate-gc-pauses.txt; echo "пауз GC > 500 мс: $(wc -l < mod/run/gate-gc-pauses.txt)"
```

Диагностика (строки «ДИАГ» — одним файлом целиком, для координатора — выборка ≤ 25 КБ; стеки долгих тиков — строка
«ДИАГ долгий тик» и её строки `at` следом):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && cd "${W:?}" && FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && L="$FILM/logs/latest.log" && \
grep 'ДИАГ' "$L" | grep -v 'ДИАГ долгий тик' > mod/run/diag-all.txt; wc -lc mod/run/diag-all.txt; \
{ grep -E 'ДИАГ (зона|очередь|ввод-вывод)' mod/run/diag-all.txt | cut -c1-700; echo "-- квадраты: первые, средние и последние отчёты"; \
  grep 'ДИАГ квадрат' mod/run/diag-all.txt | awk '{print NR": "$0}' > mod/run/diag-tiles.txt; N=$(wc -l < mod/run/diag-tiles.txt); \
  { head -12 mod/run/diag-tiles.txt; sed -n "$((N/2-5)),$((N/2+6))p" mod/run/diag-tiles.txt; tail -12 mod/run/diag-tiles.txt; } | sort -un -t: -k1,1 | cut -c1-400; } > mod/run/diag-lines.txt; \
wc -lc mod/run/diag-lines.txt; \
awk '/ДИАГ долгий тик/{n++; keep=(n<=6)} keep && (/ДИАГ долгий тик/ || /^[[:space:]]/)' "$L" | cut -c1-220 | head -c 12000 > mod/run/diag-stacks.txt; \
echo "долгих тиков (> 250 мс): $(grep -c 'ДИАГ долгий тик' "$L")"; grep -E 'ДИАГ долгий тик|Saving sub-levels|Saving chunks for level|Saving the game|Autosav' "$L" | cut -c1-120 > mod/run/diag-long-ticks.txt; wc -lc mod/run/diag-stacks.txt mod/run/diag-long-ticks.txt
```
В `diag-stacks.txt` — первые шесть долгих тиков; если среди них нет тика после подрыва дольше 500 мс, а в
`diag-long-ticks.txt` он есть, — его блок (номер из этого файла, `n==K`) вместо последнего:
`awk -v K=… '/ДИАГ долгий тик/{n++; keep=(n==K)} keep && (/ДИАГ долгий тик/ || /^[[:space:]]/)' "$L" | cut -c1-220 | head -c 6000`.
JFR — файл остаётся на ноутбуке (`$W/mod/run/diag.jfr`), наружу только выжимка (в фоне, тайм-аут вызова 20 мин):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && cd "${W:?}" && J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jfr" && \
ls -l mod/run/diag.jfr && { timeout -k 30 5m "$J" summary mod/run/diag.jfr | head -80; } > mod/run/diag-jfr-summary.txt; \
{ timeout -k 30 10m "$J" view --width 200 hot-methods mod/run/diag.jfr | head -40; timeout -k 30 5m "$J" view --width 200 gc-pauses mod/run/diag.jfr | head -20; \
  timeout -k 30 5m "$J" view --width 200 file-reads-by-path mod/run/diag.jfr | head -20; } > mod/run/diag-jfr-views.txt 2>&1; \
wc -lc mod/run/diag-jfr-summary.txt mod/run/diag-jfr-views.txt
```

## 6. Вернуть инстанс фильма (два блока, всегда, в любом исходе)
Jar мода и `renderDistance` — как до прогона; копия мира — в сторону (не удалять); worktree остаётся (уборка — только
по слову Артёма в треде «Ноутбук»).
Выжимки — каждый файл, только если он есть (без `&&` между ними):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag" && cd "${W:?}" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag-film-state" && mkdir -p "$S" && \
for f in gate-frames gate-logscan.txt gate-lines.txt gate-gc.txt gate-gc-pauses.txt gate-jstack.txt diag-all.txt diag-lines.txt diag-stacks.txt diag-long-ticks.txt diag-jfr-summary.txt diag-jfr-views.txt; do [ -e "$W/mod/run/$f" ] && cp -a "$W/mod/run/$f" "$S/" && echo "скопировано $f"; done; ls "$S"
```
Откат инстанса фильма — не зависит от прогона, повтор безвреден (jar, которые лежат в `mods/` сейчас, — в
`$S/removed/`, jar фильма — обратно из `$S/mods/`; если шаг 2 не успел сохранить jar — `mods/` не трогается):
```sh
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag-film-state" && G="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag-world" && \
if ls "$S/mods"/airstrike-*.jar >/dev/null 2>&1; then mkdir -p "$S/removed" && for j in "$FILM/mods"/airstrike-*.jar; do [ -e "$j" ] && mv "$j" "$S/removed/"; done; \
cp -a "$S/mods"/airstrike-*.jar "$FILM/mods/"; else echo "jar фильма не сохранён — mods/ не трогаю"; fi; RD=$(cat "$S/renderDistance.txt") && [ -n "$RD" ] && sed -i -E "s/^renderDistance:.*/${RD}/" "$FILM/options.txt"; \
[ -d "$FILM/saves/greenfield-gate" ] && [ ! -e "$G" ] && mv "$FILM/saves/greenfield-gate" "$G"; \
ls "$FILM/mods"/airstrike-*.jar; grep -E '^renderDistance:' "$FILM/options.txt"; ls "$FILM/saves"; ls -d "$G"
```
Должно быть: в `mods/` — тот же jar, что в шаге 1; `renderDistance` — как в шаге 1; в `saves/` нет `greenfield-gate`.
Выжимки и кадры — в `/mnt/data/projects/airstrike/mod/run/claude-work/nuke-diag-film-state/` (и в worktree).

## Что прислать координатору
Текстом, всего ≤ 40 КБ (кадры — отдельно; JFR, `diag-all.txt` и полный лог — не присылать, остаются на ноутбуке):
0. Код выхода прогона, время начала и конца (шаг 3), строку «выход:» (последняя в `gate-lines.txt`).
1. `diag-lines.txt` целиком (≤ 25 КБ; если больше — строки «ДИАГ зона», «ДИАГ очередь» и «ДИАГ ввод-вывод» целиком, квадраты — сколько влезет).
2. `diag-long-ticks.txt` (долгие тики вперемешку со строками автосохранения, ≤ 4 КБ, иначе первые 40 строк и все
   тики дольше 500 мс) и `diag-stacks.txt` (≤ 12 КБ). Тик > 200 мс сразу после «Saving sub-levels» — автосохранение
   (базовая линия 68986c0: 213–259 мс), его отметить отдельно от остальных.
3. `diag-jfr-summary.txt` — строки с числом событий `jdk.ExecutionSample`, `jdk.GarbageCollection`, продолжительность
   записи; `diag-jfr-views.txt` целиком (≤ 6 КБ).
4. Из `gate-lines.txt` дословно: `Руины подрыва №M (…): отставание …`, `… ушло игроку до руин …`, `… не дождались …`,
   все `Подрыв №M: зона за волной — …`, все `дальние кольца`, все `Работа мода за 30 с`.
5. Куча: пик из `gate-gc.txt`, были ли Full GC во время игры.
6. По строке о кадрах close_ruins и ruins_e/s/w/n: камера смотрит вниз на город (да/нет), что видно. Кадры (10 JPEG из
   `gate-frames`) — в `/mnt/project-files/nuke/diag/` (SendUserFile).

Критериев прохождения нет: это сбор данных. Прогон годен, если он кончился сам (код 0), в логе есть строки
«ДИАГ зона» после подрыва и `diag.jfr` записан.
