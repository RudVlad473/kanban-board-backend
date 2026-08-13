<!-- refreshed: 2026-07-31 -->
# Architecture

**Analysis Date:** 2026-07-31

## System Overview

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                           HTTP Controllers                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │   Board      │  │   Column     │  │    Task      │  │  Subtask     │   │
│  │ Controller   │  │  Controller  │  │ Controller   │  │  Controller  │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │
│           │                │                │                │               │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │           AuthenticationController (Security)                      │    │
│  │  `src/main/java/com/vrudenko/kanban_board/security/`              │    │
│  └────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            Service Layer                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │   Board      │  │   Column     │  │    Task      │  │  Subtask     │   │
│  │  Service     │  │  Service     │  │   Service    │  │   Service    │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │
│           │                │                │                │               │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │       OwnershipVerifierService (Access Control)                   │    │
│  │  `src/main/java/com/vrudenko/kanban_board/service/`              │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│           │                                                                   │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │             UserService (User + Board Setup)                      │    │
│  └────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Mapping & DTO Layer                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │   Board      │  │   Column     │  │    Task      │  │   User       │   │
│  │   Mapper     │  │   Mapper     │  │   Mapper     │  │   Mapper     │   │
│  │ (MapStruct)  │  │ (MapStruct)  │  │ (MapStruct)  │  │ (MapStruct)  │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │
│  `src/main/java/com/vrudenko/kanban_board/mapper/`                        │
│  `src/main/java/com/vrudenko/kanban_board/dto/`                           │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Data Access & Persistence Layer                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │   Board      │  │   Column     │  │    Task      │  │  Subtask     │   │
│  │ Repository   │  │ Repository   │  │ Repository   │  │ Repository   │   │
│  │ (JPA)        │  │ (JPA)        │  │ (JPA)        │  │ (JPA)        │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │
│           │                │                │                │               │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │   UserRepository (JPA)                                             │    │
│  │   `src/main/java/com/vrudenko/kanban_board/repository/`           │    │
│  └────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Entity & Domain Layer                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │   Board      │  │   Column     │  │    Task      │  │  Subtask     │   │
│  │   Entity     │  │   Entity     │  │   Entity     │  │   Entity     │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │
│           │                │                │                │               │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │   UserEntity (Base: BaseEntity with RandFlake ID)                 │    │
│  │   `src/main/java/com/vrudenko/kanban_board/entity/`              │    │
│  └────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PostgreSQL Database                                │
│  (tables: users, boards, columns, tasks, subtasks, spring_session)         │
└─────────────────────────────────────────────────────────────────────────────┘
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

**Overall:** Layered architecture with vertical slices per domain entity

**Key Characteristics:**
- Spring Boot REST API with session-based authentication
- JPA/Hibernate ORM with PostgreSQL persistence
- MapStruct for DTO-to-Entity mapping
- Ownership-based access control (users can only modify their own boards)
- Transactional cascade deletes (board → columns → tasks → subtasks)
- ULID-based entity IDs (RandFlake implementation)
- Global exception handler for consistent error responses

## Layers

**Presentation Layer:**
- Purpose: Receive HTTP requests, return JSON responses
- Location: `src/main/java/com/vrudenko/kanban_board/controller/`
- Contains: Spring @RestController classes (BoardController, ColumnController, TaskController, SubtaskController)
- Depends on: Service layer, DTOs, Security resolver (@CurrentUserId)
- Used by: HTTP clients (frontend, API consumers)

**Security Layer:**
- Purpose: Authentication, authorization, session management
- Location: `src/main/java/com/vrudenko/kanban_board/security/`
- Contains: SecurityConfiguration, AuthenticationController, CurrentUserIdResolver, UserAuthenticationProvider, LogoutHandler
- Depends on: UserService, Spring Security framework
- Used by: Controllers (via @PreAuthorize, @CurrentUserId)

**Service Layer (Business Logic):**
- Purpose: Implement business rules, orchestrate repository access, enforce ownership
- Location: `src/main/java/com/vrudenko/kanban_board/service/`
- Contains: Service classes for Board, Column, Task, Subtask, User, OwnershipVerifier
- Depends on: Repositories, Mappers, other Services (UserService calls BoardService, BoardService calls ColumnService, etc.)
- Used by: Controllers

**Data Mapping Layer:**
- Purpose: Convert between Entities (database) and DTOs (API contracts)
- Location: `src/main/java/com/vrudenko/kanban_board/mapper/` and `src/main/java/com/vrudenko/kanban_board/dto/`
- Contains: MapStruct interfaces for automatic mapping, DTO classes with validation annotations
- Depends on: Entities, validation annotations
- Used by: Services when returning responses

**Persistence Layer:**
- Purpose: Abstract database access via JPA interfaces
- Location: `src/main/java/com/vrudenko/kanban_board/repository/`
- Contains: Spring Data JpaRepository interfaces (BoardRepository, ColumnRepository, TaskRepository, SubtaskRepository, UserRepository)
- Depends on: Entities, Hibernate/JPA
- Used by: Services

**Entity/Domain Layer:**
- Purpose: Represent domain concepts and relationships
- Location: `src/main/java/com/vrudenko/kanban_board/entity/`
- Contains: JPA @Entity classes (UserEntity, BoardEntity, ColumnEntity, TaskEntity, SubtaskEntity)
- Depends on: Base classes (BaseEntity), Lombok annotations
- Used by: Repositories, Services

**Configuration Layer:**
- Purpose: Bean definitions, security configuration, argument resolvers
- Location: `src/main/java/com/vrudenko/kanban_board/config/`
- Contains: BeanConfiguration (PasswordEncoder, AuthenticationManager), SecurityConfiguration, CustomArgumentResolverConfig, RandFlake ID generator
- Depends on: Spring Boot, Spring Security
- Used by: Application startup, request handling

**Exception Handling Layer:**
- Purpose: Centralized exception-to-HTTP-status mapping
- Location: `src/main/java/com/vrudenko/kanban_board/handler/`
- Contains: GlobalExceptionHandler (@ControllerAdvice)
- Depends on: Custom exceptions, Spring exception types
- Used by: Spring framework to intercept exceptions

## Data Flow

### Primary Request Path (GET Boards)

1. **Request Entry** (`BoardController.findAllByUserId()` - line 34)
   - HTTP GET `/api/boards`
   - @CurrentUserId resolver extracts userId from SecurityContext via CurrentUserIdResolver (line 28-29 of CurrentUserIdResolver)

2. **Authorization Check** (line 29 of BoardController @PreAuthorize)
   - Spring Security verifies `isAuthenticated()` before method execution

3. **Service Processing** (BoardService.findAllByUserId() - line 28-30)
   - Call repository.findAllByUserId(userId) to fetch boards owned by user

4. **Mapping** (BoardMapper.toResponseDTOList() - line 22)
   - MapStruct automatically converts List<BoardEntity> to List<BoardResponseDTO>

5. **Response** (line 37 of BoardController)
   - Return 200 OK with JSON array of BoardResponseDTO objects

### Board Deletion Path (DELETE Board)

1. **Request Entry** (`BoardController.deleteById()` - line 41-46)
   - HTTP DELETE `/api/boards/{boardId}`
   - PathVariable boardId extracted, @CurrentUserId provides userId

2. **Ownership Verification** (BoardService.deleteById() → findById() - line 59-62)
   - OwnershipVerifierService.verifyOwnershipOfBoard() checks user owns board
   - Throws AppAccessDeniedException if ownership check fails (line 54)
   - Returns Pair<UserEntity, BoardEntity>

3. **Cascade Delete** (BoardService.deleteById() - line 44)
   - ColumnService.deleteAllByBoardId() → TaskService.deleteAllByColumn()
   - TaskService batches delete via taskRepository.deleteAllByIdInBatch() (line 115 of TaskService)
   - SubtaskService.deleteAllByTaskIds() also batches deletes (line 114)
   - entityManager.flush() and entityManager.clear() maintain session consistency (lines 117-118)

4. **Database Delete** (line 46 of BoardService)
   - boardRepository.deleteById(board.getId()) removes board record

5. **Response** (line 45 of BoardController)
   - Return 200 OK (empty body)

### Authentication Path (POST Signup)

1. **Request Entry** (`AuthenticationController.signup()` - line 62-83)
   - HTTP POST `/api/signup` with SignupRequestDTO body

2. **User Creation** (UserService.save() - line 56-57)
   - UserMapper converts SignupRequestDTO to UserEntity
   - userRepository.save() persists user with hashed password (BCrypt via passwordEncoder)

3. **Authentication** (AuthenticationController.authenticate() - line 85-106)
   - Create UsernamePasswordAuthenticationToken with userId as username, password as credentials
   - authenticationManager.authenticate() delegates to UserAuthenticationProvider → UserService.loadUserByUsername()
   - On success: create SecurityContext, save to session repository (line 101)

4. **Session Cookie** (line 101 of AuthenticationController)
   - SecurityContextRepository.saveContext() persists authentication to JDBC session store
   - Browser receives JSESSIONID cookie with HttpOnly, Secure, SameSite=Strict flags

5. **Response** (line 82 of AuthenticationController)
   - Return 201 Created with Location header

### State Management

**Session-Based Authentication:**
- SecurityContext stored in PostgreSQL via spring_session table (application.properties line 23-24)
- SessionCreationPolicy.IF_REQUIRED: session created only on login (line 66)
- Maximum 2 concurrent sessions per user, maxSessionsPreventsLogin=true (line 63)
- Session timeout: 1 minute at servlet level, 180 minutes at Spring Session level (application.properties lines 22, 27)

**Ownership-Based Access Control:**
- Every resource (Board, Column, Task, Subtask) traces back to UserEntity via foreign keys
- OwnershipVerifierService chains verification: Subtask → Task → Column → Board → User
- Ownership checks happen at service layer before any modification

**Transaction Management:**
- Services use @Transactional to wrap operations in database transactions
- Batch deletes use EntityManager.flush() + clear() to maintain Hibernate session consistency
- Optimistic locking handled by GlobalExceptionHandler (line 81-84)

## Key Abstractions

**BaseEntity:**
- Purpose: Common base class for all domain entities
- Location: `src/main/java/com/vrudenko/kanban_board/entity/BaseEntity.java`
- Provides: ULID-based id field via @RandFlakeId annotation (line 14)
- Examples: UserEntity, BoardEntity, ColumnEntity, TaskEntity, SubtaskEntity

**BaseId & BaseBoard (Marker Interfaces):**
- Purpose: Define common contracts for entities and DTOs
- Location: `src/main/java/com/vrudenko/kanban_board/base/entity/`
- Pattern: Small interfaces (1-2 fields) implemented by entities and corresponding DTOs
- Benefit: Ensures DTOs match entity structure, enables polymorphic handling

**RandFlakeId:**
- Purpose: Generate distributed, sortable IDs without database round-trips
- Location: `src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java`
- Type: ULID (Universally Unique Lexicographically Sortable Identifier)
- Applied via @RandFlakeId annotation on id fields

**DTO Layer (Request/Response Objects):**
- Purpose: Decouple API contracts from entity structure
- Location: `src/main/java/com/vrudenko/kanban_board/dto/` (organized by domain: board_dto, column_dto, task_dto, subtask_dto, user_dto)
- Types: SaveXRequestDTO (creation), UpdateXRequestDTO (partial updates), XResponseDTO (read)
- Validation: Custom @AppEmail, @BoardName, @Description, @DisplayName, @Password, @SubtaskTitle, @TaskTitle annotations on fields

**Mappers:**
- Purpose: Automatic Entity ↔ DTO conversion via MapStruct
- Location: `src/main/java/com/vrudenko/kanban_board/mapper/`
- Framework: MapStruct with componentModel=SPRING for autowiring, unmappedTargetPolicy=IGNORE
- Pattern: Interface with @Mapper annotation, no implementation needed (generated at compile time)

## Entry Points

**Application Bootstrap:**
- Location: `src/main/java/com/vrudenko/kanban_board/KanbanBoardApplication.java`
- Triggers: Spring Boot main method
- Responsibilities: Initialize Spring context, load configurations, scan components

**HTTP Controller Entry Points:**
- **Board Operations:** `/api/boards` (GET list, POST create), `/api/boards/{boardId}` (PUT, DELETE), `/api/boards/{boardId}/full` (GET nested board+columns+tasks+subtasks read), `/api/boards/{boardId}/columns` (POST create column)
  - `src/main/java/com/vrudenko/kanban_board/controller/BoardController.java`
- **Column Operations:** `/api/boards/{boardId}/columns` (GET all), `/api/boards/{boardId}/columns/{columnId}` (PUT, DELETE), `/api/boards/{boardId}/columns/{columnId}/reorder` (PATCH), `/api/boards/{boardId}/columns/{columnId}` (POST create task)
  - `src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java`
- **Task Operations:** `/api/boards/{boardId}/columns/{columnId}/tasks` (GET all), `/api/boards/{boardId}/columns/{columnId}/tasks/{taskId}` (PUT, DELETE), `/api/boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks` (POST create subtask)
  - `src/main/java/com/vrudenko/kanban_board/controller/TaskController.java`
- **Subtask Operations:** `/api/boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks` (GET all), `/api/boards/{boardId}/columns/{columnId}/tasks/{taskId}/subtasks/{subtaskId}` (PUT, DELETE)
  - `src/main/java/com/vrudenko/kanban_board/controller/SubtaskController.java`
- **Task Move:** `/api/tasks/{taskId}/move` (PATCH, cross-column move)
  - `src/main/java/com/vrudenko/kanban_board/controller/TaskMoveController.java`
- **Board Activity Feed:** `/api/boards/{boardId}/activity` (GET, paginated)
  - `src/main/java/com/vrudenko/kanban_board/controller/ActivityController.java`
- **User Theme Preference:** `/api/users/me/theme` (GET, PUT — identity taken from the session, no user id in the path)
  - `src/main/java/com/vrudenko/kanban_board/controller/UserController.java`
- **Authentication:** `/api/signin`, `/api/signup`, `/api/logout`
  - `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java`

**Spring Security Entry Point:**
- Location: `src/main/java/com/vrudenko/kanban_board/config/SecurityConfiguration.java`
- Triggers: Before every HTTP request
- Responsibilities: Authorize requests, manage sessions, handle logout

## Architectural Constraints

- **Threading:** Single-threaded per request (standard servlet model). EntityManager, SecurityContext are thread-local.
- **Global state:** PasswordEncoder bean singleton, AuthenticationManager bean singleton, SecurityContextRepository bean singleton. No module-level mutable static state.
- **Circular imports:** Potential: UserService → BoardService → ColumnService → TaskService → SubtaskService → OwnershipVerifierService → UserRepository. No actual circular dependency because all use constructor/field injection with @Autowired (lazy initialization).
- **Session persistence:** All sessions stored in PostgreSQL spring_session table, not in memory. Allows horizontal scaling.
- **Transactional cascade:** Ownership verification and delete cascades must happen within same @Transactional method to ensure consistency.
- **Lazy loading risk:** JPA entities lazy-load relationships. Any access to entity.getColumn() outside transaction can throw LazyInitializationException. Services avoid this by using repository queries that fetch complete graphs.

## Anti-Patterns

### Circular Dependency in Service Construction

**What happens:** UserService autowires BoardService, BoardService autowires ColumnService, etc. If unfinalized, could create circular bean dependency at startup.

**Why it's wrong:** Spring cannot resolve bean construction order; application fails to start with BeanCurrentlyInCreationException.

**Do this instead:** All services use @Autowired field injection (not constructor injection) with lazy initialization. Spring resolves this via proxy injection. Real circular logic (e.g., User.addBoard() → Board.setUser()) is avoided by having UserService call BoardService instead of bidirectional method calls. See `src/main/java/com/vrudenko/kanban_board/service/UserService.java` line 76-81 and `src/main/java/com/vrudenko/kanban_board/service/BoardService.java` line 79-86.

### LazyInitializationException Risk in DTO Mapping

**What happens:** Service loads an entity, returns it, later code tries to access lazy-loaded relationship (e.g., task.getColumn()) outside of transaction context.

**Why it's wrong:** Hibernate session is closed after transaction ends. Accessing unmaterialized relationship throws exception.

**Do this instead:** Repositories use JOIN FETCH or @EntityGraph to eagerly load required relationships. DTOs only reference fields that are guaranteed to be loaded. For example, TaskResponseDTO includes taskId but doesn't reference the full Column object. See `src/main/java/com/vrudenko/kanban_board/dto/task_dto/TaskResponseDTO.java` — it's a flat structure, not a nested ColumnResponseDTO.

### Reusing findById() Without Re-verification

**What happens:** Code calls service.findById() to get entity, then assumes ownership is verified because find() succeeded.

**Why it's wrong:** find() does not verify ownership — it only checks entity exists. A malicious user could construct URL with another user's boardId and modify it.

**Do this instead:** Call OwnershipVerifierService.verifyOwnershipOfXXX() instead of raw repository.findById(). Example: TaskService.findById() (line 58-62) calls ownershipVerifierService.verifyOwnershipOfTask(), not taskRepository.findById().

### Forgetting EntityManager.clear() After Batch Delete

**What happens:** Service deletes 100 tasks via taskRepository.deleteAllByIdInBatch(). Later in same transaction, code queries tasks and expects fresh data from DB. But Hibernate session still has old entities in persistence context.

**Why it's wrong:** Hibernate returns stale cached objects instead of re-querying. Data inconsistency.

**Do this instead:** Call entityManager.flush() to sync pending operations to DB, then entityManager.clear() to evict all objects from persistence context. Example: TaskService.deleteAllByColumn() (lines 107-119) does this correctly after batch deletes.

## Error Handling

**Strategy:** Centralized exception handling via @ControllerAdvice with per-exception-type @ExceptionHandler methods

**Patterns:**
- Custom exceptions (AppEntityNotFoundException, AppAccessDeniedException) thrown from service layer
- GlobalExceptionHandler intercepts and maps to HTTP status codes:
  - EntityNotFoundException → 404 Not Found
  - AppEntityNotFoundException → 404 Not Found
  - AppAccessDeniedException → 401 Unauthorized (line 54-56)
  - AccessDeniedException → 401 Unauthorized (line 48-51)
  - BadCredentialsException → 401 Unauthorized (line 87-90)
  - IllegalArgumentException → 400 Bad Request
  - OptimisticLockingFailureException → 423 Locked
  - MethodArgumentNotValidException → 400 Bad Request with field-level error map (line 92-105)
  - General Exception → 500 Internal Server Error
- Controllers do not catch exceptions — they propagate to handler
- Vavr Try monad used in AuthenticationController (line 92-105) for functional error handling

## Cross-Cutting Concerns

**Logging:** No explicit logging framework configured. Uses Spring/SLF4J defaults if needed. GlobalExceptionHandler could log exceptions before returning (currently doesn't).

**Validation:** Jakarta Validation (jakarta.validation package) with custom annotations:
- @Valid on controller parameters triggers validation
- Custom validators in dto/annotation/ package validate domain-specific rules (@AppEmail, @Password, @BoardName, etc.)
- Validation errors return 400 with field-error map via MethodArgumentNotValidException handler

**Authentication:** Session-based (JSESSIONID cookie) stored in PostgreSQL. Every request after login automatically restores SecurityContext via HttpSessionSecurityContextRepository. Controllers extract current user ID via @CurrentUserId resolver.

**Authorization:** Method-level via @PreAuthorize("isAuthenticated()") on controllers. Resource-level via OwnershipVerifierService in service methods.

---

*Architecture analysis: 2026-07-31*
