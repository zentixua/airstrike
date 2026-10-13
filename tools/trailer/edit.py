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
"""Монтаж трейлера «Война двух городов» (~2:10) из записи tools/trailer/record.sh: 53 плана по таблице сценария
(rows-v8), склейки — на доли «Juggernaut» (Scott Buckley, CC BY 4.0) там, где она играет; цвет, кинокаше 2.39:1 в
старте и актах I–II (план 14 раскрывается до 16:9), чат ИИ-ведущего, логотип и конечный титр; звук игры собирается
заново из журнала (те же файлы мода, громкость по расстоянию до камеры, панорама, Доплер) и кладётся под музыку.

    uv run tools/trailer/edit.py [--lang en|ru] [--draft] [--rec ПАПКА …] [--music ФАЙЛ] [--out ФАЙЛ]
    --lang   — слова на экране: en (по умолчанию, Modrinth и YouTube) или ru
    --draft  — быстрый черновик 960×540 (проверить монтаж)
    --rec    — папка записи; несколько — планы из следующих (пересъёмка) заменяют одноимённые; план с текстом
               игры в кадре берётся из записи на языке ролика (en_us или ru_ru, record.sh: INCOMING_LANG)
    --music  — Juggernaut.mp3 (scottbuckley.com.au): один раз, копируется в tools/.trailer-cache (проверка sha256);
               музыка не скачивается
    --slowmo — планы, снятые замедленно (speed < 1), — как сняты; по умолчанию каждый план идёт со скоростью игры
    --jobs   — процессов отрисовки (по умолчанию min(ядер, 6))
    результат: dist/incoming-trailer.mp4 (H.264, 60 fps, AAC), …-lite.mp4 (до 4 Мбит/с, для мессенджеров)
    и …-credits.txt — строки об авторах музыки и звуков для описания ролика

Планы записи — «p%02d» и буква дубля: p10a, p10b, p10c — три склейки плана 10, p39a…p39t — пик. Нет плана в записи —
на его месте тёмный кадр с подписью «p23 · что в кадре» (черновик собирается из того, что снято), список — в выводе.
Якорь склейки (Clip.at) — секунды игры от начала плана, «mark:что» (gone:<тип снаряда> — снаряд пропал, impact — удар
объекта плана: у вывода из повтора и у живого плана с проверкой кадров, release, detonation, video, close, exit) или
«sound:часть события», ±сдвиг; без sync/land он — начало склейки.

Шрифты скачиваются один раз в tools/.trailer-cache (проверка sha256); шрифт чата — растровый шрифт Minecraft из jar
ресурсов игры (mod/build/moddev, есть после сборки мода). Пакеты Python — в блоке script выше (PEP 723, uv run ставит
их сам); ffmpeg — системный или из пакета imageio-ffmpeg.

Память на 1080p (замер): процесс отрисовки ~0.3 ГБ и главный ~0.3 ГБ — ровно весь монтаж (в очереди не больше
2×jobs кадров), кодер x264 slow 2.5 ГБ на 4 ядрах и больше с числом потоков; при --jobs 6 пик ~5–6 ГБ.
"""
import argparse
import bisect
import collections
import glob
import hashlib
import io
import json
import math
import multiprocessing
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
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
MOD_ARTIFACTS = os.path.join(MOD, "build", "moddev", "artifacts")
FPS = 60
SR = 48000
TPS = 20

# ---------------------------------------------------------------- музыка и шрифты (лицензии — в CREDITS)

MUSIC = {
    "file": "juggernaut.mp3",  # копия файла из --music
    # первый — файл в файлах проекта (video/trailer-war/music, по нему сняты доли), второй — тот же трек по прямой
    # ссылке сайта (scottbuckley.com.au/library/wp-content/uploads/2022/07/Juggernaut.mp3, 8 885 000 байт)
    "sha256": ("994ce3d4d2fa61a2e5434c3b11c104a207f2525209840197d88585dd84584f1a",
               "50be286d170f5a08791f1540b110fbbb4c0aa26e841084fceff0c908379e76f6"),
    "credit": 'Music: "Juggernaut" by Scott Buckley – released under CC-BY 4.0. www.scottbuckley.com.au',
}
FONTS = {
    "title": ("https://raw.githubusercontent.com/google/fonts/main/ofl/russoone/RussoOne-Regular.ttf",
              "bc0abcc660bd8b7ad3000ecb2898a27c58a29a50f7ec81652fa12e75148d09df", "RussoOne-Regular.ttf"),
    "text": ("https://raw.githubusercontent.com/google/fonts/main/ofl/oswald/Oswald%5Bwght%5D.ttf",
             "5b38c246e255a12f5712d640d56bcced0472466fc68983d2d0410ec0457c2817", "Oswald.ttf"),
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, digest, name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        print(f"скачиваю {name}")
        req = urllib.request.Request(url, headers={"User-Agent": "zentixua/incoming trailer"})
        with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(path + ".part", path)
    got = sha256(path)
    if got != digest:
        raise SystemExit(f"{name}: sha256 {got} не совпадает — удалите файл из {CACHE}")
    return path


def music_file(src=None):
    """Juggernaut в кэше: из --music (проверка sha256 до копирования), потом — оттуда. Музыка не скачивается."""
    path = os.path.join(CACHE, MUSIC["file"])
    if src:
        got = sha256(src)
        if got not in MUSIC["sha256"]:
            raise SystemExit(f"{src}: sha256 {got} — не тот файл (нужен Juggernaut.mp3, Scott Buckley)")
        if not os.path.exists(path) or sha256(path) != got:
            os.makedirs(CACHE, exist_ok=True)
            shutil.copyfile(src, path + ".part")
            os.replace(path + ".part", path)
    if not os.path.exists(path):
        raise SystemExit(f"нет музыки в {CACHE}: один раз --music <путь к Juggernaut.mp3> "
                         "(Scott Buckley, scottbuckley.com.au/library/juggernaut)")
    got = sha256(path)
    if got not in MUSIC["sha256"]:
        raise SystemExit(f"{path}: sha256 {got} не совпадает — удалите файл и дайте --music заново")
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


# ---------------------------------------------------------------- такт и карта музыки (music.md)

# Сильные доли (каждая вторая, как в Juggernaut.beats.json): 1.974 + k·0.960 с в файле долей (отклонение до 18 мс —
# шаг его анализа), сдвинуты на −47 мс к ударам в звуке после ffmpeg (поток спектра по всему треку; удары 15.36,
# 129.60, 186.24, 207.37 с ложатся на сетку в пределах 6 мс). 125 ударов в минуту: доля 0.48 с.
BEAT0, BEAT = 1.924, 0.95999


def beat(k):
    """Сильная доля k в треке, с."""
    return BEAT0 + k * BEAT


# места трека по карте music.md
T_IN1 = beat(4)        # 0:05.76 — вход акта I
T_FIRE = beat(14)      # 0:15.36 — «FIRE», музыка обрывается
T_IN3 = beat(133)      # 2:09.60 — врывается на вершине сирены
T_OUT3 = beat(192)     # 3:06.24 — последний удар пика, «1»: обрыв
T_IN4 = beat(196)      # 3:10.08 — возвращается тихо под звон
T_LAST = beat(214)     # 3:27.36 — последний удар: волна накрывает людей
T_CHORD = 208.0        # 3:28.0–3:29.6 — самый громкий аккорд: волна накрывает камеру
T_RELEASE = 209.7      # 3:29.7 — обрыв трека: чёрный
T_TAIL = 215.7         # хвост под титулом, к концу тишина
RING = 0.24            # сколько звучит удар обрыва (I — «FIRE», III — «1»), потом тишина

# время трейлера, где трек входит (акты I, III, IV)
AT1, AT3, AT4 = 6.0, 28.0, 100.4


@dataclass
class Part:
    """Кусок трека в трейлере: место трека track — в момент трейлера at, до места трека end; склейки ложатся на
    сетку до места трека snap; громкость — точки (место трека, доля) с линейным переходом."""
    track: float
    at: float
    end: float
    snap: float
    gain: tuple = ()
    fade: float = 0.04  # спад на конце, с

    def trailer(self, track):
        return self.at + track - self.track


PARTS = [
    Part(T_IN1, AT1, T_FIRE + RING, T_FIRE),
    Part(T_IN3, AT3, T_OUT3 + RING, T_OUT3),
    # входит тихо под звон, в полную — на волне (план 47), хвост под титулом стихает к концу
    Part(T_IN4, AT4, T_TAIL, T_RELEASE, gain=((T_IN4, 0.2), (T_IN4 + 3.6, 1.0), (T_TAIL - 2.5, 1.0), (T_TAIL, 0.0)),
         fade=0.0),
]
FIRE_AT = PARTS[0].trailer(T_FIRE)                # 0:15.60
OUT3_AT = PARTS[1].trailer(T_OUT3)                # 1:24.64
LAST_AT = PARTS[2].trailer(T_LAST)                # 1:57.68
CHORD_AT = PARTS[2].trailer(T_CHORD)              # 1:58.32
RELEASE_AT = PARTS[2].trailer(T_RELEASE)          # 2:00.02


def snap(t, grid=2):
    """Время трейлера t — на ближайшую долю трека (grid 1 — сильные доли, 2 — доли, 4 — полудоли), если там играет
    музыка; почти равные — к более сильной доле."""
    for p in PARTS:
        if p.at - 1e-6 <= t <= p.trailer(p.snap) + 1e-6:
            x = (t - p.at + p.track - BEAT0) / BEAT * grid
            lo, hi = math.floor(x), math.ceil(x)
            def strength(k):
                return next((g for g in (1, 2, 4, 8) if (k * g / grid) % 1 < 1e-9), 16)
            best = min((lo, hi), key=lambda k: (round(abs(x - k), 1), strength(k)))
            return p.trailer(BEAT0 + best * BEAT / grid)
    return t


def frame_time(t):
    """Склейка — на ближайший кадр."""
    return round(t * FPS) / FPS


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
        """Время игры от начала плана, с (по нему — якоря и звук; кадры — в frame_at)."""
        return ticks / TPS

    def frame_at(self, sec):
        """Номер кадра под момент игры sec: кадр k снят в k·speed/60 с игры."""
        return int(round(sec * FPS / self.speed))

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
        """Длина плана во времени игры, с."""
        return self.count * self.speed / FPS


def load_recording(rec=REC):
    shots = {}
    timeline = os.path.join(rec, "timeline.jsonl")
    if not os.path.exists(timeline):
        print(f"  ! нет записи {timeline} — её планы будут заглушками")
        return shots
    with open(timeline, encoding="utf-8") as f:
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
    """Момент плана в секундах игры: число, «end», «mark:что» или «sound:часть имени» (#n — n-е совпадение, #-1 —
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
    return max(off, 0.0)


# ---------------------------------------------------------------- монтажный лист

@dataclass
class Clip:
    shot: str           # в таблице — буква дубля («b» → p10b), "" — план целиком (p13) или полное имя (p40a)
    at: object = None   # откуда в плане (секунды игры или якорь, см. anchor); None — удар плана (отметка «impact»
                        # вывода из повтора) на HIT_LAND склейки, без удара — начало плана
    dur: float = None   # длительность в трейлере, с; None — остаток плана (поровну между такими склейками)
    note: str = ""      # что в этой склейке (подпись заглушки)
    land: object = 0.0  # где в склейке окажется момент at: секунды от её начала или «end»
    sync: float = None  # или: время трейлера, на которое ляжет момент at (удар на долю трека)
    open: object = None  # кадр раскрывается из 2.39:1 в 16:9 в этот момент плана (якорь)
    xfade: float = 0.0  # наплыв из прошлого плана, с
    flash: bool = False  # белая вспышка на склейке
    zoom: tuple = (1.0, 1.0)  # наезд: масштаб в начале и в конце
    sfx: float = 1.0    # громкость звука игры
    frame_y: float = 0.5  # какая полоса плана видна в окне 2.39:1: 0 — верхняя, 0.5 — середина
    rate: float = 1.0   # заполняется: секунд игры на секунду трейлера (1 — скорость игры; --slowmo — как снят)
    plan: int = 0       # заполняется: номер плана
    what: str = ""      # заполняется: что в кадре плана
    start: float = 0.0  # заполняется: начало в трейлере
    src: float = 0.0    # заполняется: начало в плане, с игры


@dataclass
class Fill:
    """Ровный кадр: чёрный или белый (level 255)."""
    dur: float = None
    level: int = 0
    plan: int = 0
    start: float = 0.0


@dataclass
class Plan:
    n: int
    start: object       # «м:сс.с» по таблице (на долю трека, если играет музыка) или точное время трейлера, с
    what: str           # что в кадре — подпись заглушки (из таблицы, коротко)
    cuts: list = None   # склейки (Clip, Fill); по умолчанию — один план «pNN» целиком
    grid: int = 2       # сетка склеек: 1 — сильные доли, 2 — доли, 4 — полудоли


@dataclass
class Text:
    at: float           # от начала трейлера, с
    dur: float
    lines: tuple
    style: str = "title"  # chat — строка чата игры; title — логотип; end — конечный титр
    fade: float = 0.35
    row: int = 0        # строка чата снизу


@dataclass
class Hit:
    """Звук трейлера поверх: удар (boom), нарастание к моменту (riser), свист склейки (whoosh), тик (tick), далёкий
    свист снаряда (whistle), обрыв (cut) — звуки монтажа, начатые раньше, обрываются."""
    at: float
    kind: str
    dur: float = 1.0
    gain: float = 1.0


END = 130.0  # 2:10
HIT_LAND = 0.6  # где в склейке ложится удар плана, если склейка не задаёт своего якоря

# 53 плана по таблице сценария (страница сценария, версия 9): время, кадр, что в кадре. Start, I, II — 2.39:1, с плана 15 — 16:9.
# пик (план 39): 19 склеек из ночных дублей пика (p39a–d) и чужих планов с другого их места — дубль, секунда
PEAK = [("p39a", 0.3), ("p39b", 0.4), ("p39c", 0.3), ("p39d", 0.3), ("p24b", 2.0), ("p39a", 1.4), ("p38", 1.5),
        ("p39b", 1.6), ("p39c", 1.3), ("p26", 1.0), ("p39d", 1.4), ("p20", 0.8), ("p39a", 2.4), ("p28", 1.5),
        ("p39b", 2.6), ("p01c", 0.5), ("p39c", 2.2), ("p36", 1.0), ("p39d", 2.4)]

PLANS = [
    # ---- Старт: без музыки, только звук игры; каждая склейка на взрыв; первый кадр — обложка
    Plan(1, "0:00", "Пять самых горячих кадров ролика подряд, по секунде", [
        Clip("a", 0.0, 1.0, "FPV с крылатой ракетой между небоскрёбами"),
        Clip("b", "mark:gone-0.05", 1.0, "удар"),
        Clip("c", "sound:rocket.launch-0.05", 1.0, "залп «Града» изнутри, 40 хвостов вокруг камеры"),
        Clip("d", 0.0, 1.0, "шахеды над головой игрока со стороны затылка"),
        Clip("e", "mark:gone-0.2", None, "ковёр разрывов бежит на камеру"),
        Fill(0.1, 255),  # белая вспышка на 6 кадров, на ней — обрыв
    ]),
    Plan(2, "0:04.4", "Чёрный экран, две строки ИИ-ведущего в чате", [Fill()]),  # строки — TEXTS["chat"]
    # ---- I · Перед ударом: трек входит на долю, склейки на его доли, «FIRE» обрывает музыку
    Plan(3, "0:06", "Рассвет над Zearth: пролёт на высоте 300 м над туманом"),
    Plan(4, "0:08", "Восток, площадь у госкомплекса: люди, у депо поезд"),
    Plan(5, "0:09", "Запад, цех Create: крафтеры собирают крылатую ракету"),
    Plan(6, "0:10", "Колонна солдат вдоль ряда из двадцати пусковых"),
    Plan(7, "0:11", "Бинокль: метка на небоскрёбе Востока за 4 км"),
    Plan(8, "0:12", "Карта наведения: точка за горизонтом"),
    Plan(9, PARTS[0].trailer(13.5), "Пульт STRIKE CONSOLE, палец на «FIRE»"),
    # ---- II · Первый удар: без музыки
    Plan(10, FIRE_AT, "Пуск, три склейки", [
        Clip("a", 0.0, None, "ракеты сходят с пусковых, сбрасывают ускорители"),
        Clip("b", 0.0, None, "«Град» выпускает 40 ракет"),
        Clip("c", 0.0, None, "шахеды уходят со стойки"),
    ]),
    Plan(11, "0:18", "FPV за шахедом над рекой, дымные следы на восток"),
    Plan(12, "0:20", "Деревня: шахеды низко над жителем, он поднимает голову"),
    Plan(13, "0:21", "Восток, крыша: удар в башню за 370 блоков, звук позже"),
    # интерфейс MISSILE THREAT появляется — кадр раскрывается до 16:9 (open — момент плана)
    Plan(14, "0:26", "Сирена над Востоком, кадр раскрывается: MISSILE THREAT",
         [Clip("", "mark:siren-1.0", None, open="mark:siren")]),
    # ---- III · Война 16:9: трек врывается на вершине сирены. Волна 1 · Тревога
    Plan(15, "0:28", "Люди бегут в подземку, FPV ныряет за ними"),
    Plan(16, "0:30", "Подземка: глухие удары, пыль, свет гаснет секциями"),
    Plan(17, "0:32", "Квартира: за окном вспышка, стекло влетает в комнату"),
    Plan(18, "0:33", "FPV вылетает из подземки под удары, второй — за спиной"),
    Plan(19, "0:35", "Игрок на крыше: горизонт в ударах, ракеты над головой", [
        Clip("a", 0.0, None, "горизонт вспыхивает ударами"),
        Clip("b", 0.0, None, "ракеты над головой, он поднимает голову"),
    ]),
    # Волна 2 · Небо
    Plan(20, "0:38", "Запад бьёт всей линией: пусковые и «Грады» разом"),
    Plan(21, "0:40", "С хребта к востоку от города: камера поднимается над кронами, рой шахедов идёт к горящему городу",
         [Clip("", 0.0, None, "подъём над хребтом, рой над головой уходит к городу")]),
    Plan(22, "0:42", "ЗРК: перехватчики из стаканов", [
        Clip("a", 0.0, None, "перехватчики уходят вертикально, шахед разлетается"),
        Clip("b", 0.0, None, "промах, шахед входит в крышу"),
    ]),
    Plan(23, "0:44", "FPV в дыму перехватов: ракета раскалывает дирижабль"),
    Plan(24, "0:46", "Кабина биплана: взлёт по тревоге над горящим аэродромом"),
    Plan(25, "0:48", "Из двора, длинный фокус: рой шахедов идёт над крышами на камеру"),
    # Волна 3 · Улица
    Plan(26, "0:49", "FPV над уличным боем, рядом рвётся снаряд"),
    Plan(27, "0:53", "Глазами игрока: перестрелка с ботами Юга", [
        Clip("a", 0.0, None, "перестрелка"),
        Clip("b", 0.0, None, "на дороге рвутся снаряды"),
        Clip("c", 0.0, None, "перестрелка"),
    ]),
    Plan(28, "0:56", "FPV над перекрёстком: пакет «Града» бежит по улице"),
    Plan(29, "0:59", "Боец у стены, стекло; шахед входит в дом напротив"),
    Plan(30, "1:01", "Глазами бойца: шахед пикирует в камеру",
         [Clip("", "mark:gone", None, "склейка на ударе", land="end")]),
    # Волна 4 · Сквозь огонь
    Plan(31, "1:02", "FPV за шахедом в башню, сквозь огненный шар"),
    Plan(32, "1:04", "…и вылетает над улицей, куда ложится «Град»"),
    Plan(33, "1:05", "Бинокль с крыши: ракеты бьют в башни Востока за 700 блоков, дальше прорисовки",
         [Clip("", "mark:impact", None, "три удара справа налево, звук ещё в пути", land=0.5)]),
    Plan(34, "1:07", "С борта ракеты: пике, SIGNAL LOST, помехи",
         [Clip("", "mark:gone+0.4", None, "помехи режут в следующий план", land="end")]),
    Plan(35, "1:08", "Пусковая Запада: залп, ракеты роняют ускорители"),
    Plan(36, "1:09", "Шахеды Востока накрывают позиции Запада"),
    Plan(37, "1:10", "B-2 на закате: створки, бомба, земля вспучивается", [Clip("", "mark:release-1.2", None)]),
    # Волна 5 · Ночь и пик
    Plan(38, "1:12", "Ночь, дождь: рой шахедов, перехваты, «Град»"),
    # пик: 20 склеек, 12 по доле и 8 по полудоле — всё чаще к отсчёту; удар на каждой склейке
    Plan(39, "1:15", "Пик: все виды оружия, пуски и прилёты вперемешку",
         [Clip(shot, at, 0.48 if i < 12 else 0.24, f"пик {i + 1}/20") for i, (shot, at) in enumerate(PEAK)]
         + [Clip("p39a", 3.0, None, "пик 20/20: ковёр разрывов на камеру")], grid=4),
    # ☢ LAUNCH IN 3, 2, 1 — цифры на три сильные доли, между ними вспышки боя; на «1» всё обрывается
    Plan(40, "1:22.6", "LAUNCH IN 3, 2, 1: второй ключ, вспышки боя", [
        Clip("p40a", 1.0, 0.48, "ключ, LAUNCH IN 3"),
        Clip("b", 0.0, 0.48, "вспышка боя"),
        Clip("p40a", 2.0, 0.48, "LAUNCH IN 2"),
        Clip("c", 0.0, 0.48, "вспышка боя"),
        Clip("p40a", 3.0, None, "LAUNCH IN 1 — обрыв"),
    ]),
    # ---- IV · Конец света: 16 секунд без музыки, камера медленная; трек возвращается с волной
    Plan(41, OUT3_AT + RING, "Тишина: МБР уходит в ночь за спиной расчёта"),
    Plan(42, "1:29", "Огненная точка МБР уходит в ночное небо, издали"),
    Plan(43, "1:31", "Восток: MISSILE ATTACK, бой замирает, медленный наезд"),
    Plan(44, "1:36", "Та же площадь, что в плане 4: горит, пусто"),
    Plan(45, "1:39", "Вспышка: кадр белый, люди отворачиваются",
         [Clip("", "mark:detonation", None, land=0.3)]),
    Plan(46, "1:41", "Блэкаут: город гаснет, огненный шар тянет гриб",
         [Clip("", 1.5, None, "вспышка уже была в плане 45")]),
    Plan(47, "1:44", "С высоты: стена пыли катится по рельефу"),
    Plan(48, "1:47", "С холма: волна закрывает горизонт, башни исчезают",
         [Clip("", "mark:front", None, "стена накрывает взгорок", land=2.4)]),
    Plan(49, "1:50", "Улица: стена несётся между домами на камеру",
         [Clip("", "mark:front", None, "стена у камеры", land=1.6)]),
    # момент плана, когда волна накрывает людей, — на последний удар трека; чёрный — на обрыв трека
    Plan(50, "1:52", "Финал: крыша в км от эпицентра, волна накрывает людей",
         [Clip("", "mark:front", None, "волна накрывает людей — на последний удар", sync=LAST_AT)]),
    # ---- Титул
    Plan(51, RELEASE_AT, "Логотип INCOMING", [Fill()]),
    Plan(52, "2:03", "Конечный титр", [Fill()]),
    # ---- После титров: чёрный, пульт снова — LAUNCH IN 3, обрыв на «2»
    Plan(53, "2:06", "Пульт снова: LAUNCH IN 3, обрыв на «2»", [Fill(0.5), Clip("", 0.0, None), Fill(0.2)]),
]

# для описания ролика (Modrinth, YouTube): музыка CC BY требует указать автора, название, источник и лицензию
CREDITS = f"""{MUSIC["credit"]}
Sound effects: Freesound contributors (CC0 / CC BY 4.0), full list: https://github.com/zentixua/incoming/blob/main/SOUND-CREDITS.md
Fonts: Russo One, Oswald (SIL Open Font License)

Музыка: «Juggernaut», Scott Buckley — лицензия CC BY 4.0. www.scottbuckley.com.au
Звуки: авторы Freesound (CC0 / CC BY 4.0), список: https://github.com/zentixua/incoming/blob/main/SOUND-CREDITS.md
"""

# слова на экране: английские — для Modrinth и YouTube, русские — для своих (--lang ru)
GAME_LANG = {"en": "en_us", "ru": "ru_ru"}  # язык игры для планов с текстом интерфейса в кадре (record.sh: INCOMING_LANG)
TEXTS = {
    "en": {"chat": ("<Claude> West batteries: targets set. Await the order.", "<Claude> Fire on my mark."),
           "end": ("Free and open source · Minecraft 1.21.1 · NeoForge · Modrinth · CurseForge",
                   "War staged with Almighty, an AI game master for your Minecraft server",
                   "All footage captured in-game",
                   "Music: \"Juggernaut\" by Scott Buckley (CC BY 4.0)")},
    "ru": {"chat": ("<Claude> Батареи Запада: цели заданы. Ждать приказа.", "<Claude> Огонь по моей команде."),
           "end": ("Бесплатно и с открытым кодом · Minecraft 1.21.1 · NeoForge · Modrinth · CurseForge",
                   "Войну поставил Almighty — ИИ-ведущий для вашего сервера Minecraft",
                   "Всё снято в игре",
                   "Музыка: «Juggernaut», Scott Buckley (CC BY 4.0)")},
}


def parse_time(s):
    m, sec = s.split(":")
    return int(m) * 60 + float(sec)


def build_edit(lang="en"):
    """
    Монтажный лист по PLANS: начало плана — по таблице, на долю трека, если там играет музыка; склейки внутри — по
    своим длительностям, тоже на доли (сетка плана). Возвращает склейки, титры, звуки монтажа, длину и раскрытие
    кадра (план 14).
    """
    starts = [p.start if isinstance(p.start, float) else snap(parse_time(p.start)) for p in PLANS] + [END]
    starts = [frame_time(t) for t in starts]
    edit = []
    for p, a, b in zip(PLANS, starts, starts[1:]):
        if b <= a:
            raise SystemExit(f"план {p.n}: конец {b:.2f} раньше начала {a:.2f}")
        cuts = p.cuts or [Clip("")]
        fixed = sum(c.dur for c in cuts if c.dur is not None)
        free = sum(1 for c in cuts if c.dur is None)
        rest = (b - a - fixed) / free if free else 0.0
        if rest < 0 or (not free and abs(b - a - fixed) > 1e-6 and len(cuts) > 1):
            print(f"  ! план {p.n}: склейки {fixed:.2f} с, а план {b - a:.2f} с")
        t = a
        for i, c in enumerate(cuts):
            if i == len(cuts) - 1:
                end = b
            else:
                end = frame_time(snap(t + (c.dur if c.dur is not None else rest), p.grid))
                end = min(max(end, t + 1 / FPS), b)
            c.plan, c.start, c.dur = p.n, t, end - t
            if isinstance(c, Clip):
                c.what = p.what
                if not (c.shot[:1] == "p" and c.shot[1:3].isdigit()):  # буква дубля (и «p» — 16-я склейка пика)
                    c.shot = f"p{p.n:02d}{c.shot}"
            edit.append(c)
            t = end
    total = END

    def plan(n):
        """Начало и конец плана n в трейлере."""
        cs = [c for c in edit if c.plan == n]
        return cs[0].start, cs[-1].start + cs[-1].dur

    L = TEXTS[lang]
    p2, p3 = plan(2)
    p51, p52 = plan(51)
    p53 = plan(53)[0]
    chat1, chat2 = p2 + 0.15, p2 + 0.75   # строки появляются по одной; новая поднимает прошлую, как в игре
    texts = [
        Text(chat1, chat2 - chat1, (L["chat"][0],), "chat", fade=0),
        Text(chat2, p3 - chat2, (L["chat"][0],), "chat", fade=0, row=1),
        Text(chat2, p3 - chat2, (L["chat"][1],), "chat", fade=0),
        Text(p51 + 0.5, p52 - p51 - 0.7, ("INCOMING",), "title", fade=0.5),
        Text(p52 + 0.2, p53 - p52 - 0.4, L["end"], "end", fade=0.4),
    ]
    flash = next(c for c in edit if isinstance(c, Fill) and c.level > 0 and c.plan == 1)
    hits = [
        # старт: удар на каждой склейке, на вспышке — обрыв в тишину, тиканье и далёкий свист до входа трека
        *[Hit(c.start, "boom", 1.2, 0.45) for c in edit if c.plan == 1 and isinstance(c, Clip) and c.start > 0],
        Hit(flash.start, "cut"),
        Hit(AT1 - 2 * BEAT / 2, "tick", 0.1, 0.4),
        Hit(AT1 - BEAT / 2, "tick", 0.1, 0.4),
        Hit(p2, "whistle", AT1 - p2, 0.45),
        # «FIRE»: нарастание к нажатию, удар на пуск
        Hit(FIRE_AT, "riser", 2.0, 0.5),
        Hit(FIRE_AT, "boom", 2.5, 0.8),
        # пик: удар на каждой склейке; на «1» всё обрывается
        *[Hit(c.start, "boom", 0.8, 0.3) for c in edit if c.plan == 39],
        Hit(OUT3_AT + RING, "cut"),
        # после титров: тиканье пульта, обрыв на «2»
        *[Hit(t, "tick", 0.1, 0.35) for t in np.arange(p53 + 0.5, total - 0.2, 0.5)],
        Hit(total - 0.2, "cut"),
    ]
    p14 = next(c for c in edit if c.plan == 14 and isinstance(c, Clip))
    return edit, texts, hits, total, p14


def resolve(edit, shots, slowmo=False):
    """Откуда в плане начинается каждая склейка (якоря по записи); раскрытие кадра плана 14 — время трейлера."""
    for c in edit:
        if not isinstance(c, Clip) or c.shot not in shots:
            continue
        s = shots[c.shot]
        c.rate = s.speed if slowmo and s.speed < 1 else 1.0
        if c.at is None:
            # удар — то, ради чего план: он в склейке, после него — огонь и звук
            impact = any(m == "impact" for _, m in s.marks)
            c.at, c.land = ("mark:impact", round(HIT_LAND * c.dur, 3)) if impact else (0.0, 0.0)
        moment = anchor(s, c.at)
        if c.sync is not None:
            lead = c.sync - c.start
        elif c.land == "end":
            lead = c.dur
        else:
            lead = float(c.land)
        c.src = moment - lead * c.rate
        if c.src < 0:
            print(f"  ! {c.shot}: план начинается на {-c.src:.2f} с позже, чем нужно склейке — сдвигаю")
            c.src = 0.0
        # камера снаряда вернулась к игроку («exit») — склейка кончается раньше
        exits = [s.sec(t) for t, m in s.marks if m == "exit"]
        if exits and exits[0] > c.src and c.src + c.dur * c.rate > exits[0] - 0.1:
            c.src = max(0.0, exits[0] - 0.1 - c.dur * c.rate)
        # не хватает кадров: склейка сдвигается к началу плана, а если и так мало — последний кадр стоит
        need = c.dur * c.rate
        if c.src + need > s.duration:
            c.src = max(0.0, s.duration - need)
            if need > s.duration:
                print(f"  ! {c.shot}: план {s.duration:.2f} с, а склейка {need:.2f} с — конец стоит на последнем кадре")


def opening(p14, shots):
    """Когда кадр раскрывается из 2.39:1 в 16:9 (время трейлера): в плане 14, когда появляется MISSILE THREAT."""
    if p14.shot in shots and p14.open is not None:
        t = p14.start + (anchor(shots[p14.shot], p14.open) - p14.src) / p14.rate
    else:
        t = p14.start + (p14.open if isinstance(p14.open, (int, float)) else p14.dur / 2)
    return min(max(t, p14.start), p14.start + p14.dur - 0.5)


def fmt_time(t):
    return f"{int(t // 60)}:{t % 60:05.2f}"


def print_edit(edit, shots):
    print("монтаж:")
    for n in sorted({c.plan for c in edit}):
        cs = [c for c in edit if c.plan == n]
        a, b = cs[0].start, cs[-1].start + cs[-1].dur
        names = " ".join((c.shot + ("" if c.shot in shots else "?")) if isinstance(c, Clip)
                         else ("белый" if c.level else "чёрный") for c in cs)
        print(f"  {n:2d} {fmt_time(a)}–{fmt_time(b)} {b - a:5.2f} с  {names}")


# ---------------------------------------------------------------- картинка

SCOPE = 2.39  # кинокаше старта и актов I–II


class Look:
    """Цвет: мягкая «киношная» кривая, тёплые света и холодные тени, виньетка; кинокаше 2.39:1."""

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
        self.scope_bar = int(round((h - w / SCOPE) / 2))

    def grade(self, img):
        a = np.asarray(img, dtype=np.uint8)
        a = np.stack([self.lut[a[..., i], i] for i in range(3)], axis=-1).astype(np.float32)
        grey = a.mean(axis=2, keepdims=True)
        a = grey + (a - grey) * 1.12
        a *= self.vignette
        return np.clip(a, 0, 255)

    @staticmethod
    def bars(a, bar):
        if bar > 0:
            a[:bar] = 0
            a[-bar:] = 0
        return a


class McFont:
    """Шрифт чата Minecraft: растровые знаки из ресурсов игры (jar client-extra от ModDevGradle), как у клиента —
    первый поставщик со знаком, ширина — до последнего непрозрачного столбца, шаг — ширина + 1, пробел — 4."""

    def __init__(self):
        self.glyphs = {}
        jars = sorted(glob.glob(os.path.join(MOD_ARTIFACTS, "*client-extra*.jar")))
        if not jars:
            return
        with zipfile.ZipFile(jars[-1]) as z:
            providers = json.loads(z.read("assets/minecraft/font/include/default.json"))["providers"]
            for p in providers:
                if p.get("type") != "bitmap":
                    continue
                ns, path = p["file"].split(":", 1)
                img = Image.open(io.BytesIO(z.read(f"assets/{ns}/textures/{path}"))).convert("RGBA")
                rows = p["chars"]
                ch, cw = img.height // len(rows), img.width // len(rows[0])
                scale = p.get("height", 8) / ch
                alpha = np.asarray(img)[..., 3] > 0
                for r, row in enumerate(rows):
                    for c, char in enumerate(row):
                        if char in "\0 " or char in self.glyphs:
                            continue
                        cell = alpha[r * ch:(r + 1) * ch, c * cw:(c + 1) * cw]
                        cols = np.flatnonzero(cell.any(axis=0))
                        width = int(cols[-1]) + 1 if len(cols) else 0
                        mask = cell[:, :width]
                        if scale != 1 and width:
                            mask = np.asarray(Image.fromarray(mask).resize(
                                (max(1, round(width * scale)), max(1, round(ch * scale))), Image.NEAREST))
                        self.glyphs[char] = (mask, int(0.5 + width * scale) + 1, 7 - p["ascent"])

    def width(self, text):
        return sum(4 if ch == " " else self.glyphs.get(ch, (None, 6))[1] for ch in text)

    def draw(self, canvas, x, y, text, rgb):
        """Строка в RGBA-холст (numpy, пиксель = пиксель интерфейса), верх строки — y."""
        for ch in text:
            if ch == " ":
                x += 4
                continue
            g = self.glyphs.get(ch)
            if g is None:
                x += 6
                continue
            mask, adv, top = g
            hh, ww = mask.shape
            y0 = y + top
            sub = canvas[max(y0, 0):y0 + hh, x:x + ww]
            m = mask[max(-y0, 0):max(-y0, 0) + sub.shape[0], :sub.shape[1]]
            sub[m] = (*rgb, 255)
            x += adv


CHAT_SCALE = 3          # guiScale записи (record.sh): пикселей кадра 1080p на пиксель интерфейса
CHAT_WIDTH = 320        # ширина чата игры по умолчанию, пикселей интерфейса
CHAT_BG = (46, 46, 46)  # полоса под строкой: в игре — чёрная полупрозрачная, на чёрном кадре — серая


class Titles:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.fonts = {k: fetch(*v) for k, v in FONTS.items()}
        self.mc = McFont()
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
        key = (text.lines, text.style, text.row)
        if key in self.cache:
            return self.cache[key]
        w, h = self.w, self.h
        s = h / 1080
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        if text.style == "chat":
            self._chat(layer, text.lines[0], text.row)
        elif text.style in ("title", "end"):
            if text.style == "title":
                lines = [(text.lines[0], self.font("title", int(190 * s)), (255, 244, 228))]
            else:
                sizes, colours = (int(40 * s), int(34 * s), int(30 * s), int(26 * s)), ((255, 255, 255), (225, 225, 225), (165, 165, 165), (130, 130, 130))
                lines = [(ln, self.font("text", sizes[i], 400), colours[i]) for i, ln in enumerate(text.lines)]
            boxes = [d.textbbox((0, 0), ln, font=f) for ln, f, _ in lines]
            gap = int(26 * s)
            y = (h - sum(bb[3] - bb[1] for bb in boxes) - gap * (len(lines) - 1)) / 2
            for (ln, f, rgb), bb in zip(lines, boxes):
                x = (w - (bb[2] - bb[0])) / 2 - bb[0]
                self._glow_text(layer, (x, y - bb[1]), ln, f, text.style, rgb)
                y += bb[3] - bb[1] + gap
        self.cache[key] = layer
        return layer

    def _chat(self, layer, line, row):
        """Строка чата, как в игре: полоса 9 пикселей интерфейса, текст с тенью; в левом нижнем углу картинки
        (над кинокаше). Без jar ресурсов игры — шрифт Oswald."""
        w, h = self.w, self.h
        k = CHAT_SCALE * h / 1080
        bottom = h - int(round((h - w / SCOPE) / 2)) - 6 * k   # низ картинки 2.39:1
        y = bottom - (row + 1) * 9 * k
        if self.mc:
            gw = max(CHAT_WIDTH, self.mc.width(line)) + 8
            canvas = np.zeros((9, gw, 4), np.uint8)
            canvas[...] = (*CHAT_BG, 255)
            self.mc.draw(canvas, 5, 2, line, (63, 63, 63))  # тень: на 1 правее и ниже, четверть яркости
            self.mc.draw(canvas, 4, 1, line, (255, 255, 255))
            img = Image.fromarray(canvas, "RGBA").resize((round(gw * k), round(9 * k)), Image.NEAREST)
            layer.alpha_composite(img, (0, int(round(y))))
            return
        f = self.font("text", int(8 * k), 400)
        d = ImageDraw.Draw(layer)
        d.rectangle([0, y, (CHAT_WIDTH + 8) * k, y + 9 * k], fill=(*CHAT_BG, 255))
        d.text((4 * k + 1, y + 0.5 * k + 1), line, font=f, fill=(63, 63, 63, 255))
        d.text((4 * k, y + 0.5 * k), line, font=f, fill=(255, 255, 255, 255))

    def placeholder(self, c):
        """Заглушка неснятого плана: тёмный кадр, «p23 · что в кадре», ниже — склейка и её время."""
        w, h = self.w, self.h
        s = h / 1080
        img = Image.new("RGB", (w, h), (22, 24, 28))
        d = ImageDraw.Draw(img)
        f1, f2 = self.font("text", int(40 * s), 400), self.font("text", int(32 * s), 400)
        line1 = f"{c.shot} · {c.what}"
        line2 = " · ".join(x for x in (c.note, f"{fmt_time(c.start)}–{fmt_time(c.start + c.dur)}", f"{c.dur:.2f} с") if x)
        b1, b2 = d.textbbox((0, 0), line1, font=f1), d.textbbox((0, 0), line2, font=f2)
        y = h / 2 - (b1[3] - b1[1]) - 6 * s
        d.text(((w - (b1[2] - b1[0])) / 2 - b1[0], y - b1[1]), line1, font=f1, fill=(200, 200, 205))
        d.text(((w - (b2[2] - b2[0])) / 2 - b2[0], h / 2 + 6 * s - b2[1]), line2, font=f2, fill=(130, 132, 140))
        return img

    def _glow_text(self, layer, xy, line, font, style, rgb):
        glow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        warm = style == "title"
        ImageDraw.Draw(glow).text(xy, line, font=font, fill=(255, 120, 30, 200) if warm else (0, 0, 0, 200))
        glow = glow.filter(ImageFilter.GaussianBlur(radius=max(4, font.size // 9)))
        layer.alpha_composite(glow)
        ImageDraw.Draw(layer).text(xy, line, font=font, fill=(*rgb, 255))


# состояние рабочих процессов отрисовки
_W = {}


def _init_worker(edit, texts, shots, size, opened):
    _W.update(edit=edit, texts=texts, shots=shots, size=size, opened=opened, look=Look(*size), titles=Titles(*size),
              placeholders={})


def _clip_at(t):
    for c in reversed(_W["edit"]):
        if c.start <= t + 1e-9:
            return c
    return _W["edit"][0]


def _bar(t):
    """Высота кинокаше в момент трейлера: 2.39:1 до раскрытия в плане 14, за 0.5 с уходит, дальше 16:9."""
    full = _W["look"].scope_bar
    t0 = _W["opened"]
    if t < t0:
        return full
    p = min(1.0, (t - t0) / 0.5)
    return int(round(full * (1 - (3 * p * p - 2 * p ** 3))))


def _source(c, t):
    """Кадр плана под время трейлера t (с наездом и кинокаше), float32 HxWx3."""
    w, h = _W["size"]
    bar = _bar(t)
    if isinstance(c, Fill):
        return Look.bars(np.full((h, w, 3), float(c.level), np.float32), bar)
    if c.shot not in _W["shots"]:
        key = id(c)
        if key not in _W["placeholders"]:
            _W["placeholders"][key] = _W["look"].grade(_W["titles"].placeholder(c))
        return Look.bars(_W["placeholders"][key].copy(), bar)
    s = _W["shots"][c.shot]
    k = s.frame_at(c.src + (t - c.start) * c.rate)
    img = Image.open(s.frame(max(0, min(k, s.count - 1)))).convert("RGB")
    if img.size != (w, h):
        img = img.resize((w, h), Image.LANCZOS)
    z0, z1 = c.zoom
    z = z0 + (z1 - z0) * min(1.0, max(0.0, (t - c.start) / c.dur))
    if abs(z - 1) > 1e-3:
        cw, ch = w / z, h / z
        img = img.resize((w, h), Image.BICUBIC, box=((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2))
    if bar > 0 and c.frame_y != 0.5:
        # окно 2.39:1 сдвигается по плану, чтобы кинокаше не срезало интерфейс у края
        top = int(round(2 * bar * c.frame_y))
        window = img.crop((0, top, w, top + h - 2 * bar))
        img = Image.new("RGB", (w, h))
        img.paste(window, (0, bar))
    return Look.bars(_W["look"].grade(img), bar)


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
            fade = 1.0 if tx.fade <= 0 else min(1.0, (t - tx.at) / tx.fade, (tx.at + tx.dur - t) / tx.fade)
            layer = np.asarray(_W["titles"].render(tx), dtype=np.float32)
            alpha = layer[..., 3:4] / 255 * fade
            a = a * (1 - alpha) + layer[..., :3] * alpha
    return np.clip(a, 0, 255).astype(np.uint8).tobytes()


def render_frames(n, jobs, init):
    """Кадры трейлера по порядку. В работе и в очереди — не больше 2×jobs кадров: Pool.imap не ждёт потребителя
    и копит готовые кадры (6 МБ на 1080p), пока кодер x264 медленнее отрисовки, — 16 процессов съели бы больше 8 ГБ."""
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
        self.vanilla = {}
        props = os.path.join(MOD, "build", "moddev", "minecraft_assets.properties")
        if os.path.exists(props):
            conf = dict(line.strip().split("=", 1) for line in open(props) if "=" in line and not line.startswith("#"))
            root = conf["assets_root"]
            index = json.load(open(os.path.join(root, "indexes", conf["asset_index"] + ".json")))["objects"]
            self.vanilla = {k: os.path.join(root, "objects", v["hash"][:2], v["hash"]) for k, v in index.items()}
        else:
            print(f"  ! нет {props} (./gradlew downloadAssets — индекс ресурсов игры; ./gradlew build его не пишет) — ванильные звуки не будут слышны")
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
# голос снаряда: мотор, вой пике, свист — под ним музыка уходит вниз (DUCK_DB), иначе в погоне его не слышно
CRAFT_SOUNDS = (".engine", ".dive", ".whistle")
DUCK_DB = 9.0


def render_sfx(edit, shots, total, bank):
    """Звук игры по монтажу: каждый звук плана, попавший в склейку, — с громкостью и панорамой по камере. Второй
    результат — громкость голоса снарядов (CRAFT_SOUNDS) по отсчётам, для ухода музыки под него."""
    out = np.zeros((int(total * SR) + SR, 2), np.float32)
    craft = np.zeros(len(out), np.float32)
    for c in edit:
        if not isinstance(c, Clip) or c.sfx <= 0 or c.shot not in shots:
            continue
        s = shots[c.shot]
        lo, hi = c.src, c.src + c.dur * c.rate     # секунды игры
        stretch = c.rate ** -0.35 if c.rate < 1 else 1.0  # замедленный план (--slowmo) — звук ниже и длиннее
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
            ts = seg_start + np.arange(n) / SR * c.rate          # время игры для каждого сэмпла
            # громкость, тон, место — по кадрам (движок пишет их для меняющихся звуков)
            ups = s.updates.get(e["id"], [])
            ut = np.array([s.sec(u[0]) for u in ups]) if ups else None
            kf = np.arange(s.frame_at(seg_start), s.frame_at(seg_end) + 2)
            gains, pans, pitches, dists, highs = [], [], [], [], []
            for k in kf:
                tk = k * s.speed / FPS
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
            ft = kf * s.speed / FPS
            g = np.interp(ts, ft, gains)
            pan = np.interp(ts, ft, pans) * 0.75
            pr = np.interp(ts, ft, pitches)
            # позиция в файле: тон — скорость чтения
            pos_samples = (seg_start - t0) * SR * base_pitch + np.cumsum(pr) - pr[0]
            if e["loop"]:
                pos_samples = np.mod(pos_samples, len(data) - 1)
            valid = pos_samples < len(data) - 1
            y = np.interp(np.where(valid, pos_samples, 0), np.arange(len(data)), data) * valid
            dist = float(np.median(dists)) if dists else 0.0
            hf = float(np.median(highs)) if highs else 1.0
            if hf < 0.98:  # фильтр мода (воздух, холм или дом между): доля верхов → срез
                y = sosfilt(butter(2, max(500.0, 20000.0 * hf ** 1.6), "low", fs=SR, output="sos"), y)
            elif dist > 40 and not e["event"].startswith("incoming:"):  # ванильные звуки издалека — воздух глушит верха
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
            if e["event"].startswith("incoming:") and any(k in e["event"] for k in CRAFT_SOUNDS):
                craft[i0:i0 + n] += np.abs(y)
    return out, craft


def smooth(a, k):
    """Скользящее среднее за O(n) (np.convolve на 6 млн отсчётов — минуты)."""
    c = np.concatenate(([0.0], np.cumsum(np.pad(a, (k // 2, k - 1 - k // 2), mode="edge"), dtype=np.float64)))
    return ((c[k:] - c[:-k]) / k).astype(np.float32)


WHISTLE = "incoming:sounds/missile_whistle.ogg"  # далёкий свист старта — звук самого мода


def synth_hit(kind, dur, rng, bank=None):
    """Звуки монтажа: низкий удар (boom), нарастающий шум (riser), свист (whoosh), тик (tick), далёкий свист снаряда
    (whistle — файл мода, издалека: глухо и всё громче)."""
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
    if kind == "tick":
        click = np.sin(2 * np.pi * 2300 * t) * np.exp(-t * 180) + rng.standard_normal(n) * np.exp(-t * 900) * 0.4
        y = sosfilt(butter(2, 900, "high", fs=SR, output="sos"), click)
        return np.stack([y, y], 1)
    if kind == "whistle":
        data = bank.load(WHISTLE) if bank else None
        if data is None or len(data) < n:  # без файла мода — тон, сползающий вниз
            f = 1500 - 300 * t / dur
            data = np.sin(2 * np.pi * np.cumsum(f) / SR) * 0.5
        y = sosfilt(butter(2, 2500, "low", fs=SR, output="sos"), data[:n]) * (t / dur) ** 2.5
        k = min(n, int(0.05 * SR))
        y[-k:] *= np.linspace(1, 0, k)
        return np.stack([y, y], 1)
    raise ValueError(kind)


def music_track(total, path):
    """Музыка по карте PARTS: куски трека на своих местах трейлера, вход — на долю, конец — обрыв после удара."""
    wav = os.path.join(CACHE, "juggernaut.wav")
    if not os.path.exists(wav) or os.path.getmtime(wav) < os.path.getmtime(path):
        subprocess.run([ffmpeg(), "-loglevel", "error", "-y", "-i", path, "-ar", str(SR), "-ac", "2", wav], check=True)
    x, _ = sf.read(wav, dtype="float32", always_2d=True)
    out = np.zeros((int(total * SR) + SR, 2), np.float32)
    env = np.zeros(len(out), np.float32)   # где звучит музыка и насколько громко — для громкости звука игры
    for p in PARTS:
        a, i = int(round(p.track * SR)), int(round(p.at * SR))
        n = min(int(round((p.end - p.track) * SR)), len(x) - a, len(out) - i)
        g = np.ones(n, np.float32)
        if p.gain:
            pts = np.array(p.gain)
            g = np.interp(p.track + np.arange(n) / SR, pts[:, 0], pts[:, 1]).astype(np.float32)
        k = int(0.01 * SR)                 # вход на долю: без щелчка
        g[:k] *= np.linspace(0, 1, k)
        if p.fade > 0:
            k = int(p.fade * SR)
            g[-k:] *= np.linspace(1, 0, k)
        out[i:i + n] += x[a:a + n] * g[:, None]
        env[i:i + n] = np.maximum(env[i:i + n], g)
    return out, env


SFX_WITH_MUSIC, SFX_ALONE = 0.9, 1.3  # звук игры под музыкой и там, где её нет


def mix(edit, hits, shots, total, music_path):
    bank = SoundBank()
    sfx, craft = render_sfx(edit, shots, total, bank)
    music, env = music_track(total, music_path)
    env = smooth(env, int(0.3 * SR))       # громкость звука игры меняется плавно
    sfx *= (SFX_ALONE + (SFX_WITH_MUSIC - SFX_ALONE) * env)[:, None]
    # голос снаряда (средний модуль за 50 мс) от половины громкости музыки и громче — музыка уходит вниз на DUCK_DB,
    # тише — меньше; назад плавно (0,3 с)
    level = smooth(np.abs(music).mean(axis=1) * 0.55, int(0.05 * SR)) + 1e-4
    voice = smooth(craft * SFX_WITH_MUSIC, int(0.05 * SR))
    want = np.clip(2 * voice / level, 0, 1)
    want = np.maximum(want, smooth(want, int(0.3 * SR)))
    music *= (10 ** (-DUCK_DB / 20 * want))[:, None]
    rng = np.random.default_rng(20261012)
    extra = np.zeros_like(music)
    cuts = sorted(h.at for h in hits if h.kind == "cut")
    for h in hits:
        if h.kind == "cut":
            continue
        y = synth_hit(h.kind, h.dur, rng, bank) * h.gain
        i = int(h.at * SR)
        if h.kind == "riser":  # нарастание кончается в момент удара
            i = max(0, i - len(y))
        # обрыв: звук монтажа, начатый раньше, кончается на нём
        stop = next((int(c * SR) for c in cuts if c * SR > i), None)
        if stop is not None and i + len(y) > stop:
            y = y[:stop - i].copy()
            f = min(len(y), int(0.015 * SR))
            y[-f:] *= np.linspace(1, 0, f)[:, None]
        n = min(len(y), len(extra) - i)
        extra[i:i + n] += y[:n]
    out = music * 0.55 + sfx + extra * 0.6
    # мягкий ограничитель
    peak = np.max(np.abs(out)) + 1e-9
    out = np.tanh(out * (1.4 / max(1.0, peak * 0.7))) * 0.95
    return out


# ---------------------------------------------------------------- сборка

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--draft", action="store_true")
    ap.add_argument("--lang", choices=sorted(TEXTS), default="en", help="слова на экране: en — Modrinth и YouTube, ru — для своих")
    ap.add_argument("--out", help="файл (по умолчанию dist/incoming-trailer.mp4, для ru — …-ru.mp4)")
    ap.add_argument("--rec", action="append", help="папка записи (по умолчанию mod/run/scenario/trailer); можно несколько — "
                    "планы из следующих (пересъёмка) заменяют одноимённые")
    ap.add_argument("--music", help="Juggernaut.mp3 (Scott Buckley): один раз, копируется в tools/.trailer-cache")
    ap.add_argument("--slowmo", action="store_true", help="планы, снятые замедленно (speed < 1), — как сняты, а не со скоростью игры")
    ap.add_argument("--preset", default="slow", help="предустановка x264 для чистового (slow — лучше, medium — быстрее)")
    ap.add_argument("--jobs", type=int, default=min(os.cpu_count() or 1, 6),
                    help="процессов отрисовки (по умолчанию не больше 6: каждый ~0.3 ГБ на 1080p)")
    args = ap.parse_args()
    if args.out is None:
        args.out = os.path.join(DIST, "incoming-trailer.mp4" if args.lang == "en" else f"incoming-trailer-{args.lang}.mp4")
    music = music_file(args.music)
    shots = pick_takes([load_recording(rec) for rec in args.rec or [REC]], GAME_LANG[args.lang])
    for v in FONTS.values():  # скачать до рабочих процессов
        fetch(*v)
    edit, texts, hits, total, p14 = build_edit(args.lang)
    used = {c.shot for c in edit if isinstance(c, Clip)}
    print("планы:", ", ".join(f"{s.name} {s.duration:.1f}с" for s in shots.values()) or "нет")
    missing = [c.shot for c in edit if isinstance(c, Clip) and c.shot not in shots]
    if missing:
        # нет плана — на его месте заглушка с подписью: черновик собирается из того, что снято
        print(f"  ! нет планов ({len(set(missing))}), вместо них заглушки:", ", ".join(dict.fromkeys(missing)))
    extra = sorted(set(shots) - used)
    if extra:
        print("  в записи, но не в монтаже:", ", ".join(extra))
    resolve(edit, shots, args.slowmo)
    opened = opening(p14, shots)
    print_edit(edit, shots)
    print(f"кадр раскрывается до 16:9 в {fmt_time(opened)}; длина {fmt_time(total)}")
    size = (1920, 1080)
    if shots:
        w0, h0 = Image.open(next(iter(shots.values())).frame(0)).size
        if w0 * 9 != h0 * 16:
            raise SystemExit(f"кадры {w0}×{h0}, а нужно 16:9 (1920×1080) — переснять: окно клиента было не того размера")
        size = (w0, h0)
    if args.draft:
        size = (960, 540)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    audio = os.path.join(CACHE, "trailer-audio.wav")
    print("звук…")
    sf.write(audio, mix(edit, hits, shots, total, music), SR, subtype="PCM_16")
    print(f"картинка: {int(total * FPS)} кадров {size[0]}×{size[1]}…")
    cmd = [ffmpeg(), "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{size[0]}x{size[1]}", "-r", str(FPS),
           "-i", "-", "-i", audio, "-c:v", "libx264", "-preset", "veryfast" if args.draft else args.preset, "-crf", "23" if args.draft else "15",
           "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-c:a", "aac", "-b:a", "256k", "-shortest", args.out]
    enc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    n = int(total * FPS)
    for i, buf in enumerate(render_frames(n, args.jobs, (edit, texts, shots, size, opened))):
        enc.stdin.write(buf)
        if i % 600 == 0:
            print(f"  {i / FPS:.0f} с")
    enc.stdin.close()
    if enc.wait() != 0:
        raise SystemExit("ffmpeg: кодирование не удалось")
    print("готово:", args.out)
    if not args.draft:
        # лёгкая версия для мессенджеров: тот же монтаж, поток не выше 4 Мбит/с (~65 МБ на 2:10)
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
