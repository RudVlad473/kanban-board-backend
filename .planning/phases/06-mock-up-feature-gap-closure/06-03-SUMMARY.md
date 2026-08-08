---
phase: 06-mock-up-feature-gap-closure
plan: 03
subsystem: api
tags: [spring-boot, jpa, kafka, avro, schema-registry, rest-assured]

# Dependency graph
requires:
  - phase: 06-01
    provides: V5 Flyway migration and entity foundation (positions, subtask version, theme, board-name uniqueness) that the rest of Phase 6 builds on; this plan did not need any of those fields directly but depended on the phase's shared foundation being landed first
  - phase: 04-schema-registry
    provides: The Avro/Confluent Schema Registry pipeline (AvroSchemaRegistrar, ActivityEventAvroMapper, BACKWARD compatibility) this plan's sixth event type registers into
provides:
  - "DELETE /boards/{boardId}/columns/{columnId} — authenticated, ownership-verified, cascades to tasks/subtasks via the existing batched TaskService.deleteAllByColumn, no non-empty-column guard"
  - "ColumnDeletedEvent — sixth ActivityEvent sealed-interface member with its own AvroColumnDeletedEvent.avsc schema, both ActivityEventAvroMapper switch arms, ActivityAction.COLUMN_DELETED, and an ActivityLogConsumer arm"
  - "AvroSchemaRegistrar.SCHEMAS now registers 6 subjects, not 5"
affects: [06-mock-up-feature-gap-closure]

# Actuals (#2632)
actuals:
  tokens: 46000
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Sixth event type added to the existing sealed-interface + one-.avsc-per-type + exhaustive-switch pattern (Phase 4 D-03) — no new architectural pattern, pure mechanical extension"
    - "Ids needed by an after-cascade event capture into locals before the cascade runs, mirroring TaskService.deleteById's established idiom"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/event/ColumnDeletedEvent.java
    - src/main/avro/AvroColumnDeletedEvent.avsc
    - src/test/java/com/vrudenko/kanban_board/ColumnDeletionE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java
    - src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
    - src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapper.java
    - src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java
    - src/main/java/com/vrudenko/kanban_board/entity/ActivityAction.java
    - src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java
    - src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructor.java
    - src/test/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapperTest.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java

key-decisions:
  - "Task 3's Kafka-backed activity-log proof landed in ActivityLogConsumerE2ETest.java (a new shouldPersistColumnDeleted_withColumnIdDetailAndEventTimestamp test), not ColumnDeletionE2ETest.java as the plan's files_modified list stated — this codebase's real-HTTP E2E tests (AbstractAppE2ETest) and real-Kafka-broker tests (AbstractKafkaContainerTest) are two separate class hierarchies sharing no ancestor besides AbstractPostgresContainerTest, and ActivityReadE2ETest's own Javadoc already documents this split as deliberate (\"the Kafka path itself is already proven end-to-end by [the activitylog package's own E2E classes]\"). Following that existing precedent exactly, rather than merging two incompatible base-class hierarchies inside one file, is the change that matches the codebase's own architecture."
  - "Two pre-existing test-side exhaustive switches (HistoricalActivityEventReconstructor.reconstruct, ActivityEventAvroMapperTest's field-comparison helper) needed a sixth arm the moment ColumnDeletedEvent/COLUMN_DELETED existed — caught by the pre-commit hook's compileTestJava run, fixed in the same commit as Task 2's production code, same pattern as 06-01's SubtaskControllerTest fix"

patterns-established:
  - "A deviation from a plan's stated test-file scope, when the deviation is compelled by the codebase's own already-documented architectural split (not a fresh judgment call), is disposed of by following the existing precedent exactly rather than inventing a workaround"

requirements-completed: [GAP-02]

coverage:
  - id: D1
    description: "An authenticated owner can DELETE one column over HTTP; its tasks and subtasks are gone from the database afterwards, a sibling column and its tasks/subtasks are untouched, empty and non-empty columns both delete cleanly, a foreign column returns 401 and deletes nothing, and an unknown column id returns 404"
    requirement: GAP-02
    verification:
      - kind: e2e
        ref: "ColumnDeletionE2ETest$DeleteById#shouldReturnOkAndCascadeDeleteTasksAndSubtasks_andLeaveSiblingColumnUntouched_whenColumnIsNonEmpty"
        status: pass
      - kind: e2e
        ref: "ColumnDeletionE2ETest$DeleteById#shouldReturnOk_whenColumnIsEmpty"
        status: pass
      - kind: e2e
        ref: "ColumnDeletionE2ETest$DeleteById#shouldReturnUnauthorizedAndDeleteNothing_whenColumnBelongsToAnotherUser"
        status: pass
      - kind: e2e
        ref: "ColumnDeletionE2ETest$DeleteById#shouldReturnNotFound_whenColumnDoesNotExist"
        status: pass
    human_judgment: false
  - id: D2
    description: "ColumnDeletedEvent exists as a sixth ActivityEvent sealed-interface member with its own Avro schema registered at BACKWARD compatibility, both mapper directions round-trip every field, and the five pre-existing event types round-trip unchanged"
    requirement: GAP-02
    verification:
      - kind: integration
        ref: "ActivityEventAvroMapperTest$RoundTripTest#shouldRoundTrip_whenColumnDeletedEvent"
        status: pass
      - kind: integration
        ref: "./gradlew build -x test (both exhaustive switches compile with no default arm added)"
        status: pass
    human_judgment: false
  - id: D3
    description: "A column delete produces exactly one activity_log row via the real broker/schema registry, with the COLUMN_DELETED action, a detail map containing only the deleted column's id, and a timestamp taken from the event rather than the consumer's own clock"
    requirement: GAP-02
    verification:
      - kind: integration
        ref: "ActivityLogConsumerE2ETest$OnActivityEventTest#shouldPersistColumnDeleted_withColumnIdDetailAndEventTimestamp"
        status: pass
    human_judgment: false
  - id: D4
    description: "The tasks/subtasks cascade is batched, not per-task — its statement count does not scale with the number of tasks in the deleted column"
    requirement: GAP-02
    verification:
      - kind: unit
        ref: "ColumnServiceTest$DeleteByIdTest#shouldCostSameQueryCount_regardlessOfTaskCountInColumn"
        status: pass
    human_judgment: false

duration: 45min
completed: 2026-08-08
status: complete
---

# Phase 6 Plan 3: Column Deletion with Cascade and a Sixth Activity Event Summary

**`DELETE /boards/{boardId}/columns/{columnId}` cascades to tasks/subtasks via the existing batched delete and publishes a new `ColumnDeletedEvent` — the sixth Avro-registered, sealed-interface `ActivityEvent` — closing column deletion as the one previously-unlogged mutation in the domain.**

## Performance

- **Duration:** 45 min
- **Started:** 2026-08-08T20:14:16+02:00 (base commit)
- **Completed:** 2026-08-08T20:48:23+02:00 (final task commit)
- **Tasks:** 3 completed
- **Files modified:** 14 (3 created, 11 modified)

## Accomplishments

- `ColumnService.deleteById` replaces the literal `// TODO: implement delete logic` placeholder: verifies ownership, cascades via the existing `TaskService.deleteAllByColumn` (D-05, the exact single-column case of `deleteAllByBoardId`'s own loop), deletes the column row, then publishes `ColumnDeletedEvent`. No non-empty-column guard exists anywhere on this path (D-07).
- `ColumnController` gains a `DELETE` mapping matching `BoardController.deleteById`'s exact shape, composing with the class's existing board-nested route to produce `DELETE /boards/{boardId}/columns/{columnId}`.
- `ColumnDeletedEvent` joins the `ActivityEvent` sealed interface with its own `AvroColumnDeletedEvent.avsc` (byte-identical field types to `AvroTaskDeletedEvent.avsc`, minus `taskId`), both `ActivityEventAvroMapper` switch arms, an `ActivityAction.COLUMN_DELETED` value, and an `ActivityLogConsumer` arm writing a `columnId`-only detail map. `AvroSchemaRegistrar.SCHEMAS` now lists 6 schemas, registered under `RecordNameStrategy`-derived subjects at BACKWARD compatibility.
- `ColumnDeletionE2ETest` proves the full cascade over real HTTP: non-empty-column cascade with explicit sibling-column survival, empty-column success, 401 on a foreign column (nothing deleted), 404 on an unknown id.
- `ColumnServiceTest` proves ownership rejection and — via `AbstractAppTest.countQueries` — that the cascade's statement count is identical for a 2-task and an 8-task column, confirming the batching property `TaskService.deleteAllByColumn` exists to provide.
- `ActivityLogConsumerE2ETest` proves the real end-to-end path: a `ColumnDeletedEvent` sent through the real broker and schema registry lands in `activity_log` with the `COLUMN_DELETED` action, the correct detail JSON, and a timestamp sourced from the event (not the consumer's clock).

## Task Commits

Each task was committed atomically:

1. **Task 1: End-to-end column deletion with cascade — one column, one HTTP call** - `c36adc6` (feat)
2. **Task 2: Add ColumnDeletedEvent across the sealed interface, Avro schema and consumer** - `2661c65` (feat; includes the two pre-existing exhaustive-switch fixes described in Deviations below)
3. **Task 3: Prove the column-delete event reaches the activity log through real Kafka** - `79e5ba9` (test)

**Plan metadata:** this SUMMARY.md's commit (created immediately after this file, per the atomic close-out protocol)

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/event/ColumnDeletedEvent.java` - new sealed-interface member, modeled on `TaskDeletedEvent` minus `taskId`
- `src/main/avro/AvroColumnDeletedEvent.avsc` - new Avro schema, byte-identical field types to `AvroTaskDeletedEvent.avsc` minus `taskId`
- `src/test/java/com/vrudenko/kanban_board/ColumnDeletionE2ETest.java` - new HTTP E2E tracer, 4 test methods
- `src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java` - `+ DELETE /{columnId}` mapping
- `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java` - `+ deleteById`, replacing the TODO
- `src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java` - `+ ColumnDeletedEvent` in `permits`
- `src/main/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapper.java` - `+` both switch arms
- `src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java` - `+` sixth schema in `SCHEMAS`, doc counts updated
- `src/main/java/com/vrudenko/kanban_board/entity/ActivityAction.java` - `+ COLUMN_DELETED`
- `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java` - `+` switch arm, columnId-only detail
- `src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java` - `+ DeleteByIdTest` nested class, 3 test methods
- `src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructor.java` - `+ COLUMN_DELETED` switch arm (deviation)
- `src/test/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapperTest.java` - `+` round-trip test and switch arm (deviation)
- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java` - `+ shouldPersistColumnDeleted_withColumnIdDetailAndEventTimestamp` (Task 3's Kafka proof, relocated per the deviation below)

## Decisions Made

- Task 1 deliberately does not capture `boardId`/`columnId` into locals or publish anything — that's Task 2's job, keeping the cascade provable in isolation from Kafka, exactly as the plan specified.
- Task 2's id-capture-before-cascade in `ColumnService.deleteById` mirrors `TaskService.deleteById`'s already-documented rationale verbatim (nothing survives to derive `boardId` from once the column row is gone).
- The Kafka-backed activity-log proof (Task 3) was added to `ActivityLogConsumerE2ETest.java` rather than `ColumnDeletionE2ETest.java` — see Deviations below.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Widened two pre-existing exhaustive switches the moment the sixth event/action existed**

- **Found during:** Task 2 (adding `ColumnDeletedEvent`/`ActivityAction.COLUMN_DELETED`), surfaced by the pre-commit hook's `compileTestJava` run
- **Issue:** `HistoricalActivityEventReconstructor.reconstruct` (a test-only `ActivityAction` switch, not in this plan's stated file list) and `ActivityEventAvroMapperTest`'s field-comparison helper (an `ActivityEvent` switch) both stopped compiling — both are exhaustive by design, mirroring `ActivityLogConsumer.deriveActionAndDetailIds`/`ActivityEventAvroMapper.toAvro`, and neither carries a `default` arm
- **Fix:** Added a `COLUMN_DELETED`/`ColumnDeletedEvent` arm to each, plus a new `shouldRoundTrip_whenColumnDeletedEvent` test in `ActivityEventAvroMapperTest` exercising the new arm
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructor.java`, `src/test/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapperTest.java`
- **Verification:** `./gradlew spotlessApply spotlessCheck build -x test` green; reconfirmed by the full `./gradlew test` run
- **Committed in:** `2661c65` (Task 2 commit, reason spelled out in the commit message)

**2. [Rule 1 - Bug/plan imprecision, no fix needed] `AvroSchemaRegistrar`'s `getClassSchema` acceptance-criterion grep**

- **Found during:** Task 2's acceptance-criteria verification
- **Issue:** The plan's acceptance criterion `grep -c "getClassSchema" AvroSchemaRegistrar.java` returns 6 assumed the string appears once per `SCHEMAS` entry only; the class also carries one pre-existing Javadoc line quoting `{@code getClassSchema()}` in prose, so the actual (correct) count is 7
- **Fix:** None needed — the real invariant (`SCHEMAS` lists exactly 6 `getClassSchema()` calls) is satisfied and independently confirmed by `ls src/main/avro/*.avsc` returning 6 and the full build/test suite passing
- **Files modified:** none
- **Verification:** Manual line-by-line inspection of the file; `./gradlew build -x test` green
- **Committed in:** n/a — no code change, documented here for audit-trail completeness

### File-scope Deviation

**3. Task 3's Kafka-backed activity-log proof landed in `ActivityLogConsumerE2ETest.java`, not `ColumnDeletionE2ETest.java`**

- **Found during:** Task 3, while designing the Kafka-backed assertion the plan's `<action>` describes
- **Issue:** The plan's `files_modified` names `ColumnDeletionE2ETest.java` for this assertion, but that class extends `AbstractAppE2ETest` (real HTTP, `RANDOM_PORT`) while a real broker/schema-registry round trip requires `AbstractKafkaContainerTest` — two hierarchies sharing no common ancestor except `AbstractPostgresContainerTest`. This split is not incidental: `ActivityReadE2ETest`'s own Javadoc documents it as deliberate ("Rows are seeded directly through `ActivityLogRepository` rather than published through Kafka: this suite needs no broker... The Kafka path itself is already proven end-to-end by [the activitylog package's own E2E classes]").
- **Fix:** Added `shouldPersistColumnDeleted_withColumnIdDetailAndEventTimestamp` to `ActivityLogConsumerE2ETest`, mirroring its five sibling per-event-type tests exactly (same `sendAndAwaitAck`/`Awaitility` idiom, same `findByEventId` helper), plus a millisecond-tolerance timestamp assertion proving the row's `createdAt` comes from the event, not the consumer's clock.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java` (in place of `ColumnDeletionE2ETest.java` for this specific assertion)
- **Verification:** `./gradlew spotlessCheck test` — full suite green, 0 failures, 0 errors; `git diff --stat` on `ActivityLogConsumerE2ETest.java` shows pure additions (36 insertions, 0 deletions) — no existing assertion touched
- **Committed in:** `79e5ba9` (Task 3 commit, full rationale in the commit message)

---

**Total deviations:** 3 (1 auto-fixed blocking widening, 1 confirmed-no-fix-needed plan imprecision, 1 file-scope deviation compelled by existing codebase architecture)
**Impact on plan:** No scope creep — every deviation either kept a genuinely blocking compile error out of the commit or followed an already-established codebase precedent instead of inventing a new one. All of GAP-02's must-haves are met.

## Issues Encountered

None beyond the deviations documented above.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `DELETE /boards/{boardId}/columns/{columnId}` (GAP-02) is fully closed: cascade, event, Avro schema, consumer arm, and all six event types proven to round-trip together.
- `docs/MOCKUP_FEATURE_GAP.md` §1.2's column-delete gap is resolved.
- No blockers or concerns carried forward — the sixth event type is additive to the schema registry (a brand-new subject), so the five pre-existing subjects' BACKWARD compatibility is unaffected, confirmed by the unmodified pre-existing round-trip tests still passing.

---
*Phase: 06-mock-up-feature-gap-closure*
*Completed: 2026-08-08*

## Self-Check: PASSED

- Verified `[ -f]` on all three `key-files.created` paths: present.
- `git log --oneline --all --grep="06-03"` returns 4 commits (3 task commits above; this SUMMARY commit will be the 4th).
- Re-ran task-level `<acceptance_criteria>` and plan-level `<verification>` commands during execution — all passed except the one documented, non-functional grep-count discrepancy (Deviation 2).
- Full `./gradlew spotlessCheck test` reconfirmed green (0 failures, 0 errors) immediately before writing this SUMMARY.
