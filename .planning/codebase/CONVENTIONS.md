# Coding Conventions

**Analysis Date:** 2026-07-31

## Naming Patterns

**Files:**
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

**Functions/Methods:**
- camelCase: `findById()`, `verifyOwnershipOfBoard()`, `deleteAllByColumnId()`
- Prefix conventions:
  - Getters: `get{PropertyName}()` or just property name in Lombok-generated accessors
  - Finders: `findById()`, `findAllByColumnId()`, `findAllByUserId()`
  - Counters: `countByColumnId()`, `getTaskCountByColumnId()`
  - Setters: `set{PropertyName}()` (Lombok-generated)
  - Deleters: `deleteById()`, `deleteAllByColumnId()`, `deleteAll()`
  - Adders/Creators: `addColumnByBoardId()`, `addSubtaskByTaskId()`, `addBoardByUserId()`

**Variables:**
- camelCase for all local variables: `userId`, `boardId`, `taskTitle`, `columnName`
- Constants in UPPER_SNAKE_CASE: `MIN_BOARD_NAME_LENGTH`, `MAX_TASK_DESCRIPTION_LENGTH`, `MOCK_COLUMNS_AMOUNT`
- Collection descriptive names: `mockTasks`, `mockColumns`, `listOfMessages`
- In tests, variable names correspond to what they represent: `userId`, `boardId`, `columnId`, `taskId`

**Types:**
- DTOs: `{ObjectName}ResponseDTO`, `Save{ObjectName}RequestDTO`, `Update{ObjectName}RequestDTO`
- Custom exceptions: `App{ExceptionType}` (extends JPA/Spring exceptions)
- Interfaces for contract definition: Named without "I" prefix (Java convention)

## Code Style

**Formatting:**
- Google Java Format with AOSP style (`googleJavaFormat().aosp()`)
- Managed by Spotless plugin (build.gradle)
- Target: `src/**/*.java`
- Features: Format annotations, import order, remove unused imports automatically

**Linting:**
- Spotless with GoogleJavaFormat enforces consistent formatting
- No separate linting tool configured; formatting rules are the linting standard

## Import Organization

**Order:**
1. Standard library imports (`java.*`)
2. External library imports (`org.springframework`, `com.google`, `io.vavr`, etc.)
3. Project-specific imports (`com.vrudenko.kanban_board.*`)

**Path Aliases:**
- No path aliases configured; full package paths used throughout
- Package structure mirrors domain: `com.vrudenko.kanban_board.{service,controller,entity,dto,repository}`

## Error Handling

**Patterns:**
- Custom exception hierarchy: `AppAccessDeniedException` (extends `AccessDeniedException`), `AppEntityNotFoundException` (extends `EntityNotFoundException`)
- Constructor-based messaging: `new AppAccessDeniedException("Board")` produces message "You do not have access to that board"
- Exceptions thrown from service layer for domain logic violations (ownership verification, entity not found)
- Global exception handler (`GlobalExceptionHandler.java`) maps exceptions to HTTP status codes:
  - `EntityNotFoundException` → 404 NOT_FOUND
  - `AppEntityNotFoundException` → 404 NOT_FOUND
  - `AccessDeniedException` → 401 UNAUTHORIZED
  - `AppAccessDeniedException` → 401 UNAUTHORIZED
  - `MethodArgumentNotValidException` → 400 BAD_REQUEST with field-level error map
  - `HandlerMethodValidationException` → 400 BAD_REQUEST
  - `BadCredentialsException` → 401 UNAUTHORIZED
  - `OptimisticLockingFailureException` → 423 LOCKED
  - Generic `Exception` → 500 INTERNAL_SERVER_ERROR

**Validation:**
- Jakarta Validation (`jakarta.validation.constraints`) used for DTO field validation
- Custom validation annotations: `@BoardName`, `@TaskTitle`, `@SubtaskTitle`, `@DisplayName`, `@AppEmail`, `@Password`, `@Description`
- Constants defined in `ValidationConstants.java` for all length constraints (MIN/MAX pairs)
- Validation error messages defined as constants and reused in annotations

## Logging

**Framework:** No explicit logging framework configured; relies on Spring Boot's default logging (SLF4J with Logback)

**Patterns:**
- Logging not extensively used in current codebase; focus is on exception handling and method-level documentation
- Exception messages are informative (e.g., "You do not have access to that board", "User was not found")
- Performance-related comments in code (see TaskService deleteAllByColumn method) indicate implicit logging/monitoring concerns

## Comments

**When to Comment:**
- Complex domain logic: Ownership verification chains (see `TaskService.deleteAllByColumn()`)
- Performance implications: N+1 query patterns and batch delete optimizations documented
- Implementation constraints: Hibernate persistence context behavior, JPQL bulk delete side effects
- Design decisions affecting callers: Method visibility, batch vs. single-operation variants

**JavaDoc/JSDoc:**
- Used for public API methods with non-obvious behavior
- Extensive multi-line JavaDoc on complex methods: `TaskService.deleteAllByColumn()` documents persistence context flushing strategy
- Mapper interfaces documented to explain MapStruct configuration
- DTO classes documented to explain relationships and constraints
- All custom validation annotations include JavaDoc explaining requirements
- Link references used: `{@link ClassName#methodName}` to cross-reference related methods
- HTML tags used in JavaDoc: `<p>` for paragraphs, `{@code variableName}` for inline code

## Function Design

**Size:** Methods typically 5-40 lines; complex orchestration methods 15-25 lines

**Parameters:**
- Order: `userId` or security context first, then resource identifiers, then request payloads
- DTOs always prefixed with `@Valid` for validation
- Path variables annotated with `@NotBlank` when expecting non-empty strings
- No builder patterns in method signatures; used only for object construction

**Return Values:**
- Service methods return DTOs (response objects): `TaskResponseDTO`, `BoardResponseDTO`
- Void for delete operations
- Generic `ResponseEntity<T>` wrapping in controllers
- Collections returned as Lists: `List<TaskResponseDTO>`
- Pairs used for multi-value returns: `Pair<UserEntity, BoardEntity>` from ownership verification

## Module Design

**Exports:**
- Services: `@Service` stereotype beans, injected via `@Autowired`
- Controllers: `@RestController` endpoints, public methods return `ResponseEntity<T>`
- Repositories: Spring Data JPA interfaces, auto-implemented by framework
- Mappers: MapStruct interfaces, implementation auto-generated

**Barrel Files:**
- No barrel (wildcard export) files; each class explicitly imported
- Constants consolidated in {Domain}Constants.java files (ValidationConstants, ApiPaths)
- API routes centralized in `ApiPaths.java`

**Dependency Injection:**
- Field injection via `@Autowired`: `@Autowired private TaskService taskService;`
- No constructor injection used in current codebase
- All dependencies explicitly declared as private fields at class level

## Annotations

**Spring/Jakarta:**
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

**Lombok:**
- `@Getter`, `@Setter` - Auto-generate accessors and mutators
- `@Builder` - Fluent builder pattern
- `@NoArgsConstructor`, `@AllArgsConstructor` - Constructor generation
- `@EqualsAndHashCode` - Equals and hashCode implementation
- `@JsonIgnore` - Exclude fields from JSON serialization

**MapStruct:**
- `@Mapper` - Marks interface as MapStruct mapper
- `componentModel = MappingConstants.ComponentModel.SPRING` - Integrates as Spring bean
- `unmappedTargetPolicy = ReportingPolicy.IGNORE` - Silently ignore unmapped fields

## Transactionality

**Pattern:** `@Transactional` applied to service methods that modify state or depend on consistent reads

**Usage:**
- Methods that read and then modify: `updateById()`, `findAllByColumnId()`
- Batch operations: `deleteAllByColumnId()`, `deleteAllByTaskIds()`
- Cascade operations: `deleteById()` on parent deletes all children
- Only methods that perform updates explicitly marked; pure reads without dependencies often unmarked

**Side Effects:**
- Batch JPQL deletes (`deleteAllByIdInBatch()`) bypass persistence context
- `entityManager.flush()` and `entityManager.clear()` used to sync session state after bulk operations
- Transactional context prevents stale data in same transaction (important for account deletion scenarios)

---

*Convention analysis: 2026-07-31*
