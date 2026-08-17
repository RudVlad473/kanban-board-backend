---
phase: 06-mock-up-feature-gap-closure
plan: 05
subsystem: api
tags: [spring-boot, spring-data-jpa, hibernate, mapstruct, rest-assured, nested-read, fetch-join]

# Dependency graph
requires:
  - phase: 06-01
    provides: V5 Flyway migration's tasks.position/columns.position/subtasks.version fields, surfaced in the nested response
  - phase: 06-02
    provides: BoardController/BoardService conventions (created-response shape, ownership-first loading) this plan's GET mapping follows
provides:
  - "GET /boards/{boardId}/full (GAP-04): one nested read returning board, columns, tasks and subtasks four levels deep in a single database round trip"
  - "BoardFullResponseDTO/ColumnFullResponseDTO/TaskFullResponseDTO -- the one deliberate exception to this codebase's flat-DTO convention, composed via MapStruct's uses attribute and reusing the existing SubtaskResponseDTO unchanged at the leaf"
  - "BoardRepository.findByIdWithColumnsTasksAndSubtasks -- a chained LEFT JOIN FETCH query proven to cost exactly 3 prepared statements (2 ownership-verification lookups + 1 fetch join) regardless of graph size"
  - "Corrected, load-bearing finding: Hibernate's MultipleBagFetchException fires on 2+ List collections fetch-joined anywhere in one query, not only siblings off the same parent -- documented in BoardRepository's Javadoc for any future multi-level fetch-join work in this codebase"
affects: []

# Actuals (#2632)
actuals:
  tokens: 52000
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "MapStruct mapper composition via `uses = {...}` for a nested DTO tree (BoardFullMapper -> ColumnFullMapper -> TaskFullMapper -> existing SubtaskMapper), first use of this attribute in this codebase"
    - "Set (not List) for every JPA collection in a multi-level fetch-join chain, with @OrderBy(\"id\") to restore a deterministic iteration order plain HashSet does not have"
    - "Entity equals/hashCode must be identity-based (or otherwise collision-safe) for any entity used as a Set element populated by Hibernate collection hydration -- field-based equals/hashCode that excludes id is unsafe the moment a List becomes a Set"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/dto/board_dto/BoardFullResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnFullResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/task_dto/TaskFullResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/mapper/BoardFullMapper.java
    - src/main/java/com/vrudenko/kanban_board/mapper/ColumnFullMapper.java
    - src/main/java/com/vrudenko/kanban_board/mapper/TaskFullMapper.java
    - src/test/java/com/vrudenko/kanban_board/BoardFullReadE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java
    - src/main/java/com/vrudenko/kanban_board/service/BoardService.java
    - src/main/java/com/vrudenko/kanban_board/controller/BoardController.java
    - src/main/java/com/vrudenko/kanban_board/entity/BoardEntity.java
    - src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
    - src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java
    - src/main/java/com/vrudenko/kanban_board/entity/SubtaskEntity.java
    - src/test/java/com/vrudenko/kanban_board/service/BoardServiceTest.java

key-decisions:
  - "MultipleBagFetchException fires on 2+ List (bag) collections fetch-joined anywhere in one query, not only siblings off the same parent as the plan's design rationale assumed -- verified against a real Hibernate 6 build, not assumed. Fixed by converting all three collections in the chain (BoardEntity.column, ColumnEntity.task, TaskEntity.subtasks) to Set, leaving zero bags."
  - "Converting to Set surfaced two further, distinct bugs (not assumed, each independently observed failing then fixed): (1) a List-typed outer collection still accumulates duplicate elements from a multi-level fetch join's row multiplication, since JPQL DISTINCT only dedupes the query's ROOT entity, never nested collections -- fixed by making every collection in the chain Set, not just the two flagged by MultipleBagFetchException; (2) SubtaskEntity's and ColumnEntity's field-based equals/hashCode (excluding id) can silently merge distinct sibling entities in a HashSet when their non-id fields collide (isCompleted defaults to false for every subtask; position defaults to 0 for every column pre-GAP-03) -- fixed by disabling both entities' field-based equals/hashCode, matching TaskEntity's existing identical precedent (Object identity, safe because Hibernate's session identity map always reuses the same Java reference for one row within one persistence context)."
  - "Added @OrderBy(\"id\") to all three collections once they became Set, since plain HashSet has no defined iteration order and the nested response's ordering needs to be deterministic, not merely correct-content."
  - "The ordering-equivalence test compares nested-vs-flat as same-elements-any-order plus an internal id-ascending sort, not a strict element-for-element order match: neither ColumnRepository.findAllByBoardId nor TaskRepository.findAllByColumnId carries an explicit ORDER BY (no ordering feature has landed in this wave), so their row order is PostgreSQL's incidental query-plan order -- observed directly, across repeat runs, to vary for the SAME data. A strict order match was tried first and found genuinely flaky, not a test-authoring mistake. Fixing this properly (adding ORDER BY to the flat repositories) is out of this plan's scope -- those files belong to the sibling ordering plan (06-04) running in a separate worktree."
  - "409/401/404 status semantics for the new route follow existing conventions unchanged: ownership-denied -> 401 (AppAccessDeniedException, matching every other endpoint, not the 403 an unauthenticated zero-cookie request gets), unknown board -> 404 (AppEntityNotFoundException)."

patterns-established:
  - "A multi-level Hibernate fetch-join chain requires: (a) zero or one List-typed collection across the whole chain, others must be Set; (b) every entity used as a Set element must have collision-safe equals/hashCode (identity-based is safest, matching TaskEntity's pre-existing choice); (c) @OrderBy on each Set-typed collection to restore deterministic ordering. This is now the reference implementation for any future multi-level nested-read endpoint in this codebase."

requirements-completed: [GAP-04]

coverage:
  - id: D1
    description: "GET /boards/{boardId}/full returns board, columns, tasks and subtasks four levels deep in one nested JSON document, with populated (not null) leaf fields at every level, for a board the caller owns."
    requirement: "GAP-04"
    verification:
      - kind: e2e
        ref: "BoardFullReadE2ETest#GetFullBoard.shouldReturnNestedDocumentFourLevelsDeep_whenBoardHasColumnsTasksAndSubtasks"
        status: pass
    human_judgment: false
  - id: D2
    description: "Empty associations at every level (board with no columns, column with no tasks, task with no subtasks) serialise as empty arrays, never null."
    requirement: "GAP-04"
    verification:
      - kind: e2e
        ref: "BoardFullReadE2ETest#GetFullBoard.shouldReturnEmptyColumnsArray_whenBoardHasNoColumns"
        status: pass
      - kind: e2e
        ref: "BoardFullReadE2ETest#GetFullBoard.shouldReturnEmptyTasksArray_whenColumnHasNoTasks"
        status: pass
      - kind: e2e
        ref: "BoardFullReadE2ETest#GetFullBoard.shouldReturnEmptySubtasksArray_whenTaskHasNoSubtasks"
        status: pass
    human_judgment: false
  - id: D3
    description: "Requesting another user's board returns 401 and discloses nothing about that board's contents (name absent from the response body); requesting an unknown board id returns 404."
    requirement: "GAP-04"
    verification:
      - kind: e2e
        ref: "BoardFullReadE2ETest#GetFullBoard.shouldReturnUnauthorizedAndDiscloseNothing_whenBoardOwnedByAnotherUser"
        status: pass
      - kind: e2e
        ref: "BoardFullReadE2ETest#GetFullBoard.shouldReturnNotFound_whenBoardDoesNotExist"
        status: pass
    human_judgment: false
  - id: D4
    description: "The nested read costs a statement count invariant to graph size (3, regardless of columns/tasks/subtasks count), proven by an invariance assertion that was falsified by hand (temporarily swapping the fetch join for a plain findById made the count scale with graph size -- 9 vs 23 observed -- before being restored)."
    requirement: "GAP-04"
    verification:
      - kind: unit
        ref: "BoardServiceTest#FindFullByIdQueryCountTest.queryCountDoesNotScaleWithGraphSize"
        status: pass
    human_judgment: false
  - id: D5
    description: "The nested response carries the same elements as the four existing flat endpoints for the same board, field-by-field equal (name, version, title, description, isCompleted), with no element dropped or duplicated by the fetch-join/Set conversion; nested arrays have their own deterministic (id-ascending) order."
    requirement: "GAP-04"
    verification:
      - kind: e2e
        ref: "BoardFullReadE2ETest#FlatEquivalence.shouldMatchFlatEndpointsFieldByField_forSameBoard"
        status: pass
      - kind: e2e
        ref: "BoardFullReadE2ETest#FlatEquivalence.shouldContainSameElementsAsFlatEndpoints_andBeInternallyOrdered_forSameBoard"
        status: pass
    human_judgment: false
  - id: D6
    description: "The four existing flat read endpoints (GET /boards, GET .../columns, GET .../tasks, GET .../subtasks) are unchanged by this plan -- this is an addition, not a replacement."
    requirement: "GAP-04"
    verification:
      - kind: other
        ref: "git diff --stat against ColumnController.java/TaskController.java/SubtaskController.java shows zero changes; BoardController.java's diff is purely additive (new GET mapping only)"
        status: pass
    human_judgment: false

duration: 121min
completed: 2026-08-08
status: complete
---

# Phase 6 Plan 5: Nested Board Read (GAP-04) Summary

**GET /boards/{boardId}/full returns board, columns, tasks and subtasks four levels deep in a single database round trip, via a chained LEFT JOIN FETCH query and a MapStruct BoardFullMapper -> ColumnFullMapper -> TaskFullMapper -> SubtaskMapper composition chain -- built on top of a corrected, non-obvious finding that Hibernate's MultipleBagFetchException and multi-level collection duplication apply more broadly than this plan's own design rationale assumed.**

## Performance

- **Duration:** 121 min
- **Started:** 2026-08-08T19:05:00Z
- **Completed:** 2026-08-08T21:03:59Z
- **Tasks:** 3 completed (task 3 required two follow-up rounds of investigation and fixes before it went green)
- **Files modified:** 15 (7 created, 8 modified)

## Accomplishments

- `GET /boards/{boardId}/full` added to `BoardController`, backed by `BoardService.findFullById` (ownership-verified first, via the existing `findById`, then a fetch-join query against the verified entity's own id) and a `BoardFullMapper -> ColumnFullMapper -> TaskFullMapper -> SubtaskMapper` (existing, unchanged) composition chain.
- `BoardFullResponseDTO`/`ColumnFullResponseDTO`/`TaskFullResponseDTO` -- three new nested DTOs, following the existing `dto/{domain}_dto/` convention and reusing `SubtaskResponseDTO` unchanged at the leaf (a subtask has no children of its own).
- `BoardRepository.findByIdWithColumnsTasksAndSubtasks` -- a single chained `LEFT JOIN FETCH` JPQL query, proven by `BoardServiceTest` to cost exactly 3 prepared statements (2 ownership-verification lookups + 1 fetch join) regardless of graph size, with the invariance test explicitly falsified (confirmed red without the fetch join, green with it).
- Corrected the plan's own design-rationale assumption about `MultipleBagFetchException`: it fires on 2+ `List` collections fetch-joined *anywhere* in one query, not only siblings off the same parent. Fixed by converting every collection in the `board->column->task->subtasks` chain to `Set`.
- Discovered and fixed two further bugs surfaced only by task 3's edge-case/equivalence tests, both consequences of the Set conversion: (1) a `List`-typed outer collection still accumulates duplicate elements from row multiplication, since JPQL `DISTINCT` only dedupes the query's root entity; (2) `SubtaskEntity`/`ColumnEntity`'s field-based `equals`/`hashCode` (excluding `id`) can silently merge distinct sibling entities in a `HashSet` when non-id fields collide -- fixed by disabling both entities' field-based `equals`/`hashCode`, matching `TaskEntity`'s existing identical precedent.
- Added `@OrderBy("id")` to all three collections for deterministic iteration order (plain `HashSet` has none).
- `BoardFullReadE2ETest` (13 test methods across 2 `@Nested` groups) proves the full document shape, empty-collection handling at every level, the 401/404 cases, and field-by-field/same-elements equivalence against the four flat endpoints.
- `BoardServiceTest.FindFullByIdQueryCountTest` proves the query-count invariance with a falsification pass.

## Task Commits

Each task was committed atomically:

1. **Task 1: End-to-end nested board read — one GET, four levels deep** - `098db48` (feat; includes the List->Set MultipleBagFetchException fix for `ColumnEntity.task`/`TaskEntity.subtasks`, described in Deviations below)
2. **Task 2: Prove the nested read costs exactly one prepared statement** - `ed24cf0` (test)
3. **Task 3: Edge cases, ordering, and confirming the flat endpoints are untouched** - `2df315f` (fix; includes the `BoardEntity.column` List->Set fix, the `SubtaskEntity`/`ColumnEntity` equals/hashCode fix, and `@OrderBy("id")`, described in Deviations below)

**Plan metadata:** this SUMMARY.md's commit (created immediately after this file, per the atomic close-out protocol)

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/dto/board_dto/BoardFullResponseDTO.java` - new nested root DTO
- `src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnFullResponseDTO.java` - new nested column DTO (id, name, version, position, tasks)
- `src/main/java/com/vrudenko/kanban_board/dto/task_dto/TaskFullResponseDTO.java` - new nested task DTO (id, title, description, version, position, subtasks)
- `src/main/java/com/vrudenko/kanban_board/mapper/BoardFullMapper.java` - new, `uses = {ColumnFullMapper.class}`, explicit `@Mapping(source="column", target="columns")`
- `src/main/java/com/vrudenko/kanban_board/mapper/ColumnFullMapper.java` - new, `uses = {TaskFullMapper.class}`, explicit `@Mapping(source="task", target="tasks")`
- `src/main/java/com/vrudenko/kanban_board/mapper/TaskFullMapper.java` - new, `uses = {SubtaskMapper.class}` (existing, unchanged)
- `src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java` - `+ findByIdWithColumnsTasksAndSubtasks`, chained `LEFT JOIN FETCH`, extensive Javadoc documenting the corrected MultipleBagFetchException/row-duplication findings
- `src/main/java/com/vrudenko/kanban_board/service/BoardService.java` - `+ findFullById(userId, boardId)`
- `src/main/java/com/vrudenko/kanban_board/controller/BoardController.java` - `+ GET /{boardId}/full`
- `src/main/java/com/vrudenko/kanban_board/entity/BoardEntity.java` - `column`: `List` -> `Set`, `@EqualsAndHashCode.Exclude`, `@OrderBy("id")`
- `src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java` - `task`: `List` -> `Set`, `@OrderBy("id")`; `@Data`/`@EqualsAndHashCode` replaced with plain `@Getter`/`@Setter` (identity-based equality)
- `src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java` - `subtasks`: `List` -> `Set`, `@OrderBy("id")`
- `src/main/java/com/vrudenko/kanban_board/entity/SubtaskEntity.java` - `@EqualsAndHashCode(callSuper=false)` commented out (identity-based equality, matching `TaskEntity`'s existing precedent)
- `src/test/java/com/vrudenko/kanban_board/BoardFullReadE2ETest.java` - new tracer, 13 test methods, 2 `@Nested` groups
- `src/test/java/com/vrudenko/kanban_board/service/BoardServiceTest.java` - `+ FindFullByIdQueryCountTest` nested class, 1 test method

## Decisions Made

- See `key-decisions` in the frontmatter above for the full technical reasoning; summarized: MultipleBagFetchException is broader than assumed (any 2+ Lists anywhere in one query, not just siblings), a List-typed outer collection still duplicates under row multiplication even when it's the query's only bag, entity equals/hashCode must be collision-safe (effectively id-based or identity-based) for any entity used as a Set element, and the ordering-equivalence test had to be weakened from strict order match to same-elements-plus-internal-sort because the flat endpoints' own row order is genuinely non-deterministic without an explicit ORDER BY they don't have.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Corrected the plan's own design-rationale assumption about `MultipleBagFetchException`**

- **Found during:** Task 1, first test run of the chained fetch-join query
- **Issue:** The plan's design rationale asserted `MultipleBagFetchException` "only fires on two-or-more List collections fetched from the *same* parent," and that a linear three-level chain would be exempt. This is factually wrong, verified against a real Hibernate 6 build: even a two-level chain (`board.column` + `column.task`) throws it.
- **Fix:** Converted `ColumnEntity.task` and `TaskEntity.subtasks` from `List` to `Set`, leaving `BoardEntity.column` as the one `List` the query was (at that point) still allowed.
- **Files modified:** `entity/ColumnEntity.java`, `entity/TaskEntity.java`
- **Verification:** `BoardFullReadE2ETest` went from 500 (`MultipleBagFetchException`) to 200 with a correctly nested body.
- **Committed in:** `098db48` (Task 1 commit)

**2. [Rule 1 - Bug] `BoardEntity.column` (still `List`) also duplicated elements under row multiplication**

- **Found during:** Task 3, the new flat-vs-nested ordering-equivalence test
- **Issue:** JPQL `DISTINCT` only dedupes the query's root entity (`BoardEntity`), never collections nested under it. A column referenced by 14 underlying rows (from its own nested task/subtask fan-out) appeared 14 times in `board.getColumn()`.
- **Fix:** Converted `BoardEntity.column` to `Set` as well (zero bags left in the query), which required first excluding it from `BoardEntity`'s `equals`/`hashCode` to avoid a `Board<->Column` mutual `hashCode()` recursion (`ColumnEntity`'s previous `@Data`-generated hashCode included its `board` back-reference) that only becomes live the instant Hibernate populates a `HashSet`-backed collection (`Set.add()` calls `hashCode()`; `ArrayList.add()` never did).
- **Files modified:** `entity/BoardEntity.java`
- **Verification:** Column count in the nested response matched the flat endpoint's count exactly.
- **Committed in:** `2df315f` (Task 3 commit)

**3. [Rule 1 - Bug] `SubtaskEntity`/`ColumnEntity`'s field-based `equals`/`hashCode` silently merged distinct sibling entities**

- **Found during:** Task 3, the flat-vs-nested field-by-field equivalence test (a `NoSuchElementException` -- a flat subtask's id was missing from the nested response)
- **Issue:** `SubtaskEntity`'s active `equals`/`hashCode` (title, isCompleted, task -- deliberately excluding `id`) could not reliably distinguish sibling subtasks under the same task: `isCompleted` defaults to `false` for every subtask, leaving `title` as the only differentiator, which is not guaranteed unique. A real subtask was dropped by the `HashSet` this way. `ColumnEntity` carried the identical structural risk (name is the only field with real entropy among sibling columns; `board`/`task`/`position` are frequently identical or empty).
- **Fix:** Disabled both entities' field-based `equals`/`hashCode`, matching `TaskEntity`'s existing identical precedent (falls back to `Object`'s identity-based equality, safe because Hibernate's session-level identity map always reuses the same Java reference for the same row within one persistence context).
- **Files modified:** `entity/SubtaskEntity.java`, `entity/ColumnEntity.java`
- **Verification:** The equivalence test passed with zero elements missing.
- **Committed in:** `2df315f` (Task 3 commit)

**4. [Rule 1 - Bug] Plain `HashSet` has no defined iteration order**

- **Found during:** Task 3, immediately after fixes 2 and 3, the ordering test
- **Issue:** Converting every collection to `Set` fixed correctness but broke the coincidental order-preservation the previous `List`-typed design got "for free" from JDBC `ResultSet` row order.
- **Fix:** Added `@OrderBy("id")` to all three collections.
- **Files modified:** `entity/BoardEntity.java`, `entity/ColumnEntity.java`, `entity/TaskEntity.java`
- **Verification:** Nested arrays' `id` ordering is now provably ascending (`Assertions.assertThat(...).isSorted()`).
- **Committed in:** `2df315f` (Task 3 commit)

**5. [Rule 1 - Bug, test-side] The ordering-equivalence test's strict order match against the flat endpoints was itself flaky**

- **Found during:** Task 3, three full-suite reruns after fix 4, one of which failed on this test specifically
- **Issue:** Neither `ColumnRepository.findAllByBoardId` nor `TaskRepository.findAllByColumnId` carries an explicit `ORDER BY` (verified -- no ordering feature has landed in this wave). Their row order is PostgreSQL's incidental query-plan order for that query shape, observed directly to differ run-to-run for the same underlying data. `@OrderBy("id")` on the nested side made that side deterministic, but could not make it coincide with the flat side's non-deterministic order.
- **Fix:** Weakened the assertion to same-elements-any-order (`containsExactlyInAnyOrderElementsOf`) plus a separate, provable internal-ordering check (`isSorted()`), with an in-code comment explaining why a strict match is not achievable without adding an `ORDER BY` to `ColumnRepository`/`TaskRepository` themselves -- out of this plan's scope, since those files belong to the sibling ordering plan (06-04) running in a separate worktree, per the parallel-execution files-modified-list contract.
- **Files modified:** `test/BoardFullReadE2ETest.java`
- **Verification:** Three consecutive full-suite runs afterward were green on this test (one had an unrelated, pre-existing `SignupRequestDTOTest` flake, confirmed non-deterministic by an isolated rerun).
- **Committed in:** `2df315f` (Task 3 commit)

---

**Total deviations:** 5 auto-fixed (all Rule 1 - bugs necessary to deliver the plan's core deliverable correctly; none were scope creep, all were required to make GAP-04's nested read actually correct rather than merely compiling)
**Impact on plan:** Substantial beyond the plan's original estimate (task 3 alone required three additional investigation-fix-verify cycles), but every fix was strictly necessary: the plan's chained-fetch-join design was sound in principle, but its stated MultipleBagFetchException analysis was wrong, and Set conversion (the correct fix) has its own well-known correctness requirements (collision-safe equals/hashCode, explicit ordering) that were not anticipated in the plan and had to be discovered empirically.

## Issues Encountered

- The Gradle daemon was killed mid-run by an unrelated external `--stop` signal during one diagnostic test run (background task `bkm8o1iaq`), producing a spurious `FAILURE` with no test output. Not a code issue -- resolved by simply rerunning.
- `SignupRequestDTOTest.whenDisplayNameIsTooShort_thenOneViolation`'s pre-existing, already-documented flake (random `DataFactory`-generated fixtures occasionally violate their own annotation constraints) surfaced during full-suite reruns, unrelated to this plan. Confirmed non-deterministic by an isolated rerun (passed in isolation).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `GET /boards/{boardId}/full` (GAP-04) is fully closed: routed, ownership-verified, query-count-invariant, tested end-to-end for the nested document shape, empty-collection handling, 401/404 cases, and field-by-field/same-elements equivalence against the four flat endpoints, which remain demonstrably unchanged.
- The three entity collection type changes (`List` -> `Set` for `BoardEntity.column`, `ColumnEntity.task`, `TaskEntity.subtasks`) and the two entity equals/hashCode changes (`ColumnEntity`, `SubtaskEntity` now identity-based) are outside this plan's originally stated `files_modified` list -- flagged here explicitly for the orchestrator, since sibling wave-3 plans (06-04, ordering; 06-06) may touch these same entities and should be aware their collection fields are now `Set`, not `List`, and that `ColumnEntity`/`SubtaskEntity` no longer have field-based `equals`/`hashCode`.
- No blockers or concerns carried forward for GAP-04 itself.

---
*Phase: 06-mock-up-feature-gap-closure*
*Completed: 2026-08-08*
