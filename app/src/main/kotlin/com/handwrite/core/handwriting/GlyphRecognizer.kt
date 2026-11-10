package com.handwrite.core.handwriting

import com.handwrite.core.font.FileGlyphSource
import com.handwrite.core.font.GlyphSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The interface to the neural network. [HandwriteModel] implements it on the device;
 * tests and the training pipeline use a fake implementation.
 */
interface TileEmbedder {
    val inputSize: Int
    fun embed(tile: FloatArray): FloatArray
}

/**
 * Classifies letter crops with the CNN embedding + a bank of reference templates that were
 * rendered from the bundled font, plus handwriting-specific geometry rules
 * (x-height / ascender / descender bands).
 */
class GlyphRecognizer(
    private val embedder: TileEmbedder,
    /** true when the input is printed text (шире допустимые пропорции) */
    private val printed: Boolean = false
) {

    /**
     * When set, only these characters are considered. Cyrillic "а/е/о/р/с" and Latin "a/e/o/p/c"
     * are pixel-identical, so without a script filter the network cannot know which one to answer.
     */
    private var allowed: Set<Int>? = null

    /**
     * Cost added to a candidate that another script wrote the same way.
     *
     * Cyrillic and Latin share many letterforms (К/л look alike, М/м, Н/н, О/о, Т/т, а/a, с/c,
     * е/e, о/o, р/p, х/x, у/y) and the crop itself carries no hint of the language. On a page of
     * Russian notes the network happily returns "K" for "к" and "H" for "н", which both hides the
     * real letter and poisons the atlas. Sending mixed text through the recogniser (WritingMode
     * MIXED) switches this off.
     */
    var foreignScriptPenalty: Float = 0.12f

    fun setAllowedCharacters(chars: String?) {
        allowed = if (chars.isNullOrEmpty()) null else chars.map { it.code }.toSet()
    }

    class Template(
        val codePoint: Int,
        val embedding: FloatArray,
        /** ink bbox of the ideal glyph inside the tile (for shape scoring) */
        val aspect: Float,
        val ascender: Boolean,
        val descender: Boolean,
        val iDot: Boolean
    )

    class Guess(val codePoint: Int, val score: Float)

    private val templates = ArrayList<Template>()

    /**
     * Adds the glyphs of [source] to the reference bank. Calling it for several fonts makes the
     * bank cover different handwriting styles ("проMessy", "школьный", "печатный") which is what
     * lets templates built from one font recognise a different person's handwriting.
     */
    fun buildTemplates(source: GlyphSource, charset: String, clear: Boolean = false) {
        if (clear) templates.clear()
        val size = embedder.inputSize
        val coords = HashMap<String, Float>()
        if (source.supportsWeightAxis) coords["wght"] = source.weightDefault
        val em = source.unitsPerEm.toFloat()
        for (ch in charset) {
            val gid = source.gid(ch.code)
            if (gid == 0) continue
            val tile = TileRasterizer.tile(source, gid, coords)
            if (TileRasterizer.isEmpty(tile)) continue
            val path = source.path(gid, coords)
            val aspect = (path.maxX - path.minX) / max(1f, path.maxY - path.minY)
            val asc = path.maxY > em * 0.55f
            val desc = path.minY < -em * 0.02f
            val iDot = ch in "iйёіїj"
            templates.add(
                Template(
                    codePoint = ch.code,
                    embedding = embedder.embed(tile),
                    aspect = aspect,
                    ascender = asc,
                    descender = desc,
                    iDot = iDot
                )
            )
            @Suppress("UNUSED_VARIABLE") val s = size
        }
    }

    fun templateCount(): Int = templates.size

    /**
     * @param crop the segmented letter
     * @param line the whole page (for the x-height band)
     */
    fun classify(crop: InkSegmenter.Crop, line: InkSegmenter.Page): List<Guess> {
        if (templates.isEmpty()) return emptyList()
        val emb = embedder.embed(crop.tile)
        val top = crop.y.toFloat()
        val bottom = (crop.y + crop.h).toFloat()
        val mid = line.midline
        val lh = line.lineHeight
        val isTall = (mid - top) > lh * 0.42f          // reaches into the ascender band
        val isDeep = (bottom - mid) > lh * 0.32f       // goes below the baseline
        val scores = HashMap<Int, Float>()
        val filter = allowed
        val prefer = preferredScript()
        for (t in templates) {
            if (filter != null && t.codePoint !in filter) continue
            var sim = cosine(emb, t.embedding)
            if (prefer > 0 && scriptOf(t.codePoint) != prefer) sim -= foreignScriptPenalty
            // geometry priors help a lot on small datasets
            if (isTall && !t.ascender) sim -= 0.08f
            if (isDeep && !t.descender) sim -= 0.06f
            if (!isTall && t.ascender && crop.h < lh * 0.55f) sim -= 0.05f
            val aspectDiff = abs(crop.aspect.coerceIn(0.1f, 2f) - t.aspect)
            sim -= min(0.12f, aspectDiff * 0.10f)
            val prev = scores[t.codePoint]
            if (prev == null || sim > prev) scores[t.codePoint] = sim
        }
        return scores.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { Guess(it.key, it.value) }
    }

    fun classifyTile(tile: FloatArray): List<Guess> {
        val emb = embedder.embed(tile)
        val scores = HashMap<Int, Float>()
        val filter = allowed
        val prefer = preferredScript()
        for (t in templates) {
            if (filter != null && t.codePoint !in filter) continue
            var sim = cosine(emb, t.embedding)
            if (prefer > 0 && scriptOf(t.codePoint) != prefer) sim -= foreignScriptPenalty
            val prev = scores[t.codePoint]
            if (prev == null || sim > prev) scores[t.codePoint] = sim
        }
        return scores.entries.sortedByDescending { it.value }.take(6).map { Guess(it.key, it.value) }
    }

    /**
     * Which script the current [allowed] set is made of: 0 = mixed/unknown (no bias),
     * 1 = Cyrillic only, 2 = Latin only. Derived from the allowed characters, so the caller does
     * not have to pass the writing mode around.
     */
    private fun preferredScript(): Int {
        val f = allowed ?: return 0
        var cyr = 0
        var lat = 0
        for (cp in f) when (scriptOf(cp)) {
            1 -> cyr++
            2 -> lat++
        }
        return when {
            cyr > 0 && lat == 0 -> 1
            lat > 0 && cyr == 0 -> 2
            else -> 0
        }
    }

    /** 1 = Cyrillic letter, 2 = Latin letter, 0 = digit/punctuation */
    private fun scriptOf(cp: Int): Int {
        val b = Character.UnicodeBlock.of(cp)
        return when (b) {
            Character.UnicodeBlock.CYRILLIC,
            Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> 1
            Character.UnicodeBlock.BASIC_LATIN,
            Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
            Character.UnicodeBlock.LATIN_EXTENDED_A -> if (Character.isLetter(cp)) 2 else 0
            else -> 0
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    companion object {
        /** letters + digits + punctuation of the script the user is writing in */
        const val CYRILLIC = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
        const val LATIN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val DIGITS_PUNCT = "0123456789.,!?;:-—()\"'"

        fun charsetFor(mode: com.handwrite.core.model.WritingMode): String = when (mode) {
            com.handwrite.core.model.WritingMode.ENGLISH_PRINT -> LATIN + DIGITS_PUNCT
            com.handwrite.core.model.WritingMode.RUSSIAN_CURSIVE -> CYRILLIC + DIGITS_PUNCT
            com.handwrite.core.model.WritingMode.MIXED -> CYRILLIC + LATIN + DIGITS_PUNCT
        }

        val DEFAULT_CHARSET: String =
            "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" +
                "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ" +
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                "0123456789.,!?;:-—()\"'"
    }
}
