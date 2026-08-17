package dev.loupe.core.index

import dev.loupe.core.io.TextSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * The numbers beside each value in the facet sidebar, and the shape the timeline draws.
 *
 * The subtlety is *which* number. Counting over the current result set would show every other
 * category at zero the moment one is picked — leaving no way to see what you could switch to. So
 * each facet is counted with **its own constraint lifted**: the count beside `Wpp` is what you
 * would get by clicking it, which is the only reading of that number that helps.
 *
 * The timeline follows the same rule for the same reason, which is why [timeline] lifts the time
 * window: a strip that empties out where you just brushed shows you the answer instead of the
 * question.
 *
 * One pass per facet, run concurrently, and none of them materialises a match array — at nine
 * million entries a scratch `IntArray` per facet per keystroke would be over a hundred megabytes
 * of garbage for a handful of counts.
 */
object FacetCounts {

    /** Counts per value id for one facet, evaluated as if that facet were unconstrained. */
    fun forFacet(index: LogIndex, filter: EntryFilter, text: TextSources?, facetIndex: Int): IntArray {
        val counts = IntArray(index.facetDictionaries[facetIndex].size)
        val relaxed: EntryFilter = filter.withoutFacet(facetIndex)
        val values: IntArray = index.facetValues[facetIndex]
        for (entry in 0 until index.entryCount) {
            if (!relaxed.accepts(index, text, entry)) continue
            val valueId: Int = values[entry]
            if (valueId != LogIndex.NO_VALUE) counts[valueId]++
        }
        return counts
    }

    /** Counts per severity ordinal, evaluated as if the level term were absent. */
    fun forLevels(index: LogIndex, filter: EntryFilter, text: TextSources?): IntArray {
        val counts = IntArray(maxOf(index.profile.levelCount, 1))
        val relaxed: EntryFilter = filter.withoutLevels()
        for (entry in 0 until index.entryCount) {
            if (!relaxed.accepts(index, text, entry)) continue
            val ordinal: Int = index.levels[entry].toInt()
            if (ordinal >= 0 && ordinal < counts.size) counts[ordinal]++
        }
        return counts
    }

    /** Density per bucket per severity, evaluated as if the time window were absent. */
    fun timeline(index: LogIndex, filter: EntryFilter, text: TextSources?, bucketCount: Int): Array<IntArray> {
        val buckets: Array<IntArray> = Array(maxOf(index.profile.levelCount, 1)) { IntArray(bucketCount) }
        val span: Long = index.maxTimestampMillis - index.minTimestampMillis
        if (span <= 0L) return buckets
        val relaxed: EntryFilter = filter.withoutTimeWindow()
        for (entry in 0 until index.entryCount) {
            if (!relaxed.accepts(index, text, entry)) continue
            val ordinal: Int = index.levels[entry].toInt()
            if (ordinal < 0 || ordinal >= buckets.size) continue
            val bucket: Int = (((index.timestamps[entry] - index.minTimestampMillis) * bucketCount) / span)
                .toInt()
                .coerceAtMost(bucketCount - 1)
            buckets[ordinal][bucket]++
        }
        return buckets
    }

    /** Everything the sidebar and the strip need, computed concurrently — independent passes. */
    fun all(index: LogIndex, filter: EntryFilter, text: TextSources?, timelineBuckets: Int): SidebarCounts =
        runBlocking(Dispatchers.Default) {
            val levels = async { forLevels(index, filter, text) }
            val timeline = async { timeline(index, filter, text, timelineBuckets) }
            val facets = index.facets.indices.map { facetIndex -> async { forFacet(index, filter, text, facetIndex) } }
            SidebarCounts(levels.await(), facets.awaitAll(), timeline.await())
        }
}

class SidebarCounts(
    val levels: IntArray,
    val facets: List<IntArray>,
    /** `[severityOrdinal][bucket]`, over the whole file's time span. */
    val timeline: Array<IntArray>,
)
