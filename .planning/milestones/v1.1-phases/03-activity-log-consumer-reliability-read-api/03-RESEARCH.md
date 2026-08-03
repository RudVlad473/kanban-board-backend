# Phase 3: Activity Log Consumer, Reliability & Read API - Research

**Researched:** 2026-08-02
**Domain:** Spring Kafka idempotent consumer + dead-letter reliability + paginated read API, grafted onto an existing layered Spring Boot 3.5/Java 21 REST API
**Confidence:** MEDIUM (project-specific integration facts are HIGH — read directly from source this session; generic Spring Kafka 3.3.x/Testcontainers 1.21.x API shapes are MEDIUM, sourced from official docs via WebSearch/WebFetch, not a pinned-version doc fetch)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Activity log detail format**
- D-01: The `detail` field stores raw structured identifiers only (e.g. `{"taskId":"...","columnId":"..."}`), never a pre-rendered human-readable string. The consumer runs on a listener thread with no `SecurityContext` and cannot safely re-verify ownership to look up task/column/board names — human-readable rendering ("Jane moved Task X to Done") is deferred to the frontend, which already has the relevant names loaded. — Reversibility: costly — switching to pre-rendered strings later means re-deriving names for every already-persisted row (a backfill), or living with a format inconsistency between old and new rows.
- D-02: `action` is a fixed string enum, one of exactly `TASK_CREATED`, `TASK_MOVED`, `TASK_DELETED`, `BOARD_CREATED`, `COLUMN_CREATED`, mapped 1:1 from the event's Java class name (`ActivityEvent` implementor).
- D-03: `detail`'s event-specific ids are stored as a JSON string in a single column, not as separate nullable columns per possible id — one flexible column instead of a wide, mostly-null table, since each event type populates a different subset of ids.

**Consumer retry/backoff before DLT**
- D-04: The consumer retries a failing message 3 times with a short fixed backoff (~1s, `DefaultErrorHandler` + `FixedBackOff`) before routing it to the dead-letter topic. Applies uniformly to whatever exception the listener throws — Spring Kafka already special-cases deserialization failures as effectively non-retryable.
- D-05: The expected duplicate-`eventId` case (ACTLOG-03's idempotency requirement) MUST be caught inside the consumer as a silent no-op — it is not a failure and must never be allowed to throw into the retry/DLT path. — Reversibility: costly — if this isn't handled explicitly, every redelivered duplicate would exhaust 3 retries and land on the DLT, defeating the point of idempotent consumption and polluting the dead-letter topic with non-poison messages.
- D-06: RELY-02's poison-message test publishes a malformed/unparseable JSON payload (a genuine, deterministic deserialization failure), not a well-formed event rigged to always throw via a test-only failure-injection hook.

**Topic creation strategy**
- D-07: Both `kanban.activity` and `kanban.activity.dlt` are created via explicit `NewTopic` `@Bean` definitions, not broker auto-create. A typo'd topic name fails loudly (topic doesn't exist) instead of silently auto-creating a stray topic with default settings; the beans also self-document the topic list.
- D-08: Both topics get exactly 1 partition, matching the actual topology (single-broker KRaft, single consumer instance, no parallelism benefit from more partitions).

**Response shape for the read endpoint**
- D-09: `GET /boards/{boardId}/activity` returns a plain Spring Data `Page<ActivityLogResponseDTO>` — idiomatic, includes `totalElements`/`totalPages`/pageable metadata for free, no custom wrapper DTO to write and maintain. This is the first paginated endpoint in this codebase; no existing convention to match instead.
- D-10: Each list item DTO carries `eventId`, `action`, `detail` (raw JSON), `userId`, `createdAt`. `boardId` is deliberately omitted from each item — it's already known from the URL path and would be redundant on every row.

### Claude's Discretion
- Exact `ActivityLogEntity` field names/types beyond what's specified (e.g., whether `detail` is `String` or `@Lob` on the JPA side) — planner/researcher to confirm the right column type for a JSON string in this codebase's existing Postgres/Hibernate setup.
- Exact retry/backoff tuning knobs beyond "3 retries, ~1s fixed" (e.g., whether to use `FixedBackOff(1000L, 3)` literally or something equivalent) — implementation detail.
- `ActivityLogConsumer`'s package location (`activitylog` per the epic spec, already implied by REQUIREMENTS.md ACTLOG-02) and internal class structure (single consumer class vs. split per concern) — planner's call.
- Whether `ActivityLogEntity` extends `BaseEntity` (ULID id) with a separate unique `eventId` column, or uses `eventId` (UUID) directly as the primary key — both viable, research (Phase 2 milestone-level ARCHITECTURE.md) flagged this as an open question, still unresolved — planner/researcher to decide during Phase 3 planning. **Resolved below in "Don't Hand-Roll" / entity design section — see Alternate Approaches table.**

### Deferred Ideas (OUT OF SCOPE)
- Confluent Schema Registry (Avro/Protobuf) — captured as SEED-001, triggers at a future Kafka-related milestone (v1.2/v2.0+), not this phase.
- Schema evolution risk on `ActivityEvent` shapes — captured as a pending todo, relevant only if/when event record shapes change in the future, not actionable within this phase.
- Production (EC2) Kafka deployment (KAFKA-V2-01) and cursor/keyset pagination (PAGE-V2-01) — already tracked as v2-deferred in REQUIREMENTS.md, re-confirmed here as out of scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| ACTLOG-01 | `ActivityLogEntity`/`ActivityLogRepository` persists `boardId`, `userId`, `action`, `detail`, `createdAt`, `eventId` (UUID) with a DB-level `UNIQUE` constraint on `eventId` | Entity design section (BaseEntity + separate `eventId` column), manual-DDL-bridge finding (prod `ddl-auto` is unset — see Critical Pitfall 0), `UserEntity`'s `@Column(unique = true)` precedent |
| ACTLOG-02 | `@KafkaListener`-based `ActivityLogConsumer` in new `activitylog` package maps all 5 event types to `ActivityLogEntity` | Architecture Patterns (sealed-interface dispatch), Code Examples (`@KafkaListener` + `@KafkaHandler` per event type) |
| ACTLOG-03 | Redelivered `eventId` does not create a duplicate row — DB unique constraint is the safety net, `existsByEventId` is the fast path | Don't Hand-Roll (idempotency), Alternate Approaches table (exists-check vs. catch-and-swallow), Common Pitfalls 2 |
| READ-01 | `GET /boards/{boardId}/activity` returns the log newest-first, authorized via `OwnershipVerifierService.verifyOwnershipOfBoard` | Code Examples (repository query, controller/service shape), reused `OwnershipVerifierService.verifyOwnershipOfBoard(String, String)` signature verified this session |
| READ-02 | Paginated via standard Spring Data `Pageable` | Code Examples (`Page<ActivityLogResponseDTO>`, `PageRequest`/`Pageable` controller param) |
| RELY-01 | Dead-letter topic (`kanban.activity.dlt`) via `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` isolates poison messages | Code Examples (exact 3.3.x API shape), Architecture Patterns |
| RELY-02 | Test proves a deliberately-failing message lands on `kanban.activity.dlt` | Common Pitfalls 4, Code Examples (Testcontainers DLT-consumption test pattern) |
| TEST-01 | Testcontainers integration test publishes `TaskMovedEvent` through a real broker, asserts `ActivityLogEntity` row appears | Code Examples (Testcontainers Kafka setup + Awaitility polling pattern) |
| TEST-02 | Redelivery test publishes same `eventId` twice, asserts exactly one row | Code Examples, Common Pitfalls 2 |
</phase_requirements>

## Summary

This phase is the consumer half of an already-proven producer pipeline: Phase 2 shipped a sealed `ActivityEvent` interface, all five event records, and an after-commit `KafkaEventPublisher` that reliably sends to `kanban.activity` off the request thread. Phase 3 adds nothing new to the producer side — it builds a new `activitylog` vertical slice (`ActivityLogConsumer` → `ActivityLogEntity` → `ActivityLogRepository` → `ActivityLogService` → `ActivityController`) that mirrors the existing Board/Column/Task/Subtask package shape exactly, consumes the same topic, and exposes a first-of-its-kind paginated read endpoint.

The single most consequential finding this session, not previously surfaced in milestone-level research, is that **the real Postgres profile has no `spring.jpa.hibernate.ddl-auto` set at all** (confirmed by reading `application.properties` directly — no such property exists in that file). Epic 2's optimistic-locking phase hit this exact problem for the `version` column and shipped a standalone, git-tracked `.sql` bridge script (`docs/plans/backend-modernization/02-optimistic-locking-ddl.sql`) with an explicit "run this manually before merge" runbook. `ActivityLogEntity` is a **brand-new table**, not a new column on an existing one — without an equivalent DDL script, the table will not exist in production on deploy, and `ActivityLogConsumer` will hard-fail every save with a "relation does not exist" error the moment a real event arrives, since the H2 test profile's `ddl-auto=create-drop` will hide this gap completely in `./gradlew test`. This must be planned as an explicit deliverable, not discovered post-merge.

The second major finding is on the retry/DLT/idempotency interaction (CONTEXT.md D-04/D-05): `DefaultErrorHandler` + `FixedBackOff` + `DeadLetterPublishingRecoverer` is the correct, current (non-deprecated) Spring Kafka 3.3.x API shape, confirmed against the official reference docs this session. The subtlety the milestone research flagged as thin is now resolved: the duplicate-`eventId` no-op (D-05) must be implemented as a **local try/catch inside the listener method itself** — checking `existsByEventId` first (fast path, zero-exception common case) and additionally catching `DataIntegrityViolationException` around the actual `save()` call (the DB-constraint backstop for the race window between check and insert) — because anything the listener method *throws* is what `DefaultErrorHandler` sees and retries/dead-letters. A duplicate must never throw past the listener boundary, in either the fast-path or backstop case, or it silently exhausts D-04's 3 retries and pollutes the DLT with non-poison messages.

**Primary recommendation:** Build `ActivityLogEntity` extending `BaseEntity` (ULID `id`, matching every other entity's convention) with a separate `@Column(nullable = false, unique = true) UUID eventId` — the same `@Column(unique = true)` pattern `UserEntity.email` already uses — ship a hand-written DDL bridge script (`docs/plans/backend-modernization/03-activity-log-ddl.sql`) modeled directly on the optimistic-locking precedent, wire `DefaultErrorHandler(deadLetterPublishingRecoverer, new FixedBackOff(1000L, 3L))` with `existsByEventId` + a `DataIntegrityViolationException` catch as the two-layer idempotency guard, and prove the whole pipeline with `org.testcontainers.kafka.KafkaContainer` (the newer Testcontainers module already on the classpath, supports the same `apache/kafka` image family as `docker-compose.yml`) via `@ServiceConnection`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Event consumption & idempotent persistence (`ActivityLogConsumer`) | API/Backend (Kafka consumer container thread) | Database/Storage (unique constraint enforcement) | Runs entirely server-side, no HTTP request context; correctness depends on a DB-level constraint, not just application logic |
| Dead-letter routing (RELY-01/02) | API/Backend (Spring Kafka error-handling infra) | — | Configuration-only concern inside the same JVM/consumer container; no new service boundary |
| Activity log storage (`ActivityLogEntity`) | Database/Storage | — | New table in the existing Postgres schema; no new datasource |
| Paginated read endpoint (`GET /boards/{boardId}/activity`) | API/Backend | Database/Storage (Pageable query) | Same shape as every other `GET` list endpoint in this codebase; ownership-scoped via existing service-layer check |
| Ownership authorization | API/Backend (`OwnershipVerifierService`, reused unmodified) | — | Already-established boundary; read endpoint must not introduce new authorization logic |
| Topic provisioning (`NewTopic` beans) | API/Backend (Spring context startup) | — | Declarative Spring beans consumed by `KafkaAdmin` at context startup, not a separate infra step |

## Standard Stack

### Core

No new dependencies this phase. Everything required is already declared in `build.gradle` from Phase 2:

| Library | Version (as pinned by Spring Boot 3.5.0 BOM) | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.springframework.kafka:spring-kafka` | 3.3.6 [CITED: milestone STACK.md, verified against `spring-boot-dependencies` BOM at `v3.5.0` tag] | Producer/consumer abstraction; this phase adds the consumer side (`@KafkaListener`, `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, `NewTopic` beans) | Same dependency already on the classpath, BOM-managed, no version string needed in `build.gradle` |
| `org.testcontainers:kafka` | 1.21.0 [CITED: milestone STACK.md, verified against BOM] | Real embedded-in-Docker Kafka broker for TEST-01/TEST-02's integration tests | Already declared `testImplementation` in `build.gradle`; this phase is its first actual usage (Phase 2 only declared it) |
| `org.springframework.boot:spring-boot-testcontainers` | Boot-managed (3.5.0) | `@ServiceConnection` auto-wiring of `spring.kafka.bootstrap-servers` from the Testcontainers-started broker | Already declared; idiomatic Boot 3.1+ pattern, avoids manual `@DynamicPropertySource` |
| `org.testcontainers:junit-jupiter` | 1.21.0 (BOM-managed) | `@Testcontainers` JUnit 5 extension | Already declared |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Jackson (`spring-kafka`'s `JsonSerializer`/`JsonDeserializer`, transitively via `spring-boot-starter-json`) | Boot-managed | Serialize/deserialize `ActivityEvent` implementors over the wire | Already used by the existing `KafkaTemplate<String, Object>` producer bean from Phase 2 — the consumer side must configure a matching `JsonDeserializer` (with trusted packages) to read the same wire format |
| `org.awaitility:awaitility` | **Not currently in `build.gradle`** — see Package Legitimacy Audit | Polling-style assertions for the async Testcontainers integration tests (TEST-01/TEST-02/RELY-02) | Recommended over `Thread.sleep` for the exact reason PITFALLS.md flags: consumer group formation and delivery are asynchronous; a hard sleep is either flaky (too short) or slow (padded long) |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `Awaitility` | Manual `Thread.sleep` + retry loop | Awaitility is the idiomatic, already-cited pattern in this project's own milestone PITFALLS.md ("Testcontainers assertion timing flakiness" checklist item); a hand-rolled polling loop reinvents exactly what Awaitility already does correctly, for one test file — not worth hand-rolling per "Don't Hand-Roll" below |
| `existsByEventId` + unique constraint (locked, ACTLOG-03) | Unique constraint alone, catch `DataIntegrityViolationException` on every insert attempt | See "Alternate Approaches" table below — this is the CLAUDE.md-required second approach, evaluated and NOT chosen because it contradicts the already-locked ACTLOG-03 decision, but documented for the trade-off record |

**Installation:**
No `build.gradle` changes required for `spring-kafka`/Testcontainers (already present). One optional addition:
```gradle
testImplementation 'org.awaitility:awaitility:4.2.2'
```

**Version verification:** `org.awaitility:awaitility` was checked via WebSearch only, not `npm view`-equivalent registry verification (this is a Maven Central artifact, no direct query tool available in this session) — tag as `[ASSUMED]`, gate behind `checkpoint:human-verify` per the Package Legitimacy Audit below. `spring-kafka`/Testcontainers versions were already `[CITED]` in Phase 2's own STACK.md research against the pinned BOM — not re-verified this session since no `build.gradle` change is needed for them.

## Package Legitimacy Audit

Only one candidate new package this phase: `org.awaitility:awaitility` (test-scope, for polling assertions in the Testcontainers integration tests). No `gsd-tools`/npm-equivalent registry-check tool is available for Maven Central in this environment, so this cannot be elevated past `[ASSUMED]`.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| `org.awaitility:awaitility` | Maven Central | Long-established (originally released ~2013, actively maintained, widely used in the Spring ecosystem — per training knowledge, not verified this session) | Not queryable this session | `github.com/awaitility/awaitility` (per training knowledge) | `[ASSUMED]` — not run through an automated legitimacy check this session | Planner must gate the `build.gradle` addition behind a `checkpoint:human-verify` task before it is installed |

**Packages removed due to `[SLOP]` verdict:** none.
**Packages flagged as suspicious `[SUS]`:** none — the one candidate is `[ASSUMED]` (unverified), not flagged as suspicious; it is a well-known library in training knowledge, but that alone does not earn `[VERIFIED]` per this agent's provenance rules.

**Everything else this phase touches is already an existing `build.gradle` dependency** (`spring-kafka`, `org.testcontainers:kafka`, `org.testcontainers:junit-jupiter`, `spring-boot-testcontainers`) — no legitimacy check needed for already-adopted, already-reviewed dependencies.

## Architecture Patterns

### System Architecture Diagram

```text
Kafka broker: kanban.activity topic (Phase 2, already publishing)
        │
        ▼
┌───────────────────────────────────────────────────────────────────┐
│  activitylog package (NEW)                                        │
│                                                                     │
│  ActivityLogConsumer  @KafkaListener(topics = KafkaTopics.ACTIVITY)│
│    ├─ deserialize as ActivityEvent (sealed interface, 5 impls)     │
│    ├─ existsByEventId(event.eventId())?                            │
│    │      yes → return (silent no-op, D-05)                        │
│    │      no  → map event → ActivityLogEntity, try save()          │
│    │             DataIntegrityViolationException on save()?        │
│    │                yes → catch, return (silent no-op, backstop)   │
│    │                no  → row persisted                            │
│    └─ any OTHER exception propagates out of the listener method    │
│              │                                                     │
│              ▼                                                     │
│      DefaultErrorHandler (3 retries, FixedBackOff ~1s, D-04)       │
│              │  retries exhausted, or non-retryable                │
│              ▼                                                     │
│      DeadLetterPublishingRecoverer → kanban.activity.dlt topic      │
└───────────────────────────────────────────────────────────────────┘
              │
              ▼
      PostgreSQL: activity_log table (NEW — needs manual DDL bridge,
                  see Critical Pitfall 0; unique constraint on event_id)

┌───────────────────────────────────────────────────────────────────┐
│  Read path: GET /boards/{boardId}/activity?page=&size=             │
│                                                                     │
│  ActivityController → ActivityLogService.findAllByBoardId(         │
│      userId, boardId, pageable)                                    │
│      ├─ OwnershipVerifierService.verifyOwnershipOfBoard(userId,    │
│      │     boardId)   [REUSED, unmodified — READ-01]               │
│      └─ ActivityLogRepository.findAllByBoardIdOrderByCreatedAtDesc(│
│            boardId, pageable)   [READ-02]                          │
│                                                                     │
│  200 OK  Page<ActivityLogResponseDTO>  (D-09/D-10)                 │
└───────────────────────────────────────────────────────────────────┘
```

### Recommended Project Structure

```
src/main/java/com/vrudenko/kanban_board/
├── activitylog/
│   └── ActivityLogConsumer.java        # @KafkaListener + @KafkaHandler per event type
├── entity/
│   └── ActivityLogEntity.java          # extends BaseEntity; separate unique eventId column
├── repository/
│   └── ActivityLogRepository.java      # existsByEventId, findAllByBoardIdOrderByCreatedAtDesc(Pageable)
├── service/
│   └── ActivityLogService.java         # reuses OwnershipVerifierService.verifyOwnershipOfBoard
├── controller/
│   └── ActivityController.java         # GET /boards/{boardId}/activity, paginated
├── dto/
│   └── activity_dto/
│       └── ActivityLogResponseDTO.java # eventId, action, detail, userId, createdAt (D-10 — no id, no boardId)
├── mapper/
│   └── ActivityLogMapper.java          # MapStruct, componentModel=SPRING, matches existing convention
└── config/
    └── KafkaConsumerConfig.java        # NewTopic beans (D-07/D-08), DefaultErrorHandler + DeadLetterPublishingRecoverer wiring
```

### Structure Rationale

- `activitylog/ActivityLogConsumer.java` as its own package, not under `service/`, matches the milestone ARCHITECTURE.md's explicit reasoning (already read this session): the consumer runs on a Kafka container thread with no `SecurityContext`, structurally distinct from anything a controller calls.
- `entity/`, `repository/`, `service/`, `controller/`, `dto/activity_dto/`, `mapper/` all mirror the exact existing per-domain convention (`board_dto`, `column_dto`, `task_dto`, `subtask_dto`, `user_dto` confirmed via `Glob` this session) — `ActivityLogEntity` is a fifth domain entity, not infrastructure.
- `config/KafkaConsumerConfig.java` is new and separate from Phase 2's `config/KafkaEventPublisher.java` (producer-side) — no functional overlap, matches Phase 2's `AsyncConfig`/`KafkaEventPublisher` split-by-concern precedent.

### Pattern 1: Sealed-interface dispatch via `@KafkaListener` + multiple `@KafkaHandler` methods

**What:** `ActivityEvent` is already a Java 21 `sealed interface` (`permits TaskCreatedEvent, TaskMovedEvent, TaskDeletedEvent, BoardCreatedEvent, ColumnCreatedEvent` — verified this session by reading `event/ActivityEvent.java`). Spring Kafka's `JsonDeserializer` can deserialize directly to the concrete record type using the `__TypeId__` header (which `JsonSerializer` already writes on the producer side by default), and a single `@KafkaListener`-annotated class can declare one `@KafkaHandler` per concrete type, with Spring routing based on the deserialized payload's runtime type.

**When to use:** ACTLOG-02's "map all five event types to an `ActivityLogEntity` row" requirement — this is the natural fit for 5 related types on one topic.

**Example:**
```java
// Source: Spring Kafka reference docs pattern (CITED — annotation-based listener docs),
// combined with the project's own event/ActivityEvent.java sealed interface (VERIFIED this
// session — see event/ActivityEvent.java lines 15-28)
@Component
@KafkaListener(topics = KafkaTopics.ACTIVITY, groupId = "activity-log")
public class ActivityLogConsumer {
    @Autowired private ActivityLogRepository activityLogRepository;

    @KafkaHandler
    public void onTaskCreated(TaskCreatedEvent event) {
        persist(event.eventId(), event.boardId(), event.userId(), "TASK_CREATED",
                Map.of("taskId", event.taskId(), "columnId", event.columnId()));
    }

    @KafkaHandler
    public void onTaskMoved(TaskMovedEvent event) {
        persist(event.eventId(), event.boardId(), event.userId(), "TASK_MOVED",
                Map.of("taskId", event.taskId(),
                       "sourceColumnId", event.sourceColumnId(),
                       "targetColumnId", event.targetColumnId()));
    }
    // ... onTaskDeleted, onBoardCreated, onColumnCreated, same shape

    private void persist(UUID eventId, String boardId, String userId, String action,
                          Map<String, String> detail) {
        if (activityLogRepository.existsByEventId(eventId)) {
            return; // D-05: redelivery, silent no-op
        }
        try {
            activityLogRepository.save(
                    new ActivityLogEntity(boardId, userId, action, toJson(detail), eventId));
        } catch (DataIntegrityViolationException e) {
            // Backstop: the exists-check raced with a concurrent redelivery and lost.
            // Still a duplicate, still a silent no-op (D-05) — never rethrow here.
        }
    }
}
```

**Trade-offs:** Requires `spring.json.trusted.packages` (or a type mapper) configured so `JsonDeserializer` will deserialize into these concrete record types — otherwise it throws `IllegalArgumentException: The class ... is not in the trusted packages`. Configure via a consumer factory bean or `spring.kafka.consumer.properties.spring.json.trusted.packages=com.vrudenko.kanban_board.event` in `application.properties`. `[CITED: docs.spring.io Spring Kafka JSON serialization reference]`.

### Pattern 2: `NewTopic` `@Bean`s, auto-provisioned by `KafkaAdmin` at startup

**What:** Both `kanban.activity` and `kanban.activity.dlt` (D-07) are declared as `@Bean NewTopic` using `TopicBuilder`. Spring Boot auto-configures a `KafkaAdmin` bean from `spring.kafka.*` properties; any `NewTopic` bean in the context is automatically submitted to the broker's `AdminClient` at application startup (idempotent — `TopicBuilder` calls are safe to re-run, existing topics are left alone).

**Example:**
```java
// Source: Spring Kafka reference docs pattern — CITED (WebSearch-confirmed against
// docs.spring.io "Provisioning Topics" section)
@Configuration
public class KafkaTopicConfig {
    @Bean
    public NewTopic activityTopic() {
        return TopicBuilder.name(KafkaTopics.ACTIVITY).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic activityDeadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.ACTIVITY + ".dlt").partitions(1).replicas(1).build();
    }
}
```

**When to use:** D-07/D-08 explicitly require this over broker auto-create. `replicas(1)` matches the single-broker KRaft topology already running in `docker-compose.yml` (verified this session — Phase 2 Plan 03 summary confirms a single-node KRaft broker, no Zookeeper, `apache/kafka-native:4.3.1`).

**Trade-offs:** `KafkaAdmin` provisioning requires the broker to be reachable at application startup, or the app will log a provisioning failure (not necessarily fail startup — depends on `spring.kafka.admin.fail-fast`). Since Phase 2 already accepted "app starts fine with Kafka down, mutations just don't publish" as a design choice (verified in Phase 2 Plan 03 summary: a stopped-Kafka move request still returned 200), verify at planning time whether `fail-fast` should stay at its Boot default (`false`) for consistency with that already-accepted degraded-mode behavior.

### Pattern 3: `KafkaTopics.ACTIVITY + ".dlt"` vs. the epic's literal `kanban.activity.dlt` — same string either way

**What:** `KafkaTopics.ACTIVITY` is `"kanban.activity"` (verified this session — `constant/KafkaTopics.java` line 5). `KafkaTopics.ACTIVITY + ".dlt"` evaluates to `"kanban.activity.dlt"`, which matches ACTLOG's epic-spec-mandated literal name exactly (not Spring's default `.DLT`-suffix convention, which would produce `kanban.activity.DLT`). Add a second constant `KafkaTopics.ACTIVITY_DLT = "kanban.activity.dlt"` to `KafkaTopics.java` for the same self-documenting reason `KafkaTopics.ACTIVITY` already exists, rather than string-concatenating inline at each use site.

**When to use:** Wiring both the `NewTopic` bean and the `DeadLetterPublishingRecoverer`'s destination resolver — both need the exact same literal string, so a single source-of-truth constant avoids a silent topic-name mismatch between "the topic I created" and "the topic I route failures to."

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Retry-then-dead-letter routing | A custom `try/catch` + manual re-publish-to-DLT-topic loop inside the listener | `DefaultErrorHandler` + `FixedBackOff` + `DeadLetterPublishingRecoverer`, wired at the container-factory level | This is exactly what RELY-01 names as the required mechanism; it's also battle-tested for edge cases (non-retryable exception classification, partition/offset preservation in the DLT record headers) a hand-rolled version would miss |
| Async polling/wait for Kafka delivery in tests | `Thread.sleep(n)` before asserting on the repository | `Awaitility.await().atMost(...).untilAsserted(...)` | PITFALLS.md (already read this session) names this exact "Testcontainers assertion timing flakiness" pitfall by name — a fixed sleep is either flaky or wastefully slow, and this is a solved, one-dependency problem |
| Pagination metadata (`totalElements`, `totalPages`, page navigation) | A custom pagination wrapper DTO | Spring Data's `Page<T>` (D-09, already locked) | Spring Data JPA's `Pageable`/`Page<T>` gives this for free from a single `findAll(..., Pageable)`-shaped repository method; this is the first paginated endpoint in the codebase and no reason to deviate from the framework-idiomatic shape |
| JSON serialization of the `detail` field's id map | Manual string concatenation (`"{\"taskId\":\"" + id + "\"}"`) | The already-on-the-classpath Jackson `ObjectMapper` (autowired the same way `GlobalExceptionHandler` already does, verified this session) | Manual JSON string-building is a classic escaping-bug source (ids could theoretically contain characters needing escaping, though ULIDs don't in practice) — Jackson is already a dependency, zero cost to use it correctly |

**Key insight:** Every "don't hand-roll" item here is a case where Spring Kafka, Spring Data, or Jackson already solves the exact problem this phase needs — the temptation in each case is to write 10-20 lines of "simple enough" custom code that in fact re-implements a documented, tested library feature with worse edge-case coverage.

## Common Pitfalls

### Pitfall 0: `activity_log` table does not exist in production after a plain merge/deploy

**What goes wrong:** The real Postgres profile has no `spring.jpa.hibernate.ddl-auto` property set at all `[VERIFIED: src/main/resources/application.properties:1-47 — no ddl-auto line exists in this file, confirmed by reading the full file this session]`. Hibernate will not create the new `activity_log` table. `ActivityLogConsumer.save()` will throw a SQL "relation does not exist" error on every single event once this phase's code deploys, silently failing every consumption attempt in production — and since D-05/RELY-01's retry-then-DLT path will treat this as a generic exception, every real activity event will exhaust 3 retries and land on the DLT, which is worse than a clean failure: it looks like "the DLT works" (technically true) while actually meaning "the entire feature is broken."

**Why it happens:** The H2 test profile sets `spring.jpa.hibernate.ddl-auto=create-drop` `[VERIFIED: src/main/resources/application-test.properties:19 — "spring.jpa.hibernate.ddl-auto=create-drop"]`, so `./gradlew test` creates the schema fresh from the entity every run and this gap is completely invisible in CI. This is the exact same category of bug Epic 2's optimistic-locking phase already hit and fixed for the `version` column (`[CITED: docs/plans/backend-modernization/02-optimistic-locking-ddl.sql]`, `[VERIFIED: .planning/milestones/v1.0-REQUIREMENTS.md:21]`).

**How to avoid:** Ship a standalone `.sql` bridge script (e.g. `docs/plans/backend-modernization/03-activity-log-ddl.sql`), modeled directly on the existing precedent — `CREATE TABLE IF NOT EXISTS activity_log (...)` with the `event_id` unique constraint included in the same script, plus the same header-comment runbook style (what this is, when to run it manually via `psql` before merge, what it is not a replacement for). Do not rely on `docker-compose.yml`'s `SPRING_JPA_HIBERNATE_DDL_AUTO: update` override (verified this session — Phase 2 Plan 03's compose file sets this only for the local dev stack, per `02-03-PLAN.md` line ~181) to paper over this for local testing and then forget the production gap exists.

**Warning signs:** Any plan that treats `ActivityLogEntity`'s table as "just works" because local `docker compose up` or `./gradlew test` succeeded — both of those environments have `ddl-auto` set to something permissive; only the real Postgres deploy target doesn't.

### Pitfall 1: Retry/DLT path swallows the D-05 duplicate case, polluting the DLT with non-poison messages

**What goes wrong:** If the idempotency check is implemented as a naive `if (exists) throw new DuplicateEventException()` (reaching for an exception-based control-flow pattern because "that's how errors are usually signaled"), `DefaultErrorHandler` sees it as any other listener failure — retries it 3 times (D-04), then routes the perfectly-expected duplicate to `kanban.activity.dlt`. This directly contradicts D-05 ("MUST be caught inside the consumer as a silent no-op ... must never be allowed to throw into the retry/DLT path") and pollutes the dead-letter topic, defeating RELY-02's "prove poison messages land on the DLT" test — a redelivery test now also produces a DLT entry, making it hard to distinguish real poison messages from routine duplicates when reading the DLT.

**Why it happens:** Exception-based signaling is idiomatic elsewhere in this codebase (`AppEntityNotFoundException`, `AppAccessDeniedException`) — it's tempting to reach for the same pattern here without noticing the Kafka listener's exception semantics are fundamentally different (any listener exception is retry-then-DLT fodder, not an HTTP-status-mapped response).

**How to avoid:** The duplicate check and its DB-constraint backstop must both `return` (or otherwise complete normally) rather than throw — see Pattern 1's example `persist()` method: `existsByEventId` returns early, and the backstop `catch (DataIntegrityViolationException e)` catches and swallows rather than rethrowing.

**Warning signs:** Any code path where a duplicate `eventId` results in a checked or unchecked exception propagating out of the `@KafkaHandler`/`@KafkaListener` method.

### Pitfall 2: `JsonDeserializer` trusted-packages misconfiguration breaks all 5 event types at once

**What goes wrong:** Spring Kafka's `JsonDeserializer` refuses to deserialize into application classes unless the sender's package is explicitly trusted (`spring.json.trusted.packages`), as a deserialization-gadget-attack mitigation. If this isn't configured on the consumer side, every single message — not just malformed ones — throws `IllegalArgumentException: The class ... is not in the trusted packages`, which `DefaultErrorHandler` classifies as a deserialization-adjacent failure. Depending on exact exception type, this can either dead-letter every real message immediately (masking as if RELY-01 "works" when actually nothing is being consumed) or retry pointlessly since the error is deterministic and retrying never helps.

**Why it happens:** The producer side (`KafkaEventPublisher`, already built in Phase 2) never needed this configuration — it's a serializer, and serialization has no equivalent trust boundary. This asymmetry between producer and consumer config is easy to miss when copy-adapting Phase 2's Kafka properties block.

**How to avoid:** Explicitly set `spring.kafka.consumer.properties.spring.json.trusted.packages=com.vrudenko.kanban_board.event` (or `*` for a quick local-dev-only shortcut, not recommended for the checked-in default) in `application.properties`/`application-test.properties`. Verify with a real end-to-end message, not just a compiling `@KafkaListener` method signature.

**Warning signs:** A DLT-consumption test (RELY-02) that passes even though its payload is well-formed JSON matching a real event shape — that's a sign the "poison" it's proving is actually a trust-boundary misconfiguration affecting everything, not the deliberately malformed payload D-06 requires.

### Pitfall 3 (carried forward from milestone PITFALLS.md, phase-specific application): Idempotency check-then-insert race under concurrent redelivery

**What goes wrong:** Already covered in milestone-level PITFALLS.md Pitfall 2 (already read this session) — a plain `existsByEventId` + `save()` without a DB unique constraint has a race window under concurrent/duplicate delivery. ACTLOG-03 already locks in the two-layer defense (exists-check + unique constraint), so this phase's job is to implement both layers correctly, not to relitigate whether the constraint is needed.

**How to avoid:** See Pattern 1's example — the unique constraint (Pitfall 0's DDL script) is the actual correctness guarantee; `existsByEventId` is a fast-path optimization only. TEST-02 must exercise the actual constraint-violation path (not just "call the consumer method twice sequentially in one thread," which never reaches the race condition since the first call's transaction commits before the second starts).

## Code Examples

### `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`, wired for this project's exact requirements (D-04/D-07/D-08)

```java
// Source: Spring Kafka reference docs pattern for DefaultErrorHandler/DeadLetterPublishingRecoverer
// [CITED: docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html],
// adapted to this project's KafkaTopics constant and 1-partition/1-replica topology (D-08)
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler activityErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.ACTIVITY_DLT, 0));

        // D-04: 3 retries, ~1s fixed backoff. FixedBackOff(interval, maxAttempts) —
        // maxAttempts=3 means 3 RETRY attempts after the first failure (4 total attempts).
        // Confirm exact semantics against spring-kafka 3.3.6 FixedBackOff Javadoc during
        // planning if "3 retries" must mean exactly 3 total attempts vs. 3 retries + 1 initial.
        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));

        return errorHandler;
    }
}
```

`[CITED: docs.spring.io Spring Kafka reference — "Handling Exceptions" page, confirmed via WebFetch this session]`. By Boot auto-configuration convention, a single `DefaultErrorHandler` `@Bean` in the context is automatically applied to the auto-configured `ConcurrentKafkaListenerContainerFactory` — no manual factory wiring needed unless a custom factory already exists for another reason.

**Non-retryable exceptions:** `DefaultErrorHandler` already treats `DeserializationException`, `MessageConversionException`, and related class-cast/conversion failures as non-retryable by default (confirmed via WebFetch this session) — this satisfies D-04's "Spring Kafka already special-cases deserialization failures as effectively non-retryable" and D-06's requirement that the poison-message test use a genuine deserialization failure, not a rigged well-formed payload.

### `NewTopic` beans (D-07/D-08)

```java
// Source: Spring Kafka reference docs "Provisioning Topics" pattern [CITED]
@Bean
public NewTopic activityTopic() {
    return TopicBuilder.name(KafkaTopics.ACTIVITY).partitions(1).replicas(1).build();
}

@Bean
public NewTopic activityDeadLetterTopic() {
    return TopicBuilder.name(KafkaTopics.ACTIVITY_DLT).partitions(1).replicas(1).build();
}
```

### Testcontainers Kafka setup matching this codebase's module (`org.testcontainers:kafka` 1.21.0, `spring-boot-testcontainers`)

```java
// Source: Spring Boot official Testcontainers docs pattern [CITED:
// docs.spring.io/spring-boot/reference/testing/testcontainers.html, confirmed via WebFetch
// this session] + testcontainers.com Kafka listener guide's Awaitility pattern [CITED],
// adapted to this codebase's AbstractAppE2ETest/no-mocks convention [VERIFIED:
// .planning/codebase/TESTING.md — "What NOT to Mock" section]
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActivityLogConsumerE2ETest extends AbstractAppE2ETest {

    @Container
    @ServiceConnection
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.3.1"));
    // Image tag matches docker-compose.yml's own pinned tag (VERIFIED this session — Phase 2
    // Plan 03 summary names apache/kafka-native:4.3.1) for local/CI/test image-family parity.
    // org.testcontainers.kafka.KafkaContainer (the module already in build.gradle) supports
    // both apache/kafka and confluentinc/cp-kafka image families [CITED: testcontainers-java
    // GitHub docs/modules/kafka.md, confirmed via WebFetch this session] — exact constructor
    // overload not confirmed against the pinned 1.21.0 Javadoc this session, verify at
    // implementation time.

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private ActivityLogRepository activityLogRepository;

    @Test
    void publishedTaskMovedEvent_appearsAsActivityLogRow() {
        var event = new TaskMovedEvent(UUID.randomUUID(), userId, boardId, taskId,
                sourceColumnId, targetColumnId, Instant.now());

        kafkaTemplate.send(KafkaTopics.ACTIVITY, event.eventId().toString(), event);

        // Source: testcontainers.com Kafka listener guide's Awaitility pattern [CITED]
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        Assertions.assertThat(
                                        activityLogRepository.existsByEventId(event.eventId()))
                                .isTrue());
    }
}
```

**Redelivery test (TEST-02) shape:**
```java
@Test
void redeliveredEventId_producesExactlyOneRow() {
    var eventId = UUID.randomUUID();
    var event = new TaskMovedEvent(eventId, userId, boardId, taskId,
            sourceColumnId, targetColumnId, Instant.now());

    kafkaTemplate.send(KafkaTopics.ACTIVITY, eventId.toString(), event);
    kafkaTemplate.send(KafkaTopics.ACTIVITY, eventId.toString(), event); // same eventId, redelivered

    Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() ->
                    Assertions.assertThat(activityLogRepository.findAll())
                            .filteredOn(row -> row.getEventId().equals(eventId))
                            .hasSize(1));
}
```

### Repository shape (READ-01/READ-02/ACTLOG-03)

```java
public interface ActivityLogRepository extends JpaRepository<ActivityLogEntity, String> {
    boolean existsByEventId(UUID eventId);

    Page<ActivityLogEntity> findAllByBoardIdOrderByCreatedAtDesc(String boardId, Pageable pageable);
}
```
Matches the existing `TaskRepository`/`ColumnRepository` convention `[VERIFIED: src/main/java/com/vrudenko/kanban_board/repository/TaskRepository.java:1-11 — "public interface TaskRepository extends JpaRepository<TaskEntity, String>"]` — Spring Data derives both queries from the method name, no custom `@Query` needed.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| `SeekToCurrentErrorHandler` + `DeadLetterPublishingRecoverer` | `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` | Spring Kafka 2.8 (2021) deprecated `SeekToCurrentErrorHandler` | Many older tutorials (pre-2022) still show the deprecated class; `DefaultErrorHandler` is what spring-kafka 3.3.6 (this project's pinned version) actually ships and documents — already flagged as a risk in milestone PITFALLS.md, confirmed still current via this session's WebFetch of the official reference docs |
| `org.testcontainers.containers.KafkaContainer` (Zookeeper-era) | `org.testcontainers.kafka.KafkaContainer` (newer package, KRaft-native, supports `apache/kafka`/`apache/kafka-native`) | Testcontainers ~1.19-1.20 introduced the new `org.testcontainers.kafka` package | `build.gradle` already pins `org.testcontainers:kafka` at BOM-managed 1.21.0 — use the newer package, not the legacy `org.testcontainers.containers.KafkaContainer`, to match `docker-compose.yml`'s KRaft-only, no-Zookeeper topology |

**Deprecated/outdated:** `SeekToCurrentErrorHandler` — do not copy any tutorial using this class name; `DefaultErrorHandler` is its full replacement in the version this project uses.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `FixedBackOff(1000L, 3L)`'s second argument (`maxAttempts`) means exactly 3 retry attempts after the initial failure (D-04's "3 retries"), not 3 total attempts including the first | Code Examples | Off-by-one in retry count before DLT routing; low-impact (still bounded, still eventually dead-letters), but worth confirming against the exact 3.3.6 Javadoc at implementation time rather than assuming |
| A2 | `org.testcontainers.kafka.KafkaContainer`'s exact constructor signature accepts a `DockerImageName` for `apache/kafka-native:4.3.1` the same way it accepts `confluentinc/cp-kafka` images | Code Examples | If the constructor rejects the `apache/kafka-native` tag or requires a different builder method, the Testcontainers test setup needs a small adjustment — not a design-invalidating risk, but not verified against the pinned 1.21.0 API this session (WebFetch results were summarized, not the literal Javadoc) |
| A3 | `org.awaitility:awaitility:4.2.2` is a legitimate, current, non-hallucinated artifact | Standard Stack / Package Legitimacy Audit | If wrong, `build.gradle` resolution fails immediately and loudly at `./gradlew build` — low blast radius, but per this agent's provenance rules must still be gated behind `checkpoint:human-verify` since it was not run through an automated registry check this session |
| A4 | Boot auto-configuration applies a single `DefaultErrorHandler` `@Bean` to the auto-configured Kafka listener container factory without additional manual wiring | Code Examples | If wrong (e.g., this project's `application.properties` Kafka block somehow already customizes the factory in a way that suppresses auto-application), the error handler would silently not apply, and RELY-01/RELY-02 would fail at verification time, not silently in production — self-correcting via the phase's own required DLT-proving test |
| A5 | `spring.kafka.consumer.properties.spring.json.trusted.packages` is the correct property key (vs. a `JsonDeserializer` constructor-arg approach) for this project's property-file-driven Kafka configuration style | Common Pitfalls 2 | If the property key is slightly wrong, every consumed message fails with a trust-boundary exception; caught immediately by TEST-01 (the phase's own required happy-path Testcontainers test), so low risk of reaching production undetected |

**If this table is empty:** N/A — see entries above. Every entry here reflects a WebSearch/WebFetch-sourced generic Spring Kafka/Testcontainers API detail not independently verified against the exact pinned version's Javadoc/source in this session.

## Open Questions

1. **Exact `FixedBackOff` retry-count semantics against spring-kafka 3.3.6 specifically**
   - What we know: `FixedBackOff(interval, maxAttempts)` is the standard shape; WebSearch results consistently show `FixedBackOff(1000L, 2L)` described as "2 retries = 3 total attempts" in one source and other sources use `3L` for "3 retries" without the same total-attempts clarification.
   - What's unclear: Whether D-04's "retries a failing message 3 times" should map to `FixedBackOff(1000L, 3L)` (my Code Examples recommendation) or `FixedBackOff(1000L, 4L)` if "3 retries" is meant as "3 attempts after the first, for 4 total."
   - Recommendation: Confirm against the actual `spring-kafka:3.3.6` Javadoc (not a web-search summary) during planning, or treat this as a `Claude's Discretion` tuning knob per CONTEXT.md's own framing ("exact retry/backoff tuning knobs beyond '3 retries, ~1s fixed'... implementation detail") and pick `FixedBackOff(1000L, 3L)` as the literal reading of "3 retries," documenting the choice in the plan.

2. **Whether `KafkaAdmin.fail-fast` should change from its Boot default for this phase's `NewTopic` beans**
   - What we know: Phase 2 already accepted "Kafka down → mutation still succeeds, publish just logs an error" (verified via Phase 2 Plan 03's own end-to-end proof).
   - What's unclear: Whether adding two `NewTopic` beans changes application startup behavior when Kafka is unreachable at boot (a different moment than "Kafka goes down mid-request").
   - Recommendation: Verify empirically during planning/execution — start the app with Kafka stopped and confirm it still starts (degraded), consistent with Phase 2's existing resilience posture; do not assume this without testing it, since `KafkaAdmin` provisioning failures have historically been noisier at startup than a mid-request publish failure.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker Desktop | Testcontainers-based TEST-01/TEST-02/RELY-02 tests, local `docker compose up` | Not probed this session (Windows dev machine; Docker availability not verified via tool call) | — | If unavailable, Testcontainers tests cannot run locally — CI environment (`.github/workflows`) availability should be confirmed separately during planning, since GitHub Actions runners generally ship Docker by default |
| Local Kafka broker (`docker-compose.yml`, `apache/kafka-native:4.3.1`) | Manual end-to-end verification (optional, beyond the automated test suite) | Established in Phase 2 (verified via Phase 2 Plan 03 summary — stack proven to start healthy) | 4.3.1 | Testcontainers tests are self-contained and don't depend on the local compose stack being up |
| PostgreSQL (real, for the DDL bridge script) | ACTLOG-01's manual DDL step (Pitfall 0) | Not probed this session — depends on whether planning/execution has access to run `psql` against the real deploy target | — | If no direct DB access during planning, the DDL script must still be authored and the runbook documented (matching the optimistic-locking precedent), with actual execution flagged as a human `checkpoint:human-verify` step before merge |

**Missing dependencies with no fallback:** none identified as fully blocking — Docker/Testcontainers availability should be confirmed at the start of execution (not this research pass), since it's foundational to TEST-01/TEST-02/RELY-02.

**Missing dependencies with fallback:** none beyond what's noted above.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | No (new) | Read endpoint reuses existing session-based auth (`@PreAuthorize("isAuthenticated()")`), no new auth surface |
| V3 Session Management | No (new) | No change to session handling |
| V4 Access Control | Yes | `OwnershipVerifierService.verifyOwnershipOfBoard(userId, boardId)` reused unmodified `[VERIFIED: src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java:32-58]` — the read endpoint's sole authorization mechanism |
| V5 Input Validation | Yes | `@PathVariable @NotBlank String boardId` (matches every other controller's existing pattern); `Pageable` parameters bound via Spring's standard `@PageableDefault`/binder, no custom parsing |
| V6 Cryptography | No | No new cryptographic material introduced this phase |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|-----------------------|
| Consumer trusts event payload's `boardId`/`userId` without re-verification (no `SecurityContext` on the listener thread) | Spoofing / Tampering | Already an accepted, documented trade-off from Phase 2's own architecture research (verified this session — milestone ARCHITECTURE.md Anti-Pattern 2 explicitly names this as correct behavior, not a gap): the event was authorized once at publish time via the same `OwnershipVerifierService` chain every mutating endpoint already goes through. The read endpoint's `verifyOwnershipOfBoard` check is the actual last line of defense against a cross-user data leak if a bug ever mis-attributes an event's `boardId` |
| `JsonDeserializer` deserialization-gadget risk if `trusted.packages` is left overly permissive (`*`) | Tampering / Elevation of Privilege | Scope `spring.kafka.consumer.properties.spring.json.trusted.packages` to `com.vrudenko.kanban_board.event` specifically, not `*` — this is a checked-in production property, not a local-dev-only shortcut (see Common Pitfalls 2) |
| Poison/malformed messages on `kanban.activity` (RELY-01/02's own concern) | Denial of Service | `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` isolates a single bad message from blocking the entire consumer — already the phase's core reliability requirement, not an additional security consideration beyond what's already planned |
| `detail` column stores raw event ids as JSON, exposed verbatim via `GET /boards/{boardId}/activity` | Information Disclosure | Already scoped by D-01 (ids only, never user-authored content/names) and by the read endpoint's ownership check — no additional mitigation needed beyond what's already locked in CONTEXT.md |

## Sources

### Primary (HIGH confidence)
- Existing codebase, read directly this session: `entity/BaseEntity.java`, `entity/TaskEntity.java`, `entity/UserEntity.java` (grep for `unique`), `event/ActivityEvent.java`, `event/TaskMovedEvent.java`, `event/TaskCreatedEvent.java`, `event/TaskDeletedEvent.java`, `event/BoardCreatedEvent.java`, `event/ColumnCreatedEvent.java`, `config/KafkaEventPublisher.java`, `constant/KafkaTopics.java`, `constant/ApiPaths.java`, `service/OwnershipVerifierService.java`, `handler/GlobalExceptionHandler.java`, `repository/TaskRepository.java`, `controller/TaskController.java`, `controller/BoardController.java`, `controller/ColumnController.java`, `dto/task_dto/TaskResponseDTO.java`, `support/RecordingActivityEventListener.java`, `build.gradle`, `application.properties`, `application-test.properties`, `docs/plans/backend-modernization/01-kafka-activity-feed.md`, `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql`, `.planning/codebase/TESTING.md`, `.planning/config.json`
- `.planning/phases/02-kafka-foundation-domain-events-move-endpoint/02-01-SUMMARY.md`, `02-02-SUMMARY.md`, `02-03-SUMMARY.md` — Phase 2's actual delivered artifacts and discovered gotchas (async dispatch fix, Docker image fix, docker-compose gotchas)
- `.planning/phases/03-activity-log-consumer-reliability-read-api/03-CONTEXT.md`, `.planning/REQUIREMENTS.md`, `.planning/STATE.md` — this phase's locked decisions and requirements

### Secondary (MEDIUM confidence — official docs, fetched via WebFetch/WebSearch this session, not a pinned-version doc fetch)
- [Handling Exceptions :: Spring Kafka](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) — `DefaultErrorHandler`/`FixedBackOff`/`DeadLetterPublishingRecoverer` API shape, non-retryable exception defaults
- [Testcontainers :: Spring Boot](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html) — `@ServiceConnection` KafkaContainer patterns
- [testcontainers-java kafka module docs](https://github.com/testcontainers/testcontainers-java/blob/main/docs/modules/kafka.md) — `org.testcontainers.kafka.KafkaContainer` image support (`apache/kafka`, `apache/kafka-native`)
- [Testing Spring Boot Kafka Listener using Testcontainers](https://testcontainers.com/guides/testing-spring-boot-kafka-listener-using-testcontainers/) — Awaitility polling pattern for async consumer assertions
- [DeadLetterPublishingRecoverer API docs](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/DeadLetterPublishingRecoverer.html) — `destinationResolver` `BiFunction<ConsumerRecord, Exception, TopicPartition>` shape

### Tertiary (LOW confidence — general web search, not cross-verified against a second independent source)
- Various Medium/blog posts on `DataIntegrityViolationException`-based idempotent-consumer patterns (Conduktor, dev.to, LinkedIn) — used only to confirm the "catch-on-insert" alternate approach is a real, named pattern in the wild, not for any specific numeric/API claim
- `org.awaitility:awaitility` version/legitimacy — training knowledge only, not independently verified this session (see Assumptions Log A3)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH for already-adopted dependencies (verified in Phase 2's own STACK.md against the pinned BOM); LOW for the one new candidate (`awaitility`, unverified this session)
- Architecture: HIGH — directly extends Phase 2's already-built, already-verified `ActivityEvent`/`KafkaEventPublisher`/`KafkaTopics` foundation, read directly this session
- Pitfalls: HIGH for Pitfall 0 (the `ddl-auto`/DDL-bridge finding — verified directly by reading `application.properties`/`application-test.properties` and cross-referencing the existing optimistic-locking precedent); MEDIUM for the Spring Kafka-specific pitfalls (official docs, not pinned-version-verified)

**Research date:** 2026-08-02
**Valid until:** 30 days (stable Spring Kafka/Testcontainers API surface; re-verify if `build.gradle`'s Boot BOM version changes)
