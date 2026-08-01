---
created: 2026-08-01T17:51:59.209Z
title: Create sequence diagram documenting full system flow for frontend handoff
area: docs
severity: minor
files: []
---

## Problem

Once all functional epics of the backend modernization plan (Kafka activity feed, Flyway/OpenAPI polish, Redis, Testcontainers, Observability, Kubernetes) are complete, the project will be ready to hand off for frontend integration. At that point there's no single artifact that walks a new consumer (frontend dev, or future self) through how a request actually flows through the system end-to-end — auth, ownership verification, the event-driven activity-log side path introduced in the Kafka epic, session handling, etc. A sequence diagram would give that handoff a concrete, visual reference instead of requiring someone to reconstruct the flow from source.

## Solution

TBD — likely a Mermaid sequence diagram (or a small set of them, one per major flow: auth/session, a typical CRUD mutation with its Kafka side-effect, and the activity-log read path) committed to `docs/` or `README.md`. Defer any concrete design work until the modernization plan's remaining epics are actually done, since the diagram should reflect the final architecture, not an intermediate state.

**Trigger:** Not before all functional epics (Epics 1–7 of `docs/plans/backend-modernization/`) are complete and the project is considered ready for frontend integration.
