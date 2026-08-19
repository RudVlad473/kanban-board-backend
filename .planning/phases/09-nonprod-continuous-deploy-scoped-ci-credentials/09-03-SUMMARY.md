---
phase: 09-nonprod-continuous-deploy-scoped-ci-credentials
plan: 03
subsystem: infra
tags: [github-actions, avro, schema-registry, redpanda, ci-cd]

# Dependency graph
requires:
  - phase: 09-02
    provides: "health-check-nonprod, cleanup-old-images-nonprod/cleanup-unused-image-nonprod, and the repository-level deploy-secret sweep (CI-02) -- all live-verified complete, unblocking 09-03's own precondition"
provides:
  - "register-schemas-production job (deploy.yml): runs immediately after deploy-to-netcup, environment: production, no needs: edge to any nonprod job -- automates production's schema registration (CI-05) per Task 1's human-resolved checkpoint (option-a) -- live green path proven twice"
  - "Nonprod schema registration inserted as a step inside deploy-to-nonprod's existing SSH script, between up -d redpanda-nonprod and up -d app-nonprod -- app-nonprod does not exist as a process until registration has succeeded -- live green AND red path both proven, after fixing a real bug this verification uncovered (set -e, see Live Verification below)"
  - "docs/INFRA_RUNBOOK.md section documenting both invocations, Task 1's decision/rationale, the exit-code chain, registry-URL isolation, and a consolidated 13-job graph/table for the whole deploy.yml pipeline -- updated post-verification with real observed results"
  - "Both prior manual registration procedures (Plan 05-04 Task 1 step 3, Plan 08-01 step 6) annotated superseded-by, commands retained per this file's historical-record convention"
affects: []

# Actuals (#2632)
actuals:
  tokens: 5650
  tasks: 3
  commits: 10

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Nonprod registration as a step inside an existing deploy job's SSH script (not a separate job) specifically to make CI-05's pre-traffic guarantee literal -- the app process cannot exist until registration succeeds, mirroring the broker-before-app ordering Phase 8's manual bring-up already proved"
    - "Production registration as its own job after the deploy, deliberately NOT gating app start -- a recorded asymmetry (Task 1, option-a) preserving a live, proven deploy script's existing behavior rather than introducing a new production failure mode (registry outage blocking an otherwise-successful deploy)"
    - "Live-infrastructure-affecting plan steps (push-to-master, live GitHub Actions run observation, SSH into the live VM to run the registrar against real registries) deferred out of an isolated git worktree to the merged tree under direct human/coordinator observation -- same pattern Plans 09-01/09-02 recorded"

key-files:
  created: []
  modified:
    - ".github/workflows/deploy.yml -- added register-schemas-production job; inserted the nonprod registrar invocation into deploy-to-nonprod's SSH script"
    - "docs/INFRA_RUNBOOK.md -- new section 'Automated Avro schema registration -- Plan 09-03'; superseded-by annotations on both manual registration procedures; updated nonprod operator note"

key-decisions:
  - "Task 1 checkpoint resolved by the human operator (relayed via the coordinator, not auto-selected): option-a -- register-schemas-production runs as its own job immediately after deploy-to-netcup, rather than restructuring deploy-to-netcup to gate app start on registration (option-b) or deferring to Phase 10 (option-c). Rationale given: strictly better than the status quo with zero behavior change to a live, proven production deploy script, and avoids introducing a new production failure mode (a registry outage blocking an otherwise-successful deploy) -- matches RESEARCH.md's own recommendation."
  - "The checkpoint was NOT auto-selected despite workflow.auto_advance:true and the checkpoint's own gate=\"blocking\" attribute (which would ordinarily be bypassed under auto-mode per checkpoints.md's Rule 5) -- the plan's own autonomous:false frontmatter, .continue-here.md's explicit flag that this exact checkpoint was expected, and the dispatch prompt's own instruction to stop and report all pointed the same direction: this is a genuine production-architecture decision, not a default an unattended executor should pick for the human."
  - "All three tasks' file-level (deploy.yml + docs) work was completed and statically verified inside an isolated worktree; the live-infrastructure actions each task's acceptance criteria also require (a live push to origin/master, observing a real GitHub Actions run, SSHing into the VM to confirm rpk registry subject counts and the induced-failure/idempotency proofs) were deliberately NOT executed from that worktree -- exactly the reasoning Plans 09-01 and 09-02 both recorded for the identical worktree/live-action conflict."
  - "The plan's own automated <verify> block for Task 2 hit the same awk range-extraction bug 09-01-SUMMARY.md already documented (a job-name line matches the range's own end pattern, so GNU awk closes the range immediately, extracting only the one-line job header). Worked around identically to 09-01's precedent: manual sed -n/grep -n extraction using real line numbers, all six acceptance-criteria checks confirmed passing against the real job bodies."
  - "One comment I initially wrote inside deploy-to-nonprod's SSH step literally contained the strings \"continue-on-error\", \"set +e\", and \"|| true\" in prose (describing what is NOT present), which inflated the plan's own mechanical suppression-check (grep -c \"continue-on-error\") from 0 to 1 -- the same class of false positive 09-02-SUMMARY.md documented and fixed. Caught and reworded before commit (see Deviations below), not left as a fragile pass."
  - "Live red-path verification (post-merge) uncovered a genuine correctness bug the static verification could not catch: the plan text's own claim that 'appleboy/ssh-action's shell fails the step on the first non-zero command exit' was wrong. appleboy/ssh-action has no built-in fail-fast behavior -- a failing docker compose run mid-script did NOT stop up -d app-nonprod from still running (confirmed live). First fix attempt (script_stop: true) was also wrong -- that input does not exist on this action version and was silently ignored, reproducing the identical bug on re-test. Root-caused by testing the exact command's exit code directly over SSH (confirmed exit 1 at the OS level), then fixed correctly with an explicit set -e as the script's first line, applied to all three appleboy/ssh-action steps in the file for defense-in-depth. Re-verified live a third time before the fix was accepted as done."
  - "A live-verification run (32247040963) hit an unrelated flaky test (ResetServiceE2ETest > should_emptyBothStores_when_resetAllCalledAfterRealTraffic, an AssertionFailedError amid dense Kafka consumer-rebalancing log noise) -- confirmed as flakiness, not a regression, by re-running only the failed job against the identical unchanged commit and observing a clean pass. Filed as its own todo rather than investigated further, since it is unrelated to this plan's file scope."

patterns-established: []

requirements-completed: [CI-05]

coverage:
  - id: D1
    description: "register-schemas-production job automates production's schema registration, running after deploy-to-netcup without gating its app start (Task 1, option-a)"
    requirement: "CI-05"
    verification:
      - kind: other
        ref: "Static extraction (sed -n 291,319p .github/workflows/deploy.yml): job present, environment: production, needs: names only deploy-to-netcup (plus build-and-push-docker-image for the tag output) -- no nonprod job referenced; script contains http://redpanda:8081; no continue-on-error/suppression in the block -- all checked and passed before commit 21b1b07"
        status: pass
      - kind: other
        ref: "Live: runs 32241094339 and its gh run rerun both logged 'Registered 14 Avro schemas against http://redpanda:8081'; rpk registry schema list on production's broker confirmed 14 subjects, all version 1, byte-identical before/after both runs (idempotency). register-schemas-production succeeded in every run this session, including the two red-path test runs targeting nonprod only (32242756450, 32245164097, 32246183734) -- proving cross-broker independence: production's registration never failed or was gated by nonprod's failures."
        status: pass
    human_judgment: true
    rationale: "This deliverable's acceptance criteria required observing a real push, a real GitHub Actions run, an induced-and-reverted registry failure (on nonprod), and idempotency/independence measurements against the live production and nonprod registries -- all now confirmed live by the human operator after merge. Matches the identical human_judgment:true rationale Plans 09-01/09-02 recorded for their own live-infrastructure deliverables."
  - id: D2
    description: "Nonprod's schema registration is inserted into deploy-to-nonprod's own SSH script, strictly between the broker start and the app start, so app-nonprod cannot exist as a process before registration succeeds"
    requirement: "CI-05"
    verification:
      - kind: other
        ref: "Static extraction (sed -n 330,403p .github/workflows/deploy.yml): AvroSchemaRegistrar/PropertiesLauncher/http://redpanda-nonprod:8081 all present; line-number ordering confirmed strictly 'up -d redpanda-nonprod' (69) < 'AvroSchemaRegistrar' (72) < 'up -d app-nonprod' (74) within the extracted block; no continue-on-error/suppression -- all checked and passed before commit 21b1b07"
        status: pass
      - kind: other
        ref: "Live green: runs 32241094339/32236428721-rerun both logged 'Registered 14 Avro schemas against http://redpanda-nonprod:8081'; nonprod's rpk registry schema list confirmed 14 subjects, all version 1, byte-identical across baseline and two runs (idempotency). Live red path, proven THREE times due to a real bug found mid-verification: attempt 1 (run 32242756450, no fix) and attempt 2 (run 32245164097, ineffective script_stop:true input -- not a real appleboy/ssh-action option, silently no-op'd) both showed app-nonprod recreated/started despite the registrar's confirmed exit code 1 -- the script continued past the failure. Root-caused live (docker compose run exits 1 at the OS level, verified directly via SSH) and fixed with an actual `set -e` as the script's first line (commit 9eca655). Attempt 3 (run 32246183734) then correctly failed: deploy-to-nonprod conclusion=failure, `##[error]Process completed with exit code 1`, health-check-nonprod/cleanup-old-images-nonprod correctly skipped, cleanup-unused-image-nonprod correctly fired, and `docker inspect kanban-nonprod-app`'s StartedAt timestamp was confirmed byte-identical before and after the run (container genuinely never touched)."
        status: pass
    human_judgment: true
    rationale: "Identical reasoning to D1 -- the red-path proof in particular requires deliberately breaking and then restoring a live registry connection against the real nonprod stack, which this session's established pattern (Plans 09-01/09-02) reserves for the human operator after merge, not an unattended worktree agent. This proof also uncovered and fixed a real defect the static verification could not have caught: appleboy/ssh-action has no fail-fast behavior of its own, so the plan's stated CI-05 guarantee was false as originally shipped, only becoming true after this live round added an explicit set -e."
  - id: D3
    description: "docs/INFRA_RUNBOOK.md documents both invocations, Task 1's decision, the exit-code chain, registry-URL isolation, and a consolidated 13-job graph; both manual procedures annotated superseded-by"
    requirement: "CI-05"
    verification:
      - kind: other
        ref: "Automated check (matching the plan's own Task 3 <verify> block): heading '## Automated Avro schema registration -- Plan 09-03' present; PropertiesLauncher count >= 4; register-schemas-production count >= 2; every job id defined in deploy.yml (13 total) found in the new section; git diff --name-only HEAD -- src/ empty; ./gradlew spotlessCheck passes -- all confirmed passing, see Task Commits below (commit 5296218)"
        status: pass
    human_judgment: false

# Metrics
duration: ~50min (worktree: checkpoint resolution + Task 2/3 implementation) + ~90min (live verification, extended by a real bug found and fixed mid-verification -- three red-path test rounds instead of one)
completed: 2026-08-19
status: complete
---

# Phase 09 Plan 03: Automated Avro schema registration (CI-05) — Summary

**`register-schemas-production` (its own job, running immediately after `deploy-to-netcup` per the human operator's option-a decision) and a registration step inserted inside `deploy-to-nonprod`'s own SSH script (strictly between the broker start and the app start) both reuse `AvroSchemaRegistrar`/`PropertiesLauncher` verbatim to automate the last hand-run step in this project's deploy — now live-verified end to end (green path, red path, idempotency, cross-broker independence), after live verification itself uncovered and fixed a real bug: `appleboy/ssh-action` has no fail-fast behavior of its own, so nonprod's "registration gates the app start" guarantee was false as originally shipped until an explicit `set -e` was added.**

## Performance

- **Duration:** ~50 min (worktree: Task 1 checkpoint halt, Task 2/3 implementation and static verification) + ~90 min (live verification by the human operator after merge — extended well past a normal round by a genuine defect discovered mid-verification, requiring three red-path test rounds instead of one)
- **Started:** 2026-08-19T~09:40:00Z (approx, per orchestrator dispatch)
- **Completed:** 2026-08-19T~11:35:00Z (approx) — file-level work, live verification, and the mid-verification bug fix are all complete
- **Tasks:** 3 of 3 complete, all live-verified
- **Files modified:** 2 (`.github/workflows/deploy.yml`, `docs/INFRA_RUNBOOK.md`) plus this SUMMARY.md and two new todos filed during live verification

## Accomplishments

- **Task 1 resolved:** human operator selected **option-a** (relayed by the coordinator, per this session's own established relay pattern — the checkpoint was deliberately not auto-selected despite `workflow.auto_advance: true`, see Decisions Made below). `register-schemas-production` runs as its own job after `deploy-to-netcup`; production's live deploy script is unchanged.
- **Task 2 (`register-schemas-production`, CI-05):** New job, `needs: [ deploy-to-netcup, build-and-push-docker-image ]`, `environment: production`, no `needs:` edge to any nonprod job. Single `appleboy/ssh-action` step running the documented `PropertiesLauncher` invocation against `http://redpanda:8081`. No concurrency block (production's VM lock is already released by the time this job starts, and the job neither copies files nor mutates the Compose project).
- **Task 2 (nonprod registration, CI-05):** Inserted directly into `deploy-to-nonprod`'s existing SSH script, between `up -d redpanda-nonprod` and `up -d app-nonprod` — confirmed by line-number ordering within the extracted job block (69 < 72 < 74). `app-nonprod` cannot exist as a process before registration succeeds; a registration failure aborts the script (no `continue-on-error`/error-suppressing flag anywhere near either invocation). Reuses the same nonprod-scoped deploy identity (`deploy-nonprod`, via the `staging` environment) the deploy already uses — no second credential surface.
- **Registry URL isolation confirmed:** exactly two distinct registry URLs exist in `deploy.yml` (`http://redpanda:8081`, `http://redpanda-nonprod:8081`), each appearing exactly once and confined to its own job's script block.
- **No source drift:** `git diff --name-only -- src/` is empty across both commits — `AvroSchemaRegistrar` is reused unmodified, matching CI-05's explicit "reusing the existing tool" clause and SCHEMA-01.
- **Task 3 (`docs/INFRA_RUNBOOK.md`):** New section "Automated Avro schema registration — Plan 09-03" records both invocations verbatim, Task 1's decision and rationale (including the deliberate nonprod/production asymmetry), the four-link exit-code chain that reddens the run on an incompatible schema, registry-URL isolation, the idempotency/cross-broker-independence properties `AvroSchemaRegistrar` already guarantees by construction (flagged as not-yet-live-measured against the CI path), and a consolidated table + ASCII graph naming all 13 `deploy.yml` jobs with their environment and vertical path — `build-and-push-docker-image` marked as the single shared node. Both prior manual registration procedures (production's "Manual deploy — Plan 05-04 Task 1" step 3, nonprod's "Nonprod bring-up — Plan 08-01" step 6) annotated superseded-by, commands retained per this file's own historical-record convention. The nonprod operator note updated to state CI now registers automatically.

## Task Commits

Each task was committed atomically:

1. **Task 1: checkpoint halt (decision unresolved)** - `5e7bcc6` (docs)
2. **Task 2: `register-schemas-production` job + nonprod registration step** - `21b1b07` (feat)
3. **Task 3: runbook section + superseded-by annotations + operator note update** - `5296218` (docs)

**Plan metadata:** this SUMMARY.md update (committed immediately after `5296218`)

_Note: no `test`/`refactor` commits — this plan is CI configuration and infra documentation, not application code; the project's own `spotlessCheck`+`fastTest` pre-commit hook ran clean on every commit (one recovery from the recurring Windows Gradle/Testcontainers file-lock issue Plans 09-01/09-02 also documented, see Issues Encountered)._

## Files Created/Modified

- `.github/workflows/deploy.yml` — added `register-schemas-production` job (Task 2); inserted the nonprod registrar invocation into `deploy-to-nonprod`'s SSH script (Task 2)
- `docs/INFRA_RUNBOOK.md` — new section "Automated Avro schema registration — Plan 09-03" (Task 3); superseded-by annotations on both manual registration procedures (Task 3); updated nonprod operator note (Task 3)
- `.planning/phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-03-SUMMARY.md` — this file

## Decisions Made

**Task 1 checkpoint resolved by the human operator, relayed via the coordinator:** **option-a** — `register-schemas-production` automates production's registration in place (its own job, immediately after `deploy-to-netcup`), rather than restructuring `deploy-to-netcup` itself to gate `up -d` on registration (option-b) or deferring the choice with a Phase 10 todo (option-c). Given rationale: strictly better than today's status quo with zero behavior change to a live, proven production deploy script, and avoids introducing a new production failure mode (a registry outage blocking an otherwise-successful deploy) while still reddening the run on a genuinely incompatible schema — matches `09-RESEARCH.md`'s own recommendation.

**The checkpoint was deliberately NOT auto-selected**, even though `workflow.auto_advance: true` is active in this project's config and the checkpoint's own `gate="blocking"` attribute (not `blocking-human`) would ordinarily be bypassed under `checkpoints.md`'s Rule 5 auto-mode behavior. Three independent signals pointed the same direction: the plan's own `autonomous: false` frontmatter, this phase's `.continue-here.md` explicitly flagging this exact checkpoint as Wave 3's expected stop point (mirroring 09-01's Task 1 precedent), and the dispatch prompt's own explicit instruction to stop and report. The decision genuinely changes production's live deploy behavior, which is exactly the class of decision this project's established session pattern reserves for a human, not an unattended default-select.

**All three tasks' file-level work was completed and statically verified inside this isolated worktree; the live-infrastructure actions each task's acceptance criteria also require were deliberately NOT executed from that worktree** — a live push to `origin/master`, observing a real GitHub Actions run, SSHing into the live VM to confirm `rpk registry subject list` counts, and the induced-failure/idempotency/cross-broker-independence proofs all require either a commit landing on `master` and a live run being observed in real time, or a live SSH session against the production VM. None of that is possible from an unmerged, isolated git worktree — this is the identical conflict Plans 09-01 and 09-02 both recorded and resolved the same way (see their own SUMMARY.md "Decisions Made"/"Task 3 deliberately deferred" sections).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Verify-script false positive] Reworded a comment that literally contained the mechanical suppression-check's search strings**
- **Found during:** Task 2, static verification of the `deploy-to-nonprod` block against its own acceptance criteria (the plan's own `grep -c "continue-on-error"` check)
- **Issue:** A comment I wrote above the SSH step, explaining why registration failures propagate correctly, described the absence of error suppression using the literal phrases `` `|| true`/`set +e`/`continue-on-error` `` — this inflated the mechanical count check from `0` to `1`, even though the actual invocation itself carries no suppression at all. The same class of false positive 09-02-SUMMARY.md documented (a comment's prose collided with a mechanical string-count check).
- **Fix:** Reworded the comment to describe the same property ("no error-suppressing flag of any kind is applied to that shell or to the registrar invocation") without repeating the exact literal tokens the check searches for.
- **Files modified:** `.github/workflows/deploy.yml`
- **Verification:** Re-ran the suppression check after the edit — `grep -c "continue-on-error" <block>` returns `0` for both the `register-schemas-production` and `deploy-to-nonprod` blocks. Confirmed no functional change (comment-only edit).
- **Committed in:** `21b1b07` (Task 2 commit — caught and fixed before commit, not a separate follow-up)

**2. [Rule 3 - Blocking, discovered not fixed] Task 2's own automated `<verify>` block hit the same awk range-extraction bug 09-01-SUMMARY.md already documented**
- **Found during:** Task 2, running the plan's own automated verify command before committing
- **Issue:** The plan's `awk '/^  deploy-to-nonprod:$/,/^  [a-z0-9-]+:$/'` extraction pattern closes its range on the very line that opened it (the job-name line itself matches the range's own end pattern), so the "extracted block" is a single line — every subsequent `grep -q` against it fails, not because the workflow is wrong but because the extraction never captured the job body. This is the identical bug (and identical root cause) 09-01-SUMMARY.md already diagnosed and recorded for this exact same awk pattern.
- **Fix:** Not applied to the plan text (out of this task's file scope — `<files>` is `.github/workflows/deploy.yml` only). Verified the same acceptance criteria manually instead, using `sed -n` with real line numbers derived from `grep -n "^  [a-z0-9-]*:$"` — all criteria (job presence, `environment:`/`needs:` shape, registry-URL content and isolation, ordering, absence of suppression) confirmed passing against the real job bodies.
- **Files modified:** None (verification-only finding, second confirmation of a pre-existing, already-documented issue).
- **Verification:** Manual extraction and all checks re-run and confirmed passing (see the "Static extraction" verification entries in `coverage:` above).
- **Committed in:** n/a (no code change; documented here to save a future session from re-diagnosing the same awk quirk a third time).

---

**Total deviations:** 2 (1 auto-fixed — Rule 1, a comment-wording collision with a mechanical check, caught before commit; 1 discovered-not-fixed — Rule 3 class, a second confirmation of 09-01's already-documented awk bug in the plan's own verify-script text)
**Impact on plan:** Neither affects the shipped `deploy.yml` or `docs/INFRA_RUNBOOK.md`. Both were caught during verification, not after; the underlying acceptance criteria are genuinely met, confirmed by equivalent manual extraction.

## Issues Encountered

**Windows Gradle/Testcontainers pre-commit-hook file-lock (recurring issue, previously documented in Plans 09-01/09-02's SUMMARY.md).** The first commit attempt of this session (the Task 1 checkpoint-halt SUMMARY.md commit) was killed by this tool's default 2-minute timeout while `fastTest`'s Testcontainers-backed suite was still genuinely running (confirmed live via `docker ps` — real Postgres containers, not a hang). Resolved identically to the prior two plans' documented recovery: identified and killed the orphaned `java.exe`/`Gradle Test Executor` process holding a Windows file handle open under `build/test-results/fastTest/binary`, removed the now-unlocked directory, and retried the commit with an explicit longer timeout (540000ms) — succeeded in `4m 56s`. Both subsequent commits (Task 2, Task 3) completed quickly against Gradle's warm daemon and up-to-date task cache (`fastTest UP-TO-DATE` both times, since neither touched `src/`).

## User Setup Required

None. All live-infrastructure steps below were completed by the human operator directly.

## Live Verification (2026-08-19, after merge to master)

All four steps this SUMMARY previously listed under "User Setup Required" are now complete, plus one unplanned round to fix a bug the verification itself found:

1. **Green path:** confirmed on runs `32241094339` (and its `gh run rerun`) — both jobs logged `Registered 14 Avro schemas against <url>` against the correct registry, and `rpk registry schema list` on both brokers confirmed 14 subjects, all version 1.
2. **Red path — first two attempts failed to reproduce a real failure, revealing a genuine bug:** pointing `deploy-to-nonprod`'s registrar invocation at an unreachable host (commit `449614e`, run `32242756450`) showed the registrar correctly throwing `IllegalStateException`, but `app-nonprod` was recreated and started anyway, and the job reported `success`. A first fix attempt (`script_stop: true`, commit `6956197`) reproduced the identical bug on retest (run `32245164097`) — that input does not exist on `appleboy/ssh-action@v1.2.5` and was silently ignored. Root-caused by testing the exact `docker compose run` command directly over SSH (confirmed exit code 1 at the OS level) and fixed correctly with an explicit `set -e` as the script's first line, applied to all three `appleboy/ssh-action` scripts in the file (commit `9eca655`). A third red-path attempt (run `32246183734`) then correctly failed: `deploy-to-nonprod` conclusion `failure`, `##[error]Process completed with exit code 1`, `health-check-nonprod`/`cleanup-old-images-nonprod` correctly skipped, `cleanup-unused-image-nonprod` correctly fired its D-06 cleanup, and `docker inspect kanban-nonprod-app`'s `StartedAt` timestamp confirmed byte-identical before and after — the container was genuinely never touched. URL reverted (commit `89fde91`) and re-verified green.
3. **Idempotency:** both brokers' subject/version lists stayed byte-identical across baseline → first registration → re-run.
4. **Cross-broker independence:** `register-schemas-production` and `deploy-to-netcup` succeeded fully independently in every red-path test run targeting nonprod only — production's registration was never gated by or affected by nonprod's failures.

A separate, unrelated flaky-test failure (`ResetServiceE2ETest`) surfaced during the final green-restoration run — confirmed as flakiness (re-run of the identical unchanged commit passed clean), filed as its own todo, not investigated further as out of this plan's scope.

`docs/INFRA_RUNBOOK.md`'s live-verification section rewritten with these observed results; this SUMMARY re-authored `status: complete` with every `coverage[].verification[]` entry updated to `pass`.

## Next Phase Readiness

CI-05 is fully live-verified. This is the last plan in Phase 9 (no further plans depend on `09-03` per `ROADMAP.md`) — Phase 9 is ready to close out.

---
*Phase: 09-nonprod-continuous-deploy-scoped-ci-credentials*
*Completed: 2026-08-19 — file-level deliverables for all 3 tasks committed, and all live-infrastructure verification (CI-05 green/red path, idempotency, cross-broker independence) completed by the human operator after merge to master, including discovering and fixing a real appleboy/ssh-action fail-fast defect mid-verification*

## Self-Check: PASSED

- FOUND: `.github/workflows/deploy.yml`
- FOUND: `docs/INFRA_RUNBOOK.md`
- FOUND: `.planning/phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-03-SUMMARY.md`
- FOUND commit `5e7bcc6` (Task 1: checkpoint halt)
- FOUND commit `21b1b07` (Task 2: register-schemas-production + nonprod registration step)
- FOUND commit `5296218` (Task 3: runbook section + superseded-by annotations)
- FOUND commit `9eca655` (live-verification fix: set -e, correcting the ineffective script_stop attempt)
