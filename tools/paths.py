"""Пути проекта и игры — единственное место, где они заданы.

Переопределяются переменными окружения:
  PRISM_DIR  папка данных Prism Launcher
  MC_DIR     папка .minecraft инстанса

Из shell:  python3 tools/paths.py MC
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.path.join(ROOT, "mod")
DIST = os.path.join(ROOT, "dist")

INSTANCE = "Airstrike Pack"
PRISM = os.path.expanduser(os.environ.get("PRISM_DIR", "~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"))
MC = os.environ.get("MC_DIR", os.path.join(PRISM, "instances", INSTANCE, "minecraft"))
MODS = os.path.join(MC, "mods")
JAVA = os.path.join(PRISM, "java", "java-runtime-delta")
# сюда deploy.sh переносит старые версии мода и копии датапака/пакета звуков — удаляет их только Артём
BACKUP = os.path.join(MC, "airstrike-backup")

# до мода был датапак (сначала под именем shahed) и пакет звуков: их копии в игре больше не нужны
LEGACY_DATAPACKS = "airstrike shahed"
LEGACY_RESOURCEPACKS = "Airstrike Sounds|Shahed Sounds"

if __name__ == "__main__":
    print(globals()[sys.argv[1]])
