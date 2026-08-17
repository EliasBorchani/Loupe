package dev.loupe.core.query

import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.io.TextSources
import dev.loupe.core.parse.ProfileEntryParser
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.testing.BundledProfile
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
        private val WITHINGS: CompiledProfile = BundledProfile.withings

        /**
         * The machine's zone, deliberately — not a fixed one.
         *
         * The bundled profile declares `zone = "local"`, so the index is built in the machine's
         * zone; pinning the *query* side to Europe/Paris made `since:11:00` mean something else
         * from the timestamps it was compared against. It agreed on a CEST laptop and failed on a
         * UTC runner. The two sides have to read the same clock, and `Test { systemProperty
         * "user.timezone" }` fixes which clock that is.
         */
        private val ZONE: ZoneId = ZoneId.systemDefault()

        /**
         * The first code block a visitor to the repository sees.
         *
         * It read `cat:Sync` until it was found to be fiction: `resolveFacetIndex` matches a facet's
         * name or its label and there is no alias table, so the headline example answered "Unknown
         * field 'cat'". Every test wrote `category:`, so nothing caught it. Now the example is
         * compiled here, and pinned to the file it is quoted from.
         */
        private const val README_EXAMPLE: String = "level>=W category:Sync since:-2h \"timeout\""

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
    fun `the example in the README compiles with no problems`() {
        // Given
        val readme = File(System.getProperty("loupe.repositoryRoot"), "README.md")

        // When
        val compiled: CompiledQuery = compiler.compile(README_EXAMPLE)

        // Then — a documented example that is also a test cannot rot.
        assertEquals(emptyList<String>(), compiled.problems)
        assertTrue(
            readme.readText().contains(README_EXAMPLE),
            "README.md no longer quotes '$README_EXAMPLE'. Update the constant — and check the new text compiles.",
        )
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
    fun `a term with no value means the same thing wherever it sits`() {
        // Given / When — `problems` is the whole query's list, and resolveFacet consulted it to
        // decide its own term. So `category:` selected nothing on its own, silently, and was dropped
        // entirely after any earlier failure: one term, two meanings, decided by its neighbour.
        val alone: CompiledQuery = compiler.compile("category:")
        val afterAFailure: CompiledQuery = compiler.compile("level:Nope category:")

        // Then — dropped and reported, both times. Dropping a failed term is the documented policy;
        // doing it only sometimes was not.
        assertTrue(alone.problems.any { problem -> problem.contains("'category' needs a value") }, alone.problems.toString())
        assertTrue(afterAFailure.problems.any { problem -> problem.contains("'category' needs a value") }, afterAFailure.problems.toString())
        assertEquals(CORPUS.size, select("category:").size)
        assertEquals(CORPUS.size, select("level:Nope category:").size)
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
