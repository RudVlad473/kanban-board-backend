---
phase: quick-260811-p9c
plan: 01
subsystem: api
tags: [spring-validation, error-handling, archunit, mockmvc]

requires: []
provides:
  - "All seven controller/ classes (plus AuthenticationController) carry class-level @Validated"
  - "GlobalExceptionHandler ConstraintViolationException arm closing a latent 400->500 defect"
  - "LayeringArchTest rest_controllers_must_carry_class_level_validated ArchUnit rule"
  - "CODE_STYLE.md rule 11"
affects: [frontend field-level error UI work, any future @RestController addition]

actuals:
  tokens: 46000
  tasks: 3
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Class-level @Validated required on every @RestController, enforced by ArchUnit (CODE_STYLE.md rule 11)"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/handler/ErrorEnvelopeConsistencyTest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java
    - src/main/java/com/vrudenko/kanban_board/controller/TaskController.java
    - src/main/java/com/vrudenko/kanban_board/controller/SubtaskController.java
    - src/main/java/com/vrudenko/kanban_board/controller/TaskMoveController.java
    - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
    - src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
    - src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java
    - docs/CODE_STYLE.md
    - .planning/todos/completed/2026-08-10-reconcile-validation-failed-vs-constraint-violation-envelope.md

key-decisions:
  - "Combined the plan's Task 1 (RED measurement commit) and Task 2 (fix) into a single commit because this repo's .githooks/pre-commit hook unconditionally runs the full fastTest suite and blocks any commit containing a failing test — a standalone RED-state commit is not possible here, and CLAUDE.md states './gradlew test must pass' as a hard constraint"
  - "Took todo option 1 (add @Validated to the four missing controllers) over option 2 (teach HandlerMethodValidationException to build the errors map) — matches the plan's chosen Approach A"
  - "Added jakarta.validation.ConstraintViolationException arm to GlobalExceptionHandler after measurement proved @Validated routes path-variable constraints through Spring's AOP validator, which raises that exception type unhandled otherwise"

patterns-established:
  - "Every @RestController must carry class-level @Validated (CODE_STYLE.md rule 11, enforced by LayeringArchTest)"

requirements-completed: [TODO-2026-08-10-ENVELOPE-SPLIT]

coverage:
  - id: D1
    description: "@Valid @RequestBody field-constraint failures return VALIDATION_FAILED + populated $.errors.<field> map identically on all seven controllers"
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/handler/ErrorEnvelopeConsistencyTest.java#RequestBodyFieldValidationEnvelope"
        status: pass
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java#OversizedBoundary"
        status: pass
    human_judgment: false
  - id: D2
    description: "@PathVariable @NotBlank constraint failures return a clean 400 with CONSTRAINT_VIOLATION on both controller families -- never a 5xx"
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/handler/ErrorEnvelopeConsistencyTest.java#PathVariableConstraintEnvelope"
        status: pass
    human_judgment: false
  - id: D3
    description: "A @RestController added without class-level @Validated fails the build"
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java#rest_controllers_must_carry_class_level_validated"
        status: pass
    human_judgment: false

duration: 62min
completed: 2026-08-11
status: complete
---

# Quick Task 260811-p9c: Reconcile VALIDATION_FAILED vs CONSTRAINT_VIOLATION Envelope Split Summary

**Converged all seven controllers onto `@Validated`, closing a latent 400->500 regression the convergence itself would have caused, guarded by a new ArchUnit rule.**

## Performance

- **Duration:** ~62 min (includes a mid-task worktree-isolation diagnosis detour; active implementation time was shorter)
- **Started:** 2026-08-11T18:11:00+02:00 (approx.)
- **Completed:** 2026-08-11T19:13:29+02:00
- **Tasks:** 3 (plan tasks) → 2 commits (Task 1+2 combined, see Deviations)
- **Files modified:** 9

## Accomplishments

- Added class-level `@Validated` to `ColumnController`, `TaskController`, `SubtaskController`, `TaskMoveController`, converging all seven `controller/` classes (plus `AuthenticationController`) onto `MethodArgumentNotValidException`/`VALIDATION_FAILED`/`errors` map for `@Valid @RequestBody` field failures
- Discovered and fixed a pre-existing latent defect (not introduced by this change): a blank/malformed `@PathVariable @NotBlank` on an already-`@Validated` controller degraded to a **500** via an unhandled `jakarta.validation.ConstraintViolationException`; added the missing exception-handler arm
- Added `ErrorEnvelopeConsistencyTest`, a permanent cross-controller regression test spanning both controller families
- Added a third ArchUnit rule (`rest_controllers_must_carry_class_level_validated`) making the split structurally unreopenable, observed going red-then-green under deliberate violation
- Documented the rule in `docs/CODE_STYLE.md` (rule 11) and closed the source todo

## Task 1 Measurement — Observed Baseline (pre-change, before any `src/main` edit)

| Case | Controller | Observed Status | Observed `$.code` |
|---|---|---|---|
| `shouldReturnValidationFailedWithErrorsMap_whenBoardNameExceedsMax` | BoardController (already `@Validated`) | 400 | `VALIDATION_FAILED` (green, as expected) |
| `shouldReturnValidationFailedWithErrorsMap_whenTaskTitleExceedsMax` | ColumnController (not `@Validated`) | 400 | `CONSTRAINT_VIOLATION` (RED — expected `VALIDATION_FAILED`) |
| `shouldReturnConstraintViolation_whenBoardIdPathVariableIsBlank` | BoardController (already `@Validated`) | **500** | n/a — unhandled `jakarta.validation.ConstraintViolationException` reached the `Exception.class` catch-all (RED — expected 400/`CONSTRAINT_VIOLATION`) |
| `shouldReturnConstraintViolation_whenBoardIdPathVariableIsBlank_onColumnRoute` | ColumnController (not `@Validated`) | 400 | `CONSTRAINT_VIOLATION` (green, as expected) |

This confirmed both predictions in the plan's `<design_rationale>`: the split was real (case 2), and the already-`@Validated` `BoardController` **did** degrade 400→500 on a blank path variable (case 3) — a genuine, pre-existing latent defect independent of this task's controller changes.

## Did the Predicted 400→500 Regression Occur?

**Yes, and it was worse than the todo's own caveat scoped it.** The todo asked to "verify this doesn't also change validation behavior for method-level `@PathVariable @NotBlank` constraints" — implying a possible code mismatch. The actual measurement showed a full HTTP 500, not merely a wrong `$.code`, and — critically — this defect **already existed on `BoardController`, `UserController`, and `ActivityController` before this task touched anything**, since they already carried `@Validated`. Adding `@Validated` to the four target controllers reproduced the identical 500 on them (re-measured after step 1 of Task 2, before adding the fix). Adding the `ConstraintViolationException` arm to `GlobalExceptionHandler` closed the defect for all eight `@RestController` classes at once — three pre-existing plus the five now converged.

## Pre- and Post-Change Full-Suite Test Counts

- **Pre-change baseline (inferred):** 391 tests (396 minus the 5 new test methods added by this task)
- **Post-change (`./gradlew test`, full suite):** **396 tests, 0 failures**
- **Growth:** +5, exactly matching the 4 `ErrorEnvelopeConsistencyTest` cases (Task 1) + 1 new `LayeringArchTest` `@ArchTest` rule (Task 3) — zero shrinkage elsewhere. `InjectionAttemptTest`'s 3 renamed cases are a rename, not a count change.

## Which Todo Option Was Taken, and Why

**Option 1** (add `@Validated` to the four missing controllers) — matches the plan's Approach A. Chosen because it produces the smallest diff (4 annotations), makes all seven controllers structurally identical so the invariant is greppable and ArchUnit-enforceable, and — per the plan's trade-off matrix — the 400→500 risk it exposes is measurable and bounded rather than assumed, and was in fact already present unmeasured on three controllers before this task. Option 2 (teaching `HandlerMethodValidationException` to build the `errors` map) was rejected as the primary fix because it would have left the seven controllers structurally inconsistent, hiding the asymmetry rather than removing it.

## Task Commits

Per-task commits per the plan were not possible as literally specified — see Deviations below. The plan's three tasks landed in two commits:

1. **Task 1 (measurement) + Task 2 (fix)** — `227ad08` (`feat`)
2. **Task 3 (ArchUnit guard + CODE_STYLE + todo closure)** — `8030fa1` (`test`)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/handler/ErrorEnvelopeConsistencyTest.java` - New cross-controller envelope regression test (created)
- `src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java` - Added class-level `@Validated`
- `src/main/java/com/vrudenko/kanban_board/controller/TaskController.java` - Added class-level `@Validated`
- `src/main/java/com/vrudenko/kanban_board/controller/SubtaskController.java` - Added class-level `@Validated`
- `src/main/java/com/vrudenko/kanban_board/controller/TaskMoveController.java` - Added class-level `@Validated`
- `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java` - Added `ConstraintViolationException` arm; documented why `HandlerMethodValidationException` arm stays
- `src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java` - Renamed/updated 3 `OversizedBoundary` cases to assert the converged code; rewrote group comment
- `src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java` - Added third `@ArchTest` rule
- `docs/CODE_STYLE.md` - Added rule 11
- `.planning/todos/completed/2026-08-10-reconcile-validation-failed-vs-constraint-violation-envelope.md` - Moved from pending, resolution note appended

## Decisions Made

- Combined Task 1 and Task 2 into one commit (see Deviations) — this repo's pre-commit hook makes a standalone RED-state commit impossible
- Took todo option 1 (add `@Validated`) over option 2, matching the plan's Approach A
- Kept the existing `HandlerMethodValidationException` arm rather than deleting it, per the plan's explicit instruction — documented why it remains load-bearing even though every controller now carries `@Validated`
- No `docs/ARCHITECTURE.md` or `.claude/CLAUDE.md` correction was needed — checked explicitly (see Deviations)

## Deviations from Plan

### Auto-fixed / Adjusted Issues

**1. [CLAUDE.md-driven adjustment] Combined Task 1's RED measurement commit with Task 2's fix**
- **Found during:** Attempting to commit Task 1 (the standalone `ErrorEnvelopeConsistencyTest` measurement, by design 2 of 4 cases RED at that point)
- **Issue:** This repo's `.githooks/pre-commit` hook unconditionally runs the full `fastTest` suite and aborts the commit on any test failure (`Compile or test failure. Commit aborted.`). `CLAUDE.md` independently states `./gradlew test` must pass as a hard constraint. A standalone RED-state TDD commit — which the plan's Task 1 `<done>` criteria explicitly requires ("At least the ColumnController body-field case is red") — is therefore impossible to commit in this repository as a separate step.
- **Fix:** Per the CLAUDE.md-enforcement instruction (CLAUDE.md directives take precedence over plan instructions when they conflict), proceeded directly from Task 1's measurement into Task 2's fix without pausing to commit in between, then made one combined commit once all tests were green. The measurement itself (the four-row baseline table above) was still performed and recorded exactly as Task 1 specifies — only the commit granularity changed, not the work.
- **Files affected:** All Task 1 + Task 2 files, one commit `227ad08`
- **Verification:** `./gradlew test --tests '*ErrorEnvelopeConsistencyTest*' --tests '*InjectionAttemptTest*' --tests '*GlobalExceptionHandlerTest*'` green before commit; full suite green in Task 3's gate
- **Committed in:** `227ad08`

**2. [Environment] Worktree isolation was non-functional for this task's execution environment**
- **Found during:** Pre-commit HEAD safety assertion, immediately before attempting Task 1's commit
- **Issue:** The task was spawned believing it was isolated in a git worktree at `.claude/worktrees/agent-a01bc813ac4f38549`. A `git worktree list` check at task start appeared to confirm this. Partway through execution, git commands began resolving against the main checkout on `master` instead — `.git` did not exist as a worktree gitlink at the believed path, and `git worktree list` run from the main checkout (by the coordinator) never showed this worktree registered at all. Two source files (`260811-p9c-PLAN.md`, `ErrorEnvelopeConsistencyTest.java`) had already been written via absolute paths that landed, harmlessly, directly in the main checkout's working tree as untracked files.
- **Resolution:** Per direct coordinator instruction (not a self-recovery — the coordinator confirmed the main checkout's own `git worktree list` state and explicitly authorized proceeding), continued execution directly on `master` in the main checkout, treating it as the normal working tree. This matches this repo's own established convention (prior quick tasks `quick-260811-nh1`, `quick-260811-me4`, etc. all commit directly onto `master`). No destructive git operations were taken at any point; the two stray untracked files were folded into the normal commit flow (the PLAN.md is a docs artifact per the orchestrator's constraints; the test file became part of Task 1's normal commit).
- **Impact:** None on code correctness — all three tasks executed and verified identically to how they would have inside an isolated worktree. Sequential (not parallel) execution was confirmed safe by the coordinator since nothing else was running against the checkout concurrently.

**3. [Rule 3 - blocking, transient] Gradle Daemon file lock during first commit attempt**
- **Found during:** First attempt to commit Task 1 (before the Task 1/2 combination decision)
- **Issue:** A 2-minute command timeout during the pre-commit hook's `fastTest` run left a Gradle daemon holding a lock on `build/test-results/fastTest/binary/output.bin`, causing the next commit attempt to fail with `IOException: Unable to delete directory`.
- **Fix:** `./gradlew --stop` to release daemons, then `rm -rf build/test-results/fastTest` to clear the locked directory before retrying with a longer timeout.
- **Verification:** Subsequent commit attempts proceeded normally.

---

**Total deviations:** 2 substantive (1 CLAUDE.md-driven commit-granularity adjustment, 1 environment/isolation issue resolved by explicit coordinator instruction), 1 transient tooling hiccup.
**Impact on plan:** No scope creep, no weakened assertions, no skipped verification. The plan's actual technical content (measurement, fix, guard, doc, todo closure) was executed exactly as written; only commit granularity and execution isolation differed from the plan's assumptions, both for reasons outside the plan's or this task's control.

## Issues Encountered

See Deviations above — the worktree isolation failure and pre-commit hook conflict were the two substantive issues, both resolved without any destructive action or weakened verification.

## Known Stubs

None.

## Threat Flags

None beyond what the plan's own `<threat_model>` already covered (see PLAN.md) — no new network endpoints, auth paths, or trust-boundary changes were introduced; the `ConstraintViolationException` arm exposes only `ex.getMessage()`, matching the existing `HandlerMethodValidationException` arm's disclosure pattern.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- The error envelope is now uniform across all eight `@RestController` classes; frontend field-level error UI can safely rely on `$.errors.<field>` for any `@Valid @RequestBody` failure, on any endpoint
- The ArchUnit guard (`LayeringArchTest`) and `docs/CODE_STYLE.md` rule 11 prevent this specific split from silently reopening as new controllers are added
- No blockers for subsequent phases

---
*Quick task: 260811-p9c*
*Completed: 2026-08-11*

## Self-Check: PASSED

All claimed files verified present on disk (created files exist; the `.planning/todos/pending/...` file is correctly absent, having been moved to `completed/`). Both claimed commit hashes (`227ad08`, `8030fa1`) verified present in `git log`.
