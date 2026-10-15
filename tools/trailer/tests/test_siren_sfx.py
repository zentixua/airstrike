"""Сирена монтажом (takes.siren_sfx_for → edit.py render_sfx) и её секунды (takes.siren_seconds): сработавшая до окна
вывода сирена повтором не играется — монтаж ставит её сам, как игра (два излучателя у первого удара, вход в середину
записи); рост сирены по этим же суммам — в cut_checks. Планы — из настоящего newisle-takes.json (n20 — сирена
включена, n42 — первый удар без тревоги).

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import takes as T  # noqa: E402

A = 185          # первый тик окна вывода (тик повтора)
CAM = (0.0, 70.0, 0.0, 0.0, 0.0)


def _w(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")


def render_dir(root, name="n20", marks=(), impacts=(), sounds=(), seconds=6.0):
    """Кандидат вывода <root>/render/<план>/cam-a: кадры, журнал повтора с отметками и ударами, отчёт окна."""
    d = os.path.join(root, "render", name, "cam-a")
    rows = [{"type": "shot", "shot": name, "speed": 1.0}]
    for k in range(int(seconds * T.FPS)):
        rows.append({"type": "frame", "shot": name, "k": k, "t": k * T.TPS / T.FPS, "cam": list(CAM)})
    rows += list(sounds)
    _w(os.path.join(d, "timeline.jsonl"), rows)
    events = [{"t": t, "mark": what} for t, what in marks]
    for i, (t, pos, weapon) in enumerate(impacts):
        events.append({"t": t - 1, "new": i, "id": f"f{i}", "weapon": weapon})
        events.append({"t": t, "impact": i, "pos": list(pos)})
    _w(os.path.join(d, f"{name}.events.jsonl"), events)
    _w(os.path.join(root, "render", "report.jsonl"),
       [{"shot": name, "candidate": "cam-a", "a": A, "event": -1}])
    return d


def siren_rows(t_ticks, sid=1, **kw):
    r = {"type": "sound", "shot": "n20", "t": t_ticks, "id": sid, "event": "incoming:siren",
         "file": "incoming:sounds/siren.ogg", "pos": [10, 80, 10], "volume": 16.0, "pitch": 1.0, "range": 256.0,
         "relative": False, "linear": True, "loop": False}
    r.update(kw)
    return r


class SirenSfxForTest(unittest.TestCase):
    def test_siren_before_window_is_placed_at_first_impact(self):
        """Отметки тревоги до окна (n20: t=74, 97 при окне 185) — две сирены у первого удара, вход в середину записи."""
        with tempfile.TemporaryDirectory() as root:
            d = render_dir(root, marks=[(74, "siren"), (97, "siren"), (A, "start"), (A, "plan:n20")],
                           impacts=[(227, (300.0, 70.0, -300.0), "rocket")])
            out = T.siren_sfx_for("n20", d)
            self.assertEqual(2, len(out), out)          # одна тревога (память 26,5 с), два излучателя
            self.assertEqual({(-111.0), }, {e["t"] for e in out})   # тик 74 − окно 185
            self.assertEqual("incoming:sounds/siren.ogg", out[0]["file"])
            self.assertEqual(16.0, out[0]["volume"])
            self.assertEqual([300.0 + 125, 70.0 + 15, -300.0 - 65], out[0]["pos"])
            self.assertEqual([300.0 - 95, 70.0 + 15, -300.0 + 115], out[1]["pos"])
            self.assertEqual((1.0, 0.97), (out[0]["pitch"], out[1]["pitch"]))

    def test_siren_without_impact_is_placed_at_recorder(self):
        with tempfile.TemporaryDirectory() as root:
            d = render_dir(root, marks=[(140, "siren"), (A, "start")], impacts=[])
            _w(os.path.join(root, "replays", "n20.events.jsonl"), [
                {"t": 100, "me": [-104.0, 79.0, -4.0], "cam": list(CAM), "p": []},
                {"t": 200, "me": [-104.0, 79.0, -4.0], "cam": list(CAM), "p": []}])
            out = T.siren_sfx_for("n20", d)
            self.assertEqual(2, len(out), out)
            self.assertEqual([-104.0 + 125, 79.0 + 15, -4.0 - 65], out[0]["pos"])

    def test_played_by_render_client_not_duplicated(self):
        """Клиент вывода сам играл сирену в окне (T04 n13) — монтаж свою не добавляет."""
        with tempfile.TemporaryDirectory() as root:
            d = render_dir(root, marks=[(20, "siren")], sounds=[siren_rows(20)])
            self.assertEqual([], T.siren_sfx_for("n20", d))

    def test_siren_disabled_take_gets_nothing(self):
        with tempfile.TemporaryDirectory() as root:
            d = render_dir(root, name="n42", marks=[(74, "siren")])
            self.assertEqual([], T.siren_sfx_for("n42", d))

    def test_far_before_window_is_silent_and_not_placed(self):
        """Тревога, чья запись кончилась до окна (тик 74 при записи 26,5 с и окне с 700) — sounded out, не ставится."""
        with tempfile.TemporaryDirectory() as root:
            d = render_dir(root, marks=[(74, "siren")])
            _w(os.path.join(root, "render", "report.jsonl"),
               [{"shot": "n20", "candidate": "cam-a", "a": 700, "event": -1}])
            self.assertEqual([], T.siren_sfx_for("n20", d))


class SirenSecondsTest(unittest.TestCase):
    def test_played_siren_runs_to_window_end(self):
        with tempfile.TemporaryDirectory() as root:
            d = render_dir(root, sounds=[siren_rows(60, sid=9)], seconds=8.0)
            take = T.load_take("n20", d)
            self.assertAlmostEqual(5.0, T.siren_seconds(take), places=3)   # тик 60 → 3 с, до конца окна 8 с

    def test_placed_siren_covers_the_window_from_before_it(self):
        with tempfile.TemporaryDirectory() as root:
            d = render_dir(root, marks=[(74, "siren")], seconds=8.0)
            take = T.load_take("n20", d)
            extra = T.siren_sfx_for("n20", d)
            self.assertEqual(2, len(extra))
            # два излучателя (как у игры) — каждый всё окно; старый счёт _siren_seconds суммировал так же
            self.assertAlmostEqual(16.0, T.siren_seconds(take, extra), places=3)


if __name__ == "__main__":
    unittest.main()
