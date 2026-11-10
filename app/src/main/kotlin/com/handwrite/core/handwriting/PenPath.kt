package com.handwrite.core.handwriting

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline-to-online conversion, the part that pays off in a fixed amount of code: the **pen path** of
 * a letter - the line the pen actually travelled - instead of its outline.
 *
 * Why this matters for joins. A connector between two letters must continue the *movement*, not the
 * shape: it leaves where the pen left the first letter, along the direction the pen was going, and
 * arrives where the pen touched down for the second one. Outlines cannot tell us that; a centre-line
 * can.
 *
 * Pipeline:
 *
 *  1. **thinning** (Zhang-Suen) - the stroke becomes a one-pixel curve, independent of pen width;
 *  2. **diameter traversal** - of all the routes through the skeleton we take the longest one. On a
 *     written letter that route *is* the main movement: it passes straight through crossings
 *     ("н", "т", "ж") instead of stopping at them, and it closes the loop of "о", "а", "в";
 *  3. **orientation** - cursive writing enters a lower-case letter on its left and leaves it on its
 *     right at the baseline, so the route is oriented lower-left -> lower-right. This is the same
 *     convention the renderer uses to decide where a join starts and where it ends.
 *
 * What is *not* here: full stroke ordering for letters written with several pen-downs (the "ij" dot,
 * the crossbar of "т", retraced strokes). That is what SORT-style transformers and InkSight do and it
 * needs a model, not a heuristic; it is on the roadmap. [Path.strokes] therefore holds the route plus
 * the leftover pieces that carry real ink (dots), which is enough for correct joins today.
 */
object PenPath {

    /** a point of the reconstructed trajectory (normalised 0..1 inside the tile, y down) */
    class Point(val x: Float, val y: Float)

    /** one continuous pen movement: touches down at [start], lifts at [end] */
    class Stroke(val points: List<Point>) {
        val start: Point get() = points.first()
        val end: Point get() = points.last()
        val exitDir: Pair<Float, Float> get() = directionAtEnd(points, fromStart = false)
        val entryDir: Pair<Float, Float> get() = directionAtEnd(points, fromStart = true)
    }

    class Path(val strokes: List<Stroke>) {
        val isEmpty: Boolean get() = strokes.isEmpty()
        /** where the pen enters the letter */
        val start: Point? get() = strokes.firstOrNull()?.start
        /** where the pen leaves the letter, ready to be joined to the next one */
        val end: Point? get() = strokes.firstOrNull()?.end
        val entryDir: Pair<Float, Float> get() = strokes.firstOrNull()?.entryDir ?: (1f to 0f)
        val exitDir: Pair<Float, Float> get() = strokes.firstOrNull()?.exitDir ?: (1f to 0f)

        /**
         * How much of the skeleton the route covers. 1 = the letter is one movement (the normal case
         * for cursive), lower when the letter was broken into pieces by the photo or the thinning.
         */
        fun quality(): Float {
            val first = strokes.firstOrNull() ?: return 0f
            val total = strokes.sumOf { it.points.size }
            if (total == 0) return 0f
            return (first.points.size.toFloat() / total).coerceIn(0f, 1f)
        }
    }

    private val EMPTY = Path(emptyList())
    private val cache = HashMap<Int, Path>()

    fun pathOf(tile: FloatArray, size: Int = TileRasterizer.SIZE, inkThreshold: Float = 0.35f): Path {
        val key = tile.contentHashCode() * 31 + size
        synchronized(cache) {
            cache[key]?.let { return it }
            val p = reconstruct(tile, size, inkThreshold)
            if (cache.size > 3000) cache.clear()
            cache[key] = p
            return p
        }
    }

    fun pathOf(sample: GlyphAtlas.Sample): Path = pathOf(sample.tile)

    private val NB8 = arrayOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to 0, 1 to 0,
        -1 to 1, 0 to 1, 1 to 1
    )

    fun reconstruct(tile: FloatArray, size: Int = TileRasterizer.SIZE, inkThreshold: Float = 0.35f): Path {
        val mask = BooleanArray(size * size) { tile[it] > inkThreshold }
        val skel = thin(mask, size)
        val pixels = (0 until size * size).filter { skel[it] }
        if (pixels.isEmpty()) return EMPTY

        // where can the pen have touched down and lifted? only at skeleton tips (degree 1)
        val tips = pixels.filter { p ->
            var n = 0
            val x = p % size
            val y = p / size
            for ((dx, dy) in NB8) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until size && ny in 0 until size && skel[ny * size + nx]) n++
            }
            n == 1
        }
        // cursive writing enters a lower-case letter at its lower-left and leaves at its lower-right,
        // both near the baseline: tips are only taken from the lower part of the ink
        val inkMaxY = pixels.maxOf { it / size }
        val lowBand = inkMaxY - size * 0.45f
        val lowTips = tips.filter { it / size >= lowBand }
        val pool = if (lowTips.size >= 2) lowTips else tips
        val entryTip = pool.maxByOrNull { (it / size) - 0.7f * (it % size) }
        val exitTip = pool.maxByOrNull { (it / size) + 0.7f * (it % size) }
        val byTips = if (entryTip != null && exitTip != null && entryTip != exitTip)
            routeBetween(skel, size, entryTip, exitTip) else null
        val route = byTips ?: diameterPath(skel, size, pixels) ?: return EMPTY

        var pts: List<Point> = route.map { Point((it % size + 0.5f) / size, (it / size + 0.5f) / size) }

        // last check: the route must start on the left/bottom end and finish on the right one
        val scoreA = pts.first().y - 0.55f * pts.first().x
        val scoreB = pts.last().y - 0.55f * pts.last().x
        if (scoreB > scoreA) pts = pts.reversed()

        val strokes = ArrayList<Stroke>()
        strokes.add(Stroke(simplify(pts)))
        return Path(strokes)
    }

    /** shortest route through the skeleton between two of its pixels (the pen's overall movement) */
    private fun routeBetween(skel: BooleanArray, size: Int, from: Int, to: Int): List<Int>? {
        val parent = IntArray(size * size) { -2 }
        val queue = IntArray(size * size)
        var head = 0
        var tail = 0
        parent[from] = -1
        queue[tail++] = from
        while (head < tail) {
            val p = queue[head++]
            if (p == to) break
            val x = p % size
            val y = p / size
            for ((dx, dy) in NB8) {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
                val q = ny * size + nx
                if (!skel[q] || parent[q] != -2) continue
                parent[q] = p
                queue[tail++] = q
            }
        }
        if (parent[to] == -2) return null
        val path = ArrayList<Int>()
        var cur = to
        while (cur >= 0) {
            path.add(cur)
            cur = parent[cur]
        }
        path.reverse()
        return if (path.size < 3) null else path
    }

    /**
     * Longest route through the skeleton (two BFS sweeps, i.e. the diameter of the skeleton graph).
     * Runs in O(pixels) and does not care how many junctions the letter has.
     */
    private fun diameterPath(skel: BooleanArray, size: Int, pixels: List<Int>): List<Int>? {
        fun bfs(from: Int): Pair<IntArray, Int> {
            val parent = IntArray(size * size) { -2 }
            val dist = IntArray(size * size) { -1 }
            val queue = IntArray(size * size)
            var head = 0
            var tail = 0
            parent[from] = -1
            dist[from] = 0
            queue[tail++] = from
            var far = from
            while (head < tail) {
                val p = queue[head++]
                if (dist[p] > dist[far]) far = p
                val x = p % size
                val y = p / size
                for ((dx, dy) in NB8) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
                    val q = ny * size + nx
                    if (!skel[q] || dist[q] >= 0) continue
                    dist[q] = dist[p] + 1
                    parent[q] = p
                    queue[tail++] = q
                }
            }
            return parent to far
        }
        val seed = pixels.first()
        val (_, far1) = bfs(seed)
        val (parent, far2) = bfs(far1)
        val path = ArrayList<Int>()
        var cur = far2
        while (cur >= 0) {
            path.add(cur)
            cur = parent[cur]
        }
        if (path.size < 3) return null
        path.reverse()
        return path
    }

    /** removes the staircase produced by thinning so the direction at the ends is meaningful */
    private fun simplify(points: List<Point>): List<Point> {
        if (points.size <= 2) return points
        val out = ArrayList<Point>(points.size)
        out.add(points.first())
        for (i in 1 until points.size - 1) {
            val prev = out.last()
            val p = points[i]
            val nxt = points[i + 1]
            val d1x = p.x - prev.x
            val d1y = p.y - prev.y
            val d2x = nxt.x - p.x
            val d2y = nxt.y - p.y
            val cross = d1x * d2y - d1y * d2x
            if (kotlin.math.abs(cross) > 1e-4f) out.add(p)
        }
        out.add(points.last())
        return out
    }

    private fun directionAtEnd(points: List<Point>, fromStart: Boolean): Pair<Float, Float> {
        if (points.size < 2) return 1f to 0f
        val n = min(points.size - 1, 6)
        val (a, b) = if (fromStart) points[0] to points[n] else points[points.size - 1 - n] to points.last()
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = max(1e-4f, sqrt(dx * dx + dy * dy))
        return dx / len to dy / len
    }

    /* --------------------------------- thinning -------------------------------- */

    /**
     * Zhang-Suen thinning: delete boundary pixels until a one-pixel wide line remains. The line runs
     * along the centre of the stroke, so the pen path does not depend on how thick the pen was.
     */
    fun thin(mask: BooleanArray, size: Int): BooleanArray {
        val img = mask.copyOf()
        val del = BooleanArray(size * size)
        var changed = true
        var guard = 0
        val p = IntArray(9)
        while (changed && guard++ < 100) {
            changed = false
            for (step in 0..1) {
                var any = false
                for (y in 1 until size - 1) {
                    for (x in 1 until size - 1) {
                        val i = y * size + x
                        if (!img[i]) continue
                        p[1] = i - size
                        p[2] = i - size + 1
                        p[3] = i + 1
                        p[4] = i + size + 1
                        p[5] = i + size
                        p[6] = i + size - 1
                        p[7] = i - 1
                        p[8] = i - size - 1
                        var b = 0
                        for (k in 1..8) if (img[p[k]]) b++
                        if (b < 2 || b > 6) continue
                        var a = 0
                        for (k in 1..8) {
                            val cur = p[k]
                            val nxt = p[if (k == 8) 1 else k + 1]
                            if (!img[cur] && img[nxt]) a++
                        }
                        if (a != 1) continue
                        val ok = if (step == 0) {
                            !(img[p[1]] && img[p[3]] && img[p[5]]) && !(img[p[3]] && img[p[5]] && img[p[7]])
                        } else {
                            !(img[p[1]] && img[p[3]] && img[p[7]]) && !(img[p[1]] && img[p[5]] && img[p[7]])
                        }
                        if (!ok) continue
                        del[i] = true
                        any = true
                    }
                }
                if (any) {
                    for (i in img.indices) if (del[i]) img[i] = false
                    java.util.Arrays.fill(del, false)
                    changed = true
                }
            }
        }
        return img
    }

    /** ASCII dump of the skeleton, for the dev harnesses */
    fun skeletonAscii(tile: FloatArray, size: Int = TileRasterizer.SIZE): String {
        val mask = BooleanArray(size * size) { tile[it] > 0.35f }
        val skel = thin(mask, size)
        val sb = StringBuilder()
        for (y in 0 until size) {
            for (x in 0 until size) sb.append(if (skel[y * size + x]) '#' else if (mask[y * size + x]) '.' else ' ')
            sb.append('\n')
        }
        return sb.toString()
    }
}
