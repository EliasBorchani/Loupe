package dev.loupe.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import dev.loupe.core.profile.ProfileRegistry
import dev.loupe.core.source.LogSource
import dev.loupe.desktop.state.LoupeState
import dev.loupe.desktop.state.ViewMode
import java.awt.Desktop
import java.io.File

/**
 * The macOS menu bar.
 *
 * With `apple.laf.useScreenMenuBar` set this lands at the top of the screen rather than inside the
 * window, which is where a Mac user looks for it — and it is also where a feature goes to be
 * *discovered*. Exporting and adding a profile both existed before this and neither was findable
 * without being told.
 *
 * **There is deliberately no Edit menu.** A menu shortcut is claimed by the native menu before the
 * window ever sees the key, so putting Copy on ⌘C and Select All on ⌘A here would break both
 * inside the query field — you would be selecting log rows while trying to select text. They stay
 * as window-level handlers, scoped to the list that owns them.
 */
@Composable
fun FrameWindowScope.LoupeMenuBar(
    state: LoupeState,
    source: LogSource?,
    viewMode: ViewMode,
    queryFocus: FocusRequester,
) {
    val isOpen: Boolean = source != null

    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("Open…", shortcut = KeyShortcut(Key.O, meta = true)) {
                chooseAndOpen(state, add = false)
            }
            Item("Add Files…", shortcut = KeyShortcut(Key.O, meta = true, shift = true), enabled = isOpen) {
                chooseAndOpen(state, add = true)
            }
            Separator()
            Item("Export Current Filter…", shortcut = KeyShortcut(Key.E, meta = true), enabled = isOpen) {
                source?.let { open -> chooseExportTarget(open)?.let(state::export) }
            }
            Separator()
            Item("Close Log", enabled = isOpen) { state.close() }
        }

        Menu("View", mnemonic = 'V') {
            Item("Columns", shortcut = KeyShortcut(Key.One, meta = true), enabled = isOpen) {
                state.setViewMode(ViewMode.Columns)
            }
            Item("Raw Line", shortcut = KeyShortcut(Key.Two, meta = true), enabled = isOpen) {
                state.setViewMode(ViewMode.Raw)
            }
            Separator()
            Item("Find", shortcut = KeyShortcut(Key.F, meta = true), enabled = isOpen) {
                runCatching { queryFocus.requestFocus() }
            }
            Item("Unrecognised Lines…", enabled = isOpen) { state.showParseReport(true) }
        }

        Menu("Profiles", mnemonic = 'P') {
            Item("Reveal Profiles Folder") { revealProfilesFolder() }
            Menu("New from Template") {
                // Copying a bundled profile beats an empty file: they are heavily commented, and
                // the fastest way to describe a new format is to edit one that already works.
                ProfileRegistry.bundledFileNames().forEach { fileName ->
                    Item(fileName.removeSuffix(ProfileRegistry.PROFILE_EXTENSION)) {
                        copyTemplate(fileName)
                    }
                }
            }
            Separator()
            // Profiles are re-read on open, so reopening the same files is the reload.
            Item("Reload Profiles and Reopen", enabled = isOpen) {
                source?.let { open -> state.open(open.files) }
            }
        }
    }
}

/** Creates the directory if it is missing, so the menu item always leads somewhere. */
private fun revealProfilesFolder() {
    val directory: File = ProfileRegistry.userDirectory()
    directory.mkdirs()
    runCatching { Desktop.getDesktop().open(directory) }
}

/**
 * Writes a bundled profile into the user's directory under a new name, and reveals it.
 *
 * Never overwrites: someone who already started editing `withings.logprofile.toml` would lose it,
 * and a menu item is not where you expect to be asked about that.
 */
private fun copyTemplate(bundledFileName: String) {
    val source: String = ProfileRegistry.bundledSource(bundledFileName) ?: return
    val directory: File = ProfileRegistry.userDirectory()
    directory.mkdirs()

    val base: String = bundledFileName.removeSuffix(ProfileRegistry.PROFILE_EXTENSION)
    var target = File(directory, "$base-copy${ProfileRegistry.PROFILE_EXTENSION}")
    var attempt = 2
    while (target.exists()) {
        target = File(directory, "$base-copy-$attempt${ProfileRegistry.PROFILE_EXTENSION}")
        attempt++
    }

    // The name inside the file has to change too, or the copy and the original both answer to the
    // same name and detection has two candidates it cannot tell apart.
    target.writeText(source.replaceFirst(Regex("""^name\s*=\s*"[^"]*"""", RegexOption.MULTILINE), """name = "$base-copy""""))
    runCatching { ProcessBuilder("open", "-R", target.absolutePath).start() }
}
