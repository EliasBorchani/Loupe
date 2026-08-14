package dev.loupe.core.index

import dev.loupe.core.profile.CompiledFacet
import dev.loupe.core.profile.CompiledProfile

/**
 * The whole log, as columns of primitives.
 *
 * One `Long`/`Int` per entry per field, and **not one `String`** — the text stays in the file and
 * is reached through `(byteOffsets[i], byteLengths[i])`. That is what makes the memory budget hold:
 * M0 measured 30 bytes per entry on a nine-million-entry file, independent of how long the lines
 * are.
 *
 * Facets are whatever the profile declared, in its order — [facetValues] is `[facetIndex][entry]`.
 * Arrays are exposed directly and are exactly [entryCount] long: filtering is a tight loop over
 * them, and wrapping that in accessors would defeat the point.
 */
class LogIndex(
    val profile: CompiledProfile,
    val entryCount: Int,
    /** Epoch millis. Ascending within one file; a merged multi-file view sorts afterwards. */
    val timestamps: LongArray,
    /** Severity ordinals on the profile's scale, or [dev.loupe.core.profile.LevelDecoder.UNKNOWN_ORDINAL]. */
    val levels: ByteArray,
    /** `[facetIndex][entry]` → id into the matching [facetDictionaries], or [NO_VALUE]. */
    val facetValues: Array<IntArray>,
    val facetDictionaries: Array<ValueDictionary>,
    val byteOffsets: LongArray,
    val byteLengths: IntArray,
    /** Entry count per severity ordinal, accumulated during the indexing pass. */
    val levelCounts: IntArray,
    val lineCount: Long,
    val continuationLineCount: Long,
    val sectionLineCount: Long,
    val noticeLineCount: Long,
    val unrecognisedLineCount: Long,
) {

    companion object {
        const val NO_VALUE: Int = -1

        /** ts + level + offset + length, plus one Int per facet column. */
        fun bytesPerEntry(facetCount: Int): Int = 8 + 1 + 8 + 4 + 4 * facetCount
    }

    val facets: List<CompiledFacet> get() = profile.facets

    val minTimestampMillis: Long
    val maxTimestampMillis: Long

    init {
        // Deliberately a raw loop: `timestamps.take(n).minOrNull()` would box nine million Longs.
        var minimum: Long = Long.MAX_VALUE
        var maximum: Long = Long.MIN_VALUE
        for (entry in 0 until entryCount) {
            val timestamp: Long = timestamps[entry]
            if (timestamp < minimum) minimum = timestamp
            if (timestamp > maximum) maximum = timestamp
        }
        minTimestampMillis = if (entryCount == 0) 0L else minimum
        maxTimestampMillis = if (entryCount == 0) 0L else maximum
    }

    /** Share of lines the profile accounted for — the "4 812 / 4 815" health indicator. */
    val recognisedLineRatio: Double
        get() = if (lineCount == 0L) 1.0 else (lineCount - unrecognisedLineCount).toDouble() / lineCount

    val estimatedHeapBytes: Long get() = entryCount.toLong() * bytesPerEntry(facetValues.size)

    fun facetIndexOf(name: String): Int = facets.indexOfFirst { facet -> facet.name == name }

    fun dictionaryOf(name: String): ValueDictionary? =
        facetIndexOf(name).takeIf { index -> index >= 0 }?.let { index -> facetDictionaries[index] }

    /** Density per time bucket, per severity — the data behind the brushable timeline strip. */
    fun timelineHistogram(bucketCount: Int): Array<IntArray> {
        val buckets: Array<IntArray> = Array(maxOf(profile.levelCount, 1)) { IntArray(bucketCount) }
        val span: Long = maxTimestampMillis - minTimestampMillis
        if (span <= 0L) return buckets
        for (entry in 0 until entryCount) {
            val levelOrdinal: Int = levels[entry].toInt()
            if (levelOrdinal < 0) continue
            val bucket: Int = (((timestamps[entry] - minTimestampMillis) * bucketCount) / span)
                .toInt()
                .coerceAtMost(bucketCount - 1)
            buckets[levelOrdinal][bucket]++
        }
        return buckets
    }
}
