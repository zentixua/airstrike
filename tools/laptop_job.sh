#!/usr/bin/env bash
# Тяжёлая задача на ноутбуке хоста (проверки со всей сборкой, клиенты сценариев, монтаж трейлера, стенд):
#
#   tools/laptop_job.sh <имя> -- <команда …>
#
# Задача идёт своей временной службой systemd --user (systemd-run --wait --collect), а не в группе того, кто её
# запустил (например, приложения Claude): по её выходу гасится всё, что осталось в группе (помощники вложенной сессии
# D-Bus не висят), а если памяти в системе всё же не хватит, первой пойдёт она (OOMScoreAdjust), а не программы
# Артёма. Пока задача идёт, ноутбук не засыпает (systemd-inhibit). Пределов памяти нет: одновременно — одна задача
# со всей сборкой или стенд, лёгкие — рядом, только если по сумме помещаются в ОЗУ.
set -euo pipefail
[ $# -ge 3 ] && [ "$2" = "--" ] || { echo "использование: $0 <имя> -- <команда …>" >&2; exit 2; }
name=$1; shift 2

# окружение службы — только нужное задаче (служба не наследует окружение оболочки)
envs=()
while IFS= read -r line; do envs+=(-E "$line"); done < <(env | grep -E '^(JAVA_HOME|MC_DIR|MODS|AIRSTRIKE_[A-Z_]+|PYTHON_CPU_COUNT)=' || true)

exec systemd-inhibit --what=sleep:idle --who="Airstrike: $name" --why="проверка мода (tools/laptop_job.sh)" \
  systemd-run --user --wait --pipe --collect --quiet --same-dir --unit="airstrike-job-$name-$$" \
    -p OOMScoreAdjust=500 "${envs[@]}" -- "$@"
