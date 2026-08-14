---
description: Dependency budget — three, each argued; how to justify a fourth
paths:
  - "**/build.gradle.kts"
  - "gradle/libs.versions.toml"
  - "settings.gradle.kts"
---

# Dependencies

**Default to not adding one.** Prefer the standard library, something already here, or thirty lines
written in place. Every dependency costs build time, artifact size, supply-chain surface and a
future upgrade you will have to do at a bad moment. For a tool whose selling point is that it opens
instantly with no server, the bar is higher than usual.

## What is in, and why each earned it

| | Why it, and not something else |
|---|---|
| `com.akuleshov7:ktoml` | The only genuinely third-party runtime dependency. TOML with tables, arrays of tables, inline tables and multi-line literals is not worth hand-rolling. Chosen over `tomlj` because it is pure Kotlin and multiplatform — an ANTLR-based Java parser would close the door on Compose's native targets. |
| `kotlinx-coroutines` | First-party. Parallel filter evaluation and the state holder. |
| `kotlinx-serialization` | First-party. Decodes the profile spec; ktoml's idiomatic API. |
| `compose.desktop.currentOs` | The UI. `foundation` only — see `desktop-ui.md` for why no Material. |

## Adding a fourth

1. **Say what it replaces.** If the answer is "fifty lines I would rather not write", write them.
2. **Check it is alive** — a release in the last year, a live issue tracker, more than one
   maintainer, and compatibility with the current Kotlin and Gradle. An abandoned library is the one
   that blocks the next toolchain bump.
3. **Check it is multiplatform-safe** if it lands in `core/`. `core/` is pure Kotlin with no JVM-only
   API in its public surface beyond `java.io.File` and `java.util.regex`; keep that door open.
4. **Put the reason in the commit body**, not just the catalog. The table above is the record.

## Version catalog
Everything goes through `gradle/libs.versions.toml` — no inline coordinates in a `build.gradle.kts`.

Two versions are coupled and must move together:
- **The Compose compiler plugin tracks the Kotlin version exactly** (`version.ref = "kotlin"`). It
  ships with Kotlin; a mismatch fails at configuration time with an unhelpful message.
- **Compose Multiplatform** is versioned independently (`compose`), and its runtime is built on
  androidx artifacts — which is why `google()` is in `settings.gradle.kts` alongside `mavenCentral()`.
  Remove it and the desktop module stops resolving.

## The build must be warning-free
`./gradlew build` produces no `w:` lines. A deprecation warning is a small task now and a migration
later; fix it when it appears rather than letting a wall of them hide the one that matters.
