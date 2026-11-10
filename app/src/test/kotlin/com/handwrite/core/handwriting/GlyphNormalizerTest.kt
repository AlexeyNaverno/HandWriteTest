package com.handwrite.core.handwriting

// assertions replaced by the offline runner: assertEquals
// assertTrue

class GlyphNormalizerTest {
    fun smallLetters() = smallLettersTest()
    fun ascender() = ascenderTest()
    fun descender() = descenderTest()
    fun clamped() = clampedTest()



    private fun smallLettersTest() {
        // whatever the page measured, a plain "о" ends up 1.0 tall and sits on the baseline
        for (measured in listOf(0.7f, 0.9f, 1.1f, 1.19f)) {
            val m = GlyphNormalizer.metrics(measured, 0f, 'о'.code)
            assertEquals(1f, m.height)
            assertEquals(0f, m.bottom)
        }
    }


    private fun ascenderTest() {
        val m = GlyphNormalizer.metrics(1.7f, -0.4f, 'б'.code)
        assertTrue(m.height > 1.15f, "б must be taller than the x-height")
        assertEquals(0f, m.bottom, "an ascender starts at the baseline")
    }


    private fun descenderTest() {
        val m = GlyphNormalizer.metrics(1.4f, 0.5f, 'д'.code)
        assertTrue(m.bottom < 0f, "д has a tail below the line")
        assertTrue(m.top > 0.5f)
    }


    private fun clampedTest() {
        val tiny = GlyphNormalizer.metrics(0.05f, -5f, 'ш'.code)
        val huge = GlyphNormalizer.metrics(9f, 9f, 'ш'.code)
        assertTrue(tiny.height in 0.8f..2.0f)
        assertTrue(huge.height in 0.8f..2.0f)
    }
}
