#!/usr/bin/env python3
"""Время строк лога Minecraft — один разбор для выжимок заданий ноутбука (tools/laptop-jobs/*.md).

Строка начинается с «[ЧЧ:ММ:СС]», «[ЧЧ:ММ:СС.ммм]» или «[01Oct2026 ЧЧ:ММ:СС.ммм]» (дата — у боевого клиента
prod_client.py, формат log4j сборки). Строки без времени (стек исключения, продолжение сообщения) времени не имеют.
Через полночь время идёт дальше 24 ч: лог шага — одна непрерывная запись (full.log из архивов и latest.log).

Модулем: ``sys.path.insert(0, "tools"); import logtime`` — ``timeline(lines)``, ``stamp``, ``body``.
Из оболочки: ``python3 tools/logtime.py LOG РЕГВЫР [--since РЕГВЫР]`` — строки, где есть РЕГВЫР, как
«ЧЧ:ММ:СС.ммм +С.с текст»: +С.с — секунды от первой строки с ``--since`` (без неё — от первой найденной строки);
строки до неё — с минусом.
"""
import re
import sys

_TIME = re.compile(r"^\[(?:\d{1,2}\S{3,5}\d{4} )?(\d\d):(\d\d):(\d\d)(?:\.(\d{1,3}))?\]")
_BODY = re.compile(r"^\[[^\]]*\](?: \[[^\]]*\])*?: ")


def seconds(line):
    """Секунды от полуночи у строки лога или None, если у строки нет времени."""
    m = _TIME.match(line)
    if not m:
        return None
    return int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + (int(m[4].ljust(3, "0")) / 1000 if m[4] else 0)


def stamp(t):
    """«ЧЧ:ММ:СС.ммм» для секунд из timeline (время суток, без дней)."""
    t %= 86400
    return f"{int(t // 3600):02d}:{int(t % 3600 // 60):02d}:{t % 60:06.3f}"


def body(line):
    """Текст сообщения без времени, потока и источника."""
    return _BODY.sub("", line, count=1)


def timeline(lines):
    """[(секунды, строка)] строк со временем по порядку; переход через полночь — +86400 (назад больше чем на 12 ч)."""
    out, day, prev = [], 0, None
    for line in lines:
        s = seconds(line)
        if s is None:
            continue
        if prev is not None and s + day < prev - 43200:
            day += 86400
        prev = s + day
        out.append((prev, line))
    return out


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    path, pattern = argv[0], re.compile(argv[1])
    since = re.compile(argv[argv.index("--since") + 1]) if "--since" in argv else None
    rows = timeline(read(path))
    zero = next((t for t, l in rows if (since or pattern).search(l)), None)
    for t, l in rows:
        if pattern.search(l):
            print(f"{stamp(t)} {t - zero:+.1f} {body(l)[:300]}")


if __name__ == "__main__":
    main(sys.argv[1:])
