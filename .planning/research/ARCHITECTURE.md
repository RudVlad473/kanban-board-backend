# Architecture Research

**Domain:** Infra migration (Neon Postgres + self-hosted Redpanda on Oracle Cloud) + Schema Registry (Avro) integration into an existing Spring Boot 3.5/Java 21 monolith
**Researched:** 2026-08-03
**Confidence:** MEDIUM (cross-checked against Neon/Redpanda/Spring Kafka official docs and multiple independent sources; no first-party benchmark run against the actual OCI A1 Flex target was possible from this research pass)

## Standard Architecture

### System Overview — what's staying, what's new

```
┌───────────────────────────────────────────────────────────────────────────┐
│  Oracle Cloud A1 Flex VM (ARM64/Ampere, Docker Compose)                   │
│  ┌─────────────────────────┐        ┌────────────────────────────────┐   │
│  │  Spring Boot app          │        │  Redpanda (single-node broker)  │   │
│  │  (unchanged: controller-   │◄──────┤  + built-in Schema Registry     │   │
│  │   service-repo layers,    │ Kafka  │  (API-compatible w/ Confluent) │   │
│  │   Spring Security/Session)│ proto  │  ports: 9092 (kafka), 8081 (SR) │   │
│  └───────────┬───────────────┘        └────────────────────────────────┘   │
│              │ JDBC over TLS (sslmode=require)                             │
└──────────────┼──────────────────────────────────────────────────────────────┘
               ▼
   ┌─────────────────────────────┐
   │  Neon serverless Postgres    │   (external, scale-to-zero, PgBouncer
   │  (replaces local/RDS Postgres)│   pooler endpoint available)
   └─────────────────────────────┘

GitHub Actions: test → spotlessCheck → build image → push → SSH deploy to OCI VM
(same shape as existing deploy.yml, target host/secrets swapped)
```

Nothing in the controller → service → repository → entity stack changes. The only architectural deltas are: (1) the datasource endpoint moves off-box and gains a TLS/cold-start dimension, (2) the Kafka broker becomes Redpanda instead of `apache/kafka-native`, both self-hosted but one is now the deploy target not just local dev, and (3) a Schema Registry client is threaded into the existing single `KafkaEventPublisher` (producer) / `KafkaConsumerConfig` + `ActivityLogConsumer` (consumer) touchpoints.

### Component Responsibilities — new/modified only

| Component | Status | Responsibility | Notes |
|-----------|--------|-----------------|-------|
| `application.properties` datasource block | **Modified** | JDBC URL, SSL mode, Hikari pool sizing | Add `sslmode=require`, tune `spring.datasource.hikari.*` |
| `docker-compose.yml` `kafka` service | **Replaced** | Broker definition | Swap `apache/kafka-native` block for a Redpanda `redpanda start` command block; different env-var/flag vocabulary |
| `KafkaConsumerConfig` (producer/consumer factory beans) | **Modified** | Wires `NewTopic` beans, `KafkaTemplate`s, `DefaultErrorHandler`+DLT recoverer | Serializer/deserializer *properties* change (Avro), bean wiring itself (topics, error handler, DLT template) is untouched |
| `KafkaEventPublisher` | **Unmodified** (Java) / **modified config only** | Publishes `ActivityEvent` after commit | Continues to call `kafkaTemplate.send(topic, key, event)` — Avro serialization happens transparently in the configured `ProducerFactory`'s serializer, *if* `event` is Avro-serializable (see Integration Points) |
| `ActivityLogConsumer` | **Modified** | Deserializes and records activity events | Exhaustive `switch` over the sealed `ActivityEvent` interface must still receive that same Java type after Avro deserialization — needs a translation step (see below) |
| New: Avro schema files (`.avsc`, 5 of them) | **New** | One schema per `ActivityEvent` record type | Generated Java classes via Gradle Avro plugin, checked into `build/generated` like MapStruct output |
| New: `schema.registry.url` config | **New** | Points producer + consumer at the registry | Local dev: standalone container or Redpanda's built-in registry; prod: Redpanda's built-in registry on the OCI VM |
| GitHub Actions `deploy.yml` | **Modified** | CI/CD target | `EC2_HOST`/`EC2_USER`/`EC2_SSH_KEY` secrets renamed/repointed at the OCI VM; add a DDL-verification job against Neon |

## Integration Points (answers to the four research questions)

### (a) Neon Postgres — not a drop-in beyond the connection string

**SSL/TLS is mandatory, not optional.** Neon requires SSL/TLS on every connection. The current `spring.datasource.url=jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}` has no `sslmode` parameter at all. The Postgres JDBC driver's default (`sslmode=prefer`) will usually still negotiate TLS opportunistically against a server that mandates it, but this should not be left implicit for a deploy-target change: add `?sslmode=require` explicitly, and prefer `&channelBinding=require` since Neon's default auth is SCRAM-SHA-256, which channel binding hardens against MITM. This is a one-line property change, but it is a **required** change, not optional — the milestone claim of "no JPA/Hibernate code changes" is accurate (this is a connection-string/property change, not an entity/repository change), but it is not merely "swap host/user/pass" either.

**Connection pooling has a real interaction with scale-to-zero, and it isn't solved by HikariCP alone.** Neon's own architecture already puts PgBouncer in front of Postgres (its "pooled connection" endpoint, `...-pooler...neon.tech`), separate from Spring Boot's app-side HikariCP pool. Two decisions follow:
- **Direct (non-pooled) endpoint vs. pooler endpoint:** Neon's pooler defaults to PgBouncer *transaction* pooling mode, which does not preserve session-level state across statements in a transaction the way Hibernate's server-side prepared-statement cache and some session-scoped features assume. For an app this size (personal/portfolio, low concurrency), connecting to Neon's **direct (non-pooled) endpoint** with a small HikariCP pool (`maximumPoolSize` in the 3-10 range) is the safer default — it avoids PgBouncer-transaction-mode/Hibernate incompatibilities entirely, and Neon's direct-connection limits are generous enough for this traffic level. Only reach for the pooler endpoint if connection-count pressure actually appears.
- **Cold-start latency is a real, measurable number, not theoretical.** Neon computes typically take 300ms-1s to wake from idle (measured cold-start range across multiple sources), and scale-to-zero triggers after 5 minutes of inactivity by default (configurable 5 min-7 days, or disable entirely). HikariCP's default `connectionTimeout` (30s) already comfortably absorbs a single cold start, so the default is not actually a functional blocker — but the *first request after any idle period* will visibly stall by up to ~1s, which is worth documenting as expected behavior (not a bug) for this project's demo/portfolio audience. If that latency is judged unacceptable, the only real fix is disabling scale-to-zero on the branch used for production — which trades away the specific cost-guard rationale (Neon free tier) driving this migration in the first place. Recommendation: **keep scale-to-zero on** (matches the "cost-guarded" goal explicit in PROJECT.md) and treat the occasional cold-start delay as an accepted, documented trade-off rather than something to engineer around with keep-alive pings.

### (b) Redpanda — mostly a drop-in for the app, not for docker-compose

**Application code (`KafkaEventPublisher`, `ActivityLogConsumer`, the `KafkaConsumerConfig` bean wiring) needs zero changes.** Redpanda implements the Kafka wire protocol; any Kafka client (Kafka clients v0.11+, which spring-kafka's underlying client is) talks to it with only a `bootstrap.servers`/`spring.kafka.bootstrap-servers` change. This confirms the milestone's own claim.

**`docker-compose.yml`'s `kafka` service block will be replaced wholesale, not edited in place.** `apache/kafka-native` is configured via `KAFKA_*`-prefixed environment variables mapping to Kafka's server.properties (`KAFKA_PROCESS_ROLES`, `KAFKA_CONTROLLER_QUORUM_VOTERS`, etc. — all currently in `docker-compose.yml`). Redpanda's official image is instead configured via `rpk redpanda start` CLI flags in the `command:` block (`--smp`, `--memory`, `--overprovisioned`, `--node-id`, `--kafka-addr`, `--advertise-kafka-addr`). These are two different configuration surfaces — none of the existing `KAFKA_*` env vars carry over. A minimal single-node Redpanda service looks like:
```yaml
redpanda:
  image: docker.redpanda.com/redpandadata/redpanda:latest
  command:
    - redpanda
    - start
    - --smp=1
    - --memory=1G
    - --overprovisioned
    - --node-id=0
    - --kafka-addr internal://0.0.0.0:9092,external://0.0.0.0:19092
    - --advertise-kafka-addr internal://redpanda:9092,external://<vm-host>:19092
    - --schema-registry-addr 0.0.0.0:8081
  ports:
    - "19092:19092"
    - "8081:8081"
```
`--overprovisioned` is not cosmetic — Redpanda's thread-per-core design otherwise assumes dedicated bare-metal cores and can refuse to start or perform badly on a shared/virtualized VM without it.

**ARM64 is supported** — Redpanda ships official `linux/arm64/v8` images and `rpk` ARM64 binaries, so the OCI A1 Flex (Ampere/ARM) target is not a blocker, but this should be explicitly verified once (pull and boot the image on the actual VM) rather than assumed, since some ecosystem tooling around Redpanda (e.g. certain Connect plugins) has historically lagged on ARM parity even where the core broker hasn't.

**Sizing:** Redpanda recommends ~2GB RAM per core. OCI's Always Free A1 Flex tier provides up to 4 OCPU / 24GB total (shared across up to 4 instances, or one instance using all of it) — comfortably sufficient for a single-node Redpanda instance (`--smp=1 --memory=1G` is enough for this project's traffic) running alongside the Spring Boot app on the same VM. The app and Redpanda will be co-resident and competing for the same VM's cores/RAM (Postgres is no longer local — it moved to Neon), so this is a two-process box now, not three.

**Replication/ISR: no meaningful change, because a single-node cluster can't do otherwise.** Redpanda's cluster-wide default replication factor is 1 (versus Kafka's typical production default of 3) — for a single broker, replication factor >1 is impossible anyway, so this "difference" is moot in practice: `KafkaConsumerConfig`'s existing `NewTopic` beans already hardcode `.replicas(1)` for both `kanban.activity` and `kanban.activity.dlt`, which already matches Redpanda's reality with no change needed. The docker-compose `apache/kafka-native` block's explicit `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1` / `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1` env vars (needed there because Kafka's own defaults assume a multi-broker cluster) simply have no Redpanda equivalent to carry over — Redpanda's internal topics self-configure to the cluster's actual broker count. Net effect: this whole category of Kafka-cluster-sizing configuration disappears from the compose file rather than needing translation.

**Health check can be upgraded, though it isn't required to be.** The current healthcheck is a bare TCP-connect against port 19092, documented in `docker-compose.yml` as a workaround for `apache/kafka-native`'s bare-alpine runtime having no admin-script tree. Redpanda ships `rpk` in its image, so `rpk cluster health` or `rpk topic list` become available as real readiness probes instead of a socket-open proxy — worth doing since it directly improves the fragile "socket accepts, but KRaft controller might not have settled" caveat already called out in the current compose file's comments, but not a hard requirement for the migration to function.

### (c) Schema Registry client — where it plugs in, and the one non-trivial gap

**Producer side (`KafkaEventPublisher` / its `ProducerFactory`):** No change to `KafkaEventPublisher.java` itself — it stays a thin `kafkaTemplate.send(topic, key, event)` call. The change is entirely in configuration: `spring.kafka.producer.value-serializer` moves from `org.springframework.kafka.support.serializer.JsonSerializer` to `io.confluent.kafka.serializers.KafkaAvroSerializer`, plus a new `spring.kafka.producer.properties.schema.registry.url=http://<registry-host>:8081`. Because Redpanda's built-in Schema Registry is API-compatible with Confluent's Schema Registry (confirmed: works with existing Confluent-SDK clients, no code changes), the same `KafkaAvroSerializer`/`KafkaAvroDeserializer` classes from Confluent's client libraries work unchanged against Redpanda's registry — only the URL differs.

**The one real gap: `KafkaAvroSerializer` needs Avro-shaped types, and the app's `ActivityEvent` sealed interface is deliberately plain POJOs.** `ActivityEvent.java`'s own Javadoc states it is "plain, dependency-free records only — no Lombok, no JPA, no Spring annotations." Confluent's `KafkaAvroSerializer` serializes `GenericRecord` or generated `SpecificRecord` classes, not arbitrary Java records via reflection. This means the 5 event types (`TaskCreatedEvent`, `TaskMovedEvent`, `TaskDeletedEvent`, `BoardCreatedEvent`, `ColumnCreatedEvent`) each need a paired `.avsc` schema and Gradle-generated Avro class, and `KafkaEventPublisher` needs a translation step immediately before `.send()` — domain event → Avro DTO — mirroring the Entity↔DTO MapStruct pattern already used elsewhere in this codebase. **Recommend keeping the domain-event records Avro-agnostic and mapping at the publish boundary**, not converting `ActivityEvent` itself into Avro-generated classes — that would break the "plain, dependency-free" design intent the events package explicitly calls out, and would also break `ActivityLogConsumer`'s existing exhaustive `switch (event) { case TaskCreatedEvent e -> ... }` pattern unless the consumer maps the incoming Avro-generated type back to the sealed interface first. Either way, a mapping layer is required on both ends — this is the single largest net-new piece of application code this phase actually needs, and it should be sized/planned as such rather than treated as "just a serializer property."

**Consumer side (`KafkaConsumerConfig`'s deserializer chain):** `ErrorHandlingDeserializer` itself is unaffected — it is deliberately format-agnostic; it wraps whatever "delegate" deserializer is configured and only cares about catching exceptions from it. The change is: `spring.kafka.consumer.properties.spring.deserializer.value.delegate.class` moves from `JsonDeserializer` to `KafkaAvroDeserializer`, plus `spring.kafka.consumer.properties.schema.registry.url=...` and (recommended) `spring.kafka.consumer.properties.specific.avro.reader=true` to get typed generated classes back rather than generic `GenericRecord`s. Two JsonDeserializer-specific properties become dead weight and should be removed rather than left in place: `spring.json.trusted.packages` and `spring.json.use.type.headers` — both are meaningless once the delegate is Avro-based.

**Does the DLT byte-fidelity guarantee still hold with Avro? Yes, for the case it was built for — and that case is unaffected by format.** `DeadLetterPublishingRecoverer`'s byte-preservation behavior (since Spring Kafka 2.3) triggers specifically off the `DeserializationException` header that `ErrorHandlingDeserializer` attaches when its delegate throws — at that point the recoverer republishes the **raw bytes that failed to deserialize**, regardless of what deserializer format was configured. This is a Spring Kafka mechanic keyed on `ErrorHandlingDeserializer`+`DeserializationException`, not on JSON specifically, so swapping the delegate from `JsonDeserializer` to `KafkaAvroDeserializer` does not change this behavior at all — a malformed/incompatible Avro payload still dead-letters with its original bytes intact, exactly like a malformed JSON payload does today. Separately (and pre-existing, not something this migration changes either way): if `ActivityLogConsumer.onActivityEvent` throws *after* successful deserialization (e.g. the detail-map serialization failure it already handles explicitly, or a downstream DB failure not absorbed by `ActivityLogRecorder`), `DeadLetterPublishingRecoverer` republishes the already-deserialized Java object through the DLT-specific `KafkaTemplate`'s `DelegatingByTypeSerializer`, which routes any non-`byte[]` object through `JsonSerializer` today — this path was never byte-for-byte to begin with (it re-serializes a live object as JSON, not the original wire bytes), and Avro adoption doesn't change or regress that; it's an orthogonal, pre-existing characteristic of the DLT design, not a new gap introduced by this phase.

### (d) Build order across the two phases

**The two phases are largely independent; do Schema Registry first.** Roughly 95% of the schema-registry phase's work — the 5 `.avsc` schemas, Gradle Avro codegen wiring, the producer-side mapping layer, the consumer-side deserializer config, and the DLT-behavior-under-Avro verification — can be built and fully verified against the **existing local `docker-compose.yml`** stack, using either a standalone Confluent-compatible schema-registry container pointed at the current `apache/kafka-native` broker, or (simpler, one fewer moving part) temporarily standing up a local single-node Redpanda in place of `apache/kafka-native` in dev before the Oracle/Redpanda production migration exists at all. Either way, none of this depends on the OCI VM, Neon, or GitHub Actions changes existing first.

Recommend this order:
1. **Schema Registry phase** — built and verified entirely against the local dev stack (docker-compose + a schema-registry container, Testcontainers-backed tests matching this codebase's existing convention of verifying Kafka behavior against real containers, not mocks). Produces: `.avsc` schemas, codegen, mapping layer, updated serializer/deserializer config, DLT-under-Avro verification.
2. **Infra migration phase** — Oracle Cloud VM, Neon, Redpanda docker-compose replacement, GitHub Actions retargeting, new DDL-verification-against-Neon step. The only schema-registry-related task that lands *here* rather than in phase 1 is the last-mile cutover: repoint `schema.registry.url` from the local/standalone registry to Redpanda's built-in registry on the OCI VM, and re-run the same verification suite against that real target once it exists.

This ordering is deliberate, not arbitrary: it isolates the phase that touches **application source code and design intent** (schema registry — new mapping layer, new dependency on Avro codegen, a real architectural decision about where domain events end and wire format begins) from the phase that is **entirely infrastructure/ops** (Neon connection string + Hikari tuning, Redpanda docker-compose swap, CI/CD secrets) with, per the milestone's own stated goal, zero JPA/Hibernate or producer/consumer/DLQ code changes. Bundling both into one phase would conflate an app-logic change with a pure-ops change, making either harder to review, test, or roll back independently — which cuts against this project's existing "one-epic-per-PR" discipline (see CLAUDE.md's PR-discipline constraint). The reverse order (migration first) would work too, but offers no benefit and needlessly blocks the more code-heavy, more interesting phase on unrelated ops work (VM provisioning, DNS/networking, secrets rotation) landing first.

## Anti-Patterns to Avoid

### Anti-Pattern 1: Treating "no code changes" as "no config changes"
**What people do:** Read the milestone framing ("zero JPA/Hibernate code changes," "zero producer/consumer/DLQ code changes") as license to swap host/port values only.
**Why it's wrong:** Neon requires an explicit `sslmode`/`channelBinding` addition to the JDBC URL and a deliberate Hikari-pool-sizing decision informed by scale-to-zero; Redpanda requires a fully different docker-compose service block (different config vocabulary entirely, not just a different image tag).
**Do this instead:** Treat "no code changes" as scoped precisely to `src/main/java` — application source is genuinely untouched — while still budgeting real work for `application.properties`, `docker-compose.yml`, and CI secrets.

### Anti-Pattern 2: Converting `ActivityEvent` itself into Avro-generated classes
**What people do:** Point Gradle's Avro codegen output directly at the existing `event` package and use the generated `SpecificRecord` classes as the domain event types.
**Why it's wrong:** Breaks the explicitly-documented "plain, dependency-free records" design intent in `ActivityEvent.java`, and breaks `ActivityLogConsumer`'s exhaustive sealed-interface `switch` unless it's rewritten around Avro-generated types instead.
**Do this instead:** Keep `ActivityEvent` as-is; add a mapping layer (Avro DTO ↔ domain event) at the publish and consume boundaries, following this codebase's existing Entity↔DTO/MapStruct convention.

### Anti-Pattern 3: Connecting to Neon's pooler endpoint by default "because pooling is good"
**What people do:** Assume PgBouncer-in-front-of-HikariCP is strictly better because "more pooling."
**Why it's wrong:** Neon's pooler defaults to PgBouncer transaction-mode, which can silently break Hibernate's server-side prepared-statement caching and other session-scoped JDBC behavior — a subtle correctness risk, not just a performance one.
**Do this instead:** Use Neon's direct (non-pooled) endpoint with a small, explicit HikariCP pool for this project's traffic level; only reconsider if connection-count pressure is actually observed.

## Integration Points Summary

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| Neon Postgres | JDBC, `spring.datasource.*` properties | Requires `sslmode=require`; prefer direct (non-pooled) endpoint; keep HikariCP pool small |
| Redpanda (self-hosted, same VM) | Kafka protocol, `spring.kafka.bootstrap-servers` | App code unchanged; docker-compose service block fully replaced |
| Redpanda Schema Registry (built-in) | HTTP, `schema.registry.url` property | API-compatible with Confluent client libs; same `KafkaAvroSerializer`/`KafkaAvroDeserializer` classes work |

### Internal Boundaries (new)

| Boundary | Communication | Notes |
|----------|---------------|-------|
| `KafkaEventPublisher` ↔ new Avro mapping layer | Direct method call before `kafkaTemplate.send()` | New code; translates domain `ActivityEvent` → Avro-generated DTO |
| `ActivityLogConsumer` ↔ new Avro mapping layer | Direct method call on receipt | New code; translates Avro-generated DTO → domain `ActivityEvent` before the existing exhaustive switch |

## Sources

- [Connection latency and timeouts — Neon Docs](https://neon.com/docs/connect/connection-latency)
- [Connection pooling — Neon Docs](https://neon.com/docs/connect/connection-pooling)
- [Connect a Java application to Neon Postgres — Neon Docs](https://neon.com/docs/guides/java)
- [Connect to Neon securely — Neon Docs](https://neon.com/docs/connect/connect-securely)
- [Why Postgres needs better connection security defaults — Neon](https://neon.com/blog/postgres-needs-better-connection-security-defaults)
- [Manage Topics — Redpanda Streaming Docs](https://docs.redpanda.com/current/develop/config-topics/)
- [Topic Configuration Properties — Redpanda Self-Managed Docs](https://docs.redpanda.com/current/reference/topic-properties/)
- [Kafka Compatibility — Redpanda Self-Managed Docs](https://docs.redpanda.com/current/develop/kafka-clients/)
- [Redpanda Schema Registry overview — Redpanda Self-Managed Docs](https://docs.redpanda.com/current/manage/schema-reg/schema-reg-overview/)
- [Produce and consume Avro messages with Redpanda schema registry](https://www.redpanda.com/blog/produce-consume-apache-avro-tutorial)
- [Requirements and Recommendations — Redpanda Self-Managed Docs](https://docs.redpanda.com/current/deploy/redpanda/manual/production/requirements/)
- [Sizing Guidelines — Redpanda Self-Managed Docs](https://docs.redpanda.com/current/deploy/redpanda/manual/sizing/)
- [Handling Exceptions — Spring Kafka reference docs](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
- [Dead Letter Topics: Routing Failed Messages with DeadLetterPublishingRecoverer](https://blog.devops-monk.com/tutorials/spring-kafka/dead-letter-topics/)
- [Spring Kafka Beyond the Basics — Confluent blog](https://www.confluent.io/blog/spring-kafka-can-your-kafka-consumers-handle-a-poison-pill/)
- [Guide to Spring Cloud Stream with Kafka, Apache Avro and Confluent Schema Registry — Baeldung](https://www.baeldung.com/spring-cloud-stream-kafka-avro-confluent)
- [Oracle Cloud Infrastructure Arm Compute](https://www.oracle.com/cloud/compute/arm/)
- Codebase inspection: `docker-compose.yml`, `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java`, `KafkaEventPublisher.java`, `activitylog/ActivityLogConsumer.java`, `event/ActivityEvent.java`, `constant/KafkaTopics.java`, `src/main/resources/application.properties`, `.github/workflows/deploy.yml`

---
*Architecture research for: infra migration + schema registry integration into existing kanban-board-backend*
*Researched: 2026-08-03*
