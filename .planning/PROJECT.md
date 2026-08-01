# Kanban Board Backend — Epic 2 Completion

## What This Is

A Spring Boot 3.5.0 / Java 21 REST API backend for a Kanban board application (users → boards → columns → tasks → subtasks), with session-based authentication and ownership-based access control. This GSD project scopes specifically to finishing **Epic 2** of the existing [backend modernization plan](../docs/plans/backend-modernization/README.md): closing out the JPA/Hibernate depth work (N+1 fixes, optimistic locking) that was partially completed in a prior session.

## Core Value

Ship optimistic locking on concurrent edits as clean, independently reviewable, interview-defensible work that matches the standard already set by the completed part of Epic 2. (The "get full board" endpoint was narrowed out to v2 during requirements definition — see Out of Scope.)

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

### Active

(None — Phase 1 was this project's only phase; all v1 requirements are now Validated.)

### Out of Scope

- `GET /boards/{boardId}/full` (nested board→columns→tasks→subtasks endpoint) — deferred to v2 per [REQUIREMENTS.md](REQUIREMENTS.md); scoped out of this GSD project after requirements definition, not permanently excluded
- Epics 1, 3–7 (Kafka activity feed, Flyway/OpenAPI polish, Redis, Testcontainers, Observability, Kubernetes) — deferred to future milestones, not part of this GSD project
- Re-fixing `OwnershipVerifierService`'s chain of `findById()` calls (Finding 1) — measured and confirmed non-issue; already resolves in 1 query via EAGER join chain, no work needed
- Full microservice extraction / broader architectural rework — not part of Epic 2's scope

## Context

- This is a personal/portfolio project; the modernization plan's stated purpose is closing real technology gaps for interview-relevance (see [README.md](../docs/plans/backend-modernization/README.md)), but this specific GSD project is scoped purely to the remaining Epic 2 technical work.
- Full epic spec: [02-n-plus-one-optimistic-locking.md](../docs/plans/backend-modernization/02-n-plus-one-optimistic-locking.md).
- Progress notes and gotchas already hit during the completed portion of Epic 2 are logged in [STATUS.md](../docs/plans/backend-modernization/STATUS.md) — includes a real gotcha about mixing derived `deleteAllByXIn` (fetch-then-remove) with bulk JPQL deletes causing FK violations, and stale persistence-context entities requiring `flush()`/`clear()`.
- Codebase map available at `.planning/codebase/` (ARCHITECTURE.md, STACK.md, CONVENTIONS.md, TESTING.md, INTEGRATIONS.md, CONCERNS.md, STRUCTURE.md) — read before planning.
- Existing convention: services use `@Autowired` field injection (not constructor) to sidestep circular bean dependencies between Board/Column/Task/Subtask/Ownership services.
- Existing convention: DTOs are flat (no nested entity graphs) specifically to avoid `LazyInitializationException` — the new `/full` endpoint DTO will need deliberate nested structure, a departure from this pattern that should be justified explicitly.
- **Outstanding manual step (Phase 1):** `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` has NOT been run against the real Postgres database yet. Since `master` auto-deploys to EC2 on every push, this script must be run manually right before this phase's PR merges/deploys, or every Task/Column request will 500 in production on a missing `version` column.
- Phase 1's required auth-path fix (`UserAuthenticationProvider` propagating the actual authenticated principal instead of a broken bare-userId string) initially introduced a real security regression — the full `UserEntity` (with bcrypt `passwordHash`) flowing into the JDBC-backed session table. Caught by code review, fixed same-session (commit `c5fb656`) by using a minimal Spring Security `User` principal instead.

## Constraints

- **Tech stack**: Spring Boot 3.5.0, Java 21, Spring Data JPA/Hibernate, PostgreSQL (H2 for tests) — no new frameworks introduced for this scope
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
| Manual DDL now, not deferred to Epic 3/Flyway | Deferring would leave production broken between merge and Epic 3 landing, since master auto-deploys on push | ✓ Good — script delivered; live-DB run still outstanding (see Context) |

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
*Last updated: 2026-08-01 after Phase 1 completion*
