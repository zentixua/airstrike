#!/usr/bin/env python3
"""Запись окна X11 программы в видео в реальном времени — клиент во вложенном KWin (tools/prod_client.py --video).

  tools/x11_record.py --out <файл>.mkv [--title Minecraft] [--fps 60] [--crf 16] [--audio <файл>] -- <команда …>

Запускается внутри сессии вложенного KWin (там есть DISPLAY его Xwayland): запускает команду, ждёт её окно
(по имени, обход дерева окон через libX11) и пишет его ffmpeg (`x11grab -window_id`) в <файл>.000.mkv. Окно KWin в Xwayland
перенаправлено в свой буфер, поэтому снимается именно окно, а не корень (корень без окон — чёрный). Сменился размер
окна (загрузка, полный экран) — запись этого куска кончается, следующий кусок — <файл>.001.mkv и т. д.

Время: ffmpeg ставит кадрам часы стены (`-use_wallclock_as_timestamps`), начало куска (секунды эпохи) — строка
«start:» в его журнале <кусок>.log; оно же — в <файл>.jsonl. С --audio туда же пишется, когда появился этот файл
(драйвер OpenAL Soft «wave» создаёт его при открытии звука и дальше пишет в реальном времени), — по этим двум
отметкам звук ложится на видео. Код выхода — код команды. Нужен ffmpeg с x11grab.
"""
import argparse
import ctypes
import ctypes.util
import json
import os
import re
import signal
import subprocess
import sys
import time

# окно, которое ffmpeg не может снять, дало бы по куску в секунду
MAX_SEGMENTS = 50
START = re.compile(r"start: (\d+\.\d+)")
IS_VIEWABLE = 2


class XWindowAttributes(ctypes.Structure):
    _fields_ = [("x", ctypes.c_int), ("y", ctypes.c_int), ("width", ctypes.c_int), ("height", ctypes.c_int),
                ("border_width", ctypes.c_int), ("depth", ctypes.c_int), ("visual", ctypes.c_void_p),
                ("root", ctypes.c_ulong), ("class_", ctypes.c_int), ("bit_gravity", ctypes.c_int),
                ("win_gravity", ctypes.c_int), ("backing_store", ctypes.c_int), ("backing_planes", ctypes.c_ulong),
                ("backing_pixel", ctypes.c_ulong), ("save_under", ctypes.c_int), ("colormap", ctypes.c_ulong),
                ("map_installed", ctypes.c_int), ("map_state", ctypes.c_int), ("all_event_masks", ctypes.c_long),
                ("your_event_mask", ctypes.c_long), ("do_not_propagate_mask", ctypes.c_long),
                ("override_redirect", ctypes.c_int), ("screen", ctypes.c_void_p)]


# ошибка X (окно исчезло между запросами) — не выход из процесса, как у обработчика Xlib по умолчанию
_X_ERROR = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)(lambda display, event: 0)


class X11:
    """Окна Xwayland вложенного KWin через libX11 (ctypes): та же библиотека, что у Xwayland-клиентов, ставить нечего."""

    def __init__(self):
        lib = ctypes.util.find_library("X11")
        if lib is None:
            sys.exit("нет libX11")
        x = self.x = ctypes.CDLL(lib)
        x.XOpenDisplay.restype = ctypes.c_void_p
        x.XOpenDisplay.argtypes = [ctypes.c_char_p]
        x.XDefaultRootWindow.restype = ctypes.c_ulong
        x.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
        x.XQueryTree.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong),
                                 ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.POINTER(ctypes.c_ulong)),
                                 ctypes.POINTER(ctypes.c_uint)]
        x.XFetchName.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_void_p)]
        x.XGetWindowAttributes.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(XWindowAttributes)]
        x.XFree.argtypes = [ctypes.c_void_p]
        x.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
        x.XSetErrorHandler(_X_ERROR)
        self.display = x.XOpenDisplay(None)
        if not self.display:
            sys.exit(f"не открыть DISPLAY {os.environ.get('DISPLAY')}")
        self.root = x.XDefaultRootWindow(self.display)

    def _children(self, w):
        root, parent, n = ctypes.c_ulong(), ctypes.c_ulong(), ctypes.c_uint()
        kids = ctypes.POINTER(ctypes.c_ulong)()
        if not self.x.XQueryTree(self.display, w, ctypes.byref(root), ctypes.byref(parent), ctypes.byref(kids), ctypes.byref(n)):
            return []
        out = [kids[i] for i in range(n.value)]
        if kids:
            self.x.XFree(kids)
        return out

    def _name(self, w):
        p = ctypes.c_void_p()
        if not self.x.XFetchName(self.display, w, ctypes.byref(p)) or not p.value:
            return ""
        name = ctypes.string_at(p.value).decode("latin-1")
        self.x.XFree(p)
        return name

    def find(self, title):
        """Самое большое показанное окно, в имени которого есть title: (id, ширина, высота) или None."""
        best, stack = None, [self.root]
        while stack:
            w = stack.pop()
            kids = self._children(w)
            stack += kids
            if w == self.root or title not in self._name(w):
                continue
            a = XWindowAttributes()
            # неотображённое окно ffmpeg не снимет (BadMatch)
            if self.x.XGetWindowAttributes(self.display, w, ctypes.byref(a)) and a.map_state == IS_VIEWABLE \
                    and a.width > 1 and a.height > 1 and (best is None or a.width * a.height > best[1] * best[2]):
                best = (w, a.width, a.height)
        self.x.XSync(self.display, 0)
        return best


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
    ap.add_argument("--size", metavar="WxH", help="писать только окно этого размера (окно загрузки меньше: снимать его "
                                                 "ffmpeg продолжал бы в старом размере)")
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
    x11 = X11()
    child = subprocess.Popen(cmd)
    mark(event="command", pid=child.pid, wall=time.time())
    want = tuple(int(v) for v in a.size.split("x")) if a.size else None
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
                win = x11.find(a.title)
                if ffmpeg is not None and win is not None and win[1:] != size:
                    finish()
                    seg += 1
                elif ffmpeg is None and win is not None and (want is None or win[1:] == want):
                    size, log = win[1:], f"{base}.{seg:03d}.log"
                    with open(log, "w") as lf:
                        ffmpeg = subprocess.Popen(
                            ["ffmpeg", "-hide_banner", "-nostats", "-loglevel", "info", "-y",
                             "-use_wallclock_as_timestamps", "1", "-f", "x11grab", "-framerate", str(a.fps),
                             "-draw_mouse", "0", "-window_id", str(win[0]), "-i", os.environ["DISPLAY"],
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
