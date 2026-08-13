---
phase: quick-260813-euo
plan: 01
subsystem: testing
tags: [bean-validation, mockmvc, jpa, dead-code, flaky-test]

requires:
  - phase: 260811-ufu
    provides: "@OptionalNotBlank on UpdateTaskRequestDTO.title/UpdateSubtaskRequestDTO.title, which already covers the whitespace-only case both wrong-DTO todos mused about retargeting to"
provides:
  - "TaskControllerTest.UpdateById and SubtaskControllerTest.UpdateById's \"data is invalid\" tests now genuinely exercise @TaskTitle/@SubtaskTitle's @Size(min=3), proven by falsification"
  - "SubtaskTitle's composing @Size names the correct SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE constant, with a test pinning why the mismatch was inert"
  - "DeleteBoardByIdRequestDTO and TaskService.deleteAllByColumnId (plus their orphaned test coverage) removed; the cascade-delete property they guarded migrated to ColumnServiceTest.DeleteByIdTest"
  - "GlobalExceptionHandlerTest.AccessDeniedTest is deterministic against the RFC 7807 \"about:blank\" collision, with leak coverage widened rather than narrowed"
affects: [update-time title validation regression coverage, TaskServiceTest/ColumnServiceTest test placement, GlobalExceptionHandlerTest reliability]

actuals:
  tokens: 389206
  tasks: 6
  commits: 5

tech-stack:
  added: []
  patterns:
    - "Falsification as teeth-proof for rewritten tests: temporarily remove the constraint annotation, confirm red, restore, confirm green and zero net src/main diff — applied to Tasks 1-3"
    - "FK-constraint-as-assertion: a column delete completing without throwing is itself proof its tasks were cascaded, since fk_tasks_column carries no ON DELETE CASCADE (Task 4/D-03)"
    - "Fixed literal value + field-scoped leak check, not narrowed assertion scope, to de-flake a test whose randomness collided with unrelated boilerplate (Task 5/D-05)"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java
  modified:
    - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
    - src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java
    - src/main/java/com/vrudenko/kanban_board/dto/annotation/SubtaskTitle.java
    - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
    - src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java
    - src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java
    - src/test/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandlerTest.java
  deleted:
    - src/main/java/com/vrudenko/kanban_board/dto/board_dto/DeleteBoardByIdRequestDTO.java

key-decisions:
  - "D-01 (locked, plan-time): rewrote both wrong-DTO tests around a too-short (2-char) title violating @Size(min=3), not the whitespace-only case the source todos suggested retargeting to — that case was already covered by a sibling shouldReturnBadRequest_whenTitleIsWhitespaceOnly test in each nested class (260811-ufu's @OptionalNotBlank), so retargeting would have moved the duplicate-coverage bug rather than fixed it"
  - "D-02/D-03: deleted TaskService.deleteAllByColumnId and its now-superseded query-count test (re-verified ColumnServiceTest already proves the same no-scaling property, more strongly, through the live column-delete entry point) but migrated (not dropped) the one assertion that was not duplicated anywhere else — the cascade-delete check — into ColumnServiceTest.DeleteByIdTest, using the fk_tasks_column FK constraint's absence of ON DELETE CASCADE as the proof mechanism"
  - "D-04: fixed SubtaskTitle's message constant AND added a falsified regression test pinning why the mismatch was inert (@ReportAsSingleViolation), diverging from the source todo's own \"no meaningful test\" framing"
  - "D-05: de-flaked GlobalExceptionHandlerTest.AccessDeniedTest with a fixed \"about\" board name plus a field-scoped leak check (pin type, check every other field), rejecting the source todo's own suggestion to narrow the assertion to the detail field alone — that would have silently dropped title/instance/future-field leak coverage"

requirements-completed: [TODO-260811-TCT, TODO-260811-SCT, TODO-260811-SUBTITLE, TODO-260811-DELDTO, TODO-260811-DEADSVC, TODO-260812-FLAKE]

coverage:
  - id: D1
    description: "TaskControllerTest/SubtaskControllerTest UpdateById's invalid-data tests fail if @TaskTitle/@SubtaskTitle is removed"
    requirement: "TODO-260811-TCT, TODO-260811-SCT"
    verification:
      - kind: integration
        ref: "controller/TaskControllerTest.java#UpdateById (falsified: red with @TaskTitle removed, green restored)"
        status: pass
      - kind: integration
        ref: "controller/SubtaskControllerTest.java#UpdateById (falsified: red with @SubtaskTitle removed, green restored)"
        status: pass
    human_judgment: false
  - id: D2
    description: "SubtaskTitle's composing @Size names the correct message constant, and its inertness is pinned by a test"
    requirement: "TODO-260811-SUBTITLE"
    verification:
      - kind: unit
        ref: "dto/SubtaskTitleMessageTest.java (falsified: red with @ReportAsSingleViolation removed, green restored)"
        status: pass
    human_judgment: false
  - id: D3
    description: "DeleteBoardByIdRequestDTO and TaskService.deleteAllByColumnId no longer exist; project compiles; guarded properties remain guarded"
    requirement: "TODO-260811-DELDTO, TODO-260811-DEADSVC"
    verification:
      - kind: unit
        ref: "grep -rn 'DeleteBoardByIdRequestDTO|deleteAllByColumnId' src/main src/test (zero hits)"
        status: pass
      - kind: unit
        ref: "service/ColumnServiceTest.java#DeleteByIdTest (query-count property + new cascade-delete assertion)"
        status: pass
    human_judgment: false
  - id: D4
    description: "GlobalExceptionHandlerTest.AccessDeniedTest is deterministic and its leak coverage is no narrower than before"
    requirement: "TODO-260812-FLAKE"
    verification:
      - kind: integration
        ref: "handler/GlobalExceptionHandlerTest.java#AccessDeniedTest (fixed \"about\" board name, field-scoped leak check)"
        status: pass
    human_judgment: false

duration: 47min
completed: 2026-08-13
status: complete
---

# Quick Task 260813-euo: DTO/Dead-Code Cleanup Bundle Summary

**Closed six independently-confirmed audit findings in one pass: two wrong-DTO test bugs that left update-time title validation untested, one wrong message constant, two dead code symbols, and one flaky test.**

## Performance

- **Duration:** ~47 min
- **Completed:** 2026-08-13
- **Tasks:** 6
- **Files modified:** 7 production/test files, 1 new test file, 1 deleted production file, plus 6 todo files moved pending → completed

## Accomplishments

- `TaskControllerTest.UpdateById` and `SubtaskControllerTest.UpdateById`'s "data is invalid" tests now build from `Update*RequestDTO` (not `Save*RequestDTO`) with a valid version and a too-short title, so the 400 they assert is actually attributable to `@TaskTitle`/`@SubtaskTitle`'s `@Size(min=3)` — previously it was a missing-`version` `@NotNull` trip, unrelated to title validation entirely. Each rewrite was falsified (constraint temporarily removed, observed red, restored, observed green).
- `SubtaskTitle`'s composing `@Size` now names `SUBTASK_TITLE_LENGTH_VALIDATION_MESSAGE` instead of the board-name-flavored constant it carried before — a new `SubtaskTitleMessageTest` pins the `@ReportAsSingleViolation` interaction that made the old mismatch inert (never client-visible), so a future removal of that meta-annotation is caught.
- Deleted `DeleteBoardByIdRequestDTO` (zero references — board deletion binds a bare `@PathVariable`, never had a request body) and `TaskService.deleteAllByColumnId` (zero production callers). The N+1/query-count property the deleted method's test guarded is already proven, more strongly, by `ColumnServiceTest`'s existing live-entry-point test; the cascade-delete property was migrated (not dropped) into a new `ColumnServiceTest.DeleteByIdTest` case using the `fk_tasks_column` foreign-key constraint's absence of `ON DELETE CASCADE` as the proof mechanism.
- `GlobalExceptionHandlerTest.AccessDeniedTest` no longer flakes against RFC 7807's literal `"about:blank"` boilerplate — now uses a fixed `"about"` board name (the exact collision value) plus a field-scoped leak check that pins `type` and checks every other response field, rather than narrowing the assertion to the `detail` field alone as the source todo suggested (which would have silently dropped leak coverage on `title`/`instance`/future fields).
- All 6 source todos closed with `## Resolution` sections recording what was actually done and where it diverged from the todo's own suggestion.

## Task Commits

1. **Task 1: Fix TaskControllerTest.UpdateById's wrong-DTO test and prove it has teeth**
   - `72908cd` — rewrote the test around `UpdateTaskRequestDTO` + 2-char title; falsified (removed `@TaskTitle`, observed red, restored, observed green, zero net `src/main` diff)
2. **Task 2: Apply the same fix to SubtaskControllerTest.UpdateById**
   - `989564a` — same shape against `UpdateSubtaskRequestDTO`; `isCompleted` deliberately left unset so `@Size` stays the payload's sole violation; falsified identically
3. **Task 3: Correct SubtaskTitle's message constant and pin why it is inert**
   - `912cb18` — fixed the constant; added `SubtaskTitleMessageTest`; falsified via temporary `@ReportAsSingleViolation` removal
4. **Task 4: Remove both dead symbols, preserving every property their tests guarded**
   - `ca6f8b6` — re-verified zero callers by fresh grep; deleted `DeleteBoardByIdRequestDTO` and `TaskService.deleteAllByColumnId`; deleted the superseded query-count test; migrated the cascade-delete assertion into `ColumnServiceTest.DeleteByIdTest`
5. **Task 5: Make GlobalExceptionHandlerTest.AccessDeniedTest deterministic**
   - `158c9e4` — fixed board name to `"about"`; replaced raw-body substring check with a field-scoped leak check pinning `type`
6. **Task 6: Full gate, close the six todos, record state**
   - Docs-only (SUMMARY.md, STATE.md, todo moves) — not committed by this executor per quick-task convention; folded into the orchestrator's own docs commit after a worktree-cleanup complication (see Deviations).

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/dto/SubtaskTitleMessageTest.java` — new, pins `@ReportAsSingleViolation`'s inertness effect
- `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java` — `UpdateById`'s invalid-data test rewritten
- `src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java` — same rewrite, subtask side
- `src/main/java/com/vrudenko/kanban_board/dto/annotation/SubtaskTitle.java` — corrected message constant
- `src/main/java/com/vrudenko/kanban_board/service/TaskService.java` — `deleteAllByColumnId` deleted
- `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java` — `DeleteAllByColumnIdQueryCountTest` deleted, `DeleteAllByColumnIdTest.shouldDeleteAll_whenTasksExist` migrated out
- `src/test/java/com/vrudenko/kanban_board/service/ColumnServiceTest.java` — new cascade-delete assertion in `DeleteByIdTest`
- `src/test/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandlerTest.java` — `AccessDeniedTest` de-flaked
- `src/main/java/com/vrudenko/kanban_board/dto/board_dto/DeleteBoardByIdRequestDTO.java` — deleted
- 6 todo files moved `.planning/todos/pending/` → `.planning/todos/completed/`, each with a `## Resolution` section

## Decisions Made

See `key-decisions` in frontmatter (D-01 through D-05) — each matches the plan's own design-decision section, executed as designed with no unplanned forks.

## Deviations from Plan

- **Test-count arithmetic came out at 440, not the plan's expected 436.** Reconciled explicitly rather than accepted at face value: 435 (STATE.md's stated baseline) + 4 (an unrelated Phase 5 `ActuatorHealthE2ETest` class that landed in an intervening commit, `8a0747d`, after that baseline was recorded but before this task's worktree forked) + 1 (this task's own net delta: `SubtaskTitleMessageTest` +1, the deleted query-count test −1, the migrated cascade test +0) = 440 exactly.
- **This SUMMARY.md's original content was lost after the orchestrator's worktree cleanup and had to be reconstructed** from the executor's final agent-report text (visible in the orchestrating session) plus the plan file and the completed todos' own `## Resolution` sections, rather than copied verbatim from the executor's original file. All facts below (commits, gate result, file list) were cross-checked against `git log` and the actual repository state, not merely recalled.
- **Docker Desktop was not running at dispatch time**, causing the orchestrator's pre-dispatch `git commit` (for `PLAN.md`) to fail on the pre-commit hook's Testcontainers-backed `fastTest` run; restarted Docker Desktop and retried before the executor was spawned.
- A Windows Gradle-daemon file lock appeared after a Bash-tool timeout on the first commit attempt inside the worktree, and `jacocoTestCoverageVerification` produced false failures on every scoped `--tests` run — both documented as environment friction, not code defects, and worked around (poll-until-idle for the lock; `-x jacocoTestCoverageVerification` for scoped falsification runs, with the full unscoped gate still exercising and passing the ratchet).

## Issues Encountered

- See Deviations above — the Docker-down pre-dispatch failure, the stale-lock/lock-file cleanup, and the JaCoCo scoped-run false-failure were all environment/tooling friction encountered during orchestration and worktree execution, not defects in the changed code.

## Measured Full-Gate Numbers

- **Test count:** 435 → 440 (+5 net: +1 `SubtaskTitleMessageTest`, −1 `DeleteAllByColumnIdQueryCountTest`, +0 migrated cascade test, +4 unrelated Phase 5 `ActuatorHealthE2ETest` tests from an intervening commit), zero failures, zero errors.
- **Gate:** `./gradlew spotlessCheck test` — BUILD SUCCESSFUL in 5m 35s, JaCoCo ratchet passed (INSTRUCTION/LINE ≥ 0.90, BRANCH ≥ 0.75).
- **`git diff --stat src/main`** confined to exactly the plan's predicted scope: `SubtaskTitle.java`'s message constant, `TaskService.java`'s deleted method, and `DeleteBoardByIdRequestDTO.java`'s deletion — no other production file touched.

## Known Stubs

None.

## Next Phase Readiness

- All 6 source todos closed; no follow-up todos filed by this task.
- No blockers for any other in-flight work (this task's file scope — DTO/service/test files — is disjoint from the concurrently in-progress Phase 5 infra work).

---
*Phase: quick-260813-euo*
*Completed: 2026-08-13*

## Self-Check: PASSED

All 9 claimed key files found on disk (`git show HEAD --stat`); all 5 claimed commit hashes (`72908cd`, `989564a`, `912cb18`, `ca6f8b6`, `158c9e4`) found in git history on master; all 6 claimed todo files found under `.planning/todos/completed/` each carrying a `## Resolution` section; `./gradlew spotlessCheck test` reconfirmed green at 440 tests post-merge.
