---
phase: 09-nonprod-continuous-deploy-scoped-ci-credentials
plan: 02
subsystem: infra
tags: [github-actions, github-environments, docker-hub, ci-cd, health-check]

# Dependency graph
requires:
  - phase: 09-01
    provides: "Two GitHub Environments (production/staging) with nine scoped deploy secrets each, deploy-to-nonprod/flyway-verify-nonprod wired into deploy.yml, a live-verified green run (32184033760) proving every secret-reading job already resolves through a declared environment:"
provides:
  - "health-check-nonprod job (deploy.yml): bounded 30x10s poll of nonprod's actuator/health endpoint, gates the run red with ::error:: when nonprod never comes up (CI-04) -- code complete, live green (2/30 attempts) and live red (full 300s bound exhausted) paths both observed"
  - "cleanup-old-images-nonprod and cleanup-unused-image-nonprod jobs (deploy.yml): nonprod's own image-retention pair, isolated to base_image_name_nonprod exclusively, both already-fixed Docker Hub bugs (JWT login, next-link pagination) inherited (CI-03) -- code complete, live idempotency (zero deletes on re-run) and cross-repository isolation (both repos list exactly their own current tag) both observed"
  - "docs/INFRA_RUNBOOK.md section documenting the plan's final job graph, the health-poll bound arithmetic (with real measured timing), and the retention semantics/asymmetry"
  - "Mechanical re-verification that every deploy.yml job interpolating secrets. declares an environment:, including this plan's three new jobs -- holds"
  - "Repository-level deploy secret sweep complete: nine plan-scoped secrets plus one unreferenced orphan (NONPROD_RESET_TOKEN) deleted; gh secret list returns exactly NVD_API_KEY"
affects: [09-03-nonprod-continuous-deploy-scoped-ci-credentials]

# Actuals (#2632)
actuals:
  tokens: 5000
  tasks: 3
  commits: 6

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Separate health-gate job (not a step inside the deploy job) to avoid holding a deploy concurrency lock for the duration of a bounded poll"
    - "Per-environment Docker Hub image-retention pair, isolated by interpolating only that environment's setup output -- never a hand-typed repository path segment"
    - "Live-infrastructure-affecting plan steps (secret deletion, push-to-master, deliberate outage induction) deferred out of an isolated git worktree to the merged tree under direct human/coordinator observation -- same pattern Plan 09-01 recorded for its own Task 3"

key-files:
  created: []
  modified:
    - ".github/workflows/deploy.yml -- added health-check-nonprod, cleanup-old-images-nonprod, cleanup-unused-image-nonprod jobs"
    - "docs/INFRA_RUNBOOK.md -- new section 'Nonprod CI health gate and image retention -- Plan 09-02'; annotated the original 'Repository secret inventory' table"

key-decisions:
  - "All three tasks' file-level (YAML + docs) work was completed and statically verified inside an isolated worktree; the live-infrastructure actions each task's acceptance criteria also required (gh secret delete, a live push to origin/master, observing/inducing live GitHub Actions runs) were deliberately NOT executed from that worktree -- deletion is rated costly reversibility and a live push must happen from the merged tree under direct human/coordinator observation, exactly the reasoning Plan 09-01's own runbook section already recorded for its Task 3 when it hit the identical worktree/live-action conflict. All were subsequently completed live by the human operator after the orchestrator merged the worktree to master (see 'Live Verification' below)."
  - "A tenth, orphaned repository secret (NONPROD_RESET_TOKEN) was found during the sweep, unreferenced by any workflow or doc. Deleted alongside the plan's nine after explicit operator confirmation, since leaving it would have both violated the plan's own acceptance criterion (gh secret list returns exactly NVD_API_KEY) and left unnecessary attack surface."
  - "The red path for health-check-nonprod was proven by temporarily pointing NONPROD_HEALTH_URL at an unreachable .invalid host (the plan's own documented alternative) rather than racing a live container stop/start against the poll window -- deterministic, and never touched the actually-running nonprod stack."
  - "security-scan.yml's dependency-check job was found already failing on 'NVD_API_KEY repository secret is not set' two days before this sweep (2026-08-17) -- confirmed pre-existing and unaffected by the sweep via identical failure signature before/after, filed as its own todo rather than folded into this plan's scope."
  - "SUMMARY status set to complete: all three tasks' live-infrastructure verification steps are now run and confirmed, per the checklist this SUMMARY previously recorded under 'Next Phase Readiness'."

patterns-established: []

requirements-completed: [CI-02, CI-03, CI-04]

coverage:
  - id: D1
    description: "health-check-nonprod: bounded 30x10s poll gating the workflow red on a dead nonprod stack (CI-04)"
    requirement: "CI-04"
    verification:
      - kind: other
        ref: "Static extraction against committed deploy.yml: job present, needs:/environment:/env: literals correct, 000 curl-failure sentinel present, exit 1 after loop, no concurrency:/continue-on-error:, nonprod hostname only -- all checked and passed pre-commit (see Task Commits below)"
        status: pass
      - kind: other
        ref: "Live green path: runs 32233904310 and 32236428721 both report 'Nonprod healthy after 2/30 attempts'. Live red path: NONPROD_HEALTH_URL temporarily pointed at an unreachable .invalid host (run 32235116988) -- poll exhausted all 30 attempts, emitted ##[error]Nonprod did not answer 200 within 30 attempts (bound: 300s elapsed)..., job conclusion failure, run conclusion failure. URL reverted (commit 406893c) and re-verified green."
        status: pass
    human_judgment: true
    rationale: "The plan's own acceptance criteria required observing both a real green run and a deliberately-induced red run against the live nonprod stack -- both now confirmed live by the human operator after merge to master."
  - id: D2
    description: "cleanup-old-images-nonprod / cleanup-unused-image-nonprod: nonprod's own image-retention pair, isolated to its own Docker Hub repository (CI-03)"
    requirement: "CI-03"
    verification:
      - kind: other
        ref: "Static extraction: both jobs present, needs:/if: conditions correct, repository-name isolation proven positively (base_image_name_nonprod is the only interpolation in both blocks; base_image_name is the only interpolation in production's two blocks), URL-vs-interpolation-count formula holds (hub+registry URL occurrences == base_image_name_nonprod occurrences minus one), both already-fixed Docker Hub bugs (JWT login-token exchange, next-link pagination) present -- all checked and passed pre-commit"
        status: pass
      - kind: other
        ref: "Live idempotency: run 32236428721 re-run via 'gh run rerun' against the identical commit/tag -- cleanup-old-images-nonprod produced zero 'Deleting tag:' lines, FAILED=0, exit 0. Live cross-repository isolation: public Docker Hub tags API confirmed both kanban-board-backend and kanban-board-backend-nonprod list exactly one tag each, the current short SHA (406893c)."
        status: pass
    human_judgment: true
    rationale: "The plan's own acceptance criteria required a live re-run against real Docker Hub repositories to prove idempotency and cross-repository isolation -- both now confirmed live by the human operator after merge to master."
  - id: D3
    description: "Task 1: repository-level deploy secret sweep, leaving NVD_API_KEY as the sole repository-scoped secret (CI-02)"
    requirement: "CI-02"
    verification:
      - kind: other
        ref: "Mechanical precondition re-check: the set of deploy.yml jobs interpolating secrets. is a subset of the set declaring environment: (verified against the file including this plan's own Task 2/3 additions); docs/INFRA_RUNBOOK.md's original secret inventory table annotated recording the pending sweep"
        status: pass
      - kind: other
        ref: "gh secret delete run on the nine repository-level deploy secrets plus one unreferenced orphan (NONPROD_RESET_TOKEN, deleted after explicit operator confirmation). gh secret list now returns exactly NVD_API_KEY. Live push-to-master proof: run 32233904310 green end to end across both deploy paths immediately after the sweep. security-scan.yml checked separately -- confirmed pre-existing failure (predates the sweep by two days), not a regression; filed as its own todo."
        status: pass
    human_judgment: true
    rationale: "Deleting live repository secrets is rated costly reversibility (GitHub secrets are write-only; a deleted repository secret cannot be recovered) and its required proof is a live push to origin/master observed in real time -- both now completed by the human operator after merge to master, per this session's own established pattern (see Plan 09-01's identical 'Task 3 deliberately deferred' precedent)."

# Metrics
duration: ~55min (worktree) + ~50min (live verification)
completed: 2026-08-19
status: complete
---

# Phase 09 Plan 02: Nonprod CI health gate and image-retention pair (CI-03/CI-04), CI-02 sweep complete — Summary

**`health-check-nonprod` (bounded 30x10s poll, fails the run on a dead nonprod stack), the nonprod image-retention pair (`cleanup-old-images-nonprod`/`cleanup-unused-image-nonprod`, isolated to their own Docker Hub repository), and the repository-level deploy-secret sweep (CI-02) are all written, wired into `deploy.yml`, and now live-verified end to end — green path, red path, idempotency, and cross-repository isolation all confirmed against real infrastructure by the human operator after the orchestrator merged this plan's worktree to `master`.**

## Performance

- **Duration:** ~55 min (worktree setup/reading ~15 min; deploy.yml/runbook authoring and static verification ~20 min; two Windows Gradle/Testcontainers pre-commit-hook recoveries, matching the recurring issue Plan 09-01 also hit, ~20 min)
- **Started:** 2026-08-19T08:05:00Z (approx)
- **Completed:** 2026-08-19T09:00:00Z (approx) — file-level work only; live verification remains
- **Tasks:** 3 of 3 attempted; all 3 partially complete (file-level deliverable done and statically verified, live-infrastructure proof deferred)
- **Files modified:** 2 (`.github/workflows/deploy.yml`, `docs/INFRA_RUNBOOK.md`) plus this SUMMARY.md

## Accomplishments

- **Task 2 (`health-check-nonprod`, CI-04):** Added as a separate job (not a step inside `deploy-to-nonprod`, to avoid holding the `deploy-to-nonprod-vm` concurrency lock for up to 300s of pure waiting). Polls `https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health` up to 30 attempts x 10s, using the house `curl -s -o ... -w "%{http_code}"` idiom with a `"000"` fallback on outright connection failure, printing a status line per attempt, exiting 0 on the first `200`, and emitting `::error::` + exiting non-zero when the bound is exhausted. No `continue-on-error`, no `|| true`. The 300s bound and its arithmetic (Compose `start_period`/`interval`/`retries` on both `redpanda-nonprod` and `app-nonprod`, plus image-pull/Flyway/DNS-TLS margin) is documented inline and in the runbook.
- **Task 3 (`cleanup-old-images-nonprod` / `cleanup-unused-image-nonprod`, CI-03):** Both added as line-for-line copies of production's `cleanup-old-images`/`cleanup-unused-image` with exactly one axis changed throughout — every Docker Hub URL and Registry API scope parameter interpolates `needs.setup.outputs.base_image_name_nonprod`, never hand-typed. `cleanup-old-images-nonprod` is gated on `health-check-nonprod` (D-05, a strictly stronger retention gate than production has). `cleanup-unused-image-nonprod` mirrors production's `needs:` shape literally for parity (D-06) and adds a deliberate improvement over its production original: a `::warning::`-only HTTP status check on the manifest `DELETE`, which production's own copy performs no check on at all. Both already-fixed Docker Hub bugs (the 2026-08-16 JWT-login fix, the 2026-08-17 `next`-link pagination fix) are inherited in their fixed form.
- **Task 1 (repository secret sweep, CI-02) — file-level portion only:** Re-verified mechanically that every job in `deploy.yml` interpolating `secrets.` (including this plan's own three new jobs) declares an `environment:` — holds. Annotated `docs/INFRA_RUNBOOK.md`'s original "Repository secret inventory" table (not deleted — the file's own established convention of recording corrections beside superseded text) with the pending sweep and its reasoning.
- **`docs/INFRA_RUNBOOK.md`:** New section "Nonprod CI health gate and image retention — Plan 09-02" documents the plan's final `deploy.yml` job graph (both vertical paths sharing only `setup`/`build-and-push-docker-image`), the health-poll bound's full arithmetic, the retention semantics and the deliberate health-gated/deploy-gated asymmetry between the two nonprod cleanup jobs, and an explicit "What remains" section naming every live-verification step still outstanding.

## Task Commits

Each task was committed atomically:

1. **Task 2: `health-check-nonprod`** - `195a7bb` (feat)
2. **Task 3: `cleanup-old-images-nonprod` / `cleanup-unused-image-nonprod` + runbook section** - `8d9c097` (feat)
3. **Task 1: repository secret inventory annotation** - `6d889db` (docs)

**Plan metadata:** this SUMMARY.md, committed immediately after `6d889db`.

_Note: no `test`/`refactor` commits — this plan is CI configuration and infra documentation, not application code; the project's own `spotlessCheck`+`fastTest` pre-commit hook ran clean on every commit (twice recovering from the same Windows Gradle/Testcontainers file-lock issue Plan 09-01 also documented, see Issues Encountered)._

## Files Created/Modified

- `.github/workflows/deploy.yml` - added `health-check-nonprod` (Task 2); added `cleanup-old-images-nonprod` and `cleanup-unused-image-nonprod` (Task 3)
- `docs/INFRA_RUNBOOK.md` - new section "Nonprod CI health gate and image retention — Plan 09-02" (Task 3); annotated "Repository secret inventory" table (Task 1)
- `.planning/phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-02-SUMMARY.md` - this file

## Decisions Made

**This plan's live-infrastructure steps were deliberately deferred out of this worktree, matching an existing project precedent, not invented for this plan.** Task 1 Part B (`gh secret delete` on nine repository secrets, rated `costly` reversibility by the plan itself), Task 1 Part C (a live push to `origin/master` plus observing the resulting run), Task 2's live green/red-path proof (including deliberately breaking and then restoring nonprod's reachability), and Task 3's live idempotency/cross-repository-isolation proof all require either an irreversible live action on the real GitHub secret store or a commit landing on `master` and a live GitHub Actions run being observed in real time. None of that is possible from an unmerged, isolated git worktree — and `docs/INFRA_RUNBOOK.md` already contains a section, written during Plan 09-01, documenting the identical conflict for that plan's own Task 3 ("Task 3 deliberately deferred — not run in this session": "This work was done inside an isolated git worktree — its commits live on a private per-agent branch until the orchestrator merges them back to `master`... that must happen under the human operator's direct observation, not fire unattended from a background agent inside a worktree."). This plan follows that exact precedent rather than re-deriving a different answer to the same conflict, and rather than attempting an irreversible action (deleting nine live production secrets) without the real-time observation this session has repeatedly required before every prior live-affecting action (see Plan 09-01's SUMMARY.md "Coordinator Remediation" section, where two live-run defects were fixed only after explicit operator go-ahead at each step).

**`status: halted`, not `complete` (frontmatter #2830 semantics).** This plan intentionally left tasks unfinished — a designed stop, not a failure — so 09-03 (which `depends_on: [09-02]`) is correctly reported as blocked by tooling until the remaining live steps below are run and this SUMMARY is re-authored with `status: complete`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Verify-script false positive] Reworded a runbook comment that accidentally inflated an acceptance-criteria interpolation count**
- **Found during:** Task 3, static verification of the `cleanup-old-images-nonprod` block against its own acceptance criteria (URL-occurrence-count formula)
- **Issue:** An inline shell comment explaining the repository-name-interpolation convention literally contained the string `base_image_name_nonprod` in prose, inflating the mechanical count check (`grep -o base_image_name_nonprod | wc -l`) by one and breaking the plan's own `hub+registry URL count == base_image_name_nonprod count - 1` formula, even though the actual code (the three real call sites) was correct.
- **Fix:** Reworded the comment to describe the convention without repeating the exact token, so it no longer appears as a spurious "call site" to the mechanical grep-based check.
- **Files modified:** `.github/workflows/deploy.yml`
- **Verification:** Re-ran the count formula after the edit — `hub=2 reg=2 base=5`, `2+2 == 5-1` — passes.
- **Committed in:** `8d9c097` (Task 3 commit — caught and fixed before commit, not a separate follow-up)

---

**Total deviations:** 1 auto-fixed (Rule 1, a wording-only fix to a comment, not to the workflow's actual behavior)
**Impact on plan:** None on shipped behavior — this was a comment-text collision with a mechanical string-count check, not a logic bug. No scope creep.

## Issues Encountered

**Windows Gradle/Testcontainers file-lock on the pre-commit hook (recurring issue, previously documented in Plan 09-01's SUMMARY.md).** The first commit attempt (Task 2) was killed by this tool's default timeout while `fastTest`'s Testcontainers-backed suite was still genuinely running (confirmed live via `docker ps` showing real Postgres + Redpanda containers, not a hang). This left orphaned `java.exe` processes holding Windows file handles open under `build/test-results/fastTest`. Resolved identically to Plan 09-01's documented recovery: identified and killed the four orphaned `java.exe` processes by PID, stopped and removed only the specific orphaned Testcontainers container names (leaving two unrelated long-running containers, `bitmagnet-gluetun`/`bitmagnet-pyroscope`, untouched — deliberately filtering by exact container name rather than repeating Plan 09-01's own documented `grep -v` ID-vs-name mistake), removed the now-unlocked `build/test-results/fastTest` directory, and retried the commit with an explicit longer timeout (540000ms). The retried commit succeeded (`195a7bb`); the two subsequent commits (`8d9c097`, `6d889db`) both completed quickly against Gradle's warm daemon and up-to-date task cache.

**Docker Desktop was not running at session start**, so the pre-commit hook's `gitleaks` secret scan failed with a Docker-daemon-unreachable error on the first commit attempt (a distinct failure mode from "a secret was found" — the hook correctly refused to silently pass). Started Docker Desktop, polled for readiness (~5s), and retried; the scan then ran and passed cleanly on all three commits.

## User Setup Required

None. All live-infrastructure steps below were completed by the human operator directly.

## Live Verification (2026-08-19, after merge to master)

All four steps this SUMMARY previously listed under "Next Phase Readiness" are now complete:

1. **Task 1 (CI-02) — repository secret sweep:** Nine plan-scoped repository secrets deleted (`gh secret delete`, no `--env`), plus one unreferenced orphan (`NONPROD_RESET_TOKEN`) found and deleted after explicit operator confirmation. `gh secret list --json name --jq '[.[].name]|join(",")'` now returns exactly `NVD_API_KEY`. Push immediately after (`08b253b`, run `32233904310`) confirmed both deploy paths green end to end. `security-scan.yml` checked separately: its `dependency-check` job was already failing on `NVD_API_KEY repository secret is not set` two days before this sweep (run `32001604789`, 2026-08-17) — confirmed pre-existing, unaffected by the sweep, filed as todo `2026-08-19-security-scan-yml-nvd-api-key-not-resolving.md` rather than folded into this plan.
2. **Task 2 (CI-04) — health-check-nonprod live proof:** Green path confirmed twice (`Nonprod healthy after 2/30 attempts`, runs `32233904310` and `32236428721`). Red path proven by temporarily pointing `NONPROD_HEALTH_URL` at an unreachable `.invalid` host (commit `5dc792a`, run `32235116988`, per the plan's own documented alternative rather than racing a live container stop/start) — full 300s bound exhausted, `##[error]Nonprod did not answer 200 within 30 attempts...`, job and run conclusion `failure`. Reverted (`406893c`) and re-verified green. Bonus: the same red-path run live-proved `cleanup-old-images-nonprod`'s `if: success()` gate on `health-check-nonprod` — it correctly stayed `skipped`.
3. **Task 3 (CI-03) — retention idempotency and isolation:** Run `32236428721` re-run via `gh run rerun` against the identical already-deployed commit/tag — `cleanup-old-images-nonprod` produced zero `Deleting tag:` lines, `FAILED=0`, exit 0. Both Docker Hub repositories confirmed via the public tags API to list exactly one tag each — the current short SHA (`406893c`) — proving cross-repository isolation.
4. `docs/INFRA_RUNBOOK.md`'s "What remains" section rewritten as "Live verification" with the observed results above; the health-poll timing note updated with real measured attempt counts; the secret-inventory section updated with the sweep's actual `gh secret list` result. This SUMMARY re-authored with `status: complete` and every `coverage[].verification[]` entry updated to `status: pass`.

09-03 (Wave 3) is now unblocked — it is `autonomous: false` and, per `.continue-here.md`, expected to hit its own blocking checkpoint independent of this one.

---
*Phase: 09-nonprod-continuous-deploy-scoped-ci-credentials*
*Completed: 2026-08-19 — file-level deliverables for all 3 tasks committed, and all live-infrastructure verification (CI-02 sweep, CI-03 idempotency/isolation, CI-04 green/red paths) completed by the human operator after merge to master*

## Self-Check: PASSED

- FOUND: `.github/workflows/deploy.yml`
- FOUND: `docs/INFRA_RUNBOOK.md`
- FOUND: `.planning/phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-02-SUMMARY.md`
- FOUND commit `195a7bb` (Task 2: health-check-nonprod)
- FOUND commit `8d9c097` (Task 3: nonprod retention pair + runbook)
- FOUND commit `6d889db` (Task 1: secret inventory annotation)
