"""Что снимает план: объект и событие из журнала — те же правила выбора, что у трейлера (`Rig.impact`, `flight`,
`framedImpact`, `firstImpact`, `launch`, `nuke`), и окно плана (отметки «start»…«end», удар — с запасом 20 тиков)."""
from __future__ import annotations

import re
from dataclasses import dataclass

import numpy as np

from . import camera as C
from .scene import Scene, Track, blast_of, size_of

HIT_MARGIN = 20  # Coverage.HIT_MARGIN


@dataclass
class Pick:
    what: str
    track: Track | None
    key: int            # тик удара, −1 — нет
    key_pos: np.ndarray | None
    size: float
    key_size: float
    a: int
    b: int


def window(sc: Scene) -> tuple[int, int]:
    start, end = sc.mark("start"), sc.mark("end")
    last = max([int(t.t[-1]) for t in sc.tracks.values()] + [int(sc.cams.t[-1]) if sc.cams is not None else 0]
               + [im.t for im in sc.impacts] + [0])
    return (start if start is not None else 0), (end if end is not None else last)


def _impact_of(sc: Scene, tid: str):
    return next((im for im in sc.impacts if im.track == tid), None)


def _cam_pose(sc: Scene, t: int):
    i = int(np.clip(np.searchsorted(sc.cams.t, t, side="left"), 0, len(sc.cams.t) - 1))
    return sc.cams.pos[i], sc.cam_look[i]


def _in_frame(sc: Scene, t: int, p: np.ndarray, margin: float = 0.85) -> bool:
    if sc.cams is None or sc.cam_look is None or p is None or np.isnan(p).any():
        return False
    eye, (yaw, pitch, roll, fov) = _cam_pose(sc, t)
    u, v, z = C.project(eye[None, None], np.array([[yaw]]), np.array([[pitch]]), np.array([[roll]]), fov, 16 / 9,
                        np.asarray(p)[None, None])
    far = np.hypot(p[0] - eye[0], p[2] - eye[2]) > sc.fog
    return bool(z[0, 0] > 0 and abs(u[0, 0]) <= margin and abs(v[0, 0]) <= margin and not far)


def _framed(sc: Scene, tr: Track, a: int, b: int) -> float:
    """Сколько тиков окна камера сценария держит снаряд в кадре (как `Rig.framed`)."""
    if sc.cams is None:
        return 0.0
    n = 0
    for t in range(max(a, tr.first), min(b, tr.last) + 1):
        if _in_frame(sc, t, tr.at(np.array([t]))[0]):
            n += 1
    return n


def _of(sc: Scene, weapon: str) -> list[Track]:
    return [tr for tr in sc.tracks.values() if weapon in ("*", "any") or tr.weapon == weapon]


def pick(sc: Scene, sel: str, span: int = 0) -> Pick:
    """Выбор по строке: `impact:W[@x,z][#n]`, `first:W`, `framed:W`, `flight:W`, `launch:W@x,z[,r]`, `nuke`,
    `auto` (удар в окне, лучший у камеры сценария; нет — снаряд, которого она держит дольше всех)."""
    a0, b0 = window(sc)
    if span > 0:
        b0 = min(b0, a0 + span)
    m = re.fullmatch(r"(\w+)(?::([\w*]+))?(?:@(-?[\d.]+),(-?[\d.]+)(?:,([\d.]+))?)?(?:#(\d+))?", sel.strip())
    if not m:
        raise SystemExit(f"не понял объект плана: {sel}")
    kind, weapon, nx, nz, rad, nth = m.groups()
    weapon = weapon or "*"
    near = None if nx is None else np.array([float(nx), float(nz)])
    nth = int(nth or 0)
    hit = lambda tr: _impact_of(sc, tr.id)  # noqa: E731

    def with_key(tr: Track, im, what: str) -> Pick:
        return Pick(what, tr, im.t, im.pos, size_of(tr.weapon), blast_of(tr.weapon), a0, b0)

    if kind == "auto":
        hits = [tr for tr in _of(sc, "*") if hit(tr) and a0 <= hit(tr).t <= b0 + HIT_MARGIN]
        if hits:
            kind = "framed"
        else:
            kind = "flight"
    if kind == "impact":
        hits = [tr for tr in _of(sc, weapon) if hit(tr)]
        hits.sort(key=(lambda tr: hit(tr).t) if near is None else
                  (lambda tr: float(np.hypot(*(hit(tr).pos[[0, 2]] - near)))))
        if len(hits) <= nth:
            raise SystemExit(f"{sel}: удара нет")
        tr = hits[nth]
        p = with_key(tr, hit(tr), f"{tr.weapon} → удар")
    elif kind in ("first", "framed"):
        hits = sorted([tr for tr in _of(sc, weapon) if hit(tr) and a0 <= hit(tr).t <= b0 + HIT_MARGIN],
                      key=lambda tr: hit(tr).t)
        if not hits:
            raise SystemExit(f"{sel}: удара в окне {a0}..{b0} нет")
        tr = hits[0]
        if kind == "framed" and sc.cams is not None:
            tr = max(hits, key=lambda x: (_in_frame(sc, hit(x).t, hit(x).pos), _framed(sc, x, a0, hit(x).t)))
        p = with_key(tr, hit(tr), f"{tr.weapon} → удар")
    elif kind in ("flight", "launch"):
        cand = [tr for tr in _of(sc, weapon) if len(tr.t)]
        if kind == "launch":
            r = float(rad or 150)
            cand = [tr for tr in cand if a0 - 40 <= tr.first <= b0 and near is not None
                    and np.hypot(*(tr.pos[0, [0, 2]] - near)) <= r]
        cand.sort(key=lambda tr: tr.first)
        if not cand:
            raise SystemExit(f"{sel}: полёта нет")
        tr = cand[0]
        if sc.cams is not None:
            tr = max(cand, key=lambda x: _framed(sc, x, a0, b0))
        p = Pick(tr.weapon, tr, -1, None, size_of(tr.weapon), 0.0, a0, b0)
    elif kind == "nuke":
        if not sc.nukes:
            raise SystemExit("подрыва в журнале нет")
        t, pos, kt = sc.nukes[0]
        p = Pick("ядерный подрыв", None, t, pos, 300.0, 300.0, a0, b0)
    else:
        raise SystemExit(f"не знаю объект «{kind}»")
    if p.key >= 0:
        p.a = min(p.a, p.key - HIT_MARGIN)
        p.b = max(p.b, p.key + HIT_MARGIN)
    p.a = max(p.a, 1)
    return p
