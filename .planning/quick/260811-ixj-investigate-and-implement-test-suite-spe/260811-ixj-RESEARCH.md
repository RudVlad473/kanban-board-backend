# Quick Task 260811-ixj: Test suite speed / parallelization — Research

**Researched:** 2026-08-11
**Domain:** JVM test-suite wall-clock, JUnit 5 / Gradle parallelism, Spring Boot + Testcontainers fixture cost
**Confidence:** HIGH (the headline finding is measured on this machine, not inferred)

## Summary

The todo lists three candidate levers and asks that they be **measured before choosing**. They were.
The measurement reorders them decisively, and adds a fourth that nobody had listed.

**The suite is not slow because it is serial. It is slow because `AbstractAppTest.setup()` runs three
BCrypt hashes at cost factor 10 — 251 ms of pure key-stretching — before every one of 318 test
methods.** That is ~80 s of a 241.5 s total test-execution budget spent deliberately burning CPU, by
design of the algorithm. Parallelization would spread that cost across cores; lowering the
test-profile work factor deletes it.

The two parallelization levers the todo names split cleanly on safety, and the split is not a
judgement call — it is structural. Gradle's `maxParallelForks` forks **separate JVMs**, and this
repo starts its containers from **`static` initializers** (`AbstractPostgresContainerTest`), so each
fork gets its own PostgreSQL container, its own database, and its own Spring context cache. The D-02
`@AfterEach`-deletion isolation model is therefore untouched — within a fork, execution is still
strictly sequential. JUnit 5's in-JVM `parallel.enabled` shares all three, and the isolation model
does not survive contact with it (five independent blockers, §JUnit 5 below — the decisive one being
that `cleanup()` calls `userService.deleteAll()`, an unscoped global wipe).

**Primary recommendation:** Pull lever 1 (BCrypt work factor) now — it is a properties change plus a
one-line `src/main` edit, carries no concurrency risk, and no test asserts on the work factor. Pull
lever 2 (`maxParallelForks`) second, measured at 2 and 4, if lever 1's win is not enough. Do **not**
pull JUnit 5 in-JVM parallelism. Do **not** split into per-tier Gradle tasks — Gradle will not run
them in parallel in a single-project build, so that lever is strictly negative.

## Measurement

All figures below are read from `build/test-results/` XML produced by real runs already on disk
(`test/` at 2026-08-11 13:38, `fastTest/` at 11:58) — not re-run for this research, not estimated.

| Population | Tests | Summed test time | Mean/test |
|---|---|---|---|
| Full `test` task | 385 | **241.5 s** | 0.627 s |
| `fastTest` task | 348 | **201.2 s** | 0.578 s |
| Classes in the `AbstractAppTest` hierarchy | **318** | **188.4 s** | 0.593 s |
| All other classes | 67 | 52.8 s | 0.787 s |

[VERIFIED: build/test-results/test/*.xml, 175 files parsed]

The control group is what makes this conclusive. Four classes boot a **full Spring context against
the same PostgreSQL container** but extend `AbstractPostgresContainerTest` directly, so they pay no
fixture:

| Class | Tests | Mean/test |
|---|---|---|
| `FlywaySchemaProvenanceTest` | 12 | **0.008 s** |
| `SignupRequestDTOTest` | 8 | 0.006 s |
| `EventIdGeneratorTest` | 3 | 0.006 s |
| `CorsConfigTest` | 1 | 0.004 s |
| `KanbanBoardApplicationTests` | 1 | 0.003 s |

Same container, same Spring context caching, same JVM — **~0.005 s/test**. The fixture-bearing
classes floor out at **0.342 s** (`TaskServiceTest`'s cheapest method) and average 0.593 s. The
delta is ~70×, and it is entirely `@BeforeEach setup()` + `@AfterEach cleanup()`. Spring context
boot and container startup are *not* the problem; they are amortized to near zero already.

### What the fixture actually costs

`AbstractAppTest.setup()` [VERIFIED: src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppTest.java:103-185]
creates **31 entities** per test method — 3 users (`owningUser`, `noBoardsUser`, `foreignUser`),
4 boards, 9 columns, 8 tasks, 7 subtasks — each through a real service call with ownership
verification, then `cleanup()` cascades all of it away.

Three of those 31 are `createUser()` calls, and each one runs a BCrypt encode.
`BeanConfiguration.passwordEncoder()` returns a bare `new BCryptPasswordEncoder()`
[VERIFIED: src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java:19-21] — no
strength argument, so Spring Security's default of **10**, in tests exactly as in production.
Measured on this machine (8 CPU, JDK 21, `spring-security-crypto-6.5.0`):

```
strength=10  encode=  83.7 ms/op   matches=  77.9 ms/op
strength= 8  encode=  21.4 ms/op   matches=  18.3 ms/op
strength= 6  encode=   5.1 ms/op   matches=   4.9 ms/op
strength= 4  encode=   1.8 ms/op   matches=   1.3 ms/op
```

[VERIFIED: measured this session, scratchpad `BcryptBench.java`, 10 iterations after JIT warmup]

**3 × 83.7 ms = 251 ms per test method, before a single assertion runs.** Against the measured
0.342 s floor, that is **73 % of the fixture's cost**. The residual 91 ms for 31 entity inserts plus
a cascade delete against a local container is entirely plausible — the model is self-consistent,
which is the check that makes this an explanation rather than a coincidence.

Suite-wide:

- 318 fixture tests × 251 ms = **79.8 s**
- ~117 `signinCookie(` call sites across 16 files, each a real `POST /signin` → one
  `matches()` at 77.9 ms ≈ **9 s** [VERIFIED: `grep -rn "signinCookie(" src/test/java | wc -l` → 117]
- **≈ 89 s, or 37 % of the 241.5 s total test-execution time, is BCrypt key stretching.**

## The Levers

| Lever | Est. win | Risk | Effort | Verdict |
|---|---|---|---|---|
| **1. Test-profile BCrypt work factor** | **~78 s (−32 %)** | None measured | ~10 lines | **PULL NOW** |
| **2. `maxParallelForks` (JVM forks)** | Sub-linear; Docker-RAM-bound | Low — separate DBs | 1 line + measure | **PULL SECOND** |
| 3. JUnit 5 in-JVM `parallel.enabled` | n/a | **Breaks isolation model** | Redesign | **DO NOT PULL** |
| 4. Split `test` into per-tier tasks | **Negative** | — | Medium | **DO NOT PULL** |
| 5. Trim fixture to what each test needs | ~29 s residual | Medium (29 classes) | Large refactor | Out of scope |

### Lever 1 — BCrypt work factor in the test profile [RECOMMENDED]

Make the strength injectable with a production-safe default, and override it only in
`application-test.properties`:

```java
// BeanConfiguration.java
@Bean
public PasswordEncoder passwordEncoder(
        @Value("${security.bcrypt.strength:10}") int strength) {
    return new BCryptPasswordEncoder(strength);
}
```

```properties
# application-test.properties
security.bcrypt.strength=4
```

Expected: 3 × 1.8 ms = 5.4 ms/test instead of 251 ms → **~78 s off the full suite**, and a similar
proportion off `fastTest` (the pre-commit gate the todo actually complains about).

**Why this is safe here specifically — verified, not assumed:**

- **No test asserts on the work factor.** The only BCrypt-shape assertion in the entire suite is
  `AuthenticationTest`'s `BCRYPT_HASH_MARKER = "$2a$"`
  [VERIFIED: src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java:109] — the
  *algorithm* prefix, deliberately not the cost segment. `$2a$04$…` still matches. Its own Javadoc
  says it is derived "so these tests do not depend on the repository's row shape — only on what a
  bcrypt hash always looks like."
- **`SigninTimingEqualizationTest` still works.** It proves the F1 timing fix by *counting*
  `PasswordEncoder.matches()` invocations via a `CountingPasswordEncoder` delegate, not by comparing
  wall-clock durations [VERIFIED: src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java:30-37].
  A cheaper work factor changes the constant, not the count. (The planner should still confirm this
  by reading the assertions — it is the one test with a *reason* to care.)
- **The F1 dummy-hash stays coherent.** `AuthenticationController` derives its dummy hash from the
  injected `PasswordEncoder` bean precisely "so its BCrypt work factor automatically tracks whatever
  strength `BeanConfiguration` configures"
  [VERIFIED: src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java:59-60].
  This lever was anticipated by that comment.
- **No `docs/CODE_STYLE.md` rule 8 violation** — the override lives in a version-controlled
  properties file, not a developer's machine [CITED: docs/CODE_STYLE.md:316].
- **Not a mock** (rule 4): it is the same real `BCryptPasswordEncoder` class doing real BCrypt, at a
  different documented cost parameter.

**The one genuine trade-off to state in the plan:** the test profile now exercises a weaker work
factor than production, so a hypothetical regression that only manifests at strength 10 would go
unseen. BCrypt's cost parameter does not change its output format or `matches()` semantics, so this
is a theoretical rather than practical gap — but it should be written down, not glossed. Mitigation
if wanted: leave `AuthenticationTest` (or one dedicated method) pinned at the production strength.

### Lever 2 — `maxParallelForks` [SAFE, MEASURE BEFORE COMMITTING A VALUE]

`maxParallelForks` is "the maximum number of test processes to start in parallel", default **1**
[CITED: docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html]. This is
**JVM-fork-level** parallelism, which is categorically different from lever 3:

Each fork is a fresh JVM → a fresh classloader → `AbstractPostgresContainerTest`'s
`static { postgres.start(); }` runs **once per fork**
[VERIFIED: src/test/java/com/vrudenko/kanban_board/support/containers/AbstractPostgresContainerTest.java:80-82].
So N forks = N PostgreSQL containers = **N independent databases**. Tests within a fork still run
sequentially, so `cleanup()`'s global `userService.deleteAll()` only ever wipes its own fork's
database. Every isolation assumption in D-02 holds unchanged.

**Costs and caps to measure against, not assume:**

- **Docker memory is the binding constraint.** This machine reports Docker **CPUs: 8, Total Memory:
  7.728 GiB** [VERIFIED: `docker info`]. Postgres containers are cheap; **Redpanda is not**.
- **`fastTest` is not Redpanda-free, contrary to what its tag filter implies.**
  `HistoricalActivityEventReconstructorTest extends AbstractKafkaContainerTest` carries **no
  `@Tag`** [VERIFIED: src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructorTest.java:38-39],
  and its results are present in `build/test-results/fastTest/` [VERIFIED]. So `fastTest` already
  starts one Redpanda container — and under N forks could start up to N. This is a real finding for
  this task and interacts with the pending "E2ETest-suffix vs. fastTest-filter" todo.
- **Spring context cache is per-JVM**, so N forks multiply context boots. Cheap here (contexts are
  few and boot is already amortized), but not free.
- **Never set `forkEvery`.** Default is 0 = one JVM handles all its classes
  [CITED: docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html]. Setting it to 1 would
  start a container *per test class* — catastrophic here. Worth a comment in `build.gradle` so a
  future contributor does not "improve" it.
- Gradle's own guidance suggests cores/2 as a starting point
  [CITED: docs.gradle.org/current/userguide/performance.html]. On 8 CPUs / 7.7 GiB that argues for
  **2–4, measured**, not 8.

Because lever 1 removes CPU-bound work and lever 2 adds CPU/memory contention, **measure lever 2
after lever 1 lands** — the optimal fork count is different once BCrypt is gone.

### Lever 3 — JUnit 5 in-JVM parallel execution [DO NOT PULL]

`junit.jupiter.execution.parallel.enabled`, `.mode.default`, `.mode.classes.default`
[CITED: docs.junit.org/6.1.0/writing-tests/parallel-execution.html]. Same JVM → same static
containers, same database, same cached Spring contexts. **Five independent blockers**, any one of
which is sufficient:

1. **`cleanup()` is a global wipe.** `userService.deleteAll()` deletes *all* users and cascades to
   every board/column/task/subtask [VERIFIED: AbstractAppTest.java:199-203]. One test's `@AfterEach`
   firing while another test is mid-execution destroys that test's fixtures. This is not a subtle
   race — it is a guaranteed cross-test wipe.
2. **`countQueries()` is corrupted by construction.** It calls `statistics.clear()`, runs the
   action, then reads `getPrepareStatementCount()` [VERIFIED: AbstractAppTest.java:307-312]. Those
   statistics are **`SessionFactory`-global, not per-thread** — any concurrent test issuing queries
   against the same context pollutes the count. This breaks the query-count assertions in
   `TaskServiceTest` / `OwnershipVerifierServiceTest`, which CLAUDE.md names as a project testing
   convention.
3. **A mutable static explicitly documents sequential execution as a precondition.**
   `AbstractKafkaContainerTest.producerSchemaRegistryUrlOverride` is a `volatile` field one test
   flips and resets; its Javadoc states "Test classes in this package always run sequentially within
   one JVM (no parallel test execution is configured anywhere in this project), so there is no
   window where two classes' contexts are built concurrently against a transiently wrong value"
   [VERIFIED: AbstractKafkaContainerTest.java:145-151]. Parallelism opens exactly that window and
   breaks `SchemaRegistryOutageE2ETest`.
4. **One shared topic, one shared consumer group.** All Kafka E2E classes share the single
   `kanban.activity` topic and the `activity-log` consumer group
   [VERIFIED: src/main/resources/application-test.properties, `spring.kafka.consumer.group-id=activity-log`].
   Concurrent classes would consume each other's records. `AbstractKafkaContainerTest`'s Javadoc
   already cites unrelated fixture traffic "turning every test method into a race against unrelated
   traffic" as its reason for not extending `AbstractAppTest` — parallelism reintroduces that at
   class scale.
5. **Positional assertions over shared tables.** e.g. `BoardServiceTest` takes
   `boardService.findAll().getFirst()` and expects an `owningUser` board, with fixture creation order
   deliberately arranged to keep that stable [VERIFIED: AbstractAppTest.java:169-173]. Concurrent
   population makes ordering nondeterministic.

**And `@ResourceLock` does not rescue it.** Its `READ_WRITE` mode means "execution of the annotated
element will occur while no other test class or test method that uses the shared resource is being
executed", and for a method the lock is held "before any `@BeforeEach` … and released after all
`@AfterEach`" [CITED: junit.org ResourceLock API]. The shared resource here is *the database*, which
**all 318 fixture-bearing tests touch for write**. Locking them correctly serializes precisely the
318 tests that constitute 188.4 s of the 241.5 s budget — i.e. it would produce a correct suite with
essentially no speedup, at the cost of annotating 29 classes. The mechanism is right; the workload
shape defeats it.

Making lever 3 viable would require: per-test schema or tenant scoping to replace the global
`deleteAll()`, a thread-safe replacement for `countQueries()`, per-class Kafka topics and consumer
groups, and removal of the mutable registry-URL static. That is a phase, not a quick task.

### Lever 4 — Splitting `test` into per-tier Gradle tasks [DO NOT PULL — counterproductive]

Mechanically it *is* purely additive: `fastTest` already proves the pattern — register a `Test` task
reusing `sourceSets.test.output.classesDirs` and `sourceSets.test.runtimeClasspath` with a
`useJUnitPlatform { includeTags … }` filter [VERIFIED: build.gradle:197-206]. **No source-set or
directory restructuring is needed.**

But it does not buy parallelism. Gradle's `--parallel` flag executes "tasks from different
subprojects in parallel" [CITED: docs.gradle.org/current/userguide/performance.html]. This is a
**single-project build** — no `settings.gradle` subprojects — so N tier tasks run **sequentially**,
each paying its own JVM startup, its own PostgreSQL container start, and its own Spring context
boots. That is strictly worse than today.

`maxParallelForks` is Gradle's supported intra-task parallelism and achieves the same
distribute-classes-across-JVMs effect without the multiplied fixed costs. Splitting into tiers would
only pay off after restructuring into Gradle subprojects — a large change, well outside this task.

## Common Pitfalls

- **Assuming Testcontainers startup is the cost.** Already measured and rejected at ~2.29 s / ~1 %
  [CITED: docs/LOCAL_DEV.md, "Testcontainers reuse: evaluated, not enabled"]. This research
  independently confirms it: the no-fixture control classes run at 0.005 s/test on the same
  container.
- **Assuming Spring context caching is broken.** It is not — the control group proves contexts are
  well-shared. No `@DirtiesContext` or `@MockBean` exists anywhere in `src/test` [VERIFIED: grep],
  which is exactly why context fragmentation is absent.
- **Setting `maxParallelForks` in a local `gradle.properties`.** There is no `gradle.properties` in
  this repo [VERIFIED]. Any value must go in `build.gradle` to satisfy CODE_STYLE rule 8 — a
  developer-local file is the manual setup step that rule forbids.
- **Re-running the suite to get a baseline.** Not needed for the *analysis* — `build/test-results/`
  already holds a full run. It **is** needed to validate the win: record before/after wall-clock for
  both `test` and `fastTest`, ideally twice each, since this project has documented 18 s run-to-run
  variance [CITED: docs/LOCAL_DEV.md — 232 s / 224 s / 242 s].
- **Reporting summed test time as wall-clock.** They differ: 241.5 s summed vs. the ~5–6 min
  wall-clock the todo cites. The gap is Gradle startup, compilation, container start, and Spring
  context boots, none of which lever 1 touches. State the win against both numbers.

## Recommendation for the plan

1. **Task 1 — Lever 1.** Parameterize BCrypt strength (default 10), set 4 in the test profile.
   Verify `AuthenticationTest`, `SigninTimingEqualizationTest`, and `AuthenticationE2ETest`-family
   classes stay green. Record `test` and `fastTest` wall-clock before and after.
2. **Task 2 — Lever 2, only if Task 1's win is insufficient.** Add `maxParallelForks` to `fastTest`
   first (the gate that hurts), measure at 2 and 4, watch Docker memory, and comment the
   `forkEvery`-stays-0 constraint. Commit whichever value measures best — or none, if contention
   wins.
3. **Do not touch** JUnit 5 in-JVM parallelism or tier-splitting. Record both as evaluated-and-
   rejected with the reasoning above, so they are not re-litigated — the same discipline
   `docs/LOCAL_DEV.md` applied to container reuse.
4. **File as a side-finding:** `HistoricalActivityEventReconstructorTest` is untagged and drags a
   Redpanda container into the pre-commit `fastTest` gate. Relevant to the existing E2ETest-suffix
   todo; likely worth its own `@Tag("kafka")` decision rather than a silent fix here.

## Assumptions Log

| # | Claim | Risk if wrong |
|---|---|---|
| A1 | `SigninTimingEqualizationTest` asserts on `matches()` *call counts*, not elapsed time — inferred from its Javadoc and the `CountingPasswordEncoder` name; its assertion bodies were not read line-by-line. | Low. If it does compare durations, a lower work factor shrinks the measured window and could make it flaky — the executor should read its assertions first and, if so, pin that one class to strength 10. |
| A2 | Lowering strength to 4 saves ~78 s. Extrapolated from a measured 83.7 ms→1.8 ms per encode × 3 encodes × 318 tests, not from a post-change suite run. | Low — arithmetic on measured constants. Must still be confirmed by a real before/after run. |
| A3 | Every one of the 29 classes I attributed to the `AbstractAppTest` hierarchy genuinely extends it (list built from grep of `extends Abstract*`). | Low; a misattribution shifts the 318/67 split slightly, not the conclusion (the 70× control-group gap is independent of it). |

## Sources

**Primary (HIGH):**
- Repository source, read this session: `AbstractAppTest.java`, `AbstractPostgresContainerTest.java`,
  `AbstractKafkaContainerTest.java`, `AbstractAppMockMvcTest.java`, `AbstractAppE2ETest.java`,
  `BeanConfiguration.java`, `build.gradle`, `application-test.properties`
- `build/test-results/test/*.xml` and `build/test-results/fastTest/*.xml` (175 + 144 files, parsed)
- BCrypt benchmark executed this session against `spring-security-crypto-6.5.0`
- `docker info`, `os.cpu_count()` on this machine

**Secondary (MEDIUM–HIGH, official docs):**
- [Gradle Test task DSL — `maxParallelForks`, `forkEvery`](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html)
- [Gradle performance guide — `--parallel` is cross-subproject](https://docs.gradle.org/current/userguide/performance.html)
- [JUnit 5 Parallel Execution](https://docs.junit.org/6.1.0/writing-tests/parallel-execution.html)
- [JUnit 5 `@ResourceLock` API](https://junit.org/junit5/docs/current/api/org.junit.jupiter.api/org/junit/jupiter/api/parallel/ResourceLock.html)
- `docs/LOCAL_DEV.md` (prior container-reuse investigation), `docs/CODE_STYLE.md` rule 8

**Research date:** 2026-08-11 · **Valid until:** measurements are machine-specific; re-measure BCrypt
on any other host before reusing the 83.7 ms figure.
