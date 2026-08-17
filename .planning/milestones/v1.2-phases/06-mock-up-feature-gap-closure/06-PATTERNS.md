# Phase 6: Mock-up Feature Gap Closure - Pattern Map

**Mapped:** 2026-08-08
**Files analyzed:** 24 (new + modified, GAP-01..GAP-07)
**Analogs found:** 22 / 24

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `controller/BoardController.java` (+`POST /boards`) | controller | request-response | itself — `addColumnByBoardId` in same file | exact (modify existing) |
| `controller/ColumnController.java` (+`DELETE /{columnId}`) | controller | request-response | `BoardController.deleteById` | exact |
| `controller/ColumnController.java` (+`PATCH .../reorder`) | controller | request-response | `TaskMoveController.moveToColumn` | exact |
| `controller/TaskMoveController.java` (extend DTO, no new method) | controller | request-response | itself | exact (modify existing) |
| `controller/UserController.java` (NEW) | controller | request-response | `BoardController.java` (class shape) | role-match |
| `controller/BoardFullController` or `BoardController.java` (+`GET /full`) | controller | request-response | `BoardController.findAllByUserId` | exact |
| `service/ColumnService.java` (`deleteById`) | service | CRUD (cascade) | `TaskService.deleteById` | exact |
| `service/ColumnService.java` (reorder logic) | service | batch/transform | `TaskService.deleteAllByColumn` (bulk JPQL pattern) | role-match |
| `service/TaskService.java` (`moveToColumn` + position) | service | CRUD | itself — `moveToColumn` | exact (modify existing) |
| `service/SubtaskService.java` (`updateById` version guard) | service | CRUD | `TaskService.updateById` | exact |
| `service/UserService.java` (`updateTheme`, uniqueness check) | service | CRUD | `UserService.addBoardByUserId` / `ColumnService.updateById` (version-guard shape reused for guard-clause style) | role-match |
| `service/BoardService.java` (`findFullById`, name-uniqueness) | service | CRUD / transform | `ColumnService.findAllByBoardId` | role-match |
| `repository/BoardRepository.java` (+`existsByUserIdAndName`) | repository | CRUD | existing derived-query methods on same interface | exact |
| `repository/TaskRepository.java`/`ColumnRepository.java` (+bulk position-shift `@Modifying @Query`) | repository | batch | `SubtaskRepository.deleteAllByTaskIdIn` | role-match |
| `entity/ColumnEntity.java` (+`position`) | model | CRUD | `entity/TaskEntity.java` (`@Version` field-declaration style) | exact |
| `entity/TaskEntity.java` (+`position`) | model | CRUD | itself | exact |
| `entity/SubtaskEntity.java` (+`@Version version`) | model | CRUD | `entity/TaskEntity.java` (`@Version` field block, lines 36-38) | exact |
| `entity/UserEntity.java` (+`theme` enum) | model | CRUD | `entity/TaskEntity.java`/`ColumnEntity.java` (`@Column` field style) | role-match |
| `entity/ThemePreference.java` (NEW enum) | model | n/a | `entity/ActivityAction.java` (existing enum in same package) | role-match |
| `dto/column_dto/ReorderColumnRequestDTO.java` (NEW) | model (DTO) | request-response | `dto/task_dto/MoveTaskRequestDTO.java` | exact |
| `dto/user_dto/UpdateThemeRequestDTO.java` (NEW) | model (DTO) | request-response | `dto/task_dto/MoveTaskRequestDTO.java` (Builder/Getter/Setter/EqualsAndHashCode shape) | role-match |
| `dto/board_dto/BoardFullResponseDTO.java` + `ColumnFullResponseDTO`/`TaskFullResponseDTO` (NEW) | model (DTO) | transform | `dto/user_dto/UserResponseDTO.java` (flat DTO shape) | role-match (new nested pattern, no exact analog) |
| `mapper/BoardFullMapper.java`/`ColumnFullMapper.java`/`TaskFullMapper.java` (NEW) | service (mapper) | transform | `mapper/ColumnMapper.java` | role-match |
| `event/ColumnDeletedEvent.java` (NEW record) | model (event) | event-driven | `event/TaskDeletedEvent.java` | exact |
| `event/ActivityEvent.java` (+permits) | model | event-driven | itself | exact (modify existing) |
| `event/avro/ActivityEventAvroMapper.java` (+switch arms) | service (mapper) | event-driven | itself — `TaskDeletedEvent`/`ColumnCreatedEvent` arms | exact (modify existing) |
| `src/main/avro/AvroColumnDeletedEvent.avsc` (NEW) | config | event-driven | `src/main/avro/AvroTaskDeletedEvent.avsc` | exact |
| `handler/GlobalExceptionHandler.java` (+duplicate-name handler) | middleware | request-response | itself — `handleOptimisticLockingFailure` arm | exact (modify existing) |
| `exception/AppDuplicateResourceException.java` (NEW) | model (exception) | n/a | `exception/AppEntityNotFoundException.java` | exact |
| `src/main/resources/db/migration/V5__*.sql` (NEW) | migration | CRUD | `V2__add_optimistic_locking_version_columns.sql` | exact |

## Pattern Assignments

### `service/ColumnService.java` — `deleteById` (GAP-02)

**Analog:** `service/TaskService.java` lines 188-208 (`deleteById`) + `service/ColumnService.java` lines 39-66 (`deleteAllByBoardId`, for cascade call shape)

**Core pattern — capture ids before delete, cascade, then publish event after commit:**
```java
@Transactional
public void deleteById(String userId, String taskId) {
    var task = findById(userId, taskId);

    var deletedTaskId = task.getId();
    var deletedColumnId = task.getColumn().getId();
    var deletedBoardId = task.getColumn().getBoard().getId();

    subtaskService.deleteAllByTaskId(userId, taskId);

    taskRepository.deleteById(task.getId());

    eventPublisher.publishEvent(
            new TaskDeletedEvent(
                    UUID.randomUUID(), userId, deletedBoardId, deletedColumnId, deletedTaskId,
                    Instant.now()));
}
```
For `ColumnService.deleteById`, replace `subtaskService.deleteAllByTaskId` with the already-existing
`taskService.deleteAllByColumn(column)` (package-private, callable from same-package `ColumnService`)
— this is exactly what `deleteAllByBoardId` (`ColumnService.java:57-66`) already does per-column in a
loop; `deleteById` is the single-column case of that same call, plus `columnRepository.deleteById(...)`
and the new `ColumnDeletedEvent` publish. Call `ownershipVerifierService.verifyOwnershipOfColumn`
first (D-07: no non-empty guard — always cascades once ownership passes), exactly mirroring
`OwnershipVerifierService.verifyOwnershipOfColumn` already used at `ColumnService.java:103`.

The literal `// TODO: implement delete logic` line to replace is `ColumnService.java:159`.

---

### `service/SubtaskService.java` — `updateById` (GAP-06)

**Analog:** `service/TaskService.java` lines 103-132 (`updateById`)

**Exact pattern to add (version-compare-then-409-then-flush):**
```java
if (!task.getVersion().equals(dto.getVersion())) {
    throw new OptimisticLockingFailureException(
            "Task was modified by another request, please refetch.");
}
if (Optional.ofNullable(dto.getTitle()).isPresent()) {
    task.setTitle(dto.getTitle());
}
taskRepository.save(task);
entityManager.flush();
return taskMapper.toTaskResponseDTO(task);
```
Current `SubtaskService.updateById` (`service/SubtaskService.java:49-65`) has neither the version
guard nor `entityManager.flush()` — this phase adds both, in the same order. `SubtaskService` has no
`@Autowired private EntityManager entityManager;` field today (verified — only
`SubtaskRepository`, `SubtaskMapper`, `OwnershipVerifierService` are injected); add it exactly as
declared in `TaskService.java:39` / `ColumnService.java:35`.

---

### `entity/SubtaskEntity.java` — `+ @Version version` (GAP-06)

**Analog:** `entity/TaskEntity.java` lines 36-38

**Exact field block to copy:**
```java
@Version
@Column(nullable = false)
private Long version;
```
Note: `TaskEntity` does NOT add `@EqualsAndHashCode.Exclude` on its version field (its
`@EqualsAndHashCode` is commented out entirely, line 20), but `ColumnEntity`'s Javadoc-adjacent
CONTEXT.md note says to replicate with `@EqualsAndHashCode.Exclude` — `SubtaskEntity` currently has
`@EqualsAndHashCode(callSuper = false)` **active** (line 20, unlike `TaskEntity`'s commented-out
one), so the new `version` field on `SubtaskEntity` MUST carry `@EqualsAndHashCode.Exclude` to avoid
including a mutable optimistic-lock field in equality — this is the one place `SubtaskEntity` diverges
from a literal copy of `TaskEntity`'s block, confirmed by CONTEXT.md's Reusable Assets note (
"`ColumnEntity.java:34-37` ... with `@EqualsAndHashCode.Exclude` on `ColumnEntity`'s").

---

### `controller/ColumnController.java` — `DELETE /{columnId}` (GAP-02)

**Analog:** `controller/BoardController.java` lines 40-46 (`deleteById`)

```java
@DeleteMapping(ApiPaths.BOARD_ID)
public ResponseEntity<Void> deleteById(
        @PathVariable @NotBlank String boardId, @CurrentUserId String userId) {
    boardService.deleteById(userId, boardId);
    return ResponseEntity.ok().build();
}
```
Copy directly onto `ColumnController`, swapping `ApiPaths.COLUMN_ID`, `columnId`, `columnService`.
Note `ColumnController`'s class-level mapping is already `ApiPaths.BOARDS + ApiPaths.BOARD_ID +
ApiPaths.COLUMNS`, so the method-level `@DeleteMapping(ApiPaths.COLUMN_ID)` composes to
`DELETE /boards/{boardId}/columns/{columnId}` exactly as CONTEXT.md's route spells it.

---

### `controller/ColumnController.java` — `PATCH .../{columnId}/reorder` (GAP-03) and `TaskMoveController` extension (GAP-03/D-04)

**Analog:** `controller/TaskMoveController.java` (whole file, 27 lines)

```java
@RestController
@RequestMapping(ApiPaths.TASKS)
@PreAuthorize("isAuthenticated()")
class TaskMoveController {
    @Autowired TaskService taskService;

    @PatchMapping(ApiPaths.TASK_ID + ApiPaths.MOVE)
    public ResponseEntity<TaskResponseDTO> moveToColumn(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody MoveTaskRequestDTO dto) {
        return ResponseEntity.ok(taskService.moveToColumn(userId, taskId, dto));
    }
}
```
For GAP-03's column reorder, either add a `@PatchMapping(ApiPaths.COLUMN_ID + "/reorder")` method
directly on `ColumnController` (its class-level mapping is already board-nested — no flat-route
problem exists here the way it does for tasks, since `ColumnController` itself already lives at
`/boards/{boardId}/columns`) — this is simpler than inventing a second flat controller. Add a
`REORDER = "/reorder"` constant to `ApiPaths.java` alongside `MOVE`.

For D-04 (task move+reorder as one endpoint), extend `MoveTaskRequestDTO`
(`dto/task_dto/MoveTaskRequestDTO.java`, full file shown below) with `targetPosition`, and extend
`TaskService.moveToColumn` (`service/TaskService.java:142-180`) to also apply positional shift —
no new controller method needed, per D-04.

**`MoveTaskRequestDTO` current full shape (the exact file/import block to extend):**
```java
package com.vrudenko.kanban_board.dto.task_dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MoveTaskRequestDTO {
    @NotBlank private String targetColumnId;

    @NotNull private Long version;
}
```
Add `private Integer targetPosition;` (nullable, no `@NotNull` — per Pattern 4 in RESEARCH.md,
"omitted means append at end"). Use the same shape (`@Getter @Setter @Builder @EqualsAndHashCode
@JsonInclude(NON_NULL)`) for the new `ReorderColumnRequestDTO`:
```java
@Getter @Setter @Builder @EqualsAndHashCode @JsonInclude(JsonInclude.Include.NON_NULL)
public class ReorderColumnRequestDTO {
    @NotNull private Long version;
    @NotNull private Integer targetPosition;
}
```

---

### `controller/BoardController.java` — `POST /boards` (GAP-01)

**Analog:** `controller/BoardController.java` lines 56-62 (`addColumnByBoardId`, same file — wiring an
already-implemented service method onto a new mapping is the exact shape needed)

```java
@PostMapping(ApiPaths.BOARD_ID + ApiPaths.COLUMNS)
public ResponseEntity<ColumnResponseDTO> addColumnByBoardId(
        @CurrentUserId String userId,
        @PathVariable @NotBlank String boardId,
        @Valid @RequestBody SaveColumnRequestDTO dto) {
    return ResponseEntity.ok(boardService.addColumnByBoardId(userId, boardId, dto));
}
```
For `POST /boards`, the mapping has no path variable (top-level `@PostMapping` under the class's
`ApiPaths.BOARDS` mapping), body is `SaveBoardRequestDTO`, and it must be wired to
`userService.addBoardByUserId(userId, dto)` (already implemented, `UserService.java:69-75`) — but
`BoardController` currently only autowires `BoardService`, not `UserService`; add
`@Autowired private UserService userService;` per D-08's explicit note ("`UserService.addBoardByUserId`
gets wired onto a new `BoardController` mapping").

---

### `service/BoardService.java`/`UserService.java` — board-name uniqueness (GAP-01, D-09)

**Analog:** `repository/BoardRepository.java`'s existing derived-query convention + a new
`AppDuplicateResourceException` following `exception/AppEntityNotFoundException.java`'s shape

```java
public interface BoardRepository extends JpaRepository<BoardEntity, String> {
    List<BoardEntity> findAllByUserId(String userId);
    boolean existsByUserIdAndName(String userId, String name);   // NEW
}
```
Guard clause (CODE_STYLE.md rule 7 — `isEmpty()`-then-throw style, no `orElseThrow`):
```java
if (boardRepository.existsByUserIdAndName(user.getId(), dto.getName())) {
    throw new AppDuplicateResourceException("Board");
}
```
This resolves `UserService.java:73`'s literal `// TODO: Disallow duplicating board names for a
single user` comment. Apply the same guard in `BoardService.updateById` for the rename path
(D-09: applies to both create and rename).

---

### `handler/GlobalExceptionHandler.java` — new duplicate-resource handler (GAP-01, D-09)

**Analog:** `handler/GlobalExceptionHandler.java` lines 80-84 (`handleOptimisticLockingFailure`) —
the exact one-arm-per-exception-type shape to copy

```java
@ExceptionHandler(OptimisticLockingFailureException.class)
public ResponseEntity<String> handleOptimisticLockingFailure(
        OptimisticLockingFailureException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT);
}
```
New arm:
```java
@ExceptionHandler(AppDuplicateResourceException.class)
public ResponseEntity<String> handleAppDuplicateResource(AppDuplicateResourceException ex) {
    return new ResponseEntity<>(ex.getMessage(), HttpStatus.CONFLICT);
}
```
(409 recommended by RESEARCH.md's Code Examples section — CONTEXT.md leaves exact status to planner.)

**New exception class — analog is `exception/AppEntityNotFoundException.java`** (constructor-based
messaging, `App{ExceptionType}` naming convention, extends a Spring/JPA exception type). Need to
read `AppEntityNotFoundException.java` directly at plan time to copy its exact constructor-message
pattern (not read this session — file exists at
`src/main/java/com/vrudenko/kanban_board/exception/AppEntityNotFoundException.java`).

---

### `event/ColumnDeletedEvent.java` (NEW record, GAP-02/D-06)

**Analog:** `event/TaskDeletedEvent.java` (full file, 19 lines)

```java
package com.vrudenko.kanban_board.event;

import java.time.Instant;
import java.util.UUID;

public record TaskDeletedEvent(
        UUID eventId,
        String userId,
        String boardId,
        String columnId,
        String taskId,
        Instant timestamp)
        implements ActivityEvent {}
```
New file, one field fewer (no `taskId`):
```java
package com.vrudenko.kanban_board.event;

import java.time.Instant;
import java.util.UUID;

public record ColumnDeletedEvent(
        UUID eventId, String userId, String boardId, String columnId, Instant timestamp)
        implements ActivityEvent {}
```
Add `ColumnDeletedEvent` to `event/ActivityEvent.java`'s `permits` clause (currently
`TaskCreatedEvent, TaskMovedEvent, TaskDeletedEvent, BoardCreatedEvent, ColumnCreatedEvent` at
lines 16-20).

---

### `event/avro/ActivityEventAvroMapper.java` — new switch arms (GAP-02/D-06)

**Analog:** the file's own existing `TaskDeletedEvent`/`ColumnCreatedEvent` arms (lines 62-70 and
78-85 for `toAvro`; lines 114-121 and 125-131 for `toDomain`)

```java
// toAvro arm to model the new one on:
case TaskDeletedEvent e ->
        AvroTaskDeletedEvent.newBuilder()
                .setEventId(e.eventId())
                .setUserId(e.userId())
                .setBoardId(e.boardId())
                .setColumnId(e.columnId())
                .setTaskId(e.taskId())
                .setTimestamp(e.timestamp())
                .build();
```
New arm (drop `.setTaskId`): `AvroColumnDeletedEvent.newBuilder().setEventId(...).setUserId(...)
.setBoardId(...).setColumnId(...).setTimestamp(...).build()`. Mirror the same drop-taskId shape in
`toDomain`'s `case AvroColumnDeletedEvent r -> new ColumnDeletedEvent(...)` arm. This switch has
**no `default` arm on the `toAvro` side** (exhaustive over the sealed interface) — adding the 6th
record is a compile error here until updated, by design (see the class's own Javadoc, already read
in full above). Also add `AvroColumnDeletedEvent.getClassSchema()` to
`config/AvroSchemaRegistrar.java`'s `SCHEMAS` list (not read this session — CONTEXT.md/RESEARCH.md
cite it at `config/AvroSchemaRegistrar.java:51-57`) and `ActivityAction.COLUMN_DELETED` +
a switch arm in `activitylog/ActivityLogConsumer.deriveActionAndDetailIds` (not read this session,
cited at `activitylog/ActivityLogConsumer.java:78-107`) — planner/executor must read both directly
before implementing, per RESEARCH.md's Open Question 2.

**`.avsc` analog** — `src/main/avro/AvroTaskDeletedEvent.avsc` (not read this session; RESEARCH.md
already quotes its field list verbatim: `eventId` as `{"type":"string","logicalType":"uuid"}`,
`userId`/`boardId`/`columnId` as plain `"string"`, `timestamp` as
`{"type":"long","logicalType":"timestamp-millis"}`) — copy byte-for-byte, drop the `taskId` field.

---

### `entity/{Task,Column}Entity.java` — `+ position` (GAP-03) and `entity/UserEntity.java` — `+ theme` (GAP-05)

**Analog:** `entity/TaskEntity.java` lines 30-34 (`@Column` field declaration style, non-version
field)

```java
@Column(nullable = false, length = ValidationConstants.MAX_TASK_TITLE_LENGTH)
private String title;
```
New `position` field (`Integer`, not `String`, no length constraint):
```java
@Column(nullable = false)
private Integer position;
```
For `UserEntity.theme`, follow `UserEntity`'s own existing `@Column` style (`entity/UserEntity.java`
lines 29-32, e.g. `@Column(nullable = false, unique = true) private String email;`) plus D-12's
non-nullable-with-default requirement:
```java
@Enumerated(EnumType.STRING)
@Column(nullable = false)
private ThemePreference theme;
```
New enum file, analog `entity/ActivityAction.java` (existing plain enum in same `entity` package —
not read this session, but confirmed to exist and to already back a DB column via
`ActivityLogEntity.action` per RESEARCH.md's Open Question 2 citation):
```java
package com.vrudenko.kanban_board.entity;

public enum ThemePreference {
    LIGHT,
    DARK
}
```

---

### `src/main/resources/db/migration/V5__*.sql` (GAP-01/02/03/05/06)

**Analog:** `src/main/resources/db/migration/V2__add_optimistic_locking_version_columns.sql`
(full file, 6 lines)

```sql
-- V2__add_optimistic_locking_version_columns.sql
-- Epic 2's optimistic locking, previously applied by hand via
-- docs/plans/backend-modernization/02-optimistic-locking-ddl.sql.

ALTER TABLE tasks ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE columns ADD COLUMN version bigint NOT NULL DEFAULT 0;
```
`V5` needs, per the header-comment-then-flat-`ALTER TABLE`-statements style:
```sql
ALTER TABLE tasks ADD COLUMN position integer NOT NULL DEFAULT 0;
ALTER TABLE columns ADD COLUMN position integer NOT NULL DEFAULT 0;
ALTER TABLE subtasks ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN theme varchar(10) NOT NULL DEFAULT 'LIGHT';
ALTER TABLE boards ADD CONSTRAINT uk_boards_user_id_name UNIQUE (user_id, name);
```
(GAP-07's `activity_log.event_id` type change, if sequenced into this same migration per Pitfall 2's
warning, needs its own `DROP CONSTRAINT`/`ALTER COLUMN TYPE`/`ADD CONSTRAINT` sequence — not shown
here since RESEARCH.md recommends sequencing GAP-07 as its own wave, likely its own migration.)
`FlywaySchemaProvenanceTest.FlywayHistory.shouldRecordFourSuccessfulMigrations_whenContextStarts`
(`src/test/java/.../FlywaySchemaProvenanceTest.java:38-49`, not re-read this session, cited
verbatim in RESEARCH.md) currently hardcodes `IN ('1','2','3','4')` / `isEqualTo(4)` and needs a
mechanical update to `5`/`('1','2','3','4','5')`.

---

### `mapper/BoardFullMapper.java`/`ColumnFullMapper.java`/`TaskFullMapper.java` (NEW, GAP-04)

**Analog:** `mapper/ColumnMapper.java` (full file, 24 lines) for the `@Mapper` annotation shape;
no existing analog for the `uses = {...}` composition attribute (RESEARCH.md's Pattern 3 is the
primary source for this genuinely new pattern).

```java
package com.vrudenko.kanban_board.mapper;

import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ColumnMapper {
    ColumnEntity fromSaveColumnRequestDTO(SaveColumnRequestDTO dto);
    ColumnResponseDTO toColumnResponseDTO(ColumnEntity entity);
    List<ColumnResponseDTO> toColumnResponseDTOList(List<ColumnEntity> entities);
}
```
New mappers add `uses = {...}` and an explicit `@Mapping` for the singular-vs-plural field-name
mismatch (`entity.column` → `dto.columns`, `entity.task` → `dto.tasks` — Pitfall 5, verified
`BoardEntity.column`/`ColumnEntity.task` are genuinely singular `List` fields in this codebase, not
a typo to "fix"):
```java
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {ColumnFullMapper.class})
public interface BoardFullMapper {
    @Mapping(source = "column", target = "columns")
    BoardFullResponseDTO toBoardFullResponseDTO(BoardEntity entity);
}
```

**DTO shape — analog `dto/user_dto/UserResponseDTO.java`** (flat DTO, `@Getter @Setter
@EqualsAndHashCode`, `implements BaseId, Base{Domain}`):
```java
package com.vrudenko.kanban_board.dto.user_dto;

import com.vrudenko.kanban_board.base.entity.BaseId;
import com.vrudenko.kanban_board.base.entity.BaseUser;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode
public class UserResponseDTO implements BaseId, BaseUser {
    private String id;
    private String email;
    private String displayName;
}
```
Apply the same `@Getter @Setter @EqualsAndHashCode implements BaseId, Base{X}` shape to
`BoardFullResponseDTO`/`ColumnFullResponseDTO`/`TaskFullResponseDTO`, adding the nested `List<...>`
field per RESEARCH.md's Pattern 3 exact shape (already fully specified there — reuse verbatim).

---

### `controller/UserController.java` (NEW, GAP-05)

**Analog:** `controller/BoardController.java` lines 1-38 (class-level annotations + one GET method)

```java
@RestController
@RequestMapping(ApiPaths.BOARDS)
@Validated
@PreAuthorize("isAuthenticated()")
public class BoardController {
    @Autowired private BoardService boardService;

    @GetMapping
    public ResponseEntity<List<BoardResponseDTO>> findAllByUserId(@CurrentUserId String userId) {
        var boards = this.boardService.findAllByUserId(userId);
        return ResponseEntity.ok(boards);
    }
    ...
}
```
New `UserController` mirrors this exact shape (`@RestController @RequestMapping(ApiPaths.USERS)
@PreAuthorize("isAuthenticated()")`, `@Autowired private UserService userService;`,
`@CurrentUserId String userId` on every method, no path-variable userId — confirmed
`AuthenticationController` is NOT the analog since it deliberately carries no
`@PreAuthorize`/`@CurrentUserId`, being the one unauthenticated-entry controller). Add
`ApiPaths.USERS = "/users"`, `ApiPaths.ME = "/me"`, `ApiPaths.THEME = "/theme"` constants.

---

## Shared Patterns

### Ownership verification (applies to every new/modified controller→service call)
**Source:** `service/OwnershipVerifierService.java` lines 32-58 (`verifyOwnershipOfBoard`, the base
case every other `verifyOwnershipOf*` method chains from)
```java
@Transactional
public Pair<UserEntity, BoardEntity> verifyOwnershipOfBoard(String userId, String boardId)
        throws AppEntityNotFoundException, AppAccessDeniedException {
    if (userId == null || boardId == null) {
        throw new IllegalArgumentException();
    }
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
**Apply to:** `ColumnService.deleteById` (via `verifyOwnershipOfColumn`), all new position-mutating
service methods, `UserService.updateTheme` (uses `UserService.findById` instead, since it's the
identity-root case — no ownership chain needed, per RESEARCH.md's V4 note).

### Explicit version-compare-then-409-then-flush
**Source:** `service/TaskService.java` lines 103-132 / `service/ColumnService.java` lines 118-144
**Apply to:** `SubtaskService.updateById` (GAP-06), any new position-reorder mutation that also
takes a `version` field (GAP-03's `ReorderColumnRequestDTO`/extended `MoveTaskRequestDTO`).
```java
if (!task.getVersion().equals(dto.getVersion())) {
    throw new OptimisticLockingFailureException(
            "Task was modified by another request, please refetch.");
}
// ...mutate fields...
taskRepository.save(task);
entityManager.flush();
return taskMapper.toTaskResponseDTO(task);
```

### Bulk JPQL over per-row loop for batch mutations
**Source:** `service/TaskService.java` lines 245-257 (`deleteAllByColumn`) and
`repository/SubtaskRepository.deleteAllByTaskIdIn` (Javadoc-cited, not read this session)
**Apply to:** GAP-03's position-renumbering shift (`UPDATE ... SET position = position + 1 WHERE
column_id = ? AND position >= ?` as one `@Modifying @Query`, not a per-sibling loop) — matches
RESEARCH.md's explicit recommendation and this codebase's own stated precedent.

### One-arm-per-exception-type in GlobalExceptionHandler
**Source:** `handler/GlobalExceptionHandler.java` (whole file, 106 lines) — every exception type
gets its own `@ExceptionHandler` method, no shared/generic duplicate-handling logic.
**Apply to:** New `AppDuplicateResourceException` handler (GAP-01/D-09).

### Sealed-interface event + one-.avsc-per-type + exhaustive switch
**Source:** `event/ActivityEvent.java` (permits list) + `event/avro/ActivityEventAvroMapper.java`
(whole file)
**Apply to:** `ColumnDeletedEvent` (GAP-02/D-06) — add to `permits`, add `.avsc`, add both switch
arms, add to `AvroSchemaRegistrar.SCHEMAS`, add `ActivityAction` enum value + `ActivityLogConsumer`
switch arm.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `dto/board_dto/BoardFullResponseDTO.java` + nested `ColumnFullResponseDTO`/`TaskFullResponseDTO` | model (DTO) | transform | No existing nested-DTO shape anywhere in this codebase — every existing response DTO is flat (`.planning/PROJECT.md`'s documented convention). RESEARCH.md's Pattern 3 is the authoritative source for the exact shape; `UserResponseDTO` supplies only the flat-DTO boilerplate half. |
| `mapper/BoardFullMapper.java`/`ColumnFullMapper.java`/`TaskFullMapper.java` | service (mapper) | transform | No existing MapStruct mapper in this codebase uses the `uses = {...}` composition attribute — `ColumnMapper` supplies the annotation/interface boilerplate only; the composition mechanism itself is externally CITED (MapStruct reference docs), not drawn from an in-repo precedent. |

## Metadata

**Analog search scope:** `src/main/java/com/vrudenko/kanban_board/{controller,service,entity,dto,mapper,event,handler,exception,repository,constant}/`, `src/main/resources/db/migration/`, `src/main/avro/`
**Files scanned:** 24 direct reads this session (all ≤160 lines; no file required offset/limit
truncation) plus RESEARCH.md's already-verified citations for files not independently re-read here
(`AppEntityNotFoundException.java`, `AvroTaskDeletedEvent.avsc`, `AvroSchemaRegistrar.java`,
`ActivityLogConsumer.java`, `ActivityAction.java`, `FlywaySchemaProvenanceTest.java`,
`ActivityLogEntity.java`) — flagged explicitly above wherever the planner/executor must do a fresh
direct read rather than trust this document's secondhand citation.
**Pattern extraction date:** 2026-08-08
