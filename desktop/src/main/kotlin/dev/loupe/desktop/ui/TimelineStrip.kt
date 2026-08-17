package dev.loupe.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.time.Instant
import java.time.ZonedDateTime

/**
 * The span of time the list is actually showing, so the strip can mark where you are looking.
 *
 * Timestamps rather than positions in the result: the strip's whole geometry is time, and it would
 * only have to look these two up again.
 */
data class VisibleSpan(val firstMillis: Long, val lastMillis: Long)

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
 *
 * A strip spanning several days is ruled at each midnight and labelled with the date: the hours
 * alone read as one day, and a map that does not say which day is not one. [visibleSpan] marks
 * where the list is within it.
 */
@Composable
fun TimelineStrip(
    source: LogSource,
    results: Results,
    visibleSpan: VisibleSpan?,
    onBrush: (fromMillis: Long?, untilMillis: Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    val index: LogIndex = source.index
    val levelCount: Int = maxOf(index.profile.levelCount, 1)
    val span: Long = index.maxTimestampMillis - index.minTimestampMillis

    var dragStart by remember { mutableStateOf<Float?>(null) }
    var dragEnd by remember { mutableStateOf<Float?>(null) }

    val spansDays: Boolean = remember(index) {
        Formatters.spansMultipleDays(index.minTimestampMillis, index.maxTimestampMillis)
    }

    // Outside the draw lambda for the same reason as `peak` below. Stepped with
    // `ZonedDateTime.plusDays` and not by 86_400_000 ms: a day containing a DST change is not 24
    // hours, so a fixed step would slide off midnight and stay off for the rest of the strip.
    val midnights: List<Float> = remember(source) {
        if (span <= 0L) return@remember emptyList()
        val fractions = mutableListOf<Float>()
        var midnight: ZonedDateTime = Instant.ofEpochMilli(index.minTimestampMillis)
            .atZone(Formatters.zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(Formatters.zone)
        while (midnight.toInstant().toEpochMilli() <= index.maxTimestampMillis) {
            // Past this many they sit closer together than the bars and stop reading as a grid.
            if (fractions.size >= MAX_DAY_GRIDLINES) return@remember emptyList()
            fractions += ((midnight.toInstant().toEpochMilli() - index.minTimestampMillis).toDouble() / span).toFloat()
            midnight = midnight.plusDays(1)
        }
        fractions
    }

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
        // Two draw layers over one gesture area. The viewport cursor moves with every scroll frame
        // and the bars do not, so they are drawn separately: sharing a Canvas would redraw 900
        // buckets by the level count each time the list moved by a row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                // Padding before pointerInput, so the drag maths and the draw scope share an origin.
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
            Canvas(modifier = Modifier.fillMaxSize()) {
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

                // Before the bars, like the band: a dense bar has to stay readable over the rule.
                midnights.forEach { fraction ->
                    drawRect(
                        color = colors.border,
                        topLeft = Offset(fraction * size.width, 0f),
                        size = Size(1f, size.height),
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

            // Where the list is: a rail along the top edge, which is the one place the bars almost
            // never reach. Along the bottom it would sit across the base of every bar, and full
            // height would be a third thing competing with the band and its dividers.
            if (visibleSpan != null && span > 0L) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val low: Float = ((visibleSpan.firstMillis - index.minTimestampMillis).toDouble() / span)
                        .toFloat().coerceIn(0f, 1f) * size.width
                    val high: Float = ((visibleSpan.lastMillis - index.minTimestampMillis).toDouble() / span)
                        .toFloat().coerceIn(0f, 1f) * size.width
                    drawRect(
                        color = colors.accentInk,
                        topLeft = Offset(low, 0f),
                        // Forty rows out of nine million is far under a pixel wide, and a cursor you
                        // cannot see is not one.
                        size = Size(maxOf(high - low, MINIMUM_CURSOR_PIXELS), CURSOR_THICKNESS_PIXELS),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.tiny),
        ) {
            BasicText(
                text = endLabel(index.minTimestampMillis, spansDays),
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
                text = endLabel(index.maxTimestampMillis, spansDays),
                style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
            )
        }
    }
}

/** The date only earns the width when the strip crosses midnight; otherwise the hours say it all. */
private fun endLabel(millis: Long, spansDays: Boolean): String = if (spansDays) Formatters.dayMinute(millis) else Formatters.minute(millis)

private const val MINIMUM_BRUSH_PIXELS = 4f

/** Beyond a month and a half of them a hairline per day is noise, not a grid. */
private const val MAX_DAY_GRIDLINES = 40

private const val CURSOR_THICKNESS_PIXELS = 2.5f
private const val MINIMUM_CURSOR_PIXELS = 3f
