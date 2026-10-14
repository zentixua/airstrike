#!/usr/bin/env python3
"""Монтаж трейлера на Newisle из того, что снял конвейер (produce.py): план монтажа по листу аниматика
(scenes/newisle-animatic.json) — склейки на местах и длинах листа, дубль плана — прошедший проверку вывод (PASS в
best.txt) или живая запись; плана нет — чёрная заливка с его именем (черновик показывает дыру; план живого дубля
Артёма, ещё не снятый, — «утром с Артёмом»). Сам монтаж —
montage.py (проверка verdict.py: склейка, которую проверка не пропускает, в черновике становится заливкой).

    python3 tools/trailer/newisle_cut.py --work ПАПКА_РАБОТЫ [--draft | --final] [--music Juggernaut.mp3] [--check]

--draft  — 1280×720, 30 кадров: только прошедшие планы, дыры — заливкой
--final  — в размере записи: только если прошли все планы листа, иначе отказ со списком
Итог — <работа>/cut/newisle-draft.mp4 (или newisle-final.mp4), рядом — план монтажа, таблица склеек и отчёт проверки.
"""
import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SCENES = os.path.join(HERE, "scenes")
REPO = os.path.dirname(os.path.dirname(HERE))
BEAT0, BEAT = 1.924, 0.95999      # сетка трека, как в edit.py
FAIL_RE = re.compile(r"план (\S+)")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def best(path):
    """Строки best.txt вывода: план → папка кандидата, если PASS."""
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    for line in lines:
        parts = line.split(maxsplit=3)
        if len(parts) >= 3 and parts[2] == "PASS" and parts[1] != "-":
            out[parts[0]] = parts[1]
    return out


def shot_dirs(state, takes):
    """План → папка дубля для монтажа: вывод из повтора (PASS) или папка живой записи (кадры вживую, глазами игрока)."""
    by_id = {t["id"]: t for t in takes["takes"]}
    runs = []
    for take, st in state.get("takes", {}).items():
        ok = [a for a in st.get("attempts", []) if a.get("ok")]
        if ok and st.get("status") == "passed":
            runs.append((take, ok[-1]))
    for take, st in state.get("live", {}).items():
        ok = [a for a in st.get("attempts", []) if a.get("ok")]
        if ok:
            runs.append((take, ok[-1]))
    dirs = {}
    for take, a in runs:
        rec = a["rec"]
        for plan, d in best(os.path.join(rec, "render", "best.txt")).items():
            dirs[plan] = d
        for s in by_id[take]["shots"]:
            if not s.get("cover"):
                for plan in s["plans"]:
                    dirs.setdefault(plan, rec)
    return dirs


def live_plans(takes):
    """Планы живых дублей Артёма (дубль с `artem`): их снимает он сам, конвейер ночью — нет."""
    return {p for t in takes["takes"] if "artem" in t for s in t["shots"] for p in s["plans"]}


def plan_of(sheet, dirs, broken=(), music=None, later=()):
    """План монтажа для montage.py по листу: склейки по порядку листа, длины — его (такты — сеткой трека); неснятый план
    из `later` (живые дубли) — заливка «утром с Артёмом»."""
    bar = float(sheet.get("bar", 2 * BEAT))
    cuts, t, starts = [], 0.0, []
    for i, s in enumerate(sheet["shots"]):
        dur = float(s["dur"]) if "dur" in s else float(s["bars"]) * bar
        starts.append(t)
        t += dur
        if "card" in s:
            c = {"kind": "fill", "level": 0, "dur": dur, "id": f"card{i + 1}"}
            if s["card"]:
                c["text"] = {"lines": s["card"].split("\n"), "style": "end"}
            cuts.append(c)
            continue
        plan = s["plan"]
        if plan in dirs and plan not in broken:
            c = {"take": plan, "kind": "scene", "anchor": "start", "dur": dur, "hold": False, "plan": plan}
        else:
            why = "утром с Артёмом" if plan in later and plan not in broken else "not shot"
            c = {"kind": "fill", "level": 0, "dur": dur, "id": f"{plan}-missing",
                 "text": {"lines": [f"{plan} — {why}"], "style": "title"}, "plan": plan}
        lb = sheet.get("letterbox") or {}
        if lb.get("until") == plan and "take" in c:
            c["open"] = f"start+{float(lb.get('open', 0)):.2f}"
        cuts.append(c)
    out = {"name": "newisle-draft", "takes": {p: d for p, d in dirs.items() if p not in broken},
           "letterbox": bool(sheet.get("letterbox")), "cuts": cuts}
    if music:
        pieces = []
        for part in sheet["music"]["parts"]:
            k0 = round((part["track"] - BEAT0) / BEAT)
            k1 = round((part["end"] - BEAT0) / BEAT)
            at = min(range(len(starts)), key=lambda i: abs(starts[i] - part["at"]))
            c = cuts[at]
            p = {"from": k0, "to": k1, "start": c.get("id") or c.get("take")}
            if "gain" in part:
                p["gain"] = [[k0, round(10 ** (part["gain"] / 20), 3)], [k1, round(10 ** (part["gain"] / 20), 3)]]
            if "fade" in part:
                p["fade"] = part["fade"]
            pieces.append(p)
        out["music"] = pieces
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description="монтаж Newisle из снятого конвейером")
    ap.add_argument("--work", required=True)
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--draft", action="store_true")
    g.add_argument("--final", action="store_true")
    ap.add_argument("--music")
    ap.add_argument("--check", action="store_true", help="только план монтажа и проверка")
    a = ap.parse_args(argv)
    state = load(os.path.join(a.work, "state.json"))
    takes = load(os.path.join(SCENES, "newisle-takes.json"))
    sheet = load(os.path.join(SCENES, "newisle-animatic.json"))
    dirs = shot_dirs(state, takes)
    need = [s["plan"] for s in sheet["shots"] if "plan" in s]
    missing = [p for p in need if p not in dirs]
    if a.final and missing:
        sys.exit("чистовой монтаж ждёт все планы; не сняты: " + ", ".join(missing))
    out = os.path.join(a.work, "cut")
    os.makedirs(out, exist_ok=True)
    name = "newisle-final" if a.final else "newisle-draft"
    music = a.music or os.environ.get("INCOMING_MUSIC")
    if not music and os.path.exists(sheet["music"].get("file", "")):
        music = sheet["music"]["file"]
    broken = set()
    montage = os.path.join(HERE, "montage.py")
    for _ in range(8):
        plan = plan_of(sheet, dirs, broken, music, later=live_plans(takes))
        plan["name"] = name
        path = os.path.join(out, name + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(plan, f, ensure_ascii=False, indent=1)
        args = ["uv", "run", montage, path, "--root", "/", "--report", os.path.join(out, name + "-check.md"), "--check"]
        r = subprocess.run(args, cwd=REPO, capture_output=True, text=True)
        if r.returncode == 0:
            break
        bad = set(FAIL_RE.findall(r.stdout + r.stderr)) - broken
        if a.final or not bad:
            sys.exit(r.stdout[-4000:] + r.stderr[-4000:])
        print("в черновике заливкой (проверка монтажа):", ", ".join(sorted(bad)))
        broken |= bad
    else:
        sys.exit("монтаж не сошёлся за 8 проходов")
    print("нет в монтаже:", ", ".join(missing + sorted(broken)) if missing or broken else "—")
    if a.check:
        return 0
    args = ["uv", "run", montage, path, "--root", "/", "--out", os.path.join(out, name + ".mp4")]
    if not a.final:
        args.append("--draft")
    if music:
        args += ["--music", music]
    return subprocess.run(args, cwd=REPO).returncode


if __name__ == "__main__":
    sys.exit(main())
