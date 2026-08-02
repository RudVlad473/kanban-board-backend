---
gsd_state_version: 1.0
milestone: v1.1
milestone_name: Kafka Activity Feed
current_phase: 03
current_phase_name: activity-log-consumer-reliability-read-api
status: executing
stopped_at: Completed 03-02-PLAN.md
last_updated: "2026-08-02T14:38:37.756Z"
last_activity: 2026-08-02
last_activity_desc: Phase 03 execution started
progress:
  total_phases: 3
  completed_phases: 1
  total_plans: 6
  completed_plans: 5
  percent: 33
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-08-01)

**Core value:** Deliver a real, event-driven per-board activity log (Kafka + consumer + idempotent persistence), plus the genuinely-missing "move task between columns" endpoint, as Epic 1 of the backend modernization plan.
**Current focus:** Phase 03 — activity-log-consumer-reliability-read-api

## Current Position

Phase: 03 (activity-log-consumer-reliability-read-api) — EXECUTING
Plan: 3 of 3
Status: Ready to execute
Last activity: 2026-08-02 — Phase 03 execution started

Progress: [████████░░] 83%

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
| Phase 2 P3 | 70min | 2 tasks | 6 files |
| Phase 03 P01 | 25min | 2 tasks | 16 files |
| Phase 03 P02 | 65min | 2 tasks | 4 files |

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
- [Phase ?]: [Phase 2 Plan 03]: Fixed Dockerfile's retired openjdk:21-jdk-slim runtime base image to eclipse-temurin:21-jre-jammy - was silently breaking production CI/CD too, not just this plan's local stack
- [Phase ?]: [Phase 2 Plan 03]: kafka service runs as user: root in docker-compose.yml to fix a named-volume permission failure (apache/kafka-native does not pre-create /var/lib/kafka/data, so the Docker volume driver creates it root-owned) - local-dev-only scope, avoids a 4th compose service
- [Phase ?]: [Phase 3 Plan 01]: Fixed KafkaConsumerConfig.deadLetterKafkaTemplate silently suppressing Spring Boot's default KafkaTemplate bean (@ConditionalOnMissingBean(KafkaTemplate.class) is bare-type) — added an explicit @Primary kafkaTemplate bean sourced from the autoconfigured ProducerFactory; a real production bug, not just a test artifact
- [Phase ?]: [Phase 3 Plan 01]: Pinned docker-java's negotiated Docker Engine API version to 1.44 in AbstractKafkaContainerTest (testcontainers-java#11212) to fix Docker Engine 29.x rejecting every Testcontainers transport on Windows — zero host-level Docker Desktop configuration required
- [Phase ?]: [Phase 3 Plan 01]: Replaced an exact-nanosecond Instant equality assertion with AssertJ isCloseTo(within(1, MICROS)) — Kafka JSON serialization + JPA persistence round-trip loses sub-microsecond precision without a consistent truncate-vs-round direction
- [Phase ?]: [Phase 3 Plan 02]: Fixed KafkaConsumerConfig.activityErrorHandler's ambiguous deadLetterKafkaTemplate parameter with @Qualifier - Spring's @Primary disambiguation runs before parameter-name matching, so the dead-letter path was silently using the wrong (JSON/base64-encoding) producer template
- [Phase ?]: [Phase 3 Plan 02]: Replaced AbstractKafkaContainerTest's @Testcontainers/@Container lifecycle with an imperative kafka.start() static initializer - the JUnit5-extension-driven singleton container pattern did not reliably share one broker across three sibling E2E test classes in this environment

### Pending Todos

- [minor] Create a sequence diagram documenting the full system flow — deferred until all functional epics of the backend modernization plan are complete and the project is ready for frontend hand-off. See `.planning/todos/pending/2026-08-01-create-sequence-diagram-documenting-full-system-flow-for-fro.md`.
- [minor] Bump Java version from 21 to 25 (current LTS) — build.gradle toolchain, Dockerfile (both stages), and CI `java-version` all pinned to 21; not urgent (21 LTS supported until ~2028), but worth doing proactively. See `.planning/todos/pending/2026-08-01-bump-java-version-from-21-to-25-current-lts.md`.
- [minor] Account for schema evolution risk when changing ActivityEvent shapes — a rolling deploy that renames/retypes an event field while old-shape messages are still unconsumed can dead-letter valid (non-poison) messages; Kafka itself enforces no schema. See `.planning/todos/pending/2026-08-01-account-for-schema-evolution-risk-when-changing-activityeven.md`.
- [minor] Enable virtual threads in Spring Boot config (`spring.threads.virtual.enabled=true`) — evaluate JDBC/Hibernate and Spring Session JDBC blocking-call pinning risk first. See `.planning/todos/pending/2026-08-02-enable-virtual-threads-in-spring-boot-config.md`.
- [minor] Add ErrorProne (`net.ltgt.errorprone`) for compile-time bug detection — blocked on `build.gradle` unlock post-Phase-3. See `.planning/todos/pending/2026-08-02-add-errorprone-for-compile-time-bug-detection.md`.
- [minor] Add ArchUnit to enforce documented layering and ownership-verification rules — blocked on `build.gradle` unlock post-Phase-3. See `.planning/todos/pending/2026-08-02-add-archunit-to-enforce-documented-layering-and-ownership-ve.md`.
- [minor] Auto-configure `git core.hooksPath` so the pre-commit hook needs no manual per-clone step — blocked on `build.gradle` unlock post-Phase-3. See `.planning/todos/pending/2026-08-02-auto-configure-git-core-hookspath-so-the-pre-commit-hook-nee.md`.

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

Last session: 2026-08-02T14:38:37.736Z
Stopped at: Completed 03-02-PLAN.md
Resume file: None

## Operator Next Steps

- Run `/gsd-plan-phase 2` to plan Kafka Foundation, Domain Events & Move Endpoint
