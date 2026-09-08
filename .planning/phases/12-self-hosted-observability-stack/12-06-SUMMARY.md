---
phase: 12-self-hosted-observability-stack
plan: 06
subsystem: infra
tags: [docker, mem_limit, caddy, rate-limiting, architecture-doc, mermaid, todos]

requires:
  - phase: 12-self-hosted-observability-stack (plan 12-05)
    provides: seven measured mem_limit floors, all deployed and stable, so caddy is measured proxying its real final steady state
provides:
  - "D-02: a measured mem_limit on caddy, closing the last uncapped container on the production host"
  - "A documentation set (docs/INFRA_ARCHITECTURE.md + its diagram) that matches the deployed thirteen-container reality"
  - "Two todos closed/annotated to their honest state"
affects: []

actuals:
  tokens: 90000
  tasks: 3
  commits: 2

tech-stack:
  added: []
  patterns: [restart-ladder measurement under adversarial multi-source traffic, honest single-source coverage-limit disclosure, deliberate attacker-influence margin above a bare passing rung, pinned-renderer diagram regeneration]

key-files:
  created: []
  modified:
    - docker-compose.prod.yml
    - docs/INFRA_RUNBOOK.md
    - docs/INFRA_ARCHITECTURE.md
    - docs/diagrams/infra-physical-deployment.mmd
    - docs/diagrams/infra-physical-deployment.png
    - .planning/todos/completed/2026-09-03-caddy-service-has-no-mem-limit.md
    - .planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md

key-decisions:
  - "User authorized the agent to drive this ladder over SSH too, with explicit acknowledgment of the higher risk (live public-edge interruption on every rung, cert-safety stop condition)."
  - "Adopted 32m deliberately above the bare 16m passing floor -- the entire 32m/16m gap is stated as attacker-influence margin compensating for the ladder's honest single-source-address coverage limit, not folded into the ladder evidence as if it were measured."
  - "The architecture doc's container count is stated as the HOST-wide thirteen (11 production + 2 nonprod), derived from docker ps not docker compose ps -- this exact undercount was caught by a cross-AI review in the plan's own earlier draft and is now mechanically checked by Task 3's verify script."

patterns-established:
  - "Pattern 1: when a live-traffic-interrupting restart ladder's adversarial workload component cannot achieve its ideal coverage (here: many distinct source addresses), state the real coverage achieved and adopt extra margin explicitly as compensation for the gap -- never silently substitute a weaker test and call it equivalent."

requirements-completed: [D-01, D-02]

coverage:
  - id: D1
    description: "caddy -- the last uncapped container on the production host -- now has a measured mem_limit with a genuine failing rung (8m, OOM+crash-loop) recorded below the adopted 32m"
    requirement: "D-02"
    verification:
      - kind: manual_procedural
        ref: "live restart-ladder over SSH to netcup-prod, docs/INFRA_RUNBOOK.md 'Caddy resource measurement -- Plan 12-06'"
        status: pass
    human_judgment: false
  - id: D2
    description: "docs/INFRA_ARCHITECTURE.md and its Physical/Deployment diagram describe all thirteen containers that actually run on this host, mechanically checked against docker ps (host-wide), not docker compose ps"
    verification:
      - kind: other
        ref: "Task 3's automated verify script (grep for app-nonprod/redpanda-nonprod and 'thirteen' in docs/INFRA_ARCHITECTURE.md, plus scripts/render-diagrams.sh --check)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The caddy mem_limit todo is closed with a Resolution naming the adopted value; the log-shipping todo is honestly annotated as one-third closed and stays pending"
    requirement: "D-01"
    verification:
      - kind: other
        ref: "Task 3's automated verify script (frontmatter/Resolution checks on both todo files)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The rate limiter's adversarial workload component's real coverage (one distinct source address, not a realistic multi-attacker cardinality) is disclosed explicitly rather than glossed over"
    verification: []
    human_judgment: true
    rationale: "This is a stated, accepted measurement limitation, not something a test can pass/fail -- a human should judge whether the deliberate 32m/16m attacker-influence margin is sufficient compensation, or whether a future, better-resourced adversarial test is warranted."

duration: 55min
completed: 2026-09-08
status: complete
---

# Phase 12 Plan 06: Phase Close-Out — Caddy Cap, Architecture Correction, Todo Closure Summary

**The last uncapped container on the production host now has a measured mem_limit; the architecture document and its diagram were corrected to describe all thirteen containers actually running; both folded todos reached their honest final state — closing Phase 12.**

## Performance

- **Duration:** ~55 min (measurement pass + manifest/runbook/doc/diagram writing + verification)
- **Tasks:** 3 (Task 1: checkpoint:human-action, executed by the agent under explicit user authorization; Tasks 2-3: auto)
- **Files modified:** 7

## Accomplishments
- `caddy` — the one container whose failing rung takes down the public edge for both environments and Grafana simultaneously — now carries `mem_limit: 32m`, closing D-02 in fact.
- The 8m rung's real OOM crash-loop was caught and stopped immediately per the plan's certificate-safety discipline, proving the stop condition works rather than merely documenting it.
- `docs/INFRA_ARCHITECTURE.md` and `infra-physical-deployment.mmd`/`.png` now describe the host-wide reality: thirteen containers (11 production + 2 nonprod), derived from `docker ps` not `docker compose ps` — the exact undercount a cross-AI review caught in this plan's earlier draft, now mechanically gated by Task 3's verify script.
- Both folded todos reached their honest final state: the caddy cap todo closed with evidence; the log-shipping todo annotated as one-third done (remote shipping closed, structured-logging and alerting still open) and correctly left in `pending/`.

## Task Commits

Each task was committed atomically:

1. **Task 1: Caddy restart-ladder measurement** — no commit (measurement-only; `git status`/`git diff --stat` both confirmed empty per its own acceptance criteria)
2. **Task 2: Write caddy's cap + runbook evidence** — `a699dd9` (feat)
3. **Task 3: Architecture doc, diagram, todo closure** — `aaeb537` (docs)

## Files Created/Modified
- `docker-compose.prod.yml` — `caddy` gains `mem_limit: 32m` with a dated `MEASURED BASIS (Plan 12-06, 2026-09-08)` comment stating the unauthenticated-traffic influence explicitly
- `docs/INFRA_RUNBOOK.md` — new `## Caddy resource measurement — Plan 12-06` section with the full ladder, the 8m failure evidence, and the certificate-safety proof-of-mechanism
- `docs/INFRA_ARCHITECTURE.md` — Physical/Deployment prose updated for the observability stack, the host-wide thirteen-container count, and the extended trust-boundary numbering (`[6]`, `[7]`); Maintenance Note extended with 4 new config paths + `kanban-metrics`
- `docs/diagrams/infra-physical-deployment.mmd`/`.png` — seven new containers added in a grouped subgraph, the `kanban-metrics` cross-project edge drawn, PNG re-rendered through the pinned renderer
- `.planning/todos/completed/2026-09-03-caddy-service-has-no-mem-limit.md` — closed with `resolved: 2026-09-08`, `resolves_phase: 12`, and a Resolution section
- `.planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md` — annotated with a dated `## Partial resolution (Phase 12)` section, deliberately left pending

## Decisions Made
- **SSH execution authorization (higher stakes than 12-05):** the user was explicitly told this ladder's every rung briefly interrupts live production traffic on the public edge, and that a crash-loop risks a Let's Encrypt rate-limit ban — and authorized the agent to proceed anyway. This was a fresh checkpoint decision, not an extension of 12-05's authorization.
- **Attacker-influence margin, stated explicitly:** the adversarial workload component could only exercise one distinct source address from this session, which does not represent the rate limiter's true attacker-influenced memory driver (per-`{remote_host}`-key state). Rather than silently adopting the bare passing floor as if it were a complete measurement, the adopted value (32m) sits one full rung above it (16m), with that entire margin named as compensation for the coverage gap.
- **Container-count correction:** the architecture doc states the host-wide count (thirteen) rather than the production-Compose-project count (eleven) — this exact miscount was caught by a cross-AI review of the plan's own earlier draft and is now enforced by Task 3's verify script checking for `app-nonprod`/`redpanda-nonprod` by name.

## Deviations from Plan

None — plan executed exactly as written, including its verify scripts (adjusted only for the same `.env.prod.example` secret-guard workaround already used in plan 12-05: structural checks that needed `docker compose config` with the env file were run via equivalent scoped checks instead — `git diff` scoping, direct `sed`-extracted field checks, and the standalone `scripts/verify-compose-ports.py`/`scripts/verify-caddy-image-tag.py` invariant scripts, none of which need the gated file).

### Auto-fixed Issues

**1. [Rule 1 - Bug] Wrong script path referenced from the fork's report**
- **Found during:** Task 2 (writing the caddy cap comment and runbook section)
- **Issue:** Initially wrote `scripts/run-rate-limit-verification.sh` (matching the executing fork's own report) — the real path is `scripts/loadtest/run-rate-limit-verification.sh`.
- **Fix:** Corrected via `sed` across both `docker-compose.prod.yml` and `docs/INFRA_RUNBOOK.md` before committing.
- **Files modified:** `docker-compose.prod.yml`, `docs/INFRA_RUNBOOK.md` (both already in scope for Task 2).
- **Verification:** `grep -rn "run-rate-limit-verification.sh"` confirmed both references now point at the real, existing file.
- **Committed in:** `a699dd9` (caught before commit, no separate fix commit needed)

**2. [Rule 1 - Bug] Task 3's exact-string check for the log-shipping annotation heading**
- **Found during:** Task 3's verify script run
- **Issue:** First draft of the partial-resolution heading was `## Partial resolution (Phase 12, 2026-09-08)` (date inline) — the verify script's `grep -qF '## Partial resolution (Phase 12)'` requires the literal substring with an immediate closing paren, which a trailing comma+date breaks.
- **Fix:** Moved the date into the section's prose instead of the heading (`## Partial resolution (Phase 12)` / `Dated 2026-09-08. ...`).
- **Files modified:** `.planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md`.
- **Verification:** Task 3's verify script re-run, passed.
- **Committed in:** `aaeb537`

---

**Total deviations:** 2 auto-fixed (1 stale path reference, 1 exact-string heading mismatch), both caught and corrected before committing, no plan scope changed.
**Impact on plan:** Neither affected the substance of the measurement or documentation — both were transcription/formatting corrections.

## Issues Encountered
None beyond the deviations above.

## User Setup Required
None — no external service configuration required.

## Next Phase Readiness
- **Phase 12 is complete.** All six plans (12-01 through 12-06) delivered a self-hosted observability stack with dated, measured resource caps throughout and documentation that matches the deployment.
- **Carried-forward findings to file as `.planning/todos/pending/` entries at phase close:**
  1. Grafana admin-password drift between `.env.prod` and the VM's deployed Grafana instance (found in plan 12-05, blocked a full authenticated UI dashboard render during measurement).
  2. The rate limiter's adversarial-coverage gap (single source address tested, not a realistic multi-attacker cardinality) — not itself a defect, but worth tracking if a more thorough adversarial re-test is ever warranted.
- Next milestone not yet scoped — see STATE.md's Operator Next Steps for `/gsd-new-milestone`.

---
*Phase: 12-self-hosted-observability-stack*
*Completed: 2026-09-08*
