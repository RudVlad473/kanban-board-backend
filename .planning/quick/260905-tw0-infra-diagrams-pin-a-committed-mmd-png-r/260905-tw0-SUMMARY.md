---
quick_id: 260905-tw0
phase: quick
subsystem: docs/diagrams
tags: [diagrams, infra, mermaid, ci]
dependency-graph:
  requires: []
  provides:
    - scripts/render-diagrams.sh (digest-pinned mermaid-cli renderer, render + --check modes)
    - docs/diagrams/render-manifest.tsv (per-diagram render scale)
    - docs/diagrams/infra-packet-path-scenario.mmd/.png (Scenario +1 packet-path view)
  affects:
    - docs/DIAGRAM_CONVENTIONS.md
    - docs/INFRA_ARCHITECTURE.md
    - docs/diagrams/infra-physical-deployment.mmd/.png
tech-stack:
  added:
    - "ghcr.io/mermaid-js/mermaid-cli/mermaid-cli@sha256:a6fb0574dded4086888b5e38476899c9aff8963196f689f11a0f8fceee588ce1 (digest-pinned, Docker)"
  patterns:
    - "Digest re-resolved independently at execution time via the GHCR registry API's docker-content-digest header, not trusted from the plan"
key-files:
  created:
    - scripts/render-diagrams.sh
    - docs/diagrams/render-manifest.tsv
    - docs/diagrams/infra-packet-path-scenario.mmd
    - docs/diagrams/infra-packet-path-scenario.png
    - .planning/todos/pending/2026-09-05-seven-diagrams-drift-from-the-pinned-renderer.md
    - .planning/todos/pending/2026-09-05-convert-architecture-mutation-flowchart-td-to-tb.md
  modified:
    - docs/DIAGRAM_CONVENTIONS.md
    - docs/INFRA_ARCHITECTURE.md
    - docs/diagrams/infra-physical-deployment.mmd
    - docs/diagrams/infra-physical-deployment.png
decisions:
  - "Match criterion is exact width + 2% height tolerance, NOT colour mode -- mmdc has no flag to force RGB output, and forcing it would require a Pillow/ImageMagick dependency this script deliberately avoids; RGBA is accepted going forward for any diagram this script renders"
  - "Two diagrams' PNGs (infra-physical-deployment, infra-packet-path-scenario) were rendered under the pin in this change; the other seven were measured but explicitly NOT re-rendered, to preserve them as evidence for the renderer-drift finding"
  - "Post-review layout fixes (commit 4) reused the existing spacer+~~~ idiom (rule 3) and the existing edge-label-vs-subgraph-wrapper idiom for [4] (already used elsewhere in the physical view) rather than inventing new idioms or touching the shared %%{init}%% block"
metrics:
  duration: "~80min"
  completed: 2026-09-05
actuals:
  tokens: 8400
  tasks: 3
  commits: 4
status: complete
---

# Quick Task 260905-tw0: Infra diagrams — pin a committed .mmd/.png renderer Summary

Pinned a digest-locked mermaid-cli renderer, measured (but did not silently fix) drift across all
nine committed diagrams, added four checkable layout rules to `DIAGRAM_CONVENTIONS.md`, and drew a
new Scenario (+1) packet-path view documenting an already-known but previously undiagrammed gap: the
VM's `DOCKER-USER` iptables chain is empty, so nothing at the OS level governs traffic to a
published container port.

## Commits on this branch

4 commits, `git log main..HEAD --oneline`:

```
2b0bd7b fix(260905-tw0): clear title/node overlaps in both infra diagrams, move [1] outside the VM boundary
199f1c6 feat(260905-tw0): add packet-path Scenario diagram, number trust boundaries, extend Maintenance Note
7e9bc5b docs(260905-tw0): add four layout rules to DIAGRAM_CONVENTIONS.md, each with its deciding test
93ca958 feat(260905-tw0): pin a mermaid-cli renderer and measure drift on all nine committed diagrams
```

HEAD after this session: `2b0bd7b84dcb9244b2a18d7e426ada7a869a8010`.

## Drift finding (Task 1) — measured, not silently fixed

Ran `scripts/render-diagrams.sh --check --all` against all nine committed diagram pairs. Verbatim
table (re-captured after Task 3's re-renders of the two in-scope diagrams; the other seven are
unchanged from Task 1's first run):

```
architecture-activity-feed-read        committed=3136x896    rendered=3136x840    width=OK(3136v3136) height=FAIL(-6.25%) mode=RGB->RGBA
architecture-error-response-split      committed=3136x1756   rendered=3136x1596   width=OK(3136v3136) height=FAIL(-9.11%) mode=RGB->RGBA
architecture-mutation-flowchart        committed=2044x5060   rendered=2044x5132   width=OK(2044v2044) height=OK(+1.42%) mode=RGB->RGBA
architecture-mutation-sequence         committed=3136x1100   rendered=3136x1008   width=OK(3136v3136) height=FAIL(-8.36%) mode=RGB->RGBA
architecture-signin-scenario           committed=3136x2112   rendered=3136x1904   width=OK(3136v3136) height=FAIL(-9.85%) mode=RGB->RGBA
auth-signin-scenario                   committed=784x862    rendered=784x764    width=OK(784v784) height=FAIL(-11.37%) mode=RGB->RGBA
auth-signup-scenario                   committed=784x739    rendered=784x674    width=OK(784v784) height=FAIL(-8.80%) mode=RGB->RGBA
infra-delivery-scenario                committed=1568x2102   rendered=1568x1926   width=OK(1568v1568) height=FAIL(-8.37%) mode=RGB->RGBA
infra-packet-path-scenario             committed=1458x3632   rendered=1458x3632   width=OK(1458v1458) height=OK(+0.00%) mode=RGBA->RGBA
infra-physical-deployment              committed=1568x1882   rendered=1568x1882   width=OK(1568v1568) height=OK(+0.00%) mode=RGBA->RGBA
```
`--check --all` exit code: 1 (non-zero is the expected, reportable outcome for this table — seven
of the ten rows fail the height tolerance).

**Renderer drift, not stale-source drift.** For every one of the seven older diagrams, `git log -1`
confirms its `.mmd` and `.png` were committed in the SAME commit — so the historical source is
byte-identical to the current one, and the class of drift where someone edits a `.mmd` without
re-rendering is ruled out by history. What remains is renderer drift: all seven widths reproduce
EXACTLY under the pinned renderer (confirming `render-manifest.tsv`'s per-family scales are
correct — auth-\*=1, infra-\*=2, architecture-\*=4), but heights differ by -6% to -11%, in every
case a height REDUCTION relative to committed. This is a real, reproducible mermaid-version
difference between whatever renderer produced the original nine PNGs and the pinned
`11.17.0`/`sha256:a6fb0574...`, not scale rounding (widths already prove scale is right) and not a
tolerance-band artifact (magnitudes are 3-8x the 2% band). `architecture-mutation-flowchart` alone
sits inside the 2% band (+1.42%, the only positive delta among the seven); it is still flagged and
tracked in the same todo below rather than treated as "passing," because its status was never
verified before this task and one in-tolerance measurement today does not establish it as
maintained going forward.

Filed two todos rather than silently re-rendering or silently leaving the diagrams as-is:
- `.planning/todos/pending/2026-09-05-seven-diagrams-drift-from-the-pinned-renderer.md` — tracks
  re-rendering all seven as their own reviewable change (a diff of exactly seven PNG replacements).
- `.planning/todos/pending/2026-09-05-convert-architecture-mutation-flowchart-td-to-tb.md` — tracks
  converting that one file's `flowchart TD` to `TB` (Task 2's new layout rule), sequenced to land
  before or together with its re-render, not independently.

## RGB vs. RGBA — judgement call

Committed PNGs are all colour type 2 (RGB, no alpha channel), confirmed by reading every file's
IHDR byte-for-byte before writing any code. Every fresh render from the pinned `mmdc` is colour
type 6 (RGBA) — `mmdc --help` was checked and offers no flag to force RGB output; `-b white` only
sets the background colour Puppeteer paints under the alpha channel, it does not strip the
channel. Forcing RGB would require Pillow or ImageMagick; ImageMagick is not installed on this box
and the script's dimension-reading logic is deliberately stdlib-only (the plan's own instruction).

**Decision: accept RGBA going forward, do not flatten.** Stated in the script's own header as a
load-bearing decision, not left implicit. The match criterion (width exact, height ≤2%) does not
gate on colour mode — it is reported per row (`mode=RGB->RGBA`) as an observation, not a pass/fail
condition, so this task's own two re-rendered diagrams (`infra-physical-deployment`,
`infra-packet-path-scenario`) are now RGBA, differing from the other seven's RGB. This is an
accepted, documented inconsistency in the committed set rather than a silently introduced one.

## CI-trigger consequence

`scripts/render-diagrams.sh` is a new file under `scripts/`, which is NOT in the `paths-ignore`
list (`docs/**`, `**/*.md`, `.planning/**`) that both `invariant-checks.yml` and `deploy.yml` share.
Confirmed this commit set will therefore:
- **Run `invariant-checks.yml`'s two jobs** (`verify-caddy-image-tag.py`,
  `verify-compose-ports-selftest.py` + `verify-compose-ports.py`) — wanted, and all three ran clean
  locally against this change (see Verification below).
- **Trigger `deploy.yml`'s full production+nonprod redeploy on merge to `main`** — an accepted,
  stated cost, not an accident. `docker/render-diagrams.sh` touches no application code, so the
  redeploy is a no-op restart of unchanged images, but it does run all 14 jobs.

This consequence is also documented inline in `scripts/render-diagrams.sh`'s own header and in
`docs/INFRA_ARCHITECTURE.md`'s existing framing of why the renderer is deliberately not wired into
CI itself (which would require removing the `docs/**` ignore entirely).

## Task-by-task detail

**Task 1 — pin the renderer, measure drift (tracer).** Re-resolved the digest independently rather
than trusting the plan's copy: `docker manifest inspect` alone returns per-architecture manifest
digests, not the top-level index digest an `@sha256:...` reference needs, so used the GHCR registry
API's `docker-content-digest` response header for the `11.17.0` tag instead — it matched the
plan's digest exactly (`sha256:a6fb0574dded4086888b5e38476899c9aff8963196f689f11a0f8fceee588ce1`).
Wrote `scripts/render-diagrams.sh` (render / --all / name / --check modes, IHDR-only PNG reading,
no image-library dependency) and `docs/diagrams/render-manifest.tsv` (scales derived empirically —
auth-\*=1, infra-\*=2, architecture-\*=4, confirmed by test-rendering every family, including
`architecture-mutation-flowchart` which turned out to reproduce exactly at scale 4 too, resolving
the plan's stated concern about a possible non-integer residual). Zero committed PNGs modified.

**Task 2 — four layout rules in `DIAGRAM_CONVENTIONS.md`.** Added a `## Layout rules for
flowcharts` section (42 added lines, 0 deleted) covering `flowchart TB` vs. `TD`, the
one-vertical-spine-with-leaves rule, `~~~` for rank-only alignment, and the shared `%%{init}%%`
block — each with the concrete test a reader can apply unaided. Named
`architecture-mutation-flowchart.mmd` as the one file still spelled `TD` and tracked its conversion
as a todo rather than converting it here (converting the source without re-rendering the PNG would
have manufactured the exact divergence Task 1's drift check exists to catch).

**Task 3 — packet-path Scenario, numbered boundaries, Maintenance Note.** Authored
`infra-packet-path-scenario.mmd` as a new Scenario (+1) flowchart under the Task 2 rules: one
spine (client → Netcup Cloud Firewall → `nat PREROUTING` → `filter FORWARD` → `DOCKER-USER` →
`DOCKER` → caddy → app) with the host-daemon `filter INPUT` path drawn as a leaf branch, so
container traffic is visibly routed through `FORWARD` and visibly never through `INPUT`. The
`DOCKER-USER` node carries the literal text `empty as of 2026-09-05` inside it. Edited
`infra-physical-deployment.mmd` additively — only subgraph/edge label text changed (the bare
`"trust boundary"` string promoted to `[2] VM host network boundary`, plus `[1]`/`[3]`/`[4]`/`[5]`
added to existing labels) — topology and the existing `~~~` spacer are untouched. Rendered both
PNGs under the pin; `--check` confirms exact reproduction for both. Updated
`docs/INFRA_ARCHITECTURE.md`: corrected the opening paragraph from "two views" to three, added a
numbered-boundaries paragraph to the Physical/Deployment section, added the full new Scenario
section (reproducing commands, the finding, the falsifying todo), and extended the Maintenance
Note with the diagram/render-script fact, the iptables fact, and the external Netcup Cloud Firewall
policy fact — each stated with what would make it stale, matching the file's existing voice.

## Post-review layout fixes (commit 4)

The user reviewed the rendered PNGs from commit `199f1c6` and found three layout flaws — confirmed
visually before fixing, then re-rendered and re-viewed after. Fixed with a single additional
commit, no plan re-run:

1. **`infra-packet-path-scenario.mmd` — `vm` subgraph's two-line title collided with the
   `prerouting` node directly beneath it.** Fixed by adding a 1px spacer node
   (`vm_spacer`) joined to `prerouting` with `~~~`, the exact idiom `infra-physical-deployment.mmd`
   already uses for its own two-line `netcup` subgraph title (`netcup_spacer ~~~ caddy_box`) — no
   new idiom, no `%%{init}%%` change, so nothing needed to change in the other diagram to keep the
   two init blocks identical (rule 4).
2. **Same file — the nested `caddy_box` subgraph poked outside its `compose` parent, overlapping
   the `[4] Compose-internal Docker network` title with `caddy_box`'s own title.** Fixed by
   dropping the `compose` wrapper entirely and moving `[4] Compose-internal Docker network` onto
   the `docker_chain → caddy` and `caddy → app` edge labels instead — the identical pattern
   `infra-physical-deployment.mmd` already uses for its own `[4]`-labelled edges (`caddy → app`,
   `app → redpanda` ×2). `caddy_box` (carrying `[3]`) and `app_box` are now top-level subgraphs,
   siblings of `vm` and `netcup_edge`, with no enclosing wrapper to overflow.
3. **`infra-physical-deployment.mmd` — `[1] Netcup Cloud Firewall (external — not in this repo)`
   was an edge label on `client → caddy`, and the renderer placed it INSIDE the `netcup` VM
   subgraph's box — geometrically implying the external firewall lives on the VM.** Fixed by
   promoting it to its own `netcup_edge` subgraph (mirroring the packet-path diagram's identical
   subgraph, same id and same title text) sitting between `client` and `netcup`, with a new
   `netcup_fw` node inside it. Rewired `client → netcup_fw → caddy`, keeping the HTTPS/duckdns
   hostname label on the `netcup_fw → caddy` edge exactly as before. This is a small topology
   change (one new node, one new subgraph) — a deliberate exception to Task 3's original
   "topology unchanged" instruction, made because the coordinator's follow-up explicitly requested
   this exact restructuring after reviewing the rendered geometry.

**No new idiom, no `DIAGRAM_CONVENTIONS.md` edit.** Both idioms used above (spacer+`~~~` for rule
3, edge-label placement of a boundary number for `[4]`) already exist in the committed diagrams and
are already described by rule 3's own text (which already cites `netcup_spacer ~~~ caddy_box` as
precedent) — nothing needed adding.

**Verification after the fix:**
```
infra-packet-path-scenario             committed=1568x2474   rendered=1568x2474   width=OK(1568v1568) height=OK(+0.00%) mode=RGBA->RGBA
infra-physical-deployment              committed=1568x1702   rendered=1568x1702   width=OK(1568v1568) height=OK(+0.00%) mode=RGBA->RGBA
```
Both diagrams' dimensions changed from the commit-3 versions (packet-path grew from 1458x3632 to
1568x2474 — wider due to the new sibling subgraphs no longer nesting under a narrower `compose`
wrapper, shorter due to flattening one level of nesting; physical-deployment grew narrower to taller
by a similar restructuring) — expected, since the topology and label placement changed. Both PNGs
were viewed after rendering and confirmed to have zero title/node overlaps. All three
`invariant-checks.yml` gate scripts (`verify-caddy-image-tag.py`,
`verify-compose-ports-selftest.py`, `verify-compose-ports.py`) re-ran clean.

## Verification run (all commands from the plan's `<verification>` block)

1. `bash -n scripts/render-diagrams.sh` — parses, exit 0.
2. `bash scripts/render-diagrams.sh --check infra-packet-path-scenario` and same for
   `infra-physical-deployment` — both pass exactly (0.00% height delta, since these two were just
   rendered by this same pinned renderer).
3. CI invariant scripts (all pass, run locally exactly as `invariant-checks.yml` runs them):
   - `python3 scripts/verify-caddy-image-tag.py` → `invariants OK: computed
     tag=2.11.4-rl5625512f compose image=rudenkovladimir/kanban-board-caddy:2.11.4-rl5625512f`
   - `python3 scripts/verify-compose-ports-selftest.py` → `invariants OK: every invariant (I1-I7)
     proven to fire, plus malformed-document, scoping-trap and clean-document cases`
   - `python3 scripts/verify-compose-ports.py` → `invariants OK -- ... every compose file at the
     repo root accounted for`
4. `git status --porcelain docs/diagrams/ | grep -cE '\.png$'` on the clean post-commit tree → `0`.
5. `git diff --numstat main..HEAD -- docs/DIAGRAM_CONVENTIONS.md` → `42  0` (zero deletions).
6. Pre-commit hook (`gitleaks` + `spotlessCheck` + `fastTest`) ran and passed clean on all three
   commits; `secret-scan.yml` runs on every push with no path filter and is not locally runnable,
   noted per the plan as an unrun-but-unskippable CI gate.

**Deliberately not run** (per the plan, stated so silence isn't mistaken for coverage): no markdown
linter is configured in this repository; `shellcheck` is not installed, so `bash -n` is the only
static check `render-diagrams.sh` gets; `./gradlew test`/`spotlessCheck` ran anyway as part of the
pre-commit hook (they run on every commit regardless of files touched) but this change touches no
Java, so they exercised nothing related to it.

## Known Stubs

None. No hardcoded empty values, placeholder text, or unwired data sources were introduced.

## Deviations from Plan

**1. [Judgement call, not a deviation] `architecture-mutation-flowchart` scale.** The plan flagged
a possible non-integer-multiple residual for this diagram's scale. Empirical testing found scale 4
reproduces its committed width (2044px) exactly, same as the other `architecture-*` sequence
diagrams — no forced/approximate scale was needed, and no residual is recorded in the manifest.

No Rule 1/2/3 auto-fixes were needed; the plan's action text was followed as written for all three
tasks.

## Self-Check: PASSED

- `scripts/render-diagrams.sh` — FOUND, executable, `bash -n` clean.
- `docs/diagrams/render-manifest.tsv` — FOUND, 9 non-comment rows.
- `docs/diagrams/infra-packet-path-scenario.mmd` / `.png` — FOUND.
- `docs/diagrams/infra-physical-deployment.mmd` / `.png` — FOUND, modified.
- `docs/DIAGRAM_CONVENTIONS.md` — FOUND, Layout rules section present.
- `docs/INFRA_ARCHITECTURE.md` — FOUND, new Scenario section present.
- `.planning/todos/pending/2026-09-05-seven-diagrams-drift-from-the-pinned-renderer.md` — FOUND.
- `.planning/todos/pending/2026-09-05-convert-architecture-mutation-flowchart-td-to-tb.md` — FOUND.
- Commit `93ca958` — FOUND in `git log`.
- Commit `7e9bc5b` — FOUND in `git log`.
- Commit `199f1c6` — FOUND in `git log`.
- Commit `2b0bd7b` — FOUND in `git log` (post-review layout fixes, HEAD).
- Both diagrams re-viewed after the fourth commit — FOUND, zero title/node overlaps in either PNG.
