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
