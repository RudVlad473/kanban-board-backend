---
phase: quick-260811-ixj
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java
  - src/main/resources/application-test.properties
  - src/test/java/com/vrudenko/kanban_board/config/PasswordEncoderStrengthTest.java
  - build.gradle
  - docs/LOCAL_DEV.md
  - .planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md
  - .planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md
  - .planning/todos/completed/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md
autonomous: true
requirements: [QUICK-260811-ixj]

estimate:
  tokens: 85000
  raw_tokens: 85000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "The BCrypt cost factor the test profile runs at is a version-controlled property, not a hand-edited local file and not a hard-coded literal in src/test — a clean checkout of this repo gets the faster suite with zero manual setup (docs/CODE_STYLE.md rule 8)"
    - "The production cost factor is unchanged at 10, and that is proven by an assertion that goes red if someone changes the fallback, not by a claim in a summary"
    - "The lowered cost factor is proven to be actually in force under the test profile by reading the cost segment of a real hash the application's own PasswordEncoder bean produced — a silently-ignored property would go red, not pass quietly"
    - "Wall-clock for ./gradlew test and ./gradlew fastTest is recorded twice before the change and twice after, on this machine, back-to-back — no single run is reported as definitive against this project's documented ~18s run-to-run variance"
    - "The recorded after-numbers carry the test count alongside them, so a suite that got faster by running fewer tests would be visible rather than read as a win"
    - "Whether maxParallelForks helps is answered by measured runs at 2 and at 4 forks, and a value is committed only if it measured better than 1 — 'no value adopted' is an acceptable, recorded outcome"
    - "JUnit 5 in-JVM parallel execution and per-tier Gradle task splitting are recorded in docs/LOCAL_DEV.md as evaluated-and-rejected with their reasoning, so a future session does not re-litigate them"
    - "The one genuine trade-off — the test profile now exercises a weaker cost factor than production — is written down in docs/LOCAL_DEV.md, not glossed"
    - "./gradlew spotlessCheck and ./gradlew test both pass at plan end, with the full-suite test count reported against the baseline captured in Task 1 so silent shrinkage is visible"
    - "The untagged Kafka-container test that drags a Redpanda container into the pre-commit gate is filed as its own todo, not silently fixed inside this plan"
    - "The source todo no longer sits in .planning/todos/pending/"
  artifacts:
    - src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java
    - src/main/resources/application-test.properties
    - src/test/java/com/vrudenko/kanban_board/config/PasswordEncoderStrengthTest.java
    - docs/LOCAL_DEV.md
    - .planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md
    - .planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md
    - .planning/todos/completed/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md
  key_links:
    - "the @Value placeholder's :10 fallback -> production safety: the property is defined ONLY in application-test.properties, so any deployment that does not activate the test profile resolves the fallback. If that fallback is ever removed or lowered, production password hashing weakens silently — which is exactly what PasswordEncoderStrengthTest's reflection assertion exists to prevent"
    - "the passwordEncoder bean -> AuthenticationController's @PostConstruct equalizerHash: the F1 timing-equalization dummy hash is derived from the injected bean, so it tracks whatever cost factor is configured. Both signin branches therefore stay cost-matched at strength 4 exactly as they were at 10 — the F1 fix is not weakened by this change, only made cheaper"
    - "the passwordEncoder bean -> SigninTimingEqualizationTest's CountingPasswordEncoder delegate: that test asserts matchesInvocationCount() == 1 (a call COUNT), never an elapsed duration, so a cheaper cost factor changes the constant it measures against, not the count it asserts. Confirmed by reading its assertion bodies, closing research Assumption A1"
    - "the $2a$ prefix -> AuthenticationTest's BCRYPT_HASH_MARKER: that class asserts startsWith(\"$2a$\") and doesNotContain(\"$2a$\") — the ALGORITHM prefix, deliberately not the cost segment. $2a$04$... satisfies both assertions unchanged"
    - "Gradle maxParallelForks -> AbstractPostgresContainerTest's static { postgres.start(); }: forks are separate JVMs with separate classloaders, so N forks means N containers and N independent databases. That is what keeps cleanup()'s unscoped userService.deleteAll() safe under fork-level parallelism and unsafe under JUnit 5 in-JVM parallelism — the distinction the whole safe/unsafe split rests on"
    - "HistoricalActivityEventReconstructorTest's absent @Tag -> build.gradle's fastTest excludeTags filter: gate membership is opt-in by tag (D-21, D-22), so an untagged Kafka-container class runs in the pre-commit gate and starts a Redpanda container there — and under N forks, potentially one per fork. This is what makes the fork-count ceiling a Docker-memory question, not a CPU question"
---

<objective>
Cut test-suite wall-clock by removing the largest measured cost in it — BCrypt key stretching in the
test profile — and then measure, rather than assume, whether Gradle fork-level parallelism adds
anything on top.

Research (`260811-ixj-RESEARCH.md`) already measured the root cause: ~37% of test-execution time is
BCrypt key stretching, because `BeanConfiguration.passwordEncoder()` returns a bare
`new BCryptPasswordEncoder()` (Spring Security's default cost factor 10, in tests exactly as in
production) and `AbstractAppTest.setup()` runs three `createUser()` calls before every one of 318
test methods. That verdict is settled; this plan implements it and proves the numbers.

Purpose: make the pre-commit gate cheap enough that TDD red-green cycles stop paying for it, without
weakening production password hashing and without touching the `@AfterEach`-deletion isolation model
(D-02) that in-JVM parallelism would break.

Output:
- A version-controlled, test-profile-only cost factor with a production-safe fallback, guarded by a
  test that goes red if the fallback is changed.
- A measurement record with two before-runs and two after-runs of both `test` and `fastTest`.
- A measured answer on `maxParallelForks` (a committed value, or a recorded decision not to adopt
  one — both are acceptable outcomes).
- A `docs/LOCAL_DEV.md` section recording what was pulled, what was rejected, and why — modelled on
  the existing "Testcontainers reuse: evaluated, not enabled" section.
- A new todo for the untagged Kafka-container class that drags a Redpanda container into the
  pre-commit gate. Not fixed here.

`build.gradle` appears in `files_modified` conditionally: it is edited only if a fork count measures
better than the current default of 1. If none does, leave it untouched and record that in
`docs/LOCAL_DEV.md`.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-RESEARCH.md
@.planning/todos/pending/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md
@docs/CODE_STYLE.md
@docs/LOCAL_DEV.md
@src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java
@src/main/resources/application-test.properties
@src/test/java/com/vrudenko/kanban_board/config/CorsConfigTest.java
@build.gradle
</context>

<tradeoffs>

## Alternate approaches considered

### Lever 1 — how to make the test profile's BCrypt cost factor cheap

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A. `@Value`-injected strength on the existing `passwordEncoder()` bean, production fallback 10, overridden only in `application-test.properties`** | **+** One production wiring path, exercised identically by tests and production — the bean tests use IS the bean production uses. **+** Version-controlled, zero manual setup (rule 8). **+** `AuthenticationController`'s `@PostConstruct` equalizer hash tracks it automatically. **−** Touches `src/main` for a test-motivated reason. | **PICKED.** The `src/main` touch is one parameter with a production-preserving default; every alternative that avoids it does so by making the tested wiring differ from the shipped wiring, which is a worse trade for a security-relevant bean. |
| **B. A `@Profile("test")` / `@TestConfiguration` `PasswordEncoder` bean in `src/test`** | **+** Leaves `src/main` untouched. **−** The tested bean graph is no longer the production bean graph. **−** Needs `@Primary` or bean-definition overriding, and `SigninTimingEqualizationTest` already publishes a `@Primary` `PasswordEncoder` delegate — a second one creates a real ambiguity to resolve. **−** A per-class `@TestConfiguration` forks the Spring context cache key, adding context boots to claw back the time we just saved. | Rejected. Trades a one-parameter production edit for wiring divergence plus a live `@Primary` collision. |
| **C. `NoOpPasswordEncoder` or a fake encoder in the test profile** | **+** Fastest possible — removes the cost entirely rather than reducing it. **−** Violates `docs/CODE_STYLE.md` rule 4 (no mocks). **−** `AuthenticationTest:850` asserts the persisted hash `startsWith("$2a$")`; a no-op encoder makes that assertion, and the whole "a real bcrypt hash reaches the database" guarantee, vacuous. | Rejected. Deletes real coverage, not just cost. |
| **D. Trim `AbstractAppTest.setup()` to what each test actually needs** | **+** Attacks the residual ~29s of non-BCrypt fixture cost too. **−** ~29 test classes depend on the current fixture shape, including positional assertions (`BoardServiceTest` takes `findAll().getFirst()`). **−** Large refactor, well past a quick task's blast radius. | Rejected for this task; recorded in `docs/LOCAL_DEV.md` as the remaining headroom. |

### Lever 2 — how to get parallelism, if any

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A. Gradle `maxParallelForks` on `fastTest`, measured at 2 and 4, committed only if it wins** | **+** Forks are separate JVMs → separate classloaders → separate static Testcontainers instances → separate databases, so D-02's `@AfterEach` isolation is untouched. **+** One line, trivially revertible. **−** Multiplies container and Spring-context memory against a 7.728 GiB Docker budget. **−** Sub-linear, and less valuable once lever 1 has removed the CPU-bound work. | **PICKED, conditionally.** Measured after lever 1 precisely because lever 1 changes the optimum. |
| **B. `maxParallelForks = 4` on both tasks without measuring** | **+** No measurement wall-clock. **−** `test` runs the full Kafka tier; N forks there means up to N Redpanda containers on 7.7 GiB. Guessing here risks trading wall-clock for flaky container-startup failures. | Rejected — the source todo's own instruction is to measure before choosing. |
| **C. JUnit 5 in-JVM `parallel.enabled`** | **−** Five independent blockers, any one sufficient: `cleanup()`'s unscoped `userService.deleteAll()`; `SessionFactory`-global Hibernate statistics behind `countQueries()`; `AbstractKafkaContainerTest`'s mutable static whose Javadoc names sequential execution as its precondition; one shared Kafka topic and consumer group; positional assertions over shared tables. `@ResourceLock` does not rescue it — the shared resource is the database, which all 318 fixture-bearing tests write to, so correct locking re-serializes exactly the 188.4s that constitutes the cost. | Rejected. Making it viable is a phase, not a quick task. |
| **D. Split `test` into per-tier Gradle tasks** | **−** Gradle's `--parallel` parallelizes across *subprojects*; this is a single-project build, so N tier tasks run sequentially, each paying its own JVM startup, container start, and context boots. Strictly worse than today. | Rejected as counterproductive. |

## Non-obvious trade-offs

**Security — the one real cost, and it must be written down, not glossed.** The test profile will
exercise a weaker cost factor than production, so a hypothetical regression that only manifests at
strength 10 would go unseen. BCrypt's cost parameter changes the number of key-expansion rounds and
nothing else — not the output format, not `matches()` semantics — so this is a theoretical rather
than practical gap. The structural mitigations are that the fallback in `@Value` is the production
value (absence of configuration resolves to 10, so a lost properties line fails safe), and that
`PasswordEncoderStrengthTest` asserts both that fallback and the absence of the key from the
non-test properties file. Neither mitigation covers an environment variable or
`SPRING_APPLICATION_JSON` override at deploy time; that residual is stated in `docs/LOCAL_DEV.md`
rather than claimed closed.

**Time complexity — why the win is this large.** BCrypt's work is `2^strength` key-expansion rounds.
10 → 4 is `2^6` = 64× fewer rounds, which matches the measured 83.7 ms → 1.8 ms per encode (46×,
the gap being fixed per-call overhead that does not scale with the cost factor). Three encodes per
`@BeforeEach` × 318 fixture-bearing test methods = 251 ms → 5.4 ms per test, ~78s suite-wide. The
model being self-consistent with the measured 0.342s floor is what makes this an explanation rather
than a correlation. 4 is also `BCryptPasswordEncoder`'s minimum permitted value — anything lower
throws — so this lever cannot be over-pulled by a later edit.

**Memory — the binding constraint on lever 2, and it is not CPU.** Docker on this machine reports 8
CPUs and 7.728 GiB. Each fork starts its own PostgreSQL container (cheap) and its own Spring context
cache (cheap here). Redpanda is not cheap, and `fastTest` is not Redpanda-free: an untagged
`AbstractKafkaContainerTest` subclass runs inside it, so N forks could mean N Redpanda containers.
That is why fork count must be measured with container memory watched, and why Gradle's generic
"cores/2" guidance argues for 2–4 here rather than 8.

**State invalidation — why fork-level is safe where in-JVM is not.** The isolation model is
`@AfterEach` row deletion including an unscoped `userService.deleteAll()`. That is safe under
`maxParallelForks` for one structural reason: a fork is a fresh JVM, so
`AbstractPostgresContainerTest`'s `static { postgres.start(); }` runs once per fork and each fork's
wipe only ever reaches its own database. Under in-JVM parallelism the same call reaches a sibling
test's live fixtures mid-execution. The safe/unsafe split is structural, not a judgement call.
</tradeoffs>

<measurement_protocol>
Every timing in this plan is captured the same way, so the numbers are comparable to each other and
to the figures already in `docs/LOCAL_DEV.md`:

- Run from Git Bash at the repo root: `./gradlew <task> --rerun-tasks --console=plain`. The
  `--rerun-tasks` flag is required — without it Gradle's up-to-date check silently skips the run and
  reports a fraction of a second.
- Record the duration from Gradle's own `BUILD SUCCESSFUL in Xm Ys` line.
- Record the test count alongside it, so a faster-because-smaller suite is visible:
  `grep -ho 'tests="[0-9]*"' build/test-results/<task>/TEST-*.xml | cut -d'"' -f2 | awk '{s+=$1} END {print s}'`
- Runs go back-to-back in one session on one machine, no `clean` between them, matching the protocol
  the existing `docs/LOCAL_DEV.md` Testcontainers-reuse figures (232s / 224s / 242s) were captured
  under. Do not run other heavy work concurrently.
- Two runs per configuration. This project has ~18s of documented run-to-run variance; a single run
  is not a measurement.

Expect each `git commit` in this plan to trigger the pre-commit hook, which runs `spotlessCheck` and
then a full `fastTest`. That is roughly one extra suite run per commit. It is expected cost — do not
bypass the hook, and do not confuse a hook-triggered run with a measurement run.
</measurement_protocol>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Measure the baseline, lower the test-profile BCrypt cost factor, measure the result</name>
  <files>
    .planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md,
    src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java,
    src/main/resources/application-test.properties,
    src/test/java/com/vrudenko/kanban_board/config/PasswordEncoderStrengthTest.java
  </files>
  <read_first>
    src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java,
    src/test/java/com/vrudenko/kanban_board/config/CorsConfigTest.java,
    src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java (lines 55-95, the @PostConstruct equalizer hash),
    docs/CODE_STYLE.md rules 3, 4, 5, 9, 10
  </read_first>
  <precondition>Docker Desktop is running and `docker info` succeeds — every measurement run in this task starts a Testcontainers PostgreSQL instance, and a Docker-down machine produces a fast, meaningless failure rather than a baseline.</precondition>
  <behavior>
    PasswordEncoderStrengthTest, structured per docs/CODE_STYLE.md rule 5 (@Nested groups,
    should&lt;Outcome&gt;_when&lt;Condition&gt; names, AAA section comments), asserting with fully
    qualified AssertJ per rule 3:

    - Group "TestProfileCostFactor": encoding any throwaway plaintext through the autowired
      PasswordEncoder bean yields a hash whose leading segment names cost factor 4. This is the
      assertion that proves the property is genuinely in force rather than silently ignored — a
      typo'd or misplaced property key makes it red, not quietly slow.
    - Group "ProductionFallback", test 1: the placeholder string on the passwordEncoder bean
      method's parameter, read reflectively from its @Value annotation, is exactly
      "${security.bcrypt.strength:10}". Goes red if anyone changes the production fallback.
    - Group "ProductionFallback", test 2: the non-test properties resource on the classpath
      (application.properties) defines no line for that key once comment lines are discarded. Goes
      red if the override ever leaks out of the test profile into the default one.

    Write this test RED first — before editing BeanConfiguration — and confirm it fails for the
    right reason (no such method taking an int parameter / cost factor 10 observed), not for a
    compilation reason unrelated to the change.
  </behavior>
  <action>
    Step 1 — baseline, before any edit. Run `./gradlew test --rerun-tasks --console=plain` twice and
    `./gradlew fastTest --rerun-tasks --console=plain` twice per the measurement protocol above.
    Create `260811-ixj-MEASUREMENTS.md` in this quick task's directory with a "Baseline (unchanged
    tree)" table holding all four durations and their test counts, plus the machine facts that make
    them reproducible: the output of `docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes'` and
    the git SHA the baseline was measured at. Commit this file before changing any code, so the
    baseline exists on disk independently of whether the rest of the task succeeds.

    Step 2 — the RED test. Create `PasswordEncoderStrengthTest` in
    `src/test/java/com/vrudenko/kanban_board/config/`, extending `AbstractPostgresContainerTest`
    directly and annotated `@SpringBootTest`, exactly mirroring `CorsConfigTest`'s precedent in the
    same package. It must NOT extend `AbstractAppTest` — that base class's per-test fixture is the
    very cost this task is removing, and inheriting it here would be self-defeating. Give it no
    `@Tag`, so it runs in the pre-commit gate by default (D-21, D-22). Implement the three
    assertions described in the behavior block. Read the non-test properties resource via
    `new ClassPathResource("application.properties")` rather than a filesystem path, and pass
    `StandardCharsets.UTF_8` explicitly to whatever reader you use — `compileTestJava` promotes
    Error Prone's `DefaultCharset` check to ERROR severity, so an implicit-charset read fails the
    build. Reach the placeholder string with
    `BeanConfiguration.class.getDeclaredMethod("passwordEncoder", int.class)`, then read the
    `org.springframework.beans.factory.annotation.Value` annotation off `getParameters()[0]`. Run
    the class and confirm it is red.

    Step 3 — GREEN. In `BeanConfiguration`, change `passwordEncoder()` to take an `int` parameter
    annotated `@Value("${security.bcrypt.strength:10}")` and pass it to the `BCryptPasswordEncoder`
    constructor. The fallback of 10 is load-bearing: it is what makes the absence of any
    configuration resolve to the production cost factor, so a deployment that never activates the
    test profile is unchanged. Add a Javadoc on the bean method recording that the parameter exists
    so the test profile can run cheaper, that the fallback IS the production value, that
    `BCryptPasswordEncoder` rejects values below 4, and that
    `AuthenticationController`'s `@PostConstruct` equalizer hash derives from this bean and so
    tracks whatever is configured here.

    In `src/main/resources/application-test.properties`, add a `# === security ===` block setting
    the strength property to 4. Note in a comment that this file is only ever read when the `test`
    profile is active, which only `build.gradle`'s `test` and `fastTest` tasks arrange (via
    `systemProperty "spring.profiles.active", "test"`) — `rehearseHistoricalSchemas` deliberately
    does not, and so keeps the production cost factor.

    Step 4 — prove nothing regressed. Run `./gradlew spotlessCheck`, then `./gradlew test
    --rerun-tasks --console=plain`. Pay specific attention to three classes and report them by name
    in the summary: `PasswordEncoderStrengthTest` (new), `AuthenticationTest` (asserts on the `$2a$`
    algorithm prefix, which a cost segment of 04 still satisfies), and
    `SigninTimingEqualizationTest` (asserts `matchesInvocationCount()` equals 1 — a call count, not
    an elapsed duration, so it is unaffected; this run is what confirms it rather than assumes it).
    If `SigninTimingEqualizationTest` unexpectedly goes red on timing grounds, stop and report
    rather than weakening its assertion.

    Step 5 — after-measurement. That green `test` run is the first after-run. Run `test` once more
    and `fastTest` twice, then extend `260811-ixj-MEASUREMENTS.md` with an "After lever 1" table
    holding all four durations WITH their test counts, and a delta row stating the absolute and
    percentage change for each task. State the win against wall-clock, and note explicitly that
    wall-clock and summed test time differ (Gradle startup, compilation, container start, and Spring
    context boots are in the former and untouched by this lever) so the number is not overclaimed.
  </action>
  <verify>
    <automated>./gradlew spotlessCheck &amp;&amp; ./gradlew test --tests '*PasswordEncoderStrengthTest' --tests '*AuthenticationTest' --tests '*SigninTimingEqualizationTest'</automated>
    <automated>./gradlew test --rerun-tasks --console=plain</automated>
    <automated>grep -c 'Baseline' .planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md</automated>
  </verify>
  <done>
    `260811-ixj-MEASUREMENTS.md` holds eight timings — two `test` and two `fastTest` before, two of
    each after — every one paired with a test count, plus a delta row per task. `BeanConfiguration`
    injects the cost factor with a fallback of 10; `application-test.properties` sets 4;
    `PasswordEncoderStrengthTest` passes and was seen red before it passed. The full suite is green
    with a test count equal to or greater than the baseline count recorded in step 1.
  </done>
  <reversibility rating="reversible">The whole lever is one bean parameter plus one properties line; reverting restores the prior behavior exactly, and the guard test makes an accidental production-side change loud rather than silent.</reversibility>
</task>

<task type="auto">
  <name>Task 2: Measure Gradle fork-level parallelism at 2 and 4, adopt a value only if it wins</name>
  <files>build.gradle, .planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md</files>
  <read_first>build.gradle (the fastTest task registration, lines 186-206)</read_first>
  <precondition>Task 1 is committed and the suite is green — the optimal fork count is different once BCrypt's CPU-bound cost has been removed, so measuring this before Task 1 lands would answer a question about a tree that no longer exists.</precondition>
  <action>
    Set `maxParallelForks = 2` on the `fastTest` task only. Run `./gradlew fastTest --rerun-tasks
    --console=plain` twice, recording durations and test counts. While a run is in flight, capture
    `docker ps --format '{{.Image}}'` at least once and record how many PostgreSQL and how many
    Redpanda containers are alive simultaneously — the Redpanda count is the number that matters,
    since Docker on this machine has 7.728 GiB total and `fastTest` already pulls in one
    Redpanda-backed class (see Task 3's todo).

    Repeat at `maxParallelForks = 4`: two runs, durations, test counts, live container census.

    Do not set `forkEvery`. Its default of 0 means one JVM handles all the classes assigned to it;
    any non-zero value would restart the JVM — and therefore the Testcontainers static initializers,
    and therefore the containers — far more often, which on this codebase is catastrophic rather
    than merely slower.

    Then decide from the numbers, not from preference:
    - If a fork count beat 1 by more than this project's ~18s run-to-run variance on both of its
      runs, keep it on `fastTest` and add a comment above it recording the measured numbers that
      justified the value, the fact that fork-level parallelism is safe here because each fork is a
      separate JVM with its own static Testcontainers instance and therefore its own database, and
      the `forkEvery`-stays-at-its-default constraint with the reason above. Then measure the same
      value on `test` twice before deciding whether to apply it there too — `test` runs the full
      Kafka tier, so its container-memory profile is different and a win on `fastTest` does not
      transfer by default.
    - If neither count beat 1 by more than the variance, or if any run failed on container startup,
      port exhaustion, or memory, revert `build.gradle` to untouched and record the outcome. Not
      adopting a value is a legitimate, useful result here — it is the same disposition the existing
      Testcontainers-reuse investigation reached, and recording it prevents the question being
      reopened blind.

    Append an "Lever 2: maxParallelForks" section to `260811-ixj-MEASUREMENTS.md` with every run at
    every fork count, the container census, and the decision with its justifying numbers. Any run
    that failed goes in the table too, with its failure mode — a fork count that produced flaky
    container startup is a finding, not a run to quietly discard.
  </action>
  <verify>
    <automated>./gradlew spotlessCheck &amp;&amp; ./gradlew fastTest --rerun-tasks --console=plain</automated>
    <automated>grep -c 'maxParallelForks' .planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-MEASUREMENTS.md</automated>
    <automated>grep -v '^\s*//' build.gradle | grep -c 'forkEvery' | grep -qx 0 &amp;&amp; echo "forkEvery not set: OK"</automated>
  </verify>
  <done>
    `260811-ixj-MEASUREMENTS.md` records at least four `fastTest` runs — two at 2 forks, two at 4 —
    with durations, test counts and live container counts, and states the decision with the numbers
    that drove it. `build.gradle` either carries a measured `maxParallelForks` value with an
    explanatory comment, or is byte-identical to its pre-task state. `forkEvery` is not set anywhere.
    The suite is green at whatever configuration ends up committed.
  </done>
  <reversibility rating="reversible">One Gradle property on one task; removing the line restores serial execution with no other consequence.</reversibility>
</task>

<task type="auto">
  <name>Task 3: Record the findings durably, file the Redpanda-in-the-gate side finding, close the source todo</name>
  <files>
    docs/LOCAL_DEV.md,
    .planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md,
    .planning/todos/completed/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md
  </files>
  <read_first>
    docs/LOCAL_DEV.md (the "Testcontainers reuse: evaluated, not enabled" section, lines 147-204 — match its shape),
    .planning/todos/pending/2026-08-10-investigate-refactoring-existing-tests-to-parameterized.md (todo frontmatter shape),
    src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructorTest.java (lines 1-45)
  </read_first>
  <action>
    Add a new section to `docs/LOCAL_DEV.md` — place it adjacent to "Testcontainers reuse: evaluated,
    not enabled" and write it in that section's register: measured numbers first, decision second,
    reasoning enumerated, and a "revisit if" clause. It must record:

    - The measured before/after wall-clock for both `test` and `fastTest`, from
      `260811-ixj-MEASUREMENTS.md`, with test counts, so the durable record carries the evidence and
      not just the conclusion.
    - What was pulled: the test-profile cost factor, where the property lives, and why that satisfies
      rule 8 (a version-controlled properties file, not a developer's machine).
    - The security trade-off, stated plainly: the test profile now exercises a weaker cost factor
      than production; BCrypt's cost parameter changes only the number of rounds, not the output
      format or `matches()` semantics, so this is theoretical rather than practical; the structural
      mitigations are the production-valued fallback and `PasswordEncoderStrengthTest`; and the
      residual that neither mitigation covers is a deploy-time environment-variable or
      `SPRING_APPLICATION_JSON` override, which is stated rather than claimed closed.
    - What Task 2 measured about fork-level parallelism and what was decided.
    - JUnit 5 in-JVM parallel execution, recorded as rejected, with all five blockers named
      concretely enough that a future reader can check them: the unscoped `userService.deleteAll()`
      in `cleanup()`; `SessionFactory`-global Hibernate statistics behind `countQueries()`;
      `AbstractKafkaContainerTest`'s mutable static whose own Javadoc names sequential execution as
      its precondition; the single shared Kafka topic and consumer group; and positional assertions
      over shared tables. Include why `@ResourceLock` does not rescue it — the shared resource is
      the database, which every fixture-bearing test writes to, so correct locking re-serializes
      precisely the work that constitutes the cost.
    - Per-tier Gradle task splitting, recorded as rejected because Gradle's `--parallel` operates
      across subprojects and this is a single-project build, so tier tasks would run sequentially
      while each paid its own JVM startup, container start and context boots.
    - The remaining headroom that was deliberately not taken: trimming `AbstractAppTest`'s per-test
      fixture, ~29s, blocked behind ~29 dependent classes including positional assertions.

    File a new todo at
    `.planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md`,
    with frontmatter matching the existing pending todos (created, title, area: testing, severity:
    minor, files). Its problem statement: `HistoricalActivityEventReconstructorTest` extends
    `AbstractKafkaContainerTest` but carries no tag, and since `fastTest`'s gate membership is
    opt-in by tag (D-21, D-22), it runs inside the pre-commit gate and starts a Redpanda container
    on every commit. Cite the evidence — its own class declaration, and the presence of its results
    under `build/test-results/fastTest/`. Note the interaction this task surfaced: under
    `maxParallelForks = N` the gate could start up to N Redpanda containers against a 7.728 GiB
    Docker budget, so this is a live constraint on fork count and not only a latency annoyance.
    Frame it as a decision, not a fix: tagging it removes real coverage from the gate, leaving it
    untagged keeps the gate paying for a broker, and a third option is splitting what the class
    proves across tiers. Do NOT change the class in this plan.

    Close the source todo by moving
    `.planning/todos/pending/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md`
    into `.planning/todos/completed/` with `git mv` (create the directory if it does not exist),
    appending a Resolution section that answers the todo's three named candidates in the order it
    listed them, states the measured outcome, and points at the `docs/LOCAL_DEV.md` section for the
    durable record. Correct the todo's own framing where the measurement contradicted it: it
    proposed fixture cost as candidate 3 and framed parallelization as the primary lever, whereas
    the measurement found the dominant cost was neither — it was key-stretching inside the fixture,
    a fourth lever nobody had listed. Say so, in the same spirit the todo asked for
    ("measure before choosing").
  </action>
  <verify>
    <automated>test -f .planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md &amp;&amp; test ! -f .planning/todos/pending/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md &amp;&amp; test -f .planning/todos/completed/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md</automated>
    <automated>grep -c 'maxParallelForks' docs/LOCAL_DEV.md</automated>
    <automated>git diff --exit-code -- src/test/java/com/vrudenko/kanban_board/activitylog/HistoricalActivityEventReconstructorTest.java &amp;&amp; echo "side finding filed, not fixed: OK"</automated>
  </verify>
  <done>
    `docs/LOCAL_DEV.md` carries a measured, self-contained account of what was pulled, what was
    rejected and why, including the security trade-off and the residual it does not cover. The new
    todo exists and `HistoricalActivityEventReconstructorTest` is unmodified. The source todo is in
    `.planning/todos/completed/` with a Resolution that answers all three of its original candidates
    and names the fourth lever the measurement actually found.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| unauthenticated client → `POST /api/signin`, `POST /api/signup` | Attacker-controlled credentials reach `PasswordEncoder.matches`/`encode`; the cost factor configured by this plan is the work an offline attacker must repeat per guess against a stolen hash |
| build/deploy configuration → running application | Whichever Spring profile and property sources are active at deploy time determine the cost factor the production encoder is constructed with |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-ixj-01 | Information Disclosure | `BeanConfiguration.passwordEncoder` | critical | mitigate | The lowered cost factor is defined only in `application-test.properties`; the `@Value` fallback is the production value 10, so absence of configuration fails safe. `PasswordEncoderStrengthTest` asserts the fallback string reflectively and asserts the key is absent from the default properties file — both go red on a change that would weaken production. |
| T-ixj-02 | Tampering | deploy-time property sources | medium | accept | An environment variable or `SPRING_APPLICATION_JSON` entry could set the key in production, which no in-repo test can observe. Accepted and stated explicitly in `docs/LOCAL_DEV.md` rather than claimed closed; the property name is new and appears nowhere in deployment configuration today. |
| T-ixj-03 | Information Disclosure | signin timing side-channel (F1 fix) | medium | mitigate | `AuthenticationController`'s equalizer hash is derived from the injected bean at `@PostConstruct`, so both signin branches stay cost-matched at any configured factor. Task 1 runs `SigninTimingEqualizationTest` explicitly and reports it by name; its assertion is on `matches()` call count, not elapsed time, so the equalization property is preserved, only cheaper. |
| T-ixj-04 | Denial of Service | `maxParallelForks` × Testcontainers | low | mitigate | N forks means N PostgreSQL and potentially N Redpanda containers against a 7.728 GiB Docker budget; exhaustion presents as flaky local/CI container startup. Task 2 censuses live containers during measurement and records any startup failure as a finding rather than discarding the run; no value is committed that did not measure clean. |
| T-ixj-SC | Tampering | npm/pip/cargo installs | n/a | accept | No package-manager install occurs in this plan — the only dependency-adjacent file touched is `build.gradle`, and only to set a Gradle `Test` task property. No new coordinate is added, so the package-legitimacy gate does not apply. |
</threat_model>

<verification>
- `./gradlew spotlessCheck` passes.
- `./gradlew test` passes with a test count greater than or equal to the baseline recorded in Task 1
  step 1 — a faster suite that shrank is a regression, not a win.
- `PasswordEncoderStrengthTest` passes, and its red-before-green transition is reported in the
  summary rather than asserted.
- `AuthenticationTest` and `SigninTimingEqualizationTest` pass unmodified — neither file appears in
  `git diff` for this plan.
- `git diff` shows no change to `src/main` other than `BeanConfiguration.passwordEncoder`'s
  signature, its constructor argument and its Javadoc.
- `260811-ixj-MEASUREMENTS.md` contains at least twelve recorded runs (4 baseline, 4 after lever 1,
  4 fork-count runs), each with a duration and a test count.
- `docs/LOCAL_DEV.md` names both rejected levers with their reasoning.
- `HistoricalActivityEventReconstructorTest` is unmodified.
</verification>

<success_criteria>
- The test-profile BCrypt cost factor is 4, sourced from a version-controlled properties file, with
  production unchanged at 10 and that fact guarded by a test rather than a claim.
- Before/after wall-clock for `./gradlew test` and `./gradlew fastTest` is recorded twice each, with
  test counts, and the reported win is stated against wall-clock with the wall-clock-vs-summed-test-
  time distinction made explicit.
- `maxParallelForks` is either committed at a measured value with the numbers that justified it, or
  explicitly not adopted with the numbers that ruled it out.
- JUnit 5 in-JVM parallelism and per-tier task splitting are recorded as evaluated-and-rejected in
  `docs/LOCAL_DEV.md` with reasoning specific to this codebase.
- The untagged Kafka-container class in the pre-commit gate is filed as a todo and left unmodified.
- The source todo is closed with a Resolution that answers its three named candidates and names the
  fourth lever the measurement found.
</success_criteria>

<output>
Create `.planning/quick/260811-ixj-investigate-and-implement-test-suite-spe/260811-ixj-SUMMARY.md` when done.

The summary must report, as numbers rather than adjectives: the eight lever-1 timings with their
test counts, the fork-count timings and container census, the final full-suite test count against
the baseline count, and the three named classes (`PasswordEncoderStrengthTest`,
`AuthenticationTest`, `SigninTimingEqualizationTest`) with their observed results.
</output>
