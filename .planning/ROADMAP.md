# Roadmap: Kanban Board Backend — Epic 2 Completion

## Overview

This milestone closes out the remaining Epic 2 deliverable of the backend modernization plan: retrofitting optimistic locking onto the existing Spring Boot 3.5.0 kanban API so concurrent drag-and-drop edits to the same task or column no longer silently overwrite each other. The work is a single, tightly-coupled slice — a `@Version` field on two entities, a corrected HTTP conflict mapping, a Lombok identity audit, and an end-to-end test proving the conflict surfaces as a 409 — delivered as one clean, independently reviewable PR matching the standard set by the completed portion of Epic 2.

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Optimistic Locking** - Add `@Version` to Task/Column, map concurrent conflicts to HTTP 409, and prove it end-to-end

## Phase Details

### Phase 1: Optimistic Locking

**Goal**: Concurrent conflicting updates to the same task or column are detected and rejected with HTTP 409 Conflict instead of silently overwriting each other, with entity identity preserved across saves and the real Postgres schema updated to match.
**Depends on**: Nothing (first phase)
**Requirements**: LOCK-01, LOCK-02, LOCK-03, LOCK-04
**Success Criteria** (what must be TRUE):

  1. `TaskEntity` and `ColumnEntity` each have a `@Version` field, and the real Postgres schema has a matching `version bigint NOT NULL DEFAULT 0` column on both tables (added via one-off manual `ALTER TABLE`, since `ddl-auto` is unset there).
  2. Two concurrent conflicting updates to the same task or column return HTTP 409 Conflict — not 423 Locked, not 500, not a silent overwrite (the existing incorrect `GlobalExceptionHandler` mapping of `OptimisticLockingFailureException` is fixed).
  3. An automated test drives two concurrent updates to the same task/column, produces `ObjectOptimisticLockingFailureException`, and asserts the outcome at the E2E/HTTP-status level (409), not just as a service-level exception type.
  4. Adding the `version` field does not break entity identity: `ColumnEntity`'s `@Data`-generated equals/hashCode and `TaskEntity`'s equals/hashCode exclude `version`, verified so the same entity remains equal to itself across saves.
  5. The bulk-delete version-bypass tradeoff is explicitly documented (the recently-added bulk JPQL delete paths skip `@Version` checks by design — accepted, not a bug), and `./gradlew spotlessCheck` and `./gradlew test` both pass.

**Plans**: 3/3 plans executed

- [x] 01-01-PLAN.md
- [x] 01-02-PLAN.md
- [x] 01-03-PLAN.md

## Progress

**Execution Order:**
Phases execute in numeric order: 1

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Optimistic Locking | 3/3 | In Progress|  |
