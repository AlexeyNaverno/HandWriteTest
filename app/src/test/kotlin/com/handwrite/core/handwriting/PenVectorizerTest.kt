package com.handwrite.core.handwriting

// assertions replaced by the offline runner: assertEquals
// assertTrue

/**
 * The pen-stroke pipeline is the part that turns photos into handwriting, so it is the part that must
 * not silently regress. These tests run on synthetic ink, so they need no fonts and no photos.
 */
class PenVectorizerTest {
    // The bodies are wrapped in named functions so that both JUnit (via these delegating tests) and
    // the offline runner can execute them.

    fun singleStroke() = strokeTest()
    fun widthProfile() = widthTest()
    fun crossing() = crossingTest()
    fun empty() = emptyTest()
    fun twoBlobs() = twoBlobsTest()


    private fun tile(size: Int = 64, block: (Int, Int) -> Boolean): FloatArray =
        FloatArray(size * size) { i -> if (block(i % size, i / size)) 1f else 0f }


    private fun strokeTest() {
        // a horizontal pen stroke in the middle of the tile
        val ink = tile { x, y -> y in 30..33 && x in 12..51 }
        val pen = PenVectorizer.vectorize(ink, 64)
        assertEquals(1, pen.strokes.size, "one stroke must stay one stroke")
        val start = pen.start!!
        val end = pen.end!!
        assertTrue(start[0] < end[0], "cursive writing enters on the left and leaves on the right")
        assertTrue(pen.coherence > 0.9f)
    }


    private fun widthTest() {
        // a stroke that is thin on the left and thick on the right
        val ink = tile { x, y ->
            val half = 1 + (x - 10) / 10
            x in 10..53 && y >= 32 - half && y <= 32 + half
        }
        val pen = PenVectorizer.vectorize(ink, 64)
        val st = pen.strokes.first()
        assertTrue(st.endWidth > st.startWidth * 1.5f, "the thick end must be thicker")
    }


    private fun crossingTest() {
        // a plus: a real letter like "т" or "ж" crosses its own stroke exactly like this.
        // A plus is genuinely two pen-downs, so the check is not "one stroke" but "no stroke stops at
        // the crossing": the horizontal arm must run through it in a single movement.
        val ink = tile { x, y ->
            (y in 30..33 && x in 10..53) || (x in 30..33 && y in 10..53)
        }
        val pen = PenVectorizer.vectorize(ink, 64)
        assertTrue(pen.strokes.isNotEmpty())
        val through = pen.strokes.any { it.start[0] < 0.25f && it.end[0] > 0.75f }
        assertTrue(through, "the horizontal arm must be one movement, not two halves")
    }

    private fun emptyTest() {
        assertTrue(PenVectorizer.vectorize(FloatArray(64 * 64), 64).isEmpty)
    }


    private fun twoBlobsTest() {
        // two thick short strokes far apart: the pen was clearly lifted between them
        val ink = tile { x, y ->
            (y in 28..36 && x in 6..24) || (y in 28..36 && x in 40..58)
        }
        val pen = PenVectorizer.vectorize(ink, 64)
        assertEquals(2, pen.strokes.size, "two separated strokes must not become one")
    }
}
