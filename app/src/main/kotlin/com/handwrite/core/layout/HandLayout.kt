package com.handwrite.core.layout

import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.GlyphSource
import com.handwrite.core.geom.GlyphPath
import com.handwrite.core.geom.M2x3
import com.handwrite.core.geom.Seg
import com.handwrite.core.model.TextStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One glyph placed on a page. Coordinates are in PDF points, y down. */
class PlacedGlyph(
    val gid: Int,
    val path: GlyphPath,
    val cp: Int,
    val transform: M2x3,
    val advance: Float,
    val density: Float,
    val lineIndex: Int,
    val wordIndex: Int,
    /** page-space y of the glyph baseline (used to find natural pen exits) */
    val baselineY: Float = 0f,
    /** fill with the even-odd rule (learned glyphs) instead of non-zero */
    val evenOdd: Boolean = false
) {
    val x0 get() = path.minX
    val y0 get() = path.minY
    val x1 get() = path.maxX
    val y1 get() = path.maxY
}

/**
 * A connective pen stroke between two letters. The stroke is a tapered ribbon:
 * [width0] at the start, [width1] in the middle and [width2] at the end,
 * which is how a real pen leaves/enters the paper (нажим).
 */
class Connector(
    val x0: Float, val y0: Float,
    val c1x: Float, val c1y: Float,
    val c2x: Float, val c2y: Float,
    val x1: Float, val y1: Float,
    val width0: Float,
    val width1: Float,
    val width2: Float,
    val density: Float
)

class PlacedLine(
    val index: Int,
    val baselineY: Float,
    val xStart: Float,
    val xEnd: Float,
    val glyphs: List<PlacedGlyph>,
    val connectors: List<Connector>,
    val words: Int
)

class LaidOutPage(
    val index: Int,
    val widthPt: Float,
    val heightPt: Float,
    val lines: List<PlacedLine>,
    val useFontGlyphs: Boolean
)

class LayoutResult(
    val pages: List<LaidOutPage>,
    val style: TextStyle,
    val fontName: String
)

/**
 * Lays text out the way a hand would write it.
 *
 * text -> lines (word wrap) -> words -> glyphs (OpenType liga/calt when the font has them)
 *      -> per-glyph stable jitter, slant, variable-font weight (нажим)
 *      -> connective pen strokes between letters (курсивное соединение).
 */
object HandLayout {

    fun layout(text: String, style: TextStyle, source: GlyphSource, maxPages: Int = Int.MAX_VALUE): LayoutResult {
        val pageW = style.paperSize.widthPt
        val pageH = style.paperSize.heightPt
        val metrics = PageMetrics(style, source, pageH)
        val size = style.sizePt
        val scale = metrics.scale
        val leading = metrics.leading

        val anchors = HashMap<Int, Anchors>()
        val linesText = buildLines(text, style, source, scale, metrics.marginL, metrics.marginR, size)
        val perPage = max(1, ((metrics.marginB - metrics.marginT) / leading).toInt())
        val pages = ArrayList<LaidOutPage>()
        var lineIdx = 0
        var pageIdx = 0
        val useFontGlyphs = source is FileGlyphSource
        while (lineIdx < linesText.size && pageIdx < maxPages) {
            val chunk = linesText.subList(lineIdx, min(linesText.size, lineIdx + perPage))
            val placed = ArrayList<PlacedLine>(chunk.size)
            for ((i, ln) in chunk.withIndex()) {
                val baseline = metrics.baselineOf(i)
                placed.add(placeLine(ln, lineIdx + i, baseline, style, source, metrics, anchors, text))
            }
            pages.add(LaidOutPage(pageIdx, pageW, pageH, placed, useFontGlyphs))
            lineIdx += chunk.size
            pageIdx++
        }
        if (pages.isEmpty()) pages.add(LaidOutPage(0, pageW, pageH, emptyList(), useFontGlyphs))
        return LayoutResult(pages, style, source.name)
    }

    /* ------------------------------------------------------------------ */
    /* line breaking                                                       */
    /* ------------------------------------------------------------------ */

    private class LineText(val words: List<String>, val isParagraphEnd: Boolean, val indent: Float = 0f)

    private fun buildLines(
        text: String,
        style: TextStyle,
        source: GlyphSource,
        scale: Float,
        marginL: Float,
        marginR: Float,
        size: Float
    ): List<LineText> {
        val maxWidth = marginR - marginL
        val indent = style.paragraphIndentEm * size
        val out = ArrayList<LineText>()
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (p in paragraphs) {
            val words = p.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                out.add(LineText(emptyList(), true))
                continue
            }
            var current = ArrayList<String>()
            var width = 0f
            var firstOfParagraph = true
            for (w in words) {
                val ww = measureWord(w, style, source, scale, size)
                val space = spaceWidth(style, source, scale, size)
                val startIndent = if (firstOfParagraph) indent else 0f
                val add = if (current.isEmpty()) ww else space + ww
                if (width + startIndent + add > maxWidth && current.isNotEmpty()) {
                    out.add(LineText(current, false, startIndent))
                    current = ArrayList()
                    current.add(w)
                    width = ww
                    firstOfParagraph = false
                } else {
                    width += add
                    current.add(w)
                }
            }
            if (current.isNotEmpty()) out.add(LineText(current, true, if (firstOfParagraph) indent else 0f))
        }
        return out
    }

    private fun spaceWidth(style: TextStyle, source: GlyphSource, scale: Float, size: Float): Float {
        val spaceGid = source.gid(' '.code)
        val base = if (spaceGid != 0) source.advance(spaceGid) * scale else size * 0.28f
        return base + style.wordSpacingEm * size
    }

    /** code points of a string */
    private fun codePoints(s: String): List<Int> {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out.add(cp)
            i += Character.charCount(cp)
        }
        return out
    }

    /**
     * Raw glyph ids for a word (incl. ligatures/alternates when enabled).
     *
     * Glyphs that the source cannot draw at all are dropped instead of being emitted with an empty
     * outline: an empty glyph still consumes an advance, which shifts the rest of the line and is
     * what made lines look ragged.
     */
    fun wordGlyphs(word: String, style: TextStyle, source: GlyphSource): IntArray {
        val cps = codePoints(word)
        val keep = ArrayList<Int>(cps.size)
        for (cp in cps) {
            var g = source.gid(cp)
            if (g == 0 && cp != 0 && !Character.isWhitespace(cp)) g = source.gid('?'.code)
            if (g != 0) keep.add(g)
        }
        if (keep.isEmpty()) return IntArray(0)
        var gids = keep.toIntArray()
        if (style.useFontLigatures || style.useContextualAlternates) {
            val tags = HashSet<String>()
            if (style.useFontLigatures) { tags.add("liga"); tags.add("rlig"); tags.add("clig") }
            if (style.useContextualAlternates) { tags.add("calt") }
            gids = source.applyFeatures(gids, tags)
        }
        return gids
    }

    /**
     * Advance of a glyph in font units. A source may return 0 for a glyph it can draw but has no
     * metric for (learned letters); the caller then falls back to the outline width, so text never
     * collapses into a single point.
     */
    fun effectiveAdvance(gid: Int, source: GlyphSource, path: GlyphPath): Int {
        val a = source.advance(gid)
        if (a > 0) return a
        val w = path.maxX - path.minX
        return if (w > 0f) (w + source.unitsPerEm * 0.04f).toInt() else (source.unitsPerEm * 0.45f).toInt()
    }

    private fun measureWord(word: String, style: TextStyle, source: GlyphSource, scale: Float, size: Float): Float {
        val gids = wordGlyphs(word, style, source)
        var w = 0f
        for ((i, g) in gids.withIndex()) {
            w += effectiveAdvance(g, source, source.path(g, emptyMap())) * scale
            if (i + 1 < gids.size) w += source.kerning(g, gids[i + 1]) * scale
        }
        w += style.letterSpacingEm * size * max(0, gids.size - 1)
        return w
    }

    /* ------------------------------------------------------------------ */
    /* placing a line                                                      */
    /* ------------------------------------------------------------------ */

    private fun placeLine(
        line: LineText,
        lineIndex: Int,
        baselineY: Float,
        style: TextStyle,
        source: GlyphSource,
        metrics: PageMetrics,
        anchors: HashMap<Int, Anchors>,
        sourceText: String
    ): PlacedLine {
        val size = style.sizePt
        val scale = metrics.scale
        val marginL = metrics.marginL
        val glyphs = ArrayList<PlacedGlyph>()
        val connectors = ArrayList<Connector>()
        val rnd = DetRandom(style.seed * 31 + lineIndex * 7919L)

        val coords = HashMap<String, Float>()
        if (source.supportsWeightAxis) coords["wght"] = pressureWeight(style, source)

        val waveAmp = style.baselineWaveEm * size
        val driftPhase = rnd.next() * 6.28f
        val driftFreq = 0.9f + rnd.next() * 0.9f
        val slant = M2x3.skewX(style.slantDeg)

        val space = spaceWidth(style, source, scale, size)
        val rawWidths = line.words.map { measureWord(it, style, source, scale, size) }
        val naturalWidth = rawWidths.sum() + space * max(0, line.words.size - 1)
        val maxWidth = metrics.marginR - marginL - line.indent
        val justifyExtra = if (style.justify && line.words.size > 1 && !line.isParagraphEnd && maxWidth > naturalWidth) {
            (maxWidth - naturalWidth) / (line.words.size - 1)
        } else 0f

        var x = marginL + line.indent
        for ((wi, word) in line.words.withIndex()) {
            val cps = codePoints(word)
            val gids = wordGlyphs(word, style, source)
            // when the font joined letters itself (ligature), we must not add synthetic connectors
            val joinedByFont = gids.size != cps.size
            var prev: LetterRef? = null
            for ((gi, gid) in gids.withIndex()) {
                val path = source.path(gid, coords)
                val adv = effectiveAdvance(gid, source, path) * scale
                val cp = if (!joinedByFont && gi < cps.size) cps[gi] else 0
                val isLast = gi == gids.size - 1

                val jx = (rnd.next() - 0.5f) * 2f * style.jitterEm * size
                val jy = (rnd.next() - 0.5f) * 2f * style.jitterEm * size * 0.8f
                val rot = (rnd.next() - 0.5f) * 2f * style.jitterRotDeg
                val pressureHere = localPressure(style, rnd, isLast)
                val wave = sin((x / max(1f, size * 12f)) * driftFreq + driftPhase) * waveAmp
                val baseline = baselineY + wave + jy
                val m = M2x3.translate(x + jx, baseline)
                    .times(M2x3.rotateDeg(rot))
                    .times(slant)
                    .times(M2x3.scale(scale, -scale))
                val placed = PlacedGlyph(
                    gid = gid,
                    path = path,
                    cp = cp,
                    transform = m,
                    advance = adv,
                    density = style.inkDensity * (0.86f + 0.14f * pressureHere),
                    lineIndex = lineIndex,
                    wordIndex = wi,
                    baselineY = baseline,
                    evenOdd = source.usesEvenOddFill && source is com.handwrite.core.handwriting.AtlasGlyphSource &&
                        source.atlas.hasCode(gid)
                )
                glyphs.add(placed)

                val current = if (cp != 0) LetterRef(cp, placed, anchorsOf(gid, path, anchors), pressureHere) else null
                if (style.connectLetters && !joinedByFont && current != null && prev != null && !isLast) {
                    addConnector(connectors, prev, current, style, size, rnd, scale)
                }
                if (current != null) prev = current
                // GPOS kerning between this glyph and the next one keeps cursive joins tight
                val nextGid = if (gi + 1 < gids.size) gids[gi + 1] else 0
                val kern = if (nextGid != 0) source.kerning(gid, nextGid) else 0
                x += adv + kern * scale + style.letterSpacingEm * size
            }
            x += space + justifyExtra
        }
        return PlacedLine(lineIndex, baselineY, marginL, x, glyphs, connectors, line.words.size)
    }

    /* ------------------------------------------------------------------ */
    /* connective strokes                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Natural pen exit (rightmost point near the baseline) and pen entry (leftmost point near the
     * baseline) of a glyph, in *font units* (y up). Returning to the baseline is what makes a
     * cursive join look believable: the last stroke of one letter flows into the first of the next.
     */
    private class Anchors(val exitX: Float, val exitY: Float, val entryX: Float, val entryY: Float)

    private class LetterRef(
        val cp: Int,
        val placed: PlacedGlyph,
        val anchors: Anchors,
        val pressure: Float
    )

    /**
     * Pen exit/entry points of a glyph, in font units (y up, baseline at 0).
     *
     * Cursive joins happen at the *baseline*: the pen leaves a letter from its lowest right area
     * and starts the next letter in its lowest left area. Taking the extreme x over the whole
     * outline is wrong, because letters such as "м", "л" or "и" reach their rightmost point high
     * above the baseline (the middle of the letter), which made every join look like a small
     * tick. Only points in the lower 35 % of the ink are considered here.
     */
    private fun anchorsOf(gid: Int, path: GlyphPath, cache: HashMap<Int, Anchors>): Anchors {
        cache[gid]?.let { return it }
        var maxX = -Float.MAX_VALUE
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (seg in path.segs) {
            when (seg) {
                is Seg.Move -> {
                    if (seg.x > maxX) maxX = seg.x; if (seg.x < minX) minX = seg.x
                    if (seg.y > maxY) maxY = seg.y; if (seg.y < minY) minY = seg.y
                }
                is Seg.Line -> {
                    if (seg.x > maxX) maxX = seg.x; if (seg.x < minX) minX = seg.x
                    if (seg.y > maxY) maxY = seg.y; if (seg.y < minY) minY = seg.y
                }
                is Seg.Quad -> {
                    for (x in doubleArrayOf(seg.cx.toDouble(), seg.x.toDouble())) {
                        val xf = x.toFloat(); if (xf > maxX) maxX = xf; if (xf < minX) minX = xf
                    }
                    for (y in doubleArrayOf(seg.cy.toDouble(), seg.y.toDouble())) {
                        val yf = y.toFloat(); if (yf > maxY) maxY = yf; if (yf < minY) minY = yf
                    }
                }
                is Seg.Cubic -> {
                    for (x in doubleArrayOf(seg.c1x.toDouble(), seg.c2x.toDouble(), seg.x.toDouble())) {
                        val xf = x.toFloat(); if (xf > maxX) maxX = xf; if (xf < minX) minX = xf
                    }
                    for (y in doubleArrayOf(seg.c1y.toDouble(), seg.c2y.toDouble(), seg.y.toDouble())) {
                        val yf = y.toFloat(); if (yf > maxY) maxY = yf; if (yf < minY) minY = yf
                    }
                }
            }
        }
        if (maxX < minX) { maxX = 0f; minX = 0f }
        if (maxY < minY) { maxY = 0f; minY = 0f }
        val width = max(maxX - minX, 1f)
        // the "join zone": the lowest third of the letter, where real cursive connects
        val zoneTop = minY + (maxY - minY) * 0.35f
        var exitY = minY
        var entryY = minY
        var exitX = maxX
        var entryX = minX
        var bestExit = -Float.MAX_VALUE
        var bestEntry = -Float.MAX_VALUE
        fun consider(x: Float, y: Float) {
            if (y > zoneTop) return
            // inside the zone prefer the point furthest out and lowest (closest to the baseline)
            val score = (if (x >= minX) (x - minX) / width else 0f) - (y - minY) / max(1f, maxY - minY)
            if (score > bestExit && x >= maxX - width * 0.18f) { bestExit = score; exitX = x; exitY = y }
            val scoreEntry = (if (x <= maxX) (maxX - x) / width else 0f) - (y - minY) / max(1f, maxY - minY)
            if (scoreEntry > bestEntry && x <= minX + width * 0.18f) { bestEntry = scoreEntry; entryX = x; entryY = y }
        }
        for (seg in path.segs) {
            when (seg) {
                is Seg.Move -> consider(seg.x, seg.y)
                is Seg.Line -> consider(seg.x, seg.y)
                is Seg.Quad -> { consider(seg.cx, seg.cy); consider(seg.x, seg.y) }
                is Seg.Cubic -> { consider(seg.c1x, seg.c1y); consider(seg.c2x, seg.c2y); consider(seg.x, seg.y) }
            }
        }
        if (bestExit == -Float.MAX_VALUE) { exitX = maxX; exitY = minY }
        if (bestEntry == -Float.MAX_VALUE) { entryX = minX; entryY = minY }
        val a = Anchors(exitX, exitY, entryX, entryY)
        cache[gid] = a
        return a
    }

    private fun addConnector(
        out: MutableList<Connector>,
        prev: LetterRef,
        next: LetterRef,
        style: TextStyle,
        size: Float,
        rnd: DetRandom,
        scale: Float
    ) {
        if (!Character.isLetter(prev.cp) || !Character.isLetter(next.cp)) return
        // a hand lifts the pen after an upper-case letter (прописные пишутся отдельно)
        if (Character.isUpperCase(prev.cp) || Character.isUpperCase(next.cp)) return
        val pm = prev.placed.transform
        val nm = next.placed.transform
        val exitX = pm.applyX(prev.anchors.exitX, prev.anchors.exitY)
        val exitY = pm.applyY(prev.anchors.exitX, prev.anchors.exitY)
        val entryX = nm.applyX(next.anchors.entryX, next.anchors.entryY)
        val entryY = nm.applyY(next.anchors.entryX, next.anchors.entryY)
        // where the pen actually is when it leaves one letter and lands on the next one
        var x0 = exitX
        var x1 = entryX
        var y0 = exitY
        var y1 = entryY
        if (x1 - x0 < size * 0.02f) {
            // glyphs overlap (typical for tightly kerned cursive): join across the advance box
            x0 = pm.applyX(prev.placed.advance / scale * 0.90f, 0f)
            x1 = nm.applyX(next.placed.advance / scale * 0.10f, 0f)
            y0 = kotlin.math.min(y0, pm.applyY(0f, 0f))
            y1 = kotlin.math.min(y1, nm.applyY(0f, 0f))
        }
        val dx = x1 - x0
        if (dx < size * 0.005f) return            // nothing to bridge
        if (dx > size * 0.85f) return             // too far apart for a natural join
        if (abs(y1 - y0) > size * 0.35f) return   // different levels: the pen would cross the letter
        if (dx < size * 0.006f) return            // letters already touch: nothing to draw
        /*
         * A cursive join is not a straight line between two letters: the pen comes down to the
         * baseline, runs along it and rises into the next letter ("подхват"). That dip is what
         * makes written text readable as handwriting, so the control points are placed slightly
         * below the baseline of the line rather than on the chord between the anchors.
         */
        val baseline = prev.placed.baselineY + size * 0.02f
        val dip = max(max(y0, y1) + size * 0.04f, baseline)
        val sag = (rnd.next() - 0.5f) * size * 0.03f
        val c1x = x0 + dx * 0.30f
        val c1y = dip + sag
        val c2x = x0 + dx * 0.72f
        val c2y = dip + sag
        val base = size * 0.032f
        val w0 = base * (0.30f + 0.45f * prev.pressure)
        val w1 = base * (0.95f + 0.30f * ((prev.pressure + next.pressure) / 2f))
        val w2 = base * (0.30f + 0.45f * next.pressure)
        out.add(Connector(x0, y0, c1x, c1y, c2x, c2y, x1, y1, w0, w1, w2, style.inkDensity))
    }

    /* ------------------------------------------------------------------ */
    /* pressure                                                            */
    /* ------------------------------------------------------------------ */

    /** global pen pressure -> variable font weight axis */
    fun pressureWeight(style: TextStyle, source: GlyphSource): Float {
        if (!source.supportsWeightAxis) return source.weightDefault
        val t = style.pressure.coerceIn(0f, 1f)
        return source.weightMin + (source.weightMax - source.weightMin) * t
    }

    /** local pressure for one glyph: 0..1 */
    private fun localPressure(style: TextStyle, rnd: DetRandom, isLast: Boolean): Float {
        val base = style.pressure
        val v = style.pressureVariation
        var p = base + (rnd.next() - 0.5f) * 2f * 0.35f * v
        if (isLast) p += 0.05f * v
        return p.coerceIn(0f, 1f)
    }

    fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(abs(dx * dx + dy * dy))
    }
}

/** Deterministic, tiny RNG (xorshift) so the preview and the exported PDF are identical. */
class DetRandom(seed: Long) {
    private var s: Long = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun next(): Float {
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        return ((s ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
    }

    fun nextInt(bound: Int): Int = (next() * bound).toInt().coerceIn(0, max(0, bound - 1))
}
