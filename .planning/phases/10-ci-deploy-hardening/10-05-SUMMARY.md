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
  tasks: 2
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

requirements-completed: []  # HARDEN-07 NOT completed -- Task 3 (live nonprod verification) is an unresolved checkpoint, see below

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
    verification: []
    human_judgment: true
    rationale: "Requires pushing the merged commit to master, waiting for the real nonprod CI deploy, and a curl/cookie-jar round trip against a live nonprod host using real account credentials -- none of which exists or is reachable from this isolated, unmerged worktree branch. This is Task 3 of the plan (checkpoint:human-verify, gate=blocking) and remains open; see Deviations/Issues below."

# Metrics
duration: 32min
completed: 2026-08-19
status: halted
---

# Phase 10 Plan 05: Session Cookie Secure Attribute Summary

**`Secure` set unconditionally on the session cookie in both Spring profiles, proven by a new real-socket RestAssured test against the actual `Set-Cookie` header; live TLS-served-nonprod round-trip verification (Task 3) remains an open checkpoint.**

## Performance

- **Duration:** ~32 min (Tasks 1-2; full suite run included)
- **Started:** 2026-08-19T17:55:00+02:00 (approx)
- **Completed:** 2026-08-19T18:27:00+02:00 (approx, Tasks 1-2 only)
- **Tasks:** 2 of 3 completed (Task 3 halted at checkpoint)
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

**Task 3 (checkpoint:human-verify, gate=blocking): Prove an authenticated round trip against TLS-served nonprod with a compliant client** - NOT executed; see Deviations/Issues below. No commit for this task.

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

**Task 3 was not executed and remains an open checkpoint.** This is not a deviation in the Rule 1-4 sense (no bug was found, no auto-fix was applied) -- it is a structural impossibility for a parallel worktree executor to complete this specific task, documented here rather than silently skipped:

- Task 3's `how-to-verify` requires pushing the merged commit to `master` and waiting for the real nonprod CI deploy (`deploy.yml`) to complete, then curling the live nonprod host with a real account's email/password to prove a genuine RFC-6265-compliant client (not RestAssured's manual cookie replay) completes an authenticated round trip over real TLS.
- This executor runs on an isolated, unmerged worktree branch (`worktree-agent-a930295ef9e82c86a`). Its commits are not on `master` and will not trigger the nonprod deploy workflow until the orchestrator merges this wave's work -- so "push the commit and wait for the deploy" cannot be meaningfully performed from here yet.
- The verification also requires a real nonprod account's live credentials (a value only the human/operator has, consistent with this project's checkpoint protocol: "Claude does all automation; users only... provide secrets").
- Per the plan's own `<resume-signal>`: *"Type 'approved' once step 2 shows `Secure` on the wire and step 3 returns 200."* This is exactly the operator-verification checkpoint the plan's `type="checkpoint:human-verify" gate="blocking"` attribute exists for, and it is surfaced below as a live CHECKPOINT REACHED rather than fabricated or skipped.

---

**Total deviations:** 0 auto-fixed. One structural task deferral (Task 3), documented as an open checkpoint, not a deviation.
**Impact on plan:** Tasks 1-2 (the code, test, and doc changes HARDEN-07 actually specifies) are complete, committed, and verified live against the full test suite. HARDEN-07's requirement is only fully closed once Task 3's live nonprod round trip is independently confirmed by the operator after this wave merges to `master`.

## Issues Encountered

- The project's pre-commit hook (`.githooks/pre-commit`, which runs `fastTest` via Gradle) intermittently failed with a Windows file-lock error (`Unable to delete directory ...\build\test-results\fastTest\binary`) on the first commit attempt for Task 1, consistent with this project's known "GSD commit wrapper flaky on cold Docker" pattern. Resolved by running `./gradlew --stop` to release stale daemon file handles, then running the pre-commit hook directly once to confirm it passes cleanly, then re-issuing the plain `git commit` -- which then succeeded on the first attempt. No code change was needed; this was tooling flakiness, not a defect in the plan's deliverables.

## User Setup Required

None - no external service configuration required. Task 3 requires operator action (see Deviations above and the CHECKPOINT below), not environment setup.

## Next Phase Readiness

- Tasks 1 and 2 are complete, committed, and verified: `Secure` is set in both profiles, the new real-socket test proves it from the wire and was demonstrably RED before the change, and the full suite is green.
- **Blocker for this plan's full completion:** Task 3's live nonprod round-trip verification is outstanding and requires the operator (not this executor) to run the checkpoint's steps after this wave's commits reach `master` and the nonprod deploy completes.
- This plan's changes are self-contained (one boolean property in two files, one new test class, one doc bullet) and introduce no risk to subsequent phase-10 plans landing in the same wave.

---
*Phase: 10-ci-deploy-hardening*
*Completed: 2026-08-19 (Tasks 1-2; Task 3 open)*
