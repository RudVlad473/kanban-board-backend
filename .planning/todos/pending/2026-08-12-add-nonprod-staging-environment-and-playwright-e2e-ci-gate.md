---
created: 2026-08-12T00:00:00.000Z
title: Add a nonprod/staging environment and wire Playwright E2E tests against real (non-mocked) deploys
area: infra
severity: minor
resolves_phase: 8
files: []
audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Phase 5 (infra-migration) as planned provisions exactly one environment: production
(one Oracle A1.Flex VM, one Neon project, one Redpanda broker, one Caddy/DuckDNS
subdomain). There is no nonprod/staging target and no Playwright/E2E CI wiring anywhere
in `.planning/ROADMAP.md`, the Phase 5 plans, or `05-DISCUSSION-LOG.md`/`05-CONTEXT.md`
(confirmed by grep during the 2026-08-12 resume session, before Phase 5's VM was created).

Surfaced when the operator mentioned they're building a separate frontend and will
eventually have Playwright E2E tests that need to run against the real, deployed backend
(not a mock) -- most likely browser-driven tests exercising the frontend against a live
API, not just Playwright's `request` context doing pure HTTP-level API testing, since a
real frontend repo is involved.

## Considered, not yet decided

- **Second environment placement.** Oracle's Always Free `A1.Flex` ceiling (2 OCPU/12GB)
  is the *entire* free ARM budget for this tenancy -- no room for a second full A1 VM
  without incurring cost. Two free-tier-compatible options: (a) a second, resource-capped
  Docker Compose stack colocated on the same VM as prod, or (b) one of Oracle's separate
  Always-Free AMD `E2.1.Micro` shapes (2 included, independent of the A1 pool) as a
  dedicated small nonprod box.

- **DB isolation** -- Neon branching (free, native) gives nonprod its own branch off the
  same project with no new infra.

- **Kafka isolation** -- either a topic-name prefix per environment on a shared Redpanda
  broker, or a second single-node broker if nonprod lands on its own VM.

- **DNS/TLS** -- a second Caddy vhost + subdomain (e.g. `staging.<duckdns-domain>`).
- **CI/CD gating** -- a GitHub Actions stage that deploys to nonprod, runs the Playwright
  suite (living in the separate frontend repo) against the live nonprod URL, and only
  then promotes to prod.

- **CORS** -- Phase 07.1 added CORS support for local frontend dev origins only; a
  deployed nonprod frontend origin would need to be added to the allowed-origins
  configuration once nonprod exists.

## Solution

Not scoped here. Recommended next step: decide whether this becomes its own new phase
(after Phase 5 ships a working single prod environment) or an amendment to Phase 5's
CI/CD plan (05-05), then run it through normal phase discussion/planning rather than
bolting it onto Phase 5's remaining plans (05-04/05-05/05-06) unreviewed.
