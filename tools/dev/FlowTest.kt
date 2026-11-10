import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.StrokeFlow
import com.handwrite.core.handwriting.TileRasterizer
import java.io.File

/** Dev harness: skeleton + pen entry/exit of letters, computed from their ink tiles. */
fun main() {
    val src = FileGlyphSource(FontData(File("app/src/main/assets/fonts/Caveat-Regular.ttf").readBytes()))
    for (ch in "иошмня") {
        val tile = TileRasterizer.tile(src, src.gid(ch.code))
        val f = StrokeFlow.analyse(tile)
        println("$ch: entry=${f.entry?.let { "(%.2f,%.2f) dir(%.2f,%.2f) len=%.1f".format(it.x, it.y, it.dx, it.dy, it.length) }} exit=${f.exit?.let { "(%.2f,%.2f) dir(%.2f,%.2f) len=%.1f".format(it.x, it.y, it.dx, it.dy, it.length) }}")
        println(StrokeFlow.skeletonAscii(tile))
    }
}
