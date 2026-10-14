"""Звуки в дублях v5: сколько звуков каждого рода в журнале дубля и далеко ли записывающий игрок от людей плана.
Только чтение. Запуск: python3 sound_audit.py <trailer-v5-cuts.md> <корень airstrike> > out.txt"""
import collections, glob, json, math, os, re, statistics, sys

cuts, root = sys.argv[1], sys.argv[2]
seen = set()
for line in open(cuts, encoding="utf-8"):
    c = [x.strip() for x in line.split("|")]
    if len(c) < 6 or not re.match(r"p\d", c[3]):
        continue
    shot, src = c[3], c[5]
    src = src if os.path.isabs(src) else os.path.join(root, src)
    if (shot, src) in seen:
        continue
    seen.add((shot, src))
    kinds = collections.Counter()
    tl = os.path.join(src, "timeline.jsonl")
    if not os.path.exists(tl):
        print(f"{shot:6s} нет {tl}")
        continue
    for l in open(tl, encoding="utf-8"):
        try:
            e = json.loads(l)
        except json.JSONDecodeError:
            continue
        if e.get("type") != "sound" or e.get("shot") != shot:
            continue
        ev = e["event"]
        if ev.startswith("pointblank:"):
            k = "gun"
        elif any(w in ev for w in (".engine", ".dive", ".whistle", ".flyby", "booster.loop")):
            k = "motor"
        elif "siren" in ev or "alarm" in ev:
            k = "siren"
        elif ev.startswith(("incoming:blast", "incoming:rocket.blast", "incoming:bomb.impact")):
            k = "blast"
        elif ev.startswith("incoming:"):
            k = "mod"
        else:
            k = ev.split(":")[0] + ":" + ev.split(":")[1].split(".")[0]
        kinds[k] += 1
    # где записывающий игрок относительно людей плана (журнал повтора, если есть)
    far = ""
    film = src.split("/render")[0]
    ev_file = os.path.join(film, "replays", f"{shot}.events.jsonl")
    if os.path.exists(ev_file):
        d, n = [], 0
        for l in open(ev_file, encoding="utf-8"):
            try:
                e = json.loads(l)
            except json.JSONDecodeError:
                continue
            if "me" in e and e.get("p"):
                me = e["me"]
                ds = [math.dist(me, p[1:4]) for p in e["p"] if p[0] != "Sokol"]
                if ds:
                    d.append(min(ds))
                    n = max(n, len(ds))
        if d:
            far = f"  людей {n}, записывающий до ближнего — медиана {statistics.median(d):.0f} блоков"
    top = ", ".join(f"{k} {v}" for k, v in kinds.most_common(8))
    print(f"{shot:6s} {top or 'звуков нет'}{far}")
