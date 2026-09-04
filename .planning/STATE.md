---
gsd_state_version: 1.0
milestone: v1.3
current_phase: 11
status: completed
stopped_at: Completed quick task 260902-vjo
last_updated: "2026-09-02T20:59:09.569Z"
last_activity: 2026-08-29
last_activity_desc: Phase 11 complete
state_head: 46137464dd1b0e20251f314fabd8110923194f7a
progress:
  total_phases: 1
  completed_phases: 1
  total_plans: 8
  completed_plans: 8
milestone_name: Nonprod Environment & CI Hardening
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-17)

**Core value:** The backend is feature-complete against its own mock-ups and live in production; the differentiator now is proving the whole system — including a real frontend against a real deploy — is reliable.
**Current focus:** Phase 11 — Migrate database from Neon to self-hosted Postgres

## Current Position

Phase: 11
Plan: Not started
Status: All phases complete
Last activity: 2026-09-04 - Completed quick task 260904-obv: add 'color' field to column and accept it on column creation

## Performance Metrics

v1.0–v1.2 velocity/per-plan detail archived at milestone close — see `.planning/RETROSPECTIVE.md`'s Cost Observations per milestone and `.planning/milestones/v1.2-phases/` for individual plan SUMMARY.md files (each carries an `actuals` frontmatter block with tokens/tasks/commits). This section resets per milestone rather than accumulating a project-lifetime table.

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 11 P01 | 25min | 3 tasks | 6 files |
| Phase 11 P02 | 55min | 3 tasks | 1 files |
| Phase 11 P03 | 90min | 3 tasks | 3 files |
| Phase 11 P04 | 30min | 2 tasks | 1 files |
| Phase 11 P05 | 41min | 3 tasks | 2 files |
| Phase 11 P06 | 40min | 3 tasks | 2 files |
| Phase quick P260902-vjo | ~40min | 3 tasks | 2 files |

## Accumulated Context

### Roadmap Evolution

- Phase 11 added: Migrate database from Neon to self-hosted Postgres

### Decisions

Decisions are logged in PROJECT.md's Key Decisions table and `.planning/RETROSPECTIVE.md`. The full per-plan decision log for v1.0–v1.3 was cleared at v1.3's close — preserved in `git log` and `.planning/milestones/v1.3-phases/`.

No active-milestone decisions pending — next milestone not yet scoped.

- [Phase null]: 260825-h7m: MapStruct name-based auto-mapping was sufficient to carry createdAt onto BoardFullResponseDTO with no explicit @Mapping needed
- [Phase 11]: Phase 11 plan 01: postgres service placed in docker-compose.prod.yml (not a dedicated third file), following the kanban-edge cross-project precedent
- [Phase 11]: Phase 11 plan 01: DB_JDBC_PARAMS dropped entirely (not softened) from both app services -- self-hosted target has no TLS listener
- [Phase 11]: Phase 11 plan 01: postgres mem_limit: 512m is an explicitly-labeled PROVISIONAL Iteration 0 baseline; plan 11-03 owns the measured floor
- [Phase 11]: Phase 11 plan 04: re-derived HikariCP pool for a same-host Postgres engine (min-idle 0->2, connection-timeout 30000->10000, idle-timeout/max-lifetime raised to Hikari defaults, keepalive-time 0->120000), dropped the DB_JDBC_PARAMS query-string override, and proved prepareThreshold=0 removal safe with 1028 observed server-side prepared-statement executions against a real instance
- [Phase 11]: Phase 11 plan 04: pg_prepared_statements is session-scoped and unusable for cross-session verification from a separate docker exec psql session -- used Postgres's own statement log (log_statement=all) instead, confirmed empirically before adopting
- [Phase 11]: Phase 11 plan 05: D-13 Task 1 resolved as Option A (one-off Flyway container over SSH), no D-03 exception taken
- [Phase 11]: Phase 11 plan 05: DB_HOST/DB_NAME moved from GitHub Environment secrets to variables so the resolved database name is provably visible, unmasked, in CI logs (DB_USER/DB_PASS remain secrets)
- [Phase 11]: Phase 11 plan 05: gate-still-bites evidence gathered via a live VM-local reproduction against a disposable scratch database rather than a throwaway-branch CI push
- [Phase 11]: 260829-ii3: ResetController's params-based two-route split (fullReset=true vs fullReset!=true) routed exactly as predicted; the plan's accepted-async-race assumption for activity_log/topic-offset assertions did not hold empirically and required bounding, not tightening
- [Phase 11]: 260902-vjo: Netcup SCP console triage guidance added to INFRA_RUNBOOK.md between Access and Firewall sections; new scheduled uptime-check.yml workflow (cron */15, workflow_dispatch, permissions: {}) probes both public health endpoints, proven to bite on non-200/unreachable/wrong-body branches locally

### Pending Todos

~46 pending todos remain in `.planning/todos/pending/` (security/CI/minor backlog, all explicitly
out of v1.3 scope) — full inventory in this document's Deferred Items table below, acknowledged
and carried forward at v1.3 close. None block the next milestone; triage candidates for it via
`.planning/todos/pending/`.

### Blockers/Concerns

None open. All three carried into v1.3 (Redpanda memory floor, nonprod/production identity
confusion risk, `NVD_API_KEY` resolution failure) were resolved during Phases 8–10 — see
`.planning/milestones/v1.3-ROADMAP.md` and PROJECT.md's Key Decisions for detail.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260818-ied | Add CI-05 requirement to Phase 9: automate Avro schema registry registration for nonprod as part of the CI deploy pipeline, extending deploy.yml with a schema-registration step (mirroring CI-01's flyway-verify-nonprod/deploy-to-nonprod pattern) so production and nonprod schema registries stay in sync on every deploy without a manual step | 2026-08-18 | b985989 | [260818-ied-add-ci-05-requirement-to-phase-9-automat](./quick/260818-ied-add-ci-05-requirement-to-phase-9-automat/) |
| 260820-ecm | Resolve WINDOWS.md ledger items 3 and 7: record already-completed live verification proof in INFRA_RUNBOOK.md and mark both fixed | 2026-08-20 | b5a265a | [260820-ecm-resolve-windows-md-ledger-items-3-and-7-](./quick/260820-ecm-resolve-windows-md-ledger-items-3-and-7-/) |
| 260820-euc | Add paths-ignore filter to deploy.yml so docs-only pushes don't trigger a full production+nonprod redeploy; also close 7 stale pending todos already resolved by Phase 10 | 2026-08-20 | 95f61cc | [260820-euc-add-paths-ignore-filter-to-deploy-yml-so](./quick/260820-euc-add-paths-ignore-filter-to-deploy-yml-so/) |
| 260820-g3u | Iterate on 4 easy pending todos: docs/CODE_STYLE.md rule 4 correction, tag HistoricalActivityEventReconstructorTest as kafka, qualify bare print() static import in 4 controller tests, fix ResetServiceE2ETest flaky race; also found and fixed a real gap in gradle/verification-metadata.xml along the way | 2026-08-20 | df68443 | [260820-g3u-iterate-on-4-easy-pending-todos-docs-cod](./quick/260820-g3u-iterate-on-4-easy-pending-todos-docs-cod/) |
| 260820-giz | Audit penetration-testing/security coverage against OWASP API Security Top 10 (2023) — cited verdict for all 10 categories, 4 new gap todos filed (IDOR chain consistency, signin rate-limiting, security response headers, CSRF cross-origin test), originating todo closed with Resolution | 2026-08-20 | 6a77e54 | [260820-giz-audit-penetration-testing-and-security-c](./quick/260820-giz-audit-penetration-testing-and-security-c/) |
| 260820-iwo | File 19 new pending todos + enrich 6 already-filed todos from the ASVS 4.0.3 Level 2 audit (33-agent workflow, 253 requirements traced) — 12 moderate + 7 minor new todos filed, STATE.md Pending Todos refreshed (~26 → ~45 items) | 2026-08-20 | cdaf0b9 | [260820-iwo-file-19-new-pending-todos-and-enrich-6-a](./quick/260820-iwo-file-19-new-pending-todos-and-enrich-6-a/) |
| 260825-dfd | Add a createdAt timestamp to BoardEntity and expose it on BoardResponseDTO, populated once in BoardService.save() and shared with BoardCreatedEvent's timestamp | 2026-08-25 | 6aadda1 | [260825-dfd-add-a-createdat-timestamp-to-boardentity](./quick/260825-dfd-add-a-createdat-timestamp-to-boardentity/) |
| 260825-h7m | Add createdAt to BoardFullResponseDTO, carried through the nested GET /boards/{boardId}/full read via MapStruct name-based auto-mapping; extended the flat-equivalence test to cover board-level fields (name/version/createdAt), closing the untested gap that let createdAt reach production missing from the nested document | 2026-08-25 | 1dd30ea | [260825-h7m-add-createdat-to-boardfullresponsedto-ge](./quick/260825-h7m-add-createdat-to-boardfullresponsedto-ge/) |
| 260829-ii3 | In the nonprod reset endpoint, add a REQUIRED targeted-delete mode selected by a fullReset query param: POST /api/admin/reset?fullReset=true keeps the unconditional full reset unchanged, a bare POST with {"userIds": [...]} cascade-deletes only those users' boards/columns/tasks/subtasks and their own activity_log rows via a new ResetService.deleteUsers, reusing UserService.deleteById's existing cascade | 2026-08-29 | c29a32d | [260829-ii3-in-the-nonprod-reset-endpoint-add-a-requ](./quick/260829-ii3-in-the-nonprod-reset-endpoint-add-a-requ/) |
| 260902-vjo | Document Netcup console staleness triage in docs/INFRA_RUNBOOK.md and add a GitHub Actions cron uptime check for both public health endpoints | 2026-09-02 | 4613746 | [260902-vjo-document-netcup-console-staleness-triage](./quick/260902-vjo-document-netcup-console-staleness-triage/) |
| 260904-obv | add 'color' field to column and accept it on column creation | 2026-09-04 | 50d4aca | [260904-obv-add-color-field-to-column-and-accept-it-](./quick/260904-obv-add-color-field-to-column-and-accept-it-/) |

## Deferred Items

Items acknowledged and carried forward (full v1.2-close table in `.planning/milestones/v1.2-ROADMAP.md`):

| Category | Item | Status | Deferred At | Milestone |
|----------|------|--------|-------------|-----------|
| Epics | Modernization Epics 3 (OpenAPI half), 4, 6, 7 (Redis, Observability, K8s) | Deferred to future milestones | 2026-07-31 | v1.2 |
| Kafka | Cursor/keyset pagination on activity feed (PAGE-V2-01) | Deferred to v2 | 2026-08-01 | v1.2 |
| Infra Polish | INFRA-V2-01..03 (observability stack, blue-green deploys, multi-broker Redpanda HA) | Deferred to v2 | 2026-08-03 | v1.2 |
| Schema Registry Polish | SCHEMA-V2-01..02 (pre-merge schema-compatibility CI check, compatibility-mode rationale doc) | Deferred to v2 | 2026-08-03 | v1.2 |
| Frontend coupling | FRONTEND-DISPATCH-V2, FRONTEND-COUPLING-V2 | Deferred — hard-blocked on the frontend repo existing | 2026-08-17 | v1.2 |
| Nonprod | Per-PR ephemeral environments, ephemeral Neon branch per E2E run, second Neon project | Rejected for v1.3 with written rationale (REQUIREMENTS.md Out of Scope) | 2026-08-17 | v1.2 |
| Quick task | 260801-p03, 260802-rq5, 260802-ryf (missing/research-only summaries) | Open since v1.1 close | 2026-08-17 | v1.2 |
| quick_tasks | 260801-p03-add-explicit-comments-to-taskservice-upd | missing | 2026-08-25 | v1.3 |
| quick_tasks | 260802-rq5-bump-java-version-from-21-to-25-current- | missing | 2026-08-25 | v1.3 |
| quick_tasks | 260802-ryf-enable-virtual-threads-in-spring-boot-co | missing | 2026-08-25 | v1.3 |
| quick_tasks | 260816-hn1-wire-up-secret-scanning-gitleaks-truffle | unknown | 2026-08-25 | v1.3 |
| quick_tasks | 260824-v7n-install-docker-natively-inside-this-wsl2 | missing | 2026-08-25 | v1.3 |
| todos | 45 pending todos (full list: `.planning/todos/pending/`) | (presence-only) | 2026-08-25 | v1.3 |

**Known verification overrides: 14 total (recorded at v1.2 close); 50 newly acknowledged, 0 carried forward from a prior close, at v1.3 close (see above)**

**v1.3 close notes on the 5 flagged quick tasks:**

- `260801-p03`, `260802-rq5`, `260802-ryf` — pre-existing gap, unchanged since v1.1/v1.2 close (see rows above).
- `260816-hn1` (secret scanning) — false positive: fully complete with both PLAN.md and SUMMARY.md; flagged only because its `duration` frontmatter field is free text ("unknown (resumed mid-execution...)") rather than a parseable value.
- `260824-v7n` (install Docker natively inside WSL2) — genuinely empty, abandoned directory (scaffolded 2026-08-24, never executed); unrelated local dev-environment tooling, not a v1.3 requirement.

The 46 pending todos are individually listed and categorized in this document's own Pending Todos section above (security/CI/minor backlog, all explicitly out of v1.3 scope per PROJECT.md).

## Session Continuity

Last session: 2026-09-02T20:59:09.390Z
Stopped at: Completed quick task 260902-vjo
Resume file: None

## Operator Next Steps

- Start the next milestone with /gsd-new-milestone
