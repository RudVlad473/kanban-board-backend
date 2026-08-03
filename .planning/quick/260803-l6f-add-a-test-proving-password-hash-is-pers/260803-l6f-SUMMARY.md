---
phase: quick-260803-l6f
plan: 260803-l6f
subsystem: testing
tags: [spring-security, bcrypt, rest-assured, jdbc-template, h2]

# Dependency graph
requires: []
provides:
  - "UserPersistenceE2ETest proving HTTP signup persists a real bcrypt hash to USERS.PASSWORD_HASH"
  - "UserPersistenceE2ETest proving signup-then-signin round-trips through the persisted row"
  - "Security-marked deferred todo for the two latent null-hash paths found while planning"
affects: [security, auth]

actuals:
  tokens: 3511
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns:
    - "Reading persisted state via raw JdbcTemplate SQL against upper-cased H2 identifiers, bypassing the ORM layer under test (same idiom as SessionPersistenceE2ETest)"

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java
    - .planning/todos/pending/2026-08-03-remove-hash-less-user-mapper-overloads-and-tighten-password-hash-nullability.md
  modified: []

key-decisions:
  - "Read the persisted PASSWORD_HASH via raw JdbcTemplate SQL against USERS rather than through UserRepository, so the ORM mapping layer cannot mask a missing hash (approach A over the rejected JPA-readback approach B)"
  - "Used absolute row counts (not before/after deltas) since AbstractAppTest's @AfterEach userService.deleteAll() actually removes user rows, unlike SPRING_SESSION rows"
  - "Generated a UUID-based collision-proof email rather than dataFactory.getEmailAddress(), since this test uniquely keys its row lookup on the email and AuthenticationController.signup converts any failure (including a unique-constraint violation) into a misleading 401"
  - "Filed the two latent null-hash paths (dead UserMapper overload, nullable passwordHash column) as a deferred, security-marked todo rather than fixing them in this test-only quick task"

patterns-established: []

requirements-completed: []

coverage:
  - id: D1
    description: "HTTP signup persists a non-null, bcrypt-marked PASSWORD_HASH different from the submitted plaintext, verified via raw SQL against USERS"
    verification:
      - kind: e2e
        ref: "src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java#SignupPasswordHashPersistence.shouldPersistNonNullBcryptHashDifferentFromPlaintext_whenSignupSucceedsOverHttp"
        status: pass
    human_judgment: false
  - id: D2
    description: "Signup followed by an independent signin (same credentials, fresh cookie jar) authenticates against the persisted row"
    verification:
      - kind: e2e
        ref: "src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java#SignupThenSignin.shouldAuthenticate_whenSigninUsesCredentialsFromAnEarlierHttpSignup"
        status: pass
    human_judgment: false
  - id: D3
    description: "Deferred-follow-up todo filed for the dead hash-less UserMapper overload and the nullable passwordHash column"
    verification:
      - kind: other
        ref: ".planning/todos/pending/2026-08-03-remove-hash-less-user-mapper-overloads-and-tighten-password-hash-nullability.md exists, marked severity: security"
        status: pass
    human_judgment: false

duration: 20min
completed: 2026-08-03
status: complete
---

# Quick Task 260803-l6f: Prove password_hash persistence, signup-then-signin round trip Summary

**Added `UserPersistenceE2ETest` — a raw-SQL-verified proof that HTTP signup writes a real bcrypt hash into `USERS.PASSWORD_HASH` and that those credentials authenticate on a subsequent, independent signin — plus a security-marked deferred todo for two latent null-hash paths found while planning.**

## Performance

- **Duration:** 20 min
- **Started:** 2026-08-03T15:23:00+02:00
- **Completed:** 2026-08-03T15:38:59+02:00
- **Tasks:** 2 completed
- **Files modified:** 2 (both new files; no `src/main` files touched)

## Accomplishments

- `UserPersistenceE2ETest` proves, via `JdbcTemplate` against the raw `USERS` table, that an HTTP
  signup persists a non-null `PASSWORD_HASH` that carries the bcrypt `$2a$` marker and is neither
  equal to nor a substring of the submitted plaintext — a check that sits below the ORM layer that
  would be at fault if a hash went missing.
- `UserPersistenceE2ETest` proves the signup-then-signin sequence as an actual user performs it,
  for the first time in the suite: a brand-new user created through `POST /signup` successfully
  signs in through an independent `POST /signin` call with a fresh cookie jar.
- The class Javadoc records the existing indirect coverage (`AuthenticationControllerTest`'s
  signup test already implies a working hash via auto-login) and states the four specific reasons
  this class still earns its place, so a future reader does not delete it as redundant.
- Filed a security-marked deferred todo naming both latent null-hash paths found while planning
  (a dead, hash-less `UserMapper.fromSignupRequestDTO(SigninRequestDTO)` overload, and
  `UserEntity.passwordHash`'s nullable column with no application-level backstop), pointing at
  `UserPersistenceE2ETest` as the regression guard for the first.

## Task Commits

1. **Task 1: Prove the signup row carries a real bcrypt hash, and that signup then signin round-trips** - `fb186df` (test)
2. **Task 2: File the two latent null-hash paths found while planning, then verify the full suite** - `19dfb14` (docs)

**Plan metadata:** `7fb81a0` (pre-dispatch plan commit, already in place before execution started)

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java` - New E2E test class, two `@Nested` behaviour groups (`SignupPasswordHashPersistence`, `SignupThenSignin`)
- `.planning/todos/pending/2026-08-03-remove-hash-less-user-mapper-overloads-and-tighten-password-hash-nullability.md` - New deferred-follow-up todo, `severity: security`

## Decisions Made

- Read the persisted hash via raw `JdbcTemplate` SQL against `USERS`/`PASSWORD_HASH` rather than
  through `UserRepository`, matching the plan's chosen approach (A) and rejecting the JPA-readback
  approach (B) that would trust the exact layer under test.
- Used absolute row counts, not before/after deltas — `AbstractAppTest`'s
  `@AfterEach userService.deleteAll()` actually removes user rows (unlike `SPRING_SESSION`, which
  has no FK to users), so a unique-per-run email makes "exactly one row" deterministic without the
  delta pattern `SessionPersistenceE2ETest` needs.
- Generated the signup email as a fixed prefix plus `UUID.randomUUID()` rather than
  `dataFactory.getEmailAddress()`, since this test keys its row lookup on the email and a
  collision would silently fail the test as a bogus 401 credentials error (per
  `AuthenticationController.signup`'s catch-all).
- Filed, rather than fixed, both latent null-hash paths in Task 2 — both require `src/main`
  changes, which are out of scope for a test-only quick task.

## Deviations from Plan

None - plan executed exactly as written. One environmental condition was resolved, not deviated
from: `./gradlew test` initially failed 3 unrelated Kafka Testcontainers-based tests
(`ActivityLogConsumerE2ETest`, `ActivityLogDeadLetterE2ETest`, `ActivityLogIdempotencyE2ETest`)
because Docker Desktop was not running in this session (`Could not find a valid Docker
environment`). This is out of this plan's scope per the deviation rules' scope boundary — no file
this plan touches has anything to do with Kafka. Docker Desktop was started and the daemon waited
on until ready, then the full `./gradlew spotlessCheck test` suite was re-run and passed clean
(163 tests, 0 failures), confirming the earlier failures were purely environmental and not caused
by this plan's changes.

## Issues Encountered

None beyond the Docker-environment condition described above, which resolved itself once the
daemon was up.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `UserPersistenceE2ETest` is in place and green; `.planning/todos/pending/` carries the deferred
  todo for the two latent null-hash paths, ready to be picked up as its own scoped task when
  someone chooses to act on either finding.
- No blockers for subsequent work. `src/main` is untouched by this plan.

---
*Phase: quick-260803-l6f*
*Completed: 2026-08-03*

## Self-Check: PASSED

- FOUND: `src/test/java/com/vrudenko/kanban_board/security/UserPersistenceE2ETest.java`
- FOUND: `.planning/todos/pending/2026-08-03-remove-hash-less-user-mapper-overloads-and-tighten-password-hash-nullability.md`
- FOUND: commit `fb186df` (Task 1)
- FOUND: commit `19dfb14` (Task 2)
