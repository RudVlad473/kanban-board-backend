---
created: 2026-08-01T17:51:59.209Z
resolved: 2026-08-10
resolves_phase: "07.1"
title: Create sequence diagram documenting full system flow for frontend handoff
area: docs
severity: minor
files:
  - docs/ARCHITECTURE.md
---

## Problem

Once all functional epics of the backend modernization plan (Kafka activity feed, Flyway/OpenAPI
polish, Redis, Testcontainers, Observability, Kubernetes) are complete, the project will be ready
to hand off for frontend integration. At that point there's no single artifact that walks a new
consumer (frontend dev, or future self) through how a request actually flows through the system
end-to-end — auth, ownership verification, the event-driven activity-log side path introduced in
the Kafka epic, session handling, etc. A sequence diagram would give that handoff a concrete,
visual reference instead of requiring someone to reconstruct the flow from source.

## Solution

TBD — likely a Mermaid sequence diagram (or a small set of them, one per major flow: auth/session,
a typical CRUD mutation with its Kafka side-effect, and the activity-log read path) committed to
`docs/` or `README.md`. Defer any concrete design work until the modernization plan's remaining
epics are actually done, since the diagram should reflect the final architecture, not an
intermediate state.

**Trigger:** Not before all functional epics (Epics 1–7 of `docs/plans/backend-modernization/`)
are complete and the project is considered ready for frontend integration.

## Resolution

Trigger condition met by phase 07.1 (Address hard blockers and inconsistencies from the
frontend-integration-readiness audit) — the phase whose own goal is closing out the last
frontend-readiness gaps before hand-off, per D-25/D-26 (`07.1-CONTEXT.md`). Delivered in
`07.1-09-PLAN.md` Task 3, deliberately run **last** in the phase (D-26) so the diagrams document
the settled post-fix, post-scan state rather than an intermediate one.

Four Mermaid `sequenceDiagram` blocks added to `docs/ARCHITECTURE.md`, one more than this todo's
original three-diagram sketch — the fourth (error-handling/auth-gating across 401/403/400/409) was
added by D-25 specifically because 07.1 is what made that behavior consistent for the first time,
and a frontend hand-off needs to know exactly what each error status means:

1. **Signin and session establishment** (Scenarios(+1) view) — under "Layering and access
   control".
2. **The error-handling/auth-gating flow** (Scenarios(+1) view, all four of 401/403/400/409 in one
   diagram) — under "Layering and access control".
3. **Sequence view of the PATCH /tasks/{taskId}/move mutation and its Kafka side-effect**
   (Process View) — extends the pre-existing "Process View — path of a mutation" flowchart under
   "Event-driven activity feed" rather than duplicating it.
4. **The activity-feed read path** (Process View) — same section.

Every participant and message was traced to a source file read during the task (no diagram drawn
from memory of how such a system usually works), each diagram is preceded by prose naming the
question it answers, and each is followed by a "Simplified:" line naming what was deliberately
left out. Placed in `docs/ARCHITECTURE.md` per D-25 (not a new file) and per
`docs/DIAGRAM_CONVENTIONS.md`'s 4+1 view mapping. No new documentation file was created.
