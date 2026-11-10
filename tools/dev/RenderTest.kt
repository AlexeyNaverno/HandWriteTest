import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.AlphabetAligner
import com.handwrite.core.handwriting.AtlasGlyphSource
import com.handwrite.core.handwriting.GlyphAtlas
import com.handwrite.core.handwriting.GlyphRecognizer
import com.handwrite.core.handwriting.HandwriteModel
import com.handwrite.core.handwriting.InkSegmenter
import com.handwrite.core.handwriting.TileEmbedder
import com.handwrite.core.model.TextStyle
import com.handwrite.core.model.WritingMode
import com.handwrite.core.pdf.PdfPageComposer
import java.io.File

/**
 * Dev harness: turns a template-sheet photo into an atlas and renders two A5 PDFs.
 *
 *   java -cp /tmp/rt:<kotlin-stdlib> RenderTestKt /tmp/photos/template2.gray [outDir]
 */
fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(1) { "build/dev" })
    outDir.mkdirs()
    val model = HandwriteModel.load(File("app/src/main/assets/models/glyph_embed.bin").readBytes())
    val em = object : TileEmbedder {
        override val inputSize: Int = model.input
        override fun embed(tile: FloatArray): FloatArray = model.embed(tile)
    }
    val rec = GlyphRecognizer(em)
    for (a in listOf("Caveat-Regular.ttf", "MarckScript-Regular.ttf", "Neucha-Regular.ttf", "BadScript-Regular.ttf")) {
        rec.buildTemplates(FileGlyphSource(FontData(File("app/src/main/assets/fonts/$a").readBytes())), GlyphRecognizer.DEFAULT_CHARSET)
    }
    rec.setAllowedCharacters(GlyphRecognizer.DEFAULT_CHARSET)
    val b = File(args[0]).readBytes()
    val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val w = bb.int
    val h = bb.int
    val gray = FloatArray(w * h) { (b[8 + it].toInt() and 0xFF) / 255f }
    val page = InkSegmenter.segment(gray, w, h, template = true)
    val guesses = page.crops.map { rec.classify(it, page) }
    val index = HashMap<InkSegmenter.Crop, Int>()
    page.crops.forEachIndexed { i, c -> index[c] = i }
    val rows = page.rows.map { row -> row.map { c -> guesses.getOrElse(index[c] ?: -1) { emptyList() } } }
    val det = AlphabetAligner.detect(rows, page.rows.map { it.size })
    println("crops=${page.crops.size} sheet=${det?.sheet?.name} matched=${det?.matched}/${det?.expected}")
    val atlas = GlyphAtlas("Мой почерк")
    for ((i, c) in page.rows.flatten().withIndex()) {
        val cp = det?.labels?.getOrElse(i) { 0 } ?: 0
        if (cp == 0) continue
        atlas.add(GlyphAtlas.Sample(cp, c.tile, em.embed(c.tile), 1f, c.aspect, 0, c.x.toFloat(), c.y.toFloat(), c.sizeRatio, c.centerRatio))
    }
    atlas.deriveCapitals()
    println("atlas: ${atlas.letterCount()} letters, ${atlas.sampleCount()} samples")
    val src = AtlasGlyphSource(atlas, FileGlyphSource(FontData(File("app/src/main/assets/fonts/Caveat-Regular.ttf").readBytes())))
    val ru = TextStyle.defaultsFor(WritingMode.RUSSIAN_CURSIVE).copy(sizePt = 15f)
    File(outDir, "ru.pdf").writeBytes(PdfPageComposer.compose(
        "Съешь же ещё этих мягких французских булок, да выпей чаю.\n\n" +
            "Мы часто откладываем важное на потом, а потом жалеем.\n\n" +
            "Дорогой друг, привет! Как твои дела? Я учусь писать красиво и хочу, чтобы текст выглядел как настоящий конспект.",
        ru, src, 20))
    val en = TextStyle.defaultsFor(WritingMode.ENGLISH_PRINT).copy(sizePt = 15f)
    File(outDir, "en.pdf").writeBytes(PdfPageComposer.compose(
        "The quick brown fox jumps over the lazy dog.\n\n" +
            "Handwriting can be learned from a few photos, and the app keeps everything on the phone.",
        en, src, 20))
}
