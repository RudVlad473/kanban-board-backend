# Architecture Research

**Domain:** Kafka event-driven activity feed grafted onto an existing layered Spring Boot 3.5 / Java 21 REST API
**Researched:** 2026-08-01
**Confidence:** MEDIUM (project-specific integration reasoning is HIGH — it's derived directly from the read codebase; the generic Spring Kafka/Testcontainers/Docker facts are LOW-confidence single-source web search, cross-checked against Spring's own documented defaults where cited)

## Standard Architecture

### System Overview

The Kafka feature is a **producer append + a parallel consumer pipeline**, not a new layer inserted into the existing request path. `TaskService`/`BoardService`/`ColumnService` gain one new dependency (`KafkaTemplate`) and one new call each, at the tail of already-`@Transactional` mutating methods. Everything downstream of the topic — consumer, entity, repository, new controller — is a **second, independent vertical slice** that mirrors the existing Board/Column/Task/Subtask slice pattern exactly (controller → service → mapper → repository → entity), just fed by Kafka instead of HTTP.

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EXISTING: HTTP Controllers                                │
│  BoardController  ColumnController  TaskController  SubtaskController        │
│                                          + PATCH /tasks/{id}/move (NEW)      │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EXISTING: Service Layer (@Transactional)                  │
│  BoardService   ColumnService   TaskService (+ moveToColumn NEW)             │
│         │              │              │                                      │
│         └──────────────┴──────────────┴───► after commit-bound work:        │
│                                              @Autowired KafkaTemplate (NEW)   │
│                                              publish(topic, DomainEvent)      │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                        ▼
                         topic: kanban.activity  (NEW — Kafka broker)
                                        │
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│         NEW: com.vrudenko.kanban_board.activitylog package                   │
│  ┌──────────────────────────┐                                                │
│  │  ActivityLogConsumer     │  @KafkaListener(topics = "kanban.activity")    │
│  │  - existsByEventId() dedupe                                              │
│  │  - map event → ActivityLogEntity                                         │
│  │  - ActivityLogRepository.save()                                          │
│  └──────────────────────────┘                                                │
│           │ on repeated failure → DefaultErrorHandler                        │
│           ▼                                                                  │
│  topic: kanban.activity.dlt (NEW, via DeadLetterPublishingRecoverer)         │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│         NEW: ActivityLogRepository (Spring Data JPA)                         │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│         NEW: ActivityLogEntity → table activity_log                          │
└──────────────────────────────────────┬───────────────────────────────────────┘
                                        ▼
              PostgreSQL (existing DB, one new table, no schema coupling)

┌─────────────────────────────────────────────────────────────────────────────┐
│  NEW read path: GET /boards/{boardId}/activity                              │
│  ActivityController → ActivityLogService.findByBoardId(userId, boardId,     │
│    Pageable) → OwnershipVerifierService.verifyOwnershipOfBoard() (REUSED)   │
│    → ActivityLogRepository.findAllByBoardId(..., Pageable)                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Typical Implementation |
|-----------|----------------|------------------------|
| `event` package (NEW) | Immutable domain event payloads | Java `record`s: `TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`, each with `eventId` (UUID), `userId`, entity id(s), `timestamp`. No JPA/Spring annotations — plain data. |
| `KafkaProducerConfig` (NEW, in `config/`) | Producer serialization + `KafkaTemplate<String, Object>` bean | `ProducerFactory` with `StringSerializer` key + `JsonSerializer` value; Boot auto-configures this from `spring.kafka.*` properties if you don't hand-roll it — hand-roll only if you need `JsonSerializer` type-header suppression or custom `ObjectMapper`. |
| `TaskService`/`BoardService`/`ColumnService` (MODIFIED) | Publish one event per successful mutation, in addition to existing persistence logic | New `@Autowired private KafkaTemplate<String, Object> kafkaTemplate;` field + one `kafkaTemplate.send("kanban.activity", key, event)` call at the end of each mutating method body, inside the same `@Transactional` boundary (see Anti-Patterns for why this needs care). |
| `TaskService.moveToColumn` (NEW method) + `PATCH /tasks/{id}/move` (NEW endpoint) | The actual missing feature — reassign `TaskEntity.column`, publish `TaskMovedEvent` | Same shape as `updateById`: `ownershipVerifierService.verifyOwnershipOfTask`, then verify the *target* column via `verifyOwnershipOfColumn` (cross-board move must be rejected or explicitly allowed — decide this in planning), mutate, save, publish. |
| `ActivityLogConsumer` (NEW, `activitylog` package) | Kafka listener; idempotent persistence | `@KafkaListener(topics = "kanban.activity", groupId = "activity-log")` method taking the event type (or `ConsumerRecord<String,Object>`); checks `activityLogRepository.existsByEventId(event.eventId())` before insert. |
| `ActivityLogEntity` (NEW, `entity/`) | Persisted audit row | Extends `BaseEntity` (gets ULID id) or plain `@Id UUID eventId` as the natural key — see Patterns below. Fields: `boardId`, `userId`, `action`, `detail`, `createdAt`, `eventId` (unique). |
| `ActivityLogRepository` (NEW, `repository/`) | Data access + idempotency check + paginated read | `existsByEventId(UUID)`, `findAllByBoardIdOrderByCreatedAtDesc(String boardId, Pageable)`. |
| `ActivityLogService` (NEW, `service/`) | Read-path business logic, ownership enforcement | Mirrors `TaskService.findAllByColumnId` shape: calls `ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId)` first, then queries. |
| `ActivityController` (NEW, `controller/`) | `GET /boards/{boardId}/activity`, paginated | Same `@RestController` + `@PreAuthorize("isAuthenticated()")` + `@CurrentUserId` shape as the other four controllers. |
| `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (NEW, in `KafkaConsumerConfig`) | Route poison messages to `kanban.activity.dlt` after retries exhausted | `@Bean DefaultErrorHandler` wrapping `new DeadLetterPublishingRecoverer(kafkaTemplate)` + a bounded `FixedBackOff`/`ExponentialBackOff`, wired into the listener container factory. |

## Recommended Project Structure

```
src/main/java/com/vrudenko/kanban_board/
├── event/                          # NEW — producer-side event contracts
│   ├── TaskCreatedEvent.java
│   ├── TaskMovedEvent.java
│   ├── TaskDeletedEvent.java
│   ├── BoardCreatedEvent.java
│   └── ColumnCreatedEvent.java
├── activitylog/                    # NEW — consumer-side vertical slice
│   └── ActivityLogConsumer.java
├── entity/
│   └── ActivityLogEntity.java      # NEW — sits alongside existing entities
├── repository/
│   └── ActivityLogRepository.java  # NEW — same convention as other repos
├── service/
│   └── ActivityLogService.java     # NEW — read path, reuses OwnershipVerifierService
├── controller/
│   └── ActivityController.java     # NEW — 5th controller, same shape as the other 4
├── dto/
│   └── activity_dto/                # NEW — matches board_dto/column_dto/task_dto convention
│       └── ActivityLogResponseDTO.java
├── mapper/
│   └── ActivityLogMapper.java      # NEW — MapStruct, same componentModel=SPRING pattern
└── config/
    ├── KafkaProducerConfig.java    # NEW (only if Boot auto-config is insufficient)
    └── KafkaConsumerConfig.java    # NEW — error handler + DLT wiring
```

### Structure Rationale

- **`event/` as its own top-level package, not nested under `service/`:** events are the wire contract between producer and consumer, conceptually closer to DTOs than to service logic. The epic spec explicitly calls this out (`com.vrudenko.kanban_board.event`). Keeping it flat and dependency-free (no JPA, no Spring) means `TaskService` and `ActivityLogConsumer` can both depend on it without pulling in more coupling than necessary.
- **`activitylog/` as its own package, not `service/ActivityLogConsumer.java`:** the consumer is architecturally distinct from the CRUD services — it's not invoked by a controller, it's invoked by the Kafka container thread pool. Isolating it makes the boundary between "things HTTP requests call" and "things the Kafka listener container calls" visible in the package structure, which matters here because thread-context assumptions differ (no `SecurityContext`, no `@CurrentUserId`, no HTTP request scope).
- **`ActivityLogEntity`/`Repository`/`Service`/`Controller`/`dto/activity_dto`/`Mapper` all follow the existing per-domain vertical-slice convention exactly** (see `board_dto`, `column_dto`, `task_dto`, `subtask_dto`, `user_dto` in the current `dto/` tree, and the parallel `Board/Column/Task/Subtask` controller-service-repository triads). This is a deliberate choice: the activity log is a fifth domain entity, not a cross-cutting infrastructure concern, so it should look exactly like Board/Column/Task/Subtask to any reviewer already familiar with the codebase.
- **`config/KafkaConsumerConfig.java` separate from `KafkaProducerConfig.java`:** producer concerns (serialization, `KafkaTemplate` bean) and consumer concerns (error handling, DLT, listener container factory) have no functional overlap and are configured independently in Spring Kafka's API surface; splitting them avoids one bloated Kafka config class that mixes send-side and receive-side wiring.

## Architectural Patterns

### Pattern 1: Field-injected `KafkaTemplate` as a fourth kind of collaborator

**What:** `TaskService`, `BoardService`, `ColumnService` already field-inject repositories, mappers, and other services via `@Autowired`. `KafkaTemplate<String, Object>` is added as one more `@Autowired private` field on each, following the exact existing convention — no constructor injection introduced anywhere in this feature, even though `KafkaTemplate` has no circular-dependency risk (it's a leaf bean). Consistency with the codebase's established pattern outweighs the theoretical argument for constructor injection on this one new dependency.

**When to use:** Every mutating service method that needs to announce a domain event.

**Trade-offs:** Field injection makes `KafkaTemplate` implicitly optional-looking in tests (easy to leave unmocked and NPE) — existing service unit tests will need `@Mock KafkaTemplate` added wherever a mutating method is exercised. This is a real, mechanical cost across `TaskServiceTest`, `BoardServiceTest`, `ColumnServiceTest` — budget for it in planning rather than discovering it mid-phase.

**Example:**
```java
@Service
public class TaskService {
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskMapper taskMapper;
    @Autowired private OwnershipVerifierService ownershipVerifierService;
    @Autowired private SubtaskService subtaskService;
    @Autowired private EntityManager entityManager;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate; // NEW

    @Transactional
    public TaskResponseDTO moveToColumn(String userId, String taskId, MoveTaskRequestDTO dto) {
        var task = findById(userId, taskId);
        var targetColumn = ownershipVerifierService
                .verifyOwnershipOfColumn(userId, dto.getTargetColumnId())
                .getSecond();

        task.setColumn(targetColumn);
        taskRepository.save(task);

        kafkaTemplate.send("kanban.activity", task.getId(),
                new TaskMovedEvent(UUID.randomUUID(), userId, task.getId(), targetColumn.getId(), Instant.now()));

        return taskMapper.toTaskResponseDTO(task);
    }
}
```

### Pattern 2: Publish-after-persist, inside the same `@Transactional` method, accepting the dual-write gap

**What:** The epic spec's own phrasing — "after each successful mutating operation... publish the corresponding event" — puts the `kafkaTemplate.send()` call at the tail of the same `@Transactional` service method that does the DB write, not in a separate step. Because Kafka isn't part of the JDBC transaction, this is **not** transactionally atomic with the DB commit: it's possible for the DB write to commit and the Kafka send to fail (network blip, broker down), silently dropping an activity-log entry, or for the send to succeed but the surrounding transaction to later roll back on an unrelated later statement, publishing an event for a change that never persisted.

**When to use:** This is the correct trade-off for this project's actual goal (a legitimate, demonstrable Kafka use case) — a fully-consistent alternative (transactional outbox table + separate relay process) is real production practice but is disproportionate ceremony for a personal/portfolio project's Epic 1, and the codebase has no existing outbox infrastructure to build on.

**Trade-offs:** Document this explicitly as a known, accepted limitation (the epic spec's own "Explanation to have afterward" section anticipates exactly this question). Two mitigations worth actually implementing, both cheap: (1) call `entityManager.flush()` before the `kafkaTemplate.send()` (the codebase already does this in `TaskService.updateById` for a different reason — to surface the bumped `@Version` — so the pattern already exists) so the DB write is durable before the event goes out, narrowing the dual-write window to "DB committed, Kafka down" rather than both being in flight; (2) don't block the request thread on delivery confirmation — let `send()` return its `CompletableFuture` and log-and-continue on failure rather than making Kafka availability a hard dependency of every board mutation.

**Example:**
```java
taskRepository.save(task);
entityManager.flush();   // DB write durable before we tell Kafka about it
kafkaTemplate.send("kanban.activity", task.getId(), event)
        .exceptionally(ex -> { /* log; do not fail the HTTP response over this */ return null; });
```

### Pattern 3: Idempotent consumer via UUID `eventId` existence check

**What:** Kafka's default delivery guarantee is at-least-once — a consumer restart, rebalance, or manual offset reset can redeliver a message the consumer already processed. `ActivityLogConsumer` must check `activityLogRepository.existsByEventId(event.eventId())` before inserting, so redelivery is a no-op rather than a duplicate row.

**When to use:** Every `@KafkaListener` method that performs a non-idempotent side effect (a DB insert). This is a hard requirement, not an optimization — the epic spec calls it out explicitly as "the concrete 'at-least-once + idempotency' story worth being able to tell."

**Trade-offs:** A plain `existsByEventId` + `save()` has a narrow race window under concurrent redelivery (two threads/instances both pass the exists-check before either inserts) — acceptable for a single-consumer-instance dev/portfolio deployment. The more robust version makes `eventId` a **unique DB constraint** and catches `DataIntegrityViolationException` on the insert as the actual dedupe mechanism, with the `existsByEventId` check as a cheap pre-filter to avoid the wasted-insert attempt in the common case. Recommend doing both: unique constraint for correctness, exists-check for efficiency.

**Example:**
```java
@KafkaListener(topics = "kanban.activity", groupId = "activity-log")
public void consume(ActivityEventEnvelope event) {
    if (activityLogRepository.existsByEventId(event.eventId())) {
        return; // already processed — at-least-once redelivery, no-op
    }
    activityLogRepository.save(activityLogMapper.toEntity(event));
}
```

### Pattern 4: Single `kanban.activity` topic, one envelope type or union via headers

**What:** The epic spec defines five distinct event records but a single topic (`kanban.activity`) and a single `ActivityLogConsumer`. Spring Kafka's `JsonDeserializer` needs either (a) a common supertype/interface all five events implement (e.g. `ActivityEvent` with `eventId()`, `userId()`, `timestamp()`), consumed as that supertype with a `@KafkaListener` that switches on the concrete type, or (b) `spring.json.type.mapping` configured to map a `__TypeId__` header to each concrete record class, consumed via separate `@KafkaHandler` methods on a `@KafkaListener`-annotated class.

**When to use:** Given five *related* event types sharing one topic and one consumer (not five independently-scaled consumers), a common `sealed interface ActivityEvent permits TaskCreatedEvent, TaskMovedEvent, ...` with `@KafkaListener` + multiple `@KafkaHandler` overloads is the cleanest fit for Java 21's sealed types, and avoids hand-rolling a type-discriminator field.

**Trade-offs:** Sealed interfaces + `@KafkaHandler` is slightly more Spring-Kafka-specific machinery to learn than a single flat DTO, but it keeps the five event `record`s honest data classes without a shared abstract base class doing inheritance gymnastics.

## Data Flow

### Write Path: Move Task → Event → Activity Log Row

```
PATCH /tasks/{taskId}/move  (NEW endpoint)
    ↓
TaskController.moveToColumn()          [@PreAuthorize isAuthenticated, @CurrentUserId]
    ↓
TaskService.moveToColumn()             [@Transactional]
    ↓ verifyOwnershipOfTask + verifyOwnershipOfColumn(target)   [REUSED — OwnershipVerifierService]
    ↓ task.setColumn(target); taskRepository.save(task); entityManager.flush()
    ↓ kafkaTemplate.send("kanban.activity", taskId, new TaskMovedEvent(eventId, userId, taskId, targetColumnId, now))
    ↓
[HTTP response returns here — consumer runs fully async, on a different thread/JVM-internal thread pool]
    ↓
kanban.activity topic (Kafka broker, async)
    ↓
ActivityLogConsumer.consume()          [@KafkaListener — separate thread, no HttpSession/SecurityContext]
    ↓ existsByEventId(eventId)?  → yes: no-op (idempotency)
    ↓                              → no: continue
    ↓ map event → ActivityLogEntity(boardId, userId, action="TASK_MOVED", detail, createdAt)
    ↓ activityLogRepository.save()
    ↓ (on repeated processing failure → DefaultErrorHandler → DeadLetterPublishingRecoverer → kanban.activity.dlt)
    ↓
PostgreSQL: activity_log table (NEW)
```

### Read Path: GET Activity Log (mirrors existing GET patterns exactly)

```
GET /boards/{boardId}/activity?page=0&size=20
    ↓
ActivityController.findAllByBoardId()   [@PreAuthorize isAuthenticated, @CurrentUserId]
    ↓
ActivityLogService.findAllByBoardId(userId, boardId, pageable)   [@Transactional]
    ↓ ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId)   [REUSED]
    ↓ activityLogRepository.findAllByBoardId(board.getId(), pageable)
    ↓ activityLogMapper.toResponseDTOList(...)
    ↓
200 OK  Page<ActivityLogResponseDTO>
```

### Key Data Flows

1. **Write-path decoupling:** the HTTP request/response cycle for a task/board/column mutation never waits on the Kafka consumer or the `activity_log` insert — it only waits on the (fire-and-forget, or at most `.send()`-acknowledged) publish. This is the entire point of the "event-driven side effect" framing in the epic spec: a slow or temporarily-down activity log cannot make board mutations fail.
2. **No new dependency from existing services into `activitylog` package:** `TaskService` depends on `KafkaTemplate` and `event/*`, never on `ActivityLogConsumer`, `ActivityLogRepository`, or `ActivityLogEntity` directly. The only coupling from old code to new code is "publish an event," which keeps `activitylog` fully removable/replaceable (e.g., swappable for a separate microservice later, as the epic spec's "Out of Scope" section anticipates) without touching `TaskService`/`BoardService`/`ColumnService` again.
3. **Read-path reuses `OwnershipVerifierService` unmodified:** `ActivityLogService` calls the exact same `verifyOwnershipOfBoard(userId, boardId)` method every other read-by-board-id path uses. `ActivityLogEntity` needs a `boardId` field precisely so this reuse is possible — no new authorization logic is introduced by this feature, which is a good sign the boundary is drawn correctly.

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| Single EC2 instance, dev/portfolio traffic (current deployment) | Single-partition `kanban.activity` topic, single consumer instance (same JVM as the producer, in-process `@KafkaListener`) is entirely sufficient. This is explicitly the target per the epic spec — "the in-process `@KafkaListener` already demonstrates the event-driven pattern." Don't build for more than this. |
| Higher write volume / multiple app instances | Increase topic partitions and use a meaningful partition key (already using `task.getId()`/entity id as the Kafka message key above — this preserves per-entity ordering, which matters if e.g. two rapid moves of the same task must be logged in order) so multiple consumer instances in the same group can scale horizontally without reordering per-key events. |
| Consumer extracted to a separate deployable service (explicitly out of scope per PROJECT.md) | At that point `event/` becomes a genuine shared contract (published as a small shared library or duplicated record definitions) between two deployables, and schema evolution (adding fields to event records) becomes a real concern requiring backward-compatible JSON changes — not a problem worth solving now. |

### Scaling Priorities

1. **First real risk at current scale is not throughput, it's the dual-write gap** (Pattern 2) — under low volume this is rare but not impossible (broker restart during a deploy), and it's worth being able to explain rather than worth engineering away for a single-node personal project.
2. **Second: consumer-side idempotency correctness** (Pattern 3) matters more than performance — a single duplicated activity-log row is a visible, embarrassing bug in a portfolio demo; get the unique-constraint + exists-check combination right before optimizing anything else.

## Anti-Patterns

### Anti-Pattern 1: Publishing the Kafka event *before* `taskRepository.save()`/commit

**What people do:** Call `kafkaTemplate.send()` first "to get it out of the way," then do the DB write.

**Why it's wrong:** If the DB write subsequently fails (validation exception thrown later in the same method, constraint violation, etc.) or the transaction rolls back, an activity-log entry gets created for a mutation that never actually happened — a false audit trail entry, which defeats the entire purpose of an activity/audit log.

**Do this instead:** Always persist first, flush to make the write durable, publish last — exactly the order in Pattern 2's example. If the method can throw after the mutation but before the publish, that's an acceptable "missing" event (silent under-logging), which is far less bad than a false "phantom" event.

### Anti-Pattern 2: Consumer reading `SecurityContext` / `@CurrentUserId` / calling `OwnershipVerifierService` for write-side authorization

**What people do:** Instinctively reach for the same ownership-verification pattern used everywhere else in the codebase inside `ActivityLogConsumer`, e.g. calling `ownershipVerifierService.verifyOwnershipOfBoard(...)` before persisting the log row.

**Why it's wrong:** The `@KafkaListener` method runs on a Kafka consumer container thread, not an HTTP request thread — there is no `SecurityContext`, no session, no authenticated principal available via the existing `@CurrentUserId`/`CurrentUserIdResolver` machinery. The event was already authorized once, at publish time, inside the originating service method (which *did* run in an authenticated HTTP request and *did* go through `OwnershipVerifierService`). Re-checking authorization on the consumer side is both impossible with the existing security stack as-is and conceptually redundant — trust the event payload's `userId` as already-verified.

**Do this instead:** `ActivityLogConsumer` only does idempotency check + straightforward field mapping + save. Ownership/authorization enforcement belongs exclusively on the **read** side (`ActivityLogService.findAllByBoardId` calling `verifyOwnershipOfBoard`, which does run on a normal authenticated HTTP thread), not the write side.

### Anti-Pattern 3: `@KafkaListener` method thin enough to skip idempotency, "because it's just a log"

**What people do:** Treat the activity log as low-stakes ("it's just an audit trail, duplicates don't matter much") and skip the `existsByEventId` check to save a query.

**Why it's wrong:** This is the exact opposite of the epic's stated purpose — the whole reason this feature exists is to have a concrete, defensible "at-least-once + idempotency" story. Skipping it converts the feature from "demonstrates event-driven design correctly" into "has an obvious, easily-probed correctness bug," which is a worse outcome for a portfolio piece than not having the feature at all.

**Do this instead:** Idempotency check is not optional scope — treat it as part of the entity/repository design from the start (unique constraint on `eventId` at the DDL level), not a follow-up hardening pass.

### Anti-Pattern 4: Wiring `KafkaTemplate` via constructor injection while the rest of the service class uses field injection

**What people do:** "Constructor injection is best practice," so the new `KafkaTemplate` field gets a constructor while `taskRepository`, `taskMapper`, etc. stay `@Autowired` fields on the same class.

**Why it's wrong:** Mixing injection styles within one class is inconsistent and confusing to a reviewer, and — per this codebase's own documented rationale (CLAUDE.md, ARCHITECTURE.md Anti-Patterns) — the existing all-field-injection convention exists specifically to sidestep circular bean dependencies between the Board/Column/Task/Subtask/Ownership service graph. `KafkaTemplate` doesn't participate in that graph and has no circularity risk, but introducing a second injection style for one field is a stylistic regression, not an improvement, in a codebase that has already made and documented this trade-off.

**Do this instead:** `@Autowired private KafkaTemplate<String, Object> kafkaTemplate;` — same as every other collaborator in `TaskService`/`BoardService`/`ColumnService`.

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| Kafka broker (new, via `docker-compose.yml`) | `spring-kafka` `KafkaTemplate` (producer) + `@KafkaListener` (consumer), both auto-configured from `spring.kafka.bootstrap-servers` | Use the official `apache/kafka` Docker Hub image in **KRaft mode** (combined broker+controller, no separate Zookeeper container needed) — this is now the standard, simplest way to run single-node Kafka for local dev; minimal env vars: `KAFKA_PROCESS_ROLES=controller,broker`, `KAFKA_NODE_ID`, `KAFKA_CONTROLLER_QUORUM_VOTERS`, listeners on 9092 (client)/9093 (controller). [LOW confidence, web search only — verify exact env var names against the image's current README at implementation time.] |
| PostgreSQL (existing) | `ActivityLogRepository extends JpaRepository`, same `spring.datasource.*` connection the rest of the app already uses | No new datasource — `activity_log` is just a new table in the existing schema, created via the same manual-DDL-then-later-Flyway path the project is already using for `version` columns (per PROJECT.md's outstanding-manual-step precedent). Plan for a matching manual DDL step before deploy, same lesson as the optimistic-locking migration. |
| Testcontainers Kafka module (`org.testcontainers:kafka`, test-scope only) | `@Container static KafkaContainer` + `@DynamicPropertySource` to override `spring.kafka.bootstrap-servers` at test runtime | Standard pattern: spin up a real embedded-in-Docker broker per test class, publish an event via the real `KafkaTemplate` bean, then poll (with a timeout — production/consumption is async across threads) for the `ActivityLogEntity` row to appear via the repository. This is also how the epic spec (and Epic 5's Testcontainers epic) wants Testcontainers formalized project-wide, so this is the first real usage, not a one-off. |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `TaskService`/`BoardService`/`ColumnService` → `event/` | Direct construction of event records | New, one-directional, dependency-free — events have no behavior, just data. |
| `TaskService`/`BoardService`/`ColumnService` → Kafka broker | `KafkaTemplate.send(topic, key, event)` | Fire-and-forget from the service's perspective (see Pattern 2) — do not block the HTTP response on consumer processing. |
| Kafka broker → `ActivityLogConsumer` | `@KafkaListener` container thread | Fully decoupled from the HTTP request lifecycle — no shared thread-locals (`SecurityContext`, `EntityManager` transaction) with the producing request. |
| `ActivityLogConsumer` → `ActivityLogRepository`/`ActivityLogEntity` | Direct, same-JVM JPA save | Straightforward — this is the one place genuinely new persistence logic is introduced. |
| `ActivityController`/`ActivityLogService` → `OwnershipVerifierService` | Direct method call, **reused unmodified** | The single most important existing-architecture integration point: this is what makes the new read endpoint consistent with every other board-scoped resource in the app, and it costs zero new authorization code. |
| New endpoint `PATCH /tasks/{taskId}/move` → `TaskController`/`TaskService` | Same `@PreAuthorize("isAuthenticated()")` + `@CurrentUserId` + `OwnershipVerifierService` shape as every other mutating endpoint | No new security pattern needed — this is a same-shape addition to an existing controller, not a new component category. |

## Suggested Build Order

Dependencies flow strictly downward through this list — each step is unblocked by the previous one and (mostly) independently testable:

1. **`docker-compose.yml`** (postgres + apache/kafka KRaft + app) — unblocks all local development and the later Testcontainers work; zero code dependencies, do this first.
2. **`spring-kafka` dependency in `build.gradle`** — trivial, unblocks everything else.
3. **`event/` package** (five records) — pure data, no dependencies on anything else new; write these before touching any service.
4. **`ActivityLogEntity` + `ActivityLogRepository`** (with `eventId` unique constraint + `existsByEventId`) — needed before the consumer can be written or tested; this is also where the manual-DDL-before-deploy lesson from the optimistic-locking phase applies again.
5. **Producer side: `KafkaTemplate` wiring into `TaskService`/`BoardService`/`ColumnService`**, including the new `moveToColumn` method + `PATCH /tasks/{taskId}/move` endpoint. Can be built and unit-tested (mocked `KafkaTemplate`) independently of the consumer existing yet.
6. **`ActivityLogConsumer`** (`@KafkaListener`, idempotency check, mapping) — now has both an entity to write to and events to consume; this is the first point a real end-to-end flow can be exercised.
7. **`DefaultErrorHandler` + `DeadLetterPublishingRecoverer`** (`kanban.activity.dlt`) — layer onto the working consumer once the happy path is proven; retrofitting error handling onto an already-tested consumer is lower-risk than building it blind.
8. **Read path: `ActivityLogService` + `ActivityController` + `ActivityLogResponseDTO`/`ActivityLogMapper`** (`GET /boards/{boardId}/activity`, paginated, reusing `OwnershipVerifierService`) — depends only on step 4's entity existing with data in it; can be built/tested with directly-inserted rows even before the producer/consumer are wired.
9. **Testcontainers integration test** (`org.testcontainers:kafka`) — genuinely end-to-end, so it necessarily comes last: publish a real `TaskMovedEvent` through a real broker, assert the `ActivityLogEntity` row lands. This is also the test that will catch any mismatch between steps 5 and 6 that unit tests with mocks would miss.

This order matches the plan's own "Testing" note (the Testcontainers test is described as validating the already-built pieces end-to-end) and keeps each step reviewable as an independent, buildable increment — consistent with this project's one-epic-per-PR discipline, even if the phases within the epic end up as separate commits/PRs.

## Sources

- Existing codebase, read directly: [`ARCHITECTURE.md`](../codebase/ARCHITECTURE.md), `TaskService.java`, `OwnershipVerifierService.java`, `TaskController.java`, `BaseEntity.java`, `build.gradle`, `application.properties` — HIGH confidence, primary source.
- [Epic 1 — Kafka + event-driven activity feed](../../docs/plans/backend-modernization/01-kafka-activity-feed.md) — the driving spec for this milestone — HIGH confidence, primary source.
- [Apache Kafka Support :: Spring Boot](https://docs.spring.io/spring-boot/reference/messaging/kafka.html) — LOW confidence (web search summary, not directly fetched/verified against current doc version).
- [Handling Exceptions :: Spring Kafka](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) — DefaultErrorHandler / DeadLetterPublishingRecoverer pattern — LOW confidence (web search summary).
- [DeadLetterPublishingRecoverer API docs](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/DeadLetterPublishingRecoverer.html) — LOW confidence (web search summary).
- [apache/kafka Docker Hub image](https://hub.docker.com/r/apache/kafka) — KRaft single-node docker-compose pattern — LOW confidence (web search summary; verify exact env vars against the image README before writing the compose file).
- [Testing Spring Boot Kafka Listener using Testcontainers](https://testcontainers.com/guides/testing-spring-boot-kafka-listener-using-testcontainers/) — LOW confidence (web search summary).
- [Spring for Apache Kafka 4.0.0-M1, 3.3.4, and 3.2.8 are Available Now](https://spring.io/blog/2025/03/18/spring-kafka-4-0-0-M1-and-3-3-4-and-3-2-8-available-now/) — version compatibility with Spring Boot 3.5.x — LOW confidence (web search summary).

---
*Architecture research for: Kafka event-driven activity feed integration into an existing layered Spring Boot backend*
*Researched: 2026-08-01*
