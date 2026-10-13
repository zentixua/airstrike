#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""Место для камеры или наводчика по файлам регионов: uv run tools/trailer/perch.py <region_dir> --targets "x,y,z;x,y,z"
[--rmin 250 --rmax 390] [--min-y 90] [--step 3] [--top 12]. Ищет крыши (верх колонки по карте высот MOTION_BLOCKING,
ровный квадрат 3×3), с которых каждая цель видна лучом по блокам, на расстоянии rmin…rmax от
первой цели по горизонтали (--near N: цель видна, если луч до первого блока доходит ближе N блоков к ней — грань
башни у её середины тоже). Печатает лучшие: сколько целей видно, расстояния, ноги стоящего (x y z) и взгляд на первую
цель. Так ставится наводчик с биноклем (цель мода — до 400 блоков, `Designator.RANGE`) — по миру, а не на глаз."""
import argparse, math, os, struct, sys, zlib
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from zmap import nbt, unpack

MIN_Y = -64
REG, CH = {}, {}


def chunk(d, cx, cz):
    k = (cx, cz)
    if k in CH: return CH[k]
    rk = (cx >> 5, cz >> 5)
    if rk not in REG:
        p = os.path.join(d, f'r.{rk[0]}.{rk[1]}.mca')
        REG[rk] = open(p, 'rb').read() if os.path.exists(p) else b''
    data = REG[rk]
    res = None
    if len(data) >= 8192:
        i = (cx & 31) + (cz & 31) * 32
        off = int.from_bytes(data[i * 4:i * 4 + 3], 'big'); cnt = data[i * 4 + 3]
        if off and cnt and data[off * 4096 + 4] == 2:
            p = off * 4096
            ln = struct.unpack_from('>i', data, p)[0]
            ch = nbt(zlib.decompress(data[p + 5:p + 4 + ln]))
            secs = {}
            for s in ch.get('sections', []):
                bs = s.get('block_states')
                if not bs: continue
                pal = [e['Name'] for e in bs['palette']]
                solid = np.array([not (n.endswith('air') or n.split(':')[-1] in ('light', 'barrier')) for n in pal])
                if len(pal) == 1:
                    secs[s['Y']] = (bool(solid[0]), None)
                else:
                    arr = unpack(bs['data'], max(4, math.ceil(math.log2(len(pal)))), 4096)
                    secs[s['Y']] = (None, solid[arr])
            hm = ch.get('Heightmaps', {}).get('MOTION_BLOCKING')
            top = unpack(hm, 9, 256).reshape(16, 16) + MIN_Y - 1 if hm is not None else None
            res = (secs, top)
    CH[k] = res
    return res


def solid(d, x, y, z):
    c = chunk(d, x >> 4, z >> 4)
    if c is None: return None
    s = c[0].get(y >> 4)
    if s is None: return False
    if s[1] is None: return s[0]
    return bool(s[1][((y & 15) * 256) + (z & 15) * 16 + (x & 15)])


def top(d, x, z):
    c = chunk(d, x >> 4, z >> 4)
    if c is None or c[1] is None: return None
    return int(c[1][z & 15, x & 15])


def clear(d, eye, t, near, step=0.5):
    """Луч из глаза на цель доходит до первого блока не дальше near от цели (или до самой цели)."""
    v = np.array(t) - np.array(eye)
    dist = float(np.linalg.norm(v)); v /= dist
    last = None
    for i in range(2, int(dist / step)):
        if dist - i * step < near: return True
        p = [math.floor(eye[k] + v[k] * i * step) for k in range(3)]
        if p == last: continue
        last = p
        s = solid(d, *p)
        if s is None or s: return False
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('region'); ap.add_argument('--targets', required=True)
    ap.add_argument('--rmin', type=float, default=250); ap.add_argument('--rmax', type=float, default=390)
    ap.add_argument('--min-y', type=int, default=90); ap.add_argument('--step', type=int, default=3)
    ap.add_argument('--top', type=int, default=12); ap.add_argument('--near', type=float, default=12)
    a = ap.parse_args()
    ts = [tuple(float(v) for v in t.split(',')) for t in a.targets.split(';')]
    cx, cz = ts[0][0], ts[0][2]
    out = []
    r = int(a.rmax)
    for x in range(int(cx) - r, int(cx) + r + 1, a.step):
        for z in range(int(cz) - r, int(cz) + r + 1, a.step):
            h = math.hypot(x + 0.5 - cx, z + 0.5 - cz)
            if h < a.rmin or h > a.rmax: continue
            y = top(a.region, x, z)
            if y is None or y < a.min_y: continue
            # ровная крыша: квадрат 3×3 одной высоты, над ним два блока воздуха
            if any(top(a.region, x + i, z + j) != y for i in (-1, 0, 1) for j in (-1, 0, 1)): continue
            if solid(a.region, x, y + 1, z) or solid(a.region, x, y + 2, z): continue
            eye = (x + 0.5, y + 1 + 1.62, z + 0.5)
            seen = [clear(a.region, eye, t, a.near) for t in ts]
            if not any(seen): continue
            out.append((sum(seen), -h, x, y + 1, z, seen, [math.dist(eye, t) for t in ts]))
    out.sort(key=lambda o: (-o[0], o[1]))
    print(f'мест: {len(out)} (видна хотя бы одна цель); лучшие:')
    for n, _, x, y, z, seen, ds in out[:a.top]:
        t = ts[0]
        yaw = math.degrees(math.atan2(t[2] - z - 0.5, t[0] - x - 0.5)) - 90
        pitch = -math.degrees(math.atan2(t[1] - y - 1.62, math.hypot(t[0] - x - 0.5, t[2] - z - 0.5)))
        vis = ''.join('+' if s else '-' for s in seen)
        print(f'  {x + 0.5:.1f} {y} {z + 0.5:.1f}  цели {vis}  до целей ' + ' '.join(f'{d:.0f}' for d in ds)
              + f'  взгляд yaw {yaw:.1f} pitch {pitch:.1f}')


if __name__ == '__main__':
    main()
