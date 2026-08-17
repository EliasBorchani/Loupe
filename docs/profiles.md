# Writing a log profile

A **profile** is a small TOML file that tells Loupe how to read one log format. Four ship with the
app; you can add your own, and you should — a profile committed next to your code means everyone on
the team gets a viewer that understands your logs.

Drop yours in **`~/.loupe/profiles/`** with a `.logprofile.toml` name. The directory is read on
every open, so you can edit a profile and reopen the file without restarting.

---

## The idea in one paragraph

Your logger already writes structure: a timestamp, a level, a tag, a message. A profile is a regex
with **named groups**, plus a line saying what each group *means*. From that, Loupe knows what to
sort by, what to colour, what to offer as a facet, and what `level>=WARN` should compare.

## The smallest useful profile

For a log that looks like this:

```
2026-07-22 10:00:01 INFO  pump3  pressure nominal
2026-07-22 10:00:04 ERROR pump1  seal failure
```

```toml
name = "widget"

[parse]
regex = '''^(?<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) (?<level>\w+) +(?<unit>\w+) +(?<message>.*)$'''

[fields.ts]
role   = "timestamp"
format = "yyyy-MM-dd HH:mm:ss"

[fields.level]
role  = "level"
order = ["DEBUG", "INFO", "WARN", "ERROR"]   # ascending severity

[fields.unit]
role = "facet"

[fields.message]
role = "message"
```

That is a complete profile. Every named group needs a `[fields.<name>]` block and vice versa —
the loader refuses a profile where they disagree, and tells you every mismatch at once rather than
the first.

## The four roles

| `role` | How many | What it does |
|---|---|---|
| `timestamp` | exactly one | Sorts everything, drives the timeline, answers `since:` / `until:` |
| `level` | at most one | The severity scale. **Declaration order is the order**, so `level>=WARN` works |
| `facet` | any number | A column in the list and a group in the sidebar, with counts |
| `message` | at most one | What shows in the message column |

A format with no level is fine — `syslog-rfc3164` has none. The sidebar just drops that group.

## Multi-line entries

If your logger indents wrapped lines or stack frames, say so, and Loupe treats the whole thing as
**one entry**: it filters as one, copies as one, and folds behind a `+7` you can expand.

```toml
[entry]
continues                 = '''^ {23}'''   # exactly what a continuation line starts with
strip_continuation_indent = true
```

There is a second, optional key:

```toml
[entry]
opens = '''^\d{4}-\d{2}-\d{2} '''
```

`opens` is a **pure optimisation** — a cheap test run before the real regex. It never changes the
result, so it can be loose; the parse regex always has the final say. `continues` is the opposite:
it decides how a line is classified, so it must mean exactly what it says.

## Lines that are not entries

Separators, banners, truncation notices — keep them classified rather than counted as broken:

```toml
[[markers]]
regex = '''^=== (.+) ===$'''
role  = "section"    # or "notice"
```

## Timestamps

Java's pattern letters. Two things worth knowing:

**Fixed-width numeric patterns are read directly** — `yyyy-MM-dd HH:mm:ss.SSS` costs nanoseconds.
Anything else (a named month, a variable-width day) falls back to `DateTimeFormatter` at roughly a
microsecond per entry. Both work; the loader warns when you are on the slow one.

**A format with no year gets the current one**, which is what logcat and syslog need. The loader
says it assumed. For an archived log from another year:

```toml
[fields.ts]
format      = "MM-dd HH:mm:ss.SSS"
zone        = "local"     # or "utc", or "Europe/Paris"
assume_year = 2025
```

## Getting picked

Loupe reads the first 200 lines of a file with every profile and scores each on the share it can
account for. Highest score wins; ties break on `priority`.

```toml
priority = 60          # bundled ones sit at 0–50; higher wins a tie

[detect]
filename  = '''\.log$'''   # a corroborating hint, never a requirement
sample    = 200
min_match = 0.80           # below this, the profile declines rather than guessing
```

Nothing is ever detected silently: the app shows which profile it chose and its score, and you can
override it.

## When it does not work

The status bar shows `40 168 / 41 087 lines recognised — why?`. **Click it.** The panel groups the
lines your profile could not account for by shape, with an example of each:

- *empty line* — usually a partial write, harmless.
- *indented, but not a continuation* — your `continues` is too strict. Check the real indent width.
- *looks like an entry, but `parse.regex` rejects it* — the one worth chasing. Your format has a
  shape the profile does not describe.
- *something else* — written by a different code path entirely.

A profile that fails to load is reported in the same panel, with the reason, and never stops the
app opening anything.

## Worked examples

The bundled profiles are heavily commented and cover the awkward cases between them:

| | |
|---|---|
| [`withings.logprofile.toml`](../profiles/withings.logprofile.toml) | An optional group, 23-space continuations, a high-cardinality facet |
| [`android-logcat.logprofile.toml`](../profiles/android-logcat.logprofile.toml) | No year, seven levels, no continuation rule at all |
| [`syslog-rfc3164.logprofile.toml`](../profiles/syslog-rfc3164.logprofile.toml) | A named month, a space-padded day, and no level field |
| [`generic-timestamped.logprofile.toml`](../profiles/generic-timestamped.logprofile.toml) | Optional milliseconds, optional level, catch-all |

## When the file is not lines at all

A profile describes **lines**. Some log files are not lines: an Android Studio `.logcat` export,
despite the extension, is one JSON document where a single entry spans sixteen pretty-printed
lines. No regex can describe that, and one that appeared to would be silently wrong.

Those go through a **source adapter** instead (`core/source/SourceAdapter.kt`), which renders the
container into plain text before anything is indexed, and a profile reads the result. The rendered
copy takes the original's name in a temporary directory that is deleted when the file is closed, so
the `file` facet still reads right and reopening still points at the real file.

Two ship today:

| Adapter | Reads | Paired profile |
|---|---|---|
| `AndroidStudioLogcatAdapter` | Android Studio's `.logcat` export — one JSON document | `android-studio-logcat` |
| `JsonLinesAdapter` | JSON lines / NDJSON — one object per line | `json-lines` |

Adding a third is a deliberate act: an adapter claims that a whole file format is worth supporting,
which is a bigger promise than a profile makes. It is also the pattern `.gz` and `.zip` will want,
being containers in exactly the same sense.

### Why JSON lines is an adapter and not a profile

NDJSON is line-oriented, so a regex *looks* like it would work — and it does, until it doesn't, in
two ways that are both silent:

- **The escapes stay escaped.** A captured group is handed to the facet exactly as written, and
  nothing downstream unescapes. 12 of the 43 lines in the iOS capture this was built against carry
  `\"`, `\/` or `\n`, so the message would read `POST https:\/\/withings.net` and a stack trace
  would show a literal `\n` where it should break.
- **The key order becomes law.** A regex encodes one order. Reorder two keys and it stops matching;
  add one and it may match and capture the wrong field.

The adapter decodes properly, then picks fields by conventional key name — `timestamp`/`time`/
`date`/`ts`, `level`/`severity`, `message`/`msg`, and `category`/`logger`/`subsystem`/`tag` for the
context slot — normalises the level vocabulary onto one scale, resolves the zone, and writes any
key the four slots did not take onto a continuation line rather than dropping it. **What it picked
is named in the conversion note**, because a mapping guessed in silence would be the worst of both
worlds.

## What a profile cannot do yet

**Arbitrary JSON, as a profile.** For the reasons just given, JSON needs a field extractor rather
than a regex. Two shapes are covered by adapters — one object per line, and the Android Studio
export. A JSON document of some other shape still needs one written for it.

**A field that is not on the entry's first line.** Everything parsed comes from the opening line;
continuation lines are text, not structure.

**Its own colours, or a closed set of facet values.** Severity colour is positional — the top of the
declared `order` is the error colour, the one below it the warning colour — so a profile chooses its
colours by choosing its scale, and nothing else. `colors` and `values` keys used to be accepted here
and silently ignored; they are now rejected, because a key that parses and does nothing is worse than
one that fails.
