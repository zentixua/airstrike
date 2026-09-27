#!/usr/bin/env bash
# Проверка → установка датапака во все миры инстанса → сборка zip в dist/
#
#   tools/deploy.sh          датапак во все миры (saves/*/datapacks/shahed)
#   tools/deploy.sh --rp     + обновить пакет звуков в resourcepacks/"Shahed Sounds"
#   tools/deploy.sh --dry    только проверка и сборка zip, игру не трогать
#
# Пути к игре — tools/paths.py (или MC_DIR=/путь/к/minecraft tools/deploy.sh).
# После деплоя в игре: /reload или перезайти в мир. Пакет звуков — F3+T; друзьям его раздаёт Essential.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
path() { python3 "$ROOT/tools/paths.py" "$1"; }
MC="$(path MC)"
DP="$(path DATAPACK)"
RP="$(path RESOURCEPACK)"
RP_NAME="$(path RP_INSTALL_NAME)"

WITH_RP=0; DRY=0
for a in "$@"; do
  case "$a" in
    --rp) WITH_RP=1 ;;
    --dry) DRY=1 ;;
    *) echo "неизвестный аргумент: $a" >&2; exit 2 ;;
  esac
done

python3 "$ROOT/tools/validate.py"
python3 "$ROOT/tools/pack.py"
if [ "$DRY" = 1 ]; then echo "--dry: игра не тронута"; exit 0; fi

[ -d "$MC/saves" ] || { echo "нет папки $MC/saves — проверь tools/paths.py или задай MC_DIR" >&2; exit 1; }
n=0
for w in "$MC/saves"/*/; do
  [ -f "$w/level.dat" ] || continue
  mkdir -p "$w/datapacks"
  rm -rf "$w/datapacks/shahed"
  cp -r "$DP" "$w/datapacks/shahed"
  echo "мир: $(basename "$w")"
  n=$((n + 1))
done
echo "датапак установлен в миров: $n"

if [ "$WITH_RP" = 1 ]; then
  mkdir -p "$MC/resourcepacks"
  rm -rf "$MC/resourcepacks/$RP_NAME"
  cp -r "$RP" "$MC/resourcepacks/$RP_NAME"
  echo "пакет звуков обновлён: resourcepacks/$RP_NAME"
fi
