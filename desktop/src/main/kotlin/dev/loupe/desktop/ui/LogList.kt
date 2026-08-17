package dev.loupe.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.loupe.core.index.LogIndex
import dev.loupe.core.source.EntryRenderer
import dev.loupe.core.source.LogSource
import dev.loupe.core.source.RenderedEntry
import dev.loupe.desktop.state.Results
import dev.loupe.desktop.state.Selection
import dev.loupe.desktop.state.ViewMode
import dev.loupe.desktop.format.Formatters
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing
import java.time.format.DateTimeFormatter


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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LogList(
    source: LogSource,
    results: Results,
    viewMode: ViewMode,
    selection: Selection?,
    expandedEntries: Set<Int>,
    onSelectAt: (Int) -> Unit,
    onExtendTo: (Int) -> Unit,
    onToggleExpanded: (Int) -> Unit,
    onMoveSelection: (delta: Int, extend: Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    val listState = rememberLazyListState()
    val listFocus = remember { FocusRequester() }
    // Shared across rows so they scroll as one. Raw mode only: in columns, scrolling sideways would
    // push the timestamps off screen and destroy the alignment columns exist for — there, a line is
    // truncated and the detail pane holds the rest.
    val sideways: ScrollState = rememberScrollState()
    // `clickable` reports that a click happened, not which modifiers were down. The window does.
    val windowInfo = LocalWindowInfo.current
    val index: LogIndex = source.index
    val levelCount: Int = index.profile.levelCount
    val levelSymbols: List<String> = remember(index) { index.profile.levelDecoder?.order ?: emptyList() }

    // The list takes focus when a file opens, so the arrows work without a click first. Clicking a
    // row focuses it too — its `clickable` makes it focusable — and the key handler sits on the
    // container, which still sees the event as it bubbles up from the focused row.
    LaunchedEffect(source) { runCatching { listFocus.requestFocus() } }

    // Follows the focus rather than driving it, so arrow keys and clicks scroll identically.
    LaunchedEffect(selection?.focus, results) {
        val focus: Int = selection?.focus ?: return@LaunchedEffect
        if (focus in 0 until results.matchCount) listState.keepInView(focus)
    }

    Box(
        modifier = modifier
            .background(colors.surface)
            .focusRequester(listFocus)
            .focusable()
            // Preview, not the bubbling pass: it runs on the way down to the focused node, so the
            // arrow is ours before the LazyColumn or a focused row can read it as a scroll or a
            // focus move. The Box wraps only the list, so a cursor in the query bar is untouched.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val page: Int = maxOf(1, listState.layoutInfo.visibleItemsInfo.size - 1)
                when {
                    event.isMetaPressed && event.key == Key.A -> onSelectAll()
                    event.isMetaPressed && event.key == Key.C -> onCopy()
                    event.key == Key.DirectionDown || event.key == Key.J -> onMoveSelection(1, event.isShiftPressed)
                    event.key == Key.DirectionUp || event.key == Key.K -> onMoveSelection(-1, event.isShiftPressed)
                    event.key == Key.PageDown -> onMoveSelection(page, event.isShiftPressed)
                    event.key == Key.PageUp -> onMoveSelection(-page, event.isShiftPressed)
                    // Home and End are a page move large enough to hit the end, which stops there.
                    event.key == Key.MoveHome -> onMoveSelection(-results.matchCount, event.isShiftPressed)
                    event.key == Key.MoveEnd -> onMoveSelection(results.matchCount, event.isShiftPressed)
                    // Anything else belongs to whoever asked for it — a swallowed key is worse
                    // than an unhandled one.
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        if (results.matchCount == 0) {
            EmptyResults(Modifier.align(Alignment.Center))
            return@Box
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(count = results.matchCount, key = { position -> results.matches[position] }) { position ->
                val entry: Int = results.matches[position]
                val rendered: RenderedEntry = remember(entry, source) { EntryRenderer.render(source, entry) }
                val ordinal: Int = index.levels[entry].toInt()
                val selected: Boolean = selection != null && position in selection
                val focused: Boolean = selection?.focus == position

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                // The focused row is the one the detail pane describes, so it reads
                                // a shade stronger than the rest of the run.
                                focused -> colors.accentSoft
                                selected -> colors.accentSoft.copy(alpha = 0.55f)
                                else -> colors.surfaceForLevel(ordinal, levelCount)
                            },
                        )
                        .clickable {
                            if (windowInfo.keyboardModifiers.isShiftPressed) onExtendTo(position) else onSelectAt(position)
                        }
                        .padding(horizontal = Spacing.medium, vertical = 1.dp),
                ) {
                    when (viewMode) {
                        ViewMode.Columns -> ColumnsRow(
                            source = source,
                            entry = entry,
                            ordinal = ordinal,
                            levelSymbols = levelSymbols,
                            rendered = rendered,
                            expanded = entry in expandedEntries,
                            onToggleExpanded = { onToggleExpanded(entry) },
                        )

                        ViewMode.Raw -> RawRow(
                            rendered = rendered,
                            ordinal = ordinal,
                            levelCount = levelCount,
                            expanded = entry in expandedEntries,
                            sideways = sideways,
                            onToggleExpanded = { onToggleExpanded(entry) },
                        )
                    }

                    if (entry in expandedEntries) {
                        rendered.continuations.forEach { line ->
                            BasicText(
                                text = line,
                                style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
                                maxLines = 1,
                                overflow = if (viewMode == ViewMode.Raw) TextOverflow.Clip else TextOverflow.Ellipsis,
                                softWrap = false,
                                modifier = if (viewMode == ViewMode.Raw) {
                                    Modifier.horizontalScroll(sideways)
                                } else {
                                    Modifier.padding(start = TIME_WIDTH)
                                },
                            )
                        }
                    }
                }
            }
        }

        // fillMaxHeight, never fillMaxSize: a scrollbar stretched over the full width sits on top
        // of every row and swallows the clicks and drags meant for them.
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )

        if (viewMode == ViewMode.Raw) {
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(sideways),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            )
        }
    }
}

/**
 * Scrolls only when [position] is at or past an edge, keeping one row of margin.
 *
 * Scrolling unconditionally would jerk the list every time a visible row was clicked; the margin
 * means arrowing towards an edge moves the viewport before the selection reaches it, so the next
 * row is already on screen when you get there.
 */
private suspend fun LazyListState.keepInView(position: Int) {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        scrollToItem(position)
        return
    }
    val first: Int = visible.first().index
    val last: Int = visible.last().index
    when {
        position <= first -> scrollToItem(maxOf(0, position - 1))
        position >= last -> scrollToItem(maxOf(0, position - (last - first) + 1))
    }
}

@Composable
private fun ColumnsRow(
    source: LogSource,
    entry: Int,
    ordinal: Int,
    levelSymbols: List<String>,
    rendered: RenderedEntry,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = LoupeTheme.colors
    val index: LogIndex = source.index
    val mono = LoupeTheme.type.mono

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        BasicText(
            text = Formatters.millisecond(index.timestamps[entry]),
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

/**
 * The line as written, scrolling sideways.
 *
 * The scroll goes on this Row and not on the item, so the selection highlight still spans the
 * viewport while the text moves inside it. `weight(1f)` is gone with it: inside a horizontal scroll
 * the width is unbounded, and a weight needs a bound.
 */
@Composable
private fun RawRow(
    rendered: RenderedEntry,
    ordinal: Int,
    levelCount: Int,
    expanded: Boolean,
    sideways: ScrollState,
    onToggleExpanded: () -> Unit,
) {
    val colors = LoupeTheme.colors
    Row(
        modifier = Modifier.horizontalScroll(sideways),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        BasicText(
            text = rendered.raw.substringBefore('\n'),
            style = LoupeTheme.type.mono.copy(color = colors.inkForLevel(ordinal, levelCount)),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
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
