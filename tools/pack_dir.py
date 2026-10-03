#!/usr/bin/env python3
"""Каталог игры из своей сборки pack/ (packwiz) — без Prism и без packwiz: для проверок клиентом prod_client.py.

  tools/pack_dir.py mod/run/pack-b [--optional]
  tools/prod_client.py <сценарий> --no-copy --dir mod/run/pack-b   (сборку мода со сценариями кладёт prod_client.py)

Те же файлы, что ставит Prism из .mrpack сборки: моды и шейдерпаки по `*.pw.toml` (скачанный файл сверяется с хешем
из них), остальное из index.toml (`config/…`) — как есть. Только для клиента: `side = "server"` не ставится;
необязательные (`[option] optional = true`) — как их галочка по умолчанию (`default`, как у packwiz-installer):
выключенные — `<файл>.disabled`, как их ставит Prism без галочки; с --optional — все включёнными. Индекс должен быть
свежим (хеши метафайлов сверяются, как в CI: `packwiz refresh`).
Скачанное лежит в кэше по хешу (по умолчанию mod/run/pack-cache) и второй раз не качается. Каталог должен быть новым
или без mods/: смешивать с прежним набором модов нельзя.
"""
import argparse
import hashlib
import http.client
import os
import shutil
import sys
import time
import tomllib
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

PACK = os.path.join(paths.ROOT, "pack")
USER_AGENT = "zentixua/airstrike tools/pack_dir.py"


def digest(path, algo):
    h = hashlib.new(algo)
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def inside(root, rel):
    """Путь из файлов сборки — только внутри каталога (как требует формат .mrpack)."""
    path = os.path.normpath(os.path.join(root, rel))
    if os.path.isabs(rel) or not path.startswith(root + os.sep):
        sys.exit(f"путь {rel!r} выходит из {root}")
    return path


def fetch(name, download, cache):
    algo, want = download["hash-format"], download["hash"].lower()
    cached = os.path.join(cache, f"{algo}-{want}")
    if os.path.isfile(cached):
        return cached
    tmp = cached + ".part"
    error = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(download["url"], headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as out:
                shutil.copyfileobj(r, out)
            break
        except (OSError, http.client.HTTPException) as e:  # обрыв посреди файла — IncompleteRead
            error = e
            if attempt < 3:
                time.sleep(2 << attempt)
    else:
        sys.exit(f"{name}: не скачать {download['url']}: {error}")
    got = digest(tmp, algo)
    if got != want:
        os.remove(tmp)
        sys.exit(f"{name}: {algo} скачанного {got} ≠ {want} ({download['url']})")
    os.replace(tmp, cached)
    return cached


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("dir", help="каталог игры (новый)")
    ap.add_argument("--optional", action="store_true", help="все необязательные моды включить")
    ap.add_argument("--cache", default=os.path.join(paths.MOD, "run", "pack-cache"))
    a = ap.parse_args()
    dest = os.path.abspath(a.dir)
    if os.path.isdir(os.path.join(dest, "mods")):
        sys.exit(f"в {dest} уже есть mods/ — нужен новый каталог")

    with open(os.path.join(PACK, "pack.toml"), "rb") as f:
        pack = tomllib.load(f)
    index_file = os.path.join(PACK, pack["index"]["file"])
    if digest(index_file, pack["index"]["hash-format"]) != pack["index"]["hash"]:
        sys.exit("index.toml не тот, что в pack.toml: packwiz refresh")
    with open(index_file, "rb") as f:
        index = tomllib.load(f)
    print(f"{pack['name']} {pack['version']}: " + ", ".join(f"{k} {v}" for k, v in pack["versions"].items()))

    os.makedirs(a.cache, exist_ok=True)
    on, off, files = [], [], 0
    for entry in index["files"]:
        src = inside(PACK, entry["file"])
        if digest(src, entry.get("hash-format", index["hash-format"])) != entry["hash"]:
            sys.exit(f"{entry['file']}: хеш не тот, что в index.toml: packwiz refresh")
        if not entry.get("metafile"):
            out = inside(dest, entry["file"])
            os.makedirs(os.path.dirname(out), exist_ok=True)
            shutil.copyfile(src, out)
            files += 1
            continue
        with open(src, "rb") as f:
            meta = tomllib.load(f)
        if meta.get("side", "both") == "server":
            continue
        rel = os.path.join(os.path.dirname(entry["file"]), meta["filename"])
        option = meta.get("option", {})
        enabled = a.optional or not option.get("optional", False) or option.get("default", False)
        out = inside(dest, rel if enabled else rel + ".disabled")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        shutil.copyfile(fetch(meta["name"], meta["download"], a.cache), out)
        (on if enabled else off).append(meta["filename"])
    print(f"включено {len(on)}, выключено {len(off)}{': ' + ', '.join(off) if off else ''}; файлов как есть {files} → {dest}")


if __name__ == "__main__":
    main()
