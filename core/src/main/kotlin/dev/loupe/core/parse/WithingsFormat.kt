package dev.loupe.core.parse

/**
 * Layout constants of the HealthMate `FileLogger` line, and the digit readers the three parser
 * strategies share so the benchmark compares matching, not arithmetic.
 *
 * Reference (`util/utilslegacy/.../FileLogger.kt`, `LineFormat.render`):
 * ```
 * yyyy-MM-dd HH:mm:ss.SSS [L] [Category]? [tag] -> message
 * ```
 * Continuation lines are the message's own newlines re-indented by `timestamp.length` spaces.
 */
object WithingsFormat {

    /** `yyyy-MM-dd HH:mm:ss.SSS` — 23 characters, and therefore the continuation indent. */
    const val TIMESTAMP_LENGTH: Int = 23

    /** `ts [L] [x] -> ` — the shortest possible opening line has a tag and an empty message. */
    const val MIN_OPENING_LENGTH: Int = TIMESTAMP_LENGTH + 10

    const val ARROW_LENGTH: Int = 4 // " -> "

    private const val SPACE = ' '.code.toByte()
    private const val ASCII_ZERO = '0'.code

    /**
     * Cheap structural pre-filter: does this line even look like it opens an entry?
     *
     * Runs before the regex in every strategy. On a real HealthMate file it rejects every
     * continuation line in a handful of byte comparisons, which is what keeps the regex cost
     * proportional to entries rather than lines.
     */
    fun opensEntry(buffer: ByteArray, start: Int, end: Int): Boolean {
        if (end - start < MIN_OPENING_LENGTH) return false
        return buffer[start + 4] == '-'.code.toByte() &&
            buffer[start + 7] == '-'.code.toByte() &&
            buffer[start + 10] == SPACE &&
            buffer[start + 13] == ':'.code.toByte() &&
            buffer[start + 16] == ':'.code.toByte() &&
            buffer[start + 19] == '.'.code.toByte()
    }

    /** A continuation line is the [TIMESTAMP_LENGTH]-space indent `render` writes. */
    fun isContinuationLine(buffer: ByteArray, start: Int, end: Int): Boolean {
        if (end - start < TIMESTAMP_LENGTH) return false
        for (offset in 0 until TIMESTAMP_LENGTH) {
            if (buffer[start + offset] != SPACE) return false
        }
        return true
    }

    fun digitsFromBytes(buffer: ByteArray, offset: Int, count: Int): Int {
        var value = 0
        for (index in offset until offset + count) {
            value = value * 10 + (buffer[index].toInt() - ASCII_ZERO)
        }
        return value
    }

    fun digitsFromChars(chars: CharSequence, offset: Int, count: Int): Int {
        var value = 0
        for (index in offset until offset + count) {
            value = value * 10 + (chars[index].code - ASCII_ZERO)
        }
        return value
    }
}
