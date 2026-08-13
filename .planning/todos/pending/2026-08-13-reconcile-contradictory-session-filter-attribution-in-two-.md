---
created: 2026-08-13T00:00:00.000Z
title: Reconcile contradictory session-filter attribution in two places
area: security
severity: minor
files:
  - src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
  - .planning/todos/completed/2026-08-10-decide-whether-to-close-the-with-user-vs-max-concurrent-se.md
---

## Problem

The now-closed todo
`.planning/todos/completed/2026-08-10-decide-whether-to-close-the-with-user-vs-max-concurrent-se.md`
and `InjectionAttemptTest`'s class Javadoc both attribute the enforcement of
`SecurityConfiguration`'s `MAX_CONCURRENT_SESSIONS = 2` ceiling to `SessionManagementFilter`
invoking the configured `sessionAuthenticationStrategy` bean. `SecurityConfiguration`'s own inline
comment on its `sessionManagement` DSL block states the opposite: that those declarations are
"declarations only -- no filter reads them on this application's authentication path," and that the
strategy is instead invoked explicitly from `AuthenticationController.authenticate`.

Both claims cannot be unqualifiedly true at once. Quick task 260813-k47 deliberately did not
adjudicate this while documenting the `.with(user())` vs. session-ceiling interaction in
`docs/CODE_STYLE.md` rule 4 and `AbstractAppMockMvcTest`'s class Javadoc — it named the ceiling and
the `sessionAuthenticationStrategy` bean's live-session count as the enforcing mechanism, and named
no servlet filter as the invoker, precisely to avoid minting a third copy of an unresolved claim in
a file meant to be authoritative.

## Solution

Candidates for whoever picks this up:

1. **Probe the actual filter chain in a test** and correct whichever claim is wrong. A test that
   inspects the live `FilterChainProxy`/`SecurityFilterChain` (or that neutralizes
   `SessionManagementFilter` and observes whether the session ceiling still triggers on a
   `.with(user())`-authenticated MockMvc request) would settle this empirically, the same way Phase
   7's `AuthenticationE2ETest` falsified Assumption A2 for the real signin path.
2. **Qualify both claims to scope them** if they turn out to both be true under different
   conditions — e.g. "on the real signin path, the strategy is invoked explicitly by
   `AuthenticationController.authenticate` before `SessionManagementFilter` ever sees the request"
   vs. "on a MockMvc-injected-principal request, `SessionManagementFilter` is the one that observes
   the new session and re-invokes the strategy." Update `InjectionAttemptTest`'s Javadoc and (if
   still relevant then) any style-guide prose accordingly.

Low priority — both existing claims already work around the ceiling correctly in practice
(`InjectionAttemptTest` uses `signinCookie()`+cookie-replay regardless of which component enforces
the limit); this is about correcting the record, not fixing a live defect.
