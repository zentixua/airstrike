#!/usr/bin/env python3
"""Экземпляр Prism Launcher со сборкой pack/, которая обновляется сама при каждом запуске игры.

  tools/prism_instance.py ["dist/Airstrike Pack.zip"]

В архиве — пустой экземпляр (Minecraft и NeoForge из pack.toml, память как в инструкции для друзей) и
packwiz-installer-bootstrap в папке игры, а команда перед запуском экземпляра — packwiz-installer со сборкой
из main этого репозитория (https://packwiz.infra.link/tutorials/installing/packwiz-installer/). Prism: «Добавить
экземпляр» → «Импорт» → этот файл; на первом запуске packwiz-installer скачивает моды и спрашивает про необязательные,
на каждом следующем ставит то, что изменилось в pack/ на main. Архив от версии сборки не зависит: версии Minecraft
и NeoForge в экземпляре packwiz-installer сам приводит к pack.toml (спросив игрока).
"""
import argparse
import hashlib
import json
import os
import stat
import sys
import tomllib
import urllib.request
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

PACK_URL = "https://raw.githubusercontent.com/zentixua/airstrike/main/pack/pack.toml"
BOOTSTRAP = "packwiz-installer-bootstrap.jar"
BOOTSTRAP_URL = f"https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/{BOOTSTRAP}"
BOOTSTRAP_SHA256 = "a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c"
# как в инструкции для друзей: игре с шейдерами нужно 1,5–2,5 ГБ
MAX_MEMORY_MB = 6144
# компоненты Prism (mmc-pack.json) по ключам [versions] в pack.toml — те же, что сверяет packwiz-installer
COMPONENTS = {"minecraft": "net.minecraft", "neoforge": "net.neoforged"}
USER_AGENT = "zentixua/airstrike tools/prism_instance.py"


def ini_value(s):
    """Значение instance.cfg так, как его пишет Prism (QSettings, формат INI): \\ и " — через обратную косую."""
    return s.replace("\\", "\\\\").replace('"', '\\"')


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("out", nargs="?", default=os.path.join(paths.DIST, "Airstrike Pack.zip"))
    a = ap.parse_args()

    with open(os.path.join(paths.ROOT, "pack", "pack.toml"), "rb") as f:
        pack = tomllib.load(f)
    versions = pack["versions"]
    unknown = versions.keys() - COMPONENTS.keys()
    if unknown:
        sys.exit(f"pack.toml: нет компонента Prism для {', '.join(sorted(unknown))}")

    req = urllib.request.Request(BOOTSTRAP_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        bootstrap = r.read()
    got = hashlib.sha256(bootstrap).hexdigest()
    if got != BOOTSTRAP_SHA256:
        sys.exit(f"{BOOTSTRAP}: sha256 {got} ≠ {BOOTSTRAP_SHA256}")

    # команда — из инструкции packwiz; рабочая папка команды перед запуском у Prism — папка игры экземпляра
    command = f'"$INST_JAVA" -jar {BOOTSTRAP} {PACK_URL}'
    instance_cfg = "".join(f"{k}={v}\n" for k, v in [
        ("ConfigVersion", "1.3"),
        ("InstanceType", "OneSix"),
        ("name", ini_value(pack["name"])),
        ("OverrideCommands", "true"),
        ("PreLaunchCommand", ini_value(command)),
        ("OverrideMemory", "true"),
        ("MaxMemAlloc", str(MAX_MEMORY_MB)),
    ])
    components = [{"uid": COMPONENTS[k], "version": versions[k]} for k in ("minecraft", "neoforge") if k in versions]
    components[0]["important"] = True  # Minecraft: Prism не даёт убрать его из экземпляра
    mmc_pack = json.dumps({"components": components, "formatVersion": 1}, indent=4) + "\n"

    out = os.path.abspath(a.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in [("instance.cfg", "[General]\n" + instance_cfg), ("mmc-pack.json", mmc_pack),
                           (f"minecraft/{BOOTSTRAP}", bootstrap)]:
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.external_attr = (stat.S_IFREG | 0o644) << 16  # обычный файл rw-r--r--: без прав распаковщик ставит ноль
            z.writestr(info, data, zipfile.ZIP_DEFLATED)
    print(f"{pack['name']}: " + ", ".join(f"{k} {v}" for k, v in versions.items()) + f"; сборка {PACK_URL} → {out}")


if __name__ == "__main__":
    main()
