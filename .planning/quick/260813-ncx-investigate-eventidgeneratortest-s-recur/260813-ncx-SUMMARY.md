---
phase: quick-260813-ncx
plan: 01
subsystem: testing
tags: [randflake, event-id, birthday-collision, flaky-test, activity-log]

requires: []
provides:
  - "Measured (not reasoned) verdict on EventIdGeneratorTest's recurring uniqueness flake: INHERENT_BIRTHDAY"
  - "Measurement-derived MIN_DISTINCT_IDS=993 threshold replacing an overstated exact-distinctness assertion"
  - "Falsification proof the relaxed assertion still has teeth"
  - "Quantified ActivityLogRecorder silent-drop-on-collision production exposure, filed as a new todo"
affects: [testing, activity-log, backend-modernization-epic-2]

actuals:
  tokens: 42000
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Throwaway plain-JUnit probe (never committed) calling real production classes to settle a flaky-test verdict by measurement instead of code-reading + reasoning"
    - "Measurement-derived test threshold with two computed floors (false-failure probability < 1e-9, >50x an entropy-free-delegate baseline) plus a falsification gate proving the threshold isn't a suppressed assertion"

key-files:
  created:
    - .planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-RAW.txt
    - .planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-FINDINGS.md
    - .planning/todos/pending/2026-08-13-activitylogrecorder-silently-drops-events-on-eventid-collision.md
  modified:
    - src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java
    - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
    - .planning/todos/completed/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md

key-decisions:
  - "VERDICT: INHERENT_BIRTHDAY, from T=200 trials of 1000 rapid calls: C=13 colliding trials vs birthday prediction E=10.63 (E+3*sqrt(E)=20.41, C<=that holds; C>=2*E does not), decode validates on every trial, no randomness anomaly beyond chance"
  - "Fixed the test, not the generator: renamed shouldReturnDistinctValues_whenCalledManyTimesRapidly to shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly, replaced hasSize(1000) with hasSizeGreaterThanOrEqualTo(MIN_DISTINCT_IDS=993)"
  - "MIN_DISTINCT_IDS=993 chosen from a 5-row candidate table (990/992/993/995/996), not copied from the plan's illustrative 990 value: P(distinct<993)~=3.7e-15 gives 3 orders of magnitude of margin below the 1e-9 floor, avoiding the near-boundary risk of 995/996"
  - "Falsification (D-07) proved the relaxed assertion still has teeth: constant random draw reds exactly this assertion (other 2 tests in the class stayed green); restoring greens it with an empty git diff -- src/main"
  - "D-08 production exposure: worst-case ~47 expected colliding ActivityLogRecorder pairs per 1,000,000 lifetime events (pessimistic extrapolation) vs a realistic 1.5e-4 collision rate per 20-call burst; both clear the 0.01-pairs filing bar, so a new todo was filed rather than fixed here"

patterns-established:
  - "Measurement-first verdict protocol for flaky-test triage: a throwaway, never-committed probe over the real production code path answers the inherent-vs-defect question with a stated numeric criterion before any test or production code changes"

requirements-completed: [QUICK-260813-NCX-EVENTIDUNIQUENESSFLAKE]

coverage:
  - id: D1
    description: "Verdict on the 999/1000 flake decided by measurement (T>=200 trials) rather than reasoning, recorded as evidence"
    requirement: QUICK-260813-NCX-EVENTIDUNIQUENESSFLAKE
    verification:
      - kind: other
        ref: ".planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-RAW.txt + PROBE-FINDINGS.md (VERDICT: INHERENT_BIRTHDAY line, T=200/C=13/E=10.63)"
        status: pass
    human_judgment: false
  - id: D2
    description: "EventIdGeneratorTest's overstated exact-distinctness assertion corrected to a measurement-derived, still-effective threshold"
    requirement: QUICK-260813-NCX-EVENTIDUNIQUENESSFLAKE
    verification:
      - kind: unit
        ref: "src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java#GenerateTest.shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly"
        status: pass
    human_judgment: false
  - id: D3
    description: "Falsification proves the relaxed assertion retains detection power (goes RED when generator entropy is removed)"
    verification:
      - kind: manual_procedural
        ref: "Temporarily replaced ThreadLocalRandom draw with a constant, ran ./gradlew test --tests '*EventIdGeneratorTest*': tests=3 failures=1 (exactly the target assertion); restored, tests=3 failures=0; git diff -- src/main empty at that point"
        status: pass
    human_judgment: false
  - id: D4
    description: "ActivityLogRecorder's silent event-drop-on-collision production consequence quantified and filed"
    requirement: QUICK-260813-NCX-EVENTIDUNIQUENESSFLAKE
    verification:
      - kind: other
        ref: ".planning/todos/pending/2026-08-13-activitylogrecorder-silently-drops-events-on-eventid-collision.md"
        status: pass
    human_judgment: false
  - id: D5
    description: "Source todo closed with a Resolution quoting the measurements, not the theory"
    requirement: QUICK-260813-NCX-EVENTIDUNIQUENESSFLAKE
    verification:
      - kind: other
        ref: ".planning/todos/completed/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md#Resolution"
        status: pass
    human_judgment: false
  - id: D6
    description: "Full suite green on the shipped tree, three consecutive target-test reruns green"
    verification:
      - kind: unit
        ref: "./gradlew spotlessCheck test (BUILD SUCCESSFUL, 188/188 test-result XML files with failures=0 errors=0)"
        status: pass
      - kind: unit
        ref: "3x ./gradlew test --tests '*EventIdGeneratorTest*' --rerun-tasks, all tests=3 failures=0"
        status: pass
    human_judgment: false

duration: 35min
completed: 2026-08-13
status: complete
---

# Quick Task 260813-ncx: RandFlake collision flake investigation Summary

**Measured (not reasoned) the EventIdGeneratorTest 999/1000 flake as INHERENT_BIRTHDAY, replaced the overstated exact-distinctness assertion with a falsification-proven MIN_DISTINCT_IDS=993 threshold, and filed the quantified ActivityLogRecorder silent-drop exposure as a new todo.**

## Performance

- **Duration:** ~35 min
- **Started:** 2026-08-13T15:04:00Z (approx, first probe write)
- **Completed:** 2026-08-13T15:33:02Z
- **Tasks:** 2
- **Files modified:** 6 (2 evidence files created, 2 source files edited, 1 todo created, 1 todo moved+resolved)

## Accomplishments

- Wrote and ran a throwaway plain-JUnit probe (`RandFlakeCollisionProbeTest`, never entered git history) that called the real `EventIdGenerator`/`RandFlakeGenerator` chain directly, measuring T=200 trials of 1000 rapid calls plus a Q4 production-rate measurement (20000 trials of 20 calls)
- Verdict: **INHERENT_BIRTHDAY** — C=13 colliding trials vs birthday prediction E=10.63 (`C<=E+3*sqrt(E)=20.41` holds; `C>=2*E` does not), decode validated on every trial, no randomness anomaly beyond what a uniform 23-bit draw explains
- Corrected `EventIdGeneratorTest`: renamed the overstated method, replaced `hasSize(1000)` with a measurement-derived `hasSizeGreaterThanOrEqualTo(MIN_DISTINCT_IDS=993)` chosen from a computed candidate-threshold table (not copied from the plan's illustrative 990), documented with a full Javadoc derivation
- Proved by falsification (D-07) that the relaxed assertion still catches a real regression: replacing the random draw with a constant reds the assertion; restoring it greens with zero `src/main` diff
- Appended the measured collision rate to `RandFlakeGenerator`'s existing same-millisecond comment, comment-only, zero behavioural change
- Quantified `ActivityLogRecorder`'s silent event-drop-on-collision exposure (~47 expected colliding pairs per 1,000,000 lifetime events, worst case; 1.5e-4 realistic per-burst rate) and filed a new `[minor]` todo rather than fixing the dedupe strategy
- Closed the source todo with a `## Resolution` quoting the measurements

## Task Commits

Each task was committed atomically:

1. **Task 1: Measure the actual collision behaviour and record a verdict** - `8c52adc` (test)
2. **Task 2: Fix whichever artefact the measurement convicted, prove the teeth, close the todo** - `d4c117b` (fix)

_Note: The RED-phase falsification run (D-07) was performed in-place and reverted before Task 2's own commit — it deliberately does not appear as a separate commit (`.githooks/pre-commit` refuses a commit while the falsification constant is in place, and `--no-verify` is forbidden); its RED/GREEN evidence is recorded in this SUMMARY and the todo Resolution._

## Files Created/Modified

- `.planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-RAW.txt` - Raw per-trial probe output (T=200 main measurement + Q4 production-rate measurement)
- `.planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-FINDINGS.md` - Interpretation, observed-vs-predicted comparison, candidate-threshold table, VERDICT line
- `src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java` - Renamed method, measurement-derived `MIN_DISTINCT_IDS` threshold, corrected class Javadoc
- `src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java` - Comment-only addition of the measured collision rate
- `.planning/todos/completed/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md` - Moved from pending/, `## Resolution` appended
- `.planning/todos/pending/2026-08-13-activitylogrecorder-silently-drops-events-on-eventid-collision.md` - New todo, D-08

## Decisions Made

- **INHERENT_BIRTHDAY verdict, D-05's numeric criteria applied strictly:** `C=13 <= E+3*sqrt(E)=20.41` holds; `C>=2*E` (13>=21.26) does not hold; decode validated on all 200 trials (0 decode failures, timestamps non-decreasing, every value inside its wall-clock window); randomness structure unremarkable (max repeat count 3 in 200,000 draws over 8,388,608 slots, consistent with ~15.7 expected such repeats by chance alone).
- **MIN_DISTINCT_IDS=993, not the plan's illustrative 990:** chosen from a 5-row candidate table modeling per-trial shortfall as Poisson(mu=0.059564, the worst observed single-bucket rate). 993 clears the false-failure floor with `P(distinct<993)~=3.7e-15` (three orders of magnitude of margin below 1e-9), and clears the detection-power floor (>50x the max entropy-free-delegate distinct count observed, 4).
- **D-08 exposure filed, not fixed:** both the pessimistic worst-case figure (~47 pairs/1M lifetime events) and the realistic per-burst figure (1.5e-4/20-call burst) exceed the 0.01-pairs bar, so a new todo names `ActivityLogRecorder.persist`'s `existsByEventId` short-circuit and the `uk_activity_log_event_id` constraint rather than changing the dedupe strategy in this quick task (a behavioural change to every entity id/event_id row).

## Deviations from Plan

None — plan executed exactly as written, including the Task 1 tracer-feedback pattern (D-01 measure-first, evidence-only commit before any Task 2 edit) and Task 2's INHERENT_BIRTHDAY branch in full (rename, threshold, Javadoc, falsification, comment-only generator edit, D-08 filing, todo closure).

## Issues Encountered

- A prior `git commit` attempt was killed by the harness's 2-minute Bash timeout while `.githooks/pre-commit`'s `fastTest` was still running (the full pre-commit test suite legitimately takes longer than 2 minutes on this machine after a prior full-suite run). The killed process left an orphaned Gradle daemon holding `build/test-results/fastTest/binary/output.bin` open, which then made a retried commit fail with `IOException: Unable to delete directory`. Resolved with `./gradlew --stop` to release the stale daemon, then a clean retry succeeded (`d4c117b`) — no code or evidence content was affected by the retry.

## Next Phase Readiness

- The flake is resolved: `EventIdGeneratorTest` no longer asserts a guarantee the generator does not make, and the pre-commit `fastTest` gate (which this flake previously refused at ~1-in-15 to 1-in-17) should no longer intermittently block commits on this test.
- A new `[minor]` todo tracks `ActivityLogRecorder`'s silent event-drop-on-collision exposure for future prioritization; no blocker for current work.
- `ActivityReadTest:249`'s `doesNotHaveDuplicates()` assertion carries the same theoretical exposure at much lower volume — named in the closed todo's Resolution and the new todo, deliberately not touched here.

---
*Phase: quick-260813-ncx*
*Completed: 2026-08-13*

## Self-Check: PASSED

All claimed files confirmed present on disk (`PROBE-RAW.txt`, `PROBE-FINDINGS.md`,
`EventIdGeneratorTest.java`, `RandFlakeGenerator.java`, the completed todo, the new pending todo)
and confirmed absent where claimed absent (the source todo no longer exists in `pending/`). Both
commit hashes (`8c52adc`, `d4c117b`) confirmed present in `git log --oneline --all`.
