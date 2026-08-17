package dev.loupe.core.profile

import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.parse.ProfileEntryParser
import dev.loupe.core.source.LogSourceLoader
import dev.loupe.core.testing.facetOf
import dev.loupe.core.testing.writeLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.Year
import java.time.ZoneId

/**
 * Every bundled profile, against lines of the format it claims to read.
 *
 * These exist because each one exercises a path the HealthMate profile never touches — a yearless
 * timestamp, a named month, an optional millisecond, a format with no level at all. A profile that
 * ships without a file it can actually read is a promise nobody checked.
 */
class BundledProfilesTest {

    companion object {
        private val REGISTRY: ProfileRegistry = ProfileRegistry.bundled()

        private fun profile(name: String): CompiledProfile =
            REGISTRY.profiles.single { candidate -> candidate.name == name }
    }

    @TempDir
    lateinit var folder: File

    @Test
    fun `every bundled profile compiles`() {
        // Given / When — loading the registry compiles them all, and throws on any problem.
        // Then
        assertEquals(
            listOf(
                "android-logcat", "android-studio-logcat", "generic-timestamped", "json-lines",
                "syslog-rfc3164", "withings-healthmate",
            ),
            REGISTRY.profiles.map { candidate -> candidate.name }.sorted(),
        )
    }

    @Test
    fun `only the formats that genuinely need it take the slow timestamp path`() {
        // Given / When / Then — a fallback costs roughly a microsecond an entry, so it is a
        // property worth pinning rather than discovering on a 1 GiB file.
        assertTrue(profile("withings-healthmate").timestampFormat.isFastPath)
        assertTrue(profile("android-logcat").timestampFormat.isFastPath)
        assertTrue(profile("android-studio-logcat").timestampFormat.isFastPath)
        assertTrue(profile("json-lines").timestampFormat.isFastPath)
        assertTrue(profile("generic-timestamped").timestampFormat.isFastPath)
        // A named month and a space-padded day cannot be read at a fixed offset.
        assertFalse(profile("syslog-rfc3164").timestampFormat.isFastPath)
    }

    @Nested
    inner class AndroidLogcat {

        private val logcat: CompiledProfile = profile("android-logcat")

        @Test
        fun `reads a threadtime line`() {
            // Given
            val file = write(
                "logcat.txt",
                "--------- beginning of main",
                "06-02 10:00:01.001  1234  5678 D SyncService: pulling vasistas for user 42",
                "06-02 10:00:01.420  1234  5678 E SyncService: pull failed",
            )

            // When
            val index: LogIndex = LogIndexer(ProfileEntryParser(logcat)).index(file)

            // Then
            assertEquals(2, index.entryCount)
            assertEquals(1L, index.noticeLineCount)
            assertEquals(0L, index.unrecognisedLineCount)
            assertEquals("SyncService", facetOf(index, "tag", 0))
            assertEquals("1234", facetOf(index, "pid", 0))
            assertEquals(4, index.levels[1].toInt()) // E, fifth on V D I W E F S
        }

        @Test
        fun `assumes the current year, and says that it did`() {
            // Given — logcat writes no year at all.
            val file = write("logcat.txt", "06-02 10:00:01.001  1  1 I Tag: hello")

            // When
            val index: LogIndex = LogIndexer(ProfileEntryParser(logcat)).index(file)
            val date = Instant.ofEpochMilli(index.timestamps[0]).atZone(ZoneId.systemDefault())

            // Then — a guess, but the only one available, and the loader warns rather than hiding it.
            assertEquals(Year.now().value, date.year)
            assertEquals(6, date.monthValue)
            assertEquals(2, date.dayOfMonth)
            assertTrue(logcat.timestampFormat.assumesYear)
            assertTrue(logcat.warnings.any { warning -> warning.contains("no year") }, logcat.warnings.toString())
        }

        @Test
        fun `keeps a stack trace as separate entries, because logcat does`() {
            // Given — logcat repeats the full prefix on every line, so there is nothing to fold.
            val file = write(
                "logcat.txt",
                "06-02 10:00:01.001  1  1 E Tag: java.lang.IllegalStateException: nope",
                "06-02 10:00:01.002  1  1 E Tag: \tat com.withings.Boom.explode(Boom.kt:42)",
            )

            // When
            val index: LogIndex = LogIndexer(ProfileEntryParser(logcat)).index(file)

            // Then
            assertEquals(2, index.entryCount)
            assertEquals(0L, index.continuationLineCount)
        }
    }

    @Nested
    inner class Syslog {

        private val syslog: CompiledProfile = profile("syslog-rfc3164")

        @Test
        fun `reads a named month and a space-padded day`() {
            // Given — `Oct  1` has two spaces, `Oct 11` one. This is the fallback path's only user.
            val file = write(
                "system.log",
                "Oct  1 22:14:15 mymachine su[1234]: 'su root' failed for lonvick",
                "Oct 11 09:05:00 mymachine kernel: link up",
            )

            // When
            val index: LogIndex = LogIndexer(ProfileEntryParser(syslog)).index(file)

            // Then
            assertEquals(2, index.entryCount)
            assertEquals(0L, index.unrecognisedLineCount)
            assertEquals("su", facetOf(index, "tag", 0))
            assertEquals("kernel", facetOf(index, "tag", 1))
            assertEquals("mymachine", facetOf(index, "host", 0))

            val first = Instant.ofEpochMilli(index.timestamps[0]).atZone(ZoneId.systemDefault())
            assertEquals(10, first.monthValue)
            assertEquals(1, first.dayOfMonth)
            assertEquals(22, first.hour)
        }

        @Test
        fun `copes with a format that declares no level at all`() {
            // Given — RFC 3164 carries severity in a priority prefix that files usually drop.
            // When / Then — no level scale, and nothing downstream assumes there is one.
            assertEquals(null, syslog.levelDecoder)
            assertEquals(0, syslog.levelCount)

            val file = write("system.log", "Oct  1 22:14:15 host proc: message")
            val index: LogIndex = LogIndexer(ProfileEntryParser(syslog)).index(file)
            assertEquals(1, index.entryCount)
            assertEquals(1, index.timelineHistogram(bucketCount = 4).size) { "one bucket row, not zero" }
        }
    }

    @Nested
    inner class Generic {

        private val generic: CompiledProfile = profile("generic-timestamped")

        @Test
        fun `reads a line with milliseconds and one without`() {
            // Given — the optional tail is the case that used to read whatever followed the group
            // and call it a number.
            val file = write(
                "app.log",
                "2026-06-02 10:00:01.250 ERROR connection refused",
                "2026-06-02 10:00:02 WARN retrying",
                "2026-06-02T10:00:03Z INFO recovered",
            )

            // When
            val index: LogIndex = LogIndexer(ProfileEntryParser(generic)).index(file)

            // Then
            assertEquals(3, index.entryCount)
            assertEquals(0L, index.unrecognisedLineCount)
            assertEquals(250L, index.timestamps[0] % 1000)
            assertEquals(0L, index.timestamps[1] % 1000) { "no milliseconds means zero, not garbage" }
            assertEquals(0L, index.timestamps[2] % 1000)
        }

        @Test
        fun `accepts a line with no level`() {
            // Given
            val file = write("app.log", "2026-06-02 10:00:01 just a message")

            // When
            val index: LogIndex = LogIndexer(ProfileEntryParser(generic)).index(file)

            // Then
            assertEquals(1, index.entryCount)
            assertEquals(-1, index.levels[0].toInt())
        }
    }

    @Nested
    inner class Detection {

        @Test
        fun `picks the format that describes the file, not the catch-all`() {
            // Given — generic-timestamped matches a HealthMate line too. Score ties break on
            // priority, which is the only reason a fallback can be shipped at all.
            val file = File(folder, "2026-07-22")
            file.writeText(
                (1..12).joinToString("\n", postfix = "\n") { line ->
                    "2026-07-22 10:00:0${line % 10}.000 [D] [Sync] [tag] -> entry $line"
                },
            )

            // When
            val match: ProfileMatch = requireNotNull(ProfileRegistry.bundled().best(file))

            // Then
            assertEquals("withings-healthmate", match.profile.name)
        }

        @Test
        fun `falls back to the catch-all for a format nobody describes`() {
            // Given
            val file = File(folder, "app.log")
            file.writeText(
                (1..12).joinToString("\n", postfix = "\n") { line ->
                    "2026-07-22 10:00:0${line % 10}.000 INFO something happened, number $line"
                },
            )

            // When
            val match: ProfileMatch = requireNotNull(ProfileRegistry.bundled().best(file))

            // Then
            assertEquals("generic-timestamped", match.profile.name)
        }

        @Test
        fun `opens a logcat capture end to end`() {
            // Given
            val file = File(folder, "logcat.txt")
            file.writeText(
                (1..20).joinToString("\n", postfix = "\n") { line ->
                    // %03d, not string concatenation: ".00" + 10 is four digits of milliseconds and
                    // the profile is right to reject it.
                    "06-02 10:00:%02d.%03d  1234  5678 I Tag%d: message %d".format(line % 60, line, line, line)
                },
            )

            // When
            LogSourceLoader.open(listOf(file)).use { source ->
                // Then
                assertEquals("android-logcat", source.profile.name)
                assertEquals(20, source.index.entryCount)
                assertEquals(1.0, source.index.recognisedLineRatio)
            }
        }
    }

    private fun write(name: String, vararg lines: String): File = writeLog(folder, name, *lines)
}
