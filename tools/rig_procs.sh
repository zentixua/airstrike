# Процессы проверок (stress.sh, mp_scenario.sh): каждый сервер и клиент — в своей сессии (setsid), остановка — всей
# сессией. Обёртки клиентов (xvfb-run, nested_kwin.sh → dbus-run-session → kwin_wayland) держат JVM внуком: kill
# обёртки оставлял Minecraft жить сиротой, а Ctrl+C в терминале до JVM в своей сессии и не доходит — гасит rig_stop_all.
# Подключается через source; вызывающий скрипт ставит trap (rig_trap).

RIG_SESSIONS=()

# rig_spawn <команда…>: в фоне и в новой сессии; RIG_LAST — pid, он же номер сессии (для wait и kill -0).
# Без управления заданиями фоновый процесс не лидер группы, и setsid делает сессию на месте, без fork.
rig_spawn() {
  setsid "$@" &
  RIG_LAST=$!
  RIG_SESSIONS+=("$RIG_LAST")
  local sid=""
  for _ in $(seq 50); do
    sid=$(ps -o sid= -p "$RIG_LAST" 2>/dev/null | tr -d ' ')
    [ "$sid" = "$RIG_LAST" ] && return 0
    [ -z "$sid" ] && return 0 # уже вышел
    sleep 0.1
  done
  echo "rig_spawn: $1 не в своей сессии (sid $sid), остановка не найдёт его детей" >&2
  return 1
}

# rig_stop <sid…>: TERM всей сессии (JVM успевает сохранить мир), через 20 с оставшимся — KILL.
rig_stop() {
  local sid alive
  for sid in "$@"; do pkill -TERM -s "$sid" 2>/dev/null || true; done
  for _ in $(seq 200); do
    alive=0
    for sid in "$@"; do pgrep -s "$sid" >/dev/null 2>&1 && alive=1; done
    [ $alive -eq 0 ] && return 0
    sleep 0.1
  done
  for sid in "$@"; do pkill -KILL -s "$sid" 2>/dev/null || true; done
}

rig_stop_all() {
  [ ${#RIG_SESSIONS[@]} -gt 0 ] && rig_stop "${RIG_SESSIONS[@]}"
  return 0
}

# выход по любой причине (конец, ошибка set -e, Ctrl+C, kill скрипта) гасит все сессии
rig_trap() {
  trap rig_stop_all EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 129' HUP
}
