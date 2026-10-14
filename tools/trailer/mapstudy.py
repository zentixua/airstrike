#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""География и устройство карты по файлам регионов: районы, высотки, улицы-каньоны, дороги, вода, мосты, метро и
рельсы, взлётные полосы, горы и виды с них — то, по чему ставятся сцены трейлера, а не на глаз.

    uv run tools/trailer/mapstudy.py <region_dir> <out_dir> [--box x0 z0 x1 z1] [--map x0 z0 x1 z1]
        [--points "имя:x,z;…"] [--crops "имя:x,z,r;…"] [--jobs N]

Читает каждый полный чанк (формат 1.18+) один раз, в процессах по регионам: верх колонки (любой блок, твёрдый без
листвы и травы), блок поверхности, верх воды, рельсы, искусственные блоки по слоям в 4 блока. Земля — нижняя огибающая
твёрдого верха (раскрытие окном 65 блоков: дома уже его уходят, рельеф остаётся; там, где сверху природный блок, земля —
он сам), высота строения — верх минус земля. Дальше — numpy по карте целиком:
- районы: клетки 32×32, где застроено не меньше четверти колонок (строение от 4 блоков), связные — один район;
- высотки: вершины строений от 30 блоков над землёй, по одной на 40 блоков;
- улицы-каньоны: прямые мощёные полосы (искусственный верх у земли) вдоль x или z от 100 блоков, по обе стороны —
  дома; оценка — медиана меньшей из двух сторон;
- взлётные полосы: прямые мощёные полосы от 150 блоков шириной от 12;
- вода и глубина; мосты — искусственный настил над водой; метро — рельсы ниже земли на 4+ блока (станции — подземный
  зал шире тоннеля), железные дороги по земле отдельно; шахты и другие постройки генерации мира — по рамкам их частей
  в чанках, мимо метро и застройки; шахты, у которых рамок нет (карта собрана в старой версии игры), — по секциям с
  паутиной или крепью шахты (деревянный забор и доски) под землёй;
- горы: вершины рельефа, их превышение над округой и видны ли с них центры районов (луч по карте высот);
- точки: что вокруг и какие другие точки и районы с неё видны; вырезки — 1 блок на пиксель.
Пишет `map.png` (цвет сверху с тенью, сетка 256, точки), `heights.png` (строения по высоте), `infra.png` (дороги, вода,
мосты, рельсы, метро, станции, полосы), `crop-NN.jpg` и `crop-NN-infra.jpg`, `mapstudy.json` (всё числами),
`mapstudy.md` (сводка, в конце — проверка разбора: постройки генерации и что не попало в природное) и высоты `top.npy`,
`ground.npy`, `water.npy` (вода сверху) для разведки мест (`scout.py`). Картинки — 2 блока на пиксель, подписи — шрифтом с кириллицей, если он есть.
"""
import argparse
import glob
import json
import math
import os
import re
import struct
import sys
import time
import zlib
from multiprocessing import Pool

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from zmap import color, nbt, unpack  # noqa: E402

MIN_Y = -64
SLABS = 96            # слои по 4 блока, y −64…320
SLAB_BYTES = SLABS // 8

# природное: рельеф, вода, растения, деревья — всё, чего строитель не ставил (дерево домов из досок — не природа)
NATURAL = re.compile(
    r"^minecraft:(air|cave_air|void_air|water|lava|bubble_column|grass_block|dirt|coarse_dirt|podzol|mycelium|rooted_dirt|"
    r"mud|clay|gravel|sand|red_sand|sandstone|red_sandstone|stone|deepslate|granite|diorite|andesite|tuff|calcite|"
    r"dripstone_block|pointed_dripstone|bedrock|snow|snow_block|powder_snow|ice|packed_ice|blue_ice|.*_ore|"
    r"amethyst_block|budding_amethyst|.*amethyst_bud|amethyst_cluster|moss_block|moss_carpet|.*_leaves|.*_log|vine|"
    r"short_grass|tall_grass|fern|large_fern|dandelion|poppy|blue_orchid|allium|azure_bluet|.*_tulip|oxeye_daisy|"
    r"cornflower|lily_of_the_valley|sunflower|lilac|rose_bush|peony|.*_sapling|kelp|kelp_plant|seagrass|tall_seagrass|"
    r"sea_pickle|.*coral.*|lily_pad|sugar_cane|cactus|dead_bush|sweet_berry_bush|bamboo|brown_mushroom|red_mushroom|"
    r"glow_lichen|hanging_roots|azalea|flowering_azalea|cave_vines.*|big_dripleaf.*|small_dripleaf|spore_blossom|"
    r"terracotta|(white|orange|yellow|red|brown|light_gray)_terracotta|obsidian|magma_block|infested_.*|smooth_basalt|"
    r"pink_petals|mangrove_roots|muddy_mangrove_roots|weeping_vines.*|twisting_vines.*|mangrove_propagule|bee_nest|"
    r"brown_mushroom_block|red_mushroom_block|mushroom_stem|raw_iron_block|raw_copper_block|sculk|sculk_vein|"
    r"sculk_sensor|sculk_catalyst|sculk_shrieker|suspicious_sand|suspicious_gravel|frogspawn|torchflower|pitcher_plant)$"
    # руды и камни генерации модов (у Create — пласты asurine, crimsite… кругами под землёй)
    r"|^[a-z0-9_.-]+:(deepslate_)?[a-z_]*_ore$|^create:(asurine|crimsite|limestone|ochrum|scoria|scorchia|veridium)$")
# не верх колонки: воздух, жидкость, листва, трава и цветы, мелочь на поверхности
SOFT = re.compile(
    r"^minecraft:(air|cave_air|void_air|water|lava|bubble_column|.*_leaves|vine|short_grass|tall_grass|fern|large_fern|"
    r"dandelion|poppy|blue_orchid|allium|azure_bluet|.*_tulip|oxeye_daisy|cornflower|lily_of_the_valley|sunflower|lilac|"
    r"rose_bush|peony|.*_sapling|kelp|kelp_plant|seagrass|tall_seagrass|sea_pickle|lily_pad|sugar_cane|dead_bush|"
    r"sweet_berry_bush|brown_mushroom|red_mushroom|glow_lichen|snow|cobweb|.*torch|.*_button|.*_sign|.*_banner|"
    r"pink_petals|moss_carpet|.*_carpet|cave_vines.*|weeping_vines.*|twisting_vines.*)$")
AIRISH = re.compile(r"^minecraft:(air|cave_air|void_air)$")
RUNWAY_LEN, RUNWAY_WIDTH = 150, 12
# постройки генерации под землёй: их рельсы и стены — не метро
GEN_UNDER = ("mineshaft", "ancient_city", "stronghold", "trial_chambers", "buried_treasure", "monument")
# крепь шахты генерации: стойки — деревянный забор, балка — доски (MineshaftPieces); паутина — в её коридорах. У Newisle
# начал шахт в чанках нет (по ним — gen_under) там, где карта собрана в старой версии игры: в первом проходе (14.10)
# коридоры шахт у Города 2 вышли линиями метро на 30–60 блоков под землёй
MINE_FENCE = re.compile(r"^minecraft:(oak|dark_oak|spruce|birch|jungle|acacia|mangrove|cherry)_fence$")
MINE_PLANKS = re.compile(r"^minecraft:[a-z_]+_planks$")
NSEC = (320 - MIN_Y) // 16
WATER = re.compile(r"^minecraft:(water|bubble_column|kelp|kelp_plant|seagrass|tall_seagrass)$")
RAIL = re.compile(r"(^minecraft:(rail|powered_rail|detector_rail|activator_rail)$|^create:track$)")
# мостовая: тёмное и серое — асфальт и тротуар, белое и жёлтое — разметка
ROADISH = re.compile(r"(black|gray|coal_block|blackstone|basalt|andesite|deepslate_tile|polished_deepslate|"
                     r"yellow_concrete|white_concrete|stone_brick|smooth_stone|cobblestone|tuff)")


_FLAGS = {}


def flags_of(names):
    """(природное, не верх, воздух, вода, рельс) для палитры; по имени — один раз на процесс (палитры города — сотни имён)."""
    rows = []
    for n in names:
        f = _FLAGS.get(n)
        if f is None:
            f = _FLAGS[n] = (bool(NATURAL.match(n)), bool(SOFT.match(n)), bool(AIRISH.match(n)), bool(WATER.match(n)),
                             bool(RAIL.search(n)))
        rows.append(f)
    a = np.array(rows, bool).reshape(-1, 5)
    return a[:, 0], a[:, 1], a[:, 2], a[:, 3], a[:, 4]


def region_chunks(path):
    data = open(path, "rb").read()
    if len(data) < 8192:
        return
    for i in range(1024):
        off = int.from_bytes(data[i * 4:i * 4 + 3], "big")
        cnt = data[i * 4 + 3]
        if not off or not cnt:
            continue
        p = off * 4096
        ln = struct.unpack_from(">i", data, p)[0]
        if data[p + 4] != 2:
            continue
        try:
            yield nbt(zlib.decompress(data[p + 5:p + 4 + ln]))
        except Exception:  # битый чанк — мимо
            continue


def top_index(mask):
    """mask [y][z][x] → самый верхний y с True в колонке (16, 16) или −1."""
    any_ = mask.any(axis=0)
    top = 15 - np.argmax(mask[::-1], axis=0)
    return np.where(any_, top, -1)


def read_region(args):
    """Колонки региона 512×512: верх любой, верх твёрдый, блок поверхности (номер в своих именах), верх воды, слои
    искусственного по 4 блока (биты), рельсы [(x, y, z)], части построек генерации [(id, x0, y0, z0, x1, y1, z1)],
    счёт искусственных блоков глубоко под землёй (ниже y 40) по именам — что ещё природное, а не стройка, и секции с
    приметами шахты [чанк z][чанк x][секция] (паутина или забор с досками)."""
    path, rx, rz = args
    n = 512
    top_any = np.full((n, n), MIN_Y - 1, np.int16)
    top_solid = np.full((n, n), MIN_Y - 1, np.int16)
    water = np.full((n, n), MIN_Y - 1, np.int16)
    surf = np.zeros((n, n), np.int32)
    upper = np.zeros((n, n), np.int32)
    slabs = np.zeros((n, n, SLAB_BYTES), np.uint8)
    have = np.zeros((n, n), bool)
    mines = np.zeros((32, 32, NSEC), bool)
    rails, structs, deep = [], [], {}
    names, ids = ["minecraft:air"], {"minecraft:air": 0}
    for ch in region_chunks(path):
        if str(ch.get("Status", "")).split(":")[-1] != "full":
            continue
        cx, cz = ch.get("xPos"), ch.get("zPos")
        if cx is None:
            continue
        lx0, lz0 = (cx - rx * 32) * 16, (cz - rz * 32) * 16
        if not (0 <= lx0 < n and 0 <= lz0 < n):
            continue
        sl = (slice(lz0, lz0 + 16), slice(lx0, lx0 + 16))
        have[sl] = True
        # постройки генерации мира (шахты, монументы, деревни): рамки частей — в чанке начала постройки
        for st in (ch.get("structures") or {}).get("starts", {}).values():
            if not isinstance(st, dict) or st.get("id") in (None, "INVALID"):
                continue
            for c in st.get("Children", []):
                bb = c.get("BB") if isinstance(c, dict) else None
                if bb is not None and len(bb) == 6:
                    structs.append((str(st["id"]), int(st.get("ChunkX", cx)), int(st.get("ChunkZ", cz)), *(int(v) for v in bb)))
        secs = sorted((s for s in ch.get("sections", []) if "block_states" in s), key=lambda s: -s["Y"])
        ta, ts, tw = top_any[sl], top_solid[sl], water[sl]
        sid, uid = surf[sl], upper[sl]
        for s in secs:
            pal = [p["Name"] for p in s["block_states"]["palette"]]
            nat, soft, air, wat, rail = flags_of(pal)
            if air.all():
                continue
            if len(pal) == 1:
                idx = np.zeros((16, 16, 16), np.int64)
            else:
                bits = max(4, math.ceil(math.log2(len(pal))))
                idx = unpack(s["block_states"]["data"], bits, 4096).reshape(16, 16, 16)
            y0 = s["Y"] * 16
            if 0 <= s["Y"] + 4 < NSEC and ("minecraft:cobweb" in pal or (any(MINE_FENCE.match(p) for p in pal)
                                                                        and any(MINE_PLANKS.match(p) for p in pal))):
                mines[lz0 // 16, lx0 // 16, s["Y"] + 4] = True
            for p in pal:
                if p not in ids:
                    ids[p] = len(names)
                    names.append(p)
            gid = np.array([ids[p] for p in pal], np.int32)
            # верх любой и твёрдый: по секциям сверху вниз, первая непустая решает
            for mask, out, name_out in ((~air[idx], ta, uid), (~soft[idx], ts, sid)):
                t = top_index(mask)
                new = (t >= 0) & (out < MIN_Y)
                if new.any():
                    zz, xx = np.nonzero(new)
                    out[zz, xx] = y0 + t[zz, xx]
                    name_out[zz, xx] = gid[idx[t[zz, xx], zz, xx]]
            if wat.any():
                t = top_index(wat[idx])
                new = (t >= 0) & (tw < MIN_Y)
                tw[new] = (y0 + t)[new]
            mm = ~nat[idx]
            if s["Y"] * 16 + 15 < 40 and (~nat).any():
                cnt = np.bincount(idx.ravel(), minlength=len(pal))
                for j in np.flatnonzero(~nat & (cnt > 0)):
                    deep[pal[j]] = deep.get(pal[j], 0) + int(cnt[j])
            if mm.any():
                sb = mm.reshape(4, 4, 16, 16).any(axis=1)      # [слой][z][x]
                s0 = (s["Y"] + 4) * 4
                if 0 <= s0 < SLABS:
                    byte, shift = s0 // 8, s0 % 8
                    val = np.zeros((16, 16), np.uint8)
                    for k in range(4):
                        val |= (sb[k].astype(np.uint8) << (shift + k))
                    slabs[lz0:lz0 + 16, lx0:lx0 + 16, byte] |= val
            if rail.any():
                yy, zz, xx = np.nonzero(rail[idx])
                for y, z, x in zip(yy, zz, xx):
                    rails.append((rx * 512 + lx0 + int(x), y0 + int(y), rz * 512 + lz0 + int(z)))
    return rx, rz, have, top_any, top_solid, water, surf, upper, slabs, rails, names, structs, deep, mines


def sliding(a, k, axis, fn):
    """Скользящий min/max окном k (нечётное) вдоль оси, края — повтором крайнего."""
    r = k // 2
    pad = [(0, 0), (0, 0)]
    pad[axis] = (r, r)
    p = np.pad(a, pad, mode="edge")
    out = None
    for i in range(k):
        s = p[i:i + a.shape[0], :] if axis == 0 else p[:, i:i + a.shape[1]]
        out = s.copy() if out is None else fn(out, s)
    return out


def box_mean(a, k):
    r = k // 2
    p = np.pad(a.astype(np.float64), r, mode="edge")
    c = p.cumsum(0).cumsum(1)
    c = np.pad(c, ((1, 0), (1, 0)))
    s = c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]
    return s / (k * k)


def runs(mask, min_len, gap=3):
    """Отрезки True вдоль каждой строки: [(строка, от, до)] не короче min_len, разрывы до gap склеиваются."""
    out = []
    for r in range(mask.shape[0]):
        row = mask[r]
        idx = np.flatnonzero(row)
        if len(idx) < min_len:
            continue
        br = np.flatnonzero(np.diff(idx) > gap + 1)
        starts = np.concatenate(([0], br + 1))
        ends = np.concatenate((br, [len(idx) - 1]))
        for a, b in zip(starts, ends):
            if idx[b] - idx[a] + 1 >= min_len:
                out.append((r, int(idx[a]), int(idx[b])))
    return out


def components(mask):
    """Связные области (8 соседей): метки и число областей — без scipy, обходом по очереди."""
    lab = np.zeros(mask.shape, np.int32)
    n = 0
    H, W = mask.shape
    for z0, x0 in zip(*np.nonzero(mask)):
        if lab[z0, x0]:
            continue
        n += 1
        stack = [(z0, x0)]
        lab[z0, x0] = n
        while stack:
            z, x = stack.pop()
            for dz in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    zz, xx = z + dz, x + dx
                    if 0 <= zz < H and 0 <= xx < W and mask[zz, xx] and not lab[zz, xx]:
                        lab[zz, xx] = n
                        stack.append((zz, xx))
    return lab, n


def sight(top, x0, z0, a, b, eye=2.0, step=4.0):
    """Виден ли верх точки b из точки a над картой высот top (1 блок на клетку): луч по клеткам с шагом step."""
    (ax, az, ay), (bx, bz, by) = a, b
    d = math.hypot(bx - ax, bz - az)
    n = max(2, int(d / step))
    for i in range(1, n):
        k = i / n
        x, z = ax + (bx - ax) * k, az + (bz - az) * k
        y = ay + eye + (by - ay - eye) * k
        xi, zi = int(x) - x0, int(z) - z0
        if 0 <= zi < top.shape[0] and 0 <= xi < top.shape[1] and top[zi, xi] > y + 1:
            return False
    return True


def label_font(size):
    """Шрифт с кириллицей для подписей; нет — встроенный (без кириллицы), второе значение — есть ли кириллица."""
    pats = ["/usr/share/fonts/**/DejaVuSans.ttf", "/usr/share/fonts/**/NotoSans-Regular.ttf",
            "/usr/share/fonts/**/LiberationSans-Regular.ttf", "/usr/share/fonts/**/FreeSans.ttf"]
    for pat in pats:
        for f in glob.glob(pat, recursive=True):
            try:
                return ImageFont.truetype(f, size), True
            except OSError:
                continue
    return ImageFont.load_default(), False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("region")
    ap.add_argument("out")
    ap.add_argument("--box", type=int, nargs=4, metavar=("X0", "Z0", "X1", "Z1"), help="рамка изучения, блоки")
    ap.add_argument("--points", default="", help="точки «имя:x,z;имя:x,z» — сводка вокруг каждой и метки на картах")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    ap.add_argument("--scale", type=int, default=2)
    ap.add_argument("--map", type=int, nargs=4, metavar=("X0", "Z0", "X1", "Z1"),
                    help="рамка самой карты (дальше — сгенерированный рельеф): расстояние точек до её края")
    ap.add_argument("--crops", default="", help="вырезки «имя:x,z,r;…» — квадрат ±r блоков, 1 блок на пиксель (crop-NN.jpg)")
    a = ap.parse_args()
    t_start = time.time()
    os.makedirs(a.out, exist_ok=True)
    files = []
    for f in glob.glob(os.path.join(a.region, "r.*.*.mca")):
        m = re.match(r"r\.(-?\d+)\.(-?\d+)\.mca$", os.path.basename(f))
        if not m:
            continue
        rx, rz = int(m[1]), int(m[2])
        if a.box and (rx * 512 + 512 <= a.box[0] or rx * 512 >= a.box[2] or rz * 512 + 512 <= a.box[1] or rz * 512 >= a.box[3]):
            continue
        files.append((f, rx, rz))
    if not files:
        sys.exit("нет регионов")
    x0 = a.box[0] if a.box else min(r for _, r, _ in files) * 512
    z0 = a.box[1] if a.box else min(r for _, _, r in files) * 512
    x1 = a.box[2] if a.box else max(r for _, r, _ in files) * 512 + 512
    z1 = a.box[3] if a.box else max(r for _, _, r in files) * 512 + 512
    W, H = x1 - x0, z1 - z0
    have = np.zeros((H, W), bool)
    top_any = np.full((H, W), MIN_Y - 1, np.int16)
    top = np.full((H, W), MIN_Y - 1, np.int16)
    water = np.full((H, W), MIN_Y - 1, np.int16)
    surf = np.zeros((H, W), np.int32)
    upper = np.zeros((H, W), np.int32)
    slabs = np.zeros((H, W, SLAB_BYTES), np.uint8)
    cx_min, cz_min = x0 >> 4, z0 >> 4
    mine = np.zeros((((z1 - 1) >> 4) - cz_min + 1, ((x1 - 1) >> 4) - cx_min + 1, NSEC), bool)   # секции с приметами шахты
    rails, structs, deep = [], [], {}
    names, gids = ["minecraft:air"], {"minecraft:air": 0}
    with Pool(a.jobs) as pool:
        for k, res in enumerate(pool.imap_unordered(read_region, files)):
            rx, rz, hv, ta, ts, tw, sf, up, sb, rl, nm, stc, dp, mn = res
            structs += stc
            ci0, cj0 = rz * 32 - cz_min, rx * 32 - cx_min
            si, sj = max(0, -ci0), max(0, -cj0)
            ei, ej = min(32, mine.shape[0] - ci0), min(32, mine.shape[1] - cj0)
            if ei > si and ej > sj:
                mine[ci0 + si:ci0 + ei, cj0 + sj:cj0 + ej] |= mn[si:ei, sj:ej]
            for n_, c_ in dp.items():
                deep[n_] = deep.get(n_, 0) + c_
            for n in nm:
                if n not in gids:
                    gids[n] = len(names)
                    names.append(n)
            remap = np.array([gids[n] for n in nm], np.int32)
            gx0, gz0 = rx * 512, rz * 512
            ax0, az0 = max(gx0, x0), max(gz0, z0)
            ax1, az1 = min(gx0 + 512, x1), min(gz0 + 512, z1)
            if ax1 <= ax0 or az1 <= az0:
                continue
            src = (slice(az0 - gz0, az1 - gz0), slice(ax0 - gx0, ax1 - gx0))
            dst = (slice(az0 - z0, az1 - z0), slice(ax0 - x0, ax1 - x0))
            have[dst] |= hv[src]
            top_any[dst] = np.where(hv[src], ta[src], top_any[dst])
            top[dst] = np.where(hv[src], ts[src], top[dst])
            water[dst] = np.where(hv[src], tw[src], water[dst])
            surf[dst] = np.where(hv[src], remap[sf[src]], surf[dst])
            upper[dst] = np.where(hv[src], remap[up[src]], upper[dst])
            slabs[dst] |= sb[src]
            rails += [r for r in rl if x0 <= r[0] < x1 and z0 <= r[2] < z1]
            print(f"  регион {rx},{rz} ({k + 1}/{len(files)})", file=sys.stderr, flush=True)
    names = np.array(names)
    nat_name = np.array([bool(NATURAL.match(n)) for n in names])
    road_name = np.array([bool(ROADISH.search(n)) and not NATURAL.match(n) for n in names])
    top_f = np.where(have, top, np.int16(MIN_Y)).astype(np.float32)

    # ---------------- постройки генерации мира
    # подземные (шахты с рельсами и паутиной, древние города) — мимо метро и тоннелей везде; наземные (деревни,
    # монументы) — мимо застройки вне самой карты: внутри неё рамка начала постройки могла остаться от прежнего рельефа
    mx0, mz0, mx1, mz1 = a.map or (x0, z0, x1, z1)
    gen_under = np.zeros((H, W), bool)
    gen_surface = np.zeros((H, W), bool)
    gen = {}
    for sid_, scx, scz, bx0, by0, bz0, bx1, by1, bz1 in structs:
        inside = mx0 <= scx * 16 + 8 < mx1 and mz0 <= scz * 16 + 8 < mz1
        g_ = gen.setdefault(sid_, {"starts": set(), "inside_map": set()})
        g_["starts"].add((scx, scz))
        if inside:
            g_["inside_map"].add((scx, scz))
        cx0, cz0 = max(bx0, x0) - x0, max(bz0, z0) - z0
        cx1, cz1 = min(bx1 + 1, x1) - x0, min(bz1 + 1, z1) - z0
        if cx1 <= cx0 or cz1 <= cz0:
            continue
        if any(u in sid_ for u in GEN_UNDER):
            gen_under[cz0:cz1, cx0:cx1] = True
        elif not inside:
            gen_surface[cz0:cz1, cx0:cx1] = True
    gen_list = sorted(({"id": k, "starts": len(v["starts"]), "inside_map": len(v["inside_map"])} for k, v in gen.items()),
                      key=lambda g_: -g_["starts"])

    # ---------------- земля и строения
    natural_top = nat_name[surf] & have
    opened = sliding(sliding(top_f, 65, 0, np.minimum), 65, 1, np.minimum)
    opened = sliding(sliding(opened, 65, 0, np.maximum), 65, 1, np.maximum)
    ks = [k for k in range(SLABS) if ((slabs[..., k // 8] >> (k % 8)) & 1).any()]

    def layer(k):
        return ((slabs[..., k // 8] >> (k % 8)) & 1).astype(bool)

    def man_made_between(lo_y, hi_y):
        """Есть ли искусственное в слоях колонки между lo_y (выше) и hi_y (до)."""
        m = np.zeros((H, W), bool)
        for k in ks:
            lo = MIN_Y + k * 4
            m |= layer(k) & (lo + 3 > lo_y) & (lo <= hi_y)
        return m
    # природный верх — сама земля, кроме крыши из природного блока (камень, андезит, терракота) над искусственным
    # в той же колонке и стволов деревьев: там земля — огибающая
    # (искусственное под самой крышей, не тоннель глубоко в горе)
    log_name = np.array([bool(re.search(r"_(log|wood|stem)$", n)) for n in names])
    roofed = man_made_between(np.maximum(opened + 1, top_f - 6), top_f)
    soil = natural_top & ~roofed & ~log_name[surf]
    # под домом — средняя природная земля в 16 блоках вокруг, где она есть (дом на вершине горы не встаёт на
    # огибающую ниже вершины), иначе огибающая (центр города)
    w_ = box_mean(soil, 33)
    near = box_mean(np.where(soil, top_f, 0), 33) / np.maximum(w_, 1e-6)
    under_house = np.where(w_ > 0.05, np.maximum(opened, near), opened)
    ground = np.where(soil, top_f, np.minimum(top_f, under_house))
    ground = np.minimum(ground, box_mean(ground, 9) + 2)
    bh = np.where(have, top_f - ground, 0)
    wet = have & (water >= top) & (water > MIN_Y)
    wet_depth = np.where(wet, water - top, 0)
    # подземное искусственное (ниже земли на 4+ блока) и стройка выше земли под природной крышей; секции с приметами
    # шахты под землёй — не тоннели
    zi, xi = ((z0 + np.arange(H)) >> 4) - cz_min, ((x0 + np.arange(W)) >> 4) - cx_min
    mine_cols = {}

    def mine_at(s_):
        if s_ not in mine_cols:
            mine_cols[s_] = mine[:, :, s_][np.ix_(zi, xi)]
        return mine_cols[s_]
    under = np.zeros((H, W), bool)
    for k in ks:
        under |= layer(k) & (MIN_Y + k * 4 + 3 < ground - 4) & ~mine_at(k // 4)
    mine_cols.clear()
    mm_above = man_made_between(ground + 1, top_f)
    under &= have & ~gen_under
    built = have & (bh >= 4) & (~natural_top | mm_above) & ~gen_surface
    paved = have & ~natural_top & (bh <= 1.5) & ~wet
    road = paved & road_name[surf]
    # мост: искусственный настил над водой (вода ниже верха не глубже 40 блоков)
    bridge = have & ~natural_top & (water > MIN_Y) & (water < top) & (top - water <= 40) & ~wet

    # ---------------- районы по клеткам 32×32
    T = 32
    th, tw_ = H // T, W // T
    def tiles(m):
        return m[:th * T, :tw_ * T].reshape(th, T, tw_, T).mean(axis=(1, 3))
    built_t, road_t, wet_t, have_t = tiles(built), tiles(road), tiles(wet), tiles(have)
    bh_t = np.where(built, bh, 0)[:th * T, :tw_ * T].reshape(th, T, tw_, T)
    max_t = bh_t.max(axis=(1, 3))
    lab, nd = components(built_t >= 0.25)
    districts = []
    for i in range(1, nd + 1):
        zz, xx = np.nonzero(lab == i)
        if len(zz) < 4:
            continue
        sel = np.zeros((H, W), bool)
        for z, x in zip(zz, xx):
            sel[z * T:(z + 1) * T, x * T:(x + 1) * T] = True
        hb = bh[sel & built]
        districts.append({
            "label": i, "id": len(districts) + 1, "tiles": int(len(zz)), "area_km2": round(len(zz) * T * T / 1e6, 3),
            "x": [int(x0 + xx.min() * T), int(x0 + (xx.max() + 1) * T)], "z": [int(z0 + zz.min() * T), int(z0 + (zz.max() + 1) * T)],
            "centre": [int(x0 + (xx.mean() + 0.5) * T), int(z0 + (zz.mean() + 0.5) * T)],
            "built": round(float(built_t[lab == i].mean()), 2), "road": round(float(road_t[lab == i].mean()), 2),
            "height_median": round(float(np.median(hb)), 1) if len(hb) else 0, "height_max": int(hb.max()) if len(hb) else 0,
            "tall_30": 0, "ground": round(float(np.median(ground[sel])), 1)})
    districts.sort(key=lambda d: -d["tiles"])
    for k, d in enumerate(districts):
        d["id"] = k + 1
    dist_map = np.zeros((th, tw_), np.int32)
    for d in districts:
        dist_map[lab == d.pop("label")] = d["id"]

    def district_at(x, z):
        tz, tx = (z - z0) // T, (x - x0) // T
        if 0 <= tz < th and 0 <= tx < tw_ and dist_map[tz, tx]:
            return int(dist_map[tz, tx])
        best, bd = None, 1e9
        for d in districts:
            dd = math.hypot(d["centre"][0] - x, d["centre"][1] - z)
            if dd < bd:
                best, bd = d["id"], dd
        return best

    # ---------------- высотки
    tall = []
    hb_m = np.where(built, bh, 0).copy()
    for _ in range(80):
        i = int(np.argmax(hb_m))
        z, x = divmod(i, W)
        if hb_m[z, x] < 30:
            break
        tall.append({"x": int(x0 + x), "z": int(z0 + z), "top": int(top[z, x]), "height": int(bh[z, x]),
                     "ground": int(ground[z, x]), "district": district_at(int(x0 + x), int(z0 + z)),
                     "roof": str(names[surf[z, x]])})
        hb_m[max(0, z - 20):z + 20, max(0, x - 20):x + 20] = 0
    for d in districts:
        d["tall_30"] = sum(1 for t in tall if t["district"] == d["id"])

    # ---------------- улицы-каньоны и полосы
    def side_height(rows_along_x, r, a_, b_):
        """Медиана меньшей из сторон: высота строений в 3–28 блоках по обе стороны полосы."""
        hs = []
        src = bh if rows_along_x else bh.T
        bl = built if rows_along_x else built.T
        for c in range(a_, b_ + 1, 3):
            lo = src[max(0, r - 28):max(0, r - 3), c][bl[max(0, r - 28):max(0, r - 3), c]]
            hi = src[r + 3:r + 28, c][bl[r + 3:r + 28, c]]
            hs.append(min(lo.max() if len(lo) else 0, hi.max() if len(hi) else 0))
        return float(np.median(hs)) if hs else 0.0

    streets = []
    for along_x, m in ((True, road), (False, road.T)):
        cand = runs(m, 100)
        for r, s, e in cand:
            sh = side_height(along_x, r, s, e)
            if sh < 6:
                continue
            if along_x:
                st = {"from": [int(x0 + s), int(z0 + r)], "to": [int(x0 + e), int(z0 + r)]}
            else:
                st = {"from": [int(x0 + r), int(z0 + s)], "to": [int(x0 + r), int(z0 + e)]}
            st.update(length=e - s + 1, sides=round(sh, 1), axis="x" if along_x else "z")
            streets.append(st)
    # одна улица — несколько соседних строк полосы: оставить лучшую в пучке
    streets.sort(key=lambda s: -(s["sides"] * min(s["length"], 400)))
    kept = []
    for s in streets:
        if any(s["axis"] == k["axis"] and abs((s["from"][1] - k["from"][1]) if s["axis"] == "x" else (s["from"][0] - k["from"][0])) <= 14
               and min(s["to"][0 if s["axis"] == "x" else 1], k["to"][0 if s["axis"] == "x" else 1])
               - max(s["from"][0 if s["axis"] == "x" else 1], k["from"][0 if s["axis"] == "x" else 1]) > 0 for k in kept):
            continue
        s["district"] = district_at((s["from"][0] + s["to"][0]) // 2, (s["from"][1] + s["to"][1]) // 2)
        kept.append(s)
    streets = kept[:40]
    # широкие прямые полосы: взлётные (у аэропорта карты — около 180 × 15) и шоссе
    runways = []
    for along_x, m in ((True, paved), (False, paved.T)):
        rr = runs(m, RUNWAY_LEN, gap=2)
        rr.sort()
        groups = []
        for r, s, e in rr:
            g = next((g for g in groups if r - g["r1"] <= 1 and min(e, g["e"]) - max(s, g["s"]) > RUNWAY_LEN * 0.8), None)
            if g:
                g["r1"], g["s"], g["e"] = r, max(s, g["s"]), min(e, g["e"])
            else:
                groups.append({"r0": r, "r1": r, "s": s, "e": e})
        for g in groups:
            if g["r1"] - g["r0"] + 1 >= RUNWAY_WIDTH and g["e"] - g["s"] + 1 >= RUNWAY_LEN:
                c = (g["r0"] + g["r1"]) // 2
                fr = [int(x0 + g["s"]), int(z0 + c)] if along_x else [int(x0 + c), int(z0 + g["s"])]
                to = [int(x0 + g["e"]), int(z0 + c)] if along_x else [int(x0 + c), int(z0 + g["e"])]
                runways.append({"from": fr, "to": to, "length": g["e"] - g["s"] + 1, "width": g["r1"] - g["r0"] + 1})

    # ---------------- метро и рельсы
    rails_a = np.array(rails, np.int64).reshape(-1, 3)
    if len(rails_a):   # рельсы шахт генерации — не дороги карты
        rails_a = rails_a[~gen_under[rails_a[:, 2] - z0, rails_a[:, 0] - x0]]
    metro, railways, stations, mines = [], [], [], []
    if len(rails_a):
        g = ground[rails_a[:, 2] - z0, rails_a[:, 0] - x0]
        deep_all = rails_a[:, 1] < g - 3
        mine_all = mine[(rails_a[:, 2] >> 4) - cz_min, (rails_a[:, 0] >> 4) - cx_min, np.clip((rails_a[:, 1] >> 4) + 4, 0, NSEC - 1)]
        # станции: рельс под землёй, вокруг которого подземный зал шире тоннеля (15 × 15 почти весь искусственный)
        hall = box_mean(under, 15) >= 0.85
        for kind, out in ((True, metro), (False, railways)):
            ra, mr = rails_a[deep_all == kind], mine_all[deep_all == kind]
            if not len(ra):
                continue
            rx_, ry_, rz_ = ra[:, 0], ra[:, 1], ra[:, 2]
            gg = ground[rz_ - z0, rx_ - x0]
            rm = np.zeros((H // 4 + 1, W // 4 + 1), bool)
            rm[(rz_ - z0) // 4, (rx_ - x0) // 4] = True
            rl, nr = components(rm)
            cell = rl[(rz_ - z0) // 4, (rx_ - x0) // 4]
            for i in range(1, nr + 1):
                sel = cell == i
                n = int(sel.sum())
                if n < 20:
                    continue
                px, pz = rx_[sel].astype(np.float64), rz_[sel].astype(np.float64)
                # концы — крайние рельсы вдоль главной оси линии
                c = np.cov(np.vstack([px, pz])) if n > 2 else np.eye(2)
                ax_ = np.linalg.eigh(c)[1][:, -1]
                t_ = (px - px.mean()) * ax_[0] + (pz - pz.mean()) * ax_[1]
                ends = [[int(px[t_.argmin()]), int(pz[t_.argmin()])], [int(px[t_.argmax()]), int(pz[t_.argmax()])]]
                ext = int(max(px.max() - px.min(), pz.max() - pz.min()))
                line = {"id": len(out) + 1, "rails": n, "under": round(float(kind), 2), "x": [int(px.min()), int(px.max())],
                        "z": [int(pz.min()), int(pz.max())], "y": [int(ry_[sel].min()), int(ry_[sel].max())], "extent": ext,
                        "ends": ends, "depth_median": round(float(np.median((gg - ry_)[sel])), 1),
                        "district": district_at(int(px.mean()), int(pz.mean()))}
                if kind and mr[sel].mean() >= 0.5:   # больше половины рельсов — в секциях с крепью или паутиной: шахта
                    mines.append({k_: line[k_] for k_ in ("rails", "x", "z", "y", "extent", "depth_median", "district")})
                    continue
                line["_stations"] = []
                h_ = hall[rz_[sel] - z0, rx_[sel] - x0] if kind else np.zeros(n, bool)
                if h_.any():
                    sm = np.zeros((H // 16 + 1, W // 16 + 1), bool)
                    sm[(rz_[sel][h_] - z0) // 16, (rx_[sel][h_] - x0) // 16] = True
                    slab_, ns = components(sm)
                    for j in range(1, ns + 1):
                        zz, xx = np.nonzero(slab_ == j)
                        sx, sz = int(x0 + xx.mean() * 16 + 8), int(z0 + zz.mean() * 16 + 8)
                        near = (np.abs(rx_[sel] - sx) < 24) & (np.abs(rz_[sel] - sz) < 24)
                        line["_stations"].append({"x": sx, "z": sz, "y": int(np.median(ry_[sel][near])) if near.any() else None,
                                                  "ground": int(ground[min(H - 1, sz - z0), min(W - 1, sx - x0)]),
                                                  "district": district_at(sx, sz)})
                out.append(line)
            out.sort(key=lambda m: -m["rails"])
            for k, m in enumerate(out):
                m["id"] = k + 1
                for st_ in m.pop("_stations"):
                    st_["line"] = k + 1
                    stations.append(st_)
    ul, nu = components(under[:H // 4 * 4, :W // 4 * 4].reshape(H // 4, 4, W // 4, 4).any(axis=(1, 3)))
    tunnels = []
    for i in range(1, nu + 1):
        zz, xx = np.nonzero(ul == i)
        ext = max(xx.max() - xx.min(), zz.max() - zz.min()) * 4
        if ext < 60:
            continue
        tunnels.append({"x": [int(x0 + xx.min() * 4), int(x0 + xx.max() * 4 + 4)], "z": [int(z0 + zz.min() * 4), int(z0 + zz.max() * 4 + 4)],
                        "extent": int(ext), "area": int(len(zz) * 16)})
    tunnels.sort(key=lambda t: -t["area"])
    bl_, nb = components(bridge[:H // 2 * 2, :W // 2 * 2].reshape(H // 2, 2, W // 2, 2).any(axis=(1, 3)))
    bridges = []
    for i in range(1, nb + 1):
        zz, xx = np.nonzero(bl_ == i)
        ext = max(xx.max() - xx.min(), zz.max() - zz.min()) * 2
        if ext < 24:
            continue
        cx, cz = int(x0 + xx.mean() * 2), int(z0 + zz.mean() * 2)
        bridges.append({"x": [int(x0 + xx.min() * 2), int(x0 + xx.max() * 2 + 2)], "z": [int(z0 + zz.min() * 2), int(z0 + zz.max() * 2 + 2)],
                        "extent": int(ext), "deck": int(top[cz - z0, cx - x0]), "district": district_at(cx, cz)})
    bridges.sort(key=lambda b: -b["extent"])

    # ---------------- вода
    wl, nw = components(wet[:H // 8 * 8, :W // 8 * 8].reshape(H // 8, 8, W // 8, 8).mean(axis=(1, 3)) > 0.5)
    waters = []
    for i in range(1, nw + 1):
        zz, xx = np.nonzero(wl == i)
        if len(zz) < 30:
            continue
        sel = np.zeros((H, W), bool)
        for z, x in zip(zz, xx):
            sel[z * 8:(z + 1) * 8, x * 8:(x + 1) * 8] = True
        dep = wet_depth[sel & wet]
        waters.append({"area_km2": round(len(zz) * 64 / 1e6, 3), "x": [int(x0 + xx.min() * 8), int(x0 + xx.max() * 8 + 8)],
                       "z": [int(z0 + zz.min() * 8), int(z0 + zz.max() * 8 + 8)], "level": int(np.median(water[sel & wet])),
                       "depth_median": round(float(np.median(dep)), 1) if len(dep) else 0,
                       "touches_box": bool(zz.min() == 0 or xx.min() == 0 or zz.max() == wl.shape[0] - 1 or xx.max() == wl.shape[1] - 1)})
    waters.sort(key=lambda w: -w["area_km2"])

    # ---------------- горы и виды
    peaks = []
    gm = np.where(have & natural_top & ~built, ground, -999).astype(np.float32)
    around = box_mean(np.where(have, ground, np.nan_to_num(ground)), 301)
    gpk = gm.copy()
    big = sorted(districts, key=lambda d: -d["tiles"])[:12]
    top_vis = top_any.astype(np.float32)
    # район виден с горы, если виден верх одной из трёх его высоток (нет высоток — середина над крышами)
    aims = {}
    for d in big:
        own = [(t["x"], t["z"], float(t["top"])) for t in tall if t["district"] == d["id"]][:3]
        cz_, cx_ = min(H - 1, max(0, d["centre"][1] - z0)), min(W - 1, max(0, d["centre"][0] - x0))
        aims[d["id"]] = own or [(d["centre"][0], d["centre"][1], float(top[cz_, cx_]) + 10)]
    for _ in range(60):
        if len(peaks) >= 25:
            break
        i = int(np.argmax(gpk))
        z, x = divmod(i, W)
        if gpk[z, x] < -900:
            break
        X, Z, Y = int(x0 + x), int(z0 + z), float(top[z, x])
        gpk[max(0, z - 120):z + 120, max(0, x - 120):x + 120] = -999
        if Y - around[z, x] < 20:   # не вершина: ровное место или склон
            continue
        seen = [d["id"] for d in big if any(sight(top_vis, x0, z0, (X, Z, Y), aim) for aim in aims[d["id"]])]
        peaks.append({"x": X, "z": Z, "y": int(Y), "above_300": round(float(Y - around[z, x]), 1),
                      "sees_districts": seen, "nearest_district": district_at(X, Z)})

    # ---------------- точки
    points = []
    for item in [p for p in a.points.split(";") if p.strip()]:
        nm, _, xy = item.partition(":")
        px, pz = (int(float(v)) for v in xy.split(","))
        zz, xx = pz - z0, px - x0
        if not (0 <= zz < H and 0 <= xx < W):
            points.append({"name": nm, "x": px, "z": pz, "outside": True})
            continue
        r = 128
        sl = (slice(max(0, zz - r), zz + r), slice(max(0, xx - r), xx + r))
        near_rail = None
        if len(rails_a):
            dd = np.hypot(rails_a[:, 0] - px, rails_a[:, 2] - pz)
            near_rail = int(dd.min())
        points.append({"name": nm, "x": px, "z": pz, "top": int(top[zz, xx]), "ground": round(float(ground[zz, xx]), 1),
                       "built_128": round(float(built[sl].mean()), 2), "road_128": round(float(road[sl].mean()), 2),
                       "water_128": round(float(wet[sl].mean()), 2), "height_max_128": int(np.where(built[sl], bh[sl], 0).max()),
                       "district": district_at(px, pz), "nearest_rail": near_rail,
                       "edge": int(min(px - mx0, mx1 - px, pz - mz0, mz1 - pz))})
    for k, p in enumerate(points):
        p["n"] = k + 1
    # что видно с точки: глаз в 2 блоках над верхом, цель — 10 блоков над верхом другой точки (удар, снаряд над ней)
    for p in points:
        if p.get("outside"):
            continue
        p["sees"] = [q["n"] for q in points if q is not p and not q.get("outside")
                     and sight(top_vis, x0, z0, (p["x"], p["z"], float(p["top"])), (q["x"], q["z"], float(q["top"]) + 10))]
        p["sees_districts"] = [d["id"] for d in big if any(sight(top_vis, x0, z0, (p["x"], p["z"], float(p["top"])), aim)
                                                         for aim in aims[d["id"]])]

    # ---------------- картинки
    s = a.scale
    def down(img):
        return img[:H // s * s, :W // s * s].reshape(H // s, s, W // s, s, -1).mean(axis=(1, 3)).astype(np.uint8)
    lut = np.array([color(n) for n in names], np.uint8)
    rgb = lut[upper]
    rgb[~have] = (0, 0, 0)
    gx = np.zeros_like(top_f)
    gx[:, 1:] = top_f[:, 1:] - top_f[:, :-1]
    gz = np.zeros_like(top_f)
    gz[1:, :] = top_f[1:, :] - top_f[:-1, :]
    shade = np.clip(1.0 - 0.06 * (gx + gz), 0.55, 1.4)[..., None]
    base = np.clip(rgb * shade, 0, 255).astype(np.uint8)
    gray = (0.35 * base.mean(axis=2, keepdims=True) + 90).clip(0, 255).astype(np.uint8).repeat(3, axis=2)
    gray[wet] = (70, 105, 160)
    hcol = gray.copy()
    for lo, col in ((4, (230, 220, 120)), (12, (240, 170, 60)), (30, (230, 90, 40)), (60, (190, 20, 60))):
        hcol[built & (bh >= lo)] = col
    inf = (base.astype(np.float32) * 0.35 + 140).clip(0, 255).astype(np.uint8)
    inf[wet] = (90, 140, 210)
    inf[road] = (45, 45, 50)
    inf[under] = (150, 70, 200)
    inf[bridge] = (230, 40, 200)
    if len(rails_a):
        rr = rails_a
        gg = ground[rr[:, 2] - z0, rr[:, 0] - x0]
        dpp = rr[:, 1] < gg - 3
        for dz in (-1, 0, 1):
            for dx in (-1, 0, 1):
                zz = np.clip(rr[:, 2] - z0 + dz, 0, H - 1)
                xx = np.clip(rr[:, 0] - x0 + dx, 0, W - 1)
                inf[zz[dpp], xx[dpp]] = (220, 20, 20)
                inf[zz[~dpp], xx[~dpp]] = (250, 150, 0)
    for rw in runways:
        (ax_, az_), (bx_, bz_) = rw["from"], rw["to"]
        half = rw["width"] // 2
        if az_ == bz_:
            inf[az_ - z0 - half:az_ - z0 + half, ax_ - x0:bx_ - x0] = (40, 200, 220)
        else:
            inf[az_ - z0:bz_ - z0, ax_ - x0 - half:ax_ - x0 + half] = (40, 200, 220)
    for st_ in stations:
        zz, xx = st_["z"] - z0, st_["x"] - x0
        inf[max(0, zz - 6):zz + 6, max(0, xx - 6):xx + 6] = (255, 255, 255)
    fnt, cyr = label_font(14)
    small, _ = label_font(11)

    def label(p):   # без шрифта с кириллицей — только номер точки (имена — в сводке)
        return f"{p['n']} {p['name']}" if cyr else str(p["n"])

    def marks(im, ox, oz, sc, step, extra):
        """Сетка, точки и подписи на картинке с углом (ox, oz) и масштабом sc блоков на пиксель."""
        d = ImageDraw.Draw(im)
        w_, h_ = im.size
        for gxv in range((ox // step + 1) * step, ox + w_ * sc, step):
            d.line([((gxv - ox) / sc, 0), ((gxv - ox) / sc, h_)], fill=(255, 0, 255) if gxv % 1024 == 0 else (200, 140, 200), width=1)
            d.text(((gxv - ox) / sc + 2, 2), str(gxv), fill=(255, 255, 255), font=small)
        for gzv in range((oz // step + 1) * step, oz + h_ * sc, step):
            d.line([(0, (gzv - oz) / sc), (w_, (gzv - oz) / sc)], fill=(255, 0, 255) if gzv % 1024 == 0 else (200, 140, 200), width=1)
            d.text((2, (gzv - oz) / sc + 2), str(gzv), fill=(255, 255, 255), font=small)
        for p in points:
            if p.get("outside"):
                continue
            px, pz = (p["x"] - ox) / sc, (p["z"] - oz) / sc
            d.ellipse([px - 6, pz - 6, px + 6, pz + 6], outline=(255, 255, 0), width=2)
            d.text((px + 8, pz - 8), label(p), fill=(255, 255, 0), font=fnt, stroke_width=2, stroke_fill=(0, 0, 0))
        for kind in extra:
            if kind == "tall":
                for t in tall[:40]:
                    px, pz = (t["x"] - ox) / sc, (t["z"] - oz) / sc
                    d.text((px + 3, pz - 4), str(t["height"]), fill=(255, 255, 255), font=small, stroke_width=1, stroke_fill=(0, 0, 0))
            if kind == "districts":
                for dd_ in districts[:20]:
                    px, pz = (dd_["centre"][0] - ox) / sc, (dd_["centre"][1] - oz) / sc
                    d.text((px, pz), f"D{dd_['id']}", fill=(0, 255, 255), font=fnt, stroke_width=2, stroke_fill=(0, 0, 0))
            if kind == "stations":
                for st_ in stations:
                    px, pz = (st_["x"] - ox) / sc, (st_["z"] - oz) / sc
                    d.text((px + 8, pz - 6), f"M{st_['line']}", fill=(255, 255, 255), font=small, stroke_width=1, stroke_fill=(0, 0, 0))

    outs = {}
    for name, img, extra in (("map.png", base, ("districts",)), ("heights.png", hcol, ("tall", "districts")),
                             ("infra.png", inf, ("stations",))):
        im = Image.fromarray(down(img))
        marks(im, x0, z0, s, 256, extra)
        path = os.path.join(a.out, name)
        im.save(path)
        outs[name] = path
    # вырезки 1 блок на пиксель: цвет сверху и устройство рядом
    crops = []
    for k, item in enumerate(c for c in a.crops.split(";") if c.strip()):
        nm, _, rest = item.partition(":")
        cx_, cz_, r_ = (int(float(v)) for v in rest.split(","))
        ax0, az0 = max(x0, cx_ - r_), max(z0, cz_ - r_)
        ax1, az1 = min(x1, cx_ + r_), min(z1, cz_ + r_)
        if ax1 <= ax0 or az1 <= az0:
            continue
        files_ = []
        for tag, img, extra in (("", base, ("tall",)), ("-infra", inf, ("stations",))):
            im = Image.fromarray(img[az0 - z0:az1 - z0, ax0 - x0:ax1 - x0])
            marks(im, ax0, az0, 1, 64, extra)
            fn = f"crop-{k + 1:02d}{tag}.jpg"
            im.save(os.path.join(a.out, fn), quality=90)
            files_.append(fn)
        crops.append({"n": k + 1, "name": nm, "x": [ax0, ax1], "z": [az0, az1], "files": files_})
    # что сверху вне самой карты и не природное — проверка списка природного (генерация мира и моды)
    outside = have & ~natural_top & ~wet & ~gen_surface
    outside[max(0, mz0 - z0):max(0, mz1 - z0), max(0, mx0 - x0):max(0, mx1 - x0)] = False
    cnt = np.bincount(surf[outside], minlength=len(names))
    outside_census = [(str(names[i]), int(cnt[i])) for i in np.argsort(-cnt)[:25] if cnt[i]]
    np.save(os.path.join(a.out, "top.npy"), top)
    np.save(os.path.join(a.out, "ground.npy"), ground.astype(np.int16))
    np.save(os.path.join(a.out, "water.npy"), wet)

    study = {"box": [x0, z0, x1, z1], "scale": s, "loaded_share": round(float(have.mean()), 3),
             "ground": {"min": int(ground[have].min()), "median": float(np.median(ground[have])), "max": int(ground[have].max())},
             "built_share": round(float(built[have].mean()), 3), "road_share": round(float(road[have].mean()), 3),
             "water_share": round(float(wet[have].mean()), 3),
             "districts": districts, "tall": tall, "streets": streets, "runways": runways, "metro": metro[:30],
             "railways": railways[:30], "stations": stations[:60], "mines": sorted(mines, key=lambda m: -m["rails"]),
             "tunnels": tunnels[:30], "bridges": bridges[:40], "waters": waters[:20], "peaks": peaks, "points": points,
             "generated": gen_list, "deep_census": sorted(deep.items(), key=lambda kv: -kv[1])[:40],
             "surface_census_outside_map": outside_census, "images": list(outs), "crops": crops}
    with open(os.path.join(a.out, "mapstudy.json"), "w", encoding="utf-8") as f:
        json.dump(study, f, ensure_ascii=False, indent=1)
    with open(os.path.join(a.out, "mapstudy.md"), "w", encoding="utf-8") as f:
        f.write(summary(study))
    print(summary(study))
    print(f"готово за {time.time() - t_start:.0f} с", file=sys.stderr)


def summary(st):
    L = [f"# Карта: рамка x {st['box'][0]}..{st['box'][2]}, z {st['box'][1]}..{st['box'][3]}", "",
         f"Загружено {100 * st['loaded_share']:.0f} % колонок; земля {st['ground']['min']}…{st['ground']['max']} "
         f"(медиана {st['ground']['median']:.0f}); застроено {100 * st['built_share']:.1f} %, мостовая "
         f"{100 * st['road_share']:.1f} %, вода {100 * st['water_share']:.1f} %.", "",
         "## Точки", "",
         "| № | Точка | x, z | Верх | Земля | Застроено ±128 | Мостовая | Вода | Выше всего | Район | До рельс | До края | Видно точки | Видно районы |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for p in st["points"]:
        if p.get("outside"):
            L.append(f"| {p['n']} | {p['name']} | {p['x']}, {p['z']} | вне рамки | | | | | | | | | | |")
            continue
        L.append(f"| {p['n']} | {p['name']} | {p['x']}, {p['z']} | {p['top']} | {p['ground']} | {100 * p['built_128']:.0f} % | "
                 f"{100 * p['road_128']:.0f} % | {100 * p['water_128']:.0f} % | {p['height_max_128']} | D{p['district']} | "
                 f"{p['nearest_rail']} | {p['edge']} | {', '.join(map(str, p.get('sees', [])))} | "
                 f"{', '.join(f'D{i}' for i in p.get('sees_districts', []))} |")
    L += ["", "## Районы (клетки 32×32, застроено ≥ 25 %)", "",
          "| Район | Центр | x | z | км² | Застроено | Мостовая | Высота медиана / макс | Высоток 30+ | Земля |",
          "|---|---|---|---|---|---|---|---|---|---|"]
    for d in st["districts"][:30]:
        L.append(f"| D{d['id']} | {d['centre'][0]}, {d['centre'][1]} | {d['x'][0]}..{d['x'][1]} | {d['z'][0]}..{d['z'][1]} | "
                 f"{d['area_km2']} | {100 * d['built']:.0f} % | {100 * d['road']:.0f} % | {d['height_median']} / {d['height_max']} | "
                 f"{d['tall_30']} | {d['ground']} |")
    L += ["", "## Высотки (30+ блоков над землёй)", "", "| x, z | Верх | Высота | Район | Крыша |", "|---|---|---|---|---|"]
    for t in st["tall"][:40]:
        L.append(f"| {t['x']}, {t['z']} | {t['top']} | {t['height']} | D{t['district']} | {t['roof'].split(':')[-1]} |")
    L += ["", "## Улицы-каньоны (мостовая от 100 блоков, дома по обе стороны)", "",
          "| От | До | Длина | Стороны (медиана меньшей) | Район |", "|---|---|---|---|---|"]
    for s in st["streets"][:30]:
        L.append(f"| {s['from'][0]}, {s['from'][1]} | {s['to'][0]}, {s['to'][1]} | {s['length']} | {s['sides']} | D{s['district']} |")
    L += ["", f"## Широкие прямые полосы (мостовая от {RUNWAY_LEN} блоков, ширина от {RUNWAY_WIDTH}: взлётные, шоссе)", ""]
    L += [f"- {r['from'][0]}, {r['from'][1]} → {r['to'][0]}, {r['to'][1]}: {r['length']} × {r['width']}" for r in st["runways"]] or ["- нет"]

    def lines(rows):
        out = ["| № | Рельс | x | z | y | Протяжённость | Концы | Глубина | Район |", "|---|---|---|---|---|---|---|---|---|"]
        for m in rows[:20]:
            ends = " — ".join(f"{e[0]}, {e[1]}" for e in m["ends"])
            out.append(f"| {m['id']} | {m['rails']} | {m['x'][0]}..{m['x'][1]} | {m['z'][0]}..{m['z'][1]} | {m['y'][0]}..{m['y'][1]} | "
                       f"{m['extent']} | {ends} | {m['depth_median']} | D{m['district']} |")
        return out
    L += ["", "## Метро (рельсы ниже земли на 4+ блока, без шахт генерации)", ""]
    L += lines(st["metro"]) if st["metro"] else ["- нет"]
    L += ["", "Станции (подземный зал 15 × 15 у рельсов):", ""]
    L += [f"- M{x['line']}: {x['x']}, {x['z']}, рельсы y {x['y']}, земля {x['ground']}, D{x['district']}"
          for x in st["stations"][:40]] or ["- нет"]
    L += ["", "## Железные дороги на поверхности", ""]
    L += lines(st["railways"]) if st["railways"] else ["- нет"]
    L += ["", "Подземное искусственное (тоннели, станции, подвалы) протяжённостью от 60 блоков:", ""]
    L += [f"- x {t['x'][0]}..{t['x'][1]}, z {t['z'][0]}..{t['z'][1]}: {t['extent']} блоков, {t['area']} м²" for t in st["tunnels"][:20]] or ["- нет"]
    L += ["", "## Мосты", ""]
    L += [f"- x {b['x'][0]}..{b['x'][1]}, z {b['z'][0]}..{b['z'][1]}: {b['extent']} блоков, настил y {b['deck']}, D{b['district']}"
          for b in st["bridges"][:25]] or ["- нет"]
    L += ["", "## Вода", ""]
    L += [f"- x {w['x'][0]}..{w['x'][1]}, z {w['z'][0]}..{w['z'][1]}: {w['area_km2']} км², уровень {w['level']}, глубина "
          f"{w['depth_median']}{', до края рамки' if w['touches_box'] else ''}" for w in st["waters"][:15]] or ["- нет"]
    L += ["", "## Горы и виды", "", "| x, z | Высота | Над округой 300 | Видны районы | Ближний район |", "|---|---|---|---|---|"]
    for p in st["peaks"][:20]:
        L.append(f"| {p['x']}, {p['z']} | {p['y']} | {p['above_300']} | {', '.join(f'D{i}' for i in p['sees_districts'])} | "
                 f"D{p['nearest_district']} |")
    if st.get("crops"):
        L += ["", "## Вырезки (1 блок на пиксель)", ""]
        L += [f"- {c['n']:02d} {c['name']}: x {c['x'][0]}..{c['x'][1]}, z {c['z'][0]}..{c['z'][1]} — {', '.join(c['files'])}"
              for c in st["crops"]]
    L += ["", "## Проверка разбора", "", "Постройки генерации мира (начала; внутри самой карты):", ""]
    L += [f"- {g['id']}: {g['starts']} ({g['inside_map']})" for g in st.get("generated", [])] or ["- нет данных о постройках"]
    mn = st.get("mines", [])
    L += ["", f"Шахты без начал в чанках (рельсы под землёй в секциях с крепью или паутиной, не метро): {len(mn)}, "
              f"рельсов {sum(m['rails'] for m in mn)}" + ("; крупные: " + "; ".join(
                  f"x {m['x'][0]}..{m['x'][1]}, z {m['z'][0]}..{m['z'][1]}, y {m['y'][0]}..{m['y'][1]} ({m['rails']})" for m in mn[:6])
                                                       if mn else "")]
    L += ["", "Искусственное ниже y 40 (блоков): " + ", ".join(f"{n.split(':')[-1] if n.startswith('minecraft:') else n} {c}"
                                                             for n, c in st.get("deep_census", [])[:25])]
    L += ["", "Не природный верх вне самой карты (колонок): " + ", ".join(
        f"{n.split(':')[-1] if n.startswith('minecraft:') else n} {c}" for n, c in st.get("surface_census_outside_map", [])[:20])]
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    main()
