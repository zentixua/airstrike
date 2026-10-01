#!/usr/bin/env python3
"""Какие чанки мира на диске сгенерированы до конца: гистограмма поля Status в квадрате вокруг точки.

Только читает файлы региона (region/r.X.Z.mca), мир не меняет; запускать можно и на копии, и при открытой игре.
Для чанков не FULL — сколько в них непустых блоков (есть ли там постройки) и какие карты высот записаны.

    python3 tools/chunk_status.py <папка мира> <x блока> <z блока> <радиус в чанках> [измерение: overworld|nether|end]
"""
import io
import os
import struct
import sys
import zlib
import gzip
from collections import Counter, defaultdict


def read_nbt(data: bytes):
    """Корневой тег NBT (сжатие уже снято): словари, списки, числа, строки, массивы."""
    buf = io.BytesIO(data)

    def u(fmt):
        return struct.unpack('>' + fmt, buf.read(struct.calcsize('>' + fmt)))[0]

    def string():
        n = u('H')
        return buf.read(n).decode('utf-8', 'replace')

    def payload(t):
        if t == 1: return u('b')
        if t == 2: return u('h')
        if t == 3: return u('i')
        if t == 4: return u('q')
        if t == 5: return u('f')
        if t == 6: return u('d')
        if t == 7: return buf.read(u('i'))
        if t == 8: return string()
        if t == 9:
            et, n = u('b'), u('i')
            return [payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = u('b')
                if tt == 0: return out
                name = string()
                out[name] = payload(tt)
        if t == 11: return list(struct.unpack('>%di' % (n := u('i')), buf.read(4 * n)))
        if t == 12: return list(struct.unpack('>%dq' % (n := u('i')), buf.read(8 * n)))
        raise ValueError('тег %d' % t)

    t = u('b')
    string()
    return payload(t)


def chunk(region_dir, cx, cz, cache):
    rx, rz = cx >> 5, cz >> 5
    key = (rx, rz)
    if key not in cache:
        path = os.path.join(region_dir, 'r.%d.%d.mca' % key)
        cache[key] = open(path, 'rb').read() if os.path.exists(path) else None
    data = cache[key]
    if data is None or len(data) < 8192:
        return None
    i = 4 * ((cx & 31) + (cz & 31) * 32)
    off = int.from_bytes(data[i:i + 3], 'big') * 4096
    if off == 0:
        return None
    length = int.from_bytes(data[off:off + 4], 'big')
    kind = data[off + 4]
    raw = data[off + 5:off + 4 + length]
    if kind & 0x80:
        return 'внешний файл'
    if kind == 2: raw = zlib.decompress(raw)
    elif kind == 1: raw = gzip.decompress(raw)
    elif kind == 4: return 'lz4'
    return read_nbt(raw)


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        sys.exit(2)
    world, bx, bz, r = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
    dim = sys.argv[5] if len(sys.argv) > 5 else 'overworld'
    region = os.path.join(world, {'overworld': 'region', 'nether': 'DIM-1/region', 'end': 'DIM1/region'}[dim])
    cx0, cz0 = bx >> 4, bz >> 4
    cache, status, blocks, maps, samples = {}, Counter(), defaultdict(list), Counter(), defaultdict(list)
    for cx in range(cx0 - r, cx0 + r + 1):
        for cz in range(cz0 - r, cz0 + r + 1):
            c = chunk(region, cx, cz, cache)
            if c is None:
                status['нет на диске'] += 1
                continue
            if isinstance(c, str):
                status[c] += 1
                continue
            s = c.get('Status', '?')
            status[s] += 1
            if s != 'minecraft:full':
                solid = 0
                for sec in c.get('sections', []):
                    pal = sec.get('block_states', {}).get('palette', [])
                    if any(p.get('Name') != 'minecraft:air' for p in pal):
                        solid += 1
                blocks[s].append(solid)
                maps[(s, tuple(sorted(c.get('Heightmaps', {}).keys())))] += 1
                if len(samples[s]) < 3:
                    samples[s].append((cx, cz))
    total = sum(status.values())
    print('чанков в квадрате: %d (центр %d %d, радиус %d)' % (total, cx0, cz0, r))
    for s, n in status.most_common():
        line = '  %-28s %6d' % (s, n)
        if s in blocks:
            b = blocks[s]
            line += '  непустых секций в среднем %.1f, без блоков %d, примеры %s' % (sum(b) / len(b), sum(1 for x in b if x == 0), samples[s])
        print(line)
    for (s, keys), n in maps.most_common():
        print('  карты высот у %s: %s — %d' % (s, ', '.join(keys) or 'нет', n))


if __name__ == '__main__':
    main()
