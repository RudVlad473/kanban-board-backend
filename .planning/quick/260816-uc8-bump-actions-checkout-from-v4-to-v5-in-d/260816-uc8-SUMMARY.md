---
phase: quick-260816-uc8
plan: 01
subsystem: infra
tags: [github-actions, ci-cd, deploy, node-deprecation, actions-checkout]

# Dependency graph
requires:
  - phase: quick-260816-sv1
    provides: "Bumped the three v3-pinned actions/checkout steps (run-tests, build-and-push-docker-image, flyway-verify) to @v5, deliberately leaving deploy-to-netcup's already-v4 pin out of that task's v3-> scope"
provides:
  - "deploy-to-netcup's Checkout code step now runs actions/checkout@v5 (node24), closing the last Node-runtime deprecation warning anywhere in deploy.yml"
  - "All four actions/checkout references in deploy.yml are now uniformly @v5"
affects: [infra-migration, deploy.yml, ci-cd]

actuals:
  tokens: 122
  tasks: 2
  commits: 1

tech-stack:
  added: []
  patterns: []

key-files:
  created: []
  modified:
    - .github/workflows/deploy.yml

key-decisions:
  - "One-line in-place bump verified by a real master push and a live before/after log diff (Approach A) -- matches the precedent already proven twice this session for the three sibling jobs, no design fork remained."
  - "No per-step comment added for the pin change, matching the three sibling Checkout code steps' own zero-comment convention and keeping the diff at exactly one line."

patterns-established: []

requirements-completed: [TODO-260802-rq5-UNIT-B-CI-RESIDUAL]

coverage:
  - id: D1
    description: "deploy-to-netcup's actions/checkout pin bumped from @v4 to @v5, eliminating the job's Node-runtime deprecation warning, proven on a real green master run"
    requirement: "TODO-260802-rq5-UNIT-B-CI-RESIDUAL"
    verification:
      - kind: other
        ref: "gh run view 31969094633 --log | grep -P '^deploy-to-netcup\\t' | grep -ciE 'node.*deprecat|deprecat.*node' -> 0 (baseline run 31967459100 was 3)"
        status: pass
      - kind: other
        ref: "gh run view 31969094633 --json conclusion --jq .conclusion -> success"
        status: pass
    human_judgment: false

duration: ~15min (majority spent watching the live CI run, ~8m41s wall clock across 5 jobs)
completed: 2026-08-16
status: complete
---

# Quick Task 260816-uc8: Bump deploy-to-netcup's actions/checkout to v5 Summary

**Bumped `deploy-to-netcup`'s `Checkout code` step from `actions/checkout@v4` to `@v5`, the last residual Node-20-deprecated action reference in `deploy.yml`, proven clean on a real green production deploy.**

## Performance

- **Duration:** ~15 min (baseline capture + one-line edit + commit were fast; most of the time was watching the real CI run to completion, 8m41s wall clock across run-tests/build-and-push-docker-image/flyway-verify/deploy-to-netcup/cleanup-old-images)
- **Tasks:** 2/2
- **Files modified:** 1 (`.github/workflows/deploy.yml`)

## Accomplishments

- `deploy-to-netcup`'s `Checkout code` step now declares `actions/checkout@v5` (node24 runtime), matching the three sibling `Checkout code` steps in the same file (lines 28, 58, 108) that quick task `260816-sv1` already bumped.
- All four `actions/checkout` references in `deploy.yml` are now uniformly `@v5` -- no pre-v5 pin survives anywhere in the file.
- Verified end-to-end on a real production deploy: the full `deploy.yml` pipeline (`run-tests` -> `build-and-push-docker-image` -> `flyway-verify` -> `deploy-to-netcup` -> `cleanup-old-images`) concluded `success`, and `deploy-to-netcup`'s Node-deprecation line count dropped from 3 (baseline) to 0 (after).

## Task Commits

Task 1 was read-only evidence capture (no file changes, no commit). Task 2's file change was committed atomically:

1. **Task 1: Capture the verbatim Node-deprecation baseline** - no commit (read-only; baseline recorded below)
2. **Task 2: Apply the one-line bump, push to master, prove the warning is gone** - `3d4ad95` (fix)

**Plan metadata:** committed separately by the orchestrator after this SUMMARY (per this task's constraints, this agent does not commit `.planning/` docs).

## Files Created/Modified

- `.github/workflows/deploy.yml` - `deploy-to-netcup` job's `Checkout code` step: `uses: actions/checkout@v4` -> `uses: actions/checkout@v5` (one line changed, one insertion, one deletion)

## Before/After Evidence (live CI logs, not local inspection)

**Baseline run:** `31967459100` (head `94b7a1f`, conclusion `success`, created `2026-08-16T19:24:01Z`) -- the most recent completed `success` run at planning time, per this task's documented fallback (a second run, `31968657541`, was still in flight and completed independently before this task's own push).

Verbatim `deploy-to-netcup` Node-deprecation lines from the baseline run (3 lines, all attributable to `actions/checkout@v4`):

```
deploy-to-netcup	Checkout code	2026-08-16T19:32:29.0935942Z Node 20 is being deprecated. This workflow is running with Node 24 by default. If you need to temporarily use Node 20, you can set the ACTIONS_ALLOW_USE_UNSECURE_NODE_VERSION=true environment variable. For more information see: https://github.blog/changelog/2025-09-19-deprecation-of-node-20-on-github-actions-runners/
deploy-to-netcup	Post Checkout code	2026-08-16T19:32:46.2475760Z Node 20 is being deprecated. This workflow is running with Node 24 by default. If you need to temporarily use Node 20, you can set the ACTIONS_ALLOW_USE_UNSECURE_NODE_VERSION=true environment variable. For more information see: https://github.blog/changelog/2025-09-19-deprecation-of-node-20-on-github-actions-runners/
deploy-to-netcup	Complete job	2026-08-16T19:32:46.4957619Z ##[warning]Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: actions/checkout@v4. For more information see: https://github.blog/changelog/2025-09-19-deprecation-of-node-20-on-github-actions-runners/
```

**After run:** `31969094633` (head `3d4ad95`, this task's own push, conclusion `success`)

`deploy-to-netcup`'s log for this run carries **zero** Node-runtime deprecation lines. (An initial naive `grep 'deploy-to-netcup'` over the full log's non-job-scoped text returned 2 false-positive matches -- those lines actually came from the `build-and-push-docker-image` job's Docker image-label output, which happened to echo this commit's own message text, itself containing the words "deploy-to-netcup", "deprecat", and "node" as prose. Re-run with a job-column-anchored filter (`^deploy-to-netcup\t`) to attribute strictly by job, confirming the true count is 0.)

Full pipeline conclusion for the after run: `success` across every job, including `deploy-to-netcup` (20s) and `cleanup-old-images` (3s). `cleanup-unused-image` did not run (`if: failure()`, and the run succeeded).

## Decisions Made

- **Approach A (one-line bump, live-verified)** chosen over bundling with `security-scan.yml`'s stale pins (Approach B, rejected -- out of this task's locked one-file scope) or verifying without a production push via `act`/`workflow_dispatch` (Approach C, rejected -- cannot reproduce GitHub's runner-side deprecation annotation, and this job's real SSH-to-VM step cannot be meaningfully stood in for). Digest-pinning while the file was open (Approach D) was also rejected -- would leave the file less internally consistent than a uniform tag-pin, and the digest-pin policy question already has its own filed todo.
- No comment added for the one-line pin change, matching the three sibling steps' own zero-comment convention.

## Deviations from Plan

None - plan executed exactly as written. The one procedural wrinkle (the naive job-name substring grep briefly showing 2 false-positive matches from an unrelated job's log output) was caught and resolved within Task 2's own verification step, not a deviation from the plan -- the plan's own automated verify commands used the correct job-scoped `grep 'deploy-to-netcup'` pattern against `gh run view --log`'s per-job-prefixed output, and the false positive was an artifact of this agent's own ad-hoc intermediate check, not the plan's documented gate. The plan's actual verify commands (reproduced below) return the correct values.

## Issues Encountered

None beyond the false-positive grep noted above, resolved before drawing any conclusion from it.

## Verification Gates (from PLAN.md)

1. `deploy-to-netcup` job's `Checkout code` step declares `actions/checkout@v5` -- **PASS** (job-scoped count: 1)
2. `deploy.yml` contains four `actions/checkout` references, all `@v5` -- **PASS** (file-scoped count: 4/4, zero pre-v5 pins remain)
3. Commit diff touches exactly one non-`.planning` file, one insertion, one deletion -- **PASS** (`git diff --stat HEAD~1 HEAD`: `.github/workflows/deploy.yml | 2 +-`, `1 file changed, 1 insertion(+), 1 deletion(-)`)
4. Newest `master` run of `deploy.yml` concludes `success` across every job, including the production deploy -- **PASS** (run `31969094633`, conclusion `success`)
5. That run's `deploy-to-netcup` log carries zero Node-runtime deprecation lines -- **PASS** (0, down from baseline's 3, all correctly attributed above)
6. Baseline lines quoted verbatim in this SUMMARY alongside both run ids -- **PASS** (see Before/After Evidence section)

## Known-Open Noise (not attributable to this change, not fixed here)

- `cleanup-old-images`'s log for this run still shows `{"message":"unauthorized","errinfo":{}}` on its Docker Hub `DELETE` calls -- the pre-existing, already-filed bug (`.planning/todos/pending/2026-08-16-cleanup-old-images-delete-calls-rejected-unauthorized.md`). Expected, unrelated to this change, not fixed here.
- No Node-deprecation line survived attributable to `appleboy/scp-action@v1.0.0` or `appleboy/ssh-action@v1.2.5` -- the residual-warning contingency plan for those two third-party actions did not need to trigger; nothing was filed for them.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `deploy.yml` now carries a single uniform `actions/checkout@v5` pin across all four jobs -- the file's action-version story is fully closed out.
- `security-scan.yml`'s own stale `checkout@v3`/`setup-java@v4` pins remain open in their own separate, pre-existing todo, deliberately untouched by this task's locked scope.
- No blockers for Phase 05 (`infra-migration`, currently at plan 6 of 6 per STATE.md) -- this was a fully independent quick task.

---
*Phase: quick-260816-uc8*
*Completed: 2026-08-16*

## Self-Check: PASSED

- FOUND: `.github/workflows/deploy.yml`
- FOUND: `.planning/quick/260816-uc8-bump-actions-checkout-from-v4-to-v5-in-d/260816-uc8-SUMMARY.md`
- FOUND: commit `3d4ad95` in git log
