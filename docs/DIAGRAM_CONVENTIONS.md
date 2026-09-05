# Diagram Conventions

Architecture diagrams for this project should aim to snap to **Kruchten's 4+1 architectural view
model** rather than an ad hoc mix of styles, so each diagram has one clear, singular concern instead
of quietly blending several.

- **Logical View** — key domain classes/objects and their relationships. Rarely needed as a separate
  diagram here; the entity/DTO structure already documents this in code.
- **Process View** — runtime concurrency and communication between processes (e.g. the app JVM, the
  Redpanda broker, Caddy).
- **Development View** — module/subsystem organization from a builder's perspective. Roughly what a
  C4 container diagram captures.
- **Physical View (Deployment)** — mapping of software to physical/hardware nodes. **Always annotate
  each node with its platform/CPU architecture** (e.g. "GitHub Actions runner: x86_64" vs "Oracle A1
  Flex VM: ARM64/Ampere"), not just its name and IP. This is the view that would have caught the
  amd64-image-on-an-arm64-VM mismatch found during Phase 5 (Infra Migration) planning on 2026-08-04,
  before it became a runtime failure.
- **Scenarios (+1)** — one or two key end-to-end scenarios (e.g. "push to master → deploy", "signup →
  board creation") traced across the other views, to confirm they stay consistent with each other.

Not every diagramming task needs all five views. Pick whichever view(s) are relevant to what's being
communicated — the point is to be deliberate about which view a given diagram *is*, and to not
silently collapse a logical/development concern (what talks to what) with a physical/deployment one
(what runs where, on what hardware) in the same picture.

## Layout rules for flowcharts

Applies to `flowchart` diagrams only — this repository's `sequenceDiagram`s follow Mermaid's own
top-to-bottom message ordering and are out of scope here. Four rules, each with the test that
decides whether a diagram satisfies it, so a reader can check without asking anyone.

**1. `flowchart TB`, never `TD`.** The two keywords are documented aliases producing identical
output, so this is purely about greppability: one spelling makes `rg -n 'flowchart TB'
docs/diagrams/` a complete inventory, and a mixed vocabulary makes it silently partial. Counted
2026-09-05: `infra-physical-deployment.mmd` already uses `TB`; `architecture-mutation-flowchart.mmd`
is the one file still spelled `TD` (tracked as a todo, not converted here — see below).
*Test:* `rg -n 'flowchart TD' docs/diagrams/` returns nothing.

**2. One vertical spine, outcomes as leaves.** A flowchart has exactly one primary top-to-bottom
chain of nodes carrying the main path, with terminal outcomes hanging off it as leaves rather than
threaded back into the chain.
*Test:* delete every leaf. What remains must still read as one unbroken sequence — if removing a
node breaks the chain, that node is spine, not leaf, and belongs in the trunk.

**3. `~~~` invisible links for rank alignment only.** When two nodes must render at the same
vertical rank but carry no semantic relationship, join them with `~~~` rather than reordering
declarations or inserting a styled-invisible dummy edge. `infra-physical-deployment.mmd`'s
`netcup_spacer ~~~ caddy_box` is the existing precedent.
*Test:* removing the `~~~` must change only geometry, never meaning. If removing it also removes
information, it was carrying semantics and should have been a real edge instead.

**4. A shared `%%{init}%%` block.** Every flowchart opens with the same init block — currently
`subGraphTitleMargin` top/bottom 15 and `curve: linear` — so subgraph titles don't collide with
their first child and edges render straight rather than bezier.
*Test:* two flowcharts in this repo placed side by side must not differ in edge curvature or
subgraph title spacing. If they do, one is missing the block or has diverged from it.

These four rules were unverifiable before `scripts/render-diagrams.sh` existed — there was no
committed way to render a `.mmd` and check the result against any of them. Use it to check any of
the above.

**Known outstanding offender:** `architecture-mutation-flowchart.mmd` is still `flowchart TD` as of
2026-09-05. Its PNG is out of scope for the task that added this section — converting the source's
direction keyword without re-rendering would manufacture exactly the source/render divergence that
task's drift check exists to detect. Tracked as
`.planning/todos/pending/2026-09-05-convert-architecture-mutation-flowchart-td-to-tb.md`.

**Why this convention exists:** raised during Phase 5 planning after discovering the CI pipeline's
Docker image build was x86_64-only while the deploy target (Oracle A1 Flex) is ARM64. A plain
C4-style container diagram of "what talks to what" would not have surfaced this — a Physical/
Deployment view with node-architecture annotations would have, because the mismatch lives entirely
in "what hardware is this node" rather than in the logical topology.
