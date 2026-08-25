---
created: 2026-08-20T00:00:00.000Z
title: "CSRF defense reasoning is sound but only half-verified: no test proves an actual cross-origin request is rejected"
area: security
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
  - src/main/java/com/vrudenko/kanban_board/config/CorsConfig.java
  - src/test/java/com/vrudenko/kanban_board/security/SessionCookieAttributesE2ETest.java
  - src/test/java/com/vrudenko/kanban_board/config/CorsConfigTest.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## ASVS 4.0.3 cross-reference

A 33-agent ASVS 4.0.3 Level 2 audit independently re-confirmed this same finding via **V4.2.2** and
**V13.2.3**: the `SameSite=Strict` + CORS allowlist reasoning is sound, but no end-to-end
cross-origin-rejection test exists yet — no new information beyond what this todo already says.

## Problem

Filed from the OWASP API Security Top 10 audit closing
`.planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md`
(API8:2023 Security Misconfiguration; arguably API2:2023).

`SecurityConfiguration.securityFilterChain` calls `http.csrf(AbstractHttpConfigurer::disable)` —
zero CSRF-token defense. Two independent mitigations exist in the reasoning that this is still
safe, but they are unevenly tested:

- **`server.servlet.session.cookie.same-site=strict`** — a modern, SameSite-respecting browser
  never attaches this cookie to a cross-site request at all. **Half-verified**:
  `SessionCookieAttributesE2ETest.SigninCookieAttributes` empirically proves, over a real socket
  (not MockMvc), that the `Set-Cookie` header for a real signin genuinely carries
  `SameSite=Strict`. What it does **not** and cannot prove at this test tier is that a real browser
  then actually withholds the cookie on a cross-site request — that is inherently a browser
  behavior claim, not something a JVM-side REST Assured test can observe without a real headless
  browser in the loop.

- **`CorsConfig`'s explicit non-wildcard origin allowlist with `allowCredentials(true)`** —
  **unverified as actual rejection behavior**. `CorsConfigTest` asserts the *resolved
  `CorsConfiguration`* (non-wildcard origins, `allowCredentials=true`) but its own class Javadoc is
  explicit about the limit of that claim: "CORS is browser-enforced -- MockMvc dispatches
  in-process and never constructs a genuine cross-origin preflight OPTIONS request, so it cannot
  prove what a real browser will actually allow... Do not 'upgrade' this to a preflight test at
  this tier." No test anywhere in this repo constructs a real cross-origin request and observes it
  actually rejected.

Per this audit's own governing question ("the reasoning being sound is not the same claim as the
reasoning being tested"), this category is `assumed-covered-but-unverified`, not `covered` — one
half of the defense is proven at the attribute-presence level (not the browser-enforcement level),
and the other half has zero empirical test coverage at any level.

**Severity reasoning:** classified `minor`, not `security`, because the underlying defense is
almost certainly real (SameSite=Strict is a well-established, broadly-supported browser mechanism,
and the reasoning chain is sound) — this is a verification gap, not a suspected live vulnerability.

## Solution

1. **Add a real cross-origin CORS rejection test** at the `realSocket` tier (mirroring
   `SessionCookieAttributesE2ETest`'s precedent: `@Tag("realSocket")`,
   `SpringBootTest.WebEnvironment.RANDOM_PORT`, REST Assured against a real socket) — send a
   request carrying an `Origin` header not on the allowlist and confirm the response lacks
   `Access-Control-Allow-Origin` for that origin (the closest a JVM-side test can get to proving
   real rejection, since the actual *enforcement* of a same-origin policy happens in the browser,
   not the server — the server's job is only to withhold the permissive header).

2. **If a headless-browser-based E2E tier is ever added to this project** (none currently exists),
   that would be the correct place for a literal "cross-site form POST replays the session cookie —
   does it or doesn't it" behavioral proof. Not worth introducing a new test infrastructure tier
   for this alone; note it here so a future session doesn't have to re-derive that this is the gap
   remaining even after item 1 above ships.

3. Once item 1 lands, this category's verdict can be promoted from `assumed-covered-but-unverified`
   to `covered` for the CORS half; the SameSite half remains bounded by what a non-browser test tier
   can prove, which is already the best available evidence.
