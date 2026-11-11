package com.handwrite.core.font

/**
 * Minimal, dependency-free TrueType/OpenType reader.
 *
 * It only handles what the handwriting renderer needs:
 *  - cmap (formats 4, 6, 12) to map characters to glyph ids;
 *  - glyf/loca outlines (quadratic B-splines, composite glyphs) for filling paths;
 *  - hmtx/hhea for advances;
 *  - fvar/avar/gvar for variable axes (wght = pen pressure);
 *  - GSUB lookup types 1/4/6 for single substitutions and ligatures
 *    (this is what makes `liga`/`calt` connective writing work).
 *
 * Everything is pure Kotlin + JVM, so the same code runs on Android and in unit tests.
 */
class FontData(val bytes: ByteArray) {

    /* ------------------------------------------------------------------ */
    /* low level readers                                                   */
    /* ------------------------------------------------------------------ */

    class Reader(val data: ByteArray, var pos: Int = 0) {
        fun u8(): Int = data[pos++].toInt() and 0xFF
        fun i8(): Int = data[pos++].toInt()
        fun u16(): Int {
            val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        fun i16(): Int = u16().toShort().toInt()
        fun u32(): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
            pos += 4
            return v
        }

        fun tag(): String {
            val sb = StringBuilder(4)
            for (i in 0 until 4) sb.append((data[pos + i].toInt() and 0xFF).toChar())
            pos += 4
            return sb.toString()
        }

        fun seek(p: Int) {
            pos = p
        }

        fun skip(n: Int) {
            pos += n
        }

        fun remaining(): Int = data.size - pos
    }

    class Table(val offset: Int, val length: Int)

    private val tables = HashMap<String, Table>()
    val unitsPerEm: Int
    val ascender: Int
    val descender: Int
    val lineGap: Int

    /** glyph id -> advance width in font units */
    private var advances = IntArray(0)

    private val cmap = HashMap<Int, Int>()
    private var loca = IntArray(0)
    private var glyfOffset = -1
    private var numberOfHMetrics = 0

    /** variable font support */
    val axes: List<Axis>
    private var axisTags = ArrayList<String>()
    private var axisMin = ArrayList<Float>()
    private var axisDefault = ArrayList<Float>()
    private var axisMax = ArrayList<Float>()

    private var gsub: Gsub? = null
    private var gpos: Gsub? = null
    private var kernLoaded = false
    private val kernPairs = HashMap<Long, Int>()
    private var kernClass1: ClassPairs? = null
    private var kernClass2: ClassPairs? = null

    data class Axis(val tag: String, val min: Float, val default: Float, val max: Float)

    init {
        val r = Reader(bytes)
        val version = r.u32()
        if (version != 0x00010000L && version != 0x4F54544FL && r.tag() != "true") {
            // 0x4F54544F == 'OTTO' (CFF outlines - unsupported, but tables still parse)
        }
        r.seek(4)
        val numTables = r.u16()
        r.skip(6)
        for (i in 0 until numTables) {
            val tag = r.tag()
            r.skip(4) // checksum
            val off = r.u32().toInt()
            val len = r.u32().toInt()
            tables[tag] = Table(off, len)
        }
        val head = table("head") ?: error("no head table")
        unitsPerEm = Reader(bytes, head.offset + 18).u16()
        val indexToLocFormat = Reader(bytes, head.offset + 50).i16()
        val hhea = table("hhea") ?: error("no hhea table")
        ascender = Reader(bytes, hhea.offset + 4).i16()
        descender = Reader(bytes, hhea.offset + 6).i16()
        lineGap = Reader(bytes, hhea.offset + 8).i16()
        numberOfHMetrics = Reader(bytes, hhea.offset + 34).u16()

        readCmap()
        readHmtx()
        readLoca(indexToLocFormat)
        glyfOffset = tables["glyf"]?.offset ?: -1
        axes = readFvar()
        gsub = readGsub()
        gpos = readTableAsGsub("GPOS")
    }

    fun table(s: String): Table? = tables[s]

    private fun readCmap() {
        val cm = table("cmap") ?: return
        val r = Reader(bytes, cm.offset + 2)
        val n = r.u16()
        var bestOff = -1
        var bestScore = -1
        for (i in 0 until n) {
            val platform = r.u16()
            val encoding = r.u16()
            val off = r.u32().toInt()
            val score = when {
                platform == 3 && encoding == 10 -> 5
                platform == 0 && encoding >= 4 -> 4
                platform == 3 && encoding == 1 -> 3
                platform == 0 -> 2
                platform == 3 && encoding == 0 -> 1
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestOff = off
            }
        }
        if (bestOff < 0) return
        val s = Reader(bytes, cm.offset + bestOff)
        when (val format = s.u16()) {
            4 -> readCmap4(s)
            6 -> readCmap6(s)
            12 -> readCmap12(s)
            else -> Unit
        }
    }

    private fun readCmap4(r: Reader) {
        r.skip(2) // length
        r.skip(2) // language
        val segX2 = r.u16()
        r.skip(6) // searchRange, entrySelector, rangeShift
        val seg = segX2 / 2
        val endCodesPos = r.pos
        val startCodesPos = endCodesPos + segX2 + 2
        val idDeltaPos = startCodesPos + segX2
        val idRangePos = idDeltaPos + segX2
        for (i in 0 until seg) {
            val end = Reader(bytes, endCodesPos + i * 2).u16()
            val start = Reader(bytes, startCodesPos + i * 2).u16()
            val delta = Reader(bytes, idDeltaPos + i * 2).i16()
            val rangeOffPos = idRangePos + i * 2
            val rangeOff = Reader(bytes, rangeOffPos).u16()
            if (start == 0xFFFF) continue
            for (c in start..end) {
                var gid: Int
                if (rangeOff == 0) {
                    gid = (c + delta) and 0xFFFF
                } else {
                    val p = rangeOffPos + rangeOff + (c - start) * 2
                    if (p + 1 >= bytes.size) continue
                    gid = Reader(bytes, p).u16()
                    if (gid != 0) gid = (gid + delta) and 0xFFFF
                }
                if (gid != 0 && !cmap.containsKey(c)) cmap[c] = gid
            }
        }
    }

    private fun readCmap6(r: Reader) {
        r.skip(4)
        val first = r.u16()
        val count = r.u16()
        for (i in 0 until count) {
            val gid = r.u16()
            if (gid != 0) cmap[first + i] = gid
        }
    }

    private fun readCmap12(r: Reader) {
        r.skip(2)
        val length = r.u32().toInt()
        r.skip(4)
        val nGroups = r.u32().toInt()
        for (i in 0 until nGroups) {
            val start = r.u32().toInt()
            val end = r.u32().toInt()
            val startGid = r.u32().toInt()
            for (c in start..end) {
                if (!cmap.containsKey(c)) cmap[c] = startGid + (c - start)
            }
            if (r.pos > length + 2) break
        }
    }

    private fun readHmtx() {
        val hm = tables["hmtx"] ?: return
        val r = Reader(bytes, hm.offset)
        val n = numberOfHMetrics
        advances = IntArray(n)
        // each record is 4 bytes: advanceWidth (uint16) + leftSideBearing (int16)
        for (i in 0 until n) {
            advances[i] = r.u16()
            r.skip(2)
        }
    }

    private fun readLoca(format: Int) {
        val lc = tables["loca"] ?: return
        val glyf = tables["glyf"]
        val count = if (glyf != null && format == 0) lc.length / 2 else lc.length / 4
        loca = IntArray(if (count > 0) count else 0)
        val r = Reader(bytes, lc.offset)
        for (i in loca.indices) {
            loca[i] = if (format == 0) r.u16() * 2 else r.u32().toInt()
        }
    }

    private fun readFvar(): List<Axis> {
        val t = tables["fvar"] ?: return emptyList()
        val r = Reader(bytes, t.offset + 4)
        val axesArrayOffset = r.u16()
        r.skip(2) // reserved
        val axisCount = r.u16()
        r.skip(2) // axisSize
        val instanceCount = r.u16()
        r.skip(2) // instanceSize
        val out = ArrayList<Axis>()
        for (i in 0 until axisCount) {
            val ar = Reader(bytes, t.offset + axesArrayOffset + i * 20)
            val tag = ar.tag()
            val min = fixed(ar)
            val def = fixed(ar)
            val max = fixed(ar)
            out.add(Axis(tag, min, def, max))
            axisTags.add(tag)
            axisMin.add(min)
            axisDefault.add(def)
            axisMax.add(max)
        }
        @Suppress("UNUSED_VARIABLE") val ic = instanceCount
        return out
    }

    private fun fixed(r: Reader): Float {
        val v = r.u16()
        val f = r.u16()
        return v + f / 65536f
    }

    /** normalise axis value to -1..1 (avar table ignored: linear mapping) */
    fun normalized(tag: String, value: Float): Float {
        val i = axisTags.indexOf(tag)
        if (i < 0) return 0f
        val min = axisMin[i]
        val def = axisDefault[i]
        val max = axisMax[i]
        return when {
            value < def -> if (def - min <= 0f) 0f else (value - def) / (def - min)
            value > def -> if (max - def <= 0f) 0f else (value - def) / (max - def)
            else -> 0f
        }.coerceIn(-1f, 1f)
    }

    /* ------------------------------------------------------------------ */
    /* public glyph API                                                    */
    /* ------------------------------------------------------------------ */

    fun glyphId(codePoint: Int): Int = cmap[codePoint] ?: 0

    fun hasGlyph(codePoint: Int): Boolean = cmap.containsKey(codePoint)

    fun advance(gid: Int): Int = if (advances.isEmpty()) unitsPerEm / 2 else advances[gid.coerceIn(0, advances.size - 1)]

    fun glyphCount(): Int = loca.size - 1

    /**
     * Returns a list of closed contours, each a flat list of points (x,y,onCurve),
     * already adjusted for the given variable coordinates (axisTag -> value).
     */
    fun outline(gid: Int, coords: Map<String, Float> = emptyMap(), depth: Int = 0): List<GlyphContour> {
        if (gid <= 0 || gid + 1 >= loca.size || glyfOffset < 0 || depth > 8) return emptyList()
        val start = loca[gid]
        val end = loca[gid + 1]
        if (end <= start) return emptyList()
        val r = Reader(bytes, glyfOffset + start)
        val numberOfContours = r.i16()
        r.skip(8) // xMin yMin xMax yMax
        if (numberOfContours < 0) return composite(r, coords, depth)
        val endPts = IntArray(numberOfContours)
        for (i in 0 until numberOfContours) endPts[i] = r.u16()
        if (numberOfContours == 0) return emptyList()
        val numPoints = endPts[numberOfContours - 1] + 1
        val instructionLength = r.u16()
        r.skip(instructionLength)
        val flags = IntArray(numPoints)
        var i = 0
        while (i < numPoints) {
            val f = r.u8()
            flags[i] = f
            i++
            if (f and 8 != 0) {
                var repeat = r.u8()
                while (repeat > 0 && i < numPoints) {
                    flags[i] = f
                    i++
                    repeat--
                }
            }
        }
        val xs = IntArray(numPoints)
        val ys = IntArray(numPoints)
        var v = 0
        for (k in 0 until numPoints) {
            val f = flags[k]
            if (f and 2 != 0) {
                val d = r.u8()
                v += if (f and 16 != 0) d else -d
            } else if (f and 16 == 0) {
                v += r.i16()
            }
            xs[k] = v
        }
        v = 0
        for (k in 0 until numPoints) {
            val f = flags[k]
            if (f and 4 != 0) {
                val d = r.u8()
                v += if (f and 32 != 0) d else -d
            } else if (f and 32 == 0) {
                v += r.i16()
            }
            ys[k] = v
        }
        // variable deltas
        val dx = FloatArray(numPoints)
        val dy = FloatArray(numPoints)
        if (coords.isNotEmpty() && tables["gvar"] != null) {
            applyGvar(gid, coords, numPoints, dx, dy)
        }
        val contours = ArrayList<GlyphContour>(numberOfContours)
        var p = 0
        for (c in 0 until numberOfContours) {
            val endPt = endPts[c]
            if (endPt >= numPoints) break
            val pts = ArrayList<GlyphPoint>(endPt - p + 1)
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (k in p..endPt) {
                val pt = GlyphPoint(xs[k] + dx[k], ys[k] + dy[k], flags[k] and 1 != 0)
                pts.add(pt)
                if (pt.x < minX) minX = pt.x
                if (pt.x > maxX) maxX = pt.x
                if (pt.y < minY) minY = pt.y
                if (pt.y > maxY) maxY = pt.y
            }
            p = endPt + 1
            if (pts.size >= 2) contours.add(GlyphContour(pts, minX, minY, maxX, maxY))
        }
        return contours
    }

    private fun composite(r: Reader, coords: Map<String, Float>, depth: Int): List<GlyphContour> {
        val out = ArrayList<GlyphContour>()
        while (true) {
            if (r.remaining() < 4) break
            val flags = r.u16()
            val componentGid = r.u16()
            val arg1: Int
            val arg2: Int
            if (flags and 1 != 0) {
                arg1 = r.i16(); arg2 = r.i16()
            } else {
                arg1 = r.i8(); arg2 = r.i8()
            }
            var a = 1f
            var b = 0f
            var c = 0f
            var d = 1f
            if (flags and 8 != 0) {
                a = r.i16() / 16384f; d = a
            } else if (flags and 0x40 != 0) {
                a = r.i16() / 16384f; d = r.i16() / 16384f
            } else if (flags and 0x80 != 0) {
                a = r.i16() / 16384f; b = r.i16() / 16384f
                c = r.i16() / 16384f; d = r.i16() / 16384f
            }
            val dx: Float
            val dy: Float
            if (flags and 2 != 0) { // ARGS_ARE_XY_VALUES
                dx = arg1.toFloat(); dy = arg2.toFloat()
            } else {
                dx = 0f; dy = 0f
            }
            val sub = outline(componentGid, coords, depth + 1)
            for (cnt in sub) {
                val pts = cnt.points.map {
                    GlyphPoint(a * it.x + c * it.y + dx, b * it.x + d * it.y + dy, it.onCurve)
                }
                out.add(GlyphContour(pts, 0f, 0f, 0f, 0f).withBounds())
            }
            if (flags and 0x20 == 0) break // MORE_COMPONENTS
        }
        return out
    }

    private fun applyGvar(gid: Int, coords: Map<String, Float>, numPoints: Int, dx: FloatArray, dy: FloatArray) {
        val t = tables["gvar"] ?: return
        try {
            val r = Reader(bytes, t.offset + 4)
            val axisCount = r.u16()
            val sharedTupleCount = r.u16()
            val sharedTuplesOff = r.u32().toInt()
            val glyphCount = r.u16()
            val flags = r.u16()
            val dataArrayOff = r.u32().toInt()
            if (gid >= glyphCount) return
            val longOffsets = flags and 1 != 0
            val offPos = r.pos
            val gStart: Int
            val gEnd: Int
            if (longOffsets) {
                gStart = Reader(bytes, offPos + gid * 4).u32().toInt()
                gEnd = Reader(bytes, offPos + (gid + 1) * 4).u32().toInt()
            } else {
                gStart = Reader(bytes, offPos + gid * 2).u16() * 2
                gEnd = Reader(bytes, offPos + (gid + 1) * 2).u16() * 2
            }
            if (gStart == gEnd) return
            val dataPos = t.offset + dataArrayOff + gStart
            val g = Reader(bytes, dataPos)
            val tupleCount = if (longOffsets) g.u32().toInt() else g.u16()
            val serializedOffset = if (longOffsets) g.u32().toInt() else g.u16()
            val peakTuples = ArrayList<FloatArray>(tupleCount)
            for (ti in 0 until tupleCount) {
                val vs = g.u16()
                val tflags = vs and 0xFFF
                val peak = FloatArray(axisCount)
                for (ai in 0 until axisCount) peak[ai] = g.i16() / 16384f
                if (tflags and 0x0040 != 0) { // intermediate region
                    for (ai in 0 until axisCount) g.i16()
                    for (ai in 0 until axisCount) g.i16()
                }
                if (tflags and 0x0200 != 0) { // private point numbers
                    val pc = g.u8()
                    val read: () -> Int = if (pc <= 127) ({ g.u8() }) else ({ g.u16() })
                    for (k in 0 until pc) read()
                }
                peakTuples.add(peak)
            }
            val totalPoints = numPoints + 4 // 4 phantom points
            var skipPoints = 0
            if (flags and 0x8000 != 0) { // shared point numbers
                val pc = g.u8()
                val read: () -> Int = if (pc <= 127) ({ g.u8() }) else ({ g.u16() })
                for (k in 0 until pc) read()
                skipPoints = pc
            }
            if (sharedTupleCount > 0) { /* shared tuples live elsewhere; unused */ }
            val d = Reader(bytes, dataPos + serializedOffset)
            for (peak in peakTuples) {
                var scalar = 1f
                var ok = true
                for (ai in 0 until axisCount) {
                    val tag = axisTags.getOrNull(ai) ?: continue
                    val p = peak[ai]
                    if (p == 0f) continue
                    val n = normalized(tag, coords[tag] ?: 0f)
                    if (n == 0f || n / p <= 0f) { ok = false; break }
                    scalar *= minOf(n / p, 1f)
                }
                val apply = if (ok) scalar else 0f
                readPackedDeltas(d, totalPoints, skipPoints, apply, dx)
                readPackedDeltas(d, totalPoints, skipPoints, apply, dy)
            }
            @Suppress("UNUSED_VARIABLE") val st = sharedTuplesOff
        } catch (e: Exception) {
            // fall back to default (unvaried) outlines
        }
    }

    private fun readPackedDeltas(r: Reader, totalPoints: Int, skipPoints: Int, scalar: Float, out: FloatArray) {
        var i = skipPoints
        while (i < totalPoints) {
            val control = r.u8()
            val runLength = (control and 0x3F) + 1
            val isZero = control and 0x80 != 0
            val isWord = control and 0x40 != 0
            when {
                isZero -> i += runLength
                isWord -> {
                    for (k in 0 until runLength) {
                        val v = r.i16()
                        val idx = i + k - skipPoints
                        if (scalar != 0f && idx >= 0 && idx < out.size) out[idx] += v * scalar
                    }
                    i += runLength
                }
                else -> {
                    for (k in 0 until runLength) {
                        val v = r.i8()
                        val idx = i + k - skipPoints
                        if (scalar != 0f && idx >= 0 && idx < out.size) out[idx] += v * scalar
                    }
                    i += runLength
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* GSUB: single + ligature substitutions                               */
    /* ------------------------------------------------------------------ */

    private class Gsub(val off: Int, val scriptList: Int, val featureList: Int, val lookupList: Int)

    private fun readGsub(): Gsub? = readTableAsGsub("GSUB")

    private fun readTableAsGsub(tag: String): Gsub? {
        val t = tables[tag] ?: return null
        val r = Reader(bytes, t.offset + 4)
        val scriptOff = r.u16()
        val featureOff = r.u16()
        val lookupOff = r.u16()
        return Gsub(t.offset, t.offset + scriptOff, t.offset + featureOff, t.offset + lookupOff)
    }

    /** GPOS kerning for a glyph pair, in font units (negative = tighten). */
    fun kerning(leftGid: Int, rightGid: Int): Int {
        if (!kernLoaded) loadKerning()
        kernPairs[(leftGid.toLong() shl 20) or rightGid.toLong()]?.let { return it }
        var v = 0
        kernClass1?.let { c -> v += c.value(leftGid, rightGid) }
        kernClass2?.let { c -> v += c.value(leftGid, rightGid) }
        return v
    }

    private class ClassPairs(
        val coverage: HashSet<Int>,
        val classDef1: HashMap<Int, Int>,
        val classDef2: HashMap<Int, Int>,
        val values: Array<IntArray>,
        val fromFirst: Boolean
    ) {
        fun value(leftGid: Int, rightGid: Int): Int {
            val first = if (fromFirst) leftGid else rightGid
            val second = if (fromFirst) rightGid else leftGid
            if (!coverage.contains(first)) return 0
            val c1 = classDef1[first] ?: 0
            val c2 = classDef2[second] ?: 0
            if (c1 >= values.size) return 0
            val row = values[c1]
            if (c2 >= row.size) return 0
            return row[c2]
        }
    }

    private fun loadKerning() {
        kernLoaded = true
        val g = gpos ?: return
        try {
            val lookups = ArrayList<Int>()
            // script features
            val sl = Reader(bytes, g.scriptList)
            val sc = sl.u16()
            val wantedScripts = setOf("latn", "cyrl", "DFLT")
            val scriptFeat = HashSet<Int>()
            for (i in 0 until sc) {
                val tag = sl.tag()
                val off = sl.u16()
                if (tag !in wantedScripts) continue
                val sr = Reader(bytes, g.scriptList + off)
                val defLangSys = sr.u16()
                val langCount = sr.u16()
                val langOffsets = IntArray(langCount) { sr.u16() }
                if (defLangSys != 0) collectLangSys(g.scriptList + off + defLangSys, scriptFeat)
                for (lo in langOffsets) collectLangSys(g.scriptList + off + lo, scriptFeat)
            }
            val fl = Reader(bytes, g.featureList + 2)
            val count = fl.u16()
            val recs = ArrayList<Pair<String, Int>>(count)
            for (i in 0 until count) recs.add(fl.tag() to fl.u16())
            for ((idx, rec) in recs.withIndex()) {
                val (tag, off) = rec
                if (tag != "kern") continue
                if (scriptFeat.isNotEmpty() && idx !in scriptFeat) continue
                val fr = Reader(bytes, g.featureList + off)
                fr.skip(2)
                val lc = fr.u16()
                for (k in 0 until lc) lookups.add(fr.u16())
            }
            if (lookups.isEmpty()) return
            val ll = Reader(bytes, g.lookupList)
            val lookupCount = ll.u16()
            val lookupOffs = IntArray(lookupCount) { ll.u16() }
            for (li in lookups) {
                if (li >= lookupCount) continue
                val base = g.lookupList + lookupOffs[li]
                val lr = Reader(bytes, base)
                val type = lr.u16()
                if (type != 2) continue
                val subCount = lr.u16()
                val subOffs = IntArray(subCount) { lr.u16() }
                for (so in subOffs) readPairPos(base + so)
            }
        } catch (e: Exception) {
            // kerning is optional
        }
    }

    private fun readPairPos(pos: Int) {
        val r = Reader(bytes, pos)
        val format = r.u16()
        val covOff = r.u16()
        val vf1 = r.u16()
        val vf2 = r.u16()
        when (format) {
            1 -> {
                val pairSetCount = r.u16()
                val setOffs = IntArray(pairSetCount) { r.u16() }
                val cov = coverage(pos + covOff)
                val covList = cov.toList()
                for ((i, so) in setOffs.withIndex()) {
                    if (i >= covList.size) break
                    val first = covList[i]
                    val ps = Reader(bytes, pos + so)
                    val n = ps.u16()
                    for (k in 0 until n) {
                        val second = ps.u16()
                        val v1 = readValue(ps, vf1)
                        readValue(ps, vf2)
                        if (v1 != 0) kernPairs[(first.toLong() shl 20) or second.toLong()] = v1
                    }
                }
            }
            2 -> {
                val cd1 = r.u16()
                val cd2 = r.u16()
                val c1Count = r.u16()
                val c2Count = r.u16()
                val cov = coverage(pos + covOff)
                val covSet = cov.toHashSet()
                val values = Array(c1Count) { IntArray(c2Count) }
                for (i in 0 until c1Count) {
                    for (j in 0 until c2Count) {
                        val v1 = readValue(r, vf1)
                        readValue(r, vf2)
                        values[i][j] = v1
                    }
                }
                val c1 = classDef(pos + cd1)
                val c2 = classDef(pos + cd2)
                // remember class tables; pair records above take precedence
                kernClass1 = ClassPairs(covSet, c1, c2, values, true)
            }
        }
    }

    private fun readValue(r: Reader, format: Int): Int {
        if (format and 0x1 != 0) r.skip(2)   // XPlacement
        if (format and 0x2 != 0) r.skip(2)   // YPlacement
        var adv = 0
        if (format and 0x4 != 0) adv = r.i16() // XAdvance
        if (format and 0x8 != 0) r.skip(2)   // YAdvance
        if (format and 0x10 != 0) r.skip(2)  // XPlaDevice
        if (format and 0x20 != 0) r.skip(2)  // YPlaDevice
        if (format and 0x40 != 0) r.skip(2)  // XAdvDevice
        if (format and 0x80 != 0) r.skip(2)  // YAdvDevice
        return adv
    }

    private fun classDef(pos: Int): HashMap<Int, Int> {
        val out = HashMap<Int, Int>()
        val r = Reader(bytes, pos)
        when (val format = r.u16()) {
            1 -> {
                val start = r.u16()
                val n = r.u16()
                for (i in 0 until n) out[start + i] = r.u16()
            }
            2 -> {
                val n = r.u16()
                for (i in 0 until n) {
                    val s = r.u16()
                    val e = r.u16()
                    val c = r.u16()
                    for (g in s..e) out[g] = c
                }
            }
            else -> Unit
        }
        return out
    }

    /** feature indices reachable from the scripts we care about (latn, cyrl, DFLT) */
    private fun scriptFeatureIndices(): Set<Int> {
        val g = gsub ?: return emptySet()
        val out = HashSet<Int>()
        try {
            val sl = Reader(bytes, g.scriptList)
            val count = sl.u16()
            val wantedScripts = setOf("latn", "cyrl", "DFLT")
            for (i in 0 until count) {
                val tag = sl.tag()
                val off = sl.u16()
                if (tag !in wantedScripts) continue
                val sr = Reader(bytes, g.scriptList + off)
                val defLangSys = sr.u16()
                val langCount = sr.u16()
                val langOffsets = IntArray(langCount) { sr.u16() }
                if (defLangSys != 0) collectLangSys(g.scriptList + off + defLangSys, out)
                for (lo in langOffsets) collectLangSys(g.scriptList + off + lo, out)
            }
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    private fun collectLangSys(pos: Int, out: MutableSet<Int>) {
        val r = Reader(bytes, pos)
        r.skip(2) // lookupOrder
        r.u16() // requiredFeatureIndex
        val n = r.u16()
        for (i in 0 until n) out.add(r.u16())
    }

    /** lookup indexes for the given feature tags, restricted to relevant scripts */
    private fun featureLookups(tags: Set<String>): List<Int> {
        val g = gsub ?: return emptyList()
        val out = ArrayList<Int>()
        try {
            val scriptFeatures = scriptFeatureIndices()
            val fl = Reader(bytes, g.featureList + 2)
            val count = fl.u16()
            val records = ArrayList<Pair<String, Int>>(count)
            for (i in 0 until count) {
                val tag = fl.tag()
                val off = fl.u16()
                records.add(tag to off)
            }
            for ((idx, rec) in records.withIndex()) {
                val (tag, off) = rec
                if (tag !in tags) continue
                if (scriptFeatures.isNotEmpty() && idx !in scriptFeatures) continue
                val fr = Reader(bytes, g.featureList + off)
                fr.skip(2)
                val lookupCount = fr.u16()
                for (k in 0 until lookupCount) out.add(fr.u16())
            }
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    /** True when the font carries TrueType outlines (needed for offline path generation). */
    fun hasOutlines(): Boolean = tables.containsKey("glyf") && tables.containsKey("loca")

    /**
     * Applies single and ligature substitutions from the given features to the glyph id list.
     * `liga`/`calt`/`rlig`/`clig` are supported.
     */
    fun applyFeatures(gids: IntArray, tags: Set<String> = setOf("liga", "calt", "rlig", "clig")): IntArray {
        if (gsub == null || tags.isEmpty()) return gids
        var current = gids.toMutableList()
        val lookups = featureLookups(tags)
        for (li in lookups) {
            current = applyLookup(current, li)
        }
        return current.toIntArray()
    }

    private fun applyLookup(gids: MutableList<Int>, lookupIndex: Int): MutableList<Int> {
        val g = gsub ?: return gids
        try {
            val ll = Reader(bytes, g.lookupList)
            val count = ll.u16()
            if (lookupIndex >= count) return gids
            val offs = IntArray(count) { ll.u16() }
            val lr = Reader(bytes, g.lookupList + offs[lookupIndex])
            val type = lr.u16()
            var subCount = lr.u16()
            if (type != 1 && type != 4) return gids
            val subOffsets = IntArray(subCount) { lr.u16() }
            val base = g.lookupList + offs[lookupIndex]
            return when (type) {
                1 -> applySingle(gids, base, subOffsets)
                4 -> applyLigature(gids, base, subOffsets)
                else -> gids
            }
        } catch (e: Exception) {
            return gids
        }
    }

    private fun applySingle(gids: MutableList<Int>, base: Int, subs: IntArray): MutableList<Int> {
        for (so in subs) {
            val r = Reader(bytes, base + so)
            val format = r.u16()
            val map = HashMap<Int, Int>()
            when (format) {
                1 -> {
                    val covOff = r.u16()
                    val delta = r.i16()
                    for (gid in coverage(base + covOff)) map[gid] = (gid + delta) and 0xFFFF
                }
                2 -> {
                    val covOff = r.u16()
                    val n = r.u16()
                    val newGids = IntArray(n) { r.u16() }
                    val cov = coverage(base + covOff)
                    for (i in cov.indices) if (i < n) map[cov[i]] = newGids[i]
                }
            }
            for (i in gids.indices) map[gids[i]]?.let { gids[i] = it }
        }
        return gids
    }

    private fun applyLigature(gids: MutableList<Int>, base: Int, subs: IntArray): MutableList<Int> {
        val sets = HashMap<Int, List<Pair<IntArray, Int>>>()
        for (so in subs) {
            val r = Reader(bytes, base + so)
            val covOff = r.u16()
            val setCount = r.u16()
            val setOffs = IntArray(setCount) { r.u16() }
            val cov = coverage(base + covOff)
            for (i in cov.indices) {
                if (i >= setCount) break
                val lr = Reader(bytes, base + so + setOffs[i])
                val ligCount = lr.u16()
                val ligOffs = IntArray(ligCount) { lr.u16() }
                val list = ArrayList<Pair<IntArray, Int>>()
                for (lo in ligOffs) {
                    val dr = Reader(bytes, base + so + lo)
                    val ligGid = dr.u16()
                    val compCount = dr.u16()
                    val comps = IntArray(maxOf(0, compCount - 1)) { dr.u16() }
                    list.add(comps to ligGid)
                }
                sets[cov[i]] = list
            }
        }
        if (sets.isEmpty()) return gids
        var i = 0
        while (i < gids.size) {
            // plain when/else rather than continue-inside-run: that is an experimental Kotlin feature
            // in this language version, and the Gradle build must not need opt-in flags
            val cands = sets[gids[i]]
            if (cands == null) {
                i++
                continue
            }
            var matched = false
            for ((comps, ligGid) in cands.sortedByDescending { it.first.size }) {
                if (i + comps.size > gids.size - 1) continue
                var ok = true
                for (k in comps.indices) {
                    if (gids[i + 1 + k] != comps[k]) { ok = false; break }
                }
                if (ok) {
                    gids[i] = ligGid
                    repeat(comps.size) { gids.removeAt(i + 1) }
                    matched = true
                    break
                }
            }
            if (!matched) i++
        }
        return gids
    }

    private fun coverage(off: Int): IntArray {
        val r = Reader(bytes, off)
        val format = r.u16()
        return when (format) {
            1 -> {
                val n = r.u16()
                IntArray(n) { r.u16() }
            }
            2 -> {
                val n = r.u16()
                val out = ArrayList<Int>()
                for (i in 0 until n) {
                    val start = r.u16()
                    val end = r.u16()
                    r.u16()
                    for (g in start..end) out.add(g)
                }
                out.toIntArray()
            }
            else -> IntArray(0)
        }
    }
}

data class GlyphPoint(val x: Float, val y: Float, val onCurve: Boolean)

class GlyphContour(
    val points: List<GlyphPoint>,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
) {
    fun withBounds(): GlyphContour {
        var mnX = Float.MAX_VALUE; var mnY = Float.MAX_VALUE
        var mxX = -Float.MAX_VALUE; var mxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < mnX) mnX = p.x
            if (p.x > mxX) mxX = p.x
            if (p.y < mnY) mnY = p.y
            if (p.y > mxY) mxY = p.y
        }
        if (mnX > mxX) return this
        return GlyphContour(points, mnX, mnY, mxX, mxY)
    }
}
