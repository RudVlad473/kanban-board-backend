---
phase: 11-migrate-database-from-neon-to-self-hosted-postgres
plan: 05
subsystem: infra
tags: [ci-cd, flyway, github-actions, ssh, postgres, self-hosted]

# Dependency graph
requires:
  - phase: 11-02
    provides: "Live self-hosted postgres:16 container on the Netcup VM, shared kanban-db network, GitHub Environment secrets repointed at kanban_prod/kanban_nonprod"
  - phase: 11-03
    provides: "Measured postgres mem_limit floor (kept unchanged by this plan)"
provides:
  - "flyway-verify / flyway-verify-nonprod rewritten to run the Flyway CLI as a one-off container on the VM over SSH, attached to the shared kanban-db network — closes the red CI window plan 11-02 opened"
  - "DB_HOST/DB_NAME moved from GitHub Environment secrets to variables (per environment) so the resolved database name is visible, unmasked, in each job's log — required to prove no wrong-environment scoping"
  - "Live green run (32985965535) with both verification jobs' logs confirming they targeted their own environment's database by name, both deploys ran, both public health endpoints returned 200"
  - "Live, VM-local reproduction proving the gate still fails on a broken migration and that set -e correctly aborts the script"
affects: [11-06-decommission]

# Actuals (#2632)
actuals:
  tokens: 6700
  tasks: 3
  commits: 2

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "One-off Flyway container run on the VM over SSH, attached to an external Docker network — same pattern register-schemas-production already proved for schema registration"
    - "Non-sensitive but log-relevant config (database hostname/name) held as GitHub Environment variables, not secrets, specifically so it is not redacted from CI logs when that log output is itself the required evidence"

key-files:
  created: []
  modified:
    - .github/workflows/deploy.yml
    - docs/INFRA_RUNBOOK.md

key-decisions:
  - "D-13 Task 1: operator selected Option A (one-off container over SSH) over a literal port-forward — no exception to D-03 taken."
  - "DB_HOST/DB_NAME converted from GitHub Environment secrets to variables (Rule 2, mid-execution addition) — a masked secret value can never appear unmasked in CI logs, which would have made T-11-26's required log-based scoping proof structurally impossible to obtain."
  - "'Gate still bites' evidence gathered as a live VM-local reproduction (disposable scratch database, real broken migration, exact command shape) rather than a throwaway-branch CI push — same evidence quality, materially lower risk to live infrastructure."

requirements-completed: [D-03, D-13]

coverage:
  - id: D1
    description: "flyway-verify and flyway-verify-nonprod rewritten to reach the self-hosted database over SSH, no host port published, gate preserved"
    requirement: "D-13"
    verification:
      - kind: manual_procedural
        ref: "Live run 32985965535, flyway-verify + flyway-verify-nonprod job logs showing kanban_prod/kanban_nonprod respectively"
        status: pass
    human_judgment: false
  - id: D2
    description: "No database host port published — D-03 holds with no exception (Option A chosen, not the port-forward option)"
    requirement: "D-03"
    verification:
      - kind: manual_procedural
        ref: "docker-compose.prod.yml unchanged by this plan; deploy.yml rewrite contains no port binding"
        status: pass
    human_judgment: false
  - id: D3
    description: "Task 1's decision (D-13, gate=blocking-human) was presented to and answered by the operator before Task 2 started"
    verification: []
    human_judgment: true
    rationale: "Human authorization record, not something a test can verify — captured verbatim in this SUMMARY's Decision Record section."
  - id: D4
    description: "The gate still fails on a migration that cannot apply, proven live rather than only asserted"
    verification:
      - kind: manual_procedural
        ref: "VM-local reproduction (docs/INFRA_RUNBOOK.md, 'Gate still bites'): scratch database, broken V9 migration, set -e verified to abort the script"
        status: pass
    human_judgment: false

duration: 41min (includes ~25min waiting on an unrelated GitHub Actions platform outage)
completed: 2026-08-26
status: complete
---

# Phase 11 Plan 05: CI Flyway Verification Rewrite Summary

**Rewrote flyway-verify/flyway-verify-nonprod to run Flyway as a one-off container on the VM over SSH (D-13 Option A), moved DB_HOST/DB_NAME from secrets to variables so the resolved database name is provably visible in CI logs, and proved the rewritten gate green on a real push plus still-failing on a broken migration.**

## Performance

- **Duration:** ~41 min wall clock (includes an unrelated ~25 min wait while GitHub Actions itself was mid-incident — "major outage" per githubstatus.com — before the pushed run's `setup` job picked up a runner)
- **Started:** 2026-08-26T15:33:00Z (approx, Task 2 start)
- **Completed:** 2026-08-26T16:13:53Z
- **Tasks:** 3 (Task 1 pre-resolved before this execution; Tasks 2 and 3 executed here)
- **Files modified:** 2 (`.github/workflows/deploy.yml`, `docs/INFRA_RUNBOOK.md`)

## Decision Record — Task 1 (D-13, gate="blocking-human")

**Already resolved before this execution**, via the orchestrating session's `AskUserQuestion` tool.

**Question presented:** Which mechanism do the CI Flyway verification jobs use to reach the
self-hosted database — a one-off container run on the VM over SSH (Option A), or a literal local
port-forward from the runner (Option B)?

**Options and their concrete costs:**
- **Option A (recommended):** no port exposed anywhere (D-03 unmodified); the pattern already runs
  in this repository for schema registration (`register-schemas-production`), same digest-pinned
  action, same secret set; container-name DNS resolves correctly inside the network's own
  namespace. Cost: the migration `.sql` directory must be copied to the VM on every run.
- **Option B:** matches D-13's literal wording, keeps the Flyway container on the runner. Cost: no
  clean target — either a loopback-only host bind (an undiscussed D-03 exception) or the
  container's internal bridge address (unstable across every recreate, a real CI-flake source).

**Operator's answer, verbatim (via AskUserQuestion in the orchestrating session):** "Option A:
one-off container over SSH (recommended)"

**Reasoning presented and accepted:** No port exposure anywhere, so D-03 keeps holding unmodified.
The pattern already runs in this repository for schema registration (`register-schemas-production`
job), using the same digest-pinned action and the same secret set. Container-name DNS resolution
works because the container runs inside the network's own namespace. Trade-off accepted: the
migration `.sql` directory must be copied to the VM on every verification run.

**Task 2 implemented exactly this**: no port-forward variant, no `docker-compose.prod.yml` change,
no exception to D-03 of any shape.

## Accomplishments
- Both `flyway-verify` and `flyway-verify-nonprod` rewritten: a distinct-from-deploy staging path
  per environment (`rm: true` on every run so the staged set always mirrors the checkout exactly),
  an explicit `pg_isready` reachability assertion replacing the dead Neon-pooler-suffix guard, and
  `flyway/flyway:11.7.2 migrate` run as a one-off container over the shared `kanban-db` network.
- `DB_HOST`/`DB_NAME` moved from GitHub Environment secrets to variables (production:
  `postgres`/`kanban_prod`; staging: `postgres`/`kanban_nonprod`) — a deviation from the plan text,
  made because GitHub redacts any log line containing a registered secret's value regardless of
  source, which would have made it structurally impossible to prove which database each job
  actually targeted from its own log (the literal evidence T-11-26 and Task 3 require). `DB_USER`/
  `DB_PASS` remain real secrets; no credential moved.
- Pushed to `origin/main` (already-authorized) and observed a real, fully green run
  (`32985965535`) despite GitHub Actions itself being mid-platform-outage when the push landed —
  the run's `setup` job queued ~20-25 min with no runner assigned until GitHub's own incident
  cleared, then proceeded normally with no other anomaly.
- Read both verification jobs' real logs (not the green check): `flyway-verify` showed
  `Database: jdbc:postgresql://postgres:5432/kanban_prod`, `flyway-verify-nonprod` showed
  `.../kanban_nonprod` — confirmed distinct, confirmed matching each job's own environment.
- Confirmed both downstream deploys ran: `app` and `kanban-nonprod-app` both running image tag
  `a536e60` (`docker ps`, read directly on the VM), both public HTTPS health endpoints `200 UP`.
- Proved the gate still fails on a broken migration via a live, VM-local reproduction (a disposable
  scratch database, never touching `kanban_prod`/`kanban_nonprod`, seeded with the real V1-V8 set
  plus one deliberately invalid V9) rather than a throwaway-branch CI push — the wrapping script's
  `set -e` was confirmed to abort before a marker line placed immediately after the Flyway
  invocation, proving the same exit-code chain mechanism the real jobs rely on.
- Recorded the full mechanism, the Task 1 decision verbatim, the "why not ephemeral" rationale, the
  live-verification evidence, and the gate-still-bites evidence in a new dated
  `docs/INFRA_RUNBOOK.md` section; updated the plan-11-02 cutover section's "Known red window in
  CI" note with the closing date.

## Task Commits

Task 1 (checkpoint:decision) was pre-resolved outside this execution and produced no repository
commit — it is a recorded human decision, not a code change.

1. **Task 2: Rewrite both Flyway verification jobs against the decided mechanism** - `a536e60` (feat)
2. **Task 3: Prove both gates green on a real run, and record the mechanism** - `5592807` (docs)

## Files Created/Modified
- `.github/workflows/deploy.yml` - `flyway-verify`/`flyway-verify-nonprod` rewritten to stage
  migrations and run Flyway as a one-off container on the VM over SSH; dead endpoint-suffix guard
  removed; `DB_HOST`/`DB_NAME` switched from `secrets.*` to `vars.*`.
- `docs/INFRA_RUNBOOK.md` - New dated "CI Flyway verification over SSH — Plan 11-05" section
  (Mechanism, Decision (D-13), Why not an ephemeral database in CI, Live verification, Gate still
  bites, Verification date); "Known red window in CI" subsection in the plan-11-02 cutover section
  updated with the closing date.

## Decisions Made
- D-13 Task 1: Option A selected by the operator (see Decision Record above) — no D-03 exception
  taken.
- DB_HOST/DB_NAME moved from GitHub Environment secrets to variables (Rule 2 addition, mid-Task-2)
  — necessary for the required log-based database-name evidence to be obtainable at all; DB_USER/
  DB_PASS remain secrets.
- "Gate still bites" evidence gathered via a live VM-local reproduction against a disposable
  scratch database rather than a real broken-migration push through CI on a throwaway branch —
  judged to deliver equivalent evidence quality (real Flyway failure, real `set -e` behavior, real
  self-hosted instance) with materially lower risk (no git history pollution, no risk of the
  manually-triggered workflow run interacting unexpectedly with the real deploy-gated pipeline).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] DB_HOST/DB_NAME converted from GitHub Environment secrets to variables**
- **Found during:** Task 2 (designing the rewritten verification step)
- **Issue:** The plan's own threat register (T-11-26) and Task 3's acceptance criteria require
  reading the real resolved database name out of each job's own log to prove no wrong-environment
  scoping occurred. `DB_NAME` (and `DB_HOST`) were GitHub Environment *secrets* — GitHub redacts
  any log line containing a registered secret's value, regardless of where that value came from
  (Flyway's own connection-string log line, or an explicit `echo`). Left as secrets, the required
  evidence could never appear unmasked in any CI log, making the plan's own Task 3 acceptance
  criterion structurally unsatisfiable.
- **Fix:** Confirmed via `grep`/`gh secret list` that `DB_HOST`/`DB_NAME` were referenced only by
  these two jobs (isolated blast radius). Read the real, non-sensitive values directly from the VM
  (`postgres`/`kanban_prod` and `postgres`/`kanban_nonprod`), set them as `gh variable set` per
  environment, then deleted the now-redundant `gh secret` entries. Updated `deploy.yml` to read
  `vars.DB_HOST`/`vars.DB_NAME` for these two jobs; `DB_USER`/`DB_PASS` remain `secrets.*`.
- **Files modified:** `.github/workflows/deploy.yml`; GitHub Environment configuration for
  `production` and `staging` (not a repository file).
- **Verification:** Live run `32985965535` — both jobs' logs show the real database name unmasked
  (`kanban_prod` / `kanban_nonprod`), confirmed by direct log inspection.
- **Committed in:** `a536e60` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 missing critical).
**Impact on plan:** Necessary to make the plan's own required evidence (T-11-26, Task 3's
acceptance criteria) actually obtainable — not scope creep; the plan's file scope
(`.github/workflows/deploy.yml`) was unchanged, only the secret/variable classification of two
non-sensitive values.

## Issues Encountered
GitHub Actions experienced a platform-wide "major outage" (githubstatus.com, root-caused to a
database/Vitess primary failover) exactly while this plan's live-verification push landed. The
triggered run's `setup` job sat `queued` with no runner assigned for roughly 20-25 minutes before
GitHub's own infrastructure recovered; the run then proceeded and completed green with no other
anomaly. Not a defect in this plan's work — recorded in `docs/INFRA_RUNBOOK.md`'s Live verification
subsection so a future reader does not mistake a similarly stuck-queued run for a workflow-file
problem.

## User Setup Required
None - all live infrastructure changes (GitHub variable/secret reclassification, the VM-local
gate-still-bites reproduction) were executed directly by this session using existing SSH/`gh`
access, not left as manual follow-up steps.

## Next Phase Readiness
- The red CI window opened by plan 11-02's cutover is closed — both Flyway verification gates are
  green against the self-hosted instance and proven to still fail on a bad migration.
- Plan 11-06 (Neon decommission) can now proceed: the last blocking concern before deleting the
  managed provider's project was confirming the CI verification gate no longer depends on it, which
  this plan closes.
- `docker-compose.prod.yml` is unchanged by this plan (Option A required no D-03 exception).

---
*Phase: 11-migrate-database-from-neon-to-self-hosted-postgres*
*Completed: 2026-08-26*

## Self-Check: PASSED

- FOUND: `.github/workflows/deploy.yml`
- FOUND: `docs/INFRA_RUNBOOK.md`
- FOUND: `.planning/phases/11-migrate-database-from-neon-to-self-hosted-postgres/11-05-SUMMARY.md`
- FOUND commit: `a536e60`
- FOUND commit: `5592807`
