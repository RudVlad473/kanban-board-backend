# Quick Task 260811-ufu: Resolve whitespace-only validation gap todo (major severity) - Context

**Gathered:** 2026-08-11
**Status:** Ready for planning

<domain>
## Task Boundary

Resolve `.planning/todos/pending/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md`
(severity: major). `UpdateBoardRequestDTO.name`, `UpdateTaskRequestDTO.title`,
`UpdateSubtaskRequestDTO.title`, and `SignupRequestDTO.displayName` all lack `@NotBlank`, so a
whitespace-only value (e.g. `"   "`) passes validation and reaches persistence today (empirically
confirmed by the 260811-qru audit's throwaway `Validator` run). `UpdateColumnRequestDTO.name` is
the sole correct sibling (carries `@NotBlank`), but that also makes it non-optional (rejects
`null`), unlike every other single-field `Update*RequestDTO`.

</domain>

<decisions>
## Implementation Decisions

### Decision 1: Close the whitespace-blank gap via a reusable composed annotation (not @AssertTrue)

Close the gap on all four fields: `UpdateBoardRequestDTO.name`, `UpdateTaskRequestDTO.title`,
`UpdateSubtaskRequestDTO.title`, `SignupRequestDTO.displayName`.

**Implementation approach — do NOT use the `atLeastOneFieldPopulated()`-style `@AssertTrue`
cross-check the source todo suggested.** Instead, add a new composed constraint annotation
following this codebase's existing pattern in `src/main/java/com/vrudenko/kanban_board/dto/annotation/`
(see `BoardName.java` for the reference shape: `@Constraint(validatedBy = {})` composing built-in
constraints, `@Documented`, `@Target({ElementType.FIELD})`, `@Retention(RUNTIME)`).

The new annotation (e.g. `OptionalNotBlank`) should compose `@Pattern(regexp = "\\S.*")` (or
equivalent — "starts with a non-whitespace character," which rejects `""` and whitespace-only
strings but is a no-op on `null`, per Bean Validation's standard convention that built-in
validators return valid for `null` — only `@NotNull`/`@NotBlank`/`@NotEmpty` reject null). This
gives "optional but not blank" with no custom `ConstraintValidator` implementation needed, and is
directly stackable alongside each field's existing composed annotation (`@BoardName`, `@TaskTitle`,
`@SubtaskTitle`, `@DisplayName`) rather than replacing them.

This decorator must be genuinely reusable — written once, applied via a single annotation on each
of the four fields, so a future optional String field can adopt it the same way.

Add a regression test per corrected field asserting a whitespace-only value now returns 400, plus
a test proving `null`/omission still passes (the existing optionality must not regress).

### Decision 2: UpdateColumnRequestDTO.name stays mandatory — documented, not "fixed"

**Do not** make `UpdateColumnRequestDTO.name` optional. Investigation during discussion (no test
in `BoardServiceTest`/`BoardControllerTest` exercises a version-only board update; no UI/mockup
evidence of a "touch the resource without renaming it" flow for a single-field DTO) found no
actual use case for a version-only update on a DTO with exactly one substantive field — unlike
`UpdateTaskRequestDTO`/`UpdateSubtaskRequestDTO`, which have two independently-editable fields and
a real partial-update need.

Required changes:
1. Keep `@NotBlank` on `UpdateColumnRequestDTO.name` as-is (no code change to that field).
2. Add a class-level Javadoc note on `UpdateColumnRequestDTO` explaining a column update always
   requires a name (the DTO's only mutable property, no partial-update use case exists) — follow
   the precedent of `UpdateThemeRequestDTO`'s existing rule-6 exemption note.
3. **Correct** the now-inaccurate comment in `UpdateBoardRequestDTO.java` (currently claims "name
   is the only independently optional field, same as `UpdateColumnRequestDTO`") — it must stop
   asserting parity with Column, since Column deliberately does not follow that shape.
4. File a new `[minor]` todo (do not fix in this task — out of scope) noting that
   `UpdateBoardRequestDTO.name`'s own optionality (D-13) rests on the same unexamined assumption
   (no test or UI evidence justifies a version-only board update either) and may deserve the same
   scrutiny in a future task.

### Claude's Discretion

- Exact naming of the new composed annotation (e.g. `OptionalNotBlank` vs alternatives) and its
  exact package placement (`dto/annotation/`, matching existing siblings).
- Exact wording of the `UpdateColumnRequestDTO` class-level Javadoc and the correction to
  `UpdateBoardRequestDTO`'s comment.
- Exact wording/filing details of the new deferred todo about Board's own optionality.
- Whether `docs/CODE_STYLE.md` needs a new rule documenting the `OptionalNotBlank` pattern
  (follow existing convention: this codebase documents judgement-level annotation patterns there).

</decisions>

<specifics>
## Specific Ideas

- Reference implementation for the new annotation:
  `src/main/java/com/vrudenko/kanban_board/dto/annotation/BoardName.java` (composed-constraint
  shape) — reuse its `@Documented`/`@Target(FIELD)`/`@Retention(RUNTIME)`/`@Constraint(validatedBy = {})`
  skeleton.
- `docs/CODE_STYLE.md` rule 6 ("`Update*RequestDTO` carries a fixed shape") is the existing
  documented convention this task's Decision 2 write-up should align with / reference.
- Source todo file to close on completion:
  `.planning/todos/pending/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md`

</specifics>

<canonical_refs>
## Canonical References

- `.planning/todos/pending/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md`
  (source todo, full problem statement and empirical validation table)
- `docs/CODE_STYLE.md` rule 6 (`Update*RequestDTO` fixed-shape convention)

</canonical_refs>
