import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.model.TextStyle
import com.handwrite.core.model.WritingMode
import com.handwrite.core.pdf.PdfPageComposer
import java.io.File

/** Dev harness: joins between letters at several letter spacings. */
fun main() {
    val src = FileGlyphSource(FontData(File("app/src/main/assets/fonts/Caveat-Regular.ttf").readBytes()))
    val base = TextStyle.defaultsFor(WritingMode.RUSSIAN_CURSIVE).copy(sizePt = 24f, jitterEm = 0f, jitterRotDeg = 0f, baselineWaveEm = 0f, slantDeg = 0f)
    for (ls in listOf(0.05f, 0.07f, 0.09f)) {
        val st = base.copy(letterSpacingEm = ls, seed = 7)
        val tag = (ls * 1000).toInt()
        File("build/dev/join2_$tag.pdf").writeBytes(PdfPageComposer.compose("ишмние миши", st, src, 3))
        println("ls=$ls")
    }
}
