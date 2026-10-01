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

  uv run tools/build_sounds.py           → mod/src/main/resources/assets/airstrike/sounds/*.ogg,
                                            assets/airstrike/sounds.json, SOUND-CREDITS.md
  uv run tools/build_sounds.py blasts    → пересобрать файлы только этих разделов (остальные файлы не трогаются;
                                            sounds.json и авторы — по всем разделам)

Записи — только с лицензией, разрешающей распространение: CC0 (общественное достояние) и CC BY (с указанием
автора: SOUND-CREDITS.md в корне и credits.txt рядом со звуками в jar). Список — SOURCES ниже. Записи берутся
с Freesound: превью (ogg с потерями) — в tools/.sound-cache/, оригиналы без потерь (src(…, orig=True); нужен вход
в Freesound, см. tools/freesound.py) — в tools/.sound-cache/orig/.

Как собран каждый звук:
- петли моторов (шахед, ракета, B-2, свист бомбы, ветер, дождь) — стационарный кусок записи без шва (хвост плавно
  вклеен в начало): клиент крутит их бесконечно и сам ставит громкость, тон и Доплер;
- взрывы (раздел blasts) — из оригиналов, по ракурсам: вблизи, на средней дистанции, вдали, удар низа и эхо вокруг
  (стерео); клиент сводит их по расстоянию (client/sound/BlastMix). Без мягкого ограничителя: громкость — мгновенная
  громкость EBU R128 (master), пики — прозрачный ограничитель не глубже 6 дБ; удар — с первого отсчёта файла;
- то, чего не записать (бурение бетонобойной бомбы, звон в ушах, захват цели), — синтез из synth_mod_sounds.py.
Звуки моно (Minecraft размещает в пространстве только моно), кроме эха взрывов: оно звучит вокруг слушателя, а не
из точки, и играется без места в мире.
У каждого раздела свой генератор случайных чисел (от имени раздела): правка одного раздела не меняет другие.
"""
import json
import os
import sys
import urllib.request
import zlib
from fractions import Fraction

import numpy as np
import soundfile as sf
from scipy.ndimage import minimum_filter1d, uniform_filter1d
from scipy.signal import lfilter, resample_poly

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import freesound  # noqa: E402
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
    # взрывы (раздел blasts — из оригиналов без потерь)
    251401: ("felix.blume", "Dynamite explosion in the mountain", CC0, "251/251401_1661766"),
    475780: ("felix.blume", "Big dynamite explosion in an open mine (Chile)", CC0, "475/475780_1661766"),
    165808: ("sidohzen", "Explosion with debris - authentic. 4kg TNT", CC0, "165/165808_2872744"),
    811474: ("deleted_user_9900733", "UAV explosion in the city", CC0, "811/811474_9900733"),
    125937: ("alienistcog", "merrimack-st-demolition.aif", CC0, "125/125937_296888"),
    160794: ("Evan Cruder", "120527 08 Dynamite 4 [double].WAV", CC0, "160/160794_1241124"),
    82682: ("juskiddink", "Quarry blasting.wav", BY4, "82/82682_649468"),
    866989: ("tim.kahn", "EXPLReal_Centennial Mills Watertower Demolition", BY4, "866/866989_7037"),
    426163: ("Nanashi", "M224 Mortar Impact Medium Distance 02", BY4, "426/426163_61794"),
    426164: ("Nanashi", "M224 Mortar Impact Medium Distance 01", BY4, "426/426164_61794"),
    426166: ("Nanashi", "M224 Mortar Impact Distant 01", BY4, "426/426166_61794"),
    426167: ("Nanashi", "M224 Mortar Impact Distant 03", BY4, "426/426167_61794"),
    182431: ("qubodup", "Explosive 1 v2 [DOD 130303].flac", CC0, "182/182431_71257"),
    741174: ("qubodup", "Huge Explosion", CC0, "741/741174_71257"),
    741175: ("qubodup", "Massive Explosion", CC0, "741/741175_71257"),
    486018: ("craigsmith", "R12-02-Large Explosions.wav", CC0, "486/486018_2524442"),
    438693: ("craigsmith", "G43-14-25 Distant Explosions.wav", CC0, "438/438693_2524442"),
    438538: ("craigsmith", "G33-32-Distant Bomb Explosion.wav", CC0, "438/438538_2524442"),
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
    # сеть: выход подстанции из строя, дуга, гул трансформатора, район гаснет и загорается
    438494: ("craigsmith", "G32-10-Massive Electrical Discharges.wav", CC0, "438/438494_2524442"),
    434359: ("csengeri", "Close lightning strike 2018 07 07", CC0, "434/434359_197070"),
    367456: ("jensfelger", "Jacob's ladder.wav", CC0, "367/367456_6750383"),
    210878: ("Sclolex", "highvoltagearc.wav", CC0, "210/210878_985466"),
    547900: ("nicotep", "EDF_Electrical_substation", CC0, "547/547900_7529214"),
    451933: ("kyles", "switch big breaker metal click on, off", CC0, "451/451933_612689"),
    573936: ("TRP", "Fridge hum whirl stops loud clunk bang", CC0, "573/573936_97550"),
    139970: ("jessepash", "Switch.Big.Power.wav", CC0, "139/139970_968325"),
    262509: ("OBXJohn", "Gym Lights Powering Up", CC0, "262/262509_3719168"),
}
USED = set()


def src(fid, orig=False, stereo=False):
    """Запись в SR, float64: моно, а stereo — два канала (n×2). orig — оригинал без потерь (tools/freesound.py),
    иначе превью Freesound (ogg); кэш — tools/.sound-cache."""
    USED.add(fid)
    if orig:
        path = freesound.original(fid)
    else:
        os.makedirs(CACHE, exist_ok=True)
        path = os.path.join(CACHE, f"{fid}.ogg")
        if not os.path.exists(path):
            url = f"https://cdn.freesound.org/previews/{SOURCES[fid][3]}-hq.ogg"
            print("  скачиваю", url)
            urllib.request.urlretrieve(url, path + ".part")
            os.replace(path + ".part", path)
    x, sr = sf.read(path, always_2d=True)
    x = (np.repeat(x[:, :1], 2, axis=1) if x.shape[1] == 1 else x[:, :2]) if stereo else x.mean(axis=1)
    if sr != SR:
        fr = Fraction(SR, sr).limit_denominator(1000)
        x = resample_poly(x, fr.numerator, fr.denominator, axis=0)
    return x - np.mean(x, axis=0)


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


# ---------------------------------------------------------------- громкость без искажений (взрывы)

def k_weight(x):
    """К-взвешивание ITU-R BS.1770: полка +4 дБ выше ~1,7 кГц и срез ниже ~38 Гц — как слух оценивает громкость."""
    G, Q, fc = 3.99984385397, 0.7071752369554193, 1681.9744509555319
    K, Vh = np.tan(np.pi * fc / SR), 10 ** (G / 20)
    Vb = Vh ** 0.499666774155
    a0 = 1 + K / Q + K * K
    y = lfilter([(Vh + Vb * K / Q + K * K) / a0, 2 * (K * K - Vh) / a0, (Vh - Vb * K / Q + K * K) / a0],
                [1, 2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0], x, axis=0)
    Q, fc = 0.5003270373253953, 38.13547087613982
    K = np.tan(np.pi * fc / SR)
    a0 = 1 + K / Q + K * K
    return lfilter([1, -2, 1], [1, 2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0], y, axis=0)


def loudness(x):
    """Наибольшая мгновенная громкость (окно 400 мс, шаг 100 мс; EBU R128), LUFS; стерео — сумма каналов."""
    y = k_weight(x) ** 2
    y = y if y.ndim == 1 else y.sum(axis=1)
    w, h = int(0.4 * SR), int(0.1 * SR)
    c = np.concatenate([[0], np.cumsum(y)])
    ms = [(c[i + w] - c[i]) / w for i in range(0, max(1, len(y) - w), h)]
    return -0.691 + 10 * np.log10(max(ms) + 1e-15)


def low_level(x):
    """Громкость низа (удар земли): наибольший средний квадрат за 400 мс без К-взвешивания, дБ — К-фильтр срезает
    как раз то, что слышно грудью."""
    y = x ** 2 if x.ndim == 1 else (x ** 2).sum(axis=1)
    w = int(0.4 * SR)
    c = np.concatenate([[0], np.cumsum(y)])
    return 10 * np.log10(max((c[i + w] - c[i]) / w for i in range(0, max(1, len(y) - w), int(0.1 * SR))) + 1e-15)


def limit(x, thr, attack=0.002, release=0.08):
    """Ограничитель пиков с упреждением: усиление падает плавно за attack до пика и возвращается за release —
    без искажений формы, в отличие от мягкого насыщения (tanh)."""
    a = np.abs(x) if x.ndim == 1 else np.max(np.abs(x), axis=1)
    r = np.minimum(1, thr / np.maximum(a, 1e-12))
    w = 2 * int(attack * SR) + 1
    s = uniform_filter1d(minimum_filter1d(r, w), w)  # у пика не больше нужного: окно среднего — внутри окна минимума
    k = 1 - np.exp(-1 / (release * SR))
    g = np.empty_like(s)
    cur = 1.0
    for i, v in enumerate(s):
        cur = v if v < cur else cur + (v - cur) * k
        g[i] = cur
    return x * (g if x.ndim == 1 else g[:, None])


def master(x, lufs, peak_db=-1.0, max_gr=6.0, meter=None):
    """Громкость взрыва без искажений: мгновенная громкость — lufs, пики выше peak_db — прозрачный ограничитель,
    но не глубже max_gr дБ (не хватает — тише целиком). Ограничитель сам немного убавляет громкость — усиление
    подбирается за несколько проходов."""
    meter = meter or loudness
    thr = 10 ** (peak_db / 20)
    top = thr * 10 ** (max_gr / 20) / np.max(np.abs(x))
    g = min(top, 10 ** ((lufs - meter(x)) / 20))
    for _ in range(4):
        y = limit(x * g, thr * 0.995)
        g = min(top, g * 10 ** ((lufs - meter(y)) / 20))
    return limit(x * g, thr * 0.995)


def onset(x, db=-26, pre=0.0005):
    """Начало файла — удар: первый отсчёт громче пика − 26 дБ, за pre секунд до него (клиент играет взрыв в тик
    прихода фронта; тишина в начале файла — пауза между подлётом и взрывом)."""
    m = np.abs(x if x.ndim == 1 else x.mean(axis=1))
    k = max(0, int(np.argmax(m > m.max() * 10 ** (db / 20))) - int(pre * SR))
    y = x[k:].copy()
    n = int(pre * SR)
    if n:
        ramp = np.linspace(0, 1, n)
        y[:n] *= ramp if y.ndim == 1 else ramp[:, None]
    return y


def chans(fn, x, *a, **kw):
    """Обработка моно-функцией каждого канала стерео."""
    return fn(x, *a, **kw) if x.ndim == 1 else np.stack([fn(x[:, c], *a, **kw) for c in range(x.shape[1])], axis=1)


# Поглощение звука воздухом, дБ/км, ISO 9613-1 (20 °C, влажность 70 %), октавы 63 Гц … 8 кГц
AIR_F = [63, 125, 250, 500, 1000, 2000, 4000, 8000]
AIR_DB_KM = [0.1, 0.3, 1.1, 2.8, 5.0, 9.0, 22.9, 76.6]


def air(x, metres):
    """Воздух на пути в metres метров: верха гаснут по ISO 9613-1 (дальняя перспектива из ближней записи)."""
    X = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    db = np.interp(np.log2(np.maximum(f, 1)), np.log2(AIR_F), AIR_DB_KM) * metres / 1000
    H = 10 ** (-db / 20)
    return np.fft.irfft(X * (H if x.ndim == 1 else H[:, None]), len(x), axis=0)


def friedlander(dur, positive, b=1.2):
    """Давление ударной волны в точке (форма Фридлендера): мгновенный скачок, спад через ноль за positive секунд
    и разрежение — то, что делает удар близкого взрыва; записи издалека его теряют."""
    t = np.arange(int(dur * SR)) / SR
    return (1 - t / positive) * np.exp(-b * t / positive)


def widen(x, side):
    """Ширина стерео: боковой сигнал (L − R) × side — эхо от домов и склонов приходит со всех сторон."""
    m, s = (x[:, 0] + x[:, 1]) / 2, (x[:, 0] - x[:, 1]) / 2 * side
    return np.stack([m + s, m - s], axis=1)


# ---------------------------------------------------------------- запись

EVENTS = {}  # имя события → (субтитр, [файлы])
PRELOAD = set()  # события, которые игра декодирует при загрузке ресурсов, а не при первом звуке
WRITING = True  # пишутся ли файлы текущего раздела (python3 build_sounds.py <разделы> — только этих)


def write(name, x, event=None, subtitle=None, level=0.35):
    """Файл sounds/<name>.ogg (моно — вектор, стерео — n×2); event — событие sounds.json, в которое он входит
    вариантом; level — сжатие Vorbis libsndfile (0 — лучшее качество, 0,35 ≈ q6,5)."""
    x = np.clip(x, -1, 1).astype(np.float32)
    if event:
        sub, files = EVENTS.setdefault(event, (subtitle, []))
        files.append(name)
    if not WRITING:
        return
    os.makedirs(OUT, exist_ok=True)
    channels = 1 if x.ndim == 1 else x.shape[1]
    # кусками: libsndfile падает, если отдать Vorbis длинный файл одним блоком
    with sf.SoundFile(os.path.join(OUT, name + ".ogg"), "w", SR, channels, format="OGG", subtype="VORBIS",
                      compression_level=level) as fh:
        for i in range(0, len(x), 8192):
            fh.write(x[i:i + 8192])
    print(f"  {name:26} {len(x) / SR:5.1f} с  RMS {20 * np.log10(rms(x) + 1e-9):6.1f} дБ  {channels} кан.")


def variants(event, subtitle, name, sigs, **kw):
    for i, s in enumerate(sigs, 1):
        write(f"{name}_{i}" if len(sigs) > 1 else name, s, event, subtitle, **kw)


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
    снаряды воют (миномётные мины и снаряды над головой). Разрывы — в разделе blasts."""
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

# Громкость ракурсов взрыва, LUFS (наибольшая мгновенная, EBU R128; у удара низа — без взвешивания, дБ): каждый вариант
# сведён ровно на неё (level), клиент сводит ракурсы по расстоянию с этими же числами (client/sound/BlastMix).
NEAR_LUFS, MID_LUFS, FAR_LUFS, TAIL_LUFS, ROCKET_LUFS, SUB_DB = -13.5, -13.5, -15.0, -15.0, -16.0, -10.0

# Записи взрывов: (id, начало, конец, канал) — из каждой три ракурса одного и того же взрыва: вблизи, на средней
# дистанции и вдали. Ракурсы одного варианта клиент выбирает одним зерном (одинаковое число вариантов), поэтому
# там, где ракурсы звучат вместе (переход по расстоянию), это один взрыв, а не два вразнобой. Канал 1 — только правый
# (у 475780 левый перегружен на ударе).
BLASTS = [(251401, 0.0, 9.5, None), (165808, 2.1, 4.6, None), (811474, 5.1, 15.0, None), (125937, 10.0, 18.0, None),
          (866989, 181.0, 189.0, None), (475780, 0.1, 14.0, 1)]


def level(sigs, lufs, meter=None):
    """Все варианты ракурса одной громкости: клиент сводит ракурсы по расстоянию (BlastMix), считая каждый ровным.
    Вариант, который не дотянул (ограничитель не глубже 6 дБ), — ошибка сборки: взять другую запись или убавить ракурс."""
    for i, x in enumerate(sigs, 1):
        got = (meter or loudness)(x)
        if abs(got - lufs) > 0.3:
            sys.exit(f"вариант {i}: {got:.1f} LUFS, а у ракурса {lufs} LUFS")
    return sigs


def blasts():
    """Взрывы по ракурсам — из оригиналов без потерь, без перегруженных записей. Клиент сводит ракурсы по расстоянию
    (BlastMix): вблизи — удар и тело взрыва, ниже — «удар в грудь», вокруг — эхо (стерео); дальше — средний ракурс
    с эхом от склонов и домов, вдали — раскат без верхов. Вариант выбирает зерно взрыва."""
    PRELOAD.update(["blast.near", "blast.mid", "blast.far", "blast.sub", "blast.tail", "rocket.blast"])
    near, mid, far = [], [], []
    for fid, a, b, ch in BLASTS:
        x = src(fid, orig=True) if ch is None else src(fid, orig=True, stereo=True)[:, ch]
        x = F(onset(cut(x, a, b), -30), lo=35)
        t = np.arange(len(x)) / SR
        # вблизи: удар и тело взрыва, эхо на 10 дБ за секунду тише (его даёт blast.tail); ударная волна — форма
        # Фридлендера, которую записи издалека теряют (положительная фаза ~8 мс — заряд ~50 кг в двух десятках метров)
        n = x[:int(4.2 * SR)] * 10 ** (-10 * np.clip(t[:int(4.2 * SR)] - 0.35, 0, None) / 20)
        shock = F(friedlander(0.15, 0.008), hi=9000)
        n[:len(shock)] += shock * 0.4 * np.max(np.abs(n)) / np.max(np.abs(shock))
        # низ ниже 60 Гц вблизи даёт blast.sub — без него у ближнего ракурса запас по пикам на сам удар
        near.append(master(fade(F(n, lo=60), 0, 0.5), NEAR_LUFS))
        # средняя дистанция (сотни метров): запись как есть — удар и эхо от склонов, домов, леса
        mid.append(master(trim_tail(gate_hiss(x), -50, 0.6), MID_LUFS))
        # вдали (с километр): тот же взрыв через воздух — верха гаснут по ISO 9613-1
        far.append(master(trim_tail(gate_hiss(air(x, 1000)), -50, 0.8), FAR_LUFS))
    variants("blast.near", "subtitles.airstrike.blast", "blast_near", level(near, NEAR_LUFS), level=0.2)
    variants("blast.mid", "subtitles.airstrike.blast", "blast_mid", level(mid, MID_LUFS), level=0.2)
    variants("blast.far", "subtitles.airstrike.blast", "blast_far", level(far, FAR_LUFS), level=0.2)

    # «под ногами»: удар земли и воздуха ниже 200 Гц — низ настоящих больших подрывов (70 т в карьере, скальный
    # подрыв в карьере на октаву ниже) и синтез: затухающий тон 50 → 35 Гц и низкий рокот
    subs = []
    for fid, a, b, ch, r in [(475780, 0.1, 6.0, 1, 1.0), (82682, 6.9, 10.4, None, 0.6)]:
        x = src(fid, orig=True) if ch is None else src(fid, orig=True, stereo=True)[:, ch]
        s = F(speed(onset(cut(x, a, b), -30), r), lo=20, hi=200)
        n = len(s)
        t = np.arange(n) / SR
        thump = np.sin(2 * np.pi * (50 * t - 3.75 * t * t)) * np.exp(-t / 0.4) * np.minimum(1, t / 0.002)
        rumble = F(rng.standard_normal(n), lo=20, hi=160) * np.exp(-t / 1.2)
        x = mix((s, 1.0), (thump, 0.8), (rumble, 0.4))
        subs.append(master(fade(x, 0, 1.0), SUB_DB, meter=low_level))
    variants("blast.sub", "subtitles.airstrike.blast", "blast_sub", level(subs, SUB_DB, low_level), level=0.2)

    # эхо вокруг: те же записи после удара — отражения от склонов, домов и леса, стерео шире; играется без места
    # в мире (прямой звук идёт из точки взрыва, эхо приходит со всех сторон)
    tails = []
    for fid, a, b, side, hi in [(866989, 181.0, 189.0, 1.0, None), (251401, 0.0, 9.5, 1.6, None),
                                (811474, 5.1, 15.0, 2.0, None), (125937, 10.0, 18.0, 1.4, None),
                                (160794, 4.95, 13.0, 1.6, 6000)]:
        x = onset(cut(src(fid, stereo=True, orig=True), a, b), -30)
        t = np.arange(len(x)) / SR
        rise = np.clip((t - 0.08) / 0.32, 0, 1)
        x = chans(F, x * (rise * rise * (3 - 2 * rise))[:, None], lo=60, hi=hi)  # прямой звук убран, низ — у blast.sub
        x = widen(chans(gate_hiss, x), side)
        tails.append(master(chans(trim_tail, x, -50, 0.8), TAIL_LUFS))
    variants("blast.tail", None, "blast_tail", level(tails, TAIL_LUFS), level=0.2)

    # разрывы РСЗО (122 мм): миномётные мины в 600–900 м — сухой удар и короткий раскат
    rockets = []
    for fid, a, b in [(426164, 0.0, 1.6), (426163, 0.0, 1.9), (426167, 0.0, 1.05), (426167, 1.05, 2.0),
                      (426166, 0.0, 1.1)]:
        x = F(onset(cut(src(fid, orig=True), a, b)), lo=30)
        rockets.append(master(fade(x, 0, 0.15), ROCKET_LUFS))
    variants("rocket.blast", "subtitles.airstrike.blast", "rocket_blast", level(rockets, ROCKET_LUFS), level=0.2)

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


# ================================================================ сеть и блэкаут

def spin_down(x, seconds, low=0.35):
    """Гул, который глохнет: обороты (тон) падают до low и громкость — в ноль за seconds, как у остановленного
    трансформатора и моторов квартала."""
    n = int(seconds * SR)
    rate = low + (1 - low) * (1 - np.linspace(0, 1, n)) ** 1.6
    pos = np.cumsum(rate)
    out = np.interp(pos, np.arange(len(x)), x)
    return out * (1 - np.linspace(0, 1, n)) ** 1.3


def grid():
    """Сеть. Выход подстанции из строя: хлопок пробоя (близкий удар молнии + синтезированный низ), затем дуга
    трещит и гаснет. Искры выбитой подстанции — настоящая дуга 20 кВ. Гул работающей — трансформатор
    на подстанции EDF (100 Гц с нечётными гармониками). Квартал гаснет — щелчок автомата и гул, который глохнет;
    загорается — рубильник и гул ламп."""
    crack = F(cut(src(434359), 2.02, 6.0), lo=35)
    arc_sources = [cut(src(438494), 0.33, 4.6), cut(src(438494), 14.42, 18.7), cut(src(367456), 1.0, 5.3)]
    crackle = F(cut(src(210878), 0.2, 2.6), lo=250)
    sigs = []
    for i, arc in enumerate(arc_sources):
        n = int(4.6 * SR)
        t = np.arange(n) / SR
        a = pad(F(arc, lo=90, hi=12000), n) * np.where(t < 0.9, 1, np.exp(-(t - 0.9) / 1.1))
        tail = np.zeros(n)
        k = int((1.6 + 0.3 * i) * SR)
        tail[k:k + len(crackle)] = crackle[:n - k] * np.exp(-np.arange(min(len(crackle), n - k)) / SR / 0.9)
        bang = mix((pad(align(crack, -20, 0.005), n), 1.0), (pad(sub_thump(2.0, 42 + 4 * i, 9, 0.55), n), 0.8))
        x = mix((bang, 1.0), (a, 0.7), (tail, 0.25))
        sigs.append(norm(fade(syn.reverb(punch(x, 5), t60=1.6, mix=0.25), 0.001, 0.6), -11, 0.95))
    variants("grid.fail", "subtitles.airstrike.grid.fail", "grid_fail", sigs)

    arc = F(src(210878), lo=300, hi=14000)
    sparks = []
    for a, b in ((0.5, 1.1), (2.0, 2.5), (3.2, 3.9), (4.4, 4.9)):
        sparks.append(norm(fade(punch(cut(arc, a, b), 3), 0.003, 0.12), -17, 0.9))
    variants("grid.spark", "subtitles.airstrike.grid.spark", "grid_spark", sparks)

    hum = F(gate_hiss(src(547900)), lo=70, hi=3000)
    hums = [norm(fade(cut(hum, a, a + 4.2), 0.8, 0.8), -21, 0.7) for a in (10.0, 41.0)]
    variants("grid.hum", "subtitles.airstrike.grid.hum", "grid_hum", hums)

    clunk = F(cut(src(451933), 0.0, 0.9), lo=60)
    fridge = F(cut(src(573936), 6.55, 8.2), lo=40)
    downs = []
    for i, (a, stop) in enumerate(((20.0, 1.6), (60.0, 2.2))):
        body = spin_down(cut(hum, a, a + 4.0), stop)
        n = int((stop + 0.9) * SR)
        c = np.zeros(n)
        k = int(0.08 * SR)
        c[k:k + len(clunk)] = clunk[:n - k]
        x = mix((pad(body, n), 1.0), (c, 0.9), (pad(fridge if i else np.zeros(1), n), 0.5))
        downs.append(norm(fade(syn.reverb(x, t60=1.0, mix=0.2), 0.002, 0.3), -13, 0.9))
    variants("grid.power_down", "subtitles.airstrike.grid.power_down", "grid_power_down", downs)

    ups = [norm(trim_tail(gate_hiss(F(cut(src(139970), 1.1, 5.2), lo=50)), -50, 0.4), -13, 0.9),
           norm(trim_tail(gate_hiss(F(cut(src(262509), 6.9, 10.8), lo=50)), -50, 0.4), -13, 0.9)]
    variants("grid.power_up", "subtitles.airstrike.grid.power_up", "grid_power_up", ups)


# ---------------------------------------------------------------- sounds.json и авторы

ORDER = ["drone.engine", "drone.engine.far", "launch.booster", "booster.engine", "booster.separate", "missile.engine",
         "missile.engine.rear", "missile.dive", "missile.engine.far", "missile.whistle", "bomber.engine",
         "bomber.engine.far", "bomb.fall", "bomb.fall.far", "bomb.drill", "siren", "blast.near", "blast.sub",
         "blast.far", "blast.mid", "blast.tail", "debris.fall", "blast.fire", "bomb.crack", "bomb.impact", "bomb.quake", "bomb.deep", "bomb.vent", "bomb.cave",
         "designator.lock", "silent", "nuke.alarm", "nuke.launch", "nuke.crack", "nuke.boom_far", "nuke.roar",
         "nuke.wind", "nuke.rumble", "nuke.glass", "nuke.tinnitus", "nuke.rain", "geiger.click", "rocket.launch",
         "rocket.incoming", "rocket.blast", "loiter.engine",
         "loiter.engine.far", "loiter.dive", "loiter.launch", "grid.fail", "grid.spark", "grid.hum", "grid.power_down",
         "grid.power_up"]


def write_json():
    assert set(ORDER) == set(EVENTS), set(ORDER) ^ set(EVENTS)
    data = {}
    for ev in ORDER:
        sub, files = EVENTS[ev]
        entry = {"subtitle": sub} if sub else {}
        entry["sounds"] = [{"name": f"airstrike:{f}", "preload": True} if ev in PRELOAD else {"name": f"airstrike:{f}"}
                           for f in files]
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
    PARTS = (drone, missile, launch, bomber, blasts, bunker, sirens, nuke, misc, rocket, loiter, grid)
    only = set(sys.argv[1:])
    unknown = only - {p.__name__ for p in PARTS}
    if unknown:
        sys.exit(f"нет таких разделов: {', '.join(sorted(unknown))}; есть: {', '.join(p.__name__ for p in PARTS)}")
    for part in PARTS:
        print(part.__name__ + ("" if not only or part.__name__ in only else " (файлы не пишутся)"))
        WRITING = not only or part.__name__ in only
        # свой генератор у каждого раздела: правка одного раздела не сдвигает случайные числа другим
        rng = np.random.default_rng(zlib.crc32(part.__name__.encode()))
        syn.rng = np.random.default_rng(zlib.crc32(("syn." + part.__name__).encode()))
        part()
    write_json()
    write_credits()
    used = {f + ".ogg" for _, files in EVENTS.values() for f in files}
    for f in os.listdir(OUT):  # прежние файлы, которых больше нет в сборке
        if f.endswith(".ogg") and f not in used:
            os.remove(os.path.join(OUT, f))
    print("готово:", len(EVENTS), "событий")
