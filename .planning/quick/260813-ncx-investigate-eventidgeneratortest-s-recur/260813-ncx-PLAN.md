---
phase: quick-260813-ncx
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - .planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-RAW.txt
  - .planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-FINDINGS.md
  - src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java
  - src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
  - .planning/todos/pending/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md
  - .planning/todos/completed/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md
  - .planning/STATE.md
autonomous: true
requirements:
  - QUICK-260813-NCX-EVENTIDUNIQUENESSFLAKE

estimate:
  tokens: 52000
  raw_tokens: 52000
  tasks: 2
  confidence: low

must_haves:
  truths:
    - "Whether the 999/1000 observation is an inherent property of the generator's design or a defect in the generator is decided by measurement over many trials, not by reading the code and reasoning (D-01)"
    - "A throwaway probe calls the real production classes (`new EventIdGenerator().generate()`, one reused instance) and reports, per trial: distinct count, decoded per-millisecond bucket sizes, every duplicate pair's decoded halves, and the random component's repeat structure (D-02, D-03)"
    - "The observed rate of colliding trials is compared against a birthday-collision expectation computed from the *observed* per-millisecond bucket sizes, so the comparison does not depend on any assumption about loop speed (D-03)"
    - "Raw probe output is written under `build/` and copied into `PROBE-RAW.txt`; `PROBE-FINDINGS.md` carries the reading of it plus exactly one machine-readable `VERDICT:` token that drives Task 2's branch (D-04)"
    - "The probe class never enters git history and does not exist on disk after Task 1 — it is created untracked, run, copied out, and deleted before Task 1's own commit (D-04)"
    - "Under verdict `INHERENT_BIRTHDAY` the test is corrected, not the generator: the method is renamed off its overstated claim, the assertion becomes a threshold derived from the measurement, and the Javadoc records the bit layout, the measured rate, and the false-failure probability (D-05)"
    - "Under verdict `GENERATOR_DEFECT` the generator is fixed and the test's exact-distinctness assertion is left standing (D-05)"
    - "Whatever threshold ships still catches the regression the test exists to catch — proven by falsification, not asserted: with the random component forced constant the test goes red, and restoring it goes green with zero net production diff (D-07)"
    - "The production consequence of an id collision — `ActivityLogRecorder.persist`'s `existsByEventId` fast path treating a genuinely distinct event as a redelivery and silently dropping it — is quantified at production event volume and either dismissed with the number or filed as a new todo (D-08)"
    - "`./gradlew spotlessCheck test` passes on the shipped tree, and the repaired test is re-run three times in a row without a failure (D-09)"
    - "The source todo is moved from `.planning/todos/pending/` to `.planning/todos/completed/` with a `## Resolution` section reporting the measured numbers, not the theory (D-10)"
  artifacts:
    - ".planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-RAW.txt — raw, machine-emitted probe observations from the decisive run. Committed as evidence."
    - ".planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-FINDINGS.md — interpretation, the observed-vs-predicted comparison, the production-volume computation, and one `VERDICT:` line."
    - "src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java — under `INHERENT_BIRTHDAY`, the uniqueness test renamed, re-asserted against a derived threshold, and documented. Under `GENERATOR_DEFECT`, untouched."
    - "src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java — under `INHERENT_BIRTHDAY`, a measured collision rate appended to the existing same-millisecond note, comment-only. Under `GENERATOR_DEFECT`, a real algorithm change."
    - ".planning/todos/completed/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md — the source todo, relocated, with an evidence-bearing `## Resolution`."
  key_links:
    - "`RandFlakeGenerator`'s own class comment (lines 25-32) already states the property under investigation in plain words: 'the low bits are random, not a sequence counter, so same-millisecond collisions were always possible at the same probability.' That is design intent on the record, written deliberately by quick task 260802-tbj. It makes `INHERENT_BIRTHDAY` the likely verdict — but it is a *claim about* the code, and this task exists because a claim about the code is exactly what needs checking. Measure anyway; the probe is cheap."
    - "`EventIdGeneratorTest` already contradicts itself. Its third test's Javadoc (lines 56-68) says the low 23 bits are random rather than a counter, so an assertion about two ids generated in the same millisecond 'would be flaky by construction' — and then the second test asserts exactly 1000 distinct values across 1000 calls that mostly land in the same millisecond. The fix is not merely a threshold change; it is making one class stop asserting two incompatible things about one generator."
    - "An id collision is not confined to this test. `ActivityLogRecorder.persist` short-circuits on `activityLogRepository.existsByEventId(...)` and the `uk_activity_log_event_id` unique constraint backs it, so two events that collide on `eventId` mean the second is silently swallowed as a redelivery — no exception, no dead letter, one activity row that should have been two. That is the reason the collision rate is worth a number rather than a shrug, even if the test's tolerance is what changes."
    - "`ActivityReadTest:249` asserts `doesNotHaveDuplicates()` over collected event ids, so it shares the same exposure at much lower volume. It is deliberately NOT changed here — naming it matters so a future flake there is recognised immediately instead of re-investigated from scratch."
    - "This flake blocks commits, not just CI. `build.gradle`'s `fastTest` (run by `.githooks/pre-commit`) excludes only classes tagged `kafka` or `realSocket`; `EventIdGeneratorTest` carries no tag, so every occurrence refuses somebody's commit."
    - "Weakening an assertion to make a red test green is normally the wrong move, and the plan must not be allowed to do it by default. The protection is D-07's falsification: any shipped threshold has to be demonstrated to still go red when the random component is removed. A threshold that survives that is a corrected claim; one that does not is a suppressed test."
---

<objective>
Decide by measurement whether `EventIdGeneratorTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly`'s
999-of-1000 failure is the generator behaving exactly as designed or the generator being wrong — then
fix whichever one measurement convicts.

`RandFlakeGenerator` composes an id as `(millisSinceCustomEpoch << 23) | random23Bits`. It holds no
counter, no dedupe and no retry, and its own comment says same-millisecond collisions were always
possible. So there is a strong prior that 1000 calls in a tight loop — which mostly land inside one
or two milliseconds — are drawing 1000 times from a space of 8,388,608 values per millisecond, where
a birthday collision is an ordinary event rather than a bug. That prior is not evidence. Task 1
turns it into evidence or falsifies it.

Purpose: this flake refuses commits (it runs in the pre-commit `fastTest` gate) and has been
rediscovered three times. Ending it means the next agent inherits a number, a derivation and a
falsification, not a third round of "probably a race condition".
Output: raw probe evidence in the plan directory, exactly one of the two branches applied, the
retained regression teeth proven by falsification, the production-volume consequence quantified, and
the source todo closed with measurements.
</objective>

## Approach & Trade-offs

**Decisions locked for this task** (derived from the task constraints plus facts confirmed by
reading `RandFlakeGenerator`, `EventIdGenerator`, `EventIdGeneratorTest`, `ActivityLogRecorder`,
`build.gradle`'s `fastTest` block and `docs/CODE_STYLE.md` rules 4/5/13 during planning):

- **D-01** — **Measure first, edit second.** Task 1 measures and records a verdict; Task 2 is the
  only task that changes behaviour or prose, and its branch is selected by that recorded verdict. The
  generator's own comment asserting that same-millisecond collisions are expected is treated as a
  hypothesis to test, not as the answer. Reading code and reasoning about probability is what the
  source todo already did; it produced two candidate explanations and picked neither.
- **D-02** — **Probe technique: a throwaway plain-JUnit class calling the real production classes.**
  `EventIdGenerator` constructs its own `RandFlakeGenerator` with `new` and has no injected
  collaborators, so `new EventIdGenerator().generate()` exercises the exact production code path the
  failing test exercises, with no Spring context and no PostgreSQL container. One instance is reused
  across all calls in a trial, matching the autowired singleton the real test uses. No copy of the
  algorithm is made anywhere (see matrix row C — measuring a copy would be measuring the wrong thing).
- **D-03** — **The probe answers four questions, each with the observation supporting it, over
  T >= 200 independent trials of 1000 calls:**
  - **Q1 — the raw rate.** In how many trials is the distinct count below 1000, and what is the
    distribution of the shortfall? Report the trial count, the failing-trial count, and min/max/mean
    distinct.
  - **Q2 — is the rate what the design predicts?** Decode every returned id (`Long.parseLong(id, 36)`,
    then `timestamp = value >>> 23`, `random = value & 0x7FFFFF`) and bucket the 1000 calls by decoded
    timestamp. For each trial compute the birthday prediction from the *observed* bucket sizes:
    `p = 1 - product over buckets of exp(-k*(k-1) / (2 * 2^23))`. Sum across trials to get the
    expected number of colliding trials `E`, and compare with the observed count `C`. Because the
    prediction is computed from the buckets actually observed, it does not depend on any guess about
    how fast the loop runs on this machine.
  - **Q3 — does the decode validate, and is the random component structureless?** Decoded timestamps
    must be non-decreasing within a trial and must fall inside the trial's own wall-clock window
    (`Instant.now().toEpochMilli()` captured immediately before and after, minus the custom epoch
    1672531200000). Every duplicate pair must agree on *both* decoded halves — it must, since equal
    strings mean equal longs, so this is a decode-validity check rather than a discriminator. Also
    report, across all draws, how many distinct random values were seen, the most-repeated random
    value and its count, and how many times two *consecutive* draws were equal — a re-seeding or
    shared-state defect in `ThreadLocalRandom` usage would show up here and nowhere else.
  - **Q4 — the production-rate figure.** Repeat the measurement at a production-representative call
    count (a single HTTP mutation publishes one event; take 20 back-to-back calls as a generous upper
    bound for one request) and, separately, compute the expected number of colliding pairs across
    1,000,000 lifetime events under the observed same-millisecond clustering. This is the number
    `ActivityLogRecorder`'s silent-dedupe exposure is judged against (D-08).
- **D-04** — **Evidence outlives the probe, and the probe never enters git.** The probe writes its
  raw output to `build/probe/randflake-probe-raw.txt` (truncating, not appending — `build/` is
  git-ignored) and the executor copies the decisive run into `PROBE-RAW.txt` in this plan's directory.
  Writing directly into `.planning/` is forbidden: quick task 260813-m9x had a pre-commit `fastTest`
  re-run append duplicate observations to an already-committed evidence file, and a re-run here would
  additionally overwrite the exact numbers `PROBE-FINDINGS.md` quotes. The probe class is created
  untracked, run, copied out, and **deleted inside Task 1 before Task 1's own commit**, so no commit
  in this task ever happens with it present. `PROBE-FINDINGS.md` carries exactly one line matching
  `VERDICT: <token>`, token one of `INHERENT_BIRTHDAY`, `GENERATOR_DEFECT`, `DECODE_INVALID`.
- **D-05** — **The verdict decides which artefact is wrong, on stated numeric criteria, not on
  judgement:**
  - `INHERENT_BIRTHDAY` requires all of: `C <= E + 3*sqrt(E)` (observed colliding trials not
    materially above the birthday expectation), every duplicate pair agreeing on both decoded halves,
    the decode validating per Q3, and no random value repeating more than chance allows. Fix the
    **test**.
  - `GENERATOR_DEFECT` if any of: `C > E + 3*sqrt(E)` and `C >= 2*E`; or decoded timestamps go
    backwards; or a random value or consecutive-draw repeat appears at a rate the uniform draw cannot
    explain. Fix the **generator**.
  - `DECODE_INVALID` if the decode does not validate. Conclude nothing, stop, and surface it — a
    failed decode means the probe is measuring something other than what it thinks it is.
- **D-06** — **Under `INHERENT_BIRTHDAY`, the test fix has a locked shape.** Rename the method to
  `shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly` (rule 5's
  `should<Outcome>_when<Condition>` form; the current name states a guarantee the generator does not
  make, and a name that overstates is how this got asserted in the first place). Replace
  `hasSize(callCount)` with `hasSizeGreaterThanOrEqualTo(MIN_DISTINCT_IDS)` against a named constant
  whose value is **derived from the measurement**, subject to two floors that must both be computed
  and recorded: `P(distinct < MIN_DISTINCT_IDS)` under the measured bucket distribution must be below
  1e-9, and `MIN_DISTINCT_IDS` must exceed 50x the distinct count a stubbed or entropy-free delegate
  would produce (which is bounded by the number of distinct milliseconds a trial spans — single
  digits). Do not adopt a threshold from this plan; 990 is an illustration of the shape, not a value
  to copy. The method Javadoc records the bit layout, T, C, E, the chosen threshold, its false-failure
  probability, and a pointer to `PROBE-FINDINGS.md`. Update the class Javadoc's "real, distinct,
  time-ordered id source" wording so the class no longer claims exhaustive distinctness while its own
  third test explains why that cannot hold.
- **D-07** — **Any weakened assertion must be proven to still have teeth, before it ships.**
  Temporarily replace the random draw in `RandFlakeGenerator.generateRandflake()` with a constant,
  run `./gradlew test --tests '*EventIdGeneratorTest*'`, and confirm the repaired test goes RED.
  Restore, confirm GREEN, and confirm the production file is byte-identical to its pre-falsification
  state. Do this *before* the D-08 comment edit so the diff check is unambiguous. Do not commit while
  the constant is in place — `.githooks/pre-commit` runs `fastTest` and `--no-verify` is forbidden.
- **D-08** — **Quantify the production consequence; file, do not fix.** `ActivityLogRecorder.persist`
  treats a repeated `eventId` as a redelivery and returns without inserting. A genuine collision
  therefore loses an activity row silently. If Q4's expected colliding pairs across 1,000,000 lifetime
  events is >= 0.01, file a new `[minor]` pending todo naming `ActivityLogRecorder.persist`, the
  `uk_activity_log_event_id` constraint and the measured figure — do **not** widen the id, add a
  counter, or change the dedupe here. If it is below 0.01, record the number in `PROBE-FINDINGS.md`
  and in the todo Resolution and file nothing. Either way, `RandFlakeGenerator`'s existing
  same-millisecond comment gains the measured rate in at most two sentences, comment-only, zero
  behavioural change (under `INHERENT_BIRTHDAY`).
- **D-09** — **Green on the shipped tree, and demonstrably not flaky.** `./gradlew spotlessApply`
  after the Java edits, then `./gradlew spotlessCheck test` on the final tree (probe already deleted),
  bounded to 15 minutes. Additionally run `./gradlew test --tests '*EventIdGeneratorTest*'
  --rerun-tasks` three times in a row and record all three results — a smoke check only; the
  statistical evidence comes from the probe's T trials, not from three suite runs.
- **D-10** — **Close the source todo** by `git mv` into `.planning/todos/completed/`, appending a
  `## Resolution` in the shape used by
  `.planning/todos/completed/2026-08-11-taskservice-deleteallbycolumnid-has-zero-production-callers.md`.
  It names this quick task, states the verdict token, quotes T/C/E and the chosen threshold, says
  which artefact was corrected and which was deliberately left alone, and points at `PROBE-RAW.txt`.
  The file's existing `## Resolved:` section for the already-fixed `ColumnLockingTest` half stays
  untouched.
- **D-11** — **Out of scope, explicitly.** Do not change `EventIdGeneratorTest`'s `@SpringBootTest` /
  `AbstractPostgresContainerTest` inheritance (its class Javadoc already explains why a container is
  needed to boot the context, and downgrading the tier is a separate decision). Do not touch
  `ActivityReadTest:249`. Do not add a `@Tag` to exclude this class from `fastTest` — muting the gate
  is not fixing the flake. Do not add a retry/rerun plugin.

**Alternates considered — how to settle inherent-vs-defect:**

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A. Throwaway plain-JUnit probe over T>=200 trials, decoding each id and comparing observed collisions against a birthday prediction computed from the observed per-millisecond buckets** (chosen) | + Self-calibrating: the prediction is built from the buckets actually measured, so it cannot be wrong about how fast this machine's loop runs. + Distinguishes the two branches on a stated numeric criterion rather than on how the reviewer feels about probability. + Decoding recovers the timestamp and random halves exactly from the returned string, so no instrumentation is added to production code and no timing is perturbed. − Needs ~200k generate calls (milliseconds of CPU) and a bit of arithmetic. − Depends on the 23-bit layout for decoding, so a layout change would need the probe updated — mitigated by Q3's decode-validation gate, which fails loudly instead of quietly mis-measuring. | Picked. It is the only option that produces a number the *fix* can then be derived from, which D-06 requires. |
| **B. Re-run the existing test N times in CI and count failures** | + Zero new code, measures the exact thing that flakes. − Each run boots a Spring context and a PostgreSQL container, so 200 trials is hours, and 5 trials is statistically worthless against a ~5% event. − Yields a failure rate and nothing else: no bucket sizes, no duplicate structure, so it cannot separate "birthday collision" from "correlated randomness", which is the actual question. | Rejected as primary. The three-rerun smoke check in D-09 is this approach kept only for what it is good at — confirming the shipped test does not trivially re-flake. |
| **C. Copy the 8-line algorithm into a standalone scratch program and measure the copy** | + No repo file created at all. − Measures a transcription. If the defect were a subtle usage error (`ThreadLocalRandom.current()` called in a way that shares state, an off-by-one in the shift), a careful copy would silently correct it and prove the wrong thing. | Rejected. The whole point is to measure the shipped code. |
| **D. Single-file source launcher (`java -cp build/classes/java/main Probe.java`) against the compiled production class** | + No file inside `src/`, so no interaction with the pre-commit gate at all. − `RandFlakeGenerator implements org.hibernate.id.IdentifierGenerator`, and the JVM resolves superinterfaces at class load, so the run needs Hibernate on the classpath; assembling that without a `build.gradle` change means a Gradle init script or hand-picking jars out of the Gradle cache. − Buys nothing over A once D-04 deletes the probe before any commit. | Rejected. Recorded because it looks obviously cleaner than A until the superinterface-loading problem surfaces. |
| **E. Add statistics to production code (a collision counter / a debug log in the generator)** | + Would measure real production traffic rather than a synthetic loop. − Ships instrumentation into the hot path of every entity insert to answer a test question. − The production event rate is far too low to observe a ~1e-7-per-same-millisecond-pair event in any useful time. | Rejected outright. |
| **F. Skip the investigation: raise the tolerance to 999 and move on** | + Ten seconds of work. − 999 is not derived from anything, and P(>=2 collisions) at this call rate is roughly 1-in-several-hundred runs, so it re-flakes later with the investigation trail already closed. − Leaves the `ActivityLogRecorder` silent-drop consequence undiscovered. | Rejected. This is the failure mode the task exists to prevent; the threshold has to come out of D-06's two floors. |

**Alternates considered — which test fix, under `INHERENT_BIRTHDAY`** (the task constraints named
three candidates; D-06 picks the first, and the other two are rejected on the record rather than
ignored):

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **T-A. Relax to a measurement-derived threshold, keeping 1000 rapid calls** (chosen, D-06) | + Preserves the condition the test exists to exercise — rapid sequential calls — while asserting only what the generator actually guarantees. + The threshold comes out of measured data with a stated false-failure probability, so it is defensible rather than arbitrary. + Retains full detection power against the regression that matters: an entropy-free delegate scores ~the millisecond count, orders of magnitude below any threshold that clears D-06's floors. − Needs the arithmetic done and written down, and a falsification run to prove the teeth. | Picked. It is the only candidate that fixes the *claim* rather than the *conditions*, which is what "correct the test's assumption" means. |
| **T-B. Force a distinct millisecond between calls, keeping exact distinctness** | + Exact 1000-of-1000 becomes genuinely true, no tolerance anywhere. − Costs at least one millisecond per call and in practice far more: `Thread.sleep(1)` on Windows rounds up to the platform timer granularity, so 1000 calls is tens of seconds inside a pre-commit gate. − Deletes the `whenCalledManyTimesRapidly` condition entirely, so the test stops covering the case it was written for. − Redundant: the class's third test already generates across a millisecond boundary and asserts on the result. | Rejected. It buys exactness by removing the scenario, and the scenario is the point. |
| **T-C. Reduce the call count to a volume the generator's guarantee covers** | + Smallest edit, keeps `hasSize` exact. − There is no call volume at which distinctness is *guaranteed* — the probability shrinks, it never becomes zero, so this converts a ~1-in-17 flake into a rarer one with a longer fuse and a closed investigation trail. − Fewer ids means less evidence: 20 draws cannot distinguish a healthy generator from a degraded one, so detection power drops precisely where T-A's keeps it. | Rejected as the primary fix. The production-rate figure it would encode is still worth having, which is why D-03's Q4 measures it as *evidence* rather than shipping it as the assertion. |
| **T-D. Decode the ids in the test and assert on the random component's distribution directly** | + Tests entropy at the source instead of inferring it from string distinctness. − Couples a test to the private bit layout (shift width, epoch), so a layout change reds the test for a reason unrelated to the behaviour under test. − The probe does exactly this already, as throwaway code, which is where layout coupling belongs. | Rejected for the shipped test; used inside the Task 1 probe, which is deleted the same day. |

**Alternates considered — what to do if the verdict is `GENERATOR_DEFECT`:** the plan deliberately
does not pre-pick a remedy, because the remedy depends on which defect the probe finds. It does fix
the constraints any remedy must respect, so the executor does not have to rediscover them: a
per-millisecond sequence counter or a last-timestamp field reintroduces exactly the shared mutable
state quick task 260802-tbj removed the `synchronized` modifier over — that method's comment says
"if mutable state ... is ever added here, revisit this", and revisiting means restoring
synchronisation or moving to an atomic, on the `IdentifierGenerator` behind `@RandFlakeId` for every
entity insert in the application. Widening the random field changes the rendered Base36 string width,
which `EventIdGeneratorTest`'s third test Javadoc already flags as breaking lexicographic ordering,
and `event_id` is a `varchar` since `V6__change_activity_log_event_id_to_varchar.sql`, so existing
rows would sort against a different width. Any such change is a behavioural change to every entity
id in the system and should be surfaced before it is implemented.

**Non-obvious trade-offs:**

- *The dangerous outcome of this task is a suppressed test, not a wrong verdict.* Both branches end
  with a green suite, and only one of them ends with a test that still detects anything. D-07's
  falsification is the entire defence: a threshold nobody proved red-able is indistinguishable from a
  disabled assertion. Run it before the comment edit, keep the RED output, and quote it in the SUMMARY.
- *The math is only as good as its bucket data.* The birthday approximation
  `1 - exp(-k(k-1)/2N)` is accurate for `k << sqrt(N)`; at k=1000 against N=8,388,608 that holds
  comfortably, but if the machine is slow enough that a trial spans many milliseconds the per-bucket k
  drops and the predicted rate falls with it. That is fine and is the point of computing the
  prediction per trial from observed buckets — but it means `E` is a property of the machine, and
  `PROBE-FINDINGS.md` must say so rather than presenting `E` as a universal constant. A CI machine
  with a different loop speed will have a different `E` and the *same* verdict.
- *Correctness, not just tidiness, is at stake downstream.* A collision costs one activity-log row,
  silently, through the `existsByEventId` fast path — no exception, no dead letter, nothing in a log.
  That is the only genuinely user-visible consequence anywhere in this investigation, and it is why
  D-08 makes the executor produce a number for it even under the "the test was too strict" branch.
- *Performance:* the probe is ~200k in-process calls with no I/O and no container — under a second of
  CPU. The shipped change adds no runtime cost either way; under `INHERENT_BIRTHDAY` the test does the
  same 1000 calls it does today. Memory: one `HashSet` of 1000 short strings per trial, released per
  trial; the probe must not retain all T trials' id sets simultaneously.
- *Supply chain:* no dependency added, no `build.gradle` change, no package-manager install — JUnit 5,
  AssertJ and the JDK are all already present, so no package-legitimacy gate applies.
- *Security:* nothing here touches authentication, authorization or a request path. The one adjacent
  security-shaped question — whether an id whose low 23 bits are `ThreadLocalRandom` is predictable
  enough to matter — is out of scope, because `event_id` is never a capability: every read path is
  ownership-verified and no endpoint accepts an `event_id` as an authorization token. Note it in the
  findings if the probe surfaces anything odd about the random component; do not act on it here.

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| (none crossed at runtime) | Under `INHERENT_BIRTHDAY` this is a test-assertion and comment change only. No request path, filter chain, bean, data path or build input is modified. |
| test-process → repository working tree | The throwaway probe writes an evidence file; the executor copies it into `.planning/`, where it becomes committed public repo content. |
| (conditional) app → `activity_log` | Under `GENERATOR_DEFECT` the change would alter every entity id and every `event_id` in the system, crossing into persisted data whose uniqueness constraint and rendered width are already relied upon. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-ncx-01 | Tampering (test-suite integrity) | `EventIdGeneratorTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly` | high | mitigate | Relaxing an assertion to silence a red test is the natural and wrong fix here. Mitigated by D-06's two computed floors (false-failure probability below 1e-9 *and* at least 50x a stubbed delegate's distinct count) and by D-07's falsification, which requires the shipped threshold to be observed going red with the random component removed. |
| T-ncx-02 | Repudiation (integrity of engineering claims) | `RandFlakeGenerator` comment, `EventIdGeneratorTest` class Javadoc | medium | mitigate | The class already asserts two incompatible things about one generator, which is how this was rediscovered three times. D-06 requires the surviving prose to carry the measured numbers and the bit layout, so the next reader inherits evidence rather than a third opinion. |
| T-ncx-03 | Denial of service (developer throughput) | `.githooks/pre-commit` → `fastTest` | medium | mitigate | The flake refuses commits at ~1-in-20 and D-11 forbids the cheap escape of tagging the class out of the gate. The fix must make the gate reliable, and D-09's three consecutive reruns are the smoke check that it did. |
| T-ncx-04 | Tampering (silent data loss) | `ActivityLogRecorder.persist` / `uk_activity_log_event_id` | medium | accept (quantify + file) | A real collision drops an activity event with no exception and no dead letter. D-08 requires the expected-pairs figure at 1M lifetime events; if it clears the stated bar it is filed as its own todo, not fixed here — changing the id width or the dedupe strategy is a behavioural change to every entity id and to persisted rows. |
| T-ncx-05 | Tampering (dead code left behind) | the throwaway probe class | low | mitigate | A forgotten probe becomes a permanent, statistically-flaky test in the pre-commit gate. D-04 deletes it before Task 1's own commit; verify gates prove it is absent from disk, from the git index, and from history. |
| T-ncx-06 | Information disclosure | `PROBE-RAW.txt` (committed) | low | accept | The probe handles no credentials, no session material and no user data — only generated ids, timestamps and counts. Nothing to redact; recorded so the absence is a decision rather than an oversight. |
| T-ncx-SC | Tampering (supply chain) | n/a | n/a | n/a | No npm/pip/cargo install and no `build.gradle` change — no package-legitimacy gate applies. |
</threat_model>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java
@src/main/java/com/vrudenko/kanban_board/config/EventIdGenerator.java
@src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java
@.planning/todos/pending/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md
@docs/CODE_STYLE.md

Facts established by reading and grepping during planning, so the executor does not re-derive them
(re-read only for exact edit anchors and prose voice):

- `RandFlakeGenerator.generateRandflake()` (lines 33-42) is four statements:
  `timestamp = Instant.now().toEpochMilli() - 1672531200000L`,
  `randomBits = ThreadLocalRandom.current().nextLong(1L << 23)`,
  `id = (timestamp << 23) | randomBits`, `return Long.toString(id, 36)`. `RANDOM_BITS = 23` and
  `CUSTOM_EPOCH = 1672531200000L` are the only fields, both `private static final`. There is no
  sequence counter, no last-timestamp field, no dedupe set, no retry loop and no mutable state of any
  kind. Anything a fix adds here is the first state the class has ever held.
- Lines 25-32 carry the design-intent comment written by quick task 260802-tbj when it removed the
  `synchronized` modifier: the class "holds no shared mutable state", and "a lock never contributed to
  id uniqueness either: the low bits are random, not a sequence counter, so same-millisecond
  collisions were always possible at the same probability", closing with "if mutable state (a sequence
  counter, a last-timestamp field) is ever added here, revisit this."
- `EventIdGenerator` (line 21) holds `private final RandFlakeGenerator randFlakeGenerator = new RandFlakeGenerator();`
  — constructed directly, not injected. `new EventIdGenerator()` therefore works outside Spring and is
  the real production object.
- The failing test is `EventIdGeneratorTest$GenerateTest.shouldReturnDistinctValues_whenCalledManyTimesRapidly`
  at lines 41-54: 1000 `IntStream` calls collected into a `HashSet`, asserted `hasSize(callCount)`.
- The sibling test's Javadoc at lines 56-68 already states the property that makes that assertion
  unsound — "the low 23 bits are random, not a counter, so such an assertion would be flaky by
  construction" — and that test deliberately waits for a millisecond boundary to avoid it. The class
  Javadoc at line 17 claims the generator is a "real, distinct, time-ordered id source".
- The class is `@SpringBootTest` extending `AbstractPostgresContainerTest`; its Javadoc explains the
  container exists only because the test profile carries no datasource without one (04.2 D-01), not
  because these assertions need a database.
- `ActivityLogRecorder.persist` short-circuits at `if (activityLogRepository.existsByEventId(entry.getEventId()))`
  (line 46) and re-checks the same predicate in its catch block (line 58); `uk_activity_log_event_id`
  is a unique constraint created in `V3__add_activity_log.sql`, and `event_id` became `varchar` in
  `V6__change_activity_log_event_id_to_varchar.sql`.
- `eventIdGenerator.generate()` has ~14 call sites across `BoardService`, `ColumnService`,
  `TaskService` and `SubtaskService` — one per published domain event, i.e. roughly one per user
  mutation, nothing resembling a tight loop.
- `grep -rln 'RandFlakeGenerator\|generateRandflake' src/test/` returns exactly one file:
  `EventIdGeneratorTest.java`. No other test exercises the generator directly.
  `ActivityReadTest:249` asserts `doesNotHaveDuplicates()` on collected event ids (D-11: leave alone).
- `build.gradle`'s `fastTest` (lines 241-254, run by `.githooks/pre-commit`) excludes classes by
  explicit `@Tag("kafka")` / `@Tag("realSocket")` declaration, not by name suffix.
  `EventIdGeneratorTest` carries no tag, so it runs in the pre-commit gate.
- `build/` is git-ignored; `.planning/` is committed.
- 2^23 = 8,388,608 distinct low-bit values per millisecond. Useful sanity anchor while reading probe
  output, not a substitute for measuring: at k=1000 draws in one millisecond the birthday expectation
  is `k(k-1)/(2*2^23)` ~= 0.06 collisions per trial, i.e. a colliding trial roughly 1 run in 17. The
  reported symptom (three occurrences in one session's full-suite runs, each exactly one duplicate)
  sits in that neighbourhood — which is a reason to measure carefully, not a reason to skip measuring.
</context>

<tasks>

<task type="tracer">
  <name>Task 1: Measure the actual collision behaviour and record a verdict</name>
  <files>
    src/test/java/com/vrudenko/kanban_board/config/RandFlakeCollisionProbeTest.java (created untracked, deleted before this task's commit),
    build/probe/randflake-probe-raw.txt (git-ignored working output),
    .planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-RAW.txt,
    .planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-FINDINGS.md
  </files>
  <read_first>
    src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java (confirm the bit layout and
    the epoch constant by eye before decoding against them — the decode is only valid if it matches
    the shipped shift width),
    src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java (the exact call pattern
    the probe must reproduce: one shared generator instance, 1000 sequential calls, HashSet collection)
  </read_first>
  <action>
    Write one throwaway probe class at
    `src/test/java/com/vrudenko/kanban_board/config/RandFlakeCollisionProbeTest.java`. Plain JUnit 5
    only — no `@SpringBootTest`, no superclass, no container, no `@Tag`, no mocks. It calls
    `new EventIdGenerator()` once and reuses that single instance for every call, mirroring the
    autowired singleton the real test uses. Do NOT `git add` it at any point (D-04).

    The class exists to observe, not to gate: assert almost nothing, record everything. Write output
    to the path in system property `probe.out`, defaulting to `build/probe/randflake-probe-raw.txt`,
    opening it in TRUNCATE mode and creating parent directories. It must never write into `.planning/`
    — the executor copies the final file there, so that a later accidental re-run cannot overwrite the
    numbers the findings quote.

    Measure T = 200 trials (make it a named constant so the findings can quote it) of 1000 calls each.
    Per trial: capture `Instant.now().toEpochMilli()` immediately before and after the loop; collect
    the ids in order into a `List` and into a `HashSet`; then decode each id with
    `Long.parseLong(id, 36)`, `timestamp = value >>> 23`, `random = value & ((1L << 23) - 1)`. Release
    each trial's collections before the next trial so memory stays flat.

    Answer the four D-03 questions, writing a clearly delimited section per question:

    Q1 — the raw rate. Per trial write the distinct count and, for any trial below 1000, the shortfall.
    Aggregate: T, the number of colliding trials C, total duplicate pairs, and min/max/mean distinct.

    Q2 — observed against predicted. Per trial write the decoded-timestamp bucket sizes (how many of
    the 1000 calls landed in each distinct millisecond) and the per-trial birthday prediction
    `p = 1 - product over buckets of exp(-k*(k-1) / (2 * 8388608.0))`. Aggregate `E = sum of p` across
    trials and write `C`, `E`, `sqrt(E)` and `E + 3*sqrt(E)` on their own lines so the D-05 criterion
    can be read off directly. Note in the file that E is a property of this machine's loop speed via
    the bucket sizes, not a universal constant.

    Q3 — decode validity and randomness structure. Per trial assert-and-record that decoded timestamps
    are non-decreasing and that `min + CUSTOM_EPOCH` and `max + CUSTOM_EPOCH` both lie within the
    trial's captured wall-clock window; write an explicit pass/fail line. For every duplicate pair
    write both decoded halves of both ids and whether the halves agree. Across all trials write: the
    number of distinct random values drawn, the most-frequent random value with its count, and the
    number of times two consecutive draws produced an equal random value. Include the expected figure
    for each alongside the observed one so the comparison is on the page.

    Q4 — production-rate figure. Run a second measurement of at least 20000 trials of 20 back-to-back
    calls (one HTTP mutation publishes one event; 20 is a generous per-request upper bound) and write
    the colliding-trial count and rate. Separately compute and write the expected number of colliding
    pairs across 1,000,000 lifetime events, stating the same-millisecond clustering assumption used
    and showing the arithmetic — this is the figure D-08 judges against.

    Run with `./gradlew test --tests '*RandFlakeCollisionProbeTest*'` (bound to 10 minutes; it needs
    no Docker, but Gradle still compiles the whole test source set). If a probe method errors, fix the
    probe and re-run — the probe failing is not a finding. If the Q3 decode check fails, stop
    measuring and record `DECODE_INVALID`.

    Copy `build/probe/randflake-probe-raw.txt` to `PROBE-RAW.txt` in this plan's directory, then write
    `PROBE-FINDINGS.md` alongside it: a short answer to each of Q1-Q4 quoting the decisive line from
    the raw file; a `## Observed vs predicted` section stating C, E and whether `C <= E + 3*sqrt(E)`
    holds; a `## What this means for the test` section reconciling the result with the two claims
    already in the tree (`RandFlakeGenerator`'s same-millisecond comment and `EventIdGeneratorTest`'s
    own third-test Javadoc, both named in the context block); a `## Production exposure` section
    carrying the Q4 figure and naming the `ActivityLogRecorder.persist` short-circuit as the mechanism
    by which a collision costs a row silently. If the verdict is `INHERENT_BIRTHDAY`, also compute and
    record, for at least three candidate thresholds, `P(distinct < threshold)` under the measured
    bucket distribution and the stubbed-delegate distinct count — Task 2 picks `MIN_DISTINCT_IDS` from
    this table under D-06's two floors, so the table must exist here.

    End the findings with exactly one line of the form `VERDICT: <token>`, token one of
    `INHERENT_BIRTHDAY`, `GENERATOR_DEFECT`, `DECODE_INVALID`, chosen strictly by D-05's numeric
    criteria applied to the recorded output. Do not choose it from expectation, and do not soften a
    `GENERATOR_DEFECT` reading because the generator's own comment predicts otherwise.

    Then delete the probe class from disk and confirm it is absent from the working tree, the git
    index and git history. Commit only the two evidence files.
  </action>
  <verify>
    <automated>cd "$(git rev-parse --show-toplevel)" && D=.planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur && P=src/test/java/com/vrudenko/kanban_board/config/RandFlakeCollisionProbeTest.java && test -s $D/PROBE-RAW.txt && test -s $D/PROBE-FINDINGS.md && grep -qE '^VERDICT: (INHERENT_BIRTHDAY|GENERATOR_DEFECT|DECODE_INVALID)$' $D/PROBE-FINDINGS.md && test "$(grep -cE '^VERDICT: ' $D/PROBE-FINDINGS.md)" = "1" && test "$(grep -cE 'Q1|Q2|Q3|Q4' $D/PROBE-FINDINGS.md)" -ge "4" && grep -q 'ActivityLogRecorder' $D/PROBE-FINDINGS.md && grep -qE '8388608|8,388,608' $D/PROBE-RAW.txt && test "$(grep -cE '[0-9]' $D/PROBE-RAW.txt)" -ge "200" && test ! -f $P && test -z "$(git ls-files $P)" && test -z "$(git log --all --oneline -- $P)" && test -z "$(git ls-files --others --exclude-standard -- '*.java')" && echo VERIFY_OK</automated>

    Gate rationale, smoke-checked against the pre-task tree during planning: neither evidence file
    exists yet, so every content gate is red before the task and can only go green by running the
    probe. The `VERDICT:` gate demands exactly one line with a known token, so a hedged conclusion
    fails. `>= 200` numeric lines in the raw file is a floor that a stub file or a single-trial run
    cannot clear at T=200 trials. The last four gates together encode D-04: the probe is gone from
    disk, absent from the index, absent from all history, and no stray untracked Java file was left
    behind anywhere in the tree.

    <human-check>
      Read `PROBE-FINDINGS.md` against `PROBE-RAW.txt`. Every number in the findings must appear in
      the raw file. Check specifically that the verdict follows D-05's criteria as applied to the
      recorded C and E rather than to the reviewer's expectation — the generator's own comment
      predicting same-millisecond collisions is a hypothesis this task tested, and a `GENERATOR_DEFECT`
      reading must not have been talked out of the data. If the verdict is `GENERATOR_DEFECT` or
      `DECODE_INVALID`, stop and raise it before Task 2 changes any production code.
    </human-check>
  </verify>
  <done>
    `PROBE-RAW.txt` carries per-trial distinct counts, decoded millisecond bucket sizes, per-trial
    birthday predictions, the aggregate C / E / E+3*sqrt(E) lines, an explicit decode-validity
    pass/fail, both decoded halves of every duplicate pair, the randomness-structure counts with their
    expected values, and the production-rate measurement. `PROBE-FINDINGS.md` answers Q1-Q4 with quoted
    evidence, reconciles the result with both existing in-tree claims, quantifies the
    `ActivityLogRecorder` exposure, carries the candidate-threshold table when the verdict is
    `INHERENT_BIRTHDAY`, and ends with exactly one valid `VERDICT:` line. The probe class exists
    nowhere — not on disk, not in the index, not in history. Only the two evidence files are committed.
  </done>
</task>

<task type="auto">
  <name>Task 2: Fix whichever artefact the measurement convicted, prove the teeth, close the todo</name>
  <precondition>Docker is running and reachable — the final `./gradlew spotlessCheck test` boots Testcontainers PostgreSQL and Redpanda; on Windows see docs/LOCAL_DEV.md.</precondition>
  <files>
    src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java,
    src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java,
    .planning/todos/pending/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md (moved),
    .planning/todos/completed/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md
  </files>
  <read_first>
    .planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/PROBE-FINDINGS.md (the verdict
    and the candidate-threshold table drive every edit below),
    .planning/todos/completed/2026-08-11-taskservice-deleteallbycolumnid-has-zero-production-callers.md
    (the `## Resolution` shape to match),
    docs/CODE_STYLE.md rules 3, 5 and 13 (AssertJ usage, `should<Outcome>_when<Condition>` naming, test
    placement — all three constrain the renamed method and its assertion)
  </read_first>
  <action>
    Read the `VERDICT:` token from `PROBE-FINDINGS.md` and apply the matching branch.

    Branch `DECODE_INVALID` — do not edit any source file. Record the failure in the SUMMARY, leave the
    todo in `pending/` with a note appended describing what the decode did, and stop. A measurement
    that did not validate cannot convict anything.

    Branch `GENERATOR_DEFECT` — the test was right and the generator is wrong. Do not implement a fix
    silently: first write the proposed remedy and its blast radius into `PROBE-FINDINGS.md` under a
    `## Proposed remedy` heading, covering the three constraints named in this plan's second matrix
    (reintroducing mutable state reopens the `synchronized` decision quick task 260802-tbj closed, on
    the `IdentifierGenerator` behind every entity insert; a wider random field changes the Base36
    string width, which breaks the lexicographic-ordering caveat the third test documents; `event_id`
    is a `varchar` carrying already-persisted rows since V6). Then implement the narrowest change that
    the probe's evidence actually justifies, add a test that fails against the old behaviour, and leave
    `shouldReturnDistinctValues_whenCalledManyTimesRapidly` exactly as it is — under this branch its
    assertion was correct all along.

    Branch `INHERENT_BIRTHDAY` — the generator behaves as designed and the test asserts a guarantee it
    never made. Fix the test, in `EventIdGeneratorTest` only:

    Rename `shouldReturnDistinctValues_whenCalledManyTimesRapidly` to
    `shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly` (D-06). Add a
    `private static final int MIN_DISTINCT_IDS` constant on the `GenerateTest` nested class and change
    the assertion to `Assertions.assertThat(ids).hasSizeGreaterThanOrEqualTo(MIN_DISTINCT_IDS)`,
    keeping the existing arrange/act/assert comment structure and the fully-qualified AssertJ style
    (rule 3). Pick the constant's value from the candidate-threshold table Task 1 recorded, subject to
    both D-06 floors — the false-failure probability under the measured bucket distribution below
    1e-9, and at least 50x the distinct count an entropy-free delegate would yield. Do not adopt a
    number from this plan; state in the SUMMARY which table row was chosen and why.

    Give the method a Javadoc that records, in this repo's voice: the composition
    (timestamp millis in the high bits, 23 random bits in the low bits, so 8,388,608 values per
    millisecond); that 1000 rapid sequential calls mostly land in one or two milliseconds, making a
    birthday collision an ordinary outcome rather than a defect; the measured T, C and E from
    `PROBE-FINDINGS.md` with a pointer to it; the chosen threshold and its computed false-failure
    probability; and what the threshold still catches — a stubbed, constant or entropy-free delegate
    collapses the distinct count to roughly the number of milliseconds the loop spanned, orders of
    magnitude below the threshold. Also correct the class Javadoc's "real, distinct, time-ordered id
    source" phrasing so the class stops claiming exhaustive distinctness eleven lines above the test
    whose own Javadoc explains why that cannot hold.

    Then prove the teeth (D-07), before touching `RandFlakeGenerator`'s comment so the diff check is
    unambiguous: temporarily replace the `ThreadLocalRandom` draw in `generateRandflake()` with a
    constant, run `./gradlew test --tests '*EventIdGeneratorTest*'`, and capture the RED output
    verbatim for the SUMMARY and the todo Resolution. Restore the file, re-run, capture GREEN, and
    confirm `git diff -- src/main` is empty at that moment. Do not commit while the constant is in
    place — the pre-commit hook runs `fastTest` and `--no-verify` is forbidden.

    Then append the measured collision rate to `RandFlakeGenerator`'s existing same-millisecond
    sentence (lines 25-32) in at most two sentences, comment-only — no field, no statement, no
    signature touched. Cite `PROBE-FINDINGS.md` rather than restating its arithmetic.

    Apply D-08: if Q4's expected colliding pairs across 1,000,000 lifetime events is at or above 0.01,
    create a new `[minor]` pending todo in `.planning/todos/pending/` naming
    `ActivityLogRecorder.persist`'s `existsByEventId` short-circuit, the `uk_activity_log_event_id`
    constraint and the measured figure, framed as an event lost with no exception and no dead letter.
    Do not widen the id, add a counter, or change the dedupe. If it is below 0.01, file nothing and
    state the figure in the Resolution instead.

    Finish in every branch except `DECODE_INVALID`: `./gradlew spotlessApply`, then `git mv` the source
    todo into `.planning/todos/completed/` and append a `## Resolution` per D-10 — this quick task's
    id, the verdict token, T / C / E, the chosen threshold and its false-failure probability, the RED
    and GREEN falsification results, the production-exposure figure and whether a todo was filed, which
    artefact was corrected and which was deliberately left alone (`ActivityReadTest:249`, the class's
    `@SpringBootTest` tier, the `fastTest` tag), and a pointer to `PROBE-RAW.txt`. Leave the file's
    existing `## Resolved:` section for the `ColumnLockingTest` half untouched. Run
    `./gradlew test --tests '*EventIdGeneratorTest*' --rerun-tasks` three times in a row and record all
    three results, then `./gradlew spotlessCheck test` on the final tree, bounded to 15 minutes.
  </action>
  <verify>
    <automated>cd "$(git rev-parse --show-toplevel)" && D=.planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur && T=src/test/java/com/vrudenko/kanban_board/config/EventIdGeneratorTest.java && V=$(grep -oE '^VERDICT: [A-Z_]+' $D/PROBE-FINDINGS.md | cut -d' ' -f2) && echo "verdict=$V" && if [ "$V" = "INHERENT_BIRTHDAY" ]; then grep -q 'MIN_DISTINCT_IDS' $T && grep -q 'hasSizeGreaterThanOrEqualTo' $T && grep -q 'shouldReturnOverwhelminglyDistinctValues_whenCalledManyTimesRapidly' $T && grep -q 'PROBE-FINDINGS' $T && grep -q 'PROBE-FINDINGS' src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java && grep -qE 'ThreadLocalRandom' src/main/java/com/vrudenko/kanban_board/config/RandFlakeGenerator.java; elif [ "$V" = "GENERATOR_DEFECT" ]; then grep -q 'Proposed remedy' $D/PROBE-FINDINGS.md && grep -q 'shouldReturnDistinctValues_whenCalledManyTimesRapidly' $T; else grep -q 'DECODE_INVALID' $D/PROBE-FINDINGS.md; fi && if [ "$V" != "DECODE_INVALID" ]; then test -f .planning/todos/completed/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md && test ! -f .planning/todos/pending/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md && grep -q '## Resolution' .planning/todos/completed/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md && grep -qE "$V" .planning/todos/completed/2026-08-10-investigate-recurring-eventidgeneratortest-uniqueness-fla.md; fi && test -z "$(git status --porcelain)" && ./gradlew spotlessCheck test && echo VERIFY_OK</automated>

    Gate rationale: the gate reads the verdict token out of `PROBE-FINDINGS.md` and applies only the
    branch that token selects, so it cannot be satisfied by editing the other branch's files. Under
    `INHERENT_BIRTHDAY` the four test-file gates are all red on the pre-task tree — `MIN_DISTINCT_IDS`,
    `hasSizeGreaterThanOrEqualTo`, the new method name and the findings pointer do not exist today —
    and the `ThreadLocalRandom` gate is the D-07 restore check, red for exactly as long as the
    falsification constant is in place. Under `GENERATOR_DEFECT` the gate instead requires the remedy
    write-up and requires the original method name to have survived, so that branch cannot quietly
    relax the test. The todo gates encode D-10 (moved, not copied; carries a Resolution; names the
    verdict). `git status --porcelain` empty proves nothing was left uncommitted, and
    `./gradlew spotlessCheck test` is the shipped-tree green run — bound this invocation to 15 minutes
    rather than waiting on it open-endedly.

    <human-check>
      Read the shipped test method and ask whether it would still fail if the generator regressed. The
      Javadoc must let a future reader re-derive the threshold rather than trust it: bit layout, T, C,
      E, the false-failure probability, and what a stubbed delegate would score. Confirm the RED output
      captured during falsification is a real failure of *this* assertion and not a compilation error
      or a different test. Confirm `RandFlakeGenerator`'s diff is comment-only under
      `INHERENT_BIRTHDAY` — no statement, field, signature or import changed.
    </human-check>
  </verify>
  <done>
    Under `INHERENT_BIRTHDAY`: `EventIdGeneratorTest` asserts a measurement-derived
    `MIN_DISTINCT_IDS` floor under a method name that no longer claims exhaustive distinctness, with a
    Javadoc carrying the layout, the measured numbers and the retained-teeth argument; the class
    Javadoc no longer contradicts its own third test; `RandFlakeGenerator` carries the measured rate in
    a comment-only edit with `ThreadLocalRandom` restored; the falsification produced a recorded RED
    and a recorded GREEN with an empty `git diff -- src/main` between them. Under `GENERATOR_DEFECT`:
    the remedy and its blast radius are written up, the narrowest justified change is implemented with
    a test that fails against the old behaviour, and the original assertion is untouched. In both:
    the D-08 figure is recorded and a todo filed if it cleared the bar, the source todo is in
    `completed/` with a Resolution quoting the measurements, three consecutive target-test reruns are
    recorded green, `./gradlew spotlessCheck test` passes on the shipped tree, and the working tree is
    clean.
  </done>
</task>

</tasks>

<verification>
- `PROBE-RAW.txt` and `PROBE-FINDINGS.md` exist, are committed, and the findings quote only numbers
  that appear in the raw file.
- Exactly one `VERDICT:` line exists, its token is one of the three defined, and it follows D-05's
  numeric criteria applied to the recorded C and E.
- `RandFlakeCollisionProbeTest.java` is absent from disk, from the git index and from git history.
- The branch applied matches the verdict; the other branch's files are unmodified.
- The falsification produced a recorded RED and a recorded GREEN, with `git diff -- src/main` empty
  between them (D-07).
- Three consecutive `./gradlew test --tests '*EventIdGeneratorTest*' --rerun-tasks` runs are recorded,
  all green.
- `./gradlew spotlessCheck test` passes on the shipped tree, bounded to 15 minutes.
- The source todo is in `.planning/todos/completed/` with a `## Resolution` carrying the verdict, T, C,
  E, the threshold, the falsification results and the production-exposure figure; its pre-existing
  `## Resolved:` section is unchanged.
- Working tree clean; nothing left in `build/probe/` matters (git-ignored).
</verification>

<success_criteria>
The 999-of-1000 observation has a documented cause backed by T>=200 measured trials rather than two
competing hypotheses, the artefact that measurement convicted is the one that changed, any relaxed
assertion has been demonstrated to still go red when the generator's entropy is removed, the silent
activity-row-loss consequence has a number attached, the full suite is green, and the source todo is
closed with the measurements rather than the reasoning.
</success_criteria>

<output>
Create `.planning/quick/260813-ncx-investigate-eventidgeneratortest-s-recur/260813-ncx-SUMMARY.md` when done
</output>