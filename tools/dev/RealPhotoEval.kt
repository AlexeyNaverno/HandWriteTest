import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.GlyphAtlas
import com.handwrite.core.handwriting.GlyphRecognizer
import com.handwrite.core.handwriting.HandwriteModel
import com.handwrite.core.handwriting.InkSegmenter
import com.handwrite.core.handwriting.TileEmbedder
import com.handwrite.core.model.TextStyle
import com.handwrite.core.model.WritingMode
import com.handwrite.core.pdf.PdfPageComposer
import java.io.File
import javax.imageio.ImageIO

/**
 * Dev harness: the whole app path on real photos.
 *
 *   java -jar realphoto.jar <photo.jpg>... [outDir]
 *
 * For every photo: grayscale -> segment -> classify (both scripts) -> per-word script decision
 * -> atlas -> render the same text with the learned handwriting -> PDF.
 * Prints the text the recogniser "read", so the output can be compared with the page by eye or
 * pushed through an OCR (see tools/dev/ocr_check.py).
 */
fun main(args: Array<String>) {
    val photos = args.filter { it.endsWith(".jpg", true) || it.endsWith(".png", true) }
    val outDir = File(args.lastOrNull()?.takeIf { !it.endsWith(".jpg", true) && !it.endsWith(".png", true) } ?: "build/realphoto")
    outDir.mkdirs()

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
    println("templates=${rec.templateCount()}")

    val atlas = GlyphAtlas("Мой почерк")
    val allText = StringBuilder()

    for ((pi, path) in photos.withIndex()) {
        val img = ImageIO.read(File(path)) ?: continue
        // the app scales the photo down first (PhotoLoader); mirror that
        val scale = minOf(1f, 1900f / maxOf(img.width, img.height))
        val w = (img.width * scale).toInt()
        val h = (img.height * scale).toInt()
        val gray = FloatArray(w * h)
        val cr = FloatArray(w * h)
        val cg2 = FloatArray(w * h)
        val cb2 = FloatArray(w * h)
        val rgb = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, rgb, 0, img.width)
        for (y in 0 until h) {
            val sy = (y / scale).toInt().coerceIn(0, img.height - 1)
            for (x in 0 until w) {
                val sx = (x / scale).toInt().coerceIn(0, img.width - 1)
                val c = rgb[sy * img.width + sx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[y * w + x] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                cr[y * w + x] = r / 255f
                cg2[y * w + x] = g / 255f
                cb2[y * w + x] = b / 255f
            }
        }

        val ink = com.handwrite.core.handwriting.InkMask.compute(cr, cg2, cb2, w, h)
        // CROP="x0,y0,x1,y1" (fractions of the page) limits recognition to one line, for debugging
        System.getenv("CROP")?.split(",")?.map { it.toFloat() }?.let { r ->
            if (r.size == 4) {
                for (y in 0 until h) for (x in 0 until w) {
                    if (x < r[0] * w || x > r[2] * w || y < r[1] * h || y > r[3] * h) ink[y * w + x] = 0f
                }
            }
        }
        val page = InkSegmenter.segment(gray, w, h, preMask = ink)
        println("\n=== page ${pi + 1}: ${File(path).name} ${w}x$h ink=${page.crops.size} crops, " +
            "lines=${page.rows.size}, lineHeight=${page.lineHeight}")

        dumpCrops(page, 96, File(outDir, "crops${pi + 1}.png"))
        run {
            val ov = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) for (x in 0 until w) {
                val v = (ink[y * w + x] * 200).toInt().coerceIn(0, 200)
                ov.setRGB(x, y, (v shl 16) or (v shl 8) or v)
            }
            val gg = ov.createGraphics()
            gg.color = java.awt.Color.RED
            for (c in page.crops) gg.drawRect(c.x, c.y, c.w, c.h)
            gg.dispose()
            ImageIO.write(ov, "png", File(outDir, "overlay${pi + 1}.png"))
        }
        run {
            val mi = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) for (x in 0 until w) {
                val v = (ink[y * w + x] * 255).toInt().coerceIn(0, 255)
                mi.setRGB(x, y, (v shl 16) or (v shl 8) or v)
            }
            ImageIO.write(mi, "png", File(outDir, "mask${pi + 1}.png"))
        }
        val guesses = page.crops.map { rec.classify(it, page, bothScripts = true) }
        val resolved = resolveScripts(guesses, page)

        // second pass: case by letter size + vote among crops that look the same
        val items = page.crops.indices.map { i ->
            val best = resolved[i].firstOrNull()
            com.handwrite.core.handwriting.LetterConsensus.Item(
                codePoint = best?.codePoint ?: 0,
                score = best?.score ?: 0f,
                embedding = rec.embeddingOf(page.crops[i]),
                sizeRatio = page.crops[i].sizeRatio
            )
        }
        val refined = com.handwrite.core.handwriting.LetterConsensus.refine(items)
        println("consensus changed ${refined.count { it.voted }} labels")

        val text = StringBuilder()
        var accepted = 0
        for ((i, crop) in page.crops.withIndex()) {
            val gs = resolved[i]
            val base = gs.firstOrNull() ?: continue
            val best = GlyphRecognizer.Guess(refined[i].codePoint, refined[i].score)
            val second = gs.getOrNull(1)
            val margin = if (second == null) 1f else base.score - second.score
            text.append(
                if (best.score < 0.30f) "?"
                else String(Character.toChars(best.codePoint))
            )
            if (best.score >= 0.40f) {
                val sample = GlyphAtlas.Sample(
                    codePoint = best.codePoint,
                    tile = crop.tile,
                    embedding = em.embed(crop.tile),
                    confidence = best.score + margin * 0.3f,
                    aspect = crop.aspect,
                    sourcePage = pi,
                    pageX = crop.x.toFloat(),
                    pageY = crop.y.toFloat(),
                    sizeRatio = crop.sizeRatio,
                    centerRatio = crop.centerRatio
                )
                if (atlas.add(sample)) accepted++
            }
        }
        println("read: $text")
        if (System.getenv("ROWS") != null) {
            for ((ri, row) in page.rows.withIndex()) {
                val idx = page.crops.withIndex().filter { it.value.row == ri }.map { it.index }
                val line = StringBuilder()
                for (i in idx) {
                    val g = resolved[i]
                    val b = g.firstOrNull() ?: continue
                    line.append(if (b.score < 0.30f) "?" else String(Character.toChars(b.codePoint)))
                    if (System.getenv("TOP3") != null) {
                        line.append(g.take(3).joinToString("/") { String(Character.toChars(it.codePoint)) + it.score.toString().take(5) })
                        line.append(' ')
                    }
                }
                println("row $ri (${idx.size}): $line")
            }
        }
        println("accepted=$accepted")
        allText.append(text).append('\n')
        File(outDir, "page${pi + 1}.txt").writeText(text.toString())
    }

    println("\natlas: ${atlas.letterCount()} letters, ${atlas.sampleCount()} samples")
    atlas.deriveCapitals()
    val missing = StringBuilder()
    val ru = GlyphRecognizer.CYRILLIC
    for (c in ru) if (!atlas.hasCode(c.code)) missing.append(c)
    println("missing RU letters: ${if (missing.isEmpty()) "none" else missing}")

    File(outDir, "read.txt").writeText(allText.toString())

    // render the same text in the learned handwriting
    if (atlas.sampleCount() > 0) {
        val learned = com.handwrite.core.handwriting.AtlasGlyphSource(
            atlas,
            FileGlyphSource(FontData(File("app/src/main/assets/fonts/Caveat-Regular.ttf").readBytes()))
        )
        val style = TextStyle.defaultsFor(WritingMode.RUSSIAN_CURSIVE).copy(sizePt = 18f, seed = 3)
        val demo = "Соединение селекторов приводят к одному уровню"
        File(outDir, "learned.pdf").writeBytes(PdfPageComposer.compose(demo, style, learned, 1))
        println("wrote ${outDir}/learned.pdf")
    }
}

/** Same word-level script decision as MainActivity.resolveScripts (kept in sync manually). */
private fun resolveScripts(
    guesses: List<List<GlyphRecognizer.Guess>>,
    page: InkSegmenter.Page
): List<List<GlyphRecognizer.Guess>> {
    val out = ArrayList<List<GlyphRecognizer.Guess>>(guesses.size)
    var run = ArrayList<Int>()
    var cyr = 0f
    var lat = 0f
    fun flush() {
        val preferLatin = lat > cyr * 1.05f
        for (idx in run) {
            val gs = guesses[idx]
            val keep = if (preferLatin) gs.filter { GlyphRecognizer.scriptOfStatic(it.codePoint) == 2 }
            else gs.filter { GlyphRecognizer.scriptOfStatic(it.codePoint) == 1 }
            out[idx] = if (keep.isNotEmpty()) keep else gs
        }
        run = ArrayList()
        cyr = 0f
        lat = 0f
    }
    for (i in guesses.indices) {
        val gs = guesses[i]
        val best = gs.firstOrNull()
        if (best == null || GlyphRecognizer.scriptOfStatic(best.codePoint) == 0) {
            flush(); out.add(gs); continue
        }
        out.add(gs)
        run.add(i)
        cyr += gs.firstOrNull { GlyphRecognizer.scriptOfStatic(it.codePoint) == 1 }?.score ?: 0f
        lat += gs.firstOrNull { GlyphRecognizer.scriptOfStatic(it.codePoint) == 2 }?.score ?: 0f
        val crop = page.crops.getOrNull(i)
        val nextCrop = page.crops.getOrNull(i + 1)
        val gapTooWide = crop != null && nextCrop != null &&
            (nextCrop.x - (crop.x + crop.w)).toFloat() > page.lineHeight * 0.55f
        if (i == guesses.size - 1 || guesses[i + 1].isEmpty() || gapTooWide) flush()
    }
    flush()
    return out
}

/** Dev helper: writes the first [n] crops of a page as one contact sheet PNG. */
fun dumpCrops(page: InkSegmenter.Page, n: Int, file: File) {
    val cols = 16
    val cell = 40
    val rows = (minOf(n, page.crops.size) + cols - 1) / cols
    val img = java.awt.image.BufferedImage(cols * cell, kotlin.math.max(1, rows) * cell, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, img.width, img.height)
    for ((i, c) in page.crops.take(n).withIndex()) {
        val cx = (i % cols) * cell
        val cy = (i / cols) * cell
        for (y in 0 until cell) for (x in 0 until cell) {
            val v = c.tile[(y * 64 / cell) * 64 + (x * 64 / cell)]
            val level = ((1f - v) * 255).toInt().coerceIn(0, 255)
            img.setRGB(cx + x, cy + y, (level shl 16) or (level shl 8) or level)
        }
    }
    g.dispose()
    ImageIO.write(img, "png", file)
}
