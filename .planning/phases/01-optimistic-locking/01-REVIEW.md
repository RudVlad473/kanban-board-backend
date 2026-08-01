---
phase: 01-optimistic-locking
reviewed: 2026-08-01T11:40:00Z
depth: standard
files_reviewed: 18
files_reviewed_list:
  - docs/plans/backend-modernization/02-optimistic-locking-ddl.sql
  - docs/plans/backend-modernization/STATUS.md
  - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java
  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/task_dto/TaskResponseDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java
  - src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java
  - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
  - src/main/java/com/vrudenko/kanban_board/security/CurrentUserIdResolver.java
  - src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
  - src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
  - src/main/java/com/vrudenko/kanban_board/service/TaskService.java
  - src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/e2e/task/TaskLockingE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/service/TaskServiceTest.java
findings:
  critical: 0
  warning: 4
  info: 3
  total: 7
status: issues_found
---

# Phase 01: Code Review Report

**Reviewed:** 2026-08-01T11:40:00Z
**Depth:** standard
**Files Reviewed:** 18
**Status:** issues_found

## Summary

Reviewed the optimistic-locking implementation (`@Version` on `ColumnEntity`/`TaskEntity`, explicit
version-check-then-update in `ColumnService`/`TaskService`, `409 CONFLICT` mapping in
`GlobalExceptionHandler`) and the accompanying auth-path fix (`UserAuthenticationProvider` now stores
the full `UserDetails` principal instead of a bare `userId` string; `CurrentUserIdResolver` now checks
`instanceof UserDetails` instead of the concrete Spring `User` class).

The core locking logic is sound and well-tested: the explicit `dto.getVersion()` vs.
`entity.getVersion()` check correctly closes the same-transaction blind spot that bare `@Version`
dirty-checking would miss across separate HTTP requests, the `entityManager.flush()` calls correctly
force the version bump before the response DTO is built, and the E2E tests genuinely exercise the
concurrent-conflict scenario end-to-end (verified by running `ColumnLockingE2ETest` and
`TaskLockingE2ETest` locally — 6/6 pass). The `423 LOCKED` → `409 CONFLICT` fix in
`GlobalExceptionHandler` is correct and matches HTTP semantics for a client-retriable conflict.

The auth fix is a real, necessary correctness fix (the old code set `UsernamePasswordAuthenticationToken(userId, ...)` — a bare `String` principal — while the resolver checked
`principal instanceof User` (Spring's concrete `org.springframework.security.core.userdetails.User`),
so a session round-tripped through `spring_session` (JDBC-backed, `store-type=jdbc` in both prod and
test profiles) would never match, breaking every authenticated request after the first). The fix
itself is correct in isolation, but it changes what gets serialized into the JDBC-backed session store
on every request — see WR-01 below, a genuine (if lower-severity) new exposure introduced specifically
by this fix, not present before it.

Two version-check edge cases (no-op updates not bumping version despite passing the version gate;
`Long.equals` NPE potential if an entity's version were ever null) are flagged as warnings — neither
causes data loss, but both weaken the guarantee the feature is meant to provide or the code's
defensiveness.

## Warnings

### WR-01: Password hash now flows into the JDBC-backed session store on every authenticated request

**File:** `src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java:32`
**Issue:** `authenticate()` now returns
`new UsernamePasswordAuthenticationToken(userDetails, null, new ArrayList<>())`, where `userDetails`
is the full `UserEntity` (id, email, displayName, `passwordHash`, lazy `boards` collection) rather
than the previous bare `userId` `String`. `HttpSessionSecurityContextRepository` stores this
`Authentication` (principal included) in the servlet session, and
`spring.session.store-type=jdbc` (set in both `application.properties` and
`application-test.properties`) means Spring Session JDBC persists the serialized session attributes —
including `SPRING_SECURITY_CONTEXT`, containing the bcrypt `passwordHash` — into the
`spring_session_attributes` table for the life of the session (180 min per
`spring.session.timeout`). Before this change, only the bare `userId` string ever left the
authentication path into session storage.

This is a genuine, new increase in what's at rest in a table that a broader class of readers (backups,
replicas, a future reporting/analytics service with read access to `spring_session*` tables, a
different SQL injection elsewhere in the schema) might reach without needing direct access to the
`users` table. It doesn't defeat `@JsonIgnore` (that only protects the HTTP JSON response), and it
doesn't break anything today, but it's an avoidable expansion of the password-hash blast radius
introduced specifically by this fix.
**Fix:** Store a minimal, purpose-built principal instead of the full JPA entity — e.g. build a
`org.springframework.security.core.userdetails.User` (or a small custom `UserDetails` DTO) from
`userDetails.getUsername()` / `getAuthorities()` without carrying `passwordHash` (set credentials to
`""` or a constant placeholder, since Spring Security's `UserDetails.getPassword()` contract doesn't
require the *real* hash to be present on the post-authentication principal):
```java
var authenticatedPrincipal =
    org.springframework.security.core.userdetails.User
        .withUsername(userDetails.getUsername())
        .password("")
        .authorities(new ArrayList<>())
        .build();
return new UsernamePasswordAuthenticationToken(authenticatedPrincipal, null, new ArrayList<>());
```
This keeps `CurrentUserIdResolver`'s `instanceof UserDetails` check working (Spring's own `User` class
still implements `UserDetails`) while no longer round-tripping the hash through session storage.

### WR-02: Version-gated update can return 200 with an unchanged version when the payload is a no-op

**File:** `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:76-100`, `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java:96-117`
**Issue:** The explicit version check (`if (!task.getVersion().equals(dto.getVersion())) throw ...`)
only guards against a *stale* version. It does not guard against — and the code does not account for
— a request that passes the version check but sets `title`/`description` (or `name`, for columns) to
the value the field already has. Hibernate's dirty-checking will not emit an `UPDATE` for a field set
to its current value, so `@Version` will not increment, and `entityManager.flush()` becomes a no-op.
The endpoint still returns `200 OK` with the *same* version the client submitted (not `version + 1`),
silently breaking the "every successful write bumps the version" invariant the rest of the design
(and every test in `ColumnLockingE2ETest`/`TaskLockingE2ETest`) relies on — a client that reasonably
assumes "200 response implies my write happened and the version moved forward" gets a version that
looks stale but isn't. This is a genuine edge case gap, not exercised by any of the new tests (all use
`dataFactory`-generated distinct values).
**Fix:** Either (a) explicitly document this as accepted no-op behavior, or (b) force the version bump
regardless of whether tracked fields actually changed, e.g. via
`@OptimisticLocking(type = OptimisticLockType.FORCE_INCREMENT)` / `@DynamicUpdate` tuning on the two
entities, or (c) short-circuit no-op updates at the service layer with a comment explaining the
intentional non-bump. Given the phase's own stated goal ("closing out ... optimistic locking"), (b) or
an explicit doc comment is the low-risk fix.

### WR-03: `entity.getVersion().equals(dto.getVersion())` will NPE instead of failing safely if `version` is ever null

**File:** `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:79`, `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java:101`
**Issue:** `task.getVersion().equals(dto.getVersion())` dereferences `task.getVersion()` directly.
Both entities declare `@Column(nullable = false)` for `version`, and the DDL script backs that with
`DEFAULT 0`, so under normal operation this is never null. However, this is the only null-unsafe
comparison in an otherwise defensive codebase (`Optional.ofNullable(...)` is used two lines below for
the same DTO in `TaskService`), and a `NullPointerException` here is caught by the generic
`@ExceptionHandler(Exception.class)` in `GlobalExceptionHandler`, which returns
`500 INTERNAL_SERVER_ERROR` with the raw NPE message — a worse failure mode than the
`409 CONFLICT`/`404`/`400` this endpoint otherwise produces for every other invalid-state path. If a
row somehow predates the DDL bridge script (e.g., DDL not yet run against a given environment, per the
explicit one-off manual-migration risk called out in `STATUS.md`/`02-optimistic-locking-ddl.sql`),
every update to that row 500s instead of producing a clear error.
**Fix:** Guard with `Objects.equals(task.getVersion(), dto.getVersion())` (also handles the DTO side,
though `@NotNull` already prevents that) instead of `task.getVersion().equals(...)`, matching the
`Optional.ofNullable` defensiveness already used elsewhere in the same method.

### WR-04: `ColumnEntity`/`TaskEntity` diverge on `@EqualsAndHashCode` handling of the new `version` field

**File:** `src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java:34-37`, `src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java:36-38`
**Issue:** `ColumnEntity` correctly annotates the new `version` field with `@EqualsAndHashCode.Exclude`
(important because `@Version` is meant to be a bookkeeping field, not part of entity identity/equality,
and its class-level `@EqualsAndHashCode(callSuper = false)` is active). `TaskEntity` adds the identical
`@Version` field but has no equivalent exclusion — because `TaskEntity`'s own `@EqualsAndHashCode` is
already commented out (`// @EqualsAndHashCode(callSuper = false)`, pre-existing, not part of this
diff), so today this is inert. It's still an inconsistency worth a comment: if `TaskEntity`'s
`@EqualsAndHashCode` is ever re-enabled without noticing the sibling `ColumnEntity` pattern, `version`
would silently become part of task identity/equality, which is the exact bug `ColumnEntity`'s exclude
was added to prevent.
**Fix:** Either add `@EqualsAndHashCode.Exclude` on `TaskEntity.version` now (defensive, costs nothing
while `@EqualsAndHashCode` stays disabled) or leave a short comment noting the two entities must stay
in sync if `TaskEntity`'s `@EqualsAndHashCode` is ever restored.

## Info

### IN-01: `ColumnController.updateById` does not use the `boardId` path variable for authorization

**File:** `src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java:48-54`
**Issue:** Ownership is verified solely via `columnId` (through
`OwnershipVerifierService.verifyOwnershipOfColumn`, which walks `column → board → user`), so the
`boardId` segment in the URL is effectively decorative — a request to
`/boards/{wrong-board}/columns/{real-column-i-own}` still succeeds. This is pre-existing behavior
(unchanged by this phase, and access control is still correctly enforced end-to-end via the
`columnId`-rooted chain — no cross-user access is possible), so not a regression, but worth a note
since the endpoint's URL shape implies a `boardId`-scoped check that isn't actually performed.
**Fix:** No action required for this phase; if addressed, either validate `column.getBoard().getId()
.equals(boardId)` explicitly or drop `boardId` from the path variable list to avoid implying a check
that doesn't happen.

### IN-02: `ColumnControllerTest` / `TaskControllerTest` `..._whenColumnDoesNotExist` tests carry stale exploratory comments

**File:** `src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java:92-105`
**Issue:** Pre-existing (not introduced by this phase) block comment
("This depends on ColumnService.findAllByBoardId behavior ... Or handle as per actual service
behavior") reads as leftover exploratory reasoning rather than a documented test intent. Not part of
this diff's changed lines, flagged only because it sits directly adjacent to the new `UpdateById`
nested test class and stands out by contrast with the clean, assertion-focused style of the new tests.
**Fix:** Optional cleanup — replace with a one-line comment stating the asserted behavior
(404 on nonexistent board) as fact, not speculation.

### IN-03: `deleteAllByBoardId` / `deleteAllByColumn` version-bypass asymmetry is well-documented but still worth flagging as intentional tech debt

**File:** `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java:33-50`, `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:118-145`
**Issue:** Not a bug — the Javadoc on both methods explicitly and thoroughly documents that
column-cascade deletes honor `@Version` (derived delete, per-entity) while task/subtask bulk deletes
bypass it (raw JPQL bulk delete), and gives a reasoned "delete wins" justification. Flagging only so
this asymmetry is visible in the review record: a delete-path race with a version-mismatched update on
the task/subtask side is unconditionally allowed to proceed even though the equivalent race on the
column-delete side would throw `OptimisticLockingFailureException`. Given the explicit acceptance
already written into both docstrings, no action is being requested here.
**Fix:** None required; carried forward as accepted, documented tradeoff.

---

_Reviewed: 2026-08-01T11:40:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
