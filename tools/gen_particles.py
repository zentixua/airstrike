#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
# ]
# ///
"""Текстуры частиц эффектов (дым, огонь, искры, вспышка, ударное кольцо), нарисованные кодом; сид фиксированный.

  uv run tools/gen_particles.py   → mod/src/main/resources/assets/airstrike/textures/fx/particle/…

  smoke_00…15 — 4 клуба дыма × 4 стадии рассеивания (клуб «тает» по краям), почти белые: цвет даёт частица;
  fire_00…07  — клубы пламени: белое ядро, жёлтое и оранжевое тело, тёмно-красные языки по краю;
  spark       — точка света 16×16 (искра, тянется вдоль скорости), flash — вспышка с лучами 64×64,
  ring        — кольцо ударной волны 128×128 (пыльная полоса, края рваные);
  ../plume, ../plume_diamonds — факел двигателя 64×256 (сопло сверху, хвост внизу; второй — с «алмазами»
  скачков уплотнения, как у МБР и стартового ускорителя), ../halo — ореол у сопла 64×64; у этих трёх цвет
  домножен на прозрачность — они рисуются сложением.
  ../../far/disc — круг с мягким краем 32×32 (корпус снаряда, огненный шар и вспышка вдали), ../../far/glow — гауссов
  ореол 64×64 (блик и вуаль вокруг вспышки и шара, зарево на облаках: широкий мягкий свет
  без ядра; лентой шлейфа — средняя строка); белые, цвет и яркость даёт вершина.
Частицы, круг, ореол и клубы nuke/puffs слой эффектов кладёт на свой лист со сглаживанием и уменьшенными копиями
(client/fx/layer/FxAtlas).
Нужны Pillow и numpy.
"""
import os

import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "airstrike", "textures", "fx", "particle")
rng = np.random.default_rng(1945)


def save(arr_rgb, alpha, name):
    img = Image.fromarray(np.uint8(np.clip(np.dstack([arr_rgb, alpha]), 0, 1) * 255 + 0.5), "RGBA")
    path = os.path.normpath(os.path.join(OUT, name + ".png"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def fbm(n, octaves=5, base=4):
    """Фрактальный шум без швов: сумма октав гладкого шума (интерполяция случайной решётки)."""
    out = np.zeros((n, n))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = base * 2 ** o
        grid = rng.random((cells + 1, cells + 1))
        grid[-1, :] = grid[0, :]
        grid[:, -1] = grid[:, 0]
        x = np.linspace(0, cells, n, endpoint=False)
        i = x.astype(int)
        f = x - i
        f = f * f * (3 - 2 * f)
        a = grid[np.ix_(i, i)]
        b = grid[np.ix_(i, i + 1)]
        c = grid[np.ix_(i + 1, i)]
        d = grid[np.ix_(i + 1, i + 1)]
        fy, fx = np.meshgrid(f, f, indexing="ij")
        out += amp * ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy)
        total += amp
        amp *= 0.5
    return out / total


def smooth(e0, e1, x):
    s = np.clip((x - e0) / (e1 - e0), 0, 1)
    return s * s * (3 - 2 * s)


def blobs(n, count, spread, size):
    """Цветная капуста: основной ком и бугры вокруг — плотность клуба."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    d = np.exp(-(u ** 2 + v ** 2) / (2 * 0.38 ** 2))
    for _ in range(count):
        a, r = rng.uniform(0, 2 * np.pi), rng.uniform(0.15, spread)
        sg = rng.uniform(size * 0.6, size)
        d += rng.uniform(0.45, 0.9) * np.exp(-((u - r * np.cos(a)) ** 2 + (v - r * np.sin(a)) ** 2) / (2 * sg ** 2))
    edge = smooth(0.0, 0.25, 0.97 - np.hypot(u, v))
    return d / d.max() * edge, u, v


def blur(a, radius):
    """Размытие по Гауссу (через Pillow) — убирает «зерно» мелкого шума."""
    img = Image.fromarray(np.uint8(np.clip(a, 0, 1) * 255))
    return np.asarray(img.filter(ImageFilter.GaussianBlur(radius)), dtype=float) / 255


def smoke(n=64):
    """Клуб дыма и 4 стадии: освещён сверху-слева, тень внизу, края клочьями; к последней стадии — редкая дымка."""
    d, u, v = blobs(n, rng.integers(7, 12), 0.5, 0.26)
    detail = fbm(n, 4, 3)
    dens = blur(d * (0.7 + 0.45 * detail), 1.2)
    gy, gx = np.gradient(blur(dens, 1.6))
    # свет сверху-слева: склоны, повёрнутые к свету, ярче; низ клуба в собственной тени
    lit = (gy * 0.8 + gx * 0.45) * n / 3
    # внутренние «кочаны» клуба: средний шум в яркости, без мелкого зерна
    lum = blur(np.clip(0.8 + 0.3 * lit - 0.14 * np.clip(v, 0, 1) + 0.28 * (blur(detail, 1.5) - 0.5), 0.45, 1.0), 0.8)
    frames = []
    for k in range(4):
        thr = 0.04 + 0.09 * k
        erode = dens - thr * (0.6 + 0.8 * fbm(n, 3, 4))
        alpha = blur(smooth(0.0, 0.5 + 0.1 * k, erode), 0.8) * (0.95 - 0.1 * k)
        frames.append((np.dstack([lum] * 3), alpha))
    return frames


def fire(n=64):
    """Клуб пламени: температура по плотности — белое ядро, жёлтое, оранжевое, красные рваные края."""
    d, u, v = blobs(n, rng.integers(6, 10), 0.45, 0.24)
    turb = fbm(n, 5, 4)
    t = np.clip(d * (0.4 + 0.9 * turb) * 1.25, 0, 1)
    stops = [(0.0, (0.35, 0.04, 0.0)), (0.25, (0.75, 0.16, 0.02)), (0.5, (1.0, 0.45, 0.06)),
             (0.72, (1.0, 0.75, 0.25)), (0.9, (1.0, 0.95, 0.7)), (1.0, (1.0, 1.0, 0.95))]
    rgb = np.dstack([np.interp(t, [p for p, _ in stops], [c[i] for _, c in stops]) for i in range(3)])
    alpha = smooth(0.08, 0.35, t)
    return rgb, alpha


def spark(n=16):
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    a = np.clip(np.exp(-(r / 0.28) ** 2) + 0.35 * np.exp(-(r / 0.7) ** 2), 0, 1) * smooth(0, 0.15, 1 - r)
    return np.ones((n, n, 3)), a / a.max()


def flash(n=64):
    """Вспышка: слепящее ядро, ореол и шесть тонких лучей."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    ang = np.arctan2(v, u)
    rays = np.abs(np.cos(3 * ang)) ** 40 * np.exp(-(r / 0.8) ** 2) * 0.5
    a = 0.75 * np.exp(-(r / 0.12) ** 2) + 0.45 * np.exp(-(r / 0.45) ** 2) + rays
    a *= smooth(0, 0.2, 1 - r)
    return np.ones((n, n, 3)), np.clip(a / a.max(), 0, 1)


def ring(n=128):
    """Кольцо ударной волны: пыльная полоса по окружности, внутренний край чёткий, внешний — рваный."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    noise = fbm(n, 5, 6)
    band = smooth(0.62, 0.8, r + 0.08 * (noise - 0.5)) * smooth(0.0, 0.14, 0.98 - r - 0.1 * noise)
    alpha = band * (0.55 + 0.6 * noise)
    lum = 0.82 + 0.18 * noise
    return np.dstack([lum] * 3), np.clip(alpha, 0, 1)


def plume(w=64, h=256, diamonds=False):
    """Факел: яркое узкое ядро у сопла, расширяется и остывает к хвосту, рваный край; по желанию — «алмазы»."""
    v, u = np.meshgrid(np.linspace(0, 1, h), np.linspace(-1, 1, w), indexing="ij")
    turb = fbm(max(w, h), 5, 4)[:h, :w]
    width = 0.35 + 0.65 * np.sqrt(v)  # ширина струи вдоль длины
    x = np.abs(u) / width
    core = np.clip(1 - x ** 2, 0, 1) ** 1.5
    along = np.exp(-v * 2.2) * smooth(0.0, 0.03, v)
    inten = core * along * (0.75 + 0.5 * turb) * smooth(0.0, 0.35, 1 - v - 0.25 * (turb - 0.5))
    if diamonds:
        nodes = np.clip(np.cos(v * np.pi * 2 * 6.5) * 0.5 + 0.5, 0, 1) ** 6 * np.exp(-v * 4) * np.clip(1 - (np.abs(u) / 0.3) ** 2, 0, 1)
        inten = inten + 0.9 * nodes
    a = np.clip(inten * 1.6, 0, 1)
    # цвет домножен на прозрачность: факел рисуется сложением (ONE, ONE), где альфа не участвует
    return np.dstack([a] * 3), a


def halo(n=64):
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    a = (0.7 * np.exp(-(r / 0.2) ** 2) + 0.3 * np.exp(-(r / 0.55) ** 2)) * smooth(0, 0.2, 1 - r)
    a = a / a.max()
    return np.dstack([a] * 3), a


def far_disc(n=32):
    """Круг: сплошной до 0,85 полуразмера, край — до 1 (сглаженный край в пиксель-другой на любом размере)."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    return np.ones((n, n, 3)), smooth(0, 0.15, 1 - r)


def far_glow(n=64):
    """Ореол: exp(−(r/0,42)²), к краю квадрата — ноль без ступеньки."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    a = np.exp(-(r / 0.42) ** 2) * smooth(0, 0.25, 1 - r)
    return np.ones((n, n, 3)), a / a.max()


if __name__ == "__main__":
    for i in range(4):
        for k, (rgb, a) in enumerate(smoke()):
            save(rgb, a, f"smoke_{i * 4 + k:02d}")
    for i in range(8):
        save(*fire(), f"fire_{i:02d}")
    save(*spark(), "spark")
    save(*flash(), "flash")
    save(*ring(), "ring")
    save(*plume(), "../plume")
    save(*plume(diamonds=True), "../plume_diamonds")
    save(*halo(), "../halo")
    save(*far_disc(), "../../far/disc")
    save(*far_glow(), "../../far/glow")
    print("ok")
