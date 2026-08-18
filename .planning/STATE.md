---
gsd_state_version: 1.0
milestone: v1.3
milestone_name: Nonprod Environment & CI Hardening
current_phase: 9
current_phase_name: Nonprod Continuous Deploy & Scoped CI Credentials
status: planning
stopped_at: Phase 9 context gathered
last_updated: "2026-08-18T15:50:45.820Z"
last_activity: 2026-08-18
last_activity_desc: v1.3 roadmap created (3 phases, 19/19 requirements mapped)
progress:
  total_phases: 3
  completed_phases: 1
  total_plans: 3
  completed_plans: 3
  percent: 33
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-17)

**Core value:** The backend is feature-complete against its own mock-ups and live in production; the differentiator now is proving the whole system — including a real frontend against a real deploy — is reliable.
**Current focus:** Phase 08 — isolated-nonprod-environment-live-and-resettable

## Current Position

Phase: 9 — Nonprod Continuous Deploy & Scoped CI Credentials
Plan: Not started
Status: Ready to plan
Last activity: 2026-08-18 — Phase 08 complete, transitioned to Phase 9

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

v1.0–v1.2 velocity/per-plan detail archived at milestone close — see `.planning/RETROSPECTIVE.md`'s Cost Observations per milestone and `.planning/milestones/v1.2-phases/` for individual plan SUMMARY.md files (each carries an `actuals` frontmatter block with tokens/tasks/commits). This section resets per milestone rather than accumulating a project-lifetime table.

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md's Key Decisions table and `.planning/RETROSPECTIVE.md`. The full per-plan decision log for v1.0–v1.2 was cleared at v1.2's close — preserved in `git log` and `.planning/milestones/v1.2-phases/`.

v1.3 decisions so far (from requirements/research, before any phase plan):

- Nonprod colocates on the existing Netcup VPS; a second ~€4/month VPS is the fallback only if live memory measurement (NONPROD-06) says colocation doesn't hold.
- Kafka isolation is a **second Redpanda broker**, not topic-prefixing — the Avro registry's `RecordNameStrategy` keys compatibility history by class name, so prefixed topics would still share the registry.
- The backend does **not** gate its own production promotion on the frontend repo's E2E results; the frontend gates itself on nonprod reachability.
- Phase numbering continues across milestones — v1.3 starts at Phase 8 (v1.2 ended at Phase 7 + inserted 07.1).
- Roadmap compressed research's 4 buildable phases to 3 (granularity `coarse`): RESET-01 folded into Phase 8 rather than standing alone, and the 8 HARDEN-* todos given their own phase (Phase 10) since they are independent of the nonprod build and two of them are not CI at all.

### Pending Todos

Six pending todos are now in-scope v1.3 requirements and should be closed by Phase 10, not re-triaged: dependabot `github-actions` ecosystem (HARDEN-01), TruffleHog live-credential pass (HARDEN-02), digest-pinning (HARDEN-03), gradle cache in `run-tests` (HARDEN-04), gitleaks-in-worktree (HARDEN-05), `security-scan.yml` stale comment/actions (HARDEN-06), cookie `Secure` flag (HARDEN-07), README expansion (HARDEN-08).

Still open and out of v1.3 scope (representative, see `.planning/todos/pending/` for the complete set — ~20 items):

- [security] Audit penetration-testing/security coverage against OWASP API Security Top 10 (CSRF posture, signin rate-limiting, full-depth IDOR, security headers, DTO mass-assignment).
- [minor] Full-system sequence diagram for frontend hand-off; bump Java 21 → 25; enable virtual threads (JDBC/Hibernate pinning risk first).
- [minor] `E2ETest`-suffix vs. `fastTest`-filter coupling decision; two coexisting session-ceiling enforcers; `ActivityAction` enum misplaced in `entity/`.
- [minor] `activity_log` has no retention policy; `TaskMovedEvent` carries no position; `UpdateBoardRequestDTO.name` optionality assumption.
- [minor] OpenAPI breaking-change detection in CI; `POST /signup` `Location` points at a URI with no GET handler; 4 `Location` sites diverge from signup's pattern.
- [minor] Ratchet `dependencyCheckAnalyze`'s `failBuildOnCVSS` after a CPE-matched baseline; evaluate PMD/Checkstyle/SpotBugs; alert-service microservice exploration; JavaDoc verbosity policy; `docs/CODE_STYLE.md` rule 4 claim falsified by 260813-m9x.

### Blockers/Concerns

- **[Phase 8, open unknown]** Nonprod Redpanda's memory floor is genuinely unmeasured. Production's reserved caps leave ~2.65GB unreserved on the 7.8GB host, and `mem_limit` is a per-container cap, not a host reservation — this project already hit a cgroup-accounting surprise at exactly this boundary. Requires iterative live restart cycles; if no safe value fits, the second-VPS fallback must actually be exercised.
- **[Phase 9, known trap]** The existing `deploy-to-netcup` job hardcodes its target directory and the Compose project name is pinned, and `cleanup-old-images` deletes every Docker Hub tag except its own run's. A copy-pasted nonprod job that does not change *every* identity axis will mutate live production, and a shared image repo will let production's next push delete nonprod's running tag.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260818-ied | Add CI-05 requirement to Phase 9: automate Avro schema registry registration for nonprod as part of the CI deploy pipeline, extending deploy.yml with a schema-registration step (mirroring CI-01's flyway-verify-nonprod/deploy-to-nonprod pattern) so production and nonprod schema registries stay in sync on every deploy without a manual step | 2026-08-18 | b985989 | [260818-ied-add-ci-05-requirement-to-phase-9-automat](./quick/260818-ied-add-ci-05-requirement-to-phase-9-automat/) |

## Deferred Items

Items acknowledged and carried forward (full v1.2-close table in `.planning/milestones/v1.2-ROADMAP.md`):

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Epics | Modernization Epics 3 (OpenAPI half), 4, 6, 7 (Redis, Observability, K8s) | Deferred to future milestones | 2026-07-31 |
| Kafka | Cursor/keyset pagination on activity feed (PAGE-V2-01) | Deferred to v2 | 2026-08-01 |
| Infra Polish | INFRA-V2-01..03 (observability stack, blue-green deploys, multi-broker Redpanda HA) | Deferred to v2 | 2026-08-03 |
| Schema Registry Polish | SCHEMA-V2-01..02 (pre-merge schema-compatibility CI check, compatibility-mode rationale doc) | Deferred to v2 | 2026-08-03 |
| Frontend coupling | FRONTEND-DISPATCH-V2, FRONTEND-COUPLING-V2 | Deferred — hard-blocked on the frontend repo existing | 2026-08-17 |
| Nonprod | Per-PR ephemeral environments, ephemeral Neon branch per E2E run, second Neon project | Rejected for v1.3 with written rationale (REQUIREMENTS.md Out of Scope) | 2026-08-17 |
| Quick task | 260801-p03, 260802-rq5, 260802-ryf (missing/research-only summaries) | Open since v1.1 close | 2026-08-17 |

**Known verification overrides: 14 total (recorded at v1.2 close)**

## Session Continuity

Last session: 2026-08-18T15:50:45.804Z
Stopped at: Phase 9 context gathered
Resume file: C:/Dev/Repos/kanban-board-backend/.planning/phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-CONTEXT.md

## Operator Next Steps

- `/gsd-plan-phase 8` to plan the nonprod environment bootstrap. Research flagged Phase 8 as the one needing real iteration budget (Redpanda memory floor); Phases 9 and 10 are standard patterns with existing in-repo precedent.
