#!/usr/bin/env python3
"""Конвейер трейлера на Newisle без человека: дубли по порядку истории (scenes/newisle-takes.json) — запись повтором на
копии мира, проверка по данным игры (produce.py: итог и лог; take_checks.py: пуски в один тик, отставание сервера,
полёт МБР, сирена, стая в бинокль — запись; поворот камеры, ось ракеты, тряска, FPV, МБР в кадре, застывшие кадры —
вывод), вывод камерами из повтора, пересъёмка запасной камерой, черновик монтажа 720p.

    python3 tools/trailer/produce.py run    --config МАШИНА.json [--work ПАПКА] [--takes T02,T04,T24] [--fault T02=kill …]
    python3 tools/trailer/produce.py live   --config МАШИНА.json --take T03 [--standin]
    python3 tools/trailer/produce.py search --config МАШИНА.json [--only terrace,grad,spots,boat,pointblank]
    python3 tools/trailer/produce.py night  --config МАШИНА.json [--takes T02,T04,T24] [--fault T02=kill …]
    python3 tools/trailer/produce.py status --config МАШИНА.json

run    — цепочка: дубль `chain` идёт на копии снимка после прошлого дубля цепочки (первый — на копии мастера),
         `branch:<дубль>` — на копии снимка того дубля (цепочка его не берёт); дубль с Артёмом в цепочке — только удары,
         без записи (его планы — из живого дубля), а мир его входа держится до живого дубля. После записи — вывод планов дубля и проверка; не прошёл — та же
         копия входа и запасная камера (INCOMING_NEWISLE_VARIANT 1, 2), после `retries` пересъёмок — пропуск. Конец —
         черновик монтажа из прошедших планов (newisle_cut.py). Повторный запуск продолжает с места (state.json).
live   — живой дубль Артёма на копии мира, с которого цепочка шла в его дубль (цепочки ещё не было — мастера): окно
         игры на его экране, подсказки, начало по Enter (`--standin` — бот вместо него, слепой прогон P1).
search — поиски P1, каждая проба — на своей копии мастера; найденное — scenes/newisle-found.json (постановщик берёт
         его места и размер залпа раньше своих).
night  — ночь П2: проба трёх дублей с порчей (по умолчанию T02=kill, T04=disk, T24=camera; своя папка <work>/smoke),
         прошла — вся цепочка, черновик и чистовой; не прошла — «stopped, waiting». Пройденная проба не повторяется.
status — файл статуса (пять строк).

Ограждения (limits в newisle-takes.json): пауза вместо падения, пока свободно меньше `disk_floor_gb` (+ размер копии
мира), памяти меньше `ram_floor_gb` + `client_gb` или идёт игра Артёма (`pause_if_running` машины); срок дубля —
`take_factor` × замер (до замера — `minutes` дубля), срок ночи — `night_hours`; сторож снимает клиент без движения
(папка дубля не растёт, в логе нет строк, кроме пульса кучи) дольше `stall_seconds`, с живой кучей выше `heap_share` и замечает упавший;
вышедший или снятый клиент считается ушедшим после его градл-демона (одиночный демон Gradle 8, и при daemon=false,
дописывает мир ещё секунды); запись и удаление — только в папках работы, путь под защищёнными корнями (`protected`, мастер) — отказ. Неожиданное (дубль
сказал «stopped», ошибка конвейера) — «остановлен, жду» в статусе и выход с кодом 3: дальше решает человек.

Проверка ограждений (дымовой прогон P2): `--fault ДУБЛЬ=kill` — клиент убивается через `fault_kill_s` (сторож, повтор),
`=disk` — первая проверка места видит меньше порога (пауза и продолжение), `=camera` — первая камера дубля под землёй
(INCOMING_NEWISLE_FAULT=camera: проверка ловит, пересъёмка запасной). Итог проверки — smoke.json в папке работы.

Машина (JSON): repo, master, work (`--work` сильнее: новая папка — новый прогон с начала), saves (по умолчанию
<repo>/mod/run/scenario/saves), protected [пути], record и render [команда от repo; render null — прогон вслепую: только
запись и проверка по данным, без вывода и монтажа], env {переменные}, pause_if_running [подстроки командной строки java
игры Артёма], world_gb (размер копии мира, по умолчанию — замер мастера), fault_kill_s (90), pause_minutes, limits
{сверх limits дублей}, commit (начало SHA: другой коммит в repo — стоп), status (файл статуса, по умолчанию
<work>/status.txt), cut [команда монтажа без --draft/--final; false — без монтажа], final (false — только черновик:
чистовой — по слову Артёма, когда он посмотрел черновик), gpu_lock (файл замка видеокарты:
запись и вывод — под ним, держит другая задача — пауза), triage [команда разбора не снятого дубля, не ждётся; в
аргументах {take} {rec} {work} {repo} {reason} {out}].
"""
import argparse
import fcntl
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time

import take_checks

HERE = os.path.dirname(os.path.abspath(__file__))
SCENES = os.path.join(HERE, "scenes")
STOPPED_EXIT = 3
CRASH = ("Exception in server tick loop", "Encountered an unexpected exception", "java.lang.OutOfMemoryError",
         "Exception ticking world", "---- Minecraft Crash Report ----")
HEAP_RE = re.compile(r"NEWISLE heap (\d+)%")
CAMERA_RE = re.compile(r"SCENARIO film camera (\S+):")
ETA_TOLERANCE = 1.0      # секунд: удар (или пролёт) не дальше от своего времени плана


class Stopped(Exception):
    """Остановлен, жду: дальше — решение человека."""


# ---------------------------------------------------------------- машина

class Machine:
    """Всё, что конвейер делает с машиной: время, место, память, процессы, файлы. Тест подменяет его своим."""

    def now(self):
        return time.time()

    def sleep(self, s):
        time.sleep(s)

    def disk_free_gb(self, path):
        return shutil.disk_usage(path).free / 2 ** 30

    def mem_available_gb(self):
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) / 2 ** 20
        return 0.0

    def running(self, patterns):
        """Игра Артёма: процесс java, в командной строке которого есть одна из подстрок, — его pid и строка; нет — None.
        Только java: подстроку несёт и оболочка, что запустила проверку (`pgrep -f '…'`, этот конвейер с настройкой)."""
        if not patterns:
            return None
        me = os.getpid()
        for pid in os.listdir("/proc"):
            if not pid.isdigit() or int(pid) == me:
                continue
            try:
                with open(f"/proc/{pid}/cmdline", "rb") as f:
                    argv = f.read().split(b"\0")
            except OSError:
                continue
            if not argv or not os.path.basename(argv[0].decode("utf-8", "replace")).startswith("java"):
                continue
            cmd = b" ".join(argv).decode("utf-8", "replace")
            for p in patterns:
                if p in cmd:
                    return pid, cmd[:200]
        return None

    def client_running(self):
        """Клиент съёмки с градл-обвязкой (java с org.gradle в командной строке) — pid и строка; нет — None.
        Gradle 8 и при daemon=false держит сборку в одиночном демоне — он вне группы сессии запускателя."""
        return self.running(["org.gradle"])

    def copy_tree(self, src, dst):
        shutil.copytree(src, dst, symlinks=True)

    def remove_tree(self, path):
        shutil.rmtree(path)

    def tree_gb(self, path):
        total = 0
        for root, _, files in os.walk(path):
            for n in files:
                try:
                    total += os.lstat(os.path.join(root, n)).st_size
                except OSError:
                    pass
        return total / 2 ** 30

    def newest_mtime(self, paths):
        """Время последнего изменения файлов под путями (движение клиента: лог, повторы, журналы)."""
        best = 0.0
        for p in paths:
            if os.path.isfile(p):
                best = max(best, os.path.getmtime(p))
            elif os.path.isdir(p):
                for root, _, files in os.walk(p):
                    for n in files:
                        try:
                            best = max(best, os.path.getmtime(os.path.join(root, n)))
                        except OSError:
                            pass
        return best

    def lock(self, path):
        """Замок видеокарты (flock на файле): свободен — открытый файл, держит другой — None."""
        f = open(path, "a+")
        try:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            f.close()
            return None
        return f

    def unlock(self, f):
        if f is not None:
            f.close()

    def git_head(self, repo):
        r = subprocess.run(["git", "-C", repo, "rev-parse", "HEAD"], capture_output=True, text=True)
        return r.stdout.strip() if r.returncode == 0 else ""

    def start(self, cmd, cwd, env, log):
        out = open(log, "ab")
        return Proc(subprocess.Popen(cmd, cwd=cwd, env=env, stdout=out, stderr=subprocess.STDOUT, start_new_session=True), out)


class Proc:
    """Запускатель клиента в своей сессии: снять — всю группу (обёртки xvfb, KWin, запускатель градла), TERM, через
    20 с KILL. Сам JVM-клиент идёт под одиночным градл-демоном вне группы — его уход ждёт Conveyor.wait_client_gone."""

    def __init__(self, popen, out):
        self.popen, self.out = popen, out

    def poll(self):
        code = self.popen.poll()
        if code is not None and not self.out.closed:
            self.out.close()
        return code

    def kill(self, hard=False):
        try:
            os.killpg(self.popen.pid, signal.SIGKILL if hard else signal.SIGTERM)
        except ProcessLookupError:
            return
        if hard:
            self.popen.wait()
            return
        for _ in range(40):
            if self.popen.poll() is not None:
                break
            time.sleep(0.5)
        else:
            try:
                os.killpg(self.popen.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            self.popen.wait()
        self.poll()


# ---------------------------------------------------------------- данные и состояние

def load_json(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    os.replace(tmp, path)


def under(path, root):
    path, root = os.path.realpath(path), os.path.realpath(root)
    return path == root or path.startswith(root.rstrip(os.sep) + os.sep)


class Guard:
    """Запись и удаление — только под папками работы, никогда под защищёнными корнями (мастер, golden, игра Артёма)."""

    def __init__(self, protected, writable):
        self.protected = [p for p in protected if p]
        self.writable = [p for p in writable if p]

    def check(self, path, what):
        for p in self.protected:
            if under(path, p):
                raise Stopped(f"отказ: {what} {path} — под защищённым {p}")
        if not any(under(path, w) for w in self.writable):
            raise Stopped(f"отказ: {what} {path} — не в папках работы")
        return path


def save_dir(master):
    """Папка мира с level.dat: копия для съёмки держит его в world/ (рядом DistantHorizons.toml и карта MapSyncer
    для сервера ноутбука) — копия всей папки открывалась бы клиентом как мир без level.dat («Failed to load world»)."""
    inner = os.path.join(master, "world")
    if not os.path.exists(os.path.join(master, "level.dat")) and os.path.exists(os.path.join(inner, "level.dat")):
        return inner
    return master


class Conveyor:
    def __init__(self, cfg, machine=None, takes_file=None, faults=None, log=print):
        self.m = machine or Machine()
        self.cfg = cfg
        self.repo = cfg["repo"]
        self.master = save_dir(cfg["master"])
        self.only = None
        self.work = cfg["work"]
        self.saves = cfg.get("saves") or os.path.join(self.repo, "mod", "run", "scenario", "saves")
        self.data = load_json(takes_file or os.path.join(SCENES, "newisle-takes.json"))
        self.limits = dict(self.data["limits"])
        self.limits.update(cfg.get("limits", {}))
        self.takes = {t["id"]: t for t in self.data["takes"]}
        self.order = [t["id"] for t in self.data["takes"]]
        self.guard = Guard([cfg["master"], self.master] + cfg.get("protected", []), [self.work, self.saves])
        self.faults = dict(faults or {})
        self.fault_used = set()
        self.echo = log
        self.last_status = ("starting", "")
        os.makedirs(self.work, exist_ok=True)
        self.log_file = os.path.join(self.work, "produce.log")
        self.state_file = os.path.join(self.work, "state.json")
        self.state = load_json(self.state_file, {"takes": {}, "measured": {}, "started": None})
        self.status_file = cfg.get("status") or os.path.join(self.work, "status.txt")
        self.events = []          # что сработало из ограждений (дымовой прогон)
        self.night_end = None
        self.current = ""

    # ---------------------------------------------------------------- статус

    def status(self, state, note=""):
        """Пять строк: этап; дубли сняты / не сняты / осталось; срок по замерам; последняя ошибка; чего ждёт."""
        st = self.state["takes"]
        done = [t for t in self.order if st.get(t, {}).get("status") in ("passed", "chained")]
        skipped = [t for t in self.order if st.get(t, {}).get("status") == "skipped"]
        left = [t for t in self.plan_order if st.get(t, {}).get("status") not in ("passed", "skipped", "chained")]
        night = "" if self.night_end is None else f", ночи осталось {max(0, self.night_end - self.m.now()) / 3600:.1f} ч"
        if state == "stopped, waiting":
            self.state["last_error"] = note
            note = "решения человека"
        lines = [
            f"stage: {state}" + (f" — {self.current}" if self.current else ""),
            f"takes: сняты {len(done)}, не сняты {len(skipped)}" + (f" ({', '.join(skipped)})" if skipped else "")
            + f", осталось {len(left)}" + (f" (дальше {left[0]})" if left else ""),
            f"eta: ~{self.eta(left) / 3600:.1f} ч по замерам{night}",
            f"last error: {self.state.get('last_error') or '—'}",
            f"waits for: {note or '—'}",
        ]
        tmp = self.status_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        os.replace(tmp, self.status_file)
        self.last_status = (state, note)
        self.beat = self.m.now()
        self.say(" | ".join(lines))

    def save(self):
        write_json(self.state_file, self.state)

    def say(self, line):
        """Строка — в вывод и в produce.log папки работы (сторож ночи читает его вместе со статусом)."""
        self.echo(line)
        with open(self.log_file, "a", encoding="utf-8") as f:
            f.write(time.strftime("%H:%M:%S ", time.localtime(self.m.now())) + line + "\n")

    def eta(self, left):
        """Сколько ещё: оценка дубля × темп снятых (время всего дубля с выводом и пересъёмками на минуту оценки)."""
        spent = self.state.get("spent", {})
        est = sum(60 * float(self.takes[t].get("minutes", 10)) for t in spent if t in self.takes)
        rate = sum(spent.values()) / est if est else 2.0
        return rate * sum(60 * float(self.takes[t].get("minutes", 10)) for t in left if t in self.takes)

    def next_take(self):
        for t in self.plan_order:
            if self.state["takes"].get(t, {}).get("status") not in ("passed", "skipped", "chained"):
                return t
        return None

    # ---------------------------------------------------------------- ограждения

    def wait_resources(self, need_gb):
        """Пауза, пока нет места, памяти или идёт игра Артёма; ночь кончилась — остановка."""
        pause = 60 * float(self.cfg.get("pause_minutes", self.limits.get("pause_minutes", 30)))
        while True:
            why = self.blocked(need_gb)
            if not why:
                return
            if why.startswith("мало места") and self.state.get("released"):
                world = self.state["released"].pop(0)
                self.remove(os.path.join(self.saves, world))
                self.events.append(("prune", self.current_take, world))
                self.save()
                continue
            self.events.append(("pause", self.current, why))
            self.status("paused", why)
            if self.night_end is not None and self.m.now() + pause > self.night_end:
                raise Stopped(f"ночь кончилась на паузе: {why}")
            self.m.sleep(pause)

    def blocked(self, need_gb):
        game = self.m.running(self.cfg.get("pause_if_running", []))
        if game:
            return f"идёт игра Артёма (pid {game[0]})"
        free = self.m.disk_free_gb(self.work)
        if self.faults.get(self.current_take) == "disk" and ("disk", self.current_take) not in self.fault_used:
            self.fault_used.add(("disk", self.current_take))
            free = 0.0
        floor = float(self.limits["disk_floor_gb"]) + need_gb
        if free < floor:
            return f"мало места: свободно {free:.1f} ГБ, нужно {floor:.1f}"
        mem = self.m.mem_available_gb()
        need = float(self.limits["ram_floor_gb"]) + float(self.limits["client_gb"])
        if mem < need:
            return f"мало памяти: {mem:.1f} ГБ, нужно {need:.1f}"
        return None

    # ---------------------------------------------------------------- миры

    def world_gb(self):
        if "world_gb" in self.cfg:
            return float(self.cfg["world_gb"])
        if "world_gb" not in self.state:
            self.state["world_gb"] = round(self.m.tree_gb(self.master), 2)
            self.save()
        return self.state["world_gb"]

    def input_for(self, take, intact=False):
        """Мир входа дубля: мастер, снимок после прошлого дубля цепочки или снимок дубля ветки."""
        t = self.takes[take]
        if intact:
            return self.master
        src = t.get("from", "chain")
        if src.startswith("branch:"):
            snap = self.state["takes"].get(src[7:], {}).get("snapshot")
            if not snap:
                raise Stopped(f"{take}: нет снимка {src[7:]} для ветки")
            return os.path.join(self.saves, snap)
        snap = self.state.get("chain")
        return os.path.join(self.saves, snap) if snap else self.master

    def copy_world(self, src, name):
        dst = self.guard.check(os.path.join(self.saves, name), "копия мира в")
        if os.path.exists(dst):
            self.remove(dst)
        self.m.copy_tree(src, dst)
        return name

    def remove(self, path):
        self.guard.check(path, "удаление")
        if os.path.exists(path):
            self.m.remove_tree(path)

    # ---------------------------------------------------------------- запуск клиента

    def env(self, extra):
        env = dict(os.environ)
        env.update({k: str(v) for k, v in self.cfg.get("env", {}).items()})
        env["INCOMING_TRAILER_MAP"] = "newisle"
        env["INCOMING_NEWISLE_SCENES"] = SCENES
        env.update({k: str(v) for k, v in extra.items()})
        return env

    def limit_s(self, take):
        measured = self.state["measured"].get(take)
        base = measured if measured else 60 * float(self.takes[take].get("minutes", 10)) if take in self.takes else 1800
        return float(self.limits["take_factor"]) * base

    def gpu(self, kind):
        """Запись и вывод — под замком видеокарты машины (`gpu_lock`): его же берёт любая другая задача с клиентом игры
        (GLM, ролики), иначе два клиента делили бы видеокарту и память. Держит другой — пауза, как на игре Артёма."""
        path = self.cfg.get("gpu_lock")
        if not path or kind not in ("record", "render"):
            return None
        pause = 60 * float(self.cfg.get("pause_minutes", self.limits.get("pause_minutes", 30)))
        while True:
            f = self.m.lock(path)
            if f is not None:
                return f
            why = f"видеокарта занята другой задачей ({path})"
            self.events.append(("pause", self.current, why))
            self.status("paused", why)
            if self.night_end is not None and self.m.now() + pause > self.night_end:
                raise Stopped(f"ночь кончилась на паузе: {why}")
            self.m.sleep(pause)

    def client(self, kind, take, cmd, env, rec, limit):
        """Клиент до выхода под сторожем: срок, движение, куча, игра Артёма. Итог — (код или None, причина снятия)."""
        lock = self.gpu(kind)
        try:
            return self.watch(kind, take, cmd, env, rec, limit)
        finally:
            self.m.unlock(lock)

    def triage(self, take, rec, reason):
        """Разбор не снятого дубля другой моделью (`triage` машины, например glm.sh ask — только чтение): к утру рядом с
        записью — `triage.md`, короткая причина и предложение правки; решение — за Claude. Конвейер его не ждёт."""
        cmd = self.cfg.get("triage")
        if not cmd or not rec or not os.path.isdir(rec):
            return
        sub = {"take": take, "rec": rec, "work": self.work, "repo": self.repo, "reason": reason.replace("\n", " ")[:500],
               "out": os.path.join(rec, "triage.md")}
        try:
            self.m.start([a.format(**sub) for a in cmd], self.repo, self.env({}), os.path.join(rec, "triage.log"))
            self.events.append(("triage", take, sub["out"]))
        except (OSError, KeyError, IndexError, ValueError) as e:
            self.say(f"разбор {take} не запущен: {e!r}")

    def wait_client_gone(self):
        """Клиент ушёл целиком, а не только его запускатель: JVM-клиент — под одиночным градл-демоном (Gradle 8 заводит
        его и при daemon=false) вне группы сессии, после снятия тот догоняет воркер ещё секунды и дописывает мир —
        раньше копию мира не удалять (налетало на «Directory not empty»)."""
        end = self.m.now() + float(self.limits.get("client_gone_s", 90))
        while self.m.client_running() is not None and self.m.now() < end:
            self.m.sleep(1)
        if self.m.client_running() is not None:
            self.events.append(("client-lingers", self.current_take, "клиент не ушёл после снятия"))

    def watch(self, kind, take, cmd, env, rec, limit):
        log = os.path.join(rec, f"{kind}.log")
        proc = self.m.start(cmd, self.repo, env, log)
        start = self.m.now()
        last = start
        kill_at = None
        if self.faults.get(take) == "kill" and ("kill", take, kind) not in self.fault_used and kind == "record":
            self.fault_used.add(("kill", take, kind))
            kill_at = start + float(self.cfg.get("fault_kill_s", 90))
        stall = float(self.limits["stall_seconds"])
        heap = 100 * float(self.limits["heap_share"])
        pos = 0
        while True:
            code = proc.poll()
            if code is not None:
                self.wait_client_gone()
                return code, None
            now = self.m.now()
            if kill_at is not None and now >= kill_at:
                self.events.append(("fault-kill", take, kind))
                proc.kill(hard=True)
                kill_at = None
                continue
            # движение — папка дубля или строка лога, кроме пульса кучи: клиент на экране «Failed to load world»
            # пишет только пульс раз в минуту и иначе стоял бы до срока
            pos, pct, said = tail_heap(log, pos)
            moved = max(self.m.newest_mtime([rec]), now if said else 0)
            if moved > last:
                last = moved
            why = None
            if now - start > limit:
                why = f"срок {limit / 60:.0f} мин"
            elif now - last > stall:
                why = f"нет движения {stall / 60:.0f} мин"
            else:
                if pct is not None and pct > heap:
                    why = f"живая куча {pct}% > {heap:.0f}%"
                else:
                    game = self.m.running(self.cfg.get("pause_if_running", []))
                    if game:
                        why = "игра Артёма"
            if why:
                self.events.append(("watchdog", take, why))
                proc.kill()
                self.wait_client_gone()
                return None, why
            if now - self.beat >= 300:      # пульс: статус свежий и посреди долгой записи (сторож ночи ждёт его)
                state, note = self.last_status
                self.status(state, "" if state == "stopped, waiting" else note)
            self.m.sleep(5)

    # ---------------------------------------------------------------- дубль

    def attempt(self, take, n, variant, src, chain_only=False, live=False, standin=False, last=False):
        """Одна запись дубля на свежей копии входа. Итог — dict: ok, reason, world, rec, result, problems, warnings.
        Проверки take_checks — мягкие: пересъёмка, пока попытки есть; на последней (`last`) — предупреждение, а не
        пропуск дубля (пересъёмка их не всегда лечит: сирена или ось ракеты — от мода, не от дубля)."""
        self.current = f"{take} попытка {n + 1}/{int(self.limits['retries']) + 1} (камера {variant})"
        self.current_take = take
        self.wait_resources(self.world_gb())
        world = self.copy_world(src, f"nw-{take}-a{n}")
        rec = self.guard.check(os.path.join(self.work, "takes", take, f"a{n}"), "запись в")
        if os.path.exists(rec):
            self.remove(rec)
        os.makedirs(rec)
        result_file = os.path.join(rec, "result.json")
        extra = {"INCOMING_NEWISLE_TAKE": take, "INCOMING_NEWISLE_VARIANT": variant, "INCOMING_NEWISLE_RESULT": result_file,
                 "INCOMING_WORLD": world, "INCOMING_TRAILER_DIR": rec}
        if chain_only:
            extra["INCOMING_TRAILER_SHOTS"] = "-"
        if live:
            extra["INCOMING_NEWISLE_LIVE"] = 1
            extra["INCOMING_LIVE_WINDOW"] = 1
        if standin:
            extra["INCOMING_NEWISLE_STANDIN"] = 1
        if self.faults.get(take) == "camera" and variant == 0:
            extra["INCOMING_NEWISLE_FAULT"] = "camera"
        self.status("recording")
        t0 = self.m.now()
        limit = 3600 * 2 if live else self.limit_s(take)
        code, killed = self.client("record", take, self.cfg["record"], self.env(extra), rec, limit)
        seconds = self.m.now() - t0
        result = load_json(result_file)
        out = {"world": world, "rec": rec, "result": result, "seconds": seconds, "code": code}
        if killed:
            out.update(ok=False, reason=f"клиент снят: {killed}", interrupted=killed == "игра Артёма")
            return out
        if result is None:
            out.update(ok=False, reason=f"клиент вышел без итога (код {code})")
            return out
        if result.get("status") == "stopped":
            raise Stopped(f"{take}: {result.get('reason')}")
        problems = check_take(result, read(os.path.join(rec, "record.log")), self.takes[take])
        problems += check_hud(os.path.join(rec, "hud.jsonl"))
        soft = take_checks.record_problems(rec, self.takes[take], result)
        warnings = soft if last else []
        if not last:
            problems += soft
        out.update(ok=not problems, reason="; ".join(problems), problems=problems, warnings=warnings)
        return out

    def render(self, take, rec, plans, last=False):
        """Вывод планов из повторов записи; итог — {план: (PASS|FAIL, причина)}. Снятый сторожем вывод продолжается с
        того, что уже в best.txt (INCOMING_FILM_RESUME), один раз; снятый игрой Артёма — после паузы, не в счёт.
        PASS плана проверяется по данным вывода (take_checks): поворот камеры, ось ракеты, тряска, FPV, МБР,
        застывшие кадры — провал делает план FAIL, и дубль переснимается."""
        if not plans:
            return {}
        resumed = 0
        while True:
            self.status("rendering")
            extra = {"INCOMING_TRAILER_DIR": rec, "INCOMING_FILM_SHOTS": ",".join(plans)}
            if os.path.exists(os.path.join(rec, "render", "best.txt")):
                extra["INCOMING_FILM_RESUME"] = 1
            code, killed = self.client("render", take, self.cfg["render"], self.env(extra), rec, self.limit_s(take) * 2)
            best = parse_best(os.path.join(rec, "render", "best.txt"))
            if all(p in best for p in plans):
                break
            if killed == "игра Артёма":
                self.wait_resources(0)
                continue
            if resumed:
                break
            resumed += 1
            self.events.append(("render-resume", take, killed or f"код {code}"))
        out = {}
        for p in plans:
            verdict, why, path = best.get(p, (None, None, None))
            if verdict is None:
                out[p] = ("FAIL", f"не выведен ({killed or f'код {code}'})")
                continue
            if verdict == "PASS" and path and path != "-":
                problems = take_checks.render_problems(path, p, **self.render_flags(take, rec, p, path))
                if problems and last:
                    self.say(f"{take}: вывод {p} — последняя попытка, в монтаж с предупреждением: {'; '.join(problems)}")
                    out[p] = (verdict, "предупреждение: " + "; ".join(problems))
                    continue
                if problems:
                    self.say(f"{take}: вывод {p} не прошёл — {'; '.join(problems)}")
                    out[p] = ("FAIL", "; ".join(problems))
                    continue
            out[p] = (verdict, why)
        return out

    def render_flags(self, take, rec, plan, path):
        """Какие проверки вывода нужны плану: FPV, МБР; onboard, HUD и ядерные — без тряски."""
        shot = next((s for s in self.takes[take].get("shots", []) if plan in s.get("plans", [])), {})
        flights, nuke = take_checks.read_events(os.path.join(rec, "replays", f"{plan}.events.jsonl"))
        return {"fpv": shot.get("camera") == "fpv" or os.path.basename(path).startswith("fpv"),
                "icbm": any(f.weapon in take_checks.ICBM_WEAPONS for f in flights),
                "shake": not (nuke or shot.get("camera") == "onboard" or shot.get("hud"))}

    def take(self, take):
        """Дубль цепочки до итога: запись, проверка, вывод, пересъёмка запасной камерой, пропуск."""
        t = self.takes[take]
        chain_only = "artem" in t
        st = self.state["takes"].setdefault(take, {"attempts": []})
        src = self.input_for(take)
        if chain_only and "before" not in st:
            # мир, с которого цепочка идёт в дубль Артёма: его живой дубль снимается на копии этого мира
            st["before"] = None if src == self.master else os.path.basename(src)
            self.save()
        retries = int(self.limits["retries"])
        # продолжение после остановки: пересъёмки, что уже были, в счёт
        variant = sum(1 for x in st["attempts"] if not x.get("interrupted"))
        kept = st.get("kept")
        t0 = self.m.now()
        for n in range(len(st["attempts"]), 20):
            if variant > retries:
                break
            a = self.attempt(take, n, variant, src, chain_only=chain_only, last=variant >= retries)
            if a.get("interrupted"):
                st["attempts"].append({"variant": variant, "ok": False, "reason": a["reason"], "interrupted": True})
                self.save()
                self.remove(os.path.join(self.saves, a["world"]))
                self.wait_resources(self.world_gb())
                continue
            plans = {}
            if a["ok"] and not chain_only and self.cfg.get("render"):
                plans = self.render(take, a["rec"], covered(a["result"], t), last=variant >= retries)
                failed = {p: r for p, r in plans.items() if r[0] != "PASS"}
                if failed:
                    a["ok"] = False
                    a["reason"] = "; ".join(f"{p}: {r[1] or r[0]}" for p, r in failed.items())
            st["attempts"].append({"variant": variant, "ok": a["ok"], "reason": a["reason"], "warnings": a.get("warnings") or [],
                                   "seconds": round(a["seconds"]),
                                   "world": a["world"], "rec": a["rec"], "plans": plans})
            if a["ok"] and take not in self.state["measured"]:
                self.state["measured"][take] = a["seconds"]
            saved = bool(a["result"] and a["result"].get("saved"))
            if a["ok"]:
                if kept and kept != a["world"]:
                    self.remove(os.path.join(self.saves, kept))
                kept = a["world"]
                st.update(status="chained" if chain_only else "passed", snapshot=kept)
                break
            self.say(f"{take}: не прошёл — {a['reason']}")
            self.state["last_error"] = f"{take}: {a['reason']}"[:300]
            # падение игры — не из известных отказов: второе на том же дубле — стоп (не жечь ночь пересъёмками)
            if any("в логе «" in p for p in a.get("problems") or ()):
                st["crashes"] = st.get("crashes", 0) + 1
                if st["crashes"] >= 2:
                    self.save()
                    raise Stopped(f"{take}: игра падает второй раз ({a['reason'][:200]})")
            # мир, где удары уже легли, — запасной снимок цепочки, если ни одна попытка не пройдёт
            if saved and kept is None:
                kept = st["kept"] = a["world"]
            else:
                self.remove(os.path.join(self.saves, a["world"]))
            variant += 1
            self.save()
        else:
            raise Stopped(f"{take}: слишком много попыток")
        if st.get("status") not in ("passed", "chained"):
            st.update(status="skipped", snapshot=kept)
            last = st["attempts"][-1] if st["attempts"] else {}
            self.triage(take, last.get("rec"), "; ".join(a.get("reason", "") for a in st["attempts"]))
        self.state.setdefault("spent", {})[take] = self.state.get("spent", {}).get(take, 0) + round(self.m.now() - t0)
        src = t.get("from", "chain")
        if src == "chain" and st.get("snapshot"):
            prev = self.state.get("chain")
            self.state["chain"] = st["snapshot"]
            self.release(prev)
        elif src.startswith("branch:"):
            self.release(st.get("snapshot"))                 # ветка — лист: дальше её снимок никому не вход
            base = self.state["takes"].get(src[7:], {}).get("snapshot")
            if base != self.state.get("chain"):
                self.release(base)
        self.save()
        return st

    def render_live(self):
        """Живые дубли (вечер с Артёмом) — вывод их планов в начале ночи, потом их копия мира — прочь."""
        if not self.cfg.get("render"):
            return
        for take, st in self.state.get("live", {}).items():
            last = st["attempts"][-1] if st["attempts"] else None
            if not last or not last["ok"] or last.get("plans") is not None:
                continue
            self.current = f"{take} живой: вывод"
            self.current_take = take
            last["plans"] = self.render(take, last["rec"], covered(None, self.takes[take]))
            self.release(last["world"])
            self.release(self.state["takes"].get(take, {}).get("before"))
            self.save()

    def cut(self, final=False):
        """Монтаж из прошедших планов (newisle_cut.py по листу аниматика): черновик 720p30 — всегда, без неснятых;
        чистовой — только когда сняты все планы листа."""
        what = "final" if final else "draft"
        self.current = "чистовой монтаж" if final else "черновик монтажа"
        self.current_take = "cut"
        self.status("cutting")
        out = self.guard.check(os.path.join(self.work, "cut"), "монтаж в")
        os.makedirs(out, exist_ok=True)
        cmd = self.cfg.get("cut") or ["python3", "tools/trailer/newisle_cut.py", "--work", self.work]
        code, killed = self.client(f"cut-{what}", "cut", list(cmd) + [f"--{what}"], self.env({}), out, 3 * 3600)
        if killed or code:
            raise Stopped(f"монтаж {what} не собрался ({killed or f'код {code}'}): {os.path.join(out, f'cut-{what}.log')}")

    def missing_plans(self):
        """Планы листа аниматика, которых нет среди прошедших (чистовой их ждёт)."""
        import newisle_cut
        sheet = load_json(os.path.join(SCENES, "newisle-animatic.json"))
        dirs = newisle_cut.shot_dirs(self.state, self.data)
        return [s["plan"] for s in sheet["shots"] if "plan" in s and s["plan"] not in dirs]

    def preflight(self):
        """Не тот коммит, нет мастера или неизвестный дубль — стоп до первой копии."""
        want = self.cfg.get("commit")
        if want:
            head = self.m.git_head(self.repo)
            if not head.startswith(want):
                raise Stopped(f"коммит {head[:12] or '?'} вместо {want}")
        if not os.path.exists(os.path.join(self.master, "level.dat")):
            raise Stopped(f"нет мира {self.master}")
        unknown = [t for t in self.only or () if t not in self.takes]
        if unknown:
            raise Stopped("неизвестные дубли: " + ", ".join(unknown))

    def release(self, world):
        """Снимок, который больше не вход ни одному дублю впереди (его планы выведены), — в очередь на удаление. Удаляет
        его проверка места (старые первыми), когда места мало; до того мир лежит: перевывод плана по замечанию берёт LOD
        DH из мира, где план записан."""
        if not world or world == self.state.get("chain"):
            return
        for t, tk in self.takes.items():           # вход дубля Артёма — до его живого дубля
            live = self.state.get("live", {}).get(t, {}).get("attempts") or ()
            if "artem" in tk and self.state["takes"].get(t, {}).get("before") == world and not any(a["ok"] for a in live):
                return
        for t in self.plan_order:
            src = self.takes[t].get("from", "chain")
            if src.startswith("branch:") and self.state["takes"].get(src[7:], {}).get("snapshot") == world \
                    and self.state["takes"].get(t, {}).get("status") not in ("passed", "skipped"):
                return
        released = self.state.setdefault("released", [])
        if world not in released:
            released.append(world)

    # ---------------------------------------------------------------- ночь

    def run(self, only=None):
        if self.state["started"] is None:
            self.state["started"] = self.m.now()
            self.save()
        self.night_end = self.m.now() + 3600 * float(self.limits["night_hours"])
        self.plan_order = [t for t in self.order if not only or t in only]
        self.only = only
        try:
            self.preflight()
            self.render_live()
            late = []
            for take in self.plan_order:
                if self.state["takes"].get(take, {}).get("status") in ("passed", "skipped", "chained"):
                    continue
                if self.m.now() > self.night_end:
                    late.append(take)      # ночь кончилась: черновик — из того, что снято
                    continue
                self.take(take)
            self.current = ""
            tail = f"; ночь кончилась, не дошли {', '.join(late)}" if late else ""
            if only or self.cfg.get("cut") is False or not self.cfg.get("render"):
                self.status("done", "дубли кончились" + tail)
                return 0
            self.cut()
            self.current = ""
            missing = self.missing_plans()
            draft = "черновик готов: " + os.path.join(self.work, "cut", "newisle-draft.mp4")
            if missing or late:
                self.status("done", draft + "; чистовой ждёт " + ", ".join(missing) + tail)
                return 0
            if self.cfg.get("final") is False:
                self.status("done", draft + "; чистовой — по слову Артёма")
                return 0
            self.cut(final=True)
            self.current = ""
            self.status("done", "чистовой готов: " + os.path.join(self.work, "cut", "newisle-final.mp4"))
            return 0
        except Stopped as e:
            self.status("stopped, waiting", str(e))
            tries = self.state["takes"].get(self.current_take, {}).get("attempts") or [{}]
            self.triage(self.current_take, tries[-1].get("rec"), str(e))
            return STOPPED_EXIT
        except Exception as e:  # noqa: BLE001 — неожиданное: остановиться и сказать, а не упасть молча
            self.status("stopped, waiting", f"ошибка конвейера: {e!r}")
            raise

    plan_order = []
    current_take = ""
    only = None
    beat = 0.0


# ---------------------------------------------------------------- проверка дубля по данным

def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def tail_heap(log, pos):
    """Лог с места pos: (новое место, процент последней строки кучи или None, была ли строка кроме пульса кучи)."""
    try:
        with open(log, "rb") as f:
            f.seek(pos)
            chunk = f.read()
    except OSError:
        return pos, None, False
    text = chunk.decode("utf-8", "replace")
    pct = None
    for m in HEAP_RE.finditer(text):
        pct = int(m.group(1))
    said = any(line.strip() and not HEAP_RE.search(line) for line in text.splitlines())
    return pos + len(chunk), pct, said


def check_take(result, log, take):
    """Что не так с записью дубля по её итогу и логу: момент плана, удары не в срок, камера в блоках, падение."""
    out = []
    for c in CRASH:
        if c in log:
            out.append(f"в логе «{c}»")
    if not result.get("saved"):
        out.append("мир не сохранён")
    bad_cams = set(CAMERA_RE.findall(log))
    for s in result.get("shots", []):
        name = s.get("replay")
        if not s.get("selected", True):
            continue
        if not s.get("moment", True):
            out.append(f"{name}: момент не наступил")
        if name in bad_cams:
            out.append(f"{name}: камера в блоках")
        for k in s.get("strikes", []):
            if not k.get("bound"):
                out.append(f"{name}: удар {k.get('weapon')} не найден у клиента")
            elif "actual" in k and abs(k["actual"] - k["eta"]) > ETA_TOLERANCE:
                out.append(f"{name}: {k.get('weapon')} в {k['actual']:.1f} с вместо {k['eta']:.1f}")
    return out


def check_hud(path):
    """Живые планы (кадры вживую, дальше прорисовки): итог HudQa по каждому — последняя строка плана."""
    last = {}
    for line in read(path).splitlines():
        if line.strip():
            r = json.loads(line)
            last[r["shot"]] = r
    return [f"{s}: {r.get('verdict')} {r.get('why', '')}".strip() for s, r in last.items() if r.get("verdict") != "PASS"]


def covered(result, take):
    """Планы дубля для вывода из повторов: всё с `cover` у записей и у дубля."""
    plans = []
    for s in take.get("shots", []):
        plans.extend((s.get("cover") or {}).keys())
    plans.extend((take.get("cover") or {}).keys())
    return plans


def parse_best(path):
    """Строки best.txt вывода: план → (PASS/FAIL, причина, папка кандидата или «-»)."""
    best = {}
    for line in read(path).splitlines():
        parts = line.split(maxsplit=3)
        if len(parts) >= 3:
            best[parts[0]] = (parts[2], parts[3] if len(parts) > 3 else "", parts[1])
    return best


# ---------------------------------------------------------------- поиски P1

def searches(conv, only=None):
    """Пробы поисков на копиях мастера; выбор — по правилам поиска; найденное — scenes/newisle-found.json."""
    conv.preflight()
    s = conv.data["searches"]
    found_file = os.path.join(SCENES, "newisle-found.json")
    found = load_json(found_file, {"about": "Найдено поисками P1 (produce.py search): постановщик берёт это раньше своих мест."})
    found.setdefault("places", {})
    trials = []
    if not only or "terrace" in only:
        trials += [("terrace", f"terrace:{d}") for d in s["terrace"]["distances"]]
    if not only or "grad" in only:
        trials += [("grad", f"grad:{n}") for n in s["grad"]["sizes"]]
    for k in ("spots", "boat", "pointblank"):
        if not only or k in only:
            trials.append((k, k))
    results = {}
    for kind, trial in trials:
        r = conv.trial(trial)
        results.setdefault(kind, []).append(r)
    pick(s, results, found)
    found_file_tmp = found_file
    write_json(found_file_tmp, found)
    return found


def pick(s, results, found):
    """Выбор по пробам: терраса — ближайший удар, где стекло выбито, а дом стоит; «Град» — самый большой залп в пределах
    тика; места — как нашли лучи; лодка Create — если держится ровно; Point Blank — сбит ли шахед."""
    if "terrace" in results:
        rule = s["terrace"]
        ok = []
        for r in results["terrace"]:
            t = r.get("trial", {})
            p = r.get("probes", {})
            glass, house = p.get(rule["probes"][0], {}), p.get(rule["probes"][1], {})
            if not t.get("hit") or glass.get("before", 0) <= 0 or house.get("before", 0) <= 0:
                continue
            broken = 1 - glass["after"] / glass["before"]
            kept = house["after"] / house["before"]
            if broken >= rule["glass_broken_min"] and kept >= rule["house_kept_min"]:
                ok.append((t["distance"], t["hit"]))
        if ok:
            found["places"][rule["place"]] = min(ok)[1]
        found["terrace"] = {"tried": len(results["terrace"]), "passed": [d for d, _ in sorted(ok)]}
    if "grad" in results:
        rule = s["grad"]
        sizes = [r["trial"]["size"] for r in results["grad"] if r.get("trial", {}).get("mspt_peak", 1e9) <= rule["mspt_max"]]
        if sizes:
            found["grad"] = {"plan": rule["plan"], "size": max(sizes)}
    if "spots" in results:
        for r in results["spots"]:
            found["places"].update(r.get("trial", {}).get("places", {}))
    if "boat" in results:
        r = results["boat"][-1].get("trial", {})
        found["boat"] = {"kind": "create" if r.get("ok") else "vanilla", "roll": r.get("roll"), "pitch": r.get("pitch")}
    if "pointblank" in results:
        r = results["pointblank"][-1].get("trial", {})
        found["pointblank"] = {"downed": bool(r.get("downed")), "last_distance": r.get("last_distance")}


def trial_method(self, trial):
    """Одна проба поиска на свежей копии мастера; итог — файл итога постановщика."""
    name = trial.replace(":", "-")
    self.current = f"поиск {trial}"
    self.current_take = name
    self.wait_resources(self.world_gb())
    world = self.copy_world(self.master, f"nw-search-{name}")
    rec = self.guard.check(os.path.join(self.work, "search", name), "запись в")
    if os.path.exists(rec):
        self.remove(rec)
    os.makedirs(rec)
    result_file = os.path.join(rec, "result.json")
    env = self.env({"INCOMING_NEWISLE_TRIAL": trial, "INCOMING_NEWISLE_RESULT": result_file, "INCOMING_WORLD": world,
                    "INCOMING_TRAILER_DIR": rec, "INCOMING_TRAILER_SHOTS": "-"})
    self.status("searching")
    code, killed = self.client("record", name, self.cfg["record"], env, rec, 3600)
    self.remove(os.path.join(self.saves, world))
    r = load_json(result_file)
    if r is None:
        raise Stopped(f"поиск {trial}: нет итога ({killed or f'код {code}'})")
    if r.get("status") == "stopped":
        raise Stopped(f"поиск {trial}: {r.get('reason')}")
    return r


Conveyor.trial = trial_method


# ---------------------------------------------------------------- живой дубль

def live(conv, take, standin=False):
    """Живой дубль Артёма (или бот вместо него) на копии мира, с которого цепочка шла в его дубль (до цепочки — мастера):
    запись и вывод его планов, как у цепочки; дубли после него цепочка уже сняла с мира своего прогона его ударов."""
    conv.plan_order = [take]
    conv.only = [take]
    conv.preflight()
    st = conv.state.setdefault("live", {}).setdefault(take, {"attempts": []})
    n = len(st["attempts"])
    before = conv.state["takes"].get(take, {}).get("before")
    src = os.path.join(conv.saves, before) if before else conv.master
    if not os.path.exists(os.path.join(src, "level.dat")):
        raise Stopped(f"{take}: нет мира входа {src}")
    a = conv.attempt(take, 100 + n, 0, src, live=not standin, standin=standin)
    st["attempts"].append({"ok": a["ok"], "reason": a["reason"], "rec": a["rec"], "world": a["world"], "standin": standin})
    conv.save()
    conv.status("done" if a["ok"] else "stopped, waiting", f"живой {take}: " + ("снят" if a["ok"] else a["reason"]))
    return 0 if a["ok"] else STOPPED_EXIT


# ---------------------------------------------------------------- вход

def main(argv=None):
    ap = argparse.ArgumentParser(description="конвейер трейлера Newisle")
    ap.add_argument("mode", choices=("run", "live", "search", "status", "night"))
    ap.add_argument("--config", required=True)
    ap.add_argument("--work", help="папка работы вместо work машины")
    ap.add_argument("--takes", help="только эти дубли (через запятую), по порядку истории")
    ap.add_argument("--take", help="дубль живой записи")
    ap.add_argument("--standin", action="store_true", help="живой дубль — ботом вместо Артёма")
    ap.add_argument("--only", help="поиски через запятую")
    ap.add_argument("--fault", action="append", default=[], metavar="ДУБЛЬ=kill|disk|camera")
    a = ap.parse_args(argv)
    cfg = load_json(a.config)
    if cfg is None:
        sys.exit(f"нет машины {a.config}")
    if a.work:
        cfg["work"] = os.path.abspath(a.work)
    faults = dict(f.split("=", 1) for f in a.fault)
    conv = Conveyor(cfg, faults=faults)
    if a.mode == "status":
        print(read(conv.status_file), end="")
        return 0
    try:
        if a.mode == "night":
            return night(cfg, a.takes.split(",") if a.takes else ["T02", "T04", "T24"],
                         faults or {"T02": "kill", "T04": "disk", "T24": "camera"})
        if a.mode == "live":
            return live(conv, a.take, a.standin)
        if a.mode == "search":
            conv.plan_order = []
            searches(conv, a.only.split(",") if a.only else None)
            conv.status("done", "поиски записаны в scenes/newisle-found.json")
            return 0
        code = conv.run(a.takes.split(",") if a.takes else None)
        if faults:
            write_json(os.path.join(conv.work, "smoke.json"), smoke_verdict(conv, faults))
        return code
    except Stopped as e:
        conv.status("stopped, waiting", str(e))
        return STOPPED_EXIT


def night(cfg, smoke, faults, machine=None, takes_file=None, log=print):
    """Ночь П2: проба (дубли `smoke` с порчей `faults`, своя папка <work>/smoke, тот же файл статуса) — каждое
    ограждение сработало и дубли кончились; прошла — вся цепочка в <work> (там же живые дубли вечера), потом черновик
    и чистовой; не прошла — «stopped, waiting», ночь не тратится. Пройденная проба при перезапуске не повторяется."""
    status = cfg.get("status") or os.path.join(cfg["work"], "status.txt")
    smoke_file = os.path.join(cfg["work"], "smoke.json")
    verdict = load_json(smoke_file)
    if not (verdict and verdict.get("pass")):
        probe = Conveyor(dict(cfg, work=os.path.join(cfg["work"], "smoke"), status=status), machine=machine,
                         takes_file=takes_file, faults=faults, log=log)
        code = probe.run(smoke)
        verdict = smoke_verdict(probe, faults)
        write_json(smoke_file, verdict)
        for st in probe.state["takes"].values():           # миры пробы — прочь: цепочка ночи — с мастера
            for w in {st.get("snapshot"), st.get("kept")} - {None}:
                probe.remove(os.path.join(probe.saves, w))
        if code or not verdict["pass"]:
            bad = [k for k, c in verdict["checks"].items() if not c["ok"]]
            probe.status("stopped, waiting", "проба не прошла: " + (", ".join(bad) or probe.state.get("last_error", "")))
            return STOPPED_EXIT
    conv = Conveyor(dict(cfg, status=status), machine=machine, takes_file=takes_file, log=log)
    return conv.run()


def smoke_verdict(conv, faults):
    """Дымовой прогон: каждое ограждение сработало, а дубли всё равно кончились (снятый клиент — пересъёмка, место —
    пауза и продолжение, плохая камера — пересъёмка запасной)."""
    ev = conv.events
    checks = {}
    for take, kind in faults.items():
        st = conv.state["takes"].get(take, {})
        tries = st.get("attempts", [])
        done = st.get("status") in ("passed", "chained")
        if kind == "kill":
            ok = any(e[0] == "fault-kill" and e[1] == take for e in ev) and len(tries) >= 2 and done
        elif kind == "disk":
            ok = any(e[0] == "pause" and "мало места" in e[2] for e in ev) and done
        elif kind == "camera":
            ok = len(tries) >= 2 and not tries[0]["ok"] and "камера" in tries[0]["reason"] and done
        else:
            ok = False
        checks[f"{take}={kind}"] = {"ok": ok, "status": st.get("status"), "attempts": [t.get("reason") for t in tries]}
    return {"pass": all(c["ok"] for c in checks.values()), "checks": checks}


if __name__ == "__main__":
    sys.exit(main())
