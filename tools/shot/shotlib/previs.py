"""Черновой кадр плана по сцене, без игры и видеокарты: лучи по блокам мира (цвет блока, грани светлее и темнее, как в
Minecraft, дымка вдали), снаряды — яркой точкой со следом, удары — огненным шаром, дым — серым облаком (те же клубы, что
считает планировщик). Цель — увидеть композицию и движение плана за секунды, до вывода на ноутбуке.
"""
from __future__ import annotations

import math
import os
import subprocess

import numpy as np
from PIL import Image, ImageDraw

from . import camera as C
from . import world as W
from .scene import Scene

SKY_TOP = np.array([110, 150, 215], dtype=np.float64)
SKY_LOW = np.array([185, 210, 235], dtype=np.float64)
FACE = {0: 0.62, 1: 1.0, 2: 0.8}  # шаг по оси x / y / z: как свет граней Minecraft (верх — 1, север-юг — 0,8, запад-восток — 0,6)


def rays(world: W.World, eye: np.ndarray, dirs: np.ndarray, reach: float = 600.0):
    """Первый не-воздух по лучам (k, 3): расстояние, ось грани, род, цвет (k, 3). Пустое пространство — прыжками по
    шахматному расстоянию, у стен — по клеткам (Amanatides–Woo)."""
    k = len(dirs)
    o = eye - world.origin
    shape = np.array(world.shape)
    dist = world.dist
    d = dirs / np.linalg.norm(dirs, axis=1, keepdims=True)
    inv = 1.0 / np.where(np.abs(d) < 1e-9, 1e-9, d)
    t = np.zeros(k)
    # вход в рамку сверху: луч над рамкой идёт до её верха
    hit = np.zeros(k, dtype=bool)
    axis = np.full(k, 1)
    kind = np.zeros(k, dtype=np.uint8)
    col = np.zeros(k, dtype=np.int64)
    alive = np.ones(k, dtype=bool)
    for _ in range(900):
        idx = np.flatnonzero(alive)
        if len(idx) == 0:
            break
        p = o + d[idx] * t[idx, None]
        c = np.floor(p).astype(np.int64)
        inb = (c >= 0).all(1) & (c < shape).all(1)
        # над рамкой — идём к её верху, вне по сторонам или ниже — луч кончился (неизвестно)
        above = (c[:, 1] >= shape[1]) & (c[:, 0] >= 0) & (c[:, 0] < shape[0]) & (c[:, 2] >= 0) & (c[:, 2] < shape[2])
        gone = ~inb & ~above
        too_far = t[idx] > reach
        stop = gone | too_far | (above & (d[idx, 1] >= 0))
        alive[idx[stop]] = False
        go_above = above & ~stop
        if go_above.any():
            ii = idx[go_above]
            t[ii] = np.maximum(t[ii] + 1e-3, (shape[1] - 1e-3 - o[ii * 0 + 0, 1] if False else (shape[1] - 1e-3 - o[1])) / d[ii, 1]
                               if False else (shape[1] - 1e-3 - o[1]) / d[ii, 1])
        sel = inb & ~stop
        if not sel.any():
            continue
        ii = idx[sel]
        cc = c[sel]
        kd = world.kind[cc[:, 0], cc[:, 1], cc[:, 2]]
        solid = kd != W.AIR
        if solid.any():
            hh = ii[solid]
            hit[hh] = True
            alive[hh] = False
            kind[hh] = kd[solid]
            col[hh] = world.col[cc[solid, 0], cc[solid, 1], cc[solid, 2]]
        free = ~solid
        if not free.any():
            continue
        ff = ii[free]
        fc = cc[free]
        dd = dist[fc[:, 0], fc[:, 1], fc[:, 2]].astype(np.float64)
        jump = dd >= 3
        if jump.any():
            jj = ff[jump]
            t[jj] += dd[jump] - 2
        walk = ~jump
        if walk.any():
            ww = ff[walk]
            pc = o + d[ww] * t[ww, None]
            cell = np.floor(pc)
            nxt = np.where(d[ww] > 0, cell + 1, cell)
            tt = (nxt - pc) * inv[ww]
            tt = np.where(tt <= 1e-9, 1e-9, tt)
            ax = np.argmin(tt, axis=1)
            t[ww] += tt[np.arange(len(ww)), ax] + 1e-4
            axis[ww] = ax
    rgb = world.rgb[np.clip(col, 0, len(world.rgb) - 1)].astype(np.float64)
    return t, axis, kind, rgb, hit


def sky(dirs: np.ndarray) -> np.ndarray:
    up = np.clip(dirs[:, 1] / np.linalg.norm(dirs, axis=1), -0.2, 1)
    k = np.clip(up * 2.2, 0, 1)[:, None]
    return SKY_LOW * (1 - k) + SKY_TOP * k


def frame(scene: Scene, eye, yaw, pitch, roll, fov, t: float, size=(480, 270), trails: int = 24,
          reach: float = 600.0, night: bool = False) -> Image.Image:
    w, h = size
    aspect = w / h
    f, r, u = C.basis(np.array(yaw), np.array(pitch), np.array(roll))
    th = math.tan(math.radians(fov) / 2)
    xs = (np.arange(w) + 0.5) / w * 2 - 1
    ys = 1 - (np.arange(h) + 0.5) / h * 2
    X, Y = np.meshgrid(xs, ys)
    dirs = f[None] + r[None] * (X.reshape(-1, 1) * th * aspect) + u[None] * (Y.reshape(-1, 1) * th)
    dirs /= np.linalg.norm(dirs, axis=1, keepdims=True)
    eye = np.asarray(eye, dtype=np.float64)
    dist, axis, kind, rgb, hit = rays(scene.world, eye, dirs, reach)
    shade = np.vectorize(FACE.get)(axis).astype(np.float64)
    # низ граней — темнее (луч вверх в грань по y)
    shade = np.where((axis == 1) & (dirs[:, 1] > 0), 0.5, shade)
    col = rgb * shade[:, None]
    col = np.where((kind == W.WATER)[:, None], col * 0.85 + np.array([20, 40, 80]) * 0.15, col)
    skyc = sky(dirs)
    fog = np.clip((dist / reach) ** 1.6, 0, 1)[:, None]
    img = np.where(hit[:, None], col * (1 - fog) + SKY_LOW * fog, skyc)
    depth = np.where(hit, dist, np.inf)

    # огненные шары ударов (растут 6 тиков, гаснут к 40)
    for im in scene.impacts:
        age = t - im.t
        if age < 0 or age > 40:
            continue
        rad = im.blast * (0.4 + 0.6 * min(1, age / 6)) * (1 if age < 14 else max(0.15, 1 - (age - 14) / 30))
        c = im.pos + np.array([0, im.blast * 0.35, 0])
        oc = eye - c
        b = (dirs * oc).sum(1)
        q = (oc * oc).sum() - rad * rad
        disc = b * b - q
        ok = disc > 0
        tt = -b - np.sqrt(np.maximum(disc, 0))
        ok &= (tt > 0) & (tt < depth)
        heat = max(0.0, 1 - age / 40)
        fire = np.array([255, 200, 90]) * heat + np.array([120, 60, 30]) * (1 - heat)
        img[ok] = img[ok] * 0.15 + fire * 0.85
        depth = np.where(ok, tt, depth)

    # дым: свет сквозь клубы до глубины пикселя
    puffs = scene.puffs(t)
    if len(puffs):
        tau = np.zeros(len(dirs))
        from scipy.special import erf
        end_all = np.where(np.isfinite(depth), depth, 4000)
        px_all, py_all = X.reshape(-1), Y.reshape(-1)
        for p in puffs:
            c, s, k = p[:3], p[3], p[4]
            wv = c - eye
            # только пиксели около клуба на экране (шлейфы — тысячи клубов): 3σ вокруг его места
            z = float(wv @ f)
            dist = float(np.linalg.norm(wv))
            if dist > 3 * s:
                if z <= 0:
                    continue
                cu, cv = float(wv @ r) / z / (th * aspect), float(wv @ u) / z / th
                ru, rv = 3 * s / z / (th * aspect), 3 * s / z / th
                if abs(cu) - ru > 1 or abs(cv) - rv > 1:
                    continue
                idx = np.flatnonzero((np.abs(px_all - cu) <= ru) & (np.abs(py_all - cv) <= rv))
                if not len(idx):
                    continue
            else:
                idx = np.arange(len(dirs))
            along = dirs[idx] @ wv
            miss2 = (wv * wv).sum() - along ** 2
            g = np.exp(-np.maximum(miss2, 0) / (2 * s * s))
            end = end_all[idx]
            span = 0.5 * (erf((end - along) / (math.sqrt(2) * s)) - erf((0 - along) / (math.sqrt(2) * s)))
            tau[idx] += k * s * math.sqrt(2 * math.pi) * g * span
        a = (1 - np.exp(-tau))[:, None]
        img = img * (1 - a) + np.array([70, 66, 62]) * a

    if night:
        img = img * 0.35
    im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8).reshape(h, w, 3))
    draw = ImageDraw.Draw(im)
    dmap = depth.reshape(h, w)

    def dot(p, rad, color):
        uu, vv, z = C.project(eye, np.array(yaw), np.array(pitch), np.array(roll), fov, aspect, p)
        if z <= 0.5 or abs(uu) > 1.2 or abs(vv) > 1.2:
            return None
        px, py = (uu + 1) / 2 * w, (1 - vv) / 2 * h
        ix, iy = int(np.clip(px, 0, w - 1)), int(np.clip(py, 0, h - 1))
        if dmap[iy, ix] < z - 1.5:
            return None
        pr = max(1.5, rad / z / (2 * math.tan(math.radians(fov) / 2)) * h)
        draw.ellipse([px - pr, py - pr, px + pr, py + pr], fill=color)
        return px, py

    for tr in scene.tracks.values():
        if not (tr.first <= t <= tr.last):
            continue
        pts = tr.at(np.arange(max(tr.first, t - trails), t + 0.01, 2.0))
        prev = None
        for i, p in enumerate(pts):
            if np.isnan(p[0]):
                continue
            q = dot(p, 0.4, (235, 235, 230))
            if q and prev:
                draw.line([prev, q], fill=(225, 225, 220), width=1)
            prev = q
        dot(tr.at(np.array([t]))[0], 1.2 if "b2" not in tr.weapon else 20, (255, 120, 40))
    for pp in scene.people.values():
        if pp.first <= t <= pp.last:
            dot(pp.at(np.array([t]))[0] + np.array([0, 0.9, 0]), 0.5, (40, 60, 200))
    return im


def overlay(im: Image.Image, lines: list[str], thirds: bool = True):
    d = ImageDraw.Draw(im)
    w, h = im.size
    if thirds:
        for k in (1, 2):
            d.line([(w * k / 3, 0), (w * k / 3, h)], fill=(255, 255, 255), width=1)
            d.line([(0, h * k / 3), (w, h * k / 3)], fill=(255, 255, 255), width=1)
    y = 3
    for line in lines:
        d.text((5, y), line, fill=(255, 255, 0))
        y += 11
    return im


def render(result, scene: Scene, out_dir: str, size=(480, 270), every: int = 1, night: bool = False,
           guides: bool = True, log=print) -> list[str]:
    """Кадры плана (через `every` тиков) в out_dir/frames/*.png, ролик out_dir/previs.mp4 (20 к/с / every) и лист."""
    os.makedirs(os.path.join(out_dir, "frames"), exist_ok=True)
    files = []
    p = result.parts
    n = len(result.P)
    for i in range(0, n, every):
        t = result.spec.a + i
        im = frame(scene, result.P[i], result.yaw[i], result.pitch[i], result.roll[i], result.fov, t, size, night=night)
        if guides:
            info = [f"{result.spec.name} {result.style.name} t={t} fov={result.fov:.0f}"]
            if "_u" in p and p["_have"][i]:
                info.append(f"share {p['_share'][0][i]:.3f} vis {p['_vis'][0][i]:.2f}")
            overlay(im, info, thirds=False)
        fn = os.path.join(out_dir, "frames", f"{len(files):05d}.png")
        im.save(fn)
        files.append(fn)
    fps = 20 / every
    mp4 = os.path.join(out_dir, "previs.mp4")
    try:
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", f"{fps}", "-i",
                        os.path.join(out_dir, "frames", "%05d.png"), "-pix_fmt", "yuv420p", "-vf",
                        "scale=trunc(iw/2)*2:trunc(ih/2)*2", mp4], check=True)
    except (OSError, subprocess.CalledProcessError) as e:
        log(f"ffmpeg: {e}")
    sheet(files, os.path.join(out_dir, "sheet.jpg"))
    return files


def sheet(files: list[str], out: str, cols: int = 4, rows: int = 3):
    if not files:
        return
    pick = [files[int(round(k * (len(files) - 1) / max(1, cols * rows - 1)))] for k in range(cols * rows)]
    ims = [Image.open(f) for f in pick]
    w, h = ims[0].size
    S = Image.new("RGB", (w * cols, h * rows))
    for k, im in enumerate(ims):
        S.paste(im, ((k % cols) * w, (k // cols) * h))
    S.save(out, quality=88)
