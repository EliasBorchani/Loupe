package dev.loupe.core.io

import java.io.Closeable
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Random access to the log text, kept out of the heap.
 *
 * The index only stores `(offset, length)` per entry; the bytes themselves stay in the page cache
 * behind this mapping. Rendering a screenful decodes ~40 entries on demand, and full-text search
 * runs over the raw bytes — neither ever materialises the file as `String`s.
 */
class MappedText(file: File) : Closeable {

    companion object {
        /** `FileChannel.map` is capped at `Integer.MAX_VALUE` bytes per mapping. */
        private const val MAX_MAPPING_BYTES: Long = Int.MAX_VALUE.toLong()

        private const val ASCII_UPPER_A = 'A'.code.toByte()
        private const val ASCII_UPPER_Z = 'Z'.code.toByte()
        private const val ASCII_CASE_SHIFT = ('a' - 'A').toByte()
    }

    val sizeBytes: Long

    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    private val mapping: MappedByteBuffer

    init {
        sizeBytes = channel.size()
        // TODO(M1): segment the mapping so files above 2 GiB load. The M0 fixture is 1 GiB.
        require(sizeBytes <= MAX_MAPPING_BYTES) {
            "File is ${sizeBytes} bytes; mappings above $MAX_MAPPING_BYTES need segmenting (not yet implemented)"
        }
        mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, sizeBytes)
    }

    /** Copies `[offset, offset + length)` out of the mapping — for display, one entry at a time. */
    fun decode(offset: Long, length: Int): String {
        val bytes = ByteArray(length)
        mapping.get(offset.toInt(), bytes, 0, length)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Case-insensitive ASCII substring search inside one entry's byte range.
     *
     * [needleLowercase] must already be lowercased by the caller (once per query, not per entry).
     * Non-ASCII bytes compare exactly, which is the same behaviour `grep -i` gives without a locale.
     */
    fun containsIgnoreCase(offset: Long, length: Int, needleLowercase: ByteArray): Boolean {
        val needleLength: Int = needleLowercase.size
        if (needleLength == 0) return true
        if (needleLength > length) return false

        val start: Int = offset.toInt()
        val lastStart: Int = start + length - needleLength
        val firstByte: Byte = needleLowercase[0]
        var candidate: Int = start
        while (candidate <= lastStart) {
            if (toLowerAscii(mapping.get(candidate)) == firstByte) {
                var matchOffset = 1
                while (matchOffset < needleLength &&
                    toLowerAscii(mapping.get(candidate + matchOffset)) == needleLowercase[matchOffset]
                ) {
                    matchOffset++
                }
                if (matchOffset == needleLength) return true
            }
            candidate++
        }
        return false
    }

    override fun close() {
        channel.close()
    }

    private fun toLowerAscii(byte: Byte): Byte =
        if (byte >= ASCII_UPPER_A && byte <= ASCII_UPPER_Z) (byte + ASCII_CASE_SHIFT).toByte() else byte
}
