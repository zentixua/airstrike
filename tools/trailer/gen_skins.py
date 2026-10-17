#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow"]
# ///
"""Скины людей трейлера Newisle (Артём 17.10: «все — Стив и Алекс»): у каждого бота свой скин по роли — солдаты Севера
и Юга в разной форме, Волков и его офицеры, жители в обычной одежде; соседи не похожи. Лица и волосы — девять скинов
Minecraft по умолчанию (из jar клиента, в репозиторий не кладутся), одежда рисуется здесь. Повтор их показывает
через подмену скина Flashback (FilePlayerSkin по UUID, ReplayFilm): записи не переснимаются.

    uv run tools/trailer/gen_skins.py --jar <neoforge-…-client-extra….jar> --out mod/run/newisle-skins

Пишет <out>/<имя>.png (64×64, широкая модель) и <out>/skins.json {имя: файл}. Имена — как у ботов дублей
(scenes/newisle-takes.json: приставка группы и номер, имена из names) и Артём в живых дублях (ARTEM_NAMES)."""
import argparse
import hashlib
import io
import json
import os
import random
import zipfile

from PIL import Image

FACES = ["steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"]

# роли групп: приставка имени бота → роль (бои: West/East — стороны fight, 0 — Юг, 1 — Север: T24 c2_fight_s/n)
GROUPS = {
    "Des": "desert", "Cit": "civil", "Und": "civil", "Str": "civil", "Civ": "civil2", "Hill": "civil2",
    "Crew": "south", "Mg": "north", "West": "south", "East": "north", "Roof": "south", "Off": "officer",
}
NAMED = {"Volkov": "volkov", "Artem": "artem"}
ARTEM_NAMES = ["Artem", "ZentixUA"]          # заглушка Артёма в дублях без него и сам он в живых дублях
COUNT = 40                                   # номеров на приставку: больше, чем в любой группе дублей

# ---------------------------------------------------------------- развёртка скина 64×64 (широкая модель)
# (x, y) угла развёртки части: у куба w×h×d грани — верх/низ над боками, бока — справа, перед, слева, зад
PARTS = {
    "body": ((16, 16), (8, 12, 4)), "body2": ((16, 32), (8, 12, 4)),
    "rarm": ((40, 16), (4, 12, 4)), "rarm2": ((40, 32), (4, 12, 4)),
    "larm": ((32, 48), (4, 12, 4)), "larm2": ((48, 48), (4, 12, 4)),
    "rleg": ((0, 16), (4, 12, 4)), "rleg2": ((0, 32), (4, 12, 4)),
    "lleg": ((16, 48), (4, 12, 4)), "lleg2": ((0, 48), (4, 12, 4)),
    "head": ((0, 0), (8, 8, 8)), "hat": ((32, 0), (8, 8, 8)),
}


def faces(part):
    """Грани части: {имя: (x, y, ширина, высота)}; бока — по кругу справа, перед, слева, зад (развёртка Minecraft)."""
    (x, y), (w, h, d) = PARTS[part]
    return {"top": (x + d, y, w, d), "bottom": (x + d + w, y, w, d),
            "right": (x, y + d, d, h), "front": (x + d, y + d, w, h),
            "left": (x + d + w, y + d, d, h), "back": (x + d + w + d, y + d, w, h)}


def sides(part):
    return [faces(part)[k] for k in ("right", "front", "left", "back")]


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (c[3] if len(c) > 3 else 255,)


class Skin:
    def __init__(self, face_png):
        self.im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        src = Image.open(io.BytesIO(face_png)).convert("RGBA")
        # голова и волосы над ней — от скина по умолчанию
        for part in ("head", "hat"):
            (x, y), _ = PARTS[part]
            self.im.paste(src.crop((x, y, x + 32, y + 16)), (x, y))

    def px(self, x, y, c):
        self.im.putpixel((x, y), c)

    def fill(self, part, color, rnd=None, noise=0.06):
        """Часть одним цветом с лёгкой зернистостью ткани; бока снизу чуть темнее (тень, как у скинов игроков)."""
        for name, (x, y, w, h) in faces(part).items():
            for j in range(h):
                for i in range(w):
                    k = 1.0
                    if rnd:
                        k += rnd.uniform(-noise, noise)
                    if name in ("right", "left", "back"):
                        k *= 0.9
                    if name == "bottom":
                        k *= 0.8
                    self.px(x + i, y + j, shade(color, k))

    def camo(self, part, palette, rnd):
        """Камуфляж пятнами: фон и пятна по клеткам 2×2 со случайным ростом."""
        base = rgb(palette[0])
        self.fill(part, base, rnd, 0.04)
        for name, (x, y, w, h) in faces(part).items():
            for _ in range(max(1, w * h // 10)):
                c = rgb(rnd.choice(palette[1:]))
                cx, cy = rnd.randrange(w), rnd.randrange(h)
                for _ in range(rnd.randint(2, 5)):
                    for dy in (0, 1):
                        for dx in (0, 1):
                            if 0 <= cx + dx < w and 0 <= cy + dy < h:
                                k = 0.9 if name in ("right", "left", "back") else 1.0
                                self.px(x + cx + dx, y + cy + dy, shade(c, k * rnd.uniform(0.95, 1.05)))
                    cx += rnd.choice((-1, 0, 1)) * 2
                    cy += rnd.choice((-1, 0, 1)) * 2

    def rows(self, part, frm, to, color, rnd=None):
        """Полоса по бокам части с ряда frm по to (сапоги, ремень, перчатки, манжеты)."""
        for x, y, w, h in sides(part):
            for j in range(max(0, frm), min(h, to)):
                for i in range(w):
                    self.px(x + i, y + j, shade(color, 1.0 + (rnd.uniform(-0.05, 0.05) if rnd else 0)))
        if to >= 12:
            x, y, w, h = faces(part)["bottom"]
            for j in range(h):
                for i in range(w):
                    self.px(x + i, y + j, shade(color, 0.8))

    def clear(self, part):
        (x, y), (w, h, d) = PARTS[part]
        for j in range(y, y + d + h):
            for i in range(x, x + 2 * (d + w)):
                self.im.putpixel((i, j), (0, 0, 0, 0))

    def helmet(self, color, rnd):
        """Каска на слое шляпы: верх и три верхних ряда боков, край темнее."""
        self.clear("hat")
        f = faces("hat")
        x, y, w, h = f["top"]
        for j in range(h):
            for i in range(w):
                self.px(x + i, y + j, shade(color, rnd.uniform(0.92, 1.05)))
        for x, y, w, h in sides("hat"):
            for j in range(3):
                for i in range(w):
                    self.px(x + i, y + j, shade(color, (0.8 if j == 2 else 1.0) * rnd.uniform(0.92, 1.05)))

    def cap(self, crown, band, visor, badge=None):
        """Фуражка: тулья, околыш, козырёк над лицом, кокарда спереди."""
        self.clear("hat")
        f = faces("hat")
        x, y, w, h = f["top"]
        for j in range(h):
            for i in range(w):
                self.px(x + i, y + j, crown)
        for name, (x, y, w, h) in zip(("right", "front", "left", "back"), sides("hat")):
            for i in range(w):
                self.px(x + i, y, shade(crown, 0.95))
                self.px(x + i, y + 1, band)
            if name == "front":
                for i in range(w):
                    self.px(x + i, y + 2, visor)
                if badge:
                    self.px(x + w // 2 - 1, y + 1, badge)
                    self.px(x + w // 2, y + 1, badge)

    def scarf(self, color, rnd):
        """Платок на голове: верх, бока и затылок до плеч, лицо открыто."""
        self.clear("hat")
        f = faces("hat")
        x, y, w, h = f["top"]
        for j in range(h):
            for i in range(w):
                self.px(x + i, y + j, shade(color, rnd.uniform(0.94, 1.04)))
        for name, (x, y, w, h) in zip(("right", "front", "left", "back"), sides("hat")):
            for j in range(h):
                for i in range(w):
                    if name == "front" and (j >= 2 and 1 <= i <= 6):
                        continue
                    if name in ("right", "left") and j >= 2 and (i >= 5 if name == "right" else i <= 2):
                        continue
                    self.px(x + i, y + j, shade(color, rnd.uniform(0.9, 1.02)))

    def front_rect(self, part, x0, y0, x1, y1, color):
        x, y, w, h = faces(part)["front"]
        for j in range(y0, y1):
            for i in range(x0, x1):
                self.px(x + i, y + j, color)

    def back_rect(self, part, x0, y0, x1, y1, color):
        x, y, w, h = faces(part)["back"]
        for j in range(y0, y1):
            for i in range(x0, x1):
                self.px(x + i, y + j, color)

    def png(self):
        out = io.BytesIO()
        self.im.save(out, "PNG")
        return out.getvalue()


# ---------------------------------------------------------------- роли

CAMO = {
    # лес — Север: оливковый фон, тёмно-зелёные и коричневые пятна; синяя повязка
    "north": (["#56613a", "#2f3b22", "#3d3527", "#76794c", "#44502c"], "#2c5aa0"),
    # пустыня — Юг: песок, охра, светлый; красная повязка
    "south": (["#b49a6c", "#8c7552", "#d6c294", "#6f5c40", "#a38a5e"], "#a8322d"),
}
SHIRTS = ["#c0392b", "#2e86c1", "#f4d03f", "#ecf0f1", "#27ae60", "#8e44ad", "#e67e22", "#34495e", "#d35400",
          "#16a085", "#7f8c8d", "#f5b7b1", "#5d6d7e", "#a04000", "#1abc9c", "#2c3e50", "#d4ac0d", "#922b21"]
TROUSERS = ["#2c3e66", "#1b2631", "#4d5656", "#7e5109", "#3b3b3b", "#566573", "#283747", "#6e2c00", "#a9a9a9"]
SHOES = ["#1c1c1c", "#efefef", "#5b3a1e", "#7b241c", "#2e4053"]
DESERT = ["#e8dcc0", "#d9c7a0", "#f2efe6", "#b8a07a", "#cfd8dc", "#a3826c", "#e5d1b8", "#8d6e63", "#c7b299"]


def soldier(face, side, rnd, n):
    s = Skin(face)
    pal, band = CAMO[side]
    for p in ("body", "rarm", "larm", "rleg", "lleg"):
        s.camo(p, pal, rnd)
    for p in ("body2", "rarm2", "larm2", "rleg2", "lleg2"):
        s.clear(p)
    boots = rgb("#2a2420" if side == "north" else "#5c4a33")
    for leg in ("rleg", "lleg"):
        s.rows(leg, 9, 12, boots, rnd)
    s.rows("body", 8, 9, rgb("#2b2b2b"))                 # ремень
    # жилет с подсумками на слое куртки
    vest = shade(rgb(pal[1] if side == "north" else pal[3]), 1.0)
    s.front_rect("body2", 0, 1, 8, 8, vest)
    s.back_rect("body2", 0, 1, 8, 8, vest)
    for i in (1, 3, 5):
        s.front_rect("body2", i, 5, i + 2, 7, shade(vest, 0.8))
    # повязка стороны на правом плече
    for arm_face in sides("rarm"):
        ax, ay, aw, ah = arm_face
        for i in range(aw):
            s.px(ax + i, ay + 2, rgb(band))
    if n % 3 != 2:
        s.helmet(rgb(pal[0] if n % 2 else pal[4]), rnd)
    else:
        s.cap(rgb(pal[4]), rgb(pal[1]), shade(rgb(pal[1]), 0.8))
    if n % 4 == 1:                                      # перчатки
        for arm in ("rarm", "larm"):
            s.rows(arm, 10, 12, rgb("#222222"))
    return s


def officer(face, rnd, volkov=False):
    s = Skin(face)
    if volkov:
        coat, cap, band = rgb("#24262a"), rgb("#24262a"), rgb("#b22222")
    else:
        coat, cap, band = rgb("#5a5f3c"), rgb("#5a5f3c"), rgb("#2f4f2f")
    for p in ("body", "rarm", "larm"):
        s.fill(p, coat, rnd, 0.03)
    trousers = coat if volkov else rgb("#4c5133")
    for p in ("rleg", "lleg"):
        s.fill(p, trousers, rnd, 0.03)
        s.rows(p, 9, 12, rgb("#141414"))
    for p in ("body2", "rarm2", "larm2", "rleg2", "lleg2"):
        s.clear(p)
    gold = rgb("#d4af37")
    # погоны: верх рук золотом, у Волкова — шире
    for arm in ("rarm", "larm"):
        x, y, w, h = faces(arm)["top"]
        for i in range(w):
            for j in range(h if volkov else 2):
                s.px(x + i, y + j, gold)
    s.rows("body", 8, 9, rgb("#3a2a1a"))
    s.front_rect("body", 3, 0, 5, 12, shade(coat, 0.85))  # борт
    for j in (2, 5, 8):
        s.front_rect("body", 4, j, 5, j + 1, gold)          # пуговицы
    if volkov:
        # длинная шинель: полы на ногах до колен, планки орденов на груди
        for leg in ("rleg2", "lleg2"):
            for x, y, w, h in sides(leg):
                for j in range(0, 6):
                    for i in range(w):
                        s.px(x + i, y + j, shade(coat, 1.05))
        for i, c in enumerate(("#c0392b", "#f1c40f", "#2e86c1", "#c0392b", "#27ae60")):
            s.front_rect("body", 5 + i % 3, 2 + i // 3, 6 + i % 3, 3 + i // 3, rgb(c))
        s.cap(cap, band, rgb("#111111"), gold)
    else:
        s.cap(cap, band, rgb("#1a1a1a"), gold)
    return s


def civilian(face, rnd, n, desert=False, artem=False):
    s = Skin(face)
    if artem:
        shirt, trousers, shoes, style = rgb("#3b4a3f"), rgb("#22313f"), rgb("#efefef"), 2
    else:
        shirt = rgb(DESERT[n % len(DESERT)] if desert else SHIRTS[(n * 7) % len(SHIRTS)])
        trousers = rgb(TROUSERS[(n * 5 + 3) % len(TROUSERS)])
        shoes = rgb(SHOES[(n * 3 + 1) % len(SHOES)])
        style = n % 4                                   # 0 футболка, 1 рубашка с длинным рукавом, 2 куртка, 3 худи
    for p in ("body2", "rarm2", "larm2", "rleg2", "lleg2"):
        s.clear(p)
    s.fill("body", shirt, rnd, 0.05)
    for arm in ("rarm", "larm"):
        s.fill(arm, shirt, rnd, 0.05)
        if style == 0:
            s.rows(arm, 4, 12, s.im.getpixel((11, 13)))      # руки — тоном лица
    for leg in ("rleg", "lleg"):
        s.fill(leg, trousers, rnd, 0.05)
        s.rows(leg, 10, 12, shoes)
    if style == 1:
        s.front_rect("body", 3, 0, 5, 12, shade(shirt, 0.8))
    if style == 2:                                      # куртка нараспашку поверх рубашки
        jacket = rgb(["#3e2723", "#263238", "#1b5e20", "#4e342e", "#37474f"][n % 5]) if not artem else rgb("#2b2f33")
        for p in ("body", "rarm", "larm"):
            s.fill(p, jacket, rnd, 0.04)
        s.front_rect("body", 2, 0, 6, 12, shirt)
    if style == 3:                                      # худи: карман и шнурки
        s.front_rect("body", 2, 7, 6, 10, shade(shirt, 0.85))
        s.front_rect("body", 3, 0, 4, 3, rgb("#dddddd"))
        s.front_rect("body", 5, 0, 6, 3, rgb("#dddddd"))
    s.rows("body", 11, 12, shade(trousers, 0.9))
    if desert and n % 3 == 0:
        s.scarf(rgb(["#ecf0f1", "#c0392b", "#8d6e63", "#f5f5dc"][n % 4]), rnd)
    return s


def load_faces(jar):
    out = {}
    with zipfile.ZipFile(jar) as z:
        for f in FACES:
            data = z.read(f"assets/minecraft/textures/entity/player/wide/{f}.png")
            out[f] = data
    return out


def role_of(name):
    if name in NAMED:
        return NAMED[name]
    for p, r in GROUPS.items():
        if name.startswith(p) and name[len(p):].isdigit():
            return r
    return None


def names():
    out = list(NAMED) + ARTEM_NAMES
    for p in GROUPS:
        out += [f"{p}{i}" for i in range(COUNT)]
    return list(dict.fromkeys(out))


def make(name, faces_png):
    role = "artem" if name in ARTEM_NAMES else role_of(name)
    digits = "".join(c for c in name if c.isdigit())
    n = int(digits) if digits else 0
    rnd = random.Random(int(hashlib.sha1(name.encode()).hexdigest()[:8], 16))
    # лица соседей по номеру — разные: шаг 4 по девяти скинам обходит их все, сдвиг — по группе
    shift = sum(name.encode()) % 9 if not digits else sum(name[:-len(digits)].encode()) % 9
    face = faces_png[FACES[(n * 4 + shift) % 9]]
    if role in ("north", "south"):
        return soldier(face, role, rnd, n)
    if role in ("officer", "volkov"):
        return officer(faces_png["steve"] if role == "volkov" else face, rnd, volkov=role == "volkov")
    if role == "artem":
        return civilian(faces_png["kai"], rnd, 0, artem=True)
    return civilian(face, rnd, n + shift, desert=role == "desert")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--jar", required=True, help="jar ресурсов клиента Minecraft (скины по умолчанию)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    faces_png = load_faces(a.jar)
    os.makedirs(a.out, exist_ok=True)
    table = {}
    for name in names():
        path = os.path.join(a.out, f"{name}.png")
        with open(path, "wb") as f:
            f.write(make(name, faces_png).png())
        table[name] = os.path.abspath(path)
    with open(os.path.join(a.out, "skins.json"), "w") as f:
        json.dump(table, f, indent=1)
    print(f"{len(table)} скинов → {a.out}")


if __name__ == "__main__":
    main()
