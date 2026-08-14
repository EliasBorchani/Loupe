package dev.loupe.core.model

/**
 * Severity levels. **Declaration order is the severity order** — ordinal comparisons back
 * `level>=W` in the query language, so never reorder these.
 *
 * Mirrors `com.withings.log.core.model.LogLevel` in the HealthMate app; a profile declares its own
 * symbol mapping, this enum is the normalised target.
 */
enum class LogLevel(val symbol: Char) {
    Verbose('V'),
    Debug('D'),
    Info('I'),
    Warning('W'),
    Error('E'),
    ;

    companion object {
        const val UNKNOWN_ORDINAL: Int = -1

        private const val ASCII_TABLE_SIZE = 128

        /** Symbol → ordinal, as a flat table: the parse hot path indexes it with a raw byte. */
        private val ORDINAL_BY_SYMBOL: ByteArray = ByteArray(ASCII_TABLE_SIZE) { UNKNOWN_ORDINAL.toByte() }

        init {
            entries.forEach { level -> ORDINAL_BY_SYMBOL[level.symbol.code] = level.ordinal.toByte() }
        }

        /** @return the ordinal for an ASCII symbol byte, or [UNKNOWN_ORDINAL]. Allocation-free. */
        fun ordinalOfSymbolByte(symbol: Byte): Int {
            val code: Int = symbol.toInt()
            if (code < 0 || code >= ASCII_TABLE_SIZE) return UNKNOWN_ORDINAL
            return ORDINAL_BY_SYMBOL[code].toInt()
        }

        fun ofOrdinalOrNull(ordinal: Int): LogLevel? = entries.getOrNull(ordinal)
    }
}
