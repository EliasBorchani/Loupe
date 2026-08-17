package dev.loupe.core.source

import dev.loupe.core.index.LogIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * JSON lines — one object per line, which is what the Withings iOS app exports.
 *
 * The case that justifies decoding rather than a regex is the first one below: 12 of the 43 lines
 * in the capture this was written against carry `\"`, `\/` or `\n`, and a captured group would
 * hand every one of them to the reader still escaped.
 */
class JsonLinesAdapterTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `claims one object per line and nothing else`() {
        // Given
        val jsonLines: File = write("app.ndjson", """{"date":"2026-08-13T15:21:15.293Z","message":"hello"}""")
        val prettyPrinted: File = write("export.logcat", "{\n  \"logcatMessages\": []\n}")
        val plainText: File = write("plain.log", "2026-08-13 15:21:15.293 [D] [Sync] [tag] -> hello")

        // When / Then — a lone brace is a document, and belongs to the other adapter.
        assertTrue(JsonLinesAdapter.claims(jsonLines))
        assertFalse(JsonLinesAdapter.claims(prettyPrinted))
        assertFalse(JsonLinesAdapter.claims(plainText))
    }

    @Test
    fun `decodes the escapes a regex profile would leave in place`() {
        // Given — all three shapes are in the real iOS capture.
        val file: File = write(
            "ios.ndjson",
            """{"date":"2026-08-13T15:21:15.501Z","category":"webservice","message":"POST https:\/\/withings.net body {\"action\":\"getlastupdate\"}","level":"debug"}""",
        )

        // When
        val lines: List<String> = convert(file)

        // Then
        assertTrue(
            lines.single().endsWith("""POST https://withings.net body {"action":"getlastupdate"}"""),
            "unexpected: '${lines.single()}'",
        )
    }

    @Test
    fun `folds an escaped newline into one entry`() {
        // Given
        val file: File = write(
            "ios.ndjson",
            """{"date":"2026-08-13T15:21:15.404Z","category":"general","message":"loadFromDisk failed:\n  Code=260 « HealthKitDailyAnalytics »","level":"error"}""",
        )

        // When
        val lines: List<String> = convert(file)

        // Then — a real line break, indented, rather than a literal backslash-n.
        assertEquals(2, lines.size)
        assertTrue(lines[1] == " ".repeat(23) + "  Code=260 « HealthKitDailyAnalytics »", "unexpected: '${lines[1]}'")
        open(file).use { source -> assertEquals(1, source.index.entryCount) }
    }

    @Test
    fun `writes the instant in local time`() {
        // Given — the iOS app writes UTC; the tests run pinned to Europe/Paris.
        val file: File = write("ios.ndjson", """{"date":"2026-08-13T15:21:15.293Z","message":"Scene connected."}""")

        // When / Then
        assertTrue(convert(file).single().startsWith("2026-08-13 17:21:15.293 "), convert(file).single())
    }

    @Test
    fun `reads the other shapes a timestamp comes in`() {
        // Given / When / Then — the same instant as epoch milliseconds and as epoch seconds, then
        // an offset that is not the machine's, all landing on Europe/Paris wall-clock time.
        assertEquals("2026-08-17 12:48:55.293 ", prefixOf(write("a.ndjson", """{"ts":1786963735293,"msg":"epoch millis"}""")))
        assertEquals("2026-08-17 12:48:55.000 ", prefixOf(write("b.ndjson", """{"ts":1786963735,"msg":"epoch seconds"}""")))
        assertEquals("2026-08-13 15:21:15.293 ", prefixOf(write("c.ndjson", """{"time":"2026-08-13T15:21:15.293+02:00","msg":"an offset"}""")))
    }

    private fun prefixOf(file: File): String = convert(file).single().take(24)

    @Test
    fun `normalises whatever the producer calls its levels`() {
        // Given — pino writes `warn`, syslog `notice`, Serilog `Warning`, log4j `SEVERE`.
        val file: File = write(
            "mixed.ndjson",
            """{"time":"2026-08-13T15:00:00.000Z","msg":"a","level":"warn"}""",
            """{"time":"2026-08-13T15:00:01.000Z","msg":"b","level":"Warning"}""",
            """{"time":"2026-08-13T15:00:02.000Z","msg":"c","level":"SEVERE"}""",
            """{"time":"2026-08-13T15:00:03.000Z","msg":"d","level":"notice"}""",
        )

        // When
        val lines: List<String> = convert(file)

        // Then
        assertEquals(
            listOf("WARN", "WARN", "ERROR", "NOTICE"),
            lines.map { line -> line.substringAfter("[").substringBefore("]") },
        )
    }

    @Test
    fun `keeps a key no slot claimed`() {
        // Given
        val file: File = write(
            "extra.ndjson",
            """{"time":"2026-08-13T15:00:00.000Z","msg":"pull done","level":"info","logger":"Sync","userId":42,"durationMs":1180}""",
        )

        // When
        val lines: List<String> = convert(file)

        // Then — on a continuation line, so it is searchable rather than dropped for want of a column.
        assertEquals(2, lines.size)
        assertEquals(" ".repeat(23) + "userId=42 durationMs=1180", lines[1])
    }

    @Test
    fun `names the keys it picked`() {
        // Given
        val file: File = write("ios.ndjson", """{"date":"2026-08-13T15:00:00.000Z","category":"general","message":"hi","level":"notice"}""")

        // When
        val report: ConversionReport = JsonLinesAdapter.convert(file, File(folder, "converted.txt"))

        // Then — a mapping chosen in silence would be the worst of both worlds.
        assertEquals(
            "1 JSON lines read — time from \"date\", message from \"message\", level from \"level\", context from \"category\"",
            report.note,
        )
    }

    @Test
    fun `says so when no key looks like a time`() {
        // Given
        val file: File = write("odd.ndjson", """{"who":"me","what":"happened"}""")

        // When / Then
        val failure = assertThrows<JsonFormatException> { open(file) }
        assertTrue(failure.message.orEmpty().contains("no key looks like a time"), failure.message)
        assertTrue(failure.message.orEmpty().contains("who, what"), failure.message)
    }

    @Test
    fun `reads the iOS export end to end`() {
        // Given
        val file: File = write(
            "Withings-Logs.ndjson",
            """{"date":"2026-08-13T15:21:15.293Z","category":"general","message":"Scene connected.","level":"notice"}""",
            """{"date":"2026-08-13T15:21:15.501Z","category":"webservice","message":"URL Request: POST https:\/\/withings.net","level":"debug"}""",
            """{"date":"2026-08-13T15:21:16.001Z","category":"synchronization","message":"pull failed","level":"error"}""",
        )

        // When
        open(file).use { source ->
            // Then
            assertEquals("json-lines", source.profile.name)
            assertEquals(3, source.index.entryCount)
            assertEquals(1.0, source.index.recognisedLineRatio)
            assertEquals(
                listOf("general", "webservice", "synchronization"),
                (0..2).map { entry -> facet(source.index, "context", entry) },
            )
            assertEquals("Withings-Logs.ndjson", source.files.single().name)
        }
    }

    private fun convert(file: File): List<String> {
        val destination = File(folder, "${file.nameWithoutExtension}-converted.txt")
        JsonLinesAdapter.convert(file, destination)
        return destination.readLines()
    }

    private fun open(file: File): LogSource = LogSourceLoader.open(listOf(file))

    private fun write(name: String, vararg lines: String): File {
        val file = File(folder, name)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }

    private fun facet(index: LogIndex, name: String, entry: Int): String? {
        val facetIndex: Int = index.facetIndexOf(name)
        val valueId: Int = index.facetValues[facetIndex][entry]
        return if (valueId == LogIndex.NO_VALUE) null else index.facetDictionaries[facetIndex].valueOf(valueId)
    }
}
