package com.handwrite.core.handwriting

import kotlin.math.max

/**
 * Puts every learned letter on a common metric grid.
 *
 * This is what separates "a heap of letter photos" from a font. On a photo, one "о" is 20 px tall and
 * sits on the line, the next one is 26 px and rides 3 px above it; letters are also stretched by the
 * perspective of the shot. Copying the crop as it was photographed gives text where every letter is a
 * different size - and no amount of joins hides that.
 *
 * So every sample is re-measured the way a typographer would:
 *
 *  * **x-height letters** ("о", "и", "ш", "т"...) are normalised to exactly 1.0 x-height;
 *  * **ascenders** ("б", "в", "ж", capitals) keep their natural ratio to the x-height, clamped to a
 *    sane range, and their baseline is moved to the real baseline of their line;
 *  * **descenders** ("д", "р", "у", "з") keep the depth of their tail, same way;
 *  * a letter whose ink coincides with the baseline (a "period", a comma) keeps its vertical position
 *    relative to the baseline instead of being centred.
 *
 * The result is [Metrics] - a *relative* ink box in x-heights, measured from the baseline - which the
 * layout uses instead of the raw page measurements. It is derived, never stored, so old atlases and
 * the correction UI keep working unchanged.
 */
object GlyphNormalizer {

    /** relative ink box of a letter: heights in x-heights, measured up from the baseline */
    class Metrics(
        /** ink height / x-height (1.0 = a small letter) */
        val height: Float,
        /** bottom of the ink above the baseline, in x-heights (negative = descender) */
        val bottom: Float,
        /** top of the ink above the baseline, in x-heights */
        val top: Float
    )

    /** letters whose ink normally hangs below the baseline */
    private val DESCENDERS = "дцузрфэДЦУЗРФЭqypgj"

    /**
     * @param sizeRatio measured ink height / x-height of the row
     * @param centerRatio measured ink centre relative to the row's small-letter band, in x-heights
     *   (negative = above the middle of the band, as ascenders are)
     * @param codePoint the letter, used to tell ascenders and descenders from plain letters
     */
    fun metrics(sizeRatio: Float, centerRatio: Float, codePoint: Int): Metrics {
        // A crop that lost its dot or its tail measures small; one that swallowed a neighbour's
        // ascender measures huge. Both are noise, not style, so they are clamped.
        val h = sizeRatio.coerceIn(0.45f, 1.7f)
        val c = centerRatio.coerceIn(-0.9f, 0.7f)
        val top = c + h / 2f
        val bottom = c - h / 2f
        val cp = codePoint
        val isDescender = cp != 0 && String(Character.toChars(cp)).any { it in DESCENDERS }

        if (isDescender) {
            // the part above the baseline is the x-height, the tail keeps going below it
            val above = top.coerceIn(0.6f, 1.2f)
            val below = bottom.coerceIn(-1.2f, -0.05f)
            return Metrics((above - below).coerceIn(0.8f, 2.0f), below, above)
        }
        // A small letter is drawn at exactly one x-height, whatever the page measured: on the page the
        // same "о" measures anywhere from 0.7 to 1.4 depending on where it sits in the row, and that
        // spread is exactly what makes a "font" look like a pile of photos.
        if (h <= 1.2f) return Metrics(1f, 0f, 1f)
        // taller than the x-height: it is an ascender or a capital, keep the proportion
        val height = h.coerceIn(1.2f, 1.75f)
        return Metrics(height, 0f, height)
    }

    /**
     * Height of the whole writing line in x-heights, used to keep the descenders of one line from
     * colliding with the ascenders of the next one.
     */
    fun lineExtent(metrics: Metrics): Pair<Float, Float> = -metrics.bottom.coerceAtLeast(0f) to metrics.top.coerceAtLeast(0f)

    /** convenience: is this letter drawn as tall as the small letters? */
    fun isSmall(m: Metrics): Boolean = m.height <= 1.05f && m.bottom >= -0.05f

    /** the biggest ratio between the tallest and the smallest accepted x-height */
    const val MAX_STRETCH = 1.35f

    /**
     * Removes the outliers from a set of measured heights: a page photographed at an angle can
     * measure one row at 1.6 x-height and the next at 0.9. Normalising to the *median* keeps the
     * page's own proportions instead of amplifying a bad measurement.
     */
    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 1f
        val sorted = values.sorted()
        return max(0.1f, sorted[sorted.size / 2])
    }
}
