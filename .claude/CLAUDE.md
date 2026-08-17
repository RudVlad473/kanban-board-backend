<!-- GSD:project-start source:PROJECT.md -->

## Project

**Kanban Board Backend — Epic 2 Completion**

A Spring Boot 3.5.16 / Java 21 REST API backend for a Kanban board application (users → boards → columns → tasks → subtasks), with session-based authentication and ownership-based access control. This GSD project scopes specifically to finishing **Epic 2** of the existing [backend modernization plan](../docs/plans/backend-modernization/README.md): closing out the JPA/Hibernate depth work (N+1 fixes, optimistic locking) that was partially completed in a prior session.

**Core Value:** Ship the two remaining Epic 2 deliverables — a real "get full board" endpoint and optimistic locking on concurrent edits — as clean, independently reviewable, technically defensible work that matches the standard already set by the completed part of Epic 2.

### Constraints

- **Tech stack**: Spring Boot 3.5.16, Java 21, Spring Data JPA/Hibernate, PostgreSQL for both production and tests (tests run against a Testcontainers-managed PostgreSQL instance executing the same Flyway migrations) — no new frameworks introduced for this scope
- **Testing**: Match existing convention — unit tests for services/DTOs, integration tests (REST Assured) for controllers; query-count assertions via Hibernate `Statistics.getPrepareStatementCount()` (not `getQueryExecutionCount()`, which misses `findById()` calls)
- **PR discipline**: This work should remain reviewable as its own unit, consistent with the modernization plan's one-epic-per-PR intent
- **Format check**: `./gradlew spotlessCheck` and `./gradlew test` must pass (matches existing CI)
- **Secret scanning**: `.githooks/pre-commit` runs a pinned `gitleaks` scan of the staged diff first, ahead of formatting/tests, and refuses the commit on a detected credential — a real credential-shaped value (API key, AWS access key, password literal, etc.) pasted anywhere in a staged file, including `.planning/` prose, will be refused before it ever reaches formatting/test checks. `.github/workflows/secret-scan.yml` re-scans full history on every push/PR as a hard gate. A genuine false positive needs a narrow, evidence-cited entry in `.gitleaks.toml` (never a blanket path exemption) — see that file's existing entries for the pattern.

<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->

## Technology Stack

## Languages

- Java 21 - Backend application code

## Runtime

- JDK 21 (via Gradle wrapper and Docker)
- Gradle 8.7
- Lockfile: Not applicable (Gradle manages versioning via build.gradle)

## Frameworks

- Spring Boot 3.5.16 - Application framework and HTTP server
- Spring Web - REST API and HTTP request handling (`org.springframework.boot:spring-boot-starter-web`)
- Spring Data JPA - ORM and database abstraction (`org.springframework.boot:spring-boot-starter-data-jpa`)
- Spring Security - Authentication and authorization (`org.springframework.boot:spring-boot-starter-security`)
- Jakarta Validation - Bean validation and constraint annotations (`org.springframework.boot:spring-boot-starter-validation`)
- Spring Session JDBC - Server-side session storage in database
- SpringDoc OpenAPI 2.8.8 - Swagger/OpenAPI documentation generation and UI (`org.springdoc:springdoc-openapi-starter-webmvc-ui`)
- Spring Boot Test - Testing framework with JUnit 5 (`org.springframework.boot:spring-boot-starter-test`)
- Spring Security Test - Security-specific testing utilities (`org.springframework.security:spring-security-test`)
- REST Assured 5.5.5 - REST API testing library (`io.rest-assured:rest-assured`)
- JUnit Platform Launcher - Test platform discovery and execution
- Spotless 7.0.2 - Code formatting plugin using Google Java Format (AOSP variant)

## Key Dependencies

- MapStruct 1.5.3 - DTO mapping and object transformation (`org.mapstruct:mapstruct` and `mapstruct-processor`)
- Lombok 1.18.36 - Boilerplate reduction (annotations for getters, setters, constructors)
- ULID Creator 5.2.0 - Unique ID generation (`com.github.f4b6a3:ulid-creator`)
- PostgreSQL Driver - PostgreSQL database client (`org.postgresql:postgresql`)
- Testcontainers PostgreSQL 1.21.4 (BOM-managed via Spring Boot 3.5.16) - Real PostgreSQL 16
  container backing every test (`org.testcontainers:postgresql`)
- Vavr 0.10.4 - Functional programming utilities
- Guava 32.0.1-android - Google collections and utilities
- Apache Commons Lang 3 - String and utility functions (runtime dependency; moved from test-only scope, quick task 260813-q1i)
- Apache Commons Collections 4.5.0 - Collection utilities
- DataFactory 0.8 - Test data generation

## Configuration

- Environment variables for database connection:
- `build.gradle` - Gradle build configuration
- `gradle/wrapper/gradle-wrapper.properties` - Gradle wrapper version specification
- `Dockerfile` - Multi-stage Docker build configuration
- `src/main/resources/application.properties` - Main application configuration
- `src/main/resources/application-test.properties` - Test profile configuration

## Platform Requirements

- Java 21 JDK
- Gradle 8.7 (via wrapper)
- Docker Compose - Container deployment (`docker-compose.prod.yml`, standalone from local dev's
  `docker-compose.yml`)
- Netcup VPS Lite 2 G12s - Deployment target (v1.2 Phase 5; superseded AWS EC2, torn down on cost
  grounds — see `docs/INFRA_RUNBOOK.md`)
- Neon serverless Postgres - Database server
- Self-hosted Redpanda - Kafka-protocol broker, resource-capped
- Caddy - Automatic public HTTPS / reverse proxy
- Linux environment (from Docker image: `eclipse-temurin:21-jre-jammy`)
- Ports 80/443 published on the VM; app's port 8080 stays internal-only behind Caddy
- GitHub Actions - Automated testing, build, Flyway migration verification, and deployment
- Docker Hub - Container registry

<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->

## Conventions

## Naming Patterns

- Entity classes: `{EntityName}Entity.java` (e.g., `UserEntity.java`, `TaskEntity.java`)
- DTO classes: `{ObjectName}ResponseDTO.java`, `Save{ObjectName}RequestDTO.java`, `Update{ObjectName}RequestDTO.java` (e.g., `BoardResponseDTO.java`, `SaveTaskRequestDTO.java`)
- Service classes: `{ObjectName}Service.java` (e.g., `TaskService.java`, `ColumnService.java`)
- Controller classes: `{ObjectName}Controller.java` (e.g., `BoardController.java`)
- Repository interfaces: `{EntityName}Repository.java` (e.g., `TaskRepository.java`)
- Mapper interfaces: `{EntityName}Mapper.java` (e.g., `BoardMapper.java`)
- Test classes: `{ClassUnderTest}Test.java` (e.g., `TaskServiceTest.java`, `BoardControllerTest.java`)
- Base/abstract classes: `Base{Name}.java` (e.g., `BaseEntity.java`, `BaseUserOwnedService.java`)
- Constants: `{NameType}Constants.java` (e.g., `ValidationConstants.java`, `ApiPaths.java`)
- Exception classes: `App{ExceptionType}.java` (e.g., `AppAccessDeniedException.java`, `AppEntityNotFoundException.java`)
- camelCase: `findById()`, `verifyOwnershipOfBoard()`, `deleteAllByColumnId()`
- Prefix conventions:
- camelCase for all local variables: `userId`, `boardId`, `taskTitle`, `columnName`
- Constants in UPPER_SNAKE_CASE: `MIN_BOARD_NAME_LENGTH`, `MAX_TASK_DESCRIPTION_LENGTH`, `MOCK_COLUMNS_AMOUNT`
- Collection descriptive names: `mockTasks`, `mockColumns`, `listOfMessages`
- In tests, variable names correspond to what they represent: `userId`, `boardId`, `columnId`, `taskId`
- DTOs: `{ObjectName}ResponseDTO`, `Save{ObjectName}RequestDTO`, `Update{ObjectName}RequestDTO`
- Custom exceptions: `App{ExceptionType}` (extends JPA/Spring exceptions)
- Interfaces for contract definition: Named without "I" prefix (Java convention)

## Code Style

- Google Java Format with AOSP style (`googleJavaFormat().aosp()`)
- Managed by Spotless plugin (build.gradle)
- Target: `src/**/*.java`
- Features: Format annotations, import order, remove unused imports automatically
- Spotless with GoogleJavaFormat enforces consistent formatting
- No separate linting tool configured; formatting rules are the linting standard
- Judgement-level style rules (beyond what Spotless can check) are recorded in [`docs/CODE_STYLE.md`](../docs/CODE_STYLE.md) — consult it before writing or modifying Java code
- Architecture diagrams should aim to snap to Kruchten's 4+1 view model (Logical, Process, Development, Physical/Deployment, Scenarios) rather than an ad hoc mix of styles — see [`docs/DIAGRAM_CONVENTIONS.md`](../docs/DIAGRAM_CONVENTIONS.md); consult it before authoring or updating any architecture diagram

## Import Organization

- No path aliases configured; full package paths used throughout
- Package structure mirrors domain: `com.vrudenko.kanban_board.{service,controller,entity,dto,repository}`

## Error Handling

- Custom exception hierarchy: `AppAccessDeniedException` (extends `AccessDeniedException`), `AppEntityNotFoundException` (extends `EntityNotFoundException`)
- Constructor-based messaging: `new AppAccessDeniedException("Board")` produces message "You do not have access to that board"
- Exceptions thrown from service layer for domain logic violations (ownership verification, entity not found)
- Global exception handler (`GlobalExceptionHandler.java`, `@ControllerAdvice`) maps every exception to a single RFC 7807 `ProblemDetail` envelope (`code` + `detail`, and `errors` for field validation) rather than a mix of bare-string/map bodies. Ownership denials (`AppAccessDeniedException`/`AccessDeniedException`) map to **403**; a genuinely unauthenticated request never reaches this class at all — it is answered **401** by `ProblemDetailAuthenticationEntryPoint`, a separate producer wired into the Spring Security filter chain that emits the identical envelope shape (see `docs/ARCHITECTURE.md`'s "401 means unauthenticated, 403 means forbidden" note and its error-handling sequence diagram for the full four-way 401/403/400/409 split)
- Jakarta Validation (`jakarta.validation.constraints`) used for DTO field validation
- Custom validation annotations: `@BoardName`, `@TaskTitle`, `@SubtaskTitle`, `@DisplayName`, `@AppEmail`, `@Password`, `@Description`
- Constants defined in `ValidationConstants.java` for all length constraints (MIN/MAX pairs)
- Validation error messages defined as constants and reused in annotations

## Logging

- Logging not extensively used in current codebase; focus is on exception handling and method-level documentation
- Exception messages are informative (e.g., "You do not have access to that board", "User was not found")
- Performance-related comments in code (see TaskService deleteAllByColumn method) indicate implicit logging/monitoring concerns

## Comments

- Complex domain logic: Ownership verification chains (see `TaskService.deleteAllByColumn()`)
- Performance implications: N+1 query patterns and batch delete optimizations documented
- Implementation constraints: Hibernate persistence context behavior, JPQL bulk delete side effects
- Design decisions affecting callers: Method visibility, batch vs. single-operation variants
- Used for public API methods with non-obvious behavior
- Extensive multi-line JavaDoc on complex methods: `TaskService.deleteAllByColumn()` documents persistence context flushing strategy
- Mapper interfaces documented to explain MapStruct configuration
- DTO classes documented to explain relationships and constraints
- All custom validation annotations include JavaDoc explaining requirements
- Link references used: `{@link ClassName#methodName}` to cross-reference related methods
- HTML tags used in JavaDoc: `<p>` for paragraphs, `{@code variableName}` for inline code

## Function Design

- Order: `userId` or security context first, then resource identifiers, then request payloads
- DTOs always prefixed with `@Valid` for validation
- Path variables annotated with `@NotBlank` when expecting non-empty strings
- No builder patterns in method signatures; used only for object construction
- Service methods return DTOs (response objects): `TaskResponseDTO`, `BoardResponseDTO`
- Void for delete operations
- Generic `ResponseEntity<T>` wrapping in controllers
- Collections returned as Lists: `List<TaskResponseDTO>`
- Pairs used for multi-value returns: `Pair<UserEntity, BoardEntity>` from ownership verification

## Module Design

- Services: `@Service` stereotype beans, injected via `@Autowired`
- Controllers: `@RestController` endpoints, public methods return `ResponseEntity<T>`
- Repositories: Spring Data JPA interfaces, auto-implemented by framework
- Mappers: MapStruct interfaces, implementation auto-generated
- No barrel (wildcard export) files; each class explicitly imported
- Constants consolidated in {Domain}Constants.java files (ValidationConstants, ApiPaths)
- API routes centralized in `ApiPaths.java`
- Field injection via `@Autowired`: `@Autowired private TaskService taskService;`
- No constructor injection used in current codebase
- All dependencies explicitly declared as private fields at class level

## Annotations

- `@Service` - Service layer stereotype
- `@Repository` - Data access stereotype (implicit with Spring Data JPA)
- `@RestController` - REST endpoint handler
- `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping` - HTTP method bindings
- `@RequestMapping` - Class-level route prefix
- `@PathVariable` - URL path parameters
- `@RequestBody` - JSON request body binding
- `@Transactional` - Transaction management (from jakarta.transaction)
- `@Valid` - Cascade validation to nested objects
- `@PreAuthorize` - Method-level security (e.g., `"isAuthenticated()"`)
- `@Getter`, `@Setter` - Auto-generate accessors and mutators
- `@Builder` - Fluent builder pattern
- `@NoArgsConstructor`, `@AllArgsConstructor` - Constructor generation
- `@EqualsAndHashCode` - Equals and hashCode implementation
- `@JsonIgnore` - Exclude fields from JSON serialization
- `@Mapper` - Marks interface as MapStruct mapper
- `componentModel = MappingConstants.ComponentModel.SPRING` - Integrates as Spring bean
- `unmappedTargetPolicy = ReportingPolicy.IGNORE` - Silently ignore unmapped fields

## Transactionality

- Methods that read and then modify: `updateById()`, `findAllByColumnId()`
- Batch operations: `deleteAllByColumnId()`, `deleteAllByTaskIds()`
- Cascade operations: `deleteById()` on parent deletes all children
- Only methods that perform updates explicitly marked; pure reads without dependencies often unmarked
- Batch JPQL deletes (`deleteAllByIdInBatch()`) bypass persistence context
- `entityManager.flush()` and `entityManager.clear()` used to sync session state after bulk operations
- Transactional context prevents stale data in same transaction (important for account deletion scenarios)

<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->

## Architecture

## System Overview

```text

```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| BoardController | HTTP endpoints for board CRUD, column addition | `src/main/java/com/vrudenko/kanban_board/controller/BoardController.java` |
| ColumnController | HTTP endpoints for column retrieval, task addition | `src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java` |
| TaskController | HTTP endpoints for task CRUD, subtask addition | `src/main/java/com/vrudenko/kanban_board/controller/TaskController.java` |
| SubtaskController | HTTP endpoints for subtask CRUD operations | `src/main/java/com/vrudenko/kanban_board/controller/SubtaskController.java` |
| UserController | HTTP endpoints for the caller's own theme preference | `src/main/java/com/vrudenko/kanban_board/controller/UserController.java` |
| TaskMoveController | HTTP endpoint for cross-column task moves | `src/main/java/com/vrudenko/kanban_board/controller/TaskMoveController.java` |
| ActivityController | HTTP endpoint for a board's paginated activity feed | `src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java` |
| AuthenticationController | User signup, signin, session management | `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java` |
| BoardService | Board business logic, cascading deletes | `src/main/java/com/vrudenko/kanban_board/service/BoardService.java` |
| ColumnService | Column business logic, task operations | `src/main/java/com/vrudenko/kanban_board/service/ColumnService.java` |
| TaskService | Task business logic, subtask operations, batch deletes | `src/main/java/com/vrudenko/kanban_board/service/TaskService.java` |
| SubtaskService | Subtask business logic | `src/main/java/com/vrudenko/kanban_board/service/SubtaskService.java` |
| UserService | User registration, deletion, board management | `src/main/java/com/vrudenko/kanban_board/service/UserService.java` |
| OwnershipVerifierService | Access control — validates user ownership of resources | `src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java` |
| GlobalExceptionHandler | Centralized exception handling, HTTP status mapping | `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java` |

## Pattern Overview

- Spring Boot REST API with session-based authentication
- JPA/Hibernate ORM with PostgreSQL persistence
- MapStruct for DTO-to-Entity mapping
- Ownership-based access control (users can only modify their own boards)
- Transactional cascade deletes (board → columns → tasks → subtasks)
- ULID-based entity IDs (RandFlake implementation)
- Global exception handler for consistent error responses

## Layers

- Purpose: Receive HTTP requests, return JSON responses
- Location: `src/main/java/com/vrudenko/kanban_board/controller/`
- Contains: Spring @RestController classes (BoardController, ColumnController, TaskController, SubtaskController)
- Depends on: Service layer, DTOs, Security resolver (@CurrentUserId)
- Used by: HTTP clients (frontend, API consumers)
- Purpose: Authentication, authorization, session management
- Location: `src/main/java/com/vrudenko/kanban_board/security/`
- Contains: SecurityConfiguration, AuthenticationController, CurrentUserIdResolver, UserAuthenticationProvider, LogoutHandler
- Depends on: UserService, Spring Security framework
- Used by: Controllers (via @PreAuthorize, @CurrentUserId)
- Purpose: Implement business rules, orchestrate repository access, enforce ownership
- Location: `src/main/java/com/vrudenko/kanban_board/service/`
- Contains: Service classes for Board, Column, Task, Subtask, User, OwnershipVerifier
- Depends on: Repositories, Mappers, other Services (UserService calls BoardService, BoardService calls ColumnService, etc.)
- Used by: Controllers
- Purpose: Convert between Entities (database) and DTOs (API contracts)
- Location: `src/main/java/com/vrudenko/kanban_board/mapper/` and `src/main/java/com/vrudenko/kanban_board/dto/`
- Contains: MapStruct interfaces for automatic mapping, DTO classes with validation annotations
- Depends on: Entities, validation annotations
- Used by: Services when returning responses
- Purpose: Abstract database access via JPA interfaces
- Location: `src/main/java/com/vrudenko/kanban_board/repository/`
- Contains: Spring Data JpaRepository interfaces (BoardRepository, ColumnRepository, TaskRepository, SubtaskRepository, UserRepository)
- Depends on: Entities, Hibernate/JPA
- Used by: Services
- Purpose: Represent domain concepts and relationships
- Location: `src/main/java/com/vrudenko/kanban_board/entity/`
- Contains: JPA @Entity classes (UserEntity, BoardEntity, ColumnEntity, TaskEntity, SubtaskEntity)
- Depends on: Base classes (BaseEntity), Lombok annotations
- Used by: Repositories, Services
- Purpose: Bean definitions, security configuration, argument resolvers
- Location: `src/main/java/com/vrudenko/kanban_board/config/`
- Contains: BeanConfiguration (PasswordEncoder, AuthenticationManager), SecurityConfiguration, CustomArgumentResolverConfig, RandFlake ID generator
- Depends on: Spring Boot, Spring Security
- Used by: Application startup, request handling
- Purpose: Centralized exception-to-HTTP-status mapping
- Location: `src/main/java/com/vrudenko/kanban_board/handler/`
- Contains: GlobalExceptionHandler (@ControllerAdvice)
- Depends on: Custom exceptions, Spring exception types
- Used by: Spring framework to intercept exceptions

## Data Flow

### Primary Request Path (GET Boards)

### Board Deletion Path (DELETE Board)

### Authentication Path (POST Signup)

### State Management

- SecurityContext persisted via Spring Session JDBC (`spring.session.store-type=jdbc`) into the `spring_session_attributes` table; `spring_session` itself holds session metadata (id, timestamps, principal name), not the serialized context. Proven by `AuthenticationTest`.
- SessionCreationPolicy.IF_REQUIRED: session created only on login (line 66)
- `maximumSessions(2)` / `maxSessionsPreventsLogin=true` are enforced by a `CompositeSessionAuthenticationStrategy` bean (`SecurityConfiguration.sessionAuthenticationStrategy`) composing `ConcurrentSessionControlAuthenticationStrategy` (backed by a `SpringSessionBackedSessionRegistry` reading live `SPRING_SESSION` rows) and `ChangeSessionIdAuthenticationStrategy`, invoked explicitly from `AuthenticationController.authenticate` after `authenticationManager.authenticate(token)` succeeds and before the `SecurityContext` is saved — since Spring Security 6 no longer installs `SessionManagementFilter` on the default chain, and the custom signin path never ran one either, nothing else would call it. A third concurrent signin for one principal is rejected as HTTP 401 with the generic "Invalid username or password" body, deliberately indistinguishable from a wrong password (the `SessionAuthenticationException` collapses through the same Vavr `Try` and blanket catch as any other authentication failure) — proven by `AuthenticationTest.ConcurrentSessionCeiling`. The same call site rotates the session id on every successful authentication, so `sessionFixation` protection is real too — proven by `AuthenticationTest.SessionFixation`. Both controls apply to `signup` as well as `signin`, since both share this helper.
- Session timeout: `spring.session.timeout=180m` takes precedence over `server.servlet.session.timeout=1m` now that Spring Session JDBC is on the classpath, so the effective server-side idle timeout is 180 minutes (previously 1 minute, before that dependency was wired). `server.servlet.session.cookie.max-age=600` independently caps the cookie itself at 10 minutes, so the client-side and server-side lifetimes differ by design.
- Every resource (Board, Column, Task, Subtask) traces back to UserEntity via foreign keys
- OwnershipVerifierService chains verification: Subtask → Task → Column → Board → User
- Ownership checks happen at service layer before any modification
- Services use @Transactional to wrap operations in database transactions
- Batch deletes use EntityManager.flush() + clear() to maintain Hibernate session consistency
- Optimistic locking (`@Version` + explicit compare-before-mutate) covers `BoardEntity`, `ColumnEntity`, `TaskEntity` and `SubtaskEntity`; `UserEntity` deliberately does not carry it (last-write-wins theme preference, a documented trade-off, not a gap). `OptimisticLockingFailureException` is mapped to HTTP 409 by `GlobalExceptionHandler.handleOptimisticLockingFailure` — see `docs/ARCHITECTURE.md#concurrency-optimistic-locking`

## Key Abstractions

- Purpose: Common base class for all domain entities
- Location: `src/main/java/com/vrudenko/kanban_board/entity/BaseEntity.java`
- Provides: ULID-based id field via @RandFlakeId annotation (line 14)
- Examples: UserEntity, BoardEntity, ColumnEntity, TaskEntity, SubtaskEntity
- Purpose: Define common contracts for entities and DTOs
- Location: `src/main/java/com/vrudenko/kanban_board/base/entity/`
- Pattern: Small interfaces (1-2 fields) implemented by entities and corresponding DTOs
- Benefit: Ensures DTOs match entity structure, enables polymorphic handling
- Purpose: Generate distributed, sortable IDs without database round-trips
- Location: `src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java`
- Type: ULID (Universally Unique Lexicographically Sortable Identifier)
- Applied via @RandFlakeId annotation on id fields
- Purpose: Decouple API contracts from entity structure
- Location: `src/main/java/com/vrudenko/kanban_board/dto/` (organized by domain: board_dto, column_dto, task_dto, subtask_dto, user_dto)
- Types: SaveXRequestDTO (creation), UpdateXRequestDTO (partial updates), XResponseDTO (read)
- Validation: Custom @AppEmail, @BoardName, @Description, @DisplayName, @Password, @SubtaskTitle, @TaskTitle annotations on fields
- Purpose: Automatic Entity ↔ DTO conversion via MapStruct
- Location: `src/main/java/com/vrudenko/kanban_board/mapper/`
- Framework: MapStruct with componentModel=SPRING for autowiring, unmappedTargetPolicy=IGNORE
- Pattern: Interface with @Mapper annotation, no implementation needed (generated at compile time)

## Entry Points

- Location: `src/main/java/com/vrudenko/kanban_board/KanbanBoardApplication.java`
- Triggers: Spring Boot main method
- Responsibilities: Initialize Spring context, load configurations, scan components
- **Board Operations:** `/api/boards` (GET list, POST create), `/api/boards/{boardId}` (PUT, DELETE), `/api/boards/{boardId}/full` (GET nested board+columns+tasks+subtasks read), `/api/boards/{boardId}/columns` (POST create column)
- **Column Operations:** `/api/boards/{boardId}/columns` (GET all), `/api/boards/{boardId}/columns/{columnId}` (PUT, DELETE), `/api/boards/{boardId}/columns/{columnId}/reorder` (PATCH), `/api/boards/{boardId}/columns/{columnId}` (POST create task)
- **Task Operations:** `/api/boards/{boardId}/columns/{columnId}/tasks` (GET all), `/api/boards/{boardId}/columns/{columnId}/tasks/{taskId}` (PUT, DELETE), `/api/boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks` (POST create subtask)
- **Subtask Operations:** `/api/boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks` (GET all), `/api/boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks/{subtaskId}` (PUT, DELETE)
- **Task Move:** `/api/tasks/{taskId}/move` (PATCH, cross-column move)
- **Board Activity Feed:** `/api/boards/{boardId}/activity` (GET, paginated)
- **User Theme Preference:** `/api/users/me/theme` (GET, PUT — identity taken from the session, no user id in the path)
- **Authentication:** `/api/signin`, `/api/signup`, `/api/logout`
- Location: `src/main/java/com/vrudenko/kanban_board/config/SecurityConfiguration.java`
- Triggers: Before every HTTP request
- Responsibilities: Authorize requests, manage sessions, handle logout

## Architectural Constraints

- **Threading:** Single-threaded per request (standard servlet model). EntityManager, SecurityContext are thread-local.
- **Global state:** PasswordEncoder bean singleton, AuthenticationManager bean singleton, SecurityContextRepository bean singleton. No module-level mutable static state.
- **Circular imports:** Potential: UserService → BoardService → ColumnService → TaskService → SubtaskService → OwnershipVerifierService → UserRepository. No actual circular dependency because all use constructor/field injection with @Autowired (lazy initialization).
- **Session persistence:** All sessions stored in PostgreSQL `spring_session`/`spring_session_attributes` tables via Spring Session JDBC, not in memory — a restart or a second instance no longer discards logins. This does not mean every session-related guarantee holds across instances by accident: the concurrent-session ceiling (`maximumSessions(2)`) now holds across instances too, but specifically because its enforcing registry (`SpringSessionBackedSessionRegistry`) reads the count live from the same JDBC store rather than from per-instance bookkeeping (see the State Management note above) — so "allows horizontal scaling" should be read as "session state itself is shared, and this particular session control was deliberately built on top of that shared state," not as a blanket guarantee that every session control behaves identically at scale for free. That ceiling itself carries a knowingly accepted, bounded TOCTOU overshoot (F6, 2026-08-10 scan; D-01, quick task 260811-h2v): two genuinely simultaneous signins for one principal can both pass the count-then-register check, briefly allowing one extra session, self-correcting on the next non-concurrent signin — see `SecurityConfiguration.sessionAuthenticationStrategy`'s Javadoc.
- **Transactional cascade:** Ownership verification and delete cascades must happen within same @Transactional method to ensure consistency.
- **Lazy loading risk:** JPA entities lazy-load relationships. Any access to entity.getColumn() outside transaction can throw LazyInitializationException. Services avoid this by using repository queries that fetch complete graphs.

## Anti-Patterns

### Circular Dependency in Service Construction

### LazyInitializationException Risk in DTO Mapping

### Reusing findById() Without Re-verification

### Forgetting EntityManager.clear() After Batch Delete

## Error Handling

- Custom exceptions (AppEntityNotFoundException, AppAccessDeniedException) thrown from service layer
- GlobalExceptionHandler intercepts and maps every exception to one RFC 7807 ProblemDetail envelope with a stable `code`; 403 (not 401) for ownership denials, 409 for optimistic-lock conflicts, 400 for field validation. A genuinely unauthenticated request (401) never reaches this class — see `docs/ARCHITECTURE.md`'s error-handling sequence diagram for the full split.
- Controllers do not catch exceptions — they propagate to handler
- Vavr Try monad used in AuthenticationController (line 92-105) for functional error handling

## Cross-Cutting Concerns

- @Valid on controller parameters triggers validation
- Custom validators in dto/annotation/ package validate domain-specific rules (@AppEmail, @Password, @BoardName, etc.)
- Validation errors return 400 with field-error map via MethodArgumentNotValidException handler

<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->

## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->

## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:

- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

## GSD Execution Directives

- Source `.dev/gsd-run.sh` instead of re-pasting the runtime resolver in bash blocks: `. ./.dev/gsd-run.sh && gsd_run query ...`.
- BEFORE creating or approving any PLAN.md:
  1. Document 2 alternate technical approaches considered.
  2. Output a 3-column Trade-off Matrix: [Approach | Pros/Cons | Why Picked].
  3. Detail any non-obvious performance, memory, or security trade-offs (time complexity, state invalidation risks, etc.).
- NEVER auto-execute code blocks without explaining the core data-flow mechanism in 3 sentences or less.
- Operational lessons from past GSD sessions — git hygiene during phase execution — are recorded in [`docs/SESSION_LESSONS.md`](../docs/SESSION_LESSONS.md); read it before starting or resuming a phase-execution session.

<!-- GSD:profile-start -->

## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
