---
created: 2026-08-11T00:00:00.000Z
title: Whitespace-only name/title values pass validation on 4 of 5 examined optional fields -- decide the Update-DTO optionality design fork this exposes
area: backend
severity: major
files:
  - src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/UpdateSubtaskRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/user_dto/SignupRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java
---

## Problem

None of `UpdateBoardRequestDTO.name`, `UpdateTaskRequestDTO.title`, `UpdateSubtaskRequestDTO.title`,
or `SignupRequestDTO.displayName` carries an explicit `@NotBlank` — each relies solely on its
composed annotation's `@Size(min >= 1)`, which only rejects a fully-empty string, not a
whitespace-only one at or above the minimum length. `UpdateColumnRequestDTO.name`
(`src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java:21-25`) is the
sole exception in this codebase: it carries an explicit `@NotBlank` alongside its `@Size`.

**Empirically confirmed** during the 260811-qru DTO/controller audit with a throwaway
`jakarta.validation.Validator`, each DTO built with a 3-space value for the field in question:

| DTO.field | value `"   "` | violations |
|---|---|---|
| `UpdateBoardRequestDTO.name` | `"   "` | **0** (passes) |
| `UpdateTaskRequestDTO.title` | `"   "` | **0** (passes) |
| `UpdateSubtaskRequestDTO.title` | `"   "` | **0** (passes) |
| `SignupRequestDTO.displayName` | `"   "` | **0** (passes) |
| `UpdateColumnRequestDTO.name` | `"   "` | **1** (correctly rejected) |

A whitespace-only board name, task title, subtask title, or signup display name reaches
persistence today via the four DTOs listed above.

**A related, second asymmetry `UpdateColumnRequestDTO`'s `@NotBlank` also causes:** because
`@NotBlank` rejects `null` as well as blank, `UpdateColumnRequestDTO.name` is not actually
independently optional despite the DTO's partial-update (`@JsonInclude(NON_NULL)`) shape — a PUT
to `/columns/{id}` that omits `name` entirely is rejected by `@NotBlank` before it ever reaches the
service layer. `UpdateBoardRequestDTO.name`, by contrast, may genuinely be omitted (a version-only
update is valid). Two single-mutable-field Update DTOs disagreeing on whether their one field may
be omitted at all.

## Solution

This is a design fork, not a one-line fix — closing the whitespace-blank gap on
`UpdateBoardRequestDTO`/`UpdateTaskRequestDTO`/`UpdateSubtaskRequestDTO`/`SignupRequestDTO` cannot
be done by simply adding `@NotBlank` to each: that would also reject `null`, silently making the
field mandatory and breaking the documented, tested optionality (`SignupRequestDTOTest.whenDisplayNameIsMissing_thenNoViolation`
explicitly asserts `displayName` may be omitted; the `Update*RequestDTO` partial-update convention,
`@JsonInclude(NON_NULL)`, exists for the same reason). No existing pattern in this codebase
currently implements "reject blank only when the field is actually provided" — it would need a
small `@AssertTrue`-style cross-check (Bean Validation's `@AssertTrue`, following the precedent
`atLeastOneFieldPopulated()` already sets on `UpdateTaskRequestDTO`/`UpdateSubtaskRequestDTO` for a
different cross-field concern) checking `value == null || !value.isBlank()`.

Two decisions are needed before implementing:

1. **Should the whitespace-blank gap be closed on all four fields**, using a new shared
   "optional-but-not-blank" pattern (worth extracting once, given it would apply identically to
   all four)?
2. **Should `UpdateColumnRequestDTO.name` become genuinely optional** (dropping its `@NotBlank` in
   favor of the same optional-but-not-blank pattern, so a version-only column update becomes
   valid, matching `UpdateBoardRequestDTO`'s shape) — or is a column update *meant* to always
   require a name, since `name` is the column's only mutable property? If the latter, that should
   be documented at the class level the way `UpdateThemeRequestDTO`'s existing rule-6 exemption is,
   so a future audit does not re-flag it.

Add a regression test per corrected field asserting a whitespace-only value returns 400 once the
fix lands, and (if decision 1 above lands) a test proving `null`/omission still passes.
