package dev.loupe.desktop.state

import dev.loupe.core.index.LogIndex
import dev.loupe.core.source.LogSource
import dev.loupe.core.testing.writeLog
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
        // Two days on purpose, and the milliseconds are not round: a brush bound truncated to the
        // second lands somewhere other than where the pointer was released.
        writeDay(
            "2026-07-21",
            "2026-07-21 23:59:58.114 [I] [Sync] [PullVasistas] -> before-midnight",
            "2026-07-21 23:59:59.902 [W] [Sync] [PullVasistas] -> retry backoff=200",
            "2026-07-22 00:00:03.507 [E] [Sync] [PullVasistas] -> gave-up",
            "                       java.lang.IllegalStateException: nope",
        )
        writeDay(
            "2026-07-22",
            "2026-07-22 00:00:00.250 [I] [Wpp] [Session] -> midnight",
            "2026-07-22 00:00:01.318 [D] [Wpp] [Session] -> frame",
            "2026-07-22 10:00:00.641 [I] [Ui] [HomeActivity] -> morning",
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
    fun `a brush on a later day selects that day, not the first`() = runBlocking {
        // Given — the merged stream starts on the 21st; entries 4 and 5 are both on the 22nd.
        val source: LogSource = openFolder()

        // When
        state.setTimeWindow(source.index.timestamps[4], source.index.timestamps[5])

        // Then — the bounds carry the date. Written as a bare `HH:mm:ss` they were read as times on
        // the day the source *starts*, which put a brush over the 22nd onto the 21st and matched
        // nothing at all.
        assertTrue(state.query.value.contains("since:2026-07-22T"), state.query.value)
        assertEquals(listOf("gave-up", "morning"), messagesOf(awaitResults(state.query.value)))
    }

    @Test
    fun `the brushed band lands exactly where it was dragged`() = runBlocking {
        // Given — the band is drawn from the window the query compiled to, so a bound rounded down
        // to the second would redraw away from the pointer, and drop the entries in between.
        val source: LogSource = openFolder()

        // When
        state.setTimeWindow(source.index.timestamps[1], source.index.timestamps[4])
        val brushed: Results = awaitResults(state.query.value)

        // Then
        assertEquals(source.index.timestamps[1], brushed.windowSinceMillis)
        assertEquals(source.index.timestamps[4], brushed.windowUntilMillis)
        assertEquals(listOf("retry", "midnight", "frame", "gave-up"), messagesOf(brushed))
    }

    @Test
    fun `a facet opens past its top-N and folds back`() = runBlocking {
        // Given — the sidebar's "+ n more" said how many values were hidden and gave no way in.
        openFolder()
        assertFalse("tag" in state.expandedFacets.value)

        // When / Then
        state.toggleFacetExpanded("tag")
        assertTrue("tag" in state.expandedFacets.value)

        state.toggleFacetExpanded("tag")
        assertFalse("tag" in state.expandedFacets.value)
    }

    @Test
    fun `reopening folds every facet back`() = runBlocking {
        // Given — a facet left open by the file before this one is a control nobody touched.
        openFolder()
        state.toggleFacetExpanded("tag")

        // When
        state.open(listOf(File(folder, "2026-07-22")))
        withTimeout(TIMEOUT_MILLIS) { state.source.first { source -> source != null && source.files.size == 1 } }

        // Then
        assertEquals(emptySet<String>(), state.expandedFacets.value)
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
        openFolder()
        awaitResults("")

        // When / Then — the first press always lands somewhere useful.
        state.moveSelection(1)
        assertEquals("before-midnight", focusedMessage())

        state.clearSelection()
        state.moveSelection(-1)
        assertEquals("morning", focusedMessage())
    }

    @Test
    fun `arrows step through the result, not through the index`() = runBlocking {
        // Given — Sync matches entries 0, 1 and 4 of the merged stream; 2 and 3 are Wpp.
        openFolder()
        state.setQuery("category:Sync")
        awaitResults("category:Sync")
        state.selectAt(1)

        // When
        state.moveSelection(1)

        // Then — the next *match*, skipping the two entries the query excluded.
        assertEquals("gave-up", focusedMessage())
    }

    @Test
    fun `arrows stop at the ends instead of wrapping`() = runBlocking {
        // Given — in a list of nine million, teleporting to the far end is never what was meant.
        openFolder()
        awaitResults("")
        state.selectAt(0)

        // When / Then
        repeat(3) { state.moveSelection(-1) }
        assertEquals("before-midnight", focusedMessage())

        repeat(20) { state.moveSelection(1) }
        assertEquals("morning", focusedMessage())
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
        assertEquals(null, state.selection.value)
    }

    @Test
    fun `shift-click extends from the anchor, in either direction`() = runBlocking {
        // Given
        openFolder()
        awaitResults("")
        state.selectAt(3)

        // When — extend upwards.
        state.extendTo(1)

        // Then — the anchor stays put and the run covers both ends.
        val selection: Selection = requireNotNull(state.selection.value)
        assertEquals(3, selection.anchor)
        assertEquals(1, selection.focus)
        assertEquals(listOf(1, 2, 3), (1..3).filter { position -> position in selection })

        // When — extend the other way from the same anchor.
        state.extendTo(5)

        // Then
        assertEquals(3, requireNotNull(state.selection.value).size)
    }

    @Test
    fun `shift-arrow grows the run, a plain arrow collapses it`() = runBlocking {
        // Given
        openFolder()
        awaitResults("")
        state.selectAt(1)

        // When
        state.moveSelection(1, extend = true)
        state.moveSelection(1, extend = true)

        // Then
        assertEquals(3, requireNotNull(state.selection.value).size)

        // When
        state.moveSelection(1, extend = false)

        // Then — a plain arrow drops the run and starts a new one.
        assertEquals(1, requireNotNull(state.selection.value).size)
    }

    @Test
    fun `select-all covers the result, not the file`() = runBlocking {
        // Given
        openFolder()
        state.setQuery("category:Sync")
        awaitResults("category:Sync")

        // When
        state.selectAll()

        // Then — three of the six entries.
        assertEquals(3, requireNotNull(state.selection.value).size)
    }

    @Test
    fun `copying a run over a filter skips the entries the query excluded`() = runBlocking {
        // Given — Sync is entries 0, 1 and 4; the two Wpp entries sit in the gap.
        openFolder()
        state.setQuery("category:Sync")
        awaitResults("category:Sync")
        state.selectAll()

        // When
        val copied: String = requireNotNull(state.copySelection())

        // Then — "between these two" means between them *on screen*.
        assertEquals(3, copied.lines().count { line -> line.contains(" -> ") })
        assertTrue(copied.contains("before-midnight"))
        assertTrue(copied.contains("gave-up"))
        assertFalse(copied.contains("midnight\n") && copied.contains("frame"))
        assertFalse(copied.contains("-> frame"))
    }

    @Test
    fun `copying carries an entry's continuation lines with it`() = runBlocking {
        // Given — an entry is not a line; its stack trace comes along.
        openFolder()
        awaitResults("")
        state.selectAt(requireNotNull(state.results.value).positionOf(entryWithMessage("gave-up")))

        // When
        val copied: String = requireNotNull(state.copySelection())

        // Then
        assertTrue(copied.contains("java.lang.IllegalStateException"), copied)
    }

    @Test
    fun `a capped copy says so instead of truncating in silence`() = runBlocking {
        // Given
        openFolder()
        awaitResults("")
        state.selectAll()

        // When
        val copied: String = requireNotNull(state.copySelection(maxEntries = 2))

        // Then — a truncated paste that says nothing is how a bug report loses its cause.
        assertEquals(2, copied.lines().count { line -> line.contains(" -> ") })
        val notice: String = requireNotNull(state.notice.value)
        assertTrue(notice.contains("2 of 6"), notice)
        assertTrue(notice.contains("Export"), notice)
    }

    @Test
    fun `context reaches past the filter to what was actually around the line`() = runBlocking {
        // Given — only the errors on screen.
        val source: LogSource = openFolder()
        state.setQuery("level:E")
        assertEquals(1, awaitResults("level:E").matchCount)

        // When
        val context: IntRange = state.contextAround(entryWithMessage("gave-up"), radius = 2)

        // Then — the Debug and Wpp entries the query hid are exactly the point of it.
        val messages: List<String> = context.map { entry -> messageOf(source, entry) }
        assertTrue("frame" in messages, messages.toString())
        assertTrue("midnight" in messages, messages.toString())
        assertTrue("gave-up" in messages, messages.toString())
    }

    @Test
    fun `context stops at the ends of the file`() = runBlocking {
        // Given
        openFolder()
        awaitResults("")

        // When / Then — no negative index, no read past the end.
        assertEquals(0, state.contextAround(0, radius = 10).first)
        assertEquals(5, state.contextAround(5, radius = 10).last)
    }

    @Test
    fun `export writes the whole result, uncapped`() = runBlocking {
        // Given — the clipboard is capped, a file is not; this is the case someone reaches for it.
        openFolder()
        state.setQuery("category:Sync")
        awaitResults("category:Sync")
        val target = File(folder, "out.txt")

        // When
        state.export(target)
        val notice: String = withTimeout(TIMEOUT_MILLIS) {
            state.notice.first { text -> text != null && text.startsWith("Exported") }!!
        }

        // Then
        assertTrue(notice.contains("3 entries"), notice)
        val written: String = target.readText()
        assertEquals(3, written.lines().count { line -> line.contains(" -> ") })
        assertTrue(written.contains("java.lang.IllegalStateException")) { "an entry's continuations come with it" }
        assertFalse(written.contains("-> frame")) { "the export is the filter, not the file" }
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
        assertTrue(failure.message.contains(".loupe/profiles"), failure.message)
    }

    /** The messages a result selected, in order — a count cannot see that they are the wrong ones. */
    private fun messagesOf(results: Results): List<String> {
        val source: LogSource = requireNotNull(state.source.value)
        return (0 until results.matchCount).map { position -> messageOf(source, results.matches[position]) }
    }

    private fun entryWithMessage(message: String): Int {
        val source: LogSource = requireNotNull(state.source.value)
        return (0 until source.index.entryCount).first { entry -> messageOf(source, entry) == message }
    }

    /** The message of the row the detail pane would describe. */
    private fun focusedMessage(): String {
        val results: Results = requireNotNull(state.results.value)
        val focus: Int = requireNotNull(state.selection.value).focus
        return messageOf(requireNotNull(state.source.value), results.matches[focus])
    }

    private fun messageOf(source: LogSource, entry: Int): String = source.rawText(entry)
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
        writeLog(folder, name, *lines)
    }
}
