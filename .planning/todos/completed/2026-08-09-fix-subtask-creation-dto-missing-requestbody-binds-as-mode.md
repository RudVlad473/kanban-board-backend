---
created: 2026-08-09T21:46:28.000Z
resolved: 2026-08-11
title: Fix subtask creation DTO missing @RequestBody -- binds as model attribute, not JSON body
area: backend
severity: major
files:
  - src/main/java/com/vrudenko/kanban_board/controller/TaskController.java
---

## Problem

`TaskController.addSubtaskByTaskId` declares its DTO parameter as
`@Valid SaveSubtaskRequestDTO dto` with **no `@RequestBody`** annotation, unlike every other
create/update endpoint in this codebase (`BoardController.save`, `ColumnController.addTaskByColumnId`,
`TaskController.updateById`, etc., all of which carry `@Valid @RequestBody ...`). Without
`@RequestBody`, Spring MVC treats the parameter as a model attribute and populates it via
`ServletModelAttributeMethodProcessor` from request parameters (query string / form-urlencoded
body) rather than parsing the JSON request body. A client POSTing a JSON body the way every other
creation endpoint expects will have its `title` field silently ignored, most likely tripping the
`@SubtaskTitle` validation constraint and returning 400 instead of creating the subtask.

Found during plan 07.1-06 (`ROADMAP-201`, "make create-endpoint success status codes consistently
201") while changing this method's return type from `ResponseEntity.ok(...)` to
`ResponseEntity.created(...)`. Deliberately not fixed there -- changing the binding annotation is a
behavioral change to how subtask creation parses input and deserves its own scoped change with its
own tests, not a drive-by fix bundled into a status-code plan.

## Solution

Add `@RequestBody` to the `dto` parameter of `TaskController.addSubtaskByTaskId`, matching every
sibling creation endpoint's shape. Add/update a controller-tier test proving a JSON-bodied POST to
`.../tasks/{taskId}/subtasks` actually creates the subtask (no such HTTP-level test currently
exists for this endpoint -- all existing coverage calls `TaskService.addSubtaskByTaskId` directly,
bypassing the controller's binding entirely, which is exactly why this defect went unnoticed).

Plan 07.1-08's injection/security test suite is expected to exercise this endpoint over real HTTP
and may surface this concretely (as a validation failure on a well-formed JSON payload) before this
todo is picked up directly.

## Resolution

Fixed via quick task `260811-me4`, delivered RED-then-GREEN across two commits touching
`TaskController.java` and `TaskControllerTest.java`:

- **What shipped:** `TaskController.addSubtaskByTaskId`'s `dto` parameter now carries
  `@RequestBody` alongside its existing `@Valid`, matching every sibling creation/update
  endpoint. No import change -- the class already wildcard-imports
  `org.springframework.web.bind.annotation.*`. Production diff is exactly one annotation on
  one parameter.
- **Two new controller-tier tests guard it:** `AddSubtaskByTaskId
  .testWithAuthenticatedUser_shouldAddSubtask_whenJsonBodyIsPosted` (a JSON-bodied POST creates
  the subtask, 201, persistence confirmed via `SubtaskService`) and `AddSubtaskByTaskId
  .testWithAuthenticatedUser_shouldReturnBadRequest_whenTitleIsSentAsQueryParamWithNoBody` (the
  query-parameter form no longer binds, 400). Both replace the prior workaround test, which sent
  the title as a request parameter and carried a comment naming this exact defect.
- **Correction to this todo's own prediction:** this todo predicted a JSON-bodied POST would trip
  `@SubtaskTitle` validation and return 400. That is not what actually happened. `@SubtaskTitle`
  composes only `@Size`, which passes on `null` -- so the pre-fix request instead had its `title`
  bind as `null` (silently ignored, per the model-attribute binding this todo describes), sailed
  through validation, and only failed downstream when Postgres rejected the null insert against
  `subtasks.title`'s `NOT NULL` constraint. The observed pre-fix status was **409 Conflict**
  (`DataIntegrityViolationException` mapped by `GlobalExceptionHandler`), not 400. This gap --
  `SaveSubtaskRequestDTO.title` lacking a null/blank guard that its sibling `Save*RequestDTO`
  classes all carry -- is real, predates this fix, and is filed separately rather than folded in
  here: see `.planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md`.
- **Blast radius verified, not assumed:** `AuthorizationGatingTest`, `InjectionAttemptTest`,
  `SubtaskControllerTest`, `SubtaskLockingTest`, and `BoardFullReadTest` all pass unchanged, plus
  the full `./gradlew fastTest` gate, with no test-count shrinkage.
