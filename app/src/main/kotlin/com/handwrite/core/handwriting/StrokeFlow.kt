package com.handwrite.core.handwriting

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reconstructs how the pen moved through a letter, so the letter can be joined to its neighbours
 * the way the original handwriting was joined.
 *
 * The input is a bitmap crop (64x64 ink tile). The steps are the classic skeleton/serif analysis
 * used by "handwriting to font" tools:
 *
 *  1. **skeleton** — thinning until one pixel wide, so a stroke is a curve instead of a blob;
 *  2. **graph** — skeleton pixels become nodes, 8-neighbour links become edges;
 *  3. **endpoints** — nodes of degree 1 are the places where the pen touched down / lifted;
 *  4. **direction** — walking a few pixels along the stroke from an endpoint gives the *movement
 *     vector* there (this is what the user asked for: "как шли вектора движений");
 *  5. **entry / exit** — of the bottom-most endpoints (a cursive join happens at the baseline) the
 *     right one is the letter's **exit** (where the pen leaves towards the next letter) and the
 *     left one is its **entry** (where the previous letter's stroke arrives).
 *
 * A connector between two letters is then drawn as a cubic that leaves the exit *along its own
 * movement vector*, dips to the baseline and enters the next letter *against its entry vector* -
 * the curve therefore continues the stroke instead of being a separate tick between two boxes.
 */
object StrokeFlow {

    /** An endpoint of the skeleton together with the direction the pen moves at that point. */
    class Terminal(
        val x: Float,
        val y: Float,
        /** unit vector of the pen movement at this point (x right, y down - tile coordinates) */
        val dx: Float,
        val dy: Float,
        /** how far the stroke travels inside the letter from this point, in pixels */
        val length: Float
    )

    /**
     * Pen entry (left) and exit (right) of a letter.
     *
     * Coordinates are normalised to 0..1 over the tile (x right, y down), matching [TileRasterizer].
     */
    class Flow(val entry: Terminal?, val exit: Terminal?) {
        val hasEntry: Boolean get() = entry != null
        val hasExit: Boolean get() = exit != null
    }

    private val EMPTY = Flow(null, null)
    private val cache = HashMap<Int, Flow>()

    /** Flow of a stored atlas sample (cached: the skeleton is the expensive part). */
    fun flowOf(sample: GlyphAtlas.Sample): Flow {
        val key = System.identityHashCode(sample)
        synchronized(cache) {
            cache[key]?.let { return it }
            val f = analyse(sample.tile, TileRasterizer.SIZE)
            if (cache.size > 4000) cache.clear()
            cache[key] = f
            return f
        }
    }

    /**
     * Analyses one ink tile.
     *
     * @param tile 64x64 values 0..1 (ink), row-major, as produced by [TileRasterizer] and the segmenter
     */
    fun analyse(tile: FloatArray, size: Int = TileRasterizer.SIZE): Flow {
        val mask = BooleanArray(size * size) { tile[it] > 0.35f }
        var minX = size
        var maxX = -1
        var minY = size
        var maxY = -1
        for (y in 0 until size) for (x in 0 until size) {
            if (!mask[y * size + x]) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (maxX < 0) return EMPTY
        val skel = thin(mask, size)
        val band = maxY - (maxY - minY) * 0.30f          // joins live near the baseline
        // lowest skeleton point of each half of the letter: the pen enters on the left and leaves
        // on the right. Using the *lowest* point (not the extreme one) is what makes a join look
        // like it comes off the baseline instead of the middle of the letter.
        var left: IntArray? = null
        var right: IntArray? = null
        val midX = (minX + maxX) / 2f
        for (y in 0 until size) for (x in 0 until size) {
            if (!skel[y * size + x]) continue
            if (y < band) continue
            if (x <= midX) {
                val cur = left
                if (cur == null || y > cur[1] || (y == cur[1] && x < cur[0])) left = intArrayOf(x, y)
            } else {
                val cur = right
                if (cur == null || y > cur[1] || (y == cur[1] && x > cur[0])) right = intArrayOf(x, y)
            }
        }
        // letters such as "о" have no free stroke end at the baseline: fall back to the bbox corners
        val entryPoint = left ?: intArrayOf(minX, maxY - 2)
        val exitPoint = right ?: intArrayOf(maxX, maxY - 2)
        val entry = terminal(skel, size, entryPoint[1] * size + entryPoint[0], outward = floatArrayOf(-0.2f, 1f))
        val exit = terminal(skel, size, exitPoint[1] * size + exitPoint[0], outward = floatArrayOf(0.2f, 1f))
        return Flow(entry, exit)
    }

    /* ------------------------------------------------------------------ */
    /* skeletonisation                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Zhang-Suen thinning: removes pixels from the border until the shape is one pixel wide.
     * Works offline, ~1 ms for a 64x64 crop on a phone.
     */
    fun thin(src: BooleanArray, size: Int): BooleanArray {
        val img = src.copyOf()
        val toClear = ArrayList<Int>(size * size / 4)
        var changed = true
        var guard = 0
        while (changed && guard++ < 40) {
            changed = false
            for (phase in 0..1) {
                toClear.clear()
                for (y in 1 until size - 1) {
                    for (x in 1 until size - 1) {
                        val i = y * size + x
                        if (!img[i]) continue
                        val p2 = img[i - size]
                        val p3 = img[i - size + 1]
                        val p4 = img[i + 1]
                        val p5 = img[i + size + 1]
                        val p6 = img[i + size]
                        val p7 = img[i + size - 1]
                        val p8 = img[i - 1]
                        val p9 = img[i - size - 1]
                        val bp = (if (p2) 1 else 0) + (if (p3) 1 else 0) + (if (p4) 1 else 0) + (if (p5) 1 else 0) +
                            (if (p6) 1 else 0) + (if (p7) 1 else 0) + (if (p8) 1 else 0) + (if (p9) 1 else 0)
                        if (bp < 2 || bp > 6) continue
                        val seq = booleanArrayOf(p2, p3, p4, p5, p6, p7, p8, p9, p2)
                        var transitions = 0
                        for (k in 0 until 8) if (!seq[k] && seq[k + 1]) transitions++
                        if (transitions != 1) continue
                        val a = if (phase == 0) p2 && p4 && p6 else p2 && p4 && p8
                        val b = if (phase == 0) p4 && p6 && p8 else p2 && p6 && p8
                        if (a || b) continue
                        toClear.add(i)
                    }
                }
                if (toClear.isNotEmpty()) {
                    changed = true
                    for (i in toClear) img[i] = false
                }
            }
        }
        return img
    }

    /** skeleton points with exactly one neighbour */
    private fun endpoints(skel: BooleanArray, size: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (y in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val i = y * size + x
                if (!skel[i]) continue
                if (neighbours(skel, size, x, y) == 1) out.add(i to y)
            }
        }
        return out
    }

    private fun neighbours(skel: BooleanArray, size: Int, x: Int, y: Int): Int {
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
            if (skel[ny * size + nx]) n++
        }
        return n
    }

    /**
     * Computes the pen direction at an endpoint by walking [steps] pixels along the skeleton and
     * taking the vector from the end of that walk back towards the endpoint: this is the direction
     * in which the pen *leaves* the letter at this terminal.
     */
    private fun terminal(
        skel: BooleanArray,
        size: Int,
        startIdx: Int,
        steps: Int = 7,
        outward: FloatArray = floatArrayOf(1f, 0f)
    ): Terminal? {
        val sx = startIdx % size
        val sy = startIdx / size
        var x = sx
        var y = sy
        var px = -1
        var py = -1
        var travelled = 0f
        for (step in 0 until steps) {
            var bestX = -1
            var bestY = -1
            var bestD = -1f
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
                if (nx == px && ny == py) continue
                if (!skel[ny * size + nx]) continue
                val d = (dx * dx + dy * dy).toFloat()
                if (d > bestD) { bestD = d; bestX = nx; bestY = ny }
            }
            if (bestX < 0) break
            val ddx = (bestX - x).toFloat()
            val ddy = (bestY - y).toFloat()
            travelled += sqrt(ddx * ddx + ddy * ddy)
            px = x
            py = y
            x = bestX
            y = bestY
            if (travelled >= steps) break
        }
        val vx = sx - x
        val vy = sy - y
        val len = sqrt((vx * vx + vy * vy).toFloat())
        if (len < 0.5f) {
            // no stroke leaves this point (closed loop, or a hook that doubles back): keep the
            // caller's outward direction so the join still leaves the letter downwards/outwards
            val ol = max(0.001f, sqrt(outward[0] * outward[0] + outward[1] * outward[1]))
            return Terminal(sx.toFloat() / size, sy.toFloat() / size, outward[0] / ol, outward[1] / ol, travelled)
        }
        return Terminal(sx.toFloat() / size, sy.toFloat() / size, vx / len, vy / len, travelled)
    }

    /** debug helper: degree histogram and endpoint list of the skeleton */
    fun debugSkeleton(tile: FloatArray, size: Int = TileRasterizer.SIZE): String {
        val mask = BooleanArray(size * size) { tile[it] > 0.35f }
        val skel = thin(mask, size)
        var total = 0
        val hist = IntArray(9)
        val ends = ArrayList<String>()
        for (y in 0 until size) for (x in 0 until size) {
            if (!skel[y * size + x]) continue
            total++
            val n = neighbours(skel, size, x, y)
            hist[n]++
            if (n == 1) ends.add("($x,$y)")
        }
        return "skelPixels=$total degrees=${hist.toList()} endpoints=$ends"
    }

    /** debug helper: renders the skeleton as an ASCII grid (used by the dev harness) */
    fun skeletonAscii(tile: FloatArray, size: Int = TileRasterizer.SIZE, step: Int = 2): String {
        val mask = BooleanArray(size * size) { tile[it] > 0.35f }
        val skel = thin(mask, size)
        val sb = StringBuilder()
        var y = 0
        while (y < size) {
            var x = 0
            while (x < size) {
                val ink = skel[y * size + x]
                sb.append(if (ink) '#' else if (mask[y * size + x]) '·' else ' ')
                x += step
            }
            sb.append('\n')
            y += step
        }
        return sb.toString()
    }
}
