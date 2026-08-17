# M4 — publishing

Three more profiles, continuous integration, and the publishing identifiers.

---

## The profiles, and what they broke

Each was picked to exercise a path the Withings profile never touches. That was the stated goal at M1
— "the timestamp compiler's fallback has only one test today" — and it did find two bugs.

| Profile | Priority | What it exercises |
|---|---:|---|
| `withings-healthmate` | 50 | An optional group, indented continuations, R8 tags |
| `android-logcat` | 40 | **A yearless timestamp**, seven levels, no continuations at all |
| `syslog-rfc3164` | 30 | **A named month, a space-padded day, no level whatsoever** — the only one to take the `DateTimeFormatter` fallback |
| `generic-timestamped` | 0 | **Optional milliseconds**, optional level, catch-all |

> Two more shipped after M4 — `android-studio-logcat` and `json-lines` — and neither carries a
> priority, because a profile an adapter writes for is named rather than detected. See
> [`profiles.md`](profiles.md).

### Bug 1 — the invented year did not exist

`MM-dd HH:mm:ss.SSS` (logcat) and `MMM d HH:mm:ss` (syslog) carry no year. The fast path demanded one,
and the `DateTimeFormatter` fallback would have failed anyway: you cannot build a `LocalDateTime`
without a complete date.

The year is now **assumed** — the current year by default, which is what every other logcat viewer does
and the only guess available — with `assume_year` in the profile for an archived capture. And more to
the point, **the loader says so**: `assumesYear` raises a warning, because a tool that invents a date
without mentioning it is lying.

On the fallback side, `parseDefaulting(ChronoField.YEAR, …)` supplies what the pattern does not carry.

### Bug 2 — a group shorter than its layout read whatever followed

`generic-timestamped` has optional milliseconds: `(?:\.\d{3})?`. So the captured group is 19 or 23
characters. The fast reader reads at fixed offsets — it went looking for the milliseconds at offset 20
of a group that stops at 19, and **called a number whatever came next in the line**.

A `slot.offset + slot.width > available` check is enough: an absent tail leaves its field at zero
instead of producing an invented value. Exactly the kind of bug that does not crash and gives you wrong
timestamps.

### What was not shipped, and why

**`json-lines`.** A regex-driven JSON profile would work for one key order and be silently wrong for
every other — before you get to nested objects and escaped quotes. Correct JSON needs a field
extractor, not a regular expression. Shipping a profile that lies about three formats to read one is
worse than shipping none, so it was deferred with its reason.

> It shipped later, and the reasoning above is why it is a **source adapter** rather than a profile:
> the adapter decodes the JSON properly and renders text a profile can read. The escapes were the
> deciding argument — 12 of 43 lines in a real iOS capture carry `\"`, `\/` or `\n`, and a captured
> group hands them to the facet still escaped.

### The catch-all does not steal detection

`generic-timestamped` will happily recognise a Withings or a logcat line. Detection sorts by score and
breaks ties on **priority**, so a format that genuinely describes the file always wins, and the
catch-all only takes over when nobody else understands anything. Its `min_match` is stricter too
(0.90): a fallback that recognises four lines in five of a format it does not understand is worse than
admitting ignorance.

Two tests pin the property in both directions.

---

## `⌘F` / `⌘L`

The last shortcut from M3. Placed on the window's root rather than on the list, because "find" has to
work from wherever you are — and strictly limited to those two keys, so everything else reaches the
list.

---

## Continuous integration

`.github/workflows/build.yml`, on Linux: nothing here opens a window. The state is tested without
Compose and the parsers are plain Kotlin; the only thing that genuinely needs a Mac is `packageDmg`,
which is a release step and not a per-push one.

The job **fails on a compiler warning**. A deprecation is a small task now and a migration later; it
may as well be visible immediately instead of piling up behind a wall of noise.

> A GitLab configuration followed, for a self-hosted instance: Docker runners for build and test, a Mac
> mini shell runner for packaging. Both call the same `tools/package-dmg.sh`. See
> [`packaging.md`](packaging.md).

---

## Identifiers

`group = "io.github.eliasborchani"`, bundle id `io.github.eliasborchani.loupe`. An `io.github.`
coordinate asks for nothing but the GitHub account, where `dev.loupe` would claim a domain. The Kotlin
packages stay `dev.loupe.*` — those are names, not claims — and it is one line to change if
`loupe.dev` is ever acquired.

---

## Third-party profiles were promised, not wired

`ProfileRegistry.fromDirectory` had existed since M1 and **nobody called it**. Worse, the error message
when no profile recognises a file already said "Add one to `~/.loupe/profiles/` and reopen" — it
promised a feature that did not exist.

> `fromDirectory` was never called afterwards either: what got written was `bundledPlusUser`, which
> collects failures instead of throwing on the first one. The dead function survived two more
> milestones and has since been deleted.

It is wired now: `~/.loupe/profiles/*.logprofile.toml`, re-read **on every open** so that writing a
profile does not need a restart — which is the whole workflow when writing one for a format nobody has
described.

Two design decisions:

- **`core` never reads the home directory; the app does.** `LogSourceLoader` defaults to the bundled
  registry, and `LoupeState` passes it `bundledPlusUser()`. Otherwise every test would silently depend
  on whatever happens to be sitting in `~/.loupe/profiles` on the machine running it.
- **A broken profile is reported, never fatal.** Someone writing one has a syntax error half the time;
  refusing to open anything would break exactly the task the feature serves. Failures surface in the
  diagnostic pane beside the unrecognised lines — it is the same "why is this not working".

The public documentation of the format is in [`profiles.md`](profiles.md).

## The icon

`desktop/icon.svg` (detailed) and `desktop/icon-small.svg` (16 and 32 px), assembled into an `.icns` by
`tools/render-icon.sh`.

The concept is the product's thesis in one image: outside the lens a log is a wall of undifferentiated
lines; inside it, the same lines resolve into columns, and **exactly two** carry colour — because that
is the app's rule, where colouring every level is the same as colouring none. **No handle**: a
watchmaker's loupe has none, and that is what avoids the generic search glyph everyone already uses.

Two drawings, because **the detailed one does not survive 16 px**: the page behind the lens becomes
noise, the metadata column merges into the message, and the amber and the red average into mud. The
variant keeps only what reads at that size — three bars, no page, and a rim at 8 % of the width instead
of 4 % so it is still more than one pixel.

Rasterised by headless Chrome, for want of an installed SVG rasteriser, then `sips` for the reductions.
Two traps, both in the script: Chrome pointed at an `.svg` with intrinsic dimensions **crops** instead
of scaling (hence the HTML wrapper), and it **clamps** a window below ~50 px (hence the single 1024
render followed by `sips`).

## The menu bar

`LoupeMenuBar.kt`. With `apple.laf.useScreenMenuBar` it lands at the top of the screen, where a Mac
user looks for it — and, more importantly, where a feature gets **discovered**: export and adding a
profile both already existed, and neither was findable without being told.

| Menu | |
|---|---|
| **File** | Open… ⌘O · Add Files… ⇧⌘O · Export Current Filter… ⌘E · Close Log |
| **View** | Columns ⌘1 · Raw Line ⌘2 · Find ⌘F · Unrecognised Lines… |
| **Profiles** | Reveal Profiles Folder · New from Template ▸ · Reload Profiles and Reopen |

**There is deliberately no Edit menu.** A menu shortcut is caught by the native menu before the window
ever sees the key: putting Copy on ⌘C and Select All on ⌘A up there would break both inside the query
field — you would select log rows while believing you were selecting text. They stay window-level
handlers, owned by the list that owns them.

"New from Template" copies a bundled profile rather than creating an empty file: they are heavily
commented, and the fastest way to describe a format is to edit one that works. The copy is renamed
inside the file too, or the original and it answer to the same name and detection has two
indistinguishable candidates.

## A launch that lied

The `run { workingDir = rootProject.projectDir }` block was **never** committed: `tasks.named("run")`
fails at configuration time, because the Compose plugin registers its `run` task after this file is
evaluated. The consequence: `--args="spike/fixtures/folder"` resolved against `desktop/`, pointed at
nothing, and `main` filtered the non-existent path **in silence** — the app opened empty, which looks
exactly like a successful launch.

Fixed on both sides: `tasks.withType<JavaExec>().configureEach` does not depend on registration order,
and a non-existent path is now **reported on stderr** instead of dropped. An invalid input that says
nothing costs more than one that fails.

## Left over

**Apple notarisation.** It needs an Apple Developer account, a *Developer ID Application* certificate
and an app-specific password. Without it the `.dmg` opens after a right-click → Open, or an
`xattr -d com.apple.quarantine`.

> The build is wired for it now — `signing.sign` turns on when `loupe.signing.identity` is present —
> and a self-hosted Mac runner is where it is easiest, because the certificate lives in a login
> keychain and no secret reaches the repository. See [`packaging.md`](packaging.md).
> [Conveyor](https://conveyor.hydraulic.dev) remains the shorter road if the keychain turns into an
> afternoon.

**Pushing to GitHub.** *(Done — `github.com/EliasBorchani/Loupe`, plus a self-hosted GitLab mirror.)*
