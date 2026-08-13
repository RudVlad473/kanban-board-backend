---
created: 2026-08-13T00:00:00.000Z
title: "Two independent session-concurrency enforcers coexist (filter-held, in-memory vs. bean, JDBC-backed)"
area: security
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
---

## Problem

Quick task 260813-m9x measured (`PROBE-RAW.txt` Q2; `PROBE-FINDINGS.md`) that
`SecurityConfiguration`'s `sessionManagement` DSL block (`maximumSessions(2)`,
`sessionFixation().changeSessionId()`) produces **two** distinct
`CompositeSessionAuthenticationStrategy` instances, not one:

1. The `sessionAuthenticationStrategy` `@Bean` — explicitly invoked by
   `AuthenticationController.authenticate` on the real signin/signup path. Its
   `ConcurrentSessionControlAuthenticationStrategy` delegate is backed by
   `SpringSessionBackedSessionRegistry`, which reads the live `SPRING_SESSION` JDBC table — so its
   session count is consistent across horizontally scaled instances.
2. A separate strategy `SessionManagementConfigurer` composes from the same DSL calls and hands to
   the `SessionManagementFilter` it installs on the chain. Its
   `ConcurrentSessionControlAuthenticationStrategy` delegate is backed by an in-memory
   `SessionRegistryImpl` — per-instance, resets on restart, invisible to a second instance. This one
   only ever fires on requests that reach the chain already authenticated without a stored security
   context — in this codebase, exclusively MockMvc's `.with(user(...))` test shortcut.

Both currently enforce the same numeric ceiling (`MAX_CONCURRENT_SESSIONS = 2`), and only the
filter-held one is reachable from a genuine, unauthenticated-until-now HTTP request in a
hypothetical future flow that authenticates via a standard Spring Security filter (e.g. a
`UsernamePasswordAuthenticationFilter`-based login instead of this application's fully custom
`AuthenticationController.authenticate`). Today, in this codebase's actual traffic shape, only
enforcer 1 ever sees a real user — but the two enforcers silently diverging is a latent trap:
`docs/ARCHITECTURE.md`'s horizontal-scaling argument for the concurrent-session ceiling is sound
only for enforcer 1's registry, and nothing currently states that qualification.

Deliberately not fixed by 260813-m9x (`T-m9x-05`, disposition: accept, in that task's threat
register) — collapsing the two into one enforcer is a behavioral change to a live security control
(which registry backs the DSL's own filter, or whether the DSL should consume the bean via
`session.sessionAuthenticationStrategy(...)`) and needs its own task with its own tests, not a
comment-correction quick task.

## Solution

Not yet decided. Candidates for whoever picks this up:

1. **Wire the DSL to consume the application bean** — call
   `session.sessionAuthenticationStrategy(sessionAuthenticationStrategy)` (forward-referenced or
   restructured) inside `securityFilterChain`'s `sessionManagement` block, so
   `SessionManagementConfigurer` no longer composes a second, in-memory-backed strategy. Collapses
   to one enforcer, one registry, one ceiling. Needs care: verify this doesn't change behavior on
   the real signin path (which invokes the bean directly regardless) and add/adjust a test proving
   the filter-held path now shares the JDBC-backed registry too.
2. **Leave both, document the divergence explicitly** — add a note (where `docs/ARCHITECTURE.md`
   makes its horizontal-scaling claim, and/or in `SecurityConfiguration`) that the ceiling's
   cross-instance consistency guarantee applies to the real signin path's enforcer only, not to any
   hypothetical future authentication flow that routes through the standard filter chain.
3. **Do nothing** — if no realistic future flow in this application will ever authenticate through
   a standard Spring Security filter (this app's `AuthenticationController` is fully custom and
   unlikely to change), the second enforcer may be permanently dead code from a behavioral
   standpoint, in which case option 1 is a low-risk simplification rather than a risk-bearing
   change.

Low priority — no live request path in this codebase currently exercises the weaker (in-memory)
enforcer; this is a latent-trap / documentation-accuracy concern, not an active vulnerability.
