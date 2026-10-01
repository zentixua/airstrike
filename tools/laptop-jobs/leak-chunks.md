# Ноутбук: кто держит в памяти выгруженные чанки (одна задача, только замер)

Тред «Разбор сегодняшних игр на 2.4.1», п. 3 разбора `reports/sessions-2026-10-01/analysis.md`. Папки и задача —
`leak-chunks`, следы прошлых прогонов не трогаются. Код мода не меняется: нужен ответ, какой объект держит чанки.

Коммит **@SHA@** — полный SHA из сообщения координатора подставить во все блоки ниже вместо `@SHA@` (одна замена,
до шага 2; строки с `@SHA@` после неё быть не должно). Коммит берётся по SHA: он остаётся в истории ветки PR #182
и после её слияния — в `main`.

Что меряем. В игре Артёма 01.10 AllTheLeaks насчитал 38–51 тыс. выгруженных `LevelChunk`, которые ещё живы, старое
поколение кучи выросло на 3–3,4 ГБ; после ядерок число росло скачками (+15–18 тыс.), без них — медленнее (залпы,
телепорты). Здесь — та же сборка (копия инстанса Артёма, куча 12 ГБ и поколенческий ZGC, как у него), мир Greenfield
(копия мира фильма) и четыре замера живой кучи на одном и том же месте камеры:
- **heap0** — после входа;
- **heap1** — после облёта четырёх точек города без ударов (чанки грузятся и выгружаются);
- **heap2** — через 5 мин после ядерки 15 кт по городу (камера в 2,5 км);
- **heap3** — после облёта тех же точек над руинами.
На каждом — `jcmd GC.class_histogram` (сперва полная сборка: считаются только живые объекты), на последнем — ещё дамп
JFR с образцами старых объектов и путями от них до корней (`path-to-gc-roots`): по нему видно поле и класс, которые
держат чанк.

Условия: Артём не играет; одна тяжёлая задача на машине; только `tools/laptop_job.sh`; клиент только через
`tools/nested_kwin.sh` (это делает prod_client.py); большие файлы — в `mod/run/`. Инстанс Артёма, его миры и настройки
только читаются (копия инстанса — `prod_client.py --copy-only`). Инстанс фильма (`mod/run/film/instance/minecraft`)
и его мир `greenfield-film` только читаются: мир копируется в копию инстанса. Возвращать после прогона нечего.

**Каждый блок ниже — одним вызовом, как написан**: состояние оболочки между вызовами не сохраняется. Блоки с шага 2
начинаются с `W=… && cd "${W:?}"`; блок 1 работает до появления папки и пишет полные пути. Папка worktree — ровно
`/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks` (новая; если она уже есть — остановиться и сообщить координатору, ничего не удалять и не перезаписывать).

**Остановить задачу — только** `systemctl --user stop 'airstrike-job-leak-chunks-*'`. Никаких `pkill`/`killall`/`kill`
по имени, никакого `./gradlew --stop`, никаких `-f`/`--force`.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, строка «стоп» или «нет» — не запускать, сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks" && echo "папка /mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks уже есть — стоп"
FILM="/mnt/data/projects/airstrike/mod/run/film/instance/minecraft"
test -d "$FILM/saves/greenfield-film" && echo "мир фильма есть" || echo "мира фильма нет — стоп"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
ls "$MC/mods" | grep -i -E 'alltheleaks|distanthorizons' ; grep -E '^(renderDistance|simulationDistance):' "$MC/options.txt"
J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin"; ls -l "$J/jcmd" "$J/jfr" || echo "нет jcmd или jfr в JDK Prism — стоп"
du -sh "$MC/mods" "$MC/config" "$MC/shaderpacks" "$MC/resourcepacks" "$FILM/saves/greenfield-film" 2>/dev/null; df -h /mnt/data/projects/airstrike/mod/run
```
Свободно должно быть не меньше суммы размеров выше плюс 10 ГБ (JFR до 2 ГБ, гистограммы, логи), иначе — стоп.

## 2. Подготовка: worktree, копия инстанса с миром, скрипты, сборка
`mod/run/` в .gitignore, в свежем worktree его нет — создаётся сразу после `worktree add`. Копия инстанса Артёма
и мира фильма — в `$W/mod/run/leak-instance` (prod_client.py копирует mods/ без Airstrike, config/, options.txt,
shaderpacks/, resourcepacks/ и мир; источники не меняются).
```sh
cd /mnt/data/projects/airstrike && git fetch origin main @SHA@ && git cat-file -e '@SHA@^{commit}' && \
git worktree add "/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks" @SHA@ && \
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks" && cd "${W:?}" && mkdir -p "$W/mod/run/leak" && \
python3 tools/prod_client.py --copy-only --dir "$W/mod/run/leak-instance" --world "/mnt/data/projects/airstrike/mod/run/film/instance/minecraft/saves/greenfield-film" && \
git log --oneline -1 && ls "$W/mod/run/leak-instance" "$W/mod/run/leak-instance/saves" && du -sh "$W/mod/run/leak-instance" && \
grep -h -E '^\s*(flight_time|destruction_ms_per_tick|prep_ms_per_tick)\s*=' "$W/mod/run/leak-instance/config/airstrike-server.toml" "$W/mod/run/leak-instance/saves/greenfield-film/serverconfig/airstrike-server.toml" 2>/dev/null
```
Скрипты задачи — в `$W/mod/run/leak/` (наблюдатель, разбор гистограмм, разбор цепочек JFR, запуск):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks" && cd "${W:?}" && O="$W/mod/run/leak" && mkdir -p "$O" && \
cat > "$O/watch.py" <<'EOF'
# Наблюдатель задачи leak-chunks: на каждый снимок клиента heapN — гистограмма живой кучи (jcmd GC.class_histogram:
# сперва полная сборка) и GC.heap_info, на последнем — ещё дамп JFR с путями от образцов до корней. Игру не трогает
# ничем, кроме jcmd. Выходит сам, когда игры нет больше минуты.
import glob, os, subprocess, sys, time
d, out, jdk, *marks = sys.argv[1:]
start = os.path.getmtime(os.path.join(out, "run-start"))
real = os.path.realpath(d)
def log(s):
    print(time.strftime("%H:%M:%S", time.gmtime()), s, flush=True)
def game():
    for p in os.listdir("/proc"):
        if p.isdigit():
            try:
                if open(f"/proc/{p}/comm").read().strip() == "java" and os.readlink(f"/proc/{p}/cwd") == real:
                    return p
            except OSError:
                pass
def jcmd(pid, args, path, limit):
    t = time.time()
    try:
        with open(path, "w") as f:
            code = subprocess.run([os.path.join(jdk, "jcmd"), pid, *args], stdout=f, stderr=subprocess.STDOUT, timeout=limit).returncode
    except subprocess.TimeoutExpired:
        code = "тайм-аут"
    log(f"{args[0]} → {os.path.basename(path)}: код {code}, {time.time() - t:.0f} с, {os.path.getsize(path)} байт")
seen = None
for i, m in enumerate(marks):
    log(f"жду снимок {m}")
    while True:
        pid, now = game(), time.time()
        if pid:
            seen = now
        elif (seen and now - seen > 60) or (not seen and now - start > 1800):
            log("игры нет — выхожу")
            sys.exit(1)
        if pid and any(os.path.getmtime(f) > start for f in glob.glob(os.path.join(d, "screenshots", f"{m}_*.png"))):
            break
        time.sleep(1)
    log(f"снимок {m}, java {pid}")
    jcmd(pid, ["GC.class_histogram"], os.path.join(out, f"histo-{m}.txt"), 600)
    jcmd(pid, ["GC.heap_info"], os.path.join(out, f"heapinfo-{m}.txt"), 60)
    if i == len(marks) - 1:
        jcmd(pid, ["JFR.dump", "name=leak", "path-to-gc-roots=true", "filename=" + os.path.join(out, "leak.jfr")],
             os.path.join(out, "jfr-dump.txt"), 1200)
log("готово")
EOF
cat > "$O/histo.py" <<'EOF'
# Гистограммы живой кучи (jcmd GC.class_histogram) по снимкам: итоги, классы чанков, рост по классам и пакетам.
import os, re, sys
rx = re.compile(r"^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)")
names, H, total = [], [], []
for f in sys.argv[1:]:
    h, t = {}, (0, 0)
    for ln in open(f, errors="replace"):
        m = rx.match(ln)
        if m:
            n, b = h.get(m.group(3), (0, 0))
            h[m.group(3)] = (n + int(m.group(1)), b + int(m.group(2)))
        elif ln.startswith("Total"):
            p = ln.split()
            t = (int(p[1]), int(p[2]))
    names.append(os.path.basename(f)[6:-4])
    H.append(h)
    total.append(t)
mb = lambda b: f"{b / 2**20:,.1f}".replace(",", " ")
print("снимок        объектов       живая куча, МБ")
for nm, (n, b) in zip(names, total):
    print(f"{nm:10s} {n:14,d} {mb(b):>14s}".replace(",", " "))
WATCH = ["net.minecraft.world.level.chunk.LevelChunk", "net.minecraft.world.level.chunk.LevelChunkSection",
         "net.minecraft.world.level.chunk.PalettedContainer", "net.minecraft.world.level.chunk.ProtoChunk",
         "net.minecraft.world.level.chunk.ImposterProtoChunk", "net.minecraft.server.level.ChunkHolder",
         "net.minecraft.world.level.chunk.DataLayer", "net.minecraft.world.level.levelgen.Heightmap",
         "net.minecraft.client.multiplayer.ClientChunkCache$Storage"]
print("\nклассы чанков (экземпляров по снимкам; МБ на последнем):")
for c in WATCH + sorted(c for c in H[-1] if "Chunk" in c and c not in WATCH and H[-1][c][0] - H[0].get(c, (0, 0))[0] >= 1000):
    row = "  ".join(f"{h.get(c, (0, 0))[0]:>9d}" for h in H)
    print(f"{row}  {mb(H[-1].get(c, (0, 0))[1]):>6s}  {c}")
def grow(a, b, top):
    d = sorted(((b.get(c, (0, 0))[1] - a.get(c, (0, 0))[1], b.get(c, (0, 0))[0] - a.get(c, (0, 0))[0], c) for c in set(a) | set(b)), reverse=True)
    for db, dn, c in [x for x in d if x[0] > 0][:top]:
        print(f"{mb(db):>8s} МБ {dn:>+12,d}  {c}".replace(",", " "))
for i in range(1, len(H)):
    print(f"\nрост {names[0]} → {names[i]}, классы по байтам:")
    grow(H[0], H[i], 40 if i == len(H) - 1 else 15)
def pkg(h):
    s = {}
    for c, (n, b) in h.items():
        k = c.lstrip("[L").split("$")[0]
        k = ".".join(k.split(".")[:3]) if "." in k else "(массивы и примитивы)"
        s[k] = (s.get(k, (0, 0))[0] + n, s.get(k, (0, 0))[1] + b)
    return s
print(f"\nрост {names[0]} → {names[-1]} по пакетам (три уровня):")
grow(pkg(H[0]), pkg(H[-1]), 25)
EOF
cat > "$O/chains.py" <<'EOF'
# Образцы старых объектов JFR (jfr print --json --events jdk.OldObjectSample) → цепочки от объектов выбранных классов
# до корня, по числу образцов; корни и классы всех образцов. Аргументы: json, классы (через «/», как в JFR).
import collections, json, sys
ev = json.load(open(sys.argv[1]))["recording"]["events"]
targets = sys.argv[2:]
def short(t):
    return t.replace("/", ".").split(".")[-1] if t.startswith(("java/", "it/unimi/", "com/google/")) else t.replace("/", ".")
samples, roots, kinds = [], collections.Counter(), collections.Counter()
for e in ev:
    v = e["values"]
    o, steps = v.get("object"), []
    while o:
        t = (o.get("type") or {}).get("name") or "?"
        r = o.get("referrer") or {}
        f = (r.get("field") or {}).get("name") if r.get("field") else ("[i]" if r.get("array") else ("…%s…" % r.get("skip") if r.get("skip") else ""))
        steps.append((t, f))
        o = r.get("object")
    root = v.get("root") or {}
    rd = ("%s %s %s" % (root.get("system") or "", root.get("type") or "", (root.get("description") or "")[:90])).strip()
    roots[rd] += 1
    if steps:
        kinds[short(steps[0][0])] += 1
    samples.append((steps, rd))
print(f"образцов {len(ev)}")
for target in targets:
    chains, hit = collections.Counter(), 0
    for steps, rd in samples:
        idx = next((i for i, (t, _) in enumerate(steps) if t == target), None)
        if idx is None:
            continue
        hit += 1
        # у шага i поле f — поле объекта i+1, в котором лежит объект i
        chain = [short(steps[idx][0])] + ["%s.%s" % (short(steps[i + 1][0]), steps[i][1]) for i in range(idx, min(len(steps) - 1, idx + 14))]
        if len(steps) - 1 > idx + 14:
            chain.append("… ещё %d" % (len(steps) - 1 - idx - 14))
        chains[" ← ".join(chain) + "  ‖ " + rd] += 1
    print(f"\n{target}: в цепочке у {hit} образцов; цепочки (образцов — от объекта к корню ‖ корень):")
    for c, n in chains.most_common(25):
        print(f"{n:5d}  {c}")
print("\nкорни всех образцов:")
for r, n in roots.most_common(15):
    print(f"{n:5d}  {r}")
print("\nклассы образцов:")
for k, n in kinds.most_common(15):
    print(f"{n:5d}  {k}")
EOF
cat > "$O/run.sh" <<'EOF'
# задача leak-chunks: клиент со всей сборкой и наблюдатель кучи рядом (tools/laptop-jobs/leak-chunks.md)
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks" && cd "${W:?}" || exit 2
D="$W/mod/run/leak-instance"; O="$W/mod/run/leak"
CAM="tp @s -2399.5 160 -495.5 -90 -5"
TOUR="tp @s 136.5 250 -495.5 0 60;wait:600;tp @s 1136.5 250 -495.5 0 60;wait:600;tp @s 136.5 250 504.5 0 60;wait:600;tp @s -863.5 250 -1495.5 0 60;wait:600"
CMDS="hud:off;gamemode spectator;$CAM;wait:1800;shot:heap0;atl force_refresh;wait:1200;$TOUR;$CAM;wait:2400;shot:heap1;atl force_refresh;wait:1200"
CMDS="$CMDS;airstrike nuke at 136.5 69 -495.5 15 air;wait:nuke;wait:6000;shot:heap2;atl force_refresh;wait:1200"
CMDS="$CMDS;$TOUR;$CAM;wait:2400;shot:heap3;atl force_refresh;wait:3600"
mkdir -p "$O/jfr-repo"
python3 "$O/watch.py" "$D" "$O" "$JAVA_HOME/bin" heap0 heap1 heap2 heap3 > "$O/watch.log" 2>&1 &
WP=$!
python3 tools/prod_client.py commands --no-copy --dir "$D" --world greenfield-film --seconds 2700 \
  --jvm=-Xms12288m --jvm=-Xmx12288m --jvm=-XX:+UseZGC --jvm=-XX:+ZGenerational \
  "--jvm=-XX:FlightRecorderOptions=repository=$O/jfr-repo,old-object-queue-size=2048" \
  --jvm=-XX:StartFlightRecording=name=leak,settings=profile,disk=true,maxsize=1g \
  --prop "airstrike.commands=$CMDS"
code=$?
wait "$WP"; echo "наблюдатель: код $?"
exit "$code"
EOF
python3 -m py_compile "$O/watch.py" "$O/histo.py" "$O/chains.py" && bash -n "$O/run.sh" && ls -l "$O"
```
Репозиторий JFR — в `$O/jfr-repo` на диске: по умолчанию JFR пишет в `/tmp`, а он на ноутбуке в ОЗУ.

Сборка jar сценария — отдельно, до задачи (в фоне, тайм-аут вызова не меньше 25 мин); код не 0 — сообщить:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks" && cd "${W:?}/mod" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
timeout -k 60 20m ./gradlew scenarioJar -q --console=plain; echo "код $?"; ls -l build/scenario-libs/
```

## 3. Прогон (один; в фоне, тайм-аут вызова 60 мин)
Камера в спектаторе в 2,5 км к западу от центра Greenfield (136.5 69 −495.5), смотрит на восток. Шаги — в `run.sh`:
вход и 90 с — heap0; облёт четырёх точек над городом по 30 с, возврат, 2 мин на выгрузку — heap1; ядерка 15 кт,
воздушная, 5 мин после подрыва — heap2; тот же облёт над руинами, возврат, 2 мин — heap3; ещё 3 мин на дамп JFR —
и выход. После каждого снимка — команда AllTheLeaks `atl force_refresh` (его счёт живых выгруженных чанков — в лог).
Всего ~23 мин игры плюс полёт МБР, загрузка сборки и мира; `--seconds 2700` и `timeout 60m` — пределы.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
touch mod/run/leak/run-start && echo "начало: $(date -u +%T) UTC" && \
timeout -k 60 60m tools/laptop_job.sh leak-chunks -- bash mod/run/leak/run.sh; code=$?; echo "код $code, конец: $(date -u +%T) UTC"; \
case "$code" in 124|137) systemctl --user stop 'airstrike-job-leak-chunks-*';; esac; true
```
Конец — `SCENARIO done` в `$W/mod/run/leak-instance/logs/latest.log`, клиент выходит сам: **код 0**. Код 124 или 143 —
предел. Наблюдатель (`mod/run/leak/watch.log`) пишет время каждого замера и выходит сам, когда игры нет больше минуты.
На замер (полная сборка кучи 12 ГБ) игра может замереть на секунды — это ожидаемо.

## 4. Наблюдатель
Прогон жив: в `$W/mod/run/leak-instance/logs/latest.log` растёт лог, появляются строки `SCENARIO /…`, в конце
`SCENARIO done`; в `$W/mod/run/leak/watch.log` — замеры heap0…heap3. Смерть прогона — координатору в течение 5 мин,
с причиной (последние 40 строк лога, `crash-reports/`, `watch.log`). Если `SCENARIO done` есть, а через 3 мин после
него java прогона ещё жива — один раз `jstack` (только чтение):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks" && cd "${W:?}" && \
R=$(realpath "$W/mod/run/leak-instance") && PID=$(for p in $(pgrep -x java); do [ "$(readlink "/proc/$p/cwd")" = "$R" ] && echo "$p"; done | head -1) && [ -n "$PID" ] && echo "java прогона: $PID" && \
timeout 60 "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jstack" "$PID" > mod/run/leak/jstack.txt; \
echo "код $?"; wc -lc mod/run/leak/jstack.txt
```
Координатору — первые 200 строк потока «Server thread» из `jstack.txt`.

## 5. Выжимка
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/leak-chunks" && cd "${W:?}" && O="$W/mod/run/leak" && D="$W/mod/run/leak-instance" && L="$D/logs/latest.log" && J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin" && \
ls -l "$O" && python3 "$O/histo.py" "$O"/histo-heap*.txt > "$O/histo-summary.txt" 2>&1; wc -lc "$O/histo-summary.txt"; \
[ -f "$O/leak.jfr" ] && "$J/jfr" print --json --events jdk.OldObjectSample "$O/leak.jfr" > "$O/oldobj.json"; echo "jfr код $?"; ls -l "$O/oldobj.json"; \
python3 "$O/chains.py" "$O/oldobj.json" net/minecraft/world/level/chunk/LevelChunk net/minecraft/world/level/chunk/LevelChunkSection net/minecraft/world/level/chunk/ProtoChunk > "$O/chains.txt" 2>&1; wc -lc "$O/chains.txt"; \
grep -E 'AllTheLeaks.*(LevelChunk|ProtoChunk|heap|memory usage)|\[CHAT\].*Chunk|SCENARIO (/|commands|done)|Ядерный подрыв №|Руины удара №|зона за волной|дальние кольца|дальше руины заранее не строятся|Can.t keep up|has crashed|emergencySaveAndCrash' "$L" | cut -c1-400 > "$O/lines.txt"; wc -lc "$O/lines.txt"; \
grep -E 'Major Collection|Allocation Stall' "$D/logs/gc.log" | tail -40 > "$O/gc-major.txt"; wc -l "$O/gc-major.txt"; \
python3 tools/logscan.py "$L" --all > "$O/logscan.txt"; wc -lc "$O/logscan.txt"; du -sh "$D" "$O"
```
Всё остаётся в `$W/mod/run/` (копия инстанса, JFR, гистограммы): удалять — только по слову Артёма в треде «Ноутбук».

## Что прислать координатору
Текстом (≈ до 40 КБ; больше 60 КБ — двумя сообщениями, не урезая):
0. Код выхода прогона, время начала и конца (шаг 3), `watch.log` целиком, вывод `du -sh` из шага 5.
1. `histo-summary.txt` целиком.
2. `chains.txt` целиком (больше 20 КБ — первые 150 строк и раздел «корни всех образцов»).
3. `lines.txt` (больше 12 КБ — строки AllTheLeaks, `[CHAT]`, `Ядерный подрыв №`, `Руины удара №` и `SCENARIO done`).
4. `gc-major.txt`; `heapinfo-heap*.txt` целиком (по ~1 КБ).
5. logscan: ошибки, исключения, `Mixin`, `DUMMY` — или «ноль».

## Готово, если
- есть все четыре `histo-heap*.txt` и `leak.jfr`, а в `chains.txt` строка «образцов N» с N > 0;
- клиент не упал: нет `has crashed`, `emergencySaveAndCrash`; прогон кончается сам с **кодом 0**.
Выводы по замеру делает тред, не ноутбук.
