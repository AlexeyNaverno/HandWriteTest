package com.handwrite.core.handwriting

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device glyph recogniser (pure CPU, no native code, no network).
 *
 * The binary blob in `assets/models/glyph_embed.bin` holds a small quantised CNN
 * (int8 weights + float scale, batch-norm folded at export time).
 * [embed] turns a 64x64 ink tile into a 128-dim unit vector; the caller compares it with
 * pre-computed embeddings of ideal glyph templates (see [GlyphAtlas]).
 *
 * Throughput: ~91k MACs per tile, i.e. a few milliseconds on a mid-range phone.
 */
class HandwriteModel @JvmOverloads constructor(
    val input: Int,
    val embedSize: Int,
    val charset: String,
    val layers: List<Layer>
) {

    sealed class Layer
    class Conv(val cin: Int, val cout: Int, val stride: Int, val pad: Int, val scale: Float, val w: ByteArray, var bias: FloatArray?) : Layer()
    class BatchNorm(val gamma: FloatArray, val beta: FloatArray, val mean: FloatArray, val varArr: FloatArray, val eps: Float) : Layer()
    class Linear(val cin: Int, val cout: Int, val scale: Float, val w: ByteArray) : Layer()
    object Relu : Layer()
    class MaxPool(val k: Int, val stride: Int) : Layer()
    object GlobalAvg : Layer()
    object L2Norm : Layer()

    /** Runs the network on one 64x64 tile (values 0..1, row-major). */
    fun embed(tile: FloatArray): FloatArray {
        var x = tile.copyOf()
        var w = input
        var h = input
        var c = 1
        for (layer in layers) {
            when (layer) {
                is Conv -> {
                    val ow = (w + 2 * layer.pad - 3) / layer.stride + 1
                    val oh = (h + 2 * layer.pad - 3) / layer.stride + 1
                    val out = FloatArray(ow * oh * layer.cout)
                    val bias = layer.bias
                    val ws = layer.scale
                    for (co in 0 until layer.cout) {
                        val wBase = co * layer.cin * 9
                        val b = bias?.get(co) ?: 0f
                        for (oy in 0 until oh) {
                            val iy0 = oy * layer.stride - layer.pad
                            for (ox in 0 until ow) {
                                val ix0 = ox * layer.stride - layer.pad
                                var acc = b
                                for (ci in 0 until layer.cin) {
                                    val ciBase = ci * 9 + wBase
                                    for (ky in 0 until 3) {
                                        val iy = iy0 + ky
                                        if (iy < 0 || iy >= h) continue
                                        val rowBase = (iy * w + ix0) * c + ci
                                        for (kx in 0 until 3) {
                                            val ix = ix0 + kx
                                            if (ix < 0 || ix >= w) continue
                                            val v = x[rowBase + kx * c]
                                            acc += v * (layer.w[ciBase + ky * 3 + kx].toInt() * ws)
                                        }
                                    }
                                }
                                out[(oy * ow + ox) * layer.cout + co] = acc
                            }
                        }
                    }
                    x = out
                    w = ow
                    h = oh
                    c = layer.cout
                }
                is BatchNorm -> {
                    // channel-major statistics (same layout as PyTorch's BatchNorm2d over NCHW)
                    val n = w * h * c
                    for (ch in 0 until c) {
                        val inv = 1f / sqrt(layer.varArr[ch] + layer.eps)
                        for (i in ch until n step c) {
                            x[i] = (x[i] - layer.mean[ch]) * inv * layer.gamma[ch] + layer.beta[ch]
                        }
                    }
                }
                Relu -> for (i in x.indices) if (x[i] < 0f) x[i] = 0f
                is MaxPool -> {
                    val ow = (w - layer.k) / layer.stride + 1
                    val oh = (h - layer.k) / layer.stride + 1
                    val out = FloatArray(ow * oh * c)
                    for (oy in 0 until oh) for (ox in 0 until ow) for (ch in 0 until c) {
                        var m = -Float.MAX_VALUE
                        for (ky in 0 until layer.k) for (kx in 0 until layer.k) {
                            val v = x[((oy * layer.stride + ky) * w + (ox * layer.stride + kx)) * c + ch]
                            if (v > m) m = v
                        }
                        out[(oy * ow + ox) * c + ch] = m
                    }
                    x = out
                    w = ow
                    h = oh
                }
                GlobalAvg -> {
                    val out = FloatArray(c)
                    val n = w * h
                    for (i in 0 until n) for (ch in 0 until c) out[ch] += x[i * c + ch]
                    for (ch in 0 until c) out[ch] /= n.toFloat()
                    x = out
                    w = 1
                    h = 1
                }
                is Linear -> {
                    val out = FloatArray(layer.cout)
                    val ws = layer.scale
                    for (o in 0 until layer.cout) {
                        var acc = 0f
                        val base = o * layer.cin
                        for (i in 0 until layer.cin) acc += x[i] * (layer.w[base + i].toInt() * ws)
                        out[o] = acc
                    }
                    x = out
                    c = layer.cout
                    w = 1
                    h = 1
                }
                L2Norm -> {
                    var s = 0f
                    for (v in x) s += v * v
                    val inv = 1f / sqrt(max(s, 1e-8f))
                    for (i in x.indices) x[i] *= inv
                }
            }
        }
        return x
    }

    companion object {
        private const val MAGIC = "HWNN"

        @Volatile
        private var cached: HandwriteModel? = null

        fun load(bytes: ByteArray): HandwriteModel {
            val r = ByteReader(bytes)
            val magic = String(r.bytes(4), Charsets.US_ASCII)
            require(magic == MAGIC) { "not a HandWrite model" }
            val version = r.u16()
            require(version == 1) { "unsupported model version $version" }
            val input = r.u16()
            val embed = r.u16()
            val charCount = r.u32()
            val sb = StringBuilder()
            for (i in 0 until charCount) sb.appendCodePoint(r.u32())
            val layerCount = r.u16()
            val layers = ArrayList<Layer>(layerCount)
            for (i in 0 until layerCount) {
                when (val op = r.u8()) {
                    0 -> { // conv
                        val cin = r.u16()
                        val cout = r.u16()
                        val stride = r.u8()
                        val pad = r.u8()
                        val scale = r.f32()
                        val w = r.bytes(cin * cout * 9)
                        r.skip(4 * cout) // bias (zeros)
                        layers.add(Conv(cin, cout, stride, pad, scale, w, null))
                    }
                    1 -> { // batchnorm
                        val ch = r.u16()
                        val g = r.f32array(ch)
                        val b = r.f32array(ch)
                        val mu = r.f32array(ch)
                        val va = r.f32array(ch)
                        val eps = r.f32()
                        layers.add(BatchNorm(g, b, mu, va, eps))
                    }
                    2 -> layers.add(Relu)
                    3 -> layers.add(MaxPool(r.u8(), r.u8()))
                    4 -> { // linear
                        val cin = r.u16()
                        val cout = r.u16()
                        val scale = r.f32()
                        val w = r.bytes(cin * cout)
                        r.skip(4 * cout)
                        layers.add(Linear(cin, cout, scale, w))
                    }
                    5 -> layers.add(L2Norm)
                    6 -> layers.add(GlobalAvg)
                    else -> error("unknown operator $op")
                }
            }
            return HandwriteModel(input, embed, sb.toString(), layers)
        }
    }

    private class ByteReader(val b: ByteArray) {
        var p = 0
        fun u8(): Int = b[p++].toInt() and 0xFF
        fun u16(): Int {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
            p += 2
            return v
        }
        fun u32(): Int {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        fun f32(): Float = Float.fromBits(u32())
        fun f32array(n: Int): FloatArray = FloatArray(n) { f32() }
        fun bytes(n: Int): ByteArray {
            val out = b.copyOfRange(p, p + n)
            p += n
            return out
        }
        fun skip(n: Int) { p += n }
    }
}

/** Small helpers shared by the handwriting pipeline. */
object InkMath {
    /** resizes a grayscale tile to [size]x[size] with the ink centred (bilinear) */
    fun resizeCentred(src: FloatArray, sw: Int, sh: Int, size: Int): FloatArray {
        var minX = sw; var minY = sh; var maxX = -1; var maxY = -1
        for (y in 0 until sh) for (x in 0 until sw) {
            if (src[y * sw + x] > 0.25f) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        val out = FloatArray(size * size)
        if (maxX < 0) return out
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val scale = (size - 6f) / max(bw, bh)
        val dw = max(1, (bw * scale).toInt())
        val dh = max(1, (bh * scale).toInt())
        val ox = (size - dw) / 2
        val oy = (size - dh) / 2
        for (y in 0 until dh) {
            val sy = minY + (y / scale).toInt().coerceIn(0, bh - 1)
            for (x in 0 until dw) {
                val sx = minX + (x / scale).toInt().coerceIn(0, bw - 1)
                out[(oy + y) * size + ox + x] = src[sy * sw + sx]
            }
        }
        return out
    }

    fun softmax(logits: FloatArray): FloatArray {
        var m = -Float.MAX_VALUE
        for (v in logits) if (v > m) m = v
        val out = FloatArray(logits.size)
        var s = 0f
        for (i in logits.indices) {
            out[i] = exp((logits[i] - m).toDouble()).toFloat()
            s += out[i]
        }
        if (s <= 0f) return out
        for (i in out.indices) out[i] /= s
        return out
    }
}
