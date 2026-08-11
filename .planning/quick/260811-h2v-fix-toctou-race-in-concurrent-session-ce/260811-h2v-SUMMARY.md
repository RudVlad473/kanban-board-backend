---
phase: quick-260811-h2v
plan: 01
subsystem: security
tags: [concurrency, session-management, toctou, security, testing]
dependency-graph:
  requires: []
  provides:
    - ConcurrentSigninCeilingE2ETest
    - SecurityConfiguration.sessionAuthenticationStrategy TOCTOU Javadoc
  affects:
    - docs/ARCHITECTURE.md signin sequence diagram
    - F6 (2026-08-10 /claude-security scan) todo
tech-stack:
  added: []
  patterns:
    - "Real-concurrency TOCTOU characterization via CountDownLatch + fixed-size ExecutorService (mirrors BoardCreationE2ETest.ConcurrentCreate)"
    - "Cross-connection visibility measurement via a temporary JdbcTemplate probe, added and reverted within one task, gated by a zero-net-diff git check"
key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/security/ConcurrentSigninCeilingE2ETest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
    - docs/ARCHITECTURE.md
    - .claude/CLAUDE.md
    - .planning/todos/completed/2026-08-10-toctou-race-in-concurrent-session-ceiling-enforcement.md
decisions:
  - "D-01 ratified: accept a bounded, self-healing TOCTOU overshoot on the concurrent-session ceiling rather than serialize it with a transaction-scoped advisory lock -- settled by measurement (Task 2), not by reasoning about Spring Session's source."
  - "Measured overshoot frequency: 10 of 10 @RepeatedTest(10) repetitions overshot on this machine (window opens on every attempt under this harness's real-socket thread-pool pattern), not the plan's illustrative 0-of-10 baseline -- does not change the disposition, does change how the trade-off should be read (reliable, not rare)."
  - "Task 2's cross-connection probe measurement: in-controller read 0 committed SPRING_SESSION rows right after saveContext() returned; a client-side probe taken after the HTTP response was received read 1 -- confirms a pg_advisory_xact_lock around AuthenticationController.authenticate would release before the row it needs to serialize against exists."
metrics:
  duration: 105min
  completed: 2026-08-11
status: complete
actuals:
  tokens: 27000
  tasks: 3
  commits: 2
---

# Quick Task 260811-h2v: Fix TOCTOU race in concurrent-session-ceiling enforcement Summary

Resolved finding F6 (2026-08-10 `/claude-security` scan) as a measured, documented, empirically-proven trade-off: the concurrent-session ceiling's check-then-act race is accepted as a bounded, self-healing overshoot rather than closed with a transaction-scoped advisory lock, because a real cross-connection measurement showed the advisory lock would have serialized nothing.

## What Was Built

**Task 1 — `ConcurrentSigninCeilingE2ETest`** (real-socket, `@Tag("realSocket")`, concurrent sibling of `AuthenticationTest.ConcurrentSessionCeiling`'s sequential spec): two cookie-less `POST /signin` requests race the ceiling at exactly one session of headroom below `MAX_CONCURRENT_SESSIONS`. The shipped assertions are an invariant plus a range, never a guessed exact count:

- `liveSessionCount() == 1 + successCount` always holds (no lost or phantom rows).
- `successCount` is in `[1, 2]` — the racers either serialized (ceiling held exactly) or the accepted TOCTOU overshoot occurred; both are conformant.
- A signin issued sequentially after the burst settles is still refused with 401 and creates no new row — the self-heal assertion, and this test's teeth.

**Teeth check performed and recorded:** `sessionAuthenticationStrategy.onAuthentication(...)` was temporarily commented out in `AuthenticationController.authenticate`. The test went RED exactly on the self-heal assertion:

```
org.opentest4j.AssertionFailedError:
expected: 401
 but was: 200
	at ConcurrentSigninCeilingE2ETest$ConcurrentSignin.shouldCreateOneSessionPerAcceptedSignin_whenTwoSigninsRaceTheCeiling(ConcurrentSigninCeilingE2ETest.java:209)
```

Restored, then verified `git diff --quiet HEAD -- AuthenticationController.java` (zero net production diff).

**Characterization run performed and recorded:** `@Test` was temporarily changed to `@RepeatedTest(10)` with the bound assertion tightened to `isEqualTo(1)`. **All 10 of 10 repetitions failed** with `expected: 1 but was: 2` — the TOCTOU overshoot occurred on every single repetition on this machine, under this harness's real-socket, two-thread `ExecutorService` submission pattern. This is a genuinely more decisive (and different) result than the plan's illustrative "0 of 10" scenario, but it does not change D-01: the overshoot is still exactly one extra session per genuinely-simultaneous racer, and the self-heal assertion still holds. It does mean the accepted trade-off is better described as "the ceiling reliably allows one extra concurrent signin to succeed" than as a rare edge case — recorded verbatim in the class Javadoc and in the closed todo's Resolution, not editorialized away. Restored to `@Test` + `isBetween(1, 2)` afterward.

**Task 2 — session-row visibility measurement** (evidence only, zero shipped production diff): a temporary `JdbcTemplate` probe was injected into `AuthenticationController`, printing the live cross-connection `SPRING_SESSION` count for the just-authenticated principal immediately after `securityContextRepository.saveContext(...)` returns (the latest point any controller-scoped transaction could still be open). A throwaway client-side test sampled the same quantity after the HTTP response was fully received.

Measured result (`--info` Gradle output, grepped for the `PROBE-260811-h2v-TASK2` token):
- In-controller cross-connection count: **0**
- Post-response client-side count: **1**

This is the plan's "expected result" branch: the new session row is not committed, and therefore not visible to another database connection, until Spring Session's request-scoped filter commits it as the response is flushed — strictly *after* `authenticate` (and any transaction scoped around it) has already returned. A `pg_advisory_xact_lock` held across that call would release before the row it needs to serialize against exists, closing nothing while adding a blocking round trip to every signin. This confirms the premise and ratifies D-01/Approach A. Both the probe and the throwaway test were fully reverted; `git diff --quiet HEAD -- AuthenticationController.java` confirmed zero net diff, and the working tree was clean (no commit needed for this task, per the plan's own instruction for a clean revert).

**Task 3 — documentation and todo closure** (only since Task 2 ratified Approach A):
- `SecurityConfiguration.sessionAuthenticationStrategy`'s Javadoc gained a paragraph documenting the TOCTOU window, the bound stated as a function of concurrency (never "at most 3" as a flat ceiling), why a transaction-scoped advisory lock does not close it (backed by the Task 2 measurement, not assumed), and the D-08 reminder that a ceiling rejection must stay a plain 401.
- `docs/ARCHITECTURE.md`'s signin sequence diagram gained a concurrency note beside the existing `SAS` note, and its prior claim that `SecurityContextRepository` synchronously writes the session row was corrected — it now shows Spring Session's request-scoped filter committing the row after the controller returns, matching the measured ordering.
- `.claude/CLAUDE.md`'s cross-instance session-ceiling claim was qualified with the accepted bounded overshoot, one added clause, nothing else in that section changed.
- The F6 todo (`2026-08-10-toctou-race-in-concurrent-session-ceiling-enforcement.md`) moved from `pending/` to `completed/` with `resolved: 2026-08-11` and a `## Resolution` section recording the disposition, both measurements, the rejected alternatives (transaction-scoped advisory lock, database INSERT trigger, in-JVM striped lock) and why each was rejected, and the coverage caveat that the regression test is real-socket tier only (excluded from the pre-commit `fastTest` gate by `@Tag("realSocket")`).

## Verification

- `./gradlew spotlessCheck` — green.
- `./gradlew test` (full suite) — green, **385 tests, 0 failures, 0 errors** (Gradle's own HTML report counter), up from STATE.md's ~210-test baseline / the more recent 278-test Phase 07.1 baseline via several intervening quick tasks (SigninTimingEqualizationTest, AuthorizationGatingTest, InjectionAttemptTest, this task's new class, and others). No shrinkage.
- `ConcurrentSigninCeilingE2ETest` passes in its shipped `@Test` form; its teeth were proven with an actual RED run (quoted above), not merely asserted.
- `git diff --quiet HEAD -- src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java` exits 0 — confirmed at the end of both Task 1 (after the teeth check) and Task 2 (after the probe measurement). No probe or neutralization code survives.
- `git diff --stat` across the plan's two commits (`7f01dbc`, `f5fb11a`) shows exactly one `src/main` file changed (`SecurityConfiguration.java`, Javadoc only — confirmed by inspecting the diff, no executable statement touched), one new test file, and three docs/planning files (`docs/ARCHITECTURE.md`, `.claude/CLAUDE.md`, the todo).
- `AuthenticationTest` is unedited — the sequential ceiling spec (`AuthenticationTest.ConcurrentSessionCeiling`) was left untouched, as required.
- `.planning/todos/pending/2026-08-10-toctou-race-in-concurrent-session-ceiling-enforcement.md` no longer exists; `.planning/todos/completed/2026-08-10-toctou-race-in-concurrent-session-ceiling-enforcement.md` exists with a `## Resolution` section (grep count 1) and `F6` appears in `SecurityConfiguration.java` (grep count 1, confirming the accepted bound is documented beside the enforcing bean, not only in planning artifacts).

## Deviations from Plan

### Auto-fixed / operational

**1. [Rule 3 - blocking issue] Pre-commit hook's stale-lock failure on Task 1's first commit attempt**
- **Found during:** first `git commit` attempt for Task 1, after a prior invocation had been killed by a 2-minute Bash timeout mid-`fastTest`.
- **Issue:** `./gradlew fastTest` failed with `Unable to delete directory ... build/test-results/fastTest/binary` — a stale Gradle daemon from the killed run still held a file lock on `output.bin`.
- **Fix:** `./gradlew --stop` to release the daemon, then removed the stale `build/test-results/fastTest/binary` directory by hand, then retried the commit with a longer timeout. This matches the documented expectation (CLAUDE.md: "this repo's pre-commit hook runs the full fastTest suite and can take several minutes — that is expected, not a hang") plus an operational recovery step for the specific stale-lock symptom.
- **Files modified:** none (build artifact cleanup only).
- **Commit:** not applicable (no source change).

No other deviations — the plan's own decision logic (Task 2's disposition fork) was followed faithfully, and the measurement confirmed rather than falsified the plan's stated premise, so Task 3 proceeded as written without needing the halt-and-report branch.

## Known Stubs

None. No hardcoded empty values, placeholder text, or unwired data sources were introduced.

## Self-Check: PASSED

- `src/test/java/com/vrudenko/kanban_board/security/ConcurrentSigninCeilingE2ETest.java` — FOUND (created, committed in `7f01dbc`).
- `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java` — FOUND, Javadoc addition present, committed in `f5fb11a`.
- `docs/ARCHITECTURE.md` — FOUND, diagram correction and note present, committed in `f5fb11a`.
- `.planning/todos/completed/2026-08-10-toctou-race-in-concurrent-session-ceiling-enforcement.md` — FOUND with `## Resolution` section, committed in `f5fb11a`.
- Commit `7f01dbc` — FOUND in `git log`.
- Commit `f5fb11a` — FOUND in `git log`.
