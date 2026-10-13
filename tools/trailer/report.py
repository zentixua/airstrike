#!/usr/bin/env python3
"""Отчёт по планам трейлера из повторов: что вывод снял и прошёл ли каждый план проверку кадров.

    python3 tools/trailer/report.py <папка записи>/render [--md отчёт.md]

Берёт render/report.jsonl (строка на кандидата: камера, доли кадров, причина) и render/best.txt (выбранный кандидат
плана). Доли — от кадров плана: «мир» — кадры с построенным миром, «до удара» — объект в кадре, виден и не мельче
нормы, «последняя секунда» — то же за секунду до удара, «удар» — удар в кадре, виден и не за дымом прежних ударов,
«мотор» — звук снаряда слышен, «в дыму» — кадры до удара, где больше половины кадра за дымом.
Планы, снятые вживую с проверкой кадров (HudQa), — из hud.jsonl папки записи (рядом с render): камера «hud», у снарядов
дальше прорисовки «до удара» — худшая доля последней секунды, «удар» — худший из ударов плана.
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
    hud = a.render.parent / "hud.jsonl"
    if hud.exists():
        # живой план снимается заново целиком: последняя строка плана — его итог
        for line in hud.read_text().splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            rows = [x for x in rows if x["shot"] != r["shot"]] + [r]
            best[r["shot"]] = ("hud", r["verdict"], r["why"])
    shots = list(dict.fromkeys(r["shot"] for r in rows))
    out = ["| План | Камера | Итог | Мир | До удара | Последняя секунда | Удар | Мотор | В дыму | Кандидатов | Причина |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    passed = 0
    for s in shots:
        cands = [r for r in rows if r["shot"] == s]
        name, verdict, why = best.get(s, (None, "FAIL", "нет итога"))
        # строка без камеры (ни один кандидат не прошёл оценку) — без camera, score и долей
        r = next((c for c in cands if c.get("candidate") == name), None) \
            or max(cands, key=lambda c: c.get("score", float("-inf")))
        passed += verdict == "PASS"
        no_key = r.get("event", -1) < 0
        out.append(f"| {s} | {r.get('candidate', '—')} | {verdict} | {pct(r.get('ready'))} | {pct(r.get('approach'))} | "
                   f"{'—' if no_key else pct(r.get('near_hit'))} | {'—' if no_key else pct(r.get('at_key'))} | {pct(r.get('engine'))} | "
                   f"{pct(r.get('smoky'))} | {len(cands)} | {why or r.get('why', '')} |")
    out.append("")
    out.append(f"Прошли проверку: {passed} из {len(shots)}.")
    text = "\n".join(out) + "\n"
    if a.md:
        a.md.write_text(text)
    sys.stdout.write(text)


if __name__ == "__main__":
    main()
