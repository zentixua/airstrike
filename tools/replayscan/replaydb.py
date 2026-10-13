#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["duckdb>=1.1,<2"]
# ///
"""База по разбору повтора (README.md): таблицы мода replayscan — в DuckDB, сверху — виды под выбор планов.

  uv run tools/replayscan/replaydb.py build <каталог разбора> [--db файл]   таблицы и виды (replay.duckdb рядом)
  uv run tools/replayscan/replaydb.py sql <db> "<запрос>"                  любой запрос, вывод — таблицей
  uv run tools/replayscan/replaydb.py report <db> [--out каталог]           кандидаты планов: пуски, пролёты, удары,
                                                                            хаос, дальние удары с тишиной до раската

Кадр задаётся углом обзора по вертикали и отношением сторон (--fov 70 --aspect 0.5625 — вертикальное 9:16): точка в
кадре, если её |cx| ≤ aspect·tan(fov/2) и |cy| ≤ tan(fov/2) (cx, cy — сдвиг в плоскости кадра на единицу глубины,
sight.csv); «в середине кадра» — то же с долей --center. Тик повтора — 1/20 с; время в отчёте — мм:сс от начала повтора.
"""
import argparse
import json
import math
import re
import sys
from pathlib import Path

import duckdb

SOUND = 17.15  # блоков за тик: скорость звука у Incoming (BlastSounds)

TABLES = {
    "events": "e BIGINT, t INTEGER, dim VARCHAR, kind VARCHAR, src VARCHAR, x DOUBLE, y DOUBLE, z DOUBLE, size DOUBLE, "
              "uuid VARCHAR, clear INTEGER, solid DOUBLE, pre INTEGER, data VARCHAR",
    "blocks": "t INTEGER, dim VARCHAR, x INTEGER, y INTEGER, z INTEGER, state VARCHAR",
    "far": "t INTEGER, dim VARCHAR, src VARCHAR, uuid VARCHAR, x DOUBLE, y DOUBLE, z DOUBLE, data VARCHAR",
    "tracks": "t INTEGER, eid INTEGER, x DOUBLE, y DOUBLE, z DOUBLE, yaw DOUBLE, pitch DOUBLE, vx DOUBLE, vy DOUBLE, vz DOUBLE, ground INTEGER",
    "entities": "eid INTEGER, id INTEGER, uuid VARCHAR, type VARCHAR, name VARCHAR, subject INTEGER, player INTEGER, dim VARCHAR, "
                "first INTEGER, last INTEGER, x0 DOUBLE, y0 DOUBLE, z0 DOUBLE, x1 DOUBLE, y1 DOUBLE, z1 DOUBLE",
    "states": "t INTEGER, eid INTEGER, data VARCHAR",
    "sight": "t INTEGER, viewer VARCHAR, kind VARCHAR, ref VARCHAR, dist DOUBLE, ahead DOUBLE, cx DOUBLE, cy DOUBLE, "
             "hard DOUBLE, soft INTEGER, unknown DOUBLE",
    "viewers": "t INTEGER, viewer VARCHAR, dim VARCHAR, x DOUBLE, y DOUBLE, z DOUBLE, yaw DOUBLE, pitch DOUBLE",
    "time": "t INTEGER, game_time BIGINT, day_time BIGINT",
}

# Виды: всё, что нужно планам, — запросами по таблицам; числа кадра — переменные (fov, aspect, center, every)
VIEWS = r"""
CREATE OR REPLACE MACRO tanv() AS tan(radians(getvariable('fov')) / 2);
CREATE OR REPLACE MACRO in_frame(cx, cy, k) AS cx IS NOT NULL AND cy IS NOT NULL
    AND abs(cy) <= k * tanv() AND abs(cx) <= k * tanv() * getvariable('aspect');
-- луч до цели чист: ни непрозрачного блока, ни неизвестного чанка, прозрачного с формой (листва, стекло, вода) — не
-- больше --soft блоков
CREATE OR REPLACE MACRO clear_ray(hard, unknown, soft) AS coalesce(hard, -1) < 0 AND coalesce(unknown, -1) < 0
    AND coalesce(soft, 0) <= getvariable('soft');
CREATE OR REPLACE MACRO mmss(t) AS printf('%02d:%04.1f', CAST(floor(t / 1200) AS INTEGER), (t % 1200) / 20.0);

-- оси камеры зрителя: взгляд f, вправо r, вверх u = r × f (как Scan.axes)
CREATE OR REPLACE VIEW eyes AS
WITH a AS (
  SELECT *, -sin(radians(yaw)) * cos(radians(pitch)) AS fx, -sin(radians(pitch)) AS fy, cos(radians(yaw)) * cos(radians(pitch)) AS fz,
         -cos(radians(yaw)) AS rx, -sin(radians(yaw)) AS rz
  FROM viewers)
SELECT *, -rz * fy AS ux, rz * fx - rx * fz AS uy, rx * fy AS uz FROM a;

-- взрывы, удары и перехваты: пакеты взрыва игры и пакеты модов с «blast», «intercept», «detonation», «impact» в id
CREATE OR REPLACE VIEW blasts AS
SELECT * FROM events
WHERE x IS NOT NULL AND (src = 'ClientboundExplodePacket'
   OR (kind = 'payload' AND regexp_matches(src, ':(blast|intercept|nuke_detonation|bunker_impact)$')));

-- снаряды и прочее, за чем следили (не игроки)
CREATE OR REPLACE VIEW subjects AS SELECT * FROM entities WHERE subject = 1;
"""


def connect(db: Path, a) -> duckdb.DuckDBPyConnection:
    con = duckdb.connect(str(db))
    con.execute(f"SET VARIABLE fov = {float(a.fov)}")
    con.execute(f"SET VARIABLE aspect = {float(a.aspect)}")
    con.execute(f"SET VARIABLE center = {float(a.center)}")
    con.execute(f"SET VARIABLE soft = {int(a.soft)}")
    return con


def build(a) -> Path:
    scan = Path(a.dir)
    # без meta.json (разбор оборвался, не дописав её) — таблицы как есть, разбор не полный
    meta_file = scan / "meta.json"
    meta = json.loads(meta_file.read_text()) if meta_file.exists() else {"complete": False}
    db = Path(a.db) if a.db else scan / "replay.duckdb"
    if db.exists():
        db.unlink()
    con = connect(db, a)
    for name, columns in TABLES.items():
        cols = "{" + ", ".join(f"'{c.split()[0]}': '{' '.join(c.split()[1:])}'" for c in columns.split(", ")) + "}"
        con.execute(f"CREATE TABLE {name} AS SELECT * FROM read_csv('{scan / (name + '.csv.gz')}', header=true, columns={cols}, "
                    f"quote='\"', escape='\"', max_line_size=10000000, ignore_errors=true)")
    con.execute("CREATE TABLE meta AS SELECT ? AS json", [json.dumps(meta)])
    con.execute(f"SET VARIABLE every = {int(meta.get('every', 2))}")
    con.execute(VIEWS)
    impacts(con)
    con.close()
    print(f"{db}: {', '.join(f'{n} {c}' for n, c in meta.get('rows', {}).items())}; complete={meta.get('complete')}")
    return db


# вторичные подрывы удара: взрывы игры (без пакета мода) не дальше и не позже этого от пакета мода — его ступени
# (у крылатой ракеты Incoming — до 10,6 блока и 9 тиков от точки удара)
STAGE_REACH, STAGE_TICKS = 12, 10
# грунт в точке по серверу: Incoming кладёт в пакет взрыва «material» — из чего блок в точке удара или под ней;
# 8 — гравий, им же сервер отвечает за воздух, за блоки без своего грунта и за неготовый чанк
MATERIAL_SRC, MATERIAL_UNSURE = r"^(airstrike|incoming):(blast|bunker_impact)$", 8


def material(data: str) -> int | None:
    try:
        m = json.loads(data).get("material")
    except (ValueError, AttributeError):
        return None
    return m if isinstance(m, int) else None


def impacts(con):
    """Удары: взрывы одного удара (пакет мода и пакеты взрыва игры, ступени взрыва) — одной строкой, с родом места."""
    # в тике пакеты модов — раньше взрывов игры: ступени находят свой удар
    rows = con.execute("SELECT e, t, dim, src, x, y, z, coalesce(size, 0), uuid, clear, solid, pre, data FROM blasts "
                       "ORDER BY t, src = 'ClientboundExplodePacket', e").fetchall()
    groups: list[dict] = []
    for e, t, dim, src, x, y, z, size, uuid, clear, solid, pre, data in rows:
        g = None
        if src == "ClientboundExplodePacket":
            # ступень удара: ближайший пакет мода в STAGE_REACH блоках и STAGE_TICKS тиках
            near = [(d2, g) for g in groups if g["anchor"] and g["dim"] == dim and 0 <= t - g["t"] <= STAGE_TICKS
                    and (d2 := (x - g["x"]) ** 2 + (y - g["y"]) ** 2 + (z - g["z"]) ** 2) <= STAGE_REACH ** 2]
            g = min(near, key=lambda n: n[0])[1] if near else None
        if g is None:
            # снаряд взрыва (uuid пакета мода): разные снаряды залпа рядом — разные удары
            g = next((g for g in reversed(groups) if g["dim"] == dim and t - g["t1"] <= 3
                      and (x - g["x"]) ** 2 + (y - g["y"]) ** 2 + (z - g["z"]) ** 2 <= 36
                      and (uuid is None or g["uuid"] is None or uuid == g["uuid"])), None)
        if g is None:
            g = {"id": len(groups), "t": t, "t1": t, "dim": dim, "x": x, "y": y, "z": z, "events": [], "srcs": set(),
                 "power": 0.0, "uuid": None, "clear": None, "solid": None, "pre": 0, "anchor": False, "material": None}
            groups.append(g)
        if g["material"] is None and data and re.match(MATERIAL_SRC, src):
            g["material"] = material(data)
        g["t1"] = max(g["t1"], t)
        g["events"].append(e)
        g["srcs"].add(src)
        g["power"] = max(g["power"], size)
        # место удара — по пакету мода (точка попадания), иначе по первому взрыву
        if src != "ClientboundExplodePacket" and not g["anchor"]:
            g["x"], g["y"], g["z"], g["anchor"] = x, y, z, True
        g["uuid"] = g["uuid"] or uuid
        if clear is not None and clear >= 0 and (g["clear"] is None or pre > g["pre"] or (pre == g["pre"] and clear < g["clear"])):
            g["clear"], g["pre"] = clear, max(g["pre"], pre)
        if solid is not None and solid >= 0 and (g["solid"] is None or solid < g["solid"]):
            g["solid"] = solid
    con.execute("CREATE TABLE impacts (id INTEGER, t INTEGER, t1 INTEGER, dim VARCHAR, x DOUBLE, y DOUBLE, z DOUBLE, power DOUBLE, "
                "uuid VARCHAR, clear INTEGER, solid DOUBLE, pre INTEGER, material INTEGER, intercept BOOLEAN, srcs VARCHAR, events BIGINT[])")
    if groups:
        con.executemany("INSERT INTO impacts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        [(g["id"], g["t"], g["t1"], g["dim"], g["x"], g["y"], g["z"], g["power"], g["uuid"], g["clear"], g["solid"], g["pre"],
                          g["material"], any(s.endswith(":intercept") for s in g["srcs"]), ",".join(sorted(g["srcs"])), g["events"])
                         for g in groups])
    # воронка: блоки, ставшие воздухом в 6 блоках от места за 20 тиков после удара
    con.execute("""
        CREATE TABLE craters AS
        SELECT i.id, count(*) AS broken FROM impacts i JOIN blocks b
          ON b.dim = i.dim AND b.t BETWEEN i.t AND i.t1 + 20 AND b.state LIKE '%air'
         AND (b.x + 0.5 - i.x) ^ 2 + (b.y + 0.5 - i.y) ^ 2 + (b.z + 0.5 - i.z) ^ 2 <= 36
        GROUP BY i.id""")
    con.execute("""
        CREATE OR REPLACE VIEW impact_kinds AS
        SELECT i.*, coalesce(c.broken, 0) AS broken,
          CASE WHEN i.intercept THEN 'intercept'
               WHEN (i.solid IS NOT NULL AND i.solid <= 2.5) OR (i.clear IS NOT NULL AND i.clear <= 2) OR coalesce(c.broken, 0) > 0
                 OR coalesce(i.material, {UNSURE}) <> {UNSURE} THEN 'ground'
               WHEN i.solid IS NOT NULL AND i.clear IS NOT NULL THEN 'air'
               ELSE 'unknown' END AS place
        FROM impacts i LEFT JOIN craters c ON c.id = i.id""".replace("{UNSURE}", str(MATERIAL_UNSURE)))
    # удар глазами зрителя: геометрия по глазу тика удара, луч — лучший из лучей событий удара
    con.execute("""
        CREATE OR REPLACE VIEW impact_views AS
        WITH g AS (
          SELECT k.*, v.viewer, v.x AS ex, v.y AS ey, v.z AS ez,
                 (k.x - v.x) * v.fx + (k.y - v.y) * v.fy + (k.z - v.z) * v.fz AS ahead,
                 (k.x - v.x) * v.rx + (k.z - v.z) * v.rz AS side,
                 (k.x - v.x) * v.ux + (k.y - v.y) * v.uy + (k.z - v.z) * v.uz AS upv,
                 sqrt((k.x - v.x) ^ 2 + (k.y - v.y) ^ 2 + (k.z - v.z) ^ 2) AS dist
          FROM impact_kinds k JOIN eyes v ON v.t = k.t AND v.dim = k.dim),
        rays AS (
          SELECT i.id, s.viewer, bool_or(clear_ray(s.hard, s.unknown, s.soft)) AS clear, bool_or(s.unknown >= 0) AS unknown,
                 min(s.soft) AS soft
          FROM impacts i, unnest(i.events) AS u(e) JOIN sight s ON s.kind = 'p' AND s.ref = CAST(u.e AS VARCHAR)
          GROUP BY i.id, s.viewer)
        SELECT g.id, g.t, g.dim, g.x, g.y, g.z, g.place, g.broken, g.power, g.uuid, g.srcs, g.viewer, g.dist,
               CASE WHEN g.ahead > 0 THEN g.side / g.ahead END AS cx, CASE WHEN g.ahead > 0 THEN g.upv / g.ahead END AS cy,
               r.clear, r.unknown, r.soft, g.t + g.dist / {SOUND} AS heard
        FROM g LEFT JOIN rays r ON r.id = g.id AND r.viewer = g.viewer""".replace("{SOUND}", str(SOUND)))
    # пуски: первое появление снаряда (по UUID: вышедший из дальности и вернувшийся — новая сущность того же UUID)
    # почти без скорости (на пусковой) или рядом с пусковой; расстояние и видимость — глазами каждого зрителя:
    # samples — тики замеров (раз в every) первых 3 с, пока снаряд есть, seen — из них в середине кадра и не закрыт
    # (тик без строки видимости — вне кадра или дальше range: не видно)
    con.execute("""
        CREATE OR REPLACE VIEW launches AS
        WITH firsts AS (
          SELECT s.*, row_number() OVER (PARTITION BY s.uuid ORDER BY s.first) AS k FROM subjects s
          WHERE s.type NOT LIKE '%launcher%' AND s.type NOT LIKE '%booster%'),
        f AS (
          SELECT s.eid, s.uuid, s.type, s.dim, s.first, s.last, s.x0, s.y0, s.z0,
                 (SELECT max(sqrt(tr.vx ^ 2 + tr.vy ^ 2 + tr.vz ^ 2)) FROM tracks tr WHERE tr.eid = s.eid AND tr.t <= s.first + 3) AS v0,
                 (SELECT max(sqrt(tr.vx ^ 2 + tr.vy ^ 2 + tr.vz ^ 2)) FROM tracks tr WHERE tr.eid = s.eid AND tr.t BETWEEN s.first + 20 AND s.first + 60) AS v3,
                 (SELECT min(sqrt((l.x0 - s.x0) ^ 2 + (l.y0 - s.y0) ^ 2 + (l.z0 - s.z0) ^ 2)) FROM entities l
                   WHERE l.type LIKE '%launcher%' AND l.dim = s.dim AND l.first <= s.first AND l.last >= s.first) AS launcher
          FROM firsts s WHERE k = 1)
        SELECT f.*, v.viewer, sqrt((f.x0 - v.x) ^ 2 + (f.y0 - v.y) ^ 2 + (f.z0 - v.z) ^ 2) AS dist0,
          (SELECT count(*) FROM viewers w WHERE w.viewer = v.viewer AND w.dim = f.dim
             AND w.t BETWEEN f.first AND least(f.first + 60, f.last) AND w.t % getvariable('every') = 0) AS samples,
          (SELECT count(*) FROM sight s WHERE s.kind = 'e' AND s.ref = CAST(f.eid AS VARCHAR) AND s.viewer = v.viewer
             AND s.t BETWEEN f.first AND f.first + 60 AND in_frame(s.cx, s.cy, getvariable('center')) AND clear_ray(s.hard, s.unknown, s.soft)) AS seen
        FROM f JOIN eyes v ON v.t = f.first AND v.dim = f.dim
        WHERE coalesce(f.v0, 0) < 0.5 OR f.launcher <= 8""")
    # пролёты: сколько тиков снаряд в 10–60 блоках, в кадре и не закрыт
    con.execute("""
        CREATE OR REPLACE VIEW flybys AS
        SELECT s.viewer, e.eid, e.type, e.first, e.last, min(s.dist) AS closest, arg_min(s.t, s.dist) AS t_closest,
               count(*) FILTER (WHERE s.dist BETWEEN 10 AND 60 AND in_frame(s.cx, s.cy, 1) AND clear_ray(s.hard, s.unknown, s.soft)) * getvariable('every') AS seen_ticks,
               min(s.t) FILTER (WHERE s.dist BETWEEN 10 AND 60 AND in_frame(s.cx, s.cy, 1)) AS t_in
        FROM sight s JOIN subjects e ON s.kind = 'e' AND s.ref = CAST(e.eid AS VARCHAR)
        WHERE e.type NOT LIKE '%launcher%' AND e.type NOT LIKE '%booster%'
        GROUP BY s.viewer, e.eid, e.type, e.first, e.last""")
    # дальние снаряды (пакеты модов): геометрия по глазу того же тика
    con.execute("""
        CREATE OR REPLACE VIEW far_views AS
        WITH g AS (
          SELECT f.*, v.viewer, (f.x - v.x) * v.fx + (f.y - v.y) * v.fy + (f.z - v.z) * v.fz AS ahead,
                 (f.x - v.x) * v.rx + (f.z - v.z) * v.rz AS side,
                 (f.x - v.x) * v.ux + (f.y - v.y) * v.uy + (f.z - v.z) * v.uz AS upv,
                 sqrt((f.x - v.x) ^ 2 + (f.y - v.y) ^ 2 + (f.z - v.z) ^ 2) AS dist
          FROM far f JOIN eyes v ON v.t = f.t AND v.dim = f.dim)
        SELECT t, viewer, src, uuid, x, y, z, dist, CASE WHEN ahead > 0 THEN side / ahead END AS cx,
               CASE WHEN ahead > 0 THEN upv / ahead END AS cy, data FROM g""")


def table(con, query: str, params=None, limit: int = 0):
    cur = con.execute(query, params or [])
    cols = [d[0] for d in cur.description]
    rows = cur.fetchall()
    if limit:
        rows = rows[:limit]
    widths = [max(len(c), *(len(fmt(r[i])) for r in rows)) if rows else len(c) for i, c in enumerate(cols)]
    print("  ".join(c.ljust(w) for c, w in zip(cols, widths)))
    for r in rows:
        print("  ".join(fmt(v).ljust(w) for v, w in zip(r, widths)))
    return cols, rows


def fmt(v) -> str:
    if v is None:
        return ""
    if isinstance(v, float):
        return f"{v:.2f}" if math.isfinite(v) else str(v)
    return str(v)


def sql(a):
    con = connect(Path(a.db), a)
    con.execute(f"SET VARIABLE every = {int(json.loads(con.execute('SELECT json FROM meta').fetchone()[0]).get('every', 2))}")
    table(con, a.query)


REPORT = {
    "launches": """
        SELECT mmss(first) AS at, first AS t, viewer, type, round(dist0) AS dist, launcher, round(v0, 2) AS v0, round(v3, 2) AS v3,
               seen, samples, eid, uuid
        FROM launches
        WHERE dist0 BETWEEN 30 AND 200 AND samples > 0 AND seen >= 0.8 * samples
        ORDER BY seen DESC, first""",
    "flybys": """
        SELECT mmss(t_in) AS at, t_in AS t, viewer, type, round(closest) AS closest, mmss(t_closest) AS closest_at, seen_ticks, eid
        FROM flybys WHERE seen_ticks >= 30
        ORDER BY seen_ticks DESC""",
    "ground_impacts": """
        SELECT mmss(t) AS at, t, viewer, round(dist) AS dist, round(cx, 2) AS cx, round(cy, 2) AS cy, broken, srcs, id
        FROM impact_views
        WHERE place = 'ground' AND dist BETWEEN 40 AND 300 AND in_frame(cx, cy, 1) AND clear
        ORDER BY t""",
    "chaos": """
        WITH hits AS (SELECT viewer, t FROM impact_views WHERE place = 'ground' AND in_frame(cx, cy, 1) AND coalesce(clear, false)),
             loud AS (SELECT viewer, heard, in_frame(cx, cy, 1) AS shown FROM impact_views),
             w AS (SELECT DISTINCT viewer, (t // 20) * 20 AS start FROM hits)
        SELECT mmss(w.start) AS at, w.start AS t, w.viewer,
               (SELECT count(*) FROM hits h WHERE h.viewer = w.viewer AND h.t BETWEEN w.start AND w.start + 199) AS ground_in_frame,
               (SELECT count(*) FROM loud l WHERE l.viewer = w.viewer AND l.heard BETWEEN w.start AND w.start + 199 AND l.shown) AS loud_on,
               (SELECT count(*) FROM loud l WHERE l.viewer = w.viewer AND l.heard BETWEEN w.start AND w.start + 199 AND NOT l.shown) AS loud_off
        FROM w ORDER BY ground_in_frame DESC, loud_off LIMIT 40""",
    "far_quiet": """
        SELECT mmss(i.t) AS at, i.t, i.viewer, i.place, round(i.dist) AS dist, round(i.heard - i.t) AS boom_after,
               round(i.cx, 2) AS cx, round(i.cy, 2) AS cy, i.id,
               (SELECT count(*) FROM impact_views o WHERE o.viewer = i.viewer AND o.id <> i.id
                  AND o.heard BETWEEN i.t - 40 AND i.heard + 40 AND o.dist < 2 * i.dist) AS other_loud
        FROM impact_views i
        WHERE i.dist BETWEEN 300 AND 1500 AND in_frame(i.cx, i.cy, 1) AND i.place <> 'intercept'
        ORDER BY other_loud, i.t""",
}


def report(a):
    db = Path(a.db)
    con = connect(db, a)
    con.execute(f"SET VARIABLE every = {int(json.loads(con.execute('SELECT json FROM meta').fetchone()[0]).get('every', 2))}")
    out = Path(a.out) if a.out else db.parent / "report"
    out.mkdir(parents=True, exist_ok=True)
    for name, query in REPORT.items():
        print(f"\n== {name}")
        table(con, query, limit=a.limit)
        con.execute(f"COPY ({query}) TO '{out / (name + '.csv')}' (HEADER)")
    print(f"\n{out}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--fov", type=float, default=70, help="угол обзора по вертикали, градусы")
    p.add_argument("--aspect", type=float, default=16 / 9, help="ширина кадра к высоте (9:16 — 0.5625)")
    p.add_argument("--center", type=float, default=0.7, help="доля кадра для «в середине»")
    p.add_argument("--soft", type=int, default=2, help="луч чист, если прозрачного с формой на пути не больше стольких блоков")
    sub = p.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("dir")
    b.add_argument("--db")
    s = sub.add_parser("sql")
    s.add_argument("db")
    s.add_argument("query")
    r = sub.add_parser("report")
    r.add_argument("db")
    r.add_argument("--out")
    r.add_argument("--limit", type=int, default=25)
    a = p.parse_args()
    {"build": build, "sql": sql, "report": report}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
