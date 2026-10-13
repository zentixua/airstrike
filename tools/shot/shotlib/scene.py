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
    """Дым ударов: столб клубов над каждым ударом по земле, растёт и редеет, сносится ветром. Клуб — гауссово облако:
    свет сквозь него на отрезке считается точно (интеграл гаусса по прямой). Числа — по эффектам Incoming
    (`BlastEffects`: клубы ~R, живут 240–380 тиков) с запасом: столб выше и дольше, как дальняя картинка мода."""
    life: float = 700.0
    height: float = 3.2      # высота столба — доля огненного шара удара на подъём
    rise: float = 0.08       # блоков за тик
    wind: tuple = (0.02, 0.0, 0.01)  # снос, блоков за тик
    density: float = 0.22    # оптическая плотность в середине клуба на блок

    def puffs(self, impacts: list[Impact], t: float) -> np.ndarray:
        """Клубы в тик t: (n, 5) — x, y, z, сигма, плотность."""
        out = []
        for im in impacts:
            age = t - im.t
            if not im.ground or age < 2 or age > self.life:
                continue
            r = im.blast
            fade = 1.0 if age < self.life * 0.45 else max(0.0, 1 - (age - self.life * 0.45) / (self.life * 0.55))
            top = min(r * self.height, r * 0.6 + self.rise * age)
            n = max(2, int(top / max(2.0, r * 0.5)) + 1)
            drift = np.array(self.wind) * age
            for k in range(n):
                h = top * k / max(1, n - 1)
                sigma = r * (0.35 + 0.25 * min(1.0, age / 120)) * (1 + 0.5 * h / max(1.0, top))
                c = im.pos + np.array([0, r * 0.3 + h, 0]) + drift * (0.3 + h / max(1.0, top))
                out.append((c[0], c[1], c[2], sigma, self.density * fade))
        return np.array(out, dtype=np.float64).reshape(-1, 5)


def transmittance(puffs: np.ndarray, a: np.ndarray, b: np.ndarray, spare: float = 0.0) -> np.ndarray:
    """Доля света по отрезкам a→b (…, 3) сквозь клубы (n, 5); у конца b не считаются `spare` блоков (огонь самой цели)."""
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
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
    for c in puffs:
        p, s, k = c[:3], c[3], c[4]
        w = p - a
        along = (w * u).sum(-1)
        miss2 = (w * w).sum(-1) - along ** 2
        g = np.exp(-np.maximum(miss2, 0) / (2 * s * s))
        span = 0.5 * (erf((end - along) / (sqrt(2) * s)) - erf((0 - along) / (sqrt(2) * s)))
        tau += k * s * sqrt(2 * pi) * g * span
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

    def puffs(self, t: float) -> np.ndarray:
        return self.smoke.puffs(self.impacts, t)

    def mark(self, name: str) -> int | None:
        return next((t for t, m in self.marks if m == name), None)


# ---------------------------------------------------------------- журнал плана трейлера (EventLog)


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
                flights[d["new"]] = {"id": d.get("id"), "weapon": d.get("weapon") or "", "t": t}
            if "f" in d:
                for e in d["f"]:
                    pts.setdefault(int(e[0]), []).append((t, e[1], e[2], e[3]))
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
    return sc


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
