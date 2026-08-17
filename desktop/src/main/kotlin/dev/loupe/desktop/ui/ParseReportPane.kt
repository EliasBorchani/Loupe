package dev.loupe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.loupe.core.index.UnrecognisedReport
import dev.loupe.desktop.format.Formatters
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing

/**
 * The pane that answers "what are those unrecognised lines?".
 *
 * Built because the tool could not answer its own diagnostic question: it reported a ratio and left
 * the user to go and find out why.
 */
@Composable
fun ParseReportPane(
    report: UnrecognisedReport,
    totalLines: Long,
    profileProblems: List<String>,
    fileNameOf: (Int) -> String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(208.dp)
            .background(colors.surface)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("UNRECOGNISED LINES", style = LoupeTheme.type.label.copy(color = colors.inkTertiary))
            Spacer(Modifier.weight(1f))
            BasicText(
                text = "close",
                style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
                modifier = Modifier.clickable(onClick = onClose),
            )
        }
        BasicText(
            text = "${Formatters.count(report.total)} of ${Formatters.count(totalLines)} lines are neither " +
                "an entry, a continuation, nor a declared marker — so nothing will ever find them.",
            style = LoupeTheme.type.uiSmall.copy(color = colors.inkSecondary),
            modifier = Modifier.padding(vertical = Spacing.small),
        )

        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            // A profile that would not load is a likelier explanation than anything below it.
            if (profileProblems.isNotEmpty()) {
                BasicText(
                    text = "${profileProblems.size} profile(s) in ~/.loupe/profiles/ failed to load:",
                    style = LoupeTheme.type.uiSmall.copy(color = colors.error, fontWeight = FontWeight.SemiBold),
                )
                profileProblems.forEach { problem ->
                    BasicText(
                        text = problem,
                        style = LoupeTheme.type.monoSmall.copy(color = colors.error),
                        modifier = Modifier.padding(bottom = Spacing.tiny),
                    )
                }
            }
            report.kindsByCount().forEach { kind ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.small),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    BasicText(
                        text = Formatters.count(report.countOf(kind)),
                        style = LoupeTheme.type.monoSmall.copy(color = colors.warn, fontWeight = FontWeight.Bold),
                    )
                    Column {
                        BasicText(
                            text = kind.label,
                            style = LoupeTheme.type.uiSmall.copy(color = colors.ink, fontWeight = FontWeight.SemiBold),
                        )
                        BasicText(
                            text = kind.meaning,
                            style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
                        )
                        report.samplesOf(kind).forEach { sample ->
                            Row(
                                modifier = Modifier.padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                            ) {
                                BasicText(
                                    text = "${fileNameOf(sample.fileId)}:${sample.lineNumber}",
                                    style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
                                )
                                BasicText(
                                    text = if (sample.text.isEmpty()) "⟨empty⟩" else sample.text,
                                    style = LoupeTheme.type.monoSmall.copy(color = colors.inkSecondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
