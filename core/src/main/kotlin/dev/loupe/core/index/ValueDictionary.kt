package dev.loupe.core.index

/**
 * Dictionary-encodes facet values so a column stores an `Int` per entry instead of a `String`.
 *
 * Interning is done straight from the raw bytes of the line: a `String` is only ever built the
 * first time a value is seen (and for display), so a 5 M-entry file with 22 categories allocates
 * 22 strings, not 5 M. That is the whole point — see the memory budget in the PRD (§8).
 *
 * Open addressing with linear probing; the slot table stores `id + 1` so `0` means empty.
 * Not thread-safe: one dictionary per indexing pass.
 */
class ValueDictionary(expectedValues: Int = 64) {

    companion object {
        private const val FNV_OFFSET_BASIS = -2128831035 // 0x811C9DC5 as a signed Int
        private const val FNV_PRIME = 16777619
        private const val MAX_LOAD_FACTOR = 0.5
    }

    val size: Int get() = valueBytes.size

    private val valueBytes: ArrayList<ByteArray> = ArrayList(expectedValues)
    private val valueCounts: ArrayList<Int> = ArrayList(expectedValues)
    private var decodedValues: Array<String?> = arrayOfNulls(expectedValues)

    private var slots: IntArray = IntArray(tableSizeFor(expectedValues))
    private var slotMask: Int = slots.size - 1

    /**
     * Interns `bytes[start until end]` and increments its count.
     *
     * @return the stable id of that value, usable as a column value.
     */
    fun intern(bytes: ByteArray, start: Int, end: Int): Int {
        val hash: Int = hashOf(bytes, start, end)
        var slot: Int = hash and slotMask
        while (true) {
            val occupant: Int = slots[slot]
            if (occupant == 0) {
                val id: Int = appendValue(bytes, start, end)
                slots[slot] = id + 1
                if (valueBytes.size > slots.size * MAX_LOAD_FACTOR) growTable()
                return id
            }
            val candidateId: Int = occupant - 1
            if (matches(valueBytes[candidateId], bytes, start, end)) {
                valueCounts[candidateId] = valueCounts[candidateId] + 1
                return candidateId
            }
            slot = (slot + 1) and slotMask
        }
    }

    /** Number of entries that carried this value — the facet count, free of a second pass. */
    fun countOf(id: Int): Int = valueCounts[id]

    /** Decodes lazily: a value only becomes a `String` when something wants to display it. */
    fun valueOf(id: Int): String {
        decodedValues[id]?.let { cached -> return cached }
        val decoded = String(valueBytes[id], Charsets.UTF_8)
        decodedValues[id] = decoded
        return decoded
    }

    /** Ids ordered by descending count — the order the facet sidebar renders them in. */
    fun idsByDescendingCount(): IntArray =
        (0 until size).sortedByDescending { id -> valueCounts[id] }.toIntArray()

    private fun appendValue(bytes: ByteArray, start: Int, end: Int): Int {
        val id: Int = valueBytes.size
        valueBytes.add(bytes.copyOfRange(start, end))
        valueCounts.add(1)
        if (id >= decodedValues.size) {
            decodedValues = decodedValues.copyOf(maxOf(decodedValues.size * 2, id + 1))
        }
        return id
    }

    private fun growTable() {
        slots = IntArray(slots.size * 2)
        slotMask = slots.size - 1
        valueBytes.forEachIndexed { id, value ->
            var slot: Int = hashOf(value, 0, value.size) and slotMask
            while (slots[slot] != 0) slot = (slot + 1) and slotMask
            slots[slot] = id + 1
        }
    }

    private fun hashOf(bytes: ByteArray, start: Int, end: Int): Int {
        var hash: Int = FNV_OFFSET_BASIS
        for (index in start until end) {
            hash = (hash xor bytes[index].toInt()) * FNV_PRIME
        }
        // FNV's low bits are well mixed but the high bits carry most entropy; fold them down
        // because the slot index only ever uses the low bits.
        return hash xor (hash ushr 16)
    }

    private fun matches(stored: ByteArray, bytes: ByteArray, start: Int, end: Int): Boolean {
        if (stored.size != end - start) return false
        for (offset in stored.indices) {
            if (stored[offset] != bytes[start + offset]) return false
        }
        return true
    }

    private fun tableSizeFor(expectedValues: Int): Int {
        var size = 16
        while (size * MAX_LOAD_FACTOR < expectedValues) size = size shl 1
        return size
    }
}
