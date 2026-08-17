package dev.loupe.core.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * No two adapters may claim the same file.
 *
 * This was prose. One adapter's KDoc said it takes a first line that is exactly `{`, the other's
 * said it takes one that starts with `{` and is longer — complementary by construction, argued in
 * two places, enforced nowhere, and encoded a third time as the order of `SourceAdapters.all`.
 *
 * The awkward shapes below are what a sniff on a fixed-size prefix can get wrong: a byte-order mark,
 * CRLF, a first line longer than the prefix, and a multi-byte character cut in half by its last byte.
 */
class SourceAdapterClaimsTest {

    @TempDir
    lateinit var folder: File

    @Test
    fun `each shape is claimed by at most one adapter`() {
        // Given / When / Then
        assertEquals("Android Studio logcat export", claimantOf("export.logcat", "{\n  \"logcatMessages\": []\n}"))
        assertEquals("JSON lines", claimantOf("app.ndjson", """{"time":"2026-08-13T15:00:00Z","msg":"one per line"}"""))

        // A document written on Windows: the lone brace carries a `\r`, which trimming removes.
        assertEquals("Android Studio logcat export", claimantOf("crlf.logcat", "{\r\n  \"logcatMessages\": []\r\n}\r\n"))

        // A byte-order mark is not whitespace, so it would otherwise sit in front of the `{`.
        assertEquals("JSON lines", claimantOf("bom.ndjson", "﻿{\"time\":\"2026-08-13T15:00:00Z\",\"msg\":\"hi\"}"))

        assertNull(claimantOf("2026-07-22", "2026-07-22 10:00:00.000 [D] [Sync] [Pull] -> hello"))
        assertNull(claimantOf("logcat.txt", "06-02 10:00:01.001  1234  5678 D Tag: hello"))
        assertNull(claimantOf("syslog", "Jul 22 10:00:00 host daemon[1]: hello"))
        assertNull(claimantOf("empty.log", ""))
        assertNull(claimantOf("array.json", "[{\"time\":\"2026-08-13T15:00:00Z\"}]"))
    }

    @Test
    fun `a first line longer than the sniff window is still judged the same way`() {
        // Given — one NDJSON object whose first line runs well past the prefix, and one whose 4096th
        // byte falls inside a two-byte character.
        val long: File = write("long.ndjson", """{"time":"2026-08-13T15:00:00Z","msg":"${"a".repeat(6000)}"}""")
        val split: File = write("split.ndjson", "{" + "a".repeat(SNIFF_BYTES - 2) + "é" + ""","time":"x"}""")

        // When / Then — claims() must answer on the prefix exactly as it would on the whole line,
        // because that is all it is ever given. A predicate that looked at the line's *end* would
        // fail here, which is the point of pinning it.
        listOf(long, split).forEach { file ->
            assertEquals("JSON lines", claimantOf(file.name, file.readText()), file.name)
            assertEquals(
                SourceAdapters.all.map { adapter -> adapter.claims(file) },
                SourceAdapters.all.map { adapter -> adapter.claims(truncatedCopyOf(file)) },
                "${file.name}: the answer changed when the file was cut to the sniff window",
            )
        }
    }

    private fun claimantOf(name: String, content: String): String? =
        SourceAdapters.claiming(write(name, content))?.name

    private fun write(name: String, content: String): File {
        val file = File(folder, name)
        file.writeText(content)
        return file
    }

    private fun truncatedCopyOf(file: File): File {
        val copy = File(folder, "${file.name}.prefix")
        copy.writeBytes(file.readBytes().copyOf(SNIFF_BYTES))
        return copy
    }
}
