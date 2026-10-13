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


class SolveTest(unittest.TestCase):
    def test_tripod_moves_off_the_wall(self):
        sc = wall_scene()
        spec = V.Spec("t", 40, 140, sc.tracks["1"], keys=[(120, np.array([70.0, 64.0, 30.0]), 12.0)], style="tripod", size=3.5)
        r = V.solve(sc, spec, log=lambda *_: None)
        self.assertGreater(r.report["subject_framed"], 0.8)
        self.assertLess(r.parts["keys"], 10)

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
            rows = open(os.path.join(out, "p99", "summary.txt"), encoding="utf-8").read()
            self.assertIn("own", rows)
            self.assertIn("лучшая: tripod", rows)
            cam = [json.loads(x) for x in open(os.path.join(out, "p99", "tripod", "camera.jsonl"))]
            self.assertEqual(cam[0]["t"], 1)
            self.assertEqual(len(cam), 119)
            path = json.load(open(os.path.join(out, "p99", "tripod", "path-camera.json")))
            self.assertEqual(path["kind"], "path")


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
