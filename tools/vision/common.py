"""Shared definitions for the HandWrite on-device glyph recogniser.

The model is a small convolutional embedding network. It is trained on synthetic
renderings of many handwriting fonts with heavy augmentation, so that two samples of
the same letter written by different hands land close to each other in embedding space.
On the device the embedding of a photo crop is compared (cosine) with embeddings of
"ideal" glyph templates rendered from the bundled font; the best match gives the letter.
"""
from __future__ import annotations

CHARSET = (
    "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    "abcdefghijklmnopqrstuvwxyz"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "0123456789"
    ".,!?;:-—()\"'«»…@#№"
)

INPUT = 64
EMBED = 128

MAGIC = b"HWNN"
VERSION = 1

# op codes used by the binary format
OP_CONV = 0
OP_BN = 1
OP_RELU = 2
OP_MAXPOOL = 3
OP_LINEAR = 4
OP_L2NORM = 5
OP_GAP = 6
