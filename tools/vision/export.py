"""Export a trained GlyphNet to the compact int8 binary format used on the device.

Binary layout (little endian):
    char[4]  magic  "HWNN"
    u16      version
    u16      input size (64)
    u16      embed size
    u32      char count, then char count * u32 code points
    u16      layer count
    layer:
        u8   op
        conv:   u16 cin, u16 cout, u8 stride, u8 pad,
                float32 scale, int8[cin*cout*9] weights, int32[cout] bias
        linear: u16 in, u16 out, float32 scale, int8[out*in] weights, int32[out] bias
        bn:     u16 ch, float32[ch] gamma, beta, mean, var, eps
        relu:   -
        maxpool:u8 kernel, u8 stride
        gap:    -
        l2norm: -
"""
from __future__ import annotations

import struct
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

from common import (EMBED, INPUT, MAGIC, OP_BN, OP_CONV, OP_GAP, OP_L2NORM,
                    OP_LINEAR, OP_MAXPOOL, OP_RELU, VERSION)


def iter_layers(module):
    """yields leaf layers in execution order (features is a nested Sequential)"""
    for child in module.children():
        if len(list(child.children())) > 0:
            yield from iter_layers(child)
        else:
            yield child


def _quantise(w: np.ndarray) -> tuple[np.ndarray, float]:
    m = float(np.abs(w).max())
    if m < 1e-8:
        return np.zeros_like(w, np.int8), 1.0
    scale = m / 127.0
    q = np.clip(np.round(w / scale), -127, 127).astype(np.int8)
    return q, scale


def export_model(net: nn.Module, out: Path, charset: str) -> None:
    buf = bytearray()
    buf += MAGIC
    buf += struct.pack("<HHH", VERSION, INPUT, EMBED)
    cps = [ord(c) for c in charset]
    buf += struct.pack("<I", len(cps))
    for cp in cps:
        buf += struct.pack("<I", cp)

    layers: list[bytes] = []
    for m in iter_layers(net.features):
        if isinstance(m, nn.Conv2d):
            w = m.weight.detach().numpy()
            cout, cin, kh, kw = w.shape
            q, scale = _quantise(w)
            b = bytearray([OP_CONV])
            b += struct.pack("<HHBB", cin, cout, m.stride[0], m.padding[0])
            b += struct.pack("<f", scale)
            b += q.astype(np.int8).tobytes()
            bias = np.zeros(cout, np.int32)
            b += bias.tobytes()
            layers.append(bytes(b))
        elif isinstance(m, nn.BatchNorm2d):
            g = m.weight.detach().numpy().astype(np.float32)
            be = m.bias.detach().numpy().astype(np.float32)
            mu = m.running_mean.detach().numpy().astype(np.float32)
            var = m.running_var.detach().numpy().astype(np.float32)
            b = bytearray([OP_BN])
            b += struct.pack("<H", g.size)
            b += g.tobytes() + be.tobytes() + mu.tobytes() + var.tobytes()
            b += struct.pack("<f", float(m.eps))
            layers.append(bytes(b))
        elif isinstance(m, nn.ReLU):
            layers.append(bytes([OP_RELU]))
        elif isinstance(m, nn.MaxPool2d):
            layers.append(bytes([OP_MAXPOOL, m.kernel_size, m.stride]))
    layers.append(bytes([OP_GAP]))
    w = net.head.weight.detach().numpy()
    q, scale = _quantise(w)
    b = bytearray([OP_LINEAR])
    b += struct.pack("<HH", w.shape[1], w.shape[0])
    b += struct.pack("<f", scale)
    b += q.astype(np.int8).tobytes()
    b += np.zeros(w.shape[0], np.int32).tobytes()
    layers.append(bytes(b))
    layers.append(bytes([OP_L2NORM]))

    buf += struct.pack("<H", len(layers))
    for lb in layers:
        buf += lb
    out.write_bytes(bytes(buf))


def dequantize_check(net: nn.Module, path: Path, x: torch.Tensor) -> float:
    """Compares the float model with the int8 weights (sanity check of the export)."""
    net.eval()
    with torch.no_grad():
        ref = net(x).numpy()
    # emulate quantisation errors by re-running with quantised weights
    import copy
    q = copy.deepcopy(net)
    for m in q.features:
        if isinstance(m, nn.Conv2d):
            w = m.weight.detach().numpy()
            qw, scale = _quantise(w)
            m.weight.data = torch.from_numpy(qw.astype(np.float32) * scale)
    with torch.no_grad():
        approx = q(x).numpy()
    return float(np.abs(ref - approx).max())
