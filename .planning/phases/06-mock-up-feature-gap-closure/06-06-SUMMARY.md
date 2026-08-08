---
phase: 06-mock-up-feature-gap-closure
plan: 06
subsystem: api
tags: [spring-boot, spring-security, spring-data-jpa, rest-assured, user-preferences, theme]

# Dependency graph
requires:
  - phase: 06-01
    provides: UserEntity.theme column (V5 migration, @Enumerated(EnumType.STRING), NOT NULL default LIGHT) and ThemePreference enum
  - phase: 06-02
    provides: precedent for the 403-not-401 unauthenticated-request finding, reused here rather than re-investigated
provides:
  - "GET/PUT /users/me/theme (GAP-05), a new session-scoped UserController mirroring BoardController's shape"
  - "Per-user theme persistence proven across logout/fresh-signin, not session-scoped"
  - "UserResponseDTO.theme field, mapped by name via existing UserMapper (no mapper change needed)"
  - "GlobalExceptionHandler HttpMessageNotReadableException -> 400 arm, generalizable beyond this one DTO"
affects: []

# Actuals (#2632)
actuals:
  tokens: 20000
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Session-derived 'me' route segment (no user-id path variable anywhere) as the structural IDOR mitigation for a controller with no ownership chain above it -- UserService is the identity root"
    - "HttpMessageNotReadableException given its own GlobalExceptionHandler arm so Jackson enum-deserialization failures surface as 400, not the Exception.class catch-all's 500"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/controller/UserController.java
    - src/main/java/com/vrudenko/kanban_board/dto/user_dto/UpdateThemeRequestDTO.java
    - src/test/java/com/vrudenko/kanban_board/ThemePersistenceE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/dto/user_dto/UserResponseDTO.java
    - src/main/java/com/vrudenko/kanban_board/service/UserService.java
    - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
    - src/test/java/com/vrudenko/kanban_board/service/UserServiceTest.java

key-decisions:
  - "A fully-unauthenticated GET/PUT (no session cookie at all) returns 403, not the plan's stated 401 -- the same pre-existing Http403ForbiddenEntryPoint framework behavior plan 06-02 already found for POST /boards, reused rather than re-investigated. Tests assert FORBIDDEN with an inline comment pointing at the shared reasoning."
  - "UpdateThemeRequestDTO deliberately carries no @JsonInclude(NON_NULL) and no version field, despite docs/CODE_STYLE.md rule 6's usual Update*RequestDTO shape -- theme is a whole-value replacement on a non-@Version-guarded entity, documented in the DTO's own Javadoc so the absence reads as intentional."
  - "Theme writes are last-write-wins by design (T-06-29, accepted risk) -- UserEntity carries no @Version and this plan does not add one, since rejecting a user's own preference toggle with a 409 would be worse than applying it."

patterns-established:
  - "UserController: session-derived identity root controller shape (@RestController, class-level @PreAuthorize(\"isAuthenticated()\"), @CurrentUserId on every method, zero @PathVariable) -- the template for any future user-scoped preference that has no ownership chain to check against."

requirements-completed: [GAP-05]

coverage:
  - id: D1
    description: "A freshly signed-up user's theme reads back as LIGHT over GET /users/me/theme without the client ever sending a theme value."
    requirement: "GAP-05"
    verification:
      - kind: e2e
        ref: "ThemePersistenceE2ETest#GetTheme.shouldReturnLight_whenUserHasNoExplicitPreference"
        status: pass
    human_judgment: false
  - id: D2
    description: "PUT DARK returns 200 with DARK in the body; a subsequent GET also returns DARK."
    requirement: "GAP-05"
    verification:
      - kind: e2e
        ref: "ThemePersistenceE2ETest#UpdateTheme.shouldReturnOkWithDark_whenWritingDark"
        status: pass
      - kind: e2e
        ref: "ThemePersistenceE2ETest#UpdateTheme.shouldReturnDarkOnSubsequentGet_whenDarkWasJustWritten"
        status: pass
    human_judgment: false
  - id: D3
    description: "A theme value outside the two-member enum returns 400 and leaves the stored value unchanged; a missing/null theme also returns 400."
    requirement: "GAP-05"
    verification:
      - kind: e2e
        ref: "ThemePersistenceE2ETest#UpdateTheme.shouldReturnBadRequestAndLeaveValueUnchanged_whenThemeIsUnknownValue"
        status: pass
      - kind: e2e
        ref: "ThemePersistenceE2ETest#UpdateTheme.shouldReturnBadRequest_whenThemeIsMissing"
        status: pass
    human_judgment: false
  - id: D4
    description: "An unauthenticated GET or PUT of the theme route is rejected (measured as 403, corrected from the plan's stated 401 -- see Deviations) rather than succeeding."
    requirement: "GAP-05"
    verification:
      - kind: e2e
        ref: "ThemePersistenceE2ETest#GetTheme.shouldReturnForbidden_whenNotAuthenticated"
        status: pass
      - kind: e2e
        ref: "ThemePersistenceE2ETest#UpdateTheme.shouldReturnForbidden_whenNotAuthenticated"
        status: pass
    human_judgment: false
  - id: D5
    description: "UserController carries no user-id path variable, both methods derive identity from @CurrentUserId, the class carries one @PreAuthorize, and AuthenticationController is provably unchanged -- the IDOR mitigation is structural."
    requirement: "GAP-05"
    verification:
      - kind: other
        ref: "grep -c PathVariable UserController.java == 0; grep -c CurrentUserId == 3 (import + 2 usages); grep -c PreAuthorize == 2 (import + annotation); git diff --stat d2502be 5ca7a65 -- AuthenticationController.java is empty"
        status: pass
    human_judgment: false
  - id: D6
    description: "The stored theme survives logout and a fresh signin from a brand-new session -- proving persistence lives in the users table, not HttpSession."
    requirement: "GAP-05"
    verification:
      - kind: e2e
        ref: "ThemePersistenceE2ETest#UpdateTheme.shouldReturnDark_whenLoggingOutAndSigningInAgainAfterWritingDark"
        status: pass
    human_judgment: false
  - id: D7
    description: "The theme column is stored as the enum's STRING form ('DARK'), not an ordinal integer -- proves @Enumerated(EnumType.STRING) is actually in force, invisible at the HTTP/Jackson layer."
    requirement: "GAP-05"
    verification:
      - kind: unit
        ref: "UserServiceTest#UpdateTheme.shouldPersistThemeAsEnumStringForm_whenUpdatingTheme"
        status: pass
    human_judgment: false
  - id: D8
    description: "Two different users hold independent themes -- one user's write does not leak to another's read."
    requirement: "GAP-05"
    verification:
      - kind: e2e
        ref: "ThemePersistenceE2ETest#UpdateTheme.shouldBeIndependentPerUser_whenTwoUsersSetDifferentThemes"
        status: pass
    human_judgment: false
  - id: D9
    description: "The signup response body itself carries the default theme, so a client never needs a second call to learn it."
    requirement: "GAP-05"
    verification: []
    human_judgment: true
    rationale: "Not implemented. AuthenticationController.signup() returns ResponseEntity.created(...).build() with no body today, a pre-existing condition unrelated to this plan. AuthenticationController.java is outside this plan's files_modified list and is explicitly the one controller this plan must not add routes to (prohibition 2); giving its signup response a body is a shared-file, cross-cutting API-contract change (it affects every existing signup caller) that this plan should not make silently under a two-test-file task scope. UserService.save() (the DTO signup already produces, which AuthenticationController.signup() calls and discards) does return the LIGHT default -- proven indirectly by every fixture in this suite going through it -- but no test asserts it directly, and the literal HTTP-response-body claim is unverifiable given the current controller. Flagged here for a human/future-plan call on whether to wire the theme onto a real signup response body.

duration: ~2h11m elapsed (21:29-23:40), including an unplanned mid-task stall; see Issues Encountered
completed: 2026-08-08
status: complete
---

# Phase 6 Plan 6: Per-User Theme Persistence Summary

**GET/PUT /users/me/theme (GAP-05) on a new session-scoped UserController, proving the theme survives logout/fresh-signin and is stored as the enum's STRING form per user -- recovered after a mid-task stall left task 2's tests written but uncommitted.**

## Performance

- **Duration:** ~2h11m elapsed across two commits (21:29:05 -> 23:40:12); real working time was substantially less -- an unplanned mid-task stall (see Issues Encountered) accounts for most of the gap, not active work.
- **Started:** 2026-08-08T21:02:27+02:00 (prior commit d2502be)
- **Completed:** 2026-08-08T23:40:12+02:00
- **Tasks:** 2 completed
- **Files modified:** 7 (3 created, 4 modified)

## Accomplishments

- New `UserController` (`GET`/`PUT /users/me/theme`), mirroring `BoardController`'s shape exactly: class-level `@PreAuthorize("isAuthenticated()")`, `@Validated`, field-injected `UserService`, both methods taking `@CurrentUserId String userId` and zero `@PathVariable` anywhere -- the whole IDOR mitigation is structural, since `UserService` is the identity root with no ownership chain above it.
- `UserService.updateTheme` / `findThemeByUserId`, both routed through the existing `findById(String)` guard (`AppEntityNotFoundException` on a missing user).
- `UserResponseDTO` gained a `theme` field; `UserMapper.toResponseDTO` needed no change since it maps by name.
- New `UpdateThemeRequestDTO`: single `@NotNull ThemePreference theme` field, deliberately without `@JsonInclude(NON_NULL)` or a `version` field, with class Javadoc explaining both omissions (whole-value replacement; `UserEntity` carries no `@Version`).
- `GlobalExceptionHandler` gained an `HttpMessageNotReadableException` -> 400 arm so an unknown/malformed theme value fails as a validation error rather than falling through to the `Exception.class` catch-all's 500.
- `ThemePersistenceE2ETest` (new, 9 test methods across 2 `@Nested` groups): LIGHT default, DARK write + read-back, 400 on an unknown value (value left unchanged), 400 on a missing value, 403 on both unauthenticated routes, cross-session persistence (PUT DARK -> logout -> fresh signin -> GET still DARK), and per-user independence.
- `UserServiceTest.UpdateTheme` (new, 1 test method): direct-JDBC assertion that the stored column holds `"DARK"` (the enum's string form), not an ordinal, proving `@Enumerated(EnumType.STRING)` is actually in force.
- Full suite green: 247 tests, 0 failures, 0 errors (`./gradlew spotlessCheck test`), measured directly from `build/test-results/test/*.xml` on the final recovery run.

## Task Commits

Each task was committed atomically:

1. **Task 1: End-to-end theme read and write over HTTP** - `8cb0788` (feat)
2. **Task 2: Prove the theme is persisted per user, not held in session state** - `5ca7a65` (test)

**Plan metadata:** this SUMMARY.md's commit (created immediately after this file, per the atomic close-out protocol)

_Note: task 2's commit (`5ca7a65`) landed mid-recovery -- see Issues Encountered for how this SUMMARY's authoring session found it already committed._

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/controller/UserController.java` - new, `GET`/`PUT /users/me/theme`
- `src/main/java/com/vrudenko/kanban_board/dto/user_dto/UpdateThemeRequestDTO.java` - new request DTO
- `src/test/java/com/vrudenko/kanban_board/ThemePersistenceE2ETest.java` - new E2E tracer, 9 test methods, 2 `@Nested` groups
- `src/main/java/com/vrudenko/kanban_board/dto/user_dto/UserResponseDTO.java` - `+ theme` field
- `src/main/java/com/vrudenko/kanban_board/service/UserService.java` - `+ updateTheme`, `+ findThemeByUserId`
- `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java` - `+ 1` `@ExceptionHandler` arm (`HttpMessageNotReadableException` -> 400)
- `src/test/java/com/vrudenko/kanban_board/service/UserServiceTest.java` - `+ UpdateTheme` nested class, 1 test method

## Decisions Made

- A fully-unauthenticated request (no session cookie) returns 403, not the plan's stated 401 -- reused plan 06-02's already-established finding (Spring Security's default `Http403ForbiddenEntryPoint`, since `SecurityConfiguration` registers no custom `AuthenticationEntryPoint`) rather than re-investigating; test names and comments cross-reference that precedent.
- `UpdateThemeRequestDTO` skips both `@JsonInclude(NON_NULL)` and a `version` field, with the rationale written into the class Javadoc rather than left implicit, so it doesn't read as an oversight against `docs/CODE_STYLE.md` rule 6.
- Theme writes are accepted last-write-wins (no `@Version` on `UserEntity`) -- a deliberate, documented deviation from the version-guarded pattern the rest of this phase follows (T-06-29 in the plan's threat model, disposition: accept).

## Deviations from Plan

### Known Gap (not auto-fixed, flagged for follow-up)

**1. [Behavior not implemented] Signup response body does not carry the default theme**

- **Found during:** Task 2 (persistence + independence tests), while comparing the plan's `<behavior>` block against test coverage.
- **Issue:** The plan's task 2 `<behavior>` block states "A signup response body carries the default theme, so a client never has to make a second call to learn it." `AuthenticationController.signup()` returns `ResponseEntity.created(...).build()` with no body at all -- a pre-existing condition, unrelated to this plan, confirmed by reading the controller directly.
- **Why not fixed:** `AuthenticationController.java` is not in this plan's `files_modified` list, and is explicitly the one controller this plan must not add routes to (prohibition 2 in the plan's `must_haves`). Giving its signup response a body is a shared-file, cross-cutting change to every existing signup caller's contract -- not something a two-test-file task should do silently. `UserService.save()` (the DTO that controller method already produces and discards) does return the LIGHT default, exercised indirectly by every test fixture in the suite, but no test asserts it directly and the literal HTTP-response-body claim is unverifiable as the code stands.
- **Disposition:** Not gated by task 2's actual `acceptance_criteria` list (full suite green, cross-session test, storage-level assertion, two-user independence test, no new `@Disabled`) -- all five of those are met. Recorded as coverage item D9 with `human_judgment: true` for a human or a future plan to decide whether wiring the theme onto a real signup response body is worth the shared-file change.
- **Files modified:** None (intentionally left as-is).
- **Verification:** N/A (not implemented).

---

**Total deviations:** 1 known gap, not auto-fixed.
**Impact on plan:** No scope creep, no production-code change beyond this plan's stated files. The gap is between the plan's descriptive `<behavior>` block and its actual `acceptance_criteria` gate; the latter is fully met.

## Issues Encountered

- **Mid-task stall and concurrent recovery.** This plan's task 2 was originally left with test code written but uncommitted after an executor session went dormant (root cause unknown; possibly a stdin-inheritance hang in a nested subprocess, since fixed elsewhere in `.githooks/pre-commit`). A recovery session was started in this same worktree to verify and commit that work. Partway through the recovery session's own verification pass (spotless + targeted tests), a review against the plan's `<behavior>` block surfaced the signup-default-theme gap above, and the recovery session added a service-layer test for it (`UserServiceTest.Save.shouldReturnDefaultTheme_whenSigningUp`). Before that addition could be committed, the working tree was found to already contain a commit (`5ca7a65`) -- apparently written by the original, not-actually-dead executor process resuming independently in the same worktree -- covering task 2 without that fourth test, but with the same signup-body gap already identified and documented in its own commit message. The recovery session's extra test was superseded (silently overwritten on disk) by that commit. No data was lost from the shipped feature; the working tree was verified clean and quiescent (no lock files, no live `java`/`gradle` processes) before this SUMMARY was authored, and the full suite was re-run against the final, actually-committed state to confirm it independently. This concurrency -- two agent processes writing to the same worktree without coordination -- is worth the orchestrator's attention even though the outcome here was benign.
- No other issues.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- GAP-05 is closed to the extent gated by this plan's acceptance criteria: theme is persisted server-side per user, survives logout/fresh-signin, is stored as a readable string, and is independent per user.
- Open item for a future plan or human decision: whether `AuthenticationController.signup()` should return a body (carrying the default theme) at all -- currently it returns none, for any field, not just theme. Tracked as coverage item D9 in this summary.
- No blockers carried forward for other 06-xx plans; this plan's file scope did not overlap with sibling plans' `files_modified`.

---
*Phase: 06-mock-up-feature-gap-closure*
*Completed: 2026-08-08*
