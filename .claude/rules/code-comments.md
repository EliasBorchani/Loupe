---
description: Comment policy — say what the code cannot, especially what a measurement showed
paths:
  - "**/*.kt"
  - "**/*.kts"
  - "**/*.toml"
---

# Code Comments

## Default: none
If the code is self-evident, don't comment it. Never paraphrase the next line, narrate the obvious,
or address the reviewer ("now uses X", "fixed per feedback") — that belongs in the commit body.

```kotlin
// ❌ restates the code
// increment the matched counter
matched++
```

Exception: the `// Given` / `// When` / `// Then` markers required by `testing.md` always stay.

## This codebase comments more than most, for one reason
Most of its structure was chosen from a **measurement**, and the measurement is invisible in the
result. A comment that carries the number is the difference between a decision and an accident:

```kotlin
// Continuation is tested first, before the parse regex is ever reached. M0 measured 18.6 % of
// lines in a real HealthMate file as continuations, so nearly a fifth of the file is dismissed in
// a handful of byte comparisons instead of a regex match.
```

Write one when it states something the code cannot:
- **A measurement** — the number, and where it came from (`M0 measured…`, `docs/m1-core.md`).
- **A branch order that matters** — swap these two and the cost changes by an order of magnitude.
- **A guard against a specific failure** — name the failure, not the guard.
- **A workaround** — the upstream bug or platform quirk, and when it can go.
- **Already-commented code you touch** — keep it, or enrich it. Never strip it as noise.

## Magic values — say what the value means
```kotlin
private const val CONTINUATION_INDENT = 23   // ❌ says nothing the name did not
```
```kotlin
/** `yyyy-MM-dd HH:mm:ss.SSS` — 23 characters, and therefore the continuation indent. */
const val TIMESTAMP_LENGTH: Int = 23         // ✅ says where the number comes from
```
A value taken from an external contract (a log format, a JVM limit, a spec) carries its source.

## Keep comments in sync — non-negotiable
When you change code that has a comment on it, re-read the comment and update it. A stale comment
is worse than none: it actively misleads, and here it will usually be misleading about *why*.

## No issue numbers in comments
Traceability lives in the commit and the PR. The single exception is a `TODO` pointing at work that
is genuinely queued — `// TODO(M3): hand-written selection model` — and it disappears with the work.
