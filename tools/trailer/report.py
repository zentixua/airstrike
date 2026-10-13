#!/usr/bin/env python3
"""Отчёт по планам трейлера из повторов: что вывод снял и прошёл ли каждый план проверку кадров.

    python3 tools/trailer/report.py <папка записи>/render [--md отчёт.md]

Берёт render/report.jsonl (строка на кандидата: камера, доли кадров, причина) и render/best.txt (выбранный кандидат
плана). Доли — от кадров плана: «мир» — кадры с построенным миром, «до удара» — объект в кадре, виден и не мельче
нормы, «последняя секунда» — то же за секунду до удара, «удар» — удар в кадре и виден, «мотор» — звук снаряда слышен.
"""
import argparse
import json
import sys
from pathlib import Path


def pct(v):
    return "—" if v is None else f"{round(100 * v)}%"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("render", type=Path)
    ap.add_argument("--md", type=Path)
    a = ap.parse_args()
    rows = [json.loads(l) for l in (a.render / "report.jsonl").read_text().splitlines() if l.strip()]
    best = {}
    for line in (a.render / "best.txt").read_text().splitlines():
        parts = line.split(maxsplit=3)
        if len(parts) >= 3:
            best[parts[0]] = (Path(parts[1]).name, parts[2], parts[3] if len(parts) > 3 else "")
    shots = list(dict.fromkeys(r["shot"] for r in rows))
    out = ["| План | Камера | Итог | Мир | До удара | Последняя секунда | Удар | Мотор | Кандидатов | Причина |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    passed = 0
    for s in shots:
        cands = [r for r in rows if r["shot"] == s]
        name, verdict, why = best.get(s, (None, "FAIL", "нет итога"))
        r = next((c for c in cands if c["candidate"] == name), max(cands, key=lambda c: c["score"]))
        passed += verdict == "PASS"
        no_key = r.get("event", -1) < 0
        out.append(f"| {s} | {r['candidate']} | {verdict} | {pct(r['ready'])} | {pct(r['approach'])} | "
                   f"{'—' if no_key else pct(r['near_hit'])} | {'—' if no_key else pct(r['at_key'])} | {pct(r['engine'])} | "
                   f"{len(cands)} | {why or r['why']} |")
    out.append("")
    out.append(f"Прошли проверку: {passed} из {len(shots)}.")
    text = "\n".join(out) + "\n"
    if a.md:
        a.md.write_text(text)
    sys.stdout.write(text)


if __name__ == "__main__":
    main()
