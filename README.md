# Loupe

**A structured log viewer for macOS. The Datadog experience — facets, counts, a brushable
timeline — on a local file, with no server.**

> **Status: pre-alpha.** The performance core is built and measured; there is no UI yet.
> See [the roadmap](#roadmap).

Your app already writes structured logs — a timestamp, a level, a category, a tag. Every tool then
throws that structure away and hands you back a wall of text to `grep`. Loupe reads it, counts it,
and turns it into filters:

```
level>=W cat:Sync since:-2h "timeout"
```

Formats are described by **declarative profiles** you can commit next to your code, so a whole team
shares one viewer that understands their logs.

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
[`docs/m0-perf-spike.md`](docs/m0-perf-spike.md) and [`docs/m1-core.md`](docs/m1-core.md) (in French).

## Building

```bash
./gradlew :core:test                  # 56 tests, incl. golden cases for a real log format
./gradlew :spike:run --args="1g A"    # generate a 1 GiB fixture and benchmark the indexer
```

Requires JDK 17+. No other setup.

## Roadmap

| | | |
|---|---|---|
| **M0** | Performance spike — columnar index, parser strategies, benchmarks | ✅ done |
| **M1** | Core — TOML profiles, format auto-detection, query language | ✅ done |
| **M2** | UI — Compose Multiplatform window, virtualised list, facet sidebar, query bar | next |
| **M3** | Product — timeline, detail pane, export, themes, shortcuts |  |
| **M4** | Public — English docs, bundled profiles, CI, notarised `.dmg` |  |

Full product spec (in French): [`docs/PRD.fr.md`](docs/PRD.fr.md).

## Licence

MIT.
