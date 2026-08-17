# M3 — the product

What was missing for the tool to be driven rather than consulted: selecting several lines, copying
them, moving by keyboard, seeing what surrounded a line, and getting the result out.

Nothing new on performance — all of M3 is interaction.

---

## Selection, hand-written

`SelectionContainer` over `LazyColumn` is unstable on Compose Desktop. The risk was called before the
first line of code was written, it has not moved, so the model is hand-made.

**It is a range, not a set**, because that is what the gestures produce: click, `⇧`-click, extend with
an arrow. And it is held in **positions within the result, not entry indices** — that is the part that
matters. "Everything between these two" means everything between them *on screen*: with
`category:Sync` active, the Wpp entries asleep in the interval are not selected and must not be
copied. That is the test.

The anchor stays where the selection began; the focus is the end that moves, and the row the detail
pane describes. That row reads one shade stronger than the rest of the range, so a long selection
still says where you are in it.

## The keyboard

Everything goes through the list's `onPreviewKeyEvent` handler, whose `Box` does not wrap the query
bar — a cursor in the text field is never touched.

| | |
|---|---|
| `↑` `↓` `j` `k` | move one row |
| `⇧` + any of them | extend instead of move |
| `Page↑` `Page↓` | one screen, computed from the rows actually visible |
| `Home` `End` | to the ends of the result |
| `⌘A` | select the result — not the file |
| `⌘C` | copy |

## The clipboard is capped, and says so

At 20,000 entries. A `⌘A` over nine million is gigabytes, and the clipboard is for pasting into a
ticket — the file is what export is for. **The cap is reported, never applied in silence**: a truncated
paste that says nothing is how a bug report loses its cause.

The cap is a default parameter, so the test can exercise it at 2 rather than by generating twenty
thousand lines of fixture.

## Unfiltered context

What happened around a line is usually why it happened, and the filter has by definition hidden it.
The detail pane's `context ±20` toggle reads **the index, not the result**: with `level:E` on screen,
that is where the Debug lines that led to the error are.

## Export

Writes **the current result**, not the selection — that is what the button says, and narrowing further
is the query bar's job. Uncapped, off the UI thread, decoded entry by entry straight into the writer:
the peak cost is one entry rather than the result.

## Horizontal scrolling, in raw-line mode only

Not a compromise, a decision. In columns, scrolling sideways would push the timestamps off screen and
destroy the alignment that is the whole point of columns; there, a line is truncated and the detail
pane shows the rest. In raw-line mode, where you want the file as it is, scrolling is exactly right.

It sits on the content `Row` rather than on the item, so the selection highlight always covers the
visible width while the text slides inside it. The `weight(1f)` went with it: inside a horizontal
scroll the width is unbounded, and a weight needs a bound.

## The health indicator answers its own question

`40,168 / 41,087 lines recognised` said there was a problem without saying what it was — which is half
the work. The counter is now **clickable** and opens a pane grouping the orphaned lines **by shape**,
with one example of each and its position.

The number says the profile is imperfect; the shape says *which part* of it is, and the five point at
very different fixes:

| Shape | What it means |
|---|---|
| empty line | Benign. A partial write, or a second producer on the same file. |
| whitespace only | `entry.continues` is close but not exact — check the real indent width. |
| indented, but not a continuation | Looks like a folded message or a frame. `entry.continues` is probably too strict: an older writer with a different timestamp width indents by that much less. |
| **looks like an entry, but `parse.regex` rejects it** | **The one worth the trip.** The pre-filter said yes and the full regex said no: the format has a shape the profile does not describe. |
| something else | Written by an entirely different code path. |

Counted **in full**, sampled **per shape**. In full because an approximate ratio is worth nothing;
sampled because a file opened with the wrong profile has *every* line orphaned, and holding nine
million strings to say so would turn a diagnosis into an `OutOfMemoryError`. The cap is **per shape and
not global**: one shape almost always dominates, and a global cap would let it crowd out the single
example of the one that explains the problem.

So the tool can diagnose its own profile — which matters all the more with four more about to be
written in M4.

---

## Left for M4

`⌘F` / `⌘L` to focus the query bar. The `android-logcat`, `json-lines`, `syslog` and
`generic-timestamped` profiles — each of which will exercise the timestamp compiler's fallback path,
which has only one test today. And publishing: the Gradle group, notarisation, a README.
