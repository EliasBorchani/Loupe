package dev.loupe.core.source

import java.io.File

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

object SourceAdapters {

    /**
     * The order is the priority order. Kept short deliberately — an adapter is a claim that a whole
     * file format is worth supporting, not a convenience.
     */
    val all: List<SourceAdapter> = listOf(AndroidStudioLogcatAdapter, JsonLinesAdapter)

    fun claiming(file: File): SourceAdapter? = all.firstOrNull { adapter -> adapter.claims(file) }
}
