# Probe Findings — quick task 260813-m9x

Interpretation of `PROBE-RAW.txt`, produced by
`SessionFilterAttributionProbeTest` (read-only introspection + behavioural replication, D-02).
All three probe methods passed; observations below quote raw lines verbatim.

## Q1 — chain composition

> `chain[0].filters = [..., org.springframework.security.web.session.ConcurrentSessionFilter, ...,
> org.springframework.security.web.session.SessionManagementFilter, ...]`
> `SessionManagementFilter present on any chain: true`
> `ConcurrentSessionFilter present on any chain: true`

Both filters are present on the application's single security filter chain. This confirms
`SecurityConfiguration.java:180-184`'s own aside ("the already-installed `ConcurrentSessionFilter`")
was correct, and directly contradicts the inline comment at `SecurityConfiguration.java:103-107`
("no filter reads them on this application's authentication path... Spring Security 6 no longer
installs `SessionManagementFilter` on the default chain either").

## Q2 — strategy identity (the load-bearing question)

> `Filter's strategy == application sessionAuthenticationStrategy bean: false`
> `filter-held strategy class: ...CompositeSessionAuthenticationStrategy`
> `filter-held delegate class: ...ConcurrentSessionControlAuthenticationStrategy`
> `filter-held delegate sessionRegistry class: org.springframework.security.core.session.SessionRegistryImpl`
> `filter-held delegate class: ...ChangeSessionIdAuthenticationStrategy`
> `filter-held delegate class: ...RegisterSessionAuthenticationStrategy`
> `application-bean delegate sessionRegistry class: org.springframework.session.security.SpringSessionBackedSessionRegistry`

`SessionManagementFilter` holds a **separate instance** of `CompositeSessionAuthenticationStrategy`
from the `sessionAuthenticationStrategy` `@Bean` — confirmed by reference inequality, not inferred.
`SecurityConfiguration` never calls `session.sessionAuthenticationStrategy(...)`, so
`SessionManagementConfigurer` composed its own strategy from the DSL's `maximumSessions(2)` /
`sessionFixation().changeSessionId()` calls, exactly as the plan's context section predicted. The
filter-held strategy's `ConcurrentSessionControlAuthenticationStrategy` delegate is backed by an
**in-memory** `SessionRegistryImpl`; the application bean's own delegate is backed by the
**JDBC-persisted** `SpringSessionBackedSessionRegistry`. These are two enforcers with two different
notions of "live sessions for this principal" — the filter-held one resets on every instance
restart and does not see sessions from a second instance; the bean's one reads a live, shared
count. (`maximumSessions` itself hit a field-lookup miss on both sides — Spring Security 6.5 stores
the configured ceiling as `sessionLimit`, not `maximumSessions`; declared-fields dump confirms this
is a moved-field-name situation, not a probe bug, and both sides missed identically so the
comparison stays symmetric.)

## Q3 — behaviour on the injected-principal path

> `SPRING_SESSION total rows before injected-principal sequence: 0`
> `call 1: status=200 spring_session_total=1 spring_session_for_principal=1`
> `call 2: status=200 spring_session_total=2 spring_session_for_principal=2`
> `call 3: status=401 spring_session_total=2 spring_session_for_principal=2`
> `call 4: status=401 spring_session_total=2 spring_session_for_principal=2`

Both halves of the documented `docs/CODE_STYLE.md` rule 4 sequence are confirmed exactly:
`200, 200, 401, 401`, and the `SPRING_SESSION` row count for the principal climbs one per
*successful* call and then plateaus at 2 — the two refused calls create no new rows. Four
identical `.with(user(<one principal>))` calls really do establish a brand-new session each time
and really do trip a ceiling at exactly `MAX_CONCURRENT_SESSIONS = 2`.

## Q4 — shape of the refusal

> Injected-principal path: `errorMessage: Unauthorized`, `Content-Type: null`, `body: ` (empty)
> Real signin path (control, different principal — see below): `errorMessage: null`,
> `Content-Type: application/problem+json`,
> `body: {"type":"about:blank","title":"Unauthorized","status":401,"detail":"Invalid username or
> password","instance":"/signin","code":"BAD_CREDENTIALS"}`

The two refusals are **not** the same shape. The injected-principal refusal is a bare servlet
`sendError` (non-null `getErrorMessage()`, null content type, empty body) — the fingerprint of
`SessionManagementFilter`'s own `authenticationFailureHandler`, a filter-internal handler that never
touches `GlobalExceptionHandler` or `ProblemDetailAuthenticationEntryPoint`. The real-path refusal
is this application's own RFC 7807 envelope with a `code` field, produced by
`AuthenticationController`'s blanket-catch-to-`BadCredentialsException` path. This falsifies
`docs/CODE_STYLE.md` rule 4's claim that the MockMvc-shortcut refusal "carries the exact same
generic invalid-credentials envelope a wrong password would produce" — the status code (401)
matches, but the envelope does not. Filed as a new pending todo per D-08 (not edited here).

**Methodology note on the Q4 control:** the real-path control deliberately signed in as
`noBoardsUser`, not the owning user the Q3 loop already used — reusing the same principal would
have let the loop's two successful `.with(user())` calls (which persist real `SPRING_SESSION` rows
for that principal, confirmed above) prime the real path's own ceiling before the control's first
signin even ran, confounding the very 200/200/401 sequence the control exists to observe. Using an
independent principal keeps the two probes orthogonal.

## What this means for each contested claim

- **`InjectionAttemptTest.java:52-53`** ("`SessionManagementFilter` treats each `.with(user())` call
  as a fresh login"): **supported**, exactly as written, for the MockMvc injected-principal path.
  Q2/Q3/Q4 together show causation, not mere correlation: the filter is present, holds a strategy
  whose ceiling maps to `MAX_CONCURRENT_SESSIONS`, and the third/fourth refused calls carry that
  filter's own failure-handler fingerprint. Needs only a citation to this quick task, no content
  correction.
- **`SecurityConfiguration.java:103-107`** (inline comment: "no filter reads them on this
  application's authentication path... Spring Security 6 no longer installs
  `SessionManagementFilter`"): **falsified**. The filter is installed and does read these
  declarations — just not on the path this comment is actually trying to describe. Needs
  correction, path-scoped.
- **`SecurityConfiguration.java:136-139`** (bean Javadoc: "neither the default filter chain... nor
  this application's custom signin path would ever call it otherwise"): **falsified** in the same
  way — `SessionManagementFilter` does exist and is invoked, on a different path than the one this
  Javadoc is actually about (the real signin/signup path, where this bean is genuinely the sole
  invoker). Needs correction, path-scoped.
- **`SecurityConfiguration.java:179-184`** (the `ConcurrentSessionFilter` remark inside the bean's
  own Javadoc, not separately contested but load-bearing context): confirmed accurate by Q1 — left
  untouched.

## Permanence considered and rejected (matrix row F)

Keeping this probe as a permanent regression test was considered and rejected. It asserts on
private Spring Security field names (already one field-lookup miss above — `maximumSessions` moved
to `sessionLimit` between the class's own field list and what earlier code apparently expected) and
on filter ordering, so a routine Spring Boot patch bump would turn it red for a reason unrelated to
this application's own behaviour. The coverage that actually matters already exists without it:
`AuthenticationTest.ConcurrentSessionCeiling` proves the real-path ceiling end-to-end, and
`docs/CODE_STYLE.md` rule 4 (now correctable per the todo filed above) documents the measured
MockMvc-path symptom. Deleted per D-05 after this findings file was written.

VERDICT: SMF_PRESENT_INVOKES
