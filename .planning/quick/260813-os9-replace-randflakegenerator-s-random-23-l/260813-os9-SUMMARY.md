---
phase: quick-260813-os9
plan: 01
subsystem: backend
tags: [randflake, id-generation, snowflake, monotonic-sequence, atomiclong, event-id, activity-log]

requires:
  - phase: quick-260813-ncx
    provides: "Measured the prior random-low-bits design's same-millisecond collision rate (13/200 trials of 1000 calls, ~6.5%) and the MIN_DISTINCT_IDS=993 tolerance this plan tightens back to an exact 1000"
provides:
  - "RandFlakeGenerator's 23 random low bits replaced with a monotonic, cross-thread, cross-instance shared sequence (static AtomicLong updateAndGet CAS loop) -- same-millisecond id collisions are now structurally impossible instead of a measured probabilistic event"
  - "63-bit layout (1 sign + 41 timestamp + 22 sequence) closing the 2058 sign-overflow bug, exhausting 2087-09-07"
  - "CUSTOM_EPOCH moved to 2018-01-01 to keep every new id numerically above the highest id the legacy layout could ever produce, preserving @OrderBy(\"id\") creation-order semantics across the deploy boundary"
  - "EventIdGeneratorTest's distinctness assertion tightened from a probabilistic MIN_DISTINCT_IDS=993 threshold to an exact hasSize(1000), falsification-proven"
affects: [testing, activity-log, id-generation, backend-modernization-epic-2]

actuals:
  tokens: 58000
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Single packed static AtomicLong holding (timestamp << sequenceBits | sequence), updated via updateAndGet(prev -> max(candidate, prev + 1)) -- lock-free monotonic Snowflake-family sequence where the CAS'd word itself is what makes the (timestamp, sequence) pair atomic"
    - "Frozen (not live-computed) ceiling constant for cross-deploy-boundary ordering-continuity assertions -- a live-computed ceiling would silently stop being a real assertion once its cutoff date passes"
    - "Recompute layout/epoch constants with a throwaway node --eval BigInt script at execution time rather than trusting a plan's illustrative numbers verbatim"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
    - src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java
    - .planning/todos/completed/2026-08-13-add-monotonic-sequence-counter-to-randflakegenerator-fix-205.md (moved from pending/)
    - .planning/todos/completed/2026-08-13-activitylogrecorder-silently-drops-events-on-eventid-collision.md (moved from pending/)

key-decisions:
  - "LAST_ID declared static (not an instance field): Hibernate builds one RandFlakeGenerator per @RandFlakeId mapping and EventIdGenerator builds its own with `new RandFlakeGenerator()`; a non-static counter would give each instance its own sequence and two instances ticking in the same millisecond would emit identical ids -- a finding this plan's own measurement surfaced, not anticipated by the source todo"
  - "CUSTOM_EPOCH moved to 2018-01-01 (not left at 2023-01-01): narrowing the low field alone would have halved every future id's magnitude, inverting @OrderBy(\"id\") on three entity collections until a measured 2030-03-26; moving the epoch back keeps new ids above the legacy ceiling instead"
  - "Recomputed legacy-layout ceiling used as the frozen ordering-continuity constant: 1058897343291588607 (includes the legacy layout's maximum possible random-bit contribution), 8,388,607 higher than the plan's own illustrative figure (1058897343283200000, which implicitly assumed zero random bits) -- the plan's own instruction that a disagreeing recomputation wins was applied"
  - "ActivityLogRecorder's dependent todo closed, not merely amended: the generator-collision source it was filed against no longer exists, and its one unaffected residue (genuine Kafka redelivery dedupe) was never actually broken"

patterns-established:
  - "Falsification-proof pattern (established by 260813-ncx, reused here): temporarily break the property under test in production code, confirm the exact expected assertions go RED (and no others), restore, confirm GREEN with an empty git diff -- src/main"

requirements-completed: [QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER]

coverage:
  - id: D1
    description: "2000 ids generated concurrently from 8 threads are all distinct, deterministically"
    requirement: QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java#GenerateRandflakeTest.shouldProduceAllDistinctIds_whenCalledConcurrentlyFromMultipleThreads"
        status: pass
    human_judgment: false
  - id: D2
    description: "Ids from two separate RandFlakeGenerator instances interleaved in the same millisecond are all distinct"
    requirement: QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java#GenerateRandflakeTest.shouldProduceAllDistinctIds_whenInterleavedAcrossTwoSeparateInstances"
        status: pass
    human_judgment: false
  - id: D3
    description: "1000 rapid sequential calls yield exactly 1000 distinct, strictly increasing ids -- no probabilistic tolerance"
    requirement: QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java#GenerateRandflakeTest.shouldProduceStrictlyIncreasingIds_whenCalledRapidlyInSequence"
        status: pass
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java#GenerateTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly"
        status: pass
    human_judgment: false
  - id: D4
    description: "Every generated id decodes to a positive long; bit 63 never written"
    requirement: QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java#GenerateRandflakeTest.shouldDecodeToPositiveLong_whenGenerated"
        status: pass
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java#GenerateRandflakeTest.shouldRenderAsTwelveCharPositiveBase36String_whenGeneratedFreshly"
        status: pass
    human_judgment: false
  - id: D5
    description: "Every id generated after this change exceeds the highest id the legacy layout could ever have produced -- @OrderBy(\"id\") creation-order continuity across the deploy boundary, verified not assumed"
    requirement: QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java#GenerateRandflakeTest.shouldDecodeAboveLegacyLayoutCeiling_whenGeneratedFreshly"
        status: pass
    human_judgment: false
  - id: D6
    description: "Base36 string width unchanged (12 chars) so every already-persisted @RandFlakeId primary key remains a valid, comparable id"
    requirement: QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java#GenerateRandflakeTest.shouldRenderAsTwelveCharPositiveBase36String_whenGeneratedFreshly"
        status: pass
    human_judgment: false
  - id: D7
    description: "Falsification proves the tightened EventIdGeneratorTest/RandFlakeGeneratorTest assertions have teeth: removing the monotonic carry reds exactly the 5 uniqueness/ordering-dependent assertions, restoring greens all 10 with an empty git diff -- src/main"
    requirement: QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER
    verification:
      - kind: manual_procedural
        ref: "Task 2: replaced Math.max(candidate, previous + 1) with bare candidate in RandFlakeGenerator.generateRandflake(); ./gradlew test --tests '*RandFlakeGeneratorTest*' --tests '*EventIdGeneratorTest*' -> RandFlakeGeneratorTest tests=6 failures=3, EventIdGeneratorTest$GenerateTest tests=4 failures=2 (the exact 5 assertions depending on same-millisecond distinctness/ordering; the other 5 stayed green). Restored: both suites green (6/6, 4/4), git diff -- src/main empty at that point."
        status: pass
    human_judgment: false
  - id: D8
    description: "Source todo closed with a Resolution quoting measured/recomputed numbers; dependent ActivityLogRecorder todo reconciled"
    requirement: QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER
    verification:
      - kind: other
        ref: ".planning/todos/completed/2026-08-13-add-monotonic-sequence-counter-to-randflakegenerator-fix-205.md#Resolution and .planning/todos/completed/2026-08-13-activitylogrecorder-silently-drops-events-on-eventid-collision.md#Resolution"
        status: pass
    human_judgment: false
  - id: D9
    description: "Full suite green, zero shrinkage"
    requirement: QUICK-260813-OS9-RANDFLAKESEQUENCECOUNTER
    verification:
      - kind: unit
        ref: "./gradlew spotlessCheck test -- BUILD SUCCESSFUL, 189 test-result XML files, 451 tests, 0 failures, 0 errors (net +7 over the prior 444-test baseline)"
        status: pass
    human_judgment: false

duration: ~50min
completed: 2026-08-13
status: complete
---

# Quick Task 260813-os9: RandFlakeGenerator monotonic sequence counter Summary

**Replaced `RandFlakeGenerator`'s 23 random low bits with a monotonic, cross-thread/cross-instance shared `AtomicLong` sequence and moved `CUSTOM_EPOCH` to 2018-01-01, making same-millisecond id collisions structurally impossible while preserving `@OrderBy("id")` ordering across the deploy boundary.**

## Performance

- **Duration:** ~50 min
- **Started:** ~2026-08-13T15:55:00Z (approx, first plan/context read)
- **Completed:** 2026-08-13T16:30:28Z (final commit timestamp)
- **Tasks:** 3
- **Files modified:** 6 (2 source files, 1 new test file, 2 todos moved+resolved, 1 todo amended-then-moved)

## Accomplishments

- Rewrote `RandFlakeGenerator.generateRandflake()` to pack `(timestamp << 22 | sequence)` into a single `static AtomicLong`, updated via a lock-free `updateAndGet(previous -> Math.max(candidate, previous + 1))` CAS loop -- same-millisecond collisions across threads and generator instances are now structurally impossible, not a measured ~6.5%-per-1000-calls probabilistic event (quick task 260813-ncx)
- Chose 41-bit timestamp + 22-bit sequence + reserved sign bit (63 bits total), closing the documented 2058 sign-overflow bug and pushing exhaustion to 2087-09-07 (recomputed via a throwaway Node.js `BigInt` script, confirmed exact match to the plan's illustrative date)
- Moved `CUSTOM_EPOCH` from 2023-01-01 to 2018-01-01 in the same change -- narrowing the low field alone would have halved every future id's magnitude and inverted `@OrderBy("id")` on `BoardEntity.column`, `ColumnEntity.task` and `TaskEntity.subtasks` until a measured 2030-03-26; the epoch move keeps every new id above the legacy layout's maximum possible output instead
- New `RandFlakeGeneratorTest` (6 tests, no Spring context): cross-thread distinctness (8 threads x 250 calls, `CountDownLatch` start gate), cross-instance distinctness, strict monotonicity over 1000 sequential calls, ordering continuity against a frozen legacy-ceiling constant, and base36 width/sign
- Tightened `EventIdGeneratorTest`'s `shouldReturnOverwhelminglyDistinctValues_...` (relaxed to `MIN_DISTINCT_IDS=993` by 260813-ncx) back to an exact `shouldReturnDistinctValues_...` / `hasSize(1000)`, added a same-millisecond back-to-back strict-ordering test
- Falsification (Task 2): temporarily dropped the monotonic carry, confirmed exactly the 5 uniqueness/ordering-dependent assertions across both test classes went RED (the other 5 stayed green); restored, confirmed all 10 GREEN with an empty `git diff -- src/main`
- Full suite: 451 tests, 0 failures, 0 errors (net +7 over the prior 444-test baseline), `spotlessCheck` clean
- Source todo closed with a Resolution quoting the recomputed constants; dependent `ActivityLogRecorder` todo reconciled and also closed (its generator-collision premise no longer exists; its unaffected residue -- genuine Kafka redelivery dedupe -- was never broken)

## Task Commits

Each task was committed atomically:

1. **Task 1: Monotonic shared sequence, end-to-end, proven across threads and instances** - `4ddcb69` (feat)
2. **Task 2: Lock in the layout invariants, tighten EventIdGeneratorTest, prove the tightening has teeth** - `61b0279` (test)
3. **Task 3: Full suite, reconcile the dependent todo, close the source todo** - `9a23a3a` (docs)

_Note: Task 2's RED-phase falsification run was performed in-place and reverted before its own commit -- `.githooks/pre-commit` refuses a commit while the falsification edit is in place, and `--no-verify` is forbidden. Its RED/GREEN evidence is recorded in this SUMMARY and the todo Resolution, matching quick task 260813-ncx's established pattern._

## Recomputed Constants (Task 2, per the plan's explicit instruction to recompute rather than copy)

Computed with a throwaway Node.js `BigInt` script (not committed; exact source and output recorded here):

| Constant | Plan's illustrative figure | Recomputed value | Match? |
|---|---|---|---|
| `CUSTOM_EPOCH` (2018-01-01T00:00:00Z) | 1514764800000 | 1514764800000 | Exact match |
| 63-bit exhaustion date | 2087-09-07 | 2087-09-07T15:47:35.551Z | Exact match |
| Base36 width 12->13 transition | 2053-10-19 | 2053-10-19T10:35:45.924Z | Exact match |
| Legacy-layout ceiling (used as frozen constant) | 1058897343283200000 | **1058897343291588607** | **Disagrees -- recomputation wins** |

The legacy-ceiling disagreement: the plan's figure is `(deltaOldMax << 23)` with the random low bits implicitly zero -- the *minimum* value at the maximum legacy timestamp, not the *maximum* value the legacy layout could produce. The recomputed value additionally ORs in the legacy layout's maximum possible random contribution (`2^23 - 1 = 8388607`), which is what "the largest value the old layout could produce" actually requires for the assertion to be a genuine ceiling rather than one with an ~8.4-million-wide gap near the boundary. `RandFlakeGeneratorTest.shouldDecodeAboveLegacyLayoutCeiling_whenGeneratedFreshly` asserts against the recomputed (higher) value. A fresh id generated today exceeds it by a margin of `81416982347382785` (verified).

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java` - Rewrote bit-packing and state: `SEQUENCE_BITS=22`, `CUSTOM_EPOCH=1514764800000L` (2018-01-01), new `static final AtomicLong LAST_ID`, `updateAndGet` CAS loop replacing `ThreadLocalRandom`; replaced the "deliberately not synchronized" comment block per the plan's explicit requirement
- `src/test/java/com/vrudenko/kanban_board/config/RandFlakeGeneratorTest.java` - New file, 6 tests, plain JUnit (no Spring context), matching `dto/OptionalNotBlankTest`'s precedent
- `src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java` - Renamed `shouldReturnOverwhelminglyDistinctValues_...` back to `shouldReturnDistinctValues_...`, `hasSizeGreaterThanOrEqualTo(993)` -> `hasSize(1000)`, deleted `MIN_DISTINCT_IDS`, added a same-millisecond back-to-back ordering test, rewrote Javadoc from probabilistic derivation to structural guarantee
- `.planning/todos/completed/2026-08-13-add-monotonic-sequence-counter-to-randflakegenerator-fix-205.md` - Moved from `pending/`, `## Resolution` appended
- `.planning/todos/completed/2026-08-13-activitylogrecorder-silently-drops-events-on-eventid-collision.md` - Moved from `pending/`, `## Resolution` appended (closed rather than left open -- see Decisions Made)

## Decisions Made

- **`LAST_ID` is `static`, not an instance field.** Hibernate builds one `RandFlakeGenerator` per `@RandFlakeId` mapping and `EventIdGenerator` builds its own with `new RandFlakeGenerator()`; a non-static counter would give each instance its own sequence, converting the old design's probabilistic collision into a deterministic one across instances. This is a finding this plan's own measurement surfaced, not anticipated by the source todo's "matching the existing philosophy" phrasing if taken to mean instance-scoped state.
- **`CUSTOM_EPOCH` moved to 2018-01-01, not left unchanged.** The source todo's "no migration concern" claim was only half true: base36 width and persisted-id validity are unaffected, but ordering across the deploy boundary is not free. Left at 2023-01-01, narrowing the low field alone would invert `@OrderBy("id")` on three entity collections until 2030-03-26 (measured). The epoch move avoids this by keeping every new id above the legacy layout's maximum possible output.
- **Frozen legacy-ceiling constant recomputed higher than the plan's illustrative figure** (see table above) -- the plan's own instruction that "if a recomputed value disagrees with the plan, the recomputation wins" was applied, and the discrepancy is documented rather than silently corrected.
- **`ActivityLogRecorder`'s dependent todo closed, not left open.** Its three candidate solutions were all framed around a generator-collision source that no longer exists; its one genuinely unaffected residue (Kafka redelivery dedupe via `existsByEventId`) was never actually broken by the original filing, so there was no remaining action item to track.

## Deviations from Plan

None -- plan executed exactly as written, including the Task 1 tracer-feedback pattern (implement end-to-end, then the falsification/full-suite validation in Tasks 2-3) and the explicit instruction to recompute constants rather than trust the plan's illustrative numbers, which surfaced one genuine (and now-documented) discrepancy.

## Issues Encountered

- Two `git commit` invocations (Task 1, Task 2) exceeded the harness's default 2-minute Bash timeout while `.githooks/pre-commit`'s `fastTest` was still running (the pre-commit test suite legitimately takes 3-4+ minutes on this machine). Resolved per `docs/SESSION_LESSONS.md`: ran `./gradlew --stop` to release the orphaned daemon before retrying each commit with a longer timeout; both retries succeeded cleanly with no code or evidence affected.
- `jacocoTestCoverageVerification` failed on every `--tests '*Filter*'`-scoped run (expected: coverage ratios computed against the whole codebase but only a test subset ran) -- not a real failure, confirmed by inspecting the per-class XML result files directly for pass/fail counts each time, and by the full unscoped `./gradlew spotlessCheck test` run in Task 3 passing `jacocoTestCoverageVerification` cleanly.

## Next Phase Readiness

- `RandFlakeGenerator` now provides deterministic same-millisecond uniqueness for every `@RandFlakeId` entity primary key and every `EventIdGenerator`-issued `event_id`, closing both the collision risk `260813-ncx` measured and the 2058 sign-overflow bug in one change.
- No outstanding todo remains from this line of investigation: both the source todo and its dependent `ActivityLogRecorder` todo are closed.
- The uniqueness guarantee is explicitly per-JVM (documented in the class comment and the threat model's T-os9-04) -- a future multi-instance deployment would need a machine-id field added to the layout; not needed today (`docker-compose.prod.yml` is single-instance).

---
*Phase: quick-260813-os9*
*Completed: 2026-08-13*

## Self-Check: PASSED

All claimed files confirmed present on disk (`RandFlakeGenerator.java`, `RandFlakeGeneratorTest.java`,
`EventIdGeneratorTest.java`, both completed todos, this SUMMARY) and confirmed absent from
`.planning/todos/pending/` where claimed moved. All three commit hashes (`4ddcb69`, `61b0279`,
`9a23a3a`) confirmed present in `git log --oneline --all`.
