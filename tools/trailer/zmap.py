#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""Карта сверху по файлам регионов мира: цвет верхнего блока + тень по высоте, 1 пиксель = SCALE блоков.
  zmap.py <region_dir> <out.png> [--scale 2] [--x0 X --z0 Z --x1 X --z1 Z]  (блоки)
Рядом — <out>_h.npy: высота верха (MOTION_BLOCKING) в том же масштабе, наибольшая в клетке (для лучей видимости
с запасом), и <out>.json — начало и масштаб. Печатает самые высокие точки (небоскрёбы) — для выбора мест съёмки.
Места камер трейлера выбираются по ней и лучам по блокам (ray.py), а не на глаз."""
import argparse, glob, json, os, re, struct, zlib, math
import numpy as np
from PIL import Image


def nbt(buf):
    pos = 0

    def u(fmt, n):
        nonlocal pos
        v = struct.unpack_from(fmt, buf, pos)
        pos += n
        return v[0]

    def payload(t):
        nonlocal pos
        if t == 1: return u('>b', 1)
        if t == 2: return u('>h', 2)
        if t == 3: return u('>i', 4)
        if t == 4: return u('>q', 8)
        if t == 5: return u('>f', 4)
        if t == 6: return u('>d', 8)
        if t == 7:
            n = u('>i', 4); v = buf[pos:pos + n]; pos += n; return v
        if t == 8:
            n = u('>H', 2); v = buf[pos:pos + n].decode('utf-8', 'replace'); pos += n; return v
        if t == 9:
            et = u('>b', 1); n = u('>i', 4); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = u('>b', 1)
                if tt == 0: return d
                n = u('>H', 2); name = buf[pos:pos + n].decode('utf-8', 'replace'); pos += n
                d[name] = payload(tt)
        if t == 11:
            n = u('>i', 4); v = np.frombuffer(buf, '>i4', n, pos); pos += 4 * n; return v
        if t == 12:
            n = u('>i', 4); v = np.frombuffer(buf, '>i8', n, pos); pos += 8 * n; return v
        raise ValueError(t)

    t = u('>b', 1)
    n = u('>H', 2); pos += n
    return payload(t)


def chunks(path):
    data = open(path, 'rb').read()
    if len(data) < 8192: return
    for i in range(1024):
        off = int.from_bytes(data[i * 4:i * 4 + 3], 'big'); cnt = data[i * 4 + 3]
        if off == 0 or cnt == 0: continue
        p = off * 4096
        ln = struct.unpack_from('>i', data, p)[0]; comp = data[p + 4]
        raw = data[p + 5:p + 4 + ln]
        if comp == 2: raw = zlib.decompress(raw)
        elif comp == 1:
            import gzip; raw = gzip.decompress(raw)
        elif comp == 3: pass
        else: continue
        try:
            yield nbt(raw)
        except Exception:
            continue


def unpack(longs, bits, count):
    per = 64 // bits
    mask = (1 << bits) - 1
    a = longs.astype(np.uint64)
    out = np.zeros(len(a) * per, np.uint64)
    for k in range(per):
        out[k::per] = (a >> np.uint64(k * bits)) & np.uint64(mask)
    return out[:count].astype(np.int64)


COLORS = [
    (r'water|kelp|seagrass', (52, 92, 180)), (r'lava', (220, 90, 20)),
    (r'grass_block|moss|fern|short_grass|tall_grass', (98, 140, 60)), (r'leaves', (55, 105, 40)),
    (r'sand(?!stone)', (220, 208, 160)), (r'sandstone', (210, 195, 140)), (r'snow|ice', (235, 240, 245)),
    (r'dirt|mud|podzol|farmland|path', (130, 95, 60)), (r'gravel', (140, 135, 130)),
    (r'white_concrete|quartz|white_', (225, 225, 225)), (r'light_gray', (160, 160, 160)), (r'gray_|andesite|stone|cobble|tuff|deepslate|smooth', (115, 115, 118)),
    (r'black_', (35, 35, 38)), (r'brick|terracotta|red_', (150, 70, 55)), (r'glass', (170, 200, 215)),
    (r'oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|planks|log|wood', (140, 105, 65)),
    (r'blue_|cyan_|light_blue', (70, 110, 160)), (r'yellow_|orange_', (200, 160, 50)), (r'green_|lime_', (90, 140, 70)),
    (r'iron|copper', (180, 170, 160)), (r'air', (0, 0, 0)),
]
_cache = {}


def color(name):
    c = _cache.get(name)
    if c is None:
        c = (160, 150, 140)
        for pat, col in COLORS:
            if re.search(pat, name):
                c = col
                break
        _cache[name] = c
    return c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('region'); ap.add_argument('out')
    ap.add_argument('--scale', type=int, default=2)
    ap.add_argument('--x0', type=int); ap.add_argument('--z0', type=int); ap.add_argument('--x1', type=int); ap.add_argument('--z1', type=int)
    a = ap.parse_args()
    files = []
    for f in glob.glob(os.path.join(a.region, 'r.*.*.mca')):
        m = re.match(r'r\.(-?\d+)\.(-?\d+)\.mca', os.path.basename(f))
        rx, rz = int(m[1]), int(m[2])
        if a.x0 is not None and (rx * 512 + 512 <= a.x0 or rx * 512 > a.x1 or rz * 512 + 512 <= a.z0 or rz * 512 > a.z1): continue
        files.append((rx, rz, f))
    x0 = a.x0 if a.x0 is not None else min(r[0] for r in files) * 512
    z0 = a.z0 if a.z0 is not None else min(r[1] for r in files) * 512
    x1 = a.x1 if a.x1 is not None else max(r[0] for r in files) * 512 + 512
    z1 = a.z1 if a.z1 is not None else max(r[1] for r in files) * 512 + 512
    W, H = x1 - x0, z1 - z0
    hgt = np.full((H, W), -64, np.int32)
    rgb = np.zeros((H, W, 3), np.uint8)
    for rx, rz, f in files:
        for ch in chunks(f):
            cx, cz = ch.get('xPos'), ch.get('zPos')
            if cx is None or ch.get('Status', '').split(':')[-1] not in ('full', 'minecraft:full'): continue
            bx, bz = cx * 16 - x0, cz * 16 - z0
            if bx + 16 <= 0 or bz + 16 <= 0 or bx >= W or bz >= H: continue
            hm = ch.get('Heightmaps', {}).get('MOTION_BLOCKING')
            if hm is None: continue
            miny = ch.get('yPos', -4) * 16
            heights = unpack(hm, 9, 256).reshape(16, 16) + miny  # [z][x]
            secs = {s['Y']: s for s in ch.get('sections', []) if 'block_states' in s}
            for lz in range(16):
                for lx in range(16):
                    X, Z = bx + lx, bz + lz
                    if not (0 <= X < W and 0 <= Z < H): continue
                    y = int(heights[lz, lx]) - 1
                    hgt[Z, X] = y
                    s = secs.get(y >> 4)
                    if s is None: continue
                    bs = s['block_states']; pal = bs['palette']
                    if len(pal) == 1: idx = 0
                    else:
                        key = ('u', id(bs))
                        u_ = _cache.get(key)
                        if u_ is None:
                            bits = max(4, math.ceil(math.log2(len(pal))))
                            u_ = unpack(bs['data'], bits, 4096); _cache[key] = u_
                        idx = int(u_[((y & 15) * 256) + lz * 16 + lx])
                    rgb[Z, X] = color(pal[idx]['Name'])
            _cache.clear()
    # тень: свет с северо-запада
    gx = np.zeros_like(hgt, np.float32); gz = np.zeros_like(hgt, np.float32)
    gx[:, 1:] = hgt[:, 1:] - hgt[:, :-1]; gz[1:, :] = hgt[1:, :] - hgt[:-1, :]
    shade = np.clip(1.0 + 0.08 * (gx + gz) * -1, 0.55, 1.35)[..., None]
    img = np.clip(rgb.astype(np.float32) * shade, 0, 255).astype(np.uint8)
    # линии сетки каждые 256 блоков с подписями в выводе
    for gxv in range((x0 // 256 + 1) * 256, x1, 256): img[:, gxv - x0] = (255, 0, 255) if gxv % 1024 == 0 else (200, 120, 200)
    for gzv in range((z0 // 256 + 1) * 256, z1, 256): img[gzv - z0, :] = (255, 0, 255) if gzv % 1024 == 0 else (200, 120, 200)
    im = Image.fromarray(img)
    if a.scale > 1: im = im.resize((W // a.scale, H // a.scale), Image.LANCZOS)
    im.save(a.out)
    base = os.path.splitext(a.out)[0]
    s = a.scale
    hs = hgt[:H // s * s, :W // s * s].reshape(H // s, s, W // s, s).max(axis=(1, 3)) if s > 1 else hgt
    np.save(base + '_h.npy', hs.astype(np.int16))
    with open(base + '.json', 'w') as f:
        json.dump({'x0': x0, 'z0': z0, 'scale': s}, f)
    print(f'map {a.out}: x {x0}..{x1}, z {z0}..{z1}, 1 px = {a.scale} blocks; grid every 256 (magenta every 1024)')
    # самые высокие точки (кластеры)
    flat = hgt.copy(); tops = []
    for _ in range(25):
        i = np.argmax(flat); z, x = divmod(int(i), W)
        if flat[z, x] < 80: break
        tops.append((x + x0, z + z0, int(flat[z, x])))
        flat[max(0, z - 40):z + 40, max(0, x - 40):x + 40] = -999
    print('tallest:', ' '.join(f'({x},{z}) y{y}' for x, z, y in tops))


if __name__ == '__main__':
    main()
