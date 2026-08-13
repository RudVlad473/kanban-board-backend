---
created: 2026-08-11T00:00:00.000Z
resolved: 2026-08-13
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

## Resolution

Resolved by quick task `260813-h2f`. Took the todo's primary option (D-01): added
`@NotBlank(message = "Subtask title cannot be empty")` to `SaveSubtaskRequestDTO.title` alongside
the existing `@SubtaskTitle`, matching the `SaveBoardRequestDTO` / `SaveTaskRequestDTO` /
`SaveColumnRequestDTO` precedent exactly. The alternative the todo names -- composing `@NotBlank`
into the shared `@SubtaskTitle` annotation itself -- was rejected (D-03), not merely deferred as
out of scope: `@SubtaskTitle` is also used by `UpdateSubtaskRequestDTO.title`, which is
deliberately optional (partial-update semantics from quick task `260811-ufu`), so composing a
blank-check into the shared annotation would have silently made partial subtask updates
mandatory-title. `SubtaskTitle.java` is byte-identical after this task, confirmed by
`git diff --name-only src/main` listing exactly `SaveSubtaskRequestDTO.java`.

**Pre-fix status, observed against unmodified production code (not assumed):**
- `{}` -> 409, `code=DATA_INTEGRITY_VIOLATION`, `detail` naming the raw `subtasks.title` NOT NULL
  constraint -- exactly matching this todo's description.
- `{"title":null}` -> 409, identical shape -- exactly matching this todo's description.
- A three-space (whitespace-only) title -> **201 Created**, not 409. This was not what the todo
  described and is a real, separate gap: `@Size(min=3)` only checks length, so a 3-character
  whitespace string satisfies it and a subtask with a blank title was silently persisted. `@NotBlank`
  closes this gap too, as a side effect of the same fix.
- An empty-string title -> **already 400**, not 409. `@SubtaskTitle`'s own `@Size(min=3, max=32)`
  already rejects a zero-length string, independent of this fix, with the message
  `"Subtask title cannot be empty"` -- coincidentally identical to the message `@NotBlank` now uses
  (that string was chosen deliberately to match `SubtaskTitle`'s own default, D-02).

**Post-fix status:** all four cases return 400 with `code=VALIDATION_FAILED` and an `errors.title`
entry. The `{}`, `{"title":null}`, and whitespace-only cases each trip exactly one constraint and
assert the exact message `"Subtask title cannot be empty"`; the empty-string case trips both
`@NotBlank` and `@Size`, whose messages collapse last-writer-wins in `GlobalExceptionHandler`'s
`HashMap`-backed errors map (unspecified iteration order), so that one test asserts only that
`errors.title` is present, not its exact text.

Four new `TaskControllerTest.AddSubtaskByTaskId` tests cover all four cases end-to-end through the
real HTTP stack. `SubtaskTitleMessageTest`'s two `hasSize(1)` assertions (one per `Save`/`Update`
DTO) re-ran unmodified and stayed green -- an over-long title is not blank, so `@NotBlank` does not
additionally fire there. Full suite green with zero shrinkage (see quick task `260813-h2f`'s
SUMMARY for the exact before/after counts).

**Left open, not resolved by this task:** the todo's own "Alternatively, a project-wide decision...
should be made once and applied consistently" paragraph, regarding whether to fold `@NotBlank`
checking into the shared size-shaped annotations (`@SubtaskTitle`, `@BoardName`, `@TaskTitle`,
etc.) instead of stacking it at each `Save*RequestDTO` field. This task did not settle that
question -- it applied the existing stacking convention, per precedent, exactly as three sibling
DTOs already do.
