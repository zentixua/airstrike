#!/usr/bin/env python3
"""Запись окна X11 программы в видео в реальном времени — клиент во вложенном KWin (tools/prod_client.py --video).

  tools/x11_record.py --out <файл>.mkv [--title Minecraft] [--fps 60] [--crf 16] [--audio <файл>] -- <команда …>

Запускается внутри сессии вложенного KWin (там есть DISPLAY его Xwayland): запускает команду, ждёт её окно
(по имени, `xwininfo -root -tree`) и пишет его ffmpeg (`x11grab -window_id`) в <файл>.000.mkv. Окно KWin в Xwayland
перенаправлено в свой буфер, поэтому снимается именно окно, а не корень (корень без окон — чёрный). Сменился размер
окна (загрузка, полный экран) — запись этого куска кончается, следующий кусок — <файл>.001.mkv и т. д.

Время: ffmpeg ставит кадрам часы стены (`-use_wallclock_as_timestamps`), начало куска (секунды эпохи) — строка
«start:» в его журнале <кусок>.log; оно же — в <файл>.jsonl. С --audio туда же пишется, когда появился этот файл
(драйвер OpenAL Soft «wave» создаёт его при открытии звука и дальше пишет в реальном времени), — по этим двум
отметкам звук ложится на видео. Код выхода — код команды. Нужны ffmpeg и xwininfo.
"""
import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time

# 0x1a00007 "Minecraft* 1.21.1": ("Minecraft* 1.21.1" "Minecraft* 1.21.1")  1920x1080+0+0  +0+0
WINDOW = re.compile(r'^\s*(0x[0-9a-f]+) "(.*)":.*?\s(\d+)x(\d+)[+-]\d+[+-]\d+\s+[+-]\d+[+-]\d+\s*$')
START = re.compile(r"start: (\d+\.\d+)")
# окно, которое ffmpeg не может снять, дало бы по куску в секунду
MAX_SEGMENTS = 50


def find_window(title):
    """Самое большое окно, в имени которого есть title: (id, ширина, высота) или None."""
    try:
        out = subprocess.run(["xwininfo", "-root", "-tree"], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    best = None
    for line in out.splitlines():
        m = WINDOW.match(line)
        if m and title in m.group(2) and viewable(m.group(1)):
            w, h = int(m.group(3)), int(m.group(4))
            if w > 1 and h > 1 and (best is None or w * h > best[1] * best[2]):
                best = (m.group(1), w, h)
    return best


def viewable(window):
    """Окно показано: снимок с неотображённого окна ffmpeg не берёт (BadMatch)."""
    try:
        out = subprocess.run(["xwininfo", "-id", window], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.TimeoutExpired):
        return False
    return "Map State: IsViewable" in out


class Stop(Exception):
    pass


def _stop(signum, frame):
    raise Stop()


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("--out", required=True, help="видео: <out без .mkv>.NNN.mkv, отметки — <out без .mkv>.jsonl")
    ap.add_argument("--title", default="Minecraft", help="часть имени окна")
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--crf", type=int, default=16, help="качество libx264 (меньше — лучше)")
    ap.add_argument("--audio", help="файл звука, момент появления которого отметить")
    ap.add_argument("command", nargs=argparse.REMAINDER)
    a = ap.parse_args()
    cmd = a.command[1:] if a.command[:1] == ["--"] else a.command
    if not cmd:
        ap.error("нет команды")
    # только внутри вложенного KWin (nested_kwin.sh, сокет wayland-airstrike-…): снять экран рабочего стола нельзя
    if not os.environ.get("DISPLAY") or not os.environ.get("WAYLAND_DISPLAY", "").startswith("wayland-airstrike-"):
        ap.error("нужна сессия вложенного KWin (tools/nested_kwin.sh): DISPLAY и WAYLAND_DISPLAY=wayland-airstrike-…")
    base = a.out[:-4] if a.out.endswith(".mkv") else a.out
    marks = open(base + ".jsonl", "a", buffering=1)

    def mark(**kv):
        marks.write(json.dumps(kv, ensure_ascii=False) + "\n")

    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, _stop)
    child = subprocess.Popen(cmd)
    mark(event="command", pid=child.pid, wall=time.time())
    ffmpeg, seg, size, log, audio_seen = None, 0, None, None, a.audio is None

    def finish():
        """Остановить кусок: «q» в ffmpeg (он дописывает файл), отметить его начало по журналу."""
        nonlocal ffmpeg
        if ffmpeg is None:
            return
        try:
            ffmpeg.stdin.write(b"q")
            ffmpeg.stdin.close()
        except OSError:
            pass
        try:
            ffmpeg.wait(timeout=30)
        except subprocess.TimeoutExpired:
            ffmpeg.send_signal(signal.SIGINT)
            ffmpeg.wait()
        with open(log, encoding="utf-8", errors="replace") as f:
            m = START.search(f.read())
        mark(event="segment_end", file=f"{base}.{seg:03d}.mkv", start=float(m.group(1)) if m else None,
             code=ffmpeg.returncode, wall=time.time())
        ffmpeg = None

    next_check = 0.0
    try:
        while child.poll() is None:
            if not audio_seen and os.path.exists(a.audio):
                audio_seen = True
                mark(event="audio", file=a.audio, wall=time.time())
            if ffmpeg is not None and ffmpeg.poll() is not None:
                finish()
                seg += 1
            now = time.monotonic()
            if now >= next_check and seg < MAX_SEGMENTS:
                next_check = now + 1
                win = find_window(a.title)
                if ffmpeg is not None and win is not None and win[1:] != size:
                    finish()
                    seg += 1
                elif ffmpeg is None and win is not None:
                    size, log = win[1:], f"{base}.{seg:03d}.log"
                    with open(log, "w") as lf:
                        ffmpeg = subprocess.Popen(
                            ["ffmpeg", "-hide_banner", "-nostats", "-loglevel", "info", "-y",
                             "-use_wallclock_as_timestamps", "1", "-f", "x11grab", "-framerate", str(a.fps),
                             "-draw_mouse", "0", "-window_id", str(int(win[0], 16)), "-i", os.environ["DISPLAY"],
                             "-c:v", "libx264", "-preset", "ultrafast", "-crf", str(a.crf), "-pix_fmt", "yuv420p",
                             f"{base}.{seg:03d}.mkv"],
                            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=lf)
                    mark(event="segment", file=f"{base}.{seg:03d}.mkv", window=win[0], width=size[0], height=size[1],
                         wall=time.time())
            time.sleep(0.2 if audio_seen else 0.01)
    except Stop:
        child.send_signal(signal.SIGTERM)
    finally:
        for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            signal.signal(sig, signal.SIG_IGN)
        finish()
        code = child.wait()
        mark(event="exit", code=code, wall=time.time())
        marks.close()
    sys.exit(code if code >= 0 else 128 - code)


if __name__ == "__main__":
    main()
