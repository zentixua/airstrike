#!/usr/bin/env python3
"""Снаряды, ушедшие с пути у записывающего клиента: по журналам повторов (<запись>/replays/*.events.jsonl).

Очередь мест мода до 22b2cfd (13.10) при сервере, отставшем от темпа записи, шла прогнозом по прямой и не возвращалась
к пакетам сервера: журнал записал снаряд не там, где он летел, а вывод на исправленном моде показывает его на пути
сервера — камеры, поставленные по журналу, смотрят мимо. Признак — последнее место снаряда в журнале перед ударом
далеко от места взрыва (его журнал берёт из пакета взрыва сервера).

  python3 tools/trailer/drift.py mod/run/scenario/film-war4 [--min 10]
"""
import argparse
import json
import math
import statistics
import sys
from pathlib import Path


def flights(path):
    """Пути журнала: номер → {weapon, at: {тик: (x, y, z)} по сущности у клиента, impact, pos}."""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            try:
                o = json.loads(line)
            except json.JSONDecodeError:  # журнал оборвался на строке
                continue
            t = o["t"]
            if "new" in o:
                out[o["new"]] = {"weapon": o.get("weapon", "?"), "at": {}, "impact": None, "pos": None}
            elif "impact" in o:
                fl = out.get(o["impact"])
                if fl is not None:
                    fl["impact"], fl["pos"] = t, o["pos"]
            elif "f" in o:
                for e in o["f"]:
                    fl = out.get(e[0])
                    # по данным сервера (сущности у клиента нет) — не место у клиента
                    if fl is not None and (len(e) < 6 or e[5] != 0):
                        fl["at"][t] = (e[1], e[2], e[3])
    return out


def drift(fl):
    """(тик последнего места, расстояние до взрыва, шаг за тик, лишнее сверх ожидаемого) или None."""
    if fl["impact"] is None or not fl["at"]:
        return None
    ticks = sorted(k for k in fl["at"] if k <= fl["impact"])
    if len(ticks) < 3:
        return None
    last = ticks[-1]
    steps = [math.dist(fl["at"][a], fl["at"][b]) for a, b in zip(ticks[-11:], ticks[-10:]) if b == a + 1]
    step = statistics.median(steps) if steps else 0.0
    gap = math.dist(fl["at"][last], fl["pos"])
    # до взрыва снаряд проходит ещё (удар − последний тик) шагов и сам взрыв — чуть в стороне от носа
    expected = step * (fl["impact"] - last + 1) + 4
    return last, gap, step, gap - expected


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("rec", type=Path, help="папка записи (в ней replays/)")
    ap.add_argument("--min", type=float, default=10, help="лишнее расстояние до взрыва, блоков, с которого путь ушёл")
    a = ap.parse_args()
    files = sorted((a.rec / "replays").glob("*.events.jsonl"))
    if not files:
        sys.exit(f"нет журналов в {a.rec / 'replays'}")
    for path in files:
        plan = path.name.removesuffix(".events.jsonl")
        bad, n = [], 0
        for i, fl in flights(path).items():
            d = drift(fl)
            if d is None:
                continue
            n += 1
            last, gap, step, extra = d
            if extra >= a.min:
                bad.append(f"#{i} {fl['weapon']} {gap:.0f} блоков (шаг {step:.1f}, последний тик {last}, удар {fl['impact']})")
        mark = "УШЛИ" if bad else "ok"
        print(f"{plan}: {mark}, ударов с путём {n}" + "".join(f"\n    {b}" for b in bad))


if __name__ == "__main__":
    main()
