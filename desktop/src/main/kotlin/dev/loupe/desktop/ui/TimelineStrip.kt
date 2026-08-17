package dev.loupe.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.loupe.core.index.LogIndex
import dev.loupe.core.source.LogSource
import dev.loupe.desktop.format.Formatters
import dev.loupe.desktop.state.Results
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing

/**
 * The density strip above the list, which is a filter rather than a decoration.
 *
 * Brushing it writes `since:`/`until:` into the query, and the bars keep their shape while a range is
 * selected: emptying them would hide the very comparison the selection was made to see.
 */
/**
 * Density over time, brushable.
 *
 * A filter, not a decoration: dragging writes `since:` / `until:` into the query, so the bar always
 * explains the picture. Clicking anywhere clears the range.
 *
 * The bars are drawn **with the time window lifted** — the strip shows the whole file's shape, and
 * the selection is marked on top of it with a tinted band and two divider lines. Emptying the bars
 * outside the range instead would show the answer where the question belongs, and take away the
 * context that makes brushing worth doing.
 */
@Composable
fun TimelineStrip(
    source: LogSource,
    results: Results,
    onBrush: (fromMillis: Long?, untilMillis: Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    val index: LogIndex = source.index
    val levelCount: Int = maxOf(index.profile.levelCount, 1)
    val span: Long = index.maxTimestampMillis - index.minTimestampMillis

    var dragStart by remember { mutableStateOf<Float?>(null) }
    var dragEnd by remember { mutableStateOf<Float?>(null) }

    // Outside the draw lambda, and keyed on the result: this walks 900 buckets by the level count,
    // and inside the Canvas it ran on every frame — including every frame of a drag.
    val peak: Int = remember(results) {
        val buckets: Array<IntArray> = results.histogram
        val bucketCount: Int = buckets.firstOrNull()?.size ?: 0
        (0 until bucketCount)
            .maxOfOrNull { bucket -> (0 until levelCount).sumOf { ordinal -> buckets[ordinal][bucket] } }
            ?.coerceAtLeast(1)
            ?: 1
    }

    Column(modifier = modifier.fillMaxWidth().background(colors.surface)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = Spacing.medium, vertical = Spacing.small)
                .pointerInput(source) {
                    detectTapGestures { onBrush(null, null) }
                }
                .pointerInput(source) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset.x
                            dragEnd = offset.x
                        },
                        onDragEnd = {
                            val from: Float? = dragStart
                            val to: Float? = dragEnd
                            dragStart = null
                            dragEnd = null
                            if (from == null || to == null) return@detectDragGestures
                            if (kotlin.math.abs(to - from) < MINIMUM_BRUSH_PIXELS) {
                                onBrush(null, null)
                                return@detectDragGestures
                            }
                            val low: Float = (minOf(from, to) / size.width).coerceIn(0f, 1f)
                            val high: Float = (maxOf(from, to) / size.width).coerceIn(0f, 1f)
                            onBrush(
                                index.minTimestampMillis + (low * span).toLong(),
                                index.minTimestampMillis + (high * span).toLong(),
                            )
                        },
                        onDragCancel = {
                            dragStart = null
                            dragEnd = null
                        },
                        onDrag = { change, _ -> dragEnd = change.position.x },
                    )
                },
        ) {
            // The band goes down first so the bars stay legible on top of it.
            val live: ClosedFloatingPointRange<Float>? = dragStart?.let { from ->
                dragEnd?.let { to -> minOf(from, to)..maxOf(from, to) }
            }
            val settled: ClosedFloatingPointRange<Float>? = when {
                live != null -> null
                span <= 0L -> null
                results.windowSinceMillis == null && results.windowUntilMillis == null -> null
                else -> {
                    val from: Long = results.windowSinceMillis ?: index.minTimestampMillis
                    val to: Long = results.windowUntilMillis ?: index.maxTimestampMillis
                    val low: Float = ((from - index.minTimestampMillis).toDouble() / span).toFloat().coerceIn(0f, 1f)
                    val high: Float = ((to - index.minTimestampMillis).toDouble() / span).toFloat().coerceIn(0f, 1f)
                    (low * size.width)..(high * size.width)
                }
            }
            val band: ClosedFloatingPointRange<Float>? = live ?: settled
            if (band != null) {
                drawRect(
                    color = colors.accent.copy(alpha = 0.13f),
                    topLeft = Offset(band.start, 0f),
                    size = Size(band.endInclusive - band.start, size.height),
                )
            }

            val buckets: Array<IntArray> = results.histogram
            val bucketCount: Int = buckets.firstOrNull()?.size ?: return@Canvas
            val barWidth: Float = size.width / bucketCount
            for (bucket in 0 until bucketCount) {
                var drawn = 0f
                for (ordinal in levelCount - 1 downTo 0) {
                    val count: Int = buckets[ordinal][bucket]
                    if (count == 0) continue
                    val height: Float = (count.toFloat() / peak) * size.height
                    drawRect(
                        color = colors.barForLevel(ordinal, levelCount),
                        topLeft = Offset(bucket * barWidth, size.height - drawn - height),
                        size = Size(maxOf(barWidth - 0.5f, 0.5f), height),
                    )
                    drawn += height
                }
            }

            // Dividers last, so the edges of the selection stay readable over a dense bar.
            if (band != null) {
                listOf(band.start, band.endInclusive).forEach { edge ->
                    drawRect(
                        color = colors.accent,
                        topLeft = Offset(edge - 0.75f, 0f),
                        size = Size(1.5f, size.height),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.tiny),
        ) {
            BasicText(
                text = Formatters.minute(index.minTimestampMillis),
                style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                text = if (results.windowSinceMillis != null || results.windowUntilMillis != null) {
                    "click to clear"
                } else {
                    "drag to bound"
                },
                style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                text = Formatters.minute(index.maxTimestampMillis),
                style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
            )
        }
    }
}

private const val MINIMUM_BRUSH_PIXELS = 4f
