package com.handwrite.core.pdf

import com.handwrite.core.geom.Seg
import com.handwrite.core.layout.Connector
import com.handwrite.core.layout.HandLayout
import com.handwrite.core.layout.LaidOutPage
import com.handwrite.core.layout.PlacedGlyph
import com.handwrite.core.model.PaperStyle
import com.handwrite.core.model.PageSize
import com.handwrite.core.model.TextStyle
import com.handwrite.core.font.GlyphSource
import kotlin.math.roundToInt

/**
 * Renders a [com.handwrite.core.layout.LayoutResult] into a vector PDF.
 *
 * Everything (glyph outlines, connective strokes, ruling, ink transparency) becomes PDF path
 * operators, so the exported A5 page is resolution independent and prints crisply.
 */
object PdfPageComposer {

    fun compose(text: String, style: TextStyle, source: GlyphSource, maxPages: Int = 400): ByteArray {
        val result = HandLayout.layout(text, style, source, maxPages)
        val w = result.pages.first().widthPt
        val h = result.pages.first().heightPt
        val pdf = PdfWriter(w, h, title = "HandWrite", subject = style.mode.label)
        for ((i, page) in result.pages.withIndex()) {
            if (i > 0) pdf.newPage()
            drawPage(pdf.canvas(), page, style, source)
        }
        return pdf.build()
    }

    fun drawPage(c: PdfCanvas, page: LaidOutPage, style: TextStyle, source: GlyphSource) {
        val w = page.widthPt
        val h = page.heightPt
        // paper
        c.fillRect(0f, 0f, w, h, style.paperRgb, 1f)
        drawPaper(c, w, h, style, source, page.heightPt)

        val ink = style.inkRgb
        for (line in page.lines) {
            // joins first: the letters are painted on top and hide the ends of the stroke
            for (conn in line.connectors) drawConnector(c, conn, ink, style)
            for (g in line.glyphs) drawGlyph(c, g, ink)
        }
    }

    fun drawPaper(c: PdfCanvas, w: Float, h: Float, style: TextStyle, source: GlyphSource, pageH: Float) {
        val ruleColor = mix(style.paperRgb, 0x6F8FC4, 0.35f)
        when (style.paperStyle) {
            PaperStyle.BLANK -> Unit
            PaperStyle.RULED -> {
                // rules follow exactly the baselines produced by the layout engine
                val metrics = com.handwrite.core.layout.PageMetrics(style, source, pageH)
                var i = 0
                while (true) {
                    val y = metrics.ruleY(i)
                    if (y > h - 5f) break
                    if (y > 4f) c.line(6f, y, w - 4f, y, 0.45f, ruleColor, 0.55f)
                    i++
                    if (i > 400) break
                }
                val redX = metrics.marginL - 4f
                c.line(redX, 6f, redX, h - 6f, 0.5f, mix(style.paperRgb, 0xD46A6A, 0.4f), 0.5f)
            }
            PaperStyle.GRID -> {
                val step = 5f * 72f / 25.4f
                var x = step
                while (x < w) {
                    c.line(x, 0f, x, h, 0.35f, ruleColor, 0.45f)
                    x += step
                }
                var y = step
                while (y < h) {
                    c.line(0f, y, w, y, 0.35f, ruleColor, 0.45f)
                    y += step
                }
            }
            PaperStyle.DOTTED -> {
                val step = 5f * 72f / 25.4f
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) {
                        c.dot(x, y, 0.35f, ruleColor, 0.55f)
                        x += step
                    }
                    y += step
                }
            }
        }
    }

    fun drawGlyph(c: PdfCanvas, g: PlacedGlyph, ink: Int) {
        c.setFillRgb(ink)
        c.setAlpha(g.density.coerceIn(0.15f, 1f))
        var first = true
        for (seg in g.path.segs) {
            when (seg) {
                is Seg.Move -> {
                    if (!first) c.close()
                    val x = g.transform.applyX(seg.x, seg.y)
                    val y = g.transform.applyY(seg.x, seg.y)
                    c.moveTo(x, y)
                    first = false
                }
                is Seg.Line -> {
                    c.lineTo(g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y))
                }
                is Seg.Quad -> {
                    c.quadTo(
                        g.transform.applyX(seg.cx, seg.cy), g.transform.applyY(seg.cx, seg.cy),
                        g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y)
                    )
                }
                is Seg.Cubic -> {
                    c.curve(
                        g.transform.applyX(seg.c1x, seg.c1y), g.transform.applyY(seg.c1x, seg.c1y),
                        g.transform.applyX(seg.c2x, seg.c2y), g.transform.applyY(seg.c2x, seg.c2y),
                        g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y)
                    )
                }
            }
        }
        if (!first) c.close()
        if (g.evenOdd) c.fillEvenOdd() else c.fill()
    }

    /** Connective pen stroke: a cubic ribbon whose width follows the pen pressure. */
    fun drawConnector(c: PdfCanvas, conn: Connector, ink: Int, style: TextStyle) {
        val steps = 14
        val left = ArrayList<FloatArray>(steps + 1)
        val right = ArrayList<FloatArray>(steps + 1)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val mt = 1 - t
            val x = mt * mt * mt * conn.x0 + 3 * mt * mt * t * conn.c1x + 3 * mt * t * t * conn.c2x + t * t * t * conn.x1
            val y = mt * mt * mt * conn.y0 + 3 * mt * mt * t * conn.c1y + 3 * mt * t * t * conn.c2y + t * t * t * conn.y1
            // tangent of the cubic
            val tx = 3 * mt * mt * (conn.c1x - conn.x0) + 6 * mt * t * (conn.c2x - conn.c1x) + 3 * t * t * (conn.x1 - conn.c2x)
            val ty = 3 * mt * mt * (conn.c1y - conn.y0) + 6 * mt * t * (conn.c2y - conn.c1y) + 3 * t * t * (conn.y1 - conn.c2y)
            val len = kotlin.math.sqrt(tx * tx + ty * ty).coerceAtLeast(0.0001f)
            val nx = -ty / len
            val ny = tx / len
            val w = when {
                t < 0.5f -> conn.width0 + (conn.width1 - conn.width0) * (t / 0.5f)
                else -> conn.width1 + (conn.width2 - conn.width1) * ((t - 0.5f) / 0.5f)
            } / 2f
            left.add(floatArrayOf(x + nx * w, y + ny * w))
            right.add(floatArrayOf(x - nx * w, y - ny * w))
        }
        c.setFillRgb(ink)
        c.setAlpha(conn.density.coerceIn(0.2f, 1f))
        val all = left + right.asReversed()
        c.moveTo(all[0][0], all[0][1])
        for (i in 1 until all.size) c.lineTo(all[i][0], all[i][1])
        c.close()
        c.fillNonZero()
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val ar = a shr 16 and 0xFF
        val ag = a shr 8 and 0xFF
        val ab = a and 0xFF
        val br = b shr 16 and 0xFF
        val bg = b shr 8 and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * t).roundToInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).roundToInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * t).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or bl
    }

    fun mm(v: Float): Float = v * 72f / 25.4f
}
