package dev.loupe.core.profile

import dev.loupe.core.source.LogSourceLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Profiles a user drops in `~/.loupe/profiles/`.
 *
 * The directory is passed in here rather than read from the home directory: a test that depends on
 * what happens to be on the machine running it is not a test.
 */
class UserProfilesTest {

    companion object {
        /**
         * Derived, not written down. These tests are about what a *user* directory adds; a literal
         * count here would fail every time a profile ships, which says nothing about user profiles.
         * The bundled set has its own guard in `BundledProfilesTest`.
         */
        private val BUNDLED_COUNT: Int = ProfileRegistry.bundled().profiles.size

        private val WIDGET_LOG = """
            name        = "widget"
            description = "A format nobody has described before"
            priority    = 60

            [parse]
            regex = '''^(?<ts>\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}) \| (?<severity>OK|BAD) \| (?<unit>\w+) \| (?<message>.*)${'$'}'''

            [fields.ts]
            role   = "timestamp"
            format = "yyyy/MM/dd HH:mm:ss"
            zone   = "utc"

            [fields.severity]
            role  = "level"
            order = ["OK", "BAD"]

            [fields.unit]
            role  = "facet"
            label = "Unit"

            [fields.message]
            role = "message"
        """.trimIndent()
    }

    @TempDir
    lateinit var profiles: File

    @TempDir
    lateinit var logs: File

    @Test
    fun `a profile dropped in the directory joins the bundled ones`() {
        // Given
        File(profiles, "widget.logprofile.toml").writeText(WIDGET_LOG)

        // When
        val loaded: LoadedRegistry = ProfileRegistry.bundledPlusUser(profiles)

        // Then
        assertTrue("widget" in loaded.registry.profiles.map { profile -> profile.name })
        assertEquals(emptyList<String>(), loaded.problems)
    }

    @Test
    fun `and then reads a file no bundled profile understands`() {
        // Given
        File(profiles, "widget.logprofile.toml").writeText(WIDGET_LOG)
        val file = File(logs, "widget.log")
        file.writeText(
            (1..12).joinToString("\n", postfix = "\n") { line ->
                "2026/07/22 10:00:%02d | %s | pump%d | reading %d".format(line, if (line % 4 == 0) "BAD" else "OK", line % 3, line)
            },
        )

        // When
        LogSourceLoader.open(listOf(file), ProfileRegistry.bundledPlusUser(profiles)).use { source ->
            // Then
            assertEquals("widget", source.profile.name)
            assertEquals(12, source.index.entryCount)
            assertEquals(1.0, source.index.recognisedLineRatio)
            assertEquals(listOf("OK", "BAD"), source.profile.levelDecoder?.order)
            assertEquals(3, requireNotNull(source.index.dictionaryOf("unit")).size)
        }
    }

    @Test
    fun `a higher priority takes a format the bundled profiles also match`() {
        // Given — the same shape as generic-timestamped, but the user says theirs is the right one.
        File(profiles, "mine.logprofile.toml").writeText(
            """
            name     = "mine"
            priority = 99

            [parse]
            regex = '''^(?<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) (?<level>INFO|WARN) (?<message>.*)${'$'}'''

            [fields.ts]
            role   = "timestamp"
            format = "yyyy-MM-dd HH:mm:ss"

            [fields.level]
            role  = "level"
            order = ["INFO", "WARN"]

            [fields.message]
            role = "message"
            """.trimIndent(),
        )
        val file = File(logs, "app.log")
        file.writeText((1..12).joinToString("\n", postfix = "\n") { line -> "2026-07-22 10:00:%02d INFO line %d".format(line, line) })

        // When
        val match: ProfileMatch = requireNotNull(ProfileRegistry.bundledPlusUser(profiles).registry.best(file))

        // Then — score ties break on priority, which is how a user overrides a bundled format.
        assertEquals("mine", match.profile.name)
    }

    @Test
    fun `a broken profile is reported, and the others still load`() {
        // Given — someone iterating on a new format has a syntax error in it half the time. Refusing
        // to open anything at all would make the tool useless for exactly that task.
        File(profiles, "widget.logprofile.toml").writeText(WIDGET_LOG)
        File(profiles, "broken.logprofile.toml").writeText(
            """
            name = "broken"

            [parse]
            regex = '''^(?<ts>.*)${'$'}'''

            [fields.ts]
            role = "facet"
            """.trimIndent(),
        )

        // When
        val loaded: LoadedRegistry = ProfileRegistry.bundledPlusUser(profiles)

        // Then
        assertTrue("widget" in loaded.registry.profiles.map { profile -> profile.name })
        assertEquals(1, loaded.problems.size)
        assertTrue(loaded.problems.single().startsWith("broken.logprofile.toml"), loaded.problems.toString())
        assertTrue(loaded.problems.single().contains("timestamp"), loaded.problems.toString())
    }

    @Test
    fun `a missing directory is not a problem`() {
        // Given / When
        val loaded: LoadedRegistry = ProfileRegistry.bundledPlusUser(File(logs, "nothing-here"))

        // Then
        assertEquals(BUNDLED_COUNT, loaded.registry.profiles.size)
        assertEquals(emptyList<String>(), loaded.problems)
    }

    @Test
    fun `files without the profile extension are ignored`() {
        // Given — a README next to the profiles is normal.
        File(profiles, "widget.logprofile.toml").writeText(WIDGET_LOG)
        File(profiles, "notes.md").writeText("remember to fix the widget regex")

        // When
        val loaded: LoadedRegistry = ProfileRegistry.bundledPlusUser(profiles)

        // Then
        assertEquals(BUNDLED_COUNT + 1, loaded.registry.profiles.size)
        assertEquals(emptyList<String>(), loaded.problems)
    }
}
