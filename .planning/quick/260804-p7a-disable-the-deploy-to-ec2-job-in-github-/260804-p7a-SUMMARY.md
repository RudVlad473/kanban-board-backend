---
phase: quick-260804-p7a
plan: 01
subsystem: infra
tags: [github-actions, ci-cd, deploy, docker-hub]

# Dependency graph
requires: []
provides:
  - "deploy-to-ec2 GitHub Actions job unconditionally skipped (if: false) so pushes to master stop failing on a deleted AWS EC2 host"
  - "Explanatory comment above deploy-to-ec2 documenting why it's off, what still runs, and that Phase 5 must rewrite (not re-enable) it"
  - "Pending todo (resolves_phase: 5, severity: major) tracking the rewrite, the Docker Hub tag-accumulation side effect, and the pre-existing truncated curl -X DELETE defect"
affects: [phase-5-infra-migration]

# Actuals (#2632)
actuals:
  tokens: 1232
  tasks: 2
  commits: 2

tech-stack:
  added: []
  patterns: []

key-files:
  created:
    - .planning/todos/pending/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md
  modified:
    - .github/workflows/deploy.yml

key-decisions:
  - "Chose if: false + explanatory comment (Approach A) over commenting out the job block, a repo-variable gate, or deleting the job — the only option that changes deploy behavior without changing the dependency graph shape, keeping both cleanup jobs' needs: [deploy-to-ec2, ...] references valid"
  - "Left the Docker Hub tag-accumulation side effect (cleanup-old-images also skips) as an accepted trade-off rather than rewiring the cleanup jobs' needs: — that would widen the diff past 'disable one job' and be reverted again in Phase 5 anyway"
  - "Did not fix the pre-existing truncated curl -X DELETE in cleanup-unused-image (out of scope) — recorded in the todo as further evidence the file needs a Phase 5 rewrite, not a revival"

patterns-established: []

requirements-completed: [QUICK-260804-p7a]

coverage:
  - id: D1
    description: "deploy-to-ec2 job is unconditionally skipped (if: false) instead of failing on every push to master"
    requirement: "QUICK-260804-p7a"
    verification:
      - kind: other
        ref: "grep-based gate G1: awk-scoped grep -c 'if: false' inside the deploy-to-ec2 job region == 1"
        status: pass
    human_judgment: false
  - id: D2
    description: "run-tests and build-and-push-docker-image remain unaffected, still gated on if: success()"
    requirement: "QUICK-260804-p7a"
    verification:
      - kind: other
        ref: "grep-based gates G2/G3: awk-scoped grep -c 'if: success()' inside each job region == 1"
        status: pass
    human_judgment: false
  - id: D3
    description: "Both cleanup jobs (cleanup-old-images, cleanup-unused-image) skip cleanly once deploy-to-ec2 is skipped, per two independent documented GitHub Actions semantics reasons"
    verification: []
    human_judgment: true
    rationale: "No GitHub Actions run is executable from this sandbox. The claim rests on documented needs:/status-function semantics (F-3 in PLAN.md), not an observed run. First real confirmation arrives on the next push to master."
  - id: D4
    description: "Explanatory comment above deploy-to-ec2 conveys why it's disabled, what still runs, and that Phase 5 must rewrite it"
    requirement: "QUICK-260804-p7a"
    verification:
      - kind: other
        ref: "git diff -U0 review (G7): all added lines are # comments plus the single if: false line; comment covers all 5 required points"
        status: pass
    human_judgment: false
  - id: D5
    description: "Pending todo filed at the exact path the comment references, tagged resolves_phase: 5 and severity: major, carrying forward the Docker Hub tag-accumulation and truncated curl findings"
    requirement: "QUICK-260804-p7a"
    verification:
      - kind: other
        ref: "grep-based gates G8-G13 in PLAN.md Task 2 verify block"
        status: pass
    human_judgment: false

duration: 12min
completed: 2026-08-04
status: complete
---

# Quick Task 260804-p7a: Disable deploy-to-ec2 CI job Summary

**Skipped the deploy-to-ec2 GitHub Actions job (if: false) so pushes to master stop failing on a deleted AWS EC2 host, while leaving tests/build unaffected and filing a resolves_phase: 5 todo to rewrite it against the Oracle VM target.**

## Performance

- **Duration:** 12 min
- **Started:** 2026-08-04T16:06:00Z (approx.)
- **Completed:** 2026-08-04T16:18:23Z
- **Tasks:** 2
- **Files modified:** 2 (1 modified, 1 created)

## Accomplishments
- `.github/workflows/deploy.yml`'s `deploy-to-ec2` job condition changed from `if: success()` to `if: false`, with a 10-line explanatory comment inserted immediately above the job key
- Verified via `git diff -U0` that the only changed lines are the new comment block and the single `if:` value swap — no secret reference, step body, `needs:` list, or line ending was touched, and CRLF endings were preserved throughout
- Filed `.planning/todos/pending/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md` (`resolves_phase: 5`, `severity: major`) carrying forward two findings that would otherwise be lost: the Docker Hub tag-accumulation side effect (both cleanup jobs also stopped running) and the pre-existing truncated `curl -X DELETE` in `cleanup-unused-image`

## Task Commits

Each task was committed atomically:

1. **Task 1: Skip the deploy-to-ec2 job with an explanatory comment** - `c350940` (chore)
2. **Task 2: File the pending todo for re-enabling and rewriting the deploy job** - `6ad98ae` (docs)

_Note: `.planning/config.json` shows as modified in `git status` but was pre-existing and unrelated to this task — left untouched and unstaged._

## Files Created/Modified
- `.github/workflows/deploy.yml` - `deploy-to-ec2`'s `if:` condition changed to `false`; explanatory comment added above the job
- `.planning/todos/pending/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md` - New pending todo tracking the Phase 5 rewrite

## Decisions Made
- Picked `if: false` + comment (Approach A from the plan's trade-off matrix) over commenting out the job block (Approach B — breaks the workflow entirely because both cleanup jobs' `needs:` would reference an unknown job), a repo-variable gate (Approach C — moves an auth-sensitive control out of version control, unreviewable), or deleting the job (Approach D — loses reference material Phase 5 needs). Full matrix is in the plan.
- Left the Docker Hub tag-accumulation side effect unfixed (accepted trade-off, `T-p7a-04` in the plan's threat register, severity low, disposition accept) rather than rewiring the cleanup jobs' `needs:` to exclude `deploy-to-ec2` — that widens the diff past "disable one job" and would need to be undone again in Phase 5.
- Did not fix the pre-existing truncated `curl -s -X DELETE` in `cleanup-unused-image` (F-5 in the plan) — out of this task's stated scope; recorded in the todo instead as evidence Phase 5 should rewrite rather than revive the file.

## Deviations from Plan

None - plan executed exactly as written. Both tasks' automated verification gates (G1-G13) passed on the first attempt with no rework.

## Verification Method Note (F-3 / D3 above)

Per the plan's own verification section: **no GitHub Actions run is executable from this sandbox.** The claim that both `cleanup-old-images` and `cleanup-unused-image` skip cleanly once `deploy-to-ec2` is skipped rests on two independent readings of GitHub's documented semantics, not an observed run:

1. **Skip propagation** — GitHub's `jobs.<job_id>.needs` documentation states a skip in a dependency chain propagates downstream unless the downstream job's condition uses `always()` or `!cancelled()`-style guards. Neither cleanup job does, so both are skipped.
2. **Status-function evaluation** — independently, a skipped job's result is `skipped` (neither `success` nor `failure`). `success()` requires all `needs` jobs to have succeeded (false once `deploy-to-ec2` is skipped), and `failure()` requires an ancestor to have actually **failed** (also false — skipped is not failure).

Both readings converge on the same outcome. First empirical confirmation arrives on the next push to `master`: `setup`, `run-tests`, `build-and-push-docker-image` should show green; `deploy-to-ec2`, `cleanup-old-images`, `cleanup-unused-image` should show grey/skipped, with the overall run green. If any of the three shows red instead of skipped, this reasoning was wrong and needs revisiting — flagged as `human_judgment: true` (D3 above) rather than auto-passed.

## Issues Encountered
None. The file's git-config `core.autocrlf=true` setting meant CRLF endings needed explicit verification after the edit (confirmed via `file` and raw byte inspection) rather than being taken on faith from the plan's F-6 note.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- The workflow file is reactivation-ready: all secrets, step bodies, and the `needs:` dependency graph survive verbatim, so Phase 5 (Infra Migration, INFRA-01..INFRA-08) can rewrite `deploy-to-ec2` in place against the new Oracle Cloud VM target.
- Blocker for Phase 5 completion: the filed todo explicitly states Phase 5 must not be marked complete while `deploy-to-ec2` is still skipped (`if: false`).
- Outstanding, not addressed here: Docker Hub tags accumulated since this change lands will need pruning as part of Phase 5's cutover (see todo `## Solution`).

---
*Phase: quick-260804-p7a*
*Completed: 2026-08-04*

## Self-Check: PASSED

- FOUND: `.github/workflows/deploy.yml`
- FOUND: `.planning/todos/pending/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md`
- FOUND: `.planning/quick/260804-p7a-disable-the-deploy-to-ec2-job-in-github-/260804-p7a-SUMMARY.md`
- FOUND commit `c350940` (Task 1)
- FOUND commit `6ad98ae` (Task 2)
