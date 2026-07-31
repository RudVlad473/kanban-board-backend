# Kanban Board Backend — Epic 2 Completion

## What This Is

A Spring Boot 3.5.0 / Java 21 REST API backend for a Kanban board application (users → boards → columns → tasks → subtasks), with session-based authentication and ownership-based access control. This GSD project scopes specifically to finishing **Epic 2** of the existing [backend modernization plan](../docs/plans/backend-modernization/README.md): closing out the JPA/Hibernate depth work (N+1 fixes, optimistic locking) that was partially completed in a prior session.

## Core Value

Ship the two remaining Epic 2 deliverables — a real "get full board" endpoint and optimistic locking on concurrent edits — as clean, independently reviewable, interview-defensible work that matches the standard already set by the completed part of Epic 2.

## Requirements

### Validated

- ✓ Board/Column/Task/Subtask CRUD with ownership-based access control — existing
- ✓ Session-based authentication (Spring Security + JDBC session store) — existing
- ✓ Cascading deletes (board → columns → tasks → subtasks) — existing
- ✓ Bulk task/column delete N+1 fix (batched JPQL deletes, `entityManager.flush()/clear()`) — Epic 2, done 2026-07-31
- ✓ Query-count regression guard (`OwnershipVerifierServiceTest.QueryCountTest`) — Epic 2, done 2026-07-31
- ✓ Confirmed non-issue: `OwnershipVerifierService` chain already resolves in 1 query via EAGER joins (Finding 1) — no code change needed

### Active

- [ ] `GET /boards/{boardId}/full` — single endpoint returning a board with nested columns, tasks, and subtasks, avoiding client-side N+1 round trips. Must avoid Cartesian-product blowup from a naive triple `JOIN FETCH` across two `List` collections — use `@BatchSize` or two-queries-and-stitch, with the choice explained.
- [ ] Optimistic locking on `TaskEntity` and `ColumnEntity` (`@Version`) to prevent silent overwrite on concurrent drag-and-drop reorder/move. Includes a test proving `ObjectOptimisticLockingFailureException` on conflicting concurrent updates, and a 409 mapping in `GlobalExceptionHandler`.

### Out of Scope

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

## Constraints

- **Tech stack**: Spring Boot 3.5.0, Java 21, Spring Data JPA/Hibernate, PostgreSQL (H2 for tests) — no new frameworks introduced for this scope
- **Testing**: Match existing convention — unit tests for services/DTOs, integration tests (REST Assured) for controllers; query-count assertions via Hibernate `Statistics.getPrepareStatementCount()` (not `getQueryExecutionCount()`, which misses `findById()` calls)
- **PR discipline**: This work should remain reviewable as its own unit, consistent with the modernization plan's one-epic-per-PR intent
- **Format check**: `./gradlew spotlessCheck` and `./gradlew test` must pass (matches existing CI)

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Scope this GSD project to Epic 2 completion only, not the full 7-epic plan | User wants to resume exactly where prior work left off rather than commit to the full modernization roadmap right now | — Pending |
| Treat Finding 1 (ownership chain) as closed, no further code change | Already measured and confirmed as 1 query via EAGER join chain; re-opening would be wasted effort | ✓ Good |
| `/full` endpoint fetch strategy (`@BatchSize` vs two-query-stitch vs naive triple JOIN FETCH) deferred to phase planning/research | Needs codebase-specific investigation of collection sizes and Cartesian-product risk before committing | — Pending |

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
*Last updated: 2026-07-31 after initialization*
