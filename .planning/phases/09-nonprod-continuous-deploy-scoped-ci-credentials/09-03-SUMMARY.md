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
  - "register-schemas-production job (deploy.yml): runs immediately after deploy-to-netcup, environment: production, no needs: edge to any nonprod job -- automates production's schema registration (CI-05) per Task 1's human-resolved checkpoint (option-a)"
  - "Nonprod schema registration inserted as a step inside deploy-to-nonprod's existing SSH script, between up -d redpanda-nonprod and up -d app-nonprod -- app-nonprod does not exist as a process until registration has succeeded"
  - "docs/INFRA_RUNBOOK.md section documenting both invocations, Task 1's decision/rationale, the exit-code chain, registry-URL isolation, and a consolidated 13-job graph/table for the whole deploy.yml pipeline"
  - "Both prior manual registration procedures (Plan 05-04 Task 1 step 3, Plan 08-01 step 6) annotated superseded-by, commands retained per this file's historical-record convention"
affects: []

# Actuals (#2632)
actuals:
  tokens: 5650
  tasks: 3
  commits: 4

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
        ref: "Live push, live GitHub Actions run, and live rpk registry subject list confirmation on both brokers -- NOT run from this worktree, deferred to the orchestrator/human operator after merge (see 'Live Verification' note below and docs/INFRA_RUNBOOK.md's 'Live verification -- pending' subsection)"
        status: unknown
    human_judgment: true
    rationale: "This deliverable's acceptance criteria require observing a real push, a real GitHub Actions run, an induced-and-reverted registry failure, and idempotency/independence measurements against the live production and nonprod registries -- none of which can run from an isolated, unmerged git worktree. Matches the identical human_judgment:true rationale Plans 09-01/09-02 recorded for their own live-infrastructure deliverables."
  - id: D2
    description: "Nonprod's schema registration is inserted into deploy-to-nonprod's own SSH script, strictly between the broker start and the app start, so app-nonprod cannot exist as a process before registration succeeds"
    requirement: "CI-05"
    verification:
      - kind: other
        ref: "Static extraction (sed -n 330,403p .github/workflows/deploy.yml): AvroSchemaRegistrar/PropertiesLauncher/http://redpanda-nonprod:8081 all present; line-number ordering confirmed strictly 'up -d redpanda-nonprod' (69) < 'AvroSchemaRegistrar' (72) < 'up -d app-nonprod' (74) within the extracted block; no continue-on-error/suppression -- all checked and passed before commit 21b1b07"
        status: pass
      - kind: other
        ref: "Live push, live GitHub Actions run, the deliberately-induced-and-reverted red-path proof (unreachable registry URL -> deploy-to-nonprod failure -> confirmed-unchanged kanban-nonprod-app container id), and idempotency/independence measurements -- NOT run from this worktree, deferred to after merge"
        status: unknown
    human_judgment: true
    rationale: "Identical reasoning to D1 -- the red-path proof in particular requires deliberately breaking and then restoring a live registry connection against the real nonprod stack, which this session's established pattern (Plans 09-01/09-02) reserves for the human operator after merge, not an unattended worktree agent."
  - id: D3
    description: "docs/INFRA_RUNBOOK.md documents both invocations, Task 1's decision, the exit-code chain, registry-URL isolation, and a consolidated 13-job graph; both manual procedures annotated superseded-by"
    requirement: "CI-05"
    verification:
      - kind: other
        ref: "Automated check (matching the plan's own Task 3 <verify> block): heading '## Automated Avro schema registration -- Plan 09-03' present; PropertiesLauncher count >= 4; register-schemas-production count >= 2; every job id defined in deploy.yml (13 total) found in the new section; git diff --name-only HEAD -- src/ empty; ./gradlew spotlessCheck passes -- all confirmed passing, see Task Commits below (commit 5296218)"
        status: pass
    human_judgment: false

# Metrics
duration: ~50min (checkpoint resolution + Task 2 implementation/verification + Task 3 documentation, across a continuation after the coordinator relayed the human operator's Task 1 decision)
completed: 2026-08-19
status: halted
---

# Phase 09 Plan 03: Automated Avro schema registration (CI-05) — Summary

**`register-schemas-production` (its own job, running immediately after `deploy-to-netcup` per the human operator's option-a decision) and a registration step inserted inside `deploy-to-nonprod`'s own SSH script (strictly between the broker start and the app start) both reuse `AvroSchemaRegistrar`/`PropertiesLauncher` verbatim to automate the last hand-run step in this project's deploy — all file-level work statically verified inside this isolated worktree; the live push/run/registry proofs remain for the orchestrator/human operator after merge.**

## Performance

- **Duration:** ~50 min total across this session — Task 1 checkpoint reached and halted (~15 min, including a recurring Windows Gradle/Testcontainers pre-commit-hook file-lock recovery), the coordinator relayed the human operator's option-a decision, then Task 2 (`deploy.yml` edits, static verification, commit) and Task 3 (`docs/INFRA_RUNBOOK.md` section, superseded-by annotations, commit) executed in the same continuation (~35 min)
- **Started:** 2026-08-19T~09:40:00Z (approx, per orchestrator dispatch)
- **Completed:** 2026-08-19T~10:05:00Z (approx) — file-level work only; live verification remains
- **Tasks:** 3 of 3 attempted; all 3 file-level deliverables complete and statically verified; live-infrastructure proof deferred (same pattern as 09-02's initial halt)
- **Files modified:** 2 (`.github/workflows/deploy.yml`, `docs/INFRA_RUNBOOK.md`) plus this SUMMARY.md

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

**None outstanding for the file-level work** — both `deploy.yml` and `docs/INFRA_RUNBOOK.md` changes are complete and statically verified.

**Outstanding for live verification — human/coordinator action required after this worktree merges to `master`,** per this phase's established live-infrastructure-deferral pattern (Plans 09-01/09-02):

1. **Green path:** after a real push, confirm `rpk registry subject list` inside `kanban-nonprod-redpanda` and inside production's `redpanda` each return 14 subjects, and both jobs' logs contain the registrar's own `Registered 14 Avro schemas against <url>` line naming the correct registry. Confirm the nonprod log shows the registrar's output line before the `up -d app-nonprod` output at runtime.
2. **Red path (deliberately induced, then reverted):** temporarily point the nonprod invocation at a registry URL that cannot answer, confirm `deploy-to-nonprod` goes red, and confirm on the VM that `kanban-nonprod-app`'s container id and image are unchanged from before that run. Revert and re-verify green.
3. **Idempotency:** capture each broker's `rpk registry subject list` and a spot-check subject's `GET /subjects/<name>/versions` before and after a run, then re-run the same commit and capture again — subject counts and version lists must be byte-identical across the re-run.
4. **Cross-broker independence:** confirm a nonprod-only re-run leaves production's subject version lists unchanged.

Once these four are run, `docs/INFRA_RUNBOOK.md`'s "Live verification — pending, deferred to after merge" subsection (in the new "Automated Avro schema registration — Plan 09-03" section) should be rewritten with the observed results, matching Plan 09-02's own "Live Verification" pattern, and this SUMMARY's `status` updated to `complete`.

## Next Phase Readiness

**File-level work for CI-05 is complete and statically verified; live-infrastructure proof is the only remaining gap**, matching the exact halt pattern Plan 09-02 itself went through before its own live-verification round. This is the last plan in Phase 9 (no further plans depend on 09-03 per `ROADMAP.md`) — once the four live-verification steps above are run and confirmed, Phase 9 itself should be ready to close out.

---
*Phase: 09-nonprod-continuous-deploy-scoped-ci-credentials*
*Completed: 2026-08-19 — file-level deliverables for all 3 tasks committed and statically verified; live-infrastructure verification (green/red path, idempotency, cross-broker independence) remains for the human operator after merge*

## Self-Check: PASSED

- FOUND: `.github/workflows/deploy.yml`
- FOUND: `docs/INFRA_RUNBOOK.md`
- FOUND: `.planning/phases/09-nonprod-continuous-deploy-scoped-ci-credentials/09-03-SUMMARY.md`
- FOUND commit `5e7bcc6` (Task 1: checkpoint halt)
- FOUND commit `21b1b07` (Task 2: register-schemas-production + nonprod registration step)
- FOUND commit `5296218` (Task 3: runbook section + superseded-by annotations)
