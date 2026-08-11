---
phase: quick-260811-qru
plan: 01
subsystem: api
tags: [bean-validation, archunit, dto, controller-testing, spring-web]

requires: []
provides:
  - "FINDINGS.md: exhaustive binding + validation-annotation + coverage audit across all 8 controllers / 14 request DTOs"
  - "LayeringArchTest.mutating_handlers_must_bind_request_dto_parameters_from_the_body ArchUnit rule"
  - "SaveColumnRequestDTO.name message-text fix (F-04), regression-tested"
  - "5 filed todos for design forks and test-quality gaps found by the audit"
affects: [any future @RestController/RequestDTO addition, whoever picks up the 5 filed todos, whoever revisits Update*RequestDTO optionality semantics]

actuals:
  tokens: 20000
  tasks: 3
  commits: 5

tech-stack:
  added: []
  patterns:
    - "Every @PostMapping/@PutMapping/@PatchMapping handler's *RequestDTO parameter must carry both @RequestBody and @Valid, enforced by ArchUnit (LayeringArchTest, 4th rule)"

key-files:
  created:
    - .planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-FINDINGS.md
    - .planning/todos/pending/2026-08-11-taskcontrollertest-updateby-blank-title-test-uses-wrong-dt.md
    - .planning/todos/pending/2026-08-11-subtaskcontrollertest-updateby-blank-title-test-uses-wrong.md
    - .planning/todos/pending/2026-08-11-subtasktitle-composed-annotation-carries-wrong-message-cons.md
    - .planning/todos/pending/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md
    - .planning/todos/pending/2026-08-11-delete-dead-deleteboardbyidrequestdto-class.md
  modified:
    - src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/SaveColumnRequestDTO.java
    - src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
    - .planning/todos/completed/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md

key-decisions:
  - "Gated on operator approval of all 10 proposed dispositions before touching any source file (Approach A from PLAN.md's trade-off matrix), exactly as the source todo required"
  - "Verified 2 structurally-identical message-constant mismatches (SaveColumnRequestDTO, SubtaskTitle) empirically with a throwaway jakarta.validation.Validator harness rather than trusting the annotations at face value -- one turned out live (fixed, F-04), the other confirmed dead code via @ReportAsSingleViolation (filed, not fixed, F-05)"
  - "Did not add @NotBlank to the 4 DTOs found accepting whitespace-only values (F-06) -- doing so would also reject null, silently breaking the documented optional/omission semantics those fields carry; filed as a design-fork todo instead of guessing"
  - "Did not delete DeleteBoardByIdRequestDTO in this plan despite confirming it dead -- fails Task 2's FIX-NOW eligibility test (deleting a file is not 'a single annotation change'); filed instead (F-10)"

patterns-established:
  - "A composed custom validation annotation's inner constraint message can be dead code if the annotation carries @ReportAsSingleViolation -- verify empirically (Validator.validate(...).getMessage()) before treating a message-constant mismatch as a live client-visible bug"

requirements-completed: [QRU-01, QRU-02, QRU-03, QRU-04]

coverage:
  - id: D1
    description: "Every mutating handler's *RequestDTO parameter carries both @RequestBody and @Valid, permanently enforced"
    requirement: "QRU-01"
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java#mutating_handlers_must_bind_request_dto_parameters_from_the_body"
        status: pass
    human_judgment: false
  - id: D2
    description: "SaveColumnRequestDTO.name returns the column-specific validation message, not the board-flavored one"
    requirement: "QRU-02"
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java#AddColumnByBoardId.testWithAuthenticatedUser_shouldReturnColumnSpecificMessage_whenNameIsTooShort"
        status: pass
    human_judgment: false
  - id: D3
    description: "Every finding in the audit carries exactly one disposition from the closed set, with a resulting artifact"
    requirement: "QRU-04"
    verification: []
    human_judgment: true
    rationale: "The findings register's completeness and the quality of each written NO-ACTION/FILE-TODO reasoning is a judgment call a human should skim, not something an automated check can certify beyond the row-count parity already verified in Task 2's <verify> block"

duration: "~50 min for Tasks 2-3 (post-approval); Task 1's audit ran in a prior session turn"
completed: 2026-08-11
status: complete
---

# Quick Task 260811-qru: Audit DTO and Controller Test Coverage for Validation/Binding Blind Spots Summary

**Systematic audit of all 8 controllers / 14 request DTOs found the binding failure mode (260811-me4) does not recur anywhere, fixed one confirmed-live wrong-message-text bug, and filed 5 todos for genuine design forks rather than guessing at them.**

## Performance

- **Duration:** ~50 min for Tasks 2-3 (ArchUnit guard, F-04 fix + regression test, 5 todos filed, source todo closed, full gate); Task 1 (the three-part audit itself, producing FINDINGS.md) ran in a prior session turn before the human-verify checkpoint
- **Completed:** 2026-08-11T20:11:11+02:00
- **Tasks:** 3 (Task 1: audit; Task 2: guard + fix + todos; Task 3: full gate + close source todo)
- **Files modified:** 9 (4 src, 5 new todos, 1 closed todo, plus FINDINGS.md/STATE.md/SUMMARY.md left for the orchestrator per this session's explicit instruction)

## Accomplishments

- Enumerated all 13 mutating (`@PostMapping`/`@PutMapping`/`@PatchMapping`) DTO-carrying handlers across all 8 `@RestController` classes (including `AuthenticationController` in `security/`) directly from source — zero binding findings, confirming the 260811-me4 defect class is isolated, not systemic
- Added a 4th `LayeringArchTest` ArchUnit rule making that binding failure mode structurally unreopenable — teeth-checked in the working tree (removed `@RequestBody`, then `@Valid`, from a handler; both correctly failed the build and named the offending method; restored before committing, no red state committed)
- Field-by-field validation-annotation audit across all 14 `Save*RequestDTO`/`Update*RequestDTO` classes resolved every composed custom annotation to its actual runtime effect — found and fixed a confirmed-live message-text bug (`SaveColumnRequestDTO`), found and correctly did NOT fix a structurally-identical but confirmed-dead one (`SubtaskTitle`, superseded by `@ReportAsSingleViolation`), both verified empirically with a throwaway `jakarta.validation.Validator` harness rather than assumed from reading the annotations
- Confirmed a genuine, live validation gap with the same harness: whitespace-only values pass on 4 of 5 examined optional name/title fields; filed as a design-fork todo rather than guessed at
- Coverage classification confirmed all 13 mutating endpoints have real HTTP-JSON-body test coverage, just not always inside a dedicated `controller/*ControllerTest.java` class — matched, not newly discovered, the planning inventory's own prediction
- Closed the source todo with a full Resolution section; full suite 396→398 tests, 0 shrinkage

## Task Commits

Each disposed finding's artifact was committed atomically:

1. **Task 2, step 1-2 (ArchUnit guard):** `55216b2` (`test`)
2. **Task 2, step 3 (F-04 fix + regression test):** `46753da` (`fix`)
3. **Task 2, step 4 (5 todos filed):** `b042a9a` (`docs`)
4. **Task 3 (close source todo, part 1 — rename only, see Issues Encountered):** `20f6f11` (`docs`)
5. **Task 3 (close source todo, part 2 — Resolution content):** `f4a5a7b` (`docs`)

**Not committed, per this session's explicit instruction:** `260811-qru-FINDINGS.md`, `.planning/STATE.md`, this `260811-qru-SUMMARY.md` — left as prepared, on-disk content for the orchestrator's own final docs commit.

## Files Created/Modified

- `.planning/quick/260811-qru-audit-dto-and-controller-test-coverage-f/260811-qru-FINDINGS.md` - Full audit: binding table, test-side binding audit, validation-annotation table, coverage table, 10-row findings register with actual outcomes/artifacts (uncommitted, left for orchestrator)
- `src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java` - 4th ArchUnit rule: mutating handlers must bind `*RequestDTO` params with `@RequestBody` + `@Valid`
- `src/main/java/com/vrudenko/kanban_board/dto/column_dto/SaveColumnRequestDTO.java` - Fixed `@Size` message constant (F-04)
- `src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java` - Added regression test for F-04
- `.planning/todos/pending/2026-08-11-taskcontrollertest-updateby-blank-title-test-uses-wrong-dt.md` - New (F-02)
- `.planning/todos/pending/2026-08-11-subtaskcontrollertest-updateby-blank-title-test-uses-wrong.md` - New (F-03)
- `.planning/todos/pending/2026-08-11-subtasktitle-composed-annotation-carries-wrong-message-cons.md` - New (F-05)
- `.planning/todos/pending/2026-08-11-whitespace-only-name-title-values-pass-validation-on-4-of-5.md` - New (F-06)
- `.planning/todos/pending/2026-08-11-delete-dead-deleteboardbyidrequestdto-class.md` - New (F-10)
- `.planning/todos/completed/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md` - Moved from pending/, Resolution section appended
- `.planning/STATE.md` - Pending Todos updated (1 removed, 5 added), Quick Tasks Completed row added, decision entry added (uncommitted, left for orchestrator)

## Decisions Made

- Gated on operator approval of all 10 proposed dispositions before touching any source file, exactly as the source todo's own scoping sentence required ("this todo is the audit, not a blank check to fix everything found")
- Verified both message-constant mismatches (F-04, F-05) empirically with a throwaway `Validator` harness before deciding FIX-NOW vs. FILE-TODO — this correctly separated a live client-visible bug from a structurally-identical but dead one, rather than treating both the same from source inspection alone
- Did not add `@NotBlank` to the 4 DTOs found accepting whitespace-only values (F-06) — that would also reject `null`, silently breaking each field's documented optional/omission semantics; filed as a design-fork todo instead of picking an answer unilaterally
- Did not delete the confirmed-dead `DeleteBoardByIdRequestDTO` (F-10) — fails Task 2's FIX-NOW eligibility test (deleting a file is not "a single annotation change"); filed instead

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed a staging-pathspec mistake that silently dropped the todo's Resolution content from its own commit**
- **Found during:** Task 3, closing the source todo
- **Issue:** `git add .planning/todos/completed/....md .planning/todos/pending/....md` (the second path already renamed away by an earlier `git mv`) errored with "pathspec did not match any files" and aborted the whole `git add` before staging anything new — so the subsequent commit captured only the pure rename (0 insertions/deletions) even though its message described adding a Resolution section.
- **Fix:** Re-ran `git add` with only the valid (post-rename) path, then made a second, explicit follow-up commit adding the actual Resolution content, with a message explaining the correction.
- **Files modified:** `.planning/todos/completed/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md`
- **Committed in:** `f4a5a7b` (follows `20f6f11`)

---

**Total deviations:** 1 auto-fixed (git-tooling mistake, self-corrected with a new commit rather than an amend, per this session's git-hygiene instructions).
**Impact on plan:** None on the audit's substance — the Resolution content itself was written correctly the first time; only its staging/commit mechanics needed a follow-up commit.

## Issues Encountered

- **Pre-commit hook timeouts.** `.githooks/pre-commit` runs `spotlessApply` + `fastTest` (~4-5 min) on every commit; several early commit attempts exceeded a 2-minute default Bash timeout and were retried with longer timeouts or backgrounded-and-polled. One retry hit a transient Windows file-lock (`Unable to delete directory build/test-results/fastTest/binary`) from a prior killed Gradle process — resolved with `./gradlew --stop` + removing the locked directory, matching the same transient issue 260811-p9c's summary documents.
- **Git-staging pathspec mistake** — see Deviations above.

Neither issue required a destructive git operation, weakened verification, or changed the audit's actual findings.

## Known Stubs

None.

## Threat Flags

None. This plan's own `<threat_model>` (T-qru-01..04) is addressed entirely within its own dispositions: T-qru-01 (missing `@RequestBody`) is now guarded by the new ArchUnit rule; T-qru-02 (unvalidated fields) is partially closed (F-04 fixed) and partially tracked (F-01 confirmed-existing, F-06 filed); T-qru-03 and T-qru-04 were already accepted/mitigated by prior work and re-confirmed, not re-opened.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- The binding failure mode that motivated this audit cannot silently recur — the new ArchUnit rule fails the build on any future handler missing `@RequestBody`/`@Valid` on a `*RequestDTO` parameter
- 5 todos are now in `.planning/todos/pending/` for whoever wants to pick up the remaining design forks (whitespace-only-value handling across Update DTOs is the one with the widest blast radius — it touches 4 DTOs and needs an explicit decision, not a mechanical fix)
- `SaveSubtaskRequestDTO.title`'s pre-existing missing-`@NotBlank` gap (F-01) remains open under its own, already-filed todo — untouched by this plan, as instructed
- No blockers for subsequent phase work; this was a standalone audit quick task

---
*Quick task: 260811-qru*
*Completed: 2026-08-11*

## Self-Check: PASSED

All 5 new todo files verified present in `.planning/todos/pending/`. The closed source todo verified present in `.planning/todos/completed/` and absent from `.planning/todos/pending/`. All 5 claimed commit hashes (`55216b2`, `46753da`, `b042a9a`, `20f6f11`, `f4a5a7b`) verified present in `git log`. `LayeringArchTest` verified green (4/4 tests) after the teeth-check restore. Full suite verified at 398 tests, 0 failures, 0 errors.
