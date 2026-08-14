package dev.loupe.core.index

/**
 * Dictionary-encodes facet values so a column stores an `Int` per entry instead of a `String`.
 *
 * A `String` is only ever built the first time a value is seen, so a five-million-entry file with
 * twenty-two categories allocates twenty-two strings, not five million. That is what makes the
 * memory budget hold.
 *
 * Two ways in, because the two parser shapes have different things in hand: [intern] from the
 * decoded line, [internBytes] straight from the read buffer. They hash differently, so **one
 * dictionary must only ever be fed one way** — enforced rather than documented, since mixing them
 * would corrupt the slot table in a way no test would notice until it produced a wrong facet.
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

    val size: Int get() = values.size

    private val values: ArrayList<String> = ArrayList(expectedValues)
    private val valueCounts: ArrayList<Int> = ArrayList(expectedValues)

    /** Populated only in byte mode, where comparisons happen before anything is decoded. */
    private var valueBytes: ArrayList<ByteArray>? = null

    private var slots: IntArray = IntArray(tableSizeFor(expectedValues))
    private var slotMask: Int = slots.size - 1
    private var feedMode: FeedMode? = null

    /** Interns `chars[start until end]` and counts it. */
    fun intern(chars: CharSequence, start: Int, end: Int): Int {
        requireFeedMode(FeedMode.Chars)
        var hash: Int = FNV_OFFSET_BASIS
        for (index in start until end) {
            hash = (hash xor chars[index].code) * FNV_PRIME
        }
        return internAt(mix(hash), { candidate -> matchesChars(candidate, chars, start, end) }) {
            chars.subSequence(start, end).toString()
        }
    }

    /** Interns `bytes[start until end]`, decoding as UTF-8 only when the value is new. */
    fun internBytes(bytes: ByteArray, start: Int, end: Int): Int {
        requireFeedMode(FeedMode.Bytes)
        var hash: Int = FNV_OFFSET_BASIS
        for (index in start until end) {
            hash = (hash xor bytes[index].toInt()) * FNV_PRIME
        }
        return internAt(mix(hash), { candidate -> matchesBytes(candidate, bytes, start, end) }) {
            requireNotNull(valueBytes).add(bytes.copyOfRange(start, end))
            String(bytes, start, end - start, Charsets.UTF_8)
        }
    }

    /** Number of entries that carried this value — the facet count, with no second pass. */
    fun countOf(id: Int): Int = valueCounts[id]

    fun valueOf(id: Int): String = values[id]

    /** Ids ordered by descending count — the order the facet sidebar renders them in. */
    fun idsByDescendingCount(): IntArray =
        (0 until size).sortedByDescending { id -> valueCounts[id] }.toIntArray()

    /** Every distinct value, in first-seen order. */
    fun allValues(): List<String> = values

    private inline fun internAt(hash: Int, matches: (Int) -> Boolean, decode: () -> String): Int {
        var slot: Int = hash and slotMask
        while (true) {
            val occupant: Int = slots[slot]
            if (occupant == 0) {
                val id: Int = values.size
                values.add(decode())
                valueCounts.add(1)
                slots[slot] = id + 1
                if (values.size > slots.size * MAX_LOAD_FACTOR) growTable()
                return id
            }
            val candidateId: Int = occupant - 1
            if (matches(candidateId)) {
                valueCounts[candidateId] = valueCounts[candidateId] + 1
                return candidateId
            }
            slot = (slot + 1) and slotMask
        }
    }

    private fun growTable() {
        slots = IntArray(slots.size * 2)
        slotMask = slots.size - 1
        val storedBytes: ArrayList<ByteArray>? = valueBytes
        values.forEachIndexed { id, value ->
            val hash: Int = if (storedBytes != null) hashOfBytes(storedBytes[id]) else hashOfChars(value)
            var slot: Int = hash and slotMask
            while (slots[slot] != 0) slot = (slot + 1) and slotMask
            slots[slot] = id + 1
        }
    }

    private fun hashOfChars(value: String): Int {
        var hash: Int = FNV_OFFSET_BASIS
        for (index in value.indices) hash = (hash xor value[index].code) * FNV_PRIME
        return mix(hash)
    }

    private fun hashOfBytes(value: ByteArray): Int {
        var hash: Int = FNV_OFFSET_BASIS
        for (index in value.indices) hash = (hash xor value[index].toInt()) * FNV_PRIME
        return mix(hash)
    }

    /**
     * FNV's high bits carry most of the entropy and the slot index only uses the low ones, so fold
     * them down before masking.
     */
    private fun mix(hash: Int): Int = hash xor (hash ushr 16)

    private fun matchesChars(candidateId: Int, chars: CharSequence, start: Int, end: Int): Boolean {
        val stored: String = values[candidateId]
        if (stored.length != end - start) return false
        for (offset in stored.indices) {
            if (stored[offset] != chars[start + offset]) return false
        }
        return true
    }

    /**
     * Compares against the value's UTF-8 bytes, kept alongside in byte mode.
     *
     * Comparing byte-to-char instead would silently re-intern any value containing a multi-byte
     * character as a fresh id every time it appeared — a duplicated facet entry, not a crash.
     */
    private fun matchesBytes(candidateId: Int, bytes: ByteArray, start: Int, end: Int): Boolean {
        val stored: ByteArray = requireNotNull(valueBytes)[candidateId]
        if (stored.size != end - start) return false
        for (offset in stored.indices) {
            if (stored[offset] != bytes[start + offset]) return false
        }
        return true
    }

    private fun requireFeedMode(mode: FeedMode) {
        if (feedMode == null) {
            feedMode = mode
            if (mode == FeedMode.Bytes) valueBytes = ArrayList(values.size + 16)
            return
        }
        require(feedMode == mode) { "A ValueDictionary cannot be fed both chars and bytes: it hashes them differently" }
    }

    private fun tableSizeFor(expectedValues: Int): Int {
        var size = 16
        while (size * MAX_LOAD_FACTOR < expectedValues) size = size shl 1
        return size
    }

    private enum class FeedMode { Chars, Bytes }
}
