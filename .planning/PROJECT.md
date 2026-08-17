# Kanban Board Backend — Epic 2 Completion

## What This Is

A Spring Boot 3.5.16 / Java 21 REST API backend for a Kanban board application (users → boards → columns → tasks → subtasks), with session-based authentication, ownership-based access control, optimistic locking on concurrent edits, and a Kafka-backed, event-driven per-board activity log. Originally scoped to finish **Epic 2** of the [backend modernization plan](../docs/plans/backend-modernization/README.md) (v1.0); v1.1 additionally delivered Epic 1 (the activity feed). The project is a personal/portfolio showcase, and its next milestone shifts focus from feature epics to deployment infrastructure.

## Core Value

v1.0 and v1.1 shipped the backend-depth showcase (JPA/Hibernate optimistic locking, Kafka event sourcing with dead-letter reliability, idempotent consumption — all proven against real Postgres/Kafka, not mocks). With the AWS EC2/RDS deploy target deleted (2026-08-03, cost-risk driven), the next priority is making the project reachable and cheaply/reliably deployable again — Oracle Cloud + Neon + self-hosted Redpanda — without diluting the backend-depth narrative that is this project's actual differentiator.

## Current Milestone: v1.2 Infra Migration & Schema Registry

**Goal:** Redeploy the app on a cost-guarded, always-free/near-free stack after the AWS EC2/RDS deployment was deleted, and add a Schema Registry (Avro/Protobuf) in front of the Kafka activity-log pipeline to close the schema-evolution risk flagged during v1.1.

**Target features:**
- App redeployed on Oracle Cloud Always Free (A1 Flex VM) via Docker
- Neon serverless Postgres (scale-to-zero) replacing the deleted RDS/EC2-hosted Postgres — no JPA/Hibernate code changes
- Self-hosted single-node Redpanda broker replacing local-only Kafka for the deploy target — Kafka-protocol-compatible, no producer/consumer/DLQ code changes
- GitHub Actions CI/CD pipeline for automated deploy
- A new pre-merge DDL verification step against the new deploy target (the old one, against the deleted AWS Postgres instance, was acknowledged as superseded at v1.1 close)
- Schema Registry (Confluent or Redpanda's built-in) with Avro/Protobuf schemas for the 5 `ActivityEvent` record types, replacing convention-based `JsonSerializer`/`JsonDeserializer` agreement (promoted from SEED-001, planted during v1.1 Phase 3)

## Requirements

### Validated

- ✓ Board/Column/Task/Subtask CRUD with ownership-based access control — existing
- ✓ Session-based authentication (Spring Security + JDBC session store) — existing
- ✓ Cascading deletes (board → columns → tasks → subtasks) — existing
- ✓ Bulk task/column delete N+1 fix (batched JPQL deletes, `entityManager.flush()/clear()`) — Epic 2, done 2026-07-31
- ✓ Query-count regression guard (`OwnershipVerifierServiceTest.QueryCountTest`) — Epic 2, done 2026-07-31
- ✓ Confirmed non-issue: `OwnershipVerifierService` chain already resolves in 1 query via EAGER joins (Finding 1) — no code change needed
- ✓ Optimistic locking on `TaskEntity` and `ColumnEntity` (`@Version`), required client-supplied version checked explicitly before mutation on both `TaskService.updateById` and the new `ColumnService.updateById`, `GlobalExceptionHandler`'s 423→409 fix, and a Lombok equals/hashCode exclusion on `ColumnEntity.version` — Phase 1, done 2026-08-01. Proven end-to-end by real HTTP E2E tests (`TaskLockingE2ETest`, `ColumnLockingE2ETest`).
- ✓ New `PUT /boards/{boardId}/columns/{columnId}` endpoint — Phase 1, done 2026-08-01 (previously missing; added so `ColumnEntity`'s `@Version` is reachable/testable via the API)
- ✓ Local Kafka dev stack (`docker-compose.yml`: postgres + KRaft-mode `apache/kafka-native` + app, health-gated) — v1.1 Phase 2, done 2026-08-01
- ✓ `PATCH /tasks/{taskId}/move` endpoint reusing the Phase 1 explicit-version-compare convention (409 stale version, 400 cross-board) — v1.1 Phase 2, done 2026-08-01
- ✓ All 5 domain mutation types (task create/move/delete, board create, column create) publish typed, sealed `ActivityEvent` records via `KafkaEventPublisher` only after transaction commit — v1.1 Phase 2, done 2026-08-01. Proven live by killing the Kafka broker mid-request and confirming mutations still succeed with the failure logged, never swallowed.
- ✓ Idempotent, deduplicated activity-log consumer (`ActivityLogConsumer`/`ActivityLogRecorder`) with a database-unique-constraint-backed dedup and a dead-letter topic (byte-fidelity preserved) for poison messages — v1.1 Phase 3, done 2026-08-02. Proven against a real Testcontainers Kafka broker, not mocks.
- ✓ `GET /boards/{boardId}/activity` — paginated, ownership-verified, deterministic newest-first read endpoint — v1.1 Phase 3, done 2026-08-02
- ✓ Avro schemas for all 5 `ActivityEvent` types with enforced BACKWARD compatibility, build-time registration (not producer auto-registration), DLT byte-fidelity under Avro, and a real-historical-data rehearsal — v1.2 Phase 4, done 2026-08-04
- ✓ Flyway-managed domain schema: V1–V4 reconstructing the schema's real evolution, `ddl-auto=validate` outside the test profile so Hibernate can no longer create or alter — v1.2 Phase 04.1, done 2026-08-05
- ✓ Whole test suite runs against a Testcontainers PostgreSQL 16 instance whose schema is built by the same Flyway V1–V4 migrations production runs; `com.h2database:h2` removed outright with no fallback profile — v1.2 Phase 04.2, done 2026-08-06. Verified 17/17 must-haves against the live codebase; schema provenance is enforced by a standing test (`FlywaySchemaProvenanceTest`) asserting Flyway-only artifacts and zero Hibernate-generated constraint names, so a silent regression to Hibernate-built DDL fails the build. Measured ~19s/6.1% **faster** than the like-for-like H2 baseline, inverting the expected cost.
- ✓ Production redeploy on a cost-guarded stack (Netcup VPS, pivoted from Oracle Cloud A1 Flex after 200+ automated provisioning attempts hit structural capacity constraints) — Neon serverless Postgres, self-hosted resource-capped Redpanda, Caddy automatic HTTPS, GitHub Actions CI/CD with a pre-merge Flyway verification gate — v1.2 Phase 5, done 2026-08-17. All 8 INFRA requirements verified live by an independent `gsd-verifier` pass: real HTTPS health check, an off-VM external port scan (three independent full-range passes confirming only 22/80/443 reachable), Docker log-rotation proven deterministic via a throwaway container, AWS-era secrets confirmed absent, and Docker Hub tag pruning fixed and live-verified (two bugs: Basic-auth rejection, then pagination) after an initial same-day checkpoint halt. This closes out v1.2 (Infra Migration & Schema Registry) — all 7 phases of the milestone are now complete.

### Active

(None yet — next milestone's scope not yet defined. v1.2 is fully shipped; run `/gsd-complete-milestone` to archive and open the next one.)

### Out of Scope

- `GET /boards/{boardId}/full` (nested board→columns→tasks→subtasks endpoint) — deferred to v2 per REQUIREMENTS.md history; scoped out during v1.0's requirements definition, not permanently excluded
- Epics 4, 6, 7 (Redis, Observability, Kubernetes) — deferred to future milestones. Two of the originally-deferred epics have since been pulled forward and delivered as inserted v1.2 phases: Epic 3's Flyway half (Phase 04.1) and Epic 5 (Testcontainers, drop H2 — Phase 04.2). Epic 3's OpenAPI-polish half remains deferred and is the only part of Epic 3 still outstanding.
- Re-fixing `OwnershipVerifierService`'s chain of `findById()` calls (Finding 1) — measured and confirmed non-issue; already resolves in 1 query via EAGER join chain, no work needed
- Full microservice extraction of the activity-log consumer into a separate deployable service — the in-process `@KafkaListener` already demonstrates the event-driven pattern; a possible later epic, not this one
- GraphQL, Elasticsearch, WebFlux/reactive, Kotlin, Oracle/PL-SQL, Angular — explicitly excluded by the modernization plan as niche/low-ROI for this project's scope
- Cursor/keyset pagination on the activity feed (PAGE-V2-01) — offset `Pageable` shipped in v1.1 for API consistency; revisit only if activity-log scale becomes a real concern
- Field-level diff logging, DLT replay/reprocessing admin tooling, real-time push (WebSocket/SSE) of activity updates, retention/archival policy, generic/pluggable event schema — all explicitly excluded from v1.1's scope per REQUIREMENTS.md's Out of Scope table; reasons still valid, no signal any of these are needed yet

## Context

- This is a personal/portfolio project; the modernization plan's stated purpose is closing real technology gaps in the backend stack (see [README.md](../docs/plans/backend-modernization/README.md)).
- Full epic specs: [02-n-plus-one-optimistic-locking.md](../docs/plans/backend-modernization/02-n-plus-one-optimistic-locking.md) (v1.0), Epic 1 Kafka activity feed (v1.1, archived requirements at `.planning/milestones/v1.1-REQUIREMENTS.md`).
- Progress notes and gotchas already hit during the completed portion of Epic 2 are logged in [STATUS.md](../docs/plans/backend-modernization/STATUS.md) — includes a real gotcha about mixing derived `deleteAllByXIn` (fetch-then-remove) with bulk JPQL deletes causing FK violations, and stale persistence-context entities requiring `flush()`/`clear()`.
- Codebase map available at `.planning/codebase/` (ARCHITECTURE.md, STACK.md, CONVENTIONS.md, TESTING.md, INTEGRATIONS.md, CONCERNS.md, STRUCTURE.md) — read before planning.
- Existing convention: services use `@Autowired` field injection (not constructor) to sidestep circular bean dependencies between Board/Column/Task/Subtask/Ownership services.
- Existing convention: DTOs are flat (no nested entity graphs) specifically to avoid `LazyInitializationException` — the deferred `/full` endpoint DTO will need deliberate nested structure, a departure from this pattern that should be justified explicitly.
- Phase 1's required auth-path fix (`UserAuthenticationProvider` propagating the actual authenticated principal instead of a broken bare-userId string) initially introduced a real security regression — the full `UserEntity` (with bcrypt `passwordHash`) flowing into the JDBC-backed session table. Caught by code review, fixed same-session (commit `c5fb656`) by using a minimal Spring Security `User` principal instead.
- **Infrastructure change (2026-08-03):** The operator deleted the project's AWS EC2 instance and its Postgres database, moving off AWS due to unpredictable pricing risk. This makes the "run the DDL bridge script against the real Postgres deploy target" human-verification step from v1.1 Phase 3 permanently moot — there is no longer a deploy target for it to run against (acknowledged in `03-VERIFICATION.md`'s Acknowledged Gaps and `03-UAT.md`). A v1.2 infra-migration milestone is next: Oracle Cloud Always Free (compute) + Neon (serverless Postgres, scale-to-zero, zero JPA/Hibernate code changes needed) + a self-hosted single-node Redpanda broker (Kafka-protocol-compatible, zero producer/consumer/DLQ code changes needed) + GitHub Actions CI/CD. That milestone will need its own equivalent pre-merge DDL verification step against the new deploy target.
- Phase 2 (Kafka Foundation, Domain Events & Move Endpoint) was executed successfully but its verification step was never run at the time — discovered during the pre-close audit for v1.1. Retroactively verified 2026-08-03: 15/15 must-haves passed against a live Docker Compose stack, no regressions to Phase 3.

## Constraints

- **Tech stack**: Spring Boot 3.5.16, Java 21, Spring Data JPA/Hibernate, PostgreSQL for both production and tests (tests run against a Testcontainers-managed PostgreSQL instance executing the same Flyway migrations) — no new frameworks introduced for this scope
- **Testing**: Match existing convention — unit tests for services/DTOs, integration tests (REST Assured) for controllers; query-count assertions via Hibernate `Statistics.getPrepareStatementCount()` (not `getQueryExecutionCount()`, which misses `findById()` calls)
- **PR discipline**: This work should remain reviewable as its own unit, consistent with the modernization plan's one-epic-per-PR intent
- **Format check**: `./gradlew spotlessCheck` and `./gradlew test` must pass (matches existing CI)

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Scope this GSD project to Epic 2 completion only, not the full 7-epic plan | User wants to resume exactly where prior work left off rather than commit to the full modernization roadmap right now | ✓ Good |
| Treat Finding 1 (ownership chain) as closed, no further code change | Already measured and confirmed as 1 query via EAGER join chain; re-opening would be wasted effort | ✓ Good |
| Narrow v1 to optimistic locking only; defer `/full` endpoint to v2 | User confirmed this narrower scope during requirements definition, after research showed both were independent, separately-sized pieces of work | ✓ Good |
| Client-supplied `version` required + explicitly compared server-side, not just relying on Hibernate's automatic `@Version` dirty-check | This codebase's update flow loads-then-saves fresh within one transaction, so the automatic check alone would never catch a stale-read conflict across separate requests — the explicit check is what actually delivers the phase's goal | ✓ Good |
| Manual DDL now, not deferred to Epic 3/Flyway | Deferring would leave production broken between merge and Epic 3 landing, since master auto-deploys on push | ✓ Good — script delivered; live-DB run superseded by AWS deletion, moved to v1.2 (see Context) |
| Continue phase numbering across milestones (v1.1 starts at Phase 2, not Phase 1) | Keeps a single, continuous phase history across the whole modernization plan rather than resetting per milestone | ✓ Good — though it produced a tooling false-positive at v1.1 close (a phase-completeness checker misread the already-shipped, archived Phase 1 as "unstarted"); required `--force` after manual verification, not a real gap |
| Split v1.1 into 2 coarse phases (producer, then consumer) instead of research's suggested 5 | Producer side is independently buildable/testable with a mocked `KafkaTemplate`; consumer side depends on producer's event contracts and needs a running pipeline | ✓ Good |
| Retroactively verify Phase 2 before v1.1 close, rather than skip it | Phase 2 had zero verification artifacts (executed but the verifier step never ran) — a real gap in project history, not acceptable to paper over | ✓ Good — 15/15 must-haves passed |
| Resolve the stale Phase 3 UAT blocker (prod DDL check) as superseded rather than force-passing it | The AWS deploy target it referenced was deleted; forcing it to "pass" would misrepresent what was actually verified | ✓ Good |
| Error Prone landed at "hard-gate main and test" (`allErrorsAsWarnings` removed, 5 checks promoted to ERROR on `compileTestJava`) rather than the todo's literal "just remove the demotion" | Measured that removing the demotion alone was a no-op (all 27 test findings were WARNING severity); promotion is what makes "hard-gate" true | ✓ Good — teeth-checked by reintroduce-and-revert |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-08-17 after Phase 5 (Infra Migration) completed and verified — v1.2 milestone fully shipped*
