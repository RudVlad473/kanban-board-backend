# Codebase Concerns

**Analysis Date:** 2026-07-31

## Tech Debt

**Missing Board Name Uniqueness Constraint (×2 locations):**
- Issue: The codebase allows users to create multiple boards with the same name. This violates domain logic and creates confusion.
- Files: `src/main/java/com/vrudenko/kanban_board/service/BoardService.java:70`, `src/main/java/com/vrudenko/kanban_board/service/UserService.java:79`
- Impact: Users can create duplicate board names under the same account, making it unclear which board is which. No database constraint prevents this; validation is purely client-side.
- Fix approach: Add unique constraint on `(user_id, board_name)` at the database schema level. Add validation in `BoardService.updateById()` (line 70) and `UserService.addBoardByUserId()` (line 79) to reject duplicate names before save.

**Incomplete Column Delete Logic:**
- Issue: `ColumnService` (line 76) has a TODO marker but no actual delete method implemented. The method stub exists but is empty.
- Files: `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java:76`
- Impact: No way to delete individual columns via the API. Columns can only be deleted indirectly via board deletion cascade.
- Fix approach: Implement `deleteById(String userId, String columnId)` method following the pattern of `TaskService.deleteById()`. Must verify ownership, delete cascade subtasks/tasks, then delete the column.

**Missing Service Interface Abstraction:**
- Issue: `TaskService` (line 57) lacks a service interface abstraction, making it harder to test and swap implementations.
- Files: `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:57`
- Impact: Controllers depend directly on concrete `TaskService` class, limiting testability and flexibility.
- Fix approach: Create `TaskServiceInterface` (or `ITaskService`), move method signatures to it, and have `TaskService` implement it. Update `@Autowired` declarations in controllers.

## Known Bugs

**Flaky Test in SignupRequestDTOTest:**
- Symptoms: Test `whenDisplayNameIsMissing_thenNoViolation()` intermittently fails with non-zero violation count.
- Files: `src/test/java/com/vrudenko/kanban_board/dto/SignupRequestDTOTest.java:69-85`
- Trigger: The test uses `DataFactory` random generation for `validEmail` and `validPassword` (fields at lines 17-25). Occasionally these random values fail `@AppEmail` or `@Password` validation on their own, causing spurious violations unrelated to `displayName`.
- Workaround: Test still passes most of the time (~50% success rate observed); failures are intermittent and can be mitigated by rerunning.
- Fix approach: Replace random generation with fixed known-valid test values for `validEmail` and `validPassword` instead of generating them at field-initialization. Assert on specific violation property paths rather than raw count.

**Duplicate Parameter Names in SubtaskService:**
- Symptoms: Methods `findById(String id)` (line 75) and `deleteAllByTaskId(String userId, String subtaskId)` (line 85) have mismatched parameter semantics.
- Files: `src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java:44, 75, 85`
- Trigger: Method at line 44 takes `taskId` as second parameter but is named `findById` (expects subtask ID). Method at line 85 takes `subtaskId` but is named `deleteAllByTaskId` (semantically incorrect). No compiler error because these are package-private.
- Impact: Confusing API; callers must carefully check parameter order.
- Fix approach: Rename `findById(String id)` (line 75) to `findByIdInternal()` for clarity. Rename parameter `subtaskId` to `taskId` in line 85 and update its implementation to pass the correct ID.

**Potential NullPointerException in UserEntity.getAuthorities():**
- Symptoms: `getAuthorities()` always returns `null` (line 47 in `UserEntity.java`).
- Files: `src/main/java/com/vrudenko/kanban_board/entity/UserEntity.java:47`
- Trigger: Any code calling `user.getAuthorities()` expecting a collection will receive null. Spring Security will attempt to iterate this collection in some contexts, throwing NPE.
- Impact: If Spring Security ever tries to enumerate user authorities (for role-based access control), the application crashes.
- Fix approach: Return `new ArrayList<>()` (empty collection) instead of `null`. If role-based access control is needed in the future, populate this list with appropriate authorities.

## Test Coverage Gaps

**Missing Cascade Deletion Tests:**
- What's not tested: When a task is deleted, all its subtasks should cascade. No explicit test verifies this behavior.
- Files: `src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java:81-83`
- Risk: Silent data inconsistency — orphaned subtasks could remain after task deletion, breaking referential integrity and confusing users.
- Priority: High — cascade deletion is critical to data consistency.

**Missing Authorization Tests:**
- What's not tested: Cross-user access denial. No tests verify that User A cannot access boards, columns, tasks, or subtasks owned by User B.
- Files: Only authorization tested in `OwnershipVerifierServiceTest.java`; no controller-level auth tests beyond the service layer.
- Risk: Authorization bypass via API if ownership verification is accidentally disabled or misconfigured in a controller.
- Priority: High — authorization is security-critical.

**Missing Validation Edge Cases:**
- What's not tested: Boundary conditions for field lengths (empty strings, max length + 1), invalid characters in fields other than displayName, null inputs in required fields.
- Files: `src/test/java/com/vrudenko/kanban_board/` (limited controller validation tests)
- Risk: Unexpected validation bypass or confusing error messages to clients.
- Priority: Medium.

**E2E Test Suite Skeleton Only:**
- What's not tested: `BoardE2ETest` (line 7) is empty — it extends `AbstractAppE2ETest` but contains no test methods.
- Files: `src/test/java/com/vrudenko/kanban_board/e2e/board/BoardE2ETest.java`
- Risk: No end-to-end integration tests running; refactoring could break API contracts undetected.
- Priority: Medium — unit/integration tests exist, but full flow validation is absent.

**Missing Subtask CRUD Tests:**
- What's not tested: No controller tests for subtask endpoints (create, read, update, delete via HTTP).
- Files: `src/test/java/com/vrudenko/kanban_board/` (no `SubtaskControllerTest`)
- Risk: Subtask API could be broken without being caught by test suite.
- Priority: Medium.

## Fragile Areas

**OwnershipVerifierService Chain Logic:**
- Files: `src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java`
- Why fragile: Verification walks a chain: Subtask → Task → Column → Board → User. Each method calls the previous level's verifier. If any intermediate entity relationship is broken (e.g., a Task without a Column), the entire chain fails with a generic `AppEntityNotFoundException`. Debugging is difficult because the error doesn't indicate *which* level failed.
- Safe modification: Add context to exceptions (e.g., "Subtask with ID X not found, verified via Task") or log which step failed. Add null-checks on foreign key fields before accessing them.
- Test coverage: Ownership tests are comprehensive but only test the happy path and simple error cases. No tests for partially-broken relationships.

**Batch Delete Operations with Session Cache:**
- Files: `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:96-119`
- Why fragile: `deleteAllByColumn()` uses bulk JPQL delete statements that bypass the Hibernate persistence context, then calls `entityManager.flush()` and `entityManager.clear()`. If a caller loops this method (e.g., deleting many columns in one transaction), the timing of flushes/clears is critical. A mistake in transaction boundary management could lead to stale data or race conditions.
- Safe modification: Add integration tests that verify cascade deletes across multiple entities in a single transaction. Document the flush/clear requirement in the method javadoc.
- Test coverage: No tests for multi-level cascade deletes in a loop. Current tests only test single-column deletion.

**GlobalExceptionHandler Broad Catch-All:**
- Files: `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java:43-46`
- Why fragile: Line 43-46 catches generic `Exception`, which masks unexpected errors (like programming bugs) by returning a generic 500 response. If a controller accidentally throws an unchecked exception, it gets swallowed with no logging.
- Safe modification: Change generic handler to log the full exception stack trace before returning the response. Add separate handlers for common unchecked exceptions (NPE, IllegalArgumentException, etc.) with context-specific messages. Ensure all domain exceptions extend a common base exception.
- Test coverage: No tests for error handler behavior; handlers are validated only through E2E tests if they fail.

## Security Considerations

**Null Authority Collection in UserDetails:**
- Risk: `UserEntity.getAuthorities()` returns `null`. Spring Security may crash or behave unpredictably when this is null. If role-based access control is added without proper null-handling, authentication could be bypassed or exploited.
- Files: `src/main/java/com/vrudenko/kanban_board/entity/UserEntity.java:46-48`
- Current mitigation: Method-level `@EnableMethodSecurity` is configured (SecurityConfiguration.java:25), but no role annotations are used in controllers. Current authorization relies solely on `OwnershipVerifierService`.
- Recommendations: Return empty authority list, not null. If roles are needed in the future, ensure `@PreAuthorize` annotations reference existing authority objects.

**CSRF Disabled in SecurityConfiguration:**
- Risk: CSRF protection is explicitly disabled (line 41: `csrf(AbstractHttpConfigurer::disable)`). This is acceptable for stateless APIs but dangerous if session-based forms or browser-initiated state changes are added.
- Files: `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java:41`
- Current mitigation: API uses JSON and requires authentication; CORS is enabled with defaults.
- Recommendations: Document why CSRF is disabled. If browser-based UI is added, re-enable CSRF tokens in SecurityConfiguration and add them to all state-changing forms.

**Session Configuration:**
- Risk: `maximumSessions(2).maxSessionsPreventsLogin(true)` (line 63) allows max 2 concurrent sessions per user. This could prevent legitimate multi-device login but is appropriate for a personal task board. However, no explicit timeout or activity-based logout is configured.
- Files: `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java:61-67`
- Current mitigation: Session fixation protection is enabled (`newSession`); session creation policy is `IF_REQUIRED`.
- Recommendations: Set `session.timeout()` in `application.properties` to expire idle sessions after 30+ minutes.

## Performance Bottlenecks

**Potential N+1 Query in Board Hierarchy Walks:**
- Problem: `OwnershipVerifierService.verifyOwnershipOfSubtask()` (line 89-100) walks Subtask → Task → Column → Board → User, calling `findById()` at each level. Without explicit `@Fetch(FetchType.EAGER)` overrides, this could generate N+1 queries.
- Files: `src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java`
- Cause: Each entity relationship is a `@ManyToOne` without explicit fetch strategy override. Default is EAGER, but if changed, N+1 occurs.
- Improvement path: Codebase guards against this with detailed javadoc in `OwnershipVerifierServiceTest.java:20-26`. Verify existing `@ManyToOne` defaults remain EAGER. Add integration test that counts queries for multi-level ownership verification.

**No Database Indexing on Foreign Keys:**
- Problem: Queries like `findAllByBoardId(boardId)` rely on foreign key lookups. Without indexes, these degrade as data grows.
- Files: Implicitly in all repository query methods.
- Cause: Spring Boot JPA repositories generate queries but don't create indexes beyond primary keys.
- Improvement path: Add `@Index` annotations to foreign key columns in entities (e.g., `@ManyToOne` on `ColumnEntity.board`). Example: `@ManyToOne @Index(name = "idx_column_board") BoardEntity board`.

## Architectural Concerns

**Hard-Coded Dependency Injection with @Autowired:**
- Issue: All services use `@Autowired` field injection (constructor injection is not used). This makes circular dependency detection difficult and makes unit testing harder without mocking frameworks.
- Files: All service classes (e.g., `BoardService.java:20-26`, `TaskService.java:23-31`)
- Impact: Subtle circular dependencies can go undetected until runtime. Field injection is less explicit about dependencies.
- Fix approach: Use constructor injection (`@RequiredArgsConstructor` in lombok) instead of `@Autowired` fields. Spring will fail to start if there are circular dependencies.

**Unused EntityManager in TaskService:**
- Issue: `TaskService` injects `EntityManager` (line 31) but only uses it in `deleteAllByColumn()` for flush/clear operations.
- Files: `src/main/java/com/vrudenko/kanban_board/service/TaskService.java:31`
- Impact: Direct EntityManager use couples service to JPA implementation details. Makes it harder to switch persistence layers.
- Fix approach: Consider moving flush/clear logic to a repository-level method or a separate transaction manager utility.

---

*Concerns audit: 2026-07-31*
