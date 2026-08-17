package dev.loupe.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.ValueDictionary
import dev.loupe.core.profile.CompiledFacet
import dev.loupe.core.profile.FacetMode
import dev.loupe.core.query.QueryEdits
import dev.loupe.core.source.LogSource
import dev.loupe.desktop.format.Formatters
import dev.loupe.desktop.state.LEVEL_FIELD
import dev.loupe.desktop.state.Results
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing
import java.util.Locale

/**
 * The facet sidebar: what this file contains, counted, and clickable.
 *
 * Counts come from [dev.loupe.core.index.FacetCounts], each control counted with its own constraint
 * lifted — so a number says what you would get by clicking it, not what is on screen.
 */
/**
 * Facets with their counts and a volume bar, so the sidebar doubles as the shape of the file.
 *
 * Each count is what you would get **by clicking that value** — its own constraint lifted — which
 * is the only reading of the number that helps you decide where to go next. Counting over the
 * current result set instead would show every other category at zero the moment you pick one.
 */
@Composable
fun FacetSidebar(
    source: LogSource,
    results: Results,
    query: String,
    onToggleValue: (field: String, value: String) -> Unit,
    onClearField: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    val index: LogIndex = source.index
    val levelOrder: List<String> = index.profile.levelDecoder?.order.orEmpty()
    val levelLabels: List<String> = index.profile.levelDecoder?.labels.orEmpty()

    Column(
        modifier = modifier
            .background(colors.sunken)
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.large),
    ) {
        if (levelOrder.isNotEmpty()) {
            val selected: Set<String> = remember(query) { QueryEdits.selectedValues(query, LEVEL_FIELD, levelOrder) }
            FacetGroup(
                title = "Level",
                hasSelection = selected.isNotEmpty(),
                onClear = { onClearField(LEVEL_FIELD) },
            ) {
                val peak: Int = results.counts.levels.maxOrNull() ?: 0
                levelOrder.forEachIndexed { ordinal, symbol ->
                    FacetRow(
                        label = levelLabels.getOrElse(ordinal) { symbol },
                        count = results.counts.levels.getOrElse(ordinal) { 0 },
                        peak = peak,
                        selected = selected.any { value -> value.equals(symbol, ignoreCase = true) },
                        accent = colors.inkForLevel(ordinal, levelOrder.size),
                        onClick = { onToggleValue(LEVEL_FIELD, symbol) },
                    )
                }
            }
        }

        index.facets.forEachIndexed { facetIndex, facet ->
            FacetValues(
                facet = facet,
                dictionary = index.facetDictionaries[facetIndex],
                counts = results.counts.facets.getOrNull(facetIndex) ?: IntArray(0),
                query = query,
                onToggleValue = onToggleValue,
                onClearField = onClearField,
            )
        }
    }
}

@Composable
private fun FacetValues(
    facet: CompiledFacet,
    dictionary: ValueDictionary,
    counts: IntArray,
    query: String,
    onToggleValue: (String, String) -> Unit,
    onClearField: (String) -> Unit,
) {
    val colors = LoupeTheme.colors
    val selected: Set<String> = remember(query, facet.name) { QueryEdits.selectedValues(query, facet.name) }
    var search by remember(facet.name) { mutableStateOf("") }

    // A release build's tag facet has 817 distinct values, most of them R8 noise like "ou1".
    // A flat list is unusable, so anything that big gets top-N plus a search box.
    val searchable: Boolean = facet.mode == FacetMode.Auto && dictionary.size > TOP_N

    val ordered: List<Int> = remember(query, search, facet.name, counts) {
        dictionary.idsByDescendingCount()
            .filter { id -> counts.getOrElse(id) { 0 } > 0 || dictionary.valueOf(id) in selected }
            .filter { id -> search.isEmpty() || dictionary.valueOf(id).contains(search, ignoreCase = true) }
            .toList()
    }
    val shown: List<Int> = when {
        !searchable -> ordered
        search.isEmpty() -> ordered.take(TOP_N)
        else -> ordered.take(MAX_SEARCH_RESULTS)
    }

    FacetGroup(
        title = if (dictionary.size > TOP_N) "${facet.label} · ${dictionary.size}" else facet.label,
        hasSelection = selected.isNotEmpty(),
        onClear = { onClearField(facet.name) },
    ) {
        if (searchable) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.tiny, vertical = Spacing.tiny)
                    .clip(RoundedCornerShape(5.dp))
                    .background(LoupeTheme.colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(5.dp))
                    .padding(horizontal = Spacing.small, vertical = 3.dp),
            ) {
                if (search.isEmpty()) {
                    BasicText("search…", style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary))
                }
                BasicTextField(
                    value = search,
                    onValueChange = { typed -> search = typed },
                    singleLine = true,
                    textStyle = LoupeTheme.type.uiSmall.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val peak: Int = shown.maxOfOrNull { id -> counts.getOrElse(id) { 0 } } ?: 0
        shown.forEach { id ->
            FacetRow(
                label = dictionary.valueOf(id),
                count = counts.getOrElse(id) { 0 },
                peak = peak,
                selected = selected.any { value -> value.equals(dictionary.valueOf(id), ignoreCase = true) },
                accent = colors.accent,
                onClick = { onToggleValue(facet.name, dictionary.valueOf(id)) },
            )
        }
        if (ordered.size > shown.size) {
            BasicText(
                text = "+ ${ordered.size - shown.size} more",
                style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
                modifier = Modifier.padding(start = Spacing.small, top = Spacing.tiny),
            )
        }
    }
}

private const val TOP_N = 8

/**
 * How many search results a facet shows at once.
 *
 * The list below is a plain `Column`, not a `LazyColumn` — for eight rows that is the right call. But
 * typing one character used to compose *every* match, and a release build's tag facet has 817 values,
 * so one keystroke built 817 rows. Bounded now, and the "n more" line below already says so.
 */
private const val MAX_SEARCH_RESULTS = 40

@Composable
private fun FacetGroup(
    title: String,
    hasSelection: Boolean,
    onClear: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LoupeTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.small, vertical = Spacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.tiny, vertical = Spacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text = title.uppercase(Locale.getDefault()), style = LoupeTheme.type.label.copy(color = colors.inkTertiary))
            Spacer(Modifier.weight(1f))
            if (hasSelection) {
                BasicText(
                    text = "clear",
                    style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
                    modifier = Modifier.clickable(onClick = onClear),
                )
            }
        }
        content()
    }
}

@Composable
private fun FacetRow(
    label: String,
    count: Int,
    peak: Int,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val colors = LoupeTheme.colors
    Box(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // The volume bar sits behind the label rather than beside it: the sidebar is a
        // distribution as much as a set of controls, and a separate column would cost width.
        if (peak > 0 && count > 0) {
            Canvas(modifier = Modifier.fillMaxWidth().height(Spacing.rowHeight)) {
                val fraction: Float = (count.toDouble() / peak).toFloat().coerceIn(0f, 1f)
                drawRect(
                    color = colors.accent.copy(alpha = 0.14f),
                    size = Size(size.width * fraction, size.height),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.small, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Box(
                modifier = Modifier
                    .width(11.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) accent else colors.surface)
                    .border(1.dp, if (selected) accent else colors.borderStrong, RoundedCornerShape(3.dp)),
            )
            BasicText(
                text = label,
                style = LoupeTheme.type.uiSmall.copy(
                    color = if (selected) colors.ink else colors.inkSecondary,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = Formatters.count(count),
                style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
                maxLines = 1,
            )
        }
    }
}
