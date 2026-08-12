---
created: 2026-08-04T12:25:41.058Z
resolved: 2026-08-12
resolves_phase: 5
title: Create high-level infra architecture diagram before live infra onboarding
area: docs
severity: minor
files:
  - docs/INFRA_ARCHITECTURE.md
---

## Problem

The v1.2 infra stack (Oracle Cloud VM boundary running the app + self-hosted Redpanda + Caddy, talking out to Neon and GitHub Actions, plus the Schema Registry data flow layered on top from Phase 4) has gotten complex enough that it's worth diagramming before doing the actual live infra onboarding in Phase 5 — not just planning it. No diagram exists today.

**Trigger:** Before starting Phase 5's actual live infra onboarding work (real VM provisioning, DNS, first deploy) — not before Phase 5 planning/discussion, which can proceed without it. Tagged `resolves_phase: 5` so it surfaces alongside that phase.

## Solution

Mermaid diagram(s), checked into `docs/` (versioned, diffable, renders natively on GitHub):
1. A C4-style Context/Container diagram: the Oracle VM boundary (app + Redpanda + Caddy) and its external dependencies (Neon, GitHub Actions).
2. Optionally, a second lower-level diagram for the Kafka / Schema Registry data flow (producer → Redpanda → Schema Registry → consumer → dead-letter topic), now that Phase 4 adds real structure there.

## Resolution

Resolved by `docs/INFRA_ARCHITECTURE.md`, delivered in plan 05-02 (task 3). Per
`docs/DIAGRAM_CONVENTIONS.md` (itself written during this phase's planning after discovering the
CI pipeline's image was x86_64-only while the deploy target is ARM64), the deliverable landed as
two deliberately-scoped Kruchten 4+1 views rather than a single blended C4-style diagram:

1. A **Physical/Deployment view** — the Oracle VM as an explicit trust boundary containing the
   `caddy`, `app` and `redpanda` containers, Neon as an external system, and every node labelled
   with its CPU architecture (Oracle VM: ARM64/Ampere) — the annotation discipline that would have
   caught this same phase's amd64-image-on-an-arm64-VM finding had it existed before the mismatch
   was found by other means.
2. A **Scenario (+1) view** (sequence diagram) of the delivery path — push to master through
   GitHub Actions (labelled x86_64 runner) to Docker Hub and the VM over SSH — explicitly labelled
   as the target state after plan 05-05 lands, not as currently live.

Each diagram carries a prose section on externally-reachable vs. internal-only ports, where TLS
terminates, and which components are stateful and where that state lives. A maintenance note
points back at the three files (`docker-compose.prod.yml`, `Caddyfile`,
`.github/workflows/deploy.yml`'s build job) the diagram describes.

The optional second (Kafka/Schema-Registry data-flow) diagram this todo floated was not added —
Phase 4's own verification artifacts already document that flow in depth, and duplicating it here
would create a second, driftable source of truth for a data flow this phase does not change.
</content>
