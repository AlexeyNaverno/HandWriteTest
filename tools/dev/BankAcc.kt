import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.GlyphRecognizer
import com.handwrite.core.handwriting.HandwriteModel
import com.handwrite.core.handwriting.InkSegmenter
import com.handwrite.core.handwriting.TileEmbedder
import java.io.File

/**
 * Dev harness: accuracy of the template bank on clean crops, before and after the script filter.
 *
 *   java -cp /tmp/x:<kotlin-stdlib> BankAccKt /tmp/photos/template2.gray
 */
fun main(args: Array<String>) {
    val model = HandwriteModel.load(File("app/src/main/assets/models/glyph_embed.bin").readBytes())
    val em = object : TileEmbedder {
        override val inputSize: Int = model.input
        override fun embed(tile: FloatArray): FloatArray = model.embed(tile)
    }
    val rec = GlyphRecognizer(em)
    for (a in listOf("Caveat-Regular.ttf", "MarckScript-Regular.ttf", "Neucha-Regular.ttf", "BadScript-Regular.ttf", "PatrickHand-Regular.ttf")) {
        rec.buildTemplates(FileGlyphSource(FontData(File("app/src/main/assets/fonts/$a").readBytes())), GlyphRecognizer.DEFAULT_CHARSET)
    }
    val b = File(args[0]).readBytes()
    val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val w = bb.int
    val h = bb.int
    val gray = FloatArray(w * h) { (b[8 + it].toInt() and 0xFF) / 255f }
    val page = InkSegmenter.segment(gray, w, h, template = true)
    val truth = File(args[0].replace(".gray", ".cp")).readText().split(",").filter { it.isNotBlank() }.map { it.trim().toInt() }
    val crops = page.rows.flatten()
    fun run(label: String, charset: String?) {
        rec.setAllowedCharacters(charset)
        var top1 = 0
        var top3 = 0
        var n = 0
        for ((i, c) in crops.withIndex()) {
            if (i >= truth.size) break
            val g = rec.classify(c, page)
            n++
            if (g.firstOrNull()?.codePoint == truth[i]) top1++
            if (g.take(3).any { it.codePoint == truth[i] }) top3++
        }
        println("%-28s top1=%d/%d top3=%d/%d".format(label, top1, n, top3, n))
    }
    run("all scripts (mixed)", null)
    run("russian only", GlyphRecognizer.CYRILLIC + GlyphRecognizer.DIGITS_PUNCT)
    run("mixed ru+en", GlyphRecognizer.CYRILLIC + GlyphRecognizer.LATIN + GlyphRecognizer.DIGITS_PUNCT)
}
