---
description: Hot-path discipline — the project's thesis; measure before and after
paths:
  - "core/src/main/**/*.kt"
  - "spike/**/*.kt"
---

# Performance — this is the product, not an optimisation

A log viewer that takes ten seconds to open a file is a worse `grep`. Every structural choice in
`core/` exists to hold the budget in `CLAUDE.md`, and a change that quietly breaks one of them is a
regression even when every test still passes.

## The hot paths
Exactly two, and they are the only places this rule is strict:
1. **The indexing pass** — runs once per entry. Nine million times on a 1 GiB file.
2. **Filter and count evaluation** — runs once per entry per keystroke, times the number of facets.

Everything else (loading a profile, rendering forty rows, formatting a status bar) is cold. Do not
contort cold code for speed; readability wins there without argument.

## Rules on a hot path

**Allocate nothing per entry.** Reuse the sink, reuse the buffer, intern from the raw bytes. A
`String` per line is nine million strings.
```kotlin
// ✅ one ParsedEntry for the whole pass, overwritten per line
val sink: ParsedEntry = parser.newSink()
// ❌ three nine-million-int scratch buffers per keystroke, for a handful of counts
val scratch = IntArray(index.entryCount)
```
This is why `EntryFilter.accepts` is public: it lets a counting pass run without materialising a
match array at all.

**Columns of primitives, never a list of objects.** `LongArray` / `IntArray` / `ByteArray`, one per
field. A `List<Entry>` of nine million objects is a gigabyte of headers and a cache miss per read.

**Don't box.** `timestamps.take(n).minOrNull()` boxes nine million `Long`s — write the loop. The
same goes for `Map<Int, T>` and `List<Int>` in any per-entry structure.

**Order predicates by cost.** Cheap array reads first, then anything that touches the file, then
anything that decodes. Each stage only ever sees what the cheaper ones already accepted.

**Parallelise what touches the file.** Full-text search missed its target at 659 ms sequential and
made it at 41–117 ms across 18 workers. For anything reading bytes, `evaluateParallel` is the
required path, not the optimisation.

## Prove it, then write the number down
A hot-path change lands with a measurement, and the measurement goes in a comment or in `docs/`.
"This should be faster" is not a reason; it is how the `entry.opens` regex pre-filter got written
the first time, and it was **slower than no pre-filter at all**.

```bash
./gradlew :spike:run --args="1g A"   # generic profile parser
./gradlew :spike:run --args="1g C"   # hand-written byte scanner — the floor
```

## Benchmark discipline — one strategy per JVM
Running several parsers in one process makes shared call sites polymorphic, the JIT stops
specialising, and you get a **phantom 2× slowdown**. This has happened twice: strategy B read 613
ns/entry in a combined run and 325 alone; the byte scanner read 315 and 152. The harness prints the
warning itself.

**A combined run is for the cross-check, never for a number.** If you are about to quote a timing,
check which run it came from.

## Where the ceiling actually is
The hand-written `ByteScannerEntryParser` is 152 ns/entry against the generic parser's 411. That
factor of 2.7 is the known, measured price of staying declarative — and it is worth paying. The
scanner is a benchmark floor and a second opinion in the tests. **It is not a shipping path**, and
compiling a profile into one is a last resort for a hot spot that has been measured, not suspected.
