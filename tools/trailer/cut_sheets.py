#!/usr/bin/env python3
"""
Листы проверки ролика глазами зрителя: по списку склеек и переходов скорости (<ролик>-cuts.json, его пишет edit.py)
для каждой точки — лист 10 кадров/с на ±1 с вокруг неё (5×4, момент точки — 11-й кадр, левый в третьем ряду).
Проверка по листам перед показом: камера на цели с первого кадра, нет рывков и скачков времени, текст внутри
кинокаше 2.39, за помехами «SIGNAL LOST» нет мира.

  python3 tools/trailer/cut_sheets.py dist/airstrike-trailer.mp4 [--out папка] [--width 480]
"""
import argparse
import json
import os
import shutil
import subprocess


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("video")
    ap.add_argument("--cuts", help="список точек (по умолчанию <ролик>-cuts.json)")
    ap.add_argument("--out", help="папка листов (по умолчанию <ролик>-sheets)")
    ap.add_argument("--width", type=int, default=480, help="ширина кадра на листе")
    args = ap.parse_args()
    base = os.path.splitext(args.video)[0]
    cuts = json.load(open(args.cuts or base + "-cuts.json", encoding="utf-8"))
    out = args.out or base + "-sheets"
    os.makedirs(out, exist_ok=True)
    ff = shutil.which("ffmpeg") or "ffmpeg"
    index = []
    for i, (t, what) in enumerate(cuts):
        start = max(0.0, t - 1.0)
        name = f"{i:02d}_{t:07.2f}.png"
        subprocess.run([ff, "-loglevel", "error", "-y", "-ss", f"{start:.3f}", "-t", "2.0", "-i", args.video,
                        "-vf", f"fps=10,scale={args.width}:-1,tile=5x4",
                        "-frames:v", "1", os.path.join(out, name)], check=True)
        # кадр n листа (слева направо, сверху вниз, с нуля) — момент start + n·0,1 с
        index.append(f"{name}\tс {start:.2f} с\tточка {t:.2f} с\t{what}")
    with open(os.path.join(out, "index.tsv"), "w", encoding="utf-8") as f:
        f.write("\n".join(index) + "\n")
    print(f"{len(cuts)} листов → {out} (список — index.tsv)")


if __name__ == "__main__":
    main()
