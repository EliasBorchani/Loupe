package dev.loupe.desktop.state

import dev.loupe.core.index.LogIndex
import dev.loupe.core.source.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The screen's behaviour, driven without a window.
 *
 * Everything the UI does goes through [LoupeState], so this covers the interaction loop — open,
 * type, tick a facet, brush the timeline — without needing a display or a Compose test harness.
 * What it cannot cover is layout, which is what the eye is for.
 */
class LoupeStateTest {

    companion object {
        private const val TIMEOUT_MILLIS = 20_000L
    }

    @TempDir
    lateinit var folder: File

    private lateinit var scope: CoroutineScope
    private lateinit var state: LoupeState

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        state = LoupeState(scope)
        writeDay(
            "2026-07-21",
            "2026-07-21 23:59:58.000 [I] [Sync] [PullVasistas] -> before-midnight",
            "2026-07-21 23:59:59.000 [W] [Sync] [PullVasistas] -> retry backoff=200",
            "2026-07-22 00:00:03.000 [E] [Sync] [PullVasistas] -> gave-up",
            "                       java.lang.IllegalStateException: nope",
        )
        writeDay(
            "2026-07-22",
            "2026-07-22 00:00:00.000 [I] [Wpp] [Session] -> midnight",
            "2026-07-22 00:00:01.000 [D] [Wpp] [Session] -> frame",
            "2026-07-22 10:00:00.000 [I] [Ui] [HomeActivity] -> morning",
        )
    }

    @AfterEach
    fun tearDown() {
        state.close()
        scope.cancel()
    }

    @Test
    fun `opens a folder and reports what it found`() = runBlocking {
        // Given / When
        val source: LogSource = openFolder()

        // Then
        assertEquals(6, source.index.entryCount)
        assertEquals(listOf("2026-07-21", "2026-07-22"), source.files.map { file -> file.name })
        assertEquals("withings-healthmate", source.profile.name)
        assertEquals(1L, source.index.continuationLineCount)
    }

    @Test
    fun `a facet click writes the query, and the query drives the results`() = runBlocking {
        // Given
        openFolder()
        assertEquals(6, awaitResults("").matchCount)

        // When — the sidebar's job, in one call.
        state.toggleFacetValue("category", "Sync")

        // Then
        assertEquals("category:Sync", state.query.value)
        assertEquals(3, awaitResults("category:Sync").matchCount)
    }

    @Test
    fun `ticking two levels collapses to a comparison`() = runBlocking {
        // Given
        openFolder()

        // When
        state.toggleFacetValue(LEVEL_FIELD, "W")
        state.toggleFacetValue(LEVEL_FIELD, "E")

        // Then — what makes the syntax learnable: you tick boxes and the words appear.
        assertEquals("level>=W", state.query.value)
        assertEquals(2, awaitResults("level>=W").matchCount)
    }

    @Test
    fun `facet counts are what you would get by clicking, not what is on screen`() = runBlocking {
        // Given — one category picked.
        val source: LogSource = openFolder()
        state.setQuery("category:Sync")
        val results: Results = awaitResults("category:Sync")
        val categoryFacet: Int = source.index.facetIndexOf("category")
        val dictionary = source.index.facetDictionaries[categoryFacet]
        val wppId: Int = dictionary.allValues().indexOf("Wpp")

        // Then — Wpp must still show its own count, or there would be no way to switch to it.
        assertEquals(2, results.counts.facets[categoryFacet][wppId])
        assertEquals(3, results.counts.facets[categoryFacet][dictionary.allValues().indexOf("Sync")])
    }

    @Test
    fun `the file facet filters a merged folder`() = runBlocking {
        // Given
        val source: LogSource = openFolder()
        assertTrue(source.index.fileFacetIndex != LogIndex.NO_FACET)

        // When
        state.toggleFacetValue(LogIndex.FILE_FACET, "2026-07-22")

        // Then
        assertEquals("file:2026-07-22", state.query.value)
        assertEquals(3, awaitResults("file:2026-07-22").matchCount)
    }

    @Test
    fun `a timeline brush writes a time window into the query`() = runBlocking {
        // Given
        val source: LogSource = openFolder()

        // When — the first two entries' span.
        state.setTimeWindow(source.index.minTimestampMillis, source.index.timestamps[1])

        // Then
        assertTrue(state.query.value.startsWith("since:"), state.query.value)
        assertTrue(state.query.value.contains("until:"), state.query.value)
        assertEquals(2, awaitResults(state.query.value).matchCount)
    }

    @Test
    fun `the timeline keeps its shape when a range is brushed`() = runBlocking {
        // Given — the whole file's density.
        val source: LogSource = openFolder()
        val unbounded: Results = awaitResults("")
        val totalBars: Int = unbounded.histogram.sumOf { level -> level.sum() }

        // When — brush the two earliest entries.
        state.setTimeWindow(source.index.minTimestampMillis, source.index.timestamps[1])
        val brushed: Results = awaitResults(state.query.value)

        // Then — the list narrows, but the strip still draws every entry: it is a map of where you
        // are, and emptying it would show the answer where the question belongs.
        assertEquals(2, brushed.matchCount)
        assertEquals(totalBars, brushed.histogram.sumOf { level -> level.sum() })
        assertEquals(source.index.minTimestampMillis, brushed.windowSinceMillis)
    }

    @Test
    fun `a facet still narrows the timeline, unlike the time window`() = runBlocking {
        // Given — only the time window is lifted for the strip; every other term still applies.
        openFolder()

        // When
        state.setQuery("category:Wpp")
        val results: Results = awaitResults("category:Wpp")

        // Then
        assertEquals(2, results.histogram.sumOf { level -> level.sum() })
    }

    @Test
    fun `add merges into what is open, instead of replacing it`() = runBlocking {
        // Given — one file open.
        state.open(listOf(File(folder, "2026-07-21")))
        withTimeout(TIMEOUT_MILLIS) { state.source.first { source -> source != null } }
        assertEquals(3, requireNotNull(state.source.value).index.entryCount)

        // When
        state.add(listOf(File(folder, "2026-07-22")))
        val merged: LogSource = withTimeout(TIMEOUT_MILLIS) {
            requireNotNull(state.source.first { source -> source != null && source.files.size == 2 })
        }

        // Then
        assertEquals(6, merged.index.entryCount)
        assertEquals(listOf("2026-07-21", "2026-07-22"), merged.files.map { file -> file.name })
    }

    @Test
    fun `adding a file already open changes nothing`() = runBlocking {
        // Given
        val source: LogSource = openFolder()
        val before: Int = source.index.entryCount

        // When
        state.add(listOf(File(folder, "2026-07-21")))
        val after: LogSource = withTimeout(TIMEOUT_MILLIS) {
            requireNotNull(state.source.first { candidate -> candidate != null && candidate !== source })
        }

        // Then
        assertEquals(before, after.index.entryCount)
        assertEquals(2, after.files.size)
    }

    @Test
    fun `arrows start at an end when nothing is selected`() = runBlocking {
        // Given
        val source: LogSource = openFolder()
        awaitResults("")

        // When / Then — the first press always lands somewhere useful.
        state.moveSelection(1)
        assertEquals("before-midnight", messageOf(source, requireNotNull(state.selectedEntry.value)))

        state.select(null)
        state.moveSelection(-1)
        assertEquals("morning", messageOf(source, requireNotNull(state.selectedEntry.value)))
    }

    @Test
    fun `arrows step through the result, not through the index`() = runBlocking {
        // Given — Sync matches entries 0, 1 and 4 of the merged stream; 2 and 3 are Wpp.
        val source: LogSource = openFolder()
        state.setQuery("category:Sync")
        awaitResults("category:Sync")
        state.select(entryWithMessage(source, "retry"))

        // When
        state.moveSelection(1)

        // Then — the next *match*, skipping the two entries the query excluded.
        assertEquals("gave-up", messageOf(source, requireNotNull(state.selectedEntry.value)))
    }

    @Test
    fun `arrows stop at the ends instead of wrapping`() = runBlocking {
        // Given — in a list of nine million, teleporting to the far end is never what was meant.
        val source: LogSource = openFolder()
        awaitResults("")
        state.select(entryWithMessage(source, "before-midnight"))

        // When
        repeat(3) { state.moveSelection(-1) }

        // Then
        assertEquals("before-midnight", messageOf(source, requireNotNull(state.selectedEntry.value)))

        // When
        repeat(20) { state.moveSelection(1) }

        // Then
        assertEquals("morning", messageOf(source, requireNotNull(state.selectedEntry.value)))
    }

    @Test
    fun `an empty result leaves the selection alone`() = runBlocking {
        // Given
        openFolder()
        state.setQuery("\"nothing matches this\"")
        assertEquals(0, awaitResults("\"nothing matches this\"").matchCount)

        // When
        state.moveSelection(1)

        // Then
        assertEquals(null, state.selectedEntry.value)
    }

    @Test
    fun `a typo is reported and the rest of the query still narrows`() = runBlocking {
        // Given
        openFolder()

        // When
        state.setQuery("category:Snyc level>=W")
        val results: Results = awaitResults("category:Snyc level>=W")

        // Then — the level term still applies; an empty screen would be a worse answer.
        assertTrue(results.problems.single().contains("Snyc"), results.problems.toString())
        assertEquals(2, results.matchCount)
    }

    @Test
    fun `results carry the query they were computed for`() = runBlocking {
        // Given — this is what makes the "catching up" indicator honest rather than a stale flag.
        openFolder()
        val settled: Results = awaitResults("")

        // Then
        assertEquals("", settled.query)
        assertFalse(state.isCatchingUp(settled))
        assertTrue(state.isCatchingUp(null))
    }

    @Test
    fun `a folder with nothing recognisable fails with a message that says what to do`() = runBlocking {
        // Given
        val empty = File(folder, "elsewhere").apply { mkdirs() }
        File(empty, "notes.txt").writeText("nothing structured\n")

        // When
        state.open(listOf(empty))
        val failure: OpenStatus.Failed = withTimeout(TIMEOUT_MILLIS) {
            state.status.first { status -> status is OpenStatus.Failed } as OpenStatus.Failed
        }

        // Then
        assertTrue(failure.message.contains("~/.loupe/profiles/"), failure.message)
    }

    private fun entryWithMessage(source: LogSource, message: String): Int =
        (0 until source.index.entryCount).first { entry -> messageOf(source, entry) == message }

    private fun messageOf(source: LogSource, entry: Int): String = source.text
        .decode(source.index.fileIdOf(entry), source.index.byteOffsets[entry], source.index.byteLengths[entry])
        .substringAfter("-> ")
        .substringBefore('\n')
        .substringBefore(' ')

    private suspend fun openFolder(): LogSource {
        state.open(listOf(folder))
        return withTimeout(TIMEOUT_MILLIS) { requireNotNull(state.source.first { source -> source != null }) }
    }

    /** Waits for the results computed for [query] — the flow debounces and runs off-thread. */
    private suspend fun awaitResults(query: String): Results = withTimeout(TIMEOUT_MILLIS) {
        state.results.first { results -> results != null && results.query == query }!!
    }

    private fun writeDay(name: String, vararg lines: String) {
        File(folder, name).writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
