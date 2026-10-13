"""Монтаж превью на снятых планах-заглушках: склейка, громкость, версия с музыкой, вспышка события по кадрам видео."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import edit  # noqa: E402

A = 100


def video(path: Path, flash: bool, ticks: int = 60):
    """Видео плана 108×192, кадр на тик (20 fps): серый фон, со вспышкой — белый квадрат посередине с кадра 30."""
    box = ",drawbox=x=44:y=86:w=20:h=20:color=white:t=fill:enable='between(n,30,45)'" if flash else ""
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"color=c=gray:s=108x192:r=20:d={ticks / 20}",
                    "-f", "lavfi", "-i", f"sine=f=220:d={ticks / 20}", "-vf", f"format=yuv420p{box}", "-c:v", "libx264",
                    "-c:a", "aac", "-shortest", str(path)], check=True)


def shot(root: Path, name: str, keys: list, flash: bool) -> dict:
    d = root / "film" / name / "cand"
    d.mkdir(parents=True)
    video(d / "video.mkv", flash)
    # камера смотрит на юг из (0, 64, 0): событие в (0, 64, 50) — посередине кадра
    (d / "qa.jsonl").write_text("".join(json.dumps({"t": A + i, "cam": [0, 64, 0, 0, 0], "fov": 70}) + "\n" for i in range(60)))
    (d / "verdict.json").write_text(json.dumps({"frames": 60, "approach": 1, "keys_ok": len(keys), "keys": len(keys), "loud_on": 1, "loud_off": 0}))
    return {"name": name, "a": A, "b": A + 59, "subject": {"keys": keys}, "dir": str(d)}


class EditTest(unittest.TestCase):
    def build(self, flash: bool) -> Path:
        root = Path(tempfile.mkdtemp(prefix="clips-edit-"))
        key = [{"t": A + 30, "pos": [0, 64, 50], "size": 8}]
        shots = [shot(root, "rockets-0-launch", [], False), shot(root, "rockets-0-impact", key, flash)]
        plan = {"width": 1080, "height": 1920, "fps": 20, "out": str(root / "film"), "previews": {"rockets-0": [s["name"] for s in shots]},
                "shots": [{k: v for k, v in s.items() if k != "dir"} for s in shots]}
        (root / "plan.json").write_text(json.dumps(plan))
        (root / "film" / "best.json").write_text(json.dumps({s["name"]: {"pass": True, "dir": s["dir"], "candidate": "pov-x", "a": s["a"], "b": s["b"]}
                                                            for s in shots}))
        (root / "runs.json").write_text(json.dumps([{"plan": str(root / "plan.json")}]))
        return root

    def run_edit(self, root: Path, *extra: str) -> str:
        argv = sys.argv
        sys.argv = ["edit.py", str(root), *extra]
        try:
            edit.main()
        finally:
            sys.argv = argv
        return (root / "previews" / "report.md").read_text()

    def test_flash_seen_and_clip_built(self):
        root = self.build(flash=True)
        music = root / "music.wav"
        subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=f=440:d=10", str(music)], check=True)
        report = self.run_edit(root, "--music", str(music), "--credit", "Test track")
        self.assertIn("rockets-0 — годится", report)
        self.assertIn("вспышка видна 1/1", report)
        self.assertAlmostEqual(edit.duration(root / "previews" / "rockets-0.mp4"), 6, delta=0.2)
        self.assertTrue((root / "previews" / "rockets-0-music.mp4").exists())
        text = (root / "previews" / "rockets-0.txt").read_text()
        self.assertIn("Modrinth", text)
        self.assertIn("Music: Test track", text)

    def test_hidden_flash_flagged(self):
        report = self.run_edit(self.build(flash=False))
        self.assertIn("ЕСТЬ ЗАМЕЧАНИЯ", report)
        self.assertIn("вспышка видна 0/1", report)

    def test_clip_filmed_live(self):
        root = self.build(flash=True)
        live = root / "far-live.mp4"
        video(live, flash=True, ticks=440)
        report = self.run_edit(root, "--clip", f"far={live}")
        self.assertIn("## far — годится", report)
        self.assertIn("снят не из повтора", report)
        self.assertAlmostEqual(edit.duration(root / "previews" / "far.mp4"), 22, delta=0.3)
        self.assertIn("Strikes far away through binoculars", (root / "previews" / "far.txt").read_text())

    def test_clip_checked_before_work(self):
        root = self.build(flash=True)
        live = root / "live.mp4"
        video(live, flash=True, ticks=440)
        mute = root / "mute.mp4"
        subprocess.run(["ffmpeg", "-v", "error", "-i", str(live), "-an", "-c", "copy", str(mute)], check=True)
        # подписи без чисел повтора нет у других роликов; ролик без звука не склеить со звуком
        for spec in (f"rockets={live}", str(live), f"far={mute}", f"far={root / 'none.mp4'}"):
            with self.subTest(spec=spec), self.assertRaises(SystemExit):
                self.run_edit(root, "--clip", spec)
        self.assertFalse((root / "previews" / "rockets-0.mp4").exists(), "до проверки --clip ничего не собрано")

    def test_failed_shot_left_out(self):
        root = self.build(flash=True)
        best = json.loads((root / "film" / "best.json").read_text())
        best["rockets-0-launch"]["pass"] = False
        (root / "film" / "best.json").write_text(json.dumps(best))
        report = self.run_edit(root)
        self.assertIn("в ролик не вошли планы без проверки кадров: 1", report)
        self.assertAlmostEqual(edit.duration(root / "previews" / "rockets-0.mp4"), 3, delta=0.2, msg="только прошедший план")
        text = (root / "previews" / "rockets-0.txt").read_text()
        self.assertIn("flight and impacts", text)
        self.assertNotIn("launch", text, "подпись — без пуска, которого нет")
        self.assertNotIn("пуск", text)

    def test_shot_of_other_plan_not_used(self):
        root = self.build(flash=True)
        best = json.loads((root / "film" / "best.json").read_text())
        best["rockets-0-impact"]["a"] = A - 200
        (root / "film" / "best.json").write_text(json.dumps(best))
        report = self.run_edit(root)
        self.assertIn("rockets-0-impact: не снят — снят по другому плану", report)
        self.assertIn("ЕСТЬ ЗАМЕЧАНИЯ", report)

    def test_captions(self):
        en, ru = edit.caption("far", {"weapon": "missile+grad", "numbers": {"strikes": [{"dist": 400}, {"dist": 650}, {"dist": 900}]}})
        self.assertIn("Strikes 400, 650 and 900 blocks away", en)
        self.assertIn("Удары в 400, 650 и 900 блоках", ru)
        far = edit.shown({"preview": "far", "weapon": "grad", "numbers": {"strikes": [{"dist": 875}, {"dist": 579}, {"dist": 1075}]}},
                         [{"name": "far-0-far1"}, {"name": "far-0-far3"}])
        self.assertEqual(edit.caption("far", far)[0], "Strikes 875 and 1075 blocks away: each flash comes before its sound.",
                         "удара плана, не вошедшего в ролик, в подписи нет")
        en, ru = edit.caption("rockets", {"weapon": "grad", "numbers": {}})
        self.assertTrue(ru.startswith("«Град»: пуск"), ru)
        en, ru = edit.caption("nuke", edit.shown({"preview": "nuke", "weapon": "icbm", "numbers": {}}, [{"name": "nuke-0-burst"}]))
        self.assertEqual(en, "A nuclear strike: flash, shock wave and mushroom cloud.", "пуска в ролике нет")
        en, ru = edit.caption("chaos", {"weapon": "grad", "numbers": {"ground": 21, "seconds": 20}})
        self.assertEqual(ru, "Ударов по земле за 20 секунд: 21. Одна камера.")
        en, ru = edit.caption("drones", {"weapon": "shahed", "numbers": {"shot_down": 1, "ground": 3}})
        self.assertEqual(en, "Shahed drones over the players: one is shot down, the rest hit the streets nearby.")
        self.assertEqual(ru, "Шахеды над игроками: один сбивают, остальные бьют по соседним улицам.")
        cand = {"preview": "drones", "weapon": "shahed", "numbers": {"shot_down": 1, "ground": 3}}
        merged = edit.shown(cand, [{"name": "drones-0-shootdown+impact"}])
        self.assertEqual(merged["numbers"]["ground"], 3, "удар — в плане, слитом со сбитием")
        alone = edit.shown(cand, [{"name": "drones-0-shootdown"}])
        self.assertEqual(alone["numbers"]["ground"], 0, "удара в ролике нет")
        en, ru = edit.caption("drones", edit.shown(cand, [{"name": "drones-0-flyby"}]))
        self.assertEqual(en, "Shahed drones fly low over the players.", "ни сбития, ни удара в ролике — о них ни слова")
        en, ru = edit.caption("rockets", edit.shown({"preview": "rockets", "weapon": "grad", "numbers": {"ground": 8}}, [{"name": "rockets-0-launch"}]))
        self.assertEqual(en, "Grad rockets: launch and flight.")
        pov = {"preview": "pov", "weapon": "shahed", "numbers": {"shot_down": 1, "ground": 3, "seconds": 12.0}}
        self.assertIs(edit.shown(pov, [{"name": "pov-0-pov"}]), pov, "глазами игрока — один план, события все")
        self.assertEqual(edit.caption("pov", pov), ("A drone is shot down in front of a player, seen through their eyes.",
                                                    "Дрон сбивают у игрока на глазах: вид его глазами."), "кто сбил — данных нет")
        en, ru = edit.caption("pov", {"weapon": "grad+shahed", "numbers": {"ground": 6, "seconds": 13.4}})
        self.assertEqual(ru, "Ударов вокруг игрока за 13 секунд: 6. Вид его глазами.")
        en, ru = edit.caption("pov", {"weapon": "shahed", "numbers": {"flyby": 2, "seconds": 10}})
        self.assertEqual(en, "Shahed drones overhead, seen through a player's eyes.")

    def test_projection_matches_game_axes(self):
        # взгляд на юг: точка восточнее (+x) — слева, выше — вверху
        x, y, depth = edit.project([0, 64, 0, 0, 0], 70, 9 / 16, [10, 74, 50])
        self.assertLess(x, 0)
        self.assertGreater(y, 0)
        self.assertAlmostEqual(depth, 50)


if __name__ == "__main__":
    unittest.main()
