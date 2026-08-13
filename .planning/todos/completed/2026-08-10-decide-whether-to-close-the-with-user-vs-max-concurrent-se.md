---
created: 2026-08-10T13:40:00.000Z
resolved: 2026-08-13
title: Decide whether to close the .with(user()) vs MAX_CONCURRENT_SESSIONS test-infra interaction
area: testing
severity: minor
files:
  - src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
  - src/test/java/com/vrudenko/kanban_board/security/AuthorizationGatingTest.java
  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
  - docs/CODE_STYLE.md
---

## Problem

Discovered while writing plan 07.1-08's `InjectionAttemptTest`: MockMvc's
`SecurityMockMvcRequestPostProcessors.user(userId)` shortcut (used throughout this codebase's
`controller/*ControllerTest` classes for already-authenticated scenarios) establishes a brand-new
HTTP session on every request it authenticates, via `HttpSessionSecurityContextRepository`'s
end-of-chain `saveContext()`. When a single test method issues three or more `.with(user(userId))`
calls for the **same** principal, the third call is rejected with 401 `UNAUTHENTICATED` --
`SecurityConfiguration`'s own `MAX_CONCURRENT_SESSIONS = 2` ceiling, enforced by
`SessionManagementFilter` invoking the configured `sessionAuthenticationStrategy` bean, which treats
each freshly-created session as a new login.

This is invisible in production and in every existing test class today: real requests always
authenticate through `AuthenticationController.authenticate`, which pre-establishes the session via
an explicit `sessionAuthenticationStrategy.onAuthentication(...)` call *before* the security context
is ever saved -- so by the time `HttpSessionSecurityContextRepository` runs, a session already
exists, and `SessionManagementFilter` never treats it as "new." No existing `*ControllerTest` class
calls `.with(user())` more than twice for the same user within one test method, so nothing has
tripped this before.

Confirmed empirically with a throwaway scratch test (not committed): 4 identical
`.with(user(userId))`-authenticated GETs to the same URL in one method returned 200, 200, 401, 401.
Switching to a real `signinCookie()` call once per test method, replaying the returned cookie on
every subsequent request, avoided the issue entirely (4/4 returned 200) -- this is the fix plan
07.1-08's two new test classes both adopted.

## Solution

No code change is required to close this -- `InjectionAttemptTest`/`AuthorizationGatingTest` already
work around it by using `signinCookie()`+cookie-replay instead of `.with(user())`. This todo is about
whether the *pattern itself* deserves closing off in `docs/CODE_STYLE.md` rule 4 so a future test
author doesn't independently rediscover it:

1. **Document only** (cheapest): add a note to rule 4 or to `AbstractAppMockMvcTest`'s Javadoc
   warning that `.with(user())` should not be called more than twice for the same principal within
   one test method, and pointing at `signinCookie()`+cookie-replay as the alternative for tests that
   need three or more authenticated calls for one user.
2. **Investigate a MockMvc-level fix**: a custom `RequestPostProcessor` wrapping `.with(user())`
   that also propagates a shared `MockHttpSession`/cookie across calls within a test, so existing
   call sites don't need to switch to `signinCookie()` by hand. Higher effort, not obviously worth it
   given how rarely a single test method needs 3+ authenticated calls for the same user.
3. **Do nothing further** -- the two classes that hit this already document and route around it in
   their own class Javadoc; a future test author hitting the same 401 has this todo's commit history
   and 07.1-08's SUMMARY to search.

Recommendation: option 1, the smallest change that prevents the next person from re-deriving this by
trial and error.

## Resolution

Resolved by quick task 260813-k47: option 1 (document only) was chosen, per this todo's own
recommendation. The account landed in two places, deliberately, rather than one: `docs/CODE_STYLE.md`
rule 4 carries the full worked account (the mechanism, the measured `200, 200, 401, 401` sequence, why
the boundary is the test method, and the `signinCookie()` alternative), because rule 4 is where the
test-tier decision is made *before* writing a test; `AbstractAppMockMvcTest`'s class Javadoc carries a
short pointer to the same ceiling, deferring to rule 4 for the reasoning, because that Javadoc is
literally where `signinCookie()` is defined and is where an author following an existing test (rather
than reading the style guide first) will actually land.

This todo's own premise about the reference classes was corrected, not transcribed: it does not claim
`AuthorizationGatingTest` adopted the `signinCookie()` workaround, but a planning-time grep confirmed it
independently, and the correction is worth recording here since it shapes what the new documentation
says. `InjectionAttemptTest` calls `signinCookie()` 25 times and `.with(user(` twice; `AuthorizationGatingTest`
calls `signinCookie()` zero times and `.with(user(` six times, spread across methods that never exceed
two authenticated calls per principal. So the new documentation cites `InjectionAttemptTest` — and only
`InjectionAttemptTest` — as the cookie-replay reference, and cites `AuthorizationGatingTest` as the
counterpart that correctly keeps the shortcut because it never needs more than two calls.

This Problem section's own claim that `SessionManagementFilter` invokes the enforcing strategy was
deliberately **not** repeated in either new paragraph. `SecurityConfiguration`'s own inline comment
states the opposite — that no filter reads those declarations on this application's authentication
path — and that contradiction was not adjudicated here (a doc edit cannot resolve it; a filter-chain
probe can). It is now tracked by its own pending todo:
`.planning/todos/pending/2026-08-13-reconcile-contradictory-session-filter-attribution-in-two-.md`.
