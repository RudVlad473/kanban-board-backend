# Phase 2: Kafka Foundation, Domain Events & Move Endpoint - Pattern Map

**Mapped:** 2026-08-01
**Files analyzed:** 12
**Analogs found:** 10 / 12

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `docker-compose.yml` (repo root) | config | infra/orchestration | `Dockerfile` (repo root) | no-analog (new format, infra-only) |
| `build.gradle` (modified — add spring-kafka, testcontainers) | config | dependency-management | `build.gradle` (existing) | exact (same file, additive edit) |
| `src/main/java/.../event/ActivityEvent.java` | model (sealed interface) | event-driven | `src/main/java/.../base/entity/BaseTask.java` (small marker/contract interface pattern) | role-match |
| `src/main/java/.../event/TaskCreatedEvent.java` | model (record) | event-driven | n/a (no existing record-based DTOs) | no-analog |
| `src/main/java/.../event/TaskMovedEvent.java` | model (record) | event-driven | n/a | no-analog |
| `src/main/java/.../event/TaskDeletedEvent.java` | model (record) | event-driven | n/a | no-analog |
| `src/main/java/.../event/BoardCreatedEvent.java` | model (record) | event-driven | n/a | no-analog |
| `src/main/java/.../event/ColumnCreatedEvent.java` | model (record) | event-driven | n/a | no-analog |
| `src/main/java/.../config/KafkaEventPublisher.java` | service (event listener/producer) | event-driven | `src/main/java/.../handler/GlobalExceptionHandler.java` (only existing cross-cutting `@Component`-style listener/interceptor) | role-match |
| `src/main/java/.../dto/task_dto/MoveTaskRequestDTO.java` | model (DTO) | request-response | `src/main/java/.../dto/task_dto/UpdateTaskRequestDTO.java` | exact |
| `src/main/java/.../controller/TaskMoveController.java` | controller | request-response | `src/main/java/.../controller/TaskController.java` | role-match (routing differs — flat, not nested) |
| `src/main/java/.../service/TaskService.java` (modified — `moveToColumn`, publish calls in save/updateById/deleteById) | service | CRUD + event-driven | `TaskService.updateById` (same file, existing method) | exact |
| `src/main/java/.../service/BoardService.java` (modified — publish calls) | service | CRUD + event-driven | `BoardService.save`/`updateById` (same file) | exact |
| `src/main/java/.../service/ColumnService.java` (modified — publish calls) | service | CRUD + event-driven | `ColumnService.save`/`updateById` (same file) | exact |
| `src/main/java/.../constant/ApiPaths.java` (modified — add `MOVE` constant) | config | n/a | same file, existing constants | exact |

## Pattern Assignments

### `src/main/java/.../service/TaskService.java` — new `moveToColumn` method (service, CRUD + event-driven)

**Analog:** `TaskService.updateById` (`src/main/java/com/vrudenko/kanban_board/service/TaskService.java`, lines 65-100)

**Imports already present** (lines 1-20): `EntityManager`, `jakarta.transaction.Transactional`, `OptimisticLockingFailureException`, `@Autowired` field injection. `moveToColumn` needs additionally: `com.vrudenko.kanban_board.event.TaskMovedEvent`, `org.springframework.context.ApplicationEventPublisher`, `java.time.Instant`, `java.util.UUID`.

**Explicit version-check-before-mutate pattern** (lines 75-100):
```java
@Transactional
public TaskResponseDTO updateById(String userId, String taskId, UpdateTaskRequestDTO dto) {
    var task = findById(userId, taskId);

    if (!task.getVersion().equals(dto.getVersion())) {
        throw new OptimisticLockingFailureException(
                "Task was modified by another request, please refetch.");
    }

    if (Optional.ofNullable(dto.getTitle()).isPresent()) {
        task.setTitle(dto.getTitle());
    }
    // ... field updates ...

    taskRepository.save(task);

    // Hibernate only bumps the in-memory @Version field once the UPDATE statement actually
    // runs, which normally happens at transaction commit, not at save(). Flushing here forces
    // that UPDATE (and the version increment) to happen before the response DTO is built.
    entityManager.flush();

    return taskMapper.toTaskResponseDTO(task);
}
```

**Ownership pattern to reuse for target column** — `findById(userId, taskId)` internally calls `ownershipVerifierService.verifyOwnershipOfTask(userId, taskId)` (lines 59-63). `moveToColumn` additionally needs `ownershipVerifierService.verifyOwnershipOfColumn(userId, dto.getTargetColumnId())` (same service, different method — see OwnershipVerifierService below) to authorize the target column, then a manual cross-board equality check (`IllegalArgumentException` → existing 400 handler, no new exception class).

**Concrete `moveToColumn` implementation is fully specified in RESEARCH.md** (Code Examples section, "TaskService.moveToColumn") — copy that directly; it already threads through `findById`, `ownershipVerifierService.verifyOwnershipOfColumn`, the cross-board `IllegalArgumentException` check, the explicit version check, `taskRepository.save` + `entityManager.flush()`, and the `eventPublisher.publishEvent(new TaskMovedEvent(...))` call at the tail.

**Event-publish call shape to add at the tail of `save`, `updateById`, `deleteById`** (new — not yet in codebase):
```java
eventPublisher.publishEvent(
        new TaskCreatedEvent(UUID.randomUUID(), userId, column.getBoard().getId(), task.getId(), Instant.now()));
```
Applied per-method with the appropriate event type (`TaskCreatedEvent` in `save`, `TaskMovedEvent` in `moveToColumn`, `TaskDeletedEvent` in `deleteById` — capture `boardId` from the loaded `TaskEntity` **before** `taskRepository.deleteById(...)` runs, per RESEARCH.md Pattern 3).

**`@Transactional` addition to `save()`** — per RESEARCH.md Pitfall 1 (A3), add `@Transactional` directly to `TaskService.save`, `BoardService.save`, `ColumnService.save` (currently unannotated, lines 34 in TaskService.java, 79 in BoardService.java, 62 in ColumnService.java) so the event-publish guarantee (`@TransactionalEventListener` firing) doesn't depend on the caller's ambient transaction.

---

### `src/main/java/.../service/BoardService.java` and `ColumnService.java` — same publish-call pattern

**Analog:** `BoardService.save`/`updateById` (lines 65-86), `ColumnService.save`/`updateById` (lines 62-117) — same file being modified, same shape as `TaskService`.

Both already follow: `@Autowired` field injection, `@Transactional` on mutating methods (except `save`, addressed above), MapStruct mapper call at the return. New `ApplicationEventPublisher eventPublisher` field to add via `@Autowired`, publish call added at the tail of each mutating method (`BoardService.save` → `BoardCreatedEvent`; `ColumnService.save` → `ColumnCreatedEvent`).

---

### `src/main/java/.../controller/TaskMoveController.java` (controller, request-response)

**Analog:** `src/main/java/com/vrudenko/kanban_board/controller/TaskController.java` (full file, 61 lines) — but note the **routing pattern must NOT be copied verbatim**: `TaskController`'s class-level `@RequestMapping` is `ApiPaths.BOARDS + BOARD_ID + COLUMNS + COLUMN_ID + TASKS` (lines 19-24), which composes additively with method-level mappings and cannot produce the required flat `/tasks/{taskId}/move` path. `TaskMoveController` must instead be `@RequestMapping(ApiPaths.TASKS)` at the class level only.

**Structural pattern to copy** (imports, `@RestController`, `@PreAuthorize("isAuthenticated()")` class-level guard, `@Autowired TaskService taskService`, `@CurrentUserId` param, `@PathVariable @NotBlank`, `@Valid @RequestBody`, `ResponseEntity.ok(...)` wrapping):
```java
@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS + ApiPaths.COLUMN_ID + ApiPaths.TASKS)
@PreAuthorize("isAuthenticated()")
class TaskController {
    @Autowired TaskService taskService;

    @PutMapping(ApiPaths.TASK_ID)
    public ResponseEntity<TaskResponseDTO> updateById(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody UpdateTaskRequestDTO dto) {
        return ResponseEntity.ok(taskService.updateById(userId, taskId, dto));
    }
}
```

**Full `TaskMoveController` implementation is fully specified in RESEARCH.md** (Code Examples, "TaskMoveController") — copy directly, using `@RequestMapping(ApiPaths.TASKS)` + `@PatchMapping(ApiPaths.TASK_ID + ApiPaths.MOVE)`.

---

### `src/main/java/.../dto/task_dto/MoveTaskRequestDTO.java` (DTO, request-response)

**Analog:** `src/main/java/com/vrudenko/kanban_board/dto/task_dto/UpdateTaskRequestDTO.java` (full file, 34 lines)

**Full pattern to copy** (lines 1-34):
```java
@Getter
@Setter
@Builder
@EqualsAndHashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateTaskRequestDTO implements BaseTask {
    @TaskTitle String title;
    @Description String description;
    @NotNull private Long version;

    @AssertTrue(message = "Either 'title' or 'description' (or both) must be provided.")
    private boolean atLeastOneFieldPopulated() { ... }
}
```
`MoveTaskRequestDTO` follows the same annotation stack (`@Getter @Setter @Builder @EqualsAndHashCode @JsonInclude(NON_NULL)`) but does **not** implement `BaseTask` (doesn't carry task fields) and has no "at least one field" check (both `targetColumnId` and `version` are always required): `@NotBlank private String targetColumnId; @NotNull private Long version;` — exact shape given in RESEARCH.md Code Examples.

---

### `src/main/java/.../constant/ApiPaths.java` (config, modified)

**Analog:** same file, existing constants block (lines 6-25) — every path segment is already a named constant, no bare string literals. Add:
```java
public static final String MOVE = "/move";
```
placed alongside `TASK_ID` for locality.

---

### `src/main/java/.../event/*` (new package — 5 records + sealed interface)

**No direct codebase analog** — this is the first event/record-based package in the project (existing DTOs use Lombok `@Builder` classes, not records). Closest structural precedent is the small-interface-per-concept pattern in `src/main/java/.../base/entity/` (e.g. `BaseTask` — a thin interface implemented by both entity and DTO), which justifies the `sealed interface ActivityEvent permits ...` shape. Full record definitions (`ActivityEvent`, `TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`) are fully specified in RESEARCH.md Architecture Patterns, Pattern 2 — copy directly (plain Java records, no Lombok, no framework annotations, `implements ActivityEvent`).

---

### `src/main/java/.../config/KafkaEventPublisher.java` (new — event listener/producer)

**No direct codebase analog for the `@TransactionalEventListener` mechanism** — this is genuinely new machinery. Closest structural precedent for "a single `@Component` that centrally intercepts something for every mutating request" is `GlobalExceptionHandler` (`@ControllerAdvice`, `@Autowired` field injection, one method per concern) — same "cross-cutting `@Component`, not embedded per-service" shape, though the annotation/mechanism differs entirely (`@ExceptionHandler` vs `@TransactionalEventListener`).

**Full implementation is fully specified in RESEARCH.md** (Architecture Patterns, Pattern 2, "config/KafkaEventPublisher.java") — copy directly: `@Component`, `@Autowired private KafkaTemplate<String, Object> kafkaTemplate`, one `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` method typed against `ActivityEvent`, SLF4J `log.error(...)` in the `.whenComplete` failure callback (satisfies D-02).

---

### `docker-compose.yml` (new, repo root)

**No codebase analog** — first compose file in the project; only existing infra file is `Dockerfile` (single-stage app build, not orchestration). Full compose file (postgres + kafka with TCP healthcheck + app with `depends_on: condition: service_healthy`) is fully specified in RESEARCH.md Architecture Patterns, Pattern 1 — copy directly, adjusting `${DB_NAME}`/`${DB_USER}`/`${DB_PASS}` to match this project's actual `application.properties` env var names (verify against `src/main/resources/application.properties` during implementation).

---

### `build.gradle` (modified)

**Analog:** same file, existing dependency block (lines 35-81) — convention is BOM-managed (no explicit version string) for anything covered by the Spring Boot BOM, explicit version pinned only for non-BOM libraries (e.g. `com.google.guava:guava:32.0.1-android`, `io.vavr:vavr:0.10.4`). Add, no version strings (BOM-managed per spring-boot-dependencies 3.5.0):
```groovy
implementation 'org.springframework.kafka:spring-kafka'

testImplementation 'org.testcontainers:kafka'
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
```

## Shared Patterns

### `@Autowired` field injection (not constructor)
**Source:** every existing `@Service`/`@RestController` class (`TaskService.java` lines 24-32, `BoardService.java` lines 20-26, `TaskController.java` line 27)
**Apply to:** `TaskMoveController` (`@Autowired TaskService taskService;`), `KafkaEventPublisher` (`@Autowired private KafkaTemplate<String, Object> kafkaTemplate;`), and the new `ApplicationEventPublisher eventPublisher` field added to `TaskService`/`BoardService`/`ColumnService`.

### Explicit `@Version` check-before-mutate
**Source:** `TaskService.updateById` (lines 75-82), `ColumnService.updateById` (lines 101-104) — identical Javadoc explaining why `@Version` alone is insufficient
**Apply to:** `TaskService.moveToColumn` — reuse verbatim (same `OptimisticLockingFailureException` message pattern, same `entityManager.flush()` placement after `save()`).

### Error handling — no new exception types needed
**Source:** `GlobalExceptionHandler.java` — `IllegalArgumentException` → 400 (lines 38-41), `OptimisticLockingFailureException` → 409 (lines 80-84), `AppAccessDeniedException`/`AccessDeniedException` → 401 (lines 48-56)
**Apply to:** MOVE-03's cross-board rejection (throw `IllegalArgumentException`, already mapped to 400 — do not add a new handler), the move endpoint's stale-version case (throw `OptimisticLockingFailureException`, already mapped to 409), and target-column ownership failure (`OwnershipVerifierService.verifyOwnershipOfColumn` already throws the correctly-mapped exception).

### Ownership verification chain
**Source:** `OwnershipVerifierService` (referenced throughout `TaskService`/`BoardService`/`ColumnService` as `ownershipVerifierService.verifyOwnershipOf{Task,Column,Board}(userId, id)`, returns `Pair<UserEntity, XEntity>`)
**Apply to:** `TaskService.moveToColumn` needs both `verifyOwnershipOfTask` (via existing `findById`) and `verifyOwnershipOfColumn` (new call, for the target column) — both already exist and are unmodified.

### DTO annotation stack
**Source:** `UpdateTaskRequestDTO.java` (`@Getter @Setter @Builder @EqualsAndHashCode @JsonInclude(NON_NULL)`)
**Apply to:** `MoveTaskRequestDTO`.

### No-version-pin convention for Spring Boot BOM-managed deps
**Source:** `build.gradle` lines 36-51 (e.g. `implementation 'org.springframework.boot:spring-boot-starter-web'` — no version)
**Apply to:** `spring-kafka`, `org.testcontainers:*`, `spring-boot-testcontainers` additions.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `event/ActivityEvent.java` + 5 event records | model | event-driven | First record-based type and first event-driven data flow in the codebase — no prior producer/event infrastructure exists. Full definitions already provided in RESEARCH.md, use those directly. |
| `config/KafkaEventPublisher.java` | service | event-driven | First `@TransactionalEventListener`/Kafka-producer component — no prior messaging integration exists (confirmed via `.planning/codebase/INTEGRATIONS.md`). Full definition already provided in RESEARCH.md. |
| `docker-compose.yml` | config | infra | First compose file in the repo; only prior infra artifact is the single `Dockerfile`. Full file already provided in RESEARCH.md. |

## Metadata

**Analog search scope:** `src/main/java/com/vrudenko/kanban_board/{service,controller,dto,event,config,constant,handler,base}` directories; `build.gradle`; repo root for compose/Dockerfile precedent
**Files scanned:** `TaskService.java`, `BoardService.java`, `ColumnService.java`, `TaskController.java`, `ApiPaths.java`, `UpdateTaskRequestDTO.java`, `GlobalExceptionHandler.java`, `build.gradle`
**Pattern extraction date:** 2026-08-01

---
*Phase: 2-Kafka Foundation, Domain Events & Move Endpoint*
