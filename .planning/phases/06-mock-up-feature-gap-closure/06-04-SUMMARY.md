---
phase: 06-mock-up-feature-gap-closure
plan: 04
subsystem: api
tags: [spring-boot, jpa, ordering, bulk-update, rest-assured]

# Dependency graph
requires:
  - phase: 06-01
    provides: V5 Flyway migration and entity foundation (TaskEntity.position, ColumnEntity.position already landed as inert `= 0`-initialised fields)
  - phase: 06-03
    provides: ColumnService.deleteById (D-05/D-06/D-07 column deletion cascade) that this plan's delete-gap-closing step extends
provides:
  - "Task position ordering — TaskEntity.position actively maintained on create (append-at-end) and move (targetPosition), via a single bulk column-scoped shift statement per side (source gap-close, destination slot-open)"
  - "Column position ordering — ColumnEntity.position actively maintained on create, a new PATCH /boards/{boardId}/columns/{columnId}/reorder route, and delete-time gap-closing so a deleted column never leaves a permanent hole"
  - "MoveTaskRequestDTO.targetPosition (nullable, D-04) — task move and task reorder are one endpoint, not two"
  - "ReorderColumnRequestDTO (mandatory targetPosition) — columns get their own reorder route since UpdateColumnRequestDTO has no position-shaped field to extend"
  - "TaskResponseDTO.position and ColumnResponseDTO.position exposed; TaskRepository.findAllByColumnId and ColumnRepository.findAllByBoardId now sort by (position, id) as a genuine total order"
affects: [06-05, 06-06, 06-07]

# Actuals (#2632)
actuals:
  tokens: 92000
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Bulk column/board-scoped @Modifying @Query position shift (signed delta over an inclusive [from, to] range) — one statement per side of a move/reorder, mirroring SubtaskRepository.deleteAllByTaskIdIn's stated preference for bulk JPQL over per-row loops"
    - "Same-column reorder computed as a single signed range shift strictly between old and new position (never touching the moved row's own pre-shift position), rather than two composed cross-column shifts, to avoid double-shifting"
    - "Existing derived-query repository methods reused as the 'next position' probe (countByColumnId / countByBoardId) instead of adding a dedicated max-position query"
    - "Ordered reads land via an explicit @Query with an inline order-by clause rather than a derived-name rename, so no existing call site needed updating"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/TaskOrderingE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/ColumnOrderingE2ETest.java
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ReorderColumnRequestDTO.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/repository/TaskRepository.java
    - src/main/java/com/vrudenko/kanban_board/repository/ColumnRepository.java
    - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
    - src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
    - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java
    - src/main/java/com/vrudenko/kanban_board/dto/task_dto/MoveTaskRequestDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/task_dto/TaskResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java
    - src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java
    - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
    - src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java

key-decisions:
  - "Reused TaskRepository.countByColumnId / ColumnRepository.countByBoardId as the 'next position' probe rather than adding a dedicated max-position query — positions are kept contiguous from zero by every mutation, so 'current sibling count' and 'next append-at-end index' are the same number"
  - "Same-column moveToColumn/reorder is one signed range shift between old and new position (not two composed cross-column shifts) to avoid double-shifting — the single most likely defect the plan flagged, driven test-first via the same-column TaskOrderingE2ETest case"
  - "Ordered reads (findAllByColumnId / findAllByBoardId) kept their existing method names via an explicit @Query with an inline order-by, instead of a derived-name rename, so zero existing call sites needed changes"

patterns-established:
  - "A total-order sibling read is (position ASC, id ASC), with the ULID id as tiebreak, matching the activity feed's existing (createdAt, id) precedent from Phase 3 Plan 03"

requirements-completed: [GAP-03]

coverage:
  - id: D1
    description: "Creating three tasks in an empty column yields positions 0, 1, 2 in creation order"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "TaskOrderingE2ETest$TaskCreation#shouldAssignContiguousPositions_whenCreatingThreeTasksInEmptyColumn"
        status: pass
    human_judgment: false
  - id: D2
    description: "Moving a task to position 0 of its own column puts it first and shifts every previously-preceding sibling down by exactly one, with no gaps and no duplicates"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "TaskOrderingE2ETest$MoveToColumn#shouldMoveThirdTaskToFront_andShiftOthersDown_whenTargetPositionIsZeroInSameColumn"
        status: pass
    human_judgment: false
  - id: D3
    description: "Moving a task to another column at a given position removes it from the source column's sequence, closes the gap it left, and opens a slot at the target position in the destination column"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "TaskOrderingE2ETest$MoveToColumn#shouldLeaveBothColumnsContiguous_whenMovingTaskToDifferentColumnAtPositionZero"
        status: pass
    human_judgment: false
  - id: D4
    description: "A move request that omits targetPosition appends the task at the end of the target column, preserving pre-existing move behaviour for clients that never send the new field; the pre-existing TaskMoveE2ETest suite passes unmodified"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "TaskOrderingE2ETest$MoveToColumn#shouldAppendAtEnd_whenTargetPositionIsOmitted"
        status: pass
      - kind: e2e
        ref: "TaskMoveE2ETest (pre-existing suite, unmodified)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Reordering a column within its board renumbers its siblings the same way, and tasks inside those columns are untouched"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "ColumnOrderingE2ETest$Reorder#shouldMoveThirdColumnToFront_andShiftOthersDown_whenTargetPositionIsZero"
        status: pass
      - kind: e2e
        ref: "ColumnOrderingE2ETest$Reorder#shouldNotChangeAnyTaskPosition_whenReorderingAColumn"
        status: pass
    human_judgment: false
  - id: D6
    description: "Every read that returns sibling tasks or sibling columns returns them in a stable, total order that does not depend on database row order"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "TaskOrderingE2ETest$MoveToColumn#shouldReturnSameOrderTwice_whenReadingSameColumnRepeatedly"
        status: pass
      - kind: e2e
        ref: "TaskOrderingE2ETest$MoveToColumn#shouldReturnTasksSortedByPosition_overHttp"
        status: pass
    human_judgment: false
  - id: D7
    description: "A move or reorder carrying a stale version returns 409 before any renumbering statement runs, leaving positions unchanged"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "TaskOrderingE2ETest$MoveToColumn#shouldReturnConflict_andLeavePositionsUnchanged_whenVersionIsStale"
        status: pass
      - kind: e2e
        ref: "ColumnOrderingE2ETest$Reorder#shouldReturnConflict_andLeavePositionsUnchanged_whenVersionIsStale"
        status: pass
    human_judgment: false
  - id: D8
    description: "A negative targetPosition is rejected by bean validation with 400, and a targetPosition beyond the sibling count is clamped to the end rather than creating a gap"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "TaskOrderingE2ETest$MoveToColumn#shouldReturnBadRequest_whenTargetPositionIsNegative"
        status: pass
      - kind: e2e
        ref: "TaskOrderingE2ETest$MoveToColumn#shouldClampToEnd_whenTargetPositionExceedsDestinationSize"
        status: pass
      - kind: e2e
        ref: "ColumnOrderingE2ETest$Reorder#shouldReturnBadRequest_whenTargetPositionIsNegative"
        status: pass
      - kind: e2e
        ref: "ColumnOrderingE2ETest$Reorder#shouldClampToEnd_whenTargetPositionExceedsBoardColumnCount"
        status: pass
    human_judgment: false
  - id: D9
    description: "Deleting a column leaves the surviving columns' positions contiguous from zero — no permanent hole"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "ColumnOrderingE2ETest$DeleteById#shouldLeaveSurvivingColumnsContiguousFromZero_whenDeletingAMiddleColumn"
        status: pass
    human_judgment: false
  - id: D10
    description: "A move's statement count is bounded and constant, not scaling with the number of siblings in the source column"
    requirement: GAP-03
    verification:
      - kind: unit
        ref: "TaskServiceTest$MoveToColumnQueryCountTest#queryCountDoesNotScaleWithSourceColumnSize"
        status: pass
    human_judgment: false
  - id: D11
    description: "The move endpoint stayed a single endpoint (D-04) — TaskMoveController still has exactly one @PatchMapping method, and a reorder of a column on another user's board returns 401"
    requirement: GAP-03
    verification:
      - kind: e2e
        ref: "ColumnOrderingE2ETest$Reorder#shouldReturnUnauthorized_whenColumnBelongsToAnotherUsersBoard"
        status: pass
      - kind: other
        ref: "manual inspection — TaskMoveController.java untouched by this plan, exactly one @PatchMapping method"
        status: pass
    human_judgment: false

duration: 95min
completed: 2026-08-08
status: complete
---

# Phase 6 Plan 4: Task and Column Position Ordering Summary

**`Integer position` on both `TaskEntity` and `ColumnEntity`, maintained by renumber-on-insert via single bulk column/board-scoped shift statements, with task move and task reorder collapsed into the existing move endpoint's new `targetPosition` field (D-04) and a new `PATCH /boards/{boardId}/columns/{columnId}/reorder` route for columns.**

## Performance

- **Duration:** ~95 min (from wave-2 base commit `d2502be` to final gate)
- **Started:** 2026-08-08T21:05:00+02:00 (approx.)
- **Completed:** 2026-08-08T22:20:00+02:00 (approx.)
- **Tasks:** 3 completed
- **Files modified:** 14 (3 created, 11 modified)

## Accomplishments

- `TaskRepository.shiftPositions` and `ColumnRepository.shiftPositions`: one bulk `@Modifying @Query` signed-range shift each, mandatory `column.id`/`board.id` predicate, mirroring `SubtaskRepository.deleteAllByTaskIdIn`'s stated preference for bulk JPQL over per-row loops.
- `TaskService.save`/`ColumnService.save` assign the next append-at-end position via the already-existing `countByColumnId`/`countByBoardId` methods, superseding the entity's inert `= 0` initialiser from plan 01.
- `TaskService.moveToColumn` extended (not duplicated — D-04) with the full renumbering contract: resolve effective target position (null → append, over-large → clamp), close the source gap, open the destination slot, with the same-column case computed as one signed range shift rather than two composed cross-column shifts.
- `ColumnService.reorder` (new) mirrors `updateById`'s version-guarded mutation shape exactly: load, compare version before any renumbering statement runs, shift, save, flush.
- `ColumnService.deleteById` (plan 03's route) now closes the gap a deleted column leaves, scoped to its board.
- `TaskResponseDTO.position`/`ColumnResponseDTO.position` exposed; `findAllByColumnId`/`findAllByBoardId` sort by `(position ASC, id ASC)` via an explicit `@Query`, preserving their method names so no existing call site needed a change.
- `TaskOrderingE2ETest` (10 tests) and `ColumnOrderingE2ETest` (8 tests) prove every must-have over real HTTP; `TaskServiceTest$MoveToColumnQueryCountTest` proves a move's statement count is independent of sibling count; the pre-existing `TaskMoveE2ETest` suite passes unmodified, proving `targetPosition` stayed optional.

## Task Commits

Each task was committed atomically:

1. **Task 1: End-to-end task ordering — create assigns a position, move places at one** - `bd61e2a` (feat)
2. **Task 2: Column ordering and the column-reorder endpoint** - `72b0d33` (feat)
3. **Task 3: Expose position on responses and make every sibling read a total order** - `3cd5e99` (feat)

**Plan metadata:** committed alongside this SUMMARY.md

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/repository/TaskRepository.java` - bulk `shiftPositions` shift query; `findAllByColumnId` now sorts `(position, id)` via explicit `@Query`
- `src/main/java/com/vrudenko/kanban_board/repository/ColumnRepository.java` - board-scoped mirror of the above
- `src/main/java/com/vrudenko/kanban_board/service/TaskService.java` - `save` assigns position; `moveToColumn` gains the full renumbering contract
- `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java` - `save` assigns position; new `reorder`; `deleteById` gap-closes
- `src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java` - `+ PATCH .../reorder`
- `src/main/java/com/vrudenko/kanban_board/dto/task_dto/MoveTaskRequestDTO.java` - `+ targetPosition` (nullable, `@Min(0)`)
- `src/main/java/com/vrudenko/kanban_board/dto/column_dto/ReorderColumnRequestDTO.java` - new DTO, mandatory `version`/`targetPosition`
- `src/main/java/com/vrudenko/kanban_board/dto/task_dto/TaskResponseDTO.java` - `+ position`
- `src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java` - `+ position`
- `src/test/java/com/vrudenko/kanban_board/TaskOrderingE2ETest.java` - new, 10 tests
- `src/test/java/com/vrudenko/kanban_board/ColumnOrderingE2ETest.java` - new, 8 tests
- `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java` - `+ MoveToColumnQueryCountTest`
- `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java` - `UpdateAllFields` expected DTO now sets `position` (fallout)
- `src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java` - `UpdateById` expected DTO now sets `position` (fallout)

## Decisions Made

See `key-decisions` in frontmatter. In brief: reused existing `count*` methods as the position probe instead of a new max-position query; computed same-column moves as one signed range shift instead of two composed cross-column shifts (the plan's flagged highest-risk defect, driven test-first); kept ordered-read method names unchanged via explicit `@Query` rather than a derived-name rename.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug, self-introduced] `TaskOrderingE2ETest`'s HTTP GET stability test used the wrong URL (missing `/tasks` suffix)**
- **Found during:** Task 3, full-suite verification run
- **Issue:** `shouldReturnTasksSortedByPosition_overHttp` reused the task-creation POST helper's URL (`/boards/{boardId}/columns/{columnId}`, `ColumnController`'s route) for a GET call, but listing tasks lives on `TaskController`'s differently-nested `/boards/{boardId}/columns/{columnId}/tasks` route. The GET returned a non-JSON error body, failing to deserialize.
- **Fix:** Added a distinct `getListTasksUrl` helper appending `ApiPaths.TASKS`.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/TaskOrderingE2ETest.java`
- **Verification:** Full suite green afterward.
- **Committed in:** `3cd5e99` (Task 3 commit)

**2. [Rule 1 - Bug/pre-existing test fallout, anticipated by the plan] `ColumnControllerTest`/`TaskControllerTest`'s `UpdateById` expected-DTO comparisons broke on the new `position` field**
- **Found during:** Task 3, full-suite verification run
- **Issue:** Both tests build an `expected` DTO via `.builder()...build()` (leaving `position` at its default `null`) and compare it against the real HTTP response via `content().json(...)`. Spring's `content().json()` is lenient about *extra* actual fields but still enforces an exact match for any field *explicitly present* in the expected JSON — and since neither `TaskResponseDTO` nor `ColumnResponseDTO` carries `@JsonInclude(NON_NULL)`, the expected DTO serializes an explicit `"position": null`, which then mismatched the real `"position": 7`.
- **Fix:** Added `.position(mockPopulatedTask.getPosition())` / `.position(mockPopulatedColumn.getPosition())` to each expected-DTO builder.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java`, `src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java`
- **Verification:** Full suite green afterward.
- **Committed in:** `3cd5e99` (Task 3 commit)

**3. [Rule 1 - Bug/plan imprecision, no fix needed] Two acceptance-criteria greps overcount by exactly one, matching the same class of imprecision noted in 06-03's SUMMARY**
- **Found during:** Task 1 and Task 2 acceptance-criteria verification
- **Issue:** `grep -c "PatchMapping" TaskMoveController.java` returns 2, not the plan's stated 1 — the file's own `import ...PatchMapping;` line also matches the substring, and that file was never touched by this plan. `grep -c "NotNull" ReorderColumnRequestDTO.java` returns 3, not the plan's stated 2 — same cause, `import jakarta.validation.constraints.NotNull;` also matches.
- **Fix:** None needed — the real invariants (`TaskMoveController` has exactly one `@PatchMapping` method; `ReorderColumnRequestDTO` has exactly two `@NotNull`-annotated fields) both hold, confirmed by direct inspection.
- **Files modified:** none
- **Verification:** Manual inspection of both files.
- **Committed in:** n/a — no code change

**4. [Rule 1 - Bug, self-introduced, caught before commit] Query-count test's comment literally named the forbidden weaker counter**
- **Found during:** Task 3, acceptance-criteria verification (`grep -c "getQueryExecutionCount"` was expected to return 0)
- **Issue:** The first draft of `MoveToColumnQueryCountTest`'s explanatory comment spelled out `getQueryExecutionCount()` by name (to explain why it's the wrong counter), which the acceptance criterion's grep — by design — flags as a false positive, since the criterion cannot distinguish "uses the weak counter" from "mentions the weak counter's name in a comment."
- **Fix:** Reworded the comment to describe the weaker counter ("the weaker, HQL/JPQL-only counter") without naming it, per the plan's own explicit guidance for this exact situation ("refer to the counter by description, not by name").
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java`
- **Verification:** `grep -c "getQueryExecutionCount" TaskServiceTest.java` returns 0; full suite still green.
- **Committed in:** `3cd5e99` (Task 3 commit)

**5. [Operational — gradle daemon/file-lock flakiness, same as 06-01's documented issue] Two commit attempts failed on transient Gradle daemon/file-lock issues, not code problems**
- **Found during:** Task 1's commit
- **Issue:** A first `git commit` attempt was killed by a bash-tool 2-minute timeout mid-`fastTest`, leaving a stale lock on `build/test-results/fastTest/binary/output.bin` (the exact issue 06-01's SUMMARY documents); a follow-up attempt failed with "Gradle build daemon has been stopped: stop command received" from a race between an in-flight `./gradlew --stop` and the hook's own daemon.
- **Fix:** `./gradlew --stop`, removed the stale locked directory, retried with a longer timeout; the third attempt succeeded cleanly.
- **Files modified:** none (build artifacts only)
- **Verification:** Successful commit `bd61e2a` with a full green `fastTest` run in the hook.
- **Committed in:** n/a — operational, not a code change

---

**Total deviations:** 5 (2 self-introduced test bugs auto-fixed, 1 anticipated test fallout auto-fixed, 1 plan-count imprecision confirmed non-issue for two separate greps, 1 operational gradle flakiness worked around).
**Impact on plan:** No scope creep. All fixes were either mechanically necessary (URL bug, fallout DTOs, comment wording) or confirmed non-issues after direct inspection. The plan's flagged highest-risk item — same-column move/reorder shift composition — was implemented exactly as specified and is covered by a dedicated test, with no defect found.

## Issues Encountered

None beyond the deviations documented above.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- GAP-03 (task and column position ordering) is fully closed: both entities have actively-maintained, contiguous-from-zero positions on create/move/reorder/delete; the move endpoint stayed single (D-04); columns have their own reorder route; every sibling read is a total order under `(position, id)`; every shift statement is parent-scoped and every client-supplied position is validated and clamped.
- `docs/MOCKUP_FEATURE_GAP.md` §1.3's ordering gap is resolved.
- No blockers or concerns carried forward. Plans 05, 06, and 07 (if they touch `TaskEntity`/`ColumnEntity`/their DTOs/repositories) should be aware `position` is now a live, actively-shifted field on both entities and both response DTOs.

---
*Phase: 06-mock-up-feature-gap-closure*
*Completed: 2026-08-08*

## Self-Check: PASSED

- Verified `[ -f ]` on all three `key-files.created` paths: present.
- `git log --oneline --all --grep="06-04"` returns 3 commits (the three task commits above; this SUMMARY commit follows).
- Re-ran task-level `<acceptance_criteria>` and the plan-level `<verification>` commands during execution — all passed except the two documented, non-functional grep-count discrepancies (Deviation 3), both confirmed non-issues by direct inspection.
- Full `./gradlew spotlessCheck test` reconfirmed green (255 tests, 0 failures, 0 errors) after Task 3; `./gradlew spotlessCheck` and `./gradlew fastTest` reconfirmed green immediately before writing this SUMMARY.
