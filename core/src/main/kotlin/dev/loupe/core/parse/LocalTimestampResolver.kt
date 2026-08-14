package dev.loupe.core.parse

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Turns a `yyyy-MM-dd HH` prefix into the epoch-millis of that hour, caching the last one seen.
 *
 * Going through `LocalDateTime.atZone().toInstant()` for every entry costs a few hundred
 * nanoseconds — on 5 M entries that alone is over a second, more than the whole indexing budget
 * can spare. A log file walks time forwards, so the hour changes at most a few dozen times in a
 * file: resolve it once, then the minutes/seconds/millis are plain arithmetic.
 *
 * Caching on the **hour** rather than the day is what keeps this correct across a DST transition,
 * since offsets only ever change on an hour boundary. The one case no parser can resolve is the
 * repeated hour of a fall-back, where a local timestamp is genuinely ambiguous; like
 * `ZonedDateTime`, this picks the earlier offset.
 */
class LocalTimestampResolver(private val zone: ZoneId = ZoneId.systemDefault()) {

    companion object {
        const val MILLIS_PER_SECOND: Long = 1_000L
        const val MILLIS_PER_MINUTE: Long = 60 * MILLIS_PER_SECOND
        const val MILLIS_PER_HOUR: Long = 60 * MILLIS_PER_MINUTE
    }

    private var cachedYear: Int = Int.MIN_VALUE
    private var cachedMonth: Int = 0
    private var cachedDay: Int = 0
    private var cachedHour: Int = -1
    private var cachedHourStartMillis: Long = 0L

    fun resolve(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, milli: Int): Long {
        if (year != cachedYear || month != cachedMonth || day != cachedDay || hour != cachedHour) {
            cachedYear = year
            cachedMonth = month
            cachedDay = day
            cachedHour = hour
            cachedHourStartMillis = LocalDateTime.of(year, month, day, hour, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }
        return cachedHourStartMillis + minute * MILLIS_PER_MINUTE + second * MILLIS_PER_SECOND + milli
    }
}
