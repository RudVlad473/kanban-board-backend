# Stack Research

**Domain:** Kafka-based event-driven activity log, added to an existing Spring Boot 3.5.0 / Java 21 REST API
**Researched:** 2026-08-01
**Confidence:** MEDIUM (version numbers verified directly against the pinned `spring-boot-dependencies` BOM at the `v3.5.0` git tag — a primary source — but the fetch tooling available this session classifies as LOW-tier by default; cross-check before merging if in doubt. See Sources.)

This file covers ONLY the NEW additions needed for the v1.1 Kafka activity-feed milestone (Epic 1 of the backend modernization plan). Everything already validated and in `build.gradle` (Spring Boot 3.5.0, Java 21, Spring Data JPA, Spring Security, MapStruct, springdoc-openapi, Lombok, ULID Creator, Vavr, Guava, REST Assured, H2, `@Version` optimistic locking) is out of scope — already shipped in v1.0, do not re-research or re-version. The prior milestone's JPA/Hibernate stack research that previously lived in this file has been superseded; it's preserved in git history and in `PROJECT.md`'s Validated section / `docs/plans/backend-modernization/STATUS.md`.

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| `spring-kafka` | **3.3.6** (BOM-managed — do not pin explicitly) | Producer/consumer abstraction (`KafkaTemplate`, `@KafkaListener`, `DefaultErrorHandler`) over the raw Kafka client | This is the exact version Spring Boot 3.5.0's own `spring-boot-dependencies` BOM pins (verified by reading the `build.gradle` of the `spring-boot` repo at the `v3.5.0` git tag directly — a primary source, not a blog post). The project already applies `io.spring.dependency-management` (line 4 of `build.gradle`), so declaring `implementation 'org.springframework.kafka:spring-kafka'` with **no version string** — same pattern already used for `spring-boot-starter-security`, `spring-boot-starter-web`, etc. — resolves the correct, tested-together version automatically. |
| `org.apache.kafka:kafka-clients` | **3.9.1** (transitive via `spring-kafka`, do not declare directly) | Underlying Kafka wire-protocol client | Pulled in transitively by `spring-kafka`; only add directly if you need a client-only feature `spring-kafka` doesn't expose (not the case here). |
| `apache/kafka-native` (Docker image) | Track `:latest` or pin to a current 4.x tag (e.g. `4.1.2`) | Single-node KRaft (no Zookeeper) local broker for `docker-compose.yml` | Epic spec explicitly calls for the native KRaft image, no separate Zookeeper service. `apache/kafka-native` is the GraalVM ahead-of-time-compiled variant of the official `apache/kafka` image — same env-var config surface, faster cold start and lower memory, which matters for the `docker compose up` inner-dev-loop. Client/broker version skew (broker on Kafka 4.x, client lib on 3.9.x wire protocol) is a non-issue: Kafka brokers are backward compatible with older client protocol versions. |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.testcontainers:kafka` | BOM-managed → **1.21.0** (do not pin explicitly) | Spins up a real containerized Kafka broker for integration tests | Add as `testImplementation`, no version string — same BOM-managed convention already used for `com.h2database:h2`. This is the artifact the epic spec names explicitly. Note: a separately-versioned `org.testcontainers:testcontainers-kafka` module (2.x line, part of a Testcontainers-Java 2.0 rebrand) exists in the wider ecosystem, but Spring Boot 3.5.0 does **not** manage that line — use the classic `org.testcontainers:kafka` coordinate so it resolves cleanly off the BOM already in play. |
| `org.testcontainers:junit-jupiter` | BOM-managed | JUnit 5 integration (`@Testcontainers`, `@Container`) for the Kafka container lifecycle | Needed alongside `org.testcontainers:kafka` for the integration test the epic calls for (publish `TaskMovedEvent` end-to-end through a real broker, assert `ActivityLogEntity` row appears). |
| `org.springframework.boot:spring-boot-testcontainers` | BOM-managed | Enables `@ServiceConnection` on a `KafkaContainer` bean | Lets Spring Boot auto-wire `spring.kafka.bootstrap-servers` (and related `spring.kafka.*` connection props) from the running Testcontainers Kafka instance with **zero manual `@DynamicPropertySource` wiring** — one annotation on a `@TestConfiguration`-declared container bean. This is the idiomatic Spring Boot 3.1+ pattern and keeps the new test config terse and consistent with how clean the rest of this codebase's tests are. |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| `docker-compose.yml` (new, repo root) | Full local dev environment: `postgres` + `kafka` (native KRaft) + the app | Currently absent from the repo — only a `Dockerfile` exists. Single-node KRaft combined mode (broker+controller in one process) needs: `KAFKA_NODE_ID`, `KAFKA_PROCESS_ROLES=broker,controller`, `KAFKA_LISTENERS` (a `PLAINTEXT` listener for clients + a `CONTROLLER` listener), `KAFKA_ADVERTISED_LISTENERS`, `KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER`, `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP`, `KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093`, and — specifically for single-node — `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1` (the internal `__consumer_offsets` topic defaults to replication factor 3 and will fail to come up with only one broker otherwise). Official reference compose files live in `apache/kafka`'s own repo under `docker/examples/docker-compose-files/single-node/` — pull the exact YAML from there rather than hand-assembling it, to avoid a subtly wrong listener/advertised-listener combination (a very common Kraft-in-Docker footgun). |
| Kafka UI (optional, e.g. `provectuslabs/kafka-ui` or Redpanda Console) | Ad-hoc topic/message inspection during local dev | Not required by the epic spec. A cheap add (one more `docker-compose.yml` service, zero app code) if you want to visually confirm message shape and DLT routing while building the consumer — mention as optional, skip if you want to keep the compose file minimal and reviewable. |

## Installation

```bash
# build.gradle additions (Gradle/Groovy DSL, matching existing file style)

# Core — versions omitted deliberately; resolved via Spring Boot's
# io.spring.dependency-management BOM (already applied, line 4 of build.gradle)
implementation 'org.springframework.kafka:spring-kafka'

# Testing — same BOM-managed convention already used for com.h2database:h2
testImplementation 'org.testcontainers:kafka'
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
```

No `npm`/lockfile step — this is a Gradle project; the four lines above are the complete dependency-side change. Run `./gradlew build` once added to confirm resolution. No version bump to `io.spring.dependency-management` (currently 1.1.6) is needed.

### `application.properties` additions

```properties
# === Kafka ===
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
spring.kafka.consumer.group-id=kanban-activity-log
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
spring.kafka.consumer.properties.spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.properties.spring.json.trusted.packages=com.vrudenko.kanban_board.event
```

Follows the existing file's `# === section ===` comment convention (see `spring.datasource.*`, `spring.jpa.*` blocks already there). `KAFKA_BOOTSTRAP_SERVERS` should join `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` as an env var supplied both by the new `docker-compose.yml` (pointing at the `kafka` service, e.g. `kafka:9092`) and by whatever mechanism supplies the Postgres env vars in the EC2 deploy today (needs a decision: does v1.1 also stand up Kafka in production, or is the Kafka activity feed local/dev-only for now? — flagged as an open question below). `ErrorHandlingDeserializer` wrapping `JsonDeserializer` is what lets a malformed/poison message be handed to `DefaultErrorHandler` → `DeadLetterPublishingRecoverer` instead of killing the listener container outright — this directly enables the epic's dead-letter-topic requirement.

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|--------------------------|
| `org.testcontainers:kafka` (real containerized broker) | Spring's `@EmbeddedKafka` / `spring-kafka-test` (in-JVM fake broker) | `@EmbeddedKafka` boots faster and is fine for pure unit-level producer/consumer wiring tests, but doesn't exercise real broker partition/offset/redelivery semantics as faithfully — which is exactly what's needed to credibly demonstrate idempotent-consumption and dead-letter-topic behavior. The epic spec already names Testcontainers directly; that choice is correct as scoped. Reach for `@EmbeddedKafka` instead only if the test suite later grows many more fast unit-style Kafka tests and Testcontainers startup cost becomes a CI bottleneck. |
| `apache/kafka-native` (native KRaft image) | `apache/kafka` (JVM KRaft image), or `confluentinc/cp-kafka` / Bitnami Kafka images | Plain `apache/kafka` (JVM) is a safe fallback if the native image has a compatibility hiccup locally — same env-var contract, just slower cold start. `confluentinc/cp-kafka` / Bitnami are heavier, bring Confluent-specific tooling/licensing surface not needed for a local dev broker, and aren't what the epic spec asks for — skip them. |
| No explicit version on `spring-kafka` / `org.testcontainers:kafka` (BOM-managed) | Pinning explicit versions | Only pin explicitly if there's a specific documented reason to diverge from Boot's tested-together dependency set (e.g. a CVE fix not yet in the BOM) — and if so, override via the `kafka.version` / `spring-kafka.version` / `testcontainers.version` Gradle properties Spring's dependency-management plugin exposes, not a bare version string on the dependency line, so the override stays visible and centralized. |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|--------------|
| Zookeeper-based Kafka setup (`wurstmeister/kafka`, `confluentinc/cp-zookeeper` + `cp-kafka` pair, etc.) | Zookeeper mode is legacy for new Kafka deployments; adds an extra container and moving part for zero benefit on a single-node local dev broker | KRaft mode, single combined broker+controller process, via `apache/kafka-native` — explicitly what the epic spec calls for |
| Manually pinning `kafka-clients` / `spring-kafka` versions independent of the Spring Boot BOM | Easy path to a version-skew bug (e.g. a `spring-kafka` version expecting client APIs not present in a manually-pinned `kafka-clients`) that Boot's own compatibility testing doesn't cover | Let `io.spring.dependency-management` resolve both from the Boot 3.5.0 BOM (already applied to this project); omit version strings entirely, exactly as done for `spring-boot-starter-*` today |
| `@DynamicPropertySource` + manually constructed `KafkaContainer` property wiring for the integration test | More boilerplate than necessary; this project doesn't use this pattern anywhere today, so introducing it here would be inconsistent with the rest of the test suite | `@ServiceConnection` on a Testcontainers `KafkaContainer` bean (via `spring-boot-testcontainers`) — one annotation, matches the "as clean and reviewable as the rest of the modernization plan" bar set in `PROJECT.md` |
| A full microservice extraction of the activity-log consumer | Explicitly out of scope per `PROJECT.md` ("Full microservice extraction of the activity-log consumer... not this one") | In-process `@KafkaListener` in the same Spring Boot app, in a new `com.vrudenko.kanban_board.activitylog` package |
| Kafka Streams / ksqlDB | No stream-processing requirement here — this is a single producer to single consumer to DB-write pipeline, not a topology | Plain `spring-kafka` producer (`KafkaTemplate`) + `@KafkaListener` consumer, as scoped |

## Stack Patterns by Variant

**If the dead-letter topic needs a fixed, predictable name (`kanban.activity.dlt`, per the epic spec) rather than Spring's default `{topic}.DLT` suffix:**
- Supply a custom destination resolver (a `BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>`) to the `DeadLetterPublishingRecoverer` constructor instead of the default single-arg form
- Because the epic spec explicitly names `kafka.activity.dlt` as the target, not Spring's default `kanban.activity.DLT`

**If idempotent consumption needs to survive consumer restarts/rebalances, not just in-memory dedup:**
- Use `ActivityLogRepository.existsByEventId(...)` as a DB-backed idempotency check (as the epic spec already specifies) rather than an in-memory `Set<UUID>` or Kafka-native exactly-once semantics (transactional producer/consumer)
- Because DB-backed dedup survives process restarts and is simpler to reason about than configuring end-to-end Kafka transactions for a feature this scoped; exactly-once semantics would be a large scope increase not justified by the epic's stated goals

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|------------------|-------|
| `spring-boot` 3.5.0 | `spring-kafka` 3.3.6 (BOM-managed) | Verified directly against `spring-boot-dependencies`' `build.gradle` at the `v3.5.0` git tag — the exact pinned version, not inferred from release notes |
| `spring-boot` 3.5.0 | `org.apache.kafka:kafka-clients` 3.9.1 (transitive via `spring-kafka`) | Same source as above |
| `spring-boot` 3.5.0 | `org.testcontainers` BOM 1.21.0 | Same source as above; use the classic `org.testcontainers:kafka` module coordinate at this version line, not the newer `testcontainers-kafka` 2.x rebrand, which Boot 3.5.0 does not manage |
| `kafka-clients` 3.9.x (app-side client) | `apache/kafka-native` broker 4.x (local dev image) | Kafka brokers are wire-protocol backward compatible with older client versions; a 3.9.x client against a 4.x broker is a normal, supported combination — differing version numbers across the client/broker boundary are expected, not a bug |
| `io.spring.dependency-management` 1.1.6 (already in `build.gradle`) | All of the above | No plugin version bump required; it just needs to import the Boot 3.5.0 BOM, which it already does via the `org.springframework.boot` plugin block |

## Open Questions for Roadmap / Phase Planning

- **Production Kafka:** `PROJECT.md` and the epic spec both frame this primarily as a local-dev/portfolio-demonstration feature (`docker-compose.yml` for local dev). It's not yet decided whether v1.1 also stands up a managed Kafka broker in production (EC2 deploy target) or whether the activity-feed feature is dev/demo-only until a later milestone. This affects whether `KAFKA_BOOTSTRAP_SERVERS` needs a production value wired into the deploy pipeline now or can default to `localhost:9092` safely for this milestone. Flag for roadmap phase-1 scoping.
- **Dead-letter topic auto-creation:** confirm whether `kanban.activity` and `kanban.activity.dlt` topics should be auto-created (`spring.kafka.template.default-topic` + broker `auto.create.topics.enable=true`, the KRaft image's default) or explicitly declared via `NewTopic` `@Bean`s (more explicit, more reviewable, catches partition-count/replication-factor decisions at code-review time rather than implicitly at runtime). Recommend explicit `NewTopic` beans for a portfolio-quality diff — call out during phase planning.

## Sources

- GitHub raw `build.gradle` at `spring-projects/spring-boot` tag `v3.5.0` (`spring-boot-project/spring-boot-dependencies/build.gradle`) — direct read of the pinned `kafka` (3.9.1), `spring-kafka` (3.3.6), and `testcontainers` (1.21.0) version properties. This is a primary source (the actual tagged release's build file), so treat the version numbers above as reliable even though the generic tooling classification for this fetch method defaults to LOW confidence this session — cross-checked, not guessed.
- [docs.spring.io — Handling Exceptions (Spring for Apache Kafka reference)](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) — `DefaultErrorHandler` / `DeadLetterPublishingRecoverer` pattern, default `{topic}.DLT` naming, custom destination resolver — MEDIUM confidence (official framework reference docs)
- [Apache Kafka docs — Docker](https://kafka.apache.org/41/getting-started/docker/) and [apache/kafka `docker/examples/README.md`](https://github.com/apache/kafka/blob/trunk/docker/examples/README.md) — official image names/tags (`apache/kafka`, `apache/kafka-native`, current release line 4.1.x) and pointer to the official single-node KRaft compose examples — LOW-MEDIUM confidence (official project docs, but the exact compose YAML itself was not directly retrieved this session — pull the actual file from `docker/examples/docker-compose-files/single-node/` in the `apache/kafka` repo before finalizing the project's `docker-compose.yml`)
- General web search (built-in `WebSearch` tool) corroborating the KRaft single-node env-var set (`KAFKA_NODE_ID`, `KAFKA_PROCESS_ROLES`, `KAFKA_CONTROLLER_QUORUM_VOTERS`, `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`) and the Testcontainers-vs-`@EmbeddedKafka` / `@ServiceConnection` patterns — LOW confidence per this session's tooling tier (no MCP-backed search/docs provider — Context7, Exa, Brave, Tavily, Firecrawl — was available; all reported unavailable via the research-plan seam, so `WebSearch`/`WebFetch` fallback was used throughout). Cross-check the exact compose file and error-handling code against current official docs before merging.

---
*Stack research for: Kafka event-driven activity feed (v1.1 milestone, Epic 1 of backend modernization plan)*
*Researched: 2026-08-01*
