package dev.loupe.core.profile

import java.util.regex.Pattern

/**
 * A yes/no test on a raw line, used for the two cheap questions asked before the parse regex runs:
 * *does this line continue the one above it*, and *is it even worth trying to parse*.
 *
 * The two questions have different contracts, and conflating them is a correctness bug:
 *
 *  - `entry.continues` is **semantic**. Its answer decides whether a line becomes part of the
 *    entry above or is tried as a new one, so it must mean exactly what its regex means —
 *    [compileExact].
 *  - `entry.opens` is **an optimisation only**. The full parse regex runs immediately afterwards
 *    and has the final say, so this one merely has to be a *necessary* condition: it may accept
 *    lines that will not parse, it must never reject one that would — [compileNecessary].
 *
 * That difference is what makes a generic pre-filter possible at all. `^\d{4}-\d{2}-\d{2} …`
 * cannot be reduced to a literal prefix, but it can be reduced to "position 0 is a digit,
 * position 4 is a dash, …", which is the same handful of byte comparisons the hand-written M0
 * scanner used, derived rather than written.
 */
sealed interface LinePredicate {

    /** True when the predicate accepts `buffer[start until end]`. */
    fun matches(buffer: ByteArray, start: Int, end: Int): Boolean

    /** True when this predicate needs no allocation. Reported by the profile loader. */
    val isFastPath: Boolean

    /** How much of the regex the predicate actually covers, for the loader's diagnostics. */
    val description: String

    companion object {
        /** Regex syntax with meaning; anything else is a literal character. */
        private const val METACHARACTERS = ".*+?()[]{}|^$\\"

        /**
         * Compiles a predicate that means **exactly** what [regexSource] means.
         *
         * Falls back to running the regex — allocating a `String` per tested line — when the
         * source is richer than a literal prefix. The loader warns when that happens.
         */
        fun compileExact(regexSource: String): LinePredicate {
            val prefix: ByteArray? = deriveLiteralPrefix(regexSource)
            return if (prefix != null) LiteralPrefix(prefix) else RegexMatch(Pattern.compile(regexSource))
        }

        /**
         * Compiles a predicate that is a **necessary** condition of [regexSource] — it accepts
         * every line the regex would, and cheaply rejects as many others as it can work out.
         *
         * Derivation is greedy and stops at the first construct it does not understand, keeping
         * whatever it has: a partial prefix is still a valid necessary condition. Never produces a
         * [RegexMatch], because a regex pre-filter in front of a regex parse is pure overhead.
         *
         * @return `null` when nothing could be derived, meaning there is no useful pre-filter.
         */
        fun compileNecessary(regexSource: String): LinePredicate? {
            if (!regexSource.startsWith("^")) return null
            val constraints: PositionConstraints = derivePositionConstraints(regexSource)
            return constraints.toPredicateOrNull()
        }

        /**
         * Recognises `^` followed only by literal characters, each optionally repeated `{n}` times
         * — which covers `^ {23}`, `^\t`, `^--- `. Anything richer returns `null`.
         */
        private fun deriveLiteralPrefix(regexSource: String): ByteArray? {
            if (!regexSource.startsWith("^")) return null

            val prefix = StringBuilder()
            var index = 1
            while (index < regexSource.length) {
                val literal: Char = readLiteral(regexSource, index) ?: return null
                index += literalWidth(regexSource, index)
                val repeat: Int = readRepeat(regexSource, index) ?: return null
                index += repeatWidth(regexSource, index)
                repeat(repeat) { prefix.append(literal) }
            }
            return prefix.takeIf { derived -> derived.isNotEmpty() }?.toString()?.toByteArray(Charsets.UTF_8)
        }

        /** Walks as far as it can, recording what each leading position must contain. */
        private fun derivePositionConstraints(regexSource: String): PositionConstraints {
            val constraints = PositionConstraints()
            var index = 1
            while (index < regexSource.length) {
                val digitClass: Boolean = regexSource.startsWith("""\d""", index)
                val literal: Char? = if (digitClass) null else readLiteral(regexSource, index)
                if (!digitClass && literal == null) break

                index += if (digitClass) 2 else literalWidth(regexSource, index)
                val repeat: Int = readRepeat(regexSource, index) ?: break
                index += repeatWidth(regexSource, index)

                var derivable = true
                repeat(repeat) {
                    if (!derivable) return@repeat
                    derivable = if (digitClass) constraints.addDigit() else constraints.addLiteral(requireNotNull(literal))
                }
                if (!derivable) break
            }
            return constraints
        }

        /** @return the literal character at [index], or `null` if it is a metacharacter or class. */
        private fun readLiteral(regexSource: String, index: Int): Char? {
            val character: Char = regexSource.getOrNull(index) ?: return null
            if (character != '\\') return if (character in METACHARACTERS) null else character
            val escaped: Char = regexSource.getOrNull(index + 1) ?: return null
            return when (escaped) {
                't' -> '\t'
                // A backslash before punctuation is an escaped literal; before a letter it is a
                // character class, which no single character can stand for.
                in METACHARACTERS, '-', '/', '"', '\'' -> escaped
                else -> null
            }
        }

        private fun literalWidth(regexSource: String, index: Int): Int =
            if (regexSource[index] == '\\') 2 else 1

        /** @return the `{n}` repeat count at [index], `1` when there is none, `null` for `{n,m}`. */
        private fun readRepeat(regexSource: String, index: Int): Int? {
            if (regexSource.getOrNull(index) != '{') return 1
            val closing: Int = regexSource.indexOf('}', index)
            if (closing < 0) return null
            return regexSource.substring(index + 1, closing).toIntOrNull()
        }

        private fun repeatWidth(regexSource: String, index: Int): Int {
            if (regexSource.getOrNull(index) != '{') return 0
            val closing: Int = regexSource.indexOf('}', index)
            return if (closing < 0) 0 else closing - index + 1
        }
    }

    /** The line must start with these exact bytes. Exact, so usable for `entry.continues`. */
    class LiteralPrefix(private val prefix: ByteArray) : LinePredicate {

        override val isFastPath: Boolean = true

        override val description: String = "literal prefix of ${prefix.size} bytes"

        override fun matches(buffer: ByteArray, start: Int, end: Int): Boolean {
            if (end - start < prefix.size) return false
            for (offset in prefix.indices) {
                if (buffer[start + offset] != prefix[offset]) return false
            }
            return true
        }
    }

    /**
     * Per-position byte constraints — literal bytes and digit positions.
     *
     * Only a necessary condition, so only ever used for `entry.opens`. Checks literals before
     * digits: a literal is the more selective test, and the loop should fail on the first
     * comparison for a line that is not an entry.
     */
    class PositionalPrefix(
        private val minimumLength: Int,
        private val literalOffsets: IntArray,
        private val literalBytes: ByteArray,
        private val digitOffsets: IntArray,
    ) : LinePredicate {

        override val isFastPath: Boolean = true

        override val description: String =
            "${literalOffsets.size} literal + ${digitOffsets.size} digit positions over $minimumLength bytes"

        override fun matches(buffer: ByteArray, start: Int, end: Int): Boolean {
            if (end - start < minimumLength) return false
            for (index in literalOffsets.indices) {
                if (buffer[start + literalOffsets[index]] != literalBytes[index]) return false
            }
            for (index in digitOffsets.indices) {
                val byte: Int = buffer[start + digitOffsets[index]].toInt()
                if (byte < ASCII_ZERO || byte > ASCII_NINE) return false
            }
            return true
        }

        private companion object {
            const val ASCII_ZERO = '0'.code
            const val ASCII_NINE = '9'.code
        }
    }

    /** The general case. Allocates a `String` per tested line, so it is the last resort. */
    class RegexMatch(private val pattern: Pattern) : LinePredicate {

        override val isFastPath: Boolean = false

        override val description: String = "regex, one String allocation per line"

        override fun matches(buffer: ByteArray, start: Int, end: Int): Boolean =
            pattern.matcher(String(buffer, start, end - start, Charsets.UTF_8)).find()
    }
}

/** Accumulator for [LinePredicate.compileNecessary]. */
private class PositionConstraints {

    private val literalOffsets: MutableList<Int> = mutableListOf()
    private val literalBytes: MutableList<Byte> = mutableListOf()
    private val digitOffsets: MutableList<Int> = mutableListOf()
    private var length = 0

    /**
     * @return false when the character cannot be positioned, which must stop the derivation.
     *   A multi-byte character occupies an unknown number of buffer bytes, so every offset after
     *   it would be wrong — and a *wrong* necessary condition rejects lines that should parse.
     */
    fun addLiteral(character: Char): Boolean {
        if (character.code >= 0x80) return false
        literalOffsets.add(length)
        literalBytes.add(character.code.toByte())
        length++
        return true
    }

    fun addDigit(): Boolean {
        digitOffsets.add(length)
        length++
        return true
    }

    fun toPredicateOrNull(): LinePredicate? {
        if (length == 0) return null
        return LinePredicate.PositionalPrefix(
            minimumLength = length,
            literalOffsets = literalOffsets.toIntArray(),
            literalBytes = literalBytes.toByteArray(),
            digitOffsets = digitOffsets.toIntArray(),
        )
    }
}
