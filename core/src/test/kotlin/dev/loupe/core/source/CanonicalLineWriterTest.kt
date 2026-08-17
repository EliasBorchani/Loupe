package dev.loupe.core.source

import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.ProfileRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import java.util.regex.Matcher

/**
 * What the writer writes, its own profile reads back — value for value.
 *
 * `AdapterProfilePairingTest` compares regex *text*. This closes the other half: awkward values go
 * through [CanonicalLineWriter], and the bundled profile's own compiled `Pattern` has to match the
 * line and hand back exactly what went in. That is the assertion the two adapters lacked, and it is
 * why `DisplayPowerController[0\]` reached a facet escaped before anyone noticed.
 *
 * The awkward set is drawn from real captures: tags that hold `]`, `[` and `: `, an application id
 * with colons, a 7-digit pid, an empty field, `« »` and an emoji, a message opening with `[`, a
 * message holding `] `, and a stack trace arriving as one multi-line message.
 */
class CanonicalLineWriterTest {

    companion object {
        private val PAIRED: List<CanonicalSourceAdapter> = SourceAdapters.all.filterIsInstance<CanonicalSourceAdapter>()

        private val BUNDLED: ProfileRegistry = ProfileRegistry.bundled()

        private val INSTANT: Instant = Instant.parse("2026-08-17T12:48:55.620Z")

        private val AWKWARD_MESSAGES: List<String> = listOf(
            "plain",
            "[GF_HAL] a message that opens with a bracket",
            "steps: 100 -> 250] and a ] space, which a greedy field would swallow",
            "« Écran intégré » renderFrameRate=30.000002 🔍",
            "",
            "Failed to deliver transaction\n\tat android.os.Binder.execTransact(Binder.java:1345)\n\tat com.example.Foo.bar(Foo.java:12)",
        )
    }

    @Test
    fun `every column survives the round trip through its own profile`() {
        PAIRED.forEach { adapter ->
            // Given
            val profile: CompiledProfile = BUNDLED.profiles.single { candidate -> candidate.name == adapter.emittedProfileName }

            AWKWARD_MESSAGES.forEach { message ->
                valueSetsFor(adapter.shape).forEach { values ->
                    // When
                    val lines: List<String> = write(adapter.shape, values, message)
                    val matcher: Matcher = profile.pattern.matcher(lines.first())

                    // Then
                    val where = "${adapter.emittedProfileName} — '${lines.first()}'"
                    assertTrue(matcher.matches(), "$where: its own profile does not match what it wrote")
                    values.forEach { (column, value) ->
                        assertEquals(value, matcher.group(column.field), "$where: ${column.field}")
                    }
                    assertEquals(message.lineSequence().first(), matcher.group(CanonicalLine.MESSAGE_GROUP), where)

                    // And a multi-line message stays one entry, which is what the indent buys.
                    assertEquals(message.lines().size, lines.size, where)
                    lines.drop(1).forEachIndexed { position, line ->
                        assertTrue(line.startsWith(CanonicalLine.CONTINUATION_INDENT), "$where: continuation $position")
                        assertEquals(message.lines()[position + 1], line.removePrefix(CanonicalLine.CONTINUATION_INDENT), where)
                    }
                }
            }
        }
    }

    @Test
    fun `a value no column claimed goes on an indented line of its own`() {
        PAIRED.forEach { adapter ->
            // Given
            val values: Map<CanonicalColumn, String> = valueSetsFor(adapter.shape).first()

            // When
            val lines: List<String> = write(adapter.shape, values, "pull done", trailing = "userId=42 durationMs=1180")

            // Then — searchable, rather than dropped for want of a column.
            assertEquals(2, lines.size, lines.toString())
            assertEquals(CanonicalLine.CONTINUATION_INDENT + "userId=42 durationMs=1180", lines[1])
        }
    }

    @Test
    fun `a newline inside a field is flattened, not folded`() {
        PAIRED.forEach { adapter ->
            // Given — never seen in a real tag, and cheap to refuse: a newline there would forge a
            // continuation line and swallow the entry after it.
            val bracketed: CanonicalColumn? = adapter.shape.columns
                .filterIsInstance<CanonicalColumn.Bracketed>()
                .lastOrNull()
            if (bracketed == null) return@forEach
            val values: Map<CanonicalColumn, String> = valueSetsFor(adapter.shape).first()
                .toMutableMap()
                .apply { put(bracketed, "two\nlines") }

            // When
            val lines: List<String> = write(adapter.shape, values, "hello")

            // Then
            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines.first().contains("[two lines]"), lines.first())
        }
    }

    @Test
    fun `a column left unset names itself`() {
        PAIRED.forEach { adapter ->
            // Given / When — the failure a stringly-typed row would have turned into a blank facet.
            val failure = assertThrows<IllegalStateException> {
                write(adapter.shape, values = emptyMap(), message = "hello")
            }

            // Then
            assertTrue(
                failure.message.orEmpty().contains(adapter.shape.columns.first().field),
                failure.message,
            )
        }
    }

    private fun write(
        shape: CanonicalLineShape,
        values: Map<CanonicalColumn, String>,
        message: String,
        trailing: String? = null,
    ): List<String> {
        val sink = StringWriter()
        // UTC, so the timestamp is the same on any machine — the zone is the writer's, not the test's.
        val writer = CanonicalLineWriter(shape, sink, ZoneOffset.UTC)
        values.forEach { (column, value) -> writer.set(column, value) }
        writer.write(INSTANT, message, trailing)
        return sink.toString().removeSuffix("\n").split('\n')
    }

    /** Three passes over each column, so every branch of every column type is written at least once. */
    private fun valueSetsFor(shape: CanonicalLineShape): List<Map<CanonicalColumn, String>> =
        (0..2).map { variant -> shape.columns.associateWith { column -> awkwardValue(column, variant) } }

    private fun awkwardValue(column: CanonicalColumn, variant: Int): String = when (column) {
        // 4194304 is the pid ceiling on a modern Android — seven digits, which fills the field.
        is CanonicalColumn.Padded -> listOf("1", "32155", "4194304")[variant]

        is CanonicalColumn.Code -> column.alphabet[variant % column.alphabet.length].toString()

        is CanonicalColumn.Vocabulary -> column.words[variant % column.words.size]

        is CanonicalColumn.Bracketed -> if (column.mayContainBracket) {
            listOf("DisplayPowerController[0]", "[GF_HAL][DelmarHalUtils]", "ASvc::AudioMetricDataReader")[variant]
        } else {
            listOf("system_server", "com.brave.browser:sandboxed_process0:14", "")[variant]
        }
    }
}
