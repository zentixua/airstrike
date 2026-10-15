#!/usr/bin/env python3
"""Проверки дубля Newisle по данным записи и вывода (задача Артёма 15.10): то, что раньше замечал глаз, меряется по
файлам журналов — провал ведёт к пересъёмке или заливке в монтаже, старая запись без поля — не провал. Только
системный python3, без numpy.

Запись (папка дубля produce.py, каждый провал — `problems` попытки, пересъёмка запасной камерой; отставание сервера
и сирена — только предупреждение попытки (`record_warnings`): пересъёмка их не лечит, их правит монтаж):
- пуски в один тик: в replays/<план>.events.jsonl два пути восходят (фаза READY→IGNITION/BOOST или новый путь
  впервые в IGNITION) в один и тот же тик — «установки не стреляют в одну миллисекунду»;
- отставание сервера: у планов с `mspt` (result.json, тик встроенного сервера) худшее среднее 10 тиков подряд
  в окне события ([удар−2 с, удар+2 с] каждого actual; без ударов — весь план) выше 50 мс — «цели дёргаются»;
- полёт МБР: пути оружия nuke по местам от сущности (флаг 1 в строках `f`) — ускорение ≤ 0,05 блока/тик² (~2 g;
  сама ступень на видимом разгоне даёт до 0,043: a(t) = T/(m₀−ṁ·t) − g, к уходу из мира ~7 с) и рывок без
  всплеска: |Δa| ≤ 0,01 за тик — модель даёт ~3·10⁻⁵ (WeaponSpec.Ascent: da/dt = T·ṁ/m(t)²), шум округления
  0,01 блока после сглаживания на 9 тиков ~2·10⁻³; скачок пути (телепорт) даёт на порядок больше;
- сирена дубля: у дублей с включённой сиреной (`siren: true` в newisle-takes.json; `false` — первый удар без
  тревоги) записывающий игрок слышит её (timeline.jsonl, входящий:siren) не меньше чем за 2 с до первого удара
  дубля (sounds.jsonl: триггер siren с heard) и в сумме не меньше 6 с;
- стая в бинокль: план ночной стаи (камера scope, watch-оружие drone, сумерки/ночь — в qa/<план>.jsonl HudQa)
  читается как стая: в квадрате окуляра (`in`) и не закрыты (`open`) не меньше min(6, пущенных сценой) дронов в
  ≥ 60 % кадров; видимого размера строки qa не дают — предела размера нет.

Вывод (папка кандидата render/<план>/<кандидат>/, каждый провал — PASS плана становится FAIL, дубль переснимается):
- поворот камеры ≤ 120°/с по state.json (ключи на тик; по тикам × 20, не с интерполяцией до 60 кадров: в файле
  нет кадров между тиками, а плавная интерполяция Flashback не создаёт поворота быстрее ключей дольше одного
  кадра; курс через ±180°);
- ось ракеты: строка qa.jsonl с `axis` > 15° — нос модели расходится с ходом (замечание к v5: ракета летит «стоя»);
- тряска по расстоянию: у удара окна ближе 150 блоков к камере в миг прихода волны (17,15 блока/тик) путь вывода
  показывает толчок — пик отклонения угла (курс или тангаж) после вычитания среднего за окно ~0,6 с не меньше
  0,15·min(3,5°; 6·огненный шар/(расстояние+8)) — той же модели, что Operator.shake, с самым ровным стилем
  (tracker, доля 0,3): верный путь проходит с запасом ≥ 1,7×, `-raw` без тряски — нет. Ядерные планы (трясёт
  модель мода), onboard и HUD — без этой проверки;
- FPV над живыми районами: у FPV-планов в ≥ 70 % кадров — удар последних 15 с ближе 200 блоков в конусе взгляда
  (fov/2 + 10°) или объект снят по qa.jsonl (in, виден, не в облаках и тумане, секция построена); у планов с МБР —
  в ≥ 80 % кадров, где она в кадре, `share` ≥ 0,02 высоты кадра;
- застывшие кадры: в frames/<план>/ три и больше одинаковых подряд (хеш файла) — вывод встал.

    python3 tools/trailer/take_checks.py record <папка дубля> [--scenes ПАПКА] [--take T04]
    python3 tools/trailer/take_checks.py render <папка кандидата> --plan <план> [--fpv] [--icbm] [--no-shake]
"""
import argparse
import hashlib
import json
import math
import os
import sys

READY, IGNITION, BOOST = 0, 1, 2          # FlightPhase (порядковый номер)
ENTITY_FLAG = 1                            # в строках `f`: место от сущности, не от пакетов сервера
MSPT_RUN, MSPT_MAX, MSPT_WINDOW = 10, 50.0, 2.0
ICBM_WEAPONS, ICBM_MAX_A, ICBM_MAX_JERK, ICBM_SMOOTH = ("nuke", "icbm"), 0.05, 0.01, 9
SIREN_EVENT, SIREN_BEFORE_S, SIREN_TOTAL_S, SIREN_MAX_S = "incoming:siren", 2.0, 6.0, 26.5
FLOCK_MIN, FLOCK_SHARE = 6, 0.6
TURN_MAX, AXIS_MAX = 120.0, 15.0
WAVE_SPEED, SHAKE_RANGE, SHAKE_WINDOW, SHAKE_K, SHAKE_MAX = 17.15, 150.0, 12, 6.0, 3.5
SHAKE_SHARE = 0.15                         # доля от размаха модели Operator, ниже которой толчка нет
BLAST = {"rocket": 8, "drone": 12, "loiter": 12, "missile": 18, "bunker": 24}   # Rig.blastOf, нюк — 16
FPV_SHARE, FPV_PAST_S, FPV_RANGE, FPV_CONE = 0.7, 15.0, 200.0, 10.0
ICBM_SHARE, ICBM_FRAMES = 0.02, 0.8
FROZEN_RUN = 3


def _jsonl(path):
    """Строки JSONL, которых нет — пусто (старая запись без поля — не провал)."""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        yield json.loads(line)
                    except ValueError:
                        pass
    except OSError:
        return


def load_json(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8") as f:
        return json.load(f)


# ================================================================ чтение журналов повтора

class Flight:
    """Путь снаряда из events.jsonl: фазы и места по тикам, взрыв."""

    def __init__(self, i, weapon, born):
        self.i, self.weapon, self.born = i, weapon, born
        self.phase, self.pos, self.entity = {}, {}, set()
        self.impact, self.impact_pos = None, None

    def samples(self):
        """Места от сущности (флаг 1) по возрастанию тика."""
        return [(t, self.pos[t]) for t in sorted(self.entity) if t in self.pos]

    def launches(self):
        """Тики пуска: фаза READY→IGNITION/BOOST или первый наблюдаемый phase в IGNITION/BOOST."""
        out = []
        ticks = sorted(self.phase)
        for k, t in enumerate(ticks):
            ph = self.phase[t]
            if ph in (IGNITION, BOOST) and (k == 0 or self.phase[ticks[k - 1]] == READY):
                out.append(t)
        return out


def read_events(path):
    """replays/<план>.events.jsonl → (планы-полёты, есть ли ядерный подрыв)."""
    flights, nuke = [], False
    by_index = {}
    for r in _jsonl(path):
        t = r.get("t", 0)
        if "new" in r:
            f = Flight(r["new"], r.get("weapon", ""), t)
            by_index[f.i] = f
            flights.append(f)
        elif "impact" in r or "lost" in r:
            f = by_index.get(r.get("impact", r.get("lost")))
            if f and "impact" in r:
                f.impact, f.impact_pos = t, tuple(r.get("pos", ()))
        elif "nuke" in r:
            nuke = True
        elif "f" in r:
            for e in r["f"]:
                f = by_index.get(e[0])
                if not f:
                    continue
                f.pos[t] = (e[1], e[2], e[3])
                f.phase[t] = e[4]
                if len(e) > 5 and e[5] == ENTITY_FLAG:
                    f.entity.add(t)
    return flights, nuke


# ================================================================ запись: п. 6-10

def same_tick_launches(rec):
    """Пуски в один тик: в каждом журнале повтора два восхождения в один тик — проблема (Артём, 15.10)."""
    out = []
    for name in sorted(os.listdir(os.path.join(rec, "replays"))) if os.path.isdir(os.path.join(rec, "replays")) else []:
        if not name.endswith(".events.jsonl"):
            continue
        plan = name[:-len(".events.jsonl")]
        flights, _ = read_events(os.path.join(rec, "replays", name))
        at = {}
        for f in flights:
            for t in f.launches():
                at.setdefault(t, []).append(f.i)
        for t, who in sorted(at.items()):
            if len(who) > 1:
                out.append(f"{plan}: {len(who)} пуска в тик {t} (пути {' и '.join(map(str, sorted(who)))})")
    return out


def worst_run(values, run=MSPT_RUN):
    """Худшее среднее `run` подряд: (среднее, индекс начала) или None."""
    if len(values) < run:
        return None
    s = sum(values[:run])
    best = (s / run, 0)
    for k in range(run, len(values)):
        s += values[k] - values[k - run]
        if s / run > best[0]:
            best = (s / run, k - run + 1)
    return best


def server_lag(result):
    """Отставание сервера: у планов с mspt худшее среднее 10 тиков в окне события (±2 с удара) выше 50 мс."""
    out = []
    for s in result.get("shots", []):
        mspt = s.get("mspt")
        if not mspt:
            continue
        windows = [[0, len(mspt)]]
        actuals = [k["actual"] for k in s.get("strikes", []) if "actual" in k]
        if actuals:
            windows = [[max(0, int((a - MSPT_WINDOW) * 20)), min(len(mspt), math.ceil((a + MSPT_WINDOW) * 20))]
                       for a in actuals]
        worst = None
        for a, b in windows:
            w = worst_run([v for v in mspt[a:b] if isinstance(v, (int, float))])
            if w and (worst is None or w[0] > worst[0]):
                worst = (w[0], a + w[1])
        if worst and worst[0] > MSPT_MAX:
            out.append(f"{s.get('replay')}: сервер {worst[0]:.1f} мс/тик (тики {worst[1]}..{worst[1] + MSPT_RUN - 1})")
    return out


def _smooth(v, n):
    return [sum(v[max(0, i - n // 2):i - n // 2 + n]) / len(v[max(0, i - n // 2):i - n // 2 + n]) for i in range(len(v))]


def icbm_flight(rec):
    """МБР: ускорение ≤ 0,05 блока/тик² и плавность (без всплеска рывка) по местам от сущности."""
    out = []
    replays = os.path.join(rec, "replays")
    for name in sorted(os.listdir(replays)) if os.path.isdir(replays) else []:
        if not name.endswith(".events.jsonl"):
            continue
        plan = name[:-len(".events.jsonl")]
        flights, _ = read_events(os.path.join(replays, name))
        for f in flights:
            if f.weapon not in ICBM_WEAPONS:
                continue
            samples = f.samples()
            # скорости между соседними местами от сущности; дырка дальше 5 тиков — не соседние
            vels = []
            for k in range(len(samples) - 1):
                (t0, p0), (t1, p1) = samples[k], samples[k + 1]
                if 0 < t1 - t0 <= 5:
                    vels.append(([(p1[j] - p0[j]) / (t1 - t0) for j in range(3)], t1))
            # ускорения между соседними скоростями — тоже без разрывов
            segs, cur = [], []
            for k in range(len(vels) - 1):
                dt = vels[k + 1][1] - vels[k][1]
                if not 0 < dt <= 5:
                    if len(cur) >= ICBM_SMOOTH:
                        segs.append(cur)
                    cur = []
                    continue
                cur.append(([(vels[k + 1][0][j] - vels[k][0][j]) / dt for j in range(3)], vels[k + 1][1]))
            if len(cur) >= ICBM_SMOOTH:
                segs.append(cur)
            for seg in segs:
                # сглаживание по компонентам: шум округления 0,01 блока не должен читать скачки
                vectors = [s[0] for s in seg]
                acc = [[_smooth(list(comp), ICBM_SMOOTH)[k] for comp in zip(*vectors)] for k in range(len(vectors))]
                ticks = [s[1] for s in seg]
                for k, a in enumerate(acc):
                    if max(abs(x) for x in a) > ICBM_MAX_A:
                        out.append(f"{plan}: МБР ускорение {max(abs(x) for x in a):.3f} бл/тик² в тик {ticks[k]} "
                                   f"(предел {ICBM_MAX_A})")
                        break
                for k in range(1, len(acc)):
                    dt = max(1, ticks[k] - ticks[k - 1])
                    jerk = max(abs(acc[k][j] - acc[k - 1][j]) for j in range(3)) / dt
                    if jerk > ICBM_MAX_JERK:
                        out.append(f"{plan}: МБР рывок {jerk:.3f} бл/тик² за тик около {ticks[k]} (предел {ICBM_MAX_JERK})")
                        break
    return out


def siren_take(rec, take, plans):
    """Сирена дубля (у включённых): слышана до первого удара (не меньше 2 с) и в сумме не меньше 6 с."""
    if not take.get("siren", True):
        return []      # первый удар без тревоги: siren: false — сирены в мире нет
    impacts, heard = {}, []
    for plan in plans:
        sounds = os.path.join(rec, "replays", f"{plan}.sounds.jsonl")
        if not os.path.exists(sounds):
            continue
        events = os.path.join(rec, "replays", f"{plan}.events.jsonl")
        flights, _ = read_events(events) if os.path.exists(events) else ([], False)
        at = sorted(f.impact for f in flights if f.impact is not None)
        if at:
            impacts[plan] = at[0]
        for r in _jsonl(sounds):
            if r.get("trigger") == "siren" and r.get("heard"):
                heard.append((plan, r["t"]))
    out = []
    if impacts:
        first_plan = min(impacts, key=lambda p: (plans.index(p) if p in plans else 0, impacts[p]))
        t0 = impacts[first_plan]
        before = [h for h in heard if h[0] == first_plan and h[1] <= t0 - SIREN_BEFORE_S * 20]
        if not before:
            early = [h for h in heard if h[0] == first_plan and h[1] < t0]
            out.append(f"{first_plan}: сирена {'позднее 2 с до удара' if early else 'не слышна'} "
                       f"(первый удар в тик {t0})")
    total = _siren_seconds(os.path.join(rec, "timeline.jsonl"))
    if heard and total < SIREN_TOTAL_S:
        out.append(f"сирена слышна {total:.1f} с из {SIREN_TOTAL_S:.0f}")
    return out


def _siren_seconds(timeline):
    """Суммарная длительность сирены у записывающего игрока: строки звука и их stop (без stop — до конца плана)."""
    stop, end, sounds, last_t = {}, {}, [], 0.0
    for r in _jsonl(timeline):
        if "t" in r:
            last_t = max(last_t, r["t"])
        if r.get("type") == "sound" and r.get("event") == SIREN_EVENT:
            sounds.append(r)
        elif r.get("type") == "stop":
            stop[r.get("id")] = r.get("t", last_t)
        elif r.get("type") == "end":
            end[r.get("shot")] = last_t      # в строке end нет времени — конец плана это последний его кадр/звук
    total = 0.0
    for r in sounds:
        t0 = r.get("t", 0)
        finish = stop.get(r.get("id"), end.get(r.get("shot"), t0 + SIREN_MAX_S * 20))
        total += max(0.0, min(SIREN_MAX_S, (finish - t0) / 20.0))
    return total


def flock_in_scope(rec, take):
    """Стая в бинокль (камера scope, оружие watch — дрон, сумерки/ночь): в окуляре и открыты не меньше положенного."""
    out = []
    for s in take.get("shots", []):
        watch = s.get("watch") or {}
        if s.get("camera") != "scope" or watch.get("weapon") != "drone":
            continue
        launched = len(s.get("strikes", []))
        need = min(FLOCK_MIN, max(1, launched))
        for plan in s.get("plans", []):
            rows = list(_jsonl(os.path.join(rec, "qa", f"{plan}.jsonl")))
            if not rows:
                continue        # живой план не дошёл до проверки — это скажет check_hud
            good = sum(1 for r in rows if sum(1 for f in r.get("flights", []) if f.get("in") and f.get("open")) >= need)
            if good < FLOCK_SHARE * len(rows):
                best = max((sum(1 for f in r.get("flights", []) if f.get("in") and f.get("open")) for r in rows), default=0)
                out.append(f"{plan}: стая в окуляре — {need} дронов в {100 * good / len(rows):.0f}% кадров "
                           f"(лучший кадр {best}; нужно {FLOCK_SHARE:.0%})")
    return out


def record_problems(rec, take, result):
    """Проверки записи, провал которых лечит пересъёмка: список причин для attempt() конвейера."""
    return same_tick_launches(rec) + icbm_flight(rec) + flock_in_scope(rec, take)


def record_warnings(rec, take, result):
    """Проверки записи только в предупреждения (ночь 15.10): отставание сервера и сирена от пересъёмки не уходят —
    три попытки на дубль жгли ночь, а звук и подёргивание правит монтаж."""
    plans = sorted({p for s in take.get("shots", []) for p in s.get("plans", [])})
    return server_lag(result or {}) + siren_take(rec, take, plans)


# ================================================================ вывод: п. 11-13

def _cam_keys(state):
    """state.json → (тики по возрастанию, {тик: (x, y, z, yaw, pitch)}) дорожки CAMERA."""
    try:
        tracks = state["scenes"][0]["keyframeTracks"]
        cam = next(t for t in tracks if t.get("keyframeType") == "CAMERA")["keyframesByTick"]
    except (KeyError, IndexError, StopIteration, TypeError):
        return [], {}
    keys = {}
    for t, k in cam.items():
        p = k.get("position", (0, 0, 0))
        keys[int(t)] = (p[0], p[1], p[2], k.get("yaw", 0.0), k.get("pitch", 0.0))
    return sorted(keys), keys


def _wrap(deg):
    return (deg + 180.0) % 360.0 - 180.0


def camera_turn(cand):
    """Поворот камеры вывода по state.json: по тикам (ключи только на тик) × 20; курс через ±180°."""
    _, keys = _cam_keys(load_json(os.path.join(cand, "state.json")))
    ticks = sorted(keys)
    out = []
    for a, b in zip(ticks, ticks[1:]):
        if b - a != 1:
            continue
        dy, dp = abs(_wrap(keys[b][3] - keys[a][3])), abs(keys[b][4] - keys[a][4])
        if max(dy, dp) * 20 > TURN_MAX:
            out.append(f"камера вывода поворачивает {max(dy, dp) * 20:.0f}°/с в тик {b} (предел {TURN_MAX:.0f})")
            break
    return out


def axis_fail(cand):
    """Ось ракеты: кадр qa.jsonl, где нос модели расходится с ходом сильнее 15°."""
    for r in _jsonl(os.path.join(cand, "qa.jsonl")):
        a = r.get("axis")
        if isinstance(a, (int, float)) and a > AXIS_MAX:
            return [f"ось ракеты {a:.0f}° к ходу в тик {r.get('t')} (предел {AXIS_MAX:.0f}°)"]
    return []


def shake_present(cand, plan):
    """Тряска по расстоянию: у близкого удара (волна доходит за dist/17,15 тика) путь вывода вздрагивает."""
    _, keys = _cam_keys(load_json(os.path.join(cand, "state.json")))
    if not keys:
        return []
    flights, nuke = read_events(os.path.join(cand, f"{plan}.events.jsonl"))
    if nuke:
        return []      # ядерные планы трясёт сама модель мода (ReplayFilm не выключает ей тряску)
    out = []
    for f in flights:
        if f.impact is None or not f.impact_pos or len(f.impact_pos) != 3:
            continue
        cam = keys.get(f.impact)
        if cam is None:
            continue
        dist = math.dist(cam[:3], f.impact_pos)
        if dist > SHAKE_RANGE:
            continue
        blast = BLAST.get(f.weapon, 16)
        need = SHAKE_SHARE * min(SHAKE_MAX, SHAKE_K * blast / (dist + 8))
        arrive = f.impact + dist / WAVE_SPEED
        window = [k for k in keys if arrive - 2 <= k <= arrive + SHAKE_WINDOW]
        if len(window) < 4:
            continue
        peak = 0.0
        for j in (3, 4):
            ang = [keys[k][j] for k in window]
            mean = sum(ang) / len(ang)
            peak = max(peak, max(abs(a - mean) for a in ang))
        if peak < need:
            out.append(f"удар {f.weapon} в {dist:.0f} блоках в тик {f.impact} не трясёт камеру "
                       f"({peak:.2f}° < {need:.2f}°)")
    return out


def _visible(r):
    return r.get("in") and r.get("seen", 0) >= 0.4 and not r.get("cloud") and not r.get("far") and r.get("drawn", True)


def _look(yaw, pitch):
    y, p = math.radians(yaw), math.radians(pitch)
    return (-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p))


def fpv_over_districts(cand, plan):
    """FPV над живыми районами: в ≥ 70 % кадров рядом (≤ 200 бл., ≤ 15 с, в конусе) бьёт или объект снят."""
    flights, _ = read_events(os.path.join(cand, f"{plan}.events.jsonl"))
    impacts = [(f.impact, f.impact_pos) for f in flights if f.impact is not None and f.impact_pos]
    rows = list(_jsonl(os.path.join(cand, "qa.jsonl")))
    if not rows:
        return []
    good = 0
    for r in rows:
        cam, fov = r.get("cam"), r.get("fov", 70)
        if cam is None:
            continue
        if _visible(r):
            good += 1
            continue
        look = _look(cam[3], cam[4])
        for t, pos in impacts:
            if not 0 <= r.get("t", 0) - t <= FPV_PAST_S * 20 or len(pos) != 3:
                continue
            d = [pos[j] - cam[j] for j in range(3)]
            far = math.sqrt(sum(x * x for x in d))
            if far > FPV_RANGE or far < 0.1:
                continue
            cos = sum(look[j] * d[j] / far for j in range(3))
            if cos >= math.cos(math.radians(fov / 2 + FPV_CONE)):
                good += 1
                break
    if good < FPV_SHARE * len(rows):
        return [f"FPV над глушью: признаки боя в {100 * good / len(rows):.0f}% кадров (нужно {FPV_SHARE:.0%})"]
    return []


def icbm_in_frame(cand):
    """МБР в кадре: в ≥ 80% кадров, где она в кадре, доля высоты кадра ≥ 0,02."""
    rows = [r for r in _jsonl(os.path.join(cand, "qa.jsonl")) if r.get("in")]
    if not rows:
        return []
    good = sum(1 for r in rows if r.get("share", 0) >= ICBM_SHARE)
    if good < ICBM_FRAMES * len(rows):
        return [f"МБР в кадре мельче {ICBM_SHARE:.0%} высоты в {100 * (1 - good / len(rows)):.0f}% кадров "
                f"из тех, где она видна"]
    return []


def frozen_frames(cand, plan):
    """Застывшие кадры: три и больше одинаковых PNG подряд (вывод встал)."""
    frames = os.path.join(cand, "frames", plan)
    if not os.path.isdir(frames):
        return []
    run, last, at = 0, None, None
    for name in sorted(os.listdir(frames)):
        if not name.endswith(".png"):
            continue
        try:
            with open(os.path.join(frames, name), "rb") as f:
                h = hashlib.md5(f.read()).hexdigest()
        except OSError:
            continue
        run, last = (run + 1, last) if h == last else (1, h)
        if run == 1:
            at = name
        if run >= FROZEN_RUN:
            return [f"застывшие кадры: {run} одинаковых подряд с {at}"]
    return []


def render_problems(cand, plan, fpv=False, icbm=False, shake=True):
    """Все проверки вывода папки кандидата: PASS плана с любой из них — FAIL (дубль переснимается)."""
    out = camera_turn(cand) + axis_fail(cand) + frozen_frames(cand, plan)
    if shake:
        out += shake_present(cand, plan)
    if fpv:
        out += fpv_over_districts(cand, plan)
    if icbm:
        out += icbm_in_frame(cand)
    return out


# ================================================================ вход

def main(argv=None):
    ap = argparse.ArgumentParser(description="проверки дубля Newisle по данным записи и вывода")
    ap.add_argument("mode", choices=("record", "render"))
    ap.add_argument("dir", help="папка дубля (record) или кандидата вывода (render)")
    ap.add_argument("--plan", help="план кандидата (для render)")
    ap.add_argument("--scenes", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "scenes"))
    ap.add_argument("--take", help="дубль в newisle-takes.json (record; по умолчанию — из result.json)")
    ap.add_argument("--fpv", action="store_true")
    ap.add_argument("--icbm", action="store_true")
    ap.add_argument("--no-shake", action="store_true", help="onboard, HUD и ядерные планы без проверки тряски")
    a = ap.parse_args(argv)
    if a.mode == "render":
        problems = render_problems(a.dir, a.plan, fpv=a.fpv, icbm=a.icbm, shake=not a.no_shake)
    else:
        result = load_json(os.path.join(a.dir, "result.json"), {})
        take_id = a.take or result.get("take") or os.path.basename(os.path.dirname(a.dir.rstrip("/")))
        takes = load_json(os.path.join(a.scenes, "newisle-takes.json"), {})
        take = next((t for t in takes.get("takes", []) if t["id"] == take_id), {})
        if not take:
            sys.exit(f"нет дубля {take_id} в {a.scenes}")
        problems = record_problems(a.dir, take, result)
        for w in record_warnings(a.dir, take, result):
            print("предупреждение: " + w)
    for p in problems:
        print(p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
