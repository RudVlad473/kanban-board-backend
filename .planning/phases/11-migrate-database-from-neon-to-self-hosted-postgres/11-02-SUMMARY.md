---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
plan: 02
subsystem: database
tags: [postgres, docker-compose, flyway, cutover, github-secrets, neon-migration]

# Dependency graph
requires:
  - phase: 11-01
    provides: "Self-hosted Postgres topology (Compose service, init script, network, env-file contracts), proven locally"
provides:
  - "Live postgres:16 container on the Netcup VM serving kanban_prod and kanban_nonprod, D-01 isolation proven on the real VM"
  - "Both public HTTPS environments answering 200, backed by the self-hosted instance, with a write proven to survive an app container restart"
  - "GitHub Environment secrets (production, staging) repointed at the self-hosted instance"
  - "The managed Neon provider's project still exists, untouched, at the end of this plan"
affects: [11-03-memory-measurement, 11-04-hikari-retuning, 11-05-ci-flyway-verify, 11-06-decommission]

# Actuals (#2632)
actuals:
  tokens: 8200
  tasks: 3
  commits: 1

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Credentials generated ON the target host and piped directly between hosts (VM -> gh secret set stdin) — never displayed, never transit a chat transcript or a local file"
    - "Nonprod cut over before production (lower blast radius first) when both environments share one instance"

key-files:
  created: []
  modified:
    - docs/INFRA_RUNBOOK.md

key-decisions:
  - "D-04 fresh start: operator explicitly chose to proceed (see Decision Record below), abandoning every row the managed provider held rather than halting for a paid-plan data-export effort."
  - "IMAGE_TAG in both .env.prod and .env.nonprod corrected from stale, pruned tags (08feddb / 777cb27) to f835369 — the actually-running/cached image, matching origin/HEAD — a Rule 1 fix discovered live during Step 8, same failure class the Plan 08-01 nonprod bring-up hit and documented."

requirements-completed: [D-01, D-04, D-05, D-06]

coverage:
  - id: D1
    description: "Self-hosted postgres:16 provisions both databases + both least-privilege roles on the real VM, with D-01 connect-isolation proven refused in both directions"
    verification:
      - kind: manual_procedural
        ref: "docker exec psql refusal/success proof, captured verbatim in docs/INFRA_RUNBOOK.md 'Isolation proof (D-01)'"
        status: pass
    human_judgment: false
  - id: D2
    description: "Both public HTTPS health endpoints return 200, backed by the self-hosted instance; a real write survives an app container restart"
    verification:
      - kind: manual_procedural
        ref: "curl https://.../api/actuator/health from off-VM (both envs); board created/verified/restart-survived through the public production API"
        status: pass
    human_judgment: false
  - id: D3
    description: "Both databases show 8/8 successful Flyway migrations, built from scratch, no data carried over from the managed provider"
    verification:
      - kind: manual_procedural
        ref: "SELECT count(*) FROM flyway_schema_history WHERE success — both kanban_prod and kanban_nonprod"
        status: pass
    human_judgment: false
  - id: D4
    description: "GitHub Environment secrets (production, staging) repointed at the self-hosted values"
    verification:
      - kind: manual_procedural
        ref: "gh secret list --env production / --env staging, timestamps confirmed post-cutover"
        status: pass
    human_judgment: false
  - id: D5
    description: "The D-04 fresh-start decision was presented to and explicitly answered by the operator before any live step ran"
    verification: []
    human_judgment: true
    rationale: "This is inherently a human authorization record, not something a test can verify — captured verbatim below."

duration: 55min
completed: 2026-08-26
status: complete
---

# Phase 11 Plan 02: Self-hosted Postgres cutover on the Netcup VM Summary

**Cut both production and nonprod onto the self-hosted `postgres:16` instance in one operation (nonprod first), with D-01 isolation proven refused on the real VM in both directions and both public HTTPS environments verified backed by real Flyway-migrated schemas.**

## Performance

- **Duration:** ~55 min
- **Started:** 2026-08-26T14:20:00Z (approx, Task 1 checkpoint)
- **Completed:** 2026-08-26T14:37:00Z
- **Tasks:** 3
- **Files modified:** 1 (`docs/INFRA_RUNBOOK.md`) plus live VM/GitHub state (no other repository files)

## Decision Record — Task 1 (D-04, gate="blocking-human")

**Question presented:** Proceed with D-04's fresh start — abandon every row currently held by the managed provider (Neon, hard-blocked by a compute-quota exhaustion until 2026-09-01) — or halt the phase and open a data-export effort first (requires a paid Neon plan upgrade)?

**Operator's answer, verbatim (via AskUserQuestion in the orchestrating session):** "Proceed with fresh start (recommended per locked D-04)"

**What was abandoned:** every row in the managed provider's database — boards, columns, tasks, subtasks, users (including bcrypt password hashes), the activity log, and both Spring Session tables. The provider's project itself was NOT deleted or modified by this plan — it still exists, untouched, and deletion is plan 11-06's separately-gated decision (D-07).

## Accomplishments
- Self-hosted `postgres:16` brought up on the Netcup VM, D-01 connect-isolation proven refused in both directions with the real PostgreSQL error text (`FATAL: permission denied for database ... DETAIL: User does not have CONNECT privilege`), not a password/host/db-not-exist error.
- Nonprod cut over first (lower risk — its reset endpoint already TRUNCATEs), then production. Both reached `(healthy)` with 8/8 successful Flyway migrations from an empty engine.
- Both public HTTPS health endpoints proven 200 from outside the VM; a real board created through the public production API survived an `app` container restart (proving a working datasource, not just a health endpoint), then cleaned up.
- GitHub Environment secrets (`production`, `staging`) repointed at the self-hosted instance's `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` — passwords piped directly from the VM into `gh secret set` via stdin, never displayed.
- Full cutover documented in a new dated `docs/INFRA_RUNBOOK.md` section with verbatim evidence, following the file's established one-section-per-plan convention.

## Task Commits

Task 1 (checkpoint:decision) and Task 2 (checkpoint:human-action) produce no code commits — they are live infrastructure operations and a recorded human decision, not repository changes. Task 3's documentation commit:

1. **Task 3: Record the cutover in the infrastructure runbook** - `7a3874f` (docs)

_Note: Tasks 1 and 2 executed the live VM/GitHub operations described above and in `docs/INFRA_RUNBOOK.md`'s new "Self-hosted Postgres cutover — Plan 11-02" section; only Task 3 touches the repository._

## Files Created/Modified
- `docs/INFRA_RUNBOOK.md` - New dated "Self-hosted Postgres cutover — Plan 11-02" section (Topology, Sequence, Deviations, Isolation proof, Schema proof, Sessions-not-carried-over, Known red window in CI, Operator note, Cutover date); one-line forward pointer added to the existing "Database — Neon" section.

## Decisions Made
- D-04 fresh start: operator explicitly authorized abandoning the managed provider's data (see Decision Record above) rather than pursuing a paid-plan data-export effort.
- Nonprod cut over before production, per D-06 and the plan's own ordering — proves the mechanism on the lower-risk environment first.
- The verification board created in Task 2's write-survives-restart proof was deleted immediately after use to keep the fresh production database clean; the throwaway verification user account was left in place as low-risk, not worth building an account-deletion path under live-cutover time pressure to remove.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Stale IMAGE_TAG values in both preserved .env files**
- **Found during:** Task 2, Step 8 (cutting nonprod over)
- **Issue:** The Neon-era `.env.prod`/`.env.nonprod` carried `IMAGE_TAG=08feddb` / `IMAGE_TAG=777cb27` respectively — both since pruned from Docker Hub (this repo's CI publishes one tag per commit, never `latest`, and old tags are pruned per the "Decommission Record — Plan 05-06 Task 3" runbook section). `docker compose up -d` for nonprod failed outright: `...kanban-board-backend-nonprod:777cb27: not found`.
- **Fix:** Read the actually-running (pre-cutover, unhealthy due to the Neon outage) containers' real image tag via `docker ps` — both `app` and `app-nonprod` were already running under `f835369`, matching `origin/HEAD` and confirmed cached locally on the VM. Set `IMAGE_TAG=f835369` in both `.env.prod` and `.env.nonprod`.
- **Files modified:** `/opt/deploy/kanban-board-backend/.env.prod`, `/opt/deploy/kanban-board-nonprod/.env.nonprod` (VM only, never committed).
- **Verification:** Both `docker compose up -d` invocations succeeded afterward; both containers reached `(healthy)`.
- **Committed in:** N/A — VM-only env file state, not a repository change. Documented in `docs/INFRA_RUNBOOK.md`'s new section (commit `7a3874f`).

---

**Total deviations:** 1 auto-fixed (1 blocking — stale image tag).
**Impact on plan:** Necessary correction to unblock the cutover; no scope creep. Same failure class as a previously-recorded incident (Plan 08-01's nonprod bring-up), recovered the same way.

## Issues Encountered
None beyond the documented deviation above.

## User Setup Required
None - the `user_setup` block in `11-02-PLAN.md`'s frontmatter (three credentials, generated ON the VM) was executed as part of Task 2 itself, by the orchestrating agent with the operator's live SSH access, rather than left as a manual follow-up step.

## Next Phase Readiness
- Both environments are live on the self-hosted instance. `flyway-verify` / `flyway-verify-nonprod` CI jobs are now expected to fail on every push (known red window, see runbook) until plan 11-05 rewrites them to reach the self-hosted database.
- Plan 11-03 (memory measurement) and 11-04 (HikariCP/JDBC retuning) can now proceed against the real deployed instance.
- The managed provider's project is untouched — plan 11-06 owns its decommission, separately gated.

---
*Phase: 11-migrate-database-from-neon-to-self-hosted-postgres*
*Completed: 2026-08-26*
