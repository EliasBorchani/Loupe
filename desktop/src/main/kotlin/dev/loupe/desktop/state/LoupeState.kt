package dev.loupe.desktop.state

import dev.loupe.core.index.EntryFilter
import dev.loupe.core.index.FacetCounts
import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.SidebarCounts
import dev.loupe.core.query.CompiledQuery
import dev.loupe.core.query.QueryCompiler
import dev.loupe.core.query.QueryEdits
import dev.loupe.core.source.EntryExport
import dev.loupe.core.source.LogSource
import dev.loupe.core.profile.ProfileRegistry
import dev.loupe.core.source.LogSourceLoader
import dev.loupe.core.source.OpenPhase
import dev.loupe.desktop.format.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ViewMode { Columns, Raw }

sealed interface OpenStatus {
    data object Idle : OpenStatus
    class Working(val phase: OpenPhase, val bytesDone: Long, val bytesTotal: Long) : OpenStatus {
        val fraction: Float get() = if (bytesTotal <= 0L) 0f else (bytesDone.toDouble() / bytesTotal).toFloat()
    }
    class Failed(val message: String) : OpenStatus
}

/**
 * A contiguous run of selected rows, held as **positions in the result** rather than entry indices.
 *
 * A range, not a set, because that is what the gestures produce: click, shift-click, extend with an
 * arrow. And positions rather than entries because "everything between these two" means everything
 * between them *in what is on screen* — with a filter active, the entries in the gap are not part
 * of the selection and must not be copied.
 *
 * [anchor] is where the selection started and stays put; [focus] is the end that moves, and is the
 * row the detail pane describes.
 */
class Selection(val anchor: Int, val focus: Int) {

    val first: Int get() = minOf(anchor, focus)
    val last: Int get() = maxOf(anchor, focus)
    val size: Int get() = last - first + 1

    operator fun contains(position: Int): Boolean = position in first..last

    fun coercedTo(matchCount: Int): Selection? {
        if (matchCount <= 0) return null
        val limit: Int = matchCount - 1
        return Selection(anchor.coerceIn(0, limit), focus.coerceIn(0, limit))
    }
}

/**
 * What the screen shows for one query.
 *
 * Not a data class, on purpose: [matches] is an `IntArray` whose structural equality would compare
 * nine million ints on every recomposition. Identity equality is both correct and free here —
 * a new instance means new results.
 */
class Results(
    val query: String,
    val matches: IntArray,
    val matchCount: Int,
    val problems: List<String>,
    val counts: SidebarCounts,
    /** The brushed range, or `null` on each side that is unbounded. Drawn as a band, not applied. */
    val windowSinceMillis: Long?,
    val windowUntilMillis: Long?,
    val elapsedMillis: Long,
) {
    val histogram: Array<IntArray> get() = counts.timeline

    /**
     * Where [entry] sits in the result, or `-1` if the query no longer selects it.
     *
     * A binary search, which is only valid because [matches] is ascending — both the sequential
     * and the parallel evaluation preserve entry order, the latter by compacting its workers'
     * slices in order rather than as they finish.
     */
    fun positionOf(entry: Int): Int {
        var low = 0
        var high = matchCount - 1
        while (low <= high) {
            val middle: Int = (low + high) ushr 1
            val candidate: Int = matches[middle]
            when {
                candidate < entry -> low = middle + 1
                candidate > entry -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }
}

/**
 * The screen's state, and the only place that decides what runs off the UI thread.
 *
 * The shape is the one from `LogViewerViewModel` in the Android app, which was worth keeping:
 * inputs are `StateFlow`s, results are a `combine` of them, and **the result carries the query it
 * was computed for**. That last part is what makes the "catching up" indicator honest — the UI can
 * tell that what it is showing is behind what was asked, rather than guessing from a boolean
 * somebody forgot to clear.
 */
class LoupeState(private val scope: CoroutineScope) {

    companion object {
        /** Long enough to skip the intermediate states of typing, short enough to feel immediate. */
        private const val QUERY_DEBOUNCE_MILLIS = 180L

    }

    private val _source = MutableStateFlow<LogSource?>(null)
    val source: StateFlow<LogSource?> = _source.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _status = MutableStateFlow<OpenStatus>(OpenStatus.Idle)
    val status: StateFlow<OpenStatus> = _status.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.Columns)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    /** Transient feedback for an action that has no visible result of its own — a copy, an export. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** The bottom slot holds one thing at a time: the selected entry, or the parse report. */
    private val _showParseReport = MutableStateFlow(false)
    val showParseReport: StateFlow<Boolean> = _showParseReport.asStateFlow()

    private val _expandedEntries = MutableStateFlow<Set<Int>>(emptySet())
    val expandedEntries: StateFlow<Set<Int>> = _expandedEntries.asStateFlow()

    private var openJob: Job? = null

    /**
     * Filtering runs on [Dispatchers.Default] and only after typing pauses. A cleared query skips
     * the debounce so wiping the box snaps back to the whole file instantly.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    val results: StateFlow<Results?> =
        combine(
            _source,
            _query.debounce { typed -> if (typed.isEmpty()) 0L else QUERY_DEBOUNCE_MILLIS },
        ) { source, query -> source to query }
            .mapLatest { (source, query) -> if (source == null) null else compute(source, query) }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** True while the displayed results are behind what the user has asked for. */
    fun isCatchingUp(results: Results?): Boolean =
        _source.value != null && (results == null || results.query != _query.value)

    fun open(paths: List<File>) {
        openJob?.cancel()
        openJob = scope.launch {
            _status.value = OpenStatus.Working(OpenPhase.Detecting, 0, 0)
            _selection.value = null
            _showParseReport.value = false
            _expandedEntries.value = emptySet()
            try {
                val opened: LogSource = withContext(Dispatchers.IO) {
                    // Re-read on every open, so editing a profile needs no restart — which is the
                    // whole workflow when writing one for a format nobody has described yet.
                    LogSourceLoader.open(paths, ProfileRegistry.bundledPlusUser()) { phase, done, total ->
                        _status.value = OpenStatus.Working(phase, done, total)
                    }
                }
                _source.value?.close()
                _source.value = opened
                _status.value = OpenStatus.Idle
            } catch (failure: Exception) {
                _status.value = OpenStatus.Failed(failure.message ?: failure.toString())
            }
        }
    }

    /**
     * Re-opens with [paths] **added** to what is already open.
     *
     * A drop or the open dialog replaces, the way every document app does. This is the other half:
     * once the model is "a set of files viewed as one stream", adding yesterday's archive to
     * today's folder has to be expressible, and the `file` facet is already there to separate them
     * again afterwards.
     */
    fun add(paths: List<File>) {
        val existing: List<File> = _source.value?.files.orEmpty()
        open(existing + paths.filterNot { path -> path in existing })
    }

    fun setQuery(query: String) {
        _query.value = query
        // Positions mean nothing once the result changes under them.
        _selection.value = null
        _notice.value = null
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
    }

    fun showParseReport(show: Boolean) {
        _showParseReport.value = show
    }

    /** A plain click: one row, and a new anchor. */
    fun selectAt(position: Int) {
        _selection.value = Selection(position, position)
        _showParseReport.value = false
    }

    /** Shift-click: keep the anchor, move the far end. With nothing selected, behaves like a click. */
    fun extendTo(position: Int) {
        val current: Selection? = _selection.value
        _selection.value = if (current == null) Selection(position, position) else Selection(current.anchor, position)
    }

    fun clearSelection() {
        _selection.value = null
    }

    fun selectAll() {
        val matchCount: Int = results.value?.matchCount ?: return
        if (matchCount > 0) _selection.value = Selection(0, matchCount - 1)
    }

    /**
     * Moves the focus [delta] rows through the **result**, not through the index.
     *
     * With nothing selected yet, a step down starts at the top and a step up at the bottom, so the
     * first arrow press always lands somewhere useful. At either end it stops rather than wrapping:
     * in a list of nine million, silently teleporting to the far end is never what was meant.
     *
     * @param extend keep the anchor where it is, so shift-arrow grows the selection.
     */
    fun moveSelection(delta: Int, extend: Boolean = false) {
        val current: Results = results.value ?: return
        if (current.matchCount == 0) return

        val existing: Selection? = _selection.value?.coercedTo(current.matchCount)
        if (existing == null) {
            val start: Int = if (delta > 0) 0 else current.matchCount - 1
            _selection.value = Selection(start, start)
            return
        }
        val focus: Int = (existing.focus + delta).coerceIn(0, current.matchCount - 1)
        _selection.value = if (extend) Selection(existing.anchor, focus) else Selection(focus, focus)
    }

    /**
     * The selected entries' raw text, newline-joined, capped.
     *
     * Capped because select-all on nine million entries is gigabytes, and the clipboard is for
     * pasting into a ticket. Export writes the whole thing. The cap is reported rather than applied
     * silently — a truncated paste that says nothing is how a bug report ends up missing its cause.
     *
     * Suspending because it is not free: up to [CLIPBOARD_MAX_ENTRIES] entries are decoded out of
     * mmapped text and joined into one string, and it used to do that on the caller's thread — which
     * is Compose's. A ⌘A followed by a copy froze the window for as long as it took.
     *
     * @param maxEntries seam for the test; defaults to [CLIPBOARD_MAX_ENTRIES].
     * @return the text, or `null` when nothing is selected.
     */
    suspend fun copySelection(maxEntries: Int = CLIPBOARD_MAX_ENTRIES): String? {
        val source: LogSource = _source.value ?: return null
        val current: Results = results.value ?: return null
        val selection: Selection = _selection.value?.coercedTo(current.matchCount) ?: return null

        val total: Int = selection.size
        val taken: Int = minOf(total, maxEntries)
        val text: String = withContext(Dispatchers.IO) {
            (0 until taken).joinToString("\n") { offset -> source.rawText(current.matches[selection.first + offset]) }
        }

        _notice.value = if (taken < total) {
            "Copied $taken of $total entries — use Export for all of them"
        } else {
            "Copied $taken ${if (taken == 1) "entry" else "entries"}"
        }
        return text
    }

    fun clearNotice() {
        _notice.value = null
    }

    /**
     * Writes the whole current result to [target].
     *
     * The **result**, not the selection: "export the current filter" is what the button says, and
     * narrowing further is what the query bar is for. Uncapped, off the UI thread, and it reports
     * the count — an export that silently wrote a subset would be worse than no export.
     */
    fun export(target: File) {
        val source: LogSource = _source.value ?: return
        val current: Results = results.value ?: return
        scope.launch {
            _notice.value = "Exporting ${current.matchCount} entries…"
            try {
                val written: Int = withContext(Dispatchers.IO) {
                    EntryExport.write(source, current.matches, current.matchCount, target)
                }
                _notice.value = "Exported $written entries to ${target.name}"
            } catch (failure: Exception) {
                _notice.value = "Export failed: ${failure.message ?: failure.toString()}"
            }
        }
    }

    /**
     * The entries around the focused row, ignoring the filter.
     *
     * What happened around a line is usually why it happened, and the
     * query has by definition hidden it.
     */
    fun contextAround(entry: Int, radius: Int = CONTEXT_RADIUS): IntRange =
        _source.value?.index?.neighbourhood(entry, radius) ?: IntRange.EMPTY

    fun toggleExpanded(entry: Int) {
        _expandedEntries.value = _expandedEntries.value.let { open ->
            if (entry in open) open - entry else open + entry
        }
    }

    /** A facet click edits the query text — see [QueryEdits] for why that is the whole design. */
    fun toggleFacetValue(field: String, value: String) {
        val order: List<String>? = _source.value?.profile?.levelDecoder?.order
        setQuery(QueryEdits.toggleFacetValue(_query.value, field, value, order.takeIf { field == LEVEL_FIELD }))
    }

    fun clearField(field: String) {
        setQuery(QueryEdits.clearField(_query.value, field))
    }

    /**
     * A timeline brush writes `since:` / `until:`, so the bar always explains the picture.
     *
     * The bounds are absolute instants and not times of day: see [Formatters.queryInstant] for what
     * a bare `HH:mm:ss` did to a folder spanning more than one day.
     */
    fun setTimeWindow(sinceMillis: Long?, untilMillis: Long?) {
        setQuery(
            QueryEdits.setTimeWindow(
                query = _query.value,
                since = sinceMillis?.let { millis -> Formatters.queryInstant(millis) },
                until = untilMillis?.let { millis -> Formatters.queryInstant(millis) },
            ),
        )
    }

    fun close() {
        openJob?.cancel()
        _source.value?.close()
        _source.value = null
        _query.value = ""
        _status.value = OpenStatus.Idle
    }

    private fun compute(source: LogSource, query: String): Results {
        val startedAt: Long = System.nanoTime()
        val index: LogIndex = source.index
        val compiled: CompiledQuery = QueryCompiler(index).compile(query)
        // A query with a typo in it still filters on the parts that did parse: the user is mid-word
        // and an empty screen would be a worse answer than a partial one.
        val filter: EntryFilter = compiled.filter

        val destination = IntArray(index.entryCount)
        val matchCount: Int = filter.evaluateParallel(index, source.text, destination)

        return Results(
            query = query,
            matches = destination,
            matchCount = matchCount,
            problems = compiled.problems,
            counts = FacetCounts.all(index, filter, source.text, TIMELINE_BUCKETS),
            windowSinceMillis = filter.sinceMillis.takeIf { bound -> bound != Long.MIN_VALUE },
            windowUntilMillis = filter.untilMillis.takeIf { bound -> bound != Long.MAX_VALUE },
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }
}

const val LEVEL_FIELD: String = "level"

/** Select-all on a nine-million-entry result is gigabytes; the clipboard is for a ticket. */
const val CLIPBOARD_MAX_ENTRIES: Int = 20_000

/** Twenty either side is about a screenful, which is what "what was going on there" means. */
const val CONTEXT_RADIUS: Int = 20

/** Enough buckets that a 700 px strip has more than one per pixel column. */
const val TIMELINE_BUCKETS: Int = 900
