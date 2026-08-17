package dev.loupe.core.source

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads Android Studio's `.logcat` export.
 *
 * Despite the extension this is not logcat text at all — it is one JSON document holding the whole
 * capture:
 *
 * ```json
 * { "metadata": { … },
 *   "logcatMessages": [
 *     { "header": { "logLevel": "DEBUG", "pid": 1971, "tid": 2033, "applicationId": "system_server",
 *                   "processName": "system_server", "tag": "WindowManager",
 *                   "timestamp": { "seconds": 1786963735, "nanos": 620829517 } },
 *       "message": "…" } ] }
 * ```
 *
 * One entry spans sixteen pretty-printed lines, so a line-oriented profile cannot see it. This
 * renders the document back into the logcat text everyone already reads, close to `adb logcat
 * -v year` but keeping what the export carries and plain logcat does not.
 *
 * ## Why the fields are bracketed, and in this order
 *
 * Real logcat writes `tag: message` and simply breaks when a tag contains `: ` — and tags do:
 * `ASvc::AudioMetricDataReader`, `pixelstats: MmMetrics`. Since this writes the text as well as
 * reads it, the boundary can be made unambiguous instead, so both go in brackets.
 *
 * Tags also contain `]` (`DisplayPowerController[0]` — 7 of 382 in the capture this was written
 * against). Escaping it would be the obvious answer and is the wrong one: nothing downstream
 * unescapes, so the facet would read `DisplayPowerController[0\]`. Instead the **tag goes first
 * and the process last**, because an application id is a package name and cannot contain a
 * bracket. The profile then matches the tag lazily up to `] [`, and the closing `] ` it anchors on
 * belongs to a field that is safe by construction rather than by hope.
 *
 * That leaves one theoretical hole — a tag containing `] ` followed by `[` — which mis-splits.
 * Nothing emits such a tag, and the failure is visible rather than silent.
 *
 * ## What is not preserved
 *
 * `applicationId` and `processName` were identical on all 9 994 entries of that capture, so only
 * one is written, as `process`. Should they ever differ, the application id is the one kept.
 */
object AndroidStudioLogcatAdapter : CanonicalSourceAdapter {

    private val PID = CanonicalColumn.Padded("pid", width = 5)
    private val TID = CanonicalColumn.Padded("tid", width = 5)
    private val LEVEL = CanonicalColumn.Code("level", alphabet = "VDIWEFA")

    /** Tags hold brackets — `DisplayPowerController[0]`, `[GF_HAL][DelmarHalUtils]`. */
    private val TAG = CanonicalColumn.Bracketed("tag", mayContainBracket = true)

    /** An application id is a package name, so it can safely close the prefix. */
    private val PROCESS = CanonicalColumn.Bracketed("process", mayContainBracket = false)

    override val shape = CanonicalLineShape(listOf(PID, TID, LEVEL, TAG, PROCESS))

    override val emittedProfileName: String = "android-studio-logcat"


    /** The timestamp width, and so the continuation indent the profile strips back off. */
    private const val TIMESTAMP_WIDTH = 23

    /** Enough to see the opening brace and decide; never enough to matter if the file is huge. */
    private const val SNIFF_BYTES = 4096

    private val LINE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    override val name: String = "Android Studio logcat export"

    /**
     * A pretty-printed JSON document opens with a brace **alone on its first line**. That single
     * test is what separates this from a JSON-lines log, where the first line is a whole object and
     * which a line-oriented profile can read perfectly well without any help from here.
     */
    override fun claims(file: File): Boolean = try {
        firstLineOf(file).trim() == "{"
    } catch (failure: java.io.IOException) {
        // claims() is asked of every file that is opened; an unreadable one is not this adapter's
        // problem to report, and the loader will fail on it with something far more useful.
        false
    }

    override fun convert(source: File, destination: File): ConversionReport {
        var written = 0L
        var unknownLevels = 0L
        BufferedReader(InputStreamReader(source.inputStream(), StandardCharsets.UTF_8), 1 shl 16).use { reader ->
            BufferedWriter(OutputStreamWriter(destination.outputStream(), StandardCharsets.UTF_8), 1 shl 16).use { writer ->
                val scanner = JsonScanner(reader)
                val zone: ZoneId = ZoneId.systemDefault()
                var sawMessages = false
                scanner.expect('{')
                if (!scanner.skipIf('}')) {
                    do {
                        val key: String = scanner.readString()
                        scanner.expect(':')
                        if (key == "logcatMessages") {
                            sawMessages = true
                            forEachMessage(scanner) { message ->
                                if (message.unknownLevel) unknownLevels++
                                writeLine(writer, message, zone)
                                written++
                            }
                        } else {
                            scanner.skipValue()
                        }
                    } while (scanner.skipIf(','))
                    scanner.expect('}')
                }
                if (!sawMessages) {
                    throw JsonFormatException(
                        "'${source.name}' is a JSON document but has no \"logcatMessages\" array — " +
                            "it does not look like an Android Studio logcat export.",
                    )
                }
            }
        }
        return ConversionReport(
            entriesWritten = written,
            note = buildString {
                append("$written messages read from an Android Studio export")
                if (unknownLevels > 0) append(", $unknownLevels of an unknown level shown as Info")
            },
        )
    }

    private inline fun forEachMessage(scanner: JsonScanner, action: (LogcatMessage) -> Unit) {
        scanner.expect('[')
        if (scanner.skipIf(']')) return
        do {
            action(readMessage(scanner))
        } while (scanner.skipIf(','))
        scanner.expect(']')
    }

    private fun readMessage(scanner: JsonScanner): LogcatMessage {
        val message = LogcatMessage()
        scanner.expect('{')
        if (scanner.skipIf('}')) return message
        do {
            val key: String = scanner.readString()
            scanner.expect(':')
            when (key) {
                "header" -> readHeader(scanner, message)
                "message" -> message.text = scanner.readString()
                else -> scanner.skipValue()
            }
        } while (scanner.skipIf(','))
        scanner.expect('}')
        return message
    }

    private fun readHeader(scanner: JsonScanner, message: LogcatMessage) {
        scanner.expect('{')
        if (scanner.skipIf('}')) return
        do {
            val key: String = scanner.readString()
            scanner.expect(':')
            when (key) {
                "logLevel" -> message.setLevel(scanner.readString())
                "pid" -> message.pid = scanner.readLong()
                "tid" -> message.tid = scanner.readLong()
                "tag" -> message.tag = scanner.readString()
                "applicationId" -> message.process = scanner.readString()
                // Only used when there is no applicationId: the two were identical everywhere the
                // format was checked, and the application id is the more meaningful of the pair.
                "processName" -> if (message.process.isEmpty()) message.process = scanner.readString() else scanner.skipValue()
                "timestamp" -> readTimestamp(scanner, message)
                else -> scanner.skipValue()
            }
        } while (scanner.skipIf(','))
        scanner.expect('}')
    }

    private fun readTimestamp(scanner: JsonScanner, message: LogcatMessage) {
        scanner.expect('{')
        if (scanner.skipIf('}')) return
        do {
            val key: String = scanner.readString()
            scanner.expect(':')
            when (key) {
                "seconds" -> message.seconds = scanner.readLong()
                "nanos" -> message.nanos = scanner.readLong()
                else -> scanner.skipValue()
            }
        } while (scanner.skipIf(','))
        scanner.expect('}')
    }

    private fun writeLine(writer: Writer, message: LogcatMessage, zone: ZoneId) {
        val line = StringBuilder(message.text.length + 96)
        line.append(LINE_FORMAT.format(Instant.ofEpochSecond(message.seconds, message.nanos).atZone(zone)))
        // The space is written, not left to the padding: a 7-digit pid — Android allows them —
        // would fill the field and weld the timestamp to the number.
        line.append(' ').append(message.pid.toString().padStart(5))
        line.append(' ').append(message.tid.toString().padStart(5))
        line.append(' ').append(message.level).append(' ')
        // Tag first: it is both what a reader scans for and the field that may hold a bracket.
        line.appendBracketed(message.tag)
        line.append(' ')
        line.appendBracketed(message.process)
        line.append(' ')
        // A message carries its own newlines — a stack trace is one message, not one per frame.
        // Indenting the wrapped lines to the timestamp width is what lets the profile fold them
        // back into a single entry, which is what makes a trace filterable as one thing.
        message.text.lineSequence().forEachIndexed { position, textLine ->
            if (position > 0) line.append('\n').append(CONTINUATION_INDENT)
            line.append(textLine)
        }
        line.append('\n')
        writer.append(line)
    }

    private val CONTINUATION_INDENT: String = " ".repeat(TIMESTAMP_WIDTH)

    /**
     * Written through verbatim — deliberately. The field order, not an escape, is what keeps a
     * bracket inside a tag readable; anything escaped here would reach the facet still escaped.
     */
    private fun StringBuilder.appendBracketed(value: String) {
        append('[')
        value.forEach { character ->
            // A newline would forge a continuation line and swallow the entry after it. Never seen
            // in a tag or an application id, and cheap to refuse.
            if (character == '\n' || character == '\r') append(' ') else append(character)
        }
        append(']')
    }

    private fun firstLineOf(file: File): String =
        file.inputStream().use { stream ->
            val buffer = ByteArray(SNIFF_BYTES)
            val read: Int = stream.read(buffer)
            if (read <= 0) return ""
            val text = String(buffer, 0, read, StandardCharsets.UTF_8)
            text.lineSequence().first()
        }

    private class LogcatMessage {
        var seconds: Long = 0
        var nanos: Long = 0
        var pid: Long = 0
        var tid: Long = 0
        var tag: String = ""
        var process: String = ""
        var text: String = ""
        var level: Char = 'I'
        var unknownLevel: Boolean = false

        fun setLevel(logLevel: String) {
            level = when (logLevel) {
                "VERBOSE" -> 'V'
                "DEBUG" -> 'D'
                "INFO" -> 'I'
                "WARN" -> 'W'
                "ERROR" -> 'E'
                "ASSERT" -> 'A'
                else -> {
                    // Android Studio's enum has exactly the six above. Anything else is shown as
                    // Info and counted, rather than written through as a letter the profile would
                    // reject — an entry that vanishes is worse than one filed under the wrong level.
                    unknownLevel = true
                    'I'
                }
            }
        }
    }
}
