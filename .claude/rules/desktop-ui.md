---
description: Compose Desktop conventions — tokens, parameter order, state holder, and the traps
paths:
  - "desktop/**/*.kt"
---

# Desktop UI

## No Material — `foundation` plus our own tokens
The only Compose dependency is `compose.desktop.currentOs`, and the UI is built on
`androidx.compose.foundation`. `BasicText`, `BasicTextField`, `Canvas`, `Box`/`Row`/`Column`.
Pulling in Material would drag a whole theme in whose tokens we would then override everywhere.

**No hardcoded colours or dimensions.** Everything comes from `theme/LoupeTheme.kt`:
```kotlin
// ✅
color = LoupeTheme.colors.inkSecondary
modifier = Modifier.padding(Spacing.medium)
style = LoupeTheme.type.monoSmall
// ❌
color = Color(0xFF4C5F5A)
modifier = Modifier.padding(10.dp)
```
No matching token? Add one to `LoupeTheme` / `Spacing`. Both themes get the same care — a colour
that only works on one ground is a bug, not a shortcut.

**Severity colour is not the accent.** `colors.inkForLevel` / `colors.surfaceForLevel` decide it,
and only the top two levels get a hue. Seven lines in ten are Debug.

## Parameter ordering
1. Required params, **including plain lambdas** (`onSelect: (Int) -> Unit`) — never trailing.
2. `modifier: Modifier = Modifier`, first among the defaulted ones, passed straight through.
3. Other defaulted params.
4. A single `@Composable` content lambda may go last, after `modifier`. Two or more stay required
   and get named at the call site.

## State
- **No `Pair` in state** — a named class, always.
- **No Compose type in `LoupeState`.** No `Color`, no `Dp`, no resource of any kind. The state
  carries domain values (`Results`, `OpenStatus`, `ViewMode`) and the composables map them.
- One `StateFlow` per concern, exposed read-only via `asStateFlow()`; mutations go through named
  methods (`setQuery`, `toggleFacetValue`, `select`), never a public `MutableStateFlow`.
- **Derived state is declarative:** `combine(...).mapLatest { compute(it) }.flowOn(Dispatchers.Default)
  .stateIn(scope, Eagerly, null)`. Filtering never runs on the UI thread.
- **A result carries the inputs it was computed for.** `Results.query` is what makes
  `isCatchingUp()` honest instead of a boolean somebody forgot to clear. Keep that shape when you
  add an input.

## The list is the performance surface
- **Rows are one line tall, always.** A wrapping row makes every scroll position a layout pass,
  which is what kills a virtualised list. Long text goes to the detail pane; continuations expand
  on demand.
- **Key `items` by entry**, not by position, so narrowing a query keeps what you were looking at in
  view instead of holding a row number.
- **Decode per visible row.** `EntryRenderer.render(index, text, entry)` inside a `remember(entry)`,
  never a pass over the result set.

## Traps already paid for
- **`VerticalScrollbar` takes `fillMaxHeight()`, never `fillMaxSize()`.** Stretched over the list it
  sits on top of every row and swallows the clicks and drags meant for them — it cost us both
  "cannot scroll" and "clicking does something strange", from one modifier.
- **`Modifier.padding` has two overloads that do not mix.** `(horizontal, vertical)` or
  `(start, top, end, bottom)`; `padding(horizontal = …, bottom = …)` does not compile.
- **A pane gets a definite height, not a maximum**, when it contains something scrollable — the
  child needs a bound to fill, and a pane that resizes per selection makes the list jump under the
  pointer.
- **`SelectionContainer` over `LazyColumn` is unstable on Compose Desktop.** The selection model is
  hand-written. Do not reach for it as a shortcut.
- Coordinates in `pointerInput` are relative to whatever the modifier chain has already applied.
  Put `.padding()` before `.pointerInput()` and the drag maths matches the `Canvas` draw scope.
- **Keys that a scrollable might also want go on `onPreviewKeyEvent`, not `onKeyEvent`.** Preview
  runs down towards the focused node; the bubbling pass runs after whoever had focus already had
  its say, so an arrow can be read as a scroll or a focus move before you ever see it. Scope it to
  the container that owns the interaction — the list's Box does not wrap the query bar, so a cursor
  in the text field is untouched.

## Every control is counted with its own constraint lifted
The number beside a facet value is what you would get **by clicking it**; the timeline draws with
the time window lifted and marks the selection with a band. Counting over the current result set
shows the answer where the question belongs and removes the context that made the control usable.
`FacetCounts` does this for all of them — a new control follows the same rule.
