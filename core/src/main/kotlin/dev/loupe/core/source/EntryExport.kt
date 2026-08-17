package dev.loupe.core.source

import dev.loupe.core.index.LogIndex
import java.io.BufferedWriter
import java.io.File

/**
 * Writes entries out as the text they came from.
 *
 * Streamed, and **not capped**. The clipboard is capped because it is for pasting into a ticket;
 * a file is for the whole thing, and a nine-million-entry export is exactly the case someone
 * reaches for it. Entries are decoded one at a time straight into the writer, so the peak cost is
 * one entry rather than the result.
 */
object EntryExport {

    private const val BUFFER_BYTES = 1 shl 20

    /**
     * @param entries indices into the index, usually a filter's result.
     * @param count how many of [entries] to write.
     * @param onProgress invoked every [PROGRESS_EVERY] entries, for a status line.
     * @return the number of entries written.
     */
    fun write(
        source: LogSource,
        entries: IntArray,
        count: Int,
        target: File,
        onProgress: ((written: Int, total: Int) -> Unit)? = null,
    ): Int {
        val index: LogIndex = source.index
        target.bufferedWriter(bufferSize = BUFFER_BYTES).use { writer: BufferedWriter ->
            for (position in 0 until count) {
                val entry: Int = entries[position]
                writer.write(
                    source.text.decode(index.fileIdOf(entry), index.byteOffsets[entry], index.byteLengths[entry]),
                )
                writer.newLine()
                if (position % PROGRESS_EVERY == 0) onProgress?.invoke(position, count)
            }
        }
        onProgress?.invoke(count, count)
        return count
    }

    const val PROGRESS_EVERY: Int = 50_000
}
