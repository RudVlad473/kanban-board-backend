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

## Resolution

Resolved by quick task 260813-m9x. Candidate 1 (probe the live filter chain) was chosen, via a
throwaway, read-only `FilterChainProxy` introspection test
(`SessionFilterAttributionProbeTest`, created untracked, never entered git history, deleted before
the final commit) plus behavioural replication of the `.with(user())` refusal sequence.

**Verdict: `SMF_PRESENT_INVOKES`** — `SessionManagementFilter` IS installed by
`SessionManagementConfigurer` from `SecurityConfiguration`'s `sessionManagement` DSL declarations,
and it DOES invoke a session-authentication strategy — but not the `sessionAuthenticationStrategy`
`@Bean`. The measurement (`PROBE-RAW.txt`, quoted in full in `PROBE-FINDINGS.md`):

- **Q1 (chain composition).** Both `SessionManagementFilter` and `ConcurrentSessionFilter` are
  present on the application's single security filter chain (`chain[0].filters` lists both by
  fully-qualified class name).
- **Q2 (strategy identity, the load-bearing question).** `SessionManagementFilter`'s held strategy
  is reference-distinct (`==` false) from the `sessionAuthenticationStrategy` bean. It is a
  *separate*, DSL-composed `CompositeSessionAuthenticationStrategy` whose
  `ConcurrentSessionControlAuthenticationStrategy` delegate is backed by an **in-memory**
  `SessionRegistryImpl` — unlike the bean's own delegate, backed by the **JDBC-persisted**
  `SpringSessionBackedSessionRegistry`. Two independent ceiling enforcers, two different notions of
  "live sessions for this principal."
- **Q3 (behaviour).** Four identical `.with(user(<principal>))` calls returned exactly
  `200, 200, 401, 401`, with the principal's `SPRING_SESSION` row count climbing to 2 and then
  plateauing — confirming the ceiling really trips and really stops creating rows once tripped.
- **Q4 (refusal shape).** The injected-principal refusal is a bare servlet `sendError`
  (`getErrorMessage()` non-null, `Content-Type` null, empty body) — `SessionManagementFilter`'s own
  failure-handler fingerprint. The real-signin-path refusal (control, using a different principal to
  avoid confounding the loop above) carries this application's RFC 7807 `ProblemDetail` envelope
  with `code: BAD_CREDENTIALS`. The two are not the same shape, only the same status code.

**Files corrected (both copies in `SecurityConfiguration`, per this task's own D-06):** the inline
comment on the `sessionManagement` DSL block, and the `sessionAuthenticationStrategy` bean's opening
Javadoc paragraph — both previously claimed no filter reads the DSL declarations on this
application's authentication path; both now state the measured split, path-scoped: the filter-held
strategy fires only on a request that arrives already authenticated without a stored context
(MockMvc's `.with(user(...))`), the bean is the sole invoker on the real signin/signup path.
`InjectionAttemptTest`'s class Javadoc needed no content correction — its original claim was
supported by the measurement — only a citation to this quick task and a note on which strategy
instance/registry the filter actually holds.

**Deliberately left alone:** `docs/CODE_STYLE.md` rule 4 and `AbstractAppMockMvcTest`'s Javadoc
(out of scope, D-08) — Q4 falsified rule 4's "exact same generic invalid-credentials envelope"
claim and its bean-attribution sentence; filed as
`.planning/todos/pending/2026-08-13-code-style-rule-4-refusal-envelope-claim-falsified-by-m9x.md`
rather than edited here. The two-enforcer situation itself (T-m9x-05, disposition: accept) was
filed as
`.planning/todos/pending/2026-08-13-two-independent-session-ceiling-enforcers-coexist.md` rather
than resolved by rewiring the DSL.

Raw evidence: `.planning/quick/260813-m9x-reconcile-the-contradictory-sessionmanag/PROBE-RAW.txt`
and `PROBE-FINDINGS.md`.
