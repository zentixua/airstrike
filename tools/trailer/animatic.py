#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""Аниматик трейлера: черновые кадры планов (`scout.py --previs`) по монтажному листу под музыку — первый взгляд на
сценарий до съёмки. Кадр плана держится свою длину, у плана с несколькими кадрами — переход от кадра к кадру (путь
камеры), лёгкий наезд; внизу — подпись (план, время суток, что в кадре, звук); заставки — чёрный кадр с текстом.
План глазами игрока (`camera: hud` — пульт, бинокль, чат) темнее и с меткой: интерфейс снимает клиент вживую.

    uv run tools/trailer/animatic.py <лист.json> <папка scout> <выход.mp4> [--music F] [--size 1280x720]

Лист: {"bar": 1.92, "letterbox": {"aspect", "until", "open"}, "music": {"file", "parts": [{"at", "track", "end",
"fade"}]}, "shots": [{"plan" | "card", "dur" | "bars", "text", "note", "style"}]}. `style` карточки в монтаже (newisle_cut.py): "end" —
строки конечного титра (по умолчанию), "title" — кегль логотипа (название). `parts` — куски трека на своих местах
аниматика (как `PARTS` в edit.py, `gain` — дБ, `fade` — затухание в конце, с); нет файла музыки — без звука.
`letterbox` — кадр `aspect` (2.39:1) чёрными полосами до первого плана `until`, там полосы уходят за `open` секунд.
Плана нет в `poses.json` или у него нет кадра — серый кадр с его подписью.
"""
import argparse
import glob
import json
import math
import os
import subprocess
import tempfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FPS = 24
TIME = {"dawn": "рассвет", "day": "день", "sunset": "закат", "night": "ночь"}


def font(size):
    for pat in ("/usr/share/fonts/**/DejaVuSans.ttf", "/usr/share/fonts/**/NotoSans-Regular.ttf",
                "/usr/share/fonts/**/LiberationSans-Regular.ttf"):
        for f in glob.glob(pat, recursive=True):
            try:
                return ImageFont.truetype(f, size)
            except OSError:
                continue
    return ImageFont.load_default()


def length(shot, bar):
    return float(shot["dur"]) if "dur" in shot else float(shot["bars"]) * bar


def plan_frames(scout_dir, poses, size):
    """Кадры планов: id → список картинок размера кадра; подписи планов — из poses.json."""
    out, info = {}, {}
    for r in poses:
        info[r["id"]] = r
        ims = []
        for fn in r.get("previs", []):
            p = os.path.join(scout_dir, fn)
            if os.path.exists(p):
                ims.append(Image.open(p).convert("RGB").resize(size, Image.LANCZOS))
        out[r["id"]] = ims
    return out, info


def caption(im, lines, f1, f2):
    d = ImageDraw.Draw(im, "RGBA")
    w, h = im.size
    pad = int(h * 0.02)
    hh = pad * 2 + f1.size + (f2.size + pad // 2) * (len(lines) - 1)
    d.rectangle([0, h - hh, w, h], fill=(0, 0, 0, 150))
    y = h - hh + pad
    for k, line in enumerate(lines):
        f = f1 if k == 0 else f2
        d.text((pad * 2, y), line, font=f, fill=(255, 255, 255) if k == 0 else (210, 210, 210))
        y += f.size + pad // 2
    return im


def card(text, size, big):
    im = Image.new("RGB", size, (0, 0, 0))
    d = ImageDraw.Draw(im)
    lines = text.split("\n")
    hs = [big.size * 1.25] * len(lines)
    y = (size[1] - sum(hs)) / 2
    for line, lh in zip(lines, hs):
        w = d.textlength(line, font=big)
        d.text(((size[0] - w) / 2, y), line, font=big, fill=(235, 235, 235))
        y += lh
    return im


def picture(shot, t, dur, frames, info, size, fonts, box=0.0):
    """Кадр листа в момент t от начала плана; box — доля полос кадра 2.39:1 (под подписью)."""
    big, f1, f2 = fonts
    if "card" in shot:
        return card(shot["card"], size, big)
    pid = shot["plan"]
    ims = frames.get(pid) or []
    r = info.get(pid, {})
    if ims:
        if len(ims) > 1:
            pos = min(t / max(dur, 1e-6), 1.0) * (len(ims) - 1)
            i = min(int(pos), len(ims) - 2)
            im = Image.blend(ims[i], ims[i + 1], pos - i)
        else:
            im = ims[0]
        z = 1 + 0.04 * min(t / max(dur, 1e-6), 1.0)     # лёгкий наезд
        w, h = size
        cw, ch = w / z, h / z
        im = im.crop(((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2)).resize(size, Image.BILINEAR)
    else:
        im = Image.new("RGB", size, (60, 60, 60))
    if r.get("camera") == "hud":
        im = hud(im, big)
    tod = r.get("time", shot.get("time", ""))
    lines = [f"{pid} · {TIME.get(tod, tod)} · {shot.get('text') or r.get('title', '')}"]
    if shot.get("note"):
        lines.append(shot["note"])
    if not ims:
        lines.append("нет чернового кадра")
    return caption(bars(im.copy(), box), lines, f1, f2)


def hud(im, big):
    """План глазами игрока (пульт, бинокль, чат, тревога на экране): интерфейс снимает клиент вживую, черновой кадр —
    только место; кадр темнее, посередине — метка."""
    im = Image.blend(im, Image.new("RGB", im.size, (0, 0, 0)), 0.45)
    d = ImageDraw.Draw(im, "RGBA")
    w, h = im.size
    text = "ИНТЕРФЕЙС МОДА · снимается вживую"
    f = font(int(big.size * 0.5))
    tw = d.textlength(text, font=f)
    pad = f.size * 0.6
    d.rectangle([(w - tw) / 2 - pad, h * 0.42 - pad, (w + tw) / 2 + pad, h * 0.42 + f.size + pad],
                outline=(240, 162, 59, 255), width=max(2, h // 240), fill=(0, 0, 0, 120))
    d.text(((w - tw) / 2, h * 0.42), text, font=f, fill=(240, 162, 59))
    return im


def bars(im, frac):
    """Чёрные полосы сверху и снизу: frac — доля полной высоты полос (1 — кадр aspect, 0 — без полос)."""
    if frac <= 0:
        return im
    d = ImageDraw.Draw(im)
    w, h = im.size
    b = int(round(frac * h / 2))
    d.rectangle([0, 0, w, b], fill=(0, 0, 0))
    d.rectangle([0, h - b, w, h], fill=(0, 0, 0))
    return im


def letterbox(sheet, shots, start):
    """Доля полос в момент t: (t) → 0…1 по листу; без `letterbox` — всегда 0."""
    lb = sheet.get("letterbox")
    if not lb:
        return lambda t, size: 0.0
    until = next((start[k] for k, s in enumerate(shots) if s.get("plan") == lb.get("until")), float("inf"))
    opening = float(lb.get("open", 1.0))

    def frac(t, size):
        full = 1 - size[0] / float(lb["aspect"]) / size[1]
        if t < until:
            return full
        x = min(1.0, (t - until) / max(opening, 1e-6))
        return full * (1 - (3 * x * x - 2 * x ** 3))
    return frac


def music(sheet, total, path, out):
    """Звук аниматика: куски трека на своих местах (ffmpeg), остальное — тишина; без файла — тишина целиком."""
    parts = sheet.get("music", {}).get("parts", [])
    if not path or not os.path.exists(path) or not parts:
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
                        "-t", f"{total:.3f}", out], check=True)
        return
    chain, names = [], []
    for k, p in enumerate(parts):
        n = float(p["end"]) - float(p["track"])
        f = float(p.get("fade", 0))
        fade = f",afade=t=out:st={max(0.0, n - f):.3f}:d={f:.3f}" if f > 0 else ""
        ms = int(round(float(p["at"]) * 1000))
        gain = f",volume={float(p['gain']):.1f}dB" if p.get("gain") else ""
        chain.append(f"[0:a]atrim=start={p['track']}:end={p['end']},asetpts=PTS-STARTPTS,afade=t=in:d=0.01{fade}{gain},"
                     f"adelay={ms}|{ms}[m{k}]")
        names.append(f"[m{k}]")
    chain.append("".join(names) + f"amix=inputs={len(names)}:normalize=0,apad=whole_dur={total:.3f}[a]")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", path, "-filter_complex", ";".join(chain), "-map", "[a]",
                    "-ar", "48000", "-ac", "2", "-t", f"{total:.3f}", out], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sheet")
    ap.add_argument("scout")
    ap.add_argument("out")
    ap.add_argument("--music")
    ap.add_argument("--size", default="1280x720")
    a = ap.parse_args()
    with open(a.sheet, encoding="utf-8") as f:
        sheet = json.load(f)
    size = tuple(int(v) for v in a.size.split("x"))
    poses_path = os.path.join(a.scout, "poses.json")
    poses = json.load(open(poses_path, encoding="utf-8")) if os.path.exists(poses_path) else []
    frames, info = plan_frames(a.scout, poses, size)
    bar = float(sheet.get("bar", 1.92))
    shots = sheet["shots"]
    lens = [length(s, bar) for s in shots]
    total = sum(lens)
    fonts = (font(int(size[1] * 0.07)), font(int(size[1] * 0.032)), font(int(size[1] * 0.026)))
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "music.wav")
        music(sheet, total, a.music or sheet.get("music", {}).get("file"), wav)
        enc = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s",
                                f"{size[0]}x{size[1]}", "-r", str(FPS), "-i", "-", "-i", wav,
                                "-map", "0:v", "-map", "1:a",
                                "-c:v", "libx264", "-crf", "22", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                                "-c:a", "aac", "-b:a", "192k", "-t", f"{total:.3f}", a.out], stdin=subprocess.PIPE)
        n = int(math.ceil(total * FPS))
        start = np.concatenate(([0.0], np.cumsum(lens)))
        cache = {}
        lb = letterbox(sheet, shots, start)
        for i in range(n):
            t = i / FPS
            k = min(int(np.searchsorted(start, t, side="right")) - 1, len(shots) - 1)
            s = shots[k]
            key = (k, None) if "card" in s else None
            im = cache.get(key) if key else None
            if im is None:
                im = picture(s, t - start[k], lens[k], frames, info, size, fonts, lb(t, size))
                if key:
                    cache.clear()
                    cache[key] = im
            enc.stdin.write(im.tobytes())
        enc.stdin.close()
        if enc.wait():
            raise SystemExit("ffmpeg: ошибка вывода")
    plans, cards = sum(1 for s in shots if "plan" in s), sum(1 for s in shots if "card" in s)
    print(f"{a.out}: {total:.1f} с, планов {plans}, заставок {cards}")


if __name__ == "__main__":
    main()
