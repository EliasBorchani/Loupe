package dev.loupe.core.io

import java.io.Closeable
import java.io.File

/**
 * Random access to the text of every file behind an index.
 *
 * A merged view interleaves entries from several day files by timestamp, so an entry's byte range
 * only means anything alongside the file it came from. Everything downstream — rendering a row,
 * searching, copying a selection — goes through here with a `fileId` that the index carries as its
 * `file` facet.
 *
 * The text itself never enters the heap: each file is memory-mapped and the OS page cache does the
 * work. Opening a week of HealthMate logs costs seven mappings and no allocation per entry.
 */
class TextSources(val files: List<File>) : Closeable {

    companion object {
        fun of(file: File): TextSources = TextSources(listOf(file))
    }

    val fileCount: Int get() = files.size

    private val mappings: List<MappedText> = files.map { file -> MappedText(file) }

    val totalBytes: Long = mappings.sumOf { mapping -> mapping.sizeBytes }

    fun nameOf(fileId: Int): String = files[fileId].name

    /** Copies one entry's bytes out of its mapping — for display, one entry at a time. */
    fun decode(fileId: Int, offset: Long, length: Int): String = mappings[fileId].decode(offset, length)

    /** [needleLowercase] must already be lowercased by the caller, once per query. */
    fun containsIgnoreCase(fileId: Int, offset: Long, length: Int, needleLowercase: ByteArray): Boolean =
        mappings[fileId].containsIgnoreCase(offset, length, needleLowercase)

    override fun close() {
        mappings.forEach { mapping -> mapping.close() }
    }
}
