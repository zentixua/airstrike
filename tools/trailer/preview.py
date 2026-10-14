#!/usr/bin/env python3
"""Ранний просмотр ночи Newisle: по три кадра каждого снятого плана (вывод PASS из best.txt, живые записи) — маленькими
JPEG в <работа>/preview, рядом index.html: статус конвейера и дубли по порядку истории (что снято, что нет и почему).
Ещё — шапка (этап ночи, P1 на netcup, срок черновика) и живая миниатюра: новейший уже записанный кадр идущего дубля.
Конвейер его не ждёт и не знает о нём: идёт таймером рядом (раз в минуту), страницу отдаёт http.server на адресе Tailscale
ноутбука.

    python3 tools/trailer/preview.py --work mod/run/newisle-night
    python3 tools/trailer/preview.py --work mod/run/newisle-night/smoke --out mod/run/newisle-night/preview/smoke

Кадры — из уже выведенного (только чтение), JPEG — ffmpeg; готовые не пересобираются.
"""
import argparse
import glob
import html
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import newisle_cut  # noqa: E402

SCENES = os.path.join(HERE, "scenes")
PICKS = (0.2, 0.5, 0.8)      # доли плана: начало, середина, конец без краёв
WIDTH = 640
DEADLINE = "2026-10-15 05:00"  # UTC: черновик к утру Артёма
P1_EVERY = 300                 # статус P1 по ssh — не чаще, с
P1_CMD = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-i", "/mnt/data/SSH Keys/netcup", "ubuntu@100.94.165.81",
          "cat ~/claude/newisle-p1/status.txt"]


def load(path, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def frames_of(plan_dir, plan):
    """Кадры плана по порядку: <папка>/frames/<план>/*.png (вывод и живая запись — одна раскладка)."""
    d = os.path.join(plan_dir, "frames", plan)
    try:
        names = sorted(n for n in os.listdir(d) if n.endswith(".png"))
    except OSError:
        return []
    return [os.path.join(d, n) for n in names]


def picks(frames):
    if not frames:
        return []
    out = []
    for p in PICKS:
        f = frames[min(len(frames) - 1, int(p * len(frames)))]
        if f not in out:
            out.append(f)
    return out


def ffmpeg_jpeg(src, dst):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-vf", f"scale={WIDTH}:-2", "-q:v", "4", dst],
                   check=True, timeout=60)


def build(work, out=None, convert=ffmpeg_jpeg):
    """Кадры и страница. Итог — {план: [имена jpg]}."""
    out = out or os.path.join(work, "preview")
    os.makedirs(out, exist_ok=True)
    takes = load(os.path.join(SCENES, "newisle-takes.json"))
    state = load(os.path.join(work, "state.json"), {"takes": {}})
    dirs = newisle_cut.shot_dirs(state, takes)
    later = newisle_cut.live_plans(takes)
    shots = {}
    for plan, d in dirs.items():
        names = []
        for i, src in enumerate(picks(frames_of(d, plan))):
            name = f"{plan}-{i}.jpg"
            dst = os.path.join(out, name)
            if not os.path.exists(dst) or os.path.getmtime(dst) < os.path.getmtime(src):
                try:
                    convert(src, dst + ".tmp.jpg")
                    os.replace(dst + ".tmp.jpg", dst)
                except (OSError, subprocess.SubprocessError):
                    continue
            names.append(name)
        shots[plan] = names
    live = live_frame(work, out, convert)
    page = render(takes, state, shots, later, status(work), os.path.exists(os.path.join(out, "smoke", "index.html")),
                  header(out, live))
    tmp = os.path.join(out, "index.html.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(page)
    os.replace(tmp, os.path.join(out, "index.html"))
    return shots


def live_frame(work, out, convert, now=None):
    """Живая миниатюра: новейший кадр PNG в самой свежей папке дубля (основная ночь и проба), кроме самого нового — его,
    может быть, ещё пишут. Итог — (имя jpg, откуда, когда) или None."""
    recs = glob.glob(os.path.join(work, "takes", "*", "a*")) + glob.glob(os.path.join(work, "smoke", "takes", "*", "a*"))
    recs.sort(key=lambda d: os.path.getmtime(d) if os.path.exists(d) else 0, reverse=True)
    for rec in recs[:2]:
        pngs = []
        for root, _dirs, files in os.walk(rec):
            pngs += [os.path.join(root, f) for f in files if f.endswith(".png")]
        if len(pngs) < 2:
            continue
        pngs.sort(key=lambda f: (os.path.getmtime(f), f))
        src = pngs[-2]
        dst = os.path.join(out, "live.jpg")
        mark = os.path.join(out, "live.txt")
        if load_text(mark) != src:
            try:
                convert(src, dst + ".tmp.jpg")
                os.replace(dst + ".tmp.jpg", dst)
                with open(mark, "w", encoding="utf-8") as f:
                    f.write(src)
            except (OSError, subprocess.SubprocessError):
                return None
        return "live.jpg", os.path.relpath(src, work), os.path.getmtime(src)
    return None


def load_text(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def p1_status(out, now=None, fetch=None):
    """Статус P1 на netcup — из кэша, по ssh не чаще P1_EVERY."""
    now = now or time.time()
    cache = os.path.join(out, "p1.txt")
    if not os.path.exists(cache) or now - os.path.getmtime(cache) > P1_EVERY:
        try:
            text = fetch() if fetch else subprocess.run(P1_CMD, capture_output=True, text=True, timeout=20).stdout
        except (OSError, subprocess.SubprocessError):
            text = ""
        if text.strip():
            with open(cache, "w", encoding="utf-8") as f:
                f.write(text)
    return load_text(cache) or "нет ответа"


def header(out, live, now=None, fetch=None):
    now = now or time.time()
    left = time.mktime(time.strptime(DEADLINE, "%Y-%m-%d %H:%M")) - time.timezone - now
    parts = [f"<p>Сейчас {time.strftime('%H:%M', time.gmtime(now))} UTC; черновик — к 05:00 UTC"
             + (f", осталось {left / 3600:.1f} ч" if left > 0 else ", срок прошёл") + "</p>"]
    if live:
        name, src, at = live
        parts.append(f'<h2>Сейчас снимается</h2><img src="{name}?t={int(at)}"><div class="hole">{html.escape(src)} · '
                     f'{time.strftime("%H:%M:%S", time.gmtime(at))} UTC</div>')
    parts.append(f"<h2>P1 на netcup</h2><pre>{html.escape(p1_status(out, now, fetch))}</pre>")
    return "".join(parts)


def status(work):
    try:
        with open(os.path.join(work, "status.txt"), encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return "статуса ещё нет"


def why_not(st):
    """Почему дубль не снят: причины его попыток, коротко."""
    reasons = [a.get("reason") for a in st.get("attempts", []) if a.get("reason")]
    return "; ".join(dict.fromkeys(reasons))[:600]


def render(takes, state, shots, later, status_text, smoke=False, head=""):
    st_all = state.get("takes", {})
    rows = []
    for t in takes["takes"]:
        st = st_all.get(t["id"], {})
        plans = [p for s in t["shots"] for p in s["plans"]]
        state_word = {"passed": "снят", "chained": "только удары (живой — утром с Артёмом)" if "artem" in t else "только удары",
                      "skipped": "не снят"}.get(st.get("status"), "впереди" if not st else "снимается")
        cells = []
        for p in plans:
            imgs = "".join(f'<img src="{html.escape(n)}" loading="lazy">' for n in shots.get(p, []))
            if not imgs:
                imgs = '<div class="hole">' + ("утром с Артёмом" if p in later else "нет кадров") + "</div>"
            cells.append(f'<div class="plan"><b>{html.escape(p)}</b>{imgs}</div>')
        note = f'<div class="why">{html.escape(why_not(st))}</div>' if st.get("status") == "skipped" else ""
        rows.append(f'<section><h2>{html.escape(t["id"])} · {html.escape(t.get("title", ""))} — {state_word}</h2>{note}'
                    f'{"".join(cells)}</section>')
    return f"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="30"><title>Newisle night</title>
<style>
:root{{color-scheme:dark}} body{{background:#111;color:#ddd;font:14px/1.4 system-ui,sans-serif;margin:0 16px 32px}}
pre{{background:#1b1b1b;padding:8px;white-space:pre-wrap}} h2{{font-size:15px;margin:20px 0 6px}}
.plan{{margin:4px 0}} .plan b{{display:block;color:#aaa}} img{{width:min(100%,{WIDTH}px);margin:2px 4px 2px 0}}
.hole{{color:#888;padding:6px 0}} .why{{color:#e99}}
</style></head><body>
<h1>Newisle — ночная съёмка</h1><pre>{html.escape(status_text)}</pre>{'<p><a href="smoke/">Проба ночи</a></p>' if smoke else ''}{head}
{''.join(rows)}
</body></html>
"""


def main(argv=None):
    ap = argparse.ArgumentParser(description="ранний просмотр ночи Newisle")
    ap.add_argument("--work", required=True)
    ap.add_argument("--out")
    a = ap.parse_args(argv)
    shots = build(a.work, a.out)
    print(f"планов с кадрами: {sum(1 for v in shots.values() if v)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
