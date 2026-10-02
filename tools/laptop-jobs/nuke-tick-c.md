# Ноутбук: удар 2 из nuke-tick-ab ещё раз — с правкой края чанка для Sable (одна задача, один прогон C)

Тред «Разбор сегодняшних игр на 2.4.1». Папки и задача — `nuke-tick-c`; следы прошлых прогонов (`nuke-tick-a`,
`nuke-tick-b`, `nuke-tick-state`, `nuke-tick-world-*`, `nuke-far-ruins*`, `leak-chunks*`) не трогаются, из
`nuke-tick-state` только читается `x2.txt`.

Коммит **C** = `@SHA_C@` — полный SHA коммита, по которому взят этот файл (PR #186), из сообщения координатора.
Подставить его во все блоки ниже (одна замена, до шага 2; строк с `@SHA_` после неё быть не должно).

Зачем. В прогоне B задачи nuke-tick-ab остался тик 3,5 с («Медленный чанк руин [-385, -49]: 3457 мс»): поток сервера
ждал соседний чанк внутри `ColumnScar.replace` — Sable на смену блока читает соседей места на 2 блока, а проверка
перед бревном поваленного ствола смотрела на 1. Прогон C — тот же сценарий B на коммите с правкой: долгих ядерных
тиков быть не должно.

Условия: Артём не играет; одна тяжёлая задача на машине; только `tools/laptop_job.sh`; клиент только через
`tools/nested_kwin.sh` (это делает prod_client.py); большие файлы — в `mod/run/`. Инстанс Артёма, его миры и настройки
не трогать. Мир фильма (`greenfield-film`) не трогать: удары идут по **копии** `greenfield-tick`. Инстанс фильма
(`mod/run/film/instance/minecraft`) после прогона возвращается как был: `options.txt` и jar мода в `mods/` — шаги 2
и 6. Режим питания не менять.

**Каждый блок ниже — одним вызовом, как написан**: состояние оболочки между вызовами не сохраняется. Блоки с шага 2
начинаются с `W=… && cd "${W:?}"`. Папки — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-c`
(worktree), `…/nuke-tick-c-state` и `…/nuke-tick-world-c` (новые; если какая-то уже есть — остановиться и сообщить
координатору, ничего не удалять и не перезаписывать).

**Остановить задачу — только** `systemctl --user stop 'airstrike-job-nuke-tick-c*'`. Никаких `pkill`/`killall`/`kill`
по имени, никакого `./gradlew --stop`, никаких `-f`/`--force`.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп» или «нет» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
C="/mnt/data/projects/airstrike/mod/run/claude-work"
for d in nuke-tick-c nuke-tick-c-state nuke-tick-world-c; do test -e "$C/$d" && echo "$C/$d уже есть — стоп"; done
test -s "$C/nuke-tick-state/x2.txt" && echo "место удара 2: x $(cat "$C/nuke-tick-state/x2.txt")" || echo "нет nuke-tick-state/x2.txt — стоп"
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft"
test -d "$FILM/saves/greenfield-film" && echo "мир фильма есть" || echo "мира фильма нет — стоп"
test -e "$FILM/saves/greenfield-tick" && echo "копия greenfield-tick уже есть — стоп"
ls "$FILM/mods"/airstrike-*.jar; ls "$FILM/mods" | grep -i distanthorizons || echo "Distant Horizons в инстансе фильма нет — стоп"
grep -E '^renderDistance:' "$FILM/options.txt"
du -sh "$FILM/saves/greenfield-film"; df -h /mnt/data/projects/airstrike/mod/run    # свободно ≥ размер мира + 8 ГБ
```

## 2. Подготовка: worktree, состояние инстанса фильма, сборка
Состояние инстанса фильма (jar мода и строка `renderDistance`) сохраняется в `claude-work/nuke-tick-c-state` и
возвращается в шаге 6.
```sh
cd /mnt/data/projects/airstrike && git fetch origin @SHA_C@ && git cat-file -e '@SHA_C@^{commit}' && \
C="/mnt/data/projects/airstrike/mod/run/claude-work" && git worktree add "$C/nuke-tick-c" @SHA_C@ && mkdir -p "$C/nuke-tick-c/mod/run" && \
W="$C/nuke-tick-c" && cd "${W:?}" && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="$C/nuke-tick-c-state" && \
mkdir -p "$S/mods" && cp -a "$FILM/mods"/airstrike-*.jar "$S/mods/" && grep -E '^renderDistance:' "$FILM/options.txt" > "$S/renderDistance.txt" && \
sed -i -E 's/^renderDistance:.*/renderDistance:24/' "$FILM/options.txt" && \
git log --oneline -1 && ls "$S/mods" && cat "$S/renderDistance.txt" && grep -E '^renderDistance:' "$FILM/options.txt" && ls tools/laptop-jobs/nuke-tick/
```
Сборка jar сценария (в фоне, тайм-аут вызова не меньше 25 мин); код не 0 — шаг 6, сообщить:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-c" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ./gradlew scenarioJar -q --console=plain; echo "c: код $?"; ls -l build/scenario-libs/
```

## 3. Прогон C (в фоне, тайм-аут вызова 50 мин)
Тот же сценарий, что у B в nuke-tick-ab: удар 1 — камера в 2,5 км к западу от Greenfield, 15 кт воздушный; удар 2 —
камера на 2,5 км восточнее места из `nuke-tick-state/x2.txt`, 1 кт воздушный, 9600 тиков наблюдения. Перед прогоном —
свежая копия мира фильма с тем же конфигом (flight_time 1800, destruction_ms_per_tick 30), после — копия в сторону
(`claude-work/nuke-tick-world-c`), лог, JFR и лог сборщика — в `nuke-tick-c-state/`. JFR пишется на диск
(`$O/jfr-repo`; `/tmp` на ноутбуке в ОЗУ), файл — при выходе клиента.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-c" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
O="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-c-state" && mkdir -p "$O/jfr-repo" && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && X2=$(cat /mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-state/x2.txt) && [ -n "$X2" ] && C2=$((X2 + 2500)) && echo "прогон c: удар 2 x $X2, камера x $C2" && \
test ! -e "$FILM/saves/greenfield-tick" && cp -a "$FILM/saves/greenfield-film" "$FILM/saves/greenfield-tick" && \
T="$FILM/saves/greenfield-tick/serverconfig/airstrike-server.toml" && CF="$FILM/config/airstrike-server.toml" && \
if [ -f "$T" ]; then \
  sed -i -E 's/^(\s*flight_time\s*=).*/\1 1800/; s/^(\s*destruction_ms_per_tick\s*=).*/\1 30/' "$T" && grep -E '^\s*(flight_time|destruction_ms_per_tick|far_zone)\s*=' "$T"; \
elif grep -Eq '^\s*flight_time\s*=\s*1800\s*$' "$CF" 2>/dev/null && grep -Eq '^\s*destruction_ms_per_tick\s*=\s*30\s*$' "$CF" 2>/dev/null; then \
  echo "конфиг — $CF:" && grep -E '^\s*(flight_time|destruction_ms_per_tick|far_zone)\s*=' "$CF"; \
else echo "конфиг не тот — стоп"; false; fi && \
touch "$O/run-start" && echo "начало: $(date -u +%T) UTC" && timeout -k 60 50m tools/laptop_job.sh "nuke-tick-c" -- python3 tools/prod_client.py commands --no-copy --dir "$FILM" --world greenfield-tick --seconds 2700 \
  --prop airstrike.nukeDiag=true \
  "--jvm=-XX:FlightRecorderOptions=repository=$O/jfr-repo" \
  "--jvm=-XX:StartFlightRecording=name=tick,settings=profile,jdk.ExecutionSample#period=5ms,jdk.GCLocker#threshold=0ms,disk=true,maxsize=3g,dumponexit=true,filename=$O/tick.jfr" \
  "--jvm=-Xlog:gc*:file=$O/gc-full.log:time,uptime" \
  --prop "airstrike.commands=hud:off;gamemode spectator;tp @s -2399.5 160 -495.5 -90 -5;wait:600;airstrike nuke at 136.5 69 -495.5 15 air;wait:nuke;wait:620;wait:1800;wait:1200;wait:3600;tp @s ${C2}.5 200 -495.5 90 -5;wait:1200;airstrike nuke at ${X2}.5 300 -495.5 1 air;wait:1980;wait:9600"; code=$?; echo "код $code, конец: $(date -u +%T) UTC"; \
case "$code" in 124|137) systemctl --user stop "airstrike-job-nuke-tick-c-*";; esac; \
cp -a "$FILM/logs/latest.log" "$O/latest.log"; cp -a "$FILM/logs/gc.log" "$O/gc.log" 2>/dev/null; ls -la "$O"; \
G="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-world-c"; [ -d "$FILM/saves/greenfield-tick" ] && [ ! -e "$G" ] && mv "$FILM/saves/greenfield-tick" "$G"; ls -d "$G"; true
```
Конец — `SCENARIO done` в логе, клиент выходит сам: **код 0** (124/143 — предел). Всего ~19 мин игры плюс загрузка.

## 4. Наблюдатель
Прогон жив: в `$FILM/logs/latest.log` растёт лог, появляются `SCENARIO commands`, в конце `SCENARIO done`. Смерть
прогона — координатору в течение 5 мин, с причиной (последние 40 строк лога, `crash-reports/`). Если `SCENARIO done`
уже есть, а через 3 мин java прогона ещё жива — один раз jstack (только чтение, процесс не трогается):
```sh
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && O="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-c-state" && \
R=$(realpath "$FILM") && PID=$(for p in $(pgrep -x java); do [ "$(readlink "/proc/$p/cwd")" = "$R" ] && echo "$p"; done | head -1) && [ -n "$PID" ] && echo "java прогона: $PID" && \
timeout 60 "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jstack" "$PID" > "$O/jstack-$(date -u +%H%M%S).txt"; echo "код $?"
```
**Шаг 6 выполняется всегда** — и после смерти прогона или упавшей сборки.

## 5. Выжимка (инструменты — из worktree C: `tools/laptop-jobs/nuke-tick/`)
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-c" && cd "${W:?}" && O="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-c-state" && \
J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin" && L="$O/latest.log" && \
grep -E 'Ядерный подрыв №|Ядерный тик|Медленный чанк руин|Can.t keep up|Работа мода за 30 с|Руины удара №|дальше руины заранее не строятся|Руины подрыва №[0-9]+: (по готовому|в памяти)|зона за волной — готово|SCENARIO done|has crashed|emergencySaveAndCrash' "$L" | grep -v 'ДИАГ' | cut -c1-400 > "$O/lines.txt"; wc -lc "$O/lines.txt"; \
python3 tools/logscan.py "$L" --all > "$O/logscan.txt"; wc -lc "$O/logscan.txt"; \
if [ -f "$O/tick.jfr" ]; then ls -l "$O/tick.jfr"; "$J/java" -Dstdout.encoding=UTF-8 tools/laptop-jobs/nuke-tick/LongTicks.java "$O/tick.jfr" 400 > "$O/longticks.txt" 2>&1; echo "LongTicks: код $?"; wc -lc "$O/longticks.txt"; else echo "JFR нет"; fi
```

## 6. Вернуть инстанс фильма (всегда, в любом исходе; повтор безвреден)
Jar мода и `renderDistance` — как до прогона; копия мира — в сторону (не удалять); worktree остаётся (уборка — только
по слову Артёма в треде «Ноутбук»).
```sh
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-c-state" && \
if ls "$S/mods"/airstrike-*.jar >/dev/null 2>&1; then mkdir -p "$S/removed" && for j in "$FILM/mods"/airstrike-*.jar; do [ -e "$j" ] && mv "$j" "$S/removed/"; done; \
cp -a "$S/mods"/airstrike-*.jar "$FILM/mods/"; else echo "jar фильма не сохранён — mods/ не трогаю"; fi; RD=$(cat "$S/renderDistance.txt") && [ -n "$RD" ] && sed -i -E "s/^renderDistance:.*/${RD}/" "$FILM/options.txt"; \
G="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-world-c"; [ -d "$FILM/saves/greenfield-tick" ] && [ ! -e "$G" ] && mv "$FILM/saves/greenfield-tick" "$G"; \
ls "$FILM/mods"/airstrike-*.jar; grep -E '^renderDistance:' "$FILM/options.txt"; ls "$FILM/saves"
```
Должно быть: в `mods/` — тот же jar, что в шаге 1; `renderDistance` — как в шаге 1; в `saves/` нет `greenfield-tick`.

## Что прислать координатору
Текстом (≈ до 40 КБ; больше — двумя сообщениями, не урезая):
0. Код выхода, время начала и конца.
1. `lines.txt` целиком (если больше 12 КБ — все строки `Ядерный тик`, `Медленный чанк руин`, `Can't keep up`,
   `Ядерный подрыв №`, `Руины подрыва №`, `зона за волной — готово`, а из `Работа мода за 30 с` — только те, где наибольший
   тик больше 500 мс).
2. `longticks.txt` целиком (если больше 16 КБ — первые 16 КБ).
3. logscan: ошибки/исключения/`Mixin`/`AccessTransformer`/`DUMMY` — или «ноль».
Файл `tick.jfr` — не присылать, он остаётся в `nuke-tick-c-state/`.

## Проходит, если
- прогон кончился сам с **кодом 0**, клиент не упал, logscan без новых ошибок;
- ни одного «Ядерный тик» дольше 1000 мс и ни одного ядерного окна дольше 1000 мс в `longticks.txt`, кроме тика самого
  подрыва (в B были 3458 мс и 916 мс в очереди руин);
- руины обоих ударов встали, как в B (`Руины подрыва №M: …`, `зона за волной — готово Y из Y`).
