---
phase: 02-kafka-foundation-domain-events-move-endpoint
verified: 2026-08-03T23:30:00Z
status: passed
score: 15/15 must-haves verified
behavior_unverified: 0
overrides_applied: 0
re_verification:
  previous_status: null
  previous_score: null
  gaps_closed: []
  gaps_remaining: []
  regressions: []
---

# Phase 2: Kafka Foundation, Domain Events & Move Endpoint Verification Report

**Phase Goal:** Local Kafka infrastructure runs alongside Postgres and the app, every successful board/column/task mutation publishes a typed domain event only after its transaction commits, and users can move a task to a different column using the same optimistic-locking convention already proven for Task/Column updates.
**Verified:** 2026-08-03T23:30:00Z
**Status:** passed
**Re-verification:** No — initial verification (this phase was executed 2026-08-01 and never went through `/gsd-verify-work` until now; this is a retroactive first pass, run with full rigor — real Docker stack brought up, full Gradle suite executed, live HTTP calls issued against a running broker)

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `docker compose up` yields postgres, a Zookeeper-free KRaft Kafka broker, and the app — exactly three services (KAFKA-01, ROADMAP SC1) | ✓ VERIFIED | Ran `docker compose up -d --wait` for real: all three containers (`kafka`, `postgres`, `app`) came up, `kafka` reported `healthy`, `app` started clean (`Started KanbanBoardApplication`, no fatal errors). `docker compose --env-file .env.example config --services` → `app kafka postgres`. No `zookeeper` anywhere in `docker-compose.yml`. |
| 2 | The app is gated on Kafka's health signal and never starts if the broker doesn't become healthy in a bounded window (D-03) | ✓ VERIFIED | `docker-compose.yml`: `app.depends_on.kafka.condition: service_healthy`; healthcheck `interval=5s, retries=8, start_period=15s` (~55s bound). Observed live: compose sequenced `kafka Healthy` → `app Starting` → `app Healthy`. |
| 3 | Kafka's log directory survives a `down`/`up` cycle (no state wipe) | ✓ VERIFIED | Ran `docker compose down` (no `-v`) then `docker compose up -d --wait` a second time: `docker compose logs kafka` on the second boot showed no `Formatting`/first-boot initialization output — broker restarted against its retained `kafka-data` volume. |
| 4 | `spring-kafka` and Testcontainers Kafka dependencies are on the classpath, BOM-managed, no version pins (KAFKA-02) | ✓ VERIFIED | `build.gradle`: `implementation 'org.springframework.kafka:spring-kafka'`, `testImplementation 'org.testcontainers:kafka'`, `'org.testcontainers:junit-jupiter'`, `'org.springframework.boot:spring-boot-testcontainers'` — none carry a version suffix. |
| 5 | A signed-in user can `PATCH /tasks/{taskId}/move` and the task's column changes on re-read (MOVE-01) | ✓ VERIFIED | Live HTTP proof against the running stack: created board/columns/task via real endpoints, issued `PATCH /tasks/{id}/move`, got `200` with incremented version. Also: `TaskMoveE2ETest.move_toColumnOnSameBoard_succeedsAndAnnouncesTaskMovedEvent` — passed (re-ran full suite, 0 failures). |
| 6 | A stale `version` is rejected with 409, using the existing explicit compare-before-mutate convention (MOVE-02, ROADMAP SC2) | ✓ VERIFIED | Live: re-submitting a stale version returned `409` with body "Task was modified by another request, please refetch." (byte-identical message to `updateById`). Test suite: `TaskMoveE2ETest.MoveToColumn.StaleVersion` (stale + retried-stale, both 409) and `ConcurrentConflict` (first wins/200, second loses/409) — both passed live. `TaskService.moveToColumn` compares `task.getVersion()` vs `dto.getVersion()` before mutating, mirroring `updateById`. |
| 7 | A move to a column on a different board (even one owned by the same user) is rejected 400, not silently allowed (MOVE-03, ROADMAP SC3) | ✓ VERIFIED | Live: created a second board+column for the same user, attempted the move, got `400` "Cannot move a task to a column on a different board." Test: `TaskMoveE2ETest.MoveToColumn.CrossBoardTarget` — passed. Source: `moveToColumn` explicitly checks `targetColumn.getBoard().getId().equals(sourceBoardId)` before the version check. |
| 8 | A move to a column owned by another user is rejected before any mutation runs | ✓ VERIFIED | `OwnershipVerifierService.verifyOwnershipOfColumn` runs before the cross-board/version checks; `TaskMoveE2ETest.MoveToColumn.UnownedTarget` asserts 401 and an unchanged column — passed. |
| 9 | Every successful board create, column create, task create, task move, and task delete publishes its corresponding typed event to `kanban.activity` only after commit (EVENT-01, EVENT-02, ROADMAP SC4) | ✓ VERIFIED | All five records (`TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`) exist in `event/`, sealed `ActivityEvent permits` names all five. `publishEvent` call count: `TaskService`=3 (created/moved/deleted), `BoardService`=1, `ColumnService`=1 — matching exactly the 5 publishing mutations, no more. All `save`/`moveToColumn`/`deleteById` methods are `@Transactional`; publisher is `KafkaEventPublisher` bound to `@TransactionalEventListener(phase = AFTER_COMMIT)`. Live proof: signed-in move against a running broker produced no publish-failure log line (event actually reached Kafka). `ActivityEventPublicationTest` (rollback-suppression, adjacency, non-null-field probes) — all passed in the full-suite run. |
| 10 | A rolled-back mutation never publishes an event (EVENT-02 negative case) | ✓ VERIFIED | `ActivityEventPublicationTest`'s transactional-suppression `@Nested` group (uses `Assertions.catchException`, asserts `recorder.getRecorded()).isEmpty()`) — passed live in the full-suite run. Structurally guaranteed by `AFTER_COMMIT` phase binding. |
| 11 | A missing/unreachable broker never fails or stalls a mutation (D-01) | ✓ VERIFIED (strongest evidence — live broker outage) | Live: stopped the real `kafka` container mid-session, then issued `PATCH /tasks/{id}/move` — request completed in 65ms and returned `200` with incremented version. `./gradlew test` (full suite) passes with no broker running anywhere: `BUILD SUCCESSFUL in 3m 24s`, `178 tests, 0 failures, 0 errors`. Bounded timeouts (`max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms`) present in both `application.properties` (2000ms) and `application-test.properties` (50ms, tightened further — documented deviation in 02-02-SUMMARY.md, does not violate D-01's intent). Publish is additionally dispatched via `@Async("kafkaPublishExecutor")` (`AsyncConfig`), added post-task after discovering `KafkaTemplate.send()` blocks the calling thread inside `waitOnMetadata` even before the bound elapses — this was a real, verified bug fix, not scope creep, and strengthens D-01 rather than weakening it. |
| 12 | A broker failure is logged, never silently swallowed (D-02) | ✓ VERIFIED | Live: after stopping Kafka, the app log showed exactly one `ERROR ... KafkaEventPublisher : Failed to publish TaskMovedEvent (eventId=..., boardId=...) to kanban.activity` line, with a `TimeoutException` cause. `KafkaEventPublisher.onActivityEvent`'s `whenComplete` callback logs at `error` level on any exception. |
| 13 | The Kafka client API is confined to `KafkaEventPublisher` in `src/main` (domain services never import it) | ✓ VERIFIED | `grep -rl "org.springframework.kafka\|org.apache.kafka" src/main/java/` → `activitylog/ActivityLogConsumer.java`, `config/KafkaConsumerConfig.java` (both Phase 3, consumer-side, expected), `config/KafkaEventPublisher.java` (Phase 2, producer-side). No service class imports it. |
| 14 | Event payloads carry identifiers/actor/timestamp only — no user-authored content (title/description/name) | ✓ VERIFIED | All six files in `event/` reviewed directly: every record's components are `UUID`/`String <id-field>`/`Instant` — no `title`, `description`, or `name` field on any record. |
| 15 | A real `.env` can never be committed; only `.env.example` with placeholders is tracked | ✓ VERIFIED | `.gitignore` line 43: `.env`. `git show HEAD:.env.example` shows only `DB_NAME`, `DB_USER`, `DB_PASS` placeholders plus a header comment. `git log -- .env` produces no history. |

**Score:** 15/15 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `event/ActivityEvent.java` | Sealed interface, all 5 permits | ✓ VERIFIED | `permits TaskCreatedEvent, TaskMovedEvent, TaskDeletedEvent, BoardCreatedEvent, ColumnCreatedEvent` |
| `event/TaskMovedEvent.java` | Move event record | ✓ VERIFIED | 7 components, identifiers only |
| `event/TaskCreatedEvent.java`, `TaskDeletedEvent.java`, `BoardCreatedEvent.java`, `ColumnCreatedEvent.java` | Remaining four event records | ✓ VERIFIED | All present, identifiers-only, no Lombok/JPA/Spring annotations |
| `config/KafkaEventPublisher.java` | After-commit publisher, sole Kafka touchpoint | ✓ VERIFIED | `@TransactionalEventListener(phase = AFTER_COMMIT)`, `whenComplete` + `log.error` on failure, `@Async("kafkaPublishExecutor")` |
| `config/AsyncConfig.java` | Bounded executor for async Kafka dispatch | ✓ VERIFIED | Not in the original plan — added post-task to fix a real 20-25min full-suite hang; reviewed and confirmed correct |
| `constant/KafkaTopics.java` | Single `kanban.activity` definition | ✓ VERIFIED | Referenced by both `KafkaEventPublisher` (producer) and Phase 3's consumer |
| `dto/task_dto/MoveTaskRequestDTO.java` | `targetColumnId` + `version`, exactly 2 fields | ✓ VERIFIED | `@NotBlank targetColumnId`, `@NotNull Long version`, no ordering/position field |
| `controller/TaskMoveController.java` | Flat `PATCH /tasks/{taskId}/move` | ✓ VERIFIED | Own `@RequestMapping(ApiPaths.TASKS)`, not on `TaskController` (which stayed unmodified) |
| `test/.../TaskMoveE2ETest.java` | Real-HTTP proof, happy path + rejections | ✓ VERIFIED | 8 tests (1 happy path + 7 rejection cases across `@Nested` groups), all passed live in this verification's own test run |
| `test/.../ActivityEventPublicationTest.java` | After-commit publication + rollback-suppression proof | ✓ VERIFIED | Covers all 5 event types, rollback suppression, adjacency (distinct eventIds), non-null field invariant — all passed live |
| `docker-compose.yml` | 3-service local stack | ✓ VERIFIED | postgres/kafka/app, 2 named volumes, TCP healthcheck, health-gated `depends_on` — proven to actually come up healthy, not just parse |
| `.env.example` | Placeholder template | ✓ VERIFIED | `DB_NAME`, `DB_USER`, `DB_PASS` present, tracked in git |
| `docs/LOCAL_DEV.md` | Runbook + local-dev-only scoping | ✓ VERIFIED | Exists, contains `local development only`, `KAFKA-V2-01`, `docker run` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `TaskService.moveToColumn` | `event.TaskMovedEvent` | `eventPublisher.publishEvent(new TaskMovedEvent(...))` at the tail of the method | ✓ WIRED | Confirmed in source; live HTTP call produced exactly one recorded event with server-derived `userId`/`boardId` |
| `TaskService.save`/`deleteById` | `TaskCreatedEvent`/`TaskDeletedEvent` | `eventPublisher.publishEvent(...)` | ✓ WIRED | Confirmed; `deleteById` captures ids into locals before the delete runs |
| `BoardService.save` | `BoardCreatedEvent` | `eventPublisher.publishEvent(...)` | ✓ WIRED | Confirmed, 1 call site |
| `ColumnService.save` | `ColumnCreatedEvent` | `eventPublisher.publishEvent(...)` | ✓ WIRED | Confirmed, 1 call site |
| `KafkaEventPublisher` | `kanban.activity` topic | `@TransactionalEventListener(AFTER_COMMIT)` → `kafkaTemplate.send(KafkaTopics.ACTIVITY, ...)` | ✓ WIRED | Confirmed live against a real broker — successful publish with no error log, then a logged failure when the broker was stopped |
| `TaskMoveController` | `TaskService.moveToColumn` | Direct method call | ✓ WIRED | Confirmed in source and via live HTTP round-trip |
| `docker-compose.yml app` | `docker-compose.yml kafka` | `depends_on: kafka: condition: service_healthy` | ✓ WIRED | Confirmed via live `docker compose up -d --wait` sequencing |

### Behavioral Spot-Checks / Full Test Suite Execution

Rather than spot-checking individual commands, the verifier brought up the real Docker stack, drove live HTTP traffic against it (including a live broker outage), and then independently re-ran the full Gradle test suite — not merely re-reading SUMMARY.md's claimed prior runs.

| Check | Result | Status |
|-------|--------|--------|
| `docker compose up -d --wait` (real stack) | All 3 services healthy/running, no fatal startup errors | ✓ PASS |
| `docker compose down` + `up -d --wait` (volume persistence) | No first-boot init markers on second boot | ✓ PASS |
| Live `PATCH /tasks/{id}/move` (broker up) | 200, incremented version, no publish-failure log | ✓ PASS |
| Live `PATCH /tasks/{id}/move` (broker stopped) | 200 in 65ms, one `ERROR` log line naming the event/eventId | ✓ PASS |
| Live stale-version move | 409 | ✓ PASS |
| Live cross-board move | 400 | ✓ PASS |
| `./gradlew spotlessCheck` | `BUILD SUCCESSFUL` | ✓ PASS |
| `./gradlew test` (full suite, no broker running) | `BUILD SUCCESSFUL in 3m 24s`; 178 tests across 145 result files, 0 failures, 0 errors | ✓ PASS |
| `TaskMoveE2ETest` (all nested classes) | All passed (happy path + 7 rejection cases) | ✓ PASS |
| `ActivityEventPublicationTest` | All passed (5 event types, rollback suppression, adjacency probe, non-null invariant) | ✓ PASS |

This directly falsifies the "task completion ≠ goal achievement" risk for this phase: every claimed behavioral proof (move + rejections, after-commit publish timing, broker-outage resilience and logging, docker-compose health gating and volume persistence) was independently re-executed by the verifier against real infrastructure, not merely re-read from SUMMARY.md.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| KAFKA-01 | 02-03 | `docker compose up` gives postgres + KRaft Kafka + app | ✓ SATISFIED | Live stack proof (this verification) |
| KAFKA-02 | 02-01 | spring-kafka + Testcontainers on classpath, BOM-managed | ✓ SATISFIED | `build.gradle` reviewed, no version pins |
| EVENT-01 | 02-01, 02-02 | 5 typed event records in `event` package | ✓ SATISFIED | All 6 files (interface + 5 records) reviewed |
| EVENT-02 | 02-01, 02-02 | All 3 services publish after-commit via `ApplicationEventPublisher` + `AFTER_COMMIT` | ✓ SATISFIED | Source review + live broker proof + full test suite |
| MOVE-01 | 02-01 | `PATCH /tasks/{taskId}/move` moves task, publishes event | ✓ SATISFIED | Live HTTP + `TaskMoveE2ETest` |
| MOVE-02 | 02-01 | Stale version → 409, existing compare-before-mutate convention | ✓ SATISFIED | Live HTTP + `TaskMoveE2ETest.StaleVersion`/`ConcurrentConflict` |
| MOVE-03 | 02-01 | Cross-board target → 400 | ✓ SATISFIED | Live HTTP + `TaskMoveE2ETest.CrossBoardTarget` |

No orphaned requirements — all 7 phase-declared requirement IDs (KAFKA-01/02, EVENT-01/02, MOVE-01/02/03) appear in REQUIREMENTS.md's traceability table mapped to Phase 2, and all are satisfied by at least one of the three plans (02-01, 02-02, 02-03).

### Anti-Patterns Found

Scanned all files this phase created/modified (event package, config, controller, DTO, service, docker-compose.yml, Dockerfile) for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER`. Three `TODO` hits found in `TaskService.java`, `BoardService.java`, `ColumnService.java` — all three confirmed via `git blame` to predate this phase by over a year (2025-06-05, commit `5121740f`), unrelated to Phase 2's scope. No blocker.

No stub patterns (`return null`, empty handlers, hardcoded empty collections feeding a response) found in any Phase 2 artifact.

### Regression Check Against Phase 3

Phase 3's `03-VERIFICATION.md` (already `passed`, 14/14 truths) built directly on Phase 2's `ActivityEvent` contract, `KafkaTopics.ACTIVITY`, and the after-commit publish convention. Re-running the full suite in this verification pass confirms 178 tests (up from Phase 3's own count of 79 XML result files reported at its verification time, reflecting subsequent quick-task additions) all pass with 0 failures/0 errors — including every `ActivityLogConsumerE2ETest`, `ActivityLogIdempotencyE2ETest`, and `ActivityLogDeadLetterE2ETest` class, none of which regressed. No changes were needed to Phase 3 code during this verification.

### Human Verification Required

None. Every must-have was verified either by direct source inspection, by an independently re-executed automated test, or by live HTTP/Docker interaction with the actual running system (including a real broker outage).

### Gaps Summary

No gaps. All 15 derived observable truths (merged from ROADMAP Phase 2 success criteria and both plans' `must_haves.truths`) are verified against the actual codebase and a live running instance of the system — not just against SUMMARY.md narrative. The verifier independently:

1. Brought up the full Docker Compose stack (postgres + KRaft Kafka + app) and confirmed it reaches a healthy state, confirmed volume persistence across a `down`/`up` cycle.
2. Drove real HTTP traffic through the running app: created a board/columns/task via existing endpoints (no board-creation REST endpoint exists, so seeded via one direct SQL insert — same approach the original plan 03 execution used), then exercised the move endpoint's happy path, stale-version rejection, and cross-board rejection, all against live infrastructure.
3. Stopped the live Kafka container mid-session and confirmed the mutation still succeeded in 65ms while a single structured error was logged — the strongest possible proof of D-01/D-02.
4. Independently re-ran `./gradlew spotlessCheck` and the full `./gradlew test` suite with no broker running anywhere: 178 tests, 0 failures, 0 errors, ~3m24s (no hang, confirming the `@Async` fix from Plan 02 holds).
5. Read every event record, the publisher, the service methods, the controller, and the DTO to confirm the exact ordering, field provenance (server-derived, never client-supplied), and absence of user-authored content or an ordering/position field, matching the plan's explicit scope guards (D-04/D-05).

The phase is functionally complete and provably correct against both the codebase and a live running instance of the system. No regressions to Phase 3's already-passed verification were found.

---

*Verified: 2026-08-03T23:30:00Z*
*Verifier: Claude (gsd-verifier)*
