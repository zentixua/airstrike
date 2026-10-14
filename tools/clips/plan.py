#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["duckdb>=1.1,<2"]
# ///
"""План съёмки превью из кандидатов (moments.py) — для съёмки replayscan (-Dreplayscan.film, README там).

  uv run tools/clips/plan.py <clips>/candidates.json [--pick rockets-0,drones-1] [--all pov,fly] [--top 1] [--out <clips>/film]
         [--size 1080x1920] [--fps 60] [--replays <каталог копий>]

Берёт по --top лучших кандидатов каждого ролика (или --pick и всех кандидатов роликов --all), достаёт из базы пути снарядов (tracks и дальние
строки far), прежние удары (дым) и измерение и пишет план на каждый повтор: <out>/<повтор>/plan.json; вывод съёмки —
<out>/<повтор>/film. <out>/runs.json — что запускать: повтор, план и версия Incoming записи (jar того дня).
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "replayscan"))
import replaydb  # noqa: E402
from moments import BLAST, BLAST_LOOK, flights, incoming_version  # noqa: E402

# Пороги проверки кадров по роду плана (README replayscan, «Съёмка»): доля кадров до события, где снят объект; то же
# за последнюю секунду; доля событий, видных в первые 12 тиков; наименьшая доля ширины кадра; мотор слышен; громких
# за кадром не больше `loud` на одно в кадре (по умолчанию 1; меньше 0 — не проверяется)
NEED = {
    "launch": {"subject": 0.6, "near": 0, "keys": 1, "share": 0.004},
    "flight": {"subject": 0.5, "near": 0, "keys": 1, "share": 0.004, "engine": 0.3},
    "impact": {"subject": 0.4, "near": 0.5, "keys": 1, "share": 0.004},
    # глазами игрока, который сбивает: дрон в кадре не всё время до сбития, вокруг идёт бой — взрывы за кадром слышны
    "shootdown": {"subject": 0.25, "near": 0, "keys": 1, "share": 0.004, "loud": 3},
    # хаос — много ударов сразу: превью хватает половины видных
    "chaos": {"subject": 0, "near": 0, "keys": 0.5, "share": 0},
    "far": {"subject": 0, "near": 0, "keys": 1, "share": 0},
    "nuke": {"subject": 0, "near": 0, "keys": 1, "share": 0},
    # глазами игрока как есть: куда он смотрел, то и в кадре; громкое за кадром — его бой вокруг
    "pov": {"subject": 0, "near": 0, "keys": 0, "share": 0, "loud": -1},
    # пролёт по пути, заданному человеком: удары в кадре считаются, но не решают; громкое за кадром — налёт вокруг
    "fly": {"subject": 0, "near": 0, "keys": 0, "share": 0, "loud": -1},
    # проход пилота мимо удара: удар виден в первые тики; снаряд влетает в кадр, когда долетит (пилот его ждёт, но сзади
    # камеры его до пролёта мимо не видно); громкое за кадром — налёт вокруг
    "fpv": {"subject": 0, "near": 0, "keys": 1, "share": 0, "loud": -1, "smother": 0.03},
}


def path_of(con, uuid: str, a: int, b: int) -> list[list[float]]:
    """Путь снаряда в [a − 60, b + 60]: места сущности (tracks), где её нет — дальние строки (far)."""
    rows = con.execute("""
        SELECT k.t, k.x, k.y, k.z FROM tracks k JOIN entities e ON e.eid = k.eid
        WHERE e.uuid = ? AND k.t BETWEEN ? AND ? ORDER BY k.t""", [uuid, a - 60, b + 60]).fetchall()
    seen = {r[0] for r in rows}
    far = con.execute("SELECT t, x, y, z FROM far WHERE uuid = ? AND t BETWEEN ? AND ? ORDER BY t", [uuid, a - 60, b + 60]).fetchall()
    out = sorted([list(r) for r in rows] + [list(r) for r in far if r[0] not in seen])
    return [[t, round(x, 2), round(y, 2), round(z, 2)] for t, x, y, z in out]


def dim_of(con, shot: dict) -> str:
    s = shot["subject"]
    if shot["camera"].get("dim"):
        return shot["camera"]["dim"]
    if shot["camera"]["kind"] == "pov":
        # глазами игрока — его измерение в начале плана (отмеченный момент бывает без событий)
        r = con.execute("SELECT dim FROM viewers WHERE viewer = ? AND t >= ? ORDER BY t LIMIT 1", [shot["camera"]["viewer"], shot["a"]]).fetchone()
        if r:
            return r[0]
    if s.get("uuid"):
        r = con.execute("SELECT dim FROM entities WHERE uuid = ? LIMIT 1", [s["uuid"]]).fetchone()
        if r:
            return r[0]
    for k in s.get("keys", []):
        r = con.execute("SELECT dim FROM impacts WHERE id = ?", [k.get("id")]).fetchone()
        if r:
            return r[0]
    return "minecraft:overworld"


def cameras(shot: dict) -> list[dict]:
    """Камеры плана: глаза игрока (с запасной свободной камерой у удара, сбития, пуска и хаоса; у плана «глазами
    игроков» — без запасной), камера по пути (пролёт) или свободная камера по роду."""
    cam, s, kind = shot["camera"], shot["subject"], shot["kind"]
    keys = s.get("keys", [])
    if cam["kind"] == "pilot":
        # путь прокладывает съёмка по миру повтора (Pilot replayscan)
        return [{"kind": "pilot", "fov": cam["fov"]}]
    if cam["kind"] == "path":
        # и на 16 блоков выше: путь по карте бывает сквозь высотку
        return [{"kind": "path", "keys": cam["keys"], "fov": cam["fov"], "rise": cam.get("rise", [0, 16])}]
    if cam["kind"] == "pov":
        out = [{"kind": "pov", "player": cam["viewer"], "fov": 70}]
        if kind in ("impact", "shootdown") and keys:
            out.append({"kind": "tripod", "around": keys[0]["pos"], "dist": [60, 90, 130], "elev": [6, 16, 28], "share": 0.3,
                        "follow": True, "size": keys[0]["size"]})
        if kind == "chaos" and keys:
            # запасная — широкий план над ударами окна
            xs, zs = [k["pos"][0] for k in keys], [k["pos"][2] for k in keys]
            centre = [sum(xs) / len(xs), sum(k["pos"][1] for k in keys) / len(keys), sum(zs) / len(zs)]
            spread = max(max(xs) - min(xs), max(zs) - min(zs)) / 2
            out.append({"kind": "tripod", "around": centre, "dist": [max(90, 2.5 * spread), max(140, 3.5 * spread)],
                        "elev": [12, 25, 40], "share": 0.85, "follow": False, "size": 2 * spread + 30})
        if kind == "launch" and s.get("launch"):
            out.append({"kind": "tripod", "around": s["launch"], "dist": [35, 55, 80], "elev": [3, 10], "share": 0.15,
                        "follow": True, "size": 12})
        return out
    rig = cam.get("rig")
    # длинный объектив с места игрока: подрыв бывает за чанками повтора (ядерка вдали), место игрока — в них
    lens = [{"kind": "fixed", "eye": cam["lens"]["eye"], "aim": cam["lens"]["aim"], "fov": cam["lens"]["fov"],
             "rise": cam["lens"].get("rise", [0])}] if cam.get("lens") else []
    if rig == "lens":
        return [{"kind": "fixed", "eye": cam["eye"], "aim": "subject", "fov": cam["fov"], "rise": cam.get("rise", [0])}]
    if rig == "wide":
        spread = s.get("spread", 60)
        return [{"kind": "tripod", "around": s["centre"], "dist": cam["dist"], "elev": cam["elev"], "share": 0.85,
                 "follow": False, "size": 2 * spread + 30}]
    around = s.get("launch") if cam.get("around") == "launch" else keys[0]["pos"] if keys else s.get("centre")
    size = keys[0]["size"] if keys else 12
    return lens + [{"kind": "tripod", "around": around, "dist": cam["dist"], "elev": cam["elev"], "share": cam.get("share", 0.3),
                    "follow": kind != "nuke", "size": size}]


SMOKE_LIFE = 4800
# бомба: глубже этого под верхом (BunkerCover.BREACH_DEPTH) взрыв не прорывается наружу — без шара, столб из провала
BREACH_DEPTH = 10
# вид взрыва в пакете взрыва (S2C.Blast: шахед 0, ракета 1, бомба 2, РСЗО 3; так и в 2.10) → вид дыма Plume
PACKET_LOOK = {0: 2, 1: 3, 2: 4, 3: 1}
_HIT_WEAPONS: dict[int, tuple] = {}


def smoke_row(t: int, x: float, y: float, z: float, surface: float | None, weapon: str | None, kind: int | None = None) -> list:
    """Строка дыма удара для Plume replayscan: место, поперечник шара, вид взрыва — по пакету взрыва, как у мода, без пакета
    — по оружию снаряда. Дым бомбы — над верхом (surfaceY пакета, как у FarBlasts.add), не у заряда в толще; заряд глубже
    BREACH_DEPTH — вид 5 (столб без шара)."""
    look = PACKET_LOOK.get(kind, BLAST_LOOK.get(weapon, 2))
    if look == 4 and surface is not None:
        if math.floor(int(surface) - y) > BREACH_DEPTH:
            look = 5
        y = surface
    return [t, x, y, z, BLAST.get(weapon, 16), look]


def hit_weapons(con) -> dict:
    """Удар (id) → оружие его снаряда; один раз на базу. Запись держит и саму базу: адрес закрытой занимает следующая,
    и по одному `id` ей досталось бы чужое оружие."""
    hit = _HIT_WEAPONS.get(id(con))
    if hit is None or hit[0] is not con:
        hit = _HIT_WEAPONS[id(con)] = (con, {f.hit: f.weapon for f in flights(con) if f.hit is not None})
    return hit[1]


def film_shot(con, cid: str, shot: dict) -> dict:
    a, b, kind, s = shot["a"], shot["b"], shot["kind"], shot["subject"]
    subject = {"size": s.get("size", 4), "keys": [{"t": k["t"], "pos": k["pos"], "size": k["size"]} for k in s.get("keys", [])]}
    if s.get("uuid"):
        subject["uuid"] = s["uuid"]
        subject["path"] = path_of(con, s["uuid"], a, b)
    if s.get("centre"):
        subject["area"] = {"pos": s["centre"], "size": 2 * s.get("spread", 60)}
    dim = dim_of(con, shot)
    # столб дыма у Incoming стоит до 4 минут (FarBlasts.Look.life ракеты — 4800 тиков): удары с тех пор; в кадре из них —
    # только удары живого повтора (с a − warm, Plan.Shot.live в replayscan), прежние — для сверки модели с кадрами
    # ядерный подрыв — без строки: у него своя картинка, не столб взрыва (снаряда в пакете подрыва нет — по пакету)
    smoke = con.execute("""
        WITH k AS (SELECT id, t, x, y, z, events FROM impact_kinds
                   WHERE place = 'ground' AND dim = ? AND t BETWEEN ? AND ? AND srcs NOT LIKE '%nuke_detonation%'),
        s AS (SELECT k.id, max(TRY_CAST(json_extract(ev.data, '$.surfaceY') AS DOUBLE)) AS surface,
                     min(TRY_CAST(json_extract(ev.data, '$.kind') AS INTEGER)) AS kind
              FROM k, unnest(k.events) AS u(e) JOIN events ev ON ev.e = u.e WHERE regexp_matches(ev.src, ':blast$') GROUP BY k.id)
        SELECT k.id, k.t, k.x, k.y, k.z, s.surface, s.kind FROM k LEFT JOIN s ON s.id = k.id ORDER BY k.t""",
                        [dim, a - SMOKE_LIFE, b]).fetchall()
    # поперечник огненного шара и вид взрыва — по оружию снаряда (дым удара: модель Plume replayscan); МБР — без строки:
    # у ядерного подрыва своя картинка, не столб взрыва
    weapons = hit_weapons(con) if smoke else {}
    far = kind in ("far", "nuke", "fly")
    return {"name": f"{cid}-{shot['name']}", "a": a, "b": b, "dim": dim, "warm": 200, "hold": 100 if far or kind == "fpv" else 20,
            "far": far, "cameras": cameras(shot), "subject": subject,
            "smoke": [smoke_row(t, x, y, z, surface, weapons.get(i), blast) for i, t, x, y, z, surface, blast in smoke
                      if weapons.get(i) != "icbm"],
            "need": NEED.get(kind, NEED["impact"]), "why": shot.get("why", ""),
            **({"time": shot["camera"]["time"]} if "time" in shot["camera"] else {})}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("candidates")
    p.add_argument("--pick", default="", help="id кандидатов через запятую (по умолчанию — --top лучших каждого ролика)")
    p.add_argument("--top", type=int, default=1)
    p.add_argument("--all", default="", help="ролики, у которых снимать всех кандидатов (через запятую; «глазами игроков» — pov, "
                                             "пролёты — fly)")
    p.add_argument("--out", default=None)
    p.add_argument("--size", default="1080x1920")
    p.add_argument("--fps", type=float, default=60)
    p.add_argument("--bitrate", type=int, default=40_000_000)
    p.add_argument("--tries", type=int, default=3)
    p.add_argument("--clouds", type=float, default=None, help="низ облаков шейдерпака (по умолчанию — ванильные)")
    p.add_argument("--replays", default=None, help="каталог копий повторов (по умолчанию — путь из разбора)")
    a = p.parse_args()

    cands = json.loads(Path(a.candidates).read_text())
    out = Path(a.out) if a.out else Path(a.candidates).parent / "film"
    pick = [x for x in a.pick.split(",") if x]
    every = [x for x in a.all.split(",") if x]
    chosen = [c for k, v in cands.items() for c in v if c["id"] in pick or k in every] if pick or every \
        else [c for v in cands.values() for c in v[:a.top]]
    width, height = (int(x) for x in a.size.split("x"))
    by_replay: dict[str, dict] = {}
    for c in chosen:
        con = replaydb.connect(Path(c["db"]), argparse.Namespace(fov=70, aspect=width / height, center=0.7, soft=2))
        meta = json.loads(con.execute("SELECT json FROM meta").fetchone()[0])
        # путь — от каталога запуска: клиент съёмки читает план из своего каталога игры
        zip_path = (Path(a.replays) / Path(meta["replay"]).name if a.replays else Path(meta["replay"])).resolve()
        plan = by_replay.setdefault(c["replay"], {
            "replay": str(zip_path), "out": str((out / c["replay"] / "film").resolve()), "width": width, "height": height,
            "fps": a.fps, "bitrate": a.bitrate, "tries": a.tries, "shots": [], "incoming": incoming_version(meta),
            "previews": {}})
        if a.clouds is not None:
            plan["clouds"] = a.clouds
        plan["previews"][c["id"]] = [f"{c['id']}-{s['name']}" for s in c["shots"]]
        plan["shots"] += [film_shot(con, c["id"], s) for s in c["shots"]]
        con.close()
    runs = []
    for name, plan in by_replay.items():
        d = out / name
        d.mkdir(parents=True, exist_ok=True)
        (d / "plan.json").write_text(json.dumps(plan, ensure_ascii=False, indent=1))
        runs.append({"replay": plan["replay"], "plan": str((d / "plan.json").resolve()), "incoming": plan["incoming"],
                     "shots": len(plan["shots"]), "previews": plan["previews"]})
    (out / "runs.json").write_text(json.dumps(runs, ensure_ascii=False, indent=1))
    for r in runs:
        print(f"{r['plan']}: {r['shots']} shots, Incoming {r['incoming']}, {', '.join(r['previews'])}")


if __name__ == "__main__":
    sys.exit(main())
