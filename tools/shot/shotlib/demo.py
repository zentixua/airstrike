"""Тестовая сцена без карты: квартал домов, ракета заходит на дом, рядом дымят прежние удары. Для тестов и показа."""
from __future__ import annotations

import numpy as np

from . import world as W
from .scene import Impact, Scene, Track


def city(seed: int = 3, size: int = 320, ground: int = 64) -> W.World:
    w = W.empty((-size // 2, 40, -size // 2), (size, 140, size), ground_y=ground)
    w.rgb = np.array([(0, 0, 0), (95, 159, 53), (125, 125, 125), (180, 180, 186), (52, 92, 196), (60, 110, 40),
                      (190, 220, 230), (150, 97, 83), (200, 190, 170), (90, 90, 95)], dtype=np.uint8)
    w.col[:, :ground - 40, :] = 1
    r = np.random.default_rng(seed)
    # улицы — асфальт
    for k in range(-size // 2, size // 2, 40):
        W.box(w, (k, ground - 1, -size // 2), (k + 8, ground, size // 2), color=9)
        W.box(w, (-size // 2, ground - 1, k), (size // 2, ground, k + 8), color=9)
    for bx in range(-size // 2 + 10, size // 2 - 30, 40):
        for bz in range(-size // 2 + 10, size // 2 - 30, 40):
            h = int(r.integers(8, 70))
            dx, dz = int(r.integers(12, 28)), int(r.integers(12, 28))
            W.box(w, (bx, ground, bz), (bx + dx, ground + h, bz + dz), color=int(r.choice([2, 3, 7, 8])))
            # деревья во дворе
            if r.random() < 0.5:
                tx, tz = bx + dx + 3, bz + 2
                W.box(w, (tx - 2, ground + 3, tz - 2), (tx + 3, ground + 8, tz + 3), kind=W.SOFT, color=5)
    return w


def strike_scene(seed: int = 3) -> tuple[Scene, int, np.ndarray]:
    """Квартал, ракета (4 блока за тик) с востока бьёт в грань дома в тик 240; три прежних удара дымят у подлёта."""
    w = city(seed)
    sc = Scene(w)
    # цель — восточная грань самого высокого дома ближе к центру
    target = np.array([10.0, 90.0, 10.0])
    kx, kz = int(target[0] - w.origin[0]), int(target[2] - w.origin[2])
    col = w.kind[kx:kx + 60, :, kz]
    face = None
    for i in range(col.shape[0]):
        ys = np.flatnonzero(col[i] == W.OPAQUE)
        if len(ys) and ys.max() + w.origin[1] > 75:
            face = np.array([target[0] + i - 0.5, min(ys.max() + w.origin[1] - 6, 100.0), target[2] + 0.5])
            break
    if face is None:
        face = np.array([30.0, 70.0, 10.5])
    t_hit = 240
    ts = np.arange(40, t_hit + 1)
    start = face + np.array([4 * (t_hit - 40), 120, 30])
    k = ((ts - 40) / (t_hit - 40))[:, None]
    # дуга: ровно на высоте, последние 2 с — пикирование
    path = start * (1 - k) + face * k
    path[:, 1] = face[1] + 120 * np.clip(1 - (ts - (t_hit - 50)) / 50, 0, 1) ** 1.5
    sc.tracks["m1"] = Track("m1", "missile", ts, path)
    sc.impacts.append(Impact(t_hit, face, "missile", "m1", ground=True))
    for j, (dx, dz, tt) in enumerate(((60, 30, 60), (110, -20, 120), (40, 60, 160))):
        p = face + np.array([dx, 0, dz])
        p[1] = float(w.ground_at(np.array([p[0]]), np.array([p[2]]))[0])
        sc.impacts.append(Impact(tt, p, "drone", None, ground=True))
    return sc, t_hit, face
