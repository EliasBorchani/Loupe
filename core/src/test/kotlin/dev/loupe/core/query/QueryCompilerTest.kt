package dev.loupe.core.query

import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.io.TextSources
import dev.loupe.core.parse.ProfileEntryParser
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.ProfileRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId

/**
 * The query language, end to end against a real index.
 *
 * Every case asserts on the *messages* the query selects rather than on counts, because a filter
 * that returns the right number of the wrong entries is the failure mode that matters here.
 */
class QueryCompilerTest {

    companion object {
        private val WITHINGS: CompiledProfile = ProfileRegistry.bundled().profiles
            .single { profile -> profile.name == "withings-healthmate" }

        private val ZONE: ZoneId = ZoneId.of("Europe/Paris")

        /** One line per message below, so an assertion can name what it expects. */
        private val CORPUS: List<String> = listOf(
            "2026-07-22 10:00:00.000 [V] [Wpp] [Session] -> frame-in",
            "2026-07-22 10:30:00.000 [D] [Sync] [PullVasistas] -> pull-start",
            "2026-07-22 11:00:00.000 [I] [Sync] [PullVasistas] -> pull-done",
            "2026-07-22 11:30:00.000 [W] [Sync] [PushMeasures] -> push-retry backoff=200",
            "2026-07-22 12:00:00.000 [E] [Wpp] [Session] -> session-lost",
            "2026-07-22 12:30:00.000 [E] [ERROR] -> reported-no-category",
            "2026-07-22 13:00:00.000 [I] [Ui] [HomeActivity] -> ui-resumed",
        )
    }

    @TempDir
    lateinit var temporaryDirectory: File

    private lateinit var index: LogIndex
    private lateinit var text: TextSources
    private lateinit var compiler: QueryCompiler

    @BeforeEach
    fun setUp() {
        val file = File(temporaryDirectory, "2026-07-22")
        file.writeText(CORPUS.joinToString("\n", postfix = "\n"))
        index = LogIndexer(ProfileEntryParser(WITHINGS)).index(file)
        text = TextSources.of(file)
        compiler = QueryCompiler(index, ZONE)
    }

    @Test
    fun `an empty query keeps everything`() {
        assertEquals(CORPUS.size, select("").size)
    }

    @Test
    fun `level comparison walks the declared severity scale`() {
        // Given / When / Then — V D I W E, so >=W is W and E.
        assertEquals(listOf("push-retry", "session-lost", "reported-no-category"), messagesOf(select("level>=W")))
        assertEquals(listOf("session-lost", "reported-no-category"), messagesOf(select("level>W")))
        assertEquals(listOf("frame-in"), messagesOf(select("level<D")))
    }

    @Test
    fun `an enumeration of levels is an OR`() {
        // Given / When / Then — results stay in file order, which is what the list shows.
        assertEquals(listOf("frame-in", "pull-done", "ui-resumed"), messagesOf(select("level:V,I")))
    }

    @Test
    fun `a facet term matches by value, case-insensitively`() {
        assertEquals(3, select("category:sync").size)
        assertEquals(listOf("pull-start", "pull-done", "push-retry"), messagesOf(select("category:Sync")))
    }

    @Test
    fun `facet values comma-separate as an OR and terms AND together`() {
        assertEquals(5, select("category:Sync,Wpp").size)
        assertEquals(listOf("push-retry"), messagesOf(select("category:Sync level>=W")))
    }

    @Test
    fun `negating a facet keeps entries that have no value for it`() {
        // Given — one entry has no category at all (report() writes ERROR in the tag slot).
        // When / Then — "not Sync" must include it; excluding it would hide errors.
        val messages: List<String> = messagesOf(select("-category:Sync"))
        assertTrue("reported-no-category" in messages) { "an entry with no category is not a Sync entry" }
        assertFalse("pull-start" in messages)
    }

    @Test
    fun `a tilde matches facet values by substring`() {
        assertEquals(listOf("pull-start", "pull-done", "push-retry"), messagesOf(select("tag:~Pu")))
    }

    @Test
    fun `bare words search the whole entry text`() {
        assertEquals(listOf("push-retry"), messagesOf(select("backoff")))
        assertEquals(listOf("push-retry"), messagesOf(select("\"backoff=200\"")))
    }

    @Test
    fun `a regex literal searches the entry text`() {
        assertEquals(listOf("pull-start", "pull-done"), messagesOf(select("/pull-(start|done)/")))
    }

    @Test
    fun `an absolute time window bounds the range`() {
        assertEquals(
            listOf("pull-done", "push-retry"),
            messagesOf(select("since:11:00 until:11:30")),
        )
    }

    @Test
    fun `a relative window counts back from the last entry, not from now`() {
        // Given — the corpus ends at 13:00 on a date long past. Counting from the wall clock
        // would return nothing at all.
        // When / Then
        assertEquals(listOf("session-lost", "reported-no-category", "ui-resumed"), messagesOf(select("since:-1h")))
    }

    @Test
    fun `an unknown field is reported instead of silently matching nothing`() {
        // Given / When
        val compiled: CompiledQuery = compiler.compile("colour:red")

        // Then
        assertFalse(compiled.isValid)
        assertTrue(compiled.problems.single().contains("colour"), compiled.problems.toString())
        assertTrue(compiled.problems.single().contains("category"), "it should list what is available")
    }

    @Test
    fun `a value absent from this file is reported as such`() {
        // Given — a typo, not an empty result.
        val compiled: CompiledQuery = compiler.compile("category:Snyc")

        // Then
        assertFalse(compiled.isValid)
        assertTrue(compiled.problems.single().contains("Snyc"), compiled.problems.toString())
    }

    @Test
    fun `an unparsable time says what it accepts`() {
        val compiled: CompiledQuery = compiler.compile("since:yesterday")

        assertFalse(compiled.isValid)
        assertTrue(compiled.problems.single().contains("-2h"), compiled.problems.toString())
    }

    @Test
    fun `sequential and parallel evaluation agree`() {
        // Given
        val compiled: CompiledQuery = compiler.compile("level>=D category:Sync,Wpp")
        val sequential = IntArray(index.entryCount)
        val parallel = IntArray(index.entryCount)

        // When
        val sequentialCount: Int = compiled.filter.evaluate(index, text, sequential)
        val parallelCount: Int = compiled.filter.evaluateParallel(index, text, parallel, workerCount = 4)

        // Then
        assertEquals(sequentialCount, parallelCount)
        assertEquals(sequential.take(sequentialCount), parallel.take(parallelCount))
    }

    private fun select(query: String): List<Int> {
        val compiled: CompiledQuery = compiler.compile(query)
        val destination = IntArray(index.entryCount)
        val matched: Int = compiled.filter.evaluate(index, text, destination)
        return destination.take(matched)
    }

    /** The trailing message word of each selected entry, which is unique per corpus line. */
    private fun messagesOf(entries: List<Int>): List<String> = entries.map { entry ->
        text.decode(index.fileIdOf(entry), index.byteOffsets[entry], index.byteLengths[entry]).substringAfter("-> ").substringBefore(' ')
    }
}
