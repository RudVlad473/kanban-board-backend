# Codebase Structure

**Analysis Date:** 2026-07-31

## Directory Layout

```
kanban-board-backend/
├── src/
│   ├── main/
│   │   ├── java/com/vrudenko/kanban_board/
│   │   │   ├── base/                          # Base classes and interfaces
│   │   │   │   ├── entity/                    # Base entity interfaces (BaseId, BaseBoard, BaseTask, etc.)
│   │   │   │   └── service/                   # Base service interfaces
│   │   │   ├── config/                        # Spring beans and configuration
│   │   │   │   ├── BeanConfiguration.java
│   │   │   │   ├── CustomArgumentResolverConfig.java
│   │   │   │   ├── RandFlakeGenerator.java
│   │   │   │   └── RandFlakeId.java
│   │   │   ├── constant/                      # Application constants
│   │   │   │   ├── ApiPaths.java
│   │   │   │   ├── SecurityConstants.java
│   │   │   │   └── ValidationConstants.java
│   │   │   ├── controller/                    # REST controllers (HTTP entry points)
│   │   │   │   ├── BoardController.java
│   │   │   │   ├── ColumnController.java
│   │   │   │   ├── TaskController.java
│   │   │   │   └── SubtaskController.java
│   │   │   ├── dto/                           # Data Transfer Objects
│   │   │   │   ├── annotation/                # Custom validation annotations
│   │   │   │   │   ├── AppEmail.java
│   │   │   │   │   ├── BoardName.java
│   │   │   │   │   ├── Description.java
│   │   │   │   │   ├── DisplayName.java
│   │   │   │   │   ├── Password.java
│   │   │   │   │   ├── SubtaskTitle.java
│   │   │   │   │   └── TaskTitle.java
│   │   │   │   ├── board_dto/                 # Board request/response DTOs
│   │   │   │   │   ├── BoardResponseDTO.java
│   │   │   │   │   ├── DeleteBoardByIdRequestDTO.java
│   │   │   │   │   ├── SaveBoardRequestDTO.java
│   │   │   │   │   └── UpdateBoardRequestDTO.java
│   │   │   │   ├── column_dto/                # Column request/response DTOs
│   │   │   │   │   ├── ColumnResponseDTO.java
│   │   │   │   │   └── SaveColumnRequestDTO.java
│   │   │   │   ├── subtask_dto/               # Subtask request/response DTOs
│   │   │   │   │   ├── SaveSubtaskRequestDTO.java
│   │   │   │   │   ├── SubtaskResponseDTO.java
│   │   │   │   │   └── UpdateSubtaskRequestDTO.java
│   │   │   │   ├── task_dto/                  # Task request/response DTOs
│   │   │   │   │   ├── SaveTaskRequestDTO.java
│   │   │   │   │   ├── TaskResponseDTO.java
│   │   │   │   │   └── UpdateTaskRequestDTO.java
│   │   │   │   └── user_dto/                  # User request/response DTOs
│   │   │   │       ├── SigninRequestDTO.java
│   │   │   │       ├── SignupRequestDTO.java
│   │   │   │       └── UserResponseDTO.java
│   │   │   ├── entity/                        # JPA entities (domain model)
│   │   │   │   ├── BaseEntity.java
│   │   │   │   ├── BoardEntity.java
│   │   │   │   ├── ColumnEntity.java
│   │   │   │   ├── SubtaskEntity.java
│   │   │   │   ├── TaskEntity.java
│   │   │   │   └── UserEntity.java
│   │   │   ├── exception/                     # Custom exception classes
│   │   │   │   ├── AppAccessDeniedException.java
│   │   │   │   └── AppEntityNotFoundException.java
│   │   │   ├── handler/                       # Exception handlers
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   ├── mapper/                        # MapStruct DTOs ↔ Entities
│   │   │   │   ├── BoardMapper.java
│   │   │   │   ├── ColumnMapper.java
│   │   │   │   ├── SubtaskMapper.java
│   │   │   │   ├── TaskMapper.java
│   │   │   │   └── UserMapper.java
│   │   │   ├── repository/                    # Spring Data JPA repositories
│   │   │   │   ├── BoardRepository.java
│   │   │   │   ├── ColumnRepository.java
│   │   │   │   ├── SubtaskRepository.java
│   │   │   │   ├── TaskRepository.java
│   │   │   │   └── UserRepository.java
│   │   │   ├── security/                      # Security & authentication
│   │   │   │   ├── AuthenticationController.java
│   │   │   │   ├── CurrentUserId.java
│   │   │   │   ├── CurrentUserIdResolver.java
│   │   │   │   ├── LogoutHandler.java
│   │   │   │   ├── SecurityConfiguration.java
│   │   │   │   └── UserAuthenticationProvider.java
│   │   │   ├── service/                       # Business logic services
│   │   │   │   ├── BoardService.java
│   │   │   │   ├── ColumnService.java
│   │   │   │   ├── OwnershipVerifierService.java
│   │   │   │   ├── SubtaskService.java
│   │   │   │   ├── TaskService.java
│   │   │   │   └── UserService.java
│   │   │   └── KanbanBoardApplication.java   # Spring Boot entry point
│   │   └── resources/
│   │       ├── application.properties          # Production config
│   │       └── application-test.properties     # Test config
│   └── test/
│       └── java/com/vrudenko/kanban_board/
│           ├── KanbanBoardApplicationTests.java
│           ├── controller/                    # Controller tests
│           │   ├── BoardControllerTest.java
│           │   ├── ColumnControllerTest.java
│           │   ├── SubtaskControllerTest.java
│           │   └── TaskControllerTest.java
│           ├── dto/
│           │   └── SignupRequestDTOTest.java
│           ├── security/
│           │   └── AuthenticationE2ETest.java   # merged Signin/Signup/SessionPersistence/UserPersistence (Phase 7)
│           ├── service/                      # Service tests
│           │   ├── BoardServiceTest.java
│           │   ├── ColumnServiceTest.java
│           │   ├── OwnershipVerifierServiceTest.java
│           │   ├── TaskServiceTest.java
│           │   └── UserServiceTest.java
│           └── support/                      # Shared test infrastructure, 3-way split (Phase 7, D-01)
│               ├── containers/                # Testcontainers lifecycle only
│               │   ├── AbstractPostgresContainerTest.java
│               │   └── AbstractKafkaContainerTest.java
│               ├── fixtures/                  # App-domain fixture data + HTTP-flow helpers
│               │   ├── AbstractAppTest.java          # base for service/integration tests
│               │   ├── AbstractAppE2ETest.java        # base for real-socket (RestAssured) E2E tests
│               │   └── AbstractAppMockMvcTest.java    # base for in-process MockMvc E2E tests
│               └── listeners/                 # Event-capture test doubles (real Spring components)
│                   └── RecordingActivityEventListener.java
│           # Note: this tree predates Phase 4-6 additions (activitylog/, e2e/{activity,column,task}/,
│           # event/, architecture/) and is not exhaustive outside what Phase 7 corrected above.
├── build.gradle                               # Gradle build configuration
├── build/                                     # Compiled artifacts (generated)
├── gradle/                                    # Gradle wrapper scripts
├── .gradle/                                   # Gradle cache (generated)
├── .github/
│   └── workflows/                             # GitHub Actions CI/CD
├── scripts/                                   # Utility scripts
├── docs/                                      # Project documentation
├── .idea/                                     # IntelliJ IDEA config (generated)
├── .planning/                                 # GSD planning directory
│   ├── codebase/                              # Analysis documents (ARCHITECTURE.md, STRUCTURE.md, etc.)
│   └── graphs/                                # Knowledge graph (if used)
├── .claude/                                   # Claude configuration
├── README.md                                  # Project overview
├── Dockerfile                                 # Docker image definition
├── settings.gradle                            # Gradle settings
├── gradlew / gradlew.bat                      # Gradle wrapper (build tool)
└── .gitignore                                 # Git ignore rules
```

## Directory Purposes

**src/main/java/com/vrudenko/kanban_board/base/**
- Purpose: Shared base classes and marker interfaces for entities and services
- Contains: BaseEntity interfaces (BaseId, BaseBoard, BaseTask, etc.) that define contracts
- Key files: `base/entity/BaseId.java`, `base/entity/BaseBoard.java`, `base/service/BaseUserOwnedService.java`

**src/main/java/com/vrudenko/kanban_board/config/**
- Purpose: Spring framework configuration, bean definitions, custom ID generators
- Contains: BeanConfiguration (PasswordEncoder, AuthenticationManager), SecurityConfiguration, CustomArgumentResolverConfig, RandFlake ID annotation
- Key files: `config/BeanConfiguration.java`, `config/SecurityConfiguration.java`

**src/main/java/com/vrudenko/kanban_board/constant/**
- Purpose: Application-wide constants (API paths, validation rules, security settings)
- Contains: ApiPaths (REST endpoint paths), SecurityConstants (session names), ValidationConstants (max lengths)
- Key files: `constant/ApiPaths.java`

**src/main/java/com/vrudenko/kanban_board/controller/**
- Purpose: HTTP REST endpoints, request routing, response formatting
- Contains: Spring @RestController classes for Board, Column, Task, Subtask operations
- Key files: `controller/BoardController.java`, `controller/TaskController.java`

**src/main/java/com/vrudenko/kanban_board/dto/**
- Purpose: Request/response data contracts between API and clients
- Contains: Request DTOs (SaveXRequestDTO, UpdateXRequestDTO), Response DTOs (XResponseDTO), Custom validation annotations
- Organization: One subdirectory per domain entity type (board_dto/, column_dto/, task_dto/, subtask_dto/, user_dto/, annotation/)
- Key files: Organized by entity, e.g., `dto/board_dto/SaveBoardRequestDTO.java`

**src/main/java/com/vrudenko/kanban_board/entity/**
- Purpose: Domain entities representing database tables
- Contains: JPA @Entity classes with Lombok annotations, relationships (OneToMany, ManyToOne)
- Key files: `entity/BoardEntity.java`, `entity/UserEntity.java`, `entity/BaseEntity.java` (parent class)

**src/main/java/com/vrudenko/kanban_board/exception/**
- Purpose: Custom exception classes for domain-specific errors
- Contains: AppAccessDeniedException (401 Unauthorized), AppEntityNotFoundException (404 Not Found)
- Key files: `exception/AppAccessDeniedException.java`

**src/main/java/com/vrudenko/kanban_board/handler/**
- Purpose: Centralized exception-to-HTTP-response mapping
- Contains: GlobalExceptionHandler (@ControllerAdvice) with @ExceptionHandler methods per exception type
- Key files: `handler/GlobalExceptionHandler.java`

**src/main/java/com/vrudenko/kanban_board/mapper/**
- Purpose: Automatic Entity ↔ DTO conversion via MapStruct
- Contains: MapStruct @Mapper interfaces (no implementation needed, generated at compile time)
- Key files: `mapper/BoardMapper.java`, `mapper/UserMapper.java`

**src/main/java/com/vrudenko/kanban_board/repository/**
- Purpose: Data access abstraction via Spring Data JPA
- Contains: JpaRepository interfaces with custom finder methods (findAllByUserId, findAllByColumnId, etc.)
- Key files: `repository/BoardRepository.java`, `repository/UserRepository.java`

**src/main/java/com/vrudenko/kanban_board/security/**
- Purpose: Authentication, authorization, session management
- Contains: SecurityConfiguration (HTTP security rules), AuthenticationController (signin/signup), CurrentUserIdResolver (extract userId from SecurityContext)
- Key files: `security/SecurityConfiguration.java`, `security/AuthenticationController.java`

**src/main/java/com/vrudenko/kanban_board/service/**
- Purpose: Business logic, orchestration between repositories, ownership verification
- Contains: Service classes for each entity type, OwnershipVerifierService for access control, UserService (implements UserDetailsService for Spring Security)
- Key files: `service/BoardService.java`, `service/OwnershipVerifierService.java`

**src/main/resources/**
- Purpose: Runtime configuration files
- Contains: application.properties (database URL, session store, Swagger docs path), application-test.properties (Testcontainers-managed PostgreSQL for tests, Flyway-built schema)
- Key files: `application.properties`

**src/test/java/**
- Purpose: Unit tests, integration tests, end-to-end tests
- Contains: Test classes mirroring src/main/java structure plus shared fixture/setup infrastructure under `support/` (Phase 7, D-01: split into `support/containers/`, `support/fixtures/`, `support/listeners/` — no shared base class or Spring test component is interspersed with concrete test classes any more)
- Key files: `support/fixtures/AbstractAppTest.java` (base for unit/integration tests), `support/fixtures/AbstractAppE2ETest.java` (base for real-socket E2E tests), `support/fixtures/AbstractAppMockMvcTest.java` (base for in-process MockMvc E2E tests, added Phase 7)

**build/ (Generated)**
- Purpose: Compiled Java classes, JAR artifacts
- Contains: build/classes/java/main/ (compiled .class files), build/libs/ (packaged JAR)
- Note: Committed to .gitignore, regenerated by `./gradlew build`

**.planning/codebase/**
- Purpose: GSD analysis documents
- Contains: ARCHITECTURE.md, STRUCTURE.md, CONVENTIONS.md, TESTING.md, CONCERNS.md, STACK.md, INTEGRATIONS.md
- Generated by: `/gsd-map-codebase` command

## Key File Locations

**Entry Points:**
- `KanbanBoardApplication.java`: Spring Boot main() method, application bootstrap
- `controller/BoardController.java`, `controller/ColumnController.java`, `controller/TaskController.java`, `controller/SubtaskController.java`: HTTP request entry points
- `security/AuthenticationController.java`: Signin/signup entry points

**Configuration:**
- `application.properties`: Database URL, session store, Swagger path, JPA naming strategy, session timeout, cookie settings
- `build.gradle`: Dependencies (Spring Boot, JPA, Security, MapStruct, PostgreSQL driver, Test libraries), build plugins (Spotless for formatting)
- `config/SecurityConfiguration.java`: HTTP security rules, session management, authentication provider, logout handler
- `config/BeanConfiguration.java`: PasswordEncoder, AuthenticationManager, SecurityContextRepository beans

**Core Logic:**
- `service/BoardService.java`: Board CRUD, column management, cascading deletes
- `service/TaskService.java`: Task CRUD, subtask management, batch delete optimization
- `service/OwnershipVerifierService.java`: Access control — verifies user ownership before any modification
- `service/UserService.java`: User registration, board management, deletion, Spring Security integration

**Data Access:**
- `repository/BoardRepository.java`, `repository/TaskRepository.java`, etc.: JPA repository interfaces
- `entity/BaseEntity.java`: Common base for all entities (ULID id generation)
- `mapper/BoardMapper.java`, `mapper/TaskMapper.java`, etc.: Entity ↔ DTO conversion

**Testing:**
- `support/fixtures/AbstractAppTest.java`: Base class for unit/integration tests (test database setup, test users, helper methods)
- `support/fixtures/AbstractAppE2ETest.java`: Base class for real-socket end-to-end tests (RestAssured client, HTTP-level testing) — as of Phase 7 this tier has exactly one remaining subclass, `BoardCreationE2ETest` (kept for its genuinely concurrent `ConcurrentCreate` race)
- `support/fixtures/AbstractAppMockMvcTest.java`: Base class for in-process end-to-end tests (real `POST /signin`/`POST /signup` through `MockMvc` + cookie relay, no socket) — added Phase 7 as the cheaper counterpart to `AbstractAppE2ETest`
- `service/BoardServiceTest.java`, `service/TaskServiceTest.java`: Service layer tests
- `controller/BoardControllerTest.java`: Controller tests with mock services
- `security/AuthenticationE2ETest.java`: In-process end-to-end tests for signin/signup/session persistence/concurrent-session-ceiling/session-fixation (merged from three files, Phase 7)

## Naming Conventions

**Files:**
- Entity classes: `{EntityName}Entity.java` (e.g., `BoardEntity.java`, `TaskEntity.java`)
- DTO classes: `{Operation}{EntityName}{Type}DTO.java` (e.g., `SaveBoardRequestDTO.java`, `BoardResponseDTO.java`, `UpdateTaskRequestDTO.java`)
- Mapper classes: `{EntityName}Mapper.java` (e.g., `BoardMapper.java`)
- Repository classes: `{EntityName}Repository.java` (e.g., `BoardRepository.java`)
- Service classes: `{EntityName}Service.java` (e.g., `BoardService.java`)
- Controller classes: `{EntityName}Controller.java` (e.g., `BoardController.java`)
- Test classes: `{ClassName}Test.java` (e.g., `BoardServiceTest.java`, `BoardE2ETest.java`)
- Custom validators: `{ValidationRule}.java` (e.g., `AppEmail.java`, `BoardName.java`)

**Directories:**
- Domain entity directories: lowercase entity name with underscore (e.g., `board_dto/`, `column_dto/`)
- Annotation directories: `annotation/` for validation annotations
- Base classes: `base/` for shared abstractions

**Java Conventions:**
- Class names: PascalCase (BoardEntity, BoardService)
- Method names: camelCase (findAllByUserId, deleteById)
- Variable names: camelCase (userId, boardId)
- Constants: UPPER_SNAKE_CASE (SESSION_NAME, MAX_TASK_TITLE_LENGTH)
- Package names: lowercase (com.vrudenko.kanban_board)

## Where to Add New Code

**New Feature (e.g., add priority to tasks):**
- Add field to `entity/TaskEntity.java`
- Add field to `dto/task_dto/TaskResponseDTO.java` and `dto/task_dto/SaveTaskRequestDTO.java`
- Update `mapper/TaskMapper.java` (MapStruct auto-generates if field names match)
- Add validation annotation to DTO field if needed (e.g., `@Priority`)
- Add custom validator in `dto/annotation/Priority.java` if validation is complex
- Update `service/TaskService.java` to handle new field in updateById() method
- Update `controller/TaskController.java` if new endpoint needed
- Add test in `service/TaskServiceTest.java` and `controller/TaskControllerTest.java`
- Update `application.properties` if new configuration needed

**New Entity Type (e.g., add Tag entity):**
- Create `entity/TagEntity.java` extending `BaseEntity`, implementing domain interface (e.g., `BaseTag`)
- Create base interface `base/entity/BaseTag.java`
- Create DTOs: `dto/tag_dto/SaveTagRequestDTO.java`, `dto/tag_dto/TagResponseDTO.java`
- Create custom validators in `dto/annotation/` if needed (e.g., `@TagName.java`)
- Create `mapper/TagMapper.java` (@Mapper interface)
- Create `repository/TagRepository.java` extending JpaRepository
- Create `service/TagService.java` with CRUD methods, calling OwnershipVerifierService for access control
- Create `controller/TagController.java` with @RestController, @PreAuthorize("isAuthenticated()"), @RequestMapping("/tags")
- Update entity relationships: if Tag belongs to Task, add `@ManyToOne private TaskEntity task;` to TagEntity and `@OneToMany(mappedBy = "tag") private List<TagEntity> tags;` to TaskEntity
- Add tests: `service/TagServiceTest.java`, `controller/TagControllerTest.java`
- Ensure database migration adds tag table and foreign keys (if using Flyway/Liquibase; currently manual)

**New Utility / Helper Class:**
- If shared across services: `service/UtilityService.java` (autowired as needed)
- If shared across DTOs: `dto/annotation/UtilityValidator.java` (custom validator)
- If configuration-related: `config/UtilityConfiguration.java`
- If exception: `exception/AppUtilityException.java`

**New Test:**
- Co-locate with code being tested: test file in `src/test/java/` mirrors `src/main/java/` structure
- Extend one of three bases under `support/fixtures/` (Phase 7 split; see `docs/CODE_STYLE.md` rule 4 for the full decision rule): `AbstractAppTest` for tests that call services directly, `AbstractAppMockMvcTest` for HTTP tests that don't need a real socket, `AbstractAppE2ETest` only when a genuinely concurrent multi-threaded request is required
- Use `DataFactory` for test data generation (library included in build.gradle)
- No mocks anywhere in this repository (`docs/CODE_STYLE.md` rule 4) — real Spring wiring and a real Testcontainers-managed PostgreSQL for every test, including controller tests
- Follow naming: `{ClassUnderTest}Test.java`

## Special Directories

**build/ (Generated)**
- Purpose: Compiled artifacts
- Generated: Yes (by `./gradlew build`)
- Committed: No (.gitignore excludes)

**.gradle/ (Generated)**
- Purpose: Gradle cache and build metadata
- Generated: Yes (by gradlew)
- Committed: No (.gitignore excludes)

**.idea/ (Generated)**
- Purpose: IntelliJ IDEA IDE configuration
- Generated: Yes (by IntelliJ on project open)
- Committed: No (.gitignore excludes)

**.planning/ (Maintained by GSD)**
- Purpose: Planning and analysis documents
- Generated: By `/gsd-map-codebase` and other GSD commands
- Committed: Yes (tracked in git, committed after analysis/planning)

**.claude/ (User Configuration)**
- Purpose: Claude Code settings, skills
- Generated: Manually by user or automated setup
- Committed: Yes (project-specific settings)

**scripts/ (Project Utilities)**
- Purpose: Helper scripts for development, testing, deployment
- Contents: Shell scripts, database setup, etc. (as needed)
- Committed: Yes

**docs/ (Project Documentation)**
- Purpose: API documentation, architecture diagrams, user guides
- Committed: Yes (external documentation)

---

*Structure analysis: 2026-07-31*
