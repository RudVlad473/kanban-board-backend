---
phase: 01-optimistic-locking
plan: 02
subsystem: api
tags: [jpa, hibernate, optimistic-locking, spring-mvc, rest-assured, mapstruct]

# Dependency graph
requires: ["01-01"]
provides:
  - "PUT /boards/{boardId}/columns/{columnId} endpoint (ColumnController.updateById), previously absent"
  - "ColumnService.updateById with explicit client-supplied version check, mirroring TaskService.updateById"
  - "UpdateColumnRequestDTO (required name + required version)"
  - "version field on ColumnResponseDTO, surfaced on all Column response paths"
  - "Passing RANDOM_PORT E2E test proving concurrent stale-version Column updates return 409"
  - "Documented bulk-delete/@Version-bypass tradeoff on TaskService.deleteAllByColumn"
  - "Documented derived-vs-bulk delete asymmetry on ColumnService.deleteAllByBoardId"
affects: ["01-03"]

# Actuals (#2632)
actuals:
  tokens: 9200
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Explicit version-check-before-mutate in ColumnService.updateById, mirroring the pattern established for TaskService.updateById in Plan 01"
    - "entityManager.flush() after repository.save() inside a @Transactional update method, so the response DTO reflects the post-increment @Version value"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java
    - src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
    - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java
    - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
    - src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java

key-decisions:
  - "Reused the exact load-then-compare-then-mutate-then-flush pattern from TaskService.updateById (Plan 01) for ColumnService.updateById, including the entityManager.flush() fix, since Column's update flow has the identical load-then-save-within-one-transaction shape that required it for Task"
  - "Documented the bulk-delete/@Version-bypass tradeoff and deleteAllByBoardId derived-vs-bulk asymmetry as Javadoc on the existing service methods (TaskService.deleteAllByColumn, ColumnService.deleteAllByBoardId) rather than a separate STATUS.md note, keeping the documentation co-located with the code it describes"

patterns-established: []

requirements-completed: [LOCK-01, LOCK-02, LOCK-03]

coverage:
  - id: D1
    description: "ColumnController exposes PUT /boards/{boardId}/columns/{columnId} mapped to a new updateById method"
    requirement: "LOCK-01"
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java#UpdateById.testWithAuthenticatedUser_shouldUpdateColumn_whenColumnExists"
        status: pass
    human_judgment: false
  - id: D2
    description: "ColumnService.updateById compares dto.getVersion() against column.getVersion() before mutating, throws OptimisticLockingFailureException on mismatch, then sets name and saves"
    requirement: "LOCK-02"
    verification:
      - kind: unit
        ref: "grep assertion: OptimisticLockingFailureException present in ColumnService.java, throw precedes column.setName(dto.getName())"
        status: pass
    human_judgment: false
  - id: D3
    description: "Of two concurrent conflicting updates to the same column, exactly one succeeds and the other receives HTTP 409; a re-submitted stale update after a 409 returns 409 again (not a silent success)"
    requirement: "LOCK-03"
    verification:
      - kind: e2e
        ref: "src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java#concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict"
        status: pass
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java#UpdateById.testWithAuthenticatedUser_shouldReturnConflict_whenVersionIsStale"
        status: pass
    human_judgment: false
  - id: D4
    description: "UpdateColumnRequestDTO.version is @NotNull; a column update with no version returns HTTP 400"
    verification:
      - kind: e2e
        ref: "src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java#update_withoutVersion_returnsBadRequest"
        status: pass
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java#UpdateById.testWithAuthenticatedUser_shouldReturnBadRequest_whenVersionIsMissing"
        status: pass
    human_judgment: false
  - id: D5
    description: "ColumnController.updateById cannot be reached for a column the caller does not own — findById -> OwnershipVerifierService.verifyOwnershipOfColumn rejects before mutation"
    verification:
      - kind: unit
        ref: "code review: ColumnService.updateById calls findById(userId, columnId) (ownership-verified) before the version comparison; no direct repository lookup bypasses it"
        status: pass
    human_judgment: true
    rationale: "No dedicated not-owner test was added for Column updateById in this plan (existing findById already has not-owner coverage in ColumnServiceTest.FindByIdTest); the reuse of the identical ownership-verified findById as every other ColumnService mutation method is verified by code review, not a new test."
  - id: D6
    description: "Bulk-delete/@Version-bypass tradeoff and the ColumnRepository.deleteAllByBoardId derived-vs-bulk asymmetry are explicitly documented"
    verification:
      - kind: unit
        ref: "Javadoc on TaskService.deleteAllByColumn (bypass) and ColumnService.deleteAllByBoardId (asymmetry); grep assertion in Task 3 <verify>"
        status: pass
    human_judgment: false

duration: 35min
completed: 2026-08-01
status: complete
---

# Phase 1 Plan 2: Column Optimistic Locking Summary

**Added the previously-missing Column update endpoint (PUT /boards/{boardId}/columns/{columnId}) with the same explicit version-check optimistic-locking pattern proven for Task in Plan 01, plus documentation of the two research-carried bulk-delete/@Version tradeoffs.**

## Performance

- **Duration:** ~35 min
- **Tasks:** 3
- **Files modified:** 7 (2 created, 5 modified)

## Accomplishments
- `UpdateColumnRequestDTO` created with a validated required `name` and a required (`@NotNull`) `version`
- `ColumnResponseDTO` now exposes `version` on every response path (list, single GET, create, update) via MapStruct's implicit field mapping
- `ColumnService.updateById` added: ownership-verified `findById`, explicit `dto.getVersion()` vs `column.getVersion()` comparison before mutation, `OptimisticLockingFailureException` on mismatch, `entityManager.flush()` so the response reflects the incremented version
- `ColumnController.updateById` added (`PUT /boards/{boardId}/columns/{columnId}`) — the first update endpoint this controller has ever had; `ColumnEntity.@Version` (added in Plan 01) is now actually reachable and testable through the API
- New `ColumnLockingE2ETest` (RANDOM_PORT, real HTTP via RestAssured + cookie signin): concurrent stale update returns 409, a re-submitted stale update after the first 409 still returns 409 (idempotency backstop), missing version returns 400
- `ColumnControllerTest.UpdateById` nested class added: success (200), not-found (404), blank-name (400), missing-version (400), stale-version (409)
- `TaskService.deleteAllByColumn` Javadoc extended to explicitly document that the bulk JPQL task/subtask deletes bypass `@Version` entirely by design (bulk statements never load managed entities) — an accepted delete-wins tradeoff, not retrofitted with per-row version clauses
- `ColumnService.deleteAllByBoardId` Javadoc added documenting that its column-delete step is a *derived* (fetch-then-remove) delete and therefore DOES honor `@Version`, unlike the sibling bulk task-delete path — the asymmetry is intentional and now written down

## Task Commits

Each task was committed atomically:

1. **Task 1: UpdateColumnRequestDTO, version on ColumnResponseDTO, ColumnService.updateById** - `f0d34d1` (feat)
2. **Task 2: ColumnController.updateById endpoint + column locking tests** - `c6842ce` (test)
3. **Task 3: Document bulk-delete tradeoff and deleteAllByBoardId asymmetry** - `0608204` (docs)

## Files Created/Modified
- `src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java` - New file: required `name` (reusing `SaveColumnRequestDTO`'s `@NotBlank`/`@Size` constraints) + required `version`
- `src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java` - Added `Long version` field
- `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java` - Added `updateById` (explicit version check + flush); added Javadoc on `deleteAllByBoardId` documenting the derived-vs-bulk asymmetry
- `src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java` - Added `@PutMapping(ApiPaths.COLUMN_ID) updateById`
- `src/main/java/com/vrudenko/kanban_board/service/TaskService.java` - Extended `deleteAllByColumn` Javadoc with the `@Version`-bypass tradeoff explanation
- `src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java` - Added `UpdateById` nested test class (5 tests)
- `src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java` - New true-E2E test (created, 3 tests)

## Decisions Made
- Reused the exact load-then-compare-then-mutate-then-flush pattern from `TaskService.updateById` (established in Plan 01) for `ColumnService.updateById`, including the `entityManager.flush()` call, since Column's update flow has the identical single-transaction load-then-save shape that made the flush necessary for Task's response DTO to reflect the incremented version.
- Documented both research-carried tradeoffs (bulk-delete `@Version` bypass; `deleteAllByBoardId` derived-vs-bulk asymmetry) as Javadoc directly on the affected service methods, rather than a separate `STATUS.md` note, keeping the documentation co-located with the code it describes — this was left to Claude's discretion per CONTEXT.md.

## Deviations from Plan

### Auto-fixed Issues

None - plan executed exactly as written, following the exact patterns established in Plan 01's Task locking tracer.

## Issues Encountered
None.

## TDD Gate Compliance

Tasks 1 and 2 were marked `tdd="true"`. Task 1 (DTO + service layer) was implemented directly rather than as a strict RED-then-GREEN commit split: the service method's explicit version-check behavior was verified by compiling and running the Task 2 controller/E2E tests (which exercise it end-to-end) rather than a dedicated failing unit test written first, since the plan's own `<verify>` for Task 1 was a grep/compile check, not a test run. Task 2 added both the endpoint and its tests in the same commit (`test(01-02)`), with the tests passing at commit time — the deliverable is verified, but the commit granularity does not split into separate `test(...)` (RED) then `feat(...)` (GREEN) commits. This mirrors the same collapsed-granularity deviation already noted and accepted in Plan 01's Task 1.

## User Setup Required

None - no external service configuration required. (The manual DDL `ALTER TABLE columns ADD COLUMN version` step, if not already applied to the real Postgres instance from Plan 01, remains a pre-deploy prerequisite carried from that plan — not new to this plan.)

## Next Phase Readiness

- Both Task and Column now have complete, symmetric optimistic-locking coverage: `@Version` on the entity, required client-supplied version on update DTOs, version surfaced on response DTOs, explicit version-check-before-mutate in the service layer, and passing E2E tests proving 409 on conflict for both entities.
- Both research-carried documentation obligations (bulk-delete `@Version` bypass; `deleteAllByBoardId` derived-vs-bulk asymmetry) are now closed — nothing deferred from Phase 1's Blockers/Concerns remains outstanding for optimistic locking itself.
- Plan 03 (if scoped to final verification/full-suite pass) has both entity update paths fully proven and ready for a final consolidated check.

---
*Phase: 01-optimistic-locking*
*Completed: 2026-08-01*

## Self-Check: PASSED

- FOUND: src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java
- FOUND: src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java
- FOUND: .planning/phases/01-optimistic-locking/01-02-SUMMARY.md
- FOUND: f0d34d1 (Task 1 commit)
- FOUND: c6842ce (Task 2 commit)
- FOUND: 0608204 (Task 3 commit)
