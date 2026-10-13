#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""Какие блоки стоят в рамке мира, по файлам регионов: uv run tools/trailer/blocks.py <region_dir> x0 y0 z0 x1 y1 z1
[--match light,lantern,…] [--list N] [--grid S]. Печатает число блоков каждого id, чьё имя содержит одно из слов
--match (без --match — все id, кроме воздуха), их рамку и первые N мест; --grid S — карту сверху клетками S×S блоков
(цифра — сколько совпадений в клетке, 9 — девять и больше). Постановка трейлера по данным мира: из чего лампы станции
и кварталов (гаснут ли при блэкауте мода), где рельсы депо."""
import argparse, os, struct, sys, zlib, math
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from zmap import nbt, unpack


def chunk_at(data, cx, cz):
    i = (cx & 31) + (cz & 31) * 32
    off = int.from_bytes(data[i * 4:i * 4 + 3], 'big'); cnt = data[i * 4 + 3]
    if not off or not cnt: return None
    p = off * 4096
    ln = struct.unpack_from('>i', data, p)[0]
    if data[p + 4] != 2: return None
    return nbt(zlib.decompress(data[p + 5:p + 4 + ln]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('region'); ap.add_argument('box', type=int, nargs=6)
    ap.add_argument('--match', default=''); ap.add_argument('--list', type=int, default=8)
    ap.add_argument('--grid', type=int, default=0)
    a = ap.parse_args()
    x0, y0, z0, x1, y1, z1 = a.box
    x0, x1 = sorted((x0, x1)); y0, y1 = sorted((y0, y1)); z0, z1 = sorted((z0, z1))
    words = [w for w in a.match.split(',') if w]

    def wanted(name):
        if name.endswith(':air') or name.endswith('cave_air') or name.endswith('void_air'): return False
        return not words or any(w in name for w in words)

    found = {}
    missing = 0
    regions = {}
    for cx in range(x0 >> 4, (x1 >> 4) + 1):
        for cz in range(z0 >> 4, (z1 >> 4) + 1):
            k = (cx >> 5, cz >> 5)
            if k not in regions:
                p = os.path.join(a.region, f'r.{k[0]}.{k[1]}.mca')
                regions[k] = open(p, 'rb').read() if os.path.exists(p) else b''
            data = regions[k]
            ch = chunk_at(data, cx, cz) if len(data) >= 8192 else None
            if ch is None:
                missing += 1
                continue
            for s in ch.get('sections', []):
                sy = s['Y']
                if sy * 16 + 15 < y0 or sy * 16 > y1: continue
                bs = s.get('block_states')
                if not bs: continue
                pal = [e['Name'] for e in bs['palette']]
                hit = [i for i, n in enumerate(pal) if wanted(n)]
                if not hit: continue
                if len(pal) == 1:
                    arr = np.zeros(4096, np.int64)
                else:
                    arr = unpack(bs['data'], max(4, math.ceil(math.log2(len(pal)))), 4096)
                for i in hit:
                    for idx in np.nonzero(arr == i)[0]:
                        x = cx * 16 + (idx & 15); z = cz * 16 + ((idx >> 4) & 15); y = sy * 16 + (idx >> 8)
                        if x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1:
                            found.setdefault(pal[i], []).append((int(x), int(y), int(z)))
    if missing: print(f'чанков нет в файлах: {missing}')
    for name, ps in sorted(found.items(), key=lambda kv: -len(kv[1])):
        xs, ys, zs = zip(*ps)
        print(f'{name}: {len(ps)}  x {min(xs)}..{max(xs)}  y {min(ys)}..{max(ys)}  z {min(zs)}..{max(zs)}')
        for p in sorted(ps, key=lambda p: (p[1], p[0], p[2]))[:a.list]: print(f'    {p[0]} {p[1]} {p[2]}')
    if a.grid and found:
        S = a.grid
        w, h = (x1 - x0) // S + 1, (z1 - z0) // S + 1
        g = np.zeros((h, w), np.int64)
        for ps in found.values():
            for x, y, z in ps: g[(z - z0) // S, (x - x0) // S] += 1
        print(f'карта: клетка {S} блоков, слева x {x0}, сверху z {z0} (z растёт вниз)')
        for row in g: print(''.join('.' if v == 0 else str(min(9, v)) for v in row))


if __name__ == '__main__':
    main()
