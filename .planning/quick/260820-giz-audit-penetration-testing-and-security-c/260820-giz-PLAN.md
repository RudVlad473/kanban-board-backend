---
phase: quick-260820-giz
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - .planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md (moved from pending/, Resolution appended)
  - .planning/todos/pending/2026-08-20-*.md (new — one per confirmed gap; exact count/names determined by Task 1's findings)
autonomous: true
requirements: [QUICK-260820-GIZ-AUDITPENTESTSECURITYCOVERAGE]

estimate:
  tokens: 70000
  raw_tokens: 70000
  tasks: 2
  confidence: low

must_haves:
  truths:
    - "Each of the 6 named candidate categories (CSRF, signin rate-limiting/brute-force, full-depth IDOR across Board->Column->Task->Subtask, dependency CVEs, security response headers, DTO mass-assignment) has an explicit verdict — covered / assumed-covered-but-unverified / genuinely-untested — backed by a specific file/test/line citation, not a summary judgment"
    - "All 10 OWASP API Security Top 10 (2023) categories are disposed of, not just the 6 named candidates — including an explicit N/A + reasoning for any category this app's shape does not expose"
    - "Every genuinely-untested or confirmed-gap category has exactly one new pending todo filed under .planning/todos/pending/, in this repo's existing frontmatter convention (created, title, area, severity, files)"
    - "Every category found adequate has its reasoning, with citations, recorded in the originating todo's Resolution section — never silently dropped"
    - "The dependency-CVEs category cross-references (never duplicates) the existing completed 2026-08-03-add-dependency-vulnerability-scan.md todo and the still-pending 2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md follow-up"
    - "The originating todo is moved from .planning/todos/pending/ to .planning/todos/completed/ with a resolved: date and a ## Resolution section"
    - "No production code (src/main, src/test) is modified by this plan — the entire deliverable is .planning/todos/ artifacts"
  artifacts:
    - .planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md
  key_links:
    - "SecurityConfiguration.csrf(AbstractHttpConfigurer::disable) + server.servlet.session.cookie.same-site=strict + CorsConfig's credentialed non-wildcard allowlist -> the CSRF verdict's actual reasoning chain"
    - "OwnershipVerifierService.verifyOwnershipOfColumn/Task/Subtask (walks up from the leaf path id only) -> ColumnController/TaskController/SubtaskController's (userId, leafId, ...) service calls, which never cross-check the URL's other ownership-chain path segments -> the IDOR chain-consistency finding"
    - "application.properties (no server.forward-headers-strategy) + Caddyfile (no header directive anywhere) -> the security-response-headers verdict (Spring Security's default HstsHeaderWriter only fires when request.isSecure() is true, which requires forwarded-proto trust this app does not configure)"
    - "The 12 Save*/Update*RequestDTO builder classes -> their MapStruct mappers (componentModel=SPRING, unmappedTargetPolicy=IGNORE) -> entity setters -> the mass-assignment verdict"
---

<objective>
Close `.planning/todos/pending/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md` by producing a real, cited OWASP API Security Top 10 verdict — covered / assumed-covered-but-unverified / genuinely-untested — for this codebase's actual test suite (`InjectionAttemptTest`, `AuthorizationGatingTest`, `AuthenticationTest`) and production security wiring (`SecurityConfiguration`, `GlobalExceptionHandler`, `CorsConfig`, `Caddyfile`), rather than continuing to assume coverage. The originating todo itself names the reason this matters: "two independently-true-sounding claims about this same security surface... turned out to contradict each other once actually measured."

Purpose: turn a six-item, "not a verified list — this needs its own investigation" backlog item into either closed-and-cited adequacy or actionable, specific follow-up todos — so no future session re-asks the same question from scratch.

Output: the originating todo closed with a `## Resolution` section covering all 10 OWASP categories, plus zero or more new pending todos (one per confirmed gap) in this repo's established frontmatter shape. No production code changes.

**The OWASP API Security Top 10 (2023)**, reproduced here so Task 1 needs no external lookup (quick-task scope has no research phase):

1. API1:2023 Broken Object Level Authorization (BOLA/IDOR)
2. API2:2023 Broken Authentication
3. API3:2023 Broken Object Property Level Authorization (includes mass assignment)
4. API4:2023 Unrestricted Resource Consumption
5. API5:2023 Broken Function Level Authorization
6. API6:2023 Unrestricted Access to Sensitive Business Flows
7. API7:2023 Server Side Request Forgery
8. API8:2023 Security Misconfiguration
9. API9:2023 Improper Inventory Management
10. API10:2023 Unsafe Consumption of APIs
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/todos/pending/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md
@.planning/todos/completed/2026-08-03-add-dependency-vulnerability-scan.md
@.planning/todos/pending/2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md
@.planning/todos/completed/2026-08-19-security-scan-yml-nvd-api-key-not-resolving.md
@src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
@src/main/java/com/vrudenko/kanban_board/config/CorsConfig.java
@src/main/java/com/vrudenko/kanban_board/service/OwnershipVerifierService.java
@src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
@src/main/resources/application.properties
@src/main/resources/application-test.properties
@Caddyfile
@src/test/java/com/vrudenko/kanban_board/security/InjectionAttemptTest.java
@src/test/java/com/vrudenko/kanban_board/security/AuthorizationGatingTest.java
@src/test/java/com/vrudenko/kanban_board/security/AuthenticationTest.java
</context>

<tasks>

<task type="auto">
  <name>Task 1: Trace all six candidate gaps + the full OWASP API Security Top 10 against actual code/test behavior</name>
  <files>(no files modified — read-only trace; sources are the &lt;context&gt; list above plus the 12 Save*/Update*RequestDTO classes under src/main/java/com/vrudenko/kanban_board/dto/**, GlobalExceptionHandler.java, and one MapStruct mapper, e.g. BoardMapper.java)</files>
  <action>
    Produce a cited verdict — covered / assumed-covered-but-unverified / genuinely-untested — for every one of the 10 OWASP categories above, seeded by (but not limited to) the 6 candidates the originating todo names. Every verdict must name the exact file, test method, or line that backs it — a claim with no citation is not an acceptable verdict for Task 2 to act on. This task writes no files; carry the verdict set forward into Task 2 in the same session.

    Specific things already found during planning that this task must confirm or falsify, not re-derive from scratch:

    **CSRF posture (-&gt; API8, arguably API2).** `SecurityConfiguration.securityFilterChain` calls `http.csrf(AbstractHttpConfigurer::disable)` with zero CSRF-token defense. Two independent mitigations already exist: `server.servlet.session.cookie.same-site=strict` (a modern browser never attaches this cookie to a cross-site request at all) and `CorsConfig`'s explicit non-wildcard origin allowlist with `allowCredentials(true)`. Determine whether any existing test proves either half of this reasoning empirically (a cross-origin request actually rejected; the cookie actually not replayed cross-site) — check for a `SessionCookieAttributesE2ETest`-style class and any CORS-rejection test. If none exists, the verdict is "assumed-covered-but-unverified," not "covered" — the reasoning being sound is not the same claim as the reasoning being tested.

    **Signin rate-limiting / brute-force (-&gt; API4, API2).** Confirm there is genuinely no rate-limiting implementation anywhere in `src/main` (a grep for rate-limit/throttle/bucket-style terms turned up nothing during planning). `AuthenticationTest.AntiEnumeration` and `.ConcurrentSessionCeiling` prove different properties (response indistinguishability; a 2-session ceiling per already-authenticated principal) — neither bounds the volumetric rate an unauthenticated caller can hit `/signin` at. If implementation is genuinely absent, this is not merely an untested category — determine whether the correct framing for Task 2 is "add rate limiting" (an implementation gap) rather than "add a test" (a test-only gap), since that changes the resulting todo's shape.

    **Full-depth IDOR, all four ownership chains (-&gt; API1, API3).** `AuthorizationGatingTest.CrossUserSweep` proves classic cross-user IDOR (403) across all 22 routes spanning Board-&gt;Column-&gt;Task-&gt;Subtask — read it and confirm this claim holds. Separately, trace `OwnershipVerifierService.verifyOwnershipOfColumn/Task/Subtask`: each resolves ownership by walking UP from the *leaf* path id (columnId/taskId/subtaskId) to the user, and `ColumnController`/`TaskController`/`SubtaskController`'s service calls (e.g. `ColumnService.findById(userId, columnId)`) pass only that leaf id — never the URL's other path segments (e.g. `boardId` in `PUT /boards/{boardId}/columns/{columnId}`). Confirm whether this means a caller who owns two boards (A and B) can address `PUT /boards/{A}/columns/{columnId-belonging-to-B}` and have it succeed against B's column, silently ignoring that the URL named board A — same-user chain confusion, not cross-user IDOR, and not exercised by `AuthorizationGatingTest` (which only varies the *user*, never the *chain consistency* of one owning user's own path segments). If confirmed, this is a distinct, real candidate the original todo's six items did not name explicitly but is a natural reading of "full-depth IDOR."

    **Dependency CVEs (-&gt; API9, tangential API8) — cross-reference, do not duplicate.** `.planning/todos/completed/2026-08-03-add-dependency-vulnerability-scan.md` already closed scanning/remediation (OWASP `dependency-check-gradle` wired report-only, Dependabot added, Spring Boot bumped 3.5.0-&gt;3.5.16, a real `commons-lang3` runtime CVE fixed). `.planning/todos/pending/2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md` already tracks the one known follow-up. The verdict here should be "covered — see existing todo family," and Task 2 must file nothing new for this category.

    **Security response headers (-&gt; API8).** No `http.headers(...)` customization exists in `SecurityConfiguration`, so Spring Security's default header set applies unless something disables it (nothing does) — but confirm exactly which defaults that implies for this Spring Security version (X-Content-Type-Options, X-Frame-Options, Cache-Control are typically unconditional; HSTS is conditional on `request.isSecure()`). Separately: no `server.forward-headers-strategy` or `ForwardedHeaderFilter` is configured anywhere (confirmed absent during planning), and the app sits behind Caddy's reverse proxy over plain HTTP internally (`Caddyfile`'s `reverse_proxy app:8080`, no host-published app port) — so `request.isSecure()` likely evaluates false even for a browser's real HTTPS request, meaning HSTS likely never gets written despite Spring Security defaulting to emit it. The `Caddyfile` itself adds zero `header` directives of its own (confirmed by full read during planning) — Caddy's automatic HTTPS does not imply automatic HSTS/CSP/X-Frame-Options. If a live curl or MockMvc header-read is feasible in this execution environment, use it to settle X-Content-Type-Options/X-Frame-Options presence cheaply (they don't depend on `isSecure()`); if live/production verification is not feasible from this environment, say so explicitly and let the code-level trace stand as the verdict basis. No CSP exists anywhere in either layer.

    **DTO mass-assignment (-&gt; API3).** List all 12 `Save*/Update*RequestDTO` classes (glob `src/main/java/com/vrudenko/kanban_board/dto/**/*RequestDTO.java`). For each, confirm every Jackson-bindable field is one the corresponding service/mapper intentionally consumes — specifically check whether any `Update*RequestDTO` exposes a field that could re-parent or re-own a resource (e.g. a `userId`/`ownerId`-shaped field), and confirm the direction MapStruct's `unmappedTargetPolicy = ReportingPolicy.IGNORE` actually protects (DTO field with no mapper target silently ignored, vs. entity field with no DTO source left untouched) by reading one representative mapper (`BoardMapper`).

    **The remaining OWASP categories (API5, API6, API7, API9 broader inventory, API10)** are not named by the originating todo — dispose of each explicitly rather than by omission. Likely N/A given this app's shape (no roles/admin functions -&gt; API5; no purchase/booking-style flow -&gt; API6; no user-controlled outbound URL fetch -&gt; API7; `AuthorizationGatingTest.Completeness`'s reflective route-discovery sweep already guards inventory drift -&gt; API9 likely adequate; only outbound integration is the internal Confluent schema registry/Kafka broker, not third-party API consumption -&gt; API10 likely N/A) — but confirm each with a quick targeted check (e.g. grep for any outbound `RestTemplate`/`WebClient`/`HttpClient` call driven by request input for API7/API10) rather than asserting N/A from this plan's guess alone.
  </action>
  <verify>
    <automated>grep -n "csrf(AbstractHttpConfigurer::disable)" src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java && ! grep -rIli "ratelimit\|bucket4j" --include=*.java src/main/java && ! grep -n "^[[:space:]]*header " Caddyfile</automated>
  </verify>
  <done>
    A verdict — covered / assumed-covered-but-unverified / genuinely-untested — exists for all 10 OWASP API Security Top 10 categories, each citing a specific file/test/line. The three automated premise checks above (CSRF still disabled in code, no rate-limiting library present, Caddy sets no headers) match what the verdict set assumes, confirming the trace was run against the current tree rather than stale memory.
  </done>
</task>

<task type="auto">
  <name>Task 2: File confirmed-gap todos, record adequacy in the Resolution, and close the originating todo</name>
  <files>.planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md, .planning/todos/pending/2026-08-20-*.md (new, one per confirmed gap)</files>
  <action>
    Consume Task 1's cited verdict set.

    **For every category verdicted "genuinely-untested" or a confirmed implementation gap:** create one new file `.planning/todos/pending/2026-08-20-&lt;slug&gt;.md`, matching this repo's existing frontmatter convention exactly — `created` (ISO 8601 timestamp), `title`, `area`, `severity`, `files` (the relevant source/test files Task 1 cited) — followed by `## Problem` (state the gap, citing Task 1's trace) and `## Solution` (a concrete direction, not a vague "investigate further" — mirror the shape of `.planning/todos/pending/2026-08-13-two-independent-session-ceiling-enforcers-coexist.md` or the originating todo's own "Suggested fix" style). Do not default every gap's `severity` to `security` mechanically — reason about actual exploitability the way Task 1's verdict does (e.g. a missing test for an already-mitigated property is a lower-severity gap than a genuinely-absent control), and state the reasoning for whatever severity is chosen. One file per category-level finding; do not bundle unrelated categories into one todo.

    **For every category verdicted "covered" or "adequate-but-worth-recording":** file nothing. Instead, fold the citation-backed reasoning directly into the originating todo's `## Resolution` section below.

    **Close the originating todo.** Move `.planning/todos/pending/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md` to `.planning/todos/completed/`, add `resolved: 2026-08-20` to its frontmatter, and append a `## Resolution` section matching this repo's established resolution style (quote the specific facts/citations Task 1 found, not summary theory — see `.planning/todos/completed/2026-08-03-add-dependency-vulnerability-scan.md` for the precedent: findings stated plainly, the todo's own premise corrected where it turns out wrong, follow-ups filed by name rather than buried in prose). The Resolution must:
      - State the verdict for all 10 OWASP categories, not only the 6 named candidates, each with its citation.
      - For the dependency-CVE category specifically, name `2026-08-03-add-dependency-vulnerability-scan.md` (completed) and `2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md` (still pending) by filename and state plainly that this audit adds nothing new there.
      - List every new todo filed in this task, one line each, with filename and a one-line reason.
      - Explicitly note that this plan made zero production-code changes — the audit's own scope was verification and triage, not remediation.
  </action>
  <verify>
    <automated>test -f .planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md && ! test -f .planning/todos/pending/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md && grep -q "^## Resolution" .planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md</automated>
  </verify>
  <done>
    The originating todo lives under `.planning/todos/completed/` (absent from `pending/`) with a `resolved:` date and a `## Resolution` section citing a verdict for all 10 OWASP categories. Every genuinely-untested/confirmed-gap category has exactly one new file under `.planning/todos/pending/` in this repo's frontmatter convention. `git status` shows no changes under `src/main` or `src/test`.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Investigation output -> future engineering trust | A "checked and found adequate" verdict, once written into the Resolution, becomes the thing a future session trusts without re-deriving it — this repo's own STATE.md "Pending Todos" section already works this way |
| New pending todo -> backlog | A filed todo becomes actionable work for a future session; an incorrectly-scoped or incorrectly-severed todo wastes that session's time |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-giz-01 | Repudiation | originating todo's `## Resolution` section | medium | mitigate | Every "covered"/"adequate" verdict must cite an exact file/test/line, never a bare claim — matching `2026-08-03-add-dependency-vulnerability-scan.md`'s established resolution convention, so a future reader can re-verify instead of trusting prose. Task 1's `<done>` and Task 2's action both require this explicitly. |
| T-giz-02 | Information disclosure | new todo files under `.planning/todos/pending/` | low | accept | Todo files are committed to a private repo already carrying prior security-finding narrative (the TOCTOU race, session-ceiling coexistence, and signin-timing-enumeration todos) at the same trust level; this plan follows the identical, already-accepted pattern rather than introducing a new disclosure surface. |
| T-giz-03 | Tampering | none — plan performs zero production code writes | n/a | accept | Both tasks are read/trace + `.planning/` artifact writes only; nothing under `src/main` or `src/test` is modified, so this plan carries no runtime tampering surface to mitigate. Task 2's `<done>` asserts `git status` shows no changes there. |

</threat_model>

<verification>
- Every one of the 10 OWASP API Security Top 10 categories has a verdict with a citation, not a bare label.
- The 6 originally-named candidates (CSRF, rate-limiting, IDOR depth, dependency CVEs, security headers, DTO mass-assignment) are each explicitly addressed within that set.
- The IDOR chain-consistency question (does `boardId` in a nested route actually get cross-checked against the leaf id's real parent, or only walked up to the owning user) is explicitly resolved, not skipped as "already covered by AuthorizationGatingTest."
- Dependency CVEs produces zero new todos and two explicit citations to the existing todo family.
- The originating todo is in `.planning/todos/completed/` with a `## Resolution` section; absent from `pending/`.
- `git status --short` shows changes confined to `.planning/todos/`.
</verification>

<success_criteria>
- No candidate gap is closed by assumption — every "adequate" verdict traces to a specific test or line of production config.
- Every genuine gap becomes a filed, actionable todo in this repo's existing convention, not a note buried in prose.
- The dependency-CVE thread stays a single source of truth (the existing todo family), not duplicated.
- A future session reading the closed todo's Resolution needs no re-investigation to know this codebase's actual OWASP API Security Top 10 posture.
</success_criteria>

<output>
Create `.planning/quick/260820-giz-audit-penetration-testing-and-security-c/260820-giz-SUMMARY.md` when done
</output>
