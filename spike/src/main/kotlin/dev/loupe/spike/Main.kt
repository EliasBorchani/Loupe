package dev.loupe.spike

import dev.loupe.core.index.EntryFilter
import dev.loupe.core.index.LogIndex
import dev.loupe.core.index.LogIndexer
import dev.loupe.core.io.TextSources
import dev.loupe.core.parse.ByteScannerEntryParser
import dev.loupe.core.parse.EntryParser
import dev.loupe.core.parse.ProfileEntryParser
import dev.loupe.core.profile.CompiledProfile
import dev.loupe.core.profile.ProfileMatch
import dev.loupe.core.profile.ProfileRegistry
import dev.loupe.core.query.CompiledQuery
import dev.loupe.core.query.QueryCompiler
import java.io.File
import java.util.Locale

/**
 * The benchmark harness, kept from M0 and re-pointed at the profile-driven engine.
 *
 * M0 asked whether a declarative regex engine could hold the indexing budget; it could, with a
 * hardcoded parser standing in for the profile. This now runs the real thing — TOML profile,
 * auto-detection, generic facet columns — so the answer survives the genericity that was the
 * whole point.
 *
 * Deliberately not JMH: a single multi-second pass over a 1 GiB file is `SingleShotTime`
 * territory, where JMH adds ceremony without adding signal, and running the strategies in one
 * process lets the same run check that they agree.
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

    val profile: CompiledProfile = detectProfile(fixture)
    println()

    // Running both in one JVM lets them cross-check each other, but it also makes `Matcher`'s
    // internal call sites polymorphic. Pass a single letter as the second argument to measure one
    // in a clean JVM — M0 found a 2× phantom slowdown that way.
    val selection: String? = args.getOrNull(1)?.uppercase(Locale.ROOT)
    val strategies: List<EntryParser> = listOfNotNull(
        ProfileEntryParser(profile).takeIf { selection == null || selection == "A" },
        ByteScannerEntryParser(profile).takeIf { selection == null || selection == "C" },
    )
    require(strategies.isNotEmpty()) { "Unknown strategy '$selection' — expected A (profile) or C (byte scanner)." }

    val results: List<StrategyResult> = strategies.map { parser -> measureIndexing(parser, fixture) }
    printIndexingReport(results, fixture.length())
    if (results.size > 1) verifyStrategiesAgree(results)

    val reference: LogIndex = results.first().index
    printCorpusShape(reference)
    TextSources.of(fixture).use { text -> printQueryReport(reference, text) }
}

private fun detectProfile(fixture: File): CompiledProfile {
    val registry: ProfileRegistry = ProfileRegistry.bundled()
    val matches: List<ProfileMatch> = registry.detect(fixture)
    println("Profiles ${registry.profiles.size} bundled — ${registry.profiles.joinToString { profile -> profile.name }}")
    if (matches.isEmpty()) {
        error("No bundled profile recognises ${fixture.name}")
    }
    matches.forEach { match -> println("         ${if (match === matches.first()) "→" else " "} $match") }
    val chosen: CompiledProfile = matches.first().profile
    chosen.warnings.forEach { warning -> println("         ! $warning") }
    return chosen
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

    if (results.size > 1) {
        println()
        println("  !! These timings ran several strategies in one JVM, which makes shared call sites")
        println("     polymorphic and has twice now produced a phantom 2x slowdown. Trust this run for")
        println("     the CROSS-CHECK below; for timings, re-run one strategy at a time:")
        println("       ./gradlew :spike:run --args=\"1g A\"   and   --args=\"1g C\"")
    }

    val budgetNanos: Long = 5_000_000_000L
    println()
    println("Extrapolated to the 5 M-entry target (budget ${budgetNanos / 1_000_000_000}s):")
    results.forEach { result ->
        val nanos: Double = result.warmNanos.toDouble() / result.index.entryCount * 5_000_000
        println("  %-30s %6.2fs   %s".format(Locale.ROOT, result.name, nanos / 1e9, if (nanos <= budgetNanos) "PASS" else "FAIL"))
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
    if (reference.unrecognisedLineCount != candidate.unrecognisedLineCount) {
        differences.add("unrecognisedLineCount ${reference.unrecognisedLineCount} vs ${candidate.unrecognisedLineCount}")
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
        var facetDiffers = false
        for (facetIndex in reference.facetValues.indices) {
            if (facetOf(reference, facetIndex, entry) != facetOf(candidate, facetIndex, entry)) {
                differences.add(
                    "facet '${reference.facets[facetIndex].name}' at entry $entry: " +
                        "'${facetOf(reference, facetIndex, entry)}' vs '${facetOf(candidate, facetIndex, entry)}'",
                )
                facetDiffers = true
                break
            }
        }
        if (facetDiffers) break
    }
    return differences
}

private fun facetOf(index: LogIndex, facetIndex: Int, entry: Int): String {
    val valueId: Int = index.facetValues[facetIndex][entry]
    return if (valueId == LogIndex.NO_VALUE) "<none>" else index.facetDictionaries[facetIndex].valueOf(valueId)
}

private fun printCorpusShape(index: LogIndex) {
    println()
    println("─".repeat(96))
    println("CORPUS SHAPE — what the scan knows before a single line is read")
    println("─".repeat(96))
    println(
        "  lines            ${index.lineCount}  (${index.continuationLineCount} continuations, " +
            "${index.sectionLineCount} sections, ${index.noticeLineCount} notices, " +
            "${index.unrecognisedLineCount} unrecognised)",
    )
    println("  recognised       ${"%.3f".format(Locale.ROOT, index.recognisedLineRatio * 100)} %")
    println("  levels")
    index.profile.levelDecoder?.labels?.forEachIndexed { ordinal, label ->
        println("      %-10s %10d".format(Locale.ROOT, label, index.levelCounts[ordinal]))
    }
    index.facets.forEachIndexed { facetIndex, facet ->
        val dictionary = index.facetDictionaries[facetIndex]
        println("  facet '${facet.name}' — ${dictionary.size} distinct values, top 6:")
        dictionary.idsByDescendingCount().take(6).forEach { id ->
            println("      %-24s %10d".format(Locale.ROOT, dictionary.valueOf(id), dictionary.countOf(id)))
        }
    }
}

/** Runs the query language end to end — the same path the query bar will take. */
private fun printQueryReport(index: LogIndex, text: TextSources) {
    println()
    println("─".repeat(96))
    println("QUERIES — over ${index.entryCount} entries, ${Runtime.getRuntime().availableProcessors()} workers when parallel")
    println("─".repeat(96))

    val compiler = QueryCompiler(index)
    val destination = IntArray(index.entryCount)
    val topCategory: String = index.dictionaryOf("category")
        ?.let { dictionary -> dictionary.idsByDescendingCount().firstOrNull()?.let(dictionary::valueOf) }
        ?: "Sync"

    val queries: List<String> = listOf(
        "level>=W",
        "category:$topCategory",
        "level>=W category:$topCategory since:-2h",
        "\"connected\"",
        "level>=W backoff",
        "-category:Ui level:E",
        "tag:~Session",
    )

    println("%-40s %10s %14s %14s".format(Locale.ROOT, "query", "matches", "1 thread", "parallel"))
    queries.forEach { query ->
        val compiled: CompiledQuery = compiler.compile(query)
        if (!compiled.isValid) {
            println("%-40s %s".format(Locale.ROOT, query, compiled.problems.joinToString("; ")))
            return@forEach
        }
        val sequentialMatches: Int = bestOf(FILTER_RUNS) { compiled.filter.evaluate(index, text, destination) }
        val sequentialMillis: Double = lastBestMillis
        val parallelMatches: Int = bestOf(FILTER_RUNS) { compiled.filter.evaluateParallel(index, text, destination) }
        val parallelMillis: Double = lastBestMillis
        check(sequentialMatches == parallelMatches) {
            "'$query': parallel found $parallelMatches matches, sequential found $sequentialMatches"
        }
        println(
            "%-40s %10d %11.1f ms %11.1f ms".format(
                Locale.ROOT, query, sequentialMatches, sequentialMillis, parallelMillis,
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
    // Named like a HealthMate day file so `detect.filename` has something real to corroborate.
    val fixture = File("spike/fixtures/${targetBytes / (1L shl 20)}MiB/2026-06-02")
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
