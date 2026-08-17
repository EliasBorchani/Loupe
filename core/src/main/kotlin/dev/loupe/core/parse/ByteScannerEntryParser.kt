package dev.loupe.core.parse

import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.LevelDecoder
import dev.loupe.core.profile.LocalTimestampResolver

/**
 * A hand-written scanner for the HealthMate format: no regex, no character decoding at all.
 *
 * This is what a *compiled* profile would look like, and it exists for two reasons. It is the
 * benchmark floor — M0 measured 157 ns/entry against [ProfileEntryParser]'s 386, so the price of
 * staying declarative is a known factor of 2.4, not a guess. And it is an independent second
 * opinion: the test suite runs every case through both and requires an identical index, which
 * catches a regex that quietly means something other than what it looks like.
 *
 * It is deliberately *not* wired into normal use. If a profile ever becomes a measured hot spot,
 * this is the shape its compiled form takes.
 *
 * Reads facet 0 as the optional category and facet 1 as the tag, matching the order the HealthMate
 * profile declares them in — checked at construction rather than assumed.
 */
class ByteScannerEntryParser(override val profile: CompiledProfile) : EntryParser {

    companion object {
        private const val OPEN_BRACKET = '['.code.toByte()
        private const val CLOSE_BRACKET = ']'.code.toByte()
        private const val SPACE = ' '.code.toByte()
        private const val DASH = '-'.code.toByte()
        private const val GREATER_THAN = '>'.code.toByte()

        private const val CATEGORY_FACET = 0
        private const val TAG_FACET = 1
    }

    override val name: String = "byte-scanner:${profile.name}"

    private val timestamps: LocalTimestampResolver = profile.timestampFormat.newResolver()
    private val levelDecoder: LevelDecoder = requireNotNull(profile.levelDecoder) {
        "The byte scanner implements the HealthMate format, which has a level scale"
    }

    init {
        require(profile.facets.map { facet -> facet.name } == listOf("category", "tag")) {
            "The byte scanner is hand-written for the HealthMate profile's facet order " +
                "(category, tag); got ${profile.facets.map { facet -> facet.name }}"
        }
    }

    override fun newSink(): ParsedEntry = ParsedEntry(profile.facets.size, facetsAreCharOffsets = false)

    override fun parseOpening(buffer: ByteArray, start: Int, end: Int, sink: ParsedEntry): Boolean {
        if (!WithingsFormat.opensEntry(buffer, start, end)) return false

        // ts [L] [ — offsets counted off the timestamp rather than written out, so they follow it.
        val afterTimestamp: Int = start + WithingsFormat.TIMESTAMP_LENGTH
        if (buffer[afterTimestamp] != SPACE ||
            buffer[afterTimestamp + 1] != OPEN_BRACKET ||
            buffer[afterTimestamp + 3] != CLOSE_BRACKET ||
            buffer[afterTimestamp + 4] != SPACE ||
            buffer[afterTimestamp + 5] != OPEN_BRACKET
        ) {
            return false
        }

        val levelOrdinal: Int = levelDecoder.ordinalOfSingleByte(buffer[afterTimestamp + 2])
        if (levelOrdinal == LevelDecoder.UNKNOWN_ORDINAL) return false

        val firstTokenStart: Int = afterTimestamp + 6
        val firstTokenEnd: Int = indexOfClosingBracket(buffer, firstTokenStart, end)
        if (firstTokenEnd < 0) return false

        // Two bracket groups → the first is the category. Falls through to the single-group form
        // when the arrow check fails, so a message that itself starts with '[' cannot mislead us.
        val afterFirst: Int = firstTokenEnd + 1
        if (afterFirst + 1 < end && buffer[afterFirst] == SPACE && buffer[afterFirst + 1] == OPEN_BRACKET) {
            val secondTokenStart: Int = afterFirst + 2
            val secondTokenEnd: Int = indexOfClosingBracket(buffer, secondTokenStart, end)
            if (secondTokenEnd >= 0 && isArrowAt(buffer, secondTokenEnd + 1, end)) {
                fill(sink, buffer, start, levelOrdinal, firstTokenStart, firstTokenEnd, secondTokenStart, secondTokenEnd)
                return true
            }
        }

        if (!isArrowAt(buffer, afterFirst, end)) return false
        fill(sink, buffer, start, levelOrdinal, ParsedEntry.ABSENT, ParsedEntry.ABSENT, firstTokenStart, firstTokenEnd)
        return true
    }

    override fun isContinuation(buffer: ByteArray, start: Int, end: Int): Boolean = WithingsFormat.isContinuationLine(buffer, start, end)

    private fun fill(
        sink: ParsedEntry,
        buffer: ByteArray,
        start: Int,
        levelOrdinal: Int,
        categoryStart: Int,
        categoryEnd: Int,
        tagStart: Int,
        tagEnd: Int,
    ) {
        sink.timestampMillis = readTimestamp(buffer, start)
        sink.levelOrdinal = levelOrdinal
        sink.facetStarts[CATEGORY_FACET] = categoryStart
        sink.facetEnds[CATEGORY_FACET] = categoryEnd
        sink.facetStarts[TAG_FACET] = tagStart
        sink.facetEnds[TAG_FACET] = tagEnd
    }

    private fun readTimestamp(buffer: ByteArray, start: Int): Long = timestamps.resolve(
        year = WithingsFormat.digitsFromBytes(buffer, start, 4),
        month = WithingsFormat.digitsFromBytes(buffer, start + 5, 2),
        day = WithingsFormat.digitsFromBytes(buffer, start + 8, 2),
        hour = WithingsFormat.digitsFromBytes(buffer, start + 11, 2),
        minute = WithingsFormat.digitsFromBytes(buffer, start + 14, 2),
        second = WithingsFormat.digitsFromBytes(buffer, start + 17, 2),
        milli = WithingsFormat.digitsFromBytes(buffer, start + 20, 3),
    )

    private fun indexOfClosingBracket(buffer: ByteArray, from: Int, end: Int): Int {
        for (index in from until end) {
            if (buffer[index] == CLOSE_BRACKET) return index
        }
        return -1
    }

    /** Matches `" -> "`, the separator `render` writes between the header and the body. */
    private fun isArrowAt(buffer: ByteArray, offset: Int, end: Int): Boolean = offset + WithingsFormat.ARROW_LENGTH <= end &&
        buffer[offset] == SPACE &&
        buffer[offset + 1] == DASH &&
        buffer[offset + 2] == GREATER_THAN &&
        buffer[offset + 3] == SPACE
}
