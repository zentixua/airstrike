#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["duckdb>=1.1,<2"]
# ///
"""Отбор моментов для коротких роликов (README.md): по базам разбора повторов (tools/replayscan/replaydb.py) —
цепочки «пуск → полёт → удар по земле» и окна хаоса, дальние удары и ядерные подрывы, с камерой на каждый план, и
отрезки глазами живых игроков (только их камерой).

  uv run tools/clips/moments.py <база.duckdb>... [--out каталог] [--top 8] [--pov-top 15] [--notes журнал.json] [--at время]...
         [--fly 'at=25:02/25:40 from=x,y,z to=x,y,z ...']...

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
import bisect
import hashlib
import json
import math
import re
import sys
import uuid
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
  SELECT uuid, min(t) AS t_kill, arg_min(x, t) AS kx, arg_min(y, t) AS ky, arg_min(z, t) AS kz
  FROM impact_kinds WHERE intercept AND uuid IS NOT NULL GROUP BY uuid)
SELECT coalesce(n.uuid, f.uuid) AS uuid, coalesce(n.weapon, f.weapon) AS weapon, coalesce(n.dim, f.dim) AS dim,
       least(n.e_first, f.f_first) AS first, greatest(n.e_last, f.f_last) AS last,
       n.e_first, n.e_last, n.eid0, n.eids, f.f_first, f.f_last, coalesce(f.f_rows, 0) AS f_rows,
       coalesce(s.v0 < 0.5 OR s.launcher <= 8, false) AND n.e_first <= coalesce(f.f_first, n.e_first) AS launched,
       n.ex AS lx, n.ey AS ly, n.ez AS lz,
       k.id AS hit, k.t AS t_hit, k.place, k.x AS hx, k.y AS hy, k.z AS hz, k.power, k.broken,
       coalesce(known_ground(k.place, k.clear, k.solid, k.broken), false) AS ground,
       c.t_kill, c.kx, c.ky, c.kz
FROM ent n FULL OUTER JOIN fr f ON f.uuid = n.uuid
LEFT JOIN start s ON s.uuid = n.uuid
LEFT JOIN hitp k ON k.uuid = coalesce(n.uuid, f.uuid)
LEFT JOIN kill c ON c.uuid = coalesce(n.uuid, f.uuid);
"""

# Взгляд игроков: что из удара, пуска и пролёта попало в их кадр (кадр — переменные fov, aspect, center)
POV = {
    # удар по земле в кадре, луч чист, 40–300 блоков
    "impact": """
        SELECT viewer, id, t, dist, cx, cy, in_frame(cx, cy, getvariable('center')) AS centered, heard, x, y, z
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

# Сбитый снаряд глазами игроков: в кадре и не закрыт, в 8–200 блоках, в [t − 120, t] (t — сбит)
SHOT_DOWN = """
    SELECT viewer, count(*) * getvariable('every') AS seen, max(t) AS t_last, min(dist) AS closest FROM sight
    WHERE kind = 'e' AND list_contains(?, ref) AND t BETWEEN ? AND ? AND viewer NOT LIKE 'ask:%'
      AND dist BETWEEN 8 AND 200 AND in_frame(cx, cy, 1) AND clear_ray(hard, unknown, soft)
    GROUP BY viewer"""

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
    kill_pos: tuple | None = None


def flights(con) -> list[Flight]:
    con.execute(FLIGHTS)
    out = []
    for r in con.execute("""SELECT uuid, weapon, first, last, launched, lx, ly, lz, hit, t_hit, place, hx, hy, hz, ground, t_kill,
                                   f_rows, eids, kx, ky, kz FROM flights WHERE weapon IS NOT NULL ORDER BY first""").fetchall():
        uuid, weapon, first, last, launched, lx, ly, lz, hit, t_hit, place, hx, hy, hz, ground, t_kill, f_rows, eids, kx, ky, kz = r
        out.append(Flight(uuid, weapon, first, last, bool(launched), (lx, ly, lz) if launched else None, hit, t_hit, place,
                          (hx, hy, hz) if hit is not None else None, bool(ground), t_kill, f_rows or 0, list(eids or []),
                          (kx, ky, kz) if t_kill is not None else None))
    return out


def offline_uuid(name: str) -> str:
    """UUID игрока без входа Mojang, как у ванили (`UUIDUtil.createOfflinePlayerUUID`: `UUID.nameUUIDFromBytes`)."""
    h = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode()).digest())
    h[6] = h[6] & 0x0F | 0x30
    h[8] = h[8] & 0x3F | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


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


def timeline(x: str) -> float | None:
    """Тик по шкале Flashback: `25:02`, `25:02.5` или `1:02:03` (время от начала записи); не такое время — None."""
    m = re.fullmatch(r"(?:(\d+):)?(\d+):(\d\d(?:\.\d+)?)", x.strip())
    return None if not m else (int(m[1] or 0) * 3600 + int(m[2]) * 60 + float(m[3])) * TICK


def wrap(deg: float) -> float:
    """Угол в −180…180°."""
    return (deg + 180) % 360 - 180


def direction(yaw: float, pitch: float) -> tuple[float, float, float]:
    """Взгляд по курсу и наклону, как у игрока: курс 0 — юг, 90 — запад; наклон вниз — плюс."""
    y, p = math.radians(yaw), math.radians(pitch)
    return -math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p)


def frame(cam: tuple, tan_x: float, tan_y: float, p) -> tuple[float, float] | None:
    """Место точки p в кадре камеры (x, y, z, курс, наклон): доли полукадра вправо и вверх; за камерой — None
    (тот же кадр, что у вывода: Cameras.View)."""
    x, y, z, yaw, pitch = cam
    fwd = direction(yaw, pitch)
    yr = math.radians(yaw)
    right = (-math.cos(yr), 0.0, -math.sin(yr))
    up = (right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0])
    d = (p[0] - x, p[1] - y, p[2] - z)
    depth = sum(u * v for u, v in zip(d, fwd))
    if depth <= 0.1:
        return None
    return sum(u * v for u, v in zip(d, right)) / depth / tan_x, sum(u * v for u, v in zip(d, up)) / depth / tan_y


def bearing(eye, p) -> tuple[float, float, float]:
    """Курс на точку (как у игрока), угол вниз на неё и расстояние."""
    dx, dy, dz = p[0] - eye[0], p[1] - eye[1], p[2] - eye[2]
    flat = math.hypot(dx, dz)
    return math.degrees(math.atan2(-dx, dz)), math.degrees(math.atan2(-dy, flat)), math.hypot(flat, dy)


def in_view(eye, yaw: float, pitch: float, tan_x: float, tan_y: float, p, safe: float = 0.85) -> bool:
    """Точка в кадре с полями (доля полукадра safe, как у оценки камер съёмки: Cameras.SAFE)."""
    c = frame((*eye, yaw, pitch), tan_x, tan_y, p)
    return c is not None and abs(c[0]) <= safe and abs(c[1]) <= safe


def aim_flight(eye, rel: list[tuple], look: float, turn: float, tan_x: float, tan_y: float,
               focus: tuple[int, int] | None = None) -> tuple[float, float]:
    """Курс и наклон пролёта (постоянные): от look не дальше turn — туда, где в кадре больше ударов окна focus, потом
    ударов по земле, потом всех (при равенстве — ближе к look); наклон — медиана углов вниз на удары окна (без окна —
    все), до которых камера может повернуть, 2–30°. eye(t) — глаз в тик t, rel — (id, t, место, род, курс, угол вниз,
    расстояние)."""
    half_x = math.degrees(math.atan(tan_x))
    foc = [r for r in rel if focus and focus[0] <= r[1] <= focus[1]]
    sector = [r for r in (foc or rel) if abs(wrap(r[4] - look)) <= turn + half_x]
    pitch = min(30.0, max(2.0, sorted(r[5] for r in sector)[len(sector) // 2])) if sector else 10.0
    seen = lambda yaw, rs: sum(in_view(eye(r[1]), yaw, pitch, tan_x, tan_y, r[2]) for r in rs)  # noqa: E731
    ground = [r for r in rel if r[3] == "ground"]
    best = max(range(-int(turn), int(turn) + 1), key=lambda o: (seen(look + o, foc), seen(look + o, ground), seen(look + o, rel), -abs(o)))
    return look + best, pitch


def ground_points(eye, yaw: float, pitch: float, tan_x: float, tan_y: float, ground: float, near: float) -> list[tuple]:
    """Где лучи нижней половины кадра (нижний край и середина её, слева, посередине, справа) встречают землю на высоте
    ground не дальше near блоков от глаза: там у повтора должны быть чанки (иначе — рельеф Distant Horizons без воды и
    пещеры или пусто)."""
    fwd = direction(yaw, pitch)
    yr = math.radians(yaw)
    right = (-math.cos(yr), 0.0, -math.sin(yr))
    up = (right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0])
    out = []
    for sy in (-0.95, -0.5):
        for sx in (-0.9, 0.0, 0.9):
            d = [fwd[i] + right[i] * sx * tan_x + up[i] * sy * tan_y for i in range(3)]
            if d[1] >= 0 or eye[1] <= ground:
                continue
            k = (eye[1] - ground) / -d[1]
            if math.hypot(d[0] * k, d[2] * k) <= near:
                out.append((eye[0] + d[0] * k, ground, eye[2] + d[2] * k))
    return out


def fmt(p) -> str:
    return "(" + ", ".join(f"{v:.0f}" for v in p) + ")"


# Куда смотрит пролёт: курс как у игрока (0 — юг, 90 — запад)
LOOK = {"south": 0.0, "west": 90.0, "north": 180.0, "east": -90.0}


def fly_spec(spec: str) -> dict:
    """`--fly`: `at=<с>/<по> from=x,y,z to=x,y,z [to_at=<время>] [focus=<с>/<по>] [look=south|<курс>] [turn=25]
    [fov=70] [dim=minecraft:overworld] [reach=<блоков>] [ground=63]` — время по шкале Flashback; камера проходит `to` в
    `to_at` (по умолчанию — в конце) и летит дальше той же прямой; удары в окне `focus` — в кадре прежде остальных;
    `reach` — дальность прорисовки записавшего в блоках (по умолчанию — по обновлениям блоков в записи),
    `ground` — высота земли или воды под пролётом."""
    try:
        kv = dict(part.split("=", 1) for part in spec.split())
        first, _, last = kv.pop("at").partition("/")
        a, b = timeline(first), timeline(last)
        to_at = timeline(kv.pop("to_at")) if "to_at" in kv else b
        pts = [tuple(float(v) for v in kv.pop(k).split(",")) for k in ("from", "to")]
        look = kv.pop("look", "south")
        focus = [timeline(x) for x in kv.pop("focus").split("/")] if "focus" in kv else None
        out = {"a": round(a), "b": round(b), "from": pts[0], "to": pts[1], "to_at": round(to_at),
               "focus": (round(focus[0]), round(focus[-1])) if focus else None,
               "look": LOOK[look] if look in LOOK else float(look), "turn": float(kv.pop("turn", 25)),
               "fov": float(kv.pop("fov", 70)), "dim": kv.pop("dim", "minecraft:overworld"),
               "reach": float(kv.pop("reach")) if "reach" in kv else None, "ground": float(kv.pop("ground", 63)), "spec": spec}
    except (KeyError, ValueError, TypeError) as e:
        raise argparse.ArgumentTypeError(f"--fly {spec!r}: {e!r} (README.md)") from e
    if kv or len(out["from"]) != 3 or len(out["to"]) != 3 or not out["a"] < out["b"] or out["to_at"] <= out["a"]:
        raise argparse.ArgumentTypeError(f"--fly {spec!r}: лишние ключи {sorted(kv)} или не те время и точки (README.md)")
    return out


@dataclass
class Shot:
    """План ролика: что снимать (снаряд и событие), когда и чем (камера)."""
    name: str
    kind: str          # launch | flight | shootdown | impact | chaos | far | nuke | pov (отрезок глазами игрока) | fly (пролёт)
    a: int             # тики повтора
    b: int
    camera: dict       # {"kind": "pov", "viewer": "recorder" | "player:<ник>"} | {"kind": "auto", …} | {"kind": "path", "keys", …}
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


# Ролик не короче, с (превью — 20–35 с)
LEAST = 20


# род слитого плана — важнейшего из слитых
RANK = {"launch": 0, "flight": 1, "impact": 2, "shootdown": 3}


def distinct(shots: list[Shot]) -> list[Shot]:
    """Одни глаза подряд — один план: план глазами того же игрока, что начинается раньше конца прошлого, сливается с ним
    (имя — имена слитых через «+», род, камера и объект — важнейшего: сбитие, удар, пролёт, пуск; события — все), так
    одни и те же секунды не показываются дважды."""
    out: list[Shot] = []
    for s in shots:
        p = out[-1] if out else None
        if p and p.camera.get("kind") == "pov" and p.camera == s.camera and s.a < p.b:
            top = s if RANK.get(s.kind, 0) >= RANK.get(p.kind, 0) else p
            keys = (p.subject.get("keys") or []) + (s.subject.get("keys") or [])
            out[-1] = Shot(f"{p.name}+{s.name}", top.kind, min(p.a, s.a), max(p.b, s.b), top.camera, {**top.subject, "keys": keys},
                           "; ".join(x for x in (p.why, s.why) if x))
            continue
        out.append(s)
    return out


def fill(shots: list[Shot], least: float = LEAST) -> list[Shot]:
    """Ролик не короче least секунд: недостающее — плану удара или сбития (40 % до него, но не раньше конца прошлого
    плана и начала повтора, остальное — после), без них — концу последнего плана."""
    short = round(least * TICK) - sum(s.b - s.a for s in shots)
    if short <= 0 or not shots:
        return shots
    i = next((i for i in range(len(shots) - 1, -1, -1) if shots[i].kind in ("impact", "shootdown")), len(shots) - 1)
    s = shots[i]
    if s.kind in ("impact", "shootdown"):
        before = min(round(short * 0.4), max(0, s.a - shots[i - 1].b) if i > 0 else s.a)
        s.a -= before
        s.b += short - before
    else:
        s.b += short
    return shots


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
        self.views = {k: self.con.execute(q).fetchall() for k, q in POV.items()}
        self.loud = self.con.execute(LOUD).fetchall()
        self.star = [x for x in getattr(a, "star", "").split(",") if x]
        self.at = getattr(a, "at", None) or []
        self.fly_specs = getattr(a, "fly", None) or []
        self.watched: dict[str, list] = {}
        self.downs = [d for f in self.fl if (d := self.shot_down(f))]

    def shot_down(self, f: Flight):
        """Дрон, сбитый у игрока на глазах: (снаряд, тик, место, взгляд) или None. Сбит — зенитной ракетой (kill) или
        огнём игроков (взведённый дрон подрывается, где его настигли: взрыв в воздухе); зритель видел его не меньше 3 с
        из последних 6 с и в момент сбития; сперва зрители --star, потом кто видел дольше (все такие зрители —
        `watched[снаряд]`)."""
        if f.weapon not in ("shahed", "lancet") or f.ground or not f.eids:
            return None
        if f.killed is not None and f.kill_pos:
            t, pos = f.killed, f.kill_pos
        elif f.place == "air" and f.t_hit is not None:
            t, pos = f.t_hit, f.pos
        else:
            return None
        rows = [r for r in self.con.execute(SHOT_DOWN, [[str(e) for e in f.eids], t - 120, t]).fetchall()
                if r[1] >= 3 * TICK and r[2] >= t - 10]
        rows.sort(key=lambda r: (r[0] not in self.star, -r[1], r[3]))
        self.watched[f.uuid] = rows
        return (f, t, pos, rows[0]) if rows else None

    def quiet(self, viewer: str, a: int, b: int) -> tuple[int, int]:
        """Громких событий, дошедших до зрителя в [a, b]: в кадре и за кадром."""
        on = off = 0
        for v, _, _, heard, _, shown in self.loud:
            if v == viewer and a <= heard <= b:
                on, off = (on + 1, off) if shown else (on, off + 1)
        return on, off

    def best_pov(self, kind: str, ref, near: int, slack: int = 40):
        """Лучший взгляд игрока на событие: удар (id), пуск или пролёт (uuid) около тика near."""
        rows = [r for r in self.views[kind] if r[1] == ref and abs(r[2] - near) <= slack]
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

    def shot_down_shot(self, name: str, down) -> Shot:
        """Сбитие глазами игрока: 6 с до него (дрон в кадре) и 3 с после."""
        f, t, pos, (viewer, seen, _, closest) = down
        return Shot(name, "shootdown", t - 120, t + 60, {"kind": "pov", "viewer": viewer},
                    {"uuid": f.uuid, "weapon": f.weapon, "size": SIZE.get(f.weapon, 4), "keys": [{"t": t, "pos": list(pos), "size": 8}]},
                    f"глазами {viewer}: {f.weapon} сбит в {closest:.0f} блоках, виден {seen / TICK:.1f} с")

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
            flyby = next((r for f in g for r in self.views["flyby"] if r[1] == f.uuid), None)
            if flyby:
                shots.append(self.flyby_shot("flight", self.by_uuid[flyby[1]], flyby))
            shots.append(self.impact_shot("impact", self.best_hit(hits)))
            fill(shots)
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
        """2 — шахеды и «Ланцеты»: пролёт рядом с игроком, сбитие у него на глазах и удары по земле рядом с ним."""
        out = []
        for g in self.salvos:
            if g[0].weapon not in ("shahed", "lancet"):
                continue
            ids = {f.uuid for f in g}
            hits = [f for f in g if f.ground and f.t_hit is not None]
            flybys = [r for f in g for r in self.views["flyby"] if r[1] == f.uuid]
            downs = sorted((d for d in self.downs if d[0].uuid in ids), key=lambda d: (d[3][0] not in self.star, -d[3][1]))
            if not (hits or downs) or not (flybys or downs):
                continue
            shots = []
            up = [x for x in g if x.launched]
            if up:
                shots.append(self.launch_shot("launch", min(up, key=lambda x: x.first)))
            row = min(flybys, key=lambda r: (r[3], -r[4])) if flybys else None
            if row:
                shots.append(self.flyby_shot("flyby", self.by_uuid[row[1]], row))
            if downs:
                shots.append(self.shot_down_shot("shootdown", downs[0]))
            if hits:
                t0 = row[2] if row else downs[0][1]
                shots.append(self.impact_shot("impact", self.best_hit([h for h in hits if h.t_hit >= t0] or hits)))
            # после пуска — пролёт, сбитие и удар в том порядке, в каком кончились в игре
            k = 1 if up else 0
            shots[k:] = sorted(shots[k:], key=lambda s: s.b)
            shots = distinct(shots)
            fill(shots)
            star = bool(downs) and downs[0][3][0] in self.star
            row = row if any("flyby" in s.name.split("+") for s in shots) else None
            score = (min(len(hits), 6) / 6 * 2 + (min(row[4], 80) / 80 * 2 + (60 - min(row[3], 60)) / 60 if row else 0)
                     + (shots[-1].camera["kind"] == "pov") + 0.5 * bool(up) + 2 * bool(downs) + 4 * star)
            t = row[2] if row else downs[0][1]
            what = [f"пролёт в {row[3]:.0f} блоках у {row[0]} {mmss(row[2])}"] if row else []
            if downs:
                what.append(f"сбит у {downs[0][3][0]} {mmss(downs[0][1])}")
            what.append(f"{len(hits)} по земле")
            out.append(Candidate("drones", self.name, str(self.db), score, t, g[0].weapon, f"{len(g)} × {g[0].weapon}: " + ", ".join(what),
                                 {"projectiles": len(g), "ground": len(hits), "closest": round(row[3]) if row else None,
                                  "flyby_s": row[4] / TICK if row else 0, "shot_down": len(downs)}, shots))
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
                           # сверху (60°) видны удары в улицах и траншеях, которые сбоку закрывают дома
                           "elev": [12, 25, 40, 60]},
                          {"keys": [key(h) for h in group], "centre": [cx, cy, cz], "spread": spread},
                          f"{n} ударов по земле за {window / TICK:.0f} с в круге {spread:.0f} блоков")]
            out.append(Candidate("chaos", self.name, str(self.db), n + (1 if len(weapons) > 1 else 0), t0, "+".join(weapons),
                                 f"{n} ударов по земле за {window / TICK:.0f} с с {mmss(t0)} ({', '.join(weapons)}), разброс {spread:.0f} блоков",
                                 {"ground": n, "spread": round(spread), "weapons": weapons, "seconds": window // TICK}, shots))
        # глазами игроков: окна, где ударов по земле в кадре больше всего, а громкого за кадром меньше, чем в кадре
        rows = self.views["impact"]
        weapon = {f.hit: f.weapon for f in self.fl if f.hit is not None}
        for viewer in sorted({r[0] for r in rows}):
            seen = sorted((r for r in rows if r[0] == viewer), key=lambda r: r[2])
            for i, first in enumerate(seen):
                t0 = first[2]
                group = [r for r in seen[i:] if r[2] - t0 < window]
                n = len(group)
                if n < 4:
                    continue
                on, off = self.quiet(viewer, t0, t0 + window)
                if off > on:
                    continue
                # глазами игрока — первыми: удары так, как их видел игрок
                out.append(Candidate("chaos", self.name, str(self.db), n + 2, t0, "pov",
                                     f"глазами {viewer}: {n} ударов по земле в кадре за {window / TICK:.0f} с с {mmss(t0)}, громких за кадром {off}",
                                     {"ground_in_frame": n, "loud_on": on, "loud_off": off, "seconds": window // TICK},
                                     [Shot("chaos", "chaos", t0 - 40, t0 + window + 40, {"kind": "pov", "viewer": viewer},
                                           {"keys": [{"t": r[2], "pos": [r[8], r[9], r[10]], "size": BLAST.get(weapon.get(r[1]), 16), "id": r[1]}
                                                     for r in group]},
                                           "окно хаоса глазами игрока")]))
        # широкий план и глаза игрока — каждый своим отбором: окно глазами игрока не уступает широкому рядом
        return dedupe([c for c in out if c.weapon != "pov"], 2 * window) + dedupe([c for c in out if c.weapon == "pov"], 2 * window)

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
            # и над крышами: за домом или в низине игрок удара не видел бы
            shot = Shot("far", "far", t - 120, int(heard) + 60,
                        {"kind": "auto", "rig": "lens", "eye": [ex, ey, ez], "fov": round(max(8.0, min(20.0, fov)), 1), "rise": [0, 24]},
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

    def fireball(self, t: int) -> float | None:
        """Наибольший радиус огненного шара подрыва в тик t, блоков (FireballModel.maxRadius: 0.07·Y^0.4 км воздушный,
        0.088·Y^0.4 км наземный, × масштаб эффектов) — по пакету подрыва; без мощности в нём — None."""
        r = self.con.execute("SELECT data FROM events WHERE src LIKE '%:nuke_detonation' AND t BETWEEN ? AND ? AND data IS NOT NULL "
                             "ORDER BY abs(t - ?) LIMIT 1", [t - 5, t + 5, t]).fetchone()
        num = lambda key: re.search(rf'"{key}"\s*:\s*(-?[\d.eE+-]+)', r[0]) if r else None  # noqa: E731
        y, scale = num("yieldKt"), num("scale")
        if not y:
            return None
        surface = bool(r and re.search(r'"surface"\s*:\s*true', r[0]))
        return (88 if surface else 70) * float(y[1]) ** 0.4 * (float(scale[1]) if scale else 1.0)

    def nuke(self) -> list[Candidate]:
        """5 — ядерный удар: подрыв, пуск МБР, если он в мире клиента, и игроки, которые смотрели."""
        out = []
        for hid, t, x, y, z, dim in self.con.execute(
                "SELECT id, t, x, y, z, dim FROM impact_kinds WHERE srcs LIKE '%nuke_detonation%' ORDER BY t").fetchall():
            icbm = [f for f in self.fl if f.weapon == "icbm" and f.first <= t <= f.last + 40]
            shots = []
            up = [f for f in icbm if f.launched]
            # ролик не длиннее 35 с: пуск 6 с, глазами игрока 12 с, свободная камера 15 с (та же вспышка с другой стороны)
            if up:
                shots.append(self.launch_shot("launch", up[0], after=90))
            views = self.con.execute("""SELECT viewer, dist, coalesce(in_frame(cx, cy, 1), false) FROM impact_views
                                        WHERE id = ? AND viewer NOT LIKE 'ask:%' ORDER BY 3 DESC, dist""", [hid]).fetchall()
            # место игрока у подрыва (рядом никого — записавшего): длинный объектив оттуда (подрыв бывает за чанками
            # повтора, место игрока — в них)
            eye = self.con.execute("""SELECT x, y, z FROM viewers WHERE viewer = ? AND dim = ? ORDER BY abs(t - ?) LIMIT 1""",
                                   [views[0][0] if views else "recorder", dim, t]).fetchone()
            # огненный шар над домами: в кадре достаточно, луча к земле не нужно
            seen = [v for v in views if v[2]]
            keys = [{"t": t, "pos": [x, y, z], "size": BLAST["icbm"], "id": hid}]
            if seen:
                shots.append(Shot("burst-pov", "nuke", t - 40, t + 200, {"kind": "pov", "viewer": seen[0][0]}, {"keys": keys},
                                  f"глазами {seen[0][0]}: подрыв в {seen[0][1]:.0f} блоках"))
            # свободная камера: объектив с места игрока, иначе штатив у подрыва
            cam = {"kind": "auto", "rig": "tripod", "dist": [400, 700, 1000], "elev": [4, 10], "share": 0.35}
            if eye:
                # на 2 блока к подрыву: в глазу камера была бы внутри головы игрока (его модель в выводе видна)
                flat = math.hypot(x - eye[0], z - eye[2]) or 1.0
                at = (eye[0] + 2 * (x - eye[0]) / flat, eye[1], eye[2] + 2 * (z - eye[2]) / flat)
                d = max(50.0, dist(at, (x, y, z)))
                r = self.fireball(t)
                if r:
                    # шар по мощности подрыва: в кадре (на 80 % его) — от земли под ним до двух радиусов над ним и 2.6
                    # радиуса в ширину; взгляд — в середину
                    tan = max(1.5 * r / (d * 0.8), 1.3 * r / (d * 0.8 * self.aspect))
                    fov, lift = math.degrees(2 * math.atan(tan)), 0.5 * r
                else:
                    # мощность неизвестна: шар — 35 % ширины кадра
                    fov, lift = math.degrees(2 * math.atan(BLAST["icbm"] / (2 * d * 0.35) / self.aspect)), 0.25 * BLAST["icbm"]
                # взгляд — в точку над подрывом, не за объектом: ключ подрыва к t+60 опускал бы кадр, пока гриб растёт
                # и над землёй под ним: игрок бывает в доме или метро, а сверху — горизонт
                cam["lens"] = {"eye": [round(v, 2) for v in at], "aim": [x, round(y + lift, 2), z],
                               "fov": round(max(12.0, min(80.0, fov)), 1), "rise": [0, 32, 96]}
                print(f"{self.name}: ядерный подрыв {mmss(t)}: шар до {f'{r:.0f} блоков' if r else '? (мощности в пакете нет)'}, "
                      f"объектив {cam['lens']['fov']:g}° с места игрока в {d:.0f} блоках")
            shots.append(Shot("burst", "nuke", t - 30, t + (270 if seen else 500), cam, {"keys": keys},
                              "свободная камера: вспышка, волна, гриб"))
            score = 3 + bool(up) + bool(seen) + 0.5 * len(views)
            out.append(Candidate("nuke", self.name, str(self.db), score, t, "icbm",
                                 f"подрыв {mmss(t)}" + (f", пуск МБР в мире {mmss(up[0].first)}" if up else "")
                                 + (f", глазами {seen[0][0]} в {seen[0][1]:.0f} блоках" if seen else ", никто не смотрел в его сторону"),
                                 {"viewers": len(views), "seen": len(seen), "launch": bool(up)}, shots))
        return out


    # Глазами игрока — что делает момент (вес): ядерный подрыв, сбитие дрона, удар по земле в кадре (в середине кадра
    # и ближе 120 блоков — больше), пуск и пролёт рядом; сколько тиков отрезка нужно после события
    WEIGHT = {"nuke": 6, "shootdown": 4, "impact": 1, "launch": 1.5, "flyby": 1}
    AFTER = {"nuke": 200}
    # Отмеченный записавшим момент — тиков до и после отметки: маркер Flashback жмут сразу после момента, время по часам
    # называет сам момент; ядерный подрыв за NUKE_BEFORE тиков до маркера — отрезок со вспышки и не короче AFTER после
    # неё (волна идёт долго). Маркеры, которые Flashback ставит сам (смена измерения), — не отметки игрока
    MARK = {"marker": (300, 40), "at": (200, 100)}
    NUKE_SEEN = """
        SELECT DISTINCT v.viewer FROM impact_views v WHERE v.id = ? AND in_frame(v.cx, v.cy, 1) AND NOT coalesce((
          SELECT bool_and(coalesce(s.hard, -1) BETWEEN 0 AND 16) FROM impacts i, unnest(i.events) AS u(e)
          JOIN sight s ON s.kind = 'p' AND s.ref = CAST(u.e AS VARCHAR) WHERE i.id = v.id AND s.viewer = v.viewer), false)"""
    NUKE_BEFORE = 1200
    AUTO_MARKERS = {"Changed Dimension"}

    def marks(self) -> list[tuple[int, int, str]]:
        """Моменты, которые отметил записавший: (начало, конец, откуда) — маркеры Flashback (`markers` в метаданных
        записи, тик → маркер) и время `--at`: по шкале Flashback (`25:02`) или по часам (`2026-10-14T19:05:30`; часы
        записи — по времени сохранения в имени файла, это её конец); одно время — момент, `…/…` — с и по."""
        total = self.meta.get("total_ticks") or 0
        nukes = [t for (t,) in self.con.execute("SELECT t FROM impact_kinds WHERE srcs LIKE '%nuke_detonation%'").fetchall()]
        out = []
        for key, marker in self.meta.get("replay_meta", {}).get("markers", {}).items():
            if (marker or {}).get("description") in self.AUTO_MARKERS:
                continue
            m = int(key)
            before, after = self.MARK["marker"]
            flash = [t for t in nukes if m - self.NUKE_BEFORE <= t <= m]
            out.append((min([m - before] + [t - 3 * TICK for t in flash]), max([m + after] + [t + self.AFTER["nuke"] for t in flash]),
                        f"маркер {mmss(m)}"))
        anchors = None
        for spec in self.at:
            first, _, last = spec.partition("/")
            ends = [timeline(x) for x in (first, last or first)]
            if None in ends:
                anchors = clock(self, [{"end": True, "at": "name"}]) if anchors is None else anchors
                if not anchors:
                    continue
                ends = [tick_at(anchors, datetime.fromisoformat(x)) if t is None else t for x, t in zip((first, last or first), ends)]
            t0, t1 = ends
            if not last:
                # момент: до и после него; ядерный подрыв в этом отрезке — и не меньше AFTER после вспышки
                before, after = self.MARK["at"]
                t0, t1 = t0 - before, t1 + after
                t1 = max([t1] + [t + self.AFTER["nuke"] for t in nukes if t0 <= t <= t1])
            if t1 > 0 and t0 < total:
                out.append((round(t0), round(t1), f"время {spec}"))
        return sorted((max(0, a), min(total, b), why) for a, b, why in out)

    def human(self, viewer: str) -> bool:
        """Зритель — живой игрок: записавший, аккаунт Mojang (UUID версии 4) или игрок сервера без входа Mojang (UUID
        ванили по имени, `OfflinePlayer:<ник>`); боты (Almighty и других модов) — свои UUID."""
        if viewer == "recorder":
            return True
        if not viewer.startswith("player:"):
            return False
        name = viewer[7:]
        r = self.con.execute("SELECT uuid FROM entities WHERE player = 1 AND name = ? LIMIT 1", [name]).fetchone()
        return bool(r and r[0]) and (r[0][14:15] == "4" or r[0] == offline_uuid(name))

    def pov_events(self) -> dict[str, list[tuple]]:
        """Что видели живые игроки: зритель → [(тик, вес, род, оружие, событие для проверки кадров или None, снаряд)]."""
        weapon = {f.hit: f.weapon for f in self.fl if f.hit is not None}
        nukes = self.con.execute("SELECT id, t, x, y, z FROM impact_kinds WHERE srcs LIKE '%nuke_detonation%' ORDER BY t").fetchall()
        out: dict[str, list[tuple]] = {}
        add = lambda v, *e: out.setdefault(v, []).append(e)  # noqa: E731
        for v, hid, t, d, _, _, centered, _, x, y, z in self.views["impact"]:
            if any(hid == n[0] for n in nukes):
                continue  # подрыв — своим родом ниже
            w = weapon.get(hid, "")
            add(v, t, self.WEIGHT["impact"] + 0.5 * bool(centered) + 0.5 * (d <= 120), "impact", w,
                {"t": t, "pos": [x, y, z], "size": BLAST.get(w, 16), "id": hid}, None)
        # сбитие — каждому, кто его видел (не только тому, кто дольше: тот бывает ботом)
        for f, t, pos, _ in self.downs:
            for v, *_ in self.watched[f.uuid]:
                add(v, t, self.WEIGHT["shootdown"], "shootdown", f.weapon, {"t": t, "pos": list(pos), "size": 8}, f.uuid)
        # пролёт — с входа в кадр (кружащий «Ланцет» бывает в кадре недолго посреди долгого пролёта)
        for v, ref, t_in, closest, _, t_out in self.views["flyby"]:
            f = self.by_uuid.get(ref)
            add(v, min(t_in + 40, t_out), self.WEIGHT["flyby"] + 0.5 * (closest <= 30), "flyby", f.weapon if f else "", None, ref)
        for v, ref, t, *_ in self.views["launch"]:
            f = self.by_uuid.get(ref)
            add(v, t, self.WEIGHT["launch"], "launch", f.weapon if f else "", None, ref)
        for hid, t, x, y, z in nukes:
            # в кадре и не за стеной у самого игрока: в метро лицом к подрыву он видит стену (все лучи к событиям подрыва
            # упёрлись в блоки ближе 16 блоков); дома далеко на луче к земле не в счёт — шар встаёт над крышами; лучей нет
            # (дальше разбора) — в кадре значит видел
            for (v,) in self.con.execute(self.NUKE_SEEN, [hid]).fetchall():
                add(v, t, self.WEIGHT["nuke"], "nuke", "icbm", {"t": t, "pos": [x, y, z], "size": BLAST["icbm"], "id": hid}, None)
        return {v: sorted(e, key=lambda e: e[0]) for v, e in out.items() if self.human(v)}

    def pov(self, longest: int = 300, shortest: int = 200, lead: int = 60, tail: int = 80) -> list[Candidate]:
        """6 — глазами игроков: отрезки 10–15 с, где живой игрок видел больше всего (вес событий в отрезке), только
        камерой игрока — как он сам двигал взгляд; после ядерного подрыва — 10 с (волна и гриб). Из перекрывающихся
        отрезков (и разных игроков в одни секунды) — лучший; у края записи отрезок сдвигается, а не урезается.
        В записи с отмеченными моментами (`marks`) — только они, глазами записавшего, по порядку."""
        marked = self.marks()
        if marked:
            events = self.pov_events().get("recorder", [])
            return [self.pov_candidate("recorder", a, b, [e for e in events if a <= e[0] <= b], 1000 - k, why)
                    for k, (a, b, why) in enumerate(marked)]
        out = []
        total = self.meta.get("total_ticks") or 10 ** 9
        after = lambda e: self.AFTER.get(e[2], tail)  # noqa: E731
        for viewer, events in self.pov_events().items():
            for i, first in enumerate(events):
                # подряд, пока событие со своим хвостом входит в отрезок: событие внутри отрезка не пропускается
                group = []
                for e in events[i:]:
                    if e[0] - first[0] > longest - lead - after(e):
                        break
                    group.append(e)
                if not group:
                    continue
                a = first[0] - lead
                b = max(e[0] + after(e) for e in group)
                if b - a < shortest:
                    a -= (shortest - (b - a)) // 2
                    b = a + shortest
                if a < 0:
                    a, b = 0, b - a
                if b > total:
                    a, b = max(0, a - (b - total)), total
                out.append(self.pov_candidate(viewer, a, b, group, sum(e[1] for e in group)))
        return dedupe(out, longest)

    def pov_candidate(self, viewer: str, a: int, b: int, group: list[tuple], score: float, mark: str | None = None) -> Candidate:
        """Отрезок [a, b] глазами зрителя: события в нём (из pov_events) — ключи кадров, объект и числа подписи."""
        kinds = [e[2] for e in group]
        keys = [e[4] for e in group if e[4]]
        subject = next((e[5] for e in group if e[2] == "shootdown"), None) or next((e[5] for e in group if e[5]), None)
        weapons = sorted({e[3] for e in group if e[3]})
        n = {k: kinds.count(k) for k in ("impact", "shootdown", "nuke", "launch", "flyby")}
        what = [x for x in (mark, n["nuke"] and "ядерный подрыв", n["shootdown"] and f"сбитие × {n['shootdown']}",
                            n["impact"] and f"ударов по земле в кадре {n['impact']}", n["launch"] and f"пусков {n['launch']}",
                            n["flyby"] and f"пролётов рядом {n['flyby']}") if x]
        # ядерный подрыв в отрезке, которого зритель не видел (в метро, в доме): в подписи — как далеко
        off = [] if n["nuke"] else [round(dist(eye, (x, y, z))) for t, x, y, z, dim in self.con.execute(
            "SELECT t, x, y, z, dim FROM impact_kinds WHERE srcs LIKE '%nuke_detonation%' AND t BETWEEN ? AND ? ORDER BY t", [a, b]).fetchall()
            if (eye := self.con.execute("SELECT x, y, z FROM viewers WHERE viewer = ? AND dim = ? ORDER BY abs(t - ?) LIMIT 1",
                                        [viewer, dim, t]).fetchone())]
        if off:
            what.insert(1 if mark else 0, f"ядерный подрыв в {off[0]} блоках вне кадра")
        f = self.by_uuid.get(subject) if subject else None
        shot = Shot("pov", "pov", a, b, {"kind": "pov", "viewer": viewer},
                    {"keys": keys, **({"uuid": f.uuid, "weapon": f.weapon, "size": SIZE.get(f.weapon, 4)} if f else {})},
                    f"глазами {viewer}: " + ", ".join(what))
        return Candidate("pov", self.name, str(self.db), score, a, "+".join(weapons) or "pov",
                         f"глазами {viewer} {mmss(a)}–{mmss(b)}: " + ", ".join(what) + (f" ({', '.join(weapons)})" if weapons else ""),
                         {"viewer": viewer, "ground": n["impact"], "shot_down": n["shootdown"], "nuke": n["nuke"],
                          "launch": n["launch"], "flyby": n["flyby"], "seconds": round((b - a) / TICK, 1),
                          **({"nuke_off": off[0]} if off else {}), **({"mark": mark} if mark else {})}, [shot])

    # Чанки повтора — то, что запись прислала до тика (сервер повтора их не забывает, съёмка идёт с начала): всё, что было
    # не дальше прорисовки записавшего от него; её нет в разборе (пакет входа — только в снимке), поэтому — по
    # обновлениям блоков: сервер шлёт их только в чанки, которые клиент держит (99,9 % из них — не дальше)
    VIEW = """
        WITH r AS (SELECT t, dim, x, z FROM viewers WHERE viewer = 'recorder')
        SELECT count(*), quantile_cont(sqrt(power(floor(b.x / 16.0) - floor(r.x / 16.0), 2)
                                            + power(floor(b.z / 16.0) - floor(r.z / 16.0), 2)), 0.999)
        FROM blocks b ASOF JOIN r ON b.dim = r.dim AND b.t >= r.t"""
    # без обновлений блоков (меньше VIEW_ROWS) — 8 чанков: с запасом для обычной прорисовки
    VIEW_DEFAULT, VIEW_ROWS = 8, 200
    # Чанки нужны под глазом и там, где лучи нижней половины кадра встречают землю ближе FLY_NEAR блоков
    FLY_NEAR = 192.0
    # удары «у пути» — для вывода: когда там прилетало
    FLY_AROUND = 250.0
    # Пролёт не в чанках повтора: скорость пути (доля заданной) и сдвиг (блоков, шаг) — первые, при которых он в них;
    # сдвиг маленький: путь задан человеком (порт, улица), а дальше — уже другое время, не другое место
    FLY_SPEEDS = (1.0, 0.8, 0.65, 0.5, 0.4, 0.3)
    FLY_SHIFT, FLY_STEP = 64, 16

    def view_radius(self) -> tuple[int, str]:
        """Прорисовка записавшего в чанках и откуда она (по обновлениям блоков или по умолчанию)."""
        if not hasattr(self, "_view"):
            n, q = self.con.execute(self.VIEW).fetchone()
            self._view = (max(2, math.ceil(q)), f"по {n} обновлениям блоков") if n >= self.VIEW_ROWS and q is not None \
                else (self.VIEW_DEFAULT, "по умолчанию: обновлений блоков мало")
        return self._view

    def seen_chunks(self, dim: str, radius: int) -> dict[tuple[int, int], int]:
        """Чанк → первый тик, когда он у мира повтора есть: записавший был от него не дальше radius чанков."""
        key = (dim, radius)
        self._seen = getattr(self, "_seen", {})
        if key in self._seen:
            return self._seen[key]
        disk = [(dx, dz) for dx in range(-radius, radius + 1) for dz in range(-radius, radius + 1) if dx * dx + dz * dz <= radius * radius]
        rows = self.con.execute("SELECT t, x, z FROM viewers WHERE viewer = 'recorder' AND dim = ? ORDER BY t", [dim]).fetchall()
        first: dict[tuple[int, int], int] = {}
        fresh: dict[tuple[int, int], list] = {}
        prev = None
        for t, x, z in rows:
            c = (math.floor(x) >> 4, math.floor(z) >> 4)
            if c == prev:
                continue
            offs = disk
            if prev is not None and abs(c[0] - prev[0]) <= 2 and abs(c[1] - prev[1]) <= 2:
                d = (c[0] - prev[0], c[1] - prev[1])
                if d not in fresh:
                    old = {(dx - d[0], dz - d[1]) for dx, dz in disk}
                    fresh[d] = [o for o in disk if o not in old]
                offs = fresh[d]
            for dx, dz in offs:
                first.setdefault((c[0] + dx, c[1] + dz), t)
            prev = c
        self._seen[key] = first
        return first

    def fly(self) -> list[Candidate]:
        """7 — пролёт свободной камеры (`--fly`, время по шкале Flashback): глаз — по прямой из `from` через `to` (в
        `to_at`) и дальше; взгляд — постоянный (`aim_flight`): в сторону `look`, повёрнутый не дальше `turn` туда, где в
        кадре больше ударов окна `focus`, потом ударов по земле, потом всех; наклон — к ним. Чанки повтора — то, что
        запись прислала до тика (`seen_chunks`): под глазом и на земле в нижней половине кадра их нет — там рельеф
        Distant Horizons (у моря — дно и пещеры без воды) или пусто; тогда путь медленнее (вокруг середины окна `focus`)
        и чуть сдвинут, а не встаёт и так — то же место в другое время записи, где оно в чанках и ударов по земле в
        кадре больше всего. Удары в кадре — события плана, снаряды в кадре — в числах; кадр — в его тики, как у вывода."""
        out = []
        weapon = {f.hit: f.weapon for f in self.fl if f.hit is not None}
        for k, f in enumerate(self.fly_specs):
            a0, b0, total = f["a"], f["b"], self.meta.get("total_ticks") or 10 ** 9
            if a0 >= total:
                print(f"{self.name}: пролёт {f['spec']!r} после конца записи ({mmss(total)}) — не берётся")
                continue
            b0 = min(b0, total)
            p0, p1, span = f["from"], f["to"], f["to_at"] - f["a"]
            vel = [(p1[i] - p0[i]) / span for i in range(3)]
            mid = (f["focus"][0] + f["focus"][1]) / 2 if f.get("focus") else (a0 + b0) / 2
            centre = [p0[i] + vel[i] * (mid - a0) for i in range(3)]
            tan_y = math.tan(math.radians(f["fov"] / 2))
            tan_x = tan_y * self.aspect
            radius, source = (math.ceil(f["reach"] / 16), "задана") if f.get("reach") else self.view_radius()
            seen = self.seen_chunks(f["dim"], radius)
            allhits = self.con.execute("SELECT id, t, x, y, z, place FROM impact_kinds WHERE dim = ? ORDER BY t", [f["dim"]]).fetchall()
            hit_t = [r[1] for r in allhits]

            def path(speed: float, shift: tuple[float, float], moved: int = 0):
                return lambda t: (centre[0] + vel[0] * speed * (t - moved - mid) + shift[0], centre[1] + vel[1] * speed * (t - moved - mid),
                                  centre[2] + vel[2] * speed * (t - moved - mid) + shift[1])

            def aimed(eye, a: int, b: int, focus):
                hits = allhits[bisect.bisect_left(hit_t, a):bisect.bisect_right(hit_t, b)]
                rel = [(hid, t, (x, y, z), place, *bearing(eye(t), (x, y, z))) for hid, t, x, y, z, place in hits]
                yaw, pitch = aim_flight(eye, rel, f["look"], f["turn"], tan_x, tan_y, focus)
                framed = [r for r in rel if in_view(eye(r[1]), yaw, pitch, tan_x, tan_y, r[2])]
                return rel, yaw, pitch, framed

            def missing(eye, yaw: float | None, pitch: float, a: int, b: int, stop: bool = False) -> int:
                """Сколько проверок (раз в секунду) не в чанках повтора: глаз, а с курсом — и земля в нижней половине кадра."""
                n = 0
                for t in range(a, b + 1, TICK):
                    e = eye(t)
                    pts = [e] if yaw is None else [e, *ground_points(e, yaw, pitch, tan_x, tan_y, f["ground"], self.FLY_NEAR)]
                    if any(seen.get((math.floor(q[0]) >> 4, math.floor(q[2]) >> 4), total + 1) > t for q in pts):
                        n += 1
                        if stop:
                            return n
                return n

            def counts(framed, focus) -> tuple[int, int, int]:
                return (sum(bool(focus) and focus[0] <= r[1] <= focus[1] for r in framed), sum(r[3] == "ground" for r in framed), len(framed))

            focus0 = f.get("focus")
            speed, shift, moved = 1.0, (0.0, 0.0), 0
            eye = path(speed, shift)
            rel, yaw, pitch, shown = aimed(eye, a0, b0, focus0)
            lost = missing(eye, yaw, pitch, a0, b0)
            first_at = lambda q: seen.get((math.floor(q[0]) >> 4, math.floor(q[2]) >> 4))  # noqa: E731
            print(f"{self.name}: пролёт {mmss(a0)}–{mmss(b0)}: прорисовка записавшего {radius} чанков ({source}); чанки у from — "
                  + (f"с {mmss(first_at(p0))}" if first_at(p0) is not None else "ни разу") + ", у to — "
                  + (f"с {mmss(first_at(p1))}" if first_at(p1) is not None else "ни разу"))
            track = self.con.execute("SELECT t, x, z FROM viewers WHERE viewer = 'recorder' AND dim = ? ORDER BY t", [f["dim"]]).fetchall()
            for name, q in (("from", p0), ("to", p1)):
                if track:
                    t, x, z = min(track, key=lambda r: math.hypot(r[1] - q[0], r[2] - q[2]))
                    print(f"  записавший ближе всего к {name}: {mmss(t)}, {math.hypot(x - q[0], z - q[2]):.0f} блоков")
            # удары по земле у пути за всю запись, по минутам: когда там прилетало
            seg = (p1[0] - p0[0], p1[2] - p0[2])
            along = lambda x, z: min(1.0, max(0.0, ((x - p0[0]) * seg[0] + (z - p0[2]) * seg[1]) / max(1e-9, seg[0] ** 2 + seg[1] ** 2)))  # noqa: E731
            near = [t for _, t, x, _, z, place in allhits if place == "ground"
                    and math.hypot(x - p0[0] - seg[0] * along(x, z), z - p0[2] - seg[1] * along(x, z)) <= self.FLY_AROUND]
            by_min: dict[int, int] = {}
            for t in near:
                by_min[t // (60 * TICK)] = by_min.get(t // (60 * TICK), 0) + 1
            print(f"  удары по земле не дальше {self.FLY_AROUND:.0f} блоков от пути from–to за всю запись: "
                  + (", ".join(f"{m}:xx ×{n}" for m, n in sorted(by_min.items())) or "нет"))
            if lost:
                base = counts(shown, focus0)
                best = None
                grid = sorted(((x, z) for x in range(-self.FLY_SHIFT, self.FLY_SHIFT + 1, self.FLY_STEP)
                               for z in range(-self.FLY_SHIFT, self.FLY_SHIFT + 1, self.FLY_STEP)), key=lambda o: math.hypot(*o))
                for v in self.FLY_SPEEDS:
                    for o in grid:
                        e = path(v, o)
                        if missing(e, yaw, pitch, a0, b0, stop=True):
                            continue
                        r2, y2, p2, s2 = aimed(e, a0, b0, focus0)
                        if not missing(e, y2, p2, a0, b0, stop=True) and counts(s2, focus0)[0] >= base[0]:
                            best = (v, o, e, r2, y2, p2, s2)
                            break
                    if best:
                        break
                if best:
                    speed, shift, eye, rel, yaw, pitch, shown = best
                    print(f"  в чанках повтора не был в {lost} из {(b0 - a0) // TICK + 1} проверок: скорость ×{speed:g}, "
                          f"сдвиг ({shift[0]:+.0f}, {shift[1]:+.0f})")
                else:
                    # то же место в другое время: путь и окна — со сдвигом во времени, весь в чанках, ударов по земле в кадре больше
                    found = None
                    for dt in sorted(range(-a0, total - b0 + 1, TICK), key=abs):
                        e = path(1.0, (0.0, 0.0), dt)
                        if missing(e, None, 0, a0 + dt, b0 + dt, stop=True):
                            continue
                        foc = (focus0[0] + dt, focus0[1] + dt) if focus0 else None
                        r2, y2, p2, s2 = aimed(e, a0 + dt, b0 + dt, foc)
                        score = counts(s2, foc)[1:]
                        if score[0] and not missing(e, y2, p2, a0 + dt, b0 + dt, stop=True) and (found is None or score > found[0]):
                            found = (score, dt, e, r2, y2, p2, s2)
                    if found:
                        _, moved, eye, rel, yaw, pitch, shown = found
                        print(f"  в {mmss(a0)}–{mmss(b0)} не в чанках повтора ({lost} из {(b0 - a0) // TICK + 1} проверок), ни "
                              f"медленнее, ни сдвигом до {self.FLY_SHIFT} блоков: то же место в {mmss(a0 + moved)}–{mmss(b0 + moved)} "
                              f"({'+' if moved >= 0 else '−'}{mmss(abs(moved))}), там в чанках")
                    else:
                        print(f"  в {mmss(a0)}–{mmss(b0)} не в чанках повтора ({lost} из {(b0 - a0) // TICK + 1} проверок), и ни "
                              f"медленнее, ни сдвигом, ни в другое время с ударами по земле в кадре в них не встаёт: под камерой "
                              f"рельеф Distant Horizons")
            a, b = a0 + moved, b0 + moved
            focus = (focus0[0] + moved, focus0[1] + moved) if focus0 else None
            in_focus = [r for r in rel if focus and focus[0] <= r[1] <= focus[1]]

            def framed(t: float, pos) -> bool:
                return in_view(eye(t), yaw, pitch, tan_x, tan_y, pos)

            keys = [{"t": t, "pos": list(pos), "size": BLAST.get(weapon.get(hid, ""), 16), "id": hid} for hid, t, pos, *_ in shown]
            # снаряды в кадре: пути сущностей и дальние строки, раз в 10 тиков
            rows = self.con.execute("""
                SELECT e.uuid, k.t, k.x, k.y, k.z FROM tracks k JOIN subjects e ON e.eid = k.eid
                WHERE e.dim = ? AND k.t BETWEEN ? AND ? AND k.t % 10 = 0 AND e.type NOT LIKE '%launcher%'
                UNION ALL
                SELECT uuid, t, x, y, z FROM far WHERE src LIKE '%:far_flights' AND dim = ? AND t BETWEEN ? AND ? AND t % 10 = 0""",
                                    [f["dim"], a, b, f["dim"], a, b]).fetchall()
            flown = {u for u, t, x, y, z in rows if u in self.by_uuid and framed(t, (x, y, z))}
            weapons = sorted({self.by_uuid[u].weapon for u in flown} | {weapon[r[0]] for r in shown if r[0] in weapon})
            look = direction(yaw, pitch)
            path_cam = {"kind": "path", "fov": f["fov"], "dim": f["dim"],
                        "keys": [{"t": t, "eye": [round(v, 2) for v in eye(t)], "aim": [round(eye(t)[i] + 100 * look[i], 2) for i in range(3)]}
                                 for t in (a, b)]}
            ground = sum(r[3] == "ground" for r in shown)
            why = (f"свободная камера: {fmt(eye(a))} → {fmt(eye(b))}, курс {yaw:.0f}°, наклон {pitch:.0f}°; ударов в кадре {len(shown)} "
                   f"из {len(rel)} (по земле {ground}" + (f"; {mmss(focus[0])}–{mmss(focus[1])}: "
                   f"{sum(r in shown for r in in_focus)} из {len(in_focus)}" if focus else "") + f"), снарядов в кадре {len(flown)}")
            print(f"{self.name}: пролёт {mmss(a)}–{mmss(b)}: {why}")
            left = missing(eye, yaw, pitch, a, b)
            print(f"  в чанках повтора — {(b - a) // TICK + 1 - left} из {(b - a) // TICK + 1} проверок (глаз и земля нижней "
                  f"половины кадра ближе {self.FLY_NEAR:.0f} блоков)")
            for hid, t, pos, place, hyaw, _, d in rel:
                print(f"  удар {mmss(t)} {place:<9} {weapon.get(hid, '?'):<8} {d:6.0f} блоков, курс на него {hyaw:5.0f}°,"
                      f" {'в кадре' if any(r[0] == hid for r in shown) else 'вне кадра'}")
            if focus and not in_focus:
                print(f"  в окне {mmss(focus[0])}–{mmss(focus[1])} ударов нет")
            elif focus and not any(r in shown for r in in_focus):
                print(f"  в окне {mmss(focus[0])}–{mmss(focus[1])} ни одного удара в кадре — нужен другой look или turn")
            out.append(Candidate("fly", self.name, str(self.db), 1000 - k, a, "+".join(weapons) or "fly",
                                 f"пролёт {mmss(a)}–{mmss(b)}: {why}",
                                 {"seconds": round((b - a) / TICK, 1), "ground": ground, "impacts": len(shown), "flights": len(flown),
                                  "yaw": round(yaw, 1), "pitch": round(pitch, 1), "speed": speed, "shift": list(shift),
                                  "moved": moved, "covered": (b - a) // TICK + 1 - left, "checks": (b - a) // TICK + 1,
                                  "view": radius, "mark": f["spec"]},
                                 [Shot("fly", "fly", a, b, path_cam, {"keys": keys}, why)]))
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
            "far": "4. Издалека", "nuke": "5. Ядерный удар", "pov": "6. Глазами игроков", "fly": "7. Пролёт камеры"}


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
    # главный повтор (--prefer) — первым, остальные дополняют, где в нём нет подходящего; глазами игроков — лучшие из всех
    prefer = [x for x in a.prefer.split(",") if x]
    first = lambda c: c.preview != "pov" and not any(x in c.replay for x in prefer)  # noqa: E731
    result = {k: [c.json(i) for i, c in enumerate(sorted(v, key=lambda c: (first(c), -c.score))[:a.pov_top if k == "pov" else a.top])]
              for k, v in picked.items()}
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
    p.add_argument("--pov-top", type=int, default=15, help="кандидатов «глазами игроков» (каждый — свой ролик)")
    p.add_argument("--fov", type=float, default=70, help="угол обзора по вертикали у игроков, градусы")
    p.add_argument("--aspect", type=float, default=9 / 16, help="ширина кадра к высоте (вертикальный ролик — 9:16)")
    p.add_argument("--center", type=float, default=0.7, help="доля кадра для «в середине»")
    p.add_argument("--soft", type=int, default=2, help="луч чист, если листвы, стекла и воды на пути не больше стольких блоков")
    p.add_argument("--prefer", default="", help="повторы (часть имени, через запятую), чьи кандидаты идут первыми")
    p.add_argument("--star", default="", help="зрители (player:<ник>, через запятую), чьи сбития дронов идут первыми")
    p.add_argument("--notes", help="журнал игры: опоры часов повторов и отрезки с тем, что происходило (README.md)")
    p.add_argument("--note-bonus", type=float, default=1.0, help="прибавка к счёту кандидата в отрезке журнала")
    p.add_argument("--at", action="append", default=[],
                   help="момент глазами записавшего: по шкале Flashback 25:02 или по часам 2026-10-14T19:05:30 (или с/по "
                        "через /); запись с отмеченными моментами (и маркерами Flashback) даёт «глазами игроков» только их")
    p.add_argument("--fly", action="append", default=[], type=fly_spec,
                   help="пролёт свободной камеры: 'at=25:02/25:40 from=x,y,z to=x,y,z [to_at=25:32] [look=south] [turn=25] [fov=70]'")
    p.add_argument("--min-version", default="2.10.2",
                   help="записи старых версий Incoming не берутся: ролики показывают мод, каким он стал")
    a = p.parse_args()
    # время по шкале Flashback и пролёт — в своей записи: в другой (длиннее) то же время — не тот момент
    if len(a.db) > 1 and (a.fly or any(timeline(x) is not None for spec in a.at for x in spec.split("/"))):
        p.error("время по шкале Flashback (--at 25:02) и --fly — для одной записи: дайте одну базу")
    select(a)


if __name__ == "__main__":
    sys.exit(main())
