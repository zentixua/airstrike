"""Монтаж Newisle (newisle_cut.py): какие папки идут в монтаж и план монтажа по настоящему листу аниматика.

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import newisle_cut as C  # noqa: E402

SHEET = C.load(os.path.join(C.SCENES, "newisle-animatic.json"))
TAKES = C.load(os.path.join(C.SCENES, "newisle-takes.json"))


class NewisleCutTest(unittest.TestCase):
    def test_passed_renders_and_live_records(self):
        with tempfile.TemporaryDirectory() as root:
            def attempt(name, lines):
                rec = os.path.join(root, name)
                os.makedirs(os.path.join(rec, "render"))
                with open(os.path.join(rec, "render", "best.txt"), "w") as f:
                    f.write("\n".join(lines) + "\n")
                return {"ok": True, "rec": rec}
            covered = next(t for t in TAKES["takes"] if any(s.get("cover") for s in t["shots"]) and "artem" not in t)
            plans = [p for s in covered["shots"] for p in (s.get("cover") or {})]
            ok = attempt("a", [f"{plans[0]} /x/{plans[0]}/own PASS"] + [f"{p} - FAIL нет объекта" for p in plans[1:]])
            skipped = next(t for t in TAKES["takes"] if t["id"] != covered["id"] and "artem" not in t)
            state = {"takes": {covered["id"]: {"status": "passed", "attempts": [{"ok": False, "rec": "/nope"}, ok]},
                               skipped["id"]: {"status": "skipped", "attempts": [attempt("b", ["zz /y PASS"])]}}}
            dirs = C.shot_dirs(state, TAKES)
            self.assertEqual(f"/x/{plans[0]}/own", dirs[plans[0]])
            for p in plans[1:]:
                self.assertNotIn(p, dirs, "FAIL в монтаж не идёт")
            self.assertNotIn("zz", dirs, "пропущенный дубль в монтаж не идёт")

    def test_plan_follows_sheet_and_fills_holes(self):
        need = [s["plan"] for s in SHEET["shots"] if "plan" in s]
        dirs = {p: f"/r/{p}" for p in need[::2]}
        plan = C.plan_of(SHEET, dirs, broken={need[0]}, music="m.mp3")
        cuts = plan["cuts"]
        self.assertEqual(len(SHEET["shots"]), len(cuts), "склейка на каждую строку листа")
        total = sum(c["dur"] for c in cuts)
        expect = sum(float(s["dur"]) if "dur" in s else float(s["bars"]) * SHEET["bar"] for s in SHEET["shots"])
        self.assertAlmostEqual(expect, total, places=6)
        by_plan = {c.get("plan"): c for c in cuts if c.get("plan")}
        self.assertEqual("fill", by_plan[need[0]]["kind"], "сломанный в проверке — заливкой")
        self.assertEqual("fill", by_plan[need[1]]["kind"], "неснятый — заливкой")
        self.assertIn("not shot", by_plan[need[1]]["text"]["lines"][0])
        self.assertEqual(need[2], by_plan[need[2]]["take"])
        self.assertNotIn(need[0], plan["takes"])
        self.assertTrue(all(c["kind"] == "fill" for c in cuts if "card" in str(c.get("id", ""))))
        ids = {c.get("id") or c.get("take") for c in cuts}
        for piece in plan["music"]:
            self.assertIn(piece["start"], ids)
            self.assertLess(piece["from"], piece["to"])
        json.dumps(plan)

    def test_final_refuses_missing(self):
        with tempfile.TemporaryDirectory() as work:
            with open(os.path.join(work, "state.json"), "w") as f:
                json.dump({"takes": {}}, f)
            with self.assertRaises(SystemExit) as e:
                C.main(["--work", work, "--final"])
            self.assertIn("не сняты", str(e.exception.code))


if __name__ == "__main__":
    unittest.main()
