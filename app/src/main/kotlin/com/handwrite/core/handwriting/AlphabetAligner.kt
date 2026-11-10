package com.handwrite.core.handwriting

/**
 * Labelling for the "template sheet" workflow.
 *
 * When the user writes the alphabet in order (а, б, в, ... one letter per cell), the labels are
 * actually *known*: the only unknown is where each letter starts. That makes the result far more
 * reliable than pure OCR, and it is exactly how desktop font-from-handwriting tools work
 * (Draw Your Font CLI, FontCrafter, inktype).
 *
 * The alignment is a monotone dynamic program: every detected crop is matched to a letter of the
 * expected sequence (or skipped), using the neural network's scores as the match cost.
 */
object AlphabetAligner {

    val RU = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    val RU_UPPER = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    val EN = "abcdefghijklmnopqrstuvwxyz"
    val EN_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val DIGITS = "0123456789"

    class Result(
        /** code point assigned to each crop, or 0 when the crop is considered spurious */
        val labels: IntArray,
        val matched: Int,
        val expected: Int,
        val confidence: Float
    )

    /**
     * @param scores per crop: list of (codePoint, score) sorted descending (from the CNN)
     * @param expected the sequence the user was asked to write
     */
    fun align(scores: List<List<GlyphRecognizer.Guess>>, expected: String): Result {
        val n = scores.size
        val m = expected.length
        if (m == 0) return Result(IntArray(n), 0, 0, 0f)
        // dp[i][j]: best score using the first i crops to cover the first j expected letters
        val dp = Array(n + 1) { DoubleArray(m + 1) { Double.NEGATIVE_INFINITY } }
        val from = Array(n + 1) { ByteArray(m + 1) } // 0 = match, 1 = skip crop, 2 = skip letter
        dp[0][0] = 0.0
        for (j in 1..m) {
            dp[0][j] = 0.0
            from[0][j] = 2
        }
        for (i in 1..n) {
            // skipping a crop costs a little (prints, dots, noise)
            dp[i][0] = dp[i - 1][0] - 0.35
            from[i][0] = 1
        }
        for (i in 1..n) {
            for (j in 1..m) {
                val cp = expected[j - 1].code
                val matchScore = scoreFor(scores[i - 1], cp)
                val match = dp[i - 1][j - 1] + matchScore
                val skipCrop = dp[i - 1][j] - 0.35
                val skipLetter = dp[i][j - 1] - 0.20
                var best = match
                var dir: Byte = 0
                if (skipCrop > best) { best = skipCrop; dir = 1 }
                if (skipLetter > best) { best = skipLetter; dir = 2 }
                dp[i][j] = best
                from[i][j] = dir
            }
        }
        val labels = IntArray(n)
        var i = n
        var j = m
        var confidenceSum = 0.0
        while (i > 0 && j > 0) {
            when (from[i][j].toInt()) {
                0 -> {
                    labels[i - 1] = expected[j - 1].code
                    confidenceSum += scoreFor(scores[i - 1], expected[j - 1].code)
                    i--; j--
                }
                1 -> { labels[i - 1] = 0; i-- }
                else -> j--
            }
        }
        while (i > 0) {
            labels[i - 1] = 0
            i--
        }
        val matched = labels.count { it != 0 }
        return Result(labels, matched, m, (confidenceSum / maxOf(1, matched)).toFloat())
    }

    /**
     * How plausible is [cp] for this crop. The expected sequence is a strong prior: the user was
     * asked to write exactly these letters in this order, so being inside the network's top-3 is
     * already good evidence. Only a clear disagreement makes the aligner skip a letter.
     */
    private fun scoreFor(guesses: List<GlyphRecognizer.Guess>, cp: Int): Double {
        if (guesses.isEmpty()) return 0.8
        val idx = guesses.indexOfFirst { it.codePoint == cp }
        return when {
            idx == 0 -> 3.2 + guesses[0].score
            idx in 1..2 -> 2.6 + guesses[idx].score
            idx in 3..5 -> 1.8
            else -> 0.9
        }
    }

    /** One printable sheet layout: rows of characters to be written in order, top to bottom. */
    class Sheet(val name: String, val rows: List<String>) {
        /** non-space characters of the sheet, in reading order */
        val sequence: String = sequenceOf(rows)
    }

    /** A template sheet recognised inside a photo. */
    class Detection(
        val sheet: Sheet,
        /** code point per crop (0 = crop skipped) */
        val labels: IntArray,
        val matched: Int,
        val expected: Int,
        val confidence: Float,
        val quality: Float
    )

    /**
     * The sheet layouts the app can print and recognise. Recognition is automatic: if the photo's
     * row structure matches one of these layouts, the letters are labelled by position (much more
     * reliable than OCR), otherwise the caller falls back to plain OCR.
     */
    fun candidateSheets(): List<Sheet> = listOf(
        Sheet("Алфавит + цифры", RU.chunked(11) + DIGITS),
        Sheet("Алфавит", RU.chunked(11)),
        Sheet("Алфавит (строчные и заглавные)", RU.chunked(11) + RU_UPPER.chunked(11)),
        Sheet("Алфавит + цифры + пунктуация", RU.chunked(11) + DIGITS + ".,!?;:—()\"'"),
        Sheet("English + digits", EN.chunked(13) + DIGITS),
        Sheet("Полный лист", templateRows())
    )

    /**
     * Tries to see a known template sheet in the segmented page.
     *
     * @param rows crops of the page grouped per row (top to bottom, left to right); for every crop
     *        the CNN guesses, best first
     * @return the best matching sheet, or null when the page does not look like one
     */
    fun detect(rows: List<List<List<GlyphRecognizer.Guess>>>, expectedRowCounts: List<Int>): Detection? {
        if (rows.isEmpty()) return null
        var best: Detection? = null
        for (sheet in candidateSheets()) {
            val layout = sheet.rows.map { it.count { c -> c != ' ' } }
            if (layout.size != expectedRowCounts.size) continue
            var plausible = true
            for (i in layout.indices) {
                if (kotlin.math.abs(layout[i] - expectedRowCounts[i]) > 2) {
                    plausible = false
                    break
                }
            }
            if (!plausible) continue
            val cropGuesses = rows.flatten()
            val r = align(cropGuesses, sheet.sequence)
            if (r.matched < 8) continue
            // a sheet is "seen" when most of its letters were found and the CNN agrees with them
            val coverage = r.matched.toFloat() / maxOf(1, r.expected)
            val quality = coverage * 0.7f + (r.confidence / 4f).coerceIn(0f, 1f) * 0.3f
            if (best == null || quality > best!!.quality) {
                best = Detection(sheet, r.labels, r.matched, r.expected, r.confidence, quality)
            }
        }
        return best
    }

    /** the rows shown on the template sheet */
    fun templateRows(upper: Boolean = true, digits: Boolean = true): List<String> {
        val rows = ArrayList<String>()
        RU.chunked(11).forEach { rows.add(it) }
        if (upper) RU_UPPER.chunked(11).forEach { rows.add(it) }
        EN.chunked(13).forEach { rows.add(it) }
        if (digits) rows.add(DIGITS + " .,!?;:—()\"'")
        return rows
    }

    /** expected sequence for a set of rows (concatenated, in reading order) */
    fun sequenceOf(rows: List<String>): String {
        val sb = StringBuilder()
        for (r in rows) for (c in r) {
            if (c == ' ') continue
            sb.append(c)
        }
        return sb.toString()
    }
}
