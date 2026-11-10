package com.handwrite.core.handwriting

import kotlin.math.max
import kotlin.math.min

/**
 * Builds an ink mask from a colour photo of a page.
 *
 * The old pipeline converted the photo to plain grayscale and then divided by a blurred copy.
 * On a squared notebook that fails completely: the grid is about as dark as the pen, so the "ink"
 * turns into a lattice that floods the connected components and the letter crops become pure
 * noise (the app "recognised" a page of `Ё41hf''!l-4`). Blue ballpoint ink, however, differs from
 * the paper in *colour*, not only in brightness, and that is a far stronger cue:
 *
 *  * ink: the blue channel is clearly darker than the local paper lightness;
 *  * grid, paper, table, shadow: neutral - the channels move together.
 *
 * The mask is `blueness x neutrality`, which keeps every pen stroke and drops the grid, the table
 * and the shadow. If the page has no blue ink at all (black pen, pencil), it falls back to the usual
 * darkness division, restricted to the sheet of paper.
 */
object InkMask {

    /** local window for "what colour is the paper around here" */
    private const val WINDOW = 61

    /**
     * @param r,g,b colour channels 0..1, row-major, length = width * height
     * @param out optional receiver for the mask (0..1)
     * @return ink map 0..1 (1 = definitely pen), zero outside the sheet of paper
     */
    fun compute(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        out: FloatArray? = null
    ): FloatArray {
        val n = width * height
        val lum = FloatArray(n)
        val score = FloatArray(n)
        for (i in 0 until n) {
            lum[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
            score[i] = b[i] - g[i]
        }
        // The paper of a squared notebook is already slightly blue, and so is the grid, so the
        // absolute colour difference says nothing. What makes the ink different is that it is
        // *more* blue than the paper around it: subtract the local mean of the same channel
        // difference. Grid, table and shadows are constant in that space and vanish completely.
        val baseline = boxMean(score, width, height, WINDOW)
        for (i in 0 until n) score[i] -= baseline[i]

        val page = pageOf(lum, width, height)
        val hi = max(0.035f, percentile(score, 0.995f))
        val lo = max(0.010f, hi * 0.28f)

        val ink = out ?: FloatArray(n)
        var inkPixels = 0
        for (i in 0 until n) {
            val inside = page.inside(i % width, i / width)
            val v = if (inside) ramp(score[i], lo, hi) else 0f
            ink[i] = v
            if (v > 0.5f) inkPixels++
        }
        // no blue ink on the page: black pen or pencil, use local darkness instead
        if (inkPixels < n / 5000) {
            val dark0 = boxMean(lum, width, height, WINDOW)
            for (i in 0 until n) {
                if (!page.inside(i % width, i / width)) {
                    ink[i] = 0f
                    continue
                }
                val dark = ramp(dark0[i] - lum[i], 0.16f, 0.40f)
                ink[i] = max(ink[i], dark)
            }
        }
        return ink
    }

    /** value at the given quantile (0..1) of a map */
    fun percentile(v: FloatArray, q: Float): Float {
        val bins = 512
        val hist = IntArray(bins)
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (x in v) {
            if (x < minV) minV = x
            if (x > maxV) maxV = x
        }
        if (maxV <= minV) return maxV
        val k = (bins - 1) / (maxV - minV)
        for (x in v) hist[((x - minV) * k).toInt().coerceIn(0, bins - 1)]++
        val target = (v.size * q).toInt().coerceAtLeast(1)
        var acc = 0
        for (i in 0 until bins) {
            acc += hist[i]
            if (acc >= target) return minV + i / k
        }
        return maxV
    }

    /** bounding box of the sheet; strokes outside it belong to the table or the background */
    class PageBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        fun inside(x: Int, y: Int) = x in x0..x1 && y in y0..y1
        val width get() = x1 - x0 + 1
        val height get() = y1 - y0 + 1
    }

    /**
     * Finds the paper: the paper is the largest bright area of the photo. The bound is eroded a
     * little so the desk around it never counts as a page, then grown back with a small opening so
     * a bright desk stripe cannot join the sheet.
     */
    fun pageOf(grey: FloatArray, width: Int, height: Int): PageBox {
        val level = otsu(grey)
        val thr = max(level, 0.42f)
        val bright = BooleanArray(width * height) { grey[it] > thr }
        val comp = components(bright, width, height)
        val label = comp[0] as IntArray
        val count = comp[1] as Int
        val boxes = comp[2] as IntArray
        if (count == 0) return PageBox(0, 0, width - 1, height - 1)
        var best = 0
        var bestArea = 0
        for (c in 0 until count) {
            val a = (boxes[c * 4 + 2] - boxes[c * 4] + 1) * (boxes[c * 4 + 3] - boxes[c * 4 + 1] + 1)
            if (a > bestArea) {
                bestArea = a; best = c
            }
        }
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (label[y * width + x] == best) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return PageBox(0, 0, width - 1, height - 1)
        // erode by 1.5 % of the sheet so the table edge is excluded from the ink area
        val insetX = max(2, (maxX - minX) / 70)
        val insetY = max(2, (maxY - minY) / 70)
        return PageBox(
            (minX + insetX).coerceAtMost(maxX),
            (minY + insetY).coerceAtMost(maxY),
            (maxX - insetX).coerceAtLeast(minX),
            (maxY - insetY).coerceAtLeast(minY)
        )
    }

    /* --------------------------- helpers --------------------------- */

    private fun ramp(x: Float, lo: Float, hi: Float): Float = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)

    /** Otsu threshold over a 0..1 map */
    fun otsu(v: FloatArray): Float {
        val bins = 128
        val hist = IntArray(bins)
        for (x in v) hist[(x.coerceIn(0f, 1f) * (bins - 1)).toInt()]++
        val total = v.size
        var sum = 0.0
        for (i in 0 until bins) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var thr = 0.5f
        for (i in 0 until bins) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between; thr = (i + 0.5f) / bins
            }
        }
        return thr
    }

    /** box mean filter using a summed-area table (O(1) per pixel) */
    fun boxMean(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val r = max(1, radius / 2)
        val sw = width + 1
        val sat = DoubleArray(sw * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0.0
            for (x in 0 until width) {
                rowSum += src[y * width + x]
                sat[(y + 1) * sw + x + 1] = sat[y * sw + x + 1] + rowSum
            }
        }
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val y0 = max(0, y - r)
            val y1 = min(height - 1, y + r)
            for (x in 0 until width) {
                val x0 = max(0, x - r)
                val x1 = min(width - 1, x + r)
                val sum = sat[(y1 + 1) * sw + x1 + 1] - sat[y0 * sw + x1 + 1] -
                    sat[(y1 + 1) * sw + x0] + sat[y0 * sw + x0]
                out[y * width + x] = (sum / ((y1 - y0 + 1).toDouble() * (x1 - x0 + 1))).toFloat()
            }
        }
        return out
    }

    /** 4-connected component labelling; returns labels, count and per-component bbox (x0,y0,x1,y1) */
    private fun components(bin: BooleanArray, width: Int, height: Int): Array<Any> {
        val label = IntArray(width * height) { -1 }
        val boxes = ArrayList<Int>()
        val stack = IntArray(width * height)
        var count = 0
        for (start in bin.indices) {
            if (!bin[start] || label[start] >= 0) continue
            val id = count++
            var sp = 0
            stack[sp++] = start
            label[start] = id
            var x0 = Int.MAX_VALUE; var x1 = -1; var y0 = Int.MAX_VALUE; var y1 = -1
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % width
                val py = p / width
                if (px < x0) x0 = px; if (px > x1) x1 = px
                if (py < y0) y0 = py; if (py > y1) y1 = py
                if (px > 0) {
                    val q = p - 1
                    if (bin[q] && label[q] < 0) { label[q] = id; stack[sp++] = q }
                }
                if (px < width - 1) {
                    val q = p + 1
                    if (bin[q] && label[q] < 0) { label[q] = id; stack[sp++] = q }
                }
                if (py > 0) {
                    val q = p - width
                    if (bin[q] && label[q] < 0) { label[q] = id; stack[sp++] = q }
                }
                if (py < height - 1) {
                    val q = p + width
                    if (bin[q] && label[q] < 0) { label[q] = id; stack[sp++] = q }
                }
            }
            boxes.add(x0); boxes.add(y0); boxes.add(x1); boxes.add(y1)
        }
        return arrayOf(label, count, boxes.toIntArray())
    }
}
