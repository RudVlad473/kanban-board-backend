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

**Why this convention exists:** raised during Phase 5 planning after discovering the CI pipeline's
Docker image build was x86_64-only while the deploy target (Oracle A1 Flex) is ARM64. A plain
C4-style container diagram of "what talks to what" would not have surfaced this — a Physical/
Deployment view with node-architecture annotations would have, because the mismatch lives entirely
in "what hardware is this node" rather than in the logical topology.
