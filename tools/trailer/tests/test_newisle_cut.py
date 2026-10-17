"""Монтаж Newisle (newisle_cut.py): какие папки идут в монтаж и план монтажа по настоящему монтажному листу.

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import newisle_cut as C  # noqa: E402

SHEET = C.load(C.SHEET)
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
            # последняя попытка: выведенный план конвейер взял в монтаж с предупреждением — в best.txt у него FAIL
            with open(os.path.join(ok["rec"], "render", "best.txt"), "a") as f:
                f.write(f"{plans[-1]} /x/{plans[-1]}/tripod FAIL удар виден 5%\n")
            if len(plans) > 1:
                ok["plans"] = {plans[-1]: ["PASS", "предупреждение: удар виден 5%"]}
                dirs = C.shot_dirs(state, TAKES)
                self.assertEqual(f"/x/{plans[-1]}/tripod", dirs[plans[-1]], "PASS с предупреждением — в монтаж")

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
        hook = C.plan_of(SHEET, {need[0]: "/r/hook", need[2]: "/r/x"})["cuts"]
        self.assertEqual("start+2.0", next(c for c in hook if c.get("plan") == need[0])["anchor"], "якорь куска — из листа")
        self.assertEqual("start", by_plan[need[2]]["anchor"])
        self.assertNotIn(need[0], plan["takes"])
        self.assertTrue(all(c["kind"] == "fill" for c in cuts if "card" in str(c.get("id", ""))))
        styles = {tuple(c["text"]["lines"]): c["text"]["style"] for c in cuts
                  if "card" in str(c.get("id", "")) and c.get("text")}
        self.assertEqual("title", styles.get(("INCOMING",)), "название — кеглем логотипа, а не строкой титров")
        self.assertTrue(all(v == "end" for k, v in styles.items() if k != ("INCOMING",)))
        ids = {c.get("id") or c.get("take") for c in cuts}
        for piece in plan["music"]:
            self.assertIn(piece["start"], ids)
            self.assertLess(piece["from"], piece["to"])
        json.dumps(plan)

    def test_sheet_90_fits_track_and_gates(self):
        """Лист: все планы есть в дублях (второй кусок плана «n31@wide» — план n31), вторая часть трека накрывает ролик до
        конца (длина может расти — Артём 17.10), музыка на ударах трека, ворота монтажа чисты."""
        known = {p for t in TAKES["takes"] for s in t.get("shots", []) for p in s.get("plans", [])}
        known |= {p for t in TAKES["takes"] for p in (t.get("cover") or {})}
        need = [s["plan"] for s in SHEET["shots"] if "plan" in s]
        self.assertEqual([], [p for p in need if p.split("@")[0] not in known], "план листа без дубля")
        wave_part = SHEET["music"]["parts"][-1]
        self.assertLessEqual(sum(s["dur"] for s in SHEET["shots"]) - wave_part["at"], wave_part["end"] - wave_part["track"] + 0.5,
                             "трек кончается раньше ролика")
        plan = C.plan_of(SHEET, {p: f"/r/{p}" for p in need}, music="m.mp3")
        war, wave = plan["music"]
        self.assertEqual(("n38", 144, 192, 4, 0.4), (war["start"], war["from"], war["to"], war["grid"], war["ring"]))
        # ядерная часть 17.10: музыка снова входит на одеяле пыли (n65), удар трека 3:17,8 — когда оно доходит до толпы (n66)
        self.assertEqual(("n65", 196, 4), (wave["start"], wave["from"], wave["grid"]))
        cut_checks = C.cut_checks
        self.assertEqual([], cut_checks.letterbox_clash(SHEET, TAKES))
        self.assertEqual([], cut_checks.duplicates(SHEET))
        self.assertEqual([], cut_checks.chat_gate(SHEET, TAKES, C.REPO), "строка второго ключа успевает прочитаться")

    def test_live_plans_wait_for_artem(self):
        live = C.live_plans(TAKES)
        self.assertTrue(live, "у листа есть живые дубли")
        p = next(s["plan"] for s in SHEET["shots"] if s.get("plan") in live)
        cuts = {c.get("plan"): c for c in C.plan_of(SHEET, {}, later=live)["cuts"] if c.get("plan")}
        self.assertEqual(f"{p} — утром с Артёмом", cuts[p]["text"]["lines"][0])
        other = next(s["plan"] for s in SHEET["shots"] if "plan" in s and s["plan"] not in live)
        self.assertIn("not shot", cuts[other]["text"]["lines"][0])

    def test_final_refuses_missing(self):
        with tempfile.TemporaryDirectory() as work:
            with open(os.path.join(work, "state.json"), "w") as f:
                json.dump({"takes": {}}, f)
            with self.assertRaises(SystemExit) as e:
                C.main(["--work", work, "--final"])
            self.assertIn("не сняты", str(e.exception.code))


if __name__ == "__main__":
    unittest.main()
