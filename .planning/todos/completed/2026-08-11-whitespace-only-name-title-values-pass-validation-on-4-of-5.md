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
resolved: 2026-08-11T00:00:00.000Z
resolved_by: Quick task 260811-ufu
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

## Resolution (260811-ufu)

**Question 1 — close the gap on all four fields: YES**, via a shared annotation, not the
`@AssertTrue` cross-check this todo originally proposed. `@AssertTrue`'s violation property path
would key on the method name (e.g. `atLeastOneFieldPopulated`), not the field, degrading the RFC
7807 `errors.<field>` envelope quick task 260811-p9c had just converged — a concrete regression the
`@AssertTrue` shape would have reintroduced. Instead: a new composed constraint annotation,
`com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank`, built like the existing
`BoardName`/`DisplayName`/etc. siblings (`@Constraint(validatedBy = {})` composing a single
built-in `@Pattern(regexp = ".*\\S.*", flags = DOTALL)`, zero hand-written `ConstraintValidator`).
Bean Validation's built-in constraints treat `null` as valid, so composing `@Pattern` (not
`@NotBlank`) is what makes "optional but not blank" work. Stacked alongside each field's existing
composed annotation on `UpdateBoardRequestDTO.name`, `UpdateTaskRequestDTO.title`,
`UpdateSubtaskRequestDTO.title`, `SignupRequestDTO.displayName` — proven RED-first at both the
validator tier (`OptionalNotBlankTest`, one `@Nested` group per DTO) and the HTTP tier
(`BoardControllerTest`/`TaskControllerTest`/`SubtaskControllerTest`.`UpdateById`,
`AuthenticationTest.Signup.FieldValidation`) before the fix landed, confirming the defect and then
its resolution on all four fields. Documented as `docs/CODE_STYLE.md` rule 12.

**Question 2 — should `UpdateColumnRequestDTO.name` become optional: NO.** Investigation (recorded
in the discussion CONTEXT.md, not re-derived at implementation time) found no test in
`BoardServiceTest`/`BoardControllerTest` exercising a version-only column update and no mockup
evidence of a "touch without renaming" flow for a DTO whose only mutable property is `name` — no
actual use case exists, unlike `UpdateTaskRequestDTO`/`UpdateSubtaskRequestDTO`, which have two
independently-editable fields and a real partial-update need. `UpdateColumnRequestDTO.name` keeps
its pre-existing `@NotBlank` unchanged (byte-identical annotations); the DTO instead gained a
class-level Javadoc exemption note, modeled on `UpdateThemeRequestDTO`'s precedent, explaining the
choice so a future audit sees a documented answer instead of an inconsistency.

**The `@AssertTrue` implementation this todo originally proposed was considered and rejected** —
see Question 1's answer above; the property-path/error-envelope regression was the deciding factor.

**Carried forward as a new `[minor]` todo:** `UpdateBoardRequestDTO.name`'s own optionality (D-13)
rests on the same "no test, no mockup evidence" gap that Question 2 declined to accept for the
column DTO — a version-only board update has no more demonstrated use case than a version-only
column update does. This task deliberately did not change that behavior; scrutinizing it was
explicitly out of scope. See
`.planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md`.

Full suite: 417 -> 430 tests (+13, zero shrinkage). `spotlessCheck` and the full `test` task both
green.
