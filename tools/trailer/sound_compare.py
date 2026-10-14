#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Сверка звука мира с живым клиентом: живой клиент стоял в точке, пока писался план; что дают в той же точке журнал
звука мира (`<план>.sounds.jsonl`, WorldSounds) и повтор — и что из этого клиент на деле сыграл (его timeline.jsonl).

    python3 tools/trailer/sound_compare.py <timeline.jsonl живого клиента> <папка записи> <план>
        [--offset ТИКИ] [--clock t|gt] [--replay <кандидат вывода>] [--at x,y,z] [--floor-db -40]

Часы: тик живого клиента = тик журнала звука мира (`t` — тик повтора, `gt` — время игры сервера) + сдвиг; без
`--offset` сдвиг и часы подбираются по совпавшим звукам (то же событие, то же место), берётся больше совпадений.
Место живого клиента — его кадры (камера), без кадров — `--at`.

Строки отчёта:
- `match` — звук сервера есть в обоих; разница громкости у точки, дБ (журнал мира против клиента);
- `missing` — клиент слышал, восстановление не даёт: звук сервера, которого нет в журнале мира, или звук мода по пакету
  тревоги или аварии, который не ушёл игроку записи (`heard` у пакета в журнале), или — с `--replay` — звук мода, которого
  нет в повторе;
- `extra` — восстановление даёт слышимое в точке, а клиент не сыграл (пакет ему не дошёл, другой вариант записи);
- `trigger` — звук мода по пакету тревоги или аварии, пакет в журнале и ушёл игроку записи;
- `replay` — звук мода, который клиент собирает сам, есть и в повторе (только наличие: место у звука мода — от слушателя);
- `unchecked` — звук, который клиент собирает сам (мод, фон), без `--replay` не проверить.
Код выхода 1 — есть слышимое (громче `--floor-db`) `missing`; 2 — нечего сверять (нет журнала, нет сдвига).
"""
import argparse
import math
import os
import sys
from collections import Counter
from dataclasses import dataclass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import takes as T  # noqa: E402

FLOOR_DB = -40.0
# пакет тревоги или аварии → звук, который по нему играет клиент (client/hud/Alerts, NukeSounds, GridEffects)
TRIGGER_SOUNDS = {"siren": "incoming:siren", "nuke_warning": "incoming:nuke.alarm", "grid_failure": "incoming:grid.fail"}
TRIGGER_SLACK = (-0.5, 5.0)   # с: звук по пакету — от полсекунды до пакета (часы) до 5 с после
MOD_SLACK = 25.0              # с: звук мода от слушателя — у живого клиента и в повторе в пределах прихода фронта
CLIENT_ONLY = ("minecraft:ambient.", "minecraft:music", "minecraft:ui.", "minecraft:weather.")


@dataclass
class Row:
    kind: str                # match, missing, extra, trigger, replay, unchecked
    t: float                 # секунды живого клиента
    event: str
    pos: tuple
    db: float = None         # громкость у точки, дБ (живой клиент; у extra — восстановление)
    diff: float = None       # match: восстановление − живой, дБ
    why: str = ""


def db(g):
    return 20 * math.log10(g) if g > 0 else -math.inf


def gain(volume, rng, cam, pos):
    """Громкость у точки, как у движка: линейное затухание до дальности."""
    return min(volume, 1.0) * max(0.0, 1.0 - T.dist(cam, pos) / max(rng, 1e-3))


def live_take(path, plan):
    """Строки плана из журнала живого клиента; плана с таким именем нет — последний план журнала."""
    t = T.Take(plan, path, True)
    T.read_timeline(path, plan, t)
    if not t.sounds and not t.frames:
        heads = [r["shot"] for r in T.read_jsonl(path) if r.get("type") == "shot"]
        if heads:
            t = T.Take(heads[-1], path, True)
            T.read_timeline(path, heads[-1], t)
    return t


def clock(ws, which):
    return ws.gt if which == "gt" else ws.t


def align(live, world, which):
    """Сдвиг (тики) часов which журнала мира к часам живого клиента: голоса пар «то же событие, то же место», лучшие
    по окну ±2 тика — по числу совпавших звуков. (сдвиг, совпало) или (None, 0)."""
    by = {}
    for ws in world.sounds:
        by.setdefault(ws.event, []).append(ws)
    votes = Counter()
    for s in live.sounds:
        for ws in by.get(s.event, ()):
            if T.dist(s.pos, ws.pos) <= T.MATCH_BLOCKS:
                votes[round(s.t * T.TPS - clock(ws, which))] += 1
    if not votes:
        return None, 0
    near = Counter({o: sum(votes.get(o + d, 0) for d in range(-2, 3)) for o in votes})
    best, score = None, (0, 0.0)
    for o, _ in near.most_common(5):
        for d in range(-2, 3):
            k = o + d
            pairs = T.match_world(world.sounds, live.sounds, lambda ws: (clock(ws, which) + k) / T.TPS)
            # больше совпавших, при равенстве — меньше расхождение по времени
            s = (len(pairs), -sum(abs(c.t - (clock(world.sounds[i], which) + k) / T.TPS) for i, c in pairs.items()))
            if s > score:
                best, score = k, s
    return best, score[0]


def compare(live, world, offset=None, which=None, replay=None, at=None, floor_db=FLOOR_DB):
    """Сверка: (строки, сдвиг, часы). live — дубль живого клиента, world — журнал звука мира, replay — дубль вывода
    из повтора (его звуки мода), at — место клиента, если в его журнале нет кадров."""
    if offset is None:
        found = [(align(live, world, w), w) for w in ((which,) if which else ("t", "gt"))]
        (offset, _), which = max(found, key=lambda f: f[0][1])
        if offset is None:
            return None, None, which
    which = which or "t"

    def cam(t):
        return at if at is not None or not live.frames else live.cam_at(t)

    def sec(ws):
        return (clock(ws, which) + offset) / T.TPS

    if live.frames:
        t0, t1 = 0.0, live.duration
    else:
        ts = [s.t for s in live.sounds] or [0.0]
        t0, t1 = min(ts), max(ts)
    floor = 10 ** (floor_db / 20)
    events = {ws.event for ws in world.sounds}
    inside = [ws for ws in world.sounds if t0 - T.MATCH_SEC <= sec(ws) <= t1 + T.MATCH_SEC]
    heard = [s for s in live.sounds if t0 - T.MATCH_SEC <= s.t <= t1 + T.MATCH_SEC]
    pairs = T.match_world(inside, heard, sec)
    rows, matched = [], set()
    for i, s in pairs.items():
        ws = inside[i]
        matched.add(id(s))
        e = T.world_entry(ws, i, 0)
        g_live = s.gain_at(cam(s.t), s.t)
        g_world = gain(e["volume"], e["range"], cam(s.t), ws.pos) if e else 0.0
        diff = db(g_world) - db(g_live) if g_world > 0 and g_live > 0 else None
        rows.append(Row("match", s.t, s.event, s.pos, db(g_live), diff,
                        "" if e is None or e["file"] == s.file else f"вариант {e['file']} вместо {s.file}"))
    # пакеты тревоги и аварии, у которых живой клиент был в радиусе
    near_triggers = []
    for tr in world.triggers:
        t = sec(tr)
        c = cam(t)
        if c is not None and T.dist(c, tr.pos) < tr.radius and tr.kind in TRIGGER_SOUNDS:
            near_triggers.append((t, tr))
    used_triggers, replay_used = set(), set()
    for s in heard:
        if id(s) in matched or s.event.startswith(CLIENT_ONLY):
            continue
        g = s.gain_at(cam(s.t), s.t)
        if s.event in events:
            rows.append(Row("missing", s.t, s.event, s.pos, db(g), why="звук сервера: в журнале мира нет его в это время и в этом месте"))
            continue
        if not s.event.startswith("incoming:"):
            # событие уровня (ломка блока), звук только у клиента — журнал мира их не видит
            rows.append(Row("unchecked", s.t, s.event, s.pos, db(g), why="событие ни разу не в журнале мира: звук клиента"))
            continue
        tr = next((p for p in near_triggers if id(p[1]) not in used_triggers and s.event.startswith(TRIGGER_SOUNDS[p[1].kind])
                   and TRIGGER_SLACK[0] <= s.t - p[0] <= TRIGGER_SLACK[1]), None)
        if tr is not None:
            used_triggers.add(id(tr[1]))
            if tr[1].heard:
                rows.append(Row("trigger", s.t, s.event, s.pos, db(g), why=f"пакет {tr[1].kind} ушёл и игроку записи"))
            else:
                rows.append(Row("missing", s.t, s.event, s.pos, db(g),
                                why=f"пакет {tr[1].kind} (радиус {tr[1].radius:.0f}) не ушёл игроку записи: в повторе звука нет"))
            continue
        if replay is None:
            rows.append(Row("unchecked", s.t, s.event, s.pos, db(g), why="звук мода собирает клиент: нужен --replay"))
            continue
        twin = next((r for r in replay.sounds if id(r) not in replay_used and r.event == s.event
                     and abs((replay.tick(r.t) + offset) / T.TPS - s.t) <= MOD_SLACK), None) if which == "t" else None
        if twin is not None:
            replay_used.add(id(twin))
            rows.append(Row("replay", s.t, s.event, s.pos, db(g), why="есть и в повторе"))
        else:
            rows.append(Row("missing", s.t, s.event, s.pos, db(g), why="звука мода нет в повторе" if which == "t"
                            else "часы gt: повтор с ними не сверить"))
    # слышимое в точке по журналу мира, чего клиент не сыграл
    for i, ws in enumerate(inside):
        if i in pairs or ws.event.startswith(CLIENT_ONLY):
            continue
        e = T.world_entry(ws, i, 0)
        t = sec(ws)
        g = gain(e["volume"], e["range"], cam(t), ws.pos) if e else 0.0
        if g >= floor:
            rows.append(Row("extra", t, ws.event, ws.pos, db(g),
                            why=f"до точки {T.dist(cam(t), ws.pos):.0f} при рассылке {ws.send or 0:.0f}"))
    for t, tr in near_triggers:
        if id(tr) not in used_triggers and t0 <= t <= t1:
            rows.append(Row("extra", t, TRIGGER_SOUNDS[tr.kind], tr.pos, why=f"пакет {tr.kind} в радиусе, клиент звука не сыграл"))
    return sorted(rows, key=lambda r: r.t), offset, which


def report(rows, offset, which, floor_db=FLOOR_DB, out=None):
    """Текст отчёта; возвращает код выхода (1 — слышимое missing)."""
    out = out or sys.stdout
    counts = Counter(r.kind for r in rows)
    print(f"сдвиг {offset:+d} тиков (часы {which}); " + ", ".join(f"{k} {counts[k]}" for k in
                                                                     ("match", "missing", "extra", "trigger", "replay", "unchecked")), file=out)
    diffs = [r.diff for r in rows if r.kind == "match" and r.diff is not None]
    if diffs:
        diffs.sort()
        print(f"громкость совпавших, восстановление − клиент: медиана {diffs[len(diffs) // 2]:+.1f} дБ, "
              f"от {diffs[0]:+.1f} до {diffs[-1]:+.1f}", file=out)
    for r in rows:
        if r.kind == "match" and (r.diff is None or abs(r.diff) < 1.0) and not r.why:
            continue
        level = "" if r.db is None else f" {r.db:6.1f} дБ"
        diff = "" if r.diff is None else f" {r.diff:+.1f} дБ"
        pos = "" if r.pos is None else " [" + ", ".join(f"{v:.0f}" for v in r.pos) + "]"
        print(f"{r.kind:9} {r.t:7.2f} с {r.event}{pos}{level}{diff}  {r.why}".rstrip(), file=out)
    audible = [r for r in rows if r.kind == "missing" and r.db is not None and r.db >= floor_db]
    return 1 if audible else 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("live", help="timeline.jsonl живого клиента")
    ap.add_argument("recording", help="папка записи (replays/<план>.sounds.jsonl)")
    ap.add_argument("plan")
    ap.add_argument("--offset", type=int, help="тик клиента = тик журнала мира + сдвиг")
    ap.add_argument("--clock", choices=("t", "gt"), help="часы журнала мира: t — тик повтора, gt — время игры")
    ap.add_argument("--replay", help="кандидат вывода из повтора того же плана (его звуки мода)")
    ap.add_argument("--at", help="x,y,z живого клиента, если в его журнале нет кадров")
    ap.add_argument("--floor-db", type=float, default=FLOOR_DB, help="тише — не слышно (по умолчанию −40)")
    a = ap.parse_args(argv)
    world = T.read_world(os.path.join(a.recording, "replays", f"{a.plan}.sounds.jsonl"))
    if world is None:
        print(f"нет журнала звука мира {a.recording}/replays/{a.plan}.sounds.jsonl", file=sys.stderr)
        return 2
    live = live_take(a.live, a.plan)
    at = tuple(float(v) for v in a.at.split(",")) if a.at else None
    if not live.frames and at is None:
        print("в журнале живого клиента нет кадров: место — --at x,y,z", file=sys.stderr)
        return 2
    replay = T.load_take(a.plan, os.path.abspath(a.replay)) if a.replay else None
    rows, offset, which = compare(live, world, a.offset, a.clock, replay, at, a.floor_db)
    if rows is None:
        print("сдвиг часов не найти: ни одного общего звука; --offset", file=sys.stderr)
        return 2
    return report(rows, offset, which, a.floor_db)


if __name__ == "__main__":
    sys.exit(main())
