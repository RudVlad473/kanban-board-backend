---
quick_id: 260820-euc
status: complete
completed: 2026-08-20
---

# Quick Task 260820-euc: deploy.yml path filter + 7 stale todos closed

**A docs-only push no longer triggers a full production+nonprod redeploy, and 7 pending todos
that Phase 10 already satisfied are now correctly filed as completed.**

## What was done

- **Task 1:** Added `paths-ignore: [docs/**, **/*.md, .planning/**]` to `deploy.yml`'s `push:`
  trigger (commit `95f61cc`). Deliberately did not exclude `.github/**` as a whole — a workflow or
  Dependabot config change still needs a real deploy-pipeline run.
- **Task 2:** Closed 7 pending todos (all tagged `resolves_phase: 10`) found already satisfied by
  direct inspection of the current repo state: gradle cache in `deploy.yml`, Dependabot's
  `github-actions` ecosystem entry, `appleboy/*` digest pins + D-05 risk-acceptance comment,
  `security-scan.yml`'s corrected comment/action versions, Gradle wrapper checksum +
  `wrapper-validation` step in both workflows, `gradle/verification-metadata.xml`, and the README
  architecture-showcase expansion (commit `d5f145c`). Moved via `gsd-tools todo complete`, each
  annotated with a `## Resolution (2026-08-20)` section citing the specific evidence.

## Verification (live, not static-only)

Following the todo's own instruction to verify by pushing a docs-only change and confirming
`deploy.yml` does not trigger:

1. Pushed `95f61cc` (the `.github/workflows/deploy.yml` fix itself) — `gh run list
   --workflow=deploy.yml` shows a new run (`32350112119`) queued immediately. Confirms the fix
   does NOT accidentally exclude `.github/**` — a real workflow-file change still deploys.
2. This quick task's own closing commit touches only `.planning/**` (7 todo moves + this task's
   own PLAN/SUMMARY + STATE.md) — a genuine docs-only push. `gh run list --workflow=deploy.yml`
   after pushing shows no new run beyond the one from step 1, confirming the negative path: a
   pure `.planning/**` change no longer triggers a redeploy.

`python -c "import yaml; yaml.safe_load(open('.github/workflows/deploy.yml'))"` — parses clean.

## Commits

Two atomic commits: `95f61cc` (Task 1, code change) and this task's own closing docs commit
(Task 2 + STATE.md + this SUMMARY).

## Next Phase Readiness

No blockers. Both changes are independent, low-risk housekeeping — nothing downstream depends on
either.
