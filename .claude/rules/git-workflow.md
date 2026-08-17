---
description: Commit format and branch policy
---

# Git Workflow

## Commits — format and granularity

Title: `Prefix: Title`, prefix ∈ **Feat / Clean / Fix / Docs / CI / Refactor / Draft**. No other
prefix, no scope in brackets, no trailing full stop.

**The body explains *why*, not what.** The diff already says what. A good body here records:
- the decision and the alternative it beat,
- **what a measurement showed**, especially when it contradicted the plan,
- what a test caught while the work was happening.

That is not ceremony for its own sake — half the reasoning in this project is a number, and a number
that is not written down is lost. `git log` is the second place someone looks after `docs/`.

**Moving or renaming files goes in its own commit**, separate from any change to their contents, so
the rename stays reviewable.

## Branches

`main`. This rule used to say that committing straight to `main` was fine "while the repository has
one author and no remote", and that it would change the day it was pushed. **It has been pushed**, to
GitHub and to a self-hosted GitLab, and commits are still going straight to `main` — with the owner's
explicit say-so, since there is still one author and nobody to review.

So the condition the rule was written against no longer holds, and the practice has not changed. That
is a decision for the owner to make rather than a fact to record here: **ask before assuming either
way.**

What does not change: never force-push `main`, and never rewrite published history. The one rewrite
this repository has had (the author e-mail, across four commits) was safe precisely because nothing had
been pushed then.

## What not to commit

- `spike/fixtures/` — gitignored. Fixtures are generated on demand and run to gigabytes.
- Build output, `.gradle/`, IDE files. Already covered by `.gitignore`; if something new appears in
  `git status`, add the ignore rather than the file.
- A benchmark result quoted from a combined-strategy run. See `performance.md` — it will be wrong.

## Never commit on the user's behalf without being asked
Stop and say the work is ready. Committing is the author's call, and so is when.
