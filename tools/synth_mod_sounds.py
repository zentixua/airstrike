#!/usr/bin/env python3
"""Синтез всех звуков мода Airstrike (numpy + ffmpeg, фиксированный сид — результат воспроизводим).

  python3 tools/synth_mod_sounds.py        → mod/src/main/resources/assets/airstrike/sounds/*.ogg

Зацикленные звуки (моторы, свист, бурение) склеены без шва: хвост плавно переходит в начало, поэтому
клиент крутит их бесконечно и сам меняет громкость и тон (Доплер). Разовые — взрывы, удары, сирена.
Ядерный удар (nuke_*, geiger_click): сирена ГО, пуск МБР, вход боеголовки, удар фронта вблизи и далеко, рёв,
раскаты, звон стёкол, звон в ушах, щелчок счётчика Гейгера; петли — ураганный ветер и чёрный дождь.
Чужих файлов нет: всё синтезировано здесь (сирену тоже, в отличие от старого пакета звуков).
"""
import os
import subprocess

import numpy as np

SR = 32000
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "airstrike", "sounds")
rng = np.random.default_rng(42)


# ---------------------------------------------------------------- инструменты

def F(x, lo=None, hi=None, bells=()):
    """Фильтр в частотной области: срез снизу/сверху и «колокола» (частота, дБ, ширина в октавах)."""
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    H = np.ones_like(f)
    if lo:
        H *= 1 / np.sqrt(1 + (lo / np.maximum(f, 1e-3)) ** 4)
    if hi:
        H *= 1 / np.sqrt(1 + (f / hi) ** 4)
    for fc, db, q in bells:
        H *= 1 + (10 ** (db / 20) - 1) * np.exp(-0.5 * ((np.log2(np.maximum(f, 1) / fc)) / q) ** 2)
    return np.fft.irfft(X * H, len(x))


def conv(x, h):
    n = len(x) + len(h) - 1
    N = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, N) * np.fft.rfft(h, N), N)[:len(x)]


def reverb(x, t60=1.2, mix=0.3, pre=0.02, hi=3000):
    n = int(SR * t60 * 1.3)
    t = np.arange(n) / SR
    ir = F(rng.standard_normal(n) * np.exp(-6.9 * t / t60), hi=hi)
    ir[:int(pre * SR)] = 0
    ir /= np.sqrt((ir ** 2).sum())
    y = conv(x, ir)
    return (1 - mix) * x + mix * y * np.std(x) / max(np.std(y), 1e-9)


def norm(x, rms_db=-12, peak=0.9):
    x = x - np.mean(x)
    x = x * (10 ** (rms_db / 20) / max(np.sqrt(np.mean(x ** 2)), 1e-9))
    x = np.tanh(x * 1.2) / np.tanh(1.2)  # мягкий лимитер
    return x * min(1.0, peak / np.max(np.abs(x)))


def env_noise(n, rate, depth):
    """Медленная «турбулентная» модуляция громкости."""
    m = F(rng.standard_normal(n), hi=rate)
    m /= np.max(np.abs(m))
    return 1 + depth * m


def loop(sig, seconds, fade=0.5):
    """Бесшовная петля длиной seconds: хвост сигнала плавно (равная мощность) вклеивается в начало."""
    L, Fd = int(seconds * SR), int(fade * SR)
    assert len(sig) >= L + Fd
    out = sig[:L].copy()
    w = np.linspace(0, np.pi / 2, Fd)
    out[:Fd] = sig[:Fd] * np.sin(w) + sig[L:L + Fd] * np.cos(w)
    return out


def write(name, x):
    os.makedirs(OUT, exist_ok=True)
    raw = os.path.join(OUT, name + ".f32")
    x.astype(np.float32).tofile(raw)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", raw,
                    "-c:a", "libvorbis", "-q:a", "5", os.path.join(OUT, name + ".ogg")], check=True)
    os.remove(raw)


def near_far(sig, name, seconds, far_hi=900, far_t60=1.8):
    """Ближний (полный) и дальний (воздух «съел» верха, эхо от рельефа) варианты одной петли."""
    write(name, norm(loop(sig, seconds), -12))
    far = reverb(F(sig, lo=60, hi=far_hi), t60=far_t60, mix=0.45)
    write(name + "_far", norm(loop(far, seconds), -13))


# ---------------------------------------------------------------- шахед: двухтактный «мопед» и толкающий винт

def drone_engine(dur=7.0, f_rot=100.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    fr = f_rot * (1 + 0.018 * np.sin(2 * np.pi * 0.55 * t) + 0.008 * np.sin(2 * np.pi * 1.7 * t + 1))
    ph = np.cumsum(fr) / SR
    out = np.zeros(n)
    pulse_len = int(0.012 * SR)
    tp = np.arange(pulse_len) / SR
    gains = [1.0, 0.82, 0.93, 0.76]
    edges = np.nonzero(np.diff(np.floor(ph * 4).astype(int)))[0]
    for j, e in enumerate(edges):
        e2 = e + int(rng.normal(0, 0.00012) * SR)
        if e2 < 0 or e2 + pulse_len >= n:
            continue
        f_ex = rng.uniform(520, 700)
        p = np.sin(2 * np.pi * f_ex * tp) * np.exp(-tp / 0.0028) + 0.9 * rng.standard_normal(pulse_len) * np.exp(-tp / 0.0012)
        out[e2:e2 + pulse_len] += p * gains[j % 4] * rng.uniform(0.85, 1.1)
    out = F(out, lo=60, hi=4200, bells=[(104, 4, 0.4), (208, 6, 0.5), (416, 7, 0.4), (1250, 3, 0.7)])
    bp = 2 * ph
    prop = F(2 * (bp - np.floor(bp + 0.5)), lo=80, hi=2500) * 0.55
    mech = F(rng.standard_normal(n), lo=1500, hi=4500) * 0.06 * (1 + 0.5 * np.sin(2 * np.pi * ph * 4))
    x = out / np.std(out) + prop / np.std(prop) * 0.6 + mech / np.std(mech) * 0.25
    return F(np.tanh(x * 1.6), lo=55, hi=5000)


# ---------------------------------------------------------------- крылатая ракета: турбореактивный двигатель

def turbojet(dur=7.0, tone=3100.0, whine_amt=1.0, roar_amt=1.0, hiss_amt=0.4):
    n = int(dur * SR)
    t = np.arange(n) / SR
    ph = np.cumsum(tone * (1 + 0.004 * np.sin(2 * np.pi * 0.3 * t) + 0.002 * np.sin(2 * np.pi * 2.1 * t))) / SR
    whine = np.sin(2 * np.pi * ph) + 0.45 * np.sin(2 * np.pi * 2 * ph + 0.3) + 0.2 * np.sin(2 * np.pi * 1.5 * ph) + 0.15 * np.sin(2 * np.pi * 0.5 * ph)
    whine *= 1 + 0.15 * F(rng.standard_normal(n), hi=20) / 0.05
    band = F(rng.standard_normal(n), bells=[(tone, 30, 0.06)], lo=tone * 0.8, hi=tone * 1.25)
    roar = F(rng.standard_normal(n), lo=40, hi=5000, bells=[(350, 10, 1.2), (120, 6, 0.8)])
    crackle = F((rng.random(n) < 0.0015) * rng.standard_normal(n), lo=800, hi=6000)
    hiss = F(rng.standard_normal(n), lo=2500, hi=9000)
    x = (whine / np.std(whine)) * 0.55 * whine_amt + (band / np.std(band)) * 0.6 * whine_amt + (roar / np.std(roar)) * roar_amt \
        + (crackle / max(np.std(crackle), 1e-9)) * 0.15 * roar_amt + (hiss / np.std(hiss)) * hiss_amt
    return np.tanh(x * 0.9)


def whistle(dur=6.0, f0=1150.0):
    """Свист падающего боеприпаса: тон с дрожанием и «дыхание» воздуха; высоту меняет клиент."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    vib = 1 + 0.006 * np.sin(2 * np.pi * 5.3 * t) + 0.004 * F(rng.standard_normal(n), hi=6) / 0.02
    ph = np.cumsum(f0 * vib) / SR
    tone = np.sin(2 * np.pi * ph) + 0.35 * np.sin(2 * np.pi * 2 * ph) + 0.1 * np.sin(2 * np.pi * 3 * ph)
    air = F(rng.standard_normal(n), lo=f0 * 0.7, hi=f0 * 2.2) * env_noise(n, 9, 0.4)
    return tone / np.std(tone) + 0.5 * air / np.std(air)


# ---------------------------------------------------------------- B-2 и бомба

def jet(dur=8.0):
    n = int(dur * SR)
    roar = F(rng.standard_normal(n), lo=30, hi=2200, bells=[(90, 8, 0.9), (250, 5, 1.0)])
    wh = np.sin(2 * np.pi * np.cumsum(1450 * (1 + 0.003 * np.sin(2 * np.pi * 0.2 * np.arange(n) / SR))) / SR)
    return roar / np.std(roar) * env_noise(n, 1.5, 0.35) + 0.12 * wh


def bomb_fall(dur=7.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    tear = F(rng.standard_normal(n), lo=300, hi=9000, bells=[(1100, 9, 0.35), (2600, 6, 0.5)])
    mod = 1 + 0.01 * np.sin(2 * np.pi * 3.1 * t)
    tone = np.sin(2 * np.pi * np.cumsum(880 * mod) / SR) + 0.4 * np.sin(2 * np.pi * np.cumsum(1760 * mod) / SR)
    rumble = F(rng.standard_normal(n), lo=40, hi=400)
    return tear / np.std(tear) * env_noise(n, 8, 0.4) + 0.35 * tone + 0.6 * rumble / np.std(rumble)


def drill(dur=6.0):
    n = int(dur * SR)
    grind = F(rng.standard_normal(n), lo=60, hi=1400, bells=[(180, 6, 0.8), (420, 4, 0.6)])
    knocks = np.zeros(n)
    for k in np.nonzero(rng.random(n) < 22 / SR)[0]:
        L = min(int(0.05 * SR), n - k)
        knocks[k:k + L] += np.sin(2 * np.pi * 70 * np.arange(L) / SR) * np.exp(-np.arange(L) / (0.012 * SR)) * rng.uniform(0.5, 1.2)
    return F(grind / np.std(grind) * env_noise(n, 6, 0.5) + 1.3 * knocks / np.std(knocks), hi=1200)


# ---------------------------------------------------------------- разовые

def blast_near(dur=4.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    crack = F(rng.standard_normal(n), lo=60, hi=9000) * np.exp(-t / 0.012)
    boom = np.sin(2 * np.pi * (48 * t - 10 * t * t)) * np.exp(-t / 0.45) * 1.8
    body = F(rng.standard_normal(n), lo=30, hi=900) * np.exp(-t / 0.5)
    debris = F(rng.standard_normal(n), lo=900, hi=6000) * np.exp(-t / 1.3) * (rng.random(n) < 0.02)
    x = crack * 1.1 + boom + 0.8 * body / np.std(body) + 0.25 * debris / max(np.std(debris), 1e-9)
    return norm(reverb(x, t60=1.6, mix=0.3), -8, 0.95)


def blast_sub(dur=5.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    crack = rng.standard_normal(n) * np.exp(-t / 0.015)
    thump = np.sin(2 * np.pi * (55 * t - 12 * t * t)) * np.exp(-t / 0.35) * 1.6
    rumble = F(rng.standard_normal(n), lo=20, hi=220) * np.exp(-t / 1.4)
    return norm(F(crack, lo=30, hi=8000) * 0.8 + thump + rumble / np.std(rumble) * 0.7, -9, 0.95)


def blast_far(dur=8.0):
    n = int(dur * SR)
    t = np.arange(n) / SR
    boom = F(rng.standard_normal(n), lo=25, hi=400) * np.exp(-t / 0.5)
    x = boom.copy()
    for d, g in [(0.35, 0.5), (0.8, 0.35), (1.4, 0.28), (2.3, 0.2), (3.5, 0.12)]:  # эхо от рельефа и домов
        k = int(d * SR)
        x[k:] += g * F(boom, hi=250)[:n - k]
    x = reverb(x, t60=3.0, mix=0.5)
    return norm(x * np.minimum(1, t / 0.03 + 0.05), -11, 0.95)


def siren(dur=13.0):
    """Механическая сирена воздушной тревоги: разгон ротора, вой с «плаванием» тона, спад; эхо улиц."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    rise, hold = 3.5, 4.5
    f = np.where(t < rise, 150 + 330 * (1 - np.exp(-t / 1.1)) / (1 - np.exp(-rise / 1.1)),
                 np.where(t < rise + hold, 480 + 6 * np.sin(2 * np.pi * 0.35 * (t - rise)),
                          480 - 290 * (1 - np.exp(-(t - rise - hold) / 2.2))))
    ph = np.cumsum(f) / SR
    # ротор даёт почти прямоугольный сигнал: нечётные гармоники; второй ротор — «аккорд» 6:5
    tone = sum(np.sin(2 * np.pi * k * ph) / k for k in (1, 3, 5, 7, 9))
    tone += 0.55 * sum(np.sin(2 * np.pi * k * 1.2 * ph) / k for k in (1, 3, 5, 7))
    amp = np.minimum(1, t / 0.6) * np.minimum(1, (dur - t) / 2.5)
    x = F(tone * amp, lo=140, hi=2600)
    x = reverb(x, t60=2.6, mix=0.5, pre=0.06, hi=2500)
    return norm(x, -14, 0.8)


def bomb_crack():
    n = int(2.5 * SR)
    t = np.arange(n) / SR
    x = np.zeros(n)
    for t0 in (0.02, 0.13):  # N-волна: два хлёстких щелчка
        k, L = int(t0 * SR), int(0.004 * SR)
        x[k:k + L] += np.linspace(1, -1, L)
    x = F(x, lo=20, hi=9000) + F(rng.standard_normal(n), lo=30, hi=300) * np.exp(-t / 0.6) * 0.25
    return norm(reverb(x, t60=1.8, mix=0.35), -10)


def bomb_impact():
    n = int(3.5 * SR)
    t = np.arange(n) / SR
    thump = np.sin(2 * np.pi * (48 * t - 8 * t * t)) * np.exp(-t / 0.25) * 1.8
    crunch = F(rng.standard_normal(n), lo=200, hi=2800) * np.exp(-t / 0.35) * (1 + 0.8 * (rng.random(n) < 0.004))
    earth = F(rng.standard_normal(n), lo=25, hi=200) * np.exp(-t / 0.9)
    return norm(reverb(thump + 0.7 * crunch / np.std(crunch) + 0.8 * earth / np.std(earth), t60=1.6, mix=0.3), -11)


def bomb_quake():
    n = int(6 * SR)
    t = np.arange(n) / SR
    q = F(rng.standard_normal(n), lo=15, hi=90) * np.minimum(1, t / 0.15) * np.exp(-t / 1.8)
    rattle = F(rng.standard_normal(n), lo=900, hi=4000) * (rng.random(n) < 0.02) * np.exp(-t / 1.2)
    return norm(q / np.std(q) + 0.04 * rattle / max(np.std(rattle), 1e-9), -9)


def bomb_deep():
    n = int(7 * SR)
    t = np.arange(n) / SR
    boom = F(rng.standard_normal(n), lo=18, hi=180) * np.exp(-t / 0.7) * np.minimum(1, t / 0.05)
    sub = np.sin(2 * np.pi * (36 * t - 3 * t * t)) * np.exp(-t / 0.9)
    return norm(reverb(boom / np.std(boom) + 1.2 * sub, t60=3.2, mix=0.45, hi=400), -9)


def bomb_vent():
    n = int(4 * SR)
    t = np.arange(n) / SR
    v = F(rng.standard_normal(n), lo=50, hi=1800, bells=[(250, 7, 1.0)]) * env_noise(n, 12, 0.5) * np.exp(-t / 1.6) * np.minimum(1, t / 0.08)
    return norm(reverb(v, t60=1.2, mix=0.25), -10)


def bomb_cave():
    n = int(7 * SR)
    t = np.arange(n) / SR
    crack = F(rng.standard_normal(n), lo=40, hi=9000) * np.exp(-t / 0.02)
    th = np.sin(2 * np.pi * (60 * t - 10 * t * t)) * np.exp(-t / 0.4) * 1.6
    debris = F(rng.standard_normal(n), lo=500, hi=6000) * np.exp(-t / 1.5) * (rng.random(n) < 0.01)
    x = crack + th + 0.5 * F(rng.standard_normal(n), lo=25, hi=250) * np.exp(-t / 1.2) + 0.4 * debris
    return norm(reverb(x, t60=3.8, mix=0.6, pre=0.03, hi=2500), -9)


def lock_beep():
    """Захват цели: два коротких сигнала прибора."""
    n = int(0.25 * SR)
    x = np.zeros(n)
    for t0 in (0.0, 0.12):
        k, L = int(t0 * SR), int(0.06 * SR)
        tt = np.arange(L) / SR
        x[k:k + L] += np.sin(2 * np.pi * 1760 * tt) * np.sin(np.pi * tt / (L / SR))
    return norm(x, -16, 0.6)


# ---------------------------------------------------------------- ядерный удар

def nuke_alarm(cycles=3, cyc=10.0, tail=2.5):
    """Сирена гражданской обороны «ракетное нападение»: ниже воздушной тревоги, три цикла разгон — вой — спад,
    роторы в малую терцию и суб-октава дают тревожное «дрожание»; эхо улиц."""
    n = int((cycles * cyc + tail) * SR)
    t = np.arange(n) / SR
    tc = np.mod(t, cyc)
    lo, hi, rise, hold = 120.0, 360.0, 3.2, 3.8
    up = lo + (hi - lo) * (1 - np.exp(-tc / 1.0)) / (1 - np.exp(-rise / 1.0))
    top = hi + 6 * np.sin(np.pi * (tc - rise) / hold)
    down = hi - (hi - lo) * (1 - np.exp(-(tc - rise - hold) / 1.3)) / (1 - np.exp(-(cyc - rise - hold) / 1.3))
    f = np.where(tc < rise, up, np.where(tc < rise + hold, top, down))
    lvl = 0.25 + 0.75 * np.clip((f - lo) / (hi - lo), 0, 1)  # медленный ротор тише
    ph = np.cumsum(f * (1 + 0.01 * np.sin(2 * np.pi * 5.5 * t))) / SR
    tone = sum(np.sin(2 * np.pi * k * ph) / k for k in (1, 3, 5, 7, 9))
    tone += 0.6 * sum(np.sin(2 * np.pi * k * 1.194 * ph) / k for k in (1, 3, 5, 7))  # второй ротор чуть расстроен: биения
    tone += 0.35 * sum(np.sin(2 * np.pi * k * 0.5 * ph) / k for k in (1, 3, 5))
    amp = np.minimum(1, t / 0.8) * np.clip(cycles * cyc - t, 0, 1) * (1 + 0.25 * np.sin(2 * np.pi * 7.3 * t))
    x = F(tone * lvl * amp, lo=90, hi=2200)
    dull = F(x, hi=1500)
    for d, g in [(0.23, 0.35), (0.51, 0.25), (0.94, 0.18)]:  # отражения от домов
        k = int(d * SR)
        x[k:] += g * dull[:n - k]
    return norm(reverb(x, t60=3.0, mix=0.5, pre=0.07, hi=2000), -14, 0.85)


def nuke_launch(dur=22.0):
    """Пуск МБР: рёв твердотопливной ступени с треском; ракета уходит вверх — тише и глуше за ~20 с."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    roar = F(rng.standard_normal(n), lo=25, hi=6000, bells=[(80, 8, 1.0), (250, 5, 1.2)])
    roar = roar / np.std(roar) * env_noise(n, 4, 0.35)
    imp = (rng.random(n) < 300 / SR) * rng.lognormal(0, 0.8, n) * np.sign(rng.standard_normal(n))
    crackle = F(imp, lo=700, hi=9000)
    full = roar + 0.6 * crackle / np.std(crackle)
    near = np.exp(-np.maximum(0, t - 2) / 4)  # доля полного спектра: верха «съедает» расстояние
    g = np.exp(-np.maximum(0, t - 2.5) / 5.5) * np.clip((dur - t) / 2, 0, 1) * np.minimum(1, t / 0.15)
    ign = np.sin(2 * np.pi * (40 * t - 8 * t * t)) * np.exp(-t / 0.5)
    x = (near * full + (1 - near) * 1.3 * F(full, hi=350)) * g + 1.2 * ign
    return norm(reverb(x, t60=2.5, mix=0.35), -10, 0.7)


def nuke_crack(dur=6.5):
    """Приход фронта вблизи: удар Фридлендера, лязг, огромный низ; нарочно перегружен (клиппинг)."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = np.maximum(t - 0.05, 0)
    on = t >= 0.05
    slam = on * np.minimum(1, s / 0.002) * (1 - s / 0.35) * np.exp(-s / 0.35)
    body = on * np.sin(2 * np.pi * (32 * np.minimum(s, 2.5) - 6 * np.minimum(s, 2.5) ** 2)) * np.exp(-s / 0.8)
    rumble = F(rng.standard_normal(n), lo=18, hi=160) * np.exp(-s / 1.4) * on
    front = F(rng.standard_normal(n), lo=300, hi=11000) * np.exp(-s / 0.03) * on
    clang = on * sum(a * np.sin(2 * np.pi * fr * s) * np.exp(-s / (0.3 * (300 / fr) ** 0.4))
                     for fr, a in [(287, 1), (461, 0.8), (733, 0.7), (1170, 0.5), (1690, 0.4), (2410, 0.3)])
    debris = F(rng.standard_normal(n), lo=800, hi=6000) * (rng.random(n) < 0.015) * np.exp(-s / 1.2) * on
    x = 2.5 * slam + 1.4 * body + 0.9 * rumble / np.std(rumble) + 0.8 * front / np.std(front) \
        + 0.35 * clang + 0.2 * debris / np.std(debris)
    x = F(np.clip(3 * x / np.max(np.abs(x)), -1, 1), hi=8000)  # срез верхов: у ступеньки клиппинга Vorbis даёт выброс
    return norm(reverb(x, t60=2.4, mix=0.3, hi=3500), -11, 0.6)


def nuke_boom_far(dur=14.0):
    """Далёкий подрыв: глухой удар, затем долгий катящийся гром (отражения от рельефа)."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    boom = F(rng.standard_normal(n), lo=20, hi=180) * np.exp(-t / 0.6) * np.minimum(1, t / 0.04)
    boom = boom / np.max(np.abs(boom)) + 0.8 * np.sin(2 * np.pi * (30 * t - 4 * t * t)) * np.exp(-t / 0.7)
    E = np.zeros(n)
    for t0 in np.sort(rng.uniform(0.5, 9.0, 14)):
        att, dec = rng.uniform(0.1, 0.4), rng.uniform(0.5, 1.5)
        s = t - t0
        E += (s > 0) * rng.uniform(0.4, 1) * np.exp(-t0 / 4) * np.minimum(1, np.maximum(s, 0) / att) * np.exp(-np.maximum(s - att, 0) / dec)
    thunder = F(rng.standard_normal(n), lo=22, hi=260) * E
    x = (boom + 0.6 * thunder / np.std(thunder)) * np.clip((dur - t) / 2, 0, 1)
    return norm(reverb(x, t60=4.0, mix=0.5, hi=600), -12)


def nuke_roar(dur=25.0):
    """Низкий рёв огненного шара и ветра (20–120 Гц) с медленной модуляцией; быстро нарастает, долго стихает."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    low = F(rng.standard_normal(n), lo=20, hi=120)
    wind = F(rng.standard_normal(n), lo=150, hi=900, bells=[(300, 4, 0.8)])
    x = low / np.std(low) * env_noise(n, 0.6, 0.45) * env_noise(n, 3, 0.2) + 0.25 * wind / np.std(wind) * env_noise(n, 1.5, 0.5)
    env = np.minimum(1, t / 0.25) * np.where(t < 4, 1, np.cos(np.pi / 2 * (t - 4) / (dur - 4)) ** 2)
    return norm(x * env, -11)


def hurricane(dur=9.0):
    """Ураганный ветер: порывы, завывание на нескольких узких полосах, низкий гул, шелест."""
    n = int(dur * SR)
    body = F(rng.standard_normal(n), lo=60, hi=1800, bells=[(350, 5, 1.2)])
    x = body / np.std(body) * env_noise(n, 0.8, 0.55)
    for fc, g in [(620, 0.5), (940, 0.35), (1480, 0.2)]:
        howl = F(rng.standard_normal(n), lo=fc * 0.85, hi=fc * 1.2, bells=[(fc, 24, 0.04)])
        x += 0.4 * g * howl / np.std(howl) * env_noise(n, 0.5, 0.8)
    rumble = F(rng.standard_normal(n), lo=20, hi=120)
    hiss = F(rng.standard_normal(n), lo=2000, hi=7000)
    return x + 0.7 * rumble / np.std(rumble) * env_noise(n, 1.0, 0.4) + 0.2 * hiss / np.std(hiss) * env_noise(n, 2, 0.6)


def nuke_rumble(dur=10.0):
    """Раскаты после удара: случайные низкие перекаты, всё тише; клиент играет несколько раз с разным тоном."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    E = np.zeros(n)
    for t0 in np.sort(rng.uniform(0.0, 6.5, 9)):
        att, dec = rng.uniform(0.15, 0.5), rng.uniform(0.6, 1.8)
        s = t - t0
        E += (s > 0) * rng.uniform(0.4, 1) * np.exp(-t0 / 5) * np.minimum(1, np.maximum(s, 0) / att) * np.exp(-np.maximum(s - att, 0) / dec)
    r = F(rng.standard_normal(n), lo=18, hi=220, bells=[(45, 5, 0.8)]) * E
    grit = F(rng.standard_normal(n), lo=300, hi=1200) * E ** 2
    x = (r / np.std(r) + 0.1 * grit / np.std(grit)) * np.clip((dur - t) / 1.5, 0, 1)
    return norm(reverb(x, t60=3.5, mix=0.55, hi=500), -13)


def nuke_glass(dur=3.0, count=180):
    """Рой бьющегося стекла: общий треск и сотни осколков (неармоничные высокие призвуки), всё реже."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    x = F(rng.standard_normal(n), lo=1500, hi=12000) * np.exp(-t / 0.08) * 0.5
    L = int(0.15 * SR)
    tt = np.arange(L) / SR
    for t0 in rng.exponential(0.5, count):
        k = int(t0 * SR)
        if k + L >= n:
            continue
        base = rng.uniform(2000, 5500)
        shard = sum(np.sin(2 * np.pi * base * r * tt + rng.uniform(0, 6.3)) * np.exp(-tt / rng.uniform(0.01, 0.06))
                    for r in (1, rng.uniform(1.3, 1.5), rng.uniform(1.7, 2.0), rng.uniform(2.2, 2.5)))
        shard += 2 * rng.standard_normal(L) * np.exp(-tt / 0.002)
        x[k:k + L] += shard * rng.uniform(0.2, 1) * np.exp(-t0 / 1.2)
    x = F(x, lo=800) * np.clip((dur - t) / 0.3, 0, 1)
    return norm(reverb(x, t60=0.8, mix=0.2, hi=8000), -15, 0.7)


def nuke_tinnitus(dur=15.0):
    """Звон в ушах: чистый тон ~4.9 кГц с лёгкими биениями, медленно гаснет. Тихий: −29 плюс ~3 дБ лимитера norm ≈ −26."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    ph = np.cumsum(4870 * (1 + 0.0008 * np.sin(2 * np.pi * 0.13 * t))) / SR
    x = np.sin(2 * np.pi * ph) + 0.35 * np.sin(2 * np.pi * (ph + 3.1 * t) + 1)
    return norm(x * np.minimum(1, t / 0.03) * np.exp(-t / 6) * np.clip((dur - t) / 2, 0, 1), -29, 0.5)


def black_rain(dur=7.0):
    """Тяжёлые жирные капли: удар, «бульк» пузырька (тон растёт), глухой шлепок; мелочь и шелест фоном."""
    n = int(dur * SR)
    heavy = np.zeros(n)
    L = int(0.06 * SR)
    tt = np.arange(L) / SR
    for k in np.nonzero(rng.random(n) < 45 / SR)[0]:
        if k + L >= n:
            continue
        f0 = rng.uniform(350, 1100)
        d = 2 * rng.standard_normal(L) * np.exp(-tt / 0.003) \
            + np.sin(2 * np.pi * f0 * (tt + 4 * tt * tt)) * np.exp(-tt / rng.uniform(0.015, 0.04)) \
            + 1.5 * np.sin(2 * np.pi * rng.uniform(120, 200) * tt) * np.exp(-tt / 0.01)
        heavy[k:k + L] += d * rng.lognormal(0, 0.5)
    small = F((rng.random(n) < 250 / SR) * rng.lognormal(-1, 0.6, n), lo=1200, hi=9000)
    wash = F(rng.standard_normal(n), lo=400, hi=7000, bells=[(2500, 3, 1)]) * env_noise(n, 0.7, 0.2)
    return F(heavy / np.std(heavy) + 0.5 * small / np.std(small) + 0.5 * wash / np.std(wash), lo=60)


def geiger_click():
    """Один щелчок счётчика Гейгера — Мюллера: ~15 мс и крошечный хвост."""
    n = int(0.025 * SR)
    t = np.arange(n) / SR
    x = np.sin(2 * np.pi * 2900 * t) * np.exp(-t / 0.0012) + 0.6 * np.sin(2 * np.pi * 1100 * t) * np.exp(-t / 0.003) \
        + rng.standard_normal(n) * np.exp(-t / 0.0008) + 0.1 * rng.standard_normal(n) * np.exp(-t / 0.006)
    x[:2] += (1, -0.6)
    return norm(x * np.clip((0.025 - t) / 0.003, 0, 1), -18, 0.85)


def nuke_reentry(dur=4.5):
    """Боеголовка входит в атмосферу высоко над целью: слабый далёкий двойной хлопок и гул."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    x = np.zeros(n)
    for t0 in (0.05, 0.13):
        k, L = int(t0 * SR), int(0.02 * SR)
        x[k:k + L] += np.linspace(1, -1, L)
    x = F(x, lo=25, hi=600)
    hum = F(rng.standard_normal(n), lo=20, hi=200) * np.exp(-t / 1.2) * np.minimum(1, t / 0.1)
    x = x / np.max(np.abs(x)) + 0.3 * hum / np.std(hum)
    return norm(reverb(x * np.clip((dur - t) / 0.8, 0, 1), t60=3.5, mix=0.55, hi=500), -20, 0.6)


if __name__ == "__main__":
    near_far(drone_engine(), "drone_engine", 6.0, far_hi=900)
    write("missile_engine", norm(loop(turbojet(whine_amt=1.25, roar_amt=0.55, hiss_amt=0.5), 6.0), -12))
    write("missile_engine_rear", norm(loop(turbojet(whine_amt=0.35, roar_amt=1.2, hiss_amt=0.25), 6.0), -12))
    write("missile_dive", norm(loop(turbojet(tone=3400, whine_amt=1.6, roar_amt=0.6, hiss_amt=0.9), 6.0), -12))
    far = reverb(F(turbojet(), lo=60, hi=1500), t60=2.0, mix=0.45)
    write("missile_engine_far", norm(loop(far, 6.0), -13))
    write("missile_whistle", norm(loop(whistle(), 5.0), -13))
    j = jet()
    write("bomber_engine", norm(loop(reverb(F(j, hi=1600), t60=2.5, mix=0.4), 7.0), -12))
    write("bomber_engine_far", norm(loop(reverb(F(j, lo=25, hi=500), t60=3.5, mix=0.55), 7.0), -12))
    b = bomb_fall()
    write("bomb_fall", norm(loop(b, 6.0), -11))
    write("bomb_fall_far", norm(loop(reverb(F(b, lo=60, hi=1200), t60=2.0, mix=0.45), 6.0), -12))
    write("bomb_drill", norm(loop(drill(), 5.0), -11))

    write("blast_near", blast_near())
    write("blast_sub", blast_sub())
    write("blast_far", blast_far())
    write("siren", siren())
    write("bomb_crack", bomb_crack())
    write("bomb_impact", bomb_impact())
    write("bomb_quake", bomb_quake())
    write("bomb_deep", bomb_deep())
    write("bomb_vent", bomb_vent())
    write("bomb_cave", bomb_cave())
    write("designator_lock", lock_beep())

    write("nuke_alarm", nuke_alarm())
    write("nuke_launch", nuke_launch())
    write("nuke_crack", nuke_crack())
    write("nuke_boom_far", nuke_boom_far())
    write("nuke_roar", nuke_roar())
    write("nuke_wind", norm(loop(hurricane(), 8.0, 0.8), -12))
    write("nuke_rumble", nuke_rumble())
    write("nuke_glass", nuke_glass())
    write("nuke_tinnitus", nuke_tinnitus())
    write("nuke_rain", norm(loop(black_rain(), 6.0), -15))
    write("geiger_click", geiger_click())
    write("nuke_reentry", nuke_reentry())
    print("ok:", len([f for f in os.listdir(OUT) if f.endswith(".ogg")]), "файлов в", OUT)
