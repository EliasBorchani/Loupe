package dev.loupe.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.loupe.core.index.LogIndex
import dev.loupe.core.source.EntryRenderer
import dev.loupe.core.source.LogSource
import dev.loupe.core.source.RenderedEntry
import dev.loupe.desktop.state.Results
import dev.loupe.desktop.state.ViewMode
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private val TIME_WIDTH = 84.dp
private val LEVEL_WIDTH = 16.dp
private val FACET_WIDTH = 132.dp

/**
 * The list of matching entries.
 *
 * **Rows are one line tall, always.** A wrapping row would make every scroll position a layout
 * pass, which is what kills a virtualised list; the full text of an entry lives one click away in
 * the detail pane, and a stack trace expands in place on demand. Uniform height is the whole
 * reason nine million entries can scroll at all.
 *
 * Text is decoded per visible row, never in bulk: [LogIndex] stores byte ranges, and only the
 * forty-odd rows on screen are ever turned into strings.
 */
@Composable
fun LogList(
    source: LogSource,
    results: Results,
    viewMode: ViewMode,
    selectedEntry: Int?,
    expandedEntries: Set<Int>,
    onSelect: (Int) -> Unit,
    onToggleExpanded: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    val listState = rememberLazyListState()
    val zone: ZoneId = remember { ZoneId.systemDefault() }
    val index: LogIndex = source.index
    val levelCount: Int = index.profile.levelCount
    val levelSymbols: List<String> = remember(index) { index.profile.levelDecoder?.order ?: emptyList() }

    Box(modifier = modifier.background(colors.surface)) {
        if (results.matchCount == 0) {
            EmptyResults(Modifier.align(Alignment.Center))
            return@Box
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(results.matchCount) { position ->
                val entry: Int = results.matches[position]
                val rendered: RenderedEntry = remember(entry, source) { EntryRenderer.render(index, source.text, entry) }
                val ordinal: Int = index.levels[entry].toInt()
                val selected: Boolean = entry == selectedEntry

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                selected -> colors.accentSoft
                                else -> colors.surfaceForLevel(ordinal, levelCount)
                            },
                        )
                        .clickable { onSelect(entry) }
                        .padding(horizontal = Spacing.medium, vertical = 1.dp),
                ) {
                    when (viewMode) {
                        ViewMode.Columns -> ColumnsRow(
                            source = source,
                            entry = entry,
                            ordinal = ordinal,
                            levelSymbols = levelSymbols,
                            rendered = rendered,
                            zone = zone,
                            expanded = entry in expandedEntries,
                            onToggleExpanded = { onToggleExpanded(entry) },
                        )

                        ViewMode.Raw -> RawRow(
                            rendered = rendered,
                            ordinal = ordinal,
                            levelCount = levelCount,
                            expanded = entry in expandedEntries,
                            onToggleExpanded = { onToggleExpanded(entry) },
                        )
                    }

                    if (entry in expandedEntries) {
                        rendered.continuations.forEach { line ->
                            BasicText(
                                text = line,
                                style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = TIME_WIDTH),
                            )
                        }
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxSize(),
        )
    }
}

@Composable
private fun ColumnsRow(
    source: LogSource,
    entry: Int,
    ordinal: Int,
    levelSymbols: List<String>,
    rendered: RenderedEntry,
    zone: ZoneId,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = LoupeTheme.colors
    val index: LogIndex = source.index
    val mono = LoupeTheme.type.mono

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        BasicText(
            text = TIME_FORMAT.format(Instant.ofEpochMilli(index.timestamps[entry]).atZone(zone)),
            style = mono.copy(color = colors.inkTertiary),
            maxLines = 1,
            modifier = Modifier.width(TIME_WIDTH),
        )
        BasicText(
            text = levelSymbols.getOrElse(ordinal) { "?" },
            style = mono.copy(
                color = colors.inkForLevel(ordinal, index.profile.levelCount),
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            modifier = Modifier.width(LEVEL_WIDTH),
        )
        // Only the profile's own facets get a column; the synthetic `file` facet stays in the
        // sidebar, where it belongs — a column of identical file names is wasted width.
        index.profile.facets.forEachIndexed { facetIndex, _ ->
            val valueId: Int = index.facetValues[facetIndex][entry]
            BasicText(
                text = if (valueId == LogIndex.NO_VALUE) "—" else index.facetDictionaries[facetIndex].valueOf(valueId),
                style = mono.copy(color = if (facetIndex == 0) colors.accentInk else colors.inkTertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(FACET_WIDTH),
            )
        }
        BasicText(
            text = rendered.message,
            style = mono.copy(color = colors.inkForLevel(ordinal, index.profile.levelCount)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FoldToggle(rendered, expanded, onToggleExpanded)
    }
}

@Composable
private fun RawRow(
    rendered: RenderedEntry,
    ordinal: Int,
    levelCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = LoupeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        BasicText(
            text = rendered.raw.substringBefore('\n'),
            style = LoupeTheme.type.mono.copy(color = colors.inkForLevel(ordinal, levelCount)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FoldToggle(rendered, expanded, onToggleExpanded)
    }
}

/** A stack trace is not nine rows, it is one entry — so it folds. */
@Composable
private fun FoldToggle(rendered: RenderedEntry, expanded: Boolean, onToggle: () -> Unit) {
    if (!rendered.hasContinuations) return
    val colors = LoupeTheme.colors
    BasicText(
        text = if (expanded) "▾ fold" else "▸ +${rendered.continuations.size}",
        style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
        maxLines = 1,
        modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = Spacing.tiny),
    )
}

@Composable
private fun EmptyResults(modifier: Modifier = Modifier) {
    val colors = LoupeTheme.colors
    Column(modifier = modifier.padding(Spacing.large), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            text = "No entry matches.",
            style = LoupeTheme.type.uiStrong.copy(color = colors.inkSecondary),
        )
        BasicText(
            text = "Untick a facet, or widen the time range.",
            style = LoupeTheme.type.ui.copy(color = colors.inkTertiary),
            modifier = Modifier.padding(top = Spacing.tiny),
        )
    }
}
