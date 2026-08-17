package dev.loupe.core.source

import dev.loupe.core.index.LogIndex
import dev.loupe.core.io.MappedText
import dev.loupe.core.query.CompiledQuery
import dev.loupe.core.query.QueryCompiler
import dev.loupe.core.testing.writeLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Opening a folder as one time-ordered stream.
 *
 * The corpus below is the case that motivates the whole feature: a sync that starts at 23:59 in
 * one day file and finishes at 00:00 in the next. Read file by file it is two unrelated fragments;
 * merged, it is one story.
 */
class MergedSourceTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `merges day files into one ascending stream`() {
        // Given — the 21st's file runs past midnight, so the two files interleave.
        writeDay(
            "2026-07-21",
            "2026-07-21 23:59:58.000 [I] [Sync] [PullVasistas] -> a-before-midnight",
            "2026-07-21 23:59:59.500 [W] [Sync] [PullVasistas] -> a-retry",
            "2026-07-22 00:00:02.000 [E] [Sync] [PullVasistas] -> a-gave-up",
        )
        writeDay(
            "2026-07-22",
            "2026-07-22 00:00:00.000 [I] [Wpp] [Session] -> b-midnight",
            "2026-07-22 00:00:01.000 [D] [Wpp] [Session] -> b-frame",
            "2026-07-22 10:00:00.000 [I] [Ui] [HomeActivity] -> b-morning",
        )

        // When
        LogSourceLoader.open(listOf(folder)).use { source ->
            // Then — globally ordered, whatever file each entry came from.
            assertEquals(
                listOf("a-before-midnight", "a-retry", "b-midnight", "b-frame", "a-gave-up", "b-morning"),
                messagesOf(source),
            )
            assertTrue(isAscending(source.index)) { "the merged stream must be ascending by timestamp" }
            assertEquals(6, source.index.entryCount)
            assertEquals(emptyList<SkippedFile>(), source.skipped)
        }
    }

    @Test
    fun `exposes the source file as a facet`() {
        // Given
        writeDay("2026-07-21", "2026-07-21 10:00:00.000 [I] [Sync] [T] -> a-one", "2026-07-21 10:00:01.000 [I] [Sync] [T] -> a-two")
        writeDay("2026-07-22", "2026-07-22 10:00:00.000 [I] [Wpp] [T] -> b-one")

        // When
        LogSourceLoader.open(listOf(folder)).use { source ->
            val files = requireNotNull(source.index.dictionaryOf(LogIndex.FILE_FACET))

            // Then — making it a facet is what lets `file:` work in the query language for free.
            assertEquals(listOf("2026-07-21", "2026-07-22"), files.allValues())
            assertEquals(2, files.countOf(0))
            assertEquals(1, files.countOf(1))

            val compiled: CompiledQuery = QueryCompiler(source.index).compile("file:2026-07-22")
            assertTrue(compiled.isValid) { compiled.problems.toString() }
            assertEquals(listOf("b-one"), messagesOf(source, compiled))
        }
    }

    @Test
    fun `reads each entry back out of the file it came from`() {
        // Given — interleaved, so an entry's byte offset is meaningless without its file id. Using
        // the wrong one would silently return whatever text sits at that offset in another file.
        writeDay("2026-07-21", "2026-07-21 23:59:59.000 [I] [Sync] [T] -> from-the-21st")
        writeDay("2026-07-22", "2026-07-22 00:00:01.000 [I] [Sync] [T] -> from-the-22nd")

        // When
        LogSourceLoader.open(listOf(folder)).use { source ->
            // Then
            assertTrue(source.rawText(0).endsWith("from-the-21st"))
            assertTrue(source.rawText(1).endsWith("from-the-22nd"))
            assertEquals(0, source.index.fileIdOf(0))
            assertEquals(1, source.index.fileIdOf(1))
        }
    }

    @Test
    fun `sums facet counts across files instead of re-tallying them`() {
        // Given — the two files assign their own dictionary ids to the same values.
        writeDay("2026-07-21", "2026-07-21 10:00:00.000 [I] [Wpp] [T] -> a", "2026-07-21 10:00:01.000 [I] [Sync] [T] -> b")
        writeDay("2026-07-22", "2026-07-22 10:00:00.000 [I] [Sync] [T] -> c", "2026-07-22 10:00:01.000 [I] [Sync] [T] -> d")

        // When
        LogSourceLoader.open(listOf(folder)).use { source ->
            val categories = requireNotNull(source.index.dictionaryOf("category"))

            // Then — Sync is id 1 in the first file and id 0 in the second; the merge must remap.
            val syncId: Int = categories.allValues().indexOf("Sync")
            assertEquals(3, categories.countOf(syncId))
            assertEquals(1, categories.countOf(categories.allValues().indexOf("Wpp")))
        }
    }

    @Test
    fun `skips a file the chosen profile does not recognise instead of indexing garbage`() {
        // Given
        writeDay("2026-07-21", "2026-07-21 10:00:00.000 [I] [Sync] [T] -> real", "2026-07-21 10:00:01.000 [I] [Sync] [T] -> also-real")
        File(folder, "notes.txt").writeText("just some notes\nnothing structured here\n")

        // When
        LogSourceLoader.open(listOf(folder)).use { source ->
            // Then
            assertEquals(listOf("notes.txt"), source.skipped.map { skipped -> skipped.file.name })
            assertEquals(2, source.index.entryCount)
        }
    }

    @Test
    fun `a single file costs no file facet at all`() {
        // Given
        writeDay("2026-07-22", "2026-07-22 10:00:00.000 [I] [Sync] [T] -> only")

        // When
        LogSourceLoader.open(listOf(File(folder, "2026-07-22"))).use { source ->
            // Then — the merged column would be 4 bytes per entry for a constant zero.
            assertEquals(LogIndex.NO_FACET, source.index.fileFacetIndex)
            assertEquals(listOf("category", "tag"), source.index.facets.map { facet -> facet.name })
            assertEquals(0, source.index.fileIdOf(0))
        }
    }

    @Test
    fun `says so when nothing recognises the folder`() {
        // Given
        File(folder, "random.txt").writeText("not a log at all\n")

        // When / Then — the message has to name a way forward, not just fail.
        val failure = assertThrows(NoMatchingProfileException::class.java) { LogSourceLoader.open(listOf(folder)) }
        assertTrue(failure.message!!.contains(".loupe/profiles"), failure.message)
    }

    @Test
    fun `refuses a file too large to map before it tries to read it`() {
        // Given — a sparse file, so 2 GiB costs no disk. It holds nothing a profile could match,
        // which is what makes the assertion below meaningful.
        val huge = File(folder, "2026-07-21")
        java.io.RandomAccessFile(huge, "rw").use { handle -> handle.setLength(MappedText.MAX_MAPPING_BYTES + 1) }
        assumeTrue(huge.length() > MappedText.MAX_MAPPING_BYTES, "the filesystem would not make a sparse file")

        // When / Then — MappedText is built at the very end of open(), so without an up-front check
        // this waits through a full index before failing. The *type* is the proof it happened early:
        // had detection run, this would be a NoMatchingProfileException instead.
        val failure = assertThrows(IllegalArgumentException::class.java) { LogSourceLoader.open(listOf(huge)) }
        assertFalse(failure is NoMatchingProfileException, "refused at detection, not up front: ${failure.message}")
        assertTrue(failure.message!!.contains("2026-07-21"), failure.message)
        assertTrue(failure.message!!.contains("memory-mapped"), failure.message)
    }

    @Test
    fun `reports progress that reaches the total`() {
        // Given
        writeDay("2026-07-21", "2026-07-21 10:00:00.000 [I] [Sync] [T] -> a")
        writeDay("2026-07-22", "2026-07-22 10:00:00.000 [I] [Sync] [T] -> b")
        val phases: MutableList<OpenPhase> = mutableListOf()
        var lastDone = 0L
        var total = 0L

        // When
        LogSourceLoader.open(listOf(folder), progress = { phase, done, all ->
            phases.add(phase)
            lastDone = done
            total = all
        }).use { source ->
            // Then
            assertTrue(OpenPhase.Detecting in phases)
            assertTrue(OpenPhase.Merging in phases)
            assertEquals(total, lastDone) { "progress must land on the total, not near it" }
            assertEquals(source.files.sumOf { file -> file.length() }, total)
        }
    }

    private fun writeDay(name: String, vararg lines: String) {
        writeLog(folder, name, *lines)
    }

    private fun messagesOf(source: LogSource): List<String> =
        (0 until source.index.entryCount).map { entry -> source.rawText(entry).substringAfter("-> ") }

    private fun messagesOf(source: LogSource, compiled: CompiledQuery): List<String> {
        val destination = IntArray(source.index.entryCount)
        val matched: Int = compiled.filter.evaluate(source.index, source.text, destination)
        return destination.take(matched).map { entry -> source.rawText(entry).substringAfter("-> ") }
    }

    private fun isAscending(index: LogIndex): Boolean =
        (1 until index.entryCount).all { entry -> index.timestamps[entry - 1] <= index.timestamps[entry] }
}
