package dev.loupe.core.profile

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Smoke test for the TOML shapes the profile format relies on — before anything is built on them. */
class LogProfileSpecTest {

    private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))

    @Test
    fun `reads a profile using every shape the format needs`() {
        // Given — dotted sub-tables, an array of tables, inline tables, multi-line literal strings.
        val source = """
            name = "demo"
            priority = 50

            [detect]
            filename = '''^\d{4}-\d{2}-\d{2}${'$'}'''
            min_match = 0.8

            [entry]
            continues = '''^ {23}'''
            strip_continuation_indent = true

            [parse]
            regex = '''^(?<ts>\S+) \[(?<level>[VDIWE])\] (?<message>.*)${'$'}'''

            [fields.ts]
            role = "timestamp"
            format = "yyyy-MM-dd HH:mm:ss.SSS"
            zone = "local"

            [fields.level]
            role = "level"
            order = ["V", "D", "I", "W", "E"]
            labels = { V = "Verbose", E = "Error" }

            [fields.message]
            role = "message"

            [[markers]]
            regex = '''^=== (.+) ===${'$'}'''
            role = "section"

            [[markers]]
            regex = '''^--- .* ---${'$'}'''
            role = "notice"
        """.trimIndent()

        // When
        val spec: LogProfileSpec = toml.decodeFromString(source)

        // Then
        assertEquals("demo", spec.name)
        assertEquals(50, spec.priority)
        assertEquals(0.8, spec.detect.minMatch)
        assertEquals("""^ {23}""", spec.entry.continues)
        assertEquals(true, spec.entry.stripContinuationIndent)

        assertEquals(setOf("ts", "level", "message"), spec.fields.keys)
        assertEquals(FieldRole.Timestamp, spec.fields.getValue("ts").role)
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", spec.fields.getValue("ts").format)
        assertEquals(listOf("V", "D", "I", "W", "E"), spec.fields.getValue("level").order)
        assertEquals(mapOf("V" to "Verbose", "E" to "Error"), spec.fields.getValue("level").labels)
        assertEquals(FacetMode.Auto, spec.fields.getValue("level").facet)

        assertEquals(2, spec.markers.size)
        assertEquals(MarkerRole.Section, spec.markers[0].role)
        assertEquals(MarkerRole.Notice, spec.markers[1].role)
    }
}
