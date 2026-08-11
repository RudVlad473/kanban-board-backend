---
created: 2026-08-11T00:00:00.000Z
title: SubtaskTitle composed annotation's inner @Size carries the wrong message constant (confirmed inert, not client-visible)
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/dto/annotation/SubtaskTitle.java
---

## Problem

`SubtaskTitle`'s composing `@Size` constraint (`src/main/java/com/vrudenko/kanban_board/dto/annotation/SubtaskTitle.java`,
around lines 16-24) passes the wrong message constant:

```java
@Size(
        min = ValidationConstants.MIN_SUBTASK_TITLE_LENGTH,
        max = ValidationConstants.MAX_SUBTASK_TITLE_LENGTH,
        message = ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE)
public @interface SubtaskTitle {
```

`ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE` is the board-name-flavored message ("Board
name cannot be less than 1 character and more than 64 characters"), not the correct
`ValidationConstants.SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE` ("Subtask title cannot be less than
3 character and more than 32 characters"). This affects every field annotated `@SubtaskTitle`:
both `SaveSubtaskRequestDTO.title` and `UpdateSubtaskRequestDTO.title`.

**A sibling bug to this one, already fixed:** `SaveColumnRequestDTO.name`'s inline `@Size` had the
identical mistake (using `NAME_LENGTH_VALIDATION_MESSAGE` instead of
`COLUMN_NAME_LENGTH_VALIDATION_MESSAGE`) and was fixed directly in quick task 260811-qru (finding
F-04), because that field is annotated with plain `@Size` on the DTO, so the wrong text really did
reach the client. `SubtaskTitle` is different — **this one was empirically confirmed to be dead
code, not a live defect**: `SubtaskTitle` carries `@ReportAsSingleViolation`, and Bean Validation's
documented behavior for that meta-annotation is that any composing-constraint failure is reported
using the *composed* annotation's own default message, not the inner constraint's. A throwaway
`jakarta.validation.Validator` run against a too-long `title` on both `SaveSubtaskRequestDTO` and
`UpdateSubtaskRequestDTO` returned `"Subtask title cannot be empty"` in both cases —
`SubtaskTitle`'s own default `message()`, never the mismatched `NAME_LENGTH_VALIDATION_MESSAGE`
text. No client has ever seen the wrong message from this particular mismatch.

## Solution

Change the inner `@Size`'s `message` attribute from `ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE`
to `ValidationConstants.SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE`, for source-code correctness and
to remove a misleading reference (a future reader has no way to know it's inert without re-deriving
the `@ReportAsSingleViolation` interaction from scratch). Because the change has no observable
effect on any API response (confirmed above), there is no meaningful RED-then-GREEN controller-tier
regression test to write for it — a unit test constructing `SubtaskTitle`'s own default message and
asserting it is unaffected would be the only way to demonstrate anything changed, and even that
only proves the source text now matches intent, not a behavior change. Low priority; safe to batch
with any other pass through `dto/annotation/`.
