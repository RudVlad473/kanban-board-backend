# Phase 4: Schema Registry - Pattern Map

**Mapped:** 2026-08-04
**Files analyzed:** 11 (5 `.avsc` schemas, 1 mapper, 2 modified config classes, 1 modified consumer, `build.gradle`, `application.properties`, test harness)
**Analogs found:** 9 / 11 (2 have no direct in-repo analog: `.avsc` schema files, gradle-avro-plugin wiring — both purely new-technology additions with no existing project pattern to copy)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `src/main/avro/TaskCreatedEvent.avsc` (+4 siblings) | config/schema | transform | `src/main/java/com/vrudenko/kanban_board/event/TaskCreatedEvent.java` (+4 siblings) | role-match (field shape source, not a schema-file analog) |
| `src/main/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapper.java` | utility/mapper | transform | `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java` (`deriveActionAndDetailIds`, lines 69-98) | exact (exhaustive sealed-interface switch idiom) |
| `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java` (MODIFIED) | service/publisher | event-driven | itself (existing file, lines 1-61) | exact (same file, insert one mapper call) |
| `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java` (MODIFIED) | service/consumer | event-driven | itself (existing file, lines 1-108) | exact (same file, insert one mapper call before existing switch) |
| `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java` (MODIFIED — DLT re-verification only) | config | event-driven | itself (existing file, lines 84-134 `deadLetterKafkaTemplate`) | exact (no code change expected, only new test coverage) |
| `src/main/resources/application.properties` (MODIFIED — serde/schema-registry properties) | config | request-response/event-driven | itself (existing Kafka producer/consumer property block, lines ~49-79 per RESEARCH.md) | exact |
| `build.gradle` (MODIFIED — avro plugin, confluent repo, avro/kafka-avro-serializer deps, ErrorProne exclude) | config | batch/build | itself (existing `plugins{}`, `dependencies{}`, ErrorProne `excludedPaths` blocks) | exact |
| `src/test/java/.../activitylog/AbstractKafkaContainerTest.java` (MODIFIED or a new sibling `AbstractRedpandaContainerTest`) | test/harness | event-driven | itself | exact (RESEARCH.md recommends migrating this base class in place) |
| New Avro-specific DLT test (e.g. `ActivityLogAvroDeadLetterE2ETest.java`) | test | event-driven | `src/test/java/.../activitylog/ActivityLogDeadLetterE2ETest.java` (full file) | exact |
| New schema-registration/compatibility test or CI step | config/test | batch | `KafkaConsumerConfig`'s `@Bean NewTopic activityTopic()`/`activityDeadLetterTopic()` (declarative provisioning pattern) + RESEARCH.md's `curl PUT /config/{subject}` example | role-match (no existing registration-step analog in repo; closest structural parallel is declarative topic provisioning) |
| New SCHEMA-06 historical-rehearsal test/harness | test | batch/transform | `ActivityLogConsumer.deriveActionAndDetailIds` (reverse direction) + `ActivityLogRepository` usage in `ActivityLogDeadLetterE2ETest` (lines 38, 179-183) | role-match |

## Pattern Assignments

### `src/main/avro/*.avsc` (5 files) — schema definitions

**No in-repo analog** (new file type/technology). Field lists must be copied verbatim from the 5 existing domain records (already verified field-by-field in RESEARCH.md's Code Examples section):

- `TaskCreatedEvent` — `src/main/java/com/vrudenko/kanban_board/event/TaskCreatedEvent.java`
- `TaskMovedEvent` — `src/main/java/com/vrudenko/kanban_board/event/TaskMovedEvent.java` (read in full, lines 1-16):
```java
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
- `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent` — same package, same shape convention (all fields non-nullable `String`/`UUID`/`Instant`).

**Parent contract** — `src/main/java/com/vrudenko/kanban_board/event/ActivityEvent.java` (full file, 28 lines): sealed interface with `permits` clause naming all 5 records; every implementation shares `eventId`, `userId`, `boardId`, `timestamp`. This is the authoritative field-shape source for every `.avsc` file — do not invent fields not present here.

**Illustrative `.avsc` shape** (from RESEARCH.md, cross-checked against Confluent docs):
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
All fields required, no defaults (per RESEARCH.md's Alternatives Considered — no field in any of the 5 domain records has ever been legitimately nullable).

---

### `src/main/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapper.java` (new mapper, service/transform)

**Analog:** `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java`, `deriveActionAndDetailIds` (lines 69-98)

**Core exhaustive-switch pattern to copy** (no `default` arm — sealed interface exhaustiveness is compiler-enforced):
```java
private ActionAndDetailIds deriveActionAndDetailIds(ActivityEvent event) {
    return switch (event) {
        case TaskCreatedEvent e -> {
            var ids = new LinkedHashMap<String, String>();
            ids.put("columnId", e.columnId());
            ids.put("taskId", e.taskId());
            yield new ActionAndDetailIds(ActivityAction.TASK_CREATED, ids);
        }
        case TaskMovedEvent e -> { /* ... */ }
        case TaskDeletedEvent e -> { /* ... */ }
        case BoardCreatedEvent e -> new ActionAndDetailIds(ActivityAction.BOARD_CREATED, new LinkedHashMap<>());
        case ColumnCreatedEvent e -> { /* ... */ }
    };
}
```
Apply the identical idiom for `toAvro(ActivityEvent) -> SpecificRecord` (switch over the sealed interface, one arm per record, each building the matching generated Avro `newBuilder()`), and `toDomain(SpecificRecord) -> ActivityEvent` (type-based dispatch over the 5 generated classes — `default` arm throwing `IllegalArgumentException` is required here since `SpecificRecord` is not itself sealed, unlike the domain-side switch).

**Component annotation convention** — plain `@Component` (this codebase's mapper convention splits: MapStruct `@Mapper(componentModel = SPRING)` for straightforward Entity↔DTO field copies — see `src/main/java/com/vrudenko/kanban_board/mapper/ActivityLogMapper.java` below — vs. hand-rolled `@Component` for sealed-interface dispatch that MapStruct cannot generate). This mapper falls in the latter category per RESEARCH.md's explicit finding that MapStruct cannot target a sealed interface.

**Reference for a plain MapStruct-style mapper** (for contrast, not to copy) — `src/main/java/com/vrudenko/kanban_board/mapper/ActivityLogMapper.java` (full file, 20 lines):
```java
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ActivityLogMapper {
    ActivityLogResponseDTO toActivityLogResponseDTO(ActivityLogEntity entity);
}
```

---

### `KafkaEventPublisher.java` (MODIFIED — insert mapper call)

**Analog:** itself, `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java` (full file, 61 lines)

**Insertion point** — inside `onActivityEvent`, before `kafkaTemplate.send(...)` (lines 45-59):
```java
@Async("kafkaPublishExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onActivityEvent(ActivityEvent event) {
    var unused =
            kafkaTemplate
                    .send(KafkaTopics.ACTIVITY, event.eventId().toString(), event)
                    .whenComplete(
                            (result, ex) -> {
                                if (ex != null) {
                                    log.error(
                                            "Failed to publish {} (eventId={}, boardId={}) to {}",
                                            event.getClass().getSimpleName(),
                                            event.eventId(),
                                            event.boardId(),
                                            KafkaTopics.ACTIVITY,
                                            ex);
                                }
                            });
}
```
**D-01 resilience pattern to preserve verbatim:** the `whenComplete` error callback is the single, uniform failure-handling point — a schema-registry-down or schema-rejected error surfaces here identically to a broker-down error (no new branching). Do not add a separate try/catch around the new `mapper.toAvro(event)` call unless a mapping error is a genuinely different failure class (it is not, per D-01) — let any `RuntimeException` from `toAvro` propagate into the same `@Async` method so it is caught by the same async-exception path already governing this method, or simply have `toAvro` return the mapped Avro record and pass it to `kafkaTemplate.send()`'s type parameter unchanged in structure.

**Type parameter change note:** `KafkaTemplate<String, Object>` stays `Object`-typed (Avro `SpecificRecord` still satisfies `Object`); no signature change needed, only the value passed to `.send()` changes from the domain record to `mapper.toAvro(event)`.

---

### `ActivityLogConsumer.java` (MODIFIED — insert mapper call, switch stays unchanged)

**Analog:** itself, `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java` (full file, 108 lines)

**Insertion point** — `onActivityEvent` currently receives `ActivityEvent event` directly (line 37) because Spring's `JsonDeserializer` decodes to the domain type already. Once the delegate deserializer becomes `KafkaAvroDeserializer`, the `@KafkaListener` payload type becomes the generated `SpecificRecord` (or a common supertype), and this method's first line must call `mapper.toDomain(payload)` to reconstruct the `ActivityEvent` before calling `deriveActionAndDetailIds` — that method (lines 69-98) and everything below it in this file is explicitly unchanged (SCHEMA-02's hard requirement):
```java
@KafkaListener(topics = KafkaTopics.ACTIVITY, groupId = ActivityLogConsumer.GROUP_ID)
public void onActivityEvent(SpecificRecord avroRecord) {
    ActivityEvent event = activityEventAvroMapper.toDomain(avroRecord);
    var mapped = deriveActionAndDetailIds(event);
    // ... rest of method body UNCHANGED
}
```
Inject the new mapper the same way existing dependencies are injected in this class (field injection, no constructor injection per project convention):
```java
@Autowired private ActivityLogRecorder activityLogRecorder;
@Autowired private ObjectMapper objectMapper;
@Autowired private ActivityEventAvroMapper activityEventAvroMapper; // NEW
```

---

### `KafkaConsumerConfig.java` (DLT path — re-verify, do not modify)

**Analog:** itself, `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java`, `deadLetterKafkaTemplate` bean (lines 84-134) and `activityErrorHandler` bean (lines 168-201)

**No code change expected** per SCHEMA-05/RESEARCH.md's Anti-Patterns section — the `DelegatingByTypeSerializer` keyed on `byte[].class` (line 124) already routes any dead-lettered raw payload correctly regardless of what format the main path uses:
```java
var delegates = new LinkedHashMap<Class<?>, Serializer<?>>();
delegates.put(byte[].class, new ByteArraySerializer());
delegates.put(Object.class, new JsonSerializer<>());
```
Only a new test (see below) re-verifies this. Do not add an Avro-aware branch to this serializer — RESEARCH.md's Anti-Patterns explicitly warns that reusing the Avro serializer for the DLT path would throw trying to re-encode a payload that just failed to decode.

---

### `application.properties` (MODIFIED — serde config)

**Analog:** itself — the existing producer/consumer Kafka property block (per RESEARCH.md, lines ~49-79)

**Exact diff to apply** (from RESEARCH.md's Code Examples, cross-checked against Confluent docs):
```properties
# producer — replaces existing JsonSerializer line
spring.kafka.producer.value-serializer=io.confluent.kafka.serializers.KafkaAvroSerializer
spring.kafka.producer.properties.schema.registry.url=${SCHEMA_REGISTRY_URL:http://localhost:8081}
spring.kafka.producer.properties.auto.register.schemas=false
spring.kafka.producer.properties.value.subject.name.strategy=io.confluent.kafka.serializers.subject.RecordNameStrategy

# consumer — replaces JsonDeserializer delegate
spring.kafka.consumer.properties.spring.deserializer.value.delegate.class=io.confluent.kafka.serializers.KafkaAvroDeserializer
spring.kafka.consumer.properties.schema.registry.url=${SCHEMA_REGISTRY_URL:http://localhost:8081}
spring.kafka.consumer.properties.specific.avro.reader=true
spring.kafka.consumer.properties.value.subject.name.strategy=io.confluent.kafka.serializers.subject.RecordNameStrategy
# REMOVE (dead once delegate is Avro-based):
#   spring.kafka.consumer.properties.spring.json.trusted.packages=com.vrudenko.kanban_board.event
#   spring.kafka.consumer.properties.spring.json.use.type.headers=true
```
`ErrorHandlingDeserializer` itself (wrapping the delegate) stays unchanged — confirmed format-agnostic in ARCHITECTURE.md/KafkaConsumerConfig's own Javadoc (line 37).

---

### `build.gradle` (MODIFIED — new plugin/deps)

**Analog:** itself — existing `plugins{}` (lines 1-7), `repositories{}` (lines 32-34), `dependencies{}` (lines 36-101), ErrorProne `excludedPaths` (line 157)

**Pattern to follow** (comment-annotated dependency style already used throughout, e.g. lines 54-60, 96-99):
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
Extend the existing ErrorProne exclusion regex (line 157) to also cover the new Avro codegen output directory, matching the existing MapStruct exclusion:
```gradle
excludedPaths = '.*[/\\\\]build[/\\\\](generated|generated-main-avro-java)[/\\\\].*'
```

---

### Test harness — `AbstractKafkaContainerTest.java` (MODIFIED, migrate to Redpanda) or new sibling

**Analog:** itself, `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java` (full file, 117 lines)

**Imperative singleton-container pattern to preserve exactly** (lines 84-92) — this is a hard-won fix for a real static-initializer-ordering bug documented at length in this file's Javadoc; do not revert to `@Testcontainers`/`@Container`:
```java
@ServiceConnection
static final KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.3.1"));

static {
    kafka.start();
}
```
RESEARCH.md's Open Question 1 recommends swapping this for `RedpandaContainer` in place (same imperative-static-init pattern, same `@ServiceConnection`), exposing `getSchemaRegistryAddress()` alongside the existing `getBootstrapServers()`. Also preserve the `System.setProperty("api.version", "1.44")` Docker-engine-compat workaround (lines 80-82) and the `sendAndAwaitAck` helper (lines 110-115) unchanged.

---

### New DLT-under-Avro test (SCHEMA-05)

**Analog:** `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogDeadLetterE2ETest.java` (full file, 225 lines)

**Pattern to copy directly** — raw byte-level producer/consumer built with plain `kafka-clients` API (not Spring), bypassing the Avro serde entirely to inject genuinely poison bytes:
```java
private KafkaProducer<String, byte[]> buildRawByteProducer() {
    var props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    return new KafkaProducer<>(props);
}
```
And the byte-fidelity assertion idiom (never decode before comparing — lines 82-100, 137-143):
```java
Assertions.assertThat(deadLetteredValue).isEqualTo(poisonBytes);
```
For SCHEMA-05, the new test additionally needs one case using bytes with a *valid* magic-byte-prefixed Avro wire format but an unknown/unregistered schema ID, to prove the registry-aware deserializer failure path also dead-letters correctly (not just malformed-JSON-style poison, which was the prior test's only case under the JSON delegate).

**AAA structure, `@Nested` grouping, `Awaitility` polling** — copy the `DeadLetterRoutingTest`/`DeadLetterFidelityTest`/`NonBlockingTest` `@Nested` class structure (lines 102-224) verbatim as the shape for the new Avro-specific test class.

---

### SCHEMA-06 historical-data rehearsal (new test/harness)

**Analog (reverse-direction reuse):** `ActivityLogConsumer.deriveActionAndDetailIds` (lines 69-98) documents exactly which `detail` JSON keys each `ActivityAction` produces; the rehearsal harness must reconstruct an `ActivityEvent` per persisted row by reversing this mapping.

**Repository access pattern to copy** — `ActivityLogRepository` field-injection + `findAll()`/filtering idiom already used in `ActivityLogDeadLetterE2ETest` (lines 38, 179-183):
```java
@Autowired private ActivityLogRepository activityLogRepository;
// ...
var rowsForBoard =
        activityLogRepository.findAll().stream()
                .filter(row -> row.getBoardId().equals(boardId))
                .toList();
```
Per RESEARCH.md Pitfall 2, source rows from `activity_log` (via this repository), not raw topic replay — check row count first per Assumption A3, and flag explicitly if the target environment's table is empty/trivial rather than fabricating synthetic "historical" data.

## Shared Patterns

### D-01 async, post-commit, non-blocking Kafka publish
**Source:** `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java` (full file)
**Apply to:** No structural change — the `@Async("kafkaPublishExecutor")` + `@TransactionalEventListener(phase = AFTER_COMMIT)` dispatch mechanism is unchanged by this phase; only the payload passed to `kafkaTemplate.send()` changes (domain record → Avro record via the new mapper).

### Exhaustive sealed-interface switch, no `default` arm
**Source:** `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java`, `deriveActionAndDetailIds` (lines 69-98); mirrored in `ActivityEvent.java`'s own `permits` clause (lines 17-21)
**Apply to:** `ActivityEventAvroMapper.toAvro()` (domain → Avro direction only — `toDomain()` needs a `default` arm since `SpecificRecord` is not itself sealed).

### Byte-preserving DLT serializer, unaffected by main-path format
**Source:** `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java`, `deadLetterKafkaTemplate` (lines 84-134)
**Apply to:** No modification required; only new test coverage (SCHEMA-05) needed to re-prove the guarantee holds when the main path is Avro instead of JSON.

### Field-injection-only dependency wiring (no constructor injection)
**Source:** `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java` (lines 33-34), `KafkaEventPublisher.java` (line 34)
**Apply to:** All new/modified classes in this phase — `ActivityEventAvroMapper` injection into `ActivityLogConsumer`, any new mapper's own dependencies.

### Comment-annotated, versioned Gradle dependency declarations
**Source:** `build.gradle` (e.g. spring-session-jdbc rationale comment, lines 54-60; ErrorProne version-pinning rationale, lines 127-158)
**Apply to:** New `com.github.davidmc24.gradle.plugin.avro`, `org.apache.avro:avro`, `io.confluent:kafka-avro-serializer`, `org.testcontainers:redpanda` entries — document the archived-plugin caveat and the `kafka-clients` exclude rationale inline, matching this file's existing practice of explaining *why*, not just *what*.

## No Analog Found

| File | Role | Data Flow | Reason |
|------|------|-----------|--------|
| `src/main/avro/*.avsc` (5 files) | config/schema | transform | No prior Avro/schema-file convention exists in this repo; author from the verbatim field lists in RESEARCH.md's Code Examples section, cross-checked against `ActivityEvent`'s 5 implementing records |
| Schema-registration build/CI step (`PUT /config/{subject}`) | config/build | batch | No prior build-time external-service-registration step exists in this repo (topics are provisioned via Spring `@Bean NewTopic`, an in-process pattern, not an external CLI/REST call); use RESEARCH.md's Confluent REST API example directly as the source of truth |

## Metadata

**Analog search scope:** `src/main/java/com/vrudenko/kanban_board/{config,activitylog,event,mapper}/`, `src/test/java/com/vrudenko/kanban_board/activitylog/`, `build.gradle`, `application.properties`
**Files scanned:** `KafkaEventPublisher.java`, `ActivityLogConsumer.java`, `KafkaConsumerConfig.java`, `ActivityEvent.java`, `TaskMovedEvent.java`, `ActivityLogMapper.java`, `AbstractKafkaContainerTest.java`, `ActivityLogDeadLetterE2ETest.java`, `build.gradle` (full)
**Pattern extraction date:** 2026-08-04
