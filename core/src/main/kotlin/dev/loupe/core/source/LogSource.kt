package dev.loupe.core.source

import dev.loupe.core.index.IndexMerger
import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.io.MappedText
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

    /**
     * One entry's text, exactly as its file had it.
     *
     * The only place `(fileId, byteOffset, byteLength)` are read together. That triple was spelled
     * out in three places — the renderer, the exporter and the clipboard — and a fourth would have
     * been written the next time something needed an entry's text.
     */
    fun rawText(entry: Int): String =
        text.decode(index.fileIdOf(entry), index.byteOffsets[entry], index.byteLengths[entry])

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
 *  3. **Detect on the largest file** — unless it was converted, in which case the adapter *names*
 *     the profile that reads its own output rather than letting it compete. Scoring every file
 *     first would be honest but slow, and the biggest file is the most representative sample.
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
        refuseUnmappable(candidates)

        val prepared: Prepared = prepare(candidates, progress)
        // Again, on what will actually be mapped: a conversion usually shrinks its input, but
        // nothing guarantees it, and the size that matters is the one the mapping will see.
        refuseUnmappable(prepared.files.map { file -> file.readable })
        val totalBytes: Long = prepared.files.sumOf { file -> file.readable.length() }
        progress?.report(OpenPhase.Detecting, 0, totalBytes)

        val largest: PreparedFile = prepared.files.maxBy { file -> file.readable.length() }
        val detection: ProfileMatch = detectOrPin(registry, largest)
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
     * The profile for the file the rest of the folder will be scored against.
     *
     * A file a [CanonicalSourceAdapter] converted is **pinned, not detected**: that adapter wrote the
     * text, so it knows which profile reads it. Two adapter-emitted profiles competing on score used
     * to be settled by hand-written `priority` values in the TOMLs — a race neither of them should
     * have been in.
     *
     * It is still scored, because a pass that skipped scoring would hide the one failure this can
     * have: a writer that drifted from its profile. That gets its own message rather than falling
     * through to whichever other profile happens to match the output best.
     */
    private fun detectOrPin(registry: ProfileRegistry, file: PreparedFile): ProfileMatch {
        val adapter: CanonicalSourceAdapter = file.adapter as? CanonicalSourceAdapter
            ?: return detectAmongOpenProfiles(registry, file)

        val pinned: ProfileRegistry = ProfileRegistry(
            registry.profiles.filter { profile -> profile.name == adapter.emittedProfileName },
        )
        require(pinned.profiles.isNotEmpty()) {
            "${adapter.name} needs the '${adapter.emittedProfileName}' profile, which is not in this registry."
        }
        return pinned.best(file.readable) ?: throw IllegalStateException(
            "${adapter.name} converted '${file.original.name}', but '${adapter.emittedProfileName}' does not " +
                "recognise the result — the writer and its profile have drifted apart.",
        )
    }

    private fun detectAmongOpenProfiles(registry: ProfileRegistry, file: PreparedFile): ProfileMatch {
        val open: ProfileRegistry = registry.excluding(SourceAdapters.pairedProfileNames)
        return open.best(file.readable)
            ?: throw NoMatchingProfileException(file.original, open.profiles.map { profile -> profile.name })
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
            return Prepared(candidates.map { file -> PreparedFile(file, file, adapter = null) }, emptyList(), null)
        }

        val directory: File = Files.createTempDirectory("loupe-converted").toFile()
        val converted: MutableList<ConvertedSource> = mutableListOf()
        val totalBytes: Long = candidates.sumOf { file -> file.length() }
        var bytesDone = 0L
        val files: List<PreparedFile> = candidates.mapIndexed { position, file ->
            val adapter: SourceAdapter? = adapters[position]
            bytesDone += file.length()
            if (adapter == null) {
                PreparedFile(file, file, adapter = null)
            } else {
                progress?.report(OpenPhase.Converting, bytesDone, totalBytes)
                val destination = File(directory, file.name)
                converted.add(ConvertedSource(file, adapter.name, adapter.convert(file, destination)))
                PreparedFile(file, destination, adapter)
            }
        }
        return Prepared(files, converted, directory)
    }

    /**
     * A file too large to memory-map, refused before anything expensive happens.
     *
     * [MappedText] is built at the very end of [open], after detection, conversion, indexing and
     * merging — so without this the user waits through a full index of a 3 GiB file to be told it
     * cannot be opened at all. Checked twice, on the way in and after conversion.
     */
    private fun refuseUnmappable(files: List<File>) {
        val tooLarge: File = files.firstOrNull { file -> file.length() > MappedText.MAX_MAPPING_BYTES } ?: return
        throw IllegalArgumentException(
            "'${tooLarge.name}' is ${tooLarge.length()} bytes. Files above " +
                "${MappedText.MAX_MAPPING_BYTES} cannot be memory-mapped in one piece yet — split it, " +
                "or open the part you need.",
        )
    }

    /**
     * Folders **one level deep** — a nested folder is not descended into, and its files are not
     * opened. Hidden and empty files are dropped, and an extension is never consulted: HealthMate
     * day files are named `2026-06-02` with none at all.
     */
    private fun expand(paths: List<File>): List<File> = paths.flatMap { path ->
        when {
            path.isDirectory -> path.listFiles().orEmpty().filter { file -> file.isFile }.sortedBy { file -> file.name }
            path.isFile -> listOf(path)
            else -> emptyList()
        }
    }.filter { file -> file.canRead() && file.length() > 0 && !file.name.startsWith(".") }

    /** What the user chose, and what the indexer will actually read. The same file, unless converted. */
    private class PreparedFile(val original: File, val readable: File, val adapter: SourceAdapter?)

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
