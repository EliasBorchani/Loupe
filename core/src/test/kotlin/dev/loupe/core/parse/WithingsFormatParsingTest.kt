package dev.loupe.core.parse

import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.io.MappedText
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.ProfileRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * Golden tests for the bundled HealthMate profile, over the awkward shapes
 * `FileLogger.LineFormat.render` actually produces.
 *
 * Every case runs against both parsers — the generic profile-driven one and the hand-written byte
 * scanner. Two independent implementations of the same profile disagreeing is the cheapest way to
 * catch a regex that quietly means something other than what it reads like.
 */
class WithingsFormatParsingTest {

    companion object {
        private const val INDENT = "                       " // 23 spaces

        /** The bundled profile, parsed straight from the jar resource the app will ship. */
        val WITHINGS: CompiledProfile = ProfileRegistry.bundled().profiles
            .single { profile -> profile.name == "withings-healthmate" }

        @JvmStatic
        fun parsers(): List<EntryParser> = listOf(
            ProfileEntryParser(WITHINGS),
            ByteScannerEntryParser(WITHINGS),
        )
    }

    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `the bundled profile compiles clean, on the fast paths`() {
        // Given / When — compiled in the companion above.
        // Then
        assertEquals(listOf("category", "tag"), WITHINGS.facets.map { facet -> facet.name })
        assertEquals(listOf("V", "D", "I", "W", "E"), WITHINGS.levelDecoder?.order)
        assertTrue(WITHINGS.timestampFormat.isFastPath) { "the timestamp must not fall back to DateTimeFormatter" }
        assertTrue(WITHINGS.continues?.isFastPath == true) { "the 23-space indent must reduce to a literal prefix" }
        assertEquals(emptyList<String>(), WITHINGS.warnings)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    fun `parses a line that carries a category`(parser: EntryParser) {
        // Given
        val file = write("2026-07-22 10:00:00.000 [D] [Sync] [tag] -> line 1")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(1, index.entryCount)
        assertEquals(1, index.levels[0].toInt()) // Debug, second on the V D I W E scale
        assertEquals("Sync", facetValue(index, "category", 0))
        assertEquals("tag", facetValue(index, "tag", 0))
        assertEquals(0L, index.unrecognisedLineCount)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    fun `parses a line with no category, leaving the tag in place`(parser: EntryParser) {
        // Given — the deprecated Echo overloads, and R8-obfuscated tags in release builds.
        val file = write("2026-07-22 10:00:00.000 [D] [ou1] -> no category here")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(1, index.entryCount)
        assertEquals(null, facetValue(index, "category", 0))
        assertEquals("ou1", facetValue(index, "tag", 0))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
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
        assertEquals(null, facetValue(index, "category", 0))
        assertEquals(null, facetValue(index, "category", 1))
        assertEquals("ERROR", facetValue(index, "tag", 0))
        assertEquals("CRASH", facetValue(index, "tag", 1))
        assertEquals(0L, index.unrecognisedLineCount)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    fun `anchors on the first arrow when the message contains its own`(parser: EntryParser) {
        // Given
        val message = "updating existing aggregate: steps: 100 -> 250"
        val file = write("2026-07-22 10:00:00.000 [I] [AggregateComputation] [ComputeAggregateForDay] -> $message")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals("AggregateComputation", facetValue(index, "category", 0))
        assertEquals("ComputeAggregateForDay", facetValue(index, "tag", 0))
        assertTrue(readEntry(file, index, 0).endsWith(message)) { "the whole message must survive the split" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    fun `is not fooled by a message that opens with a bracket`(parser: EntryParser) {
        // Given
        val file = write("2026-07-22 10:00:00.000 [I] [Sync] [PullVasistas] -> [a1b2c3d4] start user=42")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals("Sync", facetValue(index, "category", 0))
        assertEquals("PullVasistas", facetValue(index, "tag", 0))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    fun `accepts an empty message`(parser: EntryParser) {
        // Given — render() trims the body, so a blank message leaves a trailing space after the arrow.
        val file = write("2026-07-22 10:00:00.000 [D] [Sync] [tag] -> ")

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(1, index.entryCount)
        assertEquals(0L, index.unrecognisedLineCount)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
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
        assertEquals(0L, index.unrecognisedLineCount)
        assertEquals(
            "2026-07-22 12:00:00.000 [D] [Sync] [tag] -> first\n${INDENT}second\n${INDENT}third",
            readEntry(file, index, 0),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
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
    @MethodSource("parsers")
    fun `classifies the export separator and the truncation notice instead of dropping them`(parser: EntryParser) {
        // Given
        val file = write(
            "=== 2026-07-22 ===",
            "2026-07-22 12:00:00.000 [D] [Sync] [tag] -> real entry",
            "--- older lines dropped: only the last 128 KiB were scanned ---",
        )

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals(1, index.entryCount)
        assertEquals(1L, index.sectionLineCount)
        assertEquals(1L, index.noticeLineCount)
        assertEquals(0L, index.unrecognisedLineCount)
        assertEquals(3L, index.lineCount)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    fun `keeps a facet value carrying a multi-byte character intact`(parser: EntryParser) {
        // Given — the byte and char interning paths must agree on where a value ends.
        val file = write(
            "2026-07-22 12:00:00.000 [D] [Sync] [Résumé] -> premier",
            "2026-07-22 12:00:01.000 [D] [Sync] [Résumé] -> second",
        )

        // When
        val index: LogIndex = LogIndexer(parser).index(file)

        // Then
        assertEquals("Résumé", facetValue(index, "tag", 0))
        assertEquals("Résumé", facetValue(index, "tag", 1))
        assertEquals(1, index.dictionaryOf("tag")?.size) { "the same value must intern to one id" }
    }

    @Test
    fun `both parsers agree on a file mixing every shape`() {
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
        val indexes: List<LogIndex> = parsers().map { parser -> LogIndexer(parser).index(file) }

        // Then
        val reference: LogIndex = indexes.first()
        indexes.drop(1).forEach { candidate ->
            assertEquals(reference.entryCount, candidate.entryCount)
            assertEquals(reference.unrecognisedLineCount, candidate.unrecognisedLineCount)
            assertEquals(reference.continuationLineCount, candidate.continuationLineCount)
            assertEquals(reference.sectionLineCount, candidate.sectionLineCount)
            for (entry in 0 until reference.entryCount) {
                assertEquals(reference.timestamps[entry], candidate.timestamps[entry], "timestamp at $entry")
                assertEquals(reference.levels[entry], candidate.levels[entry], "level at $entry")
                assertEquals(reference.byteOffsets[entry], candidate.byteOffsets[entry], "offset at $entry")
                assertEquals(reference.byteLengths[entry], candidate.byteLengths[entry], "length at $entry")
                assertEquals(facetValue(reference, "category", entry), facetValue(candidate, "category", entry), "category at $entry")
                assertEquals(facetValue(reference, "tag", entry), facetValue(candidate, "tag", entry), "tag at $entry")
            }
        }
    }

    /** @return the facet's value for an entry, or `null` when the group did not participate. */
    private fun facetValue(index: LogIndex, facetName: String, entry: Int): String? {
        val facetIndex: Int = index.facetIndexOf(facetName)
        val valueId: Int = index.facetValues[facetIndex][entry]
        return if (valueId == LogIndex.NO_VALUE) null else index.facetDictionaries[facetIndex].valueOf(valueId)
    }

    /** Reads an entry back through the same `(offset, length)` path the UI would use. */
    private fun readEntry(file: File, index: LogIndex, entry: Int): String =
        MappedText(file).use { text -> text.decode(index.byteOffsets[entry], index.byteLengths[entry]) }

    private fun write(vararg lines: String): File {
        val file = File(temporaryDirectory, "2026-07-22")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }
}
