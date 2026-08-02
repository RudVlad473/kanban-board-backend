# Phase 3: Activity Log Consumer, Reliability & Read API - Pattern Map

**Mapped:** 2026-08-02
**Files analyzed:** 10 (new) + 2 (modified)
**Analogs found:** 9 / 10

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `entity/ActivityLogEntity.java` | model | CRUD | `entity/TaskEntity.java` (+ `entity/UserEntity.java` for unique column) | role-match |
| `repository/ActivityLogRepository.java` | model (repository) | CRUD | `repository/TaskRepository.java` | exact |
| `service/ActivityLogService.java` | service | request-response | `service/ColumnService.java` (`findAllByBoardId`) | exact |
| `controller/ActivityController.java` | controller | request-response | `controller/ColumnController.java` | exact |
| `dto/activity_dto/ActivityLogResponseDTO.java` | model (DTO) | transform | `dto/task_dto/TaskResponseDTO.java` | exact |
| `mapper/ActivityLogMapper.java` | utility (mapper) | transform | `mapper/TaskMapper.java` | exact |
| `activitylog/ActivityLogConsumer.java` | controller (event handler) | event-driven | `config/KafkaEventPublisher.java` (only Kafka-touching class; producer-side, not consumer) | partial (no consumer analog exists in-repo — first one) |
| `config/KafkaConsumerConfig.java` | config | event-driven | `config/AsyncConfig.java` (bean-shape only, not topic/error-handler content) | partial (no error-handler analog exists) |
| `constant/KafkaTopics.java` (modify — add `ACTIVITY_DLT`) | config | — | itself (existing file) | exact |
| `constant/ApiPaths.java` (modify — add `ACTIVITY`) | config | — | itself (existing file) | exact |

## Pattern Assignments

### `entity/ActivityLogEntity.java` (model, CRUD)

**Analogs:** `entity/TaskEntity.java`, `entity/UserEntity.java` (for `unique = true`), `entity/BaseEntity.java` (for the ULID `id` superclass)

**BaseEntity superclass** (`entity/BaseEntity.java` lines 10-15):
```java
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity implements BaseId {
    @Id @RandFlakeId protected String id;
}
```
Per CONTEXT.md's resolved discretion item, `ActivityLogEntity extends BaseEntity` (ULID `id`) with a separate unique `eventId` column — do not use `eventId` as the primary key.

**Entity shape + `@Column` conventions** (`entity/TaskEntity.java` lines 15-39):
```java
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "tasks")
public class TaskEntity extends BaseEntity implements BaseTask {
    @Column(nullable = false, length = ValidationConstants.MAX_TASK_TITLE_LENGTH)
    private String title;
    ...
}
```

**Unique column pattern** (`entity/UserEntity.java` line 29):
```java
@Column(nullable = false, unique = true)
private String email;
```
Apply the same `@Column(nullable = false, unique = true)` to `eventId` (type `UUID`), matching `UserEntity.email`'s existing precedent — this is the DB-level backstop ACTLOG-03/D-05 rely on.

**No `@Version`/optimistic locking needed** — `ActivityLogEntity` is insert-only (consumer never updates rows), so omit `TaskEntity`'s `@Version` field.

**Reminder (Pitfall 0, from RESEARCH.md):** This is a brand-new table with no `ddl-auto` on the real Postgres profile — plan must ship a DDL bridge script (`docs/plans/backend-modernization/03-activity-log-ddl.sql`) modeled on `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql`, not just rely on the entity + H2 test profile.

---

### `repository/ActivityLogRepository.java` (model, CRUD)

**Analog:** `repository/TaskRepository.java` (lines 1-11, full file — small file, read once)
```java
public interface TaskRepository extends JpaRepository<TaskEntity, String> {
    List<TaskEntity> findAllByColumnId(String columnId);

    long countByColumnId(String columnId);
}
```
Same derived-query convention applies — no custom `@Query` needed:
```java
public interface ActivityLogRepository extends JpaRepository<ActivityLogEntity, String> {
    boolean existsByEventId(UUID eventId);

    Page<ActivityLogEntity> findAllByBoardIdOrderByCreatedAtDesc(String boardId, Pageable pageable);
}
```

---

### `service/ActivityLogService.java` (service, request-response)

**Analog:** `service/ColumnService.java` — specifically `findAllByBoardId` (lines 151-157) and the field-injection/class shape at the top (lines 1-37):
```java
@Service
public class ColumnService {
    @Autowired private ColumnRepository columnRepository;
    @Autowired private ColumnMapper columnMapper;
    @Autowired private OwnershipVerifierService ownershipVerifierService;
    ...

    @Transactional
    public List<ColumnResponseDTO> findAllByBoardId(String userId, String boardId) {
        var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

        return columnMapper.toColumnResponseDTOList(
                columnRepository.findAllByBoardId(pair.getSecond().getId()));
    }
}
```
**Ownership pattern to reuse unmodified** (`service/OwnershipVerifierService.java` lines 32-58) — `verifyOwnershipOfBoard(userId, boardId)` returns `Pair<UserEntity, BoardEntity>`, throws `AppEntityNotFoundException`/`AppAccessDeniedException` (already mapped by `GlobalExceptionHandler` to 404/401 — no new exception types needed).

**Adapt for pagination** — same shape, but pass `Pageable` through and map `Page<ActivityLogEntity>` to `Page<ActivityLogResponseDTO>` via `page.map(mapper::toActivityLogResponseDTO)` (Spring Data's `Page.map`, no manual reconstruction needed — this is the codebase's first paginated endpoint, so there's no prior in-repo example of that specific line, but it's the direct, idiomatic extension of the `findAllByBoardId` pattern above).

**Field injection convention:** `@Autowired private X y;` at class level, no constructor injection anywhere in this codebase — follow exactly.

---

### `controller/ActivityController.java` (controller, request-response)

**Analog:** `controller/ColumnController.java` (full file, lines 1-56 — small file, read once):
```java
@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS)
@PreAuthorize("isAuthenticated()")
public class ColumnController {
    @Autowired private ColumnService columnService;

    @GetMapping
    public ResponseEntity<List<ColumnResponseDTO>> findAllByBoardId(
            @CurrentUserId String userId, @PathVariable @NotBlank String boardId) {
        return ResponseEntity.ok(columnService.findAllByBoardId(userId, boardId));
    }
}
```
Direct adaptation for the new endpoint:
```java
@RestController
@RequestMapping(ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.ACTIVITY)
@PreAuthorize("isAuthenticated()")
public class ActivityController {
    @Autowired private ActivityLogService activityLogService;

    @GetMapping
    public ResponseEntity<Page<ActivityLogResponseDTO>> findAllByBoardId(
            @CurrentUserId String userId,
            @PathVariable @NotBlank String boardId,
            Pageable pageable) {
        return ResponseEntity.ok(activityLogService.findAllByBoardId(userId, boardId, pageable));
    }
}
```
**Route constant to add** — `ApiPaths.java` currently has `BOARDS`/`BOARD_ID`/`COLUMNS`/`COLUMN_ID`/etc. (lines 7-18); add `public static final String ACTIVITY = "/activity";` following the exact same naming convention.

**Auth annotation:** `@PreAuthorize("isAuthenticated()")` at class level — same as every other controller (`ColumnController`, `TaskController`, `BoardController`), no per-method override needed since ownership (not role) is the actual gate, enforced in the service layer.

**Path param convention:** `@PathVariable @NotBlank String boardId` — always `@NotBlank` on path variables expecting non-empty strings.

---

### `dto/activity_dto/ActivityLogResponseDTO.java` (model, transform)

**Analog:** `dto/task_dto/TaskResponseDTO.java` (full file, lines 1-19 — small file, read once):
```java
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class TaskResponseDTO implements BaseId, BaseTask {
    private String id;
    private String title;
    private String description;
    private Long version;
}
```
Per D-10, the new DTO does NOT implement `BaseId` (no `id`/`boardId` exposed) and carries exactly: `eventId`, `action`, `detail`, `userId`, `createdAt`:
```java
@Getter
@Setter
@Builder
@EqualsAndHashCode
public class ActivityLogResponseDTO {
    private UUID eventId;
    private String action;
    private String detail;
    private String userId;
    private Instant createdAt;
}
```
Package location: `dto/activity_dto/` — mirrors the `board_dto`/`column_dto`/`task_dto`/`subtask_dto`/`user_dto` per-domain folder convention.

---

### `mapper/ActivityLogMapper.java` (utility, transform)

**Analog:** `mapper/TaskMapper.java` (full file, lines 1-20 — small file, read once):
```java
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TaskMapper {
    TaskEntity fromSaveTaskRequestDTO(SaveTaskRequestDTO dto);

    TaskResponseDTO toTaskResponseDTO(TaskEntity entity);

    List<TaskResponseDTO> toTaskResponseDTOList(List<TaskEntity> entities);
}
```
Adapted (read-only mapper, no `fromSave...` needed since the consumer builds `ActivityLogEntity` directly, not via a Save DTO):
```java
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ActivityLogMapper {
    ActivityLogResponseDTO toActivityLogResponseDTO(ActivityLogEntity entity);
}
```
(`Page<T>.map(mapper::toActivityLogResponseDTO)` handles the list/page case — no `toActivityLogResponseDTOList` needed since `Page` isn't a `List`.)

---

### `activitylog/ActivityLogConsumer.java` (event handler, event-driven)

**No true analog exists** — this is the first `@KafkaListener` consumer in the codebase. Closest reference is the *producer*-side Kafka-touching class, useful only for project conventions (field injection, `@Component`, logging style), not for the consumer pattern itself:

**Producer-side conventions to carry over** (`config/KafkaEventPublisher.java` lines 30-56):
```java
@Component
public class KafkaEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    ...
}
```
Use the same `@Component`, field-injection, SLF4J `Logger` pattern for `ActivityLogConsumer`.

**Event contract to dispatch on** (`event/ActivityEvent.java` full file, lines 1-28 — sealed interface, 5 permitted records) and `event/TaskMovedEvent.java` (full file, lines 1-19) as the representative record shape — all 5 records carry `eventId`, `userId`, `boardId`, plus event-specific ids, `timestamp` last.

**Idempotency + exception-boundary pattern:** per RESEARCH.md Pattern 1/Pitfall 1 (D-05) — `existsByEventId` fast-path + `DataIntegrityViolationException` catch, both must `return` normally, never throw, from inside the `@KafkaHandler` method. See RESEARCH.md Code Examples section for the exact shape; no in-repo precedent exists for this control-flow pattern since it's Kafka-listener-specific (differs from the codebase's usual `App*Exception`-throwing convention used in `GlobalExceptionHandler`-routed paths).

---

### `config/KafkaConsumerConfig.java` (config, event-driven)

**No true analog exists** — first `DefaultErrorHandler`/`DeadLetterPublishingRecoverer`/`NewTopic` config in the codebase.

**Bean-declaration shape convention** (`config/AsyncConfig.java` — read for `@Configuration`/`@Bean` style; not reproduced here since it's executor-pool config, unrelated content, but confirms the `@Configuration` class + `@Bean`-method idiom already used project-wide alongside `BeanConfiguration.java`).

**Constant to add to `KafkaTopics.java`** (existing file, full content, lines 1-6):
```java
public final class KafkaTopics {
    public static final String ACTIVITY = "kanban.activity";
}
```
Add `public static final String ACTIVITY_DLT = "kanban.activity.dlt";` — same class, same naming convention (RESEARCH.md Pattern 3).

**Content pattern** — see RESEARCH.md "Code Examples" section (`DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `NewTopic` beans) for the exact API shape; it is Spring-Kafka-framework-sourced, not adapted from an in-repo analog.

---

## Shared Patterns

### Ownership/Authorization
**Source:** `service/OwnershipVerifierService.java` lines 32-58 (`verifyOwnershipOfBoard`)
**Apply to:** `ActivityLogService.findAllByBoardId` — reuse unmodified, do not add any new authorization logic. Returns `Pair<UserEntity, BoardEntity>`; throws `AppEntityNotFoundException`/`AppAccessDeniedException`, already mapped by `GlobalExceptionHandler`.

### Error Handling (HTTP layer)
**Source:** `handler/GlobalExceptionHandler.java` (full file, lines 1-107)
**Apply to:** `ActivityController` — no new exception types or handler methods needed; `AppEntityNotFoundException`→404 (line 33-36), `AppAccessDeniedException`→401 (line 53-56) already cover the read endpoint's only failure modes (unowned/nonexistent board).

### Field Injection
**Source:** project-wide (`OwnershipVerifierService`, `ColumnService`, `ColumnController`, `KafkaEventPublisher`)
**Apply to:** all new service/controller/consumer/config classes — `@Autowired private X fieldName;` at class level, never constructor injection.

### DTO/Entity Naming & Builder Style
**Source:** `dto/task_dto/TaskResponseDTO.java`
**Apply to:** `ActivityLogResponseDTO` — `@Getter @Setter @Builder @EqualsAndHashCode`, no Jackson annotations needed unless a field must be hidden (none here).

### MapStruct Mapper Convention
**Source:** `mapper/TaskMapper.java`
**Apply to:** `ActivityLogMapper` — `@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.IGNORE)`, plain interface, no manual implementation.

### API Path Constants
**Source:** `constant/ApiPaths.java`
**Apply to:** add `ACTIVITY = "/activity"` following the existing `{RESOURCE}` / `{RESOURCE}_ID` naming pairs already there.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `activitylog/ActivityLogConsumer.java` | event handler | event-driven | First `@KafkaListener` consumer in the codebase — Phase 2 only built the producer side (`KafkaEventPublisher`). Use RESEARCH.md's Code Examples (Pattern 1) as the primary source instead of an in-repo analog. |
| `config/KafkaConsumerConfig.java` | config | event-driven | First `DefaultErrorHandler`/`DeadLetterPublishingRecoverer`/`NewTopic` bean config — no DLT or explicit topic-provisioning precedent exists yet. Use RESEARCH.md's Code Examples for exact API shape. |

## Metadata

**Analog search scope:** `src/main/java/com/vrudenko/kanban_board/{entity,repository,service,controller,dto,mapper,config,event,handler,constant}/`
**Files scanned:** `entity/BaseEntity.java`, `entity/TaskEntity.java`, `entity/UserEntity.java`, `repository/TaskRepository.java`, `service/OwnershipVerifierService.java`, `service/ColumnService.java`, `controller/ColumnController.java`, `dto/task_dto/TaskResponseDTO.java`, `mapper/TaskMapper.java`, `config/KafkaEventPublisher.java`, `event/ActivityEvent.java`, `event/TaskMovedEvent.java`, `handler/GlobalExceptionHandler.java`, `constant/KafkaTopics.java`, `constant/ApiPaths.java`
**Pattern extraction date:** 2026-08-02
