package dev.loupe.core.parse

import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.LevelDecoder

/**
 * Mutable, reused sink for one parsed opening line.
 *
 * Facet values are handed back as offsets rather than strings so the indexer can intern them
 * without allocating. Which coordinate space those offsets are in depends on the parser and is
 * declared by [facetsAreCharOffsets]: a regex parser has already decoded the line and reports
 * offsets into that `String`, while a hand-written scanner works on the read buffer and reports
 * byte offsets. Conflating the two would slice a facet in the wrong place on any line containing
 * a multi-byte character.
 *
 * One instance per indexing pass, overwritten per line — never retain it.
 */
class ParsedEntry(facetCount: Int, val facetsAreCharOffsets: Boolean) {

    companion object {
        const val ABSENT: Int = -1
    }

    var timestampMillis: Long = 0L
    var levelOrdinal: Int = LevelDecoder.UNKNOWN_ORDINAL

    /** Start of each facet's value; [ABSENT] when the group did not participate in the match. */
    val facetStarts: IntArray = IntArray(facetCount) { ABSENT }
    val facetEnds: IntArray = IntArray(facetCount) { ABSENT }

    /** The decoded line, set by parsers that report char offsets. */
    var line: CharSequence = ""

    fun hasFacet(facetIndex: Int): Boolean = facetStarts[facetIndex] != ABSENT
}

/**
 * Recognises the *opening line* of an entry and fills a [ParsedEntry].
 *
 * Continuation lines are never passed here: the indexer tests [isContinuation] first and extends
 * the current entry's byte range instead. That keeps the expensive match to once per entry rather
 * than once per line — the single biggest lever on the indexing budget, since M0 measured 18.6 %
 * of lines in a real HealthMate file as continuations.
 */
interface EntryParser {

    /** Human-readable name, used by reports and benchmarks. */
    val name: String

    /** The format this parser implements. Drives the index's facet columns and level scale. */
    val profile: CompiledProfile

    /** A sink shaped for this parser — in particular, in its offset space. */
    fun newSink(): ParsedEntry

    /** @return true when the line opened an entry and [sink] was filled. */
    fun parseOpening(buffer: ByteArray, start: Int, end: Int, sink: ParsedEntry): Boolean

    /** @return true when the line continues the entry above it. Must be cheap. */
    fun isContinuation(buffer: ByteArray, start: Int, end: Int): Boolean
}
