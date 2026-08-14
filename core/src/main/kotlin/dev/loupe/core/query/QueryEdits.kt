package dev.loupe.core.query

import java.util.Locale

/**
 * Rewrites a query string the way a facet click should.
 *
 * The design decision this exists to serve: **the query text is the single source of truth.**
 * Ticking a box in the sidebar does not update a parallel selection model — it edits the text the
 * user can see, which is how they end up learning `level>=W` without ever reading a grammar.
 *
 * Edits are textual splices over the tokens' own spans, so everything the user typed and this
 * class does not understand — a phrase, a regex, a comparison it has no opinion about — survives
 * untouched at the position they put it.
 */
object QueryEdits {

    /**
     * Adds [value] to the term for [field], or removes it if already there.
     *
     * @param severityOrder when [field] is the level field, its scale. A selection that runs
     *   contiguously to the most severe end collapses to `level>=W`, because that is both shorter
     *   and what the user actually meant by ticking Warn and Error.
     */
    fun toggleFacetValue(
        query: String,
        field: String,
        value: String,
        severityOrder: List<String>? = null,
    ): String {
        val existing: List<QueryToken.Field> = editableTermsFor(query, field)
        val current: LinkedHashSet<String> = LinkedHashSet()
        existing.forEach { term -> current.addAll(expand(term, severityOrder)) }

        val alreadyThere: Boolean = current.any { candidate -> candidate.equals(value, ignoreCase = true) }
        if (alreadyThere) current.removeAll { candidate -> candidate.equals(value, ignoreCase = true) } else current.add(value)

        return replaceTerms(query, existing, serialise(field, current, severityOrder))
    }

    /** Drops every term for [field], leaving the rest of the query as written. */
    fun clearField(query: String, field: String): String =
        replaceTerms(query, editableTermsFor(query, field), null)

    /** Sets — or with `null`, removes — the `since` / `until` bounds a timeline brush produces. */
    fun setTimeWindow(query: String, since: String?, until: String?): String {
        var edited: String = replaceTerms(query, editableTermsFor(query, "since"), since?.let { bound -> "since:$bound" })
        edited = replaceTerms(edited, editableTermsFor(edited, "until"), until?.let { bound -> "until:$bound" })
        return edited
    }

    /** Which values a query currently selects for [field] — what the sidebar renders as ticked. */
    fun selectedValues(query: String, field: String, severityOrder: List<String>? = null): Set<String> {
        val selected: LinkedHashSet<String> = LinkedHashSet()
        editableTermsFor(query, field).forEach { term -> selected.addAll(expand(term, severityOrder)) }
        return selected
    }

    /**
     * Terms this class is willing to rewrite: same field, not negated, and a comparison it can
     * round-trip. A `-cat:Ui` or a `level<D` is left alone and a new term is added beside it —
     * silently rewriting something the user typed deliberately would be worse than a redundant term.
     */
    private fun editableTermsFor(query: String, field: String): List<QueryToken.Field> =
        QueryLexer.tokenize(query)
            .filterIsInstance<QueryToken.Field>()
            .filter { term ->
                term.field.equals(field, ignoreCase = true) &&
                    !term.negated &&
                    (term.comparison == Comparison.Equals || term.comparison == Comparison.AtLeast)
            }

    /** `level>=W` on a `V D I W E` scale means W and E, so a toggle can remove either. */
    private fun expand(term: QueryToken.Field, severityOrder: List<String>?): List<String> {
        if (term.comparison != Comparison.AtLeast || severityOrder == null) return term.values()
        val pivot: String = term.values().firstOrNull() ?: return emptyList()
        val from: Int = severityOrder.indexOfFirst { level -> level.equals(pivot, ignoreCase = true) }
        if (from < 0) return term.values()
        return severityOrder.subList(from, severityOrder.size)
    }

    private fun serialise(field: String, values: Set<String>, severityOrder: List<String>?): String? {
        if (values.isEmpty()) return null
        if (severityOrder != null) {
            val ordinals: List<Int> = values
                .map { value -> severityOrder.indexOfFirst { level -> level.equals(value, ignoreCase = true) } }
                .filter { ordinal -> ordinal >= 0 }
                .sorted()
            val runsToTop: Boolean = ordinals.isNotEmpty() &&
                ordinals.last() == severityOrder.size - 1 &&
                ordinals.last() - ordinals.first() == ordinals.size - 1
            // A single level is clearer as `level:E` than as `level>=E`, even though both hold.
            if (runsToTop && ordinals.size > 1) return "$field>=${severityOrder[ordinals.first()]}"
            return "$field:" + ordinals.joinToString(",") { ordinal -> severityOrder[ordinal] }
        }
        return "$field:" + values.joinToString(",")
    }

    /**
     * Splices [replacement] in where the first removed term sat, and drops the rest.
     *
     * Works back-to-front so an earlier span's offsets stay valid while a later one is being cut.
     */
    private fun replaceTerms(query: String, terms: List<QueryToken.Field>, replacement: String?): String {
        if (terms.isEmpty()) {
            if (replacement == null) return query
            return if (query.isBlank()) replacement else "${query.trimEnd()} $replacement"
        }
        var edited: String = query
        terms.sortedByDescending { term -> term.start }.forEachIndexed { fromEnd, term ->
            val isFirstTerm: Boolean = fromEnd == terms.size - 1
            val insert: String = if (isFirstTerm && replacement != null) replacement else ""
            edited = edited.substring(0, term.start) + insert + edited.substring(term.end)
        }
        return tidy(edited)
    }

    /** Removing a term leaves a double space or a leading one; nobody wants to see that. */
    private fun tidy(query: String): String = query.trim().replace(Regex(" {2,}"), " ")

    /** Case-insensitive field matching, kept in one place. */
    internal fun normalise(field: String): String = field.lowercase(Locale.ROOT)
}
