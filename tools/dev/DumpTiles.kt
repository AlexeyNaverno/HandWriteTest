import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.TileRasterizer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Dev harness: dumps the tiles produced by [TileRasterizer] (the template bank the phone builds at
 * runtime) so they can be compared with the Python rasteriser used for training.
 *
 *   kotlinc app/src/main/kotlin/com/handwrite/core tools/dev/DumpTiles.kt -d /tmp/dt -nowarn
 *   java -cp /tmp/dt:<kotlin-stdlib> DumpTilesKt Caveat-Regular.ttf 1072,1073 out.bin
 */
fun main(args: Array<String>) {
    val src = FileGlyphSource(FontData(File("app/src/main/assets/fonts/${args[0]}").readBytes()))
    val cps = args[1].split(",").map { it.trim().toInt() }
    val bos = ByteArrayOutputStream()
    val out = DataOutputStream(bos)
    out.writeInt(cps.size)
    for (cp in cps) {
        val tile = TileRasterizer.tile(src, src.gid(cp))
        out.writeInt(cp)
        for (v in tile) out.writeFloat(v)
    }
    out.flush()
    File(args[2]).writeBytes(bos.toByteArray())
    println("wrote ${cps.size} tiles")
}
