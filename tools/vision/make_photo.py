"""Renders realistic-looking photo pages of handwriting (for testing the CV pipeline).

usage: python3 make_photo.py out/page1.png out/page1.gray out/page1.txt
"""
from __future__ import annotations

import math
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from dataset import FONT_DIR, ensure_fonts

TEXT = [
    "Дорогой друг,",
    "сегодня я хочу рассказать тебе о том, как важно беречь время.",
    "Каждое утро начинается с маленького шага: чашка чая, тёплый свет",
    "из окна и несколько строк в тетради.",
    "Мы часто откладываем важное на потом, но именно сейчас самое",
    "лучшее время начать. Попробуй написать письмо от руки, и ты",
    "почувствуешь, как оживает бумага.",
    "С уважением, твой друг.",
]


def render_page(path: Path, seed: int, font_name: str = "Caveat[wght].ttf", text=None,
                width: int = 1100, height: int = 1500, size: int = 52):
    rng = random.Random(seed)
    np_rng = np.random.RandomState(seed)
    fonts = ensure_fonts()
    font_file = next((f for f in fonts if f.name == font_name), fonts[0])
    font = ImageFont.truetype(str(font_file), size)
    img = Image.new("L", (width, height), 235)
    d = ImageDraw.Draw(img)
    lines = text or TEXT
    y = 120
    truth = []
    x_left = 90
    for li, line in enumerate(lines):
        x = x_left + (rng.randint(0, 30) if li in (0, len(lines) - 1) else 0)
        wave_phase = rng.random() * 6.28
        wave_freq = rng.uniform(0.6, 1.4)
        for ch in line:
            bbox = font.getbbox(ch)
            w = max(2, bbox[2] - bbox[0])
            if ch == " ":
                x += size * rng.uniform(0.22, 0.34)
                continue
            dy = math.sin(x / 260.0 * wave_freq + wave_phase) * size * 0.03 + rng.uniform(-2.5, 2.5)
            rot = rng.uniform(-4, 4)
            cell = Image.new("L", (w + 40, size * 2 + 40), 0)
            cd = ImageDraw.Draw(cell)
            cd.text((20 - bbox[0], 20), ch, font=font, fill=255)
            cell = cell.rotate(rot, resample=Image.BILINEAR, fillcolor=0)
            ink = np.asarray(cell).astype(np.float32) / 255.0
            # slight pen pressure variation: thin or thick stroke
            if rng.random() < 0.25:
                ink = np.asarray(cell.filter(ImageFilter.MinFilter(3))).astype(np.float32) / 255.0
            # composite
            px = int(x - 20)
            py = int(y + dy - 10)
            base = np.asarray(img).astype(np.float32)
            h, w2 = ink.shape
            x0, x1 = max(0, px), min(width, px + w2)
            y0, y1 = max(0, py), min(height, py + h)
            if x1 > x0 and y1 > y0:
                sub = base[y0:y1, x0:x1]
                patch = ink[y0 - py:y1 - py, x0 - px:x1 - px]
                base[y0:y1, x0:x1] = sub * (1 - patch * 0.86)
                img = Image.fromarray(base.astype(np.uint8))
            truth.append(ch)
            x += w * rng.uniform(0.78, 0.92)
        y += size * 1.62
    # paper + photo effects
    arr = np.asarray(img).astype(np.float32)
    yy, xx = np.mgrid[0:height, 0:width]
    shade = 1.0 - 0.22 * (((xx - width * 0.3) ** 2) / (width ** 2) + ((yy - height * 0.2) ** 2) / (height ** 2))
    arr = arr * shade
    arr += np_rng.normal(0, 4.5, arr.shape)
    arr = np.clip(arr, 0, 255)
    img = Image.fromarray(arr.astype(np.uint8))
    # slight page rotation + blur (hand held camera)
    img = img.rotate(rng.uniform(-1.2, 1.2), resample=Image.BILINEAR, fillcolor=235)
    img = img.filter(ImageFilter.GaussianBlur(0.6))
    img = img.resize((width // 2, height // 2), Image.LANCZOS)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    # dump raw grayscale for the JVM test harness
    gray = np.asarray(img).astype(np.uint8)
    with open(str(path).replace(".png", ".gray"), "wb") as f:
        f.write(np.int32(gray.shape[1]).tobytes())
        f.write(np.int32(gray.shape[0]).tobytes())
        f.write(gray.tobytes())
    Path(str(path).replace(".png", ".txt")).write_text("".join(truth), encoding="utf-8")
    Path(str(path).replace(".png", ".cp")).write_text(",".join(str(ord(c)) for c in truth))
    return truth


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/photos/page1.png")
    t = render_page(out, seed=int(sys.argv[2]) if len(sys.argv) > 2 else 1)
    print(f"wrote {out} with {len(t)} letters")


TEMPLATE_ROWS = [
    "абвгдеёжзий",
    "клмнопрстуф",
    "хцчшщъыьэюя",
    "0123456789",
]


def render_template(path: Path, seed: int, font_name: str = "Caveat[wght].ttf",
                    width: int = 1000, height: int = 1400, cell: int = 74):
    """A printed template sheet filled in by hand: one letter per cell."""
    rng = random.Random(seed)
    np_rng = np.random.RandomState(seed)
    fonts = ensure_fonts()
    font_file = next((f for f in fonts if f.name == font_name), fonts[0])
    font = ImageFont.truetype(str(font_file), int(cell * 0.8))
    img = Image.new("L", (width, height), 240)
    d = ImageDraw.Draw(img)
    # faint grid
    x0, y0 = 70, 110
    for r in range(len(TEMPLATE_ROWS)):
        for c in range(len(TEMPLATE_ROWS[r])):
            d.rectangle([x0 + c * cell, y0 + r * cell, x0 + (c + 1) * cell, y0 + (r + 1) * cell],
                        outline=212, width=1)
    truth = []
    for r, row in enumerate(TEMPLATE_ROWS):
        for c, ch in enumerate(row):
            cx = x0 + c * cell + cell / 2
            cy = y0 + r * cell + cell / 2
            bbox = font.getbbox(ch)
            w = bbox[2] - bbox[0]
            h = bbox[3] - bbox[1]
            cellimg = Image.new("L", (cell, cell), 0)
            cd = ImageDraw.Draw(cellimg)
            cd.text(((cell - w) / 2 - bbox[0], (cell - h) / 2 - bbox[1]), ch, font=font, fill=255)
            cellimg = cellimg.rotate(rng.uniform(-3, 3), resample=Image.BILINEAR, fillcolor=0)
            ink = np.asarray(cellimg).astype(np.float32) / 255.0
            px = int(cx - cell / 2)
            py = int(cy - cell / 2)
            base = np.asarray(img).astype(np.float32)
            base[py:py + cell, px:px + cell] = base[py:py + cell, px:px + cell] * (1 - ink * 0.88)
            img = Image.fromarray(np.clip(base, 0, 255).astype(np.uint8))
            truth.append(ch)
    arr = np.asarray(img).astype(np.float32)
    yy, xx = np.mgrid[0:height, 0:width]
    shade = 1.0 - 0.18 * (((xx - width * 0.5) ** 2) / (width ** 2) + ((yy - height * 0.4) ** 2) / (height ** 2))
    arr = arr * shade + np_rng.normal(0, 3.0, arr.shape)
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    img = img.rotate(rng.uniform(-0.8, 0.8), resample=Image.BILINEAR, fillcolor=240)
    img = img.filter(ImageFilter.GaussianBlur(0.5))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    gray = np.asarray(img).astype(np.uint8)
    with open(str(path).replace(".png", ".gray"), "wb") as f:
        f.write(np.int32(gray.shape[1]).tobytes())
        f.write(np.int32(gray.shape[0]).tobytes())
        f.write(gray.tobytes())
    Path(str(path).replace(".png", ".txt")).write_text("".join(truth), encoding="utf-8")
    Path(str(path).replace(".png", ".cp")).write_text(",".join(str(ord(c)) for c in truth))
    return truth
