---
created: 2026-09-05T00:00:00.000Z
title: "Seven committed diagrams (auth-*, architecture-*) drift from scripts/render-diagrams.sh's pinned renderer"
area: docs
severity: minor
files:
  - docs/diagrams/auth-signin-scenario.png
  - docs/diagrams/auth-signup-scenario.png
  - docs/diagrams/architecture-activity-feed-read.png
  - docs/diagrams/architecture-error-response-split.png
  - docs/diagrams/architecture-mutation-flowchart.png
  - docs/diagrams/architecture-mutation-sequence.png
  - docs/diagrams/architecture-signin-scenario.png
---

## Problem

Filed from quick task 260905-tw0, which pinned `scripts/render-diagrams.sh` and ran
`--check --all` against all nine committed diagram pairs for the first time. Six of these seven
fail the script's own match criterion (exact width, height within 2%); `architecture-mutation-flowchart`
happens to fall inside the 2% height band (+1.42%) but is included here anyway because it was not
re-rendered and its status is therefore unverified going forward, not because it currently fails.

Measured 2026-09-05, `--check --all` output (width matched exactly for all seven; only height and
colour mode differ):

| Diagram | Committed (WxH) | Rendered (WxH) | Height delta |
|---|---|---|---|
| architecture-activity-feed-read | 3136x896 | 3136x840 | -6.25% |
| architecture-error-response-split | 3136x1756 | 3136x1596 | -9.11% |
| architecture-mutation-flowchart | 2044x5060 | 2044x5132 | +1.42% (within tolerance) |
| architecture-mutation-sequence | 3136x1100 | 3136x1008 | -8.36% |
| architecture-signin-scenario | 3136x2112 | 3136x1904 | -9.85% |
| auth-signin-scenario | 784x862 | 784x764 | -11.37% |
| auth-signup-scenario | 784x739 | 784x674 | -8.80% |

This is renderer drift, not stale-source drift: `git log -1` per file (checked 2026-09-05) shows
every `.mmd`/`.png` pair here was committed in the SAME commit, so the historical source is
byte-identical to the current one — the committed PNG set was produced by a mermaid/Puppeteer
version this repository never recorded, not by someone editing a `.mmd` without re-rendering.

## Solution

Re-render these seven diagrams with `scripts/render-diagrams.sh <name>` (in-place mode) under the
pinned renderer, once, as its own reviewable change — not folded into an unrelated docs edit, so
the diff is exactly nine PNG replacements and reviewable as such. `architecture-mutation-flowchart`
should also convert `flowchart TD` to `flowchart TB` in the same change (its own separate todo,
`.planning/todos/pending/2026-09-05-convert-architecture-mutation-flowchart-td-to-tb.md`, tracks the
source-level rule violation; re-rendering here should happen after that conversion lands, not
before, so the PNG reflects the corrected source).

Deliberately not done as part of quick task 260905-tw0: that task's scope was pinning the renderer
and reporting drift, not re-rendering out-of-scope diagrams whose PNGs are the very evidence the
drift measurement depends on.
