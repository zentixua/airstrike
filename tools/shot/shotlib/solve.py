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
from dataclasses import dataclass, field, replace

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
                 yaw_rate=4.0, alt=(5, 40), sideways=45, bank=1.0, step=24, sigma=4),
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
    share: tuple = (0.004, 0.07, 0.8)   # доля высоты кадра: не меньше (`minShare` трейлера), хорошо, не больше
    fov: float = 0.0             # 0 — по размеру объекта у события
    aspect: float = 16 / 9
    lead: float = 12.0           # смотрит на место объекта через столько тиков (воздух перед ним в кадре)
    frame_bias: tuple = (0.0, 0.0)  # куда в кадре ставить объект (u, v)
    reach: float = 160.0         # дальше этого от удара взгляд держит сторону подлёта, а не сам снаряд
    overlay: bool = False        # объект рисуется поверх мира (ядерный подрыв): туман, облака и чужие чанки не закрывают
    # что план обязан показать (`Coverage.Need` трейлера): доля кадров до удара, где объект в кадре, виден и читается;
    # то же за последнюю секунду; удар в кадре и виден в первые 12 тиков
    need: tuple = (0.6, 0.75, 0.7)
    hold: bool = False           # взгляд держит место удара, снаряд сам входит в кадр (иначе ведёт снаряд)


# ---------------------------------------------------------------- куда смотрит кадр


def aim_points(spec: Spec, ts: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """По тикам: точка, которую кадр должен держать (объект с воздухом перед ним, к удару — и место удара), сам объект
    (для меры) и его поперечник; NaN — объекта нет."""
    n = len(ts)
    subj = np.full((n, 3), np.nan)
    size = np.full(n, spec.size)
    if spec.subject is not None:
        tr = spec.subject
        subj = tr.at(np.clip(ts, tr.first, tr.last))
        # как `Rig.Subject.aim`: до появления — место появления, после пути — место удара
        if spec.keys:
            subj[ts > tr.last] = np.asarray(spec.keys[0][1], dtype=np.float64)
    elif spec.keys:
        subj[:] = np.asarray(spec.keys[0][1], dtype=np.float64)
    aim = subj.copy()
    if spec.subject is not None:
        aim[(ts < spec.subject.first) | (ts > spec.subject.last)] = np.nan
    if spec.subject is not None and len(spec.subject.t) > 1:
        ahead = spec.subject.at(np.minimum(ts + spec.lead, spec.subject.last))
        ok = ~np.isnan(ahead[:, 0]) & ~np.isnan(subj[:, 0])
        aim[ok] = subj[ok] + 0.6 * (ahead[ok] - subj[ok])
    # снаряд ещё далеко от удара (за туманом у места событий): кадр держит сторону, откуда он придёт, на высоте до
    # 30° над местом удара, а не небо прямо над ним
    for (tk, pos, sz) in spec.keys[:1]:
        k = np.asarray(pos, dtype=np.float64)
        d = aim - k
        far = np.linalg.norm(d, axis=1) > spec.reach
        if far.any():
            dn = d[far] / np.linalg.norm(d[far], axis=1, keepdims=True)
            h = np.hypot(dn[:, 0], dn[:, 2])
            up = np.minimum(dn[:, 1], np.tan(np.radians(30)) * h)
            dn = np.stack([dn[:, 0], up, dn[:, 2]], axis=1)
            dn /= np.linalg.norm(dn, axis=1, keepdims=True)
            aim[far] = k + dn * spec.reach
    for i, (tk, pos, sz) in enumerate(spec.keys):
        pos = np.asarray(pos, dtype=np.float64)
        fire = pos + np.array([0, sz * 0.35, 0])
        if spec.hold and i == 0:
            # держит место удара: снаряд сам входит в кадр; последнюю секунду — между снарядом и местом
            early = ts < tk - 20
            aim[early] = fire
            last = (ts >= tk - 20) & (ts < tk)
            have = ~np.isnan(aim[last, 0])
            aim[last] = np.where(have[:, None], 0.5 * (aim[last] + fire), fire)
        else:
            # последние 1,5 с до удара: в кадре и объект, и место удара; после — шар и столб над местом
            pre = (ts >= tk - 30) & (ts < tk)
            k = ((ts[pre] - (tk - 30)) / 30.0)[:, None]
            have = ~np.isnan(aim[pre, 0])
            blend = np.where(have[:, None], aim[pre] * (1 - 0.5 * k) + fire * 0.5 * k, fire)
            aim[pre] = blend
        post = (ts >= tk) & (ts <= tk + 60)
        aim[post] = fire + np.array([0, 1, 0]) * np.minimum(ts[post] - tk, 40)[:, None] * 0.1 * sz / 10
        if i == 0:
            # как проверка кадров: после удара объект — место удара, шар поднимается до 0,4 поперечника за 2 с
            after = ts >= tk
            lift = np.minimum(1, (ts[after] - tk) / 40.0) * sz * 0.4
            subj[after] = pos + np.stack([np.zeros_like(lift), lift, np.zeros_like(lift)], axis=1)
            size[after] = sz
    # нет объекта и событий — держим последнюю известную точку
    if np.isnan(aim[:, 0]).all():
        return aim, subj, size
    idx = np.arange(n)
    good = ~np.isnan(aim[:, 0])
    for k in range(3):
        aim[:, k] = np.interp(idx, idx[good], aim[good, k])
    return aim, subj, size


def look(style: Style, P: np.ndarray, aim: np.ndarray, ahead: np.ndarray | None = None):
    """Взгляд тела из мест P (s, n, 3) на точки aim (n, 3): курс, тангаж, крен (s, n). `ahead` (n,) — доля взгляда
    вперёд по курсу (FPV после удара: пилот уходит от шара и смотрит, куда летит), 12° вниз."""
    n = P.shape[1]
    if style.anticipate:
        idx = np.minimum(np.arange(n) + style.anticipate, n - 1)
        aim = aim[idx]
    d = aim[None, :, :] - P
    if ahead is not None and ahead.any():
        v = C.gauss(C.deriv(P), 4.0, axis=1)
        vh = np.stack([v[..., 0], np.zeros_like(v[..., 0]), v[..., 2]], axis=-1)
        vh /= np.maximum(np.linalg.norm(vh, axis=-1, keepdims=True), 1e-6)
        fwd = vh * math.cos(math.radians(12)) - np.array([0, 1, 0]) * math.sin(math.radians(12))
        dn = d / np.maximum(np.linalg.norm(d, axis=-1, keepdims=True), 1e-6)
        w = ahead[None, :, None]
        d = dn * (1 - w) + fwd * w
    yaw, pitch = C.angles(d)
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


def spare_of(size):
    """Сколько блоков у цели луч видимости не считает: как у проверки кадров (`FrameQa`: max(1,5, 0,4 поперечника))."""
    return np.maximum(1.5, np.asarray(size, dtype=np.float64) * 0.4)


def _sight(world: W.World, eye: np.ndarray, tgt: np.ndarray, spare: np.ndarray, maxit: int = 160,
           unknown_blocks: bool = True):
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
        hit = (kind == W.OPAQUE) | ((kind == W.UNKNOWN) & unknown_blocks)
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
    th = np.tan(np.radians(fov) / 2)
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


def _haze(scene: Scene, eye: np.ndarray, tgt: np.ndarray, overlay: bool = False) -> np.ndarray:
    """1 — отрезок глаз → цель (…, 3) кончается в тумане края прорисовки или идёт слоем облаков (как `Rig.beyondFog`,
    `Rig.throughClouds` трейлера), иначе 0."""
    if overlay:
        return np.zeros(np.broadcast_shapes(eye.shape, tgt.shape)[:-1])
    far = np.hypot(tgt[..., 0] - eye[..., 0], tgt[..., 2] - eye[..., 2]) > scene.fog
    if math.isnan(scene.clouds):
        return far.astype(np.float64)
    lo = np.minimum(eye[..., 1], tgt[..., 1])
    hi = np.maximum(eye[..., 1], tgt[..., 1])
    cloud = (hi >= scene.clouds - 1) & (lo <= scene.clouds + 6)
    return (far | cloud).astype(np.float64)


def phases(spec: Spec, ts: np.ndarray) -> dict:
    """Части окна, как их считает проверка кадров (`FrameQa.verdict`): до удара, последняя секунда до него, удар и 12
    тиков после; без удара всё окно — «до удара»."""
    if spec.keys:
        tk = spec.keys[0][0]
        return {"pre": ts < tk, "near": (ts >= tk - 20) & (ts < tk), "key": (ts >= tk) & (ts <= tk + 12)}
    return {"pre": np.ones(len(ts), dtype=bool), "near": np.zeros(len(ts), dtype=bool),
            "key": np.zeros(len(ts), dtype=bool)}


def _sideways_allowed(style: Style, spec: Spec, ts: np.ndarray) -> np.ndarray:
    """У FPV — насколько взгляд может уходить от курса, ° по тикам: на подлёте почти по курсу, у удара — доворот на шар
    на пролёте мимо (облёт с рысканием), потом снова вперёд (те же доли, что у пилота превью, `Pilot.CRAB_*`)."""
    out = np.full(len(ts), float(style.sideways))
    if spec.keys and style.sideways < 180:
        tk = spec.keys[0][0]
        out[:] = 30.0
        out[ts < tk - 30] = 20.0
        out[(ts >= tk - 30) & (ts <= tk + 30)] = 60.0
    return out


def _ahead(style: Style, spec: Spec, ts: np.ndarray) -> np.ndarray | None:
    if style.bank <= 0 or not spec.keys:
        return None
    tk = spec.keys[0][0]
    return np.clip((ts - tk - 15) / 30.0, 0, 1)


def evaluate(scene: Scene, spec: Spec, style: Style, P: np.ndarray, fov: float, fine: bool = False,
             angles: tuple | None = None) -> Eval:
    """Мера путей P (s, n, 3) за тики окна; `fine` — каждый тик и подробно (итог), иначе через тик (поиск).
    `angles` — свои курс, тангаж и крен (s, n) вместо взгляда тела: так меряется готовая камера (камера сценария).

    Главное в мере — то же, что решает проверка кадров вывода: доли кадров до удара, за последнюю секунду и у удара, где
    объект в кадре, виден и читается, против того, что план обязан показать (`Spec.need`), с запасом 0,1. Остальное —
    как снимает человек: объект держится в кадре и сверх нужного (у FPV — слабее: он летит туда, куда смотрит),
    композиция, размер, тело в своих пределах."""
    s, n, _ = P.shape
    ts = np.arange(spec.a, spec.a + n)
    aim, subj, size = aim_points(spec, ts)
    yaw, pitch, roll = look(style, P, aim, _ahead(style, spec, ts)) if angles is None else angles
    step = 1 if fine else 2
    sel = np.arange(0, n, step)
    Ps = P[:, sel]
    w = scene.world
    parts = {}
    ph = {k: v[sel] for k, v in phases(spec, ts).items()}

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
            allow = _sideways_allowed(style, spec, ts)[None]
            parts["sideways"] = (moving * (np.maximum(0, off - allow) / 30) ** 2).mean(1)
    # повороты взгляда: скорость и плавность
    dyaw = np.abs(np.gradient(yaw, axis=1))
    dpitch = np.abs(np.gradient(pitch, axis=1))
    rate = np.hypot(dyaw, dpitch)
    acc = np.abs(np.gradient(np.gradient(yaw, axis=1), axis=1)) + np.abs(np.gradient(np.gradient(pitch, axis=1), axis=1))
    parts["pan"] = ((np.maximum(0, rate - style.yaw_rate) / style.yaw_rate) ** 2 * 12 + (acc / style.yaw_rate) ** 2 * 0.5).mean(1)

    # объект: в кадре, виден, читается — по кадрам; доли по частям окна против нужного
    Y, Pi, R = yaw[:, sel], pitch[:, sel], roll[:, sel]
    have = ~np.isnan(subj[sel, 0])
    sub = np.where(have[:, None], subj[sel], aim[sel])
    sub = np.nan_to_num(sub)  # объекта нет совсем (без пути и удара): кадры не в счёт (`hv`)
    uu, vv, z = C.project(Ps, Y, Pi, R, fov, spec.aspect, sub[None])
    bu, bv = spec.frame_bias
    edge = np.maximum(np.abs(uu - bu), np.abs(vv - bv))
    g_in = np.clip(1 - (edge - 0.85) / 0.1, 0, 1) * (z > 0.1)
    sz = size[sel]
    sh = C.share(sz[None], np.maximum(z, 0.1), fov, spec.aspect)
    mn, good, mx = spec.share
    g_size = np.clip(1 + np.log(np.maximum(sh, 1e-9) / mn) / math.log(2), 0, 1)
    spare = spare_of(sz)[None].repeat(s, 0)
    occ = _sight(w, Ps, np.broadcast_to(sub[None], Ps.shape), spare, unknown_blocks=not spec.overlay)
    T = np.ones((s, len(sel)))
    for i, t in enumerate(ts[sel]):
        pf = scene.puffs(float(t))
        if len(pf):
            T[:, i] = transmittance(pf, Ps[:, i], np.broadcast_to(sub[i], Ps[:, i].shape), spare=float(sz[i] * 2 + 4))
    vis = (1 - occ) * T * (1 - _haze(scene, Ps, np.broadcast_to(sub[None], Ps.shape), spec.overlay))
    g_vis = np.clip((vis - 0.3) / 0.4, 0, 1)
    hv = have[None].astype(np.float64)
    g = g_in * g_vis * g_size * hv

    def frac(mask):
        return (g[:, mask].mean(1) if mask.any() else None)

    f_pre, f_near = frac(ph["pre"]), frac(ph["near"])
    # удар: место удара (шар) в кадре и видно в первые 12 тиков — без размера. Точка и запас у цели — как у проверки
    # кадров (`Rig.Subject.aim`, `FrameQa`): шар у самой земли и поднимается за 2 с; прежняя точка на 0,35 шара выше
    # видела удар поверх низкой преграды, которой вывод не видел за ним (14.10 p32: мера 1,00, вывод 0 % — за блоками)
    f_key = None
    if spec.keys:
        tk, pos, ksz = spec.keys[0]
        ii = np.flatnonzero((ts >= tk) & (ts <= tk + 12))
        if len(ii):
            fire = subj[ii]
            ku, kv, kz = C.project(P[:, ii], yaw[:, ii], pitch[:, ii], roll[:, ii], fov, spec.aspect, fire[None])
            kin = np.clip(1 - (np.maximum(np.abs(ku), np.abs(kv)) - 0.85) / 0.1, 0, 1) * (kz > 0.1)
            kocc = _sight(w, P[:, ii], np.broadcast_to(fire[None], P[:, ii].shape), np.full((s, len(ii)), spare_of(ksz)),
                          unknown_blocks=not spec.overlay)
            pf = scene.puffs(float(tk))
            kT = (transmittance(pf, P[:, ii], np.broadcast_to(fire[None], P[:, ii].shape), spare=ksz * 2 + 4)
                  if len(pf) else 1.0)
            kvis = (1 - kocc) * kT * (1 - _haze(scene, P[:, ii], np.broadcast_to(fire[None], P[:, ii].shape), spec.overlay))
            f_key = (kin * np.clip((kvis - 0.3) / 0.4, 0, 1)).mean(1)
            if fine:
                parts["_kseen"] = (np.maximum(np.abs(ku), np.abs(kv)) <= 0.92) & (kz > 0.1) & (kvis > 0.5)
    cost_need = np.zeros(s)
    keep = 0.4 if style.bank > 0 else 1.0
    for f, nd, wk in ((f_pre, spec.need[0], keep), (f_near, spec.need[1], keep), (f_key, spec.need[2], 2.0)):
        if f is None:
            continue
        target = min(1.0, nd + 0.1) if nd > 0 else 0.0
        cost_need = cost_need + 80 * np.maximum(0, target - f) ** 2 + wk * (1 - f)
    parts["need"] = cost_need
    # поиску — плавный след: объект за краем кадра (вне кадра доля плоская)
    parts["frame"] = (hv * np.minimum(np.maximum(0, edge - 0.85), 3) ** 2).mean(1) * 0.05
    # композиция и размер — где объект в кадре
    comp = ((uu - bu) ** 2 + (vv - bv) ** 2) * 0.4
    sizec = (np.maximum(0, np.log(np.maximum(sh, 1e-6) / mx)) ** 2 * 4
             + np.log(np.maximum(sh, 1e-6) / good) ** 2 * 0.15)
    wt = g_in * hv
    parts["compose"] = (wt * comp).sum(1) / np.maximum(1, wt.sum(1))
    parts["size"] = (wt * sizec).sum(1) / np.maximum(1, wt.sum(1))
    if fine:
        parts["_u"], parts["_v"], parts["_share"], parts["_vis"], parts["_occ"], parts["_smoke"] = uu, vv, sh, vis, occ, T
        parts["_have"], parts["_sel_ts"], parts["_z"] = have, ts[sel], z
        parts["_fpre"], parts["_fnear"], parts["_fkey"] = f_pre, f_near, f_key

    # не в огне: после удара камера дальше полутора шаров от его места (шар во весь кадр и камера в нём — брак)
    near_c = np.zeros(s)
    for (tk, pos, sz_) in spec.keys:
        ii = np.flatnonzero((ts >= tk - 5) & (ts <= tk + 60))
        if len(ii):
            dist = np.linalg.norm(P[:, ii] - np.asarray(pos)[None, None], axis=-1)
            near_c += ((np.maximum(0, 1.5 * sz_ - dist) / sz_) ** 2 * 20).mean(1)
    parts["blast"] = near_c
    # крен: кинематографичный FPV кренится в вираже, но не лежит на боку и не качается
    if style.bank > 0:
        droll = np.abs(np.gradient(roll, axis=1))
        parts["roll"] = ((np.maximum(0, np.abs(roll) - 20) / 10) ** 2 * 4
                         + (np.maximum(0, droll - 1.5) / 1.5) ** 2 * 2).mean(1)

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
    speed = {"fpv": 1.1, "drone": 0.45}.get(style.name, 0.5)
    # ближе всего к удару — через столько тиков после него: у FPV удар впереди, мимо шара — на пролёте (`Pilot.PASS`)
    pass_after = 24 if style.bank > 0 and spec.keys else 0
    for j in range(16):
        head = 22.5 * j + (head0 or 0)
        f = C.forward(head, 0)
        side_vec = np.array([-f[2], 0, f[0]])
        for side in (-1, 1):
            for beside, up in ((18, 14), (34, 24), (55, 40)):
                along = (ts - tk - pass_after) * speed
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
    want = {"fpv": 0.1, "drone": 0.14, "handheld": 0.18, "tripod": 0.3, "tracker": 0.28}.get(style.name, 0.18)
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


def solve_best(scene: Scene, spec: Spec, iters: int = 10, log=print) -> Result:
    """Лучшая камера тела по обоим замыслам взгляда — вести снаряд или держать место удара (снаряд сам входит в кадр):
    какой из них лучше, решает мера."""
    best = None
    for hold in ((False, True) if spec.keys else (False,)):
        r = solve(scene, replace(spec, hold=hold), iters=iters, log=log)
        r.report["hold"] = hold
        if best is None or r.cost < best.cost:
            best = r
    return best


def report(spec: Spec, e: Eval, fov: float) -> dict:
    """Итог плана по тикам, как его будет считать проверка кадров (`FrameQa.verdict`): доли до удара, за последнюю секунду
    и у удара против нужного плану, пройдёт ли; доли по всему окну и худшие повороты."""
    p = e.parts
    out = {"shot": spec.name, "fov": round(fov, 1), "cost": round(float(e.cost[0]), 3),
           "parts": {k: round(float(v[0]), 3) for k, v in p.items() if not k.startswith("_")}}
    if "_have" in p:
        have = p["_have"]
        u, v, z, sh, vis = p["_u"][0], p["_v"][0], p["_z"][0], p["_share"][0], p["_vis"][0]
        inframe = (np.abs(u) <= 0.92) & (np.abs(v) <= 0.92) & (z > 0.1) & have
        good = inframe & (vis > 0.5) & (sh >= spec.share[0])
        out["subject_framed"] = round(float(good[have].mean()) if have.any() else 1.0, 3)
        out["subject_in_frame"] = round(float(inframe[have].mean()) if have.any() else 1.0, 3)
        out["subject_visible"] = round(float((vis > 0.5)[have].mean()) if have.any() else 1.0, 3)
        out["share_median"] = round(float(np.median(sh[have])) if have.any() else 0.0, 4)
        ph = phases(spec, p["_sel_ts"])
        approach = float(good[ph["pre"]].mean()) if ph["pre"].any() else 1.0
        near = float(good[ph["near"]].mean()) if ph["near"].any() else 1.0
        key = float(p["_kseen"][0].mean()) if "_kseen" in p else (0.0 if spec.keys else 1.0)
        got = (approach, near, key)
        out["approach"], out["near"], out["key"] = (round(x, 3) for x in got)
        out["margin"] = round(min(g - nd for g, nd in zip(got, spec.need)), 3)
        out["pass"] = bool(out["margin"] >= 0)
    yaw = p["_yaw"][0]
    pitch = p["_pitch"][0]
    rate = np.hypot(np.gradient(yaw), np.gradient(pitch)) * 20
    out["pan_max_deg_s"] = round(float(rate.max()), 1)
    out["roll_max_deg"] = round(float(np.abs(p["_roll"][0]).max()), 1)
    return out
