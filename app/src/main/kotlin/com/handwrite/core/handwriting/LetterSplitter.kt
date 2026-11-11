package com.handwrite.core.handwriting


/**
 * Cuts a crop that turned out to be **two letters** into those two letters.
 *
 * Segmentation splits a row at the gaps between letters, so when the writer joined "ст" or "ое" the
 * pen never leaves the paper and the two letters arrive as one connected crop. Learning that crop as
 * a single letter is what makes a font look wrong: the atlas gets a glyph labelled "д" that is really
 * "де", and the renderer then stamps a two-letter blob wherever it wants a "д".
 *
 * The cut is deliberately conservative, because a wrong cut is worse than a wide letter:
 *  1. the crop must be wide - at least [MIN_ASPECT] x-heights wide - so a normal "ш" is never touched;
 *  2. each half must still be a letter of plausible width when drawn on its own;
 *  3. the classifier must recognise *both* halves confidently, and their combined probability must be
 *     greater than the best single label for the whole crop (i.e. "two letters" explains the ink
 *     better than "one strange letter" does).
 *
 * When those hold, the halves are returned as ordinary crops and can be learned, joined, or turned
 * into a ligature - because a crop that *is* two joined letters is exactly where ligatures come from.
 */
object LetterSplitter {

    /** a crop narrower than this many x-heights is never split */
    const val MIN_ASPECT = 1.30f

    /** each half must be at least this wide, in x-heights, to be a letter and not a stroke */
    const val MIN_HALF = 0.30f

    /** how confidently each half must be recognised for the crop to be accepted as two letters */
    const val MIN_HALF_SCORE = 0.62f

    /**
     * A crop the classifier already reads as a single letter, confidently, is left alone: "ш" and
     * "ж" are wide letters, and cutting them in half is how a font ends up with broken shapes.
     */
    const val MAX_WHOLE_SCORE = 0.72f

    class Split(val first: InkSegmenter.Crop, val second: InkSegmenter.Crop, val score: Float)

    /**
     * @param crop a wide crop from the page
     * @param page the row it belongs to, for midline and x-height
     * @param classify classifier over a tile (returns the best label and its score)
     */
    fun split(
        crop: InkSegmenter.Crop,
        page: InkSegmenter.Page,
        mask: FloatArray,
        pageWidth: Int,
        pageHeight: Int,
        classify: (FloatArray) -> Pair<Int, Float>
    ): Split? {
        val xHeight = page.lineHeight.coerceAtLeast(4f)
        if (crop.w < xHeight * MIN_ASPECT) return null
        val wholeGuess = classify(crop.tile)
        val whole = wholeGuess.second
        if (whole >= MAX_WHOLE_SCORE) return null

        var best: Split? = null
        // the cut is searched as a fraction of the crop width: the ink valley is not known here, and a
        // fixed grid is far cheaper than a full histogram for the few wide crops a page has
        for (f in listOf(0.42f, 0.46f, 0.50f, 0.54f, 0.58f)) {
            val cut = crop.x + (crop.w * f).toInt()
            val leftW = cut - crop.x
            val rightW = crop.x + crop.w - cut
            if (leftW < xHeight * MIN_HALF || rightW < xHeight * MIN_HALF) continue
            val a = halfCrop(crop, mask, pageWidth, pageHeight, crop.x, crop.y, leftW, crop.h)
            val b = halfCrop(crop, mask, pageWidth, pageHeight, cut, crop.y, rightW, crop.h)
            if (a == null || b == null) continue
            // a half that is a single thin vertical stroke is a piece of a letter, not a letter
            val (ca, sa) = classify(a.tile)
            val (cb, sb) = classify(b.tile)
            if (ca == 0 || cb == 0) continue
            if (sa < MIN_HALF_SCORE || sb < MIN_HALF_SCORE) continue
            if (!Character.isLetter(ca) || !Character.isLetter(cb)) continue
            // two halves of one word are in the same alphabet; "iF" is not a join anybody wrote
            if (isCyrillic(ca) != isCyrillic(cb)) continue
            if (ca == '?'.code || cb == '?'.code) continue
            // A join lives inside one word, and a word does not change case in the middle: "iF" is
            // two crops that leaked together, not a ligature somebody wrote.
            if (Character.isUpperCase(ca) != Character.isUpperCase(cb)) continue
            // two slanted strokes side by side ("и", "ш") are one letter; require the halves to differ
            // enough in position that the pair reads as two letters on the baseline
            val confidence = minOf(sa, sb)
            // Two letters explain the ink better than one odd letter when the whole crop scored badly
            // or when the halves are both confident.
            if (confidence < whole && confidence < 0.7f) continue
            if (best == null || confidence > best.score) best = Split(a, b, confidence)
        }
        return best
    }

    /** one half of a crop, rasterised from the page mask with the same normalisation crops get */
    private fun halfCrop(
        parent: InkSegmenter.Crop,
        mask: FloatArray,
        pageWidth: Int,
        pageHeight: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ): InkSegmenter.Crop? {
        if (w <= 0 || h <= 0) return null
        // trim empty columns/rows so the half is tight around its own ink
        val threshold = InkSegmenter.inkThreshold(mask)
        var x0 = Int.MAX_VALUE
        var x1 = -1
        var y0 = Int.MAX_VALUE
        var y1 = -1
        for (yy in y until (y + h).coerceAtMost(pageHeight)) {
            for (xx in x until (x + w).coerceAtMost(pageWidth)) {
                if (mask[yy * pageWidth + xx] > threshold) {
                    if (xx < x0) x0 = xx
                    if (xx > x1) x1 = xx
                    if (yy < y0) y0 = yy
                    if (yy > y1) y1 = yy
                }
            }
        }
        if (x1 < x0 || y1 < y0) return null
        val box = InkSegmenter.Box(x0, y0, x1 + 1, y1 + 1)
        val tile = InkSegmenter.cropTile(mask, pageWidth, pageHeight, box)
        if (TileRasterizer.inkCoverage(tile) < 0.01f) return null
        return InkSegmenter.Crop(
            x = x0, y = y0, w = x1 + 1 - x0, h = y1 + 1 - y0,
            tile = tile, componentCount = 1, row = parent.row,
            sizeRatio = parent.sizeRatio, centerRatio = parent.centerRatio
        )
    }

    private fun isCyrillic(cp: Int): Boolean = cp in 0x0400..0x04FF

}
