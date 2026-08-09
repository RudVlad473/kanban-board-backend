---
phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
plan: 03
subsystem: testing
tags: [junit5, mockmvc, testcontainers, spring-boot-test, restassured, refactor]

# Dependency graph
requires:
  - phase: 07-01
    provides: "AbstractAppMockMvcTest fixture base + signinCookie() helper, proven working by ColumnLockingE2ETest"
provides:
  - "ColumnDeletionE2ETest and ColumnOrderingE2ETest converted from RestAssured/RANDOM_PORT to the in-process MockMvc tier (D-03 verdict-table rows 11-12)"
affects: [07-02, 07-04, 07-05, 07-06]

# Actuals (#2632)
actuals:
  tokens: 7165
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Column-resource E2E tests now use AbstractAppMockMvcTest.signinCookie() + mockMvc.perform(...).cookie(cookie) instead of RestAssured given().cookie(...)"

key-files:
  created: []
  modified:
    - src/test/java/com/vrudenko/kanban_board/ColumnDeletionE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/ColumnOrderingE2ETest.java

key-decisions:
  - "Kept both classes in their original root package and file location — D-03 downgrades in-place, does not move or merge (that is D-02 Candidate 2, explicitly deferred until all Column-family downgrades land)"
  - "ColumnOrderingE2ETest signs in once per test method (never more than once), so the two-session-ceiling caveat the plan flagged never came into play"

patterns-established: []

requirements-completed: [TEST-03]

coverage:
  - id: D1
    description: "ColumnDeletionE2ETest runs at the in-process MockMvc tier with all 4 original @Test methods and assertions preserved one-for-one"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*ColumnDeletionE2ETest' — 4/4 pass"
        status: pass
    human_judgment: false
  - id: D2
    description: "ColumnOrderingE2ETest runs at the in-process MockMvc tier with all 8 original @Test methods and position-invariant assertions preserved one-for-one; sibling ColumnControllerTest unaffected"
    requirement: "TEST-03"
    verification:
      - kind: e2e
        ref: "./gradlew test --tests '*ColumnOrderingE2ETest' --tests '*ColumnControllerTest' — all pass"
        status: pass
    human_judgment: false

# Metrics
duration: 55min
completed: 2026-08-09
status: complete
---

# Phase 7 Plan 3: Column E2E Tier Downgrade Summary

**Converted ColumnDeletionE2ETest (4 tests) and ColumnOrderingE2ETest (8 tests) from the real-socket RestAssured/RANDOM_PORT tier to the in-process @SpringBootTest + MockMvc tier, preserving every assertion and the real signin+cookie-relay authentication path.**

## Performance

- **Duration:** ~55 min (includes three pre-commit hook retries caused by a stray/contended Gradle daemon — see Issues Encountered)
- **Started:** 2026-08-09T13:00:00Z (approx, first file reads)
- **Completed:** 2026-08-09T13:22:43Z (SUMMARY authoring); last task commit at 2026-08-09T15:22:30+02:00 local
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- `ColumnDeletionE2ETest` converted: superclass changed from `AbstractAppE2ETest` to `AbstractAppMockMvcTest`, `@SpringBootTest(webEnvironment = RANDOM_PORT)` replaced with plain `@SpringBootTest` + `@AutoConfigureMockMvc`, every `given().cookie(...).when().delete(url)` call translated to `mockMvc.perform(delete(url).cookie(cookie))`. All 4 `@Test` methods (non-empty cascade delete, empty-column delete, cross-user rejection, not-found) preserved with identical assertions, including the post-delete repository reads proving the cascade reached tasks and subtasks.
- `ColumnOrderingE2ETest` converted the same way, plus its three private HTTP-builder helpers (`createColumnOnBoard`, `createTaskInColumn`, and the reorder/delete call sites) rewritten from RestAssured's `given()...when()...then()` chain to `mockMvc.perform(...)` + manual JSON (de)serialization via the autowired `ObjectMapper`. All 8 `@Test` methods across `ColumnCreation`, `Reorder`, and `DeleteById` nested groups preserved, including the position invariants read back through `ColumnRepository`/`TaskRepository` between sequential steps.
- Both classes authenticate via `AbstractAppMockMvcTest.signinCookie()` — a real `POST /signin` through `mockMvc.perform()` — never the `.with(user())` shortcut, keeping the real authentication path under test per the plan's threat model (T-07-11).
- `ColumnOrderingE2ETest` signs in exactly once per test method (never twice), so the plan's two-session-ceiling caution never had to be exercised.
- Zero production-code changes: `git status --porcelain src/main/` confirmed empty after both tasks.
- `ColumnControllerTest` (the untouched sibling controller test) still passes, confirming the conversion didn't disturb shared Spring context wiring.

## Task Commits

Each task was committed atomically:

1. **Task 1: Convert ColumnDeletionE2ETest to the in-process tier** - `881b9d8` (refactor)
2. **Task 2: Convert ColumnOrderingE2ETest to the in-process tier** - `65cbbc9` (refactor)

**Plan metadata:** (pending — final docs commit follows this SUMMARY; per parallel-execution instructions, STATE.md/ROADMAP.md are NOT touched here — the orchestrator updates those centrally after Wave 2)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/ColumnDeletionE2ETest.java` - RestAssured/RANDOM_PORT → MockMvc/AbstractAppMockMvcTest; 4 `@Test` methods unchanged
- `src/test/java/com/vrudenko/kanban_board/ColumnOrderingE2ETest.java` - RestAssured/RANDOM_PORT → MockMvc/AbstractAppMockMvcTest; 8 `@Test` methods unchanged

## Decisions Made

- Left both files in their original root-package location — this plan's scope note explicitly forbids merging into `ColumnControllerTest` (D-02 Candidate 2 is conditional and deferred) or renaming/moving either class
- Used `AbstractAppMockMvcTest.signinCookie()` uniformly rather than any per-test cookie caching, matching the `ColumnLockingE2ETest` reference conversion from plan 07-01

## Deviations from Plan

None — plan executed exactly as written. Both classes converted mechanically per the `ColumnLockingE2ETest` reference pattern; no bugs, missing functionality, or blocking issues were found in either target file.

## Conversion Surprise Worth Warning Plans 04-06 About

- **Pre-commit hook / Gradle daemon contention under parallel execution:** this plan runs as one of 4 concurrent worktree-isolated executors (07-02, 07-03, 07-04, 07-05, 07-06 in Wave 2), each triggering the repo's `fastTest` pre-commit hook on every commit. Both commits in this plan hit `Unable to delete directory '...\build\test-results\fastTest\binary'` (a stray/contended Gradle daemon holding a file lock, same root cause documented in 07-01-SUMMARY.md) and one commit was additionally killed mid-run by an external `./gradlew --stop` (Gradle daemons are shared across the machine by version+JVM-args key, so a stop issued by any concurrent process can kill another worktree's in-flight daemon). Both resolved on retry with no code change required. Plans 04-06 running in the same wave should expect the same contention and should retry the commit (with an extended timeout) rather than treat a first-attempt hook failure as a real test failure — inspect the failure message for "Unable to delete directory" or "Gradle build daemon has been stopped" before assuming a genuine regression.
- **No `/api` context-path surprises this time:** both target files already built their URLs from bare `ApiPaths` constants (`getColumnUrl`, `getReorderUrl`, `getBoardColumnsUrl` never referenced `CONTEXT_PATH`), so unlike some hypothetical downgrade candidates, no URL prefix had to be stripped — the existing helper methods worked unchanged under MockMvc.

## Issues Encountered

- Pre-commit hook (`.githooks/pre-commit`, which runs `spotlessCheck` + `fastTest`) failed twice with `Unable to delete directory '...\build\test-results\fastTest\binary'` — a stale Gradle daemon from a previous timed-out attempt holding a file lock, same class of issue documented in `07-01-SUMMARY.md`'s Issues Encountered. Resolved by running `./gradlew --stop` and retrying. One retry attempt was itself killed by an externally-issued `Gradle build daemon has been stopped: stop command received` message — most likely a sibling worktree agent's own `--stop` call landing on a shared daemon, an artifact of Wave 2's parallel execution rather than a defect in this plan's changes. The third retry succeeded cleanly (`BUILD SUCCESSFUL in 6m 19s`). No code change was required for either commit; both are tooling/environment notes only, consistent with `docs/SESSION_LESSONS.md`.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- D-03 verdict-table rows 11 and 12 executed; `ColumnDeletionE2ETest` and `ColumnOrderingE2ETest` now stand alongside `ColumnLockingE2ETest` (converted in 07-01) as three MockMvc-tier Column E2E classes, all extending `AbstractAppMockMvcTest`
- D-02 Candidate 2 (merging the Column E2E family into `ColumnControllerTest`) remains explicitly out of scope for this phase per the plan's own scope note — deferred, not executed
- No blockers for sibling plans 07-04/07-05/07-06; each targets disjoint files

---
*Phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n*
*Completed: 2026-08-09*

## Self-Check: PASSED

- Both modified files confirmed present on disk with expected content (`ColumnDeletionE2ETest.java`, `ColumnOrderingE2ETest.java`)
- Both task commit hashes (`881b9d8`, `65cbbc9`) confirmed present in `git log --oneline`
- `git status --porcelain` confirmed clean working tree (no stray untracked/modified files) before writing this SUMMARY
