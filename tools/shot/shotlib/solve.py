"""Планировщик камеры: путь тела камеры, при котором план лучше всего по одной мере — той же, что потом проверяет кадры.

Мера (`evaluate`, меньше — лучше) по каждому тику окна: объект в кадре и в безопасной зоне, не за блоками и дымом,
размер читается; события плана (удары) видны в первые 12 тиков; камера не в блоках и не рядом с ними, кадр не смотрит
в неизвестные чанки; движение тела в его пределах (скорость, разгон, рывок, повороты взгляда), у FPV — летит туда, куда
смотрит. Поиск — кросс-энтропийный: семена по стилю (проходы с разных курсов и сторон, места штатива по кругу), лучшие
из них сгибаются случайными пробами вокруг лучшего, пока мера падает. Камера сама обходит дома и дым, потому что видит
мир и знает будущее (куда прилетит, где будет дым) — это и есть принцип пилота, а не правило под план.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from . import camera as C
from . import world as W
from .scene import Scene, Track, transmittance


@dataclass
class Style:
    """Тело камеры: пределы и как из места следует взгляд."""
    name: str
    vmax: float          # блоков за тик
    amax: float          # блоков за тик²
    jerk: float          # вес рывка (плавность)
    clear: float         # воздуха вокруг, блоков
    look_sigma: float    # сглаживание взгляда, тиков
    spring_hz: float     # пружина взгляда (0 — нет)
    damping: float
    anticipate: int      # смотрит на цель через столько тиков (знает постановку)
    yaw_rate: float      # °/тик предел поворота взгляда
    alt: tuple = (0.0, 1e9)  # над землёй
    fixed: bool = False  # не двигается (штатив)
    sideways: float = 180.0  # у FPV: насколько взгляд может уходить от курса полёта, °
    bank: float = 0.0    # доля крена дрона по физике (1 — FPV)
    step: int = 10       # тиков между опорными точками пути
    sigma: float = 6.0   # разброс проб, блоков


STYLES = {
    # FPV: квадрокоптер, крен и кивок — из ускорения (как у пилота), низко и быстро, летит туда, куда смотрит
    "fpv": Style("fpv", vmax=1.5, amax=0.045, jerk=10.0, clear=3.0, look_sigma=3, spring_hz=0, damping=1, anticipate=0,
                 yaw_rate=4.0, alt=(5, 40), sideways=45, bank=1.0, step=12, sigma=6),
    # кинодрон: плавно, медленно, взгляд на подвесе (без крена)
    "drone": Style("drone", vmax=0.9, amax=0.03, jerk=12.0, clear=4.0, look_sigma=5, spring_hz=0.7, damping=0.9,
                   anticipate=4, yaw_rate=3.0, alt=(10, 120), step=20, sigma=10),
    # оператор с камерой в руках: стоит или идёт, взгляд догоняет цель пружиной и чуть проскакивает
    "handheld": Style("handheld", vmax=0.12, amax=0.02, jerk=20.0, clear=0.6, look_sigma=2, spring_hz=1.6, damping=0.62,
                      anticipate=2, yaw_rate=6.0, alt=(1.5, 2.2), step=40, sigma=4),
    # штатив с головой: стоит, ведёт с запаздыванием, длинный фокус
    "tripod": Style("tripod", vmax=0.0, amax=0.0, jerk=0.0, clear=0.6, look_sigma=3, spring_hz=1.4, damping=0.85,
                    anticipate=3, yaw_rate=3.0, alt=(1.5, 2.2), fixed=True, sigma=12),
    # следящая камера пуска (как NASA): штатив далеко, ровно и с упреждением
    "tracker": Style("tracker", vmax=0.0, amax=0.0, jerk=0.0, clear=0.6, look_sigma=4, spring_hz=0.9, damping=1.0,
                     anticipate=8, yaw_rate=2.0, alt=(1.5, 2.2), fixed=True, sigma=16),
}


@dataclass
class Spec:
    """Что снимает план: окно [a, b], объект (путь) и события (удары), стиль, размер в кадре, кадр."""
    name: str
    a: int
    b: int
    subject: Track | None
    keys: list = field(default_factory=list)  # [(t, pos (3,), size)]
    style: str = "fpv"
    size: float = 4.0            # поперечник объекта
    share: tuple = (0.004, 0.04, 0.6)   # доля ширины кадра: не меньше (`minShare` трейлера), хорошо, не больше
    fov: float = 0.0             # 0 — по размеру объекта у события
    aspect: float = 16 / 9
    lead: float = 12.0           # смотрит на место объекта через столько тиков (воздух перед ним в кадре)
    frame_bias: tuple = (0.0, 0.0)  # куда в кадре ставить объект (u, v)


# ---------------------------------------------------------------- куда смотрит кадр


def aim_points(spec: Spec, ts: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """По тикам: точка, которую кадр должен держать (объект с воздухом перед ним, к удару — и место удара), сам объект
    (для меры) и его поперечник; NaN — объекта нет."""
    n = len(ts)
    subj = np.full((n, 3), np.nan)
    size = np.full(n, spec.size)
    if spec.subject is not None:
        subj = spec.subject.at(ts)
    aim = subj.copy()
    if spec.subject is not None and len(spec.subject.t) > 1:
        ahead = spec.subject.at(np.minimum(ts + spec.lead, spec.subject.last))
        ok = ~np.isnan(ahead[:, 0]) & ~np.isnan(subj[:, 0])
        aim[ok] = subj[ok] + 0.6 * (ahead[ok] - subj[ok])
    for (tk, pos, sz) in spec.keys:
        pos = np.asarray(pos, dtype=np.float64)
        fire = pos + np.array([0, sz * 0.35, 0])
        # последние 1,5 с до удара: в кадре и объект, и место удара; после — шар и столб над местом
        pre = (ts >= tk - 30) & (ts < tk)
        k = ((ts[pre] - (tk - 30)) / 30.0)[:, None]
        have = ~np.isnan(aim[pre, 0])
        blend = np.where(have[:, None], aim[pre] * (1 - 0.5 * k) + fire * 0.5 * k, fire)
        aim[pre] = blend
        post = (ts >= tk) & (ts <= tk + 60)
        aim[post] = fire + np.array([0, 1, 0]) * np.minimum(ts[post] - tk, 40)[:, None] * 0.1 * sz / 10
        subj[post] = fire
        size[post] = sz
    # нет объекта и событий — держим последнюю известную точку
    if np.isnan(aim[:, 0]).all():
        return aim, subj, size
    idx = np.arange(n)
    good = ~np.isnan(aim[:, 0])
    for k in range(3):
        aim[:, k] = np.interp(idx, idx[good], aim[good, k])
    return aim, subj, size


def look(style: Style, P: np.ndarray, aim: np.ndarray):
    """Взгляд тела из мест P (s, n, 3) на точки aim (n, 3): курс, тангаж, крен (s, n)."""
    n = P.shape[1]
    if style.anticipate:
        idx = np.minimum(np.arange(n) + style.anticipate, n - 1)
        aim = aim[idx]
    yaw, pitch = C.angles(aim[None, :, :] - P)
    yaw = C.unwrap_deg(yaw, axis=1)
    yaw = C.gauss(yaw, style.look_sigma, axis=1)
    pitch = C.gauss(pitch, style.look_sigma, axis=1)
    if style.spring_hz > 0:
        yaw = C.spring(yaw, style.spring_hz, style.damping, axis=1)
        pitch = C.spring(pitch, style.spring_hz, style.damping, axis=1)
    roll = np.zeros_like(yaw)
    if style.bank > 0:
        v = C.deriv(P)
        a = C.gauss(C.deriv(P, 2), 2.0, axis=1)
        roll = np.clip(style.bank * C.bank(v, a), -35, 35)
        pitch = pitch + 0.35 * np.clip(C.nod(v, a), -20, 20)
    return yaw, pitch, roll


# ---------------------------------------------------------------- путь: опорные точки → места по тикам


def bspline_matrix(n: int, m: int) -> np.ndarray:
    """Равномерный кубический B-сплайн: места n тиков из m опорных точек (n × m)."""
    if m == 1:
        return np.ones((n, 1))
    segs = m - 3
    u = np.linspace(0, segs, n)
    i = np.minimum(np.floor(u).astype(int), segs - 1)
    s = u - i
    B = np.zeros((n, m))
    b0 = (1 - s) ** 3 / 6
    b1 = (3 * s ** 3 - 6 * s ** 2 + 4) / 6
    b2 = (-3 * s ** 3 + 3 * s ** 2 + 3 * s + 1) / 6
    b3 = s ** 3 / 6
    r = np.arange(n)
    B[r, i] = b0
    B[r, i + 1] = b1
    B[r, i + 2] = b2
    B[r, i + 3] = b3
    return B


def controls_for(path: np.ndarray, m: int) -> np.ndarray:
    """Опорные точки, чей сплайн ближе всего к пути (n, 3) — для семян."""
    B = bspline_matrix(len(path), m)
    c, *_ = np.linalg.lstsq(B, path, rcond=None)
    return c


# ---------------------------------------------------------------- мера


@dataclass
class Eval:
    cost: np.ndarray
    parts: dict


def _sight(world: W.World, eye: np.ndarray, tgt: np.ndarray, spare: np.ndarray, maxit: int = 160):
    """Закрытие отрезков глаз → цель (…, 3): 1 — непрозрачный блок или неизвестный чанк на пути, иначе по листве и
    стеклу; последние `spare` блоков у цели не в счёт. Шаг — по расстоянию до ближайшего блока (в пустом воздухе —
    десятки блоков за шаг), у блоков — по блоку."""
    shape = np.broadcast_shapes(eye.shape, tgt.shape)[:-1]
    eye = np.broadcast_to(eye, shape + (3,)).reshape(-1, 3)
    tgt = np.broadcast_to(tgt, shape + (3,)).reshape(-1, 3)
    spare = np.broadcast_to(spare, shape).reshape(-1)
    d = tgt - eye
    L = np.linalg.norm(d, axis=-1)
    reach = np.maximum(L - spare, 0)
    u = d / np.maximum(L, 1e-9)[:, None]
    blocked = np.zeros(len(L), dtype=bool)
    soft = np.zeros(len(L))
    s = np.full(len(L), 0.5)
    live = np.flatnonzero(s < reach)
    sky = world.sky_y
    for _ in range(maxit):
        if not len(live):
            break
        p = eye[live] + u[live] * s[live, None]
        kind = world.kind_at(p)
        hit = (kind == W.OPAQUE) | (kind == W.UNKNOWN)
        blocked[live[hit]] = True
        soft[live] += kind == W.SOFT
        jump = np.maximum(1.0, world.clearance(p) - 1.0)
        jump[kind == W.SOFT] = 1.0
        out_up = p[:, 1] >= sky
        jump[out_up] = np.maximum(jump[out_up], 16.0)
        s[live] += jump
        live = live[~hit & (s[live] < reach[live])]
    out = np.where(blocked, 1.0, np.minimum(1.0, soft * 0.2))
    return out.reshape(shape)


def _unknown_below(world: W.World, eye, yaw, pitch, roll, fov, aspect, reach=128.0, step=4.0):
    """Доля лучей у нижнего края кадра (три), которые упираются в неизвестный чанк раньше блока."""
    f, r, u = C.basis(yaw, pitch, roll)
    th = math.tan(math.radians(1.0)) if False else np.tan(np.radians(fov) / 2)
    bad = np.zeros(yaw.shape)
    for sx in (-0.9, 0.0, 0.9):
        dirn = f + r * (sx * th * aspect)[..., None] - u * (0.92 * th)[..., None]
        dirn /= np.linalg.norm(dirn, axis=-1, keepdims=True)
        done = np.zeros(yaw.shape, dtype=bool)
        hit_unknown = np.zeros(yaw.shape, dtype=bool)
        for k in range(1, int(reach / step) + 1):
            p = eye + dirn * (k * step)
            kind = world.kind_at(p, outside_above=W.AIR, outside=W.UNKNOWN)
            new = ~done & (kind != W.AIR)
            hit_unknown |= new & (kind == W.UNKNOWN)
            done |= kind != W.AIR
            if done.all():
                break
        bad += hit_unknown
    return bad / 3.0


def _haze(scene: Scene, eye: np.ndarray, tgt: np.ndarray) -> np.ndarray:
    """1 — отрезок глаз → цель (…, 3) кончается в тумане края прорисовки или идёт слоем облаков (как `Rig.beyondFog`,
    `Rig.throughClouds` трейлера), иначе 0."""
    far = np.hypot(tgt[..., 0] - eye[..., 0], tgt[..., 2] - eye[..., 2]) > scene.fog
    if math.isnan(scene.clouds):
        return far.astype(np.float64)
    lo = np.minimum(eye[..., 1], tgt[..., 1])
    hi = np.maximum(eye[..., 1], tgt[..., 1])
    cloud = (hi >= scene.clouds - 1) & (lo <= scene.clouds + 6)
    return (far | cloud).astype(np.float64)


def evaluate(scene: Scene, spec: Spec, style: Style, P: np.ndarray, fov: float, fine: bool = False,
             angles: tuple | None = None) -> Eval:
    """Мера путей P (s, n, 3) за тики окна; `fine` — каждый тик и подробно (итог), иначе через тик (поиск).
    `angles` — свои курс, тангаж и крен (s, n) вместо взгляда тела: так меряется готовая камера (камера сценария)."""
    s, n, _ = P.shape
    ts = np.arange(spec.a, spec.a + n)
    aim, subj, size = aim_points(spec, ts)
    yaw, pitch, roll = look(style, P, aim) if angles is None else angles
    step = 1 if fine else 2
    sel = np.arange(0, n, step)
    Ps = P[:, sel]
    w = world = scene.world
    parts = {}

    # тело: не в блоках и с воздухом вокруг; над землёй в пределах стиля
    clear = w.clearance(Ps)
    inside = clear <= 0
    near = np.maximum(0, style.clear - (clear - 0.5))
    parts["walls"] = (inside * 50 + near ** 2 * 3).mean(1)
    gy = w.ground_at(Ps[..., 0], Ps[..., 2])
    alt = Ps[..., 1] - gy
    lo, hi = style.alt
    parts["alt"] = ((np.maximum(0, lo - alt) / 2) ** 2 + (np.maximum(0, alt - hi) / 4) ** 2).mean(1)

    # движение тела
    if style.fixed:
        parts["motion"] = np.zeros(s)
    else:
        v = C.deriv(P)
        a = C.deriv(P, 2)
        j = C.deriv(P, 3)
        sp = np.linalg.norm(v, axis=-1)
        ac = np.linalg.norm(a, axis=-1)
        parts["motion"] = (((np.maximum(0, sp - style.vmax) / max(style.vmax, 1e-3)) ** 2) * 20
                           + ((np.maximum(0, ac - style.amax) / max(style.amax, 1e-3)) ** 2) * 4
                           + style.jerk * (np.linalg.norm(j, axis=-1) / max(style.amax, 1e-3)) ** 2 * 0.02).mean(1)
        if style.sideways < 180:
            vh = np.degrees(np.arctan2(-v[..., 0], v[..., 2]))
            off = np.abs((yaw - vh + 180) % 360 - 180)
            moving = sp > 0.3
            parts["sideways"] = (moving * (np.maximum(0, off - style.sideways) / 30) ** 2).mean(1)
    # повороты взгляда: скорость и плавность
    dyaw = np.abs(np.gradient(yaw, axis=1))
    dpitch = np.abs(np.gradient(pitch, axis=1))
    rate = np.hypot(dyaw, dpitch)
    acc = np.abs(np.gradient(np.gradient(yaw, axis=1), axis=1)) + np.abs(np.gradient(np.gradient(pitch, axis=1), axis=1))
    parts["pan"] = ((np.maximum(0, rate - style.yaw_rate) / style.yaw_rate) ** 2 * 3 + (acc / style.yaw_rate) ** 2 * 0.5).mean(1)

    # объект: в кадре, виден, размер
    have = ~np.isnan(subj[sel, 0])
    if have.any():
        Y, Pi, R = yaw[:, sel], pitch[:, sel], roll[:, sel]
        sub = np.where(have[:, None], subj[sel], aim[sel])
        uu, vv, z = C.project(Ps, Y, Pi, R, fov, spec.aspect, sub[None])
        bu, bv = spec.frame_bias
        out = (np.maximum(0, np.abs(uu - bu) - 0.72) ** 2 + np.maximum(0, np.abs(vv - bv) - 0.72) ** 2) * 30 + (z <= 0) * 30
        comp = ((uu - bu) ** 2 + (vv - bv) ** 2) * 0.4
        sz = size[sel]
        sh = C.share(sz[None], np.maximum(z, 0.1), fov, spec.aspect)
        mn, good, mx = spec.share
        small = np.log(np.maximum(1e-6, mn) / np.maximum(sh, 1e-6))
        big = np.log(np.maximum(sh, 1e-6) / mx)
        sizec = np.maximum(0, small) ** 2 * 4 + np.maximum(0, big) ** 2 * 4 + np.log(np.maximum(sh, 1e-6) / good) ** 2 * 0.15
        spare = (sz / 2 + 1.5)[None].repeat(s, 0)
        occ = _sight(w, Ps, np.broadcast_to(sub[None], Ps.shape), spare)
        puffs_t = [scene.puffs(float(t)) for t in ts[sel]]
        T = np.ones((s, len(sel)))
        for i, pf in enumerate(puffs_t):
            if len(pf):
                T[:, i] = transmittance(pf, Ps[:, i], np.broadcast_to(sub[i], Ps[:, i].shape), spare=float(sz[i]))
        vis = (1 - occ) * T * (1 - _haze(scene, Ps, np.broadcast_to(sub[None], Ps.shape)))
        hv = have[None]
        parts["frame"] = (hv * out).sum(1) / max(1, have.sum())
        parts["compose"] = (hv * comp).sum(1) / max(1, have.sum())
        parts["size"] = (hv * sizec).sum(1) / max(1, have.sum())
        parts["hidden"] = (hv * (1 - vis) * 6).sum(1) / max(1, have.sum())
        if fine:
            parts["_u"], parts["_v"], parts["_share"], parts["_vis"], parts["_occ"], parts["_smoke"] = uu, vv, sh, vis, occ, T
            parts["_have"] = have
    else:
        for k in ("frame", "compose", "size", "hidden"):
            parts[k] = np.zeros(s)

    # события: каждое в кадре и видно в первые 12 тиков
    keyc = np.zeros(s)
    for (tk, pos, sz) in spec.keys:
        i0 = int(tk - spec.a)
        if i0 < 0 or i0 >= n:
            continue
        ii = np.arange(i0, min(n, i0 + 13))
        fire = np.asarray(pos) + np.array([0, sz * 0.35, 0])
        uu, vv, z = C.project(P[:, ii], yaw[:, ii], pitch[:, ii], roll[:, ii], fov, spec.aspect, fire[None, None])
        inn = (np.abs(uu) <= 0.9) & (np.abs(vv) <= 0.9) & (z > 0)
        occ = _sight(w, P[:, ii], np.broadcast_to(fire, P[:, ii].shape), np.full((s, len(ii)), 3.0))
        pf = scene.puffs(float(tk))
        T = transmittance(pf, P[:, ii], np.broadcast_to(fire, P[:, ii].shape), spare=sz) if len(pf) else 1.0
        seen = inn * (1 - occ) * T * (1 - _haze(scene, P[:, ii], np.broadcast_to(fire, P[:, ii].shape)))
        keyc += (1 - seen.mean(1)) * 40
    parts["keys"] = keyc

    # низ кадра — не в неизвестных чанках
    sel4 = np.arange(0, n, 4 if not fine else 1)
    unk = _unknown_below(w, P[:, sel4], yaw[:, sel4], pitch[:, sel4], roll[:, sel4], fov, spec.aspect)
    parts["unknown"] = unk.mean(1) * 20

    cost = sum(v for k, v in parts.items() if not k.startswith("_"))
    if fine:
        parts["_yaw"], parts["_pitch"], parts["_roll"] = yaw, pitch, roll
        parts["_ts"] = ts
    return Eval(cost, parts)


# ---------------------------------------------------------------- семена по стилю


def _key_point(spec: Spec) -> tuple[int, np.ndarray, float]:
    if spec.keys:
        tk, pos, sz = spec.keys[0]
        return int(tk), np.asarray(pos, dtype=np.float64), float(sz)
    ts = np.arange(spec.a, spec.b + 1)
    _, subj, _ = aim_points(spec, ts)
    good = ~np.isnan(subj[:, 0])
    i = np.flatnonzero(good)[len(np.flatnonzero(good)) // 2] if good.any() else len(ts) // 2
    return int(ts[i]), subj[i], spec.size


def _approach_heading(spec: Spec, tk: int) -> float | None:
    if spec.subject is None:
        return None
    p1 = spec.subject.at(np.array([min(tk - 2, spec.subject.last)]))[0]
    p0 = spec.subject.at(np.array([max(tk - 40, spec.subject.first)]))[0]
    d = p1 - p0
    if np.isnan(d).any() or np.hypot(d[0], d[2]) < 1:
        return None
    return float(C.angles(d)[0])


def seeds(scene: Scene, spec: Spec, style: Style, n: int, m: int) -> list[np.ndarray]:
    """Начальные пути (опорные точки (m, 3)) по стилю."""
    tk, K, sz = _key_point(spec)
    ts = np.arange(spec.a, spec.a + n)
    out = []
    w = scene.world
    if style.fixed or style.name == "handheld":
        head = _approach_heading(spec, tk)
        dists = {"tracker": (140, 200, 260), "tripod": (60, 110, 170, 240), "handheld": (35, 60, 90)}[style.name]
        for d in dists:
            for j in range(24):
                az = 15.0 * j + (head or 0)
                f = C.forward(az, 0)
                x, z = K[0] - f[0] * d, K[2] - f[2] * d
                gy = float(w.ground_at(np.array([x]), np.array([z]))[0])
                eye = np.array([x, gy + 1.62, z])
                out.append(np.repeat(eye[None], m, 0))
        return out
    head0 = _approach_heading(spec, tk)
    speed = {"fpv": 1.3, "drone": 0.45}.get(style.name, 0.5)
    pass_at = 0.62  # доля окна, где камера ближе всего к удару
    for j in range(16):
        head = 22.5 * j + (head0 or 0)
        f = C.forward(head, 0)
        side_vec = np.array([-f[2], 0, f[0]])
        for side in (-1, 1):
            for beside, up in ((18, 14), (34, 24), (55, 40)):
                along = (np.arange(n) - (tk - spec.a + (pass_at - 0.5) * 0)) * speed
                along = along - along[int(np.clip(tk - spec.a, 0, n - 1))]
                base = K + side * beside * side_vec + np.array([0, up, 0])
                path = base[None] + along[:, None] * f[None]
                # высота над землёй под путём не меньше 6
                gy = w.ground_at(path[:, 0], path[:, 2])
                path[:, 1] = np.maximum(path[:, 1], gy + 6 + (style.alt[0] if style.name == "drone" else 0))
                out.append(controls_for(path, m))
    # погоня: позади объекта и выше, если объект не быстрее тела
    if spec.subject is not None:
        sp = spec.subject.at(ts)
        ok = ~np.isnan(sp[:, 0])
        if ok.sum() > 5:
            for back, up in ((14, 4), (26, 8), (40, 14)):
                path = sp.copy()
                idx = np.arange(n)
                for k in range(3):
                    path[:, k] = np.interp(idx, idx[ok], sp[ok, k])
                d = C.deriv(path[None])[0]
                dn = d / np.maximum(np.linalg.norm(d, axis=-1, keepdims=True), 1e-6)
                path = path - dn * back + np.array([0, up, 0])
                out.append(controls_for(path, m))
    return out


# ---------------------------------------------------------------- поиск


@dataclass
class Result:
    spec: Spec
    style: Style
    P: np.ndarray       # (n, 3)
    yaw: np.ndarray
    pitch: np.ndarray
    roll: np.ndarray
    fov: float
    cost: float
    parts: dict
    report: dict


def choose_fov(spec: Spec, style: Style, P: np.ndarray) -> float:
    if spec.fov:
        return spec.fov
    tk, K, sz = _key_point(spec)
    i = int(np.clip(tk - spec.a, 0, len(P) - 1))
    d = float(np.linalg.norm(P[i] - K))
    want = {"fpv": 0.06, "drone": 0.08, "handheld": 0.1, "tripod": 0.18, "tracker": 0.16}.get(style.name, 0.1)
    f = C.fov_for(max(sz, spec.size), d, want, spec.aspect)
    lo, hi = {"fpv": (68, 84), "drone": (50, 80), "handheld": (40, 70), "tripod": (8, 60), "tracker": (5, 40)}[style.name]
    return float(np.clip(f, lo, hi))


def solve(scene: Scene, spec: Spec, iters: int = 10, samples: int = 48, keep: int = 6, rng: int = 7,
          log=print) -> Result:
    style = STYLES[spec.style]
    n = spec.b - spec.a + 1
    m = 1 if style.fixed else max(4, int(math.ceil((n - 1) / style.step)) + 3)
    B = bspline_matrix(n, m)
    seed_cs = seeds(scene, spec, style, n, m)
    R = np.random.default_rng(rng)

    def run(cs: np.ndarray, fov_fixed=None):
        P = np.einsum("nm,smk->snk", B, cs)
        if fov_fixed is None:
            fovs = np.array([choose_fov(spec, style, p) for p in P])
            # одно поле зрения на пачку — по первой; у штатива — своё у каждого места
            if style.fixed:
                costs = np.array([evaluate(scene, spec, style, P[i:i + 1], fovs[i]).cost[0] for i in range(len(P))])
                return costs, fovs
            fov = float(np.median(fovs))
        else:
            fov = fov_fixed
        e = evaluate(scene, spec, style, P, fov)
        return e.cost, np.full(len(P), fov)

    S = np.stack(seed_cs)
    costs, fovs = run(S)
    order = np.argsort(costs)
    log(f"{spec.name}: {style.name}, семян {len(S)}, лучшее {costs[order[0]]:.2f}, худшее {costs[order[-1]]:.2f}")
    best_c, best_cost, best_fov = S[order[0]], costs[order[0]], fovs[order[0]]
    if not style.fixed:
        for si in order[:keep]:
            mean = S[si].copy()
            sigma = np.full_like(mean, style.sigma)
            sigma[:, 1] *= 0.6
            cur_cost = costs[si]
            fov = fovs[si]
            for it in range(iters):
                pert = R.normal(size=(samples,) + mean.shape) * sigma[None]
                pert[0] = 0
                cand = mean[None] + pert
                cc, _ = run(cand, fov_fixed=fov)
                el = np.argsort(cc)[:max(4, samples // 6)]
                mean = cand[el].mean(0)
                sigma = np.maximum(cand[el].std(0), 0.3) * 0.9
                if cc[el[0]] < cur_cost:
                    cur_cost = cc[el[0]]
                    if cur_cost < best_cost:
                        best_cost, best_c, best_fov = cur_cost, cand[el[0]], fov
            log(f"  семя {si}: {costs[si]:.2f} → {cur_cost:.2f}")
    P = (B @ best_c)
    fov = choose_fov(spec, style, P) if style.fixed else best_fov
    e = evaluate(scene, spec, style, P[None], fov, fine=True)
    rep = report(spec, e, fov)
    return Result(spec, style, P, e.parts["_yaw"][0], e.parts["_pitch"][0], e.parts["_roll"][0], fov, float(e.cost[0]),
                  {k: (v[0] if hasattr(v, "__len__") and not k.startswith("_") else v) for k, v in e.parts.items()}, rep)


def report(spec: Spec, e: Eval, fov: float) -> dict:
    """Итог плана по тикам, как его будет считать проверка кадров: доли и худшие места."""
    p = e.parts
    out = {"shot": spec.name, "fov": round(fov, 1), "cost": round(float(e.cost[0]), 3),
           "parts": {k: round(float(v[0]), 3) for k, v in p.items() if not k.startswith("_")}}
    if "_have" in p:
        have = p["_have"]
        u, v, sh, vis = p["_u"][0], p["_v"][0], p["_share"][0], p["_vis"][0]
        inframe = (np.abs(u) <= 1) & (np.abs(v) <= 1)
        good = inframe & (vis > 0.5) & (sh >= spec.share[0])
        out["subject_framed"] = round(float(good[have].mean()) if have.any() else 1.0, 3)
        out["subject_in_frame"] = round(float(inframe[have].mean()) if have.any() else 1.0, 3)
        out["subject_visible"] = round(float((vis > 0.5)[have].mean()) if have.any() else 1.0, 3)
        out["share_median"] = round(float(np.median(sh[have])) if have.any() else 0.0, 4)
    yaw = p["_yaw"][0]
    pitch = p["_pitch"][0]
    rate = np.hypot(np.gradient(yaw), np.gradient(pitch)) * 20
    out["pan_max_deg_s"] = round(float(rate.max()), 1)
    out["roll_max_deg"] = round(float(np.abs(p["_roll"][0]).max()), 1)
    return out
