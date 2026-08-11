---
created: 2026-08-10T20:00:00.000Z
resolved: 2026-08-11
title: TOCTOU race in concurrent-session-ceiling enforcement lets a single account briefly exceed maximumSessions
area: backend
severity: security
files:
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java:112
  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
---

## Problem

`/claude-security` scan (2026-08-10, medium effort, whole-repo `attack-surface` scope, run
against phase 07.1's finished HEAD) finding **F6** (low severity, confidence medium, 2/3
adversarial-panel votes):

`AuthenticationController.authenticate` (`:112`) invokes `sessionAuthenticationStrategy
.onAuthentication(...)`, which enforces `MAX_CONCURRENT_SESSIONS = 2`
(`SecurityConfiguration.java`) by reading the caller's live session count from
`SpringSessionBackedSessionRegistry` (backed by the shared `SPRING_SESSION` table) and rejecting
the new signin if the count is already at the ceiling. Two concurrent signin requests for the
same principal can both read the same under-threshold count before either has persisted its new
session row, and both proceed — a classic time-of-check-to-time-of-use race, allowing a brief
window where more than 2 sessions exist for one account.

## Why this is deferred, not fixed now

- Severity is low, and confidence is medium (2/3, not unanimous) — this project's
  `security_block_on: high` setting does not mandate an in-phase fix, and the race is not
  introduced or worsened by this phase (the ceiling-enforcement mechanism itself shipped in quick
  task `260803-m2z`, well before 07.1).
- A correct fix needs either a database-level serialization point (e.g. an advisory lock or a
  `SELECT ... FOR UPDATE`-style guard scoped per principal around the count-then-register
  sequence) or accepting a small, bounded, self-healing overshoot — both are real design decisions
  with their own trade-offs (added latency on every signin vs. a temporarily-soft ceiling), not a
  small fix appropriate to bundle into this phase's frontend-readiness scope.
- Practical impact is bounded and low-stakes: the race window is the gap between two concurrent
  HTTP requests completing their session-registry read and their session-row write — realistically
  microseconds to low milliseconds — and the worst case is a brief, self-correcting overshoot
  (e.g. 3 live sessions momentarily instead of 2), not an unbounded or persistent bypass.

## Solution

When picked up: evaluate a per-principal serialization point around the
count-then-register sequence in `ConcurrentSessionControlAuthenticationStrategy`'s call path —
options include a short-lived advisory lock keyed on the principal (e.g. Postgres
`pg_advisory_xact_lock` scoped to the authenticating transaction) or accepting the race as a
documented, bounded design trade-off with a comment explaining why, mirroring the treatment
`T-07.1-05-04`/D-14 gave `UserEntity`'s deliberately-unversioned last-write-wins theme updates.
Whichever is chosen, add a regression test that drives two genuinely concurrent signin requests
(matching the existing `BoardCreationE2ETest.ConcurrentCreate` real-concurrency test pattern) and
asserts the resulting live session count for that principal, proving the fix (or documenting the
accepted bound) empirically rather than by inspection.

## Resolution

Disposition: **accept a bounded, self-healing overshoot** (D-01, quick task `260811-h2v`) — not
the advisory-lock option this todo's Solution section named first. The choice was settled by
measurement, not by reasoning about Spring Session's source:

- **The measurement that ruled out the advisory lock (Task 2):** a temporary probe injected into
  `AuthenticationController.authenticate` read the live `SPRING_SESSION` count for the
  just-authenticated principal from a *second* database connection, taken the instant the method's
  `mapTry` lambda finished (right after `securityContextRepository.saveContext(...)` returned — the
  latest point any controller-scoped transaction could still be open). That probe read **0**
  committed rows. A client-side probe taken after the HTTP response was fully received read **1**.
  The new session row is not committed, and therefore not visible to another connection, until
  Spring Session's request-scoped filter commits it as the response is flushed — strictly *after*
  `authenticate` (and any transaction scoped around it) has already returned. A
  `pg_advisory_xact_lock` held across `authenticate` would therefore release before the row it needs
  to serialize against exists, closing nothing while adding a blocking database round trip to every
  signin. Both database-side alternatives considered and rejected for the same underlying reason: an
  in-JVM striped lock (`ConcurrentHashMap<String, ReentrantLock>` / Guava `Striped`) guards the
  wrong interval identically, plus is per-instance and would silently degrade the moment a second
  application instance starts — exactly the failure mode `SpringSessionBackedSessionRegistry` was
  chosen to avoid. A `spring_session` INSERT trigger with its own advisory lock *would* genuinely
  serialize (the lock is held by the transaction doing the INSERT), but was rejected as
  disproportionate: it fires after the 200 and `Set-Cookie` are already on the wire, turning a
  transient low-severity overshoot into a worse user-visible failure mode (a success response for a
  session that then fails to persist, or a 500 inside a servlet filter), and puts application policy
  inside a schema this application does not own (Spring Session creates `spring_session` itself via
  `initialize-schema=always`).
- **Measured overshoot frequency (Task 1's temporary `@RepeatedTest(10)` characterization run,
  2026-08-11):** **10 of 10** repetitions produced the TOCTOU overshoot on this machine — the window
  opened on every attempt under `ConcurrentSigninCeilingE2ETest`'s real-socket,
  `Executors.newFixedThreadPool(2)` submission pattern, not narrowly. This is a real, reported
  number, not an assumption; it does not change the disposition (the overshoot is still exactly one
  extra session and still self-heals), but it means the accepted trade-off is better read as "the
  ceiling reliably allows one extra concurrent signin to succeed" rather than as a rare edge case.
- **What shipped:** `ConcurrentSigninCeilingE2ETest` (real-socket, `@Tag("realSocket")`, concurrent
  sibling of `AuthenticationTest.ConcurrentSessionCeiling`'s sequential spec) drives two genuinely
  concurrent, cookie-less signins at exactly one session of headroom below the ceiling and asserts
  the invariant `liveSessionCount() == 1 + successCount` (never lost or phantom rows),
  `successCount` in `[1, 2]` (the bound), and that a signin issued after the burst settles is still
  refused and creates no new row (the self-heal assertion — teeth-checked by temporarily
  neutralizing `sessionAuthenticationStrategy.onAuthentication`, confirmed RED on that exact
  assertion, restored with zero net `src/main` diff). `SecurityConfiguration.sessionAuthenticationStrategy`'s
  Javadoc now documents the accepted bound, the measurement that ruled out the advisory lock, and
  the D-08 anti-enumeration reminder, immediately beside the enforcing bean. `docs/ARCHITECTURE.md`'s
  signin sequence diagram carries a matching note and corrects the prior claim that
  `SecurityContextRepository` synchronously writes the session row — it now shows Spring Session's
  request-scoped filter committing the row after the controller returns, which is the ordering that
  makes the bound unavoidable.
- **The honest shape of the bound:** a function of how many signins for one principal are genuinely
  simultaneous, not a flat constant — `MAX_CONCURRENT_SESSIONS` plus at most one extra per signin in
  flight at the same instant, never documented as "at most 3". The overshoot grants no capability
  the already-authenticated principal lacked: every extra session belongs to a caller who already
  supplied valid credentials, so this is a session-hygiene control experiencing a bounded transient
  breach, not an authentication control being bypassed. The rejection stays a 401, indistinguishable
  from a wrong password (D-08) — the new test and the Javadoc both reinforce this explicitly, since
  documenting a session-limit behaviour is exactly the kind of change that tempts a future author to
  give it its own status code.
- **Coverage caveat:** `ConcurrentSigninCeilingE2ETest` carries `@Tag("realSocket")`, which
  deliberately keeps it out of the pre-commit `fastTest` gate (a two-thread HTTP race in the commit
  hook would be a flake generator) — the regression guards `./gradlew test` and CI only, not every
  commit.
- **No production behaviour changed:** `MAX_CONCURRENT_SESSIONS` stays `2`; the only surviving
  `src/main` edit across this task is the `SecurityConfiguration` Javadoc addition. The probe
  instrumentation used to take the Task 2 measurement was added and reverted within that task, with
  `git diff --quiet HEAD -- AuthenticationController.java` confirming zero net diff before the task
  ended.
