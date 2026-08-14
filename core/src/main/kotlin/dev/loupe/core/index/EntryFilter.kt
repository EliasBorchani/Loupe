package dev.loupe.core.index

import dev.loupe.core.io.TextSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.regex.Pattern

/**
 * Which values of one facet an entry may carry.
 *
 * [accepted] is indexed by dictionary id, so the test is an array read rather than a set lookup.
 * Negation is compiled away by inverting the array — the filter never needs to know that
 * `-cat:Ui` was written with a minus.
 */
class FacetConstraint(
    val accepted: BooleanArray,
    /** Whether an entry whose facet group did not match at all passes. `-cat:Ui` says yes. */
    val acceptMissing: Boolean,
)

/**
 * A compiled query, evaluated straight over [LogIndex]'s columns.
 *
 * Predicates are ordered by cost: level and time are an array read, facets are an array read
 * behind an indirection, substring search touches the file, and a regex has to decode the entry
 * first. Each one only ever sees entries the cheaper ones already accepted, which is why
 * narrowing by facet before typing a search term feels instant.
 */
class EntryFilter(
    /** Indexed by severity ordinal. `null` accepts every level. */
    val acceptedLevels: BooleanArray? = null,
    val acceptUnknownLevel: Boolean = true,
    /** Per facet index, positionally aligned with [LogIndex.facetValues]. `null` accepts all. */
    val facetConstraints: Array<FacetConstraint?>? = null,
    val sinceMillis: Long = Long.MIN_VALUE,
    val untilMillis: Long = Long.MAX_VALUE,
    /** Already lowercased by the caller, once per query rather than once per entry. */
    val substringLowercase: ByteArray? = null,
    /** `/…/` search. Decodes each surviving entry, so it runs last and is documented as slow. */
    val regex: Pattern? = null,
) {

    companion object {
        /** Below this, the fan-out costs more than the scan saves. */
        private const val MIN_ENTRIES_FOR_PARALLEL = 100_000

        val ACCEPT_ALL: EntryFilter = EntryFilter()
    }

    /**
     * Writes the indices of matching entries into [destination], in order.
     *
     * @param destination reused across evaluations; must hold [LogIndex.entryCount] ints.
     * @return how many indices were written.
     */
    fun evaluate(index: LogIndex, text: TextSources?, destination: IntArray): Int =
        evaluateRange(index, text, destination, 0, 0, index.entryCount)

    /**
     * Same result as [evaluate], fanned out over the default dispatcher.
     *
     * Each worker scans a contiguous slice and writes into the region of [destination] starting at
     * its own first entry index — which cannot overflow into the next worker's, since a slice never
     * produces more matches than it holds. A compaction pass then closes the gaps, a handful of
     * `arraycopy`s that cost nothing next to the scan.
     *
     * M0 measured full-text search at 659 ms sequential and 116 ms across 18 workers on a 1 GiB
     * corpus, against a 500 ms target: for anything touching the text, this is the required path.
     */
    fun evaluateParallel(
        index: LogIndex,
        text: TextSources?,
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
        text: TextSources?,
        destination: IntArray,
        destinationOffset: Int,
        fromEntry: Int,
        toEntry: Int,
    ): Int {
        var matched = 0
        for (entry in fromEntry until toEntry) {
            if (acceptedLevels != null) {
                val levelOrdinal: Int = index.levels[entry].toInt()
                if (levelOrdinal < 0) {
                    if (!acceptUnknownLevel) continue
                } else if (levelOrdinal >= acceptedLevels.size || !acceptedLevels[levelOrdinal]) {
                    continue
                }
            }

            val timestamp: Long = index.timestamps[entry]
            if (timestamp < sinceMillis || timestamp > untilMillis) continue

            if (facetConstraints != null && !facetsAccept(index, entry)) continue

            if (substringLowercase != null && text != null &&
                !text.containsIgnoreCase(
                    index.fileIdOf(entry), index.byteOffsets[entry], index.byteLengths[entry], substringLowercase,
                )
            ) {
                continue
            }

            if (regex != null && text != null &&
                !regex.matcher(text.decode(index.fileIdOf(entry), index.byteOffsets[entry], index.byteLengths[entry]))
                    .find()
            ) {
                continue
            }

            destination[destinationOffset + matched] = entry
            matched++
        }
        return matched
    }

    private fun facetsAccept(index: LogIndex, entry: Int): Boolean {
        val constraints: Array<FacetConstraint?> = requireNotNull(facetConstraints)
        for (facetIndex in constraints.indices) {
            val constraint: FacetConstraint = constraints[facetIndex] ?: continue
            val valueId: Int = index.facetValues[facetIndex][entry]
            if (valueId == LogIndex.NO_VALUE) {
                if (!constraint.acceptMissing) return false
            } else if (valueId >= constraint.accepted.size || !constraint.accepted[valueId]) {
                return false
            }
        }
        return true
    }
}
