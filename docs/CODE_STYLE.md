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

**Which package a new test belongs in (by purpose, decided before which base class to extend):**
`service/*ServiceTest.java`, `controller/*ControllerTest.java` and the `*E2ETest`-suffixed classes
(`e2e/`, `security/`, `activitylog/`, and a handful still at the root package) are three different
questions about the same behavior, not three copies of the same test. `service/*ServiceTest.java`
exercises the service layer directly (no HTTP, no MockMvc) for cheap, high-volume coverage of
business-logic edge cases and input-combination branches — validation boundaries, ownership-chain
edge cases, the kind of case-count that would bloat a flow test if it lived there instead. Worked
example: `TaskServiceTest.UpdateByIdTest.shouldThrow_whenUserDoesntOwnTheTask()` proves the
ownership-denial branch directly against the service, something no controller test in this
codebase separately re-proves. `controller/*ControllerTest.java` proves one HTTP endpoint's
contract — status codes, request/response JSON shape, auth/ownership wiring at the HTTP boundary —
not every edge case its underlying service test already covers. Worked example:
`TaskControllerTest.UpdateById.testWithAuthenticatedUser_shouldReturnConflict_whenVersionIsStale()`
proves the controller maps a stale-version conflict to HTTP 409; it reuses the same triggering
scenario `TaskServiceTest` uses for a different assertion (HTTP status vs. exception type), which is
intentional layering, not redundancy. `*E2ETest`-suffixed classes are for flows that genuinely span
multiple services or controllers, or that need real infrastructure this project's tier split
(rule below) already scoped — Kafka/Schema Registry, or genuine multi-threaded concurrency. Worked
examples: `BoardCreationE2ETest.ConcurrentCreate` (two real concurrent HTTP threads racing a
database unique constraint) and `ActivityLogIdempotencyE2ETest` (a cross-service Kafka
publish-then-consume-then-dedupe flow). This rule governs package/purpose selection and is
independent of the base-class rule immediately below — both must be read together when starting a
new test file.

**Which tier a Bean Validation boundary case belongs at — `dto/*Test.java` vs. one representative
controller test:** the `dto/` package holds validator-tier tests that exercise Bean Validation
constraints directly against a DTO instance — a `jakarta.validation.Validator` obtained from
`Validation.buildDefaultValidatorFactory()` in `@BeforeEach`, with no `@SpringBootTest`, no
`support/fixtures/` base class, and no container, making it the cheapest tier in the suite and the
reason an exhaustive matrix is affordable there. The split rule: one field-plus-annotation's full
boundary matrix — null, blank, whitespace-only, below the minimum length, above the maximum length,
and cases where two constraints collide — belongs at this tier; the controller tier keeps at most
one or two representatives proving that a malformed body produces 400 with the right envelope, and
does not re-enumerate the matrix behind an HTTP round trip. Worked example: quick task 260813-h2f
added `@NotBlank` alongside the existing `@SubtaskTitle` on `SaveSubtaskRequestDTO.title` and proved
it with four `TaskControllerTest.AddSubtaskByTaskId` tests; a suite-wide triage in 260813-i6r found
this was the only controller-tier over-enumeration in the codebase, relocated three of those cases
to `SubtaskTitleMessageTest.SaveSubtaskRequestDTOTest`, and kept
`testWithAuthenticatedUser_shouldReturnBadRequest_whenJsonBodyIsEmpty` — the `{}` case — as the
single controller-tier representative. This tier invites its own trap: `@SubtaskTitle` carries
`@ReportAsSingleViolation`, so its rendered message is byte-identical to `@NotBlank`'s on the same
field, and a DTO-tier test asserting only on message text cannot establish which constraint actually
fired — an input that trips more than one constraint is asserted on the set of triggered constraint
annotation types instead, while an input that trips exactly one may still assert message text, and
only because an exact violation-count assertion pins that fact. Read together with the base-class
paragraph immediately below, this is the one tier that answers it with "none."

**Which base class to extend, within `support/fixtures/`:** every test class that needs a Spring
context is a `@SpringBootTest` extending one of three bases under `support/fixtures/` —
`AbstractAppTest` for tests that call
services directly, `AbstractAppMockMvcTest` for HTTP tests that go through MockMvc without needing
a real socket, or `AbstractAppE2ETest` (full real-socket HTTP round-trips) only when a genuinely
concurrent multi-threaded request is required — exercising the real Spring context against a
Testcontainers-managed PostgreSQL 16 instance shared across the whole JVM run, whose schema is
built by the same Flyway migrations production runs. `AbstractPostgresContainerTest` (under
`support/containers/`) is the shared container ancestor both `AbstractAppTest` and
`AbstractKafkaContainerTest` extend — a bare `@SpringBootTest` extending none of these three will
not get a datasource. `AbstractAppMockMvcTest` does not apply `server.servlet.context-path` the way
a real embedded servlet container does, so tests extending it build routes from the bare `ApiPaths`
constants, without the context-path prefix the `AbstractAppE2ETest` tier needs. Mockito, `@Mock`,
`@MockBean`, and slice annotations such as `@WebMvcTest` or `@DataJpaTest` are not used anywhere in
this repository and must not be introduced. Shared fixtures (mock users, boards, columns, tasks,
subtasks) belong in `AbstractAppTest`'s single `@BeforeEach`, not re-created inline inside
individual test classes. `AbstractAppTest.countQueries(Runnable)` is the only sanctioned way to
assert on query counts; its Javadoc records why it reads `getPrepareStatementCount()` instead of
`getQueryExecutionCount()` — the latter misses `repository.findById()` calls entirely.

**`.with(user(userId))` may authenticate at most two requests for the same principal per test
method — call `signinCookie()` for a third.** `SecurityMockMvcRequestPostProcessors.user(userId)`
injects an already-authenticated principal directly into a MockMvc request's security context; because
MockMvc gives every `perform(...)` call its own request whose security context is persisted at the
end of that chain, each such call establishes a **brand-new** session for that principal instead of
reusing one. `SecurityConfiguration`'s `MAX_CONCURRENT_SESSIONS = 2`, together with
`maxSessionsPreventsLogin(true)`, therefore refuses the third such call for one principal within one
test method — enforced here by `SessionManagementFilter`'s own DSL-composed
`CompositeSessionAuthenticationStrategy`, backed by an in-memory `SessionRegistryImpl`. This is a
**different instance** from the `sessionAuthenticationStrategy` `@Bean` (that bean enforces the
ceiling only on the real signin/signup path — see "State Management" above — and never runs on this
MockMvc shortcut at all). The refusal itself is a bare servlet `sendError` — `Content-Type: null`,
empty body — `SessionManagementFilter`'s own failure-handler fingerprint, not this application's RFC
7807 `ProblemDetail` envelope (a real wrong-password refusal on the signin path *does* carry that
envelope, with `code: BAD_CREDENTIALS`; the two are both HTTP 401 but not byte-comparable). Still
true: nothing in the failure itself leaks a session-specific signal, so a ceiling hit is not a
credential-validity oracle. Measured:
four identical `.with(user(userId))` calls in one test method returned `200, 200, 401, 401`. The
limit is per principal **per test method**, not per class or per JVM run, specifically because
`AbstractAppTest`'s `@BeforeEach` mints a fresh owning user every test method, so the per-principal
live-session count restarts at zero each time — a `@ParameterizedTest` making one authenticated call
per invocation never trips it, however many invocations it has. For three or more authenticated
requests as one principal within a single test method, call
`AbstractAppMockMvcTest.signinCookie()` once and replay the returned cookie on every subsequent
request instead — a real signin establishes exactly one session and each replay reuses it, so the
count never climbs. Worked examples: `InjectionAttemptTest` is the reference for the cookie-replay
pattern, adopted because several of its cases make three or more authenticated calls per method;
`AuthorizationGatingTest` is the counterpart that correctly keeps the `.with(user())` shortcut, since
no method there makes more than two authenticated calls for one principal — it is not a
cookie-replay example, and calls `signinCookie()` zero times. This is unreachable in production:
`AuthenticationController.authenticate` pre-establishes the session on the one real signin path
before the security context is saved, so a real client never accumulates one session per request.

**Pre-commit gate membership is by `@Tag`, not by class name.** `build.gradle`'s `fastTest` task
(the pre-commit hook's gate) excludes tests by JUnit 5 `@Tag`, not by a name pattern: classes
extending `AbstractKafkaContainerTest` carry `@Tag("kafka")`, and `AbstractAppE2ETest` subclasses
carry `@Tag("realSocket")` only when the test genuinely needs a real socket (most
`AbstractAppE2ETest`-tier concerns fit `AbstractAppMockMvcTest` instead). A class with no tag runs
in `fastTest` by default — that is the safe default, and it is why a class's tier is decided by
its base class and tag, never by whether its name happens to end in `E2ETest`.

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

### 10. Import blocks are grouped java / javax / com.vrudenko / third-party / static, one blank line between groups

Every `.java` file's import block is organized into five groups, each separated by exactly one blank line, in this fixed order: `java.*`, `javax.*`, `com.vrudenko.*` (first-party), everything else third-party (`jakarta.*`, `org.springframework.*`, `io.*`, `com.github.*`, `com.fasterxml.*`, and so on), then static imports last. `build.gradle`'s `spotless { java { importOrder(...) } }` call is the enforcing mechanism — it names the five groups explicitly (`importOrder('java', 'javax', 'com.vrudenko', '', '\\#')`, where the `''` catch-all group is everything not otherwise matched and the doubled-backslash `'\\#'` is Groovy's escaping for Spotless's literal static-import token). The `javax.*` group currently matches zero imports in this codebase — Spring Boot 3 uses `jakarta.*` throughout — and is retained deliberately as future-proofing rather than removed as dead configuration.

A developer never hand-maintains these blocks: `./gradlew spotlessApply` (and the `.githooks/pre-commit` hook, which runs it automatically) rewrites every import block to this shape on every commit. Hand-ordering imports to match is unnecessary and will be silently overwritten.

**Why:** unlike rules 1-9, this one is mechanically enforced — `./gradlew spotlessCheck` genuinely rejects imports in the wrong group or missing a blank-line separator, which puts it outside this file's own preamble scope of "judgement calls Spotless cannot check." It is recorded here anyway because the build enforces *what* the order is but nothing about *why* first-party (`com.vrudenko.*`) sits third, ahead of third-party, rather than after it in the more common first-party-last convention some contributors expect. That placement is a deliberate choice, not an accident Spotless happened to produce, and a contributor unaware of that would be tempted to "correct" it toward the more familiar ordering — which `spotlessCheck` would then reject, with no explanation of why visible anywhere in the build script itself. Recording the rationale here, next to the other judgement-level conventions, is what makes the choice legible instead of just enforced.

Discouraged (single undifferentiated ASCII-sorted block, static imports first — the pre-reformat shape of `TaskControllerTest.java`):

```java
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.service.SubtaskService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.collections4.ListUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
```

Preferred (post-reformat content of the same file — java, com.vrudenko, third-party, static, one blank line between each group):

```java
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.service.SubtaskService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections4.ListUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
```

`TaskControllerTest` is the reference — its import block is one of the few files in the codebase exercising four of the five groups (java, com.vrudenko, third-party, static) at once.

### 11. Every `@RestController` carries class-level `@Validated`

Every `@RestController` class — including `AuthenticationController`, which lives in the `security` package rather than `controller` — must carry class-level `org.springframework.validation.annotation.Validated`, even on controllers with no currently-constrained `@PathVariable`/`@RequestParam`. This is mechanically enforced by `src/test/java/com/vrudenko/kanban_board/architecture/LayeringArchTest.java`'s `rest_controllers_must_carry_class_level_validated` rule, which fails `./gradlew test` for any `@RestController` missing the annotation.

**Why:** the annotation does not merely *add* validation — its presence decides *which exception Spring throws* for a `@Valid @RequestBody` field-constraint failure, and therefore which error envelope the client receives. A controller carrying `@Validated` throws `MethodArgumentNotValidException`, which `GlobalExceptionHandler` converts to `VALIDATION_FAILED` with a per-field `errors` map; the same kind of failure on a controller missing `@Validated` instead throws `HandlerMethodValidationException`, converted to `CONSTRAINT_VIOLATION` with no `errors` map. Quick task 260811-p9c discovered this split empirically — three of seven controllers carried `@Validated` and four did not, so a frontend built against `$.errors.<field>` silently got nothing to render on four of seven controllers even though every response looked uniform at a glance (a single closed `code` enum). This rule, plus the ArchUnit guard that enforces it, is what keeps that split from silently reopening the next time a controller is added.

Discouraged:

```java
@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS)
@PreAuthorize("isAuthenticated()")
public class ColumnController {
    // a @Valid @RequestBody failure here throws HandlerMethodValidationException,
    // not MethodArgumentNotValidException -- CONSTRAINT_VIOLATION, no errors map
}
```

Preferred:

```java
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS)
@Validated
@PreAuthorize("isAuthenticated()")
public class ColumnController {
    // a @Valid @RequestBody failure here throws MethodArgumentNotValidException --
    // VALIDATION_FAILED, with a populated $.errors.<field> map
}
```

`BoardController` is the original reference for this pattern; `LayeringArchTest` is the enforcing mechanism.

### 12. An optional String field that rejects blank carries `@OptionalNotBlank`, not `@NotBlank`

When a `String` field is genuinely optional (it may be `null`/omitted, matching the
`@JsonInclude(JsonInclude.Include.NON_NULL)` partial-update convention from rule 6) but must not
accept a whitespace-only value when it *is* provided, stack `com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank`
alongside the field's existing composed annotation. `@NotBlank` is reserved for fields that are
genuinely mandatory — it rejects `null` as well as blank, so adding it to an optional field
silently makes that field required, breaking the partial-update contract.

Current application sites: `UpdateBoardRequestDTO.name`, `UpdateTaskRequestDTO.title`,
`UpdateSubtaskRequestDTO.title`, `SignupRequestDTO.displayName`. `UpdateColumnRequestDTO.name` is
the one documented exception in this codebase — see that class's Javadoc for why it keeps
`@NotBlank` and stays mandatory instead of adopting this pattern.

**Why:** Bean Validation's built-in constraints (including the `@Pattern` `@OptionalNotBlank`
composes) treat `null` as valid — only `@NotNull`/`@NotBlank`/`@NotEmpty` reject it — so
`@OptionalNotBlank` gets "reject blank, ignore absent" without a hand-written
`ConstraintValidator`. Reaching for `@NotBlank` on a field that is supposed to stay optional is an
easy mistake with no compiler signal to catch it; `@OptionalNotBlank`'s name makes the intended
contract explicit at the field itself.

Discouraged:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateWidgetRequestDTO {
    @NotBlank private String label; // also rejects null -- silently makes this field mandatory
    @NotNull private Long version;
}
```

Preferred:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateWidgetRequestDTO {
    @WidgetLabel @OptionalNotBlank private String label; // null passes, "   " does not
    @NotNull private Long version;
}
```

`OptionalNotBlank.java` is the reference implementation; `UpdateBoardRequestDTO.name` is the
reference application site.

### 13. A new test class belongs in a named subpackage of `com.vrudenko.kanban_board`, never directly in the root package

Every test class lives inside one of this tree's existing subpackages -- `service/`,
`controller/`, `dto/`, `e2e/{activity,board,column,subtask,task}/`, `activitylog/`, `config/`,
`security/`, `handler/`, `architecture/`, or `support/{containers,fixtures,listeners}/` -- chosen
by rule 4's purpose test (which subpackage) together with rule 4's base-class test (which tier).
The `e2e/` subtree is itself entity-subfoldered: a new flow test that spans board, column, task or
subtask concerns lives under `e2e/<entity>/`, not loosely under `e2e/` itself. The single named
exception is `KanbanBoardApplicationTests`, Spring Initializr's conventional root-package
context-load smoke test, which stays beside `KanbanBoardApplication` by idiomatic Spring Boot
convention rather than by any technical requirement -- `@SpringBootTest` with no `classes`
attribute walks *up* the package hierarchy for `@SpringBootConfiguration`, so every subpackage
below the root can still reach it. This rule governs *where the file lives*; rule 4 above governs
*which purpose/tier it serves* -- read both together when starting a new test file.

**Why:** the convention already existed in practice (every subpackage above was deliberately
created for a purpose), but existed nowhere in writing until quick task 260812-eg8, and an
unwritten convention is one that silently reopens every few sessions -- 11 test files (2,000+
lines across `ActivityLogCleanupIsolationTest`, `BoardCreationE2ETest`, `BoardFullReadTest`,
`ColumnDeletionTest`, `ColumnOrderingTest`, `EventIdGeneratorTest`,
`FlywaySchemaProvenanceTest`, `SubtaskLockingTest`, `TaskOrderingTest`, `ThemePersistenceTest`, and
the exempted `KanbanBoardApplicationTests`) had drifted into the root package before this rule was
written down and mechanically enforced. `architecture/TestPlacementArchTest.java` is the enforcing
mechanism: it fails `./gradlew test` the moment a new test class lands in the root package outside
the named exemption, so the drift cannot silently reopen the way it did before this rule existed.
It does not catch a test class that lands in the *wrong* subpackage for what it tests (e.g. a
column concern filed under `e2e/task/`) -- that judgement call is what this rule's prose, and rule
4's purpose test, are for.

Discouraged:

```java
package com.vrudenko.kanban_board;

@SpringBootTest
@AutoConfigureMockMvc
public class BoardFullReadTest extends AbstractAppMockMvcTest {
    // a new controller-purpose test filed directly in the root package --
    // TestPlacementArchTest fails the build the moment this lands
}
```

Preferred:

```java
package com.vrudenko.kanban_board.controller;

@SpringBootTest
@AutoConfigureMockMvc
public class BoardFullReadTest extends AbstractAppMockMvcTest {
    // filed under controller/, matching rule 4's purpose test for a single-endpoint HTTP contract
}
```

`TestPlacementArchTest` is the enforcing mechanism, matching how rules 7 and 11 already cite
`LayeringArchTest`.

## Adding a rule

New rules are appended as a new `###` section under `## Rules`, numbered with the next integer. Each rule must carry the same three parts: a rule statement, a bolded **Why** line, and a bad-vs-good code example.
