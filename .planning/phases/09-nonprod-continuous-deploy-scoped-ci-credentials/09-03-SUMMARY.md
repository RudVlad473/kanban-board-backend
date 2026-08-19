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
  - "Task 1's checkpoint:decision surfaced verbatim for the human operator -- no file changes yet"
affects: [09-03-nonprod-continuous-deploy-scoped-ci-credentials]

# Actuals (#2632)
actuals:
  tokens: 0
  tasks: 0
  commits: 0

# Tech tracking
tech-stack:
  added: []
  patterns: []

key-files:
  created: []
  modified: []

key-decisions: []

patterns-established: []

requirements-completed: []

# Metrics
duration: ~10min (reading context, precondition/checkpoint verification only -- no implementation work)
completed: 2026-08-19
status: halted
---

# Phase 09 Plan 03: Automated Avro schema registration (CI-05) — Summary

**Halted at Task 1's designed `checkpoint:decision` gate before any implementation work — the plan's own frontmatter (`autonomous: false`) and this phase's `.continue-here.md` both flagged this plan as expected to stop here, and the decision itself (whether production's live deploy script gates its app start on schema registration) is a genuine production-architecture choice, not one this executor should auto-select even though the checkpoint's `gate="blocking"` attribute would ordinarily be bypassed under this project's `workflow.auto_advance: true` setting.**

## Performance

- **Duration:** ~10 min (worktree branch/base verification, reading PLAN.md/STATE.md/config.json/.continue-here.md/09-01-SUMMARY.md/09-02-SUMMARY.md, confirming Task 1 is the first task and therefore nothing to execute before the checkpoint)
- **Started:** 2026-08-19T~09:40:00Z (approx, per orchestrator dispatch)
- **Completed:** n/a — halted, not completed
- **Tasks:** 0 of 3 completed
- **Files modified:** 0 (this SUMMARY.md only)

## Accomplishments

- Confirmed the worktree base matches the expected commit (`092ee368b7ad74b111d33f8a81cb3a178e89c5bc`) and HEAD is on the correct per-agent branch before any work began.
- Read all required context (09-03-PLAN.md in full, including `<design_alternatives>` and the full Task 1 checkpoint text; STATE.md; config.json; `.continue-here.md`; 09-01-SUMMARY.md and 09-02-SUMMARY.md for precedent on how this exact phase handles checkpoints and live-infrastructure deferral from inside a worktree).
- Verified Task 1 is genuinely the plan's first task — no prior `type="auto"` task exists to execute before the checkpoint is reached, so this halt happens at the very start of the plan with zero implementation performed.
- Deliberately did **not** auto-select an option despite `workflow.auto_advance: true` being active in this project's config, because:
  1. The plan's own frontmatter marks it `autonomous: false`.
  2. This phase's `.continue-here.md` explicitly names this exact checkpoint as the expected stop point for Wave 3, mirroring 09-01's Task 1 precedent (also a `checkpoint:decision`, also halted for the human operator in that session, per 09-01-SUMMARY.md's "Decisions Made" section).
  3. The decision genuinely changes production's live deploy behavior (whether `deploy-to-netcup` gates `up -d app` on schema registration succeeding) — a new production failure mode is on the table for two of the three options, which is exactly the class of decision this project's established session pattern requires a human to make, not an unattended default-select.

## Task Commits

None — no `type="auto"` task was reached before the checkpoint.

**Plan metadata:** this SUMMARY.md (halt record only, no code/docs changes)

## Files Created/Modified

None. `.github/workflows/deploy.yml` and `docs/INFRA_RUNBOOK.md` are untouched by this session — Task 2 and Task 3 (the tasks that touch those files) both come after Task 1's unresolved checkpoint.

## Decisions Made

None yet — that is precisely what this checkpoint is asking the human operator to provide. See "CHECKPOINT REACHED" below for the full decision context, options, and trade-offs as written in 09-03-PLAN.md.

## Deviations from Plan

None — plan execution stopped exactly where designed, before any deviation-triggering work occurred.

## Issues Encountered

None. This is a designed stop, not a failure.

## User Setup Required

**A decision is required before this plan can proceed to Task 2.** See "CHECKPOINT REACHED" below — select `option-a`, `option-b`, or `option-c` for whether production's schema registration gates `deploy-to-netcup`'s app start (RESEARCH.md Open Question 2).

## Next Phase Readiness

Blocked on Task 1's decision. Once resolved (by the human operator, via a fresh dispatch of this same plan with the decision recorded), Task 2 (the actual `.github/workflows/deploy.yml` edit — nonprod registration inserted into `deploy-to-nonprod`'s SSH script, plus `register-schemas-production` per Task 1's answer) and Task 3 (the `docs/INFRA_RUNBOOK.md` supersession/consolidation section) remain to be executed. Per 09-01/09-02 precedent, Task 2's live-only proof steps (a real push to observe the green run, deliberately inducing and reverting a registry-unreachable failure, and the idempotency/independence measurements) cannot be executed from an isolated, unmerged git worktree and must be deferred to the orchestrator/human operator after this plan's file-level work is merged to master — exactly as both prior waves in this phase recorded.

---
*Phase: 09-nonprod-continuous-deploy-scoped-ci-credentials*
*Completed: n/a — halted 2026-08-19 at Task 1's checkpoint:decision, before any implementation*

## CHECKPOINT REACHED

**Type:** decision
**Gate:** blocking
**Plan:** 09-03
**Progress:** 0/3 tasks complete

### Completed Tasks

| Task | Name | Commit | Files |
| ---- | ---- | ------ | ----- |
| — | (none — checkpoint is the first task) | — | — |

### Current Task

**Task 1:** Decide whether production's schema registration gates its app start (RESEARCH.md Open Question 2)
**Status:** awaiting decision
**Blocked by:** Human decision required — this is a production-architecture choice, not an implementation detail

### Checkpoint Details

**Decision:** CI-05 says registration must complete "before that environment's app serves traffic." Nonprod, newly automated with no traffic to protect, will get that guarantee literally (Task 2 inserts nonprod's registration between `up -d redpanda-nonprod` and `up -d app-nonprod` inside `deploy-to-nonprod`'s own SSH script — this part is not in question and will happen regardless of Task 1's answer). Production's deploy path is live and proven, and today it serves traffic before any registration happens at all — registration has been a hand-run post-deploy step since v1.2 Phase 5. Should this phase automate production's registration **in place** (after the deploy, matching today's ordering) or **restructure production's deploy script** so registration gates the app start?

**Context:** Today's production deploy script is `pull app` then a bare `docker compose up -d`. Schema registration has never been part of it — `docs/INFRA_RUNBOOK.md`'s "Manual deploy — Plan 05-04 Task 1" step 3 records it as a separate, hand-run one-off container. So the unregistered window already exists in production and currently lasts until an operator remembers the runbook.

Both options remove the operator from the loop and both make an incompatible schema fail the run. They differ in whether production's app is allowed to start before registration has succeeded.

Note that option-b's structural change would also mean production's deploy fails when the schema registry is unreachable, on a path where the registry has never been a deploy precondition — a new failure mode in exchange for closing a window that is currently seconds wide instead of hours.

**Options:**

1. **option-a — Automate in place:** `register-schemas-production` runs as its own job immediately after `deploy-to-netcup`.
   - **Pros:** Strictly better than today with zero behaviour change to a live, proven deploy script. The unregistered window shrinks from "until someone runs the runbook step" to "one CI job's startup latency." An incompatible schema still reddens the run. It is RESEARCH.md's own recommendation and the non-regressing reading. Production's deploy remains independent of registry availability.
   - **Cons:** Production does not literally satisfy CI-05's "registration completes before that environment's app serves traffic" clause — the window is small but real, and the asymmetry with nonprod must be documented so it is not read later as an oversight.

2. **option-b — Restructure production's deploy:** `pull app` → `up -d redpanda` → register → `up -d app`.
   - **Pros:** Uniform treatment of both environments and the literal CI-05 guarantee everywhere. No asymmetry to explain in the runbook.
   - **Cons:** Changes production's live deploy script into an ordering the pipeline has never executed, replacing a single bare `up -d` with an explicit multi-service sequence. Introduces a new production failure mode: a schema-registry problem now blocks a deploy that would otherwise have succeeded. Enlarges this phase's production blast radius at the same time as the credential retrofit landed.

3. **option-c — Option A now, with a Phase 10 todo filed** to revisit the production ordering alongside the other CI-hardening work.
   - **Pros:** Ships the automation now on the low-risk path and keeps the stronger guarantee on the record as scheduled work rather than as an accepted gap. Phase 10 already owns `deploy.yml` hardening, so the change would land with the digest-pinning pass rather than as a one-off.
   - **Cons:** Leaves the window open for one more phase; adds a todo that may or may not be prioritised.

### Awaiting

Select `option-a`, `option-b`, or `option-c`. If `option-b`, Task 2's production half changes from a separate `register-schemas-production` job to a restructured `deploy-to-netcup` script (`up -d redpanda` → register → `up -d app`) and its acceptance criteria change accordingly — this would need to be reflected before Task 2 is dispatched.

Once selected, resume this plan (fresh dispatch, since worktree agents are not resumed mid-session) starting at Task 2 with the decision recorded.
