package dev.loupe.core.index

import dev.loupe.core.io.TextSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * The numbers beside each value in the facet sidebar.
 *
 * The subtlety is *which* number. Counting over the current result set would show every other
 * category at zero the moment one is picked — leaving no way to see what you could switch to. So
 * each facet is counted with **its own constraint lifted**: the count beside `Wpp` is what you
 * would get by clicking it, which is the only reading of that number that helps.
 *
 * That means one pass per facet rather than one pass total. On the 1 GiB corpus that is three
 * passes over columns already in cache, run off the UI thread alongside the filter itself.
 */
object FacetCounts {

    /** Counts per value id for one facet, evaluated as if that facet were unconstrained. */
    fun forFacet(index: LogIndex, filter: EntryFilter, text: TextSources?, facetIndex: Int): IntArray {
        val counts = IntArray(index.facetDictionaries[facetIndex].size)
        val relaxed: EntryFilter = filter.without(facetIndex)
        val values: IntArray = index.facetValues[facetIndex]
        val scratch = IntArray(index.entryCount)
        val matched: Int = relaxed.evaluate(index, text, scratch)
        for (position in 0 until matched) {
            val valueId: Int = values[scratch[position]]
            if (valueId != LogIndex.NO_VALUE) counts[valueId]++
        }
        return counts
    }

    /** Counts per severity ordinal, evaluated as if the level term were absent. */
    fun forLevels(index: LogIndex, filter: EntryFilter, text: TextSources?): IntArray {
        val counts = IntArray(maxOf(index.profile.levelCount, 1))
        val relaxed: EntryFilter = filter.withoutLevels()
        val scratch = IntArray(index.entryCount)
        val matched: Int = relaxed.evaluate(index, text, scratch)
        for (position in 0 until matched) {
            val ordinal: Int = index.levels[scratch[position]].toInt()
            if (ordinal >= 0 && ordinal < counts.size) counts[ordinal]++
        }
        return counts
    }

    /** Every facet plus the levels, computed concurrently — they are independent passes. */
    fun all(index: LogIndex, filter: EntryFilter, text: TextSources?): SidebarCounts = runBlocking(Dispatchers.Default) {
        val levels = async { forLevels(index, filter, text) }
        val facets = index.facets.indices.map { facetIndex -> async { forFacet(index, filter, text, facetIndex) } }
        SidebarCounts(levels.await(), facets.awaitAll())
    }
}

class SidebarCounts(val levels: IntArray, val facets: List<IntArray>)

/** @return the same filter with [facetIndex]'s constraint dropped. */
private fun EntryFilter.without(facetIndex: Int): EntryFilter {
    val constraints: Array<FacetConstraint?> = facetConstraints ?: return this
    if (constraints[facetIndex] == null) return this
    val relaxed: Array<FacetConstraint?> = constraints.copyOf()
    relaxed[facetIndex] = null
    return copyWith(facetConstraints = relaxed)
}

private fun EntryFilter.withoutLevels(): EntryFilter =
    if (acceptedLevels == null) this else copyWith(acceptedLevels = null, acceptUnknownLevel = true)

private fun EntryFilter.copyWith(
    acceptedLevels: BooleanArray? = this.acceptedLevels,
    acceptUnknownLevel: Boolean = this.acceptUnknownLevel,
    facetConstraints: Array<FacetConstraint?>? = this.facetConstraints,
): EntryFilter = EntryFilter(
    acceptedLevels = acceptedLevels,
    acceptUnknownLevel = acceptUnknownLevel,
    facetConstraints = facetConstraints,
    sinceMillis = sinceMillis,
    untilMillis = untilMillis,
    substringLowercase = substringLowercase,
    regex = regex,
)
