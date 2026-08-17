package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.loupe.core.index.LogIndex
import dev.loupe.core.source.EntryRenderer
import dev.loupe.core.source.LogSource
import dev.loupe.desktop.format.Formatters
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing

/**
 * The pane below the list: one entry, in full, with its unfiltered neighbours.
 *
 * Bottom rather than right, deliberately — log lines are wide, and a side panel would truncate every
 * row to make room for one.
 */
/**
 * The selected entry, at the bottom.
 *
 * Bottom rather than right, deliberately: log lines are wide, and a side panel would truncate
 * every row in the list to detail one of them.
 */
@Composable
fun DetailPane(source: LogSource, entry: Int, context: IntRange, onClose: () -> Unit, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LoupeTheme.colors
    val index: LogIndex = source.index
    val raw: String = remember(entry, source) { EntryRenderer.render(source, entry).raw }
    var showContext by remember(entry) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // A definite height, not a maximum: the scrollable body below needs something to fill,
            // and a pane that resizes with each entry makes the list jump under the pointer.
            .height(208.dp)
            .background(colors.surface)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            BasicText("SELECTED ENTRY", style = LoupeTheme.type.label.copy(color = colors.inkTertiary))
            Spacer(Modifier.weight(1f))
            if (!context.isEmpty()) {
                BasicText(
                    text = if (showContext) "entry" else "context ±${(context.last - context.first) / 2}",
                    style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
                    modifier = Modifier.clickable { showContext = !showContext },
                )
            }
            BasicText(
                text = "copy",
                style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
                modifier = Modifier.clickable(onClick = onCopy),
            )
            BasicText(
                text = "close",
                style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
                modifier = Modifier.clickable(onClick = onClose),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.small).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Field("time", Formatters.full(index.timestamps[entry]))
            index.profile.levelDecoder?.let { decoder ->
                val ordinal: Int = index.levels[entry].toInt()
                Field("level", decoder.labels.getOrElse(ordinal) { "?" })
            }
            index.facets.forEachIndexed { facetIndex, facet ->
                val valueId: Int = index.facetValues[facetIndex][entry]
                Field(facet.name, if (valueId == LogIndex.NO_VALUE) "none" else index.facetDictionaries[facetIndex].valueOf(valueId))
            }
            if (index.fileFacetIndex == LogIndex.NO_FACET) {
                Field("file", source.files.first().name)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.sunken)
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.medium),
        ) {
            if (showContext) {
                UnfilteredContext(source = source, focused = entry, context = context)
            } else {
                BasicText(text = raw, style = LoupeTheme.type.mono.copy(color = colors.ink))
            }
        }
    }
}

/**
 * The entries around the selected one, **ignoring the query**.
 *
 * What happened around a line is usually why it happened, and the filter has by definition hidden
 * it — with `level>=E` on screen, this is where the Debug lines that led up to the error are. The
 * focused entry keeps its highlight so it stays findable in the run.
 */
@Composable
private fun UnfilteredContext(source: LogSource, focused: Int, context: IntRange) {
    val colors = LoupeTheme.colors
    Column {
        context.forEach { entry ->
            val ordinal: Int = source.index.levels[entry].toInt()
            val text: String = remember(entry, source) {
                EntryRenderer.render(source, entry).raw.substringBefore('\n')
            }
            BasicText(
                text = text,
                style = LoupeTheme.type.monoSmall.copy(
                    color = if (entry == focused) colors.ink else colors.inkForLevel(ordinal, source.index.profile.levelCount),
                ),
                maxLines = 1,
                modifier = Modifier
                    .background(
                        if (entry ==
                            focused
                        ) {
                            colors.accentSoft
                        } else {
                            colors.surfaceForLevel(ordinal, source.index.profile.levelCount)
                        },
                    )
                    .padding(horizontal = Spacing.tiny),
            )
        }
    }
}

@Composable
private fun Field(name: String, value: String) {
    val colors = LoupeTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.sunken)
            .border(1.dp, colors.border, RoundedCornerShape(4.dp))
            .padding(horizontal = Spacing.small, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        BasicText(name, style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary))
        BasicText(value, style = LoupeTheme.type.monoSmall.copy(color = colors.ink))
    }
}
