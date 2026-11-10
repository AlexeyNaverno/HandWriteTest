"""Cross-font evaluation of the on-device recogniser.

Builds reference templates exactly like the app does (glyphs of ONE font, rendered to 64x64 tiles,
run through the exported int8 model) and then classifies samples drawn from the *other* fonts with
heavy augmentation - i.e. "the user writes like someone else's font".

usage: python3 eval.py [--model path] [--samples 3000]
"""
from __future__ import annotations

import argparse
import random
import struct
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from common import CHARSET, INPUT
from dataset import ensure_fonts, make_sample


def load_model(path: Path):
    d = path.read_bytes()
    p = 4
    ver, inp, emb = struct.unpack("<HHH", d[p:p + 6]); p += 6
    n = struct.unpack("<I", d[p:p + 4])[0]; p += 4 + 4 * n
    lc = struct.unpack("<H", d[p:p + 2])[0]; p += 2
    ops = []
    for _ in range(lc):
        op = d[p]; p += 1
        if op == 0:
            cin, cout, st, pad = struct.unpack("<HHBB", d[p:p + 6]); p += 6
            sc = struct.unpack("<f", d[p:p + 4])[0]; p += 4
            w = np.frombuffer(d[p:p + cin * cout * 9], dtype=np.int8).astype(np.float32) * sc; p += cin * cout * 9
            b = np.frombuffer(d[p:p + 4 * cout], dtype=np.int32).astype(np.float32); p += 4 * cout
            ops.append(("conv", torch.from_numpy(w.reshape(cout, cin, 3, 3).copy()),
                        torch.from_numpy(b.copy()), st, pad))
        elif op == 1:
            ch = struct.unpack("<H", d[p:p + 2])[0]; p += 2
            g = np.frombuffer(d[p:p + 4 * ch], dtype=np.float32).copy(); p += 4 * ch
            be = np.frombuffer(d[p:p + 4 * ch], dtype=np.float32).copy(); p += 4 * ch
            mu = np.frombuffer(d[p:p + 4 * ch], dtype=np.float32).copy(); p += 4 * ch
            va = np.frombuffer(d[p:p + 4 * ch], dtype=np.float32).copy(); p += 4 * ch
            eps = struct.unpack("<f", d[p:p + 4])[0]; p += 4
            ops.append(("bn", g, be, mu, va, eps))
        elif op == 2:
            ops.append(("relu",))
        elif op == 3:
            k, s = d[p], d[p + 1]; p += 2
            ops.append(("pool", k, s))
        elif op == 4:
            ci, co = struct.unpack("<HH", d[p:p + 4]); p += 4
            sc = struct.unpack("<f", d[p:p + 4])[0]; p += 4
            w = np.frombuffer(d[p:p + ci * co], dtype=np.int8).astype(np.float32) * sc; p += ci * co
            b = np.frombuffer(d[p:p + 4 * co], dtype=np.int32).astype(np.float32); p += 4 * co
            ops.append(("linear", torch.from_numpy(w.reshape(co, ci).copy()), torch.from_numpy(b.copy())))
        elif op == 5:
            ops.append(("l2",))
        elif op == 6:
            ops.append(("gap",))
    return ops


@torch.no_grad()
def embed(ops, x: torch.Tensor) -> torch.Tensor:
    y = x
    for op in ops:
        if op[0] == "conv":
            y = F.conv2d(y, op[1], op[2], stride=op[3], padding=op[4])
        elif op[0] == "bn":
            _, g, be, mu, va, eps = op
            y = (y - torch.from_numpy(mu)[None, :, None, None]) / torch.sqrt(torch.from_numpy(va)[None, :, None, None] + eps)
            y = y * torch.from_numpy(g)[None, :, None, None] + torch.from_numpy(be)[None, :, None, None]
        elif op[0] == "relu":
            y = F.relu(y)
        elif op[0] == "pool":
            y = F.max_pool2d(y, op[1], op[2])
        elif op[0] == "gap":
            y = y.mean(dim=(2, 3))
        elif op[0] == "linear":
            y = y @ op[1].t() + op[2]
        elif op[0] == "l2":
            y = F.normalize(y, dim=1)
    return y


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=str(Path(__file__).resolve().parent.parent.parent
                                           / "app/src/main/assets/models/glyph_embed.bin"))
    ap.add_argument("--samples", type=int, default=3000)
    args = ap.parse_args()

    torch.set_num_threads(2)
    ops = load_model(Path(args.model))
    fonts = ensure_fonts()
    # templates from the app font, as the device builds them
    app_font = next(f for f in fonts if "Caveat[wght]" in f.name)
    # the fonts that will actually ship inside the app (template bank), see assets/fonts
    app_fonts = [f for f in fonts if f.name in (
        "Caveat[wght].ttf", "Neucha.ttf", "PatrickHand-Regular.ttf",
        "MarckScript-Regular.ttf", "BadScript-Regular.ttf")] or [app_font]
    tpl_tiles, tpl_labels = [], []
    rng = random.Random(999)
    for ch in CHARSET:
        for af in app_fonts:
            raw = None
            for _ in range(4):
                raw = __import__("dataset").render_char(af, ch, rng, 96)
                if raw is not None:
                    break
            if raw is None:
                continue
            tpl_tiles.append(dataset_normalise(raw, random.Random(1)))
            tpl_labels.append(CHARSET.index(ch))
    print(f"templates: {len(tpl_tiles)}", flush=True)
    tpl = embed(ops, torch.from_numpy(np.stack(tpl_tiles)[:, None]))
    tpl_labels = np.array(tpl_labels)

    # evaluation samples from all fonts (they are different "hands")
    X, y = [], []
    other = [f for f in fonts if f not in app_fonts]
    tries = 0
    while len(X) < args.samples and tries < args.samples * 40:
        tries += 1
        s = make_sample(other + [app_font], random.Random(1000 + tries))
        if s is None:
            continue
        X.append(s[0]); y.append(s[1])
    print(f"eval samples: {len(X)}", flush=True)
    E = embed(ops, torch.from_numpy(np.stack(X)[:, None])).numpy()
    y = np.array(y)
    # only classes that have a template
    keep = np.isin(y, tpl_labels)
    E, y = E[keep], y[keep]
    sim = E @ tpl.numpy().T
    pred = tpl_labels[sim.argmax(1)]
    acc = float((pred == y).mean())
    top3 = 0
    for i in range(len(y)):
        idx = np.argsort(-sim[i])[:3]
        if y[i] in tpl_labels[idx]:
            top3 += 1
    print(f"1-NN top-1 accuracy (cross-font): {acc * 100:.1f}%  top-3: {top3 / len(y) * 100:.1f}%  n={len(y)}")
    # per-class report for the Russian alphabet
    ru = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    per = []
    for ch in ru:
        idx = [i for i, v in enumerate(y) if v == CHARSET.index(ch)]
        if not idx:
            continue
        a = float((pred[idx] == y[idx]).mean())
        per.append((ch, a))
    wrong = sorted(per, key=lambda t: t[1])[:12]
    print("worst letters:", ", ".join(f"{c}:{a * 100:.0f}%" for c, a in wrong))


def dataset_normalise(arr, rng):
    from dataset import normalise_char
    return normalise_char(arr, rng)


if __name__ == "__main__":
    main()
