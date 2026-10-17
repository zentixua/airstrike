"""Проверка монтажа по данным на придуманных дублях: файлы пишет тест в формате съёмки (Recorder, FrameQa, HudQa,
EventLog, отчёт вывода), игра и карта не нужны.

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import takes as T  # noqa: E402
import verdict as V  # noqa: E402

CAM = (0.0, 80.0, 0.0)
HIT = (0.0, 80.0, 200.0)          # прямо перед камерой (курс 0 — юг), 200 блоков
ARRIVE = 200 / (T.SOUND_SPEED * T.TPS)


def _w(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")


def render_take(root, name="p10", seconds=5.0, impact=2.0, seen=1.0, share=0.05, yaw=None, people=(), sounds=(),
                blast=True, suffix="", qa_extra=None):
    """Дубль вывода из повтора: камера стоит у CAM и смотрит на HIT, снаряд летит к HIT и бьёт в `impact` с.
    yaw(k) — курс кадра k (рывки камеры); people — места людей журнала; sounds — (с, событие, место) сверх взрыва."""
    rec = os.path.join(root, "film")
    d = os.path.join(rec, "render", name, "cam-a")
    a = 1000
    n = int(seconds * T.FPS)
    tl = [{"type": "shot", "shot": name, "speed": 1.0, "hud": False, "lang": "en_us"}]
    qa = []
    for k in range(n):
        t = k / T.FPS
        y = yaw(k) if yaw else 0.0
        tl.append({"type": "frame", "shot": name, "k": k, "t": t * T.TPS, "cam": [*CAM, y, 0.0]})
        after = t >= impact
        qa.append({"t": a + t * T.TPS, "ready": True, "in": True, "x": 0.0, "y": 0.0, "share": share,
                   "seen": seen, "clear": 1.0, "cloud": False, "far": False, "drawn": True, "engine": 0.0 if after else 0.3,
                   "entity": not after, "smoke": 0.0, "dh": 1, "cam": [*CAM, y, 0.0], "fov": 70.0, **(qa_extra or {})})
    sid = 0
    if blast:
        tl.append({"type": "sound", "shot": name, "t": (impact + ARRIVE) * T.TPS, "id": sid, "event": "incoming:blast.far",
                   "file": "x", "pos": list(HIT), "volume": 4.0, "pitch": 1.0, "range": 600.0, "relative": False,
                   "linear": True, "loop": False, "stream": False, "lp": [1, 1]})
        sid += 1
    for t, ev, pos in sounds:
        tl.append({"type": "sound", "shot": name, "t": t * T.TPS, "id": sid, "event": ev, "file": "x", "pos": list(pos),
                   "volume": 1.0, "pitch": 1.0, "range": 16.0, "relative": False, "linear": True, "loop": False,
                   "stream": False, "lp": [1, 1]})
        sid += 1
    _w(os.path.join(d, "timeline.jsonl"), tl)
    _w(os.path.join(d, "qa.jsonl"), qa)
    tick_hit = a + round(impact * T.TPS)
    journal = [{"t": a, "new": 0, "id": "u", "weapon": "MISSILE"}]
    for tick in range(a, tick_hit + 1):
        k = (tick - a) / (tick_hit - a)
        row = {"t": tick, "f": [[0, 0.0, 80.0, 200.0 * k, 1, 1]], "me": list(CAM)}
        if people:
            row["p"] = [[f"bot{i}", *p] for i, p in enumerate(people)]
        journal.append(row)
    for tick in range(tick_hit + 1, a + int(seconds * T.TPS) + 1):
        row = {"t": tick, "f": [], "me": list(CAM)}
        if people:
            row["p"] = [[f"bot{i}", *p] for i, p in enumerate(people)]
        journal.append(row)
    journal.append({"t": tick_hit, "impact": 0, "pos": list(HIT)})
    _w(os.path.join(d, f"{name}.events.jsonl"), journal)
    rep = {"shot": name, "candidate": "cam-a", "a": a, "b": a + int(seconds * T.TPS), "event": tick_hit, "replay": name}
    _w(os.path.join(rec, "render", "report.jsonl" + suffix), [rep])
    return T.load_take(name, d)


def live_take(root, name="p14", seconds=8.0, siren_at=0.0, qa=False):
    """Живой план (папка записи): тревога с `siren_at` с, без покадровой проверки (qa=False)."""
    d = os.path.join(root, "live")
    tl = [{"type": "shot", "shot": name, "speed": 1.0, "hud": False, "lang": "en_us"}]
    for k in range(int(seconds * T.FPS)):
        tl.append({"type": "frame", "shot": name, "k": k, "t": k / T.FPS * T.TPS, "cam": [*CAM, 0.0, 0.0]})
    tl.append({"type": "sound", "shot": name, "t": siren_at * T.TPS, "id": 0, "event": "incoming:siren.alert",
               "file": "x", "pos": [0, 90, 30], "volume": 8.0, "pitch": 1.0, "range": 350.0, "relative": False,
               "linear": True, "loop": True, "stream": False, "lp": [1, 1]})
    _w(os.path.join(d, "timeline.jsonl"), tl)
    if qa:
        rows = [{"t": k / T.FPS * T.TPS, "ready": True, "flights": [], "impacts": [], "cam": [*CAM, 0.0, 0.0], "fov": 70.0}
                for k in range(int(seconds * T.FPS))]
        _w(os.path.join(d, "qa", f"{name}.jsonl"), rows)
    return T.load_take(name, d)


def placed_blast(take, t_at, gain=0.5):
    s = next(s for s in take.sounds if s.kind == "blast")
    return V.Placed(take.name, s.id, s.event, "blast", t_at, t_at + 3, gain, s.t)


class TakesTest(unittest.TestCase):
    def test_projection(self):
        f, r, u = T.look_vectors(0, 0)
        self.assertAlmostEqual(f[2], 1)
        self.assertAlmostEqual(r[0], -1)   # курс 0 — на юг, вправо — запад
        self.assertAlmostEqual(u[1], 1)
        x, y, z = T.project((*CAM, 0, 0), 70, HIT)
        self.assertAlmostEqual(x, 0)
        self.assertAlmostEqual(y, 0)
        self.assertAlmostEqual(z, 200)
        self.assertIsNone(T.project((*CAM, 180, 0), 70, HIT))   # за спиной

    def test_load_render_take(self):
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root)
            self.assertEqual(len(t.frames), 300)
            self.assertAlmostEqual(t.key, 2.0)
            imps = t.impacts()
            self.assertEqual(len(imps), 1)
            i = imps[0]
            self.assertTrue(i.subject)
            self.assertTrue(i.arrival_played)
            self.assertAlmostEqual(i.arrival, 2.0 + ARRIVE, places=2)
            self.assertAlmostEqual(i.distance, 200)

    def test_arrival_from_front_without_sound(self):
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root, blast=False)
            i = t.impacts()[0]
            self.assertFalse(i.arrival_played)
            self.assertAlmostEqual(i.arrival, 2.0 + ARRIVE, delta=1 / T.FPS + 1e-6)

    def test_exam_archive_names(self):
        """Строки плана из архива экзамена — `report.jsonl.<план>` рядом с выводом."""
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root, suffix=".p10")
            self.assertAlmostEqual(t.key, 2.0)


class VerdictTest(unittest.TestCase):
    def check(self, cuts, takes, **kw):
        return V.Checker(cuts, {t.name: t for t in takes}, **kw).run()

    def test_hit_holds_until_sound(self):
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root)
            ok = self.check([V.Cut(0, 3.5, "hit", t.name, 0.0)], [t], placed=[placed_blast(t, 2.0 + ARRIVE)])[0]
            self.assertTrue(ok.ok, ok.findings)
            short = self.check([V.Cut(0, 2.4, "hit", t.name, 0.0)], [t], placed=[placed_blast(t, 2.0 + ARRIVE)])[0]
            self.assertFalse(short.ok)
            self.assertIn("hold", [f.rule for f in short.findings])

    def test_hit_scene_in_two_cuts(self):
        """Сцена удара в двух склейках подряд того же дубля: звук дошёл во второй — держится."""
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root)
            cuts = [V.Cut(0, 2.3, "hit", t.name, 0.0), V.Cut(2.3, 1.3, "scene", t.name, 2.3)]
            out = self.check(cuts, [t], placed=[placed_blast(t, 2.0 + ARRIVE)])
            self.assertTrue(out[0].ok, out[0].findings)

    def test_hit_without_sound_in_mix(self):
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root)
            v = self.check([V.Cut(0, 3.5, "hit", t.name, 0.0)], [t])[0]
            self.assertEqual([f.rule for f in v.findings], ["sound"])

    def test_hit_at_cut_edge(self):
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root)
            v = self.check([V.Cut(0, 2.0, "hit", t.name, 1.8)], [t])[0]   # удар через 0,2 с от начала
            self.assertIn("event", [f.rule for f in v.findings])

    def test_montage_sound_carries(self):
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root)
            cut = V.Cut(10, 0.5, "montage", t.name, 1.8)
            at = cut.at(2.0 + ARRIVE)
            ok = self.check([cut], [t], placed=[placed_blast(t, at)])[0]
            self.assertTrue(ok.ok, ok.findings)
            cut_off = self.check([cut], [t], placed=[placed_blast(t, at)], silences=[10.6])[0]
            self.assertIn("sound", [f.rule for f in cut_off.findings])
            lost = self.check([cut], [t])[0]
            self.assertIn("sound", [f.rule for f in lost.findings])

    def test_montage_without_hit(self):
        """Пик v5: короткая склейка, в которой удара нет."""
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root)
            v = self.check([V.Cut(0, 0.24, "montage", t.name, 3.0)], [t])[0]
            self.assertIn("event", [f.rule for f in v.findings])

    def test_flight_unseen_and_small(self):
        with tempfile.TemporaryDirectory() as root:
            hidden = render_take(root, name="p20", seen=0.2)
            v = self.check([V.Cut(0, 1.5, "flight", "p20", 0.0)], [hidden])[0]
            self.assertIn("visible", [f.rule for f in v.findings])
        with tempfile.TemporaryDirectory() as root:
            tiny = render_take(root, name="p41", share=0.004)
            v = self.check([V.Cut(0, 1.5, "flight", "p41", 0.0)], [tiny])[0]
            self.assertEqual([f.rule for f in v.findings], ["size"])

    def test_camera_jerk(self):
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root, yaw=lambda k: 3.0 * (k % 2))
            v = self.check([V.Cut(0, 1.5, "scene", t.name, 0.0)], [t])[0]
            self.assertIn("camera", [f.rule for f in v.findings])
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root, yaw=lambda k: 20 * math.sin(k / 60))   # плавная панорама
            v = self.check([V.Cut(0, 1.5, "scene", t.name, 0.0)], [t])[0]
            self.assertTrue(v.ok, v.findings)

    def test_fight_needs_gunfire(self):
        crowd = [(dx, 79.0, 40.0) for dx in (-6, -2, 2, 6)]
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root, people=crowd)
            silent = self.check([V.Cut(0, 1.5, "scene", t.name, 0.0, fight=True)], [t])[0]
            self.assertIn("gunfire", [f.rule for f in silent.findings])
            guns = [V.Placed(t.name, 100 + i, "pointblank:ak47", "gun", 0.1 * i, 0.1 * i + 0.3, 0.2, 0.1 * i)
                    for i in range(10)]
            loud = self.check([V.Cut(0, 1.5, "scene", t.name, 0.0, fight=True)], [t], placed=guns)[0]
            self.assertTrue(loud.ok, loud.findings)
        # бой в 200 блоках: выстрелов не слышно и в игре (v5 p31, p32, p39)
        far = [(CAM[0] + dx, 79.0, CAM[2] + 200) for dx in (-6, -2, 2, 6)]
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root, people=far)
            v = self.check([V.Cut(0, 1.5, "scene", t.name, 0.0, fight=True)], [t])[0]
            self.assertTrue(v.ok, v.findings)

    def test_hero_needs_light(self):
        """МБР v5 ночью: в кадре и крупная, но тёмная и в своём дыму — FAIL; без меры света — FAIL по данным."""
        cut = lambda t: [V.Cut(0, 1.5, "flight", t.name, 0.0, need={"hero": True})]
        for extra, rule in ((None, "data"), ({"light": 0.1, "veil": 1.0}, "light"), ({"light": 0.8, "veil": 0.2}, "smoke"),
                            ({"light": 0.8, "veil": 1.0}, None)):
            with tempfile.TemporaryDirectory() as root:
                t = render_take(root, share=0.2, qa_extra=extra)
                v = self.check(cut(t), [t])[0]
                if rule:
                    self.assertIn(rule, [f.rule for f in v.findings], extra)
                else:
                    self.assertTrue(v.ok, v.findings)

    def test_siren_cut_off_and_music(self):
        with tempfile.TemporaryDirectory() as root:
            t = live_take(root)
            siren = V.Placed(t.name, 0, "incoming:siren.alert", "siren", 0, 8, 0.6, 0)
            ok = self.check([V.Cut(0, 6.0, "siren", t.name, 0.0)], [t], placed=[siren])[0]
            self.assertTrue(ok.ok, ok.findings)
            # v5 0:26–0:28: тревога две секунды, музыка сразу
            short = self.check([V.Cut(0, 2.0, "siren", t.name, 0.0)], [t], placed=[siren],
                               music=[V.Music(0.5, 60, 0.8)])[0]
            rules = [f.rule for f in short.findings]
            self.assertIn("hold", rules)
            self.assertIn("music", rules)

    def test_live_without_frame_data(self):
        with tempfile.TemporaryDirectory() as root:
            t = live_take(root)
            v = self.check([V.Cut(0, 3.0, "hit", t.name, 0.0)], [t])[0]
            self.assertIn("data", [f.rule for f in v.findings])

    def test_live_hud_flight(self):
        with tempfile.TemporaryDirectory() as root:
            t = live_take(root, qa=True)
            for f in t.frames:
                f.qa["flights"] = [{"at": [0, 90, 150], "d": 150, "in": f.t < 1.0, "open": True}]
            v = self.check([V.Cut(0, 3.0, "flight", t.name, 0.0)], [t])[0]
            self.assertIn("visible", [f.rule for f in v.findings])

    def test_text_reading_time(self):
        line = "Volkov: second key. Launch on my mark."
        ok = self.check([V.Cut(0, 4.0, "text", None, text=(line,))], [])[0]
        self.assertTrue(ok.ok)
        with tempfile.TemporaryDirectory() as root:
            t = live_take(root)
            short = self.check([V.Cut(0, 1.2, "text", t.name, 0.0, text=(line,))], [t])[0]
            self.assertIn("text", [f.rule for f in short.findings])

    def test_title_card_outside_the_frame_margins_fails(self):
        # черновик 16.10: «n49 — утром с Артёмом» кеглем логотипа выходил за кадр с обеих сторон
        line = "n49 — утром с Артёмом"
        wide = self.check([V.Cut(0, 3.0, "text", None, text=(line,), box=(-0.06, 0.44, 1.06, 0.56))], [])[0]
        self.assertIn("text", [f.rule for f in wide.findings])
        fitted = self.check([V.Cut(0, 3.0, "text", None, text=(line,), box=(0.053, 0.44, 0.947, 0.56))], [])[0]
        self.assertTrue(fitted.ok, fitted.findings)

    def test_repeat(self):
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root)
            out = self.check([V.Cut(0, 0.5, "scene", t.name, 1.0), V.Cut(5, 0.5, "scene", t.name, 1.2)], [t])
            self.assertTrue(out[0].ok)
            self.assertIn("repeat", [f.rule for f in out[1].findings])

    def test_report(self):
        with tempfile.TemporaryDirectory() as root:
            t = render_take(root)
            out = self.check([V.Cut(0, 2.4, "hit", t.name, 0.0, plan=10)], [t])
            md = V.report(out)
            self.assertIn("| FAIL |", md)
            self.assertIn("FAIL: 1", md)


if __name__ == "__main__":
    unittest.main()
