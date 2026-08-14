package dev.loupe.core.query

/**
 * Splits a query into terms, respecting the three things whitespace must not break: a quoted
 * phrase, a `/regex/`, and the value list of a `field:a,b` term.
 */
object QueryLexer {

    fun tokenize(query: String): List<QueryToken> {
        val tokens: MutableList<QueryToken> = mutableListOf()
        var index = 0
        while (index < query.length) {
            val character: Char = query[index]
            when {
                character.isWhitespace() -> index++

                character == '"' || character == '\'' -> {
                    val closing: Int = query.indexOf(character, index + 1)
                    val end: Int = if (closing < 0) query.length else closing
                    tokens.add(QueryToken.Phrase(query.substring(index + 1, end)))
                    index = if (closing < 0) query.length else closing + 1
                }

                character == '/' -> {
                    val closing: Int = findRegexEnd(query, index)
                    val end: Int = if (closing < 0) query.length else closing
                    tokens.add(QueryToken.RegexLiteral(query.substring(index + 1, end)))
                    index = if (closing < 0) query.length else closing + 1
                }

                else -> {
                    val end: Int = findBareEnd(query, index)
                    tokens.add(parseBare(query.substring(index, end)))
                    index = end
                }
            }
        }
        return tokens
    }

    /** An unescaped `/` closes the literal, so `/a\/b/` is one regex. */
    private fun findRegexEnd(query: String, start: Int): Int {
        var index = start + 1
        while (index < query.length) {
            if (query[index] == '\\') {
                index += 2
                continue
            }
            if (query[index] == '/') return index
            index++
        }
        return -1
    }

    /** A bare term runs to the next whitespace, but a quoted value inside it may contain spaces. */
    private fun findBareEnd(query: String, start: Int): Int {
        var index = start
        var quote: Char? = null
        while (index < query.length) {
            val character: Char = query[index]
            when {
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character.isWhitespace() -> return index
            }
            index++
        }
        return query.length
    }

    private fun parseBare(raw: String): QueryToken {
        val negated: Boolean = raw.startsWith("-") && raw.length > 1
        val body: String = if (negated) raw.substring(1) else raw

        val separator: Int = findFieldSeparator(body)
        if (separator < 0) return QueryToken.Phrase(body)

        val field: String = body.substring(0, separator)
        val (comparison: Comparison, valueStart: Int) = readComparison(body, separator)
        return QueryToken.Field(
            field = field,
            comparison = comparison,
            rawValue = body.substring(valueStart),
            negated = negated,
        )
    }

    /**
     * The first `:`, `>=`, `<=`, `>` or `<` that follows a plain field name.
     *
     * A `:` inside a value (`since:14:30`) must not be mistaken for the separator, which is why
     * only the first one counts and why the field name is required to look like an identifier.
     */
    private fun findFieldSeparator(body: String): Int {
        for (index in body.indices) {
            val character: Char = body[index]
            if (character == ':' || character == '>' || character == '<' || character == '=') return index
            if (!character.isLetterOrDigit() && character != '_' && character != '-') return -1
        }
        return -1
    }

    private fun readComparison(body: String, separator: Int): Pair<Comparison, Int> = when {
        body.startsWith(">=", separator) -> Comparison.AtLeast to separator + 2
        body.startsWith("<=", separator) -> Comparison.AtMost to separator + 2
        body.startsWith(">", separator) -> Comparison.GreaterThan to separator + 1
        body.startsWith("<", separator) -> Comparison.LessThan to separator + 1
        else -> Comparison.Equals to separator + 1
    }
}

sealed interface QueryToken {

    /** `level>=W`, `cat:Sync,Wpp`, `-tag:~Aggregate`, `since:-2h`. */
    data class Field(
        val field: String,
        val comparison: Comparison,
        val rawValue: String,
        val negated: Boolean,
    ) : QueryToken

    /** Free text — quoted or not. Matched case-insensitively against the whole entry. */
    data class Phrase(val text: String) : QueryToken

    /** `/…/`. */
    data class RegexLiteral(val source: String) : QueryToken
}

enum class Comparison { Equals, AtLeast, AtMost, GreaterThan, LessThan }
