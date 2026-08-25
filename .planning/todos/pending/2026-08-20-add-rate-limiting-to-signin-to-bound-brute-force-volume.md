---
created: 2026-08-20T00:00:00.000Z
title: "No rate limiting / volumetric brute-force guard on POST /signin"
area: security
severity: security
files:

  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
  - src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java
  - src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## ASVS 4.0.3 cross-reference

A 33-agent ASVS 4.0.3 Level 2 audit independently rediscovered this same absent-rate-limiting gap
from three separate angles: **V2.2.1** (Authentication — brute force on `/signin`, this todo's
original scope), **V8.1.4** (Data Protection — general abnormal-request-volume detection), and
**V11.1.4** (Business Logic — anti-automation on business flows such as mass board/task creation,
not just login). Three independent ASVS chapters converging on the identical missing control is
evidence the original `/signin`-only framing undersold the gap — the Problem and Solution sections
below have been broadened accordingly.

## Problem

Filed from the OWASP API Security Top 10 audit closing
`.planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md`
(API4:2023 Unrestricted Resource Consumption; also API2:2023 Broken Authentication).

Confirmed absent, not merely untested: a case-insensitive grep for
`ratelimit|rate-limit|rate_limit|throttle|bucket4j|RateLimit` across all of `src/main` returned
zero matches. No rate-limiting library is on the classpath (`build.gradle` carries no
Bucket4j/Resilience4j/Spring Cloud Gateway RateLimiter or equivalent dependency), and no manual
request-counting logic exists in `AuthenticationController` or anywhere in the `security` package.

This is a genuine implementation gap, not a test gap — the framing matters for scoping the fix.
Two existing tests prove adjacent but different properties, and neither one bounds volume:

- `AuthenticationTest.AntiEnumeration` proves a wrong-password and a nonexistent-email response
  are indistinguishable (same 401, same generic body) — it says nothing about how many attempts a
  caller may make.

- `AuthenticationTest.ConcurrentSessionCeiling` proves a *third concurrent session for one already
  partially-authenticated principal* is rejected — a ceiling on simultaneous sessions, not on
  signin attempt volume for a not-yet-authenticated caller.

- `SigninTimingEqualizationTest` proves response-time equalization (defends against a
  credential-existence timing oracle), again orthogonal to volume.

An unauthenticated caller can currently send an unbounded number of `/signin` requests per unit
time — nothing in this codebase throttles, backs off, or locks out after N failures. Anti-
enumeration and timing-equalization make each individual guess non-distinguishable, but do not
raise the cost of guessing at scale (credential stuffing, distributed brute force against weak
passwords).

**Scope broadened per the ASVS cross-reference above:** the same absent control also leaves every
authenticated business endpoint (board/column/task/subtask creation, in particular) with no
abnormal-request-volume detection and no anti-automation guard — V8.1.4 and V11.1.4 name this as a
general gap, not one confined to the unauthenticated `/signin` path. The fix below should be scoped
to general request-volume abuse across authenticated business endpoints, not only `POST /signin`.

## Solution

Recommend a request-rate guard covering `POST /signin` and `/signup` (to bound account-
enumeration-via-creation and abuse) **and, per the ASVS cross-reference above, general
request-volume abuse across authenticated business endpoints** (e.g. rapid mass board/column/task
creation), sized conservatively given this app's actual traffic profile (single small VPS, Spring
Session JDBC already in the request path):

1. **Per-IP and/or per-email token-bucket limiter** (Bucket4j is the natural fit given this
   project's existing Gradle-plugin-based approach to adding scoped tooling — see how Error Prone/
   JaCoCo/dependency-check were each added with a measured, documented gate strength) in front of
   `AuthenticationController.signin`. Reject over-limit requests with 429, not the same 401 used
   for bad credentials — a distinct status code here is fine (unlike the deliberate 401/401
   indistinguishability between wrong-password and ceiling-rejection, over-limit is not a
   credential-validity oracle).

2. **Decide the bound empirically**, not by guessing a round number — e.g. instrument a local run
   to see realistic legitimate-user retry patterns (mistyped password, MFA-less retry) before
   picking a threshold, matching this project's established "measure first, then gate" convention
   (Error Prone, JaCoCo, dependency-check `failBuildOnCVSS` all followed this).

3. **Add a test proving the limiter itself** (e.g. Nth request in a burst returns 429, next
   request after the window resets returns to normal 401/200 behavior) — this is what closes the
   gap this todo exists to name, not just adding the library.

Out of scope for this todo: broader DDoS/network-layer protection (Caddy/infrastructure level) —
this is specifically about the missing application-level guard on the authentication endpoint.
