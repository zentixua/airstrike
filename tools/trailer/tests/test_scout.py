"""Разведка мест на придуманной карте высот: равнина, высотка с ровной крышей, стена, холм, улица между домами;
scout.py должен поставить камеру каждого замысла туда, откуда цель видна, и смотреть на неё.

    python3 -m unittest discover -s tools/trailer/tests     (нужны numpy; черновой кадр — ещё pillow и scipy)
"""
import json
import math
import os
import subprocess
import sys
import tempfile
import unittest

try:
    import numpy as np
except ImportError:  # облако без пакетов: тест пропускается
    np = None

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(os.path.dirname(HERE), "scout.py")
sys.path.insert(0, os.path.dirname(HERE))


def _map():
    """Рамка 400×400 от (−100, −100): земля y 63 (верх блока), высотка 16×16 у (100, 100) до y 119, стена x 40..41
    по z 0..199 до y 99, холм у (−60, 160) до y 150, улица по z 300 между рядами домов высотой 15."""
    n = 400
    top = np.full((n, n), 63, np.int16)
    ground = np.full((n, n), 63, np.int16)

    def at(x, z):
        return z + 100, x + 100

    z, x = at(100, 100)
    top[z - 8:z + 8, x - 8:x + 8] = 119
    z0, x0 = at(40, 0)
    top[z0:z0 + 200, x0:x0 + 2] = 99
    zz, xx = np.mgrid[0:n, 0:n]
    hz, hx = at(-60, 160)
    hill = 150 - np.hypot(zz - hz, xx - hx) * 1.2
    top = np.maximum(top, hill.astype(np.int16))
    ground = np.maximum(ground, hill.astype(np.int16))
    sz, sx = at(0, 300)
    top[sz - 14:sz - 4, sx - 80:sx + 80] = 78
    top[sz + 4:sz + 14, sx - 80:sx + 80] = 78
    streets = [{"from": [-80, 300], "to": [80, 300], "length": 161, "sides": 15.0, "axis": "x"}]
    return top, ground, streets


@unittest.skipIf(np is None, "нужен numpy")
class ScoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import scout
        cls.S = scout
        top, ground, streets = _map()
        cls.h = scout.Heights(top, ground, -100, -100, streets)

    def test_angles_like_minecraft(self):
        yaw, pitch = self.S.look_angles([0, 70, 0], [0, 70, 10])
        self.assertAlmostEqual(yaw, 0, 3)          # юг — +z
        yaw, pitch = self.S.look_angles([0, 70, 0], [-10, 60, 0])
        self.assertAlmostEqual(yaw, 90, 3)         # запад — −x
        self.assertAlmostEqual(pitch, 45, 3)       # вниз — плюс

    def test_sight_blocked_by_wall_and_clear_over_it(self):
        self.assertFalse(self.h.sees([0, 70, 100], [80, 70, 100]))
        self.assertTrue(self.h.sees([0, 140, 100], [80, 70, 100]))
        self.assertTrue(self.h.sees([60, 70, 100], [100, 100, 100]), "дом самой цели не в счёт: видна его стена")
        self.assertFalse(self.h.sees([60, 70, 100], [100, 100, 100], own=0), "без этого цель в доме закрыта им же")

    def test_point_specs(self):
        h, P = self.h, self.S.point
        places = {"tower": {"at": [100, 100], "above": 0}, "field": [-90, 0]}
        np.testing.assert_allclose(P(h, [100, 100], {}), [100, 122, 100])
        np.testing.assert_allclose(P(h, {"at": [100, 100], "above": 5}, {}), [100, 125, 100])
        np.testing.assert_allclose(P(h, {"at": [100, 100], "ground": 2}, {}), [100, 66, 100])
        np.testing.assert_allclose(P(h, "tower", places, 0.0), [100, 120, 100])
        np.testing.assert_allclose(P(h, {"place": "field", "above": 10}, places), [-90, 74, 0])
        np.testing.assert_allclose(P(h, [1, 2, 3], {}), [1, 2, 3])

    def test_roof_sees_target_over_wall(self):
        plan = {"id": "r", "target": [-90, 20],
                "pose": {"kind": "roof", "near": [100, 100], "radius": 20, "min_height": 20, "dist": [60, 260]}}
        r = self.S.resolve(self.h, plan, {})
        self.assertTrue(r["ok"], r["notes"])
        e = r["frames"][0]["eye"]
        self.assertGreater(e[1], 120, "глаз на крыше высотки")
        self.assertLessEqual(math.hypot(e[0] - 100, e[2] - 100), 12)
        self.assertTrue(r["sight"])
        yaw = r["frames"][0]["yaw"]
        want, _ = self.S.look_angles(e, r["target"])
        self.assertAlmostEqual(yaw, want, 0)

    def test_ground_spot_avoids_the_wall_shadow(self):
        plan = {"id": "g", "target": {"at": [100, 100], "above": -40},
                "pose": {"kind": "ground", "near": [20, 100], "radius": 40, "dist": [40, 120]}}
        r = self.S.resolve(self.h, plan, {})
        self.assertTrue(r["ok"], r["notes"])
        e = r["frames"][0]["eye"]
        self.assertGreater(e[0], 41, "за стеной цели не видно: место — по её сторону от цели")
        self.assertLess(e[1], 70)

    def test_peak_is_hilltop_with_view(self):
        plan = {"id": "p", "target": [100, 100], "pose": {"kind": "peak", "at": [-60, 160], "radius": 20}}
        r = self.S.resolve(self.h, plan, {})
        self.assertTrue(r["ok"], r["notes"])
        e = r["frames"][0]["eye"]
        self.assertGreater(e[1], 148)
        self.assertLessEqual(math.hypot(e[0] + 60, e[2] - 160), 4)

    def test_street_runs_toward_target(self):
        plan = {"id": "s", "target": [120, 300], "frames": 4, "dur": 3,
                "pose": {"kind": "street", "near": [0, 290], "above": 4}}
        r = self.S.resolve(self.h, plan, {})
        self.assertTrue(r["ok"], r["notes"])
        xs = [f["eye"][0] for f in r["frames"]]
        self.assertEqual(xs, sorted(xs), "камера идёт к цели (на восток)")
        self.assertTrue(all(abs(f["eye"][2] - 300.5) < 1 for f in r["frames"]))
        self.assertTrue(all(abs(f["eye"][1] - 68) < 1 for f in r["frames"]))
        self.assertEqual([f["t"] for f in r["frames"]], [0, 1, 2, 3])

    def test_path_looks_ahead_without_target(self):
        plan = {"id": "w", "frames": 3, "pose": {"kind": "path", "points": [[-90, 70, -90], [-90, 70, 90]]}}
        r = self.S.resolve(self.h, plan, {})
        self.assertTrue(r["ok"])
        self.assertAlmostEqual(r["frames"][0]["yaw"], 0, 0)
        self.assertIsNone(r["sight"])

    def test_plan_cap_limits_the_previs_box(self):
        plan = {"id": "c", "target": [100, 100], "pose": {"kind": "peak", "at": [-60, 160], "radius": 20}}
        r = self.S.resolve(self.h, plan, {})
        _, _, wide = self.S.view_box(self.h, r, plan)
        lo, hi, near = self.S.view_box(self.h, r, dict(plan, cap=300 * 300))
        self.assertLess(near, wide)
        self.assertLessEqual((hi[0] - lo[0]) * (hi[2] - lo[2]), 300 * 300)

    def test_fixed_closed_target_is_reported(self):
        plan = {"id": "f", "target": [80, 100], "pose": {"kind": "fixed", "eye": [0, 70, 100]}}
        r = self.S.resolve(self.h, plan, {})
        self.assertFalse(r["ok"])
        self.assertIn("цель закрыта", r["notes"])

    def test_trees_block_sight_and_spots(self):
        top, ground, streets = _map()
        canopy = top.copy()
        canopy[100 + 100 - 3:100 + 100 + 3, 100 + 20 - 3:100 + 20 + 3] = 75   # крона над (20, 100)
        h = self.S.Heights(top, ground, -100, -100, streets, canopy=canopy)
        self.assertTrue(self.h.sees([10, 66, 100], [30, 66, 100]))
        self.assertFalse(h.sees([10, 66, 100], [30, 66, 100]), "листва закрывает взгляд")
        plan = {"id": "g", "target": [100, 100], "pose": {"kind": "ground", "near": [20, 100], "radius": 6, "dist": [60, 90]}}
        r = self.S.resolve(h, plan, {})
        self.assertFalse(r["ok"], "под кроной камере не место")

    def test_eye_inside_ground_is_lifted(self):
        try:
            import scipy  # noqa: F401
            sys.path.insert(0, self.S.SHOT)
            from shotlib import world as W
        except ImportError:
            self.skipTest("нужны scipy и shotlib")
        res = {"id": "x", "title": "", "time": "day", "camera": "fixed", "distance": None, "target": None, "notes": "",
               "frames": [{"t": 0, "eye": [0.5, 60.7, 0.5], "yaw": 0, "pitch": 0, "fov": 50}]}
        lo, hi = np.array([-200, -64, -200]), np.array([200, 200, 200])
        cache = {"box": (lo - 1000, hi + 1000), "world": W.empty(lo, tuple(hi - lo), ground_y=64)}
        with tempfile.TemporaryDirectory() as d:
            self.S.previs(res, {}, self.h, {}, None, d, (64, 36), cache)
        self.assertGreaterEqual(res["frames"][0]["eye"][1], 63.7)
        self.assertIn("поднят", res["notes"])

    def test_main_writes_poses_report_and_frames(self):
        try:
            import PIL  # noqa: F401
            import scipy  # noqa: F401
        except ImportError:
            self.skipTest("нужны pillow и scipy")
        top, ground, streets = _map()
        with tempfile.TemporaryDirectory() as d:
            study = os.path.join(d, "study")
            os.makedirs(study)
            np.save(os.path.join(study, "top.npy"), top)
            np.save(os.path.join(study, "ground.npy"), ground)
            with open(os.path.join(study, "mapstudy.json"), "w") as f:
                json.dump({"box": [-100, -100, 300, 300], "streets": streets, "tall": []}, f)
            scen = os.path.join(d, "scen.json")
            with open(scen, "w") as f:
                json.dump({"places": {"tower": {"at": [100, 100], "above": 0}}, "plans": [
                    {"id": "a1", "title": "roof", "time": "night", "target": "tower", "key": 1.2,
                     "pose": {"kind": "fixed", "eye": [0, 140, 0]},
                     "strikes": [{"from": [-100, 0], "eta": 1.0, "weapon": "missile"}]},
                    {"id": "a2", "target": [0, 0], "pose": {"kind": "fixed", "eye": [0, 70, 100]}, "need_sight": True,
                     "frames": 1}]}, f)
            out = os.path.join(d, "out")
            p = subprocess.run([sys.executable, TOOL, scen, study, out, "--previs", "--size", "96x40"],
                               capture_output=True, text=True)
            self.assertEqual(p.returncode, 0, p.stderr)
            poses = json.load(open(os.path.join(out, "poses.json"), encoding="utf-8"))
            self.assertEqual([r["id"] for r in poses], ["a1", "a2"])
            self.assertEqual(poses[0]["previs"], ["a1-0.jpg"])
            self.assertTrue(os.path.exists(os.path.join(out, "a1-0.jpg")))
            self.assertTrue(os.path.exists(os.path.join(out, "sheet-1.jpg")))
            md = open(os.path.join(out, "scout.md"), encoding="utf-8").read()
            self.assertIn("| a1 roof | night |", md)


if __name__ == "__main__":
    unittest.main()
