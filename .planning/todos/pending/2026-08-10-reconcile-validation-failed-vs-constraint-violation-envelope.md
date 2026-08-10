---
created: 2026-08-10T13:40:00.000Z
title: Reconcile VALIDATION_FAILED vs CONSTRAINT_VIOLATION envelope split across controllers
area: backend
severity: major
files:
  - src/main/java/com/vrudenko/kanban_board/controller/BoardController.java
  - src/main/java/com/vrudenko/kanban_board/controller/UserController.java
  - src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java
  - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java
  - src/main/java/com/vrudenko/kanban_board/controller/TaskController.java
  - src/main/java/com/vrudenko/kanban_board/controller/SubtaskController.java
  - src/main/java/com/vrudenko/kanban_board/controller/TaskMoveController.java
  - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
---

## Problem

Discovered while writing plan 07.1-08's `InjectionAttemptTest.OversizedBoundary` group: three of this
application's seven controllers (`BoardController`, `UserController`, `ActivityController`) carry a
class-level `@Validated`; the other four (`ColumnController`, `TaskController`, `SubtaskController`,
`TaskMoveController`) do not. That presence/absence changes **which exception Spring throws** for the
exact same kind of failure -- a `@Valid @RequestBody` field constraint violation (e.g. a task title
one character over `MAX_TASK_TITLE_LENGTH`):

- **`@Validated`-carrying controllers** (Board/User/Activity): the failure is a
  `MethodArgumentNotValidException`, which `GlobalExceptionHandler` converts to `code:
  VALIDATION_FAILED` with a per-field `errors` map (`$.errors.title`, etc.) -- this phase's (D-01/D-02)
  converged envelope shape.
- **Non-`@Validated` controllers** (Column/Task/Subtask/TaskMove): the same kind of failure is a
  `HandlerMethodValidationException` instead, which `GlobalExceptionHandler`'s separate handler
  converts to `code: CONSTRAINT_VIOLATION` with a stringified `detail` message and **no `errors`
  map at all**.

Both are still clean 400s -- neither is a 500, and D-16's "malformed input degrades cleanly" claim
holds for both -- but a frontend that built its field-level error UI against `$.errors.<field>`
(the shape this phase's D-01/D-02 work explicitly converged the envelope onto) will silently get
nothing to render for any field-validation failure on four of the seven controllers. This directly
undermines the stated purpose of D-01/D-02's `ProblemDetail` convergence work: the envelope is not
actually uniform across the API for this failure class, even though `code` values from a single
closed `ErrorCode` enum make it *look* uniform at a glance.

Confirmed empirically, not assumed: `InjectionAttemptTest.OversizedBoundary`'s task-title/description
and subtask-title MAX+1 cases originally asserted `VALIDATION_FAILED` + `$.errors.<field>` (matching
the Board/Column-name cases on the same class) and failed with `CONSTRAINT_VIOLATION` instead -- the
test was adjusted to assert what each controller's actual, current behavior is, not what plan
07.1-08's authors initially assumed it would be.

## Solution

Two shapes to choose between, both real changes to `src/main` (out of scope for plan 07.1-08's
test-only remit -- `verification`'s "no production code changed" constraint applied):

1. **Add `@Validated` to the four missing controllers** (`ColumnController`, `TaskController`,
   `SubtaskController`, `TaskMoveController`), matching Board/User/Activity. Smallest diff, converges
   all seven controllers onto `MethodArgumentNotValidException`/`VALIDATION_FAILED`/`errors` map.
   Verify this doesn't also change validation behavior for method-level `@PathVariable @NotBlank`
   constraints on these same controllers (that's `@Validated`'s other, intended purpose) --
   `HandlerMethodValidationException`'s `CONSTRAINT_VIOLATION` handling may still be needed for
   *those* failures even after this change, so `GlobalExceptionHandler`'s `CONSTRAINT_VIOLATION`
   branch should stay.
2. **Teach `GlobalExceptionHandler`'s `HandlerMethodValidationException` branch to build the same
   `errors` map shape** the `MethodArgumentNotValidException` branch does, by extracting field names
   from `ex.getParameterValidationResults()`, so the two envelopes converge without touching any
   controller. Larger diff inside one class, but doesn't touch seven controller files or risk
   changing path-variable-constraint behavior.

Either way, add or extend regression coverage (this phase's `InjectionAttemptTest` already has cases
positioned to catch a regression here) asserting the SAME code/shape across at least one
`@Validated` and one non-`@Validated` controller, so a future controller added without `@Validated`
doesn't silently reopen this split.
