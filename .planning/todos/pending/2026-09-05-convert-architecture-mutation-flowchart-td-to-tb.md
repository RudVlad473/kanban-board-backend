---
created: 2026-09-05T00:00:00.000Z
title: "Convert architecture-mutation-flowchart.mmd from flowchart TD to flowchart TB"
area: docs
severity: minor
files:
  - docs/diagrams/architecture-mutation-flowchart.mmd
  - docs/diagrams/architecture-mutation-flowchart.png
---

## Problem

`docs/DIAGRAM_CONVENTIONS.md`'s Layout rules section (added by quick task 260905-tw0) requires
`flowchart TB` and never `TD` for greppability — one spelling makes
`rg -n 'flowchart TB' docs/diagrams/` a complete inventory. As of 2026-09-05,
`architecture-mutation-flowchart.mmd:1` is the one file still spelled `flowchart TD`.

Not fixed by 260905-tw0 itself: converting the source's direction keyword without re-rendering the
PNG would manufacture the exact source/render divergence that same task's drift-detection work
exists to catch, and `architecture-mutation-flowchart.png` is explicitly out of that task's scope
(see `.planning/todos/pending/2026-09-05-seven-diagrams-drift-from-the-pinned-renderer.md`, which
tracks its measured drift under the pinned renderer).

## Solution

Edit `architecture-mutation-flowchart.mmd:1` from `flowchart TD` to `flowchart TB` (a no-op on
rendered layout — the two keywords are documented aliases), then re-render the PNG with
`scripts/render-diagrams.sh architecture-mutation-flowchart` under the pinned renderer, as one
reviewable change. Do this in the same change as (or after) the broader seven-diagram re-render
tracked in the sibling todo above, not before it, so the two related PNG replacements land together
rather than as two separate diffs touching the same file.
