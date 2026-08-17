package dev.loupe.core.source

import dev.loupe.core.index.IndexMerger
import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.io.TextSources
import dev.loupe.core.parse.ProfileEntryParser
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.ProfileMatch
import dev.loupe.core.profile.LoadedRegistry
import dev.loupe.core.profile.ProfileRegistry
import java.io.Closeable
import java.io.File
import java.nio.file.Files

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
    /**
     * Files that were indexed, in the order the `file` facet numbers them — always the paths the
     * user actually chose, never a converted stand-in, so reopening and "add files" keep working
     * after [close] has thrown the temporary copies away.
     */
    val files: List<File>,
    /** Files that were passed in but that the chosen profile does not recognise. */
    val skipped: List<SkippedFile>,
    /** Files rendered into indexable text on the way in. Empty in the ordinary case. */
    val converted: List<ConvertedSource>,
    /** User profiles that failed to load. Reported, never fatal. */
    val profileProblems: List<String>,
    val elapsedMillis: Long,
    private val temporaryDirectory: File?,
) : Closeable {

    override fun close() {
        // Unmap before deleting: the converted text lives in that directory.
        text.close()
        temporaryDirectory?.deleteRecursively()
    }
}

class SkippedFile(val file: File, val reason: String)

/** Reported while opening, so a 1 GiB folder does not look frozen. */
fun interface OpenProgress {
    fun report(phase: OpenPhase, bytesDone: Long, bytesTotal: Long)
}

enum class OpenPhase { Converting, Detecting, Indexing, Merging }

/**
 * Opens files or folders as a single time-ordered view.
 *
 * The order of operations matters for the failure modes:
 *
 *  1. **Expand** folders. HealthMate day files are named `2026-06-02` with **no extension at all**,
 *     so nothing here may filter on one.
 *  2. **Convert** anything that is a container rather than lines — an Android Studio `.logcat`
 *     export is one JSON document, and no profile can ever describe that. See [SourceAdapter].
 *  3. **Detect on the largest file.** Scoring every file first would be honest but slow, and the
 *     biggest file is the most representative sample of what the folder is.
 *  4. **Re-score the others against the winner.** A file the chosen profile does not recognise is
 *     reported as skipped, never silently indexed into garbage.
 *  5. **Index, then merge.** Each file is indexed independently, then k-way merged by timestamp.
 */
object LogSourceLoader {

    fun open(
        paths: List<File>,
        loaded: LoadedRegistry = LoadedRegistry(ProfileRegistry.bundled(), emptyList()),
        progress: OpenProgress? = null,
    ): LogSource {
        val registry: ProfileRegistry = loaded.registry
        val startedAt: Long = System.nanoTime()
        val candidates: List<File> = expand(paths)
        require(candidates.isNotEmpty()) { "No readable file in ${paths.joinToString { path -> path.name }}" }

        val prepared: Prepared = prepare(candidates, progress)
        val totalBytes: Long = prepared.files.sumOf { file -> file.readable.length() }
        progress?.report(OpenPhase.Detecting, 0, totalBytes)

        val largest: PreparedFile = prepared.files.maxBy { file -> file.readable.length() }
        val detection: ProfileMatch = registry.best(largest.readable)
            ?: throw NoMatchingProfileException(largest.original, registry.profiles.map { profile -> profile.name })
        val profile: CompiledProfile = detection.profile

        val accepted: MutableList<PreparedFile> = mutableListOf()
        val skipped: MutableList<SkippedFile> = mutableListOf()
        val single = ProfileRegistry(listOf(profile))
        prepared.files.forEach { file ->
            if (file === largest) {
                accepted.add(file)
                return@forEach
            }
            val match: ProfileMatch? = single.best(file.readable)
            if (match == null) {
                skipped.add(SkippedFile(file.original, "not recognised by ${profile.name}"))
            } else {
                accepted.add(file)
            }
        }
        // Chronological by name is the right default for day files, and harmless otherwise: the
        // merge sorts by timestamp anyway, this only fixes the `file` facet's numbering.
        accepted.sortBy { file -> file.original.name }

        var bytesDone = 0L
        val indexes: List<LogIndex> = accepted.map { file ->
            val base: Long = bytesDone
            // A fresh parser per file: the Matcher and the timestamp cache are per-instance.
            val index: LogIndex = LogIndexer(ProfileEntryParser(profile))
                .index(file.readable) { readSoFar -> progress?.report(OpenPhase.Indexing, base + readSoFar, totalBytes) }
            bytesDone += file.readable.length()
            index
        }

        progress?.report(OpenPhase.Merging, totalBytes, totalBytes)
        val merged: LogIndex = IndexMerger.merge(indexes, accepted.map { file -> file.original.name })

        return LogSource(
            index = merged,
            text = TextSources(accepted.map { file -> file.readable }),
            profile = profile,
            detection = detection,
            files = accepted.map { file -> file.original },
            skipped = skipped,
            converted = prepared.converted,
            profileProblems = loaded.problems,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
            temporaryDirectory = prepared.temporaryDirectory,
        )
    }

    /**
     * Renders any container into indexable text, leaving everything else alone.
     *
     * The rendered file takes the original's **name** inside a temporary directory, so the `file`
     * facet and every label read exactly as the user expects; the original path is what [LogSource]
     * keeps, so reopening still points at the real file after the temporary one is gone.
     */
    private fun prepare(candidates: List<File>, progress: OpenProgress?): Prepared {
        val adapters: List<SourceAdapter?> = candidates.map { file -> SourceAdapters.claiming(file) }
        if (adapters.all { adapter -> adapter == null }) {
            return Prepared(candidates.map { file -> PreparedFile(file, file) }, emptyList(), null)
        }

        val directory: File = Files.createTempDirectory("loupe-converted").toFile()
        val converted: MutableList<ConvertedSource> = mutableListOf()
        val totalBytes: Long = candidates.sumOf { file -> file.length() }
        var bytesDone = 0L
        val files: List<PreparedFile> = candidates.mapIndexed { position, file ->
            val adapter: SourceAdapter? = adapters[position]
            bytesDone += file.length()
            if (adapter == null) {
                PreparedFile(file, file)
            } else {
                progress?.report(OpenPhase.Converting, bytesDone, totalBytes)
                val destination = File(directory, file.name)
                converted.add(ConvertedSource(file, adapter.name, adapter.convert(file, destination)))
                PreparedFile(file, destination)
            }
        }
        return Prepared(files, converted, directory)
    }

    /** Folders one level deep; hidden and empty files dropped, extensions never consulted. */
    private fun expand(paths: List<File>): List<File> = paths.flatMap { path ->
        when {
            path.isDirectory -> path.listFiles().orEmpty().filter { file -> file.isFile }.sortedBy { file -> file.name }
            path.isFile -> listOf(path)
            else -> emptyList()
        }
    }.filter { file -> file.canRead() && file.length() > 0 && !file.name.startsWith(".") }

    /** What the user chose, and what the indexer will actually read. The same file, unless converted. */
    private class PreparedFile(val original: File, val readable: File)

    private class Prepared(
        val files: List<PreparedFile>,
        val converted: List<ConvertedSource>,
        val temporaryDirectory: File?,
    )
}

class NoMatchingProfileException(file: File, profileNames: List<String>) : IllegalArgumentException(
    "No profile recognises '${file.name}'. Tried: ${profileNames.joinToString(", ")}. " +
        "Write one and drop it in ${ProfileRegistry.userDirectory()} — it is read on every open, " +
        "so you can edit it and reopen the file without restarting.",
)
