"""Model definition (PyTorch) for the glyph embedder."""
from __future__ import annotations

import torch
import torch.nn as nn

from common import EMBED, INPUT


def conv_block(cin: int, cout: int, stride: int = 2) -> nn.Sequential:
    return nn.Sequential(
        nn.Conv2d(cin, cout, 3, stride=stride, padding=1, bias=False),
        nn.BatchNorm2d(cout),
        nn.ReLU(inplace=True),
    )


class GlyphNet(nn.Module):
    """~91k parameters: cheap enough for a phone CPU (a few ms per glyph)."""

    def __init__(self, embed: int = EMBED):
        super().__init__()
        self.features = nn.Sequential(
            conv_block(1, 16),
            conv_block(16, 32),
            conv_block(32, 64),
            conv_block(64, 96),
        )
        self.head = nn.Linear(96, embed)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        f = self.features(x)
        f = f.mean(dim=(2, 3))          # global average pool
        e = self.head(f)
        return torch.nn.functional.normalize(e, dim=1)


class ArcFaceHead(nn.Module):
    """Normalised-softmax classifier used only for training."""

    def __init__(self, embed: int, classes: int, scale: float = 24.0, margin: float = 0.2):
        super().__init__()
        self.weight = nn.Parameter(torch.randn(classes, embed) * 0.01)
        self.scale = scale
        self.margin = margin

    def forward(self, embeddings: torch.Tensor, labels: torch.Tensor) -> torch.Tensor:
        w = torch.nn.functional.normalize(self.weight, dim=1)
        cos = embeddings @ w.t()
        theta = torch.acos(cos.clamp(-1 + 1e-7, 1 - 1e-7))
        target = theta.gather(1, labels.view(-1, 1))
        onehot = torch.zeros_like(cos)
        onehot.scatter_(1, labels.view(-1, 1), 1.0)
        theta = theta * (1 - onehot) + (target + self.margin) * onehot
        return torch.cos(theta) * self.scale
