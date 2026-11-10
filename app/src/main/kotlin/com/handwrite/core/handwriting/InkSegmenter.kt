package com.handwrite.core.handwriting

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a photo of a handwritten page into individual letter crops.
 *
 * Pipeline (classic computer vision, works fully offline):
 *   grayscale -> background normalisation -> Otsu threshold -> despeckle
 *   -> connected components -> crop merging (i dots, accents, ь/й marks)
 *   -> vertical projection split for touching letters -> crops sorted by reading order.
 */
object InkSegmenter {

    class Crop(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        /** normalised ink tile (64x64, 0..1) */
        val tile: FloatArray,
        val componentCount: Int,
        /** index of the text row (line) this crop belongs to */
        val row: Int = 0,
        /** ink height divided by the median x-height of the row (1 = small letter size) */
        val sizeRatio: Float = 1f,
        /** crop centre relative to the row centre, in x-heights */
        val centerRatio: Float = 0f
    ) {
        val area: Int get() = w * h
        val aspect: Float get() = w.toFloat() / max(1, h)
    }

    class Page(
        val width: Int,
        val height: Int,
        val ink: FloatArray,
        val crops: List<Crop>,
        val threshold: Float,
        val lineHeight: Float,
        val midline: Float,
        /** crops grouped by row, rows ordered top to bottom */
        val rows: List<List<Crop>> = emptyList(),
        /** true when the photo was segmented as a printed template sheet */
        val isTemplate: Boolean = false
    )

    /**
     * @param ink grayscale 0..1 (1 = ink)
     * @param template true when the photo is a grid/column template sheet: crops are then split
     *        by the whitespace between cells instead of being grouped into text lines
     */
    fun segment(
        gray: FloatArray,
        width: Int,
        height: Int,
        template: Boolean = false,
        preMask: FloatArray? = null
    ): Page {
        val normalised = when {
            preMask != null -> normaliseMask(preMask, width, height)
            else -> normaliseBackground(gray, width, height)
        }
        val thr = otsu(normalised)
        val bin = BooleanArray(width * height)
        if (preMask != null) {
            // The colour mask already says what is ink and what is grid, so the threshold must
            // follow it instead of Otsu: on a squared page the grid is half of the "dark" pixels
            // and Otsu happily lands in the middle of the pen strokes.
            val strong = max(0.35f, InkMask.percentile(preMask, 0.98f) * 0.45f)
            for (i in bin.indices) bin[i] = preMask[i] > strong
        } else {
            for (i in bin.indices) bin[i] = normalised[i] > thr
        }
        removeLongLines(bin, width, height)
        despeckle(bin, width, height, minArea = max(6, width * height / 400000))
        val comps = connectedComponents(bin, width, height)
        val lineHeight = estimateLineHeight(comps, height)
        // merge dots/accents with their base letter
        val merged = mergeSmallComponents(comps, lineHeight)
        val glued = if (preMask != null) glueStrokes(merged, lineHeight) else merged
        var boxes = ArrayList<Box>()
        for (g in glued) {
            if (g.w < 2 || g.h < 2) continue
            if (g.w * g.h < 12) continue
            // on a template sheet every letter sits in its own cell: splitting by the projection
            // profile would cut multi-stroke letters (ж, ш, ы) in half, so parts are only glued
            // On a colour mask letters are separated by their own outlines, so the projection split
            // only cuts them at their internal valleys ("ш" -> three sticks, "и" -> two). It is kept
            // for the grayscale path, where adjacent letters really do fuse together.
            if (template || preMask != null) boxes.add(g) else boxes.addAll(splitTouching(g, bin, width, lineHeight))
        }
        if (template) boxes = glueCellParts(boxes, normalised, width, height)
        val crops = ArrayList<Crop>()
        val crumb = lineHeight * 0.16f      // dots and specks carry no letter shape
        for (p in boxes) {
            if (preMask != null && p.w < crumb && p.h < crumb) continue
            val tile = cropTile(normalised, width, height, p)
            if (TileRasterizer.isEmpty(tile)) continue
            crops.add(Crop(p.x, p.y, p.w, p.h, tile, p.count))
        }
        val withRows: List<Crop>
        val rows: List<List<Crop>>
        if (template) {
            val r = templateRows(crops, width)
            rows = r.second
            withRows = r.first
        } else {
            val keyed = crops.map { lineOf(it, lineHeight) to it }
            val distinct = keyed.map { it.first }.distinct().sorted()
            rows = distinct.map { k -> keyed.filter { it.first == k }.map { it.second }.sortedBy { it.x } }
            withRows = rows.flatten()
            // rewrite row indices so that Crop.row is meaningful
            val measured = measureRows(rows)
            return Page(width, height, normalised, measured.first, thr, lineHeight, height / 2f, measured.second, false)
        }
        val measured = measureRows(rows)
        val mid = if (measured.first.isEmpty()) height / 2f
        else measured.first.map { it.y + it.h / 2f }.sorted()[measured.first.size / 2]
        return Page(width, height, normalised, measured.first, thr, lineHeight, mid, measured.second, template)
    }

    /**
     * Glues the parts of multi-stroke letters back together on a template sheet.
     *
     * "ы" is two separate strokes, "i" is a stem and a dot, "=" is two dashes. On a printed sheet
     * the gap inside a letter is a few pixels, while the gap between two cells is much wider, so the
     * parts are merged whenever their horizontal gap is under half of the median gap on the page and
     * the union still fits inside a cell.
     */
    private fun glueCellParts(boxes: List<Box>, ink: FloatArray, w: Int, h: Int): ArrayList<Box> {
        if (boxes.size < 2) return ArrayList(boxes)
        val byX = boxes.sortedBy { it.x0 }
        val gaps = ArrayList<Int>()
        for (i in 1 until byX.size) {
            val gap = byX[i].x0 - byX[i - 1].x1
            if (gap > 0) gaps.add(gap)
        }
        if (gaps.isEmpty()) return ArrayList(boxes)
        val medianGap = gaps.sorted()[gaps.size / 2]
        val limit = max(3, (medianGap * 0.45f).toInt())
        val heights = boxes.map { it.h }.sorted()
        val medianH = heights[heights.size / 2]
        val consumed = HashSet<Int>()
        val out = ArrayList<Box>()
        for (i in byX.indices) {
            if (i in consumed) continue
            var box = byX[i]
            var grew = true
            while (grew) {
                grew = false
                for (j in byX.indices) {
                    if (j == i || j in consumed) continue
                    val o = byX[j]
                    // vertical overlap is required: the parts belong to the same letter
                    val overlapY = min(box.y1, o.y1) - max(box.y0, o.y0)
                    if (overlapY <= 0) continue
                    val gap = max(o.x0 - box.x1, box.x0 - o.x1)
                    if (gap > limit) continue
                    val unionW = max(box.x1, o.x1) - min(box.x0, o.x0)
                    if (unionW > medianH * 2.2f) continue
                    box = box.merge(o)
                    consumed.add(j)
                    grew = true
                }
            }
            consumed.add(i)
            out.add(box)
        }
        return out
    }

    /**
     * Template sheets are read as a sequence of rows. Inside a row the letters are separated by
     * whitespace, which is much easier to detect than the spacing of flowing text, so the crops are
     * grouped by their vertical centre and ordered left to right.
     */
    private fun templateRows(crops: List<Crop>, pageWidth: Int): Pair<List<Crop>, List<List<Crop>>> {
        if (crops.isEmpty()) return emptyList<Crop>() to emptyList()
        val sorted = crops.sortedBy { it.y + it.h / 2f }
        val rows = ArrayList<MutableList<Crop>>()
        for (c in sorted) {
            val cy = c.y + c.h / 2f
            val row = rows.lastOrNull()
            if (row == null) {
                rows.add(mutableListOf(c))
            } else {
                val ref = row.map { it.y + it.h / 2f }.average().toFloat()
                val refH = row.map { it.h }.average().toFloat()
                if (abs(cy - ref) < max(refH * 0.8f, c.h * 0.6f)) row.add(c) else rows.add(mutableListOf(c))
            }
        }
        val out = rows.map { r -> r.sortedBy { it.x } }
        val flat = ArrayList<Crop>()
        for ((ri, row) in out.withIndex()) for (c in row) flat.add(Crop(c.x, c.y, c.w, c.h, c.tile, c.componentCount, ri))
        return flat to out.map { r -> r.map { c -> Crop(c.x, c.y, c.w, c.h, c.tile, c.componentCount) } }
    }

    /**
     * Measures every crop against its row: how tall it is compared to the small letters of the same
     * line, and whether it sits above or below the middle of the line. This is what lets the
     * renderer draw capitals capital-sized and "б", "в", "р" (which stick out of the line) in the
     * right place, instead of blowing every letter up to the same square.
     */
    private fun measureRows(rows: List<List<Crop>>): Pair<List<Crop>, List<List<Crop>>> {
        val flat = ArrayList<Crop>()
        val out = ArrayList<List<Crop>>()
        for ((ri, row) in rows.withIndex()) {
            if (row.isEmpty()) {
                out.add(row)
                continue
            }
            val heights = row.map { it.h }.sorted()
            val median = heights[heights.size / 2].toFloat()
            // the x-height is the typical height of the *small* letters: the tallest 30 % of the
            // crops (capitals, б/в, tail of "р") must not drag it up
            val smallBand = heights.take(max(1, (heights.size * 0.65f).toInt()))
            val xHeight = max(1f, smallBand[smallBand.size / 2].toFloat())
            val rowTop = row.minOf { it.y }.toFloat()
            val rowBottom = row.maxOf { it.y + it.h }.toFloat()
            val rowCentre = (rowTop + rowBottom) / 2f
            // where the small letters sit vertically (their band centre is the baseline-ish middle)
            val small = row.filter { it.h <= xHeight * 1.15f }
            val bandCentre = if (small.isEmpty()) rowCentre else small.map { it.y + it.h / 2f }.average().toFloat()
            val fixed = row.map { c ->
                Crop(
                    c.x, c.y, c.w, c.h, c.tile, c.componentCount, ri,
                    sizeRatio = (c.h / xHeight).coerceIn(0.4f, 2.4f),
                    centerRatio = ((c.y + c.h / 2f) - bandCentre) / xHeight
                )
            }
            flat.addAll(fixed)
            out.add(fixed)
            // silence "unused" warnings for the medians
            check(median >= 0f)
        }
        return flat to out
    }

    private fun lineOf(c: Crop, lineHeight: Float): Int = ((c.y + c.h * 0.5f) / max(1f, lineHeight)).toInt()

    /* ------------------------------------------------------------------ */

    /** column ink must be below this fraction of the letter body to count as a letter boundary */
    private const val MIN_CUT_RATIO = 0.45f

    class Box(val x0: Int, val y0: Int, var x1: Int, var y1: Int, val count: Int = 1) {
        val x get() = x0
        val y get() = y0
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
        val area get() = w * h
        fun merge(o: Box): Box = Box(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1), count + o.count)
    }

    /**
     * Turns a 0..1 ink map (see [InkMask]) into the "ink likelihood" the rest of the pipeline
     * expects: local contrast against the paper is added so that faint strokes still pass Otsu,
     * while the grid - which has no colour contrast and is soft and thin - stays below it.
     */
    private fun normaliseMask(mask: FloatArray, width: Int, height: Int): FloatArray {
        val local = InkMask.boxMean(mask, width, height, max(width, height) / 24)
        val out = FloatArray(mask.size)
        for (i in out.indices) {
            val v = mask[i]
            // unsharp: a pen stroke is much stronger than its neighbourhood; a grid line is not
            out[i] = (v + max(0f, v - local[i])).coerceIn(0f, 1f)
        }
        return out
    }

    private fun normaliseBackground(gray: FloatArray, width: Int, height: Int): FloatArray {
        // divide by a heavily blurred copy of itself: removes shadows and paper tint
        val block = max(8, min(width, height) / 12)
        val smallW = max(1, width / block)
        val smallH = max(1, height / block)
        val small = FloatArray(smallW * smallH)
        for (sy in 0 until smallH) {
            for (sx in 0 until smallW) {
                var sum = 0f
                var n = 0
                val y0 = sy * block
                val x0 = sx * block
                for (y in y0 until min(height, y0 + block) step 2) {
                    for (x in x0 until min(width, x0 + block) step 2) {
                        sum += gray[y * width + x]; n++
                    }
                }
                small[sy * smallW + sx] = if (n > 0) sum / n else 1f
            }
        }
        val out = FloatArray(gray.size)
        for (y in 0 until height) {
            val fy = y.toFloat() / block - 0.5f
            val y0 = fy.toInt().coerceIn(0, smallH - 1)
            val y1 = (y0 + 1).coerceAtMost(smallH - 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until width) {
                val fx = x.toFloat() / block - 0.5f
                val x0 = fx.toInt().coerceIn(0, smallW - 1)
                val x1 = (x0 + 1).coerceAtMost(smallW - 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val v00 = small[y0 * smallW + x0]
                val v10 = small[y0 * smallW + x1]
                val v01 = small[y1 * smallW + x0]
                val v11 = small[y1 * smallW + x1]
                val b = (v00 * (1 - tx) + v10 * tx) * (1 - ty) + (v01 * (1 - tx) + v11 * tx) * ty
                val v = gray[y * width + x]
                out[y * width + x] = (1f - (v / max(0.05f, b))).coerceIn(0f, 1f)
            }
        }
        return out
    }

    fun otsu(ink: FloatArray): Float {
        val bins = 256
        val hist = IntArray(bins)
        for (v in ink) {
            val b = (v * (bins - 1)).toInt().coerceIn(0, bins - 1)
            hist[b]++
        }
        val total = ink.size
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
                best = between
                thr = (i + 0.5f) / bins
            }
        }
        // never go below a sanity floor: faint pencil on grey paper still needs a low threshold
        return thr.coerceIn(0.15f, 0.85f)
    }

    /**
     * Removes table/notebook lines: long, thin, perfectly straight runs of ink.
     * Without this, a printed template grid or a ruled notebook page turns into giant blobs.
     */
    private fun removeLongLines(bin: BooleanArray, w: Int, h: Int) {
        val minRunH = (w * 0.18f).toInt().coerceAtLeast(24)
        val minRunV = (h * 0.18f).toInt().coerceAtLeast(24)
        // horizontal runs
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!bin[y * w + x]) { x++; continue }
                val start = x
                while (x < w && bin[y * w + x]) x++
                val run = x - start
                if (run >= minRunH) {
                    // check thickness: the run must be thin vertically
                    var thick = 0
                    val probe = start + run / 2
                    var yy = y
                    while (yy < h && bin[yy * w + probe]) { thick++; yy++ }
                    if (thick in 1..3) {
                        for (xx in start until x) {
                            for (k in 0 until thick) if (yy - thick + k >= 0) bin[(yy - thick + k) * w + xx] = false
                            if (y < h) bin[y * w + xx] = false
                        }
                    }
                }
            }
        }
        // vertical runs
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!bin[y * w + x]) { y++; continue }
                val start = y
                while (y < h && bin[y * w + x]) y++
                val run = y - start
                if (run >= minRunV) {
                    var thick = 0
                    val probe = start + run / 2
                    var xx = x
                    while (xx < w && bin[probe * w + xx]) { thick++; xx++ }
                    if (thick in 1..3) {
                        for (yy in start until y) {
                            bin[yy * w + x] = false
                            if (x > 0) bin[yy * w + x - 1] = false
                        }
                    }
                }
            }
        }
    }

    private fun despeckle(bin: BooleanArray, w: Int, h: Int, minArea: Int) {
        val comps = connectedComponents(bin, w, h)
        for (c in comps) if (c.area < minArea) {
            for (y in c.y0..c.y1) for (x in c.x0..c.x1) {
                if (bin[y * w + x]) {
                    // remove only if no big neighbour: keep it simple, erase the whole tiny box
                    bin[y * w + x] = false
                }
            }
        }
    }

    fun connectedComponents(bin: BooleanArray, w: Int, h: Int): List<Box> {
        val seen = BooleanArray(bin.size)
        val out = ArrayList<Box>()
        val stack = IntArray(bin.size)
        for (start in bin.indices) {
            if (!bin[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (bin[q] && !seen[q]) {
                        seen[q] = true
                        stack[sp++] = q
                    }
                }
            }
            out.add(Box(minX, minY, maxX, maxY, 1))
        }
        return out
    }

    private fun estimateLineHeight(comps: List<Box>, pageHeight: Int): Float {
        if (comps.isEmpty()) return pageHeight / 10f
        val heights = comps.map { it.h }.sorted()
        val median = heights[heights.size / 2]
        val big = heights.filter { it > median * 1.5 }
        val base = if (big.isNotEmpty()) big.average().toFloat() else median.toFloat()
        return base.coerceIn(8f, pageHeight / 2f)
    }

    /**
     * i/j dots, umlauts and accents are separate blobs that belong to the letter below them.
     * A small blob is absorbed by the letter whose horizontal span it overlaps.
     */
    /**
     * Joins pieces that belong to one letter.
     *
     * On a colour mask a pen stroke can break where it crosses a grid line, and the two halves of
     * "ш" or "т" then travel as separate crops. Real letter boundaries have a visible white gap, so
     * anything closer than ~0.12 of the line height (and overlapping vertically) is the same letter.
     * Extra guard: capitals and tall letters must not swallow their neighbours, so the union is
     * limited in width.
     */
    private fun glueStrokes(boxes: List<Box>, lineHeight: Float): List<Box> {
        if (boxes.size < 2) return boxes
        val sorted = boxes.sortedByDescending { it.area }
        val used = BooleanArray(sorted.size)
        val out = ArrayList<Box>()
        val limit = max(2f, lineHeight * 0.10f)
        // only fragments are glued (a dot, a broken tail). Two letter-sized pieces are left apart:
        // letters that really touch are cut by splitTouching, which follows the ink profile instead
        // of the bounding boxes.
        val fragment = lineHeight * 0.55f
        for (i in sorted.indices) {
            if (used[i]) continue
            var box = sorted[i]
            used[i] = true
            var changed = true
            while (changed) {
                changed = false
                for (j in sorted.indices) {
                    if (used[j]) continue
                    val o = sorted[j]
                    val oSmall = o.w < fragment || o.h < fragment
                    val bSmall = box.w < fragment || box.h < fragment
                    if (!oSmall && !bSmall) continue
                    val gapX = max(0, max(box.x0, o.x0) - min(box.x1, o.x1))
                    val gapY = max(0, max(box.y0, o.y0) - min(box.y1, o.y1))
                    if (gapX > limit || gapY > limit) continue
                    val unionW = max(box.x1, o.x1) - min(box.x0, o.x0)
                    val unionH = max(box.y1, o.y1) - min(box.y0, o.y0)
                    if (unionW > lineHeight * 0.9f || unionH > lineHeight * 2.0f) continue
                    box = box.merge(o)
                    used[j] = true
                    changed = true
                }
            }
            out.add(box)
        }
        return out
    }

    private fun mergeSmallComponents(comps: List<Box>, lineHeight: Float): List<Box> {
        val smallLimit = lineHeight * 0.55f
        val sorted = comps.sortedByDescending { it.area }
        val used = BooleanArray(sorted.size)
        val out = ArrayList<Box>()
        for (i in sorted.indices) {
            if (used[i]) continue
            var box = sorted[i]
            used[i] = true
            if (box.h >= smallLimit) {
                // absorb dots/accents that sit above (or right below) this letter
                var changed = true
                while (changed) {
                    changed = false
                    for (j in sorted.indices) {
                        if (used[j]) continue
                        val o = sorted[j]
                        if (o.h >= smallLimit) continue
                        val gapY = o.y1.toFloat() - box.y0      // negative when the dot is above
                        val gapX = max(0, max(box.x0, o.x0) - min(box.x1, o.x1))
                        val overlap = gapX < min(box.w, o.w) * 0.5f
                        val vertical = gapY in -lineHeight * 0.55f..lineHeight * 0.30f
                        val left = o.x0 - box.x0
                        val right = box.x1 - o.x1
                        val inside = left > -box.w * 0.25f && right > -box.w * 0.25f
                        if (overlap && vertical && inside) {
                            box = box.merge(o)
                            used[j] = true
                            changed = true
                        }
                    }
                }
            }
            out.add(box)
        }
        return out
    }

    /** cuts a wide blob into several letters using the vertical ink profile */
    /**
     * Splits a run of touching letters by the minima of the vertical ink profile.
     *
     * Cursive letters in flowing text are joined by strokes that sit on the baseline, so the
     * profile between two letters drops to a few pixels instead of zero - a plain "cut at the
     * deepest minimum" is not enough. A cut is accepted when the column is clearly thinner than
     * the letter body around it (see [MIN_CUT_RATIO]); without that test the splitter produced
     * 175 "letters" for a 299-character page and every crop was a fragment.
     */
    private fun splitTouching(b: Box, bin: BooleanArray, w: Int, lineHeight: Float): List<Box> {
        val expected = lineHeight * 0.95f
        if (b.w <= expected * 1.35f || b.count > 1) return listOf(b)
        val profile = IntArray(b.w)
        for (x in b.x0..b.x1) {
            var c = 0
            for (y in b.y0..b.y1) if (bin[y * w + x]) c++
            profile[x - b.x0] = c
        }
        // a letter is at most ~1.35 x-heights wide, so the number of pieces follows from the width
        val pieces = min(6, max(2, Math.round(b.w / expected)))
        val body = profile.sorted()[profile.size / 2].toFloat()
        // find (pieces-1) minima that are separated by at least 0.45 * expected width
        val cuts = ArrayList<Int>()
        var from = (expected * 0.45f).toInt()
        for (k in 0 until pieces - 1) {
            val to = b.w - ((pieces - 1 - k) * expected * 0.45f).toInt()
            if (to <= from) break
            var bestX = -1
            var bestV = Int.MAX_VALUE
            for (x in from until to) {
                if (profile[x] < bestV) {
                    bestV = profile[x]
                    bestX = x
                }
            }
            // only a real valley counts: the column must carry much less ink than the body
            if (bestX >= 0 && bestV <= body * MIN_CUT_RATIO) {
                cuts.add(bestX)
                from = bestX + (expected * 0.45f).toInt()
            }
        }
        if (cuts.isEmpty()) return listOf(b)
        val out = ArrayList<Box>()
        var start = 0
        for (c in cuts + listOf(b.w)) {
            if (c - start < 2) {
                start = c
                continue
            }
            // shrink to the real ink in this column range
            var x0 = start
            var x1 = c - 1
            while (x0 < x1 && profile[x0] == 0) x0++
            while (x1 > x0 && profile[x1] == 0) x1--
            var y0 = b.y1
            var y1 = b.y0
            for (x in x0..x1) for (y in b.y0..b.y1) {
                if (bin[y * w + b.x0 + x]) { if (y < y0) y0 = y; if (y > y1) y1 = y }
            }
            if (y1 < y0) { y0 = b.y0; y1 = b.y1 }
            out.add(Box(b.x0 + x0, y0, b.x0 + x1, y1, 1))
            start = c
        }
        return if (out.isEmpty()) listOf(b) else out
    }

    /** copies the crop into a 64x64 tile, scaled like the training data */
    fun cropTile(ink: FloatArray, w: Int, h: Int, b: Box): FloatArray {
        val size = TileRasterizer.SIZE
        val out = FloatArray(size * size)
        if (b.w <= 0 || b.h <= 0) return out
        val scale = (size - 6f) / max(b.w, b.h)
        val dw = max(1, (b.w * scale).toInt())
        val dh = max(1, (b.h * scale).toInt())
        val ox = (size - dw) / 2
        val oy = (size - dh) / 2
        for (y in 0 until dh) {
            val sy = (b.y0 + y / scale).toInt().coerceIn(b.y0, b.y1)
            for (x in 0 until dw) {
                val sx = (b.x0 + x / scale).toInt().coerceIn(b.x0, b.x1)
                out[(oy + y) * size + ox + x] = ink[sy * w + sx]
            }
        }
        return normaliseContrast(out)
    }

    /**
     * Photos of pencil/ballpoint ink are faint: their maximum intensity is often 0.3-0.5, while
     * the network was trained on tiles whose ink saturates at 1.0. Stretching the contrast of each
     * crop (and a mild gamma to make the stroke solid) raises recognition accuracy a lot.
     */
    fun normaliseContrast(tile: FloatArray): FloatArray {
        var max = 0f
        for (v in tile) if (v > max) max = v
        if (max < 0.05f) return tile
        val gain = (0.95f / max).coerceIn(1f, 4f)
        if (gain <= 1.01f) return tile
        for (i in tile.indices) {
            val v = (tile[i] * gain).coerceAtMost(1f)
            tile[i] = if (v > 0.08f) Math.pow(v.toDouble(), 0.85).toFloat() else 0f
        }
        return tile
    }

    fun sameLine(a: Crop, b: Crop, lineHeight: Float): Boolean {
        val ay = a.y + a.h / 2f
        val by = b.y + b.h / 2f
        return abs(ay - by) < lineHeight * 0.6f
    }
}
