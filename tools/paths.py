"""Пути проекта и игры — единственное место, где они заданы.

Переопределяются переменными окружения:
  PRISM_DIR  папка данных Prism Launcher
  MC_DIR     папка .minecraft инстанса
  MC_JAR     клиентский jar 1.21.1
  MC_ASSETS  папка assets Prism (ванильные звуки)

Из shell:  python3 tools/paths.py MC
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATAPACK = os.path.join(ROOT, "datapack", "shahed")
RESOURCEPACK = os.path.join(ROOT, "resourcepack", "shahed-sounds")
DIST = os.path.join(ROOT, "dist")
BUILD = os.path.join(ROOT, "build")

RP_INSTALL_NAME = "Shahed Sounds"  # имя папки в resourcepacks/ — на него ссылается options.txt игроков
INSTANCE = "All of Create Aeronautics"
PRISM = os.path.expanduser(os.environ.get("PRISM_DIR", "~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"))
MC = os.environ.get("MC_DIR", os.path.join(PRISM, "instances", INSTANCE, "minecraft"))
JAR = os.environ.get("MC_JAR", os.path.join(PRISM, "libraries", "com", "mojang", "minecraft", "1.21.1", "minecraft-1.21.1-client.jar"))
ASSETS = os.environ.get("MC_ASSETS", os.path.join(PRISM, "assets"))
MODS = os.path.join(MC, "mods")

if __name__ == "__main__":
    print(globals()[sys.argv[1]])
