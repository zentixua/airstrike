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
    """МБР против кривой разгона модели: реальная запись (T23 n31, T23b n32) видит путь пачками и с
    отставанием клиента, поэтому проверяются инварианты пути, а не разности соседних тиков."""

    def check(self, samples, flag=1):
        root = tempfile.mkdtemp()
        rows = [{"t": 0, "new": 0, "id": "a", "weapon": "nuke", "bomber": False}]
        for t, (x, y, z) in samples:
            rows.append({"t": t, "f": [[0, x, y, z, 1, flag]]})
        write(os.path.join(root, "replays", "n1.events.jsonl"), rows)
        return T.icbm_flight(root)

    def flight(self, standstill=25, ticks=170, pad=100.0, lag=0, jump=0.0, drop=0.0, side=0.0):
        """Путь записи: стойка на площадке, потом кривая модели с отставанием lag тиков и пачками
        (4 тика одно место из каждых 6); на 80-м тике полёта — скачок вверх jump, падение drop или сдвиг
        вбок side (у верного полёта все нули, лёгкий дрейф вбок после наклона — как у модели)."""
        _, climbs = T._ascent_curves()
        samples = [(t, (0.0, pad, 0.0)) for t in range(-standstill, 1)]
        shown = (0.0, pad, 0.0)
        for k in range(1, ticks + 1):
            n = max(0, k - lag)
            if k > 6 and k % 6 < 4:
                pos = shown                                     # пачка: место держится, потом догоняющий скачок
            else:
                drift = 0.25 * max(0, n - T.ICBM_PITCH_TICKS)   # наклон к 85°: ~30 бл вбок за подъём
                pos = (round(drift, 2), round(pad + climbs[n], 2), 0.0)
                shown = pos
            if k == 80:
                pos = (round(pos[0] + side, 2), round(pos[1] + jump - drop, 2), 0.0)
            samples.append((k, pos))
        return samples

    def test_model_flight_with_bursts_and_client_lag_passes(self):
        self.assertEqual([], self.check(self.flight()), "ровный полёт по кривой")
        self.assertEqual([], self.check(self.flight(lag=8)), "запись отстаёт на 8 тиков (T23b n32)")
        self.assertEqual([], self.check(self.flight(lag=11)), "отставание до 11 тиков — предел данных записей")

    def test_teleport_up_is_a_path_jump(self):
        self.assertTrue(any("скачок" in p or "обгоняет" in p for p in self.check(self.flight(jump=40.0))))

    def test_sideways_jump_is_a_path_jump(self):
        self.assertTrue(any("скачок" in p for p in self.check(self.flight(side=40.0))))

    def test_falling_path_is_a_problem(self):
        self.assertTrue(any("падает" in p for p in self.check(self.flight(drop=10.0))))

    def test_standing_on_the_pad_all_plan_is_a_problem(self):
        self.assertEqual(1, len(self.check([(t, (0.0, 100.0, 0.0)) for t in range(60)])))

    def test_server_only_samples_are_ignored(self):
        self.assertEqual([], self.check(self.flight(jump=40.0), flag=0), "места от пакетов сервера — не путь сущности")


class SirenTest(unittest.TestCase):
    """Сирена дубля — как услышит монтаж: клиент вывода (timeline.jsonl кандидата) плюс поставленная монтажом."""

    def rec(self, sounds, marks=(), plan="n1"):
        root = tempfile.mkdtemp()
        d = os.path.join(root, "render", plan, "cam-a")
        rows = [{"type": "shot", "shot": plan, "speed": 1.0}]
        for k in range(20 * 60):
            rows.append({"type": "frame", "shot": plan, "k": k, "t": k / 3.0, "cam": [0, 70, 0, 0, 0]})
        rows += list(sounds)
        write(os.path.join(d, "timeline.jsonl"), rows)
        events = [{"t": t, "mark": what} for t, what in marks]
        events += [{"t": 200, "new": 0, "id": "a", "weapon": "drone", "bomber": False},
                   {"t": 200, "impact": 0, "pos": [1, 2, 3]}]
        write(os.path.join(d, f"{plan}.events.jsonl"), events)
        with open(os.path.join(root, "render", "best.txt"), "w") as f:   # best.txt — сырой текст, не JSON
            f.write(f"{plan} cam-a PASS x\n")
        return root

    @staticmethod
    def siren(t, stop=None, plan="n1", sid=1):
        rows = [{"type": "sound", "shot": plan, "t": t, "id": sid, "event": "incoming:siren",
                 "file": "incoming:sounds/siren.ogg", "pos": [1, 80, 3], "volume": 16.0, "pitch": 1.0,
                 "range": 256.0, "relative": False, "linear": True, "loop": False}]
        if stop is not None:
            rows.append({"type": "stop", "shot": plan, "t": stop, "id": sid})
        return rows

    def test_heard_early_enough_and_long_enough(self):
        r = self.rec(self.siren(40, 40 + 20 * 8))
        self.assertEqual([], T.siren_take(r, {"siren": True}, ["n1"]))

    def test_late_siren_is_a_problem(self):
        r = self.rec(self.siren(195, 195 + 20 * 8))
        self.assertTrue(any("сирена" in p for p in T.siren_take(r, {"siren": True}, ["n1"])))

    def test_silent_take_is_fine_and_short_siren_is_not(self):
        r = self.rec(self.siren(40, 40 + 20 * 3))
        self.assertEqual([], T.siren_take(r, {"siren": False}, ["n1"]))
        self.assertTrue(any("из 6" in p for p in T.siren_take(r, {"siren": True}, ["n1"])))

    def test_unheard_siren_is_not_enough(self):
        r = self.rec([])
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


class CrowdGazeTest(unittest.TestCase):
    """Толпа провожает ракеты (n43): журнал записи пишет курс головы и тангаж; люди смотрят на ближнюю ракету."""
    TAKE = {"stage": [{"do": "crowd", "group": "desert", "prefix": "Des"}],
            "shots": [{"plans": ["n52", "n43"], "ready": [{"do": "crowd.follow", "group": "desert", "weapon": "missile"}]}]}

    def rec(self, look, gaze=True):
        """Ракета идёт на восток в 100 блоках к югу и в 40 выше глаз; look(t, глаз, ракета) → (курс, тангаж)."""
        rows = [{"t": 0, "mark": "plan:n52"}, {"t": 0, "new": 0, "id": "a", "weapon": "missile", "bomber": False},
                {"t": 40, "mark": "plan:n43"}, {"t": 80, "mark": "end"}]
        for t in range(0, 80):
            m = (-50 + 2 * t, 141.6, 100.0)
            eye = (0.0, 101.62, 0.0)
            yaw, pitch = look(t, eye, m)
            person = ["Des0", 0.0, 100.0, 0.0] + ([yaw, pitch] if gaze else [])
            rows.append({"t": t, "f": [[0, m[0], m[1], m[2], 4, 1]]})
            rows.append({"t": t, "me": [0, 70, 0], "p": [["Sokol", 5.0, 70.0, 5.0, 0.0, 0.0], person]})
        root = tempfile.mkdtemp()
        write(os.path.join(root, "replays", "n52.events.jsonl"), rows)
        return root

    @staticmethod
    def at_missile(t, eye, m):
        d = [m[i] - eye[i] for i in range(3)]
        return (T.math.degrees(T.math.atan2(-d[0], d[2])), -T.math.degrees(T.math.atan2(d[1], T.math.hypot(d[0], d[2]))))

    def test_people_following_the_missile_pass(self):
        self.assertEqual([], T.crowd_gaze(self.rec(self.at_missile), self.TAKE))

    def test_people_looking_away_fail_each_plan(self):
        # как n43 16.10: слежение кончилось до окна, головы стоят на месте — курс к северу, ракета к югу
        out = T.crowd_gaze(self.rec(lambda t, eye, m: (180.0, 0.0)), self.TAKE)
        self.assertEqual(["n52", "n43"], [p.split(":")[0] for p in out], out)
        self.assertIn("0% взглядов", out[1])

    def test_level_gaze_under_a_high_missile_fails_on_pitch(self):
        out = T.crowd_gaze(self.rec(lambda t, eye, m: (self.at_missile(t, eye, m)[0], 0.0)), self.TAKE)
        self.assertEqual(2, len(out), out)

    def test_old_journal_without_gaze_and_other_people_are_not_checked(self):
        self.assertEqual([], T.crowd_gaze(self.rec(lambda t, eye, m: (180.0, 0.0), gaze=False), self.TAKE))
        take = {"stage": [{"do": "crowd", "group": "desert", "prefix": "Cit"}], "shots": self.TAKE["shots"]}
        self.assertEqual([], T.crowd_gaze(self.rec(lambda t, eye, m: (180.0, 0.0)), take))


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
        self.assertEqual(1, len(self.check([0, 15.1, 15.1])))
        self.assertEqual([], self.check([None, None]))

    def test_one_tick_of_reversed_motion_is_not_the_nose(self):
        root = tempfile.mkdtemp()
        rows = [{"t": 175.667, "axis": 0.9}] + [{"t": 176 + k / 3, "axis": 179.1} for k in range(3)] + [{"t": 177.0, "axis": 1.0}]
        write(os.path.join(root, "qa.jsonl"), rows)
        self.assertEqual([], T.axis_fail(root), "ход на тик назад (T06 n51), нос на месте")
        write(os.path.join(root, "qa.jsonl"), [{"t": 176 + k / 3, "axis": 90.0} for k in range(6)])
        self.assertEqual(1, len(T.axis_fail(root)), "два тика — уже нос")


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


class DimFramesTest(unittest.TestCase):
    """Тёмный кадр: средняя яркость против предела из cover (T07 n11 на рассвете 23200 мерился 19)."""

    def frames(self, level, n=3):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("нет PIL")
        root = tempfile.mkdtemp()
        d = os.path.join(root, "frames", "n1")
        os.makedirs(d)
        for k in range(n):
            Image.new("RGB", (64, 36), (level, level, level)).save(os.path.join(d, f"{k:05d}.png"))
        return root

    def test_dark_dawn_fails_the_floor(self):
        out = T.dim_frames(self.frames(19), "n1", 60)
        self.assertEqual(1, len(out))
        self.assertIn("яркость", out[0])

    def test_readable_dawn_passes(self):
        self.assertEqual([], T.dim_frames(self.frames(75), "n1", 60))

    def test_no_frames_or_no_floor_is_fine(self):
        self.assertEqual([], T.dim_frames(tempfile.mkdtemp(), "n1", 60))
        self.assertEqual([], T.dim_frames(self.frames(19), "n1", None))


class MapLoadingTest(unittest.TestCase):
    def test_dh_loading_line_on_the_remote_map_fails(self):
        result = {"shots": [{"plans": ["n07"], "map_ticks": 120, "map_pending": 37}]}
        out = T.map_loading(result)
        self.assertEqual(1, len(out))
        self.assertIn("n07", out[0])
        self.assertIn("37 тиков из 120", out[0])
        self.assertEqual(out, T.record_problems(tempfile.mkdtemp(), {"shots": []}, result))

    def test_built_map_and_plans_without_map_pass(self):
        self.assertEqual([], T.map_loading({"shots": [{"plans": ["n07"], "map_ticks": 120, "map_pending": 0},
                                                      {"plans": ["n13"]}]}))
        self.assertEqual([], T.map_loading(None))


class MapAimTest(unittest.TestCase):
    def test_one_mark_without_route_passes(self):
        self.assertEqual([], T.map_aim({"shots": [{"plans": ["n07"], "map_ticks": 120, "map_marks": 1, "map_route": 0}]}))

    def test_jumping_route_and_missed_click_fail(self):
        out = T.map_aim({"shots": [{"plans": ["n07"], "map_ticks": 120, "map_marks": 1, "map_route": 4}]})
        self.assertEqual(["n07: на карте пульта точек маршрута 4, маршрут прыгает"], out)
        out = T.map_aim({"shots": [{"plans": ["n07"], "map_ticks": 120, "map_marks": 0, "map_route": 0}]})
        self.assertEqual(["n07: на карте пульта конечных точек 0, нужна одна"], out)
        result = {"shots": [{"plans": ["n07"], "map_ticks": 120, "map_marks": 3, "map_route": 0}]}
        self.assertEqual(T.map_aim(result), T.record_problems(tempfile.mkdtemp(), {"shots": []}, result))

    def test_recording_before_the_counters_is_not_checked(self):
        self.assertEqual([], T.map_aim({"shots": [{"plans": ["n07"], "map_ticks": 120, "map_pending": 0}]}))
        self.assertEqual([], T.map_aim(None))


class RecordProblemsTest(unittest.TestCase):
    def test_old_recording_without_new_data_is_not_a_failure(self):
        self.assertEqual([], T.record_problems(tempfile.mkdtemp(), {"siren": True, "shots": []}, {}))

    def test_server_lag_is_a_warning_not_a_retake(self):
        result = {"shots": [{"replay": "n1", "mspt": [80.0] * 30}]}
        rec = tempfile.mkdtemp()
        self.assertEqual([], T.record_problems(rec, {"siren": False, "shots": []}, result))
        self.assertEqual(1, len(T.record_warnings(rec, {"siren": False, "shots": []}, result)))


if __name__ == "__main__":
    unittest.main()
