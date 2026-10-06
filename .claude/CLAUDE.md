<!--
Loads into every agent session in this repository, so every byte here is paid in every session.
Keep only what an agent cannot find by looking: an unwritten convention, the reason behind a choice,
a gotcha no config confesses. Put reference in docs/ and leave a one-line pointer here.
scripts/verify-instruction-budget.py caps this file and everything it imports; raising its ceiling
is a deliberate one-number edit with a dated reason. Keep this file free of GSD section markers:
GSD's generate-claude-md then leaves it untouched instead of regenerating the sections trimmed out.
-->

# Kanban Board Backend

Spring Boot 3.5 / Java 21 REST API for a kanban board (users, boards, columns, tasks, subtasks) with
session auth and per-user ownership checks. Read the matching doc before changing its area:

- Layering, ownership checks, the 401/403/400/409 error envelope, optimistic locking, query-count
  tests, bulk deletes, schema governance: docs/ARCHITECTURE.md.
- Signin, signup, the two-session ceiling, cookie versus server-side session lifetime: docs/AUTH_FLOWS.md.
- Writing or changing Java code, tests included: docs/CODE_STYLE.md. Reviewing a Java diff: docs/CODE_REVIEW_RUBRIC.md.
- Authoring or updating an architecture diagram (4+1 views): docs/DIAGRAM_CONVENTIONS.md.
- Deployment: production and nonprod run on single-node k3s reconciled by Flux from k8s/, and
  docker-compose.yml is local dev only; see docs/INFRA_ARCHITECTURE.md and docs/INFRA_RUNBOOK.md.

## Conventions and gotchas

- Services and controllers inject collaborators with field `@Autowired`, and `@Transactional` is
  `jakarta.transaction.Transactional`: match the neighbouring class.
- Entity ids are base36 RandFlake strings (`config/RandFlakeGenerator.java`), not ULIDs; the
  `ulid-creator` dependency in build.gradle is unused.
- Every test, `fastTest` included, runs on Testcontainers PostgreSQL built by the real Flyway
  migrations, so Docker must be running. Assert query counts through `AbstractAppTest.countQueries`,
  which reads `getPrepareStatementCount()`; `getQueryExecutionCount()` misses `findById()`.
- Write code comments as plain text: identifiers by bare name, a blank comment line between
  paragraphs, `- ` for each list item (docs/CODE_STYLE.md rule 14, enforced by scripts/verify-comments.py).
- Every commit runs `.githooks/pre-commit`, about 4 minutes: gitleaks on the staged diff, the comment
  lint, the instruction-file budget, `spotlessCheck` (`./gradlew spotlessApply` fixes it) and
  `fastTest`. Gitleaks refuses a credential-shaped value anywhere, `.planning/` prose included; a
  genuine false positive gets a narrow, evidence-cited entry in `.gitleaks.toml`.
- Inspecting live output (the OpenAPI document, a real HTTP response): run the app on the host and
  `curl` it rather than writing a throwaway test class. Commands and their port and path gotchas:
  docs/LOCAL_DEV.md, section "Inspecting live output".

## Wiki (karpathy-llm-wiki)

The vendored `karpathy-llm-wiki` skill is rooted at `docs/`: its raw/ is `docs/raw/` and its wiki/
is `docs/wiki/`. Lint: `python3 .claude/skills/karpathy-llm-wiki/scripts/check_evidence.py docs`.
Everything else under `docs/` stays authoritative until a cleanup deletes the originals, so edit
those rather than the wiki copies. One "article has no Raw field" lint error per migrated article is
the expected baseline, not a regression.

## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:

- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.

## GSD Execution Directives

- Source `.dev/gsd-run.sh` instead of re-pasting the runtime resolver in bash blocks: `. ./.dev/gsd-run.sh && gsd_run query ...`.
- BEFORE creating or approving any PLAN.md:
  1. Document 2 alternate technical approaches considered.
  2. Output a 3-column Trade-off Matrix: [Approach | Pros/Cons | Why Picked].
  3. Detail any non-obvious performance, memory, or security trade-offs (time complexity, state invalidation risks, etc.).
- NEVER auto-execute code blocks without explaining the core data-flow mechanism in 3 sentences or less.
- Operational lessons from past GSD sessions — git hygiene during phase execution — are recorded in [`docs/SESSION_LESSONS.md`](../docs/SESSION_LESSONS.md); read it before starting or resuming a phase-execution session.
