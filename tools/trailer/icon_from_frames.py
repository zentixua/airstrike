#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "numpy>=1.26",
#     "pillow>=10",
#     "scipy>=1.11",
# ]
# ///
"""Иконка мода из кадров плана «icon» трейлера (шахед крупно на фоне неба).

    uv run tools/trailer/icon_from_frames.py mod/run/scenario/trailer-icon/frames/icon dist/icon

План «icon» снимается сценарием трейлера (AIRSTRIKE_TRAILER_SHOTS=icon); готовая иконка в мод не входит.

Сам выбирает кадр (кроме первых 10 %, пока камера заходит за шахед): самый крупный силуэт снаряда (всё, что заметно отличается от неба), целиком в кадре и ближе
к центру. Пишет:
  airstrike-icon.png         512×512: кадр с запасом вокруг силуэта, скруглённые углы, тёплая рамка и тёмный
                             контур снаружи (видно и на тёмной, и на светлой теме Modrinth);
  airstrike-icon-small.png   512×512 для мелких размеров: кадр теснее, небо размыто, силуэт резкий и контрастный;
  airstrike-icon-96.png, -64.png — уменьшенный малый вариант;
  preview.png                оба варианта в 128/64/32 px на тёмном и светлом фоне;
  source.txt                 какой кадр взят.
Зависимости — в блоке script выше (PEP 723): uv run ставит их сам.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter
from scipy import ndimage

N = 512
RIM, OUTLINE = (255, 138, 42), (20, 16, 14)
SETTLE = 10  # первая 1/SETTLE кадров плана не берётся


def subject(img):
    """Маска силуэта: отличие от цвета неба (медиана верхней трети), крупнейшая связная область не у края."""
    a = np.asarray(img.convert("RGB"), np.float32)
    h, w = a.shape[:2]
    sky = np.median(a[: h // 3].reshape(-1, 3), axis=0)
    diff = np.linalg.norm(a - sky, axis=2)
    # снаряд тёмный на фоне неба; огонь и дым светлее — не в счёт
    mask = ndimage.binary_opening((diff > 55) & (a.mean(axis=2) < 90), iterations=2)
    lab, n = ndimage.label(mask)
    if n == 0:
        return None
    sizes = ndimage.sum(mask, lab, range(1, n + 1))
    # земля и облака у края кадра — не снаряд
    edge = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))) - {0}
    for e in edge:
        sizes[e - 1] = 0
    if sizes.max() <= 0:
        return None
    k = int(np.argmax(sizes)) + 1
    ys, xs = np.nonzero(lab == k)
    return lab == k, (xs.min(), ys.min(), xs.max(), ys.max()), sizes[k - 1]


def score(img):
    s = subject(img)
    if s is None:
        return -1, None
    mask, (x0, y0, x1, y1), area = s
    h, w = mask.shape
    pad = 0.04 * min(w, h)
    if x0 < pad or y0 < pad or x1 > w - pad or y1 > h - pad:  # обрезан краем кадра
        return -1, None
    cx, cy = (x0 + x1) / 2 / w - 0.5, (y0 + y1) / 2 / h - 0.5
    return area * (1 - 1.2 * (cx * cx + cy * cy) ** 0.5), s


def square(img, box, margin):
    x0, y0, x1, y1 = box
    side = max(x1 - x0, y1 - y0) * (1 + 2 * margin)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    side = min(side, img.width, img.height)
    left = min(max(cx - side / 2, 0), img.width - side)
    top = min(max(cy - side / 2, 0), img.height - side)
    return (left, top, left + side, top + side)


def framed(img):
    """Скруглённые углы, тёплая рамка, тёмный контур снаружи рамки."""
    img = img.convert("RGBA")
    r = int(N * 0.14)
    ring = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    d = ImageDraw.Draw(ring)
    d.rounded_rectangle((0, 0, N - 1, N - 1), r, outline=OUTLINE + (255,), width=int(N * 0.012))
    d.rounded_rectangle((int(N * 0.012), int(N * 0.012), N - 1 - int(N * 0.012), N - 1 - int(N * 0.012)), r,
                        outline=RIM + (255,), width=int(N * 0.028))
    mask = Image.new("L", (N, N), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, N - 1, N - 1), r, fill=255)
    img.putalpha(mask)
    img.alpha_composite(ring)
    return img


def main(frames, out):
    os.makedirs(out, exist_ok=True)
    names = sorted(f for f in os.listdir(frames) if f.endswith(".png"))
    best, best_s, best_name = -1, None, None
    # первые 10 % плана — камера ещё догоняет шахед (он сбоку, «обычная ракета в профиль»); дальше каждый шаг сетки
    step = max(1, len(names) // 60)
    for f in [f for i, f in enumerate(names) if i % step == 0 and i >= len(names) // SETTLE]:
        img = Image.open(os.path.join(frames, f))
        sc, s = score(img)
        if sc > best:
            best, best_s, best_name = sc, s, f
    if best_s is None:
        raise SystemExit("в кадрах не нашёлся силуэт целиком — нужен другой план")
    img = Image.open(os.path.join(frames, best_name)).convert("RGB")
    mask, box, _ = best_s

    big = img.crop(square(img, box, 0.30)).resize((N, N), Image.LANCZOS)
    big = ImageEnhance.Contrast(ImageEnhance.Color(big).enhance(1.15)).enhance(1.1)
    framed(big).save(os.path.join(out, "airstrike-icon.png"), optimize=True)

    # малый: небо размыто и светлее, силуэт резкий, темнее и с тёмной обводкой — читается на 32 px
    sil = Image.fromarray((ndimage.binary_dilation(mask, iterations=2) * 255).astype(np.uint8))
    # фон без снаряда: силуэт закрашен цветом неба, иначе размытие даёт вокруг него тёмный ореол
    arr = np.asarray(img, np.float32).copy()
    arr[ndimage.binary_dilation(mask, iterations=6)] = np.median(arr[: arr.shape[0] // 3].reshape(-1, 3), axis=0)
    bg = Image.fromarray(arr.astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius=img.width / 60))
    bg = ImageEnhance.Brightness(bg).enhance(1.08)
    fg = ImageEnhance.Contrast(img).enhance(1.35)
    edge = Image.fromarray((ndimage.binary_dilation(mask, iterations=max(2, img.width // 400)) * 255).astype(np.uint8))
    small = bg.copy()
    small.paste(Image.new("RGB", img.size, OUTLINE), mask=edge)
    small.paste(fg, mask=sil)
    box_small = square(img, box, 0.12)
    small = small.crop(box_small).resize((N, N), Image.LANCZOS).filter(ImageFilter.UnsharpMask(radius=2, percent=80))
    small = framed(small)
    small.save(os.path.join(out, "airstrike-icon-small.png"), optimize=True)
    for k in (96, 64):
        small.resize((k, k), Image.LANCZOS).save(os.path.join(out, f"airstrike-icon-{k}.png"), optimize=True)

    icons = [Image.open(os.path.join(out, "airstrike-icon.png")), small]
    prev = Image.new("RGBA", (2 * (128 + 64 + 32 + 40), 2 * 140), (0, 0, 0, 0))
    for col, bgc in enumerate(((24, 26, 30, 255), (245, 245, 247, 255))):
        x0 = col * (128 + 64 + 32 + 40)
        ImageDraw.Draw(prev).rectangle((x0, 0, x0 + 128 + 64 + 32 + 39, prev.height), fill=bgc)
        for row, ic in enumerate(icons):
            x = x0 + 5
            for k in (128, 64, 32):
                prev.alpha_composite(ic.resize((k, k), Image.LANCZOS), (x, row * 140 + 5))
                x += k + 10
    prev.save(os.path.join(out, "preview.png"))
    with open(os.path.join(out, "source.txt"), "w") as f:
        f.write(f"{os.path.join(frames, best_name)}\n")
    print("кадр:", best_name, "→", out)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
