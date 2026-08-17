# Loupe — a structured log viewer for macOS

Kotlin/JVM + Compose Multiplatform. Reads a log file (or a folder of them), recognises its format
from a declarative profile, and turns the structure the logger already wrote into **facets, counts
and a brushable timeline** — the Datadog experience, on a local file, with no server.

Born from `LogViewerActivity` in the Withings HealthMate Android app, which had the right idea on a
6" screen and the wrong pipeline (`List<String>` + `filter { contains }`). The bundled
`withings-healthmate` profile is the reference format; nothing about it is compiled in.

**State: M0 – M4 done.** 127 tests, `main`, no remote yet. Docs in `docs/` (French);
the code, README and profiles are English.

---

## Layout

| Module | Owns | Depends on |
|---|---|---|
| `core/` | Everything that is not a pixel: profiles, indexing, queries, merging | ktoml, coroutines, kotlinx-serialization |
| `desktop/` | Compose window, state holder, panels | `:core`, `compose.desktop.currentOs` |
| `spike/` | Fixture generator + benchmark harness | `:core` |
| `profiles/` | Bundled `*.logprofile.toml`, copied into the jar under `/profiles/` | — |

`generateProfileIndex` writes `/profiles/index.txt` so `ProfileRegistry.bundled()` can enumerate
them from inside a jar. It is generated, never hand-edited.

**A profile paired with an adapter is pinned, not detected.** The adapter wrote the text, so it
names the profile that reads it (`CanonicalSourceAdapter.emittedProfileName`), and those profiles are
excluded from scoring. Two of them competing used to be settled by hand-written `priority` values.
Their layout is declared once in `CanonicalLine` and their regexes derived from it — writer and
profile are two halves of one format, and `AdapterProfilePairingTest` holds them together.

**A profile describes lines; JSON is not lines you can regex.** An Android Studio `.logcat` export
is one JSON document holding every message; NDJSON is one object per line, which *looks* regexable
but would leave `\"` and `\/` in the message — nothing downstream unescapes — and would make the
key order load-bearing. Both go through a `SourceAdapter` (`core/source/`), which decodes the file
into text *before* detection and indexing — the temporary copy takes the original's name and is deleted on `LogSource.close()`,
while `LogSource.files` keeps the paths the user chose so reopening still works. Same pattern for
`.gz`/`.zip` when they land. Adding an adapter is a deliberate act: it claims a whole file format.

Packages are the responsibility buckets below — don't invent a new one without a reason:

```
dev.loupe.core.{index, io, parse, profile, query, source}
dev.loupe.desktop.{format, state, theme, ui}
```

## Conventions

In `.claude/rules/` — `kotlin-style`, `code-comments`, `testing`, `performance`, `desktop-ui`,
`dependencies`, `git-workflow`. They are **path-scoped**, so each loads when you touch the code it
governs; this file loads always. Where the two overlap it is on purpose: the invariants below are
the always-on safety net, the rules carry the detail and the examples.

---

## Invariants — break these and the design stops working

Each of these was decided for a reason that is not obvious from the code alone. The reason is in
the KDoc at the site; this is the index.

**Memory and speed**
- **The text never enters the heap.** The index stores `(fileId, byteOffset, byteLength)`; files are
  memory-mapped and only the ~40 rows on screen are ever turned into `String`s. 30 bytes/entry,
  independent of line length.
- **Chunked `ByteArray` reads for the sequential pass, `mmap` for random access.** A per-byte `get()`
  on a `MappedByteBuffer` keeps its bounds check and does not vectorise; a `ByteArray` scan does.
- **Continuation is tested *before* the parse regex.** 18.6 % of lines in a real HealthMate file are
  continuations; this is the single biggest lever on the indexing budget. Reversing the two branches
  in `LogIndexer` turns one expensive match per entry into one per line.
- **A `ValueDictionary` is fed chars *or* bytes, never both** — they hash differently. Enforced, not
  documented, because mixing them corrupts the slot table silently.
- **The merge never sorts.** Each file is already ascending, so `IndexMerger` is a k-way merge over a
  binary heap. It remaps dictionary ids rather than re-interning.

**Determinism**
- **Tests are pinned to `user.timezone=Europe/Paris`** in the root build, and the compiler runs with
  `allWarningsAsErrors`. Both exist because CI caught what a laptop could not: a test that agreed
  with itself only under CEST, and warnings nobody read.

**Packaging**
- **jpackage copies a JDK into the bundle**, so the build JDK's provenance becomes the app's. The
  Compose plugin refuses Homebrew's outright and is right to; Gradle provisions an Adoptium 17 into
  `~/.gradle/jdks`, but only when a packaging task was asked for.
- **Apple forbids a leading zero in an app version**, so the macOS bundle says 1.0.0 while the
  project says 0.1.0. That number is Apple's; the human one is the tag and the file name.
- **One `.dmg` per architecture.** Skiko ships one native library per arch and there is no universal
  binary, so an arm64 build will not launch on an Intel Mac.
- **Both CI systems call `tools/package-dmg.sh`.** GitHub Actions and GitLab CI each have a config,
  and the packaging steps live in one script so they cannot drift.
- **GitLab is self-hosted: Docker runners for build, a Mac mini shell runner for packaging.** A
  shell executor keeps its working directory between builds, which is why the script empties
  `build/release` — otherwise the previous tag's `.dmg` is still there when the release job globs
  the folder. It keeps `~/.gradle` too, which is both the cache and where signing credentials live.
- **Signing is off unless `loupe.signing.identity` is set** in that machine's
  `~/.gradle/gradle.properties`. A self-hosted Mac is the one place notarising is easy — no secret
  ever reaches the repository. Reviewed, never executed; there is no certificate here.
- **63 MB is the floor**, measured: ~30 MB Skia, ~35 MB AWT runtime, the rest Compose and Kotlin.
  jlink already keeps only seven modules. Do not go looking for something to trim.

**Formats**
- **A timestamp with no year assumes one, and says so.** logcat and syslog both write `MM-dd`. The
  loader warns rather than inventing a date in silence, and `assume_year` overrides it.
- **A captured group shorter than its layout skips the fields that do not fit.** An optional
  `(?:\.\d{3})?` yields 19 characters instead of 23; reading the slot anyway pulls whatever follows
  in the line and calls it a number.
- **`generic-timestamped` has priority 0 and min_match 0.90.** Detection sorts by score and breaks
  ties on priority, which is the only reason a catch-all can be shipped at all.
- **`core` never reads the home directory; the app does.** `LogSourceLoader` defaults to the bundled
  registry, and `LoupeState` passes `bundledPlusUser()`. Otherwise every test would silently depend
  on what happens to sit in `~/.loupe/profiles` on the machine running it.
- **A broken user profile is reported, never fatal.** Someone writing one has a syntax error in it
  half the time, and refusing to open anything would break the exact task the feature exists for.

**Semantics**
- **`entry.continues` is exact; `entry.opens` is a *necessary condition only*.** The parse regex runs
  right after `opens` and has the final say, which is what lets a positional pre-filter be derived
  from a regex no literal prefix can express. Never make `opens` a `RegexMatch` — a regex in front of
  a regex costs two `String` allocations and buys nothing.
- **The `file` is a facet, not a column.** `file:2026-06-02` then works in the query language for
  free, and a single open file pays nothing for it.
- **A profile reports every problem at once**, at load time. Hand-written profiles are usually wrong
  in more than one way on the first try.
- **Unrecognised lines are counted in full and sampled by shape.** The count says a profile is
  imperfect; the shape says which part of it is wrong. Sampling is capped *per shape*, so the one
  that dominates cannot crowd out the one that explains the problem.

**UI**
- **The query text is the single source of truth.** Ticking a facet does not update a hidden
  selection model — `QueryEdits` splices the text you can see. That is how `level>=W` gets learned
  without reading a grammar. Anything the splice does not understand (a phrase, a regex, a
  deliberately-typed `-category:Ui`) survives in place; a negated term is never rewritten.
- **Every control is counted with its own constraint lifted.** The number beside `Wpp` is what you
  would get *by clicking it*; the timeline draws with the time window lifted and marks the selection
  with a band. Counting over the current result set shows the answer where the question belongs, and
  removes the context that made the control worth using.
- **Rows are one line tall, always.** A wrapping row makes every scroll position a layout pass, which
  kills the virtualised list. Full text goes in the detail pane; stack traces expand on demand.
- **Only Warn and Error carry colour.** Seven lines in ten are Debug; colouring every level is the
  same as colouring none.
- **`Results` carries the query it was computed for.** That is what makes the "catching up" indicator
  honest instead of a boolean somebody forgot to clear.
- **Never `fillMaxSize()` a `VerticalScrollbar`.** It then covers the list and eats every click and
  gesture. Cost us both "cannot scroll" and "clicking does something weird". `fillMaxHeight()`.

---

**Core types are declared stable in `compose-stability.conf`.** `:core` has no Compose dependency —
the engine must not know what a pixel is — so the compiler cannot see that an index or an open source
is written once and then only read. Without the file, twenty composable parameters were unstable and
their bodies re-ran whenever anything above them recomposed. One remains, `counts: IntArray`, and it
is honest: the array really is mutable. Measure rather than assume:
`./gradlew :desktop:compileKotlin --rerun-tasks -Pcompose.reports=true`.

## Performance budget

Measured on 1 GiB / 9,013,588 entries, Apple M5 Pro, JDK 17. Full method in `docs/m0-perf-spike.md`.

| | Target | Actual |
|---|---|---|
| Indexing | < 5 s at 5 M entries | 411 ns/entry → **2.06 s** |
| Facet filter | < 100 ms | **1–6 ms** (18 workers) |
| Full-text search | < 500 ms | **41–117 ms** parallel, 527–659 ms sequential |
| Index memory | RSS < 500 MB | **30 bytes/entry** — 258 MiB at 9 M |

Reference points: the hardcoded M0 parser was 386 ns/entry, so genericity costs ~6 %. The
hand-written `ByteScannerEntryParser` is 152 ns/entry — it is the floor and a second opinion in the
tests, **not** a shipping path. If a profile ever becomes a measured hot spot, that is the shape its
compiled form takes.

### Benchmarking: one strategy per JVM

```bash
./gradlew :spike:run --args="1g A"   # generic profile parser
./gradlew :spike:run --args="1g C"   # byte scanner
./gradlew :spike:run --args="1g"     # both — for the CROSS-CHECK only, never for timings
```

Running several parsers in one JVM makes shared call sites polymorphic and has produced a **phantom
2× slowdown twice now**. The harness prints the warning itself. Do not quote a number from a
combined run.

---

## Commands

```bash
./gradlew test                                   # 127 tests, all three modules
./gradlew :desktop:run --args="~/logs"           # open a file or folder; no arg = empty window
./gradlew :desktop:packageDmg                    # unsigned .dmg (63 MB, arm64 or x64 per host)
./gradlew build                                  # must be warning-free
```

A multi-day fixture folder lives in `spike/fixtures/` (gitignored, regenerate on demand). It should
contain several day files with overlapping timestamps plus one file the profile does *not*
recognise, so it exercises the merge and the skip path.

---

## Where to read

| | |
|---|---|
| `docs/m0-perf-spike.md` | Why the engine looks like this, and the numbers behind every claim. |
| `docs/m1-core.md` | The profile system, and the exact-vs-necessary predicate distinction. |
| `docs/m2-ui.md` | The three UI forks, decided, and what the screen deliberately does not do. |
| `docs/m3-product.md` | Selection, keyboard, context and export — and why the clipboard is capped. |
| `docs/m4-public.md` | The bundled profiles, and the two bugs writing them found. |
| `docs/profiles.md` | How to write a profile. The public-facing one; English. |
| `docs/packaging.md` | Why the packaging config looks like that, and the notarisation checklist. |
| `profiles/withings.logprofile.toml` | The reference profile, heavily commented. |

---

## M3 scope, and known gaps

**Accepted, not a gap:** `MappedText` maps a file in one go, so a single file is capped at 2 GiB.
A HealthMate day file is tens of megabytes and the cap is a whole folder's worth of them; segmenting
the mapping would add a boundary case to every read for a file nobody has. Revisit if one shows up.

- **Multi-select and copy.** One click selects one entry, `↑`/`↓` move through the result. The
  click / `⇧`-click / `⌘A` model has to be hand-written: `SelectionContainer` over `LazyColumn` is
  unstable on Compose Desktop — a risk called before the first line of this was written.
- More shortcuts (`⌘F`, `⌘L`, `j`/`k`, `Page↑`/`Page↓` — `moveSelection` already does the work),
  export of the current filter, unfiltered ±N lines of context around the selected entry,
  horizontal scrolling of the list.
- Section markers (`=== … ===`) are counted but do not become a `source` facet.
- **No JSON profile**, deliberately. A regex-driven one would work for a single key order and be
  silently wrong for every other; JSON needs a field extractor, not an expression. Shipping one that
  lies about three formats to read one is worse than shipping none.
- **Apple notarisation.** Needs a Developer account, a Developer ID certificate and an
  app-specific password — the one step nobody but the author can take. `docs/packaging.md` has the
  checklist; the release workflow deliberately builds unsigned rather than shipping untested
  keychain scripting.
