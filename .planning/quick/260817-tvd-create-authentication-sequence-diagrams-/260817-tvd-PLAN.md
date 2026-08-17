---
phase: quick-260817-tvd
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - docs/AUTH_FLOWS.md
  - docs/diagrams/auth-signup-scenario.mmd
  - docs/diagrams/auth-signup-scenario.png
  - docs/diagrams/auth-signin-scenario.mmd
  - docs/diagrams/auth-signin-scenario.png
  - docs/ARCHITECTURE.md
  - README.md
autonomous: true
requirements: [QUICK-260817-tvd-AUTH-SCENARIO-DIAGRAMS]
user_setup: []

estimate:
  tokens: 38000
  raw_tokens: 38000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "A frontend engineer with no JVM/Spring background can read docs/AUTH_FLOWS.md and derive, without opening a single .java file, the full HTTP-observable contract of POST /api/signup and POST /api/signin: request body shape, every status code each route can return, the RFC 7807 envelope and its stable `code` value for each failure, and the Set-Cookie / Location headers on success."
    - "Both diagrams depict the REAL implementation, not a generic auth flow: the sessionAuthenticationStrategy call site is drawn AFTER authenticationManager.authenticate() returns and BEFORE securityContextRepository.saveContext(), because that is the order AuthenticationController.authenticate's Vavr mapTry lambda executes them in."
    - "The signin diagram shows that a wrong password and a rejected third concurrent session both collapse to a byte-identical 401 with code BAD_CREDENTIALS -- deliberately indistinguishable (D-08) -- so a test author never writes an assertion that tries to tell them apart."
    - "The signup diagram shows the three failure arms signin does not have: 400 VALIDATION_FAILED before the method body runs, 409 DUPLICATE_RESOURCE from the checked existsByEmail guard (with the DB-unique-constraint race backstop also landing on 409), and the auto-rollback deleteById that runs when authentication of the just-created account fails before the response collapses to 401 BAD_CREDENTIALS."
    - "The signup diagram states that the ceiling arm drawn on signin cannot fire on signup, because a brand-new principal has zero live sessions -- the two flows share one helper but not one set of outcomes."
    - "docs/AUTH_FLOWS.md carries an E2E-hazards section naming the five facts that will silently break a Playwright suite: the 2-session-per-user ceiling, the 10-minute cookie max-age against the 180-minute server-side session timeout, SameSite=Strict, the credentialed-CORS explicit origin allow-list, and the rotating session id."
    - "Every ErrorCode name that appears in either diagram or in AUTH_FLOWS.md is a real member of ErrorCode.java -- gated automatically, so a renamed enum member fails the check instead of silently falsifying the docs."
    - "The pre-existing docs/diagrams/architecture-signin-scenario.mmd is left byte-identical, and the two signin diagrams are reciprocally cross-linked so a reader is never left guessing which is authoritative for their question."
    - "Each checked-in PNG is a render of its checked-in .mmd sibling produced in this same change, and neither is wider than the widest diagram PNG already in docs/diagrams/."
    - "No file under src/, no build file, and no application properties file is modified -- this is documentation-only."
  artifacts:
    - "docs/diagrams/auth-signup-scenario.mmd (new -- client-facing Scenario (+1) view of POST /api/signup)"
    - "docs/diagrams/auth-signup-scenario.png (new -- render of the above)"
    - "docs/diagrams/auth-signin-scenario.mmd (new -- client-facing Scenario (+1) view of POST /api/signin)"
    - "docs/diagrams/auth-signin-scenario.png (new -- render of the above)"
    - "docs/AUTH_FLOWS.md (new -- the host document for both diagrams, plus the E2E-hazards section)"
    - "docs/ARCHITECTURE.md (one scoped Edit adding the reciprocal cross-link)"
    - "README.md (one scoped Edit adding the doc-index table row)"
    - ".planning/quick/260817-tvd-create-authentication-sequence-diagrams-/260817-tvd-SUMMARY.md"
  key_links:
    - "Diagram/doc error codes <-> the ErrorCode enum members in src/main/java/com/vrudenko/kanban_board/constant/ErrorCode.java. ErrorCode's own Javadoc declares these a published API contract consumed by the frontend, so this is the single most load-bearing link in the whole change -- gated by extracting every code named in the docs and checking it against the enum."
    - "The E2E-hazards numbers <-> src/main/resources/application.properties (session.cookie.max-age, spring.session.timeout, cookie.same-site, cookie.name) and SecurityConfiguration.MAX_CONCURRENT_SESSIONS. A config change silently falsifies the hazards section, which is why each number is grep-gated against its source of truth rather than merely written down."
    - "Diagram beat ordering <-> AuthenticationController.authenticate's mapTry lambda body. The onAuthentication-then-saveContext order is the single fact that makes these diagrams a description of this system rather than of Spring Security's default form-login filter, which this application does not use."
    - "docs/AUTH_FLOWS.md <-> docs/diagrams/architecture-signin-scenario.mmd. Two diagrams of one scenario at two abstraction levels for two audiences is only legitimate while both declare their audience and point at each other; without the reciprocal link this change ships the exact duplicate-diagram hazard quick task 260816-tqc's trade-off matrix rejected approach B over."
    - "README.md's doc-index table <-> docs/AUTH_FLOWS.md. That table is how every other doc in this repo is discoverable; a new doc absent from it is a doc nobody finds."
---

<objective>
Add two client-facing Kruchten **Scenario (+1)** sequence diagrams -- `POST /api/signup` and `POST /api/signin` -- written for a frontend engineer planning an E2E suite against this API, and a host document (`docs/AUTH_FLOWS.md`) that carries them plus the session/cookie/CORS facts that will otherwise break that suite silently.

Purpose: milestone v1.3 (Nonprod Environment & CI Hardening) is scoped around standing up a nonprod environment and wiring a separate frontend repo's Playwright E2E suite as a CI gate. Whoever writes those tests needs the authentication contract in HTTP terms. Today that information exists only as Java, or as `docs/diagrams/architecture-signin-scenario.mmd` -- a genuinely excellent diagram that is aimed at a security reviewer, names findings by ID (F1, F6, D-08), depicts a BCrypt timing equalizer and a TOCTOU commit-ordering measurement, and draws five Spring-internal participants. It answers "is this endpoint safe?" It does not answer "what will my test see?", and signup is not drawn in it at all.

Output: two `.mmd` sources with matching `.png` renders, one new host document, and two one-line index/cross-link edits -- with the pre-existing security-focused signin diagram left untouched and reciprocally linked, so the repo gains a second altitude on one scenario rather than a second, competing claim about it.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@docs/DIAGRAM_CONVENTIONS.md
@docs/ARCHITECTURE.md
@src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
@src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
@src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
@src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
@src/main/java/com/vrudenko/kanban_board/constant/ErrorCode.java
</context>

<approach_analysis>

Required by `.claude/CLAUDE.md`: alternatives considered, a trade-off matrix, and the non-obvious trade-offs, before any PLAN is approved.

The real decision here is not "how do I draw a sequence diagram" -- the house style for that is settled and has two recent precedents. It is **what to do about the signin diagram that already exists**, because the naive reading of this task ("create signin and signup diagrams") would ship a second signin diagram next to the first with nothing reconciling them.

## Trade-off Matrix

| Approach | Pros / Cons | Why Picked / Rejected |
|---|---|---|
| **A. Two new client-facing diagrams in a new host doc `docs/AUTH_FLOWS.md`**, leaving `architecture-signin-scenario.mmd` byte-identical, with reciprocal cross-links declaring each one's audience. | **Pros:** the security diagram keeps every finding-level detail two prior quick tasks (260811-ezy, 260811-h2v) were spent producing; the new pair is free to speak HTTP instead of Spring, which is the only way the stated audience is actually served; signup gets a real diagram rather than the prose footnote it has today; a new host doc lets the E2E-hazards section (cookie lifetimes, CORS, session ceiling) live next to the diagrams that motivate it, where ARCHITECTURE.md has no natural home for it. **Cons:** two diagrams of one scenario -- exactly the shape 260816-tqc's matrix rejected; adds a seventh doc to `docs/`. | **PICKED.** The duplicate-diagram objection is real but does not apply here, and the distinction is worth stating precisely: 260816-tqc rejected approach B because the existing artifact was *factually wrong* (named a job that no longer existed, disclaimed itself as unbuilt). Nothing in `architecture-signin-scenario.mmd` is wrong. Two diagrams that disagree is a defect; two diagrams at different altitudes for different audiences is ordinary technical writing -- but **only while both declare their audience and link to each other**, which is why Task 3 is not optional polish. |
| **B. Rewrite `architecture-signin-scenario.mmd` for the frontend audience, add signup alongside it.** | **Pros:** exactly one signin diagram in the repo, zero reconciliation burden, no new doc. **Cons:** destroys checked-in security documentation to serve a different audience -- the timing-equalizer arm (F1), the accepted TOCTOU bound (F6/D-01), and the commit-ordering measurement would all have to go, since none of them mean anything to a frontend engineer. Worse, ARCHITECTURE.md's surrounding prose is written against that detail, so this silently becomes a much larger edit than it looks. | **REJECTED.** Trading a security artifact for an onboarding artifact is a net loss when the repo can hold both. This is the scope-reduction trap in reverse -- it looks like consolidation and is actually deletion. |
| **C. One combined diagram covering signup and signin in `alt` branches.** | **Pros:** one file, one render; the shared `authenticate` helper is genuinely common to both, so the overlap is real and a combined view would show it once. **Cons:** the two flows diverge before they converge (signup persists a user first, signin looks one up) and diverge again after (201 + Location vs 200), so the shared middle is bracketed by two different beginnings and two different endings -- the `alt` would nest three deep; the diagram would carry signup's 409 arm, signin's unknown-email arm, the shared 400 arm, and the shared 401 collapse, in one picture, against a 10-participant in-repo legibility precedent. | **REJECTED.** The shared helper is better documented as a stated fact in both diagrams ("this middle section is identical in the other flow") than as a structural merge that costs three levels of nesting. Legibility at GitHub content width is this repo's recurring diagram failure mode (lesson from 260806-nyj), and this is the option most likely to trip it. |
| **D. Skip the host doc; put both diagrams in ARCHITECTURE.md.** | **Pros:** smallest footprint, no new file, no README index row. **Cons:** ARCHITECTURE.md declares itself as "the engineering detail behind the summary in the README... explains mechanisms and the reasoning behind them" -- an audience of people working *on* this backend, not people calling it; and it would then contain two signin scenario sections, which is the most confusing possible placement. | **REJECTED.** Mixing audiences inside one document is what makes the existing signin diagram unable to serve this need in the first place. Repeating that mistake in the same file would compound it. |

## Non-obvious trade-offs

- **State-invalidation risk is the dominant long-term cost, and it is higher here than in a normal diagram task.** These documents hard-code an enum's member names, four `application.properties` values, one Java constant, and five route literals. `ErrorCode`'s own Javadoc declares its members "a published API contract consumed by the frontend... renaming a member is a breaking change" -- which means the *most likely* future change to it is precisely the one that would silently falsify this doc while a frontend team relies on it. Mitigation is not a maintenance note (nobody reads those in time): Task 2's gate **extracts every error code named anywhere in the new docs and fails if it is not a member of the enum**, and greps each config number against the property file it came from. The docs cannot drift without the check firing.
- **The audience constraint and the accuracy constraint pull against each other, and accuracy wins on ties.** "Readable without JVM/Spring background" tempts a redraw where `AuthenticationController` becomes "the server" and the whole strategy/context-repository dance becomes one arrow. That would be a generic auth diagram, which is explicitly what this task forbids. The resolution adopted below is to keep the real participants and the real ordering, and pay for the reader's lack of background with plain-language `Note` blocks rather than by deleting mechanism. A frontend engineer does not need to know what a `SessionAuthenticationStrategy` *is*; they absolutely need to know that something checks a session count at that exact point and that its rejection is invisible to them.
- **The single highest-value fact in this entire change is a hazard, not a happy path.** `MAX_CONCURRENT_SESSIONS = 2`, enforced against live rows, with rejection collapsed to a 401 identical to a wrong password. A Playwright suite with three or more parallel workers all signing in as one seeded fixture user will get 401s from the third worker onward, and the response body will tell the author their password is wrong. That is a multi-hour debugging session that these diagrams exist to prevent, and it is why the ceiling arm must be drawn on signin rather than summarized in prose.
- **Two honest caveats must not be smoothed over.** (1) `POST /api/signup`'s `201` carries a `Location` naming `/api/users/me`, and that route has no `GET` handler yet (open todo, 2026-08-12) -- a test that follows the Location will fail, so the diagram must say the header does not resolve rather than drawing a tidy created-resource pattern. (2) The session cookie's `max-age` is 600 seconds while the server-side session timeout is 180 minutes; these differ *by design*, so the diagram must not imply one session lifetime. Drawing either of these the conventional way would be a worse error than omitting them.
- **Security/information disclosure is a live concern even though this is docs-only.** These files describe the authentication surface of an internet-reachable production deployment. `.githooks/pre-commit` runs gitleaks over the staged diff, but gitleaks will not catch a plausible-looking example password typed into a request-body illustration -- and it *will* refuse the commit if a genuinely credential-shaped literal lands, which on a diagram task means a wasted commit cycle. Both are handled by using clearly non-credential placeholders in every example body, gated by a negative grep, and by referencing configuration by property name rather than by pasted value where the value is environment-specific.
- **Mermaid parse hazards are a known, measured cost on this repo.** Quick task 260816-tqc burned four render iterations on three parser quirks in mermaid-cli v11: HTML-entity-escaped angle brackets, a bare colon inside message text, and a semicolon inside message text -- each surfacing only on a separate render. A fourth quirk cost a fifth iteration: quotes around a `box` title render literally. Task 1 states all four up front so this task pays that cost once, in reading, instead of five times in rendering.

## Data-flow mechanism, in three sentences

Both routes are `permitAll` in the filter chain, so a request reaches `AuthenticationController` with no session, is bean-validated into its DTO before the method body runs, and then diverges: signup persists a new user through a duplicate-email guard, signin loads an existing one by email and pays a deliberate BCrypt cost even when that lookup misses. Both then converge on one private `authenticate` helper, which builds an unauthenticated token from the user's **id** (not their email), runs it through `UserAuthenticationProvider` for the password comparison, and -- only if that succeeds -- invokes `sessionAuthenticationStrategy.onAuthentication(...)`, which enforces the two-session ceiling and then rotates the session id, before saving the `SecurityContext`. Every failure inside that helper is swallowed by a Vavr `Try` into a plain `false`, which the caller turns into a single `BadCredentialsException` and `GlobalExceptionHandler` turns into one 401 shape, which is why a wrong password, a failed provider, and a rejected third session are indistinguishable from outside.

</approach_analysis>

<source_audit>

Only two source types apply to this quick task: the GOAL (the task description) and its CONSTRAINTS. There is no ROADMAP requirement, no RESEARCH.md, and no CONTEXT.md D-NN decision set for a quick task.

| Source item | Covered by | Status |
|---|---|---|
| GOAL: sign-up sequence diagram | Task 1 | COVERED |
| GOAL: sign-in sequence diagram | Task 2 | COVERED |
| GOAL: audience is a frontend engineer planning E2E tests | Task 1 (doc framing + audience declaration), Task 2 (E2E-hazards section) | COVERED |
| GOAL: follow docs/DIAGRAM_CONVENTIONS.md, Scenario (+1) view | Tasks 1 and 2 (explicit view declaration in both `.mmd` and host doc) | COVERED |
| GOAL: accurate against AuthenticationController | Tasks 1-2, gated by literal greps on route/status/code | COVERED |
| GOAL: accurate against UserAuthenticationProvider | Task 2 (password comparison + minimal-principal beat) | COVERED |
| GOAL: accurate against SecurityConfiguration session strategy | Tasks 1-2 (call-site ordering), gated against `MAX_CONCURRENT_SESSIONS` | COVERED |
| GOAL: accurate against the RFC 7807 error envelope | Tasks 1-2, gated by the ErrorCode-membership extraction check | COVERED |
| GOAL: output under docs/diagrams/ | Both `.mmd` + `.png` pairs written there | COVERED |
| CONSTRAINT: documentation-only, no production code, no test changes | `files_modified` lists docs + README only; every task's verify asserts a clean `git diff` on `src/`, `build.gradle`, and the properties files | COVERED |
| CONSTRAINT: session creation only on login (IF_REQUIRED) | Task 2, drawn as the session materializing on the success arm only | COVERED |
| CONSTRAINT: strategy call site AFTER authenticate(), BEFORE saveContext() | Tasks 1-2, called out as the ordering fact both diagrams must get right | COVERED |
| CONSTRAINT: concurrent session ceiling + fixation protection | Task 2 (both, in composite order), Task 1 (why the ceiling cannot fire on signup) | COVERED |
| CONSTRAINT: Spring Session JDBC persistence | Tasks 1-2, incl. the commit-after-response ordering | COVERED |
| CONSTRAINT: collapsed/generic 401 -- wrong password and ceiling indistinguishable | Task 2, drawn as two arms landing on one identical response | COVERED |
| CONSTRAINT: readable without JVM/Spring background | Tasks 1-2 (plain-language Notes, HTTP-first framing), gated by a human legibility check | COVERED |
| CONSTRAINT: verify the house format rather than assuming Mermaid-in-Markdown | Resolved during planning -- house style is a standalone `.mmd` + committed `.png`, embedded via `![...](diagrams/x.png)` + `<sub>[diagram source](diagrams/x.mmd)</sub>`; confirmed across all six existing diagrams and both host docs | COVERED |
| CONSTRAINT: index/reference update elsewhere if convention calls for one | Task 3 (README doc-index row + ARCHITECTURE.md cross-link) | COVERED |

No unplanned items.

**One scope note for the developer, flagged rather than silently taken:** the constraint permits "new/updated files under `docs/diagrams/` and a reference/index update elsewhere in `docs/`". This plan also creates `docs/AUTH_FLOWS.md` (a host document -- required, because every diagram in this repo is embedded in a `.md`; a bare `.mmd` would be the first orphan) and edits `README.md`'s doc-index table by one row (the index this project actually uses lives there, not under `docs/`). Both are inside the spirit of the constraint and neither touches production code, but they are named here explicitly rather than buried in `files_modified`.

</source_audit>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| working tree -> public git history | Anything committed here is permanent and world-readable once pushed; a leaked value cannot be un-published by a later commit |
| local shell -> npm registry | An external tool is fetched at render time to produce the PNGs |
| this documentation -> an external frontend team | These docs become the authority a separate repo's test suite is written against; a wrong statement here propagates as wrong test assertions there |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-tvd-01 | Information Disclosure | `docs/AUTH_FLOWS.md`, both `.mmd` sources | medium | mitigate | Every example request body uses an obviously-fake placeholder, never a plausible credential. No production hostname, no deployed origin value, no database connection string, no session cookie value. Configuration is referenced by property name; only the two non-environment-specific numeric values already public in the committed properties file are quoted. Gated by a negative grep for credential-shaped and address-shaped literals, in addition to the `gitleaks` pre-commit scan -- which would catch a real key but not a hand-typed example password, so this cannot rely on the hook. |
| T-tvd-02 | Information Disclosure | describing the auth surface (ceiling value, cookie flags, collapsed 401) at all | low | accept | Every fact stated is already public in this repo's committed source, `application.properties`, and `docs/ARCHITECTURE.md`. The collapsed 401 is a control whose value does not depend on the *existence* of the collapse being secret -- D-08's point is that an attacker cannot distinguish the arms at runtime, which stays true whether or not the design is documented. Documenting it also prevents a future contributor from "fixing" the indistinguishability as if it were a bug. |
| T-tvd-03 | Repudiation | the docs' own accuracy claims | high | mitigate | This is the highest-severity threat in the change: these documents are consumed as an API contract by a team that cannot see this codebase, so a silently-stale claim here becomes wrong test assertions there with nothing failing in between. Every error code named is checked for membership in `ErrorCode.java` by extraction rather than by a fixed list; the ceiling value, both session lifetimes, the cookie name and the SameSite policy are each grep-gated against their source of truth; both route literals are gated against `ApiPaths.java`. A rename or a config change fails the gate. |
| T-tvd-04 | Tampering | Mermaid renderer fetched at render time (`@mermaid-js/mermaid-cli@11` via `npx`) | medium | mitigate | Package-legitimacy gate assessed and found not to apply: nothing enters this project's dependency graph (no `build.gradle`, `package.json`, or lockfile is touched), the tool is ephemeral and build-time only, and its sole output is two PNGs under `docs/diagrams/`. This exact pinned major is prior art in this repo's own reviewed history (quick tasks 260806-nyj and 260816-tqc). Pin to it; do not substitute an unvetted alternative. Recorded here rather than skipped silently. |
| T-tvd-05 | Tampering | `docs/diagrams/architecture-signin-scenario.mmd` / `.png` | low | mitigate | The pre-existing security diagram is adjacent to this work and is the most plausible accidental casualty of a task about signin diagrams. Every task's verify asserts it is byte-identical to its pre-task state. |
</threat_model>

<tasks>

<task type="tracer" tdd="false">
  <name>Task 1: Create docs/AUTH_FLOWS.md and the sign-up scenario diagram, rendered end to end</name>
  <files>docs/AUTH_FLOWS.md, docs/diagrams/auth-signup-scenario.mmd, docs/diagrams/auth-signup-scenario.png</files>
  <precondition>A Mermaid renderer is obtainable -- `npx` can reach the npm registry. No renderer is vendored in this repo, and the prior two diagram tasks both fetched it at render time. Assert it works before authoring, and halt rather than hand-editing a PNG if it does not.</precondition>
  <read_first>
    Read `src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java` in full -- specifically the `signup` method and the private `authenticate` helper, including the comment explaining why `userService.save(...)` sits deliberately outside the try block. Read `src/main/java/com/vrudenko/kanban_board/service/UserService.java`'s `save` method for the duplicate-email guard and its database-level backstop. Read `src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java` for which exception maps to which status, and `src/main/java/com/vrudenko/kanban_board/constant/ErrorCode.java` for the exact member spellings. Read `src/main/java/com/vrudenko/kanban_board/dto/user_dto/SignupRequestDTO.java` and `UserResponseDTO.java` for the request and response body shapes.

    Read `docs/DIAGRAM_CONVENTIONS.md` for the view declaration this diagram must honour, and `docs/diagrams/architecture-signin-scenario.mmd` for the house style -- `alt`/`Note over` usage, `<br/>` line breaks, short participant aliases. Note while reading it that this is the diagram the new pair must NOT duplicate or contradict; it is the security-reviewer altitude on signin, and Task 3 will link the two.

    Four Mermaid v11 parser quirks cost quick task 260816-tqc five render iterations. Avoid all four from the first draft: no HTML-entity-escaped angle brackets in message text, no bare colon inside a message body, no semicolon inside a message body, and no quotation marks around a `box` title (they render literally). Prefer parenthetical phrasing and double-dashes where you would reach for those characters.
  </read_first>
  <action>
    Create two files. This task is the tracer for the whole change -- it proves the full path (author a source, render it, embed it in a host doc, pass the accuracy gates) end to end on one flow, so that Task 2 is expansion rather than discovery.

    **First, `docs/diagrams/auth-signup-scenario.mmd`** -- a Mermaid `sequenceDiagram` that is one deliberate Kruchten Scenario (+1) view of `POST /api/signup`, drawn at the HTTP-observable altitude.

    Participants, left to right, with short aliases: an actor for the Frontend client; the signup endpoint handler; the user-persistence service; the authentication manager and the credential-checking provider; the session strategy bean; the security-context repository; and Postgres, labelled to show it holds both the users table and the Spring Session tables. Keep the participant count at or below the ten-participant in-repo precedent set by `architecture-mutation-sequence.mmd`.

    The diagram must carry these beats, in this order, each checked against the source files named above rather than reconstructed from memory:

    1. The client POSTs the request body to the route, noting it is one of only two routes reachable without a session.
    2. Bean validation of the request DTO, drawn as happening **before** the handler method body runs -- so an invalid email or password never reaches any application logic. Show the 400 arm with its envelope, including the per-field error map extension property that this arm alone carries.
    3. The duplicate-email guard, drawn as its own `alt` arm returning 409 with the checked duplicate-resource envelope. Add a short `Note` recording that a race between two simultaneous signups for one address still cannot create two rows -- the database carries a unique constraint on the column, and the loser lands on a 409 too, via a different code. Name both codes, since a client may see either.
    4. The successful persistence, then the call into the shared private helper -- and mark clearly, in a `Note`, that everything from here to the response is **identical code to the signin flow**, so a reader of the sibling diagram is not re-learning it. This is the fact that makes two diagrams cheaper than one merged one.
    5. Inside the helper, in this exact order, because the ordering is the whole point of drawing it at all: an unauthenticated token is built from the new user's **id** (not their email address -- worth a `Note`, since a reader will assume otherwise); the authentication manager delegates to the provider, which loads the stored credential and compares it; on success the provider returns a deliberately minimal principal carrying no credential material, because this object is what gets serialized into the session store.
    6. Then, and only after the previous beat succeeded, the session strategy is invoked -- and only after *that* is the security context saved. Draw these as two distinct, ordered arrows, and add a `Note` stating plainly that the concurrent-session ceiling drawn on the signin diagram **cannot reject a signup**, because a principal that did not exist a moment ago has no live sessions. The session-id rotation half of the strategy does still apply.
    7. The session materializing: the context is written into a request-scoped session, and the session rows are committed to Postgres as the response is flushed -- **after** the handler has already returned. Draw this ordering honestly; it is not an implementation detail here, it is why a client that inspects the database the instant it gets its response can see nothing yet.
    8. The success response: 201, the `Set-Cookie` header carrying the session cookie by its configured name, the `Location` header, and the response body's four fields. Attach a `Note` to the `Location` header stating that the resource it names has no read handler yet, so following it will not succeed -- cite the open todo rather than re-explaining it.
    9. The auto-rollback arm, which is signup's alone and is easy to get wrong: if the helper reports failure, the just-created user is deleted, an access-denied exception is raised, and that exception is then caught by the method's own blanket handler and re-thrown as a credentials failure -- so the client sees 401, not 403, despite the intermediate exception type. Draw the intermediate step; a reader tracing types would otherwise predict the wrong status.

    **Second, `docs/AUTH_FLOWS.md`** -- the host document, created in this task with its framing and the signup section; Task 2 appends the signin section and the hazards section to it.

    Open with a title and a short paragraph declaring, explicitly: who this document is for (a frontend or QA engineer writing tests against this API, assumed to have no JVM or Spring background), what question it answers (what a client observes when it authenticates, and what will silently break a test suite), and that it is a Scenarios (+1) view per the conventions doc. Add a sentence pointing at `docs/ARCHITECTURE.md`'s signin scenario as the deeper, security-reviewer view of the same flow, framed as complementary rather than competing -- state the difference in one clause so a reader can choose.

    Then a `## Sign up` section: the route and method, the request body fields and what each must satisfy in plain language, a table of every status this route can return with its stable code and what causes it, then the embedded diagram using the exact house pattern -- an image line pointing at the `.png`, followed on the next line by a `<sub>` line linking the `.mmd` as the diagram source. Match the existing embeds in `ARCHITECTURE.md` character for character in structure. Close the section with a short "what this means for a test" paragraph naming the two traps: the `Location` header does not resolve, and a re-run against a non-reset database hits the 409 arm rather than the 201 arm, so a suite that assumes a clean signup must either randomize the address or reset state.

    Use an obviously-fake placeholder in every example body -- something no one could mistake for a real credential. Reference configuration by property name. Do not paste any deployed origin or hostname.

    Then render `docs/diagrams/auth-signup-scenario.png` from the source and commit both together -- a source and a render that disagree is precisely the failure mode a committed PNG exists to avoid.
  </action>
  <verify>
    <automated>
cd "C:/Dev/Repos/kanban-board-backend" &&
npx -y @mermaid-js/mermaid-cli@11 -i docs/diagrams/auth-signup-scenario.mmd -o docs/diagrams/auth-signup-scenario.png &&
test -f docs/AUTH_FLOWS.md &&
grep -q 'sequenceDiagram' docs/diagrams/auth-signup-scenario.mmd &&
for lit in 201 401 400 409 VALIDATION_FAILED DUPLICATE_RESOURCE DATA_INTEGRITY_VIOLATION BAD_CREDENTIALS Location JSESSIONID; do
  grep -v '^[[:space:]]*%%' docs/diagrams/auth-signup-scenario.mmd | grep -q -- "$lit" || { echo "SIGNUP DIAGRAM MISSING: $lit"; exit 1; }
done &&
for c in $(grep -ohE '[A-Z][A-Z_]{5,}' docs/diagrams/auth-signup-scenario.mmd | sort -u); do
  case "$c" in VALIDATION_FAILED|DUPLICATE_RESOURCE|DATA_INTEGRITY_VIOLATION|BAD_CREDENTIALS|ACCESS_DENIED|ENTITY_NOT_FOUND|UNAUTHENTICATED|OPTIMISTIC_LOCK_CONFLICT|CONSTRAINT_VIOLATION|MALFORMED_REQUEST_BODY|ILLEGAL_ARGUMENT|INTERNAL_ERROR)
    grep -qE "^[[:space:]]*$c[,;]" src/main/java/com/vrudenko/kanban_board/constant/ErrorCode.java || { echo "NOT AN ErrorCode MEMBER: $c"; exit 1; };; esac
done &&
grep -q 'SIGNUP = "/signup"' src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java &&
grep -q 'server.servlet.session.cookie.name=JSESSIONID' src/main/resources/application.properties &&
grep -q 'diagrams/auth-signup-scenario.png' docs/AUTH_FLOWS.md &&
grep -q 'diagrams/auth-signup-scenario.mmd' docs/AUTH_FLOWS.md &&
grep -qi 'DIAGRAM_CONVENTIONS' docs/AUTH_FLOWS.md &&
grep -q 'ARCHITECTURE.md' docs/AUTH_FLOWS.md &&
if grep -nEi 'BEGIN [A-Z ]*PRIVATE KEY|[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|xox[baprs]-|AKIA[0-9A-Z]{16}' docs/AUTH_FLOWS.md docs/diagrams/auth-signup-scenario.mmd; then echo "SECRET-SHAPED LITERAL FOUND"; exit 1; fi &&
test "$(grep -cE '^[[:space:]]*(participant|actor)[[:space:]]' docs/diagrams/auth-signup-scenario.mmd)" -le 10 &&
node -e "const fs=require('fs'),d='docs/diagrams',w=f=>fs.readFileSync(d+'/'+f).readUInt32BE(16),t=w('auth-signup-scenario.png'),m=Math.max(...fs.readdirSync(d).filter(f=>f.endsWith('.png')&&!f.startsWith('auth-')).map(w));console.log('new width',t,'| existing max',m);if(t>m){console.error('RENDER WIDER THAN EVERY EXISTING DIAGRAM');process.exit(1)}" &&
test docs/diagrams/auth-signup-scenario.png -nt docs/diagrams/auth-signup-scenario.mmd &&
git diff --quiet -- src/ build.gradle docs/diagrams/architecture-signin-scenario.mmd docs/diagrams/architecture-signin-scenario.png &&
echo "OK: signup diagram renders, all required literals present, every error code is a real enum member, route and cookie name match source, no secret-shaped literal, participants within precedent, render newer than source, production code and the pre-existing signin diagram untouched"
    </automated>
    <human-check>Open `docs/diagrams/auth-signup-scenario.png` and confirm it reads at GitHub content width to someone who does not know Spring -- specifically that the three failure arms (400, 409, 401) are visually separable from the success path, and that the ordering of the session-strategy call and the context save is unambiguous.</human-check>
  </verify>
  <done>
    `docs/diagrams/auth-signup-scenario.mmd` is a Scenario (+1) sequence diagram of `POST /api/signup` carrying all nine beats: the permitAll entry, pre-method bean validation with its 400 arm and per-field error map, the duplicate-email 409 arm with its database-constraint backstop and second code, the shared-helper marker, the id-not-email token, the provider's credential comparison and minimal principal, the strategy-then-save ordering with the note that the ceiling cannot reject a signup, the after-response session commit, the 201 with its cookie/Location/body and the note that the Location does not resolve, and the auto-rollback arm ending in 401 rather than 403. `docs/AUTH_FLOWS.md` exists with its audience declaration, its view declaration, a complementary cross-reference to ARCHITECTURE.md's signin scenario, and a complete `## Sign up` section embedding the diagram in the house pattern. `auth-signup-scenario.png` is a fresh render of the source and is no wider than the diagrams already checked in. No example body contains a plausible credential. Nothing under `src/`, no build file, and neither pre-existing signin diagram file is modified.
  </done>
</task>

<task type="auto" tdd="false">
  <name>Task 2: Add the sign-in scenario diagram and the E2E-hazards section</name>
  <files>docs/diagrams/auth-signin-scenario.mmd, docs/diagrams/auth-signin-scenario.png, docs/AUTH_FLOWS.md</files>
  <read_first>
    Read `AuthenticationController.java`'s `signin` method, including the comment on the discarded password comparison in the unknown-email arm and why it must not be removed as dead code. Read `src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java` in full. Read `src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java` -- the `sessionManagement` block, the `sessionAuthenticationStrategy` bean, and the bean's Javadoc, which states the composite ordering (concurrency control before fixation) and why the rejection deliberately stays indistinguishable from a wrong password.

    Read `src/main/java/com/vrudenko/kanban_board/config/CorsConfig.java` and the session/cookie/CORS lines of `src/main/resources/application.properties`. These are the source of every number in the hazards section; take each value from the file rather than from this plan's prose, and if any disagrees with what is written here, the file wins and the discrepancy goes in the summary.

    Re-read `docs/diagrams/auth-signup-scenario.mmd` as written in Task 1, so the shared middle section is drawn with the same participant aliases and the same wording -- two diagrams describing one helper differently is worse than either describing it badly.
  </read_first>
  <action>
    **First, `docs/diagrams/auth-signin-scenario.mmd`** -- the sibling Scenario (+1) view of `POST /api/signin`, same participants and aliases as the signup diagram wherever the flows overlap, so a reader moving between them recognizes the shared half instantly.

    Beats, in order:

    1. The client POSTs its credentials to the route, again noting it as one of two session-free routes.
    2. Bean validation before the method body, with the 400 arm -- identical in shape to signup's, so keep it brief and say so rather than repeating the detail.
    3. The email lookup, as an `alt`. On the miss arm: the handler performs a credential comparison against a fixed hash whose result is thrown away, then fails. Draw this, and add a `Note` explaining in plain language why a discarded comparison exists at all -- without it, a request for an unregistered address would come back measurably faster than one for a registered address, which would let anyone enumerate who has an account by timing alone. Say that the response body was already identical and that this closes the timing gap; a reader who does not know what BCrypt is should still understand the point. Cite the finding id and the scan date the way the existing diagram does.
    4. On the hit arm: the same shared helper as signup, drawn with the same aliases -- token from the user's id, provider comparison, minimal principal.
    5. The wrong-password arm off the provider, landing on 401.
    6. The strategy call, drawn as the composite it is and in the order it runs: the ceiling check first, against the live session count for this principal, and the session-id rotation second. Add a `Note` giving the reason for that order -- a login the ceiling is about to refuse must not rotate the caller's existing session id as a side effect of a failed request.
    7. The ceiling arm: at the limit, the strategy raises an exception which the helper's `Try` collapses to a plain failure, which the handler turns into the same credentials failure as every other arm. **This is the diagram's headline.** Draw it landing on a response that is visually identical to the wrong-password arm, and attach a `Note` stating in plain language that the two are deliberately indistinguishable -- same status, same code, same body -- and that this is a security decision (cite the decision id), not an oversight to work around. Add a second, short `Note` recording that the ceiling permits a small, bounded, self-correcting overshoot under genuinely simultaneous logins, referencing `SecurityConfiguration`'s Javadoc for the detail rather than re-deriving it.
    8. The success arm: context saved, session rows committed to Postgres as the response flushes, then 200 with the rotated session cookie and the same four-field body signup returns. Note the two differences from signup in one line: the status, and the absence of a `Location` header.

    **Second, append two sections to `docs/AUTH_FLOWS.md`.**

    A `## Sign in` section, structured exactly like the signup section: route and method, request body, a status table with codes and causes, the embedded diagram in the house pattern, and a closing "what this means for a test" paragraph. That paragraph must state the thing a test author cannot infer from a status table -- that a 401 on signin has three distinct causes it cannot tell apart, so a failing login in a test is not evidence of a wrong password.

    Then a `## What will break your E2E suite` section -- the highest-value part of this document, and the reason it exists as its own file. Cover, each with the concrete number and the property or constant it comes from:

    - **The concurrent-session ceiling.** State the limit, that it is per user and counted from live rows in the shared session store rather than per-process, and spell out the consequence: a suite running more parallel workers than the limit against a single seeded fixture user will get 401s from the excess workers, indistinguishable from a wrong password. Give the two mitigations -- one fixture user per worker, or an explicit logout between tests -- and name the logout route.
    - **Two different session lifetimes, deliberately.** The cookie's max-age and the server-side session timeout differ by a wide margin; give both numbers and both property names, and state the consequence in the direction that actually bites: the browser discards the cookie long before the server expires the session, so a long-running suite on one login starts getting the unauthenticated 401 while the server-side session is still perfectly alive.
    - **The cookie's SameSite policy**, with its consequence for a harness driving the app from a different site.
    - **Credentialed CORS.** The allow-list is explicit and non-wildcard because credentials are allowed -- the spec forbids a wildcard once they are. Name the property that holds it, give the defaults, and state that a test origin absent from the list will have its responses dropped by the browser even though the server answered normally. Name the allowed methods.
    - **The session id rotates on every successful authentication**, so a test must not cache or assert a stable session id across a login.
    - **CSRF is disabled**, so there is no token to fetch before a mutation -- state it, because a test author coming from a Spring app will look for one.
    - **401 versus 403.** No session at all is answered from the filter chain with one code and a fixed detail message; a valid session touching someone else's resource is answered by the application with a different code. Give both codes and state that the first never reaches application code, so a 401 never means "your data was wrong", it means "you were not signed in".

    Close that section with a one-line pointer to `ARCHITECTURE.md`'s four-way rejection diagram for the full 401/403/400/409 split, so this document does not try to re-own it.
  </action>
  <verify>
    <automated>
cd "C:/Dev/Repos/kanban-board-backend" &&
npx -y @mermaid-js/mermaid-cli@11 -i docs/diagrams/auth-signin-scenario.mmd -o docs/diagrams/auth-signin-scenario.png &&
grep -q 'sequenceDiagram' docs/diagrams/auth-signin-scenario.mmd &&
for lit in 200 401 400 BAD_CREDENTIALS JSESSIONID; do
  grep -v '^[[:space:]]*%%' docs/diagrams/auth-signin-scenario.mmd | grep -q -- "$lit" || { echo "SIGNIN DIAGRAM MISSING: $lit"; exit 1; }
done &&
for f in docs/diagrams/auth-signin-scenario.mmd docs/AUTH_FLOWS.md; do
  for c in $(grep -ohE '[A-Z][A-Z_]{5,}' "$f" | sort -u); do
    case "$c" in VALIDATION_FAILED|DUPLICATE_RESOURCE|DATA_INTEGRITY_VIOLATION|BAD_CREDENTIALS|ACCESS_DENIED|ENTITY_NOT_FOUND|UNAUTHENTICATED|OPTIMISTIC_LOCK_CONFLICT|CONSTRAINT_VIOLATION|MALFORMED_REQUEST_BODY|ILLEGAL_ARGUMENT|INTERNAL_ERROR)
      grep -qE "^[[:space:]]*$c[,;]" src/main/java/com/vrudenko/kanban_board/constant/ErrorCode.java || { echo "NOT AN ErrorCode MEMBER in $f: $c"; exit 1; };; esac
  done
done &&
grep -q 'MAX_CONCURRENT_SESSIONS = 2' src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java &&
grep -q 'server.servlet.session.cookie.max-age=600' src/main/resources/application.properties &&
grep -q 'spring.session.timeout=180m' src/main/resources/application.properties &&
grep -q 'server.servlet.session.cookie.same-site=strict' src/main/resources/application.properties &&
grep -q 'app.cors.allowed-origins=http://localhost:5173,http://localhost:3000' src/main/resources/application.properties &&
grep -q 'SIGNIN = "/signin"' src/main/java/com/vrudenko/kanban_board/constant/ApiPaths.java &&
for lit in 600 180m strict UNAUTHENTICATED ACCESS_DENIED app.cors.allowed-origins JSESSIONID logout; do
  grep -qi -- "$lit" docs/AUTH_FLOWS.md || { echo "AUTH_FLOWS.md MISSING HAZARD FACT: $lit"; exit 1; }
done &&
grep -q 'diagrams/auth-signin-scenario.png' docs/AUTH_FLOWS.md &&
grep -q 'diagrams/auth-signin-scenario.mmd' docs/AUTH_FLOWS.md &&
test "$(grep -c '^## ' docs/AUTH_FLOWS.md)" -ge 3 &&
if grep -nEi 'BEGIN [A-Z ]*PRIVATE KEY|xox[baprs]-|AKIA[0-9A-Z]{16}' docs/AUTH_FLOWS.md docs/diagrams/auth-signin-scenario.mmd; then echo "SECRET-SHAPED LITERAL FOUND"; exit 1; fi &&
test "$(grep -cE '^[[:space:]]*(participant|actor)[[:space:]]' docs/diagrams/auth-signin-scenario.mmd)" -le 10 &&
node -e "const fs=require('fs'),d='docs/diagrams',w=f=>fs.readFileSync(d+'/'+f).readUInt32BE(16),t=w('auth-signin-scenario.png'),m=Math.max(...fs.readdirSync(d).filter(f=>f.endsWith('.png')&&!f.startsWith('auth-')).map(w));console.log('new width',t,'| existing max',m);if(t>m){console.error('RENDER WIDER THAN EVERY EXISTING DIAGRAM');process.exit(1)}" &&
test docs/diagrams/auth-signin-scenario.png -nt docs/diagrams/auth-signin-scenario.mmd &&
git diff --quiet -- src/ build.gradle docs/diagrams/architecture-signin-scenario.mmd docs/diagrams/architecture-signin-scenario.png &&
echo "OK: signin diagram renders, required literals present, every error code in both new files is a real enum member, ceiling/lifetime/samesite/cors/route values all still match their source of truth, hazard facts present, no secret-shaped literal, render newer than source, production code and the pre-existing signin diagram untouched"
    </automated>
    <human-check>Open `docs/diagrams/auth-signin-scenario.png` and confirm the wrong-password arm and the session-ceiling arm visibly terminate in the same response, so the indistinguishability reads as intentional rather than as a drawing error. Then read the hazards section start to finish as if you were writing the Playwright suite, and confirm each item states a consequence, not just a fact.</human-check>
  </verify>
  <done>
    `docs/diagrams/auth-signin-scenario.mmd` is a Scenario (+1) view of `POST /api/signin` with the unknown-email timing-equalizer arm and its plain-language explanation, the wrong-password arm, the strategy drawn as ceiling-then-rotation with the reason for that order, the ceiling arm terminating in a response identical to the wrong-password arm with the indistinguishability called out as deliberate, the bounded-overshoot note deferring to the Javadoc, and the 200 success arm with its rotated cookie and the two stated differences from signup. `docs/AUTH_FLOWS.md` has a `## Sign in` section matching the signup section's structure and a `## What will break your E2E suite` section covering the ceiling, both session lifetimes, SameSite, credentialed CORS with its origin list and methods, session-id rotation, disabled CSRF, and the 401-versus-403 split -- each with its number and its source, each stating a consequence. Every error code named across both new files is a real `ErrorCode` member, and every quoted configuration value still matches the file it came from. Both renders are fresh and within the existing width precedent. Nothing under `src/` and neither pre-existing signin diagram file is modified.
  </done>
</task>

<task type="auto" tdd="false">
  <name>Task 3: Reconcile the two signin diagrams and index the new document</name>
  <files>docs/ARCHITECTURE.md, README.md</files>
  <read_first>
    Read `docs/ARCHITECTURE.md`'s "Scenario — signin and session establishment" section in full, including the "Simplified:" paragraph after the embed that already describes signup's differences in prose. Read `README.md`'s doc-index table near the end of the file and match its column structure and tone exactly.
  </read_first>
  <action>
    Two scoped `Edit` calls. No whole-file rewrite of either file, and no change to any other section.

    In `docs/ARCHITECTURE.md`'s signin scenario section: add a short cross-reference so a reader who wants the client's view is sent to it rather than trying to extract it from a security diagram. Place it where it will actually be read -- with the existing framing before the embed, not appended at the end of the section. It must say what the new document is, who it is for, and what it covers that this one does not (signup drawn in full, and the client-observable session/cookie/CORS facts). One or two sentences.

    Then look at the existing "Simplified:" paragraph, which currently carries signup's differences as prose precisely because signup had no diagram. It now has one. Do not delete that paragraph -- its content is still correct and still useful in place -- but add a clause pointing at the signup diagram as the fuller treatment, so the prose stops being the only description of a flow that is now drawn.

    In `README.md`'s doc-index table: add one row for the new document, matching the existing rows' style. The description column should name the audience, since that is what distinguishes it from the ARCHITECTURE.md row directly above it.

    Change nothing else in either file.
  </action>
  <verify>
    <automated>
cd "C:/Dev/Repos/kanban-board-backend" &&
grep -q 'AUTH_FLOWS.md' docs/ARCHITECTURE.md &&
grep -q 'AUTH_FLOWS.md' README.md &&
grep -q 'diagrams/auth-signup-scenario' docs/ARCHITECTURE.md &&
test "$(grep -c '^## ' docs/ARCHITECTURE.md)" = "$(git show HEAD~2:docs/ARCHITECTURE.md 2>/dev/null | grep -c '^## ' || git show HEAD:docs/ARCHITECTURE.md | grep -c '^## ')" &&
grep -q 'diagrams/architecture-signin-scenario.png' docs/ARCHITECTURE.md &&
grep -q 'diagrams/architecture-signin-scenario.mmd' docs/ARCHITECTURE.md &&
for f in docs/AUTH_FLOWS.md docs/diagrams/auth-signup-scenario.mmd docs/diagrams/auth-signup-scenario.png docs/diagrams/auth-signin-scenario.mmd docs/diagrams/auth-signin-scenario.png; do test -f "$f" || { echo "MISSING ARTIFACT: $f"; exit 1; }; done &&
node -e "const fs=require('fs');const md=fs.readFileSync('docs/AUTH_FLOWS.md','utf8');for(const m of md.matchAll(/\]\((diagrams\/[^)]+)\)/g)){if(!fs.existsSync('docs/'+m[1])){console.error('BROKEN LINK: '+m[1]);process.exit(1)}}console.log('all AUTH_FLOWS.md diagram links resolve')" &&
git diff --quiet -- src/ build.gradle src/main/resources/application.properties docs/diagrams/architecture-signin-scenario.mmd docs/diagrams/architecture-signin-scenario.png &&
test "$(git diff --name-only HEAD | grep -cvE '^(docs/|README\.md|\.planning/)')" = "0" &&
echo "OK: reciprocal cross-links in place, ARCHITECTURE.md section count unchanged, existing embeds still resolve, all five new artifacts present, every AUTH_FLOWS.md diagram link resolves, only docs/README/.planning modified"
    </automated>
  </verify>
  <done>
    `docs/ARCHITECTURE.md`'s signin scenario section points a reader at `docs/AUTH_FLOWS.md` before the embed, naming its audience and what it adds, and its "Simplified:" paragraph now points at the signup diagram as the fuller treatment while keeping its existing prose. `README.md`'s doc-index table has one new row for `docs/AUTH_FLOWS.md` naming its audience. Both documents' pre-existing links still resolve, `ARCHITECTURE.md`'s section count is unchanged, and every diagram link in `AUTH_FLOWS.md` resolves to a file that exists. Nothing outside `docs/`, `README.md`, and `.planning/` is modified.
  </done>
</task>

</tasks>

<verification>
1. `./gradlew spotlessCheck` and `./gradlew test` are not required -- no `src/**/*.java` file is touched. Confirm that is actually true rather than assumed: `git diff --name-only HEAD | grep -c '\.java$'` returns 0.
2. `.githooks/pre-commit` runs `gitleaks` over the staged diff before anything else. Each commit must pass it with no new `.gitleaks.toml` entry. If it fires, treat it as a real finding and replace the offending literal with a clearly-fake placeholder -- do not add an exemption for a documentation file.
3. Each `.mmd` and its `.png` are staged in the same commit, and each PNG's mtime is newer than its source.
4. Final accuracy pass, by file and line rather than by recollection: re-read both rendered PNGs against `AuthenticationController.java`'s `signin`, `signup`, and `authenticate` methods end to end, confirming the order of `onAuthentication` and `saveContext`, the status code on each failure arm, and that signup's rollback arm is drawn as ending in a credentials failure rather than an access-denied one.
5. Confirm `docs/diagrams/architecture-signin-scenario.mmd` and `.png` are byte-identical to their pre-task state (`git diff --quiet` on both), and that this change added no second claim that contradicts them.
</verification>

<success_criteria>
- `docs/AUTH_FLOWS.md` exists, declares its audience and its Scenarios (+1) view, and carries both diagrams in the established house pattern plus a hazards section written for someone about to write a Playwright suite.
- Both diagrams depict the real implementation: the strategy call site sits between the successful authentication and the context save; the token is built from the user's id; the session rows commit after the response flushes.
- The signin diagram makes the collapsed 401 unmistakable -- the wrong-password arm and the session-ceiling arm terminate in the same response, marked as deliberate.
- The signup diagram carries the three arms signin does not have (400 validation, 409 duplicate with its constraint backstop, and the auto-rollback ending in 401), plus the note that the ceiling cannot reject a signup.
- Every error code named anywhere in the new documentation is a real `ErrorCode` member, verified by extraction rather than by a hand-maintained list, and every configuration value quoted in the hazards section is grep-gated against the file it came from.
- The pre-existing `architecture-signin-scenario` diagram is byte-identical, and the two signin diagrams reference each other with their audiences stated.
- `README.md`'s doc index lists the new document.
- No file under `src/`, no build file, and no properties file is modified.
</success_criteria>

<output>
Create `.planning/quick/260817-tvd-create-authentication-sequence-diagrams-/260817-tvd-SUMMARY.md` when done.
</output>
