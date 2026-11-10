import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.GlyphRecognizer
import com.handwrite.core.handwriting.HandwriteModel
import com.handwrite.core.handwriting.InkSegmenter
import com.handwrite.core.handwriting.TileEmbedder
import java.io.File

/**
 * Dev harness: how accurate is plain OCR on an ordinary (non-template) photo page?
 *
 *   java -cp /tmp/x:<kotlin-stdlib> OcrEvalKt /tmp/photos/page1.gray
 *
 * Segmentation is poor on flowing text (letters touch), so this measures the ceiling of the
 * "learn from your notes" path and tells us whether a guided sheet is required for good results.
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
    rec.setAllowedCharacters(GlyphRecognizer.CYRILLIC + GlyphRecognizer.DIGITS_PUNCT)
    val b = File(args[0]).readBytes()
    val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val w = bb.int
    val h = bb.int
    val gray = FloatArray(w * h) { (b[8 + it].toInt() and 0xFF) / 255f }
    val page = InkSegmenter.segment(gray, w, h)
    val truth = File(args[0].replace(".gray", ".txt")).readText().filter { !it.isWhitespace() }
    val guesses = page.crops.map { rec.classify(it, page) }
    val top1 = guesses.map { it.firstOrNull()?.codePoint ?: 0 }
    val top3 = guesses.map { g -> g.take(3).map { it.codePoint } }
    println("crops=${page.crops.size} truthChars=${truth.length}")
    println("top1 text: " + top1.joinToString("") { if (it == 0) "?" else String(Character.toChars(it)) })
    // segmentation unit test: is the *first* truth char among top-3 of the first crops?
    var aligned = 0
    var consumed = 0
    for ((i, cp) in top1.withIndex()) {
        if (consumed >= truth.length) break
        val want = truth[consumed].code
        if (cp == want || top3[i].contains(want)) aligned++
        consumed++
    }
    println("in-order agreement: $aligned/${minOf(page.crops.size, truth.length)}")
}
