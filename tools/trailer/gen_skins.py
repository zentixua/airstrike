#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow"]
# ///
"""Скины людей трейлера Newisle (Артём 17.10: «все — Стив и Алекс», «скины, а не одежду»): у каждого бота свой полный
скин игрока — своё лицо (тон кожи, глаза, брови, нос, рот, борода), причёска и цвет волос, одежда по роли: солдаты
Севера и Юга в разной форме, Волков и его офицеры, жители в обычной одежде; соседи не похожи. Всё рисуется здесь,
чужих скинов нет. Повтор их показывает через подмену скина Flashback (FilePlayerSkin по UUID, ReplayFilm): записи не
переснимаются.

    uv run tools/trailer/gen_skins.py --out mod/run/newisle-skins

Пишет <out>/<имя>.png (64×64, широкая модель) и <out>/skins.json {имя: файл}. Имена — как у ботов дублей
(scenes/newisle-takes.json: приставка группы и номер, имена из names) и Артём в живых дублях (ARTEM_NAMES)."""
import argparse
import hashlib
import io
import json
import os
import random

from PIL import Image

TONES = ["#f2c9a6", "#e7b48c", "#d6a079", "#c48b5f", "#ad7650", "#93603e", "#76482c", "#5a3520"]
HAIR = ["#1d1814", "#3a2619", "#5b3a22", "#7d5734", "#c8a15c", "#9c4a22", "#8a8a8a", "#2e2a28"]
EYES = ["#4a2e1c", "#2b1d14", "#3a6ea5", "#4b7a3a", "#6b7b86", "#7a5a2a"]
STYLES = ["short", "buzz", "side", "long", "curly", "bald", "short", "bun"]
BEARDS = ["none", "stubble", "none", "mustache", "beard", "none", "stubble"]

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


class Look:
    """Внешность: тон кожи, волосы (цвет и причёска), глаза, борода — по номеру человека в группе, чтобы соседи были
    разными (шаги взаимно просты с длинами списков), и по случаю внутри."""

    def __init__(self, n, g, rnd, tone=None, hair=None, style=None, eyes=None, beard=None):
        self.tone = rgb(tone or TONES[(n * 3 + g) % len(TONES)])
        self.hair = rgb(hair or HAIR[(n * 5 + 2 * g) % len(HAIR)])
        self.style = style or STYLES[(n * 3 + g * 5 + 1) % len(STYLES)]
        self.eyes = rgb(eyes or EYES[(n * 5 + g) % len(EYES)])
        self.beard = beard or (BEARDS[(n * 4 + g) % len(BEARDS)] if self.style not in ("long", "bun") else "none")
        self.rnd = rnd


class Skin:
    def __init__(self, look):
        self.im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        self.look = look
        self.head(look)

    def head(self, lk):
        """Голова игрока: лицо 8×8 (лоб, брови, глаза белок и радужка, нос, рот, подбородок), бока с ушами, затылок,
        макушка и волосы по причёске; объём волос и бороды — на слое шляпы, как у скинов игроков."""
        rnd = lk.rnd
        tone, hair = lk.tone, lk.hair
        f = faces("head")

        def skin_px(x, y, k=1.0):
            self.px(x, y, shade(tone, k * rnd.uniform(0.96, 1.04)))

        def hair_px(x, y, k=1.0, layer=None):
            self.px(x, y, shade(hair, k * rnd.uniform(0.88, 1.08)))

        for name, (x, y, w, h) in f.items():
            for j in range(h):
                for i in range(w):
                    k = {"front": 1.0, "right": 0.92, "left": 0.92, "back": 0.9, "top": 1.0, "bottom": 0.8}[name]
                    skin_px(x + i, y + j, k)
        # лицо
        fx, fy, _, _ = f["front"]
        for j in range(8):
            skin_px(fx, fy + j, 0.93)
            skin_px(fx + 7, fy + j, 0.93)
        brow = shade(hair, 0.8) if sum(hair[:3]) < 400 else shade(rgb("#5b3a22"), 1.0)
        for i in (1, 2, 5, 6):
            self.px(fx + i, fy + 3, brow)
        white = rgb("#eeeeee")
        self.px(fx + 1, fy + 4, white)
        self.px(fx + 2, fy + 4, lk.eyes)
        self.px(fx + 5, fy + 4, lk.eyes)
        self.px(fx + 6, fy + 4, white)
        skin_px(fx + 3, fy + 5, 0.86)
        skin_px(fx + 4, fy + 5, 0.86)
        lip = (min(255, int(tone[0] * 0.8) + 18), int(tone[1] * 0.62), int(tone[2] * 0.6), 255)
        for i in (3, 4):
            self.px(fx + i, fy + 6, lip)
        if rnd.random() < 0.5:
            self.px(fx + 2, fy + 6, shade(tone, 0.85))
            self.px(fx + 5, fy + 6, shade(tone, 0.85))
        for i in range(1, 7):
            skin_px(fx + i, fy + 7, 0.95)
        # уши
        for side in ("right", "left"):
            x, y, w, h = f[side]
            for j in (4, 5):
                skin_px(x + 3, y + j, 0.82)
                skin_px(x + 4, y + j, 0.82)
        # причёска
        st = lk.style
        x, y, w, h = f["top"]
        if st != "bald":
            for j in range(h):
                for i in range(w):
                    hair_px(x + i, y + j)
        bx, by, _, _ = f["back"]
        rx, ry, _, _ = f["right"]
        lx, ly, _, _ = f["left"]
        down = {"buzz": 1, "short": 3, "side": 3, "curly": 3, "bald": 0, "long": 8, "bun": 6}[st]
        back = {"buzz": 3, "short": 5, "side": 5, "curly": 6, "bald": 3, "long": 8, "bun": 7}[st]
        for j in range(back):
            for i in range(8):
                hair_px(bx + i, by + j, 0.92)
        for j in range(down):
            for i in range(8):
                if st != "long" and j >= 2 and i >= 5:     # у висков волосы короче, к уху
                    continue
                hair_px(rx + 7 - i, ry + j, 0.92)
                hair_px(lx + i, ly + j, 0.92)
        if st == "bald":
            for j in range(3, 6):
                for i in range(0, 3):
                    hair_px(rx + 7 - i, ry + j, 0.9)
                    hair_px(lx + i, ly + j, 0.9)
        # лоб: чёлка
        fringe = {"buzz": [1] * 8, "short": [2, 2, 1, 1, 1, 2, 2, 2], "side": [3, 2, 2, 1, 1, 1, 1, 2],
                  "curly": [2, 2, 2, 2, 2, 2, 2, 2], "bald": [0] * 8, "long": [2, 2, 1, 1, 1, 1, 2, 2],
                  "bun": [2, 1, 1, 1, 1, 1, 1, 2]}[st]
        for i, n in enumerate(fringe):
            for j in range(n):
                hair_px(fx + i, fy + j)
        if st == "long":
            for j in range(2, 8):
                hair_px(fx, fy + j, 0.9)
                hair_px(fx + 7, fy + j, 0.9)
        # слой шляпы: объём волос
        if st in ("long", "curly", "bun"):
            hf = faces("hat")
            for name in ("right", "left", "back", "top"):
                x, y, w, h = hf[name]
                rows = {"long": 8, "curly": 4, "bun": 3}[st] if name != "top" else h
                for j in range(rows):
                    for i in range(w):
                        if name in ("right", "left") and st == "long" and j >= 3 and (i >= 6 if name == "right" else i <= 1):
                            continue
                        if self.look.rnd.random() < (0.85 if st != "curly" else 0.7):
                            self.px(x + i, y + j, shade(hair, rnd.uniform(0.95, 1.15)))
            if st == "bun":
                x, y, w, h = hf["back"]
                for j in range(1, 4):
                    for i in range(2, 6):
                        self.px(x + i, y + j, shade(hair, 1.1))
        # борода и усы — на лице
        b = lk.beard
        if b == "stubble":
            for i in range(1, 7):
                for j in (6, 7):
                    if rnd.random() < 0.55 and not (j == 6 and i in (3, 4)):
                        self.px(fx + i, fy + j, shade(tone, 0.72))
        elif b == "mustache":
            for i in (2, 3, 4, 5):
                hair_px(fx + i, fy + 5 if i in (2, 5) else fy + 5, 0.9)
            self.px(fx + 3, fy + 5, shade(hair, 0.95))
            self.px(fx + 4, fy + 5, shade(hair, 0.95))
        elif b == "beard":
            for i in range(1, 7):
                hair_px(fx + i, fy + 7)
                if i in (1, 2, 5, 6):
                    hair_px(fx + i, fy + 6)
            hair_px(fx + 2, fy + 5, 0.9)
            hair_px(fx + 5, fy + 5, 0.9)
            for side in ("right", "left"):
                x, y, w, h = f[side]
                for j in (5, 6, 7):
                    for i in range(8):
                        if (i >= 4) if side == "right" else (i <= 3):
                            hair_px(x + i, y + j, 0.9)

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


def soldier(look, side, rnd, n):
    s = Skin(look)
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


def officer(look, rnd, volkov=False):
    s = Skin(look)
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


def civilian(look, rnd, n, desert=False, artem=False):
    s = Skin(look)
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
            s.rows(arm, 4, 12, look.tone)                    # руки — тоном лица
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


def make(name):
    role = "artem" if name in ARTEM_NAMES else role_of(name)
    digits = "".join(c for c in name if c.isdigit())
    n = int(digits) if digits else 0
    rnd = random.Random(int(hashlib.sha1(name.encode()).hexdigest()[:8], 16))
    g = sum((name[:-len(digits)] if digits else name).encode()) % 7
    if role == "volkov":
        # седой, с усами, тяжёлые брови, серые глаза — узнаётся и со спины по шинели и красному околышу
        return officer(Look(n, g, rnd, tone="#e2b08c", hair="#9a9a9a", style="short", eyes="#6b7b86", beard="mustache"), rnd,
                       volkov=True)
    if role == "artem":
        return civilian(Look(n, g, rnd, tone="#e7b48c", hair="#3a2619", style="side", eyes="#4a2e1c", beard="stubble"), rnd, 0,
                        artem=True)
    look = Look(n, g, rnd)
    if role in ("north", "south"):
        if look.style in ("long", "bun"):
            look.style = "short"
        return soldier(look, role, rnd, n)
    if role == "officer":
        if look.style in ("long", "bun", "curly"):
            look.style = "short"
        return officer(look, rnd)
    return civilian(look, rnd, n + g, desert=role == "desert")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    table = {}
    for name in names():
        path = os.path.join(a.out, f"{name}.png")
        with open(path, "wb") as f:
            f.write(make(name).png())
        table[name] = os.path.abspath(path)
    with open(os.path.join(a.out, "skins.json"), "w") as f:
        json.dump(table, f, indent=1)
    print(f"{len(table)} скинов → {a.out}")


if __name__ == "__main__":
    main()
