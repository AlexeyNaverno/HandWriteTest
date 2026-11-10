import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.model.TextStyle
import com.handwrite.core.model.WritingMode
import com.handwrite.core.pdf.PdfPageComposer
import java.io.File

/** Dev harness: renders the same word with different slants so the skew direction can be checked. */
fun main() {
    val src = FileGlyphSource(FontData(File("app/src/main/assets/fonts/Caveat-Regular.ttf").readBytes()))
    val base = TextStyle.defaultsFor(WritingMode.RUSSIAN_CURSIVE).copy(sizePt = 22f, letterSpacingEm = 0f)
    for (deg in listOf(-12f, 0f, 8f, 16f)) {
        val st = base.copy(slantDeg = deg, connectLetters = deg >= 0f)
        File("build/dev/slant_${deg.toInt()}.pdf").writeBytes(PdfPageComposer.compose("Ии ши нн", st, src, 2))
        println("slant=$deg")
    }
}
