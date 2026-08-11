---
created: 2026-08-10T12:28:00.000Z
title: Investigate test parallelization and other suite speed improvements
area: testing
severity: minor
files:
  - build.gradle
---

## Problem

The full `./gradlew test` suite has grown from 210 tests (~4m51s, measured at Phase 04.2 close) to
381 tests (~5-6 minutes, measured this session during Phase 07.1) and `fastTest` (the pre-commit
gate) is now consistently 4-5 minutes on its own. Every plan this phase paid that cost repeatedly
during TDD red-green cycles and multi-run flake verification. No investigation of test-level
parallelization has been done — only container-level reuse was evaluated, and that was a different
question with a different (correctly negative) answer.

**Already evaluated and rejected, do not re-litigate:** Testcontainers container *reuse* across
separate `./gradlew test` invocations (Phase 04.2 Plan 03, see `docs/LOCAL_DEV.md` and
`.planning/STATE.md`'s decision log) — measured container-start cost (~2.29s) is ~1% of `fastTest`
wall-clock, smaller than run-to-run variance, and no zero-manual-step opt-in satisfies
`docs/CODE_STYLE.md` rule 8. That finding is about container *startup* cost across separate JVM runs,
not about running tests *concurrently within* a single run — a genuinely different lever.

## Solution

Not yet investigated. Candidates for whoever picks this up to evaluate (not a decision, a list to
size and test):

1. **JUnit 5 parallel test execution** (`junit.jupiter.execution.parallel.enabled=true` in a
   `src/test/resources/junit-platform.properties`, `maxParallelForks` currently unset in
   `build.gradle`, i.e. Gradle's default of 1). The main open question: every `@SpringBootTest` class
   in this suite shares ONE cached Spring context and ONE Testcontainers Postgres/Kafka instance
   (04.2, D-01) — concurrent test execution against a single shared database instance risks
   cross-test data races unless isolation (currently `@AfterEach` deletion, D-02, per
   `AbstractAppTest`'s own Javadoc) holds up under concurrency, which it was never designed or
   verified for. This is the crux of the investigation, not a detail.
2. **Splitting the single `test` task into parallel Gradle test tasks** (e.g. by tier: unit,
   in-process MockMvc, Kafka-backed) run as separate Gradle workers — sidesteps the shared-context
   risk above since each task gets its own JVM/context, at the cost of N separate Testcontainers
   startups instead of one.
3. **Reducing per-test fixture cost** — `AbstractAppTest.setup()`'s `@BeforeEach` creates 2 users, up
   to 3 boards, 7 columns, 7 tasks, 7 subtasks for every single test method in every class extending
   it, regardless of whether that test needs all of it. Measuring how much of the current wall-clock
   is fixture setup vs. actual assertions would clarify whether this is worth trimming before
   reaching for parallelization at all.

Whoever picks this up should measure before choosing — the container-reuse investigation's discipline
(measure, don't assume) is the right model to follow here too.

## Resolution (quick task 260811-ixj, 2026-08-11)

Measured before choosing, per this todo's own instruction. Answering the three named candidates in
the order this todo listed them:

1. **JUnit 5 parallel test execution — investigated, rejected.** Five independent blockers, any one
   sufficient: the unscoped `userService.deleteAll()` in `AbstractAppTest.cleanup()`;
   `SessionFactory`-global Hibernate statistics behind `countQueries()`;
   `AbstractKafkaContainerTest`'s mutable static whose own Javadoc names sequential execution as a
   precondition; one shared Kafka topic and consumer group across every Kafka E2E class; and
   positional assertions over shared tables. `@ResourceLock` does not rescue it — the shared
   resource is the database, which all 318 fixture-bearing tests write to, so correct locking would
   re-serialize precisely the work that constitutes the cost. Full reasoning in `docs/LOCAL_DEV.md`.
2. **Splitting `test` into parallel Gradle test tasks — investigated, rejected.** Gradle's
   `--parallel` flag parallelizes across *subprojects*; this is a single-project build, so per-tier
   tasks would run sequentially, each paying its own JVM startup, container start, and Spring context
   boot. Strictly worse than today. `maxParallelForks` (below) is Gradle's actual supported
   mechanism for the same distribute-across-JVMs effect, without the multiplied fixed costs.
3. **Reducing per-test fixture cost — investigated, correctly reframed, deliberately not fully
   pursued.** This candidate's premise was right (the fixture *is* the dominant cost) but its
   diagnosis was incomplete: the measurement found ~89s / 37% of total test-execution time was BCrypt
   key-stretching specifically — `AbstractAppTest.setup()`'s three `createUser()` calls per test
   method, each running a real BCrypt encode at Spring Security's default cost factor of 10 — not
   entity-creation volume in general. That is a **fourth lever this todo did not name**: the
   test-profile BCrypt cost factor, now lowered to 4 (production stays at 10, guarded by
   `PasswordEncoderStrengthTest`). The remaining ~29s of non-BCrypt fixture cost (31-entity creation
   per test method) is real and was deliberately left untouched — trimming it needs ~29 dependent
   test classes reworked, including positional assertions, which is a real refactor outside a quick
   task's scope.

**Measured outcome:** `./gradlew test` 433.5s avg -> 276.5s avg (-36.2%); `./gradlew fastTest` 337.5s
avg -> 242.5s avg (-28.1%), combining the BCrypt cost-factor lever with `maxParallelForks = 2`
(measured safe and adopted on both tasks — fork-level parallelism, unlike JUnit 5's in-JVM
parallelism, gives each fork its own JVM and therefore its own static Testcontainers Postgres
instance, so isolation (D-02) is untouched). Full run-by-run numbers, the rejected 4-fork
measurement, and the container census live in
`.planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md`.
The durable, discoverable record — measured numbers, decision, reasoning, revisit-if clause, in the
same register as the existing Testcontainers-reuse section — is in `docs/LOCAL_DEV.md`, "Test-suite
speed: BCrypt cost factor lowered, fork-level parallelism adopted, in-JVM parallelism and per-tier
splitting rejected".

**One side finding filed separately, not fixed here:** `HistoricalActivityEventReconstructorTest`
extends `AbstractKafkaContainerTest` but carries no `@Tag`, so it runs inside `fastTest` and starts a
Redpanda container on every commit — and under the now-adopted `maxParallelForks = 2`, potentially
one per fork. See
`.planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md`.
