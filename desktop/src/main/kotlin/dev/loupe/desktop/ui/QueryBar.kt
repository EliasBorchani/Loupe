package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.loupe.desktop.format.Formatters
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing

/**
 * The query bar.
 *
 * Every facet click writes into this text rather than into a hidden selection model, which is how
 * the syntax gets learned without anyone reading its documentation.
 */
/**
 * The query bar, and the one place the app's state actually lives.
 *
 * Facet clicks write here rather than into a parallel selection model, which is how someone learns
 * `level>=W` without reading a grammar: they tick a box and watch the words appear.
 */
@Composable
fun QueryBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    totalCount: Int,
    problems: List<String>,
    catchingUp: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.sunken)
                    .border(1.dp, if (problems.isEmpty()) colors.border else colors.error, RoundedCornerShape(6.dp))
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            ) {
                if (query.isEmpty()) {
                    BasicText(
                        text = "level>=W  category:Sync  since:-2h  \"timeout\"",
                        style = LoupeTheme.type.mono.copy(color = colors.inkTertiary),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LoupeTheme.type.mono.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            BasicText(
                text = "${Formatters.count(matchCount)} / ${Formatters.count(totalCount)}",
                style = LoupeTheme.type.mono.copy(color = if (catchingUp) colors.inkTertiary else colors.inkSecondary),
            )
        }
        // A typo is reported, never answered with a silently empty screen.
        problems.take(2).forEach { problem ->
            BasicText(
                text = problem,
                style = LoupeTheme.type.uiSmall.copy(color = colors.error),
                modifier = Modifier.padding(top = Spacing.tiny),
            )
        }
    }
}
