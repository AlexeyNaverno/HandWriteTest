import com.handwrite.core.handwriting.InkSegmenter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** Dev harness: dumps the crops of a .gray page (x, y, w, h, aspect + 64x64 tile). */
fun main(args: Array<String>) {
    val b = File(args[0]).readBytes()
    val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val w = bb.int
    val h = bb.int
    val gray = FloatArray(w * h) { (b[8 + it].toInt() and 0xFF) / 255f }
    val page = InkSegmenter.segment(gray, w, h, template = args.getOrNull(2) == "template")
    val bos = ByteArrayOutputStream()
    val out = DataOutputStream(bos)
    out.writeInt(page.crops.size)
    for (c in page.crops) {
        out.writeInt(c.x); out.writeInt(c.y); out.writeInt(c.w); out.writeInt(c.h)
        out.writeFloat(c.aspect)
        for (v in c.tile) out.writeFloat(v)
    }
    out.flush()
    File(args[1]).writeBytes(bos.toByteArray())
    println("crops=${page.crops.size}")
}
