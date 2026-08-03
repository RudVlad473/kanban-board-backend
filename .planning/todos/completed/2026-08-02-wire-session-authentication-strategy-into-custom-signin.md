---
created: 2026-08-02T18:53:51.000Z
title: Wire a SessionAuthenticationStrategy into the custom signin path
area: backend
severity: security
files:
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
  - src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java
---

## Problem

`SecurityConfiguration:61-67` configures two session-management controls:

```java
session.maximumSessions(2).maxSessionsPreventsLogin(true);
session.sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::newSession);
```

Neither is actually enforced. Both are applied by a `SessionAuthenticationStrategy`
(`ConcurrentSessionControlAuthenticationStrategy` for the ceiling,
`ChangeSessionIdAuthenticationStrategy` for fixation), and that strategy only runs from inside an
authentication filter — normally `UsernamePasswordAuthenticationFilter`, invoked via
`SessionManagementFilter`. This application does not use either: `AuthenticationController.signin`
calls `authenticationManager.authenticate(token)` directly, then hands the resulting
`SecurityContext` straight to `securityContextRepository.saveContext(...)`. Spring Security 6 also
no longer installs `SessionManagementFilter` in the default filter chain, so there is no fallback
path that would invoke the strategy either.

Two concrete consequences, both security-relevant:

1. **Concurrent-session ceiling is unenforced.** `SessionPersistenceE2ETest.ConcurrentSessionCeiling`
   (added alongside this todo) proves a single principal can hold three live, independently valid
   sessions simultaneously — one more than the configured ceiling of two — because nothing ever
   registers a session against the principal for the ceiling to count against.
2. **Session id is not rotated on login (session-fixation exposure, threat `T-shl-01` in
   `260802-shl-PLAN.md`'s threat register).** `sessionFixation(newSession)` never runs, so a session
   id issued before authentication is not guaranteed to be replaced by a fresh one after
   authentication succeeds. This is partially bounded today by `server.servlet.session.cookie.http-only=true`
   plus `same-site=strict`, and by Spring Session's `JdbcIndexedSessionRepository` issuing a fresh id
   whenever a presented id has no matching row — but neither of those is the same guarantee
   `sessionFixation(newSession)` was configured to provide.

This is not caused by the Spring Session JDBC store-type fix delivered in `260802-shl` — the
custom signin path predates it — but that fix is what turned "these settings are silently inert"
from a documentation inaccuracy into something provably testable, which is how this was found.

## Solution

Two shapes are plausible; pick one after evaluating blast radius:

1. **Invoke a `SessionAuthenticationStrategy` explicitly from `AuthenticationController.authenticate`**,
   after `authenticationManager.authenticate(token)` succeeds and before
   `securityContextRepository.saveContext(...)`. Smaller diff, keeps the custom controller-based
   signin flow, but requires manually wiring `CompositeSessionAuthenticationStrategy` (concurrent
   session control + session fixation) as a bean and calling `.onAuthentication(authentication,
   request, response)` at the right point.
2. **Move signin onto a real authentication filter** (`UsernamePasswordAuthenticationFilter` or a
   custom filter extending it) so `SessionManagementFilter`'s normal machinery applies automatically.
   Larger diff — changes the authentication path for every request in the application (signin,
   signup, and by extension logout's session state) and needs its own test coverage for all three,
   which is why the plan that surfaced this (`260802-shl`) explicitly left it out of scope.

Either way: `SessionPersistenceE2ETest.ConcurrentSessionCeiling`'s tripwire test
(`shouldAllowThreeConcurrentSessions_whenMaxSessionsIsConfiguredButNoAuthenticationStrategyRuns`)
must be updated as part of this fix — it is written to go RED the day the strategy is correctly
wired, specifically so this fix cannot land without the test (and its Javadoc) being revisited.
`.claude/CLAUDE.md`'s session-management documentation entry (corrected to state the ceiling is
configured-but-unenforced, as part of `260802-shl`) should also be restored to a hard "maximum 2
concurrent sessions" claim once this lands.

Mark this security-relevant, not cosmetic: it affects session-hygiene and fixation posture, even
though (per `260802-shl`'s threat register, `T-shl-01`/`T-shl-04`) it does not itself widen an
authorization boundary — every request downstream is still independently authenticated and
ownership-verified regardless of how many concurrent sessions exist.
