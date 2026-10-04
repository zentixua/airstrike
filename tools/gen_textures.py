#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
# ]
# ///
"""Текстуры мода, нарисованные кодом (фиксированный сид — результат воспроизводим).

  uv run tools/gen_textures.py   → mod/src/main/resources/assets/airstrike/textures/…

  item/strike_designator, item/geiger_counter — пиксель-арт 16×16 (сетка символов + палитра);
  item/shahed, lancet, cruise_missile, grad_rockets, bunker_buster, icbm, nuclear_warhead — боеприпасы, тоже пиксель-арт;
  mob_effect/radiation_sickness, mob_effect/burns — значки эффектов 18×18;
  block/trinitite — оплавленный зелёный песок 16×16, бесшовный;
  block/substation_* — трансформаторная подстанция 16×16 (бак, фасад с табличкой, радиатор, изолятор, плита) и обгоревшие;
  block/sam_* — ЗРК 16×16 (шасси с жалюзи, борт контейнеров, торец с крышками четырёх труб, верх, решётка радара);
  item/interceptor — зенитная ракета 16×16;
  nuke/puffs — атлас 4×2 клубов гриба по 64×64 (почти серые: цвет даёт рендерер), nuke/plasma — бесшовная плазма шара 128×128,
  nuke/rain — лист капель чёрного дождя 64×256 (бесшовный по вертикали), nuke/flare — круглое свечение 64×64 (голова следа боеголовки, вспышка).
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
    "B": (250, 214, 40, 255),   # знак радиации: жёлтый
    "b": (20, 20, 20, 255),     # знак радиации: чёрный
    "F": (255, 214, 90, 255),   # пламя: ядро
    "f": (240, 120, 30, 255),   # пламя
    "r": (170, 40, 20, 255),    # пламя: край
    "A": (182, 180, 170, 255),  # планер шахеда (серо-бежевый)
    "a": (126, 124, 116, 255),  # тень планера
}

# Значок ожогов 18×18: язык пламени
BURNS_ICON = [
    "..................",
    "........r.........",
    "........rr........",
    ".......rfr........",
    ".......rffr.......",
    "......rfffr...r...",
    "......rfffrr..rr..",
    ".....rffFffr.rfr..",
    ".....rfFFffrrffr..",
    "....rffFFFffffr...",
    "....rfFFFFFfffr...",
    "...rffFFFFFFffr...",
    "...rfFFFFFFFFfr...",
    "...rfFFFFFFFFfr...",
    "...rffFFFFFFffr...",
    "....rffFFFFffr....",
    ".....rrffffrr.....",
    "..................",
]

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

# Боеприпасы пульта: все носом вверх (бомба — вниз), контур K, без мелочей — читаются в слоте инвентаря
SHAHED = [  # треугольное крыло, кили на концах, толкающий винт сзади
    "................",
    ".......KK.......",
    "......KAAK......",
    "......KAAK......",
    ".....KAAAAK.....",
    ".....KAaaAK.....",
    "....KAAaaAAK....",
    "....KAAaaAAK....",
    "...KAAAaaAAAK...",
    "..KAAAAaaAAAAK..",
    ".KAAAAAaaAAAAAK.",
    "KaAAAAAaaAAAAAaK",
    "KaKKKKAaaAKKKKaK",
    ".K....KAAK....K.",
    "......KnnK......",
    ".......nn.......",
]
LANCET = [  # цилиндр с двумя крестами крыльев, винт сзади
    "................",
    ".......KK.......",
    "......KOOK......",
    "......KOOK......",
    "...KKKKOOKKKK...",
    "..KoooKOOKoooK..",
    "...KKKKOOKKKK...",
    "......KOOK......",
    "......KOOK......",
    "......KOOK......",
    ".KKKKKKOOKKKKKK.",
    "KooooooOOooooooK",
    ".KKKKKKOOKKKKKK.",
    "......KOOK......",
    "......KnnK......",
    ".......nn.......",
]
CRUISE_MISSILE = [  # серый корпус, сложенные крылья, оперение, факел турбины
    "................",
    ".......KK.......",
    "......KMMK......",
    "......KMmK......",
    "......KMmK......",
    "......KMmK......",
    "....KKKMmKKK....",
    "...KmmmMmmmmK...",
    "....KKKMmKKK....",
    "......KMmK......",
    "......KMmK......",
    "......KMmK......",
    ".....KKMmKK.....",
    "....KmKMmKmK....",
    "....KK.FF.KK....",
    ".......ff.......",
]
GRAD_ROCKETS = [  # пакет труб спереди: в каждой — головка снаряда
    "................",
    ".KKKKKKKKKKKKKK.",
    ".KOOOOOOOOOOOOK.",
    ".KOMmOMmOMmOMmK.",
    ".KOmnOmnOmnOmnK.",
    ".KOOOOOOOOOOOOK.",
    ".KOMmOMmOMmOMmK.",
    ".KOmnOmnOmnOmnK.",
    ".KOOOOOOOOOOOOK.",
    ".KOMmOMmOMmOMmK.",
    ".KOmnOmnOmnOmnK.",
    ".KOOOOOOOOOOOOK.",
    ".KooooooooooooK.",
    ".KKKKKKKKKKKKKK.",
    "..Kn........nK..",
    "................",
]
BUNKER_BUSTER = [  # толстая бомба носом вниз: оперение, жёлтый поясок, стальной нос
    "....KK....KK....",
    "....KoK..KoK....",
    "....KooKKooK....",
    ".....KKOOKK.....",
    ".....KOOOOK.....",
    "....KOOOOOoK....",
    "....KYYYYYYK....",
    "....KOOOOOoK....",
    "....KOOOOOoK....",
    "....KOOOOOoK....",
    "....KOOOOOoK....",
    "....KOOOOOoK....",
    ".....KOOOoK.....",
    ".....KMMMmK.....",
    "......KMmK......",
    ".......KK.......",
]
ICBM = [  # белая ракета с чёрной головной частью, ступени, стабилизаторы, факел
    ".......KK.......",
    "......KbbK......",
    "......KbbK......",
    ".....KbbbbK.....",
    ".....KWWWMK.....",
    ".....KWWWMK.....",
    ".....KmmmmK.....",
    ".....KWWWMK.....",
    ".....KWWWMK.....",
    ".....KWWWMK.....",
    ".....KWWWMK.....",
    "....KKWWWMKK....",
    "...KmKWWWMKmK...",
    "...KKKKKKKKKK...",
    "......FFFF......",
    ".......ff.......",
]
NUCLEAR_WARHEAD = [  # головная часть: конус с жёлто-чёрным знаком
    "................",
    ".......KK.......",
    "......KMMK......",
    "......KMmK......",
    ".....KMMmmK.....",
    ".....KMMmmK.....",
    "....KMMBBmmK....",
    "....KMBbbBmK....",
    "...KMMBbbBmmK...",
    "...KMMMBBmmmK...",
    "..KMMMMMmmmmmK..",
    "..KMMMMMmmmmmK..",
    ".KMMMMMMmmmmmmK.",
    ".KmmmmmmmmmmmmK.",
    ".KKKKKKKKKKKKKK.",
    "................",
]
MUNITIONS = {"shahed": SHAHED, "lancet": LANCET, "cruise_missile": CRUISE_MISSILE, "grad_rockets": GRAD_ROCKETS,
             "bunker_buster": BUNKER_BUSTER, "icbm": ICBM, "nuclear_warhead": NUCLEAR_WARHEAD}


def save(img, name):
    path = os.path.join(OUT, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def paint(rows, name):
    size = len(rows)
    img = Image.new("RGBA", (size, size))
    for y, row in enumerate(rows):
        assert len(row) == size, (name, y)
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


def trefoil(N=18):
    """Значок лучевой болезни: знак радиации (трилистник) на жёлтом круге, пиксели по полярным координатам."""
    img = Image.new("RGBA", (N, N))
    c = (N - 1) / 2
    for y in range(N):
        for x in range(N):
            dx, dy = x - c, c - y
            r = np.hypot(dx, dy)
            if r > c + 0.3:
                continue
            ang = np.degrees(np.arctan2(dy, dx)) % 360
            # лепестки с центрами на 30°, 150° и 270°, по 60°
            blade = any(abs((ang - a + 180) % 360 - 180) <= 30 for a in (30, 150, 270)) and 2.6 <= r <= c - 1.2
            dark = r <= 1.5 or blade
            img.putpixel((x, y), PALETTE["b"] if dark else PALETTE["B"])
    return img


def rain_sheet(w=64, h=256, drops=46):
    """Чёрный дождь: лист тонких капель, бесшовный по вертикали (цвет даёт рендерер — здесь почти белые штрихи)."""
    a = np.zeros((h, w))
    rgb = np.ones((h, w, 3)) * 0.9
    for _ in range(drops):
        x = int(rng.integers(0, w))
        y0 = int(rng.integers(0, h))
        length = int(rng.integers(10, 26))
        for k in range(length):
            f = k / length
            a[(y0 + k) % h, x] = max(a[(y0 + k) % h, x], 0.25 + 0.75 * f ** 1.5)
        # тяжёлая маслянистая голова капли
        a[(y0 + length) % h, x] = 1.0
        if x + 1 < w:
            a[(y0 + length) % h, x + 1] = max(a[(y0 + length) % h, x + 1], 0.5)
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


# ---------------------------------------------------------------- подстанция (свой генератор: прежние текстуры не меняются)

srng = np.random.default_rng(11)
STEEL = np.array((104, 116, 106)) / 255     # крашеная сталь бака (серо-зелёная, как у трансформаторов)
CONCRETE = np.array((150, 148, 142)) / 255


def speckle(base, N=16, amount=0.06):
    """Ровная краска с мелкой неровностью, бесшовно."""
    n = pnoise(N, 1.0) - 0.5
    return np.clip(base[None, None, :] * (1 + amount * 2 * n[..., None]) + amount * 0.3 * (srng.random((N, N, 1)) - 0.5), 0, 1)


def substation_side(N=16):
    """Стенка бака: краска, рёбра жёсткости через 5 пикселей, светлая кромка сверху, тёмная снизу."""
    rgb = speckle(STEEL)
    for x in (2, 7, 12):
        rgb[:, x] *= 1.18
        rgb[:, x + 1] *= 0.8
    rgb[0] *= 1.25
    rgb[-1] *= 0.7
    return rgb


def substation_front(N=16):
    """Фасад: дверца с петлями и ручкой, жёлтый треугольник «Осторожно, электрическое напряжение» с молнией."""
    rgb = speckle(STEEL)
    rgb[0] *= 1.25
    rgb[-1] *= 0.7
    rgb[2:15, 2] *= 0.75
    rgb[2:15, 13] *= 0.75
    rgb[2, 2:14] *= 0.75
    rgb[14, 2:14] *= 0.75
    rgb[4, 3] = rgb[11, 3] = (0.3, 0.32, 0.3)             # петли
    rgb[8:10, 12] = (0.2, 0.2, 0.2)                        # ручка
    sign = ["....KK....",
            "....KK....",
            "...KYYK...",
            "...KYYK...",
            "..KYYKYK..",
            "..KYKKYK..",
            ".KYYYKYYK.",
            ".KYYKYYYK.",
            "KKKKKKKKKK"]
    colors = {"K": (0.08, 0.08, 0.08), "Y": (0.96, 0.8, 0.12)}
    for y, row in enumerate(sign):
        for x, c in enumerate(row):
            if c in colors:
                rgb[3 + y, 3 + x] = colors[c]
    return rgb


def substation_top(N=16):
    """Крышка: краска, заклёпки по краю."""
    rgb = speckle(STEEL * 1.08)
    for k in range(1, 16, 4):
        for y, x in ((1, k), (14, k), (k, 1), (k, 14)):
            rgb[y, x] = STEEL * 0.6
    return rgb


def substation_fins(N=16):
    """Радиатор: частые вертикальные рёбра со светом и тенью."""
    rgb = speckle(STEEL, amount=0.04)
    for x in range(N):
        rgb[:, x] *= (1.2, 1.0, 0.72)[x % 3]
    return rgb


def substation_insulator(N=16):
    """Изолятор ввода: коричневый глазурованный фарфор юбками — светлый край, тень под ним; сверху контакт."""
    rgb = np.zeros((N, N, 3))
    glaze = np.array((0.46, 0.2, 0.1))
    for y in range(N):
        rgb[y, :] = glaze * (1.35, 1.1, 0.8, 0.6)[y % 4]
    rgb[:, :] *= 1 + 0.05 * (srng.random((N, N, 1)) - 0.5)
    rgb[0:2] = (0.55, 0.55, 0.52)
    rgb[:, 0] *= 0.8
    rgb[:, -1] *= 0.8
    return np.clip(rgb, 0, 1)


def substation_base(N=16):
    """Бетонная плита с порами."""
    rgb = speckle(CONCRETE, amount=0.08)
    for _ in range(8):
        x, y = srng.integers(0, N, 2)
        rgb[y, x] *= 0.75
    return rgb


def burnt(rgb, N=16):
    """Выгоревшая: копоть пятнами (чёрное к низу и к центру взрыва), вздутая краска, ржавые подтёки."""
    soot = pnoise(N, 1.4)
    k = np.clip(0.25 + 0.55 * soot, 0, 1)[..., None]
    out = rgb * k
    rust = srng.random((N, N)) < 0.08
    out[rust] = (0.32, 0.14, 0.06)
    return np.clip(out, 0, 1)


def substation():
    for name, f in (("side", substation_side), ("front", substation_front), ("top", substation_top),
                    ("fins", substation_fins), ("insulator", substation_insulator)):
        rgb = f()
        save(rgba(rgb, np.ones(rgb.shape[:2])), f"block/substation_{name}")
        save(rgba(burnt(rgb), np.ones(rgb.shape[:2])), f"block/substation_{name}_burnt")
    rgb = substation_base()
    save(rgba(rgb, np.ones(rgb.shape[:2])), "block/substation_base")


# ---------------------------------------------------------------- ЗРК (свой генератор: прежние текстуры не меняются)

OLIVE = np.array((86, 98, 58)) / 255         # защитная краска, как у пульта
zrng = np.random.default_rng(13)


def olive(N=16, amount=0.06):
    """Защитная краска с мелкой неровностью, бесшовно."""
    n = pnoise(N, 1.0) - 0.5
    return np.clip(OLIVE[None, None, :] * (1 + amount * 2 * n[..., None]) + amount * 0.3 * (zrng.random((N, N, 1)) - 0.5), 0, 1)


def sam_base(N=16):
    """Шасси: жалюзи двигателя (тёмные щели со светлой кромкой), снизу — тень и грязь."""
    rgb = olive()
    for y in (3, 6, 9):
        rgb[y, 2:14] *= 0.45
        rgb[y + 1, 2:14] *= 1.2
    rgb[12:] *= np.linspace(0.95, 0.6, N - 12)[:, None, None]
    rgb[0] *= 1.2
    return np.clip(rgb, 0, 1)


def sam_side(N=16):
    """Борт пакета контейнеров: продольные рёбра, хомуты, жёлтая трафаретная полоса."""
    rgb = olive()
    for y in (0, 5, 10, 15):
        rgb[y] *= 0.7
    for x in (3, 12):
        rgb[:, x] *= 0.75
        rgb[:, x + 1] *= 1.15
    rgb[7, 5:11] = (0.78, 0.66, 0.16)
    return np.clip(rgb, 0, 1)


def sam_tubes(N=16):
    """Торец пакета: четыре трубы 2×2 под крышками — тёмный круг с кольцом и крестом крышки."""
    rgb = olive() * 0.9
    yy, xx = np.mgrid[0:N, 0:N] + 0.5
    for cy in (4.5, 11.5):
        for cx in (4.5, 11.5):
            r = np.hypot(xx - cx, yy - cy)
            rgb[r < 3.6] = OLIVE * 0.55
            rgb[(r >= 2.6) & (r < 3.6)] = OLIVE * 1.25
            rgb[(r < 2.6) & ((np.abs(xx - cx) < 0.6) | (np.abs(yy - cy) < 0.6))] = OLIVE * 0.75
    return np.clip(rgb, 0, 1)


def sam_top(N=16):
    """Верх: люк с петлями и заклёпки по краю."""
    rgb = olive() * 1.05
    rgb[4:12, 4] *= 0.6
    rgb[4:12, 11] *= 0.6
    rgb[4, 4:12] *= 0.6
    rgb[11, 4:12] *= 0.6
    rgb[7:9, 10] = OLIVE * 0.4
    for k in range(1, 16, 4):
        for y, x in ((1, k), (14, k), (k, 1), (k, 14)):
            rgb[y, x] = OLIVE * 0.6
    return np.clip(rgb, 0, 1)


def sam_radar(N=16):
    """Полотно радара: решётка излучателей (тёмные квадраты в светлой сетке), рамка."""
    base = np.array((70, 78, 66)) / 255
    rgb = np.clip(base[None, None, :] * (1 + 0.08 * (zrng.random((N, N, 1)) - 0.5)), 0, 1)
    for y in range(1, N - 1):
        for x in range(1, N - 1):
            if y % 2 == 1 and x % 2 == 1:
                rgb[y, x] *= 0.55
            else:
                rgb[y, x] *= 1.15
    rgb[0] = rgb[-1] = OLIVE * 0.8
    rgb[:, 0] = rgb[:, -1] = OLIVE * 0.8
    return np.clip(rgb, 0, 1)


# Зенитная ракета: светлый корпус наискосок, тёмная головка, жёлтая полоса, крестовые рули, сопло
INTERCEPTOR = [
    "................",
    ".............DD.",
    "............DDD.",
    "...........WWD..",
    "..........WWW...",
    ".........YWW....",
    "....F...YYW.....",
    "...FF..WWY......",
    "....FFWWW.......",
    ".....WWW........",
    "....WWWF........",
    "...WWW.FF.......",
    "..BWW...F.......",
    ".OBB............",
    "OOO.............",
    ".O..............",
]
INTERCEPTOR_PALETTE = {
    ".": (0, 0, 0, 0),
    "W": (214, 216, 208, 255),   # корпус
    "D": (60, 62, 60, 255),      # обтекатель головки
    "Y": (214, 178, 38, 255),    # полоса боевой части
    "F": (150, 152, 146, 255),   # рули и стабилизаторы
    "B": (90, 88, 84, 255),      # сопло
    "O": (255, 150, 50, 255),    # пламя
}


def paint_with(rows, palette, name):
    img = Image.new("RGBA", (len(rows), len(rows)))
    for y, row in enumerate(rows):
        assert len(row) == len(rows), (name, y)
        for x, c in enumerate(row):
            img.putpixel((x, y), palette[c])
    save(img, name)


def sam():
    for name, f in (("base", sam_base), ("side", sam_side), ("tubes", sam_tubes), ("top", sam_top), ("radar", sam_radar)):
        rgb = f()
        save(rgba(rgb, np.ones(rgb.shape[:2])), f"block/sam_{name}")
    paint_with(INTERCEPTOR, INTERCEPTOR_PALETTE, "item/interceptor")


if __name__ == "__main__":
    paint(DESIGNATOR, "item/strike_designator")
    paint(GEIGER, "item/geiger_counter")
    for name, rows in MUNITIONS.items():
        paint(rows, "item/" + name)
    save(trefoil(), "mob_effect/radiation_sickness")
    paint(BURNS_ICON, "mob_effect/burns")
    save(trinitite(), "block/trinitite")
    atlas = Image.new("RGBA", (256, 128))
    for i in range(8):
        atlas.paste(puff(), ((i % 4) * 64, (i // 4) * 64))
    save(atlas, "nuke/puffs")
    save(plasma(), "nuke/plasma")
    save(rain_sheet(), "nuke/rain")
    save(flare(), "nuke/flare")
    substation()
    sam()
    print("ok")
