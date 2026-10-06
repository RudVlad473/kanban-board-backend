---
phase: quick-261006-guz
plan: 01
subsystem: board-api
tags: [optimistic-locking, partial-update, regression-test]
requires: []
provides:
  - "PUT /api/boards/{boardId} with a version-only body returns 200 with name and version unchanged"
affects: [BoardService.updateById, docs/learning/03, docs/learning/05, docs/CODE_STYLE.md rule 12]
key-files:
  modified:
    - src/main/java/com/vrudenko/kanban_board/service/BoardService.java
    - src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
    - docs/learning/03-optimistic-locking.md
    - docs/learning/05-api-layer.md
    - docs/CODE_STYLE.md
    - .planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md
decisions:
  - "Approach A: a presence guard on boardDTO.getName() after the unchanged version guard; the event still fires and the version does not move (same as a no-op rename)"
status: complete
commits: 2
plan_head_before: 8a257661ef20eb9860c838a0d165fb3400c1deda
plan_head_after: 38881c99ad3d1a93016f2c24607d02e5be76009c
actuals:
  tokens: 14000
  tasks: 2
  commits: 2
---

# Phase quick-261006-guz Plan 01: version-only board PUT returns 200 instead of 500 Summary

`BoardService.updateById` now treats a null `name` as "no rename" behind the unchanged version guard, pinned by four MockMvc tests, one of which failed with the real 500 before the fix.

Commits: `9731b9f` (fix + tests), `38881c9` (docs). Plan base `8a25766`. Branch `quick/261006-guz-board-version-only-update`. Not pushed or merged.

## Verification record (direction checked per test)

RED, on the unfixed service (tests added, service untouched), `./gradlew test --tests '*BoardControllerTest*'`: 21 tests, 1 failed.

| Test | Unfixed code | Fixed code |
|------|--------------|------------|
| `shouldReturnOkAndLeaveNameAndVersionUnchanged_whenBodyHasOnlyVersion` | FAILED: `Status expected:<200> but was:<500>` | passes |
| `shouldReturnConflict_whenBodyHasOnlyStaleVersion` | passed | passes |
| `shouldReturnConflict_whenNameBelongsToAnotherBoard` | passed | passes |
| `shouldReturnOk_whenNameIsUnchanged` | passed | passes |

The printed 500 body on the unfixed code (test result XML system-out): `code":"INTERNAL_ERROR"`, `detail` = `could not execute statement [ERROR: null value in column "name" of relation "boards" violates not-null constraint ... Failing row contains (..., null, ...)] [update boards set created_at=?,name=?,user_id=?,version=? where id=? and version=?]`. This is the reproduction the plan asked for, and it matches the planning account, so work proceeded.

GREEN: `BoardControllerTest`, `BoardLockingTest`, `BoardServiceTest`, `OptionalNotBlankTest` together: 53 tests, 0 failures.

Mutation run for test 2 (approach D, an early return of `boardMapper.toResponseDTO(boardToUpdate)` when the name is null, inserted before the version guard): `BoardControllerTest` ran 21 tests, 1 failed, and the failure was `shouldReturnConflict_whenBodyHasOnlyStaleVersion` with `Status expected:<409> but was:<200>`; test 1 passed. The mutation was then reverted (restored from a saved copy of the fixed file; a grep for the inserted line returns 0 and `git diff` showed only the Step 3 change). The committed file is the fixed one.

The other three tests (2-4) are boundary guards: they pass in both directions by design, so they pin existing behavior rather than the bug. Only test 1 is the regression test for the 500, and only test 2 is proven by mutation to catch a lock bypass. Tests 3 (duplicate name) and 4 (no-op rename) were not mutation-checked; the plan did not ask for it.

## Gates on the final tree

- Full `./gradlew test` (including JaCoCo verification): BUILD SUCCESSFUL, 542 tests, 0 failures, 0 errors, 0 skipped (second run; see Deviations for the first).
- `./gradlew spotlessCheck`: clean (after one `spotlessApply` that rewrapped the long test method name).
- `python3 scripts/verify-comments.py check`: OK, 294 files.
- `git diff --quiet 8a25766 -- UpdateBoardRequestDTO.java OptionalNotBlankTest.java`: exit 0, both byte-identical.
- Both commits went through the pre-commit hook (gitleaks, comment lint, spotlessCheck, fastTest); no `--no-verify`. Neither run hit "stop command received".
- `git log --stat 8a25766..HEAD`: exactly two commits and only the six files in `files_modified`.
- Task 2's automated doc greps all hold (checked individually).
- `docker ps -a`: three exited Testcontainers-labelled containers remain (`strange_galois` and `loving_newton` on postgres:16, `naughty_perlman` on redpanda); all three were already listed in `docker ps -a` before this run started, so none is mine and I did not remove them. I did not check for an orphan from the first full-suite run beyond the end-state listing above, which shows nothing new.
- Gradle daemon stopped at the end.

## Deviations from Plan

**1. [Rule 3 - Blocking] Javadoc comment lint.** The method Javadoc as first written had 10 prose lines before any marker, over the limit of 8 (`segregated-narration`, CODE_STYLE rule 14). Fixed by putting the new paragraph behind a `Decisions:` marker, as rule 14 item 5 allows. File: `BoardService.java`. Folded into commit `9731b9f`.

**2. [Formatting] Spotless.** `spotlessCheck` rejected the long test method name's line layout; `spotlessApply` reformatted it. Folded into `9731b9f`.

**3. First full-suite run had one failure, unrelated.** `ColumnControllerTest.UpdateById.testWithAuthenticatedUser_shouldUpdateColumn_whenColumnExists` failed with `version Expected: 1 got: 0`. Cause (read from the code, not reproduced): the test renames the column to `getRandomWord(MIN_COLUMN_NAME_LENGTH + 2)` and expects the version to rise, but when the random word equals the fixture column's current name (also a random word of the same length) nothing is dirty, Hibernate issues no UPDATE and the version stays put. Evidence it is a flake: that class passed 100% on a rerun (`*ColumnControllerTest*`), the full suite then passed 542/542, and my change touches only the board path. I did not measure the collision probability. It is pre-existing and out of scope, so I did not fix it; recorded in `deferred-items.md` in this directory.

## Decisions recorded

- Version does not move on a version-only PUT (no UPDATE when nothing is dirty, WR-02), and `BoardUpdatedEvent` is still published, so the activity feed gets one entry for a request that changed nothing. Kept for consistency with the no-op rename; not tested here. If WR-02 is ever fixed with a forced increment, test 1's version assertion must change with it.

## Findings (not fixed): null-field audit of sibling update paths

Confirmed against the code on 2026-10-06; none changed.

- `ColumnService.updateById` (line ~147) calls `column.setName(dto.getName())` with no null guard, the same shape as the board bug. Not reachable over HTTP: `UpdateColumnRequestDTO.name` is `@NotBlank`, `ColumnController.updateById` binds it with `@Valid`, and that controller is the only caller in `src/main`. Latent: relaxing the field to `@OptionalNotBlank`, or adding a non-HTTP caller, would reproduce the 500.
- `TaskService.updateById` guards `title` and `description` with presence checks; the DTO's `atLeastOneFieldPopulated()` rejects an empty update (read from the plan's account; I read the service guard, not the DTO method). No hazard found.
- `SubtaskService.updateById` guards `title` and `isCompleted` the same way. No hazard.
- `UserService.updateTheme` sets the theme unconditionally, but `UpdateThemeRequestDTO.theme` is `@NotNull` and `UserController.updateTheme` binds it with `@Valid`; it is the only caller. Same latent shape as the column, not reachable.

## Docs left alone, and why

- `docs/wiki/learning/03*` and `05*` copies: the wiki rule says the originals stay authoritative until the follow-on cleanup, so the copies now lag the originals.
- `docs/MOCKUP_FEATURE_GAP.md` line 312: a dated snapshot that is stale on a different point (it says the board DTO has no `version` field).
- OpenAPI: no change needed, because the DTO is unchanged. I relied on the plan's account of `.planning/spikes/003-web-fuzzing-schemathesis/out/openapi-after.json` (`UpdateBoardRequestDTO.required` is `["version"]`) and did not re-open that file; `UpdateBoardRequestDTO.java` is byte-identical to the base.
- `UpdateBoardRequestDTO`'s comment saying a version-only update is accepted is now true and stays.

## Known Stubs

None.

## Threat Flags

None. No new endpoint, auth path or schema; T-guz-01 (version guard first) is mitigated and mutation-proven, T-guz-02 (SQL text in a 500 `detail`) no longer occurs for this body shape, though the general raw-`detail` gap stays open in its own todo.

## Self-Check: PASSED

- `9731b9f` and `38881c9` are ancestors of HEAD (`git rev-list --count 8a25766..HEAD` is 2).
- `BoardService.java`, `BoardControllerTest.java`, both learning chapters, `CODE_STYLE.md` and the pending todo exist and are in the two commits.
