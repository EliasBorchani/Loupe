package dev.loupe.core.profile

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.decodeFromString
import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A [LogProfileSpec] turned into something that can parse ten million lines: one compiled
 * `Pattern`, group *numbers* rather than names, a level scale, and the two cheap line predicates.
 *
 * All validation happens here, at load time, and reports every problem at once — a profile is
 * written by hand and usually wrong in more than one way on the first try.
 */
class CompiledProfile private constructor(
    val name: String,
    val description: String,
    val priority: Int,
    val detect: DetectSpec,
    val detectFilename: Pattern?,
    val pattern: Pattern,
    val timestampGroup: Int,
    val timestampFormat: TimestampFormat,
    /** `-1` when the format carries no level. */
    val levelGroup: Int,
    val levelDecoder: LevelDecoder?,
    val facets: List<CompiledFacet>,
    /** `-1` when no group is tagged as the message. */
    val messageGroup: Int,
    val opens: LinePredicate?,
    val continues: LinePredicate?,
    val stripContinuationIndent: Boolean,
    val markers: List<CompiledMarker>,
    /** Non-fatal remarks — a slow timestamp path, a predicate that could not be reduced. */
    val warnings: List<String>,
) {

    companion object {
        private val TOML = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))

        fun load(file: File): CompiledProfile = compile(parse(file.readText(), file.name))

        fun parse(source: String, origin: String = "<string>"): LogProfileSpec = try {
            TOML.decodeFromString<LogProfileSpec>(source)
        } catch (failure: Exception) {
            throw InvalidProfileException(listOf("$origin is not a readable profile: ${failure.message}"), failure)
        }

        /** @throws InvalidProfileException listing every problem found, not just the first. */
        fun compile(spec: LogProfileSpec): CompiledProfile {
            val problems: MutableList<String> = mutableListOf()
            val warnings: MutableList<String> = mutableListOf()

            val pattern: Pattern? = try {
                Pattern.compile(spec.parse.regex, Pattern.DOTALL)
            } catch (failure: PatternSyntaxException) {
                problems.add("parse.regex does not compile: ${failure.description} at index ${failure.index}")
                null
            }

            val groupIndexes: Map<String, Int> = pattern?.let { NamedGroups.indexesOf(spec.parse.regex) } ?: emptyMap()

            spec.fields.keys.filterNot { field -> field in groupIndexes }.forEach { orphan ->
                problems.add("field '$orphan' has no matching named group in parse.regex")
            }
            groupIndexes.keys.filterNot { group -> group in spec.fields }.forEach { orphan ->
                problems.add("named group '$orphan' has no matching [fields.$orphan] declaration")
            }

            val byRole: Map<FieldRole, List<Map.Entry<String, FieldSpec>>> =
                spec.fields.entries.groupBy { (_, field) -> field.role }

            val timestampFields = byRole[FieldRole.Timestamp].orEmpty()
            if (timestampFields.size != 1) {
                problems.add("exactly one field must have role = \"timestamp\", found ${timestampFields.size}")
            }
            byRole[FieldRole.Level].orEmpty().let { levels ->
                if (levels.size > 1) problems.add("at most one field may have role = \"level\", found ${levels.size}")
            }
            byRole[FieldRole.Message].orEmpty().let { messages ->
                if (messages.size > 1) problems.add("at most one field may have role = \"message\", found ${messages.size}")
            }

            var timestampFormat: TimestampFormat? = null
            timestampFields.singleOrNull()?.let { (fieldName, field) ->
                val format: String? = field.format
                if (format == null) {
                    problems.add("[fields.$fieldName] has role = \"timestamp\" but no format")
                } else {
                    try {
                        val compiled = TimestampFormat.compile(format, field.zone, field.assumeYear)
                        timestampFormat = compiled
                        if (compiled.assumesYear) {
                            warnings.add(
                                "timestamp format '$format' carries no year, so one is assumed — set " +
                                    "[fields.$fieldName] assume_year for an archived log from another year",
                            )
                        }
                        if (!compiled.isFastPath) {
                            warnings.add(
                                "timestamp format '$format' falls back to DateTimeFormatter — expect roughly a " +
                                    "microsecond per entry instead of a few nanoseconds",
                            )
                        }
                    } catch (failure: IllegalArgumentException) {
                        problems.add("[fields.$fieldName] format '$format' is not usable: ${failure.message}")
                    }
                }
            }

            var levelDecoder: LevelDecoder? = null
            byRole[FieldRole.Level].orEmpty().singleOrNull()?.let { (fieldName, field) ->
                val order: List<String>? = field.order
                when {
                    order.isNullOrEmpty() ->
                        problems.add("[fields.$fieldName] has role = \"level\" but no order — `level>=…` needs a scale")

                    order.distinct().size != order.size ->
                        problems.add("[fields.$fieldName] order repeats a value")

                    else -> levelDecoder = LevelDecoder(order, field.labels)
                }
            }

            val facets: List<CompiledFacet> = byRole[FieldRole.Facet].orEmpty()
                .filter { (fieldName, _) -> fieldName in groupIndexes }
                .map { (fieldName, field) ->
                    CompiledFacet(
                        name = fieldName,
                        label = field.label ?: fieldName,
                        group = groupIndexes.getValue(fieldName),
                        mode = field.facet,
                        declaredValues = field.values,
                    )
                }

            // `opens` is an optimisation, so it is compiled as a necessary condition and dropped
            // if nothing cheap can be derived — a regex pre-filter in front of a regex parse costs
            // two String allocations and buys nothing.
            val opens: LinePredicate? = spec.entry.opens?.let { source ->
                try {
                    LinePredicate.compileNecessary(source).also { predicate ->
                        if (predicate == null) {
                            warnings.add("entry.opens yields no cheap pre-filter and is ignored; parse.regex decides alone")
                        }
                    }
                } catch (failure: PatternSyntaxException) {
                    problems.add("entry.opens does not compile: ${failure.description}")
                    null
                }
            }

            // `continues` decides how a line is classified, so it must mean exactly what it says.
            val continues: LinePredicate? = spec.entry.continues?.let { source ->
                try {
                    LinePredicate.compileExact(source).also { predicate ->
                        if (!predicate.isFastPath) {
                            warnings.add(
                                "entry.continues could not be reduced to a literal prefix — it runs as a regex on " +
                                    "every line, which is the cost the pre-filter exists to avoid",
                            )
                        }
                    }
                } catch (failure: PatternSyntaxException) {
                    problems.add("entry.continues does not compile: ${failure.description}")
                    null
                }
            }
            if (continues == null) {
                warnings.add("no entry.continues — every line will be tried against parse.regex, which is the slow shape")
            }

            val markers: List<CompiledMarker> = spec.markers.mapIndexedNotNull { index, marker ->
                try {
                    CompiledMarker(Pattern.compile(marker.regex), marker.role)
                } catch (failure: PatternSyntaxException) {
                    problems.add("markers[$index].regex does not compile: ${failure.description}")
                    null
                }
            }

            val detectFilename: Pattern? = spec.detect.filename?.let { source ->
                try {
                    Pattern.compile(source)
                } catch (failure: PatternSyntaxException) {
                    problems.add("detect.filename does not compile: ${failure.description}")
                    null
                }
            }

            if (problems.isNotEmpty()) throw InvalidProfileException(problems)

            return CompiledProfile(
                name = spec.name,
                description = spec.description,
                priority = spec.priority,
                detect = spec.detect,
                detectFilename = detectFilename,
                pattern = requireNotNull(pattern),
                timestampGroup = groupIndexes.getValue(timestampFields.single().key),
                timestampFormat = requireNotNull(timestampFormat),
                levelGroup = byRole[FieldRole.Level].orEmpty().singleOrNull()
                    ?.let { (fieldName, _) -> groupIndexes.getValue(fieldName) } ?: NO_GROUP,
                levelDecoder = levelDecoder,
                facets = facets,
                messageGroup = byRole[FieldRole.Message].orEmpty().singleOrNull()
                    ?.let { (fieldName, _) -> groupIndexes.getValue(fieldName) } ?: NO_GROUP,
                opens = opens,
                continues = continues,
                stripContinuationIndent = spec.entry.stripContinuationIndent,
                markers = markers,
                warnings = warnings,
            )
        }

        const val NO_GROUP: Int = -1
    }

    val levelCount: Int get() = levelDecoder?.size ?: 0
}

class CompiledFacet(
    val name: String,
    val label: String,
    /** Group number in [CompiledProfile.pattern]. */
    val group: Int,
    val mode: FacetMode,
    /** Closed value set, when the profile declares one. Orders the sidebar and flags typos. */
    val declaredValues: List<String>?,
)

class CompiledMarker(val pattern: Pattern, val role: MarkerRole)

class InvalidProfileException(
    val problems: List<String>,
    cause: Throwable? = null,
) : IllegalArgumentException(problems.joinToString(prefix = "Invalid log profile:\n  - ", separator = "\n  - "), cause)
