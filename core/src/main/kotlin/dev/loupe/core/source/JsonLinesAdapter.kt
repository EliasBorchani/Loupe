package dev.loupe.core.source

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Reads JSON lines — NDJSON, one object per line, as Docker, pino, Bunyan, Serilog, Vector and the
 * Withings iOS app all write.
 *
 * ## Why this is not a profile
 *
 * A regex over `{"date":"…","category":"…","message":"…","level":"…"}` works right up until it
 * doesn't, in two ways that are both silent:
 *
 *  - **The escapes stay escaped.** 12 of the 43 lines in the iOS capture this was written against
 *    carry `\"`, `\/` or `\n`; a captured group hands them to the facet exactly as written, so the
 *    message reads `URL Request: POST https:\/\/…` and a stack trace shows a literal `\n` instead
 *    of breaking. Nothing downstream unescapes, so a regex profile cannot fix this.
 *  - **The key order becomes law.** A regex encodes one order. Reorder two keys, or add one, and
 *    the profile stops matching — or worse, matches and captures the wrong field.
 *
 * Decoding properly is the only honest option, which makes it an adapter.
 *
 * ## How the fields are chosen
 *
 * There is no standard for what the keys are called, so they are matched against the conventional
 * names below, case-insensitively, from the **first object in the file**. What was picked is
 * reported in the conversion note — a mapping guessed in silence would be the worst of both worlds.
 *
 * A timestamp and a message are required; without them there is nothing for the rest of the tool
 * to work on, and failing loudly beats indexing something meaningless.
 *
 * Any further scalar key is written to a continuation line, so it stays searchable rather than
 * being dropped. Nested objects and arrays have no column to live in and are skipped.
 */
object JsonLinesAdapter : CanonicalSourceAdapter {

    /** The one scale every producer's vocabulary is normalised onto. [LEVELS] maps into it. */
    private val LEVEL_WORDS: List<String> = listOf("TRACE", "DEBUG", "INFO", "NOTICE", "WARN", "ERROR", "FATAL")

    private val LEVEL = CanonicalColumn.Vocabulary("level", LEVEL_WORDS)

    private val CONTEXT = CanonicalColumn.Bracketed("context", mayContainBracket = false)

    override val shape = CanonicalLineShape(listOf(LEVEL, CONTEXT))

    override val emittedProfileName: String = "json-lines"

    private val TIMESTAMP_KEYS: List<String> =
        listOf("timestamp", "@timestamp", "time", "date", "datetime", "eventtime", "ts")

    private val LEVEL_KEYS: List<String> = listOf("level", "severity", "levelname", "loglevel", "lvl")

    private val MESSAGE_KEYS: List<String> = listOf("message", "msg", "text", "event", "short_message", "body")

    /** The "which part of the system" slot. First match wins, so the order is the preference order. */
    private val CONTEXT_KEYS: List<String> =
        listOf("category", "logger", "subsystem", "component", "module", "tag", "channel", "scope", "source", "name")

    /** Normalised so one static profile can declare a severity order across every producer. */
    private val LEVELS: Map<String, String> = mapOf(
        "trace" to "TRACE", "verbose" to "TRACE", "v" to "TRACE",
        "debug" to "DEBUG", "d" to "DEBUG",
        "info" to "INFO", "information" to "INFO", "informational" to "INFO", "i" to "INFO",
        "notice" to "NOTICE",
        "warn" to "WARN", "warning" to "WARN", "w" to "WARN",
        "error" to "ERROR", "err" to "ERROR", "e" to "ERROR", "severe" to "ERROR",
        "fatal" to "FATAL", "critical" to "FATAL", "crit" to "FATAL", "alert" to "FATAL",
        "emergency" to "FATAL", "panic" to "FATAL",
    )

    override val name: String = "JSON lines"

    /**
     * A complete object on the first line. The `{` **alone** on its line is a pretty-printed
     * document — Android Studio's export — and belongs to the other adapter; that one character of
     * difference is the whole test, and it needs no more of the file than its opening bytes.
     */
    override fun claims(file: File): Boolean {
        val firstLine: String = sniffFirstLine(file).trim()
        return firstLine.length > 1 && firstLine.startsWith("{")
    }

    override fun convert(source: File, destination: File): ConversionReport {
        var written = 0L
        var unknownLevels = 0L
        var mapping: KeyMapping? = null
        CanonicalLineWriter.render(source, destination, shape) { reader, writer ->
            val scanner = JsonScanner(reader)
            // For *reading* a zone-less local time out of the source, not for writing: the writer
            // owns the output zone.
            val zone: ZoneId = ZoneId.systemDefault()
            while (true) {
                scanner.skipBlanks()
                if (scanner.atEndOfInput) break
                val fields: Map<String, String> = readFlatObject(scanner)
                if (fields.isEmpty()) continue
                val keys: KeyMapping = mapping ?: mapKeys(source, fields).also { chosen -> mapping = chosen }
                if (writeLine(writer, fields, keys, zone, source)) unknownLevels++
                written++
            }
        }
        val keys: KeyMapping = mapping
            ?: throw JsonFormatException("'${source.name}' holds no JSON object to read.")
        return ConversionReport(
            entriesWritten = written,
            note = buildString {
                append("$written JSON lines read — ")
                append("time from \"${keys.timestamp}\", message from \"${keys.message}\"")
                keys.level?.let { key -> append(", level from \"$key\"") }
                keys.context?.let { key -> append(", context from \"$key\"") }
                if (keys.level == null) append(", no level field found")
                if (unknownLevels > 0) append("; $unknownLevels of an unrecognised level shown as Info")
            },
        )
    }

    /** Returns whether the level had to be guessed, so the caller can count it. */
    private fun writeLine(
        writer: CanonicalLineWriter,
        fields: Map<String, String>,
        keys: KeyMapping,
        zone: ZoneId,
        source: File,
    ): Boolean {
        val rawTimestamp: String = fields[keys.timestamp]
            ?: throw JsonFormatException("A line of '${source.name}' has no \"${keys.timestamp}\".")
        val instant: Instant = parseInstant(rawTimestamp, zone)
            ?: throw JsonFormatException(
                "'${source.name}' has \"${keys.timestamp}\": \"$rawTimestamp\", which is not a time this can read.",
            )
        val rawLevel: String? = keys.level?.let { key -> fields[key] }
        val level: String = rawLevel?.let { value -> LEVELS[value.lowercase()] } ?: "INFO"
        val guessed: Boolean = rawLevel != null && LEVELS[rawLevel.lowercase()] == null

        writer.set(LEVEL, level)
        writer.set(CONTEXT, keys.context?.let { key -> fields[key] }.orEmpty())

        // Anything the columns did not take. On its own indented line so it belongs to the entry and
        // stays searchable, rather than being quietly dropped for want of a column.
        val extras: String = fields.entries
            .filter { field -> field.key !in keys.taken && field.value.isNotEmpty() }
            .joinToString(" ") { field -> "${field.key}=${field.value}" }
        writer.write(instant, fields[keys.message].orEmpty(), extras)
        return guessed
    }

    private fun mapKeys(source: File, fields: Map<String, String>): KeyMapping {
        val timestamp: String = pick(fields, TIMESTAMP_KEYS)
            ?: throw JsonFormatException(
                "'${source.name}' is JSON lines but no key looks like a time. Saw ${fields.keys.joinToString()}; " +
                    "expected one of ${TIMESTAMP_KEYS.joinToString()}.",
            )
        val message: String = pick(fields, MESSAGE_KEYS)
            ?: throw JsonFormatException(
                "'${source.name}' is JSON lines but no key looks like a message. Saw ${fields.keys.joinToString()}; " +
                    "expected one of ${MESSAGE_KEYS.joinToString()}.",
            )
        return KeyMapping(
            timestamp = timestamp,
            message = message,
            level = pick(fields, LEVEL_KEYS),
            context = pick(fields, CONTEXT_KEYS),
        )
    }

    private fun pick(fields: Map<String, String>, conventional: List<String>): String? = conventional.firstNotNullOfOrNull { wanted ->
        fields.keys.firstOrNull { key -> key.equals(wanted, ignoreCase = true) }
    }

    private fun readFlatObject(scanner: JsonScanner): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        scanner.expect('{')
        if (scanner.skipIf('}')) return fields
        do {
            val key: String = scanner.readString()
            scanner.expect(':')
            scanner.skipBlanks()
            when (scanner.peek()) {
                // Nested structure: no column can hold it, and flattening it into one would invent
                // a shape the producer never wrote.
                '{', '[' -> scanner.skipValue()

                '"' -> fields[key] = scanner.readString()

                else -> fields[key] = scanner.readLiteral()
            }
        } while (scanner.skipIf(','))
        scanner.expect('}')
        return fields
    }

    /**
     * Every shape a log actually writes a time in: an instant with a zone, one without, and epoch
     * numbers. A local time is read in the machine's zone, which is the only guess available and
     * the same one every other viewer makes.
     */
    private fun parseInstant(value: String, zone: ZoneId): Instant? {
        val epoch: Long? = value.toLongOrNull()
        if (epoch != null) {
            // Seconds until roughly the year 5138, milliseconds after. No log is from the year
            // 5138, and every log is from after 1973 in milliseconds.
            return if (epoch > 100_000_000_000L) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch)
        }
        runCatching { return Instant.parse(value) }
        runCatching { return OffsetDateTime.parse(value).toInstant() }
        runCatching { return LocalDateTime.parse(value).atZone(zone).toInstant() }
        return null
    }

    private class KeyMapping(val timestamp: String, val message: String, val level: String?, val context: String?) {
        val taken: Set<String> = setOfNotNull(timestamp, message, level, context)
    }
}
