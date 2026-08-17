package dev.loupe.core.index

/**
 * Merges per-file indexes into one time-ordered view, with a synthetic `file` facet.
 *
 * A week of HealthMate logs is seven day files. Read separately they hide exactly the case that
 * motivates the tool — a sync that straddles midnight — so the merged stream is the default.
 *
 * Two things make this cheap:
 *
 *  - **No sort.** Each file is already ascending by construction, so this is a k-way merge, not a
 *    nine-million-element sort. A binary heap over the file heads keeps it `O(n log k)` rather than
 *    the `O(n·k)` a linear scan of heads would cost once someone opens a year of files.
 *  - **Dictionary remapping, not re-interning.** Each file assigned its own ids — `Sync` may be 0
 *    in one file and 2 in another — so the merge builds one dictionary per facet and an
 *    `oldId → newId` table per file. Counts come across in bulk from the source dictionaries
 *    instead of being re-tallied entry by entry.
 */
object IndexMerger {

    /**
     * @param sources one index per file, in the order the files should be numbered.
     * @param fileNames display names, positionally aligned with [sources].
     */
    fun merge(sources: List<LogIndex>, fileNames: List<String>): LogIndex {
        require(sources.isNotEmpty()) { "Nothing to merge" }
        require(sources.size == fileNames.size) {
            "Got ${sources.size} indexes for ${fileNames.size} file names"
        }
        if (sources.size == 1) return sources.single()

        val reference: LogIndex = sources.first()
        val profileFacetCount: Int = reference.facets.size
        require(sources.all { source -> source.facets.size == profileFacetCount }) {
            "Every merged index must come from the same profile"
        }

        val totalEntries: Int = sources.sumOf { source -> source.entryCount }
        val fileFacetIndex: Int = profileFacetCount
        val mergedFacetCount: Int = profileFacetCount + 1

        // One dictionary per profile facet, plus the file facet whose ids are the file order.
        val dictionaries: Array<ValueDictionary> = Array(mergedFacetCount) { ValueDictionary() }
        val remaps: List<Array<IntArray>> = sources.map { source ->
            Array(profileFacetCount) { facetIndex ->
                val from: ValueDictionary = source.facetDictionaries[facetIndex]
                IntArray(from.size) { id ->
                    dictionaries[facetIndex].internString(from.valueOf(id), from.countOf(id))
                }
            }
        }
        fileNames.forEachIndexed { fileId, name ->
            val assigned: Int = dictionaries[fileFacetIndex].internString(name, sources[fileId].entryCount)
            check(assigned == fileId) { "File facet ids must match file order; '$name' got $assigned, expected $fileId" }
        }

        val timestamps = LongArray(totalEntries)
        val levels = ByteArray(totalEntries)
        val facetValues: Array<IntArray> = Array(mergedFacetCount) { IntArray(totalEntries) }
        val byteOffsets = LongArray(totalEntries)
        val byteLengths = IntArray(totalEntries)

        val heap = FileHeap(sources)
        var written = 0
        while (heap.isNotEmpty()) {
            val next: Long = heap.takeSmallest()
            val fileId: Int = (next ushr 32).toInt()
            val entry: Int = next.toInt()
            val source: LogIndex = sources[fileId]

            timestamps[written] = source.timestamps[entry]
            levels[written] = source.levels[entry]
            byteOffsets[written] = source.byteOffsets[entry]
            byteLengths[written] = source.byteLengths[entry]
            for (facetIndex in 0 until profileFacetCount) {
                val valueId: Int = source.facetValues[facetIndex][entry]
                facetValues[facetIndex][written] = if (valueId == LogIndex.NO_VALUE) {
                    LogIndex.NO_VALUE
                } else {
                    remaps[fileId][facetIndex][valueId]
                }
            }
            facetValues[fileFacetIndex][written] = fileId
            written++
        }
        check(written == totalEntries) { "Merged $written of $totalEntries entries" }

        val levelCounts = IntArray(reference.levelCounts.size)
        sources.forEach { source ->
            source.levelCounts.forEachIndexed { ordinal, count -> levelCounts[ordinal] += count }
        }

        return LogIndex(
            profile = reference.profile,
            facets = reference.facets + LogIndex.fileFacet(),
            entryCount = totalEntries,
            timestamps = timestamps,
            levels = levels,
            facetValues = facetValues,
            facetDictionaries = dictionaries,
            byteOffsets = byteOffsets,
            byteLengths = byteLengths,
            levelCounts = levelCounts,
            lineCount = sources.sumOf { source -> source.lineCount },
            continuationLineCount = sources.sumOf { source -> source.continuationLineCount },
            sectionLineCount = sources.sumOf { source -> source.sectionLineCount },
            noticeLineCount = sources.sumOf { source -> source.noticeLineCount },
            unrecognisedLineCount = sources.sumOf { source -> source.unrecognisedLineCount },
            unrecognised = UnrecognisedReport.merge(
                reports = sources.map { source -> source.unrecognised },
                fileIds = sources.indices.toList(),
            ),
            fileFacetIndex = fileFacetIndex,
        )
    }

    /**
     * Min-heap over the files' current entries, keyed by timestamp.
     *
     * Ties break on file id, so a merge is deterministic and two entries stamped the same
     * millisecond always come out in the same order — otherwise the same folder would render
     * differently between runs.
     */
    private class FileHeap(private val sources: List<LogIndex>) {

        private val cursors = IntArray(sources.size)
        private val heap = IntArray(sources.size)
        private var size = 0

        init {
            sources.forEachIndexed { fileId, source ->
                if (source.entryCount > 0) {
                    heap[size] = fileId
                    size++
                }
            }
            for (node in size / 2 - 1 downTo 0) siftDown(node)
        }

        fun isNotEmpty(): Boolean = size > 0

        /**
         * Consumes the earliest pending entry across all files and re-heaps.
         *
         * @return the file id in the high 32 bits and the entry index in the low 32, packed so the
         *   caller cannot pop and consume out of step.
         */
        fun takeSmallest(): Long {
            val fileId: Int = heap[0]
            val entry: Int = cursors[fileId]
            cursors[fileId] = entry + 1
            if (cursors[fileId] >= sources[fileId].entryCount) {
                size--
                heap[0] = heap[size]
            }
            if (size > 0) siftDown(0)
            return (fileId.toLong() shl 32) or entry.toLong()
        }

        private fun siftDown(from: Int) {
            var node = from
            while (true) {
                val left: Int = node * 2 + 1
                if (left >= size) return
                val right: Int = left + 1
                var smallest: Int = if (right < size && isBefore(heap[right], heap[left])) right else left
                if (!isBefore(heap[smallest], heap[node])) return
                val swap: Int = heap[node]
                heap[node] = heap[smallest]
                heap[smallest] = swap
                node = smallest
            }
        }

        private fun isBefore(fileA: Int, fileB: Int): Boolean {
            val timeA: Long = sources[fileA].timestamps[cursors[fileA]]
            val timeB: Long = sources[fileB].timestamps[cursors[fileB]]
            return if (timeA != timeB) timeA < timeB else fileA < fileB
        }
    }
}
