#!/usr/bin/env python3
"""Монтаж дубля nuke-preview (задание nuke-preview.md): три отрезка по строкам лога клиента → одно mp4.

  python3 tools/laptop-jobs/nuke-preview-cut.py <каталог копии> <выход.mp4>

В каталоге копии: take.jsonl и take.NNN.mkv (tools/x11_record.py), audio.wav, logs/latest.log. Время отрезков —
по строкам SCENARIO в логе (пояс часов лога — по его первой строке и запуску java), видео и звук ложатся на них по отметкам take.jsonl.
Отрезки: пуск МБР (команда −1 … +11 с), вспышка и волна на дальней камере (подрыв −4 с … перенос камеры),
гриб со второй камеры (перенос +2,5 с … +47,5 с, не дальше конца сценария). Рядом — <выход>.txt: что взято откуда.
"""
import datetime
import json
import os
import re
import subprocess
import sys

LINE = re.compile(r"^\[(\d{2}\w{3}\d{4} \d{2}:\d{2}:\d{2}\.\d{3})\]")


def naive(line):
    """Время строки лога как есть (часы JVM, пояс неизвестен) — секунды, как если бы это было UTC; None — не строка лога."""
    m = LINE.match(line)
    if not m:
        return None
    t = datetime.datetime.strptime(m.group(1), "%d%b%Y %H:%M:%S.%f")
    return t.replace(tzinfo=datetime.timezone.utc).timestamp()


def log_zone(path, spawned):
    """Пояс часов лога, секунды: первая строка лога — через секунды после запуска java (отметка «command»
    x11_record.py, часы стены), пояс кратен 15 минутам. Пояс системы не годится: у JVM на ноутбуке он другой."""
    with open(path, encoding="utf-8", errors="replace") as f:
        first = next(t for t in map(naive, f) if t is not None)
    return round((first - spawned) / 900) * 900


def log_time(path, needle, zone):
    """Время первой строки лога с needle, секунды эпохи."""
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if needle in line and (t := naive(line)) is not None:
                return t - zone
    sys.exit(f"в логе нет строки «{needle}»")


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True, check=True).stdout.strip()
    return float(out)


def main():
    d, out = sys.argv[1], sys.argv[2]
    marks = [json.loads(ln) for ln in open(os.path.join(d, "take.jsonl"))]
    segs = [(m["file"], m["start"], m["start"] + duration(m["file"])) for m in marks
            if m["event"] == "segment_end" and m.get("start") and os.path.getsize(m["file"]) > 0]
    audio = next(m["wall"] for m in marks if m["event"] == "audio")
    log = os.path.join(d, "logs", "latest.log")
    ref = segs[0][1]
    zone = log_zone(log, next(m["wall"] for m in marks if m["event"] == "command"))
    launch, det, move, done = (log_time(log, needle, zone) for needle in (
        "SCENARIO /airstrike nuke at", "SCENARIO commands: подрыв пришёл", "SCENARIO /tp @s -1124.5", "SCENARIO done"))
    clips = [("пуск", launch - 1, launch + 11), ("вспышка и волна", det - 4, move - 0.2),
             ("гриб", move + 2.5, min(move + 47.5, done - 0.5))]
    inputs, chains, notes = [], [], []
    for i, (name, t0, t1) in enumerate(clips):
        seg = next((s for s in segs if s[1] <= t0 and t1 <= s[2]), None)
        if seg is None:
            sys.exit(f"отрезок «{name}» ({t0:.2f}…{t1:.2f}) не лежит целиком ни в одном куске: {segs}")
        dur = t1 - t0
        inputs += ["-ss", f"{t0 - seg[1]:.3f}", "-t", f"{dur:.3f}", "-i", seg[0],
                   "-ss", f"{t0 - audio:.3f}", "-t", f"{dur:.3f}", "-i", os.path.join(d, "audio.wav")]
        v = (f"[{2 * i}:v]fps=60,scale=1920:1080:force_original_aspect_ratio=decrease,"
             f"pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1,setpts=PTS-STARTPTS")
        a = f"[{2 * i + 1}:a]aresample=48000,asetpts=PTS-STARTPTS,afade=t=in:d=0.15,afade=t=out:st={dur - 0.15:.3f}:d=0.15"
        if i == 0:
            v += ",fade=t=in:d=0.8"
        if i == len(clips) - 1:
            v += f",fade=t=out:st={dur - 2:.3f}:d=2"
            a += f",afade=t=out:st={dur - 2:.3f}:d=2"
        chains += [f"{v}[v{i}]", f"{a}[a{i}]"]
        notes.append(f"{name}: {os.path.basename(seg[0])} с {t0 - seg[1]:.2f} с, {dur:.2f} с; звук с {t0 - audio:.2f} с")
    n = len(clips)
    graph = ";".join(chains) + ";" + "".join(f"[v{i}][a{i}]" for i in range(n)) + f"concat=n={n}:v=1:a=1[v][a0];[a0]alimiter=limit=0.95[a]"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *inputs, "-filter_complex", graph,
                    "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "slow", "-crf", "19", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out], check=True)
    with open(out[:-4] + ".txt", "w") as f:
        f.write(f"пояс часов лога: UTC{zone / 3600:+.2f} ч\n"
                f"куски: {[(os.path.basename(s[0]), round(s[2] - s[1], 1)) for s in segs]}\n"
                f"звук начался через {audio - ref:.2f} с после начала первого куска\n"
                f"пуск {launch - ref:.2f} с, подрыв {det - ref:.2f} с, перенос камеры {move - ref:.2f} с, конец {done - ref:.2f} с\n"
                + "\n".join(notes) + f"\nитог: {duration(out):.2f} с\n")
    print(open(out[:-4] + ".txt").read())


if __name__ == "__main__":
    main()
