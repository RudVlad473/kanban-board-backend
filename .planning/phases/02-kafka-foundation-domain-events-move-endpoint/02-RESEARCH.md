# Phase 2: Kafka Foundation, Domain Events & Move Endpoint - Research

**Researched:** 2026-08-01
**Domain:** Kafka producer wiring (docker-compose KRaft healthcheck mechanics, `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`) and the `PATCH /tasks/{taskId}/move` endpoint, on an existing layered Spring Boot 3.5/Java 21 REST API
**Confidence:** MEDIUM-HIGH (codebase-verified findings are HIGH; Docker/KRaft-image-internals findings are MEDIUM — cross-checked against the image's own Dockerfile, not just tutorials; generic Spring Kafka facts are MEDIUM, official docs)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Kafka-down resilience**
- **D-01:** Task/Board/Column mutations always succeed at the HTTP level even if the Kafka broker is unreachable at publish time — the activity log falls behind, the primary write path is never blocked. Matches the epic's own framing (decoupling the write path from a slower/optional side effect) and research's explicit recommendation. — **Reversibility:** costly — flipping this later (making publish failures roll back the mutation) would require reintroducing publish into the transactional boundary and re-testing every mutation path's failure semantics.
- **D-02:** A failed publish attempt is logged (SLF4J), not silently swallowed — attach a `.whenComplete`/failure callback to the async publish so a broker outage leaves a visible trace, even though the HTTP request still succeeds.
- **D-03:** Local dev startup sequencing: the `kafka` service in `docker-compose.yml` gets a healthcheck (polling its own broker readiness), and the `app` service uses `depends_on: kafka: condition: service_healthy`. This gives "wait a bounded time for Kafka to become reachable, then fail if it doesn't" using Kafka's own healthcheck as the readiness signal — no app-level polling/retry code needed for this. If the healthcheck never passes within its configured retries/timeout, Compose marks `kafka` unhealthy and the `app` container never starts.

**Move endpoint scope**
- **D-04:** `PATCH /tasks/{taskId}/move` only reassigns the task's column — no position/order concept. Confirmed via Phase 1 research that no position/order field exists anywhere in the entity layer today; adding one is out of scope for Epic 1. — **Reversibility:** reversible — a future ordering phase adds a new field and reorder logic without needing to touch this endpoint's existing column-reassignment behavior.
- **D-05:** A moved task lands with no defined position within its target column — matches the current reality that tasks aren't ordered at all today (GET endpoints return whatever order the DB gives back).

### Claude's Discretion
- Exact healthcheck command/probe for the `kafka` service in docker-compose.yml (e.g., broker API check vs. a lightweight probe) — pick whatever is idiomatic for the `apache/kafka-native` image. **Resolved by this research — see Architecture Patterns, Pattern 1: the JVM-tutorial `kafka-broker-api-versions.sh` pattern does NOT apply to this image (no JVM present); a TCP-connect probe is the only mechanism that reliably works.**
- Exact retry/timeout window for the healthcheck (interval, retries, start_period) — pick a reasonable bound (e.g., 30-60s total) for local dev, not tuned for production.
- Log level/format for the publish-failure callback — SLF4J is confirmed, exact message shape is Claude's call.
- `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` implementation details (not discussed directly, but strongly recommended by research as the mechanism that satisfies D-01/D-02 without a dual-write gap) — planner/researcher to confirm this is still the right mechanism during planning. **Confirmed — see Architecture Patterns, Pattern 2.**

### Deferred Ideas (OUT OF SCOPE)
- **Task ordering/position within a column** (drag-and-drop reorder) — raised during the move-endpoint discussion. Not in Epic 1's spec, not in current REQUIREMENTS.md. Would need a new position field on `TaskEntity`, reorder logic for sibling tasks, and likely its own migration. Explicitly deferred to a future milestone.
- **Production (EC2) Kafka deployment** — already tracked as v2-deferred in REQUIREMENTS.md (`KAFKA-V2-01`); the deploy pipeline structurally doesn't use docker-compose at all today (`.github/workflows/deploy.yml` does a single `docker run` of the app image, not `docker compose up`).

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| KAFKA-01 | `docker-compose.yml` at repo root provides `postgres`, `kafka` (native KRaft image, no Zookeeper), and the app itself | Architecture Patterns, Pattern 1 (verified env-var set from `apache/kafka`'s own repo + verified `apache/kafka-native` has no JVM, so healthcheck must be TCP-based) |
| KAFKA-02 | `spring-kafka` and `org.testcontainers:kafka` (+ `spring-boot-testcontainers`) added to `build.gradle`, BOM-managed | Standard Stack (carried forward from milestone STACK.md, re-verified this session against Maven Central tag availability is not needed — versions come from the already-primary-sourced BOM read) |
| EVENT-01 | Five typed domain event records in a new `event` package | Architecture Patterns, Pattern 3 — includes a concrete finding: every event needs `boardId`, not just "the relevant entity id(s)," because Phase 3's consumer cannot re-derive it without breaking the "consumer never re-verifies ownership" boundary |
| EVENT-02 | `TaskService`/`BoardService`/`ColumnService` publish via `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` | Architecture Patterns, Pattern 2 (confirms exact idiom, resolves the open question about which component owns the `KafkaTemplate.send()` call) and Common Pitfalls #1 (ambient-transaction dependency verified against this codebase's actual `@Transactional` placement) |
| MOVE-01 | `PATCH /tasks/{taskId}/move` moves a task to a target column, wired through `TaskService`, publishes `TaskMovedEvent` | Common Pitfalls #4 (routing conflict — verified by reading `TaskController.java`/`SubtaskController.java`: no existing controller in this codebase maps a flat, non-board/column-nested route) and Code Examples |
| MOVE-02 | Reuses the existing explicit `@Version` check-before-mutate convention | Code Examples (`TaskService.moveToColumn`, directly modeled on `TaskService.updateById`, verified lines 65-99) |
| MOVE-03 | Rejects (400/403) a move where the target column belongs to a different board | Code Examples (`TaskService.moveToColumn` — explicit board-id equality check, reuses existing `IllegalArgumentException` → 400 handler, verified in `GlobalExceptionHandler.java`) |

</phase_requirements>

## Summary

This phase has two genuinely distinct research gaps beyond what milestone-level research already covered, and both are resolved here with codebase- or source-verified answers rather than assumptions.

First, the exact `apache/kafka-native` healthcheck mechanics: this image is **not** a JVM image — its Dockerfile (read directly from `apache/kafka`'s own repo this session) shows a multi-stage build that compiles Kafka to a native binary with GraalVM and ships it on a bare `alpine:latest` base with no `java` binary at all. Every "use `kafka-broker-api-versions.sh`" healthcheck pattern found in tutorials targets the *other* image (`apache/kafka`, the JVM one) and will not work here — there's no JVM to run the script's classpath. The reliable healthcheck for this specific image is a bare TCP-connect probe against the broker's listener port using `bash`'s `/dev/tcp` pseudo-device (bash is confirmed present in the runtime image). The official `apache/kafka` repo's own single-node KRaft compose example defines no healthcheck at all — this gap is real, not an oversight in the tutorials.

Second, the exact `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` shape: the listener method **is** where `KafkaTemplate.send()` and its failure callback live — there is no separate relay/outbox component. One domain event class can and should serve double duty as both the Spring `ApplicationEvent` payload and the Kafka wire message (no separate "internal vs. wire" class pair is needed). A single `@TransactionalEventListener` method typed against a common sealed interface can receive all five event types through one listener, using Spring's supertype-based event dispatch.

Beyond the two assigned research gaps, direct codebase reading surfaced two additional, concrete, phase-blocking findings that the milestone-level research could not have caught (it never opened these specific files together): (1) **none of this codebase's four existing controllers map a route that isn't nested under `/boards/{boardId}/columns/{columnId}/tasks/{taskId}`** — the epic's literal `PATCH /tasks/{taskId}/move` (flat, no board/column path segments) cannot be added as a method on the existing `TaskController` class, because Spring composes class-level and method-level `@RequestMapping` paths additively; it needs a new controller class. (2) **`TaskService.save`, `BoardService.save`, and `ColumnService.save` are not themselves `@Transactional`** — they rely entirely on their callers' ambient transaction (`ColumnService.addTaskByColumnId`, `BoardService.addColumnByBoardId`, `UserService.addBoardByUserId`, all of which *are* `@Transactional`). Publishing via `ApplicationEventPublisher` from inside these non-annotated methods is safe today only because every real call path happens to run inside an ambient transaction — but this is a load-bearing implicit assumption a future refactor could silently break (the event would just never fire — no error, no log, since `@TransactionalEventListener`'s default `fallbackExecution=false` means "no active transaction" = "skip silently").

**Primary recommendation:** Use a `bash /dev/tcp` TCP-connect healthcheck for the `kafka` service (not a Kafka-admin-command probe); publish all five domain events through one sealed-interface-typed `@TransactionalEventListener(phase = AFTER_COMMIT)` method that owns both the `KafkaTemplate.send()` call and its failure-logging callback; capture `boardId` on every event at construction time (before any delete, not after); and put the new `PATCH /tasks/{taskId}/move` endpoint in a small new controller class mapped directly under `ApiPaths.TASKS`, not as a method on the existing nested `TaskController`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Local Kafka broker (docker-compose) | Infrastructure / Local Dev | — | New `kafka` service alongside existing `postgres`; zero application code, pure infra config |
| Domain event definitions (`event` package) | API / Backend (data contract) | — | Plain records, no framework annotations — a wire/application-event contract shared by producer and (future) consumer |
| Event publishing (`ApplicationEventPublisher` call sites) | API / Backend (Service layer) | — | Lives at the tail of existing `@Transactional` mutating methods in `TaskService`/`BoardService`/`ColumnService` — same tier that already owns persistence |
| Kafka send (`KafkaTemplate.send` + failure callback) | API / Backend (new cross-cutting component) | — | A single new `@Component` (`@TransactionalEventListener`), not embedded in the domain services — keeps `TaskService` etc. free of direct Kafka API calls, consistent with milestone ARCHITECTURE.md's "producer append, not a new layer in the request path" framing |
| `PATCH /tasks/{taskId}/move` (new endpoint) | API / Backend (Controller + Service) | — | Same tier as every other mutating endpoint; requires a **new controller class** (see Common Pitfalls #4) because of a routing-path constraint, not a new architectural tier |
| Ownership verification for move (task + target column) | API / Backend (Service, reusing `OwnershipVerifierService`) | — | Existing chain (`verifyOwnershipOfTask`, `verifyOwnershipOfColumn`) already covers both checks needed; no new authorization mechanism |
| Database / Storage | Database | — | No schema change this phase — `TaskEntity.column` reassignment uses the existing `@ManyToOne` FK; no new tables (that's Phase 3's `activity_log`) |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.springframework.kafka:spring-kafka` | **3.3.6** (BOM-managed — no explicit version string) `[VERIFIED: milestone STACK.md, primary source — spring-boot-dependencies build.gradle at git tag v3.5.0]` | Producer abstraction (`KafkaTemplate`) for this phase; consumer (`@KafkaListener`) is Phase 3 | Already confirmed against Spring Boot 3.5.0's own pinned BOM in milestone research — not re-derived here, carried forward as still current |
| `apache/kafka-native` (Docker image) | **4.3.1** is the current tag as of this session `[VERIFIED: Docker Hub tags page, fetched this session]` — supersedes milestone STACK.md's `4.1.2` suggestion, which was already several releases behind at research time | Single-node KRaft local broker | Milestone research flagged the exact tag as worth reconfirming; 4.3.1 is current, `latest` also resolves to it |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.testcontainers:kafka` | BOM-managed → 1.21.0 `[VERIFIED: milestone STACK.md, primary source]` | Declared in `build.gradle` this phase per KAFKA-02, but not exercised by any test until Phase 3 | `testImplementation`, no version string |
| `org.testcontainers:junit-jupiter` | BOM-managed | Same as above — declared, unused until Phase 3 | `testImplementation` |
| `org.springframework.boot:spring-boot-testcontainers` | BOM-managed | Same as above — declared, unused until Phase 3 | `testImplementation` |

### Alternatives Considered

No new alternatives beyond what milestone STACK.md already evaluated (KRaft vs. Zookeeper, `apache/kafka-native` vs. `apache/kafka` JVM image, Testcontainers vs. `@EmbeddedKafka`) — those decisions are locked in at the milestone level and this phase does not revisit them.

**Installation:**
```groovy
// build.gradle additions — no version strings, BOM-managed
implementation 'org.springframework.kafka:spring-kafka'

testImplementation 'org.testcontainers:kafka'
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
```

**Version verification:** Package coordinates (`org.springframework.kafka:spring-kafka`, `org.testcontainers:kafka`) are Maven/Gradle artifacts, not npm packages — the `gsd-tools package-legitimacy check` seam is npm/PyPI/crates-scoped and does not cover this ecosystem (confirmed this session: running it against `spring-kafka` returns `does-not-exist` because it queries the npm registry, which is the wrong registry entirely for a Gradle project). These are not obscure or hallucination-risk names: `spring-kafka` is the official Spring Framework project module (`spring-projects/spring-kafka` on GitHub, part of the Spring portfolio) and `org.testcontainers:kafka` is the official Testcontainers project module — both already primary-source-verified in milestone STACK.md by reading the actual `spring-boot-dependencies` BOM file at its git tag, which is a stronger verification than a registry-existence check would provide anyway.

## Package Legitimacy Audit

This phase's new dependencies are Maven Central / Gradle coordinates (`org.springframework.kafka:spring-kafka`, `org.testcontainers:kafka`, `org.testcontainers:junit-jupiter`, `org.springframework.boot:spring-boot-testcontainers`), not npm/PyPI/crates packages — the `package-legitimacy check` seam does not support this ecosystem (verified this session: it queries the npm registry regardless of the `--ecosystem` flag's actual target, so it returns a false `does-not-exist`/`SLOP` verdict for a legitimate Gradle coordinate). This is a tooling gap, not a signal about the package.

| Package | Registry | Age | Downloads | Source Repo | Verdict | Disposition |
|---------|----------|-----|-----------|-------------|---------|-------------|
| `org.springframework.kafka:spring-kafka` | Maven Central | Long-established (part of Spring portfolio since 2014) | N/A (Maven Central doesn't publish download counts the way npm does) | `github.com/spring-projects/spring-kafka` | Manually verified: official Spring project, version 3.3.6 confirmed present in Spring Boot 3.5.0's own dependency-management BOM (primary source, read directly at milestone research time) | Approved |
| `org.testcontainers:kafka` | Maven Central | Long-established (Testcontainers core project) | N/A | `github.com/testcontainers/testcontainers-java` | Manually verified: official Testcontainers project module, version 1.21.0 confirmed present in the same BOM | Approved |
| `org.testcontainers:junit-jupiter` | Maven Central | Long-established | N/A | `github.com/testcontainers/testcontainers-java` | Same as above | Approved |
| `org.springframework.boot:spring-boot-testcontainers` | Maven Central | Shipped as part of Spring Boot itself since 3.1 | N/A | `github.com/spring-projects/spring-boot` | Same BOM, same primary source | Approved |

**Packages removed due to [SLOP] verdict:** none — the tool's `SLOP` verdicts on these packages are a false positive caused by ecosystem mismatch (npm lookup against Maven coordinates), documented above, not a real legitimacy concern.
**Packages flagged as suspicious [SUS]:** none.

## Architecture Patterns

### System Architecture Diagram

```text
┌──────────────────────────────────────────────────────────────────────────┐
│  HTTP Controllers (existing: Board/Column/Task/Subtask)                  │
│  + NEW: a controller mapped directly under /tasks (see Pitfall #4)       │
│      PATCH /tasks/{taskId}/move                                          │
└───────────────────────────────┬──────────────────────────────────────────┘
                                 ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  Service layer (@Transactional mutating methods)                         │
│  TaskService.moveToColumn (NEW) — verify task ownership, verify target   │
│  column ownership, reject cross-board (400), explicit @Version check,    │
│  mutate, save, flush                                                     │
│         │                                                                 │
│         ▼ eventPublisher.publishEvent(new TaskMovedEvent(...))           │
│  (same call shape added to TaskService/BoardService/ColumnService'       │
│   existing save/updateById/deleteById methods)                           │
└───────────────────────────────┬──────────────────────────────────────────┘
                                 ▼  (still inside the ambient @Transactional
                                     boundary — nothing sent to Kafka yet)
┌──────────────────────────────────────────────────────────────────────────┐
│  Transaction commits (JDBC)                                              │
└───────────────────────────────┬──────────────────────────────────────────┘
                                 ▼  AFTER commit only
┌──────────────────────────────────────────────────────────────────────────┐
│  NEW: KafkaEventPublisher (@Component)                                   │
│  @TransactionalEventListener(phase = AFTER_COMMIT)                       │
│  publish(ActivityEvent event) {                                          │
│      kafkaTemplate.send("kanban.activity", event.eventId(), event)       │
│          .whenComplete((r, ex) -> { if (ex != null) log.error(...); })   │
│  }                                                                        │
└───────────────────────────────┬──────────────────────────────────────────┘
                                 ▼
                    kanban.activity topic (Kafka broker)
                    [consumer side is Phase 3 — out of scope here]

┌──────────────────────────────────────────────────────────────────────────┐
│  docker-compose.yml (NEW, repo root)                                     │
│  postgres  |  kafka (apache/kafka-native, healthcheck: TCP /dev/tcp)     │
│  app (depends_on: kafka: condition: service_healthy)                     │
└──────────────────────────────────────────────────────────────────────────┘
```

### Recommended Project Structure
```
src/main/java/com/vrudenko/kanban_board/
├── event/                              # NEW — 5 records + sealed interface
│   ├── ActivityEvent.java              # sealed interface: eventId, userId, boardId, timestamp
│   ├── TaskCreatedEvent.java
│   ├── TaskMovedEvent.java
│   ├── TaskDeletedEvent.java
│   ├── BoardCreatedEvent.java
│   └── ColumnCreatedEvent.java
├── config/
│   └── KafkaEventPublisher.java        # NEW — @TransactionalEventListener owns KafkaTemplate.send()
├── dto/
│   └── task_dto/
│       └── MoveTaskRequestDTO.java     # NEW — { targetColumnId, version }
├── controller/
│   ├── TaskController.java             # UNCHANGED (still board/column-nested)
│   └── TaskMoveController.java         # NEW — flat /tasks/{taskId}/move route
└── service/
    └── TaskService.java                # MODIFIED — + moveToColumn(), + publish calls in save/updateById/deleteById
```

### Pattern 1: `apache/kafka-native` healthcheck — TCP probe, not a Kafka-admin-command probe

**What:** Every widely-copied Kafka Docker healthcheck pattern (`kafka-broker-api-versions.sh --bootstrap-server ...`) targets the **JVM** `apache/kafka` image. `apache/kafka-native` is a different image entirely: its Dockerfile (`docker/native/Dockerfile` in `apache/kafka`'s own repo, read directly this session) shows a multi-stage build — build stage `ghcr.io/graalvm/graalvm-community:21` compiles Kafka ahead-of-time to a native executable via GraalVM `native-image`; the **runtime** stage is bare `alpine:latest` with only `gcompat`, `bash`, and a few security-patch libs installed, running the compiled native binary via `CMD ["/etc/kafka/docker/run"]`. There is no `java` binary and no `bin/*.sh` script tree in the runtime image — `kafka-broker-api-versions.sh` requires a JVM to execute and simply is not present.

`[VERIFIED: apache/kafka repo, docker/native/Dockerfile, fetched this session]` — multi-stage build confirmed: build stage `ghcr.io/graalvm/graalvm-community:21`, runtime stage `alpine:latest`, installs `gcompat, bash` plus `libcrypto3/libssl3/zlib` security patches, `CMD ["/etc/kafka/docker/run"]`. No JVM, no admin-script tree in the runtime layer.

The official `apache/kafka` repo's own single-node KRaft example compose file (`docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml`) defines **no healthcheck block at all** `[CITED: github.com/apache/kafka, docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml, fetched this session]` — this confirms the milestone STACK.md's LOW-confidence flag was correct to raise: there genuinely is no authoritative reference healthcheck to copy for this image.

Since `bash` is confirmed present in the runtime image, the reliable local-dev healthcheck is a bare TCP-connect probe using bash's `/dev/tcp` pseudo-device against the broker's client listener port — this only requires the TCP stack to accept a connection, which does not depend on any Kafka-specific tooling being present.

**When to use:** The `kafka` service's `healthcheck:` block in the new `docker-compose.yml`.

**Trade-offs:** A bare TCP-connect success only proves the listener socket is open — it does **not** prove KRaft controller election has finished or that the broker can actually serve produce/fetch requests yet (there's a real, if usually short, window in KRaft startup where the port is listening before metadata/leader election settles). This is a known limitation of TCP-only healthchecks, not specific to this image. Mitigate by giving `start_period` and `retries` enough headroom (a few consecutive successful TCP probes across ~15-20s of `start_period`, not just one) so the `app` service doesn't start against a technically-listening-but-not-yet-ready broker. This is "good enough" for local dev per D-03's own framing (not tuned for production).

**Example:**
```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASS}
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data

  kafka:
    image: apache/kafka-native:4.3.1
    hostname: kafka
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: 'broker,controller'
      KAFKA_LISTENERS: 'CONTROLLER://:29093,PLAINTEXT_HOST://:9092,PLAINTEXT://:19092'
      KAFKA_ADVERTISED_LISTENERS: 'PLAINTEXT_HOST://localhost:9092,PLAINTEXT://kafka:19092'
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT'
      KAFKA_CONTROLLER_QUORUM_VOTERS: '1@kafka:29093'
      KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER'
      KAFKA_INTER_BROKER_LISTENER_NAME: 'PLAINTEXT'
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_LOG_DIRS: '/var/lib/kafka/data'
    ports:
      - "9092:9092"      # host-exposed, local-dev-only listener
    volumes:
      - kafka-data:/var/lib/kafka/data   # named volume — Pitfall 6 from milestone PITFALLS.md (state loss)
    healthcheck:
      # apache/kafka-native has NO JVM and NO kafka-broker-api-versions.sh (verified — see
      # Architecture Patterns Pattern 1). A bare TCP-connect against the internal broker
      # listener is the only mechanism guaranteed to work against this specific image.
      test: ["CMD-SHELL", "bash -c 'echo > /dev/tcp/127.0.0.1/19092' || exit 1"]
      interval: 5s
      timeout: 5s
      retries: 8
      start_period: 15s   # ~55s total worst-case bound before Compose gives up — fits D-03's 30-60s guidance

  app:
    build: .
    depends_on:
      kafka:
        condition: service_healthy
      postgres:
        condition: service_started
    environment:
      KAFKA_BOOTSTRAP_SERVERS: kafka:19092
    ports:
      - "8080:8080"

volumes:
  postgres-data:
  kafka-data:
```
`[CITED: env-var names/values for KRaft cross-checked against github.com/apache/kafka docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml, fetched this session — service renamed broker→kafka and internal listener port changed 19092 to match this project's naming, values otherwise structurally identical]` `[ASSUMED: healthcheck test command and timing — reasoned from the confirmed absence of a JVM/admin-script in the image, not copied from an official example, since none exists]`

### Pattern 2: `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` — the listener owns the Kafka send

**What:** Resolves the open question directly: **the `@TransactionalEventListener` method itself calls `KafkaTemplate.send()` and attaches the failure callback** — there is no separate component that owns the actual Kafka interaction. The service method publishes a plain domain event object via `ApplicationEventPublisher.publishEvent(event)` while still inside its `@Transactional` boundary; Spring defers delivery to any `@TransactionalEventListener`-annotated method until the enclosing transaction has committed (default `phase` is already `AFTER_COMMIT`, but the epic spec asks it be stated explicitly — do so). If the method that calls `publishEvent(...)` is not itself `@Transactional` but is invoked synchronously from a caller that is, the ambient transaction still applies (see Common Pitfalls #1 for why this matters here specifically).

`[CITED: docs.spring.io Spring Framework reference, "Transaction-bound Events" section, fetched this session]` — confirms: "the listener method itself performs the side-effecting work," default phase is `AFTER_COMMIT`, and "if no transaction is running, the listener is not invoked at all" unless `fallbackExecution = true` is explicitly set.

**One event class serves both roles — no separate "internal domain event" vs. "Kafka wire event" pair is needed.** Spring's `ApplicationEventPublisher.publishEvent()` accepts any POJO (no marker interface required since Spring 4.2), and the same object can be handed directly to `KafkaTemplate.send(topic, key, event)`, which serializes it via the configured `JsonSerializer`. Building two parallel class hierarchies (an "internal" event and a "wire" event) would be unjustified ceremony for this scope — EVENT-01 asks for five typed records, not five-times-two.

**A single listener method can dispatch all five event types** by typing its parameter against a common `sealed interface ActivityEvent` that all five records implement — Spring's event multicaster resolves listeners by `ResolvableType`, including supertypes, so one `@TransactionalEventListener` method handles every subtype without five near-identical methods.

**When to use:** Every mutating method in `TaskService`, `BoardService`, `ColumnService` that needs to announce a domain event (EVENT-02's full scope: create, update/move, delete on Task; create on Board and Column).

**Trade-offs:** As already documented in milestone PITFALLS.md — this is not full exactly-once delivery; a crash in the narrow window between DB commit and the listener firing loses the event silently. Accepted tradeoff for this scope, not revisited here.

**Example:**
```java
// event/ActivityEvent.java
package com.vrudenko.kanban_board.event;

import java.time.Instant;
import java.util.UUID;

public sealed interface ActivityEvent
        permits TaskCreatedEvent, TaskMovedEvent, TaskDeletedEvent, BoardCreatedEvent, ColumnCreatedEvent {
    UUID eventId();
    String userId();
    String boardId();   // see Pattern 3 — required on every event, not optional
    Instant timestamp();
}
```
```java
// event/TaskMovedEvent.java
package com.vrudenko.kanban_board.event;

import java.time.Instant;
import java.util.UUID;

public record TaskMovedEvent(
        UUID eventId,
        String userId,
        String boardId,
        String taskId,
        String sourceColumnId,
        String targetColumnId,
        Instant timestamp)
        implements ActivityEvent {}
```
```java
// config/KafkaEventPublisher.java
package com.vrudenko.kanban_board.config;

import com.vrudenko.kanban_board.event.ActivityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class KafkaEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private static final String TOPIC = "kanban.activity";

    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivityEvent(ActivityEvent event) {
        kafkaTemplate
                .send(TOPIC, event.eventId().toString(), event)
                .whenComplete(
                        (result, ex) -> {
                            if (ex != null) {
                                log.error(
                                        "Failed to publish {} (eventId={}, boardId={}) to {}",
                                        event.getClass().getSimpleName(),
                                        event.eventId(),
                                        event.boardId(),
                                        TOPIC,
                                        ex);
                            }
                        });
    }
}
```
```java
// inside TaskService.moveToColumn — publish call, same shape reused in save/updateById/deleteById
eventPublisher.publishEvent(
        new TaskMovedEvent(
                UUID.randomUUID(),
                userId,
                targetColumn.getBoard().getId(),
                task.getId(),
                sourceColumnId,
                targetColumn.getId(),
                Instant.now()));
```

### Pattern 3: Every event must carry `boardId`, captured before any delete — not derived by the consumer

**What:** EVENT-01's literal wording ("userId, the relevant entity id(s), a timestamp, a UUID eventId") does not explicitly say `boardId` is mandatory. But milestone ARCHITECTURE.md's own consumer design (Phase 3) requires `ActivityLogEntity.boardId` for every row, and its own Anti-Pattern 2 explicitly forbids the consumer from re-verifying ownership or otherwise touching `OwnershipVerifierService` (no `SecurityContext` on a Kafka listener thread) — meaning the consumer **cannot** look up `boardId` itself; it must already be on the event. Concretely:
- `TaskCreatedEvent`/`TaskMovedEvent`: `boardId` = `column.getBoard().getId()` (or `targetColumn.getBoard().getId()` for a move) — available at construction time from the already-loaded `ColumnEntity`, cheap.
- `TaskDeletedEvent`: **must** capture `boardId` (and any other needed fields) from the loaded `TaskEntity` **before** `taskRepository.deleteById(...)` executes — once the row is gone, there is nothing left to derive it from. This is a real ordering constraint on `TaskService.deleteById`, not a style preference.
- `BoardCreatedEvent`: `boardId` is the newly created board's own id — trivial.
- `ColumnCreatedEvent`: `boardId` = `board.getId()`, already passed into `ColumnService.save(dto, board)` as a parameter — trivial.

**When to use:** Every one of the five event record constructors.

**Trade-offs:** None — this costs nothing extra to capture at publish time; it only costs a bug in Phase 3 (an activity row with no way to resolve which board it belongs to, or a consumer that has to break the ownership-boundary rule to look it up) if skipped now.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Kafka-down retry/backoff before the app container starts | Custom app-level polling loop against `localhost:9092` on startup | `docker-compose.yml` `healthcheck:` + `depends_on: condition: service_healthy` (D-03, already locked) | Compose's own health-gate mechanism does exactly this; app-level polling code would duplicate infrastructure Compose already provides |
| Guaranteeing Kafka publish only after DB commit | A hand-rolled `TransactionSynchronizationManager.registerSynchronization(...)` callback | `@TransactionalEventListener(phase = AFTER_COMMIT)` | This is the exact framework feature this pattern exists for; hand-rolling `TransactionSynchronizationManager` registration reimplements it with more surface area for bugs |
| Cross-board move validation | A new exception class / new `GlobalExceptionHandler` handler | Existing `IllegalArgumentException` → 400 mapping (`GlobalExceptionHandler.java` line 38-41, already present) | Zero new exception-handling code needed; the existing generic 400 handler is a correct fit for "the request is well-formed and authorized, but violates a domain invariant" |
| Ownership check for the move's target column | A bespoke check against `ColumnRepository` | `OwnershipVerifierService.verifyOwnershipOfColumn(userId, targetColumnId)` (existing, unmodified) | Already returns `Pair<UserEntity, ColumnEntity>` and already throws the correct 404/401 exceptions for "column doesn't exist" / "user doesn't own it" — reusing it for the target column is the same call already used for the source column path everywhere else in the codebase |

**Key insight:** every piece of new machinery this phase needs (transactional-safe publish, cross-board rejection, target-column authorization) already has a standard, in-framework or in-codebase answer — no phase-specific custom infrastructure is warranted.

## Common Pitfalls

### Pitfall 1: Publishing from a non-`@Transactional` method silently depends on the caller's ambient transaction
**What goes wrong:** `TaskService.save`, `BoardService.save`, and `ColumnService.save` are **not** annotated `@Transactional` themselves `[VERIFIED: TaskService.java:34, BoardService.java:79-86, ColumnService.java:62-69 — read directly this session; none of the three carry a @Transactional annotation]`. They rely entirely on their real callers being `@Transactional` (`ColumnService.addTaskByColumnId` line 71-77, `BoardService.addColumnByBoardId` line 32-38, `UserService.addBoardByUserId` line 75-81 — all confirmed `@Transactional` this session). If `eventPublisher.publishEvent(...)` is added inside one of these non-annotated `save()` methods, it works correctly **today** only because every call path happens to run inside an ambient transaction opened further up the stack. If any future code calls `taskService.save(...)` directly without going through an `@Transactional` caller (a new admin/seed script, a different test harness, a future refactor), `@TransactionalEventListener`'s default `fallbackExecution = false` means the event is silently never delivered — no exception, no log line, nothing to notice.
**Why it happens:** Spring's transactional proxy only demarcates a transaction at the method actually annotated `@Transactional`; every other method invoked synchronously on the same thread just participates in whatever transaction (if any) is already active. This is normal, correct Spring behavior — the risk is entirely in *this codebase's specific choice* to leave `save()` methods unannotated while depending on it implicitly.
**How to avoid:** Either (a) document this dependency explicitly with a code comment at each `save()` method (cheapest), or (b) add `@Transactional` directly to `TaskService.save`/`BoardService.save`/`ColumnService.save` themselves so the event-publish guarantee doesn't depend on caller behavior (`@Transactional` with default `REQUIRED` propagation is a no-op if a transaction is already active, so this is a safe, zero-behavior-change addition). Option (b) is the more defensible choice for a codebase this size — it converts an implicit invariant into an explicit, self-contained guarantee.
**Warning signs:** A `save()`/mutating method that calls `eventPublisher.publishEvent(...)` but has no `@Transactional` of its own — grep for this combination during code review.
**Phase to address:** This phase (producer/event-publishing phase) — the same phase introducing the publish calls.

### Pitfall 2: `apache/kafka-native`'s missing JVM breaks copy-pasted healthcheck tutorials
**What goes wrong:** Copying any `kafka-broker-api-versions.sh`-based healthcheck (the overwhelmingly most common pattern in web tutorials) into this project's compose file will fail every single health check attempt, because that script doesn't exist in this image's filesystem — see Architecture Patterns Pattern 1.
**Why it happens:** The vast majority of Kafka Docker tutorials predate or simply don't mention `apache/kafka-native` (GA since Kafka 3.8, explicitly experimental/local-dev-only) — they document the JVM `apache/kafka` image, which does ship the full `bin/*.sh` admin script tree.
**How to avoid:** Use the TCP-probe healthcheck from Pattern 1. If the team later wants a more Kafka-aware readiness signal, that would require adding a JVM-based sidecar or switching to the JVM `apache/kafka` image — not a fix worth making for this phase's local-dev scope.
**Warning signs:** `docker compose up` never marks `kafka` healthy; `docker inspect` on the `kafka` container shows the healthcheck command exiting non-zero every attempt, specifically with a "file not found" / "no such file or directory" style error for the script path.
**Phase to address:** This phase (`docker-compose.yml` authoring).

### Pitfall 3: Adding `spring-kafka` to `build.gradle` changes behavior of the existing `@SpringBootTest`-based test suite before Phase 3's Testcontainers wiring exists
**What goes wrong:** `TaskServiceTest`, `TaskLockingE2ETest`, and every other `@SpringBootTest`-annotated test in this codebase currently boot the full Spring context against H2 with no Kafka broker present `[VERIFIED: TaskServiceTest.java:19, TaskLockingE2ETest.java:16 — both read directly this session; both use plain @SpringBootTest, no Kafka-related test infrastructure exists yet]`. Once `spring-kafka` is on the classpath, Spring Boot's `KafkaAutoConfiguration` creates a real `KafkaTemplate`/`ProducerFactory` bean pointed at `spring.kafka.bootstrap-servers` (defaulting to `localhost:9092`) for every test context, including tests that exercise `TaskService.updateById`/`moveToColumn` — which now call `eventPublisher.publishEvent(...)` on every successful mutation. In CI or on a machine without `docker compose up` already running, there is no broker at `localhost:9092`, so every such publish attempt's underlying producer will repeatedly try (and fail) to fetch cluster metadata in the background.
**Why it happens:** `KafkaTemplate.send()` returning immediately (async) means the test method itself won't block or fail — but the producer's internal IO thread will retry against a nonexistent broker for a while (governed by `request.timeout.ms`/`delivery.timeout.ms` defaults), which is background log noise now and a slow-CI problem later if any test path ever waits on Kafka side-effects.
**How to avoid:** For this phase, since D-01 already establishes "mutations succeed even if Kafka is unreachable," the existing test suite's behavior is *functionally* unaffected (all assertions are on the HTTP/service-layer response, not on Kafka delivery) — but the planner should decide explicitly whether to (a) accept the log noise/slower context shutdown for this phase and let Phase 3's Testcontainers wiring clean it up, or (b) set a short producer timeout (`spring.kafka.producer.properties.max.block.ms`/`request.timeout.ms`) in `application-test.properties` now so failed-publish attempts fail fast rather than retrying for the default ~2 minutes (`delivery.timeout.ms` default is 120000ms), which would otherwise make every mutating test measurably slower. Given this project's existing test-speed-conscious conventions (query-count assertions, etc.), (b) is the more consistent choice.
**Warning signs:** Test suite wall-clock time increases noticeably after this phase's `save()`/`updateById()`/`moveToColumn()` changes land, even though no test assertion changed.
**Phase to address:** This phase — the fix (a bounded producer timeout property in the test profile) is a one-line `application-test.properties` addition, cheap to include now rather than debug later.

### Pitfall 4: The flat `PATCH /tasks/{taskId}/move` route cannot be added to the existing `TaskController`
**What goes wrong:** `TaskController`'s class-level `@RequestMapping` is `ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS + ApiPaths.COLUMN_ID + ApiPaths.TASKS` `[VERIFIED: TaskController.java:19-24]` — i.e. every method on this controller is served under `/boards/{boardId}/columns/{columnId}/tasks/...`. Spring composes class-level and method-level `@RequestMapping`/`@PatchMapping` paths **additively**; there is no way for a method on this controller to serve a path that doesn't start with that prefix. `SubtaskController` follows the identical nested pattern one level deeper `[VERIFIED: SubtaskController.java:23-30]`. REQUIREMENTS.md's MOVE-01 and the epic spec both specify the literal flat path `PATCH /tasks/{taskId}/move` (no `boardId`/`columnId` segments) — this is a locked requirement, not a discretion point, and it structurally cannot live on the existing `TaskController` class.
**Why it happens:** Every other mutating endpoint in this codebase is scoped by its full ownership chain in the URL itself; `/tasks/{taskId}/move` is the first endpoint in the entire codebase that identifies its target resource by a single flat id, breaking from that convention (the move destination, not the current location, is what the request body carries — there's no natural "column-scoped" URL for an operation whose entire point is changing the column).
**How to avoid:** Add a small new controller class, e.g. `TaskMoveController`, `@RequestMapping(ApiPaths.TASKS)` at the class level, with a single `@PatchMapping(ApiPaths.TASK_ID + ApiPaths.MOVE)` method (recommend adding `public static final String MOVE = "/move";` to `ApiPaths.java`, consistent with the existing all-constants convention — no bare string literals). This keeps route composition working correctly without touching `TaskController`'s existing nested mapping.
**Warning signs:** Attempting to add `@PatchMapping(ApiPaths.TASK_ID + "/move")` directly to `TaskController` and getting `/boards/{boardId}/columns/{columnId}/tasks/{taskId}/move` instead of the required `/tasks/{taskId}/move` when testing the route.
**Phase to address:** This phase (the phase literally adding the move endpoint) — must be resolved during planning, not discovered during implementation.

## Code Examples

### `MoveTaskRequestDTO`
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
Mirrors `UpdateTaskRequestDTO`'s shape (`[VERIFIED: UpdateTaskRequestDTO.java:15-34]` — `@Getter @Setter @Builder @EqualsAndHashCode @JsonInclude(NON_NULL)`, `@NotNull private Long version`) minus the `BaseTask` interface (not applicable — this DTO doesn't carry task fields) and minus the "at least one field populated" check (both fields are always required here).

### `TaskService.moveToColumn`
```java
/**
 * Reuses the exact explicit version-check-before-mutate pattern from {@link #updateById} (see its
 * Javadoc for why the explicit check is required in addition to {@code @Version}). Also verifies
 * ownership of the TARGET column (not just the task) and rejects a move across board boundaries —
 * MOVE-03 — before the version check, since "wrong board" is a request-shape problem independent of
 * concurrency, and 400 is the more specific signal to return first.
 */
@Transactional
public TaskResponseDTO moveToColumn(String userId, String taskId, MoveTaskRequestDTO dto) {
    var task = findById(userId, taskId);
    var sourceColumnId = task.getColumn().getId();
    var sourceBoardId = task.getColumn().getBoard().getId();

    var targetColumnPair =
            ownershipVerifierService.verifyOwnershipOfColumn(userId, dto.getTargetColumnId());
    var targetColumn = targetColumnPair.getSecond();

    if (!targetColumn.getBoard().getId().equals(sourceBoardId)) {
        throw new IllegalArgumentException(
                "Cannot move a task to a column on a different board.");
    }

    if (!task.getVersion().equals(dto.getVersion())) {
        throw new OptimisticLockingFailureException(
                "Task was modified by another request, please refetch.");
    }

    task.setColumn(targetColumn);
    taskRepository.save(task);
    entityManager.flush();

    eventPublisher.publishEvent(
            new TaskMovedEvent(
                    UUID.randomUUID(),
                    userId,
                    sourceBoardId,
                    task.getId(),
                    sourceColumnId,
                    targetColumn.getId(),
                    Instant.now()));

    return taskMapper.toTaskResponseDTO(task);
}
```
Field/method names verified against `TaskEntity.java` (`getColumn()`, `getVersion()` — `[VERIFIED: TaskEntity.java:23-38]`), `ColumnEntity.java` (`getBoard()` via `@ManyToOne` `board` field — `[VERIFIED: ColumnEntity.java:27-29]`), and `OwnershipVerifierService.verifyOwnershipOfColumn` returning `Pair<UserEntity, ColumnEntity>` (`[VERIFIED: OwnershipVerifierService.java:60-72]`).

### `TaskMoveController`
```java
package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.security.CurrentUserId;
import com.vrudenko.kanban_board.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
Requires adding `public static final String MOVE = "/move";` to `ApiPaths.java` (`[VERIFIED: ApiPaths.java:6-25]` — every path segment in this file is already a named constant; no bare literals exist today, so `MOVE` should follow the same pattern).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Exact healthcheck `test` command/timing (`bash -c 'echo > /dev/tcp/...'`, interval 5s/retries 8/start_period 15s) | Architecture Patterns, Pattern 1 | If `bash` turns out to be absent in some future image revision, or `/dev/tcp` is disabled in the shell build, the healthcheck command itself needs a fallback (e.g. `wget`/`curl` if present, or a lightweight companion container) — cheap to detect (compose just never goes healthy) and cheap to fix (swap the `test:` line) |
| A2 | `KAFKA_LOG_DIRS` path (`/var/lib/kafka/data`) and internal listener port (`19092`) chosen for this project's compose file | Architecture Patterns, Pattern 1 (Code Example) | Cosmetic only — any valid writable path/unused port works identically; not a correctness risk |
| A3 | Recommending `@Transactional` be added directly to `TaskService.save`/`BoardService.save`/`ColumnService.save` (rather than just documenting the ambient-transaction dependency) | Common Pitfalls #1 | Low risk — `@Transactional` with default `REQUIRED` propagation is a safe no-op when already inside a transaction; worth the planner confirming this doesn't conflict with some other reason these methods were left unannotated (none found this session, but not exhaustively audited beyond the four call sites checked) |
| A4 | Producer timeout property recommendation for `application-test.properties` (Pitfall 3) — exact property name/value not verified against a running test | Common Pitfalls #3 | If the property name is slightly off Spring Boot's actual `spring.kafka.producer.properties.*` prefix convention, it silently doesn't take effect (Kafka client properties are forgiving of unrecognized keys) rather than erroring — worth a quick local `./gradlew test` sanity check during implementation |

**If this table is empty:** N/A — see rows above.

## Open Questions

1. **Should `TaskService.save`/`BoardService.save`/`ColumnService.save` gain `@Transactional`, or should the ambient-transaction dependency just be documented?**
   - What we know: today it works because every real caller is `@Transactional`; a future direct call without one would silently drop the event.
   - What's unclear: whether the project prefers minimal diffs (comment only) over defensive correctness (add the annotation) for this specific phase's scope.
   - Recommendation: add `@Transactional` — it's a zero-risk, one-line-per-method change that converts a currently-implicit guarantee into a structural one, and this phase is already touching these three methods anyway.

2. **Where should `KafkaEventPublisher` live — `config/` or a new `event/` sub-package?**
   - What we know: milestone ARCHITECTURE.md put `KafkaProducerConfig`/`KafkaConsumerConfig` under `config/`; this component is a listener, not pure bean wiring, but it's Kafka-producer-adjacent infrastructure, not a domain service.
   - What's unclear: whether `config/` (infrastructure-flavored) or `event/` (co-located with the records it dispatches) reads better to a reviewer.
   - Recommendation: `config/KafkaEventPublisher.java` — keeps `event/` a pure, dependency-free data package (matches milestone ARCHITECTURE.md's explicit rationale for that package's isolation), consistent with the "config/ holds Kafka producer-side wiring" framing already established in milestone research.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker / `docker compose` | KAFKA-01 (running the new `docker-compose.yml` locally) | Not probed this session (research phase does not execute against the developer's live machine state) — assume present per project's existing `Dockerfile`-based deploy workflow | — | If absent, `./gradlew test`/unit tests still pass (D-01 means mutations don't require a reachable broker); only local manual verification of the Kafka wiring would be blocked until Docker is available |
| A locally running `kafka` container (via the new compose file) | Manual verification of `PATCH /tasks/{taskId}/move` actually producing a message on `kanban.activity` | N/A until this phase's `docker-compose.yml` exists | — | None needed for the automated test suite (Phase 2 has no Kafka-dependent automated test — that's Phase 3's Testcontainers-based test) |

**Missing dependencies with no fallback:** none identified — this phase's automated tests do not require a live broker, by design (D-01).
**Missing dependencies with fallback:** none beyond the above.

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | No — unchanged this phase | Existing session-based auth, untouched |
| V3 Session Management | No — unchanged this phase | Existing Spring Session JDBC config, untouched |
| V4 Access Control | Yes | `MoveTaskRequestDTO`'s move target is authorized via `OwnershipVerifierService.verifyOwnershipOfColumn` (existing, unmodified) before any mutation — same pattern as every other endpoint; the new `TaskMoveController` carries the same `@PreAuthorize("isAuthenticated()")` class-level guard as every other controller |
| V5 Input Validation | Yes | `@NotBlank targetColumnId`, `@NotNull version` on `MoveTaskRequestDTO`, validated via `@Valid` at the controller boundary (Jakarta Validation, existing convention) |
| V6 Cryptography | No — no new cryptographic material this phase | — |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| Confused-deputy move: user A owns both `taskId` and `targetColumnId` individually (different boards), tries to splice a task from one of their boards into an unrelated board they also own, or — more importantly — a column they DON'T own | Elevation of Privilege / Tampering | `OwnershipVerifierService.verifyOwnershipOfColumn(userId, targetColumnId)` already rejects a target column the caller doesn't own (401/`AppAccessDeniedException`, existing, reused unmodified) — MOVE-03's cross-board check (400) is an *additional*, separate domain-invariant check on top of this, not a substitute for it |
| Kafka broker locally exposed on `0.0.0.0:9092` in the dev compose file (same file that could, if misused, become a deploy artifact) | Information Disclosure | Already flagged and mitigated at the milestone level (PITFALLS.md Pitfall 6) — out of this phase's direct scope since the deploy pipeline confirmed not to use `docker compose up` (`.github/workflows/deploy.yml` uses a plain `docker run`), but worth the compose file itself still not binding a wide-open host port unnecessarily; `9092` here is deliberately host-mapped only for local developer tooling (e.g. a Kafka UI), acceptable for local-dev-only usage |
| A malformed/forged event on `kanban.activity` (not this phase's concern directly, but the producer side sets the trust boundary Phase 3 relies on) | Tampering / Spoofing | Every event this phase constructs carries `userId` and `boardId` sourced from the already-ownership-verified entities in the same request — never from client-controlled request body fields directly — so the event payload is trustworthy by construction, which is exactly what lets Phase 3's consumer skip re-verifying ownership (per milestone ARCHITECTURE.md Anti-Pattern 2) |

## Sources

### Primary (HIGH confidence)
- Existing codebase, read directly this session: `TaskService.java`, `BoardService.java`, `ColumnService.java`, `UserService.java`, `TaskEntity.java`, `ColumnEntity.java`, `BaseEntity.java`, `OwnershipVerifierService.java`, `TaskController.java`, `SubtaskController.java`, `ApiPaths.java`, `UpdateTaskRequestDTO.java`, `TaskMapper.java`, `GlobalExceptionHandler.java`, `AppAccessDeniedException.java`, `build.gradle`, `application.properties`, `application-test.properties`, `TaskServiceTest.java`, `TaskLockingE2ETest.java`
- `github.com/apache/kafka`, `docker/native/Dockerfile` — fetched this session, confirms `apache/kafka-native`'s alpine-based runtime with no JVM
- `github.com/apache/kafka`, `docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml` — fetched this session, confirms KRaft env-var set and confirms no healthcheck block exists in the official example

### Secondary (MEDIUM confidence)
- `docs.spring.io` Spring Framework reference, "Transaction-bound Events" — fetched this session, confirms `@TransactionalEventListener` semantics and default phase
- Docker Hub `apache/kafka-native` tags page — fetched this session, confirms `4.3.1` as current tag
- `.planning/research/STACK.md`, `ARCHITECTURE.md`, `PITFALLS.md`, `SUMMARY.md` (milestone-level, this project) — carried forward, not re-derived

### Tertiary (LOW confidence)
- General `WebSearch` results on Kafka Docker healthcheck patterns (used to establish the *common* JVM-image pattern this research then found does NOT apply to `apache/kafka-native` — the search results themselves are not the basis for this phase's recommendation, the Dockerfile read is)

## Metadata

**Confidence breakdown:**
- Kafka healthcheck mechanics: MEDIUM-HIGH — the "no JVM in kafka-native" finding is verified against the image's own Dockerfile (primary source); the exact healthcheck timing values are reasoned/ASSUMED, not copied from an authoritative example (none exists)
- `@TransactionalEventListener` pattern: HIGH — confirmed against official Spring Framework reference documentation, consistent with milestone research
- Move endpoint routing conflict, ambient-transaction dependency, event-boardId requirement: HIGH — all three are direct, this-session codebase reads, not inference
- Package legitimacy: N/A tooling gap, manually resolved via the already-primary-sourced BOM verification from milestone research

**Research date:** 2026-08-01
**Valid until:** 30 days for the codebase-derived findings (stable unless the codebase itself changes); 7-14 days for the `apache/kafka-native` image tag recommendation (moves fast — re-check the current tag at implementation time if this phase is executed more than two weeks after this research)

---
*Phase: 2-Kafka Foundation, Domain Events & Move Endpoint*
*Research completed: 2026-08-01*
