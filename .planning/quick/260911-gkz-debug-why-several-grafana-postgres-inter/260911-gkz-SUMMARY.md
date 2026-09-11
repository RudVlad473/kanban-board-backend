---
phase: quick
plan: 260911-gkz
subsystem: observability
tags: [grafana, postgres-exporter, prometheus, dashboard, debugging]
dependency-graph:
  requires: []
  provides: []
  affects: [docker/grafana/provisioning/dashboards/json/postgres-exporter.json, docker-compose.prod.yml]
tech-stack:
  added: []
  patterns:
    - "Grafana /api/ds/query used as a curl-driven substitute for a headless browser (no browser MCP/CLI available in this environment)"
    - "Disposable docker network + postgres:16 + pinned postgres-exporter image used to prove a collector/extension fix actually produces the right metric names before touching production"
key-files:
  created:
    - .planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/FINDINGS.md
    - .planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/verify-dashboard-fixes.sh
  modified:
    - docker-compose.prod.yml
    - docker/grafana/provisioning/dashboards/json/postgres-exporter.json
decisions:
  - "Task 2 checkpoint: user approved all 4 fix options (Uptime collector flag, stat_statements collector+extension, singlestat->stat panel migration, collapsed-row datname matcher fix across all 9 affected panels); none rejected"
  - "Item 2 (pg_stat_statements) landed as a repo/config change only -- the production Postgres restart it requires is explicitly deferred to a separate, later, gated deploy, not part of this task"
metrics:
  duration: "~2h (Task 1 investigation + Task 3 implementation, across one session)"
  completed: 2026-09-11
status: complete
actuals:
  tokens: 118000
  tasks: 3
  commits: 2
  plan_head_before: 24812e7
---

# Phase quick Plan 260911-gkz: Debug why several Grafana Postgres Internals tiles are dead Summary

**One-liner:** All 7 dead tiles now have a live-evidence root cause and an approved, locally-verified
fix landed as a repository change across 2 commits; the one fix requiring a production Postgres
restart is deliberately deferred to a separate, later, explicitly-gated deploy.

## What was built

**Task 1 (investigation, commit `a6bd609`):** ran the plan's four-layer probe against the live
production Postgres Internals dashboard -- SSH to `netcup-prod` for Postgres catalogs and the
exporter's own `/metrics`, and Grafana's own `/api/ds/query`/datasource-proxy endpoints via curl
with the viewer credential (substituting for a headless browser, not available as a tool in this
environment). Findings, with literal probe output for all 7 tiles, are in
`.planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/FINDINGS.md`.

**Task 2 (checkpoint):** user approved all four proposed fixes. Full decision record, including
item 2's explicit deploy-boundary caveat, is written into FINDINGS.md's "Task 2 decision record"
section.

**Task 3 (implementation, commit `2ad3656`):**

1. **PostgreSQL Uptime** -- added `--collector.postmaster` to `postgres-exporter`'s `command:` in
   `docker-compose.prod.yml`. No Postgres-side change; takes effect on next redeploy.
2. **Query rate / Average query runtime** -- added `shared_preload_libraries=pg_stat_statements`
   to the `postgres` service's `command:` and `--collector.stat_statements` to the exporter.
   Local verification (a disposable `postgres:16` container with the extension actually created
   and populated, scraped by the pinned `prometheuscommunity/postgres-exporter:v0.20.1` image)
   surfaced a SECOND, independent bug the plan's static reading did not anticipate: this exporter
   version emits `pg_stat_statements_calls_total` and `pg_stat_statements_seconds_total`, not the
   un-suffixed names the dashboard originally queried. Fixed in the same commit (deviation Rule 1)
   -- without it, the dashboard would have kept showing "No data" even after the eventual
   production restart. Confirmed end-to-end locally: both corrected metric names appear on the
   exporter's `/metrics` output once the extension is populated with real query activity.
3. **Max Connections / Shared Buffers** -- migrated both panels from the legacy `singlestat` type
   to the modern `stat` type, fixing the Grafana 13.2.1 rendering defect already documented in
   Phase 12 plan 04 / `12-REVIEW.md` WR-01. Query, `gridPos`, and title preserved exactly.
4. **Locks by state / Deadlocks by database** -- fixed the exact-match `datname` selector across
   all 9 panels sharing the defect inside the collapsed `"Database: $Database"` row (not just the
   2 originally reported tiles), changing `=` to `=~` so a multi-valued `$Database` matches
   correctly regardless of whether the row's legacy collapsed-row-repeat mechanism ever fires.

## Production deploy boundary (explicit, per user instruction)

**Nothing in this task touched production.** All four fixes are committed as `docker-compose.prod.yml`
and dashboard-JSON changes, verified against disposable local containers only:

- Item 2's `shared_preload_libraries=pg_stat_statements` change is committed CONFIG. It has NO
  effect on the live production Postgres container until that container is next restarted --
  `shared_preload_libraries` cannot be applied at runtime. **The production restart has NOT
  happened.** It is a separate, later, explicitly-gated deploy action, along with the one-time
  `CREATE EXTENSION pg_stat_statements` that still needs to run once on both `kanban_prod` and
  `kanban_nonprod` after that restart (same live pattern the `monitoring` role's own creation used
  -- see `docs/INFRA_RUNBOOK.md`'s "Monitoring role and metrics targets" section for the
  precedent). This matches the project's standing practice of reviewing before merging/deploying
  to main.
- Items 1, 3, and 4 also only take effect on the next deploy/dashboard-provisioning reload; no
  live Grafana object or live Postgres instance was modified during this task.
- The user's explicitly acknowledged cost for item 2 (a real outage window on the shared
  production database, and query text/identifiers becoming visible to every Grafana Viewer on the
  public hostname once deployed) applies at THAT future deploy, not to anything done here.

## Local verification performed

- `python3 -m json.tool` on the dashboard JSON.
- `.planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/verify-dashboard-fixes.sh`
  (adapted from the `260908-r16` precedent script): JSON validity, a panel-invariance diff against
  base commit `a6bd609` asserting every untouched panel is byte-identical (gridPos excluded) and
  every changed panel's diff is scoped to exactly the intended fields (type migration, matcher
  operator, or metric rename -- nothing else), and a live provisioning check against a disposable
  Grafana 13.2.1 container confirming the panel types and query strings render as expected with no
  provisioning errors in the container log.
- `scripts/verify-deploy-scp-coverage.py`, `verify-deploy-scp-coverage-selftest.py`,
  `verify-compose-ports.py`, and `verify-postgres-memory-invariant.py` all pass against the
  modified `docker-compose.prod.yml`.
- A disposable `postgres:16` + pinned exporter end-to-end run (own Docker network, no shared
  state with any other container) proving `pg_postmaster_start_time_seconds`,
  `pg_stat_statements_calls_total`, and `pg_stat_statements_seconds_total` all appear on the
  exporter's `/metrics` output once the collector flags, extension, and corrected dashboard PromQL
  are all in place together.
- Every container and the disposable Docker network created during verification (2 Grafana runs,
  1 Postgres, 1 exporter, 1 network) was torn down and confirmed removed via `docker ps -a` /
  `docker network ls` before finishing -- no orphaned containers remain.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `pg_stat_statements` metric-name mismatch between the dashboard and the
pinned exporter version**
- **Found during:** Task 3, local end-to-end verification of item 2's fix.
- **Issue:** the dashboard's "Query rate" and "Average query runtime" panels query
  `pg_stat_statements_calls` and `pg_stat_statements_total_time_seconds`; postgres_exporter
  v0.20.1's `stat_statements` collector actually emits `pg_stat_statements_calls_total` and
  `pg_stat_statements_seconds_total`. Enabling only the collector (as originally scoped) would
  have left both tiles permanently empty even after the eventual production restart -- the same
  silent-failure shape Phase 12 plan 04's WR-02 already flagged for two other metrics, one layer
  further down this chain.
- **Fix:** updated both panels' `expr` fields to the exporter's actual metric names.
- **Files modified:** `docker/grafana/provisioning/dashboards/json/postgres-exporter.json`
- **Commit:** `2ad3656`

No other deviations -- the plan executed as written otherwise, including the Task 2 checkpoint
pause and resumption exactly as scoped.

## Self-Check: PASSED

- `.planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/FINDINGS.md` -- FOUND
- `.planning/quick/260911-gkz-debug-why-several-grafana-postgres-inter/verify-dashboard-fixes.sh` -- FOUND
- `docker-compose.prod.yml` diff present -- FOUND
- `docker/grafana/provisioning/dashboards/json/postgres-exporter.json` diff present -- FOUND
- Commit `a6bd609` -- FOUND (`git log --oneline --all | grep a6bd609`)
- Commit `2ad3656` -- FOUND (`git log --oneline --all | grep 2ad3656`)
- No leftover Docker containers/networks from verification -- CONFIRMED (`docker ps -a` empty of `gsd-verify-*`)
