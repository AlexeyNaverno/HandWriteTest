import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.StrokeFlow
import com.handwrite.core.handwriting.TileRasterizer
import java.io.File

fun main() {
    val src = FileGlyphSource(FontData(File("app/src/main/assets/fonts/Caveat-Regular.ttf").readBytes()))
    for (ch in "иошмае") {
        val tile = TileRasterizer.tile(src, src.gid(ch.code))
        val f = StrokeFlow.analyse(tile)
        println("$ch entry=${f.entry?.let { "(%.2f,%.2f) dir=(%.2f,%.2f)".format(it.x, it.y, it.dx, it.dy) }} exit=${f.exit?.let { "(%.2f,%.2f) dir=(%.2f,%.2f)".format(it.x, it.y, it.dx, it.dy) }}")
    }
}
