package dev.loupe.core.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A log format, exactly as written in a `*.logprofile.toml`.
 *
 * This is the *declared* form — strings and raw regex sources, no compiled state. Turning it into
 * something that can parse ten million lines is [CompiledProfile]'s job, and everything that could
 * be wrong with a profile (a regex that will not compile, a role declared twice, a group the regex
 * does not define) is reported there rather than here, so a bad profile fails with one readable
 * message instead of a deserialisation error.
 */
@Serializable
data class LogProfileSpec(
    val name: String,
    val description: String = "",
    /** Tie-break when several profiles score equally on a file. Higher wins. */
    val priority: Int = 0,
    val detect: DetectSpec = DetectSpec(),
    val entry: EntrySpec = EntrySpec(),
    val parse: ParseSpec,
    val fields: Map<String, FieldSpec>,
    val markers: List<MarkerSpec> = emptyList(),
)

@Serializable
data class DetectSpec(
    /** Regex on the file name. A match is a strong hint, never a requirement. */
    val filename: String? = null,
    /** How many lines to read when scoring this profile against a file. */
    val sample: Int = 200,
    /** Below this share of recognised sample lines the profile is not considered a match. */
    @SerialName("min_match") val minMatch: Double = 0.8,
)

@Serializable
data class EntrySpec(
    /**
     * Cheap regex identifying a line that opens an entry. Optional: without it every line is tried
     * against [ParseSpec.regex].
     */
    val opens: String? = null,
    /**
     * Regex identifying a line that continues the entry above it — a wrapped message, a stack
     * trace frame. **Tested before [ParseSpec.regex]**, which is what keeps the expensive match to
     * once per entry rather than once per line (M0 measured 18.6 % of lines as continuations).
     */
    val continues: String? = null,
    @SerialName("strip_continuation_indent") val stripContinuationIndent: Boolean = false,
)

@Serializable
data class ParseSpec(
    /**
     * Applied to an entry's opening line. Named groups are bound to fields by name, so every
     * group must have a matching `[fields.<name>]` entry and vice versa.
     */
    val regex: String,
)

@Serializable
data class FieldSpec(
    val role: FieldRole,
    /** Human-readable name for the facet sidebar. Defaults to the group name. */
    val label: String? = null,
    /** [FieldRole.Timestamp] only — e.g. `yyyy-MM-dd HH:mm:ss.SSS`. */
    val format: String? = null,
    /** [FieldRole.Timestamp] only — `local`, `utc`, or a zone id such as `Europe/Paris`. */
    val zone: String? = null,
    /**
     * [FieldRole.Timestamp] only — the year to assume when the format has none, as logcat's
     * `MM-dd` and syslog's `MMM d` do. Defaults to the current year.
     */
    @SerialName("assume_year") val assumeYear: Int? = null,
    /** [FieldRole.Level] only — captured values in **ascending severity**. Enables `level>=W`. */
    val order: List<String>? = null,
    val labels: Map<String, String> = emptyMap(),
    val facet: FacetMode = FacetMode.Auto,
)

@Serializable
data class MarkerSpec(val regex: String, val role: MarkerRole)

@Serializable
enum class FieldRole {
    @SerialName("timestamp")
    Timestamp,

    @SerialName("level")
    Level,

    @SerialName("facet")
    Facet,

    @SerialName("message")
    Message,
}

/** How the sidebar should treat a facet whose cardinality is not known in advance. */
@Serializable
enum class FacetMode {
    /** Always render the full list. */
    @SerialName("always")
    Always,

    /** Full list while small, top-N plus a search box past that — the R8-obfuscated tag case. */
    @SerialName("auto")
    Auto,

    /** Parse the field but keep it out of the sidebar. */
    @SerialName("never")
    Never,
}

/** What a line that is not a log entry nevertheless means. */
@Serializable
enum class MarkerRole {
    /** Opens a named section, e.g. the `=== 2026-07-22 ===` separator in a multi-day export. */
    @SerialName("section")
    Section,

    /** Informational, e.g. `--- older lines dropped ---`. Shown, never counted as an entry. */
    @SerialName("notice")
    Notice,
}
