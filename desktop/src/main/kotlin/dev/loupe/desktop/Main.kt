package dev.loupe.desktop

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.loupe.core.source.LogSource
import dev.loupe.core.source.OpenPhase
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
import dev.loupe.desktop.ui.QueryBar
import dev.loupe.desktop.ui.SourceHeader
import dev.loupe.desktop.ui.StatusBar
import dev.loupe.desktop.ui.TimelineStrip
import dev.loupe.desktop.ui.VerticalDivider
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

private val SIDEBAR_WIDTH = 224.dp

fun main(args: Array<String>) {
    // Native-feeling menu bar and window title on macOS.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "Loupe")

    // Paths on the command line open straight away, which is what `loupe ~/logs` will do once the
    // CLI ships — and what makes the app testable without a human clicking a file dialog.
    val initial: List<File> = args.map { path -> File(path) }.filter { file -> file.exists() }

    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
        Window(onCloseRequest = ::exitApplication, title = "Loupe", state = windowState) {
            LoupeTheme {
                LoupeApp(initial)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LoupeApp(initialPaths: List<File> = emptyList()) {
    val scope = rememberCoroutineScope()
    val state = remember { LoupeState(scope) }
    val clipboard = LocalClipboard.current

    LaunchedEffect(Unit) {
        if (initialPaths.isNotEmpty()) state.open(initialPaths)
    }

    val source: LogSource? by state.source.collectAsState()
    val status: OpenStatus by state.status.collectAsState()
    val query: String by state.query.collectAsState()
    val results: Results? by state.results.collectAsState()
    val viewMode: ViewMode by state.viewMode.collectAsState()
    val selection: Selection? by state.selection.collectAsState()
    val notice: String? by state.notice.collectAsState()
    val expandedEntries: Set<Int> by state.expandedEntries.collectAsState()

    val dropTarget = remember {
        object : DragAndDropTarget {
            /** A drop replaces what is open, as in any document app; "+ add…" is the other half. */
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val dropped: List<File> = filesFrom(event) ?: return false
                if (dropped.isEmpty()) return false
                state.open(dropped)
                return true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LoupeTheme.colors.ground)
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
    ) {
        val current: LogSource? = source
        when {
            status is OpenStatus.Working -> Opening(status as OpenStatus.Working)
            current == null -> Welcome(
                failure = (status as? OpenStatus.Failed)?.message,
                onOpen = { chooseAndOpen(state, add = false) },
            )
            else -> Loaded(
                state = state,
                source = current,
                results = results,
                query = query,
                viewMode = viewMode,
                selection = selection,
                notice = notice,
                expandedEntries = expandedEntries,
                onCopyText = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) } },
            )
        }
    }
}

@Composable
private fun Loaded(
    state: LoupeState,
    source: LogSource,
    results: Results?,
    query: String,
    viewMode: ViewMode,
    selection: Selection?,
    notice: String?,
    expandedEntries: Set<Int>,
    onCopyText: (String) -> Unit,
) {
    val catchingUp: Boolean = state.isCatchingUp(results)
    val copySelection: () -> Unit = { state.copySelection()?.let(onCopyText) }
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

        focusedEntry?.let { entry ->
            Divider()
            DetailPane(
                source = source,
                entry = entry,
                context = state.contextAround(entry),
                onClose = state::clearSelection,
                onCopy = copySelection,
            )
        }

        Divider()
        StatusBar(
            source = source,
            results = results,
            catchingUp = catchingUp,
            selectionSize = selection?.size ?: 0,
            notice = notice,
            viewMode = viewMode,
            onViewModeChange = state::setViewMode,
        )
    }
}

@Composable
private fun Welcome(failure: String?, onOpen: () -> Unit) {
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
private fun Opening(status: OpenStatus.Working) {
    val colors = LoupeTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = when (status.phase) {
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

private fun chooseAndOpen(state: LoupeState, add: Boolean) {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser().apply {
        // Day files are named `2026-06-02` with no extension, and a folder of them is the normal
        // case — so both must be selectable, and nothing may filter on a suffix.
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        isMultiSelectionEnabled = true
        dialogTitle = if (add) "Add log files to the view" else "Open a log file or folder"
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
    val chosen: List<File> = chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) }
    if (chosen.isEmpty()) return
    if (add) state.add(chosen) else state.open(chosen)
}

/** Suggests a name from what is open, so an export lands somewhere recognisable. */
private fun chooseExportTarget(source: LogSource): File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val suggested: String = if (source.files.size == 1) {
        "${source.files.first().name}-filtered.txt"
    } else {
        "loupe-export.txt"
    }
    val chooser = JFileChooser().apply {
        dialogTitle = "Export the current filter"
        selectedFile = File(suggested)
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("UNCHECKED_CAST")
private fun filesFrom(event: DragAndDropEvent): List<File>? = runCatching {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
}.getOrNull()
