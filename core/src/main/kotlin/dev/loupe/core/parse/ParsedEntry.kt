package dev.loupe.core.parse

import dev.loupe.core.model.LogLevel

/**
 * Mutable, reused sink for one parsed opening line.
 *
 * Fields are handed back as `[start, end)` offsets **into the caller's buffer**, never as strings:
 * the indexer interns them straight from those bytes ([dev.loupe.core.index.ValueDictionary]).
 * One instance per indexing pass, overwritten per line — never retain it.
 */
class ParsedEntry {

    companion object {
        const val ABSENT: Int = -1
    }

    var timestampMillis: Long = 0L
    var levelOrdinal: Int = LogLevel.UNKNOWN_ORDINAL

    var categoryStart: Int = ABSENT
    var categoryEnd: Int = ABSENT
    var tagStart: Int = ABSENT
    var tagEnd: Int = ABSENT
    var messageStart: Int = ABSENT

    val hasCategory: Boolean get() = categoryStart != ABSENT

    fun reset() {
        timestampMillis = 0L
        levelOrdinal = LogLevel.UNKNOWN_ORDINAL
        categoryStart = ABSENT
        categoryEnd = ABSENT
        tagStart = ABSENT
        tagEnd = ABSENT
        messageStart = ABSENT
    }
}

/**
 * Recognises the *opening line* of an entry and fills a [ParsedEntry].
 *
 * Continuation lines (a stack trace, a wrapped message) are not passed here: the indexer detects
 * them with [isContinuation] and simply extends the current entry's byte range. That keeps the
 * expensive match to once per entry rather than once per line — the single biggest lever on the
 * indexing budget, since ~6 % of HealthMate lines are continuations.
 */
interface EntryParser {

    /** Human-readable name, used by the spike report. */
    val name: String

    /** @return true when the line opened an entry and [sink] was filled. */
    fun parseOpening(buffer: ByteArray, start: Int, end: Int, sink: ParsedEntry): Boolean

    /** @return true when the line continues the entry above it. Must be cheap. */
    fun isContinuation(buffer: ByteArray, start: Int, end: Int): Boolean
}
