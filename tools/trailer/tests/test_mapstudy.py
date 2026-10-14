"""Изучение карты на придуманном мире: регион пишет тест (улица между домами, высотка, озеро с мостом, метро под землёй,
холм), mapstudy.py должен найти каждое.

    python3 -m unittest discover -s tools/trailer/tests     (нужны numpy и pillow; без них — пропуск)
"""
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib

try:
    import numpy as np
    import PIL  # noqa: F401
except ImportError:  # облако без пакетов: тест пропускается
    np = None

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(os.path.dirname(HERE), "mapstudy.py")


def _payload(out, v):
    if isinstance(v, dict):
        for k, x in v.items():
            out += bytes([_tag(x)]) + struct.pack(">H", len(k.encode())) + k.encode()
            _payload(out, x)
        out += b"\0"
    elif isinstance(v, list):
        out += struct.pack(">bi", _tag(v[0]) if v else 0, len(v))
        for x in v:
            _payload(out, x)
    elif isinstance(v, str):
        out += struct.pack(">H", len(v.encode())) + v.encode()
    elif isinstance(v, np.ndarray):
        out += struct.pack(">i", len(v)) + v.astype(">i4" if v.dtype == np.int32 else ">i8").tobytes()
    else:
        out += struct.pack(">i", int(v))


def _tag(v):
    if isinstance(v, np.ndarray):
        return 11 if v.dtype == np.int32 else 12
    return 10 if isinstance(v, dict) else 9 if isinstance(v, list) else 8 if isinstance(v, str) else 3


def pack(vals, bits):
    per = 64 // bits
    n = -(-len(vals) // per)
    v = np.zeros(n * per, np.uint64)
    v[:len(vals)] = vals
    v = v.reshape(n, per)
    out = np.zeros(n, np.uint64)
    for k in range(per):
        out |= v[:, k] << np.uint64(k * bits)
    return out.view(np.int64)


def write_world(region_dir, world, cw, ch, rx=0, rz=0, starts=None):
    """world[y + 64, z, x] — номера в names; чанки 0..cw-1 × 0..ch-1 от угла региона r.rx.rz; starts —
    {(cx, cz): [(id, [x0, y0, z0, x1, y1, z1]), …]} — начала построек генерации в чанке."""
    names = world.names
    os.makedirs(region_dir, exist_ok=True)
    head, body = bytearray(8192), bytearray()
    for cz in range(ch):
        for cx in range(cw):
            secs = []
            for sy in range(-4, 20):
                blk = world.a[(sy + 4) * 16:(sy + 5) * 16, cz * 16:(cz + 1) * 16, cx * 16:(cx + 1) * 16].reshape(-1)
                used = np.unique(blk)
                if len(used) == 1 and used[0] == 0:
                    continue
                pal = [names[i] for i in used]
                idx = np.searchsorted(used, blk)
                bs = {"palette": [{"Name": n} for n in pal]}
                if len(pal) > 1:
                    bs["data"] = pack(idx.astype(np.uint64), max(4, int(np.ceil(np.log2(len(pal))))))
                secs.append({"Y": sy, "block_states": bs})
            out = bytearray(b"\x0a\x00\x00")
            chunk = {"Status": "minecraft:full", "xPos": rx * 32 + cx, "zPos": rz * 32 + cz, "sections": secs}
            st = (starts or {}).get((cx, cz))
            if st:
                chunk["structures"] = {"starts": {sid: {"id": sid, "ChunkX": rx * 32 + cx, "ChunkZ": rz * 32 + cz,
                                                        "Children": [{"id": "part", "BB": np.array(bb, np.int32)}]}
                                                  for sid, bb in st}}
            _payload(out, chunk)
            data = zlib.compress(bytes(out))
            rec = struct.pack(">ib", len(data) + 1, 2) + data
            rec += b"\0" * (-len(rec) % 4096)
            off = 2 + len(body) // 4096
            i = cx + cz * 32
            head[i * 4:i * 4 + 4] = struct.pack(">I", (off << 8) | (len(rec) // 4096))
            body += rec
    with open(os.path.join(region_dir, f"r.{rx}.{rz}.mca"), "wb") as f:
        f.write(head + body)


class World:
    def __init__(self, w, h):
        self.names = ["minecraft:air"]
        self.a = np.zeros((384, h, w), np.int32)

    def id(self, name):
        if name not in self.names:
            self.names.append(name)
        return self.names.index(name)

    def box(self, name, x0, y0, z0, x1, y1, z1):
        """Блоки [x0, x1) × [y0, y1) × [z0, z1)."""
        self.a[y0 + 64:y1 + 64, z0:z1, x0:x1] = self.id(name)


@unittest.skipIf(np is None, "нет numpy или pillow")
class MapStudyTest(unittest.TestCase):
    def run_tool(self, reg, out, *args):
        r = subprocess.run([sys.executable, TOOL, reg, out, "--jobs", "1", *args], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr[-3000:])
        with open(os.path.join(out, "mapstudy.json"), encoding="utf-8") as f:
            return json.load(f)

    def test_negative_region(self):
        """Регион r.-1.-1: места в мире — от его угла (−512, −512)."""
        w = World(64, 64)
        w.box("minecraft:grass_block", 0, 64, 0, 64, 65, 64)
        w.box("minecraft:white_concrete", 20, 65, 30, 26, 110, 36)
        with tempfile.TemporaryDirectory() as d:
            reg = os.path.join(d, "region")
            write_world(reg, w, 4, 4, rx=-1, rz=-1)
            st = self.run_tool(reg, os.path.join(d, "out"), "--box", "-512", "-512", "-448", "-448")
        t = st["tall"][0]
        self.assertEqual((t["x"], t["z"]), (-512 + 20, -512 + 30))
        self.assertAlmostEqual(t["height"], 45, delta=2)

    def test_city(self):
        W, H = 256, 192
        w = World(W, H)
        w.box("minecraft:stone", 0, -64, 0, W, 60, H)
        w.box("minecraft:dirt", 0, 60, 0, W, 64, H)
        w.box("minecraft:grass_block", 0, 64, 0, W, 65, H)
        # улица вдоль x (z 40..45) между двумя рядами домов высотой 20 и 15
        w.box("minecraft:black_concrete", 5, 64, 38, 175, 65, 48)
        w.box("minecraft:stone_bricks", 20, 65, 20, 160, 85, 36)
        w.box("minecraft:bricks", 20, 65, 50, 160, 80, 66)
        # высотка 50 блоков
        w.box("minecraft:white_concrete", 100, 65, 72, 108, 115, 80)
        # метро: тоннель на y 49..53 вдоль x, рельсы на 50
        w.box("minecraft:stone_bricks", 10, 48, 8, 150, 55, 14)
        w.box("minecraft:air", 10, 49, 9, 150, 54, 13)
        w.box("minecraft:rail", 10, 49, 11, 150, 50, 12)
        # станция метро: зал 26 × 21 на линии
        w.box("minecraft:stone_bricks", 60, 48, 2, 86, 57, 23)
        w.box("minecraft:air", 61, 49, 3, 85, 56, 22)
        w.box("minecraft:rail", 61, 49, 11, 85, 50, 12)
        # озеро и мост поперёк
        w.box("minecraft:water", 100, 58, 90, 192, 65, 130)
        w.box("minecraft:oak_planks", 150, 67, 88, 156, 68, 132)
        # взлётная полоса 180 × 14
        w.box("minecraft:gray_concrete", 20, 64, 150, 200, 65, 164)
        # железная дорога по земле
        w.box("minecraft:rail", 10, 65, 182, 220, 66, 183)
        # шахта генерации: рельсы в 30 блоках под землёй — не метро
        w.box("minecraft:oak_planks", 20, 28, 170, 130, 35, 177)
        w.box("minecraft:air", 20, 29, 171, 130, 34, 176)
        w.box("minecraft:rail", 20, 29, 173, 130, 30, 174)
        # шахта без начала в чанке (карта из старой версии игры): коридор с рельсами, крепь из забора и досок, паутина
        w.box("minecraft:air", 20, 24, 140, 131, 28, 145)
        w.box("minecraft:rail", 20, 24, 142, 131, 25, 143)
        for x in range(22, 131, 5):
            w.box("minecraft:oak_fence", x, 24, 140, x + 1, 27, 141)
            w.box("minecraft:oak_fence", x, 24, 144, x + 1, 27, 145)
            w.box("minecraft:oak_planks", x, 27, 140, x + 1, 28, 145)
        w.box("minecraft:cobweb", 41, 27, 141, 42, 28, 142)
        # пласт камня Create под землёй — природа, не тоннель
        w.box("create:asurine", 200, 20, 100, 240, 40, 140)
        # башня из стекла с каменной крышей: дом, а не гора
        w.box("minecraft:glass", 200, 65, 20, 208, 104, 28)
        w.box("minecraft:stone", 200, 104, 20, 208, 105, 28)
        # холм
        for r in range(0, 30):
            w.box("minecraft:stone", 10 + r // 2, 65 + r, 85 + r // 2, 50 - r // 2, 66 + r, 125 - r // 2)
            w.box("minecraft:grass_block", 10 + r // 2, 66 + r, 85 + r // 2, 50 - r // 2, 67 + r, 125 - r // 2)
        # домик на вершине холма: высота — от вершины, а не от подножия
        w.box("minecraft:oak_planks", 28, 96, 103, 32, 101, 107)
        with tempfile.TemporaryDirectory() as d:
            reg, out = os.path.join(d, "region"), os.path.join(d, "out")
            write_world(reg, w, W // 16, H // 16, starts={(1, 10): [("minecraft:mineshaft", [20, 28, 170, 129, 34, 176])]})
            r = subprocess.run([sys.executable, TOOL, reg, out, "--box", "0", "0", str(W), str(H), "--jobs", "1",
                                "--points", "улица:90,42;холм:30,105;башня:104,76", "--crops", "центр:100,60,64"],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr[-3000:])
            with open(os.path.join(out, "mapstudy.json"), encoding="utf-8") as f:
                st = json.load(f)
            for img in ("map.png", "heights.png", "infra.png", "crop-01.jpg", "crop-01-infra.jpg"):
                self.assertTrue(os.path.getsize(os.path.join(out, img)) > 0)
            wet, top = np.load(os.path.join(out, "water.npy")), np.load(os.path.join(out, "top.npy"))
            self.assertEqual(wet.shape, top.shape)
            self.assertTrue(wet[110 - st["box"][1], 130 - st["box"][0]] and not wet[42 - st["box"][1], 50 - st["box"][0]],
                            "вода для разведки мест: озеро — да, улица — нет")
        tower = [t for t in st["tall"] if 100 <= t["x"] < 108 and 72 <= t["z"] < 80]
        self.assertTrue(tower, st["tall"])
        self.assertAlmostEqual(tower[0]["height"], 50, delta=2)
        street = [s for s in st["streets"] if s["axis"] == "x" and 38 <= s["from"][1] < 48]
        self.assertTrue(street, st["streets"])
        self.assertGreaterEqual(street[0]["length"], 150)
        self.assertGreaterEqual(street[0]["sides"], 14)
        self.assertEqual(len(st["metro"]), 1, st["metro"])     # рельсы шахты — не линия метро
        self.assertGreaterEqual(st["metro"][0]["rails"], 130)
        self.assertTrue(any(m["rails"] >= 100 and m["y"][0] == 24 for m in st["mines"]), st["mines"])
        self.assertFalse(any(t["z"][0] <= 142 <= t["z"][1] and t["x"][1] - t["x"][0] > 60 for t in st["tunnels"]), st["tunnels"])
        self.assertGreater(st["metro"][0]["under"], 0.9)
        self.assertTrue(any(55 <= x["x"] <= 90 and x["line"] == 1 for x in st["stations"]), st["stations"])
        self.assertFalse(any(x["x"] < 50 or x["x"] > 100 for x in st["stations"]), st["stations"])
        self.assertTrue(any(r["extent"] >= 200 and r["y"][0] == 65 for r in st["railways"]), st["railways"])
        self.assertTrue(any(rw["length"] >= 170 and 12 <= rw["width"] <= 16 for rw in st["runways"]), st["runways"])
        self.assertFalse(any(t["x"][0] >= 190 for t in st["tunnels"]), st["tunnels"])   # пласт Create
        self.assertFalse(any(n.startswith("create:") for n, _ in st["deep_census"]), st["deep_census"])
        self.assertEqual(st["generated"], [{"id": "minecraft:mineshaft", "starts": 1, "inside_map": 1}])
        roof = [t for t in st["tall"] if 200 <= t["x"] < 208 and 20 <= t["z"] < 28]
        self.assertTrue(roof, st["tall"])
        self.assertAlmostEqual(roof[0]["height"], 40, delta=2)
        self.assertFalse(any(p["x"] >= 195 and p["z"] < 40 for p in st["peaks"]), st["peaks"])
        self.assertFalse(any(t["x"] < 50 and t["z"] > 85 for t in st["tall"]), st["tall"])
        self.assertTrue(any(b["x"][0] <= 152 <= b["x"][1] for b in st["bridges"]), st["bridges"])
        self.assertTrue(st["waters"], "озеро не найдено")
        self.assertTrue(st["districts"])
        peak = st["peaks"][0]
        self.assertTrue(abs(peak["x"] - 30) < 6 and abs(peak["z"] - 105) < 6, peak)
        pts = {p["name"]: p for p in st["points"]}
        self.assertGreater(pts["улица"]["road_128"], 0.05)
        self.assertGreaterEqual(pts["холм"]["top"], 90)
        self.assertIn(pts["башня"]["n"], pts["холм"]["sees"])


if __name__ == "__main__":
    unittest.main()
