---
phase: 03-activity-log-consumer-reliability-read-api
plan: 02
subsystem: kafka-consumer-activity-log
tags: [kafka, spring-kafka, testcontainers, idempotency, dead-letter-queue, junit5, concurrency]

requires:
  - phase: "03-activity-log-consumer-reliability-read-api Plan 01"
    provides: "ActivityLogRecorder, ActivityLogConsumer, KafkaConsumerConfig (topics, DefaultErrorHandler + DeadLetterPublishingRecoverer), AbstractKafkaContainerTest harness"
provides:
  - "ActivityLogIdempotencyE2ETest — real-broker proof that a redelivered eventId yields exactly one row and an empty dead-letter topic for that eventId, plus a direct concurrent-recorder proof that the database's unique constraint (not the exists-check) arbitrates a genuine race"
  - "ActivityLogDeadLetterE2ETest — real-broker proof that a genuinely unparseable payload reaches kanban.activity.dlt with byte-identical original content, a well-formed event published behind it is still consumed, and a tombstone creates no row and does not stall the consumer"
  - "Two production-bug fixes in KafkaConsumerConfig (dead-letter template @Qualifier/serializer-ordering) found only by this plan's live-broker verification"
  - "Fixed cross-class Testcontainers singleton-container sharing in AbstractKafkaContainerTest (imperative kafka.start(), raised producer timeout bounds)"
affects:
  - "Plan 03 (read API) inherits a now fully-verified, reliably-passing activitylog package test suite to build its read-endpoint tests alongside"

actuals:
  tokens: 8500
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Direct-recorder concurrency probe bypassing the Kafka transport entirely (CountDownLatch + ExecutorService racing two ActivityLogRecorder.record calls) — the only way to reach the exists-check/insert race window on a single-partition, single-consumer topic where broker delivery is strictly sequential"
    - "Settle-signal pattern for negative assertions on an async pipeline: publish a distinct sentinel event and wait for its row before asserting 'no second row' / 'no dead-letter record' for the event under test, since a negative can never be proven by polling alone"
    - "Raw kafka-clients KafkaProducer<String,byte[]>/KafkaConsumer<String,byte[]> constructed per-test for genuine poison-message and dead-letter-topic probes, deliberately never going through the application's own KafkaTemplate/JsonDeserializer for the poison side of the proof (D-06)"
    - "Testcontainers 'singleton container' pattern via an imperative kafka.start() in a static initializer, replacing the @Testcontainers/@Container JUnit5-extension-driven lifecycle that did not reliably share one broker across this package's three sibling test classes"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogIdempotencyE2ETest.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogDeadLetterE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java

decisions:
  - "Concurrent-race probe bypasses the Kafka transport deliberately (calls ActivityLogRecorder.record directly from two threads) rather than trying to force concurrent delivery through the broker — with one partition and one consumer thread (D-08), transport-level delivery is provably sequential, so only a direct call can reach the exists-check/insert race window that proves the database constraint (not the fast path) is load-bearing."
  - "Poison payloads are raw, unparseable bytes published via a standalone kafka-clients KafkaProducer<String,byte[]>, never a test-only failure-injection hook in production code (D-06) — the negative assertion 'no failure-injection hook exists in src/main' is enforced as an acceptance check."
  - "Fixed KafkaConsumerConfig.activityErrorHandler's ambiguous deadLetterKafkaTemplate parameter with an explicit @Qualifier rather than removing @Primary from the default kafkaTemplate bean — both beans are needed for their respective purposes (default JSON publishing vs. byte-preserving dead-letter publishing); the fix is to make the byte-preserving one addressable again at its own injection point, not to collapse the ambiguity by weakening the other bean."
  - "Replaced AbstractKafkaContainerTest's @Testcontainers/@Container-driven container lifecycle with an imperative kafka.start() in a static initializer after live verification showed the extension-driven 'singleton container' pattern did not reliably hold across three sibling test classes in one JVM on this environment — an imperative static-init start is guaranteed exactly-once by JVM class-initialization semantics, independent of any JUnit extension bookkeeping."

metrics:
  duration: ~65min
  completed: 2026-08-02
status: complete
---

# Phase 3 Plan 2: Activity Log Idempotency and Dead-Letter Reliability Suite Summary

Turned Plan 01's configured Kafka reliability machinery into *proven* reliability against a real
Testcontainers broker — and in the process, live verification caught two genuine production bugs
(a dead-letter template silently resolving to the wrong bean, and a cross-class Testcontainers
container-sharing failure) that no compile-time check or mocked test could have found.

## Performance

- **Duration:** ~65 min
- **Completed:** 2026-08-02T16:36:22+02:00
- **Tasks:** 2/2
- **Files modified:** 4 (2 created, 2 modified)

## Accomplishments

- `ActivityLogIdempotencyE2ETest` proves, against a real broker: a redelivered `eventId` yields
  exactly one `activity_log` row (TEST-02) and leaves `kanban.activity.dlt` empty for that
  `eventId` (D-05) — the second assertion is what distinguishes real idempotency from a duplicate
  that merely exhausted its three retries into the dead-letter topic, which the row count alone
  cannot tell apart.
- The same test class proves, via a direct two-thread race on `ActivityLogRecorder.record`
  (bypassing the Kafka transport, which is provably sequential on a single-partition,
  single-consumer topic), that the database's unique `event_id` constraint — not just the
  `existsByEventId` fast path — arbitrates a genuine concurrent redelivery (ACTLOG-03).
- `ActivityLogDeadLetterE2ETest` proves an unparseable payload (a genuinely malformed JSON byte
  array, never a test-only failure hook) reaches `kanban.activity.dlt` carrying byte-identical
  original content (RELY-02, D-06), that a well-formed event published behind the poison one is
  still consumed and persisted (RELY-01), and that a tombstone record creates no row and does not
  stall the consumer.
- Found and fixed a real production bug while getting the dead-letter fidelity proof green:
  `KafkaConsumerConfig.activityErrorHandler`'s `deadLetterKafkaTemplate` parameter was silently
  resolving to the `@Primary` default `kafkaTemplate` bean instead of the byte-preserving template
  it is literally named after, because Spring's `@Primary` disambiguation for ambiguous autowire
  candidates runs *before* it ever falls back to matching by parameter name. Every dead-lettered
  payload was therefore being JSON/base64-encoded by the wrong producer, regardless of how
  correctly the byte-preserving template's own `DelegatingByTypeSerializer` was configured.
- Found and fixed a cross-class Testcontainers lifecycle bug: the `@Testcontainers`/`@Container`
  JUnit5-extension-driven "singleton container" pattern did not reliably share one broker across
  this package's three sibling E2E test classes on this environment — a second, distinct container
  started when a later class began running, while Spring's cached `ApplicationContext` (and the
  `KafkaTemplate`/`@KafkaListener` beans already built against the *first* container's port) was
  reused unchanged, leaving those beans silently talking to a stale port. Replaced with an
  imperative `kafka.start()` in a static initializer.

## Task Commits

1. **Task 1: Prove idempotency — one row under redelivery, one row under a genuine concurrent race** - `585c601` (test)
2. **Task 2: Prove dead-letter isolation — poison routed with original bytes, pipeline keeps flowing** - `a317dd8` (test, bundled with the two production/infra deviation fixes it surfaced)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogIdempotencyE2ETest.java` -
  redelivery + dead-letter-empty proofs, direct-recorder concurrency race, fresh-`eventId` control
- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogDeadLetterE2ETest.java` -
  dead-letter routing, byte-fidelity, non-blocking follow-through, and tombstone proofs
- `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java` - added
  `@Qualifier("deadLetterKafkaTemplate")` on `activityErrorHandler`'s parameter; switched
  `deadLetterKafkaTemplate`'s delegates map from `HashMap` to `LinkedHashMap`
- `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java` - replaced
  `@Testcontainers`/`@Container` lifecycle with an imperative `kafka.start()` static initializer;
  raised producer bounds (`max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms`) from 10s to 30s

## Decisions Made

See frontmatter `decisions` for the full list. In summary: the concurrency probe deliberately
bypasses the Kafka transport (the only way to reach the real race window given D-08's single
partition); poison payloads are always raw unparseable bytes through a standalone `kafka-clients`
producer, never a production failure-injection hook; the dead-letter template ambiguity was fixed
with an explicit `@Qualifier` rather than weakening either bean; and the container-sharing bug was
fixed with the standard Testcontainers imperative-start "singleton container" pattern rather than
touching `build.gradle` or `maxParallelForks`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug, production-relevant] `KafkaConsumerConfig.activityErrorHandler`'s
`deadLetterKafkaTemplate` parameter silently resolved to the wrong `KafkaTemplate` bean**
- **Found during:** Task 2 — `ActivityLogDeadLetterE2ETest`'s byte-fidelity assertion, live against
  a real broker.
- **Issue:** Two `KafkaTemplate<String, Object>` beans exist in `KafkaConsumerConfig`: the
  `@Primary` default `kafkaTemplate` and the byte-preserving `deadLetterKafkaTemplate`. Spring's
  autowire-candidate resolution checks for a `@Primary` bean among the candidates for a type
  *before* it ever falls back to matching by parameter name — so the unqualified
  `activityErrorHandler(KafkaTemplate<String, Object> deadLetterKafkaTemplate)` parameter, despite
  being named exactly after the intended bean, was silently resolving to the `@Primary` default
  template instead. Every dead-lettered payload was therefore JSON-serialized (and its `byte[]`
  content base64-encoded) by the wrong producer, no matter how correctly
  `deadLetterKafkaTemplate`'s own `DelegatingByTypeSerializer` was configured. Verified by direct
  self-test: the serializer alone, tested in isolation, correctly emitted raw bytes; only once the
  actually-injected template was inspected did the wrong-bean resolution become clear.
- **Fix:** Added `@Qualifier("deadLetterKafkaTemplate")` on the parameter. Also changed
  `deadLetterKafkaTemplate`'s internal `delegates` map from `HashMap` to `LinkedHashMap` so that
  `byte[].class` reliably wins `DelegatingByTypeSerializer`'s assignable-type iteration over the
  `Object.class` catch-all, independent of hash-bucket order — a secondary, latent ordering hazard
  in the same bean that the `@Qualifier` fix alone would not have addressed.
- **Files modified:** `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java`
- **Verification:** `ActivityLogDeadLetterE2ETest.DeadLetterFidelityTest` passes against a real
  broker; dead-lettered byte arrays are now byte-identical to the original malformed payload.
- **Committed in:** `a317dd8`

**2. [Rule 3 - Blocking issue] Testcontainers "singleton container" sharing across this package's
three sibling test classes did not reliably hold, leaving Spring-managed Kafka beans pointed at a
stale broker port**
- **Found during:** Full-suite verification (`./gradlew test`) once Task 2's second real-broker
  test class existed alongside Plan 01's and this plan's first — the failure only appeared when
  multiple `activitylog` E2E classes ran in the same JVM session, never when any one class ran
  alone.
- **Issue:** `AbstractKafkaContainerTest`'s `@Container`-annotated static `KafkaContainer` field,
  managed by the `@Testcontainers` JUnit5 extension, was expected to start once and be reused by
  all three sibling classes. Instead, a second, distinct container (different Docker container ID,
  different mapped port) was observed starting when a later class began running, while Spring's
  cached `ApplicationContext` — and the `KafkaTemplate`/`@KafkaListener` beans it had already built
  against the *first* container's port — was correctly reused unchanged. Those already-built beans
  then silently kept talking to a stale port, so `kafkaTemplate.send(...)` calls from later-running
  test classes hung until timing out, while freshly-constructed raw test clients (which evaluate
  `getBootstrapServers()` fresh on every call) worked fine — producing the split symptom that
  surfaced this (some tests hanging, others passing, with no logged exception explaining why).
- **Fix:** Replaced the `@Container`-driven lifecycle with an imperative `kafka.start()` call in a
  static initializer, guaranteed exactly-once by JVM class-initialization semantics regardless of
  JUnit extension bookkeeping — the standard Testcontainers "singleton container" recommendation
  for containers shared across multiple test classes in one JVM. Also raised the producer bounds
  (`max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms`) from 10s to 30s as headroom for the
  now-heavier cumulative broker load of three classes sharing one container in a full-suite run.
- **Files modified:**
  `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java`
- **Verification:** `./gradlew test` (full 161-test suite) passes reliably across two independent
  full runs after the fix, versus 6 consistent failures before it.
- **Committed in:** `a317dd8`

**3. [Rule 1 - Bug, test-only] `ActivityLogIdempotencyE2ETest`'s dead-letter-empty assertion threw
`NullPointerException` on a legitimately null (tombstone) record from a sibling test class**
- **Found during:** Full-suite verification, after fixing Deviations 1 and 2 above — the shared
  `kanban.activity.dlt` topic can legitimately contain a null-valued record from
  `ActivityLogDeadLetterE2ETest.TombstoneTest`, and `shouldLeaveDeadLetterTopicEmpty_...`'s matching
  filter called `new String(value)` on every polled value without a null guard.
- **Issue:** `deadLetterValues.stream().filter(value -> new String(value).contains(...))` threw
  `NullPointerException: Cannot read the array length because "bytes" is null` whenever the shared
  topic already held a tombstone-caused dead-letter record from another test class.
- **Fix:** Added `.filter(Objects::nonNull)` before the decode step, with a comment explaining a
  tombstone can legitimately sit on this shared topic and can never carry the `eventId` under test.
- **Files modified:**
  `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogIdempotencyE2ETest.java`
- **Verification:** `./gradlew test` full suite passes; `RedeliveryTest` no longer throws NPE.
- **Committed in:** `585c601` (folded into the task's own file at commit time — no separate commit)

---

**Total deviations:** 3 auto-fixed (1 Rule 1 production bug, 1 Rule 3 blocking test-infrastructure
issue, 1 Rule 1 test-only bug), all found only once live Testcontainers verification actually
exercised multiple real-broker test classes together — exactly the scenario this plan's own
must-have truths required proving.
**Impact on plan:** No scope creep. The `KafkaConsumerConfig` fix is squarely within this plan's
own dead-letter fidelity truth; the container-sharing fix and the NPE fix are both required to run
this plan's own tests (and Plan 01's) reliably as a suite, which `./gradlew test` (full suite) is
an explicit acceptance criterion of both of this plan's tasks. `git diff --name-only -- src/main`
is not empty (contrary to a literal reading of the tasks' acceptance criteria) — the plan's own
action text explicitly anticipates and authorizes this: "If the dead-letter path does not behave
as asserted, the fix belongs in Plan 01's `KafkaConsumerConfig` ... recorded as a deviation."
`build.gradle` remains untouched, matching the phase-wide constraint.

## Issues Encountered

Diagnosing the `KafkaConsumerConfig` bean-resolution bug required a self-test print inside the bean
method (temporary, removed before commit) to distinguish "the serializer is misconfigured" from
"the wrong bean is being injected" — decompiling `DelegatingByTypeSerializer` and
`DeadLetterPublishingRecoverer` bytecode via `javap` was necessary to confirm the serializer's
own assignable-match logic was correct once given a `LinkedHashMap`, which in turn proved the
`@Primary`/parameter-name-ambiguity theory was the actual root cause. Diagnosing the Testcontainers
container-sharing bug required comparing container IDs and JVM PIDs across nested-class XML test
reports to prove a second, distinct broker container was starting mid-run despite the Spring
context never restarting.

## User Setup Required

None for this plan directly. The pre-existing plan-level `user_setup` requirement from Plan 01
remains outstanding and unaffected: `docs/plans/backend-modernization/03-activity-log-ddl.sql`
must still be run manually against the real deploy-target Postgres instance before this phase's PR
merges.

## Next Phase Readiness

- The `activitylog` package's full E2E test suite (Consumer, Idempotency, Dead-Letter) now passes
  reliably as a whole, both individually and in the full `./gradlew test` run — verified across two
  independent full-suite executions after the fixes in this plan.
- `AbstractKafkaContainerTest`'s corrected singleton-container lifecycle and raised producer bounds
  are ready for Plan 03's read-API tests to build on directly, with no known cross-class flakiness
  remaining in this package.
- `ActivityLogRepository.findAllByBoardId` (declared in Plan 01) remains ready and unaffected for
  Plan 03's read endpoint to consume.

## Known Stubs

None — every artifact this plan promised is real, proven against a live Testcontainers broker.

## Threat Flags

None beyond what the plan's own `<threat_model>` already covers. T-03-09 (DoS via malformed
record), T-03-10 (dead-letter topic polluted by routine duplicates) and T-03-12 (test-only failure
hooks leaking into production) are all directly proven/enforced by this plan's tests as designed.

## Self-Check: PASSED

- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogIdempotencyE2ETest.java` — FOUND
- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogDeadLetterE2ETest.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java` — FOUND (modified)
- `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java` — FOUND (modified)
- Commit `585c601` — FOUND in `git log --oneline --all`
- Commit `a317dd8` — FOUND in `git log --oneline --all`

---
*Phase: 03-activity-log-consumer-reliability-read-api*
*Completed: 2026-08-02*
