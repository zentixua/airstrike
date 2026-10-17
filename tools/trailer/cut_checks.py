#!/usr/bin/env python3
"""Ворота монтажа Newisle (newisle_cut.py, задача Артёма 15.10): по листу аниматика, дублям и данным записей — что
человек заметил бы глазами в собранном ролике. Провал ворота пересъёмку не вызывает: план становится заливкой с
причиной в черновике, чистовой монтаж отказывается. Только системный python3.

- Интерфейс не обрезан: планы с интерфейсом мода (hud/chat/live или камера scope/onboard/player в
  newisle-takes.json) не стоят под кинокаше 2.39:1 — каше держит планы до `letterbox.until` включительно (у этого
  плана кадр раскрывается через `letterbox.open` с начала, как отсчитывает montage.py: якорь «open» этой склейки).
  Ещё — интерфейс влезает в кадр записи: при guiScale клиента съёмки (record.sh) самый большой экран мода — пульт
  (RemoteScreen: 320 GUI-px шириной, высота 204 + строки описания × 9) и окуляр бинокля (квадрат со стороной
  «меньшая сторона − 20», Designator.renderHud) входят в 2560×1440; числа статические — проверены тестом на
  настоящих числах.
- Без повторов: план в листе не дважды; две склейки не берут перекрывающиеся окна кадров одной папки дубля (окно
  плана — отметка «plan:<план>» в timeline.jsonl плюс длина склейки листа).
- Чат ключа МБР читается на телефоне: планы с chat стоят на экране ≥ max(3,5 с; 1,5 с + 0,35 с на слово) строки
  «incoming.nuke.key.prompt» до двоеточия — кто просит второй ключ (слова — шаблон перевода, каждый %s за слово;
  место, время и команда после двоеточия трейлеру не нужны; время появления строки в данных записи нет — считаем от
  начала плана). Высота строки чата в кадре ≥ 2,5 % высоты: guiScale × chatScale × 9 px (масштабы —
  из options клиента съёмки, record.sh; запись — 2560×1440).
- Сирена растёт по сценам: у включённых сиреной дублей (`siren: true`) в порядке истории измеренная сирена у
  записывающего игрока (слышные секунды incoming:siren в timeline.jsonl) не падает там, где история её наращивает
  (следующий дубль пускает больше снарядов — сирену включают все обычные оружия).
"""
import json
import os
import re

import take_checks
import takes as trailer_takes

CHAT_KEY = "incoming.nuke.key.prompt"
CHAT_BASE_S, WORD_S, CHAT_MIN_S = 1.5, 0.35, 3.5
CHAT_HEIGHT_SHARE = 0.025
CHAT_LINE_PX = 9                     # высота строки чата в GUI-пикселях
FRAME_H = 1440                       # кадр записи съёмки, px (2560×1440)
LETTERBOX_ASPECT = 2.39


def load(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def sheet_dur(sheet, s):
    return float(s["dur"]) if "dur" in s else float(s["bars"]) * float(sheet.get("bar", 1.92))


def interface_plans(takes):
    """Планы с интерфейсом мода: камера scope/onboard/player или hud/chat/live у записи."""
    out = {}
    for t in takes["takes"]:
        for s in t.get("shots", []):
            if s.get("hud") or s.get("chat") or s.get("live") or s.get("camera") in ("scope", "onboard", "player"):
                for p in s.get("plans", []):
                    out[p] = True
    return out


def letterbox_clash(sheet, takes):
    """Интерфейс под кинокаше: каше держит всё до плана `letterbox.until` (у него кадр раскрывается за open с)."""
    lb = sheet.get("letterbox") or {}
    under = []
    for s in sheet["shots"]:
        if "plan" not in s:
            continue
        under.append(s["plan"])
        if s["plan"] == lb.get("until"):
            break
    have = interface_plans(takes)
    return [(p, f"интерфейс мода под кинокаше {LETTERBOX_ASPECT}:1") for p in under if p in have]


def duplicates(sheet):
    """План в листе не дважды."""
    seen, out = set(), []
    for s in sheet["shots"]:
        if s.get("plan") in seen:
            out.append((s["plan"], "план в листе дважды"))
        if "plan" in s:
            seen.add(s["plan"])
    return out


def overlaps(sheet, dirs):
    """Склейки не берут перекрывающиеся окна одной папки дубля: окно плана — отметка plan:<план> + длина склейки."""
    by_dir = {}
    for s in sheet["shots"]:
        if "plan" in s and s["plan"] in dirs:
            by_dir.setdefault(dirs[s["plan"]], []).append((s["plan"], sheet_dur(sheet, s)))
    out = []
    for rec, plans in by_dir.items():
        marks = {}
        for r in take_checks._jsonl(os.path.join(rec, "timeline.jsonl")):
            if r.get("type") == "mark" and str(r.get("what", "")).startswith("plan:"):
                marks[r["what"][5:]] = r.get("t", 0) / 20.0
        windows = sorted((marks[p], marks[p] + dur, p) for p, dur in plans if p in marks)
        for (a0, b0, _), (a1, _, p1) in zip(windows, windows[1:]):
            if a1 < b0 - 0.05:      # окно следующего начинается до конца прошлого
                out.append((p1, f"окно кадров в записи налегает на соседний план ({a1:.1f} с < {b0:.1f} с)"))
    return out


def chat_words(lang_file):
    """Слова строки второго ключа до двоеточия (кто просит ключ): шаблон перевода, каждый %s — слово (аргументы в
    воротах неизвестны)."""
    try:
        with open(lang_file, encoding="utf-8") as f:
            line = json.load(f).get(CHAT_KEY, "")
    except (OSError, ValueError):
        return None
    return len(re.sub(r"%s", "x", line.split(":")[0]).split())


def record_options(record_sh):
    """guiScale и chatScale клиента съёмки (record.sh, options.txt); нет — значения игры по умолчанию (3 и 1)."""
    try:
        with open(record_sh, encoding="utf-8") as f:
            text = f.read()
    except OSError:
        text = ""
    gui = re.search(r"^guiScale:(\d+)$", text, re.M)
    chat = re.search(r"^chatScale:([\d.]+)$", text, re.M)
    return float(gui.group(1)) if gui else 3.0, float(chat.group(1)) if chat else 1.0


def chat_gate(sheet, takes, repo):
    """Чат ключа: план с chat достаточно долго на экране и строка достаточно высока."""
    chat_plans = {p for t in takes["takes"] for s in t.get("shots", []) if s.get("chat") for p in s.get("plans", [])}
    in_sheet = [s for s in sheet["shots"] if s.get("plan") in chat_plans]
    if not in_sheet:
        return []
    out = []
    words = chat_words(os.path.join(repo, "mod/src/main/resources/assets/incoming/lang/en_us.json"))
    need = max(CHAT_MIN_S, CHAT_BASE_S + WORD_S * words) if words else CHAT_MIN_S
    for s in in_sheet:
        dur = sheet_dur(sheet, s)
        if dur < need:
            out.append((s["plan"], f"чат {dur:.1f} с < {need:.1f} с ({words} слов строки второго ключа)"))
    gui, chat = record_options(os.path.join(repo, "tools/trailer/record.sh"))
    px = gui * chat * CHAT_LINE_PX
    if px < CHAT_HEIGHT_SHARE * FRAME_H:
        why = (f"строка чата {px:.0f} px из {FRAME_H} (guiScale {gui:g} × chatScale {chat:g} × {CHAT_LINE_PX}) "
               f"— меньше {CHAT_HEIGHT_SHARE:.0%} высоты кадра")
        out += [(s["plan"], why) for s in in_sheet]
    return out


def strikes_count(take, scenario):
    """Сколько снарядов пускает дубль: свои удары записей и удары планов сценария по его планам."""
    n = sum(len(s.get("strikes", [])) for s in take.get("shots", []))
    for s in take.get("shots", []):
        for p in s.get("plans", []):
            spec = next((x for x in scenario.get("plans", []) if x.get("id") == p), None)
            n += len(spec.get("strikes", [])) if spec else 0
    return n


def siren_gate(takes, scenario, state, dirs):
    """Сирена не падает там, где история наращивает: следующий дубль пускает больше — у каждого его плана сирена
    звучит не меньшую долю окна, чем в среднем у прошлого дубля. Меряем то, что звучит в монтаже: сирены клиента
    вывода (кандидат плана в dirs) плюс поставленные монтажом (takes.siren_sfx_for — сработавшая до окна вывода сирена
    повтором не играется). Доля окна, а не сумма секунд дубля: у дублей разное число и длина планов (16.10: n11 с
    сиреной на всё окно «тише» трёх планов T06), и не пройти может только план, у которого сирена тише, а не весь
    дубль (бортовой n25 без сирены закрывал n11)."""
    by_id = {t["id"]: t for t in takes["takes"]}
    measured = {}
    for t in takes["takes"]:
        if not t.get("siren", True) or "artem" in t:
            continue
        shares = {}
        for p in (p for s in t.get("shots", []) for p in s.get("plans", []) if p in dirs):
            take = trailer_takes.load_take(p, dirs[p])
            if take.duration > 0:
                got = trailer_takes.siren_seconds(take, trailer_takes.siren_sfx_for(p, dirs[p]))
                shares[p] = (got, take.duration)
        if shares:
            measured[t["id"]] = (sum(g for g, _ in shares.values()) / sum(d for _, d in shares.values()), shares)
    order = [i for i in by_id if i in measured]
    out = []
    for prev, take in zip(order, order[1:]):
        if strikes_count(by_id[take], scenario) <= strikes_count(by_id[prev], scenario):
            continue      # история сирену не наращивает — сравнивать нечего
        level = measured[prev][0]
        for p, (got, dur) in measured[take][1].items():
            if got / dur < level - 0.01:
                out.append((p, f"сирена тише истории: {got:.1f} с сирены на {dur:.1f} с окна ({got / dur:.2f}) "
                               f"против {level:.2f} у {prev}"))
    return out


def gates(sheet, takes, scenario, dirs, state, repo):
    """Все ворота сразу: план → причина заливки в черновике."""
    out = {}
    for plan, why in (letterbox_clash(sheet, takes) + duplicates(sheet) + overlaps(sheet, dirs)
                      + chat_gate(sheet, takes, repo) + siren_gate(takes, scenario, state, dirs)):
        out[plan] = why
    return out
