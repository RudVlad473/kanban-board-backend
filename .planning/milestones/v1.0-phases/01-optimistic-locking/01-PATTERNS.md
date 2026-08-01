# Phase 1: Optimistic Locking - Pattern Map

**Mapped:** 2026-08-01
**Files analyzed:** 13
**Analogs found:** 13 / 13

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|--------------------|------|-----------|-----------------|----------------|
| `entity/TaskEntity.java` | model | CRUD | `entity/ColumnEntity.java` (sibling entity, already has `@EqualsAndHashCode`) | exact |
| `entity/ColumnEntity.java` | model | CRUD | `entity/TaskEntity.java` (sibling entity, commented-out `@EqualsAndHashCode`) | exact |
| `handler/GlobalExceptionHandler.java` | middleware (exception handler) | request-response | itself — existing `handleOptimisticLockingFailure` (lines 80-84) | exact (one-line fix) |
| `dto/task_dto/UpdateTaskRequestDTO.java` | model (DTO) | request-response | itself — add `version` field | exact |
| `dto/column_dto/UpdateColumnRequestDTO.java` (new) | model (DTO) | request-response | `dto/task_dto/UpdateTaskRequestDTO.java` + `dto/board_dto/UpdateBoardRequestDTO.java` | exact |
| `dto/task_dto/TaskResponseDTO.java` | model (DTO) | request-response | itself — add `version` field | exact |
| `dto/column_dto/ColumnResponseDTO.java` | model (DTO) | request-response | itself — add `version` field | exact |
| `controller/ColumnController.java` (add `updateById`) | controller | request-response | `controller/TaskController.java` `updateById` (lines 45-51) | exact |
| `service/ColumnService.java` (add `updateById`) | service | CRUD | `service/TaskService.java` `updateById` (lines 64-78) | exact |
| `mapper/ColumnMapper.java` (wire new DTO) | utility (mapper) | transform | `mapper/TaskMapper.java` | exact |
| Task/Column update E2E-style tests | test | request-response | `controller/TaskControllerTest.java` `UpdateById` nested class (lines 107-240) | exact |
| Optimistic-lock conflict test (new) | test | request-response | `controller/TaskControllerTest.java` `UpdateById` + `GlobalExceptionHandler` conventions | role-match (new scenario, existing harness) |
| DDL script (new, non-Java) | config | batch | none in-repo (first manual DDL deliverable) | no analog |

## Pattern Assignments

### `entity/TaskEntity.java` and `entity/ColumnEntity.java` (model, CRUD)

**Analogs:** each other (sibling entities extending `BaseEntity`)

**Current state — `TaskEntity.java`** (`src/main/java/com/vrudenko/kanban_board/entity/TaskEntity.java`, lines 1-34):
```java
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// @EqualsAndHashCode(callSuper = false)
@Table(name = "tasks")
public class TaskEntity extends BaseEntity implements BaseTask {
    @ManyToOne
    @JoinColumn(name = "column_id")
    private ColumnEntity column;

    @OneToMany(mappedBy = "task")
    private List<SubtaskEntity> subtasks;

    @Column(nullable = false, length = ValidationConstants.MAX_TASK_TITLE_LENGTH)
    private String title;

    @Column(length = ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH)
    private String description;
}
```
`@EqualsAndHashCode` is commented out on `TaskEntity` — Lombok falls back to `@Getter`/`@Setter` only, no generated `equals`/`hashCode` at all (uses `Object` identity). This means adding `@Version` here has NO equals/hashCode landmine today — but confirm this is intentional/stays that way; do not un-comment it without excluding `version` and any collection fields.

**Current state — `ColumnEntity.java`** (`src/main/java/com/vrudenko/kanban_board/entity/ColumnEntity.java`, lines 1-33):
```java
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Table(name = "columns")
public class ColumnEntity extends BaseEntity implements BaseColumn {
    @Column(nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "board_id")
    private BoardEntity board;

    @OneToMany(mappedBy = "column")
    private List<TaskEntity> task;
}
```
`ColumnEntity` DOES use `@Data` (generates equals/hashCode over all fields) + `@EqualsAndHashCode(callSuper = false)`. Adding `@Version private Long version;` here WILL land in equals/hashCode unless explicitly excluded — per CONTEXT.md D-decision this is Claude's discretion but MUST NOT let `version` participate. Use field-level `@EqualsAndHashCode.Exclude` on the new `version` field (Lombok supports mixing `@Data` + per-field `@EqualsAndHashCode.Exclude`), e.g.:
```java
@Version
@EqualsAndHashCode.Exclude
private Long version;
```

**Pattern to apply to both entities:**
```java
import jakarta.persistence.Version;
...
@Version
@Column(nullable = false)
private Long version;
```
Place directly on `TaskEntity`/`ColumnEntity` (not `BaseEntity`) per CONTEXT.md — `BaseEntity.java` (`src/main/java/com/vrudenko/kanban_board/entity/BaseEntity.java`) only carries the ULID `@Id` and must stay untouched.

---

### `handler/GlobalExceptionHandler.java` (middleware, request-response)

**Analog:** itself — one-line status code fix

**Current (buggy) state** (lines 79-84):
```java
// handles errors from db
@ExceptionHandler(OptimisticLockingFailureException.class)
public ResponseEntity<String> handleOptimisticLockingFailure(
        OptimisticLockingFailureException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatus.LOCKED);
}
```
`HttpStatus.LOCKED` = 423. Fix per D-05: change to `HttpStatus.CONFLICT` (409). Keep the `ResponseEntity<String>` shape — matches every other handler in this class (see `handleEntityNotFound`, `handleAppEntityNotFound`, lines 28-36) which all return plain string bodies, no structured error DTO. Per D-05, the message text can stay `ex.getMessage()` (Hibernate's default message) or be replaced with a custom string like `"Task was modified by another request, please refetch."` — CONTEXT.md leaves exact wording to discretion, just must be a plain string with no embedded id/type.

---

### `dto/task_dto/UpdateTaskRequestDTO.java` (model DTO, request-response)

**Current state** (`src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java`, lines 1-31):
```java
@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateTaskRequestDTO implements BaseTask {
    @TaskTitle String title;
    @Description String description;

    @AssertTrue(message = "Either 'title' or 'description' (or both) must be provided.")
    private boolean atLeastOneFieldPopulated() {
        var isTitlePresent = Optional.ofNullable(getTitle()).isPresent();
        var isDescriptionPresent = Optional.ofNullable(getDescription()).isPresent();
        return isTitlePresent || isDescriptionPresent;
    }
}
```
Add a required `version` field (D-02: client-supplied, compared server-side before applying). Since this is a partial-update DTO gated by `@AssertTrue` on "at least one of title/description," version should likely be `@NotNull` (always required, unlike title/description which are optional-but-one-required) — add e.g. `@NotNull private Long version;` outside the `atLeastOneFieldPopulated` check. Follow the same `@Getter @Setter @Builder @EqualsAndHashCode @JsonInclude(NON_NULL)` annotation stack.

---

### `dto/column_dto/UpdateColumnRequestDTO.java` (new file, model DTO, request-response)

**Analogs:** `dto/task_dto/UpdateTaskRequestDTO.java` (partial-update shape) + `dto/board_dto/UpdateBoardRequestDTO.java` (single-field update, simpler — Board also has just a `name` field) + `dto/column_dto/SaveColumnRequestDTO.java` (validation annotations for `name` on Column).

**`SaveColumnRequestDTO.java` validation pattern to reuse** (lines 1-22):
```java
@Getter
@Setter
@EqualsAndHashCode
@Builder
public class SaveColumnRequestDTO implements BaseColumn {
    @NotBlank(message = "Column name cannot be empty") @Size(
            min = ValidationConstants.MIN_COLUMN_NAME_LENGTH,
            max = ValidationConstants.MAX_COLUMN_NAME_LENGTH,
            message = ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE)
    private String name;
}
```
Since `ColumnEntity`'s only mutable field is `name` (per D-04), `UpdateColumnRequestDTO` needs `name` (reuse the same `@NotBlank`/`@Size` constraints as `SaveColumnRequestDTO`, or the project's `@BoardName`/custom annotation equivalent if one exists for column names — check `dto/annotation/` package; currently no `@ColumnName` custom annotation exists, so plain `@NotBlank`/`@Size` is the established convention here) plus a required `version` field:
```java
package com.vrudenko.kanban_board.dto.column_dto;

@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateColumnRequestDTO implements BaseColumn {
    @NotBlank(message = "Column name cannot be empty") @Size(
            min = ValidationConstants.MIN_COLUMN_NAME_LENGTH,
            max = ValidationConstants.MAX_COLUMN_NAME_LENGTH,
            message = ValidationConstants.NAME_LENGTH_VALIDATION_MESSAGE)
    private String name;

    @NotNull private Long version;
}
```
Since Column has only one mutable field (no "at least one populated" ambiguity like Task), both `name` and `version` can be plain `@NotBlank`/`@NotNull` required fields — simpler than `UpdateTaskRequestDTO`'s optional-fields pattern.

---

### `dto/task_dto/TaskResponseDTO.java` / `dto/column_dto/ColumnResponseDTO.java` (model DTO, request-response)

**Current state — `TaskResponseDTO.java`** (lines 1-18):
```java
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class TaskResponseDTO implements BaseId, BaseTask {
    private String id;
    private String title;
    private String description;
}
```
**Current state — `ColumnResponseDTO.java`** (lines 1-17):
```java
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class ColumnResponseDTO implements BaseId, BaseColumn {
    private String id;
    private String name;
}
```
Per D-01, add `private Long version;` to both — surfaced on ALL response paths (list, single GET, create, update) since these DTOs are shared across all those service methods already (see `TaskMapper.toTaskResponseDTO`/`toTaskResponseDTOList`, `ColumnMapper.toColumnResponseDTO`/`toColumnResponseDTOList`). No per-endpoint DTO variants exist, so one field addition covers everything automatically via MapStruct's implicit field-name mapping (see Mapper section below) — no mapper method signature changes needed as long as `version` exists on both the entity and the DTO with matching name/type.

---

### `controller/ColumnController.java` — new `updateById` (controller, request-response)

**Analog:** `controller/TaskController.java` `updateById` (`src/main/java/com/vrudenko/kanban_board/controller/TaskController.java`, lines 45-51):
```java
@PutMapping(ApiPaths.TASK_ID)
public ResponseEntity<TaskResponseDTO> updateById(
        @CurrentUserId String userId,
        @PathVariable @NotBlank String taskId,
        @Valid @RequestBody UpdateTaskRequestDTO dto) {
    return ResponseEntity.ok(taskService.updateById(userId, taskId, dto));
}
```
**Secondary analog:** `controller/BoardController.java` `updateById` (lines 48-54) — nearly identical shape, confirms convention is uniform across Board/Task.

**Current `ColumnController.java` state** (full file, `src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java`, lines 1-46) — `@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS)`, has `GetMapping` (findAllByBoardId) and `PostMapping(ApiPaths.COLUMN_ID)` (addTaskByColumnId) only — no PUT/update endpoint exists yet. Add:
```java
@PutMapping(ApiPaths.COLUMN_ID)
public ResponseEntity<ColumnResponseDTO> updateById(
        @CurrentUserId String userId,
        @PathVariable @NotBlank String columnId,
        @Valid @RequestBody UpdateColumnRequestDTO dto) {
    return ResponseEntity.ok(columnService.updateById(userId, columnId, dto));
}
```
Note: `ColumnController` is `@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS)` (nested under board), so the resulting route is `PUT /boards/{boardId}/columns/{columnId}` as specified in D-04 — matches `ApiPaths.COLUMN_ID = "/{columnId}"` convention already used for `addTaskByColumnId`. Import additions needed: `PutMapping`, `UpdateColumnRequestDTO`.

---

### `service/ColumnService.java` — new `updateById` (service, CRUD)

**Analog:** `service/TaskService.java` `updateById` (`src/main/java/com/vrudenko/kanban_board/service/TaskService.java`, lines 64-78):
```java
@Transactional
public TaskResponseDTO updateById(String userId, String taskId, UpdateTaskRequestDTO dto) {
    var task = findById(userId, taskId);

    if (Optional.ofNullable(dto.getTitle()).isPresent()) {
        task.setTitle(dto.getTitle());
    }
    if (Optional.ofNullable(dto.getDescription()).isPresent()) {
        task.setDescription(dto.getDescription());
    }

    taskRepository.save(task);

    return taskMapper.toTaskResponseDTO(task);
}
```
**Secondary analog:** `service/BoardService.java` `updateById` (lines 65-77) — simpler single-field version (Board only has `name`), closer shape to what Column needs since Column also only has one mutable field (`name`):
```java
@Transactional
public BoardResponseDTO updateById(
        String userId, String boardId, UpdateBoardRequestDTO boardDTO) {
    var boardToUpdate = findById(userId, boardId);
    boardToUpdate.setName(boardDTO.getName());
    var savedBoard = boardRepository.save(boardToUpdate);
    return boardMapper.toResponseDTO(savedBoard);
}
```

**Critical addition not present in either analog — the D-02 version check.** Neither `TaskService.updateById` nor `BoardService.updateById` currently do an explicit version comparison; they rely purely on `@Version`'s automatic dirty-check-time exception, which (per CONTEXT.md D-02) does NOT catch the "two clients read-then-write across separate requests" scenario in this codebase's load-then-save-within-one-transaction flow. The new `ColumnService.updateById` (and the retrofit to `TaskService.updateById`) must explicitly compare `dto.getVersion()` against `column.getVersion()` BEFORE mutating, and throw/let `OptimisticLockingFailureException` propagate (or throw it directly) if they differ, e.g.:
```java
@Transactional
public ColumnResponseDTO updateById(String userId, String columnId, UpdateColumnRequestDTO dto) {
    var column = findById(userId, columnId);

    if (!column.getVersion().equals(dto.getVersion())) {
        throw new OptimisticLockingFailureException(
                "Column was modified by another request, please refetch.");
    }

    column.setName(dto.getName());

    columnRepository.save(column);

    return columnMapper.toColumnResponseDTO(column);
}
```
This same explicit-check pattern must be retrofitted into `TaskService.updateById` (lines 64-78) since `UpdateTaskRequestDTO` also gains a required `version` field per D-02. Existing `findById(userId, columnId)` (`ColumnService.java` lines 56-61) already does ownership verification via `OwnershipVerifierService` — reuse as-is, no change needed there.

Import needed: `org.springframework.dao.OptimisticLockingFailureException` (already used in `GlobalExceptionHandler`).

---

### `mapper/ColumnMapper.java` (utility/mapper, transform)

**Current state** (`src/main/java/com/vrudenko/kanban_board/mapper/ColumnMapper.java`, lines 1-24):
```java
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ColumnMapper {
    ColumnEntity fromSaveColumnRequestDTO(SaveColumnRequestDTO dto);
    SaveColumnRequestDTO toSaveColumnRequestDTO(ColumnEntity entity);
    List<SaveColumnRequestDTO> toSaveColumnRequestDTOList(List<ColumnEntity> entities);
    ColumnResponseDTO toColumnResponseDTO(ColumnEntity entity);
    List<ColumnResponseDTO> toColumnResponseDTOList(List<ColumnEntity> entities);
}
```
Analog: `mapper/TaskMapper.java` (identical structure, lines 1-20). No new mapper method is strictly required for `updateById` — the service sets fields manually (see `ColumnService`/`TaskService` update pattern above), not via a MapStruct "update" method. `unmappedTargetPolicy = ReportingPolicy.IGNORE` means adding `version` to `ColumnResponseDTO`/`ColumnEntity` will auto-map by field name with zero mapper interface changes needed — MapStruct matches `version` -> `version` implicitly.

---

## Shared Patterns

### Ownership verification (service layer)
**Source:** `service/ColumnService.findById` (lines 56-61) / `service/TaskService.findById` (lines 58-62)
**Apply to:** `ColumnService.updateById` (new), retrofit into `TaskService.updateById`
```java
@Transactional
public ColumnEntity findById(String userId, String columnId) {
    var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);
    return pair.getSecond();
}
```
Always call this before mutating — do not bypass with direct repository lookups.

### `@Autowired` field injection (not constructor)
**Source:** every existing service (`TaskService`, `ColumnService`, `BoardService`)
**Apply to:** no new services in this phase, but any modified service code must keep this convention (explicitly called out in CONTEXT.md as deliberate, to avoid circular bean ordering issues).

### 409 plain-string error responses
**Source:** `handler/GlobalExceptionHandler.java` — every handler in the class returns `ResponseEntity<String>` (see `handleEntityNotFound`, `handleAppEntityNotFound`, `handleIllegalArgument`, all lines 28-46)
**Apply to:** the fixed `handleOptimisticLockingFailure` handler — do not introduce a structured error DTO, stay consistent with the rest of the class.

### Route path constants
**Source:** `constant/ApiPaths.java` (lines 1-25) — `BOARD_ID`, `COLUMN_ID`, `TASK_ID`, `SUBTASK_ID` all already defined as `"/{xId}"` path-variable constants, reused compositionally in `@RequestMapping`/`@PutMapping` across all controllers.
**Apply to:** new `ColumnController.updateById` — reuse `ApiPaths.COLUMN_ID`, no new constant needed.

### Test harness / mock fixtures
**Source:** `AbstractAppTest` (referenced by all controller/service tests via `mockPopulatedBoard`, `mockPopulatedColumn`, `mockPopulatedTask`, `getOwningUser()`, `dataFactory`) and `AbstractAppE2ETest` (`src/test/java/com/vrudenko/kanban_board/AbstractAppE2ETest.java`, lines 1-55) which adds RestAssured + cookie-based signin (`signin()` method, lines 38-54) for true E2E (RANDOM_PORT) tests.
**Apply to:** the new concurrent-update E2E test — note existing `e2e/board/BoardE2ETest.java` (lines 1-8) is currently an EMPTY stub class (`@SpringBootTest(webEnvironment = RANDOM_PORT) public class BoardE2ETest extends AbstractAppE2ETest {}`) with no test methods yet — there is no populated E2E example to copy method bodies from. Use `TaskControllerTest.UpdateById` (MockMvc-based, `@SpringBootTest` + `@AutoConfigureMockMvc`, NOT true E2E) as the structural/assertion-style analog instead, adapted to true E2E via `AbstractAppE2ETest.signin()` + RestAssured `given()/when()/then()` if a true multi-request E2E test is required, or keep it as a MockMvc test in `ColumnControllerTest`/`TaskControllerTest` style if a single-process test suffices for asserting 409 on stale version.

**`TaskControllerTest.UpdateById` structure to copy** (lines 107-217):
```java
@Nested
class UpdateById {
    @Test
    void testWithAuthenticatedUser_shouldUpdateTitleOnly_whenTaskExists() throws Exception {
        var userId = getOwningUser().getId();
        var taskId = mockPopulatedTask.getId();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var url = getTaskPrefix(boardId, columnId) + "/" + taskId;
        var updateDto = UpdateTaskRequestDTO.builder().title("Updated Task Name").build();
        // ... mockMvc.perform(put(url).with(user(userId))...).andExpect(status().isOk())
    }

    @Test
    void testWithAuthenticatedUser_shouldReturnNotFound_whenTaskDoesNotExist() throws Exception {
        // ... status().isNotFound()
    }

    @Test
    void testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid() throws Exception {
        // ... status().isBadRequest()
    }
}
```
**New test to add (no existing analog body, but same harness/style):** a `shouldReturnConflict_whenVersionIsStale` test — build an `UpdateTaskRequestDTO`/`UpdateColumnRequestDTO` with a `version` value that does not match the current DB row (e.g. `version(0L)` when the mock entity is actually at version `1L` after a prior update, or simulate two sequential PUTs where the first succeeds and the second reuses the stale pre-update DTO), assert `status().isConflict()` (409).

Existing `UpdateTaskRequestDTO` request DTOs in `TaskControllerTest` (e.g. line 117, 144, 167-175) will all need a `.version(...)` builder call added once `version` becomes a required field — this is a required update to ALL existing `UpdateTaskRequestDTO.builder()...build()` call sites in `TaskControllerTest.java`, not just new tests, since the `@AssertTrue`/`@NotNull` validation will otherwise fail with 400.

### Query-count regression test convention (if applicable)
**Source:** `OwnershipVerifierServiceTest.QueryCountTest` (`src/test/java/com/vrudenko/kanban_board/service/OwnershipVerifierServiceTest.java`, lines 27-45), uses a `countQueries(() -> ...)` helper (defined in `AbstractAppTest`, not re-read here) wrapping Hibernate `Statistics.getPrepareStatementCount()`.
```java
@Nested
class QueryCountTest {
    @Test
    void verifyOwnershipOfSubtask_issuesOneQuery() {
        var userId = getOwningUser().getId();
        var subtaskId = mockSubtasks.getFirst().getId();
        var queryCount = countQueries(() -> ownershipVerifierService.verifyOwnershipOfSubtask(userId, subtaskId));
        Assertions.assertThat(queryCount).isEqualTo(1);
    }
}
```
**Apply to:** optional — CONTEXT.md notes this phase's tests are primarily HTTP-status assertions, not query counts, so this pattern is lower priority; only use it if the planner decides to regression-test that the new explicit version check in `updateById` doesn't introduce an extra query beyond the existing `findById` + `save`.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| DDL script (`ALTER TABLE tasks/columns ADD COLUMN version bigint NOT NULL DEFAULT 0`) | config | batch | First manual DDL deliverable in this codebase — no prior SQL script exists in `docs/plans/backend-modernization/`; `ddl-auto` is unset (confirmed by CONTEXT.md D-06/ARCHITECTURE.md) so no Hibernate-generated DDL to reference either. Per CONTEXT.md Claude's Discretion, place under `docs/plans/backend-modernization/` following that directory's existing doc-file naming/structure. |
| True E2E concurrent-update test (two-actor stale-version scenario) | test | request-response | `e2e/board/BoardE2ETest.java` is an empty stub with no test bodies — no populated true-E2E (RANDOM_PORT + RestAssured) example exists anywhere in the test suite yet. Nearest usable structural analog is `AbstractAppE2ETest.signin()` (cookie-based auth) + `TaskControllerTest.UpdateById`'s MockMvc assertion style, combined by the planner. |

## Metadata

**Analog search scope:** `src/main/java/com/vrudenko/kanban_board/{entity,dto,controller,service,mapper,handler,constant}`, `src/test/java/com/vrudenko/kanban_board/{controller,service,e2e}`
**Files scanned:** ~25 main + ~9 test files (directory listing), 13 read in full for pattern extraction
**Pattern extraction date:** 2026-08-01
