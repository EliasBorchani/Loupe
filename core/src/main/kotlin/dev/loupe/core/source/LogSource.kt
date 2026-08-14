package dev.loupe.core.source

import dev.loupe.core.index.IndexMerger
import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.io.TextSources
import dev.loupe.core.parse.ProfileEntryParser
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.ProfileMatch
import dev.loupe.core.profile.ProfileRegistry
import java.io.Closeable
import java.io.File

/**
 * An open log: one merged index, the text behind it, and how the format was recognised.
 *
 * This is the whole API the UI needs. Everything above it — facets, queries, the timeline — reads
 * from [index]; everything that renders text goes through [text].
 */
class LogSource(
    val index: LogIndex,
    val text: TextSources,
    val profile: CompiledProfile,
    val detection: ProfileMatch,
    /** Files that were indexed, in the order the `file` facet numbers them. */
    val files: List<File>,
    /** Files that were passed in but that the chosen profile does not recognise. */
    val skipped: List<SkippedFile>,
    val elapsedMillis: Long,
) : Closeable {

    override fun close() {
        text.close()
    }
}

class SkippedFile(val file: File, val reason: String)

/** Reported while opening, so a 1 GiB folder does not look frozen. */
fun interface OpenProgress {
    fun report(phase: OpenPhase, bytesDone: Long, bytesTotal: Long)
}

enum class OpenPhase { Detecting, Indexing, Merging }

/**
 * Opens files or folders as a single time-ordered view.
 *
 * The order of operations matters for the failure modes:
 *
 *  1. **Expand** folders. HealthMate day files are named `2026-06-02` with **no extension at all**,
 *     so nothing here may filter on one.
 *  2. **Detect on the largest file.** Scoring every file first would be honest but slow, and the
 *     biggest file is the most representative sample of what the folder is.
 *  3. **Re-score the others against the winner.** A file the chosen profile does not recognise is
 *     reported as skipped, never silently indexed into garbage.
 *  4. **Index, then merge.** Each file is indexed independently, then k-way merged by timestamp.
 */
object LogSourceLoader {

    fun open(
        paths: List<File>,
        registry: ProfileRegistry = ProfileRegistry.bundled(),
        progress: OpenProgress? = null,
    ): LogSource {
        val startedAt: Long = System.nanoTime()
        val candidates: List<File> = expand(paths)
        require(candidates.isNotEmpty()) { "No readable file in ${paths.joinToString { path -> path.name }}" }

        val totalBytes: Long = candidates.sumOf { file -> file.length() }
        progress?.report(OpenPhase.Detecting, 0, totalBytes)

        val largest: File = candidates.maxBy { file -> file.length() }
        val detection: ProfileMatch = registry.best(largest)
            ?: throw NoMatchingProfileException(largest, registry.profiles.map { profile -> profile.name })
        val profile: CompiledProfile = detection.profile

        val accepted: MutableList<File> = mutableListOf()
        val skipped: MutableList<SkippedFile> = mutableListOf()
        val single = ProfileRegistry(listOf(profile))
        candidates.forEach { file ->
            if (file === largest) {
                accepted.add(file)
                return@forEach
            }
            val match: ProfileMatch? = single.best(file)
            if (match == null) {
                skipped.add(SkippedFile(file, "not recognised by ${profile.name}"))
            } else {
                accepted.add(file)
            }
        }
        // Chronological by name is the right default for day files, and harmless otherwise: the
        // merge sorts by timestamp anyway, this only fixes the `file` facet's numbering.
        accepted.sortBy { file -> file.name }

        var bytesDone = 0L
        val indexes: List<LogIndex> = accepted.map { file ->
            val base: Long = bytesDone
            // A fresh parser per file: the Matcher and the timestamp cache are per-instance.
            val index: LogIndex = LogIndexer(ProfileEntryParser(profile))
                .index(file) { readSoFar -> progress?.report(OpenPhase.Indexing, base + readSoFar, totalBytes) }
            bytesDone += file.length()
            index
        }

        progress?.report(OpenPhase.Merging, totalBytes, totalBytes)
        val merged: LogIndex = IndexMerger.merge(indexes, accepted.map { file -> file.name })

        return LogSource(
            index = merged,
            text = TextSources(accepted),
            profile = profile,
            detection = detection,
            files = accepted,
            skipped = skipped,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    /** Folders one level deep; hidden and empty files dropped, extensions never consulted. */
    private fun expand(paths: List<File>): List<File> = paths.flatMap { path ->
        when {
            path.isDirectory -> path.listFiles().orEmpty().filter { file -> file.isFile }.sortedBy { file -> file.name }
            path.isFile -> listOf(path)
            else -> emptyList()
        }
    }.filter { file -> file.canRead() && file.length() > 0 && !file.name.startsWith(".") }
}

class NoMatchingProfileException(file: File, profileNames: List<String>) : IllegalArgumentException(
    "No profile recognises '${file.name}'. Tried: ${profileNames.joinToString(", ")}. " +
        "Add one to ~/.loupe/profiles/ and reopen.",
)
