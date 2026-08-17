package dev.loupe.core.profile

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Reads a timestamp out of a captured group, as fast as the pattern allows.
 *
 * `DateTimeFormatter.parse` costs roughly a microsecond. At five million entries that is five
 * seconds — the entire indexing budget, spent on dates. So the common case gets its own path:
 * a pattern made only of fixed-width numeric fields (`yyyy-MM-dd HH:mm:ss.SSS` and its cousins)
 * compiles to a list of `(offset, width)` slots, and reading it is digit arithmetic plus the
 * hour-granularity zone cache in [LocalTimestampResolver].
 *
 * Anything the fast path cannot express — a named month, an explicit offset, a variable-width
 * field — falls back to `DateTimeFormatter`. Correct, and slow enough that [isFastPath] exists so
 * the profile loader can say so out loud.
 */
class TimestampFormat private constructor(
    val pattern: String,
    val isFastPath: Boolean,
    /** True when the format carries no year and one had to be assumed — logcat and syslog do this. */
    val assumesYear: Boolean,
    private val assumedYear: Int,
    private val zone: ZoneId,
    private val slots: List<Slot>,
    private val fallbackFormatter: DateTimeFormatter?,
) {

    companion object {
        /**
         * Pattern letters the fast path understands, with the field and the **exact** run width
         * each must have.
         *
         * The width is not a formality. `MMM` is a *named* month (`Jul`), not a three-digit one,
         * and a bare `M` is variable-width — neither can be read at a fixed offset. Accepting any
         * width here made `dd MMM yyyy` parse "Jul" as month 3350.
         */
        private val FAST_PATH_LETTERS: Map<Char, FastPathLetter> = mapOf(
            'y' to FastPathLetter(SlotKind.Year, 4..4),
            'M' to FastPathLetter(SlotKind.Month, 2..2),
            'd' to FastPathLetter(SlotKind.Day, 2..2),
            'H' to FastPathLetter(SlotKind.Hour, 2..2),
            'm' to FastPathLetter(SlotKind.Minute, 2..2),
            's' to FastPathLetter(SlotKind.Second, 2..2),
            // A fractional second may be written to any precision; it is scaled on read.
            'S' to FastPathLetter(SlotKind.Milli, 1..9),
        )

        /**
         * @param zoneSpec `local`, `utc`, or a zone id such as `Europe/Paris`.
         * @param assumeYear the year to use when the format carries none — logcat's `MM-dd` and
         *   syslog's `MMM d` both do. Defaults to the current one, which is what every other
         *   logcat viewer assumes and the only guess available.
         */
        fun compile(pattern: String, zoneSpec: String?, assumeYear: Int? = null): TimestampFormat {
            val zone: ZoneId = resolveZone(zoneSpec)
            val year: Int = assumeYear ?: java.time.Year.now(zone).value
            val slots: List<Slot>? = compileFastPathSlots(pattern)
            val hasYear: Boolean = slots?.any { slot -> slot.kind == SlotKind.Year }
                ?: patternMentionsYear(pattern)

            return if (slots != null) {
                TimestampFormat(
                    pattern = pattern,
                    isFastPath = true,
                    assumesYear = !hasYear,
                    assumedYear = year,
                    zone = zone,
                    slots = slots,
                    fallbackFormatter = null,
                )
            } else {
                TimestampFormat(
                    pattern = pattern,
                    isFastPath = false,
                    assumesYear = !hasYear,
                    assumedYear = year,
                    zone = zone,
                    slots = emptyList(),
                    // Locale.ROOT, not the machine's: a log file is written by a program, so a
                    // named month in it is "Jul", never "juil." — and the reader's locale has no
                    // business deciding whether a file parses.
                    //
                    // parseDefaulting supplies the year a yearless format cannot: without it,
                    // building a LocalDateTime from the parsed fields simply throws.
                    fallbackFormatter = DateTimeFormatterBuilder()
                        .appendPattern(pattern)
                        .parseDefaulting(ChronoField.YEAR, year.toLong())
                        .toFormatter(java.util.Locale.ROOT),
                )
            }
        }

        /** `y` outside a quoted literal. Used only when the fast path declined the pattern. */
        private fun patternMentionsYear(pattern: String): Boolean {
            var index = 0
            var quoted = false
            while (index < pattern.length) {
                val character: Char = pattern[index]
                if (character == '\'') quoted = !quoted
                else if (!quoted && character == 'y') return true
                index++
            }
            return false
        }

        private fun resolveZone(zoneSpec: String?): ZoneId = when (zoneSpec?.lowercase()) {
            null, "local" -> ZoneId.systemDefault()
            "utc" -> ZoneOffset.UTC
            else -> ZoneId.of(zoneSpec)
        }

        /**
         * @return one slot per numeric field, or `null` if the pattern needs the fallback.
         *   A run of identical letters is one field whose width is the run length; everything else
         *   is a literal the fast path skips rather than verifies (the regex that captured the
         *   group already proved the shape).
         *
         *   Slot offsets are counted in **output** characters, not pattern characters: a quoted
         *   literal such as the `'T'` of `yyyy-MM-dd'T'HH:mm:ss` is three characters of pattern
         *   and one of text. Getting that wrong would silently misread every ISO-8601 timestamp.
         */
        private fun compileFastPathSlots(pattern: String): List<Slot>? {
            val slots: MutableList<Slot> = mutableListOf()
            val seenKinds: MutableSet<SlotKind> = mutableSetOf()
            var patternIndex = 0
            var textOffset = 0
            while (patternIndex < pattern.length) {
                val character: Char = pattern[patternIndex]
                when {
                    character == '\'' -> {
                        val consumed: QuotedLiteral = readQuotedLiteral(pattern, patternIndex) ?: return null
                        patternIndex += consumed.patternLength
                        textOffset += consumed.textLength
                    }

                    character.isLetter() -> {
                        val letter: FastPathLetter = FAST_PATH_LETTERS[character] ?: return null
                        var width = 0
                        while (patternIndex + width < pattern.length && pattern[patternIndex + width] == character) width++
                        if (width !in letter.widths) return null
                        if (!seenKinds.add(letter.kind)) return null
                        slots.add(Slot(offset = textOffset, width = width, kind = letter.kind))
                        patternIndex += width
                        textOffset += width
                    }

                    else -> {
                        patternIndex++
                        textOffset++
                    }
                }
            }
            // A yearless format is fine — logcat and syslog both write one — but a pattern with no
            // numeric field at all is not a timestamp.
            return slots.takeIf { compiled -> compiled.isNotEmpty() }
        }

        /** `'T'` → 3 pattern chars, 1 text char. `''` → an escaped quote: 2 and 1. */
        private fun readQuotedLiteral(pattern: String, start: Int): QuotedLiteral? {
            if (start + 1 < pattern.length && pattern[start + 1] == '\'') return QuotedLiteral(2, 1)
            val closing: Int = pattern.indexOf('\'', start + 1)
            if (closing < 0) return null
            return QuotedLiteral(patternLength = closing - start + 1, textLength = closing - start - 1)
        }
    }

    /** Widest offset the reader touches — the group must be at least this long. */
    val minimumLength: Int = slots.maxOfOrNull { slot -> slot.offset + slot.width } ?: 0

    /** One cache per parser: it is a mutable single-slot cache and must not cross threads. */
    fun newResolver(): LocalTimestampResolver = LocalTimestampResolver(zone)

    /**
     * @param chars the whole line; [offset] and [end] delimit the captured timestamp group.
     * @param resolver per-parser cache, not shared between threads.
     */
    fun read(chars: CharSequence, offset: Int, end: Int, resolver: LocalTimestampResolver): Long {
        if (!isFastPath) return readWithFormatter(chars, offset, end)

        var year: Int = assumedYear
        var month = 1
        var day = 1
        var hour = 0
        var minute = 0
        var second = 0
        var milli = 0
        val available: Int = end - offset
        slots.forEach { slot ->
            // A group shorter than the layout is not corruption, it is an optional tail: a pattern
            // with `(?:\.\d{3})?` captures 19 characters when the milliseconds are absent. Reading
            // the slot anyway would pull whatever follows in the line and call it a number.
            if (slot.offset + slot.width > available) return@forEach
            val value: Int = readDigits(chars, offset + slot.offset, slot.width)
            when (slot.kind) {
                SlotKind.Year -> year = value
                SlotKind.Month -> month = value
                SlotKind.Day -> day = value
                SlotKind.Hour -> hour = value
                SlotKind.Minute -> minute = value
                SlotKind.Second -> second = value
                // `S` is fractional: `SS` means hundredths, `SSSSSS` microseconds.
                SlotKind.Milli -> milli = scaleFractionToMillis(value, slot.width)
            }
        }
        return resolver.resolve(year, month, day, hour, minute, second, milli)
    }

    /** Slow path. Handles an explicit offset in the text, and falls back to [zone] when there is none. */
    private fun readWithFormatter(chars: CharSequence, offset: Int, end: Int): Long {
        val formatter: DateTimeFormatter = requireNotNull(fallbackFormatter) { "no fallback formatter for '$pattern'" }
        val parsed: java.time.temporal.TemporalAccessor = formatter.parse(chars.subSequence(offset, end))
        return if (parsed.isSupported(ChronoField.INSTANT_SECONDS)) {
            java.time.Instant.from(parsed).toEpochMilli()
        } else {
            java.time.LocalDateTime.from(parsed).atZone(zone).toInstant().toEpochMilli()
        }
    }

    private fun readDigits(chars: CharSequence, offset: Int, width: Int): Int {
        var value = 0
        for (index in offset until offset + width) {
            value = value * 10 + (chars[index].code - '0'.code)
        }
        return value
    }

    private fun scaleFractionToMillis(value: Int, width: Int): Int = when {
        width == 3 -> value
        width < 3 -> value * POWERS_OF_TEN[3 - width]
        else -> value / POWERS_OF_TEN[width - 3]
    }

    private class Slot(val offset: Int, val width: Int, val kind: SlotKind)

    private class QuotedLiteral(val patternLength: Int, val textLength: Int)

    private class FastPathLetter(val kind: SlotKind, val widths: IntRange)

    private enum class SlotKind { Year, Month, Day, Hour, Minute, Second, Milli }
}

private val POWERS_OF_TEN: IntArray = IntArray(10).also { powers ->
    var value = 1
    for (exponent in powers.indices) {
        powers[exponent] = value
        value *= 10
    }
}
