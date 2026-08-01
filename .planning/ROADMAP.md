# Roadmap: Kanban Board Backend — Epic 2 Completion

## Overview

v1.0 closed out Epic 2 (optimistic locking). v1.1 delivers Epic 1 of the backend modernization plan: a real, event-driven per-board activity log. The producer side (Kafka infra, typed domain events published after commit, and the genuinely-missing "move task between columns" endpoint) ships first, since it can be built and unit-tested independently with a mocked `KafkaTemplate`. The consumer side (idempotent persistence, dead-letter isolation, the paginated read endpoint, and end-to-end verification against a real broker) ships second, since it depends on the producer's event contracts and needs a running pipeline to prove correctness against.

## Milestones

- ✅ **v1.0 Optimistic Locking** — Phase 1 (shipped 2026-08-01)
- 🚧 **v1.1 Kafka Activity Feed** — Phases 2-3 (in progress)

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

<details>
<summary>✅ v1.0 Optimistic Locking (Phase 1) — SHIPPED 2026-08-01</summary>

- [x] Phase 1: Optimistic Locking (3/3 plans) — completed 2026-08-01

</details>

### 🚧 v1.1 Kafka Activity Feed (In Progress)

**Milestone Goal:** Deliver a real, event-driven per-board activity log (Kafka + consumer + idempotent persistence), plus the genuinely-missing "move task between columns" endpoint, as Epic 1 of the backend modernization plan.

- [ ] **Phase 2: Kafka Foundation, Domain Events & Move Endpoint** - Local Kafka stack runs, mutations publish typed domain events only after commit, and users can move tasks between columns
- [ ] **Phase 3: Activity Log Consumer, Reliability & Read API** - Board owners can view a deduplicated, paginated activity feed; poison messages are isolated; the full pipeline is proven against a real broker

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

### Phase 2: Kafka Foundation, Domain Events & Move Endpoint

**Goal**: Local Kafka infrastructure runs alongside Postgres and the app, every successful board/column/task mutation publishes a typed domain event only after its transaction commits, and users can move a task to a different column using the same optimistic-locking convention already proven for Task/Column updates.
**Depends on**: Phase 1 (reuses its explicit `@Version` check-before-mutate convention)
**Requirements**: KAFKA-01, KAFKA-02, EVENT-01, EVENT-02, MOVE-01, MOVE-02, MOVE-03
**Success Criteria** (what must be TRUE):

  1. A developer can run `docker compose up` and get a fully working local stack — Postgres, a KRaft-mode Kafka broker (no Zookeeper), and the app itself — with `spring-kafka` and Testcontainers Kafka dependencies on the classpath.
  2. A user can move a task to a different column via `PATCH /tasks/{taskId}/move`; a request carrying a stale `version` is rejected with 409, using the same explicit compare-before-mutate convention as the existing Task/Column update endpoints.
  3. Moving a task to a column that belongs to a different board is rejected (400/403), not silently allowed.
  4. Every successful board create, column create, task create, task move, and task delete publishes its corresponding typed domain event (`TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`) to `kanban.activity` only after the enclosing transaction commits — a rolled-back mutation never publishes an event, and no committed mutation silently fails to publish.

**Plans**: TBD

### Phase 3: Activity Log Consumer, Reliability & Read API

**Goal**: Board owners can view a durable, deduplicated, paginated activity log covering every mutation type, poison messages are isolated to a dead-letter topic instead of stalling the pipeline, and the full producer-to-persistence path is proven correct against a real Kafka broker.
**Depends on**: Phase 2 (consumes the event contracts and topic it publishes)
**Requirements**: ACTLOG-01, ACTLOG-02, ACTLOG-03, READ-01, READ-02, RELY-01, RELY-02, TEST-01, TEST-02
**Success Criteria** (what must be TRUE):

  1. `GET /boards/{boardId}/activity` returns a newest-first, paginated activity feed covering all five mutation types (task create/move/delete, board create, column create), and only the board's owner can view it — other users are denied via the existing `OwnershipVerifierService.verifyOwnershipOfBoard`.
  2. Redelivering the same Kafka message (duplicate `eventId`) never creates a second `ActivityLogEntity` row — enforced by a database-level unique constraint on `eventId`, with `existsByEventId` as a fast-path check rather than the sole safety net.
  3. A message that fails processing is routed to the `kanban.activity.dlt` dead-letter topic and does not block subsequent activity events from being consumed.
  4. An automated Testcontainers-based test publishes a real event through a containerized Kafka broker end-to-end and confirms the corresponding `ActivityLogEntity` row is persisted; a companion test confirms duplicate delivery produces exactly one row, and another confirms a poison message reaches the DLT — the pipeline is proven, not just configured.

**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Optimistic Locking | 3/3 | Complete | 2026-08-01 |
| 2. Kafka Foundation, Domain Events & Move Endpoint | 0/TBD | Not started | - |
| 3. Activity Log Consumer, Reliability & Read API | 0/TBD | Not started | - |
