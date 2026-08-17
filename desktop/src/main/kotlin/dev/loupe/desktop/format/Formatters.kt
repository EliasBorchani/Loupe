package dev.loupe.desktop.format

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
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
    private val SECOND: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val MILLISECOND: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** Grouped by the machine's convention, because these are read, not parsed. */
    private val COUNT: NumberFormat = NumberFormat.getIntegerInstance(Locale.getDefault())

    /** The timeline's two ends, where a minute is as much as 700 pixels can distinguish. */
    fun minute(millis: Long): String = MINUTE.format(instant(millis))

    /** What `since:` and `until:` accept — this one is query syntax, not display. */
    fun querySecond(millis: Long): String = SECOND.format(instant(millis))

    /** A row in the list: the finest the format records, and all that fits in the column. */
    fun millisecond(millis: Long): String = MILLISECOND.format(instant(millis))

    /** The detail pane, which is the one place the date matters as well as the time. */
    fun full(millis: Long): String = FULL.format(instant(millis))

    fun count(value: Int): String = COUNT.format(value)

    fun count(value: Long): String = COUNT.format(value)

    private fun instant(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone)
}
