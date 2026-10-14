#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "scipy"]
# ///
"""Разведка мест сцен трейлера по данным карты: где стоит камера каждого плана, куда смотрит и видна ли оттуда цель,
и черновой кадр лучами по блокам. Вход — сценарий (замысел камеры у каждого плана) и высоты из `mapstudy.py`
(`top.npy`, `ground.npy`, `water.npy`, `mapstudy.json` — улицы); выход — позы и сводка, по ним ставятся сцены,
аниматик и кадры мест на ноутбуке.

    uv run tools/trailer/scout.py <сценарий.json> <папка mapstudy> <выход> [--regions <мир>/region] [--previs]
        [--only n13,n22] [--size 768x432]

Сценарий: {"places": {имя: точка}, "plans": [{"id", "title", "time", "camera", "fov", "pose", "target", "strikes",
"frames", "dur", "key", "need_sight"}]}. Точка — `[x, z]` (верх колонки + 2: глаз стоящего), `[x, y, z]`,
`{"at": [x, z], "above": h}` (над верхом колонки), `{"at": [x, z], "ground": h}` (над землёй — под домом тоже),
`{"place": имя}` или имя строкой; у цели `"above"` по умолчанию 0 (верх колонки: крыша, вершина). Замысел камеры
(`pose.kind`; места `near`, `at`, `toward` — точки или имена):
- `fixed` — `eye` и `look` (точка) или `yaw`/`pitch`;
- `roof` — крыша (строение от `min_height` блоков, ровная 3×3) в `radius` от `near`, в `dist` блоков от цели, откуда
  цель видна, глаз — у края крыши к цели; из подходящих — выше над целью и ближе к середине `dist`;
- `ground` — то же на земле вне домов и воды (`above` — глаз);
- `peak` — самая высокая колонка в `radius` от `at`, откуда видна цель;
- `street` — улица-каньон из `mapstudy.json`, ближайшая к `near`: путь камеры вдоль её оси на `above` над землёй,
  к цели (или к `toward`), `frames` кадров за `dur` секунд;
- `path` — путь по точкам `points`.
Взгляд — на `look` или цель; у пути и улицы без них (или с `look_ahead`) — вперёд по пути; `tilt` — довернуть вниз.
Удары (`strikes`) — для чернового кадра: снаряд по прямой с `from` (на `cruise` над верхом) до `to` (цель плана), удар
— на `eta` секунде плана; кадр одного места — на `key` секунде.

Видна ли цель — луч по карте высот (`top`, твёрдый верх без листвы) с шагом в блок; дом самой цели и последние `spare`
блоков не в счёт (`Heights.sees`). Черновой кадр (`--previs`; без `--regions` — пустая рамка, только снаряды) —
`tools/shot/shotlib/previs`: блоки рамки поля зрения до `reach` блоков (не дальше цели + 150, площадь — до `cap` плана,
по умолчанию `CAP`),
`night` — темнее; листы `sheet-N.jpg` — первые кадры планов. Мир остаётся там, где лежит; наружу — `poses.json`,
`scout.md` и картинки.
"""
import argparse
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(os.path.dirname(HERE), "shot")

NIGHT = ("night", "midnight")
REACH = 600.0


class Heights:
    """Высоты карты из mapstudy: `top[z, x]` (твёрдый верх) и `ground[z, x]`, начало — угол рамки."""

    def __init__(self, top, ground, x0, z0, streets=(), tall=(), water=None, sea=62, canopy=None):
        self.top = np.asarray(top, dtype=np.float32)
        self.ground = np.asarray(ground, dtype=np.float32)
        # верх всего (листва, трава, вода): закрывает взгляд и место камеры; в прежних выводах без него — твёрдый верх
        self.canopy = np.maximum(self.top, np.asarray(canopy, np.float32)) if canopy is not None else self.top
        # вода сверху: из mapstudy, а в прежних выводах без неё — верх ниже уровня моря (дно озера)
        self.water = np.asarray(water, bool) if water is not None else self.top < sea
        self.x0, self.z0 = int(x0), int(z0)
        self.streets = list(streets)
        self.tall = list(tall)

    @classmethod
    def load(cls, folder):
        with open(os.path.join(folder, "mapstudy.json"), encoding="utf-8") as f:
            st = json.load(f)
        x0, z0 = st["box"][:2]
        def opt(name):
            f = os.path.join(folder, name)
            return np.load(f) if os.path.exists(f) else None
        return cls(np.load(os.path.join(folder, "top.npy")), np.load(os.path.join(folder, "ground.npy")), x0, z0,
                   st.get("streets", ()), st.get("tall", ()), opt("water.npy"), canopy=opt("canopy.npy"))

    def cell(self, x, z):
        zi = np.clip(np.floor(np.asarray(z) - self.z0).astype(np.int64), 0, self.top.shape[0] - 1)
        xi = np.clip(np.floor(np.asarray(x) - self.x0).astype(np.int64), 0, self.top.shape[1] - 1)
        return zi, xi

    def top_at(self, x, z):
        zi, xi = self.cell(x, z)
        return self.top[zi, xi] + 1          # над верхним блоком

    def block_at(self, x, z):
        """Над верхом всего, что закрывает взгляд (с листвой)."""
        zi, xi = self.cell(x, z)
        return self.canopy[zi, xi] + 1

    def open_sky(self, zi, xi, r=2, tol=1.5):
        """В клетках и вокруг (±r) нет листвы над твёрдым верхом (трава не в счёт): камера не под деревом и не в кроне."""
        out = np.ones(len(zi), bool)
        for dz in range(-r, r + 1):
            for dx in range(-r, r + 1):
                zz = np.clip(zi + dz, 0, self.top.shape[0] - 1)
                xx = np.clip(xi + dx, 0, self.top.shape[1] - 1)
                out &= self.canopy[zz, xx] - self.top[zz, xx] <= tol
        return out

    def ground_at(self, x, z):
        zi, xi = self.cell(x, z)
        return self.ground[zi, xi] + 1

    def sees(self, a, b, spare=3.0, step=1.0, own=48.0):
        """Луч от a к b над картой высот: ни одна колонка по пути не выше луча. Не в счёт — `spare` блоков у b и дом
        самой цели: от b назад, пока колонки той высоты, что под b (± 2), не дальше `own` блоков (цель — середина
        высотки, видна её стена)."""
        a, b = np.asarray(a, float), np.asarray(b, float)
        d = math.hypot(b[0] - a[0], b[2] - a[2])
        if d < 1:
            return True
        n = max(2, int(d / step))
        k = np.arange(1, n) / n
        x, y, z = (a[i] + (b[i] - a[i]) * k for i in range(3))
        block = self.block_at(x, z)
        back = (1 - k) * d
        same = (np.abs(block - self.top_at(b[0], b[2])) <= 2) & (back <= own)
        skip = np.cumprod(same[::-1])[::-1].astype(bool) | (back < spare)
        return bool((block[~skip] <= y[~skip] + 0.5).all())


def point(h, spec, places, default_above=2.0):
    """Точка сценария → [x, y, z] мира (см. шапку)."""
    if isinstance(spec, str):
        spec = {"place": spec}
    if isinstance(spec, dict) and "place" in spec:
        base = places[spec["place"]]
        extra = {k: v for k, v in spec.items() if k != "place"}
        spec = dict(base, **extra) if isinstance(base, dict) else (dict({"at": base}, **extra) if extra else base)
    if isinstance(spec, dict):
        x, z = spec["at"]
        if "ground" in spec:
            return np.array([x, float(h.ground_at(x, z)) + spec["ground"], z], float)
        return np.array([x, float(h.top_at(x, z)) + spec.get("above", default_above), z], float)
    if len(spec) == 3:
        return np.array(spec, float)
    x, z = spec
    return np.array([x, float(h.top_at(x, z)) + default_above, z], float)


def xz(h, spec, places):
    """Место замысла (`near`, `at`, `toward`) — [x, z]: точка сценария или имя места."""
    return [float(v) for v in point(h, spec, places)[[0, 2]]]


def look_angles(eye, at):
    d = np.asarray(at, float) - np.asarray(eye, float)
    hz = math.hypot(d[0], d[2])
    return math.degrees(math.atan2(-d[0], d[2])), math.degrees(math.atan2(-d[1], max(hz, 1e-9)))


def flat(h, zi, xi, tol=1.0):
    """Ровная площадка 3×3 вокруг клеток (крыша, где можно стоять)."""
    out = np.ones(len(zi), bool)
    t0 = h.top[zi, xi]
    for dz in (-1, 0, 1):
        for dx in (-1, 0, 1):
            zz = np.clip(zi + dz, 0, h.top.shape[0] - 1)
            xx = np.clip(xi + dx, 0, h.top.shape[1] - 1)
            out &= np.abs(h.top[zz, xx] - t0) <= tol
    return out


def candidates(h, near, radius, step=3):
    xs = np.arange(near[0] - radius, near[0] + radius + 1, step)
    zs = np.arange(near[1] - radius, near[1] + radius + 1, step)
    X, Z = np.meshgrid(xs, zs)
    m = np.hypot(X - near[0], Z - near[1]) <= radius
    X, Z = X[m], Z[m]
    zi, xi = h.cell(X, Z)
    return X.astype(float) + 0.5, Z.astype(float) + 0.5, zi, xi


def pick_spot(h, pose, target, roof, places):
    """Крыша или место на земле с видом на цель (см. шапку): (глаз, заметка) или (None, почему нет)."""
    near = xz(h, pose["near"], places)
    radius = pose.get("radius", 60)
    lo, hi = pose.get("dist", (0, 1e9))
    X, Z, zi, xi = candidates(h, near, radius)
    bh = h.top[zi, xi] - h.ground[zi, xi]
    if roof:
        ok = (bh >= pose.get("min_height", 12)) & flat(h, zi, xi)
    else:
        ok = (bh <= 1.5) & flat(h, zi, xi)
    d = np.hypot(X - target[0], Z - target[2])
    ok &= (d >= lo) & (d <= hi) & ~h.water[zi, xi] & h.open_sky(zi, xi)
    if not ok.any():
        what = "крыши" if roof else "места на земле"
        return None, f"нет {what} в {radius} блоках от {near[0]:.0f}, {near[1]:.0f} в {lo:.0f}–{hi:.0f} блоках от цели"
    idx = np.flatnonzero(ok)
    above = pose.get("above", 1.7)
    eyes = np.stack([X[idx], h.top[zi[idx], xi[idx]] + 1 + above, Z[idx]], axis=1)
    # из видящих — выше над целью и ближе к середине кольца
    mid = (lo + min(hi, 2 * lo + 200)) / 2 if hi < 1e8 else d[idx].mean()
    score = (eyes[:, 1] - target[1]) * 0.5 - np.abs(d[idx] - mid) * 0.3
    order = np.argsort(-score)
    for k in order[:400]:
        e = to_edge(h, eyes[k], target) if roof else eyes[k]
        if h.sees(e, target, spare=pose.get("spare", 3.0)):
            dd = math.hypot(e[0] - target[0], e[2] - target[2])
            if roof:
                tall = float(h.top_at(e[0], e[2]) - h.ground_at(e[0], e[2]))
                return e, f"крыша {tall:.0f} блоков над землёй, у края, до цели {dd:.0f}"
            return e, f"земля, до цели {dd:.0f}"
    return None, f"из {len(idx)} мест ни с одного не видно цели"


def to_edge(h, eye, target, tol=1.0, most=80.0):
    """Глаз — к краю крыши в сторону цели (с середины крыши вниз не видно): по крыше, пока её верх тот же."""
    e = np.array(eye, float)
    d = np.array([target[0] - e[0], target[2] - e[2]])
    n = np.linalg.norm(d)
    if n < 1:
        return e
    d /= n
    t0 = float(h.top_at(e[0], e[2]))
    s = np.arange(0.5, min(most, n), 0.5)
    ok = np.abs(h.top_at(e[0] + d[0] * s, e[2] + d[1] * s) - t0) <= tol
    last = s[np.cumprod(ok).astype(bool)]
    step = float(last[-1]) - 0.5 if len(last) else 0.0
    return np.array([e[0] + d[0] * step, e[1], e[2] + d[1] * step])


def street_path(h, pose, target, n, places):
    near = xz(h, pose["near"], places)
    best, bd = None, 1e18
    for s in h.streets:
        a, b = np.array(s["from"], float), np.array(s["to"], float)
        ab = b - a
        t = np.clip(np.dot(np.array(near, float) - a, ab) / max(np.dot(ab, ab), 1e-9), 0, 1)
        dd = np.linalg.norm(a + ab * t - np.array(near, float))
        if dd < bd:
            best, bd = s, dd
    if best is None:
        return None, "в mapstudy.json нет улиц"
    a, b = np.array(best["from"], float), np.array(best["to"], float)
    toward = pose.get("toward")
    aim = np.array(xz(h, toward, places)) if toward is not None else (target[[0, 2]] if target is not None else b)
    if np.linalg.norm(a - aim) < np.linalg.norm(b - aim):
        a, b = b, a
    k = np.linspace(0, 1, n)
    line = a[None] + (b - a)[None] * k[:, None]
    y = h.ground_at(line[:, 0], line[:, 1]) + pose.get("above", 10)
    return np.stack([line[:, 0] + 0.5, y, line[:, 1] + 0.5], axis=1), \
        f"улица {best['from']}→{best['to']} ({best['length']} блоков, стороны {best['sides']:.0f}), " \
        f"{bd:.0f} блоков от замысла"


def resolve(h, plan, places):
    """План → позы кадров: [{t, eye, yaw, pitch, fov}], цель, заметки, всё ли нашлось."""
    pose = plan.get("pose", {})
    kind = pose.get("kind", "fixed")
    fov = float(plan.get("fov", 50))
    target = point(h, plan["target"], places, 0.0) if plan.get("target") is not None else None
    n = int(plan.get("frames", 3 if kind in ("street", "path") else 1))
    dur = float(plan.get("dur", 2.0))
    times = [dur * i / max(1, n - 1) for i in range(n)] if n > 1 else [plan.get("key", 0.0)]
    notes, ok = [], True
    eyes = None
    if kind == "fixed":
        eyes = [point(h, pose["eye"], places)] * n
        notes.append("место задано")
    elif kind in ("roof", "ground"):
        e, why = pick_spot(h, pose, target, kind == "roof", places)
        notes.append(why)
        if e is None:
            ok = False
            e = point(h, pose["near"], places)
        eyes = [e] * n
    elif kind == "peak":
        X, Z, zi, xi = candidates(h, xz(h, pose["at"], places), pose.get("radius", 30), 2)
        # сначала открытые (без деревьев над головой), выше — раньше
        order = np.lexsort((-h.top[zi, xi], ~h.open_sky(zi, xi)))
        e = None
        for k in order[:200]:
            cand = np.array([X[k], h.top[zi[k], xi[k]] + 1 + pose.get("above", 1.7), Z[k]])
            if target is None or h.sees(cand, target, spare=pose.get("spare", 3.0)):
                e = cand
                break
        if e is None:
            ok = False
            e = point(h, pose["at"], places)
            notes.append("с вершины цели не видно")
        else:
            far = "" if target is None else f", до цели {math.hypot(*(target - e)[[0, 2]]):.0f}"
            notes.append(f"вершина {e[1] - 1.7:.0f}{far}")
        eyes = [e] * n
    elif kind == "street":
        path, why = street_path(h, pose, target, n, places)
        notes.append(why)
        if path is None:
            ok = False
            path = np.array([point(h, pose["near"], places)] * n)
        eyes = list(path)
    elif kind == "path":
        pts = np.array([point(h, p, places, pose.get("above", 2.0)) for p in pose["points"]])
        seg = np.r_[0, np.cumsum(np.linalg.norm(np.diff(pts, axis=0), axis=1))]
        s = np.linspace(0, seg[-1], n)
        eyes = [np.array([np.interp(v, seg, pts[:, i]) for i in range(3)]) for v in s]
        notes.append(f"путь {seg[-1]:.0f} блоков")
    else:
        raise SystemExit(f"{plan['id']}: неизвестный замысел камеры {kind}")
    look = pose.get("look")
    look_p = point(h, look, places, 0.0) if look is not None and not isinstance(look, (int, float)) else None
    frames = []
    for i, (t, e) in enumerate(zip(times, eyes)):
        if "yaw" in pose:
            yaw, pitch = float(pose["yaw"]), float(pose.get("pitch", 0))
        else:
            at = look_p if look_p is not None else target
            if at is None or (kind in ("street", "path") and pose.get("look_ahead", look_p is None and target is None)):
                nxt = eyes[min(i + 1, n - 1)] if i + 1 < n else e + (e - eyes[max(i - 1, 0)])
                at = nxt + np.array([0, -2.0, 0])
            yaw, pitch = look_angles(e, at)
            pitch += float(pose.get("tilt", 0))
        frames.append({"t": round(t, 2), "eye": [round(float(v), 1) for v in e], "yaw": round(yaw, 1),
                       "pitch": round(pitch, 1), "fov": fov})
    sight = None
    if target is not None:
        sight = all(h.sees(np.array(f["eye"]), target, spare=pose.get("spare", 3.0)) for f in frames) \
            if kind not in ("street", "path") else any(h.sees(np.array(f["eye"]), target) for f in frames)
        if sight is False and plan.get("need_sight", True):
            ok = False
            notes.append("цель закрыта")
    far = None if target is None else round(float(math.hypot(*(target - np.array(frames[0]["eye"]))[[0, 2]])), 0)
    return {"id": plan["id"], "title": plan.get("title", ""), "time": plan.get("time", "day"),
            "camera": plan.get("camera", kind), "frames": frames,
            "target": None if target is None else [round(float(v), 1) for v in target], "distance": far,
            "sight": sight, "ok": ok, "notes": "; ".join(notes)}


# ---------------------------------------------------------------- черновой кадр

CAP = 1000 * 1000   # блоков площади рамки чернового кадра: kind, цвет и расстояния — около 3 ГБ на пике


def view_box(h, res, plan, aspect=16 / 9):
    """Рамка блоков для кадров плана: глаз и углы поля зрения до `reach` (не дальше цели + 150), цель; по высоте — от
    земли до верха строений рамки. Велика — дальность уменьшается, пока площадь больше `cap` плана (`CAP`; вид
    наискось через полкарты — больше, память растёт с площадью); камера и цель сами дальше — None."""
    reach = float(plan.get("reach", REACH))
    cap = float(plan.get("cap", CAP))
    if res["distance"] is not None:
        reach = min(reach, res["distance"] + 150)
    while True:
        pts = []
        for f in res["frames"]:
            e = np.array(f["eye"])
            pts.append(e)
            half = math.degrees(math.atan(math.tan(math.radians(f["fov"]) / 2) * aspect))   # по ширине кадра
            for da in (-half, 0, half):
                yaw = math.radians(f["yaw"] + da)
                pts.append(e + np.array([-math.sin(yaw), 0, math.cos(yaw)]) * reach)
        if res["target"] is not None:
            pts.append(np.array(res["target"]))
        P = np.array(pts)
        lo, hi = np.floor(P.min(0)) - 16, np.ceil(P.max(0)) + 16
        area = (hi[0] - lo[0]) * (hi[2] - lo[2])
        if area <= cap:
            break
        if reach < 120:
            return None
        reach *= 0.9 * math.sqrt(cap / area)
    zi0, zi1 = h.cell(0, lo[2])[0], h.cell(0, hi[2])[0]
    xi0, xi1 = h.cell(lo[0], 0)[1], h.cell(hi[0], 0)[1]
    g = h.ground[zi0:zi1 + 1, xi0:xi1 + 1]
    t = h.top[zi0:zi1 + 1, xi0:xi1 + 1]
    lo[1] = max(-64, min(float(g.min()) - 8, float(P[:, 1].min()) - 8))
    hi[1] = min(320, max(float(t.max()) + 4, float(P[:, 1].max()) + 8))
    return lo.astype(int), hi.astype(int), reach


def previs(res, plan, h, places, regions, out_dir, size, cache):
    """Черновые кадры плана лучами по блокам рамки (`shotlib.previs`); рамка прошлого плана берётся, если покрывает."""
    if SHOT not in sys.path:
        sys.path.insert(0, SHOT)
    from shotlib import previs as PV  # noqa: E402
    from shotlib import scene as SC  # noqa: E402
    from shotlib import world as W  # noqa: E402
    box = view_box(h, res, plan, size[0] / size[1])
    if box is None:
        res["notes"] += "; без чернового кадра: камера и цель не входят в рамку"
        return
    lo, hi, reach = box
    got = cache.get("box")
    if got is not None and (got[0] <= lo).all() and (got[1] >= hi).all():
        wld = cache["world"]
    else:
        cache.clear()
        wld = W.from_regions(regions, lo, hi) if regions else W.empty(lo, tuple(hi - lo))
        cache.update(box=(lo, hi), world=wld)
    lift = 0
    for f in res["frames"]:
        # глаз в блоке (место из данных высот под навесом, точка задана с ошибкой) — вверх до воздуха
        e = np.array(f["eye"], float)
        for k in range(60):
            if wld.kind_at(e[None] + np.array([[0, 0.3, 0]]))[0] not in (W.OPAQUE, W.SOFT, W.WATER):
                break
            e[1] += 1
        if e[1] > f["eye"][1]:
            lift = max(lift, e[1] - f["eye"][1])
            f["eye"][1] = round(float(e[1]), 1)
    if lift:
        res["notes"] += f"; глаз был в блоке — поднят на {lift:.0f}"
    sc = SC.Scene(world=wld)
    for j, s in enumerate(plan.get("strikes", [])):
        to = point(h, s.get("to", plan["target"]), places, 0.0)
        fr = point(h, s["from"], places, s.get("cruise", 40))
        eta = float(s.get("eta", 1.0)) * 20
        t = np.array([eta - float(s.get("flight", 6.0)) * 20, eta]).astype(int)
        weapon = s.get("weapon", "missile")
        sc.tracks[f"s{j}"] = SC.Track(f"s{j}", weapon, t, np.stack([fr, to]))
        sc.impacts.append(SC.Impact(int(eta), to, weapon))
    files = []
    night = res["time"] in NIGHT
    for k, f in enumerate(res["frames"]):
        im = PV.frame(sc, np.array(f["eye"]), f["yaw"], f["pitch"], 0.0, f["fov"], f["t"] * 20, size, reach=reach,
                      night=night)
        head = f"{res['id']}  {res['camera']}  {res['time']}  fov {f['fov']:.0f}"
        PV.overlay(im, [head, res["title"][:80]], thirds=False)
        fn = os.path.join(out_dir, f"{res['id']}-{k}.jpg")
        im.save(fn, quality=88)
        files.append(os.path.basename(fn))
    res["previs"] = files


def sheets(results, out_dir, cols=3, rows=4, scale=0.5):
    """Листы первых кадров планов (по 12 на лист, вполовину): sheet-1.jpg, … — для беглого просмотра."""
    from PIL import Image
    files = [os.path.join(out_dir, r["previs"][0]) for r in results if r.get("previs")]
    made = []
    for n in range(0, len(files), cols * rows):
        ims = [Image.open(f) for f in files[n:n + cols * rows]]
        w, h = int(ims[0].size[0] * scale), int(ims[0].size[1] * scale)
        S = Image.new("RGB", (w * cols, h * rows), (20, 20, 20))
        for k, im in enumerate(ims):
            S.paste(im.resize((w, h)), ((k % cols) * w, (k // cols) * h))
        fn = os.path.join(out_dir, f"sheet-{len(made) + 1}.jpg")
        S.save(fn, quality=85)
        made.append(os.path.basename(fn))
    return made


def report(results):
    L = ["# Разведка мест по данным", "", "| План | Время | Камера | До цели | Цель видна | Итог | Заметки |",
         "|---|---|---|---|---|---|---|"]
    for r in results:
        sight = "—" if r["sight"] is None else ("да" if r["sight"] else "нет")
        far = "—" if r["distance"] is None else f"{r['distance']:.0f}"
        L.append(f"| {r['id']} {r['title']} | {r['time']} | {r['camera']} | {far} | {sight} | "
                 f"{'ок' if r['ok'] else 'НЕТ'} | {r['notes']} |")
    bad = [r["id"] for r in results if not r["ok"]]
    L += ["", f"Не нашлось: {', '.join(bad)}" if bad else "Нашлось всё."]
    return "\n".join(L) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario")
    ap.add_argument("study")
    ap.add_argument("out")
    ap.add_argument("--regions")
    ap.add_argument("--previs", action="store_true")
    ap.add_argument("--only", default="")
    ap.add_argument("--size", default="768x432")
    a = ap.parse_args()
    with open(a.scenario, encoding="utf-8") as f:
        sc = json.load(f)
    h = Heights.load(a.study)
    os.makedirs(a.out, exist_ok=True)
    only = {s for s in a.only.split(",") if s}
    size = tuple(int(v) for v in a.size.split("x"))
    places = sc.get("places", {})
    results, cache = [], {}
    for plan in sc["plans"]:
        if only and plan["id"] not in only:
            continue
        r = resolve(h, plan, places)
        if a.previs:
            previs(r, plan, h, places, a.regions, a.out, size, cache)
        results.append(r)
        print(f"{r['id']}: {'ок' if r['ok'] else 'НЕТ'} — {r['notes']}", file=sys.stderr, flush=True)
    if a.previs and results:
        sheets(results, a.out)
    with open(os.path.join(a.out, "poses.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    with open(os.path.join(a.out, "scout.md"), "w", encoding="utf-8") as f:
        f.write(report(results))
    bad = sum(1 for r in results if not r["ok"])
    print(f"планов {len(results)}, не нашлось {bad}", file=sys.stderr)


if __name__ == "__main__":
    main()
