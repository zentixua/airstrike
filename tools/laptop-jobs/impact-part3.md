# Ноутбук: замер ударов, корень 1, часть 3 (порции блоков по времени, урон у аппарата порциями)

Тред «Подвисание при попадании ракеты». Ветка `claude/impact-stall-8bu5gw`, коммит **<SHA>** (полный SHA и его первые
7 знаков `<SHA7>` подставляет координатор; задание берётся из того же коммита: `git show <SHA>:tools/laptop-jobs/impact-part3.md`).
Два прогона подряд, по одному, те же, что у f5681b7: (А) ракета и залп из 30 шахедов в городе Greenfield; (Б) взрыв
ракеты рядом с аппаратом Sable.

Условия: Артём не играет; одна тяжёлая задача на машине; только `tools/laptop_job.sh`; клиент только через
`tools/nested_kwin.sh` (это делает prod_client.py); большие файлы — в `mod/run/` worktree. Инстанс, миры и настройки
Артёма не трогать (prod_client.py копирует мир в `mod/run/prod` worktree). Только копирование: ничего не удалять,
`git worktree remove` не делать, `rm -rf`/`--force` нет. Шагов с температурой нет.

**Каждый блок ниже — одним вызовом, как написан**: состояние оболочки между вызовами не сохраняется. Блоки с шага 3
начинаются с `W=… && cd "${W:?}"`. Папка worktree — ровно
`/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>` (новая; если она уже есть — остановиться и сообщить
координатору, ничего не удалять и не перезаписывать).

**Остановить задачу — только** `systemctl --user stop 'airstrike-job-impact-<SHA7>-*'` (прогон А) или
`systemctl --user stop 'airstrike-job-impact-craft-<SHA7>-*'` (прогон Б). Никаких `pkill`/`killall`/`kill` по имени,
никакого `./gradlew --stop`.

## 1. Проверка перед запуском
Любой вывод у первых трёх команд, «нет» у `test`, строка «уже есть — стоп» или мира нет в списке — не запускать,
сообщить координатору.
```sh
pgrep -a -x java | grep -Ei 'neoforge|minecraft'
pgrep -a -x prismlauncher
systemctl --user list-units 'airstrike-job-*' --state=active --no-legend
J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jfr"; test -x "$J" && echo "jfr есть" || echo "jfr нет"
test -e "/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && echo "папка /mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7> уже есть — стоп"
MC="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/All of Create Aeronautics/minecraft"
ls "$MC/saves"                                  # в списке есть «Greenfield v0.5.4»
du -sh "$MC/saves/Greenfield v0.5.4"; df -h /mnt/data/projects/airstrike/mod/run   # свободно ≥ размер мира + 5 ГБ
```

## 2. Подготовка
```sh
cd /mnt/data/projects/airstrike && git fetch origin claude/impact-stall-8bu5gw && \
git worktree add "/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" <SHA> && W="/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && cd "${W:?}" && \
mkdir -p "$W/mod/run/prod/jfr-repo" && git log --oneline -1 && ls -d mod/run/prod/jfr-repo
```
`git log` должен показать `<SHA7>`. Отдельно `./gradlew` не запускать: prod_client.py сам собирает jar сценария.

## 3. Прогон А (в фоне, тайм-аут вызова не меньше 3600 с)
Те же шаги, что у 2a31bd8 и f5681b7: встать там, где стоял ZentixUA, ракета по 250 63 -147, перенос к месту ENOTzRPG, 30 шахедов
с разбросом 50 туда же. **Ожидание залпа — фиксированное**: сценарий `strike-profile` умеет только шаги через
`airstrike.profile.gap` тиков клиента и не умеет ждать строк лога, поэтому `gap=4800` (240 с) после каждого шага,
и после последнего (шахедов) тоже 240 с до `SCENARIO done`. Итого ≈ 30 с + 4 × 240 с ≈ 16,5 мин после входа в мир,
плюс сборка jar, копия мира и загрузка; `--seconds 2700` — предел.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && mkdir -p "$W/mod/run/prod/jfr-repo" && \
tools/laptop_job.sh impact-<SHA7> -- python3 tools/prod_client.py strike-profile --world "Greenfield v0.5.4" --seconds 2700 \
  --prop 'airstrike.profile.steps=tp -668 87 -151; missile 1 0 250 63 -147; tp -271 71 -1141; drone 30 50 -272 72 -1142' \
  --prop airstrike.profile.gap=4800 \
  --jvm="-XX:StartFlightRecording=filename=$W/mod/run/prod/strike.jfr,settings=profile,maxsize=2g,dumponexit=true" \
  --jvm="-XX:FlightRecorderOptions:repository=$W/mod/run/prod/jfr-repo"
```
Наблюдение: в `$W/mod/run/prod/logs/latest.log` идут `SCENARIO strike-profile second`; всего 4 строки
`SCENARIO strike-profile step`; в конце `SCENARIO done`. Смерть прогона — координатору в течение 5 мин с причиной
(последние 40 строк лога, `crash-reports/`).

## 4. Обработка прогона А (до прогона Б: прогон Б пишет свой latest.log)
Записать два скрипта (один вызов):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && cd "${W:?}" && cat > "$W/mod/run/windows.py" <<'EOF'
# Горячие стеки потока «Server thread» в окнах [шаг, шаг + 60 с]: stdin — `jfr print --json`, аргумент — latest.log.
# Запускать с TZ=Etc/GMT-3 (часы лога Minecraft на ноутбуке — UTC+3). На шаг: 10 методов по верхнему кадру и 10 стеков.
import collections, datetime as dt, json, re, sys
steps = []  # (секунды от полуночи по часам лога, имя шага)
for l in open(sys.argv[1], encoding="utf-8", errors="replace"):
    if "SCENARIO strike-profile step" in l:
        m = re.search(r"(\d\d):(\d\d):(\d\d)\.(\d+)\]", l)
        steps.append((int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + float("0." + m[4]), re.search(r"step «([^»]*)»", l)[1]))
if not steps: sys.exit("в логе нет строк SCENARIO strike-profile step")
WIN = 60
stat = [[0, collections.Counter(), collections.Counter()] for _ in steps]
base = None
starts = []

def local(t):  # "2026-09-30T15:49:29.096321935Z" (UTC) -> местное время (TZ), без fromisoformat (Python < 3.11)
    m = re.match(r"(\d+)-(\d+)-(\d+)T(\d+):(\d+):(\d+)(?:\.(\d+))?(Z|[+-]\d\d:?\d\d)?", t)
    d = dt.datetime(*map(int, m.groups()[:6]), int((m[7] or "0")[:6].ljust(6, "0")))
    z = m[8]
    if z and z != "Z":
        sign = 1 if z[0] == "+" else -1
        d -= sign * dt.timedelta(hours=int(z[1:3]), minutes=int(z[-2:]))
    if z: d = d.replace(tzinfo=dt.timezone.utc).astimezone().replace(tzinfo=None)
    return d

def name(f):
    m = f["method"]
    return m["type"]["name"].replace("/", ".") + "." + m["name"] + ":" + str(f.get("lineNumber", ""))

def event(text):
    global base
    e = json.loads(text)
    if e.get("type") != "jdk.ExecutionSample": return
    v = e["values"]
    if (v.get("sampledThread") or {}).get("javaName") != "Server thread": return
    t = local(v["startTime"])
    if base is None:
        base = dt.datetime(t.year, t.month, t.day)
        first = (t - base).total_seconds()
        day = 86400 if steps[0][0] < first - 3600 else 0  # первая выборка до полуночи, шаги — после
        for s, _ in steps:  # переход через полночь между шагами: время шага меньше прошлого — следующий день
            if starts and s + day < starts[-1]: day += 86400
            starts.append(s + day)
    x = (t - base).total_seconds()
    fr = (v.get("stackTrace") or {}).get("frames") or []
    if not fr: return
    for i, s in enumerate(starts):
        if s <= x < s + WIN:
            st = stat[i]; st[0] += 1; st[1][name(fr[0])] += 1; st[2][" <- ".join(name(f) for f in fr)[:300]] += 1

buf, inside = [], False
for line in sys.stdin:
    if not inside:
        if line.strip().endswith('"events": [{'): inside, buf = True, ["{"]
        continue
    if line.rstrip() in ("    }, {", "    }]"):
        buf.append("}"); event("".join(buf))
        if line.rstrip() == "    }]": break
        buf = ["{"]
    else:
        buf.append(line)
for (s, step), (n, tops, stacks) in zip(steps, stat):
    print(f"\n=== шаг «{step}»: выборок потока сервера за {WIN} с: {n}")
    print("-- методы (верхний кадр):")
    for k, c in tops.most_common(10): print(f"{100 * c / max(n, 1):5.1f}% {k}")
    print("-- стеки (15 кадров, до 300 символов):")
    for k, c in stacks.most_common(10): print(f"{100 * c / max(n, 1):5.1f}% {k}")
EOF
cat > "$W/mod/run/results.py" <<'EOF'
# Выжимка лога прогона: шаги; самый долгий tick/period за весь прогон; на окно шага — second в [шаг − 5 с, шаг + 30 с]
# и до 15 самых долгих tick/period от 100 мс в [шаг − 10 с, шаг + 240 с]; затем полностью (до 80 строк каждого вида):
# «Попадания (» (тики дольше 50 мс, с шагами взрывов), «Итог удара (», «Итог серии попаданий (», «Взрыв: блок»
# (блок дольше 10 мс), «Can't keep up».
# Строки — время и суть, без префикса лога, до 600 символов. Аргумент — latest.log.
import re, sys
lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
def sec(l):
    m = re.search(r"(\d\d):(\d\d):(\d\d)\.(\d+)\]", l)
    return int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + float("0." + m[4]) if m else None
def short(l, n=200):
    m = re.search(r"(\d\d:\d\d:\d\d\.\d+)\]", l)
    body = re.sub(r"^.*?\]: ", "", l).replace("SCENARIO strike-profile ", "")
    body = re.sub(r" after «[^»]*»", "", body)
    return ((m[1] + " ") if m else "") + body[:n]
steps, day, prev = [], 0, None
for l in lines:
    if "SCENARIO strike-profile step" in l:
        s = sec(l)
        if prev is not None and s + day < prev: day += 86400
        prev = s + day; steps.append((s + day, l))
def when(l, ref):
    s = sec(l)
    if s is None: return None
    d0 = ref - ref % 86400
    return min((s + d for d in (d0 - 86400, d0, d0 + 86400)), key=lambda x: abs(x - ref))
def ms(l):
    m = re.search(r"strike-profile (tick|period) (\d+) ms", l)
    return int(m[2]) if m else 0
print("== шаги")
for _, l in steps: print(short(l))
top = sorted((x for x in lines if ms(x) > 0), key=lambda x: -ms(x))[:3]
print("\n== самые долгие tick/period за весь прогон (3)")
for x in top: print(short(x))
for s, l in steps:
    near = [x for x in lines if "SCENARIO strike-profile" in x and (w := when(x, s)) is not None and s - 10 <= w < s + 240]
    print(f"\n== окно: {short(l)}")
    for x in near:
        if "strike-profile second" in x and s - 5 <= when(x, s) < s + 30: print(short(x))
    slow = sorted((x for x in near if ms(x) >= 100), key=lambda x: -ms(x))
    print(f"-- tick/period от 100 мс за [−10, +240 с]: {len(slow)} (до 15 самых долгих)")
    for x in slow[:15]: print(short(x))
ok = lambda l: "[CHAT]" not in l and not re.search(r"\]: (\[Not Secure\] )?<", l)
for title, pat in (("Попадания (", "Попадания ("), ("Итог удара (", "Итог удара ("), ("Итог серии попаданий (", "Итог серии попаданий ("), ("Взрыв: блок", "Взрыв: блок"), ("Can't keep up", "Can't keep up")):
    hits = [l for l in lines if pat in l and ok(l)]
    print(f"\n== {title}: {len(hits)} (до 80)")
    for l in hits[:80]: print(short(l, 600))
EOF
echo записано
```
JFR (в фоне, тайм-аут вызова 20 мин). Код не 0 — сбой, сообщить:
```sh
set -o pipefail; W="/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && cd "${W:?}/mod/run/prod" && J="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/jfr" && \
"$J" scrub --include-events jdk.ExecutionSample strike.jfr server.jfr && \
"$J" print --json --stack-depth 15 server.jfr | TZ=Etc/GMT-3 python3 ../windows.py logs/latest.log > ../windows.txt; echo "код $?"; wc -lc ../windows.txt
```
Лог (прогон А):
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && cd "${W:?}" && python3 mod/run/results.py mod/run/prod/logs/latest.log > mod/run/results.txt; wc -lc mod/run/results.txt
W="/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && cd "${W:?}" && python3 tools/logscan.py mod/run/prod/logs/latest.log --all | sed -n '/^== Отставание сервера/,/^== /p' | head -30 > mod/run/lag.txt; wc -lc mod/run/lag.txt
W="/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && cd "${W:?}" && cp mod/run/prod/logs/latest.log mod/run/latest-A.log && ls -l mod/run/latest-A.log
```
Файлы > 40 КБ не урезать — сообщить размер координатору.

## 5. Прогон Б: взрыв рядом с аппаратом (информационный, в фоне, тайм-аут вызова не меньше 1800 с)
Тот же каталог копии (`--no-copy`: мир не копируется заново; prod_client.py сам меняет в копии только свой
`mods/airstrike-*.jar`), сценарий `commands`: команды идут от игрока Dev через чат, между командами 40 тиков.
Шаги: перенос туда, где стоял ZentixUA; 400 тиков на готовые чанки; в 200 блоках к северу (z −351) на поверхности —
брусок 3×5×3 из дубовых досок и `sable assemble area` по нему (как GameTest `craftBlocksGoInFirstUnit`); ракета
в 4 блока от аппарата (сила 20, охват лучей ~27 блоков — аппарат в охвате); 4800 тиков (240 с) на полёт и итог.
Сценарий не умеет ждать строку «Итог удара», поэтому ожидание фиксированное — 240 с.
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && cd "${W:?}" && export JAVA_HOME="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta" && \
tools/laptop_job.sh impact-craft-<SHA7> -- python3 tools/prod_client.py commands --no-copy --dir "$W/mod/run/prod" --world "Greenfield v0.5.4" --seconds 1500 \
  --prop 'airstrike.commands=gamemode creative @s;tp @s -668 87 -151;wait:400;execute positioned -668 0 -351 positioned over motion_blocking_no_leaves run fill ~ ~ ~ ~2 ~4 ~2 minecraft:oak_planks;execute positioned -668 0 -351 positioned over motion_blocking_no_leaves positioned ~ ~-5 ~ run sable assemble area ~ ~ ~ ~2 ~4 ~2;execute positioned -668 0 -351 positioned over motion_blocking_no_leaves run airstrike salvo missile 1 0 at ~4 ~ ~;wait:4800'
```
Конец — `SCENARIO done` в `$W/mod/run/prod/logs/latest.log`. Если в чате (`[CHAT]` в логе) «Unknown or incomplete
command» или отказ в правах (в копии мира нет команд) — прогон Б не повторять, так и записать в результат.
Выжимка:
```sh
W="/mnt/data/projects/airstrike/mod/run/claude-work/impact-<SHA7>" && cd "${W:?}" && L=mod/run/prod/logs/latest.log && \
{ echo "== команды и чат"; grep -E "SCENARIO /|\[CHAT\]" "$L" | cut -c1-300 | head -30; \
  for p in "Попадания (" "Итог удара (" "Итог серии попаданий (" "Взрыв: блок" "ExplosionHandoffMixin" "Can't keep up" "SCENARIO done"; do echo "== $p"; grep -F "$p" "$L" | grep -v "\[CHAT\]" | cut -c1-600 | head -20; done; } > mod/run/craft.txt; wc -lc mod/run/craft.txt
```

## 6. Результат
Загрузить (SendUserFile) четыре файла под этими именами, каждый ≤ 40 КБ (больше — не урезать, сообщить размер):
- `mod/run/results.txt` → `impact-<SHA7>-results.txt`
- `mod/run/lag.txt` → `impact-<SHA7>-lag.txt`
- `mod/run/windows.txt` → `impact-<SHA7>-windows.txt`
- `mod/run/craft.txt` → `impact-<SHA7>-craft.txt`

И одной строкой координатору: пути загрузки, код выхода обоих прогонов, есть ли `SCENARIO done` в обоих.
Логи, `strike.jfr`, `server.jfr`, `latest-A.log` остаются в `$W/mod/run/` (в .gitignore), никуда не отправлять и
не удалять.

## Что смотрит тред
Пороги (у f5681b7: единица ракеты 65,6 мс «взрывы: блоки» — порция из 16 блоков; у аппарата 126 мс):
- Прогон А: каждая единица (строки «Попадания», «самая долгая единица» в «Итог удара») ≤ ~30 мс; «взрывы: блоки» —
  около 5 мс плюс один блок. Исключение — блоки из строк «Взрыв: блок X снимался N мс» (не больше 5 на удар): чей
  блок, есть ли блок-сущность — отдельная задача, если такие есть. В «Итог удара» — «блоков дольше 10 мс N»;
  `ванильных взрывов` у прогона А — 0.
- Прогон Б: «Итог удара» у точки аппарата — `ванильных взрывов` ≥ 1; строки «ExplosionHandoffMixin не встал» нет.
  Единица ванильного пути: ожидание ~72 мс (Start, лучи с Sable, Detonate, блоки аппаратов; урон и снимок — своими
  единицами). Шаг «блоки аппаратов» теперь отдельно от «раздел»: если он ~40 мс (снятие блоков плота Sable), единица
  ~112 мс, и это записывается как известное — снятие блоков плота остаётся в единице лучей.
- Tick/period и Can't keep up — для сравнения с f5681b7 (самый долгий тик ракеты 89 мс).
