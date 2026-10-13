# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "scipy", "pillow"]
# ///
"""Камера плана — по сцене, до игры: журнал плана (EventLog трейлера) + карта (файлы регионов) → сцена → лучшая камера
каждого тела (FPV, кинодрон, штатив, следящая) по одной мере → путь камеры для вывода, черновой показ и отчёт.

    uv run tools/shot/shot.py plan p12.events.jsonl --regions <мир>/region --out shots/      # объект — по trailer_shots.json
    uv run tools/shot/shot.py plan <журналы…> --regions … --out … --styles fpv,tripod --previs best
    uv run tools/shot/shot.py demo --out shots/demo                                          # тестовый квартал без карты

На каждый план — `<out>/<план>/`: `summary.txt` (строка на камеру: мера, объект в кадре и виден, доля кадра, повороты;
`own` — камера сценария той же мерой), `<тело>/camera.jsonl` (поза на тик: `t`, `pos`, `yaw`, `pitch`, `roll`, `fov`),
`<тело>/path-camera.json` (камера `path` для replayscan), лучшая — ещё и `<out>/planned/<план>.camera.jsonl` (кандидат
`<тело>-planned` вывода трейлера: папку `planned` — в папку трейлера), `predicted.jsonl` (что мера ждёт от кадра — сверка с
qa.jsonl вывода), `report.json`; с `--previs` — `sheet.jpg`, `previs.mp4`, кадры. Карта рамки кэшируется в
`<out>/<план>/world.npz` (данные карты: не выносить с машины, где лежит мир).
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from shotlib import camera as C  # noqa: E402
from shotlib import demo as D  # noqa: E402
from shotlib import export as X  # noqa: E402
from shotlib import previs as PV  # noqa: E402
from shotlib import scene as S  # noqa: E402
from shotlib import solve as V  # noqa: E402
from shotlib import subject as J  # noqa: E402
from shotlib import world as W  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def log(*a):
    print(*a, flush=True)


# ---------------------------------------------------------------- рамка мира под план


def bounds(sc: S.Scene, p: J.Pick, radius: float) -> tuple[np.ndarray, np.ndarray]:
    """Рамка карты: `radius` блоков по горизонтали вокруг удара (без удара — вокруг объекта в середине окна), по высоте —
    от низа удара/объекта до верха объекта в рамке с запасом."""
    ts = np.arange(p.a, p.b + 1)
    if p.key_pos is not None:
        c = np.asarray(p.key_pos, dtype=np.float64)
    else:
        path = p.track.at(ts)
        ok = ~np.isnan(path[:, 0])
        c = path[ok][len(path[ok]) // 2] if ok.any() else sc.cams.pos[0]
    ys = [c[1]]
    if p.track is not None:
        path = p.track.at(ts)
        ok = ~np.isnan(path[:, 0]) & (np.hypot(path[:, 0] - c[0], path[:, 2] - c[2]) < radius)
        ys += list(path[ok, 1])
    lo = np.array([math.floor(c[0] - radius), max(-64, math.floor(min(ys) - 72)), math.floor(c[2] - radius)])
    hi = np.array([math.ceil(c[0] + radius), min(448, math.ceil(max(ys) + 48)), math.ceil(c[2] + radius)])
    if hi[1] - lo[1] < 96:
        hi[1] = lo[1] + 96
    return lo.astype(np.int64), hi.astype(np.int64)


def load_world(sc_path: str, p: J.Pick, sc: S.Scene, regions: str, radius: float, cache: str) -> W.World:
    if os.path.exists(cache):
        return W.load(cache)
    lo, hi = bounds(sc, p, radius)
    t0 = time.time()
    w = W.from_regions(regions, lo, hi)
    known = float((w.kind != W.UNKNOWN).mean())
    log(f"  карта {tuple(lo)}..{tuple(hi)}: {np.prod(hi - lo) / 1e6:.0f} млн клеток, известно {known:.0%}, "
        f"{time.time() - t0:.1f} с")
    W.save(w, cache)
    return w


# ---------------------------------------------------------------- камера сценария той же мерой


def own_result(sc: S.Scene, spec: V.Spec) -> V.Result | None:
    """Камера, которую сценарий вёл при записи (`cam` журнала), — той же мерой, что кандидаты."""
    if sc.cams is None or sc.cam_look is None:
        return None
    ts = np.arange(spec.a, spec.b + 1)
    if ts[0] > sc.cams.last or ts[-1] < sc.cams.first:
        return None
    tt = np.clip(ts, sc.cams.first, sc.cams.last)
    P = sc.cams.at(tt)
    look = sc.cam_look
    yaw = np.interp(tt, sc.cams.t, C.unwrap_deg(look[:, 0]))
    pitch = np.interp(tt, sc.cams.t, look[:, 1])
    roll = np.interp(tt, sc.cams.t, look[:, 2])
    fov = float(np.median(look[:, 3]))
    style = V.STYLES["tripod"]
    e = V.evaluate(sc, spec, style, P[None], fov, fine=True, angles=(yaw[None], pitch[None], roll[None]))
    rep = V.report(spec, e, fov)
    own = V.Style("own", vmax=0, amax=0, jerk=0, clear=0, look_sigma=0, spring_hz=0, damping=1, anticipate=0, yaw_rate=3)
    return V.Result(spec, own, P, yaw, pitch, roll, fov, float(e.cost[0]),
                    {k: (v[0] if hasattr(v, "__len__") and not k.startswith("_") else v) for k, v in e.parts.items()}, rep)


# ---------------------------------------------------------------- план


def styles_for(p: J.Pick) -> list[str]:
    if p.what == "ядерный подрыв" or p.track is not None and p.track.weapon == "nuke":
        return ["tracker", "drone", "tripod"]
    if p.key < 0:
        return ["fpv", "drone", "tripod", "tracker"]
    return ["fpv", "drone", "tripod"]


def spec_for(name: str, p: J.Pick, style: str) -> V.Spec:
    keys = [(p.key, p.key_pos, p.key_size)] if p.key >= 0 and p.key_pos is not None else []
    return V.Spec(name, p.a, p.b, p.track, keys=keys, style=style, size=p.size)


def line(name: str, r: V.Result) -> str:
    rep = r.report
    return (f"{name:8s} мера {r.cost:8.2f}  в кадре и виден {rep.get('subject_framed', 0):.2f}  в кадре "
            f"{rep.get('subject_in_frame', 0):.2f}  виден {rep.get('subject_visible', 0):.2f}  доля {rep.get('share_median', 0):.3f}"
            f"  fov {r.fov:4.0f}  поворот до {rep['pan_max_deg_s']:5.0f}°/с  крен до {rep['roll_max_deg']:4.0f}°")


def plan_one(path: str, args, table: dict):
    name = os.path.basename(path).split(".")[0]
    entry = table.get(name, {})
    sel = args.subject or entry.get("subject", "auto")
    span = args.span if args.span is not None else entry.get("span", 0)
    out = os.path.join(args.out, name)
    os.makedirs(out, exist_ok=True)
    log(f"{name}: объект {sel}" + (f", span {span}" if span else ""))
    blank = S.Scene(W.empty((0, 0, 0), (1, 1, 1)))
    sc = S.from_eventlog(path, blank.world)
    sc.fog = args.fog
    sc.clouds = args.clouds
    p = J.pick(sc, sel, span)
    log(f"  {p.what}: окно {p.a}..{p.b}" + (f", удар {p.key} у {np.round(p.key_pos, 1).tolist()}" if p.key >= 0 else ""))
    w = load_world(path, p, sc, args.regions, args.radius, os.path.join(out, "world.npz"))
    sc = S.from_eventlog(path, w)
    sc.fog = args.fog
    sc.clouds = args.clouds
    results: dict[str, V.Result] = {}
    own = own_result(sc, spec_for(name, p, "tripod"))
    if own is not None:
        results["own"] = own
    for st in (args.styles.split(",") if args.styles else styles_for(p)):
        t0 = time.time()
        r = V.solve(sc, spec_for(name, p, st), iters=args.iters, log=lambda *_: None)
        log(f"  {st}: {time.time() - t0:.1f} с")
        results[st] = r
        X.write_all(r, os.path.join(out, st))
    rows = [line(k, r) for k, r in results.items()]
    planned = {k: r for k, r in results.items() if k != "own"}
    best = max(planned, key=lambda k: (round(planned[k].report.get("subject_framed", 0), 2), -planned[k].cost))
    rows.append(f"лучшая: {best}")
    # камера для вывода трейлера (ReplayFilm, кандидат `<тело>-planned`): <out>/planned/<план>.camera.jsonl
    os.makedirs(os.path.join(args.out, "planned"), exist_ok=True)
    X.write_camera(planned[best], os.path.join(args.out, "planned", f"{name}.camera.jsonl"),
                   {"body": best, "shot": name, "framed": planned[best].report.get("subject_framed", 0)})
    text = "\n".join(rows)
    with open(os.path.join(out, "summary.txt"), "w", encoding="utf-8") as f:
        f.write(f"{name} {p.what} окно {p.a}..{p.b}\n{text}\n")
    log("\n".join("  " + r for r in rows))
    if args.previs != "none":
        which = list(results) if args.previs == "all" else (["own", best] if "own" in results else [best])
        for k in which:
            PV.render(results[k], sc, os.path.join(out, k), size=tuple(int(v) for v in args.size.split("x")),
                      every=args.every, night=args.night, log=log)
    return name, results, best


def cmd_plan(args):
    table = {}
    tf = args.table or os.path.join(HERE, "trailer_shots.json")
    if os.path.exists(tf):
        table = json.load(open(tf, encoding="utf-8"))
    summary = []
    for path in args.events:
        try:
            name, results, best = plan_one(path, args, table)
            r = results[best]
            o = results.get("own")
            summary.append(f"{name:6s} лучшая {best:7s} {r.report.get('subject_framed', 0):.2f}"
                           + (f"  (камера сценария {o.report.get('subject_framed', 0):.2f})" if o else ""))
        except SystemExit as e:
            log(f"  {os.path.basename(path)}: {e}")
            summary.append(f"{os.path.basename(path)}: {e}")
    log("\nИтог (доля окна, где объект в кадре, виден и читается):\n" + "\n".join(summary))


def cmd_demo(args):
    sc, t_hit, face = D.strike_scene(args.seed)
    p = J.Pick("missile → удар", sc.tracks["m1"], t_hit, face, S.size_of("missile"), S.blast_of("missile"),
               t_hit - 140, t_hit + 40)
    for st in (args.styles.split(",") if args.styles else ["fpv", "drone", "tripod"]):
        r = V.solve(sc, spec_for("demo", p, st), iters=args.iters, log=log)
        log(line(st, r))
        X.write_all(r, os.path.join(args.out, st))
        if args.previs != "none":
            PV.render(r, sc, os.path.join(args.out, st), size=tuple(int(v) for v in args.size.split("x")),
                      every=args.every, log=log)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("plan", "demo"):
        s = sub.add_parser(name)
        s.add_argument("--out", required=True)
        s.add_argument("--styles", default="", help="fpv,drone,tripod,tracker,handheld (по умолчанию — по объекту)")
        s.add_argument("--iters", type=int, default=10)
        s.add_argument("--previs", choices=("none", "best", "all"), default="best")
        s.add_argument("--size", default="480x270")
        s.add_argument("--every", type=int, default=2, help="кадр чернового показа через столько тиков")
        s.add_argument("--night", action="store_true")
        if name == "plan":
            s.add_argument("events", nargs="+", help="журналы планов <план>.events.jsonl")
            s.add_argument("--regions", required=True, help="папка region мира, где записан план (копия карты)")
            s.add_argument("--subject", default="", help="impact:W[@x,z][#n] first:W framed:W flight:W launch:W@x,z[,r] nuke auto")
            s.add_argument("--span", type=int, default=None)
            s.add_argument("--table", default="", help="объекты планов (по умолчанию trailer_shots.json)")
            s.add_argument("--radius", type=float, default=192.0, help="карта вокруг удара, блоков")
            s.add_argument("--fog", type=float, default=410.0, help="туман края прорисовки по горизонтали, блоков")
            s.add_argument("--clouds", type=float, default=math.nan, help="низ слоя облаков (INCOMING_FILM_CLOUDS)")
        else:
            s.add_argument("--seed", type=int, default=3)
    args = ap.parse_args(argv)
    {"plan": cmd_plan, "demo": cmd_demo}[args.cmd](args)


if __name__ == "__main__":
    main()
