"""Аниматик из придуманных черновых кадров: план с двумя кадрами, план без кадра и заставка под куском «музыки»
(синус из ffmpeg); ролик должен выйти своей длины, со звуком.

    python3 -m unittest discover -s tools/trailer/tests     (нужны numpy, pillow и ffmpeg)
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

try:
    import numpy  # noqa: F401
    from PIL import Image
except ImportError:  # облако без пакетов: тест пропускается
    Image = None

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(os.path.dirname(HERE), "animatic.py")


def probe(path, what):
    return subprocess.run(["ffprobe", "-v", "error", "-select_streams", what, "-show_entries",
                           "stream=codec_type,duration", "-of", "json", path], capture_output=True, text=True).stdout


@unittest.skipIf(Image is None or not shutil.which("ffmpeg"), "нужны pillow и ffmpeg")
class AnimaticTest(unittest.TestCase):
    def test_sheet_to_video(self):
        with tempfile.TemporaryDirectory() as d:
            scout = os.path.join(d, "scout")
            os.makedirs(scout)
            for k, c in enumerate(((200, 40, 40), (40, 40, 200))):
                Image.new("RGB", (320, 180), c).save(os.path.join(scout, f"a1-{k}.jpg"))
            with open(os.path.join(scout, "poses.json"), "w") as f:
                json.dump([{"id": "a1", "title": "roof", "time": "night", "previs": ["a1-0.jpg", "a1-1.jpg"]}], f)
            tone = os.path.join(d, "tone.wav")
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=5",
                            tone], check=True)
            sheet = os.path.join(d, "sheet.json")
            with open(sheet, "w") as f:
                json.dump({"bar": 1.0, "music": {"parts": [{"at": 0.5, "track": 1.0, "end": 2.5, "fade": 0.3}]},
                           "shots": [{"plan": "a1", "bars": 1.5, "note": "звук: сирена"}, {"plan": "zz", "dur": 0.5},
                                     {"card": "INCOMING", "dur": 1.0}]}, f)
            out = os.path.join(d, "a.mp4")
            p = subprocess.run([sys.executable, TOOL, sheet, scout, out, "--music", tone, "--size", "320x180"],
                               capture_output=True, text=True)
            self.assertEqual(p.returncode, 0, p.stderr)
            v = json.loads(probe(out, "v"))["streams"][0]
            a = json.loads(probe(out, "a"))["streams"][0]
            self.assertAlmostEqual(float(v["duration"]), 3.0, delta=0.1)
            self.assertEqual(a["codec_type"], "audio")
            # середина первого плана — смесь красного и синего кадра (путь камеры)
            mid = os.path.join(d, "mid.png")
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", "0.75", "-i", out, "-frames:v", "1", mid],
                           check=True)
            r, g, b = Image.open(mid).convert("RGB").getpixel((160, 40))
            self.assertTrue(r > 80 and b > 80, (r, g, b))


if __name__ == "__main__":
    unittest.main()
