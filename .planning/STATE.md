---
gsd_state_version: 1.0
milestone: v1.1
milestone_name: Kafka Activity Feed
current_phase: 2
current_phase_name: Kafka Foundation, Domain Events & Move Endpoint
status: executing
stopped_at: Completed 02-01-PLAN.md
last_updated: "2026-08-01T15:39:10.507Z"
last_activity: 2026-08-01
last_activity_desc: Phase 2 execution started
progress:
  total_phases: 3
  completed_phases: 0
  total_plans: 3
  completed_plans: 1
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-01)

**Core value:** Deliver a real, event-driven per-board activity log (Kafka + consumer + idempotent persistence), plus the genuinely-missing "move task between columns" endpoint, as Epic 1 of the backend modernization plan.
**Current focus:** Phase 2 — Kafka Foundation, Domain Events & Move Endpoint

## Current Position

Phase: 2 (Kafka Foundation, Domain Events & Move Endpoint) — EXECUTING
Plan: 2 of 3
Status: Ready to execute
Last activity: 2026-08-01 — Phase 2 execution started

Progress: [███░░░░░░░] 33%

## Performance Metrics

**Velocity:**

- Total plans completed: 3
- Average duration: 32 min
- Total execution time: 1.6 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1 | 3 | 95min | 32min |
| 2 | TBD | - | - |
| 3 | TBD | - | - |

**Recent Trend:**

- Last 5 plans: 45min, 35min, 15min
- Trend: Improving

*Updated after each plan completion*
**Per-Plan Metrics:**

| Plan | Duration | Tasks | Files |
|------|----------|-------|-------|
| Phase 01 P01 | 45min | 3 tasks | 11 files |
| Phase 01 P02 | 35min | 3 tasks | 7 files |
| Phase 01 P03 | 15min | 1 tasks | 2 files |
| Phase 02 P01 | 14min | 2 tasks | 13 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap/v1.1]: Continued phase numbering from v1.0 — this milestone starts at Phase 2, not Phase 1.
- [Roadmap/v1.1]: Split 16 requirements into 2 phases (coarse granularity): Phase 2 = producer side (Kafka infra, domain events, move endpoint), Phase 3 = consumer side (activity log, idempotency, DLT, read API, e2e verification). Research's 5-phase suggestion was compressed to match coarse granularity — TEST-01/02 folded into Phase 3 as additional success criteria rather than a standalone verification-only phase.
- [Roadmap/v1.1]: Phase 2 explicitly depends on Phase 1 — `PATCH /tasks/{taskId}/move` (MOVE-02) reuses the exact explicit `@Version` compare-before-mutate convention delivered in Phase 1, not a new locking path.
- [v1.0/Scope]: Narrow v1.0 to optimistic locking only; defer `/full` endpoint to v2.
- [v1.0/Finding 1]: Ownership chain treated as closed — already 1 query via EAGER joins, no code change.
- [Phase ?]: [Phase 2 Plan 01]: KafkaEventPublisher is the sole Kafka client API touchpoint in src/main — TaskService never imports org.springframework.kafka, confirmed by grep
- [Phase ?]: [Phase 2 Plan 01]: MOVE-03 cross-board guard (400) runs before the MOVE-02 version guard (409) since a wrong-board target is a request-shape problem, not a concurrency one
- [Phase ?]: [Phase 2 Plan 01]: Added AbstractAppTest.createColumnForUser fixture helper since no REST endpoint exists to create a board directly — boards are only created via UserService.addBoardByUserId

### Pending Todos

- [major] Stop exposing version as client-writable in update DTOs — `version` in `UpdateTaskRequestDTO`/`UpdateColumnRequestDTO` should be treated as read-only from the client's perspective (server-owned increment), not a normal editable field. See `.planning/todos/pending/2026-08-01-stop-exposing-version-as-client-writable-in-update-dtos.md`.

### Blockers/Concerns

Carried from research (address during Phase 2/3 planning):

- Exact KRaft env-var set and internal-vs-external listener config for `apache/kafka-native` was only web-search-sourced (LOW confidence) — pull the reference compose YAML from `apache/kafka`'s own repo before finalizing Phase 2 planning.
- `DefaultErrorHandler`/`DeadLetterPublishingRecoverer` retry-before-DLT tuning is thinly sourced (LOW-MEDIUM) — worth a research pass during Phase 3 planning to avoid stale `SeekToCurrentErrorHandler`-era tutorials.
- Cursor vs. offset pagination for `GET /boards/{boardId}/activity` is underspecified by the epic; this milestone ships offset `Pageable` per REQUIREMENTS.md (PAGE-V2-01 defers keyset pagination to v2) — confirm during Phase 3 planning before the repository query shape is locked in.
- Production (EC2) Kafka deployment is explicitly out of scope for v1.1 (KAFKA-V2-01) — the dev `docker-compose.yml` must not be reused unmodified as a deploy artifact without a review pass.

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260801-gby | Create docs/CODE_STYLE.md with agent code-style preferences (enums over magic constants), referenced from CLAUDE.md | 2026-08-01 | 685b471 | [260801-gby-create-docs-code-style-md-with-agent-cod](./quick/260801-gby-create-docs-code-style-md-with-agent-cod/) |
| 260801-gib | Append rules 2-7 to docs/CODE_STYLE.md (ownership-verified loading, AssertJ/catchException, no-mocks, @Nested/AAA, Update*RequestDTO shape, Optional isEmpty()-guard) and fix bare-int HTTP status literals in TaskLockingE2ETest/ColumnLockingE2ETest to use HttpStatus enum constants | 2026-08-01 | 85ed93f | [260801-gib-survey-the-repo-for-existing-code-conven](./quick/260801-gib-survey-the-repo-for-existing-code-conven/) |
| 260801-k93 | Reword hiring-context language ("interview-defensible", "interview prep" headings, etc.) out of 17 git-tracked docs to neutral technical phrasing, preserving meaning | 2026-08-01 | 1bdfb79 | [260801-k93-remove-interview-related-language-from-d](./quick/260801-k93-remove-interview-related-language-from-d/) |

## Deferred Items

Items acknowledged and carried forward:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Endpoint | `GET /boards/{boardId}/full` nested read (FULL-01..03) | Deferred to v2 | 2026-07-31 |
| Epics | Modernization Epics 3–7 (Flyway/OpenAPI, Redis, Testcontainers project-wide, Observability, K8s) | Deferred to future milestones | 2026-07-31 |
| Kafka | Production (EC2) Kafka deployment (KAFKA-V2-01) | Deferred to v2 | 2026-08-01 |
| Kafka | Cursor/keyset pagination on activity feed (PAGE-V2-01) | Deferred to v2 | 2026-08-01 |

## Session Continuity

Last session: 2026-08-01T15:39:10.489Z
Stopped at: Completed 02-01-PLAN.md
Resume file: None

## Operator Next Steps

- Run `/gsd-plan-phase 2` to plan Kafka Foundation, Domain Events & Move Endpoint
