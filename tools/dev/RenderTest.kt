import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.layout.HandLayout
import com.handwrite.core.pdf.PdfPageComposer
import com.handwrite.core.model.TextStyle
import com.handwrite.core.model.WritingMode
import java.io.File

/**
 * Developer harness: renders sample pages on the JVM so the layout can be checked
 * without an Android device (the app shares exactly the same code).
 *
 * usage: RenderTest <assets-fonts-dir> <out-dir>
 */
fun main(args: Array<String>) {
    val fontDir = File(args.getOrElse(0) { "app/src/main/assets/fonts" })
    val outDir = File(args.getOrElse(1) { "build/dev" })
    outDir.mkdirs()

    val ruSample = """
        Дорогой друг!
        Сегодня я хочу рассказать тебе о том, как важно беречь время.
        Каждое утро начинается с маленького шага: чашка чая, тёплый свет
        из окна и несколько строк в тетради. Если писать каждый день,
        почерк становится ровнее, а мысли — яснее.

        Мы часто откладываем важное на потом, но именно сейчас
        самое лучшее время начать. Попробуй написать письмо от руки,
        и ты почувствуешь, как оживает бумага.

        С уважением, твой друг.
    """.trimIndent()

    val enSample = """
        Dear friend,
        Today I want to tell you a short story about time and attention.
        Every morning starts with a small step: a cup of tea, warm light
        from the window and a few lines in a notebook. If you write every day,
        your hand becomes steadier and your thoughts become clearer.

        Best wishes, your friend.
    """.trimIndent()

    val caveat = FontData(File(fontDir, "Caveat-Regular.ttf").readBytes())
    val patrick = FontData(File(fontDir, "PatrickHand-Regular.ttf").readBytes())

    println("Caveat: upem=${caveat.unitsPerEm} glyphs=${caveat.glyphCount()} axes=${caveat.axes}")
    println("Patrick: upem=${patrick.unitsPerEm} glyphs=${patrick.glyphCount()} axes=${patrick.axes}")

    val ru = TextStyle.defaultsFor(WritingMode.RUSSIAN_CURSIVE).copy(sizePt = 15f)
    val pdf = PdfPageComposer.compose(ruSample, ru, FileGlyphSource(caveat, "Caveat"), 40)
    File(outDir, "sample_ru.pdf").writeBytes(pdf)

    val en = TextStyle.defaultsFor(WritingMode.ENGLISH_PRINT).copy(sizePt = 14f)
    val pdf2 = PdfPageComposer.compose(enSample, en, FileGlyphSource(patrick, "PatrickHand"), 40)
    File(outDir, "sample_en.pdf").writeBytes(pdf2)

    // feature / variant check
    val gids = ruSample.take(30).map { caveat.glyphId(it.code) }.toIntArray()
    val shaped = caveat.applyFeatures(gids, setOf("liga", "calt", "rlig", "clig"))
    println("gids before=${gids.toList()}")
    println("gids after =${shaped.toList()}")

    val res = HandLayout.layout(ruSample, ru, FileGlyphSource(caveat, "Caveat"))
    println("pages=${res.pages.size} lines=${res.pages.sumOf { it.lines.size }} glyphs=${res.pages.sumOf { p -> p.lines.sumOf { it.glyphs.size } }} connectors=${res.pages.sumOf { p -> p.lines.sumOf { it.connectors.size } }}")
    val resEn = HandLayout.layout(enSample, en, FileGlyphSource(patrick, "PatrickHand"))
    println("EN pages=${resEn.pages.size} glyphs=${resEn.pages.sumOf { p -> p.lines.sumOf { it.glyphs.size } }} connectors=${resEn.pages.sumOf { p -> p.lines.sumOf { it.connectors.size } }}")
    println("written to ${outDir.absolutePath}")
}
