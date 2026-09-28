#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "scipy>=1.11",
#     "soundfile>=0.12",
# ]
# ///
"""Сборка всех звуков мода Airstrike из настоящих записей и синтеза (numpy + scipy + soundfile).

  uv run tools/build_sounds.py      → mod/src/main/resources/assets/airstrike/sounds/*.ogg,
                                        assets/airstrike/sounds.json, SOUND-CREDITS.md

Записи — только с лицензией, разрешающей распространение: CC0 (общественное достояние) и CC BY (с указанием
автора: SOUND-CREDITS.md в корне и credits.txt рядом со звуками в jar). Список — SOURCES ниже; файлы скачиваются
с Freesound (превью высокого качества, ogg) в tools/.sound-cache/ и дальше берутся оттуда.

Как собран каждый звук:
- петли моторов (шахед, ракета, B-2, свист бомбы, ветер, дождь) — стационарный кусок записи без шва (хвост плавно
  вклеен в начало): клиент крутит их бесконечно и сам ставит громкость, тон и Доплер;
- взрывы — по несколько вариантов из разных дублей (sounds.json: игра выбирает случайно), ближние с «низом» от
  синтеза (превью записей режут инфрабас), дальние — настоящие дальние подрывы с эхом от рельефа и домов;
- то, чего не записать (бурение бетонобойной бомбы, звон в ушах, захват цели), — синтез из synth_mod_sounds.py.
Все звуки моно: Minecraft размещает в пространстве только моно.
"""
import json
import os
import sys
import urllib.request
from fractions import Fraction

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import synth_mod_sounds as syn  # noqa: E402

SR = 44100
syn.SR = SR  # синтез — в той же частоте
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "airstrike")
OUT = os.path.join(ASSETS, "sounds")
CACHE = os.path.join(ROOT, "tools", ".sound-cache")
rng = np.random.default_rng(7)

# ---------------------------------------------------------------- источники

CC0 = "CC0 1.0"
BY4 = "CC BY 4.0"
BY3 = "CC BY 3.0"

# id Freesound → (автор, название, лицензия, превью)
SOURCES = {
    # шахед: двухтактный мотор с толкающим винтом — ближе всего сверхлёгкий самолёт с Rotax
    695977: ("wniebelski", "taxing ultralight plane.wav", CC0, "695/695977_924574"),
    412819: ("blukotek", "two-stroke Trabant engine 01.wav", CC0, "412/412819_4451798"),
    # крылатая ракета: турбореактивный двигатель; настоящие Х-101 над Киевом
    152509: ("minian89", "jet_engine.wav", CC0, "152/152509_2467357"),
    824805: ("Invadium", "cruise-missiles-interception-and-fly-by (Kh-101 over Kyiv)", CC0, "824/824805_1011221"),
    168079: ("unfa", "Jet Flyby 1", CC0, "168/168079_1038806"),
    # пуск: стартовые ускорители (записи Минобороны США), рёв ракеты
    184274: ("qubodup", "Launching Anti-Tank Missiles.flac [US DoD]", CC0, "184/184274_71257"),
    182794: ("qubodup", "Rocket Launch.flac", CC0, "182/182794_71257"),
    774270: ("TheLittleCrow", "Rocket Launch Boost and Burning (Version B)", CC0, "774/774270_5672786"),
    613855: ("felix.blume", "Rocket Launch Rumble at Canaveral", CC0, "613/613855_1661766"),
    675750: ("craigsmith", "S32-27 Titan missile launch; long.wav", CC0, "675/675750_2524442"),
    # РСЗО: пуск реактивного снаряда (HIMARS, Минобороны США), вой снарядов на подлёте, разрывы
    854476: ("qubodup", "M142 HIMARS Rocket Launch 8 [US DoD]", CC0, "854/854476_71257"),
    241837: ("Zagge28", "Incoming mortar 4", CC0, "241/241837_4404989"),
    241838: ("Zagge28", "Incoming mortar 3", CC0, "241/241838_4404989"),
    241839: ("Zagge28", "Incoming mortar 2", CC0, "241/241839_4404989"),
    241840: ("Zagge28", "Incoming mortar 1", CC0, "241/241840_4404989"),
    674897: ("craigsmith", "S18-01 Incoming shells; explosions.wav", CC0, "674/674897_2524442"),
    # барражирующий боеприпас: электромотор с винтом (радиоуправляемый самолёт), катапульта
    176973: ("rcEyeSoar", "rc plane fly-by 2.wav", CC0, "176/176973_3283314"),
    854352: ("qubodup", "Quadcopter Drone Flyby", CC0, "854/854352_71257"),
    479922: ("craigsmith", "R01-04-Catapult Launch.wav", CC0, "479/479922_2524442"),
    # B-2 и бомба
    152567: ("minian89", "four_jet_engines.wav", CC0, "152/152567_2467357"),
    437931: ("craigsmith", "G11-25_B-52 Jet Fly By.wav", CC0, "437/437931_2524442"),
    162417: ("qubodup", "Jet Plane Wind Noise Loop of a KC-135 Stratotanker 1.flac", CC0, "162/162417_71257"),
    486035: ("craigsmith", "R18-04-Artillery Shells Fly Overhead.wav", CC0, "486/486035_2524442"),
    483296: ("craigsmith", "R30-12-Large Gun Shells Fly By and Explode.wav", CC0, "483/483296_2524442"),
    # взрывы
    189778: ("qubodup", "Explosive.flac [US DoD]", CC0, "189/189778_71257"),
    182429: ("qubodup", "Explosion [US DoD]", CC0, "182/182429_71257"),
    182431: ("qubodup", "Explosive 1 v2 [DOD 130303].flac", CC0, "182/182431_71257"),
    674910: ("craigsmith", "S16-23 Violent explosion and debris; 2 takes.wav", CC0, "674/674910_2524442"),
    741174: ("qubodup", "Huge Explosion", CC0, "741/741174_71257"),
    741175: ("qubodup", "Massive Explosion", CC0, "741/741175_71257"),
    486018: ("craigsmith", "R12-02-Large Explosions.wav", CC0, "486/486018_2524442"),
    438693: ("craigsmith", "G43-14-25 Distant Explosions.wav", CC0, "438/438693_2524442"),
    438538: ("craigsmith", "G33-32-Distant Bomb Explosion.wav", CC0, "438/438538_2524442"),
    320788: ("Kostrava", "distant explosions", CC0, "320/320788_1134415"),
    741267: ("the_yura", "Destruction of the missile", CC0, "741/741267_2451161"),
    871382: ("KVV_Audio", "EXPLReal_Air Explosion Medium Distance_KVV AUDIO_FREE", BY4, "871/871382_12846320"),
    324277: ("Kostrava", "distant explosion", CC0, "324/324277_1134415"),
    550342: ("Nox_Sound", "Foley_Stones_Falls_Debris_Stereo_DR05.wav", CC0, "550/550342_9250976"),
    483304: ("craigsmith", "R09-58-Large Fire with Debris.wav", CC0, "483/483304_2524442"),
    675967: ("craigsmith", "S10-19 Falling wooden beam; big interior crash; house collapses; long.wav", CC0, "675/675967_2524442"),
    # сирены: Киев (CC BY) и сирена военного времени KVV (CC0)
    676589: ("Romaner66", "Air raid siren in Kyiv", BY4, "676/676589_8949254"),
    727588: ("KVV_Audio", "ALRMMisc_Air Raid Siren In Time Of War_KVV AUDIO_FREE", CC0, "727/727588_12846320"),
    # ядерный удар: гром, ветер, стекло, дождь, счётчик Гейгера
    267551: ("Yoyodaman234", "Thunder2", CC0, "267/267551_2792951"),
    243782: ("bastipictures", "peal of thunder - distant", CC0, "243/243782_997601"),
    350506: ("Spennnyyy", "Extremely Close THUNDER! (no rain)", BY4, "350/350506_5554674"),
    438880: ("craigsmith", "G56-25-Low Wind.wav", CC0, "438/438880_2524442"),
    438346: ("craigsmith", "G26-27-Huge Storm Sequence.wav", CC0, "438/438346_2524442"),
    758207: ("LukaCafuka", "Glass panel shattering", CC0, "758/758207_16236894"),
    336425: ("221Beimesche", "Glass Shattering and Falling", CC0, "336/336425_4663534"),
    453499: ("kyles", "neon tube smash explode shatter glass debris.flac", CC0, "453/453499_612689"),
    583065: ("Profispiesser", "FX SaSc Glass Tiles Shatter Crash.wav", CC0, "583/583065_6667441"),
    860133: ("KVV_Audio", "RAIN_Heavy Rain Outdoor 01_KVV_FREE", BY4, "860/860133_12846320"),
    674113: ("Sanderboah", "Geiger counter (dry)", CC0, "674/674113_13017680"),
}
USED = set()


def src(fid):
    """Запись моно в SR, float64; кэш — tools/.sound-cache."""
    USED.add(fid)
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, f"{fid}.ogg")
    if not os.path.exists(path):
        url = f"https://cdn.freesound.org/previews/{SOURCES[fid][3]}-hq.ogg"
        print("  скачиваю", url)
        urllib.request.urlretrieve(url, path + ".part")
        os.replace(path + ".part", path)
    x, sr = sf.read(path, always_2d=True)
    m = x.mean(axis=1)
    if sr != SR:
        fr = Fraction(SR, sr).limit_denominator(1000)
        m = resample_poly(m, fr.numerator, fr.denominator)
    return m - np.mean(m)


# ---------------------------------------------------------------- инструменты

def cut(x, a, b=None):
    return x[int(a * SR): None if b is None else int(b * SR)].copy()


def fade(x, fi=0.004, fo=0.08):
    x = x.copy()
    a, b = int(fi * SR), int(fo * SR)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b) ** 2
    return x


def speed(x, r):
    """Варипитч, как у плёнки: r > 1 — выше и короче, r < 1 — ниже, длиннее и «больше»."""
    fr = Fraction(r).limit_denominator(200)
    return resample_poly(x, fr.denominator, fr.numerator)


def F(x, lo=None, hi=None, bells=()):
    return syn.F(x, lo, hi, bells)


def rms(x):
    return np.sqrt(np.mean(x ** 2))


def norm(x, rms_db=-12, peak=0.9):
    return syn.norm(x, rms_db, peak)


def pad(x, n):
    return np.pad(x, (0, max(0, n - len(x))))[:n]


def mix(*parts):
    """Смесь (сигнал, громкость в долях RMS), выровненная по длине самого длинного."""
    n = max(len(p) for p, _ in parts)
    out = np.zeros(n)
    for p, g in parts:
        out[:len(p)] += p / max(rms(p), 1e-9) * g
    return out


def env_db(x, hop=0.01):
    h = int(SR * hop)
    e = np.sqrt(np.convolve(x ** 2, np.ones(2 * h) / (2 * h), "same")[::h])
    return 20 * np.log10(e / np.max(e) + 1e-12)


def trim_tail(x, floor_db=-48, fo=0.3):
    """Обрезать хвост, где запись ушла в шум, с плавным затуханием."""
    db = env_db(x)
    loud = np.nonzero(db > floor_db)[0]
    end = min(len(x), int(((loud[-1] if len(loud) else len(db)) + 1) * 0.01 * SR) + int(fo * SR))
    return fade(x[:end], 0.002, fo)


def gate_hiss(x, lo_cut=None):
    """Убрать постоянный фон записи (шум улицы/ветра) там, где идёт хвост, — мягкий экспандер."""
    db = env_db(x)
    g = np.clip((db + 55) / 15, 0.08, 1)
    g = np.repeat(g, int(0.01 * SR))[:len(x)]
    g = np.convolve(g, np.ones(int(0.05 * SR)) / int(0.05 * SR), "same")
    return pad(x * np.pad(g, (0, max(0, len(x) - len(g))), constant_values=g[-1]), len(x))


def punch(x, db=4, t=0.02):
    """Подчеркнуть атаку: первые t секунд громче (транзиент съедается у превью)."""
    n = int(0.25 * SR)
    g = np.ones(len(x))
    tt = np.arange(min(n, len(x))) / SR
    g[:len(tt)] = 1 + (10 ** (db / 20) - 1) * np.exp(-tt / t)
    return x * g


def loop(x, seconds, xf=0.6):
    """Бесшовная петля: хвост плавно (равная мощность) вклеен в начало."""
    return syn.loop(x, seconds, xf)


def join(a, b, xf=1.0):
    """Склейка двух кусков с плавным переходом (равная мощность)."""
    k = int(xf * SR)
    w = np.linspace(0, np.pi / 2, k)
    return np.concatenate([a[:-k], a[-k:] * np.cos(w) + b[:k] * np.sin(w), b[k:]])


def align(x, db=-20, pre=0.03):
    """Сдвинуть начало к удару: первый момент громче (пик − 20 дБ) — в pre секундах от начала."""
    e = env_db(x, 0.002)
    k = max(0, int((np.argmax(e > db) * 0.002 - pre) * SR))
    return fade(x[k:], 0.002, 0.0)


def granular(x, seconds, grain=0.3):
    """Ровная текстура из короткого куска: окна-«зёрна» из случайных мест внахлёст, мощность постоянна —
    из одного пролёта снаряда получается непрерывный вой без «волн» громкости."""
    n, g = int(seconds * SR), int(grain * SR)
    w = np.hanning(g)
    out = np.zeros(n + g)
    for k in range(0, n, g // 4):
        a = rng.integers(0, len(x) - g)
        out[k:k + g] += x[a:a + g] * w
    return out[:n] / np.sqrt(np.sum(w ** 2) / (g / 4))


def sub_thump(dur, f0=48, sweep=10, tau=0.45):
    """Инфранизкий «удар в грудь» — превью записей режут низ ниже ~40 Гц."""
    t = np.arange(int(dur * SR)) / SR
    return np.sin(2 * np.pi * (f0 * t - sweep * t * t)) * np.exp(-t / tau) * np.minimum(1, t / 0.003)


# ---------------------------------------------------------------- запись

EVENTS = {}  # имя события → (субтитр, [файлы])


def write(name, x, event=None, subtitle=None):
    """Файл sounds/<name>.ogg; event — событие sounds.json, в которое он входит вариантом."""
    os.makedirs(OUT, exist_ok=True)
    x = np.clip(x, -1, 1).astype(np.float32)
    # кусками: libsndfile падает, если отдать Vorbis длинный файл одним блоком
    with sf.SoundFile(os.path.join(OUT, name + ".ogg"), "w", SR, 1, format="OGG", subtype="VORBIS",
                      compression_level=0.35) as fh:
        for i in range(0, len(x), 8192):
            fh.write(x[i:i + 8192])
    if event:
        sub, files = EVENTS.setdefault(event, (subtitle, []))
        files.append(name)
    print(f"  {name:26} {len(x) / SR:5.1f} с  RMS {20 * np.log10(rms(x) + 1e-9):6.1f} дБ")


def variants(event, subtitle, name, sigs):
    for i, s in enumerate(sigs, 1):
        write(f"{name}_{i}" if len(sigs) > 1 else name, s, event, subtitle)


# ================================================================ шахед

def drone():
    """Двухтактный мотор с винтом («мопед»): сверхлёгкий самолёт на малом газу, поднятый до оборотов шахеда
    (~100 Гц вспышек), с зерном «Трабанта»; дальний — верха съедены воздухом, эхо от земли."""
    ul = speed(cut(src(695977), 14, 50), 1.42)
    tr = speed(cut(src(412819), 2, 30), 1.12)
    body = mix((F(ul, lo=55, hi=7000), 1.0), (F(tr, lo=120, hi=5000), 0.35))
    write("drone_engine", norm(loop(body, 8.0), -9.5), "drone.engine", "subtitles.airstrike.drone")
    far = syn.reverb(F(body, lo=70, hi=900, bells=[(140, 4, 0.6)]), t60=1.8, mix=0.45)
    write("drone_engine_far", norm(loop(far, 8.0), -10.5), "drone.engine.far", "subtitles.airstrike.drone")


# ================================================================ крылатая ракета

def missile():
    """Турбореактивный двигатель: спереди — вой компрессора, сзади — рёв струи; в пике — форсаж и свист;
    вдали — настоящие Х-101 над Киевом (глухой гул с эхом от домов)."""
    je = cut(src(152509), 12, 62)
    front = mix((F(je, lo=900, hi=12000), 1.0), (F(je, lo=60, hi=900), 0.45))
    write("missile_engine", norm(loop(front, 8.0), -9.5), "missile.engine", "subtitles.airstrike.missile")
    rear = mix((F(je, lo=35, hi=2500, bells=[(160, 5, 1.0)]), 1.0), (F(je, lo=2500, hi=9000), 0.2))
    write("missile_engine_rear", norm(loop(rear, 8.0), -9.5), "missile.engine.rear", "subtitles.airstrike.missile")
    dive = speed(je, 1.12)
    air = cut(src(162417), 0, 6.2)
    dive = mix((F(dive, lo=700, hi=14000), 1.0), (F(dive, lo=50, hi=700), 0.4),
               (pad(np.tile(air, 10), len(dive)), 0.35))
    write("missile_dive", norm(loop(dive, 8.0), -9.5), "missile.dive", "subtitles.airstrike.missile")
    # дальний: гул Х-101 (13..40 с записи — после перехвата, ракеты идут над городом) + наш турбореактивный глухо
    kh = cut(src(824805), 12.5, 40)
    kh = F(kh, lo=45, hi=1400)
    far = mix((kh, 1.0), (syn.reverb(F(je, lo=50, hi=700), t60=2.0, mix=0.5)[:len(kh)], 0.6))
    write("missile_engine_far", norm(loop(far, 12.0, 1.2), -10.5), "missile.engine.far", "subtitles.airstrike.missile")
    # свист на подлёте: вой снаряда над головой, петля; тон ставит клиент
    sh = granular(cut(src(486035), 0.9, 2.1), 10.0)
    whistle = mix((F(sh, lo=500, hi=9000), 1.0), (syn.whistle(len(sh) / SR, 1150), 0.35))
    write("missile_whistle", norm(loop(whistle, 6.5), -10.3), "missile.whistle", "subtitles.airstrike.missile.whistle")


# ================================================================ пуск

def launch():
    """Старт с направляющей: удар воспламенения, рёв стартового ускорителя, уход вдаль (записи Минобороны США,
    пуски ракет). Петля ускорителя — ровный рёв с треском, пока он горит (клиент ведёт её с Доплером)."""
    at = src(184274)
    rl = src(182794)
    sigs = []
    for x, a, b in [(at, 0.0, 9.9), (at, 9.95, 19.9), (rl, 0.5, 7.0), (rl, 19.1, 26.0)]:
        s = cut(x, a, b)
        s = mix((s, 1.0), (pad(sub_thump(1.2, 42, 8, 0.3), len(s)), 0.35))
        sigs.append(norm(punch(trim_tail(s, -50, 0.6), 3), -11, 0.95))
    variants("launch.booster", "subtitles.airstrike.launch", "launch_booster", sigs)
    burn = cut(src(774270), 1, 23)
    crackle = F(cut(src(613855), 20, 42), lo=60, hi=12000)
    boost = mix((F(burn, lo=40, hi=14000), 1.0), (pad(crackle, len(burn)), 0.45))
    write("booster_engine", norm(loop(boost, 9.0), -9.5), "booster.engine", "subtitles.airstrike.launch")

    # отделение ускорителя: хлопок пиропатрона и металлический лязг замков
    sep = []
    for r, f0 in [(1.7, 620), (1.9, 740)]:
        pop = speed(cut(src(182431), 0, 1.4), r)
        n = int(1.2 * SR)
        tt = np.arange(n) / SR
        clang = sum(a * np.sin(2 * np.pi * f0 * k * tt) * np.exp(-tt / (0.25 / k ** 0.5))
                    for k, a in [(1, 1), (1.51, 0.7), (2.23, 0.5), (3.07, 0.35), (4.2, 0.2)])
        clang *= np.minimum(1, tt / 0.001)
        sep.append(norm(fade(mix((pad(pop, n), 1.0), (clang, 0.5)), 0.001, 0.3), -13, 0.95))
    variants("booster.separate", "subtitles.airstrike.launch.separate", "booster_separate", sep)


# ================================================================ РСЗО «Град»

def rocket():
    """Реактивная система залпового огня: каждый снаряд сходит с трубы резким «фш-ш» с треском (пуск HIMARS,
    выше тоном и короче — калибр 122 мм меньше), очередь по полсекунды складывается в сплошной рёв; на подлёте
    снаряды воют (миномётные мины и снаряды над головой), разрывы — жёсткие и сухие, один за другим."""
    hm = src(854476)
    decay = cut(hm, 4.1, 5.85)
    sigs = []
    for a, r in [(0.0, 1.32), (0.9, 1.45), (1.8, 1.38), (2.6, 1.52), (3.1, 1.28)]:
        body = join(cut(hm, a, a + 1.6), decay, 0.7)
        n = len(body)
        tt = np.arange(n) / SR
        # хлопок воспламенения: превью съедает атаку, возвращаем короткий треск и толчок
        crack = F(rng.standard_normal(n), lo=900, hi=12000) * np.exp(-tt / 0.018)
        x = mix((body, 1.0), (crack, 0.5), (sub_thump(n / SR, 60, 20, 0.12), 0.3))
        sigs.append(norm(fade(speed(x, r), 0.001, 0.4), -11, 0.95))
    variants("rocket.launch", "subtitles.airstrike.rocket", "rocket_launch", sigs)

    # вой на подлёте: ровная текстура из середины записей (без начала и разрыва), петля; тон ведёт Доплер
    grains = [cut(src(241840), 0.25, 1.2), cut(src(241838), 0.35, 1.5), cut(src(241837), 0.25, 1.1),
              cut(src(241839), 0.25, 1.15), cut(src(674897), 13.9, 15.3)]
    tex = None
    for g in grains:
        part = granular(F(g, lo=250, hi=11000) / max(rms(g), 1e-9), 3.0, 0.22)[int(0.25 * SR):]
        tex = part if tex is None else join(tex, part, 0.5)
    write("rocket_incoming", norm(loop(tex, 7.0, 0.8), -10), "rocket.incoming", "subtitles.airstrike.rocket.incoming")

    # разрывы: сухой удар и короткий раскат (снаряды рвутся на поверхности), без долгого эха
    shells = src(674897)
    blasts = []
    for a, b in [(4.05, 8.4), (11.1, 13.75), (15.33, 19.1), (19.15, 23.3), (26.25, 31.4)]:
        x = align(cut(shells, a, b), -18, 0.01)
        x = mix((x, 1.0), (pad(sub_thump(0.8, 55, 12, 0.18), len(x)), 0.4))
        # запись — издали, раскат ровный; снаряд рвётся ближе: после удара раскат спадает (−18 дБ за 2 с)
        tt = np.arange(len(x)) / SR
        x = x * 10 ** (-18 * np.clip(tt - 0.12, 0, None) / 2 / 20)
        blasts.append(norm(punch(trim_tail(gate_hiss(x), -46, 0.4), 7, 0.03), -11, 0.95))
    variants("rocket.blast", "subtitles.airstrike.blast", "rocket_blast", blasts)


# ================================================================ барражирующий боеприпас

def loiter():
    """«Ланцет»: электромотор с толкающим винтом — тонкий ровный вой радиоуправляемого самолёта, вдали — жужжание;
    пуск с катапульты — удар и свист без огня; в пике винт взвывает и свистит рассекаемый воздух."""
    rc = cut(src(176973), 0.2, 4.2)
    quad = cut(src(854352), 2.4, 4.6)
    body = mix((F(rc, lo=120, hi=12000), 1.0), (pad(np.tile(F(quad, lo=200, hi=9000), 3), len(rc)), 0.25))
    write("loiter_engine", norm(loop(body, 3.6, 0.4), -10), "loiter.engine", "subtitles.airstrike.loiter")
    far = syn.reverb(F(body, lo=160, hi=2200), t60=1.4, mix=0.4)
    write("loiter_engine_far", norm(loop(far, 3.6, 0.4), -11), "loiter.engine.far", "subtitles.airstrike.loiter")
    # пике: пролёт вплотную, выше тоном, и ветер
    fly = speed(cut(src(176973), 5.0, 8.4), 1.2)
    air = cut(src(162417), 0, 6.2)
    dive = mix((F(fly, lo=150, hi=14000), 1.0), (pad(np.tile(air, 2), len(fly)), 0.35))
    write("loiter_dive", norm(loop(dive, 2.4, 0.3), -9.5), "loiter.dive", "subtitles.airstrike.loiter")
    # катапульта: удар поршня, свист направляющей
    cat = src(479922)
    sigs = []
    for a in (1.25, 5.07, 13.53):
        x = align(cut(cat, a - 0.05, a + 1.6), -20, 0.01)
        x = mix((x, 1.0), (pad(sub_thump(0.5, 70, 25, 0.08), len(x)), 0.4))
        sigs.append(norm(punch(trim_tail(x, -46, 0.3), 4), -12, 0.95))
    variants("loiter.launch", "subtitles.airstrike.loiter.launch", "loiter_launch", sigs)


# ================================================================ B-2 и бомба

def bomber():
    """Четыре турбовентиляторных двигателя B-2 (запись четырёх реактивных), вдали — пролёт B-52 craigsmith."""
    fj = cut(src(152567), 15, 85)
    near = mix((F(fj, lo=30, hi=9000), 1.0), (syn.jet(len(fj) / SR)[:len(fj)], 0.15))
    write("bomber_engine", norm(loop(near, 10.0, 1.0), -11), "bomber.engine", "subtitles.airstrike.bomber")
    b52 = cut(src(437931), 12, 30)
    far = syn.reverb(F(mix((b52, 1.0), (F(fj[:len(b52)], hi=600), 0.7)), lo=28, hi=700), t60=3.0, mix=0.5)
    write("bomber_engine_far", norm(loop(far, 10.0, 1.2), -10.7), "bomber.engine.far", "subtitles.airstrike.bomber")
    # падающая бомба: рвущийся воздух (обтекание) + вой снарядов над головой
    air = np.tile(cut(src(162417), 0, 6.2), 3)
    sh = cut(src(483296), 1.6, 18.3)
    sh = pad(np.tile(sh, 2), len(air))
    fall = mix((F(air, lo=200, hi=12000, bells=[(1100, 6, 0.4)]), 1.0), (F(sh, lo=400, hi=9000), 0.7),
               (syn.bomb_fall(len(air) / SR)[:len(air)], 0.35))
    write("bomb_fall", norm(loop(fall, 8.0), -10.6), "bomb.fall", "subtitles.airstrike.bomb.fall")
    far = syn.reverb(F(fall, lo=60, hi=1200), t60=2.0, mix=0.45)
    write("bomb_fall_far", norm(loop(far, 8.0), -10.8), "bomb.fall.far", "subtitles.airstrike.bomb.fall")
    # бурение бетона: синтез скрежета + настоящий треск ломающихся перекрытий, глухо (бомба в грунте)
    crash = F(cut(src(675967), 10, 30), lo=40, hi=1600)
    dr = syn.drill(20.0)
    write("bomb_drill", norm(loop(mix((dr, 1.0), (pad(crash, len(dr)), 0.55)), 7.0), -11.3),
          "bomb.drill", "subtitles.airstrike.bomb.drill")


# ================================================================ взрывы

def blasts():
    """Взрывы по дальности. Ближний: хлёсткий удар, огненный шар, падающие обломки; «низ» — синтез.
    Дальний: настоящие дальние подрывы с раскатами эха; разные варианты на каждый взрыв."""
    near = []
    for fid, a, b in [(189778, 0.0, 1.95), (189778, 1.98, 4.05), (189778, 4.09, 8.4), (182429, 0, None),
                      (674910, 0.5, 12.5), (182431, 0, None)]:
        s = cut(src(fid), a, b)
        s = trim_tail(s, -55, 0.4)
        s = pad(s, max(len(s), int(3.2 * SR)))
        body = syn.reverb(s, t60=1.8, mix=0.25)
        s = mix((body, 1.0), (pad(sub_thump(2.5, 46, 9, 0.4), len(body)), 0.55))
        near.append(norm(punch(fade(s, 0.001, 0.4), 4), -10.5, 0.97))
    variants("blast.near", "subtitles.airstrike.blast", "blast_near", near)

    # «под ногами»: низкий удар земли — синтез + огромный взрыв ниже на октаву
    subs = []
    for fid, r in [(741174, 0.55), (741175, 0.5)]:
        s = F(speed(cut(src(fid), 0, 6), r), lo=20, hi=260)
        n = len(s)
        subs.append(norm(fade(mix((s, 1.0), (pad(syn.blast_sub(n / SR), n), 0.9)), 0.001, 1.0), -10.7, 0.97))
    variants("blast.sub", "subtitles.airstrike.blast", "blast_sub", subs)

    # дальний: удар и раскаты эха
    far = []
    for fid, a, b in [(438693, 5.93, 13.9), (438693, 14.09, 19.0), (438693, 68.94, 72.3), (438538, 15.4, 25.5),
                      (438538, 47.81, 59.3), (320788, 30.0, 41.9), (320788, 42.02, 51.2), (741267, 4.4, 15.5),
                      (824805, 0.2, 7.0), (871382, 0, None), (324277, 0, None)]:
        s = trim_tail(gate_hiss(cut(src(fid), a, b)), -50, 0.8)
        s = F(s, lo=25, hi=5000)
        far.append(norm(fade(s, 0.003, 0.8), -12.5, 0.95))
    variants("blast.far", "subtitles.airstrike.blast", "blast_far", far)

    # обломки: камни и земля падают на рядом стоящих
    deb = []
    x = src(550342)
    for a, b in [(0.0, 3.8), (4.85, 9.0), (15.17, 23.9), (24.95, 27.0)]:
        deb.append(norm(trim_tail(cut(x, a, b), -45, 0.4), -16, 0.9))
    variants("debris.fall", "subtitles.airstrike.debris", "debris_fall", deb)

    # пожар в воронке: гудящее пламя с треском, разгорается и стихает
    fire = cut(src(483304), 0.2, 14.2)
    tt = np.arange(len(fire)) / SR
    fire *= np.minimum(1, tt / 1.5) * np.clip((tt[-1] - tt) / 5, 0, 1)
    write("blast_fire", norm(fire, -15), "blast.fire", "subtitles.airstrike.fire")


def bunker():
    """Бетонобойная бомба: удар о грунт, подземный взрыв, выброс газов, обвал полостей, толчок."""
    # хлопок-щелчок: N-волна над головой + хлёсткий ближний взрыв
    cr = cut(src(182431), 0, 1.6)
    crack = mix((pad(syn.bomb_crack(), int(2.5 * SR)), 1.0), (pad(F(cr, lo=400), int(2.5 * SR)), 0.6))
    write("bomb_crack", norm(fade(crack, 0.001, 0.3), -15.4), "bomb.crack", "subtitles.airstrike.blast")
    # удар о землю: тяжёлый глухой удар (большой взрыв в два раза медленнее, без верхов) + обломки
    imp = F(speed(cut(src(486018), 16.2, 21.1), 0.6), lo=20, hi=1800)
    imp = mix((imp, 1.0), (pad(syn.bomb_impact(), len(imp)), 0.8), (pad(cut(src(550342), 25.0, 27.0), len(imp)), 0.25))
    write("bomb_impact", norm(fade(imp, 0.001, 0.6), -12.9), "bomb.impact", "subtitles.airstrike.bomb.impact")
    # подземный взрыв: глухой, как из-под земли
    deep = []
    for fid, a, b, r in [(741174, 0, 8, 0.6), (486018, 45.15, 50.0, 0.5)]:
        s = F(speed(cut(src(fid), a, b), r), lo=18, hi=320)
        s = mix((syn.reverb(s, t60=3.0, mix=0.4, hi=400), 1.0), (pad(syn.bomb_deep(), len(s)), 0.8))
        deep.append(norm(fade(s, 0.002, 1.0), -12.3))
    variants("bomb.deep", "subtitles.airstrike.blast", "bomb_deep", deep)
    # выброс газов из воронки: порыв с грохотом (рёв ускорителя, коротко)
    vent = F(cut(src(613855), 30, 34.5), lo=40, hi=3000)
    vent = mix((vent * np.exp(-np.arange(len(vent)) / SR / 1.6), 1.0), (pad(syn.bomb_vent(), len(vent)), 0.6))
    write("bomb_vent", norm(fade(vent, 0.05, 1.8), -11.3), "bomb.vent", "subtitles.airstrike.bomb.vent")
    # обвал полостей: гулкий подземный удар и осыпь
    cav = cut(src(675967), 0, 9)
    cav = mix((syn.reverb(F(cav, lo=30, hi=2500), t60=3.5, mix=0.55, hi=2000), 1.0),
              (pad(syn.bomb_cave(), len(cav)), 0.7))
    write("bomb_cave", norm(fade(cav, 0.002, 1.2), -12.8), "bomb.cave", "subtitles.airstrike.blast")
    # толчок земли: низкий гром, почти инфразвук
    q = F(speed(cut(src(267551), 1.2, 5.2), 0.5), lo=15, hi=140)
    q = mix((q, 1.0), (pad(syn.bomb_quake(), len(q)), 0.9))
    write("bomb_quake", norm(fade(q, 0.05, 1.5), -10.9), "bomb.quake", "subtitles.airstrike.bomb.quake")


# ================================================================ сирены

def sirens():
    """Воздушная тревога — настоящая сирена в Киеве (разгон, вой, спад, эхо улиц); ядерная тревога — сирена
    военного времени (ровный вой ниже, дольше)."""
    kv = src(676589)
    s = cut(kv, 20.5, 47.0)
    write("siren", norm(fade(gate_hiss(s), 0.3, 3.0), -11.7, 0.85), "siren", "subtitles.airstrike.siren")
    al = src(727588)
    a = join(cut(al, 8, 50), cut(al, 74, 97), 1.5)
    write("nuke_alarm", norm(fade(a, 0.5, 4.0), -12.8, 0.85), "nuke.alarm", "subtitles.airstrike.nuke.alarm")


# ================================================================ ядерный удар

def nuke():
    """Ядерный удар. Вблизи — удар фронта (громче грома, перегружен), рёв огненного шара, ураган, звон стекла;
    вдали — глухой удар и долгие раскаты. Пуск МБР — Titan со стола."""
    t = src(675750)
    ln = align(cut(t, 3.0, 42))
    ln = mix((ln, 1.0), (pad(sub_thump(2.5, 34, 5, 0.8), len(ln)), 0.25))
    write("nuke_launch", norm(fade(ln, 0.01, 6.0), -14.6, 0.8), "nuke.launch", "subtitles.airstrike.nuke.launch")

    # удар фронта: огромный взрыв на октаву ниже + удар грома в упор + синтез N-волны и лязга
    big = speed(cut(src(741175), 0, 9), 0.62)
    th = cut(src(350506), 0.9, 12)
    n = int(9.0 * SR)
    crack = mix((pad(big, n), 1.0), (pad(F(th, lo=30), n), 0.6), (pad(syn.nuke_crack(9.0), n), 0.9))
    tt = np.arange(n) / SR
    crack *= 0.35 + 0.65 * np.exp(-tt / 1.2)  # удар, затем спадающий грохот
    crack = crack / np.max(np.abs(crack[:int(0.5 * SR)]))
    slam = np.clip(2.5 * crack, -1, 1) * np.exp(-tt / 0.25) + np.clip(crack, -1, 1) * (1 - np.exp(-tt / 0.25))
    write("nuke_crack", fade(F(slam, hi=9000) * 0.92, 0.001, 1.5), "nuke.crack", "subtitles.airstrike.nuke.blast")

    # далёкий подрыв: дальний взрыв на пол-октавы ниже, затем гром, катящийся по рельефу
    far = []
    for fid, a, b in [(438538, 47.81, 59.3), (438693, 72.38, 81.4)]:
        s = F(speed(align(gate_hiss(cut(src(fid), a, b))), 0.7), lo=20, hi=1500)
        roll = F(cut(src(243782), 4.0, 20.0), lo=20, hi=900)
        x = mix((pad(s, int(16 * SR)), 1.0), (pad(np.pad(roll, (int(1.2 * SR), 0)), int(16 * SR)), 0.7),
                (pad(syn.nuke_boom_far(16.0), int(16 * SR)), 0.6))
        far.append(norm(fade(x, 0.002, 3.0), -12.8))
    variants("nuke.boom_far", "subtitles.airstrike.nuke.blast", "nuke_boom_far", far)

    # рёв: огненный шар и поднятый им ураган — рокот стартующей ракеты за 20 км и шторм
    rr = cut(src(613855), 8, 40)
    storm = F(cut(src(438346), 20, 52), lo=40, hi=3000)
    x = mix((F(rr, lo=18, hi=2500), 1.0), (storm, 0.45), (syn.nuke_roar(32.0)[:len(rr)], 0.6))
    tt = np.arange(len(x)) / SR
    x *= np.minimum(1, tt / 0.25) * np.where(tt < 5, 1, np.cos(np.pi / 2 * np.clip((tt - 5) / (tt[-1] - 5), 0, 1)) ** 2)
    write("nuke_roar", norm(x, -11.5), "nuke.roar", "subtitles.airstrike.nuke.roar")

    # ураганный ветер (петля)
    w = mix((F(cut(src(438880), 10.5, 43), lo=40), 1.0), (F(cut(src(438346), 20, 52.5), lo=60), 0.8),
            (syn.hurricane(32.5)[:int(32.5 * SR)], 0.35))
    write("nuke_wind", norm(loop(w, 10.0, 1.0), -11.5), "nuke.wind", "subtitles.airstrike.nuke.wind")

    # раскаты грома с разных сторон
    rum = []
    for fid, a, b, r in [(267551, 1.0, 14.0, 0.8), (267551, 14.0, 30.0, 0.75), (243782, 3.5, 17.0, 0.85)]:
        s = F(speed(cut(src(fid), a, b), r), lo=18, hi=700)
        rum.append(norm(fade(s, 0.2, 2.0), -13))
    variants("nuke.rumble", "subtitles.airstrike.nuke.rumble", "nuke_rumble", rum)

    # стёкла: окна вылетают по всей улице — много разбитых стёкол вразнобой
    shards = [cut(src(758207), 0, 1.9), cut(src(336425), 0, 3.6), cut(src(453499), 1.0, 4.7),
              cut(src(583065), 0.3, 1.2), cut(src(583065), 4.0, 5.6)]
    glass = []
    for v in range(3):
        n = int(3.5 * SR)
        g = np.zeros(n)
        for j in range(9):
            s = shards[rng.integers(len(shards))]
            s = speed(s, rng.uniform(0.85, 1.2)) * rng.uniform(0.3, 1.0)
            k = int(rng.exponential(0.35) * SR) if j else 0
            if k < n:
                g[k:k + len(s)] += s[:n - k]
        g = F(g, lo=500)
        glass.append(norm(fade(syn.reverb(g, t60=0.9, mix=0.25, hi=8000), 0.001, 0.4), -16.5, 0.8))
    variants("nuke.glass", "subtitles.airstrike.nuke.glass", "nuke_glass", glass)

    write("nuke_tinnitus", syn.nuke_tinnitus(), "nuke.tinnitus")
    rain = mix((F(cut(src(860133), 2, 45), lo=80), 1.0), (syn.black_rain(43.0)[:int(43 * SR)], 0.55))
    write("nuke_rain", norm(loop(rain, 12.0, 1.0), -15.6), "nuke.rain", "subtitles.airstrike.nuke.rain")

    # щелчки счётчика: настоящие, по одному
    g = src(674113)
    db = env_db(g, 0.001)
    peaks = [i for i in range(2, len(db) - 2) if db[i] > -12 and db[i] >= db[i - 2:i + 3].max()]
    clicks = []
    for p in peaks[::max(1, len(peaks) // 12)][:4]:
        k = int(p * 0.001 * SR) - int(0.002 * SR)
        clicks.append(norm(fade(cut(g, k / SR, k / SR + 0.03), 0.0005, 0.01), -18.8, 0.85))
    variants("geiger.click", "subtitles.airstrike.geiger", "geiger_click", clicks)


def misc():
    write("designator_lock", syn.lock_beep(), "designator.lock", "subtitles.airstrike.designator.lock")
    write("silent", np.zeros(int(0.05 * SR)), "silent")


# ---------------------------------------------------------------- sounds.json и авторы

ORDER = ["drone.engine", "drone.engine.far", "launch.booster", "booster.engine", "booster.separate", "missile.engine",
         "missile.engine.rear", "missile.dive", "missile.engine.far", "missile.whistle", "bomber.engine",
         "bomber.engine.far", "bomb.fall", "bomb.fall.far", "bomb.drill", "siren", "blast.near", "blast.sub",
         "blast.far", "debris.fall", "blast.fire", "bomb.crack", "bomb.impact", "bomb.quake", "bomb.deep", "bomb.vent", "bomb.cave",
         "designator.lock", "silent", "nuke.alarm", "nuke.launch", "nuke.crack", "nuke.boom_far", "nuke.roar",
         "nuke.wind", "nuke.rumble", "nuke.glass", "nuke.tinnitus", "nuke.rain", "geiger.click", "rocket.launch",
         "rocket.incoming", "rocket.blast", "loiter.engine",
         "loiter.engine.far", "loiter.dive", "loiter.launch"]


def write_json():
    assert set(ORDER) == set(EVENTS), set(ORDER) ^ set(EVENTS)
    data = {}
    for ev in ORDER:
        sub, files = EVENTS[ev]
        entry = {"subtitle": sub} if sub else {}
        entry["sounds"] = [{"name": f"airstrike:{f}"} for f in files]
        data[ev] = entry
    with open(os.path.join(ASSETS, "sounds.json"), "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
        fh.write("\n")


def write_credits():
    rows = []
    for fid in sorted(USED, key=lambda i: (SOURCES[i][0].lower(), i)):
        author, title, lic, _ = SOURCES[fid]
        rows.append((author, title, lic, f"https://freesound.org/s/{fid}/"))
    lic_url = {CC0: "https://creativecommons.org/publicdomain/zero/1.0/",
               BY4: "https://creativecommons.org/licenses/by/4.0/", BY3: "https://creativecommons.org/licenses/by/3.0/"}
    md = ["# Звуки: авторы и лицензии", "",
          "Звуки мода собраны `tools/build_sounds.py` из записей ниже (обрезка, смешивание, фильтры, смена скорости)",
          "и синтеза (`tools/synth_mod_sounds.py`). Записи под CC0 — общественное достояние; под CC BY — используются",
          "с указанием автора, изменены. Спасибо авторам!", "",
          "| Автор | Запись | Лицензия |", "|---|---|---|"]
    md += [f"| {a} | [{t}]({u}) | [{lic}]({lic_url[lic]}) |" for a, t, lic, u in rows]
    with open(os.path.join(ROOT, "SOUND-CREDITS.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(md) + "\n")
    txt = ["Airstrike sounds are built from the recordings below (cut, mixed, filtered, resampled)",
           "plus synthesis. CC0 = public domain; CC BY = used with attribution, modified.", ""]
    txt += [f"{a} - \"{t}\" - {lic} - {u}" for a, t, lic, u in rows]
    with open(os.path.join(OUT, "credits.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(txt) + "\n")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):  # прежние файлы, которых больше нет в сборке
        if f.endswith(".ogg"):
            os.remove(os.path.join(OUT, f))
    # новые разделы — в конец: генератор случайных чисел общий, так прежние звуки не меняются
    for part in (drone, missile, launch, bomber, blasts, bunker, sirens, nuke, misc, rocket, loiter):
        print(part.__name__)
        part()
    write_json()
    write_credits()
    print("готово:", len(EVENTS), "событий")
