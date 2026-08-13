# RandFlake collision probe — findings

Quick task 260813-ncx. Source: `RandFlakeCollisionProbeTest.java` (throwaway, deleted before Task
1's own commit — never entered git history). Raw output: `PROBE-RAW.txt` in this directory,
produced by `./gradlew test --tests '*RandFlakeCollisionProbeTest*'` against `build/probe/randflake-probe-raw.txt`.
Bit layout confirmed against `RandFlakeGenerator.java` before decoding: `RANDOM_BITS = 23`,
`CUSTOM_EPOCH = 1672531200000L`, id = `(timestamp << 23) | random23Bits`, rendered `Long.toString(id, 36)`.

## Q1 — the raw rate

T = 200 trials of 1000 calls each. Raw file lines `=== Q1 aggregate ===` (PROBE-RAW.txt:222-228):

```
T=200
collidingTrials(C)=13
totalDuplicatePairs=14
minDistinct=998
maxDistinct=1000
meanDistinct=999.93
```

13 of 200 trials (6.5%) came up short of 1000 distinct values; the worst single trial lost 2 (trial
108, `distinct=998`). This matches the reported symptom (three occurrences in a session's full-suite
runs, each exactly one duplicate) — a colliding trial roughly 1 run in 15-17, consistent with the
context block's back-of-envelope estimate.

## Q2 — is the rate what the design predicts?

Every id was decoded (`Long.parseLong(id, 36)`, `timestamp = value >>> 23`, `random = value &
0x7FFFFF`) and bucketed by decoded millisecond. The per-trial birthday prediction was computed from
the *observed* bucket sizes for that trial and summed into `E`. Raw file `=== Q2 observed vs
predicted ===` (PROBE-RAW.txt:230-237):

```
C=13
E=10.63182667873134
sqrt(E)=3.260648199167052
E+3*sqrt(E)=20.413771276232495
C<=E+3*sqrt(E) : true
C>=2*E : false
NOTE: E is a property of THIS machine's loop speed via the observed per-trial bucket sizes (avg
buckets/trial=1.265), not a universal constant. A different machine will observe a different E for
the same verdict.
```

`C = 13 <= E + 3*sqrt(E) = 20.41` — the observed colliding-trial count is comfortably inside the
statistical envelope the design predicts from the buckets actually measured. `C >= 2*E` is false.
Neither `GENERATOR_DEFECT` numeric condition in D-05 is met.

Because this machine's loop is fast (most trials landed all 1000 calls inside a single decoded
millisecond — `avg buckets/trial=1.265`), the dominant per-trial collision probability is close to
the single-bucket birthday figure `p = 1 - exp(-1000*999/(2*8388608)) ≈ 0.05781`, which appears
verbatim throughout PROBE-RAW.txt on every `buckets=1` line.

## Q3 — does the decode validate, and is the random component structureless?

Raw file `=== Q3 decode validity and randomness structure ===` (PROBE-RAW.txt:239-249):

```
decodeFailures=0
nonDecreasingFailures(timestamps went backwards within a trial)=0
windowFailures(decoded timestamp outside captured wall-clock window)=0
decodeValidityOverall=true
totalRandomDraws=200000
distinctRandomValuesSeen=197651
mostFrequentRandomValue=25772 count=3
meanOccupancyPerRandomValue(expected)=0.02384185791015625
consecutiveEqualDraws(observed)=0
consecutiveEqualDraws(expected, uniform)=0.02384185791015625
```

Decode validates on every one of the 200 trials: 0 decode failures, decoded timestamps
non-decreasing within every trial, and every trial's decoded min/max timestamp fell inside its
captured wall-clock window. Every duplicate-id pair recorded in PROBE-RAW.txt (e.g. line 18: `id
count=2 decodedTimestamp=114102241465 decodedRandom=3173523`) necessarily agrees on both decoded
halves — equal Base36 strings decode to equal longs, so this is the decode-validity check the plan
calls it, not a discriminator.

The randomness-structure check found nothing anomalous: `mostFrequentRandomValue` was drawn 3
times out of 200,000 draws over an 8,388,608-value space (mean occupancy 0.0238). Modeling each of
the 8,388,608 slots as Poisson(mean=0.0238), the *expected number of slots* reaching count>=3 across
all slots is `8,388,608 * P(X>=3 | λ=0.0238) ≈ 8,388,608 * 1.87e-6 ≈ 15.7` — so observing one slot
at count 3 is unremarkable, not a sign of correlated draws. `consecutiveEqualDraws` was 0 against an
expected value of 0.024 — also unremarkable at this sample size. Neither finding indicates a
`ThreadLocalRandom` re-seeding or shared-state defect.

## Q4 — the production-rate figure

Raw file `=== Q4 ===` sections (PROBE-RAW.txt:251-263):

```
q4Trials=20000
q4CallsPerTrial=20
q4CollidingTrials=3
q4CollidingRate=1.5E-4
```

At a production-representative call count (20 back-to-back calls, a generous upper bound for one
HTTP mutation), 3 of 20,000 trials collided — a rate of 1.5e-4, roughly 100x rarer than the 1000-call
tight-loop rate, because fewer draws per burst means a quadratically smaller birthday probability.

```
avgBucketSizeObservedInQ1Q2(calls per distinct millisecond)=790.5138339920949
numBucketsAt1MEventsAtSameClustering=1265.0
expectedPairsPerBucket=k*(k-1)/(2*8388608)=0.037200546735458924
expectedCollidingPairsAcross1MLifetimeEvents=1265.0 * 0.037200546735458924 = 47.05869162035554
```

This second figure (~47 expected colliding pairs across 1,000,000 lifetime events) is a
deliberate **worst case**: it reuses the tight-loop's observed same-millisecond clustering
(average ~790 calls per distinct millisecond) as the burst size, even though real production
traffic — `eventIdGenerator.generate()` has ~14 call sites, one per user mutation, nothing
resembling a tight loop — is far sparser in time than a synthetic 1000-call-per-loop-iteration
benchmark. The Q4 direct measurement (1.5e-4 collision rate at 20 calls/burst) is the more
realistic figure for actual request bursts; the 47-pairs-per-1M figure is the pessimistic ceiling
if production traffic somehow clustered as tightly as this probe's loop.

## Observed vs predicted

`C = 13 <= E + 3*sqrt(E) = 20.41` — holds. `C >= 2*E` (13 >= 21.26) — does not hold. Decode
validates on every trial. No random-value or consecutive-draw anomaly beyond what a uniform draw
explains. All four `INHERENT_BIRTHDAY` criteria in D-05 are met; none of the `GENERATOR_DEFECT`
criteria are met.

## What this means for the test

`RandFlakeGenerator`'s existing same-millisecond comment (lines 25-32) — "the low bits are random,
not a sequence counter, so same-millisecond collisions were always possible at the same
probability" — is confirmed by measurement, not merely restated: the observed colliding-trial rate
(13/200 = 6.5%) sits inside the statistical envelope predicted from this machine's own observed
per-millisecond clustering.

`EventIdGeneratorTest`'s third test's Javadoc (lines 56-68) is also confirmed: it already says an
exact-distinctness assertion on ids generated in the same millisecond "would be flaky by
construction," and that is exactly what
`shouldReturnDistinctValues_whenCalledManyTimesRapidly`'s `hasSize(callCount)` assertion is. The
class's own third test explains why the second test's assertion cannot hold; this measurement
supplies the missing number.

## Production exposure

`ActivityLogRecorder.persist` short-circuits at `existsByEventId(entry.getEventId())`
(`ActivityLogRecorder.java:46`, re-checked in its catch block at line 58), backed by the
`uk_activity_log_event_id` unique constraint. A genuine `eventId` collision is therefore silently
treated as a redelivery: the second event is dropped with no exception and no dead letter, and one
activity-log row is lost instead of two.

At production scale this task's Q4 measurement gives two figures: a direct realistic-burst rate
(1.5e-4 collisions per 20-call burst, the generous per-request upper bound) and a deliberate
worst-case lifetime figure (~47 expected colliding pairs across 1,000,000 lifetime events, assuming
production traffic clustered as tightly in time as this probe's tight loop, which it structurally
cannot — 14 call sites, one per user mutation). Even the worst-case figure (~47 per 1,000,000
lifetime events) is above the 0.01-pairs bar D-08 sets for filing a new todo, so a todo naming
`ActivityLogRecorder.persist`'s `existsByEventId` short-circuit, the `uk_activity_log_event_id`
constraint, and this measured figure is filed in Task 2, per D-08.

## Candidate MIN_DISTINCT_IDS thresholds (for Task 2, D-06)

Both D-06 floors computed from this trial's data:

- **Floor A (false-failure probability):** modeling per-trial shortfall `S` as Poisson with the
  worst observed single-bucket rate `μ = 1000*999/(2*8388608) ≈ 0.059564` (the *most*
  collision-prone case, when all 1000 calls land in one decoded millisecond — the dominant case in
  this run, `avg buckets/trial=1.265`), `P(distinct < MIN_DISTINCT_IDS) = P(S >= 1001 -
  MIN_DISTINCT_IDS)` must be `< 1e-9`.
- **Floor B (detection power):** `MIN_DISTINCT_IDS` must exceed `50 * stubDistinctCount`, where
  `stubDistinctCount` is the distinct count an entropy-free (constant-random) delegate would
  produce — bounded by the number of distinct milliseconds a 1000-call trial spans. The maximum
  `buckets` value observed anywhere in the 200 trials (PROBE-RAW.txt, `buckets=` column) is **4**
  (trial 0), so `stubDistinctCount <= 4` on this machine and floor B requires `MIN_DISTINCT_IDS >
  200`.

| MIN_DISTINCT_IDS | `1001-MIN` | `P(distinct < MIN)` (Poisson tail, μ=0.059564) | Floor A (< 1e-9) | Floor B (> 200) |
|---:|---:|---:|:---:|:---:|
| 990 | 11 | ≈ 1.5e-19 | pass | pass |
| 992 | 9  | ≈ 1.6e-16 | pass | pass |
| 993 | 8  | ≈ 3.7e-15 | pass | pass |
| 995 | 6  | ≈ 5.9e-11 | pass (thin margin) | pass |
| 996 | 5  | ≈ 5.9e-9  | **fail** | pass |

Row 990 is the plan's own illustrative value and is deliberately not adopted (D-06 explicitly
forbids copying it). Row 996 fails floor A and is excluded. Of the remaining rows, **993** is
selected for Task 2: it clears floor A with three orders of magnitude of margin below the 1e-9
bar (~3.7e-15, not sitting on the boundary the way 995 does), clears floor B by roughly 2.5x, and
is the tightest (highest-detection-power) value with that margin — i.e., the smallest
`MIN_DISTINCT_IDS` that isn't uncomfortably close to the floor A cliff edge.

VERDICT: INHERENT_BIRTHDAY
