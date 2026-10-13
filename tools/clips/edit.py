#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=1.26"]
# ///
"""Монтаж превью из снятых планов (replayscan film по планам plan.py): ролик — планы кандидата подряд, простыми
склейками, звук игры −14 LUFS, пики не выше −1 dBTP; рядом — версия с тихой музыкой, подписи EN/RU и отчёт.

  uv run tools/clips/edit.py <film> [--music трек.mp3 --credit "строка автора"] [--out <film>/previews]

<film> — каталог plan.py (runs.json, <повтор>/plan.json, <повтор>/film/best.json). Кроме проверки кадров съёмки
(qa.jsonl, verdict.json) — ещё одна, по самим кадрам видео: вспышка каждого события видна там, где игра его рисует
(яркость в рамке события сразу после него выше, чем до), — она ловит то, чего лучи по блокам не видят (рельеф
Distant Horizons, дым, шейдеры). Нужен ffmpeg.
"""
from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np

ORDER = ["rockets", "drones", "chaos", "far", "nuke"]
# Яркость (0–255) в рамке события сразу после него — на столько выше, чем до: вспышка видна
FLASH = 18
# Кадр проверки вспышки: видео уменьшается до этой ширины (оттенки серого)
PROBE_W = 270

WEAPON = {
    "grad": ("Grad rockets", "«Град»"), "missile": ("cruise missiles", "крылатые ракеты"), "shahed": ("Shahed drones", "шахеды"),
    "lancet": ("Lancet drones", "«Ланцеты»"), "b2": ("a B-2 bomber", "B-2"), "bomb": ("a bunker buster", "бетонобойная бомба"),
    "icbm": ("an ICBM", "МБР"),
}
TAIL_EN = "Incoming mod for Minecraft Java 1.21.1, free on Modrinth and CurseForge."
TAIL_RU = "Мод Incoming для Minecraft Java 1.21.1, бесплатно на Modrinth и CurseForge."
TAGS = "#minecraft #minecraftmods #moddedminecraft #майнкрафт #моды"


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, capture_output=True, **kw)


def duration(f: Path) -> float:
    out = run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(f)], text=True).stdout
    return float(out.strip())


def gray_frames(video: Path, fps: float, aspect: float) -> np.ndarray:
    """Кадры видео в оттенках серого шириной PROBE_W (массив кадр × строка × столбец)."""
    h = round(PROBE_W / aspect / 2) * 2
    raw = run(["ffmpeg", "-v", "error", "-i", str(video), "-vf", f"fps={fps},scale={PROBE_W}:{h},format=gray", "-f", "rawvideo", "-"]).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, h, PROBE_W)


def project(cam: list[float], fov: float, aspect: float, p: list[float]) -> tuple[float, float, float] | None:
    """Место точки в кадре (−1…1 вправо и вверх) и глубина по оси взгляда; за камерой — None. Поворот как у игрока."""
    x, y, z, yaw, pitch = cam
    yr, pr = math.radians(yaw), math.radians(pitch)
    fwd = (-math.sin(yr) * math.cos(pr), -math.sin(pr), math.cos(yr) * math.cos(pr))
    right = (-math.cos(yr), 0.0, -math.sin(yr))
    up = (right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0])
    d = (p[0] - x, p[1] - y, p[2] - z)
    depth = sum(a * b for a, b in zip(d, fwd))
    if depth <= 0.1:
        return None
    ty = math.tan(math.radians(fov / 2))
    return sum(a * b for a, b in zip(d, right)) / depth / (ty * aspect), sum(a * b for a, b in zip(d, up)) / depth / ty, depth


def flash_check(shot: dict, cand: Path, fps: float, aspect: float) -> list[dict]:
    """Вспышка каждого события по кадрам видео: яркость в его рамке за 12 тиков после него против 20 тиков до."""
    qa = [json.loads(line) for line in (cand / "qa.jsonl").read_text().splitlines() if line.strip()]
    keys = shot["subject"].get("keys", [])
    if not qa or not keys:
        return []
    frames = gray_frames(cand / "video.mkv", fps, aspect)
    h, w = frames.shape[1:]
    out = []
    for k in keys:
        before, after = [], []
        for f in qa:
            # кадр видео k — тик повтора a + k·20/fps (вывод Flashback)
            i = round((f["t"] - shot["a"]) * fps / 20)
            if not (k["t"] - 20 <= f["t"] <= k["t"] + 12) or not 0 <= i < len(frames):
                continue
            p = project(f["cam"], f["fov"], aspect, k["pos"])
            if p is None or abs(p[0]) > 1 or abs(p[1]) > 1:
                continue
            half = max(2, int(k["size"] / (2 * p[2] * math.tan(math.radians(f["fov"] / 2)) * aspect) * w / 2))
            cx, cy = int((p[0] + 1) / 2 * w), int((1 - p[1]) / 2 * h)
            box = frames[i, max(0, cy - half):cy + half + 1, max(0, cx - half):cx + half + 1]
            if box.size:
                (after if f["t"] >= k["t"] else before).append(float(box.mean()))
        if not before or not after:
            out.append({"t": k["t"], "seen": False, "why": "not in frame"})
            continue
        rise = max(after) - float(np.median(before))
        out.append({"t": k["t"], "seen": rise >= FLASH, "rise": round(rise, 1)})
    return out


def loudnorm(inputs: list[str], graph: str, label: str, out: Path, extra: list[str]):
    """Звук к −14 LUFS и −1 dBTP в два прохода (замер, потом линейно)."""
    probe = run(["ffmpeg", "-hide_banner", *inputs, "-filter_complex", f"{graph};[{label}]loudnorm=I=-14:TP=-1:LRA=11:print_format=json[x]",
                 "-map", "[v]", "-map", "[x]", "-f", "null", "-"], text=True).stderr
    m = json.loads(probe[probe.rindex("{"):probe.rindex("}") + 1])
    norm = (f"loudnorm=I=-14:TP=-1:LRA=11:measured_I={m['input_i']}:measured_TP={m['input_tp']}:measured_LRA={m['input_lra']}"
            f":measured_thresh={m['input_thresh']}:offset={m['target_offset']}:linear=true")
    run(["ffmpeg", "-y", "-hide_banner", *inputs, "-filter_complex", f"{graph};[{label}]{norm},aresample=48000[a]", "-map", "[v]", "-map", "[a]",
         *extra, "-c:v", "libx264", "-crf", "18", "-preset", "slow", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
         "-movflags", "+faststart", str(out)])


def assemble(videos: list[Path], out: Path, music: Path | None):
    inputs = [x for v in videos for x in ("-i", str(v))]
    n = len(videos)
    graph = "".join(f"[{i}:v][{i}:a]" for i in range(n)) + f"concat=n={n}:v=1:a=1[v][g]"
    if music is None:
        loudnorm(inputs, graph, "g", out, [])
        return
    total = sum(duration(v) for v in videos)
    # музыка тихо под звуком игры: на 14 дБ ниже, мягкое начало и конец
    graph += (f";[{n}:a]atrim=0:{total:.3f},volume=-14dB,afade=t=in:d=1,afade=t=out:st={max(0.0, total - 2):.3f}:d=2[m]"
              ";[g][m]amix=inputs=2:normalize=0:duration=first[mix]")
    loudnorm(inputs + ["-i", str(music)], graph, "mix", out, [])


def caption(preview: str, cand: dict) -> tuple[str, str]:
    """Подпись EN/RU по данным кандидата (оружие, расстояния); без рекламных слов."""
    w = cand["weapon"].split("+")
    en = " and ".join(WEAPON.get(x, (x, x))[0] for x in w)
    ru = " и ".join(WEAPON.get(x, (x, x))[1] for x in w)
    n = cand["numbers"]
    if preview == "rockets":
        return (f"{en.capitalize()}: launch, flight and impacts on the ground.", f"{ru.capitalize()}: пуск, полёт и удары по земле.")
    if preview == "drones":
        return (f"{en.capitalize()} fly over the players and hit the streets nearby.",
                f"{ru.capitalize()} пролетают над игроками и бьют по соседним улицам.")
    if preview == "chaos":
        k = n.get("ground") or n.get("ground_in_frame")
        return (f"{k} strikes in ten seconds, one camera.", f"{k} ударов за десять секунд, одна камера.")
    if preview == "far":
        d = n.get("dist", 0)
        return (f"A strike {d} blocks away through binoculars: the flash comes first, the sound {n.get('boom_after_s', 0)} s later.",
                f"Удар в {d} блоках в бинокль: сначала вспышка, звук — через {n.get('boom_after_s', 0)} с.")
    return ("A nuclear strike: launch, flash, shock wave and mushroom cloud.", "Ядерный удар: пуск, вспышка, ударная волна и гриб.")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("film")
    p.add_argument("--out", default=None)
    p.add_argument("--music", default=None, help="трек для версии с музыкой (CC BY и т. п.)")
    p.add_argument("--credit", default="", help="строка автора трека для подписи")
    a = p.parse_args()
    film = Path(a.film)
    out = Path(a.out) if a.out else film / "previews"
    out.mkdir(parents=True, exist_ok=True)
    cands = {c["id"]: c for v in json.loads((film.parent / "candidates.json").read_text()).values() for c in v} \
        if (film.parent / "candidates.json").exists() else {}
    report = ["# Превью: итог съёмки и монтажа", ""]
    for r in json.loads((film / "runs.json").read_text()):
        plan = json.loads(Path(r["plan"]).read_text())
        shots = {s["name"]: s for s in plan["shots"]}
        best = json.loads((Path(plan["out"]) / "best.json").read_text()) if (Path(plan["out"]) / "best.json").exists() else {}
        aspect = plan["width"] / plan["height"]
        for cid in sorted(plan["previews"], key=lambda c: ORDER.index(c.split("-")[0]) if c.split("-")[0] in ORDER else 9):
            preview = cid.split("-")[0]
            videos, lines, ok = [], [], True
            for name in plan["previews"][cid]:
                b = best.get(name)
                if not b or "dir" not in b:
                    lines.append(f"- {name}: не снят — {b.get('why') if b else 'нет в best.json'}")
                    ok = False
                    continue
                cand = Path(b["dir"])
                verdict = json.loads((cand / "verdict.json").read_text()) if (cand / "verdict.json").exists() else {}
                flashes = flash_check(shots[name], cand, plan["fps"], aspect)
                dark = [f for f in flashes if not f["seen"]]
                ok &= bool(b.get("pass")) and not dark
                videos.append(cand / "video.mkv")
                lines.append(f"- {name}: камера {b.get('candidate')}, проверка кадров {'да' if b.get('pass') else 'НЕТ: ' + str(b.get('why'))}; "
                             f"кадров {verdict.get('frames')}, объект {verdict.get('approach', 0):.0%}, событий {verdict.get('keys_ok')}/{verdict.get('keys')}, "
                             f"громких в кадре {verdict.get('loud_on')}, за кадром {verdict.get('loud_off')}; "
                             f"вспышка видна {len(flashes) - len(dark)}/{len(flashes)}")
            if not videos:
                report += [f"## {cid}", "", *lines, ""]
                continue
            clean, tuned = out / f"{cid}.mp4", out / f"{cid}-music.mp4"
            assemble(videos, clean, None)
            if a.music:
                assemble(videos, tuned, Path(a.music))
            length = duration(clean)
            en, ru = caption(preview, cands.get(cid, {"weapon": "", "numbers": {}}))
            text = f"{en} {TAIL_EN}\n{ru} {TAIL_RU}\n{TAGS}\n"
            if a.music and a.credit:
                text += f"Music: {a.credit}\n"
            (out / f"{cid}.txt").write_text(text)
            report += [f"## {cid} — {'годится' if ok else 'ЕСТЬ ЗАМЕЧАНИЯ'}", "", f"Файл {clean.name}, {length:.1f} с"
                       + ("" if 20 <= length <= 35 else " (вне 20–35 с)") + f"; подпись — {cid}.txt.", *lines, ""]
    (out / "report.md").write_text("\n".join(report))
    print(out / "report.md")


if __name__ == "__main__":
    sys.exit(main())
