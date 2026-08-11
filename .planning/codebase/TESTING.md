# Testing Patterns

**Analysis Date:** 2026-07-31

## Test Framework

**Runner:**
- JUnit 5 (Jupiter) with platform launcher
- Configured in `build.gradle` with `useJUnitPlatform()` task configuration
- Spring Boot Test auto-configuration available via `@SpringBootTest`

**Assertion Library:**
- AssertJ for fluent assertions (e.g., `Assertions.assertThat(value).isEqualTo(expected)`)
- JUnit 5 built-in assertions available (though not prominently used)

**Run Commands:**
```bash
./gradlew test              # Run all tests
./gradlew test --watch      # Watch mode (via Gradle)
./gradlew test --debug      # Debug mode with output
```

Test profile activated automatically: `spring.profiles.active=test` set in `build.gradle`

## Test File Organization

**Location:**
- Mirror source structure: `src/test/java/com/vrudenko/kanban_board/` parallels `src/main/java/com/vrudenko/kanban_board/`
- Service tests in: `src/test/java/com/vrudenko/kanban_board/service/`
- Controller tests in: `src/test/java/com/vrudenko/kanban_board/controller/`
- E2E tests in: `src/test/java/com/vrudenko/kanban_board/e2e/`
- DTO tests in: `src/test/java/com/vrudenko/kanban_board/dto/`
- Security tests in: `src/test/java/com/vrudenko/kanban_board/security/`

**Naming:**
- Test classes: `{ClassUnderTest}Test.java` (e.g., `TaskServiceTest.java`, `BoardControllerTest.java`)
- Test method naming: `test{Scenario}_{ExpectedBehavior}()` or `{scenario}_{expectedBehavior}()`
  - Examples: `testWithAuthenticatedUser_shouldReturn_whenBoardsExist()`, `shouldReturn_whenTaskExists()`

**Structure (fixture bases as of Phase 7, D-01 — the three-way `support/` split):**
```
src/test/java/com/vrudenko/kanban_board/
├── support/
│   ├── containers/
│   │   ├── AbstractPostgresContainerTest.java   # Testcontainers PostgreSQL lifecycle only
│   │   └── AbstractKafkaContainerTest.java      # Testcontainers Redpanda (Kafka+Schema Registry) lifecycle only
│   ├── fixtures/
│   │   ├── AbstractAppTest.java                 # Base for all service/integration tests
│   │   ├── AbstractAppE2ETest.java              # Base for real-socket (RestAssured) E2E tests
│   │   └── AbstractAppMockMvcTest.java          # Base for in-process MockMvc E2E tests (added Phase 7)
│   └── listeners/
│       └── RecordingActivityEventListener.java  # Real @Component test spy, not a base class
├── service/
│   ├── TaskServiceTest.java
│   ├── BoardServiceTest.java
│   ├── ColumnServiceTest.java
│   ├── UserServiceTest.java
│   └── OwnershipVerifierServiceTest.java
├── controller/
│   ├── BoardControllerTest.java
│   ├── TaskControllerTest.java
│   ├── ColumnControllerTest.java
│   └── SubtaskControllerTest.java
├── security/
│   └── AuthenticationE2ETest.java     # merged Signin/Signup/SessionPersistence/UserPersistence (Phase 7)
└── dto/
    └── SignupRequestDTOTest.java
```
(This tree shows the fixture-base layout and a representative sample only — `activitylog/`, `e2e/{activity,column,task}/`, `event/` and `architecture/` also exist; see the live tree for the full, current list.)

## Test Structure

**Suite Organization:**
```java
@SpringBootTest
@AutoConfigureMockMvc
public class BoardControllerTest extends AbstractAppTest {

    @Nested
    class FindAllByUserId {
        @Test
        void testWithAuthenticatedUser_shouldReturn_whenBoardsExist() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();

            // Act
            // Assert
            mockMvc.perform(get(getBoardPrefix()).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andReturn();
        }
    }
}
```

**Patterns:**
- `@Nested` inner classes group related test methods by functionality (AAA pattern grouping)
- Each `@Nested` class represents a logical operation (FindAllByUserId, DeleteById, UpdateById)
- `@BeforeEach` in abstract base class sets up common test data before each test
- `@AfterEach` cleans up (calls `userService.deleteAll()`)

**Arrange-Act-Assert (AAA) Pattern:**
```java
@Test
void shouldReturn_whenTaskExists() {
    // arrange - set up test data
    final var userId = getOwningUser().getId();
    final var taskId = mockPopulatedTask.getId();

    // act - execute the method under test
    var task = taskService.findById(userId, taskId);

    // assert - verify results
    Assertions.assertThat(task).isInstanceOf(TaskEntity.class);
    Assertions.assertThat(task.getId()).isSameAs(taskId);
}
```

## Mocking

**Framework:** 
- Spring's `@MockMvc` for controller testing (via `@AutoConfigureMockMvc`)
- Spring Security Test for mocking authentication (`.with(user(userId))`)
- REST Assured for E2E testing (not traditional mocking)

**Patterns:**
```java
// Controller testing with MockMvc
mockMvc.perform(get("/api/boards").with(user(userId)))
        .andExpect(status().isOk())
        .andReturn();

// Security context mock
.with(user(userId))      // Provides authenticated user context

// E2E testing with REST Assured
given()
    .contentType(ContentType.JSON)
    .body(signInRequest)
    .when()
    .post(ApiPaths.SIGNIN)
    .then()
    .extract()
    .cookie(COOKIE_NAME);
```

**What to Mock:**
- HTTP requests: Use MockMvc for controller testing
- Authentication/security context: Use Spring Security Test `with(user())`
- External HTTP calls: Use REST Assured in E2E tests (real HTTP against test server)

**What NOT to Mock:**
- Database: Real PostgreSQL 16, run via Testcontainers (test profile)
- Service layer: Test services directly against real database
- Repositories: Spring Data JPA repositories tested with real database
- Entity relationships: Full entity graph validated through tests

## Fixtures and Factories

**Test Data:**
```java
// In AbstractAppTest
protected final DataFactory dataFactory = new DataFactory();

protected UserResponseDTO createUser() {
    return userService.save(
            SignupRequestDTO.builder()
                    .email(dataFactory.getEmailAddress())
                    .displayName(
                            dataFactory.getRandomWord(
                                    ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                    .password(password)
                    .build());
}

protected TaskResponseDTO createTask() {
    return columnService.addTaskByColumnId(
            getOwningUser().getId(),
            mockPopulatedColumn.getId(),
            SaveTaskRequestDTO.builder()
                    .title(dataFactory.getRandomWord(
                            ValidationConstants.MIN_TASK_TITLE_LENGTH + 2))
                    .description(dataFactory.getRandomText(
                            ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                            ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH))
                    .build());
}
```

**Location:**
- `support/fixtures/AbstractAppTest.java` contains factory methods: `createUser()`, `createTask()`, `createSubtask()`
- `support/fixtures/AbstractAppE2ETest.java` extends `AbstractAppTest` and adds real-socket HTTP testing helpers (`signin()`); `support/fixtures/AbstractAppMockMvcTest.java` extends `AbstractAppTest` and adds the in-process `signinCookie()` counterpart (Phase 7)
- Reusable mock data collections defined as fields: `mockEmptyBoards`, `mockPopulatedBoard`, `mockTasks`, etc.
- DataFactory library (`org.fluttercode.datafactory:datafactory:0.8`) used for random data generation
- Test constants respect validation constraints: `ValidationConstants.MIN_TASK_TITLE_LENGTH`

## Coverage

**Requirements:** No enforced coverage target; no jacoco or code-coverage plugin configured

**View Coverage:**
- Not automated; would require manual jacoco setup
- Current codebase has extensive service and controller test coverage (75%+ estimated)
- Integration tests provide end-to-end coverage

## Test Types

**Unit Tests:**
- Scope: Service methods, repository queries, exception handling
- Approach: Test one method in isolation using real database
- Examples: `TaskServiceTest`, `UserServiceTest`, `ColumnServiceTest`
- Coverage: Happy path, error cases (not found, access denied), edge cases
- File location: `src/test/java/com/vrudenko/kanban_board/service/`

**Integration Tests:**
- Scope: Controller + service + repository interaction
- Approach: Use MockMvc to test HTTP layer with real services
- Spring Boot context initialized: `@SpringBootTest`, `@AutoConfigureMockMvc`
- Real PostgreSQL database transactions within test, via Testcontainers
- Examples: `BoardControllerTest`, `TaskControllerTest`, `ColumnControllerTest`
- File location: `src/test/java/com/vrudenko/kanban_board/controller/`

**E2E Tests:**
- Scope: Full application stack, `*E2ETest`-suffixed classes. As of Phase 7 (D-03 tier downgrade), this suffix no longer implies a real socket — most of these classes run at the cheaper in-process tier; see `docs/CODE_STYLE.md` rule 4 for the current three-way (`AbstractAppTest`/`AbstractAppMockMvcTest`/`AbstractAppE2ETest`) decision rule and `.planning/todos/pending/2026-08-09-decide-e2etest-suffix-vs-fasttest-filter-coupling.md` for the open naming/filter question this created.
- Real-socket approach (`support/fixtures/AbstractAppE2ETest`, exactly one remaining subclass, `BoardCreationE2ETest`): Spring Boot with `webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT`, REST Assured for making actual HTTP requests, kept only for its genuinely concurrent multi-threaded `ConcurrentCreate` test
- In-process approach (`support/fixtures/AbstractAppMockMvcTest`, the majority of `*E2ETest` classes as of Phase 7): plain `@SpringBootTest` + `@AutoConfigureMockMvc`, real `POST /signin`/`POST /signup` through `MockMvc` with the returned cookie relayed by hand — never an injected pre-authenticated principal, since that would bypass the authentication call site these classes exist to cover
- Kafka/Schema-Registry-dependent classes (`support/containers/AbstractKafkaContainerTest`, 9 classes under `activitylog/`) stay on real Testcontainers-backed Kafka regardless of tier, per D-03
- Session/cookie management tested: Signin flow returns a session cookie under both the real-socket and in-process tiers
- File location: `src/test/java/com/vrudenko/kanban_board/` (root package and `e2e/{activity,column,task}/`, `security/`, `activitylog/`) — no longer a single `e2e/` directory; the former `e2e/board/` subpackage (an empty, 0-test-method `BoardE2ETest` class) was removed entirely in Phase 7 — see `.planning/codebase/CONCERNS.md`'s resolved finding

## Common Patterns

**Async Testing:**
- Not extensively used in current codebase
- All tests synchronous using `@Transactional` services
- Controller tests use MockMvc (synchronous)

**Error Testing:**
```java
@Test
void shouldThrow_whenTaskDoesntExist() {
    // arrange
    final var userId = getOwningUser().getId();
    final var taskId = UUID.randomUUID().toString();

    // act
    var exception = Assertions.catchException(
            () -> taskService.findById(userId, taskId));

    // assert
    Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
}
```

**Exception Assertion:**
- Use `Assertions.catchException()` to capture thrown exceptions
- Assert on exception type with `.isInstanceOf()`
- No assertion on message text; exception type confirms behavior

**Security Testing:**
```java
@Test
void shouldThrow_whenUserDoesntOwnTheTask() {
    // arrange
    final var userId = getNoBoardsUser().getId();
    final var taskId = mockPopulatedTask.getId();

    // act
    var exception = Assertions.catchException(
            () -> taskService.findById(userId, taskId));

    // assert
    Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
}
```

**Query Count Testing (Performance):**
```java
@Test
void queryCountDoesNotScaleWithTaskCount() {
    // Assert query count is constant regardless of task count
    var emptyColumnQueryCount =
            countQueries(() -> taskService.deleteAllByColumnId(userId, emptyColumn));
    var populatedColumnQueryCount =
            countQueries(() -> taskService.deleteAllByColumnId(userId, populatedColumn));

    Assertions.assertThat(populatedColumnQueryCount)
            .isEqualTo(emptyColumnQueryCount + 2);  // Should be constant +2 overhead
}

// Helper method from AbstractAppTest
protected long countQueries(Runnable action) {
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    action.run();
    return statistics.getPrepareStatementCount();
}
```

**Test Data Isolation:**
- Each test inherits from `AbstractAppTest` which creates fresh users/boards/tasks in `@BeforeEach`
- Isolation between test *methods* comes from `AbstractAppTest`'s `@AfterEach` cleanup, not from
  the database being recreated: `userService.deleteAll()` (cascading to boards/columns/tasks/
  subtasks) plus a second, explicit `activityLogRepository.deleteAll()` call — `activity_log` has
  no FK back to `UserEntity` (`V3__add_activity_log.sql`), so the cascade alone cannot reach it —
  plus a third call, `RecordingActivityEventListener.clear()` (S5E), clearing the shared
  `CopyOnWriteArrayList` every published `ActivityEvent` this JVM fork has produced accumulates
  into, not just the DB rows.
- Isolation between test *runs* comes from the shared PostgreSQL container being fresh (schema
  rebuilt by Flyway from empty) once per JVM run, not per test.
- No data factories needed for complex scenarios; services create realistic entity graphs

**Test Dependencies:**
- `org.springframework.boot:spring-boot-starter-test` - Spring Test, AssertJ, Mockito
- `org.springframework.security:spring-security-test` - MockMvc security support
- `io.rest-assured:rest-assured:5.5.5` - HTTP request builder for E2E tests
- `org.fluttercode.datafactory:datafactory:0.8` - Random test data generation
- `org.apache.commons:commons-lang3:3.18.0` - String utilities
- `org.testcontainers:postgresql` - Real PostgreSQL 16 container backing every test

## Test Configuration

**Profile:** `application-test.properties` (test profile activated via build.gradle)

**Database:** Testcontainers-managed PostgreSQL 16, schema built by Flyway V1-V4 (configured in test profile)

**Server:** Random port assigned for E2E tests: `webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT`

**Session:** JDBC session store in test profile (matches production config)

---

*Testing analysis: 2026-07-31*
