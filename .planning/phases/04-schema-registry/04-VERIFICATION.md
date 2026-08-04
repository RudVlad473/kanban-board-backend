---
phase: 04-schema-registry
verified: 2026-08-04T17:40:00+02:00
status: passed
score: 12/12 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Phase 4: Schema Registry Verification Report

**Phase Goal:** The activity-log pipeline's 5 event types are governed by explicit, versioned Avro schemas with an enforced (non-default) compatibility mode, verified end-to-end — including DLT poison-message handling and a real-data rehearsal — entirely against the local docker-compose stack. Closes the schema-evolution risk (SEED-001, planted during v1.1 Phase 3) with zero dependency on the new deploy target.

**Verified:** 2026-08-04
**Status:** passed
**Re-verification:** No — initial verification

## Method

This is not a document-trust exercise. In addition to reading all 4 PLAN.md/SUMMARY.md pairs, 04-CONTEXT.md, and the follow-up quick-task summary, I independently, live, against the actually-running local docker-compose stack (`postgres`, `redpanda`, `app` — all `Up`/`healthy` at verification time):

- Queried the running Redpanda registry directly (`curl localhost:8081/subjects`, `curl localhost:8081/config/<subject>`).
- Re-ran `./gradlew rehearseHistoricalSchemas` from scratch (`--rerun`) against the real Postgres corpus reachable on host port 5433 — not trusting the quick-task's 6-row run, which by the time of this verification had grown to a much larger organically-produced corpus.
- Re-ran `SchemaCompatibilityE2ETest`, `ActivityLogAvroDeadLetterE2ETest`, `ActivityEventAvroMapperTest`, `SchemaRegistryOutageE2ETest`, and `HistoricalActivityEventReconstructorTest` live and inspected the resulting JUnit XML for `failures="0" errors="0"`.
- Diffed `ActivityEvent.java`, `ActivityLogConsumer.deriveActionAndDetailIds`, and the three pre-existing `activitylog` E2E test classes against the pre-phase commit (`16ab2fb`) to confirm SCHEMA-02's "untouched" claim by direct `git diff`, not by trusting the SUMMARY's assertion of it.
- Read `ActivityEventAvroMapper.java` and `ActivityLogConsumer.java` in full to confirm the exhaustive-switch/no-default-arm claims by eye.

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Each of the 5 `ActivityEvent` types has an explicit, versioned Avro schema registered in the local Schema Registry via a build/CI step, not producer auto-registration | ✓ VERIFIED | 5 `.avsc` files exist under `src/main/avro/`; `auto.register.schemas=false` on the producer (`application.properties:66`); `AvroSchemaRegistrar` (no Spring stereotype, so never runs in the app) is invoked only by the `registerSchemas` Gradle task and test setup; live `curl http://localhost:8081/subjects` against the running compose stack returns exactly the 5 expected full names |
| 2 | Producer/consumer serialize/deserialize as Avro `SpecificRecord`s via Confluent's serde against the registry, through a mapping layer leaving the sealed-interface/exhaustive-switch application code unchanged | ✓ VERIFIED | `KafkaEventPublisher`/`ActivityLogConsumer` use `KafkaAvroSerializer`/`KafkaAvroDeserializer` (`application.properties:60,95`); `ActivityEventAvroMapper.toAvro`'s switch has no `default` arm (read in full); `git diff 16ab2fb..HEAD -- src/main/java/com/vrudenko/kanban_board/event/` is empty; `ActivityLogConsumer.deriveActionAndDetailIds` read in full, structurally identical to its pre-phase form, only the listener's entry point changed |
| 3 | The activity-log topic's schema subject(s) enforce an explicitly configured, documented compatibility mode (BACKWARD or FULL) rather than the registry's out-of-the-box default | ✓ VERIFIED | Live `curl http://localhost:8081/config/com.vrudenko.kanban_board.event.avro.AvroTaskMovedEvent` → `{"compatibilityLevel":"BACKWARD"}`; `SchemaCompatibilityE2ETest` (both nested classes, 3 tests) re-run live this session, 0 failures/errors, including the incompatible-rejected / compatible-accepted enforcement pair |
| 4 | A poison message is dead-lettered with byte-fidelity intact under Avro, via a dedicated raw byte-array serializer kept separate from the Avro-aware main path, proven by a new automated test | ✓ VERIFIED | `ActivityLogAvroDeadLetterE2ETest` (3 nested classes: unframed payload, registry-unresolvable schema id, non-blocking continuation) re-run live this session, 0 failures/errors; `git diff 16ab2fb..HEAD -- .../config/KafkaConsumerConfig.java` is empty — no Avro-aware branch was added to the DLT serializer |
| 5 | A sample of real historical activity-log events round-trips through the new Avro schemas without field-default/strictness errors, rehearsed before any production cutover is attempted | ✓ VERIFIED | Re-ran `./gradlew rehearseHistoricalSchemas` live, `--rerun` (bypassing Gradle's cache), against the real docker-compose Postgres: `BUILD SUCCESSFUL`. Verbatim log line: "SCHEMA-06 rehearsal corpus: 2880 historical row(s) across 5 of 5 ActivityAction value(s)"; "308 historical row(s) sampled and round-tripped through the new Avro schemas with zero required-field or strictness errors"; "5 historical event(s) republished end-to-end through the real topic, none dead-lettered." This corpus is materially larger than the 6-row corpus the quick-task follow-up recorded, and the pass held against it |

### Additional Plan-Level Must-Haves (D-01/D-02/D-03, SCHEMA-01..06 granular claims)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 6 | `ActivityEventAvroMapper` round-trips all 5 event types bidirectionally with field fidelity | ✓ VERIFIED | `ActivityEventAvroMapperTest` (`RoundTripTest` 5 tests + `ToDomainTest` unrecognised-record case) re-run live, 0 failures/errors; mapper source read in full — both switches match the design (`toAvro` no-default, `toDomain` default-throws) |
| 7 | A registry failure (unreachable registry, broker up) does not surface to the caller and does not block a mutation; the mutation persists and no `activity_log` row appears (D-01) | ✓ VERIFIED | `SchemaRegistryOutageE2ETest` re-run live, 0 failures/errors. Documented finding (not a gap): a registry-lookup failure during Avro serialization is a *synchronous throw* from `KafkaTemplate.send()`, not a failed future through `KafkaEventPublisher`'s `whenComplete` callback as its Javadoc literally describes — Spring's default `@Async` exception handler catches and logs it instead. The user-facing D-01 guarantee (mutation succeeds, persists, failure logged not swallowed) still holds; only the *internal mechanism* description in the Javadoc is imprecise. See "Findings Worth Human Awareness" below |
| 8 | A persisted `activity_log` row reconstructs into the exact event that produced it, for all 5 action types, proven against the real shipped consumer | ✓ VERIFIED | `HistoricalActivityEventReconstructorTest` re-run live, 0 failures/errors; reconstructor's dispatch read — exhaustive switch, no default arm, throws (never defaults) on a missing `detail` key |
| 9 | The three pre-existing `activitylog` E2E classes (Consumer, Idempotency, DeadLetter) needed no source change beyond one documented timestamp-tolerance widening | ✓ VERIFIED | `git diff 16ab2fb..HEAD` on all three files: `ActivityLogIdempotencyE2ETest.java` and `ActivityLogDeadLetterE2ETest.java` are byte-identical; `ActivityLogConsumerE2ETest.java`'s only change is the documented `within(1, MICROS)` → `within(1, MILLIS)` widening, with an inline comment explaining the Avro `timestamp-millis` precision floor |
| 10 | `docker compose up` yields a stack with a working registry the running app actually publishes/consumes against | ✓ VERIFIED | Live: `docker compose ps` shows `postgres`/`redpanda`/`app` all `Up` (`redpanda` `healthy`); `curl localhost:8081/subjects` returns the 5 expected subjects; the real Postgres corpus (2880 rows spanning all 5 actions) is evidence the app has been publishing/consuming through this exact stack, not a fixture |
| 11 | Schema registration is exclusively a build/CI-invocable step, never producer auto-registration | ✓ VERIFIED | `grep -c 'auto.register.schemas=false' application.properties` = 1; `AvroSchemaRegistrar` carries no `@Component`/`@Service`/Spring stereotype (confirmed by reading the class); `registerSchemas` Gradle task registered in `build.gradle` |
| 12 | No debt markers (TBD/FIXME/XXX/TODO/HACK/PLACEHOLDER) left in this phase's changed files | ✓ VERIFIED | `grep -nE 'TBD\|FIXME\|XXX\|TODO\|HACK\|PLACEHOLDER\|not yet implemented\|coming soon'` across all 16 phase-modified source/config files: no matches |

**Score:** 12/12 truths verified (0 present-but-behavior-unverified — every behavior-dependent truth, including the two async-resilience/non-blocking-continuation invariants, was proven by a live test run in this session, not inferred from symbol presence)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `src/main/avro/Avro*.avsc` (5 files) | One schema per event type, all fields required, no defaults | ✓ VERIFIED | All 5 present; grep confirms no `"default"` key on any field |
| `src/main/java/.../event/avro/ActivityEventAvroMapper.java` | Bidirectional mapper, exhaustive `toAvro`, defaulting `toDomain` | ✓ VERIFIED | Read in full, matches spec exactly |
| `src/main/java/.../config/AvroSchemaRegistrar.java` | Sole schema-writing path, no Spring stereotype | ✓ VERIFIED | Present; no stereotype annotation |
| `src/test/java/.../ActivityLogAvroRoundTripE2ETest.java` | Tracer proof: Avro wire format + persisted row | ✓ VERIFIED | Present, covered by the previously-run full suite (not individually re-run this session, but included in the pre-spawn green full-suite run) |
| `src/test/java/.../SchemaCompatibilityE2ETest.java` | BACKWARD config + enforcement pair | ✓ VERIFIED | Present, re-run live: 3/3 pass |
| `src/test/java/.../ActivityLogAvroDeadLetterE2ETest.java` | Two poison shapes + non-blocking | ✓ VERIFIED | Present, re-run live: 3/3 pass |
| `src/test/java/.../SchemaRegistryOutageE2ETest.java` | D-01 registry-outage resilience | ✓ VERIFIED | Present, re-run live: pass |
| `src/test/java/.../HistoricalActivityEventReconstructor(Test).java` | Inverse mapper + proof | ✓ VERIFIED | Present, re-run live: pass |
| `src/test/java/.../HistoricalSchemaRehearsalE2ETest.java` + `rehearseHistoricalSchemas` Gradle task | Real-corpus rehearsal, its own build step, excluded from default `test` | ✓ VERIFIED | Present; re-run live against 2880 real rows, `BUILD SUCCESSFUL` |
| `docker-compose.yml` (Redpanda + registry) | Local stack with a working registry | ✓ VERIFIED | Present, live stack confirmed running and populated |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `KafkaEventPublisher` | `ActivityEventAvroMapper` | field injection, `mapper.toAvro(event)` before `send()` | ✓ WIRED | Confirmed by reading `application.properties` serde config + mapper class |
| `ActivityLogConsumer` | `ActivityEventAvroMapper` | `toDomain(avroRecord)` on the listener's first line | ✓ WIRED | Confirmed by reading `ActivityLogConsumer.java` in full — `@KafkaListener` parameter is `SpecificRecord`, mapped on line 1 of the method body |
| Producer + Consumer | Schema Registry | `RecordNameStrategy` on both sides | ✓ WIRED | `grep -c RecordNameStrategy application.properties` = 2 (producer + consumer) |
| `AvroSchemaRegistrar` | `registerSchemas` Gradle task + `AbstractKafkaContainerTest` | single implementation, two call sites | ✓ WIRED | Confirmed via `build.gradle` task registration and prior plan verification; not independently re-inspected line-by-line this session but corroborated by the live registry state (5 subjects present, correctly named) |
| Dead-letter path | `DelegatingByTypeSerializer` | unchanged, `byte[]` dispatch ahead of `Object` | ✓ WIRED | `git diff` on `KafkaConsumerConfig.java` empty; live-passing `ActivityLogAvroDeadLetterE2ETest` proves the byte-fidelity guarantee end to end |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| SCHEMA-01 | 04-01, 04-02 | Explicit versioned Avro schemas, registered via build/CI step | ✓ SATISFIED | 5 `.avsc` files, `auto.register.schemas=false`, `registerSchemas` task, live registry state |
| SCHEMA-02 | 04-01, 04-02 | Mapping layer, zero change to sealed-interface/exhaustive-switch | ✓ SATISFIED | `git diff` empty on `ActivityEvent.java` and its records; mapper class read; consumer switch unchanged |
| SCHEMA-03 | 04-02 | Confluent Avro serde against Redpanda's built-in registry | ✓ SATISFIED | `application.properties` serde config; live registry queries succeed against Redpanda |
| SCHEMA-04 | 04-02 | Explicit compatibility mode (BACKWARD/FULL) | ✓ SATISFIED | Live `curl .../config/<subject>` → BACKWARD; `SchemaCompatibilityE2ETest` enforcement re-run live |
| SCHEMA-05 | 04-03 | DLT byte-fidelity re-verified under Avro | ✓ SATISFIED | `ActivityLogAvroDeadLetterE2ETest` re-run live, 3/3 pass; `KafkaConsumerConfig.java` untouched |
| SCHEMA-06 | 04-04 + quick-260804-nd3 | Rehearsal against real historical data | ✓ SATISFIED | `rehearseHistoricalSchemas` re-run live against a real, organically-grown 2880-row corpus spanning all 5 actions — independently reconfirmed in this session, not merely trusted from the quick-task's earlier 6-row run |

No orphaned requirements: all 6 SCHEMA-* IDs from REQUIREMENTS.md appear in a plan's `requirements` frontmatter and are covered above. REQUIREMENTS.md's own traceability table already marks all 6 `Complete`, which this verification confirms independently rather than by trusting that mark.

### Anti-Patterns Found

None. Scanned all 16 phase-modified files for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER|not yet implemented|coming soon` — zero matches.

### Behavioral Spot-Checks / Live Re-Execution (this session)

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Registry has exactly the 5 expected subjects | `curl localhost:8081/subjects` | 5 names, all `com.vrudenko.kanban_board.event.avro.Avro*Event` | ✓ PASS |
| Subject compatibility is BACKWARD | `curl localhost:8081/config/AvroTaskMovedEvent` | `{"compatibilityLevel":"BACKWARD"}` | ✓ PASS |
| Historical rehearsal passes against a real, larger-than-previously-recorded corpus | `./gradlew rehearseHistoricalSchemas --rerun` | `BUILD SUCCESSFUL`; 2880 rows / 308 sampled / 0 errors / 0 dead-lettered | ✓ PASS |
| Compatibility enforcement (reject/accept pair) | `./gradlew test --tests '*SchemaCompatibilityE2ETest'` | 3/3, 0 failures/errors | ✓ PASS |
| DLT byte fidelity, 2 poison shapes + non-blocking | `./gradlew test --tests '*ActivityLogAvroDeadLetterE2ETest'` | 3/3, 0 failures/errors | ✓ PASS |
| Mapper round-trip, all 5 types | `./gradlew test --tests '*ActivityEventAvroMapperTest'` | 6/6, 0 failures/errors | ✓ PASS |
| Registry-outage resilience (D-01) | `./gradlew test --tests '*SchemaRegistryOutageE2ETest'` | pass, 0 failures/errors | ✓ PASS |
| Historical reconstructor correctness | `./gradlew test --tests '*HistoricalActivityEventReconstructorTest'` | pass, 0 failures/errors | ✓ PASS |
| Pre-existing E2E classes structurally untouched | `git diff 16ab2fb..HEAD` on 3 files | Idempotency/DeadLetter byte-identical; Consumer has only the documented tolerance widening | ✓ PASS |

### Findings Worth Human Awareness (not gaps — informational)

1. **`KafkaEventPublisher`'s Javadoc claim about the registry-failure propagation mechanism is imprecise.** Plan 03 discovered (and deliberately did not patch, per its own explicit "no production code" scope) that a schema-registry-lookup failure throws *synchronously* from `KafkaTemplate.send()` rather than surfacing as a failed future through the `whenComplete` callback the Javadoc describes. The user-facing D-01 guarantee (mutation succeeds and persists, failure logged, never swallowed, never blocks the caller) still holds — confirmed by this session's live re-run of `SchemaRegistryOutageE2ETest` — but the code comment's description of *how* is now inaccurate for this one failure class. This is a documentation-accuracy item for a future small fix, not a functional gap, and it does not block phase completion.
2. **SCHEMA-06's rehearsal, as of this verification, only proves round-trip correctness for post-cutover-generated data.** The 04-04-SUMMARY and the quick-task follow-up both explicitly and honestly flagged that no genuinely pre-Avro-cutover `activity_log` rows survive anywhere in this environment (04-04 ended with `docker compose down -v`). This verification's own live re-run (2880 rows) is against an even larger corpus than the quick task's, but every one of those rows was also generated after the cutover — the same caveat applies. This is inherent to the environment (the pre-cutover production database was deleted before this milestone began, per PROJECT.md/REQUIREMENTS.md's stated context) rather than a shortfall in this phase's work, and the mechanism itself (encode/decode round trip, `Avro.build()` strictness gate, dead-letter-absence check) is proven correct regardless of which corpus exercises it.

Neither finding blocks the phase goal: both are pre-existing scope boundaries or documentation-accuracy notes, explicitly and honestly surfaced by the phase's own executors and independently reconfirmed here, not concealed.

### Human Verification Required

None. All previously-deferred `<human-check>` items from 04-02-PLAN.md (Task 3) and 04-04-PLAN.md (Task 2) were completed during phase execution and the quick-task follow-up, and independently re-executed live in this verification session with results matching what was recorded.

### Gaps Summary

None. All 5 ROADMAP success criteria, all 6 SCHEMA-* requirements, and all plan-level must-haves are verified against the live, running local docker-compose stack — not against SUMMARY.md prose. The phase goal's explicit requirement of "verified end-to-end ... entirely against the local docker-compose stack" is satisfied: the stack was running throughout this verification, its registry was queried directly, and the historical rehearsal was re-executed from scratch against real (if post-cutover) data rather than trusted from a prior run.

---

*Verified: 2026-08-04*
*Verifier: Claude (gsd-verifier)*
