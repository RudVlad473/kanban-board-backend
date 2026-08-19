---
created: 2026-08-10T20:00:00.000Z
resolved: 2026-08-19
resolves_phase: 10
title: Set the Secure flag on the session cookie once real TLS exists (Phase 5)
area: backend
severity: security
files:
  - src/main/resources/application.properties:56
  - src/main/resources/application-test.properties:36
---

## Problem

`/claude-security` scan (2026-08-10, medium effort, whole-repo `attack-surface` scope, run
against phase 07.1's finished HEAD) surfaced two related findings on the same root cause:

- **F4** — `server.servlet.session.cookie.secure=false` in `application.properties:56`, and this
  deployment has no TLS anywhere (embedded Tomcat speaks plain HTTP only; `docker-compose.yml`
  maps straight to host port 8080 with no TLS-terminating proxy in front of it).
- **F5** — the same root cause confirmed from a second angle: there is no `server.ssl.*`
  configuration anywhere in the app, and the identical `secure=false` line is repeated in
  `application-test.properties:36`.

Without the `Secure` attribute, a browser will send the session cookie over a plain HTTP
connection if one is ever available to an attacker (e.g. a network-level downgrade or a stray
`http://` link), which a `Secure` cookie would refuse to do.

## Why this is deferred, not fixed now

Flipping `cookie.secure=true` today would be an active regression, not neutral hardening,
because **no environment this app currently runs in has TLS**:

- Local dev (`docker compose up`, `README.md`'s quick start) serves the app over plain
  `http://localhost:8080`. A `Secure` cookie is never sent back by a browser on a subsequent
  plain-HTTP request, breaking every authenticated local-dev session outright.
- The production deployment target as of this phase is still the (now-deleted) AWS EC2 host
  history — real HTTPS is `REQUIREMENTS.md` INFRA-04, explicitly scoped to Phase 5
  (`docs/plans/backend-modernization/` / `.planning/ROADMAP.md` Phase 5, "Infra Migration"), not
  yet shipped.
- The test suite's real-socket tier (`AbstractAppE2ETest`, REST Assured against
  `RANDOM_PORT`) drives plain HTTP too. Apache HttpClient's default cookie handling honors the
  `Secure` attribute per RFC 6265 and will not replay a `Secure` cookie on a subsequent plain-HTTP
  request within the same test — turning `secure=true` on today would break session-cookie relay
  in that tier as well, not just in a real browser.

A `Secure` cookie attribute is only meaningful once there is a secure transport for it to be
scoped to. Setting it before TLS exists provides zero protection (there is no plaintext
connection anywhere to protect against yet, since the whole deployment is plaintext) while
breaking the one thing that does work today.

## Solution

Flip `server.servlet.session.cookie.secure=true` in `application.properties` (production
profile only — `application-test.properties` should very likely stay `false`, since the test
profile's real-socket tier has no TLS listener either and never will) as part of Phase 5's
INFRA-04 Caddy/TLS cutover, immediately after Caddy starts terminating real HTTPS in front of
the app. Verify with a real signin-then-authenticated-request round trip against the live HTTPS
endpoint post-cutover, not just a config-value assertion — the property flipping to `true` proves
nothing about whether the cookie is actually still usable end-to-end.

## Resolution

Closed by **Phase 10, Plan 05**. `server.servlet.session.cookie.secure=true` set in both
`application.properties` and `application-test.properties` — deliberately including the test
profile, diverging from this todo's original "production only" recommendation.

**Why the divergence is safe:** the original caution assumed a client that automatically decides
whether to replay a cookie based on transport security, which would indeed break the real-socket
test tier's cookie relay under plain HTTP. The new `SessionCookieAttributesE2ETest` (`@Tag
("realSocket")`) never relies on automatic replay — it extracts the raw `Set-Cookie` header via
REST Assured's `.extract().detailedCookie(...)` and asserts on the attribute directly. Nothing in
the test suite depends on a client actually withholding the cookie over plain HTTP, so setting
`secure=true` in the test profile costs nothing and keeps both profiles in lockstep (the original
todo's own stated worry — "an override ambiguity" between the two files — is fully closed rather
than partially).

**Live verification, not just a config-value assertion** (per this todo's own closing
instruction): after `application.properties`'s change deployed to nonprod over real HTTPS
(`kanban-board-rud-vlad-473-nonprod.duckdns.org`), a fresh signup/signin round trip confirmed
`Set-Cookie` carries `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, and `Max-Age`; a follow-up
authenticated request with that cookie returned `200` (not `401`) from `/api/boards`; and a plain-
HTTP request to the same host returned a `308` redirect to HTTPS before any cookie decision could
even be made, confirming there is no unprotected path to observe the negative case on.
