#!/usr/bin/env bash
# Сборка мода и установка в инстанс Prism.
#
#   tools/deploy.sh          сборка и юнит-тесты → jar в mods/ инстанса и в dist/
#   tools/deploy.sh --test   ещё и GameTest (сервер без окна с Create, Sable и Aeronautics)
#   tools/deploy.sh --dry    только сборка и проверки, игру не трогать
#   tools/deploy.sh --jar F  без сборки: поставить готовый jar F (сборку CI или релиза — те же байты, что у друзей)
#
# Ничего не удаляется: старые jar мода, копии датапака (saves/*/datapacks/airstrike|shahed) и пакета звуков
# (resourcepacks/"Airstrike Sounds"|"Shahed Sounds") переносятся в airstrike-backup/<время>/ рядом с mods/.
# Мод заменяет их полностью; настройки датапака он переносит в свой конфиг сам при первом запуске мира.
# После установки — перезапустить игру. Друзьям нужен тот же jar (dist/airstrike-*.jar) в их mods/.
# Инстанс с автообновлением сборки (zentixua/airstrike-pack) deploy.sh не трогает: jar мода там — из сборки на её main.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
path() { python3 "$ROOT/tools/paths.py" "$1"; }
MC="$(path MC)"
MODS="$(path MODS)"
DIST="$(path DIST)"
BACKUP="$(path BACKUP)/$(date +%Y-%m-%d_%H%M%S)"
export JAVA_HOME="${JAVA_HOME:-$(path JAVA)}"

TEST=0; DRY=0; JAR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --test) TEST=1 ;;
    --dry) DRY=1 ;;
    --jar) [ $# -ge 2 ] || { echo "--jar: нужен путь к jar" >&2; exit 2; }; JAR="$(realpath "$2")"; shift ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$DIST"
if [ -n "$JAR" ]; then
  [[ "$(basename "$JAR")" == airstrike-*.jar && -f "$JAR" ]] || { echo "--jar: нужен файл airstrike-<версия>.jar" >&2; exit 2; }
  [ "$(dirname "$JAR")" = "$(realpath "$DIST")" ] || cp "$JAR" "$DIST/"
  echo "готовый: $JAR ($(sha256sum "$JAR" | cut -c1-64)) → dist/"
else
  cd "$ROOT/mod"
  ./gradlew build --console=plain -q
  if [ "$TEST" = 1 ]; then ./gradlew runGameTestServer --console=plain -q; fi
  JAR="$(realpath "$(ls -t build/libs/airstrike-*.jar | grep -v -- '-sources' | head -n1)")"
  cp "$JAR" "$DIST/"
  echo "собран: $JAR → dist/"
fi
if [ "$DRY" = 1 ]; then echo "--dry: игра не тронута"; exit 0; fi
# Перед запуском такого инстанса packwiz-installer ставит сборку: убранный jar сборки он вернёт, и Airstrike станет два.
if grep -qs '^PreLaunchCommand=.*packwiz-installer' "$(dirname "$MC")/instance.cfg"; then
  echo "инстанс обновляется сам из сборки airstrike-pack (packwiz-installer): мод приходит в игру выпуском и PR сборки;" \
    "игра не тронута" >&2
  exit 1
fi

[ -d "$MODS" ] || { echo "нет папки $MODS — проверь tools/paths.py или задай MC_DIR" >&2; exit 1; }
aside() { # перенести в сторону с сохранением пути относительно .minecraft
  local src="$1" rel="${1#"$MC"/}"
  mkdir -p "$BACKUP/$(dirname "$rel")"
  mv "$src" "$BACKUP/$rel"
  echo "в сторону: $rel"
}
for old in "$MODS"/airstrike-*.jar; do
  if [ -e "$old" ]; then aside "$old"; fi
done
for w in "$MC/saves"/*/; do
  for dp in $(path LEGACY_DATAPACKS); do
    if [ -e "$w/datapacks/$dp" ]; then aside "${w%/}/datapacks/$dp"; fi
  done
done
IFS='|' read -r -a rps <<< "$(path LEGACY_RESOURCEPACKS)"
for rp in "${rps[@]}"; do
  if [ -e "$MC/resourcepacks/$rp" ]; then aside "$MC/resourcepacks/$rp"; fi
done
cp "$JAR" "$MODS/"
echo "установлен: mods/$(basename "$JAR") — перезапусти игру"
