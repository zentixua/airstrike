"""Монтаж от событий (montage.py) и громкость (loudness.py) на придуманных дублях: файлы дублей пишет
test_verdict.render_take в формате съёмки, игра, карта и музыка не нужны.

    python3 -m unittest discover -s tools/trailer/tests
    (montage.py — с пакетами edit.py: uv run --with numpy --with scipy --with soundfile --with pillow …;
    loudness.py — только numpy; без них — пропуск)
"""
import contextlib
import io
import json
import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    import numpy as np
    import loudness as L
except ImportError:  # облако без numpy: тест пропускается
    np = L = None
try:
    import montage as M
except ImportError:  # нет пакетов edit.py (scipy, soundfile, pillow)
    M = None

import takes as T  # noqa: E402
import verdict as V  # noqa: E402
from test_verdict import ARRIVE, render_take  # noqa: E402

SR = 48000


def take_dir(root, name):
    return os.path.join(root, "film", "render", name, "cam-a")


def plan_of(cuts, music=(), **kw):
    return M.normalize({"name": "проба", "cuts": [dict(c) for c in cuts], "music": [dict(m) for m in music], **kw})


@unittest.skipIf(M is None, "нет пакетов edit.py (numpy, scipy, soundfile, pillow)")
class MontageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def take(self, name, **kw):
        render_take(self.root, name=name, suffix=f".{name}", **kw)
        return take_dir(self.root, name)

    def build(self, plan, names):
        takes = M.load_takes(plan, {n: take_dir(self.root, n) for n in names})
        return M.build(plan, takes)

    def failed(self, verdicts, label):
        return [f.rule for v in verdicts if v.cut.label == label for f in v.findings if f.level == "FAIL"]

    def test_hit_placed_by_impact_and_held_until_sound(self):
        """Удар — на якоре склейки, склейка держится до прихода звука и хвоста, хоть план и короче."""
        self.take("p10")
        plan = plan_of([{"take": "p10", "kind": "hit", "land": 0.6, "dur": 1.0}])
        ed, verdicts, _ = self.build(plan, ["p10"])
        p = ed.pieces[0]
        self.assertAlmostEqual(p.at(2.0) - p.start, 0.6, places=6)        # удар на 0,6 с склейки
        arrival = p.at(2.0 + ARRIVE)
        self.assertGreaterEqual(p.end, arrival + V.RULES["tail"] - 1e-6)  # держится до звука и хвоста
        self.assertLess(p.end, arrival + V.RULES["tail"] + 0.05)
        self.assertEqual([("удар", 2.0), ("звук", round(2.0 + ARRIVE, 2))],
                         [(w, round(x, 2)) for w, x in p.chain])
        self.assertTrue(verdicts[0].ok, verdicts[0].findings)
        # тот же план без правил событий: проверка роняет его сама
        plan = plan_of([{"take": "p10", "kind": "hit", "land": 0.6, "dur": 1.0, "hold": False}])
        _, verdicts, _ = self.build(plan, ["p10"])
        self.assertIn("hold", self.failed(verdicts, "p10"))

    def test_hit_scene_held_by_its_continuation(self):
        """Сцена удара в двух склейках подряд: звук держит вторая (continue), первая — по плану."""
        self.take("p10")
        plan = plan_of([{"take": "p10", "kind": "hit", "land": 0.5, "dur": 1.0},
                        {"take": "p10", "kind": "scene", "anchor": "continue", "dur": 0.2}])
        ed, verdicts, _ = self.build(plan, ["p10"])
        a, b = ed.pieces
        self.assertAlmostEqual(a.dur, 1.0, places=6)
        self.assertAlmostEqual(b.src, a.src_end, places=6)
        self.assertGreaterEqual(b.end, a.at(2.0 + ARRIVE) + V.RULES["tail"] - 1e-6)
        self.assertTrue(all(v.ok for v in verdicts), [v.findings for v in verdicts])

    def test_montage_sound_rings_over_next_cuts_until_silence(self):
        """Нарезка: удар в склейке, его звук доходит после неё — звучит поверх следующих склеек (L) и обрывается
        только тишиной."""
        self.take("p10")
        self.take("p20", impact=4.5)
        plan = plan_of([{"take": "p10", "kind": "montage", "land": 0.25, "dur": 0.4},
                        {"take": "p20", "kind": "scene", "dur": 0.5},
                        {"take": "p20", "id": "p20b", "kind": "scene", "anchor": 1.0, "dur": 0.5},
                        {"kind": "fill", "dur": 0.5, "silence": True}])
        ed, verdicts, segs = self.build(plan, ["p10", "p20"])
        self.assertTrue(verdicts[0].ok, verdicts[0].findings)
        hit = [g for g in segs if g.piece.take == "p10" and g.sound.kind == "blast"]
        self.assertEqual(len(hit), 1)
        g = hit[0]
        self.assertTrue(g.rings)
        silence = ed.pieces[3].start
        self.assertEqual(ed.silences, [silence])
        self.assertAlmostEqual(g.piece.at(g.t0), 0.25 + ARRIVE, places=6)   # приходит во второй склейке
        self.assertGreater(g.piece.at(g.t0), ed.pieces[0].end)
        self.assertAlmostEqual(g.piece.at(g.t1), silence, places=6)          # звучит до обрыва в тишину
        # обрыв сразу за нарезкой: тишина ждёт звук удара — склейка длиннее
        plan = plan_of([{"take": "p10", "kind": "montage", "land": 0.25, "dur": 0.4},
                        {"kind": "fill", "dur": 0.5, "silence": True}])
        ed, verdicts, _ = self.build(plan, ["p10"])
        self.assertGreaterEqual(ed.silences[0], ed.pieces[0].at(2.0 + ARRIVE) + M.SILENCE_AFTER - 1e-6)
        self.assertTrue(verdicts[0].ok, verdicts[0].findings)
        # тот же план без правил событий: звук придёт после обрыва — FAIL проверки
        plan = plan_of([{"take": "p10", "kind": "montage", "land": 0.25, "dur": 0.4, "hold": False},
                        {"kind": "fill", "dur": 0.5, "silence": True, "hold": False}])
        _, verdicts, _ = self.build(plan, ["p10"])
        self.assertIn("sound", self.failed(verdicts, "p10"))

    def test_j_cut_starts_sound_before_picture(self):
        self.take("p10")
        self.take("p20", sounds=[(0.6, "minecraft:entity.generic.explode", (0.0, 80.0, 5.0))])
        plan = plan_of([{"take": "p10", "kind": "scene", "dur": 1.0},
                        {"take": "p20", "kind": "scene", "anchor": 1.0, "dur": 1.0, "j": 0.5}])
        ed, _, segs = self.build(plan, ["p10", "p20"])
        second = ed.pieces[1]
        early = [g for g in segs if g.piece is second and g.sound.event == "minecraft:entity.generic.explode"]
        self.assertEqual(len(early), 1)
        self.assertAlmostEqual(second.at(early[0].t0), second.start - 0.4, places=6)   # звук 0,6 с дубля — за 0,4 с

    def test_music_on_bar_boundaries_and_held_scene_shifts_it(self):
        """Куски трека входят на свою сильную долю у склейки; склейки внутри — на тактах; удар, который держится до
        звука, сдвигает всё дальше на целый такт, и следующий кусок входит тактом позже."""
        self.take("p10")
        self.take("p20", impact=4.5)

        def plan(hold):
            return plan_of([{"take": "p20", "kind": "scene", "bars": 1},
                            {"take": "p10", "kind": "hit", "land": 1.5, "bars": 1, "hold": hold},
                            {"take": "p20", "id": "p20b", "kind": "scene", "anchor": 3.0, "bars": 1},
                            {"take": "p20", "id": "p20c", "kind": "scene", "anchor": 0.0, "bars": 1}],
                           music=[{"name": "A", "from": 4, "to": 40, "start": "p20"},
                                  {"name": "B", "from": 101, "to": 120, "start": "p20c"}])

        held, verdicts, _ = self.build(plan(True), ["p10", "p20"])
        loose, _, _ = self.build(plan(False), ["p10", "p20"])
        a, b = held.sections
        self.assertAlmostEqual(a.entry, 0.0)
        hit = held.pieces[1]
        self.assertGreater(hit.dur, M.BAR)                     # удар не влез в такт — держится до звука
        for p in held.pieces:                                  # концы склеек — на тактах куска A
            k = (p.end - a.entry) / M.BAR
            self.assertAlmostEqual(k, round(k), delta=1 / T.FPS / M.BAR)
        self.assertAlmostEqual((b.entry - a.entry) / M.BAR, 4, delta=0.01)
        self.assertAlmostEqual((loose.sections[1].entry - a.entry) / M.BAR, 3, delta=0.01)
        self.assertEqual(b.first, 101)                         # вход — на своей сильной доле трека
        parts = M.parts(held)
        self.assertAlmostEqual(parts[1].track, M.E.beat(101))
        self.assertAlmostEqual(parts[1].at, b.entry)
        self.assertAlmostEqual(parts[0].end - parts[0].track, b.entry - a.entry, places=6)   # A обрывает вход B
        music = M.music_map(held)
        self.assertAlmostEqual(min(m.t0 for m in music), 0.0)
        self.assertTrue(verdicts[1].ok, verdicts[1].findings)

    def test_music_waits_for_siren(self):
        """Тревога: музыка, которая вошла бы в неё, ждёт целых тактов после конца siren_hold."""
        self.take("p10")
        d = os.path.join(self.root, "live")
        rows = [{"type": "shot", "shot": "p14", "speed": 1.0, "hud": False, "lang": "en_us"}]
        for k in range(int(8 * T.FPS)):
            rows.append({"type": "frame", "shot": "p14", "k": k, "t": k / T.FPS * T.TPS, "cam": [0, 80, 0, 0, 0]})
        rows.append({"type": "sound", "shot": "p14", "t": 0.5 * T.TPS, "id": 0, "event": "incoming:siren.alert",
                     "file": "x", "pos": [0, 90, 30], "volume": 8.0, "pitch": 1.0, "range": 350.0, "relative": False,
                     "linear": True, "loop": True, "stream": False, "lp": [1, 1]})
        os.makedirs(d)
        with open(os.path.join(d, "timeline.jsonl"), "w") as f:
            f.write("\n".join(json.dumps(r) for r in rows) + "\n")
        plan = plan_of([{"take": "p14", "kind": "siren", "dur": 2.0},
                        {"take": "p10", "kind": "scene", "dur": 4.0}],
                       music=[{"name": "III", "from": 133, "to": 160, "sync": {"beat": 133, "cut": "p14", "at": "start"},
                               "start": "p14"}])
        takes = M.load_takes(plan, {"p14": d, "p10": take_dir(self.root, "p10")})
        ed, verdicts, segs = M.build(plan, takes)
        siren = ed.pieces[0]
        self.assertGreaterEqual(siren.end - siren.start, V.RULES["siren_hold"])   # тревога держится
        s = ed.sections[0]
        self.assertGreaterEqual(s.entry, siren.start + V.RULES["siren_hold"] - 1e-6)
        self.assertAlmostEqual((s.entry - siren.start) / M.BAR, round((s.entry - siren.start) / M.BAR), places=6)
        self.assertTrue(verdicts[0].ok, verdicts[0].findings)

    def test_missing_take_stops_and_names_the_plan(self):
        self.take("p10")
        plan = plan_of([{"take": "p10", "kind": "hit"}, {"take": "p23", "kind": "flight", "dur": 1.0},
                        {"take": "p24", "kind": "scene", "dur": 1.0}])
        with self.assertRaises(M.MontageError) as e:
            M.load_takes(plan, {"p10": take_dir(self.root, "p10"), "p24": take_dir(self.root, "p10")})
        msg = str(e.exception)
        self.assertIn("p23 (склейки 2)", msg)
        self.assertIn("p24 (склейки 3)", msg)   # папка есть, а плана в ней нет
        self.assertNotIn("p10 (", msg)

    def test_missing_event_stops(self):
        self.take("p10")
        plan = plan_of([{"take": "p10", "kind": "montage", "anchor": "impact#3", "dur": 0.5}])
        with self.assertRaises(M.MontageError) as e:
            self.build(plan, ["p10"])
        self.assertIn("склейка 1 p10", str(e.exception))
        self.assertIn("#3", str(e.exception))

    def test_fail_stops_rendering(self):
        """FAIL проверки — монтаж останавливается до картинки и звука и называет план; --check — только отчёт."""
        self.take("p10")
        path = os.path.join(self.root, "plan.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"cuts": [{"take": "p10", "kind": "hit", "land": 0.6, "dur": 1.0, "hold": False}]}, f)
        calls = []
        real = M.render
        M.render = lambda *a, **k: calls.append(a)
        try:
            args = [path, "--take", f"p10={take_dir(self.root, 'p10')}", "--out", os.path.join(self.root, "out.mp4")]
            with self.assertRaises(SystemExit) as e, contextlib.redirect_stdout(io.StringIO()):
                M.main(args)
            self.assertIn("p10 (hit)", str(e.exception.code))
            self.assertIn("остановлен", str(e.exception.code))
            self.assertEqual(calls, [])
            self.assertTrue(os.path.exists(os.path.join(self.root, "out-check.md")))
            out = io.StringIO()
            with self.assertRaises(SystemExit) as e, contextlib.redirect_stdout(out):
                M.main(args + ["--check"])
            self.assertEqual(e.exception.code, 1)
            self.assertIn("| FAIL |", out.getvalue())
            self.assertEqual(calls, [])
            # тот же план с правилами событий проходит и идёт в отрисовку
            with open(path, "w", encoding="utf-8") as f:
                json.dump({"cuts": [{"take": "p10", "kind": "hit", "land": 0.6, "dur": 1.0}]}, f)
            with contextlib.redirect_stdout(io.StringIO()):
                M.main(args)
            self.assertEqual(len(calls), 1)
        finally:
            M.render = real

    def test_text_held_for_reading(self):
        line = "<Claude> West batteries: targets set. Await the order."
        plan = plan_of([{"kind": "fill", "dur": 1.0, "text": {"lines": {"en": [line], "ru": ["коротко"]},
                                                              "style": "chat", "at": 0.15}}])
        ed, verdicts, _ = M.build(plan, {})
        need = V.RULES["read_base"] + len(line) / V.RULES["read_cps"]
        self.assertGreaterEqual(ed.texts[0].dur, need)
        self.assertEqual(ed.texts[0].lines, (line,))
        self.assertTrue(all(v.ok for v in verdicts))


@unittest.skipIf(L is None, "нет numpy")
class LoudnessTest(unittest.TestCase):
    """Сверка меры с определением BS.1770-4 и случаями EBU Tech 3341 (синус 997 Гц вместо 1 кГц)."""

    @staticmethod
    def sine(db, sec, f=997.0, left_only=False):
        t = np.arange(int(sec * SR)) / SR
        x = 10 ** (db / 20) * np.sin(2 * np.pi * f * t)
        return np.stack([x, np.zeros_like(x) if left_only else x], axis=1)

    def test_k_filter_coefficients(self):
        """При 48 кГц формулы дают таблицу BS.1770-4."""
        shelf, high = L.k_biquads(48000)
        for got, want in zip(shelf, (1.53512485958697, -2.69169618940638, 1.19839281085285, 1.0,
                                     -1.69065929318241, 0.73248077421585)):
            self.assertAlmostEqual(got, want, places=10)
        for got, want in zip(high, (1.0, -2.0, 1.0, 1.0, -1.99004745483398, 0.99007225036621)):
            self.assertAlmostEqual(got, want, places=10)

    def test_reference_sines(self):
        self.assertAlmostEqual(L.integrated(self.sine(-23, 20), SR), -23.0, delta=0.05)    # EBU 3341, случай 1
        self.assertAlmostEqual(L.integrated(self.sine(-33, 20), SR), -33.0, delta=0.05)    # случай 2
        self.assertAlmostEqual(L.integrated(self.sine(-20, 20), SR), -20.0, delta=0.05)
        # BS.1770: синус полной шкалы в одном канале — −3,01
        self.assertAlmostEqual(L.integrated(self.sine(0, 20, left_only=True), SR), -3.01, delta=0.05)

    def test_gating(self):
        quiet = L.integrated(np.concatenate([self.sine(-36, 10), self.sine(-23, 60), self.sine(-36, 10)]), SR)
        self.assertAlmostEqual(quiet, -23.0, delta=0.1)                                     # случай 3
        mixed = L.integrated(np.concatenate([self.sine(-26, 20), self.sine(-20, 20.1), self.sine(-26, 20)]), SR)
        self.assertAlmostEqual(mixed, -23.0, delta=0.1)                                     # случай 5
        self.assertEqual(L.integrated(np.zeros((SR * 5, 2)), SR), -math.inf)

    def test_true_peak(self):
        """Синус в четверть частоты со сдвигом 45°: отсчёты на −3,01 dBFS, настоящий пик — 0 dBTP."""
        n = np.arange(SR * 2)
        x = np.sin(2 * np.pi * n / 4 + np.pi / 4) * np.minimum(1, np.minimum(n, n[::-1]) / 4800)   # без краёв
        x = np.stack([x, x], axis=1)
        self.assertAlmostEqual(20 * math.log10(np.abs(x).max()), -3.01, delta=0.01)
        self.assertAlmostEqual(L.true_peak(x), 0.0, delta=0.05)
        fade = np.minimum(1, np.minimum(n, n[::-1]) / 4800)[:, None]
        self.assertAlmostEqual(L.true_peak(self.sine(-6, 2) * fade), -6.0, delta=0.05)

    def signal(self):
        """Ролик-образец: тишина в начале, тихий синус, шумовые всплески — взрывы (пики выше 0 dBFS), полная тишина
        в середине."""
        rng = np.random.default_rng(7)
        x = self.sine(-30, 30, f=220.0)
        x[:SR // 2] = 0.0
        for k in range(8):
            a = int((2 + 3 * k) * SR)
            env = np.exp(-np.arange(SR) / (0.25 * SR))
            x[a:a + SR] += rng.normal(0, 0.6, (SR, 2)) * env[:, None]
        x[int(12 * SR):int(16 * SR)] = 0.0   # тишина перед вспышкой
        return x

    def test_master_to_target(self):
        y, info = L.master(self.signal(), SR)
        self.assertAlmostEqual(L.integrated(y, SR), -14.0, delta=0.3)
        self.assertLessEqual(L.true_peak(y), -1.0 + 1e-6)
        self.assertGreater(info["limited"], 0.0)   # пики взрывов — через ограничитель

    def test_silence_stays_silent(self):
        x = self.signal()
        y, info = L.master(x, SR)
        self.assertEqual(float(np.abs(y[int(12.05 * SR):int(15.95 * SR)]).max()), 0.0)
        # тихий синус под ударами — на столько же громче, что и весь ролик: громкость ничего не поднимает сверх
        q0, q1 = int(26.5 * SR), int(29.5 * SR)
        self.assertAlmostEqual(20 * math.log10(np.abs(y[q0:q1]).max() / np.abs(x[q0:q1]).max()), info["gain_db"], delta=0.05)
        # лишние 30 с тишины не меняют усиления: блоки тишины в громкость не идут
        _, longer = L.master(np.concatenate([np.zeros((30 * SR, 2)), x]), SR)
        self.assertAlmostEqual(longer["gain_db"], info["gain_db"], delta=0.01)


@unittest.skipIf(M is None, "нет пакетов edit.py (numpy, scipy, soundfile, pillow)")
class TitleFitTest(unittest.TestCase):
    """Титр заливки (стиль title, кегль логотипа) не шире кадра без полей 5 %: длинная строка ужимается или переносится."""

    def test_long_fill_title_fits_with_margins(self):
        for line in ("n49 — утром с Артёмом", "n35b — ворота монтажа: сирена тише истории: 0.0 с сирены на 5.0 с окна"):
            box = M.text_span((line,), "title")
            if box is None:
                self.skipTest("нет шрифтов edit.FONTS (сеть или кэш)")
            x0, y0, x1, y1 = box
            self.assertGreaterEqual(x0, 0.05 - 1e-3, line)
            self.assertLessEqual(x1, 0.95 + 1e-3, line)
            self.assertTrue(0 <= y0 < y1 <= 1, line)

    def test_short_title_keeps_its_size(self):
        t = M.E.Titles(1920, 1080)
        try:
            (_, font, _, _, _), = t.layout(M.E.Text(0, 1, ("Incoming",), "title"))
        except (OSError, SystemExit):
            self.skipTest("нет шрифтов edit.FONTS (сеть или кэш)")
        self.assertEqual(190, font.size)


if __name__ == "__main__":
    unittest.main()


class ShotNameTest(unittest.TestCase):
    """Второй кусок того же плана из другого вывода: дубль «n31@wide» читает кадры плана n31."""

    def test_variant_reads_its_plan(self):
        self.assertEqual("n31", M.shot_name("n31@wide"))
        self.assertEqual("n31", M.shot_name("n31"))
