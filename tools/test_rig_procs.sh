#!/usr/bin/env bash
# Проверка tools/rig_procs.sh (CI и вручную): процессы проверок гасятся целиком, сирот не остаётся.
#   tools/test_rig_procs.sh   → строки ok/FAIL, код 1 при любой ошибке
# Вместо JVM — sleep с уникальными числами под обёрткой-bash (внук, как JVM под xvfb-run/nested_kwin), в том числе
# глухой к TERM; срок до KILL — RIG_GRACE=2 вместо 20 с.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
FAILS=0
# метки sleep — «<B><две цифры>»: B от PID теста, два прогона на одной машине не путают процессы друг друга
B=$(( $$ % 90000 + 10000 ))
export B
T=""
ok() { echo "ok   $*"; }
fail() { echo "FAIL $*"; FAILS=$((FAILS + 1)); }
alive() { pgrep -f "^sleep $B$1\$" >/dev/null; } # живой (зомби pgrep -f не видит: у них нет командной строки)
cleanup() {
  [ -n "$T" ] && kill -KILL "$T" 2>/dev/null
  pkill -KILL -f "^sleep $B[0-9][0-9]\$" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

# сценарий: скрипт с rig_trap запускает сессии и ждёт; $1 — тело после rig_trap, $2 — RIG_GRACE (2)
subject() {
  cat > "$WORK/subject.sh" <<EOF
set -euo pipefail
export RIG_GRACE=${2:-2}
source "$ROOT/tools/rig_procs.sh"
rig_trap
$1
EOF
}

# ждёт, пока появятся все sleep-метки, или 10 с
wait_for() { for _ in $(seq 100); do local all=1; for n in "$@"; do alive "$n" || all=0; done; [ $all -eq 1 ] && return 0; sleep 0.1; done; return 1; }

# 1. Ctrl+C: глухой к TERM внук добит KILL по сроку RIG_GRACE, повторный Ctrl+C во время остановки её не обрывает,
# скрипт умирает по INT
subject 'rig_spawn bash -c "trap \"\" TERM; sleep ${B}01 & sleep ${B}02 & wait" > /dev/null 2>&1
rig_spawn bash -c "sleep ${B}03 & wait" > /dev/null 2>&1
wait
sleep ${B}99'
set -m
bash "$WORK/subject.sh" 2>"$WORK/err1" & T=$!
set +m
if wait_for 01 02 03; then
  kill -INT $T; sleep 0.5; kill -INT $T
  wait $T; st=$?; T=""
  [ $st -eq 130 ] && ok "Ctrl+C: скрипт умер по INT" || fail "Ctrl+C: код $st, ждали 130"
  for n in 01 02 03; do alive $n && fail "Ctrl+C: sleep $B$n жив"; done
  alive 01 || alive 02 || alive 03 || ok "Ctrl+C: сирот нет"
  grep -q "не вышла за 2 с после TERM — KILL" "$WORK/err1" && ok "Ctrl+C: KILL глухого к TERM через RIG_GRACE записан" \
    || fail "Ctrl+C: нет строки о KILL через 2 с: $(cat "$WORK/err1")"
else fail "Ctrl+C: процессы не запустились"; fi

# 2. TERM скрипту: скрипт умирает самим сигналом TERM (не exit 143: цикл вокруг и systemd видят прерывание), сессии
# погашены; код смерти отличает только waitpid — Python
subject 'rig_spawn bash -c "sleep ${B}11 & wait" > /dev/null 2>&1
wait'
st=$(python3 - "$WORK/subject.sh" "^sleep ${B}11\$" <<'PY'
import signal, subprocess, sys, time
p = subprocess.Popen(["bash", sys.argv[1]], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for _ in range(100):
    if subprocess.run(["pgrep", "-f", sys.argv[2]], capture_output=True).returncode == 0:
        break
    time.sleep(0.1)
else:
    p.kill()
    print("не запустился")
    sys.exit()
p.send_signal(signal.SIGTERM)
try:
    print(p.wait(timeout=30))
except subprocess.TimeoutExpired:
    p.kill()
    print("не вышел за 30 с")
PY
)
[ "$st" = "-15" ] && ok "TERM: скрипт умер по сигналу TERM" || fail "TERM: $st, ждали -15 (смерть по TERM)"
sleep 0.3
alive 11 && fail "TERM: sleep $B""11 жив" || ok "TERM: сирот нет"

# 3. обычный конец: оставшиеся сессии погашены; сразу вышедший запуск не роняет скрипт
subject 'rig_spawn bash -c "exit 3" > /dev/null 2>&1
p=$RIG_LAST
rig_spawn bash -c "sleep ${B}21 & wait" > /dev/null 2>&1
started=0
for _ in $(seq 100); do pgrep -f "^sleep ${B}21\$" >/dev/null && { started=1; break; }; sleep 0.1; done
[ $started -eq 1 ] || echo "не запустился"
sleep 0.5
kill -0 $p 2>/dev/null && echo "первый ещё жив" || echo "первый вышел"'
out=$(bash "$WORK/subject.sh" 2>&1); st=$?
[ $st -eq 0 ] && [[ $out == *"первый вышел"* ]] && [[ $out != *"не запустился"* ]] \
  && ok "конец: вышедший сразу запуск не уронил скрипт" || fail "конец: код $st, вывод: $out"
sleep 0.3
alive 21 && fail "конец: sleep $B""21 жив" || ok "конец: сирот нет"

# 4. чужая сессия без метки (номер после оборота PID) не тронута; fd сообщений детям не достаётся
setsid sleep "${B}31" & F=$!
subject "echo \$RIG_ERR > $WORK/errfd
rig_spawn bash -c 'ls -l /proc/self/fd > $WORK/fds; sleep ${B}32' > /dev/null 2>&1
RIG_SESSIONS+=($F)
wait_for() { for _ in \$(seq 100); do pgrep -f '^sleep ${B}32\$' >/dev/null && return 0; sleep 0.1; done; }
wait_for"
bash "$WORK/subject.sh" 2>/dev/null; sleep 0.3
alive 31 && ok "чужая сессия без метки не тронута" || fail "чужая сессия убита"
kill $F 2>/dev/null
alive 32 && fail "fd: sleep $B""32 жив" || true
fd=$(cat "$WORK/errfd")
[ -s "$WORK/fds" ] || fail "fd: список не записан"
grep -q " $fd -> " "$WORK/fds" && fail "fd сообщений $fd унаследован" || ok "fd сообщений ($fd) детям не достаётся"

# 5. RIG_GRACE=0 — KILL сразу (цикл ожидания не идёт ни разу), не число — срок по умолчанию, а не выход по set -u
subject 'rig_spawn bash -c "trap \"\" TERM; sleep ${B}41 & wait" > /dev/null 2>&1
for _ in $(seq 100); do pgrep -f "^sleep ${B}41\$" >/dev/null && break; sleep 0.1; done' 0
bash "$WORK/subject.sh" 2>"$WORK/err5"; sleep 0.3
alive 41 && fail "RIG_GRACE=0: sleep $B""41 жив" || ok "RIG_GRACE=0: глухой к TERM добит сразу"
grep -q "не вышла за 0 с после TERM — KILL" "$WORK/err5" || fail "RIG_GRACE=0: нет строки о KILL: $(cat "$WORK/err5")"
bad=$FAILS
for g in abc 20s -3 08; do
  got=$(RIG_GRACE=$g bash -c 'set -euo pipefail; source "$1/tools/rig_procs.sh"; rig_grace' _ "$ROOT" 2>&1)
  want=20; [ "$g" = 08 ] && want=8
  [ "$got" = "$want" ] || fail "RIG_GRACE=$g: срок «$got», ждали $want"
done
[ $FAILS -eq $bad ] && ok "RIG_GRACE: не число — 20 с, 08 — 8 с"

[ $FAILS -eq 0 ] && echo "rig_procs: всё ok" || echo "rig_procs: ошибок $FAILS"
exit $((FAILS > 0))
