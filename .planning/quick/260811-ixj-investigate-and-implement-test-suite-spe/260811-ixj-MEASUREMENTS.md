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
