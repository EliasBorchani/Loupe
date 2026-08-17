package dev.loupe.core.query

import dev.loupe.core.index.EntryFilter
import dev.loupe.core.index.FacetConstraint
import dev.loupe.core.index.LogIndex
import dev.loupe.core.profile.LevelDecoder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Turns a query string into an [EntryFilter], against a specific index.
 *
 * It needs the index because a facet term has to be resolved to dictionary ids — which is also
 * what makes `category:Snyc` reportable as a typo rather than as zero results.
 *
 * Terms are ANDed. Values inside one term are ORed (`category:Sync,Wpp`). Two terms on the same facet
 * intersect, so `category:Sync category:Wpp` matches nothing and says so.
 *
 * Nothing here fails hard: an unknown field or an unparsable date becomes a [problems] entry and
 * the rest of the query still compiles, because the user is typing and half a query should still
 * narrow the list.
 */
class CompiledQuery(val filter: EntryFilter, val problems: List<String>) {
    val isValid: Boolean get() = problems.isEmpty()
}

class QueryCompiler(private val index: LogIndex, private val zone: ZoneId = ZoneId.systemDefault()) {

    companion object {
        private const val LEVEL_FIELD = "level"
        private const val SINCE_FIELD = "since"
        private const val UNTIL_FIELD = "until"
        private const val CONTAINS_PREFIX = '~'

        private val RELATIVE_DURATION = Regex("""^-(\d+)([smhd])$""")
    }

    fun compile(query: String): CompiledQuery {
        val problems: MutableList<String> = mutableListOf()
        val tokens: List<QueryToken> = QueryLexer.tokenize(query)

        var acceptedLevels: BooleanArray? = null
        var acceptUnknownLevel = true
        var facetConstraints: Array<FacetConstraint?>? = null
        var sinceMillis: Long = Long.MIN_VALUE
        var untilMillis: Long = Long.MAX_VALUE
        var phrase: String? = null
        var regex: Pattern? = null

        tokens.forEach { token ->
            when (token) {
                is QueryToken.Phrase -> {
                    if (token.text.isNotEmpty()) {
                        // Several bare words narrow together, which is what a space reads as.
                        phrase = if (phrase == null) token.text else "$phrase ${token.text}"
                    }
                }

                is QueryToken.RegexLiteral -> {
                    try {
                        regex = Pattern.compile(token.source, Pattern.CASE_INSENSITIVE)
                    } catch (failure: PatternSyntaxException) {
                        problems.add("/${token.source}/ is not a valid regex: ${failure.description}")
                    }
                }

                is QueryToken.Field -> when (token.field.lowercase(Locale.ROOT)) {
                    LEVEL_FIELD -> {
                        val resolved: BooleanArray? = resolveLevels(token, problems)
                        if (resolved != null) {
                            acceptedLevels = intersect(acceptedLevels, resolved)
                            // An explicit level term is a statement about severity; an entry whose
                            // level could not be read cannot satisfy it.
                            acceptUnknownLevel = false
                        }
                    }

                    SINCE_FIELD -> resolveInstant(token.rawValue, problems)?.let { instant -> sinceMillis = maxOf(sinceMillis, instant) }

                    UNTIL_FIELD -> resolveInstant(token.rawValue, problems)?.let { instant -> untilMillis = minOf(untilMillis, instant) }

                    else -> {
                        val facetIndex: Int = resolveFacetIndex(token.field)
                        if (facetIndex < 0) {
                            problems.add("Unknown field '${token.field}'. Known: ${knownFieldNames().joinToString(", ")}")
                        } else {
                            val constraint: FacetConstraint? = resolveFacet(facetIndex, token, problems)
                            if (constraint != null) {
                                val constraints: Array<FacetConstraint?> =
                                    facetConstraints ?: arrayOfNulls(index.facetValues.size)
                                constraints[facetIndex] = intersect(constraints[facetIndex], constraint)
                                facetConstraints = constraints
                            }
                        }
                    }
                }
            }
        }

        return CompiledQuery(
            filter = EntryFilter(
                acceptedLevels = acceptedLevels,
                acceptUnknownLevel = acceptUnknownLevel,
                facetConstraints = facetConstraints,
                sinceMillis = sinceMillis,
                untilMillis = untilMillis,
                substringLowercase = phrase?.lowercase(Locale.ROOT)?.toByteArray(Charsets.UTF_8),
                regex = regex,
            ),
            problems = problems,
        )
    }

    private fun knownFieldNames(): List<String> = listOf(LEVEL_FIELD, SINCE_FIELD, UNTIL_FIELD) + index.facets.map { facet -> facet.name }

    /** Matches on the declared name first, then on the label, both case-insensitively. */
    private fun resolveFacetIndex(field: String): Int {
        val wanted: String = field.lowercase(Locale.ROOT)
        index.facets.forEachIndexed { facetIndex, facet ->
            if (facet.name.lowercase(Locale.ROOT) == wanted) return facetIndex
        }
        index.facets.forEachIndexed { facetIndex, facet ->
            if (facet.label.lowercase(Locale.ROOT) == wanted) return facetIndex
        }
        return -1
    }

    private fun resolveLevels(token: QueryToken.Field, problems: MutableList<String>): BooleanArray? {
        val decoder: LevelDecoder = index.profile.levelDecoder ?: run {
            problems.add("This format declares no level scale, so 'level' cannot be filtered")
            return null
        }

        val requested: List<Int> = token.rawValue.split(',').filter { value -> value.isNotBlank() }.map { value ->
            val ordinal: Int = decoder.order.indexOfFirst { candidate -> candidate.equals(value.trim(), ignoreCase = true) }
            if (ordinal < 0) {
                problems.add("Unknown level '${value.trim()}'. Known: ${decoder.order.joinToString(", ")}")
                return null
            }
            ordinal
        }
        if (requested.isEmpty()) {
            problems.add("'level' needs a value")
            return null
        }

        val accepted = BooleanArray(decoder.size)
        val pivot: Int = requested.first()
        when (token.comparison) {
            Comparison.Equals -> requested.forEach { ordinal -> accepted[ordinal] = true }
            Comparison.AtLeast -> for (ordinal in pivot until decoder.size) accepted[ordinal] = true
            Comparison.GreaterThan -> for (ordinal in pivot + 1 until decoder.size) accepted[ordinal] = true
            Comparison.AtMost -> for (ordinal in 0..pivot) accepted[ordinal] = true
            Comparison.LessThan -> for (ordinal in 0 until pivot) accepted[ordinal] = true
        }
        if (token.negated) {
            for (ordinal in accepted.indices) accepted[ordinal] = !accepted[ordinal]
        }
        return accepted
    }

    private fun resolveFacet(facetIndex: Int, token: QueryToken.Field, problems: MutableList<String>): FacetConstraint? {
        val dictionary = index.facetDictionaries[facetIndex]
        val facetName: String = index.facets[facetIndex].name
        val requested: List<String> = token.rawValue.split(',').filter { value -> value.isNotBlank() }
        if (requested.isEmpty()) {
            problems.add("'$facetName' needs a value")
            return null
        }

        val accepted = BooleanArray(dictionary.size)
        var matchedAny = false

        requested.forEach { rawValue ->
            val value: String = rawValue.trim().trim('"', '\'')
            // Per value, not cumulative: `~a,~b` where only `a` matches has to report `b`.
            var found = false
            if (value.startsWith(CONTAINS_PREFIX)) {
                val needle: String = value.substring(1)
                for (id in 0 until dictionary.size) {
                    if (dictionary.valueOf(id).contains(needle, ignoreCase = true)) {
                        accepted[id] = true
                        found = true
                    }
                }
                if (!found) problems.add("No $facetName value contains '$needle'")
            } else {
                for (id in 0 until dictionary.size) {
                    if (dictionary.valueOf(id).equals(value, ignoreCase = true)) {
                        accepted[id] = true
                        found = true
                    }
                }
                if (!found) problems.add("No $facetName value '$value' in this file")
            }
            if (found) matchedAny = true
        }

        // Local, deliberately. This used to read `!matchedAny && problems.isNotEmpty()`, and
        // `problems` is the whole query's list — so whether this term narrowed or was dropped
        // depended on whether some *other* term had already failed.
        if (!matchedAny) return null

        return if (token.negated) {
            FacetConstraint(BooleanArray(accepted.size) { id -> !accepted[id] }, acceptMissing = true)
        } else {
            FacetConstraint(accepted, acceptMissing = false)
        }
    }

    /**
     * `-2h` counts back from the **last** entry, not from now: a log is almost always read after
     * the fact, and "the last two hours" of a file recorded yesterday means the file's last two
     * hours. `14:30` is a time on the day the file starts. Anything else is parsed as ISO.
     */
    private fun resolveInstant(rawValue: String, problems: MutableList<String>): Long? {
        val value: String = rawValue.trim()

        RELATIVE_DURATION.matchEntire(value)?.let { match ->
            val amount: Long = match.groupValues[1].toLong()
            val duration: Duration = when (match.groupValues[2]) {
                "s" -> Duration.ofSeconds(amount)
                "m" -> Duration.ofMinutes(amount)
                "h" -> Duration.ofHours(amount)
                else -> Duration.ofDays(amount)
            }
            return index.maxTimestampMillis - duration.toMillis()
        }

        runCatching { LocalTime.parse(value) }.getOrNull()?.let { time ->
            val firstDay: LocalDate = java.time.Instant.ofEpochMilli(index.minTimestampMillis).atZone(zone).toLocalDate()
            return time.atDate(firstDay).atZone(zone).toInstant().toEpochMilli()
        }

        runCatching { LocalDateTime.parse(value.replace(' ', 'T')) }.getOrNull()?.let { dateTime ->
            return dateTime.atZone(zone).toInstant().toEpochMilli()
        }

        runCatching { LocalDate.parse(value) }.getOrNull()?.let { date ->
            return date.atStartOfDay(zone).toInstant().toEpochMilli()
        }

        problems.add("Cannot read '$value' as a time. Try 14:30, 2026-06-02, 2026-06-02T14:30 or -2h")
        return null
    }

    private fun intersect(existing: BooleanArray?, added: BooleanArray): BooleanArray =
        existing?.let { previous -> BooleanArray(added.size) { index -> previous.getOrElse(index) { false } && added[index] } } ?: added

    private fun intersect(existing: FacetConstraint?, added: FacetConstraint): FacetConstraint {
        if (existing == null) return added
        return FacetConstraint(
            accepted = BooleanArray(added.accepted.size) { id -> existing.accepted.getOrElse(id) { false } && added.accepted[id] },
            acceptMissing = existing.acceptMissing && added.acceptMissing,
        )
    }
}
