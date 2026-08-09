---
phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
plan: 05
subsystem: testing
tags: [junit5, mockmvc, restassured, spring-boot-test, refactor]

# Dependency graph
requires:
  - phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n (plan 01)
    provides: "AbstractAppMockMvcTest fixture base + signinCookie() helper, support/ package relocation"
provides:
  - "BoardFullReadE2ETest and SubtaskLockingE2ETest converted to the in-process MockMvc tier (D-03 verdict-table rows 10 and 13)"
  - "Confirmation that BoardCreationE2ETest (D-03 row 9, KEEP) remains untouched on the real-socket tier — the real-HTTP fixture base's one remaining subclass"
affects: [07-07]

# Actuals (#2632)
actuals:
  tokens: 8175
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Real POST /signin through AbstractAppMockMvcTest.signinCookie() + explicit .cookie(cookie) relay on every subsequent mockMvc.perform() call, never .with(user()) — required for classes proving the actual authentication call site, not just an authenticated-request shortcut"

key-files:
  created: []
  modified:
    - src/test/java/com/vrudenko/kanban_board/BoardFullReadE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/SubtaskLockingE2ETest.java

key-decisions:
  - "Preserved each class's existing assertion style verbatim (Approach B from the plan's trade-off matrix) rather than normalizing to DTO-deserialization or JSON string comparison — a diff review shows only dispatch-mechanism changes, no assertion-predicate changes"
  - "Did not touch BoardCreationE2ETest — confirmed via git diff --stat showing zero changes, and its full test suite (9 tests across 5 @Nested groups, including ConcurrentCreate) still green at the real-socket tier"

patterns-established: []

requirements-completed: [TEST-03]

coverage:
  - id: D1
    description: "BoardFullReadE2ETest downgraded from real-socket RestAssured/RANDOM_PORT to in-process MockMvc, all 8 tests passing with assertion predicates unchanged"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*BoardFullReadE2ETest' — 8/8 pass (GetFullBoard: 6, FlatEquivalence: 2)"
        status: pass
    human_judgment: false
  - id: D2
    description: "SubtaskLockingE2ETest downgraded from real-socket RestAssured/RANDOM_PORT to in-process MockMvc, all 4 optimistic-locking tests passing with assertion predicates unchanged"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*SubtaskLockingE2ETest' — 4/4 pass (UpdateById)"
        status: pass
      - kind: e2e
        ref: "./gradlew test --tests '*SubtaskControllerTest' — still green, unaffected by this plan"
        status: pass
    human_judgment: false
  - id: D3
    description: "BoardCreationE2ETest (D-03 KEEP verdict) confirmed untouched and still green at the real-socket tier"
    verification:
      - kind: other
        ref: "git diff --stat da3eb8b..HEAD -- src/test/java/com/vrudenko/kanban_board/BoardCreationE2ETest.java — zero output (unmodified)"
        status: pass
      - kind: e2e
        ref: "./gradlew test --tests '*BoardCreationE2ETest' — 9/9 pass (5 @Nested groups including ConcurrentCreate)"
        status: pass
    human_judgment: false

# Metrics
duration: 48min
completed: 2026-08-09
status: complete
---

# Phase 7 Plan 5: BoardFullReadE2ETest and SubtaskLockingE2ETest Tier Downgrade Summary

**Converted BoardFullReadE2ETest and SubtaskLockingE2ETest from the real-socket RestAssured/RANDOM_PORT tier to the in-process @SpringBootTest + MockMvc tier, with zero assertion changes and zero production-code touches.**

## Performance

- **Duration:** 48 min
- **Started:** 2026-08-09T14:37:00Z (approx, first file reads)
- **Completed:** 2026-08-09T15:25:20Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- `BoardFullReadE2ETest` now extends `AbstractAppMockMvcTest` instead of `AbstractAppE2ETest`, declares plain `@SpringBootTest` + `@AutoConfigureMockMvc` instead of `@SpringBootTest(webEnvironment = RANDOM_PORT)`, and imports no RestAssured — all 8 tests (`GetFullBoard`: 6, `FlatEquivalence`: 2) pass with every assertion predicate — nested-document field checks, empty-array cases, cross-user isolation with response-body content assertion, not-found handling, and the flat-vs-nested field-by-field/order equivalence checks — preserved exactly (D-03 row 10, TEST-03)
- `SubtaskLockingE2ETest` converted the same way — all 4 optimistic-locking tests in `UpdateById` pass, preserving the current-version success case, the stale-version conflict-plus-unchanged-state re-read, the missing-version bad-request case, and the cross-user unauthorized-before-version-check case (D-03 row 13, TEST-03)
- Both classes authenticate via `AbstractAppMockMvcTest.signinCookie()` — a real `POST /signin` through `mockMvc.perform()` with the resulting cookie relayed by hand into every subsequent `perform()` call — never the `.with(user())` shortcut, so the real `AuthenticationController.authenticate` call site stays exercised
- `BoardCreationE2ETest` (D-03 row 9, KEEP) confirmed untouched: `git diff --stat` against the pre-plan commit shows zero changes, and its full 9-test suite across 5 `@Nested` groups (including the two-real-thread `ConcurrentCreate` race) still passes at the real-socket tier — the real-HTTP fixture base (`AbstractAppE2ETest`) now has exactly one remaining subclass, as the plan's objective anticipated
- `SubtaskControllerTest` (the existing MockMvc-tier sibling) re-verified green and unaffected
- Zero production-code changes: `git status --porcelain src/main/` is empty

## Task Commits

Each task was committed atomically:

1. **Task 1: Convert BoardFullReadE2ETest to the in-process tier** - `669e712` (refactor)
2. **Task 2: Convert SubtaskLockingE2ETest to the in-process tier and confirm BoardCreationE2ETest is untouched** - `68a9225` (refactor)

**Plan metadata:** (pending — final docs commit follows this SUMMARY, owned by the orchestrator per wave protocol)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/BoardFullReadE2ETest.java` - Superclass switched `AbstractAppE2ETest` → `AbstractAppMockMvcTest`; `@SpringBootTest(webEnvironment = RANDOM_PORT)` → `@SpringBootTest` + `@AutoConfigureMockMvc`; every `given()...when()...then()` RestAssured call replaced with `mockMvc.perform(get(url).cookie(cookie))`; `response.as(X.class)` replaced with `objectMapper.readValue(response.getResponse().getContentAsString(), X.class)`; `response.asString()` replaced with `response.getResponse().getContentAsString()`; `Pair<String,String> cookie = signin()` replaced with `Cookie cookie = signinCookie()`
- `src/test/java/com/vrudenko/kanban_board/SubtaskLockingE2ETest.java` - Same conversion shape applied to the subtask-locking sequential PUT/PUT optimistic-lock flow, including the intermediate GET re-read used to prove the stale write left state unchanged

## Decisions Made

- Preserved assertion strictness and style exactly per the plan's Approach B (chosen over normalizing to DTO round-trip comparison) — no test predicate was loosened to make the conversion pass
- Left `BoardCreationE2ETest` completely alone per D-03's KEEP verdict for its genuinely-concurrent `ConcurrentCreate` group; RESEARCH.md's Assumption A1 (whether MockMvc supports safe concurrent dispatch) was not investigated, per the plan's explicit instruction to leave it unresolved

## Deviations from Plan

None - plan executed exactly as written. Both files converted per their `<action>` instructions with no scope changes; `BoardCreationE2ETest` verified untouched as required.

## Issues Encountered

- The pre-commit hook's `fastTest` run competes for a shared Gradle daemon with the 4 sibling plans (07-02, 07-03, 07-04, 07-06) executing concurrently in their own worktrees. Two commit attempts failed with "Gradle build daemon has been stopped: stop command received" — a sibling worktree's own daemon-recovery activity (per the precedent noted in 07-01-SUMMARY.md) killed the daemon mid-build. Both times, an unmodified retry of the same `git commit` succeeded once daemon contention cleared. No code change required; consistent with `docs/SESSION_LESSONS.md`'s existing guidance on this repo's build/git hygiene under concurrent execution.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- D-03 verdict-table rows 10 and 13 are executed; combined with plan 07-01's proof of row 18 (`ColumnLockingE2ETest`), 3 of the 13 DOWNGRADE-verdict classes are now converted
- The real-HTTP fixture base (`AbstractAppE2ETest`) has exactly one remaining subclass (`BoardCreationE2ETest`) after this plan, as intended — it is not a leftover and should not be deleted or folded into its last subclass
- No blockers for remaining phase-07 plans (02, 03, 04, 06) — this plan's two files were exclusive to this plan's `files_modified` and did not intersect with sibling wave-2 plans

---
*Phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n*
*Completed: 2026-08-09*
