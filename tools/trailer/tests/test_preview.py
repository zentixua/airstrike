"""Ранний просмотр ночи (preview.py): кадры снятых планов — три на план, готовые не пересобираются, страница называет
не снятое и живые планы Артёма.

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import preview as V  # noqa: E402

TAKES = json.load(open(os.path.join(V.SCENES, "newisle-takes.json"), encoding="utf-8"))


class PreviewTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.calls = []

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def convert(self, src, dst):
        self.calls.append(src)
        shutil.copy(src, dst)

    def test_three_frames_per_shot_plan_and_holes_named(self):
        work = os.path.join(self.tmp, "work")
        shot = next(t for t in TAKES["takes"] if "artem" not in t)
        skipped = next(t for t in TAKES["takes"] if "artem" not in t and t["id"] != shot["id"])
        plan = shot["shots"][0]["plans"][0]
        rec = os.path.join(work, "takes", shot["id"], "a0")
        cand = os.path.join(rec, "render", plan, "own")
        frames = os.path.join(cand, "frames", plan)
        os.makedirs(frames)
        for i in range(10):
            with open(os.path.join(frames, f"{i:05d}.png"), "wb") as f:
                f.write(bytes([i]))
        os.makedirs(os.path.join(rec, "render"), exist_ok=True)
        with open(os.path.join(rec, "render", "best.txt"), "w", encoding="utf-8") as f:
            f.write(f"{plan} {cand} PASS\n")
        state = {"takes": {shot["id"]: {"status": "passed", "attempts": [{"ok": True, "rec": rec}]},
                           skipped["id"]: {"status": "skipped", "attempts": [{"ok": False, "reason": "удар не в срок"}]}}}
        os.makedirs(work, exist_ok=True)
        with open(os.path.join(work, "state.json"), "w", encoding="utf-8") as f:
            json.dump(state, f)
        os.makedirs(os.path.join(work, "preview"))
        V.p1_status(os.path.join(work, "preview"), fetch=lambda: "stage: recording — T10")
        shots = V.build(work, convert=self.convert)
        self.assertEqual([f"{plan}-0.jpg", f"{plan}-1.jpg", f"{plan}-2.jpg"], shots[plan])
        self.assertEqual(["00002.png", "00005.png", "00008.png"], sorted(os.path.basename(c) for c in self.calls[:3]))
        self.assertTrue(self.calls[3].endswith("00008.png"), "живая — предпоследний кадр: последний, может быть, пишется")
        page = open(os.path.join(work, "preview", "index.html"), encoding="utf-8").read()
        self.assertIn("удар не в срок", page)
        self.assertIn("утром с Артёмом", page, "живые планы Артёма — на утро")
        V.build(work, convert=self.convert)
        self.assertEqual(4, len(self.calls), "готовые кадры и живая миниатюра того же кадра не пересобираются")
        page = open(os.path.join(work, "preview", "index.html"), encoding="utf-8").read()
        self.assertIn("live.jpg", page)
        self.assertIn("T10", page, "статус P1 — из кэша")


if __name__ == "__main__":
    unittest.main()
