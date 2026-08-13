---
created: 2026-08-13T14:46:10.740Z
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
