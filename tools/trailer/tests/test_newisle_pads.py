"""Постановка пусков МБР (newisle-takes.json): место пуска — в зоне клиента записи, удар без съёмки — отменяется.

МБР — сущность, а не данные полёта: чанк места пуска клиент записи должен получить (прорисовка проверки —
24 чанка = 384 блока, дальность отслеживания МБР — 32 чанка), иначе момент «icbm» не наступает, а план пуст
(n32 15.10: шахта в 696 блоках от камеры — МБР вставала на y −55 у незагруженного чанка и не поднималась).
Удар, чей подрыв дубль не снимает (момент не «warning»/«front»), отменяется в «after»: иначе он детонирует
в мире следующего дубля цепочки (n32 15.10: gz сгорел внутри ожидания момента).

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import newisle_cut as C  # noqa: E402

TAKES = C.load(os.path.join(C.SCENES, "newisle-takes.json"))
PLACES = C.load(os.path.join(C.SCENES, "newisle.json"))["places"]
POSES = {p["id"]: p for p in C.load(os.path.join(C.SCENES, "newisle-poses.json"))}
# предел видимости места пуска для клиента записи: прорисовка проверки 24 чанка (384) с запасом
PAD_MAX_BLOCKS = 360


def place_xz(spec):
    if isinstance(spec, str):
        spec = PLACES[spec]
    at = spec["at"] if isinstance(spec, dict) else spec
    return at[0], at[1]


class NewislePadsTest(unittest.TestCase):
    def orders(self, take, shot=None):
        steps = list(take.get("stage", []))
        if shot:
            steps += shot.get("ready", []) + shot.get("cues", [])
        steps += take.get("after", [])
        return [s for s in steps if s.get("do") == "nuke.order"]

    def test_icbm_shot_launches_within_client_view(self):
        for take in TAKES["takes"]:
            for shot in take.get("shots", []):
                when = shot.get("when", {})
                if when.get("kind") != "icbm":
                    continue
                plans = shot.get("plans", [])
                eye = POSES[plans[0]]["frames"][0]["eye"]
                orders = [o for o in self.orders(take, shot) if o.get("by") == "player"]
                self.assertTrue(orders, f"{take['id']}: пуск МБР не найден")
                for o in orders:
                    x, z = place_xz(o.get("pad", "silo"))
                    d = ((x - eye[0]) ** 2 + (z - eye[2]) ** 2) ** 0.5
                    self.assertLessEqual(
                        d, PAD_MAX_BLOCKS,
                        f"{take['id']}: место пуска МБР в {d:.0f} блоках от камеры {plans[0]} — дальше прорисовки "
                        f"клиента записи, момент не наступит")

    def test_strike_without_filming_is_cleared(self):
        for take in TAKES["takes"]:
            if not any(o.get("by") == "player" for o in self.orders(take)):
                continue
            films_burst = any(s.get("when", {}).get("kind") in ("warning", "front") for s in take.get("shots", []))
            cleared = any(s.get("do") == "nuke.clear" for s in take.get("after", []))
            self.assertTrue(films_burst or cleared,
                            f"{take['id']}: заказан ядерный удар без съёмки подрыва и без nuke.clear — удар детонирует "
                            f"в мире следующего дубля")


if __name__ == "__main__":
    unittest.main()
