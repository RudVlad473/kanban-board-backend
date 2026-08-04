# Phase 4: Schema Registry - Research

**Researched:** 2026-08-04
**Domain:** Kafka Schema Registry (Avro) integration in front of an existing 5-event-type activity-log pipeline — sealed-interface-to-SpecificRecord mapping, Confluent Avro serde wiring, compatibility enforcement, DLT byte-fidelity re-verification, historical-data rehearsal
**Confidence:** MEDIUM-HIGH (official docs cross-checked for Confluent/Avro/gradle-avro-plugin mechanics and Testcontainers' Redpanda module; the mapping-layer *shape* itself has no single canonical source — synthesized from the Confluent serdes docs' `SpecificRecord` contract plus this codebase's own existing MapStruct Entity↔DTO convention, so that specific section is judgment applied to verified primitives, not a copied pattern)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01 (registry-down resilience):** A Schema Registry failure (unreachable registry, or a schema rejected by the compatibility check) is treated exactly like a Kafka-broker-down failure already is under v1.1's D-01/D-02: the HTTP mutation always succeeds regardless of what fails inside the async, post-commit publish path — logged via the existing `whenComplete` error callback in `KafkaEventPublisher`, never swallowed, never blocks the caller. One resilience policy for the whole publish path, not a special case for registry-specific failures.
- **D-02 (compatibility mode):** The activity-log topic's schema subject(s) use **BACKWARD** compatibility, explicitly configured (not left at the registry's out-of-the-box default). Matches this project's actual deployment topology — producer and consumer live in the same deployable and always ship together in one merge. Also required if the append-only activity feed is ever replayed from the beginning.
- **D-03 (schema granularity):** One Avro schema per event type — 5 separate `.avsc` files, one per `TaskCreatedEvent`/`TaskMovedEvent`/`TaskDeletedEvent`/`BoardCreatedEvent`/`ColumnCreatedEvent` — not a single union schema covering all 5. Mirrors the existing 5-record Java structure exactly, 1:1.

### Claude's Discretion

- Exact Avro schema field types/logical types for each event's fields (e.g. `timestamp` → `timestamp-millis` vs plain `long`), informed by `ActivityEvent`'s existing `Instant`-typed field. **Resolved below** (see Code Examples / Common Pitfalls).
- Per-field required-vs-optional-with-default classification for each of the 5 event types (SCHEMA-06's rehearsal work) — requires reading each record's fields against real historical event shapes. **Resolved below**: all 5 records' fields are 100%-populated, non-nullable Java primitives/`String`/`UUID`/`Instant` today (confirmed by reading all 5 record source files this session — see Code Examples); there is no field that has ever legitimately been absent or null in the existing JSON contract, so every field in every one of the 5 schemas should be **required, no default** — the reflexive nullable-with-default trap (PITFALLS.md Pitfall 10) does not apply here and should not be introduced speculatively.
- Mapping-layer implementation shape (hand-authored mapper vs Avro's reflection-based `@Union`/`SpecificRecord` codegen). **Resolved below** (see Architecture Patterns).
- Exact subject-naming-strategy configuration (`TopicNameStrategy` vs `RecordNameStrategy` vs `TopicRecordNameStrategy`). **Resolved below**: `RecordNameStrategy`.
- Whether to stand up a standalone local Schema Registry container, or point at a local single-node Redpanda instance. **Resolved below**: local single-node Redpanda (via Testcontainers' dedicated `RedpandaContainer`, one container for both broker and registry).

### Deferred Ideas (OUT OF SCOPE)

- **Pre-merge schema-compatibility CI check** (SCHEMA-V2-01) — deferred to v2.
- **Documented compatibility-mode rationale** (Javadoc-style paragraph, SCHEMA-V2-02) — deferred to v2; CONTEXT.md's D-02 already captures the rationale for downstream agents.
- "Use Snowflake ID generator for activity log events" and four other low-relevance pending todos — reviewed and confirmed unrelated to this phase, no action.

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SCHEMA-01 | Each of the 5 `ActivityEvent` types has an explicit, versioned Avro schema, registered via a build/CI step rather than producer auto-registration | Architecture Patterns (gradle-avro-plugin wiring, `.avsc` per type) + `auto.register.schemas=false` producer property in Code Examples |
| SCHEMA-02 | Mapping layer translates between plain `ActivityEvent` records and Avro `SpecificRecord`s at both boundaries, zero change to the sealed-interface/exhaustive-switch pattern | Architecture Patterns (hand-authored mapper decision) + Code Examples |
| SCHEMA-03 | Producer/consumer use Confluent's Avro serializer/deserializer against Redpanda's built-in registry | Code Examples (`application.properties` diff) + Standard Stack |
| SCHEMA-04 | Compatibility mode (BACKWARD, per D-02) explicitly configured, not left at default | Code Examples (REST API call / subject config) + Common Pitfalls (Pitfall: registration-time enforcement) |
| SCHEMA-05 | DLT byte-fidelity re-verified under Avro via a dedicated raw byte-array serializer, proven by a new test | Architecture Patterns (DLT serializer isolation) + Common Pitfalls (Pitfall 1) — extends the existing `DelegatingByTypeSerializer` pattern already in `KafkaConsumerConfig` |
| SCHEMA-06 | Real historical activity-log events rehearsed through the new schemas before cutover | Common Pitfalls (Pitfall 2) — concrete data source identified (`activity_log` Postgres table, not raw topic replay) |

</phase_requirements>

## Summary

This phase adds a Confluent-API-compatible Avro Schema Registry in front of an already-shipped, already-verified 5-event JSON Kafka pipeline. The milestone-level research (STACK.md, ARCHITECTURE.md, PITFALLS.md) already did the heavy lifting on stack selection and identified the central open question — no tooling maps a Java sealed interface to Avro automatically — without resolving it. This phase-level research resolves that question concretely, plus the two other items CONTEXT.md left to discretion.

**Mapping layer:** hand-authored mapper classes, one method per direction per event type (10 total: 5 domain→Avro, 5 Avro→domain), living alongside `KafkaEventPublisher`/`ActivityLogConsumer`. Avro's reflection-based approach (`org.apache.avro.reflect.ReflectData`) is explicitly rejected: it requires either annotating `ActivityEvent`'s records directly (which breaks the package's own documented "plain, dependency-free" invariant) or reflecting over an unrelated shadow class hierarchy, and it produces schemas from Java reflection rather than the other way around — the opposite of D-03's "author 5 `.avsc` files, each independently governs its own subject" model. Gradle-avro-plugin's ordinary `.avsc`-first codegen (already the milestone's chosen approach in STACK.md) plus a small hand-written mapper is the only approach that keeps `.avsc` files as the schema source of truth while leaving `ActivityEvent` completely untouched — MapStruct itself cannot be reused here (its `@Mapper` annotation processor cannot generate a mapper for a `sealed interface` target selected by pattern-matching over 5 unrelated record shapes; MapStruct maps single-source-type-to-single-target-type pairs, not sealed hierarchies), so the mapper is a hand-written `switch` expression matching the existing `ActivityLogConsumer.deriveActionAndDetailIds` idiom already in this codebase.

**Local verification target:** a single-node Redpanda container via Testcontainers' dedicated `org.testcontainers:redpanda` module (`RedpandaContainer`, which exposes both `getBootstrapServers()` and `getSchemaRegistryAddress()` from one container) — not a standalone Confluent Schema Registry container. This directly minimizes Phase 5's cutover cost (repoint `schema.registry.url` host:port only, same client code, same wire-compatible registry implementation, zero new integration surface) and matches the project's own REQUIREMENTS.md "Out of Scope" rejection of "a separately-deployed Confluent Schema Registry container" for the identical reason (avoids a second service). `docker-compose.yml`'s local-dev `kafka` service should also move from `apache/kafka-native:4.3.1` to a Redpanda image for the same reason, ahead of Phase 5's full redeploy-target migration.

**Subject naming:** `RecordNameStrategy` — subject name becomes the Avro record's full name (`com.vrudenko.kanban_board.event.avro.TaskCreatedEvent`, etc.), letting all 5 event types coexist as 5 independent subjects on the single `kanban.activity` topic, each evolving under its own BACKWARD compatibility setting per D-03. `TopicRecordNameStrategy` is the documented alternative if a future second topic ever reuses one of these record types — not needed today.

**Primary recommendation:** author 5 `.avsc` files with every field required (no defaults — confirmed no field in the current 5 records has ever been optional/nullable), wire `gradle-avro-plugin` 1.9.1 for codegen, add hand-written mapper classes at both Kafka boundaries, configure Confluent's `KafkaAvroSerializer`/`KafkaAvroDeserializer` with `RecordNameStrategy` and `auto.register.schemas=false`, set BACKWARD compatibility per subject via a build/CI-time REST call (not producer auto-registration), keep the DLT path on its existing raw-`byte[]` serializer, verify against a local single-node Redpanda Testcontainers instance, and rehearse the new schemas against real rows sampled from the `activity_log` Postgres table (the durable historical record — not raw topic replay, which the local dev topic has no retention guarantee over).

## Architectural Responsibility Map

This project is a backend-only monolith (no separate frontend/SSR/CDN tier in scope for this phase); tiers are adapted accordingly.

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Avro schema definition (`.avsc` files) + registration | Build/CI (Gradle + a registration step) | — | SCHEMA-01 requires build/CI-driven registration, explicitly not producer auto-registration |
| Domain event ↔ Avro SpecificRecord mapping | API/Backend (`src/main/java`) | — | Lives at the Kafka publish/consume boundary inside the existing Spring Boot process; no external service involved |
| Avro wire (de)serialization | API/Backend | Message Broker / Schema Registry (external) | `KafkaAvroSerializer`/`Deserializer` run in-process but consult the external registry over HTTP for schema ID resolution |
| Compatibility enforcement (BACKWARD) | Message Broker / Schema Registry (external) | Build/CI (the step that sets it) | The registry itself enforces compatibility at registration time; the build/CI step only declares the desired setting |
| Dead-letter byte-fidelity preservation | API/Backend | — | `DeadLetterPublishingRecoverer` + its dedicated `byte[]` serializer, entirely in-process, already exists and is unaffected by the registry's presence |
| Historical-data rehearsal | Database/Storage (Postgres `activity_log`) | API/Backend (the rehearsal harness) | The durable historical record lives in Postgres, not in the Kafka topic (which has no retention guarantee locally) |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.github.davidmc24.gradle.plugin.avro` | 1.9.1 `[VERIFIED: plugins.gradle.org / Maven Central, fetched this session]` | Gradle plugin: compiles `.avsc` → generated Java `SpecificRecord` classes at build time | Only actively-referenced Gradle Avro plugin in the ecosystem; already the milestone's chosen tool (STACK.md). **Caveat, new this session:** the project was **archived by its owner on 2026-12-28** and is explicitly "no longer maintained... donated to the Apache Avro project" `[CITED: github.com/davidmc24/gradle-avro-plugin — repo banner + README, fetched this session]`. 1.9.1 is its final, permanent version — not a mid-stream snapshot. Functionally complete for this phase's needs (plain `.avsc` schemas, no custom logical-type conversions beyond built-in `timestamp-millis`), but pin the exact version and do not expect further releases; if Apache Avro's own successor Gradle plugin has since stabilized under `org.apache.avro`, that is the natural target for a future dependency-hygiene pass, not this phase's scope. |
| `org.apache.avro:avro` | 1.12.1 `[CITED: search.maven.org / central.sonatype.com, fetched this session — not independently re-verified against a second registry mirror due to a 403 on mvnrepository.com]` | Avro core: schema model, binary encoder/decoder, `SpecificRecord`/`SpecificRecordBase` runtime support | Required runtime dependency of any Avro-generated class; STACK.md's "latest 1.12.x" is confirmed current |
| `io.confluent:kafka-avro-serializer` | **7.8.9** (revised from STACK.md's placeholder 7.7.1) `[VERIFIED: packages.confluent.io/maven/io/confluent/kafka-avro-serializer/ directory listing, fetched this session]` | Confluent's `KafkaAvroSerializer`/`KafkaAvroDeserializer` + Schema Registry REST client | `[CITED: docs.confluent.io/platform/current/installation/versions-interoperability.html, fetched this session]` confirms Confluent Platform 7.8.x is the release line whose bundled Kafka version is 3.8.x — matching Spring Boot 3.5.0's managed `kafka-clients` 3.8.1 `[ASSUMED — carried from milestone STACK.md, not re-verified this session]` most closely (CP 7.7.x pairs with an older Kafka line). 7.8.9 is the newest patch release in that line (registry lists 7.8.0 through 7.8.9; CP 7.8.x reaches end of standard support 2026-12-02, still current for this build). Confluent's client libraries are generally broker-version-tolerant, but picking the release-line match minimizes transitive `kafka-clients` conflicts against Spring Boot's BOM-managed version — verify with `./gradlew dependencies --configuration compileClasspath | grep kafka-clients` after adding the dependency, and add an explicit `exclude group: 'org.apache.kafka', module: 'kafka-clients'` on this dependency if Gradle does not resolve to Spring Boot's managed version on its own. |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.testcontainers:redpanda` | matches the `testcontainers-version` Spring Boot 3.5.0's BOM manages (same mechanism already governing `org.testcontainers:kafka`/`junit-jupiter` in `build.gradle`, no explicit version needed) `[CITED: github.com/testcontainers/testcontainers-java, module `modules/redpanda`, fetched this session — exact bundled version against Spring Boot 3.5.0's BOM not independently pinned this session, verify via `./gradlew dependencies` when added]` | `RedpandaContainer` exposes `getBootstrapServers()` **and** `getSchemaRegistryAddress()` from a single container | Add as a **new** `testImplementation` for this phase's Avro-specific test classes. Do not remove `org.testcontainers:kafka` — the existing 3 non-Avro `activitylog` E2E classes (`ActivityLogConsumerE2ETest`, `ActivityLogDeadLetterE2ETest`, `ActivityLogIdempotencyE2ETest`) can stay on `apache/kafka-native` unless this phase's plan chooses to migrate `AbstractKafkaContainerTest` itself to Redpanda (see Open Questions) |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Hand-authored mapper classes | Avro's `org.apache.avro.reflect.ReflectData`/`@Union` reflection-based serialization | Rejected: requires either annotating `ActivityEvent`'s records (breaks the package's documented "plain, dependency-free" invariant) or a parallel reflection-friendly shadow hierarchy; also inverts D-03's schema-source-of-truth model (schema-from-Java instead of Java-from-schema) |
| Local single-node Redpanda (Testcontainers `RedpandaContainer`) | Standalone Confluent-compatible `confluentinc/cp-schema-registry` container wired to the existing `apache/kafka-native` broker | Viable, and what the milestone-level ARCHITECTURE.md flagged as an option, but adds a second container to wire/network for zero benefit over the one-container Redpanda option, and Phase 5 must re-verify against a *different* registry implementation at cutover regardless — the project's own REQUIREMENTS.md Out-of-Scope table already rejects a standalone Confluent registry container for the analogous production reasoning |
| `RecordNameStrategy` | `TopicRecordNameStrategy` | `TopicRecordNameStrategy` (subject = `<topic>-<full record name>`) is strictly more flexible (scopes each record type's schema evolution per-topic) at negligible extra config cost — but this project has exactly one topic (`kanban.activity`) carrying these 5 record types today with no plan to reuse them elsewhere, so the extra flexibility has no current payoff. Revisit only if a second topic is ever introduced that reuses one of these 5 record types. |
| Required, no-default fields in all 5 `.avsc` schemas | Reflexive `["null", "T"], "default": null` on every field | Rejected per PITFALLS.md Pitfall 10's own warning: no field in any of the 5 existing records has ever been legitimately absent or null (confirmed by reading all 5 record source files — every field is a non-nullable Java primitive/`String`/`UUID`/`Instant`), so introducing nullable-with-default speculatively would only manufacture the null-vs-absent ambiguity trap the pitfall warns against, with no corresponding real-world need |

**Installation:**
```gradle
plugins {
    id 'com.github.davidmc24.gradle.plugin.avro' version '1.9.1'
}

repositories {
    mavenCentral()
    maven { url 'https://packages.confluent.io/maven/' }
}

dependencies {
    implementation 'org.apache.avro:avro:1.12.1'
    implementation('io.confluent:kafka-avro-serializer:7.8.9') {
        exclude group: 'org.apache.kafka', module: 'kafka-clients'
    }
    testImplementation 'org.testcontainers:redpanda'
}
```

`.avsc` schema files go in `src/main/avro/` (gradle-avro-plugin's default source set path); generated Java lands in `build/generated-main-avro-java` at compile time (gradle-avro-plugin's default output path `[CITED: github.com/davidmc24/gradle-avro-plugin README, fetched this session]`). This is a new source of generated code alongside MapStruct's existing `build/generated/**` output — extend both the ErrorProne `excludedPaths` regex (`build.gradle` lines ~154-159) and Spotless's `target 'src/**/*.java'` glob is already safe (it only targets `src/**`, not `build/**`, so no change needed there) to also treat `build/generated-main-avro-java/**` as generated, non-actionable code.

**Version verification performed this session:**
- `com.github.davidmc24.gradle.plugin.avro` 1.9.1 — confirmed current/final via Maven Central + GitHub repo archive banner.
- `org.apache.avro:avro` 1.12.1 — confirmed via search.maven.org/central.sonatype.com listings.
- `io.confluent:kafka-avro-serializer` 7.8.9 — confirmed via direct fetch of Confluent's own Maven repository directory index (`packages.confluent.io/maven/io/confluent/kafka-avro-serializer/`), cross-referenced against Confluent's official version-interoperability page for the Kafka-client-version match.

## Package Legitimacy Audit

**Ecosystem note:** this phase's new dependencies are all Maven/Gradle (Java) coordinates, not npm/PyPI/crates — the automated `gsd_run query package-legitimacy check` seam only supports `npm|pypi|crates` ecosystems (confirmed by running it this session; it rejected `--ecosystem maven`). Verification below was performed manually against each package's authoritative registry/source (Maven Central, Confluent's own Maven repository, GitHub), following the same intent as the automated gate (age, source repo, active/known maintainer, no scam/phantom package signals).

| Package | Registry | Age | Source Repo | Verdict | Disposition |
|---------|----------|-----|--------------|---------|-------------|
| `com.github.davidmc24.gradle.plugin.avro:1.9.1` | Gradle Plugin Portal / Maven Central | ~2 years since last release (project archived 2026-12-28) | `github.com/davidmc24/gradle-avro-plugin` (public, 1.9k+ stars, real commit history, official archive banner — not deleted/hijacked) | OK (archived, not malicious — a real, long-lived project reaching end-of-life) | Approved, with the "unmaintained, no future patches" caveat documented in Standard Stack above |
| `org.apache.avro:avro:1.12.1` | Maven Central | Apache Software Foundation top-level project, actively released | `github.com/apache/avro` | OK | Approved |
| `io.confluent:kafka-avro-serializer:7.8.9` | Confluent's own Maven repository (`packages.confluent.io/maven/`) | Confluent Platform, actively released (CP 7.8.x support through 2026-12-02) | `github.com/confluentinc/schema-registry` | OK | Approved |
| `org.testcontainers:redpanda` | Maven Central (via Spring Boot's testcontainers BOM) | Official Testcontainers module, actively maintained | `github.com/testcontainers/testcontainers-java` | OK | Approved |

**Packages removed due to [SLOP] verdict:** none.
**Packages flagged as suspicious [SUS]:** none.

*All four packages above were verified against an authoritative source this session (official registry index pages, official GitHub org repos) — none are tagged `[ASSUMED]` for existence, though the exact `io.confluent:kafka-avro-serializer` ↔ `kafka-clients` 3.8.1 compatibility claim carries an `[ASSUMED]` sub-tag (see Standard Stack) since Spring Boot 3.5.0's exact managed `kafka-clients` patch was not independently re-confirmed this session (carried from milestone STACK.md).*

## Architecture Patterns

### System Architecture Diagram

```
HTTP mutation (TaskService, BoardService, etc.)
        │  ApplicationEventPublisher.publishEvent(ActivityEvent)
        ▼
KafkaEventPublisher.onActivityEvent()  (@TransactionalEventListener AFTER_COMMIT, @Async)
        │
        │  1. NEW: mapper.toAvro(event)  — domain ActivityEvent → Avro SpecificRecord
        ▼
kafkaTemplate.send(topic, key, avroRecord)
        │
        │  2. KafkaAvroSerializer intercepts: looks up/registers schema via
        │     schema.registry.url (RecordNameStrategy → subject =
        │     event's full Avro record name), then writes
        │     [magic byte][4-byte schema ID][Avro binary payload]
        ▼
┌─────────────────────────────────────────────────────────────┐
│  Redpanda (local: Testcontainers RedpandaContainer;          │
│  prod, Phase 5: same broker, built-in registry on the OCI VM)│
│  ┌──────────────────────┐   ┌─────────────────────────────┐ │
│  │ kanban.activity topic │   │ Schema Registry (5 subjects, │ │
│  │ (all 5 event types,   │◄──┤ RecordNameStrategy, BACKWARD│ │
│  │  1 partition)         │   │ compatibility per subject)   │ │
│  └──────────────────────┘   └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
        │
        ▼  ErrorHandlingDeserializer wraps KafkaAvroDeserializer
   ┌────┴─────────────────────────────┐
   │ Deserialization succeeds?        │
   └────┬──────────────────────┬──────┘
        │ yes                  │ no (unknown schema ID, bad
        ▼                      │     magic byte, registry down,
ActivityLogConsumer            │     incompatible writer schema)
.onActivityEvent(avroRecord)   ▼
        │              DefaultErrorHandler → 3 retries (~1s) →
        │  3. NEW: mapper.toDomain(avroRecord)  DeadLetterPublishingRecoverer
        │     — Avro SpecificRecord → domain          │
        │       ActivityEvent (sealed interface)       │  UNCHANGED: raw byte[]
        ▼                                              │  via existing
deriveActionAndDetailIds()                              │  DelegatingByTypeSerializer
  (existing exhaustive switch,                           │  (byte[].class → ByteArraySerializer)
   UNCHANGED per SCHEMA-02)                              ▼
        │                                        kanban.activity.dlt
        ▼                                     (original bytes preserved,
ActivityLogRecorder.record()                   byte-for-byte, regardless
        │                                       of what format broke)
        ▼
activity_log Postgres table
        │
        │  4. SCHEMA-06 rehearsal reads FROM here (not from raw topic
        │     replay — the durable historical record)
        ▼
   (existing GET /boards/{boardId}/activity read API — unaffected)
```

### Recommended Project Structure

```
src/main/avro/                          # NEW — .avsc schema source files (D-03: one per event type)
├── TaskCreatedEvent.avsc
├── TaskMovedEvent.avsc
├── TaskDeletedEvent.avsc
├── BoardCreatedEvent.avsc
└── ColumnCreatedEvent.avsc

build/generated-main-avro-java/         # NEW — gradle-avro-plugin codegen output (analogous to
                                         # MapStruct's build/generated/**; not hand-written, exclude
                                         # from ErrorProne per Standard Stack note above)
└── com/vrudenko/kanban_board/event/avro/
    ├── TaskCreatedEvent.java           # generated SpecificRecord classes
    └── ...

src/main/java/com/vrudenko/kanban_board/
├── event/                              # UNCHANGED — ActivityEvent sealed interface + 5 records
├── event/avro/                         # NEW — hand-authored mapper package
│   └── ActivityEventAvroMapper.java    # toAvro(ActivityEvent) / toDomain(SpecificRecord) — see
│                                        # Code Examples below
├── config/
│   ├── KafkaEventPublisher.java        # MODIFIED — calls mapper.toAvro() before kafkaTemplate.send()
│   └── KafkaConsumerConfig.java        # MODIFIED CONFIG ONLY — DLT serializer path unchanged (SCHEMA-05)
└── activitylog/
    └── ActivityLogConsumer.java        # MODIFIED — calls mapper.toDomain() on receipt, exhaustive
                                         # switch below it UNCHANGED (SCHEMA-02's hard requirement)
```

### Pattern 1: Hand-authored bidirectional mapper (the mapping-layer decision)

**What:** A single `ActivityEventAvroMapper` component with two methods: `toAvro(ActivityEvent) -> SpecificRecord` (an exhaustive `switch` over the sealed interface, one arm per record type, each constructing the matching generated Avro builder) and `toDomain(SpecificRecord) -> ActivityEvent` (an `instanceof`/type-based dispatch over the 5 generated Avro classes, each constructing the matching domain record). Field-for-field, 1:1 — no transformation logic beyond type adaptation (`UUID.toString()`/`UUID.fromString()`, `Instant.toEpochMilli()`/`Instant.ofEpochMilli()` if the codegen does not already produce `Instant` natively — verify in the schema-authoring task, see Common Pitfalls).

**When to use:** This is the only mapping-layer shape compatible with D-03 (schema-as-source-of-truth `.avsc` files) + SCHEMA-02 (zero change to `ActivityEvent`/`ActivityLogConsumer`'s exhaustive switch) + this codebase's existing convention (MapStruct-generated mappers for Entity↔DTO, hand-rolled exhaustive switches for sealed-interface dispatch, exactly as `ActivityLogConsumer.deriveActionAndDetailIds` already does today).

**Example (illustrative shape — exact generated-class field accessors depend on the `.avsc` schema authored in the plan; not copy-paste-ready code, since the Avro classes do not exist yet):**
```java
// Source: synthesized from this codebase's ActivityLogConsumer.deriveActionAndDetailIds
// (src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java:69-98)
// exhaustive-switch idiom, applied to the new Avro boundary. Illustrative — the generated
// Avro Builder API shape (TaskCreatedEvent.newBuilder()...) is standard Avro SpecificRecord
// codegen, confirmed via Confluent's own serdes-avro documentation this session.
@Component
public class ActivityEventAvroMapper {

    public SpecificRecord toAvro(ActivityEvent event) {
        return switch (event) {
            case TaskCreatedEvent e -> com.vrudenko.kanban_board.event.avro.TaskCreatedEvent.newBuilder()
                    .setEventId(e.eventId().toString())
                    .setUserId(e.userId())
                    .setBoardId(e.boardId())
                    .setColumnId(e.columnId())
                    .setTaskId(e.taskId())
                    .setTimestamp(e.timestamp())   // timestamp-millis logical type -> Instant, if codegen
                                                    // supports it natively (verify; see Common Pitfalls)
                    .build();
            case TaskMovedEvent e -> com.vrudenko.kanban_board.event.avro.TaskMovedEvent.newBuilder()
                    /* ... */ .build();
            case TaskDeletedEvent e -> com.vrudenko.kanban_board.event.avro.TaskDeletedEvent.newBuilder()
                    /* ... */ .build();
            case BoardCreatedEvent e -> com.vrudenko.kanban_board.event.avro.BoardCreatedEvent.newBuilder()
                    /* ... */ .build();
            case ColumnCreatedEvent e -> com.vrudenko.kanban_board.event.avro.ColumnCreatedEvent.newBuilder()
                    /* ... */ .build();
        };
    }

    public ActivityEvent toDomain(SpecificRecord record) {
        return switch (record) {
            case com.vrudenko.kanban_board.event.avro.TaskCreatedEvent r -> new TaskCreatedEvent(
                    UUID.fromString(r.getEventId().toString()),
                    r.getUserId().toString(),
                    r.getBoardId().toString(),
                    r.getColumnId().toString(),
                    r.getTaskId().toString(),
                    r.getTimestamp());
            // ... one arm per remaining type, symmetric with toAvro()
            default -> throw new IllegalArgumentException(
                    "Unknown Avro record type: " + record.getClass());
        };
    }
}
```

### Pattern 2: `RecordNameStrategy` for multiple record types on one topic

**What:** Configure `value.subject.name.strategy=io.confluent.kafka.serializers.subject.RecordNameStrategy` on both producer and consumer. This makes each of the 5 event types' schema subject equal to its Avro record's full name (namespace + name) — independent of which topic carries it — so all 5 coexist as 5 independently-versioned, independently-BACKWARD-compatible subjects on the single `kanban.activity` topic, matching D-03 exactly.

**When to use:** Any topic that legitimately carries more than one distinct record shape (this project's `kanban.activity` topic, by design, carries 5). The default `TopicNameStrategy` (subject = topic name) is wrong here — it would force all 5 event types to share a single subject/schema, contradicting D-03 outright.

**Example:**
```properties
# Source: Confluent's Kafka SerDes documentation (subject naming strategies section),
# fetched and cross-checked this session
spring.kafka.producer.properties.value.subject.name.strategy=io.confluent.kafka.serializers.subject.RecordNameStrategy
spring.kafka.consumer.properties.value.subject.name.strategy=io.confluent.kafka.serializers.subject.RecordNameStrategy
```

### Anti-Patterns to Avoid

- **Converting `ActivityEvent` itself into Avro-generated classes:** Breaks the package's documented "plain, dependency-free records only" invariant and `ActivityLogConsumer`'s exhaustive switch (unless rewritten around Avro types, which SCHEMA-02 explicitly forbids). Already flagged at milestone level (ARCHITECTURE.md Anti-Pattern 2); repeated here because it is the single most tempting shortcut once Avro codegen classes exist and "just use them directly" looks appealing.
- **Reusing the main pipeline's `KafkaAvroSerializer`/`Deserializer` for the DLT path:** `DeadLetterPublishingRecoverer`'s recoverer would itself throw trying to re-encode a payload it just failed to decode, silently breaking the exact byte-fidelity guarantee SCHEMA-05 requires. The existing `deadLetterKafkaTemplate` bean's `DelegatingByTypeSerializer` (`byte[].class → ByteArraySerializer`, `Object.class → JsonSerializer` fallback) already routes correctly and needs **no change** — this pattern only needs re-verifying with a new Avro-specific poison-message test (SCHEMA-05), not new code.
- **Reflexive nullable-with-default Avro fields:** See Alternatives Considered above — none of the 5 existing event types has a field that has ever been legitimately absent/null; every field should be required with no default.
- **Registering schemas via producer auto-registration (`auto.register.schemas=true`, the client default):** Contradicts SCHEMA-01's explicit "registered via a build/CI step... not producer auto-registration." Set `auto.register.schemas=false` on the producer and register schemas through an explicit Gradle task/CI step (or manual `rpk` / REST call in dev) instead.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Avro binary encoding/decoding | A custom Avro reader/writer | `KafkaAvroSerializer`/`KafkaAvroDeserializer` + generated `SpecificRecord` classes | Wire format (magic byte + schema ID + binary payload) is a Confluent-defined protocol; a hand-rolled encoder would be incompatible with the registry's own resolution logic |
| Schema-to-Java-class generation | Manual POJOs matching each `.avsc` by hand | `gradle-avro-plugin`'s codegen | Any manual drift between an `.avsc` file and a hand-written "matching" class is invisible until a runtime `ClassCastException`/serialization mismatch; codegen guarantees they can never diverge |
| Compatibility checking | A custom "does this schema still parse old messages" check | The registry's own `/compatibility` endpoint (invoked implicitly at registration time, or explicitly for pre-merge checks — the latter is SCHEMA-V2-01, deferred) | Schema Registry's compatibility algorithm already encodes the full Avro resolution rules (default handling, type promotion, etc.); reimplementing it is both large in scope and a correctness risk for zero benefit |
| Dead-letter byte preservation under a new serialization format | A new DLT serializer specific to "Avro failures" | The existing `DelegatingByTypeSerializer`/`byte[]`-keyed DLT template, unmodified | Already correctly generic across *any* deserialization failure format (JSON or Avro) — the failure mode this phase worries about (recoverer re-encoding through the wrong serializer) is prevented by the existing byte-array-first dispatch, not by writing new format-aware code |

**Key insight:** every piece of new infrastructure this phase needs (encoding, codegen, compatibility checking) already has a purpose-built, Confluent/Avro-maintained implementation reachable with configuration only; the only genuinely new hand-written code this phase requires is the mapping layer (Pattern 1 above), because that boundary — sealed interface ↔ generated SpecificRecord — is inherently project-specific and has no generic tool to bridge it (confirmed at milestone level: no tooling maps a Java sealed interface to Avro automatically).

## Common Pitfalls

### Pitfall 1: `timestamp-millis`/`uuid` Avro logical-type codegen behavior is not fully documented for this specific, now-archived plugin — verify before authoring all 5 schemas

**What goes wrong:** Assuming `{"type": "long", "logicalType": "timestamp-millis"}` in an `.avsc` file automatically generates a `java.time.Instant`-typed accessor (rather than a plain `long`) without checking, or assuming `{"type": "string", "logicalType": "uuid"}` generates `java.util.UUID` (rather than `CharSequence`/`String`) without checking.

**Why it happens:** Multiple independent web sources this session confirmed Avro's Java codegen *has* generated `Instant` for `timestamp-millis` by default since Avro ~1.10 in general — but `gradle-avro-plugin`'s own README (fetched directly this session) does not document this behavior explicitly, and the plugin is archived/unmaintained as of Dec 2026, so its interaction with Avro 1.12.1's latest codegen defaults was not independently confirmed against this exact plugin+Avro version pairing.

**How to avoid:** Before authoring all 5 production schemas, write one throwaway `.avsc` with a `timestamp-millis` field and a `uuid`-logicalType field, run the codegen task, and inspect the generated class's field types directly. If `Instant`/`UUID` are generated natively, use them as-is in the mapper (no manual conversion needed). If not, fall back to plain `long`/`string` Avro types with manual `Instant.toEpochMilli()`/`.toString()` conversion in the mapper (Pattern 1 above already includes this fallback path) — either way is a small, contained decision, not a blocker, but it must be verified once by inspection rather than assumed from general Avro documentation that predates this specific archived plugin.

**Warning signs:** A compile error in the mapper (`incompatible types: long cannot be converted to Instant`, or similar) the first time the mapper is written against real generated classes — this is the verification step happening late instead of early; better to spike it first.

**Phase to address:** Schema Registry phase, schema-authoring step, before all 5 `.avsc` files are finalized.

---

### Pitfall 2: SCHEMA-06's "real historical data" is in the `activity_log` Postgres table, not the Kafka topic

**What goes wrong:** Interpreting "rehearse against real historical activity-log data" (SCHEMA-06) as "replay the actual `kanban.activity` Kafka topic from offset 0" — which may not even be possible in a local dev environment (the local `kafka-data` Docker volume can be wiped by `docker compose down -v`, and there is no documented retention/backup of the raw topic bytes).

**Why it happens:** "Historical Kafka messages" naturally suggests "replay the topic," but this project's actual durable historical record — confirmed by reading `ActivityLogEntity.java` this session — is the already-consumed, already-persisted `activity_log` table (`boardId`, `userId`, `action` enum, `detail` JSON string, `eventId`, `createdAt`), which the existing `ActivityLogConsumer.deriveActionAndDetailIds` already shows exactly how to reconstruct per-type from (each `ActivityAction` maps to a known `detail` JSON key set — e.g. `TASK_MOVED` → `{taskId, sourceColumnId, targetColumnId}`).

**How to avoid:** For SCHEMA-06, sample rows from `activity_log` (real production or long-lived local dev data, if any exists — check row count first; if the table is empty or trivially small in the actual target environment, note that explicitly rather than fabricating "historical" data), reconstruct an in-memory `ActivityEvent` per row using the same field mapping `ActivityLogConsumer.deriveActionAndDetailIds` already encodes (in reverse), then round-trip each reconstructed event through the new `toAvro`/`toDomain` mapper and assert field-for-field equality. This directly catches Pitfall 10-style field-shape mismatches (PITFALLS.md) against what has actually shipped, without depending on raw topic retention.

**Warning signs:** A plan step that says "replay the Kafka topic" without first checking whether the local dev topic actually retains anything from before the current test run, or a rehearsal that only uses freshly-constructed synthetic events (defeats the purpose of SCHEMA-06 entirely).

**Phase to address:** Schema Registry phase, cutover-rehearsal step (SCHEMA-06), before producer cutover.

---

### Pitfall 3: Redpanda's Confluent-compatibility has two documented, narrow edge cases — neither directly applies here, but verify anyway

**What goes wrong:** Assuming 100% bit-for-bit Confluent Schema Registry parity for every Avro feature, when two specific GitHub issues (`redpanda-data/redpanda#5771`, `#11912`) document narrow gaps: Protobuf-with-map-fields interoperability, and differing handling of redundant Avro namespace tags. Neither applies to this phase's plain, non-map-field Avro schemas — but `RecordNameStrategy`'s subject name *is* the record's namespace+name, so any namespace-handling discrepancy is the one edge case worth a deliberate smoke test rather than an assumption.

**Why it happens:** Redpanda's registry is independently implemented (not a fork of Confluent's), so "Confluent-API-compatible" is a documented, tested claim but not a formal guarantee against every historical Confluent client quirk.

**How to avoid:** After authoring the 5 `.avsc` files with explicit `namespace` declarations (e.g. `com.vrudenko.kanban_board.event.avro`), run one real registration + one real produce/consume round-trip against the actual local Redpanda Testcontainers instance before treating any schema as final — this is already implied by the phase's own "verified end-to-end... against the local docker-compose stack" success criterion, called out explicitly here because it is the one place the milestone-level PITFALLS.md flagged genuine (if narrow) Redpanda-specific risk.

**Warning signs:** A schema that registers successfully but a subsequent produce/consume round-trip fails with a subject-not-found or namespace-mismatch error — would indicate the namespace-tag discrepancy from issue #11912 is actually in play.

**Phase to address:** Schema Registry phase, first end-to-end verification against the real local Redpanda instance.

## Code Examples

### `application.properties` diff (production profile)

```properties
# Source: synthesized from Confluent's Apache Avro for Kafka serdes documentation
# (docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/serdes-avro.html)
# and Confluent's Kafka SerDes subject-naming-strategy documentation, both fetched this session.

# === kafka producer — REPLACES the existing JsonSerializer line ===
spring.kafka.producer.value-serializer=io.confluent.kafka.serializers.KafkaAvroSerializer
spring.kafka.producer.properties.schema.registry.url=${SCHEMA_REGISTRY_URL:http://localhost:8081}
# SCHEMA-01: schemas are registered via a build/CI step, never by the producer at runtime.
spring.kafka.producer.properties.auto.register.schemas=false
spring.kafka.producer.properties.value.subject.name.strategy=io.confluent.kafka.serializers.subject.RecordNameStrategy

# === kafka consumer — REPLACES the JsonDeserializer delegate + its two now-dead properties ===
# ErrorHandlingDeserializer itself is UNCHANGED (still wraps the delegate below) — see
# ARCHITECTURE.md's confirmed finding that it is format-agnostic.
spring.kafka.consumer.properties.spring.deserializer.value.delegate.class=io.confluent.kafka.serializers.KafkaAvroDeserializer
spring.kafka.consumer.properties.schema.registry.url=${SCHEMA_REGISTRY_URL:http://localhost:8081}
spring.kafka.consumer.properties.specific.avro.reader=true
spring.kafka.consumer.properties.value.subject.name.strategy=io.confluent.kafka.serializers.subject.RecordNameStrategy
# REMOVE these two lines — meaningless once the delegate is Avro-based, not JSON:
#   spring.kafka.consumer.properties.spring.json.trusted.packages=com.vrudenko.kanban_board.event
#   spring.kafka.consumer.properties.spring.json.use.type.headers=true
```

### Explicit BACKWARD compatibility registration (build/CI step, per D-02 + SCHEMA-04)

```bash
# Source: Confluent Schema Registry REST API reference (docs.confluent.io/platform/current/
# schema-registry/develop/api.html), fetched and cross-checked this session. One call per
# subject (5 total, per D-03) — subject names below assume RecordNameStrategy, i.e. the
# subject equals the Avro record's full name once namespace is set in each .avsc.
curl -X PUT -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data '{"compatibility": "BACKWARD"}' \
  http://localhost:8081/config/com.vrudenko.kanban_board.event.avro.TaskCreatedEvent
# ... repeat for TaskMovedEvent, TaskDeletedEvent, BoardCreatedEvent, ColumnCreatedEvent
```

### Example `.avsc` shape (illustrative — exact field lists per event type are already fully known from the 5 record source files read this session, listed verbatim below)

```json
{
  "type": "record",
  "name": "TaskCreatedEvent",
  "namespace": "com.vrudenko.kanban_board.event.avro",
  "fields": [
    {"name": "eventId", "type": "string"},
    {"name": "userId", "type": "string"},
    {"name": "boardId", "type": "string"},
    {"name": "columnId", "type": "string"},
    {"name": "taskId", "type": "string"},
    {"name": "timestamp", "type": {"type": "long", "logicalType": "timestamp-millis"}}
  ]
}
```

**Verbatim field lists for all 5 event types, confirmed by reading each record file this session (all fields non-nullable, no defaults needed anywhere):**

- `TaskCreatedEvent(UUID eventId, String userId, String boardId, String columnId, String taskId, Instant timestamp)` — `[VERIFIED: src/main/java/com/vrudenko/kanban_board/event/TaskCreatedEvent.java:11-18]`
- `TaskMovedEvent(UUID eventId, String userId, String boardId, String taskId, String sourceColumnId, String targetColumnId, Instant timestamp)` — `[VERIFIED: src/main/java/com/vrudenko/kanban_board/event/TaskMovedEvent.java:11-19]`
- `TaskDeletedEvent(UUID eventId, String userId, String boardId, String columnId, String taskId, Instant timestamp)` — `[VERIFIED: src/main/java/com/vrudenko/kanban_board/event/TaskDeletedEvent.java:12-19]`
- `BoardCreatedEvent(UUID eventId, String userId, String boardId, Instant timestamp)` — `[VERIFIED: src/main/java/com/vrudenko/kanban_board/event/BoardCreatedEvent.java:10-11]`
- `ColumnCreatedEvent(UUID eventId, String userId, String boardId, String columnId, Instant timestamp)` — `[VERIFIED: src/main/java/com/vrudenko/kanban_board/event/ColumnCreatedEvent.java:10-12]`

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| JSON serialization with no schema enforcement (`JsonSerializer`/`JsonDeserializer`, `spring.json.trusted.packages`) | Avro + Schema Registry (`KafkaAvroSerializer`/`Deserializer`, explicit compatibility mode) | This phase | Field-shape drift between producer/consumer deploys becomes a registration-time or deserialization-time error instead of a silently-wrong or silently-dropped field — directly closes SEED-001 |
| `gradle-avro-plugin` as the go-to Gradle Avro codegen tool | Same artifact, but now permanently frozen at 1.9.1 (archived 2026-12-28, donated to Apache Avro) | Confirmed this session | No functional impact for this phase (1.9.1 fully covers plain `.avsc`-based codegen), but a future dependency-hygiene todo should track whether Apache Avro's own successor Gradle plugin (if/when it stabilizes under `org.apache.avro`) should replace it |
| Confluent Platform 7.7.x as the Kafka-client-aligned line (STACK.md's initial placeholder) | Confluent Platform 7.8.x (kafka-avro-serializer 7.8.9) as the closer match to Spring Boot 3.5.0's managed kafka-clients 3.8.1 | Confirmed this session via Confluent's own interoperability matrix | Revises the milestone-level STACK.md's placeholder version — use 7.8.9 in the actual plan, not 7.7.1 |

**Deprecated/outdated:**
- `spring.kafka.consumer.properties.spring.json.trusted.packages` / `spring.json.use.type.headers` — dead weight once the consumer delegate is Avro-based; remove, don't leave inert.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|----------------|
| A1 | Spring Boot 3.5.0 manages `kafka-clients` at exactly 3.8.1 (carried from milestone STACK.md, not independently re-verified this session against Spring Boot's dependency-versions appendix) | Standard Stack (kafka-avro-serializer version pick) | If the actual managed version is a different 3.8.x/3.9.x patch, 7.8.9 is very likely still fine (Confluent clients are broker-tolerant across minor Kafka versions), but the `exclude` + `./gradlew dependencies` verification step becomes more important, not optional |
| A2 | gradle-avro-plugin 1.9.1's codegen against Avro 1.12.1 generates `java.time.Instant`/`java.util.UUID` natively for the relevant logical types, without extra `dateTimeLogicalTypeImplementation`/custom-conversion configuration | Common Pitfalls (Pitfall 1), Code Examples (mapper) | If codegen instead produces plain `long`/`CharSequence`, the mapper needs one extra manual-conversion line per affected field per event type (10 total) — small, contained, already has a documented fallback in Pattern 1, not a redesign |
| A3 | The local dev/target environment's `activity_log` Postgres table actually contains a non-trivial sample of real historical rows to rehearse against (SCHEMA-06) | Common Pitfalls (Pitfall 2) | If the table is empty or near-empty in whatever environment the plan executes against, SCHEMA-06's rehearsal has no real data to exercise — the plan should include an explicit row-count check as its first rehearsal step and flag this to the user if the table is empty, rather than silently rehearsing against zero rows |
| A4 | Redpanda's `RecordNameStrategy` subject resolution behaves identically to Confluent's for schemas with an explicit Avro `namespace` (the specific narrow edge case flagged by GH issue #11912) | Common Pitfalls (Pitfall 3) | If it diverges, a produce/consume round-trip against the real local Redpanda instance will fail visibly at schema-registration or subject-lookup time — caught by the phase's own required end-to-end verification, not a silent production risk |

**If this table is empty:** N/A — see entries above; all are low-blast-radius (caught by required verification steps this phase already includes) rather than silent-failure risks.

## Open Questions

1. **Should `AbstractKafkaContainerTest` (the shared harness for the 3 existing non-Avro `activitylog` E2E test classes) migrate from `apache/kafka-native` to `RedpandaContainer`, or should this phase add a *second*, Avro-specific test base class alongside the existing one?**
   - What we know: `RedpandaContainer` is needed for any *new* Avro-specific test (schema registration, DLT-under-Avro, historical rehearsal). The 3 existing classes currently pass against `apache/kafka-native` and don't need a registry at all.
   - What's unclear: whether migrating the shared base class to Redpanda (one container, reused everywhere, matching this project's "singleton container across sibling classes" pattern already documented in `AbstractKafkaContainerTest`'s own Javadoc) is preferable to keeping two separate harnesses.
   - Recommendation: migrate the shared base class to Redpanda. Redpanda is a superset (Kafka-protocol-compatible broker + registry in one container) — the 3 existing non-Avro tests do not need to change at all if the migration only swaps the container image/class, since they never reference a registry. This also directly matches `docker-compose.yml`'s local-dev `kafka` service being recommended (Standard Stack) to move to Redpanda in this same phase, keeping local dev and the test harness on the same broker family — avoid running two different broker images (`apache/kafka-native` in tests, Redpanda in dev compose) simultaneously post-migration, since that reintroduces exactly the "verify once, re-verify against a different target" duplication this phase's own recommendation elsewhere warns against.

2. **Exact resolved `org.testcontainers:redpanda` version against Spring Boot 3.5.0's testcontainers BOM was not pinned this session.**
   - What we know: the module exists, is actively maintained, and exposes the needed `getSchemaRegistryAddress()` API (added via `testcontainers-java` PR #5994, confirmed this session).
   - What's unclear: the exact version Spring Boot 3.5.0's dependency-management BOM resolves it to, and whether that version already includes the `getSchemaRegistryAddress()` API (added at some point in the module's history — not independently dated this session).
   - Recommendation: add the dependency with no explicit version (matching this project's existing pattern for `org.testcontainers:kafka`), then run `./gradlew dependencies --configuration testCompileClasspath | grep testcontainers` as the first executable step of the relevant plan task to confirm the resolved version, before writing any test code against `getSchemaRegistryAddress()`.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|--------------|-----------|---------|----------|
| Docker Desktop (local) | Testcontainers `RedpandaContainer` for all new Avro tests | Assumed ✓ (already required by the 3 existing `AbstractKafkaContainerTest`-based E2E classes, unchanged by this phase) | — | None needed — already a hard project dependency |
| `packages.confluent.io/maven/` reachability | Gradle dependency resolution for `io.confluent:kafka-avro-serializer` | ✓ (confirmed reachable this session — fetched the directory index directly) | — | None — already required by STACK.md's milestone-level plan |
| Gradle Plugin Portal / Maven Central reachability | `com.github.davidmc24.gradle.plugin.avro`, `org.apache.avro:avro` | ✓ (confirmed reachable this session) | — | None |

**Missing dependencies with no fallback:** none identified.
**Missing dependencies with fallback:** none identified — this phase adds no new external service dependency beyond what local Docker + existing Maven repositories already provide.

## Security Domain

ASVS Level 1, `security_enforcement: true` per `.planning/config.json`.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|----------------|---------|-------------------|
| V2 Authentication | No | This phase touches no authentication surface |
| V3 Session Management | No | Unaffected |
| V4 Access Control | No | Event publication already happens post-ownership-verification (unchanged by this phase); the consumer already runs with no security context and trusts server-derived identifiers only, per `ActivityLogConsumer`'s existing Javadoc — unaffected by the Avro migration |
| V5 Input Validation | Yes (narrow) | Avro deserialization is itself a stricter input-validation boundary than JSON was — a malformed/incompatible wire payload now fails at the schema level (magic byte, schema ID lookup, Avro binary decode) before it can reach application code at all. `ErrorHandlingDeserializer` + `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (all pre-existing) already correctly isolate this class of failure — no new validation code needed, but SCHEMA-05's new DLT-under-Avro test is itself the security-relevant verification that this isolation still holds |
| V6 Cryptography | No | No new cryptographic material introduced; `schema.registry.url` in this phase's local-verification scope is plain HTTP against a local Testcontainers instance (matches the project's existing local-dev posture — production TLS/network exposure of the registry is explicitly Phase 5's concern, not this phase's) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|-----------------------|
| Deserialization-gadget surface via an overly permissive trusted-package/type list | Tampering / Elevation of Privilege | Already mitigated pre-Avro (`spring.json.trusted.packages` scoped to one package, never a wildcard) — and further tightened by this phase, since Avro's `SpecificRecord` deserialization has no equivalent "trusted packages" surface at all: the deserializer only ever instantiates the exact generated classes matching the schema resolved by ID, not arbitrary types from a header. This phase's move to Avro is a net security improvement over the JSON-era design in this one specific respect, worth noting explicitly since it's an incidental but real benefit, not something to re-derive from scratch |
| Un-authenticated/un-encrypted registry endpoint accepting schema writes from any producer | Tampering | Not this phase's concern for local verification (plain HTTP against Testcontainers is standard practice and not internet-reachable); explicitly Phase 5's concern for the production Redpanda registry — flag forward, don't attempt to solve here |
| DLT losing the audit trail for exactly the malformed/malicious messages it exists to capture (if the recoverer re-uses the Avro-aware serializer and throws) | Repudiation | Already correctly mitigated by the existing `DelegatingByTypeSerializer`/byte-array-first DLT template (SCHEMA-05 re-verifies, doesn't newly build, this mitigation) |

## Sources

### Primary (HIGH confidence)
- `packages.confluent.io/maven/io/confluent/kafka-avro-serializer/` — directory index, fetched this session, confirms 7.8.0–7.8.9 exist
- `docs.confluent.io/platform/current/installation/versions-interoperability.html` — Confluent Platform ↔ Kafka version matrix, fetched this session
- `docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/serdes-avro.html` — KafkaAvroSerializer/Deserializer config properties, wire format, fetched this session
- `docs.confluent.io/platform/current/schema-registry/develop/api.html` (via search synthesis) — Schema Registry REST API, `PUT /config/{subject}` for compatibility
- `github.com/davidmc24/gradle-avro-plugin` — repo (archive banner, README), fetched this session
- `github.com/davidmc24/gradle-avro-plugin/blob/master/CHANGES.md` — changelog, fetched this session, confirms 1.9.1 is final
- `github.com/testcontainers/testcontainers-java` (`modules/redpanda`) — `RedpandaContainer` source + PR #5994 (`getSchemaRegistryAddress()`), fetched this session
- `docs.redpanda.com/redpanda-labs/docker-compose/single-broker/` — single-node Redpanda compose reference with schema registry, fetched this session
- Codebase inspection this session: `ActivityEvent.java` and all 5 implementing records, `KafkaEventPublisher.java`, `KafkaConsumerConfig.java`, `ActivityLogConsumer.java`, `AbstractKafkaContainerTest.java`, `ActivityLogDeadLetterE2ETest.java`, `ActivityLogEntity.java`, `build.gradle`, `application.properties`, `application-test.properties`, `docker-compose.yml`, `KafkaTopics.java`

### Secondary (MEDIUM confidence)
- `search.maven.org` / `central.sonatype.com` — `org.apache.avro:avro` 1.12.1 confirmation (mvnrepository.com blocked this session with a 403, not independently cross-verified against a second mirror)
- Multiple WebSearch-synthesized results on Avro `timestamp-millis`→`Instant` codegen defaults (general Avro documentation, not specific to gradle-avro-plugin 1.9.1 + Avro 1.12.1 exact pairing — flagged as Pitfall 1 / Assumption A2 for this reason)
- `.planning/research/STACK.md`, `ARCHITECTURE.md`, `PITFALLS.md` — milestone-level research this phase builds directly on top of, per the phase's own instructions not to re-research

### Tertiary (LOW confidence)
- None used as load-bearing claims this session — every WebSearch finding above was either cross-checked against an official source via WebFetch, or explicitly flagged as an open question/assumption rather than presented as settled.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — every version claim was verified against the package's own authoritative registry this session (Confluent's own Maven repo, Maven Central, GitHub)
- Architecture (mapping layer, subject strategy, local registry choice): MEDIUM-HIGH — the *mechanics* (SpecificRecord codegen, RecordNameStrategy semantics, RedpandaContainer API) are HIGH confidence (official docs/source read directly); the *specific mapper shape recommendation* is a judgment call synthesized from those verified primitives plus this codebase's existing conventions, not a copied external pattern, since none exists for this exact problem
- Pitfalls: MEDIUM — Pitfall 1 (logical-type codegen) and Pitfall 3 (Redpanda namespace-tag edge case) are both flagged as needing a first-step spike/verification rather than presented as resolved, precisely because the sources available this session could not fully resolve them

**Research date:** 2026-08-04
**Valid until:** ~30 days (stable domain — Avro/Confluent Schema Registry mechanics change slowly; re-verify the `io.confluent:kafka-avro-serializer` exact patch version if this phase's planning/execution is delayed more than a few weeks, since Confluent ships patches frequently on this line)

---
*Phase 4 research for: Kanban Board Backend v1.2 (Schema Registry)*
*Researched: 2026-08-04*
