#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
#     "scipy>=1.11",
#     "soundfile>=0.12",
# ]
# ///
"""Монтаж трейлера от событий: план монтажа — данные (JSON), каждая склейка держится на событии своего дубля (пуск,
полёт, удар, приход звука удара к камере), до картинки и звука монтаж проходит проверку verdict.py — любой FAIL
останавливает его с названием плана и причиной.

    uv run tools/trailer/montage.py ПЛАН.json [--take ПЛАН=ПАПКА …] [--root ПАПКА] [--check] [--lang en|ru]
                                    [--draft] [--music ФАЙЛ] [--out ФАЙЛ] [--jobs N]
    --take   — папка дубля плана (кандидат вывода `…/render…/<план>/<камера>` или папка живой записи); сильнее, чем
               "takes" плана монтажа
    --root   — от неё — относительные папки "takes" (по умолчанию mod/run/scenario)
    --check  — только разобрать и проверить: таблица монтажа и отчёт verdict.report, код выхода 1 при FAIL
    --draft  — черновик 1280×720, 30 кадров в секунду; чистовой — в размере кадров записи, 60
    --music  — Juggernaut.mp3 (Scott Buckley; как у edit.py: один раз, копия в tools/.trailer-cache), не скачивается
    результат: dist/incoming-trailer.mp4 (для ru — …-ru.mp4), …-cuts.md (время → план, окно, событие, папка),
    …-check.md (отчёт проверки), …-credits.txt

Звук — только звуки журналов дублей (мод и ваниль) и музыка, своих звуков нет (решение Артёма). Звук склейки
(`sound_plan`): всё, что звучит в её окне; звук события (удар: звуки взрыва, что клиент сыграл к приходу фронта;
пуск — старт) у montage, flight, onboard, hit и launch звучит и после склейки поверх следующих (L), пока не кончится
сам, не придёт обрыв в тишину ("silence" склейки) или та же запись не пойдёт в следующей склейке; "j" — звук
склейки начинается раньше её картинки (J). Один план звука — и для проверки (`verdict.Placed`), и для сведения
(edit.render_sfx с окнами звука отсюда). Сведение — edit.mix без мягкого ограничителя, потом loudness.master:
−14 LUFS интегрально, истинный пик не выше −1 dBTP, одно усиление на весь ролик (тишина перед вспышкой остаётся
тишиной: блоки тише порогов громкость не тянут, тише ничего не становится громче).

Сетка музыки — из edit.py (замер по треку): сильная доля `edit.beat(k)` = BEAT0 + k·BEAT (BEAT0 1,924 с, BEAT
0,96 с, 125 ударов в минуту), такт — две сильные доли (1,92 с). Места трека — номера сильных долей k; куски трека
входят и кончаются на сильной доле, склейки внутри куска — на его сетке от входа.

План монтажа (tools/trailer/plans/<имя>.json; про конкретные сцены здесь нет ничего — всё в плане):
{
  "takes": {"p13": "film/render/p13/cam-a", …},   папки дублей (от --root); --take сильнее
  "letterbox": false,      кинокаше 2.39:1 — до якоря "open" склейки или весь ролик (true без "open")
  "music": [кусок, …],
  "cuts": [склейка, …]     по порядку: склейка начинается, где кончилась прошлая
}
Кусок трека:
  "from", "to"   сильные доли трека: вход и доля обрыва (обе — целые)
  "start"        id склейки: "from" — на её начале; или {"cut": id, "at": "anchor"} — на её якоре
  "sync"         {"beat": k, "cut": id, "at": "start"|"anchor"|"end"}: доля k — на этом месте картинки, вход —
                 назад от него; картинка до него длиннее — музыка ждёт (входит позже), короче — первые доли
                 выпадают (не раньше "start")
  "until"        id склейки: на её конце музыка обрывается
  "grid"         склейки внутри куска — на его сетке: 1 — такты (по умолчанию), 2 — сильные доли, 4 — доли;
                 склейка, которую событие удлинило (удар держится до звука, тревога, текст), кончается на следующем
                 такте — музыка за ней не сбивается с такта
  "ring"         сколько трек звучит после доли "to" (удар обрыва), с; "fade" — спад на конце, с
  "gain"         [[доля, громкость], …] с линейным переходом
Тревога (siren) держится без музыки verdict.RULES siren_hold с начала сирены: кусок, что вошёл бы в неё, ждёт
целых тактов после неё; идущий — обрывается на такте до неё.
Склейка:
  "take"         дубль (план записи); без него — заливка ("kind": "fill", "level": 0 — чёрный, 255 — белый)
  "kind"         род verdict.py: hit, montage, flight, launch, siren, text, scene, onboard, nuke, wave, fill
  "id"           имя для ссылок кусков музыки (по умолчанию — дубль, у повтора — дубль#2…)
  "anchor"       событие дубля: impact (удар объекта плана; у живого — первый), impact#n (n-й, −1 — последний),
                 impact~с (ближайший к секунде дубля), arrival[#n|~с] (звук удара дошёл до камеры), launch[#n],
                 mark:что[#n] (siren, dark, front, detonation, release, close, exit, gone:…), sound:род или часть
                 имени события[#n], start, end, число секунд; ±сдвиг ("mark:release-1.2"); continue — с конца прошлой
                 склейки того же дубля. По умолчанию: hit, montage — impact; launch — launch; siren — sound:siren;
                 иначе start. Нет такого события в дубле — монтаж останавливается.
  "land"         где в склейке ляжет якорь: секунды от начала или "end" (по умолчанию hit 0.6, montage 0.25,
                 launch 0.1, иначе 0); не раньше правил verdict (lead, montage_lead, launch_lead)
  "snap"         "anchor" — на сетку куска ложится якорь (удар на такт), а не начало склейки: прошлая длиннее
  "dur" | "beats" | "bars" | "until"   длина: секунды, сильные доли, такты или до события: "sound" — пока звук
                 удара не дошёл до камеры, и ещё "tail" (у hit — по умолчанию, и короче его hit не бывает), "end" —
                 до конца дубля, или якорь
  "rate"         секунд дубля на секунду ролика (замедление — меньше 1)
  "need"         пороги проверки склейки (verdict Cut.need: hero, flight_share, tail…)
  "fight"        идёт бой: выстрелы должны быть в ролике
  "text"         строки на экране: {"lines": […] | {"en": […], "ru": […]}, "style": "title"|"end"|"chat",
                 "at": с, "step": с (чат: строки по одной), "in_take": true — текст в кадре дубля (не рисуется,
                 только время чтения)}; склейка держится, пока каждую строку можно прочесть
  "sfx"          громкость звука дубля (0 — без звука); "carry" — звук события после склейки: true, false или
                 секунды; "carry_kinds" — роды звуков события ("blast", "launch"…); "j" — J, с
  "hold"         false — склейка ровно как в плане, без правил событий (сверка со старым монтажом); проверка — та же
  "silence"      обрыв в тишину в начале склейки: звуки прошлых склеек кончаются
  "quiet"        тихая склейка (тишина перед вспышкой): после громкости — на столько LU тише ролика (true — 10)
  "open"         якорь, в который кадр раскрывается из 2.39:1 в 16:9
  "plan", "label", "note"; "zoom", "xfade", "flash", "frame_y" — как у edit.Clip
Удар склейки montage и hit у вывода из повтора — удар объекта плана (FrameQa следит за одним объектом, и видно ли
удар, проверка знает только у него); у живого плана — первый удар в окне склейки.
"""
import argparse
import math
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import edit as E  # noqa: E402
import loudness as L  # noqa: E402
import takes as T  # noqa: E402
import verdict as V  # noqa: E402

FPS = T.FPS
BEAT = E.BEAT            # сильная доля трека, с (замер edit.py)
BAR = 2 * E.BEAT         # такт: две сильные доли
KINDS = ("hit", "montage", "flight", "launch", "siren", "text", "scene", "onboard", "nuke", "wave", "fill")
ANCHOR = {"hit": "impact", "montage": "impact", "launch": "launch", "siren": "sound:siren"}
LAND = {"hit": 0.6, "montage": 0.25, "launch": 0.1}
CARRY = {"montage": ("blast",), "flight": ("blast",), "onboard": ("blast",), "hit": ("blast",), "launch": ("launch",)}
QUIET_LU = 10.0          # тихая склейка — на столько тише ролика
SILENT_LUFS = -70.0      # тишина: громче её блоки 400 мс не бывают
SILENCE_AFTER = 0.25     # звук удара звучит хотя бы столько до обрыва в тишину, с
TARGET_LUFS, CEILING_DBTP = -14.0, -1.0
EPS = 1e-6
ANCHOR_RE = re.compile(r"^(?P<what>impact|arrival|launch|start|end|continue|mark:.+?|sound:.+?)"
                       r"(?:#(?P<n>-?\d+)|~(?P<near>\d+(?:\.\d+)?))?(?P<off>[+-]\d+(?:\.\d+)?)?$")


class MontageError(Exception):
    """Монтаж не собирается: план, его склейка и причина."""


def frame_ceil(t):
    return math.ceil(t * FPS - 1e-6) / FPS


def frame_floor(t):
    return math.floor(t * FPS + 1e-6) / FPS


def frame_round(t):
    return round(t * FPS) / FPS


# ---------------------------------------------------------------- план монтажа и дубли

def load_plan(path):
    with open(path, encoding="utf-8") as f:
        plan = json.load(f)
    plan.setdefault("name", os.path.splitext(os.path.basename(path))[0])
    return normalize(plan, path)


def normalize(plan, path=None):
    """Проверка плана монтажа по схеме и имена склеек (id) для ссылок кусков музыки."""
    path = path or plan.setdefault("name", "plan")
    plan.setdefault("name", path)
    cuts = plan.get("cuts") or []
    if not cuts:
        raise MontageError(f"{path}: в плане нет склеек")
    seen = {}
    for i, c in enumerate(cuts):
        kind = c.get("kind", "fill" if not c.get("take") else None)
        if kind not in KINDS:
            raise MontageError(f"{path}: склейка {i + 1} ({c.get('take', '—')}): род «{kind}» — не из {', '.join(KINDS)}")
        c["kind"] = kind
        if kind == "fill" and c.get("take"):
            raise MontageError(f"{path}: склейка {i + 1}: у заливки нет дубля ({c['take']})")
        if kind != "fill" and not c.get("take") and not c.get("text"):
            raise MontageError(f"{path}: склейка {i + 1} ({kind}): нет дубля")
        base = c.get("id") or c.get("take") or f"{kind}{i + 1}"
        seen[base] = seen.get(base, 0) + 1
        c["_id"] = base if seen[base] == 1 else f"{base}#{seen[base]}"
    ids = {c["_id"] for c in cuts}
    for s in plan.get("music", []):
        for key in ("start", "until"):
            ref = s.get(key)
            ref = ref.get("cut") if isinstance(ref, dict) else ref
            if ref is not None and ref not in ids:
                raise MontageError(f"{path}: кусок музыки {s.get('name', s.get('from'))}: нет склейки «{ref}» ({key})")
        if "sync" in s and s["sync"].get("cut") not in ids:
            raise MontageError(f"{path}: кусок музыки {s.get('name', s.get('from'))}: нет склейки «{s['sync'].get('cut')}» (sync)")
        if "start" not in s and "sync" not in s:
            raise MontageError(f"{path}: кусок музыки {s.get('name', s.get('from'))}: нет ни start, ни sync")
        for key in ("from", "to"):
            if not isinstance(s.get(key), int):
                raise MontageError(f"{path}: кусок музыки {s.get('name', '?')}: {key} — целый номер сильной доли трека")
    return plan


def take_dirs(plan, root=None, overrides=()):
    """Папка каждого дубля плана: "takes" плана (от root), --take сильнее."""
    root = root or os.path.join(E.MOD, "run", "scenario")
    dirs = {k: v if os.path.isabs(v) else os.path.join(root, v) for k, v in (plan.get("takes") or {}).items()}
    for o in overrides:
        name, _, d = o.partition("=")
        if not d:
            raise MontageError(f"--take {o}: нужно ПЛАН=ПАПКА")
        dirs[name] = d
    return dirs


def load_takes(plan, dirs):
    """Дубли всех склеек; нет дубля (не задана папка, в папке нет плана, нет кадров) — монтаж останавливается и
    называет каждый такой план с его склейками: заглушек нет."""
    need = {}
    for i, c in enumerate(plan["cuts"]):
        if c.get("take"):
            need.setdefault(c["take"], []).append(i + 1)
    takes, missing = {}, []
    for name, where in need.items():
        cuts = ", ".join(map(str, where))
        d = dirs.get(name)
        if d is None:
            missing.append(f"{name} (склейки {cuts}): папка дубля не задана (\"takes\" плана или --take {name}=ПАПКА)")
            continue
        if not os.path.isdir(d):
            missing.append(f"{name} (склейки {cuts}): нет папки {d}")
            continue
        t = T.load_take(name, d)
        if not t.frames:
            missing.append(f"{name} (склейки {cuts}): в {d} нет кадров плана {name}")
            continue
        takes[name] = t
    if missing:
        raise MontageError(f"монтаж {plan['name']}: нет дублей ({len(missing)}):\n  " + "\n  ".join(missing))
    return takes


# ---------------------------------------------------------------- события дубля

_IMPACTS = {}


def impacts(t):
    """Удары дубля (takes.Take.impacts — с приходом звука к камере), один раз на дубль."""
    got = _IMPACTS.get(id(t))
    if got is None or got[0] is not t:
        got = _IMPACTS[id(t)] = (t, t.impacts())
    return got[1]


def launches(t):
    """Пуски по звукам старта: несколько звуков одного старта (разгон, рёв) — один пуск."""
    out = []
    for x in sorted(s[0] for s in t.launches()):
        if not out or x - out[-1] > 0.05:
            out.append(x)
    return out


def subject(t):
    """Удар объекта плана, как его берёт проверка: объект плана, иначе — первый удар в дубле."""
    imps = impacts(t)
    subj = [i for i in imps if i.subject]
    if subj:
        return subj[0]
    inside = [i for i in imps if -EPS <= i.t <= t.duration]
    return inside[0] if inside else None


def event(t, spec, where):
    """Момент дубля по якорю: (секунды дубля, удар или None). Нет события — MontageError с местом where."""
    if isinstance(spec, (int, float)):
        return float(spec), None
    m = ANCHOR_RE.match(str(spec))
    if not m:
        raise MontageError(f"{where}: якорь «{spec}» не разобран")
    what, n, near, off = m["what"], m["n"], m["near"], float(m["off"] or 0)
    imp = None
    if what in ("impact", "arrival"):
        inside = [i for i in impacts(t) if -EPS <= i.t <= t.duration]
        if n is None and near is None:
            got = [subject(t)] if subject(t) is not None else []
        else:
            got = inside
        if what == "arrival":
            got = [i for i in got if i.arrival is not None]
        items = [(i.arrival if what == "arrival" else i.t, i) for i in got]
    elif what == "launch":
        items = [(x, None) for x in launches(t)]
    elif what == "start":
        items = [(0.0, None)]
    elif what == "end":
        items = [(t.duration, None)]
    elif what.startswith("mark:"):
        w = what[5:]
        items = sorted(((x, None) for x, mk in t.marks if mk == w or mk.startswith(w)), key=lambda p: p[0])
    else:
        w = what[6:]
        items = sorted(((s.t, None) for s in t.sounds if s.kind == w or w in s.event), key=lambda p: p[0])
    if not items:
        raise MontageError(f"{where}: в дубле {t.name} нет события «{what}»")
    if near is not None:
        x, imp = min(items, key=lambda p: abs(p[0] - float(near)))
    elif n is not None:
        k = int(n)
        if k == 0 or abs(k) > len(items):
            raise MontageError(f"{where}: в дубле {t.name} событий «{what}» {len(items)}, а нужно #{k}")
        x, imp = items[k - 1 if k > 0 else k]
    else:
        x, imp = items[0]
    return x + off, imp


def blast_layers(t, imp):
    """Звуки удара, что клиент сыграл у камеры: взрыв у места удара или начатый у прихода фронта (все слои:
    ближний, дальний, хвост) — как их узнаёт проверка (verdict.sound_placed)."""
    out = []
    for s in t.sounds:
        if s.kind != "blast" or s.t < imp.t - 0.05:
            continue
        near = imp.pos is not None and T.dist(s.pos, imp.pos) <= 8
        timed = imp.arrival is not None and abs(s.t - imp.arrival) <= T.ARRIVAL_SLACK
        if near or timed:
            out.append(s)
    return out


# ---------------------------------------------------------------- разбор монтажа

@dataclass
class Piece:
    """Склейка после разбора: где в ролике, откуда в дубле, событие и цепочка событий."""
    i: int
    id: str
    kind: str
    spec: dict
    take: str = None
    start: float = 0.0
    dur: float = 0.0
    src: float = 0.0
    rate: float = 1.0
    land: float = 0.0
    moment: float = None        # якорь, секунды дубля
    impact: object = None       # удар склейки (takes.Impact)
    chain: list = field(default_factory=list)   # (что, секунды дубля)
    section: str = ""
    notes: list = field(default_factory=list)
    lines: tuple = ()           # строки текста склейки (на её языке)
    siren: float = None         # начало тревоги склейки, время ролика

    @property
    def end(self):
        return self.start + self.dur

    @property
    def src_end(self):
        return self.src + self.dur * self.rate

    def at(self, take_t):
        return self.start + (take_t - self.src) / self.rate

    @property
    def sfx(self):
        return float(self.spec.get("sfx", 1.0))

    @property
    def plan_no(self):
        if "plan" in self.spec:
            return int(self.spec["plan"])
        m = re.match(r"p(\d+)", self.take or "")
        return int(m[1]) if m else self.i + 1

    @property
    def label(self):
        if self.spec.get("label") or self.take:
            return self.spec.get("label") or self.take
        return ("белый" if self.spec.get("level") else "чёрный") if self.kind == "fill" else self.kind


@dataclass
class Section:
    """Кусок трека в ролике."""
    name: str
    spec: dict
    first: int                  # сильная доля входа (после выпавших)
    last: int                   # доля обрыва
    origin: float = None        # начало сетки склеек, время ролика
    sync_at: float = None       # место картинки доли sync
    until_end: float = None
    entry: float = None
    end: float = None
    notes: list = field(default_factory=list)

    @property
    def step(self):
        return BAR / float(self.spec.get("grid", 1))

    @property
    def span(self):
        return E.beat(self.last) - E.beat(self.first)

    def grid_end(self):
        """До какого времени ролика сетка куска действует (None — до следующего куска)."""
        if self.origin is None:
            return None
        ends = []
        if "sync" not in self.spec:
            ends.append(self.origin + self.span)
        if self.until_end is not None:
            ends.append(self.until_end)
        return min(ends) if ends else None


@dataclass
class Edit:
    plan: dict
    pieces: list
    sections: list
    texts: list                 # edit.Text — строки поверх кадра
    text_cuts: list             # они же для проверки (verdict.Cut text без дубля)
    silences: list
    total: float
    opened: float
    quiet: list                 # (склейка, LU)


def rule(spec, name):
    return (spec.get("need") or {}).get(name, V.RULES[name])


def read_time(spec, line):
    return max(rule(spec, "read_min"), rule(spec, "read_base") + len(line) / rule(spec, "read_cps"))


def text_lines(spec, lang):
    tx = spec.get("text")
    if not tx:
        return (), {}
    lines = tx.get("lines", ())
    if isinstance(lines, dict):
        lines = lines.get(lang) or lines.get("en") or next(iter(lines.values()), ())
    return tuple(lines), tx


def ceil_grid(t, origin, step):
    """Следующая точка сетки не раньше t; конец на полкадра позже точки — на ней (длина в секундах плана и сетка
    трека расходятся в тысячных: 1,92 с против такта 1,91998 с)."""
    return origin + math.ceil((t - origin - 0.5 / FPS) / step) * step


def starts_here(s, cut_id, at):
    ref = s.spec.get("start")
    if isinstance(ref, dict):
        return ref.get("cut") == cut_id and ref.get("at", "start") == at
    return ref == cut_id and at == "start"


def resolve(plan, takes, lang="en"):
    """Монтаж по плану: где каждая склейка в ролике и в дубле, куски музыки, тексты, обрывы в тишину. Склейки
    ложатся подряд; длина — из плана, но не короче, чем нужно событию (удар держится до звука и хвоста, пуск — до
    схода, тревога — без музыки, текст — до прочтения); на сетке куска музыки — до сетки, удлинённая — до такта."""
    cuts = plan["cuts"]
    sections = [Section(s.get("name", f"m{k + 1}"), s, s["from"], s["to"]) for k, s in enumerate(plan.get("music", []))]
    pieces, t = [], 0.0
    pending = None                       # удар, который держит следующая склейка того же дубля (continue)
    for i, spec in enumerate(cuts):
        kind, name = spec["kind"], spec.get("take")
        p = Piece(i, spec["_id"], kind, spec, name, rate=float(spec.get("rate", 1.0)))
        where = f"монтаж {plan['name']}: склейка {i + 1} {p.label} ({kind})"
        tk = takes.get(name) if name else None
        p.lines, tx = text_lines(spec, lang)
        nxt = cuts[i + 1] if i + 1 < len(cuts) else None
        continues = nxt is not None and name is not None and nxt.get("take") == name and nxt.get("anchor") == "continue"
        nominal = None
        if "dur" in spec:
            nominal = float(spec["dur"])
        elif "beats" in spec:
            nominal = float(spec["beats"]) * BEAT
        elif "bars" in spec:
            nominal = float(spec["bars"]) * BAR
        strict = spec.get("hold", True) is not False   # false — как в плане, без правил событий
        until = spec.get("until", "sound" if kind == "hit" and not continues and strict else None)
        anchor = spec.get("anchor", ANCHOR.get(kind, "start"))
        start, land = t, 0.0
        if spec.get("silence") and strict and pieces:
            start = wait_for_sound(pieces, start)
        grid = active(sections, start)
        # якорь и где он ляжет в склейке
        if tk is not None:
            if anchor == "continue":
                prev = pieces[-1] if pieces else None
                if prev is None or prev.take != name:
                    raise MontageError(f"{where}: continue — а прошлая склейка не из {name}")
                p.moment, p.impact = prev.src_end, None
            else:
                p.moment, p.impact = event(tk, anchor, where)
                land = spec.get("land", LAND.get(kind, 0.0))
                if land == "end":
                    if nominal is None:
                        raise MontageError(f"{where}: якорь на конце (land end) — нужна длина dur, beats или bars")
                    land = nominal
                land = float(land)
                own = {"hit": "impact", "montage": "impact", "launch": "launch"}.get(kind)
                lo = {"hit": rule(spec, "lead"), "montage": rule(spec, "montage_lead"),
                      "launch": rule(spec, "launch_lead") + 0.02}.get(kind, 0.0)
                if own and str(anchor).startswith(own) and land < lo - EPS and strict:
                    p.notes.append(f"якорь на {land:.2f} с от начала, правило — не раньше {lo:.2f} с: кладу на {lo:.2f}")
                    land = lo
                if spec.get("snap") == "anchor" and grid is not None:
                    at = frame_round(ceil_grid(start + land, grid.origin, grid.step))
                    if pieces and at - land > start + EPS:
                        pieces[-1].dur += at - land - start
                        pieces[-1].notes.append(f"длиннее на {at - land - start:.2f} с: якорь следующей — на сетку")
                    start = max(start, at - land)
            p.src = p.moment - land * p.rate
            if p.src < -EPS:
                p.notes.append(f"дубль начинается на {-p.src:.2f} с позже, чем нужно склейке: якорь на {p.moment / p.rate:.2f} с")
                land, p.src = p.moment / p.rate, 0.0
            if p.impact is None and kind in ("hit", "montage") and anchor != "continue":
                p.impact = subject(tk)
        p.start, p.land = start, land
        for s in sections:
            if starts_here(s, p.id, "start"):
                s.origin = p.start
            elif starts_here(s, p.id, "anchor"):
                s.origin = p.start + p.land
        grid = active(sections, p.start)
        # конец: по плану и по событиям
        if until is not None and tk is not None:
            if until == "sound":
                imp = p.impact
                if imp is None or imp.arrival is None:
                    p.notes.append("время прихода звука удара неизвестно — держать не до чего")
                    end_plan = p.start + (nominal or 0.0)
                else:
                    end_plan = p.at(imp.arrival) + rule(spec, "tail") + 1 / FPS
            else:
                x, _ = event(tk, until, where)
                end_plan = p.at(x)
            if nominal is not None:
                end_plan = max(end_plan, p.start + nominal)
        elif nominal is not None:
            end_plan = p.start + nominal
        elif kind == "siren" or p.lines:
            end_plan = p.start
        else:
            raise MontageError(f"{where}: нет длины (dur, beats, bars или until)")
        need = minimum(p, tk, pieces, cuts, spec, tx) if strict else p.start
        if kind == "hit" and continues and p.impact is not None and p.impact.arrival is not None:
            pending = (name, p.at(p.impact.arrival) + rule(spec, "tail") + 1 / FPS)   # сцену держит её продолжение
        if pending and pending[0] == name and anchor == "continue" and not continues:
            need = max(need, pending[1])
            pending = None
        end = max(end_plan, need)
        if end > end_plan + EPS:
            p.notes.append(f"длиннее плана на {end - end_plan:.2f} с: событие")
        if grid is not None:
            end = ceil_grid(end, grid.origin, BAR if end > end_plan + EPS else grid.step)
        end = frame_round(end)
        if end < max(need, p.start + 1 / FPS) - EPS:
            end += 1 / FPS
        if tk is not None:
            room = p.start + (tk.duration - p.src) / p.rate
            if end > room + EPS:
                p.notes.append(f"дубль кончается на {end - room:.2f} с раньше склейки — склейка короче (кадров не выдумываю)")
                end = max(p.start + 1 / FPS, frame_floor(room))
        p.dur = end - p.start
        p.chain = chain(tk, p) if tk is not None else []
        p.section = grid.name if grid is not None else ""
        for s in sections:
            if s.spec.get("until") == p.id:
                s.until_end = p.end
            sy = s.spec.get("sync")
            if sy and sy.get("cut") == p.id:
                s.sync_at = {"start": p.start, "end": p.end}.get(sy.get("at", "start"), p.start + p.land)
                if s.origin is None:
                    s.origin = s.sync_at
        pieces.append(p)
        t = p.end
    total = pieces[-1].end
    silences = sorted({p.start for p in pieces if p.spec.get("silence")})
    texts, text_cuts = overlay_texts(pieces)
    quiet = [(p, QUIET_LU if p.spec["quiet"] is True else float(p.spec["quiet"])) for p in pieces if p.spec.get("quiet")]
    ed = Edit(plan, pieces, sections, texts, text_cuts, silences, total, opening(plan, pieces, takes, total), quiet)
    place_music(ed)
    return ed


def wait_for_sound(pieces, start):
    """Обрыв в тишину ждёт звук удара прошлых склеек (montage, flight, onboard: удар в окне, звук позже): звук
    дошёл до камеры и звучит хотя бы SILENCE_AFTER — прошлая склейка длиннее."""
    need = start
    for q in reversed(pieces):   # склейки после прошлого обрыва (та, что с него начинается, — тоже)
        imp = q.impact
        if q.kind in ("montage", "flight", "onboard") and imp is not None and imp.arrival is not None \
                and q.src - EPS <= imp.t <= q.src_end + EPS:
            need = max(need, q.at(imp.arrival) + SILENCE_AFTER)
        if q.spec.get("silence"):
            break
    need = frame_ceil(need)
    if need > start + EPS:
        last = pieces[-1]
        last.dur += need - start
        last.notes.append(f"длиннее на {need - start:.2f} с: обрыв в тишину ждёт звук удара")
    return max(start, need)


def active(sections, at):
    """Сетка, что действует в момент ролика at: последний начатый кусок, чья сетка ещё не кончилась."""
    best = None
    for s in sections:
        if s.origin is None or s.origin > at + EPS:
            continue
        end = s.grid_end()
        if end is not None and at >= end - EPS:
            continue
        if best is None or s.origin >= best.origin:
            best = s
    return best


def siren_start(p, tk):
    """Начало тревоги склейки в ролике (как у verdict k_siren: первая сирена дубля, не раньше начала склейки)."""
    sirens = sorted(s.t for s in tk.sounds if s.kind == "siren")
    return p.at(max(sirens[0], p.src)) if sirens else None


def minimum(p, tk, pieces, cuts, spec, tx):
    """Конец склейки не раньше, чем нужно её событию (время ролика)."""
    kind, need = p.kind, p.start
    if tk is not None and kind == "montage" and p.impact is not None:
        need = max(need, p.at(p.impact.t) + rule(spec, "montage_after") + 1 / FPS)
    if tk is not None and kind == "hit" and p.impact is not None:
        need = max(need, p.at(p.impact.t) + 0.3 + 1 / FPS)
    if tk is not None and kind == "launch" and p.moment is not None:
        need = max(need, p.at(p.moment) + rule(spec, "launch_after") + 1 / FPS)
    if tk is not None and kind == "siren":
        p.siren = siren_start(p, tk)
        nxt = cuts[p.i + 1] if p.i + 1 < len(cuts) else None
        if nxt is None or nxt["kind"] != "siren":
            # тревога — склейки siren подряд; у каждой от её сирены до конца цепочки — не меньше siren_hold
            chain_ = [p]
            for q in reversed(pieces):
                if q.kind != "siren" or abs(q.end - chain_[0].start) > 1e-3:
                    break
                chain_.insert(0, q)
            for q in chain_:
                if q.siren is not None:
                    need = max(need, q.siren + rule(q.spec, "siren_hold") + 1 / FPS)
    if p.lines:
        if tx.get("in_take"):
            need = max(need, p.start + max(read_time(spec, x) for x in p.lines) + 1 / FPS)
        else:
            chat = tx.get("style") == "chat"
            at, step = float(tx.get("at", 0.15)), float(tx.get("step", 0.6))
            for k, line in enumerate(p.lines):
                shown = at + (k * step if chat else 0.0)
                need = max(need, p.start + shown + read_time(spec, line) + (0.0 if chat else 0.2) + 1 / FPS)
    return need


def chain(tk, p):
    """Цепочка событий склейки в секундах дубля: пуск до удара, удар, приход его звука; якорь."""
    out = []
    if p.impact is not None:
        before = [x for x in launches(tk) if x <= p.impact.t]
        if before:
            out.append(("пуск", before[-1]))
        out.append(("удар", p.impact.t))
        if p.impact.arrival is not None:
            out.append(("звук", p.impact.arrival))
    elif p.kind == "launch" and p.moment is not None:
        out.append(("пуск", p.moment))
    return out


def overlay_texts(pieces):
    """Строки на экране поверх кадра (edit.Text) и для проверки — склейки text без дубля, по строке."""
    texts, cuts = [], []
    for p in pieces:
        if not p.lines:
            continue
        tx = p.spec["text"]
        if tx.get("in_take"):
            continue
        style = tx.get("style", "title")
        at = p.start + float(tx.get("at", 0.15))
        end = p.end - (0.0 if style == "chat" else 0.2)
        if style == "chat":
            step = float(tx.get("step", 0.6))
            shows = [at + k * step for k in range(len(p.lines))] + [end]
            for k in range(len(p.lines)):
                for j in range(k + 1):   # новая строка поднимает прошлые, как в игре
                    texts.append(E.Text(shows[k], shows[k + 1] - shows[k], (p.lines[j],), "chat", fade=0, row=k - j))
            for j, line in enumerate(p.lines):
                cuts.append(V.Cut(shows[j], end - shows[j], "text", None, plan=p.plan_no, label=f"текст: {p.label}",
                                  text=(line,)))
        else:
            fade = float(tx.get("fade", 0.4))
            texts.append(E.Text(at, end - at, p.lines, style, fade=fade))
            cuts.append(V.Cut(at, end - at, "text", None, plan=p.plan_no, label=f"текст: {p.label}", text=p.lines))
    return texts, cuts


def opening(plan, pieces, takes, total):
    """Когда кадр раскрывается из 2.39:1 в 16:9 (edit._bar): по якорю "open" склейки; кинокаше без него — весь ролик."""
    if not plan.get("letterbox"):
        return -1.0
    for p in pieces:
        if "open" in p.spec and p.take:
            x, _ = event(takes[p.take], p.spec["open"], f"монтаж {plan['name']}: склейка {p.i + 1} {p.label} (open)")
            return min(max(p.at(x), p.start), p.end - 0.5)
    return total + 1.0


def place_music(ed):
    """Куски трека на картинке: вход — на начале склейки или назад от места sync; кусок, который вошёл бы в тревогу,
    ждёт целых тактов после неё; идущий — обрывается на такте до неё; следующий кусок обрывает прошлый."""
    zones = [(p.siren, p.siren + rule(p.spec, "siren_hold")) for p in ed.pieces
             if p.siren is not None and p.spec.get("hold", True) is not False]
    prev_end = 0.0
    for s in sorted(ed.sections, key=lambda s: s.origin if s.origin is not None else math.inf):
        if s.origin is None and s.sync_at is None:
            s.notes.append("склейки куска нет в монтаже")
            continue
        sy = s.spec.get("sync")
        if sy:
            entry = s.sync_at - (E.beat(sy["beat"]) - E.beat(s.first))
            low = s.origin if "start" in s.spec else prev_end
            if entry < low - EPS:
                n = math.ceil((low - entry) / BEAT - 1e-6)
                s.first += n
                entry += n * BEAT
                s.notes.append(f"картинка короче куска: первые {n} сильн. долей выпадают")
            elif "start" in s.spec and entry > s.origin + EPS:
                s.notes.append(f"картинка длиннее куска: музыка ждёт {entry - s.origin:.2f} с")
        else:
            entry = s.origin
        if s.first >= s.last:
            s.notes.append("от куска ничего не осталось")
            continue
        for z0, z1 in zones:
            if z0 - EPS <= entry < z1:
                n = math.ceil((z1 - entry) / BAR - 1e-6)
                entry += n * BAR
                s.notes.append(f"ждёт тревогу: входит на {n} такт. позже")
        end = entry + s.span + float(s.spec.get("ring", 0.0))
        if s.until_end is not None:
            end = min(end, s.until_end)
        for z0, z1 in zones:
            if entry < z0 < end:
                cut = entry + math.floor((z0 - entry) / BAR + 1e-6) * BAR
                s.notes.append(f"обрыв на такте перед тревогой ({V.ts(cut)})")
                end = cut
        s.entry, s.end = entry, min(end, ed.total)
        prev_end = s.end
    live = sorted((s for s in ed.sections if s.entry is not None and s.end > s.entry), key=lambda s: s.entry)
    for a, b in zip(live, live[1:]):
        if a.end > b.entry + EPS:
            a.end = b.entry
            a.notes.append(f"обрывает кусок {b.name}")


def music_gain(s, track):
    pts = s.spec.get("gain")
    if not pts:
        return 1.0
    xs = [E.beat(k) for k, _ in pts]
    ys = [g for _, g in pts]
    if track <= xs[0]:
        return ys[0]
    if track >= xs[-1]:
        return ys[-1]
    for k in range(len(xs) - 1):
        if xs[k] <= track <= xs[k + 1]:
            return ys[k] + (ys[k + 1] - ys[k]) * (track - xs[k]) / (xs[k + 1] - xs[k])
    return ys[-1]


def music_map(ed):
    """Где играет музыка и как громко (verdict.Music): куски по 0,25 с."""
    out = []
    for s in ed.sections:
        if s.entry is None or s.end <= s.entry:
            continue
        t = s.entry
        while t < s.end - 1e-9:
            t1 = min(t + 0.25, s.end)
            out.append(V.Music(t, t1, music_gain(s, E.beat(s.first) + (t + t1) / 2 - s.entry)))
            t = t1
    return out


def parts(ed):
    """Карта музыки для edit.music_track (edit.Part)."""
    out = []
    for s in ed.sections:
        if s.entry is None or s.end <= s.entry:
            continue
        track = E.beat(s.first)
        gain = tuple((E.beat(k), g) for k, g in s.spec.get("gain", ()))
        out.append(E.Part(track, s.entry, track + s.end - s.entry, track + s.end - s.entry, gain,
                          float(s.spec.get("fade", 0.04))))
    return out


# ---------------------------------------------------------------- звук: J/L

@dataclass
class Seg:
    """Звук дубля в склейке: окно в секундах дубля; rings — звучит и после склейки (L)."""
    piece: Piece
    sound: T.Sound
    t0: float
    t1: float
    rings: bool


def carry_of(p):
    """Звук события после склейки: (роды звуков, предел после конца склейки в секундах или None — до обрыва)."""
    c = p.spec.get("carry")
    kinds = tuple(p.spec.get("carry_kinds") or CARRY.get(p.kind, ("blast",)))
    if c is False or c is None and p.kind not in CARRY:
        return (), 0.0
    if c is None or c is True:
        return kinds, None
    return kinds, float(c)


def sound_plan(ed, takes):
    """Что из звуков дублей куда ложится в ролике: всё, что звучит в окне склейки (с J — и до её картинки); звук
    события — и после неё (L): удары в окне (их взрывы приходят к камере позже удара, бывает — после склейки) и звуки
    родов carry_kinds, начатые в окне, — пока не кончатся, не придёт обрыв в тишину или та же запись не пойдёт в
    следующей склейке."""
    segs = []
    for p in ed.pieces:
        if not p.take or p.sfx <= 0:
            continue
        t = takes[p.take]
        lo, hi = p.src, p.src_end
        before = max((s for s in ed.silences if s <= p.start + EPS), default=0.0)
        j0 = max(p.start - float(p.spec.get("j", 0.0)), before, 0.0)
        lo_j = max(0.0, lo - (p.start - j0) * p.rate)
        cut = min((s for s in ed.silences if s > p.start + EPS), default=ed.total)
        kinds, cap = carry_of(p)
        ring_to = cut if cap is None else min(cut, p.end + cap)
        carry_end = hi + max(0.0, ring_to - p.end) * p.rate
        chained = set()
        if kinds:
            for imp in impacts(t):
                if lo - EPS <= imp.t <= hi + EPS:
                    chained.update(s.id for s in blast_layers(t, imp))
        for s in t.sounds:
            if s.event.startswith(E.SKIP_SOUNDS):
                continue
            end = s.stop if s.stop is not None else math.inf
            rings = bool(kinds) and not s.loop and ((s.kind in kinds and lo - 0.05 <= s.t <= hi) or s.id in chained)
            if s.t > hi + EPS and not rings or end < lo_j - 0.05:
                continue
            t0, t1 = max(s.t, lo_j), min(end, carry_end if rings else hi)
            if t1 > t0 + EPS:
                segs.append(Seg(p, s, t0, t1, rings and t1 > hi + EPS))
    # хвост звука после склейки кончается там, где та же запись идёт в следующей склейке (иначе — вдвое)
    for a in segs:
        if not a.rings:
            continue
        for b in segs:
            if b is not a and b.piece.take == a.piece.take and b.sound.id == a.sound.id and b.piece.start > a.piece.start:
                a.t1 = min(a.t1, max(a.piece.src_end, a.piece.src_end + (b.piece.start - a.piece.end) * a.piece.rate))
                a.rings = a.t1 > a.piece.src_end + EPS
    return [s for s in segs if s.t1 > s.t0 + EPS]


def placed(segs, takes):
    """План звука для проверки (verdict.Placed): громкость — наибольшая у камеры склейки (после её конца — последний
    кадр склейки), как у edit.render_sfx."""
    out = []
    for g in segs:
        p, s = g.piece, g.sound
        t = takes[p.take]
        frames = t.frames_in(g.t0, min(g.t1, p.src_end)) or [f for f in t.frames if f.t <= p.src_end][-1:]
        gain = max((s.gain_at(f.cam, f.t) for f in frames), default=0.0) * p.sfx
        out.append(V.Placed(p.take, s.id, s.event, s.kind, p.at(g.t0), p.at(g.t1), gain, s.t))
    return out


# ---------------------------------------------------------------- проверка

def vcuts(ed):
    out = []
    for p in ed.pieces:
        if p.take is None:
            out.append(V.Cut(p.start, p.dur, "fill", plan=p.plan_no, label=p.label))
            continue
        text = p.lines if p.kind == "text" else ()
        out.append(V.Cut(p.start, p.dur, p.kind, p.take, p.src, p.rate, p.plan_no, p.label, dict(p.spec.get("need") or {}),
                         bool(p.spec.get("fight")), text))
    return out + list(ed.text_cuts)


def gate(ed, takes):
    """Проверка монтажа до картинки и звука: verdict.Checker по склейкам, плану звука, музыке и обрывам."""
    segs = sound_plan(ed, takes)
    verdicts = V.Checker(vcuts(ed), takes, placed(segs, takes), music_map(ed), ed.silences).run()
    return verdicts, segs


def failures(ed, verdicts):
    bad = [v for v in verdicts if not v.ok]
    if not bad:
        return None
    lines = [f"монтаж {ed.plan['name']} остановлен: склеек не прошли проверку — {len(bad)}"]
    for v in bad:
        c = v.cut
        lines.append(f"  {V.ts(c.start)} план {c.plan} {c.label} ({c.kind}): "
                     + "; ".join(f.text for f in v.findings if f.level == "FAIL"))
    return "\n".join(lines)


def table(ed, dirs=None):
    """Таблица монтажа: время в ролике → склейка, окно дубля, цепочка событий (секунды дубля и ролика), музыка."""
    rows = ["| Время | План | Склейка | Род | Окно дубля, с | События | Музыка | Заметки | Откуда |",
            "|---|---|---|---|---|---|---|---|---|"]
    for p in ed.pieces:
        span = f"{V.ts(p.start)}–{V.ts(p.end)}"
        win = f"{p.src:.2f}–{p.src_end:.2f}" if p.take else ""
        ev = ", ".join(f"{w} {x:.2f} ({V.ts(p.at(x))})" for w, x in p.chain)
        rows.append(f"| {span} | {p.plan_no} | {p.label} | {p.kind} | {win} | {ev} | {p.section} | "
                    f"{'; '.join(p.notes)} | {(dirs or {}).get(p.take, '') if p.take else ''} |")
    rows += ["", "| Музыка | Доли трека | В ролике | Заметки |", "|---|---|---|---|"]
    for s in ed.sections:
        where = f"{V.ts(s.entry)}–{V.ts(s.end)}" if s.entry is not None else "—"
        rows.append(f"| {s.name} | {s.first}–{s.last} | {where} | {'; '.join(s.notes)} |")
    if ed.silences:
        rows += ["", "Обрывы в тишину: " + ", ".join(V.ts(x) for x in ed.silences)]
    rows += ["", f"Длина: {V.ts(ed.total)}"]
    return "\n".join(rows) + "\n"


# ---------------------------------------------------------------- звук и картинка

def clips(ed):
    """Склейки edit.py (Clip, Fill) по монтажу — для edit.render_frames и edit.render_sfx."""
    out, of = [], {}
    for p in ed.pieces:
        if p.take is None:
            c = E.Fill(p.dur, int(p.spec.get("level", 0)), p.plan_no, p.start)
        else:
            c = E.Clip(p.take, None, p.dur, p.spec.get("note", ""), sfx=p.sfx, xfade=float(p.spec.get("xfade", 0.0)),
                       flash=bool(p.spec.get("flash", False)), zoom=tuple(p.spec.get("zoom", (1.0, 1.0))),
                       frame_y=float(p.spec.get("frame_y", 0.5)))
            c.rate, c.plan, c.what, c.start, c.src = p.rate, p.plan_no, p.label, p.start, p.src
            of[id(c)] = p
        out.append(c)
    return out, of


def soundtrack(ed, segs, music_path, shots, clip_list, of):
    """Сведение: звук дублей по плану звука (edit.render_sfx через окна span), музыка по кускам (edit.music_track),
    уход музыки под голос снаряда (edit.mix); громкость и пик — loudness.master."""
    windows = {}
    for g in segs:
        windows[(g.piece.i, g.sound.id)] = (g.t0, g.t1, g.rings)

    def span(c, e):
        p = of.get(id(c))
        return windows.get((p.i, e["id"])) if p is not None else None

    raw = E.mix(clip_list, ed.silences, shots, ed.total, music_path, span=span, parts=parts(ed), limit=False)
    out, info = L.master(raw, E.SR, TARGET_LUFS, CEILING_DBTP)
    return out.astype("float32"), info


def quiet_check(ed, audio, info):
    """Тишина остаётся тишиной: в склейке "quiet" блоки 400 мс на заданные LU тише ролика; заливка с обрывом в
    тишину и без музыки — тише −70 LUFS."""
    bl = L.blocks(audio, E.SR)
    bad = []

    def loudest(a, b):
        i0, i1 = int(math.ceil(a / L.STEP)), int((b - L.BLOCK) / L.STEP)
        return float(bl[i0:i1 + 1].max()) if i1 >= i0 and i0 < len(bl) else -math.inf

    for p, lu in ed.quiet:
        x = loudest(p.start, p.end)
        if x > info["lufs"] - lu:
            bad.append(f"{V.ts(p.start)} {p.label}: тихая склейка громче ролика на {x - info['lufs']:+.1f} LU, "
                       f"нужно не громче {-lu:.0f} LU")
    playing = music_map(ed)
    for k, p in enumerate(ed.pieces):
        if p.take is None and p.spec.get("silence") and not any(m.t0 < p.end and m.t1 > p.start for m in playing):
            x = loudest(p.start, p.end)
            if x > SILENT_LUFS:
                bad.append(f"{V.ts(p.start)} {p.label}: тишина звучит на {x:.1f} LUFS")
    return bad


def render(ed, takes, dirs, args, segs, out):
    """Картинка (edit.render_frames: цвет, кинокаше, титры) и звук; ffmpeg → mp4."""
    shots = {}
    for name in {p.take for p in ed.pieces if p.take}:
        s = E.load_recording(dirs[name]).get(name)
        if s is None:
            raise MontageError(f"монтаж {ed.plan['name']}: у дубля {name} в {dirs[name]} нет кадров PNG")
        shots[name] = s
    w0, h0 = E.Image.open(next(iter(shots.values())).frame(0)).size if shots else (1920, 1080)
    if w0 * 9 != h0 * 16:
        raise MontageError(f"кадры {w0}×{h0}, а нужно 16:9 — переснять: окно клиента было не того размера")
    size, step = ((1280, 720), 2) if args.draft else ((w0, h0), 1)
    for v in E.FONTS.values():   # скачать до рабочих процессов
        E.fetch(*v)
    music = E.music_file(args.music)
    clip_list, of = clips(ed)
    print("звук…")
    audio, info = soundtrack(ed, segs, music, shots, clip_list, of)
    print(f"  громкость {info['lufs_in']:.1f} → {info['lufs']:.2f} LUFS (усиление {info['gain_db']:+.1f} дБ), "
          f"истинный пик {info['tp']:.2f} dBTP, ограничитель — в {100 * info['limited']:.1f} % отсчётов")
    if abs(info["lufs"] - TARGET_LUFS) > 0.5 or info["tp"] > CEILING_DBTP + 1e-6:
        raise MontageError(f"громкость {info['lufs']:.2f} LUFS, пик {info['tp']:.2f} dBTP — не легли в −14 LUFS и −1 dBTP")
    loud = quiet_check(ed, audio, info)
    if loud:
        raise MontageError(f"монтаж {ed.plan['name']}: тишина не тиха:\n  " + "\n  ".join(loud))
    wav = os.path.join(E.CACHE, "montage-audio.wav")
    os.makedirs(E.CACHE, exist_ok=True)
    E.sf.write(wav, audio, E.SR, subtype="FLOAT")
    n = int(round(ed.total * FPS))
    fps = FPS // step
    print(f"картинка: {n // step} кадров {size[0]}×{size[1]}, {fps} fps…")
    cmd = [E.ffmpeg(), "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{size[0]}x{size[1]}",
           "-r", str(fps), "-i", "-", "-i", wav, "-c:v", "libx264", "-preset", "veryfast" if args.draft else args.preset,
           "-crf", "23" if args.draft else "15", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-c:a", "aac",
           "-b:a", "256k", "-shortest", out]
    enc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for i, buf in enumerate(E.render_frames(n, args.jobs, (clip_list, ed.texts, shots, size, ed.opened), step)):
        enc.stdin.write(buf)
        if i % (10 * fps) == 0:
            print(f"  {i / fps:.0f} с")
    enc.stdin.close()
    if enc.wait() != 0:
        raise MontageError("ffmpeg: кодирование не удалось")
    print("готово:", out)
    if not args.draft:
        lite = os.path.splitext(out)[0] + "-lite.mp4"
        subprocess.run([E.ffmpeg(), "-loglevel", "error", "-y", "-i", out, "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                        "-maxrate", "4M", "-bufsize", "8M", "-pix_fmt", "yuv420p", "-r", str(FPS), "-c:a", "aac", "-b:a", "192k",
                        "-movflags", "+faststart", lite], check=True)
        print("лёгкая версия:", lite)
    credits = os.path.splitext(out)[0] + "-credits.txt"
    with open(credits, "w", encoding="utf-8") as f:
        f.write(E.CREDITS)


# ---------------------------------------------------------------- сборка

def build(plan, takes, lang="en"):
    """Разбор и проверка: (монтаж, отчёты проверки, план звука)."""
    _IMPACTS.clear()
    ed = resolve(plan, takes, lang)
    verdicts, segs = gate(ed, takes)
    return ed, verdicts, segs


def main(argv=None):
    ap = argparse.ArgumentParser(description="монтаж трейлера от событий дублей")
    ap.add_argument("plan", help="план монтажа (JSON, схема — в начале montage.py)")
    ap.add_argument("--take", action="append", default=[], metavar="ПЛАН=ПАПКА")
    ap.add_argument("--root", help="от неё — папки \"takes\" плана (по умолчанию mod/run/scenario)")
    ap.add_argument("--check", action="store_true", help="только разобрать и проверить")
    ap.add_argument("--lang", choices=sorted(E.TEXTS), default="en")
    ap.add_argument("--draft", action="store_true", help="черновик 1280×720, 30 fps")
    ap.add_argument("--music", help="Juggernaut.mp3 (Scott Buckley): один раз, копируется в tools/.trailer-cache")
    ap.add_argument("--out", help="файл (по умолчанию dist/incoming-trailer.mp4, для ru — …-ru.mp4)")
    ap.add_argument("--report", help="отчёт проверки (Markdown) сюда, а не только в вывод")
    ap.add_argument("--preset", default="slow")
    ap.add_argument("--jobs", type=int, default=min(os.cpu_count() or 1, 6))
    args = ap.parse_args(argv)
    try:
        plan = load_plan(args.plan)
        dirs = take_dirs(plan, args.root, args.take)
        takes = load_takes(plan, dirs)
        ed, verdicts, segs = build(plan, takes, args.lang)
    except MontageError as e:
        sys.exit(str(e))
    md = table(ed, dirs) + "\n" + V.report(verdicts, f"Проверка монтажа {plan['name']}")
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            f.write(md)
    stop = failures(ed, verdicts)
    if args.check:
        print(md)
        sys.exit(1 if stop else 0)
    out = args.out or os.path.join(E.DIST, "incoming-trailer.mp4" if args.lang == "en" else f"incoming-trailer-{args.lang}.mp4")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    base = os.path.splitext(out)[0]
    with open(base + "-cuts.md", "w", encoding="utf-8") as f:
        f.write(table(ed, dirs))
    with open(base + "-check.md", "w", encoding="utf-8") as f:
        f.write(md)
    if stop:
        sys.exit(stop + f"\n(отчёт: {base}-check.md)")
    for p in ed.pieces:
        for n in p.notes:
            print(f"  ! {V.ts(p.start)} {p.label}: {n}")
    try:
        render(ed, takes, dirs, args, segs, out)
    except MontageError as e:
        sys.exit(str(e))


if __name__ == "__main__":
    main()
