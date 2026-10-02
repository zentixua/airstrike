# Ноутбук: руины ядерки вдали в Distant Horizons и догенерация зоны (одна задача)

Тред «Ядерка: потерянные руины в 2.4.1», PR #181. Папки и задача — `nuke-far-ruins`, следы прошлых прогонов
(`nuke-gate*`, `nuke-diag`) не трогаются.

Коммит **@SHA@** — полный SHA из сообщения координатора подставить во все блоки ниже вместо `@SHA@` (одна замена,
до шага 2; строки с `@SHA@` после неё быть не должно). Коммит берётся по SHA: ветку PR после слияния удаляют, а коммит
остаётся в `main` или в `pull/181/head`.

Что меряем (в игре Артёма 2.4.1 руины вдали шли в DH через 15–20 мин или не шли совсем, «не дождались 453»):
1. **Удар 1** — 15 кт по Greenfield (как в gate8: вся тяжёлая зона целая на диске). Камера в 2,5 км, город — в LOD DH.
   Через сколько после подрыва все запросы руин вдали прошли (`в очереди … (руины 0)` в строке «LOD Distant Horizons
   вдали»), и нет ли «не дождались» в зоне за волной.
2. **Удар 2** — 1 кт в несгенерированном месте (шаг 2 выбирает его по файлам регионов копии мира). Почти вся тяжёлая
   зона не целая на диске: её догенерирует `FarZone` («не целые на диске … (в мир: квадратов …)»). Сколько квадратов,
   сколько тиков генерация квадрата и когда зона кончилась.
Блэкаут обоих ударов идёт как в игре (радиус 4096): запросы света стоят в той же очереди, что и руины.

Условия: Артём не играет; одна тяжёлая задача на машине; только `tools/laptop_job.sh`; клиент только через
`tools/nested_kwin.sh` (это делает prod_client.py); большие файлы — в `mod/run/`. Инстанс Артёма, его миры и настройки
не трогать. Мир фильма (`greenfield-film`) не трогать: удары идут по **копии** `greenfield-farlod`. Инстанс фильма
(`mod/run/film/instance/minecraft`) после прогона возвращается как был: `options.txt` и jar мода в `mods/` — шаги 2 и 6.

**Каждый блок ниже — одним вызовом, как написан**: состояние оболочки между вызовами не сохраняется. Блоки с шага 2
начинаются с `W=… && cd "${W:?}"`; блок 1 работает до появления папки и пишет полные пути. Папка worktree — ровно
`/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins` (новая; если она уже есть — остановиться и сообщить
координатору, ничего не удалять и не перезаписывать).

**Остановить задачу — только** `systemctl --user stop 'airstrike-job-nuke-far-ruins-*'`. Никаких `pkill`/`killall`/`kill`
по имени, никакого `./gradlew --stop`, никаких `-f`/`--force`.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп» или «нет» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" && echo "папка /mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins уже есть — стоп"
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft"
test -d "$FILM/saves/greenfield-film" && echo "мир фильма есть" || echo "мира фильма нет"
test -e "$FILM/saves/greenfield-farlod" && echo "копия greenfield-farlod уже есть — стоп"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins-world" && echo "nuke-far-ruins-world уже есть — стоп"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins-state" && echo "nuke-far-ruins-state уже есть — стоп"
ls "$FILM/mods"/airstrike-*.jar; ls "$FILM/mods" | grep -i distanthorizons || echo "Distant Horizons в инстансе фильма нет — стоп"
grep -E '^renderDistance:' "$FILM/options.txt"
du -sh "$FILM/saves/greenfield-film"; df -h /mnt/data/projects/airstrike/mod/run    # свободно ≥ размер мира + 8 ГБ
```

## 2. Подготовка: worktree, копия мира, место удара 2, сохранить состояние инстанса фильма
`mod/run/` в .gitignore, в свежем worktree его нет — создаётся сразу после `worktree add`.
Состояние инстанса фильма (jar мода и строка `renderDistance`) сохраняется в `claude-work/nuke-far-ruins-state`
и возвращается в шаге 6.
```sh
cd /mnt/data/projects/airstrike && git fetch origin main @SHA@ && git cat-file -e '@SHA@^{commit}' && \
git worktree add "/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" @SHA@ && \
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" && cd "${W:?}" && mkdir -p "$W/mod/run" && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins-state" && \
mkdir -p "$S/mods" && cp -a "$FILM/mods"/airstrike-*.jar "$S/mods/" && grep -E '^renderDistance:' "$FILM/options.txt" > "$S/renderDistance.txt" && \
cp -a "$FILM/saves/greenfield-film" "$FILM/saves/greenfield-farlod" && \
T="$FILM/saves/greenfield-farlod/serverconfig/airstrike-server.toml" && C="$FILM/config/airstrike-server.toml" && \
if [ -f "$T" ]; then \
  sed -i -E 's/^(\s*flight_time\s*=).*/\1 1800/; s/^(\s*destruction_ms_per_tick\s*=).*/\1 30/' "$T" && echo "конфиг копии мира: $T" && \
  grep -E '^\s*(flight_time|destruction_ms_per_tick|far_zone)\s*=' "$T"; \
elif grep -Eq '^\s*flight_time\s*=\s*1800\s*$' "$C" 2>/dev/null && grep -Eq '^\s*destruction_ms_per_tick\s*=\s*30\s*$' "$C" 2>/dev/null; then \
  echo "в копии мира конфига нет — сервер берёт $C:" && grep -E '^\s*(flight_time|destruction_ms_per_tick|far_zone)\s*=' "$C"; \
else \
  echo "в копии мира конфига нет, а в $C не flight_time 1800 и destruction_ms_per_tick 30 — стоп"; \
  grep -E '^\s*(flight_time|destruction_ms_per_tick)\s*=' "$C"; false; \
fi && \
sed -i -E 's/^renderDistance:.*/renderDistance:24/' "$FILM/options.txt" && \
git log --oneline -1 && ls "$S/mods" && cat "$S/renderDistance.txt" && grep -E '^renderDistance:' "$FILM/options.txt"
```
Строка «стоп» — прогон не запускать, сразу шаг 6 (он уберёт копию мира в сторону и вернёт инстанс фильма), сообщить
координатору. `far_zone` в конфиге должно быть `true` или строки нет (по умолчанию `true`); `false` — тоже «стоп».

Место удара 2 — по копии мира (только чтение): к западу от Greenfield по линии z = −495 первое x (шаг 1000 блоков),
у которого нет ни одного файла региона в квадрате 5×5 регионов вокруг (±1 км от места: вся тяжёлая зона 1 кт не
сгенерирована), и которое с запасом 1,5 км внутри границы мира. Камера удара 2 — на 2,5 км восточнее места.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" && cd "${W:?}" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins-state" && \
python3 - "/mnt/data/projects/airstrike/mod/run/film/instance/minecraft/saves/greenfield-farlod" "$S/x2.txt" <<'EOF'
import gzip, os, struct, sys
world, out = sys.argv[1], sys.argv[2]
data = gzip.open(os.path.join(world, "level.dat")).read()
def double(name):
    key = b"\x06" + struct.pack(">H", len(name)) + name.encode()
    i = data.find(key)
    return None if i < 0 else struct.unpack(">d", data[i + len(key):i + len(key) + 8])[0]
size, cx = double("BorderSize") or 59999968.0, double("BorderCenterX") or 0.0
have = set(os.listdir(os.path.join(world, "region")))
print(f"граница мира: ширина {size:.0f}, центр x {cx:.0f}; файлов регионов {len(have)}")
def free(x):
    rx = x // 512
    return all(f"r.{a}.{b}.mca" not in have for a in range(rx - 2, rx + 3) for b in range(-3, 2))
for x in range(-5000, -30001, -1000):
    if x - 1500 < cx - size / 2:
        break
    if free(x):
        open(out, "w").write(str(x))
        print(f"место удара 2: x {x}, камера x {x + 2500}")
        break
else:
    print("места для удара 2 нет — стоп")
EOF
cat "$S/x2.txt"
```
«стоп» или пустой `x2.txt` — прогон не запускать, шаг 6, сообщить координатору (с выводом блока).

Сборка jar сценария — отдельно, до задачи (в фоне, тайм-аут вызова не меньше 25 мин); код не 0 — сообщить:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```
(prod_client.py внутри задачи ещё раз зовёт `scenarioJar` — после этого шага это проверка «всё собрано», секунды.)

## 3. Прогон (один; в фоне, тайм-аут вызова 65 мин)
Удар 1: камера в 2,5 км к западу от эпицентра Greenfield (136.5 69 −495.5), смотрит на восток; 15 кт, воздушный; кадры
через 30 с, 2, 3 и 6 мин после подрыва. Удар 2: камера переносится к месту из `x2.txt` (на 2,5 км восточнее, смотрит
на запад), через минуту — 1 кт, воздушный (высота 300 — сервер опускает точку на землю готового чанка, как у самого
подрыва); кадры примерно через 0, 1, 5, 10 и 15 мин после подрыва. Камера в спектаторе.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins-state" && X2=$(cat "$S/x2.txt") && [ -n "$X2" ] && C2=$((X2 + 2500)) && echo "удар 2: x $X2, камера x $C2" && \
touch mod/run/run-start && echo "начало: $(date -u +%T) UTC" && timeout -k 60 65m tools/laptop_job.sh nuke-far-ruins -- python3 tools/prod_client.py commands --no-copy --dir "/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" --world greenfield-farlod --seconds 3600 \
  --prop airstrike.nukeDiag=true --prop "airstrike.commands=hud:off;gamemode spectator;tp @s -2399.5 160 -495.5 -90 -5;wait:600;airstrike nuke at 136.5 69 -495.5 15 air;wait:nuke;wait:600;shot:far1_30s;wait:1780;shot:far1_2m;wait:1180;shot:far1_3m;wait:3580;shot:far1_6m;tp @s ${C2}.5 200 -495.5 90 -5;wait:1200;airstrike nuke at ${X2}.5 300 -495.5 1 air;wait:1960;shot:far2_0;wait:1180;shot:far2_1m;wait:4780;shot:far2_5m;wait:5980;shot:far2_10m;wait:5980;shot:far2_15m;wait:200"; code=$?; echo "код $code, конец: $(date -u +%T) UTC"; \
case "$code" in 124|137) systemctl --user stop 'airstrike-job-nuke-far-ruins-*';; esac; true
```
Время кадров удара 1 — от прихода к клиенту пакета подрыва (`wait:nuke`); после снимка шаг ждёт 20 тиков, после
команды — 40, `wait:N` прибавляется. Удар 2 — по часам (`wait:nuke` в плане один): полёт МБР 1800 тиков. Всего ~26 мин
игры плюс загрузка сборки и мира; `--seconds 3600` и `timeout 65m` — пределы. Конец — `SCENARIO done` в
`$FILM/logs/latest.log`, клиент выходит сам: **код 0**. Код 124 или 143 — предел.

## 4. Наблюдатель
Прогон жив: в `/mnt/data/projects/airstrike/mod/run/film/instance/minecraft/logs/latest.log` растёт лог, появляются
строки `SCENARIO commands`, в конце `SCENARIO done`. Смерть прогона — координатору в течение 5 мин, с причиной
(последние 40 строк лога, `crash-reports/`). Зависание выхода: если `SCENARIO done` уже есть, а через 3 мин после него
java прогона ещё жива — один раз (только чтение, процесс не трогается):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" && cd "${W:?}" && mkdir -p mod/run && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && \
R=$(realpath "$FILM") && PID=$(for p in $(pgrep -x java); do [ "$(readlink "/proc/$p/cwd")" = "$R" ] && echo "$p"; done | head -1) && [ -n "$PID" ] && echo "java прогона: $PID" && \
timeout 60 "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jstack" "$PID" > mod/run/farlod-jstack.txt; \
echo "код $?"; wc -lc mod/run/farlod-jstack.txt
```
Координатору — первые 200 строк потока «Server thread» из `farlod-jstack.txt`.
**Шаг 6 выполняется всегда** — и после смерти прогона или упавшей сборки.

## 5. Кадры и выжимка
Кадры — `$FILM/screenshots/<имя>_<тик>.png` (у каждого имени — самый новый файл этого прогона). JPEG q85 —
в `$W/mod/run/farlod-frames/`:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" && cd "${W:?}" && mkdir -p mod/run/farlod-frames && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && \
for n in far1_30s far1_2m far1_3m far1_6m far2_0 far2_1m far2_5m far2_10m far2_15m; do \
  f=$(find "$FILM/screenshots" -maxdepth 1 -name "${n}_[0-9]*.png" -newer mod/run/run-start -printf "%T@ %p\n" 2>/dev/null | sort -nr | head -1 | cut -d" " -f2-); \
  if [ -n "$f" ]; then ffmpeg -loglevel error -y -i "$f" -q:v 3 "mod/run/farlod-frames/$n.jpg" && echo "$n ← $(basename "$f")"; else echo "$n: кадра нет"; fi; \
done; ls -l mod/run/farlod-frames
```
Лог:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" && cd "${W:?}" && FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && L="$FILM/logs/latest.log" && \
python3 tools/logscan.py "$L" --all > mod/run/farlod-logscan.txt; wc -lc mod/run/farlod-logscan.txt; \
grep -E 'Ядерный подрыв №|Руины удара №|Подрыв №|Руины подрыва №|зона за волной|дальние кольца|LOD Distant Horizons вдали|LOD вдали:|Блэкаут №|Can.t keep up|Ядерный тик|дальше руины заранее не строятся|Работа мода за 30 с|SCENARIO commands|SCENARIO done|has crashed|emergencySaveAndCrash' "$L" | grep -v 'ДИАГ' | cut -c1-600 > mod/run/farlod-lines.txt; wc -lc mod/run/farlod-lines.txt; \
grep -E 'ДИАГ (зона|очередь)' "$L" | sed 's/.*ДИАГ/ДИАГ/' | cut -c1-500 > mod/run/farlod-diag.txt; wc -lc mod/run/farlod-diag.txt; \
hms() { grep -oE '[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}' | head -1; } && \
{ echo "выход: SCENARIO done $(grep -m1 'SCENARIO done' "$L" | hms), Stopping server $(grep -m1 'Stopping server' "$L" | hms), All dimensions are saved $(grep -m1 'All dimensions are saved' "$L" | hms), последняя строка $(tail -1 "$L" | hms)"; } >> mod/run/farlod-lines.txt; tail -1 mod/run/farlod-lines.txt
```

## 6. Вернуть инстанс фильма (два блока, всегда, в любом исходе)
Jar мода и `renderDistance` — как до прогона; копия мира — в сторону (не удалять); worktree остаётся (уборка — только
по слову Артёма в треде «Ноутбук»). Выжимки — каждый файл, только если он есть:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins" && cd "${W:?}" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins-state" && mkdir -p "$S" && \
for f in farlod-frames farlod-logscan.txt farlod-lines.txt farlod-diag.txt farlod-jstack.txt; do [ -e "$W/mod/run/$f" ] && cp -a "$W/mod/run/$f" "$S/" && echo "скопировано $f"; done; ls "$S"
```
Откат инстанса фильма — не зависит от прогона, повтор безвреден (jar, которые лежат в `mods/` сейчас, — в
`$S/removed/`, jar фильма — обратно из `$S/mods/`; если шаг 2 не успел сохранить jar — `mods/` не трогается):
```sh
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins-state" && G="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-far-ruins-world" && \
if ls "$S/mods"/airstrike-*.jar >/dev/null 2>&1; then mkdir -p "$S/removed" && for j in "$FILM/mods"/airstrike-*.jar; do [ -e "$j" ] && mv "$j" "$S/removed/"; done; \
cp -a "$S/mods"/airstrike-*.jar "$FILM/mods/"; else echo "jar фильма не сохранён — mods/ не трогаю"; fi; RD=$(cat "$S/renderDistance.txt") && [ -n "$RD" ] && sed -i -E "s/^renderDistance:.*/${RD}/" "$FILM/options.txt"; \
[ -d "$FILM/saves/greenfield-farlod" ] && [ ! -e "$G" ] && mv "$FILM/saves/greenfield-farlod" "$G"; \
ls "$FILM/mods"/airstrike-*.jar; grep -E '^renderDistance:' "$FILM/options.txt"; ls "$FILM/saves"; ls -d "$G"
```
Должно быть: в `mods/` — тот же jar, что в шаге 1; `renderDistance` — как в шаге 1; в `saves/` нет `greenfield-farlod`.

## Что прислать координатору
Текстом (≈ до 40 КБ; больше 60 КБ — двумя сообщениями, не урезая):
0. Код выхода прогона, время начала и конца (шаг 3), вывод блока выбора места удара 2, строку `выход: …`
   (последняя в `farlod-lines.txt`).
1. Из `farlod-lines.txt` дословно: обе строки `Ядерный подрыв №…: … кт …`; все строки `LOD Distant Horizons вдали …`
   (с меткой времени); все `Подрыв №M: зона за волной — …`, `… дальние кольца: …`, `Руины удара №N готовы: …`,
   `Подрыв №M: руины заранее — …`; все строки `Руины подрыва №M: …` (в том числе `не дождались …` — или «нет»);
   все `LOD вдали: квадрат …` (или «нет»); строки `Блэкаут №…`; `… дальше руины заранее не строятся` (или «нет»).
2. Все `Работа мода за 30 с: …`, `Ядерный тик …`, `Can't keep up` дословно; самый долгий тик сервера за 60 с после
   каждого подрыва (раздел «Отставание сервера» из `farlod-logscan.txt`).
3. logscan: ошибки/исключения/`Mixin`/`AccessTransformer`/`DUMMY` — или «ноль».
4. `farlod-diag.txt` (≤ 12 КБ, иначе первые и последние 8 строк).
5. По строке о каждом из 9 кадров: что видно на месте города (удар 1) и места удара 2 — целые постройки/лес в LOD
   или разрушенное, пыль закрывает или нет.
Кадры (9 JPEG из `farlod-frames`) — загрузить в `/mnt/project-files/nuke/far-ruins/` (SendUserFile).

## Проходит, если
- **А (удар 1, руины вдали с диска):** в строках `LOD Distant Horizons вдали` после подрыва 1 — `(руины 0)` не позже
  чем через **3 мин** после подрыва (или строки до этого кончились: строка пишется раз в 30 с, пока очередь не пуста) (расчёт — около минуты: 8 чтений с диска за тик). Прислать и ряд `с руинами`
  по времени: в 2.4.1 он стоял 16 мин после первых 2,5 мин.
- **Б:** строки `Руины подрыва №M: не дождались …` нет у обоих ударов, а если есть — `в зоне за волной 0`;
  `зона за волной — готово Y из Y` у удара 1. Так же и после строки `… дальше руины заранее не строятся`: зона тогда
  грузит только чанки с готовым планом, и они не пропадают (написать, была ли остановка и у какого удара).
- **В (удар 2, догенерация):** `не целые на диске` в строке `LOD Distant Horizons вдали` больше нуля (иначе `FarZone`
  не проверен — так и написать); к концу прогона `квадратов ждёт 0, держится 0`, `по сроку 0`, строк
  `LOD вдали: квадрат … отпущен без руин` нет. Прислать время от подрыва 2 до строки `ждёт 0, держится 0` (или
  последнюю строку, если не дошло), `взято`, `генерация в среднем … тиков, до отпуска …` — для оценки, сколько идёт
  догенерация у края исследованного мира.
- **клиент не упал:** нет `has crashed`, `emergencySaveAndCrash`, `Unreported exception`; прогон кончается сам с
  **кодом 0**; logscan — без новых ошибок.
- самый долгий тик после каждого подрыва — для сведения (кроме тика подрыва и 5 с после `tp`); пауза из-за
  догенерации зоны удара 2 видна в «Работа мода за 30 с» и `Can't keep up` — тоже прислать.
