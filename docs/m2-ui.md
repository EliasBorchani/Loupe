# M2 — the screen

**The app runs.** Compose Multiplatform, a macOS window, opening a folder, facets, the query bar, a
brushable timeline, a detail pane.

The three forks from the [design brief](https://claude.ai/code/artifact/59cda4e2-7101-44eb-acaf-1fcb5fb3a3ea)
are settled and built:

| Fork | Decision | Where |
|---|---|---|
| Columns or raw line | **Both**, columns by default, toggle bottom-right | `ui/LogList.kt` |
| Detail at the bottom or on the right | **Bottom** — log lines are wide | `ui/DetailPane.kt` |
| Merged folder or tabs | **One merged stream**, with a `file` facet | `index/IndexMerger.kt` |

---

## What makes the loop

**The query bar is the single source of truth.** Ticking a facet does not write into a parallel
selection model: it edits the text the user is looking at. That is how `level>=W` gets learned without
reading a grammar — you tick Warn, then Error, and the words appear.

Mechanically that is `QueryEdits` (`core/query/`): a textual splice over the tokens' spans, which
leaves untouched everything it does not understand. A phrase, a regex, a deliberately typed
`-category:Ui` — all come back where they were. A negated term is never rewritten; a new term is added
beside it, because silently inverting what someone typed would be worse than a redundant term. Twelve
tests pin that property.

**Facet counts are the ones you would get by clicking**, not the ones on screen. Counting against the
current result would show every other category at zero the moment you pick one — and there would be no
way to see what to switch to. So each facet is counted **with its own constraint lifted**
(`index/FacetCounts.kt`): one pass per facet, run in parallel off the UI thread.

**A result carries the query it was computed for.** That is the shape borrowed from
`LogViewerViewModel` on Android, and it is what makes the "catching up" indicator honest: the UI knows
that what it is showing is not what was asked for, instead of inferring it from a boolean somebody
forgot to reset.

---

## Rendering decisions

- **Rows are one line tall, always.** A wrapping row would make every scroll position a layout pass,
  which kills a virtualised list. The full text is one click away in the detail pane, and a stack
  trace unfolds in place on demand. Uniform height is the only reason nine million entries can scroll.
- **Text is decoded per visible row, never in bulk.** The index stores only byte ranges; only the
  forty rows on screen become `String`s.
- **Only W and E are coloured.** In a file where seven lines in ten are `D`, colouring every level is
  the same as colouring nothing.
- **The `file` facet gets no column.** A column of identical file names is wasted width; it lives in
  the sidebar, where it filters.
- **Timeline buckets span the whole file**, even when the query has narrowed it: a map that rescales
  under your feet is not a map.
- **The arrows walk the result, not the index.** `moveSelection(±1)` moves through `results.matches` —
  with `category:Sync` active, going down skips what the query excludes. At the ends it stops rather
  than wrapping: in a list of nine million, teleporting to the other end is never what you meant.
- **Scrolling follows the selection, it does not drive it.** A click and an arrow key scroll
  identically, and only when the selection reaches an edge — with one row of margin, so the next one
  is already on screen when you get to it.

---

## Architecture, as it stood at M2

```
desktop/
├─ Main.kt              window, drag and drop, open dialog, paths from the command line
├─ state/LoupeState.kt  StateFlow of inputs → combine + debounce → Results, off the UI thread
├─ theme/LoupeTheme.kt  colour (light/dark) and type tokens
└─ ui/
   ├─ LogList.kt        virtualised list, columns or raw line
   └─ Panels.kt         query bar, facets, timeline, detail, status bar
```

`Panels.kt` grew to 928 lines holding nine unrelated regions and was later split one file per region;
`Main.kt` gave up the screen graph and the AWT dialogs the same way. The current layout is in
`CLAUDE.md`.

The module depends on `compose.desktop.currentOs` and `:core`, and nothing else. No Material: the
components are built on `foundation`, which avoids dragging in a theme none of whose tokens would be
used.

---

## What is missing, and known to be

M3 delivered multi-select, copy, the keyboard, unfiltered context, export and horizontal scrolling —
see [`m3-product.md`](m3-product.md). Still open at the end of M2:

- **`⌘F` / `⌘L`** to focus the query bar. *(Shipped in M4.)*
- **One profile shipped.** `android-logcat`, `json-lines` and `syslog` were waiting for M4.
  *(All shipped.)*

## Trying it

```bash
./gradlew :desktop:run                            # empty window, drag a folder onto it
./gradlew :desktop:run --args="/path/to/logs"     # opens straight away
./gradlew test
./gradlew :desktop:packageDmg                     # .dmg, unsigned for now
```

A 120,000-entry folder across three days is built with the spike's generator; it exercises the merge,
the continuations, and the unrecognised file that has to be skipped.
