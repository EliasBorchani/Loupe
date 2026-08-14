package dev.loupe.core.profile

/**
 * Maps a captured level token to a severity ordinal.
 *
 * The declared [order] *is* the severity scale — index 0 is the least severe — which is what makes
 * `level>=W` expressible without the profile having to name a comparison anywhere.
 *
 * Single-character levels (`V D I W E`) get a flat lookup table indexed by the character itself.
 * Word levels (`TRACE DEBUG INFO WARN ERROR`) fall back to comparing against each candidate in
 * turn, which is still only a handful of character comparisons because a scale has a handful of
 * steps. Neither path allocates.
 */
class LevelDecoder(val order: List<String>, labels: Map<String, String>) {

    companion object {
        const val UNKNOWN_ORDINAL: Int = -1

        private const val ASCII_TABLE_SIZE = 128
    }

    val size: Int get() = order.size

    /** Display names, positionally aligned with [order]; falls back to the raw token. */
    val labels: List<String> = order.map { value -> labels[value] ?: value }

    private val isSingleCharacter: Boolean = order.all { value -> value.length == 1 && value[0].code < ASCII_TABLE_SIZE }

    private val ordinalByCharacter: ByteArray? = if (!isSingleCharacter) {
        null
    } else {
        ByteArray(ASCII_TABLE_SIZE) { UNKNOWN_ORDINAL.toByte() }.also { table ->
            order.forEachIndexed { ordinal, value -> table[value[0].code] = ordinal.toByte() }
        }
    }

    /**
     * @return the severity ordinal of a single ASCII byte, or [UNKNOWN_ORDINAL].
     *
     * For parsers that never decode the line. Always [UNKNOWN_ORDINAL] on a word-level scale, so
     * only a scanner written against a single-character profile should use it.
     */
    fun ordinalOfSingleByte(symbol: Byte): Int {
        val table: ByteArray = ordinalByCharacter ?: return UNKNOWN_ORDINAL
        val code: Int = symbol.toInt()
        if (code < 0 || code >= ASCII_TABLE_SIZE) return UNKNOWN_ORDINAL
        return table[code].toInt()
    }

    /** @return the severity ordinal of `chars[start until end]`, or [UNKNOWN_ORDINAL]. */
    fun ordinalOf(chars: CharSequence, start: Int, end: Int): Int {
        if (ordinalByCharacter != null) {
            if (end - start != 1) return UNKNOWN_ORDINAL
            val code: Int = chars[start].code
            if (code >= ASCII_TABLE_SIZE) return UNKNOWN_ORDINAL
            return ordinalByCharacter[code].toInt()
        }

        val length: Int = end - start
        order.forEachIndexed { ordinal, candidate ->
            if (candidate.length == length && regionMatches(chars, start, candidate)) return ordinal
        }
        return UNKNOWN_ORDINAL
    }

    private fun regionMatches(chars: CharSequence, start: Int, candidate: String): Boolean {
        for (offset in candidate.indices) {
            if (chars[start + offset] != candidate[offset]) return false
        }
        return true
    }
}
