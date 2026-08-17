package dev.loupe.core.profile

import dev.loupe.core.io.ChunkedLineReader
import java.io.File

/**
 * The profiles available to the app, and the logic that picks one for a file.
 *
 * Detection is a score, never a guess in the dark: each candidate reads the same sample of lines
 * and reports the share it can account for. The winner and its score are both surfaced, so the UI
 * can say *which* profile it chose and how confident it was, and let the user override in one
 * click. Silently mis-detecting a format and showing a plausible-but-wrong table would be the
 * worst failure this tool could have.
 */
class ProfileRegistry(val profiles: List<CompiledProfile>) {

    companion object {
        const val PROFILE_EXTENSION: String = ".logprofile.toml"

        private const val BUNDLED_ROOT = "/profiles"
        private const val BUNDLED_INDEX = "$BUNDLED_ROOT/index.txt"

        /** The profiles shipped inside the jar, enumerated through the generated index. */
        fun bundled(): ProfileRegistry {
            val loaded: List<CompiledProfile> = readBundledIndex().lineSequence()
                .map { line -> line.trim() }
                .filter { line -> line.isNotEmpty() }
                .map { fileName ->
                    val source: String = requireNotNull(
                        ProfileRegistry::class.java.getResourceAsStream("$BUNDLED_ROOT/$fileName"),
                    ) { "$BUNDLED_ROOT/$fileName is listed in the index but not on the classpath" }
                        .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
                    CompiledProfile.compile(CompiledProfile.parse(source, fileName))
                }
                .toList()
            return ProfileRegistry(loaded)
        }

        private fun readBundledIndex(): String =
            requireNotNull(ProfileRegistry::class.java.getResourceAsStream(BUNDLED_INDEX)) {
                "$BUNDLED_INDEX is missing — the generateProfileIndex task did not run"
            }.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }

        /** File names of the bundled profiles, for anyone offering one as a starting point. */
        fun bundledFileNames(): List<String> = readBundledIndex()
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .toList()

        /** The raw TOML of a bundled profile — heavily commented, which is the point of copying it. */
        fun bundledSource(fileName: String): String? =
            ProfileRegistry::class.java.getResourceAsStream("$BUNDLED_ROOT/$fileName")
                ?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }

        /** Where a user drops their own profiles. Read on every open, so editing one needs no restart. */
        fun userDirectory(): File = File(System.getProperty("user.home"), ".loupe/profiles")

        /**
         * The bundled profiles plus whatever is in [directory].
         *
         * **A broken user profile must not stop the app opening a file.** Someone iterating on a
         * new format will have a syntax error in it half the time, and refusing to launch would
         * make the tool useless for exactly the task it is meant to support. Each failure is
         * collected and surfaced instead.
         */
        fun bundledPlusUser(directory: File = userDirectory()): LoadedRegistry {
            val bundled: ProfileRegistry = bundled()
            if (!directory.isDirectory) return LoadedRegistry(bundled, emptyList())

            val loaded: MutableList<CompiledProfile> = mutableListOf()
            val problems: MutableList<String> = mutableListOf()
            directory.listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file.name.endsWith(PROFILE_EXTENSION) }
                .sortedBy { file -> file.name }
                .forEach { file ->
                    try {
                        loaded.add(CompiledProfile.load(file))
                    } catch (failure: IllegalArgumentException) {
                        problems.add("${file.name}: ${failure.message}")
                    }
                }
            return LoadedRegistry(ProfileRegistry(bundled.profiles + loaded), problems)
        }

        /** Profiles the user dropped in a directory. Throws on the first bad one. */
        fun fromDirectory(directory: File): ProfileRegistry {
            val loaded: List<CompiledProfile> = directory.listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file.name.endsWith(PROFILE_EXTENSION) }
                .sortedBy { file -> file.name }
                .map { file -> CompiledProfile.load(file) }
            return ProfileRegistry(loaded)
        }
    }

    operator fun plus(other: ProfileRegistry): ProfileRegistry = ProfileRegistry(profiles + other.profiles)

    /**
     * The same registry without [names] — the profiles a source adapter has already spoken for.
     *
     * Those describe text Loupe writes itself, so scoring them against an ordinary file is at best a
     * waste of a pass and at worst a mis-detection: a plain log merely *shaped* like a converted one
     * would be read by a profile intended for something else.
     */
    fun excluding(names: Set<String>): ProfileRegistry =
        ProfileRegistry(profiles.filter { profile -> profile.name !in names })

    /** @return every candidate that cleared its own `min_match`, best first. Empty if none did. */
    fun detect(file: File): List<ProfileMatch> = profiles
        .map { profile -> score(profile, file) }
        .filter { match -> match.score >= match.profile.detect.minMatch }
        .sortedWith(compareByDescending<ProfileMatch> { match -> match.score }.thenByDescending { match -> match.profile.priority })

    fun best(file: File): ProfileMatch? = detect(file).firstOrNull()

    private fun score(profile: CompiledProfile, file: File): ProfileMatch {
        val sampleSize: Int = profile.detect.sample
        var sampled = 0
        var recognised = 0
        var entries = 0

        // The reader has no early exit — a sample of 200 lines out of a 1 GiB file would otherwise
        // read the whole thing once per candidate profile. Throwing is the exit.
        runCatching {
            ChunkedLineReader(file).forEachLine { buffer, start, end, _ ->
                if (sampled >= sampleSize) throw SampleComplete
                sampled++
                if (accounts(profile, buffer, start, end)) {
                    recognised++
                    if (opensEntry(profile, buffer, start, end)) entries++
                }
            }
        }.exceptionOrNull()?.let { failure -> if (failure !== SampleComplete) throw failure }

        return ProfileMatch(
            profile = profile,
            score = if (sampled == 0) 0.0 else recognised.toDouble() / sampled,
            filenameMatches = profile.detectFilename?.matcher(file.name)?.matches() ?: false,
            sampledLines = sampled,
            recognisedLines = recognised,
            entryLines = entries,
        )
    }

    /** A line counts as accounted for if it is an entry, a continuation, or a declared marker. */
    private fun accounts(profile: CompiledProfile, buffer: ByteArray, start: Int, end: Int): Boolean {
        if (end == start) return true // blank lines say nothing either way
        if (opensEntry(profile, buffer, start, end)) return true
        if (profile.continues?.matches(buffer, start, end) == true) return true
        if (profile.markers.isEmpty()) return false
        val line = String(buffer, start, end - start, Charsets.UTF_8)
        return profile.markers.any { marker -> marker.pattern.matcher(line).find() }
    }

    private fun opensEntry(profile: CompiledProfile, buffer: ByteArray, start: Int, end: Int): Boolean {
        if (profile.opens?.matches(buffer, start, end) == false) return false
        return profile.pattern.matcher(String(buffer, start, end - start, Charsets.UTF_8)).matches()
    }

    private object SampleComplete : RuntimeException(null, null, false, false)
}

/** A registry, plus whatever refused to load on the way. */
class LoadedRegistry(val registry: ProfileRegistry, val problems: List<String>)

class ProfileMatch(
    val profile: CompiledProfile,
    /** Share of sampled lines the profile accounted for, in `0.0..1.0`. */
    val score: Double,
    /** The file name matched `detect.filename` — a corroborating signal, never a requirement. */
    val filenameMatches: Boolean,
    val sampledLines: Int,
    val recognisedLines: Int,
    /** How many of the sampled lines were entry openings, as opposed to continuations or markers. */
    val entryLines: Int,
) {
    override fun toString(): String =
        "${profile.name} ${"%.1f".format(score * 100)}% ($recognisedLines/$sampledLines lines, $entryLines entries)"
}
