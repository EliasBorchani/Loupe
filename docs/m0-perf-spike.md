# M0 — the performance spike

**Verdict: the declarative design holds. Risk number one is closed.**

One question was asked: can a generic engine driven by regex profiles index ~5 M entries in under 5 s
on the JVM, or does declarative have to be abandoned for hand-compiled scanners? The answer is that
regex passes with a factor of 2.6 to spare.

---

## Protocol

| | |
|---|---|
| Machine | Apple M5 Pro, 18 cores, 48 GiB |
| JVM | OpenJDK 64-Bit Server VM 17.0.19 (Homebrew), `-Xmx4g -XX:+UseG1GC` |
| Fixture | 1024 MiB generated, **9,013,588 entries**, 11,066,525 lines |
| | of which 2,052,937 continuation lines (**18.6 %**), 22 categories, 817 distinct tags |
| Measurement | 3 passes per strategy, **one JVM per strategy**, best warm pass kept |

The fixture (`spike/src/.../LogFileGenerator.kt`) reproduces the format
`FileLogger.LineFormat.render` writes and, more to the point, its awkward cases: heavy-tailed
categories, R8-obfuscated tags, messages containing their own ` -> `, messages opening with `[`,
lines with no category, the `ERROR`/`CRASH` pseudo-tags, multi-line messages and stack traces
re-indented by 23 spaces, plus 1 % accented messages to exercise UTF-8.

**One JVM per strategy, and that is not a detail.** Measured together they contaminate each other:
`Matcher`'s `charAt` call site becomes bimorphic — both a `String` and a hand-rolled `CharSequence`
pass through it — and strategy B came out at 613 ns/entry. Isolated, it does 325. The first number
was an artefact of the harness, not a property of B.

---

## Indexing

| Strategy | Warm | ns/entry | MiB/s | Extrapolated to 5 M | 5 s budget |
|---|---:|---:|---:|---:|:---:|
| **A · `String` + `Pattern`** | 3.48 s | 386 | 295 | **1.93 s** | ✅ |
| **B · widened chars + `Pattern`** | 2.93 s | 325 | 350 | **1.63 s** | ✅ |
| **C · byte scanner** | 1.41 s | 157 | 725 | **0.79 s** | ✅ |

All three produce a **strictly identical** index — checked entry by entry across all 9,013,588
entries (timestamp, level, category, tag, byte range), plus 31 golden tests on the awkward shapes. A
strategy that is fast and wrong is worth nothing, so the comparison runs inside the harness.

> **B was dropped after M0** and is no longer in the harness, which now takes `A` or `C`. It was
> measured here, so it stays in the table; the decision that removed it is number 2 below.

## Memory

| | |
|---|---|
| Measured heap delta | **258 MiB** for 9.01 M entries, i.e. **30.0 bytes/entry** (first estimate: 33) |
| Text | off-heap — the 1 GiB stays in the file, reached by `(offset, length)` |
| Extrapolated to 5 M | ≈ **143 MiB** |
| RSS target < 500 MB | ✅ |

## Filtering

18 workers on `Dispatchers.Default`, over all 9.01 M entries.

| Query | Matches | 1 thread | Parallel | Target |
|---|---:|---:|---:|:---:|
| `level>=W` | 926,146 | 7.8 ms | **1.3 ms** | < 100 ms ✅ |
| `category:Sync` | 2,105,017 | 22.9 ms | **6.4 ms** | < 100 ms ✅ |
| `level>=W category:Sync` + 1 h window | 2,910 | 8.0 ms | **1.0 ms** | < 100 ms ✅ |
| full text `"connected"` | 899,314 | 658.7 ms | **116.5 ms** | < 500 ms ✅ |
| `level>=W "backoff"` | 89,655 | 224.7 ms | **18.7 ms** | < 500 ms ✅ |
| Timeline histogram (2000 buckets × 5 levels) | — | 18.0 ms | — | — |

Full text is the only case that **fails single-threaded** — 658 ms against a 500 ms target — and
passes comfortably once spread out. Parallelism is therefore not an optimisation to keep for later;
it is a requirement.

## The merged path

Added after the fact, because the harness measured only a single file indexed with a parser handed
to it — while the app calls `LogSourceLoader.open`, which scores every bundled profile against the
largest file, indexes each file separately and k-way merges them by timestamp. The published budget
described strictly less than the tool does.

From one 64 MiB run, three day files that overlap at midnight, 563,770 entries:

| | ns/entry |
|---|---:|
| Single file, profile parser | 389 |
| Full `open()` — detect, index each, merge | **520** |

So detection plus the merge costs about **a third more** than indexing alone. Both numbers come from
the same JVM, which the harness itself warns is not the number to quote for a single strategy — the
ratio is what this measures, not the absolute.

A folder is also the only place the merge's dictionary remapping happens, so the run asserts what it
cannot measure: the merged stream is ascending, and the synthetic `file` facet is present.

---

## Decisions

1. **The engine stays declarative.** No hand-written scanner on the nominal path.
2. **Strategy A ships** (`String` + `Pattern`). B's 16 % lead does not pay for itself: it widens
   bytes into chars, which turns the message into mojibake inside the match buffer and requires every
   indexed field to be ASCII. A assumes neither.
3. **C stays documented as the escape hatch.** A factor of 2.2 over A; if a profile ever becomes a
   proven hot spot it can be compiled — not before. It is also the differential oracle that keeps the
   profile engine honest, which turned out to be the better reason to keep it.
4. **The structural pre-filter carries the result.** 18.6 % of lines are continuations, rejected in
   ~6 byte comparisons before the regex runs. In M1 that pre-filter had to be **derived from the
   profile** — a literal prefix, fixed-position characters pulled out of `entry.opens` — rather than
   hardcoded as it was here.
5. **`mmap` loses on the sequential pass and wins on random access.** A per-byte `get()` on a
   `MappedByteBuffer` keeps its bounds check and does not vectorise; a `ByteArray` scan does. Hence
   8 MiB block reads to index, and a mapping to read back and search.
6. **One benchmark, one JVM per strategy**, or you are measuring the JIT's type profile.

## What the spike did not cover, and what became of it

- `MappedText` caps at 2 GiB (one mapping). **Still true** — but the loader now refuses such a file
  up front instead of after indexing it.
- The pre-filter and the regex were Withings', hardcoded: no TOML loading yet. **Shipped in M1.**
- The zone was fixed when the parser was built; it had to come from the profile. **Shipped in M1.**
- No multi-file merge and no markers (`=== … ===`, `--- older lines dropped ---`) — both counted as
  "unrecognised", which was the right default and not the final behaviour. **The merge shipped and is
  measured above.** Markers are recognised and counted; a section marker still does not become a
  `source` facet.

## Reproducing

```bash
./gradlew :spike:run --args="1g"       # both surviving strategies, cross-checked, then the merge
./gradlew :spike:run --args="1g A"     # one strategy in a clean JVM — the numbers above
./gradlew :core:test                   # the golden tests on the format's awkward shapes
```

Fixtures are generated on first run (~2 s for 1 GiB) and cached in `spike/fixtures/`.
