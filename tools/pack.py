#!/usr/bin/env python3
"""Собрать dist/shahed_datapack.zip и dist/Shahed_Sounds_v2.zip.
pack.mcmeta лежит в корне архива — zip можно класть прямо в datapacks/ или resourcepacks/."""
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import ROOT, DATAPACK, RESOURCEPACK, DIST


def zipdir(src, out):
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for r, d, fs in os.walk(src):
            d.sort()
            for f in sorted(fs):
                p = os.path.join(r, f)
                z.write(p, os.path.relpath(p, src))
    print(f"{os.path.relpath(out, ROOT)}  {os.path.getsize(out) // 1024} КБ")


os.makedirs(DIST, exist_ok=True)
zipdir(DATAPACK, os.path.join(DIST, "shahed_datapack.zip"))
zipdir(RESOURCEPACK, os.path.join(DIST, "Shahed_Sounds_v2.zip"))
