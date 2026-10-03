#!/usr/bin/env python3
"""Чанки мира по состоянию на диске — как их видит DiskStatus мода (только чтение файлов регионов).

region_status.py <папка region> [--around X Z R]   (X Z R — блоки: только чанки, чей центр в круге)
"""
import collections
import gzip
import os
import re
import struct
import sys
import time
import zlib

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)
FIXED = {BYTE: 1, SHORT: 2, INT: 4, LONG: 8, FLOAT: 4, DOUBLE: 8}
FINISHED = {"light", "spawn", "heightmaps", "full"}
MAPS = {"MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES", "OCEAN_FLOOR", "WORLD_SURFACE"}


class Reader:
    def __init__(self, b):
        self.b, self.i = b, 0

    def u8(self):
        self.i += 1
        return self.b[self.i - 1]

    def i32(self):
        self.i += 4
        return struct.unpack_from(">i", self.b, self.i - 4)[0]

    def name(self):
        n = struct.unpack_from(">H", self.b, self.i)[0]
        self.i += 2 + n
        return self.b[self.i - n:self.i].decode("utf-8", "replace")


def skip(r, t):
    if t in FIXED:
        r.i += FIXED[t]
    elif t in (BYTE_ARRAY, INT_ARRAY, LONG_ARRAY):
        n = r.i32()  # не «r.i += r.i32()»: r.i читается до того, как i32 его сдвинет
        r.i += {BYTE_ARRAY: 1, INT_ARRAY: 4, LONG_ARRAY: 8}[t] * n
    elif t == STRING:
        r.name()
    elif t == LIST:
        et, n = r.u8(), r.i32()
        if et in FIXED:
            r.i += FIXED[et] * n
        else:
            for _ in range(n):
                skip(r, et)
    elif t == COMPOUND:
        while (ct := r.u8()) != END:
            r.name()
            skip(r, ct)
    else:
        raise ValueError(f"тег {t}")


def compound(r, want):
    """want: имя -> "val" (строка или число), "keys" (имена вложенного, без значений) или вложенный want."""
    out = {}
    while (t := r.u8()) != END:
        name = r.name()
        spec = want.get(name)
        if spec is None:
            skip(r, t)
        elif t == STRING and spec == "val":
            out[name] = r.name()
        elif t == INT and spec == "val":
            out[name] = r.i32()
        elif t == COMPOUND and spec == "keys":
            keys = set()
            while (ct := r.u8()) != END:
                keys.add(r.name())
                skip(r, ct)
            out[name] = keys
        elif t == COMPOUND and isinstance(spec, dict):
            out[name] = compound(r, spec)
        else:
            skip(r, t)
    return out


WANT = {"DataVersion": "val", "Status": "val", "Heightmaps": "keys", "below_zero_retrogen": {"target_status": "val"},
        "Level": {"Status": "val", "Heightmaps": "keys"}}


def norm(status):
    """Как ResourceLocation.tryParse против minecraft:…: имя без пространства имён — из minecraft."""
    if status is None:
        return None
    if status.startswith("minecraft:"):
        return status[10:]
    return None if ":" in status else status


def classify(tag):
    """(состояние PARTIAL/FINAL/WHOLE, подпись, карты высот целы) — то же правило, что DiskStatus.of."""
    level = tag.get("Level")
    if "below_zero_retrogen" in tag:
        target = tag["below_zero_retrogen"].get("target_status", "")
        state = "FINAL" if norm(target) in FINISHED else "PARTIAL"
        label = f"догенерация под нулём, цель {target or '—'}"
    elif "Status" in tag:
        state = "WHOLE" if norm(tag["Status"]) == "full" else "PARTIAL"
        label = "целый" if state == "WHOLE" else f"статус {tag['Status']}"
    elif level is not None and "Status" in level:
        state = "FINAL" if norm(level["Status"]) in FINISHED else "PARTIAL"
        label = f"формат до 1.18, статус {level['Status']}"
    else:
        state, label = "PARTIAL", "без статуса"
    maps = tag.get("Heightmaps", (level or {}).get("Heightmaps", set()))
    return state, label, MAPS <= maps


def chunks(path):
    m = re.match(r"r\.(-?\d+)\.(-?\d+)\.mca$", os.path.basename(path))
    if not m:
        return
    rx, rz = int(m[1]), int(m[2])
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 8192:
        return
    for idx in range(1024):
        loc = struct.unpack_from(">I", data, idx * 4)[0]
        off, count = (loc >> 8) * 4096, loc & 0xFF
        if off == 0 or count == 0:
            continue
        cx, cz = rx * 32 + idx % 32, rz * 32 + idx // 32
        try:
            length = struct.unpack_from(">I", data, off)[0]
            kind = data[off + 4]
            if kind & 128:
                with open(os.path.join(os.path.dirname(path), f"c.{cx}.{cz}.mcc"), "rb") as f:
                    payload = f.read()
                kind &= 127
            else:
                payload = data[off + 5:off + 4 + length]
            if kind == 1:
                raw = gzip.decompress(payload)
            elif kind == 2:
                raw = zlib.decompress(payload)
            elif kind == 3:
                raw = payload
            else:
                yield cx, cz, None, f"сжатие {kind}"
                continue
            r = Reader(raw)
            if r.u8() != COMPOUND:
                yield cx, cz, None, "корень не compound"
                continue
            r.name()
            yield cx, cz, compound(r, WANT), None
        except Exception as e:  # noqa: BLE001 — битый чанк считается, разбор идёт дальше
            yield cx, cz, None, f"не разобран: {type(e).__name__}"


def main():
    args = sys.argv[1:]
    if not args or not os.path.isdir(args[0]):
        sys.exit(__doc__)
    around = None
    if "--around" in args:
        i = args.index("--around")
        around = tuple(float(a) for a in args[i + 1:i + 4])
    t0 = time.time()
    labels, versions, broken = collections.Counter(), collections.Counter(), collections.Counter()
    states, mapsok = {}, {}
    for name in sorted(os.listdir(args[0])):
        for cx, cz, tag, err in chunks(os.path.join(args[0], name)):
            if around and (cx * 16 + 8 - around[0]) ** 2 + (cz * 16 + 8 - around[1]) ** 2 > around[2] ** 2:
                continue
            if tag is None:
                broken[err] += 1
                continue
            state, label, ok = classify(tag)
            states[(cx, cz)], mapsok[(cx, cz)] = state, ok
            labels[(state, label)] += 1
            versions[tag.get("DataVersion")] += 1
    print(f"папка {args[0]}" + (f", круг {around}" if around else "") + f": чанков на диске {len(states)}, за {time.time() - t0:.0f} с")
    for (state, label), n in labels.most_common():
        print(f"  {state:7} {n:7}  {label}")
    for err, n in broken.most_common():
        print(f"  ОШИБКА  {n:7}  {err}")
    print("  версии данных: " + ", ".join(f"{v}: {n}" for v, n in versions.most_common(8)))
    final = [c for c, s in states.items() if s != "PARTIAL"]
    nomaps = sum(1 for c in final if not mapsok[c])
    print(f"  с окончательными блоками {len(final)}, из них без всех карт высот {nomaps}")

    def readable(c, after):
        s = states.get(c)
        return s is not None and mapsok[c] and (s != "PARTIAL" if after else s == "WHOLE")

    for after in (False, True):
        own = [c for c in states if readable(c, after)]
        whole_window = sum(1 for (x, z) in own if all(readable((x + dx, z + dz), after) for dx in range(-2, 3) for dz in range(-2, 3)))
        print(f"  {'после' if after else 'до':5} правки: читается с диска {len(own)}, из них с целым окном 5×5 (план с диска) {whole_window}")


if __name__ == "__main__":
    main()
