---
phase: quick-260811-ixj
plan: 01
subsystem: testing
tags: [performance, testing, bcrypt, gradle, parallelism, security]
dependency-graph:
  requires: []
  provides:
    - PasswordEncoderStrengthTest
    - BeanConfiguration.passwordEncoder(int) injectable BCrypt strength
    - build.gradle maxParallelForks=2 on test and fastTest
  affects:
    - docs/LOCAL_DEV.md
    - .planning/todos (source todo closed, new todo filed)
tech-stack:
  added: []
  patterns:
    - "Injectable @Value cost factor with a production-safe fallback, guarded by a reflection test that goes red if the fallback string changes"
    - "Measure-before-choosing protocol for build-tooling levers: two runs per configuration, test count recorded alongside duration, decision only if a value beats the documented run-to-run variance on both runs"
key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/config/PasswordEncoderStrengthTest.java
    - .planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md
    - .planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md
  modified:
    - src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java
    - src/main/resources/application-test.properties
    - build.gradle
    - docs/LOCAL_DEV.md
    - .planning/todos/completed/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md (moved from pending/)
decisions:
  - "Lever 1 (BCrypt cost factor): production fallback stays 10 (Spring Security default), test profile overrides to 4 (BCryptPasswordEncoder's floor) via application-test.properties. Measured win smaller than RESEARCH.md's arithmetic extrapolation (~63s/14.5% on test vs. a projected ~78s/32%) because wall-clock includes Gradle startup, compilation, container start and Spring context boot, none of which this lever touches -- stated explicitly, not overclaimed."
  - "Lever 2 (maxParallelForks): measured 2 vs 4 on fastTest after lever 1 landed. 2 forks won decisively (242.5s avg vs 285.0s 1-fork baseline, both runs beating the ~18s documented variance). 4 forks was both slower (267.0s avg) and only cleared variance on one of its two runs, so it was rejected. 2 forks was then separately measured and adopted on test too (276.5s avg vs 370.5s baseline)."
  - "JUnit 5 in-JVM parallel execution and per-tier Gradle task splitting recorded as evaluated-and-rejected in docs/LOCAL_DEV.md, with reasoning specific to this codebase's isolation model (D-02) and single-project build shape, so a future session does not re-litigate them from scratch."
  - "HistoricalActivityEventReconstructorTest's missing @Tag(\"kafka\") deliberately not fixed in this quick task -- filed as its own decision-framed todo, since tagging it is a real coverage trade-off (removes a class from the pre-commit gate), not a mechanical fix."
metrics:
  duration: 102min
  completed: 2026-08-11
status: complete
actuals:
  tokens: 10545
  tasks: 3
  commits: 4
---

# Quick Task 260811-ixj: Test suite speed / parallelization Summary

Cut the test suite's dominant measured cost -- BCrypt key-stretching in the test profile, ~89s of it per RESEARCH.md -- by making the cost factor injectable with a production-safe fallback, then measured (rather than assumed) whether Gradle fork-level parallelism adds anything on top. Combined, `./gradlew test` went from a 433.5s 2-run average to a 276.5s 2-run average (-36.2%) and `./gradlew fastTest` (the pre-commit gate) from 337.5s to 242.5s (-28.1%), with zero test-count shrinkage at any step.

## What Was Built

**Task 1 -- BCrypt cost factor made injectable, lowered in the test profile.**
`BeanConfiguration.passwordEncoder()` now takes `@Value("${security.bcrypt.strength:10}") int strength` and passes it to `BCryptPasswordEncoder`'s constructor. The `:10` fallback IS the production value, so any deployment that never activates the `test` Spring profile is unchanged. Only `application-test.properties` overrides the key, to 4 (`BCryptPasswordEncoder`'s minimum permitted value).

`PasswordEncoderStrengthTest` (new, `src/test/java/.../config/`) proves this is genuinely in force, not silently ignored:
- `TestProfileCostFactor.shouldEncodeAtCostFactorFour_whenUsingTheAutowiredBean` -- encodes a throwaway plaintext through the real autowired bean and asserts the resulting hash's cost segment is `"04"`.
- `ProductionFallback.shouldFallBackToTen_whenNoOverrideIsConfigured` -- reflects on the `@Value` annotation of `BeanConfiguration.passwordEncoder`'s parameter and asserts the fallback string is exactly `"${security.bcrypt.strength:10}"`.
- `ProductionFallback.shouldBeAbsentFromDefaultProperties_whenReadingApplicationProperties` -- reads `application.properties` from the classpath and asserts the key is absent from it.

**Written RED first, confirmed for the right reasons before any `BeanConfiguration` edit:** `TestProfileCostFactor`'s test failed with `AssertionFailedError` (cost segment was `"10"`, the pre-edit un-parameterized bean); `ProductionFallback.shouldFallBackToTen_...` failed with `NoSuchMethodException` (no `passwordEncoder(int)` overload existed yet). After the GREEN edit, all three pass.

`AuthenticationTest` and `SigninTimingEqualizationTest` were run explicitly and pass unmodified under strength 4 (confirmed by `git diff` showing zero changes to either file):
- `AuthenticationTest`'s `BCRYPT_HASH_MARKER = "$2a$"` assertion is the algorithm prefix, not the cost segment -- `$2a$04$...` satisfies it exactly as `$2a$10$...` did.
- `SigninTimingEqualizationTest`'s `CountingPasswordEncoder` asserts `matchesInvocationCount()).isEqualTo(1)` -- a call-count assertion, not an elapsed-time one (confirmed by reading its assertion bodies, closing RESEARCH.md's Assumption A1). A cheaper cost factor changes what the one counted call costs, not the count itself.

**Task 2 -- `maxParallelForks` measured at 2 and 4, adopted at 2 on both `fastTest` and `test`.**
Measured after Task 1 landed (the optimal fork count differs once BCrypt's CPU-bound cost is gone). `fastTest` at 2 forks: 233s and 252s (avg 242.5s) against a 1-fork average of 285.0s -- both runs beat it by far more than this project's documented ~18s run-to-run variance. At 4 forks: 277s and 257s (avg 267.0s) -- slower than 2 forks, and only one run cleared the variance bar against the 1-fork baseline, so 4 was rejected. 2 forks was then measured on `test` too (which runs the full Kafka tier, a different container-memory profile) and won there as well: 283s and 270s (avg 276.5s) against a 370.5s 1-fork baseline.

`build.gradle` now carries `maxParallelForks = 2` on both `tasks.named('test')` and `tasks.register('fastTest', ...)`, each with a comment recording the measured numbers and why fork-level parallelism is safe here: each Gradle test-worker fork is a separate JVM with its own classloader, so `AbstractPostgresContainerTest`'s `static { postgres.start(); }` runs once per fork -- N forks means N independent PostgreSQL containers and N independent databases, leaving the D-02 `@AfterEach`-deletion isolation model untouched. Live `docker ps` census during both fork-count runs confirmed exactly N `postgres:16` containers (one per fork) with no startup or memory failures against this machine's 7.728 GiB Docker budget. `forkEvery` was left unset (default 0) -- a non-zero value would restart the JVM, and therefore the static Testcontainers initializer, per class.

**Task 3 -- findings recorded durably, side finding filed, source todo closed.**
`docs/LOCAL_DEV.md` gained a new section (adjacent to "Testcontainers reuse: evaluated, not enabled", same register: measured numbers first, decision second, reasoning enumerated, revisit-if clause) covering both levers, the accepted security trade-off, and the two rejected alternatives:
- **JUnit 5 in-JVM parallel execution -- rejected.** Five independent blockers named concretely: the unscoped `userService.deleteAll()` in `cleanup()`; `SessionFactory`-global Hibernate statistics behind `countQueries()`; `AbstractKafkaContainerTest`'s mutable static whose own Javadoc names sequential execution as a precondition; one shared Kafka topic/consumer group; positional assertions over shared tables. `@ResourceLock` does not rescue it -- the shared resource is the database, which all 318 fixture-bearing tests write to, so correct locking would re-serialize precisely the cost.
- **Per-tier Gradle task splitting -- rejected.** Gradle's `--parallel` parallelizes across subprojects; this is a single-project build, so per-tier tasks would run sequentially, each paying its own JVM/container/context startup -- strictly worse than today.

New todo filed (`.planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md`): `HistoricalActivityEventReconstructorTest` extends `AbstractKafkaContainerTest` with no `@Tag`, so it runs inside `fastTest` and starts a Redpanda container on every commit -- and under the now-adopted `maxParallelForks = 2`, potentially one per fork. Framed as a three-option decision (tag it, leave it, split what it proves across tiers), not fixed here; the class is unmodified (`git diff --exit-code` confirmed).

Source todo (`2026-08-10-investigate-test-parallelization-and-other-suite-speed.md`) moved to `.planning/todos/completed/` with a `## Resolution` section answering its three named candidates in order (JUnit 5 parallelism: rejected; per-tier splitting: rejected; fixture cost: correctly reframed -- the dominant cost was BCrypt key-stretching specifically, a fourth lever the todo did not name, not entity-creation volume in general) and pointing at both `docs/LOCAL_DEV.md` and `260811-ixj-MEASUREMENTS.md` for the durable record.

## Verification

- `./gradlew spotlessCheck` -- green at every task boundary.
- `./gradlew test` -- green, **388 tests, 0 failures, 0 errors** at the final committed state (2 forks, cost factor 4), up from a 385-test baseline (the +3 is `PasswordEncoderStrengthTest`'s three new methods, confirmed by cross-checking every run's test count against the previous one -- no shrinkage at any step).
- `PasswordEncoderStrengthTest` -- passes; its RED-before-GREEN transition was observed and is reported above with the actual failure types (`AssertionFailedError`, `NoSuchMethodException`), not merely asserted.
- `AuthenticationTest` and `SigninTimingEqualizationTest` -- pass, unmodified (`git diff` for both files against the pre-task base is empty).
- `git diff --stat` for `src/main/java/.../BeanConfiguration.java` shows exactly the described change: one new import, one Javadoc block, the method signature (`passwordEncoder()` -> `passwordEncoder(@Value(...) int strength)`), and the constructor call (`new BCryptPasswordEncoder()` -> `new BCryptPasswordEncoder(strength)`). No other `src/main` Java file changed.
- `260811-ixj-MEASUREMENTS.md` contains 14 individually recorded runs with durations and test counts (4 baseline, 4 after lever 1, 6 fork-count runs: 2+2 on `fastTest` at 2 and 4 forks, 2 on `test` at 2 forks) -- exceeds the plan's 12-run floor.
- `docs/LOCAL_DEV.md` names both rejected levers (JUnit 5 in-JVM parallelism, per-tier task splitting) with codebase-specific reasoning, and `maxParallelForks` appears 4 times (twice in `build.gradle` comments, twice referenced from the new `docs/LOCAL_DEV.md` section and the closed todo's Resolution).
- `HistoricalActivityEventReconstructorTest` -- unmodified (`git diff --exit-code` confirmed at Task 3).
- `.planning/todos/pending/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md` no longer exists; `.planning/todos/completed/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md` exists with a `## Resolution` section.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] ErrorProne `StringSplitter` warning on the RED-phase test's `String.split("\\$")` call**
- **Found during:** Task 1, first compile of the RED `PasswordEncoderStrengthTest`.
- **Issue:** `compileTestJava` emitted an ErrorProne `StringSplitter` warning (not one of the five checks promoted to ERROR for test sources, so it did not fail the build, but left avoidable noise).
- **Fix:** replaced `hash.split("\\$")[2]` with Guava's `Splitter.on('$').splitToList(hash).get(2)` (Guava is already a project dependency), matching ErrorProne's own suggested fix.
- **Files modified:** `src/test/java/com/vrudenko/kanban_board/config/PasswordEncoderStrengthTest.java`.
- **Commit:** folded into `34185aa` (pre-existing at commit time, not a separate fix-up commit).

No other deviations -- both levers landed as researched and planned, including the conditional `build.gradle` edit (only touched because a fork count measured better than 1, per the plan's own framing), and neither `SigninTimingEqualizationTest` nor `AuthenticationTest` needed any change.

## Known Stubs

None. No hardcoded empty values, placeholder text, or unwired data sources were introduced.

## Threat Flags

None. The one security-relevant change (BCrypt cost factor) is already covered by the plan's own threat register (T-ixj-01 through T-ixj-04), reproduced in `docs/LOCAL_DEV.md`'s new section rather than newly discovered here.

## Self-Check: PASSED

- `src/test/java/com/vrudenko/kanban_board/config/PasswordEncoderStrengthTest.java` -- FOUND, committed in `34185aa`.
- `src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java` -- FOUND, injectable-strength change present, committed in `34185aa`.
- `src/main/resources/application-test.properties` -- FOUND, `security.bcrypt.strength=4` present, committed in `34185aa`.
- `build.gradle` -- FOUND, `maxParallelForks = 2` present on both `test` and `fastTest`, committed in `7c6957a`.
- `docs/LOCAL_DEV.md` -- FOUND, new section present, committed in `f75c678`.
- `.planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md` -- FOUND, committed in `f75c678`.
- `.planning/todos/completed/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md` -- FOUND with `## Resolution`, committed in `f75c678`.
- `.planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md` -- FOUND, 14 runs recorded, committed across `74f11e1`, `34185aa`, `7c6957a`.
- Commit `74f11e1` -- FOUND in `git log`.
- Commit `34185aa` -- FOUND in `git log`.
- Commit `7c6957a` -- FOUND in `git log`.
- Commit `f75c678` -- FOUND in `git log`.
