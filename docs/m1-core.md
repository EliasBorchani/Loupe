# M1 — the generic core

**Verdict: the engine is genuinely profile-driven, and genericity costs 6 %.**

M0 answered "yes" with a parser hardcoded for the Withings format. M1 replaces that parser with an
engine that knows no format at all: everything comes from a `*.logprofile.toml`.

---

## What shipped

| Piece | File | Role |
|---|---|---|
| Profile spec | `profile/LogProfileSpec.kt` | The TOML as written, deserialised, nothing compiled |
| Compilation + validation | `profile/CompiledProfile.kt` | Compiled regex, group numbers, level scale, predicates. **Reports every problem at once**, not the first |
| Group numbering | `profile/NamedGroups.kt` | `Matcher.start("name")` re-resolves the name on every call, and `Pattern.namedGroups()` only exists from Java 20. So the source is scanned |
| Timestamps | `profile/TimestampFormat.kt` | Compiles a fixed-width pattern into offsets, with a `DateTimeFormatter` fallback |
| Line predicates | `profile/LinePredicate.kt` | M0's pre-filter, derived from the profile instead of hand-written |
| Level scale | `profile/LevelDecoder.kt` | The declared order **is** severity, which is what makes `level>=W` expressible |
| Auto-detection | `profile/ProfileRegistry.kt` | Scored on a sample, never a silent choice |
| Generic parser | `parse/ProfileEntryParser.kt` | Replaces M0's strategy A |
| Generic index | `index/LogIndex.kt` | N declared facet columns, rather than "category + tag" hardcoded |
| Query language | `query/QueryLexer.kt`, `query/QueryCompiler.kt` | The query bar's grammar → `EntryFilter` |

The suite stood at **56 tests**, with every case of the Withings format replayed against both parsers.

---

## The distinction that makes a generic pre-filter work

M0 established that rejecting continuation lines before the regex is the main lever — 18.6 % of
lines. What remained was deriving it from a profile rather than writing it by hand.

The first attempt failed instructively. `entry.opens` (`^\d{4}-\d{2}-\d{2} …`) does not reduce to a
literal prefix, so it fell back to "run the regex" — which allocated a `String` per line **before**
the parser allocated a second one. Strictly worse than no pre-filter at all. The profile-compilation
test caught it immediately, by refusing a profile that produced a warning.

The way through is a distinction the code now makes explicit:

- **`entry.continues` is semantic.** Its answer decides whether a line joins the entry above or tries
  to open one. It has to mean *exactly* what its regex says → `compileExact`, which produces a
  literal prefix or, failing that, runs the regex and says so.
- **`entry.opens` is only an optimisation.** The real regex runs immediately after and settles it. So
  it need only be a **necessary condition**: it may accept lines that will not parse, and must never
  reject one that would have → `compileNecessary`, which derives positional constraints ("position 0
  is a digit, position 4 is a dash…") and stops at the first pattern it does not understand, keeping
  what it has. A partial prefix is still a valid necessary condition.

That is exactly M0's hand-written `opensEntry`, derived instead of coded. A test pins the property
that matters: over a corpus mixing every shape, no line the full regex accepts is rejected by the
pre-filter.

---

## Performance — genericity costs almost nothing

1 GiB, 9,013,588 entries, Apple M5 Pro, JDK 17, **one JVM per strategy**.

| | ns/entry | Warm | Extrapolated to 5 M | 5 s budget |
|---|---:|---:|---:|:---:|
| M0 — hardcoded regex parser | 386 | 3.48 s | 1.93 s | ✅ |
| **M1 — generic, profile-driven parser** | **411** | **3.71 s** | **2.06 s** | ✅ |
| Byte scanner (reference, unused) | 152 | 1.37 s | 0.76 s | ✅ |

**+6 % for complete genericity**: group numbers read out of arrays, a nullable level decoder, a loop
over N facets. The byte scanner is at 152 ns against 157 at M0 — unchanged, which confirms no
regression slipped into the shared path.

### Queries, 18 workers

| Query | Matches | 1 thread | Parallel |
|---|---:|---:|---:|
| `level>=W` | 926,146 | 8.6 ms | **1.3 ms** |
| `category:Sync` | 2,105,017 | 30.1 ms | **3.1 ms** |
| `level>=W category:Sync since:-2h` | 5,484 | 9.9 ms | **1.2 ms** |
| `"connected"` | 899,314 | 527.3 ms | **40.7 ms** |
| `level>=W backoff` | 89,655 | 95.7 ms | **9.1 ms** |
| `-category:Ui level:E` | 200,293 | 7.1 ms | **0.8 ms** |
| `tag:~Session` | 479,190 | 20.3 ms | **1.9 ms** |

---

## The measurement trap, a second time

Measured **together** in one JVM, the two strategies gave 333 and 315 ns — the generic parser looked
faster than M0's hardcoded one, and the byte scanner looked twice as slow as at M0. Both numbers were
wrong: passing two implementations through the same call sites makes them polymorphic and the JIT
stops specialising. It is the same phenomenon that gave strategy B 613 ns at M0.

The harness now prints the warning on every multi-strategy run. **The rule: a combined run is for the
cross-check, never for the numbers.**

---

## Bugs the tests caught

- **`MMM` read as a three-digit month.** The timestamp compiler accepted any pattern width, so
  `dd MMM yyyy` took the fast path and read "Jul" as month 3350. Widths are now constrained per
  letter (`y` exactly 4, `M d H m s` exactly 2, `S` from 1 to 9) and everything else falls back.
- **The fallback depended on the machine's locale.** `DateTimeFormatter.ofPattern` with no locale
  refused "Jul" on a French machine. Logs are written by programs: `Locale.ROOT`.
- **Offsets shifted by quoted literals.** `'T'` in `yyyy-MM-dd'T'HH:mm:ss` takes three pattern
  characters and one of text; counting in pattern characters shifted every field after it, in every
  ISO-8601 timestamp.
- **A non-ASCII value re-interned.** The byte path compared bytes against `char`s, so a facet holding
  a multi-byte character was re-interned on every occurrence — a duplicated facet entry, not a crash.

---

## Debt taken on for M2, and what became of it

- `MappedText` caps at 2 GiB (one mapping) — segmenting still to write. **Still true**, though the
  loader now refuses such a file before indexing it rather than after.
- No multi-file merge yet; section markers are counted but do not become a `source` facet.
  **The merge shipped in M2.** A section marker still does not become a facet.
- `[fields.x] values` serves validation and ordering, but does not yet report an out-of-list value.
  **This was not true when it was written** — the key was deserialised and read by nothing, for four
  milestones. It was removed rather than implemented; see `docs/profiles.md`.
- One profile shipped. `android-logcat`, `json-lines`, `syslog` and `generic-timestamped` are expected
  at M4 — and each will exercise the timestamp compiler's fallback, which has only one test today.
  **All shipped**, and `json-lines` turned out to need a source adapter rather than a profile.

## Reproducing

```bash
./gradlew :core:test
./gradlew :spike:run --args="1g A"     # generic parser, clean JVM — the numbers above
./gradlew :spike:run --args="1g C"     # the reference scanner
./gradlew :spike:run --args="1g"       # both, plus the cross-check (not for timings)
```
