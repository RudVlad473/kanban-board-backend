---
created: 2026-08-10T10:10:00.000Z
resolved: 2026-08-13
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

## Resolution

Resolved by quick task 260813-ncx: **VERDICT: INHERENT_BIRTHDAY.** A throwaway probe
(`RandFlakeCollisionProbeTest`, never entered git history, deleted before its own task's commit)
measured T=200 trials of 1000 rapid `EventIdGenerator.generate()` calls: C=13 colliding trials
against a birthday prediction E=10.63 computed from the observed per-trial millisecond-clustering
buckets (`C <= E+3*sqrt(E) = 20.41` holds; `C >= 2*E` does not). Decode validated on every trial
(0 decode failures, timestamps non-decreasing, every decoded value inside its captured wall-clock
window); the randomness-structure check found nothing beyond what a uniform 23-bit draw explains.
Full raw data and derivation: `.planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-RAW.txt`
and `PROBE-FINDINGS.md`.

**The test was corrected, not the generator** (per D-05/D-06):
`EventIdGeneratorTest$GenerateTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly` was
renamed to `shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly` and its
`hasSize(callCount)` assertion replaced with `hasSizeGreaterThanOrEqualTo(MIN_DISTINCT_IDS)` against
`MIN_DISTINCT_IDS = 993`, chosen from PROBE-FINDINGS.md's candidate-threshold table:
`P(distinct < 993) ~= 3.7e-15` (three orders of magnitude below the 1e-9 false-failure floor) and
993 is more than 50x the maximum entropy-free-delegate distinct count observed on this machine (4).
The method's Javadoc records the bit layout, T/C/E, and the threshold derivation; the class Javadoc
no longer claims exhaustive distinctness. `RandFlakeGenerator`'s existing same-millisecond comment
gained the measured rate in a comment-only, two-sentence addition — no statement, field, signature
or import touched.

**Falsification (D-07), proving the relaxed assertion still has teeth:** temporarily replaced the
`ThreadLocalRandom` draw in `generateRandflake()` with a constant. RED: `EventIdGeneratorTest >
GenerateTest > shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly() FAILED` (the
other two tests in the class stayed green — `TEST-...EventIdGeneratorTest$GenerateTest.xml`:
`tests="3" failures="1"`). Restored the constant; re-ran green (`tests="3" failures="0"`); confirmed
`git diff -- src/main` was empty at that point, before the comment-only edit landed.

**Production exposure (D-08):** `ActivityLogRecorder.persist`'s `existsByEventId` short-circuit
silently drops an event on a genuine `eventId` collision (no exception, no dead letter, one activity
row lost instead of two). Measured worst-case figure: ~47 expected colliding pairs across 1,000,000
lifetime events (a deliberately pessimistic extrapolation reusing the tight-loop's same-millisecond
clustering, since real traffic — ~14 call sites, one per user mutation — is far sparser); a more
realistic direct measurement at 20-call bursts found a 1.5e-4 collision rate. Both figures cleared
the 0.01-pairs bar, so a new `[minor]` todo was filed:
`.planning/todos/pending/2026-08-13-activitylogrecorder-silently-drops-events-on-eventid-collision.md`.

**Deliberately left alone:** `ActivityReadTest:249`'s `doesNotHaveDuplicates()` assertion (same
exposure, much lower volume — named, not touched); the class's `@SpringBootTest` /
`AbstractPostgresContainerTest` tier; no `@Tag` was added to exclude this class from `fastTest`.

Three consecutive `./gradlew test --tests '*EventIdGeneratorTest*' --rerun-tasks` runs recorded
green; `./gradlew spotlessCheck test` passed on the shipped tree.
