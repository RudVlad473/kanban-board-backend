---
phase: quick/260802-shl
plan: 01
subsystem: auth
tags: [spring-session, spring-security, jdbc, session-management, h2, postgresql]

# Dependency graph
requires: []
provides:
  - "org.springframework.session:spring-session-jdbc on the runtime classpath (BOM-resolved to 3.5.0, no hand-pinned version), so the pre-existing spring.session.* properties are live instead of inert"
  - "SessionPersistenceE2ETest: proves SPRING_SESSION/SPRING_SESSION_ATTRIBUTES tables are created, a signin writes a real SPRING_SESSION row plus a SPRING_SECURITY_CONTEXT attribute row, and the persisted context carries no bcrypt hash"
  - "SessionPersistenceE2ETest.ConcurrentSessionCeiling: tripwire proving maximumSessions(2)/maxSessionsPreventsLogin is currently unenforced on the custom signin path"
  - "Security-marked todo filing the SessionAuthenticationStrategy gap (unenforced ceiling + unrotated session id on login)"
affects: [security, session-management, CLAUDE.md-architecture-notes]

actuals:
  tokens: 6262
  tasks: 3
  commits: 3

tech-stack:
  added: ["org.springframework.session:spring-session-jdbc (Spring Boot 3.5.0 BOM-resolved to 3.5.0)"]
  patterns:
    - "identify a newly-created accumulating-table row by set difference (capture PRIMARY_IDs before/after) rather than absolute count or ordering, since SPRING_SESSION carries no FK to users and rows are never cleared by AbstractAppTest's per-test cleanup"
    - "tripwire test that documents unenforced-but-configured security behaviour: designed to go RED the day the real fix lands, forcing the test and its Javadoc to be updated together instead of drifting silently out of sync with the fix"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java
    - .planning/todos/pending/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md
  modified:
    - build.gradle
    - src/main/resources/application.properties
    - src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
    - .claude/CLAUDE.md

key-decisions:
  - "D-01 (locked, from CONTEXT.md): wire spring-session-jdbc for real rather than deleting the inert properties and downgrading docs to match the bug"
  - "D-02 (locked, from CONTEXT.md): rely solely on spring.session.jdbc.initialize-schema=always for schema creation — no third manual DDL script, no new manual pre-merge step"
  - "Research correction during planning: initialize-schema=always is safe to re-run NOT because Spring Session's schema script is idempotent (it is bare CREATE TABLE, verified against upstream — it is not), but because Spring Boot's JdbcSessionDataSourceScriptDatabaseInitializer sets continueOnError(true). Documented in application.properties so the resulting 'relation already exists' restart log noise isn't mistaken for a failed deploy."
  - "Research correction during planning: maximumSessions(2)/maxSessionsPreventsLogin and sessionFixation(newSession) are dead configuration of the same class as the session-store bug — both require a SessionAuthenticationStrategy that only runs inside an authentication filter, and this app's custom AuthenticationController.signin never invokes one. Proven with a tripwire test (Task 2) and corrected in CLAUDE.md (Task 3) rather than left standing as fact; the real fix filed as a security-marked todo, out of this quick task's scope."
  - "SecurityConfiguration.java and AuthenticationController.java deliberately left unmodified — HttpSessionSecurityContextRepository already writes to whatever request.getSession() returns, and SessionRepositoryFilter (order Integer.MIN_VALUE+50) wraps the request ahead of springSecurityFilterChain (order -100), so the JDBC-backed session is picked up with zero code change there. Review conclusion recorded as a class-level comment in SessionPersistenceE2ETest."

patterns-established:
  - "bcrypt hash marker ($2a$) derived as a test-local constant (BCryptPasswordEncoder's own prefix) rather than fetched from the fixture user's real hash, so the regression test doesn't depend on repository row shape"

requirements-completed: []

coverage:
  - id: D1
    description: "spring-session-jdbc wired via build.gradle with no hand-pinned version; the three pre-existing spring.session.* properties in application.properties are live"
    verification:
      - kind: integration
        ref: "SessionPersistenceE2ETest.SchemaCreation#shouldCreateSpringSessionTables_whenApplicationStarts"
        status: pass
    human_judgment: false
  - id: D2
    description: "A signin writes a real SPRING_SESSION row and a SPRING_SECURITY_CONTEXT SPRING_SESSION_ATTRIBUTES row — sessions survive a restart instead of living only in Tomcat memory"
    verification:
      - kind: integration
        ref: "SessionPersistenceE2ETest.SigninPersistence#shouldAddOneSessionRow_whenSigninSucceeds"
        status: pass
      - kind: integration
        ref: "SessionPersistenceE2ETest.SigninPersistence#shouldPersistSecurityContextAttribute_whenSigninSucceeds"
        status: pass
    human_judgment: false
  - id: D3
    description: "UserAuthenticationProvider's minimal-principal guard (no bcrypt hash in the persisted SecurityContext) is enforced by a regression test for the first time"
    verification:
      - kind: integration
        ref: "SessionPersistenceE2ETest.SigninPersistence#shouldNotPersistBcryptHash_whenSigninSucceeds"
        status: pass
    human_judgment: false
  - id: D4
    description: "The concurrent-session ceiling (maximumSessions(2)) is proven unenforced on the custom signin path, and the finding is filed as a security-marked todo rather than left silently broken"
    verification:
      - kind: integration
        ref: "SessionPersistenceE2ETest.ConcurrentSessionCeiling#shouldAllowThreeConcurrentSessions_whenMaxSessionsIsConfiguredButNoAuthenticationStrategyRuns"
        status: pass
    human_judgment: false
  - id: D5
    description: "Every session-related documentation claim in .claude/CLAUDE.md, UserAuthenticationProvider, and application.properties is corrected to match verified code behaviour, including the concurrent-session claim this plan discovered was independently false"
    verification:
      - kind: manual_procedural
        ref: ".claude/CLAUDE.md lines ~305-308 and ~358, UserAuthenticationProvider.java:33-38, application.properties Spring Session block — reviewed line-by-line against code during Task 3"
        status: pass
    human_judgment: true
    rationale: "Documentation-accuracy claims are prose assertions verified by review, not by an automated test; each specific correction is cross-referenced to the test that enforces the underlying behaviour where one exists (D1-D4), but the prose wording itself needs a human read to confirm it reads as intended."
  - id: D6
    description: "No .sql file added anywhere (D-02), SecurityConfiguration.java and AuthenticationController.java unmodified, full suite (spotlessCheck + test) green"
    verification:
      - kind: integration
        ref: "git diff 8020969 HEAD --name-only | grep '\\.sql$' -> none; git diff --stat -- SecurityConfiguration.java AuthenticationController.java -> empty; ./gradlew spotlessCheck test -> BUILD SUCCESSFUL, 0 failures/errors across 84 test result files"
        status: pass
    human_judgment: false

duration: ~20min
completed: 2026-08-02
status: complete
---

# Quick Task 260802-shl: Fix the Dead Spring Session JDBC Configuration Summary

**Wired `spring-session-jdbc` for real (BOM-resolved, no hand-pinned version) so the pre-existing `spring.session.*` properties stop being silently inert, proved it with a new `SessionPersistenceE2ETest` that asserts real JDBC rows rather than assuming schema creation, and discovered + documented + filed a second, independent dead-configuration bug (the unenforced concurrent-session ceiling) found while auditing the same block of documentation.**

## Performance

- **Duration:** ~20 min
- **Tasks:** 3 (Task 1 tracer/TDD, Task 2 auto, Task 3 auto)
- **Files modified:** 6 (2 created, 4 modified)

## Accomplishments

- **The core bug is fixed and proven, not assumed.** `org.springframework.session:spring-session-jdbc` is now on the runtime classpath (added to `build.gradle` with no hand-pinned version — Spring Boot 3.5.0's BOM resolved it to `3.5.0`), so `spring.session.store-type=jdbc` and `spring.session.jdbc.initialize-schema=always`, both present in `application.properties` for a long time, now do what they were always meant to do. `SessionPersistenceE2ETest` proves — via real H2 `INFORMATION_SCHEMA` and table queries, not inference — that the `SPRING_SESSION`/`SPRING_SESSION_ATTRIBUTES` tables get created, that a signin adds a real `SPRING_SESSION` row, and that it adds a `SPRING_SECURITY_CONTEXT` `SPRING_SESSION_ATTRIBUTES` row.
- **A previously-unverifiable security guard is now enforced by test.** `UserAuthenticationProvider` has always deliberately built a minimal principal (username only, no password hash) specifically to keep `passwordHash` out of the session store — but until this dependency existed, nothing was ever actually persisted, so the guard was unverifiable. `SessionPersistenceE2ETest.SigninPersistence#shouldNotPersistBcryptHash_whenSigninSucceeds` decodes the persisted `ATTRIBUTE_BYTES` and asserts the bcrypt hash prefix (`$2a$`, derived as a test-local constant, not fetched from the fixture user) is absent.
- **A second, independent dead-configuration bug was found and not swept under the rug.** While auditing the same documentation block this plan corrects, research (recorded in `<research_corrections>` in the plan) found that `SecurityConfiguration`'s `maximumSessions(2).maxSessionsPreventsLogin(true)` and `sessionFixation(newSession)` are configured but never applied — both require a `SessionAuthenticationStrategy` that only runs inside an authentication filter, and this app's custom `AuthenticationController.signin` authenticates directly via `authenticationManager.authenticate(token)`, never invoking one. A new tripwire test (`SessionPersistenceE2ETest.ConcurrentSessionCeiling`) proves a single principal can hold three concurrent sessions today — one more than the configured ceiling — and is deliberately written to go RED the day the real fix lands, so the fix can't ship without the test being revisited.
- **The real fix for that second bug is filed, not attempted.** It changes the authentication path for every request in the app and needs its own signin/signup/logout test coverage — outside a quick task's blast radius. Filed as `.planning/todos/pending/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md`, marked `severity: security` (not `minor`, unlike every other pending todo in this repo) so it isn't triaged as cosmetic, and cross-referencing the tripwire test and the threat register entry (`T-shl-01`) from the plan.
- **Every session-related documentation claim is corrected to match verified reality**, including the one this plan discovered was independently false. `.claude/CLAUDE.md`'s State Management block now names the properties instead of citing rot-prone line numbers, correctly attributes the `SecurityContext` to `spring_session_attributes` (not `spring_session` itself), states the effective 180-minute idle timeout (up from the previous 1-minute effective timeout — a real behaviour change this task introduces) alongside the independent 10-minute cookie cap, and — critically — no longer asserts the concurrent-session ceiling as settled fact. `UserAuthenticationProvider`'s comment now says "on session change" instead of "on every authenticated request" and points at the new test. `application.properties` documents the real `initialize-schema=always` idempotency mechanism (`continueOnError`, not an idempotent script — Spring Session's `schema-postgresql.sql` is bare `CREATE TABLE`, verified against upstream) so the "relation already exists" restart log noise this change introduces isn't mistaken for a failed deploy.
- **Full verification green**, including the whole suite (not just the new class) since the plan flagged that the effective session timeout and cookie format change for every E2E test in the suite: `./gradlew spotlessCheck test` — `BUILD SUCCESSFUL`, 0 failures/errors across 84 test result files.

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire spring-session-jdbc and prove the JDBC store actually does the work** - `1b8d378` (test, tracer/TDD)
2. **Task 2: Sanity-check the behaviours the store switch touches, and file what it cannot fix** - `2000984` (test)
3. **Task 3: Correct every documentation claim so all of them are true** - `500fde5` (docs)

## Files Created/Modified

- `build.gradle` - Added `implementation 'org.springframework.session:spring-session-jdbc'` (no version, BOM-resolved) with a comment explaining why it looks unused to a naive audit (nothing in `src/main` imports `org.springframework.session`) and must not be removed
- `src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java` - New: 4 behaviours from Task 1 (schema creation, session row on signin, `SPRING_SECURITY_CONTEXT` attribute row, no bcrypt hash persisted) plus Task 2's concurrent-session tripwire; class Javadoc records the `SecurityConfiguration:38` review conclusion
- `.planning/todos/pending/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md` - New: security-marked todo for the unenforced ceiling + session-fixation exposure, with two candidate fix shapes and their trade-offs
- `.claude/CLAUDE.md` - State Management block (SecurityContext storage location, concurrent-session claim, timeout framing) and Architectural Constraints' Session persistence bullet corrected to match verified behaviour
- `src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java` - Comment corrected: `spring_session_attributes` (not `spring_session`), "on session change" (not "on every authenticated request"), points at the new test
- `src/main/resources/application.properties` - Spring Session block gains a comment recording the real `initialize-schema=always` idempotency mechanism and why no manual DDL script accompanies it (D-02)

## Decisions Made

- D-01/D-02 from `260802-shl-CONTEXT.md` were locked coming into planning (wire the dependency for real; rely solely on `initialize-schema=always`) — both executed exactly as decided, no deviation
- Identified the newly-created `SPRING_SESSION` row per test by set difference (capture `PRIMARY_ID`s before/after signin) rather than absolute count or ordering, since the table has no FK to users and rows accumulate across the whole suite run — this was the plan's explicit constraint 1 and is why `signinAndCaptureNewSessionPrimaryId()` exists as a shared helper
- Chose `INFORMATION_SCHEMA.TABLES` existence checks (not just an unguarded `SELECT COUNT(*)`) for the schema-creation test, so the assertion proves the tables are registered in H2's metadata, not merely that a query against them happens not to throw
- Reused Task 1's `SecurityConfiguration:38` review-conclusion Javadoc for Task 2's requirement to record the same conclusion in the test class, rather than duplicating it — both plan tasks target the same class, and the conclusion only needed writing once

## Deviations from Plan

None - plan executed exactly as written, including both research corrections the plan itself had already identified during planning (the `initialize-schema=always` idempotency mechanism, and the dead concurrent-session configuration). No new deviations surfaced during execution.

## Issues Encountered

None. All four Task 1 behaviours, the Task 2 tripwire, and the full `./gradlew spotlessCheck test` suite passed on first attempt after implementation.

## User Setup Required

None - no external service configuration required. The schema-creation mechanism (`initialize-schema=always`) is fully automatic; no manual pre-merge DDL step was introduced, per D-02.

## Next Phase Readiness

- Session loss on every EC2 redeploy (the original defect) is fixed and proven; `master` can continue to auto-deploy on every push without discarding logged-in users
- A new, independent, security-marked todo now exists for the unenforced concurrent-session ceiling — not a blocker for this task, but should be picked up before the ceiling is relied upon for anything (it currently provides no protection)
- `SessionPersistenceE2ETest.ConcurrentSessionCeiling`'s tripwire test will go RED automatically the day someone wires the `SessionAuthenticationStrategy` fix — that failure is the intended signal to also update the test, its Javadoc, and the corresponding `.claude/CLAUDE.md` entry together
- No blockers for other work. `SecurityConfiguration.java` and `AuthenticationController.java` were deliberately left untouched by this plan and remain exactly as they were

---
*Quick task: 260802-shl*
*Completed: 2026-08-02*

## Self-Check: PASSED

- FOUND: build.gradle
- FOUND: src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java
- FOUND: .planning/todos/pending/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md
- FOUND: .claude/CLAUDE.md
- FOUND: src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
- FOUND: src/main/resources/application.properties
- FOUND: 1b8d378
- FOUND: 2000984
- FOUND: 500fde5
