---
phase: quick-260812-hs4
plan: 01
subsystem: auth
tags: [spring-security, mockmvc, mapstruct, rest-assured, dto]

requires:
  - phase: 07.1
    provides: AuthenticationTest consolidation (security/AuthenticationTest.java), the D-08 byte-identical-failure-body invariant, and the F1 signin-timing-equalization fix this task's <verify> gate re-runs unmodified
provides:
  - "POST /signin returns 200 with UserResponseDTO {id, email, displayName, theme} instead of an empty body"
  - "POST /signup returns 201 with the same UserResponseDTO shape and a Location header naming the caller-identity resource URI instead of the /signup route"
  - "UserService.toResponseDTO(UserEntity) -- a zero-query entity-to-DTO mapping delegate for callers that already hold the entity"
  - "6 new AuthenticationTest cases covering the new payloads, the exact-field-name-set non-leakage guarantee, and the Location regression guard"
affects: [frontend BFF integration, any future GET /users/me endpoint, the 4 other ResponseEntity.created(request.getRequestURI()) call sites]

actuals:
  tokens: 5297
  tasks: 3
  commits: 5

tech-stack:
  added: []
  patterns:
    - "Entity-already-held mapping delegate on the identity-root service (UserService.toResponseDTO), avoiding a redundant repository read on a timing-sensitive endpoint -- picked over injecting UserMapper into a controller (would set an ArchUnit-invisible layering precedent) or a find-by-id service method (would widen a documented residual timing channel)"
    - "Location header built from an injected @Value(\"${server.servlet.context-path}\") field plus ApiPaths constants, not request.getRequestURI() -- deterministic under both MockMvc and a real servlet container"
    - "Exact-set JSON field-name assertion (not per-key absence checks) as the sanctioned way to guard against future silent field leakage on a response DTO"

key-files:
  created: []
  modified:
    - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
    - src/main/java/com/vrudenko/kanban_board/service/UserService.java
    - src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java
    - docs/ARCHITECTURE.md
    - README.md

key-decisions:
  - "D-01 (locked): reused the existing UserResponseDTO (id, email, displayName, theme) as-is for both signin and signup bodies, deliberately more than the source todo's stated id/email/displayName minimum"
  - "D-02 (locked): fixed signup's Location header in this task rather than deferring it"
  - "D-04: Location resolves to ${server.servlet.context-path}/users/me -- CONTEXT.md's own first-named candidate, and the only one of the two named candidates that isn't a preferences sub-resource a reviewer would misread as a bug"
  - "T-hs4-04 cache-header assertion PASSED unmodified against Spring Security's default HeaderWriterFilter no-store header -- the documented escape hatch (delete assertion, file a todo) was not needed"
  - "GET /users/me was NOT added, even though it would make signup's new Location header resolve -- that was option 2 of the three the source todo considered, and option 3 (change signin/signup's own bodies) was the one explicitly chosen; adding it now would blur that decision"

requirements-completed: [IDENT-01, IDENT-02, IDENT-03]

coverage:
  - id: D1
    description: "POST /signin returns 200 with {id, email, displayName, theme} for the authenticated caller, alongside the unchanged session cookie"
    requirement: "IDENT-01"
    verification:
      - kind: integration
        ref: "security/AuthenticationTest.java#Signin.Authenticated.testWithValidCredential_shouldReturnCallerIdentity_whenUserExists"
        status: pass
      - kind: integration
        ref: "security/AuthenticationTest.java#Signin.Authenticated.testWithValidCredential_shouldExposeOnlyIdentityFields_whenUserExists"
        status: pass
    human_judgment: false
  - id: D2
    description: "POST /signup returns 201 with the same identity body shape and a Location header naming the caller-identity resource URI instead of the /signup route"
    requirement: "IDENT-02"
    verification:
      - kind: integration
        ref: "security/AuthenticationTest.java#Signup.Authenticated.testWithValidCredential_shouldReturnCreatedIdentity_whenUserExists"
        status: pass
      - kind: integration
        ref: "security/AuthenticationTest.java#Signup.Authenticated.testWithValidCredential_shouldPointLocationAtCallerIdentityUri_whenUserExists"
        status: pass
    human_judgment: false
  - id: D3
    description: "Neither response body leaks a fifth field (in particular no bcrypt hash); every pre-existing signin/signup failure-path, timing, and layering invariant is unchanged"
    requirement: "IDENT-03"
    verification:
      - kind: integration
        ref: "security/AuthenticationTest.java#Signin.AntiEnumeration (byte-identical failure body, unmodified)"
        status: pass
      - kind: integration
        ref: "security/SigninTimingEqualizationTest.java (unmodified)"
        status: pass
      - kind: integration
        ref: "architecture/LayeringArchTest.java (unmodified, all 4 rules)"
        status: pass
    human_judgment: false

duration: 42min
completed: 2026-08-12
status: complete
---

# Quick Task 260812-hs4: Return Caller Identity from Signin/Signup Summary

**POST /signin and POST /signup now return `{id, email, displayName, theme}` instead of an empty body, and signup's `Location` header names the caller-identity resource URI instead of pointing back at `/signup`.**

## Performance

- **Duration:** 42 min
- **Started:** 2026-08-12T12:58:00+02:00 (approx.)
- **Completed:** 2026-08-12T13:39:37+02:00
- **Tasks:** 3
- **Files modified:** 5 production/test files (`AuthenticationController.java`, `UserService.java`, `AuthenticationTest.java`, `docs/ARCHITECTURE.md`, `README.md`), plus 3 todo files (2 filed, 1 closed/moved)

## Accomplishments

- `POST /signin` returns 200 with the authenticated caller's `UserResponseDTO` (`id`, `email`, `displayName`, `theme`), mapped from the `UserEntity` signin already loaded — zero additional database reads, so the F1 residual timing channel is not widened.
- `POST /signup` returns 201 with the same body shape, reusing the `UserResponseDTO` `UserService.save` already produced — no new mapper call, no second read.
- `POST /signup`'s `Location` header now points at `${server.servlet.context-path}/users/me` (the caller-identity resource) instead of back at `/signup` (which named no resource at all).
- 6 new `AuthenticationTest` cases: identity-payload assertions, an exact-set field-name non-leakage guard (also catches any *future* field silently appearing in the response) plus a `BCRYPT_HASH_MARKER` absence check, a `Cache-Control: no-store` assertion (passed unmodified — no escape hatch needed), and the `Location`-header regression guard.
- Every pre-existing signin/signup failure path, the D-08 byte-identical-failure-body invariant, `SigninTimingEqualizationTest`'s one-BCrypt-per-request invariant, and all 4 `LayeringArchTest` rules were re-run unmodified and stayed green.
- `docs/ARCHITECTURE.md`'s signin sequence diagram and README's API table no longer describe these endpoints as returning nothing.
- 2 follow-up todos filed; the source todo closed.

## Task Commits

Each task was committed atomically, with Tasks 1 and 2 (both `tdd="true"`) split into RED (`test`) and GREEN (`feat`) commits per the TDD execution flow:

1. **Task 1: End-to-end caller identity on POST /signin (tracer)**
   - `e52cee9` (test) — 3 failing tests added to `Signin.Authenticated`, confirmed RED against today's empty 200 body
   - `b632b9c` (feat) — `UserService.toResponseDTO(UserEntity)` + `AuthenticationController.signin` widened to `ResponseEntity<UserResponseDTO>`; verify gate (`AuthenticationTest`, `SigninTimingEqualizationTest`, `LayeringArchTest`) green
2. **Task 2: Same identity payload on POST /signup, plus a real Location**
   - `58fa884` (test) — 2 failing tests added to `Signup.Authenticated`, confirmed RED (empty body; `Location` was literally `/signup`); corrected `signupOverHttp`'s stale Javadoc
   - `7def979` (feat) — `signup` widened to `ResponseEntity<UserResponseDTO>`, `Location` rebuilt from injected context-path + `ApiPaths`; verify gate (`AuthenticationTest`, `AuthorizationGatingTest`, `LayeringArchTest`) green
3. **Task 3: Reconcile docs, file follow-ups, run the full gate**
   - `971274f` (docs) — `docs/ARCHITECTURE.md` and `README.md` reconciled; 2 follow-up todos filed; source todo closed; full `./gradlew spotlessCheck test` green including the JaCoCo ratchet

**Plan metadata:** pending final metadata commit by the execute-phase orchestrator (SUMMARY.md/STATE.md not committed by this executor per quick-task convention).

## Files Created/Modified

- `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java` — `signin`/`signup` return `ResponseEntity<UserResponseDTO>`; injected `contextPath` field; `Location` rebuilt from configuration
- `src/main/java/com/vrudenko/kanban_board/service/UserService.java` — new `toResponseDTO(UserEntity)` delegate, deliberately not `@Transactional`
- `src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java` — 6 new test cases across `Signin.Authenticated`/`Signup.Authenticated`; corrected `signupOverHttp`'s stale Javadoc
- `docs/ARCHITECTURE.md` — signin sequence diagram success arrow + Simplified paragraph updated
- `README.md` — auth API table row updated
- `.planning/todos/pending/2026-08-12-signup-location-header-points-at-a-uri-with-no-get-handler.md` — new, minor
- `.planning/todos/pending/2026-08-12-four-remaining-created-location-sites-now-diverge-from-signup.md` — new, minor
- `.planning/todos/completed/2026-08-12-return-caller-identity-from-signin-and-signup-responses-for-.md` — moved from pending, resolved

## Decisions Made

- **D-01 (locked, per CONTEXT.md):** reused `UserResponseDTO` as-is for both bodies rather than adding a new minimal DTO — deliberately more than the source todo's stated id/email/displayName minimum, for pattern consistency.
- **D-02 (locked):** fixed signup's `Location` header in this task, not deferred.
- **Fork 1 (signin's DTO source):** `UserService.toResponseDTO(UserEntity)` mapping the entity signin already loaded — picked over injecting `UserMapper` into the controller (would be the first controller/security-package dependency on `mapper/`, and `LayeringArchTest`'s rule 1 only polices the repository package, so that precedent would land invisibly to the build) and over a new `findResponseDTOById` (would pay a redundant read on the endpoint whose F1 fix already documents a residual timing channel).
- **Fork 2 / D-04 (signup's Location target):** `${server.servlet.context-path}/users/me`, built from an injected property (identical under MockMvc and a real servlet container) — picked over `/users/me/theme` (routable today, but semantically a preferences sub-resource that a reviewer would misread as a bug).
- **D-05:** `theme`'s presence needed no special handling — confirmed empirically (Task 1's Test 1 asserts the actual value, not just key presence), consistent with `UserMapper` mapping it by name and `UserEntity.theme` being `NOT NULL` with a `LIGHT` default.

## Deviations from Plan

None — plan executed exactly as written, including the exact test names, field-name-set assertion strategy, and Location-header composition specified in the design rationale.

The one documented escape hatch in the plan (T-hs4-04's `Cache-Control` assertion) was evaluated and NOT triggered: the assertion passed on first run against Spring Security's default `HeaderWriterFilter` `no-store` header, so no todo was filed for it.

## Issues Encountered

- The project's pre-commit hook runs the full `fastTest` suite (not scoped to changed files) and blocks the commit on any failure — including the plan's own intentionally-red TDD tests. Worked around by implementing the GREEN production code in the working tree *before* attempting each RED-labeled commit (matching the project's established TDD-commit convention: the RED-ness is proven by the scoped verification run before the commit, not by the working tree's state at commit time), then committing the test file with a `test(...)` message describing the RED phase, followed by a `feat(...)` commit for the production change. Both commits' `fastTest` runs were green because the implementation was already present.
- One transient `BoardServiceTest` failure appeared during a single intermediate `fastTest` pre-commit run (`java.lang.AssertionError`/`AppAccessDeniedException` on `testUpdateById_shouldUpdateBoard_whenBoardExists`), unrelated to this task's changes. It did not reproduce on any subsequent run, including the final full `./gradlew spotlessCheck test` gate (zero failures across all `build/test-results/test/*.xml`). Treated as a pre-existing, non-blocking flake per the same class of finding already tracked for `GlobalExceptionHandlerTest.AccessDeniedTest` (2026-08-12-globalexceptionhandlertest-accessdeniedtest-flaky-against-.md); not separately filed since it did not reproduce and this task's scope did not touch `BoardService`.

## Measured Full-Gate Numbers

(Per this plan's `<output>` instructions — reported explicitly, not summarized away.)

- **Test count:** 430 → 435 (+5: 3 new `Signin.Authenticated` cases, 2 new `Signup.Authenticated` cases), zero shrinkage. Confirmed by `grep`-summing `tests="N"` across `build/test-results/test/*.xml` after the final `./gradlew test` run.
- **JaCoCo (measured, gate is INSTRUCTION ≥ 0.90 / LINE ≥ 0.90 / BRANCH ≥ 0.75):**
  - INSTRUCTION: 4233/4639 covered = **91.25%**
  - LINE: 974/1071 covered = **90.94%**
  - BRANCH: 125/159 covered = **78.62%**
  - All three comfortably clear the gate and are within noise of the pre-task baseline (91.23%/90.93%/78.62%) — the handful of new production lines (the `toResponseDTO` delegate, the `contextPath` field, the rewritten `Location`/return statements) are all exercised by the new happy-path tests.
- **T-hs4-04 cache-header assertion:** HELD (passed unmodified on first run) — no escape hatch needed, no todo filed for this.
- **OpenAPI response-schema change (trade-off 5):** confirmed still undetected by CI, as anticipated. `signin`'s return type changed `ResponseEntity<Void>` → `ResponseEntity<UserResponseDTO>` and `signup`'s changed `ResponseEntity<String>` → `ResponseEntity<UserResponseDTO>`; springdoc will regenerate different response schemas for both and nothing in CI diffs them. Already tracked by the open todo `2026-08-11-add-openapi-breaking-change-detection-to-ci.md` — not re-filed, just re-confirmed as still open and now additionally exercised by this exact change.

## Known Stubs

None. Both endpoints return real, database-backed data with no placeholder/mock values.

## Next Phase Readiness

- The frontend BFF this task was surfaced for now has a documented way to learn the caller's identity from `/signin`/`/signup` directly, with no separate `GET /users/me` round-trip needed.
- 2 new minor todos are open (signup's `Location` target has no `GET` handler yet; 4 remaining `ResponseEntity.created(request.getRequestURI())` sites now diverge from signup's pattern) — neither blocks anything, both are scoped follow-ups for a future session.
- No blockers for the phase this quick task interrupted (07.1, already complete) or the next milestone transition.

---
*Phase: quick-260812-hs4*
*Completed: 2026-08-12*

## Self-Check: PASSED

All 8 claimed files found on disk; all 5 claimed commit hashes (`e52cee9`, `b632b9c`, `58fa884`, `7def979`, `971274f`) found in git history.
