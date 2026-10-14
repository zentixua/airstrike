"""Проверки дубля Newisle (take_checks.py) на придуманных данных: каждый порог — малым журналом.

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import take_checks as T  # noqa: E402


def write(path, lines):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for r in lines:
            f.write(json.dumps(r) + "\n")


class SameTickLaunchesTest(unittest.TestCase):
    def rec(self, rows):
        root = tempfile.mkdtemp()
        write(os.path.join(root, "replays", "n1.events.jsonl"), rows)
        return root

    def test_two_flights_igniting_in_one_tick(self):
        rows = [{"t": 5, "new": 0, "id": "a", "weapon": "drone", "bomber": False},
                {"t": 5, "new": 1, "id": "b", "weapon": "drone", "bomber": False},
                {"t": 9, "f": [[0, 0, 0, 0, 0, 0], [1, 5, 0, 0, 0, 0]]},
                {"t": 10, "f": [[0, 0, 0, 0, 1, 0], [1, 5, 0, 0, 1, 0]]}]
        self.assertEqual(1, len(T.same_tick_launches(self.rec(rows))))
        self.assertIn("2 пуска в тик 10", T.same_tick_launches(self.rec(rows))[0])

    def test_crew_queue_and_boost_born_are_launches(self):
        # новый путь, впервые в IGNITION (поджиг на направляющей), и READY→BOOST — оба пуски, тики разные — чисто
        rows = [{"t": 5, "new": 0, "id": "a", "weapon": "drone", "bomber": False},
                {"t": 5, "new": 1, "id": "b", "weapon": "missile", "bomber": False},
                {"t": 6, "f": [[0, 0, 0, 0, 1, 0], [1, 0, 0, 0, 0, 0]]},
                {"t": 16, "f": [[0, 1, 0, 0, 2, 0], [1, 0, 0, 0, 1, 0]]}]
        self.assertEqual([], T.same_tick_launches(self.rec(rows)))

    def test_cruise_is_not_a_launch(self):
        rows = [{"t": 5, "new": 0, "id": "a", "weapon": "drone", "bomber": False},
                {"t": 5, "f": [[0, 0, 0, 0, 4, 0]]}]
        self.assertEqual([], T.same_tick_launches(self.rec(rows)))


class ServerLagTest(unittest.TestCase):
    def shot(self, mspt, actual=None):
        s = {"replay": "n1", "mspt": mspt, "strikes": []}
        if actual is not None:
            s["strikes"] = [{"weapon": "drone", "eta": 3, "bound": True, "actual": actual}]
        return {"shots": [s]}

    def test_spike_in_the_event_window(self):
        mspt = [5.0] * 40 + [56.0] * 12 + [5.0] * 40        # удар на 3-й секунде, окно 1..5 с
        self.assertEqual(1, len(T.server_lag(self.shot(mspt, actual=3.0))))

    def test_spike_outside_the_window_is_fine(self):
        mspt = [5.0] * 10 + [56.0] * 12 + [5.0] * 70        # задержка до удара, в окне (3±2 с) тихо
        self.assertEqual([], T.server_lag(self.shot(mspt, actual=5.0)))

    def test_without_strikes_the_whole_plan_counts_and_without_mspt_nothing_does(self):
        self.assertEqual(1, len(T.server_lag(self.shot([60.0] * 30))))
        self.assertEqual([], T.server_lag(self.shot(None)))
        self.assertEqual([], T.server_lag({"shots": [{"replay": "n1"}]}))


class IcbmFlightTest(unittest.TestCase):
    def rows(self, positions, flag=1):
        rows = [{"t": 0, "new": 0, "id": "a", "weapon": "nuke", "bomber": False}]
        for k, (x, y) in enumerate(positions):
            rows.append({"t": k, "f": [[0, x, y, 0, 4, flag]]})
        return rows

    def ascent(self, a, n=60, y0=100.0):
        pos, y, v = [], y0, 0.0
        for _ in range(n):
            pos.append((0.0, round(y, 2)))
            y += v
            v += a
        return pos

    def check(self, positions, flag=1):
        root = tempfile.mkdtemp()
        write(os.path.join(root, "replays", "n1.events.jsonl"), self.rows(positions, flag))
        return T.icbm_flight(root)

    def test_smooth_ascent_passes(self):
        # ускорение 0,03 бл/тик² (вертикально), шум округления 0,01
        self.assertEqual([], self.check(self.ascent(0.03)))

    def test_teleport_is_a_jerk(self):
        jump = self.ascent(0.03, n=40)
        jump[30] = (0.0, jump[30][1] + 2.0)                  # скачок пути
        self.assertTrue(any("рывок" in p or "ускорение" in p for p in self.check(jump)))

    def test_too_steep_is_an_acceleration_problem(self):
        self.assertTrue(any("ускорение" in p for p in self.check(self.ascent(0.09, n=40))))

    def test_server_only_samples_are_ignored(self):
        jump = self.ascent(0.03, n=40)
        jump[30] = (0.0, jump[30][1] + 2.0)
        self.assertEqual([], self.check(jump, flag=0), "места от пакетов сервера — не разгон сущности")


class SirenTest(unittest.TestCase):
    def rec(self, triggers, timeline, plan="n1"):
        root = tempfile.mkdtemp()
        write(os.path.join(root, "replays", f"{plan}.sounds.jsonl"), triggers)
        write(os.path.join(root, "replays", f"{plan}.events.jsonl"),
              [{"t": 200, "new": 0, "id": "a", "weapon": "drone", "bomber": False},
               {"t": 200, "impact": 0, "pos": [1, 2, 3]}])
        write(os.path.join(root, "timeline.jsonl"), timeline)
        return root

    def test_heard_early_enough_and_long_enough(self):
        r = self.rec([{"t": 40, "trigger": "siren", "heard": True}],
                     [{"type": "sound", "shot": "n1", "t": 40, "id": 1, "event": "incoming:siren"},
                      {"type": "stop", "shot": "n1", "t": 40 + 20 * 8, "id": 1}])
        self.assertEqual([], T.siren_take(r, {"siren": True}, ["n1"]))

    def test_late_siren_is_a_problem(self):
        r = self.rec([{"t": 195, "trigger": "siren", "heard": True}],
                     [{"type": "sound", "shot": "n1", "t": 195, "id": 1, "event": "incoming:siren"},
                      {"type": "stop", "shot": "n1", "t": 195 + 20 * 8, "id": 1}])
        self.assertTrue(any("сирена" in p for p in T.siren_take(r, {"siren": True}, ["n1"])))

    def test_silent_take_is_fine_and_short_siren_is_not(self):
        r = self.rec([{"t": 40, "trigger": "siren", "heard": True}],
                     [{"type": "sound", "shot": "n1", "t": 40, "id": 1, "event": "incoming:siren"},
                      {"type": "stop", "shot": "n1", "t": 40 + 20 * 3, "id": 1}])
        self.assertEqual([], T.siren_take(r, {"siren": False}, ["n1"]))
        self.assertTrue(any("из 6" in p for p in T.siren_take(r, {"siren": True}, ["n1"])))

    def test_unheard_siren_is_not_enough(self):
        r = self.rec([{"t": 40, "trigger": "siren", "heard": False}], [])
        self.assertTrue(any("не слышна" in p for p in T.siren_take(r, {"siren": True}, ["n1"])))


class FlockTest(unittest.TestCase):
    def rows(self, n, drones=4, share=1.0):
        out = []
        for k in range(10):
            flights = [{"at": [i, 0, i], "d": 300, "in": k < share * 10, "open": True} for i in range(drones)]
            if n and k == 5:                                  # кадр без стайных — порог 60% терпит один
                flights = flights[:1]
            out.append({"t": k, "flights": flights})
        return out

    def test_flock_of_four_read_as_flock(self):
        root = tempfile.mkdtemp()
        write(os.path.join(root, "qa", "n26.jsonl"), self.rows(4))
        take = {"shots": [{"plans": ["n26"], "camera": "scope", "watch": {"weapon": "drone"},
                           "strikes": [{}, {}, {}, {}]}]}
        self.assertEqual([], T.flock_in_scope(root, take))

    def test_half_frames_is_too_sparse(self):
        root = tempfile.mkdtemp()
        write(os.path.join(root, "qa", "n26.jsonl"), self.rows(4, share=0.5))
        take = {"shots": [{"plans": ["n26"], "camera": "scope", "watch": {"weapon": "drone"},
                           "strikes": [{}, {}, {}, {}]}]}
        self.assertTrue(any("стая" in p for p in T.flock_in_scope(root, take)))

    def test_other_plans_and_cameras_are_not_checked(self):
        take = {"shots": [{"plans": ["n1"], "camera": "scene", "watch": {"weapon": "drone"}}]}
        self.assertEqual([], T.flock_in_scope(tempfile.mkdtemp(), take))


def state(rows):
    """state.json вывода: ключи камеры на тик [тик, x, y, z, yaw, pitch]."""
    keys = {str(t): {"position": [x, y, z], "yaw": yaw, "pitch": pitch, "roll": 0} for t, x, y, z, yaw, pitch in rows}
    return {"scenes": [{"keyframeTracks": [{"keyframeType": "CAMERA", "keyframesByTick": keys}]}]}


class CameraTurnTest(unittest.TestCase):
    def check(self, rows, **kw):
        root = tempfile.mkdtemp()
        with open(os.path.join(root, "state.json"), "w") as f:
            json.dump(state(rows), f)
        return T.camera_turn(root)

    def test_at_the_limit_and_above(self):
        rows = [(t, 0, 0, 0, 6 * t / (1 if t == 0 else 1) if t else 0, 0) for t in range(1, 5)]
        rows = [(0, 0, 0, 0, 0, 0)] + [(t, 0, 0, 0, 6 * t, 0) for t in range(1, 5)]     # 6°/тик = 120°/с
        self.assertEqual([], self.check(rows))
        rows = [(0, 0, 0, 0, 0, 0)] + [(t, 0, 0, 0, 7 * t, 0) for t in range(1, 5)]     # 7°/тик = 140°/с
        self.assertEqual(1, len(self.check(rows)))

    def test_yaw_seam_is_a_small_turn(self):
        rows = [(0, 0, 0, 0, 179, 0), (1, 0, 0, 0, -175, 0)]                            # 6° через шов
        self.assertEqual([], self.check(rows))

    def test_missing_state_is_fine(self):
        self.assertEqual([], T.camera_turn(tempfile.mkdtemp()))


class AxisTest(unittest.TestCase):
    def check(self, axis):
        root = tempfile.mkdtemp()
        write(os.path.join(root, "qa.jsonl"), [{"t": k, "axis": a} for k, a in enumerate(axis)])
        return T.axis_fail(root)

    def test_threshold_at_fifteen_degrees(self):
        self.assertEqual([], self.check([0, 14.9, None, 0]))
        self.assertEqual(1, len(self.check([0, 15.1])))
        self.assertEqual([], self.check([None, None]))


class ShakeTest(unittest.TestCase):
    def rec(self, pitch, events, plan="n1"):
        root = tempfile.mkdtemp()
        rows = [(t, 0, 0, 0, 0, pitch(t)) for t in range(100)]
        with open(os.path.join(root, "state.json"), "w") as f:
            json.dump(state(rows), f)
        write(os.path.join(root, f"{plan}.events.jsonl"), events)
        return root

    def impact(self, weapon="missile", pos=(30, 0, 0)):
        # удар в тик 50 в 30 блоках: волна приходит через 30/17,15 ≈ 1,75 тика; размах модели — 6·18/38 ≈ 2,8°
        return [{"t": 1, "new": 0, "id": "a", "weapon": weapon, "bomber": False},
                {"t": 50, "impact": 0, "pos": list(pos)}]

    def test_jolt_after_arrival_passes_flat_camera_fails(self):
        events = self.impact()

        def jolt(t):
            u = t - 52
            return 1.5 if 0 <= u < 8 else 0.0
        self.assertEqual([], T.shake_present(self.rec(lambda t: jolt(t), events), "n1"))
        self.assertEqual(1, len(T.shake_present(self.rec(lambda t: 0.0, events), "n1")))

    def test_far_impact_and_nuclear_plans_are_excluded(self):
        far = self.impact(pos=(400, 0, 0))
        self.assertEqual([], T.shake_present(self.rec(lambda t: 0.0, far), "n1"))
        nuke = self.impact() + [{"t": 10, "nuke": [0, 0, 0], "kt": 15}]
        self.assertEqual([], T.shake_present(self.rec(lambda t: 0.0, nuke), "n1"))


class FpvTest(unittest.TestCase):
    def rec(self, rows, impacts):
        root = tempfile.mkdtemp()
        write(os.path.join(root, "qa.jsonl"), rows)
        write(os.path.join(root, "n1.events.jsonl"),
              [{"t": 1, "new": 0, "id": "a", "weapon": "drone", "bomber": False}] + impacts)
        return root

    def test_impacts_in_cone_count(self):
        rows = [{"t": t, "cam": [0, 0, 0, 0, 0], "fov": 70, "in": False} for t in range(10)]
        impacts = [{"t": 2, "impact": 0, "pos": [0, 0, 50]}]        # взгляд (курс 0) — на +Z, удар в конусе
        self.assertEqual([], T.fpv_over_districts(self.rec(rows, impacts), "n1"))
        self.assertEqual(1, len(T.fpv_over_districts(self.rec(rows, []), "n1")))

    def test_behind_and_old_impacts_do_not_count(self):
        rows = [{"t": t, "cam": [0, 0, 0, 0, 0], "fov": 70, "in": False} for t in range(10)]
        behind = [{"t": 2, "impact": 0, "pos": [0, 0, -50]}]
        self.assertEqual(1, len(T.fpv_over_districts(self.rec(rows, behind), "n1")))

    def test_visible_object_counts(self):
        rows = [{"t": t, "cam": [0, 0, 0, 0, 0], "fov": 70, "in": True, "seen": 0.9, "cloud": False,
                  "far": False, "drawn": True} for t in range(10)]
        self.assertEqual([], T.fpv_over_districts(self.rec(rows, []), "n1"))


class IcbmInFrameTest(unittest.TestCase):
    def test_share_of_height(self):
        root = tempfile.mkdtemp()
        write(os.path.join(root, "qa.jsonl"),
              [{"t": t, "in": True, "share": 0.03 if t < 8 else 0.001} for t in range(10)])
        self.assertEqual([], T.icbm_in_frame(root))
        write(os.path.join(root, "qa.jsonl"), [{"t": t, "in": True, "share": 0.01} for t in range(10)])
        self.assertEqual(1, len(T.icbm_in_frame(root)))
        self.assertEqual([], T.icbm_in_frame(tempfile.mkdtemp()))


class FrozenFramesTest(unittest.TestCase):
    def frames(self, data):
        root = tempfile.mkdtemp()
        d = os.path.join(root, "frames", "n1")
        os.makedirs(d)
        for k, b in enumerate(data):
            with open(os.path.join(d, f"{k:05d}.png"), "wb") as f:
                f.write(b)
        return root

    def test_three_identical_in_a_row(self):
        self.assertEqual([], T.frozen_frames(self.frames([b"a", b"b", b"c"]), "n1"))
        self.assertEqual(1, len(T.frozen_frames(self.frames([b"a", b"a", b"a"]), "n1")))
        self.assertEqual([], T.frozen_frames(self.frames([b"a", b"a", b"c", b"c"]), "n1"), "две пары — не замерзание")


class RecordProblemsTest(unittest.TestCase):
    def test_old_recording_without_new_data_is_not_a_failure(self):
        self.assertEqual([], T.record_problems(tempfile.mkdtemp(), {"siren": True, "shots": []}, {}))


if __name__ == "__main__":
    unittest.main()
