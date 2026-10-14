"""Дубль плана трейлера как данные: кадры с камерой и проверкой каждого кадра, звуки, что сыграл клиент, снаряды и их
удары, люди, отметки сценария. Общий для монтажа от событий (montage.py), проверки монтажа (verdict.py) и экзамена на
старых записях (exam_v5.py). Только стандартная библиотека: читается где угодно, без игры и карты.

Время дубля — секунды от начала плана (как окно плана в таблице склеек и `Shot.sec` в edit.py): кадр k снят в
k/60 с, тик журнала записи (тик повтора) — `a + 20·с`, где `a` — первый тик окна вывода (`report.jsonl`: `a`).

Источники (как их пишет съёмка, `mod/src/devtest/…/scenario/trailer`):
- `timeline.jsonl` (`Recorder`): `shot` — начало плана, `frame` — кадр k с камерой, `sound`/`update`/`stop` — звуки,
  которые клиент сыграл у камеры (у вывода из повтора — у камеры вывода), `mark` — отметки (impact, siren, dark, front…);
- `qa.jsonl` (`FrameQa`, вывод из повтора): строка на кадр — объект в кадре, где, какой долей высоты, виден ли (лучи по
  блокам), свет сквозь дым, облака, туман, секция, мотор, доля кадра за дымом, глубина LOD DH, камера;
- `qa/<план>.jsonl` (`HudQa`, живой план с `.watch`): строка на кадр — снаряды плана и удары в кадре и открыты ли;
- журнал `<план>.events.jsonl` (`EventLog`): пути снарядов по тикам, удары, люди, камера сценария, отметки;
- `report.jsonl` вывода: окно (`a`, `b`) и тик удара объекта (`event`).
"""
import bisect
import json
import math
import os
from dataclasses import dataclass, field

FPS = 60
TPS = 20
SOUND_SPEED = 17.15  # блоков за тик: фронт звука и волны мода (Acoustics.SPEED, Warheads.FRONT_SPEED)
ARRIVAL_SLACK = 0.2  # с: сыгранный звук взрыва относится к удару, если начат так близко к приходу фронта

# роды звуков (по событию звука): как их зовёт проверка
BLAST_SOUNDS = ("incoming:blast.", "incoming:rocket.blast", "incoming:bomb.")
MOTOR_SOUNDS = (".engine", ".dive", ".whistle", ".flyby", "booster.loop")
LAUNCH_SOUNDS = ("incoming:launch.booster", "incoming:rocket.launch", "incoming:launch", "incoming:icbm.")
SIREN_SOUNDS = ("siren", "alarm")
GUN_SOUNDS = ("pointblank:",)


def sound_kind(event):
    """Род звука по имени события: gun, motor, siren, blast, launch, mod, иначе — пространство имён и группа."""
    if event.startswith(GUN_SOUNDS):
        return "gun"
    if event.startswith("incoming:") and any(w in event for w in MOTOR_SOUNDS):
        return "motor"
    if any(w in event for w in SIREN_SOUNDS):
        return "siren"
    if event.startswith(BLAST_SOUNDS):
        return "blast"
    if event.startswith(LAUNCH_SOUNDS):
        return "launch"
    if event.startswith("incoming:"):
        return "mod"
    ns, _, rest = event.partition(":")
    return f"{ns}:{rest.split('.')[0]}"


def read_jsonl(path):
    rows = []
    if not path or not os.path.exists(path):
        return rows
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:  # запись оборвалась на строке
                continue
    return rows


# ---------------------------------------------------------------- камера и проекция

def look_vectors(yaw, pitch):
    """Векторы взгляда Minecraft: курс 0 — юг (+z), тангаж плюс — вниз. (вперёд, вправо, вверх)."""
    y, p = math.radians(yaw), math.radians(pitch)
    f = (-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p))
    r = (-math.cos(y), 0.0, -math.sin(y))
    # вверх = вправо × вперёд (x — восток, y — вверх, z — юг)
    u = (r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
    return f, r, u


def project(cam, fov, point, aspect=16 / 9):
    """Место точки в кадре: (x, y) в долях полукадра (−1…1, x вправо, y вверх) и глубина; None — за камерой."""
    x0, y0, z0, yaw, pitch = cam[:5]
    f, r, u = look_vectors(yaw, pitch)
    d = (point[0] - x0, point[1] - y0, point[2] - z0)
    z = sum(a * b for a, b in zip(d, f))
    if z <= 0.1:
        return None
    tan_y = math.tan(math.radians(fov) / 2)
    tan_x = tan_y * aspect
    x = sum(a * b for a, b in zip(d, r)) / z / tan_x
    y = sum(a * b for a, b in zip(d, u)) / z / tan_y
    return x, y, z


def dist(a, b):
    return math.sqrt(sum((p - q) ** 2 for p, q in zip(a[:3], b[:3])))


# ---------------------------------------------------------------- дубль

@dataclass
class Frame:
    k: int
    t: float                 # секунды дубля
    cam: tuple               # x, y, z, yaw, pitch
    fov: float = 70.0
    qa: dict = None          # строка FrameQa (вывод из повтора) или HudQa (живой план с проверкой)


@dataclass
class Sound:
    id: int
    t: float                 # секунды дубля: клиент начал его играть (звук мода — когда фронт дошёл до уха)
    event: str
    file: str
    pos: tuple
    volume: float
    pitch: float
    range: float
    relative: bool
    linear: bool
    loop: bool
    lp: tuple = (1.0, 1.0)
    stop: float = None
    updates: list = field(default_factory=list)   # (t, x, y, z, volume, pitch, lp_gain, lp_highs)

    @property
    def kind(self):
        return sound_kind(self.event)

    def gain_at(self, cam, t=None):
        """Громкость у камеры, как у движка: линейное затухание до range; без затухания — как есть."""
        pos, vol, lpg = self.pos, self.volume, self.lp[0]
        if t is not None and self.updates:
            i = bisect.bisect_right([u[0] for u in self.updates], t) - 1
            if i >= 0:
                u = self.updates[i]
                pos, vol, lpg = u[1:4], u[4], u[6]
        if self.relative:
            return min(vol, 1.0) * lpg
        g = min(vol, 1.0) * lpg
        if self.linear:
            g *= max(0.0, 1.0 - dist(cam, pos) / max(self.range, 1e-3))
        return g


@dataclass
class Flight:
    index: int
    uuid: str
    weapon: str
    bomber: bool
    path: dict = field(default_factory=dict)   # тик повтора → (x, y, z, фаза, от сущности)
    impact: int = None       # тик повтора
    impact_pos: tuple = None
    lost: int = None

    def pos(self, tick):
        if not self.path:
            return None
        if tick in self.path:
            return self.path[tick][:3]
        keys = sorted(self.path)
        i = bisect.bisect_left(keys, tick)
        if i == 0:
            return self.path[keys[0]][:3]
        if i >= len(keys):
            if self.impact is not None and self.impact_pos is not None and tick >= self.impact:
                return self.impact_pos
            return self.path[keys[-1]][:3]
        a, b = keys[i - 1], keys[i]
        k = (tick - a) / (b - a)
        pa, pb = self.path[a], self.path[b]
        return tuple(pa[j] + (pb[j] - pa[j]) * k for j in range(3))


@dataclass
class Journal:
    flights: list = field(default_factory=list)
    people: dict = field(default_factory=dict)    # имя → {тик: (x, y, z[, yaw, head])}
    recorder: dict = field(default_factory=dict)  # тик → (x, y, z)
    camera: dict = field(default_factory=dict)    # тик → (x, y, z, yaw, pitch, roll, fov)
    marks: list = field(default_factory=list)     # (тик, что)
    nukes: list = field(default_factory=list)     # (тик, (x, y, z), кт)
    ticks: int = 0


def read_journal(path):
    """Журнал EventLog; None — файла нет."""
    if not path or not os.path.exists(path):
        return None
    j = Journal()
    by = {}
    for o in read_jsonl(path):
        t = o.get("t", 0)
        j.ticks = max(j.ticks, t + 1)
        if "new" in o:
            f = Flight(o["new"], o.get("id", ""), o.get("weapon", ""), o.get("bomber", False))
            by[f.index] = f
            j.flights.append(f)
        elif "impact" in o or "lost" in o:
            f = by.get(o.get("impact", o.get("lost")))
            if f is None:
                continue
            if "impact" in o:
                f.impact, f.impact_pos = t, tuple(o["pos"])
            else:
                f.lost = t
        elif "f" in o or "me" in o:
            for a in o.get("f", ()):
                f = by.get(a[0])
                if f is not None:
                    f.path[t] = (a[1], a[2], a[3], a[4], a[5] if len(a) > 5 else 1)
            for p in o.get("p", ()):
                j.people.setdefault(p[0], {})[t] = tuple(p[1:])
            if "me" in o:
                j.recorder[t] = tuple(o["me"])
            if "cam" in o:
                j.camera[t] = tuple(o["cam"])
        elif "nuke" in o:
            j.nukes.append((t, tuple(o["nuke"]), o.get("kt", 0)))
        elif "mark" in o:
            j.marks.append((t, o["mark"]))
    return j


@dataclass
class Impact:
    """Удар в дубле: когда (секунды дубля), где, чей; arrival — когда его звук дошёл до камеры дубля."""
    t: float
    pos: tuple
    weapon: str
    flight: int
    subject: bool = False
    arrival: float = None          # звук дошёл до камеры (секунды дубля); None — не дошёл до конца дубля
    arrival_played: bool = False   # время прихода — по звуку, который клиент сыграл, а не по расстоянию
    distance: float = None         # до камеры в момент удара


@dataclass
class Take:
    name: str
    src: str
    live: bool
    speed: float = 1.0
    frames: list = field(default_factory=list)
    sounds: list = field(default_factory=list)
    marks: list = field(default_factory=list)      # (секунды дубля, что)
    a: int = 0                                     # первый тик окна вывода (тик повтора)
    key: float = None                              # удар объекта плана, секунды дубля
    journal: Journal = None                        # журнал вывода, иначе записи
    record_journal: Journal = None
    hud: dict = None                               # итог HudQa
    fight: dict = None
    grid: dict = None
    report: dict = None
    hud_text: bool = False                         # в кадре текст интерфейса

    # --- время
    @property
    def duration(self):
        return len(self.frames) * self.speed / FPS if self.frames else 0.0

    def tick(self, sec):
        """Тик повтора в момент дубля."""
        return self.a + sec * TPS

    def sec(self, tick):
        return (tick - self.a) / TPS

    def frames_in(self, t0, t1):
        return [f for f in self.frames if t0 - 1e-6 <= f.t < t1 - 1e-6]

    def cam_at(self, t):
        """Камера дубля в момент t (последний кадр не позже; вне дубля — край)."""
        if not self.frames:
            return None
        ts = self._times()
        i = min(max(bisect.bisect_right(ts, t) - 1, 0), len(self.frames) - 1)
        return self.frames[i].cam

    def _times(self):
        if getattr(self, "_ts", None) is None or len(self._ts) != len(self.frames):
            self._ts = [f.t for f in self.frames]
        return self._ts

    # --- события
    def impacts(self):
        """Удары дубля по журналу (вывода, иначе записи) и отметкам: время, место, чей, когда звук дошёл до камеры."""
        out = []
        j = self.journal or self.record_journal
        if j is not None:
            for f in j.flights:
                if f.impact is None or f.impact_pos is None:
                    continue
                t = self.sec(f.impact)
                out.append(Impact(t, f.impact_pos, f.weapon, f.index))
        if self.live and self.hud_impacts():
            out = self.hud_impacts()
        if self.key is not None:
            best = min(out, key=lambda i: abs(i.t - self.key), default=None)
            if best is not None and abs(best.t - self.key) <= 0.15:
                best.subject = True
            elif not self.live:
                out.append(Impact(self.key, None, "", -1, subject=True))
        for i in out:
            self._arrival(i)
        return sorted(out, key=lambda i: i.t)

    def hud_impacts(self):
        """Удары живого плана с проверкой (HudQa): по кадрам, где удар впервые в списке, место и расстояние."""
        seen = {}
        for f in self.frames:
            for h in (f.qa or {}).get("impacts", ()):
                key = tuple(h["at"])
                if key not in seen:
                    seen[key] = (f.t, h.get("d"))
        out = []
        for at, (t, d) in seen.items():
            out.append(Impact(t, at, "", -1, distance=d))
        marks = [t for t, m in self.marks if m == "impact"]
        # отметка «impact» — тик удара (HudQa ставит её в тик, когда путь кончился)
        for i in out:
            near = min(marks, key=lambda m: abs(m - i.t), default=None)
            if near is not None and abs(near - i.t) < 0.5:
                i.t = near
        return out

    def _arrival(self, imp):
        """Звук удара дошёл до камеры: по звуку взрыва, который клиент сыграл, иначе — по фронту 17,15 блока за тик до
        места камеры в каждом кадре. Взрыв мода звучит не у места удара, а у слушателя со стороны удара
        (`Acoustics`): сыгранный звук — у места удара или начатый в `ARRIVAL_SLACK` от прихода фронта."""
        if imp.pos is None:
            return
        cam0 = self.cam_at(imp.t)
        if cam0 is not None and imp.distance is None:
            imp.distance = dist(cam0, imp.pos)
        front = None
        for fr in self.frames:
            if fr.t < imp.t:
                continue
            if dist(fr.cam, imp.pos) <= SOUND_SPEED * TPS * (fr.t - imp.t):
                front = fr.t
                break
        if front is None and self.frames:
            # дальше конца дубля: камера у последнего кадра
            front = imp.t + dist(self.frames[-1].cam, imp.pos) / (SOUND_SPEED * TPS)
        played = self.blast_for(imp, front)
        if played is not None:
            imp.arrival, imp.arrival_played = played.t, True
        else:
            imp.arrival = front

    def blast_for(self, imp, front):
        """Сыгранный звук взрыва этого удара: у места удара или начатый у прихода фронта."""
        for s in sorted((s for s in self.sounds if s.kind == "blast" and s.t >= imp.t - 0.05), key=lambda s: s.t):
            if dist(s.pos, imp.pos) < 8 or front is not None and abs(s.t - front) <= ARRIVAL_SLACK:
                return s
        return None

    def launches(self):
        """Пуски по звукам старта, что сыграл клиент: (секунды дубля, событие, место)."""
        return [(s.t, s.event, s.pos) for s in self.sounds if s.kind == "launch"]

    def first_mark(self, what):
        return next((t for t, m in self.marks if m == what or m.startswith(what)), None)

    def people_at(self, sec):
        """Люди в момент дубля по журналу: имя → место."""
        j = self.journal or self.record_journal
        if j is None:
            return {}
        tick = round(self.tick(sec))
        out = {}
        for name, track in j.people.items():
            if tick in track:
                out[name] = track[tick]
            else:
                ks = [k for k in track if abs(k - tick) <= 2]
                if ks:
                    out[name] = track[min(ks, key=lambda k: abs(k - tick))]
        return out


def load_take(name, src, root=None, recording=None):
    """Дубль плана name из папки src (кандидат вывода `…/render…/<план>/<камера>` или папка живой записи).
    recording — папка записи с журналами повторов (по умолчанию — часть src до `/render`)."""
    d = src if os.path.isabs(src) or root is None else os.path.join(root, src)
    live = "/render" not in d
    t = Take(name, src, live)
    tl = os.path.join(d, "timeline.jsonl")
    if not os.path.exists(tl) and os.path.exists(tl + f".{name}"):
        tl = tl + f".{name}"   # строки плана из архива экзамена
    rows = [r for r in read_jsonl(tl) if r.get("shot") == name]
    # последний дубль плана в журнале (живая запись дописывает журнал)
    starts = [i for i, r in enumerate(rows) if r.get("type") == "shot"]
    if starts:
        head = rows[starts[-1]]
        t.speed = head.get("speed", 1.0)
        t.hud_text = head.get("hud", False)
        rows = rows[starts[-1] + 1:]
    sounds = {}
    for r in rows:
        kind = r.get("type")
        if kind == "frame":
            # t — тики от начала плана (Recorder.frame); без него — по номеру кадра
            sec = r["t"] / TPS if "t" in r else r["k"] * t.speed / FPS
            t.frames.append(Frame(r["k"], sec, tuple(r["cam"])))
        elif kind == "sound":
            s = Sound(r["id"], r["t"] / TPS, r["event"], r.get("file", ""), tuple(r["pos"]), r["volume"], r["pitch"],
                      r.get("range", 16), r.get("relative", False), r.get("linear", True), r.get("loop", False),
                      tuple(r.get("lp", (1, 1))))
            sounds[s.id] = s
            t.sounds.append(s)
        elif kind == "update" and r["id"] in sounds:
            sounds[r["id"]].updates.append((r["t"] / TPS, *r["pos"], r["volume"], r["pitch"], *r.get("lp", (1, 1))))
        elif kind == "stop" and r["id"] in sounds:
            sounds[r["id"]].stop = r["t"] / TPS
        elif kind == "mark":
            what = r["what"]
            if not (what.startswith("gone:") and r["t"] <= 0):
                t.marks.append((r["t"] / TPS, what))
    t.frames.sort(key=lambda f: f.k)
    if live:
        rec = recording or d
        qa = os.path.join(d, "qa", f"{name}.jsonl")
        hud_rows = read_jsonl(qa)
        # кадры HudQa — по времени плана (тики), по порядку кадров
        for f, q in zip(t.frames, hud_rows):
            f.qa = q
            f.fov = q.get("fov", f.fov)
        for fn, attr in (("hud.jsonl", "hud"), ("fight.jsonl", "fight"), ("grid.jsonl", "grid")):
            p = os.path.join(d, fn)
            p = p if os.path.exists(p) else p + f".{name}"
            got = [r for r in read_jsonl(p) if r.get("shot") == name]
            if got:
                setattr(t, attr, got[-1])
        t.record_journal = read_journal(os.path.join(rec, "replays", f"{name}.events.jsonl"))
    else:
        render = os.path.dirname(os.path.dirname(d))
        rep = os.path.join(render, "report.jsonl")
        rep = rep if os.path.exists(rep) else rep + f".{name}"
        cand = os.path.basename(d)
        reps = [r for r in read_jsonl(rep) if r.get("shot") == name]
        mine = [r for r in reps if r.get("candidate") == cand] or reps
        if mine:
            t.report = mine[-1]
            t.a = t.report.get("a", 0)
            ev = t.report.get("event", -1)
            t.key = t.sec(ev) if ev is not None and ev >= 0 else None
        qa_rows = read_jsonl(os.path.join(d, "qa.jsonl"))
        for f, q in zip(t.frames, qa_rows):
            f.qa = q
            f.fov = q.get("fov", f.fov)
        if qa_rows and not mine:
            # окно без отчёта: первый тик — по первому кадру проверки и журнала звука
            t.a = round(qa_rows[0]["t"] - (t.frames[0].t * TPS if t.frames else 0))
        rec = recording or d.split("/render")[0]
        replay = (t.report or {}).get("replay", name)
        t.journal = read_journal(os.path.join(d, f"{name}.events.jsonl"))
        t.record_journal = read_journal(os.path.join(rec, "replays", f"{replay}.events.jsonl"))
    return t
