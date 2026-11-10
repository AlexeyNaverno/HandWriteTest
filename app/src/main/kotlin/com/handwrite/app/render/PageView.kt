package com.handwrite.app.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.handwrite.core.geom.Seg
import com.handwrite.core.layout.HandLayout
import com.handwrite.core.layout.LaidOutPage
import com.handwrite.core.layout.LayoutResult
import com.handwrite.core.layout.Connector
import com.handwrite.core.font.GlyphSource
import com.handwrite.core.model.PaperStyle
import com.handwrite.core.model.TextStyle
import com.handwrite.core.layout.PageMetrics

/**
 * Live preview of the A5 page. It draws the very same layout objects the PDF exporter writes,
 * so what you see is what you export (same jitter, same joins, same page breaks).
 */
class PageView(context: Context) : View(context) {

    private var result: LayoutResult? = null
    private var source: GlyphSource? = null
    private var pageIndex = 0
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pageRect = RectF()

    var onPageChanged: ((Int, Int) -> Unit)? = null

    private var lastPinch = 0f
    private var zoom = 1f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (zoom > 1.05f) return false
            if (e1 == null) return false
            val dx = e2.x - e1.x
            if (kotlin.math.abs(dx) > width * 0.12f && kotlin.math.abs(vx) > 400) {
                pageIndex += if (dx < 0) 1 else -1
                clampPage()
                invalidate()
                notifyPage()
                return true
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (zoom > 1.05f) {
                zoom = 1f
                invalidate()
                return true
            }
            val r = result ?: return false
            if (e.x > pageRect.right - 40 * context.resources.displayMetrics.density) {
                pageIndex++
                clampPage()
                invalidate()
                notifyPage()
                return true
            }
            return false
        }
    })

    init {
        setWillNotDraw(false)
        fill.style = Paint.Style.FILL
        fill.isAntiAlias = true
        stroke.style = Paint.Style.STROKE
        text.textSize = 13f * resources.displayMetrics.scaledDensity
        text.color = Color.parseColor("#FF8A9099")
        text.typeface = Typeface.DEFAULT
    }

    fun show(result: LayoutResult, source: GlyphSource, page: Int = 0) {
        this.result = result
        this.source = source
        this.pageIndex = page.coerceIn(0, maxOf(0, result.pages.size - 1))
        invalidate()
        notifyPage()
    }

    fun setPage(page: Int) {
        pageIndex = page
        clampPage()
        invalidate()
        notifyPage()
    }

    fun pageCount(): Int = result?.pages?.size ?: 0

    private fun clampPage() {
        val n = pageCount()
        if (pageIndex < 0) pageIndex = 0
        if (pageIndex > n - 1) pageIndex = maxOf(0, n - 1)
    }

    private fun notifyPage() = onPageChanged?.invoke(pageIndex, pageCount())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            val d = Math.hypot(
                (event.getX(0) - event.getX(1)).toDouble(),
                (event.getY(0) - event.getY(1)).toDouble()
            ).toFloat()
            if (lastPinch > 0 && d > 0) {
                zoom = (zoom * d / lastPinch).coerceIn(1f, 3.5f)
                invalidate()
            }
            lastPinch = d
            return true
        }
        lastPinch = 0f
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = result ?: run {
            canvas.drawColor(com.handwrite.app.ui.Ui.SURFACE)
            return
        }
        canvas.drawColor(com.handwrite.app.ui.Ui.SURFACE)
        val page = res.pages.getOrNull(pageIndex) ?: return
        val pad = 10f * resources.displayMetrics.density
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        val scale = minOf(availW / page.widthPt, availH / page.heightPt) * zoom
        val w = page.widthPt * scale
        val h = page.heightPt * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        pageRect.set(left, top, left + w, top + h)

        // shadow-ish border
        fill.style = Paint.Style.FILL
        fill.color = 0x22000000
        canvas.drawRoundRect(RectF(pageRect.left + 2, pageRect.top + 3, pageRect.right + 2, pageRect.bottom + 3), 6f, 6f, fill)

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        drawPage(canvas, page, res.style, source)
        canvas.restore()
    }

    private fun drawPage(canvas: Canvas, page: LaidOutPage, style: TextStyle, source: GlyphSource?) {
        fill.style = Paint.Style.FILL
        fill.color = style.paperColor
        canvas.drawRect(0f, 0f, page.widthPt, page.heightPt, fill)
        drawPaper(canvas, page, style, source)
        val ink = style.inkRgb or (0xFF shl 24)
        // joins first, letters on top
        for (line in page.lines) for (c in line.connectors) drawConnector(canvas, c, ink, style)
        for (line in page.lines) for (g in line.glyphs) drawGlyph(canvas, g, ink, g.density)
    }

    private fun drawPaper(canvas: Canvas, page: LaidOutPage, style: TextStyle, source: GlyphSource?) {
        val w = page.widthPt
        val h = page.heightPt
        val rule = com.handwrite.core.pdf.PdfPageComposer.mix(style.paperRgb, 0x6F8FC4, 0.35f) or (0xFF shl 24)
        stroke.style = Paint.Style.STROKE
        stroke.color = rule
        stroke.strokeWidth = 0.45f
        when (style.paperStyle) {
            PaperStyle.BLANK -> Unit
            PaperStyle.RULED -> {
                if (source != null) {
                    val metrics = PageMetrics(style, source, h)
                    var i = 0
                    while (i < 400) {
                        val y = metrics.ruleY(i)
                        if (y > h - 5f) break
                        if (y > 4f) canvas.drawLine(6f, y, w - 4f, y, stroke)
                        i++
                    }
                }
                stroke.color = com.handwrite.core.pdf.PdfPageComposer.mix(style.paperRgb, 0xD46A6A, 0.4f) or (0xFF shl 24)
                val x = style.marginLeftMm * 72f / 25.4f - 4f
                canvas.drawLine(x, 6f, x, h - 6f, stroke)
            }
            PaperStyle.GRID -> {
                val step = 5f * 72f / 25.4f
                var x = step
                while (x < w) { canvas.drawLine(x, 0f, x, h, stroke); x += step }
                var y = step
                while (y < h) { canvas.drawLine(0f, y, w, y, stroke); y += step }
            }
            PaperStyle.DOTTED -> {
                val step = 5f * 72f / 25.4f
                fill.color = rule
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) { canvas.drawCircle(x, y, 0.35f, fill); x += step }
                    y += step
                }
            }
        }
    }

    private fun pathFor(g: com.handwrite.core.layout.PlacedGlyph): Path {
        val p = Path()
        if (g.evenOdd) p.fillType = Path.FillType.EVEN_ODD
        for (seg in g.path.segs) {
            when (seg) {
                is Seg.Move -> p.moveTo(g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y))
                is Seg.Line -> p.lineTo(g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y))
                is Seg.Quad -> p.quadTo(
                    g.transform.applyX(seg.cx, seg.cy), g.transform.applyY(seg.cx, seg.cy),
                    g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y)
                )
                is Seg.Cubic -> p.cubicTo(
                    g.transform.applyX(seg.c1x, seg.c1y), g.transform.applyY(seg.c1x, seg.c1y),
                    g.transform.applyX(seg.c2x, seg.c2y), g.transform.applyY(seg.c2x, seg.c2y),
                    g.transform.applyX(seg.x, seg.y), g.transform.applyY(seg.x, seg.y)
                )
            }
        }
        p.close()
        return p
    }

    private fun drawGlyph(canvas: Canvas, g: com.handwrite.core.layout.PlacedGlyph, ink: Int, density: Float) {
        fill.color = ink
        fill.alpha = (255 * density.coerceIn(0.15f, 1f)).toInt()
        fill.style = Paint.Style.FILL
        canvas.drawPath(pathFor(g), fill)
    }

    private fun drawConnector(canvas: Canvas, c: Connector, ink: Int, style: TextStyle) {
        val steps = 12
        val path = Path()
        val w0 = c.width0 / 2f
        val w1 = c.width1 / 2f
        val w2 = c.width2 / 2f
        val pts = ArrayList<FloatArray>(steps + 1)
        val ws = FloatArray(steps + 1)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val mt = 1 - t
            val x = mt * mt * mt * c.x0 + 3 * mt * mt * t * c.c1x + 3 * mt * t * t * c.c2x + t * t * t * c.x1
            val y = mt * mt * mt * c.y0 + 3 * mt * mt * t * c.c1y + 3 * mt * t * t * c.c2y + t * t * t * c.y1
            val tx = 3 * mt * mt * (c.c1x - c.x0) + 6 * mt * t * (c.c2x - c.c1x) + 3 * t * t * (c.x1 - c.c2x)
            val ty = 3 * mt * mt * (c.c1y - c.y0) + 6 * mt * t * (c.c2y - c.c1y) + 3 * t * t * (c.y1 - c.c2y)
            val len = Math.hypot(tx.toDouble(), ty.toDouble()).toFloat().coerceAtLeast(0.0001f)
            pts.add(floatArrayOf(x, y, -ty / len, tx / len))
            ws[i] = if (t < 0.5f) w0 + (w1 - w0) * (t / 0.5f) else w1 + (w2 - w1) * ((t - 0.5f) / 0.5f)
        }
        path.moveTo(pts[0][0] + pts[0][2] * ws[0], pts[0][1] + pts[0][3] * ws[0])
        for (i in 1..steps) path.lineTo(pts[i][0] + pts[i][2] * ws[i], pts[i][1] + pts[i][3] * ws[i])
        for (i in steps downTo 0) path.lineTo(pts[i][0] - pts[i][2] * ws[i], pts[i][1] - pts[i][3] * ws[i])
        path.close()
        fill.style = Paint.Style.FILL
        fill.color = ink
        fill.alpha = (255 * c.density.coerceIn(0.2f, 1f)).toInt()
        canvas.drawPath(path, fill)
    }
}
