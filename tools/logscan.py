#!/usr/bin/env python3
"""Выжимка из логов игры всего, что касается airstrike (и старого имени shahed) — чтобы не просить игроков что-то проверять вручную.

  python3 tools/logscan.py              последняя сессия latest.log
  python3 tools/logscan.py --all        весь latest.log
  python3 tools/logscan.py --since 18:30  только строки после времени (ЧЧ:ММ)
  python3 tools/logscan.py path/to.log  другой файл (например logs/2026-09-27-1.log.gz)

Разделы: загрузки/перезагрузки датапака, ошибки загрузки функций и тегов, неизвестные звуки,
вызовы функций airstrike игроками, чат, жизненный цикл аппаратов Sable (sub-level added/removed),
вход/выход игроков.
"""
import gzip, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import MC
args = sys.argv[1:]
path = next((a for a in args if not a.startswith("--") and not re.match(r"^\d\d:\d\d$", a)), os.path.join(MC, "logs", "latest.log"))
since = None
if "--since" in args:
    since = args[args.index("--since") + 1]
opener = gzip.open if path.endswith(".gz") else open
lines = opener(path, "rt", encoding="utf-8", errors="replace").read().splitlines()

if "--all" not in args and since is None:
    # последняя сессия = от последней загрузки мира (Preparing level / Starting integrated server)
    starts = [i for i, l in enumerate(lines) if "Preparing start region" in l or "Starting integrated minecraft server" in l]
    if starts:
        lines = lines[starts[-1]:]


def t(l):
    m = re.match(r"\[\d+\w+\d+ (\d\d:\d\d:\d\d)", l)
    return m.group(1) if m else ""


if since:
    lines = [l for l in lines if t(l) >= since]

sections = [
    ("Загрузка датапаков / reload", r"Loaded \d+ recipes|Reloading ResourceManager|Starting integrated minecraft server|Stopping server"),
    ("Ошибки загрузки airstrike (функции, теги, JSON)", r"(?i)(fail|couldn.t|error|invalid|not all defined tags|missing data pack).*(airstrike|shahed)|(airstrike|shahed).*(fail|couldn.t|error|invalid)"),
    ("Неизвестные звуки (наши и модов, что мы используем)", r"Unable to play unknown soundEvent|Missing sound for event: (airstrike|shahed|snassets|petrochem|immersive_aircraft):"),
    ("Вызовы функций airstrike", r"Server thread/INFO.*Running function (airstrike|shahed):|Server thread/INFO.*Triggered \[(airstrike|shahed)"),
    ("Чат игроков", r"\[CHAT\] <"),
    ("Аппараты Sable (добавлены/удалены)", r"shtreimel\.lifecycle.*sub-level (added|removed)"),
    ("Игроки вход/выход", r"joined the game|left the game|lost connection"),
]
for title, pat in sections:
    hits = [l for l in lines if re.search(pat, l)]
    print(f"\n== {title}: {len(hits)}")
    for l in hits[-40:]:
        l = re.sub(r"^\[\d+\w+\d+ (\d\d:\d\d:\d\d)\.\d+\] \[[^\]]*\] \[[^\]]*\]: ", r"\1 ", l)
        print("  " + l[:240])
