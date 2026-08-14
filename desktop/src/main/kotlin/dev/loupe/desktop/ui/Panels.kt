package dev.loupe.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.ValueDictionary
import dev.loupe.core.profile.CompiledFacet
import dev.loupe.core.profile.FacetMode
import dev.loupe.core.query.QueryEdits
import dev.loupe.core.source.EntryRenderer
import dev.loupe.core.source.LogSource
import dev.loupe.desktop.state.LEVEL_FIELD
import dev.loupe.desktop.state.Results
import dev.loupe.desktop.state.ViewMode
import dev.loupe.desktop.theme.LoupeTheme
import dev.loupe.desktop.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val COUNT_FORMAT: java.text.NumberFormat = java.text.NumberFormat.getIntegerInstance(Locale.getDefault())
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

// ─── Query bar ───────────────────────────────────────────────────────────────────────────────

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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BasicText(
                text = "${COUNT_FORMAT.format(matchCount)} / ${COUNT_FORMAT.format(totalCount)}",
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

// ─── Facet sidebar ───────────────────────────────────────────────────────────────────────────

/**
 * Facets with their counts and a volume bar, so the sidebar doubles as the shape of the file.
 *
 * Each count is what you would get **by clicking that value** — its own constraint lifted — which
 * is the only reading of the number that helps you decide where to go next. Counting over the
 * current result set instead would show every other category at zero the moment you pick one.
 */
@Composable
fun FacetSidebar(
    source: LogSource,
    results: Results,
    query: String,
    onToggleValue: (field: String, value: String) -> Unit,
    onClearField: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    val index: LogIndex = source.index
    val levelOrder: List<String> = index.profile.levelDecoder?.order.orEmpty()
    val levelLabels: List<String> = index.profile.levelDecoder?.labels.orEmpty()

    Column(
        modifier = modifier
            .background(colors.sunken)
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.large),
    ) {
        if (levelOrder.isNotEmpty()) {
            val selected: Set<String> = remember(query) { QueryEdits.selectedValues(query, LEVEL_FIELD, levelOrder) }
            FacetGroup(
                title = "Level",
                hasSelection = selected.isNotEmpty(),
                onClear = { onClearField(LEVEL_FIELD) },
            ) {
                val peak: Int = results.counts.levels.maxOrNull() ?: 0
                levelOrder.forEachIndexed { ordinal, symbol ->
                    FacetRow(
                        label = levelLabels.getOrElse(ordinal) { symbol },
                        count = results.counts.levels.getOrElse(ordinal) { 0 },
                        peak = peak,
                        selected = selected.any { value -> value.equals(symbol, ignoreCase = true) },
                        accent = colors.inkForLevel(ordinal, levelOrder.size),
                        onClick = { onToggleValue(LEVEL_FIELD, symbol) },
                    )
                }
            }
        }

        index.facets.forEachIndexed { facetIndex, facet ->
            FacetValues(
                facet = facet,
                dictionary = index.facetDictionaries[facetIndex],
                counts = results.counts.facets.getOrNull(facetIndex) ?: IntArray(0),
                query = query,
                onToggleValue = onToggleValue,
                onClearField = onClearField,
            )
        }
    }
}

@Composable
private fun FacetValues(
    facet: CompiledFacet,
    dictionary: ValueDictionary,
    counts: IntArray,
    query: String,
    onToggleValue: (String, String) -> Unit,
    onClearField: (String) -> Unit,
) {
    val colors = LoupeTheme.colors
    val selected: Set<String> = remember(query, facet.name) { QueryEdits.selectedValues(query, facet.name) }
    var search by remember(facet.name) { mutableStateOf("") }

    // A release build's tag facet has 817 distinct values, most of them R8 noise like "ou1".
    // A flat list is unusable, so anything that big gets top-N plus a search box.
    val searchable: Boolean = facet.mode == FacetMode.Auto && dictionary.size > TOP_N

    val ordered: List<Int> = remember(query, search, facet.name, counts) {
        dictionary.idsByDescendingCount()
            .filter { id -> counts.getOrElse(id) { 0 } > 0 || dictionary.valueOf(id) in selected }
            .filter { id -> search.isEmpty() || dictionary.valueOf(id).contains(search, ignoreCase = true) }
            .toList()
    }
    val shown: List<Int> = if (searchable && search.isEmpty()) ordered.take(TOP_N) else ordered

    FacetGroup(
        title = if (dictionary.size > TOP_N) "${facet.label} · ${dictionary.size}" else facet.label,
        hasSelection = selected.isNotEmpty(),
        onClear = { onClearField(facet.name) },
    ) {
        if (searchable) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.tiny, vertical = Spacing.tiny)
                    .clip(RoundedCornerShape(5.dp))
                    .background(LoupeTheme.colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(5.dp))
                    .padding(horizontal = Spacing.small, vertical = 3.dp),
            ) {
                if (search.isEmpty()) {
                    BasicText("search…", style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary))
                }
                BasicTextField(
                    value = search,
                    onValueChange = { typed -> search = typed },
                    singleLine = true,
                    textStyle = LoupeTheme.type.uiSmall.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val peak: Int = shown.maxOfOrNull { id -> counts.getOrElse(id) { 0 } } ?: 0
        shown.forEach { id ->
            FacetRow(
                label = dictionary.valueOf(id),
                count = counts.getOrElse(id) { 0 },
                peak = peak,
                selected = selected.any { value -> value.equals(dictionary.valueOf(id), ignoreCase = true) },
                accent = colors.accent,
                onClick = { onToggleValue(facet.name, dictionary.valueOf(id)) },
            )
        }
        if (ordered.size > shown.size) {
            BasicText(
                text = "+ ${ordered.size - shown.size} more",
                style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
                modifier = Modifier.padding(start = Spacing.small, top = Spacing.tiny),
            )
        }
    }
}

private const val TOP_N = 8

@Composable
private fun FacetGroup(
    title: String,
    hasSelection: Boolean,
    onClear: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LoupeTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.small, vertical = Spacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.tiny, vertical = Spacing.tiny),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text = title.uppercase(Locale.getDefault()), style = LoupeTheme.type.label.copy(color = colors.inkTertiary))
            Spacer(Modifier.weight(1f))
            if (hasSelection) {
                BasicText(
                    text = "clear",
                    style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
                    modifier = Modifier.clickable(onClick = onClear),
                )
            }
        }
        content()
    }
}

@Composable
private fun FacetRow(
    label: String,
    count: Int,
    peak: Int,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val colors = LoupeTheme.colors
    Box(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // The volume bar sits behind the label rather than beside it: the sidebar is a
        // distribution as much as a set of controls, and a separate column would cost width.
        if (peak > 0 && count > 0) {
            Canvas(modifier = Modifier.fillMaxWidth().height(Spacing.rowHeight)) {
                val fraction: Float = (count.toDouble() / peak).toFloat().coerceIn(0f, 1f)
                drawRect(
                    color = colors.accent.copy(alpha = 0.14f),
                    size = Size(size.width * fraction, size.height),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.small, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Box(
                modifier = Modifier
                    .width(11.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (selected) accent else colors.surface)
                    .border(1.dp, if (selected) accent else colors.borderStrong, RoundedCornerShape(3.dp)),
            )
            BasicText(
                text = label,
                style = LoupeTheme.type.uiSmall.copy(
                    color = if (selected) colors.ink else colors.inkSecondary,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = COUNT_FORMAT.format(count),
                style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
                maxLines = 1,
            )
        }
    }
}

// ─── Timeline ────────────────────────────────────────────────────────────────────────────────

/**
 * Density over time, brushable.
 *
 * A filter, not a decoration: dragging writes `since:` / `until:` into the query, so the bar always
 * explains the picture. Buckets span the whole file even when the query has narrowed it, because a
 * map that rescales under you is not a map.
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
    val zone: ZoneId = remember { ZoneId.systemDefault() }
    val levelCount: Int = maxOf(index.profile.levelCount, 1)

    var dragStart by remember { mutableStateOf<Float?>(null) }
    var dragEnd by remember { mutableStateOf<Float?>(null) }

    Column(modifier = modifier.fillMaxWidth().background(colors.surface)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = Spacing.medium, vertical = Spacing.small)
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
                            // A stray click clears the window rather than selecting a millisecond.
                            if (kotlin.math.abs(to - from) < MINIMUM_BRUSH_PIXELS) {
                                onBrush(null, null)
                                return@detectDragGestures
                            }
                            val span: Long = index.maxTimestampMillis - index.minTimestampMillis
                            val low: Float = minOf(from, to) / size.width
                            val high: Float = maxOf(from, to) / size.width
                            onBrush(
                                index.minTimestampMillis + (low.coerceIn(0f, 1f) * span).toLong(),
                                index.minTimestampMillis + (high.coerceIn(0f, 1f) * span).toLong(),
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
            val buckets: Array<IntArray> = results.histogram
            val bucketCount: Int = buckets.firstOrNull()?.size ?: return@Canvas
            val peak: Int = (0 until bucketCount).maxOf { bucket ->
                (0 until levelCount).sumOf { ordinal -> buckets[ordinal][bucket] }
            }.coerceAtLeast(1)

            val barWidth: Float = size.width / bucketCount
            for (bucket in 0 until bucketCount) {
                var drawn = 0f
                for (ordinal in levelCount - 1 downTo 0) {
                    val count: Int = buckets[ordinal][bucket]
                    if (count == 0) continue
                    val height: Float = (count.toFloat() / peak) * size.height
                    drawRect(
                        color = barColour(ordinal, levelCount, colors.error, colors.warn, colors.accent),
                        topLeft = Offset(bucket * barWidth, size.height - drawn - height),
                        size = Size(maxOf(barWidth - 0.5f, 0.5f), height),
                    )
                    drawn += height
                }
            }

            val from: Float? = dragStart
            val to: Float? = dragEnd
            if (from != null && to != null) {
                drawRect(
                    color = colors.accent.copy(alpha = 0.18f),
                    topLeft = Offset(minOf(from, to), 0f),
                    size = Size(kotlin.math.abs(to - from), size.height),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.tiny),
        ) {
            BasicText(
                text = CLOCK.format(Instant.ofEpochMilli(index.minTimestampMillis).atZone(zone)),
                style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                text = "drag to bound",
                style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                text = CLOCK.format(Instant.ofEpochMilli(index.maxTimestampMillis).atZone(zone)),
                style = LoupeTheme.type.monoSmall.copy(color = colors.inkTertiary),
            )
        }
    }
}

private const val MINIMUM_BRUSH_PIXELS = 4f

private fun barColour(ordinal: Int, levelCount: Int, error: Color, warn: Color, accent: Color): Color = when {
    ordinal == levelCount - 1 -> error
    ordinal == levelCount - 2 -> warn
    ordinal <= 1 -> accent.copy(alpha = 0.28f)
    else -> accent.copy(alpha = 0.55f)
}

// ─── Detail pane ─────────────────────────────────────────────────────────────────────────────

/**
 * The selected entry, at the bottom.
 *
 * Bottom rather than right, deliberately: log lines are wide, and a side panel would truncate
 * every row in the list to detail one of them.
 */
@Composable
fun DetailPane(
    source: LogSource,
    entry: Int,
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoupeTheme.colors
    val index: LogIndex = source.index
    val zone: ZoneId = remember { ZoneId.systemDefault() }
    val raw: String = remember(entry, source) { EntryRenderer.render(index, source.text, entry).raw }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .background(colors.surface)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            BasicText("SELECTED ENTRY", style = LoupeTheme.type.label.copy(color = colors.inkTertiary))
            Spacer(Modifier.weight(1f))
            BasicText(
                text = "copy",
                style = LoupeTheme.type.uiSmall.copy(color = colors.accent),
                modifier = Modifier.clickable { onCopy(raw) },
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
            Field("time", STAMP.format(Instant.ofEpochMilli(index.timestamps[entry]).atZone(zone)))
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
                .clip(RoundedCornerShape(6.dp))
                .background(colors.sunken)
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.medium),
        ) {
            BasicText(text = raw, style = LoupeTheme.type.mono.copy(color = colors.ink))
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

// ─── Status bar ──────────────────────────────────────────────────────────────────────────────

@Composable
fun StatusBar(
    source: LogSource,
    results: Results?,
    catchingUp: Boolean,
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
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
        BasicText(
            text = if (recognised >= 1.0) {
                "✓ all ${COUNT_FORMAT.format(index.lineCount)} lines recognised"
            } else {
                "${COUNT_FORMAT.format(index.lineCount - index.unrecognisedLineCount)} / " +
                    "${COUNT_FORMAT.format(index.lineCount)} lines recognised"
            },
            style = LoupeTheme.type.uiSmall.copy(color = if (recognised >= 1.0) colors.accentInk else colors.warn),
        )
        BasicText(
            text = "${COUNT_FORMAT.format(index.continuationLineCount)} folded",
            style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
        )
        if (results != null) {
            BasicText(
                text = if (catchingUp) "filtering…" else "${results.elapsedMillis} ms",
                style = LoupeTheme.type.uiSmall.copy(color = colors.inkTertiary),
            )
        }
        Spacer(Modifier.weight(1f))
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

// ─── Chrome ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun SourceHeader(source: LogSource, onOpen: () -> Unit, modifier: Modifier = Modifier) {
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
            text = "${COUNT_FORMAT.format(source.index.entryCount)} entries · ${source.elapsedMillis} ms",
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
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(LoupeTheme.colors.border))
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxHeight().width(1.dp).background(LoupeTheme.colors.border))
}
