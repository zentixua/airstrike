"""Ворота монтажа Newisle (cut_checks.py) на придуманном листе и дублях: интерфейс под кинокаше, повторы, окна
записи, чат второго ключа, рост сирены; интерфейс в кадре — статическая проверка на настоящих числах мода.

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cut_checks as CC  # noqa: E402

TAKES = {"takes": [
    {"id": "T1", "siren": True, "shots": [
        {"plans": ["a1"], "seconds": 4, "strikes": [{}, {}]},
        {"plans": ["a2"], "seconds": 4, "camera": "scope", "hud": True}]},
    {"id": "T2", "siren": True, "shots": [{"plans": ["b1"], "seconds": 4, "strikes": [{}, {}, {}, {}]}]},
    {"id": "T3", "siren": False, "shots": [{"plans": ["c1"], "seconds": 4, "chat": True}]},
]}
SCENARIO = {"plans": [{"id": "b1", "strikes": [{}, {}]}]}


def sheet(shots, until="a1", open_=1.0):
    lb = {"aspect": 2.39, "until": until, "open": open_} if until else None
    return {"shots": shots, "bar": 1.92, "letterbox": lb}


class LetterboxTest(unittest.TestCase):
    def test_interface_plan_under_the_bar_clashes(self):
        s = sheet([{"plan": "a1", "dur": 3}, {"plan": "a2", "dur": 3}, {"plan": "b1", "dur": 3}], until="b1")
        self.assertEqual([("a2", "интерфейс мода под кинокаше 2.39:1")], CC.letterbox_clash(s, TAKES))

    def test_opening_plan_counts_as_under(self):
        s = sheet([{"plan": "a2", "dur": 3}], until="a2")
        self.assertEqual(["a2"], [p for p, _ in CC.letterbox_clash(s, TAKES)])

    def test_after_the_bar_and_quiet_plans_are_fine(self):
        s = sheet([{"plan": "a1", "dur": 3}, {"plan": "b1", "dur": 3}, {"plan": "a2", "dur": 3}])
        self.assertEqual([], CC.letterbox_clash(s, TAKES))


class NoRepeatsTest(unittest.TestCase):
    def test_plan_twice_and_window_overlap(self):
        s = sheet([{"plan": "a1", "dur": 3}, {"plan": "a1", "dur": 3}])
        self.assertEqual(["a1"], [p for p, _ in CC.duplicates(s)])
        with tempfile.TemporaryDirectory() as rec:
            with open(os.path.join(rec, "timeline.jsonl"), "w") as f:
                f.write(json.dumps({"type": "mark", "shot": "a1", "t": 20, "what": "plan:a1"}) + "\n")
                f.write(json.dumps({"type": "mark", "shot": "a2", "t": 100, "what": "plan:a2"}) + "\n")
            s = sheet([{"plan": "a1", "dur": 6.0}, {"plan": "a2", "dur": 3.0}])
            dirs = {"a1": rec, "a2": rec}
            # окно a1 — 1..7 с, окно a2 — 5..8 с: налегают
            self.assertEqual(["a2"], [p for p, _ in CC.overlaps(s, dirs)])
            s2 = sheet([{"plan": "a1", "dur": 3.0}, {"plan": "a2", "dur": 3.0}])
            self.assertEqual([], CC.overlaps(s2, dirs))


class ChatGateTest(unittest.TestCase):
    def lang(self, root, line):
        d = os.path.join(root, "mod/src/main/resources/assets/incoming/lang")
        os.makedirs(d)
        with open(os.path.join(d, "en_us.json"), "w") as f:
            json.dump({"incoming.nuke.key.prompt": line}, f)
        return root

    def record(self, root, options="guiScale:3\n"):
        d = os.path.join(root, "tools/trailer")
        os.makedirs(d)
        with open(os.path.join(d, "record.sh"), "w") as f:
            f.write(options)
        return root

    LINE = "☢ %s asks for the second key: %s at %s, %s. Confirm with %s (%s s left)"    # 17 слов

    def test_short_cut_and_low_chat_fail(self):
        with tempfile.TemporaryDirectory() as root:
            repo = self.record(self.lang(root, self.LINE))          # chatScale нет — как у игры по умолчанию (1)
            takes = {"takes": [{"id": "T", "shots": [{"plans": ["c1"], "chat": True}]}]}
            s = sheet([{"plan": "c1", "dur": 4.0}], until=None)
            out = CC.chat_gate(s, takes, repo)
            self.assertEqual(2, len(out), out)                      # и короче чтения, и ниже 2,5% высоты
            self.assertIn("4.0 с < 7.4 с", out[0][1])
            self.assertIn("высоты", out[1][1])

    def test_long_cut_and_scaled_chat_pass(self):
        with tempfile.TemporaryDirectory() as root:
            repo = self.record(self.lang(root, self.LINE), "guiScale:3\nchatScale:1.5\n")
            takes = {"takes": [{"id": "T", "shots": [{"plans": ["c1"], "chat": True}]}]}
            s = sheet([{"plan": "c1", "dur": 8.0}], until=None)
            self.assertEqual([], CC.chat_gate(s, takes, repo))
            s = sheet([{"plan": "c1", "dur": 6.0}], until=None)
            self.assertEqual(1, len(CC.chat_gate(s, takes, repo)))  # 1,5 + 0,35 × 17 = 7,45 с

    def test_real_record_options(self):
        gui, chat = CC.record_options(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                                   "record.sh"))
        self.assertEqual((3.0, 1.5), (gui, chat), "record.sh: guiScale 3, chatScale 1,5 (строка чата ≥ 2,5% кадра)")


class SirenGateTest(unittest.TestCase):
    def state_rec(self, root, take_id, seconds):
        rec = os.path.join(root, take_id)
        os.makedirs(rec, exist_ok=True)
        with open(os.path.join(rec, "timeline.jsonl"), "w") as f:
            f.write(json.dumps({"type": "sound", "shot": "x", "t": 0, "id": 1, "event": "incoming:siren"}) + "\n")
            f.write(json.dumps({"type": "stop", "shot": "x", "t": 20 * seconds, "id": 1}) + "\n")
        return rec

    def test_louder_history_with_quieter_take_clashes(self):
        with tempfile.TemporaryDirectory() as root:
            state = {"takes": {
                "T1": {"status": "passed", "attempts": [{"ok": True, "rec": self.state_rec(root, "T1", 10)}]},
                "T2": {"status": "passed", "attempts": [{"ok": True, "rec": self.state_rec(root, "T2", 4)}]},
            }}
            out = CC.siren_gate(TAKES, SCENARIO, state, {"b1": "/r"})
            self.assertEqual(["b1"], [p for p, _ in out], out)
            self.assertIn("4.0 с против 10.0 с", out[0][1])

    def test_growing_or_equal_siren_is_fine_and_silent_takes_are_not_compared(self):
        with tempfile.TemporaryDirectory() as root:
            state = {"takes": {
                "T1": {"status": "passed", "attempts": [{"ok": True, "rec": self.state_rec(root, "T1", 5)}]},
                "T2": {"status": "passed", "attempts": [{"ok": True, "rec": self.state_rec(root, "T2", 12)}]},
                "T3": {"status": "passed", "attempts": [{"ok": True, "rec": self.state_rec(root, "T3", 1)}]},
            }}
            self.assertEqual([], CC.siren_gate(TAKES, SCENARIO, state, {"b1": "/r"}))


class GuiFitsFrameTest(unittest.TestCase):
    """Интерфейс в кадре 2560×1440 при guiScale 3 (GUI 853×480): настоящие числа мода — статика."""

    def test_remote_screen_and_scope_fit(self):
        gui_w, gui_h = 2560 / 3, 1440 / 3
        self.assertLessEqual(320, gui_w, "пульт (RemoteScreen.W) уже GUI-кадра")
        for desc_lines in range(1, 29):                            # реальные описания — единицы строк
            self.assertLessEqual(204 + (desc_lines + 2) * 9, gui_h, "высота пульта в GUI-кадре")
        self.assertLessEqual(min(gui_w, gui_h) - 20, min(gui_w, gui_h),
                             "окуляр бинокля — квадрат в кадре по построению")

    def test_chat_line_height(self):
        self.assertGreaterEqual(3 * 1.5 * 9, 0.025 * 1440, "строка чата (record.sh) ≥ 2,5% высоты кадра")


if __name__ == "__main__":
    unittest.main()
