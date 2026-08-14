package dev.loupe.core.index

import dev.loupe.core.io.ChunkedLineReader
import dev.loupe.core.parse.EntryParser
import dev.loupe.core.parse.ParsedEntry
import dev.loupe.core.profile.MarkerRole
import java.io.File

/**
 * Single sequential pass: file → [LogIndex].
 *
 * Every per-entry cost lives in this loop, so everything it needs is pre-allocated and reused —
 * one [ParsedEntry] sink, growable primitive columns, dictionaries fed without decoding. Facet
 * counts and level counts fall out of the same pass; only the timeline histogram needs the min and
 * max first, and that is a second sweep over an already-warm `LongArray`.
 *
 * **Line classification order matters.** Continuation is tested first, before the parse regex is
 * ever reached. M0 measured 18.6 % of lines in a real HealthMate file as continuations, and the
 * profile's `entry.continues` usually reduces to a literal prefix check — so nearly a fifth of the
 * file is dismissed in a handful of byte comparisons instead of a regex match. Reversing these two
 * branches is the difference between one expensive match per entry and one per line.
 */
class LogIndexer(private val parser: EntryParser) {

    companion object {
        private const val INITIAL_CAPACITY = 1 shl 16
    }

    /**
     * @param onBytesRead invoked as the file is consumed, for a progress bar. Chunk granularity.
     */
    fun index(file: File, onBytesRead: ((Long) -> Unit)? = null): LogIndex {
        val facetCount: Int = parser.profile.facets.size
        val columns = EntryColumns(INITIAL_CAPACITY, facetCount)
        val dictionaries: Array<ValueDictionary> = Array(facetCount) { ValueDictionary(expectedValues = 64) }
        val levelCounts = IntArray(maxOf(parser.profile.levelCount, 1))
        val sink: ParsedEntry = parser.newSink()
        val markers = parser.profile.markers

        var continuationLineCount = 0L
        var sectionLineCount = 0L
        var noticeLineCount = 0L
        var unrecognisedLineCount = 0L
        // Byte range of the entry currently being accumulated; -1 when there is none open.
        var openEntryOffset = -1L
        var openEntryEndOffset = -1L

        val lineCount: Long = ChunkedLineReader(file).forEachLine(onBytesRead) { buffer, start, end, fileOffset ->
            val lineEndOffset: Long = fileOffset + (end - start)

            if (openEntryOffset >= 0L && parser.isContinuation(buffer, start, end)) {
                // Not a new entry — the previous one's message wrapping. Just extend its range.
                continuationLineCount++
                openEntryEndOffset = lineEndOffset
            } else if (parser.parseOpening(buffer, start, end, sink)) {
                if (openEntryOffset >= 0L) {
                    columns.closeLastEntry((openEntryEndOffset - openEntryOffset).toInt())
                }
                columns.append(sink, dictionaries, buffer, fileOffset)
                if (sink.levelOrdinal >= 0) levelCounts[sink.levelOrdinal]++

                openEntryOffset = fileOffset
                openEntryEndOffset = lineEndOffset
            } else {
                // Not an entry and not a continuation. Classify it rather than lose it: an export
                // separator or a truncation notice is information, not noise.
                when (classify(markers, buffer, start, end)) {
                    MarkerRole.Section -> sectionLineCount++
                    MarkerRole.Notice -> noticeLineCount++
                    null -> unrecognisedLineCount++
                }
            }
        }

        if (openEntryOffset >= 0L) {
            columns.closeLastEntry((openEntryEndOffset - openEntryOffset).toInt())
        }

        return columns.build(
            profile = parser.profile,
            dictionaries = dictionaries,
            levelCounts = levelCounts,
            lineCount = lineCount,
            continuationLineCount = continuationLineCount,
            sectionLineCount = sectionLineCount,
            noticeLineCount = noticeLineCount,
            unrecognisedLineCount = unrecognisedLineCount,
        )
    }

    /**
     * Markers are rare by construction, so this is allowed to allocate: it only ever runs on lines
     * that are neither an entry nor a continuation.
     */
    private fun classify(
        markers: List<dev.loupe.core.profile.CompiledMarker>,
        buffer: ByteArray,
        start: Int,
        end: Int,
    ): MarkerRole? {
        if (markers.isEmpty()) return null
        val line = String(buffer, start, end - start, Charsets.UTF_8)
        return markers.firstOrNull { marker -> marker.pattern.matcher(line).find() }?.role
    }
}

/** Growable primitive columns. Doubling growth; trimmed to size once at [build]. */
private class EntryColumns(initialCapacity: Int, private val facetCount: Int) {

    private var size = 0
    private var timestamps = LongArray(initialCapacity)
    private var levels = ByteArray(initialCapacity)
    private var facetValues: Array<IntArray> = Array(facetCount) { IntArray(initialCapacity) }
    private var byteOffsets = LongArray(initialCapacity)
    private var byteLengths = IntArray(initialCapacity)

    fun append(sink: ParsedEntry, dictionaries: Array<ValueDictionary>, buffer: ByteArray, fileOffset: Long) {
        if (size == timestamps.size) grow()
        timestamps[size] = sink.timestampMillis
        byteOffsets[size] = fileOffset
        levels[size] = sink.levelOrdinal.toByte()
        for (facetIndex in 0 until facetCount) {
            facetValues[facetIndex][size] = if (!sink.hasFacet(facetIndex)) {
                LogIndex.NO_VALUE
            } else if (sink.facetsAreCharOffsets) {
                dictionaries[facetIndex].intern(sink.line, sink.facetStarts[facetIndex], sink.facetEnds[facetIndex])
            } else {
                dictionaries[facetIndex].internBytes(buffer, sink.facetStarts[facetIndex], sink.facetEnds[facetIndex])
            }
        }
        size++
    }

    /** Set on the way past: the length is only known once the next opening line, or EOF, arrives. */
    fun closeLastEntry(byteLength: Int) {
        byteLengths[size - 1] = byteLength
    }

    fun build(
        profile: dev.loupe.core.profile.CompiledProfile,
        dictionaries: Array<ValueDictionary>,
        levelCounts: IntArray,
        lineCount: Long,
        continuationLineCount: Long,
        sectionLineCount: Long,
        noticeLineCount: Long,
        unrecognisedLineCount: Long,
    ): LogIndex = LogIndex(
        profile = profile,
        facets = profile.facets,
        entryCount = size,
        timestamps = timestamps.copyOf(size),
        levels = levels.copyOf(size),
        facetValues = Array(facetCount) { facetIndex -> facetValues[facetIndex].copyOf(size) },
        facetDictionaries = dictionaries,
        byteOffsets = byteOffsets.copyOf(size),
        byteLengths = byteLengths.copyOf(size),
        levelCounts = levelCounts,
        lineCount = lineCount,
        continuationLineCount = continuationLineCount,
        sectionLineCount = sectionLineCount,
        noticeLineCount = noticeLineCount,
        unrecognisedLineCount = unrecognisedLineCount,
    )

    private fun grow() {
        val capacity: Int = timestamps.size * 2
        timestamps = timestamps.copyOf(capacity)
        levels = levels.copyOf(capacity)
        facetValues = Array(facetCount) { facetIndex -> facetValues[facetIndex].copyOf(capacity) }
        byteOffsets = byteOffsets.copyOf(capacity)
        byteLengths = byteLengths.copyOf(capacity)
    }
}
