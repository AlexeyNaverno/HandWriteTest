package com.handwrite.core.model

/** A5 geometry helpers (1 mm = 72/25.4 pt). */
object PageSize {
    const val MM = 72f / 25.4f
    val A5_W = 148f * MM   // 419.53 pt
    val A5_H = 210f * MM   // 595.28 pt
    val A4_W = 210f * MM
    val A4_H = 297f * MM
    val A6_W = 105f * MM
    val A6_H = 148f * MM
}

enum class PaperSize(val key: String, val label: String, val widthPt: Float, val heightPt: Float) {
    A5("a5", "A5", PageSize.A5_W, PageSize.A5_H),
    A4("a4", "A4", PageSize.A4_W, PageSize.A4_H),
    A6("a6", "A6", PageSize.A6_W, PageSize.A6_H);

    companion object {
        fun from(key: String?): PaperSize = entries.firstOrNull { it.key == key } ?: A5
    }
}

enum class PaperStyle(val key: String, val label: String) {
    BLANK("blank", "Чистый лист"),
    RULED("ruled", "В линейку"),
    GRID("grid", "В клетку"),
    DOTTED("dotted", "В точку");

    companion object {
        fun from(key: String?): PaperStyle = entries.firstOrNull { it.key == key } ?: RULED
    }
}

/** Which glyph forms are used for the two languages of the task. */
enum class WritingMode(val key: String, val label: String) {
    /** Русский прописной: соединённое курсивное письмо (лигатуры + синтетические соединители). */
    RUSSIAN_CURSIVE("ru_cursive", "Русский прописной (с соединениями)"),
    /** Английский печатный: буквы не соединяются. */
    ENGLISH_PRINT("en_print", "English print (без соединений)"),
    /** Смешанный: кириллица соединяется, латиница печатается. */
    MIXED("mixed", "Смешанный (RU курсив / EN печать)");

    companion object {
        fun from(key: String?): WritingMode = entries.firstOrNull { it.key == key } ?: RUSSIAN_CURSIVE
    }
}

/**
 * All knobs that shape the handwriting. Everything is deterministic for a given [seed],
 * so the on-screen preview and the exported PDF are identical.
 */
data class TextStyle(
    val mode: WritingMode = WritingMode.RUSSIAN_CURSIVE,
    /** asset font file name, e.g. "Caveat-Regular.ttf" */
    val fontAsset: String = "Caveat-Regular.ttf",
    /** font size in points */
    val sizePt: Float = 15f,
    /** line height multiplier */
    val lineSpacing: Float = 1.55f,
    /** extra space between letters, in em (can be negative to tighten cursive) */
    val letterSpacingEm: Float = 0.004f,
    /** extra space between words, in em */
    val wordSpacingEm: Float = 0.30f,
    /** red line: indent of the first line of a paragraph, in em (0 = off) */
    val paragraphIndentEm: Float = 1.6f,
    /** pen slant, degrees */
    val slantDeg: Float = 0f,
    /** pen pressure 0..1 mapped to the variable font weight axis (нажим) */
    val pressure: Float = 0.55f,
    /** thickness variation along a word, 0 = even, 1 = strong (нажим «гуляет») */
    val pressureVariation: Float = 0.45f,
    /** random vertical/horizontal offsets, relative to the font size */
    val jitterEm: Float = 0.006f,
    /** random rotation per glyph, degrees */
    val jitterRotDeg: Float = 0.35f,
    /** slow baseline wave (рука «плывёт») in em */
    val baselineWaveEm: Float = 0.014f,
    /** join letters with connective strokes/ligatures */
    val connectLetters: Boolean = true,
    /** use the font's OpenType liga/calt tables */
    val useFontLigatures: Boolean = true,
    /** use the font's OpenType contextual alternates (варианты букв) */
    val useContextualAlternates: Boolean = true,
    /** ink colour, ARGB */
    val inkColor: Int = 0xFF1B2A4A.toInt(),
    /** ink darkness 0.3..1: lower values look like a drier pen (степени прозрачности) */
    val inkDensity: Float = 0.95f,
    /** paper colour, ARGB */
    val paperColor: Int = 0xFFFFFDF7.toInt(),
    val paperStyle: PaperStyle = PaperStyle.RULED,
    val paperSize: PaperSize = PaperSize.A5,
    /** page margins in mm */
    val marginLeftMm: Float = 16f,
    val marginRightMm: Float = 14f,
    val marginTopMm: Float = 18f,
    val marginBottomMm: Float = 16f,
    /** stretch spaces so both edges of the paragraph are even */
    val justify: Boolean = false,
    /** snap baselines to the ruling of the paper */
    val snapToRuling: Boolean = false,
    val seed: Long = 20261110L
) {
    val inkRgb: Int get() = inkColor and 0xFFFFFF
    val paperRgb: Int get() = paperColor and 0xFFFFFF

    companion object {
        fun defaultsFor(mode: WritingMode): TextStyle = when (mode) {
            WritingMode.RUSSIAN_CURSIVE -> TextStyle(
                mode = mode, fontAsset = "Caveat-Regular.ttf", sizePt = 15f,
                letterSpacingEm = 0.004f, slantDeg = 4f, connectLetters = true,
                paperStyle = PaperStyle.RULED, lineSpacing = 1.62f
            )
            WritingMode.ENGLISH_PRINT -> TextStyle(
                mode = mode, fontAsset = "PatrickHand-Regular.ttf", sizePt = 13f,
                letterSpacingEm = 0.01f, slantDeg = 0f, connectLetters = false, paperStyle = PaperStyle.RULED
            )
            WritingMode.MIXED -> TextStyle(
                mode = mode, fontAsset = "Caveat-Regular.ttf", sizePt = 15f,
                letterSpacingEm = 0.004f, slantDeg = 3f, connectLetters = true, paperStyle = PaperStyle.RULED
            )
        }
    }
}

data class Ink(val argb: Int)
