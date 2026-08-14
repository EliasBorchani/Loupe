---
description: Kotlin style — explicit types, spelled-out names, class layout
paths:
  - "**/*.kt"
---

# Kotlin Style — REQUIRED

## Types — always explicit
```kotlin
// ✅
fun bytesPerEntry(facetCount: Int): Int = 8 + 1 + 8 + 4 + 4 * facetCount
val entryCount: Int = source.index.entryCount
// ❌ — inferred return / property type
fun bytesPerEntry(facetCount: Int) = 8 + 1 + 8 + 4 + 4 * facetCount
val entryCount = source.index.entryCount
```
Expression functions **must** declare their return type. Local `val`s inside a tight loop may infer
when the type is obvious from the right-hand side and the line would otherwise wrap.

## No abbreviations, no single-letter names
Spell identifiers out — including destructured and lambda parameters. `entry` not `e`, `ordinal`
not `o`, `bucketCount` not `n`, `{ facet -> … }` not `{ it }` past a one-liner.

```kotlin
// ✅
dictionary.idsByDescendingCount().forEach { id -> println(dictionary.valueOf(id)) }
// ❌
d.idsByDescendingCount().forEach { println(d.valueOf(it)) }
```
Established acronyms are fine and cased as a word: `Toml`, `Utf8`, `Dmg`, `Jvm`.

## Naming
- **Packages:** lowercase, single word, no underscores. `dev.loupe.<module>.<responsibility>` —
  `dev.loupe.core.index`, `dev.loupe.desktop.ui`. Don't invent a bucket; the set is in `CLAUDE.md`.
- **Constants:** `UPPER_SNAKE_CASE` (`const val MIN_ENTRIES_FOR_PARALLEL = 100_000`).
- **Enums:** `CamelCase` entries — `Detecting`, `Indexing`, `Merging`. Never `DETECTING`.
- **Serialised names:** the TOML profile format is `snake_case`; the Kotlin is `camelCase`. Bridge it
  with `@SerialName("min_match")`, never by naming the property `min_match`.

## Class layout (order matters)
1. `companion object` — never `private companion object`; make the members private instead.
2. Initialised properties (public then private).
3. Backing pairs together: `private val _source = MutableStateFlow(...)` then
   `val source: StateFlow<…> = _source.asStateFlow()`.
4. Non-initialised properties.
5. `init` blocks → secondary constructors → methods.
6. Private nested classes last.

Methods are grouped by relatedness, not alphabetically. Keep an extension next to what it extends.

## `when` — exhaustive
Cover every branch of a sealed type or enum. **No `else`** — it silently defeats exhaustiveness the
day a case is added, which for `OpenPhase` or `FieldRole` is exactly when you want the compiler to
speak up.

## Imports — no wildcards
```kotlin
// ❌ import androidx.compose.foundation.layout.*
// ✅ import androidx.compose.foundation.layout.Row
```

## Everyday gotchas
- **`?: run { … }`**, never `?: { … }` — the latter returns the lambda instead of running it.
- **`requireNotNull(x) { "…" }`** — always with a message naming what was null and why it should not
  have been.
- **No magic sentinel for an absent value** where `null` will do. Where a primitive column genuinely
  cannot hold `null`, use a **named** constant — `LogIndex.NO_VALUE`, `CompiledProfile.NO_GROUP`,
  `LevelDecoder.UNKNOWN_ORDINAL` — never a bare `-1` at the call site.
- `data object` inside a sealed hierarchy; `Enum.entries`, never `values()`.
- `items.maxOf { it.count }` over `items.maxBy { it.count }.count`; the `…OrNull` form when empty is
  possible.
- Lambda parameters are lowercase: `{ source -> … }`, never `{ Source -> … }`.
