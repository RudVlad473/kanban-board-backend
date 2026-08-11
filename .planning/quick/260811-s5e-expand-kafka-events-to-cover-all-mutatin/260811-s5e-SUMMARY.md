---
phase: quick-260811-s5e
plan: 01
subsystem: api
tags: [kafka, avro, schema-registry, event-driven, activity-log, spring-events]

requires: []
provides:
  - "8 new ActivityEvent types (BoardUpdated, BoardDeleted, ColumnUpdated, ColumnReordered, TaskUpdated, SubtaskCreated, SubtaskUpdated, SubtaskDeleted), bringing kanban.activity to 14 total event types / 14 Avro subjects"
  - "SubtaskService now publishes events (previously published nothing at all) — create, update, delete all reach activity_log"
  - "260811-s5e-FINDINGS.md: full mutating-operation matrix, cascade-path inventory, Avro-subject evidence, compile-time safety-net map, pre-existing-defect register, and Task 5's suite-impact measurement"
  - "3 new filed todos (TaskMovedEvent position asymmetry, unbounded activity_log growth, dead deleteAllByColumnId) plus a factual correction note on the sibling d-02 todo"
affects: [any future ActivityEvent addition, Phase 5 Infra Migration's schema-registry cutover, whoever picks up the 3 new todos or the d-02 BACKWARD-vs-BACKWARD_TRANSITIVE decision]

actuals:
  tokens: 26000
  tasks: 6
  commits: 4

tech-stack:
  added: []
  patterns:
    - "New ActivityEvent types under RecordNameStrategy are always new subjects at version 1 -- never modify an existing .avsc unless a fork explicitly calls for evolving it"
    - "Non-opaque detail values (ints, booleans) are stringified on write (ActivityLogConsumer) and parsed back with the exact inverse conversion on read (HistoricalActivityEventReconstructor) -- ColumnReorderedEvent's positions and SubtaskUpdatedEvent's isCompleted are the first two examples"
    - "Every cascade-delete path gets an explicit Javadoc note when it deliberately publishes nothing, so a future reader does not read the silence as an oversight"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/event/SubtaskCreatedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/BoardUpdatedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/BoardDeletedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/ColumnUpdatedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/ColumnReorderedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/TaskUpdatedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/SubtaskUpdatedEvent.java
    - src/main/java/com/vrudenko/kanban_board/event/SubtaskDeletedEvent.java
    - src/main/avro/AvroSubtaskCreatedEvent.avsc
    - src/main/avro/AvroBoardUpdatedEvent.avsc
    - src/main/avro/AvroBoardDeletedEvent.avsc
    - src/main/avro/AvroColumnUpdatedEvent.avsc
    - src/main/avro/AvroColumnReorderedEvent.avsc
    - src/main/avro/AvroTaskUpdatedEvent.avsc
    - src/main/avro/AvroSubtaskUpdatedEvent.avsc
    - src/main/avro/AvroSubtaskDeletedEvent.avsc
    - .planning/quick/260811-s5e-expand-kafka-events-to-cover-all-mutatin/260811-s5e-FINDINGS.md
    - .planning/todos/pending/2026-08-11-taskmovedevent-position-asymmetry-not-fixed-in-s5e-fork-d-e.md
    - .planning/todos/pending/2026-08-11-unbounded-activity-log-growth-rate-materially-accelerated-by-s.md
    - .planning/todos/pending/2026-08-11-taskservice-deleteallbycolumnid-has-zero-production-callers.md
  modified:
    - src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java
    - src/main/java/com/vrudenko/kanban_board/entity/ActivityAction.java
    - src/main/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapper.java
    - src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java
    - src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java
    - src/main/java/com/vrudenko/kanban_board/service/BoardService.java
    - src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
    - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
    - src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java
    - src/test/java/com/vrudenko/kanban_board/event/ActivityEventPublicationTest.java
    - src/test/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapperTest.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructor.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/SchemaCompatibilityE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/e2e/activity/ActivityReadTest.java
    - src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppTest.java
    - build.gradle
    - docs/ARCHITECTURE.md
    - .planning/codebase/TESTING.md
    - .planning/todos/pending/2026-08-06-d-02-backward-non-transitive-vs-replay-from-zero.md
    - .planning/todos/completed/2026-08-09-expand-kafka-events-to-cover-all-mutating-changes-on-board-c.md (moved from pending/)
    - .planning/STATE.md

key-decisions:
  - "Approach A (audit -> blocking-gate -> tracer -> horizontal-expand) over an autonomous single pass -- the source todo's own 'Solution: TBD' meant guessing at 5 genuine design forks risked baking a wrong ActivityAction value into a permanent, append-only, never-migrated table"
  - "SubtaskCreatedEvent chosen as the tracer specifically because SubtaskService published nothing at all -- proved the full 10-touchpoint chain, including the two silent failure points (ActivityEventAvroMapper.toDomain's default arm, AvroSchemaRegistrar.SCHEMAS), before committing 7 more event types on top of an unproven assumption"
  - "Flagged a factual discrepancy at the Task 2 gate rather than silently absorbing it: git log --diff-filter=M -- src/main/avro/ does NOT return nothing, contradicting the sibling d-02 todo's own premise. Operator confirmed all five forks as 'all recommended' after independent review, and directed a correction note (not a resolution) on d-02"
  - "All five design forks resolved by the operator, not guessed: D-A dedicated ColumnReorderedEvent with positions (A1); D-B SubtaskUpdatedEvent with a server-derived isCompleted boolean (B2); D-C identifiers-only on *Updated events (C1); D-D cascade deletes stay silent, matching empirically-confirmed precedent (D1); D-E TaskMovedEvent's position asymmetry deferred to keep this task additive-subjects-only (E1)"
  - "Converted the 3 shouldPublishNothing_when* tests to positive assertions inside Task 4, not deferred to Task 5 as the plan's task boundary implied -- the pre-commit hook's fastTest gate would otherwise block the commit outright once the publish sites were wired"
  - "Measured suite impact rather than assumed: 398->417 tests (+19, zero shrinkage), +11.7% wall-clock, fully explained by more tests/more publishes/3 new real-broker E2E tests -- kafkaPublishExecutor did not saturate in either run, so no mitigation was applied"

patterns-established:
  - "A cascade-delete method that deliberately publishes nothing gets a Javadoc note naming the fork that decided it and pointing at the parent event it defers to -- not left silent for a future reader to mistake for an oversight"

requirements-completed: [S5E-01, S5E-02, S5E-03, S5E-04, S5E-05, S5E-06]

coverage:
  - id: D1
    description: "Every mutating endpoint on board/column/task/subtask either publishes an ActivityEvent or is recorded in FINDINGS.md with a written reason why it does not"
    requirement: "S5E-01"
    verification:
      - kind: unit
        ref: ".planning/quick/260811-s5e-expand-kafka-events-to-cover-all-mutatin/260811-s5e-FINDINGS.md#Section-1"
        status: pass
    human_judgment: false
  - id: D2
    description: "SubtaskService publishes create, update and delete events that reach activity_log end-to-end through a real broker and registry"
    requirement: "S5E-02"
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java#OnActivityEventTest.shouldPersistSubtaskCreated_withTaskIdThenSubtaskIdDetail"
        status: pass
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/event/ActivityEventPublicationTest.java#SubtaskUpdateAndDeleteTest"
        status: pass
    human_judgment: false
  - id: D3
    description: "Board update/delete, column rename/reorder and task update each produce an activity_log row with a distinct ActivityAction"
    requirement: "S5E-03"
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java#OnActivityEventTest (BoardUpdated, ColumnReordered, TaskUpdated)"
        status: pass
    human_judgment: false
  - id: D4
    description: "No published event, schema or detail payload carries user-authored text; every new publish site derives ids from the ownership-verified entity"
    requirement: "S5E-04"
    verification:
      - kind: other
        ref: "Code review across all 8 new event records/publish sites -- no name/title/description field anywhere; grep confirms no such field in any new .avsc"
        status: pass
    human_judgment: true
    rationale: "Absence of a field across 8 new records/schemas is a code-review property, not something a single automated check certifies end-to-end"
  - id: D5
    description: "The sealed-interface compile-time safety net is preserved -- adding a future event type is still a compile error until all four switches are updated"
    requirement: "S5E-05"
    verification:
      - kind: unit
        ref: "Demonstrated live: adding SubtaskCreatedEvent to ActivityEvent's permits clause broke ActivityEventAvroMapper.toAvro and ActivityLogConsumer.deriveActionAndDetailIds compilation until both were updated (Task 3)"
        status: pass
    human_judgment: false
  - id: D6
    description: "spotlessCheck and the full test suite pass, zero test shrinkage, and any wall-clock regression beyond the documented ~18s variance is explained or mitigated"
    requirement: "S5E-06"
    verification:
      - kind: other
        ref: "260811-s5e-FINDINGS.md#Task-5-Suite-impact-measurement -- 398->417 tests, 368s->411s, 0 failures in either run"
        status: pass
    human_judgment: false

duration: ~2h 15min (Task 1 audit + gate, Tasks 3-6 implementation)
completed: 2026-08-11
status: complete
---

# Quick Task 260811-s5e: Expand Kafka Events to Cover All Mutating Changes Summary

**Expanded the `kanban.activity` Kafka pipeline from 6 to 14 event types via an audit-then-gate-then-tracer-then-expand plan, closing the gap where `SubtaskService` published nothing at all and Board/Column/Task updates/deletes were invisible to the activity log — every one of the five genuine design forks the source todo left as "TBD" was decided by the operator at a blocking gate, not guessed.**

## Performance

- **Duration:** ~2h 15min total (Task 1 audit ~40min, Task 2 gate resolved by operator within the same turn, Tasks 3-6 implementation ~1h 35min)
- **Completed:** 2026-08-11
- **Tasks:** 6 (audit, blocking gate, tracer, horizontal expand, test-coverage sweep, doc/todo closure)
- **Files modified:** 33 (16 new files, 17 modified, including `.planning/` artifacts)

## Accomplishments

- Audited the full mutating surface (Task 1) directly against `src/main`/`src/test`, verified every planning-time assumption by file:line rather than trusting it, and empirically re-confirmed the load-bearing `RecordNameStrategy`/new-subject claim — while also surfacing that `git log --diff-filter=M -- src/main/avro/` does NOT return nothing, contradicting the sibling d-02 todo's premise; flagged this at the gate rather than silently reasoning past it
- Drove `SubtaskCreatedEvent` through all ten touchpoints (domain record, Avro schema, `ActivityAction`, both mapper switches, consumer switch, registrar list, subject list, publish site) as a tracer, proving the architecture end-to-end via a real Redpanda broker and registry before expanding to 7 more event types
- Added `BoardUpdatedEvent`, `BoardDeletedEvent`, `ColumnUpdatedEvent`, `ColumnReorderedEvent`, `TaskUpdatedEvent`, `SubtaskUpdatedEvent`, `SubtaskDeletedEvent` — every publish site derives its ids from the ownership-verified entity or pre-delete-captured locals (CODE_STYLE rule 2), and every publish happens after its method's guard, so a rejected mutation publishes nothing (proven by a new stale-version negative test)
- `ColumnReorderedEvent` and `SubtaskUpdatedEvent` carry this codebase's first non-opaque-identifier detail values (stringified ints, a stringified boolean) — proven not just in the in-memory Avro mapper but through a real broker/registry round trip
- Every cascade-delete path (`ColumnService.deleteAllByBoardId`, `TaskService.deleteAllByColumn`, `SubtaskService.deleteAllByTaskId`/`deleteAllByTaskIds`) stays deliberately silent per fork D-D, now documented with a Javadoc note explaining why rather than left to read as an oversight
- Fixed `RecordingActivityEventListener`'s O(n²) accumulation risk (flagged in the plan's own tradeoff analysis) by clearing it in `AbstractAppTest.cleanup()`, verified safe (not assumed) by confirming the listener carries no `@Async`
- Measured the full suite before and after: 398→417 tests (+19, zero shrinkage), 368s→411s (+11.7%, exceeds the documented ~18s variance but fully explained by more tests and more publish volume) — `kafkaPublishExecutor` did not saturate in either run
- Closed the source todo with a resolution note covering all five forks, the RecordNameStrategy finding, and the measured suite impact; filed 3 new todos for deferred findings plus a factual correction note on the d-02 todo (not a resolution — kept the scope boundary clean per the operator's explicit instruction)

## Task Commits

1. **Task 3 (tracer — SubtaskCreatedEvent end-to-end):** `1d06f63` (`feat`)
2. **Task 4 (expand — remaining 7 event types + publish sites):** `d078bf2` (`feat`)
3. **Task 5 (test-coverage sweep, recorder-clear fix):** `000f889` (`test`)
4. **Task 6 (docs/todo reconciliation, STATE.md, close source todo):** `c15a434` (`docs`)

## Files Created/Modified

See `key-files` in frontmatter for the full list. Highlights: 8 new `ActivityEvent` records + 8 new `.avsc` schemas; 4 production files touched at every new-event-type addition (`ActivityEvent`, `ActivityAction`, `ActivityEventAvroMapper`, `ActivityLogConsumer`); 4 services (`BoardService`, `ColumnService`, `TaskService`, `SubtaskService`) gained new publish sites; `docs/ARCHITECTURE.md` and `.planning/codebase/TESTING.md` corrected from stale "5"/"6"/"two calls" claims to the current "14"/"three calls" reality.

## Decisions Made

See `key-decisions` in frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `AvroColumnDeletedEvent` missing from `SchemaCompatibilityE2ETest.productionSubjects()`**
- **Found during:** Task 1's audit (pre-existing, not introduced by this quick task)
- **Issue:** `productionSubjects()` listed 5 of the then-6 production subjects — `AvroColumnDeletedEvent`'s BACKWARD compatibility was never actually asserted.
- **Fix:** Added it while touching this method for the tracer's new subject anyway (Task 3).
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/activitylog/SchemaCompatibilityE2ETest.java`
- **Committed in:** `1d06f63`

**2. [Rule 1 - Bug] `build.gradle`'s `registerSchemas` description and several Javadocs stale at "5"/"6" schemas**
- **Found during:** Task 1's audit (pre-existing)
- **Issue:** `build.gradle` said "5 Avro schemas" when 6 already existed; `ActivityEventAvroMapper`'s Javadoc said "5 unrelated record shapes" when it was already 6.
- **Fix:** Corrected progressively as each task added schemas (5→6 in the tracer, 6→7 mid-expand where relevant, final state 14 everywhere).
- **Files modified:** `build.gradle`, `src/main/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapper.java`, `src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java`
- **Committed in:** `1d06f63`, `d078bf2`

**3. [Rule 3 - blocking] Converting the 3 `shouldPublishNothing_when*` tests to positive assertions had to happen in Task 4, not Task 5**
- **Found during:** Task 4, wiring `BoardService.updateById`/`ColumnService.updateById`/`TaskService.updateById`'s publish calls
- **Issue:** The plan's own task boundary assigned this test rewrite to Task 5, but the pre-commit hook's `fastTest` gate runs on every commit — once the publish sites were wired, the existing `shouldPublishNothing_when*` tests would genuinely fail, blocking Task 4's own commit.
- **Fix:** Converted all three to positive assertions inside Task 4, documented as a deviation in that commit's message. Task 5 still owned the broader coverage sweep (per-domain E2E, negative case, recorder-clear fix, duration measurement) as planned.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/event/ActivityEventPublicationTest.java`
- **Committed in:** `d078bf2`

---

**Total deviations:** 3 (2 pre-existing-defect fixes folded into the tracer/expand commits per the plan's own instruction, 1 task-boundary adjustment forced by the pre-commit hook's fastTest gate).
**Impact on plan:** None on substance — all three are exactly the kind of mechanical fix Rules 1/3 authorize, and the task-boundary shift was disclosed in the relevant commit message rather than silently absorbed.

## Issues Encountered

- **Suite measurement required a temporary detached-HEAD checkout.** To get a genuine before/after comparison for Task 5's wall-clock measurement, checked out the pre-Task-3 commit (`b678b3f`) in a detached HEAD state, ran the full suite, then returned to `master` (`git checkout master`). No commits were made while detached; `master`'s branch ref was never moved, confirmed via `git rev-parse master` before and after. No destructive git operation was used at any point.
- **Pre-commit hook timeouts.** Each of the four commits ran `spotlessApply` + `fastTest` (~4-5 min) inside the hook; commits were backgrounded and polled rather than blocking on a short Bash timeout, matching the pattern documented in prior sessions' summaries (e.g. 260811-qru).

Neither issue required a destructive git operation, weakened verification, or changed this quick task's actual findings or implementation.

## Known Stubs

None.

## Threat Flags

None beyond what the plan's own `<threat_model>` already covers (T-S5E-01 through T-S5E-06, T-S5E-SC) — every threat there was disposed within this quick task's own scope: T-S5E-01 (information disclosure) mitigated by the no-user-authored-content rule holding across all 8 new event types (code-reviewed, D4 above); T-S5E-02 (spoofing/EoP) mitigated by every publish site deriving ids from the ownership-verified entity; T-S5E-03 (DoS via executor saturation) mitigated by measurement, not assumption (Task 5); T-S5E-04 (repudiation, cascade silence) accepted per D-D's resolution; T-S5E-05 (schema tampering) mitigated by the RecordNameStrategy/additive-subjects-only property, empirically verified; T-S5E-06 (activity_log unbounded growth) accepted and filed as a todo, not silently absorbed; T-S5E-SC (supply chain) confirmed n/a — no new dependency introduced.

## User Setup Required

None — no external service configuration required. The existing local Redpanda/Testcontainers setup already covers every new event type's E2E proof.

## Next Phase Readiness

- The `kanban.activity` pipeline is now feature-complete for the current REST surface: every mutating board/column/task/subtask operation either publishes an event or is a documented, deliberate exception (theme update — no `boardId`).
- 3 new todos are filed for deferred findings; none block Phase 5 (Infra Migration) — the `TaskMovedEvent` position asymmetry and the `activity_log` retention question are both independent of the schema-registry cutover Phase 5 performs.
- The d-02 todo's actual open question (BACKWARD vs. BACKWARD_TRANSITIVE) remains genuinely unresolved — its correction note only updates supporting evidence, not the decision itself. Worth resolving before Phase 5 repoints `schema.registry.url` at the production registry, per that todo's own existing guidance.
- No blockers for subsequent phase work; this was a standalone quick task.

---
*Quick task: 260811-s5e*
*Completed: 2026-08-11*
