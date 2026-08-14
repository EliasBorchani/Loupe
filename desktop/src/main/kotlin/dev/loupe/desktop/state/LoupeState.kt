package dev.loupe.desktop.state

import dev.loupe.core.index.EntryFilter
import dev.loupe.core.index.FacetCounts
import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.SidebarCounts
import dev.loupe.core.query.CompiledQuery
import dev.loupe.core.query.QueryCompiler
import dev.loupe.core.query.QueryEdits
import dev.loupe.core.source.LogSource
import dev.loupe.core.source.LogSourceLoader
import dev.loupe.core.source.OpenPhase
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ViewMode { Columns, Raw }

sealed interface OpenStatus {
    data object Idle : OpenStatus
    class Working(val phase: OpenPhase, val bytesDone: Long, val bytesTotal: Long) : OpenStatus {
        val fraction: Float get() = if (bytesTotal <= 0L) 0f else (bytesDone.toDouble() / bytesTotal).toFloat()
    }
    class Failed(val message: String) : OpenStatus
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

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    private val _source = MutableStateFlow<LogSource?>(null)
    val source: StateFlow<LogSource?> = _source.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _status = MutableStateFlow<OpenStatus>(OpenStatus.Idle)
    val status: StateFlow<OpenStatus> = _status.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.Columns)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _selectedEntry = MutableStateFlow<Int?>(null)
    val selectedEntry: StateFlow<Int?> = _selectedEntry.asStateFlow()

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
            _selectedEntry.value = null
            _expandedEntries.value = emptySet()
            try {
                val opened: LogSource = withContext(Dispatchers.IO) {
                    LogSourceLoader.open(paths) { phase, done, total ->
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
        _selectedEntry.value = null
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
    }

    fun select(entry: Int?) {
        _selectedEntry.value = entry
    }

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

    /** A timeline brush writes `since:` / `until:`, so the bar always explains the picture. */
    fun setTimeWindow(sinceMillis: Long?, untilMillis: Long?) {
        val zone: ZoneId = ZoneId.systemDefault()
        setQuery(
            QueryEdits.setTimeWindow(
                query = _query.value,
                since = sinceMillis?.let { millis -> TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone)) },
                until = untilMillis?.let { millis -> TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone)) },
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

/** Enough buckets that a 700 px strip has more than one per pixel column. */
const val TIMELINE_BUCKETS: Int = 900
