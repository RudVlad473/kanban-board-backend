---
phase: quick-260806-nyj
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - README.md
autonomous: true
requirements: [QUICK-260806-nyj]

estimate:
  tokens: 22000
  raw_tokens: 22000
  tasks: 1
  confidence: medium

must_haves:
  truths:
    - Every capability claim in README.md is verifiable against a named file, Gradle task, config property, or test class in this repository.
    - README.md contains no claim that the CI pipeline deploys anywhere — the deploy-to-ec2 job is `if: false` and its host is deleted.
    - README.md contains no claim of a `GET /boards/{boardId}/full` endpoint, Redis, Kubernetes, or Actuator/observability — all are deferred or unstarted.
    - A reader gets the "why" for each architectural decision in the same sentence as the decision, not as a separate rationale section.
    - The current deploy-target gap is stated honestly and framed as the in-progress v1.2 Phase 5 migration it actually is.
    - The duplicate "Running locally" / "Running it locally" sections are collapsed into one.
  artifacts:
    - README.md
  key_links:
    - README.md -> docs/LOCAL_DEV.md (full local runbook, already referenced by the current README)
    - README.md -> docs/CODE_STYLE.md (judgement-level rules the formatter cannot check)
    - README.md -> docs/DIAGRAM_CONVENTIONS.md (the convention the README's own diagram must obey)
---

<objective>
Replace README.md with an accurate, portfolio-grade description of what this repository actually
contains as of v1.2 Phase 04.1.

Purpose: the current README describes a mid-2026 snapshot of the project. Since then the repo has
gained Flyway-managed schema history, an Avro/Schema-Registry-governed Kafka pipeline with
dead-lettering and idempotent consumption, Testcontainers-backed E2E coverage, ArchUnit layering
enforcement, and ErrorProne compile-time gating — none of which the README mentions. It also
carries one outright false claim (that CI deploys to EC2; that job is `if: false` and the host is
deleted) and two duplicated "running locally" sections that contradict each other. The intended
reader is someone evaluating the author's engineering depth, so an unverifiable or stale claim is
worse than an omission.

Output: a single rewritten README.md. No other file changes.
</objective>

<approach_analysis>

## Alternate Approaches Considered

**Approach A (picked): Rewrite README.md as a single self-contained document, organised by concern
(domain → concurrency → event pipeline → schema governance → data/schema management → testing →
build gates → running it → status), with every claim carrying its mechanism inline.**

**Approach B: Keep the README short (a "front door" of ~40 lines) and push all depth into new
`docs/ARCHITECTURE.md` / `docs/TESTING.md` files linked from it.**

**Approach C: Incrementally patch the existing README — fix the EC2 falsehood, delete the duplicate
section, append bullets for the missing technologies.**

## Trade-off Matrix

| Approach | Pros / Cons | Why Picked / Rejected |
|----------|-------------|----------------------|
| **A — single rewritten README, depth inline** | **Pros:** the stated audience (a hiring reader) reads exactly one file and usually only on GitHub's rendered landing page; depth is where they already are; one file to keep true. **Cons:** a longer README (~150 lines), and every future architectural change has one more place to update. | **PICKED.** The failure mode this task exists to fix is *depth invisible to the reader*. Splitting depth into sibling docs reproduces that failure by one click. Length is acceptable when it is scannable and every line is load-bearing. |
| **B — thin README + new architecture docs** | **Pros:** keeps the landing page skimmable; matches the repo's existing habit of scoped `docs/` files (`CODE_STYLE.md`, `LOCAL_DEV.md`, `SESSION_LESSONS.md`). **Cons:** creates two new documents to keep in sync with a codebase still mid-milestone; a reader evaluating depth in 90 seconds does not open the second file; overlaps `.planning/codebase/` and `docs/plans/backend-modernization/`, which already serve the "long-form internal record" role. | **REJECTED.** The repo does not lack long-form architectural writing — it has an unusual amount of it. What it lacks is a *front door* that reflects it. |
| **C — incremental patch** | **Pros:** smallest diff; lowest risk of introducing a new false claim. **Cons:** leaves the structural problem (a bare comma-separated stack line, a "What's not done yet" section that predates two shipped milestones) intact; the document's organising principle is what is stale, not just its facts. | **REJECTED.** Patching a document whose structure is the defect produces a longer stale document. |

## Non-obvious trade-offs

- **Verifiability is the load-bearing constraint, not completeness.** A hiring reader who opens the
  repo and cannot find the thing the README claims discounts everything else in it. Every claim in
  the new README must name the artifact that proves it (`SchemaCompatibilityE2ETest`,
  `registerSchemas`, `V1__init.sql`, `LayeringArchTest`) so any statement is one grep from
  confirmation. This is also what makes staleness self-limiting: a claim that names a deleted class
  breaks visibly.
- **Three explicit non-claims are as important as the claims.** `GET /boards/{boardId}/full` is
  deferred to v2 (`.planning/STATE.md`, Deferred Items); the CI deploy job is `if: false` with its
  host deleted; Redis / Kubernetes / Actuator are unstarted epics in
  `docs/plans/backend-modernization/`. Each is plausible enough to write by accident from the
  planning documents, and each would be a discoverable falsehood.
- **`GET /api/docs` currently returns HTTP 500** (observed during Phase 04.1's checkpoint, recorded
  under Operator Next Steps in `.planning/STATE.md`). springdoc-openapi is genuinely a dependency
  and genuinely wired, so it belongs in the stack list — but the README must not instruct a reader
  to open a Swagger URL that errors. Name the dependency; do not sell the endpoint.
- **Diagram scope.** `docs/DIAGRAM_CONVENTIONS.md` requires a diagram to be deliberately *one*
  Kruchten view. The README's diagram is a **Process View** — the runtime path of a mutation across
  the request thread, the async publish pool, the broker, and the consumer thread. It must not drift
  into deployment (what runs on what hardware), which is precisely the view Phase 5 has not
  delivered yet.
- **Build/performance surface: none.** Spotless targets `src/**/*.java`; a markdown-only change is
  outside `spotlessCheck` entirely and compiles nothing. The only real cost is the `.githooks/`
  pre-commit hook, which runs `spotlessApply` then the `fastTest` suite on any commit — minutes of
  work for a zero-Java change, so the commit call needs an explicitly generous timeout
  (`docs/SESSION_LESSONS.md`, lesson 2's corollary).
- **Security — information disclosure.** README.md is the most public file in the repository. It
  must carry no hostnames, connection strings, credentials, Docker Hub tokens, or absolute
  machine-local paths. Environment variable *names* (`DB_HOST`, `SCHEMA_REGISTRY_URL`) are fine;
  values are not. `.env` is git-ignored and must stay unreferenced except through `.env.example`.

## Data-flow mechanism (3 sentences)

This plan writes one markdown file and nothing reads it at build or run time, so there is no code
path to trace. The only executable consequence is the commit itself, which triggers
`.githooks/pre-commit` (`spotlessApply`, then the `fastTest` Gradle task). Verification is therefore
grep-based against the repository's own artifacts — each claim in the new README must resolve to a
file, task, property, or test class that exists.

</approach_analysis>

<context>
@.planning/STATE.md
@docs/CODE_STYLE.md
@docs/DIAGRAM_CONVENTIONS.md
@docs/LOCAL_DEV.md
</context>

<tasks>

<task type="auto">
  <name>Task 1: Rewrite README.md</name>
  <files>README.md</files>
  <read_first>
    - `README.md` — the document being replaced; preserve its accurate parts (layered architecture, ownership verification as its own service, MapStruct rationale, the deliberate testing split, the honest "what's missing" habit) and its plain, unhyped register.
    - `.planning/ROADMAP.md` — authoritative milestone status: v1.0 and v1.1 shipped, v1.2 in progress with Phase 4 and Phase 04.1 complete and Phase 5 not started.
    - `.planning/STATE.md` — Deferred Items (the `/full` endpoint is v2, not built) and Operator Next Steps (the `GET /api/docs` 500).
  </read_first>
  <action>
Replace `README.md` wholesale. Sections, in order:

1. **Title + two-sentence framing** — what the API is (users → boards → columns → tasks →
   subtasks, session auth, ownership-based access control) and what the repository is being used
   for (going past CRUD-tutorial depth into concurrency, event-driven integration, and schema
   governance).
2. **Stack** — grouped by concern with versions, not a bare comma list.
3. **Architecture** — layering, `OwnershipVerifierService` as the single access-control chain,
   MapStruct, ULID ids, the `@PreAuthorize` + `@CurrentUserId` controller contract.
4. **Concurrency: optimistic locking** — `@Version` on `TaskEntity`/`ColumnEntity`, required client
   `version` on `UpdateTaskRequestDTO`/`MoveTaskRequestDTO`, why the explicit in-service check is
   needed *in addition* to `@Version` (a load-then-save flow otherwise cannot detect a stale read),
   `OptimisticLockingFailureException` → HTTP 409, proven by `TaskLockingE2ETest` /
   `ColumnLockingE2ETest`.
5. **Event-driven activity feed** — sealed `ActivityEvent` interface with 5 records; publication via
   `ApplicationEventPublisher`; `KafkaEventPublisher` on `@TransactionalEventListener(AFTER_COMMIT)`
   + `@Async` so a committed mutation's HTTP outcome never depends on broker reachability; bounded
   producer timeouts; the exhaustive switch with no `default` arm so a sixth event type is a compile
   error; `ActivityLogRecorder` idempotency (`existsByEventId` fast path + a
   `DataIntegrityViolationException` backstop that re-checks before absorbing); retry-then-dead-letter
   via `DefaultErrorHandler`; the byte-preserving dead-letter template. Include the **Process View**
   mermaid diagram here, labelled as such per `docs/DIAGRAM_CONVENTIONS.md`.
6. **Schema governance** — 5 `.avsc` files under `src/main/avro`, Gradle codegen, Confluent Avro
   serde, `auto.register.schemas=false` with registration owned by the `registerSchemas` task,
   `RecordNameStrategy` so all 5 types version independently on one topic, BACKWARD compatibility
   with a proven rejection test.
7. **Schema management** — Flyway `V1__init` → `V4__add_password_hash_not_null` reconstructing real
   evolution rather than a flattened baseline, `ddl-auto=validate` outside the test profile and what
   that buys.
8. **Testing** — the four categories and why each exists; the ArchUnit rules; the query-count
   regression technique and the measured N+1 result.
9. **Build quality gates** — Spotless, ErrorProne (including the stricter test-source promotion),
   `fastTest`, the auto-wired `core.hooksPath`.
10. **API surface** — table or list of routes.
11. **Running it locally** — ONE section: `cp .env.example .env` + `docker compose up`, what comes
    up, the 5433 rationale, `./gradlew test` (needs Docker for Testcontainers) and `./gradlew
    fastTest`. Link `docs/LOCAL_DEV.md`.
12. **Current status / what's next** — v1.0 and v1.1 shipped; v1.2 Phase 4 + 04.1 done, Phase 5
    (Oracle Cloud A1 + Neon + Redpanda + Caddy + CI/CD rewrite) in flight; state plainly that CI
    currently tests, builds, and pushes a Docker image only, and that the deploy job is disabled
    because the AWS host was deliberately torn down on cost grounds.

Register: match the existing README — plain, specific, no marketing adjectives, no emoji, no
badges. Every architectural claim carries its reason in the same sentence. Prefer naming the class
or Gradle task over describing it abstractly.

Hard constraints:
- Do NOT claim a `GET /boards/{boardId}/full` endpoint exists.
- Do NOT claim CI deploys anywhere.
- Do NOT claim Redis, Kubernetes, or Actuator/observability.
- Do NOT direct the reader to open `/api/docs` (it currently 500s); naming springdoc-openapi as a
  dependency is fine.
- No credentials, hostnames, connection strings, or absolute machine-local paths.
  </action>
  <verify>
    <automated>cd "$(git rev-parse --show-toplevel)" &amp;&amp; test -f README.md &amp;&amp; test "$(grep -c 'Running it locally\|Running locally' README.md)" = "1" &amp;&amp; ! grep -qi 'EC2 instance' README.md &amp;&amp; ! grep -q 'boards/{boardId}/full' README.md &amp;&amp; ! grep -qi 'kubernetes\|actuator' README.md &amp;&amp; grep -q 'SchemaCompatibilityE2ETest' README.md &amp;&amp; grep -q 'LayeringArchTest' README.md &amp;&amp; grep -q 'registerSchemas' README.md &amp;&amp; grep -q 'V4__add_password_hash_not_null' README.md &amp;&amp; grep -q 'TaskLockingE2ETest' README.md &amp;&amp; grep -q 'docs/LOCAL_DEV.md' README.md &amp;&amp; grep -q 'Process View' README.md &amp;&amp; echo TASK1_OK</automated>
    <human-check>Read it top to bottom as an outside reviewer with 90 seconds: does the depth land before they scroll away, and is there any sentence you could not immediately prove by opening a named file?</human-check>
  </verify>
  <done>`README.md` is replaced, contains exactly one local-run section, names every claimed artifact, carries the Process View diagram, states the deploy-target gap honestly, and makes none of the four forbidden claims.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| private repo state -> public landing page | README.md is the most-read and most-indexed file in the repo; anything in it is effectively published |
| planning documents -> README claims | `.planning/` and `docs/plans/` describe both shipped and merely *planned* work; copying the latter into the README manufactures a false capability claim |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-nyj-01 | Information Disclosure | README.md | medium | mitigate | Task 1 constrains content to env-var *names* and repo-relative paths; no hostnames, connection strings, credentials, Docker Hub tokens, or machine-local absolute paths. `.env` stays unreferenced except via `.env.example`. |
| T-nyj-02 | Spoofing (capability misrepresentation) | README claims vs. codebase | high | mitigate | Four explicit non-claims enumerated in the task action; the automated verify greps assert their absence and assert the presence of the named proving artifacts. |
| T-nyj-03 | Tampering | none | n/a | accept | No executable file, build configuration, or agent-instruction file is modified — README.md is not loaded into any agent context or build path. |
| T-nyj-SC | Tampering | supply chain | n/a | accept | No dependency, package manifest, or lockfile changes. `build.gradle` untouched. Package Legitimacy Gate does not apply. |
</threat_model>

<verification>
Run from the repo root:

1. `grep -c 'Running it locally\|Running locally' README.md` returns `1` — the duplicated section is gone.
2. `grep -i 'EC2 instance' README.md` returns nothing — the false deploy claim is gone.
3. `grep 'boards/{boardId}/full' README.md` returns nothing — no phantom endpoint.
4. `grep -i 'kubernetes\|actuator' README.md` returns nothing — no unstarted-epic claims.
5. Each of `SchemaCompatibilityE2ETest`, `LayeringArchTest`, `registerSchemas`,
   `V4__add_password_hash_not_null`, `TaskLockingE2ETest` appears in README.md **and** resolves to a
   real artifact in the repository (spot-check each with `find`/`grep`).
6. `git diff --stat` shows README.md as the only changed file.
7. No Gradle verification applies: Spotless targets `src/**/*.java` and this change alters zero Java.

Commit note: the commit fires `.githooks/pre-commit` (`spotlessApply`, then `fastTest`) — minutes of
work on a docs-only change. Give the `git commit` call an explicit generous timeout (600000 ms)
rather than a default, per `docs/SESSION_LESSONS.md` lesson 2's corollary.
</verification>

<success_criteria>
- A reader evaluating engineering depth can, from README.md alone, name the concurrency-control
  strategy, the event-delivery guarantee, the schema-governance mechanism, and the four test
  categories — and can verify each by opening one named file.
- Zero claims that cannot be proven against this repository at this commit.
- The deploy-target gap is stated, attributed to the deliberate AWS teardown, and tied to the
  in-flight Phase 5 — not omitted and not spun.
- README.md is the only file changed.
</success_criteria>

<output>
Create `.planning/quick/260806-nyj-rewrite-readme-md-to-reflect-current-arc/260806-nyj-SUMMARY.md` when done.
</output>
