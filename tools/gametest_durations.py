"""Время партий GameTest по логам — таблица, по которой CI делит GameTest между машинами (devtest GameTestShards).

    python3 tools/gametest_durations.py <лог>...   → mod/src/devtest/resources/gametest-durations.json

Лог — run/gametest/logs/latest.log или лог задачи GameTest из GitHub Actions; у прогона по частям — логи всех частей,
у партии из нескольких прогонов — среднее. Партия длится от своей строки «Running test batch» до следующей партии или
до конца тестов. Таблица только равняет части по времени: устаревшая не теряет тестов, части выходят неровнее.
"""
import json
import os
import re
import sys
from datetime import datetime

from paths import MOD

OUT = os.path.join(MOD, "src", "devtest", "resources", "gametest-durations.json")
# время строки: лог GitHub Actions («2026-10-01T16:29:59.2208167Z …») или latest.log («[01Oct2026 16:40:47.028] …»)
GITHUB_TIME = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?Z ")
LOG_TIME = re.compile(r"^\[(\d\d\w{3}\d{4} \d\d:\d\d:\d\d\.\d{3})\] ")
BATCH = re.compile(r"Running test batch '(.+):\d+' \(\d+ tests\)")
END = "GAME TESTS COMPLETE"


def time_of(line):
    m = GITHUB_TIME.match(line)
    if m:
        return datetime.fromisoformat(m.group(1)).timestamp() + float("0." + (m.group(2) or "0"))
    m = LOG_TIME.match(line)
    if m:
        return datetime.strptime(m.group(1), "%d%b%Y %H:%M:%S.%f").timestamp()
    return None


def batches(path):
    """Партия → секунды в одном логе."""
    out, current, start = {}, None, None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            at = time_of(line)
            if at is None:
                continue
            m = BATCH.search(line)
            if (m or END in line) and current is not None:
                out[current] = out.get(current, 0) + at - start
                current = None
            if m:
                current, start = m.group(1), at
    if current is not None:
        raise SystemExit(f"{path}: партия {current} не кончилась — лог оборван")
    return out


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    runs = {}
    for path in sys.argv[1:]:
        for name, seconds in batches(path).items():
            runs.setdefault(name, []).append(seconds)
    if not runs:
        raise SystemExit("в логах нет строк «Running test batch»")
    table = {name: round(sum(s) / len(s), 1) for name, s in sorted(runs.items())}
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(table, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")
    print(f"партий {len(table)}, всего {sum(table.values()):.0f} с → {OUT}")


if __name__ == "__main__":
    main()
