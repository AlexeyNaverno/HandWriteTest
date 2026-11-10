import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.PenPath
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import com.handwrite.core.handwriting.TileRasterizer

/**
 * Dev harness: prints the reconstructed pen path for letters and draws it as an SVG, so the order of
 * strokes can be checked by eye against how the letter is actually written.
 */
fun main(args: Array<String>) {
    val src = FileGlyphSource(FontData(java.io.File("app/src/main/assets/fonts/Caveat-Regular.ttf").readBytes()))
    val letters = args.firstOrNull() ?: "ишмниеабвя"
    val cell = 120
    val img = BufferedImage(cell * letters.length, cell, BufferedImage.TYPE_INT_RGB)
    val g2 = img.createGraphics()
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g2.color = Color.WHITE
    g2.fillRect(0, 0, img.width, img.height)
    val colours = listOf(Color(31, 119, 180), Color(214, 39, 40), Color(44, 160, 44), Color(148, 103, 189), Color(255, 127, 14))
    for ((k, ch) in letters.withIndex()) {
        val gid = src.gid(ch.code)
        if (gid == 0) continue
        val tile = TileRasterizer.tile(src, gid)
        val path = PenPath.reconstruct(tile, TileRasterizer.SIZE)
        val ox = k * cell
        // faint ink for reference
        for (y in 0 until 64) for (x in 0 until 64) {
            if (tile[y * 64 + x] > 0.5f) {
                g2.color = Color(232, 232, 232)
                g2.fillRect(ox + x * cell / 64, y * cell / 64, cell / 64 + 1, cell / 64 + 1)
            }
        }
        g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for ((si, st) in path.strokes.withIndex()) {
            g2.color = colours[si % colours.size]
            for (i2 in 1 until st.points.size) {
                val a = st.points[i2 - 1]
                val b = st.points[i2]
                g2.drawLine((ox + a.x * cell).toInt(), (a.y * cell).toInt(), (ox + b.x * cell).toInt(), (b.y * cell).toInt())
            }
            g2.color = Color.GREEN
            g2.fillOval((ox + st.start.x * cell).toInt() - 4, (st.start.y * cell).toInt() - 4, 8, 8)
            g2.color = Color.RED
            g2.fillOval((ox + st.end.x * cell).toInt() - 4, (st.end.y * cell).toInt() - 4, 8, 8)
        }
        g2.color = Color.BLACK
        g2.font = Font("SansSerif", Font.PLAIN, 13)
        g2.drawString(ch.toString(), ox + 5, 15)
        val e = path.entryDir
        val x2 = path.exitDir
        println(
            "$ch strokes=${path.strokes.size} pts=${path.strokes.sumOf { it.points.size }} " +
                "start=${path.start?.let { "%.2f,%.2f".format(it.x, it.y) }} end=${path.end?.let { "%.2f,%.2f".format(it.x, it.y) }} " +
                "entryDir=%.2f,%.2f exitDir=%.2f,%.2f q=%.2f".format(e.first, e.second, x2.first, x2.second, path.quality())
        )
    }
    g2.dispose()
    val out = java.io.File("build/dev/penpath.png")
    out.parentFile.mkdirs()
    ImageIO.write(img, "png", out)
    println("wrote $out")
}
