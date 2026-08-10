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
