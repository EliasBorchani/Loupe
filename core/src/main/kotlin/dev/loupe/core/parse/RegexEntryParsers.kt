package dev.loupe.core.parse

import dev.loupe.core.model.LogLevel
import java.time.ZoneId
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * The profile regex, in the shape a declarative `*.logprofile.toml` would compile to.
 *
 * `[^\]]*` cannot cross a `]`, so the first ` -> ` always wins and a message containing its own
 * arrow (`steps: 100 -> 250`) parses correctly. The optional category group resolves itself by
 * backtracking: on `[E] [ERROR] -> …` it grabs `ERROR`, fails to find a following `[tag]`, gives
 * the group up, and `ERROR` lands in the tag slot — which is exactly where `report()` puts it.
 *
 * `DOTALL` is not cosmetic. Java counts U+0085 (NEL) as a line terminator, so a bare `.` refuses
 * to match it — and strategy B widens raw bytes to chars, which turns the UTF-8 continuation
 * byte 0x85 into exactly that. Accented messages would silently fail to parse without it.
 */
private val OPENING_LINE_PATTERN: Pattern = Pattern.compile(
    """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([VDIWE])\] (?:\[([^\]]*)\] )?\[([^\]]*)\] -> (.*)$""",
    Pattern.DOTALL,
)

private const val GROUP_TIMESTAMP = 1
private const val GROUP_LEVEL = 2
private const val GROUP_CATEGORY = 3
private const val GROUP_TAG = 4

/** Reads the seven timestamp fields out of a fixed-width `yyyy-MM-dd HH:mm:ss.SSS` at [offset]. */
private fun LocalTimestampResolver.resolveFrom(chars: CharSequence, offset: Int): Long = resolve(
    year = WithingsFormat.digitsFromChars(chars, offset, 4),
    month = WithingsFormat.digitsFromChars(chars, offset + 5, 2),
    day = WithingsFormat.digitsFromChars(chars, offset + 8, 2),
    hour = WithingsFormat.digitsFromChars(chars, offset + 11, 2),
    minute = WithingsFormat.digitsFromChars(chars, offset + 14, 2),
    second = WithingsFormat.digitsFromChars(chars, offset + 17, 2),
    milli = WithingsFormat.digitsFromChars(chars, offset + 20, 3),
)

/**
 * Strategy A — the obvious implementation: build a `String` per line, run the `Pattern` on it.
 *
 * This is what a straight port of `LogViewerViewModel` would do, and the baseline the other two
 * have to beat.
 */
class StringRegexEntryParser(zone: ZoneId = ZoneId.systemDefault()) : EntryParser {

    override val name: String = "A · String + Pattern"

    override fun toString(): String = name

    private val timestamps = LocalTimestampResolver(zone)
    private val matcher: Matcher = OPENING_LINE_PATTERN.matcher("")

    override fun parseOpening(buffer: ByteArray, start: Int, end: Int, sink: ParsedEntry): Boolean {
        if (!WithingsFormat.opensEntry(buffer, start, end)) return false

        val line = String(buffer, start, end - start, Charsets.UTF_8)
        matcher.reset(line)
        if (!matcher.matches()) return false
        return fill(sink, matcher, line, start, timestamps)
    }

    override fun isContinuation(buffer: ByteArray, start: Int, end: Int): Boolean =
        WithingsFormat.isContinuationLine(buffer, start, end)
}

/**
 * Strategy B — same `Pattern`, but matched against a reusable char buffer instead of a `String`.
 *
 * Bytes are widened to chars one-for-one, so no decoder runs and nothing is allocated per line.
 * A non-ASCII message turns into mojibake *in the char buffer* — harmless, because every field we
 * index is ASCII by construction and the message is taken from byte offsets, never from these
 * chars. Char index and byte offset therefore coincide exactly, which is what lets the interning
 * step read the category and tag straight out of the original buffer.
 */
class WidenedCharRegexEntryParser(zone: ZoneId = ZoneId.systemDefault()) : EntryParser {

    override val name: String = "B · widened chars + Pattern"

    override fun toString(): String = name

    private val timestamps = LocalTimestampResolver(zone)
    private val chars = ResizableCharSequence()
    private val matcher: Matcher = OPENING_LINE_PATTERN.matcher(chars)

    override fun parseOpening(buffer: ByteArray, start: Int, end: Int, sink: ParsedEntry): Boolean {
        if (!WithingsFormat.opensEntry(buffer, start, end)) return false

        chars.loadWidened(buffer, start, end)
        matcher.reset(chars)
        if (!matcher.matches()) return false
        return fill(sink, matcher, chars, start, timestamps)
    }

    override fun isContinuation(buffer: ByteArray, start: Int, end: Int): Boolean =
        WithingsFormat.isContinuationLine(buffer, start, end)
}

/** Shared group extraction — identical for A and B, so the benchmark isolates the match itself. */
private fun fill(
    sink: ParsedEntry,
    matcher: Matcher,
    chars: CharSequence,
    bufferStart: Int,
    timestamps: LocalTimestampResolver,
): Boolean {
    val levelOrdinal: Int = LogLevel.ordinalOfSymbolByte(chars[matcher.start(GROUP_LEVEL)].code.toByte())
    if (levelOrdinal == LogLevel.UNKNOWN_ORDINAL) return false

    sink.timestampMillis = timestamps.resolveFrom(chars, matcher.start(GROUP_TIMESTAMP))
    sink.levelOrdinal = levelOrdinal

    val categoryStart: Int = matcher.start(GROUP_CATEGORY)
    if (categoryStart >= 0) {
        sink.categoryStart = bufferStart + categoryStart
        sink.categoryEnd = bufferStart + matcher.end(GROUP_CATEGORY)
    } else {
        sink.categoryStart = ParsedEntry.ABSENT
        sink.categoryEnd = ParsedEntry.ABSENT
    }
    sink.tagStart = bufferStart + matcher.start(GROUP_TAG)
    sink.tagEnd = bufferStart + matcher.end(GROUP_TAG)
    sink.messageStart = sink.tagEnd + 1 + WithingsFormat.ARROW_LENGTH
    return true
}

/** A `CharSequence` view over a reusable `char[]`, so `Matcher` never sees a fresh object. */
private class ResizableCharSequence : CharSequence {

    override var length: Int = 0
        private set

    private var chars: CharArray = CharArray(512)

    fun loadWidened(buffer: ByteArray, start: Int, end: Int) {
        val required: Int = end - start
        if (required > chars.size) chars = CharArray(Integer.highestOneBit(required - 1) shl 1)
        for (offset in 0 until required) {
            chars[offset] = (buffer[start + offset].toInt() and 0xFF).toChar()
        }
        length = required
    }

    override fun get(index: Int): Char = chars[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        String(chars, startIndex, endIndex - startIndex)

    override fun toString(): String = String(chars, 0, length)
}
