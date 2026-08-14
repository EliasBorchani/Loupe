package dev.loupe.core.index

import dev.loupe.core.io.MappedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * A compiled query, evaluated straight over [LogIndex]'s columns.
 *
 * Cheap column predicates run first and the full-text scan last, so the substring search only ever
 * touches entries that already passed every facet — which is why narrowing by category before
 * typing a search term feels instant.
 */
class EntryFilter(
    /** Keep entries at or above this [dev.loupe.core.model.LogLevel] ordinal. `-1` accepts all. */
    val minLevelOrdinal: Int = -1,
    /** Accepted category ids, indexed by id. `null` accepts all — including entries with none. */
    val acceptedCategories: BooleanArray? = null,
    val sinceMillis: Long = Long.MIN_VALUE,
    val untilMillis: Long = Long.MAX_VALUE,
    /** Already lowercased by the caller, once per query rather than once per entry. */
    val substringLowercase: ByteArray? = null,
) {

    companion object {
        /** Below this, the fan-out costs more than the scan saves. */
        private const val MIN_ENTRIES_FOR_PARALLEL = 100_000
    }

    /**
     * Writes the indices of matching entries into [destination], in order.
     *
     * @param destination reused across evaluations; must hold [LogIndex.entryCount] ints.
     * @return how many indices were written.
     */
    fun evaluate(index: LogIndex, text: MappedText?, destination: IntArray): Int =
        evaluateRange(index, text, destination, 0, 0, index.entryCount)

    /**
     * Same result as [evaluate], fanned out over the default dispatcher.
     *
     * Each worker scans a contiguous slice and writes into its own region of [destination] — the
     * region starting at its own first entry index, which cannot overflow into the next worker's
     * since a slice never produces more matches than it holds. A compaction pass then closes the
     * gaps, which is a handful of `arraycopy`s and costs nothing next to the scan.
     */
    fun evaluateParallel(
        index: LogIndex,
        text: MappedText?,
        destination: IntArray,
        workerCount: Int = Runtime.getRuntime().availableProcessors(),
    ): Int {
        if (index.entryCount < MIN_ENTRIES_FOR_PARALLEL || workerCount <= 1) {
            return evaluate(index, text, destination)
        }

        val sliceSize: Int = (index.entryCount + workerCount - 1) / workerCount
        val matchesPerWorker: List<Int> = runBlocking(Dispatchers.Default) {
            (0 until workerCount).map { worker ->
                async {
                    val fromEntry: Int = worker * sliceSize
                    val toEntry: Int = minOf(fromEntry + sliceSize, index.entryCount)
                    if (fromEntry >= toEntry) 0 else evaluateRange(index, text, destination, fromEntry, fromEntry, toEntry)
                }
            }.awaitAll()
        }

        var writeCursor = 0
        matchesPerWorker.forEachIndexed { worker, matched ->
            val sliceStart: Int = worker * sliceSize
            if (matched > 0 && sliceStart != writeCursor) {
                System.arraycopy(destination, sliceStart, destination, writeCursor, matched)
            }
            writeCursor += matched
        }
        return writeCursor
    }

    /** The one hot loop. Everything above is scheduling. */
    private fun evaluateRange(
        index: LogIndex,
        text: MappedText?,
        destination: IntArray,
        destinationOffset: Int,
        fromEntry: Int,
        toEntry: Int,
    ): Int {
        var matched = 0
        for (entry in fromEntry until toEntry) {
            if (minLevelOrdinal >= 0 && index.levels[entry] < minLevelOrdinal) continue

            val timestamp: Long = index.timestamps[entry]
            if (timestamp < sinceMillis || timestamp > untilMillis) continue

            if (acceptedCategories != null) {
                val categoryId: Int = index.categoryIds[entry]
                if (categoryId == LogIndex.NO_VALUE || !acceptedCategories[categoryId]) continue
            }

            if (substringLowercase != null && text != null &&
                !text.containsIgnoreCase(index.byteOffsets[entry], index.byteLengths[entry], substringLowercase)
            ) {
                continue
            }

            destination[destinationOffset + matched] = entry
            matched++
        }
        return matched
    }
}
