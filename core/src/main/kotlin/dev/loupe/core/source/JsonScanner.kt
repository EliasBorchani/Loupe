package dev.loupe.core.source

import java.io.Reader

/**
 * A pull scanner over a JSON document — exactly as much of one as this package reads.
 *
 * **Streaming, not a tree.** An Android Studio logcat export is a single document holding every
 * message, so decoding it whole would put the entire log on the heap: the one thing the rest of
 * this codebase exists to avoid. Reading it as a stream keeps a conversion flat in memory whatever
 * the file's size, which matters because nothing bounds how much someone captured.
 *
 * **Hand-written, not a library** — see `.claude/rules/dependencies.md`. What a *reader* needs is
 * string escapes and the ability to walk past a value it does not care about. That is this file,
 * and it came out shorter than the argument for adding a JSON parser would have been.
 *
 * Every method assumes the caller knows the shape it is walking: this validates enough to fail
 * loudly on something that is not JSON, and no more.
 */
internal class JsonScanner(private val reader: Reader) {

    private var currentCode: Int = reader.read()
    private var charactersRead: Long = 0

    val atEndOfInput: Boolean get() = currentCode < 0

    /** The next character without consuming it. Call [skipBlanks] first when whitespace is possible. */
    fun peek(): Char {
        if (currentCode < 0) fail("unexpected end of document")
        return currentCode.toChar()
    }

    fun skipBlanks() {
        while (currentCode == ' '.code || currentCode == '\n'.code || currentCode == '\r'.code || currentCode == '\t'.code) {
            advance()
        }
    }

    fun expect(expected: Char) {
        skipBlanks()
        if (currentCode < 0) fail("expected '$expected' but the document ended")
        val found: Char = currentCode.toChar()
        if (found != expected) fail("expected '$expected' but found '$found'")
        advance()
    }

    /** Consumes [expected] if it is next, and reports whether it was. */
    fun skipIf(expected: Char): Boolean {
        skipBlanks()
        if (currentCode < 0 || currentCode.toChar() != expected) return false
        advance()
        return true
    }

    fun readString(): String {
        skipBlanks()
        if (currentCode < 0 || currentCode.toChar() != '"') fail("expected a string")
        advance()
        val builder = StringBuilder()
        while (true) {
            if (currentCode < 0) fail("unterminated string")
            when (val character: Char = currentCode.toChar()) {
                '"' -> {
                    advance()
                    return builder.toString()
                }
                '\\' -> {
                    advance()
                    builder.append(readEscape())
                }
                else -> {
                    builder.append(character)
                    advance()
                }
            }
        }
    }

    fun readLong(): Long {
        skipBlanks()
        val builder = StringBuilder()
        while (currentCode >= 0) {
            val character: Char = currentCode.toChar()
            if (character.isDigit() || character == '-' || character == '+') {
                builder.append(character)
                advance()
            } else {
                break
            }
        }
        return builder.toString().toLongOrNull() ?: fail("expected a number")
    }

    /** Walks past the next value, whatever it is, including a nested object or array. */
    fun skipValue() {
        skipBlanks()
        if (currentCode < 0) fail("expected a value")
        when (currentCode.toChar()) {
            '{' -> skipNested('{', '}')
            '[' -> skipNested('[', ']')
            '"' -> readString()
            else -> skipLiteral()
        }
    }

    private fun readEscape(): Char {
        if (currentCode < 0) fail("unterminated escape")
        val marker: Char = currentCode.toChar()
        advance()
        return when (marker) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            // A surrogate pair arrives as two of these in a row; appending each half in turn
            // reassembles it, because a Kotlin String is UTF-16 to begin with.
            'u' -> readUnicodeEscape()
            else -> fail("unknown escape '\\$marker'")
        }
    }

    private fun readUnicodeEscape(): Char {
        var value = 0
        repeat(4) {
            if (currentCode < 0) fail("truncated \\u escape")
            val digit: Int = Character.digit(currentCode.toChar(), 16)
            if (digit < 0) fail("'${currentCode.toChar()}' is not a hex digit")
            value = value * 16 + digit
            advance()
        }
        return value.toChar()
    }

    /**
     * Counts [open] and [close] only. Braces inside a string would derail that, so strings are read
     * properly rather than scanned over; brackets of the *other* kind need no counting, since they
     * are balanced within whatever they nest in.
     */
    private fun skipNested(open: Char, close: Char) {
        expect(open)
        var depth = 1
        while (depth > 0) {
            skipBlanks()
            if (currentCode < 0) fail("unterminated '$open'")
            when (currentCode.toChar()) {
                '"' -> readString()
                open -> {
                    depth++
                    advance()
                }
                close -> {
                    depth--
                    advance()
                }
                else -> advance()
            }
        }
    }

    private fun skipLiteral() {
        while (currentCode >= 0) {
            val character: Char = currentCode.toChar()
            if (character == ',' || character == '}' || character == ']' ||
                character == ' ' || character == '\n' || character == '\r' || character == '\t'
            ) {
                return
            }
            advance()
        }
    }

    private fun advance() {
        currentCode = reader.read()
        charactersRead++
    }

    private fun fail(reason: String): Nothing =
        throw JsonFormatException("$reason (at character $charactersRead)")
}

class JsonFormatException(message: String) : IllegalArgumentException(message)
