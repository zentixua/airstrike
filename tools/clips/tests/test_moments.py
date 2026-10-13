"""Отбор моментов (moments.py) на выдуманном разборе повтора: таблицы replayscan пишутся здесь же, база — replaydb.build.

  uv run --with 'duckdb>=1.1,<2' python -m unittest discover -s tools/clips/tests
"""
import argparse
import contextlib
import csv
import gzip
import io
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
        (out / "meta.json").write_text(json.dumps({"replay": str(out / "test.zip"), "complete": True, "every": 2, "rows": {},
                                                       "total_ticks": ticks}))


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
    # шахед того же залпа сбит зенитной ракетой в 60 блоках перед записавшим
    s.entity("airstrike:drone", "D-down", 383, 470, line((0.5, 85.0, 150.0), (0.5, 85.0, 60.0), 383, 470))
    s.blast(470, "airstrike:intercept", (0.5, 85.0, 60.0), "D-down", clear=-1, solid=-1)
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
    # игроки рядом: живые (аккаунт Mojang — UUID версии 4; без входа Mojang — UUID ванили по нику) и бот Almighty (свой UUID
    # по имени — тоже версии 3)
    s.entity("minecraft:player", "5a1f0c3e-2b7d-4c1e-9f00-1d2e3f405060", 0, 10, lambda t: (5.5, 71.0, 0.5), player=True, name="Friend")
    s.entity("minecraft:player", clips.offline_uuid("Guest"), 0, 10, lambda t: (6.5, 71.0, 0.5), player=True, name="Guest")
    s.entity("minecraft:player", "7f1c2e3d-4b5a-3c6d-8e7f-9a0b1c2d3e4f", 0, 10, lambda t: (8.5, 71.0, 0.5), player=True, name="Sever_1")
    tmp = Path(tempfile.mkdtemp(prefix="clips-test-"))
    s.write(tmp / "scan", 2100)
    args = argparse.Namespace(dir=str(tmp / "scan"), db=None, fov=70, aspect=9 / 16, center=0.7, soft=2)
    return replaydb.build(args)


class SelectTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.db = build()
        cls.args = argparse.Namespace(fov=70, aspect=9 / 16, center=0.7, soft=2)
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
        # превью не короче 20 с: недостающее — плану удара, до удара — не раньше конца пуска
        self.assertGreaterEqual(sum(s.b - s.a for s in c.shots), clips.LEAST * clips.TICK)
        self.assertGreaterEqual(impact.a, launch.b)
        self.assertLess(impact.a, 300 - 60, "удар — и с подлёта")

    def test_drones(self):
        c = self.r.drones()
        self.assertEqual(len(c), 1)
        self.assertEqual([(s.name, s.kind) for s in c[0].shots], [("flyby+shootdown+impact", "shootdown")],
                         "пролёт, сбитие и удар глазами записавшего — один план, без повтора одних секунд")
        down = c[0].shots[0]
        self.assertEqual(down.camera, {"kind": "pov", "viewer": "recorder"})
        self.assertLessEqual(down.a, 470 - 120)
        self.assertGreaterEqual(down.b, 520 + 100, "до конца удара")
        self.assertEqual(down.subject["uuid"], "D-down")
        self.assertIn({"t": 470, "pos": [0.5, 85.0, 60.0], "size": 8}, down.subject["keys"])
        self.assertEqual([k["t"] for k in down.subject["keys"]][-2:], [470, 520])
        self.assertEqual(c[0].numbers["shot_down"], 1)
        alone = [clips.Shot("shootdown", "shootdown", 1000, 1180, {}, {})]
        clips.fill(alone)
        self.assertEqual((alone[0].a, alone[0].b), (1000 - 88, 1180 + 132), "сбитие одно — и до него, и после")
        self.assertEqual(c[0].shots[0].camera, {"kind": "pov", "viewer": "recorder"})
        # пролёт — пока шахед в кадре, не по ближайшей точке за всю его жизнь
        fl = self.r.flyby_shot("flyby", self.r.by_uuid["D-0"], next(r for r in self.r.views["flyby"] if r[1] == "D-0"))
        self.assertLess(fl.a, fl.b)
        self.assertLessEqual(fl.b - fl.a, 200)
        self.assertGreaterEqual(sum(s.b - s.a for s in c[0].shots), clips.LEAST * clips.TICK, "превью не короче 20 с")
        eye, other = {"kind": "pov", "viewer": "v"}, {"kind": "pov", "viewer": "w"}
        three = clips.distinct([clips.Shot("flyby", "flight", 100, 300, eye, {"uuid": "a"}),
                                clips.Shot("impact", "impact", 220, 380, eye, {"uuid": "b", "keys": [{"t": 280}]}),
                                clips.Shot("shootdown", "shootdown", 220, 400, eye, {"uuid": "c", "keys": [{"t": 340}]})])
        self.assertEqual([(s.name, s.kind, s.a, s.b, s.subject["uuid"], [k["t"] for k in s.subject["keys"]]) for s in three],
                         [("flyby+impact+shootdown", "shootdown", 100, 400, "c", [280, 340])], "одни глаза — один план, события все")
        apart = clips.distinct([clips.Shot("flyby", "flight", 100, 300, eye, {}), clips.Shot("shootdown", "shootdown", 300, 470, eye, {}),
                                clips.Shot("impact", "impact", 350, 500, other, {})])
        self.assertEqual([s.name for s in apart], ["flyby", "shootdown", "impact"], "встык и чужими глазами — отдельные планы")

    def test_shot_down_star(self):
        f, t, pos, view = self.r.downs[0]
        self.assertEqual((f.uuid, t, view[0]), ("D-down", 470, "recorder"))
        self.assertGreaterEqual(view[1], 2 * clips.TICK)
        plain = self.r.drones()[0].score
        star = clips.Replay(self.db, argparse.Namespace(**vars(self.args), star="recorder"))
        self.assertAlmostEqual(star.drones()[0].score, plain + 4, msg="сбитие у зрителя --star — первым")
        star.con.close()

    def test_fill_without_impact(self):
        shots = [clips.Shot("flight", "flight", 100, 200, {}, {}), clips.Shot("chaos", "chaos", 300, 350, {}, {})]
        clips.fill(shots)
        self.assertEqual([(s.a, s.b) for s in shots], [(100, 200), (300, 350 + round(clips.LEAST * clips.TICK) - 150)],
                         "без удара — концу последнего")
        first = [clips.Shot("impact", "impact", 10, 170, {}, {})]
        clips.fill(first)
        self.assertEqual(first[0].a, 0, "не раньше начала повтора")
        self.assertEqual(clips.fill([]), [])
        long = [clips.Shot("chaos", "chaos", 0, 500, {}, {})]
        clips.fill(long)
        self.assertEqual((long[0].a, long[0].b), (0, 500), "длинный не трогается")

    def test_air_burst_never_selected(self):
        for kind in ("rockets", "drones", "chaos", "far"):
            for c in getattr(self.r, kind)():
                for s in c.shots:
                    self.assertNotIn("M-air", json.dumps(s.json()))

    def test_chaos(self):
        c = [c for c in self.r.chaos() if c.weapon != "pov"]
        self.assertTrue(c)
        self.assertEqual(max(x.numbers["ground"] for x in c), 8)
        self.assertEqual(c[0].shots[0].camera["rig"], "wide")
        self.assertIn(60, c[0].shots[0].camera["elev"], "и сверху: удары в улицах")
        self.assertEqual(c[0].numbers["seconds"], 20)
        pov = [c for c in self.r.chaos() if c.weapon == "pov"]
        self.assertTrue(pov)
        keys = pov[0].shots[0].subject["keys"]
        self.assertGreaterEqual(len(keys), 4, "удары в кадре — события плана")
        self.assertEqual(len(keys[0]["pos"]), 3)

    def test_pov(self):
        c = self.r.pov()
        self.assertTrue(c)
        self.assertTrue(all(len(x.shots) == 1 and x.shots[0].camera == {"kind": "pov", "viewer": "recorder"} for x in c),
                        "только глазами игрока, одним планом")
        for x in c:
            s = x.shots[0]
            self.assertEqual(s.kind, "pov")
            self.assertGreaterEqual(s.b - s.a, 200, "не короче 10 с")
            self.assertLessEqual(s.b - s.a, 300, "не длиннее 15 с")
            self.assertTrue(all(s.a <= k["t"] <= s.b for k in s.subject["keys"]))
        down = next(x for x in c if x.numbers["shot_down"])
        self.assertIn({"t": 470, "pos": [0.5, 85.0, 60.0], "size": 8}, down.shots[0].subject["keys"])
        self.assertEqual(down.shots[0].subject["uuid"], "D-down", "объект — сбитый дрон")
        self.assertEqual(c[0].score, max(x.score for x in c))
        spans = sorted((x.shots[0].a, x.shots[0].b) for x in c)
        self.assertTrue(all(b0 <= a1 for (_, b0), (a1, _) in zip(spans, spans[1:])), "одни секунды — в одном кандидате")
        # после подрыва — 10 с, и когда подрыв не последний и не первый; у начала записи отрезок сдвигается
        ev = lambda t, kind, w: (t, w, kind, "", {"t": t, "pos": [0, 64, 0], "size": 8}, None)  # noqa: E731
        real = self.r.pov_events
        try:
            self.r.pov_events = lambda: {"recorder": [ev(900, "impact", 1), ev(1000, "nuke", 6), ev(1020, "impact", 1)]}
            (s,) = [x.shots[0] for x in self.r.pov()]
            self.assertGreaterEqual(s.b, 1200, "волна и гриб")
            self.assertLessEqual(s.b - s.a, 300)
            self.r.pov_events = lambda: {"recorder": [ev(10, "impact", 1)]}
            (s,) = [x.shots[0] for x in self.r.pov()]
            self.assertEqual((s.a, s.b), (0, 200), "у начала записи — не короче 10 с")
            self.r.pov_events = lambda: {"recorder": [ev(2090, "nuke", 6)]}
            (s,) = [x.shots[0] for x in self.r.pov()]
            self.assertEqual(s.b, 2100, "не дальше конца записи")
        finally:
            self.r.pov_events = real
        self.assertTrue(self.r.human("recorder"))
        self.assertTrue(self.r.human("player:Friend"))
        self.assertTrue(self.r.human("player:Guest"), "без входа Mojang — тоже человек")
        self.assertEqual(clips.offline_uuid("Notch"), "b50ad385-829d-3141-a216-7e7d7539ba7f", "как у ванили")
        self.assertFalse(self.r.human("player:Sever_1"), "бот")
        self.assertFalse(self.r.human("ask:0"))

    def test_pov_marked(self):
        """Отмеченные записавшим моменты: маркеры Flashback и время по часам — только они, глазами записавшего."""
        r = self.r
        meta, name, at = dict(r.meta), r.name, r.at
        try:
            r.meta["replay_meta"] = {"markers": {"1500": {"colour": 11141290, "description": "Changed Dimension"}}}
            self.assertTrue(all("mark" not in x.numbers for x in r.pov()), "смена измерения — маркер самого Flashback, не отметка")
            r.meta["replay_meta"] = {"markers": {"500": {"colour": 16777215}, "2060": {"colour": 16711680},
                                                 "1500": {"colour": 11141290, "description": "Changed Dimension"}}}
            c = r.pov()
            self.assertEqual([(x.shots[0].a, x.shots[0].b) for x in c], [(200, 540), (1760, 2100)],
                             "маркер — сразу после момента, 15 с до него и 2 с после; не дальше конца записи")
            self.assertTrue(all(x.shots[0].camera == {"kind": "pov", "viewer": "recorder"} for x in c))
            self.assertGreater(c[0].score, c[1].score, "по порядку записи")
            self.assertEqual(c[0].numbers["mark"], "маркер 00:25.0")
            self.assertEqual(c[0].numbers["shot_down"], 1, "события отрезка — для подписи и проверки кадров")
            self.assertIn({"t": 470, "pos": [0.5, 85.0, 60.0], "size": 8}, c[0].shots[0].subject["keys"])
            self.assertEqual(c[1].numbers["nuke"], 1)
            r.MARK, r.AFTER = {**r.MARK, "marker": (0, 10)}, {"nuke": 60}
            r.meta["replay_meta"] = {"markers": {"700": {"colour": 16711680}, "2005": {"colour": 16711680}}}
            self.assertEqual([x[:2] for x in r.marks()], [(700, 710), (1940, 2060)],
                             "ядерный подрыв за минуту до маркера — отрезок со вспышки и не короче хвоста подрыва")
            del r.MARK, r.AFTER
            # время по часам: запись сохранена в 19:10:00 (2100 тиков — с 19:08:15)
            r.meta.pop("replay_meta")
            r.name, r.at = "2026-10-14T19_10_00", ["2026-10-14T19:09:00", "2026-10-14T19:08:20/2026-10-14T19:08:30", "2026-10-14T20:00:00"]
            c = r.pov()
            self.assertEqual([(x.shots[0].a, x.shots[0].b) for x in c], [(100, 300), (700, 1000)],
                             "момент — 10 с до и 5 с после, с/по — как есть; время вне записи — не её")
            r.name = "test"
            self.assertTrue(all(x.numbers.get("mark") is None for x in r.pov()), "без времени в имени — без отметок по часам")
            # время по шкале Flashback — без часов записи; ядерный подрыв в отрезке момента — и 10 с после вспышки
            r.at = ["00:30", "01:35/01:40", "01:39"]
            self.assertEqual([x[:2] for x in r.marks()], [(400, 700), (1780, 2100), (1900, 2000)])
        finally:
            r.meta, r.name, r.at = meta, name, at
            r.__dict__.pop("MARK", None)
            r.__dict__.pop("AFTER", None)

    def test_timeline(self):
        self.assertEqual(clips.timeline("25:02"), 30040)
        self.assertEqual(clips.timeline("1:02:03.5"), (3723.5) * 20)
        self.assertIsNone(clips.timeline("2026-10-14T19:05:30"))
        self.assertIsNone(clips.timeline("25"))

    def test_nuke_out_of_view(self):
        """Подрыв в отрезке, которого зритель не видел (в метро): в числах — как далеко."""
        c = self.r.pov_candidate("recorder", 1900, 2100, [], 1)
        self.assertEqual(c.numbers["nuke_off"], 1200)
        self.assertIn("ядерный подрыв в 1200 блоках вне кадра", c.summary)
        self.assertIn("nuke", [e[2] for e in self.r.pov_events()["recorder"]], "в кадре, луч чист — видел")
        # лицом к подрыву, но за блоками (в метро): не видел
        self.r.con.execute("UPDATE sight SET hard = 3 WHERE kind = 'p' AND ref IN (SELECT CAST(u.e AS VARCHAR) FROM impacts i, "
                           "unnest(i.events) AS u(e) WHERE i.srcs LIKE '%nuke_detonation%')")
        try:
            self.assertNotIn("nuke", [e[2] for e in self.r.pov_events()["recorder"]])
            # дом далеко на луче к земле под подрывом — шар над крышами виден
            self.r.con.execute("UPDATE sight SET hard = 300 WHERE kind = 'p' AND hard = 3")
            self.assertIn("nuke", [e[2] for e in self.r.pov_events()["recorder"]])
        finally:
            self.r.con.execute("UPDATE sight SET hard = -1 WHERE kind = 'p' AND hard IN (3, 300)")

    def test_timeline_needs_one_recording(self):
        argv = sys.argv
        sys.argv = ["moments.py", "a.duckdb", "b.duckdb", "--at", "25:02"]
        try:
            with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
                clips.main()
        finally:
            sys.argv = argv

    def test_flight_aims_at_focus(self):
        """Взгляд пролёта: пять ударов чуть восточнее юга, два — к юго-западу в окне focus."""
        eye = lambda t: (0.0, 100.0, 0.0)  # noqa: E731

        def hit(t, heading):
            h = math.radians(heading)
            p = (-math.sin(h) * 200, 64.0, math.cos(h) * 200)
            return (t, t, p, "unknown", *clips.bearing(eye(t), p))
        rel = [hit(100 + 10 * k, -10) for k in range(5)] + [hit(200, 30), hit(210, 32)]
        tan_y = math.tan(math.radians(35))
        yaw, pitch = clips.aim_flight(eye, rel, 0, 40, tan_y * 9 / 16, tan_y)
        self.assertEqual(yaw, 0, "без окна — где ударов больше, ближе к look")
        self.assertAlmostEqual(pitch, math.degrees(math.atan2(36, 200)), places=6)
        yaw, _ = clips.aim_flight(eye, rel, 0, 40, tan_y * 9 / 16, tan_y, (200, 210))
        self.assertTrue(13 <= yaw <= 15, f"окно focus в кадре, ближе к look: {yaw}")
        self.assertTrue(all(clips.in_view(eye(r[1]), yaw, pitch, tan_y * 9 / 16, tan_y, r[2]) for r in rel[5:]))
        self.assertEqual(clips.fly_spec("at=00:55/01:10 from=0,100,0 to=-50,100,0 focus=01:00/01:02")["focus"], (1200, 1240))

    def test_fly(self):
        """Пролёт: камера по прямой, взгляд — к ударам окна (хаос: восемь ударов к юго-востоку), удары в кадре — события."""
        with self.assertRaises(argparse.ArgumentTypeError):
            clips.fly_spec("at=00:55/01:10 to=-100,100,0")
        with self.assertRaises(argparse.ArgumentTypeError):
            clips.fly_spec("at=00:55/01:10 from=0,100,0 to=-100,100,0 speed=3")
        spec = clips.fly_spec("at=00:55/01:10 from=0,100,0 to=-50,100,0 to_at=01:02.5 look=south turn=25")
        self.assertEqual((spec["a"], spec["b"], spec["to_at"], spec["look"]), (1100, 1400, 1250, 0.0))
        r = self.r
        r.fly_specs = [spec]
        try:
            c = r.fly()
        finally:
            r.fly_specs = []
        self.assertEqual(len(c), 1)
        shot = c[0].shots[0]
        self.assertEqual((shot.kind, shot.a, shot.b), ("fly", 1100, 1400))
        keys = shot.camera["keys"]
        self.assertEqual([k["eye"] for k in keys], [[0, 100, 0], [-100, 100, 0]], "через to в to_at и дальше той же прямой")
        yaw, pitch = c[0].numbers["yaw"], c[0].numbers["pitch"]
        self.assertTrue(-25 <= yaw < 0, "удары к востоку от юга: курс к востоку, не дальше turn")
        self.assertTrue(2 <= pitch <= 30)
        d = [keys[0]["aim"][i] - keys[0]["eye"][i] for i in range(3)]
        self.assertAlmostEqual(math.hypot(*d), 100, delta=0.1)
        self.assertEqual(keys[1]["aim"][0] - keys[1]["eye"][0], keys[0]["aim"][0] - keys[0]["eye"][0], "взгляд постоянный")
        self.assertGreaterEqual(c[0].numbers["impacts"], 6, "почти весь хаос в кадре")
        self.assertEqual(len(shot.subject["keys"]), c[0].numbers["impacts"])
        self.assertGreater(c[0].numbers["flights"], 0, "шахеды хаоса в кадре")
        self.assertEqual(c[0].weapon, "shahed")

    def test_fly_stays_in_recorder_chunks(self):
        """Чанки повтора — только вокруг записавшего: путь, у которого глаз или низ кадра дальше reach, сдвигается (и
        медленнее), пока весь не встанет в них; дальность без reach — по пакету входа записавшего."""
        self.assertEqual(self.r.view_reach(), 128.0, "пакета входа нет — 8 чанков")
        self.r.con.execute("INSERT INTO events (e, t, dim, kind, src, data) VALUES (-1, 0, ?, 'packet', 'ClientboundLoginPacket', "
                           "'{\"playerId\": 1, \"chunkRadius\": 6}')", [DIM])
        try:
            self.assertEqual(self.r.view_reach(), 80.0)
        finally:
            self.r.con.execute("DELETE FROM events WHERE e = -1")
        spec = clips.fly_spec("at=00:55/01:10 from=0,100,0 to=-50,100,0 to_at=01:02.5 reach=60")
        r = self.r
        r.fly_specs = [spec]
        try:
            with contextlib.redirect_stdout(io.StringIO()) as printed:
                c = r.fly()
        finally:
            r.fly_specs = []
        n = c[0].numbers
        self.assertTrue(n["shift"] != [0.0, 0.0] or n["speed"] < 1, n)
        self.assertIn("уходил из чанков записавшего", printed.getvalue())
        keys = c[0].shots[0].camera["keys"]
        half = spec["fov"] / 2
        for k in range(0, 301, 20):
            e = [keys[0]["eye"][i] + (keys[1]["eye"][i] - keys[0]["eye"][i]) * k / 300 for i in range(3)]
            low = clips.low_point(e, n["yaw"], n["pitch"], half, 63)
            for q in (e, low):
                self.assertLessEqual(math.hypot(q[0] - EYE[0], q[2] - EYE[2]), 60.5, f"{k}: {q}")

    def test_far(self):
        c = self.r.far()
        far = [x for x in c if x.numbers["dist"] > 700]
        self.assertEqual(len(far), 1)
        f = far[0]
        self.assertEqual(f.weapon, "missile")
        self.assertTrue(f.shots[0].subject["ground_check"], "чанк удара неизвестен — землю проверяет вывод")
        self.assertGreater(f.numbers["far_rows"], 20)
        self.assertLessEqual(f.shots[0].camera["fov"], 20)
        self.assertEqual(f.shots[0].camera["rise"], [0, 24], "и над крышами")

    def test_nuke(self):
        c = self.r.nuke()
        self.assertEqual(len(c), 1)
        self.assertEqual(c[0].t, 2000)
        self.assertEqual(c[0].shots[-1].kind, "nuke")
        # подрыв в 1200 блоках, за чанками повтора: объектив с места игрока — на 2 блока к подрыву (не в его голове),
        # шар — 35 % ширины кадра: 2·atan(120 / (2 · 1197.5 · 0.35) / 0.5625)
        lens = c[0].shots[-1].camera["lens"]
        self.assertEqual(lens["eye"], [EYE[0], EYE[1], EYE[2] + 2])
        self.assertEqual(lens["aim"], [0.5, 64.0 + 30, 1200.0], "взгляд — над подрывом, неподвижно")
        self.assertAlmostEqual(lens["fov"], 28.6, delta=0.1)
        self.assertLessEqual(sum(x.b - x.a for x in c[0].shots), 35 * clips.TICK, "ролик не длиннее 35 с")

    def test_nuke_lens_by_yield(self):
        """Мощность подрыва в пакете: объектив — по радиусу шара (20 кт в воздухе — 232 блока), шар с подъёмом в кадре."""
        self.r.con.execute("UPDATE events SET data = '{\"detonation\": {\"yieldKt\": 20.0, \"surface\": false, \"scale\": 1.0}}' "
                           "WHERE src = 'airstrike:nuke_detonation'")
        try:
            lens = self.r.nuke()[0].shots[-1].camera["lens"]
        finally:
            self.r.con.execute("UPDATE events SET data = '{\"kind\": 0}' WHERE src = 'airstrike:nuke_detonation'")
        r = 70 * 20 ** 0.4
        d = math.dist((EYE[0], EYE[1], EYE[2] + 2), (0.5, 64.0, 1200.0))
        self.assertAlmostEqual(lens["fov"], round(math.degrees(2 * math.atan(1.3 * r / (d * 0.8 * 9 / 16))), 1), delta=0.05)
        self.assertAlmostEqual(lens["aim"][1], 64.0 + r / 2, delta=0.01)

    def test_far_reel(self):
        def one(t, score):
            shot = clips.Shot("far", "far", t - 120, t + 80, {"kind": "auto", "rig": "lens"}, {"keys": []}, "")
            return clips.Candidate("far", "r", "db", score, t, "missile", f"удар {t}", {"dist": t}, [shot])
        reel = clips.Replay.far_reel([one(5000, 3), one(1000, 2), one(9000, 1), one(3000, 0.5)])
        self.assertEqual([s.name for s in reel[0].shots], ["far1", "far2", "far3"])
        self.assertEqual([s.a for s in reel[0].shots], [880, 4880, 8880], "три лучших — по времени")
        self.assertEqual([x["dist"] for x in reel[0].numbers["strikes"]], [1000, 5000, 9000])
        self.assertEqual(len(reel), 5, "и каждый удар отдельно")
        # удар через 150 тиков после лучшего перекрыл бы его план — не берётся
        reel = clips.Replay.far_reel([one(5000, 3), one(5150, 2.5), one(1000, 2)])
        self.assertEqual([s.a for s in reel[0].shots], [880, 4880])

    def test_notes_mark_candidates_by_game_log(self):
        # подрыв ядерки (тик 2000) — в 23:10:35 по журналу сервера; конец записи (2100) — в 23:10:40
        notes = {"replays": {"test": [{"src": "nuke_detonation", "at": "2026-10-06T23:10:35"},
                                      {"end": True, "at": "2026-10-06T23:10:40"}]},
                 "moments": [{"from": "2026-10-06T23:10:30", "to": "2026-10-06T23:10:38", "what": "ядерка над городом"}]}
        anchors = clips.clock(self.r, notes["replays"]["test"])
        self.assertEqual([t for t, _ in anchors], [2000, 2100])
        at = lambda s: clips.datetime.fromisoformat(s)  # noqa: E731
        self.assertAlmostEqual(clips.tick_at(anchors, at("2026-10-06T23:10:37.5")), 2050)
        self.assertAlmostEqual(clips.tick_at(anchors, at("2026-10-06T23:10:30")), 1900, msg="до опор — 20 тиков в секунду")
        cands = self.r.nuke()
        before = cands[0].score
        clips.annotate(self.r, cands, notes, 1.0)
        self.assertEqual(cands[0].numbers["notes"], ["ядерка над городом"])
        self.assertAlmostEqual(cands[0].score, before + 1)
        self.r.name = "2026-10-06T23_10_40"
        self.assertEqual(clips.clock(self.r, [{"end": True, "at": "name"}])[0][1], at("2026-10-06T23:10:40"))
        self.r.name = "test"
        far = [c for c in self.r.rockets() if c.t < 1900]
        clips.annotate(self.r, far, notes, 1.0)
        self.assertTrue(far and all("notes" not in c.numbers for c in far), "вне отрезка — без строки журнала")


class PlanTest(unittest.TestCase):
    """План съёмки из кандидатов: пути из базы, события, камеры и пороги по роду плана."""

    @classmethod
    def setUpClass(cls):
        db = build()
        cls.out = Path(tempfile.mkdtemp(prefix="clips-plan-"))
        clips.select(argparse.Namespace(db=[str(db)], out=str(cls.out), top=8, fov=70, aspect=9 / 16, center=0.7, soft=2, min_version="2.10.2", prefer="", notes=None, note_bonus=1.0, star="", pov_top=15,
                                        fly=[clips.fly_spec("at=00:55/01:10 from=0,100,0 to=-100,100,0")]))
        argv = sys.argv
        sys.argv = ["plan.py", str(cls.out / "candidates.json"), "--top", "1"]
        try:
            film_plan.main()
        finally:
            sys.argv = argv
        runs = json.loads((cls.out / "film" / "runs.json").read_text())
        cls.plan = json.loads(Path(runs[0]["plan"]).read_text())
        cls.shots = {s["name"]: s for s in cls.plan["shots"]}

    def test_nuke_lens_first(self):
        burst = self.shots["nuke-0-burst"]
        self.assertEqual([c["kind"] for c in burst["cameras"]], ["fixed", "tripod"], "объектив с места игрока, потом штатив")
        self.assertEqual(burst["cameras"][0]["eye"], [EYE[0], EYE[1], EYE[2] + 2])
        self.assertEqual(burst["cameras"][0]["aim"], [0.5, 94.0, 1200.0])
        self.assertEqual(burst["cameras"][0]["rise"], [0, 32, 96], "и над землёй: игрок бывает в метро")

    def test_every_preview_planned(self):
        self.assertEqual(sorted(self.plan["previews"]), ["chaos-0", "drones-0", "far-0", "fly-0", "nuke-0", "pov-0", "rockets-0"])
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
        self.assertEqual(s["cameras"][0]["rise"], [0, 24])
        self.assertGreater(len(s["subject"]["path"]), 5, "путь только дальними строками")

    def test_old_versions_skipped(self):
        self.assertLess(clips.version_key("2.10.0"), clips.version_key("2.10.2"))
        self.assertLess(clips.version_key("2.10.3"), clips.version_key("3.0.0"))
        self.assertEqual(clips.incoming_version({"mods": {"airstrike": "2.5.0"}}), "2.5.0")

    def test_shootdown_planned(self):
        s = self.shots["drones-0-flyby+shootdown+impact"]
        self.assertEqual([c["kind"] for c in s["cameras"]], ["pov", "tripod"], "глазами игрока, запасная — штатив у сбития")
        self.assertEqual(s["need"]["keys"], 1)
        self.assertEqual(s["need"]["loud"], 3, "бой вокруг: громкое за кадром — до трёх на одно в кадре")
        self.assertGreater(len(s["subject"]["path"]), 5)

    def test_pov_planned(self):
        argv = sys.argv
        sys.argv = ["plan.py", str(self.out / "candidates.json"), "--all", "pov", "--out", str(self.out / "pov")]
        try:
            film_plan.main()
        finally:
            sys.argv = argv
        plan = json.loads(Path(json.loads((self.out / "pov" / "runs.json").read_text())[0]["plan"]).read_text())
        pov = json.loads((self.out / "candidates.json").read_text())["pov"]
        self.assertEqual(sorted(plan["previews"]), sorted(c["id"] for c in pov), "--all: все кандидаты «глазами игроков», и только они")
        s = self.shots["pov-0-pov"]
        self.assertEqual([c["kind"] for c in s["cameras"]], ["pov"], "без запасной камеры")
        self.assertEqual(s["need"]["loud"], -1, "громкое за кадром — бой вокруг игрока, не проверяется")
        self.assertEqual(s["need"]["subject"], 0)

        class Con:  # база, где записавший в начале плана — в Незере
            def execute(self, q, args):
                self.q = q
                return self

            def fetchone(self):
                return ("minecraft:the_nether",) if "viewers" in self.q else None
        shot = {"camera": {"kind": "pov", "viewer": "recorder"}, "a": 10, "subject": {"keys": []}}
        self.assertEqual(film_plan.dim_of(Con(), shot), "minecraft:the_nether", "глазами игрока — его измерение, и без событий")

    def test_fly_planned(self):
        s = self.shots["fly-0-fly"]
        self.assertEqual([c["kind"] for c in s["cameras"]], ["path"], "только путь, заданный человеком")
        self.assertEqual(len(s["cameras"][0]["keys"]), 2)
        self.assertEqual(s["cameras"][0]["rise"], [0, 16], "и выше: путь по карте бывает сквозь высотку")
        self.assertEqual(s["cameras"][0]["fov"], 70)
        self.assertTrue(s["far"])
        self.assertEqual(s["dim"], DIM)
        self.assertEqual(s["need"], film_plan.NEED["fly"])
        self.assertTrue(s["subject"]["keys"], "удары в кадре — события плана")

    def test_chaos_frames_area(self):
        s = self.shots["chaos-0-chaos"]
        cam = s["cameras"][0]
        self.assertEqual(cam["kind"], "tripod")
        self.assertFalse(cam["follow"])
        self.assertGreaterEqual(len(s["subject"]["keys"]), 4)
        self.assertIn("area", s["subject"])


if __name__ == "__main__":
    unittest.main()
