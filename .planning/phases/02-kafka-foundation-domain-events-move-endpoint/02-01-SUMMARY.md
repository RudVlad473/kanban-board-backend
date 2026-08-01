---
phase: 02-kafka-foundation-domain-events-move-endpoint
plan: 01
subsystem: kafka-producer-move-endpoint
tags: [kafka, spring-kafka, transactional-event-listener, optimistic-locking, move-endpoint]
dependency-graph:
  requires:
    - "Phase 1: TaskEntity/ColumnEntity @Version + explicit compare-before-mutate convention"
  provides:
    - "event.ActivityEvent sealed interface (permits TaskMovedEvent — Plan 02 widens the permits clause)"
    - "config.KafkaEventPublisher (@TransactionalEventListener AFTER_COMMIT, sole Kafka client API surface)"
    - "constant.KafkaTopics.ACTIVITY"
    - "PATCH /tasks/{taskId}/move endpoint (MOVE-01/02/03)"
    - "support.RecordingActivityEventListener test fixture (reused by Plan 02)"
  affects:
    - "TaskService (new moveToColumn method, new eventPublisher field)"
    - "AbstractAppTest (new createColumnForUser fixture helper)"
tech-stack:
  added:
    - "org.springframework.kafka:spring-kafka (BOM-managed, no version string)"
    - "org.testcontainers:kafka, org.testcontainers:junit-jupiter, org.springframework.boot:spring-boot-testcontainers (declared this phase, exercised in Phase 3)"
  patterns:
    - "ApplicationEventPublisher + @TransactionalEventListener(phase = AFTER_COMMIT) — publish inside the transaction, send to Kafka only after commit"
    - "Bounded producer timeouts (max.block.ms/request.timeout.ms/delivery.timeout.ms=2000) in both application.properties and application-test.properties so a missing broker never blocks a mutation"
key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/constant/KafkaTopics.java
    - src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/TaskMovedEvent.java
    - src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java
    - src/main/java/com/vrudenko/kanban_board/dto/task_dto/MoveTaskRequestDTO.java
    - src/main/java/com/vrudenko/kanban_board/controller/TaskMoveController.java
    - src/test/java/com/vrudenko/kanban_board/support/RecordingActivityEventListener.java
    - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveE2ETest.java
  modified:
    - build.gradle
    - src/main/resources/application.properties
    - src/main/resources/application-test.properties
    - src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java
    - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
    - src/test/java/com/vrudenko/kanban_board/AbstractAppTest.java
decisions:
  - "Kept the tracer task's KafkaEventPublisher as the ONLY Kafka client API touchpoint in src/main, confirmed by grep — TaskService never imports org.springframework.kafka"
  - "Cross-board guard (MOVE-03, 400) runs before the version guard (MOVE-02, 409) since a wrong-board target is a request-shape problem, not a concurrency one"
  - "Added AbstractAppTest.createColumnForUser (service-layer fixture helper) because there is no REST endpoint for creating a board directly — boards are only created via UserService.addBoardByUserId — so the unowned-target rejection test needed a way to seed a second user's board+column"
metrics:
  duration: 14min
  completed: 2026-08-01
status: complete
actuals:
  tokens: 10182
  tasks: 2
  commits: 2
---

# Phase 2 Plan 01: Kafka Foundation, Domain Events & Move Endpoint (Tracer Slice) Summary

Wired `spring-kafka` + Testcontainers onto the classpath with bounded producer timeouts, built the
sealed `ActivityEvent` contract and `TaskMovedEvent` record, added the after-commit
`KafkaEventPublisher`, and shipped a real `PATCH /tasks/{taskId}/move` endpoint that reuses the
existing explicit `@Version` compare-before-mutate convention and rejects every unsafe move
(stale version, cross-board target, unowned target) — proven end-to-end with no Kafka broker
running.

## What Was Built

**Task 1 (tracer):** The full vertical slice — Gradle dependencies, Kafka producer configuration
(bounded `max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms=2000` in both `application.properties`
and `application-test.properties`), the `event` package (`ActivityEvent` sealed interface,
`TaskMovedEvent` record), `KafkaEventPublisher` (`@TransactionalEventListener(AFTER_COMMIT)`, the
sole Kafka client API touchpoint in `src/main`), `MoveTaskRequestDTO`, `TaskService.moveToColumn`
(ownership-verifies both the source task and target column, cross-board 400 before version-check
409, then publishes `TaskMovedEvent` with server-derived `userId`/`boardId`), the new
`TaskMoveController` (flat route — `TaskController`'s nested mapping structurally cannot serve
`/tasks/{taskId}/move`), `RecordingActivityEventListener` (real Spring wiring, no mocks), and the
happy-path `TaskMoveE2ETest`. Verified: `./gradlew test` passes with no broker running anywhere,
and the full suite's wall-clock time (~50-55s) shows no regression from the bounded timeouts.

**Task 2:** Extended `TaskMoveE2ETest` with `@Nested MoveToColumn` covering every rejection path:
stale version (409, with the same stale request retried a third time and still rejected), two
concurrent moves from the same starting version (first wins/200, second loses/409, never a silent
overwrite), a cross-board target on a different board owned by the *same* user (400 — the case
where ownership verification alone would pass), a target column owned by *another* user (401, via
the existing `OwnershipVerifierService.verifyOwnershipOfColumn`), a missing `version` field (400,
bean validation), and unknown task/column ids (404). Every rejection case also asserts the task's
column is provably unchanged and that no `TaskMovedEvent` reached the after-commit recorder.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking issue] Added a service-layer fixture helper for cross-user board/column creation**
- **Found during:** Task 2, writing the "target column owned by another user → 401" test case.
- **Issue:** The plan's `read_first` section suggested using "the `boardService`/`columnService`
  fixture path already used by `AbstractAppTest`" to seed a second user's board+column, but those
  service fields are `private` in `AbstractAppTest` — not reachable from the test subclass. The
  first attempt tried creating the second user's board via `POST /boards`, which does not exist
  as a REST endpoint (boards are only created internally via `UserService.addBoardByUserId`,
  confirmed by reading `BoardController.java` and `UserService.java`) — this failed with an
  `IllegalStateException` when RestAssured tried to deserialize a non-JSON error response.
- **Fix:** Added `AbstractAppTest.createColumnForUser(userId, boardName, columnName)`, a protected
  fixture helper that goes through `userService.addBoardByUserId` + `boardService.addColumnByBoardId`
  directly, matching the pattern the rest of `AbstractAppTest`'s own `@BeforeEach` setup already
  uses. `UnownedTarget`'s test then only uses HTTP for the actual move attempt (signed in as the
  original owning user), which is the thing under test.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/AbstractAppTest.java`,
  `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveE2ETest.java`
- **Commit:** 1b3f1d7

## Verification Evidence

- `./gradlew spotlessCheck` — exits 0.
- `./gradlew test` — exits 0, full suite, no Kafka broker running anywhere, ~50-55s wall clock
  (no measurable regression from the bounded producer timeouts).
- `./gradlew test --tests TaskMoveE2ETest` — 8 tests, all green (1 happy-path + 7 rejection tests,
  `UnknownIds` contributing 2).
- `grep -rl "org.springframework.kafka\|org.apache.kafka" src/main/java/` — matches only
  `config/KafkaEventPublisher.java`.
- `git diff --name-only <task-1-commit> -- src/main` after Task 2 — empty (Task 2 touched only
  test files).
- `TaskController.java` and `GlobalExceptionHandler.java` — both unmodified (`git diff --name-only`
  empty for both), confirming the move endpoint lives on its own controller and reuses existing
  400/409/401 exception mappings with no new handler code.

## Known Stubs

None — every artifact this plan promised (`ActivityEvent`, `TaskMovedEvent`, `KafkaEventPublisher`,
`MoveTaskRequestDTO`, `TaskMoveController`, `TaskMoveE2ETest`) is a real, wired implementation, not
a placeholder.

## Threat Flags

None beyond what the plan's own `<threat_model>` already covers — no new network endpoints, auth
paths, or schema changes at trust boundaries were introduced outside what T-02-01 through T-02-05
already account for.

## Self-Check: PASSED

- `src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/event/TaskMovedEvent.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/constant/KafkaTopics.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/dto/task_dto/MoveTaskRequestDTO.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/controller/TaskMoveController.java` — FOUND
- `src/test/java/com/vrudenko/kanban_board/support/RecordingActivityEventListener.java` — FOUND
- `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveE2ETest.java` — FOUND
- Commit `9109045` — FOUND in `git log --oneline --all`
- Commit `1b3f1d7` — FOUND in `git log --oneline --all`
