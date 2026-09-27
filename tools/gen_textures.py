#!/usr/bin/env python3
"""Текстуры мода, нарисованные кодом (фиксированный сид — результат воспроизводим).

  python3 tools/gen_textures.py   → mod/src/main/resources/assets/airstrike/textures/…

  item/strike_designator, item/geiger_counter — пиксель-арт 16×16 (сетка символов + палитра);
  block/trinitite — оплавленный зелёный песок 16×16, бесшовный;
  nuke/puffs — атлас 4×2 клубов гриба по 64×64 (почти серые: цвет даёт рендерер), nuke/plasma — бесшовная плазма шара 128×128,
  nuke/rain — капля чёрного дождя 8×32, nuke/flare — круглое свечение 64×64 (голова следа боеголовки, вспышка).
Нужны Pillow и numpy.
"""
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "airstrike", "textures")
rng = np.random.default_rng(7)

# Пульт наведения: защитный корпус, два объектива дальномера, экран с прицельной маркой, красная кнопка пуска
DESIGNATOR = [
    "................",
    "..KKKK....KKKK..",
    ".KLLLLK..KLLLLK.",
    ".KLGGLK..KLGGLK.",
    ".KLLLLKKKKLLLLK.",
    "..KKKKOOOOKKKK..",
    "..KOOOOOOOOOOK..",
    "..KODDDDDDDDOK..",
    "..KODSSSXSSDOK..",
    "..KODSXXXXSDOK..",
    "..KODSSSXSSDOK..",
    "..KODDDDDDDDOK..",
    "..KOOOOOORROOK..",
    "..KOOoOoORROOK..",
    "...KKKKKKKKKK...",
    "................",
]
PALETTE = {
    ".": (0, 0, 0, 0),
    "K": (24, 26, 22, 255),     # контур
    "O": (86, 98, 58, 255),     # корпус (олива)
    "o": (64, 74, 44, 255),     # тень корпуса
    "L": (60, 62, 66, 255),     # оправа объектива
    "G": (120, 190, 220, 255),  # стекло
    "D": (30, 34, 30, 255),     # рамка экрана
    "S": (40, 90, 50, 255),     # экран
    "X": (140, 255, 140, 255),  # прицельная марка
    "R": (220, 40, 30, 255),    # кнопка пуска / стрелка
    "Y": (206, 174, 44, 255),   # корпус счётчика
    "y": (150, 124, 32, 255),   # тень корпуса
    "W": (232, 230, 212, 255),  # шкала
    "g": (70, 62, 34, 255),     # решётка динамика
    "n": (44, 44, 46, 255),     # ручки
    "M": (196, 200, 206, 255),  # трубка датчика
    "m": (118, 124, 134, 255),  # тень трубки
    "H": (46, 46, 50, 255),     # рукоять датчика
    "c": (30, 30, 30, 255),     # витой шнур
}

# Счётчик Гейгера: жёлтый корпус с ручкой, шкала со стрелкой, динамик, датчик на витом шнуре
GEIGER = [
    "................",
    ".............KK.",
    "............KMmK",
    "............KMmK",
    "...KKKKK....KMmK",
    "...K...K....KMmK",
    "KKKKKKKKKKK.KHHK",
    "KYYYYYYYYyK.KHHK",
    "KYDDDDDYYyK.KHHK",
    "KYDWWRDYYyK..KK.",
    "KYDWRWDYYyK..c..",
    "KYDDDDDYgyK.c.c.",
    "KYYYYYYYgyKc...c",
    "KYnYnYYYgyK.c.c.",
    "KyyyyyyyyyK..c..",
    ".KKKKKKKKK......",
]


def save(img, name):
    path = os.path.join(OUT, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def paint(rows, name):
    img = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        assert len(row) == 16, (name, y)
        for x, c in enumerate(row):
            img.putpixel((x, y), PALETTE[c])
    save(img, name)


def rgba(rgb, a):
    """Массивы цвета (h, w, 3) и прозрачности (h, w) в 0..1 → картинка RGBA."""
    return Image.fromarray(np.uint8(np.clip(np.dstack([rgb, a]), 0, 1) * 255 + 0.5), "RGBA")


def pnoise(size, beta):
    """Периодический (бесшовный) шум 1/f^beta через БПФ, в 0..1."""
    X = np.fft.fft2(rng.standard_normal((size, size)))
    k = np.fft.fftfreq(size) * size
    X /= np.maximum(np.hypot(*np.meshgrid(k, k)), 1) ** beta
    n = np.real(np.fft.ifft2(X))
    return (n - n.min()) / (n.max() - n.min())


def smooth(e0, e1, x):
    s = np.clip((x - e0) / (e1 - e0), 0, 1)
    return s * s * (3 - 2 * s)


def gradient(v, stops):
    """Цвет по опорным точкам [(v, (r, g, b)), …], v и компоненты в 0..1."""
    return np.dstack([np.interp(v, [p for p, _ in stops], [c[i] for _, c in stops]) for i in range(3)])


def puff(N=64):
    """Клуб дыма: основной ком и несколько бугров, внутри «кипящий» шум, свет сверху; края прозрачные."""
    u, v = np.meshgrid(np.linspace(-1, 1, N), np.linspace(-1, 1, N))
    d = np.exp(-(u ** 2 + v ** 2) / (2 * 0.42 ** 2))
    for _ in range(rng.integers(5, 10)):
        a, r = rng.uniform(0, 2 * np.pi), rng.uniform(0.2, 0.5)
        sg = rng.uniform(0.15, 0.3)
        d += rng.uniform(0.5, 0.9) * np.exp(-((u - r * np.cos(a)) ** 2 + (v - r * np.sin(a)) ** 2) / (2 * sg ** 2))
    d = d / d.max() * (0.7 + 0.6 * pnoise(N, 1.7)) * smooth(0.0, 0.3, 0.98 - np.hypot(u, v))
    alpha = smooth(0.05, 0.5, d) * 0.95
    shade = np.gradient(d, axis=0) * N / 4  # склоны, повёрнутые вверх (плотность растёт вниз), светлее
    lum = np.clip(0.8 + 0.25 * shade + 0.1 * (pnoise(N, 1.2) - 0.5), 0.6, 1.0)
    return rgba(np.dstack([lum] * 3), alpha)


def plasma(N=128):
    """Плазма огненного шара: жёлто-белые горячие пятна и оранжевые нити, бесшовная, непрозрачная."""
    hot = pnoise(N, 1.8) ** 1.5
    fil = (1 - np.abs(2 * pnoise(N, 1.3) - 1)) ** 6
    v = np.clip(0.2 + 0.6 * hot + 0.35 * fil, 0, 1)
    rgb = gradient(v, [(0, (0.47, 0.12, 0.0)), (0.4, (0.9, 0.43, 0.08)), (0.7, (1.0, 0.78, 0.31)), (1, (1.0, 0.98, 0.9))])
    return rgba(rgb, np.ones((N, N)))


def rain_streak(w=8, h=32):
    """Капля чёрного дождя: тонкий хвост сверху, толстая маслянистая голова снизу с бликом."""
    x, y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    k = y / h
    width = 0.5 + 1.2 * k ** 3
    a = (0.15 + 0.7 * k ** 1.5) * np.exp(-((x - w / 2) / width) ** 2) * smooth(0, 1.5, h - 0.5 - y)
    glint = np.exp(-((x - w / 2 + 0.8) ** 2 + (y - h + 4) ** 2) / 1.5)
    rgb = np.dstack([0.14, 0.1, 0.07]) + np.dstack([0.2, 0.17, 0.12]) * glint[..., None]
    return rgba(rgb, a)


def flare(N=64):
    """Круглое белое свечение: яркое ядро и мягкий ореол, к краю — ноль."""
    u, v = np.meshgrid(np.linspace(-1, 1, N), np.linspace(-1, 1, N))
    r = np.hypot(u, v)
    a = (0.6 * np.exp(-(r / 0.15) ** 2) + 0.4 * np.exp(-(r / 0.5) ** 2)) * smooth(0, 0.3, 1 - r)
    return rgba(np.ones((N, N, 3)), a / a.max())


def trinitite(N=16):
    """Тринитит: стекловидный зелёно-серый сплав песка, пузырьки с бликом, светлые и тёмные вкрапления; бесшовный."""
    shades = np.array([(58, 74, 60), (72, 92, 72), (88, 110, 84), (104, 128, 96), (126, 150, 112)]) / 255
    idx = np.clip((pnoise(N, 1.2) + 0.25 * rng.random((N, N))) / 1.25 * len(shades), 0, len(shades) - 1).astype(int)
    rgb = shades[idx]
    for _ in range(4):  # пузырёк: тёмная точка, блик слева сверху (с переносом через край)
        x, y = rng.integers(0, N, 2)
        rgb[y, x] = (40 / 255, 52 / 255, 44 / 255)
        rgb[(y - 1) % N, (x - 1) % N] = (150 / 255, 176 / 255, 140 / 255)
    for _ in range(6):
        x, y = rng.integers(0, N, 2)
        rgb[y, x] = (170 / 255, 204 / 255, 150 / 255) if rng.random() < 0.6 else (34 / 255, 40 / 255, 34 / 255)
    return rgba(rgb, np.ones((N, N)))


if __name__ == "__main__":
    paint(DESIGNATOR, "item/strike_designator")
    paint(GEIGER, "item/geiger_counter")
    save(trinitite(), "block/trinitite")
    atlas = Image.new("RGBA", (256, 128))
    for i in range(8):
        atlas.paste(puff(), ((i % 4) * 64, (i // 4) * 64))
    save(atlas, "nuke/puffs")
    save(plasma(), "nuke/plasma")
    save(rain_streak(), "nuke/rain")
    save(flare(), "nuke/flare")
    print("ok")
