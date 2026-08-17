---
phase: 06-mock-up-feature-gap-closure
plan: 01
subsystem: database
tags: [flyway, jpa, hibernate, optimistic-locking, spring-boot, postgresql]

# Dependency graph
requires:
  - phase: 04.2-testcontainers-postgres
    provides: Flyway-managed schema (V1-V4) with ddl-auto=validate enforced in both main and test profiles, proven by FlywaySchemaProvenanceTest
provides:
  - V5 Flyway migration adding tasks.position, columns.position, subtasks.version, users.theme, and the uk_boards_user_id_name unique constraint
  - TaskEntity.position, ColumnEntity.position, SubtaskEntity.version (@Version), UserEntity.theme (ThemePreference enum, default LIGHT) entity fields
  - Five new ApiPaths constants (REORDER, FULL, USERS, ME, THEME) landed unused for plans 04-06 to consume
  - Subtask optimistic locking end-to-end (SubtaskService.updateById version-compare-then-409-then-flush, UpdateSubtaskRequestDTO.version, SubtaskResponseDTO.version)
affects: [06-02-PLAN, 06-03-PLAN, 06-04-PLAN, 06-05-PLAN, 06-06-PLAN, 06-07-PLAN]

# Actuals (#2632)
actuals:
  tokens: 21000
  tasks: 3
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Explicit version-compare-then-409-then-flush idiom (TaskService/ColumnService) extended to SubtaskService"
    - "Single V5 migration landing all four phase-wide entity fields plus a cross-cutting constraint, to keep parallel plans 04-06 from contending over migration numbering or shared files"

key-files:
  created:
    - src/main/resources/db/migration/V5__add_position_subtask_version_theme_board_name_uniqueness.sql
    - src/main/java/com/vrudenko/kanban_board/entity/ThemePreference.java
    - src/test/java/com/vrudenko/kanban_board/SubtaskLockingE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java
    - src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
    - src/main/java/com/vrudenko/kanban_board/entity/SubtaskEntity.java
    - src/main/java/com/vrudenko/kanban_board/entity/UserEntity.java
    - src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java
    - src/test/java/com/vrudenko/kanban_board/FlywaySchemaProvenanceTest.java
    - src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java
    - src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/SubtaskResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/UpdateSubtaskRequestDTO.java
    - src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java

key-decisions:
  - "V5 lands all four phase-wide columns (tasks.position, columns.position, subtasks.version, users.theme) plus the boards uniqueness constraint in one migration, exactly per the plan's Approach A, to keep FlywaySchemaProvenanceTest edits to one plan and unblock plans 04-06 running in parallel later"
  - "SubtaskControllerTest.java (not in the plan's stated files_modified list) required fixing during Task 2, not Task 3, because the project's pre-commit hook runs the fast test suite on every commit and Task 2's DTO change broke four of its tests immediately -- fixed then rather than deferred, and documented as a widened scope in Task 2's commit message"
  - "Task 3's two named files (SubtaskServiceTest.java, AbstractAppTest.java) needed zero changes: SubtaskServiceTest.java is an empty placeholder class with no test methods, and AbstractAppTest.java never asserts on UpdateSubtaskRequestDTO/SubtaskResponseDTO's full field set -- the only real fallout was the SubtaskControllerTest.java fix already made in Task 2"

patterns-established:
  - "New unused ApiPaths constants can land ahead of the endpoints that use them, in the shared foundation plan, specifically to keep migration/entity/constants files out of later parallel plans' files_modified lists"

requirements-completed: [GAP-03, GAP-05, GAP-06]

coverage:
  - id: D1
    description: "V5 Flyway migration adds tasks.position, columns.position, subtasks.version, users.theme, and uk_boards_user_id_name; Hibernate ddl-auto=validate accepts all four new entity fields at every context boot"
    requirement: "GAP-03, GAP-05, GAP-06"
    verification:
      - kind: integration
        ref: "FlywaySchemaProvenanceTest#FlywayHistory.shouldRecordFiveSuccessfulMigrations_whenContextStarts"
        status: pass
      - kind: integration
        ref: "FlywaySchemaProvenanceTest#FlywayOnlyArtifacts.shouldContainBoardsUserIdNameUniqueConstraintNamedByV5Migration_whenSchemaIsBuiltByFlyway"
        status: pass
      - kind: integration
        ref: "FlywaySchemaProvenanceTest#FlywayOnlyArtifacts.shouldDefaultUsersThemeColumnToLight_whenSchemaIsBuiltByV5Migration"
        status: pass
    human_judgment: false
  - id: D2
    description: "Subtask updates are optimistic-locked end to end: current-version PUT returns 200 with incremented version, stale-version PUT returns 409 and leaves stored state unchanged, missing-version PUT returns 400 before service code runs, and a cross-user PUT returns 401 without the version check ever running"
    requirement: "GAP-06"
    verification:
      - kind: e2e
        ref: "SubtaskLockingE2ETest#UpdateById.shouldReturnOkWithIncrementedVersion_whenVersionIsCurrent"
        status: pass
      - kind: e2e
        ref: "SubtaskLockingE2ETest#UpdateById.shouldReturnConflictAndLeaveStateUnchanged_whenVersionIsStale"
        status: pass
      - kind: e2e
        ref: "SubtaskLockingE2ETest#UpdateById.shouldReturnBadRequest_whenVersionIsMissing"
        status: pass
      - kind: e2e
        ref: "SubtaskLockingE2ETest#UpdateById.shouldReturnUnauthorized_whenSubtaskOwnedByAnotherUser_andVersionCheckNeverRuns"
        status: pass
    human_judgment: false
  - id: D3
    description: "A newly signed-up user's stored theme defaults to LIGHT and every newly created task/column persists a non-null position, without any client sending those values"
    requirement: "GAP-03, GAP-05"
    verification:
      - kind: integration
        ref: "./gradlew spotlessCheck test (full suite, 216 tests) -- every fixture-creating test in the suite calls UserService.save/createTask/createColumn paths; a missing @Builder.Default on UserEntity.theme or a missing field initialiser on TaskEntity.position/ColumnEntity.position would fail the NOT NULL constraint on every such insert, not just a dedicated test"
        status: pass
    human_judgment: false

duration: 54min
completed: 2026-08-08
status: complete
---

# Phase 6 Plan 1: Database/Entity Foundation and Subtask Optimistic Locking Summary

**One V5 Flyway migration lands tasks.position, columns.position, subtasks.version, users.theme (default LIGHT), and a boards per-user name uniqueness constraint; SubtaskService.updateById gains the same explicit version-compare-then-409-then-flush guard TaskService/ColumnService already use, proven over real HTTP by a new SubtaskLockingE2ETest.**

## Performance

- **Duration:** 54 min
- **Started:** 2026-08-08T15:45:50Z
- **Completed:** 2026-08-08T16:40:03Z
- **Tasks:** 3 completed (Task 3 was a verification-only pass with zero further code changes required)
- **Files modified:** 13 (3 created, 10 modified)

## Accomplishments

- `V5__add_position_subtask_version_theme_board_name_uniqueness.sql` adds all four phase-wide entity fields plus the `uk_boards_user_id_name` unique constraint (with a pre-flight duplicate-name guard mirroring V4's style) in one migration, keeping every later plan's `files_modified` list free of the migration/entity/`ApiPaths` files.
- Four entity changes: `TaskEntity.position`/`ColumnEntity.position` (plain `Integer`, initialised to `0`), `SubtaskEntity.version` (`@Version`, `@EqualsAndHashCode.Exclude`, matching `ColumnEntity`'s block rather than `TaskEntity`'s since `SubtaskEntity`'s `@EqualsAndHashCode` is active), `UserEntity.theme` (new `ThemePreference` enum, `@Builder.Default` — the exact pitfall the plan flagged as most likely to be missed, since `UserEntity` carries Lombok `@Builder`).
- Five new `ApiPaths` constants (`REORDER`, `FULL`, `USERS`, `ME`, `THEME`) added unused, unblocking plans 04-06 to run in parallel later.
- `SubtaskService.updateById` now performs the explicit version-compare guard immediately after the ownership-verified load and before any field mutation, throws `OptimisticLockingFailureException` on a stale version, and calls `entityManager.flush()` before mapping the response so the DTO carries the post-update version — the same idiom `TaskService`/`ColumnService` already use.
- `SubtaskLockingE2ETest` proves the 200-incremented-version, 409-stale-version-with-unchanged-state, 400-missing-version, and 401-cross-user (ownership checked before the version compare) paths over real HTTP.
- `FlywaySchemaProvenanceTest` updated to assert 5 successful migrations and two new Flyway-only artifacts (`uk_boards_user_id_name`, `users.theme`'s `'LIGHT'` default).

## Task Commits

Each task was committed atomically:

1. **Task 1: [BLOCKING] Write the V5 migration and every entity field it backs** - `c3d2852` (feat)
2. **Task 2: End-to-end subtask optimistic locking — one stale-write path, proven** - `d2b0cac` (feat; includes the `SubtaskControllerTest.java` fix described in Deviations below)
3. **Task 3: Repair the whole existing suite against the four new NOT NULL columns** - no commit; verification-only, zero further changes needed (see Deviations)

**Plan metadata:** this SUMMARY.md's commit (created immediately after this file, per the atomic close-out protocol)

## Files Created/Modified

- `src/main/resources/db/migration/V5__add_position_subtask_version_theme_board_name_uniqueness.sql` - the migration described above
- `src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java` - `+ position` (`Integer`, `nullable = false`, initialised `0`)
- `src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java` - `+ position` (same shape)
- `src/main/java/com/vrudenko/kanban_board/entity/SubtaskEntity.java` - `+ version` (`@Version`, `@EqualsAndHashCode.Exclude`)
- `src/main/java/com/vrudenko/kanban_board/entity/UserEntity.java` - `+ theme` (`ThemePreference`, `@Enumerated(STRING)`, `@Builder.Default`, default `LIGHT`)
- `src/main/java/com/vrudenko/kanban_board/entity/ThemePreference.java` - new enum, `LIGHT`/`DARK`
- `src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java` - `+ REORDER, FULL, USERS, ME, THEME`
- `src/test/java/com/vrudenko/kanban_board/FlywaySchemaProvenanceTest.java` - migration count 4→5, two new `FlywayOnlyArtifacts` assertions
- `src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java` - `+ entityManager` field, version-compare guard + flush in `updateById`
- `src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/SubtaskResponseDTO.java` - `+ version`
- `src/main/java/com/vrudenko/kanban_board/dto/subtask_dto/UpdateSubtaskRequestDTO.java` - `+ @NotNull version`
- `src/test/java/com/vrudenko/kanban_board/SubtaskLockingE2ETest.java` - new tracer test, 4 test methods
- `src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java` - 4 `UpdateById` tests updated to supply/expect `version` (see Deviations)

## Decisions Made

- V5 lands all four columns plus the boards constraint in a single migration (plan's Approach A), not one migration per feature, to avoid a real Flyway version-number merge collision across parallel plans.
- `SubtaskEntity.version` copies `ColumnEntity`'s `@EqualsAndHashCode.Exclude`-bearing block rather than `TaskEntity`'s bare one, because `SubtaskEntity`'s `@EqualsAndHashCode(callSuper = false)` is active (not commented out).
- `UserEntity.theme` carries `@Builder.Default` — without it, `UserEntity`'s Lombok `@Builder` would silently discard the field initialiser and write `null` into the `NOT NULL` column on every signup.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed `SubtaskControllerTest.java`'s four `UpdateById` tests, outside Task 2's stated `files_modified` list**

- **Found during:** Task 2 (subtask optimistic locking), while attempting the task commit
- **Issue:** The repo's `.githooks/pre-commit` hook runs `./gradlew spotlessApply` + the fast test suite (excluding E2E) on every commit, not just the plan's own `<verify>` command. `UpdateSubtaskRequestDTO` gaining `@NotNull version` immediately broke four `SubtaskControllerTest` tests that built the DTO without a version (now 400 instead of the expected 200/404), plus one assertion comparing a full `SubtaskResponseDTO` JSON body against an expected object with an implicit `version: null` (the actual response now carries a real incremented version, and `content().json(...)`'s lenient mode still checks explicitly-serialized `null` fields).
- **Fix:** Added `.version(mockSubtasks.getFirst().getVersion())` (or the incremented equivalent for the expected-response comparison) to the four affected test methods.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java`
- **Verification:** `./gradlew spotlessApply fastTest` green; later reconfirmed by the full `./gradlew spotlessCheck test` run.
- **Committed in:** `d2b0cac` (Task 2 commit, with the reason spelled out in the commit message)

**2. [Rule 1 - Bug/pre-existing] Task 3's stated files needed no changes; the fallout was already closed by deviation 1 above**

- **Found during:** Task 3 (repair the whole suite)
- **Issue:** The plan named `SubtaskServiceTest.java` and `AbstractAppTest.java` as Task 3's expected blast radius. `SubtaskServiceTest.java` is an empty placeholder class (`public class SubtaskServiceTest extends AbstractAppTest {}`, no test methods) and `AbstractAppTest.java` never asserts on `UpdateSubtaskRequestDTO`'s or `SubtaskResponseDTO`'s full field set — neither needed a code change.
- **Fix:** None required. Ran the full `./gradlew spotlessCheck test` suite (216 tests) to confirm; the only failure observed (`SignupRequestDTOTest.whenDisplayNameIsTooShort_thenOneViolation`) is a pre-existing, already-documented flake in that test file's own comment (random `DataFactory`-generated email/password fixtures occasionally violate their own annotation constraints, unrelated to `UpdateSubtaskRequestDTO`/`SubtaskEntity`/`UserEntity` in any way) — confirmed non-deterministic by rerunning the full suite two additional times, both fully green (216/216).
- **Files modified:** none
- **Verification:** `./gradlew spotlessCheck test` — 216 tests, 0 failures, 0 errors (two of three full-suite runs); `grep -rn "@Disabled" src/test/java` returns nothing added by this plan.
- **Committed in:** n/a — no production or test code changed for Task 3, so no separate commit exists for it.

---

**Total deviations:** 2 (1 auto-fixed widening of Task 2's stated scope, 1 confirmed-no-fix-needed for Task 3's stated scope; both are scope-boundary notes, not architectural changes)
**Impact on plan:** No scope creep beyond what the pre-commit hook forced; both deviations are documented, mechanically necessary, and leave the plan's actual deliverables (V5 migration, four entity fields, subtask locking) exactly as specified.

## Issues Encountered

- `SignupRequestDTOTest.whenDisplayNameIsTooShort_thenOneViolation` failed intermittently in two of four full-suite runs during this plan's verification. This is a pre-existing flake, not a regression: the test class's own code comment (on a sibling test method, `whenDisplayNameIsMissing_thenNoViolation`) already documents "confirmed pre-existing via `git stash` (fails intermittently on unmodified code too, ~coin-flip rate across repeated full-suite runs)." The class has zero relationship to `TaskEntity`/`ColumnEntity`/`SubtaskEntity`/`UserEntity` or any file this plan touches (it validates `SignupRequestDTO`'s email/displayName/password fields only), passed 3/3 times in isolated reruns, and the full suite passed 2/2 times on separate re-runs without any code change in between — confirming non-determinism rather than a real regression. Not fixed here (out of this plan's scope); left as-is, consistent with the existing documented disposition.
- A stray Gradle-held file lock (`build/test-results/fastTest/binary/output.bin`) from an earlier interrupted bash command timeout blocked one commit attempt with `Unable to delete directory`. Resolved with `./gradlew --stop` + removing the stale directory; not a code issue.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- The V5 migration, all four entity fields, and the five new `ApiPaths` constants are in place — plans 04, 05, and 06 (which the phase's wave plan explicitly designed to depend on this foundation) can now proceed without touching the migration file, any of `TaskEntity`/`ColumnEntity`/`SubtaskEntity`/`UserEntity`, or `ApiPaths.java` themselves.
- Subtask optimistic locking (GAP-06) is fully closed and independently verified — no further work needed on it in this phase.
- No blockers or concerns carried forward from this plan.

---
*Phase: 06-mock-up-feature-gap-closure*
*Completed: 2026-08-08*
