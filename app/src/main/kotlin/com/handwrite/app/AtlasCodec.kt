package com.handwrite.app

import com.handwrite.core.handwriting.GlyphAtlas
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Binary (de)serialisation of the learned handwriting atlas. */
object AtlasCodec {

    private const val MAGIC = 0x48574131 // "HWA1"
    private const val VERSION = 2

    fun encode(atlas: GlyphAtlas): ByteArray {
        val bos = ByteArrayOutputStream()
        val o = DataOutputStream(bos)
        o.writeInt(MAGIC)
        o.writeInt(VERSION)
        o.writeUTF(atlas.name)
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
            atlas.add(GlyphAtlas.Sample(cp, tile, emb, conf, aspect, page, px, py, sizeRatio, centerRatio))
        }
        return atlas
    }
}
