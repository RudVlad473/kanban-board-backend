---
phase: 03-activity-log-consumer-reliability-read-api
plan: 01
subsystem: kafka-consumer-activity-log
tags: [kafka, spring-kafka, testcontainers, jpa, idempotency, dead-letter-queue, docker-java]

dependency-graph:
  requires:
    - "Phase 2 Plan 01: ActivityEvent sealed interface (TaskMovedEvent, TaskCreatedEvent, TaskDeletedEvent, BoardCreatedEvent, ColumnCreatedEvent), KafkaEventPublisher, KafkaTopics.ACTIVITY"
  provides:
    - "activitylog.ActivityLogConsumer — @KafkaListener, exhaustive sealed switch over all five ActivityEvent types, no default arm"
    - "activitylog.ActivityLogRecorder — two-layer idempotent persist (existsByEventId fast path + DataIntegrityViolationException backstop), never throws"
    - "entity.ActivityLogEntity / entity.ActivityAction / repository.ActivityLogRepository — insert-only storage layer with a unique event_id constraint"
    - "config.KafkaConsumerConfig — NewTopic beans for kanban.activity + kanban.activity.dlt, DefaultErrorHandler + DeadLetterPublishingRecoverer (3 retries/~1s backoff), byte-preserving DLT template, dead-letter logging, and the @Primary default kafkaTemplate bean that Plan 03-01's own fix restored"
    - "docs/plans/backend-modernization/03-activity-log-ddl.sql — production DDL bridge for activity_log (real Postgres profile has no ddl-auto)"
    - "activitylog.AbstractKafkaContainerTest — reusable real-broker Testcontainers harness for Plan 02, including the api.version=1.44 Docker Engine pin"
  affects:
    - "config.KafkaEventPublisher — now correctly receives the @Primary default KafkaTemplate again (was silently receiving the DLT-flavored one before this plan's fix commit)"
    - "Plan 02 (reliability/DLT proofs) and Plan 03 (read API) both build directly on ActivityLogRepository.findAllByBoardId and the AbstractKafkaContainerTest harness this plan defines"

tech-stack:
  added: []
  patterns:
    - "Exhaustive switch expression over a sealed interface with no default arm as a compile-time coverage gate (ACTLOG-02) — a sixth ActivityEvent record is a compile error until ActivityLogConsumer handles it"
    - "Idempotent persist without a declarative @Transactional boundary: a constraint violation marks a surrounding transaction rollback-only, so catching it inside one relocates the failure to commit time; letting Spring Data's own per-call transaction own the failed insert is what makes the catch block a true no-op"
    - "A dedicated byte-preserving KafkaTemplate (DelegatingByTypeSerializer over ByteArraySerializer/JsonSerializer) for the dead-letter path, kept separate from the default JSON producer template so a poison message's raw bytes are never base64-encoded before an operator can inspect them"
    - "System.setProperty(\"api.version\", ...) static initializer in the Testcontainers base class as the codebase's sanctioned way to encode a Docker/Testcontainers environment workaround in version control (CODE_STYLE rule 8), instead of a runbook telling a developer what to click"

key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/entity/ActivityAction.java
    - src/main/java/com/vrudenko/kanban_board/entity/ActivityLogEntity.java
    - src/main/java/com/vrudenko/kanban_board/repository/ActivityLogRepository.java
    - src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogRecorder.java
    - src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java
    - src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java
    - docs/plans/backend-modernization/03-activity-log-ddl.sql
    - src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java
    - src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/constant/KafkaTopics.java
    - src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java
    - src/main/resources/application.properties
    - src/main/resources/application-test.properties

decisions:
  - "Exhaustive switch over ActivityEvent (no default arm) rather than per-type @KafkaHandler methods — coverage becomes a compile-time guarantee instead of a runtime MethodNotFound-class failure that would silently dead-letter a forgotten event type."
  - "ActivityLogRecorder.record() carries no @Transactional annotation, by design and with a Javadoc explaining why — a declarative transaction around the catch would relocate the DataIntegrityViolationException to commit time and let the duplicate escape the listener anyway (D-05)."
  - "boardId/userId on ActivityLogEntity are plain String columns, never @ManyToOne relations — the listener thread has no SecurityContext to resolve them and a foreign key would turn a routine already-deleted-board race into a poison message."
  - "Added an explicit @Primary kafkaTemplate @Bean in KafkaConsumerConfig, sourced from the autoconfigured ProducerFactory — Spring Boot's own default kafkaTemplate bean is guarded by a bare-type @ConditionalOnMissingBean(KafkaTemplate.class), which does not distinguish generic parameterizations, so the plan's own deadLetterKafkaTemplate bean was silently suppressing it. This was a real production bug (every activity event was being published through the DLT-flavored producer, which never honored a KafkaConnectionDetails override), not just a test artifact — found only because live Testcontainers verification exercises the real bean graph a mocked/skipped test run never would."
  - "Pinned docker-java's negotiated Docker Engine API version to 1.44 via a static initializer in AbstractKafkaContainerTest (testcontainers-java#11212: Docker Engine 29.x rejects the version testcontainers-java 1.21.0 negotiates by default, on every transport). Encoded in test code per CODE_STYLE rule 8, not a host-level Docker Desktop setting or a runbook — zero manual setup on a fresh machine."
  - "Replaced an exact-nanosecond Instant equality assertion with AssertJ isCloseTo(..., within(1, ChronoUnit.MICROS)) — the JSON-serialization-then-JPA-persistence round trip loses sub-microsecond precision without a consistent truncate-vs-round direction (observed deltas landed on both sides of the original value), so a tolerance-based comparison is the only version of this assertion that is not inherently flaky."

metrics:
  duration: ~25min (this resumption session; two prior sessions were blocked for the full session by the Docker/Testcontainers environment issue documented below and made no forward progress)
  completed: 2026-08-02
status: complete
actuals:
  tokens: 14064
  tasks: 2
  commits: 2
---

# Phase 3 Plan 01: Activity Log Consumer, Idempotent Persistence & DLT Wiring Summary

A real containerised Kafka broker now proves the whole Phase 3 pipeline end to end: all five
`ActivityEvent` types are consumed via an exhaustive sealed-interface switch, persisted
idempotently as `activity_log` rows with a database-enforced unique `eventId`, and backed by
explicit topic provisioning plus a retry-then-dead-letter path — and getting that real-broker
proof green surfaced and fixed a genuine production bug in the Kafka producer bean graph, not
just a test-environment quirk.

## Performance

- **Duration:** ~25min (this resumption session)
- **Completed:** 2026-08-02T15:06:11+02:00
- **Tasks:** 2/2 (Task 1: tracer slice; Task 2: five-event-type proof — both delivered together in the original tracer commit, see Task Commits)
- **Files modified:** 16 (13 created/modified in the original implementation commit, 3 touched again by this session's fix commit)

## Accomplishments

- `ActivityLogConsumer` maps all five `ActivityEvent` records to their `ActivityAction` and a
  stable, insertion-ordered `detail` JSON string via an exhaustive `switch` with no `default` arm
  — a sixth event type is a compile error, not a silent DLT drain.
- `ActivityLogRecorder` makes redelivery a true no-op: `existsByEventId` fast path plus a
  `DataIntegrityViolationException` backstop, neither branch throwing, with no `@Transactional`
  boundary (documented in-code as the reason the backstop actually works).
- `KafkaConsumerConfig` explicitly provisions both `kanban.activity` and `kanban.activity.dlt` as
  `NewTopic` beans, wires `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (3 retries,
  ~1s backoff) with a byte-preserving dead-letter producer template, and logs every dead-lettering
  at `ERROR` with topic/partition/offset/cause.
- `docs/plans/backend-modernization/03-activity-log-ddl.sql` closes the real-Postgres schema gap
  (`ddl-auto` is unset in production) with an idempotent, runbook-headed `CREATE TABLE IF NOT
  EXISTS activity_log`.
- All 9 methods in `ActivityLogConsumerE2ETest` pass against a real `apache/kafka-native:4.3.1`
  Testcontainers broker: single-event persistence, all five per-type `detail` shapes asserted by
  exact string equality, stable serialization across identical inputs, non-merging equal-valued
  events, and full column population on the sparsest event type.
- **Found and fixed a real production bug while getting the live broker proof green**: an
  unqualified `@Autowired KafkaTemplate<String, Object>` anywhere in the app — including
  `KafkaEventPublisher`, the only production Kafka-publishing touchpoint — was silently resolving
  to the DLT-flavored template instead of a correctly-wired default one, because defining
  `deadLetterKafkaTemplate` as a bare `KafkaTemplate` `@Bean` suppressed Spring Boot's own
  autoconfigured default (`@ConditionalOnMissingBean(KafkaTemplate.class)` doesn't distinguish
  generic parameterizations). The DLT-flavored template built its producer straight from
  `KafkaProperties` and never honored a `KafkaConnectionDetails` override, so it would have quietly
  tried to publish every activity event to `localhost:9092` in any environment relying on
  connection-details injection — this was only discoverable by actually exercising the live bean
  graph against a real broker, which two prior blocked sessions never got the chance to do.
- Fixed the Windows/Docker Engine 29.x + testcontainers-java 1.21.0 incompatibility
  (testcontainers-java#11212) that blocked the previous two execution attempts, by pinning
  `docker-java`'s negotiated API version to `1.44` in a static initializer — zero host-level Docker
  Desktop configuration required.

## Task Commits

Both plan tasks were delivered together as one tracer-slice commit, with a follow-up fix commit
once live Testcontainers verification (blocked in two prior sessions) became possible:

1. **Task 1 & Task 2: activity log consumer, idempotent persistence, DLT wiring, and the five-event-type proof** - `f2c3063` (feat)
2. **Fix: Kafka producer bean shadowing + Testcontainers Docker API version pin + nanosecond-precision assertion** - `7ad1b55` (fix)

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/entity/ActivityAction.java` - closed five-constant enum (D-02)
- `src/main/java/com/vrudenko/kanban_board/entity/ActivityLogEntity.java` - insert-only row, unique `eventId`, `EnumType.STRING` action
- `src/main/java/com/vrudenko/kanban_board/repository/ActivityLogRepository.java` - `existsByEventId` + `findAllByBoardId` (Plan 03 consumes the latter)
- `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogRecorder.java` - two-layer idempotent persist, no `@Transactional`
- `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java` - `@KafkaListener`, exhaustive sealed switch, insertion-ordered detail map
- `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java` - both `NewTopic` beans, `DefaultErrorHandler`/`DeadLetterPublishingRecoverer`, byte-preserving DLT template, and (this session's fix) the restored `@Primary` default `kafkaTemplate` bean
- `src/main/java/com/vrudenko/kanban_board/constant/KafkaTopics.java` - `ACTIVITY_DLT` constant
- `src/main/java/com/vrudenko/kanban_board/constant/ValidationConstants.java` - `MAX_ACTIVITY_DETAIL_LENGTH`
- `src/main/resources/application.properties` / `application-test.properties` - consumer `ErrorHandlingDeserializer` block, scoped `trusted.packages`, pagination defaults
- `docs/plans/backend-modernization/03-activity-log-ddl.sql` - production DDL bridge, runbook-headed, idempotent
- `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java` - Testcontainers harness, plus (this session's fix) the `api.version=1.44` Docker Engine pin
- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java` - 9-method real-broker proof, plus (this session's fix) the `isCloseTo`/`ChronoUnit.MICROS` precision fix on the sparsest-event timestamp assertion

## Decisions Made

See frontmatter `decisions` for the full list. The two decisions made in this resumption session,
not the original implementation:

- Restored an explicit `@Primary` default `kafkaTemplate` bean rather than removing
  `deadLetterKafkaTemplate`'s own bean — both templates are needed (default JSON publishing vs.
  byte-preserving dead-letter publishing), so the fix is to make the default one addressable again
  via `@Primary`, not to collapse the two.
- Chose an AssertJ `isCloseTo(..., within(1, ChronoUnit.MICROS))` tolerance over a
  `truncatedTo(ChronoUnit.MICROS)` exact-equality rewrite for the sparsest-event `createdAt`
  assertion, after empirically observing the precision loss through Kafka JSON serialization + JPA
  persistence lands on *either* side of the original nanosecond value (not a pure truncation) —
  consistent with a double-precision epoch-seconds representation somewhere in the pipeline running
  out of significant-digit headroom below microsecond resolution. A tolerance-based comparison is
  correct regardless of which direction the rounding goes; an exact-equality rewrite guessing one
  direction would have just traded one flaky assertion for another.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug, production-relevant] `KafkaConsumerConfig.deadLetterKafkaTemplate` silently suppressed Spring Boot's default `KafkaTemplate` bean**
- **Found during:** Live Testcontainers verification of `ActivityLogConsumerE2ETest` (blocked in
  two prior sessions; this was the first session able to actually run it against a real broker).
- **Issue:** Spring Boot's autoconfigured `"kafkaTemplate"` bean is guarded by a bare-type
  `@ConditionalOnMissingBean(KafkaTemplate.class)`, which does not distinguish generic
  parameterizations. Defining `deadLetterKafkaTemplate` as a plain `KafkaTemplate<String, Object>`
  `@Bean` was therefore enough to suppress Boot's own default template entirely. Every unqualified
  `@Autowired KafkaTemplate<String, Object>` in the app — including `KafkaEventPublisher`, the
  sole production Kafka-publishing touchpoint — silently resolved to the DLT-flavored template
  instead, which built its producer properties directly from `KafkaProperties.buildProducerProperties()`
  rather than the autoconfigured `ProducerFactory`, so it never honored a `KafkaConnectionDetails`
  override (e.g. Testcontainers' `@ServiceConnection`) the way the real default template does.
  Every activity event was going through the wrong producer, not just in this test.
- **Fix:** Added an explicit `@Primary @Bean kafkaTemplate(ProducerFactory<String, Object>)` that
  reuses Spring Boot's autoconfigured `kafkaProducerFactory` bean, and changed
  `deadLetterKafkaTemplate` to build its own producer from that same factory's
  `getConfigurationProperties()` instead of re-deriving raw `KafkaProperties` independently — both
  templates now share one correctly-wired bootstrap-servers source. Full Javadoc added explaining
  the shadowing mechanism.
- **Files modified:** `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java`
- **Verification:** Full test suite green (`./gradlew test`), `ActivityLogConsumerE2ETest` 9/9
  green against a real broker, `KafkaEventPublisher`'s unqualified injection site unchanged and
  now resolving correctly.
- **Committed in:** `7ad1b55`

**2. [Rule 3 - Blocking issue] `docker-java` (Testcontainers' Docker client) rejected every Docker Engine 29.x transport with a malformed `400 Bad Request`**
- **Found during:** Two prior execution sessions, both fully blocked before this one — occurred on
  Windows named pipe and TCP transports alike, despite Docker Desktop and the plain `docker` CLI
  working normally.
- **Issue:** A confirmed `docker-java`/testcontainers-java incompatibility
  (testcontainers-java#11212 / PR #11216) between Docker Engine 29.x and testcontainers-java
  1.21.0's default negotiated Docker API version, fixed upstream as the new default in
  testcontainers-java 2.x.
- **Fix:** Pinned `System.setProperty("api.version", "1.44")` in a static initializer in
  `AbstractKafkaContainerTest`, with a Javadoc explaining the root cause and the upstream fix
  version. Requires zero host-level Docker Desktop configuration or TCP daemon exposure — CODE_STYLE
  rule 8 compliant (the fix lives in version-controlled test code, not a runbook).
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java`
- **Verification:** `./gradlew test --tests 'com.vrudenko.kanban_board.activitylog.ActivityLogConsumerE2ETest'` goes from "Could not find a valid Docker environment" (0 tests run) to 9/9 passing.
- **Committed in:** `7ad1b55`

**3. [Rule 1 - Bug, test assertion] Exact-nanosecond `Instant` equality assertion could never reliably pass**
- **Found during:** First live run of `ActivityLogConsumerE2ETest` once Deviations 1 and 2 above
  unblocked it — `shouldPopulateAllColumns_whenBoardCreatedEventIsSparsest` failed with a
  sub-microsecond `Instant` mismatch (`expected: ...801499900Z but was: ...801500Z`).
- **Issue:** `Instant.now()` carries JVM nanosecond precision, but the round trip through Kafka
  JSON serialization and JPA persistence loses precision below microseconds. The loss is not a
  consistent truncation — the observed delta can land on either side of the original value — so
  neither an exact-nanosecond comparison nor a `truncatedTo(ChronoUnit.MICROS)` rewrite (which
  assumes truncation, not rounding) would reliably hold.
- **Fix:** Replaced the equality assertion with `Assertions.assertThat(row.getCreatedAt())
  .isCloseTo(timestamp, Assertions.within(1, ChronoUnit.MICROS))`, with an in-code comment
  explaining the round-trip precision loss and why a tolerance-based comparison — not a guessed
  truncation direction — is the correct fix.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java`
- **Verification:** `ActivityLogConsumerE2ETest` 9/9 passing against a real broker.
- **Committed in:** `7ad1b55`

---

**Total deviations:** 3 auto-fixed (1 Rule 1 production bug, 1 Rule 3 blocking environment issue,
1 Rule 1 flaky-assertion bug), all found only once live Testcontainers verification — blocked for
two full prior sessions — actually became possible.
**Impact on plan:** No scope creep. The producer bean-shadowing fix is squarely within this plan's
own `KafkaConsumerConfig` file and directly protects the plan's own `must_haves.truths` about a
real broker correctly receiving published events; the Docker API pin and assertion fix are both
required to prove those truths at all.

## Issues Encountered

The two prior execution attempts referenced in the checkpoint context made no forward progress:
`docker-java` returned a malformed `400 BadRequestException` on every Docker transport (Windows
named pipe and TCP), even though Docker Desktop and the plain `docker` CLI worked fine outside
Testcontainers. This blocked all live verification of `ActivityLogConsumerE2ETest` until the root
cause (testcontainers-java#11212) was identified and the `api.version=1.44` pin applied. Getting
past that blocker immediately surfaced the `KafkaConsumerConfig` bean-shadowing bug (Deviation 1),
which had been completely invisible to `./gradlew compileJava`/`compileTestJava` and would have
stayed invisible to any test run that mocked or skipped the real Kafka producer wiring.

## User Setup Required

None for this plan directly, but a pre-existing plan-level `user_setup` requirement remains
outstanding and unaffected by this session's work: `docs/plans/backend-modernization/03-activity-log-ddl.sql`
must be run manually against the real deploy-target Postgres instance before this phase's PR
merges (master auto-deploys to EC2 on push, and the real profile sets no `ddl-auto`).

## Next Phase Readiness

- `AbstractKafkaContainerTest` and the now-correct `KafkaConsumerConfig` bean graph are ready for
  Plan 02 to build its DLT/reliability proofs directly on top of, with no known environment or
  wiring blockers remaining.
- `ActivityLogRepository.findAllByBoardId` is already declared and ready for Plan 03's read
  endpoint to consume.
- The `03-activity-log-ddl.sql` pre-merge `psql` step remains an open manual gate before this
  phase's PR can ship to production — carry it forward to phase-end verification.

## Known Stubs

None — every artifact this plan promised is real, proven against a live Testcontainers broker, and
the bean-shadowing bug this session found was a real defect (not a stub) with a real fix.

## Threat Flags

None beyond what the plan's own `<threat_model>` already covers. The `@Primary kafkaTemplate` fix
does not introduce new surface — it restores the default producer wiring the plan's own threat
register (T-03-01 through T-03-08) already assumed was in place.

## Self-Check: PASSED

- `src/main/java/com/vrudenko/kanban_board/entity/ActivityAction.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/entity/ActivityLogEntity.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/repository/ActivityLogRepository.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogRecorder.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumer.java` — FOUND
- `src/main/java/com/vrudenko/kanban_board/config/KafkaConsumerConfig.java` — FOUND
- `docs/plans/backend-modernization/03-activity-log-ddl.sql` — FOUND
- `src/test/java/com/vrudenko/kanban_board/activitylog/AbstractKafkaContainerTest.java` — FOUND
- `src/test/java/com/vrudenko/kanban_board/activitylog/ActivityLogConsumerE2ETest.java` — FOUND
- Commit `f2c3063` — FOUND in `git log --oneline --all`
- Commit `7ad1b55` — FOUND in `git log --oneline --all`

---
*Phase: 03-activity-log-consumer-reliability-read-api*
*Completed: 2026-08-02*
