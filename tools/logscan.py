#!/usr/bin/env python3
"""Выжимка из лога игры всего, что касается мода Airstrike, — чтобы не просить игроков что-то проверять вручную.

  python3 tools/logscan.py                последняя сессия latest.log
  python3 tools/logscan.py --all          весь latest.log
  python3 tools/logscan.py --since 18:30  только строки после времени (ЧЧ:ММ)
  python3 tools/logscan.py path/to.log    другой файл (например logs/2026-09-27-1.log.gz)

Разделы: загрузка мода, ошибки и предупреждения мода (со стеком), удары и ядерные подрывы (мод пишет строку
на каждый приказ), отставание сервера («Can't keep up», телепорты, медленные попадания и ядерные тики мода рядом по
времени), переезд со старого датапака, неизвестные звуки, аппараты Sable, чат, вход/выход игроков.
"""
import gzip
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import MC  # noqa: E402

args = sys.argv[1:]
path = next((a for a in args if not a.startswith("--") and not re.match(r"^\d\d:\d\d$", a)), os.path.join(MC, "logs", "latest.log"))
since = args[args.index("--since") + 1] if "--since" in args else None
opener = gzip.open if path.endswith(".gz") else open
with opener(path, "rt", encoding="utf-8", errors="replace") as f:
    lines = f.read().splitlines()

if "--all" not in args and since is None:
    # последняя сессия — от последнего запуска мира
    starts = [i for i, l in enumerate(lines) if "Starting integrated minecraft server" in l or "Preparing start region" in l]
    if starts:
        lines = lines[starts[-1]:]


def time_of(line):
    m = re.match(r"\[(?:\d+\S*\s)?(\d\d:\d\d:\d\d)", line)
    return m.group(1) if m else ""


if since:
    lines = [l for l in lines if time_of(l) >= since]

# стек исключения: строки «\tat …» и «Caused by» после строки с ошибкой нашего мода
errors = []
for i, l in enumerate(lines):
    if re.search(r"/(ERROR|WARN)\]", l) and re.search(r"(?i)airstrike|ua\.zentix|ua\.ze\.ai", l) and "Assets URL" not in l:
        block = [l]
        for nxt in lines[i + 1:i + 40]:
            if nxt.startswith(("\tat ", "Caused by", "java.", "\t... ")) or re.match(r"^\S+Exception", nxt):
                block.append(nxt)
            else:
                break
        errors.append("\n    ".join(block[:12]))

sections = [
    ("Загрузка мода", r"Loading mod airstrike|Found mod file airstrike|airstrike.*(mod file|version)"),
    ("Удары и ядерные подрывы", r"Airstrike/\]: (Удар|Ядерный подрыв|МБР)"),
    # «Can't keep up» — накопленное отставание с прошлой такой строки (не чаще 15 с игры), а не одна пауза;
    # рядом по времени — удары и медленные тики мода; строки чата (игрок написал «Teleported…»:
    # «[CHAT] <…>» у клиента, «]: <…>» у сервера) — не в счёт
    ("Отставание сервера", r"^(?!.*(\[CHAT\]|\]: (\[Not Secure\] )?<)).*(Can't keep up|Airstrike/\]: (Удар:|Попадания \(|Итог удара \(|Итог серии попаданий \(|Взрыв: блок|Ядерный тик)|Teleported)"),
    ("Снаряды: потеря цели и самоликвидация", r"Airstrike/\]: Снаряды: "),
    ("Переезд со старого датапака", r"Airstrike/\]: (Выключаю старые датапаки|Настройки датапака|Удалены objectives)"),
    ("Неизвестные звуки", r"Unable to play unknown soundEvent|Missing sound for event: (airstrike|snassets):"),
    ("Аппараты Sable (добавлены/удалены)", r"shtreimel\.lifecycle.*sub-level (added|removed)"),
    ("Чат игроков", r"\[CHAT\] <"),
    ("Игроки вход/выход", r"joined the game|left the game|lost connection"),
]


def short(line):
    return re.sub(r"^\[[^\]]*?(\d\d:\d\d:\d\d)(?:\.\d+)?\] \[[^\]]*\] \[[^\]]*\]: ", r"\1 ", line)[:260]


print(f"== Ошибки и предупреждения мода: {len(errors)}")
for e in errors[-20:]:
    print("  " + short(e))
for title, pat in sections:
    hits = [l for l in lines if re.search(pat, l)]
    print(f"\n== {title}: {len(hits)}")
    for l in hits[-40:]:
        print("  " + short(l))
