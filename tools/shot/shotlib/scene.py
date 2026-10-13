"""Сцена — всё, что известно о плане заранее, в одном месте: мир (`world.World`), пути снарядов и людей по тикам,
удары и подрывы, дым ударов во времени. Камера планируется и проверяется по сцене, а не внутри клиента при выводе.

Источники: журнал плана трейлера (`<повтор>.events.jsonl`, EventLog) и база разбора повтора (`replaydb`, превью).
"""
from __future__ import annotations

import gzip
import json
import math
from dataclasses import dataclass, field

import numpy as np

from . import world as W

# Поперечник снаряда и огненный шар удара, блоков — как у трейлера (`Rig.sizeOf`/`blastOf`), имена — оружие журнала
SIZE = {"drone": 3.5, "missile": 6.0, "rocket": 3.0, "bunker": 5.0, "nuke": 18.0, "loiter": 2.5}
BLAST = {"rocket": 8.0, "drone": 12.0, "loiter": 12.0, "missile": 18.0, "bunker": 24.0}


def size_of(weapon: str | None) -> float:
    w = (weapon or "").lower()
    return SIZE.get(w, next((v for k, v in SIZE.items() if k in w), 4.0))


def blast_of(weapon: str | None) -> float:
    w = (weapon or "").lower()
    return BLAST.get(w, next((v for k, v in BLAST.items() if k in w), 16.0))


@dataclass
class Track:
    """Путь по тикам: `t` (int, по возрастанию) и `pos` (n, 3)."""
    id: str
    weapon: str
    t: np.ndarray
    pos: np.ndarray

    def at(self, t) -> np.ndarray:
        """Место в тики t (с долей; вне пути — NaN)."""
        t = np.asarray(t, dtype=np.float64)
        out = np.stack([np.interp(t, self.t, self.pos[:, k], left=np.nan, right=np.nan) for k in range(3)], axis=-1)
        return out

    @property
    def first(self) -> int:
        return int(self.t[0])

    @property
    def last(self) -> int:
        return int(self.t[-1])


@dataclass
class Impact:
    t: int
    pos: np.ndarray
    weapon: str
    track: str | None = None
    ground: bool = True

    @property
    def blast(self) -> float:
        return blast_of(self.weapon)


@dataclass
class Smoke:
    """Дым ударов по земле — столб модели `Plume` превью (replayscan, по `BlastEffects` мода): радиус
    max(8, 0,75 шара) раздувается за 20 тиков, верх — шар сразу, пыль за 5 с до 30 блоков, ось сносит ветер после 20
    тиков, живёт 500 тиков. Столб — стопка гауссовых клубов (σ = 0,6 радиуса): свет сквозь неё считается точно, через
    середину столба поперёк — около e⁻³."""
    life: float = 500.0
    wind: tuple = (0.015, 0.0, 0.0073)  # Plume.WIND_X, WIND_Z, блоков за тик
    own: float = 12.0                   # Plume.OWN: дым ближе этого к цели — её же удар

    def radius(self, size: float, age: float) -> float:
        return max(8.0, 0.75 * size) * min(1.0, 0.45 + age / 36)

    def top(self, size: float, age: float) -> float:
        return max(0.8 * size, 30 * min(1.0, age / 100))

    def puffs(self, impacts: list[Impact], t: float) -> np.ndarray:
        """Клубы в тик t: (n, 8) — x, y, z, сигма, плотность на блок, место удара (x, y, z)."""
        out = []
        for im in impacts:
            age = t - im.t
            if not im.ground or age < 0 or age > self.life:
                continue
            size = im.blast
            r, top = self.radius(size, age), self.top(size, age)
            sigma = 0.6 * r
            base = im.pos + np.array(self.wind) * max(0.0, age - 20)
            n = max(1, int(math.ceil(top / sigma)) + 1)
            k = 3.0 / (6.3 * sigma)
            for j in range(n):
                c = base + np.array([0, top * j / max(1, n - 1), 0])
                out.append((c[0], c[1], c[2], sigma, k, im.pos[0], im.pos[1], im.pos[2]))
        return np.array(out, dtype=np.float64).reshape(-1, 8)


@dataclass
class Trails:
    """Клубы объёма у горящего двигателя (`Exhaust.volume` мода: клуб за тик у сопла, пока горит ускоритель —
    фазы `ignition` и `boost`; у МБР — весь полёт) и облако старта МБР (`Exhaust.launchCloud`). Кладёт их клиент, у
    которого есть сущность снаряда, — тики журнала, где место снаряда от сущности, а не от сервера. Они висят в воздухе
    полминуты и больше, их считает проверка кадров (`FxPool.transmittance`), лента шлейфа — нет. Клуб — гауссов шар
    (σ = 0,55 радиуса квадрата), свет сквозь середину — как у частицы: 1 − непрозрачность (0,45, тает с 55 % жизни).
    Ветер сносит клуб, как тик `FxParticle`: скорость += ветер, шаг, скорость × сопротивление; у облака старта
    сопротивление 0,95 и 0,94 — сносит его вдвое быстрее следа (0,9)."""
    # оружие → (поперечник клуба вначале, в конце, жизнь, тиков): `Exhaust.rocket`, `booster`, `icbm`
    SPEC = {"rocket": (0.7, 3.6, 520.0), "missile": (0.5, 3.5, 320.0), "drone": (0.35, 2.45, 320.0),
            "loiter": (0.35, 2.45, 320.0), "nuke": (1.8, 8.0, 1050.0)}
    ALPHA = 0.45
    WIND = (0.0022, 0.0, 0.0011)  # Fx.WIND_X, WIND_Z: прибавка к скорости клуба за тик
    DRAG = 0.9  # ClientWeaponSpec.Trail.DRAG
    t: np.ndarray = field(default_factory=lambda: np.zeros(0))
    pos: np.ndarray = field(default_factory=lambda: np.zeros((0, 3)))
    s0: np.ndarray = field(default_factory=lambda: np.zeros(0))
    s1: np.ndarray = field(default_factory=lambda: np.zeros(0))
    life: np.ndarray = field(default_factory=lambda: np.zeros(0))
    alpha: np.ndarray = field(default_factory=lambda: np.zeros(0))
    drag: np.ndarray = field(default_factory=lambda: np.zeros(0))

    @staticmethod
    def of(tracks: dict, lit: dict) -> "Trails":
        ts, ps, a0, a1, lf, al, dr = [], [], [], [], [], [], []
        for tid, tr in tracks.items():
            spec = Trails.SPEC.get(tr.weapon)
            ticks = lit.get(tid, [])
            if spec is None or not len(ticks):
                continue
            tt = np.array(sorted(set(ticks)), dtype=np.float64)
            pp = tr.at(tt)
            ok = ~np.isnan(pp[:, 0])
            tt, pp = tt[ok], pp[ok]
            ts.append(tt)
            ps.append(pp)
            for arr, v in ((a0, spec[0]), (a1, spec[1]), (lf, spec[2]), (al, Trails.ALPHA), (dr, Trails.DRAG)):
                arr.append(np.full(len(tt), v))
            if tr.weapon == "nuke" and len(tt) and tt[0] - tr.t[0] < 140:
                # облако старта: кольцо до ~20 блоков у стола и столб над ним, первые 140 тиков полёта (`launchCloud`),
                # если сущность у клиента уже была; стол — 9 блоков под ракетой в первый тик у клиента
                pad = pp[0] - np.array([0, 9.0, 0])
                t0 = float(tt[0])
                for j in range(8):
                    ang = j * math.pi / 4
                    for rr in (6.0, 14.0, 22.0):
                        ts.append(np.array([t0 + rr]))
                        ps.append((pad + np.array([math.cos(ang) * rr, 2.0, math.sin(ang) * rr]))[None])
                        for arr, v in ((a0, 8.0), (a1, 22.0), (lf, 760.0), (al, 0.85), (dr, 0.95)):
                            arr.append(np.array([v]))
                for h in (6.0, 14.0, 22.0):
                    ts.append(np.array([t0 + h / 2]))
                    ps.append((pad + np.array([0, h, 0]))[None])
                    for arr, v in ((a0, 6.0), (a1, 18.0), (lf, 700.0), (al, 0.8), (dr, 0.94)):
                        arr.append(np.array([v]))
        if not ts:
            return Trails()
        cat = np.concatenate
        return Trails(cat(ts), cat(ps), cat(a0), cat(a1), cat(lf), cat(al), cat(dr))

    @staticmethod
    def drift(age: np.ndarray, d: np.ndarray) -> np.ndarray:
        """Сколько ветров (`WIND`) снесло клуб за age тиков при сопротивлении d: сумма шагов (1 − d^k)/(1 − d)."""
        return np.maximum(0.0, (age - d * (1 - d ** age) / (1 - d)) / (1 - d))

    def puffs(self, t: float) -> np.ndarray:
        """Клубы в тик t: (n, 8), как у `Smoke.puffs`; «свой удар» у них — далеко (не свои никому)."""
        if not len(self.t):
            return np.zeros((0, 8))
        age = t - self.t
        alive = (age >= 0) & (age < self.life)
        if not alive.any():
            return np.zeros((0, 8))
        age, life = age[alive], self.life[alive]
        f = age / life
        quad = self.s0[alive] + (self.s1[alive] - self.s0[alive]) * (1 - (1 - f) ** 3)
        sigma = np.maximum(0.55 * quad / 2, 0.2)
        fade = np.clip(age / 2, 0, 1) * np.where(f < 0.55, 1.0, np.maximum(0, 1 - (f - 0.55) / 0.45))
        tau = -np.log(np.maximum(1e-3, 1 - self.alpha[alive] * fade))
        k = tau / (sigma * math.sqrt(2 * math.pi))
        drift = self.drift(age, self.drag[alive])[:, None] * np.array(self.WIND)
        p = self.pos[alive] + drift
        out = np.zeros((len(p), 8))
        out[:, :3], out[:, 3], out[:, 4], out[:, 5:8] = p, sigma, k, 1e9
        return out[fade > 0.01]


def transmittance(puffs: np.ndarray, a: np.ndarray, b: np.ndarray, spare: float = 0.0) -> np.ndarray:
    """Доля света по отрезкам a→b (…, 3) сквозь клубы (n, 5 или 8); у конца b не считаются `spare` блоков (огонь самой
    цели) и клубы удара ближе `Smoke.own` к b (её же дым, как `Plume.blocks`)."""
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    both = np.broadcast_shapes(a.shape, b.shape)
    a, b = np.broadcast_to(a, both), np.broadcast_to(b, both)
    if len(puffs) == 0:
        return np.ones(a.shape[:-1])
    d = b - a
    L = np.linalg.norm(d, axis=-1)
    u = d / np.maximum(L, 1e-9)[..., None]
    end = np.maximum(L - spare, 0)
    tau = np.zeros(a.shape[:-1])
    from math import sqrt, pi
    from scipy.special import erf
    # клубы далеко от всех отрезков не считаются: отрезок «среднего» глаза к «средней» цели и разброс концов
    am, bm = a.reshape(-1, 3).mean(0), b.reshape(-1, 3).mean(0)
    spread = max(float(np.linalg.norm(a.reshape(-1, 3) - am, axis=1).max()),
                 float(np.linalg.norm(b.reshape(-1, 3) - bm, axis=1).max()))
    seg = bm - am
    k = np.clip(((puffs[:, :3] - am) @ seg) / max(float(seg @ seg), 1e-9), 0, 1)
    miss = np.linalg.norm(puffs[:, :3] - (am + k[:, None] * seg), axis=1)
    puffs = puffs[miss < spread + 3.5 * puffs[:, 3]]
    if not len(puffs):
        return np.ones(a.shape[:-1])
    shape = a.shape[:-1]
    A, U = a.reshape(-1, 3), u.reshape(-1, 3)
    E, B = end.reshape(-1), b.reshape(-1, 3)
    tau = np.zeros(len(A))
    for i in range(0, len(puffs), 512):
        c = puffs[i:i + 512]
        p, s, k = c[:, :3], c[:, 3], c[:, 4]
        k = np.broadcast_to(k, (len(A), len(c)))
        if c.shape[1] >= 8:
            mine = np.linalg.norm(B[:, None, :] - c[None, :, 5:8], axis=-1) <= Smoke.own
            k = k * ~mine
        w = p[None] - A[:, None]
        along = (w * U[:, None]).sum(-1)
        miss2 = (w * w).sum(-1) - along ** 2
        g = np.exp(-np.maximum(miss2, 0) / (2 * s * s))
        span = 0.5 * (erf((E[:, None] - along) / (sqrt(2) * s)) - erf((0 - along) / (sqrt(2) * s)))
        tau += (k * s * sqrt(2 * pi) * g * span).sum(1)
    tau = tau.reshape(shape)
    return np.exp(-tau)


@dataclass
class Scene:
    world: W.World
    tracks: dict[str, Track] = field(default_factory=dict)
    impacts: list[Impact] = field(default_factory=list)
    people: dict[str, Track] = field(default_factory=dict)
    marks: list[tuple[int, str]] = field(default_factory=list)
    cams: Track | None = None  # камера сценария (место; взгляд — в `cam_look`)
    cam_look: np.ndarray | None = None
    smoke: Smoke = field(default_factory=Smoke)
    nukes: list[tuple[int, np.ndarray, float]] = field(default_factory=list)
    clouds: float = math.nan  # низ слоя облаков (NaN — нет), толщина — 6 блоков (`Rig.CLOUD_DEPTH`)
    fog: float = 410.0        # дальше этого по горизонтали объект в тумане края прорисовки (0,8 × 32 чанка, `Rig.FOG`)
    trails: Trails = field(default_factory=Trails)
    _puffs: dict = field(default_factory=dict, repr=False, compare=False)

    def puffs(self, t: float) -> np.ndarray:
        """Клубы дыма в тик t (дым ударов и шлейфы ускорителей); поиск спрашивает одни и те же тики сотни раз."""
        got = self._puffs.get(t)
        if got is None:
            a = self.smoke.puffs(self.impacts, t)
            b = self.trails.puffs(t)
            got = np.concatenate([a, b]) if len(b) else a
            if len(self._puffs) > 4000:
                self._puffs.clear()
            self._puffs[t] = got
        return got

    def mark(self, name: str) -> int | None:
        return next((t for t, m in self.marks if m == name), None)


# ---------------------------------------------------------------- журнал плана трейлера (EventLog)


# фазы, в которых горит ускоритель (`FlightPhase.boosterLit`: IGNITION и BOOST, в журнале — номер фазы); у МБР след —
# весь подъём
LIT = {"1", "2", "ignition", "boost"}


def read_eventlog(path: str):
    """Строки журнала EventLog: пути снарядов, удары, люди, камера сценария, отметки."""
    opener = gzip.open if path.endswith(".gz") else open
    flights: dict[int, dict] = {}
    pts: dict[int, list] = {}
    people: dict[str, list] = {}
    cam = []
    marks = []
    impacts = []
    nukes = []
    with opener(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue
            t = d.get("t")
            if t is None:
                continue
            if "new" in d:
                flights.setdefault(int(d["new"]), {}).update({"id": d.get("id"), "weapon": d.get("weapon") or "", "t": t})
            if "f" in d:
                for e in d["f"]:
                    pts.setdefault(int(e[0]), []).append((t, e[1], e[2], e[3]))
                    # клубы кладёт клиент, у которого есть сущность снаряда (шестое поле — 1); место по данным
                    # сервера (0) — снаряд далеко от записывающего, дыма у него в повторе нет
                    if len(e) > 5 and int(e[5]) == 0:
                        continue
                    info = flights.setdefault(int(e[0]), {})
                    info.setdefault("seen", []).append(t)
                    if len(e) > 4 and str(e[4]).lower() in LIT:
                        info.setdefault("lit", []).append(t)
            if "impact" in d and d.get("pos"):
                impacts.append((t, int(d["impact"]), d["pos"]))
            if "cam" in d and d["cam"]:
                cam.append((t, *d["cam"]))
            if "p" in d:
                for e in d["p"]:
                    people.setdefault(str(e[0]), []).append((t, e[1], e[2], e[3]))
            if "mark" in d:
                marks.append((t, str(d["mark"])))
            if "nuke" in d:
                nukes.append((t, d["nuke"], d.get("kt", 0)))
    return flights, pts, people, cam, marks, impacts, nukes


def from_eventlog(path: str, wld: W.World) -> Scene:
    flights, pts, people, cam, marks, impacts, nukes = read_eventlog(path)
    sc = Scene(wld)
    for i, rows in pts.items():
        rows.sort()
        a = np.array(rows, dtype=np.float64)
        # один тик — одна точка
        _, keep = np.unique(a[:, 0], return_index=True)
        a = a[keep]
        info = flights.get(i, {})
        sc.tracks[str(i)] = Track(str(i), info.get("weapon", ""), a[:, 0].astype(np.int64), a[:, 1:4])
    for name, rows in people.items():
        a = np.array(sorted(rows), dtype=np.float64)
        _, keep = np.unique(a[:, 0], return_index=True)
        a = a[keep]
        sc.people[name] = Track(name, "person", a[:, 0].astype(np.int64), a[:, 1:4])
    for t, i, pos in impacts:
        weapon = sc.tracks.get(str(i)).weapon if str(i) in sc.tracks else ""
        p = np.array(pos, dtype=np.float64)
        sc.impacts.append(Impact(int(t), p, weapon, str(i), ground=_near_ground(wld, p)))
    if cam:
        a = np.array(sorted(cam), dtype=np.float64)
        _, keep = np.unique(a[:, 0], return_index=True)
        a = a[keep]
        sc.cams = Track("cam", "camera", a[:, 0].astype(np.int64), a[:, 1:4])
        sc.cam_look = a[:, 4:]
    sc.marks = sorted(marks)
    sc.nukes = [(int(t), np.array(p, dtype=np.float64), float(kt)) for t, p, kt in nukes]
    # МБР кладёт клуб каждый тик, пока сущность у клиента (`Exhaust.icbm`, без фазы), остальные — пока горит ускоритель
    lit = {str(i): info.get("seen" if sc.tracks.get(str(i)) is not None and sc.tracks[str(i)].weapon == "nuke" else "lit", [])
           for i, info in flights.items()}
    sc.trails = Trails.of(sc.tracks, lit)
    return sc


def smooth_tracks(sc: Scene, sigma: float):
    """Журнал до очереди мест мода (13.10, film-war2 и раньше): место снаряда у записывающего клиента то стоит, то
    прыгает на два шага, а повтор с нынешним модом показывает его ровно (`Shown` трейлера прогоняет пакеты очередью).
    Здесь — гаусс по тикам: для взгляда камеры этого хватает (рывок — полшага, взгляд и так сглажен)."""
    from scipy.ndimage import gaussian_filter1d
    for tr in sc.tracks.values():
        if len(tr.t) > 4:
            tr.pos = gaussian_filter1d(tr.pos, sigma, axis=0, mode="nearest")


def _near_ground(wld: W.World, p: np.ndarray) -> bool:
    """Удар у поверхности: блок не дальше 3 блоков вниз (иначе — в воздухе: дыма столбом нет)."""
    probe = np.array([p - np.array([0, k, 0]) for k in (0, 1, 2, 3)])
    return bool((wld.kind_at(probe) != W.AIR).any())


def bounds_of(path: str, a: int, b: int, pad: float = 160.0):
    """Рамка мира под план: пути и удары окна [a, b] и камера сценария с запасом `pad` по сторонам."""
    flights, pts, people, cam, marks, impacts, nukes = read_eventlog(path)
    xs = []
    for rows in pts.values():
        xs += [(x, y, z) for t, x, y, z in rows if a <= t <= b]
    xs += [tuple(p) for t, i, p in impacts if a <= t <= b]
    xs += [tuple(c[1:4]) for c in cam if a <= c[0] <= b]
    if not xs:
        raise SystemExit("в окне плана нет ни путей, ни ударов, ни камеры")
    q = np.array(xs)
    lo = np.floor(q.min(0) - pad).astype(int)
    hi = np.ceil(q.max(0) + pad).astype(int)
    lo[1] = max(-64, int(q[:, 1].min() - 64))
    hi[1] = min(320, int(q[:, 1].max() + 64))
    return lo, hi
