# Project Research Summary

**Project:** kanban-board-backend — v1.1 Kafka event-driven activity feed (Epic 1 of backend modernization plan)
**Domain:** Event-driven audit/activity log grafted onto an existing layered Spring Boot 3.5 / Java 21 REST API
**Researched:** 2026-08-01
**Confidence:** MEDIUM

## Executive Summary

This milestone adds a Kafka-backed activity feed to an already-mature, ownership-scoped Spring Boot Kanban API. Experts build this kind of feature as a **parallel vertical slice**, not a new layer bolted into the request path: existing services (`TaskService`/`BoardService`/`ColumnService`) gain one new collaborator (`KafkaTemplate`) and publish a domain event at the tail of already-`@Transactional` mutating methods, while a brand-new `activitylog` package (consumer -> entity -> repository -> service -> controller) mirrors the existing Board/Column/Task/Subtask vertical-slice pattern exactly. The one genuinely new piece of business logic is `PATCH /tasks/{taskId}/move`, a real missing CRUD capability that also happens to produce the feed's first demoable event.

The recommended approach is deliberately scoped down from "correct distributed systems" to "correct enough for a portfolio-quality, single-EC2, single-consumer deployment, with the tradeoffs explicitly documented": `spring-kafka` 3.3.6 (BOM-managed, no explicit version pin) for producer/consumer, `apache/kafka-native` in KRaft mode (no Zookeeper) for local dev via a new `docker-compose.yml`, and `org.testcontainers:kafka` + `spring-boot-testcontainers` `@ServiceConnection` for a real end-to-end integration test. Five typed domain event records feed a single `kanban.activity` topic and a single `ActivityLogConsumer`, which persists idempotently (UUID `eventId` + DB-level unique constraint) into a new `activity_log` table, exposed read-side via `GET /boards/{boardId}/activity`, reusing `OwnershipVerifierService` unmodified.

The dominant risk across all four research files is the **dual-write gap**: `kafkaTemplate.send()` is not part of the JDBC transaction, so a plain publish-inside-`@Transactional` call can either ghost-publish (DB rolls back after the event already fired) or silently drop the event (DB commits, Kafka send fails, nobody notices). The recommended mitigation — `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` — is a right-sized fix that avoids a full transactional-outbox table while still guaranteeing publish-only-after-commit. Equally important: idempotency must be enforced by a **DB-level unique constraint** on `eventId`, not just an application-level `existsByEventId` check (which has a real check-then-act race under redelivery/rebalance), and the `docker-compose.yml` used for local dev must not be reused unmodified as the EC2 deploy artifact without a review pass for state persistence (named volume) and port exposure.

## Key Findings

### Recommended Stack

The stack addition is small and BOM-managed: declare `spring-kafka`, `org.testcontainers:kafka`, `org.testcontainers:junit-jupiter`, and `org.springframework.boot:spring-boot-testcontainers` with **no explicit version strings**, letting Spring Boot 3.5.0's own dependency-management BOM resolve tested-together versions (`spring-kafka` 3.3.6, `kafka-clients` 3.9.1, Testcontainers 1.21.0 — verified directly against the pinned `spring-boot-dependencies` build file at the `v3.5.0` tag). Local dev runs on `apache/kafka-native` (GraalVM AOT KRaft image, single-node, no Zookeeper) via a new root-level `docker-compose.yml`, following official single-node KRaft compose examples rather than hand-assembling listener config (a common footgun).

**Core technologies:**
- `spring-kafka` (BOM-managed) — producer (`KafkaTemplate`) / consumer (`@KafkaListener`) abstraction over the raw Kafka client — matches Boot 3.5.0's tested dependency set, same no-version-string convention already used for other starters in this codebase
- `apache/kafka-native` (Docker, KRaft mode) — single-node local broker, explicitly required by the epic spec, faster/lighter than the JVM image
- `org.testcontainers:kafka` + `spring-boot-testcontainers` (`@ServiceConnection`) — real containerized broker for integration tests with zero manual `@DynamicPropertySource` wiring, the idiomatic Boot 3.1+ pattern

### Expected Features

**Must have (table stakes):** per-board activity feed, newest-first; human-readable action descriptions; ownership-scoped access via existing `OwnershipVerifierService`; pagination; full coverage of the five spec'd mutation types (Task create/move/delete, Board create, Column create); at-least-once delivery with no visible duplicate rows; poison-message isolation via DLT.

**Should have (differentiators):** the event-driven (Kafka) write path itself as the demonstrable "legitimate reason for Kafka to exist in a CRUD app"; a redelivery test proving idempotency, not just claiming it; a dead-letter topic with a proving test; `PATCH /tasks/{taskId}/move` as independently useful even absent Kafka; cursor/keyset pagination if Spring Data on this Boot version supports it cleanly (worth a quick spike, offset `Pageable` is an acceptable fallback).

**Defer (v2+):** field-level diff logging, a separately deployable consumer microservice, DLT replay/reprocessing tooling, real-time push (WebSocket/SSE), retention/archival policy — all explicitly out of scope per PROJECT.md and disproportionate to this epic's stated goals.

### Architecture Approach

The feature is a producer append (one new `KafkaTemplate` dependency + one publish call per mutating service method) plus an independent consumer vertical slice (`activitylog` package: consumer -> `ActivityLogEntity` -> `ActivityLogRepository` -> `ActivityLogService` -> `ActivityController`) that mirrors the existing Board/Column/Task/Subtask package shape exactly, so it reads as a fifth domain entity to any reviewer already familiar with the codebase, not as new infrastructure bolted on.

**Major components:**
1. `event/` package (5 typed records: `TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`) — plain, dependency-free wire contracts between producer and consumer
2. `TaskService`/`BoardService`/`ColumnService` (modified) + new `TaskService.moveToColumn` — publish one event per successful mutation, publish-after-persist inside the same transaction (via `@TransactionalEventListener(AFTER_COMMIT)`, not a raw inline call)
3. `activitylog` package (`ActivityLogConsumer`, `ActivityLogEntity`, `ActivityLogRepository`, `ActivityLogService`, `ActivityController`) — idempotent persistence and ownership-verified paginated read, isolated from producer thread-context assumptions (no `SecurityContext` on the consumer thread)

### Critical Pitfalls

1. **Dual-write / ghost events from publishing inside `@Transactional`** — publish via `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)`, not a raw `kafkaTemplate.send()` statement inside the mutating method.
2. **Idempotency check-then-act race** — a DB-level `UNIQUE` constraint on `eventId` is the actual correctness guarantee; `existsByEventId` alone is only a fast-path optimization, not safe under concurrent redelivery.
3. **Silently swallowed `KafkaTemplate.send()` failures** — always attach a `.whenComplete`/failure callback; fire-and-forget with no callback makes a broker outage invisible.
4. **DLT configured but never verified** — must have a bounded retry/backoff before dead-lettering and a test that intentionally fails a message and asserts it lands on `kanban.activity.dlt`; "topic exists and compiles" is not "verified."
5. **Dev `docker-compose.yml` reused unmodified as the EC2 deploy artifact** — needs a named volume for Kafka's log dir (state loss on redeploy) and a reviewed port-exposure story (host-exposed `PLAINTEXT` listener reaching the public internet).

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Local Kafka infrastructure
**Rationale:** Zero code dependencies, unblocks all local development and later Testcontainers work — architecture research's own "Suggested Build Order" puts this first.
**Delivers:** `docker-compose.yml` (postgres + `apache/kafka-native` KRaft + app), `spring-kafka`/Testcontainers dependencies added to `build.gradle`, `application.properties` Kafka block.
**Addresses:** table-stakes infrastructure prerequisite for every other feature in this milestone.
**Avoids:** Pitfall 6 (state loss / broker exposure) — build the named volume and internal-vs-external listener split in from the start rather than retrofitting.

### Phase 2: Domain events + producer wiring + move endpoint
**Rationale:** Pure data (event records) has no dependencies on anything else new and can be unit-tested with a mocked `KafkaTemplate` before the consumer exists.
**Delivers:** `event/` package (5 records), `KafkaTemplate` wired into `TaskService`/`BoardService`/`ColumnService` via `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`, `TaskService.moveToColumn` + `PATCH /tasks/{taskId}/move` (reusing the existing `@Version` optimistic-locking convention).
**Addresses:** FEATURES.md table-stakes "coverage of core mutation types" and the differentiator "real missing feature, not manufactured Kafka-bait."
**Avoids:** Pitfall 1 (dual-write/ghost events) and Pitfall 3 (silently swallowed publish failures) — both are producer-phase design decisions, not retrofits.

### Phase 3: Consumer, idempotency, and dead-letter handling
**Rationale:** Needs both an entity to write to (from Phase 2's design) and events to consume; this is the first point a real end-to-end flow exists.
**Delivers:** `ActivityLogEntity` (with unique `eventId` constraint) + `ActivityLogRepository`, `ActivityLogConsumer` (`@KafkaListener`, `existsByEventId` pre-check + unique-constraint dedup, explicit `AckMode` not auto-commit), `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` -> `kanban.activity.dlt`.
**Uses:** Stack elements — `spring-kafka` error-handling APIs, DB-backed idempotency per STACK.md's "Stack Patterns by Variant."
**Implements:** Architecture Pattern 3 (idempotent consumer) and Pattern 4 (single topic, typed event dispatch).
**Avoids:** Pitfall 2 (idempotency race), Pitfall 4 (unverified DLT), Pitfall 5 (rebalance-driven duplicate amplification).

### Phase 4: Read path — activity feed endpoint
**Rationale:** Depends only on Phase 3's entity existing with data in it; can be built/tested against directly-inserted rows even before producer/consumer are fully wired, per architecture research's build order.
**Delivers:** `ActivityLogService` (reusing `OwnershipVerifierService.verifyOwnershipOfBoard` unmodified) + `ActivityController` + `GET /boards/{boardId}/activity`, paginated.
**Addresses:** FEATURES.md table-stakes (per-board feed, ownership-scoped, paginated, human-readable descriptions) and the P2 pagination-strategy decision (offset vs. cursor/keyset — decide deliberately here).

### Phase 5: End-to-end integration testing
**Rationale:** Genuinely end-to-end and necessarily comes last — it's the test that catches any mismatch between the producer (Phase 2) and consumer (Phase 3) that mocked unit tests would miss.
**Delivers:** Testcontainers-based Kafka integration test (publish `TaskMovedEvent` through a real broker -> assert `ActivityLogEntity` row appears, via polling/Awaitility, not immediate assertion), plus an explicit duplicate-redelivery test (publish same `eventId` twice, assert one row).
**Avoids:** the "Testcontainers assertion timing flakiness" pitfall and closes out the "Looks Done But Isn't" checklist items from PITFALLS.md.

### Phase Ordering Rationale

- Infrastructure-first (Phase 1) matches dependency reality: nothing else can be built or tested locally without a running broker.
- Producer before consumer (Phase 2 -> 3) lets each side be unit-tested independently with mocks before requiring the other to exist, per architecture research's explicit build order.
- Read path (Phase 4) is deliberately sequenced after the consumer/entity exists so it can be tested against real (or seeded) rows rather than stubbed data.
- Integration testing last (Phase 5) is the only phase that requires the full pipeline, matching the epic's own framing of the Testcontainers test as validating already-built pieces end-to-end.
- This ordering also naturally staggers pitfall-avoidance: transactional-boundary and callback discipline get built in at the point they're introduced (Phase 2), idempotency/DLT correctness gets built in at the point the consumer is introduced (Phase 3), rather than being retrofitted after a "looks done" happy path ships.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 1 (Kafka infra):** exact KRaft env-var set and internal-vs-external listener config for `apache/kafka-native` was only web-search-sourced (LOW confidence in ARCHITECTURE.md/STACK.md) — pull the actual reference compose YAML from `apache/kafka`'s own repo before finalizing, per STACK.md's explicit recommendation.
- **Phase 3 (consumer/DLT):** `DefaultErrorHandler`/`DeadLetterPublishingRecoverer` retry-before-DLT tuning and non-retryable exception classification is thinly sourced (LOW-MEDIUM); worth a `--research-phase` pass to avoid copying stale `SeekToCurrentErrorHandler`-era tutorials.
- **Phase 4 (pagination decision):** whether Spring Data JPA on this Boot version cleanly supports `ScrollPosition`/keyset pagination is an open question flagged directly in FEATURES.md and STACK.md — needs a quick spike before committing.

Phases with standard patterns (skip research-phase):
- **Phase 2 (producer wiring):** `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)` is a well-documented, standard Spring pattern; `PATCH /tasks/{taskId}/move` directly reuses the codebase's own existing `@Version` convention — HIGH confidence from direct codebase inspection.
- **Phase 5 (integration test):** `@ServiceConnection` + Testcontainers Kafka is the idiomatic Boot 3.1+ pattern, directly named by the epic spec.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | MEDIUM | Version numbers verified directly against the pinned `spring-boot-dependencies` BOM at the `v3.5.0` tag (a primary source), but Docker/KRaft env-var details are web-search-only |
| Features | MEDIUM | Table-stakes/differentiator framing is well-reasoned and cross-checked across multiple sources, but public documentation of Trello/Jira's actual per-card activity feed mechanics is thin (noted gap) |
| Architecture | MEDIUM (HIGH for project-specific integration reasoning) | Codebase integration points (service structure, ownership reuse, injection convention) are HIGH confidence from direct file reads; generic Spring Kafka/Testcontainers/Docker facts are LOW-confidence single-source web search |
| Pitfalls | LOW-MEDIUM | General web sources, not cross-verified against a second independent source per claim, except where explicitly Spring/Confluent/Docker official docs |

**Overall confidence:** MEDIUM

### Gaps to Address

- **Production Kafka scope:** not yet decided whether v1.1 stands up Kafka in production (EC2) or is local/dev-only — affects `KAFKA_BOOTSTRAP_SERVERS` wiring and the compose-file security review; flag for Phase 1 scoping during roadmap creation.
- **Dead-letter topic naming/auto-creation:** epic spec names `kanban.activity.dlt` explicitly (vs. Spring's default `{topic}.DLT` suffix) — needs a custom destination resolver; also decide `NewTopic` `@Bean`s vs. broker auto-create during Phase 3 planning.
- **Cross-board move handling:** `PATCH /tasks/{taskId}/move` — whether moving a task to a column on a *different* board should be rejected or explicitly allowed is unresolved in research; needs a decision during Phase 2 planning.
- **Cursor vs. offset pagination:** genuinely underspecified in the epic; needs a quick Spring Data version spike before Phase 4 planning locks in the repository query shape (expensive to change after the endpoint ships with real data).
- **Exact KRaft compose YAML:** the specific env-var combination for `apache/kafka-native` should be pulled directly from `apache/kafka`'s own `docker/examples/docker-compose-files/single-node/` rather than hand-assembled, per STACK.md's own caveat.

## Sources

### Primary (HIGH confidence)
- GitHub raw `build.gradle` at `spring-projects/spring-boot` tag `v3.5.0` — pinned `kafka-clients`/`spring-kafka`/`testcontainers` versions
- Existing codebase (read directly): `TaskService.java`, `TaskEntity.java`, `OwnershipVerifierService.java`, `TaskController.java`, `BaseEntity.java`, `build.gradle`, `application.properties`, `docs/codebase/ARCHITECTURE.md`
- `docs/plans/backend-modernization/01-kafka-activity-feed.md` (epic spec) and `.planning/PROJECT.md` — authoritative for this milestone's scope

### Secondary (MEDIUM confidence)
- docs.spring.io official Spring Kafka reference (error handling, `DeadLetterPublishingRecoverer`, testing) — official framework docs
- Apache Kafka / Docker official docs (image names, KRaft docker guide, Confluent delivery-semantics docs)
- Practitioner sources cross-checked across multiple independent posts (Lydtech Consulting on idempotent consumers/dedup patterns, Baeldung on Kafka+Spring Boot testing)

### Tertiary (LOW confidence)
- General web search results on KRaft env-var sets, DLT retry/backoff tuning, consumer rebalance behavior, and partition-key ordering — not cross-verified against a second independent source; flagged throughout PITFALLS.md and ARCHITECTURE.md as needing a doc-check at implementation time
- Public documentation of Jira/Trello's actual activity-feed implementation details (delivery model, pagination mechanism) — largely undocumented publicly, inference-based

---
*Research completed: 2026-08-01*
*Ready for roadmap: yes*
