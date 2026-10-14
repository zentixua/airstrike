"""Громкость по ITU-R BS.1770-4 (EBU R128) и истинный пик — на numpy, без ffmpeg: мера проверяется тестами
(tests/test_montage.py). Нужен только numpy.

- K-фильтр (полка +4 дБ выше ~1,7 кГц и срез ниже ~38 Гц): коэффициенты двух биквадов — по формулам libebur128 для
  любой частоты отсчётов (при 48 кГц — ровно таблица BS.1770-4); фильтр накладывается умножением спектра сигнала на
  частотную характеристику тех же биквадов, с запасом нулей 0,5 с: хвост БИХ-фильтра затухает за ~60 мс, и результат —
  тот же, что у фильтра от покоя;
- интегральная громкость: блоки 400 мс с шагом 100 мс, абсолютный порог −70 LUFS, относительный — на 10 LU ниже
  громкости блоков над абсолютным: тишина и паузы в счёт не идут и громкость не поднимают;
- истинный пик: передискретизация ×4 фильтром sinc с окном Кайзера (129 отводов, β 8,6; на нулевой фазе — сами
  отсчёты), dBTP;
- ограничитель истинного пика (`limit`): усиление по блокам в 32 отсчёта с упреждением, атакой и спадом, ни в одном
  отсчёте не выше нужного для порога; после — проверка мерой, перелёт — порог ниже и ещё раз;
- `master` — общее усиление до цели и ограничитель, пока громкость не ляжет в допуск: усиление одно на весь ролик, тише
  оно ничего не делает громче — тишина остаётся тишиной.

Сверка (тесты): синус 997 Гц −23 dBFS в обоих каналах — −23,0 LUFS (EBU Tech 3341, случай 1: там 1 кГц); 997 Гц 0 dBFS
в одном канале — −3,01 LUFS (определение BS.1770: синус 1 кГц полной шкалы в одном канале — −3,01); −36/−23/−36 dBFS и
−72/−36/−23/−36/−72 — −23,0 (случаи 3 и 4: пороги); −26/−20/−26 — −23,0 (случай 5: среднее по мощности).
"""
import math

import numpy as np

BLOCK = 0.4            # с, блок интегральной громкости
STEP = 0.1             # с, шаг блоков (перекрытие 75 %)
ABS_GATE = -70.0       # LUFS
REL_GATE = -10.0       # LU от громкости блоков над абсолютным порогом
OVERSAMPLE = 4
TAPS_PER_PHASE = 32


def k_biquads(sr):
    """Два биквада K-фильтра при частоте sr: [(b0, b1, b2), (1, a1, a2)] полки и среза (libebur128)."""
    f0, gain, q = 1681.974450955533, 3.999843853973347, 0.7071752369554196
    k = math.tan(math.pi * f0 / sr)
    vh = 10 ** (gain / 20)
    vb = vh ** 0.4996667741545416
    a0 = 1 + k / q + k * k
    shelf = ((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
             1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    f0, q = 38.13547087602444, 0.5003270373238773
    k = math.tan(math.pi * f0 / sr)
    a0 = 1 + k / q + k * k
    high = (1.0, -2.0, 1.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    return shelf, high


def k_response(sr, n):
    """Частотная характеристика K-фильтра на частотах rfft длины n."""
    z = np.exp(-1j * np.pi * np.arange(n // 2 + 1) / (n // 2))   # e^{-jω}, ω от 0 до π
    h = np.ones(len(z), complex)
    for b0, b1, b2, a0, a1, a2 in k_biquads(sr):
        h *= (b0 + b1 * z + b2 * z * z) / (a0 + a1 * z + a2 * z * z)
    return h


def k_weight(x, sr):
    """K-фильтр по каналам: x — (отсчёты,) или (отсчёты, каналы)."""
    x = np.asarray(x, np.float64)
    mono = x.ndim == 1
    x = x[:, None] if mono else x
    n = 1 << max(1, math.ceil(math.log2(len(x) + int(0.5 * sr))))
    h = k_response(sr, n)
    y = np.empty_like(x)
    for c in range(x.shape[1]):
        y[:, c] = np.fft.irfft(np.fft.rfft(x[:, c], n) * h, n)[:len(x)]
    return y[:, 0] if mono else y


def block_powers(x, sr):
    """Средний квадрат K-взвешенного сигнала в блоках 400 мс с шагом 100 мс: (блоки, каналы)."""
    y = k_weight(x, sr)
    y = y[:, None] if y.ndim == 1 else y
    size, step = int(round(BLOCK * sr)), int(round(STEP * sr))
    if len(y) < size:
        return np.zeros((0, y.shape[1]))
    c = np.concatenate([np.zeros((1, y.shape[1])), np.cumsum(y * y, axis=0)])
    starts = np.arange(0, len(y) - size + 1, step)
    return (c[starts + size] - c[starts]) / size


def _lufs(power):
    return -0.691 + 10 * np.log10(np.maximum(power, 1e-300))


def integrated(x, sr):
    """Интегральная громкость, LUFS (каналы с весом 1: моно и стерео); тишина — −inf."""
    z = block_powers(x, sr)
    if not len(z):
        return -math.inf
    loud = _lufs(z.sum(axis=1))
    gated = z[loud > ABS_GATE]
    if not len(gated):
        return -math.inf
    rel = _lufs(gated.mean(axis=0).sum()) + REL_GATE
    keep = z[(loud > ABS_GATE) & (loud > rel)]
    return float(_lufs(keep.mean(axis=0).sum()))


def blocks(x, sr):
    """Громкость каждого блока 400 мс (шаг 100 мс), LUFS: где ролик тих."""
    z = block_powers(x, sr)
    return _lufs(z.sum(axis=1)) if len(z) else np.zeros(0)


def _taps():
    n = OVERSAMPLE * TAPS_PER_PHASE + 1
    j = np.arange(n) - (n - 1) / 2
    return np.sinc(j / OVERSAMPLE) * np.kaiser(n, 8.6)


def oversampled(x):
    """Сигнал ×4 по фазам: (4, отсчёты[, каналы]); фаза p — между отсчётом n и n+1 на p/4, фаза 0 — сами отсчёты."""
    x = np.asarray(x, np.float64)
    h = _taps()
    d = TAPS_PER_PHASE // 2   # задержка фильтра в отсчётах
    mono = x.ndim == 1
    x2 = x[:, None] if mono else x
    out = np.empty((OVERSAMPLE, *x2.shape))
    for p in range(OVERSAMPLE):
        hp = h[p::OVERSAMPLE]
        for c in range(x2.shape[1]):
            out[p, :, c] = np.convolve(x2[:, c], hp)[d:d + len(x2)]
    return out[..., 0] if mono else out


def peak_envelope(x):
    """Истинный пик у каждого отсчёта (наибольший модуль на отрезке до следующего по всем каналам), линейно."""
    up = np.abs(oversampled(x))
    env = up.max(axis=0)
    return env.max(axis=1) if env.ndim == 2 else env


def true_peak(x):
    """Истинный пик, dBTP."""
    p = float(np.max(peak_envelope(x))) if len(x) else 0.0
    return 20 * math.log10(p) if p > 0 else -math.inf


def limit(x, sr, ceiling=-1.0, lookahead=0.002, release=0.08, block=32, env=None):
    """Ограничитель истинного пика: x × усиление, где усиление ни в одном отсчёте не выше нужного (порог / пик у
    отсчёта). Усиление — по блокам: нужное блока — наименьшее в нём; упреждение — наименьшее нужное на La блоков
    вперёд, атака — среднее этого за La+1 блоков назад (каждое слагаемое не выше нужного текущего блока), спад —
    к 1 с постоянной release, но не выше атаки; к отсчётам — между центрами блоков по наименьшему из соседних (так
    промежуточное значение не выше нужного своего блока). env — готовый peak_envelope(x)."""
    x = np.asarray(x, np.float64)
    env = peak_envelope(x) if env is None else env
    c = 10 ** (ceiling / 20)
    need = np.minimum(1.0, c / np.maximum(env, 1e-12))
    nb = -(-len(x) // block)
    padded = np.ones(nb * block)
    padded[:len(need)] = need
    bmin = padded.reshape(nb, block).min(axis=1)
    la = max(1, int(round(lookahead * sr / block)))
    ext = np.concatenate([bmin, np.ones(la)])
    ahead = np.lib.stride_tricks.sliding_window_view(ext, la + 1).min(axis=1)[:nb]
    back = np.concatenate([np.full(la, ahead[0]), ahead])   # до начала — первое упреждение: оно не выше первых la блоков
    attack = np.lib.stride_tricks.sliding_window_view(back, la + 1).mean(axis=1)[:nb]
    attack = np.minimum(attack, bmin)   # среднее чисел не выше нужного — не выше; минимум — от ошибок округления
    k = 1 - math.exp(-block / (release * sr))
    g = np.empty(nb)
    prev = 1.0
    for b in range(nb):
        prev = min(attack[b], prev + (1 - prev) * k)
        g[b] = prev
    gp = np.concatenate([[g[0]], g, [g[-1]]])
    low = np.minimum(np.minimum(gp[:-2], gp[1:-1]), gp[2:])
    centers = np.arange(nb) * block + (block - 1) / 2
    gain = np.interp(np.arange(len(x)), centers, low)
    return x * (gain[:, None] if x.ndim == 2 else gain), gain


def master(x, sr, target=-14.0, ceiling=-1.0, tol=0.05, rounds=8):
    """Громкость ролика — target LUFS, истинный пик — не выше ceiling dBTP: одно усиление на весь ролик и
    ограничитель; ограничитель снимает громкость — усиление подправляется (секущей по прошлым шагам), пока громкость не
    ляжет в tol. Каждый шаг — от того же порога: итог зависит только от усиления, а не от пути к нему. Возвращает
    (сигнал, отчёт). Тишина (громкость −inf) не трогается."""
    x = np.asarray(x, np.float64)
    before = integrated(x, sr)
    info = {"lufs_in": before, "tp_in": true_peak(x)}
    if not math.isfinite(before):
        info.update(gain_db=0.0, lufs=before, tp=info["tp_in"], rounds=0, limited=0.0)
        return x, info
    env = peak_envelope(x)

    def run(gain_db):
        g = 10 ** (gain_db / 20)
        ceil_eff = ceiling
        for _ in range(4):   # перелёт истинного пика после ограничителя — порог ниже
            y, gain = limit(x * g, sr, ceil_eff, env=env * g)
            tp = true_peak(y)
            if tp <= ceiling + 1e-6:
                break
            ceil_eff -= tp - ceiling + 0.02
        return y, gain, tp, integrated(y, sr)

    gain_db, prev = target - before, None
    for r in range(1, rounds + 1):
        y, gain, tp, lufs = run(gain_db)
        if abs(lufs - target) <= tol:
            break
        slope = 1.0   # ограничитель снимает часть прибавки: громкость растёт медленнее усиления
        if prev is not None and abs(gain_db - prev[0]) > 1e-9:
            slope = min(1.5, max(0.2, (lufs - prev[1]) / (gain_db - prev[0])))
        prev = (gain_db, lufs)
        gain_db += (target - lufs) / slope
    info.update(gain_db=gain_db, lufs=lufs, tp=tp, rounds=r, limited=float(np.mean(gain < 0.999)))
    return y, info
