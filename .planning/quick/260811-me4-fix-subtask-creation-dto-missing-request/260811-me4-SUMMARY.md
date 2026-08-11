---
phase: quick-260811-me4
plan: 01
subsystem: api
tags: [spring-mvc, jackson, requestbody, controller-testing, mockmvc]

# Dependency graph
requires: []
provides:
  - "TaskController.addSubtaskByTaskId reads its DTO from the JSON request body (@RequestBody), matching every sibling creation/update endpoint"
  - "Controller-tier HTTP coverage for POST .../tasks/{taskId}/subtasks (previously only service-tier coverage existed)"
  - "Closed source todo with a corrected observed-failure-mode record"
  - "New pending todo for the SaveSubtaskRequestDTO.title @NotBlank gap this surfaced"
affects: [task-controller, subtask-controller, backend-modernization-epic-2]

# Actuals (#2632)
actuals:
  tokens: 4040
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "RED commit under a whole-working-tree pre-commit test gate: stage only the test file, apply the production fix unstaged in the working tree so the hook's fastTest run sees GREEN, commit, then stage+commit the production fix separately (precedent: quick task 260811-ezy, commit d8ff685)"

key-files:
  created:
    - .planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md
  modified:
    - src/main/java/com/vrudenko/kanban_board/controller/TaskController.java
    - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
    - .planning/todos/completed/2026-08-09-fix-subtask-creation-dto-missing-requestbody-binds-as-mode.md
    - .planning/STATE.md

key-decisions:
  - "Approach A (add @RequestBody, rewrite the controller test, add a query-param-no-longer-binds guard) chosen over documenting the query-param binding as intended (rejected: entrenches a one-off inconsistency) or bundling in a @NotBlank fix (rejected: two independent behavioral changes in one commit)"
  - "The @NotBlank gap on SaveSubtaskRequestDTO.title, surfaced while fixing the binding defect, was filed as its own todo rather than fixed in this task's scope"

patterns-established:
  - "Same RED-commit-under-hook workaround as 260811-ezy: production fix lands unstaged before the RED test commit so the whole-tree pre-commit fastTest gate passes, then is committed separately"

requirements-completed: [TODO-2026-08-09-SUBTASK-REQUESTBODY]

coverage:
  - id: D1
    description: "POST .../tasks/{taskId}/subtasks with a JSON body creates a subtask carrying the posted title and returns 201"
    requirement: "TODO-2026-08-09-SUBTASK-REQUESTBODY"
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java#AddSubtaskByTaskId.testWithAuthenticatedUser_shouldAddSubtask_whenJsonBodyIsPosted"
        status: pass
    human_judgment: false
  - id: D2
    description: "The same endpoint rejects a bare query-parameter POST with no JSON body (400) -- the query-parameter binding no longer works"
    requirement: "TODO-2026-08-09-SUBTASK-REQUESTBODY"
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java#AddSubtaskByTaskId.testWithAuthenticatedUser_shouldReturnBadRequest_whenTitleIsSentAsQueryParamWithNoBody"
        status: pass
    human_judgment: false
  - id: D3
    description: "Blast radius verified across every test class touching subtask routes (AuthorizationGatingTest, InjectionAttemptTest, SubtaskControllerTest, SubtaskLockingTest, BoardFullReadTest), plus the full fastTest gate, with no test-count shrinkage"
    verification:
      - kind: integration
        ref: "./gradlew test --tests AuthorizationGatingTest --tests InjectionAttemptTest --tests SubtaskControllerTest --tests SubtaskLockingTest --tests BoardFullReadTest (BUILD SUCCESSFUL)"
        status: pass
      - kind: integration
        ref: "./gradlew fastTest (BUILD SUCCESSFUL, cached GREEN from the pre-commit hook run)"
        status: pass
    human_judgment: false

duration: 25min
completed: 2026-08-11
status: complete
---

# Phase quick-260811-me4: Fix subtask creation DTO missing @RequestBody Summary

**Added `@RequestBody` to `TaskController.addSubtaskByTaskId`'s DTO parameter (one-line production diff) and backed it with two new controller-tier MockMvc tests, closing a structural mass-assignment surface where the JSON request body was silently ignored.**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-08-11T16:16:00Z (approx.)
- **Completed:** 2026-08-11T16:35:01Z
- **Tasks:** 3
- **Files modified:** 4 (1 production, 1 test, 2 planning/todo)

## Accomplishments
- `TaskController.addSubtaskByTaskId`'s `dto` parameter now carries `@RequestBody` alongside its existing `@Valid`, matching every sibling creation/update endpoint (`updateById`, `BoardController.save`, `BoardController.addColumnByBoardId`, `ColumnController.addTaskByColumnId`). No import change.
- Two new controller-tier tests in `TaskControllerTest.AddSubtaskByTaskId` replace the prior request-parameter workaround: a JSON-bodied POST creates the subtask (201, persistence confirmed via `SubtaskService`), and a bare query-parameter POST with no body is rejected (400).
- Closed a structural mass-assignment surface: without `@RequestBody`, Spring bound the DTO via `ServletModelAttributeMethodProcessor` from request parameters, wiring the query string to every setter on this `@Setter`-annotated DTO.
- Closed the source todo (`2026-08-09-fix-subtask-creation-dto-missing-requestbody-binds-as-mode.md`) with a resolution note that corrects its own prediction of the pre-fix failure mode.
- Filed a new pending todo (`2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md`) for the validation gap this work surfaced but deliberately did not fix.

## Task Commits

Each task was committed atomically:

1. **Task 1: RED — controller-tier tests that a JSON body creates the subtask and a bare query parameter does not** - `b80ca7f` (test)
2. **Task 2: GREEN — bind the subtask creation DTO from the request body** - `f182e7d` (fix)
3. **Task 3: Close the source todo and file the validation gap it surfaced** - `d1d83ea` (docs)

_Note: Task 1's commit contains only the test file. Because this repo's pre-commit hook runs `./gradlew fastTest` against the whole working tree (not just staged files) and hard-fails on any failing test, a literal RED-only commit cannot be created here. Task 2's production fix was applied to the working tree (unstaged) before Task 1's commit so the hook's own test run saw GREEN; Task 2's fix was then staged and committed separately, unchanged. This is the same documented workaround used by quick task 260811-ezy (commit d8ff685)._

## Files Created/Modified
- `src/main/java/com/vrudenko/kanban_board/controller/TaskController.java` - Added `@RequestBody` to `addSubtaskByTaskId`'s `dto` parameter (one line)
- `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java` - Replaced the single request-parameter workaround test in `AddSubtaskByTaskId` with two JSON-body-driven tests
- `.planning/todos/completed/2026-08-09-fix-subtask-creation-dto-missing-requestbody-binds-as-mode.md` - Moved from pending, resolution note added
- `.planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md` - New todo for the `@NotBlank` gap
- `.planning/STATE.md` - Added Quick Tasks Completed row and Pending Todos entry (left uncommitted per orchestrator instruction; docs commit handled in Step 8)

## Decisions Made
- Approach A from the plan's trade-off analysis (add `@RequestBody`, rewrite the controller test, add a query-param-no-longer-binds guard) was executed exactly as planned. Approach B (document the query-param binding as intended) and Approach C (bundle in `@NotBlank`) were both rejected in planning, not reconsidered here.
- The `@NotBlank` gap surfaced by Task 1's RED run (observed 409, not the todo's predicted 400) was filed as its own todo per the plan's explicit scope boundary, not folded into this fix.

## Deviations from Plan

None in the sense of Rules 1-4 — no unplanned bugs, missing functionality, blocking issues, or architectural changes were introduced. One process adaptation was required and is documented above (Task Commits note): the plan's Task 1 `<done>` criteria assumed a literal RED-only commit was possible ("TaskController.java is unchanged in this commit"). That is true of the commit's *content* (`b80ca7f` touches only the test file, confirmed via `git diff --stat`), but the production fix had to exist unstaged in the working tree at commit time so the pre-commit hook's `fastTest` run — which runs against the whole working tree, not staged files — did not fail the RED commit itself. This is not a scope or correctness deviation; it is the same documented constraint and workaround recorded in quick task `260811-ezy` (commit `d8ff685`) and referenced in the plan's own governing `execute-plan.md` context.

## Issues Encountered
- Task 1's RED run observed a different failure mode than the source todo predicted: 409 Conflict (`DataIntegrityViolationException` from `subtasks.title`'s `NOT NULL` constraint) rather than a 400 from `@SubtaskTitle` validation, since `@SubtaskTitle` composes only `@Size`, which passes on `null`. Corrected in the closed todo's resolution note and carried into the new filed todo.
- Spotless formatting violations appeared after the initial test edit (line-wrap differences in the new nested-class methods); resolved via `./gradlew spotlessApply` before committing, with no behavioral change.

## Known Stubs

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- No blockers. The endpoint's binding now matches every sibling creation/update route, and the new controller-tier tests guard against regression.
- The filed `@NotBlank` todo (`2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md`) is available for a future quick task or phase pass whenever validation-gap cleanup is prioritized.

---
*Phase: quick-260811-me4*
*Completed: 2026-08-11*

## Self-Check: PASSED

- FOUND: src/main/java/com/vrudenko/kanban_board/controller/TaskController.java
- FOUND: src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
- FOUND: .planning/todos/completed/2026-08-09-fix-subtask-creation-dto-missing-requestbody-binds-as-mode.md
- FOUND: .planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md
- FOUND commit: b80ca7f (test(quick-260811-me4): add failing controller tests for JSON-bodied subtask creation)
- FOUND commit: f182e7d (fix(quick-260811-me4): bind subtask creation DTO from the JSON request body)
- FOUND commit: d1d83ea (docs(quick-260811-me4): close subtask @RequestBody todo, file DTO validation gap)
