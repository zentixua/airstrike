#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
#     "scipy>=1.11",
#     "soundfile>=0.12",
# ]
# ///
"""Монтаж трейлера из записи tools/trailer/record.sh: планы (кадры 60 fps по времени игры) режутся и склеиваются
по монтажному листу EDIT под такт музыки, цвет и кинокаше, титры; звук собирается заново из журнала звуков игры
(те же файлы мода, громкость по расстоянию до камеры, панорама, Доплер из журнала) и кладётся под музыку.

    uv run tools/trailer/edit.py [--lang en|ru] [--draft] [--rec ПАПКА …] [--out ФАЙЛ]
    --lang   — слова на экране: en (по умолчанию, Modrinth и YouTube) или ru
    --draft  — быстрый черновик 960×540 (проверить монтаж)
    --rec    — папка записи; несколько — планы из следующих (пересъёмка) заменяют одноимённые; план с текстом
               игры в кадре берётся из записи на языке ролика (en_us или ru_ru, record.sh: AIRSTRIKE_LANG)
    --jobs   — процессов отрисовки (по умолчанию min(ядер, 6))
    результат: dist/airstrike-trailer.mp4 (H.264, 60 fps, AAC), …-lite.mp4 (до 4 Мбит/с, для мессенджеров)
    и …-credits.txt — строки об авторах музыки и звуков для описания ролика

Музыка и шрифты скачиваются один раз в tools/.trailer-cache (проверка sha256). Пакеты Python — в блоке script
выше (PEP 723, uv run ставит их сам); ffmpeg — системный или из пакета imageio-ffmpeg.

Память на 1080p (замер): процесс отрисовки ~0.3 ГБ и главный ~0.3 ГБ — ровно весь монтаж (в очереди не больше
2×jobs кадров), кодер x264 slow 2.5 ГБ на 4 ядрах и больше с числом потоков; при --jobs 6 пик ~5–6 ГБ.
"""
import argparse
import bisect
import collections
import hashlib
import json
import math
import multiprocessing
import os
import shutil
import subprocess
import sys
import urllib.request
from dataclasses import dataclass, field

import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy.signal import butter, resample_poly, sosfilt

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from paths import DIST, MOD, ROOT  # noqa: E402

REC = os.path.join(MOD, "run", "scenario", "trailer")
CACHE = os.path.join(ROOT, "tools", ".trailer-cache")
MOD_ASSETS = os.path.join(MOD, "src", "main", "resources", "assets")
FPS = 60
SR = 48000
TPS = 20

# ---------------------------------------------------------------- музыка и шрифты (лицензии — в титрах и CREDITS)

MUSIC = {
    "url": "https://archive.org/download/Kevin-MacLeod_Impact_2014_FullAlbum/Impact%2FKevin%20MacLeod%20-%2006%20-%20Prelude.mp3",
    "sha256": "fe69ca096bbf6b2c3fe7db94049dbf786f3d9c2bdfd3a3d62ed85c1463baacef",
    "file": "impact-prelude.mp3",
    # сильная доля громкой части: 96.02 с, такт — 3 с (80 уд/мин, 4 доли по 0.75 с)
    "drop": 96.02,
    "bar": 3.0,
    "credit": "\u201cImpact Prelude\u201d by Kevin MacLeod (incompetech.com), CC BY 4.0",
}
FONTS = {
    "title": ("https://raw.githubusercontent.com/google/fonts/main/ofl/russoone/RussoOne-Regular.ttf",
              "bc0abcc660bd8b7ad3000ecb2898a27c58a29a50f7ec81652fa12e75148d09df", "RussoOne-Regular.ttf"),
    "text": ("https://raw.githubusercontent.com/google/fonts/main/ofl/oswald/Oswald%5Bwght%5D.ttf",
             "5b38c246e255a12f5712d640d56bcced0472466fc68983d2d0410ec0457c2817", "Oswald.ttf"),
}


def fetch(url, sha256, name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        print(f"скачиваю {name}")
        req = urllib.request.Request(url, headers={"User-Agent": "zentixua/airstrike trailer"})
        with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(path + ".part", path)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if digest != sha256:
        raise SystemExit(f"{name}: sha256 {digest} не совпадает — удалите файл из {CACHE}")
    return path


def ffmpeg():
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        raise SystemExit("нужен ffmpeg (apt install ffmpeg или pip install imageio-ffmpeg)")


# ---------------------------------------------------------------- запись

@dataclass
class Shot:
    name: str
    speed: float
    frames: dict = field(default_factory=dict)      # k → путь к PNG
    cams: dict = field(default_factory=dict)        # k → (x, y, z, yaw, pitch)
    sounds: list = field(default_factory=list)
    updates: dict = field(default_factory=dict)     # id → [(t, x, y, z, volume, pitch)]
    stops: dict = field(default_factory=dict)       # id → t
    marks: list = field(default_factory=list)       # (t, что)
    count: int = 0
    hud: bool = False       # в кадре текст интерфейса игры
    lang: str = "ru_ru"     # язык игры при съёмке (записи до этого поля — только ru_ru)

    def sec(self, ticks):
        """Время плана в секундах видео по тикам игры."""
        return ticks / (TPS * self.speed)

    def frame(self, k):
        """Кадр k или ближайший снятый до него (если клиент пропустил)."""
        keys = self.keys
        i = bisect.bisect_right(keys, k) - 1
        return self.frames[keys[max(i, 0)]]

    def cam(self, k):
        keys = self.keys
        i = min(max(bisect.bisect_right(keys, k) - 1, 0), len(keys) - 1)
        return self.cams[keys[i]]

    @property
    def duration(self):
        return self.count / FPS


def load_recording(rec=REC):
    shots = {}
    with open(os.path.join(rec, "timeline.jsonl"), encoding="utf-8") as f:
        for line in f:
            try:
                e = json.loads(line)
            except json.JSONDecodeError:  # запись оборвалась на строке
                continue
            kind, name = e["type"], e["shot"]
            if kind == "shot":
                shots[name] = Shot(name, e["speed"], hud=e.get("hud", False), lang=e.get("lang", "ru_ru"))
                continue
            s = shots[name]
            if kind == "frame":
                path = os.path.join(rec, "frames", name, f"{e['k']:05d}.png")
                if os.path.exists(path):
                    s.frames[e["k"]] = path
                    s.cams[e["k"]] = tuple(e["cam"])
                s.count = max(s.count, e["k"] + 1)
            elif kind == "sound":
                s.sounds.append(e)
            elif kind == "update":
                s.updates.setdefault(e["id"], []).append((e["t"], *e["pos"], e["volume"], e["pitch"], *e.get("lp", (1, 1))))
            elif kind == "stop":
                s.stops[e["id"]] = e["t"]
            elif kind == "mark":
                # «gone» в самом начале — в записях до исправления снаряды, пропавшие между планами
                if not (e["what"].startswith("gone:") and e["t"] <= 0):
                    s.marks.append((e["t"], e["what"]))
    for s in shots.values():
        s.keys = sorted(s.frames)
    return {n: s for n, s in shots.items() if s.frames}


def pick_takes(recordings, lang):
    """Дубль каждого плана из записей (--rec по порядку): последний; план с текстом игры в кадре — последний
    на языке ролика (en_us/ru_ru), а если такого нет — последний какой есть, с предупреждением."""
    takes = {}
    for rec in recordings:
        for name, s in rec.items():
            takes.setdefault(name, []).append(s)
    shots = {}
    for name, cands in takes.items():
        own = [s for s in cands if s.lang == lang]
        if any(s.hud for s in cands):
            if not own:
                print(f"  ! {name}: нет дубля на {lang}, текст в кадре — {cands[-1].lang}")
            shots[name] = (own or cands)[-1]
        else:
            shots[name] = cands[-1]
    return shots


def anchor(shot, spec):
    """Момент плана в секундах: число, «end», «mark:что» или «sound:часть имени» (#n — n-е совпадение, #-1 —
    последнее) с ±сдвигом."""
    if isinstance(spec, (int, float)):
        return float(spec)
    off = 0.0
    for sign in "+-":
        if sign in spec[1:]:
            head, tail = spec.rsplit(sign, 1)
            try:
                off = float(tail) * (1 if sign == "+" else -1)
                spec = head
                break
            except ValueError:
                pass
    if spec == "end":
        return shot.duration + off
    kind, what = spec.split(":", 1)
    nth = 1
    if "#" in what:  # «sound:blast#3» — третье совпадение
        what, n = what.rsplit("#", 1)
        nth = int(n)
    hits = []
    if kind == "mark":
        hits = [t for t, m in shot.marks if what in m]
    elif kind == "sound":
        hits = [e["t"] for e in shot.sounds if what in e["event"]]
    if len(hits) >= abs(nth):
        return shot.sec(hits[nth - 1 if nth > 0 else nth]) + off
    print(f"  ! {shot.name}: нет «{spec}», беру начало плана")
    return off


# ---------------------------------------------------------------- монтажный лист

@dataclass
class Clip:
    shot: str
    at: object          # откуда в плане (секунды или якорь, см. anchor)
    dur: float          # длительность в трейлере, с
    rate: float = 1.0   # скорость воспроизведения (2 — вдвое быстрее)
    xfade: float = 0.0  # наплыв из прошлого плана, с
    flash: bool = False  # белая вспышка на склейке
    zoom: tuple = (1.0, 1.0)  # наезд: масштаб в начале и в конце
    sfx: float = 1.0    # громкость звука игры
    frame_y: float = 0.5  # какая полоса плана видна в окне 2:1: 0 — верхняя (список ударов справа вверху), 0.5 — середина
    start: float = 0.0  # заполняется: начало в трейлере
    src: float = 0.0    # заполняется: начало в плане, с


@dataclass
class Black:
    dur: float
    start: float = 0.0


@dataclass
class Text:
    at: float           # от начала трейлера, с
    dur: float
    lines: tuple
    style: str = "card"  # card — по центру крупно; caption — подпись слева внизу; title — логотип
    fade: float = 0.35


@dataclass
class Hit:
    """Звук трейлера поверх: удар (boom), нарастание (riser), свист склейки (whoosh), тишина музыки (mute)."""
    at: float
    kind: str
    dur: float = 1.0
    gain: float = 1.0


BAR = MUSIC["bar"]

# для описания ролика (Modrinth, YouTube): музыка CC BY требует указать автора, название, источник и лицензию
CREDITS = """Music: "Impact Prelude" by Kevin MacLeod (incompetech.com)
Licensed under Creative Commons: By Attribution 4.0 License
http://creativecommons.org/licenses/by/4.0/
Sound effects: Freesound contributors (CC0 / CC BY 4.0), full list: https://github.com/zentixua/airstrike/blob/main/SOUND-CREDITS.md
Fonts: Russo One, Oswald (SIL Open Font License)

Музыка: «Impact Prelude», Kevin MacLeod (incompetech.com), лицензия Creative Commons Attribution 4.0
http://creativecommons.org/licenses/by/4.0/
Звуки: авторы Freesound (CC0 / CC BY 4.0), список: https://github.com/zentixua/airstrike/blob/main/SOUND-CREDITS.md
"""

# слова на экране: английские — для Modrinth и YouTube, русские — для своих (--lang ru)
GAME_LANG = {"en": "en_us", "ru": "ru_ru"}  # язык игры для планов с текстом интерфейса в кадре (record.sh: AIRSTRIKE_LANG)
WORDS = {
    "en": {"presents": "presents", "aim": "AIM", "launch": "LAUNCH", "shahed": "SHAHED-136", "missile": "CRUISE MISSILE",
           "lancet": "LANCET", "grad": "GRAD MLRS", "last": "LAST RESORT", "icbm": "ICBM",
           "platform": "Minecraft 1.21.1 · NeoForge · Create Aeronautics",
           "music": "Music: ", "sounds": "Sound effects: Freesound contributors (CC0 / CC BY), see SOUND-CREDITS.md"},
    "ru": {"presents": "представляет", "aim": "НАВЕДИ", "launch": "ЗАПУСТИ", "shahed": "ШАХЕД-136", "missile": "КРЫЛАТАЯ РАКЕТА",
           "lancet": "«ЛАНЦЕТ»", "grad": "«ГРАД»", "last": "ПОСЛЕДНИЙ ДОВОД", "icbm": "МБР",
           "platform": "Minecraft 1.21.1 · NeoForge · Create Aeronautics",
           "music": "Музыка: ", "sounds": "Звуки: авторы Freesound (CC0 / CC BY), список в SOUND-CREDITS.md"},
}


def build_edit(lang="en"):
    """
    Трейлер ~2:36 под «Impact Prelude». Тихая часть — рассвет, наводчик, пусковая; сильная доля музыки (drop) —
    поджиг первого шахеда; дальше склейки по тактам (3 с) и полутактам, нарастание к МБР. Ядерная вспышка —
    на последний удар музыки (192 с трека), дальше музыки нет: гул взрыва, гриб, чёрный дождь, логотип.
    """
    D = 30.0                                   # drop в трейлере
    FLASH = D + (192.0 - MUSIC["drop"]) + 0.02  # вспышка = последний удар трека (32 такта после drop)
    edit = [
        Black(4.0),
        Clip("dawn", 1.2, 8.5, xfade=0.8),
        Clip("remote", 0.3, 3.0, xfade=0.4),
        Clip("operator", 0.3, 5.5, xfade=0.3),
        Clip("scope", 0.0, 4.0, xfade=0.2),
        Clip("launch_drone", "sound:launch.booster-5.0", 5.0),
        # drop: поджиг и сход шахеда
        Clip("launch_drone", "sound:launch.booster-0.15", 3.0, flash=True),
        Clip("boost", 0.0, 4.5),
        Clip("cruise", 1.0, 3.0),
        # глазами наводчика: шахеды проходят над головой к целям, метки целей с номерами и пунктиры
        Clip("targets", 0.0, 3.0, frame_y=0.0),
        Clip("impact_drone", "mark:gone#-1-5.0", 6.0),
        Clip("launch_missile", "sound:launch.booster-0.4", 4.5, flash=True),
        # камера V: карта оператора, пока ракета дальше прорисовки, сама переходит на видео с борта — горка, пике
        Clip("missile_camera", "mark:video-1.5", 1.5, frame_y=0.0),  # карта: окно 2:1 по верху, строки целиком
        # видео с борта — последние 150 блоков до взрыва: дальше атмосферный туман шейдерпака заливает кадр белым
        Clip("missile_camera", "mark:close", 3.0),
        Clip("impact_missile", "mark:gone-1.5", 6.0),
        # «Ланцет»: рывок с катапульты, круг над целью, пике
        Clip("loiter_launch", "sound:loiter.launch-0.5", 3.0, flash=True),
        Clip("loiter_strike", "mark:gone-3.0", 4.5),
        Clip("rocket_launch", "sound:rocket.launch-1.5", 4.5, flash=True),
        Clip("rocket_impact", "sound:blast-1.0", 4.5),
        # B-2: под брюхом — створки, бомба уходит, вираж; с земли — подземный взрыв
        Clip("bomb_bay", "mark:release-2.5", 4.5, flash=True),
        Clip("bomber", "mark:gone-0.2", 4.5),
        Clip("salvo", "mark:gone-2.5", 6.0, flash=True),
        Clip("salvo_missiles", "mark:gone-1.5", 6.0),
        # нарезка разрывов по полутактам
        Clip("impact_drone", "mark:gone#-2+0.3", 1.5, rate=0.8),
        Clip("rocket_impact", "sound:blast#6-0.2", 1.5),
        Clip("salvo", "mark:gone#4-0.3", 1.5),
        Black(4.5),
        Clip("icbm", 0.0, 5.0, flash=True),
        # из-за плеча наводчика: отсчёт, тревога, вспышка, шар, фронт доходит до вышки
        Clip("nuke", "mark:detonation-10.0", 16.0, frame_y=0.0),
        Clip("mushroom", 0.0, 9.0, rate=1.5, xfade=0.6),
        Clip("fallout", 0.5, 6.0, xfade=1.0),
        Black(9.0),
    ]
    t = 0.0
    for c in edit:
        c.start = t
        t += c.dur
    total = t
    nuke = next(c for c in edit if isinstance(c, Clip) and c.shot == "nuke")
    assert abs(nuke.start + 10.0 - FLASH) < 0.05, "вспышка в плане nuke должна прийтись на последний удар музыки"
    def start(shot, n=1):
        """Начало n-го плана с этим именем в трейлере (подписи — к своим планам, а не к числам)."""
        return [c.start for c in edit if getattr(c, "shot", None) == shot][n - 1]

    card = next(c for c in edit if isinstance(c, Black) and c.start > D)  # чёрный кадр перед МБР
    L = WORDS[lang]
    texts = [
        Text(0.6, 3.0, ("ZENTIX UA", L["presents"]), "card"),
        Text(start("scope") + 0.5, 2.2, (L["aim"],), "word"),
        Text(D - 2.4, 1.9, (L["launch"],), "word"),
        # оружие — одним словом, картинка скажет остальное
        Text(start("boost") + 0.3, 2.6, (L["shahed"],), "caption"),
        Text(start("launch_missile") + 0.5, 2.6, (L["missile"],), "caption"),
        Text(start("loiter_launch") + 0.3, 2.6, (L["lancet"],), "caption"),
        Text(start("rocket_launch") + 0.3, 2.6, (L["grad"],), "caption"),
        Text(start("bomb_bay") + 0.4, 2.6, ("B-2 SPIRIT",), "caption"),
        Text(card.start + 0.3, 3.6, (L["last"],), "word"),
        Text(start("icbm") + 0.5, 2.6, (L["icbm"],), "caption"),
        Text(total - 9.0 + 0.6, 5.2, ("AIRSTRIKE",), "title"),
        Text(total - 9.0 + 2.0, 3.8, (L["platform"], "github.com/zentixua/airstrike"), "sub"),
        Text(total - 3.0, 3.0, (L["music"] + MUSIC["credit"], L["sounds"]), "credits"),
    ]
    hits = [
        # удар на каждой склейке со вспышкой, свист — на склейках нарезки
        *[Hit(c.start, "boom", 2.0, 0.45) for c in edit if isinstance(c, Clip) and c.flash and c.start > D + 1],
        *[Hit(c.start - 0.25, "whoosh", 0.5, 0.5) for c in edit if isinstance(c, Clip) and c.dur <= 1.5],
        Hit(D - 4.0, "riser", 4.0, 0.8),
        Hit(D, "boom", 3.0, 1.0),
        Hit(card.start, "boom", 3.0, 0.9),
        Hit(FLASH - 5.0, "riser", 5.0, 0.7),
        Hit(total - 9.0 + 0.6, "boom", 5.0, 1.2),
    ]
    return edit, texts, hits, total, D, FLASH


# ---------------------------------------------------------------- картинка

class Look:
    """Цвет: мягкая «киношная» кривая, тёплые света и холодные тени, виньетка, кинокаше 2:1."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        x = np.linspace(0, 1, 256)
        curve = x + 0.18 * np.sin(2 * np.pi * (x - 0.5)) * x * (1 - x) * 2  # S-кривая
        curve = np.clip(curve * 1.02, 0, 1)
        lut = np.stack([np.clip(curve * 1.04 + 0.01 * (1 - x), 0, 1),
                        np.clip(curve * 1.0, 0, 1),
                        np.clip(curve * 0.94 + 0.04 * (1 - x), 0, 1)], axis=1)
        self.lut = (lut * 255).astype(np.uint8)
        yy, xx = np.mgrid[0:h, 0:w]
        r = np.sqrt(((xx - w / 2) / (w / 2)) ** 2 + ((yy - h / 2) / (h / 2)) ** 2)
        self.vignette = np.clip(1.0 - 0.28 * np.clip(r - 0.55, 0, None) ** 1.6, 0, 1)[..., None].astype(np.float32)
        self.bar = int(round((h - w / 2.0) / 2))

    def apply(self, img):
        a = np.asarray(img, dtype=np.uint8)
        a = np.stack([self.lut[a[..., i], i] for i in range(3)], axis=-1).astype(np.float32)
        grey = a.mean(axis=2, keepdims=True)
        a = grey + (a - grey) * 1.12
        a *= self.vignette
        a = np.clip(a, 0, 255)
        if self.bar > 0:
            a[: self.bar] = 0
            a[-self.bar:] = 0
        return a


class Titles:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.fonts = {k: fetch(*v) for k, v in FONTS.items()}
        self.cache = {}

    def font(self, kind, size, weight=None):
        f = ImageFont.truetype(self.fonts[kind], size)
        if weight is not None:
            try:
                f.set_variation_by_axes([weight])
            except (OSError, AttributeError):
                pass
        return f

    def render(self, text):
        """RGBA-картинка титра во весь кадр (кэш)."""
        key = (text.lines, text.style)
        if key in self.cache:
            return self.cache[key]
        w, h = self.w, self.h
        s = h / 1080
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        if text.style in ("card", "word", "title", "sub"):
            sizes = {"card": [int(64 * s), int(40 * s)], "word": [int(150 * s)], "title": [int(190 * s)], "sub": [int(38 * s), int(30 * s)]}[text.style]
            kinds = {"card": ["title", "text"], "word": ["title"], "title": ["title"], "sub": ["text", "text"]}[text.style]
            lines = [(ln, self.font(kinds[min(i, len(kinds) - 1)], sizes[min(i, len(sizes) - 1)], 500)) for i, ln in enumerate(text.lines)]
            heights = [d.textbbox((0, 0), ln, font=f)[3] - d.textbbox((0, 0), ln, font=f)[1] for ln, f in lines]
            gap = int(22 * s)
            y = (h - sum(heights) - gap * (len(lines) - 1)) / 2
            if text.style == "sub":
                y += int(170 * s)
            for (ln, f), hh in zip(lines, heights):
                bb = d.textbbox((0, 0), ln, font=f)
                x = (w - (bb[2] - bb[0])) / 2 - bb[0]
                self._glow_text(layer, (x, y - bb[1]), ln, f, text.style)
                y += hh + gap
        elif text.style == "caption":
            f1, f2 = self.font("title", int(56 * s)), self.font("text", int(30 * s), 400)
            x, y = int(96 * s), h - int(250 * s)
            d.rectangle([x - int(22 * s), y + int(4 * s), x - int(14 * s), y + int(118 * s)], fill=(255, 150, 40, 255))
            self._glow_text(layer, (x, y), text.lines[0], f1, "caption")
            if len(text.lines) > 1:
                d.text((x + 2, y + int(76 * s)), text.lines[1].upper(), font=f2, fill=(235, 235, 235, 235))
        elif text.style == "credits":
            f = self.font("text", int(24 * s), 400)
            y = h - int(150 * s)
            for ln in text.lines:
                bb = d.textbbox((0, 0), ln, font=f)
                d.text(((w - (bb[2] - bb[0])) / 2, y), ln, font=f, fill=(200, 200, 200, 230))
                y += int(36 * s)
        self.cache[key] = layer
        return layer

    def _glow_text(self, layer, xy, line, font, style):
        glow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        warm = style in ("title", "word")
        ImageDraw.Draw(glow).text(xy, line, font=font, fill=(255, 120, 30, 200) if warm else (0, 0, 0, 200))
        glow = glow.filter(ImageFilter.GaussianBlur(radius=max(4, font.size // 9)))
        layer.alpha_composite(glow)
        ImageDraw.Draw(layer).text(xy, line, font=font, fill=(255, 244, 228, 255) if warm else (255, 255, 255, 255))


# состояние рабочих процессов отрисовки
_W = {}


def _init_worker(edit, texts, shots, size):
    _W.update(edit=edit, texts=texts, shots=shots, size=size, look=Look(*size), titles=Titles(*size))


def _clip_at(t):
    for c in reversed(_W["edit"]):
        if c.start <= t + 1e-9:
            return c
    return _W["edit"][0]


def _source(c, t):
    """Кадр плана под время трейлера t (с наездом), float32 HxWx3."""
    w, h = _W["size"]
    if isinstance(c, Black):
        return np.zeros((h, w, 3), np.float32)
    s = _W["shots"][c.shot]
    k = int(round((c.src + (t - c.start) * c.rate) * FPS))
    img = Image.open(s.frame(max(0, min(k, s.count - 1)))).convert("RGB")
    if img.size != (w, h):
        img = img.resize((w, h), Image.LANCZOS)
    z0, z1 = c.zoom
    z = z0 + (z1 - z0) * min(1.0, max(0.0, (t - c.start) / c.dur))
    if abs(z - 1) > 1e-3:
        cw, ch = w / z, h / z
        img = img.resize((w, h), Image.BICUBIC, box=((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2))
    bar = _W["look"].bar
    if bar > 0 and c.frame_y != 0.5:
        # окно 2:1 сдвигается по плану, чтобы кинокаше не срезало интерфейс у края
        top = int(round(2 * bar * c.frame_y))
        window = img.crop((0, top, w, top + h - 2 * bar))
        img = Image.new("RGB", (w, h))
        img.paste(window, (0, bar))
    return _W["look"].apply(img)


def _render(i):
    t = i / FPS
    c = _clip_at(t)
    a = _source(c, t)
    if isinstance(c, Clip):
        idx = _W["edit"].index(c)
        if c.xfade > 0 and t - c.start < c.xfade and idx > 0:
            p = (t - c.start) / c.xfade
            a = a * p + _source(_W["edit"][idx - 1], t) * (1 - p)
        if c.flash and t - c.start < 0.35:
            a = a + (255 - a) * (1 - (t - c.start) / 0.35) ** 2
    for tx in _W["texts"]:
        if tx.at <= t < tx.at + tx.dur:
            fade = min(1.0, (t - tx.at) / tx.fade, (tx.at + tx.dur - t) / tx.fade)
            layer = np.asarray(_W["titles"].render(tx), dtype=np.float32)
            alpha = layer[..., 3:4] / 255 * fade
            a = a * (1 - alpha) + layer[..., :3] * alpha
    return np.clip(a, 0, 255).astype(np.uint8).tobytes()


def render_frames(n, jobs, init):
    """Кадры трейлера по порядку. В работе и в очереди — не больше 2×jobs кадров: Pool.imap не ждёт потребителя
    и копит готовые кадры (6 МБ на 1080p), пока кодер x264 медленнее отрисовки, — 16 процессов съедали больше 8 ГБ."""
    with multiprocessing.Pool(jobs, _init_worker, init) as pool:
        pending = collections.deque()
        for i in range(n):
            pending.append(pool.apply_async(_render, (i,)))
            if len(pending) >= 2 * jobs:
                yield pending.popleft().get()
        while pending:
            yield pending.popleft().get()


# ---------------------------------------------------------------- звук

class SoundBank:
    def __init__(self):
        props = os.path.join(MOD, "build", "moddev", "minecraft_assets.properties")
        conf = dict(line.strip().split("=", 1) for line in open(props) if "=" in line and not line.startswith("#"))
        root = conf["assets_root"]
        index = json.load(open(os.path.join(root, "indexes", conf["asset_index"] + ".json")))["objects"]
        self.vanilla = {k: os.path.join(root, "objects", v["hash"][:2], v["hash"]) for k, v in index.items()}
        self.cache = {}

    def load(self, loc):
        """Звук по пути файла из журнала («namespace:sounds/….ogg») → моно float32 при SR."""
        if loc in self.cache:
            return self.cache[loc]
        ns, path = loc.split(":", 1)
        f = os.path.join(MOD_ASSETS, ns, path)
        if not os.path.exists(f):
            f = self.vanilla.get(f"{ns}/{path}")
        if f is None or not os.path.exists(f):
            self.cache[loc] = None
            return None
        x, sr = sf.read(f, dtype="float32", always_2d=True)
        x = x.mean(axis=1)
        if sr != SR:
            g = math.gcd(sr, SR)
            x = resample_poly(x, SR // g, sr // g).astype(np.float32)
        self.cache[loc] = x
        return x


def _gain_pan(cam, pos, volume, rng, relative, linear):
    """Громкость и панорама как у движка: линейное затухание до range; без затухания (моторы мода, удар волны) —
    громкость как есть, но направление честное."""
    x, y, z, yaw, _ = cam
    if relative:
        return min(volume, 1.0), 0.0, 0.0
    d = np.array(pos) - np.array([x, y, z])
    dist = float(np.linalg.norm(d))
    g = min(volume, 1.0) * (max(0.0, 1.0 - dist / max(rng, 1e-3)) if linear else 1.0)
    yr = math.radians(yaw)
    right = np.array([-math.cos(yr), 0.0, -math.sin(yr)])
    pan = float(np.dot(d, right) / dist) if dist > 1e-3 else 0.0
    return g, pan, dist


# фон мира, который в трейлере только мешает (пещерный гул, спавнер испытаний под землёй)
SKIP_SOUNDS = ("minecraft:ambient.", "minecraft:block.trial_spawner", "minecraft:music")


def render_sfx(edit, shots, total, bank):
    """Звук игры по монтажу: каждый звук плана, попавший в склейку, — с громкостью и панорамой по камере."""
    out = np.zeros((int(total * SR) + SR, 2), np.float32)
    for c in edit:
        if not isinstance(c, Clip) or c.sfx <= 0:
            continue
        s = shots[c.shot]
        lo, hi = c.src, c.src + c.dur * c.rate
        slow = s.speed if s.speed < 1 else 1.0
        stretch = slow ** -0.35  # замедленный план — звук ниже и длиннее, как в кино
        for e in s.sounds:
            if e["event"].startswith(SKIP_SOUNDS):
                continue
            data = bank.load(e["file"])
            if data is None:
                continue
            t0 = s.sec(e["t"])
            stop = s.sec(s.stops[e["id"]]) if e["id"] in s.stops else None
            base_pitch = max(0.5, min(2.0, e["pitch"])) / stretch
            length = (stop - t0) if stop is not None else len(data) / SR / base_pitch * (1 if not e["loop"] else 1e9)
            if t0 + length < lo - 0.05 or t0 > hi:
                continue
            seg_start = max(t0, lo)
            seg_end = min(hi, t0 + length)
            if seg_end <= seg_start:
                continue
            n = int((seg_end - seg_start) / c.rate * SR)
            if n <= 0:
                continue
            ts = seg_start + np.arange(n) / SR * c.rate          # время плана для каждого сэмпла
            # громкость, тон, место — по кадрам (движок пишет их для меняющихся звуков)
            ups = s.updates.get(e["id"], [])
            ut = np.array([s.sec(u[0]) for u in ups]) if ups else None
            kf = np.arange(int(seg_start * FPS), int(seg_end * FPS) + 2)
            gains, pans, pitches, dists, highs = [], [], [], [], []
            for k in kf:
                tk = k / FPS
                if ut is not None and len(ut):
                    j = min(max(np.searchsorted(ut, tk) - 1, 0), len(ups) - 1)
                    _, px, py, pz, vol, pit, lpg, lph = ups[j]
                    pos, pitch = (px, py, pz), pit
                else:
                    pos, vol, pitch = e["pos"], e["volume"], e["pitch"]
                    lpg, lph = e.get("lp", (1, 1))
                g, p, dist = _gain_pan(s.cam(k), pos, vol, e["range"], e["relative"], e["linear"])
                gains.append(g * lpg)
                highs.append(lph)
                pans.append(p)
                pitches.append(max(0.5, min(2.0, pitch)) / stretch)
                dists.append(dist)
            ft = kf / FPS
            g = np.interp(ts, ft, gains)
            pan = np.interp(ts, ft, pans) * 0.75
            pr = np.interp(ts, ft, pitches)
            # позиция в файле: тон — скорость чтения; звук идёт в своём темпе, даже если план ускорен
            pos_samples = (seg_start - t0) * SR * base_pitch + np.cumsum(pr) - pr[0]
            if e["loop"]:
                pos_samples = np.mod(pos_samples, len(data) - 1)
            valid = pos_samples < len(data) - 1
            y = np.interp(np.where(valid, pos_samples, 0), np.arange(len(data)), data) * valid
            dist = float(np.median(dists)) if dists else 0.0
            hf = float(np.median(highs)) if highs else 1.0
            if hf < 0.98:  # фильтр мода (воздух, холм или дом между): доля верхов → срез
                y = sosfilt(butter(2, max(500.0, 20000.0 * hf ** 1.6), "low", fs=SR, output="sos"), y)
            elif dist > 40 and not e["event"].startswith("airstrike:"):  # ванильные звуки издалека — воздух глушит верха
                cutoff = max(900.0, 16000.0 / (1 + dist / 120))
                y = sosfilt(butter(2, cutoff, "low", fs=SR, output="sos"), y)
            y = y * g * c.sfx
            # склейка: короткие подъём и спад, чтобы не щёлкало
            ramp = min(n, int(0.012 * SR))
            if ramp > 1:
                y[:ramp] *= np.linspace(0, 1, ramp)
                y[-ramp:] *= np.linspace(1, 0, ramp)
            left = np.sqrt(np.clip(0.5 - pan / 2, 0, 1))
            right = np.sqrt(np.clip(0.5 + pan / 2, 0, 1))
            i0 = int((c.start + (seg_start - lo) / c.rate) * SR)
            out[i0:i0 + n, 0] += y * left
            out[i0:i0 + n, 1] += y * right
    return out


def synth_hit(kind, dur, rng):
    """Звуки монтажа: низкий удар (boom), нарастающий шум (riser), свист (whoosh)."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    if kind == "boom":
        f = 55 * np.exp(-t * 1.6) + 28
        body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 1.3)
        noise = sosfilt(butter(2, 180, "low", fs=SR, output="sos"), rng.standard_normal(n)) * np.exp(-t * 3) * 0.6
        y = np.tanh((body + noise) * 1.8)
        return np.stack([y, y], 1)
    if kind == "riser":
        noise = rng.standard_normal(n)
        out = np.zeros(n)
        for a, b in zip(range(0, n, 2048), range(2048, n + 2048, 2048)):
            fc = 300 + 6000 * (a / n) ** 2
            seg = sosfilt(butter(2, [fc, min(fc * 2.5, SR / 2 - 100)], "band", fs=SR, output="sos"), noise[a:b])
            out[a:b] = seg
        env = (t / dur) ** 2.2
        y = out * env * 0.8
        return np.stack([y * 0.9, y], 1)
    if kind == "whoosh":
        noise = sosfilt(butter(2, [400, 3000], "band", fs=SR, output="sos"), rng.standard_normal(n))
        env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 2
        y = noise * env * 0.6
        return np.stack([y, y], 1)
    raise ValueError(kind)


def music_track(total, drop_at):
    """Музыка: сильная доля трека совпадает с drop_at; в начале — наплыв, в конце — спад."""
    path = fetch(MUSIC["url"], MUSIC["sha256"], MUSIC["file"])
    wav = os.path.join(CACHE, "impact-prelude.wav")
    if not os.path.exists(wav):
        subprocess.run([ffmpeg(), "-loglevel", "error", "-y", "-i", path, "-ar", str(SR), "-ac", "2", wav], check=True)
    x, _ = sf.read(wav, dtype="float32", always_2d=True)
    offset = MUSIC["drop"] - drop_at            # время трека в начале трейлера
    out = np.zeros((int(total * SR) + SR, 2), np.float32)
    a = int(max(0, offset) * SR)
    b = int(-min(0, offset) * SR)
    n = min(len(x) - a, len(out) - b)
    out[b:b + n] = x[a:a + n]
    fade = int(3.0 * SR)
    out[b:b + fade] *= np.linspace(0, 1, fade)[:, None]
    return out


def mix(edit, texts, hits, shots, total, drop_at, flash):
    bank = SoundBank()
    sfx = render_sfx(edit, shots, total, bank)
    music = music_track(total, drop_at)
    rng = np.random.default_rng(20260928)
    extra = np.zeros_like(music)
    for h in hits:
        i = int(h.at * SR)
        if h.kind == "mute":
            continue
        y = synth_hit(h.kind, h.dur, rng) * h.gain
        if h.kind == "riser":  # нарастание кончается в момент удара
            i = max(0, i - len(y))
        extra[i:i + len(y)] += y[: len(extra) - i]
    # тишина музыки после ядерной вспышки (последний удар трека уже отзвучал): только гул и ветер игры
    i = int(flash * SR)
    k = int(0.25 * SR)
    music[i:i + k] *= np.linspace(1, 0, k)[:, None]
    music[i + k:] = 0
    out = music * 0.55 + sfx * 0.9 + extra * 0.6
    # мягкий ограничитель
    peak = np.max(np.abs(out)) + 1e-9
    out = np.tanh(out * (1.4 / max(1.0, peak * 0.7))) * 0.95
    return out


# ---------------------------------------------------------------- сборка

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--draft", action="store_true")
    ap.add_argument("--lang", choices=sorted(WORDS), default="en", help="слова на экране: en — Modrinth и YouTube, ru — для своих")
    ap.add_argument("--out", help="файл (по умолчанию dist/airstrike-trailer.mp4, для ru — …-ru.mp4)")
    ap.add_argument("--rec", action="append", help="папка записи (по умолчанию mod/run/scenario/trailer); можно несколько — "
                    "планы из следующих (пересъёмка) заменяют одноимённые")
    ap.add_argument("--preset", default="slow", help="предустановка x264 для чистового (slow — лучше, medium — быстрее)")
    ap.add_argument("--jobs", type=int, default=min(os.cpu_count() or 1, 6),
                    help="процессов отрисовки (по умолчанию не больше 6: каждый ~0.3 ГБ на 1080p)")
    args = ap.parse_args()
    if args.out is None:
        args.out = os.path.join(DIST, "airstrike-trailer.mp4" if args.lang == "en" else f"airstrike-trailer-{args.lang}.mp4")
    shots = pick_takes([load_recording(rec) for rec in args.rec or [REC]], GAME_LANG[args.lang])
    for v in FONTS.values():  # скачать до рабочих процессов
        fetch(*v)
    print("планы:", ", ".join(f"{s.name} {s.duration:.1f}с" for s in shots.values()))
    edit, texts, hits, total, drop_at, flash = build_edit(args.lang)
    missing = {c.shot for c in edit if isinstance(c, Clip) and c.shot not in shots}
    if missing:
        # нет плана — его время отдаётся прошлому плану (он идёт медленнее), без чёрных дыр
        print("  ! нет планов:", ", ".join(sorted(missing)), "— их время у соседних планов")
        kept = []
        for c in edit:
            if isinstance(c, Clip) and c.shot in missing and kept and isinstance(kept[-1], Clip):
                kept[-1].dur += c.dur
            elif not (isinstance(c, Clip) and c.shot in missing):
                kept.append(c)
        edit = kept
    for c in edit:
        if isinstance(c, Clip):
            c.src = max(0.0, anchor(shots[c.shot], c.at))
            # камера снаряда вернулась к игроку («exit») — склейка кончается раньше
            exits = [shots[c.shot].sec(t) for t, m in shots[c.shot].marks if m == "exit"]
            if exits and exits[0] > c.src and c.src + c.dur * c.rate > exits[0] - 0.1:
                c.src = max(0.0, exits[0] - 0.1 - c.dur * c.rate)
            left = shots[c.shot].duration - c.src
            if c.dur * c.rate > left > 0:  # не хватает кадров — медленнее, но до конца плана
                c.rate = left / c.dur
    t = 0.0
    for c in edit:
        c.start = t
        t += c.dur
    first = next(iter(shots.values()))
    w0, h0 = Image.open(first.frame(0)).size
    if w0 * 9 != h0 * 16:
        raise SystemExit(f"кадры {w0}×{h0}, а нужно 16:9 (1920×1080) — переснять: окно клиента было не того размера")
    size = (960, 540) if args.draft else (w0, h0)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    audio = os.path.join(CACHE, "trailer-audio.wav")
    print("звук…")
    sf.write(audio, mix(edit, texts, hits, shots, total, drop_at, flash), SR, subtype="PCM_16")
    print(f"картинка: {int(total * FPS)} кадров {size[0]}×{size[1]}…")
    cmd = [ffmpeg(), "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{size[0]}x{size[1]}", "-r", str(FPS),
           "-i", "-", "-i", audio, "-c:v", "libx264", "-preset", "veryfast" if args.draft else args.preset, "-crf", "23" if args.draft else "15",
           "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-c:a", "aac", "-b:a", "256k", "-shortest", args.out]
    enc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    n = int(total * FPS)
    for i, buf in enumerate(render_frames(n, args.jobs, (edit, texts, shots, size))):
        enc.stdin.write(buf)
        if i % 600 == 0:
            print(f"  {i / FPS:.0f} с")
    enc.stdin.close()
    if enc.wait() != 0:
        raise SystemExit("ffmpeg: кодирование не удалось")
    print("готово:", args.out)
    if not args.draft:
        # лёгкая версия для мессенджеров: тот же монтаж, поток не выше 4 Мбит/с (~80 МБ на 2.5 мин)
        lite = os.path.splitext(args.out)[0] + "-lite.mp4"
        subprocess.run([ffmpeg(), "-loglevel", "error", "-y", "-i", args.out, "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                        "-maxrate", "4M", "-bufsize", "8M", "-pix_fmt", "yuv420p", "-r", str(FPS), "-c:a", "aac", "-b:a", "192k",
                        "-movflags", "+faststart", lite], check=True)
        print("лёгкая версия:", lite)
        credits = os.path.splitext(args.out)[0] + "-credits.txt"
        with open(credits, "w", encoding="utf-8") as f:
            f.write(CREDITS)
        print("строки для описания (Modrinth, YouTube):", credits)


if __name__ == "__main__":
    main()
