package dev.loupe.core.source

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId

/**
 * Writes [CanonicalLine]s, so an adapter supplies values and never layout.
 *
 * Values are set by **column reference**, not by name or index:
 *
 * ```
 * private val TAG = CanonicalColumn.Bracketed("tag", mayContainBracket = true)
 * …
 * writer.set(TAG, message.tag)
 * ```
 *
 * A `Map<String, String>` row was the obvious alternative and is the trap: a mistyped key would
 * write an empty bracketed field, the profile's regex would still match the line, and the failure
 * would be a blank facet nobody notices. Here an unset column throws, naming itself.
 *
 * The slots are reused between entries — a capture is ten thousand entries long and a fresh row per
 * entry would allocate for nothing.
 */
class CanonicalLineWriter internal constructor(
    private val shape: CanonicalLineShape,
    private val writer: Writer,
    private val zone: ZoneId,
) {

    companion object {
        private const val BUFFER_BYTES: Int = 1 shl 16

        /**
         * Streams [source] into [destination], handing [body] the reader and a writer for [shape].
         *
         * Owns the reader/writer pair and their buffers, which both adapters used to declare inline.
         */
        fun <T> render(
            source: File,
            destination: File,
            shape: CanonicalLineShape,
            body: (Reader, CanonicalLineWriter) -> T,
        ): T =
            BufferedReader(InputStreamReader(source.inputStream(), StandardCharsets.UTF_8), BUFFER_BYTES).use { reader ->
                BufferedWriter(OutputStreamWriter(destination.outputStream(), StandardCharsets.UTF_8), BUFFER_BYTES).use { sink ->
                    body(reader, CanonicalLineWriter(shape, sink, ZoneId.systemDefault()))
                }
            }
    }

    private val values: Array<String?> = arrayOfNulls(shape.columns.size)
    private val line = StringBuilder(256)

    fun set(column: CanonicalColumn, value: String) {
        values[slotOf(column)] = value
    }

    fun set(column: CanonicalColumn, value: Long) {
        values[slotOf(column)] = value.toString()
    }

    /** [trailing] goes on its own indented line — for values no column claimed. */
    fun write(instant: Instant, message: String, trailing: String? = null) {
        line.setLength(0)
        line.append(CanonicalLine.FORMAT.format(instant.atZone(zone)))
        shape.columns.forEachIndexed { slot, column ->
            val value: String = values[slot]
                ?: throw IllegalStateException("'${column.field}' was not set for this entry")
            appendColumn(column, value)
        }
        line.append(' ')
        appendFolded(message)
        if (!trailing.isNullOrEmpty()) {
            line.append('\n').append(CanonicalLine.CONTINUATION_INDENT)
            appendSingleLine(trailing)
        }
        line.append('\n')
        writer.append(line)
        values.fill(null)
    }

    private fun appendColumn(column: CanonicalColumn, value: String) {
        when (column) {
            is CanonicalColumn.Padded -> {
                // The space is written, not left to the padding: a 7-digit pid — Android allows them
                // — would fill the field and weld itself to the timestamp.
                line.append(' ').append(value.padStart(column.width))
            }

            is CanonicalColumn.Code -> line.append(' ').append(value)

            // A closed set, so nothing to sanitise.
            is CanonicalColumn.Vocabulary -> line.append(" [").append(value).append(']')

            is CanonicalColumn.Bracketed -> {
                line.append(" [")
                appendSingleLine(value)
                line.append(']')
            }
        }
    }

    /** A message keeps its own newlines; indenting them is what folds the whole thing into one entry. */
    private fun appendFolded(text: String) {
        text.lineSequence().forEachIndexed { position, textLine ->
            if (position > 0) line.append('\n').append(CanonicalLine.CONTINUATION_INDENT)
            line.append(textLine)
        }
    }

    /**
     * A newline inside a *field* is not folded, it is flattened.
     *
     * The asymmetry with [appendFolded] is the point: a newline in a message belongs to that entry
     * and indenting keeps it there, whereas a newline in a tag would forge a continuation line and
     * swallow the entry after it. A `]` in a field is written through untouched, because escaping it
     * would reach the facet still escaped — nothing downstream unescapes.
     */
    private fun appendSingleLine(value: String) {
        value.forEach { character ->
            if (character == '\n' || character == '\r') line.append(' ') else line.append(character)
        }
    }

    private fun slotOf(column: CanonicalColumn): Int {
        val slot: Int = shape.columns.indexOfFirst { candidate -> candidate === column }
        require(slot >= 0) { "'${column.field}' is not a column of this shape" }
        return slot
    }
}
