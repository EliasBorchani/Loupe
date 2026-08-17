package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.loupe.core.source.LogSource
import dev.loupe.desktop.format.Formatters
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing

/**
 * The header: which files are open, which profile read them, and the actions on them.
 */
@Composable
fun SourceHeader(
    source: LogSource,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.sunken)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        BasicText(
            text = if (source.files.size == 1) source.files.first().name else "${source.files.size} files",
            style = LoupeTheme.type.uiStrong.copy(color = colors.ink),
            maxLines = 1,
        )
        BasicText(
            text = "${Formatters.count(source.index.entryCount)} entries · ${source.elapsedMillis} ms",
            style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
        )
        Spacer(Modifier.weight(1f))
        if (source.skipped.isNotEmpty()) {
            BasicText(
                text = "${source.skipped.size} skipped",
                style = LoupeTheme.type.uiSmall.copy(color = colors.warn),
            )
        }
        BasicText(
            text = "${source.profile.name} · ${(source.detection.score * 100).toInt()}%",
            style = LoupeTheme.type.uiSmall.copy(color = colors.accentInk),
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(colors.accentSoft)
                .padding(horizontal = Spacing.small, vertical = 2.dp),
        )
        BasicText(
            text = "open…",
            style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
            modifier = Modifier.clickable(onClick = onOpen),
        )
        // Dropping or opening replaces, the way every document app does. Adding is the other half
        // of "a set of files viewed as one stream", and it needs to be visible to exist.
        BasicText(
            text = "+ add…",
            style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
            modifier = Modifier.clickable(onClick = onAdd),
        )
        BasicText(
            text = "export…",
            style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
            modifier = Modifier.clickable(onClick = onExport),
        )
    }
}
