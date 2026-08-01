---
phase: 01-optimistic-locking
verified: 2026-08-01T11:40:00Z
status: passed
score: 10/10 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 1: Optimistic Locking Verification Report

**Phase Goal:** Concurrent conflicting updates to the same task or column are detected and rejected with HTTP 409 Conflict instead of silently overwriting each other, with entity identity preserved across saves and the real Postgres schema updated to match.
**Verified:** 2026-08-01T11:40:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `TaskEntity` and `ColumnEntity` each carry `@Version private Long version` mapped `@Column(nullable=false)` | ✓ VERIFIED | `TaskEntity.java:36-38`, `ColumnEntity.java:34-37` — both read directly, confirmed present |
| 2 | `@Version` is scoped only to `TaskEntity`/`ColumnEntity`, never `BaseEntity` (no locking blast radius on User/Board/Subtask) | ✓ VERIFIED | `grep -rn "@Version" src/main/java/.../entity/` returns exactly `ColumnEntity.java` and `TaskEntity.java`; `BaseEntity.java` read directly — only `@Id @RandFlakeId protected String id` |
| 3 | `ColumnEntity.version` is excluded from Lombok-generated `equals`/`hashCode` (LOCK-04) | ✓ VERIFIED | `ColumnEntity.java:35` — `@EqualsAndHashCode.Exclude` directly above the `version` field, `@Data`/`@EqualsAndHashCode(callSuper=false)` active at class level |
| 4 | `GlobalExceptionHandler.handleOptimisticLockingFailure` returns `HttpStatus.CONFLICT` (409), not `HttpStatus.LOCKED` (423) (LOCK-02) | ✓ VERIFIED | `GlobalExceptionHandler.java:80-84` returns `HttpStatus.CONFLICT`; `grep -n "HttpStatus.LOCKED"` on the file returns no matches |
| 5 | `TaskService.updateById`/`ColumnService.updateById` compare caller-supplied version against the loaded entity's version BEFORE mutating, throwing `OptimisticLockingFailureException` on mismatch (D-02) | ✓ VERIFIED | `TaskService.java:79-82`, `ColumnService.java:101-104` — version comparison precedes all setter calls in both methods |
| 6 | Of two concurrent conflicting updates to the same task/column, exactly one succeeds (200) and the other receives HTTP 409, including a re-submitted stale request after the first 409 (LOCK-03) | ✓ VERIFIED | Behavioral test: `TaskLockingE2ETest.concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict` and `ColumnLockingE2ETest.concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict` — real RANDOM_PORT HTTP via RestAssured, both re-run fresh (`--rerun`), both pass (0 failures, 0 errors) |
| 7 | `UpdateTaskRequestDTO`/`UpdateColumnRequestDTO` require (`@NotNull`) a client-supplied `version`; `TaskResponseDTO`/`ColumnResponseDTO` expose `version` on all response paths (D-01, D-02) | ✓ VERIFIED | Both DTOs read directly: `@NotNull private Long version;` present; both response DTOs carry `private Long version;`, auto-mapped by MapStruct (no mapper change needed, matching field name/type) |
| 8 | An automated test drives a stale-version update and asserts HTTP status 409, not merely a service-level exception type (LOCK-03) | ✓ VERIFIED | `TEST-...TaskLockingE2ETest.xml` and `TEST-...ColumnLockingE2ETest.xml`: real HTTP assertions via RestAssured status codes, both pass; controller-level `shouldReturnConflict_whenVersionIsStale` also passes (MockMvc `status().isConflict()`) for both Task and Column |
| 9 | The real Postgres schema is updated to match — a ready-to-run DDL script exists adding `version bigint NOT NULL DEFAULT 0` to both `tasks` and `columns` (LOCK-01) | ✓ VERIFIED | `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` contains both `ALTER TABLE ... ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0` statements; STATUS.md records the one-off bridge decision, before-merge obligation, and Epic-3-defer-rejected rationale. Per the explicit scope note for this project (CONTEXT.md D-06/D-07 and the plan's own must-haves), LOCK-01's schema-side truth for this phase is "the script exists and is correct" — running it against the live DB is a deliberate, documented human action item, not a phase deliverable gap. |
| 10 | `./gradlew spotlessCheck` and `./gradlew test` both pass (project constraint, matches CI) | ✓ VERIFIED | Both re-run fresh: `spotlessCheck` → BUILD SUCCESSFUL; `test --rerun` → BUILD SUCCESSFUL, 125 tests total across the suite, 0 failures, 0 errors |

**Score:** 10/10 truths verified (0 present, behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/java/.../entity/TaskEntity.java` | `@Version private Long version` | ✓ VERIFIED | Present, wired into `TaskService.updateById` comparison |
| `src/main/java/.../entity/ColumnEntity.java` | `@Version @EqualsAndHashCode.Exclude private Long version` | ✓ VERIFIED | Present, exclusion confirmed |
| `src/main/java/.../handler/GlobalExceptionHandler.java` | `HttpStatus.CONFLICT` for `OptimisticLockingFailureException` | ✓ VERIFIED | Confirmed, 423 fully removed |
| `src/main/java/.../dto/task_dto/UpdateTaskRequestDTO.java` | `@NotNull Long version` | ✓ VERIFIED | Present |
| `src/main/java/.../dto/task_dto/TaskResponseDTO.java` | `Long version` | ✓ VERIFIED | Present |
| `src/main/java/.../dto/column_dto/UpdateColumnRequestDTO.java` (new) | validated `name` + `@NotNull Long version` | ✓ VERIFIED | New file present, correct shape |
| `src/main/java/.../dto/column_dto/ColumnResponseDTO.java` | `Long version` | ✓ VERIFIED | Present |
| `src/main/java/.../service/TaskService.java` | explicit version check before mutation | ✓ VERIFIED | Present, wired, documented |
| `src/main/java/.../service/ColumnService.java` | `updateById` with explicit version check | ✓ VERIFIED | New method present, ownership-verified via existing `findById` |
| `src/main/java/.../controller/ColumnController.java` | `PUT /boards/{boardId}/columns/{columnId}` | ✓ VERIFIED | `@PutMapping(ApiPaths.COLUMN_ID) updateById` present, wired to `ColumnService.updateById` |
| `src/test/java/.../e2e/task/TaskLockingE2ETest.java` | passing E2E 409 test | ✓ VERIFIED | 3/3 tests pass (re-run fresh) |
| `src/test/java/.../e2e/column/ColumnLockingE2ETest.java` | passing E2E 409 test | ✓ VERIFIED | 3/3 tests pass (re-run fresh) |
| `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql` | runnable DDL, both tables | ✓ VERIFIED | Both `ALTER TABLE ... IF NOT EXISTS ... NOT NULL DEFAULT 0` statements present |
| `docs/plans/backend-modernization/STATUS.md` | one-off bridge decision recorded | ✓ VERIFIED | Dated entry present with required language (flyway/epic 3/before merge/one-off) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `TaskService.updateById` | `GlobalExceptionHandler.handleOptimisticLockingFailure` | `throw new OptimisticLockingFailureException(...)` → `@ExceptionHandler` → 409 | ✓ WIRED | Confirmed by passing `TaskLockingE2ETest` (real HTTP 409 observed) |
| `ColumnService.updateById` | `GlobalExceptionHandler.handleOptimisticLockingFailure` | same exception type, same handler (reused, not re-implemented) | ✓ WIRED | Confirmed by passing `ColumnControllerTest$UpdateById.testWithAuthenticatedUser_shouldReturnConflict_whenVersionIsStale` and `ColumnLockingE2ETest` |
| `TaskEntity.version` | `TaskResponseDTO.version` | MapStruct implicit field-name mapping (`unmappedTargetPolicy=IGNORE`) | ✓ WIRED | `TaskLockingE2ETest.update_withCurrentVersion_succeedsAndReturnsIncrementedVersion` passes — proves the response DTO actually carries the post-update incremented version, not just that the field compiles |
| `ColumnEntity.version` | `ColumnResponseDTO.version` | MapStruct implicit field-name mapping | ✓ WIRED | `ColumnLockingE2ETest.update_withCurrentVersion_succeedsAndReturnsIncrementedVersion` passes, same proof |
| `ColumnController.updateById` | `ColumnService.updateById` | `columnService.updateById(userId, columnId, dto)` | ✓ WIRED | Confirmed by passing controller and E2E tests hitting the real route |
| `ColumnService.updateById` → `findById` | `OwnershipVerifierService.verifyOwnershipOfColumn` | ownership gate before version comparison | ✓ WIRED | `findById` reused as-is (not bypassed); confirmed by direct code read — no alternate repository lookup path introduced |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Concurrent stale Task update → 409, retried stale → 409 again | `./gradlew test --tests "...TaskLockingE2ETest" --rerun` | `tests="3" failures="0" errors="0"` | ✓ PASS |
| Concurrent stale Column update → 409, retried stale → 409 again | `./gradlew test --tests "...ColumnLockingE2ETest" --rerun` | `tests="3" failures="0" errors="0"` | ✓ PASS |
| Controller-level stale-version 409 (Task) | `TaskControllerTest$UpdateById` | `testWithAuthenticatedUser_shouldReturnConflict_whenVersionIsStale()` pass | ✓ PASS |
| Controller-level stale-version 409 (Column) | `ColumnControllerTest$UpdateById` | `testWithAuthenticatedUser_shouldReturnConflict_whenVersionIsStale()` pass | ✓ PASS |
| Missing version → 400 (Task, Column) | controller + E2E tests | `shouldReturnBadRequest_whenVersionIsMissing`/`update_withoutVersion_returnsBadRequest` pass on both | ✓ PASS |
| `@Version` absent from `BaseEntity` | `grep -rn "@Version" src/main/java/.../entity/` | Only `TaskEntity.java`, `ColumnEntity.java` matched | ✓ PASS |
| `HttpStatus.LOCKED` fully removed | `grep -n "HttpStatus.LOCKED" GlobalExceptionHandler.java` | No matches (exit 1) | ✓ PASS |
| Full suite + format gate | `./gradlew spotlessCheck && ./gradlew test --rerun` | Both `BUILD SUCCESSFUL`; 125 tests, 0 failures, 0 errors | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| LOCK-01 | 01-01, 01-02, 01-03 | `TaskEntity`/`ColumnEntity` have `@Version`, backed by a manual DDL against real Postgres | ✓ SATISFIED | Entity fields confirmed; DDL script confirmed correct and delivered (running it against the live DB is an explicit deferred human action per CONTEXT.md D-06/D-07, not a phase gap) |
| LOCK-02 | 01-01, 01-02 | Conflicting concurrent update returns 409, not 423 | ✓ SATISFIED | Handler fix confirmed; both E2E test suites confirm 409 status at HTTP level |
| LOCK-03 | 01-01, 01-02 | Test proves concurrent conflict at E2E/HTTP-status level | ✓ SATISFIED | `TaskLockingE2ETest`/`ColumnLockingE2ETest` both assert real HTTP 409, re-run fresh and passing |
| LOCK-04 | 01-01 | `ColumnEntity`/`TaskEntity` exclude `version` from equals/hashCode | ✓ SATISFIED | `ColumnEntity.version` carries `@EqualsAndHashCode.Exclude`; `TaskEntity` has no active `@EqualsAndHashCode` (commented out, pre-existing), so no exclusion is structurally needed there — confirmed by direct source read, matches PATTERNS.md's documented rationale |

No orphaned requirements — REQUIREMENTS.md's traceability table maps exactly LOCK-01 through LOCK-04 to Phase 1, and all four appear in the `requirements:` frontmatter of at least one of the three plans.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `TaskService.java` | 58 | `// TODO: make a service interface` | ℹ️ Info | Pre-existing (not introduced by this phase, confirmed via PATTERNS.md baseline showing this line predates the diff); not a phase blocker |
| `ColumnService.java` | 132 | `// TODO: implement delete logic` | ℹ️ Info | Pre-existing, unrelated to Column deletion generally (deleteAllByBoardId already implemented); not a phase blocker |
| `TaskService.java:79`, `ColumnService.java:101` | — | `entity.getVersion().equals(dto.getVersion())` — direct dereference, no null-safety | ⚠️ Warning (carried, not newly introduced by verification) | Flagged as WR-03 in 01-REVIEW.md; would 500 instead of failing cleanly if `version` were ever null (currently prevented by `@Column(nullable=false)` + DDL `DEFAULT 0`); left open post-review, does not block the phase's core 409-detection goal |
| `TaskService.java:76-100`, `ColumnService.java:96-117` | — | No-op update (fields set to their current value) does not force a version bump | ⚠️ Warning (carried, not newly introduced by verification) | Flagged as WR-02 in 01-REVIEW.md; edge case not exercised by any test, does not defeat the core stale-version detection this phase's goal targets |

WR-01 (password hash leaking into JDBC session store via the auth-path fix) was flagged in 01-REVIEW.md and confirmed **fixed** in commit `c5fb656` — verified directly: `UserAuthenticationProvider.authenticate()` now builds a minimal `User(username, "", authorities)` principal instead of the full `UserEntity`, and the full suite + spotlessCheck were re-verified green after the fix per the review's own outcome note and this verification's independent full-suite re-run.

No unresolved `TBD`/`FIXME`/`XXX` debt markers were introduced by this phase's changes.

## Human Verification Required

None. All must-haves resolve to VERIFIED via direct source inspection plus fresh, independently re-run automated tests (not merely cached `UP-TO-DATE` build state) — including the concurrency-guarantee truth (#6), which is exercised by real two-request HTTP sequences in `TaskLockingE2ETest`/`ColumnLockingE2ETest`, not just symbol presence.

## Gaps Summary

No gaps. All 10 derived must-have truths (roadmap goal + PLAN.md frontmatter must_haves across all three plans) are verified against the actual codebase:

- Entity/DTO/service/handler wiring for both Task and Column is present, substantive, and exercised end-to-end by passing tests re-run fresh in this verification session (not just SUMMARY.md claims).
- The concurrency guarantee (exactly one 200, one 409, retried-stale still 409) is proven at the real HTTP level for both entities.
- `@Version` blast radius is correctly scoped (grep-confirmed absent from `BaseEntity`).
- Entity identity preservation (LOCK-04) is correctly implemented per each entity's actual `@EqualsAndHashCode` state (exclusion on `ColumnEntity`, moot-but-consistent on `TaskEntity` since its `@EqualsAndHashCode` is inactive).
- The DDL deliverable is correct and complete; its live-database application is an explicitly scoped, deliberately deferred human action per the project's own decision record (CONTEXT.md D-06/D-07), not a phase-verification gap.
- `./gradlew spotlessCheck` and `./gradlew test` both pass on a fresh, non-cached run (125 tests, 0 failures, 0 errors).
- The WR-01 review finding was fixed post-review (commit `c5fb656`) and independently confirmed here. WR-02/WR-03 remain open as documented, non-blocking review warnings — they represent edge-case robustness gaps (no-op update version-bump, null-safety on the version comparison) that do not undermine the phase's core deliverable: concurrent conflicting updates are detected and rejected with 409.

---

_Verified: 2026-08-01T11:40:00Z_
_Verifier: Claude (gsd-verifier)_
