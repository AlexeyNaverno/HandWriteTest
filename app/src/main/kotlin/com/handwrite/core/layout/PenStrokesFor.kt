package com.handwrite.core.layout

import com.handwrite.core.geom.GlyphPath
import com.handwrite.core.geom.M2x3
import com.handwrite.core.handwriting.AtlasGlyphSource
import com.handwrite.core.handwriting.GlyphAtlas
import com.handwrite.core.handwriting.PenVectorizer
import com.handwrite.core.handwriting.TileRasterizer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Prepares the pen strokes of a learned letter for the page.
 *
 * [PenVectorizer] gives the trajectory in tile coordinates (0..1, y down); here it is mapped the same
 * way [GlyphAtlas.pathFor] maps the outline, so strokes and joins land exactly on the glyph:
 *
 *   x_font = lsb + (xTile * 64 - xmin) * inkScale
 *   y_font = centre - (yTile * 64 - 32) * inkScale
 *
 * and then transformed to the page by the glyph's own matrix (jitter, slant, pressure).
 */
object PenStrokesFor {

    /** @param seed the same position seed the layout used to pick the variant of this glyph */
    fun glyph(source: AtlasGlyphSource, gid: Int, path: GlyphPath, m: M2x3, seed: Int): List<PenStroke>? {
        val atlas = source.atlas
        // must be the variant that was actually drawn, otherwise strokes and outline disagree
        val sample = source.sampleAt(gid, seed) ?: return null
        return of(atlas, sample, m)
    }

    fun of(atlas: GlyphAtlas, sample: GlyphAtlas.Sample, m: M2x3): List<PenStroke>? {
        val pen = PenVectorizer.of(sample)
        if (pen.isEmpty) return null
        val em = atlas.unitsPerEm
        val xHeight = em * GlyphAtlas.X_HEIGHT_RATIO
        // ink scale of the outline: the ink occupies INK_PIXELS of the 64 px tile
        val lsb = em * 0.035f
        val nm = atlas.metricsOf(sample)
        val centre = xHeight / 2f - (nm.top + nm.bottom) / 2f * xHeight
        // GlyphAtlas.pathFor maps the tile with  x_font = lsb - minX * s + xTile * s  where minX is the
        // leftmost ink of the vectorised outline; recover the same three numbers from the outline it
        // produced, so strokes and outline cannot drift apart.
        val outline = atlas.pathOf(sample, em.toFloat())
        val outlineLsb = em * 0.02f
        val s = xHeight * nm.height.coerceIn(0.55f, 2.2f) / GlyphAtlas.INK_PIXELS
        val tileMinX = (outline.minX - outlineLsb) / s
        val e = outlineLsb - tileMinX * s
        val f = centre + (TileRasterizer.SIZE / 2f) * s

        val strokes = ArrayList<PenStroke>(pen.strokes.size)
        for (st in pen.strokes) {
            val n = st.points.size
            if (n < 2) continue
            val pts = FloatArray(n * 2)
            val widths = FloatArray(n)
            for (i in 0 until n) {
                val tx = st.points[i][0] * TileRasterizer.SIZE
                val ty = st.points[i][1] * TileRasterizer.SIZE
                val fx = e + tx * s
                val fy = f - ty * s
                pts[i * 2] = m.applyX(fx, fy)
                pts[i * 2 + 1] = m.applyY(fx, fy)
                // stroke width: the distance transform gives the pen *radius* in tile pixels, so the
                // drawn ribbon needs twice that. A floor keeps hairlines visible at small sizes.
                widths[i] = (2f * st.widths[i] * TileRasterizer.SIZE * s * m.scaleOf()).coerceAtLeast(0.4f)
            }
            strokes.add(PenStroke(pts, widths, 1f))
        }
        return strokes.ifEmpty { null }
    }

    /** average linear scale of the page transform (px per font unit) */
    private fun M2x3.scaleOf(): Float {
        val sx = sqrt(a * a + b * b)
        val sy = sqrt(c * c + d * d)
        return max(0.01f, (sx + sy) / 2f)
    }
}
