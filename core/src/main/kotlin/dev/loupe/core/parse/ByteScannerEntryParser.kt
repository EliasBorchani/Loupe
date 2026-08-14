package dev.loupe.core.parse

import dev.loupe.core.model.LogLevel

/**
 * Strategy C — hand-rolled byte scanner, no regex, no character decoding at all.
 *
 * The floor of what the format can cost. If the regex strategies land near this, the generic
 * profile-driven engine is viable as designed; if they do not, hot profiles get compiled to a
 * scanner like this one and the declarative regex stays the fallback.
 */
class ByteScannerEntryParser(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) : EntryParser {

    companion object {
        private const val OPEN_BRACKET = '['.code.toByte()
        private const val CLOSE_BRACKET = ']'.code.toByte()
        private const val SPACE = ' '.code.toByte()
        private const val DASH = '-'.code.toByte()
        private const val GREATER_THAN = '>'.code.toByte()
    }

    override val name: String = "C · byte scanner"

    override fun toString(): String = name

    private val timestamps = LocalTimestampResolver(zone)

    override fun parseOpening(buffer: ByteArray, start: Int, end: Int, sink: ParsedEntry): Boolean {
        if (!WithingsFormat.opensEntry(buffer, start, end)) return false

        // ts [L] [
        if (buffer[start + 23] != SPACE ||
            buffer[start + 24] != OPEN_BRACKET ||
            buffer[start + 26] != CLOSE_BRACKET ||
            buffer[start + 27] != SPACE ||
            buffer[start + 28] != OPEN_BRACKET
        ) {
            return false
        }

        val levelOrdinal: Int = LogLevel.ordinalOfSymbolByte(buffer[start + 25])
        if (levelOrdinal == LogLevel.UNKNOWN_ORDINAL) return false

        val firstTokenStart: Int = start + 29
        val firstTokenEnd: Int = indexOfClosingBracket(buffer, firstTokenStart, end)
        if (firstTokenEnd < 0) return false

        // Two bracket groups → the first is the category. Falls through to the single-group form
        // when the arrow check fails, so a message that itself starts with '[' cannot mislead us.
        val afterFirst: Int = firstTokenEnd + 1
        if (afterFirst + 1 < end && buffer[afterFirst] == SPACE && buffer[afterFirst + 1] == OPEN_BRACKET) {
            val secondTokenStart: Int = afterFirst + 2
            val secondTokenEnd: Int = indexOfClosingBracket(buffer, secondTokenStart, end)
            if (secondTokenEnd >= 0 && isArrowAt(buffer, secondTokenEnd + 1, end)) {
                sink.timestampMillis = readTimestamp(buffer, start)
                sink.levelOrdinal = levelOrdinal
                sink.categoryStart = firstTokenStart
                sink.categoryEnd = firstTokenEnd
                sink.tagStart = secondTokenStart
                sink.tagEnd = secondTokenEnd
                sink.messageStart = secondTokenEnd + 1 + WithingsFormat.ARROW_LENGTH
                return true
            }
        }

        if (!isArrowAt(buffer, afterFirst, end)) return false
        sink.timestampMillis = readTimestamp(buffer, start)
        sink.levelOrdinal = levelOrdinal
        sink.categoryStart = ParsedEntry.ABSENT
        sink.categoryEnd = ParsedEntry.ABSENT
        sink.tagStart = firstTokenStart
        sink.tagEnd = firstTokenEnd
        sink.messageStart = afterFirst + WithingsFormat.ARROW_LENGTH
        return true
    }

    override fun isContinuation(buffer: ByteArray, start: Int, end: Int): Boolean =
        WithingsFormat.isContinuationLine(buffer, start, end)

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
    private fun isArrowAt(buffer: ByteArray, offset: Int, end: Int): Boolean =
        offset + WithingsFormat.ARROW_LENGTH <= end &&
            buffer[offset] == SPACE &&
            buffer[offset + 1] == DASH &&
            buffer[offset + 2] == GREATER_THAN &&
            buffer[offset + 3] == SPACE
}
