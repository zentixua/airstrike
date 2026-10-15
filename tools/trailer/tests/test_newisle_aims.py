"""Прицелы управляемых ударов постановки Newisle — на строениях, не под землёй.

Цель плана — место с `above`: прицел получается на столько блоков ниже верха колонки. Место должно стоять
НА строении: колонка на краю контура даёт верх площадки, и `above: -40` уходит под землю (T13 15.10:
c1_glass_w/e стояли в блоке от башен-близнецов, верх 66 вместо ~196 — ракеты n03/n29c пикировали в точку
y=27 под площадью и рвались о соседние высотки за 1.0-1.25 с до своего срока). Мастер — эталон высот;
`above` глубже -20 у крылатой ракеты оправдан только высокой колонкой.

    python3 -m unittest discover -s tools/trailer/tests
"""
import json
import math
import os
import struct
import sys
import unittest
import zlib

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import blocks  # noqa: E402  читалка регионов постановки
from zmap import unpack  # noqa: E402

SCENES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scenes")
SCENE = json.load(open(os.path.join(SCENES, "newisle.json")))
TAKES = json.load(open(os.path.join(SCENES, "newisle-takes.json")))
if isinstance(TAKES, dict):
    TAKES = TAKES["takes"]
PLACES = SCENE["places"]
ROUTES = SCENE["routes"]
MASTERS = [
    os.environ.get("NEWISLE_MASTER", ""),
    "/mnt/data/projects/airstrike/mod/run/newisle-master",   # ноутбук
    "/home/ubuntu/airstrike-work/newisle-film-1014",         # netcup
]
# башни-близнецы Городa 1: стекло у мастера, каменный каркас после T04 — верх ~196 в обоих мирах
TOWER_TOP_MIN = 150
# `above` глубже этого у крылатой ракеты — прицел глубоко внутри колонки: колонка обязана быть строением
DEEP_ABOVE = -20
DEEP_TOP_MIN = 120


def master_regions():
    for m in MASTERS:
        if not m:
            continue
        for sub in ("world/region", "region"):
            p = os.path.join(m, sub)
            if os.path.isdir(p):
                return p
    return None


def column_top(regions, x, z):
    """Верх колонки: самый высокий не-воздушный блок в ней (карта высот примерно как у мода)."""
    cx, cz = x >> 4, z >> 4
    rp = os.path.join(regions, "r.%d.%d.mca" % (cx >> 5, cz >> 5))
    with open(rp, "rb") as f:
        data = f.read()
    ch = blocks.chunk_at(data, cx, cz)
    if ch is None:
        raise unittest.SkipTest("чанк (%d,%d) не читается" % (cx, cz))
    best = None
    for s in ch.get("sections", []):
        bs = s.get("block_states")
        if not bs:
            continue
        pal = [e["Name"] for e in bs["palette"]]
        solid = [i for i, n in enumerate(pal) if not n.endswith(":air")]
        if not solid:
            continue
        if len(pal) == 1:
            top = 15
        else:
            arr = unpack(bs["data"], max(4, math.ceil(math.log2(len(pal)))), 4096)
            top = next((y for y in range(15, -1, -1)
                        if int(arr[(y << 8) | ((z & 15) << 4) | (x & 15)]) in solid), None)
        if top is None:
            continue
        y = s["Y"] * 16 + top
        if best is None or y > best:
            best = y
    return best


def aim_spec(spec):
    """Спецификация цели удара → (имя места/None, x, z, above) или None, если это не место [x, z] + above."""
    if isinstance(spec, str):
        spec = PLACES.get(spec)
    if not isinstance(spec, dict):
        return None
    at = spec.get("at")
    if not (isinstance(at, list) and len(at) == 2):
        return None
    return spec.get("name"), at[0], at[1], spec.get("above", 0)


class NewisleAimsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.regions = master_regions()
        if not cls.regions:
            raise unittest.SkipTest("мастер Newisle не найден (NEWISLE_MASTER?) — высоты не проверить")

    def missile_aims(self):
        """Все прицелы крылатых ракет постановки: имя места, x, z, above, откуда взялась."""
        out = []
        for p in SCENE["plans"]:
            for st in p.get("strikes", []):
                if st.get("weapon") != "missile":
                    continue
                spec = st.get("to") or p.get("target")
                if "route" in st:
                    spec = ROUTES[st["route"]].get("to", spec)
                a = aim_spec(spec)
                if a:
                    out.append((a[0] or (p["id"] + ":strike"), a[1], a[2], a[3]))
        for t in TAKES:
            for sh in t.get("shots", []):
                for st in sh.get("strikes", []):
                    if st.get("weapon") != "missile":
                        continue
                    spec = st.get("to")
                    if "route" in st:
                        spec = ROUTES[st["route"]].get("to", spec)
                    a = aim_spec(spec)
                    if a:
                        out.append((a[0] or (t["id"] + ":" + sh["plans"][0]), a[1], a[2], a[3]))
        return out

    def test_glass_places_on_towers(self):
        for name in ("c1_glass_w", "c1_glass_e"):
            x, z = PLACES[name]["at"]
            top = column_top(self.regions, x, z)
            self.assertGreaterEqual(
                top, TOWER_TOP_MIN,
                "%s: колонка (%d,%d) верх %d — место вне контура башен-близнецов, `above: %d` даёт прицел "
                "y=%d под площадью" % (name, x, z, top, PLACES[name]["above"], top + 1 + PLACES[name]["above"]))

    def test_deep_missile_aims_on_tall_columns(self):
        for name, x, z, above in self.missile_aims():
            if above > DEEP_ABOVE:
                continue
            top = column_top(self.regions, x, z)
            self.assertGreaterEqual(
                top, DEEP_TOP_MIN,
                "%s: прицел крылатой ракеты (%d,%d) above %.0f на колонке верхом %d — ниже строения, ракета "
                "пикирует под землю и рвётся о соседние постройки до срока" % (name, x, z, above, top))

    def test_strike_routes_exist(self):
        for p in SCENE["plans"]:
            for st in p.get("strikes", []):
                if "route" in st:
                    self.assertIn(st["route"], ROUTES, "%s: нет пути %s" % (p["id"], st["route"]))


if __name__ == "__main__":
    unittest.main()
