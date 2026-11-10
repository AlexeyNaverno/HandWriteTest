package com.handwrite.core.handwriting

/**
 * Cleans up the labels a per-letter classifier produces on a real page.
 *
 * Two problems make letter-by-letter recognition of a photographed page unreliable:
 *
 *  1. **case**. Handwriting has one form of "о" - whether the page shows "О" or "о" is decided by
 *     size, not shape. A classifier that sees a circle answers "О", "o" or "о" at random, which both
 *     loses the letter and fills the atlas with duplicates.
 *  2. **islands of error**. The same letter looks the same everywhere on the page, so its crops form
 *     a tight cluster in embedding space. Inside such a cluster the classifier is right for the
 *     majority and wrong for the rest; letting the majority vote fixes most of the mistakes for free
 *     - no extra model, no extra memory.
 *
 * Both steps are cheap and deterministic, which matters because this runs on the phone.
 */
object LetterConsensus {

    /** a crop is a capital when it is this much taller than the small letters of its line */
    const val CAPITAL_RATIO = 1.35f

    /** cosine distance at which two crops are considered the same letter shape */
    private const val CLUSTER_SIM = 0.86f

    /** a cluster smaller than this is left alone: two crops agreeing prove nothing */
    private const val MIN_CLUSTER = 3

    /** share of a cluster that must agree before the vote is applied */
    private const val VOTE_SHARE = 0.6f

    /** a single recognised letter with the size it was written at */
    class Item(
        val codePoint: Int,
        val score: Float,
        val embedding: FloatArray,
        /** ink height divided by the median x-height of the row */
        val sizeRatio: Float
    )

    class Result(val codePoint: Int, val score: Float, val voted: Boolean)

    /**
     * @param items one entry per crop, in reading order
     * @return labels with the case fixed and the cluster vote applied
     */
    fun refine(items: List<Item>): List<Result> {
        val cased = items.map { caseBySize(it) }
        val labels = IntArray(cased.size) { cased[it].first }
        val scores = FloatArray(cased.size) { cased[it].second }
        val voted = BooleanArray(items.size)

        // greedy clustering: every crop joins the first cluster whose centre is close enough
        val used = BooleanArray(items.size)
        val clusterOf = IntArray(items.size) { -1 }
        val centres = ArrayList<FloatArray>()
        val members = ArrayList<MutableList<Int>>()
        for (i in items.indices) {
            if (used[i]) continue
            val c = topCode(items[i].embedding, centres)
            if (c >= 0) {
                members[c].add(i)
                clusterOf[i] = c
            } else {
                centres.add(items[i].embedding)
                members.add(mutableListOf(i))
                clusterOf[i] = centres.size - 1
            }
            used[i] = true
        }

        for ((ci, group) in members.withIndex()) {
            if (group.size < MIN_CLUSTER) continue
            val votes = HashMap<Int, Float>()
            for (i in group) votes[labels[i]] = (votes[labels[i]] ?: 0f) + maxOf(0.05f, items[i].score)
            val total = votes.values.sum()
            val best = votes.maxByOrNull { it.value } ?: continue
            if (best.value / total < VOTE_SHARE) continue
            val centre = centres[ci]
            for (i in group) {
                // only crops that really look like the cluster centre take the cluster label;
                // the outlier that joined first keeps its own answer
                if (cosine(items[i].embedding, centre) < CLUSTER_SIM) continue
                if (labels[i] != best.key) {
                    labels[i] = best.key
                    voted[i] = true
                }
                scores[i] = maxOf(scores[i], best.value / total * items[i].score.coerceAtLeast(0.35f))
            }
        }
        return items.indices.map { Result(labels[it], scores[it], voted[it]) }
    }

    private fun caseBySize(it: Item): Pair<Int, Float> {
        val cp = it.codePoint
        val ch = String(Character.toChars(cp))[0]
        if (!ch.isLetter()) return cp to it.score
        val upper = ch.isUpperCase()
        return when {
            upper && it.sizeRatio < CAPITAL_RATIO -> ch.lowercaseChar().code to it.score
            !upper && it.sizeRatio > CAPITAL_RATIO -> ch.uppercaseChar().code to it.score
            else -> cp to it.score
        }
    }

    private fun topCode(emb: FloatArray, centres: List<FloatArray>): Int {
        var best = -1
        var bestSim = CLUSTER_SIM
        for ((i, c) in centres.withIndex()) {
            val s = cosine(emb, c)
            if (s >= bestSim) {
                bestSim = s
                best = i
            }
        }
        return best
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        var na = 0f
        var nb = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            s += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na < 1e-6f || nb < 1e-6f) return 0f
        return s / kotlin.math.sqrt(na * nb)
    }
}
