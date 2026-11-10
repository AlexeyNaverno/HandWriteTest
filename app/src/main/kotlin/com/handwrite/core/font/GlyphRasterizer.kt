package com.handwrite.core.font

import com.handwrite.core.geom.GlyphPath
import com.handwrite.core.geom.Seg

/**
 * Converts the contours of [FontData] into drawable, closed paths.
 *
 * Handwriting fonts are filled shapes (each stroke is a closed outline), so filling them with the
 * page renderer reproduces exactly what a vector PDF does: unlimited "resolution per letter",
 * because glyphs are drawn at thousands of pixels per em on screen and as vector curves in print.
 */
class GlyphRasterizer(private val font: FontData) {

    private val contourCache = HashMap<String, List<GlyphContour>>()
    private val pathCache = HashMap<String, GlyphPath>()

    private fun key(gid: Int, coords: Map<String, Float>): String {
        if (coords.isEmpty()) return gid.toString()
        val sb = StringBuilder(gid.toString())
        for (c in coords) sb.append('|').append(c.key).append('=').append(c.value)
        return sb.toString()
    }

    fun contours(gid: Int, coords: Map<String, Float> = emptyMap()): List<GlyphContour> =
        contourCache.getOrPut(key(gid, coords)) { font.outline(gid, coords) }

    /** Glyph outline as one path (several closed sub-contours) in font units, y-up. */
    fun glyphPath(gid: Int, coords: Map<String, Float> = emptyMap()): GlyphPath =
        pathCache.getOrPut(key(gid, coords)) {
            val segs = ArrayList<Seg>()
            for (c in contours(gid, coords)) segs.addAll(contourToSegs(c.points))
            GlyphPath.of(segs)
        }

    /** TrueType contour -> quadratic path segments. */
    fun contourToSegs(points: List<GlyphPoint>): List<Seg> {
        val n = points.size
        if (n < 2) return emptyList()
        val out = ArrayList<Seg>(n + 2)
        var firstOn = -1
        for (i in 0 until n) if (points[i].onCurve) { firstOn = i; break }
        val startX: Float
        val startY: Float
        var startIdx: Int
        if (firstOn < 0) {
            // all off-curve: implied on-curve point between the last and the first
            startX = (points[0].x + points[n - 1].x) / 2
            startY = (points[0].y + points[n - 1].y) / 2
            startIdx = n - 1
        } else {
            startX = points[firstOn].x
            startY = points[firstOn].y
            startIdx = firstOn
        }
        out.add(Seg.Move(startX, startY))
        var curX = startX
        var curY = startY
        var controlX = Float.NaN
        var controlY = Float.NaN
        for (k in 1..n) {
            val p = points[(startIdx + k) % n]
            if (p.onCurve) {
                if (!controlX.isNaN()) {
                    out.add(Seg.Quad(controlX, controlY, p.x, p.y))
                    controlX = Float.NaN
                } else {
                    out.add(Seg.Line(p.x, p.y))
                }
                curX = p.x
                curY = p.y
            } else {
                if (!controlX.isNaN()) {
                    val mx = (controlX + p.x) / 2
                    val my = (controlY + p.y) / 2
                    out.add(Seg.Quad(controlX, controlY, mx, my))
                    curX = mx
                    curY = my
                }
                controlX = p.x
                controlY = p.y
            }
        }
        if (!controlX.isNaN()) out.add(Seg.Quad(controlX, controlY, startX, startY))
        else if (curX != startX || curY != startY) out.add(Seg.Line(startX, startY))
        return out
    }
}
