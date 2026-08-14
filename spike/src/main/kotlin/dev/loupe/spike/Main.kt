package dev.loupe.spike

import dev.loupe.core.index.EntryFilter
import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.io.MappedText
import dev.loupe.core.model.LogLevel
import dev.loupe.core.parse.ByteScannerEntryParser
import dev.loupe.core.parse.EntryParser
import dev.loupe.core.parse.StringRegexEntryParser
import dev.loupe.core.parse.WidenedCharRegexEntryParser
import java.io.File
import java.util.Locale

/**
 * M0 — the blocking spike.
 *
 * One question: can a profile-driven, regex-based engine index ~5 M entries in under 5 s on the
 * JVM, or does the declarative design have to give way to hand-compiled scanners? Everything here
 * exists to answer that and nothing else.
 *
 * Deliberately not JMH. JMH shines on microbenchmarks it can run thousands of times; a single
 * multi-second pass over a 1 GiB file is `SingleShotTime` territory, where JMH's harness adds
 * ceremony without adding signal. Repeated in-process runs with the first one reported separately
 * show the JIT warming up just as clearly, and let the same run check that the fast strategies
 * actually agree with the slow one — which matters more than the last 2 % of timing precision.
 */
private const val DEFAULT_FIXTURE_BYTES: Long = 1L shl 30 // 1 GiB
private const val RUNS_PER_STRATEGY = 3
private const val FILTER_RUNS = 5
private const val BYTES_PER_MIB: Double = (1L shl 20).toDouble()

fun main(args: Array<String>) {
    val fixtureBytes: Long = args.getOrNull(0)?.let(::parseSize) ?: DEFAULT_FIXTURE_BYTES
    val fixture: File = prepareFixture(fixtureBytes)

    println()
    println("Fixture  ${fixture.path}  ${"%.2f".format(Locale.ROOT, fixture.length() / BYTES_PER_MIB)} MiB")
    println("JVM      ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    println("CPU      ${Runtime.getRuntime().availableProcessors()} cores, max heap ${Runtime.getRuntime().maxMemory() / (1L shl 20)} MiB")
    println()

    // Running all three in one JVM lets them cross-check each other, but it also makes `Matcher`'s
    // `charAt` call site bimorphic (String and the custom CharSequence both flow through it), which
    // can penalise whichever strategy the JIT does not specialise for. Pass a single letter as the
    // second argument to measure one strategy in a clean JVM and rule that out.
    val selection: String? = args.getOrNull(1)?.uppercase(Locale.ROOT)
    val strategies: List<EntryParser> = listOfNotNull(
        StringRegexEntryParser().takeIf { selection == null || selection == "A" },
        WidenedCharRegexEntryParser().takeIf { selection == null || selection == "B" },
        ByteScannerEntryParser().takeIf { selection == null || selection == "C" },
    )
    require(strategies.isNotEmpty()) { "Unknown strategy '$selection' — expected A, B or C." }

    val results: List<StrategyResult> = strategies.map { parser -> measureIndexing(parser, fixture) }
    printIndexingReport(results, fixture.length())
    if (results.size > 1) verifyStrategiesAgree(results)

    val reference: LogIndex = results.last().index
    printCorpusShape(reference)
    MappedText(fixture).use { text -> printFilterReport(reference, text) }
}

private class StrategyResult(
    val name: String,
    val index: LogIndex,
    val runNanos: List<Long>,
    val heapBytesAfterIndex: Long,
) {
    val coldNanos: Long get() = runNanos.first()
    val warmNanos: Long get() = runNanos.drop(1).minOrNull() ?: runNanos.first()
}

private fun measureIndexing(parser: EntryParser, fixture: File): StrategyResult {
    print("Indexing with ${parser.name} … ")
    System.out.flush()

    // Baseline taken with the previous strategies' indexes already retained, so the delta below
    // isolates *this* index rather than everything alive at the time.
    val heapBaselineBytes: Long = usedHeapBytes()

    var index: LogIndex? = null
    val runNanos: MutableList<Long> = mutableListOf()
    repeat(RUNS_PER_STRATEGY) {
        // A fresh indexer per run: dictionaries and columns must not be carried over.
        val startNanos: Long = System.nanoTime()
        index = LogIndexer(parser).index(fixture)
        runNanos.add(System.nanoTime() - startNanos)
        print("·")
        System.out.flush()
    }
    println(" done")

    return StrategyResult(
        name = parser.name,
        index = requireNotNull(index) { "indexing produced no result for ${parser.name}" },
        runNanos = runNanos,
        heapBytesAfterIndex = usedHeapBytes() - heapBaselineBytes,
    )
}

private fun printIndexingReport(results: List<StrategyResult>, fileBytes: Long) {
    val entryCount: Int = results.first().index.entryCount
    println()
    println("─".repeat(96))
    println("INDEXING — $entryCount entries, ${results.first().index.lineCount} lines")
    println("─".repeat(96))
    println(
        "%-30s %10s %10s %12s %12s %12s".format(
            Locale.ROOT, "strategy", "cold", "warm", "ns/entry", "MiB/s", "heap delta",
        ),
    )
    results.forEach { result ->
        val warmSeconds: Double = result.warmNanos / 1e9
        println(
            "%-30s %9.2fs %9.2fs %12.0f %12.0f %10d M".format(
                Locale.ROOT,
                result.name,
                result.coldNanos / 1e9,
                warmSeconds,
                result.warmNanos.toDouble() / result.index.entryCount,
                fileBytes / BYTES_PER_MIB / warmSeconds,
                result.heapBytesAfterIndex / (1L shl 20),
            ),
        )
    }
    println()
    println("Index footprint (columns only, text stays in the file): ${results.first().index.estimatedHeapBytes / (1L shl 20)} MiB")

    val target: Long = 5_000_000_000L
    val scaledToFiveMillion: List<Pair<String, Double>> = results.map { result ->
        result.name to result.warmNanos.toDouble() / result.index.entryCount * 5_000_000
    }
    println()
    println("Extrapolated to the PRD's 5 M-entry target (budget ${target / 1_000_000_000}s):")
    scaledToFiveMillion.forEach { (name, nanos) ->
        val verdict: String = if (nanos <= target) "PASS" else "FAIL"
        println("  %-30s %6.2fs   %s".format(Locale.ROOT, name, nanos / 1e9, verdict))
    }
}

/** A fast parser that disagrees with the reference is worthless — check before believing a timing. */
private fun verifyStrategiesAgree(results: List<StrategyResult>) {
    println()
    println("─".repeat(96))
    println("CROSS-CHECK")
    println("─".repeat(96))

    val reference: StrategyResult = results.first()
    var allAgree = true
    results.drop(1).forEach { candidate ->
        val differences: List<String> = compareIndexes(reference.index, candidate.index)
        if (differences.isEmpty()) {
            println("  ${candidate.name}  ≡  ${reference.name}")
        } else {
            allAgree = false
            println("  ${candidate.name}  DIFFERS from ${reference.name}:")
            differences.forEach { difference -> println("      - $difference") }
        }
    }
    if (!allAgree) error("Parser strategies disagree — timings are meaningless until this is fixed.")
}

private fun compareIndexes(reference: LogIndex, candidate: LogIndex): List<String> {
    val differences: MutableList<String> = mutableListOf()
    if (reference.entryCount != candidate.entryCount) {
        differences.add("entryCount ${reference.entryCount} vs ${candidate.entryCount}")
        return differences
    }
    if (reference.unparsedLineCount != candidate.unparsedLineCount) {
        differences.add("unparsedLineCount ${reference.unparsedLineCount} vs ${candidate.unparsedLineCount}")
    }
    for (entry in 0 until reference.entryCount) {
        if (reference.timestamps[entry] != candidate.timestamps[entry]) {
            differences.add("timestamp at entry $entry: ${reference.timestamps[entry]} vs ${candidate.timestamps[entry]}")
            break
        }
        if (reference.levels[entry] != candidate.levels[entry]) {
            differences.add("level at entry $entry")
            break
        }
        if (reference.byteOffsets[entry] != candidate.byteOffsets[entry] ||
            reference.byteLengths[entry] != candidate.byteLengths[entry]
        ) {
            differences.add("byte range at entry $entry")
            break
        }
        val referenceCategory: String = valueOrNone(reference, reference.categoryIds[entry])
        val candidateCategory: String = valueOrNone(candidate, candidate.categoryIds[entry])
        if (referenceCategory != candidateCategory) {
            differences.add("category at entry $entry: '$referenceCategory' vs '$candidateCategory'")
            break
        }
        if (reference.tags.valueOf(reference.tagIds[entry]) != candidate.tags.valueOf(candidate.tagIds[entry])) {
            differences.add("tag at entry $entry")
            break
        }
    }
    return differences
}

private fun valueOrNone(index: LogIndex, categoryId: Int): String =
    if (categoryId == LogIndex.NO_VALUE) "<none>" else index.categories.valueOf(categoryId)

private fun printCorpusShape(index: LogIndex) {
    println()
    println("─".repeat(96))
    println("CORPUS SHAPE — what the scan knows before a single line is read")
    println("─".repeat(96))
    println("  lines            ${index.lineCount}  (${index.continuationLineCount} continuations, ${index.unparsedLineCount} unrecognised)")
    println("  recognised       ${"%.3f".format(Locale.ROOT, index.recognisedLineRatio * 100)} %")
    println("  distinct tags    ${index.tags.size}")
    println("  levels")
    LogLevel.entries.forEach { level ->
        println("      %-8s %10d".format(Locale.ROOT, level.name, index.levelCounts[level.ordinal]))
    }
    println("  top categories")
    index.categories.idsByDescendingCount().take(6).forEach { id ->
        println("      %-24s %10d".format(Locale.ROOT, index.categories.valueOf(id), index.categories.countOf(id)))
    }
}

private fun printFilterReport(index: LogIndex, text: MappedText) {
    println()
    println("─".repeat(96))
    println("FILTERING — over ${index.entryCount} entries, ${Runtime.getRuntime().availableProcessors()} workers when parallel")
    println("─".repeat(96))

    val destination = IntArray(index.entryCount)
    val syncOnly = BooleanArray(index.categories.size)
    index.categories.idsByDescendingCount().firstOrNull()?.let { topId -> syncOnly[topId] = true }
    val topCategoryName: String = index.categories.idsByDescendingCount().firstOrNull()
        ?.let { id -> index.categories.valueOf(id) } ?: "?"

    val midpoint: Long = (index.minTimestampMillis + index.maxTimestampMillis) / 2

    val cases: List<Pair<String, EntryFilter>> = listOf(
        "level >= Warning" to EntryFilter(minLevelOrdinal = LogLevel.Warning.ordinal),
        "cat:$topCategoryName" to EntryFilter(acceptedCategories = syncOnly),
        "level>=W + cat + 1h window" to EntryFilter(
            minLevelOrdinal = LogLevel.Warning.ordinal,
            acceptedCategories = syncOnly,
            sinceMillis = midpoint,
            untilMillis = midpoint + 3_600_000L,
        ),
        "full text \"connected\"" to EntryFilter(substringLowercase = "connected".toByteArray()),
        "level>=W + \"backoff\"" to EntryFilter(
            minLevelOrdinal = LogLevel.Warning.ordinal,
            substringLowercase = "backoff".toByteArray(),
        ),
    )

    println("%-32s %10s %14s %14s".format(Locale.ROOT, "query", "matches", "1 thread", "parallel"))
    cases.forEach { (label, filter) ->
        val sequentialMatches: Int = bestOf(FILTER_RUNS) { filter.evaluate(index, text, destination) }
        val sequentialMillis: Double = lastBestMillis
        val parallelMatches: Int = bestOf(FILTER_RUNS) { filter.evaluateParallel(index, text, destination) }
        val parallelMillis: Double = lastBestMillis
        check(sequentialMatches == parallelMatches) {
            "'$label': parallel found $parallelMatches matches, sequential found $sequentialMatches"
        }
        println(
            "%-32s %10d %11.1f ms %11.1f ms".format(
                Locale.ROOT, label, sequentialMatches, sequentialMillis, parallelMillis,
            ),
        )
    }

    println()
    val histogramStartNanos: Long = System.nanoTime()
    val histogram: Array<IntArray> = index.timelineHistogram(bucketCount = 2000)
    val histogramMillis: Double = (System.nanoTime() - histogramStartNanos) / 1e6
    println("Timeline histogram (2000 buckets × ${histogram.size} levels): %.1f ms".format(Locale.ROOT, histogramMillis))
}

/** Best-of timing for the repeated filter runs; the timing lands in [lastBestMillis]. */
private var lastBestMillis: Double = 0.0

private fun bestOf(runs: Int, block: () -> Int): Int {
    var result = 0
    var bestNanos: Long = Long.MAX_VALUE
    repeat(runs) { run ->
        val startNanos: Long = System.nanoTime()
        result = block()
        val elapsedNanos: Long = System.nanoTime() - startNanos
        // Skip the first run: it is the JIT's, not the algorithm's.
        if (run > 0 && elapsedNanos < bestNanos) bestNanos = elapsedNanos
    }
    lastBestMillis = bestNanos / 1e6
    return result
}

private fun prepareFixture(targetBytes: Long): File {
    val fixture = File("spike/fixtures/withings-${targetBytes / (1L shl 20)}MiB.log")
    if (fixture.exists() && fixture.length() >= targetBytes * 0.98) {
        println("Fixture already present, reusing it.")
        return fixture
    }
    println("Generating ${targetBytes / (1L shl 20)} MiB fixture — one-off, then cached in ${fixture.parent} …")
    val startNanos: Long = System.nanoTime()
    val entryCount: Long = LogFileGenerator.generate(fixture, targetBytes)
    println("  wrote $entryCount entries in %.1fs".format(Locale.ROOT, (System.nanoTime() - startNanos) / 1e9))
    return fixture
}

private fun usedHeapBytes(): Long {
    val runtime: Runtime = Runtime.getRuntime()
    System.gc()
    Thread.sleep(120)
    System.gc()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun parseSize(raw: String): Long {
    val normalised: String = raw.trim().lowercase(Locale.ROOT)
    val multiplier: Long = when {
        normalised.endsWith("g") -> 1L shl 30
        normalised.endsWith("m") -> 1L shl 20
        else -> 1L
    }
    val digits: String = normalised.trimEnd('g', 'm')
    return (digits.toDoubleOrNull() ?: error("Cannot read a size from '$raw'")).toLong() * multiplier
}
