---
audit_acknowledged:
  milestone: v1.4
  at: 2026-09-30
  status: unknown
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
