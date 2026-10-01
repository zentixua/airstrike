# Ноутбук: ядерный тик 3,6 с при догенерации зоны удара 2 — что занимало поток сервера, до и после правки (одна задача)

Тред «Разбор сегодняшних игр на 2.4.1». Папки и задача — `nuke-tick`, следы прошлых прогонов (`nuke-far-ruins*`,
`leak-chunks*`) не трогаются.

Два коммита: **A** = `3b5e91d547bf434de6c7a3bc5d06f7b1e46e638d` (main после #181, код прогона nuke-far-ruins),
**B** = `@SHA_B@` — полный SHA коммита, по которому взят этот файл (PR #186 с правкой), из сообщения координатора.
Подставить его во все блоки ниже (одна замена, до шага 2; строк с `@SHA_` после неё быть не должно). Коммиты
берутся по SHA.

Что меряем. В прогоне nuke-far-ruins (1233bf2, ноутбук 01.10, тихий режим питания) после удара 2 — 1 кт в несгенерированном
месте, зону догенерирует `FarZone` — был тик сервера 3,6 с: «Ядерный тик 3597 мс: … чанки 3597 мс» в 00:22:58 по логу,
сборщик не виноват (пауз G1 не больше 38,7 мс). Своей строки «Медленный чанк руин» у него нет — значит, время ушло мимо
единиц работы с часами. Тот же прогон повторяется дважды с записью JFR (образцы потока раз в 5 мс, GCLocker, безопасные
точки, ожидания потока): **A** — чтобы увидеть, что поток сервера делал в долгих ядерных тиках; **B** — что с правкой
таких тиков нет, и строка медленного тика с правки называет, где время. В B ещё сверка кучи: строка «ДИАГ куча» мода
(старое поколение после сборки, которая его освободила) против лога G1.

Условия: Артём не играет; одна тяжёлая задача на машине (задача `leak-chunks` закончена: её юнит не активен); только
`tools/laptop_job.sh`; клиент только через `tools/nested_kwin.sh` (это делает prod_client.py); большие файлы — в
`mod/run/`. Инстанс Артёма, его миры и настройки не трогать. Мир фильма (`greenfield-film`) не трогать: удары идут по
**копиям** `greenfield-tick`. Инстанс фильма (`mod/run/film/instance/minecraft`) после прогонов возвращается как был:
`options.txt` и jar мода в `mods/` — шаги 2 и 6. Режим питания не менять.

**Каждый блок ниже — одним вызовом, как написан**: состояние оболочки между вызовами не сохраняется. Блоки с шага 2
начинаются с `W=… && cd "${W:?}"`. Папки worktree — ровно `/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-a`
и `…/nuke-tick-b` (новые; если какая-то уже есть — остановиться и сообщить координатору, ничего не удалять и не
перезаписывать).

**Остановить задачу — только** `systemctl --user stop 'airstrike-job-nuke-tick-*'`. Никаких `pkill`/`killall`/`kill`
по имени, никакого `./gradlew --stop`, никаких `-f`/`--force`.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп» или «нет» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
C="/mnt/data/projects/airstrike/mod/run/claude-work"
for d in nuke-tick-a nuke-tick-b nuke-tick-state nuke-tick-world-a nuke-tick-world-b; do test -e "$C/$d" && echo "$C/$d уже есть — стоп"; done
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft"
test -d "$FILM/saves/greenfield-film" && echo "мир фильма есть" || echo "мира фильма нет — стоп"
test -e "$FILM/saves/greenfield-tick" && echo "копия greenfield-tick уже есть — стоп"
ls "$FILM/mods"/airstrike-*.jar; ls "$FILM/mods" | grep -i distanthorizons || echo "Distant Horizons в инстансе фильма нет — стоп"
grep -E '^renderDistance:' "$FILM/options.txt"
J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin"; "$J/javac" -version || echo "нет javac в JDK Prism — стоп"
du -sh "$FILM/saves/greenfield-film"; df -h /mnt/data/projects/airstrike/mod/run    # свободно ≥ 2 × размер мира + 12 ГБ
```

## 2. Подготовка: два worktree, место удара 2, состояние инстанса фильма, сборки
Состояние инстанса фильма (jar мода и строка `renderDistance`) сохраняется в `claude-work/nuke-tick-state` и
возвращается в шаге 6. Копии мира делает шаг 3 перед каждым прогоном.
```sh
cd /mnt/data/projects/airstrike && git fetch origin main 3b5e91d547bf434de6c7a3bc5d06f7b1e46e638d @SHA_B@ && git cat-file -e '3b5e91d547bf434de6c7a3bc5d06f7b1e46e638d^{commit}' && git cat-file -e '@SHA_B@^{commit}' && \
C="/mnt/data/projects/airstrike/mod/run/claude-work" && git worktree add "$C/nuke-tick-a" 3b5e91d547bf434de6c7a3bc5d06f7b1e46e638d && git worktree add "$C/nuke-tick-b" @SHA_B@ && \
mkdir -p "$C/nuke-tick-a/mod/run" "$C/nuke-tick-b/mod/run" && \
W="$C/nuke-tick-b" && cd "${W:?}" && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="$C/nuke-tick-state" && \
mkdir -p "$S/mods" && cp -a "$FILM/mods"/airstrike-*.jar "$S/mods/" && grep -E '^renderDistance:' "$FILM/options.txt" > "$S/renderDistance.txt" && \
sed -i -E 's/^renderDistance:.*/renderDistance:24/' "$FILM/options.txt" && \
git -C "$C/nuke-tick-a" log --oneline -1 && git -C "$C/nuke-tick-b" log --oneline -1 && ls "$S/mods" && cat "$S/renderDistance.txt" && grep -E '^renderDistance:' "$FILM/options.txt" && \
ls tools/laptop-jobs/ && ls tools/laptop-jobs/nuke-tick/
```
Место удара 2 — то же правило, что в nuke-far-ruins, по миру фильма (только чтение): к западу от Greenfield по линии
z = −495 первое x (шаг 1000 блоков) без единого файла региона в квадрате 5×5 регионов вокруг, с запасом 1,5 км внутри
границы мира (в прошлый раз — x −6000).
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-b" && cd "${W:?}" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-state" && \
python3 - "/mnt/data/projects/airstrike/mod/run/film/instance/minecraft/saves/greenfield-film" "$S/x2.txt" <<'EOF'
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
«стоп» или пустой `x2.txt` — не запускать, шаг 6, сообщить координатору.

Сборки jar сценария — до задачи, по очереди (в фоне, тайм-аут вызова не меньше 45 мин); код не 0 — сообщить:
```sh
for v in a b; do W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-$v" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ./gradlew scenarioJar -q --console=plain; echo "$v: код $?"; ls -l build/scenario-libs/; done
```

## 3. Прогоны A и B (по очереди; каждый блок — в фоне, тайм-аут вызова 50 мин)
Один и тот же сценарий, что в nuke-far-ruins, без кадров (вместо снимка — те же 20 тиков ожидания), удар 2 — 9600 тиков
наблюдения вместо 20000 (тик 3,6 с был через 1 мин 43 с после подрыва 2, зона кончилась через 5 мин). Удар 1:
камера в 2,5 км к западу от Greenfield, 15 кт воздушный; удар 2: камера на 2,5 км восточнее места из `x2.txt`, 1 кт
воздушный. Перед прогоном — свежая копия мира фильма с тем же конфигом, что в nuke-far-ruins (flight_time 1800,
destruction_ms_per_tick 30), после — копия в сторону (`claude-work/nuke-tick-world-<v>`), лог, JFR и логи сборщика —
в `nuke-tick-state/<v>/`. JFR пишется на диск (`$S/<v>/jfr-repo`; `/tmp` на ноутбуке в ОЗУ), файл — при выходе клиента.

Блок для прогона **A** (для B — тот же блок с `v=b` в первой строке):
```sh
v=a && W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-$v" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-state" && O="$S/$v" && mkdir -p "$O/jfr-repo" && \
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && X2=$(cat "$S/x2.txt") && [ -n "$X2" ] && C2=$((X2 + 2500)) && echo "прогон $v: удар 2 x $X2, камера x $C2" && \
test ! -e "$FILM/saves/greenfield-tick" && cp -a "$FILM/saves/greenfield-film" "$FILM/saves/greenfield-tick" && \
T="$FILM/saves/greenfield-tick/serverconfig/airstrike-server.toml" && CF="$FILM/config/airstrike-server.toml" && \
if [ -f "$T" ]; then \
  sed -i -E 's/^(\s*flight_time\s*=).*/\1 1800/; s/^(\s*destruction_ms_per_tick\s*=).*/\1 30/' "$T" && grep -E '^\s*(flight_time|destruction_ms_per_tick|far_zone)\s*=' "$T"; \
elif grep -Eq '^\s*flight_time\s*=\s*1800\s*$' "$CF" 2>/dev/null && grep -Eq '^\s*destruction_ms_per_tick\s*=\s*30\s*$' "$CF" 2>/dev/null; then \
  echo "конфиг — $CF:" && grep -E '^\s*(flight_time|destruction_ms_per_tick|far_zone)\s*=' "$CF"; \
else echo "конфиг не тот — стоп"; false; fi && \
touch "$O/run-start" && echo "начало: $(date -u +%T) UTC" && timeout -k 60 50m tools/laptop_job.sh "nuke-tick-$v" -- python3 tools/prod_client.py commands --no-copy --dir "$FILM" --world greenfield-tick --seconds 2700 \
  --prop airstrike.nukeDiag=true \
  "--jvm=-XX:FlightRecorderOptions=repository=$O/jfr-repo" \
  "--jvm=-XX:StartFlightRecording=name=tick,settings=profile,jdk.ExecutionSample#period=5ms,jdk.GCLocker#threshold=0ms,disk=true,maxsize=3g,dumponexit=true,filename=$O/tick.jfr" \
  "--jvm=-Xlog:gc*:file=$O/gc-full.log:time,uptime" \
  --prop "airstrike.commands=hud:off;gamemode spectator;tp @s -2399.5 160 -495.5 -90 -5;wait:600;airstrike nuke at 136.5 69 -495.5 15 air;wait:nuke;wait:620;wait:1800;wait:1200;wait:3600;tp @s ${C2}.5 200 -495.5 90 -5;wait:1200;airstrike nuke at ${X2}.5 300 -495.5 1 air;wait:1980;wait:9600"; code=$?; echo "код $code, конец: $(date -u +%T) UTC"; \
case "$code" in 124|137) systemctl --user stop "airstrike-job-nuke-tick-$v-*";; esac; \
cp -a "$FILM/logs/latest.log" "$O/latest.log"; cp -a "$FILM/logs/gc.log" "$O/gc.log" 2>/dev/null; ls -la "$O"; \
G="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-world-$v"; [ -d "$FILM/saves/greenfield-tick" ] && [ ! -e "$G" ] && mv "$FILM/saves/greenfield-tick" "$G"; ls -d "$G"; true
```
Конец — `SCENARIO done` в логе, клиент выходит сам: **код 0** (124/143 — предел). Всего ~19 мин игры плюс загрузка.
Между A и B — убедиться, что `ls "$FILM/saves"` без `greenfield-tick` и java прогона нет (`pgrep -a -x java`).

## 4. Наблюдатель (на каждый прогон)
Прогон жив: в `$FILM/logs/latest.log` растёт лог, появляются `SCENARIO commands`, в конце `SCENARIO done`. Смерть
прогона — координатору в течение 5 мин, с причиной (последние 40 строк лога, `crash-reports/`). Если `SCENARIO done`
уже есть, а через 3 мин java прогона ещё жива — один раз jstack (только чтение, процесс не трогается):
```sh
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && O="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-state" && \
R=$(realpath "$FILM") && PID=$(for p in $(pgrep -x java); do [ "$(readlink "/proc/$p/cwd")" = "$R" ] && echo "$p"; done | head -1) && [ -n "$PID" ] && echo "java прогона: $PID" && \
timeout 60 "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jstack" "$PID" > "$O/jstack-$(date -u +%H%M%S).txt"; echo "код $?"
```
**Шаг 6 выполняется всегда** — и после смерти прогона или упавшей сборки.

## 5. Выжимка (после обоих прогонов; инструменты — из worktree B: `tools/laptop-jobs/nuke-tick/`)
`LongTicks.java` — долгие ядерные тики прямо по JFR (подряд идущие образцы потока сервера с `NuclearStrikes.tick` в
стеке, без сверки часов лога): для трёх самых долгих — где поток был, разрывы между образцами (поток не в Java-коде —
ждал JVM), сборки, GCLocker и безопасные точки в окне. `gcsum.py` — лог G1: сборки, после которых старое поколение
стало меньше, и строки «ДИАГ куча».
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-b" && cd "${W:?}" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-state" && \
J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin" && \
for v in a b; do O="$S/$v"; L="$O/latest.log"; [ -f "$L" ] || { echo "$v: лога нет"; continue; }; \
  grep -E 'Ядерный подрыв №|Ядерный тик|Медленный чанк руин|Can.t keep up|Работа мода за 30 с|Руины удара №|дальше руины заранее не строятся|Руины подрыва №[0-9]+: (по готовому|в памяти)|зона за волной — готово|SCENARIO done|has crashed|emergencySaveAndCrash' "$L" | grep -v 'ДИАГ' | cut -c1-400 > "$O/lines.txt"; wc -lc "$O/lines.txt"; \
  python3 tools/logscan.py "$L" --all > "$O/logscan.txt"; wc -lc "$O/logscan.txt"; \
  if [ -f "$O/tick.jfr" ]; then ls -l "$O/tick.jfr"; "$J/java" -Dstdout.encoding=UTF-8 tools/laptop-jobs/nuke-tick/LongTicks.java "$O/tick.jfr" 400 > "$O/longticks.txt" 2>&1; echo "$v LongTicks: код $?"; wc -lc "$O/longticks.txt"; else echo "$v: JFR нет"; fi; \
  [ -f "$O/gc-full.log" ] && python3 tools/laptop-jobs/nuke-tick/gcsum.py "$O/gc-full.log" "$L" > "$O/gcsum.txt"; wc -lc "$O/gcsum.txt"; \
done
```

## 6. Вернуть инстанс фильма (всегда, в любом исходе; повтор безвреден)
Jar мода и `renderDistance` — как до прогонов; копии мира — в сторону (не удалять); worktree остаются (уборка — только
по слову Артёма в треде «Ноутбук»).
```sh
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft" && S="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-state" && \
if ls "$S/mods"/airstrike-*.jar >/dev/null 2>&1; then mkdir -p "$S/removed" && for j in "$FILM/mods"/airstrike-*.jar; do [ -e "$j" ] && mv "$j" "$S/removed/"; done; \
cp -a "$S/mods"/airstrike-*.jar "$FILM/mods/"; else echo "jar фильма не сохранён — mods/ не трогаю"; fi; RD=$(cat "$S/renderDistance.txt") && [ -n "$RD" ] && sed -i -E "s/^renderDistance:.*/${RD}/" "$FILM/options.txt"; \
for v in a b; do G="/mnt/data/projects/airstrike/mod/run/claude-work/nuke-tick-world-$v"; [ -d "$FILM/saves/greenfield-tick" ] && [ ! -e "$G" ] && mv "$FILM/saves/greenfield-tick" "$G"; done; \
ls "$FILM/mods"/airstrike-*.jar; grep -E '^renderDistance:' "$FILM/options.txt"; ls "$FILM/saves"
```
Должно быть: в `mods/` — тот же jar, что в шаге 1; `renderDistance` — как в шаге 1; в `saves/` нет `greenfield-tick`.

## Что прислать координатору
Текстом (≈ до 40 КБ; больше — двумя сообщениями, не урезая). Для каждого прогона (A, затем B):
0. Код выхода, время начала и конца, вывод блока выбора места удара 2 (один раз).
1. `lines.txt` целиком (если больше 12 КБ — все строки `Ядерный тик`, `Медленный чанк руин`, `Can't keep up`,
   `Ядерный подрыв №`, а из `Работа мода за 30 с` — только те, где наибольший тик больше 500 мс).
2. `longticks.txt` целиком (если больше 16 КБ — первые 16 КБ).
3. `gcsum.txt` целиком.
4. logscan: ошибки/исключения/`Mixin`/`AccessTransformer`/`DUMMY` — или «ноль».
Файлы `tick.jfr` (оба) — не присылать, они остаются в `nuke-tick-state/<v>/`; если координатор попросит — загрузить
в `/mnt/project-files/nuke/tick/` (SendUserFile; предел 20 МБ на файл — тогда `jfr scrub --include-events
jdk.ExecutionSample,jdk.GCLocker,jdk.GarbageCollection,jdk.SafepointBegin,jdk.ThreadPark` в меньший файл).

## Проходит, если
- оба прогона кончились сами с **кодом 0**, клиент не упал, logscan без новых ошибок;
- **A**: `longticks.txt` называет, где был поток сервера в самых долгих ядерных тиках (метод мода и стек), — это и
  есть ответ, что занимало 3,6 с. Повторился ли тик дольше 1 с — для сведения;
- **B**: ни одного «Ядерный тик» дольше 1000 мс и ни одного ядерного окна дольше 1000 мс в `longticks.txt` (кроме
  тика самого подрыва); строки медленного тика B называют разделы («очередь руин … (осмотрено N, ждали M)», «руины
  заранее»); руины обоих ударов встали как в A (`Руины подрыва №M: …`, `зона за волной — готово Y из Y`);
- **куча (B)**: значения «ДИАГ куча» лежат в пределах сотни МБ от «с огромными … после» у сборок того же времени
  в `gcsum.txt` (часы разные: в прошлых прогонах лог клиента — UTC+3, лог G1 — +02:00; сверять по порядку и величине).
