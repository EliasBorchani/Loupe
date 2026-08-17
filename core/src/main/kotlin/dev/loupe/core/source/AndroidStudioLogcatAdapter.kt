package dev.loupe.core.source

import java.io.File
import java.time.Instant

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

    override val name: String = "Android Studio logcat export"

    /**
     * A pretty-printed JSON document opens with a brace **alone on its first line**. That single
     * test is what separates this from a JSON-lines log, where the first line is a whole object and
     * which a line-oriented profile can read perfectly well without any help from here.
     */
    override fun claims(file: File): Boolean = sniffFirstLine(file).trim() == "{"

    override fun convert(source: File, destination: File): ConversionReport {
        var written = 0L
        var unknownLevels = 0L
        CanonicalLineWriter.render(source, destination, shape) { reader, writer ->
            val scanner = JsonScanner(reader)
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
                            writeLine(writer, message)
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

    private fun writeLine(writer: CanonicalLineWriter, message: LogcatMessage) {
        writer.set(PID, message.pid)
        writer.set(TID, message.tid)
        writer.set(LEVEL, message.level.toString())
        writer.set(TAG, message.tag)
        writer.set(PROCESS, message.process)
        writer.write(Instant.ofEpochSecond(message.seconds, message.nanos), message.text)
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
