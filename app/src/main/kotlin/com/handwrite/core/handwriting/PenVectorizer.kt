package com.handwrite.core.handwriting

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns the bitmap of one written letter into **pen strokes**: the line the pen actually travelled,
 * with the thickness of the pen along it.
 *
 * Why this matters more than the outline. A photographed letter rasterised from an image looks like
 * the letter, but it is a *shape*, not handwriting: joins have nothing to attach to, the pen width is
 * baked in per pixel, and a letter drawn at 3x size is a blurry shape. Once we have the movement -
 * an ordered polyline with a width profile - the renderer draws the same letter at any size with a
 * pen, connects it to its neighbours through its real end points, and can reproduce the retracing and
 * the tapering of the stroke.
 *
 * Algorithm (the offline-to-online pipeline used by handwriting-to-font tools):
 *
 *  1. **thinning** (Zhang-Suen) on the letter's ink tile - a one-pixel centre line, independent of
 *     how thick the pen was;
 *  2. **graph** - skeleton pixels become nodes; tips (degree 1) and crossings (degree 3+) are the
 *     features of the letter;
 *  3. **ordering** - the centre line is cut into the pieces between features and those pieces are
 *     chained back together by a **greedy TSP** walk: from the current end we always take the
 *     remaining piece whose continuation is closest, so the pen travels the shortest plausible route
 *     and crossings ("н", "т", "ж") are passed through instead of being visited twice. This is the
 *     "modified greedy TSP" of the stroke-reconstruction papers;
 *  4. **orientation** - a lower-case letter is entered at its lower left and left at its lower right,
 *     which is the convention cursive joins rely on;
 *  5. **width profile** - the distance transform of the ink at every point of the route, so thin
 *     hairline joins and thick downstrokes can be drawn with one pen;
 *  6. **smoothing** - the staircase of the pixel route is replaced with a quadratic B-spline, and
 *     the polyline is resampled so the stroke has a manageable number of points.
 *
 * The result is [PenGlyph]: several strokes (a letter can be written with a pen lift, "й", "ё"), each
 * a smooth polyline in tile coordinates (0..1, y down) with a width at every point.
 */
object PenVectorizer {

    /** one continuous pen movement with its width profile */
    class Stroke(
        /** points in tile coordinates, 0..1, y down */
        val points: List<FloatArray>,
        /** pen width at each point, in tile units (0..1 of the tile) */
        val widths: FloatArray
    ) {
        val start: FloatArray get() = points.first()
        val end: FloatArray get() = points.last()
        val startWidth: Float get() = widths.first()
        val endWidth: Float get() = widths.last()

        /** unit direction of travel over the first few points (where a join arrives) */
        fun entryDir(): FloatArray = dir(points, 0, +1)
        /** unit direction of travel over the last few points (where a join leaves) */
        fun exitDir(): FloatArray = dir(points, points.size - 1, -1)
    }

    /** the letter as a set of pen movements, in drawing order */
    class PenGlyph(
        val strokes: List<Stroke>,
        /** how much of the skeleton the first (main) stroke covers: 1 = one clean movement */
        val coherence: Float
    ) {
        val isEmpty: Boolean get() = strokes.isEmpty()
        val main: Stroke? get() = strokes.firstOrNull()
        /** pen placed on the paper first - the join from the previous letter arrives here */
        val start: FloatArray? get() = main?.start
        /** pen lifted last - the join to the next letter leaves from here */
        val end: FloatArray? get() = main?.end
    }

    private val EMPTY = PenGlyph(emptyList(), 0f)
    private val cache = HashMap<Int, PenGlyph>()

    /** cached: the pipeline is O(pixels) but letters repeat on every page */
    fun of(tile: FloatArray, size: Int = TileRasterizer.SIZE): PenGlyph {
        val key = tile.contentHashCode() * 131 + size
        synchronized(cache) {
            cache[key]?.let { return it }
            val g = vectorize(tile, size)
            if (cache.size > 2000) cache.clear()
            cache[key] = g
            return g
        }
    }

    fun of(sample: GlyphAtlas.Sample): PenGlyph = of(sample.tile)

    /**
     * @param tile 64x64 ink values 0..1, row-major (same tile the recogniser sees)
     * @param inkThreshold where a pixel counts as ink
     */
    fun vectorize(tile: FloatArray, size: Int = TileRasterizer.SIZE, inkThreshold: Float = 0.35f): PenGlyph {
        val ink = BooleanArray(size * size) { tile[it] > inkThreshold }
        if (ink.none { it }) return EMPTY
        val skeleton = thin(ink, size)
        val route = orderStrokes(skeleton, size)
        if (route.isEmpty()) return EMPTY
        val dist = distanceTransform(ink, size)

        val strokes = ArrayList<Stroke>()
        var mainCoverage = 0
        var totalPixels = 0
        for (piece in route) {
            totalPixels += piece.size
            // one greedy route can still contain a jump between two letters of the tile: split it
            val runs = splitByJumps(piece.map { floatArrayOf(it % size + 0.5f, it / size + 0.5f) }, size)
            for ((ri, run) in runs.withIndex()) {
                if (run.size < 3) continue
                val full = extendRun(run.toMutableList(), dist, size)
                val smooth = smoothPixels(full, size, dist)
                if (smooth == null) continue
                strokes.add(smooth)
                if (strokes.size == 1) mainCoverage = run.size * (if (ri == 0) 1 else 1)
            }
        }
        if (strokes.isEmpty()) return EMPTY
        return PenGlyph(strokes, (mainCoverage.toFloat() / totalPixels.coerceAtLeast(1)).coerceIn(0f, 1f))
    }

    /* ------------------------------ stroke ordering ------------------------------ */

    private val NB8 = arrayOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to 0, 1 to 0,
        -1 to 1, 0 to 1, 1 to 1
    )

    private fun neighbours(skel: BooleanArray, size: Int, p: Int): MutableList<Int> {
        val out = ArrayList<Int>(8)
        val x = p % size
        val y = p / size
        for ((dx, dy) in NB8) {
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
            val q = ny * size + nx
            if (skel[q]) out.add(q)
        }
        return out
    }

    /**
     * Cuts the skeleton into pieces between the features of the letter (tips and crossings), then
     * chains the pieces into pen movements with a greedy nearest-continuation walk (TSP).
     */
    private fun orderStrokes(skel: BooleanArray, size: Int): List<List<Int>> {
        val pixels = (0 until size * size).filter { skel[it] }
        if (pixels.isEmpty()) return emptyList()
        val degree = HashMap<Int, Int>(pixels.size)
        for (p in pixels) degree[p] = neighbours(skel, size, p).size

        // --- pieces between features -------------------------------------------------
        val usedEdges = HashSet<Long>()
        val pieces = ArrayList<IntArray>()
        fun edge(a: Int, b: Int): Long = (min(a, b).toLong() shl 32) or max(a, b).toLong()
        for (p in pixels) {
            if ((degree[p] ?: 0) == 2) continue
            for (n in neighbours(skel, size, p)) {
                if (usedEdges.contains(edge(p, n))) continue
                val path = ArrayList<Int>()
                path.add(p)
                usedEdges.add(edge(p, n))
                var prev = p
                var cur = n
                path.add(cur)
                var guard = 0
                while ((degree[cur] ?: 0) == 2 && guard++ < 10000) {
                    val next = neighbours(skel, size, cur).firstOrNull { it != prev } ?: break
                    usedEdges.add(edge(cur, next))
                    prev = cur
                    cur = next
                    path.add(cur)
                }
                if (path.size >= 3) pieces.add(path.toIntArray())
            }
        }
        // closed loops without any feature pixel (the ring of "о"): start anywhere
        for (p in pixels) {
            if ((degree[p] ?: 0) != 2) continue
            val nb = neighbours(skel, size, p).firstOrNull() ?: continue
            if (usedEdges.contains(edge(p, nb))) continue
            val path = ArrayList<Int>()
            var prev = -1
            var cur = p
            var guard = 0
            while (guard++ < 10000) {
                path.add(cur)
                val next = neighbours(skel, size, cur).firstOrNull { it != prev && !usedEdges.contains(edge(cur, it)) }
                    ?: break
                usedEdges.add(edge(cur, next))
                prev = cur
                cur = next
                if (cur == p) break
            }
            if (path.size >= 5) pieces.add(path.toIntArray())
        }
        if (pieces.isEmpty()) return emptyList()

        // --- which end of the letter does the pen start at? lower left of the ink ------
        val inkTop = pixels.minOf { it / size }
        val inkBottom = pixels.maxOf { it / size }
        fun scoreStart(p: Int): Float {
            val x = (p % size).toFloat() / size
            val y = (p / size).toFloat() / size
            // lower left, slightly favouring the baseline (where cursive enters letters)
            return y - 0.55f * x + 0.15f * (1f - y)
        }

        val remaining = pieces.indices.toMutableSet()
        val result = ArrayList<List<Int>>()

        // Every piece belongs to some pen-down. The walk below follows one pen-down at a time; when it
        // cannot find a piece that starts where the pen stopped, the pen was lifted - so the route is
        // closed and a new one starts with the remaining pieces instead of dropping them. (Dropping
        // them was a real bug: a tile that caught two letters lost the second one completely.)
        while (remaining.isNotEmpty()) {
            // the next pen-down starts at the most "entry-like" free end left
            var curPiece = -1
            var curReversed = false
            var bestScore = -Float.MAX_VALUE
            for (i in remaining) {
                for (rev in listOf(false, true)) {
                    val pc = pieces[i]
                    val sc = scoreStart(if (rev) pc.last() else pc.first())
                    if (sc > bestScore) {
                        bestScore = sc
                        curPiece = i
                        curReversed = rev
                    }
                }
            }
            if (curPiece < 0) break
            var current = ArrayList<Int>()
            var headingX = 0f
            var headingY = 0f
            while (curPiece >= 0) {
                remaining.remove(curPiece)
                val pc = pieces[curPiece]
                val ordered = if (curReversed) pc.reversed().toIntArray() else pc
                if (current.isEmpty()) {
                    current.addAll(ordered.toList())
                } else {
                    // greedy TSP step: continue from the current end into the nearest free end
                    val gap = distanceTo(current.last(), ordered.first(), size)
                    if (gap > size * 0.10f) {
                        result.add(current)
                        current = ArrayList(ordered.toList())
                    } else {
                        current.addAll(ordered.toList())
                    }
                }
                // heading of the piece we just walked
                val n = ordered.size
                val hx = (ordered[n - 1] % size - ordered[max(0, n - 5)] % size).toFloat()
                val hy = (ordered[n - 1] / size - ordered[max(0, n - 5)] / size).toFloat()
                val hl = max(1e-3f, sqrt(hx * hx + hy * hy))
                headingX = hx / hl
                headingY = hy / hl

                // next piece: minimal continuation cost from the current end
                val endPixel = current.last()
                var nextPiece = -1
                var nextReversed = false
                var nextCost = Float.MAX_VALUE
                for (j in remaining) {
                    val pcj = pieces[j]
                    for (rev in listOf(false, true)) {
                        val p = if (rev) pcj.last() else pcj.first()
                        val gap = distanceTo(endPixel, p, size)
                        if (gap > size * 0.10f) continue      // pieces that do not touch are separate pen-downs
                        // direction of travel leaving that end, so the pen does not double back
                        val q = if (rev) pcj[max(0, pcj.size - 5)] else pcj[min(pcj.size - 1, 4)]
                        val dx = (p % size - q % size).toFloat()
                        val dy = (p / size - q / size).toFloat()
                        val dl = max(1e-3f, sqrt(dx * dx + dy * dy))
                        val turn = -(headingX * dx / dl + headingY * dy / dl)   // 1 = straight on
                        val cost = gap * 2f + (1f - turn) * size * 0.05f
                        if (cost < nextCost) {
                            nextCost = cost
                            nextPiece = j
                            nextReversed = rev
                        }
                    }
                }
                curPiece = nextPiece
                curReversed = nextReversed
            }
            if (current.size >= 3) result.add(current)
        }

        // orientation of the whole route: enter lower left, leave lower right
        if (result.size == 1) {
            val r = result[0]
            if (scoreStart(r.first()) < scoreStart(r.last())) result[0] = r.reversed()
        } else if (result.size > 1) {
            // the longest piece is the main movement and must go left-to-right
            val idx = result.indices.maxByOrNull { result[it].size } ?: 0
            val r = result[idx]
            if (scoreStart(r.first()) < scoreStart(r.last())) result[idx] = r.reversed()
        }
        if (inkBottom < inkTop) result.clear()
        return result.filter { it.size >= 3 }
    }

    private fun distanceTo(a: Int, b: Int, size: Int): Float {
        val dx = (a % size - b % size).toFloat()
        val dy = (a / size - b / size).toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    /* --------------------------- width profile, smoothing --------------------------- */

    /** chamfer distance transform of the ink: the value at the centre line is the pen radius */
    fun distanceTransform(ink: BooleanArray, size: Int): FloatArray {
        val d = FloatArray(size * size) { if (ink[it]) 1e6f else 0f }
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            if (!ink[i]) continue
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1f)
            if (y > 0) v = min(v, d[i - size] + 1f)
            if (x > 0 && y > 0) v = min(v, d[i - size - 1] + 1.414f)
            if (x < size - 1 && y > 0) v = min(v, d[i - size + 1] + 1.414f)
            d[i] = v
        }
        for (y in size - 1 downTo 0) for (x in size - 1 downTo 0) {
            val i = y * size + x
            if (!ink[i]) continue
            var v = d[i]
            if (x < size - 1) v = min(v, d[i + 1] + 1f)
            if (y < size - 1) v = min(v, d[i + size] + 1f)
            if (x < size - 1 && y < size - 1) v = min(v, d[i + size + 1] + 1.414f)
            if (x > 0 && y < size - 1) v = min(v, d[i + size - 1] + 1.414f)
            d[i] = v
        }
        return d
    }

    /**
     * Pixel route -> smooth resampled stroke with widths.
     *
     * A raw skeleton route is a staircase; drawn as a stroke it would look like saw teeth. The route
     * is therefore resampled by arc length and passed through a quadratic B-spline (midpoints), the
     * same construction the outline path uses, so the two agree at the junctions. The width at every
     * kept point is the pen radius from the distance transform, lightly smoothed along the stroke.
     */
    /**
     * Grows a stroke's two ends back to the edge of the ink.
     *
     * Zhang-Suen always eats a few pixels from the ends of a stroke - more where the stroke is thick,
     * because the end is a staircase of corners. For a *drawing* that is invisible, but here the ends
     * are where joins attach: if the "о" in "моё" stops half a stroke short, the letters never touch.
     * So each end is walked outward along its own heading while the pen was still on the paper.
     */
    private fun extendRun(run: MutableList<FloatArray>, dist: FloatArray, size: Int): MutableList<FloatArray> {
        if (run.size < 2) return run
        fun edge(point: FloatArray, dirX: Float, dirY: Float): List<FloatArray> {
            val len = sqrt(dirX * dirX + dirY * dirY)
            if (len < 1e-3f) return emptyList()
            val ux = dirX / len
            val uy = dirY / len
            val steps = ArrayList<FloatArray>()
            var t = 0.75f
            // at most four pixels: any further and the "extension" is a different stroke
            while (t <= 4.5f) {
                val x = (point[0] + ux * t).toInt()
                val y = (point[1] + uy * t).toInt()
                if (x < 0 || y < 0 || x >= size || y >= size) break
                if (dist[y * size + x] <= 0f) break
                steps.add(floatArrayOf(point[0] + ux * t, point[1] + uy * t))
                t += 0.75f
            }
            return steps
        }
        val headDir = floatArrayOf(run[0][0] - run[min(3, run.size - 1)][0], run[0][1] - run[min(3, run.size - 1)][1])
        val tailDir = floatArrayOf(run[run.size - 1][0] - run[max(0, run.size - 4)][0], run[run.size - 1][1] - run[max(0, run.size - 4)][1])
        val head = edge(run[0], headDir[0], headDir[1]).reversed()
        val tail = edge(run[run.size - 1], tailDir[0], tailDir[1])
        val out = ArrayList<FloatArray>(run.size + 8)
        out.addAll(head)
        out.addAll(run)
        out.addAll(tail)
        return out
    }

    private fun smoothPixels(points: List<FloatArray>, size: Int, dist: FloatArray): Stroke? {
        // rebuilt as the same kind of input smooth() expects: sample the distance transform by position
        val raw = FloatArray(points.size) { i ->
            val x = points[i][0].toInt().coerceIn(0, size - 1)
            val y = points[i][1].toInt().coerceIn(0, size - 1)
            dist[y * size + x].coerceIn(0.5f, 6f)
        }
        // At the very ends of a stroke the centreline runs into the end cap, where the distance to the
        // paper is small even though the pen was at full width. The pen's width at a point is the
        // thickest cross-section within a couple of pixels, not the value exactly under the centre.
        val ws = FloatArray(raw.size) { i ->
            var m = raw[i]
            for (k in max(0, i - 3)..min(raw.size - 1, i + 3)) if (raw[k] > m) m = raw[k]
            m
        }
        if (points.size < 2) return null
        val sW = FloatArray(points.size)
        val sPts = ArrayList<FloatArray>(points.size)
        for (i in points.indices) {
            var sx = 0f
            var sy = 0f
            var sw = 0f
            var n = 0
            for (k in max(0, i - 1)..min(points.size - 1, i + 1)) {
                sx += points[k][0]
                sy += points[k][1]
                sw += ws[k]
                n++
            }
            // the ends stay exactly where the pen touched down / lifted, so joins do not drift
            val cx = if (i == 0 || i == points.size - 1) points[i][0] else sx / n
            val cy = if (i == 0 || i == points.size - 1) points[i][1] else sy / n
            sPts.add(floatArrayOf(cx, cy))
            sW[i] = sw / n
        }
        return Stroke(
            sPts.map { floatArrayOf(it[0] / size, it[1] / size) },
            FloatArray(sW.size) { sW[it] / size }
        )
    }

    private fun smooth(piece: List<Int>, size: Int, dist: FloatArray): Stroke? {
        if (piece.size < 3) return null
        // 1. resample by arc length
        val stepPx = 1.6f
        val pts = ArrayList<FloatArray>()
        val ws = ArrayList<Float>()
        var acc = 0f
        pts.add(floatArrayOf(piece[0] % size + 0.5f, piece[0] / size + 0.5f))
        ws.add(dist[piece[0]].coerceIn(0.5f, 6f))
        for (i in 1 until piece.size) {
            val ax = piece[i - 1] % size + 0.5f
            val ay = piece[i - 1] / size + 0.5f
            val bx = piece[i] % size + 0.5f
            val by = piece[i] / size + 0.5f
            val seg = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            acc += seg
            if (acc >= stepPx) {
                acc = 0f
                pts.add(floatArrayOf(bx, by))
                ws.add(dist[piece[i]].coerceIn(0.5f, 6f))
            }
        }
        val lastPx = piece.last()
        if (pts.size < 2 || (pts.last()[0] - (lastPx % size)).let { abs(it) } > 1.5f) {
            pts.add(floatArrayOf(lastPx % size + 0.5f, lastPx / size + 0.5f))
            ws.add(dist[lastPx].coerceIn(0.5f, 6f))
        }
        if (pts.size < 2) return null

        // 2. smooth the route and the width with a small moving average (keeps the ends exact)
        val sPts = ArrayList<FloatArray>(pts.size)
        val sW = FloatArray(pts.size)
        for (i in pts.indices) {
            var sx = 0f
            var sy = 0f
            var sw = 0f
            var n = 0
            for (k in max(0, i - 1)..min(pts.size - 1, i + 1)) {
                sx += pts[k][0]
                sy += pts[k][1]
                sw += ws[k]
                n++
            }
            // ends stay where the pen touched down / lifted, otherwise joins drift
            val cx = if (i == 0 || i == pts.size - 1) pts[i][0] else sx / n
            val cy = if (i == 0 || i == pts.size - 1) pts[i][1] else sy / n
            sPts.add(floatArrayOf(cx, cy))
            sW[i] = sw / n
        }
        // 3. to tile coordinates 0..1
        val outPts = sPts.map { floatArrayOf(it[0] / size, it[1] / size) }
        val outW = FloatArray(sW.size) { sW[it] / size }
        return Stroke(outPts, outW)
    }

    private fun dir(points: List<FloatArray>, from: Int, step: Int): FloatArray {
        val n = min(6, points.size - 1)
        if (n <= 0) return floatArrayOf(1f, 0f)
        val a = points[from]
        val b = points[(from + step * n).coerceIn(0, points.size - 1)]
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val len = max(1e-5f, sqrt(dx * dx + dy * dy))
        return floatArrayOf(dx / len, dy / len)
    }

    /**
     * Splits the ordered route into pen-downs.
     *
     * The greedy walk keeps chaining pieces while their ends are a pixel or two apart, which is right
     * for one letter but wrong when two letters leaked into the same tile: they must not become a
     * single stroke that jumps across the tile. A jump longer than a twelfth of the tile means the pen
     * was lifted.
     */
    private fun splitByJumps(points: List<FloatArray>, size: Int): List<List<FloatArray>> {
        if (points.size < 2) return listOf(points)
        val limit = size / 12f
        val out = ArrayList<List<FloatArray>>()
        var cur = ArrayList<FloatArray>()
        cur.add(points.first())
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            if (sqrt(dx * dx + dy * dy) > limit) {
                if (cur.size >= 3) out.add(cur)
                cur = ArrayList()
            }
            cur.add(b)
        }
        if (cur.size >= 3) out.add(cur)
        return out.ifEmpty { listOf(points) }
    }

    /* --------------------------------- thinning --------------------------------- */

    /**
     * Zhang-Suen thinning: delete boundary pixels until a one-pixel line remains. The line runs along
     * the centre of the stroke, so the trajectory does not depend on the pen thickness.
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
                        // Zhang-Suen reads the neighbours clockwise from north: p2=east ... p9=south-west.
                        // The indices here are offsets, so they must follow that same order or the
                        // connectivity test counts the wrong transitions and the thinning stops early
                        // (a 19-pixel stroke collapsed to two pixels, and letters lost their skeletons).
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
}
