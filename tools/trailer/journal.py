#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow"]
# ///
"""Журнал плана (replays/<план>.events.jsonl) текстом: uv run tools/trailer/journal.py <журнал> [--region DIR] [--every N]
[--tail N] [--weapon W]. Печатает отметки, людей в начале плана, камеру сценария через N тиков (место, курс, наклон, угол кадра),
снаряды (появление, конец, удар) и каждый снаряд в окне плана относительно камеры: расстояние, высоту над камерой,
угол от оси кадра и в кадре ли он (поля 0.85, как у Rig.inFrame). --region — папка region мира части: тики, где
камера в блоках (как Rig.World.free: куб ±0,2 блока вокруг глаза), и блоки в углах куба, а у снаряда и удара — первый
блок на луче из камеры (за блоками ли он). Почему план не прошёл проверку вывода — по данным записи, без игры."""
import argparse, json, math, os, sys
sys.path.insert(0, os.path.dirname(__file__))

# блоки без формы столкновения (Rig.World.free их не считает): трава, цветы, факелы, таблички, вода
PASSABLE = ('air', 'light', 'short_grass', 'tall_grass', 'grass', 'fern', 'large_fern', 'dead_bush', 'torch', 'wall_torch',
            'sign', 'banner', 'button', 'pressure_plate', 'water', 'lava', 'vine', 'sapling', 'flower', 'tulip', 'poppy',
            'dandelion', 'orchid', 'allium', 'bluet', 'daisy', 'cornflower', 'lily_of_the_valley', 'rose_bush', 'peony',
            'lilac', 'sunflower', 'seagrass', 'kelp', 'sugar_cane', 'wheat', 'carrots', 'potatoes', 'beetroots', 'cobweb',
            'redstone_wire', 'rail', 'fire', 'tripwire', 'lever', 'glow_lichen', 'hanging_roots', 'moss_carpet_none')


# воздух вокруг глаза, как Rig.World.CLEAR
CLEAR = 0.2


def passable(name):
    n = name.split(':')[-1]
    return n.endswith('air') or any(n == p or n.endswith('_' + p) or p in n for p in PASSABLE)


def sight(region, a, b, near=1.5):
    """Первый блок с формой на отрезке камера → объект (без последних near блоков у объекта) или None."""
    from ray import block
    d = math.dist(a, b)
    if d <= near:
        return None
    v = [(b[i] - a[i]) / d for i in range(3)]
    last, t = None, 0.3
    while t < d - near:
        c = tuple(math.floor(a[i] + v[i] * t) for i in range(3))
        if c != last:
            last = c
            bl = block(region, *c)
            if bl == '?' or not passable(bl):
                return f'за {bl.split(":")[-1]}@{c[0]},{c[1]},{c[2]} в {t:.0f} бл'
        t += 0.2
    return None


def look(yaw, pitch):
    # как Vec3.directionFromRotation у Minecraft
    y, p = math.radians(yaw), math.radians(pitch)
    return (-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p))


def in_frame(cam, at, safe=0.85, aspect=16 / 9):
    x, y, z, yaw, pitch, roll, fov = cam
    f = look(yaw, pitch)
    d = (at[0] - x, at[1] - y, at[2] - z)
    zz = sum(d[i] * f[i] for i in range(3))
    if zz <= 0.1:
        return 'за камерой'
    r = (-f[2], 0.0, f[0])  # f × (0, 1, 0), как в Rig.inFrame
    n = math.hypot(r[0], r[2]) or 1
    r = (r[0] / n, 0.0, r[2] / n)
    u = (r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
    ty = math.tan(math.radians(fov / 2))
    tx = ty * aspect
    sx = sum(d[i] * r[i] for i in range(3)) / zz
    sy = sum(d[i] * u[i] for i in range(3)) / zz
    if abs(sx) <= tx * safe and abs(sy) <= ty * safe:
        return 'в кадре'
    return 'выше кадра' if sy > ty * safe else 'ниже кадра' if sy < -ty * safe else 'сбоку'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('journal')
    ap.add_argument('--region', help='папка region мира, где план записан (копия части)')
    ap.add_argument('--every', type=int, default=5)
    ap.add_argument('--tail', type=int, default=100, help='тиков после отметки end')
    ap.add_argument('--weapon', help='только снаряды этого оружия (drone, missile, rocket…); none — без снарядов')
    a = ap.parse_args()
    cams, people, marks, flights, pos = {}, {}, [], {}, {}
    for line in open(a.journal, encoding='utf-8'):
        if not line.strip():
            continue
        e = json.loads(line)
        t = e['t']
        if 'cam' in e:
            cams[t] = e['cam']
        if 'p' in e and t not in people:
            people[t] = e['p']
        if 'mark' in e:
            marks.append((t, e['mark']))
        if 'new' in e:
            flights[e['new']] = {'weapon': e['weapon'], 'new': t}
        for k in ('impact', 'lost'):
            if k in e:
                flights.setdefault(e[k], {})[k] = (t, e['pos'])
        for f in e.get('f', []):
            pos.setdefault(f[0], {})[t] = (f[1], f[2], f[3], f[4] if len(f) > 4 else '')
    start = next((t for t, m in marks if m == 'start'), min(cams) if cams else 0)
    end = next((t for t, m in marks if m == 'end'), max(cams) if cams else 0)
    print(f'отметки: {marks}')
    print(f'окно {start}..{end}, хвост до {end + a.tail}')
    p0 = people.get(start) or (people[min(people)] if people else [])
    print('люди в начале:', ', '.join(f'{p[0]} {p[1]:.1f} {p[2]:.1f} {p[3]:.1f}' for p in p0))
    print('камера:')
    for t in sorted(cams):
        if start - a.every <= t <= end + a.tail and (t - start) % a.every == 0:
            c = cams[t]
            print(f'  {t:5d} {c[0]:9.2f} {c[1]:7.2f} {c[2]:9.2f}  курс {c[3]:7.1f} наклон {c[4]:6.1f} крен {c[5]:5.1f} кадр {c[6]:5.1f}')
    print('снаряды:')
    for i, f in sorted(flights.items()):
        ps = pos.get(i, {})
        if not ps or a.weapon and f.get('weapon') != a.weapon:
            continue
        t0, t1 = min(ps), max(ps)
        end_ = f.get('impact') or f.get('lost')
        kind = 'удар' if 'impact' in f else 'пропал' if 'lost' in f else 'летит'
        p = ps[t0]
        q = ps[t1]
        print(f'  #{i} {f.get("weapon")}: {t0} ({p[0]:.0f} {p[1]:.0f} {p[2]:.0f}) … {t1} ({q[0]:.0f} {q[1]:.0f} {q[2]:.0f}); {kind}'
              + (f' {end_[0]} у {end_[1][0]:.1f} {end_[1][1]:.1f} {end_[1][2]:.1f}' if end_ else ''))
        rows = []
        for t in range(start, end + a.tail + 1, a.every):
            if t not in ps or t not in cams:
                continue
            c, s = cams[t], ps[t]
            d = math.dist(c[:3], s[:3])
            seen = sight(a.region, c[:3], s[:3]) if a.region else None
            rows.append(f'{t}: {d:.0f} бл, выше камеры {s[1] - c[1]:+.0f}, {in_frame(c, s)}, фаза {s[3]}'
                        + (f', {seen}' if seen else ''))
        for r in rows:
            print('     ', r)
        if 'impact' in f:
            t, at = f['impact']
            ct = min(cams, key=lambda k: abs(k - t)) if cams else None
            if ct is not None and start - a.every <= t <= end + a.tail:
                c = cams[ct]
                seen = sight(a.region, c[:3], at) if a.region else None
                print(f'      удар {t} из камеры {ct}: {math.dist(c[:3], at):.0f} бл, {in_frame(c, at)}'
                      + (f', {seen or "луч чист"}' if a.region else ''))
    if a.region:
        from ray import block
        print(f'камера в блоках (куб ±{CLEAR} у глаза):')
        bad = 0
        for t in sorted(cams):
            if not start - 60 <= t <= end + a.tail:
                continue
            c = cams[t]
            hit = []
            for dx in (-CLEAR, CLEAR):
                for dy in (-CLEAR, CLEAR):
                    for dz in (-CLEAR, CLEAR):
                        x, y, z = math.floor(c[0] + dx), math.floor(c[1] + dy), math.floor(c[2] + dz)
                        b = block(a.region, x, y, z)
                        if b == '?' or not passable(b):
                            hit.append(f'{b.split(":")[-1]}@{x},{y},{z}')
            if hit:
                bad += 1
                if bad <= 40:
                    print(f'  {t:5d} {c[0]:.2f} {c[1]:.2f} {c[2]:.2f}: {", ".join(sorted(set(hit)))}')
        print(f'  тиков в блоках: {bad}')


if __name__ == '__main__':
    main()
