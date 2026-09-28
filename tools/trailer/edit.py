#!/usr/bin/env python3
"""Монтаж трейлера из записи tools/trailer/record.sh: планы (кадры 60 fps по времени игры) режутся и склеиваются
по монтажному листу EDIT под такт музыки, цвет и кинокаше, титры; звук собирается заново из журнала звуков игры
(те же файлы мода, громкость по расстоянию до камеры, панорама, Доплер из журнала) и кладётся под музыку.

    python3 tools/trailer/edit.py [--draft] [--out ФАЙЛ]
    --draft  — быстрый черновик 960×540 (проверить монтаж)
    результат: dist/airstrike-trailer.mp4 (H.264, 60 fps, AAC)

Музыка и шрифты скачиваются один раз в tools/.trailer-cache (проверка sha256). Нужны numpy, scipy, soundfile,
Pillow и ffmpeg (системный или из пакета imageio-ffmpeg).
"""
import argparse
import bisect
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
    "credit": "Kevin MacLeod — «Impact Prelude» (incompetech.com), лицензия CC BY 4.0",
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


def load_recording():
    shots = {}
    with open(os.path.join(REC, "timeline.jsonl"), encoding="utf-8") as f:
        for line in f:
            try:
                e = json.loads(line)
            except json.JSONDecodeError:  # запись оборвалась на строке
                continue
            kind, name = e["type"], e["shot"]
            if kind == "shot":
                shots[name] = Shot(name, e["speed"])
                continue
            s = shots[name]
            if kind == "frame":
                path = os.path.join(REC, "frames", name, f"{e['k']:05d}.png")
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
                s.marks.append((e["t"], e["what"]))
    for s in shots.values():
        s.keys = sorted(s.frames)
    return {n: s for n, s in shots.items() if s.frames}


def anchor(shot, spec):
    """Момент плана в секундах: число, «end», «mark:что» или «sound:часть имени» (первое совпадение) с ±сдвигом."""
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
    if kind == "mark":
        for t, m in shot.marks:
            if what in m:
                return shot.sec(t) + off
    elif kind == "sound":
        for e in shot.sounds:
            if what in e["event"]:
                return shot.sec(e["t"]) + off
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


def build_edit():
    """
    Трейлер ~2:30. Сильная доля музыки (drop) — на поджиг первого шахеда; дальше склейки по тактам (3 с) и полутактам.
    Ядерная вспышка — на последнем ударе музыки, дальше — тишина, гул взрыва и титр.
    """
    D = 34.5  # момент drop в трейлере
    edit = [
        Black(1.5),
        Clip("dawn", 0.5, 9.0, xfade=0.0),
        Clip("operator", 0.3, 4.5, xfade=0.5),
        Clip("remote", 0.4, 3.0, xfade=0.3),
        Clip("scope", 0.0, 5.0, xfade=0.2),
        Clip("launch_drone", 0.0, D - 1.5 - 9.0 - 4.5 - 3.0 - 5.0),
        # drop: поджиг и сход (от звука поджига)
        Clip("launch_drone", "sound:launch-0.4", 3.0, flash=True),
        Clip("boost", 0.0, 4.5),
        Clip("cruise", 0.5, 3.0),
        Clip("impact_drone", "mark:gone-1.2", 4.5),
        Clip("impact_drone", "mark:gone+3.5", 1.5, rate=1.5),
        Clip("launch_missile", "sound:launch-0.6", 3.0, flash=True),
        Clip("impact_missile", "mark:gone-0.8", 3.0),
        Clip("missile_camera", "end-4.5", 3.0),
        Clip("bomber", "mark:gone-4.0", 6.0),
        Clip("bomber", "mark:gone+2.0", 3.0),
        Clip("salvo", "mark:gone-2.5", 6.0),
        Clip("salvo_missiles", "mark:gone-1.0", 6.0),
        Clip("icbm", 0.0, 3.0, flash=True),
        Clip("icbm", 5.0, 6.0),
        Clip("nuke", "mark:detonation-1.5", 9.0),
        Clip("mushroom", 0.0, 7.5, rate=2.0, xfade=0.5),
        Clip("fallout", 0.5, 5.5, xfade=1.0),
        Black(9.0),
    ]
    t = 0.0
    for c in edit:
        c.start = t
        t += c.dur
    total = t
    texts = [
        Text(1.5, 3.4, ("ZENTIX UA", "представляет"), "card"),
        Text(5.6, 3.6, ("Мод для «All of Create Aeronautics»",), "card"),
        Text(D - 12.5, 2.3, ("НАВЕДИ",), "word"),
        Text(D - 5.0, 1.8, ("ЗАПУСТИ",), "word"),
        Text(D + 3.0, 3.5, ("ШАХЕД-136", "разгонный блок · 50 секунд полёта"), "caption"),
        Text(D + 16.5, 3.0, ("КРЫЛАТАЯ РАКЕТА", "11 блоков за тик · вид с борта"), "caption"),
        Text(D + 25.5, 3.5, ("B-2 SPIRIT", "бетонобойная бомба"), "caption"),
        Text(D + 34.5, 3.5, ("ЗАЛП", "до 100 снарядов"), "caption"),
        Text(D + 46.5, 3.0, ("МБР", "ядерная боевая часть · 15 кт"), "caption"),
        Text(total - 9.0 + 0.6, 5.2, ("AIRSTRIKE",), "title"),
        Text(total - 9.0 + 2.0, 3.8, ("NeoForge 1.21.1 · Create Aeronautics", "github.com/zentixua/airstrike"), "sub"),
        Text(total - 3.0, 3.0, ("Музыка: " + MUSIC["credit"], "Звуки мода: Freesound (CC0 / CC BY) — список в SOUND-CREDITS.md"), "credits"),
    ]
    hits = [
        Hit(D - 4.0, "riser", 4.0, 0.8),
        Hit(D, "boom", 3.0, 1.0),
        Hit(total - 9.0 + 0.6, "boom", 5.0, 1.2),
    ]
    return edit, texts, hits, total, D


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
        """Звук по пути ресурса (namespace:path без .ogg) → моно float32 при SR."""
        if loc in self.cache:
            return self.cache[loc]
        ns, path = loc.split(":", 1)
        f = os.path.join(MOD_ASSETS, ns, "sounds", path + ".ogg")
        if not os.path.exists(f):
            f = self.vanilla.get(f"{ns}/sounds/{path}.ogg") or self.vanilla.get(f"minecraft/sounds/{path}.ogg")
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


def mix(edit, texts, hits, shots, total, drop_at):
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
    # тишина музыки: после ядерной вспышки (гул и ветер игры)
    for c in edit:
        if isinstance(c, Clip) and c.shot == "nuke":
            det = c.start + 1.5
            i = int(det * SR)
            k = int(0.08 * SR)
            music[i:i + k] *= np.linspace(1, 0, k)[:, None]
            music[i + k:] = 0
            break
    out = music * 0.55 + sfx * 0.9 + extra * 0.6
    # мягкий ограничитель
    peak = np.max(np.abs(out)) + 1e-9
    out = np.tanh(out * (1.4 / max(1.0, peak * 0.7))) * 0.95
    return out


# ---------------------------------------------------------------- сборка

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--draft", action="store_true")
    ap.add_argument("--out", default=os.path.join(DIST, "airstrike-trailer.mp4"))
    args = ap.parse_args()
    shots = load_recording()
    for v in FONTS.values():  # скачать до рабочих процессов
        fetch(*v)
    print("планы:", ", ".join(f"{s.name} {s.duration:.1f}с" for s in shots.values()))
    edit, texts, hits, total, drop_at = build_edit()
    missing = {c.shot for c in edit if isinstance(c, Clip) and c.shot not in shots}
    if missing:
        print("  ! нет планов:", ", ".join(sorted(missing)), "— вместо них чёрный кадр")
        edit = [Black(c.dur, c.start) if isinstance(c, Clip) and c.shot in missing else c for c in edit]
    for c in edit:
        if isinstance(c, Clip):
            c.src = max(0.0, anchor(shots[c.shot], c.at))
    first = next(iter(shots.values()))
    w0, h0 = Image.open(first.frame(0)).size
    size = (960, 540) if args.draft else (w0, h0)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    audio = os.path.join(CACHE, "trailer-audio.wav")
    print("звук…")
    sf.write(audio, mix(edit, texts, hits, shots, total, drop_at), SR, subtype="PCM_16")
    print(f"картинка: {int(total * FPS)} кадров {size[0]}×{size[1]}…")
    cmd = [ffmpeg(), "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{size[0]}x{size[1]}", "-r", str(FPS),
           "-i", "-", "-i", audio, "-c:v", "libx264", "-preset", "veryfast" if args.draft else "slow", "-crf", "23" if args.draft else "15",
           "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-c:a", "aac", "-b:a", "256k", "-shortest", args.out]
    enc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    n = int(total * FPS)
    with multiprocessing.Pool(os.cpu_count(), _init_worker, (edit, texts, shots, size)) as pool:
        for i, buf in enumerate(pool.imap(_render, range(n), chunksize=8)):
            enc.stdin.write(buf)
            if i % 600 == 0:
                print(f"  {i / FPS:.0f} с")
    enc.stdin.close()
    enc.wait()
    print("готово:", args.out)


if __name__ == "__main__":
    main()
