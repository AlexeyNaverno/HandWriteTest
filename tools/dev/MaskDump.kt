import com.handwrite.core.handwriting.InkSegmenter
import java.io.File

/** Dev harness: writes the intermediate ink mask of the segmentation pipeline as a .pgm image. */
fun main(args: Array<String>) {
    val b = File(args[0]).readBytes()
    val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val w = bb.int
    val h = bb.int
    val gray = FloatArray(w * h) { (b[8 + it].toInt() and 0xFF) / 255f }
    val page = InkSegmenter.segment(gray, w, h)
    // raw threshold of the normalised background
    val out = ByteArray(w * h)
    for (i in out.indices) out[i] = (page.ink[i].coerceIn(0f, 1f) * 255).toInt().toByte()
    val f = File(args[1])
    f.outputStream().use { o ->
        o.write("P5\n$w $h\n255\n".toByteArray())
        o.write(out)
    }
    println("threshold=${page.threshold} lineHeight=${page.lineHeight} crops=${page.crops.size}")
}
