"""Отбор моментов (moments.py) на выдуманном разборе повтора: таблицы replayscan пишутся здесь же, база — replaydb.build.

  uv run --with 'duckdb>=1.1,<2' python -m unittest discover -s tools/clips/tests
"""
import argparse
import csv
import gzip
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parent.parent / "replayscan"))
import replaydb  # noqa: E402
import moments as clips  # noqa: E402
import plan as film_plan  # noqa: E402

DIM = "minecraft:overworld"
EYE = (0.5, 71.62, 0.5)  # глаз записавшего; смотрит на юг (yaw 0), вдоль +z


class Scan:
    """Таблицы разбора, как их пишет мод replayscan (README: Таблицы)."""

    def __init__(self):
        self.rows = {name: [] for name in replaydb.TABLES}
        self.eid = 0
        self.e = 0

    def entity(self, type_, uuid, first, last, path, launcher_near=False, player=False, name=None):
        eid = self.eid
        self.eid += 1
        p0, p1 = path(first), path(last)
        self.rows["entities"].append([eid, 1000 + eid, uuid, type_, name, 0 if player else 1, 1 if player else 0, DIM, first, last,
                                      *p0, *p1])
        prev = None
        for t in range(first, last + 1):
            p = path(t)
            q = path(t + 1) if t < last else p
            v = [q[i] - p[i] for i in range(3)] if t > first or not launcher_near else [0.0, 0.0, 0.0]
            self.rows["tracks"].append([t, eid, *p, 0, 0, *v, 0])
            if t % 2 == 0 and not player:
                self.see("recorder", t, "e", str(eid), p)
            prev = p
        return eid

    def see(self, viewer, t, kind, ref, p, hard=-1):
        d = [p[i] - EYE[i] for i in range(3)]
        dist = math.sqrt(sum(x * x for x in d))
        # yaw 0, pitch 0: взгляд +z, вправо −x, вверх +y
        z = d[2]
        if z <= 0:
            return
        self.rows["sight"].append([t, viewer, kind, ref, dist, z, -d[0] / z, d[1] / z, hard, 0, -1])

    def blast(self, t, src, p, uuid=None, clear=0, solid=0.5, kind=0):
        e = self.e
        self.e += 1
        data = json.dumps({"kind": kind, "pos": list(p), "projectile": uuid})
        self.rows["events"].append([e, t, DIM, "payload", src, *p, None, uuid, clear, solid, 1, data])
        self.see("recorder", t, "p", str(e), p)
        return e

    def write(self, out: Path, ticks: int):
        for t in range(ticks):
            self.rows["viewers"].append([t, "recorder", DIM, *EYE, 0.0, 0.0])
        out.mkdir(parents=True, exist_ok=True)
        for name, columns in replaydb.TABLES.items():
            header = [c.split()[0] for c in columns.split(", ")]
            with gzip.open(out / f"{name}.csv.gz", "wt", newline="") as f:
                w = csv.writer(f)
                w.writerow(header)
                for r in self.rows[name]:
                    w.writerow(["" if v is None else v for v in r])
        (out / "meta.json").write_text(json.dumps({"replay": str(out / "test.zip"), "complete": True, "every": 2, "rows": {}}))


def line(a, b, t0, t1):
    return lambda t: tuple(a[i] + (b[i] - a[i]) * min(1, max(0, (t - t0) / (t1 - t0))) for i in range(3))


def chain(p, q, t_mid):
    return lambda t: p(t) if t <= t_mid else q(t)


def build() -> Path:
    s = Scan()
    # «Град»: пусковая и четыре ракеты с неё (пуск почти без скорости), удары по земле в 200 блоках
    s.entity("airstrike:launcher", "L-1", 0, 400, lambda t: (0.5, 64.0, 100.5))
    for k in range(4):
        uid = f"R-{k}"
        a, b = (0.5, 65.0, 100.5), (20.0 + 9 * k, 64.5, 200.0)
        s.entity("airstrike:rocket", uid, 100 + 2 * k, 300 + 2 * k, line(a, b, 100 + 2 * k, 300 + 2 * k), launcher_near=True)
        s.blast(300 + 2 * k, "airstrike:blast", b, uid, kind=3)
    # шахеды: летят на игрока с юга и проходят над ним (в кадре 10–60 блоков 2 с), потом бьют по земле в 120 блоках
    for k in range(3):
        uid = f"D-{k}"
        a, m, b = (0.5 + 10 * k, 85.0, 90.0), (0.5 + 10 * k, 85.0, -20.0), (30.0 + 10 * k, 64.5, 120.0)
        s.entity("airstrike:drone", uid, 380 + k, 520 + k, chain(line(a, m, 380 + k, 450 + k), line(m, b, 450 + k, 520 + k), 450 + k))
        s.blast(520 + k, "airstrike:blast", b, uid, kind=0)
    # ракета, взорвавшаяся в воздухе (до земли 40 блоков)
    s.entity("airstrike:cruise_missile", "M-air", 600, 700, line((0.5, 120.0, 0.5), (10.0, 110.0, 150.0), 600, 700))
    s.blast(700, "airstrike:blast", (10.0, 110.0, 150.0), "M-air", clear=40, solid=7, kind=1)
    # далёкий удар: путь только дальними записями, в 800 блоках, чанк неизвестен
    for t in range(900, 1000, 4):
        p = (0.5, 90.0, 300.0 + (t - 900) * 5)
        s.rows["far"].append([t, DIM, "airstrike:far_flights", "M-far", *p, json.dumps({"weapon": 1, "bomber": False})])
    s.blast(1000, "airstrike:blast", (0.5, 64.0, 800.0), "M-far", clear=-1, solid=-1, kind=1)
    # хаос: восемь ударов шахедов за 8 с в круге 40 блоков
    for k in range(8):
        uid = f"C-{k}"
        b = (40.0 + 5 * k, 64.5, 150.0 + 3 * k)
        s.entity("airstrike:drone", uid, 1100 + 10 * k, 1200 + 20 * k, line((-300.0, 90.0, 150.0), b, 1100 + 10 * k, 1200 + 20 * k))
        s.blast(1200 + 20 * k, "airstrike:blast", b, uid, kind=0)
    # ядерный подрыв
    s.blast(2000, "airstrike:nuke_detonation", (0.5, 64.0, 1200.0), None, clear=-1, solid=-1)
    tmp = Path(tempfile.mkdtemp(prefix="clips-test-"))
    s.write(tmp / "scan", 2100)
    args = argparse.Namespace(dir=str(tmp / "scan"), db=None, fov=70, aspect=9 / 16, center=0.7)
    return replaydb.build(args)


class SelectTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.db = build()
        cls.args = argparse.Namespace(fov=70, aspect=9 / 16, center=0.7)
        cls.r = clips.Replay(cls.db, cls.args)

    def test_flights(self):
        by = self.r.by_uuid
        self.assertTrue(by["R-0"].launched, "ракета с пусковой — пуск")
        self.assertFalse(by["D-0"].launched, "шахед вошёл в мир клиента в полёте")
        self.assertTrue(by["R-1"].ground)
        self.assertEqual(by["M-air"].place, "air")
        self.assertFalse(by["M-air"].ground)
        self.assertEqual(by["M-far"].weapon, "missile", "оружие дальнего снаряда — по номеру в пакете")
        self.assertEqual(by["M-far"].first, 900)
        self.assertEqual(by["M-far"].place, "unknown")

    def test_salvos(self):
        grad = [g for g in self.r.salvos if g[0].weapon == "grad"]
        self.assertEqual(len(grad), 1)
        self.assertEqual(len(grad[0]), 4)

    def test_rockets(self):
        c = self.r.rockets()
        self.assertEqual(len(c), 1)
        c = c[0]
        self.assertEqual(c.numbers["ground"], 4)
        self.assertEqual([s.kind for s in c.shots], ["launch", "impact"])
        launch, impact = c.shots
        self.assertEqual(launch.camera["kind"], "pov", "пуск в 100 блоках прямо перед записавшим")
        self.assertEqual(impact.camera["kind"], "pov")
        self.assertEqual(impact.subject["keys"][0]["t"], 300)

    def test_drones(self):
        c = self.r.drones()
        self.assertEqual(len(c), 1)
        self.assertEqual([s.kind for s in c[0].shots], ["flight", "impact"])
        self.assertEqual(c[0].shots[0].camera, {"kind": "pov", "viewer": "recorder"})

    def test_air_burst_never_selected(self):
        for kind in ("rockets", "drones", "chaos", "far"):
            for c in getattr(self.r, kind)():
                for s in c.shots:
                    self.assertNotIn("M-air", json.dumps(s.json()))

    def test_chaos(self):
        c = [c for c in self.r.chaos() if c.weapon != "pov"]
        self.assertTrue(c)
        self.assertGreaterEqual(c[0].numbers["ground"], 8)
        self.assertEqual(c[0].shots[0].camera["rig"], "wide")

    def test_far(self):
        c = self.r.far()
        far = [x for x in c if x.numbers["dist"] > 700]
        self.assertEqual(len(far), 1)
        f = far[0]
        self.assertEqual(f.weapon, "missile")
        self.assertTrue(f.shots[0].subject["ground_check"], "чанк удара неизвестен — землю проверяет вывод")
        self.assertGreater(f.numbers["far_rows"], 20)
        self.assertLessEqual(f.shots[0].camera["fov"], 20)

    def test_nuke(self):
        c = self.r.nuke()
        self.assertEqual(len(c), 1)
        self.assertEqual(c[0].t, 2000)
        self.assertEqual(c[0].shots[-1].kind, "nuke")


class PlanTest(unittest.TestCase):
    """План съёмки из кандидатов: пути из базы, события, камеры и пороги по роду плана."""

    @classmethod
    def setUpClass(cls):
        db = build()
        cls.out = Path(tempfile.mkdtemp(prefix="clips-plan-"))
        clips.select(argparse.Namespace(db=[str(db)], out=str(cls.out), top=8, fov=70, aspect=9 / 16, center=0.7))
        argv = sys.argv
        sys.argv = ["plan.py", str(cls.out / "candidates.json"), "--top", "1"]
        try:
            film_plan.main()
        finally:
            sys.argv = argv
        runs = json.loads((cls.out / "film" / "runs.json").read_text())
        cls.plan = json.loads(Path(runs[0]["plan"]).read_text())
        cls.shots = {s["name"]: s for s in cls.plan["shots"]}

    def test_every_preview_planned(self):
        self.assertEqual(sorted(self.plan["previews"]), ["chaos-0", "drones-0", "far-0", "nuke-0", "rockets-0"])
        for names in self.plan["previews"].values():
            for n in names:
                self.assertIn(n, self.shots)

    def test_impact_has_path_and_key(self):
        s = self.shots["rockets-0-impact"]
        self.assertGreater(len(s["subject"]["path"]), 5, "путь ракеты из tracks")
        self.assertEqual(len(s["subject"]["keys"]), 1)
        self.assertLessEqual(s["subject"]["path"][0][0], s["subject"]["keys"][0]["t"])
        self.assertIn(s["cameras"][-1]["kind"], ("tripod", "pov"))
        self.assertEqual(s["need"]["keys"], 1)

    def test_far_uses_lens_and_far_rows(self):
        s = self.shots["far-0-far"]
        self.assertTrue(s["far"])
        self.assertEqual(s["cameras"][0]["kind"], "fixed")
        self.assertEqual(s["cameras"][0]["aim"], "subject")
        self.assertGreater(len(s["subject"]["path"]), 5, "путь только дальними строками")

    def test_chaos_frames_area(self):
        s = self.shots["chaos-0-chaos"]
        cam = s["cameras"][0]
        self.assertEqual(cam["kind"], "tripod")
        self.assertFalse(cam["follow"])
        self.assertGreaterEqual(len(s["subject"]["keys"]), 4)
        self.assertIn("area", s["subject"])


if __name__ == "__main__":
    unittest.main()
