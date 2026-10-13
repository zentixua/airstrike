#!/usr/bin/env python3
"""Отчёт по планам трейлера из повторов: что вывод снял и прошёл ли каждый план проверку кадров.

    python3 tools/trailer/report.py <папка записи>/render [--md отчёт.md]

Берёт render/report.jsonl (строка на кандидата: камера, доли кадров, причина, метка вывода run) и render/best.txt
(выбранный кандидат плана). report.jsonl копит строки всех выводов: у плана в счёт — только строки его последнего
вывода. Доли — от кадров плана: «мир» — кадры с построенным миром, «до удара» — объект в кадре, виден и не мельче
нормы, «последняя секунда» — то же за секунду до удара, «удар» — удар в кадре, виден и не за дымом прежних ударов,
«мотор» — звук снаряда слышен, «в дыму» — кадры до удара, где больше половины кадра за дымом.
Планы, снятые вживую с проверкой кадров (HudQa), — из hud.jsonl папки записи (рядом с render): камера «hud», у снарядов
дальше прорисовки «до удара» — худшая доля последней секунды, «удар» — худший из ударов плана. Отключение света в кадре
(LampsQa: лампы сети перед камерой плана при записи) — своей таблицей из grid.jsonl папки записи. План из render/shots.txt
(планы вывода) без строки в report.jsonl — «не выведен»: вывод до него не дошёл.
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
    last = {r["shot"]: r.get("run") for r in rows}
    rows = [r for r in rows if r.get("run") == last[r["shot"]]]
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
    planned = a.render / "shots.txt"
    if planned.exists():
        missing = [s for s in planned.read_text().split() if s not in shots]
        rows += [{"shot": s, "pass": False, "why": "не выведен: вывод до плана не дошёл"} for s in missing]
        shots += missing
    out = ["| План | Камера | Итог | Мир | До удара | Последняя секунда | Удар | Мотор | В дыму | Кандидатов | Причина |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    passed = 0
    for s in shots:
        cands = [r for r in rows if r["shot"] == s]
        name, verdict, why = best.get(s, (None, "FAIL", ""))
        # строка без камеры (план не снят: нет объекта, камеры, повтор закрылся) — без camera, score и долей; таких
        # строк несколько — последняя
        scored = [c for c in cands if "score" in c]
        r = next((c for c in reversed(cands) if c.get("candidate") == name), None) \
            or (max(scored, key=lambda c: c["score"]) if scored else cands[-1])
        passed += verdict == "PASS"
        no_key = r.get("event", -1) < 0
        out.append(f"| {s} | {r.get('candidate', '—')} | {verdict} | {pct(r.get('ready'))} | {pct(r.get('approach'))} | "
                   f"{'—' if no_key else pct(r.get('near_hit'))} | {'—' if no_key else pct(r.get('at_key'))} | {pct(r.get('engine'))} | "
                   f"{pct(r.get('smoky'))} | {sum('candidate' in c for c in cands)} | {why or r.get('why', '')} |")
    out.append("")
    out.append(f"Прошли проверку: {passed} из {len(shots)}.")
    grid = a.render.parent / "grid.jsonl"
    if grid.exists():
        # отключение света в кадре (LampsQa при записи): последняя строка плана — его итог
        last_grid = {}
        for line in grid.read_text().splitlines():
            if line.strip():
                r = json.loads(line)
                last_grid[r["shot"]] = r
        out += ["", "| План | Свет в кадре | Горели в начале | Погасли к концу | Первая погасла, тик | Причина |",
                "|---|---|---|---|---|---|"]
        for s, r in last_grid.items():
            out.append(f"| {s} | {r['verdict']} | {r['lit_start']} | {r['unlit_end']} из {r['lamps_end']} | {r['dark_at']} | {r['why']} |")
        out.append("")
        out.append(f"Свет в кадре: прошли {sum(r['verdict'] == 'PASS' for r in last_grid.values())} из {len(last_grid)}.")
    fight = a.render.parent / "fight.jsonl"
    if fight.exists():
        # бой глазами игрока (FightQa при записи): оружие в руке, противники в кадре, выстрелы; последняя строка плана — итог
        last_fight = {}
        for line in fight.read_text().splitlines():
            if line.strip():
                r = json.loads(line)
                last_fight[r["shot"]] = r
        out += ["", "| План | Бой | Оружие в руке | Противников в кадре | ≥3 в кадре | Выстрелов / тиков атаки | Звуки | Причина |",
                "|---|---|---|---|---|---|---|---|"]
        for s, r in last_fight.items():
            out.append(f"| {s} | {r['verdict']} | {r['armed']} из {r['ticks']} | {r['foes_min']}–{r['foes_max']} | {pct(r['foes_share'])} | "
                       f"{r['shots']} / {r['attack']} | {r['sounds']} | {r['why']} |")
        out.append("")
        out.append(f"Бой глазами игрока: прошли {sum(r['verdict'] == 'PASS' for r in last_fight.values())} из {len(last_fight)}.")
    text = "\n".join(out) + "\n"
    if a.md:
        a.md.write_text(text)
    sys.stdout.write(text)


if __name__ == "__main__":
    main()
