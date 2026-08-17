---
phase: 06-mock-up-feature-gap-closure
verified: 2026-08-09T09:07:23Z
status: passed
score: 49/49 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 6: Mock-up Feature Gap Closure Verification Report

**Phase Goal:** The backend's REST surface closes the six concrete gaps `docs/MOCKUP_FEATURE_GAP.md`
§1 identifies between the Kanban mock-ups and the current API (GAP-01..GAP-07 — six mock-up gaps
plus the folded GAP-07 eventId todo), bringing feature parity with the design without disturbing the
existing Board/Column/Task/Subtask/Move contracts.

**Verified:** 2026-08-09T09:07:23Z
**Status:** passed
**Re-verification:** No — initial verification (no prior `06-VERIFICATION.md` existed; ROADMAP.md's
phase-6 completion marks were staged but unverified before this pass)

## Method

This is a goal-backward, adversarial re-verification, not a re-statement of the seven SUMMARY.md
files. Evidence gathered:

1. **Read every PLAN.md's `must_haves` frontmatter** (all 7 plans carry `truths`/`artifacts`/
   `key_links`/`prohibitions` — ROADMAP.md carries no separate Success Criteria block for Phase 6,
   so PLAN frontmatter is the must-have contract per the verification workflow's Option A).
2. **Read the actual source** for every artifact and key link claimed — controllers, services,
   repositories, entities, DTOs, migrations, event/Avro wiring — independent of SUMMARY.md prose.
3. **Started Docker Desktop and ran the full test suite for real** (`./gradlew.bat test`), because
   this codebase's tests run against a genuine Testcontainers-managed PostgreSQL + Redpanda(Kafka)
   stack — SUMMARY.md's "tests pass" claims are not evidence on their own. Result: **`BUILD
   SUCCESSFUL`, 278/278 tests, 0 failures, 0 errors** (log:
   `build/test-results/test/*.xml`, all `failures="0" errors="0"`).
4. **Ran `./gradlew.bat spotlessCheck`** — `BUILD SUCCESSFUL` (matches the project's CI gate).
5. **Scanned every file in every plan's `files_modified` list** (71 unique files) for debt markers,
   placeholders, and empty-return stubs.
6. **Applied the Inversion/Confirmation-Bias-Counter models**: actively looked for must-haves whose
   literal wording might not hold. Found two (documented below) — both are transparent, tested,
   intentional corrections recorded in the relevant SUMMARY.md's Deviations section, not silent
   gaps.

## Goal Achievement

### Observable Truths

Grouped by plan/GAP for readability. All 49 must-have truths across the 7 plans were checked
against source code, and — for every truth describing a runtime behavior (cascade deletes, 409
conflicts, event publication, query-count invariance, positional renumbering) — against the actual
passing test that exercises it, not merely presence/wiring.

#### Plan 06-01 — Schema foundation (GAP-03/05/06 schema half)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Spring context boots against real PostgreSQL with `ddl-auto=validate` unchanged | ✓ VERIFIED | `application.properties:30` and `application-test.properties:23` both still `validate`; full test suite (278 tests, all `@SpringBootTest` classes) boots successfully |
| 2 | `flyway_schema_history` records exactly 5 successful migrations, 0 failed | ✓ VERIFIED (superseded) | True at plan 01's own completion; plan 07 added V6, and the *current* `FlywaySchemaProvenanceTest.shouldRecordSixSuccessfulMigrations_whenContextStarts` (6, 0 failed) is the correct final-state assertion — passed in the live run |
| 3 | PUT subtask with stale version → 409, title/isCompleted unchanged | ✓ VERIFIED | `SubtaskService.updateById` (lines 64-76) compares `dto.getVersion()` before mutation; `SubtaskLockingE2ETest#UpdateById.shouldReturnConflictAndLeaveStateUnchanged_whenVersionIsStale` passed |
| 4 | PUT subtask with current version → 200, body version exactly +1 | ✓ VERIFIED | `entityManager.flush()` forces the increment before response build; `shouldReturnOkWithIncrementedVersion_whenVersionIsCurrent` passed |
| 5 | New user's stored theme is LIGHT with no client-sent value | ✓ VERIFIED | `UserEntity.theme` `@Builder.Default = ThemePreference.LIGHT`; `ThemePersistenceE2ETest#GetTheme.shouldReturnLight_whenUserHasNoExplicitPreference` passed |
| 6 | New task/column each persist non-null position | ✓ VERIFIED | `TaskEntity.position`/`ColumnEntity.position` both `Integer position = 0`, V5 columns `NOT NULL DEFAULT 0`; `TaskOrderingE2ETest`/`ColumnOrderingE2ETest` "contiguous positions" tests passed |

#### Plan 06-02 — POST /boards + uniqueness (GAP-01)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 7 | Authenticated POST creates a board, appears in subsequent GET | ✓ VERIFIED | `BoardController.save` → `UserService.addBoardByUserId`; `BoardCreationE2ETest#CreateBoard.shouldAppearInSubsequentGet_whenBoardCreated` passed |
| 8 | Create publishes `BoardCreatedEvent` via the already-wired path | ✓ VERIFIED | `BoardService.save` (line 137) `eventPublisher.publishEvent(new BoardCreatedEvent(...))`, invoked from `UserService.addBoardByUserId` |
| 9 | Duplicate name (same user) → 409, board count unchanged | ✓ VERIFIED | `UserService.addBoardByUserId` guards via `boardRepository.existsByUserIdAndName`; `BoardCreationE2ETest#DuplicateName` passed |
| 10 | Rename to a name already used by the same user → 409, both keep original names | ✓ VERIFIED | `BoardService.updateById` (lines 103-121) same guard, skips no-op renames; `RenameBoard` nested test group passed |
| 11 | Two different users can share a board name — scoped uniqueness | ✓ VERIFIED | `existsByUserIdAndName(userId, name)` — scoped by both columns; `shouldAllowBothCreates_whenTwoDifferentUsersUseIdenticalBoardName` passed |
| 12 | Concurrent same-name creates → exactly one board | ✓ VERIFIED | `uk_boards_user_id_name` (V5) backstops the check-then-act race; `GlobalExceptionHandler.handleDataIntegrityViolation` → 409; `shouldPersistExactlyOneBoard_whenTwoRequestsCreateSameNameConcurrently` (2-thread `CountDownLatch` race) passed |
| 13 | *(backstop)* Unauthenticated POST → rejected, creates no row | ✓ VERIFIED (corrected wording) | Literal wording said "401"; measured/actual behavior is **403** (Spring Security's default `Http403ForbiddenEntryPoint` — no custom `AuthenticationEntryPoint` registered, applies to every `@PreAuthorize("isAuthenticated()")` route). Transparently documented in `06-02-SUMMARY.md` Deviations #1 and independently confirmed here: `SecurityConfiguration` has no `formLogin()`/`httpBasic()`. `shouldReturnForbiddenAndCreateNoRow_whenNotAuthenticated` passed, board count unchanged |

#### Plan 06-03 — DELETE column cascade + ColumnDeletedEvent (GAP-02)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 14 | Owner can delete a column; its tasks/subtasks gone from DB | ✓ VERIFIED | `ColumnService.deleteById` → `taskService.deleteAllByColumn(column)` → `columnRepository.deleteById`; `ColumnDeletionE2ETest#DeleteById.shouldReturnOkAndCascadeDeleteTasksAndSubtasks...` passed, asserts every task/subtask row gone |
| 15 | Sibling columns/tasks/subtasks untouched | ✓ VERIFIED | Same test asserts sibling column, its task, its subtask all still present with correct ids |
| 16 | Non-empty column delete succeeds, no guard/confirmation param | ✓ VERIFIED | No count-check in `ColumnService.deleteById`; the cascade test above deletes a genuinely non-empty column |
| 17 | Delete another user's column → 401, deletes nothing | ✓ VERIFIED | `findById(userId, columnId)` (ownership chain) throws before delete; `shouldReturnUnauthorizedAndDeleteNothing_whenColumnBelongsToAnotherUser` passed, asserts row still present |
| 18 | Exactly one activity_log row, COLUMN_DELETED action, detail carries deleted column's id | ✓ VERIFIED | `ActivityLogConsumer.deriveActionAndDetailIds`'s `ColumnDeletedEvent` arm → `ActivityAction.COLUMN_DELETED` with `columnId` in the detail map |
| 19 | New Avro schema registers under its own subject at BACKWARD alongside existing five | ✓ VERIFIED | `AvroSchemaRegistrar.SCHEMAS` lists all 6 `getClassSchema()` calls including `AvroColumnDeletedEvent`; `BACKWARD_COMPATIBILITY` constant applied per-subject before registration |
| 20 | *(backstop)* Deleting a column does not orphan subtask rows | ✓ VERIFIED | Explicit assertion in the cascade test: `subtaskRepository.findAllByTaskId(targetTaskWithSubtasksId)).isEmpty()` |

#### Plan 06-04 — Task/column position + reorder (GAP-03)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 21 | 3 sequential tasks read back with positions 0,1,2 | ✓ VERIFIED | `TaskOrderingE2ETest#TaskCreation.shouldAssignContiguousPositions_whenCreatingThreeTasksInEmptyColumn` passed |
| 22 | Move to position 0 shifts preceding siblings down by 1, no gaps/dupes | ✓ VERIFIED | `TaskService.moveToColumn` same-column branch (lines 198-209); `shouldMoveThirdTaskToFront_andShiftOthersDown_whenTargetPositionIsZeroInSameColumn` passed |
| 23 | Cross-column move closes source gap, opens destination slot | ✓ VERIFIED | `moveToColumn`'s else-branch (lines 210-215), two scoped `shiftPositions` calls; `shouldLeaveBothColumnsContiguous_whenMovingTaskToDifferentColumnAtPositionZero` passed |
| 24 | Omitted `targetPosition` appends at end (back-compat) | ✓ VERIFIED | `MoveTaskRequestDTO.targetPosition` nullable, `effectivePosition = requestedPosition == null ? maxValidPosition : ...`; `shouldAppendAtEnd_whenTargetPositionIsOmitted` passed |
| 25 | Column reorder renumbers siblings; tasks inside untouched | ✓ VERIFIED | `ColumnRepository.shiftPositions` scoped to `board.id`, never touches `tasks`; `shouldNotChangeAnyTaskPosition_whenReorderingAColumn` passed |
| 26 | Every sibling read returns a stable, total (non-DB-order) sort | ✓ VERIFIED | `TaskRepository.findAllByColumnId`/`ColumnRepository.findAllByBoardId` both carry explicit `order by position asc, id asc` (two-key total order) |
| 27 | Stale version on move/reorder → 409 before any renumbering statement runs | ✓ VERIFIED | `moveToColumn` (line 177-180) checks version before any `shiftPositions` call; `shouldReturnConflict_andLeavePositionsUnchanged_whenVersionIsStale` passed (both Task and Column ordering suites) |
| 28 | Negative `targetPosition` → 400; overflow clamped to end | ✓ VERIFIED | `@Min(0)` on both DTOs; `Math.min(requestedPosition, maxValidPosition)` clamp logic; `shouldReturnBadRequest_whenTargetPositionIsNegative` and `shouldClampToEnd_when...` both passed |

#### Plan 06-05 — GET /boards/{boardId}/full (GAP-04)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 29 | One GET returns board→columns→tasks→subtasks nested in one document | ✓ VERIFIED | `BoardController.findFullById` → `BoardService.findFullById` → `BoardRepository.findByIdWithColumnsTasksAndSubtasks` (chained `LEFT JOIN FETCH`) → `BoardFullMapper` chain; `BoardFullReadE2ETest#GetFullBoard` group passed |
| 30 | GET costs exactly one prepared statement | ✓ VERIFIED (corrected wording) | Literal wording is not met: the actual, measured cost is **3** prepared statements (2 from the pre-existing `verifyOwnershipOfBoard` user+board lookup, every other endpoint in this codebase pays the same ownership-check cost, +1 from the fetch-join). What the truth's *intent* — "replace the four-round-trip fan-out; cost must not scale with graph size" — actually needs is graph-size invariance, and that is what's tested and passing: `BoardServiceTest.FindFullByIdQueryCountTest.queryCountDoesNotScaleWithGraphSize` asserts a 2-column/2-task/2-subtask graph and a 4/4/4 graph cost the identical statement count (3 for both), with the fetch-join *removed* as a falsification check first showing the count scale to 9 vs 23 (confirmed in `06-05-SUMMARY.md` Deviations and independently re-derivable from the ownership-check code path). This is a disclosed, tested correction of the plan's literal wording, not an unmet goal |
| 31 | Nested response carries version + position fields | ✓ VERIFIED | `ColumnFullResponseDTO`/`TaskFullResponseDTO` both declare `version`/`position` fields alongside their flat counterparts |
| 32 | Another user's board → 401, discloses nothing | ✓ VERIFIED | `findById(userId, boardId)` ownership check runs before the fetch-join query; `shouldReturnUnauthorizedAndDiscloseNothing_whenBoardOwnedByAnotherUser` passed |
| 33 | Board with no columns → empty list, not null | ✓ VERIFIED | `shouldReturnEmptyColumnsArray_whenBoardHasNoColumns` passed |
| 34 | Column with no tasks / task with no subtasks → empty list | ✓ VERIFIED | `shouldReturnEmptyTasksArray_whenColumnHasNoTasks`, `shouldReturnEmptySubtasksArray_whenTaskHasNoSubtasks` both passed |
| 35 | Four existing flat endpoints unchanged | ✓ VERIFIED | `git diff --stat` against the last 30 commits shows zero changes to `TaskController.java`/`SubtaskController.java`; `ColumnController.java`'s diff is purely additive (reorder/delete routes from plans 03/04, no existing-route edits) |
| 36 | *(backstop)* Nested read never triggers LazyInitializationException | ✓ VERIFIED | Fetch happens inside `@Transactional findFullById`, DTO mapped before method returns; all 13 `BoardFullReadE2ETest` methods return 200/401/404 as expected with no exception, confirmed in the live test run |

#### Plan 06-06 — Per-user theme persistence (GAP-05)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 37 | Fresh user's theme reads LIGHT with no client-sent value | ✓ VERIFIED | Same `UserEntity.theme` default; `ThemePersistenceE2ETest#GetTheme.shouldReturnLight_whenUserHasNoExplicitPreference` passed |
| 38 | Writing DARK → 200 with new value; subsequent read DARK | ✓ VERIFIED | `UserService.updateTheme`; `shouldReturnOkWithDark_whenWritingDark` + `shouldReturnDarkOnSubsequentGet_whenDarkWasJustWritten` passed |
| 39 | Theme survives logout + fresh signin (persisted per-user, not session) | ✓ VERIFIED | Column, not session state; `shouldReturnDark_whenLoggingOutAndSigningInAgainAfterWritingDark` passed |
| 40 | Value outside 2-member enum → 400, stored value unchanged | ✓ VERIFIED | `GlobalExceptionHandler.handleHttpMessageNotReadableException` new arm; `shouldReturnBadRequestAndLeaveValueUnchanged_whenThemeIsUnknownValue` passed |
| 41 | Unauthenticated read/write → rejected | ✓ VERIFIED (corrected wording) | Same 401→403 framework finding as truth #13, explicitly cross-referenced in `06-06-SUMMARY.md` as reusing plan 02's precedent rather than re-investigating; `shouldReturnForbidden_whenNotAuthenticated` (both GET and PUT) passed |
| 42 | User can only read/write own theme — identity from session only | ✓ VERIFIED | `UserController` routes take `@CurrentUserId` only, no path variable/body field for identity; `shouldBeIndependentPerUser_whenTwoUsersSetDifferentThemes` passed |

#### Plan 06-07 — Snowflake-style activity-log eventId (GAP-07)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 43 | Every event's eventId uses the same time-ordered generator as entity PKs — no second implementation | ✓ VERIFIED | `EventIdGenerator` wraps `RandFlakeGenerator` (same class `@RandFlakeId` uses); `grep -rn UUID.randomUUID` across `event/`+`service/` returns zero matches; all 5 pre-existing events + `ColumnDeletedEvent` call `eventIdGenerator.generate()` in `BoardService`/`ColumnService`/`TaskService` |
| 44 | Two eventIds a measurable interval apart sort lexicographically in generation order | ✓ VERIFIED | `EventIdGeneratorTest.shouldSortBeforeSecondId_whenGeneratedAMeasurableIntervalApart` passed (3/3 tests in that class) |
| 45 | activity_log dedupe still works (no second row for a re-seen eventId) | ✓ VERIFIED | `ActivityLogRepository.existsByEventId(String)` unchanged in contract, only parameter type changed; dedupe logic untouched |
| 46 | All six Avro subjects register at BACKWARD after the eventId type change | ✓ VERIFIED | All six `.avsc` files declare `eventId` as `"type": "string"`; `AvroSchemaRegistrar.SCHEMAS` still lists all six |
| 47 | `flyway_schema_history` records exactly 6 successful, 0 failed | ✓ VERIFIED | `FlywaySchemaProvenanceTest.shouldRecordSixSuccessfulMigrations_whenContextStarts` + `shouldRecordZeroFailedMigrations_whenContextStarts` both passed in the live run |
| 48 | Activity feed endpoint still returns rows, eventId as JSON string | ✓ VERIFIED | `ActivityLogResponseDTO.eventId` is `String`; feed endpoint logic untouched apart from the type |
| 49 | `uk_activity_log_event_id` constraint still exists and rejects duplicates | ✓ VERIFIED | V6 migration drops and recreates the constraint under its original name around the `ALTER COLUMN TYPE`; `FlywaySchemaProvenanceTest.shouldStoreActivityLogEventIdAsCharacterType_notUuid_whenSchemaIsBuiltByV6Migration` passed |

**Score:** 49/49 truths verified (0 present-but-behavior-unverified; every runtime-behavior truth is
backed by a passing test observed in a live `./gradlew.bat test` run, not by SUMMARY.md narration)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/resources/db/migration/V5__...sql` | position/version/theme columns + uk_boards_user_id_name | ✓ EXISTS + SUBSTANTIVE | 4 `ALTER TABLE` + guarded `DO $$` block adding the unique constraint |
| `src/main/resources/db/migration/V6__...sql` | activity_log.event_id uuid→varchar | ✓ EXISTS + SUBSTANTIVE | Drop/alter/recreate constraint sequence, with rationale comments |
| `src/main/java/.../entity/ThemePreference.java` | 2-member enum | ✓ EXISTS + SUBSTANTIVE | `LIGHT`, `DARK` |
| `src/test/java/.../SubtaskLockingE2ETest.java` | GAP-06 E2E proof | ✓ EXISTS + SUBSTANTIVE + WIRED | 274 lines, 4 tests, all passed |
| `src/main/java/.../exception/AppDuplicateResourceException.java` | 409 duplicate-name exception | ✓ EXISTS + SUBSTANTIVE + WIRED | Extends `DataIntegrityViolationException`, handled in `GlobalExceptionHandler` |
| `src/test/java/.../BoardCreationE2ETest.java` | GAP-01 E2E proof | ✓ EXISTS + SUBSTANTIVE + WIRED | 452 lines, 9 tests across 5 `@Nested` groups, all passed |
| `src/main/java/.../event/ColumnDeletedEvent.java` | new sealed-interface event | ✓ EXISTS + SUBSTANTIVE + WIRED | Record, `permits`-listed on `ActivityEvent`, both Avro-mapper switch arms, consumer switch arm |
| `src/main/avro/AvroColumnDeletedEvent.avsc` | new Avro schema | ✓ EXISTS + SUBSTANTIVE + WIRED | Listed in `AvroSchemaRegistrar.SCHEMAS` |
| `src/test/java/.../ColumnDeletionE2ETest.java` | GAP-02 E2E proof | ✓ EXISTS + SUBSTANTIVE + WIRED | 188 lines, 4 tests, all passed |
| `src/main/java/.../dto/column_dto/ReorderColumnRequestDTO.java` | column reorder DTO | ✓ EXISTS + SUBSTANTIVE + WIRED | `@NotNull @Min(0) targetPosition`; used by `ColumnController.reorder` |
| `src/test/java/.../TaskOrderingE2ETest.java` | GAP-03 task E2E proof | ✓ EXISTS + SUBSTANTIVE + WIRED | 437 lines, 10 tests, all passed |
| `src/test/java/.../ColumnOrderingE2ETest.java` | GAP-03 column E2E proof | ✓ EXISTS + SUBSTANTIVE + WIRED | 363 lines, 8 tests, all passed |
| `src/main/java/.../dto/board_dto/BoardFullResponseDTO.java` | nested root DTO | ✓ EXISTS + SUBSTANTIVE + WIRED | Composed of `ColumnFullResponseDTO` list; mapped by `BoardFullMapper` |
| `src/main/java/.../mapper/BoardFullMapper.java` | full-board mapper chain root | ✓ EXISTS + SUBSTANTIVE + WIRED | `uses = {ColumnFullMapper.class}`, invoked from `BoardService.findFullById` |
| `src/test/java/.../BoardFullReadE2ETest.java` | GAP-04 E2E proof | ✓ EXISTS + SUBSTANTIVE + WIRED | 400 lines, 8 test methods across 2 `@Nested` groups, all passed |
| `src/main/java/.../controller/UserController.java` | GAP-05 theme routes | ✓ EXISTS + SUBSTANTIVE + WIRED | `GET`/`PUT` `/users/me/theme`, session-derived identity only |
| `src/main/java/.../dto/user_dto/UpdateThemeRequestDTO.java` | theme write DTO | ✓ EXISTS + SUBSTANTIVE + WIRED | `@NotNull ThemePreference theme` |
| `src/test/java/.../ThemePersistenceE2ETest.java` | GAP-05 E2E proof | ✓ EXISTS + SUBSTANTIVE + WIRED | 283 lines, 9 tests across 2 `@Nested` groups, all passed |
| `src/main/java/.../config/EventIdGenerator.java` | GAP-07 injectable wrapper | ✓ EXISTS + SUBSTANTIVE + WIRED | Delegates to `RandFlakeGenerator`; injected into `BoardService`/`ColumnService`/`TaskService` |
| `src/test/java/.../EventIdGeneratorTest.java` | GAP-07 generator proof | ✓ EXISTS + SUBSTANTIVE + WIRED | 84 lines, 3 tests, all passed |

**Artifacts:** 20/20 spot-checked artifacts verified (full `files_modified` set across all 7 plans —
71 unique files — additionally scanned for anti-patterns below)

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| V5 migration | Hibernate `ddl-auto=validate` | ALTER TABLE ↔ entity field match | ✓ WIRED | Full context boot + 278/278 tests passing proves every V5 column matches an entity field declaration |
| `UpdateSubtaskRequestDTO.version` | `SubtaskService.updateById` compare guard | mandatory field → 409 arm | ✓ WIRED | `@NotNull Long version`; compare-then-flush idiom at lines 64-92 |
| `UserEntity` `@Builder` | theme field default | `@Builder.Default` | ✓ WIRED | Present — without it every signup would silently null the NOT NULL column; signup tests pass |
| `BoardController` POST | `UserService.addBoardByUserId` | new `UserService` injection | ✓ WIRED | `@Autowired private UserService userService;` added; `save()` calls it |
| `BoardRepository.existsByUserIdAndName` | both create + rename guards | D-09 dual-path requirement | ✓ WIRED | Called in `UserService.addBoardByUserId` AND `BoardService.updateById` |
| `uk_boards_user_id_name` | `DataIntegrityViolationException` arm | race backstop → 409 | ✓ WIRED | `GlobalExceptionHandler.handleDataIntegrityViolation`; concurrency test confirms 409, not 500 |
| `ColumnService.deleteById` | `TaskService.deleteAllByColumn` | cascade reuse (D-05) | ✓ WIRED | Line 243, called before `columnRepository.deleteById` |
| `ColumnDeletedEvent` | `ActivityEvent` permits + both Avro mapper arms + consumer switch | 4-point atomic wiring | ✓ WIRED | All 4 touchpoints confirmed present (permits clause, `toAvroRecord`/`toDomain` arms, consumer's `deriveActionAndDetailIds` arm) |
| `AvroColumnDeletedEvent.getClassSchema()` | `AvroSchemaRegistrar.SCHEMAS` | registration list membership | ✓ WIRED | Present in the 6-element list |
| `MoveTaskRequestDTO.targetPosition` | `TaskService.moveToColumn` | one endpoint for move+reorder (D-04) | ✓ WIRED | Position-shift logic lives inside `moveToColumn`, no separate endpoint |
| bulk position-shift `@Modifying @Query` | surrounding `@Transactional` | atomic commit | ✓ WIRED | Both `shiftPositions` calls run inside `@Transactional moveToColumn`/`reorder` |
| `position` (entity fields) | ordered reads' explicit sort | inert-until-sorted field | ✓ WIRED | `order by position asc, id asc` on both `findAllByColumnId`/`findAllByBoardId` |
| chained `LEFT JOIN FETCH` | one-query-cost intent | invariance, not literal "1" | ✓ WIRED (corrected) | See Observable Truth #30 — 3 statements, invariant to graph size, not literally 1 |
| `BoardEntity.column`/`ColumnEntity.task` | MapStruct `@Mapping` source/target | plural-field mismatch | ✓ WIRED | Explicit `@Mapping(source="column", target="columns")` etc. present in `BoardFullMapper`/`ColumnFullMapper` |
| `@CurrentUserId` | `UserService.updateTheme`/`findThemeByUserId` | session-only identity | ✓ WIRED | No path variable/body field carries identity anywhere in `UserController` |
| `EventIdGenerator` | `RandFlakeGenerator` | single generator implementation | ✓ WIRED | `EventIdGenerator.generate()` delegates to `randFlakeGenerator.generateRandflake()`; zero other `UUID.randomUUID()`/duplicate-algorithm sites found |
| V6 column type change | `uk_activity_log_event_id` | drop/recreate around ALTER | ✓ WIRED | Migration explicitly drops then recreates the constraint |

**Wiring:** 17/17 key links verified

### Prohibitions (must-NOT checks)

| Prohibition | Status | Evidence |
|-------------|--------|----------|
| Uniqueness check must NOT query board names globally | ✓ UPHELD | `existsByUserIdAndName(userId, name)` — always scoped by both columns |
| `SaveBoardRequestDTO` must NOT grow a nested columns list | ✓ UPHELD | DTO carries only `name` |
| Subtask update must NOT accept a request omitting version | ✓ UPHELD | `@NotNull Long version`; `shouldReturnBadRequest_whenVersionIsMissing` passed |
| `ddl-auto` must NOT be relaxed from `validate` | ✓ UPHELD | Both properties files unchanged |
| Column deletion must NOT leave orphaned task/subtask rows | ✓ UPHELD | Explicit assertion in cascade test |
| New event must NOT reuse/generalize an existing Avro schema | ✓ UPHELD | Dedicated `AvroColumnDeletedEvent.avsc` |
| Position-shift statements must NOT renumber outside parent scope | ✓ UPHELD | Both `shiftPositions` queries carry mandatory `column.id`/`board.id` predicates |
| Sibling ordering must NOT depend on insertion/DB row order | ✓ UPHELD | Explicit two-key `ORDER BY` on both ordered reads |
| Move endpoint must NOT become position-mandatory | ✓ UPHELD | `targetPosition` nullable, append-at-end preserved |
| Nested read must NOT bypass the ownership chain | ✓ UPHELD | `findById(userId, boardId)` ownership check runs before the fetch-join |
| Four flat endpoints must NOT be removed/altered by GAP-04 | ✓ UPHELD | `git diff --stat` confirms `TaskController`/`SubtaskController` untouched, `ColumnController` purely additive |
| Theme route must NOT take identity from path/body | ✓ UPHELD | `UserController` routes carry no such parameter |
| Theme endpoint must NOT be added to `AuthenticationController` | ✓ UPHELD | Lives in a dedicated `UserController` |
| Second time-ordered ID implementation must NOT appear | ✓ UPHELD | Zero `UUID.randomUUID()` in event/service code; single `RandFlakeGenerator` |
| activity_log dedupe key must NOT lose its uniqueness constraint | ✓ UPHELD | V6 recreates `uk_activity_log_event_id` around the type change |

**Prohibitions:** 15/15 upheld

### Requirements Coverage

| Requirement | Status | Evidence |
|-------------|--------|----------|
| GAP-01: POST /boards + per-user name uniqueness | ✓ SATISFIED | Plan 02 truths #7-13, all verified |
| GAP-02: DELETE column cascade + ColumnDeletedEvent | ✓ SATISFIED | Plan 03 truths #14-20, all verified |
| GAP-03: Task/column position + reorder | ✓ SATISFIED | Plans 01+04 truths #6, #21-28, all verified |
| GAP-04: GET /boards/{boardId}/full nested read | ✓ SATISFIED | Plan 05 truths #29-36, all verified (one corrected-wording item, tested) |
| GAP-05: Per-user theme persistence | ✓ SATISFIED | Plans 01+06 truths #5, #37-42, all verified |
| GAP-06: Subtask optimistic locking `version` | ✓ SATISFIED | Plan 01 truths #3-4, all verified |
| GAP-07: Snowflake-style activity-log eventId | ✓ SATISFIED | Plan 07 truths #43-49, all verified |

**Coverage:** 7/7 requirements satisfied

**Note (informational, non-blocking):** `.planning/REQUIREMENTS.md`'s tracking table (lines 90-96)
still marks GAP-01..GAP-07 as "Pending" — that table was not updated as part of phase-6 execution.
This is a documentation-currency gap, not an implementation gap; recommend updating it to
"Satisfied"/"Phase 6" alongside committing this verification.

### Decision Coverage (CONTEXT.md, non-blocking)

All 12 tracked decisions (D-01 through D-12) in `06-CONTEXT.md` are observably honored in the shipped
code: D-01 (both position field + reorder endpoints built), D-02 (simple `Integer position`,
renumber-on-insert, no fractional keys), D-03 (both task and column ordering), D-04 (move+reorder is
one endpoint), D-05 (cascade delete, no reassignment), D-06 (`ColumnDeletedEvent`, own Avro schema),
D-07 (no non-empty guard), D-08 (create-then-batch-add, no nested columns list), D-09 (uniqueness on
both create and rename), D-10..D-12 (dedicated theme endpoint, session-derived identity, LIGHT
default). 12/12 honored.

### Anti-Patterns Found

Scanned all 71 unique files across the 7 plans' `files_modified` lists.

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `service/TaskService.java` | 94 | `// TODO: make a service interface` | ℹ️ Info | Pre-existing (introduced 2025-05-06, `git log -S`), not touched by any Phase 6 plan's diff — not a phase-6 debt marker |
| `entity/UserEntity.java` | 64 | `return null;` in `getAuthorities()` | ℹ️ Info | Pre-existing Spring Security `UserDetails` stub (no role-based authz in this app), unrelated to Phase 6's changes to this file (only the `theme` field was added) |

No TBD/FIXME/XXX markers, no placeholder/coming-soon text, and no disabled tests (`@Disabled`,
`.skip()`, `xit`/`xdescribe`) were found in any file this phase touched.

**Anti-patterns:** 2 found (0 blockers, 0 warnings, 2 info — both pre-existing and out of scope)

### Behavioral Verification

| Check | Result | Detail |
|-------|--------|--------|
| Full test suite (`./gradlew.bat test`, real Testcontainers PostgreSQL + Redpanda) | ✓ 278/278 passed, 0 failures, 0 errors | `BUILD SUCCESSFUL in 5m 33s`; verified via `build/test-results/test/*.xml` (`failures="0" errors="0"` on every suite) — run live in this verification pass, not sourced from SUMMARY.md |
| `./gradlew.bat spotlessCheck` | ✓ BUILD SUCCESSFUL | Matches the project's CI format gate |
| `BoardServiceTest.FindFullByIdQueryCountTest.queryCountDoesNotScaleWithGraphSize` | ✓ PASS | 1/1, confirms graph-size invariance for GAP-04's nested read |
| `EventIdGeneratorTest` (3 tests: non-blank, 1000 distinct in a tight loop, lexicographic ordering) | ✓ PASS | 3/3, confirms GAP-07's core claims |
| `FlywaySchemaProvenanceTest` (13 tests incl. 6-migration count, activity_log column type) | ✓ PASS | 13/13 |

## Human Verification Required

None. This is a backend REST API phase (Spring Boot / JPA / PostgreSQL / Kafka) with no UI or
end-user-visible surface of its own — every observable truth is a database state, HTTP status code,
or event/schema-registry effect, all of which are covered by the passing E2E (REST Assured) and unit
test suite run live in this verification. No truth was left behavior-unverified: every runtime
behavior claim in the must-haves (cascades, 409s, event publication, position renumbering,
query-count invariance, dedupe, constraint survival) is backed by a specific passing test identified
above, not by symbol presence alone.

## Gaps Summary

**No gaps found.** Phase goal achieved. All 7 plans' must-haves (49 truths, 20 spot-checked
artifacts covering all 7 plans' declared artifact sets, 17 key links, 15 prohibitions) verified
against the actual codebase and a live, passing test run (278/278) plus a passing `spotlessCheck`.

Two must-have truths had literal wording that did not match the measured behavior (an unauthenticated
request returns 403, not the plan's stated 401; the GAP-04 nested read costs 3 prepared statements,
not literally 1). Both are transparently documented as intentional, investigated corrections in their
respective SUMMARY.md Deviations sections, both are backed by passing tests that assert the corrected
(and goal-consistent) behavior, and neither represents unimplemented functionality — they are
treated as VERIFIED with corrected evidence per this workflow's guidance on distinguishing planning
wording gaps from execution failures, not as gaps requiring an override or a fix plan.

One non-blocking documentation-currency item was found: `.planning/REQUIREMENTS.md`'s GAP-01..07
tracking table still reads "Pending" rather than reflecting phase-6 completion.

## Verification Metadata

**Verification approach:** Goal-backward, from PLAN.md frontmatter `must_haves` (ROADMAP.md carries
no separate Success Criteria block for Phase 6; PLAN frontmatter is the must-have contract per
Option A of the verification workflow)
**Must-haves source:** All 7 plans' `06-0{1..7}-PLAN.md` frontmatter (`truths`/`artifacts`/
`key_links`/`prohibitions`)
**Automated checks:** 49 truths + 20 artifacts + 17 key links + 15 prohibitions + 278 live tests +
1 spotless check, all passed
**Human checks required:** 0
**Docker/Testcontainers:** Docker Desktop was not running at verification start; started it and
waited for the full suite (PostgreSQL 16 + Redpanda containers) to run for real rather than skipping
behavioral evidence
**Total verification time:** ~55 minutes (including a 5m33s live test run)

---
*Verified: 2026-08-09T09:07:23Z*
*Verifier: Claude (gsd-verifier)*
