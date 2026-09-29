# /// script
# requires-python = ">=3.11"
# dependencies = ["pillow"]
# ///
"""Листы проверки дубля: по каждому плану — первый, средний и последний кадр в ряд, с именем плана.

  uv run tools/trailer/sheets.py mod/run/scenario/<папка записи> [--width 640]
  → <папка записи>/sheets/<план>.jpg

Для проверочного прогона (AIRSTRIKE_KEEP=20: на диск каждый 20-й кадр) и для разбора полного дубля: смотреть,
есть ли в кадре то, что снимаем, не в блоке ли камера, нет ли пелены, засветки и лишнего на экране.
"""
import argparse
from pathlib import Path

from PIL import Image, ImageDraw


def sheet(shot: Path, width: int) -> Image.Image | None:
    frames = sorted(shot.glob("*.png"))
    if not frames:
        return None
    picks = [frames[0], frames[len(frames) // 2], frames[-1]]
    tiles = []
    for f in picks:
        with Image.open(f) as im:
            h = round(im.height * width / im.width)
            tiles.append((f.stem, im.convert("RGB").resize((width, h), Image.LANCZOS)))
    h = tiles[0][1].height
    out = Image.new("RGB", (width * len(tiles), h + 22), (16, 16, 16))
    draw = ImageDraw.Draw(out)
    draw.text((6, 4), f"{shot.name}  ({len(frames)} кадров на диске)", fill=(230, 230, 230))
    for i, (name, im) in enumerate(tiles):
        out.paste(im, (i * width, 22))
        draw.text((i * width + 6, 26), name, fill=(255, 220, 90))
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("rec", type=Path, help="папка записи (в ней frames/)")
    ap.add_argument("--width", type=int, default=640, help="ширина одного кадра на листе")
    args = ap.parse_args()
    out = args.rec / "sheets"
    out.mkdir(exist_ok=True)
    shots = sorted(p for p in (args.rec / "frames").iterdir() if p.is_dir())
    for shot in shots:
        s = sheet(shot, args.width)
        if s is not None:
            s.save(out / f"{shot.name}.jpg", quality=88)
            print(out / f"{shot.name}.jpg")


if __name__ == "__main__":
    main()
