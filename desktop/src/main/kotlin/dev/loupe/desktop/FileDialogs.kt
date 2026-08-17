package dev.loupe.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import dev.loupe.core.source.LogSource
import dev.loupe.desktop.state.LoupeState
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * The Swing file dialogs, and the drag-and-drop decoding beside them.
 *
 * Out of Main.kt because they are neither the process nor the window: they are AWT interop, and
 * `LoupeMenuBar` needed two of them, which is why they were `internal` and reaching across a file
 * about `main`. The look-and-feel call was written twice; it is set once here, lazily, because
 * doing it at startup costs a Swing class load the app may never need.
 */
internal fun chooseAndOpen(state: LoupeState, add: Boolean) {
    useNativeLookAndFeel()
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

/**
 * The window icon, decoded straight off the classpath.
 *
 * Not `painterResource`: it is deprecated in favour of the Compose resources library, which means a
 * generated accessor class and another dependency — a lot of machinery for one PNG. Skia is already
 * here as part of Compose Desktop and decodes it in a line.
 */

internal fun chooseExportTarget(source: LogSource): File? {
    useNativeLookAndFeel()
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
internal fun filesFrom(event: DragAndDropEvent): List<File>? = runCatching {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
}.getOrNull()

/**
 * Once per process, on first use.
 *
 * A JFileChooser under the cross-platform look and feel on macOS is unmistakably not a Mac dialog.
 */
private var lookAndFeelSet = false

private fun useNativeLookAndFeel() {
    if (lookAndFeelSet) return
    lookAndFeelSet = true
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
}
