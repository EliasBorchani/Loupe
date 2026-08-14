package dev.loupe.core.parse

import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.io.MappedText
import dev.loupe.core.model.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.time.ZoneId

/**
 * Golden tests for the HealthMate profile, over the awkward shapes `FileLogger.LineFormat.render`
 * actually produces — the list in the PRD's Annexe B.
 *
 * Every case runs against all three parser strategies: the point of the spike was that they are
 * interchangeable, and that only holds while they agree on inputs like `[E] [ERROR] -> …`, where
 * the pseudo-tag sits exactly where a category would.
 */
class WithingsFormatParsingTest {

    companion object {
        private const val INDENT = "                       " // 23 spaces
        private val ZONE: ZoneId = ZoneId.of("Europe/Paris")

        @JvmStatic
        fun strategies(): List<EntryParser> = listOf(
            StringRegexEntryParser(ZONE),
            WidenedCharRegexEntryParser(ZONE),
            ByteScannerEntryParser(ZONE),
        )
    }

    @TempDir
    lateinit var temporaryDirectory: File

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `parses a line that carries a category`(parser: EntryParser) {
        // Given
        val file = write("2026-07-22 10:00:00.000 [D] [Sync] [tag] -> line 1")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(1, index.entryCount)
        assertEquals(LogLevel.Debug.ordinal, index.levels[0].toInt())
        assertEquals("Sync", index.categories.valueOf(index.categoryIds[0]))
        assertEquals("tag", index.tags.valueOf(index.tagIds[0]))
        assertEquals(0L, index.unparsedLineCount)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `parses a line with no category, leaving the tag in place`(parser: EntryParser) {
        // Given — the deprecated Echo overloads, and R8-obfuscated tags in release builds.
        val file = write("2026-07-22 10:00:00.000 [D] [ou1] -> no category here")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(1, index.entryCount)
        assertEquals(LogIndex.NO_VALUE, index.categoryIds[0])
        assertEquals("ou1", index.tags.valueOf(index.tagIds[0]))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `reads ERROR and CRASH as tags, not categories`(parser: EntryParser) {
        // Given — report() and reportOrCrash() write their marker where a category would sit.
        val file = write(
            "2026-07-22 10:00:00.000 [E] [ERROR] -> Reported error",
            "2026-07-22 10:00:01.000 [E] [CRASH] -> Reported error with crash",
        )

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(2, index.entryCount)
        assertEquals(LogIndex.NO_VALUE, index.categoryIds[0])
        assertEquals(LogIndex.NO_VALUE, index.categoryIds[1])
        assertEquals("ERROR", index.tags.valueOf(index.tagIds[0]))
        assertEquals("CRASH", index.tags.valueOf(index.tagIds[1]))
        assertEquals(0L, index.unparsedLineCount)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `anchors on the first arrow when the message contains its own`(parser: EntryParser) {
        // Given
        val message = "updating existing aggregate: steps: 100 -> 250"
        val file = write("2026-07-22 10:00:00.000 [I] [AggregateComputation] [ComputeAggregateForDay] -> $message")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals("AggregateComputation", index.categories.valueOf(index.categoryIds[0]))
        assertEquals("ComputeAggregateForDay", index.tags.valueOf(index.tagIds[0]))
        assertTrue(readEntry(file, index, 0).endsWith(message)) { "the whole message must survive the split" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `is not fooled by a message that opens with a bracket`(parser: EntryParser) {
        // Given
        val file = write("2026-07-22 10:00:00.000 [I] [Sync] [PullVasistas] -> [a1b2c3d4] start user=42")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals("Sync", index.categories.valueOf(index.categoryIds[0]))
        assertEquals("PullVasistas", index.tags.valueOf(index.tagIds[0]))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `accepts an empty message`(parser: EntryParser) {
        // Given — render() trims the body, so a blank message leaves a trailing space after the arrow.
        val file = write("2026-07-22 10:00:00.000 [D] [Sync] [tag] -> ")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(1, index.entryCount)
        assertEquals(0L, index.unparsedLineCount)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `folds a multi-line message into one entry`(parser: EntryParser) {
        // Given
        val file = write(
            "2026-07-22 12:00:00.000 [D] [Sync] [tag] -> first",
            "${INDENT}second",
            "${INDENT}third",
            "2026-07-22 12:00:01.000 [D] [Sync] [tag] -> next entry",
        )

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(2, index.entryCount)
        assertEquals(2L, index.continuationLineCount)
        assertEquals(0L, index.unparsedLineCount)
        assertEquals(
            "2026-07-22 12:00:00.000 [D] [Sync] [tag] -> first\n${INDENT}second\n${INDENT}third",
            readEntry(file, index, 0),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `keeps a stack trace attached to the entry that raised it`(parser: EntryParser) {
        // Given
        val file = write(
            "2026-07-22 12:00:00.000 [E] [Sync] [tag] -> boom",
            "${INDENT}java.lang.IllegalStateException: nope",
            "$INDENT\tat com.withings.Boom.explode(Boom.kt:42)",
            "2026-07-22 12:00:02.000 [D] [Sync] [tag] -> carrying on",
        )

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(2, index.entryCount)
        assertEquals(2L, index.continuationLineCount)
        assertTrue(readEntry(file, index, 0).contains("at com.withings.Boom.explode(Boom.kt:42)"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `counts non-entry lines as unrecognised instead of dropping them`(parser: EntryParser) {
        // Given — the export separator and the tail-scan notice are not log entries.
        val file = write(
            "=== 2026-07-22 ===",
            "2026-07-22 12:00:00.000 [D] [Sync] [tag] -> real entry",
            "--- older lines dropped: only the last 128 KiB were scanned ---",
        )

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(1, index.entryCount)
        assertEquals(2L, index.unparsedLineCount)
        assertEquals(3L, index.lineCount)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    fun `resolves the timestamp in the configured zone`(parser: EntryParser) {
        // Given
        val file = write("2026-07-22 12:00:00.500 [D] [Sync] [tag] -> x")

        // When
        val timestamp: Long = LogIndexer(parser).index(file).timestamps[0]

        // Then — 22 July is CEST, UTC+2.
        assertEquals(
            java.time.LocalDateTime.of(2026, 7, 22, 12, 0, 0, 500_000_000).atZone(ZONE).toInstant().toEpochMilli(),
            timestamp,
        )
    }

    @Test
    fun `all three strategies agree on a file mixing every shape`() {
        // Given
        val file = write(
            "2026-07-22 10:00:00.000 [V] [Wpp] [c.w.w.Session] -> frame in",
            "2026-07-22 10:00:00.100 [D] [ou1] -> no category",
            "2026-07-22 10:00:00.200 [E] [ERROR] -> Reported error",
            "${INDENT}java.lang.IllegalStateException: nope",
            "2026-07-22 10:00:00.300 [I] [Sync] [Pull] -> steps: 100 -> 250",
            "=== separator ===",
            "2026-07-22 10:00:00.400 [W] [Sync] [Pull] -> [abc] retry — accentué",
            "${INDENT}second line",
        )

        // When
        val indexes: List<LogIndex> = strategies().map { parser -> LogIndexer(parser).index(file) }

        // Then
        val reference: LogIndex = indexes.first()
        indexes.drop(1).forEach { candidate ->
            assertEquals(reference.entryCount, candidate.entryCount)
            assertEquals(reference.unparsedLineCount, candidate.unparsedLineCount)
            assertEquals(reference.continuationLineCount, candidate.continuationLineCount)
            for (entry in 0 until reference.entryCount) {
                assertEquals(reference.timestamps[entry], candidate.timestamps[entry], "timestamp at $entry")
                assertEquals(reference.levels[entry], candidate.levels[entry], "level at $entry")
                assertEquals(reference.byteOffsets[entry], candidate.byteOffsets[entry], "offset at $entry")
                assertEquals(reference.byteLengths[entry], candidate.byteLengths[entry], "length at $entry")
                assertEquals(
                    categoryOf(reference, entry),
                    categoryOf(candidate, entry),
                    "category at $entry",
                )
                assertEquals(
                    reference.tags.valueOf(reference.tagIds[entry]),
                    candidate.tags.valueOf(candidate.tagIds[entry]),
                    "tag at $entry",
                )
            }
        }
    }

    private fun categoryOf(index: LogIndex, entry: Int): String =
        if (index.categoryIds[entry] == LogIndex.NO_VALUE) "<none>" else index.categories.valueOf(index.categoryIds[entry])

    /** Reads an entry back through the same `(offset, length)` path the UI would use. */
    private fun readEntry(file: File, index: LogIndex, entry: Int): String =
        MappedText(file).use { text -> text.decode(index.byteOffsets[entry], index.byteLengths[entry]) }

    private fun write(vararg lines: String): File {
        val file = File(temporaryDirectory, "2026-07-22")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }
}
