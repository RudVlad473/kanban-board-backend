---
task_id: 260802-shl
type: quick
description: Fix the dead Spring Session JDBC configuration
created: 2026-08-02
source_context: .planning/quick/260802-shl-fix-the-dead-spring-session-jdbc-configu/260802-shl-CONTEXT.md
source_research: .planning/quick/260802-ryf-enable-virtual-threads-in-spring-boot-co/260802-ryf-RESEARCH.md
autonomous: true
files_modified:
  - build.gradle
  - src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java
  - src/main/resources/application.properties
  - src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
  - .claude/CLAUDE.md
  - ".planning/todos/pending/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md"

estimate:
  tokens: 55000
  raw_tokens: 36000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "`org.springframework.session:spring-session-jdbc` is on the runtime classpath, so the three pre-existing `spring.session.*` properties stop being inert (D-01)."
    - "The `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` tables are proven to be CREATED by a test, not merely assumed because no test touches sessions."
    - "An authenticated session is proven to land in the JDBC store — a signin adds a real row, so a restart no longer discards logins."
    - "The persisted SecurityContext is proven NOT to carry the bcrypt password hash — the guard `UserAuthenticationProvider` claims is now enforceable for the first time."
    - "No manual DDL script and no new pre-merge production step is introduced (D-02); `initialize-schema=always` does the work by itself."
    - "Every remaining documentation claim about session behaviour is TRUE after the change — including the concurrent-session claim, which this plan discovers is independently false."
    - "`./gradlew spotlessCheck` and `./gradlew test` are both green."
  artifacts:
    - "build.gradle declares spring-session-jdbc with no hand-pinned version (Spring Boot 3.5.0 BOM resolves it)"
    - "src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java exists and asserts against real JDBC rows"
    - "application.properties carries a comment recording the REAL idempotency mechanism behind initialize-schema=always"
    - ".planning/todos/pending/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md exists"
  key_links:
    - "Spring Session's `SessionRepositoryFilter` (order Integer.MIN_VALUE + 50) wraps the request BEFORE springSecurityFilterChain (order -100). That ordering is the entire reason the untouched `HttpSessionSecurityContextRepository` in SecurityConfiguration:38 starts writing to JDBC without a code change."
    - "`spring.session.timeout=180m` only takes precedence over `server.servlet.session.timeout=1m` once Spring Session is present. Adding the dependency silently changes the effective idle timeout from 1 minute to 180 minutes."
    - "The JSESSIONID cookie value is Base64-encoded by Spring Session's DefaultCookieSerializer, so it does NOT equal the SESSION_ID column. A test comparing them raw fails for the wrong reason."
    - "SPRING_SESSION rows have no FK to users, so AbstractAppTest's @AfterEach userService.deleteAll() never clears them. Assertions must use count DELTAS, not absolute counts."
---

<objective>
Make the Spring Session JDBC configuration real. `application.properties` has set
`spring.session.store-type=jdbc` and `spring.session.jdbc.initialize-schema=always` for a long time,
and both `.claude/CLAUDE.md` and `UserAuthenticationProvider.java:35` describe sessions as persisted
to a PostgreSQL `spring_session` table — but `org.springframework.session` is absent from the
runtime classpath, so the properties are inert and sessions are Tomcat's in-memory `HttpSession`.
Every login is lost on each EC2 redeploy, and `master` auto-deploys on every push.

Locked decisions carried from CONTEXT.md:

- **D-01 (Fix direction):** wire up `spring-session-jdbc` for real. Do NOT remove the properties and
  downgrade the docs to match the bug.
- **D-02 (Schema creation strategy):** rely on the already-set
  `spring.session.jdbc.initialize-schema=always`. Do NOT add a third manual DDL script alongside
  `02-optimistic-locking-ddl.sql` / `03-activity-log-ddl.sql`, and do NOT set
  `initialize-schema=never`. No new manual pre-merge production step.

Purpose: session loss on every redeploy is a real user-facing regression, and two documents assert
JDBC-backed sessions as fact today. This closes the gap in the direction that makes the docs true.

Output: the dependency wired, the behaviour proven by test (not assumed), and every session-related
documentation claim verified true — including one that this plan discovers is independently false.
</objective>

<data_flow>
## Core mechanism, in three sentences

Adding `spring-session-jdbc` registers `SessionRepositoryFilter` at order `Integer.MIN_VALUE + 50`,
ahead of `springSecurityFilterChain` at order `-100`, so it wraps the `HttpServletRequest` before any
security filter runs and `request.getSession()` returns a `JdbcIndexedSessionRepository`-backed
session instead of Tomcat's. `AuthenticationController.authenticate()` already calls
`securityContextRepository.saveContext(context, request, response)`, and the untouched
`HttpSessionSecurityContextRepository` at `SecurityConfiguration:38` writes that context into
`request.getSession()` — which is now the JDBC-backed one — so the `SecurityContext` is serialized
into `SPRING_SESSION_ATTRIBUTES` when the filter commits the session at end of request. On the next
request the same filter reads the row back by cookie id and rehydrates the session, which is why a
restart (or a second instance) no longer loses the login.
</data_flow>

<approaches_considered>
## Alternate approaches and trade-off matrix

The project directives require documenting alternates even when the direction is locked. D-01 and
D-02 are locked, so this matrix records WHY the rejected options lose rather than reopening them.

| Approach | Pros / Cons | Why Picked |
|----------|-------------|------------|
| **A. Add `spring-session-jdbc`, rely on `initialize-schema=always`** (CHOSEN) | **Pros:** makes three existing properties, the 180m timeout, and two documents true at once; zero manual steps; survives redeploy and horizontal scaling; smallest diff (one dependency line). **Cons:** adds a DB write on session change; changes the effective idle timeout 1m → 180m; benign "relation already exists" noise in the Postgres log on every restart after the first. | Locked by D-01 + D-02. It is also genuinely the smallest change that removes the contradiction rather than papering over it. |
| **B. Delete the three properties, document in-memory sessions as the real behaviour** | **Pros:** zero new dependency, zero new DB traffic, no timeout change, trivially safe. **Cons:** accepts session loss on every EC2 redeploy as permanent; permanently forecloses horizontal scaling; makes the 180m timeout setting meaningless; downgrades docs to match a bug rather than fixing it. | Rejected by D-01. The docs, the 180m timeout, and the property block all show JDBC sessions were the intent — the classpath gap is the defect, not the config. |
| **C. Add the dependency, set `initialize-schema=never`, ship a third manual DDL script** | **Pros:** consistent with the two existing manual DDL scripts; schema changes become explicit and reviewable; no startup log noise. **Cons:** adds a THIRD manual pre-merge production gate to a project that already has two outstanding ones — the exact failure mode a forgotten manual step causes is a production 500 on first login. | Rejected by D-02, and independently: the two existing scripts exist only because Hibernate's `ddl-auto` is unset in prod. Spring Session ships its own initializer, so borrowing that constraint here buys nothing and costs a gate. |
</approaches_considered>

<research_corrections>
## Two premises checked during planning — one is WRONG, one is new

**1. CONTEXT.md's stated idempotency mechanism is factually wrong (outcome still holds).**

CONTEXT.md D-02 justifies `initialize-schema=always` by claiming *"Spring Session JDBC's own schema
script is idempotent (`CREATE TABLE IF NOT EXISTS`)"*. Verified against upstream
`spring-session/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/`: **neither
`schema-postgresql.sql` nor `schema-h2.sql` uses a conditional-existence clause.** Every statement is
a bare `CREATE TABLE` / `CREATE INDEX` that fails outright if the object already exists.

The decision's *outcome* survives, for a different reason. Spring Boot's
`JdbcSessionDataSourceScriptDatabaseInitializer.getSettings()` calls `settings.setContinueOnError(true)`,
so the failing `CREATE TABLE` on the second and later startups is swallowed and the app continues.
D-02 needs no revision — but the real mechanism must be recorded, because it has a visible
consequence D-02 did not anticipate: **every production restart after the first logs "relation
already exists" errors from the session initializer.** These are benign by design. Undocumented, they
are exactly the kind of thing a future operator reads as a failed deploy.

**2. `maximumSessions` and `sessionFixation` are dead config too — the same class of defect.**

`SecurityConfiguration:63-66` configures `maximumSessions(2).maxSessionsPreventsLogin(true)` and
`sessionFixation(newSession)`. Both are applied by a `SessionAuthenticationStrategy`, which runs
inside an authentication filter. This application has no `UsernamePasswordAuthenticationFilter` — it
authenticates through the custom `AuthenticationController.signin`, which calls
`authenticationManager.authenticate(token)` directly and then `securityContextRepository.saveContext(...)`.
Spring Security 6 no longer adds `SessionManagementFilter` to the default chain. **Nothing on this
path ever invokes the strategy, so no session is ever registered and no session id is ever rotated
on login.**

This is not caused by the change in this plan and its fix is out of scope. It matters here for one
reason: `.claude/CLAUDE.md:307` asserts *"Maximum 2 concurrent sessions per user,
maxSessionsPreventsLogin=true"* as fact, in the same block this plan is correcting. Fixing one false
session claim while leaving its neighbour standing would defeat the point. Task 2 proves it with a
tripwire test and Task 3 corrects the wording; the real fix is filed as a todo.
</research_corrections>

<context>
@.planning/STATE.md
@.planning/quick/260802-shl-fix-the-dead-spring-session-jdbc-configu/260802-shl-CONTEXT.md
@docs/CODE_STYLE.md
@src/main/resources/application.properties
@src/main/resources/application-test.properties
@src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
@src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
@src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
@src/test/java/com/vrudenko/kanban_board/AbstractAppTest.java
@src/test/java/com/vrudenko/kanban_board/AbstractAppE2ETest.java
</context>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Wire spring-session-jdbc and prove the JDBC store actually does the work</name>
  <files>build.gradle, src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java</files>
  <precondition>`./gradlew test` is green on a clean checkout before any edit — the suite currently passes with the three `spring.session.*` properties set and the dependency absent, and that green baseline is what Task 1 must preserve.</precondition>
  <read_first>
    - `src/test/java/com/vrudenko/kanban_board/AbstractAppE2ETest.java` — `signin()` returns `Pair.of(COOKIE_NAME, cookieValue)`; subclasses carry `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` (see `TaskLockingE2ETest` for the exact annotation).
    - `src/test/java/com/vrudenko/kanban_board/AbstractAppTest.java` — `@BeforeEach` creates a fresh random user per test method; `@AfterEach` runs `userService.deleteAll()`.
    - `docs/CODE_STYLE.md` rules 3, 4, 5 — qualified `Assertions.assertThat`, no mocks, `@Nested` + `should&lt;Outcome&gt;_when&lt;Condition&gt;` + `// arrange` / `// act` / `// assert`.
  </read_first>
  <behavior>
    - Test 1: the `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES` tables exist and are queryable. This is the assertion that distinguishes "schema was created" from "no test happened to touch sessions" — without it the suite passes either way, which is precisely how this defect survived.
    - Test 2: a signin increases the `SPRING_SESSION` row count by exactly 1 and yields a non-null session cookie. Proves the authenticated session is in the database, not in Tomcat memory.
    - Test 3: a signin adds exactly one `SPRING_SESSION_ATTRIBUTES` row whose `ATTRIBUTE_NAME` is `SPRING_SECURITY_CONTEXT`. Proves it is the SecurityContext being persisted, which is what `UserAuthenticationProvider`'s comment asserts.
    - Test 4: the persisted attribute bytes contain no bcrypt hash marker. `UserAuthenticationProvider` deliberately builds a minimal principal to keep `passwordHash` out of the store; until this dependency existed nothing was persisted, so that guard has never been verifiable. This makes it a real regression test.
  </behavior>
  <action>
Add `implementation 'org.springframework.session:spring-session-jdbc'` to the `dependencies` block in
`build.gradle`, grouped with the other Spring Boot starters. Declare no version — the Spring Boot
3.5.0 plugin's dependency management resolves it from the Spring Session BOM (D-01, Claude's
Discretion on coordinate/version: prefer BOM resolution over a hand-pinned version unless a
compatibility failure forces one). Add a brief comment above it recording that the `spring.session.*`
properties predate this dependency and were inert without it, so a future reader does not delete the
dependency as unused — nothing in `src/main` imports `org.springframework.session`, which is exactly
what let it go missing.

Create `SessionPersistenceE2ETest` in `src/test/java/com/vrudenko/kanban_board/security/`, annotated
`@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` and extending
`AbstractAppE2ETest`. Autowire a `JdbcTemplate`. Follow docs/CODE_STYLE.md rules 3, 4 and 5 exactly:
qualified `Assertions.assertThat`, real Spring wiring with no mocks, `@Nested` grouping, and
`// arrange` / `// act` / `// assert` section comments.

Implement the four behaviours above. Four constraints govern how the assertions must be written —
each corresponds to a way a naively-written version of this test fails or silently passes:

1. **Assert count DELTAS, never absolute counts.** `SPRING_SESSION` rows carry no foreign key to
   users, so `AbstractAppTest`'s `@AfterEach userService.deleteAll()` never removes them and rows
   accumulate across the whole suite run in the shared H2 context. Capture the count before signin
   and assert the difference. JUnit runs sequentially here (no `junit-platform.properties` declares
   parallelism), so a delta is deterministic.
2. **Never compare the cookie value to the `SESSION_ID` column.** Spring Session's
   `DefaultCookieSerializer` Base64-encodes the session id into the cookie by default, so the two
   are not equal and an equality assertion fails for a reason that has nothing to do with the fix.
   Identify the new row by delta or by ordering, not by matching the cookie.
3. **Query the tables in upper case.** H2 upper-cases unquoted identifiers and Spring Session's
   schema script creates them unquoted.
4. **For behaviour 4, read the `ATTRIBUTE_BYTES` column and search its decoded ISO-8859-1 text for
   the bcrypt hash prefix marker**, asserting absence. Java serialization writes String fields as
   modified UTF-8, so an ASCII hash would appear verbatim in those bytes if the full `UserEntity`
   were ever persisted as the principal. Derive the marker as a constant in the test rather than
   fetching the user's real hash, so the test does not depend on the repository's row shape.

Do not modify `SecurityConfiguration`. `HttpSessionSecurityContextRepository` at line 38 stays
correct and unchanged: `SessionRepositoryFilter` registers at order `Integer.MIN_VALUE + 50`, ahead
of `springSecurityFilterChain` at `-100`, so the `request.getSession()` it writes to is already the
JDBC-backed session. Task 2 records this review conclusion; the "review/replace" note in CONTEXT.md's
canonical references resolves to "keep as-is, verified".

Do not touch `application-test.properties`. Its `spring.session.*` block already mirrors production
and Spring Session resolves `schema-h2.sql` for the H2 driver automatically. Hibernate's
`ddl-auto=create-drop` is not a conflict: Spring Boot makes the `EntityManagerFactory` depend on the
database initializer, so the session schema is created first, and `create-drop` only drops
Hibernate-mapped tables at shutdown.
  </action>
  <verify>
    <automated>./gradlew test --tests '*SessionPersistenceE2ETest*'</automated>
  </verify>
  <done>`SessionPersistenceE2ETest` passes with all four behaviours asserted. The session tables are proven created, a signin is proven to add a real `SPRING_SESSION` row plus a `SPRING_SECURITY_CONTEXT` attribute row, and the persisted context is proven free of the bcrypt hash marker. `build.gradle` carries the dependency with no hand-pinned version. `SecurityConfiguration.java` and `application-test.properties` are unmodified.</done>
  <reversibility rating="reversible">A single dependency line plus one new test file; reverting restores the prior in-memory behaviour exactly. The `spring.session.*` properties are already present either way.</reversibility>
</task>

<task type="auto">
  <name>Task 2: Sanity-check the behaviours the store switch touches, and file what it cannot fix</name>
  <files>src/test/java/com/vrudenko/kanban_board/security/SessionPersistenceE2ETest.java, .planning/todos/pending/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md</files>
  <read_first>
    - `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java` — `signin` calls `authenticationManager.authenticate(token)` directly, then `securityContextRepository.saveContext(...)`. No authentication filter is involved.
    - `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java:61-67` — the `sessionManagement` block whose settings this task characterises.
    - The `<research_corrections>` section of this plan, finding 2.
  </read_first>
  <action>
CONTEXT.md's Claude's-Discretion list asks whether the max-2-concurrent-sessions and
`maxSessionsPreventsLogin` configuration interacts with the store-type switch. It does not — because
that configuration is not in force at all, for the same class of reason the session properties were
not. Confirm and pin this rather than reasoning about it once and moving on.

Add a `@Nested` group to `SessionPersistenceE2ETest` containing a single tripwire test. Sign in three
times as the same fixture user, assert all three return a non-null cookie, assert the three cookie
values are distinct, and assert the `SPRING_SESSION` row count increased by exactly 3 — three live
sessions for one principal, one more than the configured ceiling of two. Name it so the name states
what it documents, for example
`shouldAllowThreeConcurrentSessions_whenMaxSessionsIsConfiguredButNoAuthenticationStrategyRuns`.

Give that test a Javadoc explaining that it characterises current behaviour rather than endorsing it:
the ceiling is enforced by `ConcurrentSessionControlAuthenticationStrategy`, which runs inside an
authentication filter; this application authenticates through `AuthenticationController` instead, and
Spring Security 6 no longer installs `SessionManagementFilter` by default, so the strategy is never
invoked. Point the Javadoc at the todo file below. The value of the test is that it goes RED the day
someone wires the strategy correctly, forcing the documentation and the test to be updated together
instead of drifting apart again — which is the failure mode this whole task exists to correct.

Also record, as a comment in the same test class, the review conclusion for
`SecurityConfiguration:38`: `HttpSessionSecurityContextRepository` was reviewed and deliberately kept,
because `SessionRepositoryFilter` wraps the request ahead of the security chain and the repository
therefore writes to the JDBC-backed session with no code change. This closes the
"reviewed/replaced" question CONTEXT.md raised, and stops a future reader re-opening it.

Create `.planning/todos/pending/2026-08-02-wire-session-authentication-strategy-into-custom-signin.md`
following the shape of the existing files in that directory. It should record: that
`maximumSessions(2).maxSessionsPreventsLogin(true)` and `sessionFixation(newSession)` are both
configured but never applied on the custom signin path; that the concurrent-session ceiling is
therefore unenforced; that the session id is not rotated on login, which leaves a session-fixation
exposure (threat `T-shl-01` in this plan's register); the likely fix shape (invoke a
`SessionAuthenticationStrategy` from `AuthenticationController.authenticate`, or move signin onto a
real authentication filter); and that `SessionPersistenceE2ETest`'s tripwire test must be updated as
part of that fix. Mark it security-relevant so it is not triaged as cosmetic.

Do not attempt the strategy fix here. It changes the authentication path for every request in the
application, needs its own test coverage for signin, signup and logout, and is far outside a quick
task's blast radius. The finding is what this task delivers.
  </action>
  <verify>
    <automated>./gradlew test --tests '*SessionPersistenceE2ETest*'</automated>
  </verify>
  <done>The tripwire test passes and carries a Javadoc explaining what it characterises and why. The `SecurityConfiguration:38` review conclusion is recorded in the test class. The todo file exists in `.planning/todos/pending/`, names the unenforced ceiling and the session-fixation exposure, and points back at the tripwire test. `SecurityConfiguration.java` and `AuthenticationController.java` are still unmodified.</done>
  <reversibility rating="reversible">Test-only plus one planning artifact; no production code changes.</reversibility>
</task>

<task type="auto">
  <name>Task 3: Correct every documentation claim so all of them are true</name>
  <files>.claude/CLAUDE.md, src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java, src/main/resources/application.properties</files>
  <read_first>
    - `.claude/CLAUDE.md` lines 305-308 (the State Management block) and line 358 (the Session persistence constraint).
    - `src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java` lines 33-37.
    - This plan's `<research_corrections>` section — both findings feed this task.
  </read_first>
  <action>
Bring every session-related claim in the documentation into agreement with what the code now does.
Verify each claim against the code as you go rather than assuming the pre-existing wording became
true the moment the dependency landed (CONTEXT.md's Claude's-Discretion note asks for exactly this).
Four of them need work:

**`.claude/CLAUDE.md:305`** — "SecurityContext stored in PostgreSQL via spring_session table
(application.properties line 23-24)". Now true in substance, but imprecise in two ways worth fixing
while here: the SecurityContext is stored in `spring_session_attributes`, not `spring_session` itself
(`spring_session` holds session metadata; the attribute rows hold the serialized values), and the
line-number reference will rot. Prefer naming the properties over citing line numbers.

**`.claude/CLAUDE.md:307`** — "Maximum 2 concurrent sessions per user, maxSessionsPreventsLogin=true
(line 63)". This asserts as fact something Task 2 proves false. Reword it to state what is actually
true: the configuration is present in `SecurityConfiguration` but is not applied, because the custom
`AuthenticationController` signin path never invokes a `SessionAuthenticationStrategy`. Reference the
todo filed in Task 2 so the entry has somewhere to go.

**`.claude/CLAUDE.md:308`** — "Session timeout: 1 minute at servlet level, 180 minutes at Spring
Session level". The two numbers are right but the framing implies both are in force. State the
effective outcome: `spring.session.timeout` takes precedence once Spring Session is present, so the
server-side idle timeout is 180 minutes — this is a real behaviour change introduced by this task,
since the effective timeout was 1 minute before. Note alongside it that
`server.servlet.session.cookie.max-age=600` caps the cookie itself at 10 minutes regardless, so the
client-side lifetime and the server-side lifetime differ by design. Do not change either value; this
is a documentation-accuracy fix, not a tuning change.

**`.claude/CLAUDE.md:358`** — "All sessions stored in PostgreSQL spring_session table, not in memory.
Allows horizontal scaling." The first sentence is now true. The second needs a qualifier rather than
deletion: session state does now survive a restart and is shared across instances, but the
concurrent-session ceiling is tracked per-instance and unenforced (see line 307's correction), so
"allows horizontal scaling" should not be read as "every session-related guarantee holds across
instances".

**`UserAuthenticationProvider.java:33-37`** — the comment is now describing something real rather
than something aspirational, and its central claim (do not put the full `UserEntity` in the
`Authentication`, because it gets serialized into the session store) is correct and is now enforced
by Task 1's fourth assertion. Two details need tightening: the serialized context lands in
`spring_session_attributes`, and Spring Session writes on session change rather than on every
request, so "on every authenticated request" overstates the frequency. Keep the rationale, correct
the mechanism, and reference `SessionPersistenceE2ETest` so a reader can see the guard is now tested.

**`application.properties`, the Spring Session block (lines 21-24)** — add a comment recording the
real idempotency mechanism, because `initialize-schema=always` re-running a non-idempotent script is
counter-intuitive and the consequence is visible in production logs. Record that Spring Session's
`schema-postgresql.sql` uses bare `CREATE TABLE` with no conditional-existence clause, that Spring
Boot's `JdbcSessionDataSourceScriptDatabaseInitializer` sets continue-on-error which is what makes
`always` safe to re-run, and that the resulting "relation already exists" messages on every restart
after the first are expected rather than a failed deploy. Also record why no manual DDL script
accompanies this change (D-02), in contrast to `docs/plans/backend-modernization/02-optimistic-locking-ddl.sql`
and `03-activity-log-ddl.sql` which exist because Hibernate's `ddl-auto` is unset in production —
Spring Session ships its own initializer and needs no such bridge.

Run `./gradlew spotlessApply` before committing so the AOSP formatter normalises the edited Javadoc
block; the pre-commit hook runs it too, but running it explicitly keeps the commit clean.
  </action>
  <verify>
    <automated>./gradlew spotlessCheck test</automated>
  </verify>
  <done>`./gradlew spotlessCheck` and `./gradlew test` both pass. `.claude/CLAUDE.md` lines 305, 307, 308 and 358 each state something verifiable against the code as it now stands, with the concurrent-session entry no longer asserting an unenforced ceiling as fact. `UserAuthenticationProvider`'s comment names `spring_session_attributes`, describes save-on-change rather than save-per-request, and points at `SessionPersistenceE2ETest`. `application.properties` records the continue-on-error mechanism, the expected restart log noise, and why D-02 adds no DDL script. No manual pre-merge production step was introduced by any task in this plan.</done>
  <reversibility rating="reversible">Comments and documentation only.</reversibility>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client → API (session cookie) | The `JSESSIONID` cookie is the sole bearer of authenticated identity; it now resolves to a database row rather than to JVM memory. |
| application → PostgreSQL (session store) | The serialized `SecurityContext` now crosses into durable storage, where it persists beyond process lifetime and is readable by anything with database access. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-shl-01 | Spoofing | `AuthenticationController.signin` — session id not rotated on login | medium | accept | Pre-existing and not introduced by this change; `sessionFixation(newSession)` is configured but never applied because no `SessionAuthenticationStrategy` runs on the custom signin path. Partly bounded by `http-only` + `same-site=strict` cookies, and by Spring Session issuing a fresh id when a presented id is absent from the store. Documented in `.claude/CLAUDE.md:307` (Task 3) and filed as a security-marked todo (Task 2). Fixing it changes the authentication path for every request and is out of a quick task's blast radius. |
| T-shl-02 | Information Disclosure | `SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES` | medium | mitigate | The serialized `SecurityContext` becomes durable, so any secret in the `Authentication` principal becomes durable too. `UserAuthenticationProvider` already builds a minimal principal for this reason; Task 1's fourth assertion turns that previously-unverifiable guard into an enforced regression test by asserting the persisted bytes carry no bcrypt hash marker. |
| T-shl-03 | Denial of Service | `SPRING_SESSION` table growth | low | accept | Spring Session JDBC's default cleanup cron deletes expired sessions on a schedule; the 180-minute timeout bounds row lifetime. No tuning needed at this scale. |
| T-shl-04 | Elevation of Privilege | `maximumSessions(2).maxSessionsPreventsLogin(true)` unenforced | low | accept | The ceiling is a session-hygiene control, not an authorisation boundary — every request is still authenticated and every resource still ownership-verified through `OwnershipVerifierService`. Its absence widens no access path. Corrected in documentation (Task 3) and filed as a todo (Task 2) rather than left asserted as fact. |
| T-shl-SC | Tampering | `org.springframework.session:spring-session-jdbc` dependency | low | accept | Not an npm/pip/cargo install, so the package legitimacy gate does not apply. First-party Spring Projects artifact under the `org.springframework.session` group, version resolved by the Spring Boot 3.5.0 BOM the build already trusts for every other Spring dependency — no new version pin and no new registry trusted. |
</threat_model>

<verification>
1. `./gradlew spotlessCheck` passes.
2. `./gradlew test` passes — the full suite, not only the new class, since the effective session
   timeout and the session cookie format both change for every E2E test in the suite.
3. `SessionPersistenceE2ETest` proves the session tables are created and that a signin writes a real
   `SPRING_SESSION` row plus a `SPRING_SECURITY_CONTEXT` attribute row. The task is not complete on a
   green suite alone — a green suite was the pre-existing state that hid the defect.
4. No `.sql` file was added anywhere, and no new manual pre-merge step exists (D-02).
5. `SecurityConfiguration.java` and `AuthenticationController.java` are unmodified.
</verification>

<success_criteria>
- `spring-session-jdbc` is on the runtime classpath and the three `spring.session.*` properties are
  live rather than inert (D-01).
- Schema creation is proven by test, and comes solely from `initialize-schema=always` with no DDL
  script and no new manual gate (D-02).
- The bcrypt-hash-exclusion guard in `UserAuthenticationProvider` is enforced by a test for the first
  time.
- Every session-related claim in `.claude/CLAUDE.md`, `UserAuthenticationProvider`, and
  `application.properties` is true and verifiable against the code — including the concurrent-session
  claim, which is corrected rather than left standing.
- The unenforced `SessionAuthenticationStrategy` finding is captured as a security-marked todo with a
  tripwire test that fails when it is fixed.
- `./gradlew spotlessCheck` and `./gradlew test` are green.
</success_criteria>

<output>
Create `.planning/quick/260802-shl-fix-the-dead-spring-session-jdbc-configu/260802-shl-SUMMARY.md` when done.
</output>
