---
phase: 09-nonprod-continuous-deploy-scoped-ci-credentials
plan: 01
subsystem: infra
tags: [github-actions, github-environments, docker-hub, ssh, netcup, ci-cd]

# Dependency graph
requires:
  - phase: 08-isolated-nonprod-environment-live-and-resettable
    provides: "docker-compose.nonprod.yml, the nonprod Neon branch, the nonprod VM directory layout, and the standing precedent that root VM work runs over the operator's own authorized SSH session"
provides: []
affects: [09-02-nonprod-continuous-deploy-scoped-ci-credentials, 09-03-nonprod-continuous-deploy-scoped-ci-credentials]

# Actuals (#2632)
actuals:
  tokens: 0
  tasks: 0
  commits: 0

# Tech tracking
tech-stack:
  added: []
  patterns: []

key-files:
  created: []
  modified: []

key-decisions:
  - "Task 1 checkpoint resolved by human operator: option-a — deploy-nonprod VM identity confined by Unix filesystem permissions only (docker group membership root-equivalence accepted as residual risk), plus one Docker Hub token duplicated into both GitHub Environments (account-wide token scope accepted as residual risk). Mechanical names confirmed as proposed: Linux user deploy-nonprod, Docker Hub repository rudenkovladimir/kanban-board-backend-nonprod, GitHub Environments production/staging, identical secret NAMES in both environments with per-environment values."

patterns-established: []

requirements-completed: []

coverage: []

# Metrics
duration: <5min
completed: 2026-08-18
status: halted
---

# Phase 09 Plan 01: Nonprod CI deploy identity and scoped secrets — Summary

**Task 1's decision checkpoint resolved (option-a) by the human operator; now halted a second time at Task 2's unmet precondition — no GitHub Environments, VM identity, Docker Hub repository, or workflow edits have been made.**

## Performance

- **Duration:** <10 min total across two halts (plan read, Task 1 checkpoint reached and resolved, Task 2 precondition checked and found unmet)
- **Started:** 2026-08-18T18:19:14Z (approx, per STATE.md)
- **Completed:** 2026-08-18
- **Tasks:** 0 of 3 completed
- **Files modified:** 0

## Accomplishments

- Read and parsed 09-01-PLAN.md in full, confirming Task 1 is a `type="checkpoint:decision" gate="blocking"` requiring an explicit human answer before any provisioning proceeds.
- Halted execution at Task 1 exactly as instructed — no GitHub API calls, no VM SSH session, no Docker Hub API calls, and no `.github/workflows/deploy.yml` edits were attempted.
- Received the coordinator's resolution of Task 1's checkpoint: **option-a** selected, mechanical resource names confirmed as proposed (see Decisions Made below).
- Evaluated Task 2's `<precondition>` and found it unmet: no live root SSH session to the Netcup VM has been authorized to this agent, and none of the nine secret values have been supplied. Per explicit coordinator instruction, halted again rather than attempting to open an SSH session or request secrets directly — this agent has no real-time channel to the operator.

## Task Commits

None. Task 1 is the checkpoint itself; no task work was performed before it.

**Plan metadata:** (this SUMMARY.md commit)

## Files Created/Modified

None.

## Decisions Made

**Task 1 checkpoint resolved by the human operator:**
- **Selected: option-a** — confirm as planned: `deploy-nonprod` VM identity confined by standard Unix filesystem permissions only (`docker` group membership's root-equivalence explicitly accepted as a residual risk, per D-02's rejection of forced-command/restricted-shell hardening), plus one Docker Hub token duplicated into both GitHub Environments (account-wide token scope explicitly accepted as a residual risk, since per-repository Docker Hub token scoping is a paid-plan feature). Both residuals must be recorded in `docs/INFRA_RUNBOOK.md` exactly as Task 2's `<action>` part D specifies (T-09-03, T-09-09 in the plan's threat register).
- **Mechanical resource names confirmed as proposed:** Linux user `deploy-nonprod`; Docker Hub repository `rudenkovladimir/kanban-board-backend-nonprod`; GitHub Environments `production` and `staging`; identical secret NAMES in both environments (`NETCUP_SSH_KEY`, `NETCUP_DEPLOY_USER`, `NETCUP_HOST`, `NETCUP_HOST_FINGERPRINT`, `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASS`, `DOCKERHUB_TOKEN`) with per-environment values.

## Deviations from Plan

None - plan execution halted at the designed checkpoint exactly as written. No deviation rules applied because no implementation work was attempted.

## Issues Encountered

None arising from implementation error. This is a second, expected, designed stopping point for this plan: Task 1's decision checkpoint is now resolved, but Task 2's own `<precondition>` requires the operator to have personally authorized a root SSH session to the Netcup VM (`159.195.114.230`) and to have the nine live secret values on hand — none of which can be recovered by any tool (GitHub secrets are write-only). This agent is a background agent with no real-time channel to the operator; per explicit coordinator instruction, it must not attempt to open an SSH session or request secret values itself, and must halt again with a checkpoint report if the precondition is not genuinely met. It is not met: no live root SSH session has been authorized to this agent, and no secret values have been supplied. No GitHub API, VM SSH, or Docker Hub API call was attempted.

## User Setup Required

**External services require manual configuration before this plan can proceed.** See this plan's `user_setup` frontmatter block in `09-01-PLAN.md` for the full detail. Summary:
- `github-environments`: operator must supply nine secret values (or authorize the agent to recover them from the VM) for both the `production` and `staging` GitHub Environments.
- `netcup-vm-root`: operator must authorize a root SSH session to `159.195.114.230` (or run Task 2's VM commands by hand and paste the outputs back).
- `dockerhub`: operator must create (or authorize the agent to create) the public repository `rudenkovladimir/kanban-board-backend-nonprod`.

**Status: still outstanding.** Task 1's decision is resolved but Task 2 cannot begin until the operator actively supplies these.

## Next Phase Readiness

**Blocked.** Task 1's decision checkpoint is resolved (option-a, names confirmed — see Decisions Made). Task 2 cannot start until the operator personally authorizes a root SSH session to `159.195.114.230` and supplies the nine live secret values listed in `09-01-PLAN.md`'s `user_setup` frontmatter (or explicitly authorizes the agent to recover the recoverable subset from the VM, per that block's per-secret `source` notes). No provisioning, no workflow edits, and no live deploy run have happened. Re-invoke this plan's executor once the operator confirms the SSH session is open and has supplied (or made available) the secret values; the continuation agent should resume directly at Task 2's provisioning actions using the option-a decision already recorded here.

---
*Phase: 09-nonprod-continuous-deploy-scoped-ci-credentials*
*Completed: 2026-08-18 (halted)*
