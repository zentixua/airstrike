#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""География и устройство карты по файлам регионов: районы, высотки, улицы-каньоны, дороги, вода, мосты, метро и
рельсы, взлётные полосы, горы и виды с них — то, по чему ставятся сцены трейлера, а не на глаз.

    uv run tools/trailer/mapstudy.py <region_dir> <out_dir> [--box x0 z0 x1 z1] [--points "имя:x,z;…"] [--jobs N]

Читает каждый полный чанк (формат 1.18+) один раз, в процессах по регионам: верх колонки (любой блок, твёрдый без
листвы и травы), блок поверхности, верх воды, рельсы, искусственные блоки по слоям в 4 блока. Земля — нижняя огибающая
твёрдого верха (раскрытие окном 65 блоков: дома уже его уходят, рельеф остаётся; там, где сверху природный блок, земля —
он сам), высота строения — верх минус земля. Дальше — numpy по карте целиком:
- районы: клетки 32×32, где застроено не меньше четверти колонок (строение от 4 блоков), связные — один район;
- высотки: вершины строений от 30 блоков над землёй, по одной на 40 блоков;
- улицы-каньоны: прямые мощёные полосы (искусственный верх у земли) вдоль x или z от 100 блоков, по обе стороны —
  дома; оценка — медиана меньшей из двух сторон;
- взлётные полосы: прямые мощёные полосы от 300 блоков шириной от 16;
- вода и глубина; мосты — искусственный настил над водой; метро — рельсы и искусственные блоки ниже земли на 4+ блока;
- горы: вершины рельефа, их превышение над округой и видны ли с них центры районов (луч по карте высот).
Пишет `map.png` (цвет сверху с тенью, сетка 256, точки), `heights.png` (строения по высоте), `infra.png` (дороги, вода,
мосты, рельсы, метро, полосы), `mapstudy.json` (всё числами) и `mapstudy.md` (сводка). Картинки — 2 блока на пиксель.
"""
import argparse
import glob
import json
import math
import os
import re
import struct
import sys
import zlib
from multiprocessing import Pool

import numpy as np
from PIL import Image, ImageDraw

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
    r"pink_petals|mangrove_roots|muddy_mangrove_roots|weeping_vines.*|twisting_vines.*)$")
# не верх колонки: воздух, жидкость, листва, трава и цветы, мелочь на поверхности
SOFT = re.compile(
    r"^minecraft:(air|cave_air|void_air|water|lava|bubble_column|.*_leaves|vine|short_grass|tall_grass|fern|large_fern|"
    r"dandelion|poppy|blue_orchid|allium|azure_bluet|.*_tulip|oxeye_daisy|cornflower|lily_of_the_valley|sunflower|lilac|"
    r"rose_bush|peony|.*_sapling|kelp|kelp_plant|seagrass|tall_seagrass|sea_pickle|lily_pad|sugar_cane|dead_bush|"
    r"sweet_berry_bush|brown_mushroom|red_mushroom|glow_lichen|snow|cobweb|.*torch|.*_button|.*_sign|.*_banner|"
    r"pink_petals|moss_carpet|.*_carpet|cave_vines.*|weeping_vines.*|twisting_vines.*)$")
AIRISH = re.compile(r"^minecraft:(air|cave_air|void_air)$")
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
    искусственного по 4 блока (биты), рельсы [(x, y, z)]."""
    path, rx, rz = args
    n = 512
    top_any = np.full((n, n), MIN_Y - 1, np.int16)
    top_solid = np.full((n, n), MIN_Y - 1, np.int16)
    water = np.full((n, n), MIN_Y - 1, np.int16)
    surf = np.zeros((n, n), np.int32)
    upper = np.zeros((n, n), np.int32)
    slabs = np.zeros((n, n, SLAB_BYTES), np.uint8)
    have = np.zeros((n, n), bool)
    rails = []
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
    return rx, rz, have, top_any, top_solid, water, surf, upper, slabs, rails, names


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
    a = ap.parse_args()
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
    rails = []
    names, gids = ["minecraft:air"], {"minecraft:air": 0}
    with Pool(a.jobs) as pool:
        for k, res in enumerate(pool.imap_unordered(read_region, files)):
            rx, rz, hv, ta, ts, tw, sf, up, sb, rl, nm = res
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

    # ---------------- земля и строения
    natural_top = nat_name[surf] & have
    opened = sliding(sliding(top_f, 65, 0, np.minimum), 65, 1, np.minimum)
    opened = sliding(sliding(opened, 65, 0, np.maximum), 65, 1, np.maximum)
    ground = np.where(natural_top, top_f, np.minimum(top_f, opened))
    ground = np.minimum(ground, box_mean(ground, 9) + 2)
    bh = np.where(have, top_f - ground, 0)
    wet = have & (water >= top) & (water > MIN_Y)
    wet_depth = np.where(wet, water - top, 0)
    built = have & (bh >= 4) & ~natural_top
    paved = have & ~natural_top & (bh <= 1.5) & ~wet
    road = paved & road_name[surf]
    # подземное искусственное: слои ниже земли на 4+ блока
    under = np.zeros((H, W), bool)
    for k in range(SLABS):
        top_k = MIN_Y + k * 4 + 3
        under |= (((slabs[..., k // 8] >> (k % 8)) & 1).astype(bool) & (top_k < ground - 4))
    under &= have
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
    runways = []
    for along_x, m in ((True, paved), (False, paved.T)):
        rr = runs(m, 300, gap=2)
        rr.sort()
        groups = []
        for r, s, e in rr:
            g = next((g for g in groups if r - g["r1"] <= 1 and min(e, g["e"]) - max(s, g["s"]) > 250), None)
            if g:
                g["r1"], g["s"], g["e"] = r, max(s, g["s"]), min(e, g["e"])
            else:
                groups.append({"r0": r, "r1": r, "s": s, "e": e})
        for g in groups:
            if g["r1"] - g["r0"] + 1 >= 16:
                c = (g["r0"] + g["r1"]) // 2
                fr = [int(x0 + g["s"]), int(z0 + c)] if along_x else [int(x0 + c), int(z0 + g["s"])]
                to = [int(x0 + g["e"]), int(z0 + c)] if along_x else [int(x0 + c), int(z0 + g["e"])]
                runways.append({"from": fr, "to": to, "length": g["e"] - g["s"] + 1, "width": g["r1"] - g["r0"] + 1})

    # ---------------- метро и рельсы
    rails_a = np.array(rails, np.int64).reshape(-1, 3)
    metro = []
    if len(rails_a):
        rx_, ry_, rz_ = rails_a[:, 0], rails_a[:, 1], rails_a[:, 2]
        g = ground[np.clip(rz_ - z0, 0, H - 1), np.clip(rx_ - x0, 0, W - 1)]
        deep = ry_ < g - 3
        rm = np.zeros((H // 4 + 1, W // 4 + 1), bool)
        rm[(rz_ - z0) // 4, (rx_ - x0) // 4] = True
        rl, nr = components(rm)
        for i in range(1, nr + 1):
            zz, xx = np.nonzero(rl == i)
            sel = rl[(rz_ - z0) // 4, (rx_ - x0) // 4] == i
            n = int(sel.sum())
            if n < 20:
                continue
            ext = max(xx.max() - xx.min(), zz.max() - zz.min()) * 4
            metro.append({"rails": n, "under": round(float(deep[sel].mean()), 2), "x": [int(rx_[sel].min()), int(rx_[sel].max())],
                          "z": [int(rz_[sel].min()), int(rz_[sel].max())], "y": [int(ry_[sel].min()), int(ry_[sel].max())],
                          "extent": int(ext), "depth_median": round(float(np.median((g - ry_)[sel])), 1)})
        metro.sort(key=lambda m: -m["rails"])
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
    gm = np.where(have & natural_top, ground, -999).astype(np.float32)
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
    mx0, mz0, mx1, mz1 = a.map or (x0, z0, x1, z1)
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
        rr = rails_a[(rails_a[:, 0] >= x0) & (rails_a[:, 0] < x1) & (rails_a[:, 2] >= z0) & (rails_a[:, 2] < z1)]
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
    outs = {}
    for name, img in (("map.png", base), ("heights.png", hcol), ("infra.png", inf)):
        im = Image.fromarray(down(img))
        d = ImageDraw.Draw(im)
        for gxv in range((x0 // 256 + 1) * 256, x1, 256):
            d.line([((gxv - x0) / s, 0), ((gxv - x0) / s, H / s)], fill=(255, 0, 255) if gxv % 1024 == 0 else (200, 140, 200), width=1)
            d.text(((gxv - x0) / s + 2, 2), str(gxv), fill=(255, 255, 255))
        for gzv in range((z0 // 256 + 1) * 256, z1, 256):
            d.line([(0, (gzv - z0) / s), (W / s, (gzv - z0) / s)], fill=(255, 0, 255) if gzv % 1024 == 0 else (200, 140, 200), width=1)
            d.text((2, (gzv - z0) / s + 2), str(gzv), fill=(255, 255, 255))
        for p in points:
            if p.get("outside"):
                continue
            px, pz = (p["x"] - x0) / s, (p["z"] - z0) / s
            d.ellipse([px - 6, pz - 6, px + 6, pz + 6], outline=(255, 255, 0), width=2)
            d.text((px + 8, pz - 6), p["name"], fill=(255, 255, 0))
        if name == "heights.png":
            for t in tall[:30]:
                px, pz = (t["x"] - x0) / s, (t["z"] - z0) / s
                d.text((px + 3, pz - 4), str(t["height"]), fill=(255, 255, 255))
        if name == "map.png":
            for dd_ in districts[:20]:
                px, pz = (dd_["centre"][0] - x0) / s, (dd_["centre"][1] - z0) / s
                d.text((px, pz), f"D{dd_['id']}", fill=(0, 255, 255))
        path = os.path.join(a.out, name)
        im.save(path)
        outs[name] = path
    np.save(os.path.join(a.out, "top.npy"), top)
    np.save(os.path.join(a.out, "ground.npy"), ground.astype(np.int16))

    study = {"box": [x0, z0, x1, z1], "scale": s, "loaded_share": round(float(have.mean()), 3),
             "ground": {"min": int(ground[have].min()), "median": float(np.median(ground[have])), "max": int(ground[have].max())},
             "built_share": round(float(built[have].mean()), 3), "road_share": round(float(road[have].mean()), 3),
             "water_share": round(float(wet[have].mean()), 3),
             "districts": districts, "tall": tall, "streets": streets, "runways": runways, "metro": metro[:30],
             "tunnels": tunnels[:30], "bridges": bridges[:40], "waters": waters[:20], "peaks": peaks, "points": points,
             "images": list(outs)}
    with open(os.path.join(a.out, "mapstudy.json"), "w", encoding="utf-8") as f:
        json.dump(study, f, ensure_ascii=False, indent=1)
    with open(os.path.join(a.out, "mapstudy.md"), "w", encoding="utf-8") as f:
        f.write(summary(study))
    print(summary(study))


def summary(st):
    L = [f"# Карта: рамка x {st['box'][0]}..{st['box'][2]}, z {st['box'][1]}..{st['box'][3]}", "",
         f"Загружено {100 * st['loaded_share']:.0f} % колонок; земля {st['ground']['min']}…{st['ground']['max']} "
         f"(медиана {st['ground']['median']:.0f}); застроено {100 * st['built_share']:.1f} %, мостовая "
         f"{100 * st['road_share']:.1f} %, вода {100 * st['water_share']:.1f} %.", "",
         "## Точки", "", "| Точка | x, z | Верх | Земля | Застроено ±128 | Мостовая | Вода | Выше всего | Район | До рельс | До края |",
         "|---|---|---|---|---|---|---|---|---|---|---|"]
    for p in st["points"]:
        if p.get("outside"):
            L.append(f"| {p['name']} | {p['x']}, {p['z']} | вне рамки | | | | | | | | |")
            continue
        L.append(f"| {p['name']} | {p['x']}, {p['z']} | {p['top']} | {p['ground']} | {100 * p['built_128']:.0f} % | "
                 f"{100 * p['road_128']:.0f} % | {100 * p['water_128']:.0f} % | {p['height_max_128']} | D{p['district']} | "
                 f"{p['nearest_rail']} | {p['edge']} |")
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
    L += ["", "## Взлётные полосы (мостовая от 300 блоков, ширина от 16)", ""]
    L += [f"- {r['from'][0]}, {r['from'][1]} → {r['to'][0]}, {r['to'][1]}: {r['length']} × {r['width']}" for r in st["runways"]] or ["- нет"]
    L += ["", "## Рельсы и метро", "", "| Рельс | Под землёй | x | z | y | Протяжённость | Глубина |", "|---|---|---|---|---|---|---|"]
    for m in st["metro"][:20]:
        L.append(f"| {m['rails']} | {100 * m['under']:.0f} % | {m['x'][0]}..{m['x'][1]} | {m['z'][0]}..{m['z'][1]} | "
                 f"{m['y'][0]}..{m['y'][1]} | {m['extent']} | {m['depth_median']} |")
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
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    main()
