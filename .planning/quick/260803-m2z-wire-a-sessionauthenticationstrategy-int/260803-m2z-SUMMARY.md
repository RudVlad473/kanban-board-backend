---
phase: quick/260803-m2z
plan: 01
subsystem: auth
tags: [spring-security, spring-session, session-management, session-fixation, tdd]

# Dependency graph
requires:
  - "org.springframework.session:spring-session-jdbc on the classpath (260802-shl) — SpringSessionBackedSessionRegistry needs a FindByIndexNameSessionRepository bean, autoconfigured only because that dependency is present"
provides:
  - "SecurityConfiguration.sessionAuthenticationStrategy bean: a CompositeSessionAuthenticationStrategy composing ConcurrentSessionControlAuthenticationStrategy (backed by SpringSessionBackedSessionRegistry) and ChangeSessionIdAuthenticationStrategy"
  - "AuthenticationController.authenticate now invokes sessionAuthenticationStrategy.onAuthentication(...) on every successful signin AND signup, before the SecurityContext is saved"
  - "SessionPersistenceE2ETest.ConcurrentSessionCeiling: converted from a tripwire (asserted absence) to a spec (asserts enforcement) — a third concurrent signin for one principal is rejected as HTTP 401 with no session cookie, delta of exactly 2 SPRING_SESSION rows across three attempts"
  - "SessionPersistenceE2ETest.SessionFixation (new): proves a signin presenting a live session cookie rotates the session id, with a row-count delta of 1 across both signins"
affects: [security, session-management, CLAUDE.md-architecture-notes]

actuals:
  tokens: 6696
  tasks: 3
  commits: 3

tech-stack:
  added: []
  patterns:
    - "Session-control bean built with a generic method signature (<S extends Session> ... (FindByIndexNameSessionRepository<S> sessionRepository)) to satisfy SpringSessionBackedSessionRegistry's type parameter without an unchecked cast"
    - "SessionRegistry constructed as a local variable inside the bean method, never its own @Bean — publishing it would make SessionManagementConfigurer hand it to the already-installed ConcurrentSessionFilter, adding a JDBC lookup to every authenticated request for a code path that's dead under maxSessionsPreventsLogin(true)"
    - "Shared MAX_CONCURRENT_SESSIONS constant used by both the sessionManagement DSL declaration and the enforcing bean, so the declared ceiling and the enforced ceiling cannot drift apart"
    - "Row-count-delta assertions (capture before/after, assert the difference) instead of absolute counts, since SPRING_SESSION accumulates rows across the whole suite run with no per-test cleanup"

key-files:
  modified:
    - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
    - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
    - src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java
    - .claude/CLAUDE.md
    - .planning/STATE.md
  renamed:
    - ".planning/todos/pending/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md -> .planning/todos/completed/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md"

key-decisions:
  - "D-01 (locked, from plan): invoke the strategy explicitly from AuthenticationController.authenticate rather than moving signin onto a real authentication filter — smaller diff, same two files that already own authentication, provable end-to-end by the existing test"
  - "R1 (locked, from plan): SpringSessionBackedSessionRegistry, constructed as a local variable, backs the ceiling — reads live SPRING_SESSION rows rather than in-memory bookkeeping that could go stale and permanently lock out a legitimate user under maxSessionsPreventsLogin(true)"
  - "sessionFixation DSL argument changed from newSession to changeSessionId so the declared strategy matches what the bean actually implements (ChangeSessionIdAuthenticationStrategy) — the two must name the same behaviour or the declaration lies about the enforcement"
  - "The sessionAuthenticationStrategy bean stayed in SecurityConfiguration, next to securityFilterChain — the Spring context started cleanly with no bean-creation or circular-reference error, so the plan's documented BeanConfiguration fallback was never needed"

patterns-established:
  - "TDD gate sequence for a fix to previously-dead configuration: rename+rewrite the tripwire test to assert the target behaviour FIRST, confirm it fails for the predicted reason (RED: expected 401, got 200), then land the production wiring (GREEN) — makes the test's failure message itself the acceptance criterion"

requirements-completed: []

coverage:
  - id: D1
    description: "A third concurrent signin by one principal is rejected over HTTP (401) while the first two succeed; the SPRING_SESSION row-count delta across all three attempts is exactly 2, not 3"
    verification:
      - kind: integration
        ref: "SessionPersistenceE2ETest.ConcurrentSessionCeiling#shouldRejectThirdSignin_whenConcurrentSessionCeilingIsReached"
        status: pass
    human_judgment: false
  - id: D2
    description: "A signin presenting an already-authenticated session's cookie rotates the session id — the returned cookie value differs from the presented one, and the row-count delta across both signins is 1 (not 2), proving the old row is deleted and re-saved under a fresh id rather than a second row being added"
    verification:
      - kind: integration
        ref: "SessionPersistenceE2ETest.SessionFixation#shouldRotateSessionId_whenSigninPresentsAnExistingSession"
        status: pass
    human_judgment: false
  - id: D3
    description: "The strategy runs on both /api/signin and /api/signup because both share AuthenticationController's private authenticate helper, and this is stated explicitly in code comments and CLAUDE.md rather than left as an undocumented side effect"
    verification:
      - kind: manual_procedural
        ref: "AuthenticationController.java call-site comment; .claude/CLAUDE.md State Management bullet, last sentence"
        status: pass
    human_judgment: true
    rationale: "The shared-call-site fact is structural (grep-verifiable), but whether it reads clearly as an intended-not-incidental design choice needs a human read."
  - id: D4
    description: "./gradlew spotlessCheck and ./gradlew test are both green — no pre-existing E2E test regressed under the new ceiling or the new id rotation"
    verification:
      - kind: integration
        ref: "./gradlew spotlessCheck -> BUILD SUCCESSFUL; ./gradlew test -> BUILD SUCCESSFUL, 0 failures across 88 test-result files"
        status: pass
    human_judgment: false
  - id: D5
    description: "Every session-management claim in .claude/CLAUDE.md is true after the change, in both places it appears (State Management bullet and Architectural Constraints bullet)"
    verification:
      - kind: manual_procedural
        ref: "grep -c 'but NOT enforced' -> 0; grep -c 'is unenforced regardless of instance count' -> 0; grep -c 'ConcurrentSessionControlAuthenticationStrategy' -> 1"
        status: pass
    human_judgment: true
    rationale: "Grep confirms the stale phrasing is gone and the new mechanism is named, but the corrected prose still needs a human read to confirm it states the truth clearly, not just differently."
  - id: D6
    description: "The source todo is closed via git mv from pending/ to completed/, content unchanged, matching the repo's established convention (3405f15, 7e1b6e1)"
    verification:
      - kind: integration
        ref: "git log --diff-filter=R -1 -> rename .planning/todos/{pending => completed}/2026-08-02-....md (100%)"
        status: pass
    human_judgment: false

duration: ~25min
completed: 2026-08-03
status: complete
---

# Quick Task 260803-m2z: Wire a SessionAuthenticationStrategy into AuthenticationController.signin Summary

**Wired a `CompositeSessionAuthenticationStrategy` into the custom `AuthenticationController.authenticate` helper so the previously-inert `maximumSessions(2)` ceiling and `sessionFixation` protection actually run on every signin and signup, converting the tripwire test that proved the defect into a spec that proves the fix, and adding a second test proving session-id rotation.**

## Performance

- **Duration:** ~25 min
- **Tasks:** 3 (Task 1 tracer/TDD, Task 2 auto/TDD, Task 3 auto)
- **Files modified:** 5 modified + 1 renamed

## Accomplishments

- **The concurrent-session ceiling is enforced, proven by a test that failed for the right reason before the fix.** `SessionPersistenceE2ETest.ConcurrentSessionCeiling` was renamed and rewritten to assert enforcement (RED confirmed first: `expected: 401, but was: 200`), then `SecurityConfiguration` gained a `sessionAuthenticationStrategy` bean — a `CompositeSessionAuthenticationStrategy` composing `ConcurrentSessionControlAuthenticationStrategy` (backed by a `SpringSessionBackedSessionRegistry` reading live `SPRING_SESSION` rows) and `ChangeSessionIdAuthenticationStrategy` — invoked from `AuthenticationController.authenticate` right after `authenticationManager.authenticate(token)` succeeds. GREEN confirmed after: the third signin for one principal now returns HTTP 401 with no session cookie, and the row-count delta across all three attempts is exactly 2.
- **Session-fixation protection is enforced too, proven by a new test.** `SessionPersistenceE2ETest.SessionFixation#shouldRotateSessionId_whenSigninPresentsAnExistingSession` presents the first signin's live cookie on a second signin — the precondition `AbstractSessionFixationProtectionStrategy.onAuthentication` requires to do anything — and asserts the returned cookie value differs from the presented one. The predicted row-count delta of 1 (old row deleted, re-saved under a fresh id, not a second row added) matched on the first run with no test-weakening needed.
- **The bean stayed in `SecurityConfiguration`, not `BeanConfiguration`.** The plan's fallback (move the bean method verbatim to `BeanConfiguration` if the context failed to start with a circular-reference error) was never triggered — the Spring context started cleanly with the generic bean method placed directly beneath `securityFilterChain`.
- **No existing test needed attention.** The full `./gradlew test` suite (88 test-result files, including every E2E class that opens a session and the Kafka Testcontainers classes) passed green on the first full run after Task 2's change, confirming the plan's premise: no existing test method signs in more than twice, and `AbstractAppTest`'s fresh-user-per-method fixture keeps principal names unique across the suite.
- **Both `.claude/CLAUDE.md` bullets that made the now-false "configured but not enforced" claim are corrected**, not just one — the State Management bullet now names the enforcing bean and the tests that prove each half, and the Architectural Constraints bullet's horizontal-scaling caveat is rewritten to say the ceiling now holds across instances *because* its registry reads from the same shared JDBC store, not despite it.
- **The source todo is closed the way this repo actually closes todos** — `git mv` from `pending/` to `completed/`, confirmed as a pure 100%-similarity rename in `git log --diff-filter=R`, matching commits `3405f15` and `7e1b6e1`. The stale `[security]` bullet is also removed from `STATE.md`'s Pending Todos list.

## Task Commits

Each task was committed atomically:

1. **Task 1: Enforce the concurrent-session ceiling end-to-end, starting from the red tripwire** - `0260df7` (feat, tracer/TDD)
2. **Task 2: Prove session-id rotation, then run the whole suite against the new controls** - `a09c7e3` (test)
3. **Task 3: Correct both CLAUDE.md claims and close the source todo per repo convention** - `6747117` (docs)

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java` - Added `MAX_CONCURRENT_SESSIONS` constant shared by the DSL and the enforcing bean; switched `sessionFixation` from `newSession` to `changeSessionId`; added the generic `sessionAuthenticationStrategy` bean method with the local (non-bean) `SpringSessionBackedSessionRegistry`
- `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java` - Added `sessionAuthenticationStrategy` field and the `onAuthentication(...)` call as the first statement in `authenticate`'s `mapTry` lambda, with a comment recording that both signin and signup share this call site
- `src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java` - `ConcurrentSessionCeiling` renamed/rewritten from tripwire to spec; new `SessionFixation` nested class added
- `.claude/CLAUDE.md` - State Management bullet and Architectural Constraints "Session persistence" bullet both corrected to state enforcement as fact
- `.planning/STATE.md` - Stale `[security]` pending-todo bullet removed
- `.planning/todos/completed/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md` - Renamed from `pending/`, content unchanged

## Decisions Made

- D-01/R1 from the plan were locked coming into execution (invoke the strategy explicitly from `AuthenticationController.authenticate`; back the ceiling with `SpringSessionBackedSessionRegistry` as a local variable) — both executed exactly as decided, no deviation
- `sessionFixation(newSession)` changed to `sessionFixation(changeSessionId)` in the DSL, per the plan's explicit instruction, so the declared strategy names the one actually implemented by `ChangeSessionIdAuthenticationStrategy`
- Bean placement: kept in `SecurityConfiguration` beside `securityFilterChain` — the documented `BeanConfiguration` fallback was not needed since the context started without error

## Deviations from Plan

None - plan executed exactly as written. Both TDD gates (Task 1's RED-then-GREEN on the renamed ceiling test, Task 2's row-count-delta prediction on the new fixation test) resolved on the first attempt with no need to weaken an assertion or investigate a premise violation.

## Issues Encountered

None. RED was confirmed for the predicted reason (`expected: 401, but was: 200`) before any production code was written; GREEN followed immediately after the production change with no iteration. The full test suite passed on the first complete run.

## User Setup Required

None - no external service configuration required. No new dependency was added (all types used were already on the resolved classpath per the plan's `T-m2z-SC` threat-register entry).

## Next Phase Readiness

- The concurrent-session ceiling and session-fixation protection are both real and proven by tests wired to the production change (removing the `onAuthentication(...)` call makes both `ConcurrentSessionCeiling` and `SessionFixation` fail)
- `.claude/CLAUDE.md` is internally consistent again — both places that discuss these controls now agree
- No blockers for other work. No new dependency, no new exception handler, no change to the `/api/signin` or `/api/signup` request/response contract

---
*Quick task: 260803-m2z*
*Completed: 2026-08-03*

## Self-Check: PASSED

- FOUND: src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
- FOUND: src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
- FOUND: src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java
- FOUND: .claude/CLAUDE.md
- FOUND: .planning/STATE.md
- FOUND: .planning/todos/completed/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md
- FOUND: 0260df7
- FOUND: a09c7e3
- FOUND: 6747117
