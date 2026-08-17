package dev.loupe.core.source

import dev.loupe.core.testing.facetOf
import dev.loupe.core.testing.writeLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * The Android Studio `.logcat` export, which is a JSON document wearing a log file's extension.
 *
 * The cases that earned their place here are the ones the format actually produces and a naive
 * converter gets wrong: a tag holding the delimiter, a stack trace arriving as one message rather
 * than one line per frame, and a non-ASCII message. All three are taken from a real 9 994-message
 * capture off a Pixel 8 Pro.
 */
class AndroidStudioLogcatAdapterTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `claims a pretty-printed document and leaves everything else alone`() {
        // Given
        val export: File = write("export.logcat", "{\n  \"logcatMessages\": []\n}")
        val jsonLines: File = write("app.jsonl", """{"level":"INFO","message":"one object per line"}""")
        val plainText: File = write("plain.log", "06-02 10:00:01.001  1234  5678 D Tag: hello")

        // When / Then — a JSON-lines log is line-oriented already; claiming it would break it.
        assertTrue(AndroidStudioLogcatAdapter.claims(export))
        assertFalse(AndroidStudioLogcatAdapter.claims(jsonLines))
        assertFalse(AndroidStudioLogcatAdapter.claims(plainText))
    }

    @Test
    fun `renders a message as one logcat line`() {
        // Given
        val export: File = write("export.logcat", exportOf(message("WindowManager", "system_server", "DEBUG", "applying DisplayInfo")))

        // When
        val lines: List<String> = convert(export)

        // Then
        assertEquals(1, lines.size)
        assertTrue(
            lines[0].matches(
                Regex(
                    """\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+1971\s+2033 D \[WindowManager] \[system_server] applying DisplayInfo""",
                ),
            ),
            "unexpected line: ${lines[0]}",
        )
    }

    @Test
    fun `keeps a tag holding the delimiter in one piece`() {
        // Given — both shapes are real: 7 tags of 382 held a bracket, 5 held a colon.
        val export: File = write(
            "export.logcat",
            exportOf(
                message("DisplayPowerController[0]", "system_server", "INFO", "brightness 0.42"),
                message("ASvc::AudioMetricDataReader", "audioserver", "INFO", "flushed"),
            ),
        )

        // When
        val source: LogSource = open(export)

        // Then — escaped on the way out, restored on the way back in.
        source.use { open ->
            assertEquals(
                listOf("DisplayPowerController[0]", "ASvc::AudioMetricDataReader"),
                (0 until open.index.entryCount).map { entry -> facetOf(open.index, "tag", entry) },
            )
        }
    }

    @Test
    fun `folds a stack trace into one entry`() {
        // Given — the export keeps a trace as a single message; raw logcat would have split it.
        val trace = "Failed to deliver transaction\\n" +
            "\\tat android.os.Binder.execTransact(Binder.java:1345)\\n" +
            "\\tat com.example.Foo.bar(Foo.java:12)"
        val export: File = write("export.logcat", exportOf(message("ActivityManager", "system_server", "ERROR", trace)))

        // When
        val lines: List<String> = convert(export)

        // Then
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith(" ".repeat(23) + "\tat android.os.Binder"), "unexpected: '${lines[1]}'")

        // And the index folds them back into the one entry they describe.
        open(export).use { open ->
            assertEquals(1, open.index.entryCount)
            assertEquals(2L, open.index.continuationLineCount)
        }
    }

    @Test
    fun `decodes escapes and characters outside ascii`() {
        // Given
        val export: File = write(
            "export.logcat",
            exportOf(message("Tag", "app", "WARN", "quote \\\" backslash \\\\ accented caf\\u00e9 emoji \\ud83d\\udd0d tab \\t")),
        )

        // When
        val lines: List<String> = convert(export)

        // Then
        assertTrue(lines[0].endsWith("quote \" backslash \\ accented café emoji 🔍 tab \t"), "unexpected: '${lines[0]}'")
    }

    @Test
    fun `carries the timestamp across unchanged`() {
        // Given
        val export: File = write("export.logcat", exportOf(message("Tag", "app", "INFO", "hello")))

        // When
        open(export).use { open ->
            // Then — the index must hold the instant the export recorded, not a re-guessed one.
            assertEquals(Instant.ofEpochSecond(1_786_963_735L, 620_829_517L).toEpochMilli(), open.index.timestamps[0])
        }
    }

    @Test
    fun `reads the format end to end`() {
        // Given
        val export: File = write(
            "Google-Pixel-8-Pro.logcat",
            exportOf(
                message("WindowManager", "system_server", "DEBUG", "one"),
                message("SyncService", "com.withings.wiscale2", "ERROR", "two"),
                message("SyncService", "com.withings.wiscale2", "WARN", "three"),
            ),
        )

        // When
        open(export).use { open ->
            // Then
            assertEquals("android-studio-logcat", open.profile.name)
            assertEquals(3, open.index.entryCount)
            assertEquals(1.0, open.index.recognisedLineRatio)
            assertEquals(
                listOf("system_server", "com.withings.wiscale2", "com.withings.wiscale2"),
                (0..2).map { entry -> facetOf(open.index, "process", entry) },
            )
            // The facet reads the file the user chose, not the temporary copy behind it.
            assertEquals("Google-Pixel-8-Pro.logcat", open.files.single().name)
            assertEquals(1, open.converted.size)
            assertEquals(3L, open.converted.single().report.entriesWritten)
        }
    }

    @Test
    fun `deletes the converted copy on close`() {
        // Given — a converted file lives in a temporary directory, and a viewer left open all day
        // must not leak one per file it was pointed at.
        val export: File = write("export.logcat", exportOf(message("Tag", "app", "INFO", "hello")))
        val before: Int = temporaryDirectoryCount()

        // When
        open(export).close()

        // Then — nothing left behind, and the file the user chose is untouched.
        assertEquals(before, temporaryDirectoryCount())
        assertTrue(export.exists())
    }

    private fun temporaryDirectoryCount(): Int = File(System.getProperty("java.io.tmpdir")).listFiles().orEmpty()
        .count { file -> file.name.startsWith("loupe-converted") }

    @Test
    fun `refuses a JSON document that is not an export`() {
        // Given
        val notAnExport: File = write("settings.logcat", "{\n  \"metadata\": { \"device\": \"pixel\" }\n}")

        // When / Then — silently indexing nothing would be the worse outcome.
        val failure = assertThrows<JsonFormatException> { open(notAnExport) }
        assertTrue(failure.message.orEmpty().contains("logcatMessages"), failure.message)
    }

    private fun convert(export: File): List<String> {
        val destination = File(folder, "converted.txt")
        AndroidStudioLogcatAdapter.convert(export, destination)
        return destination.readLines()
    }

    private fun open(export: File): LogSource = LogSourceLoader.open(listOf(export))

    private fun write(name: String, content: String): File = writeLog(folder, name, content)

    /** The shape Android Studio writes, metadata block included so the walker has to step over it. */
    private fun exportOf(vararg messages: String): String = """
        {
          "metadata": {
            "device": { "physicalDevice": { "serialNumber": "adb-XXXX", "release": "17" } },
            "projectApplicationIds": [ "com.withings.wiscale2", "com.example.other" ],
            "filter": ""
          },
          "logcatMessages": [
        ${messages.joinToString(",\n")}
          ]
        }
    """.trimIndent()

    private fun message(tag: String, process: String, logLevel: String, text: String): String = """
        {
          "header": {
            "logLevel": "$logLevel",
            "pid": 1971,
            "tid": 2033,
            "applicationId": "$process",
            "processName": "$process",
            "tag": "$tag",
            "timestamp": { "seconds": 1786963735, "nanos": 620829517 }
          },
          "message": "$text"
        }
    """.trimIndent()
}
