---
created: 2026-08-10T10:10:00.000Z
title: Investigate recurring EventIdGeneratorTest uniqueness flake (ColumnLockingTest flake resolved)
area: testing
severity: minor
files:
  - src/test/java/com/vrudenko/kanban_board/EventIdGeneratorTest.java
  - src/main/java/com/vrudenko/kanban_board/config/EventIdGenerator.java
  - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
---

## Problem

**`EventIdGeneratorTest$GenerateTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly()`
observes 999 distinct values instead of 1000** across 1000 rapid sequential
`EventIdGenerator.generate()` calls (which delegates to `RandFlakeGenerator`). Reproduced three times
across this session's full-suite runs, always as an isolated failure in an otherwise-clean run.

## Solution

Not yet investigated beyond confirming it's real and reproducible. Suggested approach: review
`RandFlakeGenerator`'s collision probability under tight sequential calls within the same
millisecond/tick — either the test's tolerance is too strict for a generator that was never
guaranteed collision-free at this call rate, or there's a genuine narrow race in the generator worth
tightening.

## Resolved: `ColumnLockingTest` signin-400 flake (originally filed here as "Flake 1")

Originally documented alongside the above as a second, correlated-with-Kafka-timing flake in
`ColumnLockingTest.update_withoutVersion_returnsBadRequest()` (signin returned 400 instead of 200).
Root-caused and fixed 2026-08-10 during plan 07.1-09: **not** a Kafka-timing issue as originally
hypothesized. A temporary diagnostic on `AbstractAppMockMvcTest.signinCookie()` captured the real
failure body — `{"code":"VALIDATION_FAILED","errors":{"email":"Email cannot be empty"}}` for the
non-blank email `"or maybedreams@ma1lbox.org"`.

Decompiling `datafactory-0.8.jar`'s `DefaultContentDataValues` constant pool confirmed the actual
cause: its word corpus contains the literal two-word entry `"or maybe"` (confirming and extending
07.1-07 Task 1's earlier finding that this corpus is dirty story text, not clean single words).
`DataFactory.getEmailAddress()`'s word-based branch draws two "words" and concatenates them with no
separator; when one draw is `"or maybe"`, the result contains an embedded space (e.g. `"or
maybe"+"dreams"` = `"or maybedreams"`), which fails Jakarta's `@Email` format check. `@AppEmail`'s
`@ReportAsSingleViolation` then collapses that failure into the composed annotation's generic default
message ("Email cannot be empty") regardless of which sub-constraint actually failed — a second, real
bug in how the error message reads, independent of the root cause.

Fixed by replacing every `dataFactory.getEmailAddress()` fixture call with a guaranteed-valid
`RandomStringUtils.randomAlphabetic(10) + "@example.com"` generator (matching 07.1-07 Task 1's
established fix pattern for the analogous board-name collision bug): a new
`AbstractAppTest.generateValidEmail()` helper (used by `AbstractAppTest.createUser()` and inherited by
`AuthorizationGatingTest`'s two direct call sites), plus two standalone fixes in
`SchemaRegistryOutageE2ETest.java` and `SignupRequestDTOTest.java` (the latter had an existing,
now-resolved TODO comment independently describing this exact flakiness, confirming the diagnosis).
