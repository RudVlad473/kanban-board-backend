---
phase: quick-260908-mtl
plan: 01
subsystem: infra
tags: [grafana, dashboards, observability, docs]

# Dependency graph
requires:
  - phase: 12-self-hosted-observability-stack
    provides: three vendored Grafana dashboards (node-exporter-full, cadvisor, postgres-exporter) provisioned from committed JSON
provides:
  - Human-readable display titles on all three phase-12 Grafana dashboards
  - Todo re-scoped in place to track only the remaining public-links piece
affects: [grafana dashboards, .planning/todos/pending]

# Actuals (#2632)
actuals:
  tokens: 900
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns: []

key-files:
  created: []
  modified:
    - docker/grafana/provisioning/dashboards/json/node-exporter-full.json
    - docker/grafana/provisioning/dashboards/json/cadvisor.json
    - docker/grafana/provisioning/dashboards/json/postgres-exporter.json
    - .planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md

key-decisions:
  - "Edited the committed dashboard JSON directly (Approach A) rather than a live UI/API rename or a provisioning-config override, since the file provisioner is the single source of truth and re-reads the directory every 30s"
  - "Held uid and description byte-identical on all three dashboards so the provisioner updates in place and grafana.com provenance survives"
  - "Todo left in pending/ with a new '## Partial resolution (quick task 260908-mtl, 2026-09-08)' section rather than moved to completed/, since piece 2 (public links) is still open"

patterns-established: []

requirements-completed: [QUICK-260908-mtl]

coverage:
  - id: D1
    description: "Three phase-12 Grafana dashboards renamed to scannable display titles (VM Host Metrics, Per-Container Resource Usage, Postgres Internals) while preserving uid and grafana.com provenance in description"
    verification:
      - kind: other
        ref: "rg/python3 json.tool gates in 260908-mtl-PLAN.md Task 1 <verify> block — all passed (JSON validity, new titles present, old titles absent, provenance strings intact, uid unchanged, git diff --numstat exactly 3 files x 1+1 lines)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Todo re-scoped in place: piece 1 (rename) marked closed, piece 2 (public dashboard links) left open, file stays in pending/"
    verification:
      - kind: other
        ref: "rg gates in 260908-mtl-PLAN.md Task 2 <verify> block — all passed (file still in pending/, not moved to completed/, resolution section present, attributed to 260908-mtl, no resolved: key, original Problem/Solution sections preserved)"
        status: pass
    human_judgment: false

duration: 15min
completed: 2026-09-08
status: complete
---

# Quick Task 260908-mtl: Groom the Three Phase-12 Grafana Dashboards Summary

**Renamed three vendored Grafana dashboards to portfolio-legible titles (VM Host Metrics, Per-Container Resource Usage, Postgres Internals) and re-scoped their originating todo to track only the still-open public-links piece.**

## Performance

- **Duration:** 15 min
- **Completed:** 2026-09-08T16:28:13Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- All three phase-12 dashboards (`node-exporter-full.json`, `cadvisor.json`, `postgres-exporter.json`) now carry a display `title` a first-time viewer can scan, with `uid` and the grafana.com provenance in `description` left byte-identical
- Confirmed no other file in `docs/`, `docker/`, `.github/`, or `scripts/` still references a dashboard by its old title
- Re-scoped `.planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md` in place, closing piece 1 and leaving piece 2 (public dashboard links) open, following this repo's established partial-resolution precedent

## Task Commits

Each task was committed atomically:

1. **Task 1: Rename all three dashboard display titles, preserving uid and description** - `96a70db` (feat)
2. **Task 2: Re-scope the todo in place — piece 1 closed, piece 2 still pending** - `56bf9fd` (docs)

_Note: no TDD tasks in this plan; each task produced a single commit._

## Files Created/Modified
- `docker/grafana/provisioning/dashboards/json/node-exporter-full.json` - `title` changed from "Node Exporter Full" to "VM Host Metrics"
- `docker/grafana/provisioning/dashboards/json/cadvisor.json` - `title` changed from "Cadvisor exporter" to "Per-Container Resource Usage"
- `docker/grafana/provisioning/dashboards/json/postgres-exporter.json` - `title` changed from "PostgreSQL Exporter" to "Postgres Internals"
- `.planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md` - appended a dated partial-resolution section closing piece 1, leaving piece 2 open

## Decisions Made
- Edited the committed vendored JSON directly (plan's Approach A) rather than a live Grafana UI/API rename (Approach B, blocked by `allowUiUpdates` being unset and reverted by the 30s provisioner poll) or a provisioning-config title override (Approach C, no such config key exists in Grafana's file provider)
- Held `uid` and `description` unchanged on all three dashboards so the file provisioner updates the existing dashboard row in place rather than creating a duplicate, and the grafana.com ID/revision/fetch-date provenance trail survives
- Followed this repo's existing partially-resolved-todo precedent (`## Partial resolution` heading, per-piece CLOSED/STILL OPEN bullets, closing sentence explaining why the file stays in `pending/`) rather than inventing a new shape, synthesizing the phase-attributed heading style with the quick-task-attributed parenthetical seen on a fully-closed todo

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required

None - no external service configuration required. The new titles will appear in the live Grafana instance on its next 30-second provisioning poll after these commits reach the VM's bind mount; no container restart or Compose change is needed.

## Next Phase Readiness
- Piece 1 of the originating todo is closed; piece 2 (public dashboard links) remains genuinely open in `.planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md`, requiring a live Grafana session, a per-dashboard panel review for real hostnames/internal IPs, and a decision on which dashboards get public links.
- No blockers for future work.

---
*Phase: quick-260908-mtl*
*Completed: 2026-09-08*

## Self-Check: PASSED

All claimed files found on disk; both commits (`96a70db`, `56bf9fd`) found in git history.
