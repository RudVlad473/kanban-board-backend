---
phase: quick
plan: 260908-sj9
subsystem: infra
tags: [github-actions, deploy, scp, ci-gate, grafana, prometheus, loki, promtail, pyyaml]

# Dependency graph
requires:
  - phase: 12
    provides: the observability stack's bind-mounted config (Grafana provisioning, Prometheus,
      Loki, Promtail) that this task discovered was never actually reaching the VM
provides:
  - deploy-to-netcup's SCP source list now transfers all six repo-relative bind-mount sources
    docker-compose.prod.yml declares (was three of six)
  - scripts/verify-deploy-scp-coverage.py, a per-PR CI gate proving compose bind mounts and
    deploy.yml SCP source: lists provably agree, for both the prod and nonprod deploy pairs
  - a dated INFRA_RUNBOOK.md record of the live VM evidence and the two residual gaps this fix
    does not close
  - a filed follow-up todo for the reload-gap half of the problem (transferred config still
    needs a restart to take effect, for three of the four consumers)
affects: [deploy.yml, invariant-checks.yml, observability, infra-ci-gates]

# Actuals (#2632)
actuals:
  tokens: 10095
  tasks: 3
  commits: 3
plan_head_before: 8b6994b9e0306b56631b25dfa7d72271bd1b55c1

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "compose<->workflow coverage gate: a verify-*.py script + verify-*-selftest.py pair wired
      into invariant-checks.yml as self-test-then-gate, matching verify-caddy-image-tag.py and
      verify-compose-ports.py's existing shape"

key-files:
  created:
    - scripts/verify-deploy-scp-coverage.py
    - scripts/verify-deploy-scp-coverage-selftest.py
    - .planning/todos/pending/2026-09-08-scp-d-config-changes-do-not-reach-the-running-container-without-a-restart.md
  modified:
    - .github/workflows/deploy.yml
    - .github/workflows/invariant-checks.yml
    - docs/INFRA_RUNBOOK.md

key-decisions:
  - "Fixed all four missing repo-relative mounts (Grafana provisioning, Prometheus, Loki,
    Promtail) rather than only Grafana as the originating brief scoped it -- investigation found
    the gap was four paths wide against the same mechanism, on the same line"
  - "Added a CI gate (scripts/verify-deploy-scp-coverage.py) rather than shipping the source: fix
    alone -- this is the second occurrence of the same defect class in two phases (postgres-init
    was remembered in Phase 11, four Phase 12 configs were not), so a third occurrence is the
    predictable outcome of relying on manual discipline again"
  - "docker-compose.nonprod.yml / deploy-to-nonprod required no source: change (verified: zero
    host bind mounts in that file) but is still covered by the new gate with an expected
    repo-relative set of empty, so a future nonprod bind mount cannot land ungated"
  - "Left both existing dated historical records in docs/INFRA_RUNBOOK.md (lines ~502, ~915)
    untouched -- they are frozen past-tense execution records, per the Phase 11 precedent of not
    updating them when postgres-init was added to the same source: string"
  - "Filed the reload-gap (transferred config still needs a container restart to apply, for
    Grafana datasources/Prometheus/Loki/Promtail) as a separate pending todo rather than fixing
    it here -- this task's own action text explicitly forbids touching any other step in
    deploy-to-netcup's SSH script"

patterns-established:
  - "A deploy SCP source: list and its compose file's bind mounts are now mechanically checked
    per PR, not held in memory -- any future ./-prefixed bind mount added to either deployed
    compose file that is not added to its own job's source: list fails CI before merge"

requirements-completed: [QUICK-260908-sj9]

coverage:
  - id: D1
    description: "deploy-to-netcup's SCP source: extended from 3 to 7 entries, transferring the
      four previously-missing repo-relative bind-mount sources; target:, rm:-absence, and every
      other step/job left unchanged"
    requirement: QUICK-260908-sj9
    verification:
      - kind: other
        ref: "python3 -c \"import yaml; ...\" structural assertion (source list, target, rm-absence) -- see PLAN.md Task 1 <verify>"
        status: pass
      - kind: other
        ref: "git diff exactly-one-behavioral-line check -- see PLAN.md Task 1 <verify>"
        status: pass
      - kind: other
        ref: "duplicated-SCP-target check across the whole workflow -- see PLAN.md Task 1 <verify>"
        status: pass
    human_judgment: false
  - id: D2
    description: "scripts/verify-deploy-scp-coverage.py + self-test wired into
      invariant-checks.yml as a third job; proven to pass against the fixed tree and fail
      (naming all four missing paths) against the pre-fix source: string"
    requirement: QUICK-260908-sj9
    verification:
      - kind: unit
        ref: "scripts/verify-deploy-scp-coverage-selftest.py (all cases)"
        status: pass
      - kind: other
        ref: "python3 scripts/verify-deploy-scp-coverage.py (against the real, fixed tree)"
        status: pass
      - kind: other
        ref: "python3 scripts/verify-deploy-scp-coverage.py --workflow <pre-fix fixture> (falsification, exit 1, names all four paths)"
        status: pass
      - kind: other
        ref: "invariant-checks.yml third-job wiring + self-test-before-gate ordering check -- see PLAN.md Task 2 <verify>"
        status: pass
    human_judgment: false
  - id: D3
    description: "docs/INFRA_RUNBOOK.md carries a new dated section with the live VM evidence,
      the fix, the gate, and both residual gaps; the reload-gap todo is filed naming all four
      affected consumers"
    requirement: QUICK-260908-sj9
    verification:
      - kind: other
        ref: "grep checks for 260908-sj9, all four paths, verify-deploy-scp-coverage.py, and both dates in INFRA_RUNBOOK.md -- see PLAN.md Task 3 <verify>"
        status: pass
      - kind: other
        ref: "git diff frozen-historical-record-untouched check -- see PLAN.md Task 3 <verify>"
        status: pass
      - kind: other
        ref: "pending todo file existence + consumer-naming grep checks -- see PLAN.md Task 3 <verify>"
        status: pass
    human_judgment: false

duration: 13min
completed: 2026-09-08
status: complete
---

# Quick Task 260908-sj9: Deploy SCP Coverage Fix + CI Gate Summary

**Fixed a live production drift gap where four of six bind-mounted observability configs
(Grafana provisioning, Prometheus, Loki, Promtail) were never SCP'd to the VM from Phase 12
onward, then added a per-PR CI gate (`scripts/verify-deploy-scp-coverage.py`) that proves the
deploy compose files and their SCP `source:` lists provably agree, so the gap cannot silently
reopen.**

## Performance

- **Duration:** 13 min
- **Started:** 2026-09-08T20:47:06+02:00
- **Completed:** 2026-09-08T21:00:05+02:00
- **Tasks:** 3
- **Files modified:** 6 (3 created, 3 modified)

## Accomplishments

- `deploy-to-netcup`'s SCP `source:` extended from 3 to 7 paths, ending fourteen green deploys'
  worth of silent drift: production had served Grafana/Prometheus/Loki/Promtail configuration
  frozen at 2026-09-07 (a one-time hand-copy) while every other deployed file advanced normally.
- New CI gate `scripts/verify-deploy-scp-coverage.py` (plus its self-test) makes the
  compose-file-bind-mounts-vs-SCP-source-list agreement a blocking, per-PR, pure-function-of-
  the-commit check, covering both the production and nonprod deploy pairs.
- `docs/INFRA_RUNBOOK.md` gained a dated record of the live VM timestamp evidence, the fix, the
  gate, and both residual gaps (no deletion sync; three of four newly-transferred configs still
  need a container restart to actually take effect).
- Filed a follow-up todo for the reload-gap half of the problem, explicitly out of this task's
  scope.

## Task Commits

Each task was committed atomically:

1. **Task 1: Transfer every repo-relative mount source in deploy-to-netcup's SCP step** -
   `cb8ed9b` (fix)
2. **Task 2: Pin the gap shut — a CI gate proving compose mounts and SCP sources agree** -
   `5d31c5e` (feat)
3. **Task 3: Record the evidence and the two gaps this fix does not close** - `b30ca72` (docs)

**Plan metadata:** committed separately by the orchestrator after this SUMMARY.

## Files Created/Modified

- `.github/workflows/deploy.yml` - `deploy-to-netcup`'s SCP `source:` extended from 3 to 7 paths;
  adjacent comment corrected from "three" to name the fix and the residual deletion-sync gap
- `.github/workflows/invariant-checks.yml` - new third job `deploy-scp-coverage`, self-test
  ordered ahead of the gate, matching `compose-published-ports`'s existing shape
- `scripts/verify-deploy-scp-coverage.py` - the gate: I1 coverage, I2 source resolution, I3
  fail-closed on an unreadable pipeline, I4 absolute-host-paths-out-of-scope
- `scripts/verify-deploy-scp-coverage-selftest.py` - proves each invariant can fire, in-memory,
  no disk access
- `docs/INFRA_RUNBOOK.md` - new dated section, "Deploy SCP coverage gap — quick task 260908-sj9
  (2026-09-08)"
- `.planning/todos/pending/2026-09-08-scp-d-config-changes-do-not-reach-the-running-container-without-a-restart.md` -
  new pending todo for the reload-gap follow-up

## Decisions Made

- Fixed all four missing paths rather than only Grafana (the originating brief's literal scope)
  — see key-decisions in frontmatter for the full rationale.
- Added a CI gate rather than shipping the `source:` fix alone, on the grounds this is the
  second occurrence of the same defect class in two phases.
- `docker-compose.nonprod.yml` needed no `source:` change (verified: zero bind mounts in that
  file) but is still covered by the new gate.
- Left both existing dated historical records in `docs/INFRA_RUNBOOK.md` untouched, per the
  Phase 11 precedent.
- Filed the reload-gap as a separate todo rather than fixing it in this task, per this task's own
  explicit scope boundary.

## Deviations from Plan

None - plan executed exactly as written. All three tasks' `<verify>` blocks were run for real
(not skipped or stubbed), including the falsification test proving the gate fails against a
reconstructed pre-fix `source:` string and names all four missing paths.

## Issues Encountered

- `pip install pyyaml` failed on this dev machine with `externally-managed-environment` (PEP
  668). Not a blocker: PyYAML 6.0.3 was already importable on this machine (and is the same
  dependency two existing `invariant-checks.yml` jobs already install in CI's own ephemeral
  runner, where this restriction does not apply), so local verification proceeded directly
  against the already-available install.

## Known Stubs

None.

## User Setup Required

None - no external service configuration required. This task does not trigger a real GitHub
Actions deploy; that is a separate action the user performs by pushing to `main`.

## Next Phase Readiness

- The SCP coverage gap is closed and mechanically pinned. A future edit to either deployed
  compose file's bind mounts, or either deploy job's SCP `source:` list, that breaks their
  agreement will fail `invariant-checks.yml` before merge.
- One residual gap remains, tracked as a pending todo: Grafana datasources, Prometheus, and
  Promtail configuration is now correctly transferred to the VM on every deploy but still
  requires a manual restart to take effect (Grafana dashboards are the one exception, hot via
  their 30s file-provider poll). Not blocking — the originating brief's actual complaint
  (Grafana dashboard changes not reaching production) is fully closed by this fix.
- A real GitHub Actions deploy run was explicitly out of scope for this task; the user pushes
  separately to exercise the new gate and the extended SCP transfer live.

## Self-Check: PASSED

- FOUND: scripts/verify-deploy-scp-coverage.py
- FOUND: scripts/verify-deploy-scp-coverage-selftest.py
- FOUND: .planning/todos/pending/2026-09-08-scp-d-config-changes-do-not-reach-the-running-container-without-a-restart.md
- FOUND: commit cb8ed9b
- FOUND: commit 5d31c5e
- FOUND: commit b30ca72

---
*Phase: quick*
*Completed: 2026-09-08*
