package dev.loupe.spike

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlin.random.Random

/**
 * Writes a synthetic HealthMate log, byte-for-byte in the shape `FileLogger.LineFormat.render`
 * produces.
 *
 * Benchmarking on uniform lines would flatter every strategy equally and tell us nothing, so the
 * distributions here are the ones that actually make parsing awkward:
 *  - a heavy tail of categories (`Sync` and `Wpp` dominate, as in a real session),
 *  - obfuscated R8 tags mixed with readable ones, to blow up the tag facet's cardinality,
 *  - messages that contain their own ` -> `, and messages that start with `[`,
 *  - lines with no category at all, plus `report()` / `reportOrCrash()` pseudo-tags,
 *  - multi-line messages and stack traces re-indented by 23 spaces.
 */
object LogFileGenerator {

    private const val WRITE_BUFFER_BYTES = 1 shl 20
    private const val CONTINUATION_INDENT = "                       " // 23 spaces

    private const val MULTILINE_MESSAGE_RATE = 0.05
    private const val STACK_TRACE_RATE = 0.01
    private const val NO_CATEGORY_RATE = 0.03
    private const val REPORTED_ERROR_RATE = 0.003
    private const val NON_ASCII_RATE = 0.01

    /** Weighted so a handful of categories carry most of the volume, like a real session. */
    private val CATEGORIES: List<Pair<String, Int>> = listOf(
        "Sync" to 240, "Wpp" to 190, "BleNetwork" to 120, "Network" to 90,
        "Database" to 70, "Measurements" to 55, "AggregateComputation" to 45,
        "Ui" to 40, "Datastore" to 30, "Push" to 22, "Analytics" to 18,
        "SleepTrackComputation" to 15, "HealthConnect" to 12, "MeasurementPlan" to 10,
        "Migration" to 8, "BodyBalance" to 7, "Billing" to 6, "Vo2Max" to 5,
        "Tls" to 4, "Zendesk" to 3, "Other" to 3, "Unknown" to 1,
    )

    /** Level symbol weights: verbose and debug dominate, errors are rare. */
    private val LEVELS: List<Pair<Char, Int>> = listOf('V' to 20, 'D' to 45, 'I' to 25, 'W' to 8, 'E' to 2)

    private val READABLE_TAGS: List<String> = listOf(
        "SyncService", "c.w.s.PullVasistasUseCase", "WppDeviceSession", "BleGattCallback",
        "c.w.m.MeasureGroupDao", "AggregateComputationWorker", "HealthMateDatabase",
        "c.w.n.RetrofitClient", "DeviceInstallActivity", "c.w.u.UserContextHolder",
        "NotificationDispatcher", "c.w.t.TrackBuilder", "VasistasRepository",
        "c.w.b.BodyBalanceViewModel", "SessionRefreshInterceptor",
    )

    /**
     * Message shapes, as `prefix / middle / suffix` around two random numbers.
     *
     * Built by appending rather than through `String.format`: at ~1 µs a call, formatting would
     * dominate generation of a 1 GiB fixture and make it a coffee break instead of a minute.
     * Shapes 2 and 4 are the awkward ones on purpose — an arrow inside the message, and a message
     * that opens with `[`.
     */
    private val MESSAGE_SHAPES: List<Triple<String, String, String>> = listOf(
        Triple("start user=", " day=2026-06-02 scopes=[Activity] rev=", ""),
        Triple("updating existing aggregate: steps: ", " -> ", ""),
        Triple("remote vasistas fetch failed for categories=[Motion] attempt=", " backoff=", "ms"),
        Triple("[", "] request enqueued, queue depth=", ""),
        Triple("device 00:24:e4:a1:", " connected, mtu=", ""),
        Triple("response 200 in ", "ms, ", " bytes"),
        Triple("no measure to push for user=", " since=", ""),
        Triple("battery level ", "% reported by device ", ""),
        Triple("computed ", " aggregates in ", "ms"),
        Triple("session token refreshed, expires in ", "s for user=", ""),
    )

    /**
     * @param targetBytes stop once the file reaches roughly this size.
     * @return the number of entries written.
     */
    fun generate(target: File, targetBytes: Long, seed: Long = 20260814L): Long {
        target.parentFile?.mkdirs()
        val random = Random(seed)
        val categoryPicker = WeightedPicker(CATEGORIES, random)
        val levelPicker = WeightedPicker(LEVELS, random)
        val obfuscatedTags: List<String> = List(800) { index -> obfuscatedTag(index) }

        var entryCount = 0L
        var writtenBytes = 0L
        var dayOfMonth = 2
        var millisOfDay = 6 * 3_600_000L

        val line = StringBuilder(256)
        BufferedWriter(OutputStreamWriter(FileOutputStream(target), Charsets.UTF_8), WRITE_BUFFER_BYTES).use { writer ->
            while (writtenBytes < targetBytes) {
                millisOfDay += random.nextInt(1, 60)
                if (millisOfDay >= 86_400_000L) {
                    millisOfDay -= 86_400_000L
                    dayOfMonth++
                }

                line.setLength(0)
                appendTimestamp(line, dayOfMonth, millisOfDay)
                line.append(' ')

                val isReportedError: Boolean = random.nextDouble() < REPORTED_ERROR_RATE
                if (isReportedError) {
                    // report() / reportOrCrash(): no category, and ERROR/CRASH lands in the tag slot.
                    line.append("[E] [").append(if (random.nextBoolean()) "ERROR" else "CRASH").append("] -> ")
                    line.append("Reported error")
                    appendStackTrace(line, random)
                } else {
                    line.append('[').append(levelPicker.pick()).append("] ")
                    if (random.nextDouble() >= NO_CATEGORY_RATE) {
                        line.append('[').append(categoryPicker.pick()).append("] ")
                    }
                    val tag: String = if (random.nextDouble() < 0.6) {
                        obfuscatedTags[random.nextInt(obfuscatedTags.size)]
                    } else {
                        READABLE_TAGS[random.nextInt(READABLE_TAGS.size)]
                    }
                    line.append('[').append(tag).append("] -> ")
                    appendMessage(line, random)

                    when {
                        random.nextDouble() < STACK_TRACE_RATE -> appendStackTrace(line, random)
                        random.nextDouble() < MULTILINE_MESSAGE_RATE -> appendExtraMessageLines(line, random)
                    }
                }

                line.append('\n')
                writer.append(line)
                // ASCII except for the rare accented message; close enough to track progress.
                writtenBytes += line.length
                entryCount++
            }
        }
        return entryCount
    }

    private fun appendMessage(line: StringBuilder, random: Random) {
        val (prefix: String, middle: String, suffix: String) = MESSAGE_SHAPES[random.nextInt(MESSAGE_SHAPES.size)]
        line.append(prefix).append(random.nextInt(1, 99999))
            .append(middle).append(random.nextInt(1, 99999))
            .append(suffix)
        if (random.nextDouble() < NON_ASCII_RATE) {
            line.append(" — appareil « Body Scan » prêt")
        }
    }

    private fun appendExtraMessageLines(line: StringBuilder, random: Random) {
        repeat(random.nextInt(1, 4)) { index ->
            line.append('\n').append(CONTINUATION_INDENT)
                .append("continued detail line ").append(index).append(" value=").append(random.nextInt(1000))
        }
    }

    private fun appendStackTrace(line: StringBuilder, random: Random) {
        line.append('\n').append(CONTINUATION_INDENT).append("java.lang.IllegalStateException: nope")
        repeat(random.nextInt(6, 13)) { frame ->
            line.append('\n').append(CONTINUATION_INDENT)
                .append("\tat com.withings.sync.Step").append(frame).append(".run(SyncStep.kt:").append(random.nextInt(20, 400)).append(')')
        }
    }

    /** `yyyy-MM-dd HH:mm:ss.SSS`, appended without `String.format` — this runs once per entry. */
    private fun appendTimestamp(line: StringBuilder, dayOfMonth: Int, millisOfDay: Long) {
        line.append("2026-06-")
        appendPadded(line, dayOfMonth, 2)
        line.append(' ')
        appendPadded(line, (millisOfDay / 3_600_000L).toInt(), 2)
        line.append(':')
        appendPadded(line, (millisOfDay / 60_000L % 60).toInt(), 2)
        line.append(':')
        appendPadded(line, (millisOfDay / 1_000L % 60).toInt(), 2)
        line.append('.')
        appendPadded(line, (millisOfDay % 1_000L).toInt(), 3)
    }

    private fun appendPadded(line: StringBuilder, value: Int, width: Int) {
        var divisor = 1
        repeat(width - 1) { divisor *= 10 }
        while (divisor > 0) {
            line.append(('0' + (value / divisor) % 10))
            divisor /= 10
        }
    }

    private fun obfuscatedTag(index: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        return "${alphabet[index % 26]}${alphabet[(index / 26) % 26]}${index % 10}"
    }

    private class WeightedPicker<T>(weighted: List<Pair<T, Int>>, private val random: Random) {
        private val values: List<T> = weighted.map { (value, _) -> value }
        private val cumulativeWeights: IntArray = IntArray(weighted.size).also { cumulative ->
            var running = 0
            weighted.forEachIndexed { index, (_, weight) ->
                running += weight
                cumulative[index] = running
            }
        }
        private val totalWeight: Int = cumulativeWeights.last()

        fun pick(): T {
            val target: Int = random.nextInt(totalWeight)
            val found: Int = cumulativeWeights.indexOfFirst { cumulative -> target < cumulative }
            return values[found]
        }
    }
}
