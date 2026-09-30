# Phase 11 — API Coverage Decision

**Determined:** 2026-08-26 (plan time)

No external API integration: this phase stands up a self-hosted PostgreSQL 16 container on the
existing Netcup VPS and rewires Docker Compose, `application.properties`, and a GitHub Actions
job to reach it — no external API, SDK, or third-party service is being wrapped or consumed.

## Detector result

The deterministic detector (`gsd-core/bin/lib/api-coverage.cjs`) is **not present in this
project's GSD install** — `node gsd-core/bin/lib/api-coverage.cjs --json` exits
`MODULE_NOT_FOUND`. Recorded here as a visible, not silent, fallback: the checkpoint was
resolved by re-reading the phase scope rather than by a detector verdict.

## Why the scope contains no external API surface

Every external system this phase touches is either being **removed** or is an already-owned
piece of infrastructure, not an API being integrated:

| System in scope | Relationship |
|---|---|
| Neon (managed Postgres) | Being **decommissioned** (D-07), not integrated — its surface shrinks to zero |
| `postgres:16` Docker image | Self-hosted process on our own VM, spoken to over JDBC/libpq — a datastore, not a service API |
| `flyway/flyway:11.7.2` Docker image | A CLI already in use in this repo's CI, re-pointed at a new host — no new API surface |
| GitHub Actions / `appleboy/ssh-action` | Existing CI plumbing already in use, re-pointed — no new API surface |
| Docker Engine on the VM | Host tooling, already in use by every prior deploy |

There is therefore no capability surface to enumerate, and fabricating matrix rows for a
capability set that does not exist would be worse than this declaration.

## Seal-time note

This file satisfies the `verify:pre` API-coverage gate in its "reasoned declaration" form.
If a future phase adds a real external API (a backup-as-a-service provider, a monitoring
vendor, a managed Postgres provider), that phase — not this one — owes the matrix.
