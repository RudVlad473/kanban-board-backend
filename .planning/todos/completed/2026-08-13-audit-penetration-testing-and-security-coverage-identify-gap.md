---
created: 2026-08-13T14:46:10.740Z
resolved: 2026-08-20
title: Audit penetration-testing and security coverage, identify gaps
area: security
severity: security
files:
  - src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
  - src/test/java/com/vrudenko/kanban_board/security/AuthorizationGatingTest.java
  - src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java
  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
  - .planning/todos/pending/2026-08-03-add-dependency-vulnerability-scan.md
---

## Problem

This codebase's security test coverage grew organically across several sessions
(`InjectionAttemptTest` for SQL injection/XSS/oversized-payload proof per D-16/17/18,
`AuthorizationGatingTest` for ownership-chain gating, `AuthenticationTest` for
anti-enumeration/timing-equalization/session-fixation/concurrent-session-ceiling behavior) but has
never been assessed end-to-end as a deliberate audit against a recognized checklist (e.g. OWASP Top
10, OWASP API Security Top 10, or ASVS). The existing tests prove real properties, but "tests exist
for topic X" is not the same claim as "topic X's coverage is adequate" -- today's session
(`260813-m9x`) found two independently-true-sounding claims about this same security surface that
turned out to contradict each other once actually measured, which is a concrete reason not to trust
assumed coverage without checking.

Candidate gaps worth checking, not a verified list -- this needs its own investigation:

- CSRF posture (session-cookie auth without CSRF tokens is a real category, not obviously covered by
  existing tests)
- Rate limiting / brute-force protection on `/signin` (anti-enumeration and timing-equalization are
  proven; a volumetric brute-force guard is a different property and may not be)
- IDOR coverage across all four ownership chains (Board -> Column -> Task -> Subtask) --
  `AuthorizationGatingTest` may only exercise a subset of the chain depth/combinations
- Dependency CVEs -- ties directly into the existing pending todo
  `2026-08-03-add-dependency-vulnerability-scan.md`; this audit and that todo should stay aware of
  each other rather than duplicating scope
- Security response headers (HSTS, X-Content-Type-Options, X-Frame-Options, CSP, etc.) -- likely
  Caddy's responsibility once Phase 5 lands, but worth confirming explicitly rather than assuming
- Mass-assignment via DTOs (are all `Save*RequestDTO`/`Update*RequestDTO` fields actually
  intentionally bindable, or could an unexpected JSON field reach an entity setter)

## Solution

TBD -- likely wants a structured checklist pass (OWASP API Security Top 10 is probably the better
fit than the generic Top 10, given this is a pure REST API with no server-rendered pages) mapped
against this repo's actual test suite and production `SecurityConfiguration`/`GlobalExceptionHandler`
wiring, producing a gap list (covered / assumed-covered-but-unverified / genuinely untested) rather
than a pass/fail verdict. Should conclude with either new todos per confirmed gap, or an explicit
"checked and found adequate" note per category so the next session doesn't re-ask the same question
from scratch.

## Resolution

Closed by **quick task 260820-giz** (2026-08-20). Every one of the 10 OWASP API Security Top 10
(2023) categories was traced against this codebase's actual test suite and production wiring —
not summarized from memory. **Zero production-code changes were made**; this audit's own scope was
verification and triage, not remediation. `git status --short` after this task touches only
`.planning/todos/**`.

### Verdicts, all 10 categories, each cited

**API1:2023 Broken Object Level Authorization (BOLA/IDOR) — partially covered, one confirmed gap.**
Classic cross-user IDOR is covered: `AuthorizationGatingTest.CrossUserSweep` proves 403 across all
22 routes (foreign user vs. owning user's board/column/task/subtask). **Confirmed gap (not
assumed):** `OwnershipVerifierService.verifyOwnershipOfColumn/Task/Subtask` (lines 62-101) walk up
from the *leaf* path id only; `ColumnController`, `TaskController`, `SubtaskController` (read in
full) never even bind `boardId`/`columnId`/`taskId` as `@PathVariable`s on their nested mutating
routes — only the leaf id reaches the service. A caller who owns boards A and B can address
`PUT /boards/{A}/columns/{columnId-of-B}` and succeed against B's column: same-user chain
confusion, not cross-user exposure (ownership is still enforced end to end). `AuthorizationGatingTest.CrossUserSweep` never exercises this because it only varies the *user*, never a
same-user path-segment mismatch. New todo filed (below).

**API2:2023 Broken Authentication — covered, with one implementation gap tracked under API4.**
`AuthenticationTest.AntiEnumeration` proves indistinguishable wrong-password/nonexistent-email
responses; `SigninTimingEqualizationTest` proves response-time equalization;
`AuthenticationTest.SessionFixation` proves session-id rotation on every successful auth (via
`ChangeSessionIdAuthenticationStrategy`, `SecurityConfiguration` line 219);
`AuthenticationTest.ConcurrentSessionCeiling` proves the JDBC-backed 2-session ceiling
(`sessionAuthenticationStrategy` bean, `SecurityConfiguration` lines 196-220). No volumetric
brute-force guard exists — see API4.

**API3:2023 Broken Object Property Level Authorization (mass assignment) — covered.** All 13
`Save*/Update*RequestDTO`/`Reorder*`/`Move*` classes under `dto/**` read in full: no field named
`userId`/`ownerId`/`boardId` or any re-parenting-shaped field exists in any of them — every field
is a content field (`name`, `title`, `description`, `version`, `theme`, `targetPosition`,
`targetColumnId`, `isCompleted`, `email`, `password`, `displayName`). `BoardMapper` (representative
mapper, read in full) is only used for `Save*` creation flows; update flows go through manual,
explicit field-by-field service-layer mutation (e.g. `ColumnService.updateById` calls only
`column.setName(dto.getName())`, confirmed by reading that method) — `unmappedTargetPolicy =
ReportingPolicy.IGNORE` never gets a chance to auto-map a dangerous field because no generic
DTO-to-entity update mapping exists in this codebase's actual update path.

**API4:2023 Unrestricted Resource Consumption — one confirmed implementation gap.** Read-side
consumption is bounded (`spring.data.web.pageable.max-page-size=100`,
`application.properties` line 208). **Confirmed absent, not merely untested:** a grep for
`ratelimit|rate-limit|rate_limit|throttle|bucket4j|RateLimit` across all of `src/main` returned
zero matches, and no rate-limiting dependency exists in `build.gradle`. `POST /signin` has no
volumetric guard — `AntiEnumeration`/`ConcurrentSessionCeiling`/timing-equalization all prove
different, real properties, none of which bound attempt volume. This is an implementation gap
(add rate limiting), not a test gap. New todo filed (below).

**API5:2023 Broken Function Level Authorization — covered, via a non-RBAC mechanism.** No
role/admin concept exists among ordinary session-authenticated users (confirmed: grep for
`hasRole|ROLE_|@Secured|isAdmin` across `src/main` returned zero matches) — every user is a peer
scoped only by resource ownership, so classic RBAC-shaped BFLA does not apply to the primary
surface. This app's one genuinely privileged function, `ResetController` (nonprod-only, fully
resets Postgres+Kafka state), is gated by an entirely separate mechanism: `@Profile("nonprod")`
plus a constant-time (`MessageDigest.isEqual`) shared-secret header check, never session identity.
`ResetEndpointProfileGatingTest.BeanRegistration` proves neither the controller bean nor its
dedicated `SecurityFilterChain` is even registered when the `nonprod` profile is inactive.

**API6:2023 Unrestricted Access to Sensitive Business Flows — N/A.** This domain (kanban board
CRUD: boards/columns/tasks/subtasks) has no purchase, booking, inventory-limited, or other
sensitive business flow for this category to apply to.

**API7:2023 Server Side Request Forgery — N/A.** Grep for
`RestTemplate|WebClient|HttpClient|URLConnection|new URL\(` across all of `src/main` returned zero
matches. The only outbound integrations are the Kafka/Redpanda producer (Avro, fixed broker
config) and the Confluent schema registry (fixed `SCHEMA_REGISTRY_URL` env var) — neither is ever
derived from request input.

**API8:2023 Security Misconfiguration — mixed, two new todos filed.**
- *CSRF*: `assumed-covered-but-unverified`. `SecurityConfiguration` disables CSRF tokens entirely
  (`http.csrf(AbstractHttpConfigurer::disable)`). `SessionCookieAttributesE2ETest` empirically
  proves `SameSite=Strict` on the real `Set-Cookie` header (real-socket tier, not MockMvc) — but
  that proves the attribute is set, not that a real browser withholds the cookie cross-site (a
  claim no test in this repo's tiers can make). `CorsConfigTest` proves the resolved
  `CorsConfiguration` (non-wildcard allowlist, `allowCredentials=true`) but its own Javadoc states
  explicitly it "cannot prove what a real browser will actually allow" — no cross-origin-rejection
  test exists. New todo filed (below).
- *Security response headers*: `SecurityConfiguration` never calls `http.headers(...)`, so Spring
  Security's unconditional defaults apply (`X-Content-Type-Options`, `X-Frame-Options`,
  `Cache-Control`) — a strong code-level claim, but unverified on the wire (no test asserts them,
  unlike `SessionCookieAttributesE2ETest`'s precedent for cookie attributes). HSTS is conditional
  on `request.isSecure()`; confirmed absent: no `server.forward-headers-strategy` property anywhere
  in `application.properties`, and `Caddyfile` (read in full, both site blocks) adds zero `header`
  directives — meaning HSTS likely never fires in production despite the deployment being
  TLS-terminated at Caddy, since the app only ever sees the internal plain-HTTP hop. No CSP exists
  at either layer. New todo filed (below).

**API9:2023 Improper Inventory Management — covered.**
`AuthorizationGatingTest.Completeness.shouldCoverEveryDiscoveredRoute_withNoUnmatchedMapping`
reflectively queries `RequestMappingHandlerMapping` for every registered route under
`com.vrudenko.kanban_board.controller` and asserts a 1:1 set match against the test's own route
table (asserting `>= 22` discovered, zero unmatched) — a future route shipping with no gating
coverage fails this test, closing the "silent inventory drift" failure mode this category exists
to catch.

**API10:2023 Unsafe Consumption of APIs — N/A.** Same grep as API7 confirms no outbound
`RestTemplate`/`WebClient`/`HttpClient` consumption of any third-party API exists — the only
outbound integrations (Kafka/Redpanda, Confluent schema registry) are internal infrastructure at
fixed, operator-controlled endpoints, never third-party responses parsed as trusted input.

### Dependency CVEs — covered, see existing todo family (nothing new filed here)

Per the originating todo's own instruction, this audit adds nothing new to the dependency-CVE
thread. `.planning/todos/completed/2026-08-03-add-dependency-vulnerability-scan.md` already closed
scanning/remediation (OWASP `dependency-check-gradle` wired report-only, Dependabot added, Spring
Boot bumped 3.5.0->3.5.16, a real `commons-lang3` runtime CVE fixed).
`.planning/todos/pending/2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md`
already tracks the one known follow-up (picking a real `failBuildOnCVSS` rung once a genuine,
triaged baseline exists). This audit's own dependency-CVE verdict is "covered — see existing todo
family," full stop.

### New todos filed

- `.planning/todos/pending/2026-08-20-idor-same-user-chain-consistency-boardid-columnid-not-c.md`
  — API1: nested path segments never cross-checked against the leaf resource's real parent
  (moderate: same-user confusion, not a cross-user boundary crossing).
- `.planning/todos/pending/2026-08-20-add-rate-limiting-to-signin-to-bound-brute-force-volume.md`
  — API2/API4: no volumetric guard on `/signin`, confirmed genuinely absent from the codebase.
- `.planning/todos/pending/2026-08-20-security-response-headers-csp-and-unreliable-hsts-behind.md`
  — API8: no CSP anywhere, HSTS likely never emitted behind Caddy (no forwarded-headers-strategy),
  baseline headers unverified on the wire.
- `.planning/todos/pending/2026-08-20-verify-csrf-defense-with-a-real-cross-origin-rejection-t.md`
  — API8 (CSRF): the SameSite/CORS reasoning is sound but only half-verified; no test proves an
  actual cross-origin request is rejected.

Full raw trace (source files read, controllers/DTOs/mappers/tests inspected, grep queries run) is
this task's own execution transcript — no separate `MEASUREMENTS.md` was produced, since Task 1
was a pure read-only trace with no files to author beyond this Resolution and the four todos
above.
