package dev.loupe.core.parse

import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.LocalTimestampResolver
import dev.loupe.core.profile.LevelDecoder
import java.util.regex.Matcher

/**
 * The general parser: any format, driven entirely by its [CompiledProfile].
 *
 * This is the M0 winner (strategy A) generalised — decode the line, run the profile's `Pattern`,
 * read groups by number. M0 measured 386 ns/entry for the hardcoded version of exactly this shape,
 * which is 2.6× inside the indexing budget, so nothing here needs to be cleverer than it looks.
 *
 * The two things that *are* load-bearing:
 *  - the profile's `entry.continues` predicate is tested by the indexer **before** this parser is
 *    ever called, so the regex runs once per entry rather than once per line;
 *  - facet offsets are reported in the decoded `String`'s coordinates, not the buffer's, so a
 *    message — or a facet — containing a multi-byte character cannot shift them.
 *
 * Not thread-safe: the `Matcher` and the timestamp cache are per-instance. One parser per worker.
 */
class ProfileEntryParser(override val profile: CompiledProfile) : EntryParser {

    override val name: String = "profile:${profile.name}"

    private val matcher: Matcher = profile.pattern.matcher("")
    private val timestamps: LocalTimestampResolver = profile.timestampFormat.newResolver()
    private val facetGroups: IntArray = IntArray(profile.facets.size) { index -> profile.facets[index].group }
    private val levelDecoder: LevelDecoder? = profile.levelDecoder

    override fun newSink(): ParsedEntry = ParsedEntry(profile.facets.size, facetsAreCharOffsets = true)

    override fun parseOpening(buffer: ByteArray, start: Int, end: Int, sink: ParsedEntry): Boolean {
        val opens = profile.opens
        if (opens != null && !opens.matches(buffer, start, end)) return false

        val line = String(buffer, start, end - start, Charsets.UTF_8)
        matcher.reset(line)
        if (!matcher.matches()) return false

        sink.line = line
        sink.timestampMillis = profile.timestampFormat.read(
            chars = line,
            offset = matcher.start(profile.timestampGroup),
            end = matcher.end(profile.timestampGroup),
            resolver = timestamps,
        )
        sink.levelOrdinal = if (levelDecoder == null || profile.levelGroup == CompiledProfile.NO_GROUP) {
            LevelDecoder.UNKNOWN_ORDINAL
        } else {
            levelDecoder.ordinalOf(line, matcher.start(profile.levelGroup), matcher.end(profile.levelGroup))
        }

        for (facetIndex in facetGroups.indices) {
            val groupStart: Int = matcher.start(facetGroups[facetIndex])
            if (groupStart < 0) {
                // An optional group that did not participate — the absent `[Category]` of a
                // HealthMate line written through a deprecated overload, for instance.
                sink.facetStarts[facetIndex] = ParsedEntry.ABSENT
                sink.facetEnds[facetIndex] = ParsedEntry.ABSENT
            } else {
                sink.facetStarts[facetIndex] = groupStart
                sink.facetEnds[facetIndex] = matcher.end(facetGroups[facetIndex])
            }
        }
        return true
    }

    override fun isContinuation(buffer: ByteArray, start: Int, end: Int): Boolean =
        profile.continues?.matches(buffer, start, end) ?: false
}
