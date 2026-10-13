# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "scipy", "pillow"]
# ///
"""Камера плана — по сцене, до игры: журнал плана (EventLog трейлера) + карта (файлы регионов) → сцена → лучшая камера
каждого тела (FPV, кинодрон, штатив, следящая) по одной мере → путь камеры для вывода, черновой показ и отчёт.

    uv run tools/shot/shot.py plan p12.events.jsonl --regions <мир>/region --out shots/      # объект — по trailer_shots.json
    uv run tools/shot/shot.py plan <журналы…> --regions … --out … --styles fpv,tripod --previs best
    uv run tools/shot/shot.py demo --out shots/demo                                          # тестовый квартал без карты
    uv run tools/shot/shot.py check <трейлер>/render/*/*/qa.jsonl --regions … --out …         # мера против проверки кадров

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
    """Рамка карты: `radius` блоков по горизонтали вокруг удара (без удара — вокруг объекта в середине окна; у подрыва —
    вокруг камеры сценария, как `ReplayFilm.seekPoint`), по высоте — от низа удара/объекта до верха объекта с запасом."""
    ts = np.arange(p.a, p.b + 1)
    if p.overlay and sc.cams is not None:
        c = sc.cams.at(np.array([np.clip(p.a, sc.cams.first, sc.cams.last)]))[0]
    elif p.key_pos is not None:
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
    if sc.cams is not None:
        cp = sc.cams.at(np.clip(ts, sc.cams.first, sc.cams.last))
        ok = np.hypot(cp[:, 0] - c[0], cp[:, 2] - c[2]) < radius
        ys += list(cp[ok, 1])
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
    if p.overlay:
        return []  # подрыв в сотнях блоков: только мера камеры сценария
    if p.track is not None and p.track.weapon == "nuke":
        return ["tracker", "drone", "tripod"]
    if p.key < 0:
        return ["fpv", "drone", "tripod", "tracker"]
    return ["fpv", "drone", "tripod"]


def spec_for(name: str, p: J.Pick, style: str) -> V.Spec:
    keys = [(p.key, p.key_pos, p.key_size)] if p.key >= 0 and p.key_pos is not None else []
    return V.Spec(name, p.a, p.b, p.track, keys=keys, style=style, size=p.size, overlay=p.overlay)


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
    if args.shown:
        S.smooth_tracks(sc, args.shown)
    p.track = sc.tracks.get(p.track.id) if p.track is not None else None
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
    if not planned:
        with open(os.path.join(out, "summary.txt"), "w", encoding="utf-8") as f:
            f.write(f"{name} {p.what} окно {p.a}..{p.b}\n" + "\n".join(rows) + "\n")
        log("\n".join("  " + r for r in rows))
        return name, results, "own"
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
        with open(tf, encoding="utf-8") as f:
            table = json.load(f)
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


# ---------------------------------------------------------------- сверка меры с проверкой кадров вывода


def subject_at(p: J.Pick, t: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Куда смотрит проверка кадров (`Rig.Subject.aim`, `aimSize`): снаряд, до появления — место появления, после удара
    — место удара с подъёмом шара."""
    t = np.asarray(t, dtype=np.float64)
    pos = np.full((len(t), 3), np.nan)
    size = np.full(len(t), p.size)
    if p.track is not None:
        tt = np.clip(t, p.track.first, p.track.last)
        pos = p.track.at(tt)
        if p.key >= 0 and p.key_pos is not None:
            pos[t > p.track.last] = p.key_pos
    if p.key >= 0 and p.key_pos is not None:
        after = t >= p.key
        lift = np.minimum(1, (t[after] - p.key) / 40.0) * p.key_size * 0.4
        pos[after] = np.asarray(p.key_pos) + np.stack([np.zeros_like(lift), lift, np.zeros_like(lift)], axis=1)
        size[after] = p.key_size
    return pos, size


def check_one(qa_path: str, args, table: dict) -> dict | None:
    cand = os.path.basename(os.path.dirname(qa_path))
    name = os.path.basename(os.path.dirname(os.path.dirname(qa_path)))
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(qa_path))))
    events = os.path.join(args.replays or os.path.join(root, "replays"), f"{name}.events.jsonl")
    if not os.path.exists(events):
        log(f"{name}/{cand}: нет журнала {events}")
        return None
    entry = table.get(name, {})
    blank = S.Scene(W.empty((0, 0, 0), (1, 1, 1)))
    sc = S.from_eventlog(events, blank.world)
    sc.fog, sc.clouds = args.fog, args.clouds
    try:
        p = J.pick(sc, entry.get("subject", "auto"), entry.get("span", 0))
    except SystemExit as e:
        log(f"{name}/{cand}: {e}")
        return None
    cache = os.path.join(args.out, name, "world.npz")
    os.makedirs(os.path.dirname(cache), exist_ok=True)
    w = load_world(events, p, sc, args.regions, args.radius, cache)
    sc = S.from_eventlog(events, w)
    sc.fog, sc.clouds = args.fog, args.clouds
    if args.shown:
        S.smooth_tracks(sc, args.shown)
    p.track = sc.tracks.get(p.track.id) if p.track is not None else None
    rows = []
    with open(qa_path, encoding="utf-8") as f:
        for ln in f:
            try:
                rows.append(json.loads(ln))
            except json.JSONDecodeError:
                pass
    rows = [r for r in rows if "cam" in r]
    if not rows:
        return None
    t = np.array([r["t"] for r in rows])
    cam = np.array([r["cam"] for r in rows], dtype=np.float64)
    fov = np.array([r.get("fov", 70.0) for r in rows])
    eye, yaw, pitch = cam[:, :3], cam[:, 3], cam[:, 4]
    tgt, size = subject_at(p, t)
    have = ~np.isnan(tgt[:, 0])
    tgt_ = np.where(have[:, None], tgt, eye + C.forward(yaw, pitch))
    # крен проверка кадров не учитывает (векторы камеры Minecraft — без крена): и здесь без него
    u, v, z = C.project(eye, yaw, pitch, np.zeros_like(yaw), fov, 16 / 9, tgt_)
    mine_in = have & (z > 0.1) & (np.abs(u) <= 0.92) & (np.abs(v) <= 0.92)
    occ = V._sight(w, eye, tgt_, np.maximum(1.5, size * 0.4), unknown_blocks=not p.overlay)
    haze = V._haze(sc, eye, tgt_, p.overlay)
    smoke = np.array([float(S.transmittance(sc.puffs(float(ti)), eye[i], tgt_[i], spare=float(size[i] * 2 + 4)))
                      for i, ti in enumerate(t)])
    mine_vis = mine_in & (occ < 0.5) & (haze < 0.5) & (smoke >= 0.5)
    sh = C.share(size, np.maximum(z, 0.1), fov)
    q_in = np.array([bool(r.get("in")) for r in rows])
    q_seen = np.array([float(r.get("seen", 0)) for r in rows])
    q_clear = np.array([float(r.get("clear", 1)) for r in rows])
    q_cloud = np.array([bool(r.get("cloud")) for r in rows])
    q_far = np.array([bool(r.get("far")) for r in rows])
    q_vis = q_in & (q_seen >= 0.6) & ~q_cloud & ~q_far & (q_clear >= 0.5)
    both = mine_in & q_in
    qx = np.array([float(r.get("x", 9)) for r in rows])
    qy = np.array([float(r.get("y", 9)) for r in rows])
    qs = np.array([float(r.get("share", 0)) for r in rows])
    res = {"shot": name, "cand": cand, "frames": len(rows),
           "in_agree": float((mine_in == q_in).mean()),
           "vis_agree": float((mine_vis == q_vis).mean()),
           "vis_mine": float(mine_vis.mean()), "vis_qa": float(q_vis.mean()),
           "dx": float(np.median(np.abs(u[both] - qx[both]))) if both.any() else math.nan,
           "dy": float(np.median(np.abs(v[both] - qy[both]))) if both.any() else math.nan,
           "share_ratio": float(np.median(sh[both] / np.maximum(qs[both], 1e-6))) if both.any() else math.nan,
           "blocked_mine_only": int((q_in & mine_in & (occ >= 0.5) & (q_seen >= 0.6)).sum()),
           "blocked_qa_only": int((q_in & mine_in & (occ < 0.5) & (q_seen < 0.6)).sum()),
           "smoke_mine_only": int((both & (smoke < 0.5) & (q_clear >= 0.5)).sum()),
           "smoke_qa_only": int((both & (smoke >= 0.5) & (q_clear < 0.5)).sum())}
    log(f"{name:6s} {cand:28s} кадров {res['frames']:4d}  в кадре совпало {res['in_agree']:.2f}  виден совпало "
        f"{res['vis_agree']:.2f} (мера {res['vis_mine']:.2f}, вывод {res['vis_qa']:.2f})  место ±{res['dx']:.3f}/{res['dy']:.3f}"
        f"  доля ×{res['share_ratio']:.2f}  блоки: только мера {res['blocked_mine_only']}, только вывод "
        f"{res['blocked_qa_only']}  дым: только мера {res['smoke_mine_only']}, только вывод {res['smoke_qa_only']}")
    return res


def cmd_check(args):
    with open(args.table or os.path.join(HERE, "trailer_shots.json"), encoding="utf-8") as f:
        table = json.load(f)
    os.makedirs(args.out, exist_ok=True)
    out = [r for r in (check_one(q, args, table) for q in args.qa) if r]
    if out:
        keys = ("in_agree", "vis_agree")
        log("\nВсего кадров " + str(sum(r["frames"] for r in out)) + ": " + ", ".join(
            f"{k} {np.average([r[k] for r in out], weights=[r['frames'] for r in out]):.3f}" for k in keys))
    with open(os.path.join(args.out, "check.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)


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
    for name in ("plan", "demo", "check"):
        s = sub.add_parser(name)
        s.add_argument("--out", required=True)
        s.add_argument("--styles", default="", help="fpv,drone,tripod,tracker,handheld (по умолчанию — по объекту)")
        s.add_argument("--iters", type=int, default=10)
        s.add_argument("--previs", choices=("none", "best", "all"), default="best")
        s.add_argument("--size", default="480x270")
        s.add_argument("--every", type=int, default=2, help="кадр чернового показа через столько тиков")
        s.add_argument("--night", action="store_true")
        if name == "check":
            s.add_argument("qa", nargs="+", help="qa.jsonl вывода: <папка трейлера>/<вывод>/<план>/<камера>/qa.jsonl")
            s.add_argument("--replays", default="", help="папка журналов (по умолчанию <папка трейлера>/replays)")
        if name == "plan":
            s.add_argument("events", nargs="+", help="журналы планов <план>.events.jsonl")
        if name in ("plan", "check"):
            s.add_argument("--regions", required=True, help="папка region мира, где записан план (копия карты)")
            s.add_argument("--table", default="", help="объекты планов (по умолчанию trailer_shots.json)")
            s.add_argument("--radius", type=float, default=192.0, help="карта вокруг удара, блоков")
            s.add_argument("--fog", type=float, default=410.0, help="туман края прорисовки по горизонтали, блоков")
            s.add_argument("--clouds", type=float, default=math.nan, help="низ слоя облаков (INCOMING_FILM_CLOUDS)")
            s.add_argument("--shown", type=float, default=0.0,
                           help="журнал до очереди мест (film-war2 и раньше): пути снарядов сгладить гауссом σ тиков (≈1.5)")
        if name == "plan":
            s.add_argument("--subject", default="", help="impact:W[@x,z][#n] first:W framed:W flight:W launch:W@x,z[,r] nuke auto")
            s.add_argument("--span", type=int, default=None)
        if name == "demo":
            s.add_argument("--seed", type=int, default=3)
    args = ap.parse_args(argv)
    {"plan": cmd_plan, "demo": cmd_demo, "check": cmd_check}[args.cmd](args)


if __name__ == "__main__":
    main()
