package com.handwrite.core.handwriting

import com.handwrite.core.font.GlyphSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rasterises a glyph outline into the 64x64 "ink tile" that the neural network expects.
 *
 * The same code runs on the phone (to build reference templates from the bundled font) and in
 * the training/ verification scripts, so the templates used for matching are pixel-identical to
 * the ones the network was trained on.
 */
object TileRasterizer {

    const val SIZE = 64

    /** ink tile (0..1 per pixel), glyph centred and scaled the same way photo crops are */
    fun tile(source: GlyphSource, gid: Int, coords: Map<String, Float> = emptyMap()): FloatArray {
        val path = source.path(gid, coords)
        val w = path.maxX - path.minX
        val h = path.maxY - path.minY
        if (w <= 0f || h <= 0f) return FloatArray(SIZE * SIZE)
        val target = (SIZE - 6).toFloat()
        val scale = target / max(w, h)
        val ox = (SIZE - w * scale) / 2f - path.minX * scale
        val oy = (SIZE - h * scale) / 2f - path.minY * scale
        val out = FloatArray(SIZE * SIZE)
        // the scanline filler works on the flattened polygon set; the non-zero winding rule keeps
        // the counters (holes) of "о", "а", "8", ... open
        val polys = path.flatten(0.25f).map { flat ->
            FloatArray(flat.size) { i -> if (i % 2 == 0) flat[i] * scale + ox else flat[i] * scale + oy }
        }
        fill(polys, out, 3)
        return out
    }

    /** non-zero winding scanline fill with supersampling */
    fun fill(polygons: List<FloatArray>, out: FloatArray, ss: Int = 3) {
        if (polygons.isEmpty()) return
        val h = SIZE
        val w = SIZE
        val acc = FloatArray(w * h)
        for (sy in 0 until h * ss) {
            val y = (sy + 0.5f) / ss
            // gather crossings
            val xs = ArrayList<Float>()
            val dirs = ArrayList<Int>()
            for (poly in polygons) {
                val n = poly.size / 2
                if (n < 3) continue
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val x0 = poly[i * 2]
                    val y0 = poly[i * 2 + 1]
                    val x1 = poly[j * 2]
                    val y1 = poly[j * 2 + 1]
                    if (y0 == y1) continue
                    val ymin = min(y0, y1)
                    val ymax = max(y0, y1)
                    if (y < ymin || y >= ymax) continue
                    val t = (y - y0) / (y1 - y0)
                    xs.add(x0 + (x1 - x0) * t)
                    dirs.add(if (y1 > y0) 1 else -1)
                }
            }
            if (xs.isEmpty()) continue
            // sort crossings
            val order = xs.indices.sortedBy { xs[it] }
            var winding = 0
            var prevX = 0f
            for (idx in order) {
                val cx = xs[idx]
                if (winding != 0 && cx > prevX) {
                    // fill span [prevX, cx)
                    var px = (prevX * ss).toInt()
                    val pxEnd = (cx * ss).toInt()
                    while (px < pxEnd) {
                        val gx = px
                        if (gx >= 0 && gx < w * ss) acc[(sy / ss) * w + (gx / ss)] += 1f
                        px++
                    }
                }
                winding += dirs[idx]
                prevX = cx
            }
        }
        val norm = 1f / (ss * ss)
        for (i in out.indices) out[i] = min(1f, acc[i] * norm)
    }

    /** simple tile statistics used to reject empty crops */
    fun inkCoverage(tile: FloatArray): Float {
        var s = 0f
        for (v in tile) if (v > 0.3f) s += 1f
        return s / tile.size
    }

    fun isEmpty(tile: FloatArray): Boolean = inkCoverage(tile) < 0.01f

    fun distance(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += abs(d)
        }
        return s / a.size
    }
}
