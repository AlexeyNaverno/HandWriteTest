package com.handwrite.core.handwriting

import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the letter *pairs* the user joined on the page, so the font can reuse those joins.
 *
 * In cursive writing the interesting joins are not generic: "ст", "но", "ое", "ми", "ши" each have
 * their own little stroke, and a font that fakes a join between two separate letters never looks like
 * the writer. When two neighbouring letters touch, the ink between them belongs to neither crop, and
 * cropping them together gives a ligature that can be substituted as one glyph - exactly what
 * Handwritter and similar tools do, and what `liga` does in a real font.
 *
 * The mining is deliberately conservative, because a wrong ligature is worse than none: it needs
 *  1. two *recognised* letters, both with a decent score (so the pair is really "с"+"т", not "о"+"?");
 *  2. crops that touch or almost touch, and are tall enough to be letters, not dots;
 *  3. a bounding box that is plausibly two letters wide;
 *  4. a shape check - the pair's tile must not be *more* like another letter than the two halves are
 *     like their own labels.
 */
object LigatureMiner {

    /** a candidate pair found on a page */
    class Candidate(
        val first: Int,
        val second: Int,
        /** code points, for the report */
        val pair: String,
        val tile: FloatArray,
        /** how far apart the ink is, in x-heights (0 = touching) */
        val gap: Float,
        val confidence: Float,
        /** left edge of the first crop (lets the caller find the crops again) */
        val tileFirstX: Int = 0,
        /** left edge of the second crop */
        val tileSecondX: Int = 0
    )

    /**
     * @param crops crops of one row, left to right
     * @param labels recognised code point per crop (0 = unknown)
     * @param scores confidence of that label
     * @param page the segmented page (for line height and ink)
     */
    fun mine(
        crops: List<InkSegmenter.Crop>,
        labels: IntArray,
        scores: FloatArray,
        page: InkSegmenter.Page,
        minScore: Float = 0.5f
    ): List<Candidate> {
        if (crops.size < 2) return emptyList()
        val line = page.lineHeight.coerceAtLeast(4f)
        val out = ArrayList<Candidate>()
        for (i in 0 until crops.size - 1) {
            val a = crops[i]
            val b = crops[i + 1]
            val ca = labels[i]
            val cb = labels[i + 1]
            if (ca == 0 || cb == 0) continue
            if (!Character.isLetter(ca) || !Character.isLetter(cb)) continue
            if (scores[i] < minScore || scores[i + 1] < minScore) continue
            // dots and commas are not letters to join
            if (a.h < line * 0.45f || b.h < line * 0.45f) continue
            // they must touch: a visible gap means the writer lifted the pen
            val gap = (b.x - (a.x + a.w)).toFloat() / line
            if (gap > 0.12f) continue
            // both on the same line and not wildly different in size
            val sizeRatio = a.h.toFloat() / max(1, b.h)
            if (sizeRatio < 0.5f || sizeRatio > 2f) continue
            // the union must look like two letters, not like a word
            val unionW = (b.x + b.w - a.x).toFloat()
            if (unionW > line * 2.0f) continue

            val x0 = a.x
            val x1 = b.x + b.w
            val y0 = minOf(a.y, b.y)
            val y1 = maxOf(a.y + a.h, b.y + b.h)
            val box = InkSegmenter.Box(x0, y0, x1, y1)
            val tile = InkSegmenter.cropTile(page.ink, page.width, page.height, box)
            if (TileRasterizer.isEmpty(tile)) continue

            val pair = String(Character.toChars(ca)) + String(Character.toChars(cb))
            out.add(Candidate(ca, cb, pair, tile, gap, minOf(scores[i], scores[i + 1]), a.x, b.x))
        }
        // the most confident pairs win; the atlas keeps at most two shapes per pair anyway
        return out.sortedByDescending { it.confidence }.take(24)
    }

    /**
     * Rejects a pair whose combined shape is clearly a *different* single letter (a "ш" that was cut
     * in two, for instance). [classify] returns the best guess and its score for a tile.
     */
    fun isPlausiblePair(
        candidate: Candidate,
        halves: Pair<Int, Int>,
        classify: (FloatArray) -> Pair<Int, Float>
    ): Boolean {
        val (best, score) = classify(candidate.tile)
        // if the whole thing looks like one letter much more strongly than the halves look like
        // letters, the segmenter cut a single letter in two: that is not a ligature
        if (best != 0 && score > 0.72f && best != halves.first && best != halves.second) return false
        if (abs(score - candidate.confidence) < 0.001f) return true
        return true
    }
}
