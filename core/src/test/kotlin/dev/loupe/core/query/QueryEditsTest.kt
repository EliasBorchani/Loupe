package dev.loupe.core.query

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Facet clicks, expressed as edits to the query text.
 *
 * The property that matters throughout: **anything the user typed that this does not understand
 * survives, in place.** A phrase, a regex, a negation, a comparison it has no opinion about — all
 * of them must come out the other side untouched, because the alternative is an app that quietly
 * rewrites what you wrote.
 */
class QueryEditsTest {

    private val severity = listOf("V", "D", "I", "W", "E")

    @Test
    fun `adds a term to an empty query`() {
        assertEquals("category:Sync", QueryEdits.toggleFacetValue("", "category", "Sync"))
    }

    @Test
    fun `adds a value to an existing term rather than a second term`() {
        assertEquals("category:Sync,Wpp", QueryEdits.toggleFacetValue("category:Sync", "category", "Wpp"))
    }

    @Test
    fun `removes a value, and the whole term once it is empty`() {
        assertEquals("category:Wpp", QueryEdits.toggleFacetValue("category:Sync,Wpp", "category", "Sync"))
        assertEquals("", QueryEdits.toggleFacetValue("category:Sync", "category", "Sync"))
    }

    @Test
    fun `collapses a run to the top of the severity scale`() {
        // Given / When — Warn then Error, on a V D I W E scale.
        val withWarn: String = QueryEdits.toggleFacetValue("", "level", "W", severity)
        val withBoth: String = QueryEdits.toggleFacetValue(withWarn, "level", "E", severity)

        // Then — one level reads better as an equality; a run to the top reads better as `>=`.
        assertEquals("level:W", withWarn)
        assertEquals("level>=W", withBoth)
    }

    @Test
    fun `expands a comparison before removing from it`() {
        // Given — `level>=W` means W and E, so unticking Error must leave W behind.
        // When
        val remaining: String = QueryEdits.toggleFacetValue("level>=W", "level", "E", severity)

        // Then
        assertEquals("level:W", remaining)
    }

    @Test
    fun `keeps a non-contiguous selection as an enumeration`() {
        // Given / When
        val verbose: String = QueryEdits.toggleFacetValue("", "level", "V", severity)
        val both: String = QueryEdits.toggleFacetValue(verbose, "level", "I", severity)

        // Then — ordered by severity, not by click order.
        assertEquals("level:V,I", both)
    }

    @Test
    fun `leaves the rest of the query exactly where it was`() {
        // Given — a phrase before, a regex after.
        val query = "\"connection lost\" category:Sync /retry\\d+/"

        // When
        val edited: String = QueryEdits.toggleFacetValue(query, "category", "Wpp")

        // Then
        assertEquals("\"connection lost\" category:Sync,Wpp /retry\\d+/", edited)
    }

    @Test
    fun `does not rewrite a negated term, it adds beside it`() {
        // Given — `-category:Ui` was typed deliberately; silently folding a positive value into it
        // would invert what the user asked for.
        // When
        val edited: String = QueryEdits.toggleFacetValue("-category:Ui", "category", "Sync")

        // Then
        assertEquals("-category:Ui category:Sync", edited)
    }

    @Test
    fun `clearing a field leaves everything else intact`() {
        assertEquals(
            "\"timeout\" level>=W",
            QueryEdits.clearField("\"timeout\" category:Sync,Wpp level>=W", "category"),
        )
    }

    @Test
    fun `sets and clears a time window`() {
        // Given / When
        val bounded: String = QueryEdits.setTimeWindow("category:Sync", "14:30", "14:35")
        val cleared: String = QueryEdits.setTimeWindow(bounded, null, null)

        // Then
        assertEquals("category:Sync since:14:30 until:14:35", bounded)
        assertEquals("category:Sync", cleared)
    }

    @Test
    fun `replaces an existing window rather than stacking bounds`() {
        assertEquals(
            "since:15:00 until:16:00",
            QueryEdits.setTimeWindow("since:14:30 until:14:35", "15:00", "16:00"),
        )
    }

    @Test
    fun `reads back what a query selects, so the sidebar can tick the right boxes`() {
        assertEquals(setOf("Sync", "Wpp"), QueryEdits.selectedValues("category:Sync,Wpp \"x\"", "category"))
        assertEquals(setOf("W", "E"), QueryEdits.selectedValues("level>=W", "level", severity))
        assertEquals(emptySet<String>(), QueryEdits.selectedValues("category:Sync", "tag"))
    }

    @Test
    fun `every edit round-trips through the lexer`() {
        // Given — the edited text is what gets parsed next, so it must stay well-formed.
        var query = ""
        listOf("Sync" to "category", "Wpp" to "category", "W" to "level", "E" to "level")
            .forEach { (value, field) ->
                query = QueryEdits.toggleFacetValue(query, field, value, severity.takeIf { field == "level" })
            }

        // When
        val tokens: List<QueryToken> = QueryLexer.tokenize(query)

        // Then
        assertEquals("category:Sync,Wpp level>=W", query)
        assertEquals(2, tokens.size)
        assertEquals(listOf("category", "level"), tokens.filterIsInstance<QueryToken.Field>().map { it.field })
    }
}
