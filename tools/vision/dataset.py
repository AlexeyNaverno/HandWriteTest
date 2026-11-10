"""Synthetic handwriting dataset.

Every sample is a 64x64 ink image of one character, produced by rendering a real handwriting
font with random size, slant, stroke thickness, blur, noise, paper-cream tint and elastic
distortion. Using many fonts plus augmentation is what makes the embedding generalise to a
user's own notebook photos.
"""
from __future__ import annotations

import io
import math
import random
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from common import CHARSET, INPUT

FONT_DIR = Path(__file__).resolve().parent / "fonts"
FONT_DIR.mkdir(parents=True, exist_ok=True)

# handwriting fonts with Cyrillic coverage (Google Fonts, OFL)
FONT_URLS = [
    ("Caveat[wght].ttf", "google/fonts", "ofl/caveat/Caveat[wght].ttf"),
    ("Neucha.ttf", "google/fonts", "ofl/neucha/Neucha.ttf"),
    ("MarckScript-Regular.ttf", "google/fonts", "ofl/marckscript/MarckScript-Regular.ttf"),
    ("Pacifico-Regular.ttf", "google/fonts", "ofl/pacifico/Pacifico-Regular.ttf"),
    ("Lobster-Regular.ttf", "google/fonts", "ofl/lobster/Lobster-Regular.ttf"),
    ("CaveatBrush-Regular.ttf", "google/fonts", "ofl/caveatbrush/CaveatBrush-Regular.ttf"),
    ("BadScript-Regular.ttf", "google/fonts", "ofl/badscript/BadScript-Regular.ttf"),
    ("KellySlab-Regular.ttf", "google/fonts", "ofl/kellyslab/KellySlab-Regular.ttf"),
    ("PT-Serif.ttf", "google/fonts", "ofl/ptserif/PT_Serif-Web-Regular.ttf"),
    ("PT-Serif-Italic.ttf", "google/fonts", "ofl/ptserif/PT_Serif-Web-Italic.ttf"),
    ("Comforter-Regular.ttf", "google/fonts", "ofl/comforter/Comforter-Regular.ttf"),
    ("YesevaOne-Regular.ttf", "google/fonts", "ofl/yesevaone/YesevaOne-Regular.ttf"),
    ("Oregano-Regular.ttf", "google/fonts", "ofl/oregano/Oregano-Regular.ttf"),
    ("Oregano-Italic.ttf", "google/fonts", "ofl/oregano/Oregano-Italic.ttf"),
    ("PatrickHand-Regular.ttf", "google/fonts", "ofl/patrickhand/PatrickHand-Regular.ttf"),
    ("ShadowsIntoLight.ttf", "google/fonts", "ofl/shadowsintolight/ShadowsIntoLight.ttf"),
    ("IndieFlower-Regular.ttf", "google/fonts", "ofl/indieflower/IndieFlower-Regular.ttf"),
    ("RubikMonoOne-Regular.ttf", "google/fonts", "apache/rubikmonoone/RubikMonoOne-Regular.ttf"),
    ("PTMono-Regular.ttf", "google/fonts", "ofl/ptmono/PTM55FT.ttf"),
]


def _download(url: str, dest: Path) -> bool:
    """Download through the GitHub API (the sandbox only exposes github.com)."""
    import os
    import subprocess

    path = url.split("raw.githubusercontent.com/", 1)[-1]
    parts = path.split("/", 3)
    if len(parts) < 4:
        return False
    api = f"repos/{parts[0]}/{parts[1]}/contents/{parts[3]}"
    try:
        out = subprocess.run(
            ["gh", "api", "-H", "Accept: application/vnd.github.raw", api],
            capture_output=True, timeout=120,
            env={**os.environ, "GH_TOKEN": os.environ.get("GH_TOKEN", "")},
        )
        if out.returncode != 0 or len(out.stdout) < 5000:
            return False
        dest.write_bytes(out.stdout)
        return True
    except Exception as e:  # pragma: no cover - network dependent
        print(f"  ! {url.split('/')[-1]}: {e}")
        return False


def ensure_fonts() -> list[Path]:
    fonts = sorted(FONT_DIR.glob("*.ttf")) + sorted(FONT_DIR.glob("*.otf"))
    if len(fonts) >= 6:
        return fonts
    for name, repo, path in FONT_URLS:
        dest = FONT_DIR / name
        if dest.exists():
            continue
        url = f"https://raw.githubusercontent.com/{repo}/main/{path}"
        print("downloading", name)
        if not _download(url, dest):
            url = f"https://raw.githubusercontent.com/{repo}/master/{path}"
            _download(url, dest)
    return sorted(FONT_DIR.glob("*.ttf")) + sorted(FONT_DIR.glob("*.otf"))


def elastic(img: np.ndarray, rng: random.Random, strength: float = 2.2) -> np.ndarray:
    h, w = img.shape
    dx = np.random.RandomState(rng.randrange(1 << 30)).uniform(-1, 1, (5, 5)) * strength
    dy = np.random.RandomState(rng.randrange(1 << 30)).uniform(-1, 1, (5, 5)) * strength
    xs, ys = np.meshgrid(np.linspace(0, 4, w), np.linspace(0, 4, h))
    from scipy.ndimage import map_coordinates
    mx = map_coordinates(dx, [ys, xs], order=1)
    my = map_coordinates(dy, [ys, xs], order=1)
    coords = np.array([np.arange(h)[:, None] + my, np.arange(w)[None, :] + mx])
    return map_coordinates(img, coords, order=1, mode="constant")


def render_char(font_path: Path, ch: str, rng: random.Random, size_px: int = 96) -> np.ndarray | None:
    try:
        font = ImageFont.truetype(str(font_path), size_px)
    except Exception:
        return None
    bbox = font.getbbox(ch, stroke_width=0)
    if bbox is None:
        return None
    w = max(1, bbox[2] - bbox[0])
    h = max(1, bbox[3] - bbox[1])
    pad = 12
    img = Image.new("L", (w + pad * 2, h + pad * 2), 0)
    d = ImageDraw.Draw(img)
    d.text((pad - bbox[0], pad - bbox[1]), ch, font=font, fill=255)
    arr = np.asarray(img).astype(np.float32) / 255.0
    if arr.max() < 0.05:
        return None
    return arr


def normalise_char(arr: np.ndarray, rng: random.Random) -> np.ndarray:
    """Crop to ink, keep aspect ratio, centre in the INPUT x INPUT box."""
    ys, xs = np.where(arr > 0.25)
    if len(ys) < 3:
        return np.zeros((INPUT, INPUT), np.float32)
    arr = arr[ys.min(): ys.max() + 1, xs.min(): xs.max() + 1]
    h, w = arr.shape
    scale = (INPUT - 6) / max(h, w)
    nh, nw = max(1, int(round(h * scale))), max(1, int(round(w * scale)))
    im = Image.fromarray((arr * 255).astype(np.uint8)).resize((nw, nh), Image.BILINEAR)
    out = Image.new("L", (INPUT, INPUT), 0)
    out.paste(im, ((INPUT - nw) // 2, (INPUT - nh) // 2))
    a = np.asarray(out).astype(np.float32) / 255.0
    # paper tint / uneven illumination
    a = a * rng.uniform(0.75, 1.0)
    # ink noise
    a = a + np.random.RandomState(rng.randrange(1 << 30)).normal(0, rng.uniform(0.0, 0.07), a.shape).astype(np.float32)
    a = np.clip(a, 0.0, 1.0)
    # slant / shear
    shear = rng.uniform(-0.35, 0.35)
    rot = rng.uniform(-9, 9)
    im = Image.fromarray((a * 255).astype(np.uint8))
    if abs(shear) > 0.02:
        im = im.transform(im.size, Image.AFFINE, (1, shear, -shear * INPUT / 2, 0, 1, 0), resample=Image.BILINEAR)
    if abs(rot) > 1:
        im = im.rotate(rot, resample=Image.BILINEAR, fillcolor=0)
    # stroke weight (нажим)
    if rng.random() < 0.6:
        im = im.filter(ImageFilter.MinFilter(3) if rng.random() < 0.5 else ImageFilter.MaxFilter(3))
    blur = rng.uniform(0.0, 1.1)
    if blur > 0.2:
        im = im.filter(ImageFilter.GaussianBlur(blur))
    a = np.asarray(im).astype(np.float32) / 255.0
    return a


def make_sample(fonts: list[Path], rng: random.Random) -> tuple[np.ndarray, int] | None:
    ch = rng.choice(CHARSET)
    font = rng.choice(fonts)
    raw = render_char(font, ch, rng, size_px=rng.choice([64, 80, 96, 112, 128]))
    if raw is None:
        return None
    arr = normalise_char(raw, rng)
    if arr.max() < 0.05:
        return None
    label = CHARSET.index(ch)
    return arr, label


def generate(n: int, seed: int = 1234) -> tuple[np.ndarray, np.ndarray]:
    fonts = ensure_fonts()
    if not fonts:
        raise SystemExit("no fonts available for the synthetic dataset")
    print(f"fonts: {[f.name for f in fonts]}")
    rng = random.Random(seed)
    X = np.zeros((n, 1, INPUT, INPUT), np.float32)
    y = np.zeros((n,), np.int64)
    i = 0
    tries = 0
    while i < n and tries < n * 20:
        tries += 1
        s = make_sample(fonts, rng)
        if s is None:
            continue
        arr, label = s
        X[i, 0] = arr
        y[i] = label
        i += 1
        if i % 20000 == 0:
            print(f"  {i}/{n}")
    return X[:i], y[:i]
