# Diagram Conventions

Architecture diagrams for this project should aim to snap to **Kruchten's 4+1 architectural view
model** rather than an ad hoc mix of styles, so each diagram has one clear, singular concern instead
of quietly blending several.

- **Logical View** — key domain classes/objects and their relationships. Rarely needed as a separate
  diagram here; the entity/DTO structure already documents this in code.
- **Process View** — runtime concurrency and communication between processes (e.g. the app JVM's
  request thread, its `kafka-publish` executor and its Kafka consumer thread, the Redpanda broker,
  Traefik).
- **Development View** — module/subsystem organization from a builder's perspective. Roughly what a
  C4 container diagram captures.
- **Physical View (Deployment)** — mapping of software to physical/hardware nodes. **Always annotate
  each node with its platform/CPU architecture** (e.g. "GitHub Actions runner: x86_64" vs "Oracle A1
  Flex VM: ARM64/Ampere"), not just its name and IP. This is the view that would have caught the
  amd64-image-on-an-arm64-VM mismatch found during Phase 5 (Infra Migration) planning on 2026-08-04,
  before it became a runtime failure.
- **Scenarios (+1)** — one or two key end-to-end scenarios (e.g. "push to main → deploy", "signup →
  board creation") traced across the other views, to confirm they stay consistent with each other.

Not every diagramming task needs all five views. Pick whichever view(s) are relevant to what's being
communicated — the point is to be deliberate about which view a given diagram *is*, and to not
silently collapse a logical/development concern (what talks to what) with a physical/deployment one
(what runs where, on what hardware) in the same picture.

## What earns a diagram

The test: **does the diagram's truth survive a refactor?** These categories pass:

- runtime topology;
- process, thread and messaging boundaries;
- deployment (nodes, hardware, network layers);
- protocol-level scenarios (HTTP, session, Kafka, status-code semantics);
- module-level dependency direction.

A diagram fails if its lifelines or nodes are **classes calling classes**: a controller → service →
repository chain, a service-to-service call graph, a test base-class hierarchy. The reason is
upkeep, not taste. Class graphs churn on every refactor, and no check can catch the drift: a diagram
whose boxes are class names stays green while the code under it moves. Name lifelines and nodes by
runtime role instead (client, filter chain, a thread pool, a table, a topic, a pod, a node), put the
class names in the embedding doc's prose, and let the messages carry protocol facts: method and
path, status and `ProblemDetail` `code`, cookie or header, SQL-level action, topic, consumer group.
A fact a dropped class-level diagram carried moves into the prose that embedded it.

## Layout and naming

Every diagram is `docs/diagrams/<view>/<subject>.mmd` plus a rendered `<subject>.png` twin in the
same folder. `<view>` is one of `logical`, `process`, `development`, `physical`, `scenarios`
(Kruchten's names; Scenarios is the "+1"). A folder exists only while it holds a diagram. The
`<subject>` rules:

- kebab-case, matching `[a-z0-9]+(-[a-z0-9]+)*`;
- it names what is drawn, not the view (the folder carries that) and not the diagram type
  (flowchart or sequence is a rendering choice that can change while the subject stays the same);
- no old area prefix (`architecture-`, `auth-`, `infra-`).

The render manifest key is `<view>/<subject>`. Only two files sit at the top level of
`docs/diagrams/`: `render-manifest.tsv` and `mermaid-config.json`. A diagram that moves between
views is a `git mv` of both files plus a manifest key rename, as its own commit with no content
edit, so `git log --follow` keeps the history.

Current inventory:

| View | Path | Question it answers | Embedded in |
|---|---|---|---|
| Process | `process/activity-pipeline` | How does a committed mutation become an `activity_log` row, thread by thread? | `ARCHITECTURE.md` |
| Physical | `physical/production-host` | What runs where, on what hardware? | `INFRA_ARCHITECTURE.md` |
| Scenarios | `scenarios/signin` | What happens between a `POST` of credentials and a session cookie landing in Postgres? | `ARCHITECTURE.md`, `AUTH_FLOWS.md` |
| Scenarios | `scenarios/signup` | What do signup, its auto-authentication and its rollback look like to a client? | `AUTH_FLOWS.md` |
| Scenarios | `scenarios/error-status-split` | Which layer answers each of 401/403/400/409, and does the request reach application code? | `ARCHITECTURE.md` |
| Scenarios | `scenarios/push-to-deploy` | How does a push to `main` become a running deploy? | `INFRA_ARCHITECTURE.md` |
| Scenarios | `scenarios/inbound-packet-path` | Which network layers does one inbound packet cross on the VM? | `INFRA_ARCHITECTURE.md` |

`README.md` lists the same seven in its Diagrams table, and embeds an inline copy of the physical
view that is a simplified rendering of `physical/production-host.mmd`; the `.mmd` is canonical.

## Rendering

`scripts/render-diagrams.sh` renders every manifest entry through one digest-pinned `mermaid-cli`
container, so a PNG's pixels do not depend on which machine rendered it. `render-diagrams.sh --check
--all` renders into a scratch directory and compares each result with the committed PNG; it writes
nothing under `docs/diagrams/`.

- **`docs/diagrams/mermaid-config.json` turns `useMaxWidth` off** for flowcharts and sequence
  diagrams. Measured 2026-10-05: `mmdc` lays out into an 800 px viewport and, with the default
  `useMaxWidth: true`, shrinks every SVG to fit inside it (784 px) before rasterising. The `-s` flag
  multiplies pixels, not layout width, so 784 × 1, × 2 and × 4 were exactly the old committed widths
  of 784, 1568 and 3136, and a diagram that was 3600 px wide at its natural size came out with 4 px
  text. With the config the layout keeps its natural width.
- **The scale is uniformly 2.** Every manifest row says `2`, so a PNG is a 2× raster and its natural
  width is the PNG width divided by 2. A label is then 32 device px in the raster, which stays sharp
  on a devicePixelRatio-2 screen and on click-through.
- **`--check` compares the PNG's width exactly and its height within 2%.** Because width now tracks
  content, a label edit that changes the bounding box fails the check. A label edit that leaves the
  bounding box unchanged still passes: the check reads only the PNG header.

## Legibility test

The test is about text size as the reader sees it on GitHub. GitHub's `article.markdown-body` column
was measured on 2026-10-05 with headless Chromium, logged out:

| Viewport | Blob view of `docs/*.md` | Repo-home README |
|---|---|---|
| 1024 | 605 | 582 |
| 1280 | 861 | 838 |
| 1366 | 947 | 838 |
| 1440 / 1920 | 1012 | 838 |

`D = 838` px is the narrowest column at a viewport of 1280 px or wider. Narrower viewports are out
of target. GitHub keeps the `width` attribute on `<img>` and adds `max-width: 100%`.

Mermaid 11.17 draws every label at 16 px. For a diagram of natural width `W`, with `W` the PNG
width divided by 2:

- displayed text px = `16 × min(1, 838 / W)`;
- displayed height = `H × min(1, 838 / W)`, with `H` the PNG height divided by 2.

Two bounds follow, and **both are chosen, not standards**:

- **Text floor 12 CSS px**, three quarters of the 16 px body text. Hence `W_MAX = floor(16 × 838 /
  12) = 1117` px natural width.
- **Displayed height at most 1600 CSS px**, about two 800 px screens.

A diagram that fails gets these levers, in this order, and **a threshold is never raised to pass**:

1. Shorten labels and notes, moving rationale into the embedding doc's prose (notes at most two
   short lines).
2. Re-lay out: fewer lifelines by merging same-thread or same-node roles, rows of subgraphs aligned
   with `~~~`, an inner subgraph `direction LR` while the top level stays `TB`.
3. Split into two subjects in the same view.

**Embed format.** A diagram PNG is embedded as `<img src="<relative path>.png" width="<W>"
alt="<Kind>: <what it shows>">`, the standard idiom for a 2× raster at an explicit CSS width, with
the `<sub>[diagram source](…)</sub>` line after it. Markdown image syntax (`![…](….png)`) is not
allowed for a diagram PNG: it would display the 2× raster at up to twice its intended size. A plain
`[PNG](…)` link is fine.

## Layout rules for flowcharts

Applies to `flowchart` diagrams only — this repository's `sequenceDiagram`s follow Mermaid's own
top-to-bottom message ordering and are out of scope here. Four rules, each with the test that
decides whether a diagram satisfies it, so a reader can check without asking anyone.

**1. `flowchart TB`, never `TD`.** The two keywords are documented aliases producing identical
output, so this is purely about greppability: one spelling makes `rg -n 'flowchart TB'
docs/diagrams/` a complete inventory, and a mixed vocabulary makes it silently partial.
*Test:* `rg -n 'flowchart TD' docs/diagrams/` returns nothing.

**2. One vertical spine, outcomes as leaves.** A flowchart has exactly one primary top-to-bottom
chain of nodes carrying the main path, with terminal outcomes hanging off it as leaves rather than
threaded back into the chain.
*Test:* delete every leaf. What remains must still read as one unbroken sequence — if removing a
node breaks the chain, that node is spine, not leaf, and belongs in the trunk.

**3. `~~~` invisible links for rank alignment only.** When two nodes must render at the same
vertical rank but carry no semantic relationship, join them with `~~~` rather than reordering
declarations or inserting a styled-invisible dummy edge. `physical/production-host.mmd`'s
`netcup_spacer ~~~ traefik_box` is the existing precedent.
*Test:* removing the `~~~` must change only geometry, never meaning. If removing it also removes
information, it was carrying semantics and should have been a real edge instead.

**4. A shared `%%{init}%%` block.** Every flowchart opens with the same init block — currently
`subGraphTitleMargin` top/bottom 15 and `curve: linear` — so subgraph titles don't collide with
their first child and edges render straight rather than bezier. The line is byte-for-byte line 1 of
`physical/production-host.mmd`.
*Test:* two flowcharts in this repo placed side by side must not differ in edge curvature or
subgraph title spacing. If they do, one is missing the block or has diverged from it.

Rules 1 and 4 are checked mechanically by the inventory guard below (rule `flowchart-rules`, for
standalone diagrams and for inline `mermaid` fences alike). Rules 2 and 3 are judgment tests.

## Inline diagrams

A diagram may be written inline as a ```` ```mermaid ```` fence in a `docs/learning/` chapter, where
it explains something the chapter is teaching. Inline diagrams:

- pass the same keep test as standalone ones, and follow the flowchart rules above;
- are never a copy of a standalone diagram: if a chapter needs one, it embeds the PNG or links its
  `.mmd`. A hand-maintained duplicate drifts, and the README's old physical-view copy already did;
- are checked by the guard for the flowchart and class-label rules (not for width, because GitHub
  renders them itself).

## Inventory check

`scripts/verify-diagrams.py` is the mechanical check that this document's rules hold. It is
stdlib-only, enumerates files with `git ls-files` (so an untracked draft is invisible until
`git add`, and it never walks the tree and opens `.env` files), and scans tracked Markdown outside
the GSD, vendored-skill, `docs/wiki/` and `docs/raw/` trees.

```bash
python3 scripts/verify-diagrams.py check                          # exit 0 clean, 1 violations
python3 scripts/verify-diagrams.py check --diagram scenarios/signin   # one diagram and its references
python3 scripts/verify-diagrams.py report                         # legibility table: W, H, text px
python3 scripts/verify-diagrams-selftest.py                       # proves each rule fires on a plant
```

Exit code 2 means an empty scan or bad usage; an empty scan and a clean scan would otherwise look
the same. The rule ids are:

| Rule | Fails when |
|---|---|
| `outside-view-folder` | a diagram file is not directly inside a view folder |
| `missing-twin` | a `.mmd` has no `.png`, or the reverse |
| `manifest-mismatch` | a diagram has no manifest row, or a row has no source |
| `uniform-scale` | a manifest scale is not 2 |
| `bad-name` | a subject is not kebab-case, or carries an old area prefix or a type suffix |
| `bad-png` | a PNG has a bad signature or header |
| `dangling-reference` | a doc names a `diagrams/` path that does not resolve |
| `legibility-width` | natural width exceeds 1117 px |
| `legibility-height` | displayed height exceeds 1600 px |
| `embed-width` | a doc embeds a PNG without `<img width>`, or the width differs from the natural width |
| `flowchart-rules` | a flowchart lacks the shared init line or uses a keyword other than `flowchart TB` |
| `class-level-label` | a label names a class (a CamelCase name ending in a role suffix such as `Service`) |

The guard is **local-only**, like `render-diagrams.sh`: `.github/workflows/invariant-checks.yml`
ignores `docs/**`, and removing that ignore would pay a full deploy pipeline per documentation
commit. It trusts that a PNG came from the renderer, so `bash scripts/render-diagrams.sh --check
--all` is the other half of the proof; run both after touching a diagram.

**Why this convention exists:** raised during Phase 5 planning after discovering the CI pipeline's
Docker image build was x86_64-only while the deploy target (Oracle A1 Flex) is ARM64. A plain
C4-style container diagram of "what talks to what" would not have surfaced this — a Physical/
Deployment view with node-architecture annotations would have, because the mismatch lives entirely
in "what hardware is this node" rather than in the logical topology.
