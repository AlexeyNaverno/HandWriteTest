import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.model.TextStyle
import com.handwrite.core.model.WritingMode
import com.handwrite.core.pdf.PdfPageComposer
import java.io.File

/** Dev harness: isolates the skew transform with a monospaced font whose stems are vertical. */
fun main() {
    val src = FileGlyphSource(FontData(File("app/src/main/assets/fonts/_test_mono.ttf").readBytes()))
    val base = TextStyle.defaultsFor(WritingMode.RUSSIAN_CURSIVE).copy(sizePt = 40f, letterSpacingEm = 0f, connectLetters = false)
    for (deg in listOf(-15f, 0f, 15f)) {
        File("build/dev/probe_${deg.toInt()}.pdf").writeBytes(PdfPageComposer.compose("Н", base.copy(slantDeg = deg), src, 1))
    }
    println("ok")
}
