package dev.loupe.core.index

import dev.loupe.core.parse.ProfileEntryParser
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.source.LogSourceLoader
import dev.loupe.core.testing.BundledProfile
import dev.loupe.core.testing.WITHINGS_INDENT
import dev.loupe.core.testing.writeLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The health indicator, and the answer to the question it raises.
 *
 * A count on its own says the profile is imperfect. The shape says *which part* of it is wrong, and
 * the shapes point at very different fixes — which is the whole reason to keep them apart.
 */
class UnrecognisedLinesTest {

    companion object {
        private val WITHINGS: CompiledProfile = BundledProfile.withings
    }

    @TempDir
    lateinit var folder: File

    @Test
    fun `a clean file reports nothing`() {
        // Given
        val file = write("2026-07-22 10:00:00.000 [D] [Sync] [tag] -> fine", "${WITHINGS_INDENT}continued")

        // When
        val index: LogIndex = LogIndexer(ProfileEntryParser(WITHINGS)).index(file)

        // Then
        assertEquals(1.0, index.recognisedLineRatio)
        assertEquals(0, index.unrecognised.total)
    }

    @Test
    fun `tells the four shapes apart`() {
        // Given — one of each, and they mean four different things.
        val file = write(
            "2026-07-22 10:00:00.000 [D] [Sync] [tag] -> fine",
            "",
            "   short indent, not 23",
            "2026-07-22 10:00:00.000 [X] [Sync] [tag] -> unknown level",
            "printed by something else entirely",
        )

        // When
        val report: UnrecognisedReport = LogIndexer(ProfileEntryParser(WITHINGS)).index(file).unrecognised

        // Then
        assertEquals(4, report.total)
        assertEquals(1, report.countOf(UnrecognisedKind.Empty))
        assertEquals(1, report.countOf(UnrecognisedKind.NearContinuation))
        assertEquals(1, report.countOf(UnrecognisedKind.Other))
        // The informative one: the cheap pre-filter accepted it and the full regex did not, so the
        // format has a shape the profile does not describe.
        assertEquals(1, report.countOf(UnrecognisedKind.NearEntry))
    }

    @Test
    fun `keeps an example of each shape, with where to find it`() {
        // Given
        val file = write(
            "2026-07-22 10:00:00.000 [D] [Sync] [tag] -> fine",
            "junk on line two",
        )

        // When
        val report: UnrecognisedReport = LogIndexer(ProfileEntryParser(WITHINGS)).index(file).unrecognised

        // Then
        val sample: UnrecognisedLine = report.samplesOf(UnrecognisedKind.Other).single()
        assertEquals(2L, sample.lineNumber)
        assertEquals("junk on line two", sample.text)
    }

    @Test
    fun `caps samples per shape, not overall`() {
        // Given — one shape floods the file. A total cap would let it crowd out the single example
        // of the shape that actually explains the problem.
        val lines: List<String> = List(500) { index -> "junk $index" } + listOf("   indented orphan")
        val file = write(*lines.toTypedArray())

        // When
        val report: UnrecognisedReport = LogIndexer(ProfileEntryParser(WITHINGS)).index(file).unrecognised

        // Then — the count is exact, the samples are bounded, and the rare shape survives.
        assertEquals(501, report.total)
        assertEquals(UnrecognisedReport.SAMPLES_PER_KIND, report.samplesOf(UnrecognisedKind.Other).size)
        assertEquals(1, report.samplesOf(UnrecognisedKind.NearContinuation).size)
    }

    @Test
    fun `truncates a runaway line rather than holding it`() {
        // Given
        val file = write("x".repeat(UnrecognisedReport.SAMPLE_MAX_CHARS * 4))

        // When
        val report: UnrecognisedReport = LogIndexer(ProfileEntryParser(WITHINGS)).index(file).unrecognised

        // Then
        assertEquals(UnrecognisedReport.SAMPLE_MAX_CHARS, report.samples.single().text.length)
    }

    @Test
    fun `a merged folder says which file a sample came from`() {
        // Given — mostly clean, because a file that is half junk is rejected by detection long
        // before it is indexed, and rightly so: detect.min_match is 0.80.
        File(folder, "2026-07-21").writeText(
            (1..9).joinToString("\n") { line -> "2026-07-21 10:00:0$line.000 [D] [Sync] [tag] -> ok" } +
                "\njunk in the first file\n",
        )
        File(folder, "2026-07-22").writeText(
            (1..9).joinToString("\n") { line -> "2026-07-22 10:00:0$line.000 [D] [Sync] [tag] -> ok" } +
                "\njunk in the second file\n",
        )

        // When
        LogSourceLoader.open(listOf(folder)).use { source ->
            val report: UnrecognisedReport = source.index.unrecognised

            // Then — a sample with no file attached is unfindable in a merged view.
            assertEquals(2, report.total)
            val byFile: Map<Int, String> = report.samples.associate { sample -> sample.fileId to sample.text }
            assertEquals("junk in the first file", byFile[0])
            assertEquals("junk in the second file", byFile[1])
            assertTrue(source.files[1].name == "2026-07-22")
        }
    }

    private fun write(vararg lines: String): File = writeLog(folder, "2026-07-22", *lines)
}
