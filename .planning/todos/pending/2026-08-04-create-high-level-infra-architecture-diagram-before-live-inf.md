---
created: 2026-08-04T12:25:41.058Z
title: Create high-level infra architecture diagram before live infra onboarding
area: docs
severity: minor
resolves_phase: 5
files: []
---

## Problem

The v1.2 infra stack (Oracle Cloud VM boundary running the app + self-hosted Redpanda + Caddy, talking out to Neon and GitHub Actions, plus the Schema Registry data flow layered on top from Phase 4) has gotten complex enough that it's worth diagramming before doing the actual live infra onboarding in Phase 5 — not just planning it. No diagram exists today.

**Trigger:** Before starting Phase 5's actual live infra onboarding work (real VM provisioning, DNS, first deploy) — not before Phase 5 planning/discussion, which can proceed without it. Tagged `resolves_phase: 5` so it surfaces alongside that phase.

## Solution

Mermaid diagram(s), checked into `docs/` (versioned, diffable, renders natively on GitHub):
1. A C4-style Context/Container diagram: the Oracle VM boundary (app + Redpanda + Caddy) and its external dependencies (Neon, GitHub Actions).
2. Optionally, a second lower-level diagram for the Kafka / Schema Registry data flow (producer → Redpanda → Schema Registry → consumer → dead-letter topic), now that Phase 4 adds real structure there.
</content>
