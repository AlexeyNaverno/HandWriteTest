package com.handwrite.core.font

import com.handwrite.core.geom.GlyphPath

/**
 * Everything the layout engine needs from a font. Two implementations exist:
 *  - [FileGlyphSource]  – a bundled handwriting font (variable or static),
 *  - [AtlasGlyphSource] – a font learned from the user's photos (see `GlyphAtlas`).
 */
interface GlyphSource {
    val name: String
    val unitsPerEm: Int
    val ascender: Int
    val descender: Int

    fun gid(cp: Int): Int
    fun hasGlyph(cp: Int): Boolean
    fun advance(gid: Int): Int

    /** outline in font units, y up */
    fun path(gid: Int, coords: Map<String, Float>): GlyphPath

    /**
     * Outline of a glyph for one specific place in the text.
     *
     * Handwriting never repeats a letter exactly: the same "о" is written slightly differently every
     * time. A source that stores several variants (the learned atlas) uses [variantSeed] to cycle
     * through them, so a page stops looking stamped. Font sources ignore the seed and just draw the
     * glyph. The seed must be derived from the glyph's position in the text, not from the glyph id,
     * otherwise every copy of a letter picks the same variant.
     */
    fun pathAt(gid: Int, coords: Map<String, Float>, variantSeed: Int): GlyphPath = path(gid, coords)

    /**
     * Glyph id of a two-letter ligature the user wrote, or 0 when this source has none.
     * The layout substitutes such a pair for a single glyph, the way a real font's `liga` feature does.
     */
    fun ligatureGid(first: Int, second: Int): Int = 0

    /** GPOS pair kerning in font units (negative = tighten) */
    fun kerning(leftGid: Int, rightGid: Int): Int = 0

    /** applies liga/calt-like substitutions */
    fun applyFeatures(gids: IntArray, tags: Set<String>): IntArray

    /** true when the "pressure" knob can be expressed through a real weight axis */
    val supportsWeightAxis: Boolean
    val weightMin: Float
    val weightDefault: Float
    val weightMax: Float

    /**
     * True for glyphs traced from bitmaps (learned handwriting): their contours come from a
     * rasteriser and are filled with the even-odd rule, which is orientation independent.
     */
    val usesEvenOddFill: Boolean get() = false

    /**
     * Where the pen enters and leaves a glyph, in font units (y up, baseline at 0), or null when
     * the source cannot tell. Used to continue the real stroke between letters instead of drawing
     * a generic tick - see [com.handwrite.core.handwriting.StrokeFlow].
     */
    fun strokeFlow(gid: Int): StrokeFlow? = null

    /** Can the glyph be used as a connector start/end (a letter, not punctuation)? */
    fun isLetterGlyph(cp: Int, gid: Int): Boolean
}

/** Adapter for a bundled TTF. */
class FileGlyphSource(val font: FontData, override val name: String = "bundled") : GlyphSource {

    private val raster = GlyphRasterizer(font)

    override val unitsPerEm: Int get() = font.unitsPerEm
    override val ascender: Int get() = font.ascender
    override val descender: Int get() = font.descender

    override fun gid(cp: Int): Int = font.glyphId(cp)
    override fun hasGlyph(cp: Int): Boolean = font.hasGlyph(cp)
    override fun advance(gid: Int): Int = font.advance(gid)
    override fun path(gid: Int, coords: Map<String, Float>): GlyphPath = raster.glyphPath(gid, coords)
    override fun applyFeatures(gids: IntArray, tags: Set<String>): IntArray = font.applyFeatures(gids, tags)
    override fun kerning(leftGid: Int, rightGid: Int): Int = font.kerning(leftGid, rightGid)

    private val wght = font.axes.firstOrNull { it.tag == "wght" }
    override val supportsWeightAxis: Boolean get() = wght != null
    override val weightMin: Float get() = wght?.min ?: 400f
    override val weightDefault: Float get() = wght?.default ?: 400f
    override val weightMax: Float get() = wght?.max ?: 400f

    override fun isLetterGlyph(cp: Int, gid: Int): Boolean = Character.isLetter(cp)
}
