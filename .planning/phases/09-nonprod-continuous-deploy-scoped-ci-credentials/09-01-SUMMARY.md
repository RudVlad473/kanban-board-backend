---
phase: 09-nonprod-continuous-deploy-scoped-ci-credentials
plan: 01
subsystem: infra
tags: [github-actions, github-environments, docker-hub, ssh, netcup, ci-cd]

# Dependency graph
requires:
  - phase: 08-isolated-nonprod-environment-live-and-resettable
    provides: "docker-compose.nonprod.yml, the nonprod Neon branch, the nonprod VM directory layout, and the standing precedent that root VM work runs over the operator's own authorized SSH session"
provides:
  - "GitHub Environments production and staging, both with zero protection rules"
  - "Nine identically-named deploy secrets populated in both environments (DB_HOST, DB_NAME, DB_USER, DB_PASS, NETCUP_SSH_KEY, NETCUP_DEPLOY_USER, NETCUP_HOST, NETCUP_HOST_FINGERPRINT, DOCKERHUB_TOKEN); the ten repository-level secrets remain untouched as the safety net for the first environment-scoped production run"
  - "Linux user deploy-nonprod on the Netcup VM, docker-group member, confined by Unix filesystem permissions to /opt/deploy/kanban-board-nonprod/, proven locked out of /opt/deploy/kanban-board-backend/ by measured Permission denied output"
  - "Public Docker Hub repository rudenkovladimir/kanban-board-backend-nonprod"
  - "Task 1's decision checkpoint resolved (option-a) and recorded, with both residual risks (T-09-03 docker-group root-equivalence, T-09-09 account-wide Docker Hub token) written into docs/INFRA_RUNBOOK.md"
affects: [09-02-nonprod-continuous-deploy-scoped-ci-credentials, 09-03-nonprod-continuous-deploy-scoped-ci-credentials]

# Actuals (#2632)
actuals:
  tokens: 5300
  tasks: 2
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "GitHub Environments used purely as a secret-scoping mechanism (zero protection rules, D-04) rather than for approval gates"
    - "Deploy credentials recovered from the VM's own already-deployed env files where possible, rather than re-typed by the operator, wherever GitHub's write-only secret store made the original value otherwise unrecoverable"

key-files:
  created: []
  modified:
    - "docs/INFRA_RUNBOOK.md — new section '## Nonprod CI deploy identity and environment-scoped secrets — Plan 09-01', corrected '### Operator note — deploying nonprod'"

key-decisions:
  - "Task 1 checkpoint resolved by human operator: option-a — deploy-nonprod VM identity confined by Unix filesystem permissions only (docker group membership root-equivalence accepted as residual risk), plus one Docker Hub token duplicated into both GitHub Environments (account-wide token scope accepted as residual risk). Mechanical names confirmed as proposed: Linux user deploy-nonprod, Docker Hub repository rudenkovladimir/kanban-board-backend-nonprod, GitHub Environments production/staging, identical secret NAMES in both environments with per-environment values."
  - "Task 3 (the .github/workflows/deploy.yml edit and the live push-to-master that proves it) deliberately deferred to the orchestrator, per explicit coordinator instruction: this session ran inside an isolated worktree, and Task 3 pushes directly to origin/master triggering a real production-impacting deploy run, which must happen under the human operator's direct observation, not unattended from a background agent."

patterns-established: []

requirements-completed: []

coverage:
  - id: D1
    description: "Two GitHub Environments (production, staging) created with zero protection rules, each holding the same nine deploy secret names with per-environment values; ten repository-level secrets left untouched"
    requirement: "CI-02"
    verification:
      - kind: other
        ref: "gh api repos/RudVlad473/kanban-board-backend/environments --jq '.total_count' == 2; gh secret list --env production/--env staging both == the nine expected names; gh secret list (repo) == 10"
        status: pass
    human_judgment: false
  - id: D2
    description: "deploy-nonprod Linux identity created on the Netcup VM, docker-group member, confined to /opt/deploy/kanban-board-nonprod/, proven locked out of production's directory"
    requirement: "D-01/D-02 (CONTEXT.md decisions)"
    verification:
      - kind: manual_procedural
        ref: "id deploy-nonprod; sudo -u deploy-nonprod sudo -n true (fails); stat modes on both directories; sudo -u deploy-nonprod ls/cat against production's directory (both Permission denied); off-VM SSH as deploy-nonprod succeeds"
        status: pass
    human_judgment: false
  - id: D3
    description: "Public Docker Hub repository rudenkovladimir/kanban-board-backend-nonprod created for CI-03's repository separation"
    requirement: "CI-03"
    verification:
      - kind: other
        ref: "curl https://hub.docker.com/v2/repositories/rudenkovladimir/kanban-board-backend-nonprod/ | jq -r .is_private == false"
        status: pass
    human_judgment: false
  - id: D4
    description: "Task 3 (.github/workflows/deploy.yml edit and live end-to-end deploy proof) not executed this session — deliberately deferred to the orchestrator"
    verification: []
    human_judgment: true
    rationale: "Task 3 pushes to origin/master and triggers a real, production-impacting GitHub Actions deploy run; per explicit coordinator instruction this must happen under the human operator's direct observation once this worktree's commits land on master, not unattended from an isolated background agent. No automated verification applies to work that was not performed."

# Metrics
duration: ~1h10min (across three halts/resumes; see Performance below)
completed: 2026-08-18
status: halted
---

# Phase 09 Plan 01: Nonprod CI deploy identity and scoped secrets — Summary

**Two GitHub Environments (production/staging, zero protection rules) populated with nine scoped deploy secrets each, a filesystem-confined deploy-nonprod VM identity proven locked out of production's directory, and a public Docker Hub repository for nonprod — Task 3's live workflow wiring deliberately deferred to the orchestrator.**

## Performance

- **Duration:** ~1h10min total across three phases: Task 1 checkpoint reached and halted (~10 min, including Windows Gradle/pre-commit-hook file-lock recovery), checkpoint resolved and Task 2 precondition re-checked and found unmet (~5 min, second halt), then Task 2 fully provisioned and committed after the operator supplied SSH access and recoverable-secret guidance (~55 min, including a mid-task key-rotation fix)
- **Started:** 2026-08-18T18:19:14Z (approx, per STATE.md)
- **Completed:** 2026-08-18
- **Tasks:** 2 of 3 completed (Task 1's checkpoint resolved, Task 2 fully provisioned and verified; Task 3 deliberately not started)
- **Files modified:** 1 (`docs/INFRA_RUNBOOK.md`) plus this SUMMARY.md

## Accomplishments

- **Task 1 resolved:** human operator selected option-a (deploy-nonprod confined by Unix permissions only; one Docker Hub token duplicated into both environments) and confirmed all mechanical resource names.
- **Task 2 fully provisioned and independently verified:**
  - Created GitHub Environments `production` and `staging`, both with zero protection rules (`gh api .../environments --jq '.total_count'` → `2`, both `protection_rules: []`).
  - Created Linux user `deploy-nonprod` on the Netcup VM, `docker`-group member, no `sudo`; generated a new ed25519 keypair (persisted locally, mirroring production's `netcup_deploy_key` precedent); re-owned `/opt/deploy/kanban-board-nonprod/` to `deploy-nonprod:deploy-nonprod` mode `750`; tightened `/opt/deploy/kanban-board-backend` to `750` to close the reverse read path. Proved the boundary by measurement in both directions (`Permission denied` against production's directory and env file; a successful `docker compose ps` in nonprod's own directory; a successful off-VM SSH as `deploy-nonprod`).
  - Created the public Docker Hub repository `rudenkovladimir/kanban-board-backend-nonprod` (HTTP `201`, `is_private: false`) via the same JWT-login pattern `cleanup-old-images` already uses.
  - Populated all nine deploy secrets in both environments, recovering `DB_*` values as root from the VM's own already-deployed `.env.prod`/`.env.nonprod` files, reusing the operator's existing local `netcup_deploy_key` for production, and piping `DOCKERHUB_TOKEN` directly from a local file into `gh secret set` — no secret value was ever printed, echoed, or written into this repository.
  - Recorded the full provisioning, every verbatim proof output, and both accepted residual risks (T-09-03 docker-group root-equivalence, T-09-09 account-wide Docker Hub token) in `docs/INFRA_RUNBOOK.md`.
  - Corrected the pre-existing `### Operator note — deploying nonprod` section, which previously instructed running nonprod Compose commands as `deploy` — now correctly says `deploy-nonprod`.
- **Task 3 deliberately not started**, per explicit coordinator instruction (scope change, see Decisions Made): `.github/workflows/deploy.yml` is unmodified; no push to `master`; no live GitHub Actions run was triggered.

## Task Commits

Each halt/resume step was committed atomically:

1. **Checkpoint halt (Task 1 unresolved)** - `8e2bafd` (docs)
2. **Checkpoint resolution recorded, second halt (Task 2 precondition unmet)** - `d52db5a` (docs)
3. **Task 2: provision GitHub Environments, deploy-nonprod VM identity, Docker Hub repository, runbook documentation** - `d15e37e` (feat)

**Plan metadata:** (this SUMMARY.md update, committed alongside or immediately after `d15e37e`)

_Note: no `test`/`refactor` commits — this plan is infra provisioning, not application code; the project's own `spotlessCheck`+`fastTest` pre-commit hook ran clean on every commit regardless._

## Files Created/Modified

- `docs/INFRA_RUNBOOK.md` - new section "Nonprod CI deploy identity and environment-scoped secrets — Plan 09-01" (identities table, verbatim proof outputs, secret inventory, accepted residuals); corrected "Operator note — deploying nonprod"
- `.planning/phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-01-SUMMARY.md` - this file

**External resources created (not repository files, but part of this plan's deliverable):**
- GitHub Environments `production` and `staging` (repository `RudVlad473/kanban-board-backend`)
- Nine environment secrets in each (`DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASS`, `NETCUP_SSH_KEY`, `NETCUP_DEPLOY_USER`, `NETCUP_HOST`, `NETCUP_HOST_FINGERPRINT`, `DOCKERHUB_TOKEN`)
- VM Linux user `deploy-nonprod` (`159.195.114.230`), `/home/deploy-nonprod/.ssh/authorized_keys`
- Docker Hub repository `rudenkovladimir/kanban-board-backend-nonprod` (public)

## Decisions Made

**Task 1 checkpoint resolved by the human operator:**
- **Selected: option-a** — confirm as planned: `deploy-nonprod` VM identity confined by standard Unix filesystem permissions only (`docker` group membership's root-equivalence explicitly accepted as a residual risk, per D-02's rejection of forced-command/restricted-shell hardening), plus one Docker Hub token duplicated into both GitHub Environments (account-wide token scope explicitly accepted as a residual risk, since per-repository Docker Hub token scoping is a paid-plan feature). Both residuals recorded in `docs/INFRA_RUNBOOK.md` (T-09-03, T-09-09 in the plan's threat register).
- **Mechanical resource names confirmed as proposed:** Linux user `deploy-nonprod`; Docker Hub repository `rudenkovladimir/kanban-board-backend-nonprod`; GitHub Environments `production` and `staging`; identical secret NAMES in both environments with per-environment values.

**Scope change — Task 3 deferred by explicit coordinator instruction:** this worktree is isolated; its commits live on a private per-agent branch until the orchestrator merges them to `master`. Task 3 as written pushes directly to `origin/master` and triggers a real, production-impacting GitHub Actions deploy run — a genuinely hard-to-reverse, production-impacting action the coordinator determined the human operator should watch happen live rather than have fire unattended from a background agent inside an isolated worktree. `.github/workflows/deploy.yml` was not touched this session.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Deleted the only local copy of the new nonprod private key, then rotated to fix it**
- **Found during:** Task 2, Part C (secret population), immediately after cleaning up scratch files
- **Issue:** After successfully populating all nine secrets, the freshly-generated `netcup_deploy_nonprod_key` private half was deleted from the session scratch directory as part of routine cleanup. This broke the project's own established precedent (documented in `docs/INFRA_RUNBOOK.md`'s "Deploy user setup — Plan 05-05 Task 1"): production's `netcup_deploy_key` is kept permanently at the operator's local `~/.ssh/`, giving a durable copy for future manual/debugging access outside CI. GitHub Environment secrets are write-only, so once a key exists only there, it is unrecoverable by any means — the deleted key would have been permanently lost for any purpose other than what CI already does with it.
- **Fix:** Caught before this document was finalized. Generated a second `netcup_deploy_nonprod_key`, this time persisted at the operator's local `~/.ssh/netcup_deploy_nonprod_key` (mirroring `netcup_deploy_key`'s location convention). Replaced (not appended — verified still exactly one key via `wc -l`) the public half in `/home/deploy-nonprod/.ssh/authorized_keys` on the VM, re-verified the new fingerprint both locally and on the VM and confirmed off-VM SSH access still works, then overwrote the `staging` environment's `NETCUP_SSH_KEY` secret with the new private half.
- **Files modified:** `docs/INFRA_RUNBOOK.md` (fingerprint value corrected to match the final key; an explicit "Deviation found and fixed" note added inline, consistent with this runbook's existing style of documenting real incidents rather than silently correcting them)
- **Verification:** Re-ran the plan's full Task 2 automated `<verify>` block after the rotation — all checks passed (`gh api .../environments` → `2`; both environments' secret name lists match exactly; repository secrets still `10`; Docker Hub `is_private: false`; runbook heading present). Fingerprints re-confirmed distinct from production's key and matching between the local file and the VM's `authorized_keys`.
- **Committed in:** `d15e37e` (Task 2 commit — the rotation happened before the commit, so the final committed runbook state already reflects the corrected key)

---

**Total deviations:** 1 auto-fixed (Rule 1 — bug in the executor's own cleanup step, not in the plan text)
**Impact on plan:** The fix was necessary to match this project's established credential-retention precedent and to give the operator future manual-access capability consistent with production's `deploy` user. No scope creep — the rotation only replaced the nonprod key end-to-end (VM + GitHub secret), touching nothing else.

## Issues Encountered

**Windows Gradle/pre-commit-hook file-lock (first checkpoint halt only, not a Task 2 issue):** the first attempt to commit the Task 1 checkpoint SUMMARY.md timed out at 2 minutes mid-`fastTest`; orphaned Gradle Test Executor JVMs from that timeout held Windows file handles open in `build/test-results/fastTest/binary/`, causing the retried commit's `fastTest` task to fail with "Unable to delete directory." Resolved by identifying and killing the three orphaned `java.exe` processes (confirmed via their command lines referencing this exact worktree path — not a sibling agent's work) and removing the now-unlocked, gitignored `build/test-results/fastTest` directory before retrying. Not a plan or implementation issue — a Windows process-lifecycle artifact of the harness's own 2-minute default Bash timeout colliding with a multi-minute Testcontainers-backed test suite.

**Task 2 precondition genuinely unmet on first pass, correctly halted rather than proceeding:** per the coordinator's explicit second-turn instruction, this agent checked Task 2's own `<precondition>` (authorized root SSH session + nine secret values) before attempting any provisioning, found it unmet (no SSH session, no secret values supplied yet), and halted with a second checkpoint rather than attempting to open a session or request values itself. This was the correct, designed behavior for a background agent with no real-time operator channel — not an error.

## User Setup Required

None outstanding for Task 1/Task 2 — the operator has already supplied everything needed (SSH session access, guidance on recoverable secrets, the Docker Hub token file path) and it has all been consumed and verified.

**Outstanding for Task 3 (not part of this session's scope):** the orchestrator (or a follow-up session, once this worktree's commits land on `master`) must run Task 3 — edit `.github/workflows/deploy.yml` to add `flyway-verify-nonprod` and `deploy-to-nonprod`, retrofit `environment:` onto every secret-reading job, push to `master`, and verify the live run under the human operator's direct observation.

## Next Phase Readiness

**Partially blocked, by design.** Task 1 and Task 2 are complete and independently verified — the credential boundary CI-02 requires now exists and is populated. Task 3 (the actual workflow wiring and its live end-to-end proof) was deliberately not run in this isolated worktree session; per the coordinator's explicit instruction, it must be run by the orchestrator (or a follow-up session with direct push access to `master` and the human operator watching) once this worktree's three commits (`8e2bafd`, `d52db5a`, `d15e37e`) have been merged back to `master`. Plans 09-02 (repository-secret sweep) and 09-03 both depend on Task 3's green run and should not be started before it completes.

---
*Phase: 09-nonprod-continuous-deploy-scoped-ci-credentials*
*Completed: 2026-08-18 (halted — Task 3 deliberately deferred)*
