package dev.loupe.core.io

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Callback invoked once per line, with the line's bytes still sitting in the reader's own buffer.
 *
 * [buffer] is reused across calls — copy anything you need to keep. [fileOffset] is the absolute
 * byte position of [start] within the file, which is what the index stores so a line can be
 * re-read later without another scan.
 */
fun interface LineVisitor {
    fun visit(buffer: ByteArray, start: Int, end: Int, fileOffset: Long)
}

/**
 * Reads a file line by line without allocating a `String` — or anything else — per line.
 *
 * Deliberately *not* `MappedByteBuffer`: on the JVM a per-byte `get()` on a mapped buffer keeps
 * its bounds check and does not vectorise, whereas a plain `ByteArray` scan does. The mapping
 * still earns its keep for random access afterwards (see [MappedText]); it just loses on the
 * sequential pass. Confirmed by the M0 spike.
 */
class ChunkedLineReader(
    private val file: File,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {

    companion object {
        const val DEFAULT_CHUNK_SIZE: Int = 8 shl 20 // 8 MiB

        private const val NEWLINE = '\n'.code.toByte()
        private const val CARRIAGE_RETURN = '\r'.code.toByte()
    }

    /**
     * @param onChunkRead invoked once per chunk with the total bytes consumed so far. Chunk
     *   granularity is deliberate: per-line would be nine million callbacks, per-file would leave
     *   a 1 GiB single file sitting at 0 % for three seconds.
     * @return the number of lines visited. A trailing chunk without a final newline still yields
     *   its last line.
     */
    fun forEachLine(onChunkRead: ((Long) -> Unit)? = null, visitor: LineVisitor): Long {
        var buffer = ByteArray(chunkSize)
        var lineCount = 0L

        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            // Bytes of an incomplete line carried over from the previous chunk, already at index 0.
            var carriedBytes = 0
            // Absolute file offset of buffer[0].
            var bufferFileOffset = 0L
            var consumedBytes = 0L

            while (true) {
                val readable: ByteBuffer = ByteBuffer.wrap(buffer, carriedBytes, buffer.size - carriedBytes)
                val bytesRead: Int = channel.read(readable)
                if (bytesRead <= 0) break

                consumedBytes += bytesRead
                onChunkRead?.invoke(consumedBytes)

                val filled: Int = carriedBytes + bytesRead
                var lineStart = 0
                var cursor = 0
                while (cursor < filled) {
                    if (buffer[cursor] == NEWLINE) {
                        val lineEnd: Int = if (cursor > lineStart && buffer[cursor - 1] == CARRIAGE_RETURN) cursor - 1 else cursor
                        visitor.visit(buffer, lineStart, lineEnd, bufferFileOffset + lineStart)
                        lineCount++
                        lineStart = cursor + 1
                    }
                    cursor++
                }

                carriedBytes = filled - lineStart
                if (lineStart == 0 && filled == buffer.size) {
                    // No newline in a *full* buffer: the line is longer than the chunk. Grow and
                    // retry rather than truncate — a stack trace glued into one line can be long.
                    // A short read with no newline is just EOF; it falls through to the tail branch.
                    buffer = buffer.copyOf(buffer.size * 2)
                } else {
                    System.arraycopy(buffer, lineStart, buffer, 0, carriedBytes)
                    bufferFileOffset += lineStart
                }
            }

            if (carriedBytes > 0) {
                val lineEnd: Int = if (buffer[carriedBytes - 1] == CARRIAGE_RETURN) carriedBytes - 1 else carriedBytes
                visitor.visit(buffer, 0, lineEnd, bufferFileOffset)
                lineCount++
            }
        }
        return lineCount
    }
}
