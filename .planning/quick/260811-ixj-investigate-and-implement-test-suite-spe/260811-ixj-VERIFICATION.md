---
phase: quick-260811-ixj
verified: 2026-08-11T14:00:00Z
status: passed
score: 11/11 must-haves verified
behavior_unverified: 0
overrides_applied: 0
---

# Quick Task 260811-ixj: Test-suite speed / parallelization Verification Report

**Task Goal:** Investigate and implement test suite speed / parallelization improvements per
`.planning/todos/pending/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md` —
measure before choosing, per the todo's own discipline.

**Verified:** 2026-08-11
**Status:** passed
**Merged:** fast-forward to `f75c678`, now `master` HEAD (working tree clean except unrelated
`.gsd/` and the plan/research/summary docs themselves, which are untracked-but-expected quick-task
artifacts pending commit by the orchestrator)

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|---|---|---|
| 1 | Test-profile BCrypt cost factor is version-controlled, not hand-edited/hard-coded | VERIFIED | `src/main/resources/application-test.properties:17` sets `security.bcrypt.strength=4`, committed at `34185aa`; no manual step required — the test profile activates the file automatically via `build.gradle`'s `systemProperty "spring.profiles.active", "test"` |
| 2 | Production cost factor unchanged at 10, proven by a red-if-changed assertion, not a claim | VERIFIED | `BeanConfiguration.passwordEncoder(@Value("${security.bcrypt.strength:10}") int strength)` — fallback is `10`. `PasswordEncoderStrengthTest.ProductionFallback.shouldFallBackToTen_whenNoOverrideIsConfigured` reflects on the `@Value` annotation string and asserts it equals `"${security.bcrypt.strength:10}"` exactly; `application.properties` (production) has zero `bcrypt` matches (`grep -i bcrypt` → no output) |
| 3 | Lowered cost factor proven in force via a real hash's cost segment, not just config presence | VERIFIED | `TestProfileCostFactor.shouldEncodeAtCostFactorFour_whenUsingTheAutowiredBean` encodes through the real autowired `PasswordEncoder` bean and asserts `Splitter.on('$').splitToList(hash).get(2)` equals `"04"` — a typo'd property key would fail this, not silently pass |
| 4 | Wall-clock recorded twice before and twice after, on this machine | VERIFIED | `260811-ixj-MEASUREMENTS.md` — Baseline table: 2× `test` (440s, 427s), 2× `fastTest` (334s, 341s); After-lever-1 table: 2× `test` (362s, 379s), 2× `fastTest` (287s, 283s). Machine facts (Docker CPUs/memory, git SHA) recorded for reproducibility |
| 5 | After-numbers carry test count, so a smaller-but-faster suite would be visible | VERIFIED | Every row in every table in `260811-ixj-MEASUREMENTS.md` has a paired test count (385→388, 348→351, +3 explained as `PasswordEncoderStrengthTest`'s new methods, not shrinkage) |
| 6 | `maxParallelForks` answered by measured runs at 2 and 4, adopted only if it measured better | VERIFIED | `build.gradle` lines 184-195 (`test` task) and 218-234 (`fastTest` task) both carry `maxParallelForks = 2` with an inline comment recording the measured numbers (2 forks: 242.5s avg vs 285.0s 1-fork baseline on `fastTest`, beating the ~18s variance on both runs; 4 forks: 267.0s avg, rejected — only one of two runs cleared variance). `forkEvery` is not set anywhere (`grep -v '^\s*//' build.gradle \| grep -c forkEvery` → 0 real occurrences, only comment-line mentions) |
| 7 | JUnit 5 in-JVM parallelism and per-tier splitting recorded as evaluated-and-rejected with reasoning | VERIFIED | `docs/LOCAL_DEV.md` lines 265-290: five named blockers for in-JVM parallelism (unscoped `deleteAll()`, `SessionFactory`-global stats, mutable static in `AbstractKafkaContainerTest`, shared Kafka topic, positional assertions) with the `@ResourceLock` rebuttal; per-tier splitting rejected on the `--parallel`-is-subproject-scoped argument |
| 8 | The one real trade-off (weaker test-profile cost factor) is written down, not glossed | VERIFIED | `docs/LOCAL_DEV.md` lines 235-244, "The security trade-off, stated plainly, not glossed" — names the theoretical-vs-practical distinction, the two structural mitigations, and the uncovered residual (deploy-time env var / `SPRING_APPLICATION_JSON` override), explicitly not claimed closed |
| 9 | `spotlessCheck` and `test` both pass at plan end, full-suite count reported against baseline | VERIFIED | Independently re-run by orchestrator post-merge: `BUILD SUCCESSFUL in 4m33s`. `PasswordEncoderStrengthTest`'s three nested-class result XMLs under `build/test-results/test/` show `failures="0" errors="0"` across 1+2 = 3 tests, timestamped after the merge commit. `260811-ixj-MEASUREMENTS.md` and `docs/LOCAL_DEV.md` both report 388 tests (final) vs 385 (baseline) — no shrinkage |
| 10 | Untagged Kafka-container test filed as its own todo, not silently fixed inline | VERIFIED | `.planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md` exists, frames three options, cites concrete evidence (class declaration line, `build/test-results/fastTest/` presence, live `docker ps` observation). `git diff 74f11e1^..f75c678 -- .../HistoricalActivityEventReconstructorTest.java` is empty — file genuinely untouched |
| 11 | Source todo no longer sits in `.planning/todos/pending/` | VERIFIED | `.planning/todos/pending/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md` absent; `.planning/todos/completed/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md` exists with a `## Resolution` section answering all three original candidates in order and naming the fourth (unlisted) lever the measurement actually found |

**Score:** 11/11 truths verified (0 present-but-behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java` | Injectable BCrypt strength, production-safe fallback | VERIFIED | `passwordEncoder(@Value("${security.bcrypt.strength:10}") int strength)` → `new BCryptPasswordEncoder(strength)`, Javadoc explaining the mechanism and cross-referencing `AuthenticationController`'s equalizer hash |
| `src/main/resources/application-test.properties` | `security.bcrypt.strength=4`, test-only | VERIFIED | Line 17, with an explanatory comment block on why this file only applies under the `test` profile |
| `src/test/java/com/vrudenko/kanban_board/config/PasswordEncoderStrengthTest.java` | Real assertions on cost segment, fallback string, and absence from prod properties | VERIFIED | 3 `@Test` methods across 2 `@Nested` groups, each substantive (reflective `@Value` check, real hash encode + split, classpath-resource line scan) — not a stub, matches `CorsConfigTest`'s precedent, extends `AbstractPostgresContainerTest` (not `AbstractAppTest`) as the plan required |
| `build.gradle` | `maxParallelForks = 2` on measured tasks only if it won; `forkEvery` unset | VERIFIED | Present on both `test` and `fastTest` tasks with justifying comments; `forkEvery` not set anywhere in the file |
| `docs/LOCAL_DEV.md` | New section: measured numbers, decision, both rejected levers, security trade-off, residual, revisit-if | VERIFIED | Full section at lines 204-301, matches the register of the pre-existing "Testcontainers reuse" section as instructed |
| `.planning/quick/260811-ixj.../260811-ixj-MEASUREMENTS.md` | ≥12 recorded runs with duration + test count | VERIFIED | 14 runs recorded (4 baseline, 4 after-lever-1, 6 fork-count runs), exceeds the plan's 12-run floor |
| `.planning/todos/pending/2026-08-11-tag-historicalactivityeventreconstructortest-as-kafka.md` | New todo, decision-framed, class unmodified | VERIFIED | Exists, 3-option framing, cites the fork-count interaction this task surfaced |
| `.planning/todos/completed/2026-08-10-investigate-test-parallelization-and-other-suite-speed.md` | Moved from pending, with Resolution | VERIFIED | Present with `## Resolution` answering all 3 original candidates plus the 4th lever found |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `@Value` `:10` fallback | Production safety | Absence of test-profile activation resolves fallback | WIRED | Confirmed: `application.properties` (production) defines no `bcrypt` key at all; fallback is the only source of the value in that profile |
| `passwordEncoder` bean | `AuthenticationController`'s `@PostConstruct` equalizer hash | Bean injection | WIRED (by inspection) — not independently re-derived here since `AuthenticationController` was not modified by this task and the claim is architecturally sound (equalizer hash is derived from the same injected bean) |
| `PasswordEncoderStrengthTest` | `AuthenticationTest` / `SigninTimingEqualizationTest` non-interference | Independent `git diff` across full task commit range | WIRED | `git diff 74f11e1^..f75c678 -- AuthenticationTest.java SigninTimingEqualizationTest.java` returns empty — independently confirmed byte-identical, not merely asserted by SUMMARY |
| `HistoricalActivityEventReconstructorTest` untagged class | `build.gradle`'s `fastTest` excludeTags filter | Gate membership by tag, not name | WIRED (correctly identified as a gap, filed not fixed) | `git diff` across the same commit range confirms the file is genuinely unmodified; the todo names the exact mechanism (`excludeTags 'kafka', 'realSocket'` in `build.gradle`, this class has no matching `@Tag`) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| `PasswordEncoderStrengthTest`'s 3 methods actually pass (not just claimed) | Inspected `build/test-results/test/TEST-...PasswordEncoderStrengthTest$*.xml` | `TestProfileCostFactor`: tests="1" failures="0" errors="0"; `ProductionFallback`: tests="2" failures="0" errors="0" | PASS |
| Full suite result freshness vs. merge | `ls -la` timestamp on the result XML vs. `git log -1 --format=%cI f75c678` | Result files dated 15:56, merge commit at 15:44 — result files postdate the merge, consistent with the orchestrator's independently-run post-merge `BUILD SUCCESSFUL in 4m33s` claim | PASS |
| `AuthenticationTest`/`SigninTimingEqualizationTest` byte-identical across task range | `git diff 74f11e1^..f75c678 --stat -- <both files>` | Empty diff, exit 0 | PASS |
| `HistoricalActivityEventReconstructorTest` byte-identical across task range | `git diff 74f11e1^..f75c678 --stat -- <file>` | Empty diff, exit 0 | PASS |
| No debt markers introduced in touched files | `grep -E "TBD\|FIXME\|XXX\|TODO\|HACK\|PLACEHOLDER"` across the 5 primary changed files | No matches | PASS |
| `maxParallelForks` present, `forkEvery` absent | `grep` on `build.gradle` | `maxParallelForks = 2` × 2 (test, fastTest); `forkEvery` only appears inside a comment explaining it is deliberately unset | PASS |

Full `./gradlew test` was not re-run in this verification pass — the orchestrator independently re-ran it post-merge (`BUILD SUCCESSFUL in 4m33s`) per the task instructions, and the on-disk test-result XMLs corroborate that claim with a post-merge timestamp and 0 failures/errors for the new test class specifically.

### Anti-Patterns Found

None. All touched files (`BeanConfiguration.java`, `application-test.properties`, `PasswordEncoderStrengthTest.java`, `build.gradle`, `docs/LOCAL_DEV.md`) are free of debt markers, stub returns, and hardcoded-empty patterns.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| QUICK-260811-ixj | `260811-ixj-PLAN.md` | Investigate and implement test-suite speed improvements, measure before choosing | SATISFIED | All 11 must-have truths verified above |

### Human Verification Required

None. All must-haves were independently verifiable via git history, file content, and test-result artifacts.

### Gaps Summary

No gaps found. All 11 must-have truths from the plan's frontmatter are verified against the actual
codebase, not merely claimed in SUMMARY.md. Specifically checked and confirmed independently rather
than trusted from the summary:

- The production BCrypt fallback (10) and test-profile override (4) are real, version-controlled,
  and covered by a substantive (non-stub) reflective/behavioral test.
- `AuthenticationTest` and `SigninTimingEqualizationTest` are genuinely byte-identical across the
  task's full commit range (`74f11e1^..f75c678`), not just claimed identical.
- `HistoricalActivityEventReconstructorTest` is genuinely unmodified — the side finding was filed,
  not silently fixed.
- `maxParallelForks = 2` is the actual value in `build.gradle` today, matching the plan's measured
  decision (2 adopted, 4 explicitly rejected) — not a stale or divergent value.
- `docs/LOCAL_DEV.md` documents pulled levers, both rejected levers, and the residual deploy-time
  override risk, in the plan-required register.
- The new Kafka-tag todo exists in `pending/` and the source todo exists in `completed/` with a
  Resolution section.
- The new test's three assertions actually pass, per fresh, post-merge test-result XML evidence
  (not merely trusted from SUMMARY.md's "Self-Check: PASSED" section).

---

_Verified: 2026-08-11_
_Verifier: Claude (gsd-verifier)_
