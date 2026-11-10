"""Compares the Kotlin template bank (DumpTiles.kt output) with the Python one on real crops.

usage:
    python3 tools/dev/bank_eval.py crops.bin kt_Caveat.bin kt_MarckScript.bin ... --model model.bin
"""
import argparse
import random
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "vision"))
from common import CHARSET  # noqa: E402
from dataset import ensure_fonts, normalise_char, render_char  # noqa: E402
from eval import embed, load_model  # noqa: E402


def read_crops(path):
    d = Path(path).read_bytes()
    n = int.from_bytes(d[:4], "big")
    off = 4
    out = []
    for _ in range(n):
        off += 20
        out.append(np.frombuffer(d[off:off + 64 * 64 * 4], dtype=">f4").reshape(64, 64).copy())
        off += 64 * 64 * 4
    return out


def read_bank(path):
    d = Path(path).read_bytes()
    n = int.from_bytes(d[:4], "big")
    off = 4
    out = {}
    for _ in range(n):
        cp = int.from_bytes(d[off:off + 4], "big")
        off += 4
        out[chr(cp)] = np.frombuffer(d[off:off + 64 * 64 * 4], dtype=">f4").reshape(64, 64).copy()
        off += 64 * 64 * 4
    return out


def accuracy(E, tiles, labs, truth):
    T = embed(ops_global, torch.from_numpy(np.stack(tiles)[:, None])).numpy()
    S = E @ T.T
    pred = [labs[i] for i in S.argmax(1)]
    return sum(1 for p, t in zip(pred, truth) if p == t)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("crops")
    ap.add_argument("banks", nargs="+")
    ap.add_argument("--model", required=True)
    ap.add_argument("--truth", required=True)
    a = ap.parse_args()
    ops_global = load_model(Path(a.model))
    crops = read_crops(a.crops)
    truth = [chr(int(v)) for v in Path(a.truth).read_text().split(",")]
    E = embed(ops_global, torch.from_numpy(np.stack(crops)[:, None])).numpy()
    banks = [read_bank(p) for p in a.banks]
    for name, fn in {"as-is": lambda x: x, "flipud": np.flipud, "fliplr": np.fliplr,
                     "rot180": lambda x: np.flipud(np.fliplr(x))}.items():
        tiles, labs = [], []
        for b in banks:
            for ch, t in b.items():
                tiles.append(fn(t).copy())
                labs.append(ch)
        print(f"{name}: {accuracy(E, tiles, labs, truth)}/{len(crops)}")
    # python-side bank for reference
    fonts = ensure_fonts()
    for fname in ("Caveat[wght].ttf", "MarckScript-Regular.ttf", "Neucha.ttf"):
        f = [x for x in fonts if x.name == fname][0]
        tiles, labs = [], []
        for ch in CHARSET:
            raw = render_char(f, ch, random.Random(1), 96)
            if raw is None:
                continue
            tiles.append(normalise_char(raw, random.Random(1)))
            labs.append(ch)
        print(f"python {fname}: {accuracy(E, tiles, labs, truth)}/{len(crops)}")
