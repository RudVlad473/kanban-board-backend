---
phase: 08-isolated-nonprod-environment-live-and-resettable
plan: 03
subsystem: infra
tags: [docker-compose, redpanda, memory-tuning, seastar, live-measurement, netcup]

# Dependency graph
requires:
  - phase: 08-01
    provides: the live, isolated nonprod stack (docker-compose.nonprod.yml, redpanda-nonprod, the public nonprod hostname) this plan measures against
  - phase: 08-02
    provides: the profile-gated POST /api/admin/reset endpoint used to reset nonprod to a known-empty baseline before every burst in this plan
provides:
  - A measured, no-longer-provisional --memory 128M / mem_limit 300m pair for redpanda-nonprod, replacing plan 08-01's provisional 1G/1200m values
  - A full live iteration ladder (1G down to 128M all proven healthy-and-burst-surviving; 96M/64M/32M proven crash-looping with verbatim Seastar allocation-failure/SIGSEGV evidence) recorded in docs/INFRA_RUNBOOK.md
  - The D-07 colocation-vs-fallback decision, made by the developer at a blocking checkpoint and recorded with its supporting figures -- no second VPS provisioned
  - Live proof that both stacks coexist on the single Netcup VPS without production degrading (health 200 throughout ~15 restart cycles, host available memory never below 5.8GiB)
affects: []

# Actuals (#2632)
actuals:
  tokens: 6600
  tasks: 4
  commits: 3

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Continuous RestartCount monitoring (not a single point-in-time docker ps/healthcheck read) when validating container stability near a memory boundary -- a container can restart into a brief healthy window and be caught mid-window by a single check, producing a false-positive pass; 96M in this plan's own ladder did exactly that (RestartCount=22 discovered only once monitored through a full app-nonprod recreate + burst cycle)"
    - "Force-recreate (not a bare `up -d`) for a dependent service after its upstream broker is recreated -- Compose does not detect 'the broker instance changed' as a config diff, so app-nonprod would otherwise keep running against the destroyed container instance's stale connection"

key-files:
  created: []
  modified:
    - docker-compose.nonprod.yml
    - docs/INFRA_RUNBOOK.md

key-decisions:
  - "Descended past the plan's originally anticipated stop point (192M) because 192M, and then 128M on a shallow check, and then briefly 96M too, all appeared to survive -- continued down to 32M to find a genuine, reproducible failure (Seastar allocation failure -> SIGSEGV), then narrowed back up (64M, 96M) to pin the real boundary between 96M (fails under sustained load) and 128M (genuinely stable). The plan's own assumptions_and_open_items explicitly reserves the exact ladder rungs as Claude's discretion -- the boundary evidence is what's load-bearing, not hitting a specific pre-named rung."
  - "128M's first pass used only a single point-in-time docker ps check (the same shallow check every higher rung had also used) and was retroactively found insufficient once 96M's identical-looking check turned out to be a crash-loop caught mid-healthy-window. Re-verified 128M from a fresh --force-recreate with RestartCount monitored continuously through app-nonprod's own recreate, a full reset-and-burst cycle, and a 20-second post-burst delay before adopting it -- a stronger validation standard than every rung above it received, applied specifically because the false positive at 96M proved the shallow check insufficient near the true boundary."
  - "D-07 (stay-colocated vs. provision-fallback) was surfaced at a blocking checkpoint and resolved by the developer, not the agent, per CONTEXT.md D-07's explicit instruction that workflow.auto_advance does not apply to this checkpoint even though this project has auto_advance: true / mode: yolo configured. No VPS was provisioned."

requirements-completed: [NONPROD-06]

coverage:
  - id: D1
    description: "Nonprod's Redpanda memory floor is established by iterative live restart cycles against the real host under a real workload -- not arithmetic, not an idle reading"
    requirement: "NONPROD-06"
    verification:
      - kind: other
        ref: "docs/INFRA_RUNBOOK.md '## Nonprod resource measurement' '### Iteration ladder' -- 7 rungs (1G through 128M) each force-recreated, health-verified within start_period, and burst-survival-verified with a real 54-request burst through the public nonprod HTTPS API"
        status: pass
    human_judgment: false
  - id: D2
    description: "The adopted floor is backed by a failing step below it, exercised and recorded with verbatim failure evidence, not merely asserted"
    requirement: "NONPROD-06"
    verification:
      - kind: other
        ref: "docs/INFRA_RUNBOOK.md '### Step below the floor' -- 96M crash-loop (RestartCount=22, ExitCode=139) caught only via continuous monitoring after a shallow check false-positived; 64M and 32M crash-loop immediately with verbatim `seastar - Failed to allocate N bytes` / `Segmentation fault` output"
        status: pass
    human_judgment: false
  - id: D3
    description: "mem_limit exceeds --memory by a recorded, non-zero margin >= 150MiB, never numerically equal; every memory value carries an explicit unit suffix"
    requirement: "NONPROD-06"
    verification:
      - kind: other
        ref: "docker-compose.nonprod.yml redpanda-nonprod: --memory 128M / mem_limit: 300m (172MiB margin), both with explicit M/m suffixes"
        status: pass
    human_judgment: false
  - id: D4
    description: "Both stacks coexist on the host without production degrading -- host available memory stays >= 1024MiB under simultaneous burst, production's own caps are untouched, and production's health endpoint stays 200 throughout every restart cycle"
    requirement: "NONPROD-06"
    verification:
      - kind: other
        ref: "docs/INFRA_RUNBOOK.md '### Host coexistence' -- free -m available never below 5.8GiB across 12 readings this session; git diff --name-only HEAD -- docker-compose.prod.yml empty; production health 200 across ~15 restart cycles (one unrelated transient 502 during an external CI/CD redeploy, investigated and confirmed not caused by this measurement)"
        status: pass
    human_judgment: false
  - id: D5
    description: "The second-VPS fallback decision was made by the developer at a blocking checkpoint, on the measured figures, not taken unattended by the agent"
    requirement: "NONPROD-06"
    verification: []
    human_judgment: true
    rationale: "D-07 is inherently a human, real-money decision by design (CONTEXT.md D-07, gate=\"blocking\", exempt from auto_advance) -- the plan requires a human to make this specific call, so it cannot be auto-verified by tests; recording that it happened correctly (developer selected stay-colocated, on the recorded figures, nothing provisioned) is itself the evidence, captured in docs/INFRA_RUNBOOK.md's '### D-07 decision outcome'."

# Metrics
duration: ~65min (including a pause for the D-07 blocking checkpoint)
completed: 2026-08-18
status: complete
---

# Phase 8 Plan 3: Isolated Nonprod Environment, Live and Resettable Summary

**redpanda-nonprod's memory cap replaced with a live-measured floor (`--memory 128M` / `mem_limit 300m`), found by descending a restart ladder past three independently-confirmed crash-looping rungs (96M/64M/32M) rather than stopping at an assumed value, with the colocation-vs-fallback-VPS decision made by the developer at a blocking checkpoint and recorded on the measured evidence.**

## Performance

- **Duration:** ~65 min total (Tasks 1-2 continuous measurement; a pause at Task 3's blocking `checkpoint:decision` for the developer's D-07 call; Task 3/4 resolution and write-up after the decision returned)
- **Completed:** 2026-08-18T14:52:22Z
- **Tasks:** 4 of 4
- **Files modified:** 2 (`docker-compose.nonprod.yml`, `docs/INFRA_RUNBOOK.md`)

## Accomplishments

- **Task 1 (baseline):** Established idle and under-burst reference figures for both stacks together at
  the provisional `1G`/`1200m` caps. The official burst record (a third, clean run) completed 56/56
  `2xx` with production health `200` throughout; a transient `502` on an earlier attempt was
  investigated live and attributed to an unrelated, concurrent external CI/CD redeploy of production's
  own `app` container (`docker inspect` confirmed a clean `RestartCount=0`/`ExitCode=0`/`OOMKilled=false`
  recreate, not a crash) -- documented rather than silently discarded.
- **Task 2 (ladder descent):** Descended `--memory` through `1G` (reused Iteration 0), `768M`, `512M`,
  `384M`, `256M`, `192M`, `128M` -- every rung force-recreated `redpanda-nonprod` and `app-nonprod`,
  reset nonprod, re-ran the identical 54-request burst, and polled production's health after every
  cycle. Went past the plan's originally anticipated stop point once `192M` and an initial shallow
  check of `128M` both appeared to succeed, down to `32M` to find a genuine, reproducible Seastar
  allocation-failure/SIGSEGV crash, then narrowed back up (`64M`, `96M`) to pin the real boundary. Found
  that `96M` looked healthy on the same shallow single-point check every higher rung had passed, but was
  actually crash-looping (`RestartCount=22`, `ExitCode=139`) once monitored continuously -- re-verified
  `128M` from scratch with continuous `RestartCount` monitoring through a full recreate, burst, and
  20-second post-burst delay before adopting it. Committed `--memory 128M` / `mem_limit 300m` (172MiB
  margin) with a `MEASURED BASIS` comment; production's own caps never touched
  (`docker-compose.prod.yml` diff empty).
- **Task 3 (D-07 checkpoint):** Surfaced the colocation-vs-fallback-VPS decision at a blocking
  `checkpoint:decision`, presenting the measured floor, the failing step below it, peak RSS as a
  fraction of the adopted cap (~19.1%), host headroom (never below 5.8GiB, against the 1024MiB gate),
  and production's health record. Did not resolve or guess the decision -- `workflow.auto_advance`
  explicitly does not apply to this checkpoint per `08-CONTEXT.md` D-07, even though this project runs
  `auto_advance: true` / `mode: yolo`.
- **Task 4 (stay-colocated):** Developer selected `stay-colocated`. Recorded the outcome, the date, and
  that the developer (not the agent) selected it, on the figures already on record. Nothing was
  provisioned -- no VPS, no new DNS record, no new recurring cost. Re-confirmed the end state one final
  time: both public health endpoints `200`, `docker stats` for all five containers at rest, `free -m`
  at rest, the adopted caps as committed, and the reset endpoint still returning `204` for the correct
  token. Added an operator note on what would re-open this decision (a materially heavier nonprod
  workload, a production cap increase, or a Redpanda version bump) and that re-opening means re-running
  Task 2's ladder, not adjusting the value by judgement.

## Task Commits

Each task was committed atomically:

1. **Task 1: Baseline and burst -- measure both stacks together at the provisional caps** -
   `6e492b9` (docs)
2. **Task 2: Descend the ladder to a real floor, prove the step below it fails, and commit the
   measured caps** - `123e3b7` (feat)
3. **Task 3/4: Record the D-07 stay-colocated decision and re-confirm the end state** -
   `6c82647` (docs)

_Note: Task 3 (`checkpoint:decision`, `gate="blocking"`) itself produced no commit -- it paused
execution and returned the decision context to the orchestrator. The developer's `stay-colocated`
selection and Task 4's execution/write-up landed together in the third commit, since Task 4's entire
`stay-colocated` door is a documentation-only recording of an already-true state (nothing to
provision) plus a final live re-verification._

## Files Created/Modified

- `docker-compose.nonprod.yml` - `redpanda-nonprod.command`'s `--memory` and `mem_limit` replaced with
  the measured `128M`/`300m` pair; provisional comment replaced with a `MEASURED BASIS` block
  recording the ladder, the adopted floor, and the failing step below it, citing the runbook section
  holding the full log
- `docs/INFRA_RUNBOOK.md` - New `## Nonprod resource measurement -- Plan 08-03` section: workload
  shape, `### Iteration 0`, `### Iteration ladder`, `### Adopted floor`, `### Step below the floor`,
  `### Host coexistence`, and `### D-07 decision outcome` (with the Task 4 end-state re-confirmation
  and operator note)

## Decisions Made

- Went past the plan's originally anticipated ladder stop point (`192M`) because this app's real
  traffic shape is too light to stress Redpanda's memory at any of the plan's originally-listed rungs
  -- continued descending to find a genuine, reproducible failure rather than fabricating one at a
  pre-named value. The plan's own `assumptions_and_open_items` explicitly reserves exact ladder rungs
  as Claude's discretion; the boundary evidence is what's load-bearing.
- Re-verified `128M` with a stronger validation standard (continuous `RestartCount` monitoring through
  a full recreate/burst/20s-delay cycle) than every rung above it received, specifically because `96M`'s
  false positive on the same shallow check every higher rung had used proved that check insufficient
  near the true boundary. Applying the same shallow standard to `128M` without re-checking would have
  risked adopting an unverified value.
- D-07 was surfaced at a blocking checkpoint and resolved by the developer, not guessed or
  auto-selected, even though this project's config carries `auto_advance: true` / `mode: yolo` --
  `08-CONTEXT.md` D-07 explicitly exempts this checkpoint from that setting.

## Deviations from Plan

None in the Rule 1-4 sense (no bugs found, no missing critical functionality, no blocking issues, no
architectural changes) -- this is a live-infrastructure measurement task, not a code-implementation
task. The ladder descending past its originally-anticipated stop point, and `128M`'s re-verification
after `96M`'s false positive, are documented above under Decisions Made as within-scope executor
discretion the plan itself anticipated, not deviations from it.

## Issues Encountered

- **Task 1's burst was run three times before recording the official Iteration 0 record.** The first
  run completed cleanly but did not interleave a production-health poll during the burst window. The
  second run did interleave polling and caught a transient `502` on production, investigated live
  (`docker inspect` showed a clean `RestartCount=0`/`ExitCode=0` recreate to a new image tag, not a
  crash) and attributed to an unrelated, concurrent external CI/CD redeploy of production's `app`
  service -- not caused by this measurement's nonprod restart activity. A third, clean run (production
  confirmed stable beforehand, `200` on every one of 8+ polls during the burst) was taken as the
  official record so the recorded evidence would not conflate an unrelated event with this
  measurement's own findings.
- **A methodological gap was found and corrected mid-ladder.** `96M` passed the same single
  point-in-time health check every rung from `256M` down to `192M`/`128M`'s first pass had used, but
  was later found crash-looping (`RestartCount=22`) once monitored continuously. This meant `128M`'s
  own first-pass check -- taken with the same shallow method -- could not be trusted either, and it was
  re-verified from a fresh recreate with continuous `RestartCount` monitoring before being adopted.
  Documented in full in `docs/INFRA_RUNBOOK.md`'s `### Iteration ladder` subsection so a future reader
  understands why the validation depth differs between rungs.
- **Pre-commit hook timed out on the first commit attempt** (cold Gradle daemon, matching the pattern
  `docs/SESSION_LESSONS.md` already documents). Resolved per the documented recovery: `./gradlew
  --stop`, ran `fastTest` directly to confirm a clean pass (4m 32s, `BUILD SUCCESSFUL`), then retried
  the plain `git commit` -- succeeded.

## User Setup Required

None. All measurement iterations, resets, and the reset-token read were performed directly against
the live VM over the existing `netcup-prod` SSH alias; no new credentials, dashboards, or manual
configuration were introduced by this plan.

## Next Phase Readiness

- **NONPROD-06 is fully satisfied.** `redpanda-nonprod` carries a live-measured, no-longer-provisional
  floor (`128M`/`300m`) with a proven failing step below it, production's own caps are untouched, host
  coexistence is proven under simultaneous burst, and the fallback-VPS decision was made by the
  developer on the measured figures -- nothing was left as a documented-but-unexercised option.
- Phase 8 is now complete: plan 08-01 (isolated live stack), 08-02 (reset endpoint), and 08-03 (measured
  memory floor + D-07 decision) all closed. Phase 9 (CI automation) can build directly on a stable,
  measured nonprod stack rather than one still carrying provisional resource caps.
- No blockers. The operator note in `docs/INFRA_RUNBOOK.md`'s `### D-07 decision outcome` names the
  three conditions that would re-open this decision (heavier nonprod workload, a production cap
  increase, a Redpanda version bump) and requires re-running Task 2's ladder rather than adjusting the
  value by judgement if any of them occur.

---
*Phase: 08-isolated-nonprod-environment-live-and-resettable*
*Completed: 2026-08-18*

## Self-Check: PASSED

- FOUND: `docker-compose.nonprod.yml`
- FOUND: `docs/INFRA_RUNBOOK.md`
- FOUND: `.planning/phases/08-isolated-nonprod-environment-live-and-resettable/08-03-SUMMARY.md`
- FOUND commit: `6e492b9`
- FOUND commit: `123e3b7`
- FOUND commit: `6c82647`
