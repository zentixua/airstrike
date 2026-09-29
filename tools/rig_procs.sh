# Процессы проверок (stress.sh, mp_scenario.sh): каждый сервер и клиент — в своей сессии (setsid), остановка — всей
# сессией. Обёртки клиентов (xvfb-run, nested_kwin.sh → dbus-run-session → kwin_wayland) держат JVM внуком: kill
# обёртки оставлял Minecraft жить сиротой, а Ctrl+C в терминале до JVM в своей сессии и не доходит — гасит rig_stop_all.
# Подключается через source; вызывающий скрипт ставит trap (rig_trap).

RIG_SESSIONS=()
# метка в окружении всех процессов запуска (её несут exec env, xvfb-run, dbus-run-session и KWin): сессию с тем же
# номером после оборота PID, но без метки, остановка не тронет
RIG_TAG="AIRSTRIKE_RIG_TAG=$$.$RANDOM"
# сообщения — в терминал скрипта, а не в .out, куда перенаправлен вызов rig_spawn
exec {RIG_ERR}>&2

# rig_spawn <команда…>: в фоне и в новой сессии; RIG_LAST — pid, он же номер сессии (для wait и kill -0).
# Без управления заданиями фоновый процесс не лидер группы, и setsid делает сессию на месте, без fork.
rig_spawn() {
  env "$RIG_TAG" setsid "$@" &
  RIG_LAST=$!
  RIG_SESSIONS+=("$RIG_LAST")
  local sid=""
  for _ in $(seq 50); do
    sid=$(ps -o sid= -p "$RIG_LAST" 2>/dev/null | tr -d ' ') || sid=""
    [ "$sid" = "$RIG_LAST" ] && return 0
    [ -z "$sid" ] && return 0 # уже вышел: скрипт сам увидит это по kill -0 и скажет, что не так
    sleep 0.1
  done
  echo "rig: $1 не в своей сессии (sid $sid), остановка не найдёт его детей" >&$RIG_ERR
  return 1
}

# процессы сессии с нашей меткой (пусто — сессии нет или номер уже чужой)
rig_members() {
  local pid
  for pid in $(pgrep -s "$1" 2>/dev/null); do
    grep -qzxF "$RIG_TAG" "/proc/$pid/environ" 2>/dev/null && echo "$pid"
  done
  return 0
}

# rig_stop <sid…>: TERM всей сессии (JVM успевает сохранить мир), через 20 с оставшимся — KILL.
rig_stop() {
  local sid live=() left
  for sid in "$@"; do [ -n "$(rig_members "$sid")" ] && live+=("$sid"); done
  [ ${#live[@]} -eq 0 ] && return 0
  for sid in "${live[@]}"; do pkill -TERM -s "$sid" 2>/dev/null || true; done
  local deadline=$((SECONDS + 20))
  while [ $SECONDS -lt $deadline ]; do
    left=()
    for sid in "${live[@]}"; do [ -n "$(rig_members "$sid")" ] && left+=("$sid"); done
    [ ${#left[@]} -eq 0 ] && return 0
    sleep 0.2
  done
  for sid in "${left[@]}"; do
    echo "rig: сессия $sid не вышла за 20 с после TERM — KILL: $(ps -o comm= -s "$sid" 2>/dev/null | sort -u | tr '\n' ' ')" >&$RIG_ERR
    pkill -KILL -s "$sid" 2>/dev/null || true
  done
}

rig_stop_all() {
  # повторный Ctrl+C во время остановки не должен оборвать её до KILL
  trap '' INT TERM HUP PIPE
  if [ ${#RIG_SESSIONS[@]} -gt 0 ]; then
    [ -n "${RIG_SIG:-}" ] && { echo "rig: останавливаю запуски (до 20 с)…" >&$RIG_ERR || true; }
    rig_stop "${RIG_SESSIONS[@]}"
  fi
  # выход по сигналу — тем же сигналом: цикл вокруг скрипта и systemd видят прерывание, а не код ошибки
  if [ -n "${RIG_SIG:-}" ]; then
    trap - "$RIG_SIG" EXIT
    kill -"$RIG_SIG" $$
  fi
}

# выход по любой причине (конец, ошибка set -e, Ctrl+C, kill скрипта) гасит все сессии
rig_trap() {
  trap rig_stop_all EXIT
  trap 'RIG_SIG=INT; exit 130' INT
  trap 'RIG_SIG=TERM; exit 143' TERM
  trap 'RIG_SIG=HUP; exit 129' HUP
}
