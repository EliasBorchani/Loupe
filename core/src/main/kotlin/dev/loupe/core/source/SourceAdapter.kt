package dev.loupe.core.source

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Turns a file the indexer cannot read into one it can.
 *
 * The engine indexes **lines**: it memory-maps a file, classifies each line as opening an entry or
 * continuing one, and records byte offsets. A container format — a JSON document, an archive —
 * has no lines in that sense, so no profile can ever describe it. Rather than teach the indexer
 * about containers, an adapter renders one into the plain text the indexer already understands,
 * and everything downstream stays exactly as it was.
 *
 * The rendered file is written to a temporary directory that [LogSource.close] removes, and it is
 * given the **same name** as the original so the `file` facet keeps reading right.
 */
interface SourceAdapter {

    /** Named in the UI when a file was converted, so the transformation is never invisible. */
    val name: String

    /**
     * Whether this adapter recognises [file], from a cheap look at its opening bytes.
     *
     * Must be fast and must not throw: it is asked of every file that is opened, including ones
     * no adapter will claim.
     */
    fun claims(file: File): Boolean

    /** Renders [source] into [destination] as indexable text. */
    fun convert(source: File, destination: File): ConversionReport
}

/**
 * An adapter that renders into [CanonicalLine], and therefore knows which profile reads its output.
 *
 * Split from [SourceAdapter] rather than added to it because the next two adapters are `.gz` and
 * `.zip`, which emit whatever was inside the archive: no shape, no paired profile, ordinary
 * detection. A nullable `shape` would put a `?.` on every use of a property that is either always
 * there or never.
 */
interface CanonicalSourceAdapter : SourceAdapter {

    /** The columns this adapter writes, from which the paired profile's regexes are derived. */
    val shape: CanonicalLineShape

    /**
     * The bundled profile that reads this adapter's output.
     *
     * Named rather than detected: two adapter-emitted profiles scoring against each other is a race
     * that used to be settled by hand-written `priority` values.
     */
    val emittedProfileName: String
}

/** What a conversion did, so it can be reported rather than silently assumed. */
class ConversionReport(val entriesWritten: Long, val note: String)

/** Everything that was converted while opening, for the UI to show. */
class ConvertedSource(val original: File, val adapterName: String, val report: ConversionReport)

/** Enough of a file to recognise its container, and never enough to matter if the file is huge. */
internal const val SNIFF_BYTES: Int = 4096

private const val NEWLINE: Byte = '\n'.code.toByte()

/**
 * The file's first line, or its first [SNIFF_BYTES] when the line is longer than that.
 *
 * `InputStream.read` may return fewer bytes than asked for, and both adapters used to call it once:
 * a short read handed `claims()` a fragment of the first line with no newline in it, and the
 * adapter judged the file on that. Looped now, until a newline arrives, the buffer fills, or the
 * stream ends.
 *
 * Returns `""` rather than throwing, because [SourceAdapter.claims] is asked of every file that is
 * opened — including unreadable ones, whose real error the loader reports far better than this could.
 */
internal fun sniffFirstLine(file: File): String = try {
    val buffer = ByteArray(SNIFF_BYTES)
    var filled = 0
    var newline = -1
    file.inputStream().use { stream ->
        while (filled < buffer.size && newline < 0) {
            val read: Int = stream.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            val scanFrom: Int = filled
            filled += read
            for (position in scanFrom until filled) {
                if (buffer[position] == NEWLINE) {
                    newline = position
                    break
                }
            }
        }
    }
    val end: Int = if (newline >= 0) newline else filled
    // A byte-order mark is not whitespace, so trimming would leave it in front of the `{` every
    // caller below is looking for.
    String(buffer, 0, end, StandardCharsets.UTF_8).removePrefix("\uFEFF")
} catch (failure: IOException) {
    ""
}

object SourceAdapters {

    /**
     * The order is the priority order. Kept short deliberately — an adapter is a claim that a whole
     * file format is worth supporting, not a convenience.
     */
    val all: List<SourceAdapter> = listOf(AndroidStudioLogcatAdapter, JsonLinesAdapter)

    /**
     * Adapters must be mutually exclusive.
     *
     * Two of them claiming one file is a bug, and resolving it by list order would hide it behind a
     * conversion that quietly produces the wrong text. The disjointness used to be argued in two
     * KDocs and encoded a third time as the order of [all]; it is a check and a test now, and the
     * order carries no meaning.
     */
    /**
     * Profiles an adapter has spoken for. Kept out of detection — see [ProfileRegistry.excluding].
     *
     * Derived from the adapters rather than listed, because a name written in two places is a name
     * that will disagree with itself.
     */
    val pairedProfileNames: Set<String> = all.filterIsInstance<CanonicalSourceAdapter>()
        .map { adapter -> adapter.emittedProfileName }
        .toSet()

    fun claiming(file: File): SourceAdapter? {
        val claimants: List<SourceAdapter> = all.filter { adapter -> adapter.claims(file) }
        require(claimants.size <= 1) {
            "'${file.name}' is claimed by ${claimants.joinToString { adapter -> adapter.name }}. " +
                "Source adapters must be mutually exclusive."
        }
        return claimants.firstOrNull()
    }
}
