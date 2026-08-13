---
phase: quick-260813-i6r
plan: 01
subsystem: test-suite
tags: [testing, dto-validation, code-style-rule-4, subtask]
status: complete

dependency-graph:
  requires: []
  provides:
    - "SaveSubtaskRequestDTO.title's full blank-title boundary matrix, split correctly across tiers"
  affects:
    - src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java
    - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java

tech-stack:
  added: []
  patterns:
    - "docs/CODE_STYLE.md rule 4 worked example: DTO-tier input-combination coverage vs. one controller-tier HTTP-contract representative"

key-files:
  created: []
  modified:
    - src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java
    - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java

decisions:
  - "D-01: Kept whenJsonBodyIsEmpty ({}) as the single controller-tier representative; the {}/null distinction is not observed separately at HTTP since Jackson deserializes both to title == null."
  - "D-02: Moved cases land in SubtaskTitleMessageTest's existing SaveSubtaskRequestDTOTest nested class, reusing its harness verbatim."
  - "D-03: Empty-string case asserts on the set of triggered constraint annotation-type simple names (NotBlank + SubtaskTitle) plus property-path == 'title', not on message text or hasSize(1), since both constraints render the identical message string."
  - "D-04: Added before deleting; one commit covers both halves of the move, per repo precedent (260813-h2f) that the pre-commit hook's fastTest gate forbids a standalone red-only commit."

actuals:
  tokens: 9200
  tasks: 3
  commits: 1

metrics:
  duration: ~35min
  completed: 2026-08-13
---

# Quick Task 260813-i6r: Move TaskControllerTest.AddSubtaskByTaskId blank-title matrix to the DTO tier Summary

Moved the `SaveSubtaskRequestDTO.title` blank-title boundary matrix (null, whitespace-only,
empty-string) from `TaskControllerTest.AddSubtaskByTaskId` (controller/HTTP tier) down to
`SubtaskTitleMessageTest.SaveSubtaskRequestDTOTest` (DTO/Bean-Validation tier), closing the one
place in the suite that contradicted `docs/CODE_STYLE.md` rule 4's tier-purpose split.

## What Was Built

**Task 1 (DTO tier, add + falsify):** Added three tests to
`SubtaskTitleMessageTest.SaveSubtaskRequestDTOTest`, reusing its existing harness (`Validator`
field, `Assertions.` fully-qualified, `should<Outcome>_when<Condition>` naming, `// arrange` /
`// act` / `// assert` markers). Added a `whitespaceOnlyTitle()` helper beside the existing
`overLongTitle()`, deriving its length from `ValidationConstants.MIN_SUBTASK_TITLE_LENGTH` (3)
rather than hardcoding spaces. Widened the class-level Javadoc's opening sentence to describe the
file as covering `SubtaskTitle`'s constraint behavior generally, not only the length-constraint
message.

**Task 2 (controller tier, delete):** Removed the three now-redundant tests
(`whenTitleIsNull`, `whenTitleIsWhitespaceOnly`, `whenTitleIsEmptyString`) from
`TaskControllerTest.AddSubtaskByTaskId`, along with the three-line explanatory comment inside the
empty-string one. Kept `testWithAuthenticatedUser_shouldReturnBadRequest_whenJsonBodyIsEmpty`
byte-identical, including both its `jsonPath` assertions (`$.code` and `$.errors.title`). Added a
one-line comment above the kept test pointing to `SubtaskTitleMessageTest.SaveSubtaskRequestDTOTest`
as the home of the rest of the matrix. Ran `./gradlew spotlessApply` to let the formatter settle
line-wrapping on both files.

**Task 3 (gate + single commit):** Ran the full suite (`spotlessCheck cleanTest test`), confirmed
the exact-equality invariant, checked pending todos for a match (none), and made one commit
covering both halves of the move.

## Observed Violation Counts and Constraint Types (not predicted — actually run)

All four `SaveSubtaskRequestDTO.title` inputs matched the plan's predictions exactly:

| Input | Violation count | Constraint(s) that fire | Message |
|---|---|---|---|
| over-long (33 chars) | 1 | `SubtaskTitle` | "Subtask title cannot be empty" |
| `null` | 1 | `NotBlank` | "Subtask title cannot be empty" |
| whitespace-only (3 spaces, passes `@Size`) | 1 | `NotBlank` | "Subtask title cannot be empty" |
| `""` (empty string) | 2 | `NotBlank` + `SubtaskTitle` | both render "Subtask title cannot be empty" (identical string, hence D-03's annotation-type-based assertion instead of message equality) |

Every violation's property path was confirmed to render as `title` (asserted for the empty-string
case, the only one with 2 violations to distinguish).

## Falsification Result (Task 1)

Temporarily removed `@NotBlank(message = "Subtask title cannot be empty")` from
`SaveSubtaskRequestDTO.title` (kept `@SubtaskTitle`), re-ran `SubtaskTitleMessageTest`:

- `shouldReturnOneViolation_..._whenTitleIsNull` — **FAILED** (0 violations; `@Size` passes on null, and with `@NotBlank` gone nothing else fires)
- `shouldReturnOneViolation_..._whenTitleIsWhitespaceOnly` — **FAILED** (0 violations; same reason)
- `shouldTriggerBothNotBlankAndSubtaskTitle_whenTitleIsEmptyString` — **FAILED** (dropped from 2 violations to 1, `SubtaskTitle` only)

All three reds matched the plan's predicted shape exactly. Restored via
`git checkout -- src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/SaveSubtaskRequestDTO.java`,
re-ran green, confirmed `git diff --quiet src/main` exits 0 (zero net `src/main` diff in the
committed work).

## Before/After Test Counts Per Nested Class

| Nested class | Before | After |
|---|---|---|
| `SubtaskTitleMessageTest.SaveSubtaskRequestDTOTest` | 1 | 4 |
| `SubtaskTitleMessageTest.UpdateSubtaskRequestDTOTest` | 1 (unchanged, byte-identical source) | 1 |
| `TaskControllerTest.AddSubtaskByTaskId` | 6 | 3 |

Full suite: **444 tests, 0 failures** both before and after — net-zero, as designed (3 tests left
the controller tier, 3 arrived at the DTO tier).

## Pending Todo Check

Searched `.planning/todos/pending/` for a todo matching this finding. **No match found.** As the
plan anticipated, the triage that produced this quick task was ad hoc (a side-finding from a prior
audit), so there was no standing todo to close.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `containsExactlyInAnyOrder` generic-capture compile error on `Class<? extends Annotation>`**
- **Found during:** Task 1, first compile of the new empty-string test.
- **Issue:** `violation.getConstraintDescriptor().getAnnotation().annotationType()` returns
  `Class<? extends Annotation>`. Passing two concrete `Class<X>` literals to AssertJ's
  `containsExactlyInAnyOrder(ELEMENT...)` after `.extracting(...)` failed to compile — the
  wildcard-capture type variable (`CAP#1`) from the extractor's inferred `Class<?>` element type
  cannot accept a concrete `Class<NotBlank>`/`Class<SubtaskTitle>` varargs array in this AssertJ
  version, a known Java generics/varargs interaction, not a logic bug in the test's intent.
- **Fix:** Changed the extractor to map to `annotationType().getSimpleName()` (a `String`) instead
  of the `Class` object itself, and compared against `NotBlank.class.getSimpleName()` /
  `SubtaskTitle.class.getSimpleName()`. This still asserts exactly what D-03 requires — the set of
  triggered constraint annotation *types* — without the generics friction, and reads at least as
  clearly at the call site.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java`
- **Commit:** `d997f1a` (folded into the single commit per D-04; the intermediate cast-based
  attempt that also failed was never itself committed).

### Plan-verification note (not a deviation, informational)

Task 2's documented verify command
(`grep -Ec 'testWithAuthenticatedUser_shouldReturnBadRequest_whenTitleIs(Null|WhitespaceOnly|EmptyString)'`)
returns **1**, not the expected 0, when run against the whole file. This is a false positive: the
match is `TaskControllerTest.UpdateById.testWithAuthenticatedUser_shouldReturnBadRequest_whenTitleIsWhitespaceOnly`
(line 311), a pre-existing, unrelated test asserting `UpdateTaskRequestDTO`'s whitespace-only
*task* title — not a leftover subtask-title duplicate. Verified directly that
`AddSubtaskByTaskId` itself holds exactly 3 tests (happy path, query-param-no-body,
empty-JSON-body), matching the plan's actual intent; the grep pattern in the plan was simply not
scoped to the `AddSubtaskByTaskId` nested class. No code change was needed or made.

## Known Stubs

None.

## Threat Flags

None — no new surface introduced; production code (`SaveSubtaskRequestDTO.java`) was touched only
transiently for falsification and restored byte-identical (`git diff --quiet src/main` confirmed).

## Self-Check: PASSED

- `src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java` — FOUND, modified as described.
- `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java` — FOUND, modified as described.
- Commit `d997f1a` — FOUND in `git log`.
