package com.handwrite.app

import com.handwrite.core.handwriting.GlyphAtlas
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Binary (de)serialisation of the learned handwriting atlas. */
object AtlasCodec {

    private const val MAGIC = 0x48574131 // "HWA1"
    /**
     * 2: sizeRatio / centerRatio per sample.
     * 3: the ligature table (pairs the writer joined into one crop). Old files still load - they simply
     *    have no ligatures, and the layout falls back to two letters plus a connector.
     */
    private const val VERSION = 3

    fun encode(atlas: GlyphAtlas): ByteArray {
        val bos = ByteArrayOutputStream()
        val o = DataOutputStream(bos)
        o.writeInt(MAGIC)
        o.writeInt(VERSION)
        o.writeUTF(atlas.name)
        // Ligatures are stored before the samples so a reader can rebuild the pair -> code map first
        // and then hand every sample to the atlas, which checks the code against that map.
        val pairs = atlas.ligaturePairs().toList()
        o.writeInt(pairs.size)
        for (pair in pairs) {
            o.writeInt(pair.codePointAt(0))
            o.writeInt(pair.codePointAt(pair.offsetByCodePoints(0, 1)))
            o.writeInt(atlas.ligatureCode(pair))
        }
        o.writeInt(atlas.samples.size)
        for (s in atlas.samples) {
            o.writeInt(s.codePoint)
            o.writeFloat(s.confidence)
            o.writeFloat(s.aspect)
            o.writeInt(s.sourcePage)
            o.writeFloat(s.pageX)
            o.writeFloat(s.pageY)
            o.writeFloat(s.sizeRatio)
            o.writeFloat(s.centerRatio)
            o.writeInt(s.tile.size)
            for (v in s.tile) o.writeByte((v * 255f).toInt().coerceIn(0, 255))
            o.writeInt(s.embedding.size)
            for (v in s.embedding) o.writeFloat(v)
        }
        o.flush()
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): GlyphAtlas {
        val i = DataInputStream(ByteArrayInputStream(bytes))
        require(i.readInt() == MAGIC) { "not an atlas file" }
        val version = i.readInt()
        val atlas = GlyphAtlas(i.readUTF())
        if (version >= 3) {
            val pairCount = i.readInt()
            repeat(pairCount) {
                val a = i.readInt()
                val b = i.readInt()
                val code = i.readInt()
                atlas.restoreLigature(a, b, code)
            }
        }
        val n = i.readInt()
        repeat(n) {
            val cp = i.readInt()
            val conf = i.readFloat()
            val aspect = i.readFloat()
            val page = i.readInt()
            val px = i.readFloat()
            val py = i.readFloat()
            val sizeRatio = if (version >= 2) i.readFloat() else 1f
            val centerRatio = if (version >= 2) i.readFloat() else 0f
            val tileSize = i.readInt()
            val tile = FloatArray(tileSize) { (i.readUnsignedByte() / 255f) }
            val embSize = i.readInt()
            val emb = FloatArray(embSize) { i.readFloat() }
            // no variant cap on load: the file already holds what the user's photos produced, and
            // dropping the weakest variants here would silently change a saved handwriting
            atlas.add(GlyphAtlas.Sample(cp, tile, emb, conf, aspect, page, px, py, sizeRatio, centerRatio))
        }
        return atlas
    }
}
