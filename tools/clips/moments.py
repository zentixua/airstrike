#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["duckdb>=1.1,<2"]
# ///
"""Отбор моментов для коротких роликов (README.md): по базам разбора повторов (tools/replayscan/replaydb.py) —
цепочки «пуск → полёт → удар по земле» и окна хаоса, дальние удары и ядерные подрывы, с камерой на каждый план.

  uv run tools/clips/moments.py <база.duckdb>... [--out каталог] [--top 8] [--notes журнал.json]

Пишет `candidates.json` (что читает plan.py) и `candidates.md` (таблица для выбора). Камера плана — глаза игрока
повтора (`pov`), если данные показывают, что он смотрел туда, иначе — место, которое подберёт вывод (`auto`: штатив
по кругу у события, проверка лучами по миру повтора и каждого кадра — режим film мода replayscan).
Числа — из данных повтора: удар по земле — у поверхности (replaydb: impact_kinds.place = ground), в кадре — по месту
в плоскости кадра, луч — по блокам мира повтора; громкие события за кадром считаются по приходу звука.
Журнал игры (`--notes`, README.md) — что в какое время происходило по журналам сервера: кандидат в отрезке журнала
получает его строку и прибавку к счёту, отбор по данным повтора остаётся тем же.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import duckdb

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "replayscan"))
import replaydb  # noqa: E402

TICK = 20  # тиков в секунду

# Оружие по типу сущности и по номеру в пакете дальних снарядов (WeaponType: порядок неизменен с 2.x)
FLIGHTS = r"""
CREATE OR REPLACE MACRO weapon_of(type) AS CASE
  WHEN type LIKE '%:drone' THEN 'shahed' WHEN type LIKE '%:loiter' THEN 'lancet'
  WHEN type LIKE '%:cruise_missile' THEN 'missile' WHEN type LIKE '%:rocket' THEN 'grad'
  WHEN type LIKE '%:icbm' THEN 'icbm' WHEN type LIKE '%:bomber' THEN 'b2'
  WHEN type LIKE '%:bunker_buster' THEN 'bomb' END;
CREATE OR REPLACE MACRO weapon_code(w, bomber) AS CASE w
  WHEN 0 THEN 'shahed' WHEN 1 THEN 'missile' WHEN 2 THEN CASE WHEN bomber THEN 'b2' ELSE 'bomb' END
  WHEN 3 THEN 'icbm' WHEN 4 THEN 'grad' WHEN 5 THEN 'lancet' END;
-- место у удара известно (чанк был у клиента): замеры земли есть или воронка видна
CREATE OR REPLACE MACRO known_ground(place, clear, solid, broken) AS
  place = 'ground' AND (coalesce(clear, -1) >= 0 OR coalesce(solid, -1) >= 0 OR coalesce(broken, 0) > 0);

-- снаряд по UUID: сущность (первое появление — пуск, если почти без скорости или у пусковой) и дальние записи
CREATE OR REPLACE TABLE flights AS
WITH e AS (
  SELECT s.*, weapon_of(s.type) AS weapon, row_number() OVER (PARTITION BY s.uuid ORDER BY s.first) AS k
  FROM subjects s WHERE weapon_of(s.type) IS NOT NULL),
ent AS (
  SELECT uuid, any_value(weapon) AS weapon, any_value(dim) AS dim, min(first) AS e_first, max(last) AS e_last,
         max(eid) FILTER (WHERE k = 1) AS eid0, max(first) FILTER (WHERE k = 1) AS first0,
         max(x0) FILTER (WHERE k = 1) AS ex, max(y0) FILTER (WHERE k = 1) AS ey, max(z0) FILTER (WHERE k = 1) AS ez,
         list(eid ORDER BY first) AS eids
  FROM e GROUP BY uuid),
fr AS (
  SELECT uuid, any_value(dim) AS dim,
         any_value(weapon_code(TRY_CAST(json_extract(data, '$.weapon') AS INTEGER),
                               coalesce(TRY_CAST(json_extract(data, '$.bomber') AS BOOLEAN), false))) AS weapon,
         min(t) AS f_first, max(t) AS f_last, count(*) AS f_rows
  FROM far WHERE src LIKE '%:far_flights' GROUP BY uuid),
start AS (
  SELECT n.uuid,
         (SELECT max(sqrt(tr.vx ^ 2 + tr.vy ^ 2 + tr.vz ^ 2)) FROM tracks tr WHERE tr.eid = n.eid0 AND tr.t <= n.first0 + 3) AS v0,
         (SELECT min(sqrt((l.x0 - n.ex) ^ 2 + (l.y0 - n.ey) ^ 2 + (l.z0 - n.ez) ^ 2)) FROM entities l
           WHERE l.type LIKE '%launcher%' AND l.dim = n.dim AND l.first <= n.first0 AND l.last >= n.first0) AS launcher
  FROM ent n),
-- удар снаряда — его пакет взрыва (UUID снаряда в пакете): место и замеры земли — его, воронка — удара, куда он вошёл
-- (replaydb сводит взрывы в 3 тиках и 6 блоках в один удар, и снаряды плотного залпа попадают в один)
boom AS (
  SELECT uuid, arg_min(e, t) AS e FROM blasts
  WHERE kind = 'payload' AND uuid IS NOT NULL AND NOT regexp_matches(src, ':intercept$') GROUP BY uuid),
hit AS (
  SELECT o.uuid, b.t, b.x, b.y, b.z, b.clear, b.solid, k.id, k.power, k.broken, k.place AS group_place
  FROM boom o JOIN blasts b ON b.e = o.e JOIN impact_kinds k ON list_contains(k.events, o.e)),
hitp AS (
  SELECT h.*, CASE
    WHEN (h.solid IS NOT NULL AND h.solid >= 0 AND h.solid <= 2.5) OR (h.clear IS NOT NULL AND h.clear >= 0 AND h.clear <= 2) THEN 'ground'
    WHEN h.solid IS NOT NULL AND h.solid >= 0 AND h.clear IS NOT NULL AND h.clear >= 0 THEN CASE WHEN h.broken > 0 AND h.clear <= 4 THEN 'ground' ELSE 'air' END
    ELSE h.group_place END AS place
  FROM hit h),
kill AS (
  SELECT uuid, min(t) AS t_kill FROM impact_kinds WHERE intercept AND uuid IS NOT NULL GROUP BY uuid)
SELECT coalesce(n.uuid, f.uuid) AS uuid, coalesce(n.weapon, f.weapon) AS weapon, coalesce(n.dim, f.dim) AS dim,
       least(n.e_first, f.f_first) AS first, greatest(n.e_last, f.f_last) AS last,
       n.e_first, n.e_last, n.eid0, n.eids, f.f_first, f.f_last, coalesce(f.f_rows, 0) AS f_rows,
       coalesce(s.v0 < 0.5 OR s.launcher <= 8, false) AND n.e_first <= coalesce(f.f_first, n.e_first) AS launched,
       n.ex AS lx, n.ey AS ly, n.ez AS lz,
       k.id AS hit, k.t AS t_hit, k.place, k.x AS hx, k.y AS hy, k.z AS hz, k.power, k.broken,
       coalesce(known_ground(k.place, k.clear, k.solid, k.broken), false) AS ground,
       c.t_kill
FROM ent n FULL OUTER JOIN fr f ON f.uuid = n.uuid
LEFT JOIN start s ON s.uuid = n.uuid
LEFT JOIN hitp k ON k.uuid = coalesce(n.uuid, f.uuid)
LEFT JOIN kill c ON c.uuid = coalesce(n.uuid, f.uuid);
"""

# Взгляд игроков: что из удара, пуска и пролёта попало в их кадр (кадр — переменные fov, aspect, center)
POV = {
    # удар по земле в кадре, луч чист, 40–300 блоков
    "impact": """
        SELECT viewer, id, t, dist, cx, cy, in_frame(cx, cy, getvariable('center')) AS centered, heard
        FROM impact_views
        WHERE place = 'ground' AND dist BETWEEN 40 AND 300 AND in_frame(cx, cy, 1) AND coalesce(clear, false)""",
    # пуск: 30–200 блоков, в середине кадра и не закрыт ≥ 80 % первых 3 с
    "launch": """
        SELECT viewer, uuid, first AS t, dist0 AS dist, seen, samples FROM launches
        WHERE dist0 BETWEEN 30 AND 200 AND samples > 0 AND seen >= 0.8 * samples""",
    # пролёт: в 10–60 блоках, в кадре и открыт ≥ 1.5 с; первый и последний тик в кадре и ближе всего в кадре
    "flyby": """
        WITH v AS (
          SELECT viewer, ref, min(t) AS t_in, max(t) AS t_out, min(dist) AS closest FROM sight
          WHERE kind = 'e' AND dist BETWEEN 10 AND 60 AND in_frame(cx, cy, 1) GROUP BY viewer, ref)
        SELECT f.viewer, e.uuid, v.t_in AS t, v.closest AS dist, f.seen_ticks, v.t_out
        FROM flybys f JOIN entities e ON e.eid = f.eid JOIN v ON v.viewer = f.viewer AND v.ref = CAST(f.eid AS VARCHAR)
        WHERE f.seen_ticks >= 30 AND f.closest >= 10""",
}

# Громкие события у зрителя: удары (не перехваты), их звук приходит через dist / 17.15 тиков
LOUD = """
    SELECT viewer, id, t, heard, dist, in_frame(cx, cy, 1) AND coalesce(clear, false) AS shown
    FROM impact_views WHERE place <> 'intercept' AND dist <= 600"""


@dataclass
class Flight:
    uuid: str
    weapon: str
    first: int
    last: int
    launched: bool
    launch: tuple | None
    hit: int | None
    t_hit: int | None
    place: str | None
    pos: tuple | None
    ground: bool
    killed: int | None
    f_rows: int
    eids: list = field(default_factory=list)


def flights(con) -> list[Flight]:
    con.execute(FLIGHTS)
    out = []
    for r in con.execute("""SELECT uuid, weapon, first, last, launched, lx, ly, lz, hit, t_hit, place, hx, hy, hz, ground, t_kill,
                                   f_rows, eids FROM flights WHERE weapon IS NOT NULL ORDER BY first""").fetchall():
        uuid, weapon, first, last, launched, lx, ly, lz, hit, t_hit, place, hx, hy, hz, ground, t_kill, f_rows, eids = r
        out.append(Flight(uuid, weapon, first, last, bool(launched), (lx, ly, lz) if launched else None, hit, t_hit, place,
                          (hx, hy, hz) if hit is not None else None, bool(ground), t_kill, f_rows or 0, list(eids or [])))
    return out


def dist(a, b) -> float:
    return math.dist(a, b)


def salvos(fl: list[Flight], gap: int = 100, spread: float = 64) -> list[list[Flight]]:
    """Залпы: снаряды одного оружия, пущенные с разницей не больше gap тиков друг за другом и не дальше spread блоков
    от места первого (пущенные вне мира клиента — по времени первой записи)."""
    groups: list[list[Flight]] = []
    for f in sorted(fl, key=lambda f: f.first):
        g = next((g for g in reversed(groups) if g[0].weapon == f.weapon and f.first - g[-1].first <= gap
                  and (f.launch is None or g[0].launch is None or dist(f.launch, g[0].launch) <= spread)), None)
        if g is None:
            groups.append([f])
        else:
            g.append(f)
    return groups


def mmss(t: int) -> str:
    return f"{int(t // 1200):02d}:{(t % 1200) / 20:04.1f}"


@dataclass
class Shot:
    """План ролика: что снимать (снаряд и событие), когда и чем (камера)."""
    name: str
    kind: str          # launch | flight | impact | chaos | far | nuke
    a: int             # тики повтора
    b: int
    camera: dict       # {"kind": "pov", "viewer": "recorder" | "player:<ник>"} | {"kind": "auto", …}
    subject: dict      # {"uuid", "weapon"} и/или {"keys": [{"t", "pos", "size"}]}
    why: str = ""

    def json(self):
        return {"name": self.name, "kind": self.kind, "a": self.a, "b": self.b, "camera": self.camera,
                "subject": self.subject, "why": self.why}


@dataclass
class Candidate:
    preview: str
    replay: str
    db: str
    score: float
    t: int
    weapon: str
    summary: str
    numbers: dict
    shots: list[Shot]

    def json(self, i: int):
        return {"id": f"{self.preview}-{i}", "preview": self.preview, "replay": self.replay, "db": self.db,
                "score": round(self.score, 3), "t": self.t, "at": mmss(self.t), "weapon": self.weapon,
                "summary": self.summary, "numbers": self.numbers, "shots": [s.json() for s in self.shots]}


# Поперечник огненного шара удара, блоков (для доли кадра и лучей к краям): как у трейлера (Rig.blastOf)
BLAST = {"grad": 8, "shahed": 12, "lancet": 12, "missile": 18, "bomb": 24, "b2": 24, "icbm": 120}
SIZE = {"grad": 3, "shahed": 3.5, "lancet": 2.5, "missile": 6, "bomb": 5, "b2": 50, "icbm": 18}


def key(f: Flight) -> dict:
    return {"t": f.t_hit, "pos": list(f.pos), "size": BLAST.get(f.weapon, 16), "id": f.hit}


class Replay:
    """Одна база: снаряды, залпы и взгляды игроков; отбор кандидатов под каждый ролик."""

    def __init__(self, db: Path, a):
        self.db = db
        self.aspect = a.aspect
        self.con = replaydb.connect(db, a)
        meta = json.loads(self.con.execute("SELECT json FROM meta").fetchone()[0])
        self.con.execute(f"SET VARIABLE every = {int(meta.get('every', 2))}")
        self.name = Path(meta.get("replay", db.parent.name)).name.removesuffix(".zip")
        self.meta = meta
        self.fl = flights(self.con)
        self.by_uuid = {f.uuid: f for f in self.fl}
        self.salvos = salvos(self.fl)
        self.pov = {k: self.con.execute(q).fetchall() for k, q in POV.items()}
        self.loud = self.con.execute(LOUD).fetchall()

    def quiet(self, viewer: str, a: int, b: int) -> tuple[int, int]:
        """Громких событий, дошедших до зрителя в [a, b]: в кадре и за кадром."""
        on = off = 0
        for v, _, _, heard, _, shown in self.loud:
            if v == viewer and a <= heard <= b:
                on, off = (on + 1, off) if shown else (on, off + 1)
        return on, off

    def best_pov(self, kind: str, ref, near: int, slack: int = 40):
        """Лучший взгляд игрока на событие: удар (id), пуск или пролёт (uuid) около тика near."""
        rows = [r for r in self.pov[kind] if r[1] == ref and abs(r[2] - near) <= slack]
        if kind == "impact":
            rows.sort(key=lambda r: (not r[6], r[3]))      # в середине кадра, ближе
        elif kind == "launch":
            rows.sort(key=lambda r: (-r[4], r[3]))
        else:
            rows.sort(key=lambda r: (-r[4], r[3]))
        return rows[0] if rows else None

    def camera_for_impact(self, f: Flight) -> tuple[dict, str]:
        p = self.best_pov("impact", f.hit, f.t_hit, 3)
        if p:
            on, off = self.quiet(p[0], f.t_hit - 60, f.t_hit + 100)
            if off <= on:
                return ({"kind": "pov", "viewer": p[0]},
                        f"глазами {p[0]}: удар в {p[3]:.0f} блоках, в кадре {p[4]:+.2f} {p[5]:+.2f}, громких в кадре {on}, за кадром {off}")
        return {"kind": "auto", "rig": "tripod", "dist": [60, 90, 130], "elev": [6, 16, 28], "share": 0.22}, "свободная камера у удара"

    def impact_shot(self, name: str, f: Flight, before: int = 60, after: int = 100) -> Shot:
        cam, why = self.camera_for_impact(f)
        return Shot(name, "impact", f.t_hit - before, f.t_hit + after, cam,
                    {"uuid": f.uuid, "weapon": f.weapon, "size": SIZE.get(f.weapon, 4), "keys": [key(f)]}, why)

    def launch_shot(self, name: str, f: Flight, after: int = 90) -> Shot:
        p = self.best_pov("launch", f.uuid, f.first, 2)
        if p:
            cam, why = {"kind": "pov", "viewer": p[0]}, f"глазами {p[0]}: пуск в {p[3]:.0f} блоках, виден {p[4]}/{p[5]} замеров"
        else:
            cam, why = {"kind": "auto", "rig": "tripod", "around": "launch", "dist": [35, 55, 80], "elev": [3, 10],
                        "share": 0.08}, "свободная камера у пуска"
        return Shot(name, "launch", f.first - 30, f.first + after, cam,
                    {"uuid": f.uuid, "weapon": f.weapon, "size": SIZE.get(f.weapon, 4), "launch": list(f.launch)}, why)

    def flyby_shot(self, name: str, f: Flight, row, longest: int = 160) -> Shot:
        """Пролёт глазами игрока: от входа в кадр до выхода из него (кружащий «Ланцет» — не дольше longest тиков)."""
        viewer, _, t_in, closest, seen, t_out = row
        return Shot(name, "flight", t_in - 20, min(t_out, t_in + longest) + 20, {"kind": "pov", "viewer": viewer},
                    {"uuid": f.uuid, "weapon": f.weapon, "size": SIZE.get(f.weapon, 4)},
                    f"глазами {viewer}: пролёт в {closest:.0f} блоках, виден {seen / TICK:.1f} с")

    # ------------------------------------------------------------------ ролики

    def rockets(self) -> list[Candidate]:
        """1 — ракеты и «Град»: залп, пущенный в мире клиента, и его удары по земле."""
        out = []
        for g in self.salvos:
            if g[0].weapon not in ("grad", "missile"):
                continue
            up = [f for f in g if f.launched]
            hits = [f for f in g if f.ground and f.t_hit is not None]
            if not up or not hits:
                continue
            first = min(up, key=lambda f: f.first)
            hit = min(hits, key=lambda f: f.t_hit)
            fly = hit.t_hit - first.first
            shots = [self.launch_shot("launch", first)]
            flyby = next((r for f in g for r in self.pov["flyby"] if r[1] == f.uuid), None)
            if flyby:
                shots.append(self.flyby_shot("flight", self.by_uuid[flyby[1]], flyby))
            shots.append(self.impact_shot("impact", self.best_hit(hits)))
            score = (min(len(hits), 8) / 8 * 3 + len(up) / len(g) + (shots[0].camera["kind"] == "pov")
                     + (shots[-1].camera["kind"] == "pov") + (1 if fly <= 25 * TICK else 0.5 if fly <= 45 * TICK else 0))
            out.append(Candidate("rockets", self.name, str(self.db), score, first.first, g[0].weapon,
                                 f"{len(g)} × {g[0].weapon}: пуск {mmss(first.first)}, {len(hits)} по земле с {mmss(hit.t_hit)}, полёт {fly / TICK:.0f} с",
                                 {"projectiles": len(g), "launched": len(up), "ground": len(hits), "flight_s": round(fly / TICK, 1)},
                                 shots))
        return out

    def best_hit(self, hits: list[Flight]) -> Flight:
        """Удар залпа для плана удара: первый из видных глазами игрока, иначе первый."""
        seen = [f for f in hits if self.best_pov("impact", f.hit, f.t_hit, 3)]
        return min(seen or hits, key=lambda f: f.t_hit)

    def drones(self) -> list[Candidate]:
        """2 — шахеды и «Ланцеты»: пролёт рядом с игроком и удары по земле рядом с ним."""
        out = []
        for g in self.salvos:
            if g[0].weapon not in ("shahed", "lancet"):
                continue
            hits = [f for f in g if f.ground and f.t_hit is not None]
            flybys = [r for f in g for r in self.pov["flyby"] if r[1] == f.uuid]
            if not hits or not flybys:
                continue
            row = min(flybys, key=lambda r: (r[3], -r[4]))
            f = self.by_uuid[row[1]]
            shots = []
            up = [x for x in g if x.launched]
            if up:
                shots.append(self.launch_shot("launch", min(up, key=lambda x: x.first)))
            shots.append(self.flyby_shot("flyby", f, row))
            after = [h for h in hits if h.t_hit >= row[2]] or hits
            shots.append(self.impact_shot("impact", self.best_hit(after)))
            score = (min(len(hits), 6) / 6 * 2 + min(row[4], 80) / 80 * 2 + (60 - min(row[3], 60)) / 60
                     + (shots[-1].camera["kind"] == "pov") + 0.5 * bool(up))
            out.append(Candidate("drones", self.name, str(self.db), score, row[2], g[0].weapon,
                                 f"{len(g)} × {g[0].weapon}: пролёт в {row[3]:.0f} блоках у {row[0]} {mmss(row[2])}, {len(hits)} по земле",
                                 {"projectiles": len(g), "ground": len(hits), "closest": round(row[3]), "flyby_s": row[4] / TICK},
                                 shots))
        return out

    def chaos(self, window: int = 400, radius: float = 110) -> list[Candidate]:
        """3 — хаос: 20 с, в которых больше всего ударов по земле в одном месте (один широкий план), и такие же окна
        глазами игроков."""
        hits = sorted((f for f in self.fl if f.ground and f.t_hit is not None), key=lambda f: f.t_hit)
        out = []
        best: dict[int, tuple] = {}
        for i, c in enumerate(hits):
            near = [h for h in hits[i:] if h.t_hit - c.t_hit < window]
            for centre in near:
                group = [h for h in near if dist(h.pos[::2], centre.pos[::2]) <= radius]
                k = c.t_hit // window
                if len(group) > best.get(k, (0,))[0]:
                    best[k] = (len(group), c.t_hit, centre, group)
        for n, t0, centre, group in best.values():
            if n < 4:
                continue
            cx = sum(h.pos[0] for h in group) / n
            cy = sum(h.pos[1] for h in group) / n
            cz = sum(h.pos[2] for h in group) / n
            spread = max(dist((h.pos[0], h.pos[2]), (cx, cz)) for h in group)
            weapons = sorted({h.weapon for h in group})
            shots = [Shot("chaos", "chaos", t0 - 40, max(h.t_hit for h in group) + 80,
                          {"kind": "auto", "rig": "wide", "dist": [max(90, 2.5 * spread), max(140, 3.5 * spread)],
                           "elev": [12, 25, 40]},
                          {"keys": [key(h) for h in group], "centre": [cx, cy, cz], "spread": spread},
                          f"{n} ударов по земле за {window / TICK:.0f} с в круге {spread:.0f} блоков")]
            out.append(Candidate("chaos", self.name, str(self.db), n + (1 if len(weapons) > 1 else 0), t0, "+".join(weapons),
                                 f"{n} ударов по земле за {window / TICK:.0f} с с {mmss(t0)} ({', '.join(weapons)}), разброс {spread:.0f} блоков",
                                 {"ground": n, "spread": round(spread), "weapons": weapons, "seconds": window // TICK}, shots))
        # глазами игроков: окна, где ударов по земле в кадре больше всего, а громкого за кадром меньше, чем в кадре
        rows = self.pov["impact"]
        for viewer in sorted({r[0] for r in rows}):
            ts = sorted(r[2] for r in rows if r[0] == viewer)
            for i, t0 in enumerate(ts):
                n = sum(1 for t in ts[i:] if t - t0 < window)
                if n < 4:
                    continue
                on, off = self.quiet(viewer, t0, t0 + window)
                if off > on:
                    continue
                out.append(Candidate("chaos", self.name, str(self.db), n + 0.5, t0, "pov",
                                     f"глазами {viewer}: {n} ударов по земле в кадре за {window / TICK:.0f} с с {mmss(t0)}, громких за кадром {off}",
                                     {"ground_in_frame": n, "loud_on": on, "loud_off": off, "seconds": window // TICK},
                                     [Shot("chaos", "chaos", t0 - 40, t0 + window + 40, {"kind": "pov", "viewer": viewer},
                                           {"keys": []}, "окно хаоса глазами игрока")]))
        return dedupe(out, 2 * window)

    def far(self) -> list[Candidate]:
        """4 — издалека: удары в 300–1500 блоках от игрока, вспышка раньше грохота, рядом по времени тихо; камера —
        у игрока, длинный объектив."""
        out = []
        rows = self.con.execute("""
            SELECT i.viewer, i.id, i.t, i.dist, i.heard, i.x, i.y, i.z, i.place, v.x, v.y, v.z,
                   (SELECT count(*) FROM impact_views o WHERE o.viewer = i.viewer AND o.id <> i.id AND o.place <> 'intercept'
                      AND o.heard BETWEEN i.t - 60 AND i.heard + 40 AND o.dist < 2 * i.dist) AS other
            FROM impact_views i JOIN viewers v ON v.viewer = i.viewer AND v.t = i.t
            WHERE i.dist BETWEEN 300 AND 1500 AND i.place IN ('ground', 'unknown') AND v.viewer NOT LIKE 'ask:%'
              AND i.srcs NOT LIKE '%nuke_detonation%'""").fetchall()
        for viewer, hid, t, d, heard, x, y, z, place, ex, ey, ez, other in rows:
            f = next((f for f in self.fl if f.hit == hid), None)
            weapon = f.weapon if f else "?"
            drawn = 0
            if f:
                drawn = self.con.execute("SELECT count(*) FROM far WHERE uuid = ? AND t BETWEEN ? AND ?",
                                         [f.uuid, t - 120, t]).fetchone()[0]
            size = BLAST.get(weapon, 16)
            # шар — 12 % ширины кадра: угол по вертикали (как Cameras.fovFor)
            fov = math.degrees(2 * math.atan(size / (2 * d * 0.12) / self.aspect))
            score = 2 - min(other, 4) / 2 + min(drawn, 30) / 15 + (1 if place == "ground" else 0) + min(d, 900) / 900
            shot = Shot("far", "far", t - 120, int(heard) + 60,
                        {"kind": "auto", "rig": "lens", "eye": [ex, ey, ez], "fov": round(max(8.0, min(20.0, fov)), 1)},
                        {"uuid": f.uuid if f else None, "weapon": weapon, "size": SIZE.get(weapon, 4),
                         "keys": [{"t": t, "pos": [x, y, z], "size": size, "id": hid}], "ground_check": place != "ground"},
                        f"от {viewer}: {d:.0f} блоков, грохот через {(heard - t) / TICK:.1f} с, других громких рядом {other}")
            out.append(Candidate("far", self.name, str(self.db), score, t, weapon,
                                 f"{weapon} в {d:.0f} блоках от {viewer} {mmss(t)}: грохот через {(heard - t) / TICK:.1f} с, "
                                 f"рядом громких {other}, путь вдали {drawn} записей" + ("" if place == "ground" else ", земля — проверить по DH"),
                                 {"dist": round(d), "boom_after_s": round((heard - t) / TICK, 1), "other_loud": other,
                                  "far_rows": drawn, "place": place}, [shot]))
        return self.far_reel(dedupe(out, 200))

    @staticmethod
    def far_reel(single: list[Candidate], longest: float = 35, most: int = 3) -> list[Candidate]:
        """Ролик «издалека» — несколько дальних ударов подряд (по времени): лучшие, пока ролик не длиннее longest с."""
        if len(single) < 2:
            return single
        pick: list[Candidate] = []
        for c in sorted(single, key=lambda c: -c.score):
            s = c.shots[0]
            # планы не перекрываются: начало следующего не повторяет конец прошлого и его грохот
            if (len(pick) < most and sum(p.shots[0].b - p.shots[0].a for p in pick) + s.b - s.a <= longest * TICK
                    and all(s.b < p.shots[0].a or s.a > p.shots[0].b for p in pick)):
                pick.append(c)
        pick.sort(key=lambda c: c.t)
        shots = [Shot(f"far{i + 1}", c.shots[0].kind, c.shots[0].a, c.shots[0].b, c.shots[0].camera, c.shots[0].subject, c.shots[0].why)
                 for i, c in enumerate(pick)]
        reel = Candidate("far", pick[0].replay, pick[0].db, sum(c.score for c in pick), pick[0].t, "+".join(sorted({c.weapon for c in pick})),
                         f"{len(pick)} дальних удара: " + "; ".join(c.summary for c in pick),
                         {"strikes": [c.numbers for c in pick]}, shots)
        return [reel] + single

    def nuke(self) -> list[Candidate]:
        """5 — ядерный удар: подрыв, пуск МБР, если он в мире клиента, и игроки, которые смотрели."""
        out = []
        for hid, t, x, y, z in self.con.execute(
                "SELECT id, t, x, y, z FROM impact_kinds WHERE srcs LIKE '%nuke_detonation%' ORDER BY t").fetchall():
            icbm = [f for f in self.fl if f.weapon == "icbm" and f.first <= t <= f.last + 40]
            shots = []
            up = [f for f in icbm if f.launched]
            # ролик не длиннее 35 с: пуск 6 с, глазами игрока 12 с, свободная камера 15 с (та же вспышка с другой стороны)
            if up:
                shots.append(self.launch_shot("launch", up[0], after=90))
            views = self.con.execute("""SELECT viewer, dist, coalesce(in_frame(cx, cy, 1), false) FROM impact_views
                                        WHERE id = ? AND viewer NOT LIKE 'ask:%' ORDER BY 3 DESC, dist""", [hid]).fetchall()
            # огненный шар над домами: в кадре достаточно, луча к земле не нужно
            seen = [v for v in views if v[2]]
            keys = [{"t": t, "pos": [x, y, z], "size": BLAST["icbm"], "id": hid}]
            if seen:
                shots.append(Shot("burst-pov", "nuke", t - 40, t + 200, {"kind": "pov", "viewer": seen[0][0]}, {"keys": keys},
                                  f"глазами {seen[0][0]}: подрыв в {seen[0][1]:.0f} блоках"))
            shots.append(Shot("burst", "nuke", t - 30, t + (270 if seen else 500),
                              {"kind": "auto", "rig": "tripod", "dist": [400, 700, 1000], "elev": [4, 10], "share": 0.35},
                              {"keys": keys}, "свободная камера: вспышка, волна, гриб"))
            score = 3 + bool(up) + bool(seen) + 0.5 * len(views)
            out.append(Candidate("nuke", self.name, str(self.db), score, t, "icbm",
                                 f"подрыв {mmss(t)}" + (f", пуск МБР в мире {mmss(up[0].first)}" if up else "")
                                 + (f", глазами {seen[0][0]} в {seen[0][1]:.0f} блоках" if seen else ", никто не смотрел в его сторону"),
                                 {"viewers": len(views), "seen": len(seen), "launch": bool(up)}, shots))
        return out


def dedupe(cands: list[Candidate], gap: int) -> list[Candidate]:
    """Из кандидатов одного повтора ближе gap тиков друг к другу — лучший."""
    out: list[Candidate] = []
    for c in sorted(cands, key=lambda c: -c.score):
        if all(o.replay != c.replay or abs(o.t - c.t) >= gap for o in out):
            out.append(c)
    return out


def clock(r: Replay, anchors: list[dict]) -> list[tuple[int, datetime]]:
    """Опоры часов повтора — тик и время по журналу сервера: конец записи (`end`) или первое событие с `src` по
    регулярному выражению (подрыв ядерки). `at: name` — время из имени файла (Flashback называет запись временем
    сохранения, `2026-10-06T09_59_12`; запись, сохранённая позже восстановлением, этой опоры не годится)."""
    out = []
    for an in anchors:
        if an.get("end"):
            t = r.meta.get("total_ticks")
        else:
            t = r.con.execute("SELECT min(t) FROM events WHERE regexp_matches(src, ?)", [an["src"]]).fetchone()[0]
        at = an["at"]
        if at == "name":
            # секунды 00 Flashback в имени не пишет (LocalDateTime.toString)
            m = re.search(r"(\d{4}-\d\d-\d\d)T(\d\d)_(\d\d)(?:_(\d\d))?", r.name)
            at = m and f"{m[1]}T{m[2]}:{m[3]}:{m[4] or '00'}"
        if t is None or not at:
            print(f"{r.name}: опоры {an} в базе нет — не берётся")
            continue
        out.append((int(t), datetime.fromisoformat(at)))
    return sorted(out)


def tick_at(anchors: list[tuple[int, datetime]], when: datetime) -> float:
    """Тик повтора во время when: между опорами — по прямой, за краями — 20 тиков в секунду от ближней."""
    t0, w0 = min(anchors, key=lambda an: abs((an[1] - when).total_seconds()))
    for (ta, wa), (tb, wb) in zip(anchors, anchors[1:]):
        if wa <= when <= wb and wb > wa:
            return ta + (tb - ta) * (when - wa).total_seconds() / (wb - wa).total_seconds()
    return t0 + (when - w0).total_seconds() * TICK


def annotate(r: Replay, cands: list[Candidate], notes: dict, bonus: float) -> None:
    """Строки журнала игры — кандидатам повтора, чей тик в их отрезке (`from`–`to`)."""
    if not notes:
        return
    anchors = next((clock(r, an) for k, an in notes.get("replays", {}).items() if k in r.name), [])
    if not anchors:
        print(f"{r.name}: в журнале игры нет опор часов этого повтора — без строк журнала")
        return
    spans = [(tick_at(anchors, datetime.fromisoformat(m["from"])), tick_at(anchors, datetime.fromisoformat(m["to"])), m["what"])
             for m in notes.get("moments", [])]
    for c in cands:
        hit = [what for a, b, what in spans if a <= c.t <= b]
        if hit:
            c.score += bonus
            c.summary += " — по журналу: " + "; ".join(hit)
            c.numbers["notes"] = hit


PREVIEWS = {"rockets": "1. Ракеты и «Град»", "drones": "2. Шахеды и «Ланцеты»", "chaos": "3. Хаос",
            "far": "4. Издалека", "nuke": "5. Ядерный удар"}


def incoming_version(meta: dict) -> str | None:
    """Версия Incoming записи (мод того дня в разборе; до 3.0 его id — airstrike)."""
    mods = meta.get("mods", {})
    return mods.get("incoming") or mods.get("airstrike")


def version_key(v: str) -> tuple:
    return tuple(int(x) if x.isdigit() else 0 for x in v.split("-")[0].split("."))


def select(a):
    picked: dict[str, list[Candidate]] = {k: [] for k in PREVIEWS}
    notes = json.loads(Path(a.notes).read_text()) if a.notes else {}
    for db in a.db:
        r = Replay(Path(db), a)
        v = incoming_version(r.meta)
        if v and version_key(v) < version_key(a.min_version):
            print(f"{db}: Incoming {v} старше {a.min_version} — пропущен")
            r.con.close()
            continue
        found = {k: getattr(r, k)() for k in PREVIEWS}
        annotate(r, [c for v in found.values() for c in v], notes, a.note_bonus)
        for k, v in found.items():
            picked[k] += v
        r.con.close()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    # главный повтор (--prefer) — первым, остальные дополняют, где в нём нет подходящего
    prefer = [x for x in a.prefer.split(",") if x]
    first = lambda c: not any(x in c.replay for x in prefer)  # noqa: E731
    result = {k: [c.json(i) for i, c in enumerate(sorted(v, key=lambda c: (first(c), -c.score))[:a.top])] for k, v in picked.items()}
    (out / "candidates.json").write_text(json.dumps(result, ensure_ascii=False, indent=1))
    lines = ["# Кандидаты моментов", "", f"Базы: {', '.join(Path(d).parent.name for d in a.db)}. Кадр {a.fov:.0f}° по вертикали, "
             f"стороны {a.aspect:.4g}.", ""]
    for k, title in PREVIEWS.items():
        lines += [f"## {title}", "", "| id | повтор | время | что | планы |", "|---|---|---|---|---|"]
        for c in result[k]:
            plans = "; ".join(f"{s['name']}: {s['camera']['kind']} ({s['why']})" for s in c["shots"])
            lines.append(f"| {c['id']} | {c['replay']} | {c['at']} | {c['summary']} | {plans} |")
        lines.append("")
    (out / "candidates.md").write_text("\n".join(lines))
    print(f"{out / 'candidates.md'}: " + ", ".join(f"{k} {len(v)}" for k, v in result.items()))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("db", nargs="+", help="базы replaydb (replay.duckdb)")
    p.add_argument("--out", default="clips")
    p.add_argument("--top", type=int, default=8, help="кандидатов на ролик")
    p.add_argument("--fov", type=float, default=70, help="угол обзора по вертикали у игроков, градусы")
    p.add_argument("--aspect", type=float, default=9 / 16, help="ширина кадра к высоте (вертикальный ролик — 9:16)")
    p.add_argument("--center", type=float, default=0.7, help="доля кадра для «в середине»")
    p.add_argument("--soft", type=int, default=2, help="луч чист, если листвы, стекла и воды на пути не больше стольких блоков")
    p.add_argument("--prefer", default="", help="повторы (часть имени, через запятую), чьи кандидаты идут первыми")
    p.add_argument("--notes", help="журнал игры: опоры часов повторов и отрезки с тем, что происходило (README.md)")
    p.add_argument("--note-bonus", type=float, default=1.0, help="прибавка к счёту кандидата в отрезке журнала")
    p.add_argument("--min-version", default="2.10.2",
                   help="записи старых версий Incoming не берутся: ролики показывают мод, каким он стал")
    select(p.parse_args())


if __name__ == "__main__":
    sys.exit(main())
