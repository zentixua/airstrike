#!/usr/bin/env bash
# Проверка tools/rig_procs.sh (CI и вручную): процессы проверок гасятся целиком, сирот не остаётся.
#   tools/test_rig_procs.sh   → строки ok/FAIL, код 1 при любой ошибке
# Вместо JVM — sleep с уникальными числами под обёрткой-bash (внук, как JVM под xvfb-run/nested_kwin), в том числе
# глухой к TERM; срок до KILL — RIG_GRACE=2 вместо 20 с.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
FAILS=0
ok() { echo "ok   $*"; }
fail() { echo "FAIL $*"; FAILS=$((FAILS + 1)); }
alive() { pgrep -f "^sleep $1\$" >/dev/null; } # живой (зомби pgrep -f не видит: у них нет командной строки)
cleanup() { pkill -KILL -f '^sleep 77[0-9][0-9]$' 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

# сценарий: скрипт с rig_trap запускает сессии и ждёт; $1 — тело после rig_trap
subject() {
  cat > "$WORK/subject.sh" <<EOF
set -euo pipefail
export RIG_GRACE=2
source "$ROOT/tools/rig_procs.sh"
rig_trap
$1
EOF
}

# ждёт, пока появятся все sleep-метки, или 10 с
wait_for() { for _ in $(seq 100); do local all=1; for n in "$@"; do alive "$n" || all=0; done; [ $all -eq 1 ] && return 0; sleep 0.1; done; return 1; }

# 1. Ctrl+C: глухой к TERM внук добит KILL, повторный Ctrl+C во время остановки её не обрывает, скрипт умирает по INT
subject 'rig_spawn bash -c "trap \"\" TERM; sleep 7701 & sleep 7702 & wait" > /dev/null 2>&1
rig_spawn bash -c "sleep 7703 & wait" > /dev/null 2>&1
wait
sleep 600'
set -m
bash "$WORK/subject.sh" 2>"$WORK/err1" & T=$!
set +m
if wait_for 7701 7702 7703; then
  kill -INT $T; sleep 0.5; kill -INT $T
  wait $T; st=$?
  [ $st -eq 130 ] && ok "Ctrl+C: скрипт умер по INT" || fail "Ctrl+C: код $st, ждали 130"
  for n in 7701 7702 7703; do alive $n && fail "Ctrl+C: sleep $n жив"; done
  alive 7701 || alive 7702 || alive 7703 || ok "Ctrl+C: сирот нет"
  grep -q "KILL" "$WORK/err1" && ok "Ctrl+C: KILL глухого к TERM записан" || fail "Ctrl+C: нет строки о KILL: $(cat "$WORK/err1")"
else fail "Ctrl+C: процессы не запустились"; kill -KILL $T 2>/dev/null; fi

# 2. TERM скрипту: скрипт умирает самим сигналом TERM (не exit 143: цикл вокруг и systemd видят прерывание), сессии
# погашены; код смерти отличает только waitpid — Python
subject 'rig_spawn bash -c "sleep 7711 & wait" > /dev/null 2>&1
wait'
st=$(python3 - "$WORK/subject.sh" <<'PY'
import signal, subprocess, sys, time
p = subprocess.Popen(["bash", sys.argv[1]], stderr=subprocess.DEVNULL)
for _ in range(100):
    if subprocess.run(["pgrep", "-f", "^sleep 7711$"], capture_output=True).returncode == 0:
        break
    time.sleep(0.1)
p.send_signal(signal.SIGTERM)
print(p.wait(timeout=30))
PY
)
[ "$st" = "-15" ] && ok "TERM: скрипт умер по сигналу TERM" || fail "TERM: returncode $st, ждали -15 (смерть по TERM)"
sleep 0.3
alive 7711 && fail "TERM: sleep 7711 жив" || ok "TERM: сирот нет"

# 3. обычный конец: оставшиеся сессии погашены; сразу вышедший запуск не роняет скрипт
subject 'rig_spawn bash -c "exit 3" > /dev/null 2>&1
p=$RIG_LAST
rig_spawn bash -c "sleep 7721 & wait" > /dev/null 2>&1
for _ in $(seq 100); do pgrep -f "^sleep 7721\$" >/dev/null && break; sleep 0.1; done
sleep 0.5
kill -0 $p 2>/dev/null && echo "первый ещё жив" || echo "первый вышел"'
out=$(bash "$WORK/subject.sh" 2>&1); st=$?
[ $st -eq 0 ] && [[ $out == *"первый вышел"* ]] && ok "конец: вышедший сразу запуск не уронил скрипт" || fail "конец: код $st, вывод: $out"
sleep 0.3
alive 7721 && fail "конец: sleep 7721 жив" || ok "конец: сирот нет"

# 4. чужая сессия без метки (номер после оборота PID) не тронута; fd сообщений детям не достаётся
setsid sleep 7731 & F=$!
subject "echo \$RIG_ERR > $WORK/errfd
rig_spawn bash -c 'ls -l /proc/self/fd > $WORK/fds; sleep 7732' > /dev/null 2>&1
RIG_SESSIONS+=($F)
wait_for() { for _ in \$(seq 100); do pgrep -f '^sleep 7732\$' >/dev/null && return 0; sleep 0.1; done; }
wait_for"
bash "$WORK/subject.sh" 2>/dev/null; sleep 0.3
alive 7731 && ok "чужая сессия без метки не тронута" || fail "чужая сессия убита"
kill $F 2>/dev/null
alive 7732 && fail "fd: sleep 7732 жив" || true
fd=$(cat "$WORK/errfd")
[ -s "$WORK/fds" ] || fail "fd: список не записан"
grep -q " $fd -> " "$WORK/fds" && fail "fd сообщений $fd унаследован" || ok "fd сообщений ($fd) детям не достаётся"

[ $FAILS -eq 0 ] && echo "rig_procs: всё ok" || echo "rig_procs: ошибок $FAILS"
exit $((FAILS > 0))
