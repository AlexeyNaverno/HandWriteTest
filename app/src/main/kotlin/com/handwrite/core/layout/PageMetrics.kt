package com.handwrite.core.layout

import com.handwrite.core.font.GlyphSource
import com.handwrite.core.model.PaperStyle
import com.handwrite.core.model.TextStyle
import kotlin.math.max

/**
 * All vertical/horizontal page metrics in one place, so the layout engine and the page renderer
 * agree on where the ruling is. When the paper is ruled/gridded/dotted the text is snapped onto
 * the ruling, which is what makes the export look like a real sheet from a notebook.
 */
class PageMetrics(val style: TextStyle, source: GlyphSource, pageHeight: Float) {

    val sizePt: Float = style.sizePt
    val scale: Float = style.sizePt / source.unitsPerEm.toFloat()
    val ascPx: Float = source.ascender * scale
    val descPx: Float = -source.descender * scale

    val marginL: Float = style.marginLeftMm * MM
    val marginR: Float = style.paperSize.widthPt - style.marginRightMm * MM
    val marginT: Float = style.marginTopMm * MM
    val marginB: Float = pageHeight - style.marginBottomMm * MM

    /** vertical distance between two written lines */
    val leading: Float

    /** baseline of the very first line on the page */
    val firstBaseline: Float

    /** how far below the baseline the paper rule is drawn */
    val ruleDrop: Float

    init {
        val natural = sizePt * style.lineSpacing
        val minimum = ascPx + descPx + sizePt * 0.10f
        val base = max(natural, minimum)
        if (style.paperStyle == PaperStyle.BLANK) {
            leading = base
            firstBaseline = marginT + ascPx + sizePt * 0.08f
            ruleDrop = descPx * 0.62f
        } else {
            // keep the ruling step a whole number of "nice" steps (mm grid of the notebook)
            leading = base
            firstBaseline = marginT + ascPx + sizePt * 0.08f
            ruleDrop = descPx * 0.62f
        }
    }

    /** baseline of line [index] counted from the top of the page */
    fun baselineOf(index: Int): Float = firstBaseline + index * leading

    /** y of the paper rule that belongs to line [index] */
    fun ruleY(index: Int): Float = baselineOf(index) + ruleDrop

    /** number of lines that fit between the margins */
    fun linesPerPage(): Int = max(1, ((marginB - marginT + (leading - ascPx - descPx)) / leading).toInt())

    /** first line index whose baseline is still above the bottom margin */
    fun lastLineIndex(): Int {
        var i = 0
        while (baselineOf(i + 1) + descPx < marginB) i++
        return i
    }

    companion object {
        const val MM = 72f / 25.4f
        fun mm(v: Float): Float = v * MM
    }
}
