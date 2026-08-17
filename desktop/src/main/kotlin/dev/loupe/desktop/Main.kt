package dev.loupe.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.loupe.desktop.ui.Loaded
import dev.loupe.desktop.ui.LogList
import dev.loupe.desktop.ui.Opening
import dev.loupe.desktop.ui.ParseReportPane
import dev.loupe.desktop.ui.QueryBar
import dev.loupe.desktop.ui.SourceHeader
import dev.loupe.desktop.ui.StatusBar
import dev.loupe.desktop.ui.TimelineStrip
import dev.loupe.desktop.ui.VerticalDivider
import dev.loupe.desktop.ui.Welcome
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


fun main(args: Array<String>) {
    // Native-feeling menu bar and window title on macOS.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "Loupe")

    // Paths on the command line open straight away, which is what `loupe ~/logs` will do once the
    // CLI ships — and what makes the app testable without a human clicking a file dialog.
    //
    // A path that does not exist is reported, not dropped. Silently ignoring it opens an empty
    // window that looks exactly like a successful launch, which cost real time to notice.
    val requested: List<File> = args.map { path -> File(path) }
    val missing: List<File> = requested.filterNot { file -> file.exists() }
    missing.forEach { file -> System.err.println("loupe: no such file or folder: ${file.absolutePath}") }
    val initial: List<File> = requested - missing.toSet()

    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
        val windowIcon: Painter = remember { loadWindowIcon() }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Loupe",
            state = windowState,
            // The packaged app takes its icon from the .icns; `gradlew run` has no bundle, so the
            // window loads the PNG off the classpath.
            icon = windowIcon,
        ) {
            val scope = rememberCoroutineScope()
            val state = remember { LoupeState(scope) }
            val queryFocus = remember { FocusRequester() }
            val source: LogSource? by state.source.collectAsState()
            val viewMode: ViewMode by state.viewMode.collectAsState()

            LoupeMenuBar(state = state, source = source, viewMode = viewMode, queryFocus = queryFocus)

            LoupeTheme {
                LoupeApp(state = state, queryFocus = queryFocus, initialPaths = initial)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LoupeApp(
    state: LoupeState,
    queryFocus: FocusRequester,
    initialPaths: List<File> = emptyList(),
) {
    val scope = rememberCoroutineScope()
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
    val showParseReport: Boolean by state.showParseReport.collectAsState()
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
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
            // Window-wide, because "find" has to work wherever you are. Only these two: everything
            // else must reach the list, and a swallowed key is worse than an unhandled one.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) return@onPreviewKeyEvent false
                if (event.key != Key.F && event.key != Key.L) return@onPreviewKeyEvent false
                runCatching { queryFocus.requestFocus() }
                true
            },
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
                showParseReport = showParseReport,
                expandedEntries = expandedEntries,
                queryFocus = queryFocus,
                onCopyText = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) } },
            )
        }
    }
}

private fun loadWindowIcon(): Painter {
    val bytes: ByteArray = requireNotNull(object {}.javaClass.getResourceAsStream("/icon.png")) {
        "icon.png is not on the classpath — processResources should copy it from desktop/"
    }.use { stream -> stream.readBytes() }
    return BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

/** Suggests a name from what is open, so an export lands somewhere recognisable. */
