package dev.loupe.core.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ProfileCompilationTest {

    @Nested
    inner class NamedGroupNumbering {

        @Test
        fun `numbers named groups alongside the unnamed ones`() {
            // Given — a mix of named, plain and non-capturing groups.
            val source = """^(?<a>x)(y)(?:z)(?<b>w)$"""

            // When
            val indexes: Map<String, Int> = NamedGroups.indexesOf(source)

            // Then — the plain (y) still consumes number 2.
            assertEquals(mapOf("a" to 1, "b" to 3), indexes)
        }

        @Test
        fun `does not mistake a lookbehind for a named group`() {
            // Given
            val source = """^(?<=a)(?<real>b)(?<!c)$"""

            // When
            val indexes: Map<String, Int> = NamedGroups.indexesOf(source)

            // Then
            assertEquals(mapOf("real" to 1), indexes)
        }

        @Test
        fun `ignores parentheses inside a character class or behind a backslash`() {
            // Given
            val source = """^[(](?<real>b)\($"""

            // When
            val indexes: Map<String, Int> = NamedGroups.indexesOf(source)

            // Then
            assertEquals(mapOf("real" to 1), indexes)
        }

        @Test
        fun `matches what java actually numbers, on the HealthMate regex`() {
            // Given — the bundled profile's real regex, which has an optional group.
            val source = WithingsProfileFixture.REGEX
            val pattern = java.util.regex.Pattern.compile(source)
            val indexes: Map<String, Int> = NamedGroups.indexesOf(source)

            // When
            val matcher = pattern.matcher("2026-07-22 10:00:00.000 [D] [Sync] [tag] -> hello")
            assertTrue(matcher.matches())

            // Then — each derived number must select the same text as the name does.
            indexes.forEach { (name, number) ->
                assertEquals(matcher.group(name), matcher.group(number), "group '$name'")
            }
        }
    }

    @Nested
    inner class Timestamps {

        private val resolver = LocalTimestampResolver(ZoneOffset.UTC)

        @Test
        fun `reads a fixed-width pattern without a formatter`() {
            // Given
            val format = TimestampFormat.compile("yyyy-MM-dd HH:mm:ss.SSS", "utc")

            // When
            val millis: Long = format.read("2026-07-22 12:00:00.500", 0, 23, resolver)

            // Then
            assertTrue(format.isFastPath)
            assertEquals(
                LocalDateTime.of(2026, 7, 22, 12, 0, 0, 500_000_000).toInstant(ZoneOffset.UTC).toEpochMilli(),
                millis,
            )
        }

        @Test
        fun `counts offsets in output characters, not pattern characters`() {
            // Given — the quoted 'T' is three pattern characters and one of text. Getting this
            // wrong shifts every field after it.
            val format = TimestampFormat.compile("yyyy-MM-dd'T'HH:mm:ss.SSS", "utc")

            // When
            val millis: Long = format.read("2026-07-22T12:34:56.789", 0, 23, resolver)

            // Then
            assertTrue(format.isFastPath)
            assertEquals(
                LocalDateTime.of(2026, 7, 22, 12, 34, 56, 789_000_000).toInstant(ZoneOffset.UTC).toEpochMilli(),
                millis,
            )
        }

        @Test
        fun `scales a fractional second to milliseconds by its width`() {
            // Given
            val hundredths = TimestampFormat.compile("yyyy-MM-dd HH:mm:ss.SS", "utc")
            val micros = TimestampFormat.compile("yyyy-MM-dd HH:mm:ss.SSSSSS", "utc")

            // When
            val fromHundredths: Long = hundredths.read("2026-07-22 00:00:00.25", 0, 22, resolver)
            val fromMicros: Long = micros.read("2026-07-22 00:00:00.250000", 0, 26, resolver)

            // Then — .25 is 250 ms, not 25.
            assertEquals(fromHundredths, fromMicros)
            assertEquals(250L, fromHundredths % 1000)
        }

        @Test
        fun `falls back to a formatter for a named month`() {
            // Given
            val format = TimestampFormat.compile("dd MMM yyyy HH:mm:ss", "utc")

            // When
            val millis: Long = format.read("22 Jul 2026 12:00:00", 0, 20, LocalTimestampResolver(ZoneOffset.UTC))

            // Then
            assertFalse(format.isFastPath) { "a named month cannot be read at a fixed offset" }
            assertEquals(
                LocalDateTime.of(2026, 7, 22, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
                millis,
            )
        }

        @Test
        fun `stays correct across a daylight-saving transition`() {
            // Given — Paris moves from UTC+1 to UTC+2 at 02:00 on 29 March 2026. Caching the day
            // instead of the hour would put every later entry an hour out.
            val format = TimestampFormat.compile("yyyy-MM-dd HH:mm:ss.SSS", "Europe/Paris")
            val paris: ZoneId = ZoneId.of("Europe/Paris")
            val shared = format.newResolver()

            // When — read before and after the jump with the same resolver.
            val before: Long = format.read("2026-03-29 01:30:00.000", 0, 23, shared)
            val after: Long = format.read("2026-03-29 03:30:00.000", 0, 23, shared)

            // Then
            assertEquals(LocalDateTime.of(2026, 3, 29, 1, 30).atZone(paris).toInstant().toEpochMilli(), before)
            assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30).atZone(paris).toInstant().toEpochMilli(), after)
            assertEquals(3_600_000L, after - before) { "01:30 and 03:30 are one real hour apart that day" }
        }
    }

    @Nested
    inner class Predicates {

        @Test
        fun `reduces a fixed indent to a literal prefix`() {
            // Given / When
            val predicate: LinePredicate = LinePredicate.compileExact("""^ {23}""")

            // Then
            assertInstanceOf(LinePredicate.LiteralPrefix::class.java, predicate)
            assertTrue(predicate.matches("                       x".toByteArray(), 0, 24))
            assertFalse(predicate.matches("                      x".toByteArray(), 0, 23))
        }

        @Test
        fun `derives positional constraints from a date prefix`() {
            // Given — the HealthMate `entry.opens`, which no literal prefix can express.
            val predicate: LinePredicate = requireNotNull(
                LinePredicate.compileNecessary("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \["""),
            )

            // Then
            assertInstanceOf(LinePredicate.PositionalPrefix::class.java, predicate)
            assertTrue(predicate.isFastPath)
            assertTrue(accepts(predicate, "2026-07-22 10:00:00.000 [D] [Sync] [tag] -> x"))
            assertFalse(accepts(predicate, "                       continuation"))
            assertFalse(accepts(predicate, "=== 2026-07-22 ==="))
            assertFalse(accepts(predicate, "2026-07-22 10:00:00.000 (D) …")) { "position 24 must be '['" }
        }

        @Test
        fun `never rejects a line the full regex would accept`() {
            // Given — the necessary condition and the real pattern, over a mixed corpus.
            val predicate: LinePredicate = requireNotNull(LinePredicate.compileNecessary(WithingsProfileFixture.OPENS))
            val pattern = java.util.regex.Pattern.compile(WithingsProfileFixture.REGEX, java.util.regex.Pattern.DOTALL)
            val lines: List<String> = listOf(
                "2026-07-22 10:00:00.000 [D] [Sync] [tag] -> ok",
                "2026-07-22 10:00:00.000 [E] [ERROR] -> reported",
                "2026-07-22 10:00:00.000 [W] [ou1] -> obfuscated",
                "2026-12-31 23:59:59.999 [V] [Wpp] [t] -> ",
                "                       continuation",
                "=== 2026-07-22 ===",
                "",
            )

            // When / Then — the pre-filter may over-accept, never under-accept.
            lines.forEach { line ->
                if (pattern.matcher(line).matches()) {
                    assertTrue(accepts(predicate, line)) { "pre-filter wrongly rejected: $line" }
                }
            }
        }

        @Test
        fun `refuses to position anything after a multi-byte literal`() {
            // Given — 'é' is two bytes, so every offset after it would be wrong.
            val predicate: LinePredicate? = LinePredicate.compileNecessary("""^é\d{2}""")

            // Then — derivation stops before the literal rather than producing bad offsets.
            assertNull(predicate)
        }

        private fun accepts(predicate: LinePredicate, line: String): Boolean {
            val bytes: ByteArray = line.toByteArray()
            return predicate.matches(bytes, 0, bytes.size)
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `reports every problem at once`() {
            // Given — a regex group with no field, a field with no group, and no timestamp.
            val spec = CompiledProfile.parse(
                """
                name = "broken"
                [parse]
                regex = '''^(?<ts>\S+) (?<orphanGroup>\S+)${'$'}'''
                [fields.ts]
                role = "facet"
                [fields.orphanField]
                role = "message"
                """.trimIndent(),
            )

            // When
            val failure: InvalidProfileException = assertThrows(InvalidProfileException::class.java) {
                CompiledProfile.compile(spec)
            }

            // Then
            assertTrue(failure.problems.any { problem -> problem.contains("orphanField") }, failure.message)
            assertTrue(failure.problems.any { problem -> problem.contains("orphanGroup") }, failure.message)
            assertTrue(failure.problems.any { problem -> problem.contains("timestamp") }, failure.message)
        }

        @Test
        fun `refuses a key the format does not have`() {
            // Given — `colors` and `values` were accepted here and then read by nothing, for four
            // milestones. Removing them is only safe because an unknown key fails loudly; if it were
            // ignored, every profile that still declares one would silently lose the meaning it
            // thought it had.
            val source: String = """
                name = "stale"
                [parse]
                regex = '''^(?<ts>\S+) (?<message>.*)${'$'}{'$'}'''
                [fields.ts]
                role = "timestamp"
                format = "yyyy-MM-dd"
                [fields.message]
                role = "message"
                colors = { W = "warning" }
            """.trimIndent()

            // When / Then
            val failure: Exception = assertThrows(Exception::class.java) { CompiledProfile.parse(source) }
            assertTrue(failure.message.orEmpty().contains("colors"), failure.message)
        }

        @Test
        fun `refuses a level field with no scale`() {
            // Given
            val spec = CompiledProfile.parse(
                """
                name = "no-scale"
                [parse]
                regex = '''^(?<ts>\S+) (?<level>\w)${'$'}'''
                [fields.ts]
                role = "timestamp"
                format = "yyyy-MM-dd"
                [fields.level]
                role = "level"
                """.trimIndent(),
            )

            // When / Then — without an order there is nothing for `level>=W` to compare.
            val failure: InvalidProfileException = assertThrows(InvalidProfileException::class.java) {
                CompiledProfile.compile(spec)
            }
            assertTrue(failure.problems.any { problem -> problem.contains("order") }, failure.message)
        }
    }

    @Nested
    inner class Detection {

        @TempDir
        lateinit var temporaryDirectory: File

        @Test
        fun `recognises a HealthMate day file and reports its score`() {
            // Given
            val file = File(temporaryDirectory, "2026-07-22")
            file.writeText(
                listOf(
                    "2026-07-22 10:00:00.000 [D] [Sync] [tag] -> one",
                    "                       continued",
                    "2026-07-22 10:00:01.000 [W] [Wpp] [tag] -> two",
                    "=== 2026-07-22 ===",
                ).joinToString("\n", postfix = "\n"),
            )

            // When
            val match: ProfileMatch = requireNotNull(ProfileRegistry.bundled().best(file))

            // Then
            assertEquals("withings-healthmate", match.profile.name)
            assertEquals(1.0, match.score)
            assertTrue(match.filenameMatches)
            assertEquals(2, match.entryLines)
        }

        @Test
        fun `declines a file it does not understand rather than guessing`() {
            // Given — plausible-looking logs in another format entirely.
            val file = File(temporaryDirectory, "server.log")
            file.writeText(
                listOf(
                    "127.0.0.1 - - [22/Jul/2026:10:00:00 +0000] \"GET / HTTP/1.1\" 200 512",
                    "127.0.0.1 - - [22/Jul/2026:10:00:01 +0000] \"GET /a HTTP/1.1\" 404 12",
                ).joinToString("\n", postfix = "\n"),
            )

            // When
            val match: ProfileMatch? = ProfileRegistry.bundled().best(file)

            // Then — silently mis-detecting would show a plausible but wrong table.
            assertNull(match)
        }

        @Test
        fun `stops reading after the sample rather than scanning the whole file`() {
            // Given — far more lines than detect.sample.
            val file = File(temporaryDirectory, "2026-07-22")
            file.writeText((1..50_000).joinToString("\n", postfix = "\n") { index ->
                "2026-07-22 10:00:00.%03d [D] [Sync] [tag] -> line $index".format(index % 1000)
            })

            // When
            val match: ProfileMatch = requireNotNull(ProfileRegistry.bundled().best(file))

            // Then
            assertEquals(200, match.sampledLines) { "detect.sample is 200; the rest must not be read" }
        }
    }
}

/** The bundled profile's own strings, so the tests exercise what actually ships. */
private object WithingsProfileFixture {
    val PROFILE: CompiledProfile = ProfileRegistry.bundled().profiles.single { profile -> profile.name == "withings-healthmate" }
    val REGEX: String = PROFILE.pattern.pattern()
    const val OPENS: String = """^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \["""
}
