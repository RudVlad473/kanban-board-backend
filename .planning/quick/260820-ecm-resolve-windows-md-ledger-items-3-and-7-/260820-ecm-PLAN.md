---
quick_id: 260820-ecm
type: quick
files_modified:
  - docs/INFRA_RUNBOOK.md
  - .planning/WINDOWS.md
---

<objective>
Close WINDOWS.md ledger items #3 and #7 — both are `unrun-verify` entries whose required live proof
already exists (or is trivially obtainable from already-pushed, already-green infrastructure) but
was never recorded/marked fixed.

- Item #3 (phase 8): "08-02 Task 3: live curl proof against nonprod/production and runbook record
  not run" — recorded 2026-08-18T13:12:05Z, before the Task 3 re-dispatch's precondition was
  satisfied. `08-02-SUMMARY.md` and `docs/INFRA_RUNBOOK.md`'s existing "Nonprod reset endpoint —
  Plan 08-02" section already show this proof was completed in commit `c83d36e`
  (2026-08-18T13:36:52Z) — 24 minutes after the window was recorded. This is a stale ledger entry,
  not outstanding work.
- Item #7 (phase 10): "Task 1 tracer real push-to-master + gh run watch deploy proof deferred to
  post-merge" — recorded 2026-08-19T15:31:56Z. Master has since absorbed all of phase 10 (plans
  10-02 through 10-06) and is fully pushed (`origin/master` == `master`). The push of the tip commit
  `586bed2` already triggered a full green CI/CD run (`32294906063`) that exercises the
  digest-pinned `appleboy/*` actions this item's proof requires — no fresh throwaway push is
  needed, only observing and recording that run.
</objective>

<task id="1">
  <name>Record the live proof for item #7 and the item #3 bookkeeping correction, then flip both to fixed</name>
  <files>docs/INFRA_RUNBOOK.md, .planning/WINDOWS.md</files>
  <action>
1. Append a new `## Digest-pinned deploy actions — Plan 10-01 Task 1, live tracer (2026-08-20)`
   section to `docs/INFRA_RUNBOOK.md` (before `## Maintenance note`), following the file's own
   established section convention. Record: `gh run view 32294906063`'s full green job list;
   `deploy-to-netcup`/`deploy-to-nonprod`'s step-level confirmation they resolved the
   digest-pinned `appleboy/scp-action@ff85246ac...`/`appleboy/ssh-action@0ff4204d5...` SHAs; off-VM
   `curl .../actuator/health` → 200 for both hosts; on-VM `docker inspect` on both
   `kanban-board-backend-app-1` and `kanban-nonprod-app` confirming both run image tag `586bed2`,
   matching the run's own commit. Close with a short note that this also closes item #3 as a
   bookkeeping correction (not new verification), citing the `c83d36e` timestamp vs. the window's
   `recorded_at` timestamp as evidence the underlying work predates and satisfies the ledger entry.
2. Run `gsd-tools windows fixed 3` and `gsd-tools windows fixed 7` to flip both ledger entries'
   `status` to `fixed` with a `resolved_at` timestamp in `.planning/WINDOWS.md`.
  </action>
  <verify>`gsd-tools windows status --raw` shows `open_count: 1` (only item #8, the
  Dependabot-UI-only check, remains open) and items #3/#7 both show `status: "fixed"` with a
  non-null `resolved_at`.</verify>
  <done>docs/INFRA_RUNBOOK.md contains the new section with the live command output recorded above;
  .planning/WINDOWS.md shows items #3 and #7 as fixed.</done>
</task>
