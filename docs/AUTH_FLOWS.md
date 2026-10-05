# Authentication Flows

This document is for a **frontend or QA engineer writing tests against this API**, on the
assumption of no JVM or Spring background. It answers one question: *what does a client actually
observe when it calls the two authentication routes, and what will silently break an automated
test suite driving them?* It is a **Scenarios (+1)** view per
[DIAGRAM_CONVENTIONS.md](DIAGRAM_CONVENTIONS.md) -- end-to-end, client-observable, traced at the
HTTP boundary rather than through Spring's internal machinery.

[docs/ARCHITECTURE.md](ARCHITECTURE.md) embeds the same signin diagram for a different reader and a
different question: a *security reviewer* asking "is this endpoint safe?". This document is
complementary, not competing -- it reads the same code from the client's side of the wire, adds
`POST /api/signup` (not drawn in ARCHITECTURE.md at all), keeps the security-review detail of both
routes in prose right under their diagrams (BCrypt timing parity, why a rejected third session looks
like a wrong password, the accepted concurrent-signin overshoot, commit-after-flush timing), and
closes with the session/cookie/CORS facts a Playwright suite needs and a security review does not.

Both diagrams have four lifelines: the client, the application (`AuthenticationController`), the
`users` table and the session store (Spring Session JDBC's `spring_session` tables). The class names
live in the prose below; the diagrams carry only HTTP, status, `ProblemDetail` `code`, cookie and
SQL-level facts.

## Sign up

**`POST /api/signup`** -- one of the two authentication routes reachable without a session (the
other is `/signin`). The Swagger docs, Swagger UI and the actuator health check are also `permitAll`
(`SecurityConfiguration#securityFilterChain`); every other route needs a session.

### Request body

| Field | Required | Constraint |
|---|---|---|
| `email` | yes | a syntactically valid email address |
| `password` | yes | 8-64 characters; at least one uppercase letter, one lowercase letter, one digit, and one special character |
| `displayName` | no | if present, must be non-blank and 3-32 characters |

### Responses

| Status | Code | Cause |
|---|---|---|
| `201` | -- | Account created and immediately signed in |
| `400` | `VALIDATION_FAILED` | Request body fails bean validation (runs before the handler method body) -- response carries a per-field `errors` map |
| `409` | `DUPLICATE_RESOURCE` | `email` is already registered (the checked, expected path) |
| `409` | `DATA_INTEGRITY_VIOLATION` | A race between two simultaneous signups for the same address -- the database's unique constraint on `users.email` (`uk_users_email`, `V1__init.sql`) backstops the checked guard above; the loser of the race lands here instead, with a different `code` than the checked path |
| `401` | `BAD_CREDENTIALS` | The account was created, but automatically signing it in immediately afterward failed for any reason -- the just-created account is rolled back (deleted) and the client sees a generic credentials failure, not a 403, despite an intermediate access-denied exception internally |

<img src="diagrams/scenarios/signup.png" width="1047" alt="Sequence diagram: signup, its 400 and 409 arms, the automatic signin and the rollback when it fails">
<sub>[diagram source](diagrams/scenarios/signup.mmd)</sub>

**How the diagram maps to code:**

- The `409 DUPLICATE_RESOURCE` arm is `UserService#save` finding the email already used
  (`existsByEmail`) and throwing `AppDuplicateResourceException`. The call sits outside the
  controller's `try` block on purpose, so the exception reaches `GlobalExceptionHandler` as a `409`
  instead of being swallowed by the blanket `catch` that turns authentication failures into `401`.
  The race arm is the same `save` failing on the unique constraint with
  `DataIntegrityViolationException`, which the handler's broader arm maps to `409
  DATA_INTEGRITY_VIOLATION`; a client may see either code.
- After the insert, signup runs the same `AuthenticationController#authenticate` helper as signin
  (see "Sign in" below): it builds the token from the new user's **id**, not the email, and the
  provider returns a minimal principal. The concurrent-session ceiling cannot reject a signup,
  because an account that did not exist a moment ago has zero live sessions; the session-id rotation
  half of the strategy still applies.
- If that authentication fails for any reason, the controller deletes the new row
  (`UserService#deleteById`) and throws `AccessDeniedException`, which its own blanket `catch`
  rethrows as `BadCredentialsException`: the client sees `401`, not `403`, despite the intermediate
  exception type. No test drives this arm, and it is hard to reach in practice, since the password the helper
  checks is the one just stored.
- As with signin, the session rows are committed only as the response is flushed, so a client that
  inspects the database the instant it gets its `201` sees nothing yet.

**What this means for a test:** two traps are easy to miss. First, the `201`'s `Location` header
names `/api/users/me`, and that route has no `GET` handler yet -- a test that follows the
`Location` header will fail, not because of a test bug. Second, running the same signup request
twice against a database that is not reset between runs will hit the `409` arm on the second run
rather than the `201` arm the first time produced -- a suite that assumes a clean signup on every
run must either randomize the email address per run or reset state between runs.

## Sign in

**`POST /api/signin`** -- the other authentication route reachable without a session.

### Request body

| Field | Required | Constraint |
|---|---|---|
| `email` | yes | a syntactically valid email address |
| `password` | yes | 8-64 characters; at least one uppercase letter, one lowercase letter, one digit, and one special character |

### Responses

| Status | Code | Cause |
|---|---|---|
| `200` | -- | Signed in |
| `400` | `VALIDATION_FAILED` | Request body fails bean validation -- same shape as signup's `400` |
| `401` | `BAD_CREDENTIALS` | Unknown email, wrong password, **or** the caller is already at the 2-session ceiling -- all three collapse to the exact same response |

<img src="diagrams/scenarios/signin.png" width="1084" alt="Sequence diagram: signin, its 400 arm, the three causes of the collapsed 401 and session establishment">
<sub>[diagram source](diagrams/scenarios/signin.mmd)</sub>

**How the diagram maps to code, and the decisions behind it:**

- **Timing parity (finding F1, 2026-08-10 `/claude-security` scan).** For an unknown email the
  controller still runs one BCrypt comparison, against a hash computed once at startup, and discards
  the result. Without it an unknown email would fast-fail with no BCrypt work while a registered one
  always pays one, so response *timing* would let anyone enumerate accounts even though the response
  *body* is already identical. The registered path still does one extra indexed read
  (`loadUserByUsername`, the second `SELECT` in the diagram), so this narrows the channel without
  making the endpoint provably constant-time.
- **One `401` body for three causes.** Unknown email, wrong password and the session ceiling all
  answer `401 BAD_CREDENTIALS` with the same body. The ceiling rejection is deliberately not given
  its own status: that would hand an attacker a validity oracle for "these credentials are correct,
  the account just has sessions open". `SecurityConfiguration#sessionAuthenticationStrategy` throws
  `SessionAuthenticationException` and the controller's `Try` plus blanket `catch` collapse it into
  `BadCredentialsException`; `AuthenticationTest.ConcurrentSessionCeiling` proves the third signin
  gets `401` with no cookie, and `AuthenticationTest.Signin.AntiEnumeration` proves the unknown-email and
  wrong-password bodies are byte-identical.
- **The strategy is a composite with a deliberate order.** The ceiling check (against the live
  session count for that user) runs first and the session-id rotation second, so a login the
  ceiling is about to refuse never rotates the caller's existing session id as a side effect.
- **The ceiling has a small, bounded overshoot.** Two genuinely simultaneous signins for one user
  can both read an under-threshold count before either commits its row, so one extra session can
  briefly exist; the next non-concurrent signin is still refused. This was accepted knowingly
  (finding F6, 2026-08-10); the measured detail is in `SecurityConfiguration#sessionAuthenticationStrategy`'s
  Javadoc and `ConcurrentSigninCeilingE2ETest`.
- **Commit-after-flush.** The security context is written into a request-scoped session; the
  `spring_session` rows reach Postgres only as the response is flushed, after the handler has
  returned. A client that inspects the database the instant it receives the `200` sees nothing yet.
- **What is stored.** The token is built from the user's **id**, not the email, and
  `UserAuthenticationProvider` returns a minimal principal (username only, no password hash), which
  is the object Spring Session serializes; `AuthenticationTest.SigninPersistence` pins that the hash
  never reaches the store.
- **`200`, not `201`, and no `Location`.** Signin's success differs from signup's in the status and in
  carrying no `Location` header; the body is the same `{id, email, displayName, theme}`.

**What this means for a test:** a `401` on this route has three distinct causes a client cannot
tell apart -- an unregistered email, a wrong password, and a rejected third concurrent session for
an otherwise-valid login. A failing signin in a test is therefore not, by itself, evidence that the
password is wrong; see the concurrent-session ceiling below, which is the cause most likely to
produce a `401` a test author did not expect.

## What will break your E2E suite

This section is the reason this document exists as its own file rather than a paragraph inside
[ARCHITECTURE.md](ARCHITECTURE.md). Each item below states a fact, the property or constant it
comes from, and the consequence for a test suite -- not just the fact alone.

- **The concurrent-session ceiling is 2, per user, and is real across parallel test workers.**
  `SecurityConfiguration.MAX_CONCURRENT_SESSIONS = 2`. It is counted from live rows in the shared,
  JDBC-backed session store, not per application process, so it holds identically whether the
  suite runs against one instance or several. **Consequence:** an E2E suite that runs more than 2
  parallel workers signed in as one seeded fixture user will get `401 BAD_CREDENTIALS` from the
  third worker onward -- a response that reads exactly like a wrong password (see the collapsed
  `401` above). **Mitigation:** give each parallel worker its own fixture user, or call
  `POST /api/logout` between tests that share one.
- **Two different session lifetimes exist by design, and the mismatch bites in a specific
  direction.** The session cookie (`server.servlet.session.cookie.name=JSESSIONID`)'s
  `server.servlet.session.cookie.max-age` is `600` (10 minutes);
  the server-side session timeout, `spring.session.timeout`, is `180m` (180 minutes). These are not
  a typo of each other. **Consequence:** the browser discards the cookie long before the server
  would ever expire the session -- a long-running suite that logs in once and keeps working past 10
  minutes will start getting `401 UNAUTHENTICATED` responses (no session cookie presented at all)
  while the server-side session backing that login is still perfectly alive.
- **The session cookie carries the `Secure` attribute** (`server.servlet.session.cookie.secure=true`).
  A browser will not transmit the session cookie over a non-TLS connection. `http://localhost` and
  `http://127.0.0.1` remain trustworthy origins in modern browsers, so local development against
  the compose stack is unaffected. **Consequence:** a test harness pointed at a plain-HTTP
  deployment at any other hostname will silently get no cookie back on the follow-up request and
  will see the same `401 UNAUTHENTICATED` the `max-age` and `SameSite` bullets above already
  describe -- not a distinguishable error, just a login that appears not to have happened.
- **The session cookie's `SameSite` policy is `strict`** (`server.servlet.session.cookie.same-site`).
  **Consequence:** a test harness that drives this API's cookie from a different site than the one
  the browser considers "current" (e.g. a test runner opening the API directly in one tab while the
  frontend runs on another origin, or certain cross-site redirect-based auth flows) will find the
  cookie not sent at all, producing the same `401 UNAUTHENTICATED` as no login having happened.
- **CORS is credentialed and origin-allow-listed, not wildcarded.** `CorsConfig` sets
  `allowCredentials(true)`, which the CORS spec forbids combining with a wildcard origin -- so the
  allow-list is explicit, read from `app.cors.allowed-origins` (defaults:
  `http://localhost:5173,http://localhost:3000`). Allowed methods: `GET, POST, PUT, PATCH, DELETE`.
  **Consequence:** a test origin absent from that list gets a normal server response that the
  *browser* then drops before the test's HTTP client ever sees a body -- indistinguishable from a
  network failure unless the property is checked first.
- **The session id rotates on every successful authentication** (`ChangeSessionIdAuthenticationStrategy`,
  run as the second half of the same composite strategy described under "Sign in" above; it rotates
  when the request presents an existing session, which `AuthenticationTest.SessionFixation` proves
  by re-signing in with the first cookie). **Consequence:** a test must
  not cache or assert a stable session id across a login -- the id after signin is guaranteed to
  differ from any id the client held before it.
- **CSRF protection is disabled** (`http.csrf(AbstractHttpConfigurer::disable)`). **Consequence:**
  there is no CSRF token to fetch before a mutating request -- a test author coming from a
  traditional Spring MVC app will look for one and will not find it; none is needed.
- **`401` and `403` mean different things, and only one of them is a credentials problem.** A
  request with no valid session at all is answered by the security filter chain itself, before any
  application code runs, with `401` and `code: UNAUTHENTICATED`. A request with a *valid* session
  that touches a resource owned by someone else is answered by application code with `403` and
  `code: ACCESS_DENIED`. **Consequence:** a `401` never means "your data was wrong" -- it means "you
  were not signed in at all." See [ARCHITECTURE.md](ARCHITECTURE.md)'s four-way `401`/`403`/`400`/
  `409` rejection diagram for the full split across all four statuses.
