---
phase: 04-schema-registry
plan: 02
subsystem: infra
tags: [avro, kafka, schema-registry, redpanda, confluent, activity-log, docker-compose]

# Dependency graph
requires:
  - phase: 04-01
    provides: 5 Avro schemas, gradle-avro-plugin codegen, ActivityEventAvroMapper, resolved org.testcontainers:redpanda version
provides:
  - Live Avro pipeline (KafkaAvroSerializer/Deserializer) against a real Confluent-API-compatible registry (Redpanda)
  - AvroSchemaRegistrar -- the sole schema-writing path, invoked from both the registerSchemas Gradle task and AbstractKafkaContainerTest's static init
  - RecordNameStrategy wiring giving all 5 event types independent subjects on kanban.activity
  - BACKWARD compatibility explicitly set per subject, with a demonstrated rejection/acceptance pair
  - Local docker-compose stack with Redpanda broker + built-in registry, verified end-to-end
  - Measured full-suite wall-clock baseline for the Avro cutover (~231-237s vs ~208s pre-phase)
affects: [04-03, 04-04]

# Actuals (#2632)
actuals:
  tokens: 14865
  tasks: 3
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Single registration implementation (AvroSchemaRegistrar) called from both the build/CI task and test setup, so registration cannot drift between contexts"
    - "@DynamicPropertySource for schema.registry.url in the shared test harness -- no Spring Boot ConnectionDetails type exists for a schema registry, unlike Kafka bootstrap-servers (which Spring Boot 3.5.0 does support natively for RedpandaContainer via @ServiceConnection)"
    - "Registry client's own retry/timeout defaults (independent of Kafka producer bounds) explicitly bounded in the fixture-heavy test profile, then raised back up in the real-broker test harness"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogAvroRoundTripE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/SchemaCompatibilityE2ETest.java
  modified:
    - build.gradle
    - src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java
    - src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java
    - src/main/resources/application.properties
    - src/main/resources/application-test.properties
    - src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java
    - docker-compose.yml

key-decisions:
  - "Spring Boot 3.5.0 ships a RedpandaContainerConnectionDetailsFactory (wires Kafka bootstrap-servers via @ServiceConnection automatically) but no equivalent ConnectionDetails type for a schema registry -- schema.registry.url is wired explicitly via @DynamicPropertySource in the shared test harness instead"
  - "CachedSchemaRegistryClient's own retry/timeout config (max.retries=3, 1-20s backoff, 60s connect/read timeout by default) is entirely independent of the Kafka producer's max.block.ms/request.timeout.ms/delivery.timeout.ms bounds -- the registry HTTP call runs inside serialization, before those Kafka-level bounds ever apply. Bounded to fail-fast in the fixture-heavy test profile; raised back up in the real-broker harness where the registry actually responds"
  - "Measured, not assumed: bounding the registry client's retry defaults did NOT measurably reduce full-suite wall-clock (two runs: 232s and 237s, both essentially unchanged from an unbounded control run). This falsifies the a priori hypothesis that the 17 fixture-heavy classes' registry-lookup retries were on the critical path -- they run on the async kafkaPublishExecutor thread, decoupled from test-thread timing, so bounding them is correct hygiene but not the explanation for the wall-clock delta. The delta is better attributed to the new tracer test class plus one-time Redpanda container/schema-registration startup"
  - "docker-compose.yml's Redpanda service preserves the previous kafka service's exact Kafka port numbering (9092 host-exposed, 19092 internal) so existing host tooling needs no reconfiguration; the schema registry gets a single, unsplit listener on 8081 since REST has no advertised-address complexity to split between internal/external"
  - "ActivityLogConsumerE2ETest's pre-existing timestamp assertion widened from within(1, MICROS) to within(1, MILLIS): Avro's timestamp-millis logical type truncates to millisecond precision by design (already confirmed in 04-01), a real and already-accepted property of the schema, not a regression the old JSON-era tolerance was ever designed to absorb"

patterns-established:
  - "Subject names always derived from schema.getFullName() (RecordNameStrategy), never hardcoded string literals -- a schema rename cannot silently orphan a subject"
  - "Compatibility set BEFORE first registration (with a register-then-set fallback for registries that reject a compatibility write against a not-yet-existing subject), documented inline as deliberate ordering"

requirements-completed: [SCHEMA-01, SCHEMA-02, SCHEMA-03, SCHEMA-04]

coverage:
  - id: D1
    description: "Producer and consumer speak genuine Avro (Confluent magic byte + 4-byte schema id, not JSON) against a real registry, with the persisted activity_log row matching the published event's fields"
    requirement: "SCHEMA-03"
    verification:
      - kind: e2e
        ref: "ActivityLogAvroRoundTripE2ETest#FullRoundTripTest.shouldPersistMatchingActivityLogRow_whenTaskMovedEventPublishedThroughRealAvroPipeline"
        status: pass
      - kind: e2e
        ref: "ActivityLogAvroRoundTripE2ETest#WireFormatTest.shouldEncodeAsGenuineAvro_whenTaskMovedEventPublishedThroughRealPipeline"
        status: pass
    human_judgment: false
  - id: D2
    description: "ActivityLogConsumer.deriveActionAndDetailIds and the ActivityEvent sealed hierarchy are byte-identical to their pre-cutover state; only the mapping layer changed at the two Kafka boundaries"
    requirement: "SCHEMA-02"
    verification:
      - kind: other
        ref: "git diff HEAD~3 -- src/main/java/com/vrudenko/kanban_board/event/*.java (empty) and ActivityLogConsumer.java (deriveActionAndDetailIds/ActionAndDetailIds unchanged)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Schemas are registered exclusively via AvroSchemaRegistrar (registerSchemas Gradle task + test setup); the producer's auto.register.schemas=false means it can only look schemas up, never write them"
    requirement: "SCHEMA-01"
    verification:
      - kind: other
        ref: "grep -c 'auto.register.schemas=false' application.properties; AvroSchemaRegistrar carries no Spring stereotype; ./gradlew tasks --all lists registerSchemas"
        status: pass
    human_judgment: false
  - id: D4
    description: "All 5 production subjects explicitly report BACKWARD compatibility, provably subject-level (not inherited from the registry's global default)"
    requirement: "SCHEMA-04"
    verification:
      - kind: e2e
        ref: "SchemaCompatibilityE2ETest#ConfiguredCompatibilityTest (both tests)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The registry demonstrably rejects a backward-incompatible schema evolution (409) and accepts a backward-compatible one (control case)"
    requirement: "SCHEMA-04"
    verification:
      - kind: e2e
        ref: "SchemaCompatibilityE2ETest#EnforcementTest.shouldRejectIncompatibleEvolution_andAcceptCompatibleEvolution_underBackward"
        status: pass
    human_judgment: false
  - id: D6
    description: "The three pre-existing activitylog E2E classes (Consumer, Idempotency, DeadLetter) pass unchanged against the new Avro path, exercising all 5 event types"
    requirement: "SCHEMA-02"
    verification:
      - kind: e2e
        ref: "ActivityLogConsumerE2ETest, ActivityLogIdempotencyE2ETest, ActivityLogDeadLetterE2ETest -- full class runs"
        status: pass
    human_judgment: false
  - id: D7
    description: "Full ./gradlew test suite is green; wall-clock measured against the ~208s pre-phase baseline rather than assumed"
    verification:
      - kind: integration
        ref: "./gradlew test -- 0 failures across 3 consecutive full-suite runs (232s, 237s, 231s)"
        status: pass
    human_judgment: true
    rationale: "The suite is unambiguously green (0 failures, verified 3 times), but whether an ~11% wall-clock increase (208s -> ~231-237s) counts as an acceptable cost for this cutover is a project-tolerance judgment, not a fact this executor can certify. The regression is measured, explained (new tracer test class + one-time Redpanda container/schema-registration startup -- NOT the schema-registry-retry-storm hypothesized and then falsified by measurement), and nowhere near the catastrophic unbounded-block failure mode (20-minute hang) the phase's threat model was built to prevent -- but it is a real number, not a within-noise reading like the project's prior 208s-vs-210s comparison."
  - id: D8
    description: "docker compose up brings up a Redpanda broker with a working schema registry; the app publishes and consumes against it end-to-end (not just Testcontainers)"
    verification:
      - kind: other
        ref: "docker compose up -d (redpanda reported healthy) -> ./gradlew registerSchemas -PschemaRegistryUrl=http://localhost:8081 -> curl localhost:8081/subjects (exactly 5 names) -> curl localhost:8081/config/<subject> (BACKWARD) -> real column-creation mutation against the running app -> matching COLUMN_CREATED row in activity_log"
        status: pass
    human_judgment: false

duration: ~70min
completed: 2026-08-04
status: complete
---

# Phase 4 Plan 2: Live Avro Cutover, Compatibility Enforcement & Local Registry Summary

**The activity-log pipeline now speaks genuine Avro end-to-end against a real Redpanda-hosted Confluent-compatible registry — BACKWARD compatibility is enforced and demonstrated (a 409 on an incompatible evolution, a clean accept on a compatible one), and the local docker-compose stack has a registry the running app actually publishes and consumes against.**

## Performance

- **Duration:** ~70 min
- **Started:** ~2026-08-04T14:19:00+02:00 (approx.)
- **Completed:** 2026-08-04T15:23:00+02:00
- **Tasks:** 3 completed
- **Files modified:** 12 (3 created, 9 modified)

## Accomplishments

- Built `AvroSchemaRegistrar` — the single, no-Spring-stereotype registration implementation invoked identically from a new `registerSchemas` Gradle task and from `AbstractKafkaContainerTest`'s static initializer, deriving all 5 schemas from `getClassSchema()` and all 5 subject names from `schema.getFullName()` (no hardcoded strings), setting BACKWARD before each subject's first registration with a register-then-set fallback documented inline
- Migrated `AbstractKafkaContainerTest` from `apache/kafka-native` to a single-node Redpanda container (broker + built-in registry), preserving the imperative static-init start and the `api.version` Docker Engine pin verbatim; discovered mid-task that Spring Boot 3.5.0's `@ServiceConnection` support for `RedpandaContainer` wires Kafka bootstrap-servers automatically but has no equivalent for the schema registry, so `schema.registry.url` is wired via `@DynamicPropertySource` instead
- Cut `KafkaEventPublisher`/`ActivityLogConsumer` over to Confluent's `KafkaAvroSerializer`/`KafkaAvroDeserializer` against the registry, with `auto.register.schemas=false` and `RecordNameStrategy` on both sides (SCHEMA-01, SCHEMA-03, D-03); deleted (not commented out) the two JSON-era `trusted.packages`/`use.type.headers` properties, since Avro's specific-reader deserialization has no equivalent deserialization-gadget surface to guard
- Proved the tracer end-to-end with `ActivityLogAvroRoundTripE2ETest`: one `TaskMovedEvent` published through the real pipeline lands as a matching `activity_log` row, and a separate raw byte-array consumer confirms the wire is genuinely Avro (Confluent magic byte + 4-byte schema id) — not a silent JSON fallback
- All three pre-existing `activitylog` E2E classes (Consumer, Idempotency, DeadLetter) pass with zero source changes against the new Avro path except one now-necessary timestamp-tolerance widening (see Deviations) — exercising all 5 event types end to end
- Turned BACKWARD compatibility from a configured setting into a demonstrated behaviour with `SchemaCompatibilityE2ETest`: all 5 production subjects report BACKWARD via a no-fallback query that only succeeds for a genuinely explicit override (contrasted against a fresh, never-configured subject where the identical call fails); a backward-incompatible schema evolution is rejected with HTTP 409, and a backward-compatible one is accepted as a control
- Replaced `docker-compose.yml`'s `kafka` service with a single-node Redpanda service (broker + registry), preserving the previous exact Kafka port numbering for host tooling continuity, dropping the now-inapplicable `user: root` override and bare-TCP healthcheck in favour of a genuine `rpk cluster health` probe
- Verified the full local stack live: `docker compose up -d` → healthy → `./gradlew registerSchemas -PschemaRegistryUrl=http://localhost:8081` → `curl localhost:8081/subjects` listed exactly the 5 expected subjects, each confirmed BACKWARD → a real column-creation mutation against the running app produced a matching `COLUMN_CREATED` row in `activity_log` within seconds
- Measured the full suite three times post-cutover (232s, 237s, 231s) against the ~208s pre-phase baseline — a real, explained, bounded ~11% regression, not the catastrophic unbounded-block failure mode the phase's threat model was built to prevent

## Task Commits

Each task was committed atomically:

1. **Task 1: Schema registration step (SCHEMA-01) with BACKWARD compatibility (D-02), plus the Redpanda test harness** - `71ff6e3` (feat)
2. **Task 2: TRACER — cut producer and consumer over to Avro and prove one event type's full round trip** - `88b90d8` (feat)
3. **Task 3: Prove BACKWARD compatibility has teeth, and give the local compose stack a registry** - `4e2ca38` (feat)

**Plan metadata:** pending (this commit)

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java` - sole schema-registration implementation, called from build/CI and test setup
- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogAvroRoundTripE2ETest.java` - the tracer's end-to-end proof, including the raw-bytes wire-format assertion
- `src/test/java/com/vrudenko/kanban_board/activitylog/SchemaCompatibilityE2ETest.java` - configuration and enforcement proof for BACKWARD compatibility
- `build.gradle` - `registerSchemas` JavaExec task
- `src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java` - maps domain event through `ActivityEventAvroMapper` before `send()`
- `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java` - listener now receives `SpecificRecord`, maps to domain on first line
- `src/main/resources/application.properties` - Avro producer/consumer serde config, deleted JSON-era trusted-packages properties
- `src/main/resources/application-test.properties` - mirrors the serde change, bounds the registry client's own retry/timeout defaults
- `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java` - Redpanda container, `@DynamicPropertySource` for the registry URL, `sendAndAwaitAck` now maps through the mapper, registry-client bounds raised back up for the real registry
- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java` - one timestamp-tolerance assertion widened (1 microsecond → 1 millisecond)
- `docker-compose.yml` - Redpanda replaces `apache/kafka-native`, with a schema registry listener and updated app environment

## Decisions Made

All decisions were either pre-locked by 04-CONTEXT.md/PLAN.md (D-01/D-02/D-03, RecordNameStrategy, Redpanda-for-local-verification) or resolved by direct measurement during this session — see `key-decisions` in the frontmatter for the two genuinely new findings this plan surfaced: the `@ServiceConnection`/schema-registry gap in Spring Boot 3.5.0's `RedpandaContainerConnectionDetailsFactory`, and the falsified registry-retry-storm hypothesis for the wall-clock regression.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Spring Boot 3.5.0's `@ServiceConnection` has no schema-registry equivalent for `RedpandaContainer`**
- **Found during:** Task 1 (migrating `AbstractKafkaContainerTest`)
- **Issue:** The plan's action text assumed `@ServiceConnection` on the `RedpandaContainer` field would suffice for the harness's wiring. By direct inspection of `spring-boot-testcontainers-3.5.0.jar`, Spring Boot does ship a dedicated `RedpandaContainerConnectionDetailsFactory` that correctly wires `spring.kafka.bootstrap-servers` — but no equivalent `ConnectionDetails` type exists for a schema registry (it's a Confluent/Avro concern, not something Spring Boot's Kafka autoconfiguration models), and `@TestPropertySource`'s array requires compile-time constants, which the container's dynamically-assigned registry port cannot be.
- **Fix:** Kept `@ServiceConnection` (it correctly wires bootstrap-servers) and added `@DynamicPropertySource` for `schema.registry.url`, resolved from the already-started static container instance.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java`
- **Verification:** All 5 Testcontainers-backed E2E classes pass; the registry is reachable from every one of them.
- **Committed in:** `71ff6e3`

**2. [Rule 1 - Bug] `ActivityLogConsumerE2ETest`'s pre-existing 1-microsecond timestamp tolerance no longer holds under Avro**
- **Found during:** Task 2 (running the three pre-existing E2E classes after the cutover)
- **Issue:** `shouldPopulateAllColumns_whenBoardCreatedEventIsSparsest` failed with `difference was 813 Micros` against a `within(1, MICROS)` assertion. Avro's `timestamp-millis` logical type truncates to millisecond precision by design (confirmed by direct inspection in 04-01) — a real, already-accepted property of the schema, not a regression. The old tolerance was written for the JSON pipeline's much smaller (sub-microsecond) double-precision loss and was never going to survive a wire format that intentionally drops up to 999 microseconds.
- **Fix:** Widened the tolerance to `within(1, MILLIS)` and rewrote the explanatory comment to describe the Avro precision floor instead of the old JSON-era reasoning.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java`
- **Verification:** Full class re-run green; the assertion still fails if the row's timestamp diverges by more than one millisecond, so it retains real assertion power.
- **Committed in:** `88b90d8`

**3. [Rule 2 - Missing hardening] `CachedSchemaRegistryClient`'s own retry/timeout defaults are independent of and unbounded relative to the Kafka producer bounds already in force**
- **Found during:** Task 2 (measuring full-suite wall-clock, per the plan's own required acceptance criterion)
- **Issue:** By direct bytecode inspection of `kafka-schema-registry-client-7.8.9.jar`, `SchemaRegistryClientConfig`'s defaults are `max.retries=3`, `retries.wait.ms=1000`, `retries.max.wait.ms=20000`, `http.connect.timeout.ms=60000`, `http.read.timeout.ms=60000` — none of which are touched by the Kafka producer's `max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms` bounds this file already documents, because the registry HTTP lookup runs inside serialization, before those Kafka-level bounds ever apply.
- **Fix:** Bounded `max.retries=0`, `retries.wait.ms=0`, `http.connect.timeout.ms=50`, `http.read.timeout.ms=50` in the test profile (matching the existing 50ms fail-fast philosophy), then raised them back up (`max.retries=3`, `retries.wait.ms=1000`, 30000ms timeouts) in `AbstractKafkaContainerTest`'s `@TestPropertySource`, since that harness's registry is real and responds — the tight test-profile bounds would otherwise turn an occasional slow-but-real lookup into a flaky failure there.
- **Files modified:** `src/main/resources/application-test.properties`, `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java`
- **Verification:** All Testcontainers-backed classes still pass (registry-client bounds correctly raised); measured full-suite wall-clock before and after this specific change (see Issue Encountered below — it did not close the gap, and that negative result is itself the useful finding).
- **Committed in:** `88b90d8`

---

**Total deviations:** 3 auto-fixed (1 blocking test-infra gap, 1 bug in a pre-existing test, 1 hardening fix with a measured-negative result)
**Impact on plan:** All three were necessary for correctness or for honestly satisfying the plan's own measurement requirement. No scope creep — no code outside the plan's stated files (plus the one now-unavoidable `ActivityLogConsumerE2ETest` fix) was touched.

## Issues Encountered

- **Full-suite wall-clock regressed ~11% (208s → ~231-237s, measured 3 times) and the targeted fix did not close it.** The plan's threat model (T-04-04) specifically worried about the schema registry's synchronous HTTP lookup reintroducing the shape of failure that caused v1.1's 20-minute suite hang. I bounded `CachedSchemaRegistryClient`'s own retry/timeout defaults in the test profile (Deviation 3 above) on the reasoning that its unbounded-by-default retries against a deliberately-non-listening registry address (17 fixture-heavy classes × ~18 publishes each) could be burning several seconds per failed lookup. Measured before (232s) and after (237s) the fix: **no improvement** — if anything, slightly worse, within run-to-run noise. This falsifies the hypothesis: those registry-lookup failures happen on the async `kafkaPublishExecutor` thread, entirely decoupled from the JUnit test thread's timing, so they were never actually on the critical path to begin with. Individual test-class durations (checked directly from the XML reports) show nothing anomalous — the new tracer test class itself (`ActivityLogAvroRoundTripE2ETest`, ~9.3s, did not exist in the 208s baseline) plus one-time Redpanda container start + 5-schema registration overhead plausibly account for most of the delta. This is a real, honestly-reported number, not a claim that the cutover is free — but it is nowhere near the catastrophic failure mode the threat model was built to prevent (231s vs. a 1200s incident), and the retry-bounding fix is kept anyway as correct configuration hygiene independent of whether it explains the delta.

## User Setup Required

None — no external service configuration required beyond what Phase 4 Plan 1 already established. The local docker-compose stack (`docker.redpanda.com/redpandadata/redpanda:v26.2.1`) requires no credentials; `packages.confluent.io/maven/` was already confirmed reachable in Plan 01.

## Next Phase Readiness

- **Plan 03** (DLT re-verification under Avro, SCHEMA-05) and **Plan 04** (historical-data rehearsal, SCHEMA-06) can proceed: the live pipeline, the registration step, and the compatibility enforcement are all proven against a real registry now.
- **Registry-down resilience (D-01) is structurally preserved but not independently re-tested by this plan** — `KafkaEventPublisher`'s `whenComplete` callback, `@Async` dispatch, and producer bounds are all unchanged; no new try/catch was added around the mapping/send per the plan's own explicit design. This plan's `<objective>` deliberately scoped proving the *failure* paths (a genuinely unreachable registry, a rejected schema at publish time) out to Plan 03 — that scope boundary is intentional, not a gap this plan silently left.
- **DLT byte-fidelity (SCHEMA-05) was not touched or re-verified in this plan** — `KafkaConsumerConfig`'s `DelegatingByTypeSerializer` is unchanged, and the three pre-existing DLT tests in `ActivityLogDeadLetterE2ETest` pass unmodified, but a dedicated Avro-specific poison-message case (unknown/unregistered schema id) is explicitly Plan 03's scope, not this one's.
- No blockers. The 5 subject names as they actually appear in the registry: `com.vrudenko.kanban_board.event.avro.AvroTaskCreatedEvent`, `AvroTaskMovedEvent`, `AvroTaskDeletedEvent`, `AvroBoardCreatedEvent`, `AvroColumnCreatedEvent` (confirmed via `curl localhost:8081/subjects` against the live compose stack).
- The `@KafkaListener` parameter bound as `SpecificRecord` directly — no `Object`-and-cast fallback was needed.
- Redpanda's registry handled the namespaced `RecordNameStrategy` subjects without the edge case RESEARCH.md flagged (GH issue #11912) — confirmed by both the Testcontainers-backed round trip and the live compose-stack verification.

---
*Phase: 04-schema-registry*
*Completed: 2026-08-04*

## Self-Check: PASSED

All 3 created source files and the SUMMARY.md itself confirmed present on disk; all 3 task commit hashes (`71ff6e3`, `88b90d8`, `4e2ca38`) confirmed present in `git log --oneline --all`.
