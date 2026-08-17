package dev.loupe.core.source

import java.time.format.DateTimeFormatter

/**
 * The one line format Loupe writes for itself, and the regexes that read it back.
 *
 * `<ts:23> <columns> <message>`, with a message's own newlines indented to the timestamp width so a
 * stack trace stays one entry. Two [SourceAdapter]s emit it, and each used to re-derive the layout,
 * the indent and the escaping independently — four byte-identical constants and a near-duplicate
 * profile apiece, with nothing checking that the writing agreed with the reading.
 *
 * Declaring the shape here means the paired profile's regexes are *derived* rather than written
 * twice. The profiles stay hand-written files a user can read, and `AdapterProfilePairingTest`
 * fails if one drifts from its adapter.
 */
object CanonicalLine {

    /** The group the paired profile must give `role = "timestamp"`. */
    const val TIMESTAMP_GROUP: String = "ts"

    /** The group the paired profile must give `role = "message"`. */
    const val MESSAGE_GROUP: String = "message"

    const val TIMESTAMP_PATTERN: String = "yyyy-MM-dd HH:mm:ss.SSS"

    /**
     * The width of [TIMESTAMP_PATTERN]'s output, and therefore the continuation indent.
     *
     * Not arithmetic on the pattern — a test formats a real instant and counts the characters, which
     * is the only way this number stays true if the pattern ever changes.
     */
    const val TIMESTAMP_WIDTH: Int = 23

    const val TIMESTAMP_REGEX: String = """\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}"""

    val CONTINUATION_INDENT: String = " ".repeat(TIMESTAMP_WIDTH)

    internal val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN)

    fun parseRegex(shape: CanonicalLineShape): String = buildString {
        append("^(?<").append(TIMESTAMP_GROUP).append('>').append(TIMESTAMP_REGEX).append(')')
        shape.columns.forEach { column -> append(column.regexFragment()) }
        append(" (?<").append(MESSAGE_GROUP).append(">.*)\$")
    }

    /**
     * The cheap structural pre-filter: the timestamp, plus just enough of the first column to reject
     * a line in a handful of byte comparisons.
     */
    fun opensRegex(shape: CanonicalLineShape): String = "^" + TIMESTAMP_REGEX + shape.columns.first().opensLiteral()

    fun continuesRegex(): String = "^ {$TIMESTAMP_WIDTH}"
}

/** The columns between the timestamp and the message, in the order they are written. */
class CanonicalLineShape(val columns: List<CanonicalColumn>) {

    init {
        require(columns.isNotEmpty()) { "A canonical line needs at least one column before its message" }

        val names: List<String> = columns.map { column -> column.field }
        require(names.distinct().size == names.size) { "Two columns share a field name: $names" }
        require(CanonicalLine.TIMESTAMP_GROUP !in names && CanonicalLine.MESSAGE_GROUP !in names) {
            "'${CanonicalLine.TIMESTAMP_GROUP}' and '${CanonicalLine.MESSAGE_GROUP}' are the line's own groups"
        }

        // A column whose value may hold a `]` is matched lazily, so it needs a following `] [` to
        // stop at; the message that ends the line would not do, because messages contain `] ` too.
        // This is why the logcat adapter writes the tag first and the process last: an application
        // id is a package name and cannot hold a bracket, so it can safely close the prefix.
        columns.forEachIndexed { position, column ->
            if (column is CanonicalColumn.Bracketed && column.mayContainBracket) {
                val next: CanonicalColumn? = columns.getOrNull(position + 1)
                require(next is CanonicalColumn.Bracketed && !next.mayContainBracket) {
                    "'${column.field}' may hold a ']', so the column after it must be one that cannot"
                }
            }
        }
    }
}

sealed interface CanonicalColumn {

    /** Named group in the paired profile, and so the `[fields.<name>]` block that gives it meaning. */
    val field: String

    /** Includes this column's own leading separator, so a shape is a plain concatenation. */
    fun regexFragment(): String

    /** As much of this column as `opens` can cheaply demand, when it comes first. */
    fun opensLiteral(): String

    /** Right-aligned decimal — logcat's pid and tid. [width] pads; it never truncates. */
    class Padded(override val field: String, val width: Int) : CanonicalColumn {
        override fun regexFragment(): String = """\s+(?<$field>\d+)"""
        override fun opensLiteral(): String = " "
    }

    /** One character out of [alphabet] — logcat's `D`. */
    class Code(override val field: String, val alphabet: String) : CanonicalColumn {
        override fun regexFragment(): String = " (?<$field>[$alphabet])"
        override fun opensLiteral(): String = " "
    }

    /**
     * Bracketed, out of a closed set.
     *
     * Spelled out rather than left as `[A-Z]+` on purpose: enumerating the words stops a profile
     * from also matching HealthMate's `[D] [Sync] [tag] -> …`, which it otherwise would, leaving
     * only `priority` between them.
     */
    class Vocabulary(override val field: String, val words: List<String>) : CanonicalColumn {
        override fun regexFragment(): String = " \\[(?<$field>${words.joinToString("|")})\\]"
        override fun opensLiteral(): String = " \\["
    }

    /** Bracketed free text. See [CanonicalLineShape]'s init for what [mayContainBracket] costs. */
    class Bracketed(override val field: String, val mayContainBracket: Boolean) : CanonicalColumn {
        override fun regexFragment(): String = if (mayContainBracket) " \\[(?<$field>.*?)\\]" else " \\[(?<$field>[^\\]]*)\\]"

        override fun opensLiteral(): String = " \\["
    }
}
