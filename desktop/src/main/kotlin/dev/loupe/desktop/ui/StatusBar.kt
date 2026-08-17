package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import dev.loupe.core.index.LogIndex
import dev.loupe.core.source.LogSource
import dev.loupe.desktop.format.Formatters
import dev.loupe.desktop.state.Results
import dev.loupe.desktop.state.ViewMode
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing

/**
 * The status bar: what was read, how much of it was understood, and how long it took.
 */
@Composable
fun StatusBar(
    source: LogSource,
    results: Results?,
    catchingUp: Boolean,
    selectionSize: Int,
    notice: String?,
    hasProfileProblems: Boolean,
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
    onShowParseReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    val index: LogIndex = source.index
    val recognised: Double = index.recognisedLineRatio

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.sunken)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        // Not decoration: a line the profile cannot account for is a line no query will ever find,
        // so the indicator answers the question it raises rather than only posing it.
        BasicText(
            text = if (recognised >= 1.0) {
                "✓ all ${Formatters.count(index.lineCount)} lines recognised"
            } else {
                "${Formatters.count(index.lineCount - index.unrecognisedLineCount)} / " +
                    "${Formatters.count(index.lineCount)} lines recognised — why?"
            },
            style = LoupeTheme.type.uiSmall.copy(color = if (recognised >= 1.0) colors.accentInk else colors.warn),
            modifier = if (recognised >= 1.0 && !hasProfileProblems) {
                Modifier
            } else {
                Modifier.clickable(onClick = onShowParseReport)
            },
        )
        if (hasProfileProblems) {
            BasicText(
                text = "⚠ a profile failed to load",
                style = LoupeTheme.type.uiSmall.copy(color = colors.error),
                modifier = Modifier.clickable(onClick = onShowParseReport),
            )
        }
        BasicText(
            text = "${Formatters.count(index.continuationLineCount)} folded",
            style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
        )
        if (results != null) {
            BasicText(
                text = if (catchingUp) "filtering…" else "${results.elapsedMillis} ms",
                style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
            )
        }
        if (selectionSize > 1) {
            BasicText(
                text = "${Formatters.count(selectionSize)} selected",
                style = LoupeTheme.type.uiSmall.copy(color = colors.accentInk),
            )
        }
        Spacer(Modifier.weight(1f))
        // A copy leaves no trace on screen, so it has to say so — especially when it was capped.
        if (notice != null) {
            BasicText(
                text = notice,
                style = LoupeTheme.type.uiSmall.copy(color = colors.accentInk),
                modifier = Modifier.padding(end = Spacing.medium),
            )
        }
        ViewModeToggle(viewMode, onViewModeChange)
    }
}

@Composable
private fun ViewModeToggle(mode: ViewMode, onChange: (ViewMode) -> Unit) {
    val colors = LoupeTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, colors.border, RoundedCornerShape(5.dp)),
    ) {
        ViewMode.entries.forEach { candidate ->
            val active: Boolean = candidate == mode
            BasicText(
                text = if (candidate == ViewMode.Columns) "columns" else "raw line",
                style = LoupeTheme.type.uiSmall.copy(color = if (active) colors.onAccent else colors.inkSecondary),
                modifier = Modifier
                    .background(if (active) colors.accent else colors.surface)
                    .clickable { onChange(candidate) }
                    .padding(horizontal = Spacing.small, vertical = 3.dp),
            )
        }
    }
}

/**
 * What the profile could not explain, grouped by shape.
 *
 * The count says the profile is imperfect; the *shape* says which part of it is wrong, and they
 * point at very different fixes. This matters most when writing a new profile, where the first
 * draft is always wrong somewhere and the alternative is guessing.
 */
