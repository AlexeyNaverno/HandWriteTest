package com.handwrite.app

import android.content.Context
import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.FontData
import com.handwrite.core.font.GlyphSource
import com.handwrite.core.handwriting.AtlasGlyphSource
import com.handwrite.core.handwriting.GlyphAtlas
import com.handwrite.core.handwriting.GlyphRecognizer
import com.handwrite.core.handwriting.HandwriteModel
import com.handwrite.core.handwriting.TileEmbedder
import com.handwrite.core.model.WritingMode
import java.io.File

/**
 * Access to the bundled handwriting fonts and the on-device neural network.
 * Fonts are OFL licensed (see assets/fonts/OFL-*.txt).
 */
object HandAssets {

    class FontPack(
        val asset: String,
        val label: String,
        val description: String,
        val connects: Boolean,
        val modes: Set<WritingMode>
    )

    val FONTS = listOf(
        FontPack(
            "Caveat-Regular.ttf", "Caveat",
            "Беглый курсив с настоящими соединениями, вариативный вес (нажим)",
            connects = true,
            modes = setOf(WritingMode.RUSSIAN_CURSIVE, WritingMode.MIXED, WritingMode.ENGLISH_PRINT)
        ),
        FontPack(
            "MarckScript-Regular.ttf", "Marck Script",
            "Классическая русская пропись: буквы сливаются в слово",
            connects = true,
            modes = setOf(WritingMode.RUSSIAN_CURSIVE, WritingMode.MIXED)
        ),
        FontPack(
            "Neucha-Regular.ttf", "Neucha",
            "Аккуратный школьный почерк, слегка угловатый",
            connects = false,
            modes = setOf(WritingMode.RUSSIAN_CURSIVE, WritingMode.MIXED, WritingMode.ENGLISH_PRINT)
        ),
        FontPack(
            "BadScript-Regular.ttf", "Bad Script",
            "Тонкое перо, лёгкий наклон",
            connects = true,
            modes = setOf(WritingMode.RUSSIAN_CURSIVE, WritingMode.MIXED)
        ),
        FontPack(
            "PatrickHand-Regular.ttf", "Patrick Hand",
            "Печатные английские буквы, без соединений",
            connects = false,
            modes = setOf(WritingMode.ENGLISH_PRINT, WritingMode.MIXED)
        )
    )

    /** fonts used to build reference templates for the recogniser */
    private val TEMPLATE_FONTS = listOf(
        "Caveat-Regular.ttf",
        "MarckScript-Regular.ttf",
        "Neucha-Regular.ttf",
        "BadScript-Regular.ttf",
        "PatrickHand-Regular.ttf"
    )

    private val fontCache = HashMap<String, FontData>()

    fun font(context: Context, asset: String): FontData? = synchronized(fontCache) {
        fontCache[asset]?.let { return it }
        val f = try {
            FontData(context.assets.open("fonts/$asset").use { it.readBytes() })
        } catch (e: Exception) {
            null
        }
        if (f != null) fontCache[asset] = f
        f
    }

    fun source(context: Context, asset: String): GlyphSource? =
        font(context, asset)?.let { FileGlyphSource(it, asset) }

    /** the network wrapper used by [GlyphRecognizer] */
    fun embedder(context: Context): TileEmbedder? {
        val model = com.handwrite.app.handwriting.HandwriteModelLoader.get(context) ?: return null
        return object : TileEmbedder {
            override val inputSize: Int get() = model.input
            override fun embed(tile: FloatArray): FloatArray = model.embed(tile)
        }
    }

    /** builds the template bank once (used for photo -> letters) */
    @Volatile
    private var recognizer: GlyphRecognizer? = null

    /**
     * Narrows the recognised alphabet to the language of the photos. Called by the app right before
     * learning so that Cyrillic handwriting is not classified as the Latin letters that look the
     * same (к→K, н→H, о→O) - see [GlyphRecognizer.foreignScriptPenalty].
     */
    fun setRecognizerLanguage(recognizer: GlyphRecognizer, mode: WritingMode) {
        recognizer.setAllowedCharacters(GlyphRecognizer.charsetFor(mode))
    }

    fun recognizer(context: Context): GlyphRecognizer? {
        recognizer?.let { return it }
        synchronized(this) {
            recognizer?.let { return it }
            val emb = embedder(context) ?: return null
            val r = GlyphRecognizer(emb)
            for (asset in TEMPLATE_FONTS) {
                val src = source(context, asset) ?: continue
                r.buildTemplates(src, GlyphRecognizer.DEFAULT_CHARSET)
            }
            r.setAllowedCharacters(GlyphRecognizer.DEFAULT_CHARSET)
            // the charset starts as "everything"; the learning flow narrows it to the language of
            // the page (see MainActivity.onPhotosPicked -> setLanguage)
            recognizer = r
            return r
        }
    }

    fun atlasSource(atlas: GlyphAtlas, context: Context, fallbackAsset: String): GlyphSource {
        val fallback = source(context, fallbackAsset)
        return AtlasGlyphSource(atlas, fallback)
    }

    /* ------------------------- persistence ------------------------- */

    private fun atlasFile(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "atlas_v1.bin")

    fun saveAtlas(context: Context, atlas: GlyphAtlas) {
        atlasFile(context).writeBytes(AtlasCodec.encode(atlas))
    }

    fun loadAtlas(context: Context): GlyphAtlas? {
        val f = atlasFile(context)
        if (!f.exists()) return null
        return try {
            AtlasCodec.decode(f.readBytes())
        } catch (e: Exception) {
            null
        }
    }

    fun deleteAtlas(context: Context) {
        atlasFile(context).delete()
    }
}
