#!/usr/bin/env python3
"""Данные дублей монтажа для проверки монтажа по данным (tools/trailer/verdict.py): только текст, без кадров и карт.

    python3 tools/trailer/exam_pack.py <таблица склеек …-cuts.md> <корень дерева съёмки> <папка> [--max-mb 15]

По таблице склеек edit.py берёт у каждого дубля то, по чему монтаж проверяется:
- дубль из вывода (папка кандидата `…/render…/<план>/<камера>`): `qa.jsonl` (проверка каждого кадра), `timeline.jsonl`
  (кадры с камерой и звуки, что сыграл клиент вывода), журнал вывода `<план>.events.jsonl`, строки плана из
  `report.jsonl` вывода и `best.txt`; журнал записи `replays/<повтор>.events.jsonl` папки записи;
- живой дубль (папка записи): строки плана из `timeline.jsonl`, `hud.jsonl`, `fight.jsonl`, `grid.jsonl`, покадровая
  проверка `qa/<план>.jsonl`, журнал записи плана, если он есть.
Пути в архиве — как в дереве съёмки (от корня), `manifest.json` — дубль → его файлы; таблица склеек — сама в архиве.
Архив режется на части не больше --max-mb (`<имя>.tar.gz.000`, `.001`…; склеить — `cat … > архив.tar.gz`).
В журналах — места снарядов, людей и камер по тикам, кадров и файлов карты нет.
"""
import argparse
import io
import json
import os
import re
import sys
import tarfile
import time


def takes(cuts):
    """(план, папка дубля) по таблице склеек, по одному разу, в порядке монтажа."""
    seen = []
    for line in open(cuts, encoding="utf-8"):
        c = [x.strip() for x in line.split("|")]
        if len(c) < 6 or not re.match(r"p\d", c[3]):
            continue
        shot, src = c[3].split()[0], c[5]
        if src and (shot, src) not in seen:
            seen.append((shot, src))
    return seen


def shot_lines(path, shot):
    """Строки jsonl этого плана (поле shot), как есть."""
    out = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                if json.loads(line).get("shot") == shot:
                    out.append(line if line.endswith("\n") else line + "\n")
            except (json.JSONDecodeError, AttributeError):
                continue
    return "".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cuts")
    ap.add_argument("root", help="корень дерева съёмки (относительные папки таблицы склеек — от него)")
    ap.add_argument("out")
    ap.add_argument("--name", default="trailer-exam")
    ap.add_argument("--max-mb", type=float, default=15.0)
    a = ap.parse_args()
    root = os.path.abspath(a.root)
    files = {}     # путь в архиве → (файл на диске | None, текст | None)
    manifest = []

    def rel(p):
        p = os.path.abspath(p)
        return os.path.relpath(p, root) if p.startswith(root + os.sep) else p.lstrip(os.sep)

    def add(path, text=None):
        if text is None and not os.path.isfile(path):
            return None
        if text is not None and not text:
            return None
        key = rel(path)
        files[key] = (None if text is not None else path, text)
        return key

    for shot, src in takes(a.cuts):
        d = src if os.path.isabs(src) else os.path.join(root, src)
        row = {"shot": shot, "src": src, "files": [], "missing": []}
        if not os.path.isdir(d):
            row["missing"].append(d)
            manifest.append(row)
            continue
        if "/render" in d:
            rec = d.split("/render")[0]
            render = os.path.dirname(os.path.dirname(d))
            replay = shot
            for name in ("qa.jsonl", "timeline.jsonl", f"{shot}.events.jsonl"):
                k = add(os.path.join(d, name))
                (row["files"] if k else row["missing"]).append(k or os.path.join(d, name))
            rp = os.path.join(render, "report.jsonl")
            if os.path.isfile(rp):
                lines = shot_lines(rp, shot)
                for l in lines.splitlines():
                    replay = json.loads(l).get("replay", replay)
                k = add(rp + f"#{shot}", lines)
                if k:
                    row["files"].append(k)
            k = add(os.path.join(render, "best.txt"))
            if k:
                row["files"].append(k)
            k = add(os.path.join(render, shot, "candidates.txt"))
            if k:
                row["files"].append(k)
        else:
            rec = d
            tl = os.path.join(d, "timeline.jsonl")
            if os.path.isfile(tl):
                k = add(tl + f"#{shot}", shot_lines(tl, shot))
                (row["files"] if k else row["missing"]).append(k or f"{tl}#{shot}")
            for name in ("hud.jsonl", "fight.jsonl", "grid.jsonl"):
                p = os.path.join(d, name)
                if os.path.isfile(p):
                    k = add(p + f"#{shot}", shot_lines(p, shot))
                    if k:
                        row["files"].append(k)
            k = add(os.path.join(d, "qa", f"{shot}.jsonl"))
            if k:
                row["files"].append(k)
            replay = shot
        k = add(os.path.join(rec, "replays", f"{replay}.events.jsonl"))
        (row["files"] if k else row["missing"]).append(k or os.path.join(rec, "replays", f"{replay}.events.jsonl"))
        manifest.append(row)

    os.makedirs(a.out, exist_ok=True)
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz", compresslevel=9) as tar:
        def put(name, data):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mtime = int(time.time())
            tar.addfile(info, io.BytesIO(data))
        put("manifest.json", json.dumps({"root": root, "takes": manifest}, ensure_ascii=False, indent=1).encode())
        put("cuts.md", open(a.cuts, "rb").read())
        for key, (path, text) in sorted(files.items()):
            if path is not None:
                tar.add(path, arcname=key)
            else:
                put(key.replace("#", "."), text.encode())
    data = buf.getvalue()
    part = int(a.max_mb * 1024 * 1024)
    names = []
    for i in range(0, len(data), part):
        name = os.path.join(a.out, f"{a.name}.tar.gz.{i // part:03d}")
        with open(name, "wb") as f:
            f.write(data[i:i + part])
        names.append(name)
    missing = sum(len(r["missing"]) for r in manifest)
    print(f"дублей {len(manifest)}, файлов {len(files)}, архив {len(data) / 1e6:.1f} МБ в {len(names)} частях; нет файлов: {missing}")
    for r in manifest:
        for m in r["missing"]:
            print(f"  нет {r['shot']}: {m}")
    for n in names:
        print(n)


if __name__ == "__main__":
    sys.exit(main())
