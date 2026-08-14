---
description: Testing — real fixtures over mocks, Given/When/Then, assert on what was selected
paths:
  - "**/*Test.kt"
  - "**/test/**"
---

# Testing

**JUnit 5. No MockK, and that is deliberate.** The units here are parsers, an index and a query
compiler — things whose collaborators are *files*. A `@TempDir` with four real log lines in it is
both faster to read and a stronger test than a stubbed reader, because it exercises the byte
offsets, the encoding and the line splitting that a mock would paper over. If you find yourself
wanting a mock, the thing you are testing probably wants a fixture instead.

## Structure — Given / When / Then
Split every test body with `// Given` / `// When` / `// Then` comments; collapse to `// When / Then`
when the call *is* the assertion. Function names stay descriptive backtick sentences.

```kotlin
@Test
fun `folds a multi-line message into one entry`() {
    // Given
    val file = write(
        "2026-07-22 12:00:00.000 [D] [Sync] [tag] -> first",
        "${INDENT}second",
    )

    // When
    val index: LogIndex = LogIndexer(parser).index(file)

    // Then
    assertEquals(1, index.entryCount)
    assertEquals(1L, index.continuationLineCount)
}
```

## Assert on what was selected, not how much
A filter returning the right *count* of the wrong entries is the failure that matters, and a count
assertion cannot see it. Assert on the values.

```kotlin
// ✅
assertEquals(listOf("pull-start", "pull-done", "push-retry"), messagesOf(select("category:Sync")))
// ❌ passes for any three entries
assertEquals(3, select("category:Sync").size)
```
Give each fixture line a message that names it (`before-midnight`, `gave-up`), so an assertion reads
as a sentence and a failure says which entry went missing.

## Format cases run through both parsers
Every golden case for the HealthMate format is a `@ParameterizedTest` over
`ProfileEntryParser` **and** `ByteScannerEntryParser`. Two independent implementations of the same
profile disagreeing is the cheapest way to catch a regex that quietly means something other than
what it reads like — and it is the only thing keeping the hand-written scanner honest.

## Cover the awkward shape, not the happy path
The happy path is what the code was written against. The tests that have earned their place are the
ones for the shapes that break assumptions: an optional group that did not participate, a message
containing the field separator, a value with a multi-byte character, an empty message, a line that
is not an entry at all. When you add a format feature, add its awkward case.

## Test the invariant, not the implementation
Where a property is what matters, assert the property:

```kotlin
// The pre-filter may over-accept; it must never under-accept.
lines.forEach { line ->
    if (pattern.matcher(line).matches()) assertTrue(accepts(predicate, line))
}
```

## What is not covered, and why
Layout. `LoupeStateTest` drives the whole interaction loop — open, type, tick a facet, brush the
timeline — without a window, because everything the UI does goes through `LoupeState`. What no test
sees is whether the result *looks* right; that is what running the app is for.
