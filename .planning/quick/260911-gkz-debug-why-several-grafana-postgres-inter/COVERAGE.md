# API Coverage Decision — 260911-gkz

No external API integration: debugging existing postgres-exporter/Grafana observability config, not integrating a new API.

## Basis

Confirmed by reading the repo before writing the plan:

- `postgres-exporter` is already present in `docker-compose.prod.yml` at pinned
  `prometheuscommunity/postgres-exporter:v0.20.1`; this plan does not introduce it, does not change
  its tag, and does not add a new service.
- Grafana (`grafana/grafana:13.2.1`) and Prometheus are already provisioned; the dashboard under
  investigation is already committed at
  `docker/grafana/provisioning/dashboards/json/postgres-exporter.json`.
- The only external surfaces the plan contacts are read-only observations of systems this repo
  already owns and deploys: the live Grafana instance (as a Viewer) and the production VPS over
  the already-documented `netcup-prod` SSH host.
- The candidate fixes are all in-repo config edits — exporter collector flags, dashboard PromQL
  matchers — plus, if and only if the user approves it at the Task 2 checkpoint, a Postgres
  `shared_preload_libraries` change. None of these is an API/SDK integration.

## Other checkpoints

- **Assumption-Delta Architecture:** skipped. No singular→plural, required→optional or
  derived→chosen transition. No second exporter instance, tenant, auth method or source of truth
  is introduced.
- **Schema Push Detection:** skipped. This project uses Flyway/JPA, not Payload/Prisma/Drizzle/
  Supabase/TypeORM. No Flyway migration is in scope: the `monitoring` role is deliberately
  provisioned outside the init script (see `docs/INFRA_RUNBOOK.md`, "The monitoring role, and why
  it is not an init script"), and the plan admits a DB grant only against a captured
  permission-denied error, gated behind the Task 2 decision checkpoint.

## Multi-source coverage audit

| Source | Item | Status | Covered by |
|---|---|---|---|
| TASK | Root-cause PostgreSQL Uptime N/A | COVERED | Task 1, candidate 1 |
| TASK | Root-cause Max Connections N/A | COVERED | Task 1 tracer tile, candidate 3 |
| TASK | Root-cause Query rate N/A | COVERED | Task 1, candidate 2 |
| TASK | Root-cause Average query runtime N/A | COVERED | Task 1, candidate 2 |
| TASK | Root-cause Shared Buffers N/A | COVERED | Task 1, candidate 3 |
| TASK | Root-cause Locks by state empty | COVERED | Task 1, candidate 4 |
| TASK | Root-cause Deadlocks by database empty | COVERED | Task 1, candidate 4 |
| TASK | Verify exporter version/config/role rather than assume | COVERED | Task 1 layers 1 and 2 |
| TASK | Headless-browser inspection of the live dashboard | COVERED | Task 1 layer 4 |
| TASK | Read repo artifacts (docker/, compose files, dashboard JSON, runbook, exporter role/grants) | COVERED | Done at planning time; recorded as named candidates in the plan's context section |
| TASK | Read-only, non-destructive; fix lands as a repo change not a live Grafana edit | COVERED | Task 1 observe-only instruction, Task 3 in-repo-only instruction, T-gkz-04 |
| TASK | Fix the missing metrics | COVERED | Task 3, scoped by the Task 2 checkpoint |
| CONSTRAINT | Single plan, 1–3 tasks | COVERED | 3 tasks |
| CONSTRAINT | Mutable-scope authority: fix conditioned on live findings | COVERED | Task 2 decision checkpoint gates Task 3 |
| CONSTRAINT | Credentials never echoed into a committed file, log or SUMMARY | COVERED | Task 1 action + negative-grep gate; T-gkz-01 |
| CONSTRAINT | No research phase | COVERED | None planned |
| GATE | `<threat_model>` with ASVS L1 dispositions | COVERED | 7 threats, all dispositioned and severity-rated |
| GATE | 2 alternate approaches + trade-off matrix (project CLAUDE.md) | COVERED | "Approaches considered" table, 3 rows |
| GATE | Data-flow mechanism in ≤3 sentences (project CLAUDE.md) | COVERED | "Mechanism" section |

No MISSING items. No item was deferred.
