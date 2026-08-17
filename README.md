# Loupe

**A structured log viewer for macOS. The Datadog experience — facets, counts, a brushable
timeline — on a local file, with no server.**

> **Status: pre-alpha, but it runs.** Open a folder of logs and you get facets, a query bar, a
> brushable timeline and a detail pane. See [the roadmap](#roadmap) for what is missing.

Your app already writes structured logs — a timestamp, a level, a category, a tag. Every tool then
throws that structure away and hands you back a wall of text to `grep`. Loupe reads it, counts it,
and turns it into filters:

```
level>=W cat:Sync since:-2h "timeout"
```

Ticking a facet writes into the query bar rather than into a hidden selection model, so the syntax
is learned by using it. Formats are described by **declarative profiles** you can commit next to
your code, so a whole team shares one viewer that understands their logs. Four ship with it —
Withings HealthMate, Android logcat, BSD syslog, and a catch-all for anything with an ISO-8601
timestamp — and the one that best describes your file is picked by score, never silently.

```toml
[entry]
continues = '''^ {23}'''            # a wrapped message or a stack-trace frame, not a new entry

[parse]
regex = '''^(?<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[(?<level>[VDIWE])\] (?:\[(?<category>[^\]]*)\] )?\[(?<tag>[^\]]*)\] -> (?<message>.*)$'''

[fields.level]
role  = "level"
order = ["V", "D", "I", "W", "E"]   # declaration order is severity order, so `level>=W` works

[fields.category]
role = "facet"
```

## Why not just use `lnav`?

[lnav](https://lnav.org) is excellent and solves much of the same problem. Loupe differs on three
points: it is a desktop app rather than a TUI, the scan produces a *shape* of the file (level
histogram, categories by volume, temporal density) before you read a single line, and the timeline
is a filter you brush rather than a column you read.

## Performance

Measured on a 1 GiB file — **9 013 588 entries, 11 066 525 lines** — on an Apple M5 Pro, JDK 17:

| | |
|---|---:|
| Full index (parse, facet counts, dictionaries) | **3.7 s** |
| Index memory | **258 MiB** — 30 bytes/entry; the text never enters the heap |
| Facet filter | **1–6 ms** |
| Full-text search across the whole corpus | **under 120 ms** — measured between 41 and 117 ms, depending on page-cache state |

Every field comes from the profile — nothing about any format is compiled in. Method, caveats and
the numbers that did *not* meet target on the first attempt:
[`docs/m0-perf-spike.md`](docs/m0-perf-spike.md), [`docs/m1-core.md`](docs/m1-core.md),
[`docs/m2-ui.md`](docs/m2-ui.md), [`docs/m3-product.md`](docs/m3-product.md) and
[`docs/m4-public.md`](docs/m4-public.md) (in French).

## Building

```bash
./gradlew :desktop:run --args="~/logs"   # open a file or a folder of them
./gradlew test                           # 121 tests
./gradlew :spike:run --args="1g A"       # generate a 1 GiB fixture and benchmark the indexer
./gradlew :desktop:packageDmg            # unsigned .dmg
```

Requires JDK 17+. No other setup.

## Roadmap

| | | |
|---|---|---|
| **M0** | Performance spike — columnar index, parser strategies, benchmarks | ✅ done |
| **M1** | Core — TOML profiles, format auto-detection, query language | ✅ done |
| **M2** | UI — Compose Multiplatform window, virtualised list, facets, query bar, timeline | ✅ done |
| **M3** | Product — multi-select, copy, keyboard, unfiltered context, export | ✅ done |
| **M4** | Public — bundled profiles, CI, publication identifiers | ✅ done |
| | Left: Apple notarisation, and pushing this anywhere | |

Full product spec (in French): [`docs/PRD.fr.md`](docs/PRD.fr.md).

## Licence

MIT.
