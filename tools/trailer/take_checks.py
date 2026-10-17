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
- полёт МБР: пути оружия nuke по местам от сущности (флаг 1 в строках `f`) — против кривой разгона нынешней
  модели (WeaponSpec.NUKE.Ascent, Minuteman III: a(t) = T/(m₀−ṁ·t) − g даёт на видимом подъёме — ~8,5 с до
  ухода за потолок мира — ускорение до 0,044 бл/тик² и скорость до ~7 бл/тик). Запись видит путь глазами
  клиента под нагрузкой: места приходят пачками и с отставанием до ~11 тиков (заморозка нескольких тиков →
  догоняющий скачок, T23 n31 и T23b n32), поэтому разности соседних тиков — шум сэмплинга, а не разгон, и
  меряются инварианты: путь с первого движения не обгоняет кривую, выровненную по запуску из отвесной фазы
  (первые 60 тиков, Ascent.pitchOverTick), больше чем на 2 тика прогноза клиента (ArrivalQueue.MAX_DEBT);
  средняя скорость между сменами места ≤ 5-кратной модельной (догоняющие пачки дают до ~2×); высота не
  падает (подъём отвесный, 85°). Предстартовая стойка на площадке (сущность стоит с IGNITION, пока её чанк
  не тикает, — и до окна плана) — не полёт и не меряется. Равномерно более крутой или более поздний разгон
  при таких данных от сдвига запуска неотличим — не проверяется; ловится скачок пути (телепорт).
- сирена дубля: у дублей с включённой сиреной (`siren: true` в newisle-takes.json; `false` — первый удар без
  тревоги) записывающий игрок слышит её (timeline.jsonl, входящий:siren) не меньше чем за 2 с до первого удара
  дубля (sounds.jsonl: триггер siren с heard) и в сумме не меньше 6 с;
- стая в бинокль: план ночной стаи (камера scope, watch-оружие drone, сумерки/ночь — в qa/<план>.jsonl HudQa)
  читается как стая: в квадрате окуляра (`in`) и не закрыты (`open`) не меньше min(6, пущенных сценой) дронов в
  ≥ 60 % кадров; видимого размера строки qa не дают — предела размера нет;
- толпа провожает ракеты (`crowd.follow` в ready плана): в окне каждого плана люди группы (приставка шага `crowd`)
  смотрят на ближнюю ракету этого оружия в 300 блоках — курс ±30°, тангаж ±15° от глаза (ноги + 1,62) — не меньше
  чем в 70 % взглядов: журнал записи пишет курс головы и тангаж людей (с 17.10); n43 ночью 16.10 — люди смотрели
  мимо ракет на 90–175°, слежение кончалось до окна;
- карта пульта без рельефа: у плана с открытой картой (result.json: `map_ticks`) нет тиков со строкой «Distant
  Horizons is still loading its own terrain» (`map_pending`) — план ждёт ответа DH (момент `map`) и только потом пишет.

Вывод (папка кандидата render/<план>/<кандидат>/, каждый провал — PASS плана становится FAIL, дубль переснимается):
- поворот камеры ≤ 120°/с по state.json (ключи на тик; по тикам × 20, не с интерполяцией до 60 кадров: в файле
  нет кадров между тиками, а плавная интерполяция Flashback не создаёт поворота быстрее ключей дольше одного
  кадра; курс через ±180°);
- ось ракеты: строки qa.jsonl с `axis` > 15° два тика подряд и дольше — нос модели расходится с ходом (замечание к
  v5: ракета летит «стоя»); один тик — рывок хода у клиента, не нос;
- тряска по расстоянию: у удара окна ближе 150 блоков к камере в миг прихода волны (17,15 блока/тик) путь вывода
  показывает толчок — пик отклонения угла (курс или тангаж) после вычитания среднего за окно ~0,6 с не меньше
  0,15·min(3,5°; 6·огненный шар/(расстояние+8)) — той же модели, что Operator.shake, с самым ровным стилем
  (tracker, доля 0,3): верный путь проходит с запасом ≥ 1,7×, `-raw` без тряски — нет. Ядерные планы (трясёт
  модель мода), onboard и HUD — без этой проверки;
- FPV над живыми районами: у FPV-планов в ≥ 70 % кадров — удар последних 15 с ближе 200 блоков в конусе взгляда
  (fov/2 + 10°) или объект снят по qa.jsonl (in, виден, не в облаках и тумане, секция построена); у планов с МБР —
  в ≥ 80 % кадров, где она в кадре, `share` ≥ 0,02 высоты кадра;
- застывшие кадры: в frames/<план>/ три и больше одинаковых подряд (хеш файла) — вывод встал;
- тёмный кадр (планам с `brightness` в cover, T07 n11): средняя яркость кадров вывода ниже предела — рассвет, на
  котором ущелье и ракеты не читаются (23200 мерилось 19 при прошедшем взгляде QA; PIL нет — не провал: замер нечем).

    python3 tools/trailer/take_checks.py record <папка дубля> [--scenes ПАПКА] [--take T04]
    python3 tools/trailer/take_checks.py render <папка кандидата> --plan <план> [--fpv] [--icbm] [--no-shake]
"""
import argparse
import hashlib
import json
import math
import os
import sys

import takes

READY, IGNITION, BOOST = 0, 1, 2          # FlightPhase (порядковый номер)
ENTITY_FLAG = 1                            # в строках `f`: место от сущности, не от пакетов сервера
MSPT_RUN, MSPT_MAX, MSPT_WINDOW = 10, 50.0, 2.0
DENSITY_ASPECT = 16 / 9   # кадр трейлера (INCOMING_SIZE), угол fov в qa.jsonl — по вертикали
ICBM_WEAPONS = ("nuke", "icbm")
# Разгон МБР — числа паспорта WeaponSpec.NUKE: Ascent (тяга 903 700 Н, стартовая масса 36 030 кг, топливо
# 20 780 кг за 61 с) и Airframe.cruiseSpeed 25 бл/тик; блок = метр, тик = 0,05 с (Ascent.step).
ICBM_ASCENT, ICBM_TOP_SPEED = (903_700, 36_030, 20_780, 61.0), 25.0
ICBM_PITCH_TICKS, ICBM_LEAD_TICKS, ICBM_LAG_TICKS, ICBM_BURST = 60, 2, 12, 5.0
ICBM_ROUND = 0.01                               # места в журнале округлены до 0,01 блока
SIREN_EVENT, SIREN_BEFORE_S, SIREN_TOTAL_S, SIREN_MAX_S = "incoming:siren", 2.0, 6.0, 26.5
FLOCK_MIN, FLOCK_SHARE = 6, 0.6
GAZE_YAW, GAZE_PITCH, GAZE_SHARE, GAZE_RANGE, GAZE_STEP, EYE = 30.0, 15.0, 0.7, 300.0, 2, 1.62
TURN_MAX, AXIS_MAX = 120.0, 15.0
WAVE_SPEED, SHAKE_RANGE, SHAKE_WINDOW, SHAKE_K, SHAKE_MAX = 17.15, 150.0, 12, 6.0, 3.5
SHAKE_SHARE = 0.15                         # доля от размаха модели Operator, ниже которой толчка нет
BLAST = {"rocket": 8, "drone": 12, "loiter": 12, "missile": 18, "bunker": 24}   # Rig.blastOf, нюк — 16
FPV_SHARE, FPV_PAST_S, FPV_RANGE, FPV_CONE = 0.7, 15.0, 200.0, 10.0
ICBM_SHARE, ICBM_FRAMES = 0.02, 0.8
FROZEN_RUN = 3
DIM_SAMPLE = 24                              # кадров на замер средней яркости


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


def _ascent_curves():
    """Кривые разгона МБР по тикам: (скорость, подъём) от старта — как IcbmEntity.serverTick копит Ascent.step."""
    thrust, mass, propellant, burn = ICBM_ASCENT
    speeds, climbs, v, c = [0.0], [0.0], 0.0, 0.0
    for n in range(1, 500):
        t = min((n - 1) / 20.0, burn)
        v = min(ICBM_TOP_SPEED, v + (thrust / (mass - propellant / burn * t) - 9.81) / 400)
        c += v
        speeds.append(v)
        climbs.append(c)
    return speeds, climbs


def _ascent_tick(climbs, climb):
    """Тик, на котором кривая подъёма проходит высоту (обратная к кривой)."""
    if climb <= 0:
        return 0
    lo, hi = 0, len(climbs) - 1
    while lo < hi:
        mid = (lo + hi) // 2
        if climbs[mid] < climb:
            lo = mid + 1
        else:
            hi = mid
    return lo


def _icbm_flight_problems(plan, samples, speeds, climbs):
    """Инварианты одного полёта МБР по местам от сущности; окно — с первого движения (стойка на площадке —
    сущность стоит с IGNITION, пока её чанк не тикает, — не полёт). Первая же причина — в журнал проблем."""
    if not samples:
        return []                                # мест от сущности нет (только пакеты сервера) — не повод
    moving = [(t, p) for k, (t, p) in enumerate(samples) if k and p != samples[k - 1][1]]
    if not moving:
        return [f"{plan}: МБР стоит на площадке весь план (не взлетела)"]
    start = samples[0][1]
    window = [(t, p) for t, p in samples if t >= moving[0][0]]
    # запуск по кривой: место пути — самое раннее, какое допускает разгон (клиент не обгоняет сервер больше
    # тика прогноза). Ровнять — по отвесной фазе (до наклона к 85°, Ascent.pitchOverTick, и отставание записи):
    # место после неё не имеет права оказаться выше кривой — иначе телепорт вверх
    implied = [(t - _ascent_tick(climbs, p[1] - start[1]), t) for t, p in window]
    earliest = min(t for t, _ in implied)
    launch = min(t for t, k in implied if k - earliest <= ICBM_PITCH_TICKS + ICBM_LAG_TICKS)
    for t, p in window:
        n = int(t - launch)
        if n > ICBM_PITCH_TICKS + ICBM_LAG_TICKS and n < len(climbs) and \
                p[1] - start[1] > climbs[n] + ICBM_LEAD_TICKS * speeds[n] + 5 * ICBM_ROUND:
            return [f"{plan}: МБР обгоняет разгон на {p[1] - start[1] - climbs[n]:.0f} бл в тик {t} "
                    f"(кривая даёт {climbs[n]:.0f} бл)"]
    # средняя скорость между сменами места: заморозка и догоняющий скачок — одна пачка, скорость в ней ≈
    # истинная; заметно больше модельной — скачок пути (в любую сторону)
    runs = []
    for t, p in window:
        if runs and runs[-1][2] == p:
            runs[-1][1] = t
        else:
            runs.append([t, t, p])
    for a, b in zip(runs, runs[1:]):
        n = max(0, min(int((a[0] + b[0]) / 2 - launch), len(speeds) - 1))
        speed = math.dist(a[2], b[2]) / (b[0] - a[0])
        if speed > ICBM_BURST * speeds[n] + 10 * ICBM_ROUND:
            return [f"{plan}: МБР скачок пути {speed:.1f} бл/тик между тиками {a[1]} и {b[0]} "
                    f"(разгон даёт {ICBM_BURST:.0f}×{speeds[n]:.1f})"]
    # подъём отвесный (85°): высота не падает
    for (t0, p0), (t1, p1) in zip(window, window[1:]):
        if p1[1] < p0[1] - 2 * ICBM_ROUND:
            return [f"{plan}: МБР падает с {p0[1]:.1f} до {p1[1]:.1f} между тиками {t0} и {t1}"]
    return []


def icbm_flight(rec):
    """МБР: путь сущности против кривой разгона модели (WeaponSpec.NUKE.Ascent) — телепорт и скачок пути
    ловятся, пачечная запись с отставанием клиента — нет (T23 n31, T23b n32)."""
    out = []
    speeds, climbs = _ascent_curves()
    replays = os.path.join(rec, "replays")
    for name in sorted(os.listdir(replays)) if os.path.isdir(replays) else []:
        if not name.endswith(".events.jsonl"):
            continue
        plan = name[:-len(".events.jsonl")]
        flights, _ = read_events(os.path.join(replays, name))
        for f in flights:
            if f.weapon in ICBM_WEAPONS:
                out += _icbm_flight_problems(plan, f.samples(), speeds, climbs)
    return out


def siren_take(rec, take, plans):
    """Сирена дубля (у включённых): слышана до первого удара (не меньше 2 с) и в сумме не меньше 6 с — как услышит
    монтаж: сыгранные клиентом вывода (timeline.jsonl кандидата) плюс поставленные монтажом (сирена до окна вывода
    повтором не играется, ставит takes.siren_sfx_for)."""
    if not take.get("siren", True):
        return []      # первый удар без тревоги: siren: false — сирены в мире нет
    out = []
    for plan in plans:
        d = _pass_candidate(rec, plan)
        if not d:
            continue
        tk = takes.load_take(plan, d)
        extra = takes.siren_sfx_for(plan, d)
        impacts = sorted(f.impact for j in (tk.journal, tk.record_journal) if j
                         for f in j.flights if f.impact is not None)
        heard = [s.t for s in tk.sounds if s.event == SIREN_EVENT] + [e["t"] / 20.0 for e in extra]
        if impacts:
            t0 = tk.sec(impacts[0])
            if not any(t <= t0 - SIREN_BEFORE_S for t in heard):
                out.append(f"{plan}: сирена {'позднее 2 с до удара' if any(t < t0 for t in heard) else 'не слышна'} "
                           f"(первый удар на {t0:.1f} с)")
        total = takes.siren_seconds(tk, extra)
        if heard and total < SIREN_TOTAL_S:
            out.append(f"сирена слышна {total:.1f} с из {SIREN_TOTAL_S:.0f}")
    return out


def _pass_candidate(rec, plan):
    """Папка кандидата вывода плана, прошедшего проверку (render/best.txt, PASS); нет — None."""
    best = os.path.join(rec, "render", "best.txt")
    if not os.path.exists(best):
        return None
    with open(best, encoding="utf-8") as f:
        for line in f:
            parts = line.split(maxsplit=3)
            if len(parts) >= 3 and parts[0] == plan and parts[2] == "PASS" and parts[1] != "-":
                return os.path.join(rec, "render", plan, parts[1])
    return None


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


def _crowd_names(take, group):
    """Имена людей группы толпы дубля: приставка шага `crowd` (Des0, Des1, …) и названные по имени."""
    prefix, names = None, set()
    for a in take.get("stage", []):
        if a.get("do") == "crowd" and a.get("group") == group:
            prefix = a.get("prefix", prefix)
            names.update(a.get("names", ()))
    return lambda n: n in names or (prefix is not None and n.startswith(prefix))


def _gaze_rows(path):
    """events.jsonl → (отметки [(тик, что)], пути {i: (оружие, {тик: место})}, взгляды {тик: [(имя, глаз, курс, тангаж)]}).
    Строки людей без взгляда (журналы до 17.10) пропускаются."""
    marks, flights, gaze = [], {}, {}
    for r in _jsonl(path):
        t = r.get("t", 0)
        if "mark" in r:
            marks.append((t, r["mark"]))
        elif "new" in r:
            flights[r["new"]] = (r.get("weapon", ""), {})
        elif "f" in r:
            for e in r["f"]:
                if e[0] in flights:
                    flights[e[0]][1][t] = (e[1], e[2], e[3])
        if "p" in r:
            rows = [(p[0], (p[1], p[2] + EYE, p[3]), p[4], p[5]) for p in r["p"] if len(p) >= 6]
            if rows:
                gaze[t] = rows
    return marks, flights, gaze


def crowd_gaze(rec, take):
    """Толпа провожает ракеты (`crowd.follow` в ready плана): в окне каждого плана люди группы смотрят на ближнюю
    ракету этого оружия (в 300 блоках, как War.trackLook) — курс в пределах 30°, тангаж в пределах 15° — не меньше
    чем в 70 % взглядов (раз в 2 тика). Журнал до 17.10 без взглядов — не провал."""
    out = []
    for s in take.get("shots", []):
        follow = [a for a in s.get("ready", []) if a.get("do") == "crowd.follow"]
        if not follow or not s.get("plans"):
            continue
        marks, flights, gaze = _gaze_rows(os.path.join(rec, "replays", f"{s['plans'][0]}.events.jsonl"))
        if not gaze:
            continue
        order = sorted((t, m[5:]) for t, m in marks if m.startswith("plan:"))
        end = max((t for t, m in marks if m == "end"), default=max(gaze))
        for a in follow:
            member, weapon = _crowd_names(take, a.get("group")), a.get("weapon", "")
            paths = [p for w, p in flights.values() if w == weapon]
            for k, (t0, plan) in enumerate(order):
                t1 = order[k + 1][0] if k + 1 < len(order) else end
                good = total = 0
                for t in range(t0, t1, GAZE_STEP):
                    for name, eye, yaw, pitch in gaze.get(t, ()):
                        if not member(name):
                            continue
                        near = min((p[t] for p in paths if t in p), key=lambda q: math.dist(q, eye), default=None)
                        if near is None or math.dist(near, eye) > GAZE_RANGE:
                            continue
                        d = [near[i] - eye[i] for i in range(3)]
                        want_yaw = math.degrees(math.atan2(-d[0], d[2]))
                        want_pitch = -math.degrees(math.atan2(d[1], math.hypot(d[0], d[2])))
                        total += 1
                        if abs(_wrap(yaw - want_yaw)) <= GAZE_YAW and abs(pitch - want_pitch) <= GAZE_PITCH:
                            good += 1
                if total and good < GAZE_SHARE * total:
                    out.append(f"{plan}: толпа {a.get('group')} смотрит на ракету в {100 * good / total:.0f}% взглядов "
                               f"(курс ±{GAZE_YAW:.0f}°, тангаж ±{GAZE_PITCH:.0f}°; нужно {GAZE_SHARE:.0%})")
    return out


def map_loading(result):
    """Карта пульта в кадре со строкой «Distant Horizons is still loading its own terrain» (n07 16.10: пустая сетка без
    рельефа): result.json дубля пишет у плана тики с открытой картой (`map_ticks`) и из них со строкой ожидания DH
    (`map_pending`); хоть один такой тик — провал."""
    out = []
    for s in (result or {}).get("shots", []):
        pending = s.get("map_pending", 0)
        if pending:
            plan = (s.get("plans") or [s.get("replay", "?")])[0]
            out.append(f"{plan}: на карте пульта строка загрузки Distant Horizons {pending} тиков из {s.get('map_ticks', 0)}")
    return out


def map_aim(result):
    """Карта пульта в кадре ставит одну конечную точку, без маршрута (Артём 17.10 о n07: курсор щёлкал каждые полсекунды
    и маршрут прыгал): result.json пишет у плана, сколько раз на карте вставала новая точка (`map_marks`) и сколько самое
    большее было точек маршрута (`map_route`). Ни одной точки — щелчок не попал в карту; больше одной или маршрут —
    месиво. Запись без этих полей (до 17.10) не проверяется."""
    out = []
    for s in (result or {}).get("shots", []):
        if "map_marks" not in s:
            continue
        plan = (s.get("plans") or [s.get("replay", "?")])[0]
        if s["map_marks"] != 1:
            out.append(f"{plan}: на карте пульта конечных точек {s['map_marks']}, нужна одна")
        if s.get("map_route", 0):
            out.append(f"{plan}: на карте пульта точек маршрута {s['map_route']}, маршрут прыгает")
    return out


def sam_outcome(take, result):
    """Исход ПВО плана против `expect` его записи в листе дублей: `{"kills": [от, до], "interceptors": n}` — сбито ЗРК
    столько ударов плана (поле `intercepted` у удара в result.json) и зенитных ракет в полёте за запись не меньше
    `sam_launched` (Артём 17.10 о n17: батарей стреляет много, сбито одно-два, остальные шахеды прорываются к городу).
    План без `expect` не должен терять удары от ЗРК. Исход случаен (вероятность поражения), поэтому провал — пересъёмка."""
    out = []
    by_plans = {tuple(s.get("plans", [])): s for s in (result or {}).get("shots", [])}
    for spec in take.get("shots", []):
        want = spec.get("expect")
        s = by_plans.get(tuple(spec.get("plans", [])))
        if s is None:
            continue
        plan = spec["plans"][0]
        kills = sum(1 for k in s.get("strikes", []) if k.get("intercepted"))
        if not want:
            # ЗРК в плане нет, а удар сбит: остался ЗРК прошлой сцены (17.10: sam.clear в незагруженном чанке —
            # старый ЗРК у (-538, 308) сбил шахед n46 до его окна)
            if kills:
                out.append(f"{plan}: сбито ЗРК {kills}, а ЗРК в плане нет")
            continue
        lo, hi = want.get("kills", [0, len(s.get("strikes", []))])
        if not lo <= kills <= hi:
            out.append(f"{plan}: сбито {kills}, нужно {lo}–{hi}")
        if s.get("sam_launched", 0) < want.get("interceptors", 0):
            out.append(f"{plan}: зенитных ракет {s.get('sam_launched', 0)}, нужно не меньше {want['interceptors']}")
    return out


def record_problems(rec, take, result):
    """Проверки записи, провал которых лечит пересъёмка: список причин для attempt() конвейера."""
    return (same_tick_launches(rec) + icbm_flight(rec) + flock_in_scope(rec, take) + crowd_gaze(rec, take)
            + map_loading(result) + map_aim(result) + sam_outcome(take, result))


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
    """Ось ракеты: кадры qa.jsonl, где нос модели расходится с ходом сильнее 15° два тика игры и дольше. Один тик —
    рывок хода, а не нос: место снаряда у клиента на тик уходит назад, и ось ровно 179° на три кадра (T06 и T17
    ночью 15.10); «ракета стоя» (замечание к v5) держится много тиков."""
    run = []
    for r in _jsonl(os.path.join(cand, "qa.jsonl")):
        a = r.get("axis")
        if not (isinstance(a, (int, float)) and a > AXIS_MAX):
            run = []
            continue
        run.append(r)
        if len({math.floor(x.get("t") or 0) for x in run}) >= 2:
            return [f"ось ракеты {a:.0f}° к ходу с тика {run[0].get('t')} (предел {AXIS_MAX:.0f}°)"]
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


def _mean_luma(path):
    """Средняя яркость кадра (0–255), кадр ужат до 320 пикселей: замер ровный, а не пиксель в пиксель."""
    from PIL import Image
    with Image.open(path) as im:
        g = im.convert("L")
        g.thumbnail((320, 320))
        h = g.histogram()
        total = sum(h)
        return sum(i * c for i, c in enumerate(h)) / total if total else 0.0


def dim_frames(cand, plan, floor):
    """Тёмный кадр: средняя яркость кадров вывода ниже предела плана (`brightness` в cover) — время суток, на
    которое объект не читается, как рассвет 23200 у T07 n11 (средняя 19). Провал — дубль переснимается."""
    if not floor:
        return []
    frames = os.path.join(cand, "frames", plan)
    if not os.path.isdir(frames):
        return []
    names = sorted(n for n in os.listdir(frames) if n.endswith(".png"))
    if not names:
        return []
    step = max(1, len(names) // DIM_SAMPLE)
    values = []
    for name in names[::step]:
        try:
            values.append(_mean_luma(os.path.join(frames, name)))
        except OSError:
            continue
    mean = sum(values) / len(values) if values else 0.0
    if mean < floor:
        worst = min(values) if values else 0.0
        return [f"средняя яркость кадров {mean:.0f} (худший {worst:.0f}) < {floor:.0f}"]
    return []


def hits_in_frame(cand, plan, span):
    """Удары в кадре вывода за первые `span` секунд окна плана: место удара из журнала вывода в поле зрения камеры
    кадра (qa.jsonl: место, курс, тангаж, угол кадра по вертикали; кадр 16:9). Без лучей по блокам: ковёр меряется
    числом вспышек в кадре, а не тем, сколько каждой видно."""
    marks, impacts = {}, []
    for r in _jsonl(os.path.join(cand, f"{plan}.events.jsonl")):
        if "mark" in r:
            marks.setdefault(r["mark"], r["t"])
        elif "impact" in r and r.get("pos"):
            impacts.append((r.get("at", r["t"]), r["pos"]))
    start = marks.get("start")
    cams = {}
    for r in _jsonl(os.path.join(cand, "qa.jsonl")):
        if r.get("cam") and "t" in r:
            cams.setdefault(int(round(r["t"])), (r["cam"], r.get("fov", 70.0)))
    if start is None or not cams:
        return None
    count = 0
    for t, pos in impacts:
        if not start <= t <= start + span * 20:
            continue
        cam, fov = cams.get(t) or cams[min(cams, key=lambda k: abs(k - t))]
        x, y, z, yaw, pitch = cam[:5]
        dx, dy, dz = pos[0] - x, pos[1] - y, pos[2] - z
        ry, rp = math.radians(yaw), math.radians(pitch)
        # в осях камеры: вперёд, вправо, вверх (курс Minecraft: 0 — на +z, тангаж плюс — вниз)
        fx, fy, fz = -math.sin(ry) * math.cos(rp), -math.sin(rp), math.cos(ry) * math.cos(rp)
        rx, rz = -math.cos(ry), -math.sin(ry)
        ux, uy, uz = -math.sin(ry) * math.sin(rp), math.cos(rp), math.cos(ry) * math.sin(rp)
        ahead = dx * fx + dy * fy + dz * fz
        if ahead <= 0:
            continue
        half = math.tan(math.radians(fov) / 2)
        if abs(dx * rx + dz * rz) / ahead <= half * DENSITY_ASPECT and abs(dx * ux + dy * uy + dz * uz) / ahead <= half:
            count += 1
    return count / span


def sparse_hits(cand, plan, density):
    """Ковёр реже нормы плана (`density` в cover: {"min": ударов в кадре за секунду, "span": секунд окна} — кусок
    монтажа): n41 17.10 — 1–2 вспышки разом вместо «по всему кадру». Провал — дубль переснимается."""
    if not density:
        return []
    rate = hits_in_frame(cand, plan, float(density.get("span", 5)))
    if rate is not None and rate < float(density["min"]):
        return [f"ударов в кадре {rate:.1f} в секунду < {float(density['min']):.1f}"]
    return []


def render_problems(cand, plan, fpv=False, icbm=False, shake=True, brightness=None, density=None):
    """Все проверки вывода папки кандидата: PASS плана с любой из них — FAIL (дубль переснимается)."""
    out = camera_turn(cand) + axis_fail(cand) + frozen_frames(cand, plan)
    if shake:
        out += shake_present(cand, plan)
    if fpv:
        out += fpv_over_districts(cand, plan)
    if icbm:
        out += icbm_in_frame(cand)
    if brightness:
        out += dim_frames(cand, plan, float(brightness))
    if density:
        out += sparse_hits(cand, plan, density)
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
    ap.add_argument("--brightness", type=float, help="предел средней яркости кадров (T07 n11: рассвет)")
    a = ap.parse_args(argv)
    if a.mode == "render":
        problems = render_problems(a.dir, a.plan, fpv=a.fpv, icbm=a.icbm, shake=not a.no_shake,
                                   brightness=a.brightness)
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
