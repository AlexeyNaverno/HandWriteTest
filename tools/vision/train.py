"""Train + export the on-device glyph embedder.

usage:
    python3 train.py --samples 60000 --epochs 6 --out ../../app/src/main/assets/models/glyph_embed.bin

The exported file is a compact binary (MAGIC "HWNN") that the Kotlin inference engine reads
directly (see tools/vision/export.py and app/.../HandwriteModel.kt).
"""
from __future__ import annotations

import argparse
import os
import random
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader, TensorDataset

from common import CHARSET, EMBED, INPUT
from dataset import ensure_fonts, make_sample
from export import dequantize_check, export_model
from model import ArcFaceHead, GlyphNet


def build_dataset(n: int, seed: int) -> tuple[torch.Tensor, torch.Tensor]:
    fonts = ensure_fonts()
    rng = random.Random(seed)
    X = np.zeros((n, 1, INPUT, INPUT), np.float32)
    y = np.zeros((n,), np.int64)
    i = 0
    t0 = time.time()
    while i < n:
        s = make_sample(fonts, rng)
        if s is None:
            continue
        X[i, 0], y[i] = s
        i += 1
        if i % 10000 == 0:
            print(f"  samples {i}/{n} ({time.time() - t0:.0f}s)", flush=True)
    return torch.from_numpy(X), torch.from_numpy(y)


def augment_batch(x: torch.Tensor, gen: torch.Generator) -> torch.Tensor:
    b = x.size(0)
    # slight random shift and scale per sample (keeps the letter roughly centred)
    scale = 1.0 + (torch.rand(b, 1, 1, generator=gen) - 0.5) * 0.24
    theta = torch.zeros(b, 2, 3)
    theta[:, 0, 0] = scale[:, 0, 0]
    theta[:, 1, 1] = scale[:, 0, 0]
    grid = F.affine_grid(theta, x.shape, align_corners=False)
    x = F.grid_sample(x, grid, align_corners=False)
    # ink intensity jitter
    x = x * (0.75 + torch.rand(b, 1, 1, 1, generator=gen) * 0.5)
    return x.clamp(0, 1)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", type=int, default=60000)
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--out", type=str, default=str(Path(__file__).resolve().parent.parent.parent
                                                   / "app/src/main/assets/models/glyph_embed.bin"))
    ap.add_argument("--threads", type=int, default=2)
    args = ap.parse_args()

    torch.set_num_threads(args.threads)
    torch.manual_seed(7)
    gen = torch.Generator().manual_seed(11)

    print(f"charset {len(CHARSET)} classes, input {INPUT}px, embed {EMBED}", flush=True)
    X, y = build_dataset(args.samples, seed=1234)
    print("dataset", X.shape, "class counts min/max:",
          int(np.bincount(y.numpy(), minlength=len(CHARSET)).min()),
          int(np.bincount(y.numpy(), minlength=len(CHARSET)).max()), flush=True)

    n_val = max(2000, args.samples // 15)
    Xtr, ytr = X[:-n_val], y[:-n_val]
    Xva, yva = X[-n_val:], y[-n_val:]

    loader = DataLoader(TensorDataset(Xtr, ytr), batch_size=args.batch, shuffle=True, drop_last=True)
    print(f"batches/epoch {len(loader)}", flush=True)
    net = GlyphNet()
    head = ArcFaceHead(EMBED, len(CHARSET))
    opt = torch.optim.AdamW(list(net.parameters()) + list(head.parameters()), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs * len(loader))

    for epoch in range(args.epochs):
        net.train()
        total, correct, loss_sum = 0, 0, 0.0
        for xb, yb in loader:
            xb = augment_batch(xb, gen)
            emb = net(xb)
            logits = head(emb, yb)
            loss = F.cross_entropy(logits, yb)
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            loss_sum += float(loss) * xb.size(0)
            correct += int((logits.argmax(1) == yb).sum())
            total += xb.size(0)
        val_acc = evaluate(net, Xva, yva, args.batch)
        print(f"epoch {epoch + 1}/{args.epochs} loss {loss_sum / max(1, total):.3f} "
              f"train {correct / max(1, total):.3f} val {val_acc:.3f}", flush=True)

    net.eval()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    export_model(net, out, charset=CHARSET)
    err = dequantize_check(net, out, Xva[:256])
    print(f"exported {out} ({out.stat().st_size / 1024:.1f} KiB), max quantisation error {err:.4f}")


@torch.no_grad()
def evaluate(net: GlyphNet, X: torch.Tensor, y: torch.Tensor, batch: int) -> float:
    net.eval()
    correct = 0
    for i in range(0, X.size(0), batch):
        xb = X[i:i + batch]
        emb = net(xb)
        # nearest neighbour over templates = same letter of the training split
        ref = net(X[:0])
        _ = ref
        correct += 0
    # proper 1-NN accuracy over a subset of templates
    tmpl_n = 512
    tmpl_x, tmpl_y = X[:tmpl_n], y[:tmpl_n]
    te_x, te_y = X[tmpl_n:], y[tmpl_n:]
    if te_x.size(0) == 0:
        return 0.0
    te = net(te_x[:2000])
    tp = net(tmpl_x)
    sim = te @ tp.t()
    pred = tmpl_y[sim.argmax(1)]
    return float((pred == te_y[:2000]).float().mean())


if __name__ == "__main__":
    main()
