#!/usr/bin/env python3
"""Ранний просмотр ночи Newisle: по три кадра каждого снятого плана (вывод PASS из best.txt, живые записи) — маленькими
JPEG в <работа>/preview, рядом index.html: статус конвейера и дубли по порядку истории (что снято, что нет и почему).
Конвейер его не ждёт и не знает о нём: идёт таймером рядом, страницу отдаёт http.server на адресе Tailscale ноутбука.

    python3 tools/trailer/preview.py --work mod/run/newisle-night
    python3 tools/trailer/preview.py --work mod/run/newisle-night/smoke --out mod/run/newisle-night/preview/smoke

Кадры — из уже выведенного (только чтение), JPEG — ffmpeg; готовые не пересобираются.
"""
import argparse
import html
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import newisle_cut  # noqa: E402

SCENES = os.path.join(HERE, "scenes")
PICKS = (0.2, 0.5, 0.8)      # доли плана: начало, середина, конец без краёв
WIDTH = 640


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
    page = render(takes, state, shots, later, status(work), os.path.exists(os.path.join(out, "smoke", "index.html")))
    tmp = os.path.join(out, "index.html.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(page)
    os.replace(tmp, os.path.join(out, "index.html"))
    return shots


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


def render(takes, state, shots, later, status_text, smoke=False):
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
<meta http-equiv="refresh" content="300"><title>Newisle night</title>
<style>
:root{{color-scheme:dark}} body{{background:#111;color:#ddd;font:14px/1.4 system-ui,sans-serif;margin:0 16px 32px}}
pre{{background:#1b1b1b;padding:8px;white-space:pre-wrap}} h2{{font-size:15px;margin:20px 0 6px}}
.plan{{margin:4px 0}} .plan b{{display:block;color:#aaa}} img{{width:min(100%,{WIDTH}px);margin:2px 4px 2px 0}}
.hole{{color:#888;padding:6px 0}} .why{{color:#e99}}
</style></head><body>
<h1>Newisle — ночная съёмка</h1><pre>{html.escape(status_text)}</pre>{'<p><a href="smoke/">Проба ночи</a></p>' if smoke else ''}
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
