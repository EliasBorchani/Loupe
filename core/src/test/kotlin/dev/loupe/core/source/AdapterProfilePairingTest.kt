package dev.loupe.core.source

import dev.loupe.core.parse.WithingsFormat
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.FieldRole
import dev.loupe.core.profile.LogProfileSpec
import dev.loupe.core.profile.ProfileRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneOffset

/**
 * An adapter's writer and its profile are two halves of one format, and this holds them together.
 *
 * Both halves used to be written out by hand: the adapter decided a layout in Kotlin, the profile
 * described the same layout in a regex, and nothing checked that they agreed. Now the regexes are
 * derived from [CanonicalLineShape] and this test asserts the shipped files equal the derivation —
 * so the TOMLs stay hand-written, readable, and offered in the template menu, while a drift becomes
 * a build failure rather than a format nobody can read.
 *
 * A failure prints the derived string, so the fix is a paste.
 */
class AdapterProfilePairingTest {

    @TempDir
    lateinit var folder: File

    companion object {
        private val PAIRED: List<CanonicalSourceAdapter> = SourceAdapters.all.filterIsInstance<CanonicalSourceAdapter>()

        private fun specOf(profileName: String): LogProfileSpec = ProfileRegistry.bundledFileNames()
            .mapNotNull { fileName -> ProfileRegistry.bundledSource(fileName) }
            .map { source -> CompiledProfile.parse(source) }
            .single { spec -> spec.name == profileName }
    }

    @Test
    fun `there is an adapter to test`() {
        // Given / When / Then — a filterIsInstance that quietly returns nothing would make every
        // assertion below vacuously true.
        assertEquals(listOf("android-studio-logcat", "json-lines"), PAIRED.map { adapter -> adapter.emittedProfileName })
    }

    @Test
    fun `the shipped regex is the one the shape derives`() {
        PAIRED.forEach { adapter ->
            // Given
            val spec: LogProfileSpec = specOf(adapter.emittedProfileName)

            // When / Then
            assertEquals(
                CanonicalLine.parseRegex(adapter.shape),
                spec.parse.regex,
                "${adapter.emittedProfileName}: parse.regex has drifted from ${adapter.name}'s shape",
            )
            assertEquals(
                CanonicalLine.opensRegex(adapter.shape),
                spec.entry.opens,
                "${adapter.emittedProfileName}: entry.opens has drifted",
            )
            assertEquals(
                CanonicalLine.continuesRegex(),
                spec.entry.continues,
                "${adapter.emittedProfileName}: entry.continues has drifted",
            )
            assertTrue(spec.entry.stripContinuationIndent, "${adapter.emittedProfileName}: must strip the indent")
        }
    }

    @Test
    fun `the profile declares exactly the fields the shape writes`() {
        PAIRED.forEach { adapter ->
            // Given
            val spec: LogProfileSpec = specOf(adapter.emittedProfileName)
            val expected: Set<String> =
                adapter.shape.columns.map { column -> column.field }.toSet() +
                    setOf(CanonicalLine.TIMESTAMP_GROUP, CanonicalLine.MESSAGE_GROUP)

            // When / Then — a group with no field, or a field with no group, is what CompiledProfile
            // rejects at load; this says the *set* is the one the writer fills.
            assertEquals(expected, spec.fields.keys, adapter.emittedProfileName)
            assertEquals(
                CanonicalLine.TIMESTAMP_PATTERN,
                spec.fields.getValue(CanonicalLine.TIMESTAMP_GROUP).format,
                adapter.emittedProfileName,
            )
        }
    }

    @Test
    fun `the profile's level scale is the one the writer can produce`() {
        PAIRED.forEach { adapter ->
            // Given
            val spec: LogProfileSpec = specOf(adapter.emittedProfileName)
            val declared: List<String> = spec.fields.values
                .single { field -> field.role == FieldRole.Level }
                .order
                .orEmpty()

            // When
            val writable: List<String> = when (val column = adapter.shape.columns.single { candidate -> candidate.field == "level" }) {
                is CanonicalColumn.Vocabulary -> column.words
                is CanonicalColumn.Code -> column.alphabet.map { letter -> letter.toString() }
                is CanonicalColumn.Bracketed, is CanonicalColumn.Padded ->
                    error("${adapter.emittedProfileName}: a level column has to be a Code or a Vocabulary")
            }

            // Then — a writer that can emit a word the profile does not list produces lines that go
            // silently unrecognised, which is the worst failure this format has.
            assertTrue(
                declared.containsAll(writable),
                "${adapter.emittedProfileName}: the writer can emit ${writable - declared.toSet()}, which the profile does not declare",
            )
        }
    }

    @Test
    fun `a paired profile declares no priority`() {
        PAIRED.forEach { adapter ->
            // Given / When / Then — priority is a tie-break between profiles competing on detection.
            // A paired profile is named by its adapter and never enters that race, so a priority here
            // would be reasoning about something that no longer happens.
            assertEquals(0, specOf(adapter.emittedProfileName).priority, adapter.emittedProfileName)
        }
    }

    @Test
    fun `a profile an adapter speaks for is kept out of detection`() {
        // Given — plain text that happens to be shaped exactly like the NDJSON adapter's output. It
        // is not a converted file, so the profile that describes converted files must not claim it.
        val lookalike = File(folder, "lookalike.log")
        lookalike.writeText(
            (0..9).joinToString("\n", postfix = "\n") { minute ->
                "2026-08-13 17:2%d:15.293 [DEBUG] [general] a line that only looks converted".format(minute)
            },
        )

        // When
        LogSourceLoader.open(listOf(lookalike)).use { source ->
            // Then — before pinning, `json-lines` would have scored 1.0 on this and won.
            assertEquals("generic-timestamped", source.profile.name)
            assertTrue(source.converted.isEmpty(), "nothing was converted, so nothing should say it was")
        }
    }

    @Test
    fun `the paired profiles are exactly the ones excluded from detection`() {
        // Given / When / Then — derived from the adapters, so the two cannot disagree.
        assertEquals(
            PAIRED.map { adapter -> adapter.emittedProfileName }.toSet(),
            SourceAdapters.pairedProfileNames,
        )
        assertEquals(
            ProfileRegistry.bundled().profiles.size - PAIRED.size,
            ProfileRegistry.bundled().excluding(SourceAdapters.pairedProfileNames).profiles.size,
        )
    }

    @Test
    fun `the timestamp width is the pattern's, not a number someone wrote down`() {
        // Given — an instant with a two-digit month, day, hour and a full millisecond field.
        val instant: Instant = Instant.parse("2026-08-17T12:48:55.620Z")

        // When
        val formatted: String = DateTimeFormatter.ofPattern(CanonicalLine.TIMESTAMP_PATTERN)
            .withZone(ZoneOffset.UTC)
            .format(instant)

        // Then — the continuation indent is this width, in Kotlin and in three TOML regexes.
        assertEquals(CanonicalLine.TIMESTAMP_WIDTH, formatted.length, formatted)
        assertEquals(CanonicalLine.TIMESTAMP_WIDTH, CanonicalLine.CONTINUATION_INDENT.length)

        // HealthMate's FileLogger happens to use the same width. Deliberately not the same constant —
        // that would say their format moves when ours does — so the coincidence is asserted instead.
        assertEquals(CanonicalLine.TIMESTAMP_WIDTH, WithingsFormat.TIMESTAMP_LENGTH)
    }

    @Test
    fun `a profile that strips a fixed indent strips exactly the timestamp width`() {
        // Given — every bundled profile, including `withings`, which no adapter writes: the indent is
        // a property of the timestamp's width, whoever wrote the file. Profiles that accept `^\s` —
        // `generic-timestamped`, `syslog` — are exempt by construction rather than by name: they are
        // guessing at a format instead of writing it, so no fixed width is theirs to agree with.
        val fixedIndent = Regex("""^\^ \{(\d+)}${'$'}""")
        val specs: List<LogProfileSpec> = ProfileRegistry.bundledFileNames()
            .mapNotNull { fileName -> ProfileRegistry.bundledSource(fileName) }
            .map { source -> CompiledProfile.parse(source) }

        // When
        val widths: Map<String, Int> = specs.mapNotNull { spec ->
            fixedIndent.matchEntire(spec.entry.continues.orEmpty())
                ?.let { match -> spec.name to match.groupValues[1].toInt() }
        }.toMap()

        // Then — three profiles today, and a vacuous pass would be worse than a failure.
        assertEquals(
            setOf("withings-healthmate", "android-studio-logcat", "json-lines"),
            widths.keys,
            widths.toString(),
        )
        widths.forEach { (name, width) -> assertEquals(CanonicalLine.TIMESTAMP_WIDTH, width, name) }
    }
}
