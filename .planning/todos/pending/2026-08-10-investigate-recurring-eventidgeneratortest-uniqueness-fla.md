---
created: 2026-08-10T10:10:00.000Z
title: Investigate two recurring, pre-existing test flakes surfaced during 07.1-07
area: testing
severity: minor
files:
  - src/test/java/com/vrudenko/kanban_board/e2e/column/ColumnLockingTest.java
  - src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppMockMvcTest.java
  - src/main/java/com/vrudenko/kanban_board/config/KafkaEventPublisher.java
  - src/test/java/com/vrudenko/kanban_board/EventIdGeneratorTest.java
  - src/main/java/com/vrudenko/kanban_board/config/EventIdGenerator.java
  - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
---

## Problem

Two independent, intermittent full-suite failures surfaced repeatedly during plan 07.1-07's
verification passes (`./gradlew test`), across roughly half of ~6 full runs this session. Neither
file involved in either flake is touched by 07.1-07's actual diff (test `@Tag` retargeting, 11 class
renames, one comment fix), so both were documented and left unfixed rather than chased inline — full
investigation notes in
`.planning/phases/07.1-address-hard-blockers-and-inconsistencies-from-the-frontend/deferred-items.md`.

**Flake 1 — `ColumnLockingTest.update_withoutVersion_returnsBadRequest()` signin returns 400
instead of 200.** Fails at `AbstractAppMockMvcTest.signinCookie()`'s `status().isOk()` assertion on
`POST /signin`. Never reproduces when the class runs alone (`./gradlew test --tests`, 2/2 clean this
session). `@Password`'s regex is permissive enough that no `dataFactory`-generated password content
can fail it (ruled out, not confirmed a non-cause via code reading only — no debug logging added).
Every reproduction's JUnit `testsuite timestamp` coincides, to the second, with `KafkaEventPublisher`
async-publish "Node disconnected" / "Bootstrap broker ... could not be established" log lines —
circumstantial evidence this is a Testcontainers-Kafka-broker-readiness timing issue specific to this
class's `@SpringBootTest` context startup window, not a defect in the signin path or this test class
itself.

**Flake 2 — `EventIdGeneratorTest$GenerateTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly()`
observes 999 distinct values instead of 1000** across 1000 rapid sequential
`EventIdGenerator.generate()` calls (which delegates to `RandFlakeGenerator`). Reproduced twice this
session, both times as the only failure in an otherwise-clean run.

## Solution

Neither flake blocked a commit outright this session (both cleared on the next `./gradlew test`
invocation), so there was no forcing function to investigate further inside 07.1-07. Suggested
approach for whoever picks this up:

1. **Flake 1**: reproduce with Kafka client debug logging enabled
   (`-Dspring.kafka.consumer.properties.session.timeout.ms` tracing, or
   `logging.level.org.apache.kafka=DEBUG`) to confirm or rule out the broker-readiness correlation.
   If confirmed, the fix likely belongs in the shared Testcontainers Kafka bootstrap fixture (ensure
   broker readiness is awaited before `@SpringBootTest` context refresh completes), not in
   `ColumnLockingTest` itself.
2. **Flake 2**: review `RandFlakeGenerator`'s collision probability under tight sequential calls
   within the same millisecond/tick — either the test's tolerance is too strict for a generator that
   was never guaranteed collision-free at this call rate, or there's a genuine narrow race in the
   generator worth tightening.

Neither investigation was started here beyond the code-reading/log-correlation already recorded in
07.1-07's `deferred-items.md`.
