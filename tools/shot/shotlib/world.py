"""Мир сцены — блоки рамки массивом numpy: из файлов регионов (копия карты, мир записи) или собранный тестом.

Клетка — род блока для лучей и полёта (`AIR`, `OPAQUE` — закрывает взгляд, `SOFT` — листва и стекло: видно сквозь,
но камера не пролетит, `WATER`, `UNKNOWN` — чанка нет в данных: там не летать и туда не смотреть) и цвет для чернового
кадра. Расстояние до ближайшего не-воздуха (шахматное, в блоках) — для облёта и быстрых лучей.
"""
from __future__ import annotations

import math
import os
import struct
import zlib
from dataclasses import dataclass, field

import numpy as np

AIR, OPAQUE, SOFT, WATER, UNKNOWN = 0, 1, 2, 3, 4

# ---------------------------------------------------------------- NBT (только чтение)


def _nbt_payload(buf: bytes, p: int, tag: int):
    if tag == 1:
        return struct.unpack_from(">b", buf, p)[0], p + 1
    if tag == 2:
        return struct.unpack_from(">h", buf, p)[0], p + 2
    if tag == 3:
        return struct.unpack_from(">i", buf, p)[0], p + 4
    if tag == 4:
        return struct.unpack_from(">q", buf, p)[0], p + 8
    if tag == 5:
        return struct.unpack_from(">f", buf, p)[0], p + 4
    if tag == 6:
        return struct.unpack_from(">d", buf, p)[0], p + 8
    if tag == 7:
        n = struct.unpack_from(">i", buf, p)[0]
        return buf[p + 4:p + 4 + n], p + 4 + n
    if tag == 8:
        n = struct.unpack_from(">H", buf, p)[0]
        return buf[p + 2:p + 2 + n].decode("utf-8", "replace"), p + 2 + n
    if tag == 9:
        inner, n = struct.unpack_from(">bi", buf, p)
        p += 5
        out = []
        for _ in range(max(0, n)):
            v, p = _nbt_payload(buf, p, inner)
            out.append(v)
        return out, p
    if tag == 10:
        out = {}
        while True:
            t = buf[p]
            p += 1
            if t == 0:
                return out, p
            n = struct.unpack_from(">H", buf, p)[0]
            name = buf[p + 2:p + 2 + n].decode("utf-8", "replace")
            p += 2 + n
            out[name], p = _nbt_payload(buf, p, t)
    if tag == 11:
        n = struct.unpack_from(">i", buf, p)[0]
        return np.frombuffer(buf, ">i4", n, p + 4), p + 4 + 4 * n
    if tag == 12:
        n = struct.unpack_from(">i", buf, p)[0]
        return np.frombuffer(buf, ">i8", n, p + 4), p + 4 + 8 * n
    raise ValueError(f"NBT: тег {tag}")


def nbt(buf: bytes):
    """Корневой тег (составной) файла NBT без сжатия."""
    if buf[0] != 10:
        raise ValueError("NBT: корень не составной")
    n = struct.unpack_from(">H", buf, 1)[0]
    v, _ = _nbt_payload(buf, 3 + n, 10)
    return v


def unpack(longs: np.ndarray, bits: int, count: int = 4096) -> np.ndarray:
    """Упакованный массив палитры (1.16+: значение не переходит через границу long)."""
    per = 64 // bits
    u = longs.astype(">i8").view(">u8").astype(np.uint64)
    shifts = (np.arange(per, dtype=np.uint64) * np.uint64(bits))
    vals = (u[:, None] >> shifts[None, :]) & np.uint64((1 << bits) - 1)
    return vals.reshape(-1)[:count].astype(np.int32)


# ---------------------------------------------------------------- роды и цвета блоков по имени

_THIN = ("torch", "flower", "sapling", "rail", "carpet", "button", "lever", "pressure_plate", "sign", "banner", "vine",
         "ladder", "tripwire", "redstone_wire", "redstone_torch", "cobweb", "dead_bush", "sugar_cane", "bamboo", "wheat",
         "carrots", "potatoes", "beetroots", "lily_pad", "fire", "chain", "lantern", "flower_pot", "candle", "skull",
         "head", "lichen", "moss_carpet", "snow", "string", "rose", "tulip", "orchid", "allium", "bluet", "daisy",
         "dandelion", "poppy", "cornflower", "lily_of_the_valley", "azure", "pitcher", "torchflower", "spore",
         "hanging_roots", "glow_lichen", "sculk_vein", "frogspawn", "end_rod", "lightning_rod", "tall_grass",
         "short_grass", "fern", "large_fern", "bush", "sweet_berry", "mushroom", "fungus", "roots", "sprouts",
         "cave_vines", "twisting", "weeping", "pointed_dripstone", "amethyst_bud", "amethyst_cluster", "coral_fan",
         "button", "comparator", "repeater", "daylight", "tripwire_hook", "grass")
_SOFT = ("leaves", "glass", "iron_bars", "fence", "door", "trapdoor", "scaffolding", "azalea", "mangrove_roots", "ice",
         "slime_block", "honey_block", "barrier_soft", "cactus", "bars", "grate", "chain_link", "net", "mesh")
_WATER = ("water", "bubble_column", "seagrass", "kelp")
_AIRS = ("air", "cave_air", "void_air", "light", "structure_void", "barrier", "moving_piston")

_DYES = {
    "white": (233, 236, 236), "light_gray": (142, 142, 134), "gray": (62, 68, 71), "black": (20, 21, 25),
    "brown": (114, 71, 40), "red": (160, 39, 34), "orange": (240, 118, 19), "yellow": (248, 197, 39),
    "lime": (112, 185, 25), "green": (84, 109, 27), "cyan": (21, 137, 145), "light_blue": (58, 175, 217),
    "blue": (53, 57, 157), "purple": (121, 42, 172), "magenta": (189, 68, 179), "pink": (237, 141, 172),
}
_MATS = [  # по первому совпадению
    ("grass_block", (95, 159, 53)), ("moss", (89, 109, 45)), ("leaves", (60, 110, 40)), ("water", (52, 92, 196)),
    ("lava", (230, 100, 20)), ("snow", (240, 245, 248)), ("ice", (150, 190, 240)), ("sandstone", (216, 203, 155)),
    ("red_sand", (190, 102, 33)), ("sand", (219, 207, 163)), ("gravel", (131, 127, 126)), ("clay", (160, 166, 179)),
    ("podzol", (91, 63, 24)), ("mud", (60, 57, 61)), ("dirt", (134, 96, 67)), ("farmland", (110, 75, 45)),
    ("path", (148, 122, 65)), ("deepslate", (80, 80, 82)), ("blackstone", (42, 36, 41)), ("basalt", (73, 72, 78)),
    ("andesite", (136, 136, 137)), ("diorite", (188, 188, 189)), ("granite", (149, 103, 85)), ("tuff", (108, 109, 102)),
    ("calcite", (223, 224, 220)), ("cobblestone", (122, 121, 122)), ("stone_brick", (122, 121, 122)),
    ("smooth_stone", (158, 158, 158)), ("stone", (125, 125, 125)), ("brick", (150, 97, 83)), ("quartz", (235, 229, 222)),
    ("prismarine", (99, 156, 151)), ("copper", (192, 107, 79)), ("iron", (220, 220, 220)), ("gold", (246, 208, 61)),
    ("diamond", (98, 237, 228)), ("emerald", (42, 203, 87)), ("netherrack", (97, 38, 38)), ("nether_brick", (44, 21, 26)),
    ("end_stone", (219, 222, 158)), ("obsidian", (15, 10, 24)), ("bedrock", (85, 85, 85)), ("glass", (190, 220, 230)),
    ("birch", (192, 175, 121)), ("spruce", (114, 84, 48)), ("dark_oak", (66, 43, 20)), ("jungle", (160, 115, 80)),
    ("acacia", (168, 90, 50)), ("mangrove", (117, 54, 48)), ("cherry", (226, 178, 172)), ("crimson", (101, 48, 70)),
    ("warped", (43, 104, 99)), ("oak", (162, 130, 78)), ("log", (109, 85, 50)), ("planks", (162, 130, 78)),
    ("wood", (109, 85, 50)), ("hay", (166, 136, 38)), ("terracotta", (152, 94, 67)), ("concrete", (125, 125, 125)),
    ("wool", (220, 220, 220)), ("asphalt", (40, 40, 42)), ("tile", (140, 140, 140)), ("slab", (150, 150, 150)),
]


def classify(name: str) -> tuple[int, tuple[int, int, int]]:
    """Род и цвет блока по id (`minecraft:oak_leaves`, `create:…`): только для лучей, облёта и чернового кадра."""
    n = name.split(":", 1)[-1]
    if n in _AIRS:
        return AIR, (0, 0, 0)
    if any(w in n for w in _WATER):
        return WATER, (52, 92, 196)
    kind = OPAQUE
    if n not in ("grass_block", "snow_block", "dirt_path") and any(n == w or n.endswith("_" + w) or n.startswith(w + "_") or
                                                                    ("_" + w + "_") in n for w in _THIN):
        if not any(w in n for w in ("block", "wall", "stairs", "slab", "planks", "log")):
            return AIR, (0, 0, 0)
    if any(w in n for w in _SOFT):
        kind = SOFT
    color = None
    for dye, rgb in sorted(_DYES.items(), key=lambda kv: -len(kv[0])):
        if n.startswith(dye + "_") and any(w in n for w in ("concrete", "wool", "terracotta", "glass", "carpet", "bed",
                                                              "candle", "shulker", "banner")):
            color = rgb
            if "terracotta" in n:
                color = tuple(int(c * 0.7 + 40) for c in rgb)
            break
    if color is None:
        color = next((rgb for w, rgb in _MATS if w in n), (128, 128, 128))
    return kind, color


# ---------------------------------------------------------------- мир


@dataclass
class World:
    """Блоки рамки: `kind[x, y, z]` и `color[x, y, z]` (индекс палитры цвета), начало — `origin` (мир)."""
    origin: np.ndarray
    kind: np.ndarray
    rgb: np.ndarray  # (n, 3) uint8 — цвета по индексу `col`
    col: np.ndarray  # uint16 индекс цвета в клетке
    _dist: np.ndarray | None = field(default=None, repr=False)
    _ground: np.ndarray | None = field(default=None, repr=False)
    _sky: float | None = field(default=None, repr=False)

    @property
    def shape(self):
        return self.kind.shape

    def index(self, p: np.ndarray) -> np.ndarray:
        """Клетки точек (…, 3) — целые индексы массива (могут быть вне рамки)."""
        return np.floor(np.asarray(p, dtype=np.float64) - self.origin).astype(np.int64)

    def inside(self, ijk: np.ndarray) -> np.ndarray:
        s = self.kind.shape
        return (ijk[..., 0] >= 0) & (ijk[..., 0] < s[0]) & (ijk[..., 1] >= 0) & (ijk[..., 1] < s[1]) & \
            (ijk[..., 2] >= 0) & (ijk[..., 2] < s[2])

    @property
    def sky_y(self) -> float:
        """Выше этого (мировая y) в рамке ничего нет: вне рамки по сторонам там тоже считается воздух (снаряд вдали)."""
        if self._sky is None:
            self._sky = float(self.ground.max()) + 2
        return self._sky

    def kind_at(self, p: np.ndarray, outside_above: int = AIR, outside: int = UNKNOWN) -> np.ndarray:
        """Род клетки в точках; выше рамки и вне её выше всех её блоков — воздух, вне рамки ниже — неизвестно."""
        ijk = self.index(p)
        ok = self.inside(ijk)
        out = np.full(ijk.shape[:-1], outside, dtype=np.uint8)
        above = (ijk[..., 1] >= self.kind.shape[1]) | (np.asarray(p)[..., 1] >= self.sky_y)
        out[above & ~ok] = outside_above
        c = np.clip(ijk, 0, np.array(self.kind.shape) - 1)
        out[ok] = self.kind[c[..., 0], c[..., 1], c[..., 2]][ok]
        return out

    @property
    def dist(self) -> np.ndarray:
        """Шахматное расстояние (клеток) от каждой клетки до ближайшей не-воздуха, не больше 64 (uint8)."""
        if self._dist is None:
            from scipy import ndimage
            free = self.kind == AIR
            d = ndimage.distance_transform_cdt(free, metric="chessboard")
            self._dist = np.minimum(d, 64).astype(np.uint8)
        return self._dist

    def clearance(self, p: np.ndarray) -> np.ndarray:
        """Сколько блоков воздуха вокруг точек (шахматно); вне рамки сбоку — 0, выше рамки — 64."""
        ijk = self.index(p)
        ok = self.inside(ijk)
        c = np.clip(ijk, 0, np.array(self.kind.shape) - 1)
        out = np.where(ok, self.dist[c[..., 0], c[..., 1], c[..., 2]], 0).astype(np.float64)
        above = (ijk[..., 1] >= self.kind.shape[1]) & (ijk[..., 0] >= 0) & (ijk[..., 0] < self.kind.shape[0]) & \
            (ijk[..., 2] >= 0) & (ijk[..., 2] < self.kind.shape[2])
        out[above] = 64
        return out

    @property
    def ground(self) -> np.ndarray:
        """Мировая y верха самого высокого блока в колонке (x, z); нет блоков или чанка — низ рамки."""
        if self._ground is None:
            solid = (self.kind != AIR) & (self.kind != UNKNOWN)
            ny = self.kind.shape[1]
            top = ny - 1 - np.argmax(solid[:, ::-1, :], axis=1)
            has = solid.any(axis=1)
            self._ground = np.where(has, top + 1, 0).astype(np.float64) + self.origin[1]
        return self._ground

    def ground_at(self, x: np.ndarray, z: np.ndarray) -> np.ndarray:
        i = np.clip(np.floor(np.asarray(x) - self.origin[0]).astype(np.int64), 0, self.kind.shape[0] - 1)
        k = np.clip(np.floor(np.asarray(z) - self.origin[2]).astype(np.int64), 0, self.kind.shape[2] - 1)
        return self.ground[i, k]

    def max_world(self) -> np.ndarray:
        return self.origin + np.array(self.kind.shape)


def empty(origin, size, ground_y: int | None = None, ground_kind=OPAQUE) -> World:
    """Пустая рамка (тесты): воздух, по желанию — земля до `ground_y` (мировая y, не включая)."""
    origin = np.asarray(origin, dtype=np.int64)
    kind = np.zeros(size, dtype=np.uint8)
    col = np.zeros(size, dtype=np.uint16)
    rgb = np.array([(0, 0, 0), (95, 159, 53), (125, 125, 125), (180, 180, 186), (52, 92, 196), (60, 110, 40),
                    (190, 220, 230), (150, 97, 83)], dtype=np.uint8)
    w = World(origin, kind, rgb, col)
    if ground_y is not None:
        h = int(ground_y - origin[1])
        if h > 0:
            kind[:, :h, :] = ground_kind
            col[:, :h, :] = 1
    return w


def box(w: World, lo, hi, kind=OPAQUE, color=2):
    """Залить клетки мира [lo, hi) (мировые координаты) — тестовые дома и стены."""
    lo = np.clip(np.asarray(lo) - w.origin, 0, np.array(w.shape))
    hi = np.clip(np.asarray(hi) - w.origin, 0, np.array(w.shape))
    w.kind[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]] = kind
    w.col[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]] = color
    w._dist = None
    w._ground = None
    w._sky = None


def from_regions(region_dir: str, lo, hi) -> World:
    """Блоки рамки [lo, hi) из файлов регионов (`<мир>/region`). Чанка нет — его клетки `UNKNOWN`."""
    lo = np.asarray(lo, dtype=np.int64)
    hi = np.asarray(hi, dtype=np.int64)
    size = tuple(int(v) for v in hi - lo)
    kind = np.full(size, UNKNOWN, dtype=np.uint8)
    col = np.zeros(size, dtype=np.uint16)
    names: dict[str, int] = {}
    kinds: list[int] = []
    colors: list[tuple] = []

    def palette_index(name: str) -> int:
        i = names.get(name)
        if i is None:
            k, c = classify(name)
            i = len(kinds)
            names[name] = i
            kinds.append(k)
            colors.append(c)
        return i

    regions: dict[tuple, bytes] = {}
    for cx in range(lo[0] >> 4, ((hi[0] - 1) >> 4) + 1):
        for cz in range(lo[2] >> 4, ((hi[2] - 1) >> 4) + 1):
            key = (cx >> 5, cz >> 5)
            if key not in regions:
                path = os.path.join(region_dir, f"r.{key[0]}.{key[1]}.mca")
                regions[key] = b""
                if os.path.exists(path):
                    with open(path, "rb") as f:
                        regions[key] = f.read()
            data = regions[key]
            if len(data) < 8192:
                continue
            i = (cx & 31) + (cz & 31) * 32
            off = int.from_bytes(data[i * 4:i * 4 + 3], "big")
            if not off or not data[i * 4 + 3]:
                continue
            p = off * 4096
            ln = struct.unpack_from(">i", data, p)[0]
            if data[p + 4] != 2:
                continue
            ch = nbt(zlib.decompress(data[p + 5:p + 4 + ln]))
            status = ch.get("Status", "minecraft:full")
            if not str(status).endswith("full"):
                continue
            # клетки колонки в рамке
            x0, z0 = cx * 16, cz * 16
            ax0, ax1 = max(x0, lo[0]), min(x0 + 16, hi[0])
            az0, az1 = max(z0, lo[2]), min(z0 + 16, hi[2])
            kind[ax0 - lo[0]:ax1 - lo[0], :, az0 - lo[2]:az1 - lo[2]] = AIR
            for s in ch.get("sections", []):
                sy = s["Y"] * 16
                if sy + 16 <= lo[1] or sy >= hi[1]:
                    continue
                bs = s.get("block_states")
                if not bs:
                    continue
                pal = np.array([palette_index(e["Name"]) for e in bs["palette"]], dtype=np.int32)
                if len(pal) == 1:
                    arr = np.full(4096, pal[0], dtype=np.int32)
                else:
                    bits = max(4, math.ceil(math.log2(len(pal))))
                    arr = pal[unpack(bs["data"], bits)]
                cube = arr.reshape(16, 16, 16).transpose(2, 0, 1)  # [x, y, z] из [y, z, x]
                ay0, ay1 = max(sy, lo[1]), min(sy + 16, hi[1])
                sub = cube[ax0 - x0:ax1 - x0, ay0 - sy:ay1 - sy, az0 - z0:az1 - z0]
                kinds_arr = np.array(kinds, dtype=np.uint8)
                kind[ax0 - lo[0]:ax1 - lo[0], ay0 - lo[1]:ay1 - lo[1], az0 - lo[2]:az1 - lo[2]] = kinds_arr[sub]
                col[ax0 - lo[0]:ax1 - lo[0], ay0 - lo[1]:ay1 - lo[1], az0 - lo[2]:az1 - lo[2]] = sub
    rgb = np.array(colors or [(0, 0, 0)], dtype=np.uint8)
    return World(lo, kind, rgb, col)


def save(w: World, path: str):
    np.savez_compressed(path, origin=w.origin, kind=w.kind, col=w.col, rgb=w.rgb)


def load(path: str) -> World:
    z = np.load(path)
    return World(z["origin"], z["kind"], z["rgb"], z["col"])
