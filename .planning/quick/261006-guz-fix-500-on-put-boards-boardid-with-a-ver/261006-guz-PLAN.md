---
phase: quick-261006-guz
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
  - src/main/java/com/vrudenko/kanban_board/service/BoardService.java
  - docs/learning/03-optimistic-locking.md
  - docs/learning/05-api-layer.md
  - docs/CODE_STYLE.md
  - .planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md
autonomous: true
requirements: [261006-guz]

estimate:
  tokens: 110000
  raw_tokens: 110000
  tasks: 2
  confidence: low

must_haves:
  truths:
    - "PUT /api/boards/{boardId} with a version-only body carrying the current version returns 200, and the board's name and version are unchanged in the response AND on a later GET /api/boards (before the fix: 500 INTERNAL_ERROR, NOT NULL violation on boards.name)"
    - "A version-only body carrying a stale version still returns 409 OPTIMISTIC_LOCK_CONFLICT, because the version guard still runs before the name-presence guard; a recorded mutation run proves the test fails if the null-name path is moved ahead of the version guard"
    - "Renaming to the name of another of the caller's boards still returns 409 DUPLICATE_RESOURCE, and a no-op rename (same name, current version) still returns 200"
    - "The version-only test FAILED on the unfixed code with status 500 and PASSES on the fix; the other three new tests pass on both, and the SUMMARY states which direction was checked for each"
    - "UpdateBoardRequestDTO and OptionalNotBlankTest are byte-identical to the plan base: the DTO contract (name optional, version required) is kept"
    - "docs/learning/03-optimistic-locking.md and docs/learning/05-api-layer.md no longer state that a version-only board PUT returns 500, and docs/CODE_STYLE.md rule 12 says the consuming service must treat a null optional field as unchanged"
    - "The SUMMARY records the null-field audit of ColumnService, TaskService, SubtaskService and UserService update paths as findings; none of them is changed"
    - "./gradlew spotlessCheck, python3 scripts/verify-comments.py check and the full ./gradlew test pass on the final tree"
  artifacts:
    - path: src/main/java/com/vrudenko/kanban_board/service/BoardService.java
      provides: "updateById wraps the no-op detection, the uniqueness check and setName in a presence guard on boardDTO.getName(), after the unchanged version guard"
    - path: src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
      provides: "four new MockMvc tests in the UpdateById nested class: version-only current, version-only stale, duplicate name, no-op rename"
    - path: docs/learning/03-optimistic-locking.md
      provides: "trade-offs bullet, How we test it bullet and Known gaps item 4 updated to the fixed behavior"
    - path: docs/learning/05-api-layer.md
      provides: "rule-6 exceptions paragraph, catch-all bullet and Known gaps table updated to the fixed behavior"
  key_links:
    - from: BoardControllerTest.UpdateById
      to: BoardService.updateById
      via: "MockMvc PUT on ApiPaths.BOARDS + /{boardId}, real Spring wiring and Testcontainers Postgres (CODE_STYLE rule 4)"
    - from: BoardService.updateById presence guard
      to: BoardRepository.existsByUserIdAndName and BoardEntity.setName
      via: "both are skipped when boardDTO.getName() is null; save and entityManager.flush then issue no UPDATE because nothing is dirty"
    - from: BoardService.updateById version guard
      to: GlobalExceptionHandler.handleOptimisticLockingFailure
      via: "OptimisticLockingFailureException thrown before the presence guard, answered as 409 OPTIMISTIC_LOCK_CONFLICT"
---

# Quick task 261006-guz: a version-only board PUT returns 200 instead of 500

<objective>
Make `PUT /api/boards/{boardId}` with a version-only body such as `{"version":0}` succeed: a null
`name` means "no rename". The version precondition still applies, the duplicate-name check and the
name write are skipped, and the unchanged board is returned. Pin it with an HTTP-level regression
test written first and shown to fail with the 500, plus three boundary tests that keep the fix from
loosening the lock or the uniqueness rule.

Purpose: `UpdateBoardRequestDTO` documents and validates a version-only update as legal
(`OptionalNotBlankTest.UpdateBoardRequestDTOTest.shouldReturnNoViolations_whenNameIsNull`), but
`BoardService.updateById` writes the null into the NOT NULL `boards.name` column, so the flush fails
and the request surfaces as `500 INTERNAL_ERROR` with SQL text in `detail`. Schemathesis found it in
quick task 261006-dpq; `docs/learning/03-optimistic-locking.md` and `05-api-layer.md` recorded it on
2026-09-23.

Output: one service change, four new tests, three doc updates, one todo note, and a findings list for
the sibling update paths in the SUMMARY.

Data flow (3 sentences, per the project directive): the PUT body binds into `UpdateBoardRequestDTO`,
and `@Valid` accepts a missing `name` because the field is `@OptionalNotBlank` and only `version` is
`@NotNull`. `BoardService.updateById` loads the board through the ownership check, throws
`OptimisticLockingFailureException` (409) on a version mismatch, and only when a name is present
runs the duplicate-name check and `setName`. It then saves, flushes (Hibernate issues an UPDATE only
when a field is dirty), publishes `BoardUpdatedEvent` and maps the managed entity to the 200 body.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.claude/CLAUDE.md
@docs/CODE_STYLE.md
@docs/SESSION_LESSONS.md
@.planning/quick/261006-dpq-fix-openapi-doc-so-it-is-schemathesis-fu/261006-dpq-SUMMARY.md
@src/main/java/com/vrudenko/kanban_board/service/BoardService.java
@src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java
@src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java

Interfaces the executor needs (verified at planning time, 2026-10-06, HEAD 8a25766):

- `BoardService.updateById(String userId, String boardId, UpdateBoardRequestDTO boardDTO)` returns
  `BoardResponseDTO`. Order today: `findById` (ownership), version guard, `isNoOpRename`, uniqueness
  check via `boardRepository.existsByUserIdAndName(boardToUpdate.getUser().getId(), boardDTO.getName())`,
  `boardToUpdate.setName(boardDTO.getName())`, `boardRepository.save`, `entityManager.flush()`,
  `BoardUpdatedEvent`, `boardMapper.toResponseDTO(savedBoard)`.
- Presence-guard idiom already used by the sibling partial-update methods:
  `TaskService.updateById` (lines 123-128) and `SubtaskService.updateById` (lines 107-113) wrap each
  optional field write in `if (Optional.ofNullable(dto.getX()).isPresent())`.
- Fixture state per test (`AbstractAppTest.setup`): the owning user has boards named `Todo` and `Done`
  (`mockEmptyBoards`) and `In progress` (`mockPopulatedBoard`). `mockPopulatedBoard.getVersion()` is
  the board's current version (the existing `testWithAuthenticatedUser_shouldUpdateBoard_whenBoardExists`
  relies on it). Board names allow 1-64 chars of `[a-zA-Z0-9 ]`.
- Error codes: `GlobalExceptionHandler` sets `code` to `OPTIMISTIC_LOCK_CONFLICT` (409) and
  `DUPLICATE_RESOURCE` (409); both bodies are `application/problem+json`.
- `UpdateBoardRequestDTO` carries `@JsonInclude(NON_NULL)`, so a builder with only `version` set
  serializes to exactly `{"version":N}`, the Schemathesis reproduction body.
</context>

## Approach and trade-offs (project directive: 2 alternatives, matrix, non-obvious trade-offs)

The locked decision is approach A. The planner agrees with it. One honest limit: A makes the DTO's
documented contract true. It does not prove that a version-only board update has a real client use
case, which is the open question in the 2026-08-11 pending todo. That todo stays open and gets a
dated note (Task 2).

| Approach | Pros/Cons | Why Picked |
|----------|-----------|------------|
| A. Service treats a null `name` as "no rename": a presence guard around the no-op detection, the uniqueness check and `setName`, placed after the unchanged version guard | Pro: keeps the DTO contract, `OptionalNotBlankTest` and CODE_STYLE rule 12's reference site intact. Pro: no API change, and the OpenAPI schema is unchanged (`required: [version]`). Pro: uses the presence-guard idiom TaskService and SubtaskService already use. Pro: removes a pointless `name IS NULL` existence query and a doomed UPDATE. Con: a version-only PUT does not bump the version and still emits an activity event (see below) | Picked (locked by the orchestrator, reversible). It is the smallest change that makes the accepted body work, and it matches the sibling services |
| B. Make `UpdateBoardRequestDTO.name` mandatory (`@NotBlank`, drop `@OptionalNotBlank`), like `UpdateColumnRequestDTO` | Pro: one shape for both single-field DTOs; a version-only body becomes a clean 400. Con: a breaking API change (200-able request becomes 400) for any client that sends version-only. Con: contradicts the DTO comment, `OptionalNotBlankTest.shouldReturnNoViolations_whenNameIsNull` and CODE_STYLE rule 12, all of which must be rewritten. Con: changes the published OpenAPI `required` list | Rejected: it changes the contract to match the bug instead of fixing the bug |
| C. Keep the service and map the failure to a 4xx: catch the NOT NULL violation, or route `entityManager.flush()` through Spring exception translation so it becomes `409 DATA_INTEGRITY_VIOLATION` | Pro: no 500. Con: still rejects a body the DTO declares valid; only the status changes. Con: still issues a failing UPDATE and rolls back. Con: translating flush exceptions is a cross-cutting handler change that also alters Known gaps item 1 (the concurrent race) in `03-optimistic-locking.md`, far beyond this fix | Rejected: wrong contract and much wider blast radius |
| D. Variant of A: return early when `name` is null, before the version guard | Pro: no flush, no event. Con: drops the version precondition, so a stale client gets 200 and believes its view is current: a silent loss of the lock for this body shape | Rejected. Test 2 plus a recorded mutation run guard against it |

Non-obvious trade-offs:

- **Version does not move.** With nothing dirty, Hibernate issues no UPDATE, so `@Version` stays
  where it was and the response carries the same version the client sent. That is the same
  mechanism as a no-op rename today (Known gaps item 2, WR-02, in `03-optimistic-locking.md`). A
  client that hoped to use a version-only PUT as a "touch" gets nothing. The version-only test pins
  this; if WR-02 is ever fixed with a forced increment, that assertion must change with it.
- **The event still fires.** `BoardUpdatedEvent` is still published for a version-only PUT, exactly
  as for a no-op rename, so the activity feed gets one entry for a request that changed nothing.
  Kept for consistency: none of the four update services has a "did anything change" notion today,
  and adding one for boards alone would make the board feed behave differently from the others.
  Recorded as a decision, not tested here.
- **Ordering is the security property.** The version guard must stay first. Approach D shows the
  failure mode: a reordering looks harmless and silently accepts stale writes.
- **Cost.** O(1) either way. The fix removes one `SELECT ... WHERE name IS NULL` round trip and one
  failing UPDATE plus rollback per version-only request. No memory or cache invalidation effects:
  the persistence context holds the same managed entity it held before.
- **Information disclosure shrinks.** The 500 body carried the SQL constraint text, the table and
  the column name (`05-api-layer.md`, raw `detail` gap). The fix removes this one route to that leak.
  It does not fix the general raw-`detail` gap.

Discretion choices made by the planner (not in the orchestrator's brief): the presence-guard idiom
(consistency with TaskService and SubtaskService over a bare `!= null`), keeping the event, a
two-sentence addition to CODE_STYLE rule 12 so the bug class is named where the next author of an
optional field will look, and a dated note on the pending todo.

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Four HTTP tests, RED on the version-only body, then the presence guard GREEN, one commit</name>
  <files>src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java, src/main/java/com/vrudenko/kanban_board/service/BoardService.java</files>
  <read_first>src/main/java/com/vrudenko/kanban_board/service/BoardService.java (updateById, lines 126-175), src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java (the UpdateById nested class, lines 150-276, and the Save test's GET-and-filter reload at lines 77-94), src/main/java/com/vrudenko/kanban_board/service/TaskService.java (lines 112-130, the presence-guard idiom), docs/CODE_STYLE.md rules 3, 5, 9 and 14, docs/SESSION_LESSONS.md lessons 3 and 9</read_first>
  <precondition>Docker is running (Testcontainers), the current branch is quick/261006-guz-board-version-only-update, and git status lists nothing but the pre-existing untracked .serena/ and docs/learning/kafka-architecture-overview.md plus this plan directory</precondition>
  <behavior>
    - Test 1 (the regression): a version-only body with the current version returns 200; the response name equals the starting name and the response version equals the starting version; a later GET of the board list shows the same name and version for that id. Unfixed code: FAILS with status 500.
    - Test 2: after a successful real rename with the starting version, a version-only body carrying the now-stale starting version returns 409 with code OPTIMISTIC_LOCK_CONFLICT. Passes on unfixed code too; it guards against approach D.
    - Test 3: a rename of the populated board to the name of one of the owning user's empty boards, with the current version, returns 409 with code DUPLICATE_RESOURCE. Passes on unfixed code too.
    - Test 4: a no-op rename (the board's own current name, current version) returns 200 with the name unchanged. Passes on unfixed code too.
  </behavior>
  <action>
    Step 0. Record the plan base with `git rev-parse HEAD` for the SUMMARY; Task 2 diffs against it.

    Step 1, RED. Add four tests to the existing nested class `UpdateById` in `BoardControllerTest`, following the controller dialect of CODE_STYLE rule 5 (`testWithAuthenticatedUser_should<Outcome>_when<Condition>`, capitalized `// Arrange`, `// Act`, `// Assert` like the neighbouring tests), qualified `Assertions` (rule 3), and `put(url).with(user(userId)).contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(dto))` plus `.andDo(MockMvcResultHandlers.print())` like the existing UpdateById tests. Names:
    - `testWithAuthenticatedUser_shouldReturnOkAndLeaveNameAndVersionUnchanged_whenBodyHasOnlyVersion`
    - `testWithAuthenticatedUser_shouldReturnConflict_whenBodyHasOnlyStaleVersion`
    - `testWithAuthenticatedUser_shouldReturnConflict_whenNameBelongsToAnotherBoard`
    - `testWithAuthenticatedUser_shouldReturnOk_whenNameIsUnchanged`
    Build each body with `UpdateBoardRequestDTO.builder()`; for the version-only bodies set only `version`, so NON_NULL serializes the exact reproduction body. Test 1 captures `mockPopulatedBoard.getName()` and `getVersion()` before acting, reads the PUT response into `BoardResponseDTO`, then reloads through `get(getBoardPrefix())` into `BoardResponseDTO[]` and filters by id, as the Save test does, asserting name and version on both. Give test 1 one plain-text comment line saying the version stays put because Hibernate issues no UPDATE when nothing is dirty, the same as a no-op rename. Test 2 first sends a real rename (for example the name `Renamed first`) with the starting version and asserts 200, then sends the version-only body with the starting version and expects `status().isConflict()` and `jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT")`. Test 3 uses `mockEmptyBoards.getFirst().getName()` as the new name for `mockPopulatedBoard` and expects 409 with code `DUPLICATE_RESOURCE`. Test 4 sends the board's own current name and asserts 200 and an unchanged name; it does not assert the version (WR-02 is not this task's contract). Comments are plain text: no HTML tags, no inline Javadoc tags, and no comment line may start with an annotation-like token (rule 14 item 7).

    Step 2. Run the verify command's Gradle part on the unfixed service. Expected: test 1 FAILS with `Status expected:<200> but was:<500>`, and tests 2-4 PASS. Read the outcome from `build/test-results/test/TEST-com.vrudenko.kanban_board.controller.BoardControllerTest$UpdateById.xml` (Gradle prints no assertion text). Also confirm the printed 500 body in that file's system-out names the NOT NULL violation on column `name` of `boards`; that is the reproduction the orchestrator asked for. Record both in the SUMMARY. If test 1 does not fail with 500, or any of tests 2-4 fails, STOP and report: the root cause differs from the planning account, and the fix must not proceed on it. Never edit an expectation to fit.

    Step 3, GREEN (per the locked decision, approach A). In `BoardService.updateById`, leave the ownership load and the version guard exactly where they are. Wrap the existing no-op detection, the existing uniqueness check and the `setName` call in one presence guard, `if (Optional.ofNullable(boardDTO.getName()).isPresent())`, the idiom TaskService and SubtaskService use; add `import java.util.Optional;` to the java import group (rule 10). Keep the existing no-op comment inside the block. Leave `save`, `flush`, the event and the mapping after the block unchanged, so a version-only request still saves, flushes, publishes `BoardUpdatedEvent` and returns the managed entity. Update the method Javadoc in plain text: keep the summary line and the existing paragraph, and add one paragraph saying that a null name means no rename because the DTO accepts a version-only body; the version precondition still runs first; the duplicate-name check and the name write are skipped; with nothing dirty Hibernate issues no UPDATE, so the version does not increment and BoardUpdatedEvent is still published, both the same as a no-op rename. Do not touch `UpdateBoardRequestDTO`, `OptionalNotBlankTest`, the controller or any other service.

    Step 4. Run the verify command. All four new tests and every pre-existing test in the four classes pass.

    Step 5, mutation proof for test 2. Temporarily insert, directly after the `findById` line and before the version guard, an early return of `boardMapper.toResponseDTO(boardToUpdate)` when `boardDTO.getName()` is null (approach D). Run `./gradlew test --tests '*BoardControllerTest*' -x jacocoTestCoverageVerification`. Expected: test 2 FAILS (200 instead of 409) and test 1 passes. Record the result, then remove the inserted lines and confirm with `git diff src/main/java/com/vrudenko/kanban_board/service/BoardService.java` that only the Step 3 change remains. Rerun Step 4's verify.

    Step 6. If formatting fails, run `./gradlew spotlessApply`, then rerun `./gradlew spotlessCheck`. Stage the two task files by explicit path, never with `git add -A` (the untracked `docs/learning/kafka-architecture-overview.md` must stay out). Commit as `fix(quick-261006-guz): treat a null board name as no rename on PUT /boards/{boardId}`, ending the message with the session's Co-Authored-By trailer. The RED test cannot be committed on its own, because the hook's fastTest would refuse it. The hook runs gitleaks, the comment lint, spotlessCheck and fastTest (about 4 minutes), so run `git commit` in the background and poll until it exits, then confirm with `git log -1 --stat`. Never use `--no-verify`. If Gradle dies with "stop command received", follow SESSION_LESSONS lesson 9's diagnosis order and retry with `GRADLE_OPTS=-Dorg.gradle.daemon.registry.base=/tmp/guz-gradle-registry` exported (the 261006-dpq workaround); if the host still cannot run the hook, stop and report BLOCKED instead of bypassing it.
  </action>
  <verify>
    <automated>./gradlew test --tests '*BoardControllerTest*' --tests '*BoardLockingTest*' --tests '*BoardServiceTest*' --tests '*OptionalNotBlankTest*' -x jacocoTestCoverageVerification && ./gradlew spotlessCheck && python3 scripts/verify-comments.py check && rg -q 'Optional.ofNullable\(boardDTO.getName\(\)\).isPresent\(\)' src/main/java/com/vrudenko/kanban_board/service/BoardService.java</automated>
  </verify>
  <done>
    - The RED run failed test 1 with status 500 and a NOT NULL violation on boards.name in the printed body, while tests 2-4 passed; recorded in the SUMMARY.
    - The GREEN run passes all four classes, and the mutation run failed test 2 and was reverted; both recorded.
    - `git show --stat HEAD` lists exactly BoardControllerTest.java and BoardService.java.
  </done>
</task>

<task type="auto">
  <name>Task 2: Correct the docs that record the 500, note the todo, audit sibling update paths, full suite, commit</name>
  <files>docs/learning/03-optimistic-locking.md, docs/learning/05-api-layer.md, docs/CODE_STYLE.md, .planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md</files>
  <read_first>docs/learning/03-optimistic-locking.md (lines 257-282 and 831-867), docs/learning/05-api-layer.md (lines 407-421, 884-893, 1322-1342), docs/CODE_STYLE.md (rule 12, lines 548-590), .planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md, src/main/java/com/vrudenko/kanban_board/service/ColumnService.java (lines 135-150), src/main/java/com/vrudenko/kanban_board/service/TaskService.java (lines 112-130), src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java (lines 95-114), src/main/java/com/vrudenko/kanban_board/service/UserService.java (lines 118-127)</read_first>
  <precondition>Task 1's commit is HEAD and its hook run passed</precondition>
  <action>
    Edit only the originals under `docs/`, never the copies under `docs/wiki/` (project CLAUDE.md wiki rule: originals stay authoritative until the follow-on cleanup). Cite the fix as quick task 261006-guz, dated 2026-10-06.

    Step 1, `docs/learning/03-optimistic-locking.md`.
    - Trade-offs bullet at lines 262-266: keep the first sentence (column name mandatory, board name optional) and the todo link. Every sentence after the first changes, because "no test supports" becomes false once Task 1 lands. New content: a version-only board update returns 200 and leaves the name and version unchanged (261006-guz), and BoardControllerTest pins it; the pending todo still asks whether any client needs that flow.
    - "How we test it" (from line 268): add one bullet naming the four new `BoardControllerTest.UpdateById` methods with a relative link to `../../src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java`, in the same style as the BoardLockingTest bullet.
    - Known gaps item 4 (lines 851-861): keep the number so the list does not renumber. Rewrite it as resolved: a heading-style bold line marking it resolved on 2026-10-06 by 261006-guz; one sentence on what happened before (the service wrote the null name and the flush hit the NOT NULL column, returning 500, confirmed 2026-09-23); one sentence on the fix (a null name skips the duplicate check and the write, the version guard still runs first); and one sentence that the version-only use-case question stays open in the pending todo.

    Step 2, `docs/learning/05-api-layer.md`.
    - Rule-6 exceptions paragraph, lines 417-421: keep the first clause (the board DTO keeps `name` optional, so validation accepts a version-only update). Replace the remaining sentences, which say the service rejects that update and the PUT fails, with: `BoardService.updateById` treats a null name as no rename, still checks the version, skips the duplicate check and the name write, and returns the board unchanged (261006-guz); before that fix the flush failed on the NOT NULL column and the PUT returned 500.
    - Catch-all bullet at lines 891-893: drop the version-only board PUT from the list, correct the count word to four, and keep the remaining sentence.
    - Observed-examples sentence at lines 889-890: keep the dated observation and add a parenthetical that the NOT NULL example came from the version-only board PUT, fixed by 261006-guz.
    - Known gaps table, line 1337: delete the row for the version-only board PUT. The table lists open items only; its rows are unnumbered, so nothing renumbers.

    Step 3, `docs/CODE_STYLE.md` rule 12. After the "Current application sites" paragraph, add two plain sentences: validation alone does not make such a field safe, because the service that consumes it must treat null as "leave this field unchanged", as TaskService.updateById and SubtaskService.updateById do with a presence guard; BoardService.updateById lacked that guard until quick task 261006-guz, and a version-only board PUT returned 500.

    Step 4, the pending todo. Append a section headed `## Update 2026-10-06 (quick task 261006-guz)` stating: the version-only path no longer fails, it returns 200 with the name and version unchanged; the version does not increment, unlike the "only version incremented" expectation in this todo's Problem section, because Hibernate issues no UPDATE when nothing is dirty (WR-02); a test now covers the path (BoardControllerTest.UpdateById), but no client use case has been found, so this todo's decision stays open; option 2 (mandatory name) would now also be a 200-to-400 API change. Do not move the file to `completed/`: `03-optimistic-locking.md` links to its pending path and the decision is still open.

    Step 5, confirm nothing else contradicts the fix. Run `rg -n -i 'version-only' docs/*.md docs/learning src/main` and read each hit. Expected, verified at planning time: the remaining hits are the corrected lines above and the `UpdateBoardRequestDTO` comment that says a version-only update is accepted, which is now true and stays unchanged. The OpenAPI schema needs no change because the DTO is unchanged: the planning-time snapshot `.planning/spikes/003-web-fuzzing-schemathesis/out/openapi-after.json` lists `UpdateBoardRequestDTO.required` as `["version"]` only. Record in the SUMMARY two docs left alone and why: the `docs/wiki/learning/03` and `05` copies (wiki rule, they now lag the originals), and `docs/MOCKUP_FEATURE_GAP.md` line 312, a dated snapshot that is stale on a different point (it says the board DTO has no version field).

    Step 6, null-field audit, findings only. Read the four sibling update methods and their controller bindings, and record in the SUMMARY under "Findings (not fixed)". Planning-time results to confirm or correct against the code:
    - `ColumnService.updateById` calls `column.setName(dto.getName())` with no null guard, the same shape as the board bug. It is not reachable over HTTP: `UpdateColumnRequestDTO.name` is `@NotBlank`, `ColumnController` binds it with `@Valid`, and the controller is the only caller in `src/main`. It is latent: relaxing that field to `@OptionalNotBlank`, or a new non-HTTP caller, would reproduce the 500.
    - `TaskService.updateById` guards `title` and `description` with presence checks, and the DTO's `atLeastOneFieldPopulated()` rejects an empty update. No hazard.
    - `SubtaskService.updateById` guards `title` and `isCompleted` the same way. No hazard.
    - `UserService.updateTheme` sets the theme unconditionally, but `UpdateThemeRequestDTO.theme` is `@NotNull` and `UserController` binds it with `@Valid`. Same latent shape as the column, not reachable.
    Change none of them.

    Step 7, gates on the final tree. Confirm `git diff --quiet <plan base> -- src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java` exits 0. Run the full `./gradlew test` in the background and poll until it exits (about 5-6 minutes; it includes the kafka and realSocket tags and the JaCoCo ratchet); record the test count from the summary line or `build/test-results`. Run `./gradlew spotlessCheck` and `python3 scripts/verify-comments.py check`. Afterwards check `docker ps -a --filter label=org.testcontainers` for containers this run left behind (no Ryuk on this host) and remove only those.

    Step 8. Stage the four task files by explicit path and commit as `docs(quick-261006-guz): record the version-only board update fix`, with the Co-Authored-By trailer, in the background as in Task 1. Never `--no-verify`. Do not push or merge: the orchestrator owns the merge gate.
  </action>
  <verify>
    <automated>! rg -q 'board update fails with 500' docs/learning/03-optimistic-locking.md && ! rg -q 'although the DTO accepts it' docs/learning/05-api-layer.md && ! rg -q 'Five cases reach' docs/learning/05-api-layer.md && rg -q '261006-guz' docs/learning/03-optimistic-locking.md && rg -q '261006-guz' docs/learning/05-api-layer.md && rg -q '261006-guz' docs/CODE_STYLE.md && rg -q 'shouldReturnOkAndLeaveNameAndVersionUnchanged_whenBodyHasOnlyVersion' docs/learning/03-optimistic-locking.md && rg -q '^## Update 2026-10-06' .planning/todos/pending/2026-08-11-updateboardrequestdto-name-optionality-rests-on-same-unex.md && ./gradlew test && ./gradlew spotlessCheck && python3 scripts/verify-comments.py check</automated>
  </verify>
  <done>
    - Both learning chapters describe the fixed behavior; the version-only row is gone from the 05 Known gaps table; item 4 in 03 is marked resolved and keeps its number.
    - CODE_STYLE rule 12 names the consumer-side null obligation; the todo carries the dated update and stays pending.
    - The SUMMARY lists the four sibling findings, the two docs deliberately left alone, and the OpenAPI confirmation.
    - The full `./gradlew test`, `spotlessCheck` and `verify-comments.py check` exit 0 on the final tree; the docs commit landed through the hook; no Testcontainers container from this run remains.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client to API | The PUT body is untrusted JSON; `name` may be absent, and `version` is client-asserted |
| API to Postgres | The service decides which fields reach the UPDATE; the database's NOT NULL and unique constraints are the backstop |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-guz-01 | Tampering | BoardService.updateById null-name path | medium | mitigate | The version guard stays ahead of the presence guard, so a stale version-only body is still a 409. Test 2 asserts it, and the Task 1 Step 5 mutation run proves test 2 fails when the null-name path is moved first |
| T-guz-02 | Information disclosure | 500 ProblemDetail detail on PUT /boards/{boardId} | low | mitigate | The fix removes the NOT NULL failure that put the table, column and SQL text in `detail`; test 1 asserts 200 for the body that produced it. The general raw-detail gap stays open in its own todo |
| T-guz-03 | Elevation of privilege | version-only PUT on another user's board | low | accept | Unchanged code path: `findById` runs the ownership check before either guard, and existing ownership tests cover the 403 |
| T-guz-SC | Tampering | npm/pip/cargo installs | high | accept | No package or dependency is added by this plan, so no legitimacy gate applies |
</threat_model>

<verification>
- Task 1's RED run (test 1 fails with 500, tests 2-4 pass), GREEN run and mutation run are recorded in the SUMMARY with the direction checked for each test.
- `git diff --quiet <plan base> -- src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java src/test/java/com/vrudenko/kanban_board/dto/OptionalNotBlankTest.java` exits 0.
- Task 2's verify command exits 0 on the final tree, including the full `./gradlew test`.
- `git log --stat <plan base>..HEAD` shows exactly two commits and only the six files in `files_modified`.
</verification>

<success_criteria>
- `PUT /api/boards/{boardId}` with `{"version":<current>}` returns 200 with the name and version unchanged, proven by a test that failed with 500 before the fix.
- The stale-version 409, the duplicate-name 409 and the no-op rename 200 still hold over HTTP.
- No doc in `docs/` outside the wiki copies says a version-only board PUT returns 500; the sibling-service audit is in the SUMMARY as findings.
- Full suite, spotless and the comment lint are green; nothing was committed with `--no-verify`.
</success_criteria>

<output>
Create `.planning/quick/261006-guz-fix-500-on-put-boards-boardid-with-a-ver/261006-guz-SUMMARY.md` when done
</output>
