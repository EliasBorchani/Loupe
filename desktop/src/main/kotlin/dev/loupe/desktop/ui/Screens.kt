package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loupe.core.source.LogSource
import dev.loupe.core.source.OpenPhase
import dev.loupe.desktop.chooseAndOpen
import dev.loupe.desktop.chooseExportTarget
import dev.loupe.desktop.format.Formatters
import dev.loupe.desktop.state.LoupeState
import dev.loupe.desktop.state.OpenStatus
import dev.loupe.desktop.state.Results
import dev.loupe.desktop.state.Selection
import dev.loupe.desktop.state.ViewMode
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing
import dev.loupe.desktop.ui.DetailPane
import dev.loupe.desktop.ui.Divider
import dev.loupe.desktop.ui.FacetSidebar
import dev.loupe.desktop.ui.LogList
import dev.loupe.desktop.ui.ParseReportPane
import dev.loupe.desktop.ui.QueryBar
import dev.loupe.desktop.ui.SourceHeader
import dev.loupe.desktop.ui.StatusBar
import dev.loupe.desktop.ui.TimelineStrip
import dev.loupe.desktop.ui.VerticalDivider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The sidebar's width, which the loaded layout and nothing else decides. */
private val SIDEBAR_WIDTH = 224.dp

/**
 * The three things the window can be showing: a loaded log, an empty welcome, or progress.
 *
 * [Loaded] is the layout tree — header, query bar, sidebar, timeline, list, detail, status bar.
 * It lived in Main.kt, which is about starting a process and opening a window, and is neither.
 */
@Composable
internal fun Loaded(
    state: LoupeState,
    source: LogSource,
    results: Results?,
    query: String,
    viewMode: ViewMode,
    selection: Selection?,
    notice: String?,
    showParseReport: Boolean,
    expandedEntries: Set<Int>,
    queryFocus: FocusRequester,
    onCopyText: (String) -> Unit,
) {
    val catchingUp: Boolean = state.isCatchingUp(results)
    val scope: CoroutineScope = rememberCoroutineScope()
    val copySelection: () -> Unit = { scope.launch { state.copySelection()?.let(onCopyText) } }
    // The row the detail pane describes: the moving end of the selection.
    val focusedEntry: Int? = results
        ?.takeIf { current -> current.matchCount > 0 }
        ?.let { current -> selection?.focus?.takeIf { focus -> focus in 0 until current.matchCount }?.let(current.matches::get) }

    Column(modifier = Modifier.fillMaxSize()) {
        SourceHeader(
            source = source,
            onOpen = { chooseAndOpen(state, add = false) },
            onAdd = { chooseAndOpen(state, add = true) },
            onExport = { chooseExportTarget(source)?.let(state::export) },
        )
        Divider()

        QueryBar(
            query = query,
            onQueryChange = state::setQuery,
            matchCount = results?.matchCount ?: 0,
            totalCount = source.index.entryCount,
            problems = results?.problems.orEmpty(),
            catchingUp = catchingUp,
            focusRequester = queryFocus,
        )
        Divider()

        Row(modifier = Modifier.weight(1f)) {
            if (results != null) {
                FacetSidebar(
                    source = source,
                    results = results,
                    query = query,
                    onToggleValue = state::toggleFacetValue,
                    onClearField = state::clearField,
                    modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxSize(),
                )
                VerticalDivider()
            }

            Column(modifier = Modifier.weight(1f)) {
                if (results != null) {
                    TimelineStrip(
                        source = source,
                        results = results,
                        onBrush = state::setTimeWindow,
                    )
                    Divider()
                    LogList(
                        source = source,
                        results = results,
                        viewMode = viewMode,
                        selection = selection,
                        expandedEntries = expandedEntries,
                        onSelectAt = state::selectAt,
                        onExtendTo = state::extendTo,
                        onToggleExpanded = state::toggleExpanded,
                        onMoveSelection = state::moveSelection,
                        onSelectAll = state::selectAll,
                        onCopy = copySelection,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BasicText("indexing…", style = LoupeTheme.type.ui.copy(color = LoupeTheme.colors.inkTertiary))
                    }
                }
            }
        }

        // One bottom slot, one thing in it: the selected entry, or why some lines are unaccounted for.
        when {
            showParseReport -> {
                Divider()
                ParseReportPane(
                    report = source.index.unrecognised,
                    totalLines = source.index.lineCount,
                    profileProblems = source.profileProblems,
                    fileNameOf = { fileId -> source.files.getOrNull(fileId)?.name ?: "?" },
                    onClose = { state.showParseReport(false) },
                )
            }

            focusedEntry != null -> {
                Divider()
                DetailPane(
                    source = source,
                    entry = focusedEntry,
                    context = state.contextAround(focusedEntry),
                    onClose = state::clearSelection,
                    onCopy = copySelection,
                )
            }
        }

        Divider()
        StatusBar(
            source = source,
            results = results,
            catchingUp = catchingUp,
            selectionSize = selection?.size ?: 0,
            notice = notice,
            hasProfileProblems = source.profileProblems.isNotEmpty(),
            viewMode = viewMode,
            onViewModeChange = state::setViewMode,
            onShowParseReport = { state.showParseReport(true) },
        )
    }
}

@Composable
internal fun Welcome(failure: String?, onOpen: () -> Unit) {
    val colors = LoupeTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("Loupe", style = LoupeTheme.type.uiStrong.copy(color = colors.ink, fontSize = 28.sp))
            BasicText(
                text = "Drop a log file or a folder of them.",
                style = LoupeTheme.type.ui.copy(color = colors.inkSecondary),
                modifier = Modifier.padding(top = Spacing.small),
            )
            BasicText(
                text = "open…",
                style = LoupeTheme.type.uiStrong.copy(color = colors.onAccent),
                modifier = Modifier
                    .padding(top = Spacing.large)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = Spacing.large, vertical = Spacing.small),
            )
            if (failure != null) {
                BasicText(
                    text = failure,
                    style = LoupeTheme.type.uiSmall.copy(color = colors.error),
                    modifier = Modifier.padding(top = Spacing.large).width(520.dp),
                )
            }
        }
    }
}

@Composable
internal fun Opening(status: OpenStatus.Working) {
    val colors = LoupeTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = when (status.phase) {
                    OpenPhase.Converting -> "converting to text…"
                    OpenPhase.Detecting -> "recognising the format…"
                    OpenPhase.Indexing -> "indexing…"
                    OpenPhase.Merging -> "merging files…"
                },
                style = LoupeTheme.type.ui.copy(color = colors.inkSecondary),
            )
            Box(
                modifier = Modifier
                    .padding(top = Spacing.medium)
                    .width(320.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.sunken)
                    .drawBehind {
                        drawRect(color = colors.accent, size = Size(size.width * status.fraction, size.height))
                    },
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
            }
        }
    }
}
