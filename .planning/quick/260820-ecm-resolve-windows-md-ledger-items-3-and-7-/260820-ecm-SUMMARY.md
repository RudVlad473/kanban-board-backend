---
quick_id: 260820-ecm
status: complete
completed: 2026-08-20
---

# Quick Task 260820-ecm: Resolve WINDOWS.md ledger items #3 and #7

**Both open verification-debt entries closed — one by a fresh live proof, one by a bookkeeping
correction against work that was already done.**

## What was done

- **Item #7 (phase 10, deploy.yml digest-pin tracer):** confirmed live via already-pushed commit
  `586bed2` (tip of `master`, `origin/master` == `master`) and its CI run `32294906063` — every job
  green, including `deploy-to-netcup` and `deploy-to-nonprod`, both resolving the digest-pinned
  `appleboy/scp-action@ff85246ac...`/`appleboy/ssh-action@0ff4204d5...` SHAs (confirmed from
  `deploy.yml`'s own `uses:` lines, not assumed). Independently re-confirmed off-VM
  (`curl .../actuator/health` → `200` on both hosts) and on-VM (`docker inspect` on
  `kanban-board-backend-app-1` and `kanban-nonprod-app`, both running image tag `586bed2` — the
  exact commit the pinned-action run built and deployed). Recorded in a new
  `docs/INFRA_RUNBOOK.md` section: "Digest-pinned deploy actions — Plan 10-01 Task 1, live tracer
  (2026-08-20)".
- **Item #3 (phase 8, nonprod/production curl proof):** found to already be satisfied.
  `08-02-SUMMARY.md` and `docs/INFRA_RUNBOOK.md`'s existing "Nonprod reset endpoint — Plan 08-02"
  section show the live curl proof was completed in commit `c83d36e`
  (`git log -1 --format='%ci' c83d36e` → 2026-08-18T15:36:52+02:00, i.e. 13:36:52 UTC) — 24 minutes
  *after* the window was recorded (2026-08-18T13:12:05Z). The window was never flipped once the
  Task 3 re-dispatch landed. No new verification was performed for this item; the runbook update
  above documents this as a ledger correction, not a fresh check.
- `gsd-tools windows fixed 3` and `gsd-tools windows fixed 7` run — `.planning/WINDOWS.md` now
  shows `open_count: 1` (only item #8 remains: the Dependabot "Check for updates" UI log, which has
  no CLI/API surface and needs a human to look at the GitHub UI directly).

## Verification

- `gsd-tools windows status --raw` → `open_count: 1`, items #3 and #7 both `status: "fixed"` with
  non-null `resolved_at`.
- `./gradlew spotlessCheck` — passed clean (no Java touched; confirms nothing else regressed).

## Commits

Single commit, this quick task's docs artifacts plus the two source-tree files
(`docs/INFRA_RUNBOOK.md`, `.planning/WINDOWS.md`).

## Next Phase Readiness

Milestone v1.3 (Nonprod Environment & CI Hardening) now has only one open window (#8, human-only
Dependabot UI check) standing between it and `/gsd-complete-milestone`.
