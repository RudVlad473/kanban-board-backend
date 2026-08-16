---
created: 2026-08-16T20:10:00.000Z
title: gsd-tools' state.record-metric (or equivalent) writes the wrong progress percent -- reproduced twice, same wrong value both times
area: tooling
severity: minor
files:
  - .planning/STATE.md
---

## Problem

Two separate quick-task executor runs today (260816-tqc and 260816-uc8) each left
`.planning/STATE.md`'s frontmatter `progress.percent` at `86` after their `gsd-tools`
state-update calls, while `completed_plans: 38` / `total_plans: 39` computes to 97%
(38/39 = 0.974). The body text's progress bar and "Progress: [...] 97%" line stayed
correct both times -- only the frontmatter `percent` field regressed, and to the
identical wrong value (`86`) both times, not a random miscalculation.

`86` is suspiciously close to `38/44 = 0.8636` -- a plausible hardcoded or cached
stale denominator (`44` total plans) somewhere in the metric-recording path, rather
than reading `total_plans` from the same frontmatter block it's writing into.

Caught and manually corrected (86 -> 97) before committing, both times, by the
orchestrating session -- not caught by any automated gate, since nothing currently
cross-checks `progress.percent` against `completed_plans`/`total_plans` at
commit time.

## Solution

1. Find the exact `gsd-tools` command/code path the quick-task executor calls to
   update STATE.md's progress metrics (likely `state.record-metric` or a sibling
   verb invoked as part of the executor's standard STATE.md update sequence) and
   trace where `86`/`44` could originate -- check for a hardcoded fallback, a
   stale cached value, or a denominator sourced from somewhere other than the
   frontmatter's own `total_plans`.
2. Fix the calculation to derive `percent` from the same `completed_plans`/
   `total_plans` values being written in the same call, not a separate source.
3. Consider adding a cheap consistency check (e.g. a pre-commit or CI assertion)
   that `progress.percent` in STATE.md's frontmatter is within rounding distance
   of `round(completed_plans / total_plans * 100)`, so a future regression here
   fails loudly instead of silently landing in a commit.

**Trigger:** any time this file's metric-writing path is touched, or proactively
whenever convenient -- low severity, but reproducing identically twice in one
session across two independent executor invocations suggests a real, stable bug,
not noise.
