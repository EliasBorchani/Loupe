package dev.loupe.core.testing

import dev.loupe.core.index.LogIndex
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.ProfileRegistry
import java.io.File

/**
 * The scaffolding every test that touches a log file needs.
 *
 * These were copy-pasted rather than shared: the facet reader existed four times with identical
 * bodies, the file writer five times in three shapes, the bundled-profile lookup five times, and the
 * continuation indent twice — plus one copy of the writer in the `desktop` module, which could not
 * be shared at all until `core` grew a `testFixtures` source set.
 *
 * Deliberately thin. `.claude/rules/testing.md` wants a test to read as its own story, so what lives
 * here is only what was already identical everywhere: no assertions, no corpora, no builders that
 * hide what a fixture contains.
 */

/**
 * The 23 spaces `FileLogger` writes in front of a wrapped line.
 *
 * Written out, not `" ".repeat(CanonicalLine.TIMESTAMP_WIDTH)`. A test that derives its expectation
 * from the code under test proves nothing — and this one exists to catch that width changing.
 */
const val WITHINGS_INDENT: String = "                       "

/** The bundled profiles, compiled once for the whole suite rather than once per test class. */
object BundledProfile {

    private val REGISTRY: ProfileRegistry = ProfileRegistry.bundled()

    val withings: CompiledProfile get() = named("withings-healthmate")

    fun named(name: String): CompiledProfile = REGISTRY.profiles.single { profile -> profile.name == name }
}

/** Writes [lines] into `directory/name`, newline-joined, with the trailing newline a log file has. */
fun writeLog(directory: File, name: String, vararg lines: String): File {
    val file = File(directory, name)
    file.writeText(lines.joinToString("\n", postfix = "\n"))
    return file
}

/** A facet's value for one entry, or `null` when its group did not participate in the match. */
fun facetOf(index: LogIndex, name: String, entry: Int): String? {
    val facetIndex: Int = index.facetIndexOf(name)
    val valueId: Int = index.facetValues[facetIndex][entry]
    return if (valueId == LogIndex.NO_VALUE) null else index.facetDictionaries[facetIndex].valueOf(valueId)
}
