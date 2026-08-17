package dev.loupe.core.index

import dev.loupe.core.profile.CompiledProfile

/**
 * Why a line could not be accounted for.
 *
 * The count alone says a profile is imperfect; the *shape* says which part of it is wrong, and
 * they point at very different things — which matters most when writing a new profile, where the
 * first draft is always wrong somewhere.
 */
enum class UnrecognisedKind(val label: String, val meaning: String) {
    /** Almost always benign: a partial write, or another writer sharing the file. */
    Empty(
        label = "empty line",
        meaning = "A blank line between entries. Usually a partial write, or a second writer on the same file.",
    ),

    /** The continuation rule is close but not exact — a different indent width, or tabs. */
    Blank(
        label = "whitespace only",
        meaning = "Whitespace that entry.continues did not accept. Check the indent width the writer actually uses.",
    ),

    /** Indented, so it wants to be a continuation, but the rule says no. */
    NearContinuation(
        label = "indented, but not a continuation",
        meaning = "Looks like a wrapped message or a stack frame. entry.continues is probably too strict — " +
            "an older writer with a different timestamp width indents by a different amount.",
    ),

    /** The most informative one: the pre-filter said yes and the full regex said no. */
    NearEntry(
        label = "looks like an entry, but parse.regex rejects it",
        meaning = "The format has a shape the profile does not describe. This is the one worth fixing.",
    ),

    /** Something from another producer entirely. */
    Other(
        label = "something else",
        meaning = "Not an entry, not a continuation, not a declared marker. Written by a different code path.",
    ),
    ;

    companion object {
        fun of(profile: CompiledProfile, buffer: ByteArray, start: Int, end: Int): UnrecognisedKind {
            if (end == start) return Empty
            if (profile.opens?.matches(buffer, start, end) == true) return NearEntry
            var allBlank = true
            for (index in start until end) {
                val byte: Byte = buffer[index]
                if (byte != SPACE && byte != TAB && byte != CARRIAGE_RETURN) {
                    allBlank = false
                    break
                }
            }
            if (allBlank) return Blank
            val first: Byte = buffer[start]
            return if (first == SPACE || first == TAB) NearContinuation else Other
        }

        private const val SPACE = ' '.code.toByte()
        private const val TAB = '\t'.code.toByte()
        private const val CARRIAGE_RETURN = '\r'.code.toByte()
    }
}

/** One kept example, with enough to find it again. */
class UnrecognisedLine(
    val kind: UnrecognisedKind,
    /** Which file it came from, as an id into the `file` facet. `0` for a single open file. */
    val fileId: Int,
    /** 1-based, within its own file. */
    val lineNumber: Long,
    val text: String,
)

/**
 * What the profile could not explain, counted in full and sampled.
 *
 * Counted in full because the ratio is a health indicator and an approximate one is worthless.
 * Sampled because a file opened with the wrong profile has *every* line unrecognised, and holding
 * nine million strings to say so would turn a diagnostic into an out-of-memory error.
 *
 * The cap is **per kind**, not overall: one shape usually dominates, and a total cap would let it
 * crowd out the single example of the shape that actually explains the problem.
 */
class UnrecognisedReport(
    val countsByKind: IntArray,
    val samples: List<UnrecognisedLine>,
) {

    companion object {
        const val SAMPLES_PER_KIND: Int = 5

        /** A runaway line can be megabytes; nobody diagnoses anything past the first few words. */
        const val SAMPLE_MAX_CHARS: Int = 240

        val EMPTY: UnrecognisedReport = UnrecognisedReport(IntArray(UnrecognisedKind.entries.size), emptyList())

        fun merge(reports: List<UnrecognisedReport>, fileIds: List<Int>): UnrecognisedReport {
            val counts = IntArray(UnrecognisedKind.entries.size)
            val samples: MutableList<UnrecognisedLine> = mutableListOf()
            val keptPerKind = IntArray(UnrecognisedKind.entries.size)
            reports.forEachIndexed { position, report ->
                report.countsByKind.forEachIndexed { kind, count -> counts[kind] += count }
                report.samples.forEach { sample ->
                    val kind: Int = sample.kind.ordinal
                    if (keptPerKind[kind] < SAMPLES_PER_KIND) {
                        keptPerKind[kind]++
                        samples.add(
                            UnrecognisedLine(sample.kind, fileIds[position], sample.lineNumber, sample.text),
                        )
                    }
                }
            }
            return UnrecognisedReport(counts, samples)
        }
    }

    val total: Int get() = countsByKind.sum()

    fun countOf(kind: UnrecognisedKind): Int = countsByKind[kind.ordinal]

    /** Kinds that actually occurred, most common first. */
    fun kindsByCount(): List<UnrecognisedKind> = UnrecognisedKind.entries
        .filter { kind -> countOf(kind) > 0 }
        .sortedByDescending { kind -> countOf(kind) }

    fun samplesOf(kind: UnrecognisedKind): List<UnrecognisedLine> = samples.filter { sample -> sample.kind == kind }
}

/** Accumulates a report during the indexing pass. Not thread-safe; one per pass. */
internal class UnrecognisedCollector(private val profile: CompiledProfile) {

    private val counts = IntArray(UnrecognisedKind.entries.size)
    private val keptPerKind = IntArray(UnrecognisedKind.entries.size)
    private val samples: MutableList<UnrecognisedLine> = mutableListOf()

    fun record(buffer: ByteArray, start: Int, end: Int, lineNumber: Long) {
        val kind: UnrecognisedKind = UnrecognisedKind.of(profile, buffer, start, end)
        counts[kind.ordinal]++
        if (keptPerKind[kind.ordinal] >= UnrecognisedReport.SAMPLES_PER_KIND) return
        keptPerKind[kind.ordinal]++
        val length: Int = minOf(end - start, UnrecognisedReport.SAMPLE_MAX_CHARS)
        samples.add(
            UnrecognisedLine(
                kind = kind,
                fileId = 0,
                lineNumber = lineNumber,
                text = String(buffer, start, length, Charsets.UTF_8),
            ),
        )
    }

    fun build(): UnrecognisedReport = UnrecognisedReport(counts, samples)
}
