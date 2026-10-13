#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""Луч по файлам регионов: uv run tools/trailer/ray.py <region_dir> cx cy cz tx ty tz [--fov F] — первый твёрдый блок
по лучу из камеры на цель и сетка лучей 7x4 по кадру 16:9 (расстояние до первого блока; '?' — чанка нет в файлах,
'.' — чисто до 1200 блоков). Видна ли цель плана из камеры — до записи, по блокам мира."""
import sys, math, os, argparse
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from zmap import nbt, unpack
import struct, zlib

REG = {}
CH = {}

def region(d, rx, rz):
    k = (rx, rz)
    if k not in REG:
        p = os.path.join(d, f'r.{rx}.{rz}.mca')
        REG[k] = open(p, 'rb').read() if os.path.exists(p) else b''
    return REG[k]

def chunk(d, cx, cz):
    k = (cx, cz)
    if k in CH: return CH[k]
    data = region(d, cx >> 5, cz >> 5)
    res = None
    if len(data) >= 8192:
        i = (cx & 31) + (cz & 31) * 32
        off = int.from_bytes(data[i * 4:i * 4 + 3], 'big'); cnt = data[i * 4 + 3]
        if off and cnt:
            p = off * 4096
            ln = struct.unpack_from('>i', data, p)[0]
            raw = zlib.decompress(data[p + 5:p + 4 + ln])
            ch = nbt(raw)
            secs = {}
            for s in ch.get('sections', []):
                bs = s.get('block_states')
                if not bs: continue
                pal = [e['Name'] for e in bs['palette']]
                if len(pal) == 1: arr = None
                else:
                    bits = max(4, math.ceil(math.log2(len(pal))))
                    arr = unpack(bs['data'], bits, 4096)
                secs[s['Y']] = (pal, arr)
            res = secs
    CH[k] = res
    return res

AIRISH = ('air', 'light', 'barrier')
def block(d, x, y, z):
    secs = chunk(d, x >> 4, z >> 4)
    if secs is None: return '?'
    s = secs.get(y >> 4)
    if s is None: return 'minecraft:air'
    pal, arr = s
    if arr is None: return pal[0]
    return pal[int(arr[((y & 15) * 256) + (z & 15) * 16 + (x & 15)])]

def cast(d, o, v, maxd=1200, step=0.25):
    n = int(maxd / step)
    last = None
    for i in range(1, n):
        t = i * step
        x, y, z = (math.floor(o[k] + v[k] * t) for k in range(3))
        if (x, y, z) == last: continue
        last = (x, y, z)
        b = block(d, x, y, z)
        if b == '?': return t, '?', (x, y, z)
        nm = b.split(':')[-1]
        if nm.endswith('air') or nm in ('light', 'barrier') or 'glass' in nm and False: continue
        return t, nm, (x, y, z)
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('region'); ap.add_argument('c', type=float, nargs=6)
    ap.add_argument('--fov', type=float, default=70)
    a = ap.parse_args()
    o = a.c[:3]; t = a.c[3:]
    f = np.array(t) - np.array(o); f /= np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0]); r /= np.linalg.norm(r); u = np.cross(r, f)
    h = math.tan(math.radians(a.fov) / 2); w = h * 16 / 9
    hit = cast(a.region, o, f)
    print('centre:', hit)
    for j in range(4):
        row = []
        for i in range(7):
            sx = (i / 6 * 2 - 1) * w * 0.95; sy = (1 - j / 3 * 2) * h * 0.95
            v = f + r * sx + u * sy; v /= np.linalg.norm(v)
            hh = cast(a.region, o, v)
            row.append('   .   ' if hh is None else f'{hh[0]:5.0f}{hh[1][:2]}')
        print(' '.join(row))

if __name__ == "__main__":
    main()
