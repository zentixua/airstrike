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
"""Экзамен проверки монтажа (verdict.py) на трейлере v5: она должна упасть там, где Артём увидел брак.

    uv run tools/trailer/exam_v5.py <распакованный архив exam_pack.py> [--md отчёт.md] [--json итог.json]

Монтаж v5 восстанавливается без кадров: склейки и окна дублей — таблица склеек из архива (`cuts.md`), что каждая
склейка должна показать и сколько её удар звучит после неё (`Clip.ring`) — план монтажа `edit.py` (`build_edit`),
какие звуки дублей легли в ролик — по правилу `edit.render_sfx` (звук в окне склейки; удар с `ring` — до обрыва в
тишину), где играет музыка — `edit.PARTS`. Роды склеек v5 — `KINDS` ниже: что план v5 обещал показать (его
подписи в `edit.PLANS`), а не то, что в кадре вышло.

Брак, который Артём назвал (разбор v5, 14.10, `EXPECT`): пик без ударов (1:15–1:22.7), звук удара p37b не успел (1:13),
бой без выстрелов у камеры (p38), МБР под дымом, вне кадра и точкой (p40a, p41, p42), тревога оборвана музыкой
(0:26–0:28), начало не на ударах (p01a, p01c, p01d). Экзамен сдан, если у каждого пункта склейки — FAIL с причиной этого
рода, а у пунктов `NOT_DEFECTS` (не брак по правилам игры) такого FAIL нет; остальные FAIL — список ниже, каждый —
брак, который проверка нашла сама, или порог не тот.
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import edit as E  # noqa: E402
import takes as T  # noqa: E402
import verdict as V  # noqa: E402

# не брак по правилам игры (разбор v5 считал выстрелы у записывающего игрока, а не у камеры): у камеры выстрел АК
# слышен до 80 блоков (журнал: range 80, линейно), а камеры p31, p32 — в 310–430 блоках от бойцов, p39a–c — в 190–230;
# экзамен требует, чтобы проверка их здесь НЕ роняла
NOT_DEFECTS = [
    ("выстрелы p31, p32, p39a–c: бой дальше, чем слышен выстрел", [(None, "p31"), (None, "p32"), (39, "p39a"), (39, "p39b"),
                                                                    (39, "p39c")], {"gunfire"}),
]
# что склейка v5 должна была показать: род по дублю (план и буква), (род, бой в кадре, свои пороги)
KINDS = {
    # старт: «каждая склейка на взрыв» (PLANS, план 1)
    "p01a": "montage", "p01b": "montage", "p01c": "montage", "p01d": "montage", "p01e": "montage",
    "p03": "scene", "p04": "scene", "p05": "scene", "p06": "scene",
    "p07": "hud", "p08": "hud", "p09": "hud",
    "p10a": "launch", "p10b": "launch", "p10c": "launch",
    "p11": "flight", "p12": "flight", "p13": "hit", "p14": "siren",
    "p15": "scene", "p16": "scene", "p17": "hit", "p18": "scene", "p19a": "scene", "p19b": "flight",
    "p20": "launch", "p21": "flight", "p22a": "hit", "p22b": "hit", "p23": "hit", "p24": "scene", "p25": "flight",
    "p26": ("montage", True), "p27a": ("scene", True), "p27b": ("scene", True), "p27c": ("scene", True),
    "p28": "montage", "p29": "hit", "p30": "flight",
    "p31": ("montage", True), "p32": ("montage", True), "p33": "hit", "p34": "onboard", "p35": "launch",
    "p36": "hit", "p37": "hit", "p37b": "hit", "p38": ("scene", True),
    "p39a": ("montage", True), "p39b": ("montage", True), "p39c": ("montage", True), "p39d": "montage",
    "p40b": "launch", "p40c": "launch",
    "p41": ("flight", False, {"hero": True}), "p42": ("flight", False, {"hero": True}),
    "p43": "hud", "p44": "scene", "p45": "nuke", "p46": "nuke", "p47": "wave", "p48": "wave", "p49": "wave",
    "p50": "wave", "p53": "hud",
}
# в пике — пуски и удары вперемешку: чужой план в пике — его род из таблицы, у пусков — пуск
PEAK_KINDS = {"p20": "launch", "p01c": "launch"}
# строки второго ключа (en_us.json: incoming.nuke.key.prompt, .confirmed) с местом и именем командира p40a
KEY_LINES = ("☢ Volkov asks for the second key: ICBM at 1203 64 -880, 15 kt. Confirm with K (30 s left)",
             "☢ Volkov turned the second key: ICBM at 1203 64 -880")

# брак v5 по разбору Артёма: (что, склейки — план или (план, дубль) или время ролика, причины, которые годятся)
EXPECT = [
    ("пик без ударов, 1:15–1:22.7", [(39, None)], {"event", "visible", "repeat"}),
    ("1:13: звук удара p37b не успел", [(38, "p37b")], {"hold", "sound"}),
    ("бой без выстрелов: p38 (камера в 50 блоках от бойцов)", [(None, "p38")], {"gunfire"}),
    ("МБР закрыта дымом старта: p40a", [(40, "p40a", "launch")], {"visible", "data", "smoke"}),
    ("МБР нет в кадре: p41", [(41, "p41")], {"visible", "data", "light", "smoke"}),
    ("МБР — точка: p42", [(42, "p42")], {"size", "data", "light"}),
    ("тревога 0:26–0:28 оборвана музыкой", [(14, "p14")], {"hold", "music"}),
    ("начало не на ударах: p01a, p01c, p01d", [(1, "p01a"), (1, "p01c"), (1, "p01d")], {"event", "visible"}),
]


# разбор остальных FAIL v5 по кадрам и журналам (14.10): брак, который проверка нашла сама, пробел данных или порог
TRIAGE = {
    "p01b": "брак: вспышка удара на первом кадре склейки, нарезка читается скачком",
    "p01e": "брак: звук последнего удара начала режет белая вспышка (по замыслу), и он не слышен вовсе; FPV дрожит",
    "p11": "брак: погоня дёргается (замечание Артёма 13.10)",
    "p17": "проверить кадры: удар за окном квартиры не виден по лучам (стена или рама закрывают место)",
    "p22a": "пробел данных: перехват ЗРК не пишется ударом в журнал — нужен свой след события",
    "p22b": "брак: шахед входит в крышу после конца склейки",
    "p25": "брак: рой над крышами виден меньше половины склейки",
    "p27a": "брак или порог: взгляд стрелка рывками (живой план) — решить по кадрам",
    "p27b": "брак или порог: взгляд стрелка рывками (живой план) — решить по кадрам",
    "p27c": "брак или порог: взгляд стрелка рывками (живой план) — решить по кадрам",
    "p31": "на грани порога дрожи",
    "p33": "порог: бинокль, три удара по замыслу с 0,35 с — подлёт виден в окуляре",
    "p36": "брак (мелкий): склейка кончается на 0,1 с раньше хвоста звука",
    "p37": "брак: бомба B-2 падает после конца склейки — «земля вспучивается» не в кадре",
    "p40a": "брак: строки второго ключа на экране по полсекунды — не прочесть",
    "p40b": "брак: после старта меньше 0,6 с — схода ракеты не видно",
    "p40c": "брак: после старта меньше 0,6 с — схода ракеты не видно",
}


def parse_cuts(path):
    rows = []
    for line in open(path, encoding="utf-8"):
        c = [x.strip() for x in line.split("|")]
        if len(c) < 6 or not re.match(r"\d+:\d", c[1]):
            continue
        a, b = (E.parse_time(x) for x in c[1].split("–"))
        win = [float(x) for x in c[4].split("–")] if c[4] else None
        rows.append({"start": a, "end": b, "plan": int(c[2]), "shot": c[3].split()[0], "stub": "заглушка" in c[3],
                     "window": win, "src": c[5]})
    return rows


def take_dir(base, root, src):
    """Папка дубля в архиве: путь из таблицы склеек — от корня дерева съёмки."""
    rel = os.path.relpath(src, root) if os.path.isabs(src) and src.startswith(root + os.sep) else src.lstrip(os.sep)
    return os.path.join(base, rel)


def placed_sounds(cuts, clips, takes, silences, total):
    """Звуки дублей в ролике по правилу edit.render_sfx: звук, который звучит в окне склейки; удар склейки с ring
    (HIT_SOUNDS, начат в ней или до HIT_LATE после) — и после неё, не дальше ring и ближайшего обрыва. Громкость —
    наибольшая у камеры склейки (после её конца — её последний кадр)."""
    out = []
    for c in cuts:
        clip = clips.get(id(c))
        if c.take is None or clip is None or clip.sfx <= 0:
            continue
        t = takes[c.take]
        lo, hi = c.src, c.src_end
        cut = min((s for s in silences if s >= c.end - 1e-6), default=total)
        ring_end = hi + max(0.0, min(clip.ring, cut - c.end)) * c.rate
        for s in t.sounds:
            if s.event.startswith(E.SKIP_SOUNDS):
                continue
            t0 = s.t
            end = s.stop if s.stop is not None else max(t.duration, t0) + 30.0
            rings = clip.ring > 0 and not s.loop and s.event.startswith(E.HIT_SOUNDS) and lo <= t0 <= hi + E.HIT_LATE * c.rate
            if end < lo - 0.05 or t0 > hi and not rings:
                continue
            seg0, seg1 = max(t0, lo), min(ring_end if rings else hi, end)
            if seg1 <= seg0:
                continue
            frames = t.frames_in(seg0, min(seg1, hi)) or [f for f in t.frames if f.t <= hi][-1:]
            gain = max((s.gain_at(f.cam, f.t) for f in frames), default=0.0) * clip.sfx
            out.append(V.Placed(c.take, s.id, s.event, s.kind, c.at(seg0), c.at(seg1), gain, t0))
    return out


def music_map():
    """Где играет музыка v5 и как громко: куски edit.PARTS по 0,25 с (громкость — точки p.gain)."""
    out = []
    for p in E.PARTS:
        t, end = p.track, p.end
        while t < end - 1e-9:
            t1 = min(t + 0.25, end)
            g = 1.0
            if p.gain:
                xs = [x for x, _ in p.gain]
                ys = [y for _, y in p.gain]
                mid = (t + t1) / 2
                if mid <= xs[0]:
                    g = ys[0]
                elif mid >= xs[-1]:
                    g = ys[-1]
                else:
                    i = next(i for i in range(len(xs) - 1) if xs[i] <= mid <= xs[i + 1])
                    k = (mid - xs[i]) / (xs[i + 1] - xs[i])
                    g = ys[i] + (ys[i + 1] - ys[i]) * k
            out.append(V.Music(p.trailer(t), p.trailer(t1), g))
            t = t1
    return out


def kind_of(clip, plan, shot):
    k = KINDS.get(shot, "scene")
    if plan == 39:   # пик: «удар на каждой склейке — свой», бой — по плану дубля
        base = k if isinstance(k, tuple) else (k,)
        k = (PEAK_KINDS.get(shot, "montage"), *base[1:])
    if shot == "p40a":   # строки второго ключа — текст, последняя склейка — пуск МБР
        k = "text" if "asks" in clip.note or "turned" in clip.note else ("launch", False, {"hero": True})
    if isinstance(k, str):
        k = (k,)
    return k[0], (k[1] if len(k) > 1 else False), (k[2] if len(k) > 2 else {})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack", help="распакованный архив exam_pack.py (manifest.json, cuts.md, дерево дублей)")
    ap.add_argument("--md")
    ap.add_argument("--json")
    a = ap.parse_args()
    man = json.load(open(os.path.join(a.pack, "manifest.json"), encoding="utf-8"))
    root = man["root"]
    rows = parse_cuts(os.path.join(a.pack, "cuts.md"))
    edit, texts, silences, total, _ = E.build_edit("en")
    if len(edit) != len(rows):
        sys.exit(f"план монтажа edit.py ({len(edit)} склеек) не тот, что у v5 ({len(rows)}): другая версия edit.py")
    cuts, clips, takes, notes = [], {}, {}, []
    for c, r in zip(edit, rows):
        if c.plan != r["plan"] or abs(c.start - r["start"]) > 0.02:
            notes.append(f"склейка {E.fmt_time(r['start'])} плана {r['plan']}: в edit.py — план {c.plan} с {E.fmt_time(c.start)}")
        dur = r["end"] - r["start"]
        if isinstance(c, E.Fill) or r["window"] is None:
            cuts.append(V.Cut(r["start"], dur, "fill", plan=r["plan"], label="белый" if getattr(c, "level", 0) else "чёрный"))
            continue
        shot = r["shot"]
        d = take_dir(a.pack, root, r["src"])
        key = f"{shot}@{r['src']}"
        if key not in takes:
            rec = d.split("/render")[0] if "/render" in d else d
            takes[key] = T.load_take(shot, d, recording=rec)
        kind, fight, need = kind_of(c, r["plan"], shot)
        src0, src1 = r["window"]
        rate = (src1 - src0) / dur if dur > 0 else 1.0
        text = ()
        if kind == "text" and shot == "p40a":
            text = (KEY_LINES[0],) if "asks" in c.note else (KEY_LINES[1],)
        cut = V.Cut(r["start"], dur, kind, key, src0, round(rate, 3) if abs(rate - 1) > 0.02 else 1.0, r["plan"], shot,
                    dict(need), fight, text)
        cuts.append(cut)
        clips[id(cut)] = c
    # строки на экране (чат ведущего, титры) — склейки «text» без дубля
    for x in texts:
        cuts.append(V.Cut(x.at, x.dur, "text", None, plan=0, label=f"текст: {x.style}", text=tuple(x.lines)))
    placed = placed_sounds(cuts, clips, takes, silences, total)
    music = music_map()
    verdicts = V.Checker(cuts, takes, placed, music, silences).run()

    def match(v, sel):
        c = v.cut
        plan, shot = sel[0], sel[1]
        kind = sel[2] if len(sel) > 2 else None
        return ((plan is None or c.plan == plan) and (shot is None or c.label == shot) and c.take is not None
                and (kind is None or c.kind == kind))

    exam = []
    for what, sels, rules in EXPECT:
        hit = [v for v in verdicts if any(match(v, s) for s in sels)]
        good = [v for v in hit if any(f.level == "FAIL" and f.rule in rules for f in v.findings)]
        exam.append({"what": what, "cuts": len(hit), "fail": len(good),
                     "pass": [f"{V.ts(v.cut.start)} {v.cut.label}" for v in hit if v not in good],
                     "ok": bool(hit) and len(good) == len(hit)})
    for what, sels, rules in NOT_DEFECTS:
        hit = [v for v in verdicts if any(match(v, s) for s in sels)]
        wrong = [v for v in hit if any(f.level == "FAIL" and f.rule in rules for f in v.findings)]
        exam.append({"what": "не брак: " + what, "cuts": len(hit), "fail": len(wrong),
                     "pass": [f"{V.ts(v.cut.start)} {v.cut.label}" for v in wrong], "ok": bool(hit) and not wrong})
    expected_ids = {id(v) for v in verdicts for _, sels, _ in EXPECT for s in sels if match(v, s)}
    other = [v for v in verdicts if not v.ok and id(v) not in expected_ids]

    lines = ["# Экзамен проверки монтажа на v5", "",
             f"Склеек с дублем: {sum(1 for c in cuts if c.take)}, звуков в ролике по правилу edit.py: {len(placed)}.", ""]
    if notes:
        lines += ["Расхождения edit.py с таблицей v5 (время — по таблице):", ""] + [f"- {n}" for n in notes] + [""]
    lines += ["## Брак, который назвал Артём", "", "| Что | Склеек | FAIL нужного рода | Итог | Прошли зря (у «не брак» — упали зря) |",
              "|---|---|---|---|---|"]
    for e in exam:
        lines.append(f"| {e['what']} | {e['cuts']} | {e['fail']} | {'сдан' if e['ok'] else 'НЕ СДАН'} | {', '.join(e['pass'])} |")
    lines += ["", f"Экзамен: {'сдан' if all(e['ok'] for e in exam) else 'не сдан'} "
              f"({sum(e['ok'] for e in exam)} из {len(exam)}).", "",
              f"## Другие FAIL ({len(other)})", "",
              "Брак, который проверка нашла сама, или неверный порог: каждый разобран ниже в отчёте.", ""]
    for v in other:
        why = TRIAGE.get(v.cut.label)
        lines.append(f"- {V.ts(v.cut.start)} {v.cut.label} ({v.cut.kind}): "
                     + "; ".join(f.text for f in v.findings if f.level == "FAIL") + (f" — **{why}**" if why else " — **не разобрано**"))
    lines += ["", "МБР p41 и p42 падают по данным: у вывода v5 нет меры света на объекте и его же дыма (FrameQa до 14.10 "
              "не пишет их, а дым своего следа не считает вовсе). Сами меры проверяются на сцене-образце, а не этим "
              "экзаменом."]
    lines += ["", V.report(verdicts, "Все склейки")]
    md = "\n".join(lines)
    if a.md:
        with open(a.md, "w", encoding="utf-8") as f:
            f.write(md)
    else:
        print(md)
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump({"exam": exam, "verdicts": [{"start": v.cut.start, "dur": v.cut.dur, "plan": v.cut.plan,
                                                   "take": v.cut.label, "kind": v.cut.kind, "ok": v.ok,
                                                   "findings": [f.__dict__ for f in v.findings], "data": v.data}
                                                  for v in verdicts]}, f, ensure_ascii=False, indent=1)
    print(f"экзамен: {sum(e['ok'] for e in exam)} из {len(exam)}; других FAIL: {len(other)}", file=sys.stderr)


if __name__ == "__main__":
    main()
