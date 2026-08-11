---
created: 2026-08-11T00:00:00.000Z
title: SaveSubtaskRequestDTO.title missing @NotBlank -- a null/blank title reaches the database constraint instead of Bean Validation
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/SaveSubtaskRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/annotation/SubtaskTitle.java
---

## Problem

`SaveSubtaskRequestDTO.title` carries only `@SubtaskTitle`, a composed annotation that resolves
to a `@Size` constraint -- and Bean Validation's `@Size` passes on `null` by design (it only
constrains the length of a present value). This is inconsistent with every sibling
`Save*RequestDTO` in this codebase: `SaveBoardRequestDTO` pairs `@BoardName` with `@NotBlank`,
`SaveTaskRequestDTO` pairs its size rule with `@NotBlank`, and `SaveColumnRequestDTO` does the
same. `SaveSubtaskRequestDTO` is the one outlier that pairs its size rule with nothing.

**Consequence:** a JSON body of `{}` or `{"title":null}` posted to
`POST .../tasks/{taskId}/subtasks` is not rejected by Bean Validation at all. It proceeds into
`TaskService.addSubtaskByTaskId`, and the `null` title reaches the database's
`subtasks.title NOT NULL` constraint instead, surfacing as a `DataIntegrityViolationException`
mapped by `GlobalExceptionHandler` to **HTTP 409 Conflict** with a raw SQL-flavored `detail`
message -- not the clean 400 a validation failure would produce, and not a status code a client
would naturally associate with "you forgot a required field."

**Observed, not assumed:** this exact status was captured while writing the RED test for quick
task `260811-me4` (`TaskControllerTest.AddSubtaskByTaskId
.testWithAuthenticatedUser_shouldAddSubtask_whenJsonBodyIsPosted`, run before that task's
production fix existed) -- posting a JSON body with the controller's DTO parameter still
unannotated bound `title` as `null`, passed `@SubtaskTitle` validation, and returned 409 from a
`DataIntegrityViolationException` naming the `subtasks.title` NOT NULL constraint.

**Condition predates `260811-me4` and is only made reachable by the normal JSON path by that
fix.** Before `260811-me4`, this endpoint's DTO bound from request parameters/model-attribute
binding, not the JSON body, so a JSON-bodied `{}` post was already silently dropping `title` to
`null` for a different reason (the body was never parsed at all). `260811-me4` did not introduce
this gap -- it made the JSON path the endpoint's real path, so this is now the gap a normal client
integration will actually hit.

## Solution

Add `@NotBlank` to `SaveSubtaskRequestDTO.title` alongside its existing `@SubtaskTitle`, following
the precedent set by `SaveBoardRequestDTO`, `SaveTaskRequestDTO`, and `SaveColumnRequestDTO` (each
pairs its size-shaped custom annotation with `@NotBlank`). Alternatively, if a project-wide
decision is made to fold blank-checking into the custom annotations themselves (composing
`@NotBlank` inside `@SubtaskTitle`, `@BoardName`, `@TaskTitle`, etc., rather than stacking it at
each DTO field), that decision should be made once and applied consistently -- whichever of
`SaveSubtaskRequestDTO` or the shared `@SubtaskTitle` annotation gets changed, the other three
DTOs' size-plus-blank pairing is the precedent to match, not `SaveSubtaskRequestDTO`'s current
shape.

Whichever approach is taken, add/update a test asserting that a JSON body of `{}` or
`{"title":null}` (or a blank string) posted to `.../tasks/{taskId}/subtasks` returns 400, not 409
-- proving the fix actually moves the failure from the database constraint to Bean Validation.
