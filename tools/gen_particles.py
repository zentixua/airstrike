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
  spark       — точка света 16×16 (искра, тянется вдоль скорости), flash — мягкая вспышка 64×64 (ядро и ореол, без лучей:
                лучи — изъян объектива, глаз их не видит), ring — кольцо ударной волны 128×128 (пыльная полоса, края рваные);
  fireball_00…15 — огненный шар 128×128 по кадрам жизни: от вспышки (маленький белый шар) через оранжевый с сажей
                до чёрного дыма с углями. Объёмный рендер: шар с буграми из периодического шума, яркость ядра — как
                у дальней картинки (вспышка, затем остывание (1 − u)³), цвет — по температуре чёрного тела (Планк × CIE 1931,
                в линейном sRGB), где остыло ниже ~1450 K и у края — сажа; тон — по каналам 1 − e⁻ˣ (яркое к белому),
                гамма sRGB. Радиус шара — 0,7 полуразмера кадра (client/far/FarSprites.FIREBALL); свой сид.
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
    """Вспышка: слепящее ядро и ореол, к краю — ноль без ступеньки."""
    v, u = np.meshgrid(np.linspace(-1, 1, n), np.linspace(-1, 1, n), indexing="ij")
    r = np.hypot(u, v)
    a = (0.75 * np.exp(-(r / 0.12) ** 2) + 0.45 * np.exp(-(r / 0.45) ** 2)) * smooth(0, 0.2, 1 - r)
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


# ---------------------------------------------------------------- огненный шар

def _cmf(x, mu, s1, s2):
    s = np.where(x < mu, s1, s2)
    return np.exp(-0.5 * ((x - mu) / s) ** 2)


def blackbody():
    """Чёрное тело 700–4000 K: температуры, ln яркости против 2000 K, цвет в линейном sRGB на единицу яркости.
    Функции сложения цветов CIE 1931 — многолепестковое приближение Wyman, Sloan, Shirley (2013)."""
    lam = np.arange(380, 781, 2.0)
    xb = 1.056 * _cmf(lam, 599.8, 37.9, 31.0) + 0.362 * _cmf(lam, 442.0, 16.0, 26.7) - 0.065 * _cmf(lam, 501.1, 20.4, 26.2)
    yb = 0.821 * _cmf(lam, 568.8, 46.9, 40.5) + 0.286 * _cmf(lam, 530.9, 16.3, 31.1)
    zb = 1.217 * _cmf(lam, 437.0, 11.8, 36.0) + 0.681 * _cmf(lam, 459.0, 26.0, 13.8)
    to_rgb = np.array([[3.2406, -1.5372, -0.4986], [-0.9689, 1.8758, 0.0415], [0.0557, -0.2040, 1.0570]])
    temps = np.linspace(700, 4000, 331)
    lum, rgb = [], []
    for t in temps:
        b = 1 / ((lam * 1e-9) ** 5 * np.expm1(1.4388e-2 / (lam * 1e-9 * t)))
        xyz = np.array([(b * xb).sum(), (b * yb).sum(), (b * zb).sum()])
        lum.append(xyz[1])
        rgb.append(np.clip(to_rgb @ xyz, 0, None) / xyz[1])
    lum = np.array(lum)
    return temps, np.log(lum / np.interp(2000, temps, lum)), np.array(rgb)


BB_T, BB_LOG, BB_RGB = blackbody()
# яркость шара в 2000 K против белого экрана днём (как у дальней картинки: в десятки раз ярче неба)
BB_DAY = 30.0


def glow_rgb(e):
    """Линейный цвет раскалённого газа яркости e (против белого экрана): температура по яркости, цвет — чёрного тела."""
    t = np.interp(np.log(np.maximum(e, 1e-9) / BB_DAY), BB_LOG, BB_T)
    c = np.stack([np.interp(t, BB_T, BB_RGB[:, i]) for i in range(3)], -1)
    y = c @ np.array([0.2126, 0.7152, 0.0722])
    return c * (e / np.maximum(y, 1e-12))[..., None]


def noise_volume(n, seed, beta=3.6):
    """Периодический шум n³ спектром k^(−β/2), без самых мелких волн: клубы без швов при сдвиге."""
    w = np.random.default_rng(seed).standard_normal((n, n, n))
    f = np.fft.fftfreq(n)
    kx, ky, kz = np.meshgrid(f, f, f, indexing="ij")
    k = np.sqrt(kx ** 2 + ky ** 2 + kz ** 2)
    k[0, 0, 0] = 1
    amp = k ** (-beta / 2) * (k < 0.45)
    amp[0, 0, 0] = 0
    v = np.real(np.fft.ifftn(np.fft.fftn(w) * amp))
    return (v - v.mean()) / v.std()


def trilinear(vol, x, y, z):
    """Шум в точках (доли периода), трилинейно, с повтором."""
    n = vol.shape[0]
    x, y, z = x * n, y * n, z * n
    x0, y0, z0 = np.floor(x).astype(int), np.floor(y).astype(int), np.floor(z).astype(int)
    fx, fy, fz = x - x0, y - y0, z - z0
    out = 0
    for xi, wx in ((x0, 1 - fx), (x0 + 1, fx)):
        for yi, wy in ((y0, 1 - fy), (y0 + 1, fy)):
            for zi, wz in ((z0, 1 - fz), (z0 + 1, fz)):
                out = out + vol[xi % n, yi % n, zi % n] * wx * wy * wz
    return out


def fireball(u, vol, n=128, steps=96):
    """Кадр шара на доле жизни u: луч вдоль взгляда через объём, излучение и поглощение (газ и сажа)."""
    # радиус в долях полуразмера кадра: за первые проценты жизни — почти весь, потом медленно растёт
    radius = 0.70 * (0.30 + 0.70 * (1 - np.exp(-u / 0.05))) * (1 + 0.06 * u)
    # яркость ядра против белого экрана: вспышка, потом остывание
    core = 60 * max(0.0, 1 - (u / 0.14) ** 2) + 16 * (1 - u) ** 3 + 0.02
    lin = np.linspace(-1 + 1 / n, 1 - 1 / n, n)
    zs = np.linspace(-1, 1, steps)
    dz = zs[1] - zs[0]
    yy, xx = np.meshgrid(-lin, lin, indexing="ij")
    light = np.zeros((n, n, 3))
    clear = np.ones((n, n))
    q = 1 / radius
    for z in zs:
        r = np.sqrt(xx ** 2 + yy ** 2 + z ** 2)
        # бугры растут вместе с шаром (шум по точке, нормированной на радиус) и кипят со временем
        big = trilinear(vol, xx * q * 0.17, yy * q * 0.17, z * q * 0.17 + 0.3 * u)
        fine = trilinear(vol, xx * q * 0.55 + 0.5, yy * q * 0.55 + 0.3, z * q * 0.55 + 0.9 * u)
        edge = radius * (1 + (0.16 + 0.14 * u) * np.tanh(0.6 * big + 0.3 * fine))
        soft = 0.03 + 0.09 * u * u
        dens = smooth(-soft, soft, edge - r) * (1 - 0.45 * u * u * smooth(0.6, 1.0, r / edge))
        if not dens.any():
            continue
        x = np.clip(r / edge, 0, 1)
        heat = core * (1 - 0.9 * x ** 2) * np.exp(0.7 * np.clip(fine, -2, 2) - 0.25 * big) * (1 - 0.35 * u * x)
        # сажа: где остыло (яркость < ~0,5 — ниже ~1450 K) и у края, раньше всего остывающего
        soot = np.maximum(smooth(0.5, 0.05, heat), 0.8 * smooth(0.82, 1.0, x) * smooth(0.08, 0.35, u)) * (0.3 + 0.7 * smooth(0.1, 0.6, u))
        gas, smoke = 22.0 * dens, 40.0 * dens * soot
        total = gas + smoke
        a = 1 - np.exp(-total * dz)
        # дым светит только своим цветом (свет сверху, выпуклое светлее): шар рисуется без света мира
        shade = np.clip(0.75 + 0.3 * np.clip(yy * q, -1, 1) + 0.35 * np.tanh(big - 0.6 * fine), 0.25, 1.4)
        w = np.where(total > 0, gas / np.maximum(total, 1e-9), 0)[..., None]
        src = glow_rgb(heat * (1 - soot)) * w + np.array([0.055, 0.05, 0.046]) * shade[..., None] * (1 - w)
        light += (clear * a)[..., None] * src
        clear *= 1 - a
    alpha = 1 - clear
    # цвет без непрозрачности — средний свет по лучу; тон по каналам 1 − e⁻ˣ, гамма sRGB
    tone = 1 - np.exp(-light / np.maximum(alpha[..., None], 1e-4))
    srgb = np.where(tone <= 0.0031308, 12.92 * tone, 1.055 * np.power(tone, 1 / 2.4) - 0.055)
    return np.where(alpha[..., None] > 1e-4, srgb, 0), alpha


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
    volume = noise_volume(64, 1945)
    for i in range(16):
        save(*fireball((i + 0.5) / 16, volume), f"fireball_{i:02d}")
    print("ok")
