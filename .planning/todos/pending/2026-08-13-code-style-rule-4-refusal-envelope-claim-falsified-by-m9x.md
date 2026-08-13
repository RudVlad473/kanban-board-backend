---
created: 2026-08-13T00:00:00.000Z
title: "docs/CODE_STYLE.md rule 4's MockMvc-shortcut refusal claims are falsified by measurement"
area: docs
severity: minor
files:
  - docs/CODE_STYLE.md
---

## Problem

Quick task 260813-m9x measured the actual `.with(user(userId))` ceiling-refusal shape
(`PROBE-RAW.txt` Q4, `PROBE-FINDINGS.md`) while reconciling a separate, already-closed
attribution contradiction. Two sentences in `docs/CODE_STYLE.md` rule 4 (the `.with(user(userId))`
/ `MAX_CONCURRENT_SESSIONS` paragraph, currently around lines 190-195) turn out to be wrong:

1. **"the ceiling and the `sessionAuthenticationStrategy` bean's live-session count reject the
   login outright"** — measured false. The refusal on this path comes from
   `SessionManagementFilter`'s own DSL-composed `CompositeSessionAuthenticationStrategy`, backed by
   an in-memory `SessionRegistryImpl` — a different instance from the `sessionAuthenticationStrategy`
   `@Bean`, which never runs on this path at all (that bean is the real signin/signup path's
   enforcer only). See `SecurityConfiguration`'s corrected `sessionManagement` comment and bean
   Javadoc, both fixed by this quick task.
2. **"The refusal arrives as HTTP 401 carrying the exact same generic invalid-credentials envelope
   a wrong password would produce"** — measured false. The MockMvc-shortcut refusal is a bare
   servlet `sendError` (`MockHttpServletResponse.getErrorMessage()` non-null, `Content-Type` null,
   empty body) — `SessionManagementFilter`'s own failure-handler fingerprint, not this application's
   RFC 7807 `ProblemDetail` envelope. A real wrong-password refusal on the signin path *does* carry
   the RFC 7807 envelope with `code: BAD_CREDENTIALS`; the two are not byte-comparable, only both
   401.

The status code (401) and the "no session-specific signal leaks" intent both still hold — only the
mechanism attribution and the "exact same envelope" claim are wrong.

Raw evidence: `.planning/quick/260813-m9x-reconcile-the-contradictory-sessionmanag/PROBE-RAW.txt`
(Q2, Q4). Deliberately not fixed by 260813-m9x itself, per that task's D-08 scope lock — its
Task 2 was authorized to touch only `SecurityConfiguration.java` and `InjectionAttemptTest.java`.

## Solution

Not yet decided. Candidates for whoever picks this up:

1. Correct both sentences in `docs/CODE_STYLE.md` rule 4 to name `SessionManagementFilter`'s own
   DSL-composed strategy (not the bean) as the enforcer on this path, and describe the refusal as a
   bare `sendError` rather than the RFC 7807 envelope — citing quick task 260813-m9x's measurement.
2. Leave the doc as directional guidance ("expect a 401, do not rely on session-specific ceiling
   signals leaking") without naming the exact mechanism, if a future maintainer judges that level of
   detail not worth maintaining against Spring Security internals that could shift on a version bump.

Low priority — this is a documentation-accuracy correction with no `src/main` or `src/test`
behavioral component; nothing currently relies on the wrong claim being true.
