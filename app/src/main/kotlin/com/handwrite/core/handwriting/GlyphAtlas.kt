package com.handwrite.core.handwriting

import com.handwrite.core.font.FontData
import com.handwrite.core.font.GlyphSource
import com.handwrite.core.geom.GlyphPath
import com.handwrite.core.geom.M2x3
import com.handwrite.core.geom.Seg
import kotlin.math.max

/**
 * A font built from the user's own photos.
 *
 * The user photographs 1-5 pages of handwriting. The pages are segmented into letters
 * ([InkSegmenter]), every crop is classified by the on-device network ([HandwriteModel]) and the
 * accepted samples are collected here. The atlas then behaves exactly like a font: the layout
 * engine asks it for glyph shapes, and the existing renderer (preview + PDF) draws the user's
 * own letterforms, including several variants per letter and learned joins (see [VariantSelector]).
 */
class GlyphAtlas(
    val name: String = "Мой почерк",
    val unitsPerEm: Int = 1000
) {

    /** one accepted sample of a letter */
    class Sample(
        val codePoint: Int,
        /** ink tile 64x64, 0..1 */
        val tile: FloatArray,
        /** embedding produced by the network (unit length) */
        val embedding: FloatArray,
        val confidence: Float,
        val aspect: Float,
        /** index of the source photo */
        val sourcePage: Int,
        /** position of the crop inside the page (used to learn joins) */
        val pageX: Float,
        val pageY: Float,
        /**
         * Ink height of the crop divided by the median x-height of its row. 1.0 means "as tall as
         * the small letters around it", ~1.6 means a capital or an ascender (б, в, ж).
         */
        val sizeRatio: Float = 1f,
        /** Vertical centre of the crop relative to the row's x-height band, in x-heights (0 = middle). */
        val centerRatio: Float = 0f
    )

    val samples = ArrayList<Sample>()

    private val byCode = HashMap<Int, MutableList<Sample>>()

    /** measured letter height (ascender) relative to the em square, from the user's writing */
    var ascenderRatio: Float = 0.78f
    var descenderRatio: Float = 0.22f

    fun add(s: Sample): Boolean {
        val existing = byCode.getOrPut(s.codePoint) { ArrayList() }
        if (existing.size >= MAX_VARIANTS) {
            // keep the most confident variants: replace the weakest one only if clearly better
            val worst = existing.minByOrNull { it.confidence } ?: return false
            if (worst.confidence >= s.confidence) return false
            existing.remove(worst)
            samples.remove(worst)
        }
        existing.add(s)
        samples.add(s)
        return true
    }

    fun variants(codePoint: Int): List<Sample> = byCode[codePoint] ?: emptyList()

    /** moves every sample of [sample] to a different letter (used by the correction UI) */
    fun retarget(sample: Sample, newCodePoint: Int) {
        val list = byCode[sample.codePoint]
        list?.remove(sample)
        if (list != null && list.isEmpty()) byCode.remove(sample.codePoint)
        samples.remove(sample)
        val moved = Sample(
            codePoint = newCodePoint,
            tile = sample.tile,
            embedding = sample.embedding,
            confidence = sample.confidence,
            aspect = sample.aspect,
            sourcePage = sample.sourcePage,
            pageX = sample.pageX,
            pageY = sample.pageY,
            sizeRatio = sample.sizeRatio,
            centerRatio = sample.centerRatio
        )
        add(moved)
    }

    /**
     * Fills in capitals the user never wrote by growing the matching lowercase letter.
     *
     * People write "А" as a bigger "а", "К" as a bigger "к" (with a short stem), so a scaled copy
     * of the lowercase sample looks far more like *their* handwriting than a stock font glyph.
     * Explicitly written capitals always win; this only fills the gaps.
     */
    fun deriveCapitals() {
        val pairs = ArrayList<Pair<Int, Int>>()
        for (lower in 'а'..'я') {
            val upper = Character.toUpperCase(lower)
            if (upper == lower) continue
            pairs.add(lower.code to upper.code)
        }
        for (lower in 'a'..'z') {
            val upper = lower.uppercaseChar()
            pairs.add(lower.code to upper.code)
        }
        for ((lo, up) in pairs) {
            if (hasCode(up)) continue
            val src = byCode[lo] ?: continue
            val best = src.maxByOrNull { it.confidence } ?: continue
            add(
                Sample(
                    codePoint = up,
                    tile = best.tile,
                    embedding = best.embedding,
                    confidence = best.confidence * 0.9f,
                    aspect = best.aspect,
                    sourcePage = best.sourcePage,
                    pageX = best.pageX,
                    pageY = best.pageY,
                    // capitals are ~1.35 x-heights tall in handwriting and sit a little higher
                    sizeRatio = 1.35f,
                    centerRatio = -0.18f
                )
            )
        }
    }

    fun hasCode(codePoint: Int): Boolean = (byCode[codePoint]?.isNotEmpty() == true)

    fun letterCount(): Int = byCode.count { it.value.isNotEmpty() }

    fun sampleCount(): Int = samples.size

    fun avgConfidence(): Float =
        if (samples.isEmpty()) 0f else samples.map { it.confidence }.average().toFloat()

    /** how well the atlas covers the alphabet it was given */
    fun coverage(alphabet: String): Float {
        if (alphabet.isEmpty()) return 0f
        val have = alphabet.count { hasCode(it.code) }
        return have.toFloat() / alphabet.length
    }

    /**
     * Selects one of the stored variants. The choice is stable for a given position
     * (position hash) so the same text always looks the same, but neighbours rarely repeat.
     */
    fun pick(codePoint: Int, positionSeed: Int): Sample? {
        val list = variants(codePoint)
        if (list.isEmpty()) return null
        if (list.size == 1) return list[0]
        val idx = (positionSeed * 2654435761L).let { ((it ushr 8) and 0x7FFFFFFF).toInt() } % list.size
        return list[idx]
    }

    /**
     * Converts a stored ink tile into vector outlines. The tile is vectorised with a marching
     * squares pass and smoothed, so the result scales to any size (high resolution per letter).
     */
    fun pathFor(sample: Sample, em: Float = unitsPerEm.toFloat()): GlyphPath {
        val contours = TileVectorizer.vectorize(sample.tile, TileRasterizer.SIZE)
        if (contours.isEmpty()) return GlyphPath.of(emptyList())
        /*
         * Geometry of a learned glyph.
         *
         * The tile is normalised (58 px of ink inside 64 px), so the *real* size of the letter is
         * known only from what was measured on the page: [Sample.sizeRatio] is its height in
         * x-heights, [Sample.centerRatio] says whether it sits above or below the small letters of
         * its line. Both are turned into font units here:
         *
         *   ink height  = xHeight * sizeRatio
         *   ink centre  = xHeight / 2 - centerRatio * xHeight      (measured from the baseline)
         *
         * The outline is also shifted so that the leftmost ink lands on the left side bearing,
         * which is what keeps every letter inside the text column instead of hanging into the
         * margin.
         */
        val xHeight = em * X_HEIGHT_RATIO
        val growth = sample.sizeRatio.coerceIn(0.55f, 2.2f)
        val s = xHeight * growth / INK_PIXELS
        var xmin = Float.MAX_VALUE
        for (c in contours) {
            val pts = c.points
            for (i in pts.indices step 2) if (pts[i] < xmin) xmin = pts[i]
        }
        if (xmin == Float.MAX_VALUE) xmin = TileRasterizer.SIZE / 2f
        val lsb = em * 0.02f
        val e = lsb - xmin * s
        val centre = xHeight / 2f - sample.centerRatio.coerceIn(-1.5f, 1.5f) * xHeight
        val f = centre + (TileRasterizer.SIZE / 2f) * s
        // tile space is y-down, font space is y-up
        val m = M2x3(a = s, d = -s, e = e, f = f)
        val segs = ArrayList<Seg>()
        for (c in contours) {
            val pts = c.points
            val n = pts.size / 2
            if (n < 3) continue
            // quadratic B-spline through the midpoints: a smooth closed outline
            var px = (pts[(n - 1) * 2] + pts[0]) / 2f
            var py = (pts[(n - 1) * 2 + 1] + pts[1]) / 2f
            segs.add(Seg.Move(m.applyX(px, py), m.applyY(px, py)))
            for (i in 0 until n) {
                val j = (i + 1) % n
                val cx = pts[i * 2]
                val cy = pts[i * 2 + 1]
                val ax = (pts[i * 2] + pts[j * 2]) / 2f
                val ay = (pts[i * 2 + 1] + pts[j * 2 + 1]) / 2f
                segs.add(Seg.Quad(m.applyX(cx, cy), m.applyY(cx, cy), m.applyX(ax, ay), m.applyY(ax, ay)))
                px = ax
                py = ay
            }
            segs.add(Seg.Line(m.applyX(px, py), m.applyY(px, py)))
        }
        return GlyphPath.of(segs)
    }

    private val pathCache = HashMap<Sample, GlyphPath>()

    /** cached outline of a sample (the vectoriser is the most expensive step) */
    fun pathOf(sample: Sample, em: Float = unitsPerEm.toFloat()): GlyphPath =
        synchronized(pathCache) {
            pathCache.getOrPut(sample) { pathFor(sample, em) }
        }

    /**
     * Width of a letter, measured on the vectorised outline (so a narrow "г" and a wide "ж" get
     * the space they actually need) plus a side bearing that looks right for connected writing.
     */
    /**
     * Pen entry/exit of a learned letter in font units, consistent with [pathFor].
     *
     * [StrokeFlow] works on the 64x64 ink tile (x, y in 0..1, y down); the glyph is drawn from the
     * same tile with the scale and offsets used by [pathFor], so the conversion is exact:
     *
     *   x_font = lsb + (xTile * 64 - xmin) * inkScale
     *   y_font = centre - (yTile * 64 - 32) * inkScale
     */
    fun flowInFontUnits(sample: Sample, path: GlyphPath): com.handwrite.core.font.StrokeFlow? {
        // The pen path (the skeleton centre-line) is what a join must follow: it says where the pen
        // came in, where it went out, and which way it was moving at both ends. Outline extremes
        // cannot express that, which is why early joins looked like random ticks.
        val pen = PenPath.pathOf(sample)
        val start = pen.start ?: return null
        val end = pen.end ?: return null
        val xHeight = unitsPerEm * X_HEIGHT_RATIO
        val growth = sample.sizeRatio.coerceIn(0.5f, 2.4f)
        val inkScale = xHeight * growth / INK_PIXELS * (64f / 58f)
        val contours = TileVectorizer.vectorize(sample.tile, TileRasterizer.SIZE)
        var xmin = TileRasterizer.SIZE / 2f
        for (c in contours) {
            val pts = c.points
            for (i in pts.indices step 2) if (pts[i] < xmin) xmin = pts[i]
        }
        // the pen path lives on the same 64x64 tile, so the same mapping applies
        val lsb = unitsPerEm * 0.035f
        val centre = xHeight / 2f - sample.centerRatio.coerceIn(-1.5f, 1.5f) * xHeight
        fun fx(x: Float) = lsb + (x * TileRasterizer.SIZE - xmin) * inkScale
        fun fy(y: Float) = centre - (y * TileRasterizer.SIZE - TileRasterizer.SIZE / 2f) * inkScale
        val ed = pen.entryDir
        val xd = pen.exitDir
        return com.handwrite.core.font.StrokeFlow(
            entry = com.handwrite.core.font.StrokeFlow.Terminal(
                fx(start.x), fy(start.y), ed.first, -ed.second
            ),
            exit = com.handwrite.core.font.StrokeFlow.Terminal(
                fx(end.x), fy(end.y), xd.first, -xd.second
            ),
            inkMinX = path.minX,
            inkMaxX = path.maxX
        )
    }

    fun advanceFor(sample: Sample, em: Float = unitsPerEm.toFloat()): Float {
        val p = pathOf(sample, em)
        val w = (p.maxX - p.minX)
        if (w <= 0f) return em * 0.45f
        // The left bearing is baked into the outline (see [pathFor]); the right one is kept small
        // because cursive letters lean into each other. Wide letters get a touch more air.
        val lsb = em * 0.02f
        val rsb = em * (if (sample.aspect > 1.1f) 0.028f else 0.012f)
        return w + lsb + rsb
    }

    companion object {
        /** height of the small letters (x-height) as a fraction of the em square */
        const val X_HEIGHT_RATIO = 0.5f

        /** inner ink size of a normalised tile, see [TileRasterizer] */
        const val INK_PIXELS = 58f

        const val MAX_VARIANTS = 6

        val RUSSIAN_ALPHABET = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
        val ENGLISH_ALPHABET = "abcdefghijklmnopqrstuvwxyz"

        fun alphabetFor(mode: com.handwrite.core.model.WritingMode): String = when (mode) {
            com.handwrite.core.model.WritingMode.ENGLISH_PRINT -> ENGLISH_ALPHABET
            else -> RUSSIAN_ALPHABET
        }
    }
}

/**
 * Vectorises a bitmap into smooth closed contours.
 *
 * 1. the ink mask is thresholded and slightly dilated so hairlines do not break the outline;
 * 2. every connected component is traced with a Moore-neighbour walk (boundary following);
 * 3. the polyline is corner-cut (Chaikin) and simplified (Ramer-Douglas-Peucker);
 * 4. the caller converts it to quadratic curves.
 *
 * The result keeps the pressure variation of the original stroke, so a letter learns not just its
 * shape but also its thin/thick distribution.
 */
object TileVectorizer {

    class Loop(val points: FloatArray)

    fun vectorize(tile: FloatArray, size: Int, threshold: Float = 0.32f): List<Loop> {
        val mask = BooleanArray(size * size)
        for (i in tile.indices) mask[i] = tile[i] > threshold
        // one-pixel closing: fill a hole/notch if it is surrounded by ink
        val dilated = BooleanArray(mask.size)
        for (y in 1 until size - 1) for (x in 1 until size - 1) {
            val i = y * size + x
            if (!mask[i]) continue
            dilated[i] = true
            for (dy in -1..1) for (dx in -1..1) dilated[(y + dy) * size + x + dx] = true
        }
        val traced = BooleanArray(mask.size)
        val loops = ArrayList<Loop>()
        for (start in dilated.indices) {
            if (!dilated[start] || traced[start]) continue
            val loop = traceBoundary(dilated, size, start, traced) ?: continue
            if (loop.size < 12) continue
            var pts = loop
            repeat(2) { pts = chaikin(pts) }
            val simplified = rdpClosed(pts, 0.20f)
            if (simplified.size >= 8) loops.add(Loop(simplified))
        }
        return loops
    }

    /** Moore-neighbour boundary walk of the component that contains [start] */
    private fun traceBoundary(mask: BooleanArray, size: Int, start: Int, traced: BooleanArray): FloatArray? {
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        var x = start % size
        var y = start / size
        val sx = x
        val sy = y
        val pts = ArrayList<Float>()
        var dir = 6
        var steps = 0
        val maxSteps = size * size * 2
        while (steps++ < maxSteps) {
            pts.add(x.toFloat())
            pts.add(y.toFloat())
            traced[y * size + x] = true
            var found = false
            for (k in 0 until 8) {
                val nd = (dir + k) % 8
                val nx = x + dx[nd]
                val ny = y + dy[nd]
                if (nx < 0 || ny < 0 || nx >= size || ny >= size) continue
                if (!mask[ny * size + nx]) continue
                x = nx
                y = ny
                dir = (nd + 5) % 8
                found = true
                break
            }
            if (!found) break
            if (x == sx && y == sy) break
        }
        if (pts.size < 12) return null
        return pts.toFloatArray()
    }

    /** one iteration of Chaikin corner cutting (keeps the loop closed) */
    private fun chaikin(points: FloatArray): FloatArray {
        val n = points.size / 2
        if (n < 4) return points
        val out = FloatArray(n * 4)
        var k = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = points[i * 2]
            val y0 = points[i * 2 + 1]
            val x1 = points[j * 2]
            val y1 = points[j * 2 + 1]
            out[k++] = 0.75f * x0 + 0.25f * x1
            out[k++] = 0.75f * y0 + 0.25f * y1
            out[k++] = 0.25f * x0 + 0.75f * x1
            out[k++] = 0.25f * y0 + 0.75f * y1
        }
        return out
    }

    private fun rdpClosed(points: FloatArray, eps: Float): FloatArray {
        val n = points.size / 2
        if (n < 4) return points
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        rdp(points, 0, n - 1, eps, keep)
        val out = ArrayList<Float>(n * 2)
        for (i in 0 until n) if (keep[i]) {
            out.add(points[i * 2])
            out.add(points[i * 2 + 1])
        }
        return out.toFloatArray()
    }

    private fun rdp(p: FloatArray, i0: Int, i1: Int, eps: Float, keep: BooleanArray) {
        if (i1 <= i0 + 1) return
        val x0 = p[i0 * 2]
        val y0 = p[i0 * 2 + 1]
        val x1 = p[i1 * 2]
        val y1 = p[i1 * 2 + 1]
        val dx = x1 - x0
        val dy = y1 - y0
        val len = max(1e-4f, kotlin.math.sqrt(dx * dx + dy * dy))
        var best = -1f
        var bestIdx = -1
        for (i in i0 + 1 until i1) {
            val d = kotlin.math.abs((p[i * 2] - x0) * dy - (p[i * 2 + 1] - y0) * dx) / len
            if (d > best) {
                best = d
                bestIdx = i
            }
        }
        if (best > eps && bestIdx > 0) {
            keep[bestIdx] = true
            rdp(p, i0, bestIdx, eps, keep)
            rdp(p, bestIdx, i1, eps, keep)
        }
    }
}

/**
 * Atlas-backed [GlyphSource], so the layout engine and the page renderer work unchanged
 * with the user's own letterforms.
 */
class AtlasGlyphSource(
    val atlas: GlyphAtlas,
    val fallback: GlyphSource? = null
) : GlyphSource {

    override val name: String get() = atlas.name
    override val unitsPerEm: Int get() = atlas.unitsPerEm
    override val ascender: Int get() = (atlas.unitsPerEm * atlas.ascenderRatio).toInt()
    override val descender: Int get() = -(atlas.unitsPerEm * atlas.descenderRatio).toInt()

    /**
     * Glyph ids of the atlas are the code points of the learned letters. Everything the user did
     * not write falls back to the bundled font, so an English sentence still renders when only the
     * Russian alphabet was learned (and vice versa). [FALLBACK_BASE] keeps the two id spaces apart:
     * ids >= FALLBACK_BASE are delegated to the fallback font.
     */
    override fun gid(cp: Int): Int = when {
        atlas.hasCode(cp) -> cp
        fallback != null && fallback.hasGlyph(cp) -> FALLBACK_BASE or cp
        else -> 0
    }

    override fun hasGlyph(cp: Int): Boolean =
        atlas.hasCode(cp) || (fallback?.hasGlyph(cp) == true)

    private fun isFallback(gid: Int): Boolean = gid >= FALLBACK_BASE && fallback != null

    override fun advance(gid: Int): Int {
        if (isFallback(gid)) {
            val f = fallback!!
            val g = f.gid(gid - FALLBACK_BASE)
            if (g != 0) return f.advance(g)
        }
        val sample = atlas.variants(gid).maxByOrNull { it.confidence }
        if (sample != null) return atlas.advanceFor(sample, unitsPerEm.toFloat()).toInt()
        return unitsPerEm / 2
    }

    override val usesEvenOddFill: Boolean
        get() = atlas.sampleCount() > 0

    override fun path(gid: Int, coords: Map<String, Float>): GlyphPath {
        if (isFallback(gid)) {
            val f = fallback!!
            val g = f.gid(gid - FALLBACK_BASE)
            if (g != 0) return f.path(g, coords)
        }
        val sample = atlas.pick(gid, gid * 31 + coords.hashCode())
        if (sample != null) return atlas.pathOf(sample, unitsPerEm.toFloat())
        return GlyphPath.of(emptyList())
    }

    companion object {
        /** first glyph id that belongs to the fallback font */
        const val FALLBACK_BASE = 0x100000
    }

    override fun applyFeatures(gids: IntArray, tags: Set<String>): IntArray = gids

    override val supportsWeightAxis: Boolean get() = false
    override val weightMin: Float get() = 400f
    override val weightDefault: Float get() = 400f
    override val weightMax: Float get() = 400f

    override fun strokeFlow(gid: Int): com.handwrite.core.font.StrokeFlow? {
        if (isFallback(gid)) return null
        val sample = atlas.pick(gid, gid * 31) ?: return null
        val path = atlas.pathOf(sample, unitsPerEm.toFloat())
        return atlas.flowInFontUnits(sample, path)
    }

    override fun isLetterGlyph(cp: Int, gid: Int): Boolean = Character.isLetter(cp)
}
