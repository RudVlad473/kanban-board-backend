---
phase: 04-schema-registry
plan: 03
subsystem: testing
tags: [avro, kafka, schema-registry, dead-letter, resilience, dynamicpropertysource, logback]

# Dependency graph
requires:
  - phase: 04-02
    provides: Live Avro cutover (KafkaAvroSerializer/Deserializer) against a real Redpanda-hosted registry, AvroSchemaRegistrar, Redpanda-based AbstractKafkaContainerTest harness
provides:
  - ActivityLogAvroDeadLetterE2ETest -- re-verifies SCHEMA-05's byte-fidelity guarantee under Avro, for both a framing-level poison payload and the new registry-resolution-failure poison shape
  - SchemaRegistryOutageE2ETest -- proves D-01's resilience policy holds for a schema-registry outage specifically (broker up, registry down)
  - AbstractKafkaContainerTest.producerSchemaRegistryUrlOverride -- a documented, safety-argued mutable test hook for the one test that needs an unreachable producer-side registry without corrupting sibling classes' shared context
affects: [04-04]

# Actuals (#2632)
actuals:
  tokens: 7600
  tasks: 2
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Dead-letter poison payloads constructed as genuine Confluent wire format (magic byte + 4-byte schema id) to exercise the registry-resolution failure path, distinct from framing-level failures"
    - "A mutable, test-scoped static override field on a shared Testcontainers test base, guarded by an explicit @AfterAll reset and a documented no-parallel-test-execution safety argument, as the correct escape hatch when @DynamicPropertySource inheritance ordering cannot be won by a subclass"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogAvroDeadLetterE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/SchemaRegistryOutageE2ETest.java
  modified:
    - src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java

key-decisions:
  - "@DynamicPropertySource methods across a class hierarchy are all invoked into ONE shared property source, but empirically confirmed (not assumed) to be discovered/invoked subclass-local-methods-FIRST, superclass-methods-LAST -- the opposite of @BeforeAll ordering. A subclass registering the same property key as its superclass therefore always loses; this is undocumented behavior worth remembering project-wide"
  - "The fix is a mutable, test-scoped static field (producerSchemaRegistryUrlOverride) on the shared AbstractKafkaContainerTest, set in the one test class that needs it and reset in @AfterAll -- safe specifically because this project runs test classes sequentially in one JVM (no parallel test execution configured anywhere)"
  - "Genuine finding, not a bug to patch: a schema-registry lookup failure during Avro serialization is a SYNCHRONOUS throw from KafkaTemplate.send() (schema resolution happens inside KafkaProducer.doSend before any delivery future exists), never reaching KafkaEventPublisher's whenComplete callback. Spring's default @Async uncaught-exception handler catches and logs it instead -- naming the method but not the specific event. D-01's user-facing guarantee (mutation succeeds/persists, caller never blocked, failure logged not swallowed) still holds; KafkaEventPublisher.java's Javadoc claim about the specific mechanism is incomplete for this failure mode. No production code was changed, per the plan's explicit design"

patterns-established:
  - "Registry-aware poison payloads (valid magic byte + unregistered schema id) as the standard way to exercise a Confluent-registry-mediated deserialization failure in a test, distinct from framing-level poison payloads"

requirements-completed: [SCHEMA-05]

coverage:
  - id: D1
    description: "A payload with no valid Confluent magic byte is dead-lettered to kanban.activity.dlt with its bytes byte-for-byte intact under the Avro pipeline"
    requirement: "SCHEMA-05"
    verification:
      - kind: e2e
        ref: "ActivityLogAvroDeadLetterE2ETest#UnframedPayloadTest.shouldDeadLetterWithByteFidelity_whenPayloadHasNoValidMagicByte"
        status: pass
    human_judgment: false
  - id: D2
    description: "A payload in valid Confluent wire format but carrying an unregistered schema id is dead-lettered with bytes intact -- the registry-aware failure path the JSON-era test could not exercise"
    requirement: "SCHEMA-05"
    verification:
      - kind: e2e
        ref: "ActivityLogAvroDeadLetterE2ETest#UnregisteredSchemaIdTest.shouldDeadLetterWithByteFidelity_whenPayloadIsFramedButSchemaIdIsUnregistered"
        status: pass
    human_judgment: false
  - id: D3
    description: "A well-formed event published behind a poison message is still consumed and persisted -- a poison record does not stall the single-partition topic"
    requirement: "SCHEMA-05"
    verification:
      - kind: e2e
        ref: "ActivityLogAvroDeadLetterE2ETest#NonBlockingTest.shouldStillPersistEvent_whenPublishedAfterRegistryAwarePoisonMessage"
        status: pass
    human_judgment: false
  - id: D4
    description: "KafkaConsumerConfig required no code change to keep the byte-fidelity guarantee under Avro"
    verification:
      - kind: other
        ref: "git diff --exit-code src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java (empty)"
        status: pass
    human_judgment: false
  - id: D5
    description: "A real mutation completes successfully and persists while the schema registry is unreachable, with a reachable broker (D-01)"
    requirement: "SCHEMA-05"
    verification:
      - kind: e2e
        ref: "SchemaRegistryOutageE2ETest#MutationSurvivesRegistryOutageTest.shouldReturnAndPersist_butNeverPublish_whenSchemaRegistryIsUnreachable"
        status: pass
    human_judgment: false
  - id: D6
    description: "The registry failure is logged and never silently swallowed, though not through the specific whenComplete mechanism KafkaEventPublisher's Javadoc claims (a genuine finding, documented rather than patched)"
    verification:
      - kind: e2e
        ref: "SchemaRegistryOutageE2ETest#MutationSurvivesRegistryOutageTest (same test method, log-capture assertion)"
        status: pass
    human_judgment: true
    rationale: "The assertion mechanically passes, but whether 'logged by Spring's default @Async exception handler, naming the method but not the event' is an acceptable fulfillment of D-01's 'logged, never swallowed' language -- versus a real gap worth closing in a future plan -- is a judgment call for a human to make, not a fact this executor can certify alone."
  - id: D7
    description: "KafkaEventPublisher.java required no code change (D-01 satisfied by the one existing callback for the observable, user-facing guarantee; the internal propagation-path claim in its Javadoc is what this plan found to be incomplete)"
    verification:
      - kind: other
        ref: "git diff --exit-code src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java (empty)"
        status: pass
    human_judgment: false

duration: 40min
completed: 2026-08-04
status: complete
---

# Phase 4 Plan 3: Dead-Letter Byte Fidelity & Schema-Registry Outage Resilience Under Avro Summary

**Re-verified SCHEMA-05's dead-letter byte-fidelity guarantee against two Avro-era poison shapes (framing failure, registry-resolution failure) and proved D-01's mutation-survives-Kafka-failure resilience policy specifically for a schema-registry outage — surfacing along the way a genuine, previously undocumented gap in how that resilience policy is described in code.**

## Performance

- **Duration:** ~40 min
- **Started:** 2026-08-04T15:28:00+02:00 (approx.)
- **Completed:** 2026-08-04T16:06:00+02:00
- **Tasks:** 2 completed
- **Files modified:** 3 (2 created, 1 modified)

## Accomplishments

- `ActivityLogAvroDeadLetterE2ETest` proves the dead-letter path's byte-fidelity and non-blocking guarantees hold under Avro for **two distinct poison shapes**: a framing-level failure (no valid Confluent magic byte — `org.apache.kafka.common.errors.SerializationException: Unknown magic byte!`) and the new registry-resolution failure a JSON-era test could never produce (valid framing, an unregistered schema id — `SerializationException: Error retrieving Avro value schema for id 999999999`, caused by `RestClientException: Schema 999999999 not found; error code: 40403`). Both land in `kanban.activity.dlt` with their bytes byte-for-byte intact, and a well-formed event published behind either poison shape is still consumed — confirming `KafkaConsumerConfig`'s dead-letter template needed no Avro-aware code change.
- `SchemaRegistryOutageE2ETest` proves D-01's resilience policy extends to a schema-registry outage: a real `UserService.addBoardByUserId` mutation returns normally, the board persists, and no `activity_log` row ever appears for it, with the Redpanda broker reachable throughout and only the producer's `schema.registry.url` pointed at a dead port (`http://localhost:1`).
- Surfaced and documented (not patched) a genuine finding: a registry-lookup failure during Avro serialization is a **synchronous throw** from `KafkaTemplate.send()` — schema resolution happens inside `KafkaProducer.doSend` before any delivery future exists — so it never reaches `KafkaEventPublisher`'s `whenComplete` callback at all. Spring's default `@Async` uncaught-exception handler (`SimpleAsyncUncaughtExceptionHandler`) catches and logs it instead, naming the `onActivityEvent` method but not the specific `eventId`/`boardId` the way the `whenComplete` path's log line would. D-01's user-facing guarantee (mutation succeeds and persists, caller never blocked, failure logged not swallowed) still holds in full; the code comment describing the internal mechanism is what turned out to be incomplete.
- Neither `KafkaConsumerConfig.java` nor `KafkaEventPublisher.java` changed — both guarantees held (or, in the registry-outage case, held at the user-facing level) with zero production code changes, which is itself the finding both tasks were designed to establish.

## Task Commits

Each task was committed atomically:

1. **Task 1: Dead-letter byte fidelity under Avro, including the registry-aware poison shape (SCHEMA-05)** - `321932c` (test)
2. **Task 2: A mutation survives a schema registry outage (D-01)** - `c3ddfb0` (test)

**Plan metadata:** pending (this commit)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogAvroDeadLetterE2ETest.java` - new E2E class, three `@Nested` groups covering unframed-payload fidelity, registry-aware-poison fidelity, and non-blocking continuation
- `src/test/java/com/vrudenko/kanban_board/activitylog/SchemaRegistryOutageE2ETest.java` - new E2E class proving a real mutation survives a registry-only outage
- `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java` - added `producerSchemaRegistryUrlOverride`, a documented, test-scoped mutable static hook for overriding the producer-side registry URL from a subclass (shared test infrastructure, not production code)

## Decisions Made

- Reused the JSON-era `ActivityLogDeadLetterE2ETest`'s exact structure (raw byte-array producer/consumer, `awaitDeadLetterRecordMatching` exact-payload matching) for the new Avro-era class rather than inventing a new pattern, per the plan's explicit instruction — the new class is a sibling, not a replacement.
- Constructed the registry-aware poison payload with a fresh random discriminator on every call (`framedPayloadWithUnregisteredSchemaId()`) rather than a fixed byte sequence, since the dead-letter topic is shared across every class and test method in the package (cached Spring context) and two byte-identical poison payloads from different call sites would otherwise both match the exact-payload assertion and break its single-match invariant.
- See `key-decisions` in the frontmatter for the two substantive findings this plan surfaced: the `@DynamicPropertySource` subclass-loses-to-superclass ordering, and the synchronous-throw-vs-failed-future registry failure mechanism.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The plan's suggested `@TestPropertySource` override mechanism for Task 2 does not work against a `@DynamicPropertySource`-set property of the same key**
- **Found during:** Task 2 (before writing any code — reasoned from Spring's documented precedence rules)
- **Issue:** The plan's action text suggested overriding `spring.kafka.producer.properties.schema.registry.url` via `@TestPropertySource`. `@DynamicPropertySource` property sources always take precedence over `@TestPropertySource` regardless of declaration order, so this would have been silently ineffective against `AbstractKafkaContainerTest`'s own `@DynamicPropertySource` registration of the same key.
- **Fix:** Used a second, subclass-local `@DynamicPropertySource` method instead — the standard workaround for overriding a dynamic property.
- **Files modified:** `SchemaRegistryOutageE2ETest.java` (initial draft)
- **Verification:** Compiled and ran; see Deviation 2 below for what happened next.
- **Committed in:** superseded before commit (see Deviation 2)

**2. [Rule 1 - Bug] The "second `@DynamicPropertySource` method in the subclass" workaround from Deviation 1 also does not work — confirmed empirically, not assumed**
- **Found during:** Task 2, first real test run (`./gradlew test --tests '*SchemaRegistryOutageE2ETest'`)
- **Issue:** The test failed: an `activity_log` row appeared for the board even though the producer's registry URL was supposedly overridden to a dead port. Log inspection showed `schema.registry.url = [http://localhost:50944]` (the real container's address) was what the producer actually used. `@DynamicPropertySource` methods across a class hierarchy are discovered and invoked into one shared property source, but — confirmed by this failure, not assumed from documentation — subclass-local methods are invoked *before* superclass ones, the opposite of `@BeforeAll` ordering. Since every invocation writes into the same map, the superclass's method (invoked last) always wins for a shared key, silently overwriting the subclass's attempted override.
- **Fix:** Added `AbstractKafkaContainerTest.producerSchemaRegistryUrlOverride`, a `protected static volatile String` field the shared `registerSchemaRegistryProperties()` method now consults (defaulting to the real container address when `null`). `SchemaRegistryOutageE2ETest` sets it via its own `@DynamicPropertySource` method (whose real job is this assignment, not a `registry.add(...)` call) and resets it in `@AfterAll`. Declaring that method at all is what still gives the test class its own, uncached Spring context (Spring keys the context cache partly on the discovered `@DynamicPropertySource` method set, which now differs from every sibling's). Safety of the shared mutable field rests on this project's test suite always running sequentially in one JVM (no parallel test execution configured anywhere) — verified by re-running the full `activitylog` package together (all 6 classes, including this new one, passed with no cross-contamination).
- **Files modified:** `AbstractKafkaContainerTest.java` (test infrastructure, not the plan's two protected production files), `SchemaRegistryOutageE2ETest.java`
- **Verification:** `SchemaRegistryOutageE2ETest` passes in isolation and as part of the full `*activitylog*` package run; `KafkaConsumerConfig.java` and `KafkaEventPublisher.java` both remain unchanged per `git diff --exit-code`.
- **Committed in:** `c3ddfb0`

**3. [Rule 1 - Bug] The log-capture assertion's original target (KafkaEventPublisher's own logger, checking for "Failed to publish") never fires**
- **Found during:** Task 2, second test run, after Deviation 2's fix resolved the primary assertions
- **Issue:** The optional "never-swallowed" log-capture assertion failed: `Expecting value to be true but was false`. Root cause is the genuine finding recorded in `key-decisions` — a registry-lookup failure during Avro serialization throws synchronously from `KafkaTemplate.send()`, never reaching `KafkaEventPublisher`'s `whenComplete` callback (confirmed via the full stack trace: `KafkaProducer.doSend` → `KafkaAvroSerializer.serialize` → `SerializationException`, propagating straight up through `KafkaTemplate.send()` with no intervening future). Spring's `@Async` infrastructure catches it at the invocation boundary instead, logging via `SimpleAsyncUncaughtExceptionHandler`.
- **Fix:** Re-targeted the `ListAppender` at the ROOT logger (not `KafkaEventPublisher`'s) and changed the assertion to check for an `ERROR`-level log line naming `onActivityEvent`, matching what is actually emitted. Documented the finding at length in the test class's Javadoc rather than adding a try/catch to `KafkaEventPublisher.java`, per the plan's explicit instruction that this kind of discovery "belongs in the summary rather than being patched over."
- **Files modified:** `SchemaRegistryOutageE2ETest.java`
- **Verification:** Log-capture assertion now passes; full assertion suite (return value, persistence, no activity-log row, log evidence) passes together.
- **Committed in:** `c3ddfb0`

---

**Total deviations:** 3 auto-fixed (all Rule 1/3 — bugs/blockers in the test mechanism itself, not the production code under test)
**Impact on plan:** No production code (`KafkaConsumerConfig.java`, `KafkaEventPublisher.java`) was touched, exactly as the plan required. All three deviations were necessary to make Task 2's test mechanism actually exercise the registry-down scenario it claims to, and to make its optional log-capture assertion check the log line that is genuinely emitted rather than one that never fires. No scope creep.

## Issues Encountered

None beyond the three deviations above, which are the substantive content of this plan's Task 2 work.

## User Setup Required

None — no external service configuration required. Docker Desktop (already required by the existing Testcontainers-based suite) is the only local dependency, unchanged from Plan 02.

## Next Phase Readiness

- **Plan 04** (historical-data rehearsal, SCHEMA-06) can proceed: both failure paths this plan targeted are now proven under the live Avro pipeline, with no production code changes required for either.
- **A follow-up worth flagging for a future plan (not fixed here, per this plan's explicit no-production-code-changes design):** `KafkaEventPublisher`'s Javadoc claim that a registry failure "becomes a failed future rather than a synchronous throw" is incomplete — true for a mid-network-send failure (e.g. broker accepts the connection but never acknowledges), false for a schema-resolution failure (which throws synchronously before any future exists). The two failure classes are both non-blocking and both logged, but only the `whenComplete` path names the specific event (`eventId`/`boardId`); the `@Async` default-handler path names only the method. Whether this asymmetry needs closing (e.g. wrapping the `kafkaTemplate.send(...)` call in a try/catch that logs the event details before rethrowing, or swallowing) is a design decision for a human, not something this plan's scope authorized fixing.
- The two poison-shape exception messages recorded above (`Unknown magic byte!` for framing failures, `Error retrieving Avro value schema for id <n>` / `RestClientException: Schema <n> not found; error code: 40403` for registry-resolution failures) are useful reference points for Phase 5's re-verification against the production registry.
- No blockers. `KafkaConsumerConfig.java` and `KafkaEventPublisher.java` remain byte-identical to their end-of-Plan-02 state.

---
*Phase: 04-schema-registry*
*Completed: 2026-08-04*

## Self-Check: PASSED

All 3 created/modified source files and the SUMMARY.md itself confirmed present on disk; both task commit hashes (`321932c`, `c3ddfb0`) confirmed present in `git log --oneline --all`.
