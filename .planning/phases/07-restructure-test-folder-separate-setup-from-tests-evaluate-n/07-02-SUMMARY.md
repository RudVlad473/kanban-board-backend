---
phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n
plan: 02
subsystem: testing
tags: [junit5, mockmvc, spring-security-test, spring-session-jdbc, refactor]

# Dependency graph
requires:
  - phase: 07-01
    provides: "support/fixtures/AbstractAppMockMvcTest (signinCookie() real POST /signin + cookie relay) and Assumption A2 empirically proven (Spring Boot 3.5.0 auto-configures the full security filter chain under @AutoConfigureMockMvc with spring-security-test on the classpath)"
provides:
  - "security/AuthenticationE2ETest.java — one @Nested-grouped, in-process (MockMvc) file replacing AuthenticationControllerTest, SessionPersistenceE2ETest, UserPersistenceE2ETest (D-02 Candidate 1, D-03 rows 21-22)"
  - "Falsification proof that the downgraded ConcurrentSessionCeiling/SessionFixation groups still exercise the real AuthenticationController.authenticate -> sessionAuthenticationStrategy.onAuthentication call site, not a vacuous pass"
affects: [07-03, 07-04, 07-05, 07-06, 07-07]

# Actuals (#2632)
actuals:
  tokens: 15240
  tasks: 3
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Real signin/signup POST + explicit Set-Cookie relay through mockMvc.perform() for the small set of classes that must keep exercising AuthenticationController.authenticate under the in-process tier -- never .with(user(userId))"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/security/AuthenticationE2ETest.java
  modified: []

key-decisions:
  - "Merged and downgraded in one edit (plan's Approach B), not merge-then-downgrade -- avoids an intermediate file mixing RestAssured and MockMvc dispatch (RESEARCH.md Pitfall 3) and avoids translating every request chain twice"
  - "Left the stale SessionPersistenceE2ETest reference inside a src/main/java/.../UserAuthenticationProvider.java comment untouched -- fixing it would violate this plan's explicit 'zero net production-code change' success criterion and the phase's 'no production code changes' boundary (CONTEXT.md Phase Boundary); documented instead as a deferred finding below rather than auto-fixed under deviation Rule 1"

patterns-established:
  - "The falsification-probe idiom (neutralize a security call site inline via Edit, run only the dependent tests, assert red, git checkout to restore, assert clean diff) as the verification pattern for any future test-tier downgrade that touches a security-control test"

requirements-completed: [TEST-02, TEST-03]

coverage:
  - id: D1
    description: "One file, security/AuthenticationE2ETest.java, covers signin, signup, Spring Session JDBC row/attribute persistence, the concurrent-session ceiling, session-fixation rotation, bcrypt-hash persistence and signup-then-signin -- every @Nested group individually runnable, no assertion lost"
    requirement: "TEST-02"
    verification:
      - kind: integration
        ref: "./gradlew test --tests '*AuthenticationE2ETest' -- 11/11 pass (3 Signin/Signup + 6 SessionPersistence groups + 2 UserPersistence groups)"
        status: pass
      - kind: integration
        ref: "./gradlew test --tests '*AuthenticationE2ETest$ConcurrentSessionCeiling' run in isolation -- 1/1 pass, proving per-group targeting survived the merge"
        status: pass
    human_judgment: false
  - id: D2
    description: "The file runs at the in-process @SpringBootTest + MockMvc tier (never RANDOM_PORT), authenticating only through real POST /signin and POST /signup with Set-Cookie relay -- never an injected pre-authenticated principal"
    requirement: "TEST-03"
    verification:
      - kind: other
        ref: "grep -rn 'io.restassured|LocalServerPort' src/test/java/com/vrudenko/kanban_board/security/ -- zero matches"
        status: pass
    human_judgment: false
  - id: D3
    description: "The concurrent-session-ceiling and session-fixation tests are proven to still exercise the real SessionAuthenticationStrategy call site: red when neutralised, green when restored"
    requirement: "TEST-02"
    verification:
      - kind: other
        ref: "Task 3 falsification probe -- see Falsification Evidence section below"
        status: pass
    human_judgment: false

# Metrics
duration: 48min
completed: 2026-08-09
status: complete
---

# Phase 7 Plan 2: Auth/Session E2E Test Merge + Tier Downgrade + Falsification Summary

**Merged AuthenticationControllerTest, SessionPersistenceE2ETest and UserPersistenceE2ETest into one `@Nested`-grouped, in-process MockMvc test (`security/AuthenticationE2ETest`), and proved by falsification that the concurrent-session-ceiling and session-fixation controls still exercise the real `AuthenticationController.authenticate` call site after the downgrade.**

## Performance

- **Duration:** 48 min
- **Started:** 2026-08-09T14:43:00Z (approx, first file reads following 07-01's completion)
- **Completed:** 2026-08-09T15:31:00Z
- **Tasks:** 3
- **Files modified:** 4 (1 created, 3 deleted)

## Falsification Evidence (Task 3)

**Probe:** commented out the single `sessionAuthenticationStrategy.onAuthentication(authentication, request, response);` call inside `AuthenticationController.authenticate`'s `mapTry` lambda, rebuilt, and ran only `*AuthenticationE2ETest$ConcurrentSessionCeiling` and `*AuthenticationE2ETest$SessionFixation`.

**Both went red, as required:**

- `ConcurrentSessionCeiling.shouldRejectThirdSignin_whenConcurrentSessionCeilingIsReached`:
  ```
  org.opentest4j.AssertionFailedError:
  expected: 401
   but was: 200
  ```
  With the strategy neutralised, the ceiling never fires, so the third signin for the same
  principal succeeds instead of being rejected -- the exact failure mode the test exists to catch.

- `SessionFixation.shouldRotateSessionId_whenSigninPresentsAnExistingSession`:
  ```
  java.lang.AssertionError:
  Expecting actual not to be null
  ```
  With rotation neutralised, re-signing-in on an existing session sets no new `Set-Cookie` header
  at all (the existing session is simply reused), so the test's "assert a fresh cookie value came
  back" assertion fails on a null cookie.

**Restore:** `git checkout -- src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java`, rebuilt, re-ran `*AuthenticationE2ETest` in full -- **11/11 pass**, including both previously-failing groups.

**Final state:** `git status --porcelain` and `git diff --stat -- src/main/` both empty -- production code is byte-identical to where the plan started.

**Verdict on Assumption A2 (RESEARCH.md):** confirmed true and now empirically verified for these two specific controls (it was already proven true for the general MockMvc-security-autoconfiguration case in 07-01 via `ColumnLockingE2ETest`) -- Spring Boot auto-configures the full security filter chain under `@AutoConfigureMockMvc` with `spring-security-test` on the classpath, and the downgraded `ConcurrentSessionCeiling`/`SessionFixation` groups genuinely reach `AuthenticationController.authenticate`'s `sessionAuthenticationStrategy.onAuthentication` call, not a shortcut that bypasses it. No stop condition was triggered; no replan to the 11/11 KEEP/DOWNGRADE split was needed.

## Accomplishments

- Consolidated three real-HTTP authentication/session test classes (`AuthenticationControllerTest`, `SessionPersistenceE2ETest`, `UserPersistenceE2ETest`) into one file, `security/AuthenticationE2ETest.java`, with every `@Nested` group name and per-test/class Javadoc rationale carried across verbatim (referents adjusted where they said "this class" in a way that only made sense pre-merge)
- Downgraded from the real-socket RestAssured/`RANDOM_PORT` tier to the in-process `@SpringBootTest` + `MockMvc` tier (D-03 rows 21-22), authenticating exclusively through real `POST /signin`/`POST /signup` requests via `AbstractAppMockMvcTest.signinCookie()` and inline `mockMvc.perform` calls for the rejection/relay cases the shared helper cannot express -- never `.with(user(userId))`
- Closed the pre-existing defect RESEARCH.md flagged: `AuthenticationControllerTest` used to manually re-declare the real-HTTP fixture base's port/cookie-name/context-path fields and `@BeforeEach` RestAssured wiring instead of extending `AbstractAppE2ETest`; that duplication no longer exists anywhere in the tree
- 11/11 combined `@Test` methods pass (`grep -c "@Test"` on the merged file matches the pre-merge combined count of 3 + 6 + 2)
- Verified per-group test targeting survived the merge: `--tests '*AuthenticationE2ETest$ConcurrentSessionCeiling'` runs and passes in isolation
- Proved by falsification (Task 3, see above) that the concurrent-session ceiling and session-fixation controls are genuinely still exercised post-downgrade, not passing vacuously

## Task Commits

Each task was committed atomically:

1. **Task 1: Create the merged security/AuthenticationE2ETest at the in-process tier** - `3fa3a2d` (feat)
2. **Task 2: Delete the three source classes and confirm the duplicated fixture wiring is gone** - `2800487` (refactor)
3. **Task 3: Falsify the downgraded session tests against the real session-authentication call site** - no commit (by design; the probe left zero net diff, confirmed by `git status --porcelain` and `git diff --stat -- src/main/` both returning empty)

**Plan metadata:** (pending -- final docs commit follows this SUMMARY)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/security/AuthenticationE2ETest.java` - New merged, in-process, `@Nested`-grouped test (created)
- `src/test/java/com/vrudenko/kanban_board/security/AuthenticationControllerTest.java` - Deleted (merged in)
- `src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java` - Deleted (merged in)
- `src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java` - Deleted (merged in)
- `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java` - Temporarily edited and restored during Task 3's falsification probe; final state byte-identical to plan start (verified by `git diff --stat`)

## Decisions Made

- Merged and downgraded in a single edit per the plan's chosen Approach B, rather than merging first at the real-socket tier and downgrading afterward -- avoids ever committing an intermediate file that mixes RestAssured and MockMvc dispatch (RESEARCH.md Pitfall 3) and avoids translating each request chain twice
- Declared the class's own `MockMvc`/`ObjectMapper`/`JdbcTemplate`/`COOKIE_NAME` fields directly on `AuthenticationE2ETest` (matching the existing `ColumnLockingE2ETest` reference pattern) rather than exposing accessors on `AbstractAppMockMvcTest`, since that base class's own fields are intentionally private
- Left the one now-stale `SessionPersistenceE2ETest` class-name reference inside a `src/main/java/.../UserAuthenticationProvider.java` comment unedited -- see Deviations below

## Deviations from Plan

### Discovered but deliberately not auto-fixed

**1. Stale test-class-name reference in production comment**
- **Found during:** Task 2 (pre-delete grep sweep for external references to the three source classes)
- **Issue:** `UserAuthenticationProvider.java` carries a comment -- "Enforced by SessionPersistenceE2ETest.SigninPersistence#shouldNotPersistBcryptHash_whenSigninSucceeds" -- that now names a deleted class. The equivalent test lives at `AuthenticationE2ETest.SigninPersistence#shouldNotPersistBcryptHash_whenSigninSucceeds` after this merge.
- **Why not auto-fixed under deviation Rule 1:** this plan's own `<success_criteria>` states "Zero net production-code change," and the phase's `CONTEXT.md` Phase Boundary states this phase "Does not cover anything outside `src/test/java/` -- no production code changes." Both are explicit, locked constraints that this specific class of change directly contradicts (renaming a comment inside `src/main/java` is still a production-code change, however small). Treated as a deferred finding rather than an in-scope Rule 1 fix.
- **Recommendation:** a one-line follow-up (quick task or included in a later phase touching this file) to update the comment's class reference.
- **Files affected (not modified):** `src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java:38`

---

**Total deviations:** 1 discovered-but-deferred (no production code touched)
**Impact on plan:** None on this plan's own scope; a small, purely cosmetic documentation-accuracy gap left for a future task.

## Issues Encountered

- The pre-commit hook's `fastTest` run collided twice with Gradle daemon contention from the four sibling parallel-plan worktrees also running `./gradlew` concurrently: once a shared daemon was killed mid-run by a sibling's `./gradlew --stop` (retried cleanly), and once a stray daemon left a file lock on `build/test-results/fastTest/binary/output.bin` from an earlier timed-out attempt (resolved with `./gradlew --stop` plus removing the locked directory before retrying, consistent with the same failure mode `docs/SESSION_LESSONS.md` and 07-01's own SUMMARY already documented). No code change required -- tooling/environment note only.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `security/AuthenticationE2ETest.java` stands as the second worked MockMvc-conversion reference (alongside 07-01's `ColumnLockingE2ETest`) for any remaining downgrade classes named in RESEARCH.md's verdict table
- D-02 Candidate 1 (the one unconditionally-justified merge) is closed; Candidates 2-4 (Column/Task/Subtask/Board, conditional on their sibling E2E classes downgrading first) remain each executor's per-family discretion in later plans, per RESEARCH.md's own framing
- Assumption A2 is now verified for both the general case (07-01) and the two security-control-specific cases this plan covers -- no outstanding risk flagged for plans 07-03..07-06 that rely on the same MockMvc auth pattern
- One small, deferred documentation-accuracy item (stale class-name reference in `UserAuthenticationProvider.java`) noted above for a future quick task

---
*Phase: 07-restructure-test-folder-separate-setup-from-tests-evaluate-n*
*Completed: 2026-08-09*

## Self-Check: PASSED

- `src/test/java/com/vrudenko/kanban_board/security/AuthenticationE2ETest.java` confirmed present on disk
- All three deleted files (`AuthenticationControllerTest.java`, `SessionPersistenceE2ETest.java`, `UserPersistenceE2ETest.java`) confirmed absent from `src/test/java/com/vrudenko/kanban_board/security/`
- Both task commit hashes (`3fa3a2d`, `2800487`) confirmed present in `git log`
- `git status --porcelain` and `git diff --stat -- src/main/` both confirmed empty as of this SUMMARY's authoring
