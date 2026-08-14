package dev.loupe.core.index

import dev.loupe.core.model.LogLevel

/**
 * The whole log, as columns of primitives.
 *
 * One `Int`/`Long` per entry per field, and **not one `String`** — the text stays in the file and
 * is reached through `(byteOffsets[i], byteLengths[i])`. This is what makes the memory budget in
 * the PRD (§8) hold: ~33 bytes per entry, independent of how long the lines are.
 *
 * Arrays are exposed directly and are exactly [entryCount] long: filtering is a tight loop over
 * them, and wrapping that in accessors would defeat the point.
 */
class LogIndex(
    val entryCount: Int,
    /** Epoch millis, ascending in practice but not enforced — a merged multi-file view sorts later. */
    val timestamps: LongArray,
    /** [LogLevel] ordinals, or [LogLevel.UNKNOWN_ORDINAL]. */
    val levels: ByteArray,
    /** Ids into [categories], or [NO_VALUE] when the line carried no category. */
    val categoryIds: IntArray,
    /** Ids into [tags]. */
    val tagIds: IntArray,
    val byteOffsets: LongArray,
    val byteLengths: IntArray,
    val categories: ValueDictionary,
    val tags: ValueDictionary,
    /** Entry count per [LogLevel] ordinal, accumulated during the indexing pass. */
    val levelCounts: IntArray,
    val lineCount: Long,
    val continuationLineCount: Long,
    val unparsedLineCount: Long,
) {

    companion object {
        const val NO_VALUE: Int = -1

        private const val BYTES_PER_ENTRY: Int = 8 + 1 + 4 + 4 + 8 + 4 // ts, level, cat, tag, offset, length
    }

    val minTimestampMillis: Long
    val maxTimestampMillis: Long

    init {
        // Deliberately a raw loop: `timestamps.take(n).minOrNull()` would box five million Longs.
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

    /** Share of lines the profile recognised — the "4 812 / 4 815" health indicator of the PRD. */
    val recognisedLineRatio: Double
        get() = if (lineCount == 0L) 1.0 else (lineCount - unparsedLineCount).toDouble() / lineCount

    val estimatedHeapBytes: Long get() = entryCount.toLong() * BYTES_PER_ENTRY

    /** Density per time bucket, per level — the data behind the brushable timeline strip. */
    fun timelineHistogram(bucketCount: Int): Array<IntArray> {
        val buckets: Array<IntArray> = Array(LogLevel.entries.size) { IntArray(bucketCount) }
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
