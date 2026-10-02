# Ноутбук: снимки памяти во время игры Артёма (кто держит выгруженные чанки)

Тред «Разбор сегодняшних игр на 2.4.1», п. 3 разбора `reports/sessions-2026-10-01/analysis.md`: за игру 01.10 живых
выгруженных `LevelChunk` (счёт AllTheLeaks) стало с 3655 до 47 546, старое поколение кучи выросло на 3–3,4 ГБ, после
ядерок — скачками. Прогон на копии сборки (задача leak-chunks) этого не повторил, поэтому снимаем в настоящей игре.
Артём разрешил: вопрос координатора 2 «Гистограммы памяти» в ленте проекта (06:07 UTC 02.10) и его ответ в 06:24 UTC
«#1 Оставляем, остальное согласно твоим советам». Расписание снимков он видел в треде.

Что делает задача: наблюдатель (своя служба systemd `airstrike-watch-game-histo`, не `airstrike-job-*`: он лёгкий
и часами ждёт игру, другим задачам не мешает) ждёт игру Артёма и, пока он в мире, снимает
`jcmd GC.class_histogram` (живые объекты по классам) и `GC.heap_info`:
- первый снимок — через 10 мин после входа в мир, дальше раз в 30 мин, ещё — через 5 мин после каждой ядерки
  (строка «Ядерный подрыв №N: … кт» в `latest.log`); не чаще раза в 10 мин, не больше 8 за запуск игры;
- после каждого снимка — сколько игра стояла (строка `Safepoint "GC_HeapInspection"` из safepoint-лога игры, его путь —
  из аргументов JVM); дольше 2,5 с — больше в этом запуске не снимает; safepoint-лога нет — не больше 4 снимков
  и стоп, если `jcmd` шёл дольше 8 с;
- игра закрылась — сводка `summary-s<N>.txt` по снимкам этого запуска; после запуска игры с 2+ снимками наблюдатель
  выходит сам (иначе ждёт следующего), предел службы — 24 ч.
С игрой — только `jcmd` (Java сборки `java-runtime-delta`, та же 21.0.7). Инстанс, миры и настройки Артёма только
читаются (`logs/latest.log`, safepoint-лог). На время подключения `jcmd` кладёт в папку игры служебный
`.attach_pid<N>` и сам его убирает. Если `jcmd` с хоста к игре в flatpak не подключится, наблюдатель пробует
`flatpak enter`; не вышло и так — пишет в лог и выходит, игру не трогает.

Папка задачи — `/mnt/data/projects/airstrike/mod/run/claude-work/game-histo` (новая; если она уже есть — остановиться
и сообщить координатору, ничего не удалять и не перезаписывать). **Остановить — только**
`systemctl --user stop airstrike-watch-game-histo`. Никаких `pkill`/`killall`/`kill` по имени, никаких `-f`/`--force`.

**Каждый блок ниже — одним вызовом, как написан**: состояние оболочки между вызовами не сохраняется.

## 1. Проверка (только чтение)
«стоп» — не запускать, сообщить координатору. Игра Артёма может быть уже запущена: это нормально.
```sh
P="$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"; MC="$P/instances/All of Create Aeronautics/minecraft"
test -d "$MC/logs" || echo "нет $MC/logs — стоп"
ls -l "$P/java/java-runtime-delta/bin/jcmd" || echo "нет jcmd — стоп"
"$P/java/java-runtime-delta/bin/java" -version 2>&1 | head -2
test -e /mnt/data/projects/airstrike/mod/run/claude-work/game-histo && echo "папка задачи уже есть — стоп"
systemctl --user list-units 'airstrike-watch-*' --state=active --no-legend
pgrep -a -x java | grep -F "$MC" | cut -c1-300
df -h /mnt/data/projects/airstrike/mod/run | tail -1
```
Активный `airstrike-watch-game-histo` — стоп (второй наблюдатель не нужен).

## 2. Скрипты
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/game-histo && mkdir -p "$W/out" && cd "${W:?}" && \
cat > "$W/watch.py" <<'EOF'
# Задача game-histo (tools/laptop-jobs/game-histo.md): снимки живой кучи игры Артёма, пока он в мире. С игрой — только
# jcmd. Аргументы: папка результатов, предел часов, сколько запусков игры с 2+ снимками отснять.
import datetime, gzip, json, os, re, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import summary

HOME = os.path.expanduser("~")
P = os.path.join(HOME, ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher")
MC = os.path.join(P, "instances", "All of Create Aeronautics", "minecraft")
JCMD = os.path.join(P, "java", "java-runtime-delta", "bin", "jcmd")
OUT, HOURS, SESSIONS = sys.argv[1], float(sys.argv[2]), int(sys.argv[3])
FIRST, EVERY, AFTER_NUKE, GAP, MAX_SHOTS, FREEZE_STOP, NO_JOIN = 600, 1800, 300, 600, 8, 2.5, 1500
JOIN = re.compile(r"logged in with entity id")
LEAVE = re.compile(r"Stopping server")
NUKE = re.compile(r"Ядерный подрыв №\d+: [\d.,]+ кт")
ATL = re.compile(r"\|- LevelChunk \(minecraft\): (\d+)")
SAFE = re.compile(r'^\[([^\]]+)\].*Safepoint "GC_HeapInspection".*Reaching safepoint: (\d+) ns.*Total: (\d+) ns')
os.makedirs(OUT, exist_ok=True)
logf = open(os.path.join(OUT, "watch.log"), "a", buffering=1)
def log(s):
    logf.write(time.strftime("%Y-%m-%d %H:%M:%S ") + s + "\n")
REAL = os.path.realpath(MC)

def game():
    for p in os.listdir("/proc"):
        if not p.isdigit():
            continue
        try:
            if open(f"/proc/{p}/comm").read().strip() != "java":
                continue
            cmd = open(f"/proc/{p}/cmdline", "rb").read().decode("utf-8", "replace").split("\0")
            try:
                cwd = os.path.realpath(os.readlink(f"/proc/{p}/cwd"))
            except OSError:
                cwd = ""
        except OSError:
            continue
        if cwd == REAL or any(MC in a for a in cmd):
            return int(p), cmd, cwd or REAL
    return None, None, None

def nspid(pid):
    for ln in open(f"/proc/{pid}/status"):
        if ln.startswith("NSpid:"):
            return int(ln.split()[-1])
    return pid

def jcmd(mode, pid, args, limit):
    cmd = ([JCMD, str(pid)] if mode == "host" else ["flatpak", "enter", str(pid), JCMD, str(nspid(pid))]) + args
    t = time.time()
    try:
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=limit)
        code, out = r.returncode, r.stdout
    except subprocess.TimeoutExpired as e:
        code, out = "тайм-аут", e.stdout or b""
    except OSError as e:
        code, out = f"ошибка {e}", b""
    return code, out, time.time() - t

def safepoint_log(cmd, cwd):
    for a in cmd:
        if a.startswith("-Xlog:") and "file=" in a:
            what = a[6:].split(":", 1)[0]
            if "safepoint" in what:
                path = a.split("file=", 1)[1].split(":", 1)[0].strip('"')
                return path if os.path.isabs(path) else os.path.join(cwd, path)
    return None

def freeze_since(path, t0):
    """Заморозка последнего GC_HeapInspection после t0, мс (дошли до safepoint, всего) — или None."""
    try:
        with open(path, "rb") as f:
            f.seek(max(0, os.path.getsize(path) - 262144))
            tail = f.read().decode("utf-8", "replace").splitlines()
    except OSError:
        return None
    for ln in reversed(tail):
        m = SAFE.match(ln)
        if m:
            try:
                at = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp()
            except ValueError:
                at = t0
            if at >= t0 - 1:
                return int(m.group(2)) / 1e6, int(m.group(3)) / 1e6
            return None
    return None

class Tail:
    def __init__(self, path):
        self.path, self.ino, self.pos = path, None, 0
    def lines(self):
        try:
            st = os.stat(self.path)
        except OSError:
            return []
        if st.st_ino != self.ino or st.st_size < self.pos:
            self.ino, self.pos = st.st_ino, 0
        with open(self.path, "rb") as f:
            f.seek(self.pos)
            data = f.read()
        cut = data.rfind(b"\n") + 1
        self.pos += cut
        return data[:cut].decode("utf-8", "replace").splitlines()

end = time.time() + HOURS * 3600
done, session, cur = 0, 0, None
log(f"наблюдатель запущен: {MC}, предел {HOURS} ч, запусков с 2+ снимками {SESSIONS}")

def finish(s):
    global done
    log(f"запуск игры {s['n']} кончился: снимков {s['shots']}")
    if s["shots"]:
        try:
            summary.write(OUT, f"s{s['n']}")
            log(f"сводка: summary-s{s['n']}.txt")
        except Exception as e:
            log(f"сводка не вышла: {e!r}")
    if s["shots"] >= 2:
        done += 1

while time.time() < end:
    pid, cmd, cwd = game()
    now = time.time()
    if cur and cur["pid"] != pid:
        finish(cur)
        cur = None
        if done >= SESSIONS:
            log("готово")
            sys.exit(0)
    if pid and not cur:
        session += 1
        cur = dict(n=session, pid=pid, seen=now, shots=0, last=None, join=None, joined=False, nuke=None, atl=None, stopped=False,
                   tail=Tail(os.path.join(MC, "logs", "latest.log")), first=True, mode=None,
                   safepoint=safepoint_log(cmd, cwd))
        log(f"игра: java {pid} (в своём пространстве {nspid(pid)}), safepoint-лог {cur['safepoint']}")
        time.sleep(20)
        for mode in ("host", "enter"):
            code, out, dt = jcmd(mode, pid, ["VM.version"], 60)
            log(f"jcmd ({mode}) VM.version: код {code}, {dt:.1f} с: {out.decode('utf-8', 'replace').strip()[-300:]!r}")
            if code == 0:
                cur["mode"] = mode
                break
        if not cur["mode"]:
            log("к игре не подключиться — выхожу, игру не трогаю")
            sys.exit(3)
    if cur:
        for ln in cur["tail"].lines():
            if JOIN.search(ln) and not cur["join"]:
                cur["join"], cur["joined"] = (now - FIRST + 120 if cur["first"] else now), True
                log("в мире" + (" (вход был до наблюдателя)" if cur["first"] else ""))
            elif LEAVE.search(ln):
                cur["join"] = None
                log("вышел из мира")
            elif NUKE.search(ln) and not cur["first"]:
                cur["nuke"] = now + AFTER_NUKE
                log("ядерка: " + ln[-120:])
            m = ATL.search(ln)
            if m:
                cur["atl"] = int(m.group(1))
        cur["first"] = False
        # входа в мир по логу так и не видно — снимать по времени от запуска игры
        join = cur["join"] or (cur["seen"] - FIRST + NO_JOIN if not cur["joined"] and now - cur["seen"] > NO_JOIN else None)
        limit = MAX_SHOTS if cur["safepoint"] else 4
        if join and not cur["stopped"] and cur["shots"] < limit:
            due, reason = (join + FIRST, "первый") if not cur["shots"] else (cur["last"] + EVERY, "по времени")
            if cur["nuke"] and max(cur["nuke"], cur["last"] + GAP if cur["last"] else 0) < due:
                due, reason = max(cur["nuke"], cur["last"] + GAP if cur["last"] else 0), "после ядерки"
            if now >= due:
                n = cur["shots"] + 1
                tag = f"s{cur['n']}-{n}-{time.strftime('%H%M')}"
                t0 = time.time()
                code, out, dt = jcmd(cur["mode"], pid, ["GC.class_histogram"], 600)
                with gzip.open(os.path.join(OUT, tag + ".histo.txt.gz"), "wb") as f:
                    f.write(out)
                code2, out2, _ = jcmd(cur["mode"], pid, ["GC.heap_info"], 60)
                open(os.path.join(OUT, tag + ".heapinfo.txt"), "wb").write(out2)
                time.sleep(3)
                fr = freeze_since(cur["safepoint"], t0) if cur["safepoint"] else None
                total = re.findall(rb"^Total\s+(\d+)\s+(\d+)", out, re.M)
                rec = dict(tag=tag, reason=reason, at=time.strftime("%Y-%m-%d %H:%M:%S"), pid=pid, code=str(code),
                           seconds=round(dt, 1), reach_ms=fr and round(fr[0], 1), freeze_ms=fr and round(fr[1], 1),
                           objects=int(total[0][0]) if total else None, bytes=int(total[0][1]) if total else None,
                           atl_levelchunk=cur["atl"])
                with open(os.path.join(OUT, "shots.jsonl"), "a") as f:
                    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                log(f"снимок {tag} ({reason}): код {code}, {dt:.1f} с, заморозка {rec['freeze_ms']} мс, "
                    f"живая куча {rec['bytes'] and rec['bytes'] // 2**20} МБ, AllTheLeaks LevelChunk {cur['atl']}")
                cur["shots"], cur["last"] = n, time.time()
                if cur["nuke"] and cur["nuke"] <= now:
                    cur["nuke"] = None
                if code != 0 or (fr and fr[1] / 1000 > FREEZE_STOP) or (not fr and dt > 8):
                    cur["stopped"] = True
                    log("дальше в этом запуске не снимаю: " + ("ошибка jcmd" if code != 0 else "долгая заморозка"))
    time.sleep(15)
if cur:
    finish(cur)
log("предел времени — выхожу")
EOF
cat > "$W/summary.py" <<'EOF'
# Сводка снимков одного запуска игры (game-histo): итоги, классы чанков, рост по классам и пакетам. ≤ 40 КБ.
import glob, gzip, json, os, re
RX = re.compile(r"^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)")
WATCH = ["net.minecraft.world.level.chunk.LevelChunk", "net.minecraft.world.level.chunk.LevelChunkSection",
         "net.minecraft.world.level.chunk.PalettedContainer", "net.minecraft.world.level.chunk.ProtoChunk",
         "net.minecraft.world.level.chunk.ImposterProtoChunk", "net.minecraft.server.level.ChunkHolder",
         "net.minecraft.world.level.chunk.DataLayer", "net.minecraft.world.level.levelgen.Heightmap",
         "net.minecraft.client.multiplayer.ClientLevel", "net.minecraft.server.level.ServerLevel",
         "net.minecraft.client.multiplayer.ClientChunkCache$Storage"]
def load(path):
    h = {}
    for ln in gzip.open(path, "rt", errors="replace"):
        m = RX.match(ln)
        if m:
            n, b = h.get(m.group(3), (0, 0))
            h[m.group(3)] = (n + int(m.group(1)), b + int(m.group(2)))
    return h
def mb(b):
    return f"{b / 2**20:,.1f}".replace(",", " ")
def write(out, s):
    files = sorted(glob.glob(os.path.join(out, f"{s}-*.histo.txt.gz")), key=lambda f: int(os.path.basename(f).split("-")[1]))
    shots = {}
    if os.path.exists(os.path.join(out, "shots.jsonl")):
        for ln in open(os.path.join(out, "shots.jsonl")):
            r = json.loads(ln)
            shots[r["tag"]] = r
    names = [os.path.basename(f)[:-len(".histo.txt.gz")] for f in files]
    H = [load(f) for f in files]
    L = []
    p = L.append
    p(f"снимки запуска {s}: {len(files)}")
    p("снимок            время                причина       jcmd, с  заморозка, мс  объектов      живая куча, МБ  AllTheLeaks LevelChunk")
    for nm, h in zip(names, H):
        r = shots.get(nm, {})
        n, b = sum(v[0] for v in h.values()), sum(v[1] for v in h.values())
        p(f"{nm:17s} {r.get('at', ''):20s} {r.get('reason', ''):13s} {r.get('seconds', ''):>7}  {str(r.get('freeze_ms')):>13s}  {n:>12,d}  {mb(b):>14s}  {r.get('atl_levelchunk')}".replace(",", " "))
    if not H:
        open(os.path.join(out, f"summary-{s}.txt"), "w").write("\n".join(L) + "\n")
        return
    p("\nклассы чанков и миров (экземпляров по снимкам; МБ на последнем):")
    extra = sorted(c for c in H[-1] if ("Chunk" in c or "chunk" in c) and c not in WATCH
                   and H[-1][c][0] - H[0].get(c, (0, 0))[0] >= 500)
    for c in WATCH + extra[:40]:
        row = "  ".join(f"{h.get(c, (0, 0))[0]:>9d}" for h in H)
        p(f"{row}  {mb(H[-1].get(c, (0, 0))[1]):>7s}  {c}")
    def grow(a, b, top):
        d = sorted(((b.get(c, (0, 0))[1] - a.get(c, (0, 0))[1], b.get(c, (0, 0))[0] - a.get(c, (0, 0))[0], c)
                    for c in set(a) | set(b)), reverse=True)
        for db, dn, c in [x for x in d if x[0] > 0][:top]:
            p(f"{mb(db):>9s} МБ {dn:>+12,d}  {c}".replace(",", " "))
    if len(H) > 1:
        p(f"\nрост {names[0]} → {names[-1]}, классы по байтам:")
        grow(H[0], H[-1], 60)
        def pkg(h):
            r = {}
            for c, (n, b) in h.items():
                k = c.lstrip("[L").split("$")[0]
                k = ".".join(k.split(".")[:3]) if "." in k else "(массивы и примитивы)"
                r[k] = (r.get(k, (0, 0))[0] + n, r.get(k, (0, 0))[1] + b)
            return r
        p(f"\nрост {names[0]} → {names[-1]} по пакетам (три уровня):")
        grow(pkg(H[0]), pkg(H[-1]), 30)
        mods = ("com.seibel.", "dev.ryanhcode.", "ua.zentix.", "xaero.", "com.simibubi.", "me.jellysquid.",
                "net.caffeinemc.", "net.irisshaders.", "dev.uncandango.")
        p("\nклассы модов, чьих экземпляров стало больше всего (первый → последний):")
        d = sorted(((H[-1].get(c, (0, 0))[0] - H[0].get(c, (0, 0))[0], c) for c in set(H[-1]) | set(H[0])
                    if c.lstrip("[L").startswith(mods)), reverse=True)
        for dn, c in d[:40]:
            if dn > 0:
                p(f"{H[0].get(c, (0, 0))[0]:>9d} → {H[-1].get(c, (0, 0))[0]:>9d}  {c}")
    text = "\n".join(L) + "\n"
    open(os.path.join(out, f"summary-{s}.txt"), "w").write(text[:40000])
EOF
python3 -m py_compile "$W/watch.py" "$W/summary.py" && ls -l "$W"
```

## 3. Запуск наблюдателя (возвращается сразу)
```sh
W=/mnt/data/projects/airstrike/mod/run/claude-work/game-histo && cd "${W:?}" && \
systemd-run --user --unit=airstrike-watch-game-histo --collect --same-dir -p RuntimeMaxSec=24h -p Nice=10 \
  -p OOMScoreAdjust=500 -- python3 "$W/watch.py" "$W/out" 23.5 1 && sleep 5 && \
systemctl --user is-active airstrike-watch-game-histo; cat "$W/out/watch.log"
```
`active` и строка «наблюдатель запущен» — сообщить координатору и закончить. Если игра уже идёт, через ~30 с
в `watch.log` — строка `jcmd (…) VM.version: код 0`; её тоже приложить (`cat "$W/out/watch.log"`). Код не 0 у обоих
способов — наблюдатель вышел сам, лог — координатору.

## 4. После игры (когда координатор попросит)
`systemctl --user is-active airstrike-watch-game-histo` (`inactive` — вышел сам, «готово» в конце `watch.log`).
Выгрузить в /mnt/project-files: `out/summary-s*.txt`, `out/shots.jsonl`, `out/watch.log`, `out/*.heapinfo.txt`,
`out/*.histo.txt.gz` (по 1–2 МБ). Если наблюдатель ещё ждёт, а Артём наигрался — сперва
`systemctl --user stop airstrike-watch-game-histo` (сводку уже отснятого запуска он пишет, когда игра закрывается).
