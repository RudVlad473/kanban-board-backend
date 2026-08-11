---
phase: quick-260811-ezy
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
  - docs/ARCHITECTURE.md
  - .planning/todos/pending/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md
  - .planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md
autonomous: true
requirements: [QUICK-260811-ezy]

estimate:
  tokens: 70000
  raw_tokens: 70000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "A POST /signin carrying a well-formed but unregistered email performs exactly one BCrypt password comparison — the same count as a POST /signin carrying a registered email and a wrong password (D-02)"
    - "Both of those requests still return HTTP 401 with a byte-identical ProblemDetail body: AuthenticationTest.Signin.AntiEnumeration passes with none of its assertions edited"
    - "The hash the unregistered-email branch compares against is produced by the application's own configured PasswordEncoder bean at startup, so its BCrypt work factor tracks BeanConfiguration rather than a cost baked into a source literal (D-01)"
    - "The new regression test was observed RED before the production change existed and GREEN after — its teeth are demonstrated by an actual failing run, not asserted (D-03)"
    - "The new test class carries no JUnit @Tag, so it runs inside build.gradle's fastTest task and therefore inside the pre-commit gate"
    - "No Mockito construct (@Mock, @MockBean, MockitoExtension) is introduced anywhere — the invocation counter is a hand-written delegating PasswordEncoder bean wired through the real Spring context (docs/CODE_STYLE.md rule 4)"
    - "./gradlew spotlessCheck and ./gradlew test both pass"
    - "The source todo no longer sits in .planning/todos/pending/"
  artifacts:
    - src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java
    - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
    - docs/ARCHITECTURE.md
    - .planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md
  key_links:
    - "AuthenticationController's injected PasswordEncoder -> the same singleton bean UserAuthenticationProvider injects: if these two ever resolve to different encoders, the two branches' costs silently diverge again and the mitigation dies with every test still green"
    - "the @PostConstruct-computed equalizer hash -> BeanConfiguration.passwordEncoder()'s configured BCrypt strength (this derivation is the whole reason D-01 rejects a hardcoded hash literal)"
    - "the counting delegate's @Primary bean -> every PasswordEncoder injection point inside the test's Spring context: if @Primary stops winning, the counter reads 0 forever and the tests fail for a reason that has nothing to do with the fix"
    - "catch (AppEntityNotFoundException) placed ahead of catch (Exception) in signin -> the equalizing comparison runs on the unknown-email branch only, never doubling up on a branch that already paid one BCrypt"
---

<objective>
Close finding **F1** from the 2026-08-10 `/claude-security` scan: `AuthenticationController.signin`
fast-fails with zero BCrypt work when the submitted email is unregistered, but pays a full BCrypt
comparison (tens of milliseconds, by design) whenever the email is registered — so response
*latency* enumerates registered accounts even though `D-08` already made the response *body*
byte-identical.

Purpose: make both failure branches pay the same dominant cost, and prove it with a test that was
watched fail first.
Output: an equalizing BCrypt comparison in the unknown-email branch, a RED-first regression test
that counts `PasswordEncoder.matches()` invocations per request through real Spring wiring, the
signin sequence diagram updated to show the branch, and the source todo closed out.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.claude/CLAUDE.md
@docs/CODE_STYLE.md
@.planning/todos/pending/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md
@src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
@src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
@src/main/java/com/vrudenko/kanban_board/config/BeanConfiguration.java
@src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java
@src/test/java/com/vrudenko/kanban_board/support/fixtures/AbstractAppMockMvcTest.java
</context>

<interface_context>

Facts already verified against the tree — do not re-derive them:

- `AuthenticationController` (`security/`) is `@RestController @RequiredArgsConstructor`, with
  three constructor-injected finals (`authenticationManager`, `securityContextRepository`,
  `sessionAuthenticationStrategy`), one already-initialised final
  (`securityContextHolderStrategy`, which Lombok therefore excludes from the constructor), and one
  `@Autowired private UserService userService;` field. Adding a fourth `final` field extends the
  Lombok-generated constructor; nothing else needs touching.
- `UserService.findByEmail(String)` is the only call in `signin`'s try block that can throw
  `com.vrudenko.kanban_board.exception.AppEntityNotFoundException`. `authenticate(...)` cannot:
  `loadUserByUsername` throws `UsernameNotFoundException`, and the Vavr `Try` inside `authenticate`
  collapses every failure to `false` anyway.
- The single BCrypt comparison on the registered-email path is
  `UserAuthenticationProvider.authenticate` line 29: `passwordEncoder.matches(plainPassword,
  userDetails.getPassword())`. It injects the same `PasswordEncoder` bean
  `BeanConfiguration.passwordEncoder()` publishes (`new BCryptPasswordEncoder()`, default strength
  10).
- `UserMapper` hashes on signup via `encode(...)`, never `matches(...)` — so counting `matches`
  alone cannot be polluted by fixture creation.
- `AbstractAppMockMvcTest` declares `mockMvc`, `objectMapper` and `cookieName` **private**; a
  subclass needing `MockMvc`/`ObjectMapper` autowires its own, exactly as `AuthenticationTest` does
  (its lines 85-87). Inherited protected helpers available: `getOwningUser()`,
  `getOwningUserPassword()`, `generateValidPassword()`, `dataFactory`.
- `AbstractAppTest`'s `@BeforeEach setup()` creates users/boards/columns/tasks through services
  directly; it never performs a signin, so no stray `matches()` call lands in the fixture phase.
- `LayeringArchTest`'s controller rule forbids controllers touching
  `com.vrudenko.kanban_board.repository..` only. Injecting `PasswordEncoder` into
  `AuthenticationController` does not trip it.
- `build.gradle`'s `fastTest` (the pre-commit gate) excludes by JUnit `@Tag` (`kafka`,
  `realSocket`). An untagged class is included by default.
- `docs/ARCHITECTURE.md` lines 50-82 hold the signin sequence diagram. It jumps straight from
  `C->>AC: POST /api/signin` to `AC->>AM: authenticate(...)` — the unknown-email branch is not
  drawn at all today.

</interface_context>

<design_rationale>

## Alternatives considered

**Approach A (picked) — equalize inside `AuthenticationController`'s unknown-email branch, against
a dummy hash derived at startup from the injected `PasswordEncoder`.**

**Approach B — move the mitigation into the authentication provider**, either by hardening
`UserAuthenticationProvider` or by replacing it with Spring's own `DaoAuthenticationProvider`,
which ships this exact mitigation built in (`mitigateAgainstTimingAttack` /
`userNotFoundEncodedPassword`).

**Approach C — keep Approach A's shape but use a hardcoded `$2a$10$…` literal** as the dummy hash
instead of computing one at startup.

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A — controller branch, startup-derived hash** | **+** Sits exactly where the short-circuit is, so the fix is visible at the site of the bug. **+** Work factor auto-tracks `BeanConfiguration`. **+** Single-file production diff on a security-critical path. **−** One BCrypt (~50-100 ms) burned once per application start. **−** Introduces a field written after construction. | **Picked.** Smallest diff that actually closes the dominant term, and the only one whose blast radius fits a quick task. |
| **B — provider-side / `DaoAuthenticationProvider`** | **+** Mitigation lives beside the only real `matches()` call, and Spring's version is battle-tested. **+** Would cover any future caller for free. **−** Unreachable without restructuring: `authenticationManager.authenticate` is *never called at all* when the email is unknown, because the controller resolves email→userId first and the principal Spring sees is the **userId**, not the email. Fixing it there means moving email resolution into the provider, which rewrites `loadUserByUsername`'s contract, the session-strategy call site, and signup's reuse of the same `authenticate` helper. | **Rejected for this task.** Architecturally the better home, but it is an auth-path rewrite, not a quick task, and every regression it could cause is a security regression. Recorded as a follow-up note in the resolution, not silently dropped. |
| **C — hardcoded hash literal** | **+** Zero startup cost, fully deterministic, no post-construction write. **−** Silently drifts: the day `BeanConfiguration` moves to `new BCryptPasswordEncoder(12)`, the dummy stays at cost 10 and a *new*, opposite-signed timing gap opens with every test still green. **−** A literal bcrypt hash in source reads like a leaked credential to future readers and secret scanners. | **Rejected.** The drift failure mode is exactly the silent-regression class this fix exists to remove. |

## Decisions this plan locks

- **D-01:** the equalizer hash is computed once at startup via the injected
  `passwordEncoder.encode(...)`, never written as a literal in source (Approach C rejected).
- **D-02:** the mitigation lives in `AuthenticationController.signin`'s unknown-email branch
  (Approach B rejected for this task, and named as a follow-up rather than dropped).
- **D-03:** the proof is a structural invocation-count test using a hand-written delegating
  `PasswordEncoder` bean, written and observed RED *before* the production change. The statistical
  wall-clock alternative the source todo offers is rejected: it is slow, environment-sensitive, and
  the flake it would introduce sits in the pre-commit gate.

## Non-obvious trade-offs

1. **The channel narrows; it does not provably close.** Even after the fix, the registered-email
   path performs one extra database round trip (`loadUserByUsername`'s `findById`) plus, on
   success only, session-strategy and session-write work. Those are sub-millisecond against
   BCrypt's tens of milliseconds, so the dominant, remotely-measurable term is gone — but this is
   a large-constant-factor reduction, not a formal constant-time guarantee, and the plan says so
   in the code comment rather than overclaiming.
2. **CPU cost profile of `/signin` changes.** Unknown-email requests stop being cheap: a sprayed
   list of random emails now costs a full BCrypt each instead of one indexed miss. That is the
   intended symmetry, and the registered-email path always cost this, but it does remove a
   cheap-rejection fast path in an application with no rate limiting today. Accepted, and recorded
   in the threat register rather than discovered later.
3. **Post-construction write to a singleton field.** The hash field is written once during
   `@PostConstruct`, which happens-before the servlet container accepts any request, and is
   read-only thereafter — so no synchronisation is needed, but the field cannot be `final` and a
   future reader must not "tidy" it into a constant (see D-01).
4. **The discarded `matches()` result is load-bearing.** Its return value is deliberately unused —
   the call exists for its cost, not its answer. HotSpot will not eliminate it (`BCrypt.checkpw`
   does real, non-inlinable work), but a future reader might, so the comment must say why it
   stays.
5. **One extra Spring context.** The counting-encoder `@TestConfiguration` gives the new test class
   its own context cache key. The Testcontainers PostgreSQL instance is JVM-static and shared, so
   the added cost is one context startup, not a second database — and keeping the config out of
   `AuthenticationTest` is what stops that widely-shared class from forking its own context too.

</design_rationale>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: RED — counting-encoder regression test, observed failing</name>
  <files>src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java</files>
  <precondition>Docker is running and can start containers — the test extends the Testcontainers-backed PostgreSQL base (`AbstractAppTest` -> `AbstractPostgresContainerTest`). Halt if `docker ps` fails.</precondition>
  <behavior>
    - Test 1 (`shouldInvokeMatchesExactlyOnce_whenEmailIsUnregistered`): a `POST /signin` with a
      well-formed but never-registered email returns 401 **and** drives exactly 1
      `PasswordEncoder.matches(...)` invocation. This test is expected to FAIL in this task
      (observed 0), and is the falsification evidence for the whole plan.
    - Test 2 (`shouldInvokeMatchesExactlyOnce_whenPasswordIsWrong`): a `POST /signin` with
      `getOwningUser()`'s real email and a deliberately wrong password returns 401 **and** drives
      exactly 1 `PasswordEncoder.matches(...)` invocation. This test is expected to PASS in this
      task — it is what proves the counting harness itself works, so that Test 1's failure is
      attributable to the production gap and not to a broken counter.
  </behavior>
  <action>
Create `SigninTimingEqualizationTest` in package `com.vrudenko.kanban_board.security`, annotated
`@SpringBootTest` + `@AutoConfigureMockMvc`, extending `AbstractAppMockMvcTest`. Give it its own
`@Autowired private MockMvc mockMvc;` and `@Autowired private ObjectMapper objectMapper;` — the
base class holds those privately (see interface_context). Do NOT add a `@Tag`: the class must land
in the pre-commit `fastTest` gate.

Per D-03, the invocation counter is a real Spring bean, not a stub. Inside the test class declare:

- A `static final class CountingPasswordEncoder implements PasswordEncoder` holding a
  `java.util.concurrent.atomic.AtomicInteger` plus the delegate. Every method
  (`encode`, `matches`, `upgradeEncoding`) forwards to the delegate and returns the delegate's real
  answer; only `matches` increments the counter first. Expose `matchesInvocationCount()` and
  `resetMatchesInvocationCount()`.
- A `@TestConfiguration static class` publishing
  `@Bean @Primary CountingPasswordEncoder countingPasswordEncoder(@Qualifier("passwordEncoder") PasswordEncoder delegate)`.
  Declare the bean method's return type as the concrete counting class so the test can autowire it
  and read the count; `@Primary` is what makes every `PasswordEncoder` injection point in this
  context — `UserAuthenticationProvider`'s included — resolve to it. The explicit
  `@Qualifier("passwordEncoder")` on the delegate parameter is required: it names
  `BeanConfiguration`'s bean directly instead of leaning on Spring's self-reference exclusion to
  resolve an otherwise-ambiguous `PasswordEncoder` parameter.
- `@Autowired private CountingPasswordEncoder countingPasswordEncoder;` on the test class.

Structure per `docs/CODE_STYLE.md` rule 5: one `@Nested class Signin` holding both tests, method
names in the plain `should<Outcome>_when<Condition>` dialect (matching the neighbouring
`AuthenticationTest.Signin.AntiEnumeration` group, whose cases are likewise unauthenticated — not
the `testWithAuthenticatedUser_` controller dialect), and `// arrange` / `// act` / `// assert`
section comments in every body. Assertions are `Assertions.assertThat(...)` fully qualified against
`import org.assertj.core.api.Assertions;` (rule 3). Apply rule 9 to locals: `var` only where the
right-hand side already names the type (`SigninRequestDTO.builder()...build()` qualifies; a helper
call whose return type is not obvious does not).

Each test calls `countingPasswordEncoder.resetMatchesInvocationCount()` as the last arrange step —
after `AbstractAppTest`'s fixture `@BeforeEach` has run — then performs the POST through
`mockMvc.perform(post(ApiPaths.SIGNIN)...)` with an `objectMapper`-serialised `SigninRequestDTO`
body (bare `ApiPaths` constant, no context-path prefix — rule 4's MockMvc tier note). Assert the
401 status first and the invocation count second, so a request that dies at validation reports as a
status failure rather than a confusing count failure. Build the unregistered email as a fixed
literal prefix plus `UUID.randomUUID()` so it can never collide with a fixture user, mirroring
`AuthenticationTest.collisionProofEmail()`; build the wrong password by concatenating a suffix onto
`getOwningUserPassword()`, mirroring that class's existing wrong-password case.

Write a class Javadoc covering four things: (1) that this proves the *cost* half of the
anti-enumeration guarantee, while `AuthenticationTest.Signin.AntiEnumeration` proves the *content*
half — neither supersedes the other; (2) why the counting delegate does not violate
`docs/CODE_STYLE.md` rule 4 — nothing is stubbed, the real encoder still runs and still returns its
real answer, and the delegate is wired through the real Spring context, so no branch under test is
bypassed; (3) why the class is separate from `AuthenticationTest` rather than a new nested group
inside it — the `@TestConfiguration` forks a Spring context cache key, and isolating it keeps that
fork off the far more widely-shared `AuthenticationTest`; (4) that the counter is per-context
mutable state reset per test, and JUnit runs sequentially here (no `junit-platform.properties`
declares parallelism), which is what makes a shared counter deterministic.

Run the test and record the actual output. Do not create, edit, or even open
`AuthenticationController` in this task — the failure is the deliverable.

Commit as `test(260811-ezy): add failing signin BCrypt-cost equalization test`.
  </action>
  <verify>
    <automated>./gradlew test --tests '*SigninTimingEqualizationTest*'</automated>
    <automated>./gradlew spotlessCheck</automated>
  </verify>
  <done>
The gradle run exits non-zero with exactly one failure, and that failure is
`shouldInvokeMatchesExactlyOnce_whenEmailIsUnregistered` reporting an expected-1/actual-0
invocation count (NOT an error in context startup, bean resolution, a 400/404 status assertion, or
a compilation failure — any of those means the harness is wrong, not the production code, and must
be fixed before moving on). `shouldInvokeMatchesExactlyOnce_whenPasswordIsWrong` passes in the same
run. The observed failure text is copied verbatim into the summary as the plan's falsification
evidence. `spotlessCheck` is green.
  </done>
</task>

<task type="auto">
  <name>Task 2: GREEN — equalize BCrypt cost on the unknown-email branch</name>
  <files>src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java</files>
  <action>
Add `private final PasswordEncoder passwordEncoder;` alongside the existing constructor-injected
finals (Lombok's `@RequiredArgsConstructor` extends the generated constructor; the initialised
`securityContextHolderStrategy` stays excluded automatically).

Per D-01, add a `private static final String` holding the fixed plaintext used only to produce the
equalizer hash, a non-final `private String` field for the hash itself, and a
`@PostConstruct`-annotated private method (`jakarta.annotation.PostConstruct`) that assigns
`passwordEncoder.encode(<that constant>)`. Deriving the hash from the injected bean is what makes
the equalizing comparison's work factor track whatever strength `BeanConfiguration` configures,
instead of freezing today's cost into a source literal. Document on the field that it is written
once during container initialisation — which happens-before any request is served — and is
read-only thereafter, so it needs no synchronisation and must not be "tidied" into a constant.

Per D-02, restructure `signin` into two sequential try blocks rather than one:

1. The first wraps only `userService.findByEmail(dto.getEmail())`, assigning to an explicitly typed
   `UserEntity user;` declared just above it (rule 9: a service call's return type is not obvious
   from the right-hand side). Its `catch` clause is narrowed to `AppEntityNotFoundException`
   (`com.vrudenko.kanban_board.exception.AppEntityNotFoundException`). Inside that catch, invoke
   `passwordEncoder.matches(dto.getPassword(), <the equalizer hash field>)` and discard the result,
   then throw the same `BadCredentialsException` this branch throws today.
2. The second keeps the existing `authenticate(...)` call and its `!successfullyAuthenticated`
   guard, still under a blanket `catch (Exception e)` that throws `BadCredentialsException` —
   behaviour there is unchanged. Narrowing the first catch is what guarantees the extra comparison
   fires only on the unknown-email branch and never doubles up on a branch that already paid one.

Extract the repeated failure message into a `private static final String` and use it at all three
throw sites in this file (both signin sites and signup's). The entire D-08 guarantee is that these
responses are indistinguishable; one authoritative string is what keeps them so.

Comment the equalizing call with: the finding id it closes; that the return value is ignored on
purpose because the call is there for its cost, not its answer, so it must not be removed as dead
code; and the honest residual — the registered-email path still performs one extra database round
trip (`loadUserByUsername`), which is sub-millisecond against BCrypt's tens of milliseconds, so
this narrows the channel by a large constant factor rather than making the endpoint provably
constant-time.

If Error Prone rejects the ignored return value at `compileJava` (both main and test compilation
are hard-gated in this repo), assign it to a named local whose name says it is intentionally
unused, and keep the explanatory comment; do not silence the check with a suppression.

Run `./gradlew spotlessApply` before the gate. Commit as
`fix(260811-ezy): equalize signin BCrypt cost on unknown-email branch`.
  </action>
  <verify>
    <automated>./gradlew spotlessCheck test</automated>
  </verify>
  <done>
`./gradlew spotlessCheck test` is green end to end. Within it: both
`SigninTimingEqualizationTest` cases now pass (the previously-RED unregistered-email case reports 1
invocation), and `AuthenticationTest.Signin.AntiEnumeration`'s two cases —
`shouldReturnUnauthorizedWithBadCredentialsCode_whenEmailIsWellFormedButUnregistered` and
`shouldReturnByteIdenticalBody_whenComparingUnregisteredEmailAndWrongPasswordSignins` — pass with
zero edits to that file. `git diff --stat` for this task shows `AuthenticationController.java` as
the only production file touched. The summary records the total suite counts (tests run / failures)
and the wall-clock time against the ~210-test, ~5-minute baseline in STATE.md.
  </done>
</task>

<task type="auto">
  <name>Task 3: Close the todo and draw the branch into the signin diagram</name>
  <files>docs/ARCHITECTURE.md, .planning/todos/pending/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md, .planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md</files>
  <action>
**`docs/ARCHITECTURE.md`** — the signin sequence diagram (currently lines ~50-82) jumps straight
from `C->>AC: POST /api/signin` to `AC->>AM: authenticate(...)`, so the unknown-email branch it now
matters to is invisible. Add that branch: an `AC->>DB`-style lookup step for
`userService.findByEmail`, then an `alt`/`else` where the email-not-found arm shows the equalizing
`passwordEncoder.matches` against the startup-derived hash before the same
`401 ProblemDetail {code: BAD_CREDENTIALS}` reply, and the found arm continues into the existing
`AC->>AM` flow unchanged. Add a `Note` stating that the comparison exists to make both arms pay one
BCrypt, and that this closes the latency signal `D-08` left open after equalizing the response
body. Keep the existing participants and the rest of the diagram intact; if the DB participant name
needs widening beyond `Postgres (spring_session*)`, adjust the label rather than adding a second
database participant. Follow `docs/DIAGRAM_CONVENTIONS.md` — this stays a Scenarios-view sequence
diagram. Update the "Simplified:" paragraph below the diagram only if the change makes one of its
statements inaccurate.

**Todo closure** — `git mv` the pending todo to `.planning/todos/completed/` under the identical
filename (the convention every file in that directory follows). Add `resolved: 2026-08-11` to its
front matter, matching the sibling entries' key placement. Leave the Problem and "Why this is
deferred" sections untouched as the historical record, and append a `## Resolution` section
covering: what shipped (the equalizing comparison, the startup-derived hash) and where; that the
test was observed RED before the fix and the verbatim failure it produced; the residual asymmetry
from the extra `loadUserByUsername` round trip, stated as narrowed-not-closed; the accepted change
in CPU cost profile for unknown-email requests on an endpoint with no rate limiting; and the
Approach B follow-up — that `DaoAuthenticationProvider`/provider-side equalization remains the
architecturally better home and was rejected here for blast radius, not for correctness. Also
restate the finding's independence from `T-07.1-04-02`/`D-07` (signup's 409 body), so a future
reader does not re-merge the two.

Do not edit `.claude/CLAUDE.md` or `README.md`: neither carries a claim this change falsifies —
CLAUDE.md's error-handling section describes the 401/403/400/409 split, which is unchanged.

Commit as `docs(260811-ezy): document signin cost equalization and close the F1 todo`.
  </action>
  <verify>
    <automated>test ! -f .planning/todos/pending/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md && test -f .planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md && echo TODO_MOVED</automated>
    <automated>grep -c '^## Resolution' .planning/todos/completed/2026-08-10-signin-timing-side-channel-allows-email-enumeration.md</automated>
    <automated>grep -v '^\s*[*/]' src/test/java/com/vrudenko/kanban_board/security/SigninTimingEqualizationTest.java | grep -ciE 'mockito|MockBean|@Mock\b' || true</automated>
    <automated>./gradlew spotlessCheck</automated>
  </verify>
  <done>
`TODO_MOVED` prints (the todo is gone from `pending/` and present in `completed/` under the same
name, with `resolved: 2026-08-11` in its front matter); the Resolution-heading grep returns 1; the
Mockito grep — run with comment lines filtered out, so the Javadoc's own explanation of why this is
not a mock cannot self-invalidate the gate — returns 0; `spotlessCheck` is green. The updated
Mermaid block renders (no syntax error) and shows both signin arms, with the equalizing comparison
drawn on the email-not-found arm.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| unauthenticated client -> `POST /api/signin` | Attacker-chosen email and password cross here with no prior authentication, and the response — status, body, **and elapsed time** — crosses back. Response *time* is the channel this plan addresses; the body was already equalized by D-08. |
| `AuthenticationController` -> `UserService`/`UserAuthenticationProvider` | An attacker-supplied email decides which internal path runs, and therefore how much work the request costs. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-260811-ezy-01 | Information Disclosure | `AuthenticationController.signin` unknown-email branch | medium | mitigate | The finding itself (F1). Task 2 adds a BCrypt comparison against a startup-derived dummy hash on the unknown-email branch, so both failure arms pay the same dominant cost. Task 1 proves it structurally, RED first. |
| T-260811-ezy-02 | Denial of Service | `POST /api/signin` | low | accept | Unknown-email requests stop being cheap and now cost one full BCrypt (~50-100 ms CPU) each. This is the intended symmetry and the registered-email path always cost exactly this, so the mitigation does not raise the ceiling — it removes a cheap-rejection floor. No rate limiting exists on this endpoint today in either case; recorded in the todo's Resolution so a future rate-limiting decision inherits the context rather than rediscovering it. |
| T-260811-ezy-03 | Information Disclosure | `UserService.loadUserByUsername` round trip | low | accept | Residual after T-01: the registered-email path still performs one extra indexed database read the unknown-email path does not. Sub-millisecond against BCrypt's tens of milliseconds, and swamped by network jitter over any real link. Documented in code and in the Resolution as narrowed-not-closed rather than silently implied to be zero. |
| T-260811-ezy-04 | Tampering | equalizer hash work factor | medium | mitigate | A dummy hash cheaper (or dearer) than the production encoder's strength reopens the channel with the opposite sign and every test still green. D-01 mitigates structurally: the hash is produced by the injected `PasswordEncoder` bean itself, so it cannot drift from `BeanConfiguration`. This is why the hardcoded-literal alternative (Approach C) was rejected. |
| T-260811-ezy-05 | Spoofing | `@Primary` counting encoder in test scope | low | mitigate | A test-only bean overriding the production `PasswordEncoder` must never leak into production wiring. It is declared in a `@TestConfiguration` nested inside a single test class under `src/test`, never a `@Component` in a scanned package — so it is visible only to that class's context. Its `matches` also returns the real delegate's real answer, so no test can pass on a fabricated credential check. |
| T-260811-ezy-SC | Tampering | npm/pip/cargo/Gradle installs | n/a | n/a | No package-manager install occurs in this plan — zero new dependencies, zero `build.gradle` changes. The package-legitimacy gate is not applicable. |
</threat_model>

<verification>
1. `./gradlew spotlessCheck` — green (the project's format gate).
2. `./gradlew test` — green, full suite, with test count reported against STATE.md's ~210-test
   baseline so silent shrinkage is visible.
3. The RED-then-GREEN transition of `shouldInvokeMatchesExactlyOnce_whenEmailIsUnregistered` is
   evidenced by the verbatim Task 1 failure output quoted in the summary — this is the plan's
   proof that the new test has teeth, and no substitute assertion counts.
4. `AuthenticationTest.Signin.AntiEnumeration` passes with `git diff` showing zero changes to
   `AuthenticationTest.java`.
5. `git diff --stat` across the three commits shows exactly one production file touched
   (`AuthenticationController.java`) plus one new test file and two docs/planning files.
</verification>

<success_criteria>
- Unregistered-email and wrong-password signins each drive exactly one BCrypt comparison, proven
  by a test that was watched fail before the fix existed.
- The 401 ProblemDetail body remains byte-identical between the two cases, with
  `AuthenticationTest`'s assertions unedited.
- The equalizer hash derives from the application's own `PasswordEncoder` bean, so a future
  strength change cannot silently reopen the channel.
- No mock framework is introduced; the counter is real Spring wiring around the real encoder.
- The new test runs in the pre-commit `fastTest` gate.
- `./gradlew spotlessCheck` and `./gradlew test` both pass.
- The signin sequence diagram shows the unknown-email branch and its equalizing comparison.
- The source todo is in `.planning/todos/completed/` with a Resolution recording what shipped, the
  residual asymmetry, the DoS trade-off, and the provider-side follow-up.
</success_criteria>

<output>
Create `.planning/quick/260811-ezy-fix-signin-timing-side-channel-f1-consta/260811-ezy-SUMMARY.md` when done.
</output>
