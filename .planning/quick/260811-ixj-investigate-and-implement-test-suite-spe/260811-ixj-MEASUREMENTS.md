# Quick Task 260811-ixj: Test-suite speed measurements

Every timing below follows the plan's measurement protocol: `./gradlew <task> --rerun-tasks
--console=plain`, Gradle's own `BUILD SUCCESSFUL in Xm Ys` line, test count read from
`build/test-results/<task>/TEST-*.xml` via
`grep -ho 'tests="[0-9]*"' ... | cut -d'"' -f2 | awk '{s+=$1} END {print s}'`. Runs are back-to-back
in one session, no `clean` between them, no other heavy work concurrent.

## Machine facts (reproducibility)

- Docker: `8 CPUs, 8298041344 bytes` (≈ 7.728 GiB) — `docker info --format '{{.NCPU}} CPUs,
  {{.MemTotal}} bytes'`
- Git SHA baseline was measured at: `38541942b43ed32c48044a0913830772d8d3d7ce`

## Baseline (unchanged tree)

| Task | Run | Duration | Test count |
|---|---|---|---|
| `test` | 1 | 7m 20s (440s) | 385 |
| `test` | 2 | 7m 7s (427s) | 385 |
| `fastTest` | 1 | 5m 34s (334s) | 348 |
| `fastTest` | 2 | 5m 41s (341s) | 348 |

Both `test` runs and both `fastTest` runs agree on test count (385 and 348 respectively), and the
13s (`test`) / 7s (`fastTest`) run-to-run spread is well inside this project's documented ~18s
variance (`docs/LOCAL_DEV.md`: 232s/224s/242s for a prior `fastTest` series).

## After lever 1 (test-profile BCrypt cost factor 10 -> 4)

`BeanConfiguration.passwordEncoder()` now takes an injectable `@Value("${security.bcrypt.strength:10}")
int strength`; `application-test.properties` sets it to 4. Both `PasswordEncoderStrengthTest` (3
new test methods, RED-before-GREEN — see below) and the pre-existing suite ran; test counts below
are 3 higher than baseline for exactly that reason (385+3=388, 348+3=351), not shrinkage.

| Task | Run | Duration | Test count |
|---|---|---|---|
| `test` | 1 | 6m 2s (362s) | 388 |
| `test` | 2 | 6m 19s (379s) | 388 |
| `fastTest` | 1 | 4m 47s (287s) | 351 |
| `fastTest` | 2 | 4m 43s (283s) | 351 |

### Delta

| Task | Baseline avg | After avg | Delta | % change |
|---|---|---|---|---|
| `test` | 433.5s ((440+427)/2) | 370.5s ((362+379)/2) | -63.0s | -14.5% |
| `fastTest` | 337.5s ((334+341)/2) | 285.0s ((287+283)/2) | -52.5s | -15.6% |

**Read against RESEARCH.md's extrapolation, not just the measured numbers on their own:** the
research doc's arithmetic projection (83.7ms->1.8ms/encode x 3 encodes x 318 fixture-bearing test
methods) predicted ~78s / ~32% off the full suite. The real, measured win is smaller — ~63s / 14.5%
on `test`, ~52.5s / 15.6% on `fastTest`. The gap is expected and stated here rather than glossed:
wall-clock includes Gradle daemon startup, `compileJava`/`compileTestJava`, the PostgreSQL
container start, and Spring context boot for every one of the ~29 fixture-bearing test classes —
none of which lever 1 touches, and all of which the RESEARCH.md's "summed test-execution time"
figure (241.5s) excluded by construction. Summed BCrypt cost reduction is a real, large fraction of
*test-execution* time; it is a smaller fraction of *wall-clock* time, which is what a developer
actually waits on locally and what these tables report.

### Verification of the three named classes (Task 1 step 4)

- `PasswordEncoderStrengthTest` — new. Written RED first: run before any `BeanConfiguration`/
  `application-test.properties` edit, it failed for the right reasons —
  `TestProfileCostFactor.shouldEncodeAtCostFactorFour_whenUsingTheAutowiredBean` failed with
  `AssertionFailedError` (cost segment was "10", not "04" — the bean still used the un-parameterized
  constructor); `ProductionFallback.shouldFallBackToTen_whenNoOverrideIsConfigured` failed with
  `NoSuchMethodException` (no `passwordEncoder(int)` overload existed yet). After the GREEN edit, all
  3 methods pass.
- `AuthenticationTest` — unmodified, all methods green under strength 4. `BCRYPT_HASH_MARKER =
  "$2a$"` (the algorithm prefix) is satisfied by `$2a$04$...` exactly as it was by `$2a$10$...`.
- `SigninTimingEqualizationTest` — unmodified, all methods green under strength 4. Its assertions
  (`countingPasswordEncoder.matchesInvocationCount()).isEqualTo(1)`) are call-count assertions, not
  elapsed-time assertions — confirmed by reading the class before relying on RESEARCH.md's Assumption
  A1, not merely assuming it. A cheaper cost factor changes what the one counted `matches()` call
  costs, not the count itself, so this class needed no change.

## Lever 2: maxParallelForks

Measured on `fastTest` after lever 1 landed (measuring before would have answered a question about
a tree that no longer exists — the optimal fork count differs once BCrypt's CPU-bound cost is
removed). `forkEvery` was never set (default 0 -- one JVM handles every class assigned to a fork).

### fastTest at 2 forks

| Run | Duration | Test count | Live container census (mid-run) |
|---|---|---|---|
| 1 | 3m 53s (233s) | 351 | 2x `postgres:16`, 1x `redpanda` (2 samples, both showed 1 Redpanda -- `HistoricalActivityEventReconstructorTest` is untagged, see the filed todo) |
| 2 | 4m 12s (252s) | 351 | not re-censused (same task, same tag filter as run 1) |

No run failed on container startup, port exhaustion, or memory.

### fastTest at 4 forks

| Run | Duration | Test count | Live container census (mid-run) |
|---|---|---|---|
| 1 | 4m 37s (277s) | 351 | 4x `postgres:16` (one per fork, confirmed), 1x `redpanda` observed live |
| 2 | 4m 17s (257s) | 351 | not re-censused |

No run failed on container startup, port exhaustion, or memory -- 4 forks against this machine's
7.728 GiB Docker budget did not exhaust it, it was simply slower than 2 forks.

### Decision: adopt maxParallelForks = 2 on both `fastTest` and `test`

Baseline (1 fork, after lever 1) averages: `fastTest` 285.0s, `test` 370.5s.

| Fork count | `fastTest` avg | Beats 1-fork avg by (both runs individually) | Adopt? |
|---|---|---|---|
| 1 (baseline) | 285.0s | -- | -- |
| 2 | 242.5s (233s, 252s) | 52.0s and 33.0s -- both > 18s variance | **YES** |
| 4 | 267.0s (277s, 257s) | 8.0s and 28.0s -- only ONE run > 18s variance | NO |

4 forks is both slower than 2 forks on average (267.0s vs 242.5s) and fails the plan's "beats
variance on both of its runs" bar (its first run's 8s margin is inside the ~18s variance window),
so it is not adopted. 2 forks clears that bar decisively on both runs.

`test` was then measured at 2 forks (not at 4 -- 2 already won decisively on `fastTest`, and `test`
additionally runs the full Kafka tier, a different container-memory profile the plan calls out
explicitly, so re-testing 4 there would only widen an already-settled gap):

| Task | Run | Duration | Test count |
|---|---|---|---|
| `test` @ 2 forks | 1 | 4m 43s (283s) | 388 |
| `test` @ 2 forks | 2 | 4m 30s (270s) | 388 |

`test` @ 2 forks averages 276.5s against the 1-fork baseline of 370.5s -- both runs beat it by 87.5s
and 100.5s respectively, both far past the variance bar. Container census mid-run showed exactly 2
`postgres:16` containers (one per fork); no Redpanda container was observed in either sample taken,
though `test`'s Kafka-tagged classes are spread across the run and a sample can miss a
short-lived container. No run failed.

**Decision: `maxParallelForks = 2` is committed on both `fastTest` and `test` in `build.gradle`**,
with the measured numbers and the reason fork-level parallelism is safe here (each fork is a
separate JVM, therefore a separate static Testcontainers Postgres instance, therefore an
independent database -- D-02's `@AfterEach`-deletion isolation model is untouched) recorded as
comments above each `maxParallelForks` assignment.
