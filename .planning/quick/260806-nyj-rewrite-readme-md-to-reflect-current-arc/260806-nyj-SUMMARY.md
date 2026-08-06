---
phase: quick-260806-nyj
plan: 01
subsystem: docs
tags: [readme, documentation, portfolio, architecture-overview]

# Dependency graph
requires: []
provides:
  - README.md — accurate, artifact-referenced description of the codebase as of v1.2 Phase 04.1
affects: [docs]

# Actuals (#2632)
actuals:
  tokens: 1100
  tasks: 1
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "README.md: every capability claim names the class, Gradle task, migration, or test class that proves it — staleness becomes self-limiting because a claim naming a deleted artifact breaks visibly"
    - "README.md diagram is scoped to exactly one Kruchten view (Process View), per docs/DIAGRAM_CONVENTIONS.md, and is render-verified with mermaid-cli before commit rather than assumed to parse"

key-files:
  created: []
  modified:
    - README.md

key-decisions:
  - "Approach A (single rewritten README with depth inline) picked over splitting depth into new docs/ARCHITECTURE.md + docs/TESTING.md (Approach B) or incrementally patching the existing file (Approach C). The defect being fixed was depth invisible to the reader; B reproduces that failure one click away, and C leaves the document's stale organising principle intact. Full trade-off matrix in the plan's <approach_analysis>."
  - "Four claims explicitly forbidden and grep-asserted absent: GET /boards/{boardId}/full (deferred to v2, never built), any current CI deploy target (deploy-to-ec2 is `if: false`, host deleted), Redis/Kubernetes/Actuator (unstarted epics), and any instruction to open /api/docs (currently returns HTTP 500 per STATE.md Operator Next Steps). Each is plausible enough to write by accident from the planning documents and each would be a discoverable falsehood."
  - "Two genuine API gaps discovered while verifying the route table against the live controllers are stated in the README rather than omitted: board creation exists only as UserService.addBoardByUserId with no HTTP endpoint (callers are tests only), and columns have no delete route."

patterns-established:
  - "README.md claim discipline: no capability statement without a named, greppable artifact backing it."

requirements-completed: [QUICK-260806-nyj]

coverage:
  - id: D1
    description: "README.md rewritten to cover layering/ownership, optimistic locking, the event pipeline and its failure paths, Avro schema governance, Flyway history, the four test categories, and build gates — each with its rationale inline"
    requirement: "QUICK-260806-nyj"
    verification:
      - kind: other
        ref: "grep-based automated verify block in 260806-nyj-PLAN.md Task 1 — each of SchemaCompatibilityE2ETest, LayeringArchTest, registerSchemas, V4__add_password_hash_not_null, TaskLockingE2ETest present in README AND resolving to a real repo artifact (15/15 named artifacts confirmed resolvable)"
        status: pass
    human_judgment: true
    rationale: "Whether the depth lands for a 90-second outside reader is a judgment the grep gate cannot make; the plan's <human-check> reserves it."
  - id: D2
    description: "No false or unprovable claims: duplicate run-section collapsed to one, EC2 deploy claim removed, no /full endpoint, no Redis/Kubernetes/Actuator, no /api/docs directive"
    requirement: "QUICK-260806-nyj"
    verification:
      - kind: other
        ref: "grep counts — run-section=1, 'EC2 instance'=0, 'boards/{boardId}/full'=0, kubernetes|actuator=0, redis=0, swagger-ui|/api/docs=0; secrets/hostname/absolute-path scan clean"
        status: pass
    human_judgment: false
  - id: D3
    description: "Process View mermaid diagram renders correctly and fits a README content column"
    requirement: "QUICK-260806-nyj"
    verification:
      - kind: other
        ref: "rendered with @mermaid-js/mermaid-cli@11 to SVG and PNG before commit; initial flowchart LR version rendered too wide to be legible at GitHub content width and was reworked to flowchart TD, then re-rendered and visually confirmed"
        status: pass
    human_judgment: false

# Metrics
duration: 25min
completed: 2026-08-06
status: complete
---

# Quick Task 260806-nyj: Rewrite README.md Summary

**README.md replaced with an accurate, artifact-referenced description of the codebase as of v1.2 Phase 04.1 — every capability claim names the class, Gradle task, migration, or test that proves it, and the previously false EC2 deploy claim is gone.**

## Performance

- **Duration:** ~25 min
- **Tasks:** 1 completed
- **Files modified:** 1 (README.md); 251 insertions, 55 deletions

## Accomplishments

- Rewrote README.md around what the repository can actually demonstrate: layering with ArchUnit enforcement, ownership verification as a single service, optimistic locking (including *why* an explicit in-service version check is needed in addition to `@Version`), the after-commit/async event pipeline and its four failure-path decisions, Avro schema governance, Flyway history, the four test categories, and the build gates.
- Removed the one outright false claim — that CI deploys to EC2. The `deploy-to-ec2` job is `if: false` and its host was deliberately torn down on cost grounds; the README now says plainly that CI tests, builds, and pushes a tagged Docker image only, and ties the gap to the in-flight Phase 5 migration.
- Collapsed the two contradictory "Running locally" / "Running it locally" sections into one.
- Added a single mermaid **Process View** of the mutation path, scoped to one Kruchten view per `docs/DIAGRAM_CONVENTIONS.md`.
- Corrected the route table against the live controllers, which surfaced two real gaps now stated in the README rather than hidden: no HTTP board-creation endpoint, and no column delete route.
- Corrected two figures that were wrong in the source brief: the Gradle wrapper is 8.11.1 (not 8.7, as `.claude/CLAUDE.md` still claims), and 208 test methods live across 31 test-bearing classes (not 37 — six of the 37 files are abstract bases, a support listener, and a reconstructor helper).

## Task Commits

1. **Task 1: Rewrite README.md** — `a078c0b` (docs)
2. **State tracking** — see the follow-up `docs(state)` commit.

_Note: this is a docs-only change, so `.githooks/pre-commit` ran `spotlessApply` (UP-TO-DATE) and `fastTest` (UP-TO-DATE — Gradle correctly skipped execution since zero Java changed). No test actually executed on this commit, and none needed to._

## Files Created/Modified

- `README.md` — full rewrite: Stack table, Architecture, Concurrency/optimistic locking, Event-driven activity feed (+ Process View diagram), Schema governance, Schema management, Testing, Build quality gates, API, Running it locally, Current status.

## Decisions Made

- Approach A (single rewritten README, depth inline) over Approach B (thin README + new architecture docs) and Approach C (incremental patch) — full matrix in the plan. The repo does not lack long-form architectural writing; it lacked a front door reflecting it.
- Verifiability treated as the load-bearing constraint over completeness: a reader who cannot find a claimed thing discounts everything else in the document.
- The mermaid diagram was render-verified with `@mermaid-js/mermaid-cli` before commit rather than assumed to parse. The first version (`flowchart LR`) rendered legibly but far too wide for GitHub's content column and was reworked to `flowchart TD`.

## Deviations from Plan

- The plan's route table assumed board creation and column deletion were exposed over HTTP. Verification against the live controllers proved neither is: `UserService.addBoardByUserId` has no controller caller (only tests), and no `@DeleteMapping` exists on `ColumnController`. The README states both gaps explicitly rather than describing endpoints that do not exist.
- Test-class count corrected from the brief's 37 to 31 (the count of files actually containing `@Test`/`@ParameterizedTest`/`@ArchTest`).

## Issues Encountered

None blocking.

## User Setup Required

None.

## Next Phase Readiness

- No blockers. Repo remains ready to proceed to Phase 5 (Infra Migration) per `.planning/STATE.md`'s Operator Next Steps.
- Follow-up worth filing separately: `.claude/CLAUDE.md`'s Technology Stack section still states Gradle 8.7 while the wrapper is 8.11.1.
- The README's "Current status" section will need updating when Phase 5 lands and the deploy job is rewritten.

---
*Phase: quick-260806-nyj*
*Completed: 2026-08-06*

## Self-Check: PASSED
