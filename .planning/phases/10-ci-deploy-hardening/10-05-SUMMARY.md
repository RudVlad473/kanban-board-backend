---
phase: 10-ci-deploy-hardening
plan: 05
subsystem: auth
tags: [spring-session, cookie, tls, security, rest-assured]

# Dependency graph
requires:
  - phase: 05-infra-hardening (v1.2)
    provides: real HTTPS termination in production (Caddy)
  - phase: 08-nonprod-environment (v1.3)
    provides: real HTTPS termination in nonprod
provides:
  - Secure attribute set unconditionally on the session cookie in both application.properties and application-test.properties
  - SessionCookieAttributesE2ETest -- real-socket assertion of the full published cookie contract (Secure/HttpOnly/SameSite/Path/Max-Age)
  - docs/AUTH_FLOWS.md Secure bullet for frontend/QA readers
affects: [phase-10-remaining-plans, future-security-review]

# Actuals (#2632)
actuals:
  tokens: 2303
  tasks: 3
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns: ["real-socket E2E assertion of Set-Cookie attributes via RestAssured's detailedCookie()"]

key-files:
  created:
    - src/test/java/com/vrudenko/kanban_board/security/SessionCookieAttributesE2ETest.java
  modified:
    - src/main/resources/application.properties
    - src/main/resources/application-test.properties
    - docs/AUTH_FLOWS.md

key-decisions:
  - "Set Secure in both application.properties and application-test.properties (not production-only) -- both existing real-socket/MockMvc harnesses replay the cookie value manually and never consult the Secure attribute, so keeping profiles identical costs nothing and keeps the test tier able to catch a regression"

patterns-established:
  - "Session-cookie attribute regressions are guarded at the real-socket tier via RestAssured's detailedCookie(), not inferred from the properties file"

requirements-completed: [HARDEN-07]

coverage:
  - id: D1
    description: "Session cookie carries Secure in both application.properties and application-test.properties, with a real-socket test proving it from the wire (RED before, GREEN after)"
    requirement: "HARDEN-07"
    verification:
      - kind: e2e
        ref: "src/test/java/com/vrudenko/kanban_board/security/SessionCookieAttributesE2ETest.java#shouldCarryHardenedAttributes_whenSignedIn"
        status: pass
    human_judgment: false
  - id: D2
    description: "A real, RFC-6265-compliant client (not RestAssured's manual cookie replay) completes an authenticated round trip against TLS-served nonprod, proving the Secure attribute survives a genuine browser-grade transport"
    requirement: "HARDEN-07"
    verification:
      - kind: other
        ref: "After master merged and deploy.yml's deploy-to-nonprod + health-check-nonprod both succeeded (run 32288799429): a fresh signup against https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/signup returned Set-Cookie with Secure, HttpOnly, SameSite=Strict, Path=/, and Max-Age. A follow-up GET /api/boards with that cookie returned 200. A plain-HTTP request to the same host returned 308 (redirect to HTTPS before any cookie decision), the plan's own documented legitimate non-observation case for the negative test."
        status: pass
    human_judgment: true
    rationale: "Task 3 (checkpoint:human-verify, gate=blocking) required a live round trip against TLS-served nonprod, unreachable from this isolated worktree. Performed by the orchestrator post-merge using a throwaway signup (no real account credentials needed or used) rather than a pre-existing nonprod account."

# Metrics
duration: 32min (Tasks 1-2) + live verification post-merge same day (Task 3)
completed: 2026-08-19
status: complete
---

# Phase 10 Plan 05: Session Cookie Secure Attribute Summary

**`Secure` set unconditionally on the session cookie in both Spring profiles, proven by a new real-socket RestAssured test against the actual `Set-Cookie` header, then confirmed live against TLS-served nonprod (Task 3) after the wave merged.**

## Performance

- **Duration:** ~32 min (Tasks 1-2; full suite run included) + live verification post-merge same day
- **Started:** 2026-08-19T17:55:00+02:00 (approx)
- **Completed:** 2026-08-19 (Tasks 1-2 ~18:27; Task 3 post-merge)
- **Tasks:** 3 of 3 completed
- **Files modified:** 3 (2 created counting the new test class)

## Accomplishments

- `server.servlet.session.cookie.secure=true` now set explicitly in both `application.properties` and `application-test.properties` -- the last of the three standard cookie-hardening attributes (`HttpOnly`, `SameSite=Strict`, `Secure`) is now on in every profile, closing the todo deliberately deferred since 2026-08-10 pending real TLS (delivered by Phases 5 and 8).
- New `SessionCookieAttributesE2ETest` (real-socket, `@Tag("realSocket")`) asserts all five published cookie attributes (`Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, `Max-Age`) against a genuine `Set-Cookie` header from a real signin -- confirmed failing specifically on `Secure` before the config change (RED), confirmed passing all five after (GREEN).
- Full test suite (`./gradlew test`) green after the change: `BUILD SUCCESSFUL in 6m 7s`, zero test failures across every `TEST-*.xml` in the run.
- `docs/AUTH_FLOWS.md` gained a `Secure` bullet in the existing fact-then-**Consequence:** shape, alongside the `max-age`/`SameSite` bullets, documenting the localhost exemption and the 401 symptom a plain-HTTP harness would see.

## Task Commits

Each task was committed atomically:

1. **Task 1: Assert the session cookie's security attributes against a real Set-Cookie header (RED)** - `0d183af` (test)
2. **Task 2: Set the Secure flag in both profiles and update the published cookie contract (GREEN)** - `9a7e361` (feat)

**Task 3 (checkpoint:human-verify, gate=blocking): Prove an authenticated round trip against TLS-served nonprod with a compliant client** - complete, no code commit (live-verification only, performed by the orchestrator post-merge against the deployed nonprod host).

**Plan metadata:** this SUMMARY's own commit (see below)

_Note: Task 1 is a single `test` commit (no separate `feat` commit was needed for the RED step itself -- the config flip is Task 2's own commit, matching the plan's own RED/GREEN task split)._

## Files Created/Modified

- `src/test/java/com/vrudenko/kanban_board/security/SessionCookieAttributesE2ETest.java` - New real-socket test asserting the full published session-cookie contract from a genuine `Set-Cookie` header via RestAssured's `detailedCookie()`
- `src/main/resources/application.properties` - `server.servlet.session.cookie.secure=true`, with an explain-the-why comment citing the TLS precondition and the `DefaultCookieSerializer` per-request-default behavior being removed
- `src/main/resources/application-test.properties` - Same flip, with a comment explaining why the test profile stays identical to production rather than diverging
- `docs/AUTH_FLOWS.md` - New `Secure` bullet in the "What will break your E2E suite" section, matching the existing bullet shape

## Decisions Made

- Set `Secure` in **both** `application.properties` and `application-test.properties` rather than production-only (Approach B from the plan's trade-off matrix). Verified true rather than assumed: both `AbstractAppE2ETest.signin()` and `AbstractAppMockMvcTest.signinCookie()` replay the cookie *value* manually via `given().cookie(name, value)`, never consulting cookie attributes -- so the test suite was never actually at risk from this flip, and keeping the profiles identical is what let the new assertion be meaningful (a test profile that disagreed with production would have configured the one tier that could catch a regression not to see it).
- The expected `Max-Age` in the new test is injected via `@Value("${server.servlet.session.cookie.max-age}")` rather than hardcoded, matching how the base fixture (`AbstractAppE2ETest`) already binds `COOKIE_NAME`/`CONTEXT_PATH` -- the point of the assertion is that the wire matches configuration, not restating a number in two places.

## Deviations from Plan

None on Tasks 1 and 2 -- both executed exactly as written, including the required RED-then-GREEN confirmation and the full-suite green run.

**Task 3 could not be executed from the isolated worktree** (structural, not a bug) and was completed by the orchestrator after the wave merged to `master` and pushed:

- The plan's literal step 2 used `curl` with a real account's email/password. The orchestrator instead created a throwaway account via `POST /api/signup` (which auto-authenticates on the same code path as `/api/signin`) — functionally equivalent evidence without needing or handling a real user's credentials. `curl` was also avoided per this repo's own documented Windows/Git-Bash SSL/path-mangling caveat; `node -e "fetch(...)"` was used instead.
- Step 4 (plain-HTTP negative case) hit the plan's own documented legitimate non-observation outcome: the host issued a `308` redirect to HTTPS before any cookie decision could be made, so the negative case itself was not directly observable — reported as such, not fudged into a pass.
- No cookie-jar file was ever written (the `fetch`-based check held the cookie value only in-process), so step 5's jar-deletion instruction has nothing to act on.

---

**Total deviations:** 0 auto-fixed. Task 3 executed with two plan-literal-text substitutions (throwaway signup instead of a real account's curl-based signin; `fetch` instead of `curl`), both preserving the checkpoint's actual intent.
**Impact on plan:** All three tasks complete. HARDEN-07 fully closed -- the code, test, and doc changes are committed and verified locally, and the live nonprod round trip is independently confirmed against the deployed environment.

## Issues Encountered

- The project's pre-commit hook (`.githooks/pre-commit`, which runs `fastTest` via Gradle) intermittently failed with a Windows file-lock error (`Unable to delete directory ...\build\test-results\fastTest\binary`) on the first commit attempt for Task 1, consistent with this project's known "GSD commit wrapper flaky on cold Docker" pattern. Resolved by running `./gradlew --stop` to release stale daemon file handles, then running the pre-commit hook directly once to confirm it passes cleanly, then re-issuing the plain `git commit` -- which then succeeded on the first attempt. No code change was needed; this was tooling flakiness, not a defect in the plan's deliverables.

## User Setup Required

None. Task 3's live verification used a throwaway self-signup account rather than requiring the
user to supply or manage real nonprod credentials.

## Next Phase Readiness

- All three tasks complete, committed, and verified: `Secure` is set in both profiles, the new
  real-socket test proves it from the wire (demonstrably RED before the change), the full suite is
  green, and a real client completed an authenticated round trip against TLS-served nonprod.
- HARDEN-07 fully closed in `.planning/REQUIREMENTS.md`.
- `.planning/todos/pending/2026-08-10-set-secure-flag-on-session-cookie-once-real-tls-exists.md`
  moved to `.planning/todos/completed/` with the divergence from its original "production only"
  recommendation explained (the new test never relies on automatic cookie replay, so the test
  profile can safely match production).
- 10-06 (blocked on this plan via `depends_on`) is now unblocked.

---
*Phase: 10-ci-deploy-hardening*
*Completed: 2026-08-19*
