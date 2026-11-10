import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.GlyphAtlas
import com.handwrite.core.handwriting.GlyphRecognizer
import com.handwrite.core.handwriting.HandwriteModel
import com.handwrite.core.handwriting.InkMask
import com.handwrite.core.handwriting.InkSegmenter
import com.handwrite.core.handwriting.LetterSplitter
import com.handwrite.core.handwriting.PenVectorizer
import com.handwrite.core.handwriting.TileEmbedder
import com.handwrite.core.handwriting.TileRasterizer
import com.handwrite.core.model.TextStyle
import com.handwrite.core.model.WritingMode
import com.handwrite.core.pdf.PdfPageComposer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Dev harness for the pen-stroke pipeline on real photos.
 *
 *   java -jar pentest.jar <photo.png>...
 *
 * 1. segment the page (colour ink mask), learn an atlas with the same word-level script rule the app
 *    uses, then render a phrase with the learned letters *as pen strokes* (PDF + PNG);
 * 2. draw a contact sheet of the reconstructed strokes of the first crops, so the vectoriser can be
 *    judged by eye: one ribbon per pen movement, width taken from the distance transform.
 */
fun main(args: Array<String>) {
    val out = File("build/pen")
    out.mkdirs()
    val model = HandwriteModel.load(File("app/src/main/assets/models/glyph_embed.bin").readBytes())
    val em = object : TileEmbedder {
        override val inputSize: Int = model.input
        override fun embed(tile: FloatArray): FloatArray = model.embed(tile)
    }
    val rec = GlyphRecognizer(em)
    for (a in listOf("Caveat-Regular.ttf", "MarckScript-Regular.ttf", "Neucha-Regular.ttf",
        "BadScript-Regular.ttf", "PatrickHand-Regular.ttf")) {
        rec.buildTemplates(
            FileGlyphSource(FontData(File("app/src/main/assets/fonts/$a").readBytes())),
            GlyphRecognizer.DEFAULT_CHARSET
        )
    }
    val atlas = GlyphAtlas("Мой почерк")
    var shown = 0
    var splitCount = 0
    var ligCount = 0
    val sheet = BufferedImage(12 * 110, 6 * 130, BufferedImage.TYPE_INT_RGB)
    val g = sheet.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color.WHITE
    g.fillRect(0, 0, sheet.width, sheet.height)

    for ((pi, path) in args.withIndex()) {
        val img = ImageIO.read(File(path)) ?: continue
        val w = img.width
        val h = img.height
        val rgb = IntArray(w * h)
        img.getRGB(0, 0, w, h, rgb, 0, w)
        val cr = FloatArray(w * h); val cg = FloatArray(w * h); val cb = FloatArray(w * h)
        val gray = FloatArray(w * h)
        for (i in rgb.indices) {
            val p = rgb[i]
            cr[i] = ((p shr 16) and 0xFF) / 255f
            cg[i] = ((p shr 8) and 0xFF) / 255f
            cb[i] = (p and 0xFF) / 255f
            gray[i] = 0.299f * cr[i] + 0.587f * cg[i] + 0.114f * cb[i]
        }
        val ink = InkMask.compute(cr, cg, cb, w, h)
        val page = InkSegmenter.segment(gray, w, h, preMask = ink)
        println("page ${pi + 1}: ${page.crops.size} crops")
        for (crop in page.crops) {
            val guesses = rec.classify(crop, page, bothScripts = true)
            var best = guesses.firstOrNull() ?: continue
            if (best.score < 0.45f) continue
            // wide crops are often two joined letters: cut them exactly like the app does
            if (crop.w > page.lineHeight * LetterSplitter.MIN_ASPECT) {
                val split = LetterSplitter.split(crop, page, page.ink, page.width, page.height) { tile ->
                    val gg = rec.classifyTile(tile).firstOrNull()
                    if (gg == null) 0 to 0f else gg.codePoint to gg.score
                }
                if (split != null) {
                    val la = rec.classifyTile(split.first.tile).firstOrNull()
                    val lb = rec.classifyTile(split.second.tile).firstOrNull()
                    if (la != null && lb != null) {
                        for ((half, lab) in listOf(split.first to la, split.second to lb)) {
                            atlas.add(GlyphAtlas.Sample(lab.codePoint, half.tile, em.embed(half.tile), lab.score, half.aspect, pi, half.x.toFloat(), half.y.toFloat(), crop.sizeRatio, crop.centerRatio))
                        }
                        splitCount++
                        // the pair the writer joined becomes a ligature glyph
                        val x0 = minOf(split.first.x, split.second.x)
                        val x1 = maxOf(split.first.x + split.first.w, split.second.x + split.second.w)
                        val y0 = minOf(split.first.y, split.second.y)
                        val y1 = maxOf(split.first.y + split.first.h, split.second.y + split.second.h)
                        val tile = InkSegmenter.cropTile(page.ink, page.width, page.height, InkSegmenter.Box(x0, y0, x1, y1))
                        val pair = String(Character.toChars(la.codePoint)) + String(Character.toChars(lb.codePoint))
                        if (atlas.addLigature(pair, tile, em.embed(tile), minOf(la.score, lb.score), (x1 - x0).toFloat() / kotlin.math.max(1f, (y1 - y0).toFloat()), pi, crop.sizeRatio, crop.centerRatio)) ligCount++
                        continue
                    }
                }
            }
            val sample = GlyphAtlas.Sample(
                codePoint = best.codePoint, tile = crop.tile,
                embedding = em.embed(crop.tile), confidence = best.score,
                aspect = crop.aspect, sourcePage = pi,
                pageX = crop.x.toFloat(), pageY = crop.y.toFloat(),
                sizeRatio = crop.sizeRatio, centerRatio = crop.centerRatio
            )
            atlas.add(sample)
            // draw the reconstructed strokes of some crops
            if (shown < 72) {
                val pen = PenVectorizer.of(crop.tile)
                val cx = (shown % 12) * 110
                val cy = (shown / 12) * 130
                g.color = Color(232, 232, 232)
                g.fillRect(cx, cy, 104, 104)
                g.stroke = BasicStroke(1f)
                g.color = Color(31, 119, 180)
                for (st in pen.strokes) {
                    val pts = st.points
                    for (i in 1 until pts.size) {
                        val x0 = cx + (pts[i - 1][0] * 104).toInt()
                        val y0 = cy + (pts[i - 1][1] * 104).toInt()
                        val x1 = cx + (pts[i][0] * 104).toInt()
                        val y1 = cy + (pts[i][1] * 104).toInt()
                        val wdt = (st.widths[i] * 104).coerceIn(0.6f, 8f)
                        g.stroke = BasicStroke(wdt, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        g.drawLine(x0, y0, x1, y1)
                    }
                    g.color = Color.GREEN
                    g.fillOval(cx + (st.start[0] * 104).toInt() - 3, cy + (st.start[1] * 104).toInt() - 3, 6, 6)
                    g.color = Color.RED
                    g.fillOval(cx + (st.end[0] * 104).toInt() - 3, cy + (st.end[1] * 104).toInt() - 3, 6, 6)
                    g.color = Color(31, 119, 180)
                }
                g.color = Color.BLACK
                g.font = Font("SansSerif", Font.PLAIN, 12)
                g.drawString(String(Character.toChars(best.codePoint)) + " ${"%.2f".format(best.score)}", cx + 3, cy + 116)
                shown++
            }
        }
    }
    g.dispose()
    ImageIO.write(sheet, "png", File(out, "strokes.png"))
    println("atlas: ${atlas.letterCount()} letters, ${atlas.sampleCount()} samples")
    println("split: $splitCount wide crops cut into two letters; ligatures: " + atlas.ligaturePairs().joinToString { p -> p.map { c -> "U+%04X".format(c.code) }.joinToString("+") })
    println("strokes.png written")

    atlas.deriveCapitals()
    val learned = com.handwrite.core.handwriting.AtlasGlyphSource(
        atlas,
        FileGlyphSource(FontData(File("app/src/main/assets/fonts/Caveat-Regular.ttf").readBytes()))
    )
    val style = TextStyle.defaultsFor(WritingMode.RUSSIAN_CURSIVE).copy(sizePt = 20f, seed = 5)
    val demo = "Соединение букв и лигатуры: моё письмо"
    File(out, "pen.pdf").writeBytes(PdfPageComposer.compose(demo, style, learned, 1))
    println("wrote build/pen/pen.pdf")
}
