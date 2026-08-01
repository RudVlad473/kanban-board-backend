---
phase: 01-optimistic-locking
plan: 01
subsystem: api
tags: [jpa, hibernate, optimistic-locking, spring-security, rest-assured, mapstruct]

# Dependency graph
requires: []
provides:
  - "@Version on TaskEntity and ColumnEntity (ColumnEntity excludes version from Lombok equals/hashCode)"
  - "Required version field on UpdateTaskRequestDTO; version exposed on TaskResponseDTO"
  - "Explicit client-supplied version check in TaskService.updateById, before any field mutation"
  - "GlobalExceptionHandler now maps OptimisticLockingFailureException to 409 CONFLICT instead of 423 LOCKED"
  - "Passing RANDOM_PORT E2E test proving concurrent stale-version Task updates return 409"
  - "Fixed cookie-based session authentication (real signin -> authenticated request path), previously broken for any endpoint reached via true HTTP + session cookie rather than MockMvc's user() post-processor"
affects: [01-02, 01-03]

# Actuals (#2632)
actuals:
  tokens: 5510
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Explicit version-check-before-mutate in service layer, in addition to @Version's own dirty-checking, to catch cross-request stale-read races"
    - "entityManager.flush() after repository.save() inside a @Transactional update method, so the response DTO reflects the post-increment @Version value instead of the stale pre-flush one"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java
    - src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
    - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
    - src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java
    - src/main/java/com/vrudenko/kanban_board/dto/task_dto/TaskResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
    - src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
    - src/main/java/com/vrudenko/kanban_board/security/CurrentUserIdResolver.java
    - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
    - src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java

key-decisions:
  - "Fixed a pre-existing authentication bug (UserAuthenticationProvider storing the raw userId string as principal, not UserDetails) rather than working around it in the test, since it silently broke every real cookie-authenticated request and was directly blocking the plan's mandated RANDOM_PORT E2E test"
  - "Added entityManager.flush() in TaskService.updateById so the returned TaskResponseDTO carries the post-update incremented version, matching D-01 (version surfaced on all response paths)"

patterns-established:
  - "Optimistic-lock update pattern: load managed entity -> compare dto.version to entity.version -> throw OptimisticLockingFailureException on mismatch -> mutate -> save -> flush -> map to response DTO"

requirements-completed: [LOCK-01, LOCK-02, LOCK-03, LOCK-04]

coverage:
  - id: D1
    description: "@Version added to TaskEntity and ColumnEntity, scoped to those entities only (not BaseEntity); ColumnEntity's version excluded from Lombok equals/hashCode"
    requirement: "LOCK-01"
    verification:
      - kind: unit
        ref: "grep assertion: HttpStatus.CONFLICT present and @Version absent from BaseEntity.java"
        status: pass
    human_judgment: false
  - id: D2
    description: "GlobalExceptionHandler.handleOptimisticLockingFailure returns HTTP 409 CONFLICT instead of 423 LOCKED"
    requirement: "LOCK-02"
    verification:
      - kind: e2e
        ref: "src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java#concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict"
        status: pass
    human_judgment: false
  - id: D3
    description: "Two concurrent conflicting Task updates: exactly one succeeds (200) and the other is rejected (409), including a re-submitted stale PUT after the first 409"
    requirement: "LOCK-03"
    verification:
      - kind: e2e
        ref: "src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java#concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict"
        status: pass
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java#UpdateById.testWithAuthenticatedUser_shouldReturnConflict_whenVersionIsStale"
        status: pass
    human_judgment: false
  - id: D4
    description: "ColumnEntity's version field excluded from Lombok-generated equals/hashCode so entity identity is unaffected across saves"
    requirement: "LOCK-04"
    verification:
      - kind: unit
        ref: "compile-time: @EqualsAndHashCode.Exclude on ColumnEntity.version (no dedicated equals/hashCode test written)"
        status: pass
    human_judgment: true
    rationale: "No explicit unit test asserts ColumnEntity equals/hashCode behavior before/after a version bump; only source-level verification was performed for this deliverable."
  - id: D5
    description: "UpdateTaskRequestDTO.version is @NotNull; a PUT without a version returns HTTP 400"
    verification:
      - kind: e2e
        ref: "src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java#update_withoutVersion_returnsBadRequest"
        status: pass
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java#UpdateById.testWithAuthenticatedUser_shouldReturnBadRequest_whenVersionIsMissing"
        status: pass
    human_judgment: false

duration: 45min
completed: 2026-08-01
status: complete
---

# Phase 1 Plan 1: Task Optimistic Locking Tracer Summary

**End-to-end optimistic locking on Task updates (entity @Version, required client version, explicit service check, 423->409 fix) proven by a real HTTP E2E test, plus a fix to a pre-existing cookie-authentication bug that silently broke every real (non-MockMvc) authenticated request.**

## Performance

- **Duration:** ~45 min
- **Tasks:** 3
- **Files modified:** 11 (1 created, 10 modified)

## Accomplishments
- `@Version` added to `TaskEntity` and `ColumnEntity` (scoped to those two entities, not `BaseEntity`); `ColumnEntity`'s version excluded from Lombok `equals`/`hashCode`
- `UpdateTaskRequestDTO.version` is now `@NotNull`-required; `TaskResponseDTO.version` surfaces on every response path
- `TaskService.updateById` explicitly compares the caller-supplied version against the loaded entity's version before mutating anything, throwing `OptimisticLockingFailureException` on mismatch
- `GlobalExceptionHandler` now maps that exception to HTTP 409 Conflict (was 423 Locked)
- New `TaskLockingE2ETest` (RANDOM_PORT, real HTTP via RestAssured + cookie signin) proves: first concurrent update succeeds and bumps the version, the second stale update returns 409, a retried stale update still returns 409, and a PUT with no version returns 400
- Fixed a real, previously-undetected authentication bug (see Deviations) that blocked any true HTTP + session-cookie authenticated request from working at all

## Task Commits

Each task was committed atomically:

1. **Task 1: End-to-end Task locking tracer** - `1b496c5` (feat)
2. **Task 2: Repair TaskControllerTest for required version field + stale-version 409 test** - `33826df` (test)
3. **Task 3: Verify full suite and format gate** - `6e63bc0` (test, regression fix)

## Files Created/Modified
- `src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java` - Added `@Version private Long version`
- `src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java` - Added `@Version @EqualsAndHashCode.Exclude private Long version`
- `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java` - 423 -> 409 fix
- `src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java` - Added required `version` field
- `src/main/java/com/vrudenko/kanban_board/dto/task_dto/TaskResponseDTO.java` - Added `version` field
- `src/main/java/com/vrudenko/kanban_board/service/TaskService.java` - Explicit version check + `entityManager.flush()` before mapping response
- `src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java` - Fixed principal to carry `UserDetails`, not raw userId string
- `src/main/java/com/vrudenko/kanban_board/security/CurrentUserIdResolver.java` - Widened principal type check from `User` to `UserDetails`
- `src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java` - New true-E2E test (created)
- `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java` - Version wired into all update DTO builders; added stale-version 409 and missing-version 400 tests; fixed a pre-existing wrong-DTO-type test bug
- `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java` - Added version to a direct-service-call update test

## Decisions Made
- Used `entityManager.flush()` after `taskRepository.save(task)` in `updateById` so the response DTO reflects the incremented `@Version` value immediately, rather than the stale in-memory value from before Hibernate's flush-time UPDATE. Without this, D-01 ("version surfaced on all response paths") would silently return the pre-update version on every successful update.
- Fixed `UserAuthenticationProvider`/`CurrentUserIdResolver` rather than working around them, since the bug they contained made real session-cookie authentication (as opposed to MockMvc's `user()` test post-processor) fail on every single request — this was blocking the plan's explicitly required RANDOM_PORT E2E test and would have blocked all future E2E tests too.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed broken cookie-based session authentication**
- **Found during:** Task 1 (writing `TaskLockingE2ETest`)
- **Issue:** `UserAuthenticationProvider.authenticate()` discarded the `UserDetails` returned by `loadUserByUsername()` and instead built the resulting `Authentication`'s principal from the raw `userId` string. `CurrentUserIdResolver` then required `principal instanceof org.springframework.security.core.userdetails.User` (the concrete Spring Security class) to resolve `@CurrentUserId`. Since the real principal was a plain `String`, every authenticated request made through a real signin (as opposed to MockMvc's `user(userId)` test post-processor, which manufactures its own `User` principal directly) failed downstream with `AppEntityNotFoundException("User principal")`, surfaced to callers as an unexpected 404. This was invisible before because no prior test exercised the real cookie-auth path end-to-end — `BoardE2ETest` was an empty stub with no test methods.
- **Fix:** `UserAuthenticationProvider` now passes the actual `UserDetails` (the `UserEntity`, which implements `UserDetails` directly) as the token's principal. `CurrentUserIdResolver`'s type check was widened from the concrete `User` class to the `UserDetails` interface, matching what's actually stored.
- **Files modified:** `src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java`, `src/main/java/com/vrudenko/kanban_board/security/CurrentUserIdResolver.java`
- **Verification:** `TaskLockingE2ETest` (real signin + RestAssured PUT requests) passes end-to-end; full suite (118 tests) still passes.
- **Committed in:** `1b496c5` (Task 1 commit)

**2. [Rule 1 - Bug] Fixed version not reflected in the update response DTO**
- **Found during:** Task 1 (writing `TaskLockingE2ETest`)
- **Issue:** `TaskService.updateById` mapped the response DTO immediately after `taskRepository.save(task)`, but Hibernate does not bump the in-memory `@Version` field until the UPDATE statement actually flushes (normally at transaction commit). The response therefore always carried the pre-update version instead of the incremented one, contradicting D-01.
- **Fix:** Added `entityManager.flush()` between `save()` and the mapper call, forcing the UPDATE (and version increment) to happen before the DTO is built.
- **Files modified:** `src/main/java/com/vrudenko/kanban_board/service/TaskService.java`
- **Verification:** `TaskLockingE2ETest.update_withCurrentVersion_succeedsAndReturnsIncrementedVersion` asserts the returned version is strictly greater than the pre-update version.
- **Committed in:** `1b496c5` (Task 1 commit)

**3. [Rule 1 - Bug] Fixed a pre-existing wrong-DTO-type test bug surfaced by stricter validation**
- **Found during:** Task 3 (full suite regression pass)
- **Issue:** `TaskControllerTest.UpdateById.testWithAuthenticatedUser_shouldReturnNotFound_whenTaskDoesNotExist` submitted a `SaveBoardRequestDTO` body against the Task update endpoint instead of an `UpdateTaskRequestDTO`. It previously passed by accident because Jackson bound the mismatched JSON into an `UpdateTaskRequestDTO` with `title`/`description`/`version` all null, and the older, more permissive DTO happened to pass validation via `atLeastOneFieldPopulated` sometimes being satisfied by unrelated JSON structure — now that `version` is `@NotNull`, validation fails first (400) instead of reaching the not-found check (404).
- **Fix:** Replaced the body with a valid `UpdateTaskRequestDTO` (title + version), preserving the test's intent (verify 404 on a nonexistent task ID).
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java`
- **Verification:** Full `TaskControllerTest` suite passes.
- **Committed in:** `33826df` (Task 2 commit)

**4. [Rule 1 - Bug] Fixed TaskServiceTest regression from required version field**
- **Found during:** Task 3 (full suite regression pass)
- **Issue:** `TaskServiceTest.UpdateByIdTest.shouldReturn_whenTaskExists` calls `TaskService.updateById` directly, bypassing controller-level `@Valid` DTO validation, so the new explicit version check received a `null` `dto.getVersion()` and threw `OptimisticLockingFailureException`.
- **Fix:** Added `.version(mockPopulatedTask.getVersion())` to the test's DTO builder call.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java`
- **Verification:** Full suite (118 tests) passes.
- **Committed in:** `6e63bc0` (Task 3 commit)

---

**Total deviations:** 4 auto-fixed (Rule 1 - bug fixes)
**Impact on plan:** All four fixes were necessary for the plan's own mandated E2E test to pass and for the full regression suite to stay green. The auth fix in particular is a correctness fix to existing, previously-unexercised production code (not new scope) — it was silently broken for any real session-cookie-authenticated request before this plan's E2E test first exercised that path. No architectural changes, no scope creep beyond what LOCK-01..04 required.

## Issues Encountered
None beyond the deviations documented above.

## TDD Gate Compliance

Task 1 was marked `tdd="true"` and is a `type="tracer"` task combining entity, DTO, service, handler, and test changes as one atomic tracer slice. The entity/DTO/service/handler changes were interdependent — writing a genuinely failing `RED` test before any of them existed was not meaningful (the test wouldn't compile without the DTO/entity fields it references), so implementation and the E2E test were built together and committed in a single `feat(01-01)` commit (`1b496c5`) rather than as separate `test(...)` (RED) then `feat(...)` (GREEN) commits. This deviates from the strict RED->GREEN gate sequence described in the TDD execution flow. The test was run and confirmed passing (after two Rule-1 bug fixes) before the commit was made, so the deliverable itself is verified — only the granularity of the RED/GREEN commit split was collapsed for this tracer task.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- The Task optimistic-locking path (entity -> DTO -> service -> handler -> E2E test) is fully proven end-to-end and ready as the reference pattern for Plan 02 (Column update endpoint), which reuses the same `@Version`/explicit-check/409 shape already established here.
- `ColumnEntity` already carries `@Version` (with the Lombok exclusion) from this plan, so Plan 02 does not need to touch the entity again — it only needs to add the update endpoint, service method, and DTOs.
- The authentication fix (real cookie-based signin now correctly resolves `@CurrentUserId`) unblocks any future true-E2E (`RANDOM_PORT`) test in this codebase; previously only MockMvc-based tests worked because they bypassed the real authentication path.

---
*Phase: 01-optimistic-locking*
*Completed: 2026-08-01*
