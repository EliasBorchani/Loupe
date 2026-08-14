package dev.loupe.core.index

import dev.loupe.core.io.ChunkedLineReader
import dev.loupe.core.model.LogLevel
import dev.loupe.core.parse.EntryParser
import dev.loupe.core.parse.ParsedEntry
import java.io.File

/**
 * Single sequential pass: file → [LogIndex].
 *
 * Every per-entry cost lives in this loop, so everything it needs is pre-allocated and reused —
 * one [ParsedEntry] sink, growable primitive columns, and dictionaries fed straight from the read
 * buffer. Facet counts and level counts fall out of the same pass; only the timeline histogram
 * needs the min/max first, and that is a second sweep over an already-warm `LongArray`.
 */
class LogIndexer(private val parser: EntryParser) {

    companion object {
        private const val INITIAL_CAPACITY = 1 shl 16
    }

    fun index(file: File): LogIndex {
        val columns = EntryColumns(INITIAL_CAPACITY)
        val categories = ValueDictionary()
        val tags = ValueDictionary(expectedValues = 1024)
        val levelCounts = IntArray(LogLevel.entries.size)
        val sink = ParsedEntry()

        var continuationLineCount = 0L
        var unparsedLineCount = 0L
        // Byte range of the entry currently being accumulated; -1 when there is none open.
        var openEntryOffset = -1L
        var openEntryEndOffset = -1L

        val lineCount: Long = ChunkedLineReader(file).forEachLine { buffer, start, end, fileOffset ->
            val lineEndOffset: Long = fileOffset + (end - start)

            if (parser.parseOpening(buffer, start, end, sink)) {
                if (openEntryOffset >= 0L) {
                    columns.closeLastEntry((openEntryEndOffset - openEntryOffset).toInt())
                }
                val categoryId: Int =
                    if (sink.hasCategory) categories.intern(buffer, sink.categoryStart, sink.categoryEnd) else LogIndex.NO_VALUE
                val tagId: Int = tags.intern(buffer, sink.tagStart, sink.tagEnd)

                columns.append(sink.timestampMillis, sink.levelOrdinal.toByte(), categoryId, tagId, fileOffset)
                if (sink.levelOrdinal >= 0) levelCounts[sink.levelOrdinal]++

                openEntryOffset = fileOffset
                openEntryEndOffset = lineEndOffset
            } else if (openEntryOffset >= 0L && parser.isContinuation(buffer, start, end)) {
                // Not a new entry — it is the previous one's message wrapping. Just extend its range.
                continuationLineCount++
                openEntryEndOffset = lineEndOffset
            } else {
                unparsedLineCount++
            }
        }

        if (openEntryOffset >= 0L) {
            columns.closeLastEntry((openEntryEndOffset - openEntryOffset).toInt())
        }

        return columns.build(
            categories = categories,
            tags = tags,
            levelCounts = levelCounts,
            lineCount = lineCount,
            continuationLineCount = continuationLineCount,
            unparsedLineCount = unparsedLineCount,
        )
    }
}

/** Growable primitive columns. Doubling growth; trimmed to size once at [build]. */
private class EntryColumns(initialCapacity: Int) {

    private var size = 0
    private var timestamps = LongArray(initialCapacity)
    private var levels = ByteArray(initialCapacity)
    private var categoryIds = IntArray(initialCapacity)
    private var tagIds = IntArray(initialCapacity)
    private var byteOffsets = LongArray(initialCapacity)
    private var byteLengths = IntArray(initialCapacity)

    fun append(timestampMillis: Long, level: Byte, categoryId: Int, tagId: Int, byteOffset: Long) {
        if (size == timestamps.size) grow()
        timestamps[size] = timestampMillis
        levels[size] = level
        categoryIds[size] = categoryId
        tagIds[size] = tagId
        byteOffsets[size] = byteOffset
        size++
    }

    /** The length is only known once the following opening line (or EOF) is reached. */
    fun closeLastEntry(byteLength: Int) {
        byteLengths[size - 1] = byteLength
    }

    fun build(
        categories: ValueDictionary,
        tags: ValueDictionary,
        levelCounts: IntArray,
        lineCount: Long,
        continuationLineCount: Long,
        unparsedLineCount: Long,
    ): LogIndex = LogIndex(
        entryCount = size,
        timestamps = timestamps.copyOf(size),
        levels = levels.copyOf(size),
        categoryIds = categoryIds.copyOf(size),
        tagIds = tagIds.copyOf(size),
        byteOffsets = byteOffsets.copyOf(size),
        byteLengths = byteLengths.copyOf(size),
        categories = categories,
        tags = tags,
        levelCounts = levelCounts,
        lineCount = lineCount,
        continuationLineCount = continuationLineCount,
        unparsedLineCount = unparsedLineCount,
    )

    private fun grow() {
        val capacity: Int = timestamps.size * 2
        timestamps = timestamps.copyOf(capacity)
        levels = levels.copyOf(capacity)
        categoryIds = categoryIds.copyOf(capacity)
        tagIds = tagIds.copyOf(capacity)
        byteOffsets = byteOffsets.copyOf(capacity)
        byteLengths = byteLengths.copyOf(capacity)
    }
}
