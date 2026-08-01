---
phase: 02-kafka-foundation-domain-events-move-endpoint
plan: 02
subsystem: kafka-producer-domain-events
tags: [kafka, spring-kafka, transactional-event-listener, async, ownership]
dependency-graph:
  requires:
    - "Plan 01: event.ActivityEvent sealed interface, config.KafkaEventPublisher, RecordingActivityEventListener"
  provides:
    - "event.TaskCreatedEvent, event.TaskDeletedEvent, event.BoardCreatedEvent, event.ColumnCreatedEvent (ActivityEvent permits clause now names all five records — EVENT-01 fully satisfied)"
    - "@Transactional on TaskService.save, BoardService.save, ColumnService.save (closes the ambient-transaction dependency the after-commit publish guarantee relied on)"
    - "ActivityEventPublicationTest — after-commit publication proof for all five event types plus rollback suppression and cross-mutation event integrity"
  affects:
    - "TaskService.deleteById (boardId/columnId captured into locals before the delete, for TaskDeletedEvent)"
    - "BoardService, ColumnService (new eventPublisher field each)"
tech-stack:
  added: []
  patterns:
    - "Repeat Plan 01's shape per mutation type: ApplicationEventPublisher.publishEvent at the tail of a now-@Transactional save/delete method, actor/boardId always derived from the ownership-verified entity graph, never a request DTO field"
    - "Capture ids into locals before a delete runs, since the row (and its FK chain) is gone afterward and Phase 3's consumer has no SecurityContext to re-derive them"
key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/event/TaskCreatedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/TaskDeletedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/BoardCreatedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/ColumnCreatedEvent.java
    - src/test/java/com/vrudenko/kanban_board/event/ActivityEventPublicationTest.java
    - src/main/java/com/vrudenko/kanban_board/config/AsyncConfig.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java
    - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
    - src/main/java/com/vrudenko/kanban_board/service/BoardService.java
    - src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
    - src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java
    - src/main/resources/application-test.properties
decisions:
  - "Added @EnableAsync + a bounded kafkaPublishExecutor pool and dispatched KafkaEventPublisher.onActivityEvent via @Async, discovered as a post-task correctness/performance fix (not in the original plan) — KafkaTemplate.send() blocks the calling thread inside KafkaProducer.doSend -> waitOnMetadata for up to max.block.ms even before returning its future, so the AFTER_COMMIT listener thread (request thread in prod, AbstractAppTest.setup()'s fixture-creation thread in tests) still stalled on every mutation despite the bounded timeout. AbstractAppTest.setup() creates ~18 fixtures per test method across 17 fixture-heavy test classes (144 total @Test methods), which compounded into a 20-25 minute full-suite hang with no broker in the test environment."
  - "Lowered application-test.properties producer timeouts from 2000ms to 50ms as a secondary mitigation — publication is already verified at the Spring-event level via RecordingActivityEventListener (not via real Kafka delivery), so the real network attempt in ordinary tests has no verification value, only wall-clock cost."
  - "Did not add a circuit breaker for repeated wasted Kafka attempts during an outage (each send() independently re-runs waitOnMetadata with no memory of prior failures) — the async fix already makes this invisible to callers; deferred as out of scope for this milestone per explicit user decision."
metrics:
  duration: ~90min (including post-task hang investigation and fix)
  completed: 2026-08-01
status: complete
actuals:
  tokens: 27000
  tasks: 3
  commits: 4
---

# Phase 2 Plan 02: Domain Events Expansion Summary

Expanded the tracer's event-publishing shape to the four remaining mutation types
(`TaskCreatedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`), added
`@Transactional` to the three `save()` methods that previously relied on an ambient caller
transaction, and proved the negative cases (rollback publishes nothing, update paths publish
nothing, two mutations in one transaction each get their own distinct event) with a dedicated
test. Discovered post-task that the after-commit Kafka send was blocking its caller thread
despite the bounded timeout — compounding into a 20-25 minute full-suite hang via
`AbstractAppTest`'s fixture-heavy `@BeforeEach` — and fixed it by dispatching the publish onto a
background executor via `@Async`, restoring the full suite to ~1m5s.

## What Was Built

**Task 1:** `TaskCreatedEvent`/`TaskDeletedEvent` records, widened `ActivityEvent` permits clause,
`@Transactional` added to `TaskService.save`, publish call at the tail of `save` deriving
`userId`/`boardId` from the ownership-verified `ColumnEntity` (not a request field).
`TaskService.deleteById` captures `boardId`/`columnId`/`taskId` into locals *before* the delete
runs (the row is gone afterward — nothing left to derive `boardId` from, and Phase 3's consumer
has no `SecurityContext` to re-verify ownership and look it up). `updateById` deliberately
publishes nothing — REQUIREMENTS.md defines exactly five event types, no update event.

**Task 2:** `BoardCreatedEvent`/`ColumnCreatedEvent` records, permits clause now names all five
records (EVENT-01 fully satisfied). `@Transactional` + a new `eventPublisher` field added to
`BoardService`/`ColumnService`; `save()` on each publishes its creation event after commit,
actor derived from the already-resolved `UserEntity`/`BoardEntity` parameter. Neither
`updateById`, `deleteById`, nor either bulk-delete method publishes anything.

**Task 3:** Extended `ActivityEventPublicationTest` with a `@Nested` transactional-suppression
group: a rolled-back mutation (ownership check fails before persist) records zero events; two
publishing mutations inside one outer transaction each produce their own distinct event
(`eventId`s differ) after that single commit; every recorded event across a representative set of
mutations has non-null `eventId`/`userId`/`boardId`/`timestamp`.

**Post-task fix (not part of the original plan):** Running the full suite exposed a 20-25 minute
hang. Root-caused via thread dump to `KafkaProducer.doSend -> waitOnMetadata` blocking the
*calling* thread for the full `max.block.ms` bound before ever returning its `Future` — meaning
the bounded timeout added in Plan 01 protected against unbounded blocking but not against blocking
*at all*. `AbstractAppTest.setup()` (used by 17 test classes, 144 total `@Test` methods) creates
roughly 18 board/column/task fixtures per test method, each now going through the newly
`@Transactional`, publishing `save()`/`deleteById()` paths from this plan — multiplying that
per-call block across the whole suite. Fixed by adding `AsyncConfig` (`@EnableAsync` + a bounded
`kafkaPublishExecutor` pool) and marking `KafkaEventPublisher.onActivityEvent` `@Async`, so the
publish now runs entirely off the caller's thread — neither production requests nor test fixture
creation wait on Kafka reachability at all. Also lowered `application-test.properties`'s producer
timeouts from 2000ms to 50ms as a secondary mitigation (publication is already verified at the
Spring-event level via `RecordingActivityEventListener`, so the real network attempt has no test
value). Full suite: 144/144 passing, 1m5s (down from the 20-25 minute hang).

## Task Commits

1. **Task 1: Task lifecycle events (created, deleted)** — `24b611b` (feat)
2. **Task 2: Board and column creation events** — `5cfdc7c` (feat)
3. **Task 3: Prove the negatives** — `62a0a06` (test)
4. **Post-task fix: async Kafka dispatch** — `40948c8` (fix, discovered during full-suite verification, not part of the original 3-task plan)

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java` — permits clause widened to all 5 records
- `src/main/java/com/vrudenko/kanban_board/event/TaskCreatedEvent.java`, `TaskDeletedEvent.java`, `BoardCreatedEvent.java`, `ColumnCreatedEvent.java` — new records
- `src/main/java/com/vrudenko/kanban_board/service/TaskService.java` — `@Transactional` on `save`; boardId/columnId captured before delete in `deleteById`
- `src/main/java/com/vrudenko/kanban_board/service/BoardService.java`, `ColumnService.java` — new `eventPublisher` field, `@Transactional` on `save`
- `src/test/java/com/vrudenko/kanban_board/event/ActivityEventPublicationTest.java` — new, 357 lines, covers all 5 event types plus rollback/cross-mutation proofs
- `src/main/java/com/vrudenko/kanban_board/config/AsyncConfig.java` — new, `@EnableAsync` + bounded `kafkaPublishExecutor`
- `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java` — `@Async("kafkaPublishExecutor")` added
- `src/main/resources/application-test.properties` — producer timeouts 2000ms → 50ms

## Decisions Made

See `decisions` in frontmatter — async dispatch fix, lowered test-profile timeouts, explicit
decision to skip a circuit breaker for this milestone (async fix judged sufficient; user confirmed).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug, discovered post-task] Kafka publish blocked its caller thread despite the bounded timeout**
- **Found during:** Full-suite verification after Task 3 completed and committed.
- **Issue:** `KafkaTemplate.send()` blocks synchronously inside `KafkaProducer.doSend -> waitOnMetadata`
  for up to `max.block.ms` before returning its `Future` — the bounded-timeout fix from Plan 01
  prevented an *unbounded* hang but not blocking *at all*. With no broker in the test environment,
  every fixture creation in `AbstractAppTest.setup()` (17 fixture-heavy test classes) paid that cost,
  compounding into a 20-25 minute full-suite hang.
- **Fix:** Added `@EnableAsync` + `kafkaPublishExecutor` (`AsyncConfig`), dispatched the publish via
  `@Async`. Also lowered test-profile timeouts 2000ms → 50ms.
- **Files modified:** `config/AsyncConfig.java` (new), `config/KafkaEventPublisher.java`,
  `application-test.properties`
- **Verification:** Full suite 144/144 passing, `spotlessCheck` green, 1m5s wall clock (was 20-25+ min).
- **Committed in:** `40948c8`

---

**Total deviations:** 1 auto-fixed (Rule 1 - bug fix, discovered during post-implementation verification, not anticipated by the plan's own acceptance criteria since Plan 01's ~50-55s baseline was measured before this plan's broader `@Transactional`+publish wiring existed).
**Impact on plan:** No scope creep — the fix is entirely infrastructural (async dispatch), touches no domain logic, and doesn't change any of EVENT-01/EVENT-02's functional guarantees (still exactly one event per publishing mutation, still zero for rollback/update paths). All of Plan 02's own acceptance criteria and threat-model mitigations remain satisfied as written.

## Verification Evidence

- `./gradlew test` (full suite) — 144/144 passing, 0 failures, 0 errors, 1m5s.
- `./gradlew spotlessCheck` — exits 0.
- `ls src/main/java/com/vrudenko/kanban_board/event/ | wc -l` → 6 (`ActivityEvent`, `TaskMovedEvent`, `TaskCreatedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`).
- `grep -c publishEvent TaskService.java` → 3 (created, moved, deleted); `BoardService.java`/`ColumnService.java` → 1 each.
- No file under `service/` imports the Kafka client API (`KafkaEventPublisher` remains the sole touchpoint).
- No user-authored text (title/description/name) in any event record.

## Known Stubs

None — every artifact this plan promised is a real, wired implementation.

## Threat Flags

T-02-09 (Tampering — an event delivered for a mutation that never committed) is strengthened, not
weakened, by the async fix: `@TransactionalEventListener(AFTER_COMMIT)` still gates publication to
after-commit; `@Async` only changes which thread performs the sends, not when they're triggered.

## Self-Check: PASSED

- `src/main/java/com/vrudenko/kanban_board/event/TaskCreatedEvent.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/event/TaskDeletedEvent.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/event/BoardCreatedEvent.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/event/ColumnCreatedEvent.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/config/AsyncConfig.java` — FOUND
- `src/test/java/com/vrudenko/kanban_board/event/ActivityEventPublicationTest.java` — FOUND
- Commit `24b611b` — FOUND in `git log --oneline --all`
- Commit `5cfdc7c` — FOUND in `git log --oneline --all`
- Commit `62a0a06` — FOUND in `git log --oneline --all`
- Commit `40948c8` — FOUND in `git log --oneline --all`
