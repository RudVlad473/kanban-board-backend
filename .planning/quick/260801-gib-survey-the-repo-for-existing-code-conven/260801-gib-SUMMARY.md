---
phase: quick/260801-gib
plan: 01
subsystem: testing
tags: [documentation, code-style, http-status, assertj, jpa, testing-conventions]

# Dependency graph
requires:
  - phase: quick/260801-gby
    provides: "docs/CODE_STYLE.md with Rule 1 (enums over magic constants) established as the format template"
provides:
  - "docs/CODE_STYLE.md rules 2-7: ownership-verified loading, AssertJ/catchException, no-mocks testing, @Nested/AAA structure, Update*RequestDTO shape, Optional isEmpty()-guard unwrapping"
  - "TaskLockingE2ETest and ColumnLockingE2ETest brought into compliance with CODE_STYLE.md Rule 1"
affects: [future-phases-touching-services, future-phases-adding-tests, future-phases-adding-update-dtos]

actuals:
  tokens: 4409
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "docs/CODE_STYLE.md now documents 7 house-style rules with a fixed section shape (statement / **Why:** / Discouraged / Preferred / closing reference)"

key-files:
  created: []
  modified:
    - docs/CODE_STYLE.md
    - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java

key-decisions:
  - "All six rules inserted between Rule 1's closing sentence and the '## Adding a rule' section, with zero deletions to existing content"
  - "Fixed the code (E2E tests), not the rule — resolved the one existing Rule 1 violation by converting bare int status literals to HttpStatus.*.value()"

patterns-established:
  - "CODE_STYLE.md Rule 2: resolve entities only through a service's own findById(userId, id) loader, never repository.findById directly; downstream repository calls use the verified entity's id"
  - "CODE_STYLE.md Rule 3: AssertJ always via qualified Assertions.assertThat(...); exceptions captured via Assertions.catchException(...), never assertThrows"
  - "CODE_STYLE.md Rule 4: no mocks — @SpringBootTest extending AbstractAppTest/AbstractAppE2ETest against real H2, countQueries(Runnable) the only sanctioned query-count assertion"
  - "CODE_STYLE.md Rule 5: @Nested grouping by method-under-test, should<Outcome>_when<Condition> naming (or testWithAuthenticatedUser_... for MockMvc controller tests), // arrange / // act / // assert comments instead of @DisplayName"
  - "CODE_STYLE.md Rule 6: Update*RequestDTO carries @JsonInclude(NON_NULL), @NotNull Long version, and atLeastOneFieldPopulated() when more than one field is optional"
  - "CODE_STYLE.md Rule 7: unwrap repository Optionals with an isEmpty() guard + .get(), not orElseThrow, for consistency with every existing unwrap site"

requirements-completed: [QUICK-260801-gib]

coverage:
  - id: D1
    description: "docs/CODE_STYLE.md carries seven numbered rules (1-7), each following Rule 1's exact shape, citing real repo symbols, inserted with zero deletions to existing content"
    requirement: "QUICK-260801-gib"
    verification:
      - kind: other
        ref: "Task 1 automated verify command (grep-based structural + content checks) — printed RULES_OK"
        status: pass
    human_judgment: false
  - id: D2
    description: "TaskLockingE2ETest and ColumnLockingE2ETest no longer assert HTTP status via bare numeric literals; both use HttpStatus.*.value()"
    requirement: "QUICK-260801-gib"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests \"*TaskLockingE2ETest*\" --tests \"*ColumnLockingE2ETest*\" — BUILD SUCCESSFUL"
        status: pass
      - kind: other
        ref: "./gradlew spotlessCheck — BUILD SUCCESSFUL"
        status: pass
      - kind: e2e
        ref: "./gradlew test (full suite) — BUILD SUCCESSFUL"
        status: pass
    human_judgment: false

duration: 20min
completed: 2026-08-01
status: complete
---

# Phase quick/260801-gib Plan 01: Survey-Driven CODE_STYLE.md Expansion + E2E Rule 1 Fix Summary

**Appended six new house-style rules (ownership-verified loading, AssertJ/catchException, no-mocks, @Nested/AAA structure, Update*RequestDTO shape, Optional isEmpty()-guard) to docs/CODE_STYLE.md, and converted the ten raw-int HTTP status assertions across two locking E2E tests to HttpStatus enum constants.**

## Performance

- **Duration:** ~20 min
- **Completed:** 2026-08-01
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments
- `docs/CODE_STYLE.md` now documents 7 rules total (1 pre-existing + 6 new), each with a rule statement, a `**Why:**` line, and a Discouraged/Preferred fenced-java-block pair citing real repo symbols (`TaskService.findById`, `Assertions.catchException`, `AbstractAppTest.countQueries`, `UpdateTaskRequestDTO`, `OwnershipVerifierService.verifyOwnershipOfBoard`, etc.)
- Rule 1 and the `## Adding a rule` section survive byte-identical (`git diff --numstat` confirmed 0 deletions to the file)
- `TaskLockingE2ETest` and `ColumnLockingE2ETest` each now express all five expected HTTP statuses via `HttpStatus.OK.value()` / `HttpStatus.CONFLICT.value()` / `HttpStatus.BAD_REQUEST.value()` instead of bare `200`/`409`/`400` literals, closing the codebase's one existing violation of its own Rule 1
- Full test suite (`./gradlew test`) and `./gradlew spotlessCheck` both pass after the changes

## Task Commits

Each task was committed atomically:

1. **Task 1: Insert Rules 2-7 into docs/CODE_STYLE.md** - `c8ed78d` (docs)
2. **Task 2: Replace numeric status literals with HttpStatus in TaskLockingE2ETest** - `44f572c` (test)
3. **Task 3: Replace numeric status literals with HttpStatus in ColumnLockingE2ETest** - `85ed93f` (test)

_No TDD tasks in this plan — documentation edit plus literal substitution in already-passing tests._

## Files Created/Modified
- `docs/CODE_STYLE.md` - Added rules 2-7 (225 inserted lines, 0 deleted), inserted between Rule 1's closing sentence and the `## Adding a rule` heading
- `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java` - Added `import org.springframework.http.HttpStatus;`; replaced 5 bare-int status assertions with `HttpStatus.*.value()`
- `src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java` - Same treatment, mirroring the task-locking test structurally

## Decisions Made
- Followed the plan's locked CONTEXT decisions verbatim for rule content and ordering (C3, C1, C4, C2, C6, C10 mapped to Rules 2-7 respectively)
- Resolved the Rule 1 self-contradiction by fixing the two E2E test files rather than weakening the rule, per the plan's "fix-the-code-not-the-rule" directive
- Used `.value()` on each `HttpStatus` constant since `statusCode()` returns `int` and a bare enum constant would not compile against it

## Deviations from Plan

None - plan executed exactly as written. All verification commands (`RULES_OK`, `TASK_E2E_OK`-equivalent static checks, `COLUMN_E2E_OK`-equivalent static checks) passed, plus `./gradlew spotlessCheck` and the full `./gradlew test` suite.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `docs/CODE_STYLE.md` is now a real, seven-rule house-style reference rather than a single-rule stub; future phases can cite it directly when reviewing service-loading patterns, test structure, DTO shape, or Optional-unwrapping style
- Both locking E2E tests are now internally consistent with the style guide they must comply with — no outstanding Rule 1 violations in the codebase
- No blockers for subsequent work

---
*Phase: quick/260801-gib*
*Completed: 2026-08-01*

## Self-Check: PASSED

All created/modified files confirmed present on disk; all three task commit hashes (`c8ed78d`, `44f572c`, `85ed93f`) confirmed present in `git log`.
