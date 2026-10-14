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
- `report.jsonl` вывода: окно (`a`, `b`) и тик удара объекта (`event`);
- журнал `<план>.sounds.jsonl` (`WorldSounds`, сервер записи): все звуки, что играл сервер, пакеты мода со звуком,
  ушедшие только тем, кто рядом, и снаряды с дальностью мотора; по нему `world_sfx` ставит звуки, которых клиент повтора
  не получил (он слышит только то, что сервер слал игроку записи), а `edit.load_recording` кладёт их в план.
"""
import bisect
import json
import math
import os
import zlib
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
    world: "WorldLog" = None                       # звук мира записи (вывод из повтора)

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
    read_timeline(tl, name, t)
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
        t.world = read_world(os.path.join(rec, "replays", f"{replay}.sounds.jsonl"))
    return t


def read_timeline(path, name, t):
    """Строки плана name из журнала клиента (`Recorder`, timeline.jsonl) — в дубль t: последний дубль плана в журнале
    (живая запись дописывает журнал), скорость, кадры с камерой, звуки с их изменениями и концом, отметки."""
    rows = [r for r in read_jsonl(path) if r.get("shot") == name]
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
    return t


# ---------------------------------------------------------------- звук мира (WorldSounds)

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MOD_ASSETS = os.path.join(REPO, "mod", "src", "main", "resources", "assets")
# индекс ресурсов игры, как у edit.SoundBank (./gradlew downloadAssets)
ASSETS_PROPS = os.path.join(REPO, "mod", "build", "moddev", "minecraft_assets.properties")

WORLD_ID = 10 ** 6   # id звуков мира в списке звуков плана: выше id журнала клиента (Recorder считает с 0)
MATCH_SEC = 0.1      # звук мира сыгран клиентом: то же событие не дальше 0,1 с…
MATCH_BLOCKS = 4.0   # …и 4 блоков (звук сущности клиент ставит по своему месту сущности)
SOUND_LEN_MAX = 8.0  # с: слышен ли звук мира камере, смотрится в этом окне после начала (звуки мира короче)
ATTENUATION = 16     # затухание варианта по умолчанию (sounds.json: attenuation_distance)


@dataclass
class WorldSound:
    """Звук, который сыграл сервер (`PlayLevelSoundEvent` после всех подписчиков)."""
    t: int                   # тик повтора, в который пакет лёг бы у игрока записи
    gt: int                  # время игры сервера
    event: str
    source: str
    pos: tuple
    volume: float            # как ушли клиенту
    pitch: float
    seed: int = None         # зерно варианта; None — его дал вызывающий, не узнать
    file: str = None         # вариант, который выбрал бы клиент (сервер записи выбрал звуками своего клиента)
    attenuation: int = None
    fv: float = 1.0          # множители громкости и тона варианта
    fp: float = 1.0
    range: float = None      # дальность линейного затухания у клиента
    send: float = None       # дальность, в которой сервер шлёт пакет
    heard: bool = False      # игрок записи получил пакет (и сущность звука у него была)
    by: str = None           # игрок, у которого звук (выстрел бота — с места стрелка)
    entity: dict = None


@dataclass
class Trigger:
    """Пакет мода, по которому клиент играет звук сам, ушедший только тем, кто ближе radius."""
    t: int
    gt: int
    kind: str                # siren, nuke_warning, grid_failure
    pos: tuple
    radius: float
    heard: bool
    extra: dict = field(default_factory=dict)


@dataclass
class WorldLog:
    sounds: list = field(default_factory=list)
    triggers: list = field(default_factory=list)
    # (t, gt, [[uuid, оружие, x, y, z, слышно, heard]], [[x, y, z, слышно, heard]])
    flights: list = field(default_factory=list)


def read_world(path):
    """Журнал WorldSounds; None — файла нет."""
    if not path or not os.path.exists(path):
        return None
    w = WorldLog()
    for o in read_jsonl(path):
        t, gt = o.get("t", 0), o.get("gt", 0)
        if "sound" in o:
            w.sounds.append(WorldSound(t, gt, o["sound"], o.get("source", ""), tuple(o["pos"]), o.get("volume", 1.0),
                                       o.get("pitch", 1.0), o.get("seed"), o.get("file"), o.get("attenuation"),
                                       o.get("fv", 1.0), o.get("fp", 1.0), o.get("range"), o.get("send"),
                                       o.get("heard", False), o.get("by"), o.get("entity")))
        elif "trigger" in o:
            known = ("t", "gt", "trigger", "pos", "radius", "heard")
            w.triggers.append(Trigger(t, gt, o["trigger"], tuple(o["pos"]), o.get("radius", 0.0), o.get("heard", False),
                                      {k: v for k, v in o.items() if k not in known}))
        elif "flights" in o:
            w.flights.append((t, gt, o.get("flights", []), o.get("interceptors", [])))
    return w


class JavaRandom:
    """Источник случайных чисел звука у клиента: `RandomSource.create(seed)` (LegacyRandomSource) — LCG 48 бит
    java.util.Random, `nextInt(bound)` как у него (степень двойки — старшими битами, иначе отбор)."""
    MULT, ADD, MASK = 0x5DEECE66D, 0xB, (1 << 48) - 1

    def __init__(self, seed):
        self.state = (seed ^ self.MULT) & self.MASK

    def next(self, bits):
        self.state = (self.state * self.MULT + self.ADD) & self.MASK
        r = self.state >> (48 - bits)
        return r - (1 << 32) if bits == 32 and r >= 1 << 31 else r

    def next_int(self, bound):
        if bound <= 0:
            raise ValueError("bound must be positive")
        if bound & (bound - 1) == 0:
            return (bound * self.next(31)) >> 31
        while True:
            i = self.next(31)
            j = i % bound
            if i - j + (bound - 1) < 1 << 31:   # в Java — переполнение int
                return j


@dataclass
class Variant:
    file: str                # «namespace:sounds/….ogg», как в журнале клиента
    attenuation: int
    volume: float
    pitch: float


class SoundDefs:
    """События звуков, как их собирает клиент (`SoundManager.Preparations`): записи sounds.json по порядку, вес файла —
    его weight, вес ссылки на событие (`"type": "event"`) — весь вес того события, громкость и тон ссылки множатся на
    вариант, затухание — варианта. Ваниль — sounds.json из индекса ресурсов игры (как `edit.SoundBank`), мод — из
    mod/src/main/resources/assets. Событие, которого нет (Point Blank: его ресурсов в проекте нет), — один файл
    «namespace:sounds/<путь>.ogg» с затуханием 16, как в журналах клиента; ванильное без индекса — пусто."""

    _default = None

    def __init__(self):
        self.events = {}     # событие → [(тип, имя, вес, громкость, тон, затухание)]

    @classmethod
    def default(cls):
        if cls._default is None:
            d = cls()
            vanilla = _vanilla_sounds_json()
            if vanilla:
                d.add("minecraft", vanilla)
            if os.path.isdir(MOD_ASSETS):
                for ns in sorted(os.listdir(MOD_ASSETS)):
                    p = os.path.join(MOD_ASSETS, ns, "sounds.json")
                    if os.path.exists(p):
                        with open(p, encoding="utf-8") as f:
                            d.add(ns, json.load(f))
            cls._default = d
        return cls._default

    def add(self, namespace, sounds_json):
        """Пакет ресурсов namespace: записи дописываются к событию, `replace` — заменяют прежние."""
        for key, reg in sounds_json.items():
            event = key if ":" in key else f"{namespace}:{key}"
            out = [] if reg.get("replace") else self.events.get(event, [])
            for e in reg.get("sounds", ()):
                if isinstance(e, str):
                    out.append(("file", _loc(e), 1, 1.0, 1.0, ATTENUATION))
                else:
                    out.append((e.get("type", "file"), _loc(e["name"]), int(e.get("weight", 1)), float(e.get("volume", 1.0)),
                                float(e.get("pitch", 1.0)), int(e.get("attenuation_distance", ATTENUATION))))
            self.events[event] = out

    def weight(self, event, depth=0):
        if depth > 16 or event not in self.events:
            return 0
        return sum(self._weight(e, depth) for e in self.events[event])

    def _weight(self, e, depth):
        return self.weight(e[1], depth + 1) if e[0] == "event" else e[2]

    def pick(self, event, seed):
        """Вариант, который выберет клиент по зерну пакета (`WeighedSoundEvents.getSound`); None — пусто."""
        if event not in self.events:
            ns, _, path = event.partition(":")
            # ванильного события без индекса ресурсов не угадать (файлы игры названы иначе, чем события)
            return None if ns == "minecraft" else Variant(f"{ns}:sounds/{path}.ogg", ATTENUATION, 1.0, 1.0)
        return self._pick(event, JavaRandom(seed), 0)

    def _pick(self, event, rnd, depth):
        total = self.weight(event, depth)
        if total <= 0:
            return None
        j = rnd.next_int(total)
        for e in self.events[event]:
            j -= self._weight(e, depth)
            if j < 0:
                kind, name, _, vol, pitch, att = e
                if kind != "event":
                    ns, _, path = name.partition(":")
                    return Variant(f"{ns}:sounds/{path}.ogg", att, vol, pitch)
                v = self._pick(name, rnd, depth + 1)
                return None if v is None else Variant(v.file, v.attenuation, v.volume * vol, v.pitch * pitch)
        return None


def _loc(name):
    return name if ":" in name else f"minecraft:{name}"


def _vanilla_sounds_json():
    """sounds.json игры из индекса ресурсов (как его читает edit.SoundBank); нет индекса — None."""
    if not os.path.exists(ASSETS_PROPS):
        return None
    try:
        with open(ASSETS_PROPS, encoding="utf-8") as f:
            conf = dict(line.strip().split("=", 1) for line in f if "=" in line and not line.startswith("#"))
        root = conf["assets_root"]
        with open(os.path.join(root, "indexes", conf["asset_index"] + ".json"), encoding="utf-8") as f:
            obj = json.load(f)["objects"]["minecraft/sounds.json"]
        with open(os.path.join(root, "objects", obj["hash"][:2], obj["hash"]), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, KeyError, ValueError):
        return None


def world_variant(ws, defs=None):
    """Вариант звука мира: выбранный сервером записи (его клиентом), иначе — по зерну из sounds.json; зерна нет —
    по месту и времени звука (вариант, может быть, не тот, что у клиента, но звук тот же)."""
    if ws.file:
        return Variant(ws.file, ws.attenuation or ATTENUATION, ws.fv, ws.fp)
    seed = ws.seed if ws.seed is not None else zlib.crc32(f"{ws.event}|{ws.gt}|{ws.pos}".encode())
    return (defs or SoundDefs.default()).pick(ws.event, seed)


def world_entry(ws, index, a, defs=None):
    """Звук мира записью списка звуков плана edit.py (`render_sfx`): t — тики от начала плана (a — первый тик окна),
    громкость и тон — с множителями варианта, дальность — как у клиента (`SoundEngine.play`: max(громкость, 1) ×
    затухание варианта; постоянная дальность события — только дальность рассылки сервера). None — звук пустой."""
    v = world_variant(ws, defs)
    if v is None:
        return None
    volume = ws.volume * v.volume
    return {"id": WORLD_ID + index, "t": ws.t - a, "event": ws.event, "file": v.file, "pos": list(ws.pos),
            "volume": volume, "pitch": ws.pitch * v.pitch, "range": max(volume, 1.0) * v.attenuation,
            "relative": False, "linear": True, "loop": False}


def match_world(world_sounds, client_sounds, sec):
    """Пары звук мира → звук клиента: то же событие, не дальше MATCH_SEC и MATCH_BLOCKS; каждый звук клиента — одному,
    ближайшему по времени. sec(ws) — время звука мира на часах звуков клиента. {индекс звука мира: звук клиента}."""
    by_event = {}
    for s in client_sounds:
        by_event.setdefault(s.event, []).append(s)
    used, out = set(), {}
    for i, ws in sorted(enumerate(world_sounds), key=lambda p: sec(p[1])):
        t = sec(ws)
        best = None
        for s in by_event.get(ws.event, ()):
            if id(s) in used or abs(s.t - t) > MATCH_SEC + 1e-9 or dist(s.pos, ws.pos) > MATCH_BLOCKS:
                continue
            if best is None or abs(s.t - t) < abs(best.t - t):
                best = s
        if best is not None:
            used.add(id(best))
            out[i] = best
    return out


def world_unplayed(take):
    """Звуки мира, начатые в окне дубля, которых клиент повтора не сыграл: [(индекс в журнале, звук)]. Почему — у
    звука: heard=False — пакет не ушёл игроку записи (дальше дальности рассылки или сущности звука у него не было)."""
    if take.world is None or take.live:
        return []
    end = take.duration
    inside = [(i, ws) for i, ws in enumerate(take.world.sounds) if -1e-6 <= take.sec(ws.t) <= end + 1e-6]
    played = match_world([ws for _, ws in inside], take.sounds, lambda ws: take.sec(ws.t))
    return [p for k, p in enumerate(inside) if k not in played]


def world_sfx(take, defs=None):
    """Звуки мира, которых клиент повтора у камеры дубля не сыграл и которые камера дубля слышит хоть в одном кадре,
    — записями списка звуков плана edit.py (`Shot.sounds`, формат `render_sfx`)."""
    out = []
    for i, ws in world_unplayed(take):
        e = world_entry(ws, i, take.a, defs)
        if e is None:
            continue
        t0 = take.sec(ws.t)
        if any(dist(f.cam, ws.pos) < e["range"] for f in take.frames_in(t0, t0 + SOUND_LEN_MAX)):
            out.append(e)
    return out


def world_sfx_for(name, folder):
    """Крючок edit.load_recording: звуки мира плана name из папки folder (кандидат вывода из повтора). Живой план,
    нет журнала звука мира — пусто."""
    if "/render" not in os.path.abspath(folder):
        return []
    take = load_take(name, os.path.abspath(folder))
    if take.world is None:
        return []
    out = world_sfx(take)
    if out:
        kinds = {}
        for e in out:
            k = sound_kind(e["event"])
            kinds[k] = kinds.get(k, 0) + 1
        print(f"  {name}: звуков мира, которых не было у клиента повтора, — {len(out)} "
              f"({', '.join(f'{k} {n}' for k, n in sorted(kinds.items()))})")
    return out


def missing_triggers(take):
    """Звуки мода, которые клиент играет сам по пакету, не ушедшему игроку записи, хотя камера дубля в его радиусе:
    тревоги и аварии сети (`Trigger`) и моторы снарядов и зенитных ракет (снимки полётов). В монтаж не ставятся —
    звук мода собирает клиент; список — для проверки. [{t, kind, pos, radius, distance}], t — секунды дубля."""
    if take.world is None or take.live or not take.frames:
        return []
    out, end = [], take.duration
    for tr in take.world.triggers:
        t = take.sec(tr.t)
        cam = take.cam_at(t)
        if tr.heard or not -1e-6 <= t <= end or cam is None:
            continue
        d = dist(cam, tr.pos)
        if d < tr.radius:
            out.append({"t": t, "kind": tr.kind, "pos": tr.pos, "radius": tr.radius, "distance": d})
    seen = set()
    for tick, _, flights, sams in take.world.flights:
        t = take.sec(tick)
        cam = take.cam_at(t)
        if not -1e-6 <= t <= end or cam is None:
            continue
        rows = [("motor:" + f[1], f[0], f[2:5], f[5], f[6]) for f in flights]
        rows += [("motor:interceptor", f"sam{k}", f[0:3], f[3], f[4]) for k, f in enumerate(sams)]
        for kind, key, pos, hear, heard in rows:
            d = dist(cam, pos)
            if heard or key in seen or d >= hear:
                continue
            seen.add(key)
            out.append({"t": t, "kind": kind, "pos": tuple(pos), "radius": hear, "distance": d})
    return sorted(out, key=lambda m: m["t"])
