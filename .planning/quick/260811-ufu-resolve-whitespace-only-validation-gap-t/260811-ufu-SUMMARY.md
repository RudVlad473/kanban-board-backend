---
phase: quick-260811-ufu
plan: 01
subsystem: api
tags: [validation, bean-validation, dto, jakarta-validation]

requires: []
provides:
  - "com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank -- reusable composed constraint (composes @Pattern, zero hand-written ConstraintValidator) rejecting whitespace-only String values while leaving null (omitted optional field) untouched"
  - "Whitespace-only board name / task title / subtask title / signup display name now rejected with HTTP 400 -- previously reached persistence unchanged"
  - "UpdateColumnRequestDTO's deliberate NotBlank-and-mandatory asymmetry documented at the class level, ending its status as an unexplained outlier"
  - "docs/CODE_STYLE.md rule 12 -- the @OptionalNotBlank pattern and its one documented exception"
affects: [any future optional String field on a partial-update DTO, a later audit that would otherwise re-flag UpdateColumnRequestDTO.name as inconsistent]

actuals:
  tokens: 8000
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "An optional String field that must reject blank stacks @OptionalNotBlank alongside its existing composed annotation (@BoardName, @TaskTitle, @SubtaskTitle, @DisplayName) -- never replaces it"
    - "Composing @Pattern (not @NotBlank) inside a new constraint annotation is what preserves null-passthrough -- Bean Validation's built-in constraints treat null as valid, only @NotNull/@NotBlank/@NotEmpty reject it"
    - "@Pattern(regexp = \".*\\\\S.*\", flags = Pattern.Flag.DOTALL) is @NotBlank's blank-rejection semantics minus the null rejection -- DOTALL is required because @Pattern is a whole-string Matcher.matches() and an undotted '.' does not match a newline"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java
    - src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java
    - .planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md
  modified:
    - src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/UpdateSubtaskRequestDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/user_dto/SignupRequestDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java
    - src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
    - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
    - src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java
    - src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java
    - docs/CODE_STYLE.md
    - .planning/todos/pending/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md (moved to completed/, resolution note appended)
    - .planning/STATE.md

key-decisions:
  - "Rejected the source todo's own proposed @AssertTrue cross-check in favor of a composed @Pattern annotation -- @AssertTrue's violation property path keys on the method name, not the field, which would have degraded the errors.<field> RFC 7807 envelope quick task 260811-p9c had just converged"
  - "Regex form .*\\S.* with Pattern.Flag.DOTALL chosen over the source todo's illustrative \\S.* -- the latter is Matcher.matches() whole-string so it would reject a leading space (stricter than UpdateColumnRequestDTO's @NotBlank reference behavior) and reject any multi-line value outright without DOTALL"
  - "UpdateColumnRequestDTO.name kept mandatory (@NotBlank unchanged, byte-identical annotations) rather than made optional -- investigation found no test or mockup evidence of a version-only column update use case, unlike Task/Subtask which have a genuine second independently-editable field"
  - "UpdateBoardRequestDTO's own optionality was NOT re-examined in this task despite resting on the same kind of unexamined assumption -- deliberately carried forward as a new [minor] todo rather than unilaterally resolved, keeping this task's scope to the source todo's two named decisions"

patterns-established:
  - "A judgement-level annotation-composition pattern (rule 12) documents both its application sites and its one deliberate exception in the same place, so a future audit sees a documented answer instead of re-flagging an asymmetry"

requirements-completed: [UFU-01, UFU-02, UFU-03, UFU-04, UFU-05, UFU-06]

coverage:
  - id: D1
    description: "A whitespace-only value on UpdateBoardRequestDTO.name, UpdateTaskRequestDTO.title, UpdateSubtaskRequestDTO.title and SignupRequestDTO.displayName is rejected -- HTTP 400, not persisted"
    requirement: "UFU-02"
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java -- one @Nested group per DTO, shouldReturnOneViolationOn*_when*IsWhitespaceOnly"
        status: pass
      - kind: integration
        ref: "BoardControllerTest.UpdateById / TaskControllerTest.UpdateById / SubtaskControllerTest.UpdateById / AuthenticationTest.Signup.FieldValidation -- each gained a whitespace-only 400 case"
        status: pass
    human_judgment: false
  - id: D2
    description: "Omitting (null) any of those four fields still passes validation -- documented optionality not regressed"
    requirement: "UFU-02"
    verification:
      - kind: unit
        ref: "OptionalNotBlankTest -- shouldReturnNoViolations_when*IsNull per DTO group; SignupRequestDTOTest.whenDisplayNameIsMissing_thenNoViolation (pre-existing) still passes unchanged"
        status: pass
    human_judgment: false
  - id: D3
    description: "One reusable field annotation applied four times, not four per-DTO @AssertTrue methods"
    requirement: "UFU-01"
    verification:
      - kind: other
        ref: "src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java -- single artifact, zero ConstraintValidator implementations, applied at 4 field sites"
        status: pass
    human_judgment: false
  - id: D4
    description: "UpdateColumnRequestDTO.name still carries @NotBlank and still rejects null -- unchanged behavior, now documented"
    requirement: "UFU-03"
    verification:
      - kind: other
        ref: "git diff confirms only a class-level Javadoc was added -- field annotations byte-identical to pre-task state"
        status: pass
    human_judgment: false
  - id: D5
    description: "UpdateBoardRequestDTO's D-13 comment no longer claims shape parity with UpdateColumnRequestDTO"
    requirement: "UFU-04"
    verification:
      - kind: other
        ref: "src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java D-13 comment corrected"
        status: pass
    human_judgment: false
  - id: D6
    description: "Board's own version-only-update assumption filed as a new [minor] todo, not fixed here"
    requirement: "UFU-05"
    verification:
      - kind: other
        ref: ".planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md"
        status: pass
    human_judgment: false
  - id: D7
    description: "spotlessCheck and full test both pass, zero test shrinkage against the 417-test baseline"
    requirement: "UFU-06"
    verification:
      - kind: other
        ref: "./gradlew spotlessCheck && ./gradlew test -- 417->430 tests (+13), 0 failures, 0 errors"
        status: pass
    human_judgment: false

duration: ~51min
completed: 2026-08-11
status: complete
---

# Quick Task 260811-ufu: Resolve Whitespace-Only Validation Gap Todo (Major Severity) Summary

**Closed the whitespace-only validation gap on four DTO fields via a new reusable `@OptionalNotBlank` composed constraint annotation (composes `@Pattern`, zero hand-written `ConstraintValidator`), and documented `UpdateColumnRequestDTO`'s deliberate `@NotBlank`-and-mandatory asymmetry instead of "fixing" it.**

## Performance

- **Duration:** ~51 minutes
- **Completed:** 2026-08-11
- **Tasks:** 3 (tracer, batched expansion, doc/todo closure)
- **Files modified:** 14 (3 created, 11 modified, including `.planning/` artifacts)

## Accomplishments

- Created `com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank`, built like the codebase's existing composed-constraint siblings (`BoardName`, `DisplayName`, etc.): `@Constraint(validatedBy = {})` composing a single built-in `@Pattern(regexp = ".*\S.*", flags = Pattern.Flag.DOTALL)`, no hand-written `ConstraintValidator`. The `@Pattern` choice (not `@NotBlank`) is what preserves null-passthrough — Bean Validation's built-in constraints treat `null` as valid.
- Applied it as a tracer on `UpdateBoardRequestDTO.name` first (Task 1), RED-first at both the validator tier and the HTTP tier, before expanding to the remaining three fields.
- Batched the expansion (Task 2) to `UpdateTaskRequestDTO.title`, `UpdateSubtaskRequestDTO.title`, `SignupRequestDTO.displayName` — again RED-first: 6 new tests (3 validator, 3 HTTP) all reproduced the defect (0 violations / HTTP 200-200-201 on whitespace-only) before the annotations landed, then went GREEN.
- Rejected the source todo's own proposed `@AssertTrue` cross-check design: its violation property path would key on the method name, not the field, degrading the `errors.<field>` envelope quick task 260811-p9c had just converged.
- Kept `UpdateColumnRequestDTO.name` byte-identical (verified via `git diff` — only a class-level Javadoc was added, zero annotation changes) and added a class-level exemption note (precedent: `UpdateThemeRequestDTO`) explaining why a version-only column update has no use case, unlike Task/Subtask's genuine two-field partial-update need.
- Corrected `UpdateBoardRequestDTO`'s D-13 comment, which incorrectly claimed shape parity with `UpdateColumnRequestDTO`; the two DTOs now deliberately diverge and the comment says so.
- Added `docs/CODE_STYLE.md` rule 12 documenting the `@OptionalNotBlank` pattern, its four application sites, and its one documented exception.
- Filed a new `[minor]` todo carrying forward `UpdateBoardRequestDTO.name`'s own optionality as an open question — it rests on the same "no test, no mockup evidence" gap D-02 declined to resolve for the sibling column DTO, deliberately left unfixed to keep this task's scope to the source todo's two named decisions.
- Closed the source todo with a full resolution note covering both decisions, why the `@AssertTrue` alternative was rejected, and what was carried forward.
- Measured the full suite before/after: 417→430 tests (+13, zero shrinkage), `spotlessCheck` and `test` both green.

## Task Commits

1. **Task 1 (tracer — `@OptionalNotBlank` end-to-end on `UpdateBoardRequestDTO.name`):** `103d453` (`feat`)
2. **Task 2 (batched expansion — Task/Subtask/Signup):** `31d7955` (`feat`)
3. **Task 3 (document Column asymmetry, correct Board comment, file deferred todo, close out):** `82a3c6a` (`docs`)

## Files Created/Modified

See `key-files` in frontmatter for the full list. Highlights: one new annotation (`OptionalNotBlank.java`) and its validator-tier test class (`OptionalNotBlankTest.java`, one `@Nested` group per DTO); 4 DTOs gained the annotation, 1 DTO (`UpdateColumnRequestDTO`) gained an explanatory class Javadoc with zero behavior change; 4 test classes each gained one HTTP-tier regression test; `docs/CODE_STYLE.md` gained rule 12; the source todo moved from `pending/` to `completed/` with a resolution note; one new `[minor]` todo filed.

## Decisions Made

See `key-decisions` in frontmatter.

## Deviations from Plan

None — plan executed exactly as written. The plan's own `<approach_analysis>` had already resolved the regex-form and null-passthrough trade-offs before implementation began, so no in-flight design decision was needed beyond what the plan specified.

## Issues Encountered

- **Pre-commit hook timeouts on this Windows sandbox.** Each of the three commits ran `spotlessCheck` + `fastTest` (~2-5 min) inside the `.githooks/pre-commit` hook. The first commit attempt was backgrounded with a 2-minute default timeout and killed mid-run by an unrelated `./gradlew --stop` call issued while it was still executing, leaving a Gradle daemon file-lock error on retry (`Unable to delete directory ...\fastTest\binary`) — resolved by stopping all daemons cleanly (`./gradlew --stop` run alone, not concurrently with another gradle invocation) and retrying. Subsequent commits ran to completion in the foreground with generous timeouts (up to 10 minutes) and no interference, matching the pattern documented in `docs/SESSION_LESSONS.md` and prior sessions' summaries (e.g. 260811-s5e, 260811-qru) — commit-time hooks in this repo genuinely take several minutes and must be given room to finish rather than raced.
- No destructive git operation was used at any point; no test was weakened or skipped to work around the timeout.

## Known Stubs

None.

## Threat Flags

None beyond what the plan's own `<threat_model>` already covers (T-ufu-01 through T-ufu-04, T-ufu-SC) — every threat there was disposed within this quick task's own scope: T-ufu-01 (the whitespace-blank defect itself) closed by `@OptionalNotBlank` on all four fields with HTTP-tier regression proof; T-ufu-02 (ReDoS via the composed `@Pattern`) mitigated by the linear-backtracking regex analysis in `<approach_analysis>`, not assumed; T-ufu-03 (information disclosure via the generic violation message) accepted, no new information disclosed relative to existing per-field constraints; T-ufu-04 (violation-count interaction with existing exact-count assertions) mitigated — pre-surveyed before planning, and the full suite run (417→430, zero shrinkage) is the actual proof no existing assertion broke; T-ufu-SC (package legitimacy) not applicable — no dependency added, removed, or version-changed.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- The whitespace-only validation gap is closed on all four fields the source todo named; `UpdateColumnRequestDTO`'s asymmetry is now a documented decision, not an open question.
- One new `[minor]` todo (`UpdateBoardRequestDTO.name`'s own optionality) is filed and does not block any subsequent phase work — it is independent of Phase 5 (Infra Migration).
- No blockers for subsequent phase work; this was a standalone quick task.

---
*Quick task: 260811-ufu*
*Completed: 2026-08-11*

## Self-Check: PASSED

- FOUND: `src/main/java/com/vrudenko/kanban_board/dto/annotation/OptionalNotBlank.java`
- FOUND: `src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java`
- FOUND: `.planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md`
- FOUND: `.planning/todos/completed/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md`
- FOUND: `.planning/quick/260811-ufu-resolve-whitespace-only-validation-gap-t/260811-ufu-SUMMARY.md`
- FOUND commit `103d453` (Task 1)
- FOUND commit `31d7955` (Task 2)
- FOUND commit `82a3c6a` (Task 3)
