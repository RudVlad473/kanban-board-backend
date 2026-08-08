---
phase: 06-mock-up-feature-gap-closure
plan: 02
subsystem: api
tags: [spring-boot, spring-data-jpa, rest-assured, board-creation, uniqueness-constraint]

# Dependency graph
requires:
  - phase: 06-01
    provides: V5 Flyway migration's uk_boards_user_id_name unique constraint, backstopping this plan's service-level check-then-act race
provides:
  - "POST /boards route (GAP-01), wiring the already-implemented UserService.addBoardByUserId onto BoardController with a 201 + Location-header created response"
  - "Per-user board-name uniqueness on both create and rename (D-09), resolving both identical long-standing TODOs"
  - "AppDuplicateResourceException + two new GlobalExceptionHandler 409 arms (checked path and DataIntegrityViolationException race backstop)"
  - "BoardRepository.existsByUserIdAndName(userId, name), the sole existence query, deliberately user-scoped"
affects: [06-03-PLAN, 06-04-PLAN, 06-05-PLAN, 06-06-PLAN, 06-07-PLAN]

# Actuals (#2632)
actuals:
  tokens: 14500
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "New AppDuplicateResourceException extends DataIntegrityViolationException so the checked service-layer guard and the unchecked database-constraint race resolve through the same 409 status via Spring's more-specific-exception-handler-wins resolution"
    - "Created-response construction (ResponseEntity.created(URI.create(request.getRequestURI()))) reused verbatim from ColumnController.addTaskByColumnId onto the new POST /boards mapping"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/exception/AppDuplicateResourceException.java
    - src/test/java/com/vrudenko/kanban_board/BoardCreationE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/controller/BoardController.java
    - src/main/java/com/vrudenko/kanban_board/service/UserService.java
    - src/main/java/com/vrudenko/kanban_board/service/BoardService.java
    - src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java
    - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
    - src/test/java/com/vrudenko/kanban_board/service/BoardServiceTest.java

key-decisions:
  - "A fully-unauthenticated POST /boards request (no session cookie at all) returns 403, not the plan's stated 401 -- verified as pre-existing, correct, framework-level behavior (Spring Security's default Http403ForbiddenEntryPoint, since SecurityConfiguration registers no custom AuthenticationEntryPoint/formLogin/httpBasic), not specific to this new route. Corrected the test's expectation to HttpStatus.FORBIDDEN rather than changing SecurityConfiguration, which would have widened the blast radius to every authenticated route in the app."
  - "409 (Approach A from the plan's design rationale) chosen for the duplicate-name conflict, matching GlobalExceptionHandler's existing state-conflict-vs-malformed-request distinction (400 reserved for IllegalArgumentException-style problems)."
  - "BoardService.updateById skips the uniqueness check entirely when the new name equals the board's current name, so a no-op rename never collides with its own row."

patterns-established:
  - "A new App*Exception can extend a broader Spring exception type (DataIntegrityViolationException here) specifically so a single new GlobalExceptionHandler arm backstops both the checked application path and the unchecked database-constraint race, with Spring's exception-resolution order (most specific wins) doing the dispatching."

requirements-completed: [GAP-01]

coverage:
  - id: D1
    description: "An authenticated user creates a board over HTTP with POST /boards carrying only {name}, receives 201 with a Location header and a populated BoardResponseDTO body, and the board appears in that user's next GET /boards."
    requirement: "GAP-01"
    verification:
      - kind: e2e
        ref: "BoardCreationE2ETest#CreateBoard.shouldReturnCreatedWithLocationHeaderAndBody_whenNameIsValid"
        status: pass
      - kind: e2e
        ref: "BoardCreationE2ETest#CreateBoard.shouldAppearInSubsequentGet_whenBoardCreated"
        status: pass
    human_judgment: false
  - id: D2
    description: "A blank name returns 400 before service code runs; a request with no session cookie at all returns 403 (not 401, per the corrected, verified pre-existing framework behavior) and creates no row."
    requirement: "GAP-01"
    verification:
      - kind: e2e
        ref: "BoardCreationE2ETest#CreateBoard.shouldReturnBadRequest_whenNameIsBlank"
        status: pass
      - kind: e2e
        ref: "BoardCreationE2ETest#CreateBoard.shouldReturnForbiddenAndCreateNoRow_whenNotAuthenticated"
        status: pass
    human_judgment: false
  - id: D3
    description: "Creating a board publishes BoardCreatedEvent via the already-wired UserService.addBoardByUserId path -- no new event type introduced (D-08 honoured); SaveBoardRequestDTO unchanged."
    requirement: "GAP-01"
    verification:
      - kind: other
        ref: "git diff --stat src/main/java/com/vrudenko/kanban_board/dto/board_dto/SaveBoardRequestDTO.java (empty, confirming no schema change)"
        status: pass
    human_judgment: false
  - id: D4
    description: "A second create with a name the same user already used returns 409 with the board count unchanged; renaming to a name already used by another board of the same user returns 409 with both boards keeping their original names; renaming a board to its own current name succeeds (200)."
    requirement: "GAP-01"
    verification:
      - kind: e2e
        ref: "BoardCreationE2ETest#DuplicateName.shouldReturnConflictAndLeaveCountUnchanged_whenCreatingBoardWithNameAlreadyUsedBySameUser"
        status: pass
      - kind: e2e
        ref: "BoardCreationE2ETest#RenameBoard.shouldReturnConflictAndLeaveNamesUnchanged_whenRenamingToNameAlreadyUsedByAnotherBoardOfSameUser"
        status: pass
      - kind: e2e
        ref: "BoardCreationE2ETest#RenameBoard.shouldReturnOk_whenRenamingBoardToItsOwnCurrentName"
        status: pass
      - kind: unit
        ref: "BoardServiceTest#UpdateByIdUniquenessTest.shouldThrowAppDuplicateResourceException_whenRenamingToNameAlreadyUsedByAnotherBoardOfSameUser"
        status: pass
      - kind: unit
        ref: "BoardServiceTest#UpdateByIdUniquenessTest.shouldSucceed_whenRenamingBoardToItsOwnCurrentName"
        status: pass
    human_judgment: false
  - id: D5
    description: "Two different users may each own a board with the same name -- uniqueness is scoped per user, never global."
    requirement: "GAP-01"
    verification:
      - kind: e2e
        ref: "BoardCreationE2ETest#CrossUserIsolation.shouldAllowBothCreates_whenTwoDifferentUsersUseIdenticalBoardName"
        status: pass
    human_judgment: false
  - id: D6
    description: "Two concurrent creates of the same name by the same user result in exactly one board -- the uk_boards_user_id_name database constraint backstops the service-level check-then-act window, rendered as 409 by the new DataIntegrityViolationException handler arm rather than falling through to the catch-all 500."
    requirement: "GAP-01"
    verification:
      - kind: e2e
        ref: "BoardCreationE2ETest#ConcurrentCreate.shouldPersistExactlyOneBoard_whenTwoRequestsCreateSameNameConcurrently"
        status: pass
    human_judgment: false

duration: 35min
completed: 2026-08-08
status: complete
---

# Phase 6 Plan 2: Board Creation Route and Per-User Name Uniqueness Summary

**POST /boards wired end-to-end onto the pre-existing UserService.addBoardByUserId (201 + Location header, inheriting BoardCreatedEvent for free), plus a new AppDuplicateResourceException giving both the board-create and board-rename paths a real per-user name-uniqueness guard backstopped by the uk_boards_user_id_name constraint.**

## Performance

- **Duration:** 35 min
- **Started:** 2026-08-08T18:19:05Z
- **Completed:** 2026-08-08T18:54:17Z
- **Tasks:** 3 completed
- **Files modified:** 8 (2 created, 6 modified)

## Accomplishments

- `BoardController` gained a bare `@PostMapping` under `ApiPaths.BOARDS`, injecting `UserService` (previously absent) and calling `userService.addBoardByUserId(userId, dto)`, with the created-response construction (`ResponseEntity.created(URI.create(request.getRequestURI()))`) copied verbatim from `ColumnController.addTaskByColumnId`'s precedent.
- `BoardRepository.existsByUserIdAndName(userId, name)` -- the single, deliberately user-scoped existence query (no name-only variant exists, closing off the information-disclosure vector the threat model flagged).
- New `AppDuplicateResourceException`, modeled on `AppEntityNotFoundException`'s shape, extending `DataIntegrityViolationException` so it sits in the same Spring DAO family as the constraint violation it shadows.
- `GlobalExceptionHandler` gained two 409 arms: the checked `AppDuplicateResourceException` path, and a broader `DataIntegrityViolationException` arm (with an explicit comment recording that Spring resolves the more specific arm first) catching a `uk_boards_user_id_name` violation that slips past the service-level check.
- Both identical `// TODO: Disallow duplicating board names for a single user` comments (`UserService.addBoardByUserId`, `BoardService.updateById`) are gone, replaced by real guards -- `UserService` scopes by the verified user entity's id, `BoardService.updateById` scopes by `boardToUpdate.getUser().getId()` and skips the check entirely for a no-op rename.
- `BoardCreationE2ETest` (new, 9 test methods across 5 `@Nested` groups) proves the full HTTP surface: 201 create with Location header, appearance in a subsequent GET, 400 on blank name, 403 on no session cookie (corrected from the plan's stated 401 -- see Deviations), 409 on duplicate create, 409 on duplicate rename with both boards' names left unchanged, 200 on no-op rename, cross-user name-sharing, and a same-name concurrent-create race (`ExecutorService`/`CountDownLatch`, mirroring `ActivityLogIdempotencyE2ETest`) leaving exactly one board.
- `BoardServiceTest.UpdateByIdUniquenessTest` (new, 2 test methods) proves the same two service-level cases using `Assertions.catchException` per `docs/CODE_STYLE.md` rule 3.

## Task Commits

Each task was committed atomically:

1. **Task 1: End-to-end board creation over HTTP** - `8475add` (feat)
2. **Task 2: Per-user board-name uniqueness on both create and rename** - `6333735` (feat)
3. **Task 3: Prove uniqueness scoping, the no-op rename, and the concurrent-create backstop** - `fbce9bf` (test)

**Plan metadata:** this SUMMARY.md's commit (created immediately after this file, per the atomic close-out protocol)

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/exception/AppDuplicateResourceException.java` - new exception, extends `DataIntegrityViolationException`
- `src/test/java/com/vrudenko/kanban_board/BoardCreationE2ETest.java` - new E2E tracer, 9 test methods, 5 `@Nested` groups
- `src/main/java/com/vrudenko/kanban_board/controller/BoardController.java` - `+ POST /boards` mapping, `+ userService` field
- `src/main/java/com/vrudenko/kanban_board/service/UserService.java` - `+ boardRepository` field, uniqueness guard in `addBoardByUserId`
- `src/main/java/com/vrudenko/kanban_board/service/BoardService.java` - uniqueness guard (with no-op-rename skip) in `updateById`
- `src/main/java/com/vrudenko/kanban_board/repository/BoardRepository.java` - `+ existsByUserIdAndName`
- `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java` - `+ 2` `@ExceptionHandler` arms (12 → 14 occurrences of "ExceptionHandler")
- `src/test/java/com/vrudenko/kanban_board/service/BoardServiceTest.java` - `+ UpdateByIdUniquenessTest` nested class, 2 test methods

## Decisions Made

- A fully-unauthenticated `POST /boards` (no session cookie at all) returns 403, not the plan's stated 401. Verified this is pre-existing, correct, framework-level behavior -- Spring Security's default `Http403ForbiddenEntryPoint`, since `SecurityConfiguration` registers no custom `AuthenticationEntryPoint`/`formLogin`/`httpBasic` -- rather than a bug in the new route. See Deviations below for why the fix was the test's expectation, not the security config.
- 409 (Approach A from the plan's design rationale) for the duplicate-name conflict, matching `GlobalExceptionHandler`'s existing 400-vs-409 semantic split.
- `BoardService.updateById` skips the uniqueness check entirely when the new name equals the board's current name -- a no-op rename must not collide with its own row.
- `AppDuplicateResourceException` extends `DataIntegrityViolationException` (not a plain exception) specifically so one `GlobalExceptionHandler` arm backstops the unchecked database-constraint race and Spring's exception resolution (most-specific-wins) still dispatches the checked path to its own, more specific arm.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug/pre-existing] Corrected the "unauthenticated POST" test's expected status from 401 to 403**

- **Found during:** Task 1 (End-to-end board creation over HTTP), first test run
- **Issue:** The plan's `<behavior>` block and must-haves table state an unauthenticated POST returns 401 (flagged `verification: backstop`, the plan's own lower-confidence marker). The actual, measured response for a request carrying no session cookie at all is 403.
- **Investigation:** Confirmed this is not specific to the new route: `SecurityConfiguration` registers no custom `AuthenticationEntryPoint` and no `formLogin()`/`httpBasic()`, so Spring Security's default `Http403ForbiddenEntryPoint` applies uniformly to every `@PreAuthorize("isAuthenticated()")` route whenever the security context is empty. No existing test in this codebase previously exercised a fully-unauthenticated (zero-cookie) request against any route, so this had never been directly observed and written down before.
- **Fix:** Renamed the test to `shouldReturnForbiddenAndCreateNoRow_whenNotAuthenticated` and changed the assertion to `HttpStatus.FORBIDDEN`, with an inline comment recording the reasoning, rather than adding a custom `AuthenticationEntryPoint` to `SecurityConfiguration` -- which would have been an application-wide security-behavior change, entirely out of this plan's board-creation scope, to make one test's guessed status code match.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/BoardCreationE2ETest.java`
- **Verification:** `./gradlew test --tests '*BoardCreationE2ETest'` green after the fix; reconfirmed by the full `./gradlew spotlessCheck test` run at Task 3.
- **Committed in:** `8475add` (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 pre-existing framework-behavior correction)
**Impact on plan:** No scope creep and no production-code change beyond the plan's stated files. The single deviation corrects a test's expected value against measured, verified, pre-existing behavior; it does not touch `SecurityConfiguration` or any other file outside this plan's stated `files_modified` list.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `POST /boards` (GAP-01) is fully closed: routed, tested end-to-end, and per-user name uniqueness is enforced on both create and rename with both long-standing TODOs resolved.
- `uk_boards_user_id_name` (plan 01's V5) is now exercised by a real concurrency test, not just present in the schema.
- No blockers or concerns carried forward. This plan's `files_modified` list did not overlap with sibling plan 06-03's, per the parallel-execution contract; no merge conflict risk expected.

---
*Phase: 06-mock-up-feature-gap-closure*
*Completed: 2026-08-08*
