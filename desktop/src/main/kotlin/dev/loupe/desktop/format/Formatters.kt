package dev.loupe.desktop.format

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every clock and count the UI prints.
 *
 * These were four `DateTimeFormatter`s in three files, with three different patterns, and
 * `ZoneId.systemDefault()` resolved independently in four places — three of them wrapped in a
 * `remember` that outlived nothing. `COUNT_FORMAT` was private to one file, so nothing else could
 * format a number the same way even where it printed one right beside it.
 *
 * The zone is read once. It is the machine's, which is what the index was built in
 * (`zone = "local"` in every bundled profile), and both sides have to read the same clock — see
 * `.claude/rules/testing.md`.
 */
object Formatters {

    val zone: ZoneId = ZoneId.systemDefault()

    private val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val MILLISECOND: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val DAY_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val QUERY_INSTANT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    /** Grouped by the machine's convention, because these are read, not parsed. */
    private val COUNT: NumberFormat = NumberFormat.getIntegerInstance(Locale.getDefault())

    /** The timeline's two ends, where a minute is as much as 700 pixels can distinguish. */
    fun minute(millis: Long): String = MINUTE.format(instant(millis))

    /** The timeline's two ends when the strip spans more than one day, which the hours alone hide. */
    fun dayMinute(millis: Long): String = DAY_MINUTE.format(instant(millis))

    /**
     * What the brush writes into `since:` / `until:` — query syntax, not display.
     *
     * Absolute, because a bare `HH:mm:ss` is resolved against the day the source *starts*
     * (`QueryCompiler.resolveInstant`). That is the right reading of a typed `14:30` and silently
     * wrong for a drag over any day but the first: brushing day 3 filtered day 1. `'T'` rather than
     * a space because the lexer ends a token at whitespace, and milliseconds rather than seconds
     * because both bounds are inclusive — truncating `until` down to the second drops entries the
     * drag covered, and the band then redraws somewhere other than where it was released.
     */
    fun queryInstant(millis: Long): String = QUERY_INSTANT.format(instant(millis))

    /** A row in the list: the finest the format records, and all that fits in the column. */
    fun millisecond(millis: Long): String = MILLISECOND.format(instant(millis))

    /** The day a row belongs to, spelled out — the list's sticky header, which carries the year. */
    fun day(millis: Long): String = DAY.format(instant(millis))

    /** The day beside a row, where the header above it already says which year. */
    fun monthDay(millis: Long): String = MONTH_DAY.format(instant(millis))

    /** The detail pane, which is the one place the date matters as well as the time. */
    fun full(millis: Long): String = FULL.format(instant(millis))

    /**
     * Which calendar day an instant falls on, as a number that can be compared.
     *
     * The list marks the row where the day changes, and comparing formatted `MM-dd` strings would
     * call the same day in two different years equal. This is also zone-correct, which
     * `millis / 86_400_000` is not.
     */
    fun epochDay(millis: Long): Long = instant(millis).toLocalDate().toEpochDay()

    /** Whether a source crosses midnight, and therefore has to say which day a row is on. */
    fun spansMultipleDays(fromMillis: Long, toMillis: Long): Boolean = epochDay(fromMillis) != epochDay(toMillis)

    fun count(value: Int): String = COUNT.format(value)

    fun count(value: Long): String = COUNT.format(value)

    private fun instant(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)
}
