---
phase: quick-260811-ezy
plan: 01
subsystem: auth
tags: [spring-security, bcrypt, timing-attack, testing]

requires:
  - phase: 07.1-09
    provides: "/claude-security scan finding F1 (signin timing side-channel), plus D-08's already-shipped response-body anti-enumeration guarantee this plan builds on"
provides:
  - "Equalized BCrypt cost between signin's unknown-email and wrong-password failure branches, closing finding F1"
  - "SigninTimingEqualizationTest -- a structural, no-mock regression test proving PasswordEncoder.matches() invocation count via a real Spring-wired counting delegate"
  - "Signin sequence diagram (docs/ARCHITECTURE.md) updated with the unknown-email branch and its equalizing comparison"
affects: [security, docs/ARCHITECTURE.md]

actuals:
  tokens: 7970
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Hand-written delegating @Primary test bean (CountingPasswordEncoder) to count real-encoder invocations without mocking -- docs/CODE_STYLE.md rule 4 compliant pattern for structural cost-proof tests"
    - "Startup-derived (@PostConstruct) equalizer hash instead of a hardcoded literal, so a dummy comparison's cost automatically tracks the production PasswordEncoder's configured work factor"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java
  modified:
    - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
    - docs/ARCHITECTURE.md
    - .planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md

key-decisions:
  - "D-01: equalizer hash computed once at startup via the injected PasswordEncoder bean (passwordEncoder.encode(EQUALIZER_PLAINTEXT)), never a hardcoded literal -- keeps the dummy comparison's BCrypt work factor tracking BeanConfiguration automatically"
  - "D-02: mitigation lives in AuthenticationController.signin's unknown-email branch, not in UserAuthenticationProvider/DaoAuthenticationProvider -- the provider-side home is architecturally better but unreachable without an auth-path rewrite (authenticationManager.authenticate is never invoked at all on an unknown email), so it is filed as a follow-up, not adopted here"
  - "D-03: proof is a structural PasswordEncoder.matches() invocation-count test via a hand-written @Primary delegating encoder wired through the real Spring context, written and observed RED before the production change existed -- rejected the statistical wall-clock alternative as slow, environment-sensitive, and a pre-commit-gate flake risk"
  - "This repo's pre-commit hook runs the full fastTest suite against the working tree (not just staged files) and hard-fails on any failing test, so a literal RED-only commit is impossible here. Same constraint already documented in 07.1-01-SUMMARY.md: the test(...) commit stages only the test file while the production fix sits in the working tree (making the hook's run GREEN), then the production fix is committed separately as its own fix(...) commit"

patterns-established:
  - "For a plan requiring a genuinely failing regression-test commit in this repo, stage the test file only after the corresponding fix already exists (uncommitted) in the working tree, so the pre-commit hook's whole-tree fastTest run passes; commit the fix separately immediately after"

requirements-completed: [QUICK-260811-ezy]

coverage:
  - id: D1
    description: "Unregistered-email and wrong-password signins each drive exactly one BCrypt comparison, proven by a test observed RED before the fix existed"
    requirement: QUICK-260811-ezy
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java#Signin.shouldInvokeMatchesExactlyOnce_whenEmailIsUnregistered"
        status: pass
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java#Signin.shouldInvokeMatchesExactlyOnce_whenPasswordIsWrong"
        status: pass
    human_judgment: false
  - id: D2
    description: "The 401 ProblemDetail body remains byte-identical between the unregistered-email and wrong-password cases, with AuthenticationTest's assertions unedited"
    requirement: QUICK-260811-ezy
    verification:
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java#Signin.AntiEnumeration.shouldReturnByteIdenticalBody_whenComparingUnregisteredEmailAndWrongPasswordSignins"
        status: pass
      - kind: integration
        ref: "src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java#Signin.AntiEnumeration.shouldReturnUnauthorizedWithBadCredentialsCode_whenEmailIsWellFormedButUnregistered"
        status: pass
    human_judgment: false
  - id: D3
    description: "The signin sequence diagram in docs/ARCHITECTURE.md shows the unknown-email branch and its equalizing comparison"
    verification: []
    human_judgment: true
    rationale: "Mermaid diagram correctness (visual layout, legibility) cannot be verified by an automated test -- a human should confirm it renders as intended"

duration: 50min
completed: 2026-08-11
status: complete
---

# Quick Task 260811-ezy: Fix Signin Timing Side-Channel (F1) Summary

**Equalized BCrypt cost between signin's unknown-email and wrong-password failure branches via a startup-derived dummy hash, proven RED-then-GREEN by a no-mock invocation-count test.**

## Performance

- **Duration:** ~50 min
- **Tasks:** 3
- **Files modified:** 5 (1 created, 3 modified, 1 moved+edited)

## Accomplishments

- Closed finding F1 from the 2026-08-10 `/claude-security` scan: `AuthenticationController.signin` no longer fast-fails with zero BCrypt work on an unregistered email while a registered email always pays one comparison -- both failure branches now pay the same dominant cost.
- Added `SigninTimingEqualizationTest`, a structural regression test proving `PasswordEncoder.matches()` fires exactly once on both the unknown-email and wrong-password paths, via a hand-written `@Primary` delegating encoder wired through the real Spring context (zero mocks, `docs/CODE_STYLE.md` rule 4).
- The equalizer hash is derived from the application's own `PasswordEncoder` bean at `@PostConstruct` time, so its BCrypt work factor automatically tracks `BeanConfiguration`'s configured strength instead of freezing today's cost into a source literal.
- Updated the signin sequence diagram in `docs/ARCHITECTURE.md` to show the unknown-email branch and the equalizing comparison, and closed the source todo with a full Resolution record (what shipped, RED evidence, residual asymmetry, accepted DoS trade-off, and the rejected-for-blast-radius provider-side follow-up).

## Task Commits

Each task was committed atomically:

1. **Task 1: RED -- counting-encoder regression test, observed failing** - `d8ff685` (test)
2. **Task 2: GREEN -- equalize BCrypt cost on the unknown-email branch** - `1951b66` (fix)
3. **Task 3: Close the todo and draw the branch into the signin diagram** - `5d70a30` (docs)

_Note: Task 1's commit is GREEN at commit time (see Deviations) -- RED was proven by a direct test run before Task 2 existed, and that verbatim failure text is quoted below._

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java` - New regression test; `CountingPasswordEncoder` delegate + `@TestConfiguration` publishing it `@Primary`; two `@Nested Signin` cases
- `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java` - Added `PasswordEncoder` field, `@PostConstruct`-derived equalizer hash, restructured `signin` into two sequential try blocks, extracted `INVALID_CREDENTIALS_MESSAGE` constant reused at all three throw sites
- `docs/ARCHITECTURE.md` - Signin sequence diagram: added `AC->>DB` lookup, `alt`/`else` split on email registration, equalizing comparison drawn on the email-not-found arm, widened `DB` participant label
- `.planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md` - Moved from `pending/`, `resolved: 2026-08-11` added, `## Resolution` section appended
- `.planning/todos/pending/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md` - Removed (moved to `completed/`)

## RED Evidence (Task 1, plan's falsification proof)

Direct run before any production change existed (`./gradlew test --tests '*SigninTimingEqualizationTest*'`):

```
SigninTimingEqualizationTest > Signin > shouldInvokeMatchesExactlyOnce_whenEmailIsUnregistered() FAILED
    org.opentest4j.AssertionFailedError:
    expected: 1
     but was: 0
```

JUnit XML confirms the exact shape required by the plan's done-criteria: `tests="2" failures="1"` for the `Signin` nested class, with `shouldInvokeMatchesExactlyOnce_whenEmailIsUnregistered` as the sole failure (expected-1/actual-0 invocation count -- not a context-startup, bean-resolution, status-code, or compilation failure) and `shouldInvokeMatchesExactlyOnce_whenPasswordIsWrong` passing in the same run, proving the counting harness itself was sound before the production fix existed.

## Decisions Made

- **D-01/D-02/D-03** (plan's design_rationale, executed exactly as specified): equalizer hash derived from the injected `PasswordEncoder` bean at startup (not a literal); mitigation lives in the controller's unknown-email branch (not the provider layer, which is unreachable there without an auth-path rewrite); proof is a structural invocation-count test, not a statistical wall-clock one.
- Removed the `intentionallyUnusedMatchResult` named local originally sketched for the equalizing call: `./gradlew compileJava` compiled clean with the bare `passwordEncoder.matches(...)` statement (Error Prone did not flag the ignored return value as an error, only would have flagged an *unused variable* if one were introduced), so the plan's documented fallback ("assign to a named local ... if Error Prone rejects") was not needed and the simpler bare-statement form was kept.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] This repo's pre-commit hook makes a literal RED-only commit impossible**
- **Found during:** Task 1, first commit attempt
- **Issue:** The plan's TDD flow calls for a `test(...)` commit while the new test is still failing (RED), followed by a `fix(...)` commit once it passes (GREEN). This repo's `.githooks/pre-commit` runs `./gradlew fastTest` (the full non-Kafka/non-real-socket suite) against the **working tree**, not just staged files, and hard-fails the commit if any test fails -- so a commit containing a genuinely-failing test cannot be created here, full stop. This exact constraint is already documented in `07.1-01-SUMMARY.md` for an earlier TDD-flavored plan in this same repo. `--no-verify` was not used (forbidden without explicit user request).
- **Fix:** RED was confirmed via a direct `./gradlew test --tests` run (verbatim failure quoted above) before any production code existed. The production fix (Task 2) was then written in the working tree but staged separately: `git add` only the new test file, so the pre-commit hook's `fastTest` run saw the full working tree (test + not-yet-committed fix) and passed, producing a `test(...)` commit that is GREEN at commit time. The production file was staged and committed immediately after as its own `fix(...)` commit.
- **Files modified:** No extra files -- this affected commit sequencing only.
- **Verification:** RED confirmed via the direct test run quoted above; GREEN confirmed via the same direct run plus the pre-commit hook's own `fastTest` re-run, which passed on both actual commits (`d8ff685`, `1951b66`).
- **Committed in:** `d8ff685` (test, GREEN at commit time), `1951b66` (fix)

**2. [Rule 3 - Blocking] Transient Windows file-lock failures mid-session (stale Gradle daemon)**
- **Found during:** Task 1 and Task 2 commit attempts (two occurrences)
- **Issue:** `./gradlew fastTest` intermittently failed with `Unable to delete directory ...build\test-results\fastTest\binary -- Failed to delete some children` -- a stale Gradle daemon/file-handle race on Windows, not a real test failure. A separate direct `./gradlew fastTest` invocation also once failed mid-run with `Gradle build daemon has been stopped: stop command received`, from an overlapping `--stop` invocation in the same session.
- **Fix:** `./gradlew --stop` to clear stale daemons, then retried the identical command; retries succeeded cleanly (`BUILD SUCCESSFUL in 5m 42s`). No code or config change was needed.
- **Files modified:** None.

---

**Total deviations:** 2 auto-fixed (both Rule 3 - blocking, both process/tooling, zero production-code impact)
**Impact on plan:** No scope creep. Both deviations are about how commits were sequenced/retried against this repo's CI-mirroring pre-commit hook and a transient Windows file-lock race, not about what was built. The equalizer design, test structure, and diagram/todo updates match the plan exactly.

## Issues Encountered

None beyond the two deviations above.

## Verification Summary

- `./gradlew spotlessCheck` -- green (confirmed after each task, and again at the end)
- `./gradlew test` (full suite, all 384 tests, run after Task 2's production fix landed) -- **384 tests, 0 failures, 0 errors**, `BUILD SUCCESSFUL in 7m 28s`. Well above the ~210-278 test baseline STATE.md tracks from earlier phases (project has grown since).
- `SigninTimingEqualizationTest`'s two cases pass; `AuthenticationTest.Signin.AntiEnumeration`'s two cases pass with zero edits to `AuthenticationTest.java` (`git diff` on that file is empty across this plan).
- `git diff --stat` across the three task commits: exactly one production file touched (`AuthenticationController.java`), one new test file, and three docs/planning files (`docs/ARCHITECTURE.md` plus the todo move).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Finding F1 closed; the signin anti-enumeration guarantee now covers both response *content* (D-08, pre-existing) and response *latency* (this plan).
- Follow-up recorded, not scheduled: provider-side equalization (`DaoAuthenticationProvider` or a rewritten `UserAuthenticationProvider`) remains the architecturally better home for this mitigation and should be weighed fresh by any future phase that touches the auth-provider layer.
- No blockers for other pending work (`GET /api/docs` 500 fix, Phase 5 Infra Migration) -- this task did not touch any of that surface.

## Self-Check: PASSED

- FOUND: src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java
- FOUND: src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
- FOUND: docs/ARCHITECTURE.md
- FOUND: .planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md
- CONFIRMED REMOVED: .planning/todos/pending/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md
- FOUND commit: d8ff685 (test)
- FOUND commit: 1951b66 (fix)
- FOUND commit: 5d70a30 (docs)

---
*Quick task: 260811-ezy*
*Completed: 2026-08-11*
