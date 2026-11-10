package com.handwrite.core.handwriting

/**
 * Ligatures and the "wide crop is two letters" cut: both exist so that a font built from photos of
 * joined handwriting does not turn into a pile of two-letter blobs.
 */
class LigatureTest {

    // named wrappers so the offline runner and JUnit call the same bodies
    fun layout() = ligatureInLayout()
    fun rules() = pairRules()

    fun ligatureInLayout() {
        val atlas = GlyphAtlas("t")
        val tile = FloatArray(TileRasterizer.SIZE * TileRasterizer.SIZE) { 0.6f }
        val emb = FloatArray(4) { 0.25f }
        atlas.add(GlyphAtlas.Sample('н'.code, tile, emb, 0.9f, 1f, 0, 0f, 0f, 1f, 0f))
        atlas.add(GlyphAtlas.Sample('т'.code, tile, emb, 0.9f, 1f, 0, 0f, 0f, 1f, 0f))
        assertTrue(atlas.addLigature("нт", tile, emb, 0.8f, 1.3f, 0, 1f, 0f))
        val source = AtlasGlyphSource(atlas)
        val lig = source.ligatureGid('н'.code, 'т'.code)
        assertTrue(lig >= GlyphAtlas.LIGATURE_BASE, "a learned pair must get a ligature code")
        assertTrue(source.ligatureGid('н'.code, 'о'.code) == 0, "an unlearned pair stays two letters")
    }

    fun pairRules() {
        val atlas = GlyphAtlas("t")
        val tile = FloatArray(TileRasterizer.SIZE * TileRasterizer.SIZE) { 0.6f }
        // a pair of one-letter strings, or a pair containing a space, is not a join anybody wrote
        assertTrue(!atlas.addLigature("н", tile, FloatArray(4), 0.9f, 1f, 0, 1f, 0f))
        assertTrue(!atlas.addLigature(" т", tile, FloatArray(4), 0.9f, 1f, 0, 1f, 0f))
    }
}
