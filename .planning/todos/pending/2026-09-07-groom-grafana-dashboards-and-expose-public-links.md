---
created: 2026-09-07T00:00:00.000Z
title: "Groom the three phase-12 Grafana dashboards and expose selected ones as public links"
area: infra
severity: minor
files:

  - docker/grafana/provisioning/dashboards/json/node-exporter-full.json
  - docker/grafana/provisioning/dashboards/json/cadvisor.json
  - docker/grafana/provisioning/dashboards/json/postgres-exporter.json

---

## Problem

Phase 12 (12-04) vendored three community Grafana dashboards as-is from grafana.com to get real
data rendering quickly: "Node Exporter Full" (ID 1860), "Cadvisor exporter" (ID 14282), and
"PostgreSQL Exporter" (ID 12485). Their titles are the upstream authors' generic names, not
anything a first-time viewer — e.g. a recruiter or technical interviewer looking at the portfolio
this project supports — would immediately understand as "here's the VM's host metrics" / "here's
per-container resource usage" / "here's the database's internal health."

There is also currently no way to view any dashboard without a Grafana login (D-03's deliberate
single gate in front of every metric this project collects). That's the right default for
operational use, but it's friction for someone with 30 seconds to glance at a portfolio link.

## Solution

Two separable pieces of work:

1. **Rename for clarity.** Set each dashboard's `title` (and optionally `description`, which
   already carries grafana.com provenance — keep that, just make the title itself scannable) to
   something a non-operator immediately understands, e.g. "Node Exporter Full" -> "VM Host Metrics",
   "Cadvisor exporter" -> "Per-Container Resource Usage", "PostgreSQL Exporter" -> "Postgres
   Internals". Keep the provenance (grafana.com ID/revision/fetch date) in the description exactly
   as 12-04 recorded it — only the display title changes.

2. **Expose selected dashboards via Grafana's built-in public-dashboard feature** (a per-dashboard
   read-only share link, not org-wide anonymous access — see this project's 2026-09-07 session
   discussion for why the scoped feature was picked over `GF_AUTH_ANONYMOUS_ENABLED`). Before
   publishing, review each dashboard's panels for anything not meant to be public — real hostnames,
   internal IPs, or anything beyond generic container/metric names — since the underlying data
   genuinely becomes public once a dashboard is published this way, not merely link-obscured.

Not started; both pieces need a live Grafana session (the CLI/API path for public dashboards may
also work — check current Grafana 13.x docs at execution time) and a decision on which of the
three (likely all three, but confirm) get public links.

## Partial resolution (quick task 260908-mtl, 2026-09-08)

This todo names two separable pieces of work. Quick task 260908-mtl closed one of them:

- **Piece 1, renaming — CLOSED.** All three dashboards were renamed to scannable display titles:
  "Node Exporter Full" -> "VM Host Metrics", "Cadvisor exporter" -> "Per-Container Resource Usage",
  "PostgreSQL Exporter" -> "Postgres Internals". Each dashboard's `description` (grafana.com ID,
  revision, fetch date) and `uid` were deliberately left untouched, so provenance and dashboard
  identity both survive the rename.
- **Piece 2, public dashboard links — STILL OPEN.** This still needs a live Grafana session, a
  per-dashboard panel review for real hostnames / internal IPs before publishing (because the
  underlying data genuinely becomes public once published, not merely link-obscured), and a
  decision on which of the three get links.

This todo stays in `pending/` rather than moving to `completed/` — the remaining piece is real
work, not a formality.
