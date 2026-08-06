# Code Style Guide

This file records code-style preferences that AI coding agents and human contributors must follow when writing Java in this repository. It is additive: new rules are appended over time as they come up, never rewritten wholesale. It complements — does not replace — the Spotless / Google Java Format AOSP formatting already enforced by the build. Formatting is mechanical and enforced by `./gradlew spotlessCheck`; this file covers judgement-level choices Spotless cannot check.

## Rules

### 1. Prefer enums over magic int/String constants

When a value comes from a fixed, known-at-compile-time set, model it as an enum (a JDK/framework-provided one where it exists, otherwise a project enum under `com.vrudenko.kanban_board`) rather than as bare `int` or `String` literals scattered across call sites. HTTP status codes are the canonical case: use `org.springframework.http.HttpStatus`.

**Why:** the compiler enforces the closed set, so a typo or an out-of-range value fails at build time instead of runtime; switch statements can be checked for exhaustiveness; the value carries a self-documenting name at every call site; and the set has one authoritative definition to change instead of N literal sites to grep for.

Discouraged:

```java
@ExceptionHandler(AppEntityNotFoundException.class)
public ResponseEntity<String> handleAppEntityNotFound(AppEntityNotFoundException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatusCode.valueOf(404));
}

@ExceptionHandler(AppAccessDeniedException.class)
public ResponseEntity<String> handleAppAccessDenied(AppAccessDeniedException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatusCode.valueOf(404));
}
```

Preferred:

```java
import org.springframework.http.HttpStatus;

@ExceptionHandler(AppEntityNotFoundException.class)
public ResponseEntity<String> handleAppEntityNotFound(AppEntityNotFoundException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatus.NOT_FOUND);
}
```

`GlobalExceptionHandler` already follows this rule and is the reference to imitate. The rule generalises beyond HTTP status — any closed value set (roles, states, sort directions) should be an enum.

### 2. Load entities through the ownership-verified loader, never `repository.findById` directly

The four domain services (`BoardService`, `ColumnService`, `TaskService`, `SubtaskService`) must resolve every entity through their own `findById(userId, id)` method, which delegates to `ownershipVerifierService.verifyOwnershipOf...` — never through a direct call to `repository.findById(id)`. For example, `TaskService.findById(userId, taskId)` calls `ownershipVerifierService.verifyOwnershipOfTask(userId, taskId)` and returns `pair.getSecond()`; every other method that needs a task goes through it instead of touching `taskRepository.findById` itself. Once an entity has been verified this way, any downstream repository call made later in the same method must be built from the verified entity's own id (`pair.getSecond().getId()`), not from the raw path-variable parameter that was passed in. `OwnershipVerifierService` (the root of the ownership chain) and `UserService` (the identity root, with no owner above it) are the only two places a direct repository `findById` is sanctioned.

**Why:** this is the entire access-control model of the application, and nothing in the type system enforces it — a direct repository load compiles cleanly, passes a naive test, and silently removes the ownership check it was supposed to go through; re-deriving the downstream id from the verified entity, rather than reusing the raw parameter, also guarantees that the id which was actually authorised is the id that gets used.

Discouraged:

```java
public TaskResponseDTO updateById(String userId, String taskId, String columnId, UpdateTaskRequestDTO dto) {
    var task = taskRepository.findById(taskId).get();
    task.setTitle(dto.getTitle());
    taskRepository.save(task);

    var siblingTasks = taskRepository.findAllByColumnId(columnId);
    return taskMapper.toDto(task);
}
```

Preferred:

```java
public TaskResponseDTO updateById(String userId, String taskId, String columnId, UpdateTaskRequestDTO dto) {
    var task = findById(userId, taskId);
    task.setTitle(dto.getTitle());
    taskRepository.save(task);

    var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);
    var siblingTasks = taskRepository.findAllByColumnId(pair.getSecond().getId());
    return taskMapper.toDto(task);
}
```

`TaskService.findById` and `TaskService.findAllByColumnId` are the reference implementations of this pattern.

### 3. Use AssertJ fully qualified; capture exceptions with `catchException`

Assertions are always written as `Assertions.assertThat(...)`, against `import org.assertj.core.api.Assertions;` — never a static import of `assertThat`. When a test needs to assert that a call throws, the exception is captured first with `Assertions.catchException(...)` and asserted on afterwards; JUnit's `assertThrows` is not used.

**Why:** capturing the throwable into a local variable lets a single test assert on the exception and then continue asserting follow-up state in the same method, which `assertThrows`'s callback shape makes awkward; keeping `Assertions` qualified at every call site also matches every existing assertion in the suite, so a reader never has to guess which assertion library produced a given call.

Discouraged:

```java
import static org.assertj.core.api.Assertions.assertThat;

@Test
void shouldThrow_whenColumnDoesntExist() {
    var userId = getOwningUser().getId();
    var columnId = UUID.randomUUID().toString();

    assertThrows(
            AppEntityNotFoundException.class,
            () -> taskService.findAllByColumnId(userId, columnId));
}
```

Preferred:

```java
import org.assertj.core.api.Assertions;

@Test
void shouldThrow_whenColumnDoesntExist() {
    var userId = getOwningUser().getId();
    var columnId = UUID.randomUUID().toString();

    var exception =
            Assertions.catchException(() -> taskService.findAllByColumnId(userId, columnId));

    Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
}
```

`TaskServiceTest` is the reference for this pattern throughout.

### 4. No mocks — test against real Spring wiring

Every test class is a `@SpringBootTest` extending `AbstractAppTest` (or, for full HTTP round-trips, `AbstractAppE2ETest`), exercising the real Spring context against a Testcontainers-managed PostgreSQL 16 instance shared across the whole JVM run, whose schema is built by the same Flyway migrations production runs. `AbstractPostgresContainerTest` is the shared container ancestor both `AbstractAppTest` and `AbstractKafkaContainerTest` extend — a bare `@SpringBootTest` extending neither will not get a datasource. Mockito, `@Mock`, `@MockBean`, and slice annotations such as `@WebMvcTest` or `@DataJpaTest` are not used anywhere in this repository and must not be introduced. Shared fixtures (mock users, boards, columns, tasks, subtasks) belong in `AbstractAppTest`'s single `@BeforeEach`, not re-created inline inside individual test classes. `AbstractAppTest.countQueries(Runnable)` is the only sanctioned way to assert on query counts; its Javadoc records why it reads `getPrepareStatementCount()` instead of `getQueryExecutionCount()` — the latter misses `repository.findById()` calls entirely.

**Why:** mocking a repository bypasses exactly the ownership chain and JPA behaviour these tests exist to catch regressions in, so a fully green, fully mocked test can sit directly on top of a broken access-control path or a reintroduced N+1 query.

Discouraged:

```java
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {
    @Mock TaskRepository taskRepository;
    @InjectMocks TaskService taskService;
}
```

Preferred:

```java
@SpringBootTest
public class TaskServiceTest extends AbstractAppTest {
    @Autowired TaskService taskService;
}
```

`AbstractAppTest` is the reference for shared fixtures and the `countQueries` helper.

### 5. Group by method under test with `@Nested`; name `should<Outcome>_when<Condition>`; mark sections with AAA comments

Test methods that exercise one method under test are grouped inside a `@Nested` class named after that method (for example `FindAllByColumnIdTest`). Test methods are named `should<Outcome>_when<Condition>`, and each method body is divided into `// arrange`, `// act`, `// assert` section comments. `@DisplayName` is not used — the method name is the display name. Two naming dialects both exist and neither should be normalised into the other: service and unit tests use the plain `should<Outcome>_when<Condition>` form; MockMvc controller tests prefix the auth context, as `testWithAuthenticatedUser_should<Outcome>_when<Condition>`.

**Why:** nesting by method under test makes the method itself the unit of navigation, rather than scrolling a flat wall of unrelated test methods; the name-plus-section-comment convention removes the need for a second, separately-maintained `@DisplayName` string that can drift out of sync with what the method actually asserts.

Discouraged:

```java
@Test
@DisplayName("returns tasks for an existing column")
void test1() {
    var userId = getOwningUser().getId();
    var columnId = mockPopulatedColumn.getId();
    var tasks = taskService.findAllByColumnId(userId, columnId);
    Assertions.assertThat(tasks).isNotEmpty();
}
```

Preferred:

```java
@Nested
class FindAllByColumnIdTest {
    @Test
    void shouldReturn_whenColumnExists() {
        // arrange
        var userId = getOwningUser().getId();
        var columnId = mockPopulatedColumn.getId();

        // act
        var tasks = taskService.findAllByColumnId(userId, columnId);

        // assert
        Assertions.assertThat(tasks).isNotEmpty();
    }
}
```

`TaskServiceTest` is the reference for the service dialect; `BoardControllerTest` is the reference for the controller dialect (`testWithAuthenticatedUser_...`).

### 6. `Update*RequestDTO` carries a fixed shape

Every `Update*RequestDTO` carries `@JsonInclude(JsonInclude.Include.NON_NULL)` on the class, a `@NotNull private Long version` field, and — whenever the DTO has more than one independently optional field — a private `@AssertTrue`-annotated `atLeastOneFieldPopulated()` method. `Save*RequestDTO` and `*ResponseDTO` classes never carry `@JsonInclude`; its presence on a class is exactly what marks that class as a partial-update DTO.

**Why:** omitting `@NotNull Long version` silently disables optimistic locking for that entity — the request still passes validation and the write still succeeds, it just stops being safe against concurrent edits; giving the cross-field check a different name on each DTO would make the same check unfindable when scanning across DTOs for this invariant.

Discouraged:

```java
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class UpdateWidgetRequestDTO implements BaseWidget {
    private String name;
    private Long version;
}
```

Preferred:

```java
@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateTaskRequestDTO implements BaseTask {
    @TaskTitle private String title;
    @Description private String description;
    @NotNull private Long version;

    @AssertTrue(message = "Either 'title' or 'description' (or both) must be provided.")
    private boolean atLeastOneFieldPopulated() {
        return Optional.ofNullable(getTitle()).isPresent()
                || Optional.ofNullable(getDescription()).isPresent();
    }
}
```

`UpdateTaskRequestDTO` is the reference; single-field update DTOs such as `UpdateColumnRequestDTO` correctly omit `atLeastOneFieldPopulated()` since there is no second field to cross-check against.

### 7. Unwrap `Optional` with an `isEmpty()` guard, not `orElseThrow`

An `Optional` returned by a repository is unwrapped with an explicit `isEmpty()` guard that throws the appropriate `App...Exception`, followed by a plain `.get()`. `orElseThrow` does not appear anywhere in `src/main` and should not be introduced. This is a deliberate consistency choice, not a claim that the guard form is technically better: `orElseThrow` is shorter and the more idiomatic modern Java, but every existing unwrap site in this codebase uses the guard form, and staying consistent across those sites is the entire point.

**Why:** the guard is a statement, not an expression, so a second check — another entity load, an ownership comparison — slots in right beside it as a peer in the same flat sequence, instead of forcing the whole thing to be restructured the moment a second condition needs checking.

Discouraged:

```java
public UserEntity findUser(String userId) {
    return userRepository.findById(userId).orElseThrow(() -> new AppEntityNotFoundException("User"));
}
```

Preferred:

```java
public Pair<UserEntity, BoardEntity> verifyOwnershipOfBoard(String userId, String boardId) {
    var user = userRepository.findById(userId);
    if (user.isEmpty()) {
        throw new AppEntityNotFoundException("User");
    }

    var board = boardRepository.findById(boardId);
    if (board.isEmpty()) {
        throw new AppEntityNotFoundException("Board");
    }

    var userOwnsBoard = board.get().getUser().getId().equals(user.get().getId());
    if (!userOwnsBoard) {
        throw new AppAccessDeniedException("Board");
    }

    return Pair.of(user.get(), board.get());
}
```

`OwnershipVerifierService.verifyOwnershipOfBoard` is the reference — it chains exactly this shape three times in one flat sequence (user, board, ownership) rather than nesting.

This rule is mechanically enforced by `src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java`, which fails `./gradlew test` if a domain service other than `OwnershipVerifierService` or `UserService` calls `repository.findById` directly. That check is a floor, not a ceiling — see the class Javadoc for what it does not catch.

### 8. Test setup must be fully automated — never a manual step for the developer

Running the test suite must never depend on a developer performing a manual, host-level setup step first (flipping an application GUI setting, hand-editing a config file outside version control, running a one-off command before `./gradlew test` will work). If a test needs specific environment/tooling behavior to run correctly, that behavior must be configured from within the codebase itself — a system property set in test code, a project-local config file that ships in version control, a Gradle task — so that `./gradlew test` (or the equivalent single command) is sufficient on a clean checkout. When a failure turns out to be caused by a missing environment quirk (a client/tooling version incompatibility, a platform-specific default), fix it by encoding the workaround in the codebase, not by writing runbook instructions for a human to follow by hand.

**Why:** a manual setup step is a step every new environment forgets — a fresh clone, a new contributor's machine, a CI runner — and it turns "run the tests" into "run the tests, but first go read the docs and remember to do this one fiddly thing," which reliably doesn't happen. A one-time automated fix in the codebase benefits every future run and every future machine; a documented manual workaround has to be rediscovered and repeated by everyone who hits it.

Discouraged:

```markdown
<!-- docs/LOCAL_DEV.md -->
## Running the Kafka Testcontainers tests on Windows

Docker Desktop → Settings → General → enable "Expose daemon on tcp://localhost:2375
without TLS", then run:
DOCKER_HOST=tcp://localhost:2375 ./gradlew test --tests '*ActivityLog*E2ETest'
```

Preferred:

```java
// AbstractKafkaContainerTest.java
public abstract class AbstractKafkaContainerTest {
    // docker-java (bundled by Testcontainers 1.21.0) negotiates a Docker Engine API
    // version that Docker Engine 29.x rejects (testcontainers-java#11212). Pinning the
    // client to API 1.44 fixes it with zero host-level configuration.
    static {
        System.setProperty("api.version", "1.44");
    }
    // ...
}
```

`AbstractKafkaContainerTest`'s `api.version` pin is the reference: the actual fix for a real Docker/Testcontainers incompatibility encountered on Windows lives in test code, not in a runbook telling a developer what to click.

### 9. Use `var` only when the RHS already makes the type obvious

For local variable declarations, `var` is preferred only when the right-hand side already makes the type visually obvious at the call site — a constructor call (`var user = new UserEntity();`), a well-known factory/builder call (`var id = UlidCreator.getUlid();`, `var dto = TaskResponseDTO.builder()...build();`), or a loop variable over a collection whose element type was just declared. Keep the explicit type when the RHS is a call whose return type isn't obvious without checking the method signature — a service/repository/mapper method call, a generic collection assembled through a stream or factory method, or any expression a reader would have to look up elsewhere to resolve. `var` is a Java local-variable-only feature — it cannot appear on fields, method parameters, or return types — so this rule only ever applies to local variable declarations, never anywhere else.

**Why:** this codebase (and an AI agent editing it) is read top-to-bottom without an IDE's inline type hints, so the type has to come from either the keyword or the RHS; when the RHS already spells out the type, `var` removes true redundancy, but when it doesn't, `var` forces a detour to a method signature just to know what a variable supports — which is friction the explicit-type form never asks for.

Discouraged:

```java
public TaskResponseDTO updateById(String userId, String taskId, UpdateTaskRequestDTO dto) {
    var task = findById(userId, taskId);
    var siblingTasks = taskRepository.findAllByColumnId(task.getColumn().getId());
    var response = taskMapper.toDto(task);
    return response;
}
```

Preferred:

```java
public TaskResponseDTO updateById(String userId, String taskId, UpdateTaskRequestDTO dto) {
    TaskEntity task = findById(userId, taskId);
    List<TaskEntity> siblingTasks = taskRepository.findAllByColumnId(task.getColumn().getId());
    var id = UlidCreator.getUlid();
    return taskMapper.toDto(task);
}
```

## Adding a rule

New rules are appended as a new `###` section under `## Rules`, numbered with the next integer. Each rule must carry the same three parts: a rule statement, a bolded **Why** line, and a bad-vs-good code example.
