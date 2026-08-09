---
phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
plan: 04
subsystem: testing
tags: [junit5, mockmvc, testcontainers, spring-boot-test, restassured, refactor, optimistic-locking]

# Dependency graph
requires:
  - phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
    provides: "AbstractAppMockMvcTest fixture base and support/ package relocation (plan 07-01)"
provides:
  - "TaskLockingE2ETest, TaskOrderingE2ETest, TaskMoveE2ETest converted from RestAssured/RANDOM_PORT to the in-process MockMvc tier (D-03 verdict-table rows 14, 19, 20)"
  - "Confirmation that TaskMoveE2ETest's 'ConcurrentConflict' nested group is genuinely sequential (two back-to-back requests, no thread/executor/future) — safe to downgrade, not a KEEP"
affects: [07-07]

# Actuals (#2632)
actuals:
  tokens: 12966
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "MockMvc helper methods (createColumnOnBoard, createTaskInColumn, getColumnTasks) that perform+deserialize in one call, replacing RestAssured's given().when().then().extract().as() fluent chain"

key-files:
  created: []
  modified:
    - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/TaskOrderingE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveE2ETest.java

key-decisions:
  - "Confirmed by reading every nested group body in TaskMoveE2ETest that 'ConcurrentConflict' issues two sequential requests, not two threads — RESEARCH.md's finding held, no replan needed"
  - "Each TaskOrderingE2ETest test method keeps exactly one signinCookie() call (matching the original's one signin() call), staying well under the two-session ceiling despite the class doing ~10 signins across all methods combined"

patterns-established: []

requirements-completed: [TEST-03]

coverage:
  - id: D1
    description: "TaskLockingE2ETest converted to in-process MockMvc tier, all 3 optimistic-locking assertions preserved"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*TaskLockingE2ETest' — 3/3 pass"
        status: pass
    human_judgment: false
  - id: D2
    description: "TaskOrderingE2ETest converted to in-process MockMvc tier, all 10 position/move assertions preserved"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*TaskOrderingE2ETest' — 10/10 pass (1 TaskCreation + 9 MoveToColumn)"
        status: pass
    human_judgment: false
  - id: D3
    description: "TaskMoveE2ETest converted to in-process MockMvc tier, all 8 tests including event-listener assertions preserved; sibling TaskControllerTest and ActivityEventPublicationTest unaffected"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*TaskMoveE2ETest' --tests '*TaskControllerTest' --tests '*ActivityEventPublicationTest' — 8/8 + 10/10 + 11/11 pass"
        status: pass
    human_judgment: false

# Metrics
duration: 50min
completed: 2026-08-09
status: complete
---

# Phase 7 Plan 4: Task E2E Classes Converted to In-Process MockMvc Tier Summary

**TaskLockingE2ETest, TaskOrderingE2ETest, and TaskMoveE2ETest downgraded from real-socket RestAssured/RANDOM_PORT to in-process MockMvc, preserving every optimistic-locking and event-observation assertion one-for-one, with TaskMoveE2ETest's misleadingly-named "ConcurrentConflict" group confirmed genuinely sequential before conversion.**

## Performance

- **Duration:** 50 min
- **Started:** 2026-08-09T12:44:00Z (approx, first file reads)
- **Completed:** 2026-08-09T13:32:44Z
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments

- `TaskLockingE2ETest` converted to `AbstractAppMockMvcTest` + `MockMvc`, following `ColumnLockingE2ETest`'s structural pattern exactly (per plan's `<read_first>` instruction); 3/3 tests pass, all optimistic-lock assertions (conflict on stale version, success + version bump on current version, bad-request on missing version) unchanged
- `TaskOrderingE2ETest` converted; 10/10 tests pass across `TaskCreation` (1) and `MoveToColumn` (9), including the `TaskRepository`-backed `(position, id)` ordering assertions and the double-read determinism check that reads the production query directly with no test-side re-sort
- `TaskMoveE2ETest` converted, the largest of the three; 8/8 tests pass across the top-level move test and 6 nested groups (`StaleVersion`, `ConcurrentConflict`, `CrossBoardTarget`, `UnownedTarget`, `MissingVersion`, `UnknownIds` with 2 tests). `RecordingActivityEventListener` assertions carried across byte-identical apart from the request-dispatch lines — same real Spring `@TransactionalEventListener` bean, same after-commit observation, no test-managed `@Transactional` introduced. Sibling `TaskControllerTest` (10/10) and `ActivityEventPublicationTest` (11/11) both still pass, confirming the untouched controller test and the other listener consumer are unaffected
- **Confirmed, not assumed:** read every nested group body in `TaskMoveE2ETest` before converting — the `ConcurrentConflict` group name is a misnomer (RESEARCH.md's finding): it issues two sequential `patch()` calls to prove a stale-version conflict, never spawns a thread, submits to an executor, or awaits a future. Safe to downgrade as planned; no replan to a KEEP was triggered
- Zero production-code changes: `git diff --name-only da3eb8b HEAD -- src/main/` is empty

## Task Commits

Each task was committed atomically:

1. **Task 1: Convert TaskLockingE2ETest to the in-process tier** - `c00b4c3` (refactor)
2. **Task 2: Convert TaskOrderingE2ETest to the in-process tier** - `431539b` (refactor)
3. **Task 3: Convert TaskMoveE2ETest to the in-process tier, preserving its event-observation assertions** - `13e7e2b` (refactor)

**Plan metadata:** (pending — final docs commit follows this SUMMARY)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java` - RestAssured/RANDOM_PORT → MockMvc/AbstractAppMockMvcTest, 3 tests preserved
- `src/test/java/com/vrudenko/kanban_board/TaskOrderingE2ETest.java` - RestAssured/RANDOM_PORT → MockMvc/AbstractAppMockMvcTest, 10 tests preserved
- `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskMoveE2ETest.java` - RestAssured/RANDOM_PORT → MockMvc/AbstractAppMockMvcTest, 8 tests preserved, `RecordingActivityEventListener` assertions unchanged

## Decisions Made

- Kept each `TaskOrderingE2ETest` test method to exactly one `signinCookie()` call, matching the original's one-`signin()`-per-method shape — the plan's caution about the two-session ceiling didn't require any structural change since the original was already compliant
- Verified `ConcurrentConflict`'s sequential nature by reading its body directly rather than trusting RESEARCH.md's finding secondhand, per the plan's explicit instruction to stop and report if a genuinely concurrent group were found

## Deviations from Plan

None — plan executed exactly as written. All three files converted in-place with unchanged class names, unchanged nested group names, and unchanged `@Test` counts. No genuinely concurrent group was found in any of the three files.

## Issues Encountered

- The pre-commit hook's `fastTest` run intermittently timed out or was interrupted by a stray `./gradlew --stop` early in the session (bash tool timeout on the first attempt, then a shared Gradle daemon being stopped mid-run — plausibly by one of the 4 sibling parallel-wave agents executing concurrently in adjacent worktrees). Both interruptions left the commit un-created (verified via `git status`/`git log` before retrying), and a bare retry succeeded each time with no code change required. One `fastTest` run also surfaced a single failing test, `EventIdGeneratorTest.GenerateTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly()`, entirely unrelated to this plan's files (a pre-existing probabilistic birthday-paradox flake in the Snowflake-style ID generator's 23 random bits-per-millisecond design, worsened by CPU contention from parallel sibling builds) — out of scope per the deviation rules' scope boundary; a bare retry passed.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- D-03 verdict-table rows 14, 19, 20 executed; `TaskLockingE2ETest`, `TaskOrderingE2ETest`, `TaskMoveE2ETest` all run at the cheaper in-process tier with unchanged coverage
- No class renamed, moved, or merged — D-02's conditional Task-class merge into `TaskControllerTest` remains explicitly out of scope and deferred, per the plan
- `src/main/java` untouched; this plan's changes are fully isolated to `src/test/java`

---
*Phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n*
*Completed: 2026-08-09*

## Self-Check: PASSED

- All 4 claimed files confirmed present on disk (3 converted test classes, this SUMMARY.md)
- All 3 task commit hashes (`c00b4c3`, `431539b`, `13e7e2b`) confirmed present in `git log`
