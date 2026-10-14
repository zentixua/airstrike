"""Проверки съёмки по сцене без игры и без карты: регионы пишутся тестом, журнал плана — тоже.

    uv run --with numpy --with scipy --with pillow python -m unittest discover -s tools/shot/tests
"""
from __future__ import annotations

import io
import json
import math
import os
import struct
import sys
import tempfile
import unittest
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import shot  # noqa: E402
from shotlib import camera as C  # noqa: E402
from shotlib import scene as S  # noqa: E402
from shotlib import solve as V  # noqa: E402
from shotlib import subject as J  # noqa: E402
from shotlib import world as W  # noqa: E402


# ---------------------------------------------------------------- запись NBT и регионов (только для тестов)


def _w_payload(out: io.BytesIO, v):
    if isinstance(v, dict):
        for k, x in v.items():
            out.write(bytes([_tag(x)]))
            kb = k.encode()
            out.write(struct.pack(">H", len(kb)) + kb)
            _w_payload(out, x)
        out.write(b"\0")
    elif isinstance(v, list):
        inner = _tag(v[0]) if v else 0
        out.write(struct.pack(">bi", inner, len(v)))
        for x in v:
            _w_payload(out, x)
    elif isinstance(v, str):
        b = v.encode()
        out.write(struct.pack(">H", len(b)) + b)
    elif isinstance(v, np.ndarray):
        out.write(struct.pack(">i", len(v)) + v.astype(">i8").tobytes())
    elif isinstance(v, bool) or (isinstance(v, int) and -128 <= v < 128):
        out.write(struct.pack(">b", int(v)))
    else:
        out.write(struct.pack(">i", int(v)))


def _tag(v) -> int:
    if isinstance(v, dict):
        return 10
    if isinstance(v, list):
        return 9
    if isinstance(v, str):
        return 8
    if isinstance(v, np.ndarray):
        return 12
    if isinstance(v, bool) or (isinstance(v, int) and -128 <= v < 128):
        return 1
    return 3


def nbt_bytes(root: dict) -> bytes:
    out = io.BytesIO()
    out.write(b"\x0a\x00\x00")
    _w_payload(out, root)
    return out.getvalue()


def pack(vals: np.ndarray, bits: int) -> np.ndarray:
    per = 64 // bits
    n = math.ceil(len(vals) / per)
    longs = np.zeros(n, dtype=np.uint64)
    for i, v in enumerate(vals):
        longs[i // per] |= np.uint64(int(v)) << np.uint64((i % per) * bits)
    return longs.view(np.int64)


def write_region(region_dir: str, blocks: dict, chunks: list):
    """Регион r.0.0: чанки `chunks` [(cx, cz)], блоки `blocks` {(x, y, z): имя}, остальное — воздух."""
    os.makedirs(region_dir, exist_ok=True)
    head = bytearray(8192)
    body = bytearray()
    for (cx, cz) in chunks:
        sections = []
        for sy in range(-4, 20):
            arr = ["minecraft:air"] * 4096
            any_block = False
            for (x, y, z), name in blocks.items():
                if x >> 4 == cx and z >> 4 == cz and y >> 4 == sy:
                    arr[(y & 15) * 256 + (z & 15) * 16 + (x & 15)] = name
                    any_block = True
            if not any_block:
                continue
            pal = sorted(set(arr))
            idx = np.array([pal.index(a) for a in arr])
            bits = max(4, math.ceil(math.log2(len(pal))))
            bs = {"palette": [{"Name": n} for n in pal]}
            if len(pal) > 1:
                bs["data"] = pack(idx, bits)
            sections.append({"Y": sy, "block_states": bs})
        data = zlib.compress(nbt_bytes({"Status": "minecraft:full", "xPos": cx, "zPos": cz, "sections": sections}))
        rec = struct.pack(">ib", len(data) + 1, 2) + data
        rec += b"\0" * (-len(rec) % 4096)
        off = 2 + len(body) // 4096
        i = (cx & 31) + (cz & 31) * 32
        head[i * 4:i * 4 + 4] = struct.pack(">I", (off << 8) | (len(rec) // 4096))
        body += rec
    with open(os.path.join(region_dir, "r.0.0.mca"), "wb") as f:
        f.write(head + body)


# ---------------------------------------------------------------- сцены


def wall_scene():
    """Ровное поле, стена x=40..42 (z 0..60, до y 90), за ней (x > 42) летит дрон и бьёт в землю в тик 120."""
    w = W.empty((-40, 50, -60), (200, 100, 180), ground_y=64)
    W.box(w, (40, 64, 0), (43, 90, 60))
    sc = S.Scene(w)
    ts = np.arange(0, 121)
    start, end = np.array([140.0, 100.0, 30.0]), np.array([70.0, 64.0, 30.0])
    k = (ts / 120.0)[:, None]
    sc.tracks["1"] = S.Track("1", "drone", ts, start * (1 - k) + end * k)
    sc.impacts.append(S.Impact(120, end, "drone", "1", ground=True))
    return sc


class WorldTest(unittest.TestCase):
    def test_region_round_trip(self):
        blocks = {(1, 64, 1): "minecraft:stone", (2, 65, 3): "minecraft:oak_leaves", (17, 70, 5): "minecraft:water",
                  (5, 64, 20): "minecraft:glass", (6, 64, 6): "minecraft:poppy"}
        for x in range(0, 32):
            for z in range(0, 32):
                blocks.setdefault((x, 63, z), "minecraft:grass_block")
        with tempfile.TemporaryDirectory() as d:
            write_region(d, blocks, [(0, 0), (1, 0), (0, 1)])
            w = W.from_regions(d, (0, 60, 0), (40, 80, 40))
        at = lambda x, y, z: int(w.kind_at(np.array([x + 0.5, y + 0.5, z + 0.5]))[()])  # noqa: E731
        self.assertEqual(at(1, 64, 1), W.OPAQUE)
        self.assertEqual(at(2, 65, 3), W.SOFT)
        self.assertEqual(at(17, 70, 5), W.WATER)
        self.assertEqual(at(5, 64, 20), W.SOFT)
        self.assertEqual(at(6, 64, 6), W.AIR)       # цветок — не преграда
        self.assertEqual(at(3, 64, 3), W.AIR)
        self.assertEqual(at(3, 63, 3), W.OPAQUE)
        self.assertEqual(at(20, 63, 20), W.UNKNOWN)  # чанка (1, 1) нет
        self.assertEqual(float(w.ground_at(np.array([3.5]), np.array([3.5]))[0]), 64.0)
        # неизвестный чанк не поднимает «землю»: небо рамки — над настоящими блоками
        self.assertLess(w.sky_y, 75)


class MeasureTest(unittest.TestCase):
    def test_wall_hides_subject_and_fog(self):
        sc = wall_scene()
        spec = V.Spec("t", 60, 119, sc.tracks["1"], style="tripod", size=3.5)
        st = V.STYLES["tripod"]
        n = spec.b - spec.a + 1
        behind = np.repeat(np.array([[20.0, 66.0, 30.0]]), n, 0)[None]   # за стеной: дрон ниже её верха — закрыт
        above = np.repeat(np.array([[20.0, 120.0, 30.0]]), n, 0)[None]   # над стеной: видно
        e1 = V.evaluate(sc, spec, st, behind, 40.0, fine=True)
        e2 = V.evaluate(sc, spec, st, above, 40.0, fine=True)
        low = sc.tracks["1"].at(np.arange(spec.a, spec.b + 1))[:, 1] < 88
        self.assertGreater(e1.parts["_occ"][0][low].mean(), 0.9)
        self.assertLess(e2.parts["_occ"][0].mean(), 0.1)
        sc.fog = 30.0
        e3 = V.evaluate(sc, spec, st, above, 40.0, fine=True)
        self.assertLess(e3.parts["_vis"][0].mean(), 0.1)

    def test_smoke_on_line(self):
        sc = wall_scene()
        sc.impacts.append(S.Impact(10, np.array([100.0, 64.0, 30.0]), "missile", None, ground=True))
        pf = sc.puffs(100.0)
        t_through = S.transmittance(pf, np.array([100.0, 70.0, -20.0]), np.array([100.0, 70.0, 80.0]))
        t_clear = S.transmittance(pf, np.array([0.0, 70.0, -20.0]), np.array([0.0, 70.0, 80.0]))
        self.assertLess(float(t_through), 0.2)
        self.assertGreater(float(t_clear), 0.99)

    def test_booster_trail_hides_what_is_behind_it(self):
        # «Град» на ускорителе 40 тиков по прямой вдоль x: след висит; луч вдоль следа закрыт, поперёк — когда клубы
        # разрослись и сошлись (через 15 с), мимо — чист; через 600 тиков след растаял
        sc = wall_scene()
        ts = np.arange(0, 41)
        sc.tracks["9"] = S.Track("9", "rocket", ts, np.stack([ts * 3.0, np.full(41, 120.0), np.full(41, 0.0)], 1))
        sc.trails = S.Trails.of(sc.tracks, {"9": list(ts)})
        pf = sc.puffs(80.0)
        along = float(S.transmittance(pf, np.array([-20.0, 120.0, 0.0]), np.array([140.0, 120.0, 0.0])))
        late = sc.puffs(320.0)
        across = float(S.transmittance(late, np.array([60.0, 120.0, -30.0]), np.array([60.0, 120.0, 30.0])))
        aside = float(S.transmittance(late, np.array([60.0, 140.0, -30.0]), np.array([60.0, 140.0, 30.0])))
        self.assertLess(along, 0.05)
        self.assertLess(across, 0.9)
        self.assertGreater(aside, 0.99)
        self.assertEqual(len(sc.trails.puffs(700.0)), 0)

    def test_trail_only_where_the_client_had_the_projectile(self):
        # два «Града» на ускорителе (фаза 2): у первого место от сущности клиента, у второго — от сервера (далеко от
        # записывающего, клубов в повторе нет); МБР кладёт клубы и без фазы, облако старта — если сущность была с начала
        rows = [{"t": 0, "new": 1, "weapon": "rocket"}, {"t": 0, "new": 2, "weapon": "rocket"}, {"t": 0, "new": 3, "weapon": "nuke"}]
        for t in range(30):
            rows.append({"t": t, "f": [[1, t * 3.0, 100.0, 0.0, 2, 1], [2, t * 3.0, 100.0, 500.0, 2, 0],
                                       [3, 0.0, 70.0 + t * 2, -500.0, 3, 1]]})
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "p.events.jsonl")
            with open(path, "w") as f:
                f.write("\n".join(json.dumps(r) for r in rows))
            sc = S.from_eventlog(path, wall_scene().world)
        pf = sc.trails.puffs(40.0)
        self.assertTrue((np.abs(pf[:, 2]) < 50).any())
        self.assertFalse((np.abs(pf[:, 2] - 500) < 50).any())
        self.assertGreater(int((np.abs(pf[:, 2] + 500) < 50).sum()), 30)


    def test_wind_drift_is_the_particle_tick(self):
        # снос клуба — как тик FxParticle: скорость += ветер, шаг, скорость × сопротивление
        for d in (0.9, 0.95, 0.94):
            x, v = 0.0, 0.0
            for age in range(1, 401):
                v += 1.0
                x += v
                v *= d
                if age in (1, 10, 100, 400):
                    self.assertAlmostEqual(float(S.Trails.drift(np.array([age], float), np.array([d]))[0]), x, places=6)


class SolveTest(unittest.TestCase):
    def test_tripod_moves_off_the_wall(self):
        sc = wall_scene()
        spec = V.Spec("t", 40, 140, sc.tracks["1"], keys=[(120, np.array([70.0, 64.0, 30.0]), 12.0)], style="tripod", size=3.5)
        r = V.solve(sc, spec, log=lambda *_: None)
        self.assertGreater(r.report["subject_framed"], 0.8)
        self.assertGreaterEqual(r.report["key"], 0.7)

    def test_need_is_the_measure(self):
        # штатив смотрит на место удара, снаряд входит в кадр в конце: удару (HIT) этого хватает, ведению (STRIKE) — нет
        sc = wall_scene()
        key = np.array([70.0, 64.0, 30.0])
        eye = np.array([60.0, 66.0, -20.0])
        n = 101
        P = np.repeat(eye[None, None], n, 1)
        yaw, pitch = C.angles(key + np.array([0, 4.0, 0]) - eye)
        ang = (np.full((1, n), yaw), np.full((1, n), pitch), np.zeros((1, n)))
        got = {}
        for name, need in (("hit", (0, 0, 0.6)), ("strike", (0.6, 0.75, 0.7))):
            spec = V.Spec("t", 40, 140, sc.tracks["1"], keys=[(120, key, 12.0)], style="tripod", size=3.5, need=need)
            e = V.evaluate(sc, spec, V.STYLES["tripod"], P, 20.0, fine=True, angles=ang)
            got[name] = (float(e.cost[0]), V.report(spec, e, 20.0))
        self.assertTrue(got["hit"][1]["pass"])
        self.assertFalse(got["strike"][1]["pass"])
        self.assertLess(got["strike"][1]["approach"], 0.6)
        self.assertLess(got["hit"][0] + 5, got["strike"][0])

    def test_fpv_is_flyable(self):
        sc = wall_scene()
        spec = V.Spec("t", 40, 140, sc.tracks["1"], keys=[(120, np.array([70.0, 64.0, 30.0]), 12.0)], style="fpv", size=3.5)
        r = V.solve(sc, spec, iters=6, log=lambda *_: None)
        v = np.linalg.norm(np.diff(r.P, axis=0), axis=1)
        self.assertLess(v.max(), V.STYLES["fpv"].vmax * 1.25)
        self.assertGreater(float(sc.world.clearance(r.P).min()), 1.0)    # не в блоках
        self.assertGreater(r.report["subject_in_frame"], 0.7)


class SubjectTest(unittest.TestCase):
    def test_pick_and_plan_from_eventlog(self):
        blocks = {}
        for x in range(0, 96):
            for z in range(0, 96):
                blocks[(x, 63, z)] = "minecraft:grass_block"
        for y in range(64, 90):
            for z in range(20, 60):
                blocks[(50, y, z)] = "minecraft:stone_bricks"
        with tempfile.TemporaryDirectory() as d:
            region = os.path.join(d, "region")
            write_region(region, blocks, [(cx, cz) for cx in range(6) for cz in range(6)])
            ev = os.path.join(d, "p99.events.jsonl")
            with open(ev, "w") as f:
                f.write(json.dumps({"t": 0, "mark": "start"}) + "\n")
                f.write(json.dumps({"t": 5, "new": 1, "id": "u1", "weapon": "drone"}) + "\n")
                f.write(json.dumps({"t": 5, "new": 2, "id": "u2", "weapon": "drone"}) + "\n")
                for t in range(5, 100):
                    k = (t - 5) / 94
                    a = [90 - 40 * k, 100 - 36 * k, 40]
                    b = [10, 90, 10 + 0.5 * t]
                    f.write(json.dumps({"t": t, "f": [[1, *a, "cruise", 7], [2, *b, "cruise", 8]],
                                        "cam": [30, 70, 40, -90, 10, 0, 70]}) + "\n")
                f.write(json.dumps({"t": 99, "impact": 1, "pos": [50.5, 64, 40]}) + "\n")
                f.write(json.dumps({"t": 100, "mark": "end"}) + "\n")
            sc = S.from_eventlog(ev, W.empty((0, 0, 0), (1, 1, 1)))
            p = J.pick(sc, "framed:drone")
            self.assertEqual(p.track.id, "1")
            self.assertEqual((p.a, p.b, p.key), (1, 119, 99))
            out = os.path.join(d, "out")
            shot.main(["plan", ev, "--regions", region, "--out", out, "--styles", "tripod", "--previs", "none",
                       "--radius", "60", "--iters", "3"])
            with open(os.path.join(out, "p99", "summary.txt"), encoding="utf-8") as f:
                rows = f.read()
            self.assertIn("own", rows)
            self.assertIn("лучшая: tripod", rows)
            cam = lines(os.path.join(out, "p99", "tripod", "camera.jsonl"))
            self.assertEqual(cam[0]["t"], 1)
            self.assertEqual(len(cam), 119)
            planned = lines(os.path.join(out, "planned", "p99.camera.jsonl"))
            self.assertEqual(planned[0]["body"], "tripod")
            self.assertEqual(len(planned), 120)
            with open(os.path.join(out, "p99", "tripod", "path-camera.json"), encoding="utf-8") as f:
                path = json.load(f)
            self.assertEqual(path["kind"], "path")
            # проверка кадров вывода (FrameQa) своей формулой: векторы камеры Minecraft, курс −90 — на восток
            # вторая камера — у вывода снаряд с середины пути уходит от журнала вбок (журнал с изъяном очереди мест)
            ty = math.tan(math.radians(35))
            tx = ty * 16 / 9
            eye = np.array([30.0, 70.0, 40.0])
            fwd, left, up = np.array([1.0, 0, 0]), np.array([0, 0, -1.0]), np.array([0, 1.0, 0])
            qas = []
            for cand, aside, lag in (("own-38mm", 0.0, 0.0), ("own-off", 0.4, 0.0), ("own-lag", 0.0, 1.0)):
                qa_dir = os.path.join(d, "render", "p99", cand)
                os.makedirs(qa_dir)
                qas.append(os.path.join(qa_dir, "qa.jsonl"))
                with open(qas[-1], "w") as f:
                    for t in range(5, 99):
                        k = (t - lag - 5) / 94   # у own-lag вывод на тик позади журнала (место на доле тика)
                        dd = np.array([90 - 40 * k, 100 - 36 * k, 40 + aside * max(0, t - 40)]) - eye
                        z = float(dd @ fwd)
                        x, y = -float(dd @ left) / z / tx, float(dd @ up) / z / ty
                        f.write(json.dumps({"t": t, "in": abs(x) <= 0.92 and abs(y) <= 0.92, "x": x, "y": y,
                                            "share": round(3.5 / (2 * z * ty), 4), "seen": 1.0, "clear": 1.0, "cloud": False,
                                            "far": False, "entity": True, "cam": [*eye, -90.0, 0.0], "fov": 70.0}) + "\n")
            # четвёртая — вывод ведёт другой снаряд (второй шахед; камера смотрит на запад, на него)
            qa_dir = os.path.join(d, "render", "p99", "own-other")
            os.makedirs(qa_dir)
            qas.append(os.path.join(qa_dir, "qa.jsonl"))
            fwd_w, left_w = np.array([-1.0, 0, 0]), np.array([0, 0, 1.0])
            with open(qas[-1], "w") as f:
                for t in range(5, 99):
                    dd = np.array([10, 90, 10 + 0.5 * t]) - eye
                    z = float(dd @ fwd_w)
                    x, y = -float(dd @ left_w) / z / tx, float(dd @ up) / z / ty
                    f.write(json.dumps({"t": t, "in": abs(x) <= 0.92 and abs(y) <= 0.92, "x": x, "y": y,
                                        "share": round(3.5 / (2 * z * ty), 4), "seen": 1.0, "clear": 1.0, "cloud": False,
                                        "far": False, "entity": True, "cam": [*eye, 90.0, 0.0], "fov": 70.0}) + "\n")
            shot.main(["check", *qas, "--regions", region, "--out", os.path.join(d, "chk"), "--replays", d, "--radius", "60"])
            with open(os.path.join(d, "chk", "check.json"), encoding="utf-8") as f:
                res, off, lagged, other = json.load(f)
            self.assertEqual(other["other"], "2")
            self.assertLess(other["other_med"], 0.5)
            self.assertFalse(shot.journal_off(other))
            self.assertGreater(res["in_agree"], 0.98)
            self.assertLess(res["dx"], 0.01)
            self.assertLess(res["dy"], 0.01)
            self.assertAlmostEqual(res["share_ratio"], 1.0, places=2)
            self.assertGreater(res["blocked_mine_only"], 10)   # за стеной, а «вывод» тут нарочно всё видит
            # место объекта у вывода обратно из кадра: совпадает с журналом, а у второй — в конце на ~23 блока вбок
            self.assertLess(res["off_p90"], 0.5)
            self.assertFalse(shot.journal_off(res))
            self.assertGreater(off["off_p90"], 15)
            self.assertTrue(shot.journal_off(off))
            self.assertIsNone(off["other"])
            self.assertEqual(res["journal"], "записи")
            # вывод на тик позади: не изъян журнала — сдвиг −1 находится, расхождение при нём ~0
            self.assertEqual(lagged["off_lag"], -1.0)
            self.assertLess(lagged["off_p90"], 0.5)
            self.assertGreater(lagged["off0_med"], 0.4)
            self.assertFalse(shot.journal_off(lagged))
            # журнал самого вывода рядом с qa.jsonl (клиент, который рисовал кадры) — сверка по нему
            os.replace(ev, os.path.join(os.path.dirname(qas[0]), "p99.events.jsonl"))
            shot.main(["check", qas[0], "--regions", region, "--out", os.path.join(d, "chk2"), "--radius", "60"])
            with open(os.path.join(d, "chk2", "check.json"), encoding="utf-8") as f:
                own = json.load(f)[0]
            self.assertEqual(own["journal"], "вывода")
            self.assertLess(own["off_p90"], 0.5)


class PickTest(unittest.TestCase):
    def test_flight_tie_goes_to_the_larger(self):
        # оба дрона в кадре всё окно: как `Rig.flight`, берётся крупнее в кадре (ближе к камере), а не первый
        with tempfile.TemporaryDirectory() as d:
            ev = os.path.join(d, "p98.events.jsonl")
            with open(ev, "w") as f:
                f.write(json.dumps({"t": 0, "mark": "start"}) + "\n")
                f.write(json.dumps({"t": 1, "new": 1, "id": "far", "weapon": "drone"}) + "\n")
                f.write(json.dumps({"t": 1, "new": 2, "id": "near", "weapon": "drone"}) + "\n")
                for t in range(1, 60):
                    f.write(json.dumps({"t": t, "f": [[1, 200, 70, t * 0.2, "cruise", 7], [2, 80, 70, t * 0.2, "cruise", 7]],
                                        "cam": [30, 70, 5, -90, 0, 0, 70]}) + "\n")
                f.write(json.dumps({"t": 60, "mark": "end"}) + "\n")
            sc = S.from_eventlog(ev, W.empty((0, 0, 0), (1, 1, 1)))
            self.assertEqual(J.pick(sc, "flight:drone").track.id, "2")


    def test_span_cuts_the_window_not_the_choice(self):
        # как `Rig.flight`: объект — тот, что камера держит в кадре дольше всех за всё окно «start»…«end»; `span` плана
        # режет только окно проверки (13.10 p12: выбор по первым 30 тикам взял другой шахед, чем вывод)
        with tempfile.TemporaryDirectory() as d:
            ev = os.path.join(d, "p97.events.jsonl")
            with open(ev, "w") as f:
                f.write(json.dumps({"t": 0, "mark": "start"}) + "\n")
                f.write(json.dumps({"t": 1, "new": 1, "id": "early", "weapon": "drone"}) + "\n")
                f.write(json.dumps({"t": 1, "new": 2, "id": "late", "weapon": "drone"}) + "\n")
                for t in range(1, 80):
                    early = [80, 70, 5] if t < 30 else [80, 70, 400]
                    late = [80, 70, 400] if t < 20 else [90, 70, 5]
                    f.write(json.dumps({"t": t, "f": [[1, *early, "cruise", 7], [2, *late, "cruise", 7]],
                                        "cam": [30, 70, 5, -90, 0, 0, 70]}) + "\n")
                f.write(json.dumps({"t": 80, "mark": "end"}) + "\n")
            sc = S.from_eventlog(ev, W.empty((0, 0, 0), (1, 1, 1)))
            p = J.pick(sc, "flight:drone", 30)
            self.assertEqual((p.track.id, p.a, p.b), ("2", 1, 30))
            p = J.pick(sc, "track:1", 30)
            self.assertEqual((p.track.id, p.key, p.b), ("1", -1, 30))

    def test_ties_go_to_the_first_born(self):
        # из равных ударов трейлер берёт первый по строкам «new» (`EventLog.Data.flights`), не по первой точке пути
        with tempfile.TemporaryDirectory() as d:
            ev = os.path.join(d, "p96.events.jsonl")
            with open(ev, "w") as f:
                f.write(json.dumps({"t": 0, "mark": "start"}) + "\n")
                f.write(json.dumps({"t": 1, "new": 2, "id": "b", "weapon": "rocket"}) + "\n")
                f.write(json.dumps({"t": 1, "new": 1, "id": "a", "weapon": "rocket"}) + "\n")
                for t in range(1, 40):
                    f.write(json.dumps({"t": t, "f": [[1, 50, 90 - t, 20, "cruise", 7], [2, 60, 90 - t, 20, "cruise", 7]]}) + "\n")
                f.write(json.dumps({"t": 40, "impact": 1, "pos": [50, 50, 20]}) + "\n")
                f.write(json.dumps({"t": 40, "impact": 2, "pos": [60, 50, 20]}) + "\n")
                f.write(json.dumps({"t": 50, "mark": "end"}) + "\n")
            sc = S.from_eventlog(ev, W.empty((0, 0, 0), (1, 1, 1)))
            self.assertEqual(list(sc.tracks), ["2", "1"])
            self.assertEqual(J.pick(sc, "first:rocket").track.id, "2")


class ChooseTest(unittest.TestCase):
    def test_first_by_intent_unless_much_worse(self):
        R = lambda m, f: type("R", (), {"report": {"margin": m, "subject_framed": f}, "cost": 0.0})()  # noqa: E731
        order = ["fpv", "drone", "tripod"]
        # 13.10 p12: FPV проходит, но объект в 42 % кадров против 90 % у дрона — дрон
        self.assertEqual(shot.choose({"fpv": R(0.17, 0.42), "drone": R(0.65, 0.90), "tripod": R(0.3, 0.8)}, order), "drone")
        # FPV чуть хуже лучшего — FPV, как задумано
        self.assertEqual(shot.choose({"fpv": R(0.5, 0.85), "drone": R(0.65, 0.90)}, order), "fpv")
        # не проходит никто — у кого меньше всех не хватает
        self.assertEqual(shot.choose({"fpv": R(-0.3, 0.2), "drone": R(-0.1, 0.5)}, order), "drone")
        self.assertEqual(shot.subject_for("p32", "p32=track:3,p01b=impact:missile@2977.5,602.5"), "track:3")
        self.assertEqual(shot.subject_for("p01b", "p32=track:3,p01b=impact:missile@2977.5,602.5"), "impact:missile@2977.5,602.5")
        self.assertEqual(shot.subject_for("p12", "p32=track:3"), "")
        self.assertEqual(shot.subject_for("p12", "flight:drone"), "flight:drone")


def lines(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(x) for x in f if x.strip()]


class CameraTest(unittest.TestCase):
    def test_minecraft_angles(self):
        # курс 0 — на юг (+z), 90 — на запад (−x); тангаж плюс — вниз
        np.testing.assert_allclose(C.forward(0.0, 0.0), [0, 0, 1], atol=1e-9)
        np.testing.assert_allclose(C.forward(90.0, 0.0), [-1, 0, 0], atol=1e-9)
        np.testing.assert_allclose(C.forward(0.0, 90.0), [0, -1, 0], atol=1e-9)
        yaw, pitch = C.angles(np.array([-1.0, -1.0, 0.0]))
        self.assertAlmostEqual(float(yaw), 90.0)
        self.assertAlmostEqual(float(pitch), 45.0)


if __name__ == "__main__":
    unittest.main()
