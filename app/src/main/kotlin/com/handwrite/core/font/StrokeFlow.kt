package com.handwrite.core.font

/**
 * Pen entry/exit of a glyph in font units (y up, baseline at 0).
 *
 * [entry] is where the pen arrives when the previous letter joins this one, [exit] is where it
 * leaves towards the next letter. The vectors (`dx`, `dy`) are unit directions of the pen movement
 * *at* those points, so a joining stroke can leave/arrive along the real stroke direction.
 */
class StrokeFlow(
    val entry: Terminal?,
    val exit: Terminal?,
    /** ink bounds of the glyph in font units, used to sanity-check a join distance */
    val inkMinX: Float = 0f,
    val inkMaxX: Float = 0f
) {
    class Terminal(val x: Float, val y: Float, val dx: Float, val dy: Float)

    val hasExit: Boolean get() = exit != null
    val hasEntry: Boolean get() = entry != null
}
