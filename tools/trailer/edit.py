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
"""Монтаж трейлера v2 из записи tools/trailer/record.sh: планы (кадры 60 fps) режутся по якорям и клеятся по долям
музыки, цвет, зерно, кинокаше 2.39:1 до ядерной вспышки; звук игры собирается заново из журнала (те же файлы мода,
громкость и панорама по камере, Доплер) по времени мира плана — в замедлении ниже и длиннее, в стоп-кадре тишина;
поверх — звуки монтажа (свисты, удары, брэм, нарастание) и музыка.

    uv run tools/trailer/edit.py [--draft] [--rec ПАПКА …] [--music eyes|nightfall] [--sfx ПАПКА] [--out ФАЙЛ]
    --draft  — быстрый черновик 960×540 (и тизер 540×960): проверить монтаж
    --rec    — папка записи (по умолчанию mod/run/scenario/trailer); несколько — одноимённые планы из следующих
               (пересъёмка) заменяют прежние; план с текстом игры в кадре — дубль на en_us, если он есть
    --music  — eyes (Scott Buckley, «Eyes in the Void», по умолчанию) или nightfall (запасная)
    --sfx    — папка звуков монтажа с manifest.json (см. SfxLibrary); без неё звуки монтажа синтезируются
    --no-blackout — без плана блэкаута (затишье после шквала на его месте)
    --only   — что собрать: trailer, teaser, thumb (можно через запятую; по умолчанию всё)
    результат (dist/): airstrike-trailer.mp4 (2560×1440, 60 fps, H.264 + AAC), …-lite.mp4 (1080p), …-teaser.mp4
    (1080×1920, 30 с), …-thumbnail.png (1280×720), …-credits.txt (строки для описания ролика)

Время в записи v2: у кадра k — «t» (тики мира от начала плана) и «ct» (время камеры). Скорость мира в плане меняется
(замедление на подлёте), в стоп-кадре (отметки freeze/unfreeze) «t» стоит, а «ct» идёт. Отметки и звуки пишутся
в журнал после кадра, в который они случились, — их место в видео плана — номер этого кадра (Shot.event_video).

Музыка, шрифты — один раз в tools/.trailer-cache (проверка sha256). Пакеты Python — в блоке script выше (PEP 723,
uv run ставит их сам); ffmpeg — системный или из пакета imageio-ffmpeg. Юнит-тесты: uv run tools/trailer/test_edit.py.
"""
import argparse
import bisect
import collections
import functools
import hashlib
import json
import math
import multiprocessing
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zlib
from dataclasses import dataclass, field

import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy.ndimage import gaussian_filter
from scipy.signal import butter, resample_poly, sosfilt

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from paths import DIST, MOD, ROOT  # noqa: E402

REC = os.path.join(MOD, "run", "scenario", "trailer")
CACHE = os.path.join(ROOT, "tools", ".trailer-cache")
SFX_DIR = os.path.join(ROOT, "tools", "trailer", "sfx")
MOD_ASSETS = os.path.join(MOD, "src", "main", "resources", "assets")
FPS = 60
SR = 48000
TPS = 20

SIZE = (2560, 1440)
LITE = (1920, 1080)
DRAFT = (960, 540)
TEASER = (1080, 1920)
TEASER_DRAFT = (540, 960)
THUMB = (1280, 720)
SCOPE = 2.39            # кинокаше до вспышки
BARS_OPEN = 0.5         # за сколько секунд кашe раскрывается на вспышке

# звук в замедлении: чтение файла со скоростью мира (тон ниже), но не ниже SLOW_MIN; ещё медленнее — затухание
# до тишины к SLOW_MUTE (стоп-кадр — тишина)
SLOW_MIN = 0.25
SLOW_MUTE = 0.1

# ---------------------------------------------------------------- музыка и шрифты (лицензии — в титрах и CREDITS)


@dataclass(frozen=True)
class Music:
    """Трек и его сетка долей: доля n — beat0 + n·60/bpm (с, от начала файла). Сетка и опорные доли сняты
    анализом онсетов (librosa: beat_track и спектральный поток по файлу; отклонение долей от сетки ~10 мс).
    Опорные доли (номера): a — начало части A, hit — удар перед «drop», drop — сильная доля, a_end — конец A;
    b3 — отрезок напряжения (сирена, МБР), b4 — начало куска, в котором flash (вспышка) и title (логотип);
    teaser — начало музыки тизера. snap — склейки ложатся на сетку по столько долей."""
    name: str
    url: str
    sha256: str
    file: str
    credit: str
    bpm: float
    beat0: float
    snap: int
    a: int
    hit: int
    drop: int
    a_end: int
    b3: tuple
    b4: int
    flash: int
    title: int
    teaser: int

    @property
    def beat_len(self):
        return 60.0 / self.bpm

    def t(self, n):
        return self.beat0 + n * self.beat_len


MUSICS = {
    # 130 уд/мин (доля 0.4615 с, полтакта 0.923 с, 2 такта 3.69 с); сильные доли: 46.32 (n98), 48.17 (n102, вход
    # барабанов), 182.95 (n394), 192.17 (n414, «удар 3:12» — громкость прыгает на 4 дБ)
    "eyes": Music(
        name="Eyes in the Void",
        url="https://www.scottbuckley.com.au/library/wp-content/uploads/2025/06/EyesInTheVoid.mp3",
        sha256="285e9aea3e24698fda179eba56d2f6c7d6ecd92f0ddc78932633e0641f769335",
        file="EyesInTheVoid.mp3",
        credit="“Eyes in the Void” by Scott Buckley — www.scottbuckley.com.au, CC BY 4.0",
        bpm=130.0, beat0=1.0911, snap=2,
        a=66, hit=98, drop=102, a_end=182, b3=(344, 360), b4=382, flash=394, title=414, teaser=372),
    # 80 уд/мин (доля 0.75 с); вход громкой части ~128.2 (n156), кульминация ~176.2 (n220)
    "nightfall": Music(
        name="Nightfall",
        url="https://www.scottbuckley.com.au/library/wp-content/uploads/2019/05/sb_nightfall.mp3",
        sha256="831a6baeb1d01fbecb89a0473b141625dd1136809f059ddaba6e106fd01db8e5",
        file="sb_nightfall.mp3",
        credit="“Nightfall” by Scott Buckley — www.scottbuckley.com.au, CC BY 4.0",
        bpm=80.0, beat0=11.197, snap=1,
        a=134, hit=154, drop=156, a_end=196, b3=(104, 116), b4=200, flash=208, title=220, teaser=190),
}

FONTS = {
    "title": ("https://raw.githubusercontent.com/google/fonts/main/ofl/russoone/RussoOne-Regular.ttf",
              "bc0abcc660bd8b7ad3000ecb2898a27c58a29a50f7ec81652fa12e75148d09df", "RussoOne-Regular.ttf"),
    "text": ("https://raw.githubusercontent.com/google/fonts/main/ofl/oswald/Oswald%5Bwght%5D.ttf",
             "5b38c246e255a12f5712d640d56bcced0472466fc68983d2d0410ec0457c2817", "Oswald.ttf"),
}

MAP_CREDIT = "Greenfield city map by the Greenfield team — https://www.greenfieldmc.net"
# Read Me карты: «If you are using Greenfield for a video … please provide a link in the description» — ссылка оттуда
# («Downloaded from», страница карты на Planet Minecraft)
MAP_LINK = "https://www.planetminecraft.com/project/greenfield---new-life-size-city-project/"
SOUNDS_LINK = "https://github.com/zentixua/airstrike/blob/main/SOUND-CREDITS.md"
PLATFORM = "Minecraft 1.21.1 · NeoForge"
REPO = "github.com/zentixua/airstrike"


def fetch(url, sha256, name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        print(f"скачиваю {name}")
        req = urllib.request.Request(url, headers={"User-Agent": "zentixua/airstrike trailer"})
        with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(path + ".part", path)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    if h.hexdigest() != sha256:
        raise SystemExit(f"{name}: sha256 {h.hexdigest()} не совпадает — удалите файл из {CACHE}")
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
    """План записи. Кадр k видео плана — момент k/FPS с; t[k] — тики мира, ct[k] — тики камеры в этот кадр."""
    name: str
    speed: float
    hud: bool = False
    lang: str = "ru_ru"
    paths: dict = field(default_factory=dict)      # k → PNG
    t: np.ndarray = None
    ct: np.ndarray = None
    cams: np.ndarray = None                        # (count, 5): x, y, z, yaw, pitch
    sounds: list = field(default_factory=list)     # события журнала + «k» — кадр, после которого записано
    updates: dict = field(default_factory=dict)    # id → [(k, x, y, z, volume, pitch, lp_gain, lp_highs)]
    stops: dict = field(default_factory=dict)      # id → k
    marks: list = field(default_factory=list)      # (k, t, что)

    @property
    def count(self):
        return len(self.t)

    @property
    def duration(self):
        return self.count / FPS

    def frame(self, k):
        """PNG кадра k или ближайшего снятого до него (если клиент пропустил)."""
        keys = self.keys
        i = bisect.bisect_right(keys, k) - 1
        return self.paths[keys[max(i, 0)]]

    def cam(self, k):
        return self.cams[min(max(int(k), 0), self.count - 1)]

    # --- время мира ↔ время видео плана

    def world(self, v):
        """Тики мира в момент v (с видео плана); за концом плана мир идёт с последней скоростью."""
        f = np.asarray(v, dtype=np.float64) * FPS
        n = self.count
        if n == 1:
            return self.t[0] + 0 * f
        last = self.t[-1] - self.t[-2]
        return np.where(f <= n - 1, np.interp(f, np.arange(n), self.t), self.t[-1] + (f - (n - 1)) * last)

    def rates(self):
        """Скорость мира на каждом кадре k (от кадра k до k+1): секунд мира на секунду видео (1 — как в игре,
        0 — стоп-кадр)."""
        r = getattr(self, "_rates", None)
        if r is None or len(r) != self.count:
            if self.count < 2:
                r = np.ones(self.count)
            else:
                d = np.diff(self.t) * FPS / TPS
                r = np.append(d, d[-1])
            self._rates = r
        return r

    def rate(self, v):
        k = min(max(int(v * FPS), 0), self.count - 1)
        return float(self.rates()[k])

    def video_of_tick(self, tick, last=False):
        """Момент видео плана (с), когда мир дошёл до tick тиков: первый (или последний — last) такой кадр
        с долей кадра между соседними; на стоп-кадре, где t стоит, first — его начало, last — конец."""
        t, n = self.t, self.count
        if last:
            i = int(np.searchsorted(t, tick, side="right")) - 1   # последний кадр с t ≤ tick
            if i < 0:
                return 0.0
            if i >= n - 1:
                return (n - 1) / FPS
            dt = t[i + 1] - t[i]
            return (i + ((tick - t[i]) / dt if dt > 0 else 0.0)) / FPS
        i = int(np.searchsorted(t, tick, side="left"))              # первый кадр с t ≥ tick
        if i <= 0:
            return 0.0
        if i >= n:
            return (n - 1) / FPS
        dt = t[i] - t[i - 1]
        return (i - (t[i] - tick) / dt if dt > 0 else i) / FPS

    def event_video(self, k):
        """Отметка или звук, записанные после кадра k: момент видео плана."""
        return k / FPS

    def freezes(self):
        """Стоп-кадры плана: [(начало, конец)] в секундах видео плана. По отметкам freeze/unfreeze, а в записи
        без них — по кадрам, где время мира стоит (не меньше 6 кадров подряд)."""
        out, start = [], None
        for k, _, what in self.marks:
            if what == "freeze":
                start = (k + 1) / FPS           # отметка — после кадра, в котором мир дошёл до момента заморозки
            elif what == "unfreeze" and start is not None:
                out.append((start, (k + 1) / FPS))
                start = None
        if start is not None:
            out.append((start, self.duration))
        if any(m[2] == "freeze" for m in self.marks):
            return out
        r = self.rates()
        k = 0
        while k < self.count:
            if r[k] == 0:
                j = k
                while j < self.count and r[j] == 0:
                    j += 1
                if j - k >= 6:
                    out.append(((k + 1) / FPS, (j + 1) / FPS))
                k = j
            else:
                k += 1
        return out


def load_recording(rec=REC):
    shots, last_k = {}, {}
    frames = collections.defaultdict(dict)       # имя → k → (t, ct, cam)
    with open(os.path.join(rec, "timeline.jsonl"), encoding="utf-8") as f:
        for line in f:
            try:
                e = json.loads(line)
            except json.JSONDecodeError:  # запись оборвалась на строке
                continue
            kind, name = e["type"], e["shot"]
            if kind == "shot":
                shots[name] = Shot(name, e["speed"], hud=e.get("hud", False), lang=e.get("lang", "ru_ru"))
                frames[name] = {}
                last_k[name] = 0
                continue
            if name not in shots:
                continue
            s = shots[name]
            k = last_k[name]
            if kind == "frame":
                k = last_k[name] = e["k"]
                frames[name][k] = (e["t"], e.get("ct", e["t"]), e["cam"])
                path = os.path.join(rec, "frames", name, f"{k:05d}.png")
                if os.path.exists(path):
                    s.paths[k] = path
            elif kind == "sound":
                s.sounds.append(dict(e, k=k))
            elif kind == "update":
                s.updates.setdefault(e["id"], []).append((k, *e["pos"], e["volume"], e["pitch"], *e.get("lp", (1, 1))))
            elif kind == "stop":
                s.stops[e["id"]] = k
            elif kind == "mark":
                # «gone» в самом начале — снаряды, пропавшие между планами (старые записи)
                if not (e["what"].startswith("gone:") and e["t"] <= 0):
                    s.marks.append((k, e["t"], e["what"]))
    out = {}
    for name, s in shots.items():
        fr = frames[name]
        if not fr or not s.paths:
            continue
        n = max(fr) + 1
        ks = np.array(sorted(fr))
        s.t = np.interp(np.arange(n), ks, [fr[k][0] for k in ks])
        s.ct = np.interp(np.arange(n), ks, [fr[k][1] for k in ks])
        cams = np.array([fr[k][2] for k in ks], dtype=np.float64)
        s.cams = np.stack([np.interp(np.arange(n), ks, cams[:, j]) for j in range(5)], axis=1)
        s.keys = sorted(s.paths)
        out[name] = s
    return out


def pick_takes(recordings, lang="en_us"):
    """Дубль каждого плана из записей (--rec по порядку): последний; план с текстом игры в кадре — последний
    на английском, а если такого нет — последний какой есть, с предупреждением."""
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


ANCHOR = re.compile(r"^(?P<kind>[a-z]+)(?::(?P<what>[^#+\-]*))?(?:#(?P<nth>-?\d+))?(?P<off>[+-]\d+(?:\.\d*)?)?$")


def anchor(shot, spec):
    """Момент плана в секундах видео плана. spec: число (секунды); «end»; «slowest» — кадр, где мир медленнее всего
    (пролёт у объектива); «tick:N» — мир дошёл до N тиков; «mark:что» (freeze, unfreeze, detonation, release,
    close, video, exit, gone — и gone:тип); «sound:часть имени». #n — n-е совпадение (#-1 — последнее), ±сдвиг в с.
    Нет такого — None."""
    if isinstance(spec, (int, float)):
        return float(spec)
    m = ANCHOR.match(spec)
    if not m:
        raise ValueError(f"якорь «{spec}»")
    kind, what = m["kind"], m["what"]
    nth = int(m["nth"]) if m["nth"] else 1
    off = float(m["off"]) if m["off"] else 0.0
    if kind == "end":
        return shot.duration + off
    if kind == "slowest":
        r = shot.rates()
        return float(np.argmin(r)) / FPS + off
    if kind == "tick":
        return shot.video_of_tick(float(what)) + off
    if kind == "mark":
        hits = [k for k, _, mk in shot.marks if mk == what or mk.startswith(what + ":")]
    elif kind == "sound":
        hits = [e["k"] for e in shot.sounds if what in e["event"]]
    else:
        raise ValueError(f"якорь «{spec}»: нет вида {kind}")
    if len(hits) >= abs(nth) and nth != 0:
        return shot.event_video(hits[nth - 1 if nth > 0 else nth]) + off
    return None


# ---------------------------------------------------------------- монтажный лист


@dataclass
class Clip:
    shot: str
    at: object = 0.0        # откуда в плане (секунды видео плана или якорь, см. anchor)
    dur: float = 2.0        # длительность в трейлере, с (раскладка ставит склейки на сетку долей)
    align: str = "start"    # «end» — якорь at отмечает конец отрезка плана
    rate: float = 1.0       # скорость воспроизведения плана (2 — вдвое быстрее)
    sfx: float = 1.0        # громкость звука игры
    tail: float = 0.0       # звук плана звучит ещё столько секунд после склейки (в чёрном кадре)
    whoosh: bool = False    # свист в эту склейку
    impact: str = None      # якорь удара в плане: низкий удар монтажа
    frame_y: float = 0.5    # какая полоса плана видна в кинокаше: 0 — верхняя (строки интерфейса), 0.5 — середина
    zoom: tuple = (1.0, 1.0)  # наезд: масштаб в начале и в конце
    game: tuple = ()        # звуки мода не по месту: ((якорь, файл в assets/airstrike/sounds, громкость), …) — то, что
                            # игра играет только вблизи (щелчок и глохнущий гул квартала), для общего плана издалека
    start: float = 0.0      # заполняется: начало в трейлере, с
    src: float = 0.0        # заполняется: начало в плане, с

    def trailer_time(self, v):
        """Момент трейлера для момента v видео плана."""
        return self.start + (v - self.src) / self.rate


@dataclass
class Card:
    """Чёрный кадр с надписью."""
    lines: tuple
    dur: float = 2.0
    style: str = "card"     # card — строка по центру; count — цифры отсчёта; title — логотип; end — последний кадр
    fade: float = 0.2
    start: float = 0.0


@dataclass
class Black:
    dur: float
    start: float = 0.0


@dataclass
class Hit:
    """Звук монтажа: at — момент синхронизации (для align=end — конец звука)."""
    at: float
    kind: str
    dur: float = 1.0
    gain: float = 1.0
    align: str = "start"


@dataclass
class Placement:
    """Кусок музыки: с trailer_t в трейлере играет файл с src0 до src1 (None — до конца раскладки)."""
    trailer_t: float
    src0: float
    src1: float = None
    xin: float = 0.0        # наплыв из прошлого куска (равная мощность), по центру склейки
    xout: float = 0.0
    fade_in: float = 0.01
    fade_out: float = 0.01


@dataclass
class Cut:
    """Разложенный монтаж: кадры, музыка, звуки монтажа."""
    items: list
    total: float
    music: list
    mutes: list             # (с, по) — музыка и звук игры молчат (после вспышки)
    hits: list = field(default_factory=list)
    bars_until: float = None  # до этого момента кинокаше, затем раскрытие за BARS_OPEN
    marks: dict = field(default_factory=dict)  # опорные моменты трейлера: drop, flash, title…

    def item_at(self, t):
        starts = [it.start for it in self.items]
        i = bisect.bisect_right(starts, t + 1e-9) - 1
        return self.items[max(i, 0)]


def snap_units(total_units, weights):
    """Склейки на сетке: total_units единиц сетки делятся между отрезками пропорционально weights, каждому — не
    меньше единицы. Возвращает число единиц каждого отрезка (сумма — total_units)."""
    n = len(weights)
    if total_units < n:
        raise ValueError(f"{n} отрезков не помещаются в {total_units} единиц сетки")
    cum = np.cumsum(weights) / sum(weights) * total_units
    cuts, prev = [], 0
    for i, c in enumerate(cum[:-1]):
        k = int(round(c))
        k = max(k, prev + 1)
        k = min(k, total_units - (n - 1 - i))
        cuts.append(k)
        prev = k
    edges = [0, *cuts, total_units]
    return [b - a for a, b in zip(edges, edges[1:])]


def snap_to_grid(t, origin, unit):
    """Ближайшая к t точка сетки origin + j·unit."""
    return origin + round((t - origin) / unit) * unit


class Timeline:
    """Раскладка: куски без музыки (свободно по секундам) и части под музыку, где склейки — на сетке долей."""

    def __init__(self, music):
        self.m = music
        self.t = 0.0
        self.items = []
        self.placements = []
        self.mutes = []
        self.marks = {}
        self.section = None     # [(доля с, доля по, начало в трейлере)]

    def add(self, *items):
        for it in items:
            it.start = self.t
            self.items.append(it)
            self.t += it.dur

    def play(self, *segments, xfade=0.3):
        """Музыка с этого момента: куски [(доля с, доля по или None)] встык, склейки кусков — наплывом xfade
        с центром на доле стыка (обе стороны стыка — сильные доли своих фраз)."""
        self.section = []
        t = self.t
        for i, (n0, n1) in enumerate(segments):
            self.section.append((n0, n1, t))
            p = Placement(t, self.m.t(n0), None if n1 is None else self.m.t(n1),
                          xin=xfade if i > 0 else 0.0, xout=xfade if i < len(segments) - 1 else 0.0)
            self.placements.append(p)
            if n1 is not None:
                t += (n1 - n0) * self.m.beat_len

    def at(self, name):
        """Момент трейлера опорной доли музыки (имя поля Music или номер доли) в текущей части."""
        n = getattr(self.m, name) if isinstance(name, str) else name
        for n0, n1, t0 in self.section:
            if n0 <= n and (n1 is None or n <= n1):
                return t0 + (n - n0) * self.m.beat_len
        raise ValueError(f"доля {n} не в этой части музыки")

    @property
    def unit(self):
        return self.m.snap * self.m.beat_len

    def run(self, items, until=None):
        """Отрезки подряд: до опорной доли until — пропорционально их длине на сетке, без until — каждый своей
        длиной, округлённой до сетки (не меньше единицы)."""
        u = self.unit
        if until is not None:
            end = self.at(until)
            units = snap_units(int(round((end - self.t) / u)), [it.dur for it in items])
        else:
            units = [max(1, int(round(it.dur / u))) for it in items]
        for it, n in zip(items, units):
            it.dur = n * u
            self.add(it)
        if until is not None:
            self.t = end            # без накопления ошибки округления

    def stop(self, fade=0.3, at=None):
        """Музыка кончается в at (по умолчанию — сейчас) спадом fade после этого момента."""
        at = self.t if at is None else at
        p = self.placements[-1]
        t0 = self.section[-1][2]
        p.src1 = p.src0 + (at - t0) + fade
        p.fade_out = fade
        self.section = None

    def mute(self, t0, t1):
        self.mutes.append((t0, t1))

    def cut(self, **kw):
        return Cut(self.items, self.t, self.placements, self.mutes, marks=self.marks, **kw)


def trailer_edit(music, blackout=True):
    """
    Трейлер ~1:40. Холодное начало (шахед у объектива, чёрный кадр со взрывом), часть A музыки — рассвет, наводчик,
    карта, «drop» на пуске ракеты и удары подряд по полутактам; тишина — музыка обрывается, только фон ночного
    города; сирена и МБР — часть B, отсчёт по долям, вспышка на сильную долю (кинокаше раскрывается), 3 с полной
    тишины, ударная волна с возвращением звука, логотип на «удар 3:12», гриб, руины, осадки, последний кадр.
    """
    tl = Timeline(music)
    # холодное начало: до музыки, свободно по секундам
    tl.add(Clip("cold_open", "slowest-1.75", 1.9, tail=1.2, sfx=1.1), Black(1.0))
    tl.play((music.a, None))
    tl.run([
        Card(("EVERYTHING YOU SEE",), 3.7, fade=0.5),
        Clip("dawn_city", 0.6, 2.8),
        Clip("dawn_tower", 0.4, 2.8),
        Clip("operator", 0.8, 1.9),
        Clip("scope", 1.0, 1.9, frame_y=0.35),
        Clip("map", 2.9, 1.9, frame_y=0.3),
    ], until="hit")
    tl.run([Card(("CAN BE A TARGET",), 1.9, fade=0.12)], until="drop")
    tl.marks["drop"] = tl.t
    tl.run([
        Clip("launch_missile", "sound:launch.booster-0.4", 1.9, whoosh=True),
        Clip("missile_tower", "mark:freeze-0.6", 3.7, impact="mark:freeze"),
        Clip("launch_drone", "sound:launch.booster-0.3", 1.9, whoosh=True),
        Clip("boost", 0.2, 1.9, whoosh=True),
        Clip("drone_city", 1.0, 1.9),
        Clip("impact_drone", "mark:freeze-0.9", 2.8, impact="mark:freeze"),
        Clip("loiter_launch", "sound:loiter.launch-0.4", 1.9, whoosh=True),
        Clip("loiter_strike", "mark:gone-1.5", 1.9, impact="mark:gone"),
        Clip("rocket_launch", "sound:rocket.launch-0.5", 1.9, whoosh=True),
        Clip("rocket_impact", "mark:gone-0.5", 1.9, impact="mark:gone"),
        Clip("fighters", "slowest-1.2", 2.8, whoosh=True),
        Clip("missile_camera", "mark:close", 2.8, frame_y=0.0, whoosh=True),
        Clip("bomb_bay", "mark:release-1.4", 1.9),
        Clip("bomb_impact", "mark:freeze-0.8", 2.8, impact="mark:freeze"),
        Clip("swarm_night", "mark:gone-1.2", 2.8),
        Clip("grad_night", "mark:gone-0.8", 2.8, impact="mark:gone"),
        Clip("barrage", "mark:gone#3-0.6", 2.8, impact="mark:gone#3"),
    ], until="a_end")
    tl.stop(fade=0.35)
    # тишина: музыки нет, только фон ночного города после шквала
    # подстанция: ракета, дуга, кварталы гаснут от неё вглубь; щелчки кварталов с общего плана не слышны — из ресурсов мода
    # удар по подстанции вживую, потом кварталы гаснут втрое быстрее: волна идёт от подстанции 5–17 с (ноутбук, kfcheck4b),
    # а на 1× за 6,4 с успевала погаснуть лишь стоянка под ней
    if blackout:
        tl.add(Clip("blackout", "mark:gone-1.2", 3.2, sfx=0.9,
                    game=(("mark:gone+1.6", "grid_power_down_1", 0.35),)))
        tl.add(Clip("blackout", "mark:gone+2.0", 3.2, rate=3.0, sfx=0.3,
                    game=(("mark:gone+3.4", "grid_power_down_2", 0.25),)))
    else:
        # без блэкаута (его нет в версии мода): затишье с последнего попадания шквала на то же место и ту же длину
        tl.add(Clip("night_after", 1.0, 6.4, sfx=0.8))
    tl.play(music.b3, (music.b4, None))
    tl.run([
        Clip("siren", 0.4, 3.7),
        Clip("icbm", 0.0, 3.7, game=((0.1, "nuke_launch", 0.6),)),
        # план пишется с пуска; звук пуска игра даёт только в первые 2 с после пакета — на ноутбуке (дубль 2) его
        # в журнале не было, тогда он из ресурсов мода
    ], until=music.b3[1])
    tl.run([Clip("icbm", 5.8, 2.8, zoom=(1.0, 1.06))], until=music.flash - 3 * music.snap)
    tl.run([Card(("03",), 1, "count", fade=0.0), Card(("02",), 1, "count", fade=0.0), Card(("01",), 1, "count", fade=0.0)],
           until="flash")
    flash = tl.t
    tl.marks["flash"] = flash
    # вспышка: кинокаше раскрывается, 3 с полной тишины (музыка идёт дальше без звука — логотип останется на доле)
    tl.run([Clip("flash", "mark:detonation-0.05", 2.8, sfx=0.0)])
    tl.mute(flash, tl.t)
    tl.run([
        Clip("wave_street", "tick:24-0.8", 1.9, impact="tick:24"),
        Clip("wave_port", "tick:24-0.8", 1.9, impact="tick:24"),
        Clip("wave_hill", "tick:60-1.0", 2.8, impact="tick:60", frame_y=0.35),
    ], until="title")
    tl.marks["title"] = tl.t
    tl.run([
        Card(("AIRSTRIKE",), 2.8, "title", fade=0.04),
        Clip("mushroom", 2.0, 2.8),
        Clip("ruins", 7.0, 2.8),                 # начало плана — в пыли гриба (сплошной бурый кадр)
        Clip("fallout", 1.5, 3.7, frame_y=0.35),
        Card(("AIRSTRIKE", PLATFORM, REPO, music.credit, MAP_CREDIT,
              "Sound effects: Freesound contributors (CC0 / CC BY 4.0)"), 5.5, "end", fade=0.5),
    ])
    tl.stop(fade=4.0, at=tl.t - 4.0)
    return tl.cut(bars_until=flash)


def teaser_edit(music):
    """Вертикальный тизер 30 с: стоп-кадр ракеты у башни, шквал, вспышка, волна, логотип на ударе, гриб, последний кадр."""
    tl = Timeline(music)
    tl.play((music.teaser, None))
    tl.run([
        Clip("missile_tower", "mark:freeze-1.8", 5.5, impact="mark:freeze"),
        Clip("barrage", "mark:gone#3-1.0", 4.6, impact="mark:gone#3"),
    ], until="flash")
    flash = tl.t
    tl.marks["flash"] = flash
    tl.run([Clip("flash", "mark:detonation-0.05", 2.8, sfx=0.0)])
    tl.mute(flash, tl.t)
    tl.run([Clip("wave_street", "tick:24-0.8", 2.8, impact="tick:24"),
            Clip("wave_hill", "tick:60-1.2", 2.8, impact="tick:60")], until="title")
    tl.marks["title"] = tl.t
    end = music.teaser + music.snap * round(30.0 / tl.unit)      # 30 с, ровно на сетке
    # после логотипа — гриб: одни надписи занимали треть тизера (черновик 30.09)
    tl.run([Card(("AIRSTRIKE",), 2.8, "title", fade=0.04),
            Clip("mushroom", 2.0, 2.8),
            Card(("AIRSTRIKE", PLATFORM, REPO), 3.7, "end", fade=0.4)], until=end)
    tl.stop(fade=2.5, at=tl.t - 2.5)
    return tl.cut()


def resolve(cut, shots):
    """Якоря → места в планах; нет плана — его время у соседнего плана (без чёрных дыр)."""
    items = []
    for it in cut.items:
        if isinstance(it, Clip) and it.shot not in shots:
            print(f"  ! нет плана {it.shot} — его время у прошлого отрезка")
            if items:
                items[-1].dur += it.dur
            else:
                items.append(Black(it.dur, it.start))
            continue
        items.append(it)
    cut.items = items
    for c in items:
        if not isinstance(c, Clip):
            continue
        s = shots[c.shot]
        a = anchor(s, c.at)
        if a is None:
            print(f"  ! {c.shot}: нет «{c.at}», беру начало плана")
            a = 0.0
        span = c.dur * c.rate
        c.src = a - span if c.align == "end" else a
        # камера снаряда вернулась к игроку («exit») — отрезок кончается раньше
        exits = [s.event_video(k) for k, _, m in s.marks if m == "exit"]
        if exits and exits[0] > c.src and c.src + span > exits[0] - 0.1:
            c.src = exits[0] - 0.1 - span
        c.src = max(0.0, c.src)
        left = s.duration - c.src
        if span > left:
            if left >= span * 0.5:     # не хватает кадров — медленнее, но до конца плана
                c.rate = left / c.dur
            else:
                c.src = max(0.0, s.duration - span)
    return cut


def sfx_layer(cut, shots, music):
    """Звуки монтажа: свист в быструю склейку, низкий удар на попадание, стоп-кадр (обратный свист в него, гул,
    удар на выходе), удар на «drop», нарастание к вспышке, брэм на логотип, щелчки отсчёта."""
    hits = []
    for c in cut.items:
        if isinstance(c, Card) and c.style == "count":
            hits.append(Hit(c.start, "tick", 0.9, 0.8))
        if not isinstance(c, Clip):
            continue
        s = shots[c.shot]
        end = c.start + c.dur
        if c.whoosh:
            hits.append(Hit(c.start + 0.05, "whoosh", 0.7, 0.55, "end"))
        if c.impact:
            v = anchor(s, c.impact)
            if v is not None and c.start <= c.trailer_time(v) < end:
                hits.append(Hit(c.trailer_time(v), "sub", 2.4, 0.8))
        for at, name, gain in c.game:
            if any(e["file"].endswith("/" + name + ".ogg") for e in s.sounds):
                continue                                    # игра его и так записала — не дублировать
            v = anchor(s, at)
            if v is not None and c.start <= c.trailer_time(v) < end:
                hits.append(Hit(c.trailer_time(v), "game:" + name, 3.0, gain))
        for f0, f1 in s.freezes():
            t0, t1 = c.trailer_time(f0), c.trailer_time(f1)
            if t1 <= c.start or t0 >= end:
                continue
            if t0 > c.start + 0.2:
                hits.append(Hit(t0, "reverse", 1.1, 0.5, "end"))
            a, b = max(t0, c.start), min(t1, end)
            if b - a > 0.3:
                hits.append(Hit(a, "drone", b - a, 0.6))
            if t1 < end - 0.05:
                hits.append(Hit(t1, "impact", 1.6, 0.8))
    if "drop" in cut.marks:
        hits.append(Hit(cut.marks["drop"], "impact", 2.5, 0.9))
    if "flash" in cut.marks:
        hits.append(Hit(cut.marks["flash"], "riser", 4.0, 0.7, "end"))
    if "title" in cut.marks:
        hits.append(Hit(cut.marks["title"], "braam", 4.5, 1.0))
    cut.hits = sorted(hits, key=lambda h: h.at)
    return cut


# ---------------------------------------------------------------- картинка

class Look:
    """Цвет: мягкая киношная кривая, холодные тени и тёплые света (слегка), виньетка, мелкое живое зерно."""

    GRAIN = 3.2             # сила зерна в средних тонах, единиц из 255
    POOL = 6

    def __init__(self, w, h, seed=20260929):
        self.w, self.h = w, h
        x = np.linspace(0, 1, 256)
        base = x + 0.12 * np.sin(2 * np.pi * (x - 0.5)) * x * (1 - x) * 2   # S-кривая, 0 и 1 на месте
        shadows = np.clip(1 - x / 0.45, 0, 1) ** 2 * np.clip(x / 0.06, 0, 1)
        highs = np.clip((x - 0.55) / 0.45, 0, 1) ** 1.5 * np.clip((1 - x) / 0.04, 0, 1)
        lut = np.stack([base + 0.030 * highs - 0.020 * shadows,
                        base + 0.008 * highs + 0.006 * shadows,
                        base - 0.030 * highs + 0.026 * shadows], axis=0)
        self.lut = np.clip(np.round(lut * 255), 0, 255).astype(np.uint8)
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        r = np.sqrt(((xx - w / 2) / (w / 2)) ** 2 + ((yy - h / 2) / (h / 2)) ** 2)
        self.vignette = np.clip(1.0 - 0.24 * np.clip(r - 0.55, 0, None) ** 1.7, 0, 1).astype(np.float32)[..., None]
        # зерно: поле нормального шума, чуть размытое (зёрна крупнее пикселя), кадр — окно со сдвигом в своём поле
        rng = np.random.default_rng(seed)
        sigma = 0.5 * min(w, h) / 1080
        self.pad = 32
        self.grain = []
        for _ in range(self.POOL):
            g = rng.standard_normal((h + self.pad, w + self.pad), dtype=np.float32)
            if sigma > 0.3:
                g = gaussian_filter(g, sigma)
            self.grain.append((g / g.std()).astype(np.float32))

    def grade(self, a):
        """Кривая и оттенок: uint8 HxWx3 → uint8."""
        return np.stack([self.lut[i][a[..., i]] for i in range(3)], axis=-1)

    def finish(self, a, i):
        """Виньетка и зерно кадра i (от номера кадра — одинаково при любой разбивке на процессы)."""
        f = a.astype(np.float32)
        f *= self.vignette
        rng = np.random.default_rng(i)
        g = self.grain[i % self.POOL]
        dy, dx = rng.integers(0, self.pad, 2)
        g = g[dy:dy + self.h, dx:dx + self.w]
        lum = f[..., 1] * (1 / 255)
        f += (g * (self.GRAIN * (0.35 + 2.6 * lum * (1 - lum))))[..., None]
        np.clip(f, 0, 255, out=f)
        return f.astype(np.uint8)


class Titles:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.s = min(w / 1920, h / 1080) if w >= h else w / 1920 * 1.2
        self.fonts = {k: fetch(*v) for k, v in FONTS.items()}
        self.cache = {}

    def font(self, kind, size, weight=None):
        f = ImageFont.truetype(self.fonts[kind], max(8, int(size)))
        if weight is not None:
            try:
                f.set_variation_by_axes([weight])
            except (OSError, AttributeError):
                pass
        return f

    def _tracked(self, d, xy, text, font, fill, tracking):
        """Строка с разрядкой (tracking — доля кегля между буквами)."""
        x, y = xy
        for ch in text:
            d.text((x, y), ch, font=font, fill=fill)
            x += font.getlength(ch) + tracking * font.size

    def _width(self, text, font, tracking):
        return sum(font.getlength(ch) for ch in text) + tracking * font.size * (len(text) - 1)

    def render(self, card):
        """Надпись во весь кадр: (y0, y1, x0, x1, rgb float32, alpha float32) по её рамке (кэш)."""
        key = (card.lines, card.style)
        if key in self.cache:
            return self.cache[key]
        w, h, s = self.w, self.h, self.s
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        if card.style == "card":
            f = self.font("text", 58 * s, 300)
            tr = 0.32
            y = h / 2 - f.size * 0.62
            for ln in card.lines:
                self._tracked(d, ((w - self._width(ln, f, tr)) / 2, y), ln, f, (236, 236, 232, 255), tr)
                y += f.size * 1.4
        elif card.style == "count":
            f = self.font("text", 220 * s, 200)
            ln = card.lines[0]
            bb = d.textbbox((0, 0), ln, font=f)
            d.text(((w - (bb[2] - bb[0])) / 2 - bb[0], (h - (bb[3] - bb[1])) / 2 - bb[1]), ln, font=f, fill=(240, 240, 236, 255))
        elif card.style in ("title", "end"):
            size = 200 if card.style == "title" else 130
            f = self.font("title", size * s)
            ln = card.lines[0]
            bb = d.textbbox((0, 0), ln, font=f)
            ty = h * (0.5 if card.style == "title" else 0.36) - (bb[3] - bb[1]) / 2 - bb[1]
            xy = ((w - (bb[2] - bb[0])) / 2 - bb[0], ty)
            glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            ImageDraw.Draw(glow).text(xy, ln, font=f, fill=(255, 118, 30, 190))
            layer.alpha_composite(glow.filter(ImageFilter.GaussianBlur(radius=max(4, f.size // 8))))
            d.text(xy, ln, font=f, fill=(255, 244, 228, 255))
            if card.style == "end":
                y = ty + (bb[3] - bb[1]) + 60 * s
                for i, ln in enumerate(card.lines[1:3]):
                    fs = self.font("text", (38 if i == 0 else 30) * s, 400)
                    tr = 0.12
                    self._tracked(d, ((w - self._width(ln, fs, tr)) / 2, y), ln, fs, (225, 225, 220, 255), tr)
                    y += fs.size * 1.55
                fc = self.font("text", 22 * s, 300)
                lines = card.lines[3:]
                y = h - (len(lines) * 34 + 70) * s
                for ln in lines:
                    bb = d.textbbox((0, 0), ln, font=fc)
                    d.text(((w - (bb[2] - bb[0])) / 2, y), ln, font=fc, fill=(170, 170, 166, 255))
                    y += 34 * s
        a = np.asarray(layer)
        ys, xs = np.nonzero(a[..., 3])
        if len(ys) == 0:
            out = None
        else:
            y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
            part = a[y0:y1, x0:x1].astype(np.float32)
            out = (y0, y1, x0, x1, part[..., :3], part[..., 3:4] / 255)
        self.cache[key] = out
        return out

    def draw(self, a, card, t):
        """Надпись карточки поверх кадра a (uint8) с появлением и уходом."""
        r = self.render(card)
        if r is None:
            return a
        y0, y1, x0, x1, rgb, alpha = r
        k = 1.0
        if card.fade > 0:
            k = min(1.0, (t - card.start) / card.fade, (card.start + card.dur - t) / card.fade)
        k = max(0.0, k)
        if k <= 0:
            return a
        region = a[y0:y1, x0:x1].astype(np.float32)
        al = alpha * k
        a[y0:y1, x0:x1] = np.clip(region * (1 - al) + rgb * al, 0, 255).astype(np.uint8)
        return a


# состояние рабочих процессов отрисовки
_W = {}


def _init_worker(cut, shots, size, vertical):
    _W.update(cut=cut, shots=shots, size=size, vertical=vertical, look=Look(*size), titles=Titles(*size))
    w, h = size
    _W["bar"] = 0 if vertical else int(round((h - w / SCOPE) / 2))   # вертикальный тизер — без кинокаше


def bars_at(cut, t, full):
    """Высота полосы кинокаше в момент t: до вспышки — вся, за BARS_OPEN после — плавно в ноль."""
    if full <= 0:
        return 0
    if cut.bars_until is None:
        return full
    p = min(1.0, max(0.0, (t - cut.bars_until) / BARS_OPEN))
    ease = p * p * (3 - 2 * p)
    return int(round(full * (1 - ease)))


def _open(c, t):
    s = _W["shots"][c.shot]
    k = int(round((c.src + (t - c.start) * c.rate) * FPS))
    return Image.open(s.frame(max(0, min(k, s.count - 1)))).convert("RGB")


def _zoom_box(c, t, box):
    z0, z1 = c.zoom
    z = z0 + (z1 - z0) * min(1.0, max(0.0, (t - c.start) / c.dur))
    if abs(z - 1) < 1e-3:
        return box
    x0, y0, x1, y1 = box
    cx, cy, bw, bh = (x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / z, (y1 - y0) / z
    return (cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)


def _source(c, t):
    """Кадр плана под момент трейлера t в размере вывода (uint8), с наездом и окном кинокаше."""
    w, h = _W["size"]
    img = _open(c, t)
    sw, sh = img.size
    bar = _W["bar"]
    if bar > 0 and c.frame_y != 0.5 and t < (_W["cut"].bars_until or 1e9):
        # окно 2.39:1 сдвигается по плану, чтобы кинокаше не срезало интерфейс у края
        win = sh * (h - 2 * bar) / h
        top = (sh - win) * c.frame_y
        box = _zoom_box(c, t, (0, top, sw, top + win))
        out = np.zeros((h, w, 3), np.uint8)
        out[bar:h - bar] = np.asarray(img.resize((w, h - 2 * bar), Image.LANCZOS, box=box))
        return out
    return np.asarray(img.resize((w, h), Image.LANCZOS, box=_zoom_box(c, t, (0, 0, sw, sh))))


def _source_vertical(c, t):
    """Вертикальный кадр: середина плана квадратом по центру, фон — тот же кадр во весь экран, размытый и тёмный."""
    w, h = _W["size"]
    img = _open(c, t)
    sw, sh = img.size
    side = min(sw, sh)
    fg = img.resize((w, w), Image.LANCZOS, box=_zoom_box(c, t, ((sw - side) / 2, (sh - side) / 2, (sw + side) / 2, (sh + side) / 2)))
    bw = sh * w / h
    small = img.resize((max(8, w // 12), max(8, h // 12)), Image.BILINEAR, box=((sw - bw) / 2, 0, (sw + bw) / 2, sh))
    bg = small.filter(ImageFilter.GaussianBlur(3)).resize((w, h), Image.BICUBIC)
    out = (np.asarray(bg, dtype=np.float32) * 0.45).astype(np.uint8)
    y = (h - w) // 2
    out[y:y + w] = np.asarray(fg)
    return out


def render_frame(i):
    cut, look = _W["cut"], _W["look"]
    w, h = _W["size"]
    t = i / FPS
    it = cut.item_at(t)
    if isinstance(it, Clip):
        a = look.grade((_source_vertical if _W["vertical"] else _source)(it, t))
    else:
        a = np.zeros((h, w, 3), np.uint8)
    a = look.finish(a, i)
    bar = bars_at(cut, t, _W["bar"])
    if bar > 0:
        a[:bar] = 0
        a[h - bar:] = 0
    if isinstance(it, Card):
        a = _W["titles"].draw(a, it, t)
    fade = _fade(cut, t)
    if fade < 1:
        a = (a.astype(np.float32) * fade).astype(np.uint8)
    return np.ascontiguousarray(a).tobytes()


def _fade(cut, t):
    """Затемнение в начале и в конце ролика."""
    return max(0.0, min(1.0, t / 0.15, (cut.total - t) / 0.6))


def render_frames(n, jobs, init):
    """Кадры по порядку. В работе и в очереди — не больше 2×jobs кадров: Pool.imap не ждёт потребителя и копит
    готовые кадры (11 МБ на 1440p), пока кодер медленнее отрисовки."""
    if jobs <= 1:
        _init_worker(*init)
        for i in range(n):
            yield render_frame(i)
        return
    with multiprocessing.Pool(jobs, _init_worker, init) as pool:
        pending = collections.deque()
        for i in range(n):
            pending.append(pool.apply_async(render_frame, (i,)))
            if len(pending) >= 2 * jobs:
                yield pending.popleft().get()
        while pending:
            yield pending.popleft().get()


# ---------------------------------------------------------------- звук игры

class SoundBank:
    def __init__(self):
        self.vanilla = {}
        props = os.path.join(MOD, "build", "moddev", "minecraft_assets.properties")
        if os.path.exists(props):
            conf = dict(line.strip().split("=", 1) for line in open(props) if "=" in line and not line.startswith("#"))
            root = conf["assets_root"]
            index = json.load(open(os.path.join(root, "indexes", conf["asset_index"] + ".json")))["objects"]
            self.vanilla = {k: os.path.join(root, "objects", v["hash"][:2], v["hash"]) for k, v in index.items()}
        else:
            print(f"  ! нет {props} (./gradlew build) — ванильные звуки не войдут")
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


def gain_pan(cams, pos, volume, rng, relative, linear):
    """Громкость, панорама и расстояние по кадрам, как у движка: линейное затухание до range; без затухания (моторы
    мода, удар волны) — громкость как есть, но направление честное. cams (N, 5), pos (N, 3), volume (N,)."""
    n = len(cams)
    g = np.minimum(volume, 1.0)
    if relative:
        return g, np.zeros(n), np.zeros(n)
    d = pos - cams[:, :3]
    dist = np.linalg.norm(d, axis=1)
    if linear:
        g = g * np.maximum(0.0, 1.0 - dist / max(rng, 1e-3))
    yr = np.radians(cams[:, 3])
    side = -d[:, 0] * np.cos(yr) - d[:, 2] * np.sin(yr)
    pan = np.where(dist > 1e-3, side / np.maximum(dist, 1e-3), 0.0)
    return g, pan, dist


def slow_read(r):
    """Скорость чтения файла звука при скорости мира r (секунд мира на секунду видео): как у настоящей замедленной
    съёмки (тон ниже) до SLOW_MIN, ниже — не ниже SLOW_MIN (звук затихает, см. slow_gain); ускоренный план — звук
    в своём темпе (не «мультяшный»). Где звук уже не слышен (r < SLOW_MUTE), чтение идёт с миром — после стоп-кадра
    звук продолжается с того же места."""
    r = np.asarray(r, dtype=np.float64)
    return np.where(r >= 1.0, 1.0, np.where(r >= SLOW_MIN, r, np.where(r >= SLOW_MUTE, SLOW_MIN, r)))


def slow_gain(r):
    """Громкость звука игры при скорости мира r: полная от SLOW_MIN, плавно в ноль к SLOW_MUTE (стоп-кадр — тишина)."""
    r = np.asarray(r, dtype=np.float64)
    x = np.clip((r - SLOW_MUTE) / (SLOW_MIN - SLOW_MUTE), 0, 1)
    return x * x * (3 - 2 * x)


def read_cubic(data, pos):
    """Чтение звука в дробных позициях (кубическая интерполяция Эрмита — Катмулла–Рома); за краем — ноль."""
    n = len(data)
    i = np.floor(pos).astype(np.int64)
    f = (pos - i).astype(np.float32)
    ok = (i >= 0) & (i < n - 1)
    i = np.clip(i, 0, n - 1)
    ym1 = data[np.clip(i - 1, 0, n - 1)]
    y0 = data[i]
    y1 = data[np.clip(i + 1, 0, n - 1)]
    y2 = data[np.clip(i + 2, 0, n - 1)]
    c1 = 0.5 * (y1 - ym1)
    c2 = ym1 - 2.5 * y0 + 2 * y1 - 0.5 * y2
    c3 = 0.5 * (y2 - ym1) + 1.5 * (y0 - y1)
    return np.where(ok, ((c3 * f + c2) * f + c1) * f + y0, 0.0).astype(np.float32)


# фон мира, который в трейлере только мешает (пещерный гул, спавнер испытаний под землёй)
SKIP_SOUNDS = ("minecraft:ambient.", "minecraft:block.trial_spawner", "minecraft:music")


def render_sound(shot, e, data, c, window):
    """Один звук журнала на отрезке c трейлера: (номер первого сэмпла трейлера, левый, правый) или None.

    Всё — по кадрам видео плана: время мира (скорость r — секунд мира на секунду видео), камера, громкость и тон
    из обновлений. Позиция в файле — интеграл тона × slow_read(r) по видео плана от кадра, в котором звук
    зазвучал; отрезок трейлера c читает её со своей скоростью c.rate."""
    t_lo, t_hi = window                         # отрезок трейлера (со «хвостом» звука)
    k_on = e["k"]
    k_off = shot.stops.get(e["id"])
    v_lo = c.src + (t_lo - c.start) * c.rate    # то же в видео плана
    v_hi = c.src + (t_hi - c.start) * c.rate
    if k_on / FPS >= v_hi or (k_off is not None and k_off / FPS <= v_lo):
        return None
    n_frames = int(math.ceil(v_hi * FPS)) + 2
    ks = np.arange(k_on, max(k_on + 2, n_frames))
    kc = np.minimum(ks, shot.count - 1)
    r = np.where(ks < shot.count, shot.rates()[kc], 1.0)    # за концом плана (хвост звука) — как в игре
    ups = shot.updates.get(e["id"])
    if ups:
        u = np.array(ups, dtype=np.float64)                 # (k, x, y, z, volume, pitch, lp_gain, lp_highs)
        j = np.clip(np.searchsorted(u[:, 0], ks, side="right") - 1, 0, len(u) - 1)
        pos, vol, pit, lpg, lph = u[j, 1:4], u[j, 4], u[j, 5], u[j, 6], u[j, 7]
    else:
        n = len(ks)
        lp = e.get("lp", (1, 1))
        pos = np.tile(np.asarray(e["pos"], dtype=np.float64), (n, 1))
        vol, pit, lpg, lph = (np.full(n, float(x)) for x in (e["volume"], e["pitch"], lp[0], lp[1]))
    g, pan, dists = gain_pan(shot.cams[kc], pos, vol, e["range"], e["relative"], e["linear"])
    gains = g * lpg * slow_gain(r)
    pans = pan
    q = np.clip(pit, 0.5, 2.0) * slow_read(r)       # секунд файла на секунду видео плана, по кадрам
    seen = ks >= int(v_lo * FPS) - 1                # кадры, что слышны в отрезке (для фильтра по расстоянию)
    dists, highs = dists[seen] if seen.any() else dists, lph[seen] if seen.any() else lph
    # позиция в файле (сэмплы) на границах кадров: звук начался в кадре k_on
    P = np.concatenate([[0.0], np.cumsum(q) * SR / FPS])
    i0 = max(int(math.ceil(max(t_lo, c.trailer_time(k_on / FPS)) * SR)), 0)
    t_end = t_hi if k_off is None else min(t_hi, c.trailer_time(k_off / FPS))
    i1 = int(t_end * SR)
    if i1 <= i0:
        return None
    ts = np.arange(i0, i1) / SR
    fv = (c.src + (ts - c.start) * c.rate) * FPS - k_on     # кадр плана от начала звука (дробный)
    fv = np.clip(fv, 0, len(ks) - 1e-6)
    pos = np.interp(fv, np.arange(len(P)), P)
    if e["loop"]:
        pos = np.mod(pos, len(data) - 1)
    y = read_cubic(data, pos)
    fc = np.arange(len(ks)) + 0.5
    g = np.interp(fv, fc, gains)
    pan = np.interp(fv, fc, pans) * 0.75
    dist = float(np.median(dists))
    hf = float(np.median(highs))
    if hf < 0.98:  # фильтр мода (воздух, холм или дом между): доля верхов → срез
        y = sosfilt(butter(2, max(500.0, 20000.0 * hf ** 1.6), "low", fs=SR, output="sos"), y)
    elif dist > 40 and not e["event"].startswith("airstrike:"):  # ванильные звуки издалека — воздух глушит верха
        y = sosfilt(butter(2, max(900.0, 16000.0 / (1 + dist / 120)), "low", fs=SR, output="sos"), y)
    y = (y * g * c.sfx).astype(np.float32)
    ramp = min(len(y), int(0.012 * SR))         # склейка: короткие подъём и спад, чтобы не щёлкало
    if ramp > 1:
        y[:ramp] *= np.linspace(0, 1, ramp, dtype=np.float32)
        y[-ramp:] *= np.linspace(1, 0, ramp, dtype=np.float32)
    left = np.sqrt(np.clip(0.5 - pan / 2, 0, 1))
    right = np.sqrt(np.clip(0.5 + pan / 2, 0, 1))
    return i0, y * left, y * right


def render_game(cut, shots, bank):
    """Звук игры по монтажу: каждый звук плана, попавший в отрезок, — по времени мира плана."""
    out = np.zeros((int(cut.total * SR) + SR, 2), np.float32)
    for c in cut.items:
        if not isinstance(c, Clip) or c.sfx <= 0:
            continue
        s = shots[c.shot]
        window = (c.start, min(cut.total, c.start + c.dur + c.tail))
        for e in s.sounds:
            if e["event"].startswith(SKIP_SOUNDS):
                continue
            data = bank.load(e["file"])
            if data is None or len(data) < 4:
                continue
            r = render_sound(s, e, data, c, window)
            if r is None:
                continue
            i0, left, right = r
            n = min(len(left), len(out) - i0)
            out[i0:i0 + n, 0] += left[:n]
            out[i0:i0 + n, 1] += right[:n]
    return out


# ---------------------------------------------------------------- звуки монтажа

class SfxLibrary:
    """Звуки монтажа из папки: manifest.json — {«вид»: [{«file»: «x.wav», «gain»: 1.0, «sync»: 0.35}, …]},
    вид — whoosh, reverse, sub, drone, impact, braam, riser, tick; sync — момент файла (с), который ложится на момент
    склейки (для whoosh/reverse/riser по умолчанию — конец файла, иначе — начало). Файлы — WAV/FLAC/OGG с лицензией,
    разрешающей такое использование (CC0, бесплатные библиотеки GDC Sonniss, BOOM Library free), только из
    официальных источников; источник и лицензия каждого — в manifest (поля «source», «license»). Вида нет в папке —
    он синтезируется (synth_sfx)."""

    def __init__(self, folder):
        self.folder = folder
        self.kinds = {}
        man = os.path.join(folder, "manifest.json") if folder else None
        if man and os.path.exists(man):
            with open(man, encoding="utf-8") as f:
                self.kinds = json.load(f)
            print(f"звуки монтажа: {man} ({', '.join(sorted(self.kinds))})")
        self.cache = {}

    def get(self, kind, n):
        """n-й звук вида (по кругу): (стерео float32, момент синхронизации в сэмплах, громкость) или None."""
        lst = self.kinds.get(kind)
        if not lst:
            return None
        spec = lst[n % len(lst)]
        path = os.path.join(self.folder, spec["file"])
        if path not in self.cache:
            x, sr = sf.read(path, dtype="float32", always_2d=True)
            if x.shape[1] == 1:
                x = np.repeat(x, 2, axis=1)
            x = x[:, :2]
            if sr != SR:
                g = math.gcd(sr, SR)
                x = resample_poly(x, SR // g, sr // g, axis=0).astype(np.float32)
            self.cache[path] = x
        x = self.cache[path]
        default = len(x) if kind in ("whoosh", "reverse", "riser") else 0
        sync = int(spec["sync"] * SR) if "sync" in spec else default
        return x, sync, float(spec.get("gain", 1.0))


def _noise_band(rng, n, lo, hi):
    return sosfilt(butter(2, [lo, min(hi, SR / 2 - 200)], "band", fs=SR, output="sos"), rng.standard_normal(n))


def synth_sfx(kind, dur, rng):
    """Звуки монтажа синтезом (детерминированно по rng): стерео float32, пик ~1. Пока нет библиотеки."""
    n = max(1, int(dur * SR))
    t = np.arange(n) / SR
    if kind == "whoosh":
        # шум с полосой, идущей вверх, огибающая к концу (к склейке) — рывок воздуха
        x = np.zeros(n)
        seg = 1024
        for a in range(0, n, seg):
            p = a / n
            fc = 350 + 3200 * p ** 2
            x[a:a + seg] = _noise_band(rng, min(seg, n - a) + 256, fc, fc * 2.2)[256:]
        env = (t / dur) ** 2.5 * np.exp(-np.maximum(0, t - dur * 0.9) * 40)
        y = x * env
        pan = np.linspace(-0.5, 0.5, n)
        return _stereo(y, pan)
    if kind == "reverse":
        # «обратный» звук: нарастающий хвост удара, обрыв на склейке
        body = _noise_band(rng, n, 80, 2400) + 0.6 * np.sin(2 * np.pi * 55 * t)
        env = np.exp((t - dur) * 5.5)
        return _stereo(body * env)
    if kind == "sub":
        f = 62 * np.exp(-t * 2.2) + 24
        y = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 1.4)
        y += 0.35 * sosfilt(butter(2, 140, "low", fs=SR, output="sos"), rng.standard_normal(n)) * np.exp(-t * 4)
        return _stereo(np.tanh(y * 1.6))
    if kind == "drone":
        # низкий гул стоп-кадра: два близких тона (биения) и тёмный шум, мягкие края
        y = np.sin(2 * np.pi * 41 * t) + 0.7 * np.sin(2 * np.pi * 41.6 * t + 1) + 0.3 * np.sin(2 * np.pi * 82.3 * t)
        y += 0.5 * sosfilt(butter(2, 220, "low", fs=SR, output="sos"), rng.standard_normal(n))
        edge = np.minimum(1, np.minimum(t / 0.25, (dur - t) / 0.2))
        return _stereo(y * np.clip(edge, 0, 1) * 0.5)
    if kind == "impact":
        # удар: щелчок, тело 45→30 Гц, шумовой хвост
        body = np.sin(2 * np.pi * np.cumsum(45 * np.exp(-t * 3) + 30) / SR) * np.exp(-t * 2.2)
        crack = _noise_band(rng, n, 900, 7000) * np.exp(-t * 35)
        tail = sosfilt(butter(2, 400, "low", fs=SR, output="sos"), rng.standard_normal(n)) * np.exp(-t * 3.5) * 0.5
        return _stereo(np.tanh((body + crack * 0.6 + tail) * 1.5))
    if kind == "braam":
        # «брэм»: медные низы — пилы на одной ноте с расстройкой, фильтр открывается и закрывается
        f0 = 55.0
        saw = sum(((np.cumsum(np.full(n, f0 * d)) / SR) % 1.0 * 2 - 1) for d in (0.995, 1.0, 1.006, 2.003))
        y = np.zeros(n)
        seg = 1024
        for a in range(0, n, seg):
            p = a / SR
            fc = 180 + 1400 * np.exp(-p * 1.6) * min(1, p / 0.06 + 0.2)
            y[a:a + seg] = sosfilt(butter(2, fc, "low", fs=SR, output="sos"), saw[max(0, a - 512):a + seg])[-min(seg, n - a):]
        y += np.sin(2 * np.pi * f0 / 2 * t) * 1.2
        env = np.minimum(1, t / 0.03) * np.exp(-t * 0.9)
        return _stereo(np.tanh(y * env * 0.6), np.full(n, 0.0))
    if kind == "riser":
        noise = np.zeros(n)
        seg = 2048
        for a in range(0, n, seg):
            fc = 250 + 5500 * (a / n) ** 2
            noise[a:a + seg] = _noise_band(rng, min(seg, n - a) + 256, fc, fc * 2.5)[256:]
        tone = np.sin(2 * np.pi * np.cumsum(110 * 2 ** (2 * t / dur)) / SR)
        env = (t / dur) ** 2.2
        return _stereo((noise * 0.8 + tone * 0.25) * env)
    if kind == "tick":
        y = np.sin(2 * np.pi * 70 * t) * np.exp(-t * 7) + _noise_band(rng, n, 1500, 6000) * np.exp(-t * 60) * 0.5
        return _stereo(np.tanh(y * 1.5))
    raise ValueError(kind)


def _stereo(y, pan=None):
    y = np.asarray(y, np.float64)
    peak = np.max(np.abs(y)) + 1e-9
    y = y / peak
    if pan is None:
        return np.stack([y, y], 1).astype(np.float32)
    left = np.sqrt(np.clip(0.5 - pan / 2, 0, 1)) * math.sqrt(2)
    right = np.sqrt(np.clip(0.5 + pan / 2, 0, 1)) * math.sqrt(2)
    return np.stack([y * left, y * right], 1).astype(np.float32)


GAME_SOUNDS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "airstrike", "sounds")


@functools.lru_cache(maxsize=None)
def game_sound(name):
    """Звук мода из его ресурсов (моно OGG) как звук монтажа: (стерео float32, 0, 1.0)."""
    x, sr = sf.read(os.path.join(GAME_SOUNDS, name + ".ogg"), dtype="float32", always_2d=True)
    x = x[:, :1]
    if sr != SR:
        g = math.gcd(sr, SR)
        x = resample_poly(x, SR // g, sr // g, axis=0).astype(np.float32)
    return np.repeat(x, 2, axis=1), 0, 1.0


def render_hits(cut, lib, n):
    out = np.zeros((n, 2), np.float32)
    counters = collections.Counter()
    for h in cut.hits:
        rng = np.random.default_rng(zlib.crc32(f"{h.kind}:{counters[h.kind]}".encode()))   # тот же звук при каждой сборке
        got = game_sound(h.kind[5:]) if h.kind.startswith("game:") else lib.get(h.kind, counters[h.kind])
        counters[h.kind] += 1
        if got is not None:
            y, sync, gain = got
            if h.kind == "drone" and len(y) > int(h.dur * SR):   # гул — ровно на стоп-кадр
                y = y[:int(h.dur * SR)].copy()
                k = min(len(y), int(0.2 * SR))
                y[-k:] *= np.linspace(1, 0, k)[:, None]
        else:
            y, gain = synth_sfx(h.kind, h.dur, rng), 1.0
            sync = len(y) if h.align == "end" else 0
        i = int(round(h.at * SR)) - sync
        a, b = max(0, i), min(n, i + len(y))
        if b > a:
            out[a:b] += y[a - i:b - i] * (h.gain * gain)
    return out


# ---------------------------------------------------------------- музыка и сведение

def music_wav(music):
    path = fetch(music.url, music.sha256, music.file)
    wav = os.path.join(CACHE, os.path.splitext(music.file)[0] + ".wav")
    if not os.path.exists(wav):
        subprocess.run([ffmpeg(), "-loglevel", "error", "-y", "-i", path, "-ar", str(SR), "-ac", "2", wav], check=True)
    x, _ = sf.read(wav, dtype="float32", always_2d=True)
    return x


def render_music(cut, x, n):
    """Куски музыки по раскладке: наплывы равной мощности на стыках, спады, тишина в mutes."""
    out = np.zeros((n, 2), np.float32)
    for p in cut.music:
        src1 = p.src1 if p.src1 is not None else p.src0 + (cut.total - p.trailer_t)
        a = p.src0 - p.xin / 2
        b = src1 + p.xout / 2
        i0 = int(round((p.trailer_t - p.xin / 2) * SR))
        s0, s1 = int(round(a * SR)), int(round(b * SR))
        seg = np.zeros((s1 - s0, 2), np.float32)
        lo, hi = max(0, s0), min(len(x), s1)
        if hi > lo:
            seg[lo - s0:hi - s0] = x[lo:hi]
        m = len(seg)
        env = np.ones(m, np.float32)
        # наплыв — синусом (равная мощность: две фразы на стыке не проваливаются), спад — линейно
        for length, rising, power in ((p.xin, True, True), (p.xout, False, True),
                                      (p.fade_in, True, False), (p.fade_out, False, False)):
            k = min(m, int(length * SR))
            if k <= 1:
                continue
            ramp = np.sin(np.linspace(0, np.pi / 2, k)) if power else np.linspace(0, 1, k)
            if rising:
                env[:k] *= ramp
            else:
                env[-k:] *= ramp[::-1]
        seg *= env[:, None]
        j0, j1 = max(0, i0), min(n, i0 + m)
        if j1 > j0:
            out[j0:j1] += seg[j0 - i0:j1 - i0]
    return out


def mute_env(cut, n, edge=0.02):
    env = np.ones(n, np.float32)
    k = int(edge * SR)
    for t0, t1 in cut.mutes:
        a, b = int(t0 * SR), min(n, int(t1 * SR))
        env[a:b] = 0
        if b + k < n:
            env[b:b + k] = np.minimum(env[b:b + k], np.linspace(0, 1, k))
        if a - k > 0:
            env[a - k:a] = np.minimum(env[a - k:a], np.linspace(1, 0, k))
    return env


def mix(cut, shots, music, lib):
    n = int(cut.total * SR)
    game = render_game(cut, shots, SoundBank())[:n]
    mus = render_music(cut, music_wav(music), n)
    hits = render_hits(cut, lib, n)
    env = mute_env(cut, n)[:, None]
    # тишина после вспышки — и для музыки, и для игры; звуки монтажа в ней не звучат по раскладке (нарастание
    # кончается на вспышке, удар — с приходом волны)
    out = mus * 0.5 * env + game * 0.9 * env + hits * 0.55
    peak = float(np.max(np.abs(out))) + 1e-9
    out = np.tanh(out * (1.25 / max(1.0, peak * 0.8))) * 0.93   # мягкий ограничитель
    fade = int(0.6 * SR)
    out[-fade:] *= np.linspace(1, 0, fade)[:, None]
    return out


# ---------------------------------------------------------------- сборка

def encode(cut, shots, size, vertical, audio, out, jobs, preset, crf, draft):
    w, h = size
    vf = "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p"
    cmd = [ffmpeg(), "-loglevel", "error", "-y",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(FPS), "-i", "-", "-i", audio,
           "-vf", vf, "-c:v", "libx264", "-preset", "veryfast" if draft else preset, "-crf", str(crf),
           *([] if draft else ["-tune", "grain"]),
           "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv",
           "-movflags", "+faststart", "-c:a", "aac", "-b:a", "320k", "-shortest", out]
    enc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    n = int(round(cut.total * FPS))
    for i, buf in enumerate(render_frames(n, jobs, (cut, shots, size, vertical))):
        enc.stdin.write(buf)
        if i % 600 == 0:
            print(f"  {i / FPS:.0f} с из {cut.total:.0f}")
    enc.stdin.close()
    if enc.wait() != 0:
        raise SystemExit("ffmpeg: кодирование не удалось")
    print("готово:", out)


def thumbnail(shots, out, size=THUMB):
    """Обложка: середина стоп-кадра у башни (облёт застывшего взрыва) и логотип."""
    s = shots.get("missile_tower") or next(iter(shots.values()))
    fr = s.freezes()
    v = (fr[0][0] + fr[0][1]) / 2 if fr else s.duration / 2
    img = Image.open(s.frame(int(v * FPS))).convert("RGB")
    w, h = size
    look = Look(w, h)
    a = look.finish(look.grade(np.asarray(img.resize((w, h), Image.LANCZOS))), 7)
    titles = Titles(w, h)
    card = Card(("AIRSTRIKE",), 10.0, "title", fade=0.0)
    a = titles.draw(a, card, 1.0)
    Image.fromarray(a).save(out, optimize=True)
    print("обложка:", out)


def credits_text(music):
    return (f"Music: {music.credit}\n"
            f"Map: {MAP_CREDIT}\n"
            f"     {MAP_LINK}\n"
            f"Sound effects: Freesound contributors (CC0 / CC BY 4.0), full list: {SOUNDS_LINK}\n"
            f"{sound_credits_by()}"
            "Fonts: Russo One, Oswald (SIL Open Font License)\n"
            f"Mod: https://{REPO} — {PLATFORM}\n")


def sound_credits_by():
    """Записи под CC BY из SOUND-CREDITS.md — их авторов нужно назвать."""
    path = os.path.join(ROOT, "SOUND-CREDITS.md")
    if not os.path.exists(path):
        return ""
    lines = []
    with open(path, encoding="utf-8") as f:
        table = f.read().splitlines()
    for ln in table:
        m = re.match(r"\|\s*([^|]+?)\s*\|\s*\[([^\]]+)\]\(([^)]+)\)\s*\|\s*\[(CC BY[^\]]*)\]", ln)
        if m:
            lines.append(f"  “{m[2]}” by {m[1]} ({m[3]}), {m[4]}\n")
    return "".join(lines)


def main():
    ap = argparse.ArgumentParser(description="Монтаж трейлера Airstrike v2")
    ap.add_argument("--draft", action="store_true", help="черновик 960×540 (тизер 540×960), быстрое кодирование")
    ap.add_argument("--out", help="файл трейлера (по умолчанию dist/airstrike-trailer.mp4)")
    ap.add_argument("--rec", action="append", help="папка записи (по умолчанию mod/run/scenario/trailer); можно несколько — "
                    "планы из следующих (пересъёмка) заменяют одноимённые")
    ap.add_argument("--music", choices=sorted(MUSICS), default="eyes")
    ap.add_argument("--sfx", default=SFX_DIR, help="папка звуков монтажа с manifest.json (нет — синтез)")
    ap.add_argument("--only", default="trailer,teaser,thumb", help="что собрать: trailer, teaser, thumb")
    ap.add_argument("--no-blackout", action="store_true",
                    help="без плана блэкаута: в тишине перед сиреной — затишье после шквала (night_after)")
    ap.add_argument("--preset", default="slow", help="предустановка x264 чистового (slow — лучше, medium — быстрее)")
    ap.add_argument("--jobs", type=int, default=min(os.cpu_count() or 1, 6), help="процессов отрисовки")
    args = ap.parse_args()
    only = set(args.only.split(","))
    out = args.out or os.path.join(DIST, "airstrike-trailer-draft.mp4" if args.draft else "airstrike-trailer.mp4")
    base = os.path.splitext(out)[0]
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    shots = pick_takes([load_recording(rec) for rec in args.rec or [REC]])
    if not shots:
        raise SystemExit("в записи нет планов")
    for v in FONTS.values():  # скачать до рабочих процессов
        fetch(*v)
    music = MUSICS[args.music]
    lib = SfxLibrary(args.sfx)
    print("планы:", ", ".join(f"{s.name} {s.duration:.1f}с" for s in shots.values()))
    w0, h0 = Image.open(next(iter(shots.values())).frame(0)).size
    if w0 * 9 != h0 * 16:
        raise SystemExit(f"кадры {w0}×{h0}, а нужно 16:9 — переснять: окно клиента было не того размера")
    jobs = max(1, args.jobs)
    todo = []
    if "trailer" in only:
        todo.append(("трейлер", functools.partial(trailer_edit, blackout=not args.no_blackout), DRAFT if args.draft else SIZE, False, out))
    if "teaser" in only:
        todo.append(("тизер", teaser_edit, TEASER_DRAFT if args.draft else TEASER, True, base + "-teaser.mp4"))
    for label, build, size, vertical, path in todo:
        cut = sfx_layer(resolve(build(music), shots), shots, music)
        print(f"{label}: {cut.total:.1f} с, {len(cut.items)} отрезков, {size[0]}×{size[1]}")
        audio = base + ("-teaser" if vertical else "") + "-audio.wav"
        sf.write(audio, mix(cut, shots, music, lib), SR, subtype="PCM_24")
        encode(cut, shots, size, vertical, audio, path, jobs, args.preset, 23 if args.draft else 16, args.draft)
        os.remove(audio)
        if not vertical and not args.draft:
            lite = base + "-lite.mp4"
            subprocess.run([ffmpeg(), "-loglevel", "error", "-y", "-i", path,
                            "-vf", f"scale={LITE[0]}:{LITE[1]}:flags=lanczos", "-c:v", "libx264", "-preset", "medium",
                            "-crf", "21", "-maxrate", "6M", "-bufsize", "12M", "-pix_fmt", "yuv420p",
                            "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
                            "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", lite], check=True)
            print("лёгкая версия:", lite)
    if "thumb" in only:
        thumbnail(shots, base + "-thumbnail.png")
    with open(base + "-credits.txt", "w", encoding="utf-8") as f:
        f.write(credits_text(music))
    print("строки для описания (YouTube, Modrinth):", base + "-credits.txt")


if __name__ == "__main__":
    main()
