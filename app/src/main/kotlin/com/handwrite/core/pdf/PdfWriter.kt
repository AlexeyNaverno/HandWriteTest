package com.handwrite.core.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.math.roundToInt

/**
 * Small PDF 1.7 writer: vector paths only, no external dependencies.
 *
 * Coordinate system: the caller works in "screen" coordinates with y pointing down
 * (that is what the layout engine produces); the writer flips the axis internally.
 */
class PdfWriter(
    private val pageWidth: Float,
    private val pageHeight: Float,
    private val title: String = "HandWrite",
    private val subject: String = ""
) {

    private val pages = ArrayList<ByteArray>()
    private var canvas = PdfCanvas(pageWidth, pageHeight)

    fun newPage() {
        pages.add(canvas.build())
        canvas = PdfCanvas(pageWidth, pageHeight)
    }

    fun canvas(): PdfCanvas = canvas

    fun build(): ByteArray {
        pages.add(canvas.build())

        val objects = ArrayList<ByteArray>()
        fun add(b: ByteArray): Int { objects.add(b); return objects.size }
        fun reserve(): Int { objects.add(ByteArray(0)); return objects.size }
        fun set(idx: Int, b: ByteArray) { objects[idx - 1] = b }

        val catalogIdx = reserve()
        val pagesIdx = reserve()
        // one ExtGState object holding a set of alpha values
        val gstateIdx = reserve()
        val infoIdx = reserve()

        val pageIdxs = ArrayList<Int>()
        val contentIdxs = ArrayList<Int>()
        for (p in pages) {
            val contentIdx = reserve()
            val pageIdx = reserve()
            pageIdxs.add(pageIdx)
            contentIdxs.add(contentIdx)
            val compressed = deflate(p)
            val header = "<< /Length ${compressed.size} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.ISO_8859_1)
            val footer = "\nendstream".toByteArray(Charsets.ISO_8859_1)
            val stream = ByteArray(header.size + compressed.size + footer.size)
            System.arraycopy(header, 0, stream, 0, header.size)
            System.arraycopy(compressed, 0, stream, header.size, compressed.size)
            System.arraycopy(footer, 0, stream, header.size + compressed.size, footer.size)
            set(contentIdx, obj(contentIdx, stream))
            val pageDict = StringBuilder()
            pageDict.append("<< /Type /Page /Parent $pagesIdx 0 R")
            pageDict.append(" /MediaBox [0 0 ${num(pageWidth)} ${num(pageHeight)}]")
            pageDict.append(" /Resources << /ExtGState << ${alphaResource(gstateIdx)} >> >>")
            pageDict.append(" /Contents $contentIdx 0 R >>")
            set(pageIdx, obj(pageIdx, pageDict.toString().toByteArray(Charsets.ISO_8859_1)))
        }

        set(catalogIdx, obj(catalogIdx, "<< /Type /Catalog /Pages $pagesIdx 0 R >>".toByteArray(Charsets.ISO_8859_1)))
        val kids = pageIdxs.joinToString(" ") { "$it 0 R" }
        set(pagesIdx, obj(pagesIdx, "<< /Type /Pages /Kids [$kids] /Count ${pageIdxs.size} >>".toByteArray(Charsets.ISO_8859_1)))
        set(gstateIdx, obj(gstateIdx, gstateDict().toByteArray(Charsets.ISO_8859_1)))
        set(infoIdx, obj(infoIdx, infoDict(title, subject).toByteArray(Charsets.ISO_8859_1)))

        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.7\n")
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
        val offsets = IntArray(objects.size + 1)
        for ((i, o) in objects.withIndex()) {
            offsets[i + 1] = out.size()
            out.write(o)
        }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n")
        write("0000000000 65535 f \n")
        for (i in 1..objects.size) {
            write(String.format("%010d 00000 n \n", offsets[i]))
        }
        write("trailer\n<< /Size ${objects.size + 1} /Root $catalogIdx 0 R /Info $infoIdx 0 R >>\n")
        write("startxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private fun alphaResource(gstateIdx: Int): String {
        val sb = StringBuilder()
        for (a in ALPHAS) sb.append("/GS${alphaIndex(a)} << /CA ${num(a)} /ca ${num(a)} /BM /Multiply >> ")
        return sb.toString()
    }

    private fun gstateDict(): String {
        val sb = StringBuilder("<< ")
        for (a in ALPHAS) sb.append("/GS${alphaIndex(a)} << /Type /ExtGState /CA ${num(a)} /ca ${num(a)} /BM /Multiply >> ")
        sb.append(">>")
        return sb.toString()
    }

    private fun infoDict(title: String, subject: String): String {
        val sb = StringBuilder("<< /Title ${pdfString(title)} /Author ${pdfString("HandWrite")}")
        sb.append(" /Creator ${pdfString("HandWrite 0.0.1")} /Producer ${pdfString("HandWrite PDF engine")}")
        if (subject.isNotEmpty()) sb.append(" /Subject ${pdfString(subject)}")
        sb.append(" /CreationDate (D:20261110120000Z) /ModDate (D:20261110120000Z) >>")
        return sb.toString()
    }

    private fun obj(num: Int, body: ByteArray): ByteArray {
        val head = "$num 0 obj\n".toByteArray(Charsets.ISO_8859_1)
        val tail = "\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(head.size + body.size + tail.size)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(body, 0, out, head.size, body.size)
        System.arraycopy(tail, 0, out, head.size + body.size, tail.size)
        return out
    }

    companion object {
        val ALPHAS = floatArrayOf(1f, 0.95f, 0.9f, 0.85f, 0.8f, 0.75f, 0.7f, 0.6f, 0.5f, 0.4f, 0.25f, 0.12f)

        fun alphaIndex(a: Float): Int {
            var best = 0
            var bestDiff = Float.MAX_VALUE
            for ((i, v) in ALPHAS.withIndex()) {
                val d = kotlin.math.abs(v - a)
                if (d < bestDiff) { bestDiff = d; best = i }
            }
            return best
        }

        fun num(v: Float): String {
            if (v.isNaN() || v.isInfinite()) return "0"
            val scaled = (v * 1000f).roundToInt() / 1000f
            if (scaled == scaled.toLong().toFloat()) return scaled.toLong().toString()
            var s = String.format(java.util.Locale.US, "%.3f", scaled)
            s = s.trimEnd('0')
            if (s.endsWith(".")) s = s.dropLast(1)
            return s
        }

        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION, false)
            d.setInput(data)
            d.finish()
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            while (!d.finished()) {
                val n = d.deflate(tmp)
                if (n > 0) buf.write(tmp, 0, n)
            }
            d.end()
            return buf.toByteArray()
        }

        /** PDF text string: UTF-16BE with BOM when non-ASCII. */
        fun pdfString(s: String): String {
            val ascii = s.all { it.code in 32..126 }
            if (ascii) {
                val esc = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                return "($esc)"
            }
            val sb = StringBuilder("<FEFF")
            for (b in s.toByteArray(Charsets.UTF_16BE)) sb.append(String.format("%02X", b.toInt() and 0xFF))
            sb.append(">")
            return sb.toString()
        }
    }
}

/** Content-stream builder with a y-down coordinate system. */
class PdfCanvas(private val pageW: Float, private val pageH: Float) {

    private val sb = StringBuilder(1 shl 16)
    private var pathOpen = false
    private var currentAlpha = 1f
    private var currentRgb = -1

    private fun y(v: Float): Float = pageH - v

    fun setAlpha(a: Float) {
        val idx = PdfWriter.alphaIndex(a)
        sb.append("/GS$idx gs\n")
        currentAlpha = PdfWriter.ALPHAS[idx]
    }

    fun setFillRgb(rgb: Int) {
        if (rgb == currentRgb) return
        currentRgb = rgb
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        sb.append(PdfWriter.num(r)).append(' ').append(PdfWriter.num(g)).append(' ').append(PdfWriter.num(b)).append(" rg\n")
    }

    fun setStrokeRgb(rgb: Int) {
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        sb.append(PdfWriter.num(r)).append(' ').append(PdfWriter.num(g)).append(' ').append(PdfWriter.num(b)).append(" RG\n")
    }

    fun moveTo(x: Float, y0: Float) {
        if (pathOpen) sb.append('\n')
        sb.append(PdfWriter.num(x)).append(' ').append(PdfWriter.num(y(y0))).append(" m")
        pathOpen = true
        lastPoint = x to y0
    }

    fun lineTo(x: Float, y0: Float) {
        sb.append(' ').append(PdfWriter.num(x)).append(' ').append(PdfWriter.num(y(y0))).append(" l")
        lastPoint = x to y0
    }

    fun quadTo(cx: Float, cy: Float, x: Float, y1: Float) {
        // convert quadratic to cubic
        val x0: Float
        val y0: Float
        val last = lastPoint
        x0 = last.first
        y0 = last.second
        val c1x = x0 + 2f / 3f * (cx - x0)
        val c1y = y0 + 2f / 3f * (cy - y0)
        val c2x = x + 2f / 3f * (cx - x)
        val c2y = y1 + 2f / 3f * (cy - y1)
        sb.append(' ').append(PdfWriter.num(c1x)).append(' ').append(PdfWriter.num(y(c1y)))
        sb.append(' ').append(PdfWriter.num(c2x)).append(' ').append(PdfWriter.num(y(c2y)))
        sb.append(' ').append(PdfWriter.num(x)).append(' ').append(PdfWriter.num(y(y1))).append(" c")
        lastPoint = x to y1
    }

    private var lastPoint: Pair<Float, Float> = 0f to 0f

    fun close() {
        if (pathOpen) sb.append(" h")
    }

    fun fill() {
        if (!pathOpen) return
        sb.append(" f*\n")
        pathOpen = false
    }

    fun fillEvenOdd() {
        if (!pathOpen) return
        sb.append(" f*\n")
        pathOpen = false
    }

    fun fillNonZero() {
        if (!pathOpen) return
        sb.append(" f\n")
        pathOpen = false
    }

    fun stroke(widthPt: Float) {
        if (!pathOpen) return
        sb.append(' ').append(PdfWriter.num(widthPt)).append(" w S\n")
        pathOpen = false
    }

    fun rect(x: Float, y0: Float, w: Float, h: Float) {
        if (!pathOpen) {
            sb.append(PdfWriter.num(x)).append(' ').append(PdfWriter.num(y(y0 + h)))
            sb.append(' ').append(PdfWriter.num(w)).append(' ').append(PdfWriter.num(h)).append(" re")
            pathOpen = true
        }
    }

    fun fillRect(x: Float, y0: Float, w: Float, h: Float, rgb: Int, alpha: Float = 1f) {
        setFillRgb(rgb)
        setAlpha(alpha)
        rect(x, y0, w, h)
        sb.append(" f\n")
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, widthPt: Float, rgb: Int, alpha: Float = 1f) {
        setStrokeRgb(rgb)
        setAlpha(alpha)
        moveTo(x0, y0)
        lineTo(x1, y1)
        stroke(widthPt)
    }

    fun dot(cx: Float, cy: Float, r: Float, rgb: Int, alpha: Float = 1f) {
        setFillRgb(rgb)
        setAlpha(alpha)
        val k = 0.5523f * r
        // draw a small 4-segment circle as its own path (no reliance on 'current path' state)
        val separate = pathOpen
        if (separate) {
            sb.append("\n")
            pathOpen = false
        }
        sb.append(PdfWriter.num(cx - r)).append(' ').append(PdfWriter.num(y(cy))).append(" m")
        sb.append(' ').append(PdfWriter.num(cx - r)).append(' ').append(PdfWriter.num(y(cy - k)))
        sb.append(' ').append(PdfWriter.num(cx - k)).append(' ').append(PdfWriter.num(y(cy - r)))
        sb.append(' ').append(PdfWriter.num(cx)).append(' ').append(PdfWriter.num(y(cy - r))).append(" c")
        sb.append(' ').append(PdfWriter.num(cx + k)).append(' ').append(PdfWriter.num(y(cy - r)))
        sb.append(' ').append(PdfWriter.num(cx + r)).append(' ').append(PdfWriter.num(y(cy - k)))
        sb.append(' ').append(PdfWriter.num(cx + r)).append(' ').append(PdfWriter.num(y(cy))).append(" c")
        sb.append(' ').append(PdfWriter.num(cx + r)).append(' ').append(PdfWriter.num(y(cy + k)))
        sb.append(' ').append(PdfWriter.num(cx + k)).append(' ').append(PdfWriter.num(y(cy + r)))
        sb.append(' ').append(PdfWriter.num(cx)).append(' ').append(PdfWriter.num(y(cy + r))).append(" c")
        sb.append(' ').append(PdfWriter.num(cx - k)).append(' ').append(PdfWriter.num(y(cy + r)))
        sb.append(' ').append(PdfWriter.num(cx - r)).append(' ').append(PdfWriter.num(y(cy + k)))
        sb.append(' ').append(PdfWriter.num(cx - r)).append(' ').append(PdfWriter.num(y(cy))).append(" c f\n")
        pathOpen = false
        lastPoint = cx to cy
    }

    fun curve(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y1: Float) {
        sb.append(' ').append(PdfWriter.num(c1x)).append(' ').append(PdfWriter.num(y(c1y)))
        sb.append(' ').append(PdfWriter.num(c2x)).append(' ').append(PdfWriter.num(y(c2y)))
        sb.append(' ').append(PdfWriter.num(x)).append(' ').append(PdfWriter.num(y(y1))).append(" c")
        lastPoint = x to y1
    }

    fun build(): ByteArray = sb.toString().toByteArray(Charsets.ISO_8859_1)
}
