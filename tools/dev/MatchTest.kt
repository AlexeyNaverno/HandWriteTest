import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.handwriting.HandwriteModel
import com.handwrite.core.handwriting.InkMask
import com.handwrite.core.handwriting.InkSegmenter
import com.handwrite.core.handwriting.TileEmbedder
import com.handwrite.core.handwriting.TileRasterizer
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dev experiment: compare matchers for photo crops.
 *
 *   MATCH=iou  java ... MatchTest <photo.png> x0,y0,x1,y1
 *
 * For each crop of the selected line we print the best letter by
 *   - cosine on the 64-d network embedding (what the app uses), and
 *   - shape overlap (NCC) against the same font templates.
 */
fun main(args: Array<String>) {
    val img = ImageIO.read(File(args[0]))
    val r = args[1].split(",").map { it.toFloat() }
    val w = img.width
    val h = img.height
    val cr = FloatArray(w * h); val cg = FloatArray(w * h); val cb = FloatArray(w * h)
    val gray = FloatArray(w * h)
    val rgb = IntArray(w * h)
    img.getRGB(0, 0, w, h, rgb, 0, w)
    for (i in rgb.indices) {
        val p = rgb[i]
        cr[i] = ((p shr 16) and 0xFF) / 255f
        cg[i] = ((p shr 8) and 0xFF) / 255f
        cb[i] = (p and 0xFF) / 255f
        gray[i] = 0.299f * cr[i] + 0.587f * cg[i] + 0.114f * cb[i]
    }
    val ink = InkMask.compute(cr, cg, cb, w, h)
    for (y in 0 until h) for (x in 0 until w) {
        if (x < r[0] * w || x > r[2] * w || y < r[1] * h || y > r[3] * h) ink[y * w + x] = 0f
    }
    val page = InkSegmenter.segment(gray, w, h, preMask = ink)
    println("crops=${page.crops.size}")

    val model = HandwriteModel.load(File("app/src/main/assets/models/glyph_embed.bin").readBytes())
    val em = object : TileEmbedder {
        override val inputSize: Int = model.input
        override fun embed(tile: FloatArray): FloatArray = model.embed(tile)
    }
    // templates: one tile + embedding per glyph of five fonts
    val tpl = ArrayList<Triple<Int, FloatArray, FloatArray>>()
    for (a in listOf("Caveat-Regular.ttf", "MarckScript-Regular.ttf", "Neucha-Regular.ttf",
        "BadScript-Regular.ttf", "PatrickHand-Regular.ttf")) {
        val src = FileGlyphSource(FontData(File("app/src/main/assets/fonts/$a").readBytes()))
        for (ch in "абвгдеёжзийклмнопрстуфхцчшщъыьэюяabcdefghijklmnopqrstuvwxyz0123456789") {
            val gid = src.gid(ch.code)
            if (gid == 0) continue
            val tile = TileRasterizer.tile(src, gid)
            if (TileRasterizer.isEmpty(tile)) continue
            tpl.add(Triple(ch.code, tile, em.embed(tile)))
        }
    }
    println("templates=${tpl.size}")
    var cnnRight = 0
    var nccRight = 0
    for (c in page.crops) {
        val e = em.embed(c.tile)
        var bestCnn = 0
        var bestCnnS = -2f
        var bestNcc = 0
        var bestNccS = -2f
        for ((cp, tile, te) in tpl) {
            var s = 0f
            for (i in e.indices) s += e[i] * te[i]
            if (s > bestCnnS) { bestCnnS = s; bestCnn = cp }
            val n = ncc(c.tile, tile)
            if (n > bestNccS) { bestNccS = n; bestNcc = cp }
        }
        println(
            "crop ${c.x},${c.y} ${c.w}x${c.h}  cnn=${String(Character.toChars(bestCnn))}(${"%.2f".format(bestCnnS)})" +
                "  ncc=${String(Character.toChars(bestNcc))}(${"%.2f".format(bestNccS)})"
        )
    }
    @Suppress("UNUSED_VARIABLE") val unused = max(0, min(cnnRight, nccRight)) + abs(0)
}

/** normalised cross-correlation of two equally sized tiles, after a small blur to forgive offsets */
private fun ncc(a: FloatArray, b: FloatArray): Float {
    var meanA = 0f
    var meanB = 0f
    for (i in a.indices) { meanA += a[i]; meanB += b[i] }
    meanA /= a.size
    meanB /= b.size
    var num = 0f
    var da = 0f
    var db = 0f
    for (i in a.indices) {
        val x = a[i] - meanA
        val y = b[i] - meanB
        num += x * y
        da += x * x
        db += y * y
    }
    if (da < 1e-5f || db < 1e-5f) return -1f
    return num / kotlin.math.sqrt(da * db)
}
