package dev.loupe.core.index

import dev.loupe.core.profile.CompiledFacet
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.FacetMode

/**
 * The whole log, as columns of primitives.
 *
 * One `Long`/`Int` per entry per field, and **not one `String`** — the text stays in the files and
 * is reached through `(fileIdOf(entry), byteOffsets[entry], byteLengths[entry])`. That is what
 * makes the memory budget hold: M0 measured 30 bytes per entry on a nine-million-entry file,
 * independent of how long the lines are.
 *
 * Facets are whatever the profile declared, in its order, plus — when several files were merged —
 * a synthetic `file` facet appended at the end. Making the file a *facet* rather than a column
 * means `file:2026-06-02` works in the query language with no extra code, and it costs nothing at
 * all when a single file is open.
 *
 * Arrays are exposed directly and are exactly [entryCount] long: filtering is a tight loop over
 * them, and wrapping that in accessors would defeat the point.
 */
class LogIndex(
    val profile: CompiledProfile,
    val facets: List<CompiledFacet>,
    val entryCount: Int,
    /** Epoch millis, ascending — within a file by construction, across a merge by [IndexMerger]. */
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
    /** What the profile could not explain: counted in full, sampled by shape. */
    val unrecognised: UnrecognisedReport = UnrecognisedReport.EMPTY,
    /** Index into [facets] of the synthetic `file` facet, or [NO_FACET] for a single file. */
    val fileFacetIndex: Int = NO_FACET,
) {

    companion object {
        const val NO_VALUE: Int = -1
        const val NO_FACET: Int = -1

        /** The name and label of the synthetic facet a merge appends. */
        const val FILE_FACET: String = "file"

        fun fileFacet(): CompiledFacet = CompiledFacet(
            name = FILE_FACET,
            label = "File",
            group = CompiledProfile.NO_GROUP,
            mode = FacetMode.Always,
        )

        /** ts + level + offset + length, plus one Int per facet column. */
        fun bytesPerEntry(facetCount: Int): Int = 8 + 1 + 8 + 4 + 4 * facetCount
    }

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

    /** Which file an entry came from. Always `0` when only one file is open. */
    fun fileIdOf(entry: Int): Int = if (fileFacetIndex == NO_FACET) 0 else facetValues[fileFacetIndex][entry]

    /**
     * The entries around [entry], **ignoring any filter**.
     *
     * This is the point of it: what happened around a line is usually the reason it happened, and
     * a filter has by definition hidden that. The range is over the index, so with `level>=E` on
     * screen you still get the Debug lines that led up to the error.
     */
    fun neighbourhood(entry: Int, radius: Int): IntRange {
        if (entryCount == 0) return IntRange.EMPTY
        return (entry - radius).coerceAtLeast(0)..(entry + radius).coerceAtMost(entryCount - 1)
    }

    fun facetIndexOf(name: String): Int = facets.indexOfFirst { facet -> facet.name == name }

    fun dictionaryOf(name: String): ValueDictionary? =
        facetIndexOf(name).takeIf { index -> index >= 0 }?.let { index -> facetDictionaries[index] }

    /** Density per time bucket, per severity, over every entry. */
    fun timelineHistogram(bucketCount: Int): Array<IntArray> = timelineHistogram(bucketCount, entries = null, entryCount = entryCount)

    /**
     * Density per time bucket, per severity — the data behind the brushable timeline strip.
     *
     * Buckets always span the **whole** file, even when [entries] is a filtered subset: the strip
     * is a map of where you are, so it must not rescale under you every time a facet is ticked.
     *
     * @param entries indices to count, or `null` for all of them.
     */
    fun timelineHistogram(bucketCount: Int, entries: IntArray?, entryCount: Int): Array<IntArray> {
        val buckets: Array<IntArray> = Array(maxOf(profile.levelCount, 1)) { IntArray(bucketCount) }
        val span: Long = maxTimestampMillis - minTimestampMillis
        if (span <= 0L) return buckets
        for (position in 0 until entryCount) {
            val entry: Int = entries?.get(position) ?: position
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
