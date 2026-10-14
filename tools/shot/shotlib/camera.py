"""Камера как тело: место по тикам и взгляд, который из него следует, — как у дрона, оператора на ногах или штатива.

Углы — как в Minecraft: курс 0 — на юг (+Z), 90 — на запад (−X); тангаж плюс — вниз; крен плюс — влево (как ключи
Flashback). Поле зрения — по вертикали, градусы. Тик — 1/20 с, блок — метр.
"""
from __future__ import annotations

import math

import numpy as np

G = 9.81 / 400.0  # ускорение свободного падения, блоков за тик²


def forward(yaw, pitch):
    y = np.radians(yaw)
    p = np.radians(pitch)
    return np.stack([-np.sin(y) * np.cos(p), -np.sin(p), np.cos(y) * np.cos(p)], axis=-1)


def angles(d):
    """Курс и тангаж направления d (…, 3), градусы."""
    d = np.asarray(d, dtype=np.float64)
    h = np.hypot(d[..., 0], d[..., 2])
    yaw = np.degrees(np.arctan2(-d[..., 0], d[..., 2]))
    pitch = np.degrees(np.arctan2(-d[..., 1], np.maximum(h, 1e-9)))
    return yaw, pitch


def unwrap_deg(a, axis=-1):
    return np.degrees(np.unwrap(np.radians(a), axis=axis))


def basis(yaw, pitch, roll):
    """Оси кадра: вперёд, вправо, вверх (…, 3) с креном."""
    f = forward(yaw, pitch)
    up0 = np.array([0.0, 1.0, 0.0])
    r = np.cross(f, up0)
    r /= np.maximum(np.linalg.norm(r, axis=-1, keepdims=True), 1e-9)
    u = np.cross(r, f)
    c = np.cos(np.radians(roll))[..., None]
    s = np.sin(np.radians(roll))[..., None]
    # крен влево (плюс): верх кадра уходит влево
    r2 = r * c + u * s
    u2 = u * c - r * s
    return f, r2, u2


def project(eye, yaw, pitch, roll, fov, aspect, p):
    """Точки p в кадре: u, v (−1…1 по краям кадра), глубина по оси взгляда (≤0 — позади)."""
    f, r, u = basis(yaw, pitch, roll)
    d = np.asarray(p) - np.asarray(eye)
    z = (d * f).sum(-1)
    th = np.tan(np.radians(fov) / 2)
    # у самой плоскости кадра и позади деление дало бы бесконечность: место — не дальше 20 полукадров
    zz = np.where(z > 0.1, z, 0.1)
    u_ = np.clip((d * r).sum(-1) / zz / (th * aspect), -20, 20)
    v_ = np.clip((d * u).sum(-1) / zz / th, -20, 20)
    return u_, v_, z


def share(size, depth, fov, aspect=None):
    """Доля высоты кадра под предметом поперечника size на глубине depth (как `share` проверки кадров FrameQa)."""
    th = np.tan(np.radians(fov) / 2)
    return size / np.maximum(depth, 0.1) / (2 * th)


def fov_for(size, dist, want, aspect=None):
    """Угол кадра (по вертикали), при котором предмет size на расстоянии dist — доля want высоты кадра."""
    return math.degrees(2 * math.atan(size / (2 * want * max(dist, 1e-6))))


def gauss(x, sigma, axis=-1):
    """Сглаживание без сдвига во времени (края — отражением)."""
    if sigma <= 0:
        return x
    from scipy.ndimage import gaussian_filter1d
    return gaussian_filter1d(x, sigma, axis=axis, mode="nearest")


def spring(x, hz, damping, axis=-1):
    """Пружина второго порядка (запаздывание и чуть проскакивает): как взгляд оператора догоняет цель."""
    if hz <= 0:
        return x
    x = np.moveaxis(np.asarray(x, dtype=np.float64), axis, -1)
    w = 2 * math.pi * hz / 20.0
    out = np.empty_like(x)
    pos = x[..., 0].copy()
    vel = np.zeros_like(pos)
    for i in range(x.shape[-1]):
        acc = w * w * (x[..., i] - pos) - 2 * damping * w * vel
        vel += acc
        pos += vel
        out[..., i] = pos
    return np.moveaxis(out, -1, axis)


def deriv(p, k=1):
    """Производная по тикам вдоль оси −2 (места (…, n, 3))."""
    for _ in range(k):
        p = np.gradient(p, axis=-2)
    return p


def bank(vel, acc):
    """Крен дрона в вираже: вектор тяги наклонён на боковое ускорение (влево — плюс), градусы."""
    h = np.stack([vel[..., 0], np.zeros_like(vel[..., 0]), vel[..., 2]], axis=-1)
    hn = h / np.maximum(np.linalg.norm(h, axis=-1, keepdims=True), 1e-6)
    left = np.cross(np.array([0.0, 1.0, 0.0]), hn)  # слева от курса
    lat = (acc * left).sum(-1)
    return np.degrees(np.arctan2(lat, G + acc[..., 1]))


def nod(vel, acc):
    """Кивок дрона: разгон — носом вниз, торможение — вверх (тангаж плюс — вниз), градусы."""
    h = np.stack([vel[..., 0], np.zeros_like(vel[..., 0]), vel[..., 2]], axis=-1)
    hn = h / np.maximum(np.linalg.norm(h, axis=-1, keepdims=True), 1e-6)
    fwd = (acc * hn).sum(-1)
    return np.degrees(np.arctan2(fwd, G + acc[..., 1]))
