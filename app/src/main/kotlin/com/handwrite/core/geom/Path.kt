package com.handwrite.core.geom

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/** Simple 2D affine transform: x' = a*x + c*y + e ; y' = b*x + d*y + f  (PDF/Android order). */
data class M2x3(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val e: Float = 0f,
    val f: Float = 0f
) {
    fun applyX(x: Float, y: Float) = a * x + c * y + e
    fun applyY(x: Float, y: Float) = b * x + d * y + f

    /** this * other (other applied first) */
    fun times(o: M2x3) = M2x3(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        e = a * o.e + c * o.f + e,
        f = b * o.e + d * o.f + f
    )

    companion object {
        fun translate(tx: Float, ty: Float) = M2x3(e = tx, f = ty)
        fun scale(sx: Float, sy: Float) = M2x3(a = sx, d = sy)
        fun rotateDeg(deg: Float): M2x3 {
            val r = Math.toRadians(deg.toDouble())
            val cs = cos(r).toFloat()
            val sn = sin(r).toFloat()
            return M2x3(a = cs, b = sn, c = -sn, d = cs)
        }

        fun skewX(deg: Float): M2x3 {
            val t = Math.tan(Math.toRadians(deg.toDouble())).toFloat()
            return M2x3(c = t)
        }
    }
}

/** Path segment in page coordinates. */
sealed class Seg {
    data class Move(val x: Float, val y: Float) : Seg()
    data class Line(val x: Float, val y: Float) : Seg()
    data class Quad(val cx: Float, val cy: Float, val x: Float, val y: Float) : Seg()
    data class Cubic(val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float, val x: Float, val y: Float) : Seg()
}

/** One closed contour of a glyph, as TrueType quadratic segments. */
class GlyphPath(val segs: List<Seg>, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {

    fun transformed(m: M2x3): GlyphPath {
        val out = ArrayList<Seg>(segs.size)
        var mnX = Float.MAX_VALUE
        var mnY = Float.MAX_VALUE
        var mxX = -Float.MAX_VALUE
        var mxY = -Float.MAX_VALUE
        fun track(x: Float, y: Float) {
            if (x < mnX) mnX = x
            if (x > mxX) mxX = x
            if (y < mnY) mnY = y
            if (y > mxY) mxY = y
        }
        for (s in segs) {
            when (s) {
                is Seg.Move -> {
                    val x = m.applyX(s.x, s.y); val y = m.applyY(s.x, s.y)
                    track(x, y); out.add(Seg.Move(x, y))
                }
                is Seg.Line -> {
                    val x = m.applyX(s.x, s.y); val y = m.applyY(s.x, s.y)
                    track(x, y); out.add(Seg.Line(x, y))
                }
                is Seg.Quad -> {
                    val cx = m.applyX(s.cx, s.cy); val cy = m.applyY(s.cx, s.cy)
                    val x = m.applyX(s.x, s.y); val y = m.applyY(s.x, s.y)
                    track(cx, cy); track(x, y); out.add(Seg.Quad(cx, cy, x, y))
                }
                is Seg.Cubic -> {
                    val c1x = m.applyX(s.c1x, s.c1y); val c1y = m.applyY(s.c1x, s.c1y)
                    val c2x = m.applyX(s.c2x, s.c2y); val c2y = m.applyY(s.c2x, s.c2y)
                    val x = m.applyX(s.x, s.y); val y = m.applyY(s.x, s.y)
                    track(c1x, c1y); track(c2x, c2y); track(x, y)
                    out.add(Seg.Cubic(c1x, c1y, c2x, c2y, x, y))
                }
            }
        }
        if (mnX > mxX) return this
        return GlyphPath(out, mnX, mnY, mxX, mxY)
    }

    /** Flattens the path into polylines (each a float array x0,y0,x1,y1,...). */
    fun flatten(tolerance: Float = 0.4f): List<FloatArray> {
        val result = ArrayList<FloatArray>()
        var current = ArrayList<Float>()
        var cx = 0f
        var cy = 0f
        for (s in segs) {
            when (s) {
                is Seg.Move -> {
                    if (current.size >= 6) result.add(current.toFloatArray())
                    current = ArrayList()
                    current.add(s.x); current.add(s.y)
                    cx = s.x; cy = s.y
                }
                is Seg.Line -> {
                    current.add(s.x); current.add(s.y)
                    cx = s.x; cy = s.y
                }
                is Seg.Quad -> {
                    val steps = quadSteps(cx, cy, s.cx, s.cy, s.x, s.y, tolerance)
                    var px = cx; var py = cy
                    for (i in 1..steps) {
                        val t = i.toFloat() / steps
                        val mt = 1 - t
                        val x = mt * mt * px + 2 * mt * t * s.cx + t * t * s.x
                        val y = mt * mt * py + 2 * mt * t * s.cy + t * t * s.y
                        current.add(x); current.add(y)
                    }
                    cx = s.x; cy = s.y
                }
                is Seg.Cubic -> {
                    val steps = 8
                    var px = cx; var py = cy
                    for (i in 1..steps) {
                        val t = i.toFloat() / steps
                        val mt = 1 - t
                        val x = mt * mt * mt * px + 3 * mt * mt * t * s.c1x + 3 * mt * t * t * s.c2x + t * t * t * s.x
                        val y = mt * mt * mt * py + 3 * mt * mt * t * s.c1y + 3 * mt * t * t * s.c2y + t * t * t * s.y
                        current.add(x); current.add(y)
                    }
                    cx = s.x; cy = s.y
                }
            }
        }
        if (current.size >= 6) result.add(current.toFloatArray())
        return result
    }

    private fun quadSteps(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, tol: Float): Int {
        val dx = x0 - 2 * x1 + x2
        val dy = y0 - 2 * y1 + y2
        val d = sqrt(dx * dx + dy * dy)
        if (d < 1e-4f) return 1
        val steps = ceil(sqrt(d / (4f * tol))).toInt()
        return steps.coerceIn(1, 24)
    }

    companion object {
        fun of(segs: List<Seg>): GlyphPath {
            var mnX = Float.MAX_VALUE; var mnY = Float.MAX_VALUE
            var mxX = -Float.MAX_VALUE; var mxY = -Float.MAX_VALUE
            fun track(x: Float, y: Float) {
                if (x < mnX) mnX = x
                if (x > mxX) mxX = x
                if (y < mnY) mnY = y
                if (y > mxY) mxY = y
            }
            for (s in segs) when (s) {
                is Seg.Move -> track(s.x, s.y)
                is Seg.Line -> track(s.x, s.y)
                is Seg.Quad -> { track(s.cx, s.cy); track(s.x, s.y) }
                is Seg.Cubic -> { track(s.c1x, s.c1y); track(s.c2x, s.c2y); track(s.x, s.y) }
            }
            if (mnX > mxX) { mnX = 0f; mnY = 0f; mxX = 0f; mxY = 0f }
            return GlyphPath(segs, mnX, mnY, mxX, mxY)
        }

        fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val dx = x1 - x0
            val dy = y1 - y0
            return sqrt(abs(dx * dx + dy * dy))
        }
    }
}
