import com.handwrite.app.AtlasCodec
import com.handwrite.core.handwriting.GlyphAtlas

/** Dev harness: atlas serialisation round-trip (the app keeps the learned font in a file). */
fun main() {
    val a = GlyphAtlas("тест")
    val tile = FloatArray(64 * 64) { i -> ((i * 37) % 255) / 255f }
    val emb = FloatArray(128) { i -> (i % 7 - 3) / 7f }
    a.add(GlyphAtlas.Sample('а'.code, tile, emb, 0.9f, 0.8f, 0, 10f, 20f, 1.2f, -0.1f))
    a.add(GlyphAtlas.Sample('б'.code, tile, emb, 0.7f, 1.4f, 1, 30f, 40f, 1.8f, -0.3f))
    a.deriveCapitals()
    val bytes = AtlasCodec.encode(a)
    val b = AtlasCodec.decode(bytes)
    val ok = b.sampleCount() == a.sampleCount() && b.letterCount() == a.letterCount() &&
        b.variants('а'.code).first().sizeRatio == 1.2f &&
        b.variants('б'.code).first().centerRatio == -0.3f &&
        b.hasCode('А'.code) && b.variants('А'.code).first().sizeRatio == 1.35f
    println("bytes=${bytes.size} samples=${a.sampleCount()}->${b.sampleCount()} letters=${b.letterCount()} roundtrip=${if (ok) "OK" else "FAIL"}")
    require(ok) { "atlas codec lost data" }
}
