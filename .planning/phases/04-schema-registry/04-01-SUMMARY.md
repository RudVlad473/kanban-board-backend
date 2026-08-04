---
phase: 04-schema-registry
plan: 01
subsystem: infra
tags: [avro, kafka, schema-registry, gradle-avro-plugin, confluent, activity-log]

# Dependency graph
requires: []
provides:
  - 5 Avro schemas (.avsc), one per ActivityEvent type, all fields required/no defaults (D-03)
  - gradle-avro-plugin codegen wired into the build, output excluded from ErrorProne
  - io.confluent:kafka-avro-serializer on the classpath with a single resolved kafka-clients version
  - ActivityEventAvroMapper: bidirectional ActivityEvent <-> Avro SpecificRecord translation
  - Confirmed generated accessor types (native UUID/Instant, no manual conversion needed)
  - Resolved org.testcontainers:redpanda version for Plan 02's local verification harness
affects: [04-02, 04-03, 04-04]

# Actuals (#2632)
actuals:
  tokens: 6432
  tasks: 3
  commits: 4

# Tech tracking
tech-stack:
  added:
    - com.github.davidmc24.gradle.plugin.avro 1.9.1 (archived, final release)
    - org.apache.avro:avro 1.12.1
    - io.confluent:kafka-avro-serializer 7.8.9
    - org.testcontainers:redpanda (resolved 1.21.0)
  patterns:
    - "Hand-authored bidirectional mapper for sealed-interface <-> Avro SpecificRecord translation (MapStruct cannot target a sealed interface)"
    - "toAvro: exhaustive switch, no default arm, mirrors ActivityLogConsumer.deriveActionAndDetailIds"
    - "toDomain: switch with a required default arm (SpecificRecord is not sealed), throws IllegalArgumentException naming the offending class"

key-files:
  created:
    - src/main/avro/AvroTaskMovedEvent.avsc
    - src/main/avro/AvroTaskCreatedEvent.avsc
    - src/main/avro/AvroTaskDeletedEvent.avsc
    - src/main/avro/AvroBoardCreatedEvent.avsc
    - src/main/avro/AvroColumnCreatedEvent.avsc
    - src/main/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapper.java
    - src/test/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapperTest.java
  modified:
    - build.gradle

key-decisions:
  - "5 separate .avsc files, one per event type, per D-03 -- no union schema"
  - "Avro-prefixed generated class names (AvroTaskCreatedEvent, not TaskCreatedEvent) to avoid FQN-only references in the mapper"
  - "Plain @Component mapper, not a MapStruct @Mapper interface -- MapStruct cannot generate a mapper whose source is a sealed interface dispatched over 5 unrelated record shapes"
  - "Both uuid and timestamp-millis Avro logical types produce native UUID/Instant accessors under gradle-avro-plugin 1.9.1 + Avro 1.12.1 -- no manual conversion code anywhere in the mapper"
  - "Round-trip test asserts timestamp with isCloseTo(within 1ms), not exact equality -- Avro's generated setTimestamp() truncates to millisecond precision, a real property of the timestamp-millis logical type, not a test bug"

patterns-established:
  - "Generated Avro codegen output (build/generated-main-avro-java/**) is excluded from ErrorProne via explicit alternation in excludedPaths, joining MapStruct's build/generated/** exclusion"

requirements-completed: [SCHEMA-01, SCHEMA-02]

coverage:
  - id: D1
    description: "5 Avro schemas (.avsc), one per ActivityEvent type, all fields required with no defaults; gradle-avro-plugin codegen produces 5 SpecificRecord classes; generated output excluded from ErrorProne"
    requirement: "SCHEMA-01"
    verification:
      - kind: other
        ref: "./gradlew generateAvroJava -- 5 .avsc files produce 5 generated classes under com/vrudenko/kanban_board/event/avro/"
        status: pass
      - kind: other
        ref: "./gradlew spotlessCheck compileJava compileTestJava -- ErrorProne excludedPaths covers build/generated-main-avro-java"
        status: pass
    human_judgment: false
  - id: D2
    description: "ActivityEventAvroMapper translates all 5 ActivityEvent types to/from Avro SpecificRecord in both directions with proven field fidelity, no manual conversion needed for either logical type"
    requirement: "SCHEMA-02"
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapperTest#RoundTripTest (5 tests)"
        status: pass
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapperTest#ToDomainTest.shouldThrow_whenRecordTypeIsUnrecognised"
        status: pass
    human_judgment: false
  - id: D3
    description: "ActivityEvent sealed interface and its 5 implementing records are byte-identical to their pre-phase state -- the mapper is fully external to the domain event package"
    requirement: "SCHEMA-02"
    verification:
      - kind: other
        ref: "git diff --exit-code src/main/java/com/vrudenko/kanban_board/event/"
        status: pass
    human_judgment: false
  - id: D4
    description: "Full pre-existing test suite (./gradlew test, 173 tests) remains green after the Avro dependencies land on the classpath"
    verification:
      - kind: integration
        ref: "./gradlew test"
        status: pass
    human_judgment: false

duration: 40min
completed: 2026-08-04
status: complete
---

# Phase 4 Plan 1: Avro Schema Foundation & Mapping Layer Summary

**5 Avro schemas (one per ActivityEvent type), gradle-avro-plugin codegen wired into the build, and a hand-authored ActivityEventAvroMapper proving bidirectional translation with a no-mock round-trip test — nothing wired into the live Kafka path yet.**

## Performance

- **Duration:** ~40 min
- **Started:** 2026-08-04T13:40:00+02:00 (approx.)
- **Completed:** 2026-08-04T14:17:00+02:00
- **Tasks:** 3 completed
- **Files modified:** 8 (1 modified, 7 created)

## Accomplishments

- Wired `gradle-avro-plugin` 1.9.1, Confluent's Maven repo, `org.apache.avro:avro:1.12.1`, `io.confluent:kafka-avro-serializer:7.8.9` (with `kafka-clients` excluded), and `org.testcontainers:redpanda` into `build.gradle`, plus extended ErrorProne's `excludedPaths` to cover the new Avro codegen output directory
- Authored all 5 `.avsc` schemas (`AvroTaskMovedEvent`, `AvroTaskCreatedEvent`, `AvroTaskDeletedEvent`, `AvroBoardCreatedEvent`, `AvroColumnCreatedEvent`), one per `ActivityEvent` type per D-03, every field required with no defaults
- Confirmed by direct inspection of generated source that both Avro logical types used (`uuid`, `timestamp-millis`) produce native `java.util.UUID`/`java.time.Instant` accessors under this exact plugin+Avro pairing — resolving RESEARCH.md's Assumption A2/Pitfall 1 concretely, recorded inline in `build.gradle`
- Built `ActivityEventAvroMapper` (`toAvro`/`toDomain`), a plain `@Component` mirroring `ActivityLogConsumer.deriveActionAndDetailIds`'s exhaustive-switch idiom, proven correct by a 6-test round-trip suite covering all 5 event types plus the unrecognised-record `IllegalArgumentException` case
- Confirmed `ActivityEvent` and its 5 implementing records are byte-identical to their pre-phase state (SCHEMA-02) via `git diff --exit-code`
- Full pre-existing suite (`./gradlew test`, 173 tests) stayed green after the new dependencies landed, following a targeted classpath fix (see Deviations)

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire Avro codegen into the build and probe the generated accessor types** - `2fbc97e` (feat)
2. **Task 2: Author the remaining four Avro schemas, one per event type (D-03)** - `617caab` (feat)
3. **Task 3: Bidirectional ActivityEventAvroMapper with a round-trip test over all 5 types** - `dc15649` (feat)
4. **Deviation fix: bump commons-lang3 test pin** - `8c481d4` (fix)

**Plan metadata:** pending (this commit)

_Note: TDD note for Task 3 — see Deviations below for why the RED phase was verified manually rather than committed as a standalone `test(...)` commit._

## Files Created/Modified

- `build.gradle` - Avro plugin, Confluent repo, 3 new dependencies, ErrorProne exclusion extension, commons-lang3 version bump, inline codegen observation comment
- `src/main/avro/AvroTaskMovedEvent.avsc` - probe schema (7 fields, richest event type)
- `src/main/avro/AvroTaskCreatedEvent.avsc` - 6 fields
- `src/main/avro/AvroTaskDeletedEvent.avsc` - 6 fields
- `src/main/avro/AvroBoardCreatedEvent.avsc` - 4 fields
- `src/main/avro/AvroColumnCreatedEvent.avsc` - 5 fields
- `src/main/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapper.java` - bidirectional mapper, `toAvro`/`toDomain`
- `src/test/java/com/vrudenko/kanban_board/event/avro/ActivityEventAvroMapperTest.java` - 6 tests (5 round-trip + 1 unrecognised-record)

## Decisions Made

- All decisions were pre-locked by CONTEXT.md (D-01/D-02/D-03) and PLAN.md's design_alternatives section (Approach A: `.avsc`-first codegen + hand-written mapper); no new architectural decisions were made during execution beyond the two logical-type/accessor-type findings already anticipated by RESEARCH.md as open questions to resolve by inspection (both resolved: native `UUID`/`Instant`, no manual conversion needed).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Bumped `commons-lang3` test dependency pin from 3.0 to 3.18.0**
- **Found during:** Task 3 (running the full `./gradlew test` verification step)
- **Issue:** `org.apache.avro:avro:1.12.1` (added in Task 1) transitively pulls `commons-compress:1.28.0`, which calls `commons-lang3`'s `ArrayFill` (added in 3.11). Spring Boot's dependency-management Gradle plugin forces any explicitly-declared dependency version project-wide, so the pre-existing `testImplementation 'org.apache.commons:commons-lang3:3.0'` pin silently downgraded every transitive `commons-lang3` request — including `commons-compress`'s — to 3.0, breaking Testcontainers' `TarArchiveOutputStream`-based container file copy with `NoClassDefFoundError`.
- **Fix:** Bumped the pin to 3.18.0 (matches the highest version already requested transitively by other callers), with a comment documenting the causal chain.
- **Files modified:** `build.gradle`
- **Verification:** `./gradlew test` — 3 previously-failing E2E tests (`ActivityLogConsumerE2ETest`, `ActivityLogDeadLetterE2ETest`, `ActivityLogIdempotencyE2ETest`) now pass; full suite (173 tests) green.
- **Committed in:** `8c481d4`

**2. [Rule 3 - Blocking] TDD RED phase verified manually, not committed as a standalone `test(...)` commit**
- **Found during:** Task 3 (following the mandated RED → GREEN TDD sequence for `tdd="true"`)
- **Issue:** This repo's pre-commit hook (`.githooks/pre-commit`) runs `spotlessApply` then the full non-E2E suite (`fastTest`), which requires a compiling build. A genuinely broken-build RED-phase commit (test referencing a not-yet-implemented `ActivityEventAvroMapper`) cannot pass the hook, and `--no-verify` is forbidden by both CLAUDE.md and this executor's explicit instructions.
- **Fix:** The RED verification step was still performed manually — the mapper implementation was temporarily moved out of the source tree, `./gradlew compileTestJava` was run and confirmed to fail with `cannot find symbol: class ActivityEventAvroMapper`, then the implementation was restored and the full GREEN verification (`spotlessCheck compileJava compileTestJava` + the target test class) was run before a single combined commit.
- **Files modified:** none beyond the planned Task 3 files
- **Verification:** RED confirmed via manual `compileTestJava` failure; GREEN confirmed via passing `ActivityEventAvroMapperTest` (6/6 tests) and green `spotlessCheck compileJava compileTestJava`
- **Committed in:** `dc15649`

**3. [Note, not a fix] Task 1's literal `<verify>` kafka-clients count command produces a false negative**
- **Found during:** Task 1 verification
- **Issue:** The plan's automated verify command extracts version-like substrings per line with `grep -oE '[0-9]+\.[0-9]+\.[0-9]+'` from `./gradlew dependencies` output. Gradle prints a version-conflict resolution as `org.apache.kafka:kafka-clients:3.8.1 -> 3.9.1` on one line; the naive extraction captures both `3.8.1` and `3.9.1` as separate "versions," so `sort -u | wc -l` reports 2 instead of 1, even though there is genuinely only one resolved `kafka-clients` dependency edge (the `exclude` in `io.confluent:kafka-avro-serializer`'s declaration worked as intended).
- **Fix:** Not fixed (no code change needed) — confirmed correctness with `grep -oE 'org.apache.kafka:kafka-clients:[0-9.]+( -> [0-9.]+)?' | sort -u`, which shows exactly one dependency edge, resolved to 3.9.1.
- **Files modified:** none
- **Verification:** `./gradlew dependencies --configuration compileClasspath` shows a single `org.apache.kafka:kafka-clients:3.8.1 -> 3.9.1` edge, no fork.
- **Committed in:** n/a (verification-only finding)

---

**Total deviations:** 2 auto-fixed (1 bug, 1 blocking-process adaptation), 1 verification-script false-negative noted but not fixed
**Impact on plan:** Both fixes were necessary to keep the pre-existing suite green and to respect the pre-commit hook's full-suite gate. No scope creep — no code outside Task 1/2/3's planned files was touched except the one-line `commons-lang3` version bump.

## Issues Encountered

- The Windows Gradle daemon held a file lock on `build/test-results/fastTest/binary/output.bin` after a tool-timeout-interrupted commit attempt, causing a subsequent `fastTest` run to fail with `IOException: Unable to delete directory`. Resolved with `./gradlew --stop` + removing the stale directory before retrying; not a code issue.

## User Setup Required

None - no external service configuration required. This plan touches no live Kafka/registry path.

## Next Phase Readiness

- Plan 02 has everything it needs recorded: observed accessor types (native `UUID`/`Instant`, no manual conversion required), the resolved `org.testcontainers:redpanda` version (1.21.0 — `getSchemaRegistryAddress()` presence on this version was not independently jar-inspected this session; confirm at Plan 02's first actual compile against it, per RESEARCH.md Open Question 2's own recommendation), and the exact generated record full names that become `RecordNameStrategy` subject names:
  - `com.vrudenko.kanban_board.event.avro.AvroTaskCreatedEvent`
  - `com.vrudenko.kanban_board.event.avro.AvroTaskMovedEvent`
  - `com.vrudenko.kanban_board.event.avro.AvroTaskDeletedEvent`
  - `com.vrudenko.kanban_board.event.avro.AvroBoardCreatedEvent`
  - `com.vrudenko.kanban_board.event.avro.AvroColumnCreatedEvent`
- No blockers. The mapper, schemas, and codegen wiring are fully self-contained and untested against a live registry — that is explicitly Plan 02's scope (wiring the Confluent serde into `KafkaEventPublisher`/`ActivityLogConsumer` and registering schemas against a local Redpanda instance).

---
*Phase: 04-schema-registry*
*Completed: 2026-08-04*

## Self-Check: PASSED

All 7 created source files and the SUMMARY.md itself confirmed present on disk; all 4 task/deviation commit hashes (`2fbc97e`, `617caab`, `dc15649`, `8c481d4`) confirmed present in `git log --oneline --all`.
